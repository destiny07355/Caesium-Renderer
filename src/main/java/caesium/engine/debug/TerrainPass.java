package caesium.engine.debug;

import caesium.engine.backend.GpuBackend;
import caesium.engine.backend.GpuBuffer;
import caesium.engine.backend.GpuCommandEncoder;
import caesium.engine.backend.GpuPipeline;
import caesium.engine.device.CameraMatrices;
import caesium.engine.device.FrameContext;
import caesium.engine.graph.PassResource;
import caesium.engine.graph.RenderPass;
import caesium.engine.world.RenderWorld;
import caesium.engine.world.SceneManager;

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
    private boolean prepared;

    /** Uploaded GPU copies of section meshes, keyed by section. */
    private final Map<SectionKey, Mesh> meshes = new HashMap<>();

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
        prepared = true;
    }

    @Override
    public void execute(GpuCommandEncoder encoder, FrameContext frame) {
        RenderWorld world = scene.published();
        if (world == null || world.camera() == null) {
            return;
        }

        // Prune uploaded meshes for sections that are no longer in the world (unloaded or
        // pruned by the scene manager). Kept conservative: only when the cache clearly
        // exceeds the live set, to avoid building the live set every frame.
        if (meshes.size() > world.sections().size() * 2 + 64) {
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

        for (RenderWorld.Section section : world.sections()) {
            RenderWorld.SectionMesh mesh = scene.sections().getMesh(
                    section.chunkX(), section.chunkZ(), section.y());
            if (mesh == null || mesh.indices().length == 0) {
                continue;
            }
            Mesh gpu = upload(mesh, encoder);
            encoder.bindVertexBuffer(gpu.vertex, GpuCommandEncoder.VertexLayout.POS_COLOR_3F_4F);
            encoder.bindIndexBuffer(gpu.index);
            encoder.drawIndexed(mesh.indices().length, 1);
        }
    }

    /** Re-uploads the section mesh when its revision advances; caches the GPU buffers. */
    private Mesh upload(RenderWorld.SectionMesh mesh, GpuCommandEncoder encoder) {
        SectionKey key = new SectionKey(mesh.chunkX(), mesh.chunkZ(), mesh.y());
        Mesh gpu = meshes.get(key);
        if (gpu == null || gpu.revision != mesh.revision()) {
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
        }
        return gpu;
    }

    /** POS_COLOR_3F_4F interleaved vertex data: 3 position floats + 4 color floats. */
    private static ByteBuffer interleave(float[] positions, float[] colors) {
        int count = positions.length / 3;
        ByteBuffer data = ByteBuffer.allocateDirect(count * 7 * Float.BYTES)
                .order(ByteOrder.nativeOrder());
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

    private static ByteBuffer ints(int[] values) {
        ByteBuffer data = ByteBuffer.allocateDirect(values.length * Integer.BYTES)
                .order(ByteOrder.nativeOrder());
        for (int v : values) {
            data.putInt(v);
        }
        data.flip();
        return data;
    }

    /** 80-byte uniform block: column-major MVP + white tint (identity-model world space). */
    private static ByteBuffer uniformData(float[] mvp) {
        ByteBuffer data = ByteBuffer.allocateDirect(Float.BYTES * 20)
                .order(ByteOrder.nativeOrder());
        for (float v : mvp) {
            data.putFloat(v);
        }
        data.putFloat(1f).putFloat(1f).putFloat(1f).putFloat(1f);
        data.flip();
        return data;
    }

    private record SectionKey(long chunkX, long chunkZ, int y) {
    }

    private record Mesh(GpuBuffer vertex, GpuBuffer index, int revision) {
        void free(GpuBackend backend) {
            backend.memory().free(vertex);
            backend.memory().free(index);
        }
    }
}