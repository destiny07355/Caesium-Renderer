package destiny.renderer.chunk;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockRenderView;

/**
 * Struct-of-Arrays (SoA) storage for a single 16×16×16 chunk section's block data,
 * padded to 18×18×18 (one block of context on each face) to avoid cross-chunk boundary
 * checks during face culling.
 *
 * <h2>Why SoA?</h2>
 * Traditional Object-of-Arrays layouts (e.g., one {@code BlockState} object per block)
 * cause severe CPU cache misses during meshing. When the mesher accesses block [x][y][z],
 * it needs adjacent block states for face culling. In OOP layouts, those neighbours may
 * be scattered across the heap. With SoA + Morton encoding, spatially adjacent blocks
 * are adjacent in the flat arrays, maximizing L1/L2 cache hit rates.
 *
 * <h2>Layout</h2>
 * All arrays are indexed by the Morton code of (x, y, z) in [0, 17] space
 * (where [1..16] is the actual section, [0] and [17] are the neighbour padding).
 * Total entries = 32768 (conservative Morton index ceiling for 18^3 = 5832 blocks).
 */
public final class ChunkSectionData {

    /** Padded dimension: 16 section blocks + 1 padding on each side = 18. */
    public static final int PADDED_DIM = 18;

    /**
     * Block state ID array, indexed by Morton code.
     * Value is the raw block state ID from the biome/block palette.
     * 0 = air (no geometry emitted).
     */
    public final int[] blockStateIds;

    /**
     * Opacity flags array. Bit 0 = opaque face (blocks face culling on that side).
     * Stored separately from blockStateIds to allow SIMD-width comparisons during
     * face culling without touching the full 4-byte state ID.
     */
    public final byte[] opacityFlags;

    /**
     * Tint index per block. 0 = no tint, 1 = grass, 2 = foliage, 3 = water.
     * Stored separately to allow quick tint-aware batching decisions.
     */
    public final byte[] tintIndices;

    /**
     * Light level array. High nibble = sky light (0–15), low nibble = block light (0–15).
     * Packed into a single byte per block to minimize memory footprint.
     */
    public final byte[] lightLevels;

    /** The origin of this section in world-space block coordinates. */
    public int originX, originY, originZ;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public ChunkSectionData() {
        int count = MortonEncoder.paddedSectionIndexCount();
        this.blockStateIds = new int[count];
        this.opacityFlags  = new byte[count];
        this.tintIndices   = new byte[count];
        this.lightLevels   = new byte[count];
    }

    // -------------------------------------------------------------------------
    // Population
    // -------------------------------------------------------------------------

    /**
     * Populates this SoA structure from a Minecraft {@link BlockRenderView} for the
     * given section at chunk position (cx, cy, cz) in section coordinates.
     *
     * <p>This reads the 18×18×18 padded region from the world (including one layer of
     * neighbours on each face) to allow correct face culling without out-of-bounds checks
     * during meshing.
     *
     * @param world  the block render view (typically a {@code ChunkRendererRegion})
     * @param startX world-space X of the section's (-1,-1,-1) padded corner
     * @param startY world-space Y
     * @param startZ world-space Z
     */
    public void populate(BlockRenderView world, int startX, int startY, int startZ) {
        this.originX = startX + 1; // actual section corner = padded start + 1
        this.originY = startY + 1;
        this.originZ = startZ + 1;

        BlockPos.Mutable mutable = new BlockPos.Mutable();

        for (int lx = 0; lx < PADDED_DIM; lx++) {
            for (int ly = 0; ly < PADDED_DIM; ly++) {
                for (int lz = 0; lz < PADDED_DIM; lz++) {
                    mutable.set(startX + lx, startY + ly, startZ + lz);
                    int morton = MortonEncoder.encode(lx, ly, lz);

                    BlockState state = world.getBlockState(mutable);
                    int stateId = net.minecraft.block.Block.getRawIdFromState(state);
                    blockStateIds[morton] = stateId;

                    // Both of these come from precomputed tables now. They used to do a
                    // registry lookup plus a chain of String.contains per block — 5832
                    // times per section, in the hottest loop in the engine.
                    opacityFlags[morton] = BlockStateLUT.isOpaqueCube(stateId) ? (byte) 1 : (byte) 0;
                    tintIndices[morton]  = BlockStateLUT.tintOf(stateId);

                    // Light
                    int blockLight = world.getLuminance(mutable);
                    int skyLight   = world.getLightLevel(
                        net.minecraft.world.LightType.SKY, mutable);
                    lightLevels[morton] = (byte) ((skyLight << 4) | (blockLight & 0xF));
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Accessors (Morton-indexed)
    // -------------------------------------------------------------------------

    /**
     * Returns the block state ID at padded-local coordinates (lx, ly, lz) ∈ [0, 17].
     */
    public int getStateId(int lx, int ly, int lz) {
        return blockStateIds[MortonEncoder.encode(lx, ly, lz)];
    }

    /**
     * Returns true if the block at padded-local coordinates is opaque.
     * An opaque block culls the adjacent face of its neighbour.
     */
    public boolean isOpaque(int lx, int ly, int lz) {
        return opacityFlags[MortonEncoder.encode(lx, ly, lz)] != 0;
    }

    /**
     * Returns the packed light byte at padded-local coordinates.
     * High nibble = sky light, low nibble = block light.
     */
    public byte getLightPacked(int lx, int ly, int lz) {
        return lightLevels[MortonEncoder.encode(lx, ly, lz)];
    }

    // -------------------------------------------------------------------------
    // Tint classification
    // -------------------------------------------------------------------------

}
