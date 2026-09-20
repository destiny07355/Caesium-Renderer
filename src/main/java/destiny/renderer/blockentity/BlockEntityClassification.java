package destiny.renderer.blockentity;

/**
 * Classification of a block entity's current visual state for the OBE-style optimization pipeline.
 */
public enum BlockEntityClassification {
    /**
     * Statically baked into Caesium's chunk/section terrain mesh.
     * The normal per-frame {@code BlockEntityRenderer} call is completely suppressed.
     */
    STATIC,

    /**
     * Actively animating or in motion (e.g. chest opening/closing, bell ringing, player editing sign text).
     * Rendered via the normal Minecraft/mod {@code BlockEntityRenderer}.
     */
    DYNAMIC,

    /**
     * Unrecognized or modded block entity without a static handler.
     * Guaranteed safe pass-through to vanilla or modded renderers.
     */
    UNSUPPORTED
}
