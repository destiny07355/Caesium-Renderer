package destiny.renderer.chunk;

import destiny.renderer.config.RendererConfig;
import destiny.renderer.hud.PerformanceOverlay;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.SimpleOption;

/**
 * Opt-in adaptive render distance (PROGRESS.md 1.11.0 / R3).
 *
 * <p>On integrated graphics the ceiling is fill rate and memory bandwidth — the machine
 * physically cannot hold a fixed high render distance. Rather than asking the user to
 * guess where that ceiling is, this nudges the vanilla {@code viewDistance} one chunk at
 * a time, a few seconds apart, so that the {@link PerformanceOverlay} p99.5 readout stays
 * at or above the target. It is the only realistic lever left for the iGPU tier: distance
 * <em>is</em> the cost.
 *
 * <h2>Behaviour</h2>
 * <ul>
 *   <li>Off by default and off until the user opts in — zero risk to mid/high-end users
 *       who leave it off.</li>
 *   <li>Every {@link #CHECK_INTERVAL_MS} it reads the cached p99.5 and moves distance
 *       one chunk, either down (floor 2, the vanilla minimum) or back up.</li>
 *   <li>Hysteresis band: raises only when p99.5 is comfortably above target
 *       ({@code target + HYSTERESIS_FPS}), lowers when below target. Prevents
 *       oscillating around the boundary.</li>
 *   <li>Never raises above the user's own maximum: the highest value the user had set
 *       (captured when the feature first runs, or when the user manually raises it)
 *       is a hard ceiling.</li>
 * </ul>
 */
public final class AdaptiveViewDistance {

    /** How often the controller may act, in wall-clock milliseconds. */
    private static final long CHECK_INTERVAL_MS = 5000L;
    /** Derive the p99.5 FPS floor from the vanilla FPS cap so 144hz+ users benefit. */
    private static int targetFps() {
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc != null && mc.options != null) {
                int cap = mc.options.getMaxFps().getValue();
                // Use 90% of the cap as the target floor, clamped between 30 and 360.
                if (cap > 0 && cap < 1000) return Math.max(30, Math.min(360, (int)(cap * 0.90f)));
            }
        } catch (Throwable ignored) {}
        return 60;
    }
    /** We only raise distance when p99.5 is this far above target, so a small dip in a
     *  mostly-healthy scene does not make the world yo-yo. */
    private static final int HYSTERESIS_FPS = 15;
    /** Vanilla's minimum view distance. */
    private static final int MIN_DISTANCE = 2;

    private static long lastCheckMs = 0L;
    /** The user's own chosen maximum; a hard ceiling the controller never exceeds. */
    private static int userMax = -1;

    private AdaptiveViewDistance() {}

    /**
     * Called once per rendered frame. No-op until the user enables
     * {@code adaptiveViewDistance}.
     */
    public static void tick() {
        RendererConfig cfg = RendererConfig.get();
        if (!cfg.adaptiveViewDistance) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.options == null || mc.world == null) return;

        long now = System.currentTimeMillis();
        if (now - lastCheckMs < CHECK_INTERVAL_MS) return;
        lastCheckMs = now;

        SimpleOption<Integer> view = mc.options.getViewDistance();
        if (view == null) return;

        int current = view.getValue();

        // (Re)capture the user's ceiling: their setting at enable time, or any value they
        // raise it to later. The controller only ever travels between MIN_DISTANCE and
        // this ceiling.
        if (current > userMax) userMax = current;

        int p995 = PerformanceOverlay.percentileFps995();
        if (p995 <= 0) return; // no samples yet; leave distance alone

        if (p995 < targetFps()) {
            if (current > MIN_DISTANCE) {
                view.setValue(current - 1);
            }
        } else if (p995 >= targetFps() + HYSTERESIS_FPS && current < userMax) {
            view.setValue(current + 1);
        }
    }
}