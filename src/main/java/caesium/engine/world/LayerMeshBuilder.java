package caesium.engine.world;

import java.util.Arrays;

/** Growable, allocation-amortized collector for one section render layer. */
public final class LayerMeshBuilder {
    private final RenderWorld.TerrainLayer layer;
    private float[] positions = new float[768];
    private float[] uvs = new float[512];
    private int[] colors = new int[256];
    private int[] lights = new int[256];
    private byte[] normals = new byte[256];
    private int[] indices = new int[384];
    private int vertices;
    private int indexCount;

    public LayerMeshBuilder(RenderWorld.TerrainLayer layer) {
        if (layer == null) throw new IllegalArgumentException("layer is required");
        this.layer = layer;
    }

    public void appendQuad(float[] xyz, float[] uv, int argb, int packedLight, byte normal) {
        if (xyz.length != 12 || uv.length != 8) {
            throw new IllegalArgumentException("a quad requires four positions and four UVs");
        }
        ensureVertices(vertices + 4);
        ensureIndices(indexCount + 6);
        System.arraycopy(xyz, 0, positions, vertices * 3, 12);
        System.arraycopy(uv, 0, uvs, vertices * 2, 8);
        Arrays.fill(colors, vertices, vertices + 4, argb);
        Arrays.fill(lights, vertices, vertices + 4, packedLight);
        Arrays.fill(normals, vertices, vertices + 4, normal);
        int base = vertices;
        indices[indexCount++] = base;
        indices[indexCount++] = base + 1;
        indices[indexCount++] = base + 2;
        indices[indexCount++] = base;
        indices[indexCount++] = base + 2;
        indices[indexCount++] = base + 3;
        vertices += 4;
    }

    public int vertexCount() { return vertices; }
    public int indexCount() { return indexCount; }
    public boolean isEmpty() { return vertices == 0; }
    public RenderWorld.TerrainLayer layer() { return layer; }

    public void reset() {
        this.vertices = 0;
        this.indexCount = 0;
    }

    public RenderWorld.LayerMesh build() {
        float[] posCopy = Arrays.copyOf(positions, vertices * 3);
        float[] uvCopy = Arrays.copyOf(uvs, vertices * 2);
        int[] colorCopy = Arrays.copyOf(colors, vertices);
        int[] lightCopy = Arrays.copyOf(lights, vertices);
        byte[] normalCopy = Arrays.copyOf(normals, vertices);
        int[] indexCopy = Arrays.copyOf(indices, indexCount);

        // Pre-pack 32-byte layout directly on the worker thread off the render thread
        int vertBytes = vertices * TerrainVertexPacker.STRIDE;
        java.nio.ByteBuffer packedV = java.nio.ByteBuffer.allocateDirect(vertBytes)
                .order(java.nio.ByteOrder.nativeOrder());
        for (int v = 0; v < vertices; v++) {
            int p = v * 3;
            int u = v * 2;
            packedV.putFloat(posCopy[p]);
            packedV.putFloat(posCopy[p + 1]);
            packedV.putFloat(posCopy[p + 2]);
            packedV.putFloat(uvCopy[u]);
            packedV.putFloat(uvCopy[u + 1]);
            int argb = colorCopy[v];
            packedV.put((byte) (argb >>> 16));
            packedV.put((byte) (argb >>> 8));
            packedV.put((byte) argb);
            packedV.put((byte) (argb >>> 24));
            packedV.putInt(lightCopy[v]);
            packedV.put(normalCopy[v]).put((byte) 0).put((byte) 0).put((byte) 0);
        }
        packedV.flip();

        int idxBytes = indexCount * Integer.BYTES;
        java.nio.ByteBuffer packedI = java.nio.ByteBuffer.allocateDirect(idxBytes)
                .order(java.nio.ByteOrder.nativeOrder());
        for (int i = 0; i < indexCount; i++) {
            packedI.putInt(indexCopy[i]);
        }
        packedI.flip();

        return new RenderWorld.LayerMesh(layer, posCopy, uvCopy, colorCopy, lightCopy, normalCopy, indexCopy,
                packedV, packedI);
    }

    private void ensureVertices(int required) {
        if (required <= colors.length) return;
        int capacity = grow(colors.length, required);
        positions = Arrays.copyOf(positions, capacity * 3);
        uvs = Arrays.copyOf(uvs, capacity * 2);
        colors = Arrays.copyOf(colors, capacity);
        lights = Arrays.copyOf(lights, capacity);
        normals = Arrays.copyOf(normals, capacity);
    }

    private void ensureIndices(int required) {
        if (required > indices.length) indices = Arrays.copyOf(indices, grow(indices.length, required));
    }

    private static int grow(int current, int required) {
        int result = current;
        while (result < required) result = Math.addExact(result, Math.max(16, result >> 1));
        return result;
    }
}
