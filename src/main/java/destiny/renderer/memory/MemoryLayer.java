package destiny.renderer.memory;

import java.lang.foreign.MemorySegment;

/**
 * High-level memory infrastructure facade for the Caesium rendering engine.
 *
 * <p>Provides standardized allocation handles (frame allocations, persistent staging,
 * upload rings) so renderers and meshing systems consume memory services without
 * coupling to low-level FFM arena lifetimes.
 */
public final class MemoryLayer {

    private MemoryLayer() {}

    public static void initialize() {
        RendererArenaManager.initialize();
    }

    public static void beginFrame() {
        RendererArenaManager.beginFrame();
    }

    public static MemorySegment allocateFrame(long bytes) {
        return RendererArenaManager.allocateFrame(bytes);
    }

    public static GpuUploadRing getUploadRing() {
        return RendererArenaManager.getUploadRing();
    }

    public static long getTotalAllocatedBytes() {
        return RendererArenaManager.getTotalAllocatedBytes();
    }

    public static void shutdown() {
        RendererArenaManager.shutdown();
    }
}
