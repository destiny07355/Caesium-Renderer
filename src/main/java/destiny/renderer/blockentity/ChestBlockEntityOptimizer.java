package destiny.renderer.blockentity;

import caesium.engine.world.LayerMeshBuilder;
import caesium.engine.world.RenderWorld;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.EnderChestBlockEntity;
import net.minecraft.block.entity.LidOpenable;
import net.minecraft.block.entity.TrappedChestBlockEntity;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.render.model.BlockStateModel;
import net.minecraft.client.texture.Sprite;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockRenderView;

/**
 * Optimizes chests (single, double, trapped, ender) into static terrain geometry when closed.
 */
public final class ChestBlockEntityOptimizer implements IBlockEntityOptimizer<BlockEntity> {

    @Override
    public boolean supports(BlockEntity be) {
        return be instanceof ChestBlockEntity || be instanceof EnderChestBlockEntity || be instanceof TrappedChestBlockEntity;
    }

    @Override
    public BlockEntityClassification classify(BlockEntity be, BlockState state) {
        // Christmas season textures: chests use special festive textures, defer to dynamic renderer
        if (isChristmas()) {
            return BlockEntityClassification.DYNAMIC;
        }
        if (be instanceof LidOpenable lid) {
            if (lid.getAnimationProgress(0.0f) > 0.0f) {
                return BlockEntityClassification.DYNAMIC;
            }
        }
        if (be instanceof ChestBlockEntity chest) {
            if (chest.getWorld() != null && ChestBlockEntity.getPlayersLookingInChestCount(chest.getWorld(), chest.getPos()) > 0) {
                return BlockEntityClassification.DYNAMIC;
            }
            if (!chest.getViewingUsers().isEmpty()) {
                return BlockEntityClassification.DYNAMIC;
            }
        }
        return BlockEntityClassification.STATIC;
    }

    private static boolean isChristmas() {
        java.time.LocalDate date = java.time.LocalDate.now();
        int month = date.getMonthValue();
        int day = date.getDayOfMonth();
        return (month == 12 && day >= 24 && day <= 26);
    }

    @Override
    public boolean bake(BlockEntity be, BlockState state, BlockRenderView world, BlockPos pos,
                        int x, int y, int z, int packedLight, BlockRenderManager models,
                        LayerMeshBuilder[] builders, float[] posScratch, float[] uvScratch) {
        LayerMeshBuilder target = builders[RenderWorld.TerrainLayer.SOLID.ordinal()];
        if (target == null) return false;

        Direction facing = state.contains(ChestBlock.FACING) ? state.get(ChestBlock.FACING) : Direction.NORTH;
        ChestType type = state.contains(ChestBlock.CHEST_TYPE) ? state.get(ChestBlock.CHEST_TYPE) : ChestType.SINGLE;

        float minX = 0.0625f, maxX = 0.9375f;
        float minY = 0.0f, maxY = 0.875f;
        float minZ = 0.0625f, maxZ = 0.9375f;

        // Double chest side extension
        if (type == ChestType.LEFT) {
            switch (facing) {
                case NORTH -> maxX = 1.0f;
                case SOUTH -> minX = 0.0f;
                case EAST -> maxZ = 1.0f;
                case WEST -> minZ = 0.0f;
                default -> {}
            }
        } else if (type == ChestType.RIGHT) {
            switch (facing) {
                case NORTH -> minX = 0.0f;
                case SOUTH -> maxX = 1.0f;
                case EAST -> minZ = 0.0f;
                case WEST -> maxZ = 1.0f;
                default -> {}
            }
        }

        BlockStateModel model = models.getModel(state);
        Sprite sprite = model != null ? model.particleSprite() : null;

        // Bake chest body
        BlockEntityMeshHelper.appendCuboid(
            target, x, y, z,
            minX, minY, minZ, maxX, maxY, maxZ,
            sprite, packedLight, 0xFFFFFFFF, posScratch, uvScratch);

        // Bake front latch for single chests
        if (type == ChestType.SINGLE) {
            float lMinX = 0.4375f, lMaxX = 0.5625f;
            float lMinY = 0.4375f, lMaxY = 0.6875f;
            float lMinZ = 0.4375f, lMaxZ = 0.5625f;

            switch (facing) {
                case NORTH -> { lMinZ = 0.0f; lMaxZ = 0.0625f; }
                case SOUTH -> { lMinZ = 0.9375f; lMaxZ = 1.0f; }
                case WEST -> { lMinX = 0.0f; lMaxX = 0.0625f; }
                case EAST -> { lMinX = 0.9375f; lMaxX = 1.0f; }
                default -> {}
            }

            BlockEntityMeshHelper.appendCuboid(
                target, x, y, z,
                lMinX, lMinY, lMinZ, lMaxX, lMaxY, lMaxZ,
                sprite, packedLight, 0xFFFFFFFF, posScratch, uvScratch);
        }

        return true;
    }
}
