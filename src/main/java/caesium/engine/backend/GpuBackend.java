package caesium.engine.backend;

/**
 * Root abstraction over the GPU. The engine knows only this interface and its siblings;
 * it never touches a concrete API. A {@link GpuBackend} owns the device, the queues,
 * the memory allocator and the frame lifecycle for a single rendering backend.
 *
 * <p>Implementations: {@link NullBackend} (software reference), a GL backend (reference
 * pixels) and a Vulkan backend (primary, Month 2). See ARCHITECTURE.md §10.
 */
public interface GpuBackend {

    enum BackendType {
        VULKAN,
        OPENGL,
        SOFTWARE
    }

    BackendType type();

    /** Human-readable backend name, used in logs and the overlay. */
    String name();

    /** Initialises the device. Must be called once on the render thread before frames. */
    void initialize();

    /** Tears down the device. Idempotent. */
    void shutdown();

    GpuQueue graphicsQueue();

    GpuQueue transferQueue();

    GpuMemoryAllocator memory();

    GpuBuffer createBuffer(GpuBuffer.Usage usage, int size);

    GpuImage createImage(GpuImage.Format format, int width, int height);

    /** Width of the current render target's viewport, used to build the camera projection. */
    default int viewportWidth() {
        return 128;
    }

    /** Height of the current render target's viewport, used to build the camera projection. */
    default int viewportHeight() {
        return 128;
    }

    GpuPipeline createPipeline();

    /**
     * Creates a pipeline bound to a specific {@link GpuCommandEncoder.VertexLayout}. The
     * default implementation returns the layout-agnostic pipeline; backends that distinguish
     * geometry formats (GL/Vulkan 3F_4F terrain) override this to build a matching shader
     * program.
     */
    default GpuPipeline createPipeline(GpuCommandEncoder.VertexLayout layout) {
        return createPipeline();
    }

    /**
     * Creates a GPU timer for measuring GPU-side region time (ARCHITECTURE.md §19). The
     * engine records timestamps into it via {@link GpuCommandEncoder#writeTimestamp}.
     * May return a timer whose {@link GpuTimer#elapsedNanos()} is always 0 when the device
     * lacks timestamp support — callers must tolerate that.
     */
    GpuTimer createTimer();

    /** Marks the start of a frame; the backend rotates its in-flight resources. */
    void beginFrame(int frameIndex);

    /** Marks the end of a frame; all submitted queues must be flushed first. */
    void endFrame(int frameIndex);
}
