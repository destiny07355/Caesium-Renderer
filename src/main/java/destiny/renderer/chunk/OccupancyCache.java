package destiny.renderer.chunk;

/**
 * High-performance bitmask occupancy cache for a 18x18x18 padded chunk section.
 *
 * <p>Constructs bitplanes in a single sequential pass across raw section memory,
 * allowing neighbor occlusion and face visibility tests to execute via single-cycle
 * bitwise shifts and masks without calling block state registries.
 */
public final class OccupancyCache {

    public static final int PADDED_DIM = ChunkSectionData.PADDED_DIM; // 18

    // 18 layers (Y), 18 rows (Z), 32-bit word (X bitfield from 0..17)
    private final int[][] opaqueBits      = new int[PADDED_DIM][PADDED_DIM];
    private final int[][] translucentBits = new int[PADDED_DIM][PADDED_DIM];
    private final int[][] emptyBits       = new int[PADDED_DIM][PADDED_DIM];

    /** Populates bitplanes from the SoA section data. Zero object allocations. */
    public void populate(ChunkSectionData data) {
        for (int y = 0; y < PADDED_DIM; y++) {
            for (int z = 0; z < PADDED_DIM; z++) {
                int opq = 0;
                int trn = 0;
                int emp = 0;
                for (int x = 0; x < PADDED_DIM; x++) {
                    int stateId = data.getStateId(x, y, z);
                    if (stateId == 0 || BlockStateLUT.isEmpty(stateId)) {
                        emp |= (1 << x);
                    } else if (BlockStateLUT.isOpaqueCube(stateId)) {
                        opq |= (1 << x);
                    } else if (BlockStateLUT.isTranslucent(stateId)) {
                        trn |= (1 << x);
                    } else {
                        // Cutout or non-full block (not full opaque, but not air)
                        opq |= (1 << x);
                    }
                }
                opaqueBits[y][z]      = opq;
                translucentBits[y][z] = trn;
                emptyBits[y][z]       = emp;
            }
        }
    }

    /** @return true if the block at padded coord (x, y, z) is a full opaque cube. */
    public boolean isOpaque(int x, int y, int z) {
        if (x < 0 || x >= PADDED_DIM || y < 0 || y >= PADDED_DIM || z < 0 || z >= PADDED_DIM) return false;
        return (opaqueBits[y][z] & (1 << x)) != 0;
    }

    /** @return true if the block at padded coord (x, y, z) is translucent. */
    public boolean isTranslucent(int x, int y, int z) {
        if (x < 0 || x >= PADDED_DIM || y < 0 || y >= PADDED_DIM || z < 0 || z >= PADDED_DIM) return false;
        return (translucentBits[y][z] & (1 << x)) != 0;
    }

    /** @return true if the block at padded coord (x, y, z) is empty / air. */
    public boolean isEmpty(int x, int y, int z) {
        if (x < 0 || x >= PADDED_DIM || y < 0 || y >= PADDED_DIM || z < 0 || z >= PADDED_DIM) return true;
        return (emptyBits[y][z] & (1 << x)) != 0;
    }

    /**
     * Fast face visibility test: returns true if a face of the block at (lx, ly, lz)
     * pointing towards normalIdx is exposed (not occluded by an opaque neighbor).
     */
    public boolean isFaceVisible(int lx, int ly, int lz, int normalIdx) {
        switch (normalIdx) {
            case PackedVertexFormat.NORMAL_POS_X: // East (+X)
                return !isOpaque(lx + 1, ly, lz);
            case PackedVertexFormat.NORMAL_NEG_X: // West (-X)
                return !isOpaque(lx - 1, ly, lz);
            case PackedVertexFormat.NORMAL_POS_Y: // Top (+Y)
                return !isOpaque(lx, ly + 1, lz);
            case PackedVertexFormat.NORMAL_NEG_Y: // Bottom (-Y)
                return !isOpaque(lx, ly - 1, lz);
            case PackedVertexFormat.NORMAL_POS_Z: // South (+Z)
                return !isOpaque(lx, ly, lz + 1);
            case PackedVertexFormat.NORMAL_NEG_Z: // North (-Z)
                return !isOpaque(lx, ly, lz - 1);
            default:
                return true;
        }
    }
}
