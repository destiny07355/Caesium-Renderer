package caesium.engine.debug;

import caesium.engine.backend.GpuBackend;
import caesium.engine.backend.GpuBuffer;
import caesium.engine.backend.GpuCommandEncoder;
import caesium.engine.backend.GpuPipeline;
import caesium.engine.device.FrameContext;
import caesium.engine.graph.PassResource;
import caesium.engine.graph.RenderPass;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Set;

/**
 * The Month-1 proof pass: a full-screen quad drawn through the render graph by the engine
 * core. It uploads two triangles (POS_COLOR layout, 6 vertices) into a buffer allocated
 * from the backend, then records bind-pipeline / bind-vertex-buffer / draw on the graph's
 * encoder. Nothing here touches Minecraft — this is the pixel-level exit criterion for the
 * GL path (ARCHITECTURE.md §23).
 *
 * <p>Signals are the quad's vertex colors: the center of the screen maps to the four
 * vertices' average, so a uniform quad renders as one flat color and a quad with distinct
 * per-vertex colors renders as a smooth gradient.
 */
public final class TestQuadPass implements RenderPass {

    /** Full-screen NDC quad: two triangles, position (x,y) + color (r,g,b,a), 24 B/vertex. */
    private static final float[] VERTICES = {
            // first triangle
            -1.0f, -1.0f, 1f, 0f, 0f, 1f,
             1.0f, -1.0f, 1f, 0f, 0f, 1f,
             1.0f,  1.0f, 1f, 0f, 0f, 1f,
            // second triangle
            -1.0f, -1.0f, 1f, 0f, 0f, 1f,
             1.0f,  1.0f, 1f, 0f, 0f, 1f,
            -1.0f,  1.0f, 1f, 0f, 0f, 1f,
    };

    private static final PassResource OUTPUT = new PassResource("debug.testQuad.output", PassResource.Kind.IMAGE);

    private final GpuBackend backend;
    private GpuBuffer vertexBuffer;
    private GpuPipeline pipeline;
    private boolean prepared;

    public TestQuadPass(GpuBackend backend) {
        this.backend = backend;
    }

    @Override
    public String id() {
        return "debug.testQuad";
    }

    @Override
    public String name() {
        return "TestQuad";
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
        int bytes = VERTICES.length * Float.BYTES;
        vertexBuffer = backend.memory().allocate(GpuBuffer.Usage.VERTEX, bytes);
        pipeline = backend.createPipeline();

        ByteBuffer data = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
        for (float v : VERTICES) {
            data.putFloat(v);
        }
        data.flip();

        GpuCommandEncoder encoder = frame.encoder();
        encoder.writeBuffer(vertexBuffer, 0, data);
        prepared = true;
    }

    @Override
    public void execute(GpuCommandEncoder encoder, FrameContext frame) {
        encoder.bindPipeline(pipeline);
        encoder.bindVertexBuffer(vertexBuffer, GpuCommandEncoder.VertexLayout.POS_COLOR_2F_4F);
        encoder.draw(6, 1);
    }
}