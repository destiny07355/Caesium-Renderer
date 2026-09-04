package caesium.engine.world;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * An immutable snapshot of everything the engine needs to draw one frame. The engine
 * never reads Minecraft objects; it only ever sees these records. Copies are made via
 * {@link #toBuilder()} on the extraction thread and published atomically, so the render
 * thread observes a stable world (ARCHITECTURE.md §4).
 */
public final class RenderWorld {

    public record Camera(float x, float y, float z, float pitch, float yaw,
                         float fovDeg, long tick) {
    }

    public record Section(long chunkX, long chunkZ, int y, int revision, int meshHandle,
                          int blockLight, int skyLight, boolean opaque) {
    }

    /**
     * CPU-side geometry for one section, produced by the Minecraft extractor and consumed
     * by the terrain pass. Vertices are world-space POS_COLOR_3F_4F (3 floats position +
     * 4 floats color, tightly packed); indices are unsigned-int triplets. The engine treats
     * the payload as opaque — it never inspects the floats, only re-uploads them.
     */
    public record SectionMesh(long chunkX, long chunkZ, int y, int revision,
                              float[] positions, float[] colors, int[] indices) {
    }

    public record Entity(long id, float x, float y, float z, float pitch, float yaw,
                         float scale, int renderMask, int light) {
    }

    public record ParticleBatch(int typeId, int count, float cx, float cy, float cz,
                                float radius, int importance) {
    }

    public record Options(boolean fullbright, int renderDistance,
                          int maxParticlesPerFrame, int maxEntitiesPerFrame) {
    }

    public static RenderWorld create(Camera camera, Options options) {
        return new Builder(camera, options).build();
    }

    private final long revision;
    private final Camera camera;
    private final Options options;
    private final List<Section> sections;
    private final List<Entity> entities;
    private final List<ParticleBatch> particles;

    RenderWorld(long revision, Camera camera, Options options,
                List<Section> sections, List<Entity> entities, List<ParticleBatch> particles) {
        this.revision = revision;
        this.camera = camera;
        this.options = options;
        this.sections = sections;
        this.entities = entities;
        this.particles = particles;
    }

    public long revision() {
        return revision;
    }

    public Camera camera() {
        return camera;
    }

    public Options options() {
        return options;
    }

    public List<Section> sections() {
        return sections;
    }

    public List<Entity> entities() {
        return entities;
    }

    public List<ParticleBatch> particles() {
        return particles;
    }

    /**
     * Fast-path: updates camera and/or options while preserving existing section/entity lists.
     * Eliminates rebuilding 2,000+ chunk section maps on frames where only the camera moved.
     */
    public RenderWorld withCameraAndOptions(long revision, Camera camera, Options options) {
        return new RenderWorld(revision, camera, options, this.sections, this.entities, this.particles);
    }

    public RenderWorld withCamera(long revision, Camera camera) {
        return new RenderWorld(revision, camera, this.options, this.sections, this.entities, this.particles);
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    /** Copy-on-write builder used by the extraction thread to produce the next revision. */
    public static final class Builder {
        private long revision;
        private Camera camera;
        private Options options;
        private final LinkedHashMap<Long, Section> sections = new LinkedHashMap<>();
        private final ArrayList<Entity> entities = new ArrayList<>();
        private final ArrayList<ParticleBatch> particles = new ArrayList<>();

        private long packKey(long chunkX, long chunkZ) {
            return (chunkX & 0x3FFFFFL) | ((chunkZ & 0x3FFFFFL) << 22);
        }
        private boolean dirty = false;
        private RenderWorld lastBuilt = null;

        public Builder(Camera camera, Options options) {
            this.camera = camera;
            this.options = options;
        }

        public Builder(RenderWorld world) {
            this.revision = world.revision;
            this.camera = world.camera;
            this.options = world.options;
            for (Section s : world.sections) {
                this.sections.put(packKey(s.chunkX(), s.chunkZ()), s);
            }
            this.entities.addAll(world.entities);
            this.particles.addAll(world.particles);
        }

        public Camera camera() {
            return camera;
        }

        public Options options() {
            return options;
        }

        public Builder revision(long revision) {
            this.revision = revision;
            return this;
        }

        public Builder camera(Camera camera) {
            this.camera = camera;
            return this;
        }

        public Builder options(Options options) {
            this.options = options;
            return this;
        }

        public Builder addSection(Section s) {
            sections.put(packKey(s.chunkX(), s.chunkZ()), s);
            dirty = true;
            return this;
        }

        public Builder addEntity(Entity entity) {
            entities.add(entity);
            dirty = true;
            return this;
        }

        public Builder addParticles(ParticleBatch batch) {
            particles.add(batch);
            dirty = true;
            return this;
        }

        /**
         * Removes every section for which the predicate returns false. Used by the scene
         * manager to prune sections beyond the render distance so the snapshot and the
         * mesh storage stay bounded by the visible area.
         */
        public Builder filterSections(java.util.function.Predicate<Section> keep) {
            if (sections.values().removeIf(s -> !keep.test(s))) dirty = true;
            return this;
        }

        public RenderWorld build() {
            if (!dirty && lastBuilt != null) return lastBuilt;
            lastBuilt = new RenderWorld(revision, camera, options,
                    List.copyOf(sections.values()), List.copyOf(entities), List.copyOf(particles));
            dirty = false;
            return lastBuilt;
        }
    }
}