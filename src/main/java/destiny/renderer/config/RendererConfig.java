package destiny.renderer.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * Persistent renderer configuration backed by a JSON file in the Minecraft config directory.
 *
 * <p>All fields have defaults matching the BALANCED preset. Fields are public and mutable
 * to allow the in-game config screen ({@link RendererConfigScreen}) and preset system
 * ({@link destiny.renderer.hardware.HardwarePreset}) to write them without reflection.
 *
 * <p>The configuration is automatically saved on change and reloaded on launch.
 * Thread-safety: all fields should only be modified on the main/render thread.
 */
public final class RendererConfig {

    private static final Logger LOGGER = Logger.getLogger("Caesium/Config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CONFIG_FILE = "caesium.json";

    // -------------------------------------------------------------------------
    // Singleton
    // -------------------------------------------------------------------------

    private static volatile RendererConfig instance;

    public static RendererConfig get() {
        RendererConfig local = instance;
        if (local == null) {
            synchronized (RendererConfig.class) {
                local = instance;
                if (local == null) {
                    local = new RendererConfig();
                    instance = local;
                }
            }
        }
        return local;
    }

    private RendererConfig() {}

    // -------------------------------------------------------------------------
    // Configuration fields
    // -------------------------------------------------------------------------

    // --- Backend selection ---
    /**
     * Which GPU backend the engine starts with. One of:
     * <ul>
     *   <li>{@code OPENGL} (default) — works on every machine and driver. OpenGL 3.3 core
     *       is the compatibility floor; this is the safe choice for low-end, laptop and
     *       unknown GPUs.</li>
     *   <li>{@code VULKAN} — the primary backend. Only selectable when the current driver
     *       exposes a working Vulkan device; the engine validates support before use and
     *       falls back to OpenGL rather than crashing. For high- and mid-end GPUs that
     *       benefit from explicit control.</li>
     *   <li>{@code AUTO} — Vulkan when the driver supports it, otherwise OpenGL.</li>
     * </ul>
     */
    public volatile String renderingBackend = "OPENGL";

    /**
     * Which physical GPU the Vulkan backend uses when several are present (e.g. a laptop
     * with an integrated + discrete GPU). One of:
     * <ul>
     *   <li>{@code AUTO} (default) — score by capability: discrete GPUs first, then
     *       integrated, so the fastest device wins automatically.</li>
     *   <li>{@code DISCRETE} — only discrete (dGPU) devices are eligible; falls back to
     *       AUTO when the machine has none.</li>
     *   <li>{@code INTEGRATED} — prefer the iGPU (useful when the dGPU is broken, busy, or
     *       its driver crashes); falls back to AUTO when the machine has none.</li>
     *   <li>anything else — treated as a device-name substring; the first physical device
     *       whose name contains it is used (falls back to AUTO). Lets a power user pin an
     *       exact card, e.g. {@code NVIDIA GeForce RTX 3060}.</li>
     * </ul>
     * Only affects the Vulkan backend; the OpenGL backend uses whatever context the game
     * created. The chosen device must still expose a graphics queue and (for windowed
     * rendering) surface support — otherwise AUTO scoring applies.
     */
    public volatile String vulkanDevice = "AUTO";

    /**
     * Experimental: attach the Vulkan backend's swapchain to Minecraft's actual game
     * window so the engine presents its own frames instead of rendering offscreen.
     *
     * <p>Default OFF. When enabled, the engine's present path takes over the real window,
     * which can conflict with vanilla rendering while the engine still only draws its
     * test quad — this exists to prove the real-window swapchain path end to end
     * (ARCHITECTURE.md §10.2). Only meaningful when the Vulkan backend is selected.
     */
    public volatile boolean windowPresent = false;

    /**
     * Shader slot: name of a sub-folder under {@code config/caesium/shaders/} whose GLSL
     * shaders override the built-ins. Empty string uses the bundled shaders. The engine
     * compiles one logical GLSL source to GLSL on the OpenGL backend and to SPIR-V on the
     * Vulkan backend (ARCHITECTURE.md §12), so a shader pack written once runs on both.
     */
    public String shaderPack = "";

    // --- Pipeline selection ---
    /**
     * Enables DestinyRenderer's own GPU-driven terrain pipeline in place of vanilla's.
     *
     * <p>Default OFF. The custom pipeline is opt-in until it reaches full visual parity
     * with vanilla (non-cube block models, correct atlas UVs, block entities). When off,
     * vanilla draws terrain and DestinyRenderer still provides culling, batching,
     * particle/animation control and every other optimization.
     *
     * <p>Forced off automatically when Sodium / Embeddium / VulkanMod is installed —
     * see {@link destiny.renderer.compat.WorkAllotment}.
     */
    public volatile boolean experimentalTerrainPipeline = false;

    /** Per-capability Work Allotment pins. Key = Capability.name(), value = Provider.name() or "AUTO". */
    public java.util.Map<String, String> workAllotmentOverrides = new java.util.HashMap<>();

    /**
     * Set once the hardware preset has been applied. Prevents the preset from
     * overwriting the user's own settings on every subsequent launch.
     */
    public boolean hardwarePresetApplied = false;

    /** Name of the hardware preset detected on first run, for display purposes. */
    public String detectedPresetName = "";

    /**
     * Replace the vanilla Video Settings screen with DestinyRenderer's.
     * Turn off if you prefer to reach our settings only via the keybind (default HOME).
     */
    public boolean replaceVideoSettings = true;

    // --- Core rendering ---
    /** Maximum chunk render distance in chunks. */
    public int renderDistance = 16;

    /** Enable glMultiDrawElementsIndirect (MDI) rendering backend. */
    public boolean enableMDI = true;

    /** Enable GL_EXT_mesh_shader backend (requires capable GPU and driver). */
    public boolean enableMeshShaders = false;

    /** Enable GPU-driven compute shader frustum + Hi-Z occlusion culling. */
    public boolean enableComputeCull = true;

    /** Enable Hi-Z depth pyramid for occlusion culling. */
    public boolean enableHiZ = true;

    /** Enable CPU-side SIMD frustum culling via Vector API. */
    public boolean enableSIMDCulling = true;

    /** Enable packed 64-bit vertex format (disable for debugging compatibility issues). */
    public boolean enablePackedVertices = true;

    /** Enable Greedy Quad Merging to reduce vertex count by 40-60%. */
    public boolean greedyMeshing = true;

    /** Maximum GPU upload time per frame in milliseconds to prevent stuttering. */
    public float maxUploadMillisPerFrame = 2.5f;

    // --- Batching ---
    /** Enable entity batch rendering via EntityBatchRenderer. */
    public boolean enableEntityBatching = true;

    /** Enable particle batch rendering via ParticleBatchRenderer. */
    public boolean enableParticleBatching = true;

    // --- Memory ---
    /** Size of each persistent terrain VBO in megabytes. */
    public int persistentBufferSizeMB = 128;

    /** Force the zero-copy iGPU memory path regardless of detected hardware. */
    public boolean forceZeroCopyPath = false;

    // --- Threading ---
    /** Number of threads dedicated to chunk meshing (0 = auto-detect). */
    public int meshingThreads = 0;

    // --- Compatibility ---
    /** Enable Fabric Rendering API (FRAPI) compatibility layer for modded block models. */
    public boolean enableFRAPICompat = true;

    // --- Debug ---
    /** Show performance overlay in the top-left corner. */
    public boolean showPerfOverlay = false;

    /** Log frame time statistics every N seconds (0 = disabled). */
    public int logStatsIntervalSeconds = 0;

    /** Highlight chunk boundaries with wireframe boxes. */
    public boolean debugChunkBounds = false;

    // --- Sodium & VulkanMod Advanced Optimizations ---
    /** Only tick animated textures (water, lava, fire, portal) if currently inside the camera view frustum. */
    public boolean animateOnlyVisibleTextures = true;

    /** Cull geometry obscured by thick fog by pulling back the frustum far plane. */
    public boolean useFogOcclusion = true;

    /** Cull distant or occluded item frames to reduce draw calls. */
    public boolean cullItemFrames = true;

    /** Cull distant or occluded armor stands. */
    public boolean cullArmorStands = true;

    // --- Sodium Extra Animations ---
    public boolean enableWaterAnim = true;
    public boolean enableLavaAnim = true;
    public boolean enableFireAnim = true;
    public boolean enablePortalAnim = true;

    // --- Granular Particle Options ---
    public boolean enableMinimalParticles = true;
    public int minimalParticleLimitRatio = 25; // 25% particle density cap in Minimal mode
    public boolean enableRainParticles = true;
    public boolean enableExplosionParticles = true;
    public boolean enableSmokeParticles = true;
    public boolean enableFireworkParticles = true;
    public boolean enablePotionParticles = true;
    public boolean enableBlockBreakParticles = true;
    public boolean enableCritParticles = true;

    // --- Sodium Extra Render & Sky ---
    public boolean enableSky = true;
    public boolean enableSunMoon = true;
    public boolean enableStars = true;
    public boolean enableFog = true;

    // --- Sodium Extra Others ---
    public boolean showFpsCounter = true;
    public boolean showCoords = false;
    public volatile boolean showCaesiumProfiler = false;
    public volatile boolean caesiumProfilerFullMode = false;

    // --- Discord RPC ---
    public volatile boolean enableDiscordRpc = false;
    public volatile boolean rpcShowServer = true;
    public volatile boolean rpcShowFps = true;

    // --- Graphics Quality ---
    /** Smooth lighting / ambient occlusion on terrain. */
    public boolean enableSmoothLighting = true;

    /** Ambient Occlusion intensity 0=off, 1=minimal, 2=full. */
    public int ambientOcclusionLevel = 2;

    /** Biome blend radius in blocks (0–7). Reduces biome color boundary artifacts. */
    public int biomeBlendRadius = 3;

    /** Entity render distance as a fraction of chunk render distance (0.25–2.0). Lower values dramatically reduce entity counts in crowded areas like server spawns. */
    public float entityRenderDistanceMult = 0.75f;

    /** Level-of-detail threshold for entity rendering: entities farther than this use simplified model. */
    public int entityLODDistance = 24;

    /** Maximum chunk uploads per second. 0 = unlimited. Useful for low-end CPUs. */
    public int chunkUploadRateLimit = 0;

    /** Smart chunk loading: prioritize visible chunks over background loading. */
    public boolean smartChunkLoading = true;

    /** Chunk unload distance: unload sections X chunks beyond render distance to free VRAM. */
    public int chunkUnloadBuffer = 3;

    // --- Visual Toggles ---
    /** Toggles screen shake on explosion near player. */
    public boolean enableScreenShake = true;

    /** Toggles torch/candle light flicker animation. */
    public boolean enableLightFlicker = true;

    /** Toggles beacon beam rendering. */
    public boolean enableBeaconBeam = true;

    /** Toggles totem of undying animation overlay. */
    public boolean enableTotemAnim = true;

    /** Toggles nausea / nether warp screen distortion. */
    public boolean enableNauseaEffect = true;

    /** Enables mipmap crossfade transition when moving between LOD levels. */
    public boolean enableMipmapFade = true;

    // -------------------------------------------------------------------------
    // Sodium Extra parity — detail toggles
    // -------------------------------------------------------------------------

    /** Render cloud layer. */
    public boolean enableClouds = true;
    /** Render weather (rain/snow) geometry. */
    public boolean enableWeather = true;
    /** Render biome-tinted sky colour transitions. */
    public boolean enableSkyColors = true;
    /** Render the void/end sky plane. */
    public boolean enableVoidFog = true;
    /** Render block entities such as chests and signs. */
    public boolean enableBlockEntities = true;
    /** Render item frames. */
    public boolean enableItemFrames = true;
    /** Render armor stands. */
    public boolean enableArmorStands = true;
    /** Render paintings. */
    public boolean enablePaintings = true;
    /** Render item entities lying on the ground. */
    public boolean enableItemEntities = true;
    /** Render the enchantment glint effect. */
    public boolean enableEnchantmentGlint = true;
    /** Render the fire overlay when the player is burning. */
    public boolean enableFireOverlay = true;
    /** Render the water/liquid screen overlay. */
    public boolean enableWaterOverlay = true;
    /** Render the pumpkin head overlay. */
    public boolean enablePumpkinOverlay = true;
    /** Render the powder snow overlay. */
    public boolean enablePowderSnowOverlay = true;

    /** Render the darkness (Warden) effect fog darkening. */
    public boolean enableDarknessEffect = true;

    // -------------------------------------------------------------------------
    // HUD element toggles — settable from the Details page.
    // -------------------------------------------------------------------------

    /** Render the status effect icons in the top-right corner of the HUD. */
    public boolean enableStatusEffectHud = true;
    /** Render the hotbar at the bottom of the screen. */
    public boolean enableHotbar = true;
    /** Render the health/armor/food/air bars and the mount health bar. */
    public boolean enableHealthBars = true;

    // --- Animation toggles (Sodium Extra parity) ---
    public boolean enableBlockAnimations = true;
    public boolean enableSculkSensorAnim = true;
    public boolean enableTextureAnimations = true;

    /**
     * Skip texture-animation ticks on frames that are already over budget.
     * Particles and chunk rebuilds do the same; freezing animations on a slow
     * frame is a known 1% low killer in dense animated-block areas (nether
     * bases, lava lakes, large fire farms). Default ON because the worst-case
     * visible effect is an animation briefly holding its last frame — nothing is
     * lost and nothing looks wrong.
     */
    public boolean throttleTextureAnimOnSlowFrames = true;

    // --- Additional particle toggles ---
    public boolean enableDrippingParticles = true;
    public boolean enableSplashParticles   = true;
    public boolean enableBubbleParticles   = true;
    public boolean enableCampfireParticles = true;
    public boolean enableRedstoneParticles = true;
    public boolean enableEnchantParticles  = true;
    public boolean enablePortalParticles   = true;
    public boolean enableSweepParticles    = true;
    public boolean enableDamageParticles   = true;

    /**
     * Master kill switch for particles. When true, no particle is ever spawned — the
     * spawn hook returns before constructing anything, so it costs zero allocation, zero
     * ticking and zero draw calls. Overrides every other particle setting.
     */
    public boolean disableAllParticles = false;

    /**
     * Skip the per-particle tick ({@code ParticleManager.tick}) on frames that are
     * already running over budget. Particles tick one frame later instead of dragging
     * a slow frame further — a known 1% low killer in particle-heavy scenes (enderman
     * farms, raids, large fires). Default ON because the worst-case visible effect is
     * particles briefly staying at their last position for one frame; nothing is lost
     * and nothing is removed. Tied to the user's {@code maxFps} setting so it adapts:
     * if maxFps is unlimited, the throttle never fires.
     */
    public boolean throttleParticleTickOnSlowFrames = true;

    // -------------------------------------------------------------------------
    // HUD / overlay (Sodium Extra parity)
    // -------------------------------------------------------------------------

    /** FPS counter placement: 0 = off, 1 = top-left, 2 = top-right, 3 = bottom-left, 4 = bottom-right. */
    public int fpsCounterPosition = 1;
    /** Show extended FPS info (min/avg/max). */
    public boolean fpsExtended = false;
    /**
     * Show Caesium's percentile readouts (p50 / p98 / p99.5 + worst + avg) under the
     * vanilla {@code FPS T:...} line in the F3 debug overlay, mirroring how Sodium
     * surfaces its own {@code ms/f} stats. Default ON because the whole reason the
     * readout exists is to give users a way to see the wins a profiler usually hides.
     * {@code false} restores stock vanilla F3 behaviour exactly.
     */
    public boolean showExtendedFpsInF3 = true;
    /** Show the coordinate readout. */
    public boolean showCoordinates = false;
    /** Draw a translucent backdrop behind overlay text. */
    public boolean overlayBackground = true;
    /** Show GPU/CPU memory usage in the overlay. */
    public boolean showMemoryUsage = false;

    // -------------------------------------------------------------------------
    // Advanced culling
    // -------------------------------------------------------------------------

    /** Skip entities outside the view frustum. */
    public boolean cullEntities = true;
    /** Skip block entities outside the view frustum. */
    public boolean cullBlockEntities = true;
    /** Stop ticking particles that are outside the view frustum. */
    public boolean cullParticles = true;
    /** Maximum particles alive at once. 0 = unlimited. */
    public int maxParticleCount = 0;
    /** Distance beyond which particles are not spawned at all, in blocks. */
    public int particleCullDistance = 48;

    // --- Toast / Notification toggles ---
    public boolean enableAllToasts = true;
    public boolean enableAdvancementToasts = true;
    public boolean enableTutorialToasts = true;

    // -------------------------------------------------------------------------
    // Targeted render optimizations
    // -------------------------------------------------------------------------

    /**
     * Cull fluid faces that are hidden between two blocks of the same fluid.
     * Large frame time win in oceans and lava lakes; no visual difference.
     */
    public boolean optimizeFluidRendering = true;

    /** Draw the fire overlay on burning entities at all. */
    public boolean enableEntityFireOverlay = true;

    /**
     * Distance in blocks beyond which fire blocks on the ground stop rendering.
     *
     * <p>Fire is an animated, alpha-blended, non-cube block. A field of it after an
     * explosion produces hundreds of overlapping translucent quads with an animated
     * texture each — one of the worst fill-rate cases in the game. Limiting the radius
     * keeps nearby fire visible while cutting the bulk of the overdraw. 0 = unlimited.
     */
    public int groundFireRenderDistance = 24;

    /** Render fire blocks on the ground at all. */
    public boolean renderGroundFire = true;

    /** Reduce explosion particle counts based on blast size. */
    public boolean optimizeExplosions = true;

    /**
     * Upper bound on particles spawned by a single explosion. Large TNT arrays are the
     * single most common cause of multi-second freezes.
     */
    public int maxExplosionParticles = 64;

    // -------------------------------------------------------------------------
    // Frame pacing / threading
    // -------------------------------------------------------------------------

    /**
     * Frame rate limit when the window loses focus.
     * 0 = Never reduce (uncapped), otherwise the FPS cap to apply.
     */
    public int unfocusedFpsLimit = 0;

    /**
     * How many frames the CPU may run ahead of the GPU (Sodium-style frames-in-flight
     * limiter backed by a GL fence sync). 1 is the lowest-latency setting; 2 is the
     * balanced default. 0 disables the limiter.
     */
    public int cpuRenderAhead = 2;

    /**
     * Spread chunk mesh rebuilds across several frames instead of doing them all at once.
     * Trades a little pop-in for markedly steadier frame times while moving.
     */
    public volatile boolean deferChunkUpdates = true;

    /** Maximum chunk rebuilds started per frame when deferral is enabled. */
    public int maxChunkUpdatesPerFrame = 8;

    /**
     * Blocks around the player inside which section rebuilds are never deferred.
     *
     * <p>Block changes within this radius are almost always something the player just
     * did or is looking at directly — breaking, placing, a nearby explosion. Delaying
     * those for the deferred budget to reach them is the difference between a hole that
     * appears instantly and one that lags behind by several frames. 0 = off (defer
     * everywhere).
     */
    public int nearRebuildRadius = 48;

    /**
     * Allow chunk update deferral while connected to a server.
     *
     * <p>Off by default and strongly recommended to stay off for PvP. Deferring geometry
     * means the world you see can lag behind the world the server is simulating — you can
     * be shot through a wall that has already been broken, or miss a hole someone just
     * opened. Single player has no such risk because the client is authoritative.
     *
     * <p>When enabled, a "teleport burst" mode automatically activates after a large
     * position change (e.g., /tp), temporarily raising the per-frame rebuild budget so
     * chunks around the destination load smoothly instead of freezing the game.
     */
    public boolean deferChunkUpdatesInMultiplayer = true;

    /**
     * Multiplier applied to {@link #maxChunkUpdatesPerFrame} for a short window after
     * a teleport or large position change. This prevents the 3-second freeze when
     * chunks finally arrive from the server. Set to 1.0 to disable.
     */
    public double teleportBurstMultiplier = 4.0;

    /**
     * Distance threshold (in blocks) that triggers the teleport burst mode.
     * When the player moves more than this distance in a single tick, the burst
     * budget multiplier is applied for {@link #teleportBurstDurationTicks} frames.
     */
    public int teleportBurstThreshold = 128;

    /**
     * Opt-in auto-tuning of the vanilla render distance to hold the p99.5 readout at or
     * above 60 fps. Nudges {@code viewDistance} one chunk at a time, a few seconds apart
     * (PROGRESS.md 1.11.0 / R3).
     *
     * <p>Off by default. The real win is on integrated GPUs where the ceiling is fill
     * rate and memory bandwidth — distance is the one knob that actually moves that
     * floor. The controller never raises above the user's own chosen maximum, and never
     * goes below vanilla's minimum.
     */
    public boolean adaptiveViewDistance = false;

    /**
     * Keep gameplay-critical entities visible regardless of culling and distance settings.
     *
     * <p>Players, and projectiles such as arrows, tridents, fireballs and ender pearls,
     * are never hidden by our entity filters while this is on. Culling a player you are
     * fighting, or an arrow heading toward you, is a straightforward way to lose a fight.
     */
    public boolean alwaysRenderCombatEntities = true;

    // -------------------------------------------------------------------------
    // Aggressive throughput options (average FPS)
    // -------------------------------------------------------------------------

    /**
     * Skip rendering block entities beyond this distance, in blocks.
     *
     * <p>Chests, signs, banners, shulkers and beacons each render through the slow
     * immediate-mode path with their own model, texture bind and matrix work. In a base
     * or a shop district they can easily outweigh the terrain itself. 0 = unlimited.
     */
    public int blockEntityRenderDistance = 32;

    /**
     * Skip the second render pass used for entity outline glow.
     * Costs a full framebuffer and re-render of every glowing entity.
     */
    public boolean enableEntityOutlines = true;

    /**
     * Render the enchantment glint.
     *
     * <p>The glint is a second textured pass over every enchanted item with a scrolling
     * UV. In an inventory full of enchanted gear, or a server hub with armour stands, it
     * is a surprising amount of fill for a cosmetic effect.
     */
    public boolean renderGlint = true;

    /**
     * Cap how many block entities may render in a single frame. Prevents a storage room
     * from dominating the frame budget. 0 = unlimited.
     */
    public int maxBlockEntitiesPerFrame = 0;

    /** Thread priority for chunk build workers: 0 = low, 1 = normal, 2 = high. */
    public int chunkWorkerPriority = 0;

    /**
     * Frame cap while sitting in the main menu, server list, or world select.
     * There is nothing moving on those screens, so rendering them at several hundred
     * frames per second is pure heat and power for no benefit. 0 = uncapped.
     */
    public int mainMenuFpsLimit = 60;

    /**
     * Frame cap while a pause or inventory screen is open over the world.
     * Slightly higher than the main menu because the world is still visible behind it.
     * 0 = uncapped.
     */
    public int pauseScreenFpsLimit = 60;

    /** Collapse repeated chat lines into one entry with an (xN) counter. */
    public boolean compactChat = true;

    /**
     * Force a rebuild of the sections neighbouring an explosion.
     *
     * <p>Vanilla's occlusion graph can leave newly exposed faces unbuilt when terrain is
     * destroyed, producing see-through holes into the void after a creeper blast. Marking
     * the surrounding sections dirty repairs the geometry.
     */
    public boolean fixExplosionHoles = true;

    /**
     * Coalesce chunk rebuilds triggered by explosions.
     *
     * <p>A large TNT burst destroys thousands of blocks across many sections. Each removal
     * schedules its own rebuild, so a single detonation can queue the same section
     * hundreds of times in one tick. Batching collapses those into one rebuild per
     * section per window, which is what turns a multi-second freeze into a brief dip.
     */
    public boolean batchExplosionRebuilds = true;

    /** Window in milliseconds over which explosion rebuilds are coalesced. */
    public int explosionRebuildWindowMs = 120;

    /** Skip the screen shake / camera tilt from explosions. */
    public boolean explosionCameraShake = true;

    // -------------------------------------------------------------------------
    // Visual aids
    // -------------------------------------------------------------------------

    /**
     * Fullbright: renders the world at maximum brightness regardless of light level.
     * Implemented by overriding the gamma clamp rather than touching the light engine,
     * so it costs nothing and has no effect on chunk meshing.
     */
    public boolean fullbright = false;

    /** Fullbright intensity. 1.0 is vanilla maximum; higher values overbrighten. */
    public double fullbrightLevel = 15.0;

    // -------------------------------------------------------------------------
    // Interface appearance
    // -------------------------------------------------------------------------

    /** Theme preset name: DEFAULT, DARK, CUSTOM. */
    public String themePreset = "DEFAULT";

    /** Custom theme accent colour (ARGB) used when themePreset is CUSTOM. */
    public int customAccentColor = 0xFFE03C3C;

    /** Custom theme background colour (ARGB) used when themePreset is CUSTOM. */
    public int customBackgroundColor = 0xF00A0A0C;

    /** Whether the first-open quick tip tutorial popup has been displayed. */
    public boolean firstOpenTutorialShown = false;

    // -------------------------------------------------------------------------
    // Persistence
    // -------------------------------------------------------------------------

    /**
     * Loads the configuration from disk. If the file does not exist, returns a default instance.
     *
     * @param configDir the Minecraft config directory path
     */
    public static void load(Path configDir) {
        Path file = configDir.resolve(CONFIG_FILE);
        migrateLegacyConfig(configDir, file);
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file)) {
                RendererConfig loaded = GSON.fromJson(reader, RendererConfig.class);
                // Gson returns null for an empty or whitespace-only file. Without this
                // guard every subsequent get() would NPE.
                if (loaded == null) {
                    LOGGER.warning("[Caesium] Config file was empty — using defaults.");
                    loaded = new RendererConfig();
                }
                loaded.sanitize();
                loaded.recomputeDerivedFlags();
                instance = loaded;
                LOGGER.info("[Caesium] Config loaded from " + file);
            } catch (Exception e) {
                LOGGER.warning("[Caesium] Failed to load config, using defaults: " + e.getMessage());
                instance = new RendererConfig();
            }
        } else {
            instance = new RendererConfig();
            LOGGER.info("[Caesium] No config found, created defaults.");
            save(configDir);
        }
    }

    /**
     * One-time migration from the pre-rename config file (DestinyRenderer -> Caesium).
     * When the new file is absent but the old one exists, adopt the old file's contents
     * so a user's saved settings survive the rename.
     */
    private static void migrateLegacyConfig(Path configDir, Path newFile) {
        if (Files.exists(newFile)) return;
        Path legacy = configDir.resolve("destinyrenderer.json");
        if (!Files.exists(legacy)) return;
        try {
            Files.copy(legacy, newFile);
            LOGGER.info("[Caesium] Migrated saved settings from "
                + "destinyrenderer.json to caesium.json.");
        } catch (IOException e) {
            LOGGER.warning("[Caesium] Failed to migrate legacy config: " + e.getMessage());
        }
    }

    /**
     * Repairs a deserialized config: restores collections Gson may have left null and
     * clamps every numeric field into its legal range. A hand-edited or partially
     * written config must never be able to crash or corrupt the renderer.
     */
    private void sanitize() {
        if (workAllotmentOverrides == null) {
            workAllotmentOverrides = new java.util.HashMap<>();
        }
        if (renderingBackend == null
            || !(renderingBackend.equals("OPENGL") || renderingBackend.equals("VULKAN")
                || renderingBackend.equals("AUTO"))) {
            renderingBackend = "OPENGL";
        }
        if (shaderPack == null) {
            shaderPack = "";
        }
        if (vulkanDevice == null || vulkanDevice.isBlank()) {
            vulkanDevice = "AUTO";
        }
        renderDistance            = clamp(renderDistance, 2, 64);
        ambientOcclusionLevel     = clamp(ambientOcclusionLevel, 0, 2);
        biomeBlendRadius          = clamp(biomeBlendRadius, 0, 7);
        entityLODDistance         = clamp(entityLODDistance, 4, 256);
        chunkUploadRateLimit      = clamp(chunkUploadRateLimit, 0, 10000);
        chunkUnloadBuffer         = clamp(chunkUnloadBuffer, 1, 8);
        meshingThreads            = clamp(meshingThreads, 0, 32);
        persistentBufferSizeMB    = clamp(persistentBufferSizeMB, 16, 1024);
        minimalParticleLimitRatio = clamp(minimalParticleLimitRatio, 0, 100);
        logStatsIntervalSeconds   = clamp(logStatsIntervalSeconds, 0, 3600);
        maxUploadMillisPerFrame   = clampF(maxUploadMillisPerFrame, 0.5f, 16.0f);
        entityRenderDistanceMult  = clampF(entityRenderDistanceMult, 0.25f, 2.0f);
        fpsCounterPosition        = clamp(fpsCounterPosition, 0, 4);
        maxParticleCount          = clamp(maxParticleCount, 0, 32000);
        particleCullDistance      = clamp(particleCullDistance, 8, 256);
        groundFireRenderDistance  = clamp(groundFireRenderDistance, 0, 128);
        blockEntityRenderDistance = clamp(blockEntityRenderDistance, 0, 256);
        maxBlockEntitiesPerFrame  = clamp(maxBlockEntitiesPerFrame, 0, 4096);
        maxExplosionParticles     = clamp(maxExplosionParticles, 0, 2000);
        unfocusedFpsLimit         = clamp(unfocusedFpsLimit, 0, 260);
        cpuRenderAhead            = clamp(cpuRenderAhead, 0, 5);
        maxChunkUpdatesPerFrame   = clamp(maxChunkUpdatesPerFrame, 1, 64);
        nearRebuildRadius         = clamp(nearRebuildRadius, 0, 128);
        chunkWorkerPriority       = clamp(chunkWorkerPriority, 0, 2);
        mainMenuFpsLimit          = clamp(mainMenuFpsLimit, 0, 260);
        pauseScreenFpsLimit       = clamp(pauseScreenFpsLimit, 0, 260);
        explosionRebuildWindowMs  = clamp(explosionRebuildWindowMs, 0, 1000);
    }

    private static int clamp(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }

    private static float clampF(float v, float min, float max) {
        if (Float.isNaN(v)) return min;
        return v < min ? min : (v > max ? max : v);
    }

    /**
     * Saves the current configuration to disk.
     *
     * @param configDir the Minecraft config directory path
     */
    public static void save(Path configDir) {
        try {
            Files.createDirectories(configDir);
            Path file = configDir.resolve(CONFIG_FILE);
            try (Writer writer = Files.newBufferedWriter(file)) {
                GSON.toJson(get(), writer);
            }
            LOGGER.fine("[Caesium] Config saved to " + file);
        } catch (Exception e) {
            LOGGER.warning("[Caesium] Failed to save config: " + e.getMessage());
        }
    }

    /**
     * Resolves the shader pack directory for the current {@link #shaderPack} selection.
     * Users drop a folder of GLSL shaders into {@code config/caesium/shaders/<name>/}.
     *
     * @param configDir the Minecraft config directory path
     * @return the shader pack folder, or null when {@code shaderPack} is empty/missing
     */
    public static Path resolveShaderPack(Path configDir) {
        String pack = get().shaderPack;
        if (pack == null || pack.isEmpty()) {
            return null;
        }
        Path dir = configDir.resolve("caesium").resolve("shaders").resolve(pack);
        return Files.isDirectory(dir) ? dir : null;
    }

    /**
     * Fast rejection test for the per-entity render filter.
     *
     * <p>{@code EntityRenderer.shouldRender} runs for every entity every frame. When the
     * user has not changed any entity-related setting there is nothing for our filter to
     * do, and this lets the hook return after a single boolean read instead of walking an
     * instanceof chain and computing distances.
     */
    public boolean entityFilterActive = false;

    /** Recomputed whenever the config changes. */
    public void recomputeDerivedFlags() {
        entityFilterActive =
               !enableItemFrames
            || !enableArmorStands
            || !enablePaintings
            || !enableItemEntities
            || cullEntities
            || cullItemFrames
            || cullArmorStands
            || entityRenderDistanceMult < 1.0f;
    }

    /** @return true when the entity render filter has any work to do. */
    public static boolean anyEntityFilterActive() {
        return get().entityFilterActive;
    }

    /**
     * Returns the number of meshing threads to use, resolving the auto-detect (0) case
     * AND applying the {@link destiny.renderer.compat.ResourceShare} factor.
     *
     * <p>The base value is computed from the host's core count, leaving headroom for the
     * render thread and the OS. The {@link ResourceShare} factor then scales it down when
     * other render/perf mods are installed and have their own worker pools: two meshing
     * pools sized as if they were alone oversubscribe every core on this machine. With the
     * factor applied, Caesium takes most of its allocation when alone and deliberately
     * shrinks it (toward a single worker) when a full renderer replacement (Sodium /
     * Embeddium / VulkanMod) is doing the heavy geometry work.
     *
     * <p>The user's explicit {@link #meshingThreads} setting always wins over the
     * auto-shrink — only the auto path is share-aware, so a power user can still pin
     * Caesium to a fixed thread count if they want to.
     *
     * @return resolved meshing thread count, always at least 1
     */
    public int resolvedMeshingThreads() {
        int base;
        if (meshingThreads <= 0) {
            int cores = Runtime.getRuntime().availableProcessors();
            if (cores <= 4) {
                base = Math.max(1, cores - 1); // 1 free core on dual/quad core
            } else if (cores <= 12) {
                base = cores - 2; // 2 free cores on 6-12 logical cores (general use + Discord)
            } else {
                base = cores - 4; // 4 free cores on 12+ logical cores (streaming, recording, OS)
            }
            // Apply the allotment-aware shrink only on the auto path.
            int shrunk = Math.max(1, Math.round(base * destiny.renderer.compat.ResourceShare.meshingThreadFactor()));
            return shrunk;
        }
        return meshingThreads;
    }
}
