package destiny.renderer.hud;

import destiny.renderer.config.RendererConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/**
 * Caesium Hot Path Profiler.
 * Provides fine-grained microsecond breakdown of the CPU/GPU frame stages, allocation counters,
 * and automatic stress-mode tracking (NORMAL, CROWDED, FIRE, PARTICLES, EXPLOSIONS, TELEPORT, ANIMATION).
 *
 * <p>Decoupled from render mixins: rendering hooks call top-level static helpers that early-out
 * with zero overhead when the overlay is disabled.
 */
public final class CaesiumFrameProfiler {

    public enum GpuPass {
        TERRAIN,
        OPAQUE,
        CUTOUT,
        TRANSLUCENT,
        ENTITIES,
        PARTICLES,
        POST,
        PRESENT
    }

    public enum StressMode {
        NORMAL("Normal", 0x55FF55),
        CROWDED("Crowded Lobby", 0xFFAA00),
        FIRE("Fire / Flame", 0xFF5555),
        PARTICLES("Particle Burst", 0xFFAA55),
        EXPLOSIONS("Explosion Shockwave", 0xFF3333),
        TELEPORT("Teleport / RTP", 0x55FFFF),
        ANIMATION("Heavy Animation", 0xFF55FF);

        public final String label;
        public final int color;
        StressMode(String label, int color) {
            this.label = label;
            this.color = color;
        }
    }

    public enum ProfilerMode {
        LIGHT, // CPU timing only (negligible overhead)
        FULL   // CPU + GPU + allocations + stress analytics
    }

    private static volatile boolean enabled = false;
    private static volatile ProfilerMode mode = ProfilerMode.LIGHT;

    // Moving average buffers for display stability
    private static final double[] cpuTimeMs = new double[ProfilerSubsystem.values().length];
    private static final double[] gpuTimeMs = new double[GpuPass.values().length];
    private static double cpuGpuWaitMs = 0.0;
    private static double fenceWaitMs = 0.0;

    // Allocations in hot path
    private static long frameAllocations = 0;
    private static long entityAllocations = 0;
    private static long chunkAllocations = 0;

    private static long curFrameAllocs = 0;
    private static long curEntityAllocs = 0;
    private static long curChunkAllocs = 0;
    private static volatile int terrainCandidates;
    private static volatile int terrainVisible;
    private static volatile int terrainDrawCalls;
    private static volatile long terrainIndices;
    private static volatile long terrainUploadBytes;
    private static volatile double terrainUploadMs;

    private static StressMode currentStressMode = StressMode.NORMAL;

    // Rejection, deferral & safety counters
    private static final java.util.concurrent.atomic.AtomicLong meshingRejected = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong meshingDeferred = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong meshingEvicted = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong extractRejected = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong extractDeferred = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong callerExecutedHeavyJobs = new java.util.concurrent.atomic.AtomicLong();

    // Terrain cache cleanup
    private static volatile int terrainMeshesFreedThisFrame = 0;
    private static volatile double terrainCleanupMs = 0.0;

    // 4-stage pipeline latency (T0..T4)
    private static final java.util.concurrent.atomic.AtomicLong totalRequestToExtractNs = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong totalExtractDurationNs = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong totalExtractToUploadNs = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong totalUploadDurationNs = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicInteger latencySampleCount = new java.util.concurrent.atomic.AtomicInteger();

    // Transient high-resolution timestamps
    private static final long[] timerStartNs = new long[ProfilerSubsystem.values().length];

    private CaesiumFrameProfiler() {}

    // -------------------------------------------------------------------------
    // Decoupled Subsystem Timing Hooks (Zero overhead when disabled)
    // -------------------------------------------------------------------------

    public static void beginWorldUpdate() { start(ProfilerSubsystem.WORLD_UPDATE); }
    public static void endWorldUpdate()   { end(ProfilerSubsystem.WORLD_UPDATE); }

    public static void beginVisibility()  { start(ProfilerSubsystem.VISIBILITY); }
    public static void endVisibility()    { end(ProfilerSubsystem.VISIBILITY); }

    public static void beginEntities()    { start(ProfilerSubsystem.ENTITIES); }
    public static void endEntities()      { end(ProfilerSubsystem.ENTITIES); }

    public static void beginParticles()   { start(ProfilerSubsystem.PARTICLES); }
    public static void endParticles()     { end(ProfilerSubsystem.PARTICLES); }

    public static void beginAnimations()  { start(ProfilerSubsystem.ANIMATIONS); }
    public static void endAnimations()    { end(ProfilerSubsystem.ANIMATIONS); }

    public static void beginChunkScheduling() { start(ProfilerSubsystem.CHUNK_SCHEDULING); }
    public static void endChunkScheduling()   { end(ProfilerSubsystem.CHUNK_SCHEDULING); }

    public static void beginRenderGraph() { start(ProfilerSubsystem.RENDER_GRAPH); }
    public static void endRenderGraph()   { end(ProfilerSubsystem.RENDER_GRAPH); }

    public static void beginBackend()     { start(ProfilerSubsystem.BACKEND); }
    public static void endBackend()       { end(ProfilerSubsystem.BACKEND); }

    public static void start(ProfilerSubsystem s) {
        if (!enabled) return;
        timerStartNs[s.ordinal()] = System.nanoTime();
    }

    public static void end(ProfilerSubsystem s) {
        if (!enabled) return;
        long start = timerStartNs[s.ordinal()];
        if (start != 0L) {
            long delta = System.nanoTime() - start;
            double ms = delta / 1_000_000.0;
            // Exponential moving average for smooth display
            cpuTimeMs[s.ordinal()] = cpuTimeMs[s.ordinal()] * 0.85 + ms * 0.15;
            timerStartNs[s.ordinal()] = 0L;
        }
    }

    public static void setMode(ProfilerMode newMode) {
        mode = newMode;
    }

    public static ProfilerMode getMode() {
        return mode;
    }

    public static void recordGpu(GpuPass pass, double ms) {
        if (!enabled || mode != ProfilerMode.FULL) return;
        gpuTimeMs[pass.ordinal()] = gpuTimeMs[pass.ordinal()] * 0.85 + ms * 0.15;
    }

    public static void recordCpuGpuWait(double ms) {
        if (!enabled || mode != ProfilerMode.FULL) return;
        cpuGpuWaitMs = cpuGpuWaitMs * 0.85 + ms * 0.15;
    }

    public static void recordFenceWait(double ms) {
        if (!enabled || mode != ProfilerMode.FULL) return;
        fenceWaitMs = fenceWaitMs * 0.85 + ms * 0.15;
    }

    public static void recordTerrainSubmission(int candidates, int visible, int draws, long indices) {
        if (!enabled || mode != ProfilerMode.FULL) return;
        terrainCandidates = candidates;
        terrainVisible = visible;
        terrainDrawCalls = draws;
        terrainIndices = indices;
    }

    public static void recordTerrainUpload(long bytes, long nanos) {
        if (!enabled || mode != ProfilerMode.FULL) return;
        terrainUploadBytes += Math.max(0L, bytes);
        terrainUploadMs += Math.max(0L, nanos) / 1_000_000.0;
    }

    public static double getGpuMs(GpuPass pass) {
        return gpuTimeMs[pass.ordinal()];
    }

    public static double getCpuGpuWaitMs() {
        return cpuGpuWaitMs;
    }

    public static double getFenceWaitMs() {
        return fenceWaitMs;
    }

    public static void countFrameAlloc() {
        if (!enabled || mode != ProfilerMode.FULL) return;
        curFrameAllocs++;
    }

    public static void countEntityAlloc() {
        if (!enabled || mode != ProfilerMode.FULL) return;
        curEntityAllocs++;
    }

    public static void countChunkAlloc() {
        if (!enabled || mode != ProfilerMode.FULL) return;
        curChunkAllocs++;
    }

    public static void recordMeshingRejected() { meshingRejected.incrementAndGet(); }
    public static void recordMeshingDeferred() { meshingDeferred.incrementAndGet(); }
    public static void recordMeshingEvicted()  { meshingEvicted.incrementAndGet(); }
    public static void recordExtractRejected() { extractRejected.incrementAndGet(); }
    public static void recordExtractDeferred() { extractDeferred.incrementAndGet(); }
    public static void recordCallerExecutedHeavyJob() { callerExecutedHeavyJobs.incrementAndGet(); }

    public static long getMeshingRejected() { return meshingRejected.get(); }
    public static long getMeshingDeferred() { return meshingDeferred.get(); }
    public static long getMeshingEvicted()  { return meshingEvicted.get(); }
    public static long getExtractRejected() { return extractRejected.get(); }
    public static long getExtractDeferred() { return extractDeferred.get(); }
    public static long getCallerExecutedHeavyJobs() { return callerExecutedHeavyJobs.get(); }

    public static void recordTerrainCleanup(int freedMeshes, double ms) {
        terrainMeshesFreedThisFrame = freedMeshes;
        terrainCleanupMs = ms;
    }

    public static int getTerrainMeshesFreedThisFrame() { return terrainMeshesFreedThisFrame; }
    public static double getTerrainCleanupMs() { return terrainCleanupMs; }

    public static void recordPipelineLatencies(long reqToExtractNs, long extractNs, long extractToUploadNs, long uploadNs) {
        totalRequestToExtractNs.addAndGet(Math.max(0L, reqToExtractNs));
        totalExtractDurationNs.addAndGet(Math.max(0L, extractNs));
        totalExtractToUploadNs.addAndGet(Math.max(0L, extractToUploadNs));
        totalUploadDurationNs.addAndGet(Math.max(0L, uploadNs));
        latencySampleCount.incrementAndGet();
    }

    public static double getAvgRequestToExtractMs() {
        int c = latencySampleCount.get();
        return c > 0 ? (totalRequestToExtractNs.get() / (double) c) / 1_000_000.0 : 0.0;
    }
    public static double getAvgExtractDurationMs() {
        int c = latencySampleCount.get();
        return c > 0 ? (totalExtractDurationNs.get() / (double) c) / 1_000_000.0 : 0.0;
    }
    public static double getAvgExtractToUploadMs() {
        int c = latencySampleCount.get();
        return c > 0 ? (totalExtractToUploadNs.get() / (double) c) / 1_000_000.0 : 0.0;
    }
    public static double getAvgUploadDurationMs() {
        int c = latencySampleCount.get();
        return c > 0 ? (totalUploadDurationNs.get() / (double) c) / 1_000_000.0 : 0.0;
    }

    public static void beginFrame() {
        RendererConfig cfg = RendererConfig.get();
        enabled = cfg.showCaesiumProfiler;
        if (!enabled) return;

        mode = cfg.caesiumProfilerFullMode ? ProfilerMode.FULL : ProfilerMode.LIGHT;

        if (mode == ProfilerMode.FULL) {
            frameAllocations = (long) (frameAllocations * 0.8 + curFrameAllocs * 0.2);
            entityAllocations = (long) (entityAllocations * 0.8 + curEntityAllocs * 0.2);
            chunkAllocations = (long) (chunkAllocations * 0.8 + curChunkAllocs * 0.2);
            curFrameAllocs = 0;
            curEntityAllocs = 0;
            curChunkAllocs = 0;
            terrainCandidates = 0;
            terrainVisible = 0;
            terrainDrawCalls = 0;
            terrainIndices = 0L;
            terrainUploadBytes = 0L;
            terrainUploadMs = 0.0;
        }
    }

    public static void setStressMode(StressMode mode) {
        currentStressMode = mode;
    }

    public static StressMode getStressMode() {
        return currentStressMode;
    }

    public static double getCpuMs(ProfilerSubsystem s) {
        return cpuTimeMs[s.ordinal()];
    }

    public static double getTotalCpuMs() {
        double total = 0;
        for (double ms : cpuTimeMs) total += ms;
        return total;
    }

    public static double getTotalGpuMs() {
        double total = 0;
        for (double ms : gpuTimeMs) total += ms;
        return total;
    }

    /** Auto-detects stress mode from current world and client state. */
    public static void autoDetectStressMode() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.world == null) {
            currentStressMode = StressMode.NORMAL;
            return;
        }

        long now = System.currentTimeMillis();
        var eng = caesium.integration.CaesiumIntegration.getEngine();
        if (eng != null && eng.scheduler() != null && eng.scheduler().responder() != null
                && eng.scheduler().responder().isHot(now)) {
            currentStressMode = StressMode.EXPLOSIONS;
            return;
        }

        if (destiny.renderer.chunk.DeferredRebuildQueue.size() > 64) {
            currentStressMode = StressMode.TELEPORT;
            return;
        }

        if (mc.player != null && mc.player.isOnFire()) {
            currentStressMode = StressMode.FIRE;
            return;
        }

        if (destiny.renderer.particle.CaesiumParticleMetrics.getLiveEstimate() > 180) {
            currentStressMode = StressMode.PARTICLES;
            return;
        }

        int entityCount = mc.world.getRegularEntityCount();
        if (entityCount > 80) {
            currentStressMode = StressMode.CROWDED;
            return;
        }

        currentStressMode = StressMode.NORMAL;
    }

    /**
     * Draws the in-game Caesium Frame Profiler overlay.
     */
    public static void render(DrawContext context, TextRenderer tr, int x, int y) {
        if (!RendererConfig.get().showCaesiumProfiler) return;

        if (mode == ProfilerMode.FULL) {
            autoDetectStressMode();
        } else {
            currentStressMode = StressMode.NORMAL;
        }

        int lineHeight = tr.fontHeight + 2;
        int curY = y;

        int width = 200;
        int height = (mode == ProfilerMode.FULL) ? 368 : 172;
        context.fill(x - 4, y - 4, x + width + 4, y + height, 0xD010141C);
        drawBoxBorder(context, x - 4, y - 4, width + 8, height + 4, 0xFF384459);

        // Header
        String modeBadge = (mode == ProfilerMode.FULL) ? "§c[FULL]" : "§a[LIGHT]";
        context.drawText(tr, Text.literal("§6§lCAESIUM PROFILER " + modeBadge), x, curY, 0xFFFFFF, true);
        curY += lineHeight;

        if (mode == ProfilerMode.FULL) {
            context.drawText(tr, Text.literal("Stress: §l" + currentStressMode.label), x, curY, currentStressMode.color, true);
        } else {
            context.drawText(tr, Text.literal("Mode: §aMicrosecond Timing"), x, curY, 0x88FF88, true);
        }
        curY += lineHeight + 2;

        // CPU FRAME
        double totalCpu = getTotalCpuMs();
        context.drawText(tr, Text.literal(String.format("§cCPU FRAME §f%.2f ms", totalCpu)), x, curY, 0xFFFFFF, true);
        curY += lineHeight;

        drawBar(context, tr, x + 4, curY, "World update", getCpuMs(ProfilerSubsystem.WORLD_UPDATE), 0xAAAAAA);
        curY += lineHeight;
        drawBar(context, tr, x + 4, curY, "Visibility", getCpuMs(ProfilerSubsystem.VISIBILITY), 0xAAAAAA);
        curY += lineHeight;
        drawBar(context, tr, x + 4, curY, "Entities", getCpuMs(ProfilerSubsystem.ENTITIES), 0xAAAAAA);
        curY += lineHeight;
        drawBar(context, tr, x + 4, curY, "Particles", getCpuMs(ProfilerSubsystem.PARTICLES), 0xAAAAAA);
        curY += lineHeight;
        drawBar(context, tr, x + 4, curY, "Animations", getCpuMs(ProfilerSubsystem.ANIMATIONS), 0xAAAAAA);
        curY += lineHeight;
        drawBar(context, tr, x + 4, curY, "Chunk sched", getCpuMs(ProfilerSubsystem.CHUNK_SCHEDULING), 0xAAAAAA);
        curY += lineHeight;
        drawBar(context, tr, x + 4, curY, "RenderGraph", getCpuMs(ProfilerSubsystem.RENDER_GRAPH), 0xAAAAAA);
        curY += lineHeight;
        drawBar(context, tr, x + 4, curY, "Backend", getCpuMs(ProfilerSubsystem.BACKEND), 0xAAAAAA);
        curY += lineHeight + 2;

        if (mode == ProfilerMode.FULL) {
            // GPU FRAME
            double totalGpu = getTotalGpuMs();
            context.drawText(tr, Text.literal(String.format("§eGPU FRAME §f%.2f ms", totalGpu)), x, curY, 0xFFFFFF, true);
            curY += lineHeight;

            for (GpuPass pass : GpuPass.values()) {
                drawBar(context, tr, x + 4, curY, pass.name().toLowerCase(), getGpuMs(pass), 0xE0E080);
                curY += lineHeight;
            }
            curY += 2;

            // CPU-GPU STALLS / WAITS
            context.drawText(tr, Text.literal(String.format("§bSYNC WAITS §7(fence: §f%.2f§7ms | wait: §f%.2f§7ms)", fenceWaitMs, cpuGpuWaitMs)), x, curY, 0x88DDFF, true);
            curY += lineHeight + 2;

            context.drawText(tr, Text.literal(String.format("§dTERRAIN §f%d/%d visible | %d draws",
                    terrainVisible, terrainCandidates, terrainDrawCalls)), x, curY, 0xFFFFFF, true);
            curY += lineHeight;
            context.drawText(tr, Text.literal(String.format("§7  %,d indices | %.2f MB upload (%.2f ms)",
                    terrainIndices, terrainUploadBytes / 1_048_576.0, terrainUploadMs)), x, curY, 0xBBBBBB, false);
            context.drawText(tr, Text.literal(String.format("§3BACKPRESSURE §7(mesh rej:§f%d §7def:§f%d §7evict:§f%d§7)",
                    meshingRejected.get(), meshingDeferred.get(), meshingEvicted.get())), x, curY, 0x88EEFF, true);
            curY += lineHeight;
            context.drawText(tr, Text.literal(String.format("§7  ext rej:§f%d §7def:§f%d §7| callerHeavy:§%s%d",
                    extractRejected.get(), extractDeferred.get(),
                    callerExecutedHeavyJobs.get() > 0 ? "c" : "a", callerExecutedHeavyJobs.get())), x, curY, 0x88EEFF, true);
            curY += lineHeight;
            context.drawText(tr, Text.literal(String.format("§9LATENCY §7(req:§f%.1f§7ms ext:§f%.1f§7ms upWait:§f%.1f§7ms up:§f%.1f§7ms)",
                    getAvgRequestToExtractMs(), getAvgExtractDurationMs(), getAvgExtractToUploadMs(), getAvgUploadDurationMs())), x, curY, 0xAAAAFF, false);
            curY += lineHeight + 2;
        }

        // ALLOCATIONS
        context.drawText(tr, Text.literal("§aALLOCATIONS (per frame)"), x, curY, 0xFFFFFF, true);
        curY += lineHeight;
        if (mode == ProfilerMode.FULL) {
            context.drawText(tr, Text.literal(String.format("§7  Frame: §f%d §7| Entity: §f%d §7| Chunk: §f%d",
                    frameAllocations, entityAllocations, chunkAllocations)), x, curY, 0x88FF88, true);
        } else {
            context.drawText(tr, Text.literal("§8  (Light mode - toggle Full mode)"), x, curY, 0x888888, true);
        }
    }

    private static void drawBoxBorder(DrawContext context, int x, int y, int w, int h, int color) {
        context.fill(x, y, x + w, y + 1, color);
        context.fill(x, y + h - 1, x + w, y + h, color);
        context.fill(x, y + 1, x + 1, y + h - 1, color);
        context.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    private static void drawBar(DrawContext context, TextRenderer tr, int x, int y, String label, double ms, int color) {
        String str = String.format("  %-12s %5.2f ms", label, ms);
        context.drawText(tr, Text.literal(str), x, y, color, false);
    }
}
