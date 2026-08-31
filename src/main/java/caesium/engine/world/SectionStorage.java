package caesium.engine.world;

import java.util.HashMap;
import java.util.Map;

/**
 * Engine-side storage for section metadata and their current mesh revision/handle.
 * Mirrors the extracted world but keeps the authoritative copy of what has been meshed
 * and uploaded, independent of the immutable snapshots published to the render thread.
 */
public final class SectionStorage {

    public record Key(long chunkX, long chunkZ, int y) {
    }

    private final Map<Key, RenderWorld.Section> sections = new HashMap<>();
    private final Map<Key, RenderWorld.SectionMesh> meshes = new HashMap<>();

    public void put(RenderWorld.Section section) {
        sections.put(new Key(section.chunkX(), section.chunkZ(), section.y()), section);
    }

    public RenderWorld.Section get(long chunkX, long chunkZ, int y) {
        return sections.get(new Key(chunkX, chunkZ, y));
    }

    public void putMesh(RenderWorld.SectionMesh mesh) {
        meshes.put(new Key(mesh.chunkX(), mesh.chunkZ(), mesh.y()), mesh);
    }

    public RenderWorld.SectionMesh getMesh(long chunkX, long chunkZ, int y) {
        return meshes.get(new Key(chunkX, chunkZ, y));
    }

    public void remove(long chunkX, long chunkZ, int y) {
        sections.remove(new Key(chunkX, chunkZ, y));
        meshes.remove(new Key(chunkX, chunkZ, y));
    }

    public int size() {
        return sections.size();
    }

    public void clear() {
        sections.clear();
        meshes.clear();
    }
}