package destiny.renderer.mixin;

import destiny.renderer.config.RendererConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.option.GameOptions;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Screen-effect toggles wired to the Details page.
 *
 * <p>Vanilla renders the static nausea overlay at <em>full</em> strength when the
 * Distortion Effects accessibility slider is at 0% — it scales the overlay by
 * {@code (1 - scale)}, so 0% becomes 100%. That is the opposite of "off", so we
 * cancel the overlay entirely whenever the slider is at 0 OR the user disabled
 * the effect outright. The same applies to the nether-portal warp overlay.
 *
 * <p>The powder-snow screen overlay lives in {@code InGameHud.renderOverlay} keyed
 * by {@link #POWDER_SNOW_OUTLINE}, separate from the carved-pumpkin in-wall
 * overlay handled in {@link OverlayRendererMixin}. Gating it here keeps the two
 * toggles independent of each other.
 */
@Mixin(InGameHud.class)
public abstract class ScreenEffectMixin {

    @Shadow @Final private static Identifier POWDER_SNOW_OUTLINE;

    private static boolean destinyrenderer$distortionOff() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.options == null) return false;
        GameOptions o = mc.options;
        return o.getDistortionEffectScale().getValue() <= 0.0;
    }

    @Inject(method = "renderNauseaOverlay", at = @At("HEAD"), cancellable = true)
    private void destinyrenderer$skipNausea(DrawContext context, float strength, CallbackInfo ci) {
        RendererConfig cfg = RendererConfig.get();
        if (!cfg.enableNauseaEffect || destinyrenderer$distortionOff()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderPortalOverlay", at = @At("HEAD"), cancellable = true)
    private void destinyrenderer$skipPortal(DrawContext context, float strength, CallbackInfo ci) {
        RendererConfig cfg = RendererConfig.get();
        if (!cfg.enableNauseaEffect || destinyrenderer$distortionOff()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderOverlay", at = @At("HEAD"), cancellable = true)
    private void destinyrenderer$skipPowderSnow(DrawContext context, Identifier id, float opacity, CallbackInfo ci) {
        if (id == POWDER_SNOW_OUTLINE && !RendererConfig.get().enablePowderSnowOverlay) {
            ci.cancel();
        }
    }
}
