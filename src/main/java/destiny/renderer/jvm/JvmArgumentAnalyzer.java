package destiny.renderer.jvm;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.RuntimeMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Analyses the current JVM argument set at runtime, scores the configuration,
 * identifies missing or suboptimal flags, and writes a ready-to-paste
 * recommended-args file into the game's run directory.
 *
 * <p>Cannot modify running JVM args — JVM arguments are fixed at process start.
 * Instead, this class surfaces recommendations through the settings UI and the
 * log, and exports a launcher-ready file the user can paste into their profile.
 */
public final class JvmArgumentAnalyzer {

    private static final Logger LOGGER = Logger.getLogger("Caesium/JvmOptimizer");

    // Singleton result — computed once on first call to analyze().
    private static volatile JvmReport cachedReport = null;

    private JvmArgumentAnalyzer() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Returns the cached report, computing it first if necessary. */
    public static JvmReport getReport() {
        if (cachedReport == null) {
            synchronized (JvmArgumentAnalyzer.class) {
                if (cachedReport == null) cachedReport = analyze();
            }
        }
        return cachedReport;
    }

    /**
     * Writes the recommended JVM arguments file to {@code <runDir>/caesium_jvm_args.txt}.
     * Safe to call from any thread.
     */
    public static void exportRecommendedArgs(Path runDir) {
        JvmReport report = getReport();
        Path out = runDir.resolve("caesium_jvm_args.txt");
        try {
            Files.writeString(out, report.buildRecommendedArgsFile());
            LOGGER.info("[Caesium/JvmOptimizer] Recommended JVM args written to: " + out);
        } catch (IOException e) {
            LOGGER.warning("[Caesium/JvmOptimizer] Could not write args file: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Analysis
    // -------------------------------------------------------------------------

    private static JvmReport analyze() {
        RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        List<String> inputArgs = runtime.getInputArguments();

        // Detect GC
        GcType gc = detectGc(inputArgs);

        // Heap sizes (in MB)
        long maxHeapMb = memory.getHeapMemoryUsage().getMax() / (1024 * 1024);
        long totalRamMb = Runtime.getRuntime().maxMemory() / (1024 * 1024);

        // Java version
        int javaVersion = detectJavaVersion();

        // Score and collect issues
        List<JvmIssue> issues = new ArrayList<>();
        int score = 100;

        // --- GC checks ---
        if (gc == GcType.SERIAL) {
            issues.add(new JvmIssue(Severity.CRITICAL,
                "Serial GC active",
                "Serial GC causes massive stop-the-world pauses. Minecraft will stutter badly.",
                "-XX:+UseG1GC" + (javaVersion >= 21 ? " (or -XX:+UseZGC -XX:+ZGenerational)" : "")));
            score -= 40;
        } else if (gc == GcType.PARALLEL) {
            issues.add(new JvmIssue(Severity.HIGH,
                "Parallel GC active",
                "Parallel GC targets throughput over latency — causes frame spikes during GC.",
                "-XX:+UseG1GC"));
            score -= 20;
        } else if (gc == GcType.G1 && javaVersion >= 21) {
            issues.add(new JvmIssue(Severity.LOW,
                "G1GC — ZGC would be better on Java 21+",
                "ZGC with generational mode has sub-millisecond pauses vs G1's 50ms target.",
                "-XX:+UseZGC -XX:+ZGenerational"));
            score -= 5;
        }

        // --- Heap tuning ---
        if (!containsArg(inputArgs, "-Xms")) {
            issues.add(new JvmIssue(Severity.MEDIUM,
                "-Xms not set (heap starts small and grows)",
                "Without -Xms matching -Xmx, the JVM starts with a tiny heap and slowly "
                + "grows it via GC pauses. Set -Xms = -Xmx to pre-allocate.",
                "-Xms" + maxHeapMb + "M"));
            score -= 10;
        } else {
            // Check if Xms == Xmx
            long xms = parseHeapMb(inputArgs, "-Xms");
            if (xms > 0 && xms < maxHeapMb * 0.8) {
                issues.add(new JvmIssue(Severity.MEDIUM,
                    "-Xms is much smaller than -Xmx",
                    "Heap growth from Xms to Xmx causes GC pauses. Set them equal.",
                    "-Xms" + maxHeapMb + "M"));
                score -= 8;
            }
        }

        if (maxHeapMb < 3072) {
            issues.add(new JvmIssue(Severity.HIGH,
                "Heap too small (" + maxHeapMb + " MB)",
                "Minecraft 1.21 with mods needs at least 3-4 GB. Low heap causes constant "
                + "GC churn and eventual OutOfMemoryError.",
                "-Xmx4G"));
            score -= 15;
        }

        // --- G1GC-specific tuning ---
        if (gc == GcType.G1) {
            if (!containsArg(inputArgs, "G1HeapRegionSize")) {
                issues.add(new JvmIssue(Severity.MEDIUM,
                    "G1HeapRegionSize not set",
                    "Default region size (1-32MB auto) often under-sizes for Minecraft's "
                    + "large block/chunk allocations. 32MB is optimal for 4GB+ heaps.",
                    "-XX:G1HeapRegionSize=32m"));
                score -= 5;
            }
            if (!containsArg(inputArgs, "MaxGCPauseMillis")) {
                issues.add(new JvmIssue(Severity.MEDIUM,
                    "MaxGCPauseMillis not set",
                    "G1GC defaults to 200ms pause target — at 60fps you have 16ms per frame. "
                    + "Set a tighter target.",
                    "-XX:MaxGCPauseMillis=37"));
                score -= 5;
            }
            if (!containsArg(inputArgs, "G1NewSizePercent")) {
                issues.add(new JvmIssue(Severity.LOW,
                    "G1 new-gen sizing not tuned",
                    "Default new-gen sizing may be too aggressive for Minecraft's "
                    + "mixed short/long-lived allocation pattern.",
                    "-XX:G1NewSizePercent=20 -XX:G1ReservePercent=20"));
                score -= 3;
            }
        }

        // --- General optimizations ---
        if (!containsArg(inputArgs, "AlwaysPreTouch")) {
            issues.add(new JvmIssue(Severity.LOW,
                "-XX:+AlwaysPreTouch not set",
                "Without this, the OS lazily maps heap pages, causing stutters when new "
                + "heap pages are touched for the first time mid-game.",
                "-XX:+AlwaysPreTouch"));
            score -= 4;
        }

        if (!containsArg(inputArgs, "DisableExplicitGC")) {
            issues.add(new JvmIssue(Severity.LOW,
                "-XX:+DisableExplicitGC not set",
                "Some libraries call System.gc() which triggers expensive full GC pauses. "
                + "Disable explicit GC calls.",
                "-XX:+DisableExplicitGC"));
            score -= 3;
        }

        if (!containsArg(inputArgs, "PerfDisableSharedMem")) {
            issues.add(new JvmIssue(Severity.LOW,
                "-XX:+PerfDisableSharedMem not set",
                "JVM perf counters written to /tmp can cause I/O stalls on Linux. "
                + "Disabling them is safe for gaming.",
                "-XX:+PerfDisableSharedMem"));
            score -= 2;
        }

        if (javaVersion >= 17 && !containsArg(inputArgs, "UseStringDeduplication")) {
            issues.add(new JvmIssue(Severity.LOW,
                "-XX:+UseStringDeduplication not set",
                "String deduplication can reduce heap usage by 5-15% in modded Minecraft "
                + "by deduplicating identical String objects.",
                "-XX:+UseStringDeduplication"));
            score -= 2;
        }

        // --- ZGC-specific ---
        if (gc == GcType.ZGC && javaVersion >= 21 && !containsArg(inputArgs, "ZGenerational")) {
            issues.add(new JvmIssue(Severity.MEDIUM,
                "ZGC without generational mode (Java 21+)",
                "Generational ZGC has significantly lower overhead than non-generational ZGC "
                + "for Minecraft's mixed allocation pattern.",
                "-XX:+ZGenerational"));
            score -= 8;
        }

        score = Math.max(0, score);

        LOGGER.info(String.format(
            "[Caesium/JvmOptimizer] Analysis complete. Score: %d/100. GC: %s. Heap: %dMB. Issues: %d",
            score, gc.displayName, maxHeapMb, issues.size()));

        for (JvmIssue issue : issues) {
            LOGGER.info(String.format("[Caesium/JvmOptimizer]   [%s] %s — Fix: %s",
                issue.severity().name(), issue.title(), issue.fix()));
        }

        return new JvmReport(score, gc, maxHeapMb, javaVersion, issues, inputArgs);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static boolean containsArg(List<String> args, String fragment) {
        for (String a : args) {
            if (a.contains(fragment)) return true;
        }
        return false;
    }

    private static long parseHeapMb(List<String> args, String prefix) {
        for (String a : args) {
            if (a.startsWith(prefix)) {
                String val = a.substring(prefix.length()).toUpperCase();
                try {
                    if (val.endsWith("G")) return Long.parseLong(val.substring(0, val.length()-1)) * 1024;
                    if (val.endsWith("M")) return Long.parseLong(val.substring(0, val.length()-1));
                    if (val.endsWith("K")) return Long.parseLong(val.substring(0, val.length()-1)) / 1024;
                    return Long.parseLong(val) / (1024*1024);
                } catch (NumberFormatException ignored) {}
            }
        }
        return -1;
    }

    private static GcType detectGc(List<String> args) {
        for (String a : args) {
            if (a.contains("UseZGC")) return GcType.ZGC;
            if (a.contains("UseShenandoahGC")) return GcType.SHENANDOAH;
            if (a.contains("UseG1GC")) return GcType.G1;
            if (a.contains("UseParallelGC")) return GcType.PARALLEL;
            if (a.contains("UseSerialGC")) return GcType.SERIAL;
        }
        // Java 9+ defaults to G1GC
        return GcType.G1;
    }

    private static int detectJavaVersion() {
        try {
            String ver = System.getProperty("java.version");
            if (ver.startsWith("1.")) return Integer.parseInt(ver.split("\\.")[1]);
            return Integer.parseInt(ver.split("[\\.\\-]")[0]);
        } catch (Exception e) {
            return 17;
        }
    }

    // -------------------------------------------------------------------------
    // Enums / Records
    // -------------------------------------------------------------------------

    public enum GcType {
        G1("G1GC"), ZGC("ZGC"), SHENANDOAH("Shenandoah"),
        PARALLEL("Parallel GC"), SERIAL("Serial GC");
        public final String displayName;
        GcType(String displayName) { this.displayName = displayName; }
    }

    public enum Severity { CRITICAL, HIGH, MEDIUM, LOW }

    public record JvmIssue(Severity severity, String title, String description, String fix) {}
}
