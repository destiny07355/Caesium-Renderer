package caesium.engine.gl;

import caesium.engine.CaesiumEngine;
import caesium.engine.backend.GpuBackend;
import caesium.engine.backend.opengl.OpenGLBackend;
import caesium.engine.render.TerrainPass;
import caesium.engine.scheduler.FrameInput;
import caesium.engine.world.CubeMeshBuilder;
import caesium.engine.world.DeltaCommand;
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
 * Headless terrain exit test (Month 2 milestone): boots a hidden GL 3.3 core context and runs
 * the engine's {@link TerrainPass} on the {@link OpenGLBackend}. A synthetic section mesh — a
 * colored cube at (8,8,8), front (-Z) face blue — is pushed through the scene manager; the camera
 * at (8,8,0) looks along +Z (yaw 0). The pass uploads the mesh, binds the 3D pipeline with the
 * camera MVP, and draws it. Reading back the center pixel proves the engine rendered real 3D
 * world geometry positioned by a camera, not the debug quad.
 */
public final class HeadlessTerrainGlTest {

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

            long window = GLFW.glfwCreateWindow(WIDTH, HEIGHT, "caesium-terrain-gl", 0, 0);
            check(window != 0, "GLFW hidden window created");
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();

            GpuBackend backend = new OpenGLBackend();
            CaesiumEngine engine = new CaesiumEngine(backend, 2, 2);
            TerrainPass pass = new TerrainPass(backend, engine.scene());
            engine.graph().addPass(pass);
            engine.start();
            GL33.glViewport(0, 0, WIDTH, HEIGHT);
            GL33.glClearColor(1f, 0f, 0f, 1f);
            GL33.glClear(GL33.GL_COLOR_BUFFER_BIT);

            engine.scene().push(new DeltaCommand.CameraMoved(
                    new RenderWorld.Camera(8f, 8f, 0f, 0f, 0f, 70f, 0L)));
            RenderWorld.SectionMesh cube = CubeMeshBuilder.cube(0, 0, 0, 1, 8f, 8f, 8f, 1f);
            int vertices = cube.positions().length / 3;
            float[] uvs = new float[vertices * 2];
            int[] colors = new int[vertices];
            int[] lights = new int[vertices];
            byte[] normals = new byte[vertices];
            for (int i = 0; i < vertices; i++) {
                uvs[i * 2] = 0.5f;
                uvs[i * 2 + 1] = 0.5f;
                int r = Math.round(cube.colors()[i * 4] * 255f);
                int g = Math.round(cube.colors()[i * 4 + 1] * 255f);
                int b = Math.round(cube.colors()[i * 4 + 2] * 255f);
                int a = Math.round(cube.colors()[i * 4 + 3] * 255f);
                colors[i] = (a << 24) | (r << 16) | (g << 8) | b;
                lights[i] = 0xFF;
            }
            RenderWorld.LayerMesh solid = new RenderWorld.LayerMesh(RenderWorld.TerrainLayer.SOLID,
                    cube.positions(), uvs, colors, lights, normals, cube.indices());
            engine.scene().push(new DeltaCommand.LayeredSectionMeshUpdated(
                    new RenderWorld.LayeredSectionMesh(0, 0, 0, 1, List.of(solid), false, true)));
            RenderWorld world = engine.scene().update(null);

            int whiteTexture = GL33.glGenTextures();
            ByteBuffer white = MemoryUtil.memAlloc(4);
            white.put(0, (byte) 0xFF).put(1, (byte) 0xFF).put(2, (byte) 0xFF).put(3, (byte) 0xFF);
            GL33.glActiveTexture(GL33.GL_TEXTURE0);
            GL33.glBindTexture(GL33.GL_TEXTURE_2D, whiteTexture);
            GL33.glTexImage2D(GL33.GL_TEXTURE_2D, 0, GL33.GL_RGBA8, 1, 1, 0,
                    GL33.GL_RGBA, GL33.GL_UNSIGNED_BYTE, white);
            GL33.glTexParameteri(GL33.GL_TEXTURE_2D, GL33.GL_TEXTURE_MIN_FILTER, GL33.GL_NEAREST);
            GL33.glTexParameteri(GL33.GL_TEXTURE_2D, GL33.GL_TEXTURE_MAG_FILTER, GL33.GL_NEAREST);
            GL33.glActiveTexture(GL33.GL_TEXTURE2);
            GL33.glBindTexture(GL33.GL_TEXTURE_2D, whiteTexture);
            MemoryUtil.memFree(white);
            pass.configureLiveGroup(1 << RenderWorld.TerrainLayer.SOLID.ordinal(),
                    1 << RenderWorld.TerrainLayer.SOLID.ordinal(), 1);

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
            check(r < 40 && g < 40 && b > 200,
                    "center pixel is the cube's -Z blue face (r=" + r + ",g=" + g + ",b=" + b + ")");
            check(engine.graph().passCount() == 1, "graph ran the terrain pass");
            check(pass.lastExecution().valid(), "baked solid layer satisfied live coverage gate");

            engine.stop();
            GL33.glDeleteTextures(whiteTexture);
            GLFW.glfwDestroyWindow(window);
        } finally {
            GLFW.glfwTerminate();
        }

        System.out.println(failures == 0 ? "ALL PASS" : "SOME FAILED");
        if (failures > 0) {
            System.exit(1);
        }
    }

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
