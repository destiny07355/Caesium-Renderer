package destiny.renderer.chunk;

import net.minecraft.util.math.ChunkSectionPos;

/**
 * Metadata-rich chunk section meshing descriptor.
 *
 * <p>Enforces deadline-aware priority ordering, starvation-preventing age scoring,
 * and generation/version tracking so obsolete meshing jobs can be cleanly cancelled.
 */
public final class MeshJob implements Comparable<MeshJob> {

    public enum Urgency {
        /** Immediate player interaction (e.g. broken/placed block under crosshair, < 16 blocks). */
        CRITICAL(10_000.0),
        /** Visible sections directly in the camera view frustum. */
        VISIBLE(5_000.0),
        /** Sections near frustum edges or in the direction of player movement. */
        PREDICTIVE(1_000.0),
        /** Distant chunk loads, relighting, and maintenance work. */
        MAINTENANCE(0.0);

        public final double baseBonus;
        Urgency(double baseBonus) {
            this.baseBonus = baseBonus;
        }
    }

    public enum JobReason {
        CHUNK_LOAD,
        BLOCK_CHANGE,
        LIGHT_UPDATE,
        BURST_RELOAD,
        RETRY
    }

    private final ChunkSectionPos pos;
    private final long posKey;
    private final int version;
    private final Urgency urgency;
    private final JobReason reason;
    private final boolean missingExistingGeometry;
    private final long enqueueTimeNs;
    private volatile int ageTicks;
    private volatile boolean cancelled;
    private double cachedScore;

    public MeshJob(ChunkSectionPos pos, long posKey, int version, Urgency urgency, JobReason reason) {
        this(pos, posKey, version, urgency, reason, false);
    }

    public MeshJob(ChunkSectionPos pos, long posKey, int version, Urgency urgency, JobReason reason, boolean missingExistingGeometry) {
        this.pos = pos;
        this.posKey = posKey;
        this.version = version;
        this.urgency = urgency;
        this.reason = reason;
        this.missingExistingGeometry = missingExistingGeometry;
        this.enqueueTimeNs = System.nanoTime();
        this.ageTicks = 0;
        this.cancelled = false;
        this.cachedScore = Double.MAX_VALUE;
    }

    public ChunkSectionPos pos() { return pos; }
    public long posKey() { return posKey; }
    public int version() { return version; }
    public Urgency urgency() { return urgency; }
    public JobReason reason() { return reason; }
    public boolean missingExistingGeometry() { return missingExistingGeometry; }
    public long enqueueTimeNs() { return enqueueTimeNs; }
    public int ageTicks() { return ageTicks; }
    public boolean isCancelled() { return cancelled; }

    public void cancel() {
        this.cancelled = true;
    }

    public void age() {
        this.ageTicks++;
    }

    /**
     * Computes the effective priority score (lower is higher priority).
     *
     * <p>Combines:
     * <ul>
     *   <li>Euclidean distance from camera</li>
     *   <li>View-cone alignment (dot product with look vector)</li>
     *   <li>Urgency class bonus</li>
     *   <li>Age score (prevents starvation of distant jobs)</li>
     * </ul>
     */
    public double updateScore(double camX, double camY, double camZ,
                              double lookX, double lookY, double lookZ) {
        return updateScore(camX, camY, camZ, lookX, lookY, lookZ, 0.0, 0.0, 0.0);
    }

    /**
     * Computes the effective priority score (lower is higher priority).
     *
     * <p>Combines:
     * <ul>
     *   <li>Euclidean distance from camera</li>
     *   <li>View-cone alignment (dot product with look vector)</li>
     *   <li>Velocity/approaching alignment (player moving toward chunk)</li>
     *   <li>Missing geometry boost (chunks with no GPU mesh get prioritized)</li>
     *   <li>Urgency class bonus</li>
     *   <li>Age score (prevents starvation of distant jobs)</li>
     * </ul>
     */
    public double updateScore(double camX, double camY, double camZ,
                              double lookX, double lookY, double lookZ,
                              double velX, double velY, double velZ) {
        if (cancelled) {
            cachedScore = Double.MAX_VALUE;
            return cachedScore;
        }

        double cx = pos.getMinX() + 8.0;
        double cy = pos.getMinY() + 8.0;
        double cz = pos.getMinZ() + 8.0;

        double dx = cx - camX;
        double dy = cy - camY;
        double dz = cz - camZ;
        double distSq = dx * dx + dy * dy + dz * dz;
        double dist = Math.sqrt(distSq);

        // Distance penalty
        double score = dist;

        // View-cone dot product (-1.0 behind .. +1.0 directly in front)
        if (dist > 0.1) {
            double dot = (dx * lookX + dy * lookY + dz * lookZ) / dist;
            if (dot > 0.6) {
                score -= 600.0; // Directly ahead in ~50° cone
            } else if (dot > 0.0) {
                score -= 250.0; // In forward hemisphere
            } else {
                score += 350.0; // Behind player
            }

            // Approaching velocity vector bonus (player sprinting/flying toward section)
            double velSq = velX * velX + velY * velY + velZ * velZ;
            if (velSq > 0.01) {
                double velMag = Math.sqrt(velSq);
                double velDot = (dx * velX + dy * velY + dz * velZ) / (dist * velMag);
                if (velDot > 0.3) {
                    score -= 400.0 * velDot;
                }
            }
        }

        // Missing geometry boost: prioritize newly revealed sections over re-meshing
        if (missingExistingGeometry) {
            score -= 1500.0;
        }

        // Urgency bonus
        score -= urgency.baseBonus;

        // Immediate proximity bonus (< 16 blocks from player)
        if (dist < 16.0) {
            score -= 1000.0;
        }

        // Starvation prevention: every tick/frame the job waits, score decreases (higher priority)
        score -= (double) ageTicks * 25.0;

        this.cachedScore = score;
        return score;
    }

    public double cachedScore() {
        return cachedScore;
    }

    @Override
    public int compareTo(MeshJob other) {
        int cmp = Double.compare(this.cachedScore, other.cachedScore);
        if (cmp != 0) return cmp;
        return Long.compare(this.enqueueTimeNs, other.enqueueTimeNs);
    }
}
