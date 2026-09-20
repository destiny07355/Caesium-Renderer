package destiny.renderer.system.java25;

import destiny.renderer.system.common.AbstractRendererSystem;
import java.nio.ByteBuffer;

/**
 * Java 25 Platform Subsystem.
 * Located in folder: {@code system/java25}
 *
 * <p>Leverages Java 25 Foreign Function & Memory (FFM) scoped arenas for zero-cost native memory management.
 */
public final class Java25System extends AbstractRendererSystem {

    private final Java25MemoryArena arena = new Java25MemoryArena();

    public Java25System() {
        super("Caesium/Java25");
    }

    @Override
    public int javaVersion() {
        return 25;
    }

    @Override
    public String systemName() {
        return "Java 25 FFM Scoped Arena System";
    }

    @Override
    public String systemFolder() {
        return "system/java25";
    }

    @Override
    public void initialize() {
        if (initialized) return;
        initialized = true;
        arena.initialize();
        logger.info("[Caesium] Java 25 System initialized (folder: " + systemFolder() + ").");
    }

    @Override
    public void shutdown() {
        if (!initialized) return;
        arena.shutdown();
        initialized = false;
        logger.info("[Caesium] Java 25 System shut down.");
    }

    @Override
    public void beginFrame() {
        frameCounter++;
        arena.beginFrame();
    }

    @Override
    public void endFrame() {
        arena.endFrame();
    }

    @Override
    public ByteBuffer allocateFrameBuffer(long bytes) {
        return arena.allocateFrame(bytes);
    }

    @Override
    public long allocatePersistent(String name, long bytes) {
        return arena.allocatePersistent(name, bytes);
    }

    @Override
    public void freePersistent(String name) {
        arena.freePersistent(name);
    }

    @Override
    public long getTotalAllocatedBytes() {
        return arena.getTotalAllocatedBytes();
    }
}
