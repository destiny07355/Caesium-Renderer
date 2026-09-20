package destiny.renderer.blockentity;

import caesium.engine.world.LayerMeshBuilder;
import caesium.engine.world.RenderWorld;
import destiny.renderer.config.RendererConfig;

public final class BlockEntityOptimizationTest {

    public static void main(String[] args) {
        testStaticTrackingAndInvalidation();
        testSectionBasedInvalidation();
        testConfigToggleBypass();
        testCuboidMeshGeneration();
        testUnsupportedBlockEntityFallback();
        System.out.println("PASS  OBE BlockEntityOptimizationRegistry, MeshHelper, and contracts verified");
    }

    private static void testStaticTrackingAndInvalidation() {
        BlockEntityOptimizationRegistry.clear();
        require(BlockEntityOptimizationRegistry.staticCount() == 0, "registry must be empty after clear");

        long posA = 1001L;
        long secA = 50L;
        long posB = 1002L;
        long secB = 50L;

        require(!BlockEntityOptimizationRegistry.isStaticallyBaked(posA), "posA must not be statically baked yet");

        BlockEntityOptimizationRegistry.recordStatic(posA, secA);
        BlockEntityOptimizationRegistry.recordStatic(posB, secB);

        require(BlockEntityOptimizationRegistry.isStaticallyBaked(posA), "posA must be reported as statically baked");
        require(BlockEntityOptimizationRegistry.isStaticallyBaked(posB), "posB must be reported as statically baked");
        require(!BlockEntityOptimizationRegistry.isStaticallyBaked(9999L), "unrecorded position must return false");
        require(BlockEntityOptimizationRegistry.staticCount() == 2, "must track exactly 2 entries");

        BlockEntityOptimizationRegistry.invalidate(posA);
        require(!BlockEntityOptimizationRegistry.isStaticallyBaked(posA), "posA must be invalidated");
        require(BlockEntityOptimizationRegistry.isStaticallyBaked(posB), "posB must remain statically baked");
        require(BlockEntityOptimizationRegistry.staticCount() == 1, "must track exactly 1 entry after invalidation");

        BlockEntityOptimizationRegistry.clear();
        require(BlockEntityOptimizationRegistry.staticCount() == 0, "must be empty after clear");
    }

    private static void testSectionBasedInvalidation() {
        BlockEntityOptimizationRegistry.clear();

        long sec1 = 101L;
        long sec2 = 202L;

        BlockEntityOptimizationRegistry.recordStatic(10L, sec1);
        BlockEntityOptimizationRegistry.recordStatic(11L, sec1);
        BlockEntityOptimizationRegistry.recordStatic(12L, sec1);

        BlockEntityOptimizationRegistry.recordStatic(20L, sec2);
        BlockEntityOptimizationRegistry.recordStatic(21L, sec2);

        require(BlockEntityOptimizationRegistry.staticCount() == 5, "should track 5 block entities");

        // Invalidate section 1
        BlockEntityOptimizationRegistry.invalidateSection(sec1);

        require(!BlockEntityOptimizationRegistry.isStaticallyBaked(10L), "pos 10 must be invalidated");
        require(!BlockEntityOptimizationRegistry.isStaticallyBaked(11L), "pos 11 must be invalidated");
        require(!BlockEntityOptimizationRegistry.isStaticallyBaked(12L), "pos 12 must be invalidated");

        require(BlockEntityOptimizationRegistry.isStaticallyBaked(20L), "pos 20 in sec2 must remain");
        require(BlockEntityOptimizationRegistry.isStaticallyBaked(21L), "pos 21 in sec2 must remain");
        require(BlockEntityOptimizationRegistry.staticCount() == 2, "must track remaining 2 entries from sec2");

        BlockEntityOptimizationRegistry.clear();
    }

    private static void testConfigToggleBypass() {
        BlockEntityOptimizationRegistry.clear();
        RendererConfig cfg = RendererConfig.get();
        cfg.optimizeBlockEntities = true;

        BlockEntityOptimizationRegistry.recordStatic(555L, 1L);
        require(BlockEntityOptimizationRegistry.isStaticallyBaked(555L), "should be true when enabled");

        cfg.optimizeBlockEntities = false;
        require(!BlockEntityOptimizationRegistry.isStaticallyBaked(555L), "must return false when config disables OBE");
        require(BlockEntityOptimizationRegistry.classify(null, null) == BlockEntityClassification.UNSUPPORTED,
            "classify must return UNSUPPORTED when disabled");

        cfg.optimizeBlockEntities = true;
        require(BlockEntityOptimizationRegistry.isStaticallyBaked(555L), "should re-enable when config is restored");

        BlockEntityOptimizationRegistry.clear();
    }

    private static void testCuboidMeshGeneration() {
        LayerMeshBuilder builder = new LayerMeshBuilder(RenderWorld.TerrainLayer.SOLID);
        float[] posScratch = new float[12];
        float[] uvScratch = new float[8];

        BlockEntityMeshHelper.appendCuboid(
            builder,
            10, 64, -20,
            0.0625f, 0.0f, 0.0625f,
            0.9375f, 0.875f, 0.9375f,
            null,
            0xF000F0,
            0xFFFFFFFF,
            posScratch,
            uvScratch);

        require(builder.vertexCount() == 24, "a 6-sided cuboid must produce exactly 24 vertices (4 per face)");
        require(builder.indexCount() == 36, "a 6-sided cuboid must produce exactly 36 indices (6 per face)");

        RenderWorld.LayerMesh mesh = builder.build();
        require(mesh.positions().length == 72, "24 vertices * 3 floats = 72 floats");
        require(mesh.indices().length == 36, "36 indices");
        require(mesh.layer() == RenderWorld.TerrainLayer.SOLID, "layer must be SOLID");
    }

    private static void testUnsupportedBlockEntityFallback() {
        BlockEntityClassification result = BlockEntityOptimizationRegistry.classify(null, null);
        require(result == BlockEntityClassification.UNSUPPORTED, "null block entity must fall back to UNSUPPORTED");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
