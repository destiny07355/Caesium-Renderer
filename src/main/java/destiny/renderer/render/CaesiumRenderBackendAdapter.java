package destiny.renderer.render;

import caesium.engine.CaesiumEngine;
import caesium.engine.backend.GpuBackend;
import caesium.integration.CaesiumIntegration;

/**
 * Bridges the Caesium Engine & GpuBackend with DestinyRenderer's RenderBackend interface.
 */
public final class CaesiumRenderBackendAdapter implements RenderBackend {

    private final CaesiumEngine engine;
    private final GpuBackend backend;

    public CaesiumRenderBackendAdapter(CaesiumEngine engine, GpuBackend backend) {
        this.engine = engine;
        this.backend = backend;
    }

    @Override
    public void initialize() {
        // Initialized during engine startup
    }

    @Override
    public void removeSection(long sectionKey) {
        // Handled via scene manager delta commands
    }

    @Override
    public void reset() {
        // Handled on world reloads
    }

    @Override
    public void renderOpaque(double camX, double camY, double camZ,
                             float[] projectionMatrix, float[] viewMatrix) {
        CaesiumIntegration.render();
    }

    @Override
    public void renderTranslucent(double camX, double camY, double camZ) {
        // Translucent terrain rendering pass
    }

    @Override
    public void beginFrame() {
        // Frame synchronization
    }

    @Override
    public void endFrame() {
        // Frame completion and metric collection
    }

    @Override
    public void shutdown() {
        CaesiumIntegration.stop();
    }

    @Override
    public int getSectionCount() {
        return engine != null && engine.scene() != null && engine.scene().published() != null
            ? engine.scene().published().sections().size() : 0;
    }

    @Override
    public String name() {
        return backend != null ? backend.name() : "OpenGL/Vulkan";
    }
}
