package caesium.engine.world;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Packs engine-neutral baked terrain into the shared OpenGL/Vulkan vertex contract. */
public final class TerrainVertexPacker {
    public static final int STRIDE = 32;

    private TerrainVertexPacker() {
    }

    public static ByteBuffer pack(RenderWorld.LayerMesh mesh, ByteBuffer reuse) {
        int bytes = Math.multiplyExact(mesh.vertexCount(), STRIDE);
        ByteBuffer out = reuse != null && reuse.capacity() >= bytes
                ? reuse.clear() : ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
        out.order(ByteOrder.nativeOrder()).limit(bytes);
        for (int vertex = 0; vertex < mesh.vertexCount(); vertex++) {
            int position = vertex * 3;
            int uv = vertex * 2;
            out.putFloat(mesh.positions()[position]);
            out.putFloat(mesh.positions()[position + 1]);
            out.putFloat(mesh.positions()[position + 2]);
            out.putFloat(mesh.uvs()[uv]);
            out.putFloat(mesh.uvs()[uv + 1]);
            putRgba(out, mesh.colors()[vertex]);
            out.putInt(mesh.lights()[vertex]);
            out.put(mesh.normals()[vertex]).put((byte) 0).put((byte) 0).put((byte) 0);
        }
        return out.flip();
    }

    private static void putRgba(ByteBuffer out, int argb) {
        out.put((byte) (argb >>> 16));
        out.put((byte) (argb >>> 8));
        out.put((byte) argb);
        out.put((byte) (argb >>> 24));
    }
}
