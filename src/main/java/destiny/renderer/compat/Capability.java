package destiny.renderer.compat;

/**
 * A discrete unit of rendering/optimization work that can be owned by exactly one provider.
 *
 * <p>Each capability represents a job that DestinyRenderer is able to perform itself, but
 * which a specialised third-party mod may perform better. The {@link WorkAllotment} registry
 * resolves an owner for every capability at startup and every subsystem gates its work on
 * that decision rather than on scattered boolean flags.
 */
public enum Capability {

    /** Chunk/terrain geometry building and submission. Exclusive — two owners is a hard conflict. */
    TERRAIN_RENDERING("Terrain Rendering",
        "Builds and draws chunk geometry. Only one mod may own this."),

    /** Batching of entity model draws to reduce state changes. */
    ENTITY_BATCHING("Entity Batching",
        "Groups mob draw calls to cut GPU state changes."),

    /** Batching of particle quads into shared buffers. */
    PARTICLE_BATCHING("Particle Batching",
        "Batches particle geometry into unified vertex buffers."),

    /** Batching of HUD/GUI draw calls. */
    HUD_BATCHING("HUD Batching",
        "Batches HUD and GUI draw calls into fewer submissions."),

    /** Frustum/occlusion culling of entities and block entities. */
    ENTITY_CULLING("Entity Culling",
        "Skips entities hidden behind terrain."),

    /** Culling of hidden block and fluid faces inside chunks. */
    BLOCK_CULLING("Block Face Culling",
        "Skips block and fluid faces hidden by neighbouring geometry."),

    /** Ownership of the shader pipeline. */
    SHADER_PIPELINE("Shader Pipeline",
        "Owns terrain/entity shader programs."),

    /** Biome block colour resolution and blending. */
    BLOCK_COLORS("Block Colors",
        "Resolves and blends biome tint colours."),

    /** Block state palette memory deduplication. */
    PALETTE_MEMORY("Palette Memory",
        "Deduplicates block state storage to cut RAM use."),

    /** Asynchronous chunk generation and disk I/O. */
    CHUNK_IO("Chunk I/O",
        "Async chunk loading, generation and saving."),

    /** Server-side tick, AI and pathfinding optimization. */
    SERVER_TICK("Server Tick",
        "Optimizes tick loop, mob AI and pathfinding."),

    /** Network packet handling and compression. */
    NETWORK("Networking",
        "Optimizes packet compression and I/O threading."),

    /** Frame throttling when the window is unfocused or idle. */
    FRAME_THROTTLE("Frame Throttle",
        "Reduces FPS when the window is unfocused to save power."),

    /** Startup time, class loading and resource pack caching. */
    STARTUP("Startup & Loading",
        "Improves launch time and resource loading.");

    private final String displayName;
    private final String description;

    Capability(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String displayName() { return displayName; }
    public String description() { return description; }
}
