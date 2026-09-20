package destiny.renderer.blockentity;

import caesium.engine.world.LayerMeshBuilder;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.CampfireBlockEntity;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockRenderView;

/**
 * Optimizes campfires into static terrain geometry when no food items are cooking.
 */
public final class CampfireBlockEntityOptimizer implements IBlockEntityOptimizer<CampfireBlockEntity> {

    @Override
    public boolean supports(BlockEntity be) {
        return be instanceof CampfireBlockEntity;
    }

    @Override
    public BlockEntityClassification classify(CampfireBlockEntity be, BlockState state) {
        for (ItemStack item : be.getItemsBeingCooked()) {
            if (!item.isEmpty()) {
                return BlockEntityClassification.DYNAMIC;
            }
        }
        return BlockEntityClassification.STATIC;
    }

    @Override
    public boolean bake(CampfireBlockEntity be, BlockState state, BlockRenderView world, BlockPos pos,
                        int x, int y, int z, int packedLight, BlockRenderManager models,
                        LayerMeshBuilder[] builders, float[] posScratch, float[] uvScratch) {
        // The block model itself already renders the campfire structure; returning true marks
        // it as statically baked so dynamic per-frame block entity rendering is suppressed.
        return true;
    }
}
