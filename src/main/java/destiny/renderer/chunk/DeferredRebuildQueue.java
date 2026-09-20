package destiny.renderer.chunk;

import destiny.renderer.config.RendererConfig;
import net.minecraft.client.render.chunk.ChunkBuilder;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

import java.util.HashSet;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Spreads chunk rebuilds across frames instead of letting them all land at once.
 *
 * <h2>The problem</h2>
 * A large blast, or crossing a chunk boundary at speed, queues a burst of section
 * rebuilds. Each is a full re-mesh of ~4096 blocks. When dozens arrive in the same frame
 * the render thread stalls, which is the two-second freeze after an anchor or crystal
 * explosion and the drop to ~12 fps while running.
 *
 * <h2>Why this is a queue and not a filter</h2>
 * An earlier attempt simply cancelled excess rebuild requests. That was wrong: a cancelled
 * request is never re-issued, so the section keeps its stale geometry and newly exposed
 * faces are never built — which showed up as blocks with invisible sides after explosions.
 *
 * <p>This queue holds the deferred sections and re-submits them on later frames, so every
 * rebuild still happens; only the timing changes. Nothing is ever dropped.
 */
public final class DeferredRebuildQueue {

    /**
     * Pending rebuilds ordered nearest-first.
     */
    private static final PriorityQueue<Entry> pending =
        new PriorityQueue<>((a, b) -> Double.compare(a.score, b.score));

    /** Membership set so a section cannot be queued twice while it is already waiting. */
    private static final Set<ChunkBuilder.BuiltChunk> queued = new HashSet<>();

    /** Hard ceiling; past this we stop deferring and let rebuilds run immediately. */
    private static final int MAX_PENDING = 4096;

    private record Entry(ChunkBuilder.BuiltChunk chunk, double score) {}

    private DeferredRebuildQueue() {}

    /**
     * Calculates view-cone aligned priority score (lower is higher priority).
     * Sections directly in front of camera and closest to player are prioritized first.
     */
    private static double calculatePriorityScore(ChunkBuilder.BuiltChunk chunk) {
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc == null || mc.gameRenderer == null) return Double.MAX_VALUE;
            net.minecraft.client.render.Camera cam = mc.gameRenderer.getCamera();
            if (cam == null || !cam.isReady()) {
                if (mc.player == null) return Double.MAX_VALUE;
                BlockPos origin = chunk.getOrigin();
                double dx = origin.getX() - mc.player.getX();
                double dy = origin.getY() - mc.player.getY();
                double dz = origin.getZ() - mc.player.getZ();
                return Math.sqrt(dx * dx + dy * dy + dz * dz);
            }
            net.minecraft.util.math.Vec3d pos = cam.getCameraPos();
            BlockPos origin = chunk.getOrigin();
            double dx = (origin.getX() + 8.0) - pos.x;
            double dy = (origin.getY() + 8.0) - pos.y;
            double dz = (origin.getZ() + 8.0) - pos.z;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

            double score = dist;

            // Immediate section bonus (within 16 blocks of player)
            if (dist < 16.0) score -= 1000.0;

            // View cone alignment: dot product with camera look direction
            float yaw = cam.getYaw();
            float pitch = cam.getPitch();
            double radYaw = Math.toRadians(yaw);
            double radPitch = Math.toRadians(pitch);
            double lx = -Math.sin(radYaw) * Math.cos(radPitch);
            double ly = -Math.sin(radPitch);
            double lz = Math.cos(radYaw) * Math.cos(radPitch);

            if (dist > 0.1) {
                double dot = (dx * lx + dy * ly + dz * lz) / dist;
                if (dot > 0.5) {
                    // Directly in front of camera (~60 deg cone)
                    score -= 500.0;
                } else if (dot > 0.0) {
                    // In front hemisphere
                    score -= 200.0;
                } else {
                    // Behind player
                    score += 300.0;
                }
            }
            return score;
        } catch (Throwable t) {
            return Double.MAX_VALUE;
        }
    }

    /**
     * Queues a section for a later rebuild.
     *
     * @return true if it was accepted, false if the caller should proceed immediately
     */
    public static synchronized boolean defer(ChunkBuilder.BuiltChunk chunk) {
        if (chunk == null) return false;
        if (pending.size() >= MAX_PENDING) return false;
        if (!queued.add(chunk)) return true; // already waiting; drop duplicate request
        pending.add(new Entry(chunk, calculatePriorityScore(chunk)));
        return true;
    }

    // -------------------------------------------------------------------------
    // Frame-pacing state
    // -------------------------------------------------------------------------

    private static long lastTickStartNs = 0L;
    private static long currentTickStartNs = 0L;

    private static float targetFrameMs() {
        try {
            var mc = MinecraftClient.getInstance();
            if (mc != null && mc.options != null) {
                int cap = mc.options.getMaxFps().getValue();
                if (cap > 0 && cap < 260) return 1000.0f / cap;
            }
        } catch (Throwable ignored) { }
        return 16.67f;
    }

    private static int skippedLastFrame = 0;

    /** Remaining frames of the teleport burst budget multiplier. */
    private static int teleportBurstFrames = 0;

    /** Last known player position for teleport detection. */
    private static double lastPlayerX = 0, lastPlayerY = 0, lastPlayerZ = 0;
    private static boolean hasLastPosition = false;

    private static final float SAFE_HEADROOM_RATIO = 0.70f;

    private static boolean isFrameRunningLong() {
        if (currentTickStartNs == 0L || lastTickStartNs == 0L) return false;
        long elapsedNs = currentTickStartNs - lastTickStartNs;
        float elapsedMs = elapsedNs / 1_000_000.0f;
        return elapsedMs > targetFrameMs() * SAFE_HEADROOM_RATIO;
    }

    public static void processFrame() {
        RendererConfig cfg = RendererConfig.get();
        if (!cfg.deferChunkUpdates) {
            drainAll();
            return;
        }

        // Update wall-clock tracking.
        long now = System.nanoTime();
        lastTickStartNs = currentTickStartNs;
        currentTickStartNs = now;

        // Teleport burst detection: if the player moved more than the threshold in one
        // tick (e.g., /rtp or warp), activate the burst budget multiplier.
        if (cfg.teleportBurstMultiplier > 1.0 && cfg.teleportBurstThreshold > 0) {
            var mc = MinecraftClient.getInstance();
            if (mc != null && mc.player != null) {
                double dx = mc.player.getX() - lastPlayerX;
                double dy = mc.player.getY() - lastPlayerY;
                double dz = mc.player.getZ() - lastPlayerZ;
                double distSq = dx * dx + dy * dy + dz * dz;
                int effectiveThreshold = Math.min(32, cfg.teleportBurstThreshold);
                double thresholdSq = (double) effectiveThreshold * effectiveThreshold;
                if (hasLastPosition && distSq > thresholdSq) {
                    // Scale burst duration: short-range tp = 90f, long-range RTP = up to 300f (~5s)
                    double dist = Math.sqrt(distSq);
                    int burstDuration = dist > 500 ? 300 : (dist > 100 ? 180 : 90);
                    teleportBurstFrames = Math.max(teleportBurstFrames, burstDuration);
                    if (dist > 64) {
                        // On long-range teleport, clear the old pending queue — stale sections
                        // from the old position waste the burst budget and delay loading new terrain.
                        synchronized (DeferredRebuildQueue.class) {
                            pending.clear();
                            queued.clear();
                        }
                    }
                }
                lastPlayerX = mc.player.getX();
                lastPlayerY = mc.player.getY();
                lastPlayerZ = mc.player.getZ();
                hasLastPosition = true;
            }
        }
        if (teleportBurstFrames > 0) teleportBurstFrames--;

        boolean frameLong = isFrameRunningLong();
        int base = computeBudget(frameLong);

        boolean stillPending = runFirstSlice(base);
        runSecondSliceIfHeadroom(stillPending, frameLong, base);
    }

    /** Whether a teleport burst is currently active (used to unthrottle incoming chunk builds). */
    public static boolean isTeleportBursting() {
        return teleportBurstFrames > 0;
    }

    private static int computeBudget(boolean frameLong) {
        int backlog = size();
        int base = Math.max(32, RendererConfig.get().maxChunkUpdatesPerFrame);
        if (backlog > 512)      base *= 4;
        else if (backlog > 128) base *= 3;
        else if (backlog > 16)  base *= 2;

        // Teleport burst: multiply budget for a sustained window after a large position change.
        if (teleportBurstFrames > 0) {
            double mult = Math.max(4.0, RendererConfig.get().teleportBurstMultiplier);
            base = (int) Math.ceil(base * mult);
            // In burst mode, do not strangle the budget even if p99.5 drops momentarily
            return Math.max(256, base);
        }

        // C2ME-style fast flight & high movement velocity detection:
        // When the player is flying with elytra or creative flight, terrain rebuild requests
        // flood in quickly. Boost the throughput along player trajectory instead of stalling.
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.player != null) {
            var p = mc.player;
            if (p.isGliding() || p.getAbilities().flying) {
                base = Math.max(base, 128);
            }
        }

        // When frame pacing is healthy and stable (FPS is high), maximize chunk loading throughput
        double avgMs = destiny.renderer.hud.PerformanceOverlay.averageFrameMs();
        float targetMs = targetFrameMs();
        if (avgMs > 0 && avgMs < targetMs * 0.75f && !frameLong) {
            base = (int) Math.round(base * 1.5);
        }

        if (destiny.renderer.hardware.HardwareCapabilityDetector.isDetected()
            && destiny.renderer.hardware.HardwareCapabilityDetector.getProfile() != null
            && destiny.renderer.hardware.HardwareCapabilityDetector.getProfile().isIGPU()) {
            base = Math.min(base, 96);
        }

        double p995Ms = destiny.renderer.hud.PerformanceOverlay.percentileFrameMs995();
        if (p995Ms > targetFrameMs() * 2.0) {
            base = Math.max(1, base / 2);
        }
        if (frameLong) {
            base = Math.max(1, base / 4);
        }
        return base;
    }

    private static boolean runFirstSlice(int base) {
        int firstSlice = base;
        if (skippedLastFrame > 0) {
            firstSlice = Math.min(firstSlice * 2, base * 2);
        }
        runSlice(firstSlice);
        return size() > 0;
    }

    private static void runSecondSliceIfHeadroom(boolean stillPending, boolean frameLong, int base) {
        if (!stillPending) {
            skippedLastFrame = 0;
            return;
        }
        if (frameLong) {
            skippedLastFrame = Math.min(skippedLastFrame + 1, 4);
            return;
        }
        runSlice(Math.max(1, base));
        skippedLastFrame = 0;
    }

    private static int runSlice(int budget) {
        int spent = 0;
        for (int i = 0; i < budget; i++) {
            ChunkBuilder.BuiltChunk chunk;
            synchronized (DeferredRebuildQueue.class) {
                Entry e = pending.poll();
                if (e == null) return spent;
                chunk = e.chunk();
                queued.remove(chunk);
            }
            try {
                chunk.scheduleRebuild(true);
            } catch (Throwable ignored) {
            }
            spent++;
        }
        return spent;
    }

    private static void drainAll() {
        while (true) {
            ChunkBuilder.BuiltChunk chunk;
            synchronized (DeferredRebuildQueue.class) {
                Entry e = pending.poll();
                if (e == null) return;
                chunk = e.chunk();
                queued.remove(chunk);
            }
            try {
                chunk.scheduleRebuild(true);
            } catch (Throwable ignored) {
            }
        }
    }

    public static synchronized void clear() {
        pending.clear();
        queued.clear();
    }

    public static synchronized int size() {
        return pending.size();
    }
}
