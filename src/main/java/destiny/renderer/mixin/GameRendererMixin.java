package destiny.renderer.mixin;

import destiny.renderer.DestinyRenderer;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin into {@link GameRenderer} to hook into the overall render pipeline.
 *
 * <h2>Injection Points</h2>
 * <ul>
 *   <li>{@code renderWorld()} HEAD — triggers hardware detection on first render
 *       if it hasn't completed yet (deferred from mod init to ensure GL context exists).</li>
 *   <li>{@code renderWorld()} TAIL — triggers Hi-Z pyramid rebuild from the depth buffer
 *       after the opaque pass, before the compute cull dispatch for the next frame.</li>
 * </ul>
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @org.spongepowered.asm.mixin.Shadow public abstract net.minecraft.client.render.Camera getCamera();

    /** Flags whether the deferred GL-thread initialization has run. */
    private boolean destinyrenderer$glInitDone = false;

    /**
     * On the first call to renderWorld(), completes the GL-thread initialization
     * that couldn't be done in the mod initializer (e.g., hardware detection, buffer creation).
     */
    @Inject(
        method = "renderWorld",
        at = @At("HEAD")
    )
    private void destinyrenderer$onRenderWorldHead(CallbackInfo ci) {
        if (!destinyrenderer$glInitDone) {
            destinyrenderer$glInitDone = true;
            DestinyRenderer.onGLContextReady();
        }

        // Frames-in-flight limiter: block the render thread from running more than the
        // configured number of frames ahead of the GPU. No-op when disabled.
        destiny.renderer.render.CpuRenderAheadLimiter.beginFrame();
        // Camera tracking only matters to our own mesher. When the terrain pipeline is
        // inactive there is no mesher, so this was pure per-frame waste.
        if (DestinyRenderer.isActive()
            && destiny.renderer.compat.WorkAllotment.ownsTerrain()) {
            net.minecraft.client.render.Camera camera = this.getCamera();
            if (camera != null && camera.isReady()) {
                net.minecraft.util.math.Vec3d pos = camera.getCameraPos();
                float yaw = camera.getYaw();
                float pitch = camera.getPitch();
                double radYaw = Math.toRadians(yaw);
                double radPitch = Math.toRadians(pitch);
                double lx = -Math.sin(radYaw) * Math.cos(radPitch);
                double ly = -Math.sin(radPitch);
                double lz = Math.cos(radYaw) * Math.cos(radPitch);
                destiny.renderer.chunk.MeshingJobSystem.updateCamera(pos.x, pos.y, pos.z, lx, ly, lz);
            }
        }
    }

    /**
     * After the frame is fully rendered, update the Hi-Z pyramid for the next frame's
     * occlusion culling pass.
     */
    @Inject(
        method = "renderWorld",
        at = @At("TAIL")
    )
    private void destinyrenderer$onRenderWorldTail(CallbackInfo ci) {
        if (!DestinyRenderer.isActive()) return;

        // Record the completion fence for the frames-in-flight limiter.
        destiny.renderer.render.CpuRenderAheadLimiter.endFrame();

        // Opt-in adaptive render distance (R3). No-op until the user enables it; only
        // fires every few seconds, so per-frame cost is a single clock read + a config
        // field test.
        destiny.renderer.chunk.AdaptiveViewDistance.tick();

        // Drive the Caesium engine frame from the game's render loop. Offscreen by default;
        // when the windowPresent option is on the engine presents into the real window.
        DestinyRenderer.onFrame();
    }
}
