package caesium.engine.device;

import caesium.engine.backend.GpuCommandEncoder;

/**
 * State hoisted for one frame in flight: the frame index, a reference to engine status,
 * and the command encoder the graph records into. Created by {@link RenderDevice} at
 * startup (one per in-flight frame slot), reused every frame.
 */
public final class FrameContext {

    private final int index;
    private final EngineStatus status;
    private GpuCommandEncoder encoder;

    public FrameContext(int index, EngineStatus status) {
        this.index = index;
        this.status = status;
    }

    public int index() {
        return index;
    }

    public EngineStatus status() {
        return status;
    }

    public GpuCommandEncoder encoder() {
        return encoder;
    }

    void setEncoder(GpuCommandEncoder encoder) {
        this.encoder = encoder;
    }
}