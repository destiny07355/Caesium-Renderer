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

    /** Cached per-section animated-category bitmask, keyed by packed section position. */
    private static final ConcurrentHashMap<Long, Byte> SECTION_MASKS = new ConcurrentHashMap<>();

    private static volatile int visibleCategories = 0x1F;
    private static long lastScanMs = 0L;

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

    /**
     * Records the camera state for this frame and triggers a lightweight throttled scan.
     */
    public static void capture(Matrix4f projectionMatrix, Matrix4f viewMatrix, Vec3d cameraPosition) {
        if (projectionMatrix == null || viewMatrix == null || cameraPosition == null) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.world == null) return;
        if (!RendererConfig.get().animateOnlyVisibleTextures) {
            visibleCategories = 0x1F;
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastScanMs < SCAN_INTERVAL_MS) return;
        lastScanMs = now;

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

        int classifiedThisScan = 0;

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
                    Byte cached = SECTION_MASKS.get(key);

                    int mask;
                    if (cached != null) {
                        mask = cached & 0xFF;
                    } else if (classifiedThisScan < MAX_NEW_CLASSIFICATIONS_PER_SCAN) {
                        mask = classifySection(chunk, sy, bottomSection);
                        SECTION_MASKS.put(key, (byte) mask);
                        classifiedThisScan++;
                    } else {
                        // Under load, assume visible so we never drop frames computing masks
                        mask = 0x1F;
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
        SECTION_MASKS.remove(sectionKey);
    }

    /** Drops every cached mask, e.g. on world reload. */
    public static void invalidateAll() {
        SECTION_MASKS.clear();
        visibleCategories = 0x1F;
        lastScanMs = 0L;
    }

    private static long packSectionKey(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF))
            | (((long) (y & 0x1FFFFF)) << 21)
            | (((long) (z & 0x1FFFFF)) << 42);
    }
}