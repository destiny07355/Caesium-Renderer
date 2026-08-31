package destiny.renderer.memory;

import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL44;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.logging.Logger;

/**
 * Wraps a single OpenGL buffer object (VBO, SSBO, or IBO) with an off-heap {@link MemorySegment}
 * representing the persistently mapped CPU-writable view of the buffer.
 *
 * <h2>iGPU Zero-Copy Path</h2>
 * On integrated GPUs with unified memory, the CPU's off-heap segment and the GPU's buffer
 * share the same physical DRAM pages (via {@code GL_MAP_PERSISTENT_BIT | GL_MAP_COHERENT_BIT}).
 * Writing to the segment directly produces vertex data readable by the GPU without any
 * PCIe DMA transfer or driver copy.
 *
 * <h2>dGPU Persistent Mapping Path</h2>
 * On discrete GPUs, persistent mapping still eliminates the driver-level copy (the copy
 * from system RAM to VRAM happens asynchronously via DMA while the CPU continues other work).
 * This eliminates the synchronous {@code glBufferSubData} stalls that plague vanilla Minecraft.
 *
 * <h2>Usage Pattern</h2>
 * <pre>
 *   GpuBuffer vbo = GpuBuffer.createPersistent(GL15.GL_ARRAY_BUFFER, 64 * 1024 * 1024);
 *   // Write from any thread:
 *   vbo.segment().set(ValueLayout.JAVA_LONG, offset, packedVertex);
 *   // Draw:
 *   GL11.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo.handle());
 *   GL43.glMultiDrawElementsIndirect(...);
 *   vbo.close(); // when done
 * </pre>
 */
public final class GpuBuffer implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger("Caesium/GpuBuffer");

    /** OpenGL buffer object handle. */
    private int glHandle;

    /** Persistently mapped CPU-side view of the buffer memory. */
    private MemorySegment mapped;

    /** Size in bytes of the buffer. */
    private final long sizeBytes;

    /** Target binding point (e.g., GL_ARRAY_BUFFER, GL_SHADER_STORAGE_BUFFER). */
    private final int target;

    /** Whether this buffer was created with persistent mapping. */
    private final boolean persistent;

    /** Arena that owns the mapped segment lifecycle (used for cleanup on close). */
    private final Arena owningArena;

    /** Whether this buffer is in OpenGL 3.3 fallback mode. */
    private final boolean isFallback;

    // -------------------------------------------------------------------------
    // Factory methods
    // -------------------------------------------------------------------------

    /**
     * Creates a persistently mapped GPU buffer. The buffer is allocated in GPU memory
     * and its CPU-side view is represented as an off-heap {@link MemorySegment}.
     *
     * @param target    OpenGL binding target (e.g., {@code GL_ARRAY_BUFFER})
     * @param sizeBytes total buffer size in bytes
     * @return a fully initialized {@link GpuBuffer} ready for reading/writing
     */
    public static GpuBuffer createPersistent(int target, long sizeBytes) {
        boolean hasStorage = org.lwjgl.opengl.GL.getCapabilities().GL_ARB_buffer_storage;

        if (!hasStorage) {
            // OpenGL 3.3 Fallback allocation path using standard VBO + off-heap client memory
            int handle = GL15.glGenBuffers();
            GL15.glBindBuffer(target, handle);
            GL15.glBufferData(target, sizeBytes, GL15.GL_DYNAMIC_DRAW);
            GL15.glBindBuffer(target, 0);

            // Allocate a local off-heap memory segment via standard FFM Arena
            Arena arena = RendererArenaManager.getOrCreatePersistentArena("gpu_buffer_fallback_" + handle);
            MemorySegment segment = arena.allocate(sizeBytes);

            LOGGER.info("[Caesium] Created fallback GpuBuffer handle=" + handle + " size=" + sizeBytes + "B (OpenGL 3.3 mode)");
            return new GpuBuffer(handle, segment, sizeBytes, target, false, arena, true);
        }

        int handle = GL15.glGenBuffers();
        GL15.glBindBuffer(target, handle);

        // Allocate immutable buffer storage with persistent mapping flags
        // LWJGL3 size-only overload: glBufferStorage(target, size, flags)
        GL44.glBufferStorage(
            target,
            sizeBytes,
            GL44.GL_MAP_WRITE_BIT | GL44.GL_MAP_PERSISTENT_BIT | GL44.GL_MAP_COHERENT_BIT | GL44.GL_DYNAMIC_STORAGE_BIT
        );

        // Map the buffer persistently — glMapBufferRange returns ByteBuffer in LWJGL3
        ByteBuffer mappedBuf = GL30.glMapBufferRange(
            target,
            0,
            sizeBytes,
            GL44.GL_MAP_WRITE_BIT | GL44.GL_MAP_PERSISTENT_BIT | GL44.GL_MAP_COHERENT_BIT
        );

        if (mappedBuf == null) {
            GL15.glDeleteBuffers(handle);
            throw new RuntimeException("[Caesium] glMapBufferRange returned NULL for " + sizeBytes + " bytes on target " + target);
        }

        long mappedAddress = MemoryUtil.memAddress(mappedBuf);
        // Wrap the mapped pointer in a MemorySegment owned by a shared arena
        Arena arena = RendererArenaManager.getOrCreatePersistentArena("gpu_buffer_" + handle);
        MemorySegment segment = MemorySegment.ofAddress(mappedAddress).reinterpret(sizeBytes, arena, null);

        GL15.glBindBuffer(target, 0);
        LOGGER.fine("[Caesium] Created persistent GpuBuffer handle=" + handle + " size=" + sizeBytes + "B");
        return new GpuBuffer(handle, segment, sizeBytes, target, true, arena, false);
    }

    /**
     * Creates a non-persistent (standard) GPU buffer. Used for static geometry that is
     * uploaded once (e.g., index buffers) and does not require persistent CPU access.
     *
     * @param target    OpenGL binding target
     * @param sizeBytes buffer size in bytes
     * @param usage     OpenGL usage hint (e.g., {@code GL_STATIC_DRAW})
     * @return initialized non-persistent {@link GpuBuffer}
     */
    public static GpuBuffer createStatic(int target, long sizeBytes, int usage) {
        int handle = GL15.glGenBuffers();
        GL15.glBindBuffer(target, handle);
        GL15.glBufferData(target, sizeBytes, usage);
        GL15.glBindBuffer(target, 0);
        return new GpuBuffer(handle, null, sizeBytes, target, false, null, false);
    }

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    private GpuBuffer(int glHandle, MemorySegment mapped, long sizeBytes, int target, boolean persistent, Arena owningArena, boolean isFallback) {
        this.glHandle = glHandle;
        this.mapped = mapped;
        this.sizeBytes = sizeBytes;
        this.target = target;
        this.persistent = persistent;
        this.owningArena = owningArena;
        this.isFallback = isFallback;
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    /** @return the OpenGL buffer object name (handle). */
    public int handle() { return glHandle; }

    /**
     * Returns the persistently mapped CPU-side segment for direct write access.
     * Returns {@code null} for non-persistent buffers.
     *
     * @return the mapped {@link MemorySegment}, or null if not persistently mapped
     */
    public MemorySegment segment() { return mapped; }

    /** @return total size in bytes */
    public long sizeBytes() { return sizeBytes; }

    /** @return the OpenGL binding target */
    public int target() { return target; }

    /** @return true if persistently mapped */
    public boolean isPersistent() { return persistent; }

    /** @return true if using OpenGL 3.3 fallback */
    public boolean isFallback() { return isFallback; }

    private static final java.util.concurrent.ConcurrentLinkedQueue<PendingUpload> UPLOAD_QUEUE = new java.util.concurrent.ConcurrentLinkedQueue<>();

    public record PendingUpload(int target, int handle, MemorySegment segment, long offset, long bytes) {}

    /**
     * Flushes local CPU changes to GPU memory under OpenGL 3.3 fallback mode.
     */
    public void flush(long offset, long bytes) {
        if (isFallback && mapped != null && glHandle != 0 && bytes > 0) {
            UPLOAD_QUEUE.add(new PendingUpload(target, glHandle, mapped.asSlice(offset, bytes), offset, bytes));
        }
    }

    public static void processPendingUploads() {
        PendingUpload upload;
        while ((upload = UPLOAD_QUEUE.poll()) != null) {
            GL15.glBindBuffer(upload.target, upload.handle);
            ByteBuffer slice = upload.segment.asByteBuffer();
            GL15.glBufferSubData(upload.target, upload.offset, slice);
            GL15.glBindBuffer(upload.target, 0);
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Binds this buffer to its target. Must be called on the GL thread.
     */
    public void bind() {
        GL15.glBindBuffer(target, glHandle);
    }

    /**
     * Unbinds this buffer's target (binds 0). Must be called on the GL thread.
     */
    public void unbind() {
        GL15.glBindBuffer(target, 0);
    }

    /**
     * Releases the OpenGL buffer object and its CPU-mapped segment.
     * After calling this method, the buffer is invalid and must not be used.
     * Safe to call multiple times.
     */
    @Override
    public void close() {
        if (glHandle != 0) {
            try {
                if (org.lwjgl.opengl.GL.getCapabilities() != null) {
                    if (persistent && mapped != null && !isFallback) {
                        GL15.glBindBuffer(target, glHandle);
                        GL15.glUnmapBuffer(target);
                        GL15.glBindBuffer(target, 0);
                    }
                    GL15.glDeleteBuffers(glHandle);
                }
            } catch (Throwable ignored) {
                // Ignore GL errors during JVM shutdown (context already destroyed)
            }
            glHandle = 0;
        }
        mapped = null;
        // owningArena is managed by RendererArenaManager — do not close it here
    }
}
