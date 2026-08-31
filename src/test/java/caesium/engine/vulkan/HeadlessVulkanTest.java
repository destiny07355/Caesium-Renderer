package caesium.engine.vulkan;

import caesium.engine.CaesiumEngine;
import caesium.engine.backend.GpuBackend;
import caesium.engine.backend.vulkan.VulkanBackend;
import caesium.engine.debug.TestQuadPass;
import caesium.engine.scheduler.FrameInput;
import caesium.engine.world.RenderWorld;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

/**
 * Headless Vulkan exit test for Month 2 (ARCHITECTURE.md §23): boots a {@link VulkanBackend}
 * instance, runs the engine's {@link TestQuadPass} through the render graph, and reads back
 * a pixel of the offscreen target to prove the quad actually reached the GPU via the Vulkan
 * device/queue/command/buffer/pipeline/sync path. Also asserts the exit-criterion invariant:
 * the engine core's class files must contain no {@code net/minecraft} references.
 *
 * <p>When the machine has no Vulkan support, the test prints SKIP and exits 0 — a machine
 * without Vulkan is exactly the case the backend toggle handles by falling back to OpenGL.
 */
public final class HeadlessVulkanTest {

    private static final int WIDTH = 128;
    private static final int HEIGHT = 128;

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
        } else {
            GpuBackend backend = new VulkanBackend();
            CaesiumEngine engine = new CaesiumEngine(backend, 2, 2);
            engine.graph().addPass(new TestQuadPass(backend));
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
            check(r > 200 && g < 40 && b < 40,
                    "center pixel is the quad's red (r=" + r + ",g=" + g + ",b=" + b + ")");

            check(engine.device().status().frameCount() == 1, "engine recorded 1 frame");
            check(engine.graph().passCount() == 1, "graph ran the test quad pass");

            engine.stop();
        }

        System.out.println(failures == 0 && !skipped ? "ALL PASS"
                : skipped ? "SKIPPED (no Vulkan driver)"
                : "SOME FAILED");
        if (failures > 0) {
            System.exit(1);
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