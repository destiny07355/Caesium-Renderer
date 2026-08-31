package destiny.renderer.cull;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.Box;

/**
 * Fast entity frustum culler and animation distance throttle.
 */
public final class EntityFrustumCuller {

    private static final FrustumCuller FRUSTUM = new FrustumCuller();
    private static final double ANIMATION_LOD_DIST_SQ = 48.0 * 48.0; // 48 blocks

    private static double cameraX = 0.0;
    private static double cameraY = 0.0;
    private static double cameraZ = 0.0;

    /**
     * Updates the camera matrices and frustum planes for the current frame.
     */
    public static void update(float[] mvp, double camX, double camY, double camZ) {
        FRUSTUM.update(mvp);
        cameraX = camX;
        cameraY = camY;
        cameraZ = camZ;
    }

    /**
     * Checks if an entity is within the viewing frustum and within active render distance.
     */
    public static boolean shouldRender(Entity entity, double maxDistSq) {
        if (entity == null) return false;

        double dx = entity.getX() - cameraX;
        double dy = entity.getY() - cameraY;
        double dz = entity.getZ() - cameraZ;
        double distSq = dx * dx + dy * dy + dz * dz;

        // 1. Distance culling
        if (distSq > maxDistSq) return false;

        // 2. Close-up bypass (entities within 4 blocks are always rendered)
        if (distSq < 16.0) return true;

        // 3. Frustum AABB culling
        Box box = entity.getBoundingBox();
        return FRUSTUM.isBoxVisible(
            (float) box.minX, (float) box.minY, (float) box.minZ,
            (float) box.maxX, (float) box.maxY, (float) box.maxZ
        );
    }

    /**
     * Returns true if distant secondary animations (e.g. idle limb wagging) can be throttled.
     */
    public static boolean canThrottleAnimation(Entity entity) {
        if (entity == null) return false;
        double dx = entity.getX() - cameraX;
        double dy = entity.getY() - cameraY;
        double dz = entity.getZ() - cameraZ;
        return (dx * dx + dy * dy + dz * dz) > ANIMATION_LOD_DIST_SQ;
    }
}
