package destiny.renderer.chunk;

/**
 * Z-order curve (Morton code) encoder for 3D block coordinates.
 *
 * <p>Interleaves the bits of (x, y, z) coordinates into a single integer index.
 * When block states are stored in Morton order, spatially adjacent blocks are also
 * adjacent in memory — dramatically improving CPU L1/L2 cache hit rates during
 * chunk meshing, since face visibility checks read neighbouring block states.
 *
 * <h2>Encoding layout (per axis, 5 bits = 0..31)</h2>
 * <pre>
 *  Bit 0  of result ← bit 0 of x
 *  Bit 1  of result ← bit 0 of y
 *  Bit 2  of result ← bit 0 of z
 *  Bit 3  of result ← bit 1 of x
 *  Bit 4  of result ← bit 1 of y
 *  Bit 5  of result ← bit 1 of z
 *  ... and so on.
 * </pre>
 *
 * <p>With 5-bit axes the maximum Morton index is 2^15 = 32768, which exactly covers
 * an 18×18×18 padded chunk section (16 + 1 padding on each side = 18^3 = 5832 blocks).
 */
public final class MortonEncoder {

    // Pre-computed spread tables for 5-bit values: spread(v) interleaves zeros between bits.
    // spread[v] = bit 0 at pos 0, bit 1 at pos 3, bit 2 at pos 6, etc.
    private static final int[] SPREAD = new int[32];

    static {
        for (int v = 0; v < 32; v++) {
            int s = 0;
            for (int i = 0; i < 5; i++) {
                if ((v & (1 << i)) != 0) {
                    s |= (1 << (i * 3));
                }
            }
            SPREAD[v] = s;
        }
    }

    private MortonEncoder() {}

    /**
     * Encodes (x, y, z) ∈ [0, 31] into a Morton index ∈ [0, 32767].
     *
     * @param x X coordinate within the padded section (0–17 for normal use)
     * @param y Y coordinate within the padded section
     * @param z Z coordinate within the padded section
     * @return 15-bit Morton index
     */
    public static int encode(int x, int y, int z) {
        return SPREAD[x & 0x1F] | (SPREAD[y & 0x1F] << 1) | (SPREAD[z & 0x1F] << 2);
    }

    /**
     * Decodes a Morton index back to its (x, y, z) components.
     * Packed as: {@code result[0]=x, result[1]=y, result[2]=z}.
     *
     * @param morton a Morton index previously produced by {@link #encode}
     * @return int array of length 3 containing [x, y, z]
     */
    public static int[] decode(int morton) {
        return new int[]{
            compact(morton),
            compact(morton >> 1),
            compact(morton >> 2)
        };
    }

    /** Compacts every third bit from a Morton code back into a 5-bit value. */
    private static int compact(int v) {
        v &= 0x00009249;                   // keep only every 3rd bit
        v = (v | (v >> 2))  & 0x030C30C3;
        v = (v | (v >> 4))  & 0x0300F00F;
        v = (v | (v >> 8))  & 0x030000FF;
        v = (v | (v >> 16)) & 0x000003FF;
        return v & 0x1F;
    }

    /**
     * Returns the total number of valid Morton indices for an 18×18×18 padded chunk section.
     * Array sized to this value covers the full padded section without bounds checking.
     */
    public static int paddedSectionIndexCount() {
        return 32768; // 2^15 — covers all 18^3 = 5832 actual entries with headroom
    }
}
