package caesium.engine.world;

import java.util.HashMap;
import java.util.Map;

/**
 * Engine-side storage for section metadata and their current mesh revision/handle.
 * Uses packed 64-bit coordinate keys to eliminate all heap allocation on queries.
 */
public final class SectionStorage {

    /** Packs (chunkX, chunkZ, y) into a single 64-bit primitive key. */
    public static long packKey(long chunkX, long chunkZ, int y) {
        return (chunkX & 0x3FFFFFL) | ((chunkZ & 0x3FFFFFL) << 22) | (((long) y & 0xFFFFFL) << 44);
    }

    private final Map<Long, RenderWorld.Section> sections = new HashMap<>();
    private final Map<Long, RenderWorld.SectionMesh> meshes = new HashMap<>();

    public void put(RenderWorld.Section section) {
        sections.put(packKey(section.chunkX(), section.chunkZ(), section.y()), section);
    }

    public RenderWorld.Section get(long chunkX, long chunkZ, int y) {
        return sections.get(packKey(chunkX, chunkZ, y));
    }

    public void putMesh(RenderWorld.SectionMesh mesh) {
        meshes.put(packKey(mesh.chunkX(), mesh.chunkZ(), mesh.y()), mesh);
    }

    public RenderWorld.SectionMesh getMesh(long chunkX, long chunkZ, int y) {
        return meshes.get(packKey(chunkX, chunkZ, y));
    }

    public void remove(long chunkX, long chunkZ, int y) {
        long key = packKey(chunkX, chunkZ, y);
        sections.remove(key);
        meshes.remove(key);
    }

    public int size() {
        return sections.size();
    }

    public void clear() {
        sections.clear();
        meshes.clear();
    }
}