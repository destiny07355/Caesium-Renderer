package destiny.renderer.mixin;

import destiny.renderer.blockentity.BlockEntityOptimizationRegistry;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.EnderChestBlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Handles seamless transitions between static terrain meshing and dynamic BlockEntityRenderer for ender chests.
 */
@Mixin(EnderChestBlockEntity.class)
public abstract class EnderChestAnimationMixin extends BlockEntity {

    protected EnderChestAnimationMixin(net.minecraft.block.entity.BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Inject(method = "onSyncedBlockEvent", at = @At("HEAD"))
    private void destinyrenderer$onEnderChestOpenEvent(int type, int data, CallbackInfoReturnable<Boolean> cir) {
        if (type == 1 && data > 0) {
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
    private static void destinyrenderer$onEnderChestClientTick(World world, BlockPos pos, BlockState state,
                                                               EnderChestBlockEntity blockEntity, CallbackInfo ci) {
        if (world != null && world.isClient()
                && blockEntity.getAnimationProgress(0.0f) == 0.0f
                && !BlockEntityOptimizationRegistry.isStaticallyBaked(pos.asLong())) {
            if (caesium.integration.CaesiumIntegration.started()) {
                ChunkSectionPos secPos = ChunkSectionPos.from(pos);
                caesium.integration.CaesiumIntegration.extractSection(secPos);
            }
        }
    }
}
