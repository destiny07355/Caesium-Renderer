package destiny.renderer.chunk;

import caesium.engine.world.RenderWorld;
import caesium.engine.world.LayerMeshBuilder;
import net.minecraft.client.render.BlockRenderLayer;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.util.math.Vector2f;
import net.minecraft.util.math.Direction;
import org.joml.Vector3fc;

/** Converts Minecraft baked quads into the engine-neutral terrain mesh contract. */
public final class BakedQuadDecoder {
    private static final ThreadLocal<float[]> POSITION_SCRATCH = ThreadLocal.withInitial(() -> new float[12]);
    private static final ThreadLocal<float[]> UV_SCRATCH = ThreadLocal.withInitial(() -> new float[8]);
    private BakedQuadDecoder() {}

    public static RenderWorld.LayerMesh decode(BakedQuad quad, BlockRenderLayer layer,
                                                int blockX, int blockY, int blockZ,
                                                int argb, int packedLight) {
        if (quad == null || layer == null) throw new IllegalArgumentException("quad and layer are required");
        float[] positions = new float[12];
        float[] uvs = new float[8];
        int[] colors = new int[4];
        int[] lights = new int[4];
        byte[] normals = new byte[4];
        byte normal = normalIndex(quad.face());
        int light = (packedLight & 0xF0) | Math.max(packedLight & 0xF, quad.lightEmission());

        for (int vertex = 0; vertex < 4; vertex++) {
            Vector3fc position = quad.getPosition(vertex);
            int p = vertex * 3;
            positions[p] = blockX + position.x();
            positions[p + 1] = blockY + position.y();
            positions[p + 2] = blockZ + position.z();
            long packedUv = quad.getTexcoords(vertex);
            int uv = vertex * 2;
            uvs[uv] = Vector2f.getX(packedUv);
            uvs[uv + 1] = Vector2f.getY(packedUv);
            colors[vertex] = argb;
            lights[vertex] = light;
            normals[vertex] = normal;
        }
        return new RenderWorld.LayerMesh(layer(layer), positions, uvs, colors, lights,
            normals, new int[] {0, 1, 2, 0, 2, 3});
    }

    public static float[] positionScratch() { return POSITION_SCRATCH.get(); }
    public static float[] uvScratch() { return UV_SCRATCH.get(); }

    /** Appends directly into a section-level collector, avoiding one mesh allocation per quad. */
    public static void append(BakedQuad quad, int blockX, int blockY, int blockZ,
                              int argb, int packedLight, LayerMeshBuilder target) {
        append(quad, blockX, blockY, blockZ, argb, packedLight, target,
            POSITION_SCRATCH.get(), UV_SCRATCH.get());
    }

    public static void append(BakedQuad quad, int blockX, int blockY, int blockZ,
                              int argb, int packedLight, LayerMeshBuilder target,
                              float[] positions, float[] uvs) {
        if (quad == null || target == null) throw new IllegalArgumentException("quad and target are required");
        for (int vertex = 0; vertex < 4; vertex++) {
            Vector3fc position = quad.getPosition(vertex);
            int p = vertex * 3;
            positions[p] = blockX + position.x();
            positions[p + 1] = blockY + position.y();
            positions[p + 2] = blockZ + position.z();
            long packedUv = quad.getTexcoords(vertex);
            int uv = vertex * 2;
            uvs[uv] = Vector2f.getX(packedUv);
            uvs[uv + 1] = Vector2f.getY(packedUv);
        }
        int light = (packedLight & 0xF0) | Math.max(packedLight & 0xF, quad.lightEmission());
        target.appendQuad(positions, uvs, argb, light, normalIndex(quad.face()));
    }

    public static RenderWorld.TerrainLayer layer(BlockRenderLayer layer) {
        return switch (layer) {
            case SOLID -> RenderWorld.TerrainLayer.SOLID;
            case CUTOUT -> RenderWorld.TerrainLayer.CUTOUT;
            case TRANSLUCENT -> RenderWorld.TerrainLayer.TRANSLUCENT;
            case TRIPWIRE -> RenderWorld.TerrainLayer.TRIPWIRE;
        };
    }

    private static byte normalIndex(Direction direction) {
        return switch (direction) {
            case EAST -> 0;
            case WEST -> 1;
            case UP -> 2;
            case DOWN -> 3;
            case SOUTH -> 4;
            case NORTH -> 5;
        };
    }
}
