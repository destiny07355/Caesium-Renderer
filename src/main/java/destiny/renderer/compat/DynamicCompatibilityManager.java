package destiny.renderer.compat;

import net.fabricmc.loader.api.FabricLoader;

import java.util.logging.Logger;

/**
 * DynamicCompatibilityManager
 *
 * Detects installed third-party optimization mods at startup and disables
 * DestinyRenderer's own implementations of overlapping features so the
 * dedicated mod handles that work instead. This prevents conflicts and
 * ensures the best possible performance from each mod's specialization.
 *
 * Delegation Map:
 *  - ImmediatelyFast  → entity batching, particle batching, HUD batching
 *  - EntityCulling    → entity frustum + occlusion culling
 *  - FerriteCore      → block state memory compaction (palette dedup)
 *  - C2ME             → concurrent chunk generation & async I/O
 *  - Lithium          → server-side tick, pathfinding, AI optimizations
 *  - Krypton          → network packet batching & compression
 *  - Dynamic FPS      → frame rate throttling when window is unfocused
 *  - ModernFix        → startup time, class loading, resource pack caching
 *  - Iris             → shader pipeline (DestinyRenderer disables its own shader injection)
 *  - Sodium           → chunk palette building & block color resolving
 */
public final class DynamicCompatibilityManager {

    private static final Logger LOGGER = Logger.getLogger("Caesium/Compat");

    // -------------------------------------------------------------------------
    // Detected mod flags (read-only after init)
    // -------------------------------------------------------------------------

    /** ImmediatelyFast — handles entity, particle, and HUD batching */
    public static boolean IS_IMMEDIATELYFAST_LOADED = false;

    /** EntityCulling — handles entity occlusion & frustum culling */
    public static boolean IS_ENTITYCULLING_LOADED = false;

    /** FerriteCore — handles block state memory deduplication & palette compression */
    public static boolean IS_FERRITECORE_LOADED = false;

    /** C2ME (Concurrent Chunk Management Engine) — handles async chunk gen & I/O */
    public static boolean IS_C2ME_LOADED = false;

    /** Lithium — handles server-side tick, AI, and pathfinding optimizations */
    public static boolean IS_LITHIUM_LOADED = false;

    /** Krypton — handles network packet batching and compression */
    public static boolean IS_KRYPTON_LOADED = false;

    /** Dynamic FPS — handles window-unfocused frame throttle */
    public static boolean IS_DYNAMICFPS_LOADED = false;

    /** ModernFix — handles startup time, resource pack, and class loading */
    public static boolean IS_MODERNFIX_LOADED = false;

    /** Sodium — handles chunk palette building, block colors, and chunk rendering pipeline */
    public static boolean IS_SODIUM_LOADED = false;

    /** Iris Shaders — handles custom shader pipeline injection */
    public static boolean IS_IRIS_LOADED = false;

    /** BadOptimizations — handles misc rendering micro-optimizations */
    public static boolean IS_BADOPTIMIZATIONS_LOADED = false;

    // -------------------------------------------------------------------------
    // Feature delegation flags (derived from detected mods)
    // -------------------------------------------------------------------------

    /** If true, DestinyRenderer skips entity batching (delegated to ImmediatelyFast) */
    public static boolean DELEGATE_ENTITY_BATCHING = false;

    /** If true, DestinyRenderer skips particle batching (delegated to ImmediatelyFast) */
    public static boolean DELEGATE_PARTICLE_BATCHING = false;

    /** If true, DestinyRenderer skips entity culling (delegated to EntityCulling) */
    public static boolean DELEGATE_ENTITY_CULLING = false;

    /** If true, DestinyRenderer skips block state memory compaction (delegated to FerriteCore) */
    public static boolean DELEGATE_PALETTE_COMPRESSION = false;

    /** If true, DestinyRenderer skips async chunk I/O management (delegated to C2ME) */
    public static boolean DELEGATE_CHUNK_IO = false;

    /** If true, DestinyRenderer disables its shader injection (delegated to Iris) */
    public static boolean DELEGATE_SHADER_PIPELINE = false;

    /** If true, DestinyRenderer disables its own block color resolver (delegated to Sodium/FerriteCore) */
    public static boolean DELEGATE_BLOCK_COLORS = false;

    // -------------------------------------------------------------------------
    // Initialization
    // -------------------------------------------------------------------------

    public static void initCompatibilityHooks() {
        FabricLoader loader = FabricLoader.getInstance();

        IS_IMMEDIATELYFAST_LOADED  = loader.isModLoaded("immediatelyfast");
        IS_ENTITYCULLING_LOADED    = loader.isModLoaded("entityculling");
        IS_FERRITECORE_LOADED      = loader.isModLoaded("ferrite-core");
        IS_C2ME_LOADED             = loader.isModLoaded("c2me");
        IS_LITHIUM_LOADED          = loader.isModLoaded("lithium");
        IS_KRYPTON_LOADED          = loader.isModLoaded("krypton");
        IS_DYNAMICFPS_LOADED       = loader.isModLoaded("dynamic_fps");
        IS_MODERNFIX_LOADED        = loader.isModLoaded("modernfix");
        IS_SODIUM_LOADED           = loader.isModLoaded("sodium");
        IS_IRIS_LOADED             = loader.isModLoaded("iris");
        IS_BADOPTIMIZATIONS_LOADED = loader.isModLoaded("badoptimizations");

        // --- Resolve delegation ---

        // Entity & particle batching → ImmediatelyFast is purpose-built for this
        if (IS_IMMEDIATELYFAST_LOADED) {
            DELEGATE_ENTITY_BATCHING   = true;
            DELEGATE_PARTICLE_BATCHING = true;
            log("ImmediatelyFast", "entity batching, particle batching, HUD rendering");
        }

        // Entity culling → EntityCulling uses async raycasting, far superior to our CPU frustum check
        if (IS_ENTITYCULLING_LOADED) {
            DELEGATE_ENTITY_CULLING = true;
            log("EntityCulling", "entity occlusion & frustum culling");
        }

        // Block state palette & memory → FerriteCore's dedup approach is highly optimized
        if (IS_FERRITECORE_LOADED) {
            DELEGATE_PALETTE_COMPRESSION = true;
            DELEGATE_BLOCK_COLORS        = true;
            log("FerriteCore", "block state palette deduplication & block color resolving");
        }

        // Async chunk gen & I/O → C2ME's worker pool is specialized for this
        if (IS_C2ME_LOADED) {
            DELEGATE_CHUNK_IO = true;
            log("C2ME", "async chunk generation & I/O scheduling");
        }

        // Shader pipeline → Iris takes full ownership when installed
        if (IS_IRIS_LOADED) {
            DELEGATE_SHADER_PIPELINE = true;
            log("Iris", "custom shader pipeline injection (DestinyRenderer shader hooks disabled)");
        }

        // Block color resolving → Sodium has its own optimized resolver
        if (IS_SODIUM_LOADED && !DELEGATE_BLOCK_COLORS) {
            DELEGATE_BLOCK_COLORS = true;
            log("Sodium", "block color resolving & chunk palette building");
        }

        // Mods we detect but don't need to delegate (they operate independently):
        if (IS_LITHIUM_LOADED)          LOGGER.info("[Caesium/Compat] Lithium detected — server-side tick & AI optimized by Lithium.");
        if (IS_KRYPTON_LOADED)          LOGGER.info("[Caesium/Compat] Krypton detected — network batching handled by Krypton.");
        if (IS_DYNAMICFPS_LOADED)       LOGGER.info("[Caesium/Compat] Dynamic FPS detected — unfocused frame throttling handled by Dynamic FPS.");
        if (IS_MODERNFIX_LOADED)        LOGGER.info("[Caesium/Compat] ModernFix detected — startup & class loading optimized by ModernFix.");
        if (IS_BADOPTIMIZATIONS_LOADED) LOGGER.info("[Caesium/Compat] BadOptimizations detected — misc render micro-opts active.");

        LOGGER.info("[Caesium/Compat] Compatibility detection complete.");
    }

    private static void log(String modName, String delegated) {
        LOGGER.info("[Caesium/Compat] " + modName + " detected — delegating: " + delegated + ".");
    }
}
