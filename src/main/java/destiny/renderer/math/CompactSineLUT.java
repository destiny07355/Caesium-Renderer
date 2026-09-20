package destiny.renderer.math;

/**
 * A replacement for the sine angle lookup table used in MathHelper, reducing table size
 * and improving CPU cache locality.
 *
 * <p>Reduces the trigonometric lookup table from 256 KB to just 16 KB (fits in CPU L1 data cache)
 * using symmetry identities:
 *   sin(-x) = -sin(x)
 *   sin(x) = sin(pi/2 - x)
 *
 * <p>Derived from Lithium / Sodium (jellysquid3 & coderbot16).
 */
public final class CompactSineLUT {
    private static final int[] SINE_TABLE_INT = new int[16384 + 1];
    private static final float SINE_TABLE_MIDPOINT;

    static {
        for (int i = 0; i < SINE_TABLE_INT.length; i++) {
            float val = (float) Math.sin((double) i * Math.PI * 2.0 / 65536.0);
            SINE_TABLE_INT[i] = Float.floatToRawIntBits(val);
        }
        SINE_TABLE_MIDPOINT = (float) Math.sin((double) 32768 * Math.PI * 2.0 / 65536.0);
    }

    private CompactSineLUT() {}

    public static void init() {}

    public static float sin(double d) {
        return lookup((int) (d * 10430.378350470453) & 0xFFFF);
    }

    public static float cos(double d) {
        return lookup((int) (d * 10430.378350470453 + 16384.0) & 0xFFFF);
    }

    public static float sin(float f) {
        return sin((double) f);
    }

    public static float cos(float f) {
        return cos((double) f);
    }

    private static float lookup(int index) {
        if (index == 32768) {
            return SINE_TABLE_MIDPOINT;
        }
        int neg = (index & 0x8000) << 16;
        int mask = (index << 17) >> 31;
        int pos = (0x8001 & mask) + (index ^ mask);
        pos &= 0x7fff;
        return Float.intBitsToFloat(SINE_TABLE_INT[pos] ^ neg);
    }
}
