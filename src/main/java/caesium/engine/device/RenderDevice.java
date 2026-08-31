package caesium.engine.device;

import caesium.engine.backend.GpuBackend;
import caesium.engine.backend.GpuQueue;

/**
 * Owns the {@link GpuBackend} and the frame-in-flight slots. {@link #beginFrame()} and
 * {@link #endFrame(FrameContext)} are the only frame lifecycle entry points the rest of
 * the engine calls. Frames-in-flight is clamped to 2–4; the competitive policy drops it
 * to 2 to minimise latency.
 */
public final class RenderDevice {

    private final GpuBackend backend;
    private final int framesInFlight;
    private final EngineStatus status = new EngineStatus();
    private final FrameContext[] contexts;
    private int frameSlotIdx = 0;

    public RenderDevice(GpuBackend backend, int framesInFlight) {
        if (backend == null) {
            throw new IllegalArgumentException("backend must not be null");
        }
        this.backend = backend;
        this.framesInFlight = Math.max(1, Math.min(framesInFlight, 4));
        this.contexts = new FrameContext[this.framesInFlight];
        for (int i = 0; i < this.framesInFlight; i++) {
            contexts[i] = new FrameContext(i, status);
        }
    }

    public void start() {
        backend.initialize();
    }

    public void stop() {
        backend.shutdown();
    }

    public FrameContext beginFrame() {
        int idx = frameSlotIdx;
        FrameContext frame = contexts[idx];
        frame.setEncoder(backend.graphicsQueue().createEncoder());
        backend.beginFrame(idx);
        return frame;
    }

    public void endFrame(FrameContext frame) {
        if (frame.encoder() != null) {
            backend.graphicsQueue().submit(frame.encoder());
        }
        backend.endFrame(frame.index());
        frame.setEncoder(null);
        frameSlotIdx = (frameSlotIdx + 1) % framesInFlight;
    }

    public GpuBackend backend() {
        return backend;
    }

    public EngineStatus status() {
        return status;
    }

    public int framesInFlight() {
        return framesInFlight;
    }

    public GpuQueue transferQueue() {
        return backend.transferQueue();
    }
}