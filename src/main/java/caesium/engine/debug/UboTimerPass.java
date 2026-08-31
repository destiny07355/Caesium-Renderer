package caesium.engine.debug;

import caesium.engine.backend.GpuBackend;
import caesium.engine.backend.GpuBuffer;
import caesium.engine.backend.GpuCommandEncoder;
import caesium.engine.backend.GpuPipeline;
import caesium.engine.backend.GpuTimer;
import caesium.engine.device.FrameContext;
import caesium.engine.graph.PassResource;
import caesium.engine.graph.RenderPass;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Set;

/**
 * Month-2 debug pass proving the uniform-block + GPU-timing plumbing (ARCHITECTURE.md §19):
 * it uploads the same full-screen quad as {@link TestQuadPass} but binds a tinted UBO
 * through {@link GpuCommandEncoder#bindUniformBuffer} and wraps the draw in GPU timestamps.
 * The fragment shader multiplies vertex color by the tint, so binding a 50% white tint
 * turns the pure-red quad into a dim red — the pixel readback distinguishes "UBO applied"
 * from the identity-white default.
 */
public final class UboTimerPass implements RenderPass {

    private static final float[] VERTICES = {
            -1.0f, -1.0f, 1f, 0f, 0f, 1f,
             1.0f, -1.0f, 1f, 0f, 0f, 1f,
             1.0f,  1.0f, 1f, 0f, 0f, 1f,
            -1.0f, -1.0f, 1f, 0f, 0f, 1f,
             1.0f,  1.0f, 1f, 0f, 0f, 1f,
            -1.0f,  1.0f, 1f, 0f, 0f, 1f,
    };

    /** Half-intensity white tint: red quad * 0.5 = (0.5, 0, 0). */
    private static final float[] TINT = {0.5f, 0.5f, 0.5f, 1.0f};

    private static final PassResource OUTPUT =
            new PassResource("debug.uboTimer.output", PassResource.Kind.IMAGE);

    private final GpuBackend backend;
    private GpuBuffer vertexBuffer;
    private GpuBuffer uniformBuffer;
    private GpuPipeline pipeline;
    private GpuTimer timer;
    private boolean prepared;

    public UboTimerPass(GpuBackend backend) {
        this.backend = backend;
    }

    @Override
    public String id() {
        return "debug.uboTimer";
    }

    @Override
    public String name() {
        return "UboTimer";
    }

    @Override
    public Set<PassResource> reads() {
        return Set.of();
    }

    @Override
    public Set<PassResource> writes() {
        return Set.of(OUTPUT);
    }

    @Override
    public Set<PassResource> resources() {
        return writes();
    }

    @Override
    public Set<String> dependencies() {
        return Set.of();
    }

    @Override
    public boolean hasWork(FrameContext frame) {
        return true;
    }

    @Override
    public void prepare(FrameContext frame) {
        if (prepared) {
            return;
        }
        int vertexBytes = VERTICES.length * Float.BYTES;
        vertexBuffer = backend.memory().allocate(GpuBuffer.Usage.VERTEX, vertexBytes);
        uniformBuffer = backend.memory().allocate(GpuBuffer.Usage.UNIFORM, Float.BYTES * 20);
        pipeline = backend.createPipeline();
        timer = backend.createTimer();

        GpuCommandEncoder encoder = frame.encoder();
        encoder.writeBuffer(vertexBuffer, 0, floats(VERTICES));
        encoder.writeBuffer(uniformBuffer, 0, uniformData());
        prepared = true;
    }

    @Override
    public void execute(GpuCommandEncoder encoder, FrameContext frame) {
        encoder.bindPipeline(pipeline);
        encoder.bindVertexBuffer(vertexBuffer, GpuCommandEncoder.VertexLayout.POS_COLOR_2F_4F);
        encoder.bindUniformBuffer(uniformBuffer);
        encoder.writeTimestamp(timer, false);
        encoder.draw(6, 1);
        encoder.writeTimestamp(timer, true);
    }

    /** The last recorded region's GPU time, valid after the queue is idle. */
    public long elapsedNanos() {
        return timer.elapsedNanos();
    }

    private static ByteBuffer floats(float[] values) {
        ByteBuffer data = ByteBuffer.allocateDirect(values.length * Float.BYTES)
                .order(ByteOrder.nativeOrder());
        for (float v : values) {
            data.putFloat(v);
        }
        data.flip();
        return data;
    }

    /** 80-byte uniform block: column-major identity MVP (16 floats) + tint (4 floats). */
    private static ByteBuffer uniformData() {
        ByteBuffer data = ByteBuffer.allocateDirect(Float.BYTES * 20)
                .order(ByteOrder.nativeOrder());
        for (int i = 0; i < 16; i++) {
            data.putFloat(i % 5 == 0 ? 1.0f : 0.0f); // identity mat4
        }
        for (float t : TINT) {
            data.putFloat(t);
        }
        data.flip();
        return data;
    }
}