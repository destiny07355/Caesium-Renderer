package caesium.engine.world;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Owns the double-buffered world state. The game thread pushes {@link DeltaCommand}s
 * (never blocking), the extraction thread drains them into a persistent store
 * and publishes the new revision atomically. The render thread reads {@link #published()}.
 */
public final class SceneManager {

    private final Deque<DeltaCommand> pending = new ArrayDeque<>();
    private final ArrayDeque<DeltaCommand> drainBuffer = new ArrayDeque<>();
    private final SectionStorage sections = new SectionStorage();
    private volatile RenderWorld published;
    private long revision;

    // Persistent storage avoiding full-scene LinkedHashMap/List/Set re-allocations per frame
    private final Map<Long, RenderWorld.Section> sectionStore = new LinkedHashMap<>();
    private final List<RenderWorld.Section> persistentSectionList = new ArrayList<>();
    private final Map<Long, Integer> keyToIndex = new java.util.HashMap<>();
    private final List<RenderWorld.Entity> entityStore = new ArrayList<>();
    private final List<RenderWorld.ParticleBatch> particleStore = new ArrayList<>();
    private List<RenderWorld.Section> cachedSectionList = List.of();
    private Set<Long> cachedSectionKeys = Set.of();
    private boolean sectionsDirty = false;
    private boolean keysDirty = false;
    private RenderWorld.Camera currentCamera = defaultCamera();
    private RenderWorld.Options currentOptions = defaultOptions();

    // Throttled pruning state
    private float lastPruneX = Float.NaN;
    private float lastPruneY = Float.NaN;
    private float lastPruneZ = Float.NaN;
    private int lastPruneRenderDist = -1;
    private int pruneFrameCounter = 0;

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
        if (drainBuffer.isEmpty() && published != null && !sectionsDirty && !keysDirty) {
            return published;
        }

        // Synchronize external baseline on initial bootstrap if sectionStore is empty but baseline has sections
        if (published == null && baseline != null && sectionStore.isEmpty() && baseline.sections() != null && !baseline.sections().isEmpty()) {
            for (RenderWorld.Section s : baseline.sections()) {
                long key = SectionStorage.packKey(s.chunkX(), s.chunkZ(), s.y());
                sectionStore.put(key, s);
                sections.put(s);
                keyToIndex.put(key, persistentSectionList.size());
                persistentSectionList.add(s);
            }
            sectionsDirty = true;
            keysDirty = true;
        }

        // Fast path 2: ONLY camera moved and/or options changed.
        // Bypasses rebuilding chunk section maps on frames where geometry did not change.
        if (baseline != null) {
            boolean hasStructuralChange = sectionsDirty || keysDirty;
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
                if (newCamera != null) currentCamera = newCamera;
                if (newOptions != null) currentOptions = newOptions;
                published = baseline.withCameraAndOptions(revision, currentCamera, currentOptions);
                return published;
            }
        }

        if (baseline != null) {
            if (baseline.camera() != null) currentCamera = baseline.camera();
            if (baseline.options() != null) currentOptions = baseline.options();
        }

        for (DeltaCommand command : drainBuffer) {
            switch (command) {
                case DeltaCommand.CameraMoved cm -> currentCamera = cm.camera();
                case DeltaCommand.OptionChanged oc -> currentOptions = oc.options();
                case DeltaCommand.SectionDirty sd -> markSectionDirty(sd);
                case DeltaCommand.SectionMeshUpdated sm -> markSectionMesh(sm.mesh());
                case DeltaCommand.LayeredSectionMeshUpdated lm -> markLayeredSectionMesh(lm.mesh());
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

        pruneFarSections();

        if (sectionsDirty || cachedSectionList.isEmpty()) {
            cachedSectionList = List.copyOf(persistentSectionList);
            sectionsDirty = false;
        }

        if (keysDirty || cachedSectionKeys.isEmpty()) {
            cachedSectionKeys = Set.copyOf(sectionStore.keySet());
            keysDirty = false;
        }

        revision++;
        published = new RenderWorld(revision, currentCamera, currentOptions,
                cachedSectionList, entityStore, particleStore, cachedSectionKeys);
        return published;
    }

    /**
     * Drops sections (and their stored meshes) that lie beyond the render distance plus a
     * two-chunk margin around the camera. Throttled to avoid scanning thousands of sections
     * on every single frame.
     */
    private void pruneFarSections() {
        if (currentCamera == null || currentOptions == null) {
            return;
        }
        int rDist = currentOptions.renderDistance();
        pruneFrameCounter++;
        boolean shouldCheck = Float.isNaN(lastPruneX)
                || Math.abs(currentCamera.x() - lastPruneX) >= 16f
                || Math.abs(currentCamera.y() - lastPruneY) >= 16f
                || Math.abs(currentCamera.z() - lastPruneZ) >= 16f
                || rDist != lastPruneRenderDist
                || pruneFrameCounter >= 60;

        if (!shouldCheck) return;

        lastPruneX = currentCamera.x();
        lastPruneY = currentCamera.y();
        lastPruneZ = currentCamera.z();
        lastPruneRenderDist = rDist;
        pruneFrameCounter = 0;

        float limit = (rDist + 2) * 16f;
        float vertLimit = 512f;

        var it = sectionStore.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            RenderWorld.Section s = entry.getValue();
            float cx = s.chunkX() * 16f + 8f;
            float cy = s.y() * 16f + 8f;
            float cz = s.chunkZ() * 16f + 8f;
            boolean keep = Math.abs(cx - currentCamera.x()) <= limit
                    && Math.abs(cy - currentCamera.y()) <= vertLimit
                    && Math.abs(cz - currentCamera.z()) <= limit;
            if (!keep) {
                it.remove();
                sections.remove(s.chunkX(), s.chunkZ(), s.y());
                sectionsDirty = true;
                keysDirty = true;
            }
        }
        if (sectionsDirty) {
            persistentSectionList.clear();
            keyToIndex.clear();
            for (var entry : sectionStore.entrySet()) {
                keyToIndex.put(entry.getKey(), persistentSectionList.size());
                persistentSectionList.add(entry.getValue());
            }
        }
    }

    public synchronized void clear() {
        synchronized (pending) {
            pending.clear();
        }
        drainBuffer.clear();
        sections.clear();
        sectionStore.clear();
        persistentSectionList.clear();
        keyToIndex.clear();
        entityStore.clear();
        particleStore.clear();
        cachedSectionList = List.of();
        cachedSectionKeys = Set.of();
        sectionsDirty = false;
        keysDirty = false;
        published = null;
        revision = 0L;
    }

    public synchronized void removeChunk(long chunkX, long chunkZ) {
        var it = sectionStore.entrySet().iterator();
        boolean removed = false;
        while (it.hasNext()) {
            var entry = it.next();
            RenderWorld.Section s = entry.getValue();
            if (s.chunkX() == chunkX && s.chunkZ() == chunkZ) {
                it.remove();
                sections.remove(s.chunkX(), s.chunkZ(), s.y());
                removed = true;
            }
        }
        if (removed) {
            persistentSectionList.clear();
            keyToIndex.clear();
            for (var entry : sectionStore.entrySet()) {
                keyToIndex.put(entry.getKey(), persistentSectionList.size());
                persistentSectionList.add(entry.getValue());
            }
            sectionsDirty = true;
            keysDirty = true;
        }
    }

    private void markSectionDirty(DeltaCommand.SectionDirty d) {
        RenderWorld.Section s = sections.get(d.chunkX(), d.chunkZ(), d.y());
        if (s == null) {
            return;
        }
        RenderWorld.Section updated = new RenderWorld.Section(
                s.chunkX(), s.chunkZ(), s.y(),
                s.revision() + 1, s.meshHandle(),
                s.blockLight(), s.skyLight(), s.opaque());
        sections.put(updated);
        long key = SectionStorage.packKey(updated.chunkX(), updated.chunkZ(), updated.y());
        boolean isNew = (sectionStore.put(key, updated) == null);
        if (isNew) {
            keyToIndex.put(key, persistentSectionList.size());
            persistentSectionList.add(updated);
            sectionsDirty = true;
            keysDirty = true;
        } else {
            Integer idx = keyToIndex.get(key);
            if (idx != null && idx < persistentSectionList.size()) {
                persistentSectionList.set(idx, updated);
            } else {
                keyToIndex.put(key, persistentSectionList.size());
                persistentSectionList.add(updated);
                sectionsDirty = true;
            }
        }
    }

    /**
     * Registers a freshly-meshed section payload and reflects it in the snapshot. If the
     * section was never seen before, a base metadata entry is created so the section list
     * and the mesh storage stay in sync (the terrain pass keys on both).
     */
    private void markSectionMesh(RenderWorld.SectionMesh mesh) {
        RenderWorld.Section s = sections.get(mesh.chunkX(), mesh.chunkZ(), mesh.y());
        RenderWorld.SectionMesh currentMesh = sections.getMesh(mesh.chunkX(), mesh.chunkZ(), mesh.y());
        int currentRevision = Math.max(s == null ? Integer.MIN_VALUE : s.revision(),
            currentMesh == null ? Integer.MIN_VALUE : currentMesh.revision());
        if (mesh.revision() <= currentRevision) {
            return;
        }
        sections.putMesh(mesh);
        if (s == null) {
            s = new RenderWorld.Section(
                    mesh.chunkX(), mesh.chunkZ(), mesh.y(), mesh.revision(),
                    mesh.revision(), 0, 15, true);
        } else {
            s = new RenderWorld.Section(
                    s.chunkX(), s.chunkZ(), s.y(),
                    mesh.revision(), mesh.revision(),
                    s.blockLight(), s.skyLight(), s.opaque());
        }
        sections.put(s);
        long key = SectionStorage.packKey(s.chunkX(), s.chunkZ(), s.y());
        boolean isNew = (sectionStore.put(key, s) == null);
        if (isNew) {
            keyToIndex.put(key, persistentSectionList.size());
            persistentSectionList.add(s);
            sectionsDirty = true;
            keysDirty = true;
        } else {
            Integer idx = keyToIndex.get(key);
            if (idx != null && idx < persistentSectionList.size()) {
                persistentSectionList.set(idx, s);
            } else {
                keyToIndex.put(key, persistentSectionList.size());
                persistentSectionList.add(s);
                sectionsDirty = true;
            }
        }
    }

    private void markLayeredSectionMesh(RenderWorld.LayeredSectionMesh mesh) {
        RenderWorld.Section s = sections.get(mesh.chunkX(), mesh.chunkZ(), mesh.y());
        RenderWorld.LayeredSectionMesh current = sections.getLayeredMesh(mesh.chunkX(), mesh.chunkZ(), mesh.y());
        int currentRevision = Math.max(s == null ? Integer.MIN_VALUE : s.revision(),
                current == null ? Integer.MIN_VALUE : current.revision());
        if (mesh.revision() <= currentRevision) return;
        sections.putLayeredMesh(mesh);
        if (s == null) {
            s = new RenderWorld.Section(mesh.chunkX(), mesh.chunkZ(), mesh.y(), mesh.revision(),
                    mesh.revision(), 0, 15, mesh.fullyOpaque());
        } else {
            s = new RenderWorld.Section(s.chunkX(), s.chunkZ(), s.y(), mesh.revision(),
                    mesh.revision(), s.blockLight(), s.skyLight(), mesh.fullyOpaque());
        }
        sections.put(s);
        long key = SectionStorage.packKey(s.chunkX(), s.chunkZ(), s.y());
        boolean isNew = (sectionStore.put(key, s) == null);
        if (isNew) {
            keyToIndex.put(key, persistentSectionList.size());
            persistentSectionList.add(s);
            sectionsDirty = true;
            keysDirty = true;
        } else {
            Integer idx = keyToIndex.get(key);
            if (idx != null && idx < persistentSectionList.size()) {
                persistentSectionList.set(idx, s);
            } else {
                keyToIndex.put(key, persistentSectionList.size());
                persistentSectionList.add(s);
                sectionsDirty = true;
            }
        }
    }

    private static RenderWorld.Camera defaultCamera() {
        return new RenderWorld.Camera(0f, 0f, 0f, 0f, 0f, 70f, 0L);
    }

    private static RenderWorld.Options defaultOptions() {
        return new RenderWorld.Options(false, 12, 1000, 300);
    }
}
