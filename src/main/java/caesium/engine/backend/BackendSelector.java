package caesium.engine.backend;

import caesium.engine.backend.opengl.OpenGLBackend;
import caesium.engine.backend.vulkan.VulkanBackend;

/**
 * Chooses which {@link GpuBackend} the engine starts with. Decision rules:
 * <ul>
 *   <li><b>Vulkan</b> — the primary backend (Month 2). Used only when the current driver
 *       exposes a working Vulkan device; the choice is validated before the backend is
 *       constructed so a broken driver can never crash startup.</li>
 *   <li><b>OpenGL</b> — selected when a current GL context exists (a running game). It is
 *       the reference/fallback backend and works on every machine.</li>
 *   <li><b>Null (software)</b> — used headless (tests, dev) or when no GL context is
 *       current at selection time.</li>
 * </ul>
 * The choice is logged via the backend name so the user can see why a path was taken.
 */
public final class BackendSelector {

    /** How the engine resolves the backend preference from the user's settings. */
    public enum Preference {
        /** OpenGL — works on every machine and driver. Default. */
        OPENGL,
        /** Vulkan when the driver supports it, otherwise OpenGL. */
        AUTO,
        /** Vulkan, falling back to OpenGL when unsupported (never a crash). */
        VULKAN
    }

    private BackendSelector() {
    }

    /** Selects the best backend for the current process state (OpenGL-preferred default). */
    public static GpuBackend select() {
        return select(Preference.OPENGL);
    }

    /**
     * Selects a backend honouring the user's preference. Vulkan is only chosen after a
     * live support probe on the current driver; any failure degrades to OpenGL and is
     * visible in the backend name, so a machine without Vulkan simply never attempts it.
     *
     * <p>The {@code devicePreference} is passed through to the Vulkan backend's physical
     * device selection (AUTO / DISCRETE / INTEGRATED / name substring). It is ignored by
     * the OpenGL backend, which uses whatever context the game created.
     */
    public static GpuBackend select(Preference preference, String devicePreference) {
        boolean wantVulkan = preference == Preference.VULKAN || preference == Preference.AUTO;
        if (wantVulkan && VulkanBackend.isSupported()) {
            try {
                return new VulkanBackend(devicePreference);
            } catch (Throwable t) {
                // Broken drivers must degrade to the reference backend, not crash.
                return fallbackOpenGl();
            }
        }
        if (wantVulkan) {
            return fallbackOpenGl();
        }
        if (hasOpenGlContext()) {
            return new OpenGLBackend();
        }
        return new NullBackend();
    }

    /** Selects a backend honouring the user's preference, with default device selection. */
    public static GpuBackend select(Preference preference) {
        return select(preference, "AUTO");
    }

    private static GpuBackend fallbackOpenGl() {
        if (hasOpenGlContext()) {
            return new OpenGLBackend();
        }
        return new NullBackend();
    }

    /**
     * True when the calling thread has a current, initialized GL context. Used at startup
     * to prefer the reference GL backend; falls back to the software backend otherwise.
     */
    public static boolean hasOpenGlContext() {
        try {
            return org.lwjgl.opengl.GL.getCapabilities() != null;
        } catch (Throwable ignored) {
            return false;
        }
    }
}