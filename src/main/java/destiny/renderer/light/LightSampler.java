package destiny.renderer.light;

public interface LightSampler {
    int sample(double x, double y, double z);
    int sample(int blockX, int blockY, int blockZ);
    void clear();
}
