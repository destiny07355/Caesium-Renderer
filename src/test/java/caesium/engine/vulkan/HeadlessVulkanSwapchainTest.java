package caesium.engine.vulkan;

import caesium.engine.CaesiumEngine;
import caesium.engine.backend.GpuBackend;
import caesium.engine.backend.vulkan.VulkanBackend;
import caesium.engine.debug.TestQuadPass;
import caesium.engine.scheduler.FrameInput;
import caesium.engine.world.RenderWorld;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

/**
 * Headless swapchain exit test (Month 2): boots the {@link VulkanBackend}, attaches a hidden
 * GLFW window, and runs the engine's {@link TestQuadPass} through the swapchain path —
 * acquire → render into the current swapchain image → submit with semaphores → present —
 * over a few frames. Requests a capture of the presented image and reads back the center
 * pixel to prove the quad reached the window surface. Also asserts the exit-criterion
 * invariant: no {@code net/minecraft} references in the engine core.
 *
 * <p>Skips cleanly (exit 0) when the driver has no Vulkan support or no GLFW surface can be
 * created (e.g. some servers) — a machine without Vulkan uses the OpenGL fallback by design.
 */
public final class HeadlessVulkanSwapchainTest {

    private static final int WIDTH = 128;
    private static final int HEIGHT = 128;
    private static final int FRAMES = 3;

    private static int failures;
    private static boolean skipped;

    public static void main(String[] args) {
        String engineClasses = System.getProperty("caesium.engine.classes");
        if (engineClasses != null) {
            check(noMinecraftReferences(engineClasses), "engine core has no net.minecraft references");
        }

        if (!VulkanBackend.isSupported()) {
            System.out.println("SKIP  Vulkan not supported on this driver — OpenGL fallback is the correct path");
            skipped = true;
        } else if (!GLFW.glfwInit()) {
            System.out.println("SKIP  GLFW init failed — no window surface possible");
            skipped = true;
        } else {
            runSwapchainFrames();
            GLFW.glfwTerminate();
        }

        System.out.println(failures == 0 && !skipped ? "ALL PASS"
                : skipped ? "SKIPPED (no Vulkan driver / window surface)"
                : "SOME FAILED");
        if (failures > 0) {
            System.exit(1);
        }
    }

    private static void runSwapchainFrames() {
        GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API);
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        long window = GLFW.glfwCreateWindow(WIDTH, HEIGHT, "caesium-headless-swapchain", 0, 0);
        check(window != 0, "GLFW hidden window created");
        if (window == 0) {
            skipped = true;
            return;
        }

        VulkanBackend backend = new VulkanBackend();
        CaesiumEngine engine = new CaesiumEngine(backend, 2, 2);
        engine.graph().addPass(new TestQuadPass(backend));
        engine.start();

        try {
            backend.attachWindow(window);

            RenderWorld world = engine.scene().update(null);
            for (int i = 0; i < FRAMES; i++) {
                FrameInput input = new FrameInput(world, 16.67f,
                        System.currentTimeMillis(), false, List.of());
                engine.scheduler().beginFrame(input);
                engine.scheduler().execute(input);
                if (i == FRAMES - 1) {
                    backend.requestCapture();
                }
                engine.scheduler().endFrame(input);
            }

            backend.graphicsQueue().waitIdle();

            int pixel = backend.readBackPixel(WIDTH / 2, HEIGHT / 2);
            int r = (pixel >>> 24) & 0xff;
            int g = (pixel >>> 16) & 0xff;
            int b = (pixel >>> 8) & 0xff;
            check(r > 200 && g < 40 && b < 40,
                    "swapchain center pixel is the quad's red (r=" + r + ",g=" + g + ",b=" + b + ")");

            check(engine.device().status().frameCount() == FRAMES,
                    "engine recorded " + FRAMES + " frames");
            check(engine.graph().passCount() == 1, "graph ran the test quad pass");
        } finally {
            engine.stop();
            backend.detachWindow();
            GLFW.glfwDestroyWindow(window);
        }
    }

    /** Exit criterion: no caesium.engine class file may reference Minecraft bytes. */
    private static boolean noMinecraftReferences(String classesRoot) {
        Path root = Paths.get(classesRoot);
        byte[] needle = "net/minecraft".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(p -> p.toString().endsWith(".class"))
                    .filter(p -> p.toString().replace('\\', '/').contains("caesium/engine/"))
                    .allMatch(p -> !contains(readAll(p), needle));
        } catch (IOException e) {
            return false;
        }
    }

    private static byte[] readAll(Path p) {
        try {
            return Files.readAllBytes(p);
        } catch (IOException e) {
            return new byte[0];
        }
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static void check(boolean ok, String label) {
        System.out.println((ok ? "PASS" : "FAIL") + "  " + label);
        if (!ok) {
            failures++;
        }
    }
}