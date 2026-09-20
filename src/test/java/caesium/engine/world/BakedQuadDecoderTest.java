package caesium.engine.world;

import java.nio.ByteBuffer;

import destiny.renderer.chunk.BakedQuadDecoder;
import net.minecraft.client.render.BlockRenderLayer;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.util.math.Vector2f;
import net.minecraft.util.math.Direction;
import org.joml.Vector3f;

public final class BakedQuadDecoderTest {
    public static void main(String[] args) {
        BakedQuad quad = new BakedQuad(
            new Vector3f(0, 1, 0), new Vector3f(0, 1, 1),
            new Vector3f(1, 1, 1), new Vector3f(1, 1, 0),
            Vector2f.toLong(0.125f, 0.25f), Vector2f.toLong(0.125f, 0.75f),
            Vector2f.toLong(0.625f, 0.75f), Vector2f.toLong(0.625f, 0.25f),
            0, Direction.UP, null, true, 12);

        RenderWorld.LayerMesh mesh = BakedQuadDecoder.decode(
            quad, BlockRenderLayer.CUTOUT, 32, 64, -16, 0xFF80C040, 7);
        require(mesh.layer() == RenderWorld.TerrainLayer.CUTOUT, "render layer preserved");
        require(mesh.vertexCount() == 4 && mesh.indexCount() == 6, "quad topology preserved");
        require(mesh.positions()[0] == 32f && mesh.positions()[1] == 65f && mesh.positions()[2] == -16f,
            "model position translated to world position");
        require(mesh.uvs()[0] == 0.125f && mesh.uvs()[1] == 0.25f, "atlas UV preserved exactly");
        require(mesh.colors()[3] == 0xFF80C040, "ARGB tint preserved");
        require(mesh.lights()[0] == 12, "quad light emission raises packed light");
        require(mesh.normals()[0] == 2, "face normal preserved");
        require(BakedQuadDecoder.layer(BlockRenderLayer.SOLID) == RenderWorld.TerrainLayer.SOLID,
            "solid mapping");
        require(BakedQuadDecoder.layer(BlockRenderLayer.TRANSLUCENT) == RenderWorld.TerrainLayer.TRANSLUCENT,
            "translucent mapping");
        require(BakedQuadDecoder.layer(BlockRenderLayer.TRIPWIRE) == RenderWorld.TerrainLayer.TRIPWIRE,
            "tripwire mapping");
        LayerMeshBuilder collector = new LayerMeshBuilder(RenderWorld.TerrainLayer.CUTOUT);
        BakedQuadDecoder.append(quad, 0, 0, 0, 0xFFFFFFFF, 0, collector);
        BakedQuadDecoder.append(quad, 1, 0, 0, 0xFFFFFFFF, 0, collector);
        RenderWorld.LayerMesh combined = collector.build();
        require(combined.vertexCount() == 8 && combined.indexCount() == 12,
            "section collector combines quads");
        require(combined.indices()[6] == 4 && combined.indices()[11] == 7,
            "combined quad indices use the correct vertex base");
        ByteBuffer packed = TerrainVertexPacker.pack(mesh, null);
        require(packed.remaining() == 4 * TerrainVertexPacker.STRIDE, "packed stride is stable");
        require(packed.getFloat(0) == 32f && packed.getFloat(12) == 0.125f,
            "packed position and UV preserved");
        require((packed.get(20) & 255) == 0x80 && (packed.get(21) & 255) == 0xC0
                && (packed.get(22) & 255) == 0x40 && (packed.get(23) & 255) == 0xFF,
            "ARGB converted to GPU RGBA bytes");
        require(packed.getInt(24) == 12 && packed.get(28) == 2,
            "packed light and normal preserved");
        System.out.println("PASS  baked quad attributes and terrain layers preserved");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
