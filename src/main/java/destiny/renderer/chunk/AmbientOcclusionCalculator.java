package destiny.renderer.chunk;

/**
 * Computes per-vertex ambient occlusion (AO) values during chunk meshing,
 * replicating vanilla Minecraft's smooth lighting algorithm exactly.
 *
 * <h2>Algorithm</h2>
 * Minecraft's AO calculates the occlusion at each of the 4 vertices of a face by
 * looking at the 3 neighbouring blocks that share that vertex corner:
 * <ul>
 *   <li>Side block 1 (adjacent along one tangent axis)</li>
 *   <li>Side block 2 (adjacent along the other tangent axis)</li>
 *   <li>Corner block (diagonal)</li>
 * </ul>
 * The AO value for the vertex is derived from a lookup into a 4-level step function
 * (0, 1, 2, or 3 occluders → AO multiplier 1.0, 0.8, 0.6, 0.4 respectively).
 * The result is baked directly into the vertex's AO bits during meshing, eliminating
 * any runtime AO cost on the GPU.
 *
 * <h2>Coordinate Convention</h2>
 * All lookups use padded-local coordinates from {@link ChunkSectionData} (range 0–17),
 * where the actual section occupies [1..16] and [0], [17] are neighbour padding.
 */
public final class AmbientOcclusionCalculator {

    /** AO multipliers indexed by occluder count (0–3). */
    private static final float[] AO_TABLE = {1.0f, 0.8f, 0.6f, 0.4f};

    /**
     * Encodes AO as a 7-bit integer in [0, 127] from a 0.0–1.0 float.
     */
    private static int encodeAO(float ao) {
        return (int) (ao * 127.0f + 0.5f) & 0x7F;
    }

    // -------------------------------------------------------------------------
    // Face AO calculation
    // -------------------------------------------------------------------------

    /**
     * Calculates the 4 per-vertex AO values (as packed 7-bit integers) for a given
     * face of a block at section-local coordinates (lx, ly, lz).
     *
     * @param data      the SoA chunk data (18×18×18 padded)
     * @param lx        block X in padded-local space [1..16]
     * @param ly        block Y in padded-local space [1..16]
     * @param lz        block Z in padded-local space [1..16]
     * @param faceIndex face normal index from {@link PackedVertexFormat}
     * @return array of 4 packed AO values [v0, v1, v2, v3] for the face's 4 vertices
     */
    private static final int[][][] FACE_NEIGHBOURS = {
        // +Y face (top)
        {{-1,0,0, 0,0,-1, -1,0,-1}, {1,0,0, 0,0,-1, 1,0,-1}, {1,0,0, 0,0,1, 1,0,1}, {-1,0,0, 0,0,1, -1,0,1}},
        // -Y face (bottom)
        {{-1,0,0, 0,0,-1, -1,0,-1}, {-1,0,0, 0,0,1, -1,0,1}, {1,0,0, 0,0,1, 1,0,1}, {1,0,0, 0,0,-1, 1,0,-1}},
        // +X face (east)
        {{0,-1,0, 0,0,-1, 0,-1,-1}, {0,-1,0, 0,0,1, 0,-1,1}, {0,1,0, 0,0,1, 0,1,1}, {0,1,0, 0,0,-1, 0,1,-1}},
        // -X face (west)
        {{0,-1,0, 0,0,-1, 0,-1,-1}, {0,1,0, 0,0,-1, 0,1,-1}, {0,1,0, 0,0,1, 0,1,1}, {0,-1,0, 0,0,1, 0,-1,1}},
        // +Z face (south)
        {{-1,0,0, 0,-1,0, -1,-1,0}, {-1,0,0, 0,1,0, -1,1,0}, {1,0,0, 0,1,0, 1,1,0}, {1,0,0, 0,-1,0, 1,-1,0}},
        // -Z face (north)
        {{-1,0,0, 0,-1,0, -1,-1,0}, {1,0,0, 0,-1,0, 1,-1,0}, {1,0,0, 0,1,0, 1,1,0}, {-1,0,0, 0,1,0, -1,1,0}}
    };

    private static final ThreadLocal<int[]> TL_RESULT = ThreadLocal.withInitial(() -> new int[4]);

    public static int[] calculateFaceAO(ChunkSectionData data, int lx, int ly, int lz, int faceIndex) {
        int dir = Math.min(5, Math.max(0, faceIndex));
        int fx = lx + (dir == 2 ? 1 : (dir == 3 ? -1 : 0));
        int fy = ly + (dir == 0 ? 1 : (dir == 1 ? -1 : 0));
        int fz = lz + (dir == 4 ? 1 : (dir == 5 ? -1 : 0));

        int[][] neighbours = FACE_NEIGHBOURS[dir];
        int[] result = TL_RESULT.get();
        for (int vi = 0; vi < 4; vi++) {
            int[] nb = neighbours[vi];
            boolean s1 = isOccluder(data, fx + nb[0], fy + nb[1], fz + nb[2]);
            boolean s2 = isOccluder(data, fx + nb[3], fy + nb[4], fz + nb[5]);
            boolean c  = isOccluder(data, fx + nb[6], fy + nb[7], fz + nb[8]);

            int count;
            if (s1 && s2) {
                count = 2;
            } else {
                count = (s1 ? 1 : 0) + (s2 ? 1 : 0) + (c ? 1 : 0);
            }
            result[vi] = encodeAO(AO_TABLE[count]);
        }
        return result;
    }

    public static int calculateFaceAOPacked(ChunkSectionData data, int lx, int ly, int lz, int faceIndex) {
        int dir = Math.min(5, Math.max(0, faceIndex));
        int fx = lx + (dir == 2 ? 1 : (dir == 3 ? -1 : 0));
        int fy = ly + (dir == 0 ? 1 : (dir == 1 ? -1 : 0));
        int fz = lz + (dir == 4 ? 1 : (dir == 5 ? -1 : 0));

        int[][] neighbours = FACE_NEIGHBOURS[dir];
        int packed = 0;
        for (int vi = 0; vi < 4; vi++) {
            int[] nb = neighbours[vi];
            boolean s1 = isOccluder(data, fx + nb[0], fy + nb[1], fz + nb[2]);
            boolean s2 = isOccluder(data, fx + nb[3], fy + nb[4], fz + nb[5]);
            boolean c  = isOccluder(data, fx + nb[6], fy + nb[7], fz + nb[8]);

            int count;
            if (s1 && s2) {
                count = 2;
            } else {
                count = (s1 ? 1 : 0) + (s2 ? 1 : 0) + (c ? 1 : 0);
            }
            packed |= (count & 3) << (vi * 2);
        }
        return packed;
    }

    public static void decodePackedAO(int packedAO, int[] outAO) {
        for (int i = 0; i < 4; i++) {
            int count = (packedAO >> (i * 2)) & 3;
            outAO[i] = encodeAO(AO_TABLE[count]);
        }
    }

    private static boolean isOccluder(ChunkSectionData data, int lx, int ly, int lz) {
        if (lx < 0 || ly < 0 || lz < 0 || lx >= ChunkSectionData.PADDED_DIM
            || ly >= ChunkSectionData.PADDED_DIM || lz >= ChunkSectionData.PADDED_DIM) {
            return false;
        }
        return data.isOpaque(lx, ly, lz);
    }
}
