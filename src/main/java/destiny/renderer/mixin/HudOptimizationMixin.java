package destiny.renderer.mixin;

import destiny.renderer.config.RendererConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Optional skips for the most expensive vanilla HUD elements.
 *
 * <p>Each inject cancels the whole target method at HEAD when its toggle is off,
 * which is the cheapest possible path — no iteration, no texture binds, no text
 * layout. Every toggle defaults to ON (vanilla behaviour) so other mods that
 * inject into the same methods — e.g. a potion-effects timer that draws extra
 * text into {@code renderStatusEffectOverlay} — continue to work unchanged
 * unless the user explicitly opts out of that element.
 */
@Mixin(InGameHud.class)
public abstract class HudOptimizationMixin {

    @Inject(method = "renderStatusEffectOverlay", at = @At("HEAD"), cancellable = true)
    private void destinyrenderer$skipStatusEffects(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        if (!RendererConfig.get().enableStatusEffectHud) {
            ci.cancel();
        }
    }

    @Inject(method = "renderHotbar", at = @At("HEAD"), cancellable = true)
    private void destinyrenderer$skipHotbar(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        if (!RendererConfig.get().enableHotbar) {
            ci.cancel();
        }
    }

    @Inject(method = "renderStatusBars", at = @At("HEAD"), cancellable = true)
    private void destinyrenderer$skipStatusBars(DrawContext context, CallbackInfo ci) {
        if (!RendererConfig.get().enableHealthBars) {
            ci.cancel();
        }
    }

    @Inject(method = "renderMountHealth", at = @At("HEAD"), cancellable = true)
    private void destinyrenderer$skipMountHealth(DrawContext context, CallbackInfo ci) {
        if (!RendererConfig.get().enableHealthBars) {
            ci.cancel();
        }
    }
}
