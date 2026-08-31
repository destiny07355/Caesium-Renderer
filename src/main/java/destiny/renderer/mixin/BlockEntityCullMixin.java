package destiny.renderer.mixin;

import destiny.renderer.config.RendererConfig;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Distance-culls block entities.
 *
 * <h2>Why this matters for average frame rate</h2>
 * Block entities do not go through the batched chunk geometry path. Each is rendered
 * individually with its own model, texture bind, matrix work and often an animation
 * update — chests open, signs draw text, beacons cast a beam, shulkers animate. A storage
 * room can hold hundreds, and their combined cost frequently exceeds all the surrounding
 * terrain put together.
 *
 * <p>Vanilla culls them only by frustum, so a wall of chests you are facing renders in
 * full however far away it is. Adding a distance limit is one of the few remaining changes
 * that raises average FPS rather than only smoothing the lows.
 */
@Mixin(BlockEntityRenderer.class)
public interface BlockEntityCullMixin<T extends BlockEntity> {

    @Inject(method = "isInRenderDistance", at = @At("HEAD"), cancellable = true, require = 0)
    private void destinyrenderer$limitDistance(T blockEntity, Vec3d cameraPos,
                                               CallbackInfoReturnable<Boolean> cir) {
        RendererConfig cfg = RendererConfig.get();

        if (!cfg.enableBlockEntities) {
            cir.setReturnValue(false);
            return;
        }

        int limit = cfg.blockEntityRenderDistance;
        if (limit <= 0) return;

        BlockPos pos = blockEntity.getPos();
        double dx = pos.getX() + 0.5 - cameraPos.x;
        double dy = pos.getY() + 0.5 - cameraPos.y;
        double dz = pos.getZ() + 0.5 - cameraPos.z;

        if (dx * dx + dy * dy + dz * dz > (double) limit * limit) {
            cir.setReturnValue(false);
        }
    }
}
