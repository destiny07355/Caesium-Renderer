package destiny.renderer.chunk.lod;

public final class ChunkLodDecimator {

    public enum LodLevel {
        LOD0_FULL(1.0f, 1),
        LOD1_SIMPLIFIED(0.5f, 2),
        LOD2_FAR(0.25f, 4);

        final float vertexRetention;
        final int quadStep;

        LodLevel(float vertexRetention, int quadStep) {
            this.vertexRetention = vertexRetention;
            this.quadStep = quadStep;
        }

        public int getQuadStep() {
            return quadStep;
        }
    }

    private static boolean enabled = true;

    private ChunkLodDecimator() {}

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean state) {
        enabled = state;
    }

    public static LodLevel getLodLevel(double distSq) {
        if (!enabled) return LodLevel.LOD0_FULL;

        if (distSq < 65536.0) {
            return LodLevel.LOD0_FULL;
        } else if (distSq < 262144.0) {
            return LodLevel.LOD1_SIMPLIFIED;
        } else {
            return LodLevel.LOD2_FAR;
        }
    }
}
