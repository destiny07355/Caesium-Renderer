package caesium.engine.graph;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Partitions a topological order of passes into stages of independent passes. Passes in
 * the same stage share no resources and have no dependency on each other, so their CPU
 * preparation (and, on capable backends, their GPU recording) may run in parallel.
 */
final class PassScheduler {

    private PassScheduler() {
    }

    static List<List<RenderPass>> schedule(List<RenderPass> order) {
        List<List<RenderPass>> stages = new ArrayList<>();
        List<RenderPass> current = new ArrayList<>();
        Set<PassResource> used = new java.util.HashSet<>();
        for (RenderPass pass : order) {
            boolean conflicts = false;
            for (RenderPass already : current) {
                if (already.dependencies().contains(pass.id()) || pass.dependencies().contains(already.id())) {
                    conflicts = true;
                    break;
                }
            }
            if (!conflicts) {
                for (PassResource r : pass.resources()) {
                    if (used.contains(r)) {
                        conflicts = true;
                        break;
                    }
                }
            }
            if (conflicts) {
                stages.add(List.copyOf(current));
                current.clear();
                used.clear();
            }
            current.add(pass);
            used.addAll(pass.resources());
        }
        if (!current.isEmpty()) {
            stages.add(List.copyOf(current));
        }
        return stages;
    }
}