package destiny.renderer.system.common;

import java.nio.ByteBuffer;

/**
 * Common abstraction for Java-version-specific platform and memory subsystems.
 * Shared across both Java 21 and Java 25 implementations.
 */
public interface IRendererSystem {

    /** The target Java feature version (e.g., 21 or 25). */
    int javaVersion();

    /** Human-readable name of the platform system. */
    String systemName();

    /** Folder path identifier within the codebase. */
    String systemFolder();

    /** True if the platform system is initialized. */
    boolean isInitialized();

    /** Initializes the system and its off-heap memory allocators. */
    void initialize();

    /** Shuts down the system and releases all allocated native memory. */
    void shutdown();

    /** Marks the start of a frame, resetting frame-local bump allocators. */
    void beginFrame();

    /** Marks the end of a frame, reclaiming temporary frame memory. */
    void endFrame();

    /** Allocates a direct off-heap buffer for the current frame. */
    ByteBuffer allocateFrameBuffer(long bytes);

    /** Allocates or registers a named persistent native buffer. */
    long allocatePersistent(String name, long bytes);

    /** Frees a named persistent native buffer. */
    void freePersistent(String name);

    /** Returns the total native memory in bytes currently held by this system. */
    long getTotalAllocatedBytes();
}
