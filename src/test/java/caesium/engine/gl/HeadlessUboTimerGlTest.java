package caesium.engine.gl;

import caesium.engine.CaesiumEngine;
import caesium.engine.backend.GpuBackend;
import caesium.engine.backend.opengl.OpenGLBackend;
import caesium.engine.debug.UboTimerPass;
import caesium.engine.scheduler.FrameInput;
import caesium.engine.world.RenderWorld;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL33;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Headless GL test for Month 2 (ARCHITECTURE.md §19/§23): proves the uniform-block and
 * GPU-timing plumbing on the reference GL backend. Runs {@link UboTimerPass} through the
 * render graph — it binds a half-intensity white tint UBO and wraps the draw in GPU
 * timestamps — then reads back the center pixel (must be dim red, proving the UBO reached
 * the shader) and reads the timer (must be positive after the queue is idle).
 */
public final class HeadlessUboTimerGlTest {

    private static final int WIDTH = 128;
    private static final int HEIGHT = 128;

    private static int failures;

    public static void main(String[] args) {
        if (!GLFW.glfwInit()) {
            fail("GLFW init failed");
        }
        try {
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);

            long window = GLFW.glfwCreateWindow(WIDTH, HEIGHT, "caesium-ubo-timer-gl", 0, 0);
            check(window != 0, "GLFW hidden window created");
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();

            GpuBackend backend = new OpenGLBackend();
            CaesiumEngine engine = new CaesiumEngine(backend, 2, 2);
            UboTimerPass pass = new UboTimerPass(backend);
            engine.graph().addPass(pass);
            engine.start();
            GL33.glViewport(0, 0, WIDTH, HEIGHT);

            RenderWorld world = engine.scene().update(null);
            FrameInput input = new FrameInput(world, 16.67f, System.currentTimeMillis(), false, List.of());
            engine.scheduler().beginFrame(input);
            engine.scheduler().execute(input);
            engine.scheduler().endFrame(input);

            backend.graphicsQueue().waitIdle();
            GL33.glFinish();

            GL33.glReadBuffer(GL33.GL_BACK);
            ByteBuffer pixel = MemoryUtil.memAlloc(4);
            GL33.glReadPixels(WIDTH / 2, HEIGHT / 2, 1, 1, GL33.GL_RGBA, GL33.GL_UNSIGNED_BYTE, pixel);
            int r = pixel.get(0) & 0xff;
            int g = pixel.get(1) & 0xff;
            int b = pixel.get(2) & 0xff;
            MemoryUtil.memFree(pixel);
            // Red (1,0,0) * tint (0.5,0.5,0.5) = (0.5, 0, 0) → r≈127.
            check(r > 60 && r < 190 && g < 40 && b < 40,
                    "center pixel is dim red from UBO tint (r=" + r + ",g=" + g + ",b=" + b + ")");

            long elapsed = pass.elapsedNanos();
            check(elapsed > 0L, "GPU timer measured the draw region (" + elapsed + " ns)");

            engine.stop();
            GLFW.glfwDestroyWindow(window);
        } finally {
            GLFW.glfwTerminate();
        }

        System.out.println(failures == 0 ? "ALL PASS" : "SOME FAILED");
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

    private static void fail(String label) {
        check(false, label);
    }
}