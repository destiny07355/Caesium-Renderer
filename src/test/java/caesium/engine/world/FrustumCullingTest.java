package caesium.engine.world;

import caesium.engine.device.CameraMatrices;
import caesium.engine.device.FrustumCulling;

public final class FrustumCullingTest {
    public static void main(String[] args) {
        float[] mvp = CameraMatrices.mvp(new RenderWorld.Camera(8, 8, 0, 0, 0, 70, 0),
                16f / 9f, false);
        require(FrustumCulling.visible(mvp, 0, 0, 16, false), "forward section is visible");
        require(!FrustumCulling.visible(mvp, 0, 0, -32, false), "rear section is culled");
        require(!FrustumCulling.visible(mvp, 10000, 0, 16, false), "distant section is culled");
        System.out.println("PASS  terrain frustum culling");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
