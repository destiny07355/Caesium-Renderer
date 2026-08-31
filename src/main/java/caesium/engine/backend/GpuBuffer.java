package caesium.engine.backend;

/**
 * A GPU-accessible buffer. Backend implementations hide the concrete handle; the engine
 * works with {@link #size()} and {@link #usage()} and pools/recycles via the allocator.
 */
public interface GpuBuffer {

    enum Usage {
        VERTEX,
        INDEX,
        UNIFORM,
        STAGING,
        INDIRECT,
        STORAGE
    }

    Usage usage();

    int size();

    /** Backend-specific handle; opaque to the engine (used for diagnostics only). */
    long handle();

    void destroy();
}