package destiny.renderer.gui.presets;

import destiny.renderer.config.RendererConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.CloudRenderMode;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.GraphicsMode;
import net.minecraft.particle.ParticlesMode;

/**
 * 5-Tier performance presets for instant one-click optimization.
 */
public enum PerformancePreset {

    LOW_END_IGPU(
        "Low-End / iGPU",
        "Maximum FPS for Intel HD/UHD, AMD Vega, and entry-level laptops. Cuts heavy effects while keeping smooth gameplay.",
        0xFF52C41A
    ),

    BALANCED(
        "Balanced (Recommended)",
        "Optimal balance between visual fidelity and smooth 60–120 FPS. Recommended for most PCs.",
        0xFF4FA8E8
    ),

    COMPETITIVE_240HZ(
        "Competitive (240Hz+)",
        "Ultra-low latency, instant block update response, and rock-solid 1% low frame pacing for high-refresh monitors.",
        0xFFFFB300
    ),

    CINEMATIC_ULTRA(
        "Cinematic Ultra",
        "Maximum render distance, full entity draw distance, and rich particles for powerful discrete GPUs (RTX / Radeon).",
        0xFFFF5252
    ),

    CUSTOM(
        "Custom",
        "Individually tailored settings.",
        0xFF9AA3B0
    );

    public final String label;
    public final String description;
    public final int color;

    PerformancePreset(String label, String description, int color) {
        this.label = label;
        this.description = description;
        this.color = color;
    }

    /**
     * Applies this preset to both RendererConfig and vanilla GameOptions.
     */
    public static void apply(PerformancePreset preset) {
        if (preset == CUSTOM) return;

        RendererConfig cfg = RendererConfig.get();
        MinecraftClient client = MinecraftClient.getInstance();
        GameOptions opts = client != null ? client.options : null;

        switch (preset) {
            case LOW_END_IGPU -> {
                cfg.greedyMeshing = true;
                cfg.deferChunkUpdates = true;
                cfg.maxChunkUpdatesPerFrame = 6;
                cfg.chunkWorkerPriority = 0; // Low
                cfg.smartChunkLoading = true;
                cfg.adaptiveViewDistance = true;
                cfg.cpuRenderAhead = 2;

                if (opts != null) {
                    opts.getViewDistance().setValue(8);
                    opts.getSimulationDistance().setValue(6);
                    opts.getEntityDistanceScaling().setValue(0.75);
                    opts.getParticles().setValue(ParticlesMode.DECREASED);
                    opts.getCloudRenderMode().setValue(CloudRenderMode.OFF);
                    opts.getPreset().setValue(GraphicsMode.FAST);
                    opts.getCutoutLeaves().setValue(false);
                    opts.getAo().setValue(false);
                }
            }

            case BALANCED -> {
                cfg.greedyMeshing = true;
                cfg.deferChunkUpdates = true;
                cfg.maxChunkUpdatesPerFrame = 12;
                cfg.chunkWorkerPriority = 0;
                cfg.smartChunkLoading = true;
                cfg.adaptiveViewDistance = false;
                cfg.cpuRenderAhead = 2;

                if (opts != null) {
                    opts.getViewDistance().setValue(12);
                    opts.getSimulationDistance().setValue(10);
                    opts.getEntityDistanceScaling().setValue(1.0);
                    opts.getParticles().setValue(ParticlesMode.ALL);
                    opts.getCloudRenderMode().setValue(CloudRenderMode.FAST);
                    opts.getPreset().setValue(GraphicsMode.FANCY);
                    opts.getCutoutLeaves().setValue(true);
                    opts.getAo().setValue(true);
                }
            }

            case COMPETITIVE_240HZ -> {
                cfg.greedyMeshing = true;
                cfg.deferChunkUpdates = true;
                cfg.maxChunkUpdatesPerFrame = 16;
                cfg.chunkWorkerPriority = 1; // Normal
                cfg.smartChunkLoading = true;
                cfg.adaptiveViewDistance = false;
                cfg.cpuRenderAhead = 1; // Minimum input lag

                if (opts != null) {
                    opts.getViewDistance().setValue(10);
                    opts.getSimulationDistance().setValue(8);
                    opts.getEntityDistanceScaling().setValue(1.0);
                    opts.getParticles().setValue(ParticlesMode.DECREASED);
                    opts.getCloudRenderMode().setValue(CloudRenderMode.OFF);
                    opts.getPreset().setValue(GraphicsMode.FAST);
                    opts.getCutoutLeaves().setValue(false);
                    opts.getAo().setValue(true);
                }
            }

            case CINEMATIC_ULTRA -> {
                cfg.greedyMeshing = true;
                cfg.deferChunkUpdates = true;
                cfg.maxChunkUpdatesPerFrame = 24;
                cfg.chunkWorkerPriority = 1;
                cfg.smartChunkLoading = true;
                cfg.adaptiveViewDistance = false;
                cfg.cpuRenderAhead = 2;

                if (opts != null) {
                    opts.getViewDistance().setValue(24);
                    opts.getSimulationDistance().setValue(16);
                    opts.getEntityDistanceScaling().setValue(1.5);
                    opts.getParticles().setValue(ParticlesMode.ALL);
                    opts.getCloudRenderMode().setValue(CloudRenderMode.FANCY);
                    opts.getPreset().setValue(GraphicsMode.FABULOUS);
                    opts.getCutoutLeaves().setValue(true);
                    opts.getAo().setValue(true);
                }
            }

            default -> {}
        }

        if (client != null) {
            try {
                if (opts != null) opts.write();
                RendererConfig.save(client.runDirectory.toPath().resolve("config"));
            } catch (Throwable ignored) {}
        }
    }
}
