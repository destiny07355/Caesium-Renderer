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
import java.util.concurrent.atomic.AtomicInteger;
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
    private static final int MAX_TASKS_PER_SLOT = 1024;

    private final long slotCapacity;
    private final long totalCapacity;
    private int pboHandle;
    private MemorySegment mappedBuffer;
    private Arena owningArena;
    private boolean persistentMapping;

    private int activeSlot = 0;
    private final AtomicLong[] slotOffsets = new AtomicLong[NUM_SLOTS];
    private final long[] syncFences = new long[NUM_SLOTS];
    private final AtomicInteger[] taskCounts = new AtomicInteger[NUM_SLOTS];
    private final long[][] taskStagingOffsets = new long[NUM_SLOTS][MAX_TASKS_PER_SLOT];
    private final long[][] taskTargetOffsets = new long[NUM_SLOTS][MAX_TASKS_PER_SLOT];
    private final long[][] taskByteSizes = new long[NUM_SLOTS][MAX_TASKS_PER_SLOT];
    private final int[][] taskTargetHandles = new int[NUM_SLOTS][MAX_TASKS_PER_SLOT];

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
            taskCounts[i] = new AtomicInteger(0);
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

            int mapFlags = GL44.GL_MAP_WRITE_BIT | GL44.GL_MAP_PERSISTENT_BIT | GL44.GL_MAP_COHERENT_BIT;
            int storageFlags = mapFlags | GL44.GL_DYNAMIC_STORAGE_BIT;
            GL44.glBufferStorage(GL31.GL_COPY_READ_BUFFER, totalCapacity, storageFlags);

            java.nio.ByteBuffer buf = GL30.glMapBufferRange(
                    GL31.GL_COPY_READ_BUFFER, 0, totalCapacity, mapFlags);
            owningArena = RendererArenaManager.getOrCreatePersistentArena("gpu_upload_ring");
            mappedBuffer = buf != null ? MemorySegment.ofBuffer(buf) : owningArena.allocate(totalCapacity);
            persistentMapping = buf != null;

            GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, 0);
            LOGGER.info("[Caesium] Persistently mapped GPU Upload Ring created (" + (totalCapacity / (1024 * 1024)) + " MB)");
        } else {
            // Fallback staging buffer
            pboHandle = GL15.glGenBuffers();
            GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, pboHandle);
            GL15.glBufferData(GL31.GL_COPY_READ_BUFFER, totalCapacity, GL15.GL_STREAM_DRAW);
            GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, 0);
            owningArena = RendererArenaManager.getOrCreatePersistentArena("gpu_upload_ring_fallback");
            mappedBuffer = owningArena.allocate(totalCapacity);
            persistentMapping = false;
            LOGGER.info("[Caesium] Standard GPU Upload Ring created (" + (totalCapacity / (1024 * 1024)) + " MB, fallback mode)");
        }

        initialized = true;
    }

    private final Object stageLock = new Object();

    /**
     * Stages raw vertex/index data into the active frame's staging slot from any worker thread.
     *
     * @return true if successfully staged, false if the slot is full this frame
     */
    public boolean stage(MemorySegment source, long byteSize, int targetBufferHandle, long targetOffset) {
        if (!initialized || mappedBuffer == null || byteSize <= 0) return false;

        synchronized (stageLock) {
            int slot = activeSlot;
            long alignedSize = (byteSize + 15L) & ~15L;
            long offsetInSlot = slotOffsets[slot].get();

            if (offsetInSlot + byteSize > slotCapacity) {
                return false; // Slot capacity exceeded, fallback to sync copy
            }

            int taskIdx = taskCounts[slot].get();
            if (taskIdx >= MAX_TASKS_PER_SLOT) {
                return false; // Task array capacity exceeded
            }

            long globalStagingOffset = (long) slot * slotCapacity + offsetInSlot;

            // Fast copy into mapped memory
            MemorySegment.copy(source, 0L, mappedBuffer, globalStagingOffset, byteSize);

            taskStagingOffsets[slot][taskIdx] = globalStagingOffset;
            taskTargetOffsets[slot][taskIdx] = targetOffset;
            taskByteSizes[slot][taskIdx] = byteSize;
            taskTargetHandles[slot][taskIdx] = targetBufferHandle;
            slotOffsets[slot].set(offsetInSlot + alignedSize);
            taskCounts[slot].set(taskIdx + 1);
            return true;
        }
    }

    /**
     * Flushes staged tasks for the current frame to their destination GPU buffers.
     * Must be called on the GL render thread.
     */
    public void flushFrameUploads(int maxUploads) {
        if (!initialized) return;

        int slot;
        int totalTasks;
        synchronized (stageLock) {
            slot = activeSlot;
            totalTasks = Math.min(taskCounts[slot].get(), MAX_TASKS_PER_SLOT);
            if (totalTasks == 0) {
                return; // No tasks staged this frame: zero GPU commands, zero fences, zero CPU stalls
            }

            // Attempt to advance activeSlot to an available slot BEFORE copying,
            // so workers staging concurrently never touch the slot being flushed.
            int chosenSlot = -1;
            for (int i = 1; i < NUM_SLOTS; i++) {
                int candidate = (slot + i) % NUM_SLOTS;
                if (syncFences[candidate] == 0L) {
                    chosenSlot = candidate;
                    break;
                }
                int status = GL32.glClientWaitSync(syncFences[candidate], GL32.GL_SYNC_FLUSH_COMMANDS_BIT, 0L);
                if (status == GL32.GL_ALREADY_SIGNALED || status == GL32.GL_CONDITION_SATISFIED) {
                    GL32.glDeleteSync(syncFences[candidate]);
                    syncFences[candidate] = 0L;
                    chosenSlot = candidate;
                    break;
                }
            }
            if (chosenSlot != -1) {
                activeSlot = chosenSlot;
                slotOffsets[activeSlot].set(0L);
                taskCounts[activeSlot].set(0);
            }
        }

        int count = 0;
        long copiedBytes = 0L;
        long uploadStart = System.nanoTime();
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, pboHandle);

        for (int i = 0; i < totalTasks; i++) {
            if (!persistentMapping) {
                GL15.glBufferSubData(GL31.GL_COPY_READ_BUFFER, taskStagingOffsets[slot][i],
                        mappedBuffer.asSlice(taskStagingOffsets[slot][i], taskByteSizes[slot][i]).asByteBuffer());
            }
            GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, taskTargetHandles[slot][i]);
            GL31.glCopyBufferSubData(
                GL31.GL_COPY_READ_BUFFER,
                GL31.GL_COPY_WRITE_BUFFER,
                taskStagingOffsets[slot][i],
                taskTargetOffsets[slot][i],
                taskByteSizes[slot][i]
            );
            count++;
            copiedBytes += taskByteSizes[slot][i];
            if (maxUploads > 0 && count >= maxUploads) break;
        }

        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, 0);

        int remaining = totalTasks - count;
        synchronized (stageLock) {
            int currentTotal = taskCounts[slot].get();
            int newlyAdded = Math.max(0, currentTotal - totalTasks);
            if (remaining > 0 || newlyAdded > 0) {
                if (count > 0 && remaining > 0) {
                    System.arraycopy(taskStagingOffsets[slot], count, taskStagingOffsets[slot], 0, remaining);
                    System.arraycopy(taskTargetOffsets[slot], count, taskTargetOffsets[slot], 0, remaining);
                    System.arraycopy(taskByteSizes[slot], count, taskByteSizes[slot], 0, remaining);
                    System.arraycopy(taskTargetHandles[slot], count, taskTargetHandles[slot], 0, remaining);
                }
                if (newlyAdded > 0 && count > 0) {
                    System.arraycopy(taskStagingOffsets[slot], totalTasks, taskStagingOffsets[slot], remaining, newlyAdded);
                    System.arraycopy(taskTargetOffsets[slot], totalTasks, taskTargetOffsets[slot], remaining, newlyAdded);
                    System.arraycopy(taskByteSizes[slot], totalTasks, taskByteSizes[slot], remaining, newlyAdded);
                    System.arraycopy(taskTargetHandles[slot], totalTasks, taskTargetHandles[slot], remaining, newlyAdded);
                }
            }
            taskCounts[slot].set(remaining + newlyAdded);
        }
        destiny.renderer.hud.CaesiumFrameProfiler.recordTerrainUpload(
                copiedBytes, System.nanoTime() - uploadStart);

        if (count == 0) return;

        // Place a fence on the completed slot
        if (syncFences[slot] != 0L) {
            GL32.glDeleteSync(syncFences[slot]);
        }
        syncFences[slot] = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
    }

    public synchronized void shutdown() {
        if (!initialized) return;
        initialized = false;

        for (int i = 0; i < NUM_SLOTS; i++) {
            if (syncFences[i] != 0L) {
                GL32.glDeleteSync(syncFences[i]);
                syncFences[i] = 0L;
            }
            taskCounts[i].set(0);
        }

        if (pboHandle != 0) {
            GL15.glDeleteBuffers(pboHandle);
            pboHandle = 0;
        }
        mappedBuffer = null;
        persistentMapping = false;
    }
}
