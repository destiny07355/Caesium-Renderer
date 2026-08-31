package caesium.engine.backend;

import java.util.ArrayList;
import java.util.List;

/**
 * Software/CPU reference backend. It does not touch a GPU: commands are recorded into an
 * in-memory event log that the engine and tests can inspect, and allocations are
 * book-kept. Its purpose is to let the whole frame pipeline (render graph + scheduler +
 * scene) run end-to-end before any real GL/Vulkan driver exists, and to give the
 * competitive renderer a deterministic baseline for frame pacing.
 */
public final class NullBackend implements GpuBackend {

    private final Queue graphics = new Queue("graphics");
    private final Queue transfer = new Queue("transfer");
    private final Allocator allocator = new Allocator();
    private final List<RecordedFrame> frames = new ArrayList<>();
    private RecordedFrame current;

    @Override
    public BackendType type() {
        return BackendType.SOFTWARE;
    }

    @Override
    public String name() {
        return "Caesium NullBackend (software reference)";
    }

    @Override
    public void initialize() {
        synchronized (frames) {
            frames.clear();
            current = null;
        }
    }

    @Override
    public void shutdown() {
        initialize();
    }

    @Override
    public GpuQueue graphicsQueue() {
        return graphics;
    }

    @Override
    public GpuQueue transferQueue() {
        return transfer;
    }

    @Override
    public GpuMemoryAllocator memory() {
        return allocator;
    }

    @Override
    public GpuBuffer createBuffer(GpuBuffer.Usage usage, int size) {
        return new Buffer(usage, size);
    }

    @Override
    public GpuImage createImage(GpuImage.Format format, int width, int height) {
        return new Image(format, width, height);
    }

    @Override
    public GpuPipeline createPipeline() {
        return new Pipeline();
    }

    @Override
    public GpuTimer createTimer() {
        return new Timer();
    }

    @Override
    public void beginFrame(int frameIndex) {
        current = new RecordedFrame(frameIndex);
    }

    @Override
    public void endFrame(int frameIndex) {
        RecordedFrame f = current;
        if (f != null) {
            synchronized (frames) {
                frames.add(f);
            }
        }
        current = null;
    }

    /** Number of frames the engine has presented through this backend. */
    public int recordedFrames() {
        synchronized (frames) {
            return frames.size();
        }
    }

    /** Total draw commands recorded across all frames. */
    public int recordedDraws() {
        int n = 0;
        synchronized (frames) {
            for (RecordedFrame f : frames) {
                for (String e : f.events) {
                    if (e.startsWith("draw")) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    public int recordedEvents() {
        int n = 0;
        synchronized (frames) {
            for (RecordedFrame f : frames) {
                n += f.events.size();
            }
        }
        return n;
    }

    // -------------------------------------------------------------------------

    private static final class RecordedFrame {
        final int index;
        final List<String> events = new ArrayList<>();

        RecordedFrame(int index) {
            this.index = index;
        }
    }

    private final class Queue implements GpuQueue {
        private final String name;

        Queue(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public GpuCommandEncoder createEncoder() {
            return new Encoder();
        }

        @Override
        public void submit(GpuCommandEncoder encoder) {
            RecordedFrame f = current;
            if (f != null && encoder instanceof Encoder e) {
                f.events.addAll(e.events);
            }
        }

        @Override
        public void waitIdle() {
        }
    }

    private static final class Encoder implements GpuCommandEncoder {
        private final List<String> events = new ArrayList<>();

        @Override
        public void begin() {
            events.add("begin");
        }

        @Override
        public void bindPipeline(GpuPipeline pipeline) {
            events.add("bindPipeline");
        }

        @Override
        public void writeBuffer(GpuBuffer buffer, int offset, java.nio.ByteBuffer data) {
            events.add("writeBuffer:" + data.remaining() + "@" + offset);
        }

        @Override
        public void bindVertexBuffer(GpuBuffer buffer, VertexLayout layout) {
            events.add("bindVertexBuffer:" + layout);
        }

        @Override
        public void draw(int vertexCount, int instanceCount) {
            events.add("draw:" + vertexCount + "x" + instanceCount);
        }

        @Override
        public void drawIndexed(int indexCount, int instanceCount) {
            events.add("drawIndexed:" + indexCount + "x" + instanceCount);
        }

        @Override
        public void copyBuffer(GpuBuffer src, int srcOffset, GpuBuffer dst, int dstOffset, int size) {
            events.add("copyBuffer:" + size);
        }

        @Override
        public void bindUniformBuffer(GpuBuffer buffer) {
            events.add("bindUniformBuffer:" + buffer.size());
        }

        @Override
        public void bindIndexBuffer(GpuBuffer buffer) {
            events.add("bindIndexBuffer:" + buffer.size());
        }

        @Override
        public void writeTimestamp(GpuTimer timer, boolean end) {
            events.add(end ? "timestampEnd" : "timestampBegin");
        }

        @Override
        public void end() {
            events.add("end");
        }
    }

    private static final class Buffer implements GpuBuffer {
        private final Usage usage;
        private final int size;
        private boolean destroyed;

        Buffer(Usage usage, int size) {
            this.usage = usage;
            this.size = size;
        }

        @Override
        public Usage usage() {
            return usage;
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public long handle() {
            return System.identityHashCode(this);
        }

        @Override
        public void destroy() {
            destroyed = true;
        }
    }

    private static final class Image implements GpuImage {
        private final Format format;
        private final int width;
        private final int height;
        private boolean destroyed;

        Image(Format format, int width, int height) {
            this.format = format;
            this.width = width;
            this.height = height;
        }

        @Override
        public Format format() {
            return format;
        }

        @Override
        public int width() {
            return width;
        }

        @Override
        public int height() {
            return height;
        }

        @Override
        public long handle() {
            return System.identityHashCode(this);
        }

        @Override
        public void destroy() {
            destroyed = true;
        }
    }

    private static final class Pipeline implements GpuPipeline {
        private boolean destroyed;

        @Override
        public long handle() {
            return System.identityHashCode(this);
        }

        @Override
        public void destroy() {
            destroyed = true;
        }
    }

    private static final class Timer implements GpuTimer {
        private final long start = System.nanoTime();

        @Override
        public long elapsedNanos() {
            return System.nanoTime() - start;
        }

        @Override
        public void destroy() {
        }
    }

    private static final class Allocator implements GpuMemoryAllocator {
        private long bytes;
        private int count;

        @Override
        public GpuBuffer allocate(GpuBuffer.Usage usage, int size) {
            bytes += size;
            count++;
            return new Buffer(usage, size);
        }

        @Override
        public void free(GpuBuffer buffer) {
            if (buffer instanceof Buffer b && !b.destroyed) {
                bytes -= b.size();
                count--;
                b.destroy();
            }
        }

        @Override
        public long bytesAllocated() {
            return bytes;
        }

        @Override
        public int activeAllocations() {
            return count;
        }
    }
}