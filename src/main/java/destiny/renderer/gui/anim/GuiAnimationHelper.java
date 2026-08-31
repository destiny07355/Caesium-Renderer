package destiny.renderer.gui.anim;

/**
 * High-performance UI animation math and spring physics for Minecraft GUI rendering.
 *
 * <p>Provides framerate-independent exponential decay smoothing, cubic easing curves,
 * and critically damped harmonic oscillators for smooth 60–360+ FPS UI transitions.
 */
public final class GuiAnimationHelper {

    private GuiAnimationHelper() {}

    /**
     * Framerate-independent exponential smoothing (critically damped lerp).
     *
     * @param current current animated value
     * @param target  destination target value
     * @param speed   responsiveness (higher is snappier, e.g. 10.0–18.0)
     * @param delta   frame delta time in seconds (e.g. 0.016 for 60fps)
     * @return new smoothed value
     */
    public static float smoothDamp(float current, float target, float speed, float delta) {
        if (Math.abs(target - current) < 0.0001f) return target;
        float t = (float) (1.0 - Math.exp(-speed * Math.max(0.001f, Math.min(0.1f, delta))));
        return current + (target - current) * t;
    }

    /**
     * Ease-Out Cubic curve (fast start, silky deceleration).
     */
    public static float easeOutCubic(float x) {
        float inv = 1.0f - Math.max(0.0f, Math.min(1.0f, x));
        return 1.0f - inv * inv * inv;
    }

    /**
     * Ease-Out Back (slight energetic overshoot then settle).
     */
    public static float easeOutBack(float x) {
        float c1 = 1.70158f;
        float c3 = c1 + 1.0f;
        float inv = Math.max(0.0f, Math.min(1.0f, x)) - 1.0f;
        return 1.0f + c3 * inv * inv * inv + c1 * inv * inv;
    }

    /**
     * Smoothly blends two ARGB colors with alpha interpolation.
     */
    public static int lerpColor(int from, int to, float t) {
        t = Math.max(0.0f, Math.min(1.0f, t));
        int a1 = (from >> 24) & 0xFF;
        int r1 = (from >> 16) & 0xFF;
        int g1 = (from >> 8)  & 0xFF;
        int b1 = from & 0xFF;

        int a2 = (to >> 24) & 0xFF;
        int r2 = (to >> 16) & 0xFF;
        int g2 = (to >> 8)  & 0xFF;
        int b2 = to & 0xFF;

        int a = (int) (a1 + (a2 - a1) * t);
        int r = (int) (r1 + (r2 - r1) * t);
        int g = (int) (g1 + (g2 - g1) * t);
        int b = (int) (b1 + (b2 - b1) * t);

        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /**
     * Applies an alpha multiplier [0..1] to an ARGB color.
     */
    public static int applyAlpha(int argb, float alpha) {
        int a = (int) (((argb >> 24) & 0xFF) * Math.max(0.0f, Math.min(1.0f, alpha)));
        return (a << 24) | (argb & 0x00FFFFFF);
    }
}
