package caesium.engine.device;

/**
 * Live engine telemetry, updated by the scheduler each frame. The scheduler and the
 * performance overlay read this; nothing in the engine blocks on it.
 */
public final class EngineStatus {

    private volatile long cpuFrameTimeNs;
    private volatile long gpuFrameTimeNs;
    private volatile long frameCount;
    private volatile float lastDeltaMillis;
    private volatile int backgroundJobs;
    private volatile int uploadBytesInFlight;
    private volatile boolean backgroundAdmitted;

    public void recordFrameStarted(float deltaMillis) {
        this.lastDeltaMillis = deltaMillis;
    }

    public void recordFrameFinished(long cpuNanos) {
        this.cpuFrameTimeNs = cpuNanos;
        this.frameCount++;
    }

    public void recordGpuFrame(long gpuNanos) {
        this.gpuFrameTimeNs = gpuNanos;
    }

    public void recordBackgroundAdmitted(boolean admitted) {
        this.backgroundAdmitted = admitted;
    }

    public void addBackgroundJob(int delta) {
        this.backgroundJobs += delta;
    }

    public void setUploadBytes(int bytes) {
        this.uploadBytesInFlight = bytes;
    }

    public long cpuFrameTimeNs() {
        return cpuFrameTimeNs;
    }

    public long gpuFrameTimeNs() {
        return gpuFrameTimeNs;
    }

    public long frameCount() {
        return frameCount;
    }

    public float lastDeltaMillis() {
        return lastDeltaMillis;
    }

    public int backgroundJobs() {
        return backgroundJobs;
    }

    public int uploadBytesInFlight() {
        return uploadBytesInFlight;
    }

    public boolean backgroundAdmitted() {
        return backgroundAdmitted;
    }
}