package caesium.engine;

import caesium.engine.backend.GpuBackend;
import caesium.engine.device.RenderDevice;
import caesium.engine.graph.RenderGraph;
import caesium.engine.scheduler.BudgetPolicy;
import caesium.engine.scheduler.CompetitivePolicy;
import caesium.engine.scheduler.ExplosionResponder;
import caesium.engine.scheduler.FrameScheduler;
import caesium.engine.scheduler.WorkStealingPool;
import caesium.engine.world.SceneManager;

/**
 * Fires up a complete Caesium engine instance: a backend, a device with frames-in-flight,
 * the render graph, the scene manager, the work pool and the frame scheduler. Owns the
 * lifecycle ({@link #start()} / {@link #stop()}). Contains no Minecraft references.
 */
public final class CaesiumEngine {

    private final GpuBackend backend;
    private final RenderDevice device;
    private final RenderGraph graph = new RenderGraph();
    private final SceneManager scene = new SceneManager();
    private final WorkStealingPool pool;
    private final BudgetPolicy policy;
    private final CompetitivePolicy competitive;
    private final ExplosionResponder responder;
    private final FrameScheduler scheduler;

    public CaesiumEngine(GpuBackend backend, int meshingThreads, int framesInFlight) {
        this(backend, meshingThreads, framesInFlight, new BudgetPolicy(), new CompetitivePolicy());
    }

    public CaesiumEngine(GpuBackend backend, int meshingThreads, int framesInFlight,
                         BudgetPolicy policy, CompetitivePolicy competitive) {
        if (backend == null) {
            throw new IllegalArgumentException("backend must not be null");
        }
        this.backend = backend;
        this.device = new RenderDevice(backend, framesInFlight);
        this.pool = new WorkStealingPool(Math.max(1, meshingThreads), "caesium-mesh");
        this.policy = policy;
        this.competitive = competitive;
        this.responder = new ExplosionResponder();
        this.scheduler = new FrameScheduler(device, graph, pool, policy, competitive, responder);
    }

    public void start() {
        device.start();
    }

    public void stop() {
        device.stop();
        pool.close();
    }

    public GpuBackend backend() {
        return backend;
    }

    public RenderDevice device() {
        return device;
    }

    public RenderGraph graph() {
        return graph;
    }

    public SceneManager scene() {
        return scene;
    }

    public FrameScheduler scheduler() {
        return scheduler;
    }
}