package destiny.renderer.cull;

/**
 * Fast 6-plane viewing frustum culler for chunk bounding boxes.
 */
public final class FrustumCuller {

    // 6 planes: Left, Right, Bottom, Top, Near, Far. Each plane has 4 components: A, B, C, D.
    private final float[] planes = new float[24];

    /**
     * Updates frustum planes from a column-major 4x4 View-Projection matrix.
     */
    public void update(float[] mvp) {
        // Left
        planes[0]  = mvp[3]  + mvp[0];
        planes[1]  = mvp[7]  + mvp[4];
        planes[2]  = mvp[11] + mvp[8];
        planes[3]  = mvp[15] + mvp[12];

        // Right
        planes[4]  = mvp[3]  - mvp[0];
        planes[5]  = mvp[7]  - mvp[4];
        planes[6]  = mvp[11] - mvp[8];
        planes[7]  = mvp[15] - mvp[12];

        // Bottom
        planes[8]  = mvp[3]  + mvp[1];
        planes[9]  = mvp[7]  + mvp[5];
        planes[10] = mvp[11] + mvp[9];
        planes[11] = mvp[15] + mvp[13];

        // Top
        planes[12] = mvp[3]  - mvp[1];
        planes[13] = mvp[7]  - mvp[5];
        planes[14] = mvp[11] - mvp[9];
        planes[15] = mvp[15] - mvp[13];

        // Near
        planes[16] = mvp[3]  + mvp[2];
        planes[17] = mvp[7]  + mvp[6];
        planes[18] = mvp[11] + mvp[10];
        planes[19] = mvp[15] + mvp[14];

        // Far
        planes[20] = mvp[3]  - mvp[2];
        planes[21] = mvp[7]  - mvp[6];
        planes[22] = mvp[11] - mvp[10];
        planes[23] = mvp[15] - mvp[14];

        // Normalize all 6 planes
        for (int i = 0; i < 24; i += 4) {
            float a = planes[i];
            float b = planes[i + 1];
            float c = planes[i + 2];
            float length = (float) Math.sqrt(a * a + b * b + c * c);
            if (length > 0.00001f) {
                float inv = 1.0f / length;
                planes[i]     *= inv;
                planes[i + 1] *= inv;
                planes[i + 2] *= inv;
                planes[i + 3] *= inv;
            }
        }
    }

    /**
     * Tests if an Axis-Aligned Bounding Box (AABB) intersects or is inside the frustum.
     */
    public boolean isBoxVisible(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        for (int i = 0; i < 24; i += 4) {
            float a = planes[i];
            float b = planes[i + 1];
            float c = planes[i + 2];
            float d = planes[i + 3];

            // Find the p-vertex (farthest point in positive normal direction)
            float px = a > 0 ? maxX : minX;
            float py = b > 0 ? maxY : minY;
            float pz = c > 0 ? maxZ : minZ;

            // If the p-vertex is outside the plane, the entire box is culled
            if (a * px + b * py + c * pz + d < 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Tests if a 16x16x16 chunk section at the given origin is visible.
     */
    public boolean isSectionVisible(int minX, int minY, int minZ) {
        return isBoxVisible(minX, minY, minZ, minX + 16, minY + 16, minZ + 16);
    }
}
