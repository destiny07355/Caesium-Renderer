package destiny.renderer.render.entity;

public final class EntityMatrixCache {

    private static final int MAX_ENTITIES = 4096;
    private static final int FLOATS_PER_MATRIX = 16;
    private static final float[] MATRIX_BUFFER = new float[MAX_ENTITIES * FLOATS_PER_MATRIX];
    private static int activeEntityCount = 0;

    private EntityMatrixCache() {}

    public static void beginFrame() {
        activeEntityCount = 0;
    }

    public static int allocateMatrix(float tx, float ty, float tz, float yaw, float pitch, float scale) {
        if (activeEntityCount >= MAX_ENTITIES) {
            return 0;
        }

        int offset = activeEntityCount * FLOATS_PER_MATRIX;
        activeEntityCount++;

        float radYaw = (float) Math.toRadians(yaw);
        float radPitch = (float) Math.toRadians(pitch);

        float cosY = (float) Math.cos(radYaw);
        float sinY = (float) Math.sin(radYaw);
        float cosP = (float) Math.cos(radPitch);
        float sinP = (float) Math.sin(radPitch);

        MATRIX_BUFFER[offset]      = cosY * scale;
        MATRIX_BUFFER[offset + 1]  = 0.0f;
        MATRIX_BUFFER[offset + 2]  = -sinY * scale;
        MATRIX_BUFFER[offset + 3]  = 0.0f;

        MATRIX_BUFFER[offset + 4]  = sinY * sinP * scale;
        MATRIX_BUFFER[offset + 5]  = cosP * scale;
        MATRIX_BUFFER[offset + 6]  = cosY * sinP * scale;
        MATRIX_BUFFER[offset + 7]  = 0.0f;

        MATRIX_BUFFER[offset + 8]  = sinY * cosP * scale;
        MATRIX_BUFFER[offset + 9]  = -sinP * scale;
        MATRIX_BUFFER[offset + 10] = cosY * cosP * scale;
        MATRIX_BUFFER[offset + 11] = 0.0f;

        MATRIX_BUFFER[offset + 12] = tx;
        MATRIX_BUFFER[offset + 13] = ty;
        MATRIX_BUFFER[offset + 14] = tz;
        MATRIX_BUFFER[offset + 15] = 1.0f;

        return offset;
    }

    public static float[] getMatrixBuffer() {
        return MATRIX_BUFFER;
    }

    public static int getActiveEntityCount() {
        return activeEntityCount;
    }
}
