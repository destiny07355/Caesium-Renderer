package destiny.renderer.particle;

import destiny.renderer.config.RendererConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleType;
import net.minecraft.particle.ParticleTypes;

import java.util.Set;

/**
 * High-performance early-out particle pipeline governor.
 *
 * <p>Enforces zero-allocation early rejection before Minecraft creates particle objects,
 * runs physics updates, or uploads vertex geometry to the GPU.
 */
public final class CaesiumParticlePolicy {

    private static final Set<ParticleType<?>> GAMEPLAY_CRITICAL = Set.of(
        ParticleTypes.ELDER_GUARDIAN,
        ParticleTypes.SONIC_BOOM,
        ParticleTypes.TRIAL_SPAWNER_DETECTION,
        ParticleTypes.RAID_OMEN,
        ParticleTypes.OMINOUS_SPAWNING
    );

    private static int bresenhamAccum = 0;

    /**
     * Determines whether a particle should be instantiated and admitted into the simulation.
     * Evaluated at the HEAD of ParticleManager.addParticle() before any allocations.
     *
     * @return true to allow particle creation; false to reject immediately
     */
    private static float lastYaw = Float.NaN;
    private static float lastPitch = Float.NaN;
    private static double cachedLx = 0.0, cachedLy = 0.0, cachedLz = 1.0;

    private static void updateCameraLook(float yaw, float pitch) {
        if (yaw != lastYaw || pitch != lastPitch) {
            double radYaw = Math.toRadians(yaw);
            double radPitch = Math.toRadians(pitch);
            cachedLx = -Math.sin(radYaw) * Math.cos(radPitch);
            cachedLy = -Math.sin(radPitch);
            cachedLz = Math.cos(radYaw) * Math.cos(radPitch);
            lastYaw = yaw;
            lastPitch = pitch;
        }
    }

    public static boolean shouldSpawn(ParticleEffect effect, double x, double y, double z) {
        if (effect == null) return false;

        ParticleType<?> type = effect.getType();
        RendererConfig cfg = RendererConfig.get();

        if (!CaesiumParticleRegistry.isParticleAllowed(type)) {
            return false;
        }

        if (cfg.disableAllParticles) {
            return false;
        }

        if (!ParticleClassifier.isEnabled(effect, cfg)) {
            return false;
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null) return true;

        double dx = mc.player.getX() - x;
        double dy = mc.player.getY() - y;
        double dz = mc.player.getZ() - z;
        double distSq = dx * dx + dy * dy + dz * dz;

        int cullDist = cfg.particleCullDistance;
        if (cullDist > 0 && distSq > (double) cullDist * cullDist) {
            return false;
        }

        if (distSq > 9.0 && mc.gameRenderer != null) {
            net.minecraft.client.render.Camera cam = mc.gameRenderer.getCamera();
            if (cam != null && cam.isReady()) {
                net.minecraft.util.math.Vec3d camPos = cam.getCameraPos();
                double cdx = x - camPos.x;
                double cdy = y - camPos.y;
                double cdz = z - camPos.z;
                updateCameraLook(cam.getYaw(), cam.getPitch());
                double dot = cdx * cachedLx + cdy * cachedLy + cdz * cachedLz;
                if (dot < -1.0) {
                    return false; // Behind camera
                }
            }
        }

        // Density sampling
        if (mc.options != null) {
            net.minecraft.particle.ParticlesMode mode = mc.options.getParticles().getValue();
            if (mode == net.minecraft.particle.ParticlesMode.MINIMAL) {
                // In MINIMAL mode, drop purely decorative ambient particles
                if (isPurelyDecorative(type)) return false;
            } else if (mode == net.minecraft.particle.ParticlesMode.DECREASED) {
                int keepPercent = Math.max(10, cfg.minimalParticleLimitRatio);
                bresenhamAccum += keepPercent;
                if (bresenhamAccum < 100) {
                    return false;
                }
                bresenhamAccum -= 100;
            }
        }

        return true;
    }

    private static boolean isPurelyDecorative(ParticleType<?> type) {
        return type == ParticleTypes.SMOKE ||
               type == ParticleTypes.LARGE_SMOKE ||
               type == ParticleTypes.CAMPFIRE_COSY_SMOKE ||
               type == ParticleTypes.CAMPFIRE_SIGNAL_SMOKE ||
               type == ParticleTypes.DRIPPING_WATER ||
               type == ParticleTypes.DRIPPING_LAVA ||
               type == ParticleTypes.DRIPPING_HONEY ||
               type == ParticleTypes.PORTAL ||
               type == ParticleTypes.MYCELIUM ||
               type == ParticleTypes.UNDERWATER ||
               type == ParticleTypes.ASH ||
               type == ParticleTypes.WHITE_ASH;
    }
}
