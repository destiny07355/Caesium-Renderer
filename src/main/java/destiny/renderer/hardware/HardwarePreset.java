package destiny.renderer.hardware;

import destiny.renderer.config.RendererConfig;

/**
 * Hardware presets — each preset is a named configuration bundle that populates
 * {@link RendererConfig} fields based on the detected hardware tier.
 *
 * <p>Presets are applied once during initialization after {@link HardwareCapabilityDetector}
 * identifies the hardware profile. The user can override any field in the config screen.
 */
public enum HardwarePreset {

    /**
     * Very low-end hardware (integrated graphics, &lt;4GB RAM, old CPUs).
     * Aggressive CPU-side culling, minimum render distance enforcement,
     * zero-copy memory path mandatory.
     */
    POTATO {
        @Override
        public void apply(RendererConfig cfg) {
            cfg.renderDistance           = 8;
            cfg.enableMDI                = false;
            cfg.enableMeshShaders        = false;
            cfg.enableComputeCull        = false;
            cfg.enableHiZ                = false;
            cfg.enableSIMDCulling        = true;
            cfg.enableEntityBatching     = true;
            cfg.enableParticleBatching   = false;
            cfg.meshingThreads           = 1;
            cfg.persistentBufferSizeMB   = 16;
            cfg.forceZeroCopyPath        = true;
        }
    },

    /**
     * Low-end laptop (iGPU, thermal throttling concern, battery mode).
     * Minimizes GPU draw calls to reduce power consumption. Strict MDI batching.
     */
    LOW_END_LAPTOP {
        @Override
        public void apply(RendererConfig cfg) {
            cfg.renderDistance           = 12;
            cfg.enableMDI                = true;
            cfg.enableMeshShaders        = false;
            cfg.enableComputeCull        = false;
            cfg.enableHiZ                = false;
            cfg.enableSIMDCulling        = true;
            cfg.enableEntityBatching     = true;
            cfg.enableParticleBatching   = true;
            cfg.meshingThreads           = 2;
            cfg.persistentBufferSizeMB   = 32;
            cfg.forceZeroCopyPath        = true;
        }
    },

    /**
     * Mid-range balanced configuration. MDI rendering, async uploads, SIMD culling.
     * Suitable for mainstream desktop and laptop dGPUs.
     */
    BALANCED {
        @Override
        public void apply(RendererConfig cfg) {
            cfg.renderDistance           = 16;
            cfg.enableMDI                = true;
            cfg.enableMeshShaders        = false;
            cfg.enableComputeCull        = true;
            cfg.enableHiZ                = true;
            cfg.enableSIMDCulling        = true;
            cfg.enableEntityBatching     = true;
            cfg.enableParticleBatching   = true;
            cfg.meshingThreads           = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
            cfg.persistentBufferSizeMB   = 128;
            cfg.forceZeroCopyPath        = false;
        }
    },

    /**
     * High-end competitive configuration. Maximum FPS, strict GPU-driven rendering,
     * all available threads allocated to chunk compilation.
     */
    COMPETITIVE {
        @Override
        public void apply(RendererConfig cfg) {
            cfg.renderDistance           = 24;
            cfg.enableMDI                = true;
            cfg.enableMeshShaders        = false;
            cfg.enableComputeCull        = true;
            cfg.enableHiZ                = true;
            cfg.enableSIMDCulling        = true;
            cfg.enableEntityBatching     = true;
            cfg.enableParticleBatching   = true;
            cfg.meshingThreads           = Math.max(4, Runtime.getRuntime().availableProcessors() - 2);
            cfg.persistentBufferSizeMB   = 256;
            cfg.forceZeroCopyPath        = false;
        }
    },

    /**
     * Experimental — enables mesh shaders and full GPU culling.
     * Requires NVIDIA Turing+, AMD RDNA2+, or Intel Arc.
     * Targets extreme render distances with zero CPU overhead.
     */
    EXPERIMENTAL {
        @Override
        public void apply(RendererConfig cfg) {
            cfg.renderDistance           = 64;
            cfg.enableMDI                = true;
            cfg.enableMeshShaders        = true;  // Requires GL_EXT_mesh_shader
            cfg.enableComputeCull        = true;
            cfg.enableHiZ                = true;
            cfg.enableSIMDCulling        = true;
            cfg.enableEntityBatching     = true;
            cfg.enableParticleBatching   = true;
            cfg.meshingThreads           = Runtime.getRuntime().availableProcessors();
            cfg.persistentBufferSizeMB   = 512;
            cfg.forceZeroCopyPath        = false;
        }
    };

    /**
     * Applies this preset's settings to the given configuration object.
     *
     * @param cfg the configuration to mutate
     */
    public abstract void apply(RendererConfig cfg);

    /**
     * Selects the most appropriate preset for the detected hardware profile.
     * Manual override via config screen is always available.
     *
     * @param profile detected hardware profile
     * @param vramMB  estimated VRAM in megabytes
     * @param cpuCores physical CPU core count
     * @return recommended preset
     */
    public static HardwarePreset recommend(HardwareProfile profile, int vramMB, int cpuCores) {
        if (profile.isIGPU()) {
            return vramMB < 512 ? POTATO : LOW_END_LAPTOP;
        }
        if (profile.hasMeshShaders()) return EXPERIMENTAL;
        if (cpuCores >= 8 && vramMB >= 4096) return COMPETITIVE;
        return BALANCED;
    }
}
