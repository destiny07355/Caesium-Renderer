package destiny.renderer.blockentity;

import caesium.engine.world.LayerMeshBuilder;
import net.minecraft.client.texture.Sprite;
import net.minecraft.util.math.Direction;

/**
 * High-performance, zero-allocation geometry emitter for block entities baked into terrain meshes.
 */
public final class BlockEntityMeshHelper {

    private BlockEntityMeshHelper() {}

    /**
     * Appends a full 6-sided cuboid into the target layer mesh builder with correct normals, UVs, and vertex order.
     *
     * @param target the destination layer builder
     * @param bx block world X
     * @param by block world Y
     * @param bz block world Z
     * @param minX cuboid min X in [0, 1] relative to block
     * @param minY cuboid min Y in [0, 1] relative to block
     * @param minZ cuboid min Z in [0, 1] relative to block
     * @param maxX cuboid max X in [0, 1] relative to block
     * @param maxY cuboid max Y in [0, 1] relative to block
     * @param maxZ cuboid max Z in [0, 1] relative to block
     * @param sprite atlas sprite for UV texturing, or null for default unit UVs
     * @param packedLight packed block/sky light
     * @param argb tint color
     * @param posScratch 12-element scratch array for vertex coordinates
     * @param uvScratch 8-element scratch array for UV coordinates
     */
    public static void appendCuboid(LayerMeshBuilder target, int bx, int by, int bz,
                                    float minX, float minY, float minZ,
                                    float maxX, float maxY, float maxZ,
                                    Sprite sprite, int packedLight, int argb,
                                    float[] posScratch, float[] uvScratch) {
        if (target == null) return;

        float u0 = sprite != null ? sprite.getMinU() : 0.0f;
        float u1 = sprite != null ? sprite.getMaxU() : 1.0f;
        float v0 = sprite != null ? sprite.getMinV() : 0.0f;
        float v1 = sprite != null ? sprite.getMaxV() : 1.0f;

        // Populate scratch UVs once (reused across faces)
        uvScratch[0] = u0; uvScratch[1] = v0;
        uvScratch[2] = u0; uvScratch[3] = v1;
        uvScratch[4] = u1; uvScratch[5] = v1;
        uvScratch[6] = u1; uvScratch[7] = v0;

        float x0 = bx + minX, x1 = bx + maxX;
        float y0 = by + minY, y1 = by + maxY;
        float z0 = bz + minZ, z1 = bz + maxZ;

        // UP (+Y, normal = 2)
        setQuadPositions(posScratch, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
        target.appendQuad(posScratch, uvScratch, argb, packedLight, (byte) 2);

        // DOWN (-Y, normal = 3)
        setQuadPositions(posScratch, x0, y0, z1, x0, y0, z0, x1, y0, z0, x1, y0, z1);
        target.appendQuad(posScratch, uvScratch, argb, packedLight, (byte) 3);

        // NORTH (-Z, normal = 5)
        setQuadPositions(posScratch, x1, y1, z0, x1, y0, z0, x0, y0, z0, x0, y1, z0);
        target.appendQuad(posScratch, uvScratch, argb, packedLight, (byte) 5);

        // SOUTH (+Z, normal = 4)
        setQuadPositions(posScratch, x0, y1, z1, x0, y0, z1, x1, y0, z1, x1, y1, z1);
        target.appendQuad(posScratch, uvScratch, argb, packedLight, (byte) 4);

        // WEST (-X, normal = 1)
        setQuadPositions(posScratch, x0, y1, z0, x0, y0, z0, x0, y0, z1, x0, y1, z1);
        target.appendQuad(posScratch, uvScratch, argb, packedLight, (byte) 1);

        // EAST (+X, normal = 0)
        setQuadPositions(posScratch, x1, y1, z1, x1, y0, z1, x1, y0, z0, x1, y1, z0);
        target.appendQuad(posScratch, uvScratch, argb, packedLight, (byte) 0);
    }

    private static void setQuadPositions(float[] scratch,
                                         float x0, float y0, float z0,
                                         float x1, float y1, float z1,
                                         float x2, float y2, float z2,
                                         float x3, float y3, float z3) {
        scratch[0] = x0; scratch[1] = y0; scratch[2] = z0;
        scratch[3] = x1; scratch[4] = y1; scratch[5] = z1;
        scratch[6] = x2; scratch[7] = y2; scratch[8] = z2;
        scratch[9] = x3; scratch[10] = y3; scratch[11] = z3;
    }
}
