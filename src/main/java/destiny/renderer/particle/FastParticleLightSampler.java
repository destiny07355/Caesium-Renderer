package destiny.renderer.particle;

import destiny.renderer.light.LightSystem;

public final class FastParticleLightSampler {

    private FastParticleLightSampler() {}

    public static int getPackedLight(double x, double y, double z) {
        return LightSystem.getPackedLight(x, y, z);
    }

    public static int getPackedLight(int bx, int by, int bz) {
        return LightSystem.getPackedLight(bx, by, bz);
    }

    public static void clear() {
        LightSystem.clear();
    }
}
