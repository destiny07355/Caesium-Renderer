package destiny.renderer.mixin;

import destiny.renderer.config.RendererConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.InactivityFpsLimiter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Prevents unintended 30 FPS drops during gameplay while respecting user inactivity settings.
 */
@Mixin(InactivityFpsLimiter.class)
public abstract class InactivityFpsMixin {

    @Inject(method = "update", at = @At("RETURN"), cancellable = true)
    private void destinyrenderer$applyNeverOption(CallbackInfoReturnable<Integer> cir) {
        if (!destiny.renderer.compat.WorkAllotment.isOwnedByUs(
                destiny.renderer.compat.Capability.FRAME_THROTTLE)) {
            return;
        }

        RendererConfig cfg = RendererConfig.get();
        MinecraftClient client = MinecraftClient.getInstance();

        // Main menu cap
        if (client != null && client.world == null) {
            if (cfg.mainMenuFpsLimit > 0) {
                cir.setReturnValue(cfg.mainMenuFpsLimit);
                return;
            }
        }

        // When actively in-game and window is focused, ALWAYS run at configured max FPS (no random 30 FPS drops)
        if (client != null && client.world != null && client.isWindowFocused()) {
            if (client.options != null) {
                cir.setReturnValue(client.options.getMaxFps().getValue());
                return;
            }
        }

        if (cfg.unfocusedFpsLimit != 0) {
            int vanillaLimit = cir.getReturnValue();
            MinecraftClient mc = MinecraftClient.getInstance();
            int normalMax = (mc != null && mc.options != null)
                ? mc.options.getMaxFps().getValue() : 260;
            if (vanillaLimit < normalMax) {
                cir.setReturnValue(cfg.unfocusedFpsLimit);
            }
            return;
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.options != null) {
            cir.setReturnValue(mc.options.getMaxFps().getValue());
        }
    }
}
