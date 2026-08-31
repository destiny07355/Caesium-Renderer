package caesium.engine.backend;

/**
 * A synchronization primitive between queues and frames (fence/semaphore in the Vulkan
 * backend, GLsync in the OpenGL backend). The render graph decides which barriers are
 * actually needed; this type is the per-frame hand-off between frames in flight.
 */
public interface GpuSync {

    void signal();

    void waitFor();

    void reset();
}