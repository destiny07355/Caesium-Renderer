package destiny.renderer.jvm;

import java.util.List;

/**
 * Immutable result of a JVM argument analysis pass.
 * Holds the score, detected configuration, and a list of actionable issues.
 */
public final class JvmReport {

    private final int score;
    private final JvmArgumentAnalyzer.GcType gc;
    private final long heapMb;
    private final int javaVersion;
    private final List<JvmArgumentAnalyzer.JvmIssue> issues;
    private final List<String> rawArgs;

    public JvmReport(int score, JvmArgumentAnalyzer.GcType gc, long heapMb,
                     int javaVersion, List<JvmArgumentAnalyzer.JvmIssue> issues,
                     List<String> rawArgs) {
        this.score       = score;
        this.gc          = gc;
        this.heapMb      = heapMb;
        this.javaVersion = javaVersion;
        this.issues      = List.copyOf(issues);
        this.rawArgs     = List.copyOf(rawArgs);
    }

    public int score()       { return score; }
    public JvmArgumentAnalyzer.GcType gc() { return gc; }
    public long heapMb()     { return heapMb; }
    public int javaVersion() { return javaVersion; }
    public List<JvmArgumentAnalyzer.JvmIssue> issues() { return issues; }
    public List<String> rawArgs()  { return rawArgs; }

    /** True if any CRITICAL or HIGH issues are present. */
    public boolean hasUrgentIssues() {
        return issues.stream().anyMatch(i ->
            i.severity() == JvmArgumentAnalyzer.Severity.CRITICAL ||
            i.severity() == JvmArgumentAnalyzer.Severity.HIGH);
    }

    /** Human-readable score label. */
    public String scoreLabel() {
        if (score >= 90) return "Excellent";
        if (score >= 75) return "Good";
        if (score >= 55) return "Fair";
        if (score >= 35) return "Poor";
        return "Critical";
    }

    /**
     * Builds the full recommended JVM args file contents.
     * Produces args optimised for the detected Java version and system heap.
     */
    public String buildRecommendedArgsFile() {
        long targetHeapMb = Math.max(heapMb, 4096);
        boolean useZgc = javaVersion >= 21;

        StringBuilder sb = new StringBuilder();
        sb.append("# ==========================================================\n");
        sb.append("# Caesium Recommended JVM Arguments\n");
        sb.append("# Generated for: Java ").append(javaVersion)
          .append(" | Heap: ").append(heapMb).append(" MB\n");
        sb.append("# Score before: ").append(score).append("/100 (").append(scoreLabel()).append(")\n");
        sb.append("# ==========================================================\n");
        sb.append("# Paste these into your launcher's JVM Arguments field.\n");
        sb.append("# Remove any existing -Xmx / -Xms / -XX:+Use*GC lines first.\n");
        sb.append("# ==========================================================\n\n");

        if (useZgc) {
            sb.append("# --- Garbage Collector (ZGC — sub-millisecond pauses, Java 21+) ---\n");
            sb.append("-XX:+UseZGC\n");
            sb.append("-XX:+ZGenerational\n");
        } else {
            sb.append("# --- Garbage Collector (G1GC) ---\n");
            sb.append("-XX:+UseG1GC\n");
            sb.append("-XX:MaxGCPauseMillis=37\n");
            sb.append("-XX:+UnlockExperimentalVMOptions\n");
            sb.append("-XX:G1NewSizePercent=20\n");
            sb.append("-XX:G1ReservePercent=20\n");
            sb.append("-XX:G1HeapRegionSize=32m\n");
            sb.append("-XX:G1MixedGCCountTarget=4\n");
            sb.append("-XX:G1MixedGCLiveThresholdPercent=90\n");
            sb.append("-XX:G1RSetUpdatingPauseTimePercent=5\n");
            sb.append("-XX:SurvivorRatio=32\n");
            sb.append("-XX:MaxTenuringThreshold=1\n");
        }

        sb.append("\n# --- Memory ---\n");
        sb.append("-Xms").append(targetHeapMb).append("M\n");
        sb.append("-Xmx").append(targetHeapMb).append("M\n");
        sb.append("-XX:+AlwaysPreTouch\n");

        sb.append("\n# --- GC Behaviour ---\n");
        sb.append("-XX:+DisableExplicitGC\n");
        sb.append("-XX:+UseStringDeduplication\n");
        sb.append("-XX:+PerfDisableSharedMem\n");

        sb.append("\n# --- JIT Compiler ---\n");
        sb.append("-XX:+OptimizeStringConcat\n");
        sb.append("-XX:+UseCompressedOops\n");
        sb.append("-XX:-DontCompileHugeMethods\n");
        sb.append("-XX:ReservedCodeCacheSize=512m\n");
        sb.append("-XX:NonNMethodCodeHeapSize=12m\n");
        sb.append("-XX:ProfiledCodeHeapSize=194m\n");
        sb.append("-XX:NonProfiledCodeHeapSize=244m\n");

        sb.append("\n# --- Network / Misc ---\n");
        sb.append("-Djava.net.preferIPv4Stack=true\n");
        sb.append("-Dfml.ignorePatchDiscrepancies=true\n");
        sb.append("-Dfml.ignoreInvalidMinecraftCertificates=true\n");

        sb.append("\n# ==========================================================\n");
        sb.append("# One-liner (for launchers that want a single line):\n");
        sb.append("# ==========================================================\n");

        // Build one-liner
        String oneLiner;
        if (useZgc) {
            oneLiner = String.format(
                "-XX:+UseZGC -XX:+ZGenerational -Xms%dM -Xmx%dM "
                + "-XX:+AlwaysPreTouch -XX:+DisableExplicitGC "
                + "-XX:+UseStringDeduplication -XX:+PerfDisableSharedMem "
                + "-XX:+OptimizeStringConcat -XX:ReservedCodeCacheSize=512m "
                + "-Djava.net.preferIPv4Stack=true",
                targetHeapMb, targetHeapMb);
        } else {
            oneLiner = String.format(
                "-XX:+UseG1GC -XX:MaxGCPauseMillis=37 -XX:+UnlockExperimentalVMOptions "
                + "-XX:G1HeapRegionSize=32m -XX:G1NewSizePercent=20 -XX:G1ReservePercent=20 "
                + "-Xms%dM -Xmx%dM -XX:+AlwaysPreTouch -XX:+DisableExplicitGC "
                + "-XX:+UseStringDeduplication -XX:+PerfDisableSharedMem "
                + "-XX:+OptimizeStringConcat -XX:ReservedCodeCacheSize=512m "
                + "-Djava.net.preferIPv4Stack=true",
                targetHeapMb, targetHeapMb);
        }
        sb.append(oneLiner).append("\n");

        return sb.toString();
    }
}
