package caesium.engine.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compiles a {@link RenderGraph} into an executable plan: a topological pass order
 * (honouring explicit dependencies and resource-write ordering), a set of layout/access
 * barriers between consecutive users of every resource, and a parallel schedule of
 * independent passes. The compiled plan is cached by {@link RenderGraph#compile()} and
 * only rebuilt when the topology changes.
 */
public final class GraphCompiler {

    private final RenderGraph graph;

    public GraphCompiler(RenderGraph graph) {
        this.graph = graph;
    }

    public CompiledGraph compile() {
        List<RenderPass> order = topoOrder();
        List<Barrier> barriers = deriveBarriers(order);
        List<List<RenderPass>> stages = PassScheduler.schedule(order);
        return new CompiledGraph(order, stages, barriers);
    }

    // -------------------------------------------------------------------------

    private List<RenderPass> topoOrder() {
        Map<String, RenderPass> byId = new LinkedHashMap<>();
        for (RenderPass p : graph.passes()) {
            byId.put(p.id(), p);
        }

        Map<RenderPass, Set<RenderPass>> edges = new LinkedHashMap<>();
        Map<RenderPass, Integer> indegree = new LinkedHashMap<>();
        for (RenderPass p : graph.passes()) {
            edges.put(p, new HashSet<>());
            indegree.put(p, 0);
        }

        // Explicit dependencies: dep -> p.
        for (RenderPass p : graph.passes()) {
            for (String depId : p.dependencies()) {
                RenderPass dep = byId.get(depId);
                if (dep != null) {
                    addEdge(edges, indegree, dep, p);
                }
            }
        }

        // Resource-write ordering: a pass writing a resource must run after every earlier
        // pass that also touches it (readers of the previous value included).
        Map<PassResource, List<RenderPass>> users = new LinkedHashMap<>();
        for (RenderPass p : graph.passes()) {
            for (PassResource r : p.resources()) {
                users.computeIfAbsent(r, k -> new ArrayList<>()).add(p);
            }
        }
        for (List<RenderPass> list : users.values()) {
            for (int i = 0; i < list.size(); i++) {
                RenderPass writer = list.get(i);
                for (int j = 0; j < i; j++) {
                    addEdge(edges, indegree, list.get(j), writer);
                }
            }
        }

        // Kahn's algorithm.
        ArrayDeque<RenderPass> ready = new ArrayDeque<>();
        for (RenderPass p : graph.passes()) {
            if (indegree.get(p) == 0) {
                ready.add(p);
            }
        }
        List<RenderPass> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            RenderPass p = ready.poll();
            order.add(p);
            for (RenderPass next : edges.get(p)) {
                if (indegree.merge(next, -1, Integer::sum) == 0) {
                    ready.add(next);
                }
            }
        }
        if (order.size() != graph.passes().size()) {
            throw new IllegalStateException("Caesium: render graph contains a cycle");
        }
        return order;
    }

    private static void addEdge(Map<RenderPass, Set<RenderPass>> edges,
                                Map<RenderPass, Integer> indegree,
                                RenderPass from,
                                RenderPass to) {
        if (edges.get(from).add(to)) {
            indegree.merge(to, 1, Integer::sum);
        }
    }

    // -------------------------------------------------------------------------

    private List<Barrier> deriveBarriers(List<RenderPass> order) {
        Map<PassResource, String> lastWriter = new HashMap<>();
        Map<PassResource, PassResource.Layout> currentLayout = new HashMap<>();
        List<Barrier> barriers = new ArrayList<>();

        for (RenderPass pass : order) {
            for (PassResource r : pass.resources()) {
                PassResource.Layout required = requiredLayout(pass, r);
                PassResource.Layout before = currentLayout.getOrDefault(r, r.layout());
                if (before != required) {
                    String src = lastWriter.getOrDefault(r, "start");
                    barriers.add(new Barrier(r, src, pass.name(), before, required));
                    currentLayout.put(r, required);
                }
            }
            for (PassResource r : pass.writes()) {
                lastWriter.put(r, pass.name());
            }
        }
        return barriers;
    }

    private static PassResource.Layout requiredLayout(RenderPass pass, PassResource r) {
        return pass.writes().contains(r) ? PassResource.Layout.GENERAL
                                        : PassResource.Layout.SHADER_READ;
    }

    // -------------------------------------------------------------------------

    /** A synchronization point between two passes over one resource. */
    public record Barrier(PassResource resource,
                          String srcPass,
                          String dstPass,
                          PassResource.Layout from,
                          PassResource.Layout to) {

        @Override
        public String toString() {
            return "Barrier[" + resource.id() + "] " + from + " -> " + to
                    + " (" + srcPass + " -> " + dstPass + ")";
        }
    }

    /** The immutable, cached result of compiling the graph. */
    public static final class CompiledGraph {

        private final List<RenderPass> order;
        private final RenderPass[] orderArray;
        private final List<List<RenderPass>> stages;
        private final List<Barrier> barriers;

        CompiledGraph(List<RenderPass> order, List<List<RenderPass>> stages, List<Barrier> barriers) {
            this.order = List.copyOf(order);
            this.orderArray = order.toArray(new RenderPass[0]);
            this.stages = List.copyOf(stages);
            this.barriers = List.copyOf(barriers);
        }

        public List<RenderPass> order() {
            return order;
        }

        public RenderPass[] orderArray() {
            return orderArray;
        }

        public List<List<RenderPass>> stages() {
            return stages;
        }

        public List<Barrier> barriers() {
            return barriers;
        }
    }
}