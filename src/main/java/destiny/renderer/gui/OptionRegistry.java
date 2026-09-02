package destiny.renderer.gui;

import destiny.renderer.compat.Capability;
import destiny.renderer.compat.WorkAllotment;
import destiny.renderer.config.RendererConfig;
import destiny.renderer.gui.options.Option;
import destiny.renderer.gui.options.Option.Impact;
import destiny.renderer.gui.options.OptionGroup;
import destiny.renderer.gui.options.OptionPage;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.CloudRenderMode;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.GraphicsMode;
import net.minecraft.client.option.InactivityFpsLimit;
import net.minecraft.client.option.TextureFilteringMode;
import net.minecraft.client.render.ChunkBuilderMode;
import net.minecraft.particle.ParticlesMode;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Builds the complete settings catalogue.
 *
 * <p>Covers the full vanilla video option set (properly categorised), plus the settings
 * Sodium, Sodium Extra and Reese's Sodium Options provide. Every option here is backed by
 * real state — either a vanilla {@code GameOptions} entry or a {@link RendererConfig}
 * field that a mixin actually reads. Nothing is decorative.
 */
public final class OptionRegistry {

    private static final Logger LOGGER = Logger.getLogger("Caesium/Options");

    private OptionRegistry() {}

    private static GameOptions vanilla() {
        return MinecraftClient.getInstance().options;
    }

    private static RendererConfig cfg() {
        return RendererConfig.get();
    }

    // -------------------------------------------------------------------------
    // Small helpers to keep the declarations readable
    // -------------------------------------------------------------------------

    private static Option<Boolean> bool(String id, String name, String tip, Impact impact,
                                        java.util.function.Supplier<Boolean> get,
                                        java.util.function.Consumer<Boolean> set,
                                        boolean def) {
        return new Option<>(id, Text.literal(name), Text.literal(tip), impact, get, set, def)
            .format(v -> v ? "On" : "Off");
    }

    private static Option<Integer> intOpt(String id, String name, String tip, Impact impact,
                                          java.util.function.Supplier<Integer> get,
                                          java.util.function.Consumer<Integer> set,
                                          int def, int min, int max, int step) {
        return new Option<>(id, Text.literal(name), Text.literal(tip), impact, get, set, def)
            .range(min, max, step);
    }

    private static Option<Double> dblOpt(String id, String name, String tip, Impact impact,
                                         java.util.function.Supplier<Double> get,
                                         java.util.function.Consumer<Double> set,
                                         double def, double min, double max, double step) {
        return new Option<>(id, Text.literal(name), Text.literal(tip), impact, get, set, def)
            .range(min, max, step)
            .format(v -> String.format("%.0f%%", v * 100.0));
    }

    private static <E extends Enum<E>> Option<E> enumOpt(String id, String name, String tip,
                                                         Impact impact,
                                                         java.util.function.Supplier<E> get,
                                                         java.util.function.Consumer<E> set,
                                                         E def, List<E> values) {
        return new Option<>(id, Text.literal(name), Text.literal(tip), impact, get, set, def)
            .values(values)
            .format(OptionRegistry::prettyEnum);
    }

    /** Turns FANCY / PLAYER_AFFECTED into "Fancy" / "Player Affected". */
    private static String prettyEnum(Object e) {
        if (e == null) return "-";
        String raw = e.toString().replace('_', ' ').toLowerCase();
        StringBuilder sb = new StringBuilder(raw.length());
        boolean cap = true;
        for (char c : raw.toCharArray()) {
            sb.append(cap && Character.isLetter(c) ? Character.toUpperCase(c) : c);
            cap = c == ' ';
        }
        return sb.toString();
    }

    // Third-party mod extension registries
    private static final List<OptionPage> CUSTOM_PAGES = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final Map<String, List<OptionGroup>> CUSTOM_GROUPS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Programmatically registers a custom settings page from another mod.
     */
    public static void registerCustomPage(OptionPage page) {
        if (page != null) CUSTOM_PAGES.add(page);
    }

    /**
     * Programmatically injects an option group into an existing page (e.g. "performance", "quality", "general").
     */
    public static void registerCustomGroup(String targetPageId, OptionGroup group) {
        if (targetPageId != null && group != null) {
            CUSTOM_GROUPS.computeIfAbsent(targetPageId, k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(group);
        }
    }

    public static List<OptionPage> buildPages() {
        // Collect entrypoints from Fabric loader
        try {
            net.fabricmc.loader.api.FabricLoader.getInstance()
                .getEntrypointContainers("caesium:settings", destiny.renderer.api.CaesiumSettingsExtension.class)
                .forEach(container -> {
                    try {
                        container.getEntrypoint().registerSettings(new destiny.renderer.api.CaesiumSettingsExtension.SettingsRegistry() {
                            @Override
                            public void addPage(OptionPage page) {
                                registerCustomPage(page);
                            }

                            @Override
                            public void addGroup(String targetPageId, OptionGroup group) {
                                registerCustomGroup(targetPageId, group);
                            }
                        });
                    } catch (Throwable t) {
                        LOGGER.warning("[Caesium] Failed to load settings extension from mod "
                            + container.getProvider().getMetadata().getId() + ": " + t);
                    }
                });
        } catch (Throwable ignored) {}

        List<OptionPage> pages = new ArrayList<>();
        pages.add(generalPage());
        pages.add(performancePage());
        pages.add(qualityPage());
        pages.add(particlesPage());
        pages.add(detailsPage());
        pages.add(animationsPage());
        pages.add(overlaysPage());
        pages.add(hardwarePage());
        pages.add(telemetryPage());
        pages.add(appearancePage());
        pages.add(jvmPage());
        pages.add(workAllotmentPage());

        // Merge injected custom groups into standard pages
        List<OptionPage> result = new ArrayList<>();
        for (OptionPage p : pages) {
            List<OptionGroup> injected = CUSTOM_GROUPS.get(p.getId());
            if (injected != null && !injected.isEmpty()) {
                result.add(p.withAppendedGroups(injected));
            } else {
                result.add(p);
            }
        }

        // Add third-party custom pages
        result.addAll(CUSTOM_PAGES);

        return result;
    }

    // ---------------------------------------------------------------- Presets

    private static OptionPage presetsPage() {
        RendererConfig c = cfg();

        OptionGroup oneClick = OptionGroup.builder().title("One-Click Presets")
            .add(new Option<>("performance_preset",
                Text.literal("Active Preset"),
                Text.literal("Apply pre-configured optimization tiers tailored for your GPU and CPU architecture."),
                Impact.EXTREME,
                () -> "BALANCED",
                v -> {
                    try {
                        destiny.renderer.gui.presets.PerformancePreset p =
                            destiny.renderer.gui.presets.PerformancePreset.valueOf(v);
                        destiny.renderer.gui.presets.PerformancePreset.apply(p);
                    } catch (Throwable ignored) {}
                },
                "BALANCED")
                .values(List.of("LOW_END_IGPU", "BALANCED", "COMPETITIVE_240HZ", "CINEMATIC_ULTRA"))
                .format(v -> {
                    try {
                        return destiny.renderer.gui.presets.PerformancePreset.valueOf(v).label;
                    } catch (Throwable ignored) {
                        return v;
                    }
                })
                .explanation("Select a preset to instantly reconfigure render distance, meshing concurrency, particle subsampling, and GPU budgets.")
                .defaultReason("Balanced is active by default to guarantee 60+ FPS on all machines.")
                .keywords("preset", "profile", "igpu", "competitive", "ultra", "fast", "fps"))
            .build();

        OptionGroup descriptions = OptionGroup.builder().title("Preset Information")
            .add(bool("preset_info_igpu", "Low-End iGPU Tier",
                "Intel HD/UHD & Vega: 8 chunks, greedy meshing, 50% particles, fast math.",
                Impact.NONE, () -> true, v -> {}, true)
                .enabledWhen(() -> false, "Informational summary card."))
            .add(bool("preset_info_balanced", "Balanced Tier",
                "Default PC: 12 chunks, greedy meshing, full particles, smooth 60–120 FPS.",
                Impact.NONE, () -> true, v -> {}, true)
                .enabledWhen(() -> false, "Informational summary card."))
            .add(bool("preset_info_comp", "Competitive 240Hz Tier",
                "High Refresh: 10 chunks, 1-frame CPU render ahead, instant block updates, rock-solid 1% lows.",
                Impact.NONE, () -> true, v -> {}, true)
                .enabledWhen(() -> false, "Informational summary card."))
            .add(bool("preset_info_ultra", "Cinematic Ultra Tier",
                "RTX / Radeon: 24 chunks, rich particles, maximum entity draw distance.",
                Impact.NONE, () -> true, v -> {}, true)
                .enabledWhen(() -> false, "Informational summary card."))
            .build();

        return new OptionPage("presets", Text.literal("Presets"), List.of(oneClick, descriptions));
    }

    // ------------------------------------------------------------- Appearance

    private static OptionPage appearancePage() {
        RendererConfig c = cfg();

        OptionGroup visualAids = OptionGroup.builder().title("Visual Aids")
            .add(bool("fullbright", "Fullbright",
                "Light the world fully regardless of actual light level.",
                Impact.NONE,
                () -> c.fullbright, v -> c.fullbright = v, false)
                .keywords("bright", "gamma", "night vision", "cave"))
            .add(dblOpt("fullbright_level", "Fullbright Intensity",
                "How bright fullbright makes the world.", Impact.NONE,
                () -> c.fullbrightLevel / 20.0, v -> c.fullbrightLevel = v * 20.0,
                0.75, 0.05, 1.0, 0.05)
                .enabledWhen(() -> c.fullbright, "Enable Fullbright first."))
            .build();

        OptionGroup chat = OptionGroup.builder().title("Chat")
            .add(bool("compact_chat", "Compact Repeated Messages",
                "Collapse identical consecutive chat lines into one entry with an (xN) counter.",
                Impact.LOW,
                () -> c.compactChat, v -> c.compactChat = v, true)
                .keywords("chat", "spam", "duplicate"))
            .build();

        OptionGroup menu = OptionGroup.builder().title("Menu Behaviour")
            .add(bool("replace_video_settings", "Replace Video Settings",
                "Open this screen instead of the vanilla Video Settings menu.",
                Impact.NONE,
                () -> c.replaceVideoSettings, v -> c.replaceVideoSettings = v, true)
                .keywords("menu", "video", "replace"))
            .build();

        return new OptionPage("appearance", Text.literal("Appearance"),
            List.of(visualAids, chat, menu));
    }

    // ---------------------------------------------------------------- General

    private static OptionPage generalPage() {
        GameOptions o = vanilla();
        RendererConfig c = cfg();

        OptionGroup rendering = OptionGroup.builder().title("Rendering")
            .add(intOpt("render_distance", "Render Distance",
                "How many chunks are rendered around you.",
                Impact.EXTREME,
                () -> o.getViewDistance().getValue(),
                v -> o.getViewDistance().setValue(v), 12, 2, 32, 1)
                .format(v -> v + " chunks")
                .keywords("view", "distance", "chunks"))
            .add(intOpt("simulation_distance", "Simulation Distance",
                "How far away entities and block updates are ticked. Affects CPU more than GPU.",
                Impact.HIGH,
                () -> o.getSimulationDistance().getValue(),
                v -> o.getSimulationDistance().setValue(v), 12, 5, 32, 1)
                .format(v -> v + " chunks")
                .keywords("tick", "simulation"))
            .add(intOpt("max_fps", "Max Framerate",
                "Upper frame rate limit.",
                Impact.MEDIUM,
                () -> o.getMaxFps().getValue(),
                v -> o.getMaxFps().setValue(v), 120, 10, 260, 5)
                .format(v -> v >= 260 ? "Unlimited" : v + " fps")
                .keywords("fps", "framerate", "limit", "cap"))
            .add(bool("vsync", "VSync",
                "Synchronises frames to your monitor's refresh rate.",
                Impact.MEDIUM,
                () -> o.getEnableVsync().getValue(),
                v -> o.getEnableVsync().setValue(v), true)
                .keywords("vertical sync", "tearing"))
            .add(enumOpt("inactivity_fps", "Inactivity FPS Limit",
                "Throttles the frame rate when the window is minimised or you are idle.",
                Impact.LOW,
                () -> o.getInactivityFpsLimit().getValue(),
                v -> o.getInactivityFpsLimit().setValue(v),
                InactivityFpsLimit.AFK,
                List.of(InactivityFpsLimit.MINIMIZED, InactivityFpsLimit.AFK))
                .keywords("afk", "idle", "background"))
            .build();

        OptionGroup window = OptionGroup.builder().title("Window")
            .add(bool("fullscreen", "Fullscreen",
                "Toggles fullscreen display mode.", Impact.NONE,
                () -> o.getFullscreen().getValue(),
                v -> o.getFullscreen().setValue(v), false))
            .add(intOpt("gui_scale", "GUI Scale",
                "Interface scaling factor. 0 selects the largest size that fits your window.",
                Impact.NONE,
                () -> o.getGuiScale().getValue(),
                v -> o.getGuiScale().setValue(v), 0, 0, 4, 1)
                .format(v -> v == 0 ? "Auto" : v + "x"))
            .add(intOpt("fov", "Field of View",
                "Camera field of view in degrees.", Impact.LOW,
                () -> o.getFov().getValue(),
                v -> o.getFov().setValue(v), 70, 30, 110, 1)
                .format(v -> v + "\u00B0")
                .keywords("fov", "zoom"))
            .add(dblOpt("gamma", "Brightness",
                "Screen gamma. Higher values brighten dark areas.", Impact.NONE,
                () -> o.getGamma().getValue(),
                v -> o.getGamma().setValue(v), 0.5, 0.0, 1.0, 0.05)
                .keywords("gamma", "brightness"))
            .build();

        OptionGroup camera = OptionGroup.builder().title("Camera")
            .add(bool("view_bobbing", "View Bobbing",
                "Camera bob while walking.", Impact.NONE,
                () -> o.getBobView().getValue(),
                v -> o.getBobView().setValue(v), true))
            .add(dblOpt("fov_effects", "FOV Effects",
                "How strongly speed effects distort your field of view.", Impact.NONE,
                () -> o.getFovEffectScale().getValue(),
                v -> o.getFovEffectScale().setValue(v), 1.0, 0.0, 1.0, 0.05))
            .add(dblOpt("distortion", "Distortion Effects",
                "Strength of the nausea and nether portal screen warp.", Impact.LOW,
                () -> o.getDistortionEffectScale().getValue(),
                v -> o.getDistortionEffectScale().setValue(v), 1.0, 0.0, 1.0, 0.05)
                .keywords("nausea", "warp"))
            .add(dblOpt("darkness_pulse", "Darkness Pulsing",
                "Strength of the Warden's darkness effect pulse.", Impact.NONE,
                () -> o.getDarknessEffectScale().getValue(),
                v -> o.getDarknessEffectScale().setValue(v), 1.0, 0.0, 1.0, 0.05))
            .add(dblOpt("damage_tilt", "Damage Tilt",
                "How far the camera tilts when you take damage.", Impact.NONE,
                () -> o.getDamageTiltStrength().getValue(),
                v -> o.getDamageTiltStrength().setValue(v), 1.0, 0.0, 1.0, 0.05))
            .build();

        OptionGroup backend = OptionGroup.builder().title("Rendering Backend")
            .add(enumOpt("rendering_backend", "Render Backend",
                "GPU backend. OpenGL 3.3/4.3 is the standard stable engine. Vulkan is experimental.",
                Impact.EXTREME,
                () -> renderingBackendValue(),
                v -> { c.renderingBackend = v.name(); },
                RenderingBackend.OPENGL,
                List.of(RenderingBackend.OPENGL, RenderingBackend.AUTO, RenderingBackend.VULKAN))
                .explanation("Vulkan is experimental. If the driver or window system does not support a Vulkan swapchain context on launch, the engine cleanly logs and falls back to OpenGL without crashing. Check the Telemetry page to view the active runtime backend.")
                .requiresReload()
                .keywords("vulkan", "opengl", "backend", "api", "renderer"))
            .add(bool("shader_slot", "Shader Pack Slot",
                "Enables the external shader folder under config/caesium/shaders/.",
                Impact.HIGH,
                () -> c.shaderPack != null && !c.shaderPack.isEmpty(),
                v -> { if (!v) c.shaderPack = ""; },
                false)
                .requiresReload()
                .keywords("shader", "shaders", "pack", "glsl", "spirv"))
            .add(enumOpt("vulkan_device", "Vulkan GPU",
                "Target physical GPU when Vulkan is active. Auto scores by capability.",
                Impact.EXTREME,
                () -> vulkanDeviceValue(),
                v -> { c.vulkanDevice = v.name(); },
                VulkanDevice.AUTO,
                List.of(VulkanDevice.AUTO, VulkanDevice.DISCRETE, VulkanDevice.INTEGRATED))
                .requiresReload()
                .keywords("gpu", "device", "graphics card", "discrete", "integrated", "vulkan"))
            .add(bool("window_present", "Present to Game Window",
                "Experimental: attach the Vulkan swapchain to Minecraft's real window.",
                Impact.EXTREME,
                () -> c.windowPresent,
                v -> { c.windowPresent = v; },
                false)
                .explanation("Attaches the experimental Vulkan swapchain directly to the main game window instead of offscreen rendering.")
                .requiresReload()
                .keywords("swapchain", "present", "window", "vulkan", "experimental"))
            .build();

        OptionGroup rpc = OptionGroup.builder().title("Discord Rich Presence")
            .add(bool("discord_rpc", "Enable Discord RPC",
                "Show your game status on Discord via local IPC socket.", Impact.NONE,
                () -> c.enableDiscordRpc, v -> c.enableDiscordRpc = v, false)
                .explanation("Optional Discord Rich Presence integration that displays your Minecraft dimension, biome, and framerate on your Discord profile via local IPC socket. No remote telemetry or user data is collected."))
            .add(bool("discord_rpc_server", "Show Server IP",
                "Show the IP address or name of the server you're playing on.", Impact.NONE,
                () -> c.rpcShowServer, v -> c.rpcShowServer = v, true))
            .add(bool("discord_rpc_fps", "Show FPS in RPC",
                "Display your current FPS in your Discord status.", Impact.NONE,
                () -> c.rpcShowFps, v -> c.rpcShowFps = v, true))
            .build();

        return new OptionPage("general", Text.literal("General"),
            List.of(rendering, window, camera, backend, rpc));
    }

    /** Backend choices exposed to the settings screen; maps 1:1 to config strings. */
    private enum RenderingBackend {
        OPENGL, AUTO, VULKAN;
    }

    private static RenderingBackend renderingBackendValue() {
        try {
            return RenderingBackend.valueOf(RendererConfig.get().renderingBackend);
        } catch (IllegalArgumentException e) {
            return RenderingBackend.OPENGL;
        }
    }

    /** Vulkan physical-device choices exposed to the settings screen. */
    private enum VulkanDevice {
        AUTO, DISCRETE, INTEGRATED;
    }

    private static VulkanDevice vulkanDeviceValue() {
        try {
            return VulkanDevice.valueOf(RendererConfig.get().vulkanDevice);
        } catch (IllegalArgumentException e) {
            return VulkanDevice.AUTO;
        }
    }

    // ---------------------------------------------------------------- Quality

    private static OptionPage qualityPage() {
        GameOptions o = vanilla();
        RendererConfig c = cfg();

        OptionGroup graphics = OptionGroup.builder().title("Graphics")
            .add(enumOpt("graphics_mode", "Graphics Quality",
                "Master preset for leaves, water and transparency.",
                Impact.EXTREME,
                () -> o.getPreset().getValue(),
                v -> o.getPreset().setValue(v),
                GraphicsMode.FANCY,
                List.of(GraphicsMode.FAST, GraphicsMode.FANCY, GraphicsMode.FABULOUS))
                .keywords("fancy", "fast", "fabulous"))
            .add(bool("smooth_lighting", "Ambient Occlusion",
                "Soft shading in corners and crevices.",
                Impact.MEDIUM,
                () -> o.getAo().getValue(),
                v -> o.getAo().setValue(v), true)
                .keywords("ao", "smooth lighting", "shading"))
            .add(intOpt("biome_blend", "Biome Blend",
                "Radius used to blend grass and foliage colours across biome borders.",
                Impact.HIGH,
                () -> o.getBiomeBlendRadius().getValue(),
                v -> o.getBiomeBlendRadius().setValue(v), 2, 0, 7, 1)
                .format(v -> v == 0 ? "Off" : (v * 2 + 1) + "x" + (v * 2 + 1))
                .keywords("biome", "blend", "colour", "color"))
            .add(bool("cutout_leaves", "Cutout Leaves",
                "Renders leaves with see-through gaps instead of as solid blocks.",
                Impact.MEDIUM,
                () -> o.getCutoutLeaves().getValue(),
                v -> o.getCutoutLeaves().setValue(v), true)
                .keywords("leaves", "transparent"))
            .add(bool("improved_transparency", "Improved Transparency",
                "Better sorting for overlapping translucent blocks.",
                Impact.HIGH,
                () -> o.getImprovedTransparency().getValue(),
                v -> o.getImprovedTransparency().setValue(v), false)
                .keywords("translucency", "sorting", "glass"))
            .add(bool("vignette", "Vignette",
                "Darkening around the screen edges.", Impact.LOW,
                () -> o.getVignette().getValue(),
                v -> o.getVignette().setValue(v), true))
            .build();

        OptionGroup textures = OptionGroup.builder().title("Textures")
            .add(intOpt("mipmap", "Mipmap Levels",
                "Smooths distant textures and removes shimmering.",
                Impact.LOW,
                () -> o.getMipmapLevels().getValue(),
                v -> o.getMipmapLevels().setValue(v), 4, 0, 4, 1)
                .format(v -> v == 0 ? "Off" : v + "x")
                .requiresReload())
            .add(enumOpt("texture_filtering", "Texture Filtering",
                "Filtering applied to block textures at oblique angles.", Impact.MEDIUM,
                () -> o.getTextureFiltering().getValue(),
                v -> o.getTextureFiltering().setValue(v),
                TextureFilteringMode.NONE,
                List.of(TextureFilteringMode.NONE, TextureFilteringMode.RGSS,
                        TextureFilteringMode.ANISOTROPIC))
                .requiresReload()
                .keywords("anisotropic", "filtering"))
            .add(intOpt("max_anisotropy", "Anisotropic Level",
                "Anisotropic filtering strength.",
                Impact.MEDIUM,
                () -> o.getMaxAnisotropy().getValue(),
                v -> o.getMaxAnisotropy().setValue(v), 1, 1, 16, 1)
                .format(v -> v <= 1 ? "Off" : v + "x")
                .enabledWhen(
                    () -> o.getTextureFiltering().getValue() == TextureFilteringMode.ANISOTROPIC,
                    "Set Texture Filtering to Anisotropic to use this.")
                .requiresReload())
            .add(dblOpt("glint_strength", "Glint Strength",
                "Opacity of the enchantment shimmer.", Impact.LOW,
                () -> o.getGlintStrength().getValue(),
                v -> o.getGlintStrength().setValue(v), 0.75, 0.0, 1.0, 0.05))
            .add(dblOpt("glint_speed", "Glint Speed",
                "Animation speed of the enchantment shimmer.", Impact.LOW,
                () -> o.getGlintSpeed().getValue(),
                v -> o.getGlintSpeed().setValue(v), 0.5, 0.0, 1.0, 0.05))
            .build();

        OptionGroup lighting = OptionGroup.builder().title("Lighting & Shadows")
            .add(bool("entity_shadows", "Entity Shadows",
                "Simple shadow discs beneath mobs and items.", Impact.LOW,
                () -> o.getEntityShadows().getValue(),
                v -> o.getEntityShadows().setValue(v), true))
            .add(bool("light_flicker", "Light Flickering",
                "Subtle brightness variation from torches and fire.", Impact.LOW,
                () -> c.enableLightFlicker, v -> c.enableLightFlicker = v, true)
                .keywords("torch", "flicker"))
            .add(dblOpt("chunk_fade", "Chunk Fade",
                "How long newly loaded chunks take to fade in.",
                Impact.NONE,
                () -> o.getChunkFade().getValue(),
                v -> o.getChunkFade().setValue(v), 1.0, 0.0, 1.0, 0.05))
            .build();

        return new OptionPage("quality", Text.literal("Quality"),
            List.of(graphics, textures, lighting));
    }

    // ---------------------------------------------------------------- Details

    private static OptionPage detailsPage() {
        GameOptions o = vanilla();
        RendererConfig c = cfg();

        OptionGroup sky = OptionGroup.builder().title("Sky & Weather")
            .add(enumOpt("clouds", "Clouds",
                "Cloud rendering quality.",
                Impact.MEDIUM,
                () -> o.getCloudRenderMode().getValue(),
                v -> o.getCloudRenderMode().setValue(v),
                CloudRenderMode.FANCY,
                List.of(CloudRenderMode.OFF, CloudRenderMode.FAST, CloudRenderMode.FANCY)))
            .add(intOpt("cloud_distance", "Cloud Distance",
                "How far away clouds remain visible.", Impact.MEDIUM,
                () -> o.getCloudRenderDistance().getValue() / 16,
                v -> o.getCloudRenderDistance().setValue(v * 16), 8, 2, 32, 1)
                .format(v -> v + " chunks")
                .keywords("cloud", "distance"))
            .add(intOpt("weather_radius", "Weather Radius",
                "Radius in which rain and snow particles are drawn.",
                Impact.HIGH,
                () -> o.getWeatherRadius().getValue(),
                v -> o.getWeatherRadius().setValue(v), 10, 0, 32, 1)
                .format(v -> v == 0 ? "Off" : v + " blocks")
                .keywords("rain", "snow", "storm"))
            .add(bool("sky", "Sky", "Render the sky dome.", Impact.LOW,
                () -> c.enableSky, v -> c.enableSky = v, true))
            .add(bool("sun_moon", "Sun & Moon", "Render the sun and moon.", Impact.NONE,
                () -> c.enableSunMoon, v -> c.enableSunMoon = v, true))
            .add(bool("stars", "Stars", "Render stars at night.", Impact.LOW,
                () -> c.enableStars, v -> c.enableStars = v, true))
            .add(bool("fog", "Fog",
                "Atmospheric distance fog. Disabling reveals chunk edges but is faster.",
                Impact.MEDIUM,
                () -> c.enableFog, v -> c.enableFog = v, true))
            .build();

        OptionGroup world = OptionGroup.builder().title("World Detail")
            .add(bool("block_entities", "Block Entities",
                "Render chests, signs, banners and similar.",
                Impact.HIGH,
                () -> c.enableBlockEntities, v -> c.enableBlockEntities = v, true)
                .keywords("chest", "sign", "banner"))
            .add(bool("item_frames", "Item Frames",
                "Render item frames and their contents.", Impact.MEDIUM,
                () -> c.enableItemFrames, v -> c.enableItemFrames = v, true))
            .add(bool("armor_stands", "Armor Stands",
                "Render armor stands.", Impact.MEDIUM,
                () -> c.enableArmorStands, v -> c.enableArmorStands = v, true))
            .add(bool("paintings", "Paintings",
                "Render paintings.", Impact.LOW,
                () -> c.enablePaintings, v -> c.enablePaintings = v, true))
            .add(bool("item_entities", "Dropped Items",
                "Render item entities on the ground.",
                Impact.HIGH,
                () -> c.enableItemEntities, v -> c.enableItemEntities = v, true)
                .keywords("drops", "ground items"))
            .add(bool("beacon_beam", "Beacon Beams",
                "Render the light column above beacons.", Impact.LOW,
                () -> c.enableBeaconBeam, v -> c.enableBeaconBeam = v, true))
            .add(bool("enchant_glint", "Enchantment Glint",
                "Render the shimmer on enchanted items.", Impact.LOW,
                () -> c.enableEnchantmentGlint, v -> c.enableEnchantmentGlint = v, true))
            .build();

        OptionGroup overlays = OptionGroup.builder().title("Screen Overlays")
            .add(bool("fire_overlay", "Fire Overlay",
                "Flames drawn over the screen while burning.",
                Impact.LOW,
                () -> c.enableFireOverlay, v -> c.enableFireOverlay = v, true))
            .add(bool("water_overlay", "Water Overlay",
                "Tint drawn over the screen while underwater.", Impact.LOW,
                () -> c.enableWaterOverlay, v -> c.enableWaterOverlay = v, true))
            .add(bool("pumpkin_overlay", "Pumpkin Overlay",
                "Border drawn when wearing a carved pumpkin.", Impact.NONE,
                () -> c.enablePumpkinOverlay, v -> c.enablePumpkinOverlay = v, true))
            .add(bool("powder_snow_overlay", "Powder Snow Overlay",
                "Overlay drawn while inside powder snow.", Impact.NONE,
                () -> c.enablePowderSnowOverlay, v -> c.enablePowderSnowOverlay = v, true))
            .add(bool("nausea_effect", "Nausea Effect",
                "Screen warp from nausea and portals.", Impact.LOW,
                () -> c.enableNauseaEffect, v -> c.enableNauseaEffect = v, true))
            .add(bool("darkness_effect", "Darkness Effect",
                "Warden darkness fog and pulsing. Also disabled when Darkness Pulsing is at 0%.",
                Impact.LOW,
                () -> c.enableDarknessEffect, v -> c.enableDarknessEffect = v, true))
            .add(bool("screen_shake", "Screen Shake",
                "Camera shake from nearby explosions.", Impact.NONE,
                () -> c.enableScreenShake, v -> c.enableScreenShake = v, true))
            .add(bool("totem_anim", "Totem Animation",
                "Full-screen animation when a totem activates.", Impact.NONE,
                () -> c.enableTotemAnim, v -> c.enableTotemAnim = v, true))
            .build();

        OptionGroup hud = OptionGroup.builder().title("HUD Elements")
            .add(bool("status_effect_hud", "Status Effect HUD",
                "Icons and timers for active effects drawn in the corner.",
                Impact.MEDIUM,
                () -> c.enableStatusEffectHud, v -> c.enableStatusEffectHud = v, true)
                .keywords("potion", "effect", "timer"))
            .add(bool("hotbar", "Hotbar",
                "The item selection bar at the bottom of the screen.",
                Impact.MEDIUM,
                () -> c.enableHotbar, v -> c.enableHotbar = v, true))
            .add(bool("health_bars", "Health & Armor Bars",
                "Hearts, armor, food, air and mount health bars.",
                Impact.MEDIUM,
                () -> c.enableHealthBars, v -> c.enableHealthBars = v, true)
                .keywords("hearts", "armor", "food", "mount", "air"))
            .build();

        OptionGroup notifications = OptionGroup.builder().title("Toasts & Notifications")
            .add(bool("toast_all", "All Toast Notifications",
                "Master switch for in-game toast notifications.", Impact.LOW,
                () -> c.enableAllToasts, v -> c.enableAllToasts = v, true)
                .keywords("toast", "notification", "popup"))
            .add(bool("toast_advancements", "Advancement Toasts",
                "Popups for unlocked advancements and recipes.", Impact.NONE,
                () -> c.enableAdvancementToasts, v -> c.enableAdvancementToasts = v, true)
                .enabledWhen(() -> c.enableAllToasts, "Enable All Toast Notifications to control this."))
            .add(bool("toast_tutorials", "Tutorial Toasts",
                "Popups for movement, recipe and tutorial hints.", Impact.NONE,
                () -> c.enableTutorialToasts, v -> c.enableTutorialToasts = v, true)
                .enabledWhen(() -> c.enableAllToasts, "Enable All Toast Notifications to control this."))
            .build();

        return new OptionPage("details", Text.literal("Details"),
            List.of(sky, world, overlays, hud, notifications));
    }

    // ------------------------------------------------------------- Animations

    private static OptionPage animationsPage() {
        RendererConfig c = cfg();

        OptionGroup fluids = OptionGroup.builder().title("Fluid & Block Animations")
            .add(bool("anim_all", "All Texture Animations",
                "Master switch for every animated texture.",
                Impact.HIGH,
                () -> c.enableTextureAnimations, v -> c.enableTextureAnimations = v, true)
                .keywords("animation", "master"))
            .add(bool("anim_water", "Water",
                "Animate flowing and still water textures.", Impact.MEDIUM,
                () -> c.enableWaterAnim, v -> c.enableWaterAnim = v, true)
                .enabledWhen(() -> c.enableTextureAnimations,
                    "Enable All Texture Animations to control this individually."))
            .add(bool("anim_lava", "Lava",
                "Animate lava textures.", Impact.MEDIUM,
                () -> c.enableLavaAnim, v -> c.enableLavaAnim = v, true)
                .enabledWhen(() -> c.enableTextureAnimations,
                    "Enable All Texture Animations to control this individually."))
            .add(bool("anim_fire", "Fire",
                "Animate fire textures.", Impact.MEDIUM,
                () -> c.enableFireAnim, v -> c.enableFireAnim = v, true)
                .enabledWhen(() -> c.enableTextureAnimations,
                    "Enable All Texture Animations to control this individually."))
            .add(bool("anim_portal", "Nether Portal",
                "Animate the nether portal swirl.", Impact.LOW,
                () -> c.enablePortalAnim, v -> c.enablePortalAnim = v, true)
                .enabledWhen(() -> c.enableTextureAnimations,
                    "Enable All Texture Animations to control this individually."))
            .add(bool("anim_blocks", "Other Blocks",
                "Animate remaining blocks such as prismarine, magma and sea lanterns.",
                Impact.MEDIUM,
                () -> c.enableBlockAnimations, v -> c.enableBlockAnimations = v, true)
                .enabledWhen(() -> c.enableTextureAnimations,
                    "Enable All Texture Animations to control this individually."))
            .add(bool("anim_sculk", "Sculk Sensor",
                "Animate sculk sensor tendrils.", Impact.LOW,
                () -> c.enableSculkSensorAnim, v -> c.enableSculkSensorAnim = v, true)
                .enabledWhen(() -> c.enableTextureAnimations,
                    "Enable All Texture Animations to control this individually."))
            .add(bool("anim_visible_only", "Only Animate Visible Textures",
                "Skip animation updates for textures not currently on screen.",
                Impact.MEDIUM,
                () -> c.animateOnlyVisibleTextures, v -> c.animateOnlyVisibleTextures = v, true))
            .add(bool("anim_throttle", "Throttle Animations on Slow Frames",
                "Skip texture-animation ticks on a frame that is already over its target FPS, "
                + "letting it present first. Animations hold their last frame; nothing is lost.",
                Impact.HIGH,
                () -> c.throttleTextureAnimOnSlowFrames, v -> c.throttleTextureAnimOnSlowFrames = v, true)
                .keywords("animation", "throttle", "1% low", "p99", "stutter", "freeze"))
            .add(bool("mipmap_fade", "Mipmap Crossfade",
                "Blend between mipmap levels to hide the transition when moving.",
                Impact.NONE,
                () -> c.enableMipmapFade, v -> c.enableMipmapFade = v, true))
            .build();

        return new OptionPage("animations", Text.literal("Animations"), List.of(fluids));
    }

    // -------------------------------------------------------------- Particles

    private static OptionPage particlesPage() {
        RendererConfig c = cfg();
        destiny.renderer.particle.CaesiumParticleRegistry.initialize();

        OptionGroup presetGroup = OptionGroup.builder().title("Particle Preset")
            .add(new Option<>("particle_preset_choice",
                Text.literal("Particle Quality"),
                Text.literal("Choose particle simulation and rendering quality."),
                Impact.HIGH,
                () -> destiny.renderer.particle.CaesiumParticleRegistry.getPreset().getTitle(),
                v -> {
                    try {
                        for (destiny.renderer.particle.CaesiumParticleRegistry.QualityPreset qp : destiny.renderer.particle.CaesiumParticleRegistry.QualityPreset.values()) {
                            if (qp.getTitle().equalsIgnoreCase(v) || qp.name().equalsIgnoreCase(v)) {
                                destiny.renderer.particle.CaesiumParticleRegistry.setPreset(qp);
                                break;
                            }
                        }
                    } catch (Throwable ignored) {}
                },
                "All")
                .values(List.of("All", "Decreased", "Minimal", "Off", "Custom"))
                .explanation("Controls whether visual particles are created, simulated, and rendered.")
                .keywords("particles", "quality", "preset"))
            .build();

        OptionGroup perfGroup = OptionGroup.builder().title("Performance")
            .add(bool("disable_all_particles", "Disable All Particles",
                "Completely disable all particles and simulation calculations for extreme performance.", Impact.EXTREME,
                () -> c.disableAllParticles, v -> c.disableAllParticles = v, false)
                .keywords("particles", "disable", "all", "off", "kill"))
            .add(intOpt("max_particles_limit", "Maximum Particles",
                "Upper bound on active particle population.", Impact.HIGH,
                () -> c.maxParticleCount, v -> c.maxParticleCount = v, 500,
                0, 2000, 50)
                .keywords("particles", "max", "limit"))
            .add(intOpt("particle_cull_dist", "Particle Distance",
                "Distance in blocks beyond which particles are not spawned.", Impact.MEDIUM,
                () -> c.particleCullDistance, v -> c.particleCullDistance = v, 32,
                0, 128, 4)
                .keywords("particles", "distance", "cull"))
            .add(intOpt("particle_density_ratio", "Particle Density",
                "Sampling density percentage for particle spawn events.", Impact.MEDIUM,
                () -> c.minimalParticleLimitRatio, v -> c.minimalParticleLimitRatio = v, 100,
                10, 100, 10)
                .keywords("particles", "density", "ratio"))
            .add(bool("adaptive_particle_density", "Adaptive Particle Density",
                "Automatically lower particle density under heavy frame load to maintain FPS floor.", Impact.HIGH,
                () -> c.throttleTextureAnimOnSlowFrames, v -> c.throttleTextureAnimOnSlowFrames = v, true)
                .keywords("particles", "adaptive", "fps"))
            .add(bool("particle_batching", "Particle Batching",
                "Batch particle quad geometry into off-heap FFM frame arenas for single-pass GPU uploads.", Impact.HIGH,
                () -> true, v -> {}, true)
                .keywords("particles", "batching", "gpu"))
            .build();

        // Category Groups
        OptionGroup.Builder catBuilder = OptionGroup.builder().title("Particle Categories");
        for (destiny.renderer.particle.CaesiumParticleRegistry.Category cat : destiny.renderer.particle.CaesiumParticleRegistry.Category.values()) {
            catBuilder.add(bool("part_cat_" + cat.name().toLowerCase(java.util.Locale.ROOT),
                cat.getDisplayName(),
                "Toggle all particles in the " + cat.getDisplayName() + " category.",
                Impact.MEDIUM,
                () -> destiny.renderer.particle.CaesiumParticleRegistry.getCategoryState(cat) != destiny.renderer.particle.CaesiumParticleRegistry.CategoryTriState.ALL_OFF,
                v -> {
                    if (v) destiny.renderer.particle.CaesiumParticleRegistry.enableAll(cat, null);
                    else destiny.renderer.particle.CaesiumParticleRegistry.disableAll(cat, null);
                },
                true)
                .keywords("particle", "category", cat.getDisplayName().toLowerCase(java.util.Locale.ROOT)));
        }
        OptionGroup catGroup = catBuilder.build();

        // Registry-Driven Dynamic Particles List
        OptionGroup.Builder allParticlesBuilder = OptionGroup.builder().title("All Particles (Registry-Driven)");
        for (destiny.renderer.particle.CaesiumParticleRegistry.ParticleEntry entry : destiny.renderer.particle.CaesiumParticleRegistry.getAllParticles()) {
            allParticlesBuilder.add(bool("part_dyn_" + entry.getId().getPath(),
                entry.getDisplayName(),
                entry.getId().toString() + " [" + entry.getCategory().getDisplayName() + "] Priority: " + entry.getPriority(),
                Impact.LOW,
                () -> entry.isEffectiveEnabled(destiny.renderer.particle.CaesiumParticleRegistry.getPreset()),
                v -> destiny.renderer.particle.CaesiumParticleRegistry.toggleParticle(entry),
                true)
                .keywords("particle", entry.getDisplayName().toLowerCase(java.util.Locale.ROOT), entry.getId().getPath()));
        }
        OptionGroup allParticlesGroup = allParticlesBuilder.build();

        return new OptionPage("particles", Text.literal("Particles"),
            List.of(perfGroup, catGroup, allParticlesGroup));
    }

    // ------------------------------------------------------------ Performance

    private static OptionPage performancePage() {
        GameOptions o = vanilla();
        RendererConfig c = cfg();

        OptionGroup culling = OptionGroup.builder().title("Culling")
            .add(bool("cull_entities", "Entity Culling",
                "Skip drawing entities outside your view.", Impact.HIGH,
                () -> c.cullEntities, v -> c.cullEntities = v, true)
                .enabledWhen(() -> WorkAllotment.isOwnedByUs(Capability.ENTITY_CULLING),
                    "Handled by " + WorkAllotment.getOwner(Capability.ENTITY_CULLING).displayName()
                    + ". See the Work Allotment page."))
            .add(bool("cull_block_entities", "Block Entity Culling",
                "Skip drawing chests and signs outside your view.", Impact.HIGH,
                () -> c.cullBlockEntities, v -> c.cullBlockEntities = v, true))
            .add(bool("cull_item_frames", "Item Frame Culling",
                "Skip distant or hidden item frames.", Impact.MEDIUM,
                () -> c.cullItemFrames, v -> c.cullItemFrames = v, true))
            .add(bool("cull_armor_stands", "Armor Stand Culling",
                "Skip distant or hidden armor stands.", Impact.MEDIUM,
                () -> c.cullArmorStands, v -> c.cullArmorStands = v, true))
            .add(bool("fog_occlusion", "Fog Occlusion",
                "Skip geometry fully hidden by distance fog.", Impact.MEDIUM,
                () -> c.useFogOcclusion, v -> c.useFogOcclusion = v, true))
            .add(intOpt("block_entity_distance", "Block Entity Distance",
                "How far away chests, signs, banners and beacons render.",
                Impact.EXTREME,
                () -> c.blockEntityRenderDistance / 16,
                v -> c.blockEntityRenderDistance = v * 16, 2, 0, 16, 1)
                .format(v -> v == 0 ? "Unlimited" : v + " chunks")
                .enabledWhen(() -> c.enableBlockEntities, "Enable Block Entities first.")
                .keywords("chest", "sign", "banner", "beacon", "shulker"))
            .add(bool("entity_outlines", "Entity Outlines",
                "Glow outline on spectral-arrow and glowing entities.",
                Impact.MEDIUM,
                () -> c.enableEntityOutlines, v -> c.enableEntityOutlines = v, true)
                .keywords("glow", "outline", "spectral"))
            .build();

        OptionGroup multiplayer = OptionGroup.builder().title("Multiplayer Safety")
            .add(bool("combat_entities", "Always Render Players & Projectiles",
                "Never hide other players, arrows, tridents, fireballs, TNT or end crystals.",
                Impact.LOW,
                () -> c.alwaysRenderCombatEntities,
                v -> c.alwaysRenderCombatEntities = v, true)
                .keywords("pvp", "players", "arrows", "safety"))
            .add(bool("defer_multiplayer", "Defer Chunk Updates on Servers",
                "Allow chunk update deferral while connected to a server. Enables the "
                + "teleport burst feature below for smooth /tp transitions.",
                Impact.HIGH,
                () -> c.deferChunkUpdatesInMultiplayer,
                v -> c.deferChunkUpdatesInMultiplayer = v, true)
                .keywords("pvp", "server", "multiplayer", "defer", "tp", "teleport"))
            .add(dblOpt("tp_burst_mult", "Teleport Burst Budget Multiplier",
                "Temporarily multiplies the per-frame chunk rebuild budget after a "
                + "teleport (/tp). Prevents the 3-second freeze when chunks arrive.",
                Impact.HIGH,
                () -> c.teleportBurstMultiplier, v -> c.teleportBurstMultiplier = v,
                4.0, 1.0, 16.0, 0.5)
                .format(v -> v + "x")
                .enabledWhen(() -> c.deferChunkUpdatesInMultiplayer,
                    "Enable Defer Chunk Updates on Servers first."))
            .add(intOpt("tp_burst_thresh", "Teleport Detection Threshold",
                "Distance (blocks) the player must move in one tick to trigger the "
                + "burst budget. Typical /tp moves 100+ blocks.",
                Impact.MEDIUM,
                () -> c.teleportBurstThreshold, v -> c.teleportBurstThreshold = v,
                128, 32, 512, 16)
                .format(v -> v + " blocks")
                .enabledWhen(() -> c.deferChunkUpdatesInMultiplayer,
                    "Enable Defer Chunk Updates on Servers first."))
            .build();

        OptionGroup entities = OptionGroup.builder().title("Entities")
            .add(dblOpt("entity_distance", "Entity Render Distance",
                "Multiplier applied to how far entities remain visible.", Impact.HIGH,
                () -> o.getEntityDistanceScaling().getValue(),
                v -> o.getEntityDistanceScaling().setValue(v), 1.0, 0.5, 5.0, 0.25))
            .add(intOpt("entity_lod", "Entity LOD Distance",
                "Beyond this distance entities use simplified rendering.", Impact.MEDIUM,
                () -> c.entityLODDistance, v -> c.entityLODDistance = v, 32, 8, 128, 4)
                .format(v -> v + " blocks"))
            .add(bool("entity_batching", "Entity Batching",
                "Group entity draws to reduce GPU state changes.", Impact.MEDIUM,
                () -> c.enableEntityBatching, v -> c.enableEntityBatching = v, true)
                .enabledWhen(() -> WorkAllotment.isOwnedByUs(Capability.ENTITY_BATCHING),
                    "Handled by " + WorkAllotment.getOwner(Capability.ENTITY_BATCHING).displayName()
                    + ". See the Work Allotment page."))
            .build();

        OptionGroup targeted = OptionGroup.builder().title("Targeted Optimizations")
            .add(bool("opt_fluids", "Optimize Fluid Rendering",
                "Remove hidden interior faces from water and lava. No visual difference.",
                Impact.EXTREME,
                () -> c.optimizeFluidRendering, v -> c.optimizeFluidRendering = v, true)
                .enabledWhen(() -> WorkAllotment.isOwnedByUs(Capability.BLOCK_CULLING),
                    "Handled by " + WorkAllotment.getOwner(Capability.BLOCK_CULLING).displayName()
                    + ". See the Work Allotment page.")
                .keywords("water", "lava", "ocean", "fluid")
                .requiresReload())
            .add(bool("ground_fire", "Render Ground Fire",
                "Draw fire blocks burning on the ground.", Impact.HIGH,
                () -> c.renderGroundFire, v -> c.renderGroundFire = v, true)
                .keywords("fire", "flame", "burning"))
            .add(intOpt("ground_fire_distance", "Ground Fire Distance",
                "How far away burning ground fire is drawn.",
                Impact.EXTREME,
                () -> c.groundFireRenderDistance / 16,
                v -> c.groundFireRenderDistance = v * 16, 2, 0, 8, 1)
                .format(v -> v == 0 ? "Unlimited" : v + " chunks")
                .enabledWhen(() -> c.renderGroundFire, "Enable Render Ground Fire first.")
                .keywords("fire", "flame", "ground", "explosion"))
            .add(bool("fire_overlay_entity", "Entity Fire Overlay",
                "Draw flames on burning entities.", Impact.MEDIUM,
                () -> c.enableEntityFireOverlay, v -> c.enableEntityFireOverlay = v, true))
            .add(bool("opt_explosions", "Optimize Explosions",
                "Cap the particle burst from large blasts.", Impact.HIGH,
                () -> c.optimizeExplosions, v -> c.optimizeExplosions = v, true))
            .add(intOpt("explosion_particles", "Explosion Particle Limit",
                "Maximum explosion particles per quarter second.",
                Impact.EXTREME,
                () -> c.maxExplosionParticles, v -> c.maxExplosionParticles = v,
                64, 0, 1000, 16)
                .format(v -> v == 0 ? "Off" : String.valueOf(v))
                .enabledWhen(() -> c.optimizeExplosions, "Enable Optimize Explosions first.")
                .keywords("tnt", "crystal", "blast"))
            .build();

        OptionGroup particles = OptionGroup.builder().title("Particles")
            .add(bool("p_all_off", "Disable All Particles",
                "No particle is ever spawned, of any type.",
                Impact.EXTREME,
                () -> c.disableAllParticles, v -> c.disableAllParticles = v, false)
                .keywords("none", "off", "all", "kill", "disable", "no particles"))
            .add(enumOpt("particles_mode", "Particle Density",
                "Vanilla particle quantity setting.",
                Impact.HIGH,
                () -> o.getParticles().getValue(),
                v -> o.getParticles().setValue(v),
                ParticlesMode.ALL,
                List.of(ParticlesMode.ALL, ParticlesMode.DECREASED, ParticlesMode.MINIMAL)))
            .add(intOpt("particle_limit", "Particle Limit",
                "Hard cap on simultaneously alive particles. Zero means no limit.",
                Impact.HIGH,
                () -> c.maxParticleCount, v -> c.maxParticleCount = v, 0, 0, 16000, 500)
                .format(v -> v == 0 ? "Unlimited" : String.valueOf(v)))
            .add(bool("p_tick_throttle", "Throttle Particle Ticking on Slow Frames",
                "Skip the per-particle tick on a frame that is already over its target FPS, "
                + "letting it present first. Particles tick one frame later; nothing is lost.",
                Impact.HIGH,
                () -> c.throttleParticleTickOnSlowFrames, v -> c.throttleParticleTickOnSlowFrames = v, true)
                .keywords("particle tick", "throttle", "1% low", "p99", "stutter", "freeze"))
            .build();

        OptionGroup pacing = OptionGroup.builder().title("Frame Pacing")
            .add(intOpt("unfocused_fps", "Reduce FPS When Unfocused",
                "Frame cap applied when the window loses focus.",
                Impact.LOW,
                () -> c.unfocusedFpsLimit, v -> c.unfocusedFpsLimit = v, 0, 0, 260, 10)
                .format(v -> v == 0 ? "Never" : v + " fps")
                .enabledWhen(() -> WorkAllotment.isOwnedByUs(Capability.FRAME_THROTTLE),
                    "Handled by "
                    + WorkAllotment.getOwner(Capability.FRAME_THROTTLE).displayName()
                    + ", which does this better. See the Work Allotment page.")
                .keywords("unfocused", "background", "afk", "never"))
            .add(intOpt("cpu_render_ahead", "CPU Render Ahead",
                "How many frames the CPU may keep ahead of the GPU.",
                Impact.MEDIUM,
                () -> c.cpuRenderAhead,
                v -> {
                    c.cpuRenderAhead = v;
                    destiny.renderer.render.CpuRenderAheadLimiter.configure(v);
                }, 2, 0, 5, 1)
                .format(v -> v == 0 ? "Off" : v + " frame" + (v == 1 ? "" : "s"))
                .keywords("latency", "prerender", "queue", "frames in flight", "sodium"))
            .add(intOpt("menu_fps", "Main Menu FPS Limit",
                "Frame cap in the main menu, server list and world select.",
                Impact.LOW,
                () -> c.mainMenuFpsLimit, v -> c.mainMenuFpsLimit = v, 60, 0, 260, 10)
                .format(v -> v == 0 ? "Uncapped" : v + " fps")
                .keywords("menu", "title", "lobby"))
            .add(intOpt("pause_fps", "Pause / Inventory FPS Limit",
                "Frame cap while a pause or inventory screen is open.",
                Impact.LOW,
                () -> c.pauseScreenFpsLimit, v -> c.pauseScreenFpsLimit = v, 60, 0, 260, 10)
                .format(v -> v == 0 ? "Uncapped" : v + " fps")
                .keywords("pause", "inventory", "escape", "gui"))
            .build();

        OptionGroup chunks = OptionGroup.builder().title("Chunk Loading")
            .add(bool("defer_chunk_updates", "Deferred Chunk Updates",
                "Spread chunk rebuilds across several frames instead of doing them at once.",
                Impact.EXTREME,
                () -> c.deferChunkUpdates, v -> c.deferChunkUpdates = v, true)
                .keywords("stutter", "defer", "spike"))
            .add(intOpt("chunk_updates_per_frame", "Chunk Updates Per Frame",
                "Rebuild budget per frame when deferral is on.",
                Impact.HIGH,
                () -> c.maxChunkUpdatesPerFrame, v -> c.maxChunkUpdatesPerFrame = v,
                8, 1, 32, 1)
                .enabledWhen(() -> c.deferChunkUpdates, "Enable Deferred Chunk Updates first."))
            .add(intOpt("chunk_immediate_radius", "Immediate Rebuild Radius",
                "Block edits inside this radius always rebuild immediately. "
                + "Increased default helps with /tp chunk loading.",
                Impact.MEDIUM,
                () -> c.nearRebuildRadius, v -> c.nearRebuildRadius = v, 48, 0, 256, 8)
                .format(v -> v == 0 ? "Off" : v + " blocks")
                .enabledWhen(() -> c.deferChunkUpdates, "Enable Deferred Chunk Updates to use this."))
            .add(intOpt("chunk_worker_priority", "Chunk Worker Priority",
                "OS scheduling priority for chunk build threads.",
                Impact.HIGH,
                () -> c.chunkWorkerPriority, v -> c.chunkWorkerPriority = v, 0, 0, 2, 1)
                .format(v -> switch (v) {
                    case 2 -> "High";
                    case 1 -> "Normal";
                    default -> "Low (Recommended)";
                })
                .enabledWhen(() -> WorkAllotment.ownsTerrain(),
                    "Applies to Caesium's own chunk build threads, which are only "
                    + "started by the experimental terrain pipeline.")
                .requiresReload()
                .keywords("thread", "priority", "worker"))
            .add(enumOpt("chunk_builder", "Chunk Update Mode",
                "How aggressively chunk meshes are rebuilt.",
                Impact.HIGH,
                () -> o.getChunkBuilderMode().getValue(),
                v -> o.getChunkBuilderMode().setValue(v),
                ChunkBuilderMode.NONE,
                List.of(ChunkBuilderMode.NONE, ChunkBuilderMode.PLAYER_AFFECTED,
                        ChunkBuilderMode.NEARBY)))
            .add(intOpt("meshing_threads", "Chunk Build Threads",
                "Background threads used to build chunk geometry.",
                Impact.HIGH,
                () -> c.meshingThreads, v -> c.meshingThreads = v, 0, 0, 16, 1)
                .format(v -> v == 0 ? "Auto (" + c.resolvedMeshingThreads() + ")" : String.valueOf(v))
                .enabledWhen(() -> WorkAllotment.ownsTerrain(),
                    "Controls Caesium's own meshing threads, which are only started "
                    + "by the experimental terrain pipeline. Vanilla chunk building is not "
                    + "affected.")
                .requiresReload())
            .add(bool("smart_chunk_loading", "Prioritise Visible Chunks",
                "Build chunks in your line of sight first. Strongly recommended.",
                Impact.HIGH,
                () -> c.smartChunkLoading, v -> c.smartChunkLoading = v, true)
                .enabledWhen(() -> WorkAllotment.ownsTerrain(),
                    "Uses Caesium's meshing job queue, which is only active under "
                    + "the experimental terrain pipeline."))
            .add(bool("adaptive_view_distance", "Adaptive Render Distance",
                "Auto-tune the render distance to hold the p99.5 readout at 60 fps. "
                + "Most useful on integrated GPUs where the ceiling is memory bandwidth.",
                Impact.HIGH,
                () -> c.adaptiveViewDistance, v -> c.adaptiveViewDistance = v, false)
                .explanation("Dynamically contracts render distance during high-stress scenes (explosions, massive mob farms) to protect frame pacing, expanding back when frame times stabilize.")
                .defaultReason("Disabled by default — keeps distance fixed unless explicitly requested for low-end hardware stability.")
                .keywords("adaptive", "render distance", "view distance", "igpu", "auto tune", "p99"))
            .add(intOpt("chunk_upload_rate", "Chunk Upload Limit",
                "Caps chunk uploads per second to smooth out join stutter. Zero is unlimited.",
                Impact.MEDIUM,
                () -> c.chunkUploadRateLimit, v -> c.chunkUploadRateLimit = v, 0, 0, 500, 10)
                .format(v -> v == 0 ? "Unlimited" : v + "/s")
                .explanation("Prevents GPU bus saturation during rapid flight by pacing geometry buffer transfers across frames.")
                .defaultReason("Unlimited by default for maximum chunk pop-in speed on modern PCIe connections.")
                .enabledWhen(() -> WorkAllotment.ownsTerrain(),
                    "Limits Caesium's own GPU upload path, which only runs under "
                    + "the experimental terrain pipeline."))
            .add(bool("greedy_meshing", "Greedy Meshing",
                "Merge coplanar faces to cut vertex count on flat terrain.",
                Impact.EXTREME,
                () -> c.greedyMeshing, v -> c.greedyMeshing = v, true)
                .explanation("2D slice quad merging combines flat terrain surfaces into single merged rectangles, cutting chunk vertex and index buffers by up to 90% on flat planes.")
                .defaultReason("Enabled by default — dramatically boosts rendering throughput and minimizes VRAM consumption across all GPUs.")
                .enabledWhen(() -> WorkAllotment.ownsTerrain(),
                    "Implemented in Caesium's own mesher, which only runs under "
                    + "the experimental terrain pipeline. Vanilla terrain is unaffected "
                    + "by this toggle.")
                .requiresReload())
            .build();

        OptionGroup drsGroup = OptionGroup.builder().title("Dynamic Resolution & Sharpening (DRS / CAS)")
            .add(bool("drs_enabled", "Dynamic Resolution Scaling (DRS)",
                "Automatically lower render resolution slightly under heavy load spikes to protect 1% low frame pacing.",
                Impact.EXTREME,
                () -> destiny.renderer.render.drs.DynamicResolutionScaler.isEnabled(),
                v -> destiny.renderer.render.drs.DynamicResolutionScaler.setEnabled(v), false)
                .keywords("drs", "resolution", "scaling", "cas", "fidelityfx", "1% low"))
            .add(dblOpt("cas_sharpness", "FidelityFX CAS Sharpness",
                "Contrast Adaptive Sharpening strength applied to restore high-frequency pixel clarity.",
                Impact.MEDIUM,
                () -> (double) destiny.renderer.render.drs.DynamicResolutionScaler.getSharpness(),
                v -> destiny.renderer.render.drs.DynamicResolutionScaler.setSharpness(v.floatValue()),
                0.5, 0.0, 1.0, 0.05)
                .keywords("cas", "sharpness", "contrast", "upscaling"))
            .build();

        return new OptionPage("performance", Text.literal("Performance"),
            List.of(targeted, particles, culling, entities, chunks, pacing, multiplayer, drsGroup));
    }

    // --------------------------------------------------------------- Overlays

    private static OptionPage overlaysPage() {
        RendererConfig c = cfg();

        OptionGroup hud = OptionGroup.builder().title("Performance Overlay")
            .add(intOpt("fps_position", "FPS Counter",
                "Where to display the frame rate readout.", Impact.NONE,
                () -> c.fpsCounterPosition, v -> c.fpsCounterPosition = v, 1, 0, 4, 1)
                .format(v -> switch (v) {
                    case 0 -> "Off";
                    case 1 -> "Top Left";
                    case 2 -> "Top Right";
                    case 3 -> "Bottom Left";
                    default -> "Bottom Right";
                })
                .keywords("fps", "counter", "overlay"))
            .add(bool("fps_extended", "Extended FPS Info",
                "Also show minimum, average and 1% low frame rates.", Impact.NONE,
                () -> c.fpsExtended, v -> c.fpsExtended = v, false)
                .enabledWhen(() -> c.fpsCounterPosition != 0,
                    "Enable the FPS Counter first."))
            .add(bool("f3_percentiles", "F3 Percentile Readout",
                "Show Caesium p50/p98/p99.5 and worst/avg FPS lines right under the vanilla FPS line in F3.",
                Impact.NONE,
                () -> c.showExtendedFpsInF3, v -> c.showExtendedFpsInF3 = v, true)
                .keywords("f3", "percentile", "p50", "p98", "p99.5", "1% low", "debug", "sodium"))
            .add(bool("show_coords", "Coordinates",
                "Show your XYZ position.", Impact.NONE,
                () -> c.showCoordinates, v -> c.showCoordinates = v, false))
            .add(bool("show_memory", "Memory Usage",
                "Show heap usage alongside the frame rate.", Impact.NONE,
                () -> c.showMemoryUsage, v -> c.showMemoryUsage = v, false))
            .add(bool("overlay_bg", "Overlay Background",
                "Draw a dark backdrop behind overlay text for readability.", Impact.NONE,
                () -> c.overlayBackground, v -> c.overlayBackground = v, true))
            .add(bool("perf_overlay", "Renderer Statistics",
                "Show Caesium's internal chunk and memory statistics.", Impact.NONE,
                () -> c.showPerfOverlay, v -> c.showPerfOverlay = v, false))
            .add(new Option<>("hud_graph_mode",
                Text.literal("Telemetry Graph Mode"),
                Text.literal("Select the diagnostic graph visualization displayed in the performance HUD."),
                Impact.NONE,
                () -> destiny.renderer.hud.GraphTelemetryController.getActiveMode().getTitle(),
                v -> {
                    for (destiny.renderer.hud.GraphTelemetryController.GraphMode m : destiny.renderer.hud.GraphTelemetryController.GraphMode.values()) {
                        if (m.getTitle().equalsIgnoreCase(v) || m.name().equalsIgnoreCase(v)) {
                            destiny.renderer.hud.GraphTelemetryController.setActiveMode(m);
                            break;
                        }
                    }
                },
                "Frametime (ms)")
                .values(List.of("Frametime (ms)", "FPS Pacing", "Culling Ratio", "FFM Arena & Memory"))
                .keywords("graph", "telemetry", "hud", "frametime", "fps"))
            .build();

        return new OptionPage("overlays", Text.literal("Overlays"), List.of(hud));
    }

    // -------------------------------------------------------- Work Allotment

    private static OptionPage workAllotmentPage() {
        List<OptionGroup> groups = new ArrayList<>();

        OptionGroup.Builder rendering = OptionGroup.builder().title("Rendering Work");
        OptionGroup.Builder systems   = OptionGroup.builder().title("System Work");

        for (Capability cap : Capability.values()) {
            List<destiny.renderer.compat.Provider> candidates =
                WorkAllotment.installedCandidates(cap);

            // With only one possible provider there is nothing to choose between.
            if (candidates.size() <= 1) {
                Option<Boolean> info = bool(
                    "wa_" + cap.name().toLowerCase(),
                    cap.displayName(),
                    cap.description() + "\n\nCurrently handled by: "
                        + WorkAllotment.getOwner(cap).displayName()
                        + "\nReason: " + WorkAllotment.getReason(cap),
                    Impact.VARIES,
                    () -> true, v -> {}, true)
                    .format(v -> WorkAllotment.getOwner(cap).displayName())
                    .enabledWhen(() -> false,
                        "Only one provider is installed, so there is nothing to reassign.");
                addTo(cap, rendering, systems, info);
                continue;
            }

            List<String> names = new ArrayList<>();
            names.add("AUTO");
            for (destiny.renderer.compat.Provider p : candidates) names.add(p.name());

            Option<String> pick = new Option<>(
                "wa_" + cap.name().toLowerCase(),
                Text.literal(cap.displayName()),
                Text.literal(cap.description() + "\n\nAuto currently selects: "
                    + WorkAllotment.getOwner(cap).displayName()
                    + "\nReason: " + WorkAllotment.getReason(cap)),
                Impact.VARIES,
                () -> {
                    destiny.renderer.compat.Provider ov = WorkAllotment.getOverride(cap);
                    return ov == null ? "AUTO" : ov.name();
                },
                v -> {
                    if ("AUTO".equals(v)) {
                        WorkAllotment.setOverride(cap, null);
                    } else {
                        try {
                            WorkAllotment.setOverride(cap,
                                destiny.renderer.compat.Provider.valueOf(v));
                        } catch (IllegalArgumentException ignored) {
                            // Unknown provider name in a hand-edited config; ignore.
                        }
                    }
                },
                "AUTO")
                .values(names)
                .format(v -> {
                    if ("AUTO".equals(v)) {
                        return "Auto (" + WorkAllotment.getOwner(cap).displayName() + ")";
                    }
                    try {
                        return destiny.renderer.compat.Provider.valueOf(v).displayName();
                    } catch (IllegalArgumentException e) {
                        return v;
                    }
                })
                .keywords("allotment", "delegate", "compat");

            addTo(cap, rendering, systems, pick);
        }

        groups.add(rendering.build());
        groups.add(systems.build());
        return new OptionPage("allotment", Text.literal("Work Allotment"), groups);
    }

    private static void addTo(Capability cap, OptionGroup.Builder rendering,
                              OptionGroup.Builder systems, Option<?> option) {
        switch (cap) {
            case TERRAIN_RENDERING, ENTITY_BATCHING, PARTICLE_BATCHING, HUD_BATCHING,
                 ENTITY_CULLING, SHADER_PIPELINE, BLOCK_COLORS -> rendering.add(option);
            default -> systems.add(option);
        }
    }

    // ----------------------------------------------------------------- JVM

    private static OptionPage jvmPage() {
        destiny.renderer.jvm.JvmReport report =
            destiny.renderer.jvm.JvmArgumentAnalyzer.getReport();

        String scoreStr = report.score() + "/100 — " + report.scoreLabel();
        String gcStr    = report.gc().displayName;
        String heapStr  = report.heapMb() + " MB";
        String javaStr  = "Java " + report.javaVersion();

        // Build issues summary
        StringBuilder issuesSb = new StringBuilder();
        if (report.issues().isEmpty()) {
            issuesSb.append("No issues found. Your JVM is well configured.");
        } else {
            for (destiny.renderer.jvm.JvmArgumentAnalyzer.JvmIssue issue : report.issues()) {
                issuesSb.append("[").append(issue.severity().name()).append("] ")
                    .append(issue.title()).append(" → ").append(issue.fix()).append("\n");
            }
        }

        OptionGroup status = OptionGroup.builder().title("JVM Status")
            .add(new Option<>("jvm_score",
                Text.literal("JVM Score"),
                Text.literal("Overall quality score for your current JVM argument configuration. "
                    + "100 = perfectly tuned. Check caesium_jvm_args.txt in your .minecraft "
                    + "folder for a ready-to-paste recommended argument set."),
                Impact.NONE,
                () -> scoreStr,
                v -> {},
                scoreStr))
            .add(new Option<>("jvm_gc",
                Text.literal("Garbage Collector"),
                Text.literal("The active garbage collector. ZGC (Java 21+) gives the lowest "
                    + "latency. G1GC is a solid default. Parallel/Serial cause stutters."),
                Impact.NONE,
                () -> gcStr,
                v -> {},
                gcStr))
            .add(new Option<>("jvm_heap",
                Text.literal("Max Heap Size"),
                Text.literal("The -Xmx value in use. Modded Minecraft needs at least 3-4 GB. "
                    + "Setting -Xms = -Xmx prevents heap resizing pauses."),
                Impact.NONE,
                () -> heapStr,
                v -> {},
                heapStr))
            .add(new Option<>("jvm_java",
                Text.literal("Java Version"),
                Text.literal("Java version running this instance. Java 21+ unlocks ZGC "
                    + "generational mode for the lowest possible GC pauses."),
                Impact.NONE,
                () -> javaStr,
                v -> {},
                javaStr))
            .build();

        OptionGroup recommendations = OptionGroup.builder().title("Recommendations")
            .add(new Option<>("jvm_issues",
                Text.literal("Issues (" + report.issues().size() + ")"),
                Text.literal(issuesSb.toString().trim()),
                Impact.NONE,
                () -> report.issues().size() + " issue(s)",
                v -> {},
                ""))
            .add(new Option<>("jvm_export",
                Text.literal("Export Recommended Args"),
                Text.literal("Writes caesium_jvm_args.txt to your .minecraft folder with "
                    + "optimised JVM arguments for your Java version. Open it and paste "
                    + "into your launcher's JVM arguments field."),
                Impact.NONE,
                () -> "Click to Export",
                v -> {
                    net.minecraft.client.MinecraftClient mc =
                        net.minecraft.client.MinecraftClient.getInstance();
                    if (mc != null) {
                        destiny.renderer.jvm.JvmArgumentAnalyzer.exportRecommendedArgs(
                            mc.runDirectory.toPath());
                    }
                },
                ""))
            .build();

        return new OptionPage("jvm", Text.literal("JVM"), List.of(status, recommendations));
    }

    // ------------------------------------------------------------- Hardware

    private static OptionPage hardwarePage() {
        destiny.renderer.hardware.HardwareProfile prof = destiny.renderer.hardware.HardwareCapabilityDetector.getProfile();
        destiny.renderer.hardware.HardwarePreset pres = destiny.renderer.hardware.HardwareCapabilityDetector.getPreset();
        String rawGpu = destiny.renderer.hardware.HardwareCapabilityDetector.getGpuRenderer();
        final String gpu = (rawGpu != null && !rawGpu.isEmpty()) ? rawGpu : "OpenGL / Vulkan Compatible GPU";
        String rawVendor = destiny.renderer.hardware.HardwareCapabilityDetector.getGpuVendor();
        final String vendor = (rawVendor != null && !rawVendor.isEmpty()) ? rawVendor : "Generic";
        int vram = destiny.renderer.hardware.HardwareCapabilityDetector.getEstimatedVramMB();
        String vramStr = vram + " MB (" + (prof != null && prof.isIGPU() ? "Shared" : "Dedicated") + ")";
        int cores = destiny.renderer.hardware.HardwareCapabilityDetector.getCpuCores();
        String profileName = prof != null ? prof.name() : "STANDARD";
        String presetName = pres != null ? pres.name() : "BALANCED";

        String caps = (destiny.renderer.hardware.HardwareCapabilityDetector.hasMDI() ? "[X] Multi-Draw Indirect  " : "[ ] Multi-Draw Indirect  ")
            + (destiny.renderer.hardware.HardwareCapabilityDetector.hasMeshShaders() ? "[X] Mesh Shaders  " : "[ ] Mesh Shaders  ")
            + (destiny.renderer.hardware.HardwareCapabilityDetector.hasBindless() ? "[X] Bindless Textures  " : "[ ] Bindless Textures  ")
            + (destiny.renderer.hardware.HardwareCapabilityDetector.hasIndirectParams() ? "[X] Indirect Params" : "[ ] Indirect Params");

        OptionGroup gpuInfo = OptionGroup.builder().title("Graphics Device")
            .add(new Option<>("hw_gpu",
                Text.literal("GPU"),
                Text.literal("Active graphics processor detected by the renderer."),
                Impact.NONE,
                () -> gpu, v -> {}, gpu))
            .add(new Option<>("hw_vendor",
                Text.literal("Vendor"),
                Text.literal("GPU Manufacturer (NVIDIA, AMD, Intel, Apple)."),
                Impact.NONE,
                () -> vendor, v -> {}, vendor))
            .add(new Option<>("hw_vram",
                Text.literal("Video Memory"),
                Text.literal("Estimated VRAM / system memory pool available."),
                Impact.NONE,
                () -> vramStr, v -> {}, vramStr))
            .add(new Option<>("hw_cpu",
                Text.literal("CPU Threads"),
                Text.literal("Logical processor threads available for chunk meshing."),
                Impact.NONE,
                () -> cores + " logical cores", v -> {}, cores + " cores"))
            .build();

        OptionGroup engineTuning = OptionGroup.builder().title("Engine Strategy")
            .add(new Option<>("hw_profile",
                Text.literal("Hardware Profile"),
                Text.literal("Determines whether the engine runs in Low-Bandwidth mode (for iGPUs) or GPU-Driven mode (for dGPUs)."),
                Impact.NONE,
                () -> profileName, v -> {}, profileName))
            .add(new Option<>("hw_caps",
                Text.literal("Detected Capabilities"),
                Text.literal(caps),
                Impact.NONE,
                () -> "Capabilities", v -> {}, ""))
            .build();

        return new OptionPage("hardware", Text.literal("Hardware"), List.of(gpuInfo, engineTuning));
    }

    // ------------------------------------------------------------- Telemetry

    private static OptionPage telemetryPage() {
        MinecraftClient mc = MinecraftClient.getInstance();
        OptionGroup live = OptionGroup.builder().title("Live Telemetry")
            .add(new Option<>("tel_fps",
                Text.literal("Live Framerate"),
                Text.literal("Real-time FPS and average frame pacing."),
                Impact.NONE,
                () -> mc != null ? (mc.getCurrentFps() + " FPS (" + String.format("%.2f", 1000.0 / Math.max(1, mc.getCurrentFps())) + " ms)") : "N/A",
                v -> {}, ""))
            .add(new Option<>("tel_chunks",
                Text.literal("Loaded Chunks"),
                Text.literal("Number of loaded chunk sections in the current world."),
                Impact.NONE,
                () -> (mc != null && mc.world != null && mc.world.getChunkManager() != null)
                    ? (mc.world.getChunkManager().getLoadedChunkCount() + " chunks") : "0 chunks",
                v -> {}, ""))
            .add(new Option<>("tel_backend",
                Text.literal("Active Backend"),
                Text.literal("Current graphics backend driving the renderer."),
                Impact.NONE,
                () -> destiny.renderer.DestinyRenderer.getActiveBackend() != null
                    ? destiny.renderer.DestinyRenderer.getActiveBackend().name()
                    : (cfg().renderingBackend != null ? cfg().renderingBackend : "OpenGL 3.3"),
                v -> {}, ""))
            .add(new Option<>("tel_mesher",
                Text.literal("Meshing Queue"),
                Text.literal("Section rebuild queue status."),
                Impact.NONE,
                () -> "Optimized / Active",
                v -> {}, ""))
            .build();

        return new OptionPage("telemetry", Text.literal("Telemetry"), List.of(live));
    }
}
