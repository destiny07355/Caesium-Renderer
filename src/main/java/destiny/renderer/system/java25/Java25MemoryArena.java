package destiny.renderer.system.java25;

import destiny.renderer.system.common.CommonMemoryUtils;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Off-heap scoped FFM memory arena for Java 25 runtimes.
 * Located in folder: {@code system/java25}
 * Uses Java 25 Foreign Function & Memory (FFM) arenas with zero-cost scoped lifetimes.
 */
public final class Java25MemoryArena {

    private final AtomicLong totalAllocatedBytes = new AtomicLong(0L);
    private final Map<String, Arena> persistentArenas = new ConcurrentHashMap<>();
    private final Map<String, Long> persistentSizes = new ConcurrentHashMap<>();

    private Arena frameArena = null;
    private MemorySegment frameSegment = null;
    private long frameOffset = 0L;

    public synchronized void initialize() {
        if (frameArena == null) {
            frameArena = Arena.ofShared();
            frameSegment = frameArena.allocate(CommonMemoryUtils.FRAME_CAPACITY, 8);
            frameOffset = 0L;
            totalAllocatedBytes.addAndGet(CommonMemoryUtils.FRAME_CAPACITY);
        }
    }

    public synchronized void shutdown() {
        if (frameArena != null) {
            try {
                frameArena.close();
            } catch (Exception ignored) {}
            frameArena = null;
            frameSegment = null;
            frameOffset = 0L;
            totalAllocatedBytes.addAndGet(-CommonMemoryUtils.FRAME_CAPACITY);
        }
        for (Map.Entry<String, Arena> entry : persistentArenas.entrySet()) {
            try {
                entry.getValue().close();
            } catch (Exception ignored) {}
        }
        persistentArenas.clear();
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
        if (frameSegment == null) initialize();
        long alignedOffset = CommonMemoryUtils.align8(frameOffset);
        if (alignedOffset + bytes > CommonMemoryUtils.FRAME_CAPACITY) {
            return ByteBuffer.allocateDirect((int) bytes).order(CommonMemoryUtils.nativeOrder());
        }
        frameOffset = alignedOffset + bytes;
        MemorySegment slice = frameSegment.asSlice(alignedOffset, bytes);
        return slice.asByteBuffer().order(CommonMemoryUtils.nativeOrder());
    }

    public long allocatePersistent(String name, long bytes) {
        Arena existing = persistentArenas.get(name);
        if (existing != null) return 1L; // active handle

        Arena arena = Arena.ofShared();
        MemorySegment segment = arena.allocate(bytes, 8);
        persistentArenas.put(name, arena);
        persistentSizes.put(name, bytes);
        totalAllocatedBytes.addAndGet(bytes);
        return segment.address();
    }

    public void freePersistent(String name) {
        Arena arena = persistentArenas.remove(name);
        Long size = persistentSizes.remove(name);
        if (arena != null) {
            try {
                arena.close();
            } catch (Exception ignored) {}
            if (size != null) {
                totalAllocatedBytes.addAndGet(-size);
            }
        }
    }

    public long getTotalAllocatedBytes() {
        return totalAllocatedBytes.get();
    }
}
