package destiny.renderer.render;

import destiny.renderer.config.RendererConfig;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.block.AbstractFireBlock;
import net.minecraft.block.Blocks;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.fluid.Fluids;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import org.joml.Matrix4f;

import java.util.function.Predicate;

/**
 * Per-frame view-frustum visibility for animated-texture categories.
 *
 * <p>Freezes off-screen texture animations (fire, water, lava, portals, sculk)
 * without ever stalling the render thread. Section classification is cached
 * and rate-limited to prevent frame drops during world loading or teleportation.
 */
public final class SpriteVisibilityTracker {

    public static final int CAT_FIRE   = 1;
    public static final int CAT_WATER  = 2;
    public static final int CAT_LAVA   = 4;
    public static final int CAT_PORTAL = 8;
    public static final int CAT_SCULK  = 16;

    /** Recompute the visible set at most 4 times a second. */
    private static final long SCAN_INTERVAL_MS = 250L;

    /** Maximum new unclassified sections evaluated per scan to keep frame time < 0.2ms. */
    private static final int MAX_NEW_CLASSIFICATIONS_PER_SCAN = 16;

    /** Cached per-section animated-category bitmask in an unboxed open-addressed primitive cache. */
    private static final int CACHE_CAPACITY = 8192;
    private static final int CACHE_MASK = CACHE_CAPACITY - 1;
    private static final long[] CACHE_KEYS = new long[CACHE_CAPACITY];
    private static final byte[] CACHE_MASKS = new byte[CACHE_CAPACITY];
    private static final boolean[] CACHE_PRESENT = new boolean[CACHE_CAPACITY];
    private static final java.lang.invoke.VarHandle PRESENT_VH =
        java.lang.invoke.MethodHandles.arrayElementVarHandle(boolean[].class);
    private static final Object CACHE_LOCK = new Object();

    private static volatile int visibleCategories = 0x1F;
    private static long lastScanMs = 0L;
    private static final Matrix4f SCAN_MVP = new Matrix4f();
    private static final float[] SCAN_MVP_VALUES = new float[16];

    private static final Predicate<BlockState> IS_FIRE =
        s -> s.getBlock() instanceof AbstractFireBlock;
    private static final Predicate<BlockState> IS_WATER =
        s -> s.getFluidState().isOf(Fluids.WATER);
    private static final Predicate<BlockState> IS_LAVA =
        s -> s.getFluidState().isOf(Fluids.LAVA);
    private static final Predicate<BlockState> IS_PORTAL =
        s -> s.isOf(Blocks.NETHER_PORTAL);
    private static final Predicate<BlockState> IS_SCULK =
        s -> s.isOf(Blocks.SCULK_SENSOR) || s.isOf(Blocks.SCULK) ||
             s.isOf(Blocks.SCULK_CATALYST) || s.isOf(Blocks.SCULK_SHRIEKER);

    private SpriteVisibilityTracker() {}

    /** @return true if at least one block of this category is inside the view frustum. */
    public static boolean isVisible(int categoryBit) {
        return (visibleCategories & categoryBit) != 0;
    }

    private static double lastCamX = 0, lastCamY = 0, lastCamZ = 0;
    private static boolean hasLastCam = false;
    private static World lastWorld = null;

    /**
     * Records the camera state for this frame and triggers a lightweight throttled scan.
     */
    public static void capture(Matrix4f projectionMatrix, Matrix4f viewMatrix, Vec3d cameraPosition) {
        // Always refresh policy so toggles recover immediately, even when visibility
        // scanning is disabled or the world is transitioning.
        SpriteAnimationController.updateFramePolicy();
        if (projectionMatrix == null || viewMatrix == null || cameraPosition == null) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.world == null) return;
        if (!RendererConfig.get().animateOnlyVisibleTextures) {
            visibleCategories = 0x1F;
            return;
        }

        long now = System.currentTimeMillis();
        boolean forceScan = false;
        if (client.world != lastWorld) {
            lastWorld = client.world;
            forceScan = true;
        } else if (hasLastCam) {
            double dx = cameraPosition.x - lastCamX;
            double dy = cameraPosition.y - lastCamY;
            double dz = cameraPosition.z - lastCamZ;
            if (dx * dx + dy * dy + dz * dz > 32.0 * 32.0) { // teleport / sudden jump > 32 blocks
                forceScan = true;
            }
        }
        lastCamX = cameraPosition.x;
        lastCamY = cameraPosition.y;
        lastCamZ = cameraPosition.z;
        hasLastCam = true;

        if (!forceScan && (now - lastScanMs < SCAN_INTERVAL_MS)) return;
        lastScanMs = now;

        projectionMatrix.mul(viewMatrix, SCAN_MVP).get(SCAN_MVP_VALUES);
        destiny.renderer.cull.VisibilitySystem.getTerrainFrustum().update(SCAN_MVP_VALUES);
        scan(client.world, cameraPosition);
    }

    private static void scan(World world, Vec3d camera) {
        int newVisibleCategories = 0;
        destiny.renderer.cull.FusedFrustumCuller frustum = destiny.renderer.cull.VisibilitySystem.getTerrainFrustum();

        ChunkSectionPos cameraSection = ChunkSectionPos.from(camera);
        int radius = Math.min(12, MinecraftClient.getInstance().options.getClampedViewDistance());
        int bottomSection = world.getBottomSectionCoord();
        int topSection = world.getTopSectionCoord();

        int camSecX = cameraSection.getSectionX();
        int camSecZ = cameraSection.getSectionZ();

        long scanStartNs = System.nanoTime();
        long maxScanTimeNs = 150_000L; // 0.15 ms wall-clock budget to prevent scan spikes
        int classifications = 0;

        for (int cx = camSecX - radius; cx <= camSecX + radius; cx++) {
            for (int cz = camSecZ - radius; cz <= camSecZ + radius; cz++) {
                if (!world.getChunkManager().isChunkLoaded(cx, cz)) continue;

                WorldChunk chunk = world.getChunk(cx, cz);
                if (chunk == null) continue;

                int secMinX = cx << 4;
                int secMinZ = cz << 4;

                for (int sy = bottomSection; sy <= topSection; sy++) {
                    int secMinY = sy << 4;
                    if (!frustum.isSectionVisible(secMinX, secMinY, secMinZ)) continue;

                    long key = packSectionKey(cx, sy, cz);
                    int cached = getCachedMask(key);

                    int mask;
                    if (cached != -1) {
                        mask = cached;
                    } else if (classifications < MAX_NEW_CLASSIFICATIONS_PER_SCAN
                            && (System.nanoTime() - scanStartNs) < maxScanTimeNs) {
                        mask = classifySection(chunk, sy, bottomSection);
                        putCachedMask(key, (byte) mask);
                        classifications++;
                    } else {
                        // Stop the entire scan once its work budget is exhausted. Returning
                        // a conservative visible result preserves visuals and avoids walking
                        // the rest of the loaded section grid with no useful classification.
                        visibleCategories = 0x1F;
                        return;
                    }

                    if (mask != 0) {
                        newVisibleCategories |= mask;
                        if (newVisibleCategories == 0x1F) {
                            visibleCategories = 0x1F;
                            return;
                        }
                    }
                }
            }
        }

        visibleCategories = newVisibleCategories;
    }

    private static int getCachedMask(long key) {
        int hash = (int) (key ^ (key >>> 32)) * 0x9E3779B9;
        int idx = hash & CACHE_MASK;
        for (int i = 0; i < 16; i++) {
            int slot = (idx + i) & CACHE_MASK;
            boolean present = (boolean) PRESENT_VH.getAcquire(CACHE_PRESENT, slot);
            if (!present) return -1;
            if (CACHE_KEYS[slot] == key) return CACHE_MASKS[slot] & 0xFF;
        }
        return -1;
    }

    private static void putCachedMask(long key, byte mask) {
        int hash = (int) (key ^ (key >>> 32)) * 0x9E3779B9;
        int idx = hash & CACHE_MASK;
        synchronized (CACHE_LOCK) {
            for (int i = 0; i < 16; i++) {
                int slot = (idx + i) & CACHE_MASK;
                boolean present = (boolean) PRESENT_VH.get(CACHE_PRESENT, slot);
                if (!present || CACHE_KEYS[slot] == key) {
                    CACHE_KEYS[slot] = key;
                    CACHE_MASKS[slot] = mask;
                    PRESENT_VH.setRelease(CACHE_PRESENT, slot, true);
                    return;
                }
            }
            CACHE_KEYS[idx] = key;
            CACHE_MASKS[idx] = mask;
            PRESENT_VH.setRelease(CACHE_PRESENT, idx, true);
        }
    }

    private static int classifySection(WorldChunk chunk, int sectionCoord, int bottomSectionCoord) {
        int sectionIdx = sectionCoord - bottomSectionCoord;
        ChunkSection[] sections = chunk.getSectionArray();
        if (sectionIdx < 0 || sectionIdx >= sections.length) return 0;
        ChunkSection section = sections[sectionIdx];
        if (section == null || section.isEmpty()) return 0;

        int mask = 0;
        if (section.hasAny(IS_FIRE)) mask |= CAT_FIRE;
        if (section.hasAny(IS_WATER)) mask |= CAT_WATER;
        if (section.hasAny(IS_LAVA)) mask |= CAT_LAVA;
        if (section.hasAny(IS_PORTAL)) mask |= CAT_PORTAL;
        if (section.hasAny(IS_SCULK)) mask |= CAT_SCULK;

        return mask;
    }

    /** Drops the cached mask for a section that was rebuilt. */
    public static void invalidateSection(long sectionKey) {
        int hash = (int) (sectionKey ^ (sectionKey >>> 32)) * 0x9E3779B9;
        int idx = hash & CACHE_MASK;
        synchronized (CACHE_LOCK) {
            for (int i = 0; i < 16; i++) {
                int slot = (idx + i) & CACHE_MASK;
                if ((boolean) PRESENT_VH.get(CACHE_PRESENT, slot) && CACHE_KEYS[slot] == sectionKey) {
                    PRESENT_VH.setRelease(CACHE_PRESENT, slot, false);
                    return;
                }
            }
        }
    }

    /** Drops every cached mask, e.g. on world reload. */
    public static void invalidateAll() {
        synchronized (CACHE_LOCK) {
            java.util.Arrays.fill(CACHE_PRESENT, false);
        }
        visibleCategories = 0x1F;
        lastScanMs = 0L;
        hasLastCam = false;
        lastWorld = null;
    }

    public static void clear() {
        invalidateAll();
    }

    public static void invalidateChunk(long chunkX, long chunkZ) {
        synchronized (CACHE_LOCK) {
            for (int slot = 0; slot < CACHE_KEYS.length; slot++) {
                if ((boolean) PRESENT_VH.get(CACHE_PRESENT, slot)) {
                    long key = CACHE_KEYS[slot];
                    int sx = (int) (key & 0x1FFFFF);
                    if ((sx & 0x100000) != 0) sx |= 0xFFE00000;
                    int sz = (int) ((key >>> 42) & 0x1FFFFF);
                    if ((sz & 0x100000) != 0) sz |= 0xFFE00000;
                    if (sx == (int) chunkX && sz == (int) chunkZ) {
                        PRESENT_VH.setRelease(CACHE_PRESENT, slot, false);
                    }
                }
            }
        }
    }

    private static long packSectionKey(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF))
            | (((long) (y & 0x1FFFFF)) << 21)
            | (((long) (z & 0x1FFFFF)) << 42);
    }
}
