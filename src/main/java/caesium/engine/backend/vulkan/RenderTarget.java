package caesium.engine.backend.vulkan;

import caesium.engine.backend.GpuCommandEncoder;
import caesium.engine.backend.GpuPipeline;

/**
 * What the Vulkan encoder renders into. Either the offscreen correctness target
 * ({@link OffscreenTarget}) or the window swapchain ({@link SwapchainTarget}). The encoder
 * only sees this seam, so adding a target (e.g. a post-process chain) needs no engine change.
 */
interface RenderTarget {

    GpuPipeline pipeline();

    /** Pipeline bound to a specific vertex layout; falls back to the quad pipeline. */
    default GpuPipeline pipeline(GpuCommandEncoder.VertexLayout layout) {
        return pipeline();
    }

    int width();

    int height();

    void beginRenderPass(long cmdBuffer);

    void endRenderPass(long cmdBuffer);

    /** The pipeline layout the encoder binds descriptor sets against. */
    long pipelineLayout();

    /** The target's UBO descriptor set (set 0 / binding 0). */
    long descriptorSet();
}