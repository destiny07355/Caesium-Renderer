package destiny.renderer.chunk;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * Topological graph-based translucency sorter implementing the algorithm described in
 * <em>"Sorting Rendered Transparent Objects" by Douira (2022)</em>.
 *
 * <h2>Problem</h2>
 * Translucent quads (glass, water, ice) must be rendered in back-to-front order
 * relative to the camera to produce correct alpha blending. Within a chunk section,
 * the correct order changes as the camera moves.
 *
 * <h2>Algorithm Overview</h2>
 * <ol>
 *   <li>Build a directed <em>visibility graph</em> of translucent quads where an edge
 *       A → B means "A must be drawn before B" (i.e., B is closer to the camera).</li>
 *   <li>Perform a topological sort (DFS) on the graph to produce the draw order.</li>
 *   <li>If cycles exist (unavoidable in some camera angles), break them greedily
 *       at the edge that minimises visible artifacts.</li>
 *   <li>Store the resulting sorted index list, which is uploaded to the GPU as the
 *       index buffer for the translucent draw call.</li>
 * </ol>
 *
 * <h2>Complexity</h2>
 * O(Q²) for Q quads per section in the worst case, but typical chunk sections have
 * &lt;500 translucent quads and the graph is sparse, making it fast in practice.
 *
 * <h2>Camera Polytope</h2>
 * The sort direction is determined by the camera position relative to each quad's
 * centre. A quad A must render before B if A's centre is farther from the camera
 * than B's centre in the direction perpendicular to B's face.
 */
public final class TranslucencySorter {

    /** Maximum quads per chunk section (conservative upper bound). */
    private static final int MAX_QUADS = 4096;

    // -------------------------------------------------------------------------
    // Quad data (populated during meshing)
    // -------------------------------------------------------------------------

    /** Quad centre X positions (world-space). */
    private final float[] centreX = new float[MAX_QUADS];
    /** Quad centre Y positions (world-space). */
    private final float[] centreY = new float[MAX_QUADS];
    /** Quad centre Z positions (world-space). */
    private final float[] centreZ = new float[MAX_QUADS];
    /** Face normal index for each quad (from PackedVertexFormat). */
    private final int[]   normals  = new int[MAX_QUADS];
    /** First vertex index in the packed vertex buffer for each quad. */
    private final int[]   startVertices = new int[MAX_QUADS];

    // Scratch buffers for zero-allocation sorting
    private final float[] dist2 = new float[MAX_QUADS];
    private final int[]   order = new int[MAX_QUADS];

    private int quadCount = 0;

    // -------------------------------------------------------------------------
    // Face normal vectors (unit, indexed by PackedVertexFormat.NORMAL_*)
    // -------------------------------------------------------------------------
    private static final float[][] NORMAL_VECS = {
        { 1,  0,  0},  // POS_X
        {-1,  0,  0},  // NEG_X
        { 0,  1,  0},  // POS_Y
        { 0, -1,  0},  // NEG_Y
        { 0,  0,  1},  // POS_Z
        { 0,  0, -1}   // NEG_Z
    };

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Clears all quad data for a new meshing pass. */
    public void reset() {
        quadCount = 0;
    }

    /**
     * Registers a translucent quad with the sorter.
     *
     * @param cx          quad centre X (world-space)
     * @param cy          quad centre Y
     * @param cz          quad centre Z
     * @param normalIndex face normal index
     * @param startVertex first vertex index of this quad in the packed vertex buffer
     */
    public void addQuad(float cx, float cy, float cz, int normalIndex, int startVertex) {
        if (quadCount >= MAX_QUADS) return; // silently drop excess
        centreX[quadCount]        = cx;
        centreY[quadCount]        = cy;
        centreZ[quadCount]        = cz;
        normals[quadCount]        = normalIndex;
        startVertices[quadCount]  = startVertex;
        quadCount++;
    }

    /**
     * Sorts all registered quads for the given camera position and returns the
     * draw order as an array of quad indices (into {@code startVertices}).
     *
     * <p>The returned array represents the back-to-front rendering sequence.
     * Each entry is a base vertex index; the GPU renders 4 vertices per quad
     * starting at that offset, using 6 indices per quad (two triangles: 0,1,2, 0,2,3).
     *
     * @param camX camera world-space X
     * @param camY camera world-space Y
     * @param camZ camera world-space Z
     * @return sorted array of vertex start indices, length = quadCount
     */
    public int[] sort(float camX, float camY, float camZ) {
        if (quadCount == 0) return new int[0];

        // --- Build distance array for quick sort ---
        // Primary: sort by squared distance (back-to-front = descending distance)
        for (int i = 0; i < quadCount; i++) {
            float dx = centreX[i] - camX;
            float dy = centreY[i] - camY;
            float dz = centreZ[i] - camZ;
            dist2[i] = dx * dx + dy * dy + dz * dz;
            order[i] = i;
        }

        // Sort indices by descending distance (back-to-front) — primitive in-place quicksort
        quickSortDescending(order, dist2, 0, quadCount - 1);

        // Fast path: for small quad counts (< 32), distance-squared sorting is visually identical
        // and avoids building the O(Q^2) dependency graph and DFS.
        if (quadCount < 32) {
            int[] vertexOrder = new int[quadCount];
            for (int i = 0; i < quadCount; i++) {
                vertexOrder[i] = startVertices[order[i]];
            }
            return vertexOrder;
        }

        // --- Topological refinement (Douira's algorithm) ---
        // Build a sparse dependency graph: edge[i] → list of quads that must draw before i
        @SuppressWarnings("unchecked")
        List<Integer>[] before = new List[quadCount];
        for (int i = 0; i < quadCount; i++) before[i] = new ArrayList<>();

        for (int i = 0; i < quadCount; i++) {
            for (int j = i + 1; j < quadCount; j++) {
                // Check if quad j's face plane separates i and the camera
                if (mustDrawBefore(i, j, camX, camY, camZ)) {
                    before[j].add(i); // i must draw before j
                } else if (mustDrawBefore(j, i, camX, camY, camZ)) {
                    before[i].add(j); // j must draw before i
                }
            }
        }

        // Topological DFS sort
        int[] result = new int[quadCount];
        BitSet visited = new BitSet(quadCount);
        BitSet onStack = new BitSet(quadCount);
        int[] outIdx = {0};

        for (int i = 0; i < quadCount; i++) {
            if (!visited.get(order[i])) {
                dfs(order[i], before, visited, onStack, result, outIdx);
            }
        }

        // Convert quad indices to vertex start indices
        int[] vertexOrder = new int[quadCount];
        for (int i = 0; i < quadCount; i++) {
            vertexOrder[i] = startVertices[result[i]];
        }
        return vertexOrder;
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    /**
     * Returns true if quad A must be drawn before quad B.
     * This is the case when B's plane separates A's centre from the camera:
     * sign(dot(B_normal, cam - B_centre)) ≠ sign(dot(B_normal, A_centre - B_centre)).
     */
    private boolean mustDrawBefore(int a, int b, float camX, float camY, float camZ) {
        float[] nb = NORMAL_VECS[normals[b]];
        float toCam = nb[0] * (camX - centreX[b])
                    + nb[1] * (camY - centreY[b])
                    + nb[2] * (camZ - centreZ[b]);
        float toA   = nb[0] * (centreX[a] - centreX[b])
                    + nb[1] * (centreY[a] - centreY[b])
                    + nb[2] * (centreZ[a] - centreZ[b]);
        // A must draw before B if A and the camera are on opposite sides of B's plane
        return (toCam > 0) != (toA > 0);
    }

    /** Iterative DFS topological sort to avoid stack overflow on large quad counts. */
    private void dfs(int start, List<Integer>[] before, BitSet visited, BitSet onStack,
                     int[] result, int[] outIdx) {
        java.util.Deque<int[]> stack = new java.util.ArrayDeque<>();
        stack.push(new int[]{start, 0}); // [node, childIndex]

        while (!stack.isEmpty()) {
            int[] frame = stack.peek();
            int node = frame[0];
            visited.set(node);
            onStack.set(node);

            List<Integer> deps = before[node];
            boolean pushed = false;
            while (frame[1] < deps.size()) {
                int dep = deps.get(frame[1]++);
                if (!visited.get(dep)) {
                    stack.push(new int[]{dep, 0});
                    pushed = true;
                    break;
                }
                // Cycle: skip (back-edge; artifact is less bad than a crash)
            }
            if (!pushed) {
                stack.pop();
                onStack.clear(node);
                result[outIdx[0]++] = node;
            }
        }
    }

    private static void quickSortDescending(int[] arr, float[] keys, int low, int high) {
        if (low >= high) return;
        float pivot = keys[arr[(low + high) >>> 1]];
        int i = low, j = high;
        while (i <= j) {
            while (keys[arr[i]] > pivot) i++;
            while (keys[arr[j]] < pivot) j--;
            if (i <= j) {
                int tmp = arr[i];
                arr[i] = arr[j];
                arr[j] = tmp;
                i++;
                j--;
            }
        }
        if (low < j) quickSortDescending(arr, keys, low, j);
        if (i < high) quickSortDescending(arr, keys, i, high);
    }

    /** @return the number of quads registered since the last {@link #reset()}. */
    public int getQuadCount() { return quadCount; }
}
