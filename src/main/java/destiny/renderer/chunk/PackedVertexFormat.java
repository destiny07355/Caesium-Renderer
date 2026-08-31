package destiny.renderer.chunk;

/**
 * Defines the 64-bit packed vertex format used by DestinyRenderer's terrain geometry pipeline.
 *
 * <p>Standard Minecraft vertices consume 32–36 bytes each. By aggressively bit-packing all
 * per-vertex attributes into a single {@code long} (8 bytes), this format achieves a 4.5×
 * reduction in VRAM footprint and a proportional increase in geometry throughput due to
 * improved GPU memory bandwidth utilization.
 *
 * <h2>Bit Layout (64 bits total = 8 bytes per vertex)</h2>
 * <pre>
 * ┌──────────────────────────────────────────────────────────────────────────┐
 * │ Bits 63–56 │ Bits 55–49 │ Bits 48–41 │ Bits 40–31 │ Bits 30–21 │ ...  │
 * │ Tint (8b)  │ AO (7b)    │ Light (8b) │ V UV (10b) │ U UV (10b) │ ...  │
 * │            │            │ sky+block  │            │            │      │
 * ├────────────┴────────────┴────────────┴────────────┴────────────┴──────┤
 * │ ... Bits 20–18: Normal (3b) │ Bits 17–12: Z (6b) │ Bits 11–6: Y (6b) │
 * │                             │                     │                   │
 * │ ... Bits 5–0: X (6b)        │                     │                   │
 * └─────────────────────────────┴─────────────────────┴───────────────────┘
 * </pre>
 *
 * <h2>Field Details</h2>
 * <ul>
 *   <li><b>X, Y, Z</b> — 6 bits each (0–63): position within the chunk section. 5 bits would
 *       suffice for 0–31 but 6 bits (0–63) accommodates sections with fractional-block
 *       geometry (e.g., tall grass models that extend slightly outside nominal bounds).</li>
 *   <li><b>Normal</b> — 3 bits: encodes one of 6 face directions as an index into a
 *       lookup table in the vertex shader.</li>
 *   <li><b>U, V</b> — 10 bits each (0–1023): UV coordinates quantized to 1/1023 precision,
 *       sufficient for a 512×512 atlas with negligible sub-texel error.</li>
 *   <li><b>Block Light</b> — 4 bits (0–15): packed Minecraft block light level.</li>
 *   <li><b>Sky Light</b> — 4 bits (0–15): packed Minecraft sky light level.</li>
 *   <li><b>AO</b> — 7 bits (0–127): ambient occlusion multiplier, mapped to [0.0, 1.0] in shader.</li>
 *   <li><b>Tint</b> — 8 bits (0–255): per-face tint index. 0 = no tint, 1 = grass tint,
 *       2 = foliage tint, 3–255 = mod-defined tints.</li>
 * </ul>
 *
 * <h2>Shader Decode</h2>
 * The GLSL vertex shader receives each vertex as a {@code uvec2} (two 32-bit words)
 * and reconstructs all attributes via bitwise masking and shifting. See {@code terrain.vert}.
 */
public final class PackedVertexFormat {

    // -------------------------------------------------------------------------
    // Bit positions and masks
    // -------------------------------------------------------------------------

    // Position (relative to chunk section origin, in blocks × 4 sub-block precision)
    public static final int  X_SHIFT    = 0;
    public static final long X_MASK     = 0x3FL;        // 6 bits

    public static final int  Y_SHIFT    = 6;
    public static final long Y_MASK     = 0x3FL << Y_SHIFT;

    public static final int  Z_SHIFT    = 12;
    public static final long Z_MASK     = 0x3FL << Z_SHIFT;

    // Face normal direction (0=+X, 1=-X, 2=+Y, 3=-Y, 4=+Z, 5=-Z)
    public static final int  NORMAL_SHIFT = 18;
    public static final long NORMAL_MASK  = 0x7L << NORMAL_SHIFT; // 3 bits

    // Texture coordinates (quantized 0–1023)
    public static final int  U_SHIFT    = 21;
    public static final long U_MASK     = 0x3FFL << U_SHIFT;  // 10 bits

    public static final int  V_SHIFT    = 31;
    public static final long V_MASK     = 0x3FFL << V_SHIFT;  // 10 bits

    // Lightmap (block light = bits 41–44, sky light = bits 45–48)
    public static final int  BLOCK_LIGHT_SHIFT = 41;
    public static final long BLOCK_LIGHT_MASK  = 0xFL << BLOCK_LIGHT_SHIFT; // 4 bits

    public static final int  SKY_LIGHT_SHIFT   = 45;
    public static final long SKY_LIGHT_MASK    = 0xFL << SKY_LIGHT_SHIFT;  // 4 bits

    // Ambient occlusion (0–127 → 0.0–1.0 in shader)
    public static final int  AO_SHIFT   = 49;
    public static final long AO_MASK    = 0x7FL << AO_SHIFT;  // 7 bits

    // Tint index (0 = no tint, 1 = grass, 2 = foliage, etc.)
    public static final int  TINT_SHIFT = 56;
    public static final long TINT_MASK  = 0xFFL << TINT_SHIFT; // 8 bits

    // -------------------------------------------------------------------------
    // Normal index constants
    // -------------------------------------------------------------------------
    public static final int NORMAL_POS_X = 0;
    public static final int NORMAL_NEG_X = 1;
    public static final int NORMAL_POS_Y = 2;
    public static final int NORMAL_NEG_Y = 3;
    public static final int NORMAL_POS_Z = 4;
    public static final int NORMAL_NEG_Z = 5;

    // -------------------------------------------------------------------------
    // Tint index constants
    // -------------------------------------------------------------------------
    public static final int TINT_NONE    = 0;
    public static final int TINT_GRASS   = 1;
    public static final int TINT_FOLIAGE = 2;
    public static final int TINT_WATER   = 3;

    private PackedVertexFormat() {}

    // -------------------------------------------------------------------------
    // Packing helpers
    // -------------------------------------------------------------------------

    /**
     * Packs all vertex attributes into a single 64-bit long.
     *
     * @param x          block-relative X (0–31 within section)
     * @param y          block-relative Y (0–31 within section)
     * @param z          block-relative Z (0–31 within section)
     * @param normalIdx  face normal index (0–5)
     * @param u          U texture coordinate (0.0–1.0)
     * @param v          V texture coordinate (0.0–1.0)
     * @param blockLight block light level (0–15)
     * @param skyLight   sky light level (0–15)
     * @param ao         ambient occlusion (0.0–1.0)
     * @param tintIdx    tint index (0–255)
     * @return packed 64-bit vertex
     */
    public static long pack(int x, int y, int z, int normalIdx,
                            float u, float v,
                            int blockLight, int skyLight,
                            float ao, int tintIdx) {
        long packed = 0L;
        packed |= ((long) (x & 0x3F)) << X_SHIFT;
        packed |= ((long) (y & 0x3F)) << Y_SHIFT;
        packed |= ((long) (z & 0x3F)) << Z_SHIFT;
        packed |= ((long) (normalIdx & 0x7)) << NORMAL_SHIFT;
        int uPacked = Math.min(1023, Math.max(0, (int)(u * 1023.0f + 0.5f)));
        int vPacked = Math.min(1023, Math.max(0, (int)(v * 1023.0f + 0.5f)));
        int aoPacked = Math.min(127, Math.max(0, (int)(ao * 127.0f + 0.5f)));
        packed |= ((long) uPacked) << U_SHIFT;
        packed |= ((long) vPacked) << V_SHIFT;
        packed |= ((long) (blockLight & 0xF)) << BLOCK_LIGHT_SHIFT;
        packed |= ((long) (skyLight & 0xF)) << SKY_LIGHT_SHIFT;
        packed |= ((long) aoPacked) << AO_SHIFT;
        packed |= ((long) (tintIdx & 0xFF)) << TINT_SHIFT;
        return packed;
    }

    // -------------------------------------------------------------------------
    // Unpacking helpers (primarily for debugging / CPU-side validation)
    // -------------------------------------------------------------------------

    public static int unpackX(long v)          { return (int)((v >> X_SHIFT) & 0x3F); }
    public static int unpackY(long v)          { return (int)((v >> Y_SHIFT) & 0x3F); }
    public static int unpackZ(long v)          { return (int)((v >> Z_SHIFT) & 0x3F); }
    public static int unpackNormal(long v)     { return (int)((v >> NORMAL_SHIFT) & 0x7); }
    public static float unpackU(long v)        { return ((v >> U_SHIFT) & 0x3FFL) / 1023.0f; }
    public static float unpackV(long v)        { return ((v >> V_SHIFT) & 0x3FFL) / 1023.0f; }
    public static int unpackBlockLight(long v) { return (int)((v >> BLOCK_LIGHT_SHIFT) & 0xF); }
    public static int unpackSkyLight(long v)   { return (int)((v >> SKY_LIGHT_SHIFT) & 0xF); }
    public static float unpackAO(long v)       { return ((v >> AO_SHIFT) & 0x7FL) / 127.0f; }
    public static int unpackTint(long v)       { return (int)((v >> TINT_SHIFT) & 0xFF); }

    /** Bytes per vertex in this format. */
    public static final int VERTEX_STRIDE = Long.BYTES; // 8 bytes
}
