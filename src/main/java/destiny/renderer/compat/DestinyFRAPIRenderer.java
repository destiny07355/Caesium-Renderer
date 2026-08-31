package destiny.renderer.compat;

/**
 * Stub — DestinyRenderer FRAPI Renderer implementation.
 *
 * <p>Full implementation deferred to v1.1. Direct interface implementation
 * of {@code net.fabricmc.fabric.api.renderer.v1.Renderer} requires the FRAPI
 * module to be on the compile classpath as a versioned dependency, which varies
 * across Fabric API patch versions. This stub keeps the class present for
 * forward-compatibility while v1.0 uses the vanilla rendering path.
 */
public final class DestinyFRAPIRenderer {

    public static final DestinyFRAPIRenderer INSTANCE = new DestinyFRAPIRenderer();

    private DestinyFRAPIRenderer() {}

    /** @return a human-readable identifier for this renderer. */
    public String getName() {
        return "DestinyFRAPIRenderer (v1.0-stub)";
    }
}
