package destiny.renderer.chunk;

import java.util.Arrays;

public final class TranslucencySorterTest {
    public static void main(String[] args) {
        TranslucencySorter sorter = new TranslucencySorter();
        for (int i = 0; i < 64; i++) {
            sorter.addQuad(0, 0, i, PackedVertexFormat.NORMAL_POS_Z, i * 4);
        }

        int[] output = new int[64];
        int count = sorter.sortInto(0, 0, -10, output);
        int firstGeneration = sorter.getSortGeneration();
        int[] first = Arrays.copyOf(output, count);

        require(count == 64, "all quads must be returned");
        require(sorter.sortInto(0, 0, -10, output) == 64, "cached result length must match");
        require(sorter.getSortGeneration() == firstGeneration, "same camera must reuse cached order");
        require(Arrays.equals(first, output), "cached order must remain stable");

        sorter.sortInto(0, 0, 100, output);
        require(sorter.getSortGeneration() == firstGeneration + 1,
                "moving the camera must invalidate the cached order");

        TranslucencySorter dense = new TranslucencySorter();
        int denseCount = 1_024;
        for (int i = 0; i < denseCount; i++) {
            dense.addQuad(0, 0, 0, PackedVertexFormat.NORMAL_POS_Z, i * 4);
        }
        int[] denseOutput = new int[denseCount];
        require(dense.sortInto(0, 0, 1, denseOutput) == denseCount,
                "dense overlap must fall back without losing quads");
        System.out.println("PASS  primitive cached translucency sorting");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
