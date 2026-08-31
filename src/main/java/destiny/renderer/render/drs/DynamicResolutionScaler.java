package destiny.renderer.render.drs;

import destiny.renderer.hud.PerformanceOverlay;

public final class DynamicResolutionScaler {

    private static boolean enabled = false;
    private static float currentScale = 1.0f;
    private static float targetScale = 1.0f;
    private static float sharpness = 0.5f;

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean state) {
        enabled = state;
        if (!state) {
            currentScale = 1.0f;
            targetScale = 1.0f;
        }
    }

    public static float getCurrentScale() {
        return currentScale;
    }

    public static float getSharpness() {
        return sharpness;
    }

    public static void setSharpness(float s) {
        sharpness = Math.max(0.0f, Math.min(1.0f, s));
    }

    public static void update(double targetFrameMs) {
        if (!enabled) {
            currentScale = 1.0f;
            return;
        }

        double avgMs = PerformanceOverlay.averageFrameMs();
        if (avgMs <= 0.0) return;

        if (avgMs > targetFrameMs * 1.10) {
            targetScale = Math.max(0.70f, targetScale - 0.05f);
        } else if (avgMs < targetFrameMs * 0.85) {
            targetScale = Math.min(1.0f, targetScale + 0.02f);
        }

        currentScale += (targetScale - currentScale) * 0.10f;
        if (Math.abs(targetScale - currentScale) < 0.01f) {
            currentScale = targetScale;
        }
    }
}
