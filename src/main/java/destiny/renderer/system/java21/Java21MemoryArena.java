package destiny.renderer.system.java21;

import destiny.renderer.system.common.CommonMemoryUtils;
import org.lwjgl.system.MemoryUtil;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Off-heap direct memory arena for Java 21 LTS runtimes.
 * Located in folder: {@code system/java21}
 * Uses LWJGL native memory allocation and direct ByteBuffers with zero GC overhead.
 */
public final class Java21MemoryArena {

    private final AtomicLong totalAllocatedBytes = new AtomicLong(0L);
    private final Map<String, Long> persistentPointers = new ConcurrentHashMap<>();
    private final Map<String, Long> persistentSizes = new ConcurrentHashMap<>();

    private long frameBaseAddress = 0L;
    private long frameOffset = 0L;
    private ByteBuffer frameBuffer = null;

    public synchronized void initialize() {
        if (frameBaseAddress == 0L) {
            frameBaseAddress = MemoryUtil.nmemAlloc(CommonMemoryUtils.FRAME_CAPACITY);
            frameOffset = 0L;
            if (frameBaseAddress != 0L) {
                totalAllocatedBytes.addAndGet(CommonMemoryUtils.FRAME_CAPACITY);
                frameBuffer = MemoryUtil.memByteBuffer(frameBaseAddress, (int) CommonMemoryUtils.FRAME_CAPACITY);
            }
        }
    }

    public synchronized void shutdown() {
        if (frameBaseAddress != 0L) {
            MemoryUtil.nmemFree(frameBaseAddress);
            frameBaseAddress = 0L;
            frameOffset = 0L;
            frameBuffer = null;
            totalAllocatedBytes.addAndGet(-CommonMemoryUtils.FRAME_CAPACITY);
        }
        for (Map.Entry<String, Long> entry : persistentPointers.entrySet()) {
            MemoryUtil.nmemFree(entry.getValue());
        }
        persistentPointers.clear();
        persistentSizes.clear();
        totalAllocatedBytes.set(0L);
    }

    public synchronized void beginFrame() {
        frameOffset = 0L;
    }

    public synchronized void endFrame() {
        frameOffset = 0L;
    }

    public synchronized ByteBuffer allocateFrame(long bytes) {
        if (frameBaseAddress == 0L) initialize();
        long alignedOffset = CommonMemoryUtils.align8(frameOffset);
        if (alignedOffset + bytes > CommonMemoryUtils.FRAME_CAPACITY) {
            return ByteBuffer.allocateDirect((int) bytes).order(CommonMemoryUtils.nativeOrder());
        }
        frameOffset = alignedOffset + bytes;
        long address = frameBaseAddress + alignedOffset;
        return MemoryUtil.memByteBuffer(address, (int) bytes).order(CommonMemoryUtils.nativeOrder());
    }

    public long allocatePersistent(String name, long bytes) {
        long existing = persistentPointers.getOrDefault(name, 0L);
        if (existing != 0L) return existing;

        long ptr = MemoryUtil.nmemAlloc(bytes);
        if (ptr != 0L) {
            persistentPointers.put(name, ptr);
            persistentSizes.put(name, bytes);
            totalAllocatedBytes.addAndGet(bytes);
        }
        return ptr;
    }

    public void freePersistent(String name) {
        Long ptr = persistentPointers.remove(name);
        Long size = persistentSizes.remove(name);
        if (ptr != null && ptr != 0L) {
            MemoryUtil.nmemFree(ptr);
            if (size != null) {
                totalAllocatedBytes.addAndGet(-size);
            }
        }
    }

    public long getTotalAllocatedBytes() {
        return totalAllocatedBytes.get();
    }
}
