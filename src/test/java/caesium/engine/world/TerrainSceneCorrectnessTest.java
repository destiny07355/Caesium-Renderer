package caesium.engine.world;

import destiny.renderer.chunk.ChunkSectionData;
import destiny.renderer.chunk.MortonEncoder;
import destiny.renderer.chunk.SectionMeshExtractor;
import net.minecraft.util.math.ChunkSectionPos;

public final class TerrainSceneCorrectnessTest {
    public static void main(String[] args) {
        keepsEveryVerticalSectionInAChunkColumn();
        rejectsMeshCompletionsOlderThanThePublishedRevision();
        extractsAClosedSolidSectionWithoutInternalFacesOrOversizedArrays();
        System.out.println("PASS  terrain scene identity and revision ordering");
    }

    private static void extractsAClosedSolidSectionWithoutInternalFacesOrOversizedArrays() {
        ChunkSectionData data = new ChunkSectionData();
        data.originX = 32;
        data.originY = 64;
        data.originZ = -16;
        for (int y = 1; y <= 16; y++) {
            for (int z = 1; z <= 16; z++) {
                for (int x = 1; x <= 16; x++) {
                    int index = MortonEncoder.encode(x, y, z);
                    data.blockStateIds[index] = 1;
                    data.opacityFlags[index] = 1;
                }
            }
        }
        RenderWorld.SectionMesh mesh = SectionMeshExtractor.extract(
            ChunkSectionPos.from(2, 4, -1), data, 3);
        require(mesh != null, "solid section must produce a mesh");
        int exteriorFaces = 6 * 16 * 16;
        require(mesh.positions().length == exteriorFaces * 4 * 3,
            "positions must be exact-sized and contain no internal faces");
        require(mesh.colors().length == exteriorFaces * 4 * 4,
            "colors must be exact-sized and contain no trimming capacity");
        require(mesh.indices().length == exteriorFaces * 6,
            "indices must be exact-sized and contain no trimming capacity");
        require(mesh.positions()[0] >= 32 && mesh.positions()[0] <= 48,
            "mesh positions must use the populated world-space origin");
    }

    private static void keepsEveryVerticalSectionInAChunkColumn() {
        RenderWorld.Builder builder = new RenderWorld.Builder(camera(), options());
        builder.addSection(section(4, 0, 7, 1));
        builder.addSection(section(4, 1, 7, 1));
        RenderWorld world = builder.build();
        require(world.sections().size() == 2,
            "vertical sections at identical X/Z must not overwrite each other");
    }

    private static void rejectsMeshCompletionsOlderThanThePublishedRevision() {
        SceneManager scene = new SceneManager();
        scene.push(new DeltaCommand.SectionMeshUpdated(mesh(2)));
        RenderWorld current = scene.update(null);
        scene.push(new DeltaCommand.SectionMeshUpdated(mesh(1)));
        RenderWorld afterStale = scene.update(current);
        require(afterStale.sections().getFirst().revision() == 2,
            "late revision 1 must not replace published revision 2");
        require(scene.sections().getMesh(0, 0, 0).revision() == 2,
            "late revision 1 must not replace GPU upload source revision 2");
    }

    private static RenderWorld.Section section(long x, int y, long z, int revision) {
        return new RenderWorld.Section(x, z, y, revision, revision, 0, 15, true);
    }

    private static RenderWorld.SectionMesh mesh(int revision) {
        return new RenderWorld.SectionMesh(0, 0, 0, revision,
            new float[] {0, 0, 0}, new float[] {1, 1, 1, 1}, new int[] {0});
    }

    private static RenderWorld.Camera camera() {
        return new RenderWorld.Camera(0, 64, 0, 0, 0, 70, 0);
    }

    private static RenderWorld.Options options() {
        return new RenderWorld.Options(false, 12, 1000, 300);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
