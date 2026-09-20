package destiny.renderer.blockentity;

import caesium.engine.world.LayerMeshBuilder;
import caesium.engine.world.RenderWorld;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.HangingSignBlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.render.model.BlockStateModel;
import net.minecraft.client.texture.Sprite;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockRenderView;

/**
 * Optimizes signs and hanging signs into static terrain geometry when they are blank or non-animating.
 */
public final class SignBlockEntityOptimizer implements IBlockEntityOptimizer<SignBlockEntity> {

    @Override
    public boolean supports(BlockEntity be) {
        return be instanceof SignBlockEntity;
    }

    @Override
    public BlockEntityClassification classify(SignBlockEntity be, BlockState state) {
        if (be.getEditor() != null) {
            return BlockEntityClassification.DYNAMIC;
        }
        // If the sign has visible text, allow vanilla's BlockEntityRenderer to draw the text cleanly
        if (be.getFrontText().hasText(null) || be.getBackText().hasText(null)) {
            return BlockEntityClassification.DYNAMIC;
        }
        return BlockEntityClassification.STATIC;
    }

    @Override
    public boolean bake(SignBlockEntity be, BlockState state, BlockRenderView world, BlockPos pos,
                        int x, int y, int z, int packedLight, BlockRenderManager models,
                        LayerMeshBuilder[] builders, float[] posScratch, float[] uvScratch) {
        LayerMeshBuilder target = builders[RenderWorld.TerrainLayer.SOLID.ordinal()];
        if (target == null) return false;

        BlockStateModel model = models.getModel(state);
        Sprite sprite = model != null ? model.particleSprite() : null;

        if (be instanceof HangingSignBlockEntity) {
            // Hanging sign board
            BlockEntityMeshHelper.appendCuboid(
                target, x, y, z,
                0.0625f, 0.0f, 0.4375f, 0.9375f, 0.625f, 0.5625f,
                sprite, packedLight, 0xFFFFFFFF, posScratch, uvScratch);
        } else {
            // Standing sign post
            BlockEntityMeshHelper.appendCuboid(
                target, x, y, z,
                0.4375f, 0.0f, 0.4375f, 0.5625f, 0.5625f, 0.5625f,
                sprite, packedLight, 0xFFFFFFFF, posScratch, uvScratch);
            // Sign board
            BlockEntityMeshHelper.appendCuboid(
                target, x, y, z,
                0.0625f, 0.5625f, 0.4375f, 0.9375f, 1.0f, 0.5625f,
                sprite, packedLight, 0xFFFFFFFF, posScratch, uvScratch);
        }

        return true;
    }
}
