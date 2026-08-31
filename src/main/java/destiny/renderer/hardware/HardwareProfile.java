package destiny.renderer.hardware;

/**
 * Hardware capability profile determined by {@link HardwareCapabilityDetector} at startup.
 *
 * <p>The profile is used throughout the engine to select the optimal rendering backend,
 * memory allocation strategy, and culling algorithm without per-frame conditional checks.
 */
public enum HardwareProfile {

    /**
     * Integrated GPU with unified memory (Intel Iris Xe, Intel UHD, AMD Radeon APU,
     * Apple Silicon GPU). Triggers the zero-copy FFM path — CPU writes directly to physical
     * memory shared with the GPU, bypassing PCIe transfers entirely.
     *
     * <p>Culling is CPU-side via Vector API SIMD to minimize GPU workload.
     * Strict draw call reduction via MDI is mandatory to avoid thermal throttling.
     */
    IGPU_ZERO_COPY,

    /**
     * Discrete GPU (NVIDIA GTX/RTX, AMD Radeon RX, Intel Arc dGPU) without mesh shader support.
     * Uses persistently mapped VRAM buffers and {@code glMultiDrawElementsIndirect} (MDI)
     * to eliminate per-chunk draw calls. GPU-driven frustum culling via compute shader.
     */
    DGPU_MDI,

    /**
     * Discrete GPU with mesh shader support (NVIDIA Turing+, AMD RDNA2+, Intel Arc).
     * Full GPU-driven pipeline: geometry partitioned into meshlets, task shader culls,
     * mesh shader emits primitives. Zero CPU involvement in scene traversal.
     *
     * <p>Requires {@code GL_EXT_mesh_shader} OpenGL extension.
     */
    DGPU_MESH_SHADER;

    /** @return true if this profile uses the zero-copy iGPU memory path */
    public boolean isIGPU() {
        return this == IGPU_ZERO_COPY;
    }

    /** @return true if this profile supports mesh shaders */
    public boolean hasMeshShaders() {
        return this == DGPU_MESH_SHADER;
    }

    /** @return true if GPU-driven MDI or mesh shaders are active */
    public boolean isGPUDriven() {
        return this == DGPU_MDI || this == DGPU_MESH_SHADER;
    }
}
