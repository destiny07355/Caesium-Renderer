package caesium.engine.scheduler;

import caesium.engine.world.DeltaCommand;

/**
 * Reacts to Crystal/Anchor/large explosions by shifting scheduling priorities: for a short
 * window everything inside the blast radius is treated as critical, which keeps the frame
 * from spiking when the explosion tears the world (the project KPI — ARCHITECTURE.md §19).
 *
 * <p>Tracks up to 4 concurrent explosions in a ring buffer so two crystals detonating
 * within the hot window do not silently overwrite each other's priority boost.
 */
public final class ExplosionResponder {

    private static final long HOT_WINDOW_MS = 1500L;
    private static final int  CAPACITY      = 4;

    private final long[]  timesMs = new long[CAPACITY];
    private final float[] xs      = new float[CAPACITY];
    private final float[] ys      = new float[CAPACITY];
    private final float[] zs      = new float[CAPACITY];
    private final float[] radii   = new float[CAPACITY];
    private int           head    = 0;

    public void onEvent(DeltaCommand.Explosion explosion) {
        int slot = head % CAPACITY;
        timesMs[slot] = explosion.timeMs();
        xs[slot]      = explosion.x();
        ys[slot]      = explosion.y();
        zs[slot]      = explosion.z();
        radii[slot]   = explosion.radius();
        head++;
    }

    /** Whether any tracked explosion is still within its hot window. */
    public boolean isHot(long nowMs) {
        for (int i = 0; i < CAPACITY; i++) {
            long elapsed = nowMs - timesMs[i];
            if (elapsed >= 0 && elapsed < HOT_WINDOW_MS) return true;
        }
        return false;
    }

    /** Whether a world-space position is inside any active blast radius (inflated 2x). */
    public boolean affects(float x, float y, float z) {
        long now = System.currentTimeMillis();
        for (int i = 0; i < CAPACITY; i++) {
            long elapsed = now - timesMs[i];
            if (elapsed < 0 || elapsed >= HOT_WINDOW_MS) continue;
            float dx = x - xs[i];
            float dy = y - ys[i];
            float dz = z - zs[i];
            if (dx * dx + dy * dy + dz * dz < radii[i] * radii[i] * 4f) return true;
        }
        return false;
    }

    public long hotWindowMs() {
        return HOT_WINDOW_MS;
    }
}