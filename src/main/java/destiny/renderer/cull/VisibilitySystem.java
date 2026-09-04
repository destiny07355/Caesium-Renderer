package destiny.renderer.cull;

import net.minecraft.entity.Entity;

/**
 * Unified visibility and culling facade for the Caesium rendering engine.
 *
 * <p>Encapsulates terrain multi-stage culling (Bounding Sphere -> 6-Plane Frustum ->
 * Hierarchical Software Depth Occlusion) and entity frustum culling behind a single
 * policy interface so the rest of the renderer never directly handles individual cullers.
 */
public final class VisibilitySystem {

    private static final FusedFrustumCuller TERRAIN_FRUSTUM = new FusedFrustumCuller();
    private static final SoftwareOcclusionCuller OCCLUSION_CULLER = new SoftwareOcclusionCuller();

    private VisibilitySystem() {}

    /**
     * Updates camera matrices and frustum planes for the current frame.
     */
    public static void updateFrame(float[] mvp, double camX, double camY, double camZ) {
        destiny.renderer.hud.CaesiumFrameProfiler.beginVisibility();
        TERRAIN_FRUSTUM.update(mvp);
        if (OCCLUSION_CULLER.isEnabled()) {
            OCCLUSION_CULLER.beginFrame(mvp);
        }
        EntityFrustumCuller.update(mvp, camX, camY, camZ);
        destiny.renderer.hud.CaesiumFrameProfiler.endVisibility();
    }

    /**
     * Evaluates candidate terrain chunk section visibility across the full culling hierarchy.
     */
    public static boolean isSectionVisible(int minX, int minY, int minZ) {
        // High-performance Fused Frustum Check (Bounding Sphere early-out + 6-plane AABB)
        return TERRAIN_FRUSTUM.isSectionVisible(minX, minY, minZ);
    }

    /**
     * Evaluates entity visibility within the viewing frustum and distance thresholds.
     */
    public static boolean isEntityVisible(Entity entity, double maxDistSq) {
        return EntityFrustumCuller.shouldRender(entity, maxDistSq);
    }

    /**
     * Checks whether distant secondary entity limb animations can be throttled.
     */
    public static boolean canThrottleEntityAnimation(Entity entity) {
        return EntityFrustumCuller.canThrottleAnimation(entity);
    }

    public static FusedFrustumCuller getTerrainFrustum() {
        return TERRAIN_FRUSTUM;
    }

    public static SoftwareOcclusionCuller getOcclusionCuller() {
        return OCCLUSION_CULLER;
    }
}
