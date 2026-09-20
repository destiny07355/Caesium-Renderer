package destiny.renderer.cull;

public final class SoftwareOcclusionCullerTest {
    public static void main(String[] args) {
        SoftwareOcclusionCuller culler = new SoftwareOcclusionCuller();
        culler.setEnabled(true);
        culler.beginFrame(identity());

        require(!culler.rasterizeSection(-1, -1, 0, 1, 1, 0.5f, false),
                "partial sections must not become occluders");
        require(culler.rasterizeSection(-1, -1, 0, 1, 1, 0.5f, true),
                "fully opaque sections may become occluders");
        System.out.println("PASS  conservative software occluder admission");
    }

    private static float[] identity() {
        return new float[] {
                1, 0, 0, 0,
                0, 1, 0, 0,
                0, 0, 1, 0,
                0, 0, 0, 1
        };
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
