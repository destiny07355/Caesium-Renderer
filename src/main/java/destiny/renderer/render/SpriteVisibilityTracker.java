package destiny.renderer.render;

import destiny.renderer.config.RendererConfig;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.block.AbstractFireBlock;
import net.minecraft.block.Blocks;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Frustum;
import net.minecraft.fluid.Fluids;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import org.joml.Matrix4f;

import java.util.function.Predicate;

/**
 * Per-frame view-frustum visibility for the animated-texture categories.
 *
 * <p>Vanilla advances every animated sprite (fire, water, lava, portals, …) every frame
 * regardless of whether anything on screen uses it, then re-uploads the dirty regions of
 * the atlas. In a burning field that is per-frame CPU + GPU work for textures the camera
 * is not even looking at. This tracker answers the question the animation controller needs
 * — <em>"is there any visible block of this texture's category right now?"</em> — so the
 * controller can freeze exactly the categories that are off-screen.
 *
 * <p>Answers are produced from a coarse but cheap signal: each chunk section keeps a
 * cached bitmask of which animated categories it contains (computed lazily via the section
 * palette, invalidated on rebuild), and each scan ORs together the masks of the sections
 * that intersect the current view frustum.
 *
 * <h2>Cost</h2>
 * A scan iterates one column AABB test per loaded chunk column (≈ a few hundred) and a
 * cached mask lookup per section that survives the column test. Sections are only actually
 * classified on first sight. Scans are throttled to {@link #SCAN_INTERVAL_MS} and are a
 * no-op when the world, camera or toggle are absent.
 */
public final class SpriteVisibilityTracker {

    public static final int CAT_FIRE   = 1;
    public static final int CAT_WATER  = 2;
    public static final int CAT_LAVA   = 4;
    public static final int CAT_PORTAL = 8;
    public static final int CAT_SCULK  = 16;

    /** Recompute the visible set at most this often; a one-frame delay is imperceptible. */
    private static final long SCAN_INTERVAL_MS = 100L;

    /** Cached per-section animated-category bitmask, keyed by packed section position. */
    private static final ConcurrentHashMap<Long, Byte> SECTION_MASKS = new ConcurrentHashMap<>();

    private static volatile int visibleCategories = 0;
    private static long lastScanMs = 0L;
    private static Matrix4f projection = null;
    private static Matrix4f view = null;
    private static Vec3d cameraPos = null;

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
     * Records the camera state for this frame. Called unconditionally from
     * {@code WorldRenderer.render} so the animation policy never depends on which backend
     * owns terrain.
     */
    public static void capture(Matrix4f projectionMatrix, Matrix4f viewMatrix, Vec3d cameraPosition) {
        if (projectionMatrix == null || viewMatrix == null || cameraPosition == null) return;
        projection = projectionMatrix;
        view = viewMatrix;
        cameraPos = cameraPosition;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.world == null) return;
        if (!RendererConfig.get().animateOnlyVisibleTextures) return;

        long now = System.currentTimeMillis();
        if (now - lastScanMs < SCAN_INTERVAL_MS) return;
        lastScanMs = now;

        scan(client.world, cameraPosition);
    }

    private static void scan(World world, Vec3d camera) {
        int newVisibleCategories = 0;
        destiny.renderer.cull.FusedFrustumCuller frustum = destiny.renderer.cull.VisibilitySystem.getTerrainFrustum();

        ChunkSectionPos cameraSection = ChunkSectionPos.from(camera);
        int radius = MinecraftClient.getInstance().options.getClampedViewDistance();
        int bottomSection = world.getBottomSectionCoord();
        int topSection = world.getTopSectionCoord();

        int camSecX = cameraSection.getSectionX();
        int camSecZ = cameraSection.getSectionZ();

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

                    int mask = sectionMask(chunk, sy, bottomSection);
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

    private static int sectionMask(WorldChunk chunk, int sectionCoord, int bottomSectionCoord) {
        long key = packSectionKey(chunk.getPos().x, sectionCoord, chunk.getPos().z);
        Byte cached = SECTION_MASKS.get(key);
        if (cached != null) {
            return cached & 0xFF;
        }

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

        SECTION_MASKS.put(key, (byte) mask);
        return mask;
    }

    /** Drops the cached mask for a section that was rebuilt. */
    public static void invalidateSection(long sectionKey) {
        SECTION_MASKS.remove(sectionKey);
    }

    /** Drops every cached mask, e.g. on world reload. */
    public static void invalidateAll() {
        SECTION_MASKS.clear();
        visibleCategories = 0;
        lastScanMs = 0L;
    }

    private static long packSectionKey(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF))
            | (((long) (y & 0x1FFFFF)) << 21)
            | (((long) (z & 0x1FFFFF)) << 42);
    }
}