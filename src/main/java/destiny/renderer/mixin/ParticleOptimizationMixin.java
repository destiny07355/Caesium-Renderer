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
        } else {
            destiny.renderer.particle.CaesiumParticleMetrics.recordSpawn();
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
            if (!destiny.renderer.particle.CaesiumParticleMetrics.checkAndRecordSpawn(cfg.maxParticleCount)) {
                ci.cancel();
            }
        }
    }
}
