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
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Implements the entity visibility and culling toggles.
 *
 * <p>{@code shouldRender} is the correct interception point: returning false here skips
 * render-state construction entirely, so we avoid the per-entity model setup rather than
 * merely skipping the draw.
 *
 * <p>Entity culling defers to {@link WorkAllotment}. If EntityCulling is installed it uses
 * async occlusion raycasting, which is strictly better than a frustum test, so we stand
 * down rather than doing duplicate work.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin<T extends Entity> {

    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private void destinyrenderer$filterEntity(T entity, Frustum frustum,
                                              double camX, double camY, double camZ,
                                              CallbackInfoReturnable<Boolean> cir) {
        // Fast path: when nothing on this page is customised, get out before doing any
        // instanceof chain at all. This runs for every entity, every frame.
        if (!RendererConfig.anyEntityFilterActive()) return;

        RendererConfig cfg = RendererConfig.get();

        // --- Combat safety ---
        // Never hide anything that can kill you or that you need to react to. Culling an
        // opponent behind a corner, or an arrow already in flight toward you, turns a
        // performance setting into a competitive disadvantage.
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
        // Never cull the camera entity itself, or the player loses their own model in
        // third person and while riding.
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && entity == mc.getCameraEntity()) return;

        if (!WorkAllotment.isOwnedByUs(Capability.ENTITY_CULLING)) return;
        if (!cfg.cullEntities) return;

        double maxDist = maxRenderDistance(cfg, entity);
        if (maxDist > 0) {
            double dx = entity.getX() - camX;
            double dy = entity.getY() - camY;
            double dz = entity.getZ() - camZ;
            if (dx * dx + dy * dy + dz * dz > maxDist * maxDist) {
                cir.setReturnValue(false);
            }
        }
    }

    /**
     * @return true for entities that must always be visible for fair play.
     *
     * <p>Covers other players and every projectile type. Deliberately errs on the side of
     * rendering: a false positive costs a few draw calls, a false negative costs a fight.
     */
    private static boolean destinyrenderer$isCombatRelevant(Entity entity) {
        // Other players and anything in flight. Mobs are deliberately NOT included —
        // including every LivingEntity would disable entity culling altogether, which is
        // one of the larger performance wins available.
        return entity instanceof net.minecraft.entity.player.PlayerEntity
            || entity instanceof net.minecraft.entity.projectile.ProjectileEntity
            || entity instanceof net.minecraft.entity.TntEntity
            || entity instanceof net.minecraft.entity.decoration.EndCrystalEntity;
    }

    /**
     * Distance beyond which an entity is not drawn. Decorative entities get a tighter
     * budget than mobs because they carry far less gameplay information.
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
