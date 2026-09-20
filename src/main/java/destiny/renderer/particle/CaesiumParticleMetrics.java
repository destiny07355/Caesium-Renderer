package destiny.renderer.particle;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Thread-safe tracking and estimation of live particle counts for culling decisions
 * and performance profiling.
 *
 * <p>Uses a striped {@link LongAdder} accumulator for lock-free, zero-contention
 * particle spawn recording across threads, with atomic synchronization on budget checks.
 *
 * <p>Extracted from {@code ParticleOptimizationMixin} so that non-mixin callers like
 * the frame profiler and telemetry can read particle counts without violating Sponge Mixin's
 * constraint against non-private static methods on mixin classes.
 */
public final class CaesiumParticleMetrics {

    /** Striped lock-free accumulator for high-frequency spawn recording across threads. */
    private static final LongAdder spawnAccumulator = new LongAdder();

    /** Consolidated live estimate after applying decay and draining spawn accumulator. */
    private static final AtomicInteger liveEstimate = new AtomicInteger(0);
    private static long lastDecayMs = 0L;

    private CaesiumParticleMetrics() {}

    /**
     * Approximate live particle count in the scene.
     */
    public static int getLiveEstimate() {
        return Math.max(0, liveEstimate.get() + (int) spawnAccumulator.sum());
    }

    /**
     * Records a spawned particle. Striped and lock-free; zero contention across CPU cores.
     */
    public static void recordSpawn() {
        spawnAccumulator.increment();
    }

    private static void drainAccumulator() {
        long added = spawnAccumulator.sumThenReset();
        if (added > 0) {
            liveEstimate.addAndGet((int) Math.min(added, Integer.MAX_VALUE));
        }
    }

    /**
     * Checks if a new particle is permitted under the current maximum limit.
     * Bleeds down the approximate count based on elapsed time before testing.
     *
     * @param maxCount maximum particle limit (0 = unlimited)
     * @return true if permitted, false if over budget
     */
    public static synchronized boolean checkAndRecordSpawn(int maxCount) {
        if (maxCount <= 0) return true;
        decay();
        drainAccumulator();
        if (liveEstimate.get() >= maxCount) {
            return false;
        }
        liveEstimate.incrementAndGet();
        return true;
    }

    /**
     * Bleeds the estimate down over time since particles expire naturally without
     * needing explicit removal hooks.
     */
    public static synchronized void decay() {
        drainAccumulator();
        long now = System.currentTimeMillis();
        if (lastDecayMs == 0L) {
            lastDecayMs = now;
            return;
        }
        long elapsed = now - lastDecayMs;
        if (elapsed >= 100L) {
            int decaySteps = (int) (elapsed / 100L);
            liveEstimate.updateAndGet(curr -> Math.max(0, curr - decaySteps * 40));
            lastDecayMs = now;
        }
    }

    /**
     * Resets the particle metrics on world unload.
     */
    public static synchronized void reset() {
        spawnAccumulator.reset();
        liveEstimate.set(0);
        lastDecayMs = 0L;
    }
}
