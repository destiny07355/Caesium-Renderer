package destiny.renderer.mixin;

import destiny.renderer.blockentity.BlockEntityOptimizationRegistry;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Handles seamless transitions between static terrain meshing and dynamic BlockEntityRenderer for chests.
 */
@Mixin(ChestBlockEntity.class)
public abstract class ChestAnimationMixin extends BlockEntity {

    protected ChestAnimationMixin(net.minecraft.block.entity.BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Inject(method = "onSyncedBlockEvent", at = @At("HEAD"))
    private void destinyrenderer$onChestOpenEvent(int type, int data, CallbackInfoReturnable<Boolean> cir) {
        if (type == 1 && data > 0) {
            // Chest is opening -> invalidate static claim and trigger section re-extract without static BE
            long posKey = this.getPos().asLong();
            if (BlockEntityOptimizationRegistry.isStaticallyBaked(posKey)) {
                BlockEntityOptimizationRegistry.invalidate(posKey);
                if (this.getWorld() != null && this.getWorld().isClient()) {
                    ChunkSectionPos secPos = ChunkSectionPos.from(this.getPos());
                    if (caesium.integration.CaesiumIntegration.started()) {
                        caesium.integration.CaesiumIntegration.extractSection(secPos);
                    }
                }
            }
        }
    }

    @Inject(method = "clientTick", at = @At("TAIL"))
    private static void destinyrenderer$onChestClientTick(World world, BlockPos pos, BlockState state,
                                                          ChestBlockEntity blockEntity, CallbackInfo ci) {
        if (world != null && world.isClient()
                && blockEntity.getAnimationProgress(0.0f) == 0.0f
                && !BlockEntityOptimizationRegistry.isStaticallyBaked(pos.asLong())
                && ChestBlockEntity.getPlayersLookingInChestCount(world, pos) == 0) {
            // Lid closed and no viewers -> schedule section re-extract to rebake into terrain mesh
            if (caesium.integration.CaesiumIntegration.started()) {
                ChunkSectionPos secPos = ChunkSectionPos.from(pos);
                caesium.integration.CaesiumIntegration.extractSection(secPos);
            }
        }
    }
}
