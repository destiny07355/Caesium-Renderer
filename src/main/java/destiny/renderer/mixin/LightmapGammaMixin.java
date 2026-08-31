package destiny.renderer.mixin;

import destiny.renderer.config.RendererConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.SimpleOption;
import net.minecraft.client.render.LightmapTextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Implements fullbright by boosting the gamma value read while the lightmap is updated.
 *
 * <p>The lightmap update is the single place in the renderer that consumes the gamma
 * option for drawing (verified against the 1.21.11 bytecode: the gamma option getter is
 * only invoked by the lightmap manager and the video-options screen). Boosting the value
 * there flattens the lighting curve so caves and night are fully lit.
 *
 * <p>Fullbright is deliberately <em>not</em> implemented by overriding
 * {@code SimpleOption.getValue()}: that leaks the boosted value (up to 20.0) into
 * {@code GameOptions.write()}, whose codec range is clamped to [0.0, 1.0], producing the
 * recurring {@code Error saving option Brightness} entries in the log. Because the
 * override is now scoped to the lightmap, the vanilla option keeps its real value and
 * saves cleanly while rendering is still fully bright.
 */
@Mixin(LightmapTextureManager.class)
public abstract class LightmapGammaMixin {

    /**
     * Redirects every {@code SimpleOption.getValue()} call inside the lightmap update.
     *
     * <p>The lightmap update reads three options per frame (AO, darkness effect scale and
     * gamma). Only the gamma call is intercepted, identified by instance identity against
     * {@code GameOptions.getGamma()}. Calls to {@code option.getValue()} inside this
     * handler are ordinary calls to the unmodified method — the redirect only rewrites
     * call sites within {@code update}.
     */
    @Redirect(
        method = "update",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/client/option/SimpleOption;getValue()Ljava/lang/Object;")
    )
    private Object destinyrenderer$boostGamma(SimpleOption<?> option) {
        RendererConfig cfg = RendererConfig.get();
        if (!cfg.fullbright) return option.getValue();

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.options == null) return option.getValue();

        if ((Object) option != mc.options.getGamma()) return option.getValue();

        // Values above 1.0 are accepted by the lightmap shader even though the slider clamps.
        return Math.max((Double) option.getValue(), cfg.fullbrightLevel);
    }
}
