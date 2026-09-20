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
    private static volatile double velX    = 0.0;
    private static volatile double velY    = 0.0;
    private static volatile double velZ    = 0.0;

    private static final int MAX_COMPLETION_QUEUE = 4096;
    private static final ConcurrentLinkedQueue<CompletedMesh> completionQueue = new ConcurrentLinkedQueue<>();
    private static final java.util.concurrent.atomic.AtomicInteger completionQueueSize = new java.util.concurrent.atomic.AtomicInteger(0);
    private static final ConcurrentHashMap<Long, AtomicInteger> sectionVersions = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, MeshJob> pendingJobs = new ConcurrentHashMap<>();

    public static int completionQueueDepth() {
        return completionQueueSize.get();
    }

    private ThreadPoolExecutor executor;

    /** Shared scheduler for delayed re-meshing retries. Replaces spawning a Thread per failure. */
    private ScheduledExecutorService retryScheduler;

    private int threadCount;

    private final ConcurrentHashMap<ChunkSectionPos, PrioritizedMeshingTask> pendingTasks = new ConcurrentHashMap<>();

    private final ThreadLocal<ChunkMesher> threadLocalMesher = ThreadLocal.withInitial(ChunkMesher::new);

    /** Reusable per-thread section scratch buffer — avoids a large allocation per task. */
    private final ThreadLocal<ChunkSectionData> threadLocalSectionData =
        ThreadLocal.withInitial(ChunkSectionData::new);
    private volatile boolean running = false;

    public static void offerCompleted(CompletedMesh mesh) {
        if (mesh == null) return;
        // Staleness check before queueing
        AtomicInteger curVer = sectionVersions.get(mesh.posKey());
        if (curVer != null && mesh.version() < curVer.get()) {
            return; // Obsolete version, discard immediately
        }
        // Bounded capacity check
        if (completionQueueSize.get() >= MAX_COMPLETION_QUEUE) {
            // Queue full: poll and discard oldest entries to keep freshest
            CompletedMesh discarded = completionQueue.poll();
            if (discarded != null) {
                completionQueueSize.decrementAndGet();
            }
        }
        completionQueue.offer(mesh);
        completionQueueSize.incrementAndGet();
    }

    public static int nextVersion(long posKey) {
        return sectionVersions.computeIfAbsent(posKey, k -> new AtomicInteger()).incrementAndGet();
    }

    public static int currentVersion(long posKey) {
        AtomicInteger v = sectionVersions.get(posKey);
        return v != null ? v.get() : 0;
    }

    public static void setVersion(long posKey, int version) {
        AtomicInteger v = sectionVersions.get(posKey);
        if (v != null) {
            v.set(version);
        } else {
            sectionVersions.put(posKey, new AtomicInteger(version));
        }
    }

    public static boolean isLatest(long posKey, int version) {
        AtomicInteger v = sectionVersions.get(posKey);
        return v == null || version >= v.get();
    }

    public static void registerJob(MeshJob job) {
        if (job == null) return;
        MeshJob old = pendingJobs.put(job.posKey(), job);
        if (old != null) {
            old.cancel();
        }
    }

    public static void completeJob(long posKey, MeshJob job) {
        pendingJobs.remove(posKey, job);
    }

    public static void tickAging() {
        if (pendingJobs.isEmpty()) return;
        for (MeshJob job : pendingJobs.values()) {
            if (job != null && !job.isCancelled()) {
                job.age();
                job.updateScore(cameraX, cameraY, cameraZ, lookX, lookY, lookZ, velX, velY, velZ);
            }
        }
    }

    public static int drainCompletedMeshes(caesium.engine.world.SceneManager scene, double budgetMs) {
        if (scene == null || completionQueue.isEmpty()) return 0;
        long startNs = System.nanoTime();
        // Use 85% of budget as a safety margin so GPU push overhead doesn't blow the frame deadline
        long maxNanos = (long) (Math.max(0.2, budgetMs * 0.85) * 1_000_000.0);
        int drained = 0;
        while (!completionQueue.isEmpty()) {
            // Pre-check budget BEFORE polling the mesh to guarantee hard deadline compliance
            if (drained > 0 && (System.nanoTime() - startNs >= maxNanos)) {
                break;
            }
            CompletedMesh cm = completionQueue.poll();
            if (cm == null) break;
            completionQueueSize.decrementAndGet();
            AtomicInteger curVer = sectionVersions.get(cm.posKey());
            if (curVer != null && cm.version() < curVer.get()) {
                continue; // Stale mesh discarded
            }
            if (cm.layeredMesh() != null) {
                scene.push(new caesium.engine.world.DeltaCommand.LayeredSectionMeshUpdated(cm.layeredMesh()));
                drained++;
            }
        }
        return drained;
    }

    /** Maximum retry attempts for a section whose palette was mid-update. */
    private static final int MAX_RETRIES = 3;

    private static final int QUEUE_CAPACITY = 1024;

    public static final class BoundedPriorityBlockingQueue extends PriorityBlockingQueue<Runnable> {
        private final int capacity;
        private double currentWorstScore = Double.MIN_VALUE;

        public BoundedPriorityBlockingQueue(int capacity) {
            super(capacity);
            this.capacity = capacity;
        }

        @Override
        public synchronized boolean offer(Runnable e) {
            if (size() >= capacity) {
                if (e instanceof PrioritizedMeshingTask incoming) {
                    if (currentWorstScore > 0.0 && incoming.score >= currentWorstScore) {
                        return false;
                    }

                    Object[] elements = this.toArray();
                    PrioritizedMeshingTask worst = null;
                    int start = elements.length / 2;
                    double nextWorst = Double.MIN_VALUE;
                    for (int i = start; i < elements.length; i++) {
                        if (elements[i] instanceof PrioritizedMeshingTask task) {
                            if (worst == null || task.score > worst.score) {
                                if (worst != null) nextWorst = Math.max(nextWorst, worst.score);
                                worst = task;
                            } else {
                                nextWorst = Math.max(nextWorst, task.score);
                            }
                        }
                    }
                    if (worst != null) {
                        currentWorstScore = worst.score;
                    }
                    if (worst != null && incoming.score < worst.score) {
                        this.remove(worst);
                        worst.cancel();
                        destiny.renderer.hud.CaesiumFrameProfiler.recordMeshingEvicted();
                        currentWorstScore = Math.max(nextWorst, incoming.score);
                        return super.offer(incoming);
                    }
                }
                return false;
            }
            if (e instanceof PrioritizedMeshingTask task) {
                currentWorstScore = Math.max(currentWorstScore, task.score);
            }
            return super.offer(e);
        }

        @Override
        public synchronized Runnable poll() {
            Runnable r = super.poll();
            if (isEmpty()) {
                currentWorstScore = Double.MIN_VALUE;
            }
            return r;
        }
    }

    private MeshingJobSystem(int threadCount) {
        this.threadCount = Math.max(1, threadCount);
        this.running = true;
        LOGGER.info("[Caesium] MeshingJobSystem configured with " + this.threadCount + " background threads.");
    }

    private synchronized void ensureExecutor() {
        if (!running || executor != null) return;
        AtomicInteger threadId = new AtomicInteger(0);
        this.executor = new ThreadPoolExecutor(
            threadCount,
            threadCount,
            0L, TimeUnit.MILLISECONDS,
            new BoundedPriorityBlockingQueue(QUEUE_CAPACITY),
            r -> {
                Thread t = new Thread(r, "Caesium-Mesher-" + threadId.getAndIncrement());
                t.setDaemon(true);
                t.setPriority(switch (RendererConfig.get().chunkWorkerPriority) {
                    case 2  -> Thread.NORM_PRIORITY + 1;
                    case 1  -> Thread.NORM_PRIORITY;
                    default -> Thread.NORM_PRIORITY;
                });
                return t;
            }
        );
        this.executor.setRejectedExecutionHandler((r, exec) -> {
            if (r instanceof PrioritizedMeshingTask task) {
                destiny.renderer.hud.CaesiumFrameProfiler.recordMeshingRejected();
                if (task.attempt < MAX_RETRIES) {
                    destiny.renderer.hud.CaesiumFrameProfiler.recordMeshingDeferred();
                    task.scheduleRetry();
                } else {
                    pendingTasks.remove(task.pos, task);
                }
            }
        });
        this.executor.setKeepAliveTime(30L, TimeUnit.SECONDS);
        this.executor.allowCoreThreadTimeOut(true);

        this.retryScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Caesium-MeshRetry");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
        LOGGER.info("[Caesium] MeshingJobSystem background thread pool started with " + threadCount + " threads.");
    }

    public void updateWorkerCount(int targetThreads) {
        this.threadCount = Math.max(1, targetThreads);
        if (executor != null && running && targetThreads >= 1) {
            int currentCore = executor.getCorePoolSize();
            if (currentCore != targetThreads) {
                if (targetThreads > currentCore) {
                    executor.setMaximumPoolSize(targetThreads);
                    executor.setCorePoolSize(targetThreads);
                } else {
                    executor.setCorePoolSize(targetThreads);
                    executor.setMaximumPoolSize(targetThreads);
                }
                LOGGER.info("[Caesium] Meshing pool resized from " + currentCore + " to " + targetThreads + " threads.");
            }
        }
    }

    public static void setSteadyMode() {
        // Obsolete
    }

    public static void initialize() {
        int threads = RendererConfig.get().resolvedMeshingThreads();
        instance = new MeshingJobSystem(threads);
    }

    public static void clear() {
        completionQueue.clear();
        completionQueueSize.set(0);
        sectionVersions.clear();
        pendingJobs.clear();
        if (instance != null) {
            instance.pendingTasks.clear();
        }
    }

    public static void shutdown() {
        if (instance != null) {
            instance.running = false;
            if (instance.retryScheduler != null) {
                instance.retryScheduler.shutdownNow();
            }
            if (instance.executor != null) {
                instance.executor.shutdown();
                try {
                    if (!instance.executor.awaitTermination(5, TimeUnit.SECONDS)) {
                        instance.executor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    instance.executor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
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
        double dx = x - cameraX;
        double dy = y - cameraY;
        double dz = z - cameraZ;
        double distSq = dx * dx + dy * dy + dz * dz;

        // Teleport or fast dimension transition (> 32 blocks)
        if (distSq > 1024.0) {
            purgeDistantJobs(x, y, z, 64.0);
            velX = 0.0;
            velY = 0.0;
            velZ = 0.0;
        } else {
            velX = dx;
            velY = dy;
            velZ = dz;
        }

        cameraX = x;
        cameraY = y;
        cameraZ = z;
        lookX = lx;
        lookY = ly;
        lookZ = lz;
    }

    /**
     * Purges and cancels speculative and maintenance meshing jobs further than maxDist from the player
     * after a sudden position jump or teleportation.
     */
    public static void purgeDistantJobs(double cx, double cy, double cz, double maxDist) {
        if (pendingJobs.isEmpty()) return;
        double maxDistSq = maxDist * maxDist;
        pendingJobs.entrySet().removeIf(entry -> {
            MeshJob job = entry.getValue();
            if (job == null) return true;
            if (job.urgency() == MeshJob.Urgency.PREDICTIVE || job.urgency() == MeshJob.Urgency.MAINTENANCE) {
                double jx = job.pos().getMinX() + 8.0 - cx;
                double jy = job.pos().getMinY() + 8.0 - cy;
                double jz = job.pos().getMinZ() + 8.0 - cz;
                if (jx * jx + jy * jy + jz * jz > maxDistSq) {
                    job.cancel();
                    return true;
                }
            }
            return false;
        });
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
        ensureExecutor();
        if (executor != null) {
            executor.execute(task);
        }
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
        private final double score;
        private volatile boolean cancelled = false;

        PrioritizedMeshingTask(ChunkSectionPos pos, ChunkSectionData data) {
            this(pos, data, 0);
        }

        PrioritizedMeshingTask(ChunkSectionPos pos, ChunkSectionData data, int attempt) {
            this.pos = pos;
            this.data = data;
            this.attempt = attempt;
            this.score = computeTaskScore(pos);
        }

        void cancel() {
            this.cancelled = true;
        }

        @Override
        public void run() {
            if (com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread()) {
                destiny.renderer.hud.CaesiumFrameProfiler.recordCallerExecutedHeavyJob();
                LOGGER.warning("[Caesium] CRITICAL VIOLATION: Chunk meshing executed on render thread!");
            }

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
            int cmp = Double.compare(this.score, other.score);
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
    public static long elapsedMillis(long startNs, long endNs) {
        long delta = endNs - startNs;
        return delta <= 0L ? 0L : delta / 1_000_000L;
    }

    /** Packs section XYZ into a single long key. 21 bits per axis covers ±1M chunks. */
    private static long packSectionKey(int x, int y, int z) {
        return ((long)(x & 0x1FFFFF)) | (((long)(y & 0x1FFFFF)) << 21) | (((long)(z & 0x1FFFFF)) << 42);
    }
}
