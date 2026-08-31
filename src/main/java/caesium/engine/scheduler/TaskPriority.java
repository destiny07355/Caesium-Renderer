package caesium.engine.scheduler;

/**
 * Task priority used by the scheduler and the work-stealing pool. The competitive policy
 * shifts spend between these classes (ARCHITECTURE.md §13).
 */
public enum TaskPriority {

    /** Crystals, players, damage effects, camera-near terrain, critical particles. */
    CRITICAL(0),

    /** Visible but non-critical work. */
    NORMAL(1),

    /** Far chunk rebuilding, cosmetic particles, low-priority animations. */
    BACKGROUND(2);

    private final int rank;

    TaskPriority(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }
}