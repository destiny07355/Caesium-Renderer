package caesium.integration;

/**
 * Arms vanilla cancellation only after one complete, successful Caesium terrain frame.
 * A failed or missing group immediately disarms ownership for the next frame.
 */
public final class TerrainOwnershipGate {
    public static final int OPAQUE = 1;
    public static final int TRANSLUCENT = 1 << 1;
    public static final int TRIPWIRE = 1 << 2;
    public static final int ALL_GROUPS = OPAQUE | TRANSLUCENT | TRIPWIRE;

    private int completedGroups;
    private boolean frameSuccessful = true;
    private boolean armed;

    public void beginFrame() {
        armed = completedGroups == ALL_GROUPS && frameSuccessful;
        completedGroups = 0;
        frameSuccessful = true;
    }

    public boolean mayCancelVanilla() {
        return armed;
    }

    public void recordGroup(int group, boolean success) {
        if (!isSingleGroup(group) || (completedGroups & group) != 0) {
            frameSuccessful = false;
            armed = false;
            return;
        }
        completedGroups |= group;
        if (!success) {
            frameSuccessful = false;
            armed = false;
        }
    }

    private static boolean isSingleGroup(int group) {
        return group == OPAQUE || group == TRANSLUCENT || group == TRIPWIRE;
    }

    public void reset() {
        completedGroups = 0;
        frameSuccessful = true;
        armed = false;
    }
}
