package destiny.renderer.chunk;

/**
 * Precomputed 16-bit packed light and ambient occlusion encoder.
 */
public final class PackedLightMap {

    private static final int[] LIGHT_LOOKUP = new int[256];

    static {
        for (int sky = 0; sky < 16; sky++) {
            for (int block = 0; block < 16; block++) {
                int index = (sky << 4) | block;
                // Standard OpenGL packed light coordinates: (sky << 16) | block
                LIGHT_LOOKUP[index] = (sky << 16) | block;
            }
        }
    }

    private PackedLightMap() {}

    /**
     * Packs block and sky light levels into an OpenGL lightmap coordinate integer.
     */
    public static int pack(int blockLight, int skyLight) {
        int b = Math.max(0, Math.min(15, blockLight));
        int s = Math.max(0, Math.min(15, skyLight));
        return LIGHT_LOOKUP[(s << 4) | b];
    }

    /**
     * Packs 4-bit block light, 4-bit sky light, and 8-bit AO factor into a single 32-bit word.
     */
    public static int packWithAo(int blockLight, int skyLight, int aoLevel) {
        int light = pack(blockLight, skyLight);
        int ao = Math.max(0, Math.min(255, aoLevel));
        return (ao << 24) | (light & 0x00FFFFFF);
    }
}
