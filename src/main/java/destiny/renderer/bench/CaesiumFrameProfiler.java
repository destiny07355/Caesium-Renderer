package destiny.renderer.bench;

import destiny.renderer.hud.ProfilerSubsystem;

/**
 * Backward-compatibility shim for any mixin or benchmark hook compiled against
 * the legacy {@code destiny.renderer.bench.CaesiumFrameProfiler} location.
 *
 * <p>Forwards all calls directly to {@link destiny.renderer.hud.CaesiumFrameProfiler}.
 */
public final class CaesiumFrameProfiler {

    /**
     * Legacy nested enum retained to prevent NoClassDefFoundError on any bytecode
     * referencing CaesiumFrameProfiler$Subsystem.
     */
    public enum Subsystem {
        WORLD_UPDATE(ProfilerSubsystem.WORLD_UPDATE),
        VISIBILITY(ProfilerSubsystem.VISIBILITY),
        ENTITIES(ProfilerSubsystem.ENTITIES),
        PARTICLES(ProfilerSubsystem.PARTICLES),
        ANIMATIONS(ProfilerSubsystem.ANIMATIONS),
        CHUNK_SCHEDULING(ProfilerSubsystem.CHUNK_SCHEDULING),
        RENDER_GRAPH(ProfilerSubsystem.RENDER_GRAPH),
        BACKEND(ProfilerSubsystem.BACKEND),
        OTHER(ProfilerSubsystem.OTHER);

        public final ProfilerSubsystem mapped;

        Subsystem(ProfilerSubsystem mapped) {
            this.mapped = mapped;
        }
    }

    private CaesiumFrameProfiler() {}

    public static void beginWorldUpdate() {
        destiny.renderer.hud.CaesiumFrameProfiler.beginWorldUpdate();
    }

    public static void endWorldUpdate() {
        destiny.renderer.hud.CaesiumFrameProfiler.endWorldUpdate();
    }

    public static void beginVisibility() {
        destiny.renderer.hud.CaesiumFrameProfiler.beginVisibility();
    }

    public static void endVisibility() {
        destiny.renderer.hud.CaesiumFrameProfiler.endVisibility();
    }

    public static void beginEntities() {
        destiny.renderer.hud.CaesiumFrameProfiler.beginEntities();
    }

    public static void endEntities() {
        destiny.renderer.hud.CaesiumFrameProfiler.endEntities();
    }

    public static void beginParticles() {
        destiny.renderer.hud.CaesiumFrameProfiler.beginParticles();
    }

    public static void endParticles() {
        destiny.renderer.hud.CaesiumFrameProfiler.endParticles();
    }

    public static void beginAnimations() {
        destiny.renderer.hud.CaesiumFrameProfiler.beginAnimations();
    }

    public static void endAnimations() {
        destiny.renderer.hud.CaesiumFrameProfiler.endAnimations();
    }

    public static void beginChunkScheduling() {
        destiny.renderer.hud.CaesiumFrameProfiler.beginChunkScheduling();
    }

    public static void endChunkScheduling() {
        destiny.renderer.hud.CaesiumFrameProfiler.endChunkScheduling();
    }

    public static void beginRenderGraph() {
        destiny.renderer.hud.CaesiumFrameProfiler.beginRenderGraph();
    }

    public static void endRenderGraph() {
        destiny.renderer.hud.CaesiumFrameProfiler.endRenderGraph();
    }

    public static void beginBackend() {
        destiny.renderer.hud.CaesiumFrameProfiler.beginBackend();
    }

    public static void endBackend() {
        destiny.renderer.hud.CaesiumFrameProfiler.endBackend();
    }

    public static void start(Subsystem s) {
        if (s != null) destiny.renderer.hud.CaesiumFrameProfiler.start(s.mapped);
    }

    public static void end(Subsystem s) {
        if (s != null) destiny.renderer.hud.CaesiumFrameProfiler.end(s.mapped);
    }
}
