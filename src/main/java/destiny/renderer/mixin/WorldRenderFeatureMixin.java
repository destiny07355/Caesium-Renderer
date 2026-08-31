package destiny.renderer.mixin;

import destiny.renderer.config.RendererConfig;
import net.minecraft.client.render.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Implements the world-level render toggles from the Details settings page.
 *
 * <p>Each of these skips an entire render pass at its entry point, which is both the
 * cheapest place to intervene and the safest — no partial state is left behind for a
 * later pass to trip over.
 *
 * <p>Every toggle here previously existed in the config and the settings screen but was
 * read by nothing at all.
 */
@Mixin(WorldRenderer.class)
public abstract class WorldRenderFeatureMixin {

    /** Skips sky dome, sun, moon and stars as a group. */
    @Inject(method = "renderSky", at = @At("HEAD"), cancellable = true)
    private void destinyrenderer$skipSky(CallbackInfo ci) {
        RendererConfig cfg = RendererConfig.get();
        if (!cfg.enableSky) {
            ci.cancel();
        }
    }

    /** Skips the cloud layer. */
    @Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true)
    private void destinyrenderer$skipClouds(CallbackInfo ci) {
        if (!RendererConfig.get().enableClouds) {
            ci.cancel();
        }
    }

    /** Skips rain and snow geometry. A solid win during storms on weaker hardware. */
    @Inject(method = "renderWeather", at = @At("HEAD"), cancellable = true)
    private void destinyrenderer$skipWeather(CallbackInfo ci) {
        if (!RendererConfig.get().enableWeather) {
            ci.cancel();
        }
    }

    /**
     * Skips the block entity pass — chests, signs, banners, beacons and so on.
     * This is one of the largest single savings available in storage-heavy bases.
     */
    @Inject(method = "renderBlockEntities", at = @At("HEAD"), cancellable = true)
    private void destinyrenderer$skipBlockEntities(CallbackInfo ci) {
        if (!RendererConfig.get().enableBlockEntities) {
            ci.cancel();
        }
    }
}
