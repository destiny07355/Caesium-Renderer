package destiny.renderer;

import destiny.renderer.chunk.MeshingJobSystem;
import destiny.renderer.compat.FRAPICompatLayer;
import destiny.renderer.config.RendererConfig;
import destiny.renderer.hardware.HardwareCapabilityDetector;
import destiny.renderer.hardware.HardwarePreset;
import destiny.renderer.memory.RendererArenaManager;
import destiny.renderer.render.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * DestinyRenderer — Next-Generation Voxel Rendering Engine for Minecraft 1.21.11.
 *
 * <h2>Initialization Sequence</h2>
 * <ol>
 *   <li>{@code onInitializeClient()} — Mod initializer phase: load config, register FRAPI,
 *       register keybindings and event listeners.</li>
 *   <li>{@code onGLContextReady()} — First render frame: GL context confirmed. Detect hardware,
 *       apply preset, initialize memory subsystem, start meshing threads, create backends.</li>
 *   <li>Per-frame render loop: backends process MDI / mesh shader dispatch via mixins.</li>
 *   <li>{@code onWorldReload()} — Flush all GPU buffers on F3+A.</li>
 *   <li>Shutdown — JVM shutdown hook releases all off-heap memory and GL resources.</li>
 * </ol>
 *
 * <h2>Static Accessors</h2>
 * All engine subsystems are accessible via static getters from the mixin layer,
 * avoiding the need to pass instances through Minecraft's complex class hierarchy.
 */
public final class DestinyRenderer implements ClientModInitializer {

    private static final Logger LOGGER = Logger.getLogger("Caesium");

    // -------------------------------------------------------------------------
    // Engine state — static singletons accessible from mixins
    // -------------------------------------------------------------------------

    private static RenderBackend        activeBackend;
    private static EntityBatchRenderer  entityBatchRenderer;

    private static volatile boolean active = false;
    private static volatile boolean glReady = false;

    // -------------------------------------------------------------------------
    // Keybinding
    // -------------------------------------------------------------------------

    private static KeyBinding configKey;

    // -------------------------------------------------------------------------
    // ClientModInitializer
    // -------------------------------------------------------------------------

    public static String getVersion() {
        return FabricLoader.getInstance().getModContainer("caesium")
            .map(c -> c.getMetadata().getVersion().getFriendlyString())
            .orElse("2.0.4");
    }

    @Override
    public void onInitializeClient() {
        String ver = getVersion();
        LOGGER.info("======================================================");
        LOGGER.info(" Caesium v" + ver + " — Initializing");
        LOGGER.info(" Client-side performance suite for Minecraft 1.21.11");
        LOGGER.info(" Minecraft 1.21.11 | Fabric | Java 25+");
        LOGGER.info(" Built by Destiny073");
        LOGGER.info("======================================================");

        // Load configuration
        Path configDir = MinecraftClient.getInstance() != null
            ? MinecraftClient.getInstance().runDirectory.toPath().resolve("config")
            : Path.of("config");
        RendererConfig.load(configDir);
        LOGGER.info("[Caesium] Configuration loaded.");

        destiny.renderer.rpc.DiscordPresenceManager.start();

        // Initialize Dynamic Compatibility Manager (legacy flags) and resolve Work Allotment.
        destiny.renderer.compat.DynamicCompatibilityManager.initCompatibilityHooks();
        destiny.renderer.compat.WorkAllotment.resolve();
        // Log the allotment-aware resource sizing so the user can see in the game log why
        // Caesium runs fewer mesher threads when Sodium is installed alongside it.
        destiny.renderer.compat.ResourceShare.logSummary();

        // Hard safety: if another mod owns terrain rendering, our pipeline must stand down
        // regardless of what the config file says. Two terrain renderers is a conflict,
        // not a tuning choice.
        if (!destiny.renderer.compat.WorkAllotment.isOwnedByUs(
                destiny.renderer.compat.Capability.TERRAIN_RENDERING)
            && RendererConfig.get().experimentalTerrainPipeline) {
            RendererConfig.get().experimentalTerrainPipeline = false;
            LOGGER.warning("[Caesium] Experimental terrain pipeline force-disabled: "
                + "another renderer owns terrain.");
        }

        // Register Fabric Rendering API compatibility layer
        if (RendererConfig.get().enableFRAPICompat) {
            FRAPICompatLayer.register();
        }

        // Register keybinding (default: HOME key) for config screen
        // Use the vanilla misc category string — KeyBinding constructor accepts String in 1.21.11
        configKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.caesium.config",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_HOME,
            KeyBinding.Category.MISC
        ));

        // Register screen event for config key handling and safe deferred chunk processing
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (configKey.wasPressed()) {
                if (client.currentScreen == null) {
                    client.setScreen(new destiny.renderer.gui.DestinySettingsScreen(null));
                }
            }
            if (client.world != null) {
                destiny.renderer.chunk.DeferredRebuildQueue.processFrame();
            }
        });

        // Stop worker threads at JVM exit. GL resources are NOT released here — the GL
        // context may already be destroyed on this thread, and calling glDelete* off the
        // render thread is undefined behaviour. GL teardown happens in shutdownGL(),
        // invoked from the client stop event on the render thread.
        Runtime.getRuntime().addShutdownHook(new Thread(DestinyRenderer::shutdownThreads,
            "Caesium-Shutdown"));

        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STOPPING
            .register(client -> shutdownGL());

        LOGGER.info("[Caesium] Client initializer complete. Waiting for GL context...");

        // Analyze JVM arguments and export recommendations
        destiny.renderer.jvm.JvmReport jvmReport =
            destiny.renderer.jvm.JvmArgumentAnalyzer.getReport();
        if (jvmReport.hasUrgentIssues()) {
            LOGGER.warning("[Caesium] JVM configuration has " + jvmReport.issues().size()
                + " issue(s). Score: " + jvmReport.score() + "/100 ("
                + jvmReport.scoreLabel() + "). Check caesium_jvm_args.txt for recommendations.");
        }
        // Export recommended args file to run directory
        destiny.renderer.jvm.JvmArgumentAnalyzer.exportRecommendedArgs(
            MinecraftClient.getInstance() != null
                ? MinecraftClient.getInstance().runDirectory.toPath()
                : Path.of("."));
    }

    // -------------------------------------------------------------------------
    // GL-thread deferred initialization (called from GameRendererMixin)
    // -------------------------------------------------------------------------

    /**
     * Called on the first render frame after the OpenGL context is confirmed ready.
     * This is the point where all GPU resource allocation occurs.
     */
    public static synchronized void onGLContextReady() {
        if (glReady) return;
        glReady = true;

        LOGGER.info("[Caesium] OpenGL context ready — performing hardware detection and backend init.");

        try {
            // 1. Hardware detection
            HardwareCapabilityDetector.detect();

            // 2. Apply the detected hardware preset — but ONLY on first run.
            //
            // This used to run unconditionally, which meant every launch silently
            // overwrote renderDistance, meshingThreads, persistentBufferSizeMB and the
            // culling toggles with preset defaults. Any change the user made in the
            // settings screen was discarded the next time the game started.
            HardwarePreset preset = HardwareCapabilityDetector.getPreset();
            RendererConfig cfg = RendererConfig.get();
            if (!cfg.hardwarePresetApplied) {
                preset.apply(cfg);
                cfg.hardwarePresetApplied = true;
                cfg.detectedPresetName = preset.name();
                LOGGER.info("[Caesium] First run — applied hardware preset: " + preset.name());
                RendererConfig.save(MinecraftClient.getInstance().runDirectory.toPath()
                    .resolve("config"));
            } else {
                LOGGER.info("[Caesium] Using saved configuration (detected profile: "
                    + preset.name() + ").");
            }

            // ----------------------------------------------------------------
            // Terrain GPU resources are allocated ONLY when our terrain pipeline
            // actually owns terrain rendering.
            //
            // This previously ran unconditionally and reserved roughly 192 MB of
            // persistently mapped GPU buffers (2x64 MB vertex + 2x32 MB index), plus a
            // Hi-Z pyramid, a compute cull shader and two batch renderers — none of
            // which were ever drawn from, because the terrain draw call is not
            // reachable on 1.21.11. On integrated and shared-memory GPUs that
            // allocation directly competes with vanilla's own renderer and was a major
            // cause of the frame time collapse while moving.
            // ----------------------------------------------------------------
            if (destiny.renderer.compat.WorkAllotment.ownsTerrain()) {
                RendererArenaManager.initialize();

                MeshingJobSystem.initialize();
                LOGGER.info("[Caesium] Meshing job system started with "
                    + RendererConfig.get().resolvedMeshingThreads() + " threads.");

                // Build the block-state lookup tables used by the meshing hot path.
                // These are only consumed by our own pipeline (ChunkMesher /
                // ChunkSectionData), so building them when terrain is delegated was
                // pure startup work — 29k+ state iterations plus a sprite resolution
                // per state, on modpacks with 100k+ states it is measurable lag.
                destiny.renderer.chunk.BlockStateLUT.build();
            } else {
            LOGGER.info("[Caesium] Terrain pipeline inactive — skipping mesher allocation. "
                + "Optimization passes still active.");
            }

            // Entity batching only makes sense if we own that capability.
            if (RendererConfig.get().enableEntityBatching
                && destiny.renderer.compat.WorkAllotment.isOwnedByUs(
                    destiny.renderer.compat.Capability.ENTITY_BATCHING)
                && destiny.renderer.compat.WorkAllotment.ownsTerrain()) {
                entityBatchRenderer = new EntityBatchRenderer();
                entityBatchRenderer.initialize();
            }

            // Engine is now fully active
            active = true;

            // Frames-in-flight limiter works regardless of who owns terrain, so it is
            // configured outside the pipeline gate. No-op when cpuRenderAhead is 0.
            destiny.renderer.render.CpuRenderAheadLimiter.configure(
                RendererConfig.get().cpuRenderAhead);

            // Engine integration shim: boots the Caesium engine on the GL thread (after the
            // context exists) so the Minecraft -> Caesium -> frame loop runs. Attaching the
            // Vulkan swapchain to the real window is config-guarded (windowPresent, default
            // off); otherwise the engine renders offscreen.
            caesium.integration.CaesiumIntegration.start();
            activeBackend = new destiny.renderer.render.CaesiumRenderBackendAdapter(
                caesium.integration.CaesiumIntegration.getEngine(),
                caesium.integration.CaesiumIntegration.getBackend()
            );

            LOGGER.info("======================================================");
            LOGGER.info(" Caesium ACTIVE");
            LOGGER.info(" Profile : " + HardwareCapabilityDetector.getProfile());
            LOGGER.info(" Preset  : " + HardwareCapabilityDetector.getPreset());
            LOGGER.info(" Backend : " + (activeBackend != null
                ? activeBackend.name() : "None (vanilla terrain, optimization passes only)"));
            LOGGER.info(" GPU     : " + HardwareCapabilityDetector.getGpuRenderer());
            LOGGER.info(" VRAM    : " + HardwareCapabilityDetector.getEstimatedVramMB() + " MB");
            LOGGER.info("======================================================");

        } catch (Exception e) {
            LOGGER.severe("[Caesium] FATAL: Engine initialization failed: " + e.getMessage());
            e.printStackTrace();
            active = false;
        }
    }

    // -------------------------------------------------------------------------
    // Event handlers (called from mixins)
    // -------------------------------------------------------------------------

    /** Called when the world renderer reloads (F3+A). Flushes all GPU section data. */
    public static void onWorldReload() {
        LOGGER.info("[Caesium] World reload — flushing GPU buffers.");

        // Queued sections belong to the old renderer instance; holding them would leak
        // and rebuilding them would touch freed state.
        destiny.renderer.chunk.DeferredRebuildQueue.clear();
        // Previously this only logged, so every stale allocation survived a reload and
        // leaked buffer space until the ring allocator wrapped over live geometry.
        if (activeBackend != null) {
            try {
                activeBackend.reset();
            } catch (Throwable t) {
                LOGGER.warning("[Caesium] Backend reset failed: " + t);
            }
        }
    }

    /** Called by MultiBufferSourceMixin at each entity buffer flush boundary. */
    public static void onEntityFlushBoundary() {
        if (entityBatchRenderer != null) {
            // Entity batch renderer flushes at this boundary
        }
    }

    // -------------------------------------------------------------------------
    // Shutdown
    // -------------------------------------------------------------------------

    private static volatile boolean glTornDown = false;

    /**
     * Stops background worker threads. Safe to call from any thread, including a JVM
     * shutdown hook. Touches no OpenGL state.
     */
    private static void shutdownThreads() {
        active = false;
        try {
            MeshingJobSystem.shutdown();
        } catch (Throwable t) {
            LOGGER.warning("[Caesium] Error stopping meshing system: " + t);
        }
    }

    /**
     * Releases all GPU resources. MUST be called on the render thread while the GL
     * context is still current — hence the CLIENT_STOPPING event rather than a JVM hook.
     */
    public static synchronized void shutdownGL() {
        if (glTornDown) return;
        glTornDown = true;
        active = false;

        LOGGER.info("[Caesium] Releasing GPU resources...");

        shutdownThreads();
        
        destiny.renderer.rpc.DiscordPresenceManager.stop();

        safeShutdown("backend",     () -> { if (activeBackend         != null) activeBackend.shutdown(); });
        safeShutdown("entityBatch", () -> { if (entityBatchRenderer   != null) entityBatchRenderer.shutdown(); });
        safeShutdown("fenceLimiter", destiny.renderer.render.CpuRenderAheadLimiter::releaseAll);
        safeShutdown("arenas",      RendererArenaManager::shutdown);
        safeShutdown("engine",      caesium.integration.CaesiumIntegration::stop);

        LOGGER.info("[Caesium] Shutdown complete.");
    }

    private static void safeShutdown(String name, Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            LOGGER.warning("[Caesium] Error during " + name + " shutdown: " + t);
        }
    }

    /**
     * Called by the engine's frame loop mixin once per rendered frame. Routes the current
     * camera/world state into the Caesium engine; no-op when the engine is not started.
     */
    public static void onFrame() {
        if (!active) {
            return;
        }
        try {
            caesium.integration.CaesiumIntegration.render();
        } catch (Throwable t) {
            LOGGER.warning("[Caesium] Engine frame failed: " + t);
        }
    }

    // -------------------------------------------------------------------------
    // Static accessors (used by mixins and subsystems)
    // -------------------------------------------------------------------------

    /** @return true if the engine is fully initialized and active */
    public static boolean isActive()                         { return active; }

    /** @return the active rendering backend */
    public static RenderBackend getActiveBackend()           { return activeBackend; }

    /** @return the entity batch renderer */
    public static EntityBatchRenderer getEntityBatchRenderer(){ return entityBatchRenderer; }
}
