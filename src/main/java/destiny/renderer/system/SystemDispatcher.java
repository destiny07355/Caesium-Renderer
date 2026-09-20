package destiny.renderer.system;

import destiny.renderer.system.common.IRendererSystem;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Dynamic runtime dispatcher that detects the client's running Java version and
 * activates either the Java 21 system (from folder {@code system/java21}) or the Java 25 system
 * (from folder {@code system/java25}), sharing baseline interfaces and utilities from folder {@code system/common}.
 */
public final class SystemDispatcher {

    private static final Logger LOGGER = Logger.getLogger("Caesium/System");
    private static final int DETECTED_JAVA_VERSION;
    private static final IRendererSystem ACTIVE_SYSTEM;

    static {
        int ver;
        try {
            ver = Runtime.version().feature();
        } catch (Throwable t) {
            ver = 21; // safe fallback
        }
        DETECTED_JAVA_VERSION = ver;

        IRendererSystem selected = null;
        if (ver >= 25) {
            try {
                Class<?> cls = Class.forName("destiny.renderer.system.java25.Java25System");
                selected = (IRendererSystem) cls.getDeclaredConstructor().newInstance();
                LOGGER.info("[Caesium] Client is running Java " + ver + ". Activated Java 25 system from folder: " + selected.systemFolder());
            } catch (Throwable t) {
                LOGGER.log(Level.WARNING, "[Caesium] Java " + ver + " detected but Java 25 system failed to load, falling back to Java 21 system: " + t.getMessage());
            }
        }

        if (selected == null) {
            try {
                Class<?> cls = Class.forName("destiny.renderer.system.java21.Java21System");
                selected = (IRendererSystem) cls.getDeclaredConstructor().newInstance();
                LOGGER.info("[Caesium] Client is running Java " + ver + ". Activated Java 21 system from folder: " + selected.systemFolder());
            } catch (Throwable t) {
                LOGGER.log(Level.SEVERE, "[Caesium] Failed to load Java 21 system via reflection, falling back to direct instantiation: " + t.getMessage());
                selected = new destiny.renderer.system.java21.Java21System();
            }
        }

        ACTIVE_SYSTEM = selected;
    }

    private SystemDispatcher() {}

    /** Returns the active platform system selected for the current client JVM. */
    public static IRendererSystem getActiveSystem() {
        return ACTIVE_SYSTEM;
    }

    /** Returns the detected Java feature version running on this client. */
    public static int getDetectedJavaVersion() {
        return DETECTED_JAVA_VERSION;
    }

    /** True if the active system is running under the Java 25 system profile. */
    public static boolean isJava25() {
        return ACTIVE_SYSTEM.javaVersion() >= 25;
    }

    /** Initializes the active system. */
    public static void initialize() {
        ACTIVE_SYSTEM.initialize();
    }

    /** Shuts down the active system and releases native memory. */
    public static void shutdown() {
        ACTIVE_SYSTEM.shutdown();
    }
}
