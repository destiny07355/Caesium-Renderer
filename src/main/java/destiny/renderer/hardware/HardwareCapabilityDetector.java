package destiny.renderer.hardware;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GLCapabilities;

import java.util.logging.Logger;

/**
 * Hardware capability detector — queries the OpenGL context and JVM environment
 * to determine the optimal rendering profile and configuration preset.
 *
 * <p>Must be called <em>after</em> the OpenGL context is created (i.e., from a
 * {@code ClientModInitializer} callback that runs post-context).
 *
 * <h2>Detection Algorithm</h2>
 * <ol>
 *   <li>Query OpenGL renderer string, vendor, and version.</li>
 *   <li>Evaluate extension availability: MDI, mesh shaders, bindless textures.</li>
 *   <li>Estimate VRAM from OpenGL queries (NVIDIA: {@code GL_NVX_gpu_memory_info},
 *       AMD: {@code GL_ATI_meminfo}, fallback: heuristic from renderer string).</li>
 *   <li>Detect iGPU via VRAM &lt; 1 GB threshold and unified memory markers.</li>
 *   <li>Assign {@link HardwareProfile} and {@link HardwarePreset}.</li>
 * </ol>
 */
public final class HardwareCapabilityDetector {

    private static final Logger LOGGER = Logger.getLogger("Caesium/Hardware");

    // GL extension strings
    private static final String EXT_MESH_SHADER         = "GL_EXT_mesh_shader";
    private static final String ARB_MULTI_DRAW_INDIRECT = "GL_ARB_multi_draw_indirect";
    private static final String ARB_BINDLESS_TEXTURE     = "GL_ARB_bindless_texture";
    private static final String NVX_GPU_MEMORY_INFO      = "GL_NVX_gpu_memory_info";
    private static final String ATI_MEMINFO              = "GL_ATI_meminfo";

    // GL_NVX_gpu_memory_info constants
    private static final int GL_GPU_MEMORY_INFO_DEDICATED_VIDMEM_NVX = 0x9047;
    private static final int GL_GPU_MEMORY_INFO_TOTAL_AVAILABLE_MEMORY_NVX = 0x9048;

    // GL_ATI_meminfo constants
    private static final int GL_TEXTURE_FREE_MEMORY_ATI = 0x87FC;

    // -------------------------------------------------------------------------
    // Detected capabilities (populated by detect())
    // -------------------------------------------------------------------------

    private static HardwareProfile profile;
    private static HardwarePreset  preset;
    private static String          gpuVendor;
    private static String          gpuRenderer;
    private static int             estimatedVramMB;
    private static int             cpuCores;
    private static boolean         hasMultiDrawIndirect;
    private static boolean         hasMeshShaders;
    private static boolean         hasBindlessTextures;
    private static boolean         hasIndirectParameters;
    private static boolean         isDetected = false;

    private HardwareCapabilityDetector() {}

    // -------------------------------------------------------------------------
    // Detection
    // -------------------------------------------------------------------------

    /**
     * Runs hardware detection. Must be called once on the GL thread after context creation.
     * Results are stored as static fields and retrieved via getters.
     */
    public static void detect() {
        if (isDetected) return;

        LOGGER.info("[Caesium] ========= Hardware Detection =========");

        // ---- GPU info ----
        gpuVendor   = GL11.glGetString(GL11.GL_VENDOR);
        gpuRenderer = GL11.glGetString(GL11.GL_RENDERER);
        String version = GL11.glGetString(GL11.GL_VERSION);

        LOGGER.info("[Caesium] GPU Vendor  : " + gpuVendor);
        LOGGER.info("[Caesium] GPU Renderer: " + gpuRenderer);
        LOGGER.info("[Caesium] GL Version  : " + version);

        // ---- Extension check ----
        GLCapabilities caps = GL.getCapabilities();
        String glExtensions = GL11.glGetString(GL11.GL_EXTENSIONS);
        hasMultiDrawIndirect = caps.GL_ARB_multi_draw_indirect || (version != null && glVersionAtLeast(version, 4, 3));
        hasMeshShaders       = hasExtension(glExtensions, EXT_MESH_SHADER);
        hasBindlessTextures  = hasExtension(glExtensions, ARB_BINDLESS_TEXTURE);
        hasIndirectParameters = caps.GL_ARB_indirect_parameters || (version != null && glVersionAtLeast(version, 4, 6));

        LOGGER.info("[Caesium] MDI Support        : " + hasMultiDrawIndirect);
        LOGGER.info("[Caesium] Mesh Shader Support: " + hasMeshShaders);
        LOGGER.info("[Caesium] Bindless Textures  : " + hasBindlessTextures);
        LOGGER.info("[Caesium] Indirect Params    : " + hasIndirectParameters);

        // ---- VRAM estimation ----
        estimatedVramMB = estimateVRAM(glExtensions);
        LOGGER.info("[Caesium] Estimated VRAM     : " + estimatedVramMB + " MB");

        // ---- CPU core count ----
        cpuCores = Runtime.getRuntime().availableProcessors();
        LOGGER.info("[Caesium] CPU Cores (logical): " + cpuCores);

        // ---- Profile assignment ----
        boolean isIGPU = detectIGPU(gpuRenderer, estimatedVramMB);
        if (isIGPU) {
            profile = HardwareProfile.IGPU_ZERO_COPY;
        } else if (hasMeshShaders) {
            profile = HardwareProfile.DGPU_MESH_SHADER;
        } else {
            profile = HardwareProfile.DGPU_MDI;
        }

        preset = HardwarePreset.recommend(profile, estimatedVramMB, cpuCores);

        LOGGER.info("[Caesium] Selected Profile    : " + profile);
        LOGGER.info("[Caesium] Selected Preset     : " + preset);
        LOGGER.info("[Caesium] ====================================");

        isDetected = true;
    }

    // -------------------------------------------------------------------------
    // Heuristics
    // -------------------------------------------------------------------------

    /**
     * Determines whether the GPU is an integrated unit. Uses VRAM threshold combined
     * with renderer string heuristics for known iGPU families.
     */
    private static boolean detectIGPU(String renderer, int vramMB) {
        if (renderer == null) return false;
        String r = renderer.toLowerCase();
        // Known iGPU identifiers
        if (r.contains("iris xe") || r.contains("intel uhd") || r.contains("intel hd")
            || r.contains("radeon vega") || r.contains("rx vega")
            || r.contains("apple m") || r.contains("llvmpipe")
            || r.contains("swiftshader")) {
            return true;
        }
        // Fallback: very low VRAM is a strong iGPU indicator
        return vramMB > 0 && vramMB < 1024;
    }

    /**
     * Estimates VRAM using vendor-specific GL extensions, falling back to a renderer
     * string parse and finally a safe default.
     */
    private static int estimateVRAM(String extensionsStr) {
        // NVIDIA: GL_NVX_gpu_memory_info
        if (hasExtension(extensionsStr, NVX_GPU_MEMORY_INFO)) {
            int kb = GL11.glGetInteger(GL_GPU_MEMORY_INFO_DEDICATED_VIDMEM_NVX);
            if (kb > 0) return kb / 1024;
        }

        // AMD: GL_ATI_meminfo (returns total texture memory in KB)
        if (hasExtension(extensionsStr, ATI_MEMINFO)) {
            int[] values = new int[4];
            // glGetIntegerv(GL_TEXTURE_FREE_MEMORY_ATI, values)
            // We read via standard GL11 — available via Minecraft's LWJGL
            try {
                org.lwjgl.opengl.GL11.glGetIntegerv(GL_TEXTURE_FREE_MEMORY_ATI, values);
                if (values[0] > 0) return values[0] / 1024;
            } catch (Exception ignored) {}
        }

        // Heuristic: parse renderer string for known VRAM sizes
        if (gpuRenderer != null) {
            String r = gpuRenderer.toLowerCase();
            if (r.contains("rtx 4090") || r.contains("rtx 3090") || r.contains("rx 7900")) return 24576;
            if (r.contains("rtx 4080") || r.contains("rtx 3080")) return 16384;
            if (r.contains("rtx 4070") || r.contains("rtx 3070") || r.contains("rx 7800")) return 12288;
            if (r.contains("rtx 3060") || r.contains("rx 6700")) return 12288;
            if (r.contains("rtx 3050") || r.contains("rx 6600")) return 8192;
            if (r.contains("arc a7")) return 16384;
            if (r.contains("arc a5")) return 8192;
        }

        // Safe fallback — assume mid-range discrete
        return 4096;
    }
    
    private static boolean hasExtension(String extensions, String ext) {
        return extensions != null && extensions.contains(ext);
    }

    /** Checks if an OpenGL extension is available in the current context. */
    private static boolean isExtensionPresent(String ext) {
        try {
            return GL.getCapabilities() != null && GL11.glGetString(GL11.GL_EXTENSIONS) != null
                   && GL11.glGetString(GL11.GL_EXTENSIONS).contains(ext);
        } catch (Exception e) {
            return false;
        }
    }

    /** Parses "X.Y.Z ..." version strings and tests if major.minor ≥ reqMajor.reqMinor. */
    private static boolean glVersionAtLeast(String version, int reqMajor, int reqMinor) {
        try {
            String[] parts = version.split("[. ]");
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            return major > reqMajor || (major == reqMajor && minor >= reqMinor);
        } catch (Exception e) {
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    public static HardwareProfile getProfile()       { return profile; }
    public static HardwarePreset  getPreset()        { return preset; }
    public static String          getGpuVendor()     { return gpuVendor; }
    public static String          getGpuRenderer()   { return gpuRenderer; }
    public static int             getEstimatedVramMB(){ return estimatedVramMB; }
    public static int             getCpuCores()      { return cpuCores; }
    public static boolean         hasMDI()           { return hasMultiDrawIndirect; }
    public static boolean         hasMeshShaders()   { return hasMeshShaders; }
    public static boolean         hasBindless()      { return hasBindlessTextures; }
    public static boolean         hasIndirectParams(){ return hasIndirectParameters; }
    public static boolean         isDetected()       { return isDetected; }
}
