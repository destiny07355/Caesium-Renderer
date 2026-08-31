package destiny.renderer.memory;

import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Thread-safe suballocator for a single large GPU buffer.
 *
 * <p>Hands out byte ranges within a fixed-size pool and reclaims them on free, coalescing
 * adjacent holes so the pool does not fragment into unusable slivers over time.
 *
 * <h2>Why this replaced the previous allocator</h2>
 * The original implementation was a bump pointer that reset to offset 0 once it ran past
 * the end of the buffer. Freed sections were never reclaimed, so the pointer marched to
 * the end during normal play and then wrapped — writing new geometry directly on top of
 * chunks that live draw commands still pointed at. The visible result was flickering and
 * corrupted terrain after a few minutes of movement.
 *
 * <h2>Design</h2>
 * A first-fit free list held in two sorted maps: one keyed by offset (for O(log n)
 * neighbour lookup during coalescing) and one bucketed by size (for fast fit search).
 * Allocations are 16-byte aligned so vertex and index strides stay naturally aligned.
 *
 * <p>All public methods are synchronized. Meshing runs on several background threads,
 * and allocation is far cheaper than the meshing work around it, so lock contention here
 * is not a measurable cost.
 */
public final class BufferPoolAllocator {

    /** Alignment for all allocations, in bytes. */
    private static final long ALIGNMENT = 16L;

    private final long capacity;
    private final String name;

    /** offset -> length of each free block. */
    private final NavigableMap<Long, Long> freeByOffset = new TreeMap<>();

    /** offset -> length of each live allocation. */
    private final NavigableMap<Long, Long> liveByOffset = new TreeMap<>();

    private long bytesInUse = 0L;
    private long peakBytesInUse = 0L;
    private long failedAllocations = 0L;

    public BufferPoolAllocator(String name, long capacity) {
        this.name = name;
        this.capacity = capacity;
        this.freeByOffset.put(0L, capacity);
    }

    private static long align(long v) {
        return (v + ALIGNMENT - 1) & ~(ALIGNMENT - 1);
    }

    /**
     * Allocates a range of at least {@code bytes}.
     *
     * @return the byte offset of the allocation, or {@code -1} if the pool cannot satisfy it
     */
    public synchronized long allocate(long bytes) {
        if (bytes <= 0) return -1L;
        long need = align(bytes);

        // First fit. Blocks are few in practice because we coalesce aggressively.
        for (Map.Entry<Long, Long> e : freeByOffset.entrySet()) {
            long offset = e.getKey();
            long length = e.getValue();
            if (length < need) continue;

            freeByOffset.remove(offset);
            long remainder = length - need;
            if (remainder > 0) {
                freeByOffset.put(offset + need, remainder);
            }
            liveByOffset.put(offset, need);
            bytesInUse += need;
            if (bytesInUse > peakBytesInUse) peakBytesInUse = bytesInUse;
            return offset;
        }

        failedAllocations++;
        return -1L;
    }

    /**
     * Releases a previously allocated range and coalesces it with any adjacent free blocks.
     * Silently ignores offsets that are not live, so a double free cannot corrupt the pool.
     */
    public synchronized void free(long offset) {
        Long length = liveByOffset.remove(offset);
        if (length == null) return;
        bytesInUse -= length;

        long start = offset;
        long end = offset + length;

        // Coalesce with the preceding free block when it ends exactly where we start.
        Map.Entry<Long, Long> prev = freeByOffset.floorEntry(start);
        if (prev != null && prev.getKey() + prev.getValue() == start) {
            start = prev.getKey();
            freeByOffset.remove(prev.getKey());
        }

        // Coalesce with the following free block when it starts exactly where we end.
        Map.Entry<Long, Long> next = freeByOffset.ceilingEntry(end);
        if (next != null && next.getKey() == end) {
            end = next.getKey() + next.getValue();
            freeByOffset.remove(next.getKey());
        }

        freeByOffset.put(start, end - start);
    }

    /** Drops every allocation and returns the pool to a single empty block. */
    public synchronized void reset() {
        freeByOffset.clear();
        liveByOffset.clear();
        freeByOffset.put(0L, capacity);
        bytesInUse = 0L;
    }

    // -------------------------------------------------------------------------
    // Diagnostics (surfaced in the debug overlay)
    // -------------------------------------------------------------------------

    public synchronized long bytesInUse()        { return bytesInUse; }
    public synchronized long capacity()          { return capacity; }
    public synchronized long peakBytesInUse()    { return peakBytesInUse; }
    public synchronized int  liveAllocations()   { return liveByOffset.size(); }
    public synchronized int  freeBlocks()        { return freeByOffset.size(); }
    public synchronized long failedAllocations() { return failedAllocations; }

    public synchronized float usageFraction() {
        return capacity == 0 ? 0f : (float) bytesInUse / (float) capacity;
    }

    /** @return the largest single allocation the pool could currently satisfy. */
    public synchronized long largestFreeBlock() {
        long max = 0L;
        for (long len : freeByOffset.values()) {
            if (len > max) max = len;
        }
        return max;
    }

    @Override
    public synchronized String toString() {
        return String.format("%s: %.1f/%.1f MB (%.0f%%), %d live, %d holes, %d failures",
            name,
            bytesInUse / 1048576.0,
            capacity / 1048576.0,
            usageFraction() * 100.0f,
            liveByOffset.size(),
            freeByOffset.size(),
            failedAllocations);
    }
}
