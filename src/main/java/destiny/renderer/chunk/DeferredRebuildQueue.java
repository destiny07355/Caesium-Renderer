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
     *
     * <p>Ordering matters a great deal for how the deferral <em>feels</em>. When an
     * explosion at the surface breaks into a cave system it exposes a large number of
     * sections at once, most of them far away and behind you. Rebuilding nearest-first
     * means the geometry you are actually looking at appears immediately and the distant
     * backlog fills in over the following frames, instead of the renderer stalling on
     * whichever section happened to be queued first.
     */
    private static final PriorityQueue<Entry> pending =
        new PriorityQueue<>((a, b) -> Double.compare(a.score, b.score));

    /** Lock-free incoming queue to eliminate contention between worker threads and the render thread. */
    private static final java.util.concurrent.ConcurrentLinkedQueue<Entry> incoming =
        new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** Membership set so a section cannot be queued twice while it is already waiting. */
    private static final Set<ChunkBuilder.BuiltChunk> queued =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Hard ceiling; past this we stop deferring and let rebuilds run immediately. */
    private static final int MAX_PENDING = 4096;

    private static final class Entry {
        ChunkBuilder.BuiltChunk chunk;
        double score;

        Entry(ChunkBuilder.BuiltChunk chunk, double score) {
            this.chunk = chunk;
            this.score = score;
        }

        void set(ChunkBuilder.BuiltChunk chunk, double score) {
            this.chunk = chunk;
            this.score = score;
        }

        ChunkBuilder.BuiltChunk chunk() { return chunk; }
        double score() { return score; }
    }

    /** Non-blocking entry pool to eliminate allocation churn during rebuild bursts. */
    private static final java.util.concurrent.ConcurrentLinkedQueue<Entry> entryPool =
        new java.util.concurrent.ConcurrentLinkedQueue<>();

    private static Entry obtainEntry(ChunkBuilder.BuiltChunk chunk, double score) {
        Entry e = entryPool.poll();
        if (e != null) {
            e.set(chunk, score);
            return e;
        }
        return new Entry(chunk, score);
    }

    private static volatile double camX = 0.0, camY = 0.0, camZ = 0.0;
    private static volatile double camLookX = 0.0, camLookY = 0.0, camLookZ = 1.0;
    private static volatile boolean camReady = false;
    private static volatile boolean isFlying = false;

    private DeferredRebuildQueue() {}

    /**
     * Progressive Teleport Streaming priority calculation:
     * Pure arithmetic distance and dot-product scoring without sqrt or trigonometric calls.
     * P0: Current player section (distSq < 256) -> -2,000,000 bonus
     * P1: Forward visible view cone (< 60 deg cone) -> -1,000,000 bonus
     * P2: Cardinal adjacent sections (horizontal/vertical) -> -400,000 bonus
     * P3: Diagonals -> -100,000 bonus
     * P4: Behind player -> +500,000 penalty
     */
    private static double calculatePriorityScore(ChunkBuilder.BuiltChunk chunk) {
        try {
            BlockPos origin = chunk.getOrigin();
            double dx = (origin.getX() + 8.0) - camX;
            double dy = (origin.getY() + 8.0) - camY;
            double dz = (origin.getZ() + 8.0) - camZ;
            double distSq = dx * dx + dy * dy + dz * dz;

            // P0: Immediate player section (< 16 blocks = 256 distSq)
            if (distSq < 256.0) return distSq - 2_000_000.0;

            if (camReady) {
                // Dot product with precomputed unit look vector
                double dot = dx * camLookX + dy * camLookY + dz * camLookZ;
                if (dot > 0.0) {
                    // 60-degree cone check: dot / sqrt(distSq) > 0.5 <=> dot*dot > 0.25*distSq
                    if (dot * dot > 0.25 * distSq) {
                        return distSq - 1_000_000.0; // P1: Forward visible cone
                    }
                    if (Math.abs(dx) < 32.0 || Math.abs(dz) < 32.0) {
                        return distSq - 400_000.0; // P2: Cardinal adjacent
                    }
                    return distSq - 100_000.0; // P3: Diagonals
                } else {
                    return distSq + 500_000.0; // P4: Behind player
                }
            }
            return distSq;
        } catch (Throwable t) {
            return Double.MAX_VALUE;
        }
    }

    /**
     * Queues a section for a later rebuild with zero lock contention and pooled entries.
     *
     * @return true if it was accepted, false if the caller should proceed immediately
     */
    public static boolean defer(ChunkBuilder.BuiltChunk chunk) {
        if (chunk == null) return false;
        if (queued.size() >= MAX_PENDING) return false;
        if (!queued.add(chunk)) return true; // already waiting; drop duplicate request lock-free
        double score = calculatePriorityScore(chunk);
        incoming.add(obtainEntry(chunk, score));
        return true;
    }

    // -------------------------------------------------------------------------
    // Frame-pacing state for B2/B3/B4 (PROGRESS.md 1.10.0)
    // -------------------------------------------------------------------------

    /** Recorded at the start of the previous tick({@code processFrame}) call so the
     *  next call can estimate how much wall-time is being spent on the rest of the frame.
     *  Used by B3: if the frame is already deep into its target by the time we are
     *  called, we cut the second slice entirely. */
    private static long lastTickStartNs = 0L;
    /** Timestamp captured at {@link #processFrame} entry. */
    private static long currentTickStartNs = 0L;

    /** Configurable target frame budget in milliseconds. 60 fps ⇒ 16.67 ms. Resolved at
     *  call time from the vanilla {@code getMaxFps} option when F3-limited; otherwise the
     *  default is {@code 1000 / monitor-refresh}. The exact value matters less than its
     *  ratio to the live frame time, so a coarse fallback is fine. */
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

    /** Tracks whether the previous {@link #processFrame} invocation deferred its second
     *  slice because the frame was already late. If so, the next call doubles the first
     *  slice to avoid falling behind — skipped-frame coalescing (B4). Reset to 0 the
     *  moment we are able to spend that doubled slice. */
    private static int skippedLastFrame = 0;

    /** Remaining frames of the teleport burst budget multiplier. When a sudden position
     *  change (teleport) is detected, this is set to {@code teleportBurstDurationTicks}
     *  and the budget is multiplied by {@code teleportBurstMultiplier}. */
    private static int teleportBurstFrames = 0;

    /** Last known player position for teleport detection. */
    private static double lastPlayerX = 0, lastPlayerY = 0, lastPlayerZ = 0;
    private static boolean hasLastPosition = false;

    /** 70% of target — the "safe headroom" under which we will spend the budget
     *  normally. Above this slice we will fall back to the throttled amount. */
    private static final float SAFE_HEADROOM_RATIO = 0.70f;
    /** When we are already over the headroom, the second slice is dropped entirely to
     *  allow the next present to happen sooner. */
    private static boolean isFrameRunningLong() {
        if (currentTickStartNs == 0L || lastTickStartNs == 0L) return false;
        long elapsedNs = currentTickStartNs - lastTickStartNs;
        float elapsedMs = elapsedNs / 1_000_000.0f;
        // Compare the elapsed wall time since the previous call against the target frame.
        // If we are running long, the GPU/CPU is bottlenecked already; emitting more
        // rebuilds here just makes things worse.
        return elapsedMs > targetFrameMs() * SAFE_HEADROOM_RATIO;
    }

    /**
     * Re-submits up to the configured per-frame budget. Called once per frame on the
     * render thread after the level render pass (PROGRESS.md 1.10.0 / B1 — moved here
     * from the InGameHud.render TAIL so the burst lands earlier in the frame, away from
     * the present-and-vsync tail).
     *
     * <p>Delegates to the pacing steps (PROGRESS.md 1.11.0 / R8 — behaviour is
     * identical to the earlier inline B2+B3+B4):
     * <ol>
     *   <li>{@link #computeBudget} — backlog-scaled base budget, iGPU burst cap, then
     *       B3 adaptive shrink (p99.5 above 2x target → halve; live frame long → quarter).</li>
     *   <li>{@link #runFirstSlice} — B4 skipped-frame coalescing: the first slice is
     *       doubled when the previous frame's second slice was dropped.</li>
     *   <li>{@link #runSecondSliceIfHeadroom} — B2: the second slice only runs when the
     *       frame is judged to have headroom; otherwise the skip is remembered for
     *       coalescing.</li>
     * </ol>
     */
    public static void processFrame() {
        RendererConfig cfg = RendererConfig.get();
        if (!cfg.deferChunkUpdates) {
            drainAll();
            return;
        }

        // Update camera coordinates, look direction, and player state for arithmetic priority scoring
        var mc = MinecraftClient.getInstance();
        if (mc != null && mc.gameRenderer != null) {
            var cam = mc.gameRenderer.getCamera();
            if (cam != null && cam.isReady()) {
                var pos = cam.getCameraPos();
                camX = pos.x;
                camY = pos.y;
                camZ = pos.z;
                float yaw = cam.getYaw();
                float pitch = cam.getPitch();
                double radYaw = Math.toRadians(yaw);
                double radPitch = Math.toRadians(pitch);
                double cosP = Math.cos(radPitch);
                camLookX = -Math.sin(radYaw) * cosP;
                camLookY = -Math.sin(radPitch);
                camLookZ = Math.cos(radYaw) * cosP;
                camReady = true;
            }
            if (mc.player != null) {
                isFlying = mc.player.isGliding() || mc.player.getAbilities().flying;
            }
        }

        // Drain lock-free incoming submissions into priority pending queue
        Entry incomingEntry;
        while ((incomingEntry = incoming.poll()) != null) {
            pending.add(incomingEntry);
        }

        // Teleport burst detection: if the player moved more than the threshold in one
        // tick (e.g., /tp command), activate the burst budget multiplier.
        if (cfg.teleportBurstMultiplier > 1.0 && cfg.teleportBurstThreshold > 0) {
            if (mc != null && mc.player != null) {
                double dx = mc.player.getX() - lastPlayerX;
                double dy = mc.player.getY() - lastPlayerY;
                double dz = mc.player.getZ() - lastPlayerZ;
                double distSq = dx * dx + dy * dy + dz * dz;
                double thresholdSq = (double) cfg.teleportBurstThreshold * cfg.teleportBurstThreshold;
                if (hasLastPosition && distSq > thresholdSq) {
                    // Scale burst duration: short-range tp = 30f, long-range RTP = up to 120f
                    double dist = Math.sqrt(distSq);
                    int burstDuration = dist > 500 ? 120 : (dist > 100 ? 60 : 30);
                    teleportBurstFrames = Math.max(teleportBurstFrames, burstDuration);
                    if (dist > 100) {
                        // On long-range teleport, clear the old pending queue — stale sections
                        // from the old position waste the burst budget and delay loading new terrain.
                        incoming.clear();
                        pending.clear();
                        queued.clear();
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

    private static int computeBudget(boolean frameLong) {
        int backlog = size();
        int base = Math.max(1, RendererConfig.get().maxChunkUpdatesPerFrame);
        if (backlog > 512)      base *= 4;
        else if (backlog > 128) base *= 3;
        else if (backlog > 16)  base *= 2;

        var mc = MinecraftClient.getInstance();
        if (mc != null && mc.player != null && (mc.player.isGliding() || mc.player.getAbilities().flying)) {
            base = Math.max(base, 8);
        }

        if (destiny.renderer.hardware.HardwareCapabilityDetector.isDetected()
            && destiny.renderer.hardware.HardwareCapabilityDetector.getProfile() != null
            && destiny.renderer.hardware.HardwareCapabilityDetector.getProfile().isIGPU()) {
            base = Math.min(base, 12);
        }

        // Teleport burst: multiply budget for a few frames after a large position change.
        if (teleportBurstFrames > 0) {
            double mult = RendererConfig.get().teleportBurstMultiplier;
            if (mult > 1.0) base = (int) Math.ceil(base * mult);
        }

        double p995Ms = destiny.renderer.hud.PerformanceOverlay.percentileFrameMs995();
        if (p995Ms > targetFrameMs() * 2.0) {
            base = Math.max(1, base / 2);
        }
        if (frameLong) {
            base = Math.max(1, base / 4);
        }

        // Dynamic headroom budgeting: if frame has extra slack (> 2.5ms), allow progressive burst
        if (currentTickStartNs > 0 && lastTickStartNs > 0) {
            float elapsedMs = (currentTickStartNs - lastTickStartNs) / 1_000_000.0f;
            float headroomMs = targetFrameMs() - elapsedMs;
            if (headroomMs > 2.5f) {
                base = Math.min(base * 2, 48);
            }
        }
        return base;
    }

    /**
     * Runs the first slice and reports whether the backlog is still pending (B4).
     * The first slice always runs so the backlog can never grow unbounded; when the
     * previous frame's second slice was dropped, this slice is doubled — the same
     * average throughput, but the dropped slice moves the bad momenta out of two
     * consecutive frames into one, i.e. the 1% low moves up.
     */
    private static boolean runFirstSlice(int base) {
        int firstSlice = base;
        if (skippedLastFrame > 0) {
            firstSlice = Math.min(firstSlice * 2, base * 2);
        }
        runSlice(firstSlice);
        return size() > 0;
    }

    /**
     * B2: the second slice is only granted if the frame is judged to have headroom (the
     * previous call was not late). This is the cheap, GL-free proxy for the original
     * "previous frame's GL fence signalled GPU completion" idea, and it is actually safer
     * here because vanilla owns the GL context and an extra fence would not have
     * integrated cleanly with its command ordering.
     *
     * <p>When the backlog is drained the coalescing marker clears so the next burst
     * starts from a non-doubled budget. When the backlog is pending but the frame is
     * late, the second slice is deferred to the next frame and the skip is remembered.
     */
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

    /** Runs up to {@code budget} rebuilds from the head of the pending queue. */
    private static int runSlice(int budget) {
        int spent = 0;
        for (int i = 0; i < budget; i++) {
            Entry e = pending.poll();
            if (e == null) return spent;
            ChunkBuilder.BuiltChunk chunk = e.chunk();
            queued.remove(chunk);

            // Lazy incremental pruning: during fast flight, drop sections trailing far behind camera
            if (isFlying && camReady && isStaleTrailing(chunk)) {
                entryPool.offer(e);
                i--; // don't count towards rebuild budget
                continue;
            }

            try {
                // Marked important so this pass is not intercepted and deferred again.
                chunk.scheduleRebuild(true);
            } catch (Throwable ignored) {
                // A section unloaded while queued; nothing to rebuild.
            }
            entryPool.offer(e);
            spent++;
        }
        return spent;
    }

    private static boolean isStaleTrailing(ChunkBuilder.BuiltChunk chunk) {
        BlockPos origin = chunk.getOrigin();
        if (origin == null) return false;
        double dx = origin.getX() + 8.0 - camX;
        double dy = origin.getY() + 8.0 - camY;
        double dz = origin.getZ() + 8.0 - camZ;
        double distSq = dx * dx + dy * dy + dz * dz;
        if (distSq > 96.0 * 96.0) {
            double dot = dx * camLookX + dy * camLookY + dz * camLookZ;
            return dot < -0.2;
        }
        return false;
    }

    /** Flushes everything immediately, used when deferral is switched off. */
    private static void drainAll() {
        Entry in;
        while ((in = incoming.poll()) != null) {
            pending.add(in);
        }
        while (true) {
            Entry e = pending.poll();
            if (e == null) return;
            ChunkBuilder.BuiltChunk chunk = e.chunk();
            queued.remove(chunk);
            try {
                chunk.scheduleRebuild(true);
            } catch (Throwable ignored) {
            }
            entryPool.offer(e);
        }
    }

    /** Drops everything, e.g. on world change where the sections no longer exist. */
    public static void clear() {
        incoming.clear();
        pending.clear();
        queued.clear();
    }

    public static int size() {
        return queued.size();
    }
}
