package destiny.renderer.mixin;

import destiny.renderer.hud.PerformanceOverlay;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the performance overlay on top of the vanilla HUD.
 *
 * <p>The previous version rendered a hardcoded placeholder banner that showed no actual
 * data. This delegates to {@link PerformanceOverlay}, which reads the real settings and
 * tracks genuine frame timing.
 */
@Mixin(InGameHud.class)
public abstract class InGameHudMixin {

    @Shadow public abstract TextRenderer getTextRenderer();

    @Inject(method = "render", at = @At("TAIL"))
    private void destinyrenderer$renderOverlay(DrawContext context,
                                               RenderTickCounter tickCounter,
                                               CallbackInfo ci) {
        // Sampled unconditionally so the readout is already accurate the moment the
        // overlay is switched on, rather than needing a warm-up period.
        PerformanceOverlay.recordFrame();

        // The deferred-rebuild tick moved to {@link WorldRendererMixin} so it runs earlier
        // in the frame (still on the render thread) — see PROGRESS.md 1.10.0 / B1. That
        // keeps the rebuild burst away from the present-and-vsync tail of the frame where
        // it would have made the worst frames even worse.

        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
        if (mc != null && mc.getDebugHud() != null && mc.getDebugHud().shouldShowDebugHud()) {
            // Stay out of the way of F3, which already shows this information.
            return;
        }

        PerformanceOverlay.render(context, this.getTextRenderer());
    }
}
