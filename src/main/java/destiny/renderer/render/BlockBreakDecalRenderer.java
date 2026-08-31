package destiny.renderer.render;

import destiny.renderer.memory.RendererArenaManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.logging.Logger;

/**
 * Batched renderer for mining destruction progress overlays (break stages 0..9).
 */
public final class BlockBreakDecalRenderer {

    private static final Logger LOGGER = Logger.getLogger("Caesium/DecalRenderer");
    private static final int MAX_DECALS = 256;
    private static final int VERTS_PER_BOX = 24; // 6 faces * 4 verts
    private static final int VERTEX_STRIDE = 16; // 4 floats: x, y, z, packed uv

    public record DecalEntry(int stage, int x, int y, int z) {}

    private final DecalEntry[] entries = new DecalEntry[MAX_DECALS];
    private int entryCount = 0;

    private int vao;
    private int vbo;
    private boolean initialized = false;

    public void initialize() {
        if (initialized) return;

        vbo = GL15.glGenBuffers();
        vao = GL30.glGenVertexArrays();

        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, (long) MAX_DECALS * VERTS_PER_BOX * VERTEX_STRIDE, GL15.GL_DYNAMIC_DRAW);

        GL20.glVertexAttribPointer(0, 4, GL11.GL_FLOAT, false, VERTEX_STRIDE, 0L);
        GL20.glEnableVertexAttribArray(0);

        GL30.glBindVertexArray(0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

        initialized = true;
        LOGGER.info("[Caesium] BlockBreakDecalRenderer initialized.");
    }

    public void beginFrame() {
        entryCount = 0;
    }

    public void record(int stage, int x, int y, int z) {
        if (entryCount < MAX_DECALS && stage >= 0 && stage <= 9) {
            entries[entryCount++] = new DecalEntry(stage, x, y, z);
        }
    }

    public int getDecalCount() {
        return entryCount;
    }

    public void shutdown() {
        if (!initialized) return;
        initialized = false;

        if (vao != 0) {
            GL30.glDeleteVertexArrays(vao);
            vao = 0;
        }
        if (vbo != 0) {
            GL15.glDeleteBuffers(vbo);
            vbo = 0;
        }
    }
}
