package destiny.renderer.render.drs;

import destiny.renderer.hud.PerformanceOverlay;

public final class DynamicResolutionScaler {

    private static boolean enabled = false;
    private static float currentScale = 1.0f;
    private static float targetScale = 1.0f;
    private static float sharpness = 0.5f;

    private static double fastEmaMs = 16.67;
    private static int framesStable = 0;
    private static final int STABILITY_HOLD_FRAMES = 60; // Require 60 consecutive stable frames before recovering scale

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean state) {
        enabled = state;
        if (!state) {
            currentScale = 1.0f;
            targetScale = 1.0f;
            framesStable = 0;
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
            targetScale = 1.0f;
            framesStable = 0;
            return;
        }

        double avgMs = PerformanceOverlay.averageFrameMs();
        if (avgMs <= 0.0) return;

        double p995Ms = PerformanceOverlay.percentileFrameMs995();
        fastEmaMs = fastEmaMs * 0.70 + avgMs * 0.30;

        // Instantaneous Spike Detection: if p99.5 or fast EMA exceeds target, drop scale immediately
        if (p995Ms > targetFrameMs * 1.20 || fastEmaMs > targetFrameMs * 1.15) {
            framesStable = 0; // Reset stability hold
            targetScale = Math.max(0.65f, targetScale - 0.10f);
        } else if (avgMs > targetFrameMs * 1.05) {
            framesStable = 0;
            targetScale = Math.max(0.70f, targetScale - 0.05f);
        } else if (p995Ms < targetFrameMs * 0.85 && fastEmaMs < targetFrameMs * 0.80) {
            // Smooth gradual recovery only after holding stable for STABILITY_HOLD_FRAMES
            framesStable++;
            if (framesStable >= STABILITY_HOLD_FRAMES) {
                targetScale = Math.min(1.0f, targetScale + 0.008f);
            }
        }

        // Asymmetric response: fast drop (0.35) to protect 1% low, ultra-smooth recovery (0.03) to prevent resolution pumping
        float lerpRate = targetScale < currentScale ? 0.35f : 0.03f;
        currentScale += (targetScale - currentScale) * lerpRate;
        if (Math.abs(targetScale - currentScale) < 0.003f) {
            currentScale = targetScale;
        }
    }
}
