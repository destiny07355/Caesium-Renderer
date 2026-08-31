package destiny.renderer.compat;

import java.util.logging.Logger;

/**
 * Fabric Rendering API (FRAPI) compatibility layer — v1.0 stub.
 *
 * <p>Full FRAPI implementation (DestinyQuadEmitter, DestinyRenderContext, etc.)
 * requires version-matched interface contracts against the runtime Fabric API JAR.
 * For v1.0, we perform a presence check at startup and log detection. Full FRAPI
 * quad interception is planned for v1.1 once the core rendering pipeline is stable.
 *
 * <p>Mods that rely purely on FRAPI for block-model emission (e.g., Continuity,
 * Create) will fall through to vanilla rendering correctly via the existing
 * WorldRenderer pipeline. DestinyRenderer accelerates the GPU submission path
 * regardless of whether the block model used FRAPI for quad generation.
 */
public final class FRAPICompatLayer {

    private static final Logger LOGGER = Logger.getLogger("Caesium/FRAPI");
    private static boolean frapiPresent = false;

    /** Check for FRAPI at startup. Safe to call from the mod initializer. */
    public static void register() {
        try {
            Class.forName("net.fabricmc.fabric.api.renderer.v1.RendererAccess");
            frapiPresent = true;
            LOGGER.info("[Caesium] Fabric Rendering API detected — "
                + "FRAPI compat is present but deferred to v1.1. "
                + "All FRAPI-emitted block models will render correctly via vanilla pipeline.");
        } catch (ClassNotFoundException e) {
            LOGGER.info("[Caesium] FRAPI not present on classpath — compat layer skipped.");
        }
    }

    /** @return true if the Fabric Rendering API is present on the classpath */
    public static boolean isFRAPIPresent() {
        return frapiPresent;
    }

    /**
     * Returns false (conservative). Full FRAPI BakedModel detection is deferred to v1.1.
     *
     * @return false always in v1.0 stub
     */
    public static boolean isFabricModel(Object model) {
        return false;
    }
}
