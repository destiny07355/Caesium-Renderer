package caesium.engine.backend;

/**
 * An opaque compiled render pipeline (shader program + fixed-function state).
 */
public interface GpuPipeline {

    long handle();

    void destroy();
}