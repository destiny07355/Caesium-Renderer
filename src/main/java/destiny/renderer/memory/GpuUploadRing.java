package destiny.renderer.memory;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL44;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * High-throughput asynchronous geometry upload ring.
 *
 * <p>Meshing worker threads write raw packed quad geometry directly into persistently mapped
 * staging buffers off the main thread. At frame boundaries, the GL render thread executes
 * ultra-fast GPU copy commands (or pointer advances) without stalling for CPU-GPU synchronization.
 */
public final class GpuUploadRing {

    private static final Logger LOGGER = Logger.getLogger("Caesium/UploadRing");

    public record UploadTask(
        long stagingOffset,
        long targetOffset,
        long byteSize,
        int targetBufferHandle
    ) {}

    private static final int NUM_SLOTS = 3; // Triple buffered ring
    private static final long DEFAULT_SLOT_SIZE = 16L * 1024 * 1024; // 16 MB per slot (48 MB total)

    private final long slotCapacity;
    private final long totalCapacity;
    private int pboHandle;
    private MemorySegment mappedBuffer;
    private Arena owningArena;

    private int activeSlot = 0;
    private final AtomicLong[] slotOffsets = new AtomicLong[NUM_SLOTS];
    private final long[] syncFences = new long[NUM_SLOTS];
    private final ConcurrentLinkedQueue<UploadTask>[] taskQueues = new ConcurrentLinkedQueue[NUM_SLOTS];

    private boolean initialized = false;

    public GpuUploadRing() {
        this(DEFAULT_SLOT_SIZE);
    }

    public GpuUploadRing(long slotSize) {
        this.slotCapacity = slotSize;
        this.totalCapacity = slotSize * NUM_SLOTS;
        for (int i = 0; i < NUM_SLOTS; i++) {
            slotOffsets[i] = new AtomicLong(0L);
            syncFences[i] = 0L;
            taskQueues[i] = new ConcurrentLinkedQueue<>();
        }
    }

    /**
     * Initializes the GPU staging ring on the GL thread.
     */
    public synchronized void initialize() {
        if (initialized) return;

        boolean hasBufferStorage = org.lwjgl.opengl.GL.getCapabilities().GL_ARB_buffer_storage;

        if (hasBufferStorage) {
            pboHandle = GL15.glGenBuffers();
            GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, pboHandle);

            int flags = GL44.GL_MAP_WRITE_BIT | GL44.GL_MAP_PERSISTENT_BIT | GL44.GL_MAP_COHERENT_BIT;
            GL44.glBufferStorage(GL31.GL_COPY_READ_BUFFER, totalCapacity, flags);

            java.nio.ByteBuffer buf = GL30.glMapBufferRange(GL31.GL_COPY_READ_BUFFER, 0, totalCapacity, flags);
            owningArena = RendererArenaManager.getOrCreatePersistentArena("gpu_upload_ring");
            mappedBuffer = buf != null ? MemorySegment.ofBuffer(buf) : owningArena.allocate(totalCapacity);

            GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, 0);
            LOGGER.info("[Caesium] Persistently mapped GPU Upload Ring created (" + (totalCapacity / (1024 * 1024)) + " MB)");
        } else {
            // Fallback staging buffer
            pboHandle = GL15.glGenBuffers();
            owningArena = RendererArenaManager.getOrCreatePersistentArena("gpu_upload_ring_fallback");
            mappedBuffer = owningArena.allocate(totalCapacity);
            LOGGER.info("[Caesium] Standard GPU Upload Ring created (" + (totalCapacity / (1024 * 1024)) + " MB, fallback mode)");
        }

        initialized = true;
    }

    /**
     * Stages raw vertex/index data into the active frame's staging slot from any worker thread.
     *
     * @return true if successfully staged, false if the slot is full this frame
     */
    public boolean stage(MemorySegment source, long byteSize, int targetBufferHandle, long targetOffset) {
        if (!initialized || mappedBuffer == null || byteSize <= 0) return false;

        int slot = activeSlot;
        long offsetInSlot = slotOffsets[slot].getAndAdd((byteSize + 15L) & ~15L);

        if (offsetInSlot + byteSize > slotCapacity) {
            return false; // Slot capacity exceeded, fallback to sync copy
        }

        long globalStagingOffset = (long) slot * slotCapacity + offsetInSlot;

        // Fast copy into mapped memory
        MemorySegment.copy(source, 0L, mappedBuffer, globalStagingOffset, byteSize);

        taskQueues[slot].add(new UploadTask(globalStagingOffset, targetOffset, byteSize, targetBufferHandle));
        return true;
    }

    /**
     * Flushes staged tasks for the current frame to their destination GPU buffers.
     * Must be called on the GL render thread.
     */
    public void flushFrameUploads(int maxUploads) {
        if (!initialized) return;

        int slot = activeSlot;
        ConcurrentLinkedQueue<UploadTask> queue = taskQueues[slot];
        int count = 0;

        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, pboHandle);

        UploadTask task;
        while ((task = queue.poll()) != null) {
            GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, task.targetBufferHandle());
            GL31.glCopyBufferSubData(
                GL31.GL_COPY_READ_BUFFER,
                GL31.GL_COPY_WRITE_BUFFER,
                task.stagingOffset(),
                task.targetOffset(),
                task.byteSize()
            );
            count++;
            if (maxUploads > 0 && count >= maxUploads) break;
        }

        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, 0);

        // Place a fence on the completed slot
        if (syncFences[slot] != 0L) {
            GL32.glDeleteSync(syncFences[slot]);
        }
        syncFences[slot] = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);

        // Advance to next slot in the ring
        activeSlot = (activeSlot + 1) % NUM_SLOTS;

        // Wait for the next slot to be released by the GPU before reusing
        int nextSlot = activeSlot;
        if (syncFences[nextSlot] != 0L) {
            GL32.glClientWaitSync(syncFences[nextSlot], GL32.GL_SYNC_FLUSH_COMMANDS_BIT, 50_000_000L); // 50ms
            GL32.glDeleteSync(syncFences[nextSlot]);
            syncFences[nextSlot] = 0L;
        }
        slotOffsets[nextSlot].set(0L);
    }

    public synchronized void shutdown() {
        if (!initialized) return;
        initialized = false;

        for (int i = 0; i < NUM_SLOTS; i++) {
            if (syncFences[i] != 0L) {
                GL32.glDeleteSync(syncFences[i]);
                syncFences[i] = 0L;
            }
            taskQueues[i].clear();
        }

        if (pboHandle != 0) {
            GL15.glDeleteBuffers(pboHandle);
            pboHandle = 0;
        }
        mappedBuffer = null;
    }
}
