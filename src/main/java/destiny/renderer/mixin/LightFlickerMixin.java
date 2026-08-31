package destiny.renderer.mixin;

import destiny.renderer.config.RendererConfig;
import net.minecraft.client.render.LightmapTextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Implements the "Light Flickering" toggle from the Quality page.
 *
 * <p>Vanilla walks {@code flickerIntensity} around zero each tick and the lightmap adds
 * it to a constant base (1.5) when computing torch brightness, which is what makes
 * torches and candles shimmer. Pinning it to zero leaves the steady base intact and
 * removes the flicker entirely.
 */
@Mixin(LightmapTextureManager.class)
public abstract class LightFlickerMixin {

    @Shadow private float flickerIntensity;

    @Inject(method = "tick", at = @At("TAIL"))
    private void caesium$disableFlicker(CallbackInfo ci) {
        if (!RendererConfig.get().enableLightFlicker) {
            this.flickerIntensity = 0.0f;
        }
    }
}
