package destiny.renderer.mixin;

import destiny.renderer.config.RendererConfig;
import net.minecraft.client.gui.hud.InGameOverlayRenderer;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.random.Random;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Implements the screen overlay toggles from the Details page.
 *
 * <p>These are mainly visibility rather than performance settings. The fire overlay in
 * particular blocks a large portion of the screen while burning, and being able to switch
 * it off is a common competitive request.
 */
@Mixin(InGameOverlayRenderer.class)
public abstract class OverlayRendererMixin {

    @Inject(method = "renderFireOverlay", at = @At("HEAD"), cancellable = true)
    private static void destinyrenderer$skipFire(CallbackInfo ci) {
        if (!RendererConfig.get().enableFireOverlay) {
            ci.cancel();
        }
    }

    @Inject(method = "renderUnderwaterOverlay", at = @At("HEAD"), cancellable = true)
    private static void destinyrenderer$skipUnderwater(CallbackInfo ci) {
        if (!RendererConfig.get().enableWaterOverlay) {
            ci.cancel();
        }
    }

    /**
     * Covers the in-wall overlay, which renders the carved-pumpkin screen border
     * (and historically the powder-snow fill on older mappings). The powder-snow
     * screen overlay lives in {@code InGameHud.renderOverlay} keyed by
     * {@code POWDER_SNOW_OUTLINE} and is gated separately in
     * {@link ScreenEffectMixin}, so this hook must only honour the pumpkin toggle.
     */
    @Inject(method = "renderInWallOverlay", at = @At("HEAD"), cancellable = true)
    private static void destinyrenderer$skipInWall(CallbackInfo ci) {
        if (!RendererConfig.get().enablePumpkinOverlay) {
            ci.cancel();
        }
    }

    /**
     * The totem pop is the floating totem icon that rises up the middle of the screen
     * when a totem activates. It is started by {@code setFloatingItem}. Skipping it for
     * totem stacks removes the animation entirely while leaving other uses of the
     * floating-item effect (none in vanilla today) untouched.
     */
    @Inject(method = "setFloatingItem", at = @At("HEAD"), cancellable = true)
    private void destinyrenderer$skipTotem(ItemStack stack, Random random, CallbackInfo ci) {
        if (!RendererConfig.get().enableTotemAnim && stack.isOf(Items.TOTEM_OF_UNDYING)) {
            ci.cancel();
        }
    }
}
