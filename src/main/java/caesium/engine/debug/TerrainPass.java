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
    private GpuBuffer uniformBuffer;
    private GpuPipeline pipeline;
    private GpuPipeline bakedPipeline;
    private GpuTimer gpuTimer;
    private boolean gpuTimerPending;
    private boolean prepared;
    private ByteBuffer vertexStaging;
    private ByteBuffer indexStaging;
    private final ByteBuffer uniformStaging = ByteBuffer.allocateDirect(Float.BYTES * 20)
            .order(ByteOrder.nativeOrder());

    /** Uploaded GPU copies of section meshes, keyed by section. */
    private final Map<SectionKey, Mesh> meshes = new HashMap<>();
    private final Map<LayerKey, Mesh> layeredMeshes = new HashMap<>();

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

    @Override
    public boolean hasWork(FrameContext frame) {
        RenderWorld world = scene.published();
        return world != null && world.camera() != null;
    }

    @Override
    public void prepare(FrameContext frame) {
        if (prepared) {
            return;
        }
        uniformBuffer = backend.memory().allocate(GpuBuffer.Usage.UNIFORM, Float.BYTES * 20);
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

        // Prune uploaded meshes for sections that are no longer in the world (unloaded or
        // pruned by the scene manager). Kept conservative: only when the cache clearly
        // exceeds the live set, to avoid building the live set every frame.
        if (meshes.size() + layeredMeshes.size() > world.sections().size() * 8 + 64) {
            Set<SectionKey> live = new java.util.HashSet<>();
            for (RenderWorld.Section section : world.sections()) {
                live.add(new SectionKey(section.chunkX(), section.chunkZ(), section.y()));
            }
            meshes.keySet().removeIf(key -> {
                if (live.contains(key)) {
                    return false;
                }
                meshes.get(key).free(backend);
                return true;
            });
            layeredMeshes.keySet().removeIf(key -> {
                SectionKey section = new SectionKey(key.chunkX(), key.chunkZ(), key.y());
                if (live.contains(section)) return false;
                layeredMeshes.get(key).free(backend);
                return true;
            });
        }

        // Camera MVP into the shared Uniforms block (identity model: world-space vertices).
        boolean vulkan = backend.type() == GpuBackend.BackendType.VULKAN;
        float aspect = (float) Math.max(1, backend.viewportWidth())
                / Math.max(1, backend.viewportHeight());
        float[] mvp = CameraMatrices.mvp(world.camera(), aspect, vulkan);
        encoder.writeBuffer(uniformBuffer, 0, uniformData(mvp));
        // Bind pipeline first: the GL backend's bindPipeline rebinds the default identity
        // UBO, so the per-frame camera block must be bound after the pipeline.
        encoder.bindPipeline(pipeline);
        encoder.bindUniformBuffer(uniformBuffer);

        int candidates = 0;
        int visible = 0;
        int draws = 0;
        long submittedIndices = 0L;
        for (RenderWorld.Section section : world.sections()) {
            candidates++;
            if (!FrustumCulling.visible(mvp, section.chunkX() * 16f, section.y() * 16f,
                    section.chunkZ() * 16f, vulkan)) {
                continue;
            }
            visible++;
            RenderWorld.SectionMesh mesh = scene.sections().getMesh(
                    section.chunkX(), section.chunkZ(), section.y());
            RenderWorld.LayeredSectionMesh layered = scene.sections().getLayeredMesh(
                    section.chunkX(), section.chunkZ(), section.y());
            if (layered != null && layered.revision() >= section.revision()) {
                for (RenderWorld.LayerMesh layer : layered.layers()) {
                    if (layer.indexCount() == 0) continue;
                    Mesh gpu = upload(layered, layer, encoder);
                    encoder.bindPipeline(bakedPipeline);
                    encoder.bindUniformBuffer(uniformBuffer);
                    encoder.bindVertexBuffer(gpu.vertex, GpuCommandEncoder.VertexLayout.TERRAIN_BAKED);
                    encoder.bindIndexBuffer(gpu.index);
                    encoder.drawIndexed(layer.indexCount(), 1);
                    draws++;
                    submittedIndices += layer.indexCount();
                }
            } else if (mesh != null && mesh.indices().length != 0) {
                Mesh gpu = upload(mesh, encoder);
                encoder.bindPipeline(pipeline);
                encoder.bindUniformBuffer(uniformBuffer);
                encoder.bindVertexBuffer(gpu.vertex, GpuCommandEncoder.VertexLayout.POS_COLOR_3F_4F);
                encoder.bindIndexBuffer(gpu.index);
                encoder.drawIndexed(mesh.indices().length, 1);
                draws++;
                submittedIndices += mesh.indices().length;
            }
        }
        destiny.renderer.hud.CaesiumFrameProfiler.recordTerrainSubmission(
                candidates, visible, draws, submittedIndices);
        if (timeGpu) {
            encoder.writeTimestamp(gpuTimer, true);
            gpuTimerPending = true;
        }
    }

    /** Re-uploads the section mesh when its revision advances; caches the GPU buffers. */
    private Mesh upload(RenderWorld.SectionMesh mesh, GpuCommandEncoder encoder) {
        SectionKey key = new SectionKey(mesh.chunkX(), mesh.chunkZ(), mesh.y());
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
        LayerKey key = new LayerKey(section.chunkX(), section.chunkZ(), section.y(), layer.layer());
        Mesh gpu = layeredMeshes.get(key);
        if (gpu == null || gpu.revision != section.revision()) {
            long uploadStart = System.nanoTime();
            if (gpu != null) gpu.free(backend);
            ByteBuffer vertices = TerrainVertexPacker.pack(layer, vertexStaging);
            vertexStaging = vertices;
            ByteBuffer indices = ints(layer.indices());
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

    private record SectionKey(long chunkX, long chunkZ, int y) {
    }

    private record LayerKey(long chunkX, long chunkZ, int y, RenderWorld.TerrainLayer layer) {
    }

    @Override
    public void close() {
        for (Mesh mesh : meshes.values()) mesh.free(backend);
        meshes.clear();
        for (Mesh mesh : layeredMeshes.values()) mesh.free(backend);
        layeredMeshes.clear();
        if (uniformBuffer != null) backend.memory().free(uniformBuffer);
        if (pipeline != null) pipeline.destroy();
        if (bakedPipeline != null) bakedPipeline.destroy();
        if (gpuTimer != null) gpuTimer.destroy();
        uniformBuffer = null;
        pipeline = null;
        bakedPipeline = null;
        gpuTimer = null;
        gpuTimerPending = false;
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
}
