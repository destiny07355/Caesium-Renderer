package destiny.renderer.system.java21;

import destiny.renderer.system.common.AbstractRendererSystem;
import java.nio.ByteBuffer;

/**
 * Java 21 LTS Platform Subsystem.
 * Located in folder: {@code system/java21}
 *
 * <p>Provides zero-allocation off-heap memory management without requiring Java 25 preview features.
 */
public final class Java21System extends AbstractRendererSystem {

    private final Java21MemoryArena arena = new Java21MemoryArena();

    public Java21System() {
        super("Caesium/Java21");
    }

    @Override
    public int javaVersion() {
        return 21;
    }

    @Override
    public String systemName() {
        return "Java 21 LTS Direct Memory System";
    }

    @Override
    public String systemFolder() {
        return "system/java21";
    }

    @Override
    public void initialize() {
        if (initialized) return;
        initialized = true;
        arena.initialize();
        logger.info("[Caesium] Java 21 System initialized (folder: " + systemFolder() + ").");
    }

    @Override
    public void shutdown() {
        if (!initialized) return;
        arena.shutdown();
        initialized = false;
        logger.info("[Caesium] Java 21 System shut down.");
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
