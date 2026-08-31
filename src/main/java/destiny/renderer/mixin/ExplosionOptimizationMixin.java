package destiny.renderer.mixin;

import destiny.renderer.config.RendererConfig;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Rate-limits explosion particles.
 *
 * <h2>The problem</h2>
 * A single large blast — a TNT array, an end crystal chain, a creeper stack — emits
 * hundreds of {@code EXPLOSION} and {@code EXPLOSION_EMITTER} particles in one tick. Each
 * is a translucent, physics-ticked billboard, and they all overlap in a small volume, so
 * the cost is almost entirely wasted overdraw. This is a classic multi-second freeze.
 *
 * <h2>The fix</h2>
 * Track how many explosion particles have spawned within a short window and stop accepting
 * new ones past the configured budget. The blast still reads clearly because the first
 * particles spawned are the ones nearest the centre.
 */
@Mixin(ParticleManager.class)
public abstract class ExplosionOptimizationMixin {

    private static final java.util.Map<Long, long[]> destinyrenderer$bursts = new java.util.concurrent.ConcurrentHashMap<>();

    /** Explosion particles arriving within this window count against one budget. */
    private static final long BURST_WINDOW_MS = 250L;

    private static long destinyrenderer$posKey(double x, double y, double z) {
        long bx = ((long) Math.floor(x)) >> 4;
        long by = ((long) Math.floor(y)) >> 4;
        long bz = ((long) Math.floor(z)) >> 4;
        return (bx & 0x3FFFFFL) | ((bz & 0x3FFFFFL) << 22) | ((by & 0xFFFL) << 44);
    }

    @Inject(
        method = "addParticle(Lnet/minecraft/particle/ParticleEffect;DDDDDD)Lnet/minecraft/client/particle/Particle;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void destinyrenderer$limitExplosionBurst(
            ParticleEffect effect,
            double x, double y, double z,
            double vx, double vy, double vz,
            CallbackInfoReturnable<Particle> cir) {

        RendererConfig cfg = RendererConfig.get();
        if (!cfg.optimizeExplosions || cfg.maxExplosionParticles <= 0) return;
        if (effect == null) return;

        var type = effect.getType();
        boolean isBlast = type == ParticleTypes.EXPLOSION
                       || type == ParticleTypes.EXPLOSION_EMITTER
                       || type == ParticleTypes.GUST
                       || type == ParticleTypes.GUST_EMITTER_LARGE
                       || type == ParticleTypes.GUST_EMITTER_SMALL;
        if (!isBlast) return;

        long now = System.currentTimeMillis();
        long key = destinyrenderer$posKey(x, y, z);
        long[] state = destinyrenderer$bursts.computeIfAbsent(key, k -> new long[]{now, 0L});

        if (now - state[0] > BURST_WINDOW_MS) {
            state[0] = now;
            state[1] = 0L;
        }

        if (++state[1] > cfg.maxExplosionParticles) {
            cir.setReturnValue(null);
        }
    }
}
