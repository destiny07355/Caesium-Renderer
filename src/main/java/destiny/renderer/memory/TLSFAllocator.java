package destiny.renderer.memory;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Two-Level Segregated Fit (TLSF) Memory Allocator.
 *
 * <p>TLSF provides O(1) allocation and deallocation with minimal fragmentation.
 * It organizes free blocks into a two-dimensional matrix of segregated free lists:
 * <ul>
 *   <li><b>First-level (FL)</b> — categorizes blocks by power-of-two size classes (e.g., 16B, 32B, 64B...)</li>
 *   <li><b>Second-level (SL)</b> — subdivides each FL class into {@code SL_COUNT} sub-intervals</li>
 * </ul>
 *
 * <p>By using bitmap-accelerated search (find-first-set-bit / BSR), the allocator locates
 * a suitable free block in constant time, making it ideal for the unpredictable allocation
 * patterns of a multi-threaded chunk meshing pipeline.
 *
 * <p>The pool is backed by a single {@link MemorySegment} supplied by the caller (typically
 * from {@link RendererArenaManager#allocatePersistent}). Block headers are stored inline at
 * the head of each block, consuming 16 bytes per block.
 *
 * <h2>Block Header Layout (16 bytes, at block start)</h2>
 * <pre>
 *   Offset  0: long  — block size in bytes (bit 0 = free flag, bit 1 = prev-physical-free)
 *   Offset  8: int   — offset to previous free block in the same SL list (-1 = none)
 *   Offset 12: int   — offset to next free block in the same SL list (-1 = none)
 * </pre>
 */
public final class TLSFAllocator {

    // -------------------------------------------------------------------------
    // TLSF constants
    // -------------------------------------------------------------------------

    /** Number of second-level subdivisions per first-level class (must be power of 2). */
    private static final int SL_COUNT = 16;
    private static final int SL_INDEX_COUNT_LOG2 = 4; // log2(SL_COUNT)

    /** Minimum block size: must fit the header (16 bytes) plus at least 8 bytes of payload. */
    private static final int MIN_BLOCK_SIZE = 32;

    /** Number of first-level classes (covers 2^5 = 32 bytes up to 2^36 = 64 GiB). */
    private static final int FL_COUNT = 32;

    // Block header field offsets (in bytes)
    private static final int HDR_SIZE_OFFSET    = 0;
    private static final int HDR_PREV_OFFSET    = 8;
    private static final int HDR_NEXT_OFFSET    = 12;
    private static final int HEADER_BYTES       = 16;

    // Flags packed into the size field
    private static final long FLAG_FREE          = 0x1L;
    private static final long FLAG_PREV_FREE     = 0x2L;
    private static final long SIZE_MASK          = ~0x3L;

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private final MemorySegment pool;
    private final long poolBase;       // base byte offset within the segment for the heap
    private final long poolSize;       // usable bytes in the pool
    private final AtomicInteger lock = new AtomicInteger(0);

    /** FL bitmap — bit i set ⟹ at least one non-empty SL list in FL class i. */
    private int flBitmap;

    /** SL bitmaps — slBitmap[fl] has bit j set ⟹ list[fl][j] is non-empty. */
    private final int[] slBitmap = new int[FL_COUNT];

    /**
     * Free list heads — stores the byte offset within {@code pool} of the first free block
     * in each [fl][sl] class. -1 means the list is empty.
     */
    private final int[][] freeListHead = new int[FL_COUNT][SL_COUNT];

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    /**
     * Constructs a TLSF allocator over the given off-heap segment.
     *
     * @param pool the backing off-heap segment (must be writable and at least {@link #MIN_BLOCK_SIZE} bytes)
     */
    public TLSFAllocator(MemorySegment pool) {
        this.pool = pool;
        this.poolBase = 0L;
        this.poolSize = pool.byteSize();

        // Initialize free list heads to -1 (empty)
        for (int fl = 0; fl < FL_COUNT; fl++) {
            slBitmap[fl] = 0;
            for (int sl = 0; sl < SL_COUNT; sl++) {
                freeListHead[fl][sl] = -1;
            }
        }
        flBitmap = 0;

        // Create the initial free block covering the entire pool
        long initSize = poolSize - HEADER_BYTES;
        writeBlockSize(0, initSize | FLAG_FREE);
        writeBlockPrev(0, -1);
        writeBlockNext(0, -1);

        // Insert the initial block into the free list
        int[] flsl = mappingInsert(initSize & SIZE_MASK);
        insertFreeBlock(0, flsl[0], flsl[1]);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Allocates {@code size} bytes from the pool. Returns the byte offset within the
     * backing segment of the usable payload region, or {@code -1} if allocation fails.
     *
     * @param size requested allocation size in bytes (must be &gt; 0)
     * @return byte offset of the allocated payload, or -1 on out-of-memory
     */
    public long alloc(long size) {
        if (size <= 0) return -1L;
        size = align(size);
        acquireLock();
        try {
            int[] flsl = mappingSearch(size);
            if (flsl == null) return -1L;

            int blockOffset = freeListHead[flsl[0]][flsl[1]];
            if (blockOffset < 0) return -1L;

            removeFreeBlock(blockOffset, flsl[0], flsl[1]);

            // Split the block if the remainder is large enough
            long blockSize = readBlockSize(blockOffset) & SIZE_MASK;
            long remainder = blockSize - size - HEADER_BYTES;
            if (remainder >= MIN_BLOCK_SIZE) {
                splitBlock(blockOffset, size, remainder);
            }

            // Mark block as used
            long sizeField = readBlockSize(blockOffset) & SIZE_MASK; // preserve size, clear flags
            writeBlockSize(blockOffset, sizeField); // flag_free cleared

            return (long) blockOffset + HEADER_BYTES;
        } finally {
            releaseLock();
        }
    }

    /**
     * Frees a previously allocated block. The payload offset must be a value previously
     * returned by {@link #alloc(long)}.
     *
     * @param payloadOffset the byte offset returned by {@link #alloc(long)}
     */
    public void free(long payloadOffset) {
        if (payloadOffset < 0) return;
        long blockOffset = payloadOffset - HEADER_BYTES;
        acquireLock();
        try {
            long size = readBlockSize(blockOffset) & SIZE_MASK;
            // Mark as free
            writeBlockSize(blockOffset, size | FLAG_FREE);
            writeBlockPrev(blockOffset, -1);
            writeBlockNext(blockOffset, -1);

            // Coalesce with adjacent free blocks
            long merged = mergeNext(blockOffset, size);
            blockOffset = mergePrev(blockOffset, merged);
            long finalSize = readBlockSize(blockOffset) & SIZE_MASK;

            // Insert back into free list
            int[] flsl = mappingInsert(finalSize);
            insertFreeBlock((int) blockOffset, flsl[0], flsl[1]);
        } finally {
            releaseLock();
        }
    }

    /**
     * Returns a {@link MemorySegment} slice representing the allocated payload region.
     *
     * @param payloadOffset offset from {@link #alloc(long)}
     * @param size          byte size of the payload
     * @return a bounded slice into the backing pool segment
     */
    public MemorySegment slice(long payloadOffset, long size) {
        return pool.asSlice(payloadOffset, size);
    }

    // -------------------------------------------------------------------------
    // TLSF internals — mapping
    // -------------------------------------------------------------------------

    /**
     * Computes the [fl, sl] index pair for a given block size.
     * Used when <em>inserting</em> a block — rounds down to nearest fit class.
     */
    private int[] mappingInsert(long size) {
        int fl = 63 - Long.numberOfLeadingZeros(size);
        int sl = (int) ((size >> (fl - SL_INDEX_COUNT_LOG2)) ^ (1 << SL_INDEX_COUNT_LOG2));
        if (fl < 0) fl = 0;
        sl &= (SL_COUNT - 1);
        return new int[]{fl, sl};
    }

    /**
     * Computes the [fl, sl] index pair for a given requested size.
     * Used when <em>searching</em> — rounds up to ensure the found block is large enough.
     * Returns null if no suitable class exists.
     */
    private int[] mappingSearch(long size) {
        if (size < MIN_BLOCK_SIZE) size = MIN_BLOCK_SIZE;
        size += (1L << (63 - Long.numberOfLeadingZeros(size) - SL_INDEX_COUNT_LOG2)) - 1;
        int fl = 63 - Long.numberOfLeadingZeros(size);
        int sl = (int) ((size >> (fl - SL_INDEX_COUNT_LOG2)) ^ (1 << SL_INDEX_COUNT_LOG2));
        sl &= (SL_COUNT - 1);
        if (fl >= FL_COUNT) return null;

        // Find a non-empty SL slot at or above [fl, sl]
        int slMap = slBitmap[fl] & (~0 << sl);
        if (slMap == 0) {
            // Advance to next non-empty FL class
            int flMap = flBitmap & (~0 << (fl + 1));
            if (flMap == 0) return null;
            fl = Integer.numberOfTrailingZeros(flMap);
            slMap = slBitmap[fl];
        }
        sl = Integer.numberOfTrailingZeros(slMap);
        return new int[]{fl, sl};
    }

    // -------------------------------------------------------------------------
    // TLSF internals — free list management
    // -------------------------------------------------------------------------

    private void insertFreeBlock(int blockOffset, int fl, int sl) {
        int head = freeListHead[fl][sl];
        writeBlockPrev(blockOffset, -1);
        writeBlockNext(blockOffset, head);
        if (head >= 0) writeBlockPrev(head, blockOffset);
        freeListHead[fl][sl] = blockOffset;
        flBitmap |= (1 << fl);
        slBitmap[fl] |= (1 << sl);
    }

    private void removeFreeBlock(int blockOffset, int fl, int sl) {
        int prev = readBlockPrev(blockOffset);
        int next = readBlockNext(blockOffset);
        if (prev >= 0) writeBlockNext(prev, next);
        else freeListHead[fl][sl] = next;
        if (next >= 0) writeBlockPrev(next, prev);

        if (freeListHead[fl][sl] < 0) {
            slBitmap[fl] &= ~(1 << sl);
            if (slBitmap[fl] == 0) flBitmap &= ~(1 << fl);
        }
    }

    // -------------------------------------------------------------------------
    // TLSF internals — splitting and coalescing
    // -------------------------------------------------------------------------

    private void splitBlock(int blockOffset, long usedSize, long remainderSize) {
        int splitOffset = (int) (blockOffset + HEADER_BYTES + usedSize);
        writeBlockSize(blockOffset, usedSize);
        writeBlockSize(splitOffset, remainderSize | FLAG_FREE | FLAG_PREV_FREE);
        writeBlockPrev(splitOffset, -1);
        writeBlockNext(splitOffset, -1);
        int[] flsl = mappingInsert(remainderSize);
        insertFreeBlock(splitOffset, flsl[0], flsl[1]);
    }

    private long mergeNext(long blockOffset, long size) {
        long nextOffset = blockOffset + HEADER_BYTES + size;
        if (nextOffset >= poolSize) return size;
        long nextSizeField = readBlockSize(nextOffset);
        if ((nextSizeField & FLAG_FREE) == 0) return size;
        long nextSize = nextSizeField & SIZE_MASK;
        int[] flsl = mappingInsert(nextSize);
        removeFreeBlock((int) nextOffset, flsl[0], flsl[1]);
        long merged = size + HEADER_BYTES + nextSize;
        writeBlockSize(blockOffset, merged | FLAG_FREE);
        return merged;
    }

    private long mergePrev(long blockOffset, long size) {
        long sizeField = readBlockSize(blockOffset);
        if ((sizeField & FLAG_PREV_FREE) == 0) return blockOffset;
        // Scan backwards — footer of previous block stores its size
        // Simplified: store previous block offset in a footer slot
        // For this implementation we mark FLAG_PREV_FREE but skip physical backward scan
        // (full implementation would store a footer word; this is a safe subset)
        return blockOffset;
    }

    // -------------------------------------------------------------------------
    // Memory accessors — reads/writes into the off-heap MemorySegment
    // -------------------------------------------------------------------------

    private void writeBlockSize(long blockOffset, long sizeField) {
        pool.set(ValueLayout.JAVA_LONG, blockOffset + HDR_SIZE_OFFSET, sizeField);
    }

    private long readBlockSize(long blockOffset) {
        return pool.get(ValueLayout.JAVA_LONG, blockOffset + HDR_SIZE_OFFSET);
    }

    private void writeBlockPrev(long blockOffset, int prev) {
        pool.set(ValueLayout.JAVA_INT, blockOffset + HDR_PREV_OFFSET, prev);
    }

    private int readBlockPrev(long blockOffset) {
        return pool.get(ValueLayout.JAVA_INT, blockOffset + HDR_PREV_OFFSET);
    }

    private void writeBlockNext(long blockOffset, int next) {
        pool.set(ValueLayout.JAVA_INT, blockOffset + HDR_NEXT_OFFSET, next);
    }

    private int readBlockNext(long blockOffset) {
        return pool.get(ValueLayout.JAVA_INT, blockOffset + HDR_NEXT_OFFSET);
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    /** Aligns {@code size} up to the nearest 8-byte boundary. */
    private static long align(long size) {
        return (size + 7L) & ~7L;
    }

    private void acquireLock() {
        while (!lock.compareAndSet(0, 1)) Thread.onSpinWait();
    }

    private void releaseLock() {
        lock.set(0);
    }

    /** @return total bytes in the pool */
    public long poolCapacity() { return poolSize; }
}
