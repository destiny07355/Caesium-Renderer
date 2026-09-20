package destiny.renderer.chunk;

import java.util.Arrays;

/** Allocation-controlled back-to-front sorter for translucent section quads. */
public final class TranslucencySorter {
    private static final int MAX_QUADS = 4096;
    private static final int MAX_DEPENDENCY_EDGES = 262_144;
    private static final float[][] NORMALS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0},
            {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    private final float[] centreX = new float[MAX_QUADS];
    private final float[] centreY = new float[MAX_QUADS];
    private final float[] centreZ = new float[MAX_QUADS];
    private final int[] normals = new int[MAX_QUADS];
    private final int[] startVertices = new int[MAX_QUADS];
    private final float[] distances = new float[MAX_QUADS];
    private final int[] order = new int[MAX_QUADS];
    private final int[] dependencyCounts = new int[MAX_QUADS];
    private final int[] dependencyOffsets = new int[MAX_QUADS + 1];
    private final int[] dependencyWrites = new int[MAX_QUADS];
    private int[] dependencyEdges = new int[4096];
    private final byte[] visitState = new byte[MAX_QUADS];
    private final int[] dfsNodes = new int[MAX_QUADS];
    private final int[] dfsEdges = new int[MAX_QUADS];
    private final int[] topoOrder = new int[MAX_QUADS];
    private final int[] cachedVertexOrder = new int[MAX_QUADS];

    private int quadCount;
    private int dataVersion;
    private int cachedDataVersion = -1;
    private int cachedCount;
    private int sortGeneration;
    private int cachedCamXBits;
    private int cachedCamYBits;
    private int cachedCamZBits;

    public void reset() {
        quadCount = 0;
        dataVersion++;
    }

    public void addQuad(float x, float y, float z, int normal, int startVertex) {
        if (quadCount >= MAX_QUADS) return;
        centreX[quadCount] = x;
        centreY[quadCount] = y;
        centreZ[quadCount] = z;
        normals[quadCount] = normal;
        startVertices[quadCount] = startVertex;
        quadCount++;
        dataVersion++;
    }

    /** Compatibility wrapper. Prefer sortInto on hot paths. */
    public int[] sort(float camX, float camY, float camZ) {
        int[] result = new int[quadCount];
        sortInto(camX, camY, camZ, result);
        return result;
    }

    /** Writes the sorted vertex starts into caller-owned storage and returns the count. */
    public int sortInto(float camX, float camY, float camZ, int[] destination) {
        if (destination.length < quadCount) {
            throw new IllegalArgumentException("destination is smaller than quad count");
        }
        if (quadCount == 0) return 0;

        int xBits = Float.floatToIntBits(camX);
        int yBits = Float.floatToIntBits(camY);
        int zBits = Float.floatToIntBits(camZ);
        if (cachedDataVersion == dataVersion && cachedCamXBits == xBits
                && cachedCamYBits == yBits && cachedCamZBits == zBits) {
            System.arraycopy(cachedVertexOrder, 0, destination, 0, cachedCount);
            return cachedCount;
        }

        for (int i = 0; i < quadCount; i++) {
            float dx = centreX[i] - camX;
            float dy = centreY[i] - camY;
            float dz = centreZ[i] - camZ;
            distances[i] = dx * dx + dy * dy + dz * dz;
            order[i] = i;
        }
        quickSortDescending(order, distances, 0, quadCount - 1);

        if (quadCount < 32) {
            for (int i = 0; i < quadCount; i++) cachedVertexOrder[i] = startVertices[order[i]];
        } else {
            if (buildDependencies(camX, camY, camZ)) {
                int count = topologicalSort();
                for (int i = 0; i < count; i++) cachedVertexOrder[i] = startVertices[topoOrder[i]];
            } else {
                // Pathological overlap must not turn one section into an unbounded memory spike.
                for (int i = 0; i < quadCount; i++) cachedVertexOrder[i] = startVertices[order[i]];
            }
        }

        cachedCount = quadCount;
        cachedDataVersion = dataVersion;
        cachedCamXBits = xBits;
        cachedCamYBits = yBits;
        cachedCamZBits = zBits;
        sortGeneration++;
        System.arraycopy(cachedVertexOrder, 0, destination, 0, cachedCount);
        return cachedCount;
    }

    private boolean buildDependencies(float camX, float camY, float camZ) {
        Arrays.fill(dependencyCounts, 0, quadCount, 0);
        int edgeCount = 0;
        for (int i = 0; i < quadCount; i++) {
            for (int j = i + 1; j < quadCount; j++) {
                int target = dependencyTarget(i, j, camX, camY, camZ);
                if (target >= 0) {
                    dependencyCounts[target]++;
                    edgeCount++;
                    if (edgeCount > MAX_DEPENDENCY_EDGES) return false;
                }
            }
        }
        ensureEdgeCapacity(edgeCount);
        dependencyOffsets[0] = 0;
        for (int i = 0; i < quadCount; i++) {
            dependencyOffsets[i + 1] = dependencyOffsets[i] + dependencyCounts[i];
            dependencyWrites[i] = dependencyOffsets[i];
        }
        for (int i = 0; i < quadCount; i++) {
            for (int j = i + 1; j < quadCount; j++) {
                int target = dependencyTarget(i, j, camX, camY, camZ);
                if (target == j) dependencyEdges[dependencyWrites[j]++] = i;
                else if (target == i) dependencyEdges[dependencyWrites[i]++] = j;
            }
        }
        return true;
    }

    private int dependencyTarget(int i, int j, float camX, float camY, float camZ) {
        if (mustDrawBefore(i, j, camX, camY, camZ)) return j;
        if (mustDrawBefore(j, i, camX, camY, camZ)) return i;
        return -1;
    }

    private int topologicalSort() {
        Arrays.fill(visitState, 0, quadCount, (byte) 0);
        int out = 0;
        for (int rootIndex = 0; rootIndex < quadCount; rootIndex++) {
            int root = order[rootIndex];
            if (visitState[root] != 0) continue;
            int stackSize = 1;
            dfsNodes[0] = root;
            dfsEdges[0] = dependencyOffsets[root];
            visitState[root] = 1;
            while (stackSize > 0) {
                int frame = stackSize - 1;
                int node = dfsNodes[frame];
                int edge = dfsEdges[frame];
                int end = dependencyOffsets[node + 1];
                boolean pushed = false;
                while (edge < end) {
                    int dependency = dependencyEdges[edge++];
                    dfsEdges[frame] = edge;
                    if (visitState[dependency] == 0) {
                        dfsNodes[stackSize] = dependency;
                        dfsEdges[stackSize] = dependencyOffsets[dependency];
                        visitState[dependency] = 1;
                        stackSize++;
                        pushed = true;
                        break;
                    }
                }
                if (!pushed) {
                    stackSize--;
                    visitState[node] = 2;
                    topoOrder[out++] = node;
                }
            }
        }
        return out;
    }

    private boolean mustDrawBefore(int a, int b, float camX, float camY, float camZ) {
        float[] n = NORMALS[Math.max(0, Math.min(NORMALS.length - 1, normals[b]))];
        float toCamera = n[0] * (camX - centreX[b]) + n[1] * (camY - centreY[b])
                + n[2] * (camZ - centreZ[b]);
        float toA = n[0] * (centreX[a] - centreX[b]) + n[1] * (centreY[a] - centreY[b])
                + n[2] * (centreZ[a] - centreZ[b]);
        return (toCamera > 0) != (toA > 0);
    }

    private void ensureEdgeCapacity(int required) {
        if (dependencyEdges.length >= required) return;
        int capacity = dependencyEdges.length;
        while (capacity < required) capacity = Math.max(capacity + 1, capacity << 1);
        dependencyEdges = new int[capacity];
    }

    private static void quickSortDescending(int[] values, float[] keys, int low, int high) {
        if (low >= high) return;
        float pivot = keys[values[(low + high) >>> 1]];
        int i = low;
        int j = high;
        while (i <= j) {
            while (keys[values[i]] > pivot) i++;
            while (keys[values[j]] < pivot) j--;
            if (i <= j) {
                int value = values[i];
                values[i++] = values[j];
                values[j--] = value;
            }
        }
        if (low < j) quickSortDescending(values, keys, low, j);
        if (i < high) quickSortDescending(values, keys, i, high);
    }

    public int getQuadCount() { return quadCount; }
    public int getSortGeneration() { return sortGeneration; }
}
