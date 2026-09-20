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

    // Rolling exponential averages of operation costs (in microseconds)
    private double rollingMeshingUs = 420.0;
    private double rollingUploadUs = 110.0;
    private double rollingIntegrationUs = 37.0;

    // Frame-pacing and headroom estimation
    private double estimatedBaseRenderMs = 4.0;
    private float safetyReserveMs = 0.8f;
    private int dynamicWorkerLimit = 4;

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
        this.dynamicWorkerLimit = maxBackgroundJobs;
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

    // -------------------------------------------------------------------------
    // Deadline-Aware Frame Pacing & Feedback Loop
    // -------------------------------------------------------------------------

    public synchronized void recordMeshingCost(long durationNs) {
        double us = Math.max(10.0, durationNs / 1000.0);
        rollingMeshingUs = rollingMeshingUs * 0.90 + us * 0.10;
    }

    public synchronized void recordUploadCost(long durationNs) {
        double us = Math.max(5.0, durationNs / 1000.0);
        rollingUploadUs = rollingUploadUs * 0.90 + us * 0.10;
    }

    public synchronized void recordIntegrationCost(long durationNs) {
        double us = Math.max(2.0, durationNs / 1000.0);
        rollingIntegrationUs = rollingIntegrationUs * 0.90 + us * 0.10;
    }

    public double predictedMeshingMillis() {
        return rollingMeshingUs / 1000.0;
    }

    public double predictedUploadMillis() {
        return rollingUploadUs / 1000.0;
    }

    public double predictedIntegrationMillis() {
        return rollingIntegrationUs / 1000.0;
    }

    /**
     * Calculates available extra budget (in milliseconds) for chunk uploads and integration this frame.
     * Guaranteed never to spend into the safety reserve.
     */
    public synchronized float availableExtraBudgetMillis(double lastFrameMs, float clientTargetMs) {
        float target = clientTargetMs > 0 ? clientTargetMs : targetFrameMillis;
        if (lastFrameMs > 0.0) {
            estimatedBaseRenderMs = estimatedBaseRenderMs * 0.85 + lastFrameMs * 0.15;
        }
        float spare = target - (float) estimatedBaseRenderMs - safetyReserveMs;
        return Math.max(0.2f, spare);
    }

    // Anti-oscillation hysteresis counter
    private int healthyConsecutiveFrames = 0;
    private int decreaseCooldownFrames = 0;
    private int lowPriorityConsecutiveFrames = 0;

    /**
     * Closes the feedback loop: adapts worker pool concurrency and thread priority based
     * on whether the render thread is hitting its frame deadline, respecting CPU core bounds.
     */
    public synchronized void adjustWorkerPressure(double lastFrameMs, float targetMs, WorkStealingPool pool) {
        if (pool == null) return;
        int availableCores = Runtime.getRuntime().availableProcessors();
        int safeLimit = destiny.renderer.scheduler.ThreadPriorityManager.calculateSafeWorkerLimit(availableCores);
        int maxWorkers = Math.min(pool.workerCount(), safeLimit);
        float target = targetMs > 0 ? targetMs : targetFrameMillis;

        if (decreaseCooldownFrames > 0) {
            decreaseCooldownFrames--;
        }

        if (lastFrameMs < target * 0.60f) {
            // Frame healthy: increment hysteresis counter
            healthyConsecutiveFrames++;
            lowPriorityConsecutiveFrames = 0;
            if (healthyConsecutiveFrames >= 10 && decreaseCooldownFrames == 0) {
                // After 10 consecutive healthy frames and cooldown passed, cautiously allow one more worker
                dynamicWorkerLimit = Math.min(maxWorkers, dynamicWorkerLimit + 1);
                healthyConsecutiveFrames = 0;
            }
            destiny.renderer.scheduler.ThreadPriorityManager.setWorkerPriorityLow(false);
        } else if (lastFrameMs > target * 1.05f) {
            // Frame missed: immediately back off worker concurrency to guarantee render thread CPU
            dynamicWorkerLimit = Math.max(1, dynamicWorkerLimit - 1);
            healthyConsecutiveFrames = 0;
            decreaseCooldownFrames = 15; // Require 15 stable frames before allowing increase again
            destiny.renderer.scheduler.ThreadPriorityManager.setWorkerPriorityLow(true);
        } else {
            // Frame in normal range (60%-105% target): maintain steady state
            healthyConsecutiveFrames = 0;
            if (lastFrameMs > target * 0.90f) {
                lowPriorityConsecutiveFrames++;
                if (lowPriorityConsecutiveFrames >= 3) {
                    destiny.renderer.scheduler.ThreadPriorityManager.setWorkerPriorityLow(true);
                }
            } else {
                lowPriorityConsecutiveFrames = 0;
                destiny.renderer.scheduler.ThreadPriorityManager.setWorkerPriorityLow(false);
            }
        }
        pool.setActiveWorkerLimit(dynamicWorkerLimit);
    }

    public synchronized int dynamicWorkerLimit() {
        return dynamicWorkerLimit;
    }

    /**
     * Dynamically scales the per-frame GPU upload time budget (0.5 ms .. 3.0 ms).
     */
    public float dynamicUploadBudgetMillis(double lastFrameMs, float targetMs) {
        float target = targetMs > 0 ? targetMs : targetFrameMillis;
        if (lastFrameMs > target * 0.85f) {
            return 0.5f; // Frame struggling: minimal uploads
        }
        if (lastFrameMs < target * 0.50f) {
            return 2.5f; // Frame fast: aggressive uploads
        }
        return 1.5f; // Balanced default
    }

    /**
     * Dynamically scales the per-frame GPU upload byte budget (2 MB .. 16 MB).
     */
    public long dynamicUploadMaxBytes(double lastFrameMs, float targetMs) {
        float target = targetMs > 0 ? targetMs : targetFrameMillis;
        if (lastFrameMs > target * 0.85f) {
            return 2L * 1024 * 1024; // 2 MB
        }
        if (lastFrameMs < target * 0.50f) {
            return 16L * 1024 * 1024; // 16 MB
        }
        return 8L * 1024 * 1024; // 8 MB
    }
}