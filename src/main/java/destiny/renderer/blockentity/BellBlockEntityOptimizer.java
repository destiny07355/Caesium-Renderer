package destiny.renderer.blockentity;

import caesium.engine.world.LayerMeshBuilder;
import caesium.engine.world.RenderWorld;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BellBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.render.model.BlockStateModel;
import net.minecraft.client.texture.Sprite;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockRenderView;

/**
 * Optimizes bells into static terrain geometry when resting, dynamically rendering when ringing.
 */
public final class BellBlockEntityOptimizer implements IBlockEntityOptimizer<BellBlockEntity> {

    @Override
    public boolean supports(BlockEntity be) {
        return be instanceof BellBlockEntity;
    }

    @Override
    public BlockEntityClassification classify(BellBlockEntity be, BlockState state) {
        if (be.ringing || be.ringTicks > 0) {
            return BlockEntityClassification.DYNAMIC;
        }
        return BlockEntityClassification.STATIC;
    }

    @Override
    public boolean bake(BellBlockEntity be, BlockState state, BlockRenderView world, BlockPos pos,
                        int x, int y, int z, int packedLight, BlockRenderManager models,
                        LayerMeshBuilder[] builders, float[] posScratch, float[] uvScratch) {
        LayerMeshBuilder target = builders[RenderWorld.TerrainLayer.SOLID.ordinal()];
        if (target == null) return false;

        BlockStateModel model = models.getModel(state);
        Sprite sprite = model != null ? model.particleSprite() : null;

        // Static bell body
        BlockEntityMeshHelper.appendCuboid(
            target, x, y, z,
            0.25f, 0.25f, 0.25f, 0.75f, 0.75f, 0.75f,
            sprite, packedLight, 0xFFFFFFFF, posScratch, uvScratch);

        return true;
    }
}
