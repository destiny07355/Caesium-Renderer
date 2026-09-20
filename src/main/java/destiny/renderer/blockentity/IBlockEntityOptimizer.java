package destiny.renderer.blockentity;

import caesium.engine.world.LayerMeshBuilder;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockRenderView;

/**
 * Strategy interface for optimizing specific block entity types in the OBE-style pipeline.
 */
public interface IBlockEntityOptimizer<T extends BlockEntity> {

    /** Returns true if this optimizer supports the given block entity. */
    boolean supports(BlockEntity be);

    /** Classifies whether the block entity at its current state is STATIC, DYNAMIC, or UNSUPPORTED. */
    BlockEntityClassification classify(T be, BlockState state);

    /**
     * Bakes static geometry for this block entity directly into the section layer mesh builders.
     *
     * @return true if static geometry was baked or accounted for, false to fall back to dynamic.
     */
    boolean bake(T be, BlockState state, BlockRenderView world, BlockPos pos,
                 int x, int y, int z, int packedLight, BlockRenderManager models,
                 LayerMeshBuilder[] builders, float[] posScratch, float[] uvScratch);
}
