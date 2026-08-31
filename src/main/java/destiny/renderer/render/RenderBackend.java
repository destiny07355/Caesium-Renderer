package destiny.renderer.render;

/**
 * Common interface for all rendering backends.
 *
 * <p>The backend is selected once at startup and remains constant for the session.
 * On 1.21.11 the terrain pipeline is delegated to vanilla (TERRAIN_PIPELINE_PORTED = false),
 * so no backend is instantiated and {@code DestinyRenderer.getActiveBackend()} returns null.
 * This interface is retained for the debug overlay and future pipeline work.
 */
public interface RenderBackend {

    /**
     * Initializes GPU-side resources (VBOs, SSBOs, shaders, persistent buffers).
     * Must be called on the GL thread after context creation.
     */
    void initialize();

    /**
     * Removes a section's geometry from the GPU buffer pool.
     * Called when a section is unloaded or fully culled.
     *
     * @param sectionKey the section identifier
     */
    void removeSection(long sectionKey);

    /**
     * Drops every uploaded section and returns all buffer space to the allocator.
     * Called on world reload (F3+A), dimension change, and world unload.
     * Must be called on the GL thread.
     */
    void reset();

    /**
     * Executes the opaque terrain render pass for the current frame.
     *
     * @param camX camera world X
     * @param camY camera world Y
     * @param camZ camera world Z
     * @param projectionMatrix column-major float[16] projection matrix
     * @param viewMatrix       column-major float[16] view matrix
     */
    void renderOpaque(double camX, double camY, double camZ,
                      float[] projectionMatrix, float[] viewMatrix);

    /**
     * Executes the translucent terrain render pass.
     *
     * @param camX camera X
     * @param camY camera Y
     * @param camZ camera Z
     */
    void renderTranslucent(double camX, double camY, double camZ);

    /**
     * Marks the beginning of a new frame. Backends use this to rotate persistently
     * mapped buffer regions, update culling data, etc.
     */
    void beginFrame();

    /**
     * Marks the end of a frame. Backends flush any pending uploads and
     * release per-frame resources.
     */
    void endFrame();

    /**
     * Releases all GPU resources allocated by this backend.
     * Must be called on the GL thread during shutdown.
     */
    void shutdown();

    /**
     * @return number of active uploaded terrain sections in GPU memory
     */
    int getSectionCount();

    /**
     * @return a human-readable name for this backend (displayed in debug overlay)
     */
    String name();
}
