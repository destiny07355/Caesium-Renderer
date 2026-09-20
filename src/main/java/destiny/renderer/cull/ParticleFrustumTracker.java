package destiny.renderer.cull;

import net.minecraft.util.math.Box;

/**
 * Tracks the viewing frustum on the render thread to cull offscreen particles before geometry building.
 */
public final class ParticleFrustumTracker {
    private static final FrustumCuller FRUSTUM = new FrustumCuller();
    private static volatile boolean active = false;

    private ParticleFrustumTracker() {}

    public static void update(float[] mvp) {
        if (mvp != null) {
            FRUSTUM.update(mvp);
            active = true;
        }
    }

    public static boolean isBoxVisible(Box box) {
        if (!active || box == null) return true;
        float minX = (float) box.minX - 0.5f;
        float minY = (float) box.minY - 0.5f;
        float minZ = (float) box.minZ - 0.5f;
        float maxX = (float) box.maxX + 0.5f;
        float maxY = (float) box.maxY + 0.5f;
        float maxZ = (float) box.maxZ + 0.5f;
        return FRUSTUM.isBoxVisible(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
