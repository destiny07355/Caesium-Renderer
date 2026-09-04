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
        destiny.renderer.render.SpriteVisibilityTracker.capture(
            positionMatrix, viewMatrix, camera == null ? null : camera.getCameraPos());
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
     * Drives the per-frame deferred-rebuild budget. Lives on {@link WorldRenderer#render}
     * TAIL specifically (rather than {@link net.minecraft.client.gui.hud.InGameHud#render}
     * TAIL, which is where the burst used to land) because the WorldRenderer TAIL runs
     * earlier in the frame — before the HUD, before present, before vsync wait. Rebuilds
     * land in a part of the frame that has slack; the tail-side alternative would put the
     * burst right next to the present, which is exactly the worst place for it on a
     * frame that is already struggling.
     *
     * <p>Runs unconditionally because the deferred-rebuild queue exists regardless of
     * whether Caesium owns terrain — vanilla chunk rebuilds are throttled by the same
     * queue when {@link destiny.renderer.config.RendererConfig#deferChunkUpdates} is on.
     * The queue itself no-ops when nothing is queued.
     */
    @Inject(
        method = "render",
        at = @At("TAIL")
    )
    private void caesium$driveDeferredRebuild(
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
        destiny.renderer.hud.CaesiumFrameProfiler.beginChunkScheduling();
        destiny.renderer.chunk.DeferredRebuildQueue.processFrame();
        destiny.renderer.hud.CaesiumFrameProfiler.endChunkScheduling();
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

    /**
     * Intercepts vanilla block layer rendering pass, executing GPU-driven MDI
     * rendering via DestinyRenderer and returning an empty SectionRenderState to cancel vanilla CPU chunk draws safely.
     */
    // ------------------------------------------------------------------------
    // NOTE ON THE CUSTOM TERRAIN PASS (Minecraft 1.21.11)
    // ------------------------------------------------------------------------
    // There is deliberately no injection into renderBlockLayers here.
    //
    // As of 1.21.11 Mojang moved terrain submission behind the blaze3d abstraction
    // (com.mojang.blaze3d.systems.GpuDevice / RenderPass). renderBlockLayers now returns
    // a SectionRenderState record built from a GpuTextureView plus an EnumMap of
    // RenderPass.RenderObject draw lists — there is no "empty" instance to hand back and
    // no supported way to fabricate one.
    //
    // Two hard consequences:
    //   1. Raw LWJGL calls (glMultiDrawElementsIndirect and friends) can no longer be
    //      safely interleaved with vanilla rendering. blaze3d caches pipeline state and
    //      raw GL mutations corrupt its assumptions, producing driver-level errors.
    //   2. RenderPass exposes drawIndexed / drawMultipleIndexed only. There is no
    //      multi-draw-indirect entry point to bind our indirect command buffer to.
    //
    // Replacing terrain therefore means porting the whole geometry pipeline onto
    // blaze3d RenderPipeline objects — a very large piece of work, and precisely what
    // Sodium maintains full time. Until that port exists, DestinyRenderer lets vanilla
    // own terrain submission and concentrates on the wins it can deliver safely:
    // culling, meshing throughput, particle/animation control and the settings system.
    //
    // See docs/ARCHITECTURE.md for the migration plan.
}
