package destiny.renderer.mixin;

import destiny.renderer.chunk.DeferredRebuildQueue;
import destiny.renderer.config.RendererConfig;
import net.minecraft.client.render.chunk.ChunkBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Spreads non-urgent chunk rebuilds across frames.
 *
 * <p>Excess rebuilds are handed to {@link DeferredRebuildQueue} and re-submitted on later
 * frames. They are never discarded — an earlier version simply cancelled them, which left
 * sections holding stale geometry and produced blocks with invisible sides after
 * explosions.
 *
 * <p>Urgent rebuilds (block placement, breaking, explosions applying block changes) are
 * always immediate so the world stays responsive to your actions.
 */
@Mixin(ChunkBuilder.BuiltChunk.class)
public abstract class ChunkUpdateThrottleMixin {

    private static int  destinyrenderer$frameBudget = 0;
    private static long destinyrenderer$frameMarker = -1L;

    @Inject(method = "scheduleRebuild(Z)V", at = @At("HEAD"), cancellable = true)
    private void destinyrenderer$throttle(boolean important, CallbackInfo ci) {
        RendererConfig cfg = RendererConfig.get();
        if (!cfg.deferChunkUpdates) return;

        // Player-caused and explosion-driven changes must apply immediately, otherwise
        // the world visibly lags behind what just happened.
        if (important) return;

        // On a server, deferring geometry means you can be shot through a wall that has
        // already been broken, or fail to see a hole someone just opened. Anything that
        // delays what you see relative to what the server thinks is true is a competitive
        // liability, so multiplayer never defers unless it is explicitly allowed.
        if (!cfg.deferChunkUpdatesInMultiplayer && destinyrenderer$isMultiplayer()) return;

        // Block edits directly in front of the player are almost certainly something the
        // player just did or is looking at. Deferring them is visible lag — a hole you
        // broke that takes several frames to appear. Sections within the near radius are
        // always rebuilt immediately, whatever the frame budget says.
        if (destinyrenderer$isNearPlayer()) return;

        long frame = destiny.renderer.hud.PerformanceOverlay.frameCounter();
        if (frame != destinyrenderer$frameMarker) {
            destinyrenderer$frameMarker = frame;
            destinyrenderer$frameBudget = 0;
        }

        if (++destinyrenderer$frameBudget <= cfg.maxChunkUpdatesPerFrame) {
            return; // within budget for this frame
        }

        // Over budget: defer rather than drop. Only cancel if the queue accepted it.
        ChunkBuilder.BuiltChunk self = (ChunkBuilder.BuiltChunk) (Object) this;
        if (DeferredRebuildQueue.defer(self)) {
            ci.cancel();
        }
    }

    @org.spongepowered.asm.mixin.Shadow
    public abstract net.minecraft.util.math.BlockPos getOrigin();

    /** @return true when this section's origin is within the configured near radius. */
    private boolean destinyrenderer$isNearPlayer() {
        int radius = RendererConfig.get().nearRebuildRadius;
        if (radius <= 0) return false;
        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
        if (mc == null || mc.player == null) return false;

        // When flying fast with an Elytra or creative flight, expand the immediate rebuild cone/radius
        if (mc.player.isGliding() || mc.player.getAbilities().flying) {
            radius = Math.max(radius, 48);
        }

        net.minecraft.util.math.BlockPos origin = this.getOrigin();
        if (origin == null) return false;
        double dx = origin.getX() + 8.0 - mc.player.getX();
        double dy = origin.getY() + 8.0 - mc.player.getY();
        double dz = origin.getZ() + 8.0 - mc.player.getZ();
        return dx * dx + dy * dy + dz * dz <= (double) radius * (double) radius;
    }

    /** @return true when connected to a remote server rather than a local world. */
    private static boolean destinyrenderer$isMultiplayer() {
        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
        return mc != null && !mc.isInSingleplayer();
    }
}
