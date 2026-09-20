package destiny.renderer.chunk;

public final class MeshingTelemetryTest {
    public static void main(String[] args) {
        require(MeshingJobSystem.elapsedMillis(1_000_000_000L, 1_007_500_000L) == 7L,
                "nanosecond duration must convert to whole milliseconds");
        require(MeshingJobSystem.elapsedMillis(9_000L, 8_000L) == 0L,
                "negative durations must be clamped");
        System.out.println("PASS  meshing telemetry clock conversion");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
