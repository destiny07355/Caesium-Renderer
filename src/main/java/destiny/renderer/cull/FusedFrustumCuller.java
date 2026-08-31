package destiny.renderer.cull;

public final class FusedFrustumCuller {

    private static final float SECTION_RADIUS = 13.856406f;
    private final float[] planes = new float[24];

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

        for (int i = 0; i < 24; i += 4) {
            float a = planes[i];
            float b = planes[i + 1];
            float c = planes[i + 2];
            float len = (float) Math.sqrt(a * a + b * b + c * c);
            if (len > 0.00001f) {
                float inv = 1.0f / len;
                planes[i]     *= inv;
                planes[i + 1] *= inv;
                planes[i + 2] *= inv;
                planes[i + 3] *= inv;
            }
        }
    }

    public boolean isSectionVisible(int minX, int minY, int minZ) {
        float cx = minX + 8.0f;
        float cy = minY + 8.0f;
        float cz = minZ + 8.0f;

        for (int i = 0; i < 24; i += 4) {
            float a = planes[i];
            float b = planes[i + 1];
            float c = planes[i + 2];
            float d = planes[i + 3];

            float dist = a * cx + b * cy + c * cz + d;
            if (dist < -SECTION_RADIUS) {
                return false;
            }

            if (dist < SECTION_RADIUS) {
                float px = a > 0 ? (minX + 16.0f) : minX;
                float py = b > 0 ? (minY + 16.0f) : minY;
                float pz = c > 0 ? (minZ + 16.0f) : minZ;

                if (a * px + b * py + c * pz + d < 0) {
                    return false;
                }
            }
        }
        return true;
    }
}
