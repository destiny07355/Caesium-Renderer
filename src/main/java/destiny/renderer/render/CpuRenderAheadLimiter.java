package destiny.renderer.render;

import org.lwjgl.opengl.GL32C;

/**
 * Frames-in-flight limiter — the Sodium-style "CPU Render Ahead Limit".
 *
 * <p>Minecraft's render thread can run arbitrarily far ahead of the GPU: it submits
 * frame after frame while the GPU is still busy. That queues latency and, on a weak or
 * shared-memory GPU, piles CPU work onto a device that is already the bottleneck. The
 * swap interval only bounds this when vsync is enabled.
 *
 * <p>The fix is the same one Sodium ships: create a GL fence sync at the end of each
 * frame and, at the start of the next frame, wait for (and discard) the fence created
 * {@code N} frames ago. That caps the number of frames in flight at {@code N}.
 * {@code 1} is the lowest-latency configuration; {@code 2} is the balanced default.
 *
 * <p>Fence syncs are not part of the render state machine, so interleaving them with
 * blaze3d's own pipeline state is safe — they neither read nor write the cached GL
 * state that {@code GpuDevice} relies on.
 */
public final class CpuRenderAheadLimiter {

    private static long[] fences = new long[0];
    private static int cursor = 0;
    private static boolean enabled = false;

    private CpuRenderAheadLimiter() {}

    /**
     * Sets the frames-in-flight limit. 0 disables the limiter entirely.
     * Deletes any outstanding fences when the size changes.
     */
    public static synchronized void configure(int framesInFlight) {
        int current = enabled ? fences.length : 0;
        if (framesInFlight == current) return;
        releaseAll();
        enabled = framesInFlight > 0;
        if (enabled) {
            fences = new long[framesInFlight];
            cursor = 0;
        }
    }

    /**
     * Waits for the fence created {@code N} frames ago and takes over its slot.
     * Called at the start of each rendered frame on the render thread.
     */
    public static synchronized void beginFrame() {
        if (!enabled) return;
        long fence = fences[cursor];
        if (fence != 0L) {
            // Non-blocking poll first: on a healthy machine the fence is usually
            // already signaled. Only when the CPU is genuinely N frames ahead do we
            // wait, and even then for a bounded window so a slow or wedged driver
            // cannot hang the render thread.
            int res = GL32C.glClientWaitSync(fence, GL32C.GL_SYNC_FLUSH_COMMANDS_BIT, 0L);
            if (res == GL32C.GL_TIMEOUT_EXPIRED) {
                long deadline = System.nanoTime() + 2_000_000_000L; // 2 second hard timeout
                do {
                    res = GL32C.glClientWaitSync(fence, GL32C.GL_SYNC_FLUSH_COMMANDS_BIT, 100_000_000L);
                } while (res == GL32C.GL_TIMEOUT_EXPIRED && System.nanoTime() < deadline);
            }
            GL32C.glDeleteSync(fence);
            fences[cursor] = 0L;
        }
    }

    /** Records the end-of-frame completion fence. Called at the tail of the world render. */
    public static synchronized void endFrame() {
        if (!enabled) return;
        long fence = GL32C.glFenceSync(GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        if (fence != 0L) {
            if (fences[cursor] != 0L) {
                GL32C.glDeleteSync(fences[cursor]);
            }
            fences[cursor] = fence;
        }
        cursor = (cursor + 1) % fences.length;
    }

    /** Deletes any outstanding fences. Must be called on the render thread (GL current). */
    public static synchronized void releaseAll() {
        for (int i = 0; i < fences.length; i++) {
            if (fences[i] != 0L) {
                try {
                    GL32C.glDeleteSync(fences[i]);
                } catch (Throwable ignored) {
                    // Context already gone; nothing to clean up.
                }
                fences[i] = 0L;
            }
        }
        enabled = false;
        fences = new long[0];
        cursor = 0;
    }
}
