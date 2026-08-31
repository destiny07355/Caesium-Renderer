package destiny.renderer.memory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Central manager for all off-heap FFM memory arenas used by the rendering engine.
 *
 * <p>Maintains three categories of arenas:
 * <ul>
 *   <li><b>Persistent arenas</b> — long-lived VRAM-mapped buffers (chunk geometry, indirect commands)</li>
 *   <li><b>Frame arenas</b> — per-frame temporary data (entity vertices, particle quads), freed at frame end</li>
 *   <li><b>Task arenas</b> — per-meshing-task allocations, freed when the task completes</li>
 * </ul>
 *
 * <p>By operating off-heap, all allocations bypass the Java GC entirely, eliminating
 * allocation-stall pauses during heavy chunk meshing. The Generational ZGC overhead
 * is therefore limited solely to short-lived Java wrapper objects, not the bulk geometry data.
 */
public final class RendererArenaManager {

    private static final Logger LOGGER = Logger.getLogger("Caesium/Arena");

    /** Total bytes currently allocated across all live arenas. */
    private static final AtomicLong totalAllocatedBytes = new AtomicLong(0L);

    /**
     * Named persistent arenas — keyed by a descriptive label such as "terrain_vbo" or "indirect_cmds".
     * These arenas remain open for the entire session and are backed by large GPU-mapped buffers.
     */
    private static final Map<String, Arena> persistentArenas = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> arenaBytes = new ConcurrentHashMap<>();

    /**
     * The current frame arena. Replaced at the start of every frame via {@link #beginFrame()}.
     * All dynamic geometry (entities, particles, GUI overlays) is written here and automatically
     * reclaimed when {@link #endFrame()} is called.
     */
    private static MemorySegment frameBuffer = null;
    private static final long FRAME_BUFFER_SIZE = 32L * 1024 * 1024; // 32 MB
    private static long frameBumpOffset = 0L;
    private static final Arena frameArenaWrapper = Arena.ofAuto();

    /** High-performance async geometry upload ring. */
    private static GpuUploadRing uploadRing = null;

    /** Initialization flag — prevents double-initialization on modded startup hooks. */
    private static volatile boolean initialized = false;

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Initializes the arena manager. Must be called once on the render thread after the
     * OpenGL context is created. Allocates the initial frame arena.
     */
    public static void initialize() {
        if (initialized) return;
        initialized = true;
        frameBuffer = frameArenaWrapper.allocate(FRAME_BUFFER_SIZE, 8);
        if (uploadRing == null) {
            uploadRing = new GpuUploadRing();
            uploadRing.initialize();
        }
        LOGGER.info("[Caesium] RendererArenaManager initialized — FFM off-heap arenas & UploadRing active.");
    }

    public static GpuUploadRing getUploadRing() {
        return uploadRing;
    }

    /**
     * Shuts down all arenas and releases all off-heap memory. Must be called during mod unload
     * or JVM shutdown to prevent memory leaks. Persistent buffer unmapping must occur before this.
     */
    public static void shutdown() {
        if (uploadRing != null) {
            uploadRing.shutdown();
            uploadRing = null;
        }
        for (Map.Entry<String, Arena> entry : persistentArenas.entrySet()) {
            try {
                entry.getValue().close();
                LOGGER.fine("[Caesium] Closed persistent arena: " + entry.getKey());
            } catch (Exception e) {
                LOGGER.warning("[Caesium] Failed to close arena '" + entry.getKey() + "': " + e.getMessage());
            }
        }
        persistentArenas.clear();
        totalAllocatedBytes.set(0L);
        initialized = false;
        LOGGER.info("[Caesium] RendererArenaManager shut down — all off-heap memory released.");
    }

    // -------------------------------------------------------------------------
    // Frame-scoped operations
    // -------------------------------------------------------------------------

    public static void beginFrame() {
        frameBumpOffset = 0L;
        if (uploadRing != null) {
            uploadRing.flushFrameUploads(destiny.renderer.config.RendererConfig.get().maxChunkUpdatesPerFrame);
        }
    }

    /**
     * Ends the current rendering frame.
     */
    public static void endFrame() {
    }

    /**
     * Allocates a contiguous block of off-heap memory in the current frame arena.
     * The returned segment is valid only until the next call to {@link #beginFrame()}.
     *
     * @param bytes the number of bytes to allocate
     * @return a writable, off-heap {@link MemorySegment} of the requested size
     */
    public static MemorySegment allocateFrame(long bytes) {
        long aligned = (bytes + 7L) & ~7L;
        if (frameBuffer == null || frameBumpOffset + aligned > FRAME_BUFFER_SIZE) {
            return Arena.ofAuto().allocate(bytes, 8L);
        }
        MemorySegment slice = frameBuffer.asSlice(frameBumpOffset, bytes);
        frameBumpOffset += aligned;
        return slice;
    }

    /**
     * Allocates a {@code float[]} worth of frame memory. Convenience wrapper for SIMD-aligned
     * float array allocations used during CPU-side culling.
     *
     * @param floatCount number of floats (bytes = floatCount × 4)
     * @return aligned float-width MemorySegment
     */
    public static MemorySegment allocateFrameFloats(int floatCount) {
        return allocateFrame(floatCount * 4L);
    }

    // -------------------------------------------------------------------------
    // Persistent arena operations
    // -------------------------------------------------------------------------

    /**
     * Creates or retrieves a named persistent arena. Persistent arenas are designed to
     * hold large GPU-mapped buffers for the duration of the game session.
     *
     * @param name descriptive label, e.g. "terrain_vbo", "indirect_commands"
     * @return the existing or newly created {@link Arena}
     */
    public static Arena getOrCreatePersistentArena(String name) {
        return persistentArenas.computeIfAbsent(name, k -> {
            LOGGER.fine("[Caesium] Created persistent arena: " + k);
            return Arena.ofShared();
        });
    }

    public static void closeArena(String name) {
        Arena arena = persistentArenas.remove(name);
        if (arena != null) {
            try {
                arena.close();
                Long removed = arenaBytes.remove(name);
                if (removed != null) totalAllocatedBytes.addAndGet(-removed);
            } catch (Exception ignored) {}
        }
    }

    /**
     * Allocates a large persistent off-heap segment intended to back an OpenGL buffer object.
     * The caller is responsible for mapping the OpenGL buffer to this segment's address.
     *
     * @param arenaName  the logical name for the owning arena
     * @param bytes      the size in bytes
     * @param alignment  required byte alignment (typically 64 for SIMD, 4 for vertex data)
     * @return a persistent {@link MemorySegment}
     */
    public static MemorySegment allocatePersistent(String arenaName, long bytes, long alignment) {
        Arena arena = getOrCreatePersistentArena(arenaName);
        MemorySegment seg = arena.allocate(bytes, alignment);
        arenaBytes.put(arenaName, arenaBytes.getOrDefault(arenaName, 0L) + bytes);
        totalAllocatedBytes.addAndGet(bytes);
        return seg;
    }

    /**
     * Creates a scoped task arena for a single meshing task. The returned arena should be
     * used as a try-with-resources scope so the transient scratch memory is freed immediately
     * after meshing completes, rather than waiting for the frame boundary.
     *
     * @return a new {@link Arena.ofConfined()} scoped to the calling thread
     */
    public static Arena createTaskArena() {
        return Arena.ofConfined();
    }

    // -------------------------------------------------------------------------
    // Diagnostics
    // -------------------------------------------------------------------------

    /** @return total bytes currently allocated across all persistent arenas */
    public static long getTotalAllocatedBytes() {
        return totalAllocatedBytes.get();
    }

    /** @return true if the manager has been initialized */
    public static boolean isInitialized() {
        return initialized;
    }
}
