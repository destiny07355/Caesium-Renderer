package caesium.engine.vulkan;

import caesium.engine.CaesiumEngine;
import caesium.engine.backend.GpuBackend;
import caesium.engine.backend.vulkan.VulkanBackend;
import caesium.engine.debug.UboTimerPass;
import caesium.engine.scheduler.FrameInput;
import caesium.engine.world.RenderWorld;

import java.util.List;

/**
 * Headless Vulkan test for Month 2 (ARCHITECTURE.md §19/§23): proves the uniform-block and
 * GPU-timing plumbing on the Vulkan backend. Runs {@link UboTimerPass} through the render
 * graph — it binds a half-intensity white tint UBO and wraps the draw in GPU timestamps —
 * then reads back the center pixel of the offscreen target (must be dim red, proving the
 * descriptor set pointed at the engine's UBO) and reads the timer after the queue is idle.
 *
 * <p>When the machine has no Vulkan support, the test prints SKIP and exits 0 — the backend
 * toggle falls back to OpenGL in that case.
 */
public final class HeadlessUboTimerVulkanTest {

    private static final int WIDTH = 128;
    private static final int HEIGHT = 128;

    private static int failures;
    private static boolean skipped;

    public static void main(String[] args) {
        if (!VulkanBackend.isSupported()) {
            System.out.println("SKIP  Vulkan not supported on this driver — OpenGL fallback is the correct path");
            skipped = true;
        } else {
            GpuBackend backend = new VulkanBackend();
            CaesiumEngine engine = new CaesiumEngine(backend, 2, 2);
            UboTimerPass pass = new UboTimerPass(backend);
            engine.graph().addPass(pass);
            engine.start();

            RenderWorld world = engine.scene().update(null);
            FrameInput input = new FrameInput(world, 16.67f, System.currentTimeMillis(), false, List.of());
            engine.scheduler().beginFrame(input);
            engine.scheduler().execute(input);
            engine.scheduler().endFrame(input);

            backend.graphicsQueue().waitIdle();

            int pixel = ((VulkanBackend) backend).readBackPixel(WIDTH / 2, HEIGHT / 2);
            int r = (pixel >>> 24) & 0xff;
            int g = (pixel >>> 16) & 0xff;
            int b = (pixel >>> 8) & 0xff;
            // Red (1,0,0) * tint (0.5,0.5,0.5) = (0.5, 0, 0) → r≈127.
            check(r > 60 && r < 190 && g < 40 && b < 40,
                    "center pixel is dim red from UBO tint (r=" + r + ",g=" + g + ",b=" + b + ")");

            long elapsed = pass.elapsedNanos();
            check(elapsed > 0L, "GPU timer measured the draw region (" + elapsed + " ns)");

            engine.stop();
        }

        System.out.println(failures == 0 && !skipped ? "ALL PASS"
                : skipped ? "SKIPPED (no Vulkan driver)"
                : "SOME FAILED");
        if (failures > 0) {
            System.exit(1);
        }
    }

    private static void check(boolean ok, String label) {
        System.out.println((ok ? "PASS" : "FAIL") + "  " + label);
        if (!ok) {
            failures++;
        }
    }
}