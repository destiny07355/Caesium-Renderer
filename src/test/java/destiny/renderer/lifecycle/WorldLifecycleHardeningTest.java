package destiny.renderer.lifecycle;

import caesium.engine.world.RenderWorld;
import caesium.engine.world.SceneManager;
import caesium.engine.world.DeltaCommand;
import caesium.engine.render.TerrainPass;
import destiny.renderer.blockentity.BlockEntityOptimizationRegistry;
import destiny.renderer.chunk.DeferredRebuildQueue;
import destiny.renderer.chunk.MeshingJobSystem;
import destiny.renderer.render.SpriteVisibilityTracker;

public final class WorldLifecycleHardeningTest {

    public static void main(String[] args) {
        testPackLayerKeyBitExtraction();
        testBlockEntityRegistryLifecycle();
        testSpriteVisibilityLifecycle();
        testMeshingJobSystemLifecycle();
        testSceneManagerLifecycle();
        testDeferredRebuildQueueLifecycle();
        System.out.println("PASS  WorldLifecycleHardeningTest verified state cleanup, unload, and key roundtrips");
    }

    private static void testPackLayerKeyBitExtraction() {
        // Test layer key packing and unpacking with positive, negative, and edge-case coordinates
        long[] chunkXs = {-500, -1, 0, 1, 100, 10000};
        long[] chunkZs = {-2000, -1, 0, 1, 50, 50000};
        int[] ys = {-4, -1, 0, 1, 16, 24};
        int[] layers = {0, 1, 2, 3};

        for (long cx : chunkXs) {
            for (long cz : chunkZs) {
                for (int y : ys) {
                    for (int layer : layers) {
                        long key = TerrainPass.packLayerKey(cx, cz, y, layer);

                        long unpackedX = (key << 43) >> 43;
                        long unpackedZ = (key << 22) >> 43;
                        int unpackedY = (int) ((key << 8) >> 50);
                        int unpackedLayer = (int) ((key >>> 56) & 0x7L);

                        require(unpackedX == cx, "ChunkX mismatch: expected " + cx + " but got " + unpackedX);
                        require(unpackedZ == cz, "ChunkZ mismatch: expected " + cz + " but got " + unpackedZ);
                        require(unpackedY == y, "Y mismatch: expected " + y + " but got " + unpackedY);
                        require(unpackedLayer == layer, "Layer mismatch: expected " + layer + " but got " + unpackedLayer);
                    }
                }
            }
        }
    }

    private static void testBlockEntityRegistryLifecycle() {
        BlockEntityOptimizationRegistry.clear();
        require(BlockEntityOptimizationRegistry.staticCount() == 0, "Initial count must be 0");

        long posKey1 = 12345L;
        // Section in chunk (5, 10)
        long sectionKeyChunk5_10 = ((long)(5 & 0x1FFFFF)) | (((long)(2 & 0x1FFFFF)) << 21) | (((long)(10 & 0x1FFFFF)) << 42);
        BlockEntityOptimizationRegistry.recordStatic(posKey1, sectionKeyChunk5_10);
        require(BlockEntityOptimizationRegistry.isStaticallyBaked(posKey1), "posKey1 must be recorded as static");
        require(BlockEntityOptimizationRegistry.staticCount() == 1, "Count must be 1");

        // Invalidate unrelated chunk
        BlockEntityOptimizationRegistry.invalidateChunk(99, 99);
        require(BlockEntityOptimizationRegistry.isStaticallyBaked(posKey1), "posKey1 must remain after unrelated chunk unload");

        // Invalidate chunk (5, 10)
        BlockEntityOptimizationRegistry.invalidateChunk(5, 10);
        require(!BlockEntityOptimizationRegistry.isStaticallyBaked(posKey1), "posKey1 must be purged when chunk (5, 10) unloads");
        require(BlockEntityOptimizationRegistry.staticCount() == 0, "Count must be 0 after chunk unload");

        // Test clear()
        BlockEntityOptimizationRegistry.recordStatic(999L, 888L);
        require(BlockEntityOptimizationRegistry.staticCount() == 1, "Count must be 1 before clear");
        BlockEntityOptimizationRegistry.clear();
        require(BlockEntityOptimizationRegistry.staticCount() == 0, "Count must be 0 after clear");
    }

    private static void testSpriteVisibilityLifecycle() {
        SpriteVisibilityTracker.clear();
        SpriteVisibilityTracker.invalidateChunk(5, 5);
        SpriteVisibilityTracker.clear();
    }

    private static void testMeshingJobSystemLifecycle() {
        MeshingJobSystem.clear();
        long testPos = 99999L;
        MeshingJobSystem.setVersion(testPos, 5);
        require(MeshingJobSystem.currentVersion(testPos) == 5, "currentVersion must be 5");
        require(MeshingJobSystem.isLatest(testPos, 5), "version 5 must be latest");
        require(!MeshingJobSystem.isLatest(testPos, 4), "version 4 must not be latest");

        MeshingJobSystem.clear();
        require(MeshingJobSystem.currentVersion(testPos) == 0, "currentVersion must be 0 after clear");
    }

    private static void testSceneManagerLifecycle() {
        SceneManager scene = new SceneManager();
        RenderWorld.LayeredSectionMesh mesh1 = new RenderWorld.LayeredSectionMesh(
            1, 2, 2, 1, java.util.List.of(), true, true);
        scene.push(new DeltaCommand.LayeredSectionMeshUpdated(mesh1));

        RenderWorld world = scene.update(null);
        require(world != null, "Published world must not be null");
        require(world.containsSection(1, 2, 2), "Scene must contain section (1, 2, 2)");

        // Remove chunk (1, 2)
        scene.removeChunk(1, 2);
        RenderWorld worldAfterUnload = scene.update(world);
        require(!worldAfterUnload.containsSection(1, 2, 2), "Section must be removed after chunk unload");

        // Add section and test clear()
        scene.push(new DeltaCommand.LayeredSectionMeshUpdated(mesh1));
        scene.update(worldAfterUnload);
        scene.clear();
        require(scene.published() == null, "Published must be null after clear()");
        require(scene.sections().size() == 0, "Section storage must be 0 after clear()");
    }

    private static void testDeferredRebuildQueueLifecycle() {
        DeferredRebuildQueue.clear();
        require(DeferredRebuildQueue.size() == 0, "Queue must be empty after clear()");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
