package destiny.renderer.particle;

/**
 * Thread-safe tracking and estimation of live particle counts for culling decisions
 * and performance profiling.
 *
 * <p>Extracted from {@code ParticleOptimizationMixin} so that non-mixin callers like
 * the frame profiler and telemetry can read particle counts without violating Sponge Mixin's
 * constraint against non-private static methods on mixin classes.
 */
public final class CaesiumParticleMetrics {

    private static volatile int liveEstimate = 0;
    private static long lastDecayMs = 0L;

    private CaesiumParticleMetrics() {}

    /**
     * Approximate live particle count in the scene.
     */
    public static int getLiveEstimate() {
        return liveEstimate;
    }

    /**
     * Records a spawned particle.
     */
    public static void recordSpawn() {
        liveEstimate++;
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
        if (liveEstimate >= maxCount) {
            return false;
        }
        liveEstimate++;
        return true;
    }

    /**
     * Bleeds the estimate down over time since particles expire naturally without
     * needing explicit removal hooks.
     */
    public static synchronized void decay() {
        long now = System.currentTimeMillis();
        if (lastDecayMs == 0L) {
            lastDecayMs = now;
            return;
        }
        long elapsed = now - lastDecayMs;
        if (elapsed >= 100L) {
            int decaySteps = (int) (elapsed / 100L);
            liveEstimate = Math.max(0, liveEstimate - decaySteps * 40);
            lastDecayMs = now;
        }
    }

    /**
     * Resets the particle metrics on world unload.
     */
    public static synchronized void reset() {
        liveEstimate = 0;
        lastDecayMs = 0L;
    }
}
