package destiny.renderer.cull;

import destiny.renderer.config.RendererConfig;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * High-performance voxel line-of-sight raycaster for block entity wall occlusion culling.
 *
 * <p>Uses 3D Amanatides-Woo DDA voxel traversal to determine if a block entity
 * (chests, ender chests, shulker boxes, signs, bells) is completely occluded from the
 * player's camera by solid opaque blocks (such as cobblestone walls or floors).
 *
 * <p>Inspired by the core principles of {@code EnhancedBlockEntities} and {@code tr7zw/EntityCulling}
 * with competitive and gameplay safeguards:
 * <ul>
 *   <li>Block entities within 3.5 blocks are never culled (close interaction safety).</li>
 *   <li>Directly targeted blocks (crosshair) are never culled.</li>
 *   <li>Results are cached temporally (~60ms TTL) to eliminate redundant raycasts.</li>
 * </ul>
 */
public final class BlockEntityOcclusionCuller {

    private static final int CACHE_TTL_MS = 40; // Cache for ~2 frames at 60fps for snappy updates
    private static final long CLEANUP_INTERVAL_MS = 4000L;

    // Cache: packed BlockPos long -> packed (timestamp in high 32 bits, boolean in low 1 bit)
    private static final Long2LongOpenHashMap OCCLUSION_CACHE = new Long2LongOpenHashMap();
    private static long lastCleanupTimeMs = 0L;

    private static final ThreadLocal<BlockPos.Mutable> THREAD_POS = ThreadLocal.withInitial(BlockPos.Mutable::new);

    private BlockEntityOcclusionCuller() {}

    /**
     * Evaluates whether the given block entity is completely occluded by solid blocks.
     *
     * @param blockEntity the block entity to test
     * @param cameraPos camera eye position
     * @return true if the block entity is occluded behind walls and should NOT render
     */
    public static boolean isOccluded(BlockEntity blockEntity, Vec3d cameraPos) {
        if (blockEntity == null || cameraPos == null) return false;

        RendererConfig cfg = RendererConfig.get();
        if (!cfg.cullBlockEntities) return false;
        if (!destiny.renderer.compat.WorkAllotment.isOwnedByUs(destiny.renderer.compat.Capability.ENTITY_CULLING)) return false;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.world == null) return false;

        BlockPos pos = blockEntity.getPos();

        // 1. Proximity and targeting safety checks
        double centerX = pos.getX() + 0.5;
        double centerY = pos.getY() + 0.5;
        double centerZ = pos.getZ() + 0.5;

        double dx = centerX - cameraPos.x;
        double dy = centerY - cameraPos.y;
        double dz = centerZ - cameraPos.z;
        double distSq = dx * dx + dy * dy + dz * dz;

        // Close-up safety floor: within 3.5 blocks, never wall-cull
        if (distSq <= 12.25) return false;

        // Crosshair targeting safety
        if (mc.crosshairTarget != null && mc.crosshairTarget.getType() == net.minecraft.util.hit.HitResult.Type.BLOCK) {
            if (mc.crosshairTarget instanceof net.minecraft.util.hit.BlockHitResult bhr) {
                if (bhr.getBlockPos().equals(pos)) return false;
            }
        }

        long posKey = pos.asLong();
        long now = System.currentTimeMillis();

        // 2. Query cache
        synchronized (OCCLUSION_CACHE) {
            if (OCCLUSION_CACHE.containsKey(posKey)) {
                long entry = OCCLUSION_CACHE.get(posKey);
                long timestamp = entry >>> 1;
                if (now - timestamp < CACHE_TTL_MS) {
                    return (entry & 1L) == 1L;
                }
            }
        }

        // 3. Fast 3D Amanatides-Woo DDA raycast
        ClientWorld world = mc.world;
        boolean blocked = isRayBlocked(world, cameraPos.x, cameraPos.y, cameraPos.z,
                centerX, centerY, centerZ, pos);

        // 4. Update cache
        synchronized (OCCLUSION_CACHE) {
            long packed = (now << 1) | (blocked ? 1L : 0L);
            OCCLUSION_CACHE.put(posKey, packed);

            if (now - lastCleanupTimeMs > CLEANUP_INTERVAL_MS) {
                lastCleanupTimeMs = now;
                OCCLUSION_CACHE.entrySet().removeIf(e -> (now - (e.getValue() >>> 1)) > CLEANUP_INTERVAL_MS);
            }
        }

        return blocked;
    }

    /**
     * Traverses the voxel grid from (x0, y0, z0) to (x1, y1, z1).
     * Returns true if obstructed by any opaque full cube prior to reaching the target block pos.
     */
    private static boolean isRayBlocked(ClientWorld world,
                                        double x0, double y0, double z0,
                                        double x1, double y1, double z1,
                                        BlockPos targetPos) {
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

        int maxSteps = 48;
        int steps = 0;

        BlockPos.Mutable mutablePos = THREAD_POS.get();

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

            // If the ray hits our target block position, do not consider the target itself an occluder
            if (x == targetPos.getX() && y == targetPos.getY() && z == targetPos.getZ()) {
                break;
            }

            mutablePos.set(x, y, z);
            BlockState state = world.getBlockState(mutablePos);
            if (state.isOpaqueFullCube()) {
                return true;
            }
        }

        return false;
    }

    /** Invalidates a specific block position in the occlusion cache (e.g. when broken/placed). */
    public static void invalidatePos(long posKey) {
        synchronized (OCCLUSION_CACHE) {
            OCCLUSION_CACHE.remove(posKey);
        }
    }

    /** Clears the cache on world unload / dimension switch. */
    public static void clear() {
        synchronized (OCCLUSION_CACHE) {
            OCCLUSION_CACHE.clear();
        }
    }
}
