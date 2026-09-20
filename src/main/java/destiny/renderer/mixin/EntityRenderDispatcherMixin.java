package destiny.renderer.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.world.WorldView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Optimizes entity shadow rendering in crowded multiplayer areas and hubs.
 */
@Mixin(targets = "net.minecraft.client.render.entity.EntityRenderDispatcher")
public abstract class EntityRenderDispatcherMixin {

    @Inject(
        method = "renderShadow",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void destinyrenderer$cullEntityShadows(MatrixStack matrices,
                                                          VertexConsumerProvider vertexConsumers,
                                                          Entity entity,
                                                          float opacity,
                                                          float tickDelta,
                                                          WorldView world,
                                                          float radius,
                                                          CallbackInfo ci) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return;

        if (mc.options != null && !mc.options.getEntityShadows().getValue()) {
            ci.cancel();
            return;
        }

        // Distance culling on shadows: skip expensive multi-raycast shadows for entities > 16 blocks away
        // Also skip shadows on crystals and projectiles completely in combat/PvP
        if (entity instanceof net.minecraft.entity.decoration.EndCrystalEntity
                || entity instanceof net.minecraft.entity.projectile.ProjectileEntity) {
            ci.cancel();
            return;
        }

        if (mc.player != null && entity != null) {
            double dx = entity.getX() - mc.player.getX();
            double dy = entity.getY() - mc.player.getY();
            double dz = entity.getZ() - mc.player.getZ();
            if (dx * dx + dy * dy + dz * dz > 16.0 * 16.0) {
                ci.cancel();
            }
        }
    }
}
