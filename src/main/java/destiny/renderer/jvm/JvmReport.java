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
        long targetHeapMb = heapMb > 0 ? Math.min(6144, Math.max(4096, heapMb)) : 4096;
        boolean useZgc = gc == JvmArgumentAnalyzer.GcType.ZGC || (javaVersion >= 21 && gc != JvmArgumentAnalyzer.GcType.G1);

        StringBuilder sb = new StringBuilder();
        sb.append("# ==========================================================\n");
        sb.append("# Caesium Recommended JVM Arguments\n");
        sb.append("# Generated for: Java ").append(javaVersion)
          .append(" | Heap: ").append(targetHeapMb).append(" MB\n");
        sb.append("# Score before: ").append(score).append("/100 (").append(scoreLabel()).append(")\n");
        sb.append("# ==========================================================\n");
        sb.append("# Paste these into your launcher's JVM Arguments field.\n");
        sb.append("# Remove any existing -Xmx / -Xms / -XX:+Use*GC lines first.\n");
        sb.append("# ==========================================================\n\n");

        if (useZgc) {
            sb.append("# --- Garbage Collector (ZGC — Sub-Millisecond Pause Mode) ---\n");
            sb.append("-XX:+UseZGC\n");
            if (javaVersion >= 21) {
                sb.append("-XX:+ZGenerational\n");
            }
            sb.append("-XX:ConcGCThreads=2\n");
        } else {
            sb.append("# --- Garbage Collector (G1GC — Low-Latency & High Throughput) ---\n");
            sb.append("-XX:+UseG1GC\n");
            sb.append("-XX:MaxGCPauseMillis=20\n");
            sb.append("-XX:+UnlockExperimentalVMOptions\n");
            sb.append("-XX:G1NewSizePercent=20\n");
            sb.append("-XX:G1ReservePercent=15\n");
            sb.append("-XX:G1HeapRegionSize=32m\n");
            sb.append("-XX:InitiatingHeapOccupancyPercent=45\n");
            sb.append("-XX:+ParallelRefProcEnabled\n");
            sb.append("-XX:SurvivorRatio=32\n");
            sb.append("-XX:MaxTenuringThreshold=1\n");
        }

        sb.append("\n# --- Memory & Allocation ---\n");
        sb.append("-Xms").append(targetHeapMb).append("M\n");
        sb.append("-Xmx").append(targetHeapMb).append("M\n");
        sb.append("-XX:+AlwaysPreTouch\n");

        sb.append("\n# --- Stability & Optimization ---\n");
        sb.append("-XX:+DisableExplicitGC\n");
        sb.append("-XX:+UseStringDeduplication\n");
        sb.append("-XX:+OptimizeStringConcat\n");

        sb.append("\n# ==========================================================\n");
        sb.append("# One-liner (for launchers that want a single line):\n");
        sb.append("# ==========================================================\n");

        String oneLiner;
        if (useZgc) {
            oneLiner = String.format(
                "-XX:+UseZGC%s -XX:ConcGCThreads=2 -Xms%dM -Xmx%dM "
                + "-XX:+AlwaysPreTouch -XX:+DisableExplicitGC "
                + "-XX:+UseStringDeduplication -XX:+OptimizeStringConcat",
                javaVersion >= 21 ? " -XX:+ZGenerational" : "",
                targetHeapMb, targetHeapMb);
        } else {
            oneLiner = String.format(
                "-XX:+UseG1GC -XX:MaxGCPauseMillis=20 -XX:+UnlockExperimentalVMOptions "
                + "-XX:G1HeapRegionSize=32m -XX:G1NewSizePercent=20 -XX:G1ReservePercent=15 "
                + "-XX:InitiatingHeapOccupancyPercent=45 -XX:+ParallelRefProcEnabled "
                + "-Xms%dM -Xmx%dM -XX:+AlwaysPreTouch -XX:+DisableExplicitGC "
                + "-XX:+UseStringDeduplication -XX:+OptimizeStringConcat",
                targetHeapMb, targetHeapMb);
        }
        sb.append(oneLiner).append("\n");

        return sb.toString();
    }
}
