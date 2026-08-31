package caesium.engine.backend;

/**
 * A GPU image (texture, attachment or storage target). Layout management is the render
 * graph's job; the backend only exposes the resource and its dimensions.
 */
public interface GpuImage {

    enum Format {
        RGBA8,
        RGBA16F,
        DEPTH24,
        DEPTH32F
    }

    Format format();

    int width();

    int height();

    long handle();

    void destroy();
}