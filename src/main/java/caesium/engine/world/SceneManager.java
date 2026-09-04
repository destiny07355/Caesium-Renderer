package caesium.engine.world;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Owns the double-buffered world state. The game thread pushes {@link DeltaCommand}s
 * (never blocking), the extraction thread drains them into a copy-on-write {@code Builder}
 * and publishes the new revision atomically. The render thread reads {@link #published()}.
 */
public final class SceneManager {

    private final Deque<DeltaCommand> pending = new ArrayDeque<>();
    private final ArrayDeque<DeltaCommand> drainBuffer = new ArrayDeque<>();
    private final SectionStorage sections = new SectionStorage();
    private volatile RenderWorld published;
    private long revision;

    /** Called from the game thread. Bounded in practice by the queue drain below. */
    public void push(DeltaCommand command) {
        synchronized (pending) {
            pending.addLast(command);
        }
    }

    public RenderWorld published() {
        return published;
    }

    public SectionStorage sections() {
        return sections;
    }

    /**
     * Drains pending commands into the next revision and publishes it. Safe to call from
     * the extraction thread once per frame; calling it on the render thread is legal too
     * (it never touches Minecraft).
     */
    public RenderWorld update(RenderWorld baseline) {
        drainBuffer.clear();
        synchronized (pending) {
            drainBuffer.addAll(pending);
            pending.clear();
        }

        // Fast path: nothing changed this frame. The published snapshot is returned
        // as-is, so idle frames cost no copy and allocate nothing.
        if (drainBuffer.isEmpty() && published != null) {
            return published;
        }

        // Fast path 2: ONLY camera moved and/or options changed.
        // Bypasses rebuilding 2,000+ chunk section maps on frames where geometry did not change.
        if (baseline != null) {
            boolean hasStructuralChange = false;
            RenderWorld.Camera newCamera = null;
            RenderWorld.Options newOptions = null;

            for (DeltaCommand command : drainBuffer) {
                if (command instanceof DeltaCommand.CameraMoved cm) {
                    newCamera = cm.camera();
                } else if (command instanceof DeltaCommand.OptionChanged oc) {
                    newOptions = oc.options();
                } else if (!(command instanceof DeltaCommand.Explosion)) {
                    hasStructuralChange = true;
                    break;
                }
            }

            if (!hasStructuralChange) {
                revision++;
                RenderWorld.Camera cam = newCamera != null ? newCamera : baseline.camera();
                RenderWorld.Options opt = newOptions != null ? newOptions : baseline.options();
                published = baseline.withCameraAndOptions(revision, cam, opt);
                return published;
            }
        }

        RenderWorld.Builder builder = baseline != null
                ? baseline.toBuilder()
                : new RenderWorld.Builder(defaultCamera(), defaultOptions());

        for (DeltaCommand command : drainBuffer) {
            switch (command) {
                case DeltaCommand.CameraMoved cm -> builder.camera(cm.camera());
                case DeltaCommand.OptionChanged oc -> builder.options(oc.options());
                case DeltaCommand.SectionDirty sd -> markSectionDirty(builder, sd);
                case DeltaCommand.SectionMeshUpdated sm -> markSectionMesh(builder, sm.mesh());
                case DeltaCommand.EntityUpdated eu -> {
                    // Flat-array pool update happens here once entity storage is in; no-op in the skeleton.
                }
                case DeltaCommand.EntityRemoved er -> {
                    // Same as above.
                }
                case DeltaCommand.ParticleChanged pc -> {
                    // Classification and batching update happens here; no-op in the skeleton.
                }
                case DeltaCommand.Explosion ex -> {
                    // Consumed by the scheduler policy; the scene itself is unchanged.
                }
            }
        }

        pruneFarSections(builder);

        revision++;
        published = builder.revision(revision).build();
        return published;
    }

    /**
     * Drops sections (and their stored meshes) that lie beyond the render distance plus a
     * two-chunk margin around the camera. Without this, every chunk rebuild would append a
     * fresh mesh and the storage would grow for the lifetime of the world — the unbounded
     * growth that exhausted the heap.
     */
    private void pruneFarSections(RenderWorld.Builder builder) {
        RenderWorld.Camera camera = builder.camera();
        RenderWorld.Options options = builder.options();
        if (camera == null || options == null) {
            return;
        }
        float limit = (options.renderDistance() + 2) * 16f;
        builder.filterSections(s -> {
            float cx = s.chunkX() * 16f + 8f;
            float cy = s.y() * 16f + 8f;
            float cz = s.chunkZ() * 16f + 8f;
            float vertLimit = 512f; // covers full world height in blocks
            boolean keep = Math.abs(cx - camera.x()) <= limit
                    && Math.abs(cy - camera.y()) <= vertLimit
                    && Math.abs(cz - camera.z()) <= limit;
            if (!keep) {
                sections.remove(s.chunkX(), s.chunkZ(), s.y());
            }
            return keep;
        });
    }

    private void markSectionDirty(RenderWorld.Builder builder, DeltaCommand.SectionDirty d) {
        RenderWorld.Section s = sections.get(d.chunkX(), d.chunkZ(), d.y());
        if (s == null) {
            return;
        }
        RenderWorld.Section updated = new RenderWorld.Section(
                s.chunkX(), s.chunkZ(), s.y(),
                s.revision() + 1, s.meshHandle(),
                s.blockLight(), s.skyLight(), s.opaque());
        sections.put(updated);
        builder.addSection(updated);
    }

    /**
     * Registers a freshly-meshed section payload and reflects it in the snapshot. If the
     * section was never seen before, a base metadata entry is created so the section list
     * and the mesh storage stay in sync (the terrain pass keys on both).
     */
    private void markSectionMesh(RenderWorld.Builder builder, RenderWorld.SectionMesh mesh) {
        sections.putMesh(mesh);
        RenderWorld.Section s = sections.get(mesh.chunkX(), mesh.chunkZ(), mesh.y());
        if (s == null) {
            s = new RenderWorld.Section(
                    mesh.chunkX(), mesh.chunkZ(), mesh.y(), mesh.revision(),
                    mesh.revision(), 0, 15, true);
            sections.put(s);
        } else {
            s = new RenderWorld.Section(
                    s.chunkX(), s.chunkZ(), s.y(),
                    mesh.revision(), mesh.revision(),
                    s.blockLight(), s.skyLight(), s.opaque());
            sections.put(s);
        }
        builder.addSection(s);
    }

    private static RenderWorld.Camera defaultCamera() {
        return new RenderWorld.Camera(0f, 0f, 0f, 0f, 0f, 70f, 0L);
    }

    private static RenderWorld.Options defaultOptions() {
        return new RenderWorld.Options(false, 12, 1000, 300);
    }
}