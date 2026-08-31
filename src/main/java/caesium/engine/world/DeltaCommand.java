package caesium.engine.world;

/**
 * Commands pushed from the game thread (via the integration layer) describing what
 * changed since the last snapshot. The extraction thread drains them into the working
 * copy of the world; the scheduler reads the {@code Explosion} command for its policy.
 */
public sealed interface DeltaCommand {

    record SectionDirty(long chunkX, long chunkZ, int y, int layerMask) implements DeltaCommand {
    }

    record SectionMeshUpdated(RenderWorld.SectionMesh mesh) implements DeltaCommand {
    }

    record EntityUpdated(long id, float x, float y, float z) implements DeltaCommand {
    }

    record EntityRemoved(long id) implements DeltaCommand {
    }

    record ParticleChanged(int typeId, int count) implements DeltaCommand {
    }

    record CameraMoved(RenderWorld.Camera camera) implements DeltaCommand {
    }

    record OptionChanged(RenderWorld.Options options) implements DeltaCommand {
    }

    record Explosion(float x, float y, float z, float radius, long timeMs) implements DeltaCommand {
    }
}