package caesium.engine.backend;

/**
 * A command submission queue. The engine tracks graphics, compute and transfer queues;
 * the scheduler decides which queue executes a pass.
 */
public interface GpuQueue {

    String name();

    /** Creates a fresh command encoder for one submission batch. */
    GpuCommandEncoder createEncoder();

    /** Submits the encoder's commands to this queue. */
    void submit(GpuCommandEncoder encoder);

    /** Blocks the calling thread until the queue has drained. */
    void waitIdle();
}