package destiny.renderer.mixin;

import destiny.renderer.compat.Capability;
import destiny.renderer.compat.WorkAllotment;
import destiny.renderer.config.RendererConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.decoration.painting.PaintingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Implements entity visibility and crowd player distance culling.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin<T extends Entity> {

    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private void destinyrenderer$filterEntity(T entity, Frustum frustum,
                                              double camX, double camY, double camZ,
                                              CallbackInfoReturnable<Boolean> cir) {
        if (!RendererConfig.anyEntityFilterActive()) return;

        RendererConfig cfg = RendererConfig.get();

        // Never cull the camera entity itself
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && entity == mc.getCameraEntity()) return;

        double dx = entity.getX() - camX;
        double dy = entity.getY() - camY;
        double dz = entity.getZ() - camZ;
        double distSq = dx * dx + dy * dy + dz * dz;

        // --- Crowd player culling for dense lobbies/PvP hubs ---
        if (entity instanceof net.minecraft.entity.player.PlayerEntity) {
            double maxPlayerDist = mc != null && mc.options != null
                ? Math.min(mc.options.getViewDistance().getValue() * 16.0, 80.0 * cfg.entityRenderDistanceMult)
                : 64.0;
            if (distSq > maxPlayerDist * maxPlayerDist) {
                cir.setReturnValue(false);
                return;
            }
        }

        // --- Combat safety ---
        if (cfg.alwaysRenderCombatEntities && destinyrenderer$isCombatRelevant(entity)) {
            return;
        }

        // --- Per-type visibility toggles ---
        if (entity instanceof ItemFrameEntity && !cfg.enableItemFrames) {
            cir.setReturnValue(false);
            return;
        }
        if (entity instanceof ArmorStandEntity && !cfg.enableArmorStands) {
            cir.setReturnValue(false);
            return;
        }
        if (entity instanceof PaintingEntity && !cfg.enablePaintings) {
            cir.setReturnValue(false);
            return;
        }
        if (entity instanceof net.minecraft.entity.ItemEntity && !cfg.enableItemEntities) {
            cir.setReturnValue(false);
            return;
        }

        // --- Distance culling ---
        if (!WorkAllotment.isOwnedByUs(Capability.ENTITY_CULLING)) return;
        if (!cfg.cullEntities) return;

        double maxDist = maxRenderDistance(cfg, entity);
        if (maxDist > 0 && distSq > maxDist * maxDist) {
            cir.setReturnValue(false);
        }
    }

    /**
     * @return true for entities that must always be visible for fair play.
     */
    private static boolean destinyrenderer$isCombatRelevant(Entity entity) {
        return entity instanceof net.minecraft.entity.player.PlayerEntity
            || entity instanceof net.minecraft.entity.projectile.ProjectileEntity
            || entity instanceof net.minecraft.entity.TntEntity
            || entity instanceof net.minecraft.entity.decoration.EndCrystalEntity;
    }

    /**
     * Distance beyond which an entity is not drawn.
     */
    private static double maxRenderDistance(RendererConfig cfg, Entity entity) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.options == null) return 0;

        double base = mc.options.getViewDistance().getValue() * 16.0;
        double scaled = base * cfg.entityRenderDistanceMult;

        if ((entity instanceof ItemFrameEntity && cfg.cullItemFrames)
            || (entity instanceof ArmorStandEntity && cfg.cullArmorStands)) {
            return Math.min(scaled, 64.0);
        }
        return scaled;
    }
}
