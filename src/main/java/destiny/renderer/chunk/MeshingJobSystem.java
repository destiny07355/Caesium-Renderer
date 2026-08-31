package destiny.renderer.chunk;

import destiny.renderer.config.RendererConfig;
import net.minecraft.util.math.ChunkSectionPos;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Priority-Queue Thread Pool for parallel chunk section meshing.
 *
 * <h2>Design</h2>
 * Uses a {@link ThreadPoolExecutor} with a {@link PriorityBlockingQueue} to ensure
 * chunk sections closest to the player's camera are meshed first. This resolves
 * the severe stuttering/lag spikes associated with explosions.
 */
public final class MeshingJobSystem {

    private static final Logger LOGGER = Logger.getLogger("Caesium/Mesher");

    private static MeshingJobSystem instance;

    // Camera coordinates for distance sorting
    private static volatile double cameraX = 0.0;
    private static volatile double cameraY = 0.0;
    private static volatile double cameraZ = 0.0;
    private static volatile double lookX   = 0.0;
    private static volatile double lookY   = 0.0;
    private static volatile double lookZ   = 1.0;

    private final ThreadPoolExecutor executor;

    /** Shared scheduler for delayed re-meshing retries. Replaces spawning a Thread per failure. */
    private final ScheduledExecutorService retryScheduler;

    private final ConcurrentHashMap<ChunkSectionPos, PrioritizedMeshingTask> pendingTasks = new ConcurrentHashMap<>();

    private final ThreadLocal<ChunkMesher> threadLocalMesher = ThreadLocal.withInitial(ChunkMesher::new);

    /** Reusable per-thread section scratch buffer — avoids a large allocation per task. */
    private final ThreadLocal<ChunkSectionData> threadLocalSectionData =
        ThreadLocal.withInitial(ChunkSectionData::new);
    private volatile boolean running = false;

    /** Maximum retry attempts for a section whose palette was mid-update. */
    private static final int MAX_RETRIES = 3;

    private MeshingJobSystem(int threadCount) {
        AtomicInteger threadId = new AtomicInteger(0);
        this.executor = new ThreadPoolExecutor(
            threadCount,
            threadCount,
            0L, TimeUnit.MILLISECONDS,
            new PriorityBlockingQueue<>(),
            r -> {
                Thread t = new Thread(r, "Caesium-Mesher-" + threadId.getAndIncrement());
                t.setDaemon(true);
                // Defaults strictly BELOW the render thread. Meshing is throughput work;
                // the render thread is latency work. Running meshers at or above
                // NORM_PRIORITY starves the render thread during world load and causes
                // exactly the stutter this system exists to prevent.
                t.setPriority(switch (RendererConfig.get().chunkWorkerPriority) {
                    case 2  -> Thread.NORM_PRIORITY;
                    case 1  -> Thread.NORM_PRIORITY - 1;
                    default -> Thread.MIN_PRIORITY;
                });
                return t;
            }
        );
        // Let idle mesher threads die back so a brief load burst does not pin cores forever.
        this.executor.setKeepAliveTime(30L, TimeUnit.SECONDS);
        this.executor.allowCoreThreadTimeOut(true);

        this.retryScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Caesium-MeshRetry");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });

        this.running = true;
        LOGGER.info("[Caesium] MeshingJobSystem started with " + threadCount + " background threads.");
    }

    public static void setSteadyMode() {
        // Obsolete
    }

    public static void initialize() {
        // resolvedMeshingThreads() already handles the auto-detect (0) case and leaves
        // headroom for the render thread and the OS. The previous "max(4, cores-1)"
        // oversubscribed low-core machines badly.
        int threads = RendererConfig.get().resolvedMeshingThreads();
        instance = new MeshingJobSystem(threads);
    }

    public static void shutdown() {
        if (instance != null) {
            instance.running = false;
            instance.retryScheduler.shutdownNow();
            instance.executor.shutdown();
            try {
                if (!instance.executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    instance.executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                instance.executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            instance.pendingTasks.clear();
            LOGGER.info("[Caesium] MeshingJobSystem shut down.");
            instance = null;
        }
    }

    public static MeshingJobSystem get() {
        return instance;
    }

    /**
     * Updates the current camera position and forward look vector used for task priority sorting.
     */
    public static void updateCameraPosition(double x, double y, double z) {
        cameraX = x;
        cameraY = y;
        cameraZ = z;
    }

    public static void updateCamera(double x, double y, double z, double lx, double ly, double lz) {
        cameraX = x;
        cameraY = y;
        cameraZ = z;
        lookX = lx;
        lookY = ly;
        lookZ = lz;
    }

    private static volatile long firstSubmitTime = 0;

    public void submit(ChunkSectionPos pos, ChunkSectionData data) {
        if (!running) return;

        if (firstSubmitTime == 0) {
            firstSubmitTime = System.currentTimeMillis();
        }

        // Cancel any outstanding task for this position
        PrioritizedMeshingTask existing = pendingTasks.get(pos);
        if (existing != null) {
            existing.cancel();
        }

        PrioritizedMeshingTask task = new PrioritizedMeshingTask(pos, data);
        pendingTasks.put(pos, task);
        executor.execute(task);
    }

    public void cancel(ChunkSectionPos pos) {
        PrioritizedMeshingTask task = pendingTasks.remove(pos);
        if (task != null) {
            task.cancel();
        }
    }

    public int pendingTaskCount() {
        return pendingTasks.size();
    }

    public long stealCount() {
        return 0L; // Not applicable for ThreadPoolExecutor
    }

    /**
     * Prioritized Runnable task comparing distance and view-cone alignment to camera coordinates.
     */
    private final class PrioritizedMeshingTask implements Runnable, Comparable<PrioritizedMeshingTask> {
        private final ChunkSectionPos pos;
        private final ChunkSectionData data;
        private final long submitTime = System.nanoTime();
        private final int attempt;
        private volatile boolean cancelled = false;

        PrioritizedMeshingTask(ChunkSectionPos pos, ChunkSectionData data) {
            this(pos, data, 0);
        }

        PrioritizedMeshingTask(ChunkSectionPos pos, ChunkSectionData data, int attempt) {
            this.pos = pos;
            this.data = data;
            this.attempt = attempt;
        }

        void cancel() {
            this.cancelled = true;
        }

        @Override
        public void run() {
            if (cancelled || !running) {
                pendingTasks.remove(pos, this);
                return;
            }

            try {
                ChunkMesher mesher = threadLocalMesher.get();
                ChunkSectionData localData = data;
                if (localData == null) {
                    localData = threadLocalSectionData.get();
                }

                long sectionKey = packSectionKey(pos.getSectionX(), pos.getSectionY(), pos.getSectionZ());
                boolean generated = mesher.mesh(sectionKey, localData);
            } catch (Throwable t) {
                if (attempt < MAX_RETRIES) {
                    scheduleRetry();
                }
            } finally {
                pendingTasks.remove(pos, this);
            }
        }

        /**
         * Re-queues this section after a short delay via the shared scheduler.
         * Previously this spawned a brand new Thread per failure, which during a world-join
         * burst could create thousands of threads each allocating its own mesher.
         */
        private void scheduleRetry() {
            long delayMs = 50L * (1L << attempt); // 50ms, 100ms, 200ms backoff
            try {
                retryScheduler.schedule(() -> {
                    if (!running || cancelled) return;
                    PrioritizedMeshingTask next = new PrioritizedMeshingTask(pos, null, attempt + 1);
                    pendingTasks.put(pos, next);
                    try {
                        executor.execute(next);
                    } catch (RejectedExecutionException ignored) {
                        // Pool shutting down — drop silently.
                    }
                }, delayMs, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException ignored) {
                // Scheduler shutting down.
            }
        }

        @Override
        public int compareTo(PrioritizedMeshingTask other) {
            double s1 = computeTaskScore(this.pos);
            double s2 = computeTaskScore(other.pos);
            int cmp = Double.compare(s1, s2);
            if (cmp != 0) return cmp;
            return Long.compare(this.submitTime, other.submitTime); // FIFO tie-breaker
        }

        private static double computeTaskScore(ChunkSectionPos p) {
            int camSecX = (int) Math.floor(cameraX) >> 4;
            int camSecY = (int) Math.floor(cameraY) >> 4;
            int camSecZ = (int) Math.floor(cameraZ) >> 4;

            int ring = Math.max(Math.abs(p.getSectionX() - camSecX),
                       Math.max(Math.abs(p.getSectionY() - camSecY),
                                Math.abs(p.getSectionZ() - camSecZ)));

            double dx = (p.getSectionX() << 4) + 8.0 - cameraX;
            double dy = (p.getSectionY() << 4) + 8.0 - cameraY;
            double dz = (p.getSectionZ() << 4) + 8.0 - cameraZ;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

            double score = ring * 100.0 + dist;
            if (dist > 0.1 && (lookX != 0.0 || lookZ != 0.0)) {
                double dot = (dx * lookX + dy * lookY + dz * lookZ) / dist;
                if (dot > 0.5) score -= 200.0;
                else if (dot > 0.0) score -= 80.0;
                else score += 150.0;
            }
            return score;
        }
    }
    /** Packs section XYZ into a single long key. 21 bits per axis covers ±1M chunks. */
    private static long packSectionKey(int x, int y, int z) {
        return ((long)(x & 0x1FFFFF)) | (((long)(y & 0x1FFFFF)) << 21) | (((long)(z & 0x1FFFFF)) << 42);
    }
}
