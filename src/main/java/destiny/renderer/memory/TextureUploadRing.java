package destiny.renderer.memory;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL44;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.logging.Logger;

public final class TextureUploadRing {

    private static final Logger LOGGER = Logger.getLogger("Caesium/TextureRing");
    private static final int RING_SLOTS = 3;
    private static final int SLOT_CAPACITY_BYTES = 4 * 1024 * 1024;

    private static final int[] pboHandles = new int[RING_SLOTS];
    private static final MemorySegment[] mappedSegments = new MemorySegment[RING_SLOTS];
    private static int currentSlot = 0;
    private static int currentOffset = 0;
    private static boolean initialized = false;
    private static boolean supportsPersistentPbo = false;

    private TextureUploadRing() {}

    public static synchronized void initialize() {
        if (initialized) return;

        supportsPersistentPbo = org.lwjgl.opengl.GL.getCapabilities().OpenGL44
            || org.lwjgl.opengl.GL.getCapabilities().GL_ARB_buffer_storage;

        if (supportsPersistentPbo) {
            int flags = GL44.GL_MAP_WRITE_BIT | GL44.GL_MAP_PERSISTENT_BIT | GL44.GL_MAP_COHERENT_BIT;
            for (int i = 0; i < RING_SLOTS; i++) {
                pboHandles[i] = GL15.glGenBuffers();
                GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, pboHandles[i]);
                GL44.glBufferStorage(GL21.GL_PIXEL_UNPACK_BUFFER, SLOT_CAPACITY_BYTES, flags);

                ByteBuffer buf = GL30.glMapBufferRange(GL21.GL_PIXEL_UNPACK_BUFFER, 0, SLOT_CAPACITY_BYTES, flags);
                if (buf != null) {
                    mappedSegments[i] = MemorySegment.ofBuffer(buf);
                }
            }
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            LOGGER.info("[Caesium] PBO texture streaming ring ready (3x4MB).");
        }

        initialized = true;
    }

    public static void advanceFrame() {
        if (!initialized || !supportsPersistentPbo) return;
        currentSlot = (currentSlot + 1) % RING_SLOTS;
        currentOffset = 0;
    }

    public static boolean uploadSubImage2D(int textureTarget, int level, int xOffset, int yOffset,
                                          int width, int height, int format, int type,
                                          byte[] pixelData) {
        if (!initialized || !supportsPersistentPbo || pixelData == null) {
            return false;
        }

        int bytesNeeded = pixelData.length;
        int alignedNeeded = (bytesNeeded + 15) & ~15;

        if (currentOffset + alignedNeeded > SLOT_CAPACITY_BYTES) {
            return false;
        }

        MemorySegment segment = mappedSegments[currentSlot];
        if (segment == null) return false;

        MemorySegment.copy(MemorySegment.ofArray(pixelData), 0, segment, currentOffset, bytesNeeded);

        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, pboHandles[currentSlot]);
        GL11.glTexSubImage2D(textureTarget, level, xOffset, yOffset, width, height, format, type, currentOffset);
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);

        currentOffset += alignedNeeded;
        return true;
    }

    public static synchronized void shutdown() {
        if (!initialized) return;
        if (supportsPersistentPbo) {
            for (int i = 0; i < RING_SLOTS; i++) {
                if (pboHandles[i] != 0) {
                    GL15.glDeleteBuffers(pboHandles[i]);
                    pboHandles[i] = 0;
                }
                mappedSegments[i] = null;
            }
        }
        initialized = false;
    }
}
