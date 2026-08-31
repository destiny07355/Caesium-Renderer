package caesium.engine.scheduler;

import caesium.engine.backend.GpuCommandEncoder;
import caesium.engine.device.EngineStatus;
import caesium.engine.device.FrameContext;
import caesium.engine.device.RenderDevice;
import caesium.engine.graph.GraphCompiler;
import caesium.engine.graph.RenderGraph;
import caesium.engine.graph.RenderPass;
import caesium.engine.world.DeltaCommand;

/**
 * Drives one frame: consumes {@link FrameInput}, feeds explosion events to the responder,
 * executes the compiled render graph into the device's current frame, and records timing
 * into {@link EngineStatus} for the budget policy and the overlay (ARCHITECTURE.md §13).
 */
public final class FrameScheduler {

    private final RenderDevice device;
    private final RenderGraph graph;
    private final WorkStealingPool pool;
    private final BudgetPolicy policy;
    private final CompetitivePolicy competitive;
    private final ExplosionResponder responder;

    public FrameScheduler(RenderDevice device, RenderGraph graph, WorkStealingPool pool,
                          BudgetPolicy policy, CompetitivePolicy competitive,
                          ExplosionResponder responder) {
        this.device = device;
        this.graph = graph;
        this.pool = pool;
        this.policy = policy;
        this.competitive = competitive;
        this.responder = responder;
    }

    public RenderDevice device() {
        return device;
    }

    public RenderGraph graph() {
        return graph;
    }

    public WorkStealingPool pool() {
        return pool;
    }

    public BudgetPolicy policy() {
        return policy;
    }

    public CompetitivePolicy competitive() {
        return competitive;
    }

    public ExplosionResponder responder() {
        return responder;
    }

    /** Consumes frame-level inputs before any pass runs. Never blocks. */
    public void beginFrame(FrameInput input) {
        EngineStatus status = device.status();
        status.recordFrameStarted(input.deltaMillis());
        for (FrameInput.Explosion explosion : input.explosions()) {
            responder.onEvent(new DeltaCommand.Explosion(
                    explosion.x(), explosion.y(), explosion.z(),
                    explosion.radius(), explosion.timeMs()));
        }
    }

    /** Executes the compiled graph into the device's frame-in-flight slot. */
    public void execute(FrameInput input) {
        long start = System.nanoTime();
        GraphCompiler.CompiledGraph compiled = graph.compile();

        FrameContext frame = device.beginFrame();
        GpuCommandEncoder encoder = frame.encoder();
        encoder.begin();
        for (RenderPass pass : compiled.order()) {
            if (!pass.hasWork(frame)) {
                continue; // pass culling
            }
            pass.prepare(frame);
            pass.execute(encoder, frame);
        }
        encoder.end();
        device.endFrame(frame);

        device.status().recordFrameFinished(System.nanoTime() - start);
    }

    /** Post-frame policy bookkeeping (background admission decisions for meshing). */
    public void endFrame(FrameInput input) {
        EngineStatus status = device.status();
        int budget = policy.admitBackground(status.backgroundJobs()) ? 1 : 0;
        if (input.competitive()) {
            budget = status.backgroundJobs() > 0 && competitive.backgroundThrottle() < 0.1f ? 0 : budget;
        }
        status.recordBackgroundAdmitted(budget > 0);
    }
}