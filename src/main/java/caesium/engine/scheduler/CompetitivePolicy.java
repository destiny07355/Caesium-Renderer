package caesium.engine.scheduler;

/**
 * The competitive scheduling policy (ARCHITECTURE.md §13.4). Not a preset of graphics
 * options — a different frame-budget policy: minimum latency, minimal background spend,
 * and maximum budget reserved for critical work (crystals, players, camera-near terrain).
 */
public final class CompetitivePolicy {

    private final float criticalSpendRatio;
    private final int framesInFlight;
    private final float backgroundThrottle;

    public CompetitivePolicy() {
        this(0.85f, 2, 0.05f);
    }

    public CompetitivePolicy(float criticalSpendRatio, int framesInFlight, float backgroundThrottle) {
        this.criticalSpendRatio = criticalSpendRatio;
        this.framesInFlight = Math.max(1, framesInFlight);
        this.backgroundThrottle = backgroundThrottle;
    }

    /** Fraction of the frame budget reserved for critical work. */
    public float criticalSpendRatio() {
        return criticalSpendRatio;
    }

    /** Lower is less latency; 2 is the minimum that keeps the GPU busy. */
    public int framesInFlight() {
        return framesInFlight;
    }

    /** Fraction of normal background spend allowed while competitive. */
    public float backgroundThrottle() {
        return backgroundThrottle;
    }
}