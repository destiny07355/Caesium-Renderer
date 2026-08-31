package destiny.renderer.bench;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * In-game performance benchmarking and telemetry framework.
 *
 * <h2>Metrics Collected</h2>
 * <ul>
 *   <li><b>Average FPS</b> — rolling average over the last 300 frames</li>
 *   <li><b>1% Low FPS</b> — worst 1% of frame times in the sample window</li>
 *   <li><b>0.1% Low FPS</b> — worst 0.1% of frame times</li>
 *   <li><b>Chunk Rebuild Time</b> — average milliseconds per meshing task</li>
 *   <li><b>Heap Allocation Rate</b> — heap bytes allocated per frame via MemoryMXBean</li>
 *   <li><b>VRAM Footprint Estimate</b> — sum of persistent VBO sizes tracked by the backend</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * Call {@link #recordFrameStart()} at the beginning of each render frame and
 * {@link #recordFrameEnd()} after buffer swap. The overlay reads the computed statistics
 * via the public getter methods.
 */
public final class BenchmarkFramework {

    private static final Logger LOGGER = Logger.getLogger("Caesium/Bench");

    private static final int WINDOW = 300; // frames in rolling window

    // -------------------------------------------------------------------------
    // Frame time ring buffer
    // -------------------------------------------------------------------------

    private final long[] frameTimes = new long[WINDOW]; // nanoseconds
    private int frameHead = 0;
    private int frameCount = 0;
    private long frameStartNs = 0L;
    public long firstFrameNs = -1L;

    // -------------------------------------------------------------------------
    // Chunk rebuild tracking
    // -------------------------------------------------------------------------

    private final AtomicLong totalRebuildNs    = new AtomicLong(0);
    private final AtomicLong totalRebuildCount = new AtomicLong(0);

    // -------------------------------------------------------------------------
    // Heap tracking
    // -------------------------------------------------------------------------

    private final MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
    private long lastHeapUsed = 0L;
    private long heapAllocatedPerFrame = 0L;

    // -------------------------------------------------------------------------
    // Computed statistics (refreshed every WINDOW frames)
    // -------------------------------------------------------------------------

    private double avgFPS        = 0;
    private double low1FPS       = 0;
    private double low01FPS      = 0;
    private double avgRebuildMs  = 0;
    private long   heapDeltaBytes = 0;
    private long   estimatedVramBytes = 0;

    // -------------------------------------------------------------------------
    // Per-frame API
    // -------------------------------------------------------------------------

    /** Records the start of a new frame. Call at the very beginning of the render loop. */
    public void recordFrameStart() {
        frameStartNs = System.nanoTime();
        long heapNow = memBean.getHeapMemoryUsage().getUsed();
        heapDeltaBytes = heapNow - lastHeapUsed;
        lastHeapUsed = heapNow;
    }

    /** Records the end of a frame. Call after buffer swap. */
    public void recordFrameEnd() {
        long frameNs = System.nanoTime() - frameStartNs;
        frameTimes[frameHead] = frameNs;
        frameHead = (frameHead + 1) % WINDOW;
        if (frameCount < WINDOW) frameCount++;

        // Recompute statistics every WINDOW frames
        if (frameCount == WINDOW && frameHead == 0) {
            computeStatistics();
        }
    }

    /**
     * Records the duration of a single chunk meshing task.
     * Call this from the meshing thread upon task completion.
     *
     * @param nanos elapsed nanoseconds for the meshing task
     */
    public void recordRebuild(long nanos) {
        totalRebuildNs.addAndGet(nanos);
        totalRebuildCount.incrementAndGet();
    }

    /**
     * Updates the VRAM footprint estimate (sum of persistent buffer sizes).
     *
     * @param bytes total bytes allocated in persistent GPU buffers
     */
    public void setEstimatedVram(long bytes) {
        this.estimatedVramBytes = bytes;
    }

    // -------------------------------------------------------------------------
    // Statistics computation
    // -------------------------------------------------------------------------

    private void computeStatistics() {
        long[] sorted = Arrays.copyOf(frameTimes, frameCount);
        Arrays.sort(sorted);

        // Average FPS
        long sumNs = 0;
        for (long t : sorted) sumNs += t;
        double avgFrameNs = (double) sumNs / frameCount;
        avgFPS = 1_000_000_000.0 / avgFrameNs;

        // 1% low — average of the worst 1% of frame times
        int low1Count = Math.max(1, frameCount / 100);
        long sumLow1 = 0;
        for (int i = frameCount - low1Count; i < frameCount; i++) sumLow1 += sorted[i];
        low1FPS = 1_000_000_000.0 / ((double) sumLow1 / low1Count);

        // 0.1% low
        int low01Count = Math.max(1, frameCount / 1000);
        long sumLow01 = 0;
        for (int i = frameCount - low01Count; i < frameCount; i++) sumLow01 += sorted[i];
        low01FPS = 1_000_000_000.0 / ((double) sumLow01 / low01Count);

        // Chunk rebuild
        long tc = totalRebuildCount.getAndSet(0);
        long tn = totalRebuildNs.getAndSet(0);
        avgRebuildMs = tc > 0 ? (tn / tc) / 1_000_000.0 : 0;

        if (destiny.renderer.config.RendererConfig.get().logStatsIntervalSeconds > 0) {
            LOGGER.info(String.format(
                "[Caesium] Stats: %.1f FPS | 1%% Low: %.1f | 0.1%% Low: %.1f | Rebuild: %.2fms | VRAM: %.1fMB",
                avgFPS, low1FPS, low01FPS, avgRebuildMs, estimatedVramBytes / 1024.0 / 1024.0
            ));
        }
    }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    public double getAvgFPS()            { if(frameCount > 0) computeStatistics(); return avgFPS; }
    public double getLow1FPS()           { if(frameCount > 0) computeStatistics(); return low1FPS; }
    public double getLow01FPS()          { if(frameCount > 0) computeStatistics(); return low01FPS; }
    public double getAvgRebuildMs()      { return avgRebuildMs; }
    public long   getHeapDeltaBytes()    { return heapDeltaBytes; }
    public long   getEstimatedVramBytes(){ return estimatedVramBytes; }

    /** @return true if enough frames have been sampled for statistics to be valid */
    public boolean hasStats() { return frameCount >= WINDOW; }

    // =========================================================================
    // --- DEV ONLY: AUTOMATED 20-RUN BENCHMARK SUITE ---
    // =========================================================================

    public static void startBenchmarkSuite() {
    }

    public void resetWindow() {
        frameHead = 0;
        frameCount = 0;
        avgFPS = 0;
    }
}
