package destiny.renderer.mixin;

import destiny.renderer.compat.Capability;
import destiny.renderer.compat.WorkAllotment;
import destiny.renderer.config.RendererConfig;
import destiny.renderer.cull.EntityOcclusionCuller;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.AbstractDecorationEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.decoration.painting.PaintingEntity;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Implements high-performance entity visibility, crowd player distance culling,
 * fast bitmask classification, and true voxel wall occlusion culling.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin<T extends Entity> {

    private static long destinyrenderer$lastFrameId = -1L;
    private static MinecraftClient destinyrenderer$cachedMc;
    private static Entity destinyrenderer$cachedCameraEntity;
    private static double destinyrenderer$cachedMaxPlayerDistSq = 64.0 * 64.0;
    private static double destinyrenderer$cachedBaseScaledDist = 0.0;
    private static boolean destinyrenderer$cachedOwnedCulling = false;
    private static RendererConfig destinyrenderer$cachedCfg;

    // Fast category bitflags — eliminates cascading instanceof queries per entity
    private static final int CAT_PLAYER      = 1 << 0;
    private static final int CAT_CRYSTAL     = 1 << 1;
    private static final int CAT_PROJECTILE  = 1 << 2;
    private static final int CAT_TNT         = 1 << 3;
    private static final int CAT_ITEM_FRAME  = 1 << 4;
    private static final int CAT_ARMOR_STAND = 1 << 5;
    private static final int CAT_PAINTING    = 1 << 6;
    private static final int CAT_ITEM        = 1 << 7;
    private static final int CAT_DISPLAY     = 1 << 8;

    private static final int CAT_COMBAT = CAT_PLAYER | CAT_CRYSTAL | CAT_PROJECTILE | CAT_TNT;

    private static final ConcurrentHashMap<EntityType<?>, Integer> CATEGORY_CACHE = new ConcurrentHashMap<>();

    private static int destinyrenderer$getCategory(Entity entity) {
        EntityType<?> type = entity.getType();
        Integer cached = CATEGORY_CACHE.get(type);
        if (cached != null) return cached;

        int cat = 0;
        if (entity instanceof net.minecraft.entity.player.PlayerEntity) cat |= CAT_PLAYER;
        if (entity instanceof net.minecraft.entity.decoration.EndCrystalEntity) cat |= CAT_CRYSTAL;
        if (entity instanceof net.minecraft.entity.projectile.ProjectileEntity) cat |= CAT_PROJECTILE;
        if (entity instanceof net.minecraft.entity.TntEntity) cat |= CAT_TNT;
        if (entity instanceof ItemFrameEntity) cat |= CAT_ITEM_FRAME;
        if (entity instanceof ArmorStandEntity) cat |= CAT_ARMOR_STAND;
        if (entity instanceof PaintingEntity) cat |= CAT_PAINTING;
        if (entity instanceof net.minecraft.entity.decoration.DisplayEntity) cat |= CAT_DISPLAY;
        if (entity instanceof net.minecraft.entity.ItemEntity || entity instanceof net.minecraft.entity.ExperienceOrbEntity) cat |= CAT_ITEM;

        CATEGORY_CACHE.put(type, cat);
        return cat;
    }

    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private void destinyrenderer$filterEntity(T entity, Frustum frustum,
                                              double camX, double camY, double camZ,
                                              CallbackInfoReturnable<Boolean> cir) {
        if (!RendererConfig.anyEntityFilterActive()) return;

        long currentFrame = destiny.renderer.hud.PerformanceOverlay.frameCounter();
        if (destinyrenderer$lastFrameId != currentFrame) {
            destinyrenderer$lastFrameId = currentFrame;
            destinyrenderer$cachedMc = MinecraftClient.getInstance();
            destinyrenderer$cachedCameraEntity = destinyrenderer$cachedMc != null ? destinyrenderer$cachedMc.getCameraEntity() : null;
            destinyrenderer$cachedCfg = RendererConfig.get();
            destinyrenderer$cachedOwnedCulling = WorkAllotment.isOwnedByUs(Capability.ENTITY_CULLING);

            double maxPlayerDist = (destinyrenderer$cachedMc != null && destinyrenderer$cachedMc.options != null)
                ? Math.min(destinyrenderer$cachedMc.options.getViewDistance().getValue() * 16.0, 80.0 * destinyrenderer$cachedCfg.entityRenderDistanceMult)
                : 64.0;
            destinyrenderer$cachedMaxPlayerDistSq = maxPlayerDist * maxPlayerDist;

            if (destinyrenderer$cachedMc != null && destinyrenderer$cachedMc.options != null) {
                destinyrenderer$cachedBaseScaledDist = destinyrenderer$cachedMc.options.getViewDistance().getValue() * 16.0 * destinyrenderer$cachedCfg.entityRenderDistanceMult;
            } else {
                destinyrenderer$cachedBaseScaledDist = 0.0;
            }
        }

        RendererConfig cfg = destinyrenderer$cachedCfg != null ? destinyrenderer$cachedCfg : RendererConfig.get();

        // Never cull the camera entity itself
        if (destinyrenderer$cachedCameraEntity != null && entity == destinyrenderer$cachedCameraEntity) return;

        double dx = entity.getX() - camX;
        double dy = entity.getY() - camY;
        double dz = entity.getZ() - camZ;
        double distSq = dx * dx + dy * dy + dz * dz;

        int cat = destinyrenderer$getCategory(entity);

        // --- Combat safety (MUST run BEFORE occlusion and backface culling) ---
        // On competitive multiplayer servers (e.g. MCPVP.club), combat entities (players, crystals,
        // projectiles, TNT) must never be hidden or turn invisible due to raycast occlusion!
        if (cfg.alwaysRenderCombatEntities && (cat & CAT_COMBAT) != 0) {
            // Even in combat, crystals and projectiles beyond active engagement distance
            // cause severe drawcall stalls in Crystal PvP / MCPVP arenas.
            if ((cat & CAT_CRYSTAL) != 0 && distSq > 40.0 * 40.0) {
                cir.setReturnValue(false);
                return;
            }
            if ((cat & (CAT_PROJECTILE | CAT_TNT)) != 0 && distSq > 48.0 * 48.0) {
                cir.setReturnValue(false);
                return;
            }
            if ((cat & CAT_PLAYER) != 0 && distSq > destinyrenderer$cachedMaxPlayerDistSq) {
                cir.setReturnValue(false);
                return;
            }
            return;
        }

        // --- Crowd player culling for dense lobbies/PvP hubs ---
        if ((cat & CAT_PLAYER) != 0) {
            if (distSq > destinyrenderer$cachedMaxPlayerDistSq) {
                cir.setReturnValue(false);
                return;
            }
        }

        // --- Wall / Block Occlusion Culling (EntityCulling style) ---
        if (EntityOcclusionCuller.isOccluded(entity, camX, camY, camZ)) {
            cir.setReturnValue(false);
            return;
        }

        // --- Backface Decoration Attachment Culling (MoreCulling style) ---
        if ((cat & (CAT_ITEM_FRAME | CAT_PAINTING)) != 0
                && WorkAllotment.isOwnedByUs(Capability.BLOCK_CULLING)
                && destinyrenderer$isBackfaceCulled(entity, camX, camY, camZ)) {
            cir.setReturnValue(false);
            return;
        }

        // --- Per-type visibility toggles ---
        if ((cat & CAT_ITEM_FRAME) != 0 && !cfg.enableItemFrames) {
            cir.setReturnValue(false);
            return;
        }
        if ((cat & CAT_ARMOR_STAND) != 0 && !cfg.enableArmorStands) {
            cir.setReturnValue(false);
            return;
        }
        if ((cat & CAT_PAINTING) != 0 && !cfg.enablePaintings) {
            cir.setReturnValue(false);
            return;
        }
        if ((cat & CAT_ITEM) != 0 && !cfg.enableItemEntities) {
            cir.setReturnValue(false);
            return;
        }

        // --- Distance culling ---
        if (!destinyrenderer$cachedOwnedCulling) return;
        if (!cfg.cullEntities) return;

        double maxDist = maxRenderDistance(cfg, cat);
        if (maxDist > 0 && distSq > maxDist * maxDist) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "hasLabel", at = @At("HEAD"), cancellable = true)
    private void destinyrenderer$cullNametags(T entity, double squaredDistanceToCamera, CallbackInfoReturnable<Boolean> cir) {
        // Nametag culling: in crowded arenas/lobbies (e.g. MCPVP), skip matrix/text rendering
        // for entities > 32m away unless directly targeted by crosshair.
        if (squaredDistanceToCamera > 32.0 * 32.0) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc != null && mc.targetedEntity != entity) {
                cir.setReturnValue(false);
            }
        }
    }

    /**
     * MoreCulling-style backface culling for wall-mounted decorations.
     */
    private static boolean destinyrenderer$isBackfaceCulled(Entity entity, double camX, double camY, double camZ) {
        if (entity instanceof AbstractDecorationEntity deco) {
            Direction facing = deco.getHorizontalFacing();
            if (facing != null) {
                double ex = entity.getX();
                double ey = entity.getY();
                double ez = entity.getZ();
                return switch (facing) {
                    case NORTH -> camZ > ez;
                    case SOUTH -> camZ < ez;
                    case WEST  -> camX > ex;
                    case EAST  -> camX < ex;
                    case UP    -> camY < ey;
                    case DOWN  -> camY > ey;
                };
            }
        }
        return false;
    }

    /**
     * Distance beyond which an entity is not drawn.
     */
    private static double maxRenderDistance(RendererConfig cfg, int cat) {
        double scaled = destinyrenderer$cachedBaseScaledDist;
        if (scaled <= 0.0) {
            MinecraftClient mc = destinyrenderer$cachedMc != null ? destinyrenderer$cachedMc : MinecraftClient.getInstance();
            if (mc == null || mc.options == null) return 0;
            double base = mc.options.getViewDistance().getValue() * 16.0;
            scaled = base * cfg.entityRenderDistanceMult;
        }

        if ((cat & CAT_ITEM) != 0) {
            return Math.min(scaled, 32.0); // Dropped items & XP orbs beyond 32m are invisible and kill drawcalls
        }
        if ((cat & CAT_DISPLAY) != 0) {
            return Math.min(scaled, 48.0); // Server text & item displays beyond 48m kill FPS on lobby/server login
        }
        if (((cat & CAT_ITEM_FRAME) != 0 && cfg.cullItemFrames)
            || ((cat & CAT_ARMOR_STAND) != 0 && cfg.cullArmorStands)) {
            return Math.min(scaled, 64.0);
        }
        return scaled;
    }
}
