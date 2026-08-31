package caesium.engine.backend;

/**
 * GPU timing for one region of a command stream (ARCHITECTURE.md §19: GPU render time).
 * The engine records two timestamps through the {@link GpuCommandEncoder} —
 * {@code writeTimestamp(timer, false)} at the region start, {@code true} at the end — and
 * reads back the elapsed GPU time after the queue is idle. The concrete backend hides the
 * query mechanism (Vulkan timestamp query pool / GL timer query).
 */
public interface GpuTimer {

    /**
     * Elapsed GPU time of the recorded region in nanoseconds.
     *
     * <p>Valid only after the queue that submitted the timestamps has been waited idle.
     * Returns 0 when the device cannot measure GPU time (no timestamp support) or no
     * region has been recorded yet.
     */
    long elapsedNanos();

    /** Releases backend resources. Idempotent. */
    void destroy();
}