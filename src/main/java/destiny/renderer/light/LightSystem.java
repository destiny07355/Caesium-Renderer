package destiny.renderer.light;

public final class LightSystem {

    private static volatile LightSampler activeSampler = new FastLightSampler();

    private LightSystem() {}

    public static int getPackedLight(double x, double y, double z) {
        return activeSampler.sample(x, y, z);
    }

    public static int getPackedLight(int bx, int by, int bz) {
        return activeSampler.sample(bx, by, bz);
    }

    public static void setSampler(LightSampler sampler) {
        activeSampler = sampler != null ? sampler : new FastLightSampler();
    }

    public static void clear() {
        activeSampler.clear();
    }
}
