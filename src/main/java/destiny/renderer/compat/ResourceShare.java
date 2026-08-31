package destiny.renderer.compat;

import net.fabricmc.loader.api.FabricLoader;

import java.util.logging.Logger;

/**
 * How much of the machine's free CPU/heap/scheduler budget Caesium should reserve for itself,
 * given what other render/performance mods are doing on the same game loop.
 *
 * <h2>Why this exists</h2>
 * {@link WorkAllotment} decides <em>who</em> owns each unit of work. It does not, by itself,
 * decide <em>how big</em> a thread pool or budget Caesium should consume for the work it does
 * own. Without that feedback loop a user installing both Caesium <em>and</em> a dedicated
 * renderer (Sodium / VulkanMod / Embeddium) ends up with two meshing pools both sized as if
 * they were alone — every core the OS hands the JVM is parked by background workers from
 * <em>both</em> mods fighting for the same single render thread.
 *
 * <p>This class cross-references the resolved {@link WorkAllotment} state with which
 * rendering-relevant mods are installed, and returns the share of the machine that Caesium
 * should take: <em>most of it when we own the work alone, deliberately less when another mod
 * shares the frame</em>. Other mods still get to run (they are never starved), but Caesium
 * stops oversubscribing against them.
 *
 * <h2>Inputs</h2>
 * <ul>
 *   <li>The set of installed render/perf mods that schedule their own worker threads.</li>
 *   <li>Whether we own {@link Capability#TERRAIN_RENDERING}. When we don't, vanilla (or
 *       Sodium) is doing the heavy geometry lifting and our own meshing pool becomes a
 *       maintenance task rather than the primary one — we keep it small.</li>
 *   <li>Whether we own {@link Capability#BLOCK_CULLING} / {@link Capability#ENTITY_CULLING}
 *       etc. — owning more work widens our share back up.</li>
 * </ul>
 *
 * <p>All values are in [0,1] except for the meshing-thread multiplier (a real factor, may
 * go above 1.0 when Caesium is alone but never below 0.25 — we always keep at least one
 * worker so the queue cleanly drains).
 *
 * <p>This class is read by {@link destiny.renderer.config.RendererConfig#resolvedMeshingThreads()},
 * by {@link destiny.renderer.chunk.MeshingJobSystem} at initialisation, and by the
 * frame scheduler when admitting background work. It is cheap and idempotent and may be
 * queried from any thread.
 */
public final class ResourceShare {

    private static final Logger LOGGER = Logger.getLogger("Caesium/ResourceShare");

    private ResourceShare() {}

    /**
     * Render-relevant mods whose own worker pools contend with ours for the same cores
     * when they are installed. Listed here rather than scattered across {@code isPresent()}
     * checks because whether <em>any</em> such competitor is installed is a first-class
     * input to every size decision here.
     */
    private static final Provider[] RENDER_THREAD_COMPETITORS = {
        Provider.SODIUM,
        Provider.EMBEDDIUM,
        Provider.VULKANMOD,
        Provider.IRIS,
        Provider.IMMEDIATELYFAST,
        Provider.ENTITYCULLING,
        Provider.MORECULLING,
        Provider.BADOPTIMIZATIONS
    };

    /** How many of the render/perf mods that schedule their own worker threads are loaded. */
    public static int competitorCount() {
        int n = 0;
        for (Provider p : RENDER_THREAD_COMPETITORS) {
            if (p.isPresent()) n++;
        }
        return n;
    }

    /** True iff at least one full renderer replacement is installed (Sodium, Embeddium, VulkanMod). */
    public static boolean fullRendererReplacementInstalled() {
        return Provider.SODIUM.isPresent()
            || Provider.EMBEDDIUM.isPresent()
            || Provider.VULKANMOD.isPresent();
    }

    /**
     * Multiplier applied to Caesium's meshing-thread count.
     *
     * <p>1.0 when alone — Caesium gets the full {@code resolvedMeshingThreads()} value.
     * Reduced toward 0.25 as render competitors stack up, so each competitor gets a
     * guaranteed free core to schedule its own work against. Never below 0.25 (we keep at
     * least one worker so the deferred-rebuild queue drains instead of leaking).
     *
     * <p>Sharp cut-down when a full renderer replacement owns terrain: our own mesher is
     * reduced to a minimum because vanilla + the other mod already owns the geometry path.
     */
    public static float meshingThreadFactor() {
        WorkAllotment.resolve();
        boolean ownsTerrain  = WorkAllotment.isOwnedByUs(Capability.TERRAIN_RENDERING);
        boolean ownsBatching = WorkAllotment.isOwnedByUs(Capability.ENTITY_BATCHING)
                            || WorkAllotment.isOwnedByUs(Capability.PARTICLE_BATCHING);
        boolean ownsCulling  = WorkAllotment.isOwnedByUs(Capability.ENTITY_CULLING)
                            || WorkAllotment.isOwnedByUs(Capability.BLOCK_CULLING);

        // If we don't own terrain at all (the common case on 1.21.11), our heavy meshing
        // pool's job shrinks to maintenance/caching/rebuild-batching — keep it honest.
        if (!ownsTerrain && !WorkAllotment.TERRAIN_PIPELINE_PORTED) {
            // A full renderer replacement is doing the heavy lifting; we only need a
            // minimal pool for the deferred-rebuild queue and the tear-down seam.
            float floor = fullRendererReplacementInstalled() ? 0.25f : 0.5f;
            // Lighter rendering-side work we *do* own widens us back slightly, but never
            // back toward the alone-value: the other renderer is still scheduling against
            // the same cores.
            if (ownsBatching) floor += 0.15f;
            if (ownsCulling)  floor += 0.10f;
            return Math.min(floor, 1.0f);
        }

        // We own terrain. Scale down by the number of separate render/perf threads
        // competitors are scheduling. Each competitor is assumed to take ~1 thread of
        // its own, so we shave a corresponding amount off ours rather than oversubscribing.
        int competitors = competitorCount() - (fullRendererReplacementInstalled() ? 1 : 0);
        float factor = 1.0f - competitors * 0.10f;
        return Math.max(factor, 0.5f);
    }

    /**
     * Fraction of the frame budget (meshing + upload) Caesium should claim when allocating
     * its {@link caesium.engine.scheduler.BudgetPolicy}. Smaller when we own less work,
     * so the budget we reserve for ourselves does not eat time the OS/vanilla/another
     * renderer needs while we have nothing to do.
     */
    public static float budgetRatio() {
        WorkAllotment.resolve();
        if (!WorkAllotment.isOwnedByUs(Capability.TERRAIN_RENDERING)
                && !WorkAllotment.TERRAIN_PIPELINE_PORTED) {
            // No terrain pipeline of our own; the meshing/upload budget should be small.
            return fullRendererReplacementInstalled() ? 0.20f : 0.40f;
        }
        return 1.0f;
    }

    /**
     * Fraction of the normal per-frame background-job cap Caesium should admit when
     * competitors are scheduling their own background work, so the cumulative admission
     * (Caesium + competitors) does not explode the CPU back to the freezing regime this
     * whole system was built to prevent.
     */
    public static float backgroundAdmissionShare() {
        int c = competitorCount();
        if (c == 0) return 1.0f;
        // Each render-flavoured competitor takes some of the CPU; we admit proportionally
        // less of our own background burst so the render thread is still first-class on
        // the cores we share. Bounded to [0.25, 1.0].
        return Math.max(1.0f - c * 0.12f, 0.25f);
    }

    /**
     * One-line human summary for the loading log — easy to spot in the game log if a
     * user reports "Caesium eats too much CPU when Sodium is installed".
     */
    public static void logSummary() {
        int c = competitorCount();
        WorkAllotment.resolve();
        boolean ownsTerrain = WorkAllotment.isOwnedByUs(Capability.TERRAIN_RENDERING);
        String terrainNote = ownsTerrain
            ? "Caesium owns terrain"
            : (fullRendererReplacementInstalled()
                ? "terrain delegated to a third-party renderer"
                : "terrain is vanilla; Caesium does optimisation work around it");
        LOGGER.info(String.format(
            "[Caesium] ResourceShare: %d render/perf competitor(s) installed, %s. "
            + "Meshing factor %.2f, budget %.0f%%, background admit %.0f%%.",
            c, terrainNote,
            meshingThreadFactor(),
            budgetRatio() * 100f,
            backgroundAdmissionShare() * 100f));
    }
}
