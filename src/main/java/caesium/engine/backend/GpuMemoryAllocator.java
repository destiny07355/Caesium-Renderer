package caesium.engine.backend;

/**
 * GPU memory management. The engine only ever asks for buffers and releases them; the
 * allocator suballocates large blocks and recycles freed ranges (TLSF in the GL backend,
 * a block allocator in the Vulkan backend — ARCHITECTURE.md §16).
 */
public interface GpuMemoryAllocator {

    GpuBuffer allocate(GpuBuffer.Usage usage, int size);

    void free(GpuBuffer buffer);

    long bytesAllocated();

    int activeAllocations();
}