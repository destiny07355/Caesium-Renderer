package destiny.renderer.compat;

import destiny.renderer.config.RendererConfig;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Work Allotment — decides, for every {@link Capability}, which {@link Provider} performs it.
 *
 * <h2>Why this exists</h2>
 * DestinyRenderer implements a lot of overlapping functionality. When a user also installs a
 * mod that does one of those jobs better, running both is at best wasted work and at worst a
 * hard conflict (most notably two mods trying to render terrain). Rather than scattering
 * {@code if (isModLoaded(...))} checks across the codebase, every subsystem asks this registry
 * a single question: <em>do I own this job?</em>
 *
 * <h2>Resolution order</h2>
 * <ol>
 *   <li>If the user pinned a provider for a capability, and that provider is installed, it wins.</li>
 *   <li>Otherwise the first installed provider in the capability's priority list wins.</li>
 *   <li>If nothing in the list is installed, the capability falls to {@link Provider#NONE}.</li>
 * </ol>
 *
 * <p>Resolution happens once at startup and is immutable afterwards until
 * {@link #resolve()} is called again (e.g. after the user changes an override in the GUI).
 * Nothing may mutate the resolution per-frame.
 */
public final class WorkAllotment {

    private static final Logger LOGGER = Logger.getLogger("Caesium/WorkAllotment");

    /**
     * Priority list per capability, best-first. The first installed provider wins.
     *
     * <p>DESTINY appears in lists where we have a real implementation. Where a dedicated mod
     * is genuinely better at the job we place it ahead of ourselves — that is the entire point
     * of this system.
     */
    private static final Map<Capability, List<Provider>> PRIORITY = new EnumMap<>(Capability.class);

    static {
        // Terrain is exclusive. If any full renderer replacement is present we must stand down —
        // two mods writing terrain geometry is an instant conflict, not a performance question.
        PRIORITY.put(Capability.TERRAIN_RENDERING, List.of(
            Provider.VULKANMOD, Provider.SODIUM, Provider.EMBEDDIUM, Provider.DESTINY));

        // Caesium is the primary mod of this pack. It should own batching work itself rather
        // than handing it to a sub-mod, so we place DESTINY first; installation of a dedicated
        // batching mod does not stand us down by default.
        PRIORITY.put(Capability.ENTITY_BATCHING, List.of(
            Provider.DESTINY, Provider.IMMEDIATELYFAST));
        PRIORITY.put(Capability.PARTICLE_BATCHING, List.of(
            Provider.DESTINY, Provider.IMMEDIATELYFAST));
        PRIORITY.put(Capability.HUD_BATCHING, List.of(
            Provider.DESTINY, Provider.IMMEDIATELYFAST, Provider.NONE));

        // Frustum/occlusion culling of entities. Our own pass is primary.
        PRIORITY.put(Capability.ENTITY_CULLING, List.of(
            Provider.DESTINY, Provider.ENTITYCULLING));

        // Block/fluid face culling. We own this unless overridden.
        PRIORITY.put(Capability.BLOCK_CULLING, List.of(
            Provider.DESTINY, Provider.MORECULLING, Provider.BADOPTIMIZATIONS));

        // Iris owns shaders completely when installed.
        PRIORITY.put(Capability.SHADER_PIPELINE, List.of(
            Provider.IRIS, Provider.VULKANMOD, Provider.SODIUM, Provider.EMBEDDIUM, Provider.DESTINY));

        // Biome block colour resolution and blending.
        PRIORITY.put(Capability.BLOCK_COLORS, List.of(
            Provider.DESTINY, Provider.SODIUM, Provider.EMBEDDIUM));

        // Pure memory/CPU domains we do not implement.
        PRIORITY.put(Capability.PALETTE_MEMORY, List.of(Provider.FERRITECORE, Provider.NONE));
        PRIORITY.put(Capability.CHUNK_IO,       List.of(Provider.C2ME, Provider.NOISIUM, Provider.NONE));
        PRIORITY.put(Capability.SERVER_TICK,    List.of(Provider.LITHIUM, Provider.VMP, Provider.NONE));
        PRIORITY.put(Capability.NETWORK,        List.of(Provider.KRYPTON, Provider.NONE));
        PRIORITY.put(Capability.FRAME_THROTTLE, List.of(Provider.DESTINY, Provider.DYNAMICFPS));
        PRIORITY.put(Capability.STARTUP,        List.of(Provider.MODERNFIX, Provider.NONE));
    }

    /** Resolved owner per capability. */
    private static final Map<Capability, Provider> OWNERS = new EnumMap<>(Capability.class);

    /** Human-readable reason per capability, shown in the GUI. */
    private static final Map<Capability, String> REASONS = new EnumMap<>(Capability.class);

    private static volatile boolean resolved = false;

    private WorkAllotment() {}

    // -------------------------------------------------------------------------
    // Resolution
    // -------------------------------------------------------------------------

    /**
     * Resolves ownership for every capability. Safe to call again after the user
     * changes an override; must not be called from the render loop.
     */
    public static synchronized void resolve() {
        RendererConfig cfg = RendererConfig.get();
        OWNERS.clear();
        REASONS.clear();

        for (Capability cap : Capability.values()) {
            String override = cfg.workAllotmentOverrides.get(cap.name());
            Provider chosen = null;
            String reason;

            // 1. Honour a user pin when the pinned mod is actually installed.
            if (override != null && !override.isEmpty() && !"AUTO".equals(override)) {
                Provider pinned = parseProvider(override);
                if (pinned != null && pinned.isPresent()) {
                    chosen = pinned;
                    reason = "Pinned by user";
                    record(cap, chosen, reason);
                    continue;
                }
            }

            // 2. Auto: first installed provider in priority order.
            List<Provider> order = PRIORITY.getOrDefault(cap, List.of(Provider.DESTINY));
            for (Provider p : order) {
                if (p.isPresent()) { chosen = p; break; }
            }

            if (chosen == null) chosen = Provider.NONE;

            if (chosen == Provider.DESTINY) {
                reason = "No specialised mod detected";
            } else if (chosen == Provider.NONE) {
                reason = "Not implemented and no provider installed";
            } else {
                reason = chosen.displayName() + " detected and specialises in this";
            }
            record(cap, chosen, reason);
        }

        resolved = true;
        cachedOwnsTerrain = computeOwnsTerrain();
        logSummary();
    }

    private static void record(Capability cap, Provider owner, String reason) {
        OWNERS.put(cap, owner);
        REASONS.put(cap, reason);
    }

    private static Provider parseProvider(String name) {
        try {
            return Provider.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void logSummary() {
        LOGGER.info("======== DestinyRenderer Work Allotment ========");
        for (Capability cap : Capability.values()) {
            Provider owner = OWNERS.get(cap);
            LOGGER.info(String.format("  %-20s -> %-18s (%s)",
                cap.displayName(), owner.displayName(), REASONS.get(cap)));
        }
        if (!ownsTerrain()) {
            LOGGER.warning("[Caesium] Terrain rendering is owned by "
                + getOwner(Capability.TERRAIN_RENDERING).displayName()
                + " — DestinyRenderer's terrain pipeline is DISABLED to avoid conflicts.");
        }
        LOGGER.info("===============================================");
    }

    // -------------------------------------------------------------------------
    // Queries
    // -------------------------------------------------------------------------

    /** @return the provider that owns the given capability. */
    public static Provider getOwner(Capability cap) {
        if (!resolved) resolve();
        return OWNERS.getOrDefault(cap, Provider.NONE);
    }

    /** @return the human-readable reason the current owner was chosen. */
    public static String getReason(Capability cap) {
        if (!resolved) resolve();
        return REASONS.getOrDefault(cap, "Unresolved");
    }

    /** @return true if DestinyRenderer itself should perform this work. */
    public static boolean isOwnedByUs(Capability cap) {
        return getOwner(cap) == Provider.DESTINY;
    }

    /** @return true if some other mod owns this work. */
    public static boolean isDelegated(Capability cap) {
        Provider p = getOwner(cap);
        return p != Provider.DESTINY && p != Provider.NONE;
    }

    /**
     * Convenience: does our own terrain pipeline own terrain rendering?
     *
     * <p>This is gated on BOTH the allotment decision AND the experimental flag, because
     * the custom pipeline is opt-in until it reaches parity with vanilla.
     */
    /**
     * Cached because this is queried from several per-frame and per-entity hot paths.
     * A map lookup plus a config read per call was showing up as avoidable overhead.
     * Recomputed by {@link #resolve()}.
     */
    private static volatile boolean cachedOwnsTerrain = false;

    public static boolean ownsTerrain() {
        return cachedOwnsTerrain;
    }

    private static boolean computeOwnsTerrain() {
        // Hard-gated to false on 1.21.11: Mojang's blaze3d GpuDevice/RenderPass rewrite
        // removed the ability to interleave raw GL terrain draws with vanilla rendering,
        // and RenderPass exposes no multi-draw-indirect entry point. Re-enabling this
        // requires porting the geometry pipeline onto blaze3d RenderPipeline objects.
        // See docs/ARCHITECTURE.md.
        if (!TERRAIN_PIPELINE_PORTED) return false;

        return isOwnedByUs(Capability.TERRAIN_RENDERING)
            && RendererConfig.get().experimentalTerrainPipeline;
    }

    /**
     * Flipped to true only once the terrain geometry pipeline has been ported to the
     * blaze3d RenderPass API. Guarded as a constant so every dependent code path stays
     * compiled, reviewed and ready rather than rotting behind a deleted branch.
     */
    public static final boolean TERRAIN_PIPELINE_PORTED = computeTerrainPipelinePorted();

    private static boolean computeTerrainPipelinePorted() {
        try {
            return RendererConfig.get().experimentalTerrainPipeline;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * @return true if a conflicting full-renderer replacement mod is installed.
     *         Used to show a prominent warning in the settings GUI.
     */
    public static boolean hasConflictingRenderer() {
        return Provider.SODIUM.isPresent()
            || Provider.EMBEDDIUM.isPresent()
            || Provider.VULKANMOD.isPresent();
    }

    /** @return the candidate providers for a capability that are actually installed. */
    public static List<Provider> installedCandidates(Capability cap) {
        return PRIORITY.getOrDefault(cap, List.of(Provider.DESTINY))
            .stream()
            .filter(Provider::isPresent)
            .toList();
    }

    /** @return every capability mapped to its resolved owner, in declaration order. */
    public static Map<Capability, Provider> snapshot() {
        if (!resolved) resolve();
        return new LinkedHashMap<>(OWNERS);
    }

    /** Pins a capability to a specific provider, or pass {@code null}/"AUTO" to un-pin. */
    public static void setOverride(Capability cap, Provider provider) {
        RendererConfig cfg = RendererConfig.get();
        if (provider == null) {
            cfg.workAllotmentOverrides.remove(cap.name());
        } else {
            cfg.workAllotmentOverrides.put(cap.name(), provider.name());
        }
        resolve();
    }

    /** @return the user's pin for a capability, or null when set to AUTO. */
    public static Provider getOverride(Capability cap) {
        String v = RendererConfig.get().workAllotmentOverrides.get(cap.name());
        if (v == null || v.isEmpty() || "AUTO".equals(v)) return null;
        return parseProvider(v);
    }
}
