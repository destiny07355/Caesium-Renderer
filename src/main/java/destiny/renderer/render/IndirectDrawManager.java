package destiny.renderer.render;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL44;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.util.logging.Logger;

/**
 * Manages Multi-Draw Indirect (MDI) command buffers and single-pass draw dispatch.
 *
 * <p>Packs standard 20-byte OpenGL DrawElementsIndirectCommand structures:
 * <pre>
 *   uint  count;
 *   uint  instanceCount;
 *   uint  firstIndex;
 *   int   baseVertex;
 *   uint  baseInstance;
 * </pre>
 */
public final class IndirectDrawManager {

    private static final Logger LOGGER = Logger.getLogger("Caesium/MDI");
    private static final int COMMAND_STRIDE = 20; // 5 x 4 bytes
    private static final int MAX_COMMANDS = 16384; // Up to 16k chunk sections per pass
    private static final long BUFFER_SIZE = (long) MAX_COMMANDS * COMMAND_STRIDE;

    private int bufferHandle;
    private MemorySegment mappedBuffer;
    private Arena owningArena;
    private int commandCount = 0;
    private boolean hasMDI = false;
    private boolean initialized = false;

    // CPU fallback arrays
    private final int[] counts = new int[MAX_COMMANDS];
    private final int[] firstIndices = new int[MAX_COMMANDS];

    public void initialize() {
        if (initialized) return;

        hasMDI = org.lwjgl.opengl.GL.getCapabilities().GL_ARB_multi_draw_indirect;

        if (hasMDI) {
            bufferHandle = GL15.glGenBuffers();
            GL15.glBindBuffer(GL43.GL_DRAW_INDIRECT_BUFFER, bufferHandle);

            int flags = GL44.GL_MAP_WRITE_BIT | GL44.GL_MAP_PERSISTENT_BIT | GL44.GL_MAP_COHERENT_BIT;
            GL44.glBufferStorage(GL43.GL_DRAW_INDIRECT_BUFFER, BUFFER_SIZE, flags);

            ByteBuffer buf = GL30.glMapBufferRange(GL43.GL_DRAW_INDIRECT_BUFFER, 0, BUFFER_SIZE, flags);
            owningArena = destiny.renderer.memory.RendererArenaManager.getOrCreatePersistentArena("mdi_commands");
            mappedBuffer = buf != null ? MemorySegment.ofBuffer(buf) : owningArena.allocate(BUFFER_SIZE);

            GL15.glBindBuffer(GL43.GL_DRAW_INDIRECT_BUFFER, 0);
            LOGGER.info("[Caesium] Multi-Draw Indirect (MDI) command buffer created (Capacity: " + MAX_COMMANDS + " sections).");
        } else {
            LOGGER.info("[Caesium] Multi-Draw Indirect not supported by driver — using fast batched fallback.");
        }

        initialized = true;
    }

    public void beginFrame() {
        commandCount = 0;
    }

    /**
     * Adds an indirect draw command for a chunk section.
     */
    public void addCommand(int count, int instanceCount, int firstIndex, int baseVertex, int baseInstance) {
        if (commandCount >= MAX_COMMANDS || count <= 0) return;

        if (hasMDI && mappedBuffer != null) {
            long offset = (long) commandCount * COMMAND_STRIDE;
            mappedBuffer.set(ValueLayout.JAVA_INT, offset, count);
            mappedBuffer.set(ValueLayout.JAVA_INT, offset + 4, instanceCount);
            mappedBuffer.set(ValueLayout.JAVA_INT, offset + 8, firstIndex);
            mappedBuffer.set(ValueLayout.JAVA_INT, offset + 12, baseVertex);
            mappedBuffer.set(ValueLayout.JAVA_INT, offset + 16, baseInstance);
        } else {
            counts[commandCount] = count;
            firstIndices[commandCount] = firstIndex;
        }

        commandCount++;
    }

    /**
     * Dispatches all recorded draw commands in a single GPU call.
     */
    public void executeDraw() {
        if (commandCount == 0) return;

        if (hasMDI && bufferHandle != 0) {
            GL15.glBindBuffer(GL43.GL_DRAW_INDIRECT_BUFFER, bufferHandle);
            GL43.glMultiDrawElementsIndirect(
                GL11.GL_TRIANGLES,
                GL11.GL_UNSIGNED_INT,
                0L,
                commandCount,
                COMMAND_STRIDE
            );
            GL15.glBindBuffer(GL43.GL_DRAW_INDIRECT_BUFFER, 0);
        } else {
            for (int i = 0; i < commandCount; i++) {
                GL11.glDrawElements(
                    GL11.GL_TRIANGLES,
                    counts[i],
                    GL11.GL_UNSIGNED_INT,
                    (long) firstIndices[i] * 4L
                );
            }
        }
    }

    public int getCommandCount() {
        return commandCount;
    }

    public boolean isMdiSupported() {
        return hasMDI;
    }

    public void shutdown() {
        if (!initialized) return;
        initialized = false;

        if (bufferHandle != 0) {
            GL15.glDeleteBuffers(bufferHandle);
            bufferHandle = 0;
        }
        mappedBuffer = null;
    }
}
