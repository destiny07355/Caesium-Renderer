package destiny.renderer.mixin;

import destiny.renderer.config.RendererConfig;
import destiny.renderer.particle.ParticleClassifier;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticlesMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Enforces the granular particle controls exposed on the Particles settings page.
 *
 * <p>Cancelling at {@code addParticle} is the cheapest possible intervention: the particle
 * is never constructed, so it costs no allocation, no per-tick physics and no draw call.
 *
 * <h2>Fixes over the previous implementation</h2>
 * <ul>
 *   <li>The old code checked {@code mode == MINIMAL} under a comment describing OFF mode,
 *       so the branch never did what it claimed.</li>
 *   <li>Its "density ratio" used {@code (counter % 100) > limit}, which drops particles in
 *       contiguous runs rather than sampling evenly — visibly clumpy.</li>
 *   <li>None of the per-type toggles were read at all; every one of them was inert.</li>
 * </ul>
 */
@Mixin(ParticleManager.class)
public abstract class ParticleOptimizationMixin {

    /** Rolling counter used for even density sampling. */
    private static int destinyrenderer$sampleAccum = 0;

    /** Approximate live particle count, maintained locally to avoid touching internals. */
    private static int destinyrenderer$liveEstimate = 0;
    private static long destinyrenderer$lastDecayMs = 0L;

    @Inject(
        method = "addParticle(Lnet/minecraft/particle/ParticleEffect;DDDDDD)Lnet/minecraft/client/particle/Particle;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void destinyrenderer$filterParticle(
        ParticleEffect effect,
        double x, double y, double z,
        double vx, double vy, double vz,
        CallbackInfoReturnable<Particle> cir
    ) {
        if (!destiny.renderer.particle.CaesiumParticlePolicy.shouldSpawn(effect, x, y, z)) {
            cir.setReturnValue(null);
        }
    }



    /**
     * Block-break particles (mining), fireworks and a few other paths call
     * {@code addParticle(Particle)} directly, bypassing the {@link ParticleEffect} overload
     * filtered above. That overload still has to obey the master kill switch and the hard
     * population cap, or "Disable All Particles" leaks mining dust everywhere.
     *
     * <p>Per-type classification is not possible here: {@link Particle} exposes no type, so
     * only the global switches apply. That is acceptable, since the granular toggles exist
     * for effects spawned through the typed path.
     */
    @Inject(
        method = "addParticle(Lnet/minecraft/client/particle/Particle;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void destinyrenderer$filterDirectParticle(Particle particle, CallbackInfo ci) {
        RendererConfig cfg = RendererConfig.get();

        if (cfg.disableAllParticles) {
            ci.cancel();
            return;
        }

        if (cfg.maxParticleCount > 0) {
            decayEstimate();
            if (destinyrenderer$liveEstimate >= cfg.maxParticleCount) {
                ci.cancel();
                return;
            }
            destinyrenderer$liveEstimate++;
        }
    }

    /**
     * Particles are short-lived, so rather than tracking every death we bleed the estimate
     * down over time. This keeps the cap approximately correct without hooking removal.
     */
    private static void decayEstimate() {
        long now = System.currentTimeMillis();
        if (destinyrenderer$lastDecayMs == 0L) {
            destinyrenderer$lastDecayMs = now;
            return;
        }
        long elapsed = now - destinyrenderer$lastDecayMs;
        if (elapsed >= 100L) {
            int decaySteps = (int) (elapsed / 100L);
            destinyrenderer$liveEstimate =
                Math.max(0, destinyrenderer$liveEstimate - decaySteps * 40);
            destinyrenderer$lastDecayMs = now;
        }
    }
}
