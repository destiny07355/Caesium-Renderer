package destiny.renderer.blockentity;

import caesium.engine.world.LayerMeshBuilder;
import caesium.engine.world.RenderWorld;
import net.minecraft.block.BedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BedBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.render.model.BlockStateModel;
import net.minecraft.client.texture.Sprite;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockRenderView;

/**
 * Optimizes beds into static terrain geometry.
 */
public final class BedBlockEntityOptimizer implements IBlockEntityOptimizer<BedBlockEntity> {

    @Override
    public boolean supports(BlockEntity be) {
        return be instanceof BedBlockEntity;
    }

    @Override
    public BlockEntityClassification classify(BedBlockEntity be, BlockState state) {
        return BlockEntityClassification.STATIC;
    }

    @Override
    public boolean bake(BedBlockEntity be, BlockState state, BlockRenderView world, BlockPos pos,
                        int x, int y, int z, int packedLight, BlockRenderManager models,
                        LayerMeshBuilder[] builders, float[] posScratch, float[] uvScratch) {
        LayerMeshBuilder target = builders[RenderWorld.TerrainLayer.SOLID.ordinal()];
        if (target == null) return false;

        BlockStateModel model = models.getModel(state);
        Sprite sprite = model != null ? model.particleSprite() : null;
        net.minecraft.block.enums.BedPart part = state.contains(BedBlock.PART) ? state.get(BedBlock.PART) : net.minecraft.block.enums.BedPart.FOOT;

        // Base frame & mattress (0.0 to 0.5625 Y)
        BlockEntityMeshHelper.appendCuboid(
            target, x, y, z,
            0.0f, 0.0f, 0.0f, 1.0f, 0.5625f, 1.0f,
            sprite, packedLight, 0xFFFFFFFF, posScratch, uvScratch);

        // If head of bed, also append headboard based on facing direction
        if (part == net.minecraft.block.enums.BedPart.HEAD) {
            net.minecraft.util.math.Direction facing = state.contains(BedBlock.FACING) ? state.get(BedBlock.FACING) : net.minecraft.util.math.Direction.NORTH;
            float hbMinX = 0.0f, hbMaxX = 1.0f;
            float hbMinY = 0.5625f, hbMaxY = 0.8125f; // elevated headboard
            float hbMinZ = 0.0f, hbMaxZ = 1.0f;

            switch (facing) {
                case NORTH -> hbMaxZ = 0.1875f;
                case SOUTH -> hbMinZ = 0.8125f;
                case WEST  -> hbMaxX = 0.1875f;
                case EAST  -> hbMinX = 0.8125f;
                default -> {}
            }

            BlockEntityMeshHelper.appendCuboid(
                target, x, y, z,
                hbMinX, hbMinY, hbMinZ, hbMaxX, hbMaxY, hbMaxZ,
                sprite, packedLight, 0xFFFFFFFF, posScratch, uvScratch);
        }

        return true;
    }
}
