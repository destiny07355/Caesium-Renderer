package destiny.renderer.compat;

/**
 * Stub — DestinyRenderer FRAPI MaterialFinder implementation.
 * Deferred to v1.1. See {@link FRAPICompatLayer} for rationale.
 */
public final class DestinyMaterialFinder {

    /** Opaque singleton representing "no material override". */
    public static final DestinyMaterialFinder DEFAULT_MATERIAL = new DestinyMaterialFinder();

    private DestinyMaterialFinder() {}

    /** @return blend mode name (always SOLID in v1.0 stub). */
    public String getBlendMode() { return "SOLID"; }

    /** @return false — no emissive override in v1.0 stub. */
    public boolean isEmissive() { return false; }
}
