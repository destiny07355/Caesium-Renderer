package caesium.engine.graph;

import caesium.engine.backend.GpuCommandEncoder;
import caesium.engine.device.FrameContext;

import java.util.Set;

/**
 * One stage of the frame (depth, opaque terrain, particles, post, ...). A pass declares
 * what it reads, writes and otherwise uses, plus its explicit dependencies. The graph
 * compiler derives ordering, synchronization and pass culling from these declarations.
 */
public interface RenderPass {

    String id();

    String name();

    Set<PassResource> reads();

    Set<PassResource> writes();

    /** Union of {@link #reads()} and {@link #writes()} plus any intermediate resources. */
    Set<PassResource> resources();

    /** Pass ids that must complete before this one. */
    Set<String> dependencies();

    /** Whether this pass has anything to draw this frame (drives pass culling). */
    boolean hasWork(FrameContext frame);

    /** Optional CPU-side preparation (build draw lists, upload staging). */
    default void prepare(FrameContext frame) {
    }

    /** Records the pass's GPU commands into the encoder. */
    default void execute(GpuCommandEncoder encoder, FrameContext frame) {
    }
}