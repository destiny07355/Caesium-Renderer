package caesium.engine.render;

import caesium.engine.backend.GpuBackend;
import caesium.engine.backend.GpuBuffer;
import caesium.engine.backend.GpuCommandEncoder;
import caesium.engine.backend.GpuPipeline;
import caesium.engine.backend.GpuTimer;
import caesium.engine.device.CameraMatrices;
import caesium.engine.device.FrustumCulling;
import caesium.engine.device.FrameContext;
import caesium.engine.graph.PassResource;
import caesium.engine.graph.RenderPass;
import caesium.engine.world.RenderWorld;
import caesium.engine.world.SceneManager;
import caesium.engine.world.TerrainVertexPacker;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * The terrain pass (Month 2 milestone: the engine renders real scene content). It reads the
 * section meshes published by the Minecraft extractor through {@link SceneManager}, uploads
 * them into vertex/index buffers (cached per section + revision), binds the 3D
 * {@link GpuCommandEncoder.VertexLayout#POS_COLOR_3F_4F} pipeline with the camera MVP in the
 * shared {@code Uniforms} block, and issues indexed draws.
 *
 * <p>Zero Minecraft imports — the pass only ever sees the engine-neutral
 * {@link RenderWorld.SectionMesh} payloads. This is the first pass where the engine draws
 * actual world geometry, not the debug quad.
 */
public final class TerrainPass implements RenderPass {

    private static final PassResource OUTPUT =
            new PassResource("terrain.output", PassResource.Kind.IMAGE);

    private final GpuBackend backend;
    private final SceneManager scene;
    private final GpuBuffer[] uniformBuffers = new GpuBuffer[2];
    private GpuBuffer uniformBuffer;
    private GpuPipeline pipeline;
    private GpuPipeline bakedPipeline;
    private GpuTimer gpuTimer;
    private boolean gpuTimerPending;
    private boolean prepared;
    private ByteBuffer vertexStaging;
    private ByteBuffer indexStaging;
    private int activeLayerMask = 0xF;
    private int expectedLayerMask;
    private int expectedSections;
    private volatile ExecutionReport lastExecution = ExecutionReport.empty();
    private final float[] cachedExternalMvp = new float[16];
    private volatile boolean hasExternalMvp = false;
    private final ByteBuffer uniformStaging = ByteBuffer.allocateDirect(Float.BYTES * 20)
            .order(ByteOrder.nativeOrder());

    private final float[] frustumPlanes = new float[FrustumCulling.PLANE_DATA_SIZE];
    private final float[] lastCullMvp = new float[16];
    private RenderWorld lastCullWorld = null;
    private final java.util.ArrayList<RenderWorld.Section> cachedVisibleSections = new java.util.ArrayList<>();
    private int cachedCandidateCount = 0;

    /** Uploaded GPU copies of section meshes, keyed by packed primitive long coordinate. */
    private final Long2ObjectTable<Mesh> meshes = new Long2ObjectTable<>(256);
    private final Long2ObjectTable<Mesh> layeredMeshes = new Long2ObjectTable<>(512);

    public TerrainPass(GpuBackend backend, SceneManager scene) {
        this.backend = backend;
        this.scene = scene;
    }

    @Override
    public String id() {
        return "terrain";
    }

    @Override
    public String name() {
        return "Terrain";
    }

    @Override
    public Set<PassResource> reads() {
        return Set.of();
    }

    @Override
    public Set<PassResource> writes() {
        return Set.of(OUTPUT);
    }

    @Override
    public Set<PassResource> resources() {
        return writes();
    }

    @Override
    public Set<String> dependencies() {
        return Set.of();
    }

    private volatile boolean enabled = true;

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public boolean hasWork(FrameContext frame) {
        if (!enabled) return false;
        RenderWorld world = scene.published();
        return world != null && world.camera() != null;
    }

    @Override
    public void prepare(FrameContext frame) {
        if (prepared) {
            return;
        }
        uniformBuffers[0] = backend.memory().allocate(GpuBuffer.Usage.UNIFORM, Float.BYTES * 20);
        uniformBuffers[1] = backend.memory().allocate(GpuBuffer.Usage.UNIFORM, Float.BYTES * 20);
        uniformBuffer = uniformBuffers[0];
        pipeline = backend.createPipeline(GpuCommandEncoder.VertexLayout.POS_COLOR_3F_4F);
        bakedPipeline = backend.createPipeline(GpuCommandEncoder.VertexLayout.TERRAIN_BAKED);
        gpuTimer = backend.createTimer();
        prepared = true;
    }

    @Override
    public void execute(GpuCommandEncoder encoder, FrameContext frame) {
        RenderWorld world = scene.published();
        if (world == null || world.camera() == null) {
            return;
        }

        if (gpuTimerPending) {
            long elapsed = gpuTimer.tryElapsedNanos();
            if (elapsed > 0L) {
                destiny.renderer.hud.CaesiumFrameProfiler.recordGpu(
                        destiny.renderer.hud.CaesiumFrameProfiler.GpuPass.TERRAIN,
                        elapsed / 1_000_000.0);
                gpuTimerPending = false;
            }
        }
        boolean timeGpu = !gpuTimerPending;
        if (timeGpu) encoder.writeTimestamp(gpuTimer, false);

        // Budgeted cache cleanup: free at most 16 unreferenced meshes per frame to prevent frame-time spikes
        int freed = 0;
        long cleanupStart = System.nanoTime();
        if (meshes.size() + layeredMeshes.size() > world.sections().size() * 8 + 64) {
            freed += layeredMeshes.prune(16 - freed,
                (k, mesh) -> {
                    long cx = (k << 43) >> 43;
                    long cz = (k << 22) >> 43;
                    int cy = (int) ((k << 8) >> 50);
                    return world.containsSection(cx, cz, cy);
                },
                m -> m.free(backend)
            );
            if (freed < 16) {
                freed += meshes.prune(16 - freed,
                    (k, mesh) -> {
                        long cx = (k << 42) >> 42;
                        long cz = (k << 20) >> 42;
                        int cy = (int) (k >> 44);
                        return world.containsSection(cx, cz, cy);
                    },
                    m -> m.free(backend)
                );
            }
        }
        double cleanupMs = (System.nanoTime() - cleanupStart) / 1_000_000.0;
        destiny.renderer.hud.CaesiumFrameProfiler.recordTerrainCleanup(freed, cleanupMs);

        // Camera MVP into the shared Uniforms block (identity model: world-space vertices).
        boolean vulkan = backend.type() == GpuBackend.BackendType.VULKAN;
        float aspect = (float) Math.max(1, backend.viewportWidth())
                / Math.max(1, backend.viewportHeight());
        float[] mvp = hasExternalMvp ? cachedExternalMvp : CameraMatrices.mvp(world.camera(), aspect, vulkan);
        int slot = (int) (world.revision() & 1);
        uniformBuffer = uniformBuffers[slot] != null ? uniformBuffers[slot] : uniformBuffers[0];
        encoder.writeBuffer(uniformBuffer, 0, uniformData(mvp));
        encoder.bindPipeline(bakedPipeline);
        encoder.bindUniformBuffer(uniformBuffer);
        GpuPipeline boundPipeline = bakedPipeline;

        boolean needRecull = (lastCullWorld != world) || !java.util.Arrays.equals(mvp, lastCullMvp);
        if (needRecull) {
            FrustumCulling.extractPlanes(mvp, frustumPlanes, vulkan);
            cachedVisibleSections.clear();
            cachedCandidateCount = 0;
            for (RenderWorld.Section section : world.sections()) {
                cachedCandidateCount++;
                if (FrustumCulling.isVisible(frustumPlanes, section.chunkX() * 16f, section.y() * 16f, section.chunkZ() * 16f)) {
                    cachedVisibleSections.add(section);
                }
            }
            float camX = world.camera().x();
            float camY = world.camera().y();
            float camZ = world.camera().z();
            cachedVisibleSections.sort((a, b) -> {
                float dxa = (a.chunkX() * 16f + 8f) - camX;
                float dya = (a.y() * 16f + 8f) - camY;
                float dza = (a.chunkZ() * 16f + 8f) - camZ;
                float dxb = (b.chunkX() * 16f + 8f) - camX;
                float dyb = (b.y() * 16f + 8f) - camY;
                float dzb = (b.chunkZ() * 16f + 8f) - camZ;
                return Float.compare(dxa * dxa + dya * dya + dza * dza, dxb * dxb + dyb * dyb + dzb * dzb);
            });
            System.arraycopy(mvp, 0, lastCullMvp, 0, 16);
            lastCullWorld = world;
        }

        int candidates = cachedCandidateCount;
        int visible = cachedVisibleSections.size();
        int draws = 0;
        int coveredLayerMask = 0;
        int missingMeshes = 0;
        long submittedIndices = 0L;

        // Dual-budget limits for GPU upload
        float configMaxMs = destiny.renderer.config.RendererConfig.get().maxUploadMillisPerFrame;
        long maxUploadNanos = (long) (Math.max(0.5f, configMaxMs) * 1_000_000.0);
        long maxUploadBytes = 8L * 1024 * 1024; // 8 MB limit per frame
        int maxUploadCount = 16; // max 16 layer uploads per frame
        long uploadNanosSpent = 0L;
        long uploadBytesSpent = 0L;
        int uploadsDone = 0;

        for (int i = 0; i < cachedVisibleSections.size(); i++) {
            RenderWorld.Section section = cachedVisibleSections.get(i);
            RenderWorld.SectionMesh mesh = scene.sections().getMesh(
                    section.chunkX(), section.chunkZ(), section.y());
            RenderWorld.LayeredSectionMesh layered = scene.sections().getLayeredMesh(
                    section.chunkX(), section.chunkZ(), section.y());
            if (layered != null && layered.revision() >= section.revision()) {
                if (!layered.coverageComplete()) missingMeshes++;
                for (RenderWorld.LayerMesh layer : layered.layers()) {
                    int layerBit = 1 << layer.layer().ordinal();
                    if ((activeLayerMask & layerBit) == 0) continue;
                    if (layer.indexCount() == 0) continue;

                    long layerKey = packLayerKey(section.chunkX(), section.chunkZ(), section.y(), layer.layer().ordinal());
                    Mesh gpu = layeredMeshes.get(layerKey);
                    boolean needsUpload = (gpu == null || gpu.revision != section.revision());

                    if (needsUpload) {
                        boolean withinBudget = (uploadNanosSpent < maxUploadNanos
                                && uploadBytesSpent < maxUploadBytes
                                && uploadsDone < maxUploadCount);
                        if (withinBudget) {
                            long t0 = System.nanoTime();
                            gpu = upload(layered, layer, encoder);
                            long dt = System.nanoTime() - t0;
                            uploadNanosSpent += dt;
                            uploadsDone++;
                            uploadBytesSpent += (layer.hasPackedBuffers()
                                    ? (layer.packedVertices().remaining() + layer.packedIndices().remaining())
                                    : (layer.vertexCount() * 32 + layer.indexCount() * Integer.BYTES));
                        } else {
                            // Budget exhausted:
                            // If we already have a previous revision uploaded on GPU (gpu != null),
                            // keep drawing the existing mesh so there are zero holes or visual glitches!
                            if (gpu == null) {
                                continue; // Brand new section, cannot draw until uploaded in next frame
                            }
                        }
                    }

                    if (boundPipeline != bakedPipeline) {
                        encoder.bindPipeline(bakedPipeline);
                        encoder.bindUniformBuffer(uniformBuffer);
                        boundPipeline = bakedPipeline;
                    }
                    encoder.bindVertexBuffer(gpu.vertex, GpuCommandEncoder.VertexLayout.TERRAIN_BAKED);
                    encoder.bindIndexBuffer(gpu.index);
                    encoder.drawIndexed(layer.indexCount(), 1);
                    draws++;
                    coveredLayerMask |= layerBit;
                    submittedIndices += layer.indexCount();
                }
            } else if (mesh != null && mesh.indices().length != 0 && activeLayerMask == 0xF) {
                long meshKey = caesium.engine.world.SectionStorage.packKey(mesh.chunkX(), mesh.chunkZ(), mesh.y());
                Mesh gpu = meshes.get(meshKey);
                boolean needsUpload = (gpu == null || gpu.revision != mesh.revision());

                if (needsUpload) {
                    boolean withinBudget = (uploadNanosSpent < maxUploadNanos
                            && uploadBytesSpent < maxUploadBytes
                            && uploadsDone < maxUploadCount);
                    if (withinBudget) {
                        long t0 = System.nanoTime();
                        gpu = upload(mesh, encoder);
                        long dt = System.nanoTime() - t0;
                        uploadNanosSpent += dt;
                        uploadsDone++;
                    } else if (gpu == null) {
                        continue;
                    }
                }

                if (boundPipeline != pipeline) {
                    encoder.bindPipeline(pipeline);
                    encoder.bindUniformBuffer(uniformBuffer);
                    boundPipeline = pipeline;
                }
                encoder.bindVertexBuffer(gpu.vertex, GpuCommandEncoder.VertexLayout.POS_COLOR_3F_4F);
                encoder.bindIndexBuffer(gpu.index);
                encoder.drawIndexed(mesh.indices().length, 1);
                draws++;
                submittedIndices += mesh.indices().length;
            } else {
                missingMeshes++;
            }
        }
        boolean sectionCoverage = world.sections().size() >= expectedSections;
        boolean layerCoverage = (coveredLayerMask & expectedLayerMask) == expectedLayerMask;
        lastExecution = new ExecutionReport(sectionCoverage && missingMeshes == 0 && layerCoverage,
                draws, visible, missingMeshes, coveredLayerMask, submittedIndices);
        destiny.renderer.hud.CaesiumFrameProfiler.recordTerrainSubmission(
                candidates, visible, draws, submittedIndices);
        if (timeGpu) {
            encoder.writeTimestamp(gpuTimer, true);
            gpuTimerPending = true;
        }
    }

    public void configureLiveGroup(int layerMask, int expectedLayerMask, int expectedSections) {
        this.activeLayerMask = layerMask;
        this.expectedLayerMask = expectedLayerMask & layerMask;
        this.expectedSections = Math.max(0, expectedSections);
        this.lastExecution = ExecutionReport.empty();
    }

    public ExecutionReport lastExecution() {
        return lastExecution;
    }

    public void setExternalMvp(float[] mvp) {
        if (mvp == null) {
            this.hasExternalMvp = false;
        } else {
            System.arraycopy(mvp, 0, this.cachedExternalMvp, 0, 16);
            this.hasExternalMvp = true;
        }
    }

    public record ExecutionReport(boolean valid, int draws, int visibleSections,
                                  int missingMeshes, int layerMask, long submittedIndices) {
        private static ExecutionReport empty() {
            return new ExecutionReport(false, 0, 0, 0, 0, 0L);
        }
    }

    public void clear() {
        layeredMeshes.forEach((k, m) -> {
            if (m != null) m.free(backend);
        });
        layeredMeshes.clear();
        meshes.forEach((k, m) -> {
            if (m != null) m.free(backend);
        });
        meshes.clear();
        cachedVisibleSections.clear();
        cachedCandidateCount = 0;
        lastCullWorld = null;
    }

    public void unloadChunk(long chunkX, long chunkZ) {
        for (int layer = 0; layer < 4; layer++) {
            for (int cy = -4; cy <= 24; cy++) {
                long layerKey = packLayerKey(chunkX, chunkZ, cy, layer);
                Mesh m = layeredMeshes.remove(layerKey);
                if (m != null) {
                    m.free(backend);
                }
                long meshKey = caesium.engine.world.SectionStorage.packKey(chunkX, chunkZ, cy);
                Mesh oldMesh = meshes.remove(meshKey);
                if (oldMesh != null) {
                    oldMesh.free(backend);
                }
            }
        }
    }

    public static long packLayerKey(long chunkX, long chunkZ, int y, int layerOrdinal) {
        return (chunkX & 0x1FFFFFL) | ((chunkZ & 0x1FFFFFL) << 21) | (((long) y & 0x3FFFL) << 42) | (((long) layerOrdinal & 0x7L) << 56);
    }

    /** Re-uploads the section mesh when its revision advances; caches the GPU buffers. */
    private Mesh upload(RenderWorld.SectionMesh mesh, GpuCommandEncoder encoder) {
        long key = caesium.engine.world.SectionStorage.packKey(mesh.chunkX(), mesh.chunkZ(), mesh.y());
        Mesh gpu = meshes.get(key);
        if (gpu == null || gpu.revision != mesh.revision()) {
            long uploadStart = System.nanoTime();
            if (gpu != null) {
                gpu.free(backend);
            }
            int vertexBytes = mesh.positions().length * Float.BYTES;
            int colorBytes = mesh.colors().length * Float.BYTES;
            int indexBytes = mesh.indices().length * Integer.BYTES;
            GpuBuffer vertex = backend.memory().allocate(GpuBuffer.Usage.VERTEX, vertexBytes + colorBytes);
            GpuBuffer index = backend.memory().allocate(GpuBuffer.Usage.INDEX, indexBytes);
            encoder.writeBuffer(vertex, 0, interleave(mesh.positions(), mesh.colors()));
            encoder.writeBuffer(index, 0, ints(mesh.indices()));
            gpu = new Mesh(vertex, index, mesh.revision());
            meshes.put(key, gpu);
            destiny.renderer.hud.CaesiumFrameProfiler.recordTerrainUpload(
                    (long) vertexBytes + colorBytes + indexBytes, System.nanoTime() - uploadStart);
        }
        return gpu;
    }

    private Mesh upload(RenderWorld.LayeredSectionMesh section, RenderWorld.LayerMesh layer,
                        GpuCommandEncoder encoder) {
        long key = packLayerKey(section.chunkX(), section.chunkZ(), section.y(), layer.layer().ordinal());
        Mesh gpu = layeredMeshes.get(key);
        if (gpu == null || gpu.revision != section.revision()) {
            long uploadStart = System.nanoTime();
            if (gpu != null) gpu.free(backend);
            ByteBuffer vertices;
            ByteBuffer indices;
            if (layer.hasPackedBuffers()) {
                vertices = layer.packedVertices().duplicate();
                indices = layer.packedIndices().duplicate();
            } else {
                vertices = TerrainVertexPacker.pack(layer, vertexStaging);
                vertexStaging = vertices;
                indices = ints(layer.indices());
            }
            int vertexBytes = vertices.remaining();
            int indexBytes = indices.remaining();
            GpuBuffer vertex = backend.memory().allocate(GpuBuffer.Usage.VERTEX, vertexBytes);
            GpuBuffer index = backend.memory().allocate(GpuBuffer.Usage.INDEX, indexBytes);
            encoder.writeBuffer(vertex, 0, vertices);
            encoder.writeBuffer(index, 0, indices);
            gpu = new Mesh(vertex, index, section.revision());
            layeredMeshes.put(key, gpu);
            destiny.renderer.hud.CaesiumFrameProfiler.recordTerrainUpload(
                    (long) vertexBytes + indexBytes, System.nanoTime() - uploadStart);
        }
        return gpu;
    }

    /** POS_COLOR_3F_4F interleaved vertex data: 3 position floats + 4 color floats. */
    private ByteBuffer interleave(float[] positions, float[] colors) {
        int count = positions.length / 3;
        int bytes = count * 7 * Float.BYTES;
        vertexStaging = ensureCapacity(vertexStaging, bytes);
        ByteBuffer data = vertexStaging;
        for (int i = 0; i < count; i++) {
            for (int j = 0; j < 3; j++) {
                data.putFloat(positions[i * 3 + j]);
            }
            for (int j = 0; j < 4; j++) {
                data.putFloat(colors[i * 4 + j]);
            }
        }
        data.flip();
        return data;
    }

    private ByteBuffer ints(int[] values) {
        int bytes = values.length * Integer.BYTES;
        indexStaging = ensureCapacity(indexStaging, bytes);
        ByteBuffer data = indexStaging;
        for (int v : values) {
            data.putInt(v);
        }
        data.flip();
        return data;
    }

    /** 80-byte uniform block: column-major MVP + white tint (identity-model world space). */
    private ByteBuffer uniformData(float[] mvp) {
        ByteBuffer data = uniformStaging.clear();
        for (float v : mvp) {
            data.putFloat(v);
        }
        data.putFloat(1f).putFloat(1f).putFloat(1f).putFloat(1f);
        data.flip();
        return data;
    }

    private static ByteBuffer ensureCapacity(ByteBuffer current, int required) {
        if (current == null || current.capacity() < required) {
            int capacity = Math.max(256, Integer.highestOneBit(required - 1) << 1);
            current = ByteBuffer.allocateDirect(capacity).order(ByteOrder.nativeOrder());
        }
        current.clear();
        current.limit(required);
        return current;
    }

    @Override
    public void close() {
        meshes.forEach((k, mesh) -> mesh.free(backend));
        meshes.clear();
        layeredMeshes.forEach((k, mesh) -> mesh.free(backend));
        layeredMeshes.clear();
        if (uniformBuffers[0] != null) backend.memory().free(uniformBuffers[0]);
        if (uniformBuffers[1] != null) backend.memory().free(uniformBuffers[1]);
        uniformBuffers[0] = null;
        uniformBuffers[1] = null;
        uniformBuffer = null;
        if (pipeline != null) pipeline.destroy();
        if (bakedPipeline != null) bakedPipeline.destroy();
        if (gpuTimer != null) gpuTimer.destroy();
        uniformBuffer = null;
        pipeline = null;
        bakedPipeline = null;
        gpuTimer = null;
        gpuTimerPending = false;
        hasExternalMvp = false;
        lastCullWorld = null;
        cachedVisibleSections.clear();
        vertexStaging = null;
        indexStaging = null;
        prepared = false;
    }

    private record Mesh(GpuBuffer vertex, GpuBuffer index, int revision) {
        void free(GpuBackend backend) {
            backend.memory().free(vertex);
            backend.memory().free(index);
        }
    }

    /**
     * High-performance primitive-long open-addressing table with linear probing.
     * Eliminates boxing and map-entry heap allocations entirely on the hot rendering path.
     */
    private static final class Long2ObjectTable<V> {
        private long[] keys;
        private Object[] values;
        private int mask;
        private int size;
        private int threshold;

        Long2ObjectTable(int initialCapacity) {
            int cap = Math.max(16, Integer.highestOneBit(initialCapacity - 1) << 1);
            this.keys = new long[cap];
            this.values = new Object[cap];
            this.mask = cap - 1;
            this.threshold = (int) (cap * 0.75f);
        }

        @SuppressWarnings("unchecked")
        V get(long key) {
            long k = key == 0L ? 1L : key;
            int idx = (int) mix(k) & mask;
            long[] ks = keys;
            long cur = ks[idx];
            while (cur != 0L) {
                if (cur == k) return (V) values[idx];
                idx = (idx + 1) & mask;
                cur = ks[idx];
            }
            return null;
        }

        void put(long key, V val) {
            if (size >= threshold) rehash();
            long k = key == 0L ? 1L : key;
            int idx = (int) mix(k) & mask;
            long[] ks = keys;
            long cur = ks[idx];
            while (cur != 0L) {
                if (cur == k) {
                    values[idx] = val;
                    return;
                }
                idx = (idx + 1) & mask;
                cur = ks[idx];
            }
            ks[idx] = k;
            values[idx] = val;
            size++;
        }

        @SuppressWarnings("unchecked")
        V remove(long key) {
            long k = key == 0L ? 1L : key;
            int idx = (int) mix(k) & mask;
            long[] ks = keys;
            long cur = ks[idx];
            while (cur != 0L) {
                if (cur == k) {
                    V old = (V) values[idx];
                    removeAt(idx);
                    return old;
                }
                idx = (idx + 1) & mask;
                cur = ks[idx];
            }
            return null;
        }

        private void removeAt(int i) {
            size--;
            long[] ks = keys;
            Object[] vs = values;
            int m = mask;
            ks[i] = 0L;
            vs[i] = null;
            int j = (i + 1) & m;
            while (ks[j] != 0L) {
                long kToRedo = ks[j];
                Object vToRedo = vs[j];
                ks[j] = 0L;
                vs[j] = null;
                int proper = (int) mix(kToRedo) & m;
                while (ks[proper] != 0L) {
                    proper = (proper + 1) & m;
                }
                ks[proper] = kToRedo;
                vs[proper] = vToRedo;
                j = (j + 1) & m;
            }
        }

        private void rehash() {
            int newCap = keys.length << 1;
            long[] oldK = keys;
            Object[] oldV = values;
            keys = new long[newCap];
            values = new Object[newCap];
            mask = newCap - 1;
            threshold = (int) (newCap * 0.75f);
            size = 0;
            for (int i = 0; i < oldK.length; i++) {
                if (oldK[i] != 0L) {
                    put(oldK[i], (V) oldV[i]);
                }
            }
        }

        int size() { return size; }

        void clear() {
            java.util.Arrays.fill(keys, 0L);
            java.util.Arrays.fill(values, null);
            size = 0;
        }

        interface EntryFilter<V> {
            boolean retain(long key, V value);
        }

        private int scanCursor = 0;

        int prune(int maxToRemove, EntryFilter<V> filter, java.util.function.Consumer<V> onRemove) {
            if (maxToRemove <= 0 || size == 0) return 0;
            long[] toRemove = new long[maxToRemove];
            int count = 0;
            int n = keys.length;
            for (int step = 0; step < n && count < maxToRemove; step++) {
                int i = (scanCursor + step) & mask;
                if (keys[i] != 0L) {
                    if (!filter.retain(keys[i], (V) values[i])) {
                        toRemove[count++] = keys[i];
                    }
                }
            }
            scanCursor = (scanCursor + (n >> 2)) & mask;
            for (int i = 0; i < count; i++) {
                V old = remove(toRemove[i]);
                if (old != null) {
                    onRemove.accept(old);
                }
            }
            return count;
        }

        interface LongObjConsumer<V> {
            void accept(long key, V value);
        }

        @SuppressWarnings("unchecked")
        void forEach(LongObjConsumer<V> action) {
            for (int i = 0; i < keys.length; i++) {
                if (keys[i] != 0L) {
                    action.accept(keys[i], (V) values[i]);
                }
            }
        }

        private static long mix(long z) {
            z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
            z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
            return z ^ (z >>> 31);
        }
    }
}
