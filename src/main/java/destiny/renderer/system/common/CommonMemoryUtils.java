package destiny.renderer.system.common;

import java.nio.ByteOrder;

/**
 * Shared memory and buffer utilities common to both Java 21 and Java 25 subsystems.
 */
public final class CommonMemoryUtils {

    /** Standard frame bump allocator capacity: 32 MB. */
    public static final long FRAME_CAPACITY = 32L * 1024 * 1024;

    private CommonMemoryUtils() {}

    /** Aligns offset to 8-byte boundary (standard pointer/double alignment). */
    public static long align8(long offset) {
        return (offset + 7L) & ~7L;
    }

    /** Aligns offset to 16-byte boundary (SIMD / float4 alignment). */
    public static long align16(long offset) {
        return (offset + 15L) & ~15L;
    }

    /** Returns native endian byte order. */
    public static ByteOrder nativeOrder() {
        return ByteOrder.nativeOrder();
    }
}
