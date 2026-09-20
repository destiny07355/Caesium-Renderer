package destiny.renderer.blockentity;

import caesium.engine.world.LayerMeshBuilder;
import destiny.renderer.config.RendererConfig;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockRenderView;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Central registry and runtime coordinator for OBE-style static block-entity optimizations.
 *
 * <p>Coordinates classification, terrain mesh baking, and duplicate rendering suppression.
 * Tracks statically baked block entities in an unboxed, primitive fastutil map to provide
 * zero-allocation O(1) queries during per-frame entity culling.
 */
public final class BlockEntityOptimizationRegistry {

    private static final List<IBlockEntityOptimizer<?>> OPTIMIZERS = new CopyOnWriteArrayList<>();

    // Map of BlockPos.asLong() -> sectionKey
    private static final Long2LongOpenHashMap STATIC_POS_TO_SECTION = new Long2LongOpenHashMap();
    private static final Object STATE_LOCK = new Object();

    static {
        register(new ChestBlockEntityOptimizer());
        register(new SignBlockEntityOptimizer());
        register(new BedBlockEntityOptimizer());
        register(new BellBlockEntityOptimizer());
        register(new CampfireBlockEntityOptimizer());
    }

    private BlockEntityOptimizationRegistry() {}

    /** Registers a block-entity optimizer strategy. */
    public static void register(IBlockEntityOptimizer<?> optimizer) {
        if (optimizer != null && !OPTIMIZERS.contains(optimizer)) {
            OPTIMIZERS.add(optimizer);
        }
    }

    /** Returns whether OBE-style block-entity optimizations are globally enabled and owned by Caesium. */
    public static boolean isOptimizationEnabled() {
        return RendererConfig.get().optimizeBlockEntities
            && destiny.renderer.compat.WorkAllotment.isOwnedByUs(destiny.renderer.compat.Capability.BLOCK_ENTITY_OPTIMIZATION);
    }

    /**
     * Classifies a block entity's current visual state.
     *
     * @param be the block entity
     * @param state the corresponding block state
     * @return {@link BlockEntityClassification#STATIC}, {@link BlockEntityClassification#DYNAMIC},
     *         or {@link BlockEntityClassification#UNSUPPORTED}
     */
    @SuppressWarnings("unchecked")
    public static BlockEntityClassification classify(BlockEntity be, BlockState state) {
        if (!isOptimizationEnabled() || be == null || state == null) {
            return BlockEntityClassification.UNSUPPORTED;
        }

        for (IBlockEntityOptimizer<?> opt : OPTIMIZERS) {
            if (opt.supports(be)) {
                return ((IBlockEntityOptimizer<BlockEntity>) opt).classify(be, state);
            }
        }
        return BlockEntityClassification.UNSUPPORTED;
    }

    /**
     * Attempts to bake static geometry for a supported block entity directly into the terrain mesh builders.
     *
     * @return true if static geometry was baked or accounted for; false to fall back to dynamic rendering.
     */
    @SuppressWarnings("unchecked")
    public static boolean bake(BlockEntity be, BlockState state, BlockRenderView world, BlockPos pos,
                               int x, int y, int z, int packedLight, BlockRenderManager models,
                               LayerMeshBuilder[] builders, float[] posScratch, float[] uvScratch) {
        if (!isOptimizationEnabled() || be == null || state == null) {
            return false;
        }

        for (IBlockEntityOptimizer<?> opt : OPTIMIZERS) {
            if (opt.supports(be)) {
                return ((IBlockEntityOptimizer<BlockEntity>) opt).bake(
                    be, state, world, pos, x, y, z, packedLight, models, builders, posScratch, uvScratch);
            }
        }
        return false;
    }

    /**
     * Checks whether the block entity at the given packed block position has been statically baked into terrain.
     * Called on the render thread to suppress duplicate dynamic rendering.
     */
    public static boolean isStaticallyBaked(long posKey) {
        if (!isOptimizationEnabled()) return false;
        synchronized (STATE_LOCK) {
            return STATIC_POS_TO_SECTION.containsKey(posKey);
        }
    }

    /** Records that a block entity at {@code posKey} has been baked into section {@code sectionKey}. */
    public static void recordStatic(long posKey, long sectionKey) {
        synchronized (STATE_LOCK) {
            STATIC_POS_TO_SECTION.put(posKey, sectionKey);
        }
    }

    /** Invalidates a single block entity (e.g. when a chest opens). */
    public static void invalidate(long posKey) {
        synchronized (STATE_LOCK) {
            STATIC_POS_TO_SECTION.remove(posKey);
        }
    }

    /** Invalidates all block entities belonging to a rebuilt or unloaded section. */
    public static void invalidateSection(long sectionKey) {
        synchronized (STATE_LOCK) {
            var iter = STATIC_POS_TO_SECTION.long2LongEntrySet().fastIterator();
            while (iter.hasNext()) {
                var entry = iter.next();
                if (entry.getLongValue() == sectionKey) {
                    iter.remove();
                }
            }
        }
    }

    /** Invalidates all block entities belonging to an unloaded chunk. */
    public static void invalidateChunk(long chunkX, long chunkZ) {
        synchronized (STATE_LOCK) {
            var iter = STATIC_POS_TO_SECTION.long2LongEntrySet().fastIterator();
            while (iter.hasNext()) {
                var entry = iter.next();
                long sectionKey = entry.getLongValue();
                int sx = (int) (sectionKey & 0x1FFFFF);
                if ((sx & 0x100000) != 0) sx |= 0xFFE00000;
                int sz = (int) ((sectionKey >>> 42) & 0x1FFFFF);
                if ((sz & 0x100000) != 0) sz |= 0xFFE00000;
                if (sx == (int) chunkX && sz == (int) chunkZ) {
                    iter.remove();
                }
            }
        }
    }

    /** Clears all static tracking (e.g. on world unload or dimension switch). */
    public static void clear() {
        synchronized (STATE_LOCK) {
            STATIC_POS_TO_SECTION.clear();
        }
    }

    /** Returns the number of currently tracked static block entities (for testing and telemetry). */
    public static int staticCount() {
        synchronized (STATE_LOCK) {
            return STATIC_POS_TO_SECTION.size();
        }
    }
}
