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
    private GraphCompiler.CompiledGraph cached;

    public RenderGraph addResource(PassResource resource) {
        resources.put(resource.id(), resource);
        cached = null;
        return this;
    }

    public RenderGraph addPass(RenderPass pass) {
        passes.add(pass);
        cached = null;
        return this;
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

    public int resourceCount() {
        return resources.size();
    }

    /** Compiles the plan if the topology changed since last call, else returns the cache. */
    public GraphCompiler.CompiledGraph compile() {
        GraphCompiler.CompiledGraph c = cached;
        if (c == null) {
            c = new GraphCompiler(this).compile();
            cached = c;
        }
        return c;
    }
}