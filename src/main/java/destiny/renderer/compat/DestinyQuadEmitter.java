package destiny.renderer.compat;

import destiny.renderer.chunk.PackedVertexFormat;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * Stub — DestinyRenderer FRAPI QuadEmitter implementation.
 *
 * <p>Accumulates quad data into packed 64-bit vertex format without implementing
 * the version-sensitive {@code net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter}
 * interface. The full interface implementation is deferred to v1.1. This class
 * can be used internally by the v1.1 FRAPI adapter once the Fabric API module
 * is properly declared as a compile-time dependency.
 */
public final class DestinyQuadEmitter {

    private final List<long[]> output;

    // Current quad state — 4 vertices
    private final float[] x = new float[4];
    private final float[] y = new float[4];
    private final float[] z = new float[4];
    private final float[] u = new float[4];
    private final float[] v = new float[4];
    private final int[]   lightmap = new int[4];
    private int           normalIdx = PackedVertexFormat.NORMAL_POS_Y;
    private int           tintIdx   = PackedVertexFormat.TINT_NONE;
    private Direction     face;

    public DestinyQuadEmitter(List<long[]> output) {
        this.output = output;
    }

    public void reset() {
        face = null;
        normalIdx = PackedVertexFormat.NORMAL_POS_Y;
        tintIdx   = PackedVertexFormat.TINT_NONE;
    }

    // -------------------------------------------------------------------------
    // Position / UV / lightmap setters
    // -------------------------------------------------------------------------

    public DestinyQuadEmitter pos(int vertexIndex, float x, float y, float z) {
        this.x[vertexIndex] = x; this.y[vertexIndex] = y; this.z[vertexIndex] = z; return this;
    }

    public DestinyQuadEmitter uv(int vertexIndex, float u, float v) {
        this.u[vertexIndex] = u; this.v[vertexIndex] = v; return this;
    }

    public DestinyQuadEmitter lightmap(int vertexIndex, int lightmap) {
        this.lightmap[vertexIndex] = lightmap; return this;
    }

    public DestinyQuadEmitter cullFace(Direction face) {
        this.face = face;
        if (face != null) normalIdx = dirToNormalIdx(face);
        return this;
    }

    public DestinyQuadEmitter tintIndex(int colorIndex) {
        this.tintIdx = colorIndex >= 0 ? Math.min(colorIndex + 1, 255) : PackedVertexFormat.TINT_NONE;
        return this;
    }

    // -------------------------------------------------------------------------
    // Emission
    // -------------------------------------------------------------------------

    public DestinyQuadEmitter emit() {
        long[] packed = new long[4];
        for (int i = 0; i < 4; i++) {
            int bx = (int)(this.x[i] * 16) & 0x3F;
            int by = (int)(this.y[i] * 16) & 0x3F;
            int bz = (int)(this.z[i] * 16) & 0x3F;
            int lm = lightmap[i];
            int blockLight = (lm >> 4)  & 0xF;
            int skyLight   = (lm >> 20) & 0xF;
            packed[i] = PackedVertexFormat.pack(
                bx, by, bz, normalIdx,
                u[i], v[i],
                blockLight, skyLight,
                1.0f,
                tintIdx
            );
        }
        output.add(packed);
        reset();
        return this;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static int dirToNormalIdx(Direction d) {
        return switch (d) {
            case UP    -> PackedVertexFormat.NORMAL_POS_Y;
            case DOWN  -> PackedVertexFormat.NORMAL_NEG_Y;
            case EAST  -> PackedVertexFormat.NORMAL_POS_X;
            case WEST  -> PackedVertexFormat.NORMAL_NEG_X;
            case SOUTH -> PackedVertexFormat.NORMAL_POS_Z;
            case NORTH -> PackedVertexFormat.NORMAL_NEG_Z;
        };
    }
}
