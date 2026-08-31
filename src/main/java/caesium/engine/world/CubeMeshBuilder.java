package caesium.engine.world;

/**
 * Builds an engine-neutral colored-cube {@link RenderWorld.SectionMesh} (one cube, world-space
 * POS_COLOR_3F_4F vertices, 36 indices) used by the Minecraft extractor for coarse terrain
 * and by the headless terrain tests. Each face carries its own color so orientation is
 * visible in pixel readbacks. Zero Minecraft imports.
 */
public final class CubeMeshBuilder {

    private CubeMeshBuilder() {
    }

    /** Colors per face, index by face (0=+Z,1=-Z,2=+X,3=-X,4=+Y,5=-Y). */
    public static final float[][] FACE_COLORS = {
            {0f, 1f, 0f, 1f}, // +Z green
            {0f, 0f, 1f, 1f}, // -Z blue
            {1f, 0f, 0f, 1f}, // +X red
            {1f, 1f, 0f, 1f}, // -X yellow
            {1f, 1f, 1f, 1f}, // +Y white
            {0.5f, 0.5f, 0.5f, 1f}, // -Y gray
    };

    /**
     * Builds a cube of the given half-size centered at the world position, with 24 unique
     * vertices (4 per face, each carrying its face's color) so flat shading never interpolates
     * between the colors of shared corners. Back-face culling in the terrain pipeline hides
     * the far faces; the near -Z face (blue) faces the camera when it looks along +Z.
     */
    public static RenderWorld.SectionMesh cube(long chunkX, long chunkZ, int y, int revision,
                                               float cx, float cy, float cz, float half) {
        int[][] corners = {
                {-1, -1, -1}, {1, -1, -1}, {1, 1, -1}, {-1, 1, -1},
                {-1, -1, 1}, {1, -1, 1}, {1, 1, 1}, {-1, 1, 1},
        };
        // 12 triangles, outward CCW winding, per-face colors.
        int[][] faces = {
                {4, 5, 6, 7, 0}, // +Z
                {1, 0, 3, 2, 1}, // -Z
                {5, 1, 2, 6, 2}, // +X
                {0, 4, 7, 3, 3}, // -X
                {7, 6, 2, 3, 4}, // +Y
                {0, 1, 5, 4, 5}, // -Y
        };

        float[] positions = new float[24 * 3];
        float[] colors = new float[24 * 4];
        int[] indices = new int[36];
        int vert = 0;
        int idx = 0;
        for (int[] f : faces) {
            float[] col = FACE_COLORS[f[4]];
            int[] quad = {f[0], f[1], f[2], f[3]};
            for (int q = 0; q < 4; q++) {
                int c = quad[q];
                positions[vert * 3] = cx + half * corners[c][0];
                positions[vert * 3 + 1] = cy + half * corners[c][1];
                positions[vert * 3 + 2] = cz + half * corners[c][2];
                System.arraycopy(col, 0, colors, vert * 4, 4);
                vert++;
            }
            // two triangles per quad: (0,1,2) and (0,2,3)
            indices[idx++] = vert - 4;
            indices[idx++] = vert - 3;
            indices[idx++] = vert - 2;
            indices[idx++] = vert - 4;
            indices[idx++] = vert - 2;
            indices[idx++] = vert - 1;
        }

        return new RenderWorld.SectionMesh(chunkX, chunkZ, y, revision,
                positions, colors, indices);
    }
}