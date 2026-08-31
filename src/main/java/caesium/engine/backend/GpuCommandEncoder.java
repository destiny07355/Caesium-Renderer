package caesium.engine.backend;

import java.nio.ByteBuffer;

/**
 * Records GPU commands into a buffer. One encoder instance is produced per submission
 * batch ({@link GpuQueue#createEncoder()}) and is driven by the render graph.
 */
public interface GpuCommandEncoder {

    void begin();

    void bindPipeline(GpuPipeline pipeline);

    /** Uploads CPU data into a buffer. The byte order is native; content is opaque. */
    void writeBuffer(GpuBuffer buffer, int offset, ByteBuffer data);

    /** Binds a vertex buffer for subsequent draws; the engine declares its layout. */
    void bindVertexBuffer(GpuBuffer buffer, VertexLayout layout);

    /** Binds a 32-bit index buffer for subsequent {@link #drawIndexed} calls. */
    void bindIndexBuffer(GpuBuffer buffer);

    /**
     * Binds a UNIFORM-usage buffer to the pipeline's uniform block (set 0 / block 0).
     * The shader reads it as a single {@code Uniforms} block; the engine uploads per-frame
     * camera/object data through it. The backend keeps a default identity buffer bound
     * until the engine overrides it.
     */
    void bindUniformBuffer(GpuBuffer buffer);

    void draw(int vertexCount, int instanceCount);

    void drawIndexed(int indexCount, int instanceCount);

    void copyBuffer(GpuBuffer src, int srcOffset, GpuBuffer dst, int dstOffset, int size);

    /**
     * Records a GPU timestamp into {@code timer} at the current command-buffer position.
     * Call with {@code end=false} to open the region and {@code end=true} to close it; read
     * the elapsed time from {@link GpuTimer#elapsedNanos()} after the queue is idle.
     */
    void writeTimestamp(GpuTimer timer, boolean end);

    void end();

    /**
     * The vertex layout the engine asks the backend to honor when binding a buffer.
     * The reference backend implements the full set; exotic backends may round-trip.
     */
    enum VertexLayout {
        /** Position (2 floats), color (4 floats) — used by the debug/test quad. */
        POS_COLOR_2F_4F,
        /** Position (3 floats), color (4 floats) — used by 3D section-mesh geometry. */
        POS_COLOR_3F_4F
    }
}