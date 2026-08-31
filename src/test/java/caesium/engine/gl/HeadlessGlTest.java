package caesium.engine.gl;

import caesium.engine.CaesiumEngine;
import caesium.engine.backend.GpuBackend;
import caesium.engine.backend.opengl.OpenGLBackend;
import caesium.engine.debug.TestQuadPass;
import caesium.engine.scheduler.FrameInput;
import caesium.engine.world.RenderWorld;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL33;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

/**
 * Headless GL exit test for Month 1 (ARCHITECTURE.md §23): boots a hidden GLFW window with
 * an OpenGL 3.3 core context, runs the engine's {@link TestQuadPass} through the render
 * graph on the {@link OpenGLBackend}, and reads back the center pixel to prove the quad
 * actually reached the framebuffer. Also asserts the exit criterion invariant: the engine
 * core's class files must contain no {@code net/minecraft} references.
 */
public final class HeadlessGlTest {

    private static final int WIDTH = 128;
    private static final int HEIGHT = 128;

    private static int failures;

    public static void main(String[] args) {
        String engineClasses = System.getProperty("caesium.engine.classes");
        if (engineClasses != null) {
            check(noMinecraftReferences(engineClasses), "engine core has no net.minecraft references");
        }

        if (!GLFW.glfwInit()) {
            fail("GLFW init failed");
        }
        try {
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);

            long window = GLFW.glfwCreateWindow(WIDTH, HEIGHT, "caesium-headless", 0, 0);
            check(window != 0, "GLFW hidden window created");
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();

            String renderer = GL33.glGetString(GL33.GL_RENDERER);
            String version = GL33.glGetString(GL33.GL_VERSION);
            check(renderer != null && version != null,
                    "GL context up: " + version + " on " + renderer);

            GpuBackend backend = new OpenGLBackend();
            CaesiumEngine engine = new CaesiumEngine(backend, 2, 2);
            engine.graph().addPass(new TestQuadPass(backend));
            engine.start();
            GL33.glViewport(0, 0, WIDTH, HEIGHT);

            RenderWorld world = engine.scene().update(null);
            FrameInput input = new FrameInput(world, 16.67f, System.currentTimeMillis(), false, List.of());
            engine.scheduler().beginFrame(input);
            engine.scheduler().execute(input);
            engine.scheduler().endFrame(input);

            // Wait for the immediate-mode GL commands, then read the default framebuffer.
            backend.graphicsQueue().waitIdle();
            GL33.glFinish();

            GL33.glReadBuffer(GL33.GL_BACK);
            ByteBuffer pixel = MemoryUtil.memAlloc(4);
            GL33.glReadPixels(WIDTH / 2, HEIGHT / 2, 1, 1, GL33.GL_RGBA, GL33.GL_UNSIGNED_BYTE, pixel);
            int r = pixel.get(0) & 0xff;
            int g = pixel.get(1) & 0xff;
            int b = pixel.get(2) & 0xff;
            MemoryUtil.memFree(pixel);
            check(r > 200 && g < 40 && b < 40, "center pixel is the quad's red (r=" + r + ",g=" + g + ",b=" + b + ")");

            check(engine.device().status().frameCount() == 1, "engine recorded 1 frame");
            check(engine.graph().passCount() == 1, "graph ran the test quad pass");

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

    private static void fail(String label) {
        check(false, label);
    }
}