package destiny.renderer.render;

import destiny.renderer.config.RendererConfig;

/**
 * ShaderPresetManager — Manages visual graphics quality presets for Intel UHD integrated GPUs.
 *
 * <h2>Presets</h2>
 * <ul>
 *   <li><b>PERFORMANCE</b>: Maximum FPS — flat face-normal diffuse, no AO, fast linear distance fog.</li>
 *   <li><b>BALANCED</b>: Vanilla+ — smooth per-vertex AO, directional diffuse, distance fog.</li>
 *   <li><b>QUALITY</b>: High fidelity — per-fragment smooth AO, soft shadow approximation, FXAA anti-aliasing.</li>
 * </ul>
 *
 * All presets compile under OpenGL 3.3 Core Profile (#version 330 core).
 */
public final class ShaderPresetManager {

    public enum Preset {
        PERFORMANCE("Performance"),
        BALANCED("Balanced"),
        QUALITY("Quality");

        private final String name;
        Preset(String name) { this.name = name; }
        public String getName() { return name; }
    }

    private static Preset currentPreset = Preset.BALANCED;

    public static void setPreset(Preset preset) {
        if (preset != null) {
            currentPreset = preset;
        }
    }

    public static Preset getPreset() {
        return currentPreset;
    }

    public static boolean isAoEnabled() {
        return currentPreset != Preset.PERFORMANCE;
    }

    public static boolean isSmoothFragmentAo() {
        return currentPreset == Preset.QUALITY;
    }

    public static boolean isFxaaEnabled() {
        return currentPreset == Preset.QUALITY;
    }

    public static String getShaderHeader() {
        return "#version 330 core\n";
    }
}
