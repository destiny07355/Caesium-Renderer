package destiny.renderer.chunk;

public final class ChunkMesherBenchmarkTest {

    public static void main(String[] args) {
        System.out.println("=================================================");
        System.out.println("🚀 CAESIUM GREEDY CHUNK MESHER BENCHMARK");
        System.out.println("=================================================");

        // Construct realistic chunk data (18x18x18 padded)
        ChunkSectionData data = new ChunkSectionData();
        data.originX = 0;
        data.originY = 64;
        data.originZ = 0;

        // Fill lower half with solid stone (stateId = 1), middle with dirt/grass (stateId = 2), top with air (stateId = 0)
        for (int y = 1; y <= 16; y++) {
            for (int z = 1; z <= 16; z++) {
                for (int x = 1; x <= 16; x++) {
                    int m = MortonEncoder.encode(x, y, z);
                    if (y <= 8) {
                        data.blockStateIds[m] = 1; // stone
                        data.opacityFlags[m] = 1;
                    } else if (y <= 12) {
                        data.blockStateIds[m] = 2; // dirt
                        data.opacityFlags[m] = 1;
                    } else {
                        data.blockStateIds[m] = 0; // air
                        data.opacityFlags[m] = 0;
                    }
                    data.lightLevels[m] = (byte) (0 | (15 << 4)); // sky 15, block 0
                }
            }
        }

        // Set up padded boundary to test face culling
        for (int x = 0; x < 18; x++) {
            for (int z = 0; z < 18; z++) {
                int mBottom = MortonEncoder.encode(x, 0, z);
                data.blockStateIds[mBottom] = 1; // solid bottom neighbor
                data.opacityFlags[mBottom] = 1;

                int mTop = MortonEncoder.encode(x, 17, z);
                data.blockStateIds[mTop] = 0; // air top neighbor
                data.opacityFlags[mTop] = 0;
            }
        }

        ChunkMesher mesher = new ChunkMesher();

        // 1. Warm-up JIT (3,000 iterations)
        for (int i = 0; i < 3000; i++) {
            mesher.mesh(1L, data);
        }

        // 2. Measure Vertices & Indices Output
        boolean success = mesher.mesh(1L, data);
        int verts = mesher.getOpaqueVertexCount();
        int indices = mesher.getOpaqueIndexCount();
        int quads = indices / 6;

        System.out.printf("Opaque Vertices Emitted : %,d\n", verts);
        System.out.printf("Opaque Indices Emitted  : %,d\n", indices);
        System.out.printf("Total Quads Emitted     : %,d\n", quads);
        System.out.printf("Geometry Generated      : %s\n", success ? "YES" : "NO");

        // 3. Timed Throughput Benchmark (20,000 iterations)
        int iterations = 20000;
        long startNs = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            mesher.mesh(1L, data);
        }
        long elapsedNs = System.nanoTime() - startNs;

        double totalMs = elapsedNs / 1_000_000.0;
        double avgUsPerSection = (elapsedNs / (double) iterations) / 1000.0;
        double sectionsPerSecond = iterations / (totalMs / 1000.0);

        System.out.println("-------------------------------------------------");
        System.out.printf("Iterations              : %,d runs\n", iterations);
        System.out.printf("Total Benchmark Time    : %.2f ms\n", totalMs);
        System.out.printf("Avg Latency per Section : %.3f µs (%.4f ms)\n", avgUsPerSection, avgUsPerSection / 1000.0);
        System.out.printf("Meshing Throughput      : %,.0f sections / sec\n", sectionsPerSecond);
        System.out.println("=================================================");

        if (verts > 600) {
            System.err.println("❌ FAILED: Greedy mesher did not merge quads properly!");
            System.exit(1);
        } else {
            System.out.println("✅ SUCCESS: Greedy meshing verified! Massive vertex reduction confirmed.");
        }
    }
}
