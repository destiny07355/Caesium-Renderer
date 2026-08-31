package destiny.renderer.hud;

import destiny.renderer.config.RendererConfig;
import destiny.renderer.gui.Theme;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * On-screen performance and position readout.
 *
 * <p>Backs the Overlays settings page. Frame times are sampled into a ring buffer so the
 * displayed average and 1% low are meaningful rather than instantaneous noise.
 */
public final class PerformanceOverlay {

    /** Two seconds of history at 120 fps. */
    private static final int SAMPLE_COUNT = 240;
    /** Larger window used for percentile readouts, ~8 s at 120 fps. Short-term averages
     *  smooth out 1% lows too aggressively to be useful as a floor metric; the longer
     *  window keeps the rare stutters visible. */
    private static final int PERCENTILE_COUNT = 1024;

    private static final long[] frameTimesNs = new long[SAMPLE_COUNT];
    private static int sampleIndex = 0;
    private static int samplesFilled = 0;

    /** Longer-history ring buffer used exclusively for percentile readouts. Separate from
     *  the close-range buffer because the averaging window may shrink smaller than the
     *  percentile window without affecting each other. */
    private static final long[] percentileTimesNs = new long[PERCENTILE_COUNT];
    private static int percentileIndex = 0;
    private static int percentileFilled = 0;
    /** Scratch buffer for percentile sorting; reused per recompute to avoid per-frame
     *  allocations on the F3 path. */
    private static long[] sortedScratch = new long[PERCENTILE_COUNT];

    private static long lastFrameNs = 0L;

    /** Cached display values, recomputed a few times a second rather than every frame. */
    private static int cachedFps = 0;
    private static int cachedMin = 0;
    private static int cachedAvg = 0;
    /** Percentile readouts in FPS form. Cached the same way as avg/min. */
    private static int cachedP50 = 0;
    private static int cachedP98 = 0;
    private static int cachedP995 = 0;
    /** Slowest single frame observed in the percentile window, in FPS form. */
    private static int cachedWorst = 0;
    /** p99.5 frame time in milliseconds — the metric the adaptive backpressure paths read.
     *  Cached alongside the FPS form so the frame-time consumers never re-sort. */
    private static double cachedP995Ms = 0.0;
    private static long lastRecomputeMs = 0L;

    /** Monotonically increasing frame index, used by per-frame budgeting elsewhere. */
    private static long frameCounter = 0L;

    private PerformanceOverlay() {}

    /** @return a monotonically increasing frame index. */
    public static long frameCounter() { return frameCounter; }

    /**
     * @return recent average frame time in milliseconds, or 0 before any samples exist.
     *
     * <p>Used for adaptive backpressure: subsystems that queue optional work can check
     * this and back off while frames are already running long, rather than piling more
     * onto a frame that is struggling.
     */
    public static double averageFrameMs() {
        int n = samplesFilled;
        if (n == 0) return 0.0;
        long total = 0L;
        for (int i = 0; i < n; i++) total += frameTimesNs[i];
        return (total / (double) n) / 1_000_000.0;
    }

    /** Records a frame. Called once per frame from the HUD mixin. */
    public static void recordFrame() {
        frameCounter++;
        long now = System.nanoTime();
        if (lastFrameNs != 0L) {
            long delta = now - lastFrameNs;
            // Ignore absurd deltas from pauses, alt-tabs and loading screens; they would
            // poison the 1% low for the next two seconds.
            if (delta > 0 && delta < 1_000_000_000L) {
                frameTimesNs[sampleIndex] = delta;
                sampleIndex = (sampleIndex + 1) % SAMPLE_COUNT;
                if (samplesFilled < SAMPLE_COUNT) samplesFilled++;

                percentileTimesNs[percentileIndex] = delta;
                percentileIndex = (percentileIndex + 1) % PERCENTILE_COUNT;
                if (percentileFilled < PERCENTILE_COUNT) percentileFilled++;
            }
        }
        lastFrameNs = now;
    }

    private static void recompute() {
        long now = System.currentTimeMillis();
        if (now - lastRecomputeMs < 250L) return;
        lastRecomputeMs = now;

        if (samplesFilled == 0) return;

        long total = 0L;
        long worst = 0L;
        for (int i = 0; i < samplesFilled; i++) {
            long t = frameTimesNs[i];
            total += t;
            if (t > worst) worst = t;
        }

        double avgNs = total / (double) samplesFilled;
        cachedAvg = avgNs > 0 ? (int) Math.round(1_000_000_000.0 / avgNs) : 0;
        cachedMin = worst > 0 ? (int) Math.round(1_000_000_000.0 / worst) : 0;

        long recent = frameTimesNs[(sampleIndex - 1 + SAMPLE_COUNT) % SAMPLE_COUNT];
        cachedFps = recent > 0 ? (int) Math.round(1_000_000_000.0 / recent) : cachedAvg;

        recomputePercentiles();
    }

    /**
     * Ordinal-based percentile from the longer-history buffer. Wikipedia's "nearest-rank"
     * method: a sample's rank for a percentile {@code p} of {@code n} samples is
     * {@code ceil(p * n / 100)} (1-indexed). We then index into the sorted array.
     *
     * <p>For {@code p99.5} we want the value such that 99.5% of frames are at or better
     * (lower frame-time) — equivalently, 0.5% of frames are at or worse. With 1024
     * samples that rank is 1018, leaving 6 samples as the "tail of slow frames", which is
     * precisely what gives a stable P99.5 reading without thrashing every refresh.
     */
    private static int percentileFps(double p) {
        int n = percentileFilled;
        if (n == 0) return 0;
        int rank = (int) Math.ceil((p / 100.0) * n);
        if (rank < 1) rank = 1;
        if (rank > n) rank = n;

        // Defensive — resize if the buffer ever grows. The lookup is one array op in
        // practice; no per-frame allocation.
        if (sortedScratch.length < n) sortedScratch = new long[n];

        System.arraycopy(percentileTimesNs, 0, sortedScratch, 0, n);
        // Partial-sort would be ideal, but n is tiny (1024) and the same array is reused
        // so the steady-state allocation cost is zero — and recompute only fires 4x/s.
        java.util.Arrays.sort(sortedScratch, 0, n);
        long v = sortedScratch[rank - 1];
        return v > 0 ? (int) Math.round(1_000_000_000.0 / v) : 0;
    }

    /** Worst single frame observed in the percentile window, as an FPS number. */
    private static int percentileWorstFps() {
        int n = percentileFilled;
        if (n == 0) return 0;
        if (sortedScratch.length < n) sortedScratch = new long[n];
        // The previous {@link #percentileFps} call already sorted this array. But we
        // cannot rely on call ordering guarantees, so re-sort defensively; still only
        // 4x/second.
        System.arraycopy(percentileTimesNs, 0, sortedScratch, 0, n);
        java.util.Arrays.sort(sortedScratch, 0, n);
        long v = sortedScratch[n - 1]; // worst frame (highest nanos)
        return v > 0 ? (int) Math.round(1_000_000_000.0 / v) : 0;
    }

    private static void recomputePercentiles() {
        int n = percentileFilled;
        if (n == 0) {
            cachedP50 = cachedP98 = cachedP995 = cachedWorst = 0;
            cachedP995Ms = 0.0;
            return;
        }
        // Sort once; all percentile reads share the same sorted array.
        if (sortedScratch.length < n) sortedScratch = new long[n];
        System.arraycopy(percentileTimesNs, 0, sortedScratch, 0, n);
        java.util.Arrays.sort(sortedScratch, 0, n);

        cachedP50  = fpsFromSorted(sortedScratch, n, 50.0);
        cachedP98  = fpsFromSorted(sortedScratch, n, 98.0);
        cachedP995 = fpsFromSorted(sortedScratch, n, 99.5);
        long v995  = valueFromSorted(sortedScratch, n, 99.5);
        cachedP995Ms = v995 > 0 ? v995 / 1_000_000.0 : 0.0;
        long worst = sortedScratch[n - 1]; // highest nanos = worst frame
        cachedWorst = worst > 0 ? (int) Math.round(1_000_000_000.0 / worst) : 0;
    }

    private static int fpsFromSorted(long[] sorted, int n, double p) {
        long v = valueFromSorted(sorted, n, p);
        return v > 0 ? (int) Math.round(1_000_000_000.0 / v) : 0;
    }

    private static long valueFromSorted(long[] sorted, int n, double p) {
        int rank = (int) Math.ceil((p / 100.0) * n);
        if (rank < 1) rank = 1;
        if (rank > n) rank = n;
        return sorted[rank - 1];
    }

    /**
     * @return the frame time in milliseconds at percentile {@code p} of the percentile
     *         window, or 0 before any samples exist. Same sorted-window basis as
     *         {@link #percentileLines()}.
     */
    private static double percentileFrameMs(double p) {
        int n = percentileFilled;
        if (n == 0) return 0.0;
        int rank = (int) Math.ceil((p / 100.0) * n);
        if (rank < 1) rank = 1;
        if (rank > n) rank = n;
        if (sortedScratch.length < n) sortedScratch = new long[n];
        System.arraycopy(percentileTimesNs, 0, sortedScratch, 0, n);
        java.util.Arrays.sort(sortedScratch, 0, n);
        long v = sortedScratch[rank - 1];
        return v > 0 ? v / 1_000_000.0 : 0.0;
    }

    /**
     * @return recent p99.5 frame time in milliseconds (the "worst you actually feel"
     *         metric, not the average), or 0 before any samples exist. Used by the
     *         adaptive backpressure paths — {@code DeferredRebuildQueue} in particular —
     *         which should react to stutters, not to a rolling average that smooths
     *         them away.
     */
    public static double percentileFrameMs995() {
        return cachedP995Ms;
    }

    /**
     * @return recent p99.5 readout in FPS form, or 0 before any samples exist. Used by
     *         the adaptive render-distance controller to decide whether to trade chunks
     *         for frame-time headroom.
     */
    public static int percentileFps995() {
        return cachedP995;
    }

    // --- Readouts for the F3 mixin and the in-game overlay ---

    /**
     * One-line stack of percentile readouts intended to be appended to an F3 left list,
     * the same way Sodium surfaces its {@code ms/f µ this/} stats. Returns an empty
     * list when there are no samples yet so callers can safely no-op.
     */
    public static java.util.List<String> percentileLines() {
        java.util.List<String> out = new java.util.ArrayList<>(2);
        if (percentileFilled == 0) return out;
        out.add(String.format(java.util.Locale.ROOT,
            "Caesium p50 %d  p98 %d  p99.5 %d fps",
            cachedP50, cachedP98, cachedP995));
        out.add(String.format(java.util.Locale.ROOT,
            "Caesium worst %d  (avg %d) fps",
            cachedWorst, cachedAvg));
        return out;
    }

    /** Draws the overlay. Called from the HUD mixin after the rest of the HUD. */
    public static void render(DrawContext context, TextRenderer tr) {
        RendererConfig cfg = RendererConfig.get();
        if (cfg.fpsCounterPosition == 0 && !cfg.showCoordinates
            && !cfg.showMemoryUsage && !cfg.showPerfOverlay) {
            return;
        }

        recompute();

        MinecraftClient mc = MinecraftClient.getInstance();
        List<Text> lines = new ArrayList<>(6);

        if (cfg.fpsCounterPosition != 0) {
            if (cfg.fpsExtended) {
                lines.add(Text.literal(cachedFps + " fps")
                    .styled(s -> s.withColor(fpsColor(cachedFps))));
                lines.add(Text.literal("avg " + cachedAvg + "  low " + cachedMin)
                    .styled(s -> s.withColor(Theme.TEXT_MUTED)));
                if (percentileFilled > 0) {
                    lines.add(Text.literal("p50 " + cachedP50 + "  p98 " + cachedP98
                            + "  p99.5 " + cachedP995)
                        .styled(s -> s.withColor(Theme.TEXT_MUTED)));
                }
            } else {
                lines.add(Text.literal(cachedFps + " fps")
                    .styled(s -> s.withColor(fpsColor(cachedFps))));
            }
        }

        if (cfg.showCoordinates && mc != null && mc.player != null) {
            BlockPos pos = mc.player.getBlockPos();
            lines.add(Text.literal(pos.getX() + " " + pos.getY() + " " + pos.getZ())
                .styled(s -> s.withColor(Theme.TEXT_NORMAL)));
        }

        if (cfg.showMemoryUsage) {
            Runtime rt = Runtime.getRuntime();
            long usedMb = (rt.totalMemory() - rt.freeMemory()) / 1048576L;
            long maxMb = rt.maxMemory() / 1048576L;
            int pct = maxMb > 0 ? (int) (usedMb * 100 / maxMb) : 0;
            lines.add(Text.literal("mem " + usedMb + "/" + maxMb + " MB (" + pct + "%)")
                .styled(s -> s.withColor(pct > 85 ? Theme.DANGER : Theme.TEXT_MUTED)));
        }

        if (cfg.showPerfOverlay) {
            var backend = destiny.renderer.DestinyRenderer.getActiveBackend();
            if (backend != null) {
                lines.add(Text.literal("sections " + backend.getSectionCount())
                    .styled(s -> s.withColor(Theme.TEXT_MUTED)));
            }
            var mesher = destiny.renderer.chunk.MeshingJobSystem.get();
            if (mesher != null) {
                lines.add(Text.literal("queued " + mesher.pendingTaskCount())
                    .styled(s -> s.withColor(Theme.TEXT_MUTED)));
            }
        }

        if (lines.isEmpty()) return;

        // --- Position ---
        int pad = 4;
        int lineH = 10;
        int boxW = 0;
        for (Text line : lines) boxW = Math.max(boxW, tr.getWidth(line));
        boxW += pad * 2;
        int boxH = lines.size() * lineH + pad * 2 - 2;

        int screenW = context.getScaledWindowWidth();
        int screenH = context.getScaledWindowHeight();
        int margin = 4;

        int x, y;
        switch (cfg.fpsCounterPosition) {
            case 2  -> { x = screenW - boxW - margin; y = margin; }
            case 3  -> { x = margin;                  y = screenH - boxH - margin; }
            case 4  -> { x = screenW - boxW - margin; y = screenH - boxH - margin; }
            default -> { x = margin;                  y = margin; }
        }

        if (cfg.overlayBackground) {
            int totalH = boxH;
            if ((cfg.fpsExtended || cfg.showPerfOverlay) && samplesFilled > 10) {
                totalH += 26;
            }
            context.fill(x, y, x + boxW, y + totalH, 0xA0000000);
        }

        int ty = y + pad;
        for (Text line : lines) {
            context.drawTextWithShadow(tr, line, x + pad, ty, 0xFFFFFFFF);
            ty += lineH;
        }

        // Multi-Mode Telemetry Graph
        if ((cfg.fpsExtended || cfg.showPerfOverlay) && samplesFilled > 10) {
            int graphW = Math.max(boxW - pad * 2, 100);
            int graphH = 22;
            int gx = x + pad;
            int gy = ty + 2;

            GraphTelemetryController.renderGraph(context, tr, gx, gy, graphW, graphH,
                frameTimesNs, sampleIndex, samplesFilled, SAMPLE_COUNT);
        }
    }

    /** Green above 60, amber above 30, red below. */
    private static int fpsColor(int fps) {
        if (fps >= 60) return 0x5CCB5F;
        if (fps >= 30) return 0xFFB300;
        return 0xFF5252;
    }
}
