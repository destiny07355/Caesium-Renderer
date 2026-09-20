package destiny.renderer.chunk;

import caesium.engine.world.RenderWorld;

/**
 * Pre-packed, ready-to-upload chunk section mesh produced by background worker threads.
 *
 * <p>Separates off-heap geometry construction and vertex packing from render-thread GPU
 * allocation and handle swapping.
 */
public record CompletedMesh(
        long posKey,
        int chunkX,
        int chunkZ,
        int y,
        int version,
        RenderWorld.LayeredSectionMesh layeredMesh,
        long buildDurationNs,
        int totalVertices,
        int totalIndices,
        int totalBytes
) {
    public static CompletedMesh from(long posKey, int chunkX, int chunkZ, int y, int version,
                                     RenderWorld.LayeredSectionMesh mesh, long durationNs) {
        int verts = 0;
        int idxs = 0;
        int bytes = 0;
        if (mesh != null && mesh.layers() != null) {
            for (RenderWorld.LayerMesh lm : mesh.layers()) {
                verts += lm.vertexCount();
                idxs += lm.indexCount();
                bytes += lm.vertexCount() * 32 + lm.indexCount() * Integer.BYTES;
            }
        }
        return new CompletedMesh(posKey, chunkX, chunkZ, y, version, mesh, durationNs, verts, idxs, bytes);
    }
}
