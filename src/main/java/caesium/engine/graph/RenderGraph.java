package caesium.engine.graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The frame's execution plan: an ordered, resource-aware collection of passes. Adding
 * passes or resources invalidates the compiled plan; recompilation happens only when the
 * topology changes, never per frame (ARCHITECTURE.md §6).
 */
public final class RenderGraph {

    private final List<RenderPass> passes = new ArrayList<>();
    private final Map<String, PassResource> resources = new LinkedHashMap<>();
    private volatile GraphCompiler.CompiledGraph cached;
    private volatile RenderPass[] activeOrder = new RenderPass[0];

    public synchronized RenderGraph addResource(PassResource resource) {
        resources.put(resource.id(), resource);
        publishNewGraph();
        return this;
    }

    public synchronized RenderGraph addPass(RenderPass pass) {
        passes.add(pass);
        publishNewGraph();
        return this;
    }

    private void publishNewGraph() {
        GraphCompiler.CompiledGraph c = new GraphCompiler(this).compile();
        this.cached = c;
        this.activeOrder = c.orderArray();
    }

    /** Direct zero-overhead access to pre-baked execution passes for the render hot loop. */
    public RenderPass[] activeOrder() {
        return activeOrder;
    }

    public GraphCompiler.CompiledGraph activeGraph() {
        GraphCompiler.CompiledGraph c = cached;
        if (c == null) {
            synchronized (this) {
                if (cached == null) publishNewGraph();
                c = cached;
            }
        }
        return c;
    }

    public PassResource resource(String id) {
        return resources.get(id);
    }

    public List<RenderPass> passes() {
        return passes;
    }

    public int passCount() {
        return passes.size();
    }

    public int activePassCount() {
        int count = 0;
        for (RenderPass pass : passes) {
            if (pass.isEnabled()) {
                count++;
            }
        }
        return count;
    }

    public int resourceCount() {
        return resources.size();
    }

    /** Closes passes in reverse execution-registration order before device teardown. */
    public synchronized void close() {
        RuntimeException failure = null;
        for (int i = passes.size() - 1; i >= 0; i--) {
            try {
                passes.get(i).close();
            } catch (RuntimeException e) {
                if (failure == null) failure = e;
                else failure.addSuppressed(e);
            }
        }
        passes.clear();
        resources.clear();
        activeOrder = new RenderPass[0];
        cached = null;
        if (failure != null) throw failure;
    }

    /** Compiles the plan if the topology changed since last call, else returns the cache. */
    public GraphCompiler.CompiledGraph compile() {
        return activeGraph();
    }
}
