package destiny.renderer.bench;

import destiny.renderer.DestinyRenderer;
import destiny.renderer.config.RendererConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

/**
 * Self-Tuning Performance Profiler for DestinyRenderer.
 * Benchmarks three pipeline options live (GPU Compute Culling, CPU SIMD Culling,
 * and standard MDI) and applies the fastest one automatically.
 */
public final class SelfTuningProfiler {

    private static boolean tuning = false;
    private static int currentPhase = 0; // 0 = GPU, 1 = CPU SIMD, 2 = Fallback MDI
    private static int frameCounter = 0;
    private static final int FRAMES_PER_PHASE = 120;

    private static final double[] avgFrameTimeMs = new double[3];
    private static final double[] frameVarianceMs = new double[3];

    private static long phaseStartNs = 0L;
    private static final long[] phaseFrameTimes = new long[FRAMES_PER_PHASE];

    public static void startTuning(MinecraftClient client) {
        if (tuning) return;

        tuning = true;
        currentPhase = 0;
        frameCounter = 0;

        RendererConfig config = RendererConfig.get();

        // Phase 0: GPU Compute Culling
        config.enableComputeCull = true;
        config.enableHiZ = true;
        config.enableSIMDCulling = false;

        if (client.player != null) {
            client.player.sendMessage(Text.literal("§b[Caesium] Starting Performance Auto-Tuner... Please hold still."), false);
        }
    }

    public static boolean isTuning() {
        return tuning;
    }

    public static String getTuningProgressText() {
        if (!tuning) return "";
        return switch (currentPhase) {
            case 0 -> String.format("Benchmarking GPU Compute Culling... (%d%%)", (frameCounter * 100) / FRAMES_PER_PHASE);
            case 1 -> String.format("Benchmarking CPU SIMD Culling... (%d%%)", (frameCounter * 100) / FRAMES_PER_PHASE);
            case 2 -> String.format("Benchmarking Fallback MDI... (%d%%)", (frameCounter * 100) / FRAMES_PER_PHASE);
            default -> "Tuning...";
        };
    }

    public static void onFrameStart() {
        if (!tuning) return;
        phaseStartNs = System.nanoTime();
    }

    public static void onFrameEnd(MinecraftClient client) {
        if (!tuning) return;

        long elapsedNs = System.nanoTime() - phaseStartNs;
        phaseFrameTimes[frameCounter] = elapsedNs;
        frameCounter++;

        if (frameCounter >= FRAMES_PER_PHASE) {
            double avgMs = calculateAverageMs();
            double varianceMs = calculateVarianceMs(avgMs);

            avgFrameTimeMs[currentPhase] = avgMs;
            frameVarianceMs[currentPhase] = varianceMs;

            currentPhase++;
            frameCounter = 0;

            RendererConfig config = RendererConfig.get();
            if (currentPhase == 1) {
                // Set to Phase 1: CPU SIMD Culling
                config.enableComputeCull = false;
                config.enableHiZ = false;
                config.enableSIMDCulling = true;
            } else if (currentPhase == 2) {
                // Set to Phase 2: Fallback MDI Only
                config.enableComputeCull = false;
                config.enableHiZ = false;
                config.enableSIMDCulling = false;
            } else {
                // Finished tuning
                tuning = false;
                selectBestConfiguration(client);
            }
        }
    }

    private static double calculateAverageMs() {
        long sum = 0;
        for (long t : phaseFrameTimes) sum += t;
        return (double) sum / FRAMES_PER_PHASE / 1_000_000.0;
    }

    private static double calculateVarianceMs(double avg) {
        double sqSum = 0;
        for (long t : phaseFrameTimes) {
            double diff = (t / 1_000_000.0) - avg;
            sqSum += diff * diff;
        }
        return Math.sqrt(sqSum / FRAMES_PER_PHASE);
    }

    private static void selectBestConfiguration(MinecraftClient client) {
        RendererConfig config = RendererConfig.get();

        int bestIndex = 0;
        double lowestTime = avgFrameTimeMs[0];
        for (int i = 1; i < 3; i++) {
            if (avgFrameTimeMs[i] < lowestTime) {
                lowestTime = avgFrameTimeMs[i];
                bestIndex = i;
            }
        }

        if (bestIndex == 0) {
            config.enableComputeCull = true;
            config.enableHiZ = true;
            config.enableSIMDCulling = false;
        } else if (bestIndex == 1) {
            config.enableComputeCull = false;
            config.enableHiZ = false;
            config.enableSIMDCulling = true;
        } else {
            config.enableComputeCull = false;
            config.enableHiZ = false;
            config.enableSIMDCulling = false;
        }

        RendererConfig.save(client.runDirectory.toPath().resolve("config"));

        double gpuFps = 1000.0 / avgFrameTimeMs[0];
        double cpuFps = 1000.0 / avgFrameTimeMs[1];
        double fallbackFps = 1000.0 / avgFrameTimeMs[2];

        String winnerName = switch (bestIndex) {
            case 0 -> "GPU Compute Culling";
            case 1 -> "CPU SIMD Culling";
            default -> "Standard Fallback MDI";
        };

        if (client.player != null) {
            client.player.sendMessage(Text.literal("§b[Caesium] Auto-Tuning Complete!"), false);
            client.player.sendMessage(Text.literal(String.format("§7- GPU Compute: §f%.1f FPS (Avg: %.2fms, Var: %.2fms)", gpuFps, avgFrameTimeMs[0], frameVarianceMs[0])), false);
            client.player.sendMessage(Text.literal(String.format("§7- CPU SIMD:    §f%.1f FPS (Avg: %.2fms, Var: %.2fms)", cpuFps, avgFrameTimeMs[1], frameVarianceMs[1])), false);
            client.player.sendMessage(Text.literal(String.format("§7- Fallback MDI:§f%.1f FPS (Avg: %.2fms, Var: %.2fms)", fallbackFps, avgFrameTimeMs[2], frameVarianceMs[2])), false);
            client.player.sendMessage(Text.literal("§b[Caesium] Optimized winner: §a" + winnerName + " §b(Auto-saved settings)."), false);
        }
    }
}
