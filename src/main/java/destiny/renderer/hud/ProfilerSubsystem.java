package destiny.renderer.hud;

/**
 * Subsystem identifiers for fine-grained performance tracking in Caesium.
 * Extracted into a top-level type to ensure clean runtime decoupling.
 */
public enum ProfilerSubsystem {
    WORLD_UPDATE,
    VISIBILITY,
    ENTITIES,
    PARTICLES,
    ANIMATIONS,
    CHUNK_SCHEDULING,
    RENDER_GRAPH,
    BACKEND,
    OTHER
}