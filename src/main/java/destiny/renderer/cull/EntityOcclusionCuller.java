package destiny.renderer.cull;

import destiny.renderer.config.RendererConfig;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

/**
 * High-performance voxel line-of-sight raycaster for entity wall occlusion culling.
 *
 * <p>Uses Amanatides-Woo 3D Digital Differential Analyzer (DDA) fast voxel traversal
 * to check whether line-of-sight between the camera eye and an entity is completely
 * obstructed by opaque full blocks (such as cobblestone, obsidian, terrain).
 *
 * <p>Inspired by the core principles of {@code tr7zw/EntityCulling} with fair-play
 * safeguards:
 * <ul>
 *   <li>Combat safety: players and crystals within 24m are never wall-culled.</li>
 *   <li>Glowing entities (spectral arrows, outlines) are never culled.</li>
 *   <li>Directly targeted entities (crosshair) are never culled.</li>
 *   <li>Multi-point sampling (center, top, lateral corners) prevents false-positive occlusion when peeking.</li>
 *   <li>Target block exemption: target's own block is never counted as an occluding wall.</li>
 *   <li>Results are cached temporally to eliminate redundant raycasts per frame.</li>
 * </ul>
 */
public final class EntityOcclusionCuller {

    private static final int CACHE_TTL_MS = 40; // Re-evaluate every ~2 frames at 60fps
    private static final long CLEANUP_INTERVAL_MS = 4000L;

    // Cache: entityId -> packed (timestamp in high 32 bits, boolean in low 1 bit)
    private static final Int2LongOpenHashMap OCCLUSION_CACHE = new Int2LongOpenHashMap();
    private static final IntOpenHashSet OCCLUDED_ENTITIES = new IntOpenHashSet();
    private static long lastCleanupTimeMs = 0L;

    private static final ThreadLocal<BlockPos.Mutable> THREAD_POS = ThreadLocal.withInitial(BlockPos.Mutable::new);

    private EntityOcclusionCuller() {}

    /**
     * Evaluates whether the given entity is completely occluded by solid blocks.
     *
     * @param entity the entity to test
     * @param camX camera X coordinate
     * @param camY camera Y coordinate
     * @param camZ camera Z coordinate
     * @return true if the entity is occluded by walls and should NOT be rendered
     */
    public static boolean isOccluded(Entity entity, double camX, double camY, double camZ) {
        if (entity == null) return false;

        RendererConfig cfg = RendererConfig.get();
        if (!cfg.cullEntities) return false;
        if (!destiny.renderer.compat.WorkAllotment.isOwnedByUs(destiny.renderer.compat.Capability.ENTITY_CULLING)) return false;

        // 1. Fair-play bypasses: glowing, targeted, or close proximity
        if (entity.isGlowing()) return false;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.world == null) return false;
        if (mc.targetedEntity == entity || mc.getCameraEntity() == entity) return false;

        double entX = entity.getX();
        double entY = entity.getY();
        double entZ = entity.getZ();

        double dx = entX - camX;
        double dy = entY - camY;
        double dz = entZ - camZ;
        double distSq = dx * dx + dy * dy + dz * dz;

        // Close-up safety floor: within 3.5 blocks, never wall-cull
        if (distSq <= 12.25) return false;

        // Combat safety floor: players and end crystals within 24m are NEVER occluded
        if ((entity instanceof net.minecraft.entity.player.PlayerEntity
                || entity instanceof net.minecraft.entity.decoration.EndCrystalEntity)
                && distSq <= 24.0 * 24.0) {
            return false;
        }

        int id = entity.getId();
        long now = System.currentTimeMillis();

        // 2. Query cache
        synchronized (OCCLUSION_CACHE) {
            if (OCCLUSION_CACHE.containsKey(id)) {
                long entry = OCCLUSION_CACHE.get(id);
                long timestamp = entry >>> 1;
                if (now - timestamp < CACHE_TTL_MS) {
                    return (entry & 1L) == 1L;
                }
            }
        }

        // 3. Perform fast voxel line-of-sight test
        Box box = entity.getBoundingBox();
        double targetX = (box.minX + box.maxX) * 0.5;
        double targetY = (box.minY + box.maxY) * 0.5;
        double targetZ = (box.minZ + box.maxZ) * 0.5;

        ClientWorld world = mc.world;
        boolean centerBlocked = isRayBlocked(world, camX, camY, camZ, targetX, targetY, targetZ);
        boolean fullyOccluded = false;

        if (centerBlocked) {
            // Also test top point of bounding box to prevent pop-in on tall entities
            double topY = Math.min(box.maxY, targetY + 1.2);
            boolean topBlocked = isRayBlocked(world, camX, camY, camZ, targetX, topY, targetZ);
            if (topBlocked) {
                // Test corner extents so peeking entities or entities at block edges are never culled
                double halfW = (box.maxX - box.minX) * 0.45;
                double halfD = (box.maxZ - box.minZ) * 0.45;
                boolean corner1 = isRayBlocked(world, camX, camY, camZ, targetX + halfW, targetY, targetZ + halfD);
                boolean corner2 = isRayBlocked(world, camX, camY, camZ, targetX - halfW, targetY, targetZ - halfD);
                fullyOccluded = corner1 && corner2;
            }
        }

        // 4. Update cache
        synchronized (OCCLUSION_CACHE) {
            long packed = (now << 1) | (fullyOccluded ? 1L : 0L);
            OCCLUSION_CACHE.put(id, packed);
            if (fullyOccluded) {
                OCCLUDED_ENTITIES.add(id);
            } else {
                OCCLUDED_ENTITIES.remove(id);
            }

            if (now - lastCleanupTimeMs > CLEANUP_INTERVAL_MS) {
                lastCleanupTimeMs = now;
                OCCLUSION_CACHE.entrySet().removeIf(e -> (now - (e.getValue() >>> 1)) > CLEANUP_INTERVAL_MS);
            }
        }

        return fullyOccluded;
    }

    /**
     * Fast 3D Amanatides-Woo DDA voxel traversal algorithm.
     * Returns true if any opaque full block is hit between start and target.
     */
    private static boolean isRayBlocked(ClientWorld world,
                                        double x0, double y0, double z0,
                                        double x1, double y1, double z1) {
        int x = (int) Math.floor(x0);
        int y = (int) Math.floor(y0);
        int z = (int) Math.floor(z0);

        int endX = (int) Math.floor(x1);
        int endY = (int) Math.floor(y1);
        int endZ = (int) Math.floor(z1);

        double dx = x1 - x0;
        double dy = y1 - y0;
        double dz = z1 - z0;

        int stepX = dx > 0 ? 1 : (dx < 0 ? -1 : 0);
        int stepY = dy > 0 ? 1 : (dy < 0 ? -1 : 0);
        int stepZ = dz > 0 ? 1 : (dz < 0 ? -1 : 0);

        double tDeltaX = stepX != 0 ? Math.min(Math.abs(1.0 / dx), 1e6) : 1e6;
        double tDeltaY = stepY != 0 ? Math.min(Math.abs(1.0 / dy), 1e6) : 1e6;
        double tDeltaZ = stepZ != 0 ? Math.min(Math.abs(1.0 / dz), 1e6) : 1e6;

        double tMaxX = stepX > 0 ? (x + 1.0 - x0) * tDeltaX : (x0 - x) * tDeltaX;
        double tMaxY = stepY > 0 ? (y + 1.0 - y0) * tDeltaY : (y0 - y) * tDeltaY;
        double tMaxZ = stepZ > 0 ? (z + 1.0 - z0) * tDeltaZ : (z0 - z) * tDeltaZ;

        int maxSteps = 48; // Max blocks to march
        int steps = 0;

        BlockPos.Mutable pos = THREAD_POS.get();

        while ((x != endX || y != endY || z != endZ) && steps++ < maxSteps) {
            if (tMaxX < tMaxY) {
                if (tMaxX < tMaxZ) {
                    x += stepX;
                    tMaxX += tDeltaX;
                } else {
                    z += stepZ;
                    tMaxZ += tDeltaZ;
                }
            } else {
                if (tMaxY < tMaxZ) {
                    y += stepY;
                    tMaxY += tDeltaY;
                } else {
                    z += stepZ;
                    tMaxZ += tDeltaZ;
                }
            }

            if (x == endX && y == endY && z == endZ) {
                break;
            }

            pos.set(x, y, z);
            BlockState state = world.getBlockState(pos);
            if (state.isOpaqueFullCube()) {
                return true;
            }
        }

        return false;
    }

    /** Clears the cache on world leave / dimension change. */
    public static void clear() {
        synchronized (OCCLUSION_CACHE) {
            OCCLUSION_CACHE.clear();
            OCCLUDED_ENTITIES.clear();
        }
    }
}
