package caesium.engine.scheduler;

/**
 * Adaptive frame-budget policy. Gives meshing and uploads a fraction of the frame target
 * and caps how much background work may run, so the render thread is only throttled after
 * every cheaper knob has been turned first (ARCHITECTURE.md §13.3).
 *
 * <p>The default constructor scales its own fractions down using
 * {@link destiny.renderer.compat.ResourceShare} when other render/perf mods are
 * installed, so a budget we reserve for ourselves does not eat time those mods need while
 * we have no meshing work to spend it on.
 */
public class BudgetPolicy {

    public static final float DEFAULT_TARGET_MS = 16.67f;

    private final float targetFrameMillis;
    private final float meshingRatio;
    private final float uploadRatio;
    private final int maxBackgroundJobs;

    public BudgetPolicy() {
        this(DEFAULT_TARGET_MS,
             0.40f * destiny.renderer.compat.ResourceShare.budgetRatio(),
             0.15f * destiny.renderer.compat.ResourceShare.budgetRatio(),
             Math.max(2, Math.round(16 * destiny.renderer.compat.ResourceShare.backgroundAdmissionShare())));
    }

    public BudgetPolicy(float targetFrameMillis, float meshingRatio,
                        float uploadRatio, int maxBackgroundJobs) {
        this.targetFrameMillis = targetFrameMillis;
        this.meshingRatio = meshingRatio;
        this.uploadRatio = uploadRatio;
        this.maxBackgroundJobs = maxBackgroundJobs;
    }

    public float targetFrameMillis() {
        return targetFrameMillis;
    }

    public float meshingBudgetMillis() {
        return targetFrameMillis * meshingRatio;
    }

    public float uploadBudgetMillis() {
        return targetFrameMillis * uploadRatio;
    }

    public boolean admitBackground(int runningBackgroundJobs) {
        int liveMax = Math.max(2, Math.round(16 * destiny.renderer.compat.ResourceShare.backgroundAdmissionShare()));
        return runningBackgroundJobs < Math.max(maxBackgroundJobs, liveMax);
    }
}