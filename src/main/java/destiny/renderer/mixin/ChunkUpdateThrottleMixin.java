package destiny.renderer.mixin;

import destiny.renderer.chunk.DeferredRebuildQueue;
import destiny.renderer.config.RendererConfig;
import net.minecraft.client.MinecraftClient;
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
    private static RendererConfig destinyrenderer$cachedCfg;
    private static MinecraftClient destinyrenderer$cachedMc;
    private static boolean destinyrenderer$cachedIsMultiplayer = false;
    private static double destinyrenderer$cachedNearRadiusSq = 48.0 * 48.0;

    @Inject(method = "scheduleRebuild(Z)V", at = @At("HEAD"), cancellable = true)
    private void destinyrenderer$throttle(boolean important, CallbackInfo ci) {
        long frame = destiny.renderer.hud.PerformanceOverlay.frameCounter();
        if (frame != destinyrenderer$frameMarker) {
            destinyrenderer$frameMarker = frame;
            destinyrenderer$frameBudget = 0;
            destinyrenderer$cachedCfg = RendererConfig.get();
            destinyrenderer$cachedMc = MinecraftClient.getInstance();
            destinyrenderer$cachedIsMultiplayer = destinyrenderer$cachedMc != null && !destinyrenderer$cachedMc.isInSingleplayer();
            int r = destinyrenderer$cachedCfg.nearRebuildRadius;
            if (destinyrenderer$cachedMc != null && destinyrenderer$cachedMc.player != null) {
                var p = destinyrenderer$cachedMc.player;
                if (p.isGliding() || p.getAbilities().flying) {
                    r = Math.max(r, 48);
                }
            }
            destinyrenderer$cachedNearRadiusSq = (double) r * (double) r;
        }

        RendererConfig cfg = destinyrenderer$cachedCfg;
        if (cfg == null || !cfg.deferChunkUpdates) return;

        // Player-caused and explosion-driven changes must apply immediately, otherwise
        // the world visibly lags behind what just happened.
        if (important) return;

        // On a server, deferring geometry means you can be shot through a wall that has
        // already been broken, or fail to see a hole someone just opened. Anything that
        // delays what you see relative to what the server thinks is true is a competitive
        // liability, so multiplayer never defers unless it is explicitly allowed.
        if (!cfg.deferChunkUpdatesInMultiplayer && destinyrenderer$cachedIsMultiplayer) return;

        // During a teleport burst (e.g. /rtp or warp), immediately let chunks build rather than deferring
        if (DeferredRebuildQueue.isTeleportBursting()) return;

        // Block edits directly in front of the player are almost certainly something the
        // player just did or is looking at. Sections within the near radius are rebuilt
        // with higher per-frame allowance, but throttled against massive floods (e.g. mcpvp.club
        // preloaded chunks) to prevent stalling the render thread with hundreds of simultaneous rebuilds.
        if (destinyrenderer$isNearPlayer()) {
            if (++destinyrenderer$frameBudget <= cfg.maxChunkUpdatesPerFrame * 2) {
                return;
            }
        } else {
            if (++destinyrenderer$frameBudget <= cfg.maxChunkUpdatesPerFrame) {
                return; // within budget for this frame
            }
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
        if (destinyrenderer$cachedNearRadiusSq <= 0.0) return false;
        MinecraftClient mc = destinyrenderer$cachedMc;
        if (mc == null || mc.player == null) return false;

        net.minecraft.util.math.BlockPos origin = this.getOrigin();
        if (origin == null) return false;
        double dx = origin.getX() + 8.0 - mc.player.getX();
        double dy = origin.getY() + 8.0 - mc.player.getY();
        double dz = origin.getZ() + 8.0 - mc.player.getZ();
        return dx * dx + dy * dy + dz * dz <= destinyrenderer$cachedNearRadiusSq;
    }
}
