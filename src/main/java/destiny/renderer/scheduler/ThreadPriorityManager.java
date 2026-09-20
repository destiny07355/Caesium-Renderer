package destiny.renderer.scheduler;

import java.util.logging.Logger;

/**
 * ThreadTweak-inspired multi-tier CPU thread scheduling and priority manager.
 *
 * <p>Ensures that the Minecraft render and main game threads receive high priority and CPU core
 * availability, while Caesium background meshing workers and Minecraft ForkJoin workers
 * adaptively scale concurrency and scheduling priority to eliminate 1% low frame stutter.
 */
public final class ThreadPriorityManager {

    private static final Logger LOGGER = Logger.getLogger("Caesium/ThreadPriority");

    public static final int PRIORITY_RENDER_THREAD = 8;
    public static final int PRIORITY_SERVER_THREAD = 7;
    public static final int PRIORITY_NETTY_IO      = 5;
    public static final int PRIORITY_WORKER_NORMAL = 5;
    public static final int PRIORITY_WORKER_LOW    = 4;
    public static final int PRIORITY_BACKGROUND_FJ = 3;

    private static volatile boolean workerPriorityLow = false;

    private ThreadPriorityManager() {}

    /**
     * Elevates the main game and render thread to high priority (8).
     */
    public static void boostMainThread() {
        try {
            Thread current = Thread.currentThread();
            current.setPriority(Math.min(Thread.MAX_PRIORITY, PRIORITY_RENDER_THREAD));
            LOGGER.info("[Caesium] Main game thread priority elevated to " + current.getPriority());
        } catch (Throwable t) {
            LOGGER.warning("[Caesium] Failed to elevate main game thread priority: " + t.getMessage());
        }
    }

    /**
     * Optimizes singleplayer internal server thread priority (7) to keep ticks smooth
     * without contending with the render thread.
     */
    public static void optimizeServerThread(Thread serverThread) {
        if (serverThread == null) return;
        try {
            serverThread.setPriority(Math.min(Thread.MAX_PRIORITY, PRIORITY_SERVER_THREAD));
            LOGGER.info("[Caesium] Internal server thread priority configured to " + serverThread.getPriority());
        } catch (Throwable t) {
            LOGGER.warning("[Caesium] Failed to configure server thread priority: " + t.getMessage());
        }
    }

    /**
     * Calculates safe background worker concurrency based on physical/logical CPU core count.
     * Leaves sufficient dedicated capacity for the Minecraft main thread, render thread, and OS.
     */
    public static int calculateSafeWorkerLimit(int availableCores) {
        return calculateSafeWorkerLimit(availableCores, false);
    }

    /**
     * Calculates safe background worker concurrency based on physical/logical CPU core count
     * and whether an internal server thread is sharing CPU cores in singleplayer.
     */
    public static int calculateSafeWorkerLimit(int availableCores, boolean singleplayer) {
        if (availableCores <= 2) {
            return 1;
        } else if (availableCores <= 4) {
            int workers = Math.max(1, availableCores - 2); // 2 workers on 4 cores
            return singleplayer ? Math.max(1, workers - 1) : workers;
        } else if (availableCores <= 8) {
            int workers = Math.max(2, availableCores - 3); // 5 workers on 8 cores
            return singleplayer ? Math.max(2, workers - 1) : workers;
        } else if (availableCores <= 16) {
            int workers = Math.min(8, availableCores - 4); // max 8 workers on 16 cores
            return singleplayer ? Math.max(2, workers - 1) : workers;
        } else {
            int workers = Math.min(10, availableCores / 2); // cap on high-core CPUs to prevent bus saturation
            return singleplayer ? Math.max(3, workers - 1) : workers;
        }
    }

    /**
     * Returns the dynamic scheduling priority for Caesium meshing workers.
     */
    public static int getWorkerPriority() {
        return workerPriorityLow ? PRIORITY_WORKER_LOW : PRIORITY_WORKER_NORMAL;
    }

    /**
     * Sets whether background workers should run at lowered priority due to frame pressure.
     */
    public static void setWorkerPriorityLow(boolean low) {
        workerPriorityLow = low;
    }
}
