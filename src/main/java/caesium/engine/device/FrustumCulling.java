package caesium.engine.device;

/** Fast conservative section-AABB frustum culling using Gribb/Hartmann plane extraction. */
public final class FrustumCulling {
    public static final int PLANE_DATA_SIZE = 30; // 6 planes * (a, b, c, d, negRadius)

    private FrustumCulling() {}

    /**
     * Extracts 6 frustum planes and precomputed AABB radius thresholds from the MVP matrix.
     * Output array must have length of at least 30 (PLANE_DATA_SIZE).
     */
    public static void extractPlanes(float[] mvp, float[] out, boolean vulkanClip) {
        // Plane 0: Left (w + x >= 0)
        setPlane(out, 0, mvp[3] + mvp[0], mvp[7] + mvp[4], mvp[11] + mvp[8], mvp[15] + mvp[12]);
        // Plane 1: Right (w - x >= 0)
        setPlane(out, 1, mvp[3] - mvp[0], mvp[7] - mvp[4], mvp[11] - mvp[8], mvp[15] - mvp[12]);
        // Plane 2: Bottom (w + y >= 0)
        setPlane(out, 2, mvp[3] + mvp[1], mvp[7] + mvp[5], mvp[11] + mvp[9], mvp[15] + mvp[13]);
        // Plane 3: Top (w - y >= 0)
        setPlane(out, 3, mvp[3] - mvp[1], mvp[7] - mvp[5], mvp[11] - mvp[9], mvp[15] - mvp[13]);
        // Plane 4: Near
        if (vulkanClip) {
            setPlane(out, 4, mvp[2], mvp[6], mvp[10], mvp[14]);
        } else {
            setPlane(out, 4, mvp[3] + mvp[2], mvp[7] + mvp[6], mvp[11] + mvp[10], mvp[15] + mvp[14]);
        }
        // Plane 5: Far (w - z >= 0)
        setPlane(out, 5, mvp[3] - mvp[2], mvp[7] - mvp[6], mvp[11] - mvp[10], mvp[15] - mvp[14]);
    }

    private static void setPlane(float[] out, int index, float a, float b, float c, float d) {
        int off = index * 5;
        out[off]     = a;
        out[off + 1] = b;
        out[off + 2] = c;
        out[off + 3] = d;
        out[off + 4] = -8.0f * (Math.abs(a) + Math.abs(b) + Math.abs(c));
    }

    /**
     * Tests a 16x16x16 chunk section AABB against the pre-extracted planes.
     */
    public static boolean isVisible(float[] planes, float minX, float minY, float minZ) {
        float cx = minX + 8.0f;
        float cy = minY + 8.0f;
        float cz = minZ + 8.0f;

        for (int i = 0; i < 30; i += 5) {
            float dist = planes[i] * cx + planes[i + 1] * cy + planes[i + 2] * cz + planes[i + 3];
            if (dist < planes[i + 4]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Backward-compatible test evaluating a single section directly against an MVP matrix.
     */
    public static boolean visible(float[] mvp, float minX, float minY, float minZ, boolean vulkanClip) {
        float[] planes = new float[PLANE_DATA_SIZE];
        extractPlanes(mvp, planes, vulkanClip);
        return isVisible(planes, minX, minY, minZ);
    }
}
