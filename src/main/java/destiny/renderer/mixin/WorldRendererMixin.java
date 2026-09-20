package destiny.renderer.mixin;

import destiny.renderer.DestinyRenderer;
import destiny.renderer.chunk.ChunkSectionData;
import destiny.renderer.chunk.MeshingJobSystem;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.chunk.ChunkBuilder;
import net.minecraft.util.math.ChunkSectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin into {@link WorldRenderer} to intercept the terrain rendering pass.
 *
 * <h2>Injection Points</h2>
 * <ul>
 *   <li>{@code renderLayer()} — HEAD injection captures each render layer call and
 *       routes it to the active {@link destiny.renderer.render.RenderBackend}.</li>
 *   <li>{@code reload()} — Called when the renderer is reset (F3+A, settings change).
 *       We use this to flush all GPU buffers and rebuild section data.</li>
 * </ul>
 *
 * <h2>Strategy</h2>
 * Instead of completely cancelling vanilla rendering (which would break many mods),
 * we hook BEFORE the vanilla pass. If our backend successfully renders the layer,
 * we cancel the vanilla path. If the backend is not ready (e.g., during startup),
 * we fall through to vanilla rendering.
 */
@Mixin(WorldRenderer.class)
public abstract class WorldRendererMixin {

    private static final float[] currentProj = new float[16];
    private static final float[] currentView = new float[16];
    private static final org.joml.Matrix4f SCRATCH_MVP = new org.joml.Matrix4f();
    private static final float[] SCRATCH_MVP_ARR = new float[16];

    @Inject(method = "renderBlockLayers", at = @At("RETURN"))
    private void caesium$captureVanillaTerrainContract(
        org.joml.Matrix4fc positionMatrix, double cameraX, double cameraY, double cameraZ,
        CallbackInfoReturnable<net.minecraft.client.render.SectionRenderState> cir
    ) {
        if (DestinyRenderer.isActive() && destiny.renderer.compat.WorkAllotment.ownsTerrain()) {
            caesium.integration.CaesiumIntegration.observeVanillaTerrainState(cir.getReturnValue());
        }
    }

    /**
     * Intercepts the begin of each render frame to notify the active backend and capture transformation matrices.
     * Injected at the start of {@code WorldRenderer.render()}.
     */
    @Inject(
        method = "render",
        at = @At("HEAD")
    )
    private void destinyrenderer$onRenderLevelHead(
        net.minecraft.client.util.ObjectAllocator allocator,
        net.minecraft.client.render.RenderTickCounter tickCounter,
        boolean renderBlockOutline,
        net.minecraft.client.render.Camera camera,
        org.joml.Matrix4f positionMatrix,
        org.joml.Matrix4f projectionMatrix,
        org.joml.Matrix4f viewMatrix,
        com.mojang.blaze3d.buffers.GpuBufferSlice gpuBufferSlice,
        org.joml.Vector4f vector4f,
        boolean bl,
        CallbackInfo ci
    ) {
        if (!DestinyRenderer.isActive()) return;
        // Everything below exists solely to feed our own terrain backend. With the
        // terrain pipeline inactive this ran every single frame — two matrix copies and
        // an arena bookkeeping call — for no consumer at all.
        if (!destiny.renderer.compat.WorkAllotment.ownsTerrain()) return;

        destiny.renderer.render.RenderBackend backend = DestinyRenderer.getActiveBackend();
        if (backend == null) return;

        if (projectionMatrix != null) projectionMatrix.get(currentProj);
        if (viewMatrix != null) viewMatrix.get(currentView);
        caesium.integration.CaesiumIntegration.captureFrameMatrices(projectionMatrix, viewMatrix);

        backend.beginFrame();
        destiny.renderer.memory.RendererArenaManager.beginFrame();
    }

    /**
     * Feeds the current camera state to {@link SpriteVisibilityTracker} so animated
     * textures (fire, water, lava, …) can be frozen while off-screen. This runs
     * unconditionally — the animation policy must never depend on which backend owns
     * terrain — and the tracker itself no-ops when its toggle or the world is absent.
     */
    @Inject(
        method = "render",
        at = @At("HEAD")
    )
    private void destinyrenderer$captureAnimationVisibility(
        net.minecraft.client.util.ObjectAllocator allocator,
        net.minecraft.client.render.RenderTickCounter tickCounter,
        boolean renderBlockOutline,
        net.minecraft.client.render.Camera camera,
        org.joml.Matrix4f positionMatrix,
        org.joml.Matrix4f projectionMatrix,
        org.joml.Matrix4f viewMatrix,
        com.mojang.blaze3d.buffers.GpuBufferSlice gpuBufferSlice,
        org.joml.Vector4f vector4f,
        boolean bl,
        CallbackInfo ci
    ) {
        destiny.renderer.hud.CaesiumFrameProfiler.beginAnimations();
        if (projectionMatrix != null && positionMatrix != null) {
            SCRATCH_MVP.set(projectionMatrix).mul(positionMatrix).get(SCRATCH_MVP_ARR);
            destiny.renderer.cull.ParticleFrustumTracker.update(SCRATCH_MVP_ARR);
        }
        destiny.renderer.render.SpriteVisibilityTracker.capture(
            projectionMatrix, viewMatrix, camera == null ? null : camera.getCameraPos());
        destiny.renderer.hud.CaesiumFrameProfiler.endAnimations();
    }

    /**
     * Intercepts the end of each render frame to finalize backend state and record metrics.
     * Injected at the TAIL of {@code WorldRenderer.render()}.
     */
    @Inject(
        method = "render",
        at = @At("TAIL")
    )
    private void destinyrenderer$onRenderLevelTail(
        net.minecraft.client.util.ObjectAllocator allocator,
        net.minecraft.client.render.RenderTickCounter tickCounter,
        boolean renderBlockOutline,
        net.minecraft.client.render.Camera camera,
        org.joml.Matrix4f positionMatrix,
        org.joml.Matrix4f projectionMatrix,
        org.joml.Matrix4f viewMatrix,
        com.mojang.blaze3d.buffers.GpuBufferSlice gpuBufferSlice,
        org.joml.Vector4f vector4f,
        boolean bl,
        CallbackInfo ci
    ) {
        if (!DestinyRenderer.isActive()) return;
        if (!destiny.renderer.compat.WorkAllotment.ownsTerrain()) return;

        destiny.renderer.render.RenderBackend backend = DestinyRenderer.getActiveBackend();
        if (backend != null) backend.endFrame();
    }


    /**
     * Intercepts the world renderer reload (F3+A or graphics settings change).
     * Clears all GPU-side section data so sections are re-meshed with the new settings.
     */
    @Inject(
        method = "reload()V",
        at = @At("HEAD")
    )
    private void destinyrenderer$onReload(CallbackInfo ci) {
        if (!DestinyRenderer.isActive()) return;
        destiny.renderer.DestinyRenderer.onWorldReload();
        destiny.renderer.render.SpriteVisibilityTracker.invalidateAll();
    }

    // renderBlockLayers is the extraction seam. Actual per-group submission/cancellation
    // lives in SectionRenderStateMixin, where Minecraft has selected the authoritative
    // color/depth framebuffer and terrain layer group.
}
