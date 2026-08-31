package destiny.renderer.cull;

import java.util.Arrays;

public final class SoftwareOcclusionCuller {

    public static final int BUFFER_WIDTH = 128;
    public static final int BUFFER_HEIGHT = 72;
    private static final int BUFFER_SIZE = BUFFER_WIDTH * BUFFER_HEIGHT;

    private final float[] depthBuffer = new float[BUFFER_SIZE];
    private final float[] mvpMatrix = new float[16];
    private boolean enabled = true;

    private final float[] cornerX = new float[8];
    private final float[] cornerY = new float[8];
    private final float[] cornerZ = new float[8];

    public SoftwareOcclusionCuller() {
        Arrays.fill(depthBuffer, 1.0f);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void beginFrame(float[] mvp) {
        if (mvp != null && mvp.length >= 16) {
            System.arraycopy(mvp, 0, mvpMatrix, 0, 16);
        }
        Arrays.fill(depthBuffer, 1.0f);
    }

    public void rasterizeOccluder(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        if (!enabled) return;

        int minPx = BUFFER_WIDTH, maxPx = -1;
        int minPy = BUFFER_HEIGHT, maxPy = -1;
        float maxDepth = -1.0f;

        computeCorners(minX, minY, minZ, maxX, maxY, maxZ);

        for (int i = 0; i < 8; i++) {
            float x = cornerX[i];
            float y = cornerY[i];
            float z = cornerZ[i];

            float clipW = mvpMatrix[3] * x + mvpMatrix[7] * y + mvpMatrix[11] * z + mvpMatrix[15];
            if (clipW <= 0.05f) continue;

            float clipX = mvpMatrix[0] * x + mvpMatrix[4] * y + mvpMatrix[8] * z + mvpMatrix[12];
            float clipY = mvpMatrix[1] * x + mvpMatrix[5] * y + mvpMatrix[9] * z + mvpMatrix[13];
            float clipZ = mvpMatrix[2] * x + mvpMatrix[6] * y + mvpMatrix[10] * z + mvpMatrix[14];

            float ndcX = clipX / clipW;
            float ndcY = clipY / clipW;
            float ndcZ = (clipZ / clipW) * 0.5f + 0.5f;

            int px = (int) ((ndcX * 0.5f + 0.5f) * BUFFER_WIDTH);
            int py = (int) ((ndcY * 0.5f + 0.5f) * BUFFER_HEIGHT);

            if (px < minPx) minPx = px;
            if (px > maxPx) maxPx = px;
            if (py < minPy) minPy = py;
            if (py > maxPy) maxPy = py;
            if (ndcZ > maxDepth) maxDepth = ndcZ;
        }

        minPx = Math.max(0, minPx);
        maxPx = Math.min(BUFFER_WIDTH - 1, maxPx);
        minPy = Math.max(0, minPy);
        maxPy = Math.min(BUFFER_HEIGHT - 1, maxPy);

        if (minPx > maxPx || minPy > maxPy || maxDepth < 0.0f) return;

        for (int y = minPy; y <= maxPy; y++) {
            int row = y * BUFFER_WIDTH;
            for (int x = minPx; x <= maxPx; x++) {
                int idx = row + x;
                if (maxDepth < depthBuffer[idx]) {
                    depthBuffer[idx] = maxDepth;
                }
            }
        }
    }

    public boolean isOccluded(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        if (!enabled) return false;

        computeCorners(minX, minY, minZ, maxX, maxY, maxZ);

        int minPx = BUFFER_WIDTH, maxPx = -1;
        int minPy = BUFFER_HEIGHT, maxPy = -1;
        float minDepth = 2.0f;
        boolean allInFrontOfNear = true;

        for (int i = 0; i < 8; i++) {
            float x = cornerX[i];
            float y = cornerY[i];
            float z = cornerZ[i];

            float clipW = mvpMatrix[3] * x + mvpMatrix[7] * y + mvpMatrix[11] * z + mvpMatrix[15];
            if (clipW <= 0.05f) {
                allInFrontOfNear = false;
                continue;
            }

            float clipX = mvpMatrix[0] * x + mvpMatrix[4] * y + mvpMatrix[8] * z + mvpMatrix[12];
            float clipY = mvpMatrix[1] * x + mvpMatrix[5] * y + mvpMatrix[9] * z + mvpMatrix[13];
            float clipZ = mvpMatrix[2] * x + mvpMatrix[6] * y + mvpMatrix[10] * z + mvpMatrix[14];

            float ndcX = clipX / clipW;
            float ndcY = clipY / clipW;
            float ndcZ = (clipZ / clipW) * 0.5f + 0.5f;

            int px = (int) ((ndcX * 0.5f + 0.5f) * BUFFER_WIDTH);
            int py = (int) ((ndcY * 0.5f + 0.5f) * BUFFER_HEIGHT);

            if (px < minPx) minPx = px;
            if (px > maxPx) maxPx = px;
            if (py < minPy) minPy = py;
            if (py > maxPy) maxPy = py;
            if (ndcZ < minDepth) minDepth = ndcZ;
        }

        if (!allInFrontOfNear) return false;

        minPx = Math.max(0, minPx);
        maxPx = Math.min(BUFFER_WIDTH - 1, maxPx);
        minPy = Math.max(0, minPy);
        maxPy = Math.min(BUFFER_HEIGHT - 1, maxPy);

        if (minPx > maxPx || minPy > maxPy) return false;

        for (int y = minPy; y <= maxPy; y++) {
            int row = y * BUFFER_WIDTH;
            for (int x = minPx; x <= maxPx; x++) {
                if (depthBuffer[row + x] >= minDepth) {
                    return false;
                }
            }
        }

        return true;
    }

    public boolean isSectionOccluded(int secX, int secY, int secZ) {
        return isOccluded(secX, secY, secZ, secX + 16, secY + 16, secZ + 16);
    }

    private void computeCorners(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        cornerX[0] = minX; cornerY[0] = minY; cornerZ[0] = minZ;
        cornerX[1] = maxX; cornerY[1] = minY; cornerZ[1] = minZ;
        cornerX[2] = minX; cornerY[2] = maxY; cornerZ[2] = minZ;
        cornerX[3] = maxX; cornerY[3] = maxY; cornerZ[3] = minZ;
        cornerX[4] = minX; cornerY[4] = minY; cornerZ[4] = maxZ;
        cornerX[5] = maxX; cornerY[5] = minY; cornerZ[5] = maxZ;
        cornerX[6] = minX; cornerY[6] = maxY; cornerZ[6] = maxZ;
        cornerX[7] = maxX; cornerY[7] = maxY; cornerZ[7] = maxZ;
    }
}
