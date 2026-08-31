package caesium.engine.scheduler;

import caesium.engine.world.RenderWorld;

import java.util.List;

/**
 * Everything the scheduler needs to know about one frame, produced by the integration
 * layer. Carries the immutable world snapshot, camera-facing timing, whether the
 * competitive policy is active, and any recent explosions the responder should act on.
 */
public final class FrameInput {

    public record Explosion(float x, float y, float z, float radius, long timeMs) {
    }

    private final RenderWorld world;
    private final float deltaMillis;
    private final long timeMs;
    private final boolean competitive;
    private final List<Explosion> explosions;

    public FrameInput(RenderWorld world, float deltaMillis, long timeMs,
                      boolean competitive, List<Explosion> explosions) {
        this.world = world;
        this.deltaMillis = deltaMillis;
        this.timeMs = timeMs;
        this.competitive = competitive;
        this.explosions = List.copyOf(explosions);
    }

    public RenderWorld world() {
        return world;
    }

    public float deltaMillis() {
        return deltaMillis;
    }

    public long timeMs() {
        return timeMs;
    }

    public boolean competitive() {
        return competitive;
    }

    public List<Explosion> explosions() {
        return explosions;
    }
}