package destiny.renderer.mixin;

import destiny.renderer.config.RendererConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.InactivityFpsLimiter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Implements "Reduce FPS When Unfocused", including a Never option vanilla does not offer.
 *
 * <p>Vanilla exposes only Minimized and AFK, and additionally throttles hard whenever a
 * GUI is open or input has been idle for a while. That is unhelpful when recording,
 * streaming, benchmarking, or running on a second monitor. Never keeps full speed.
 *
 * <p>{@code update()} is the method that actually returns the active frame cap, so this is
 * the correct place to override rather than the {@code getCurrentFps()} readout.
 */
@Mixin(InactivityFpsLimiter.class)
public abstract class InactivityFpsMixin {

    @Inject(method = "update", at = @At("RETURN"), cancellable = true)
    private void destinyrenderer$applyNeverOption(CallbackInfoReturnable<Integer> cir) {
        // Dynamic FPS implements this far more thoroughly than we do — it handles battery
        // state, per-situation targets and volume fading. If it owns frame throttling,
        // stand aside completely rather than fighting it for control of the cap.
        if (!destiny.renderer.compat.WorkAllotment.isOwnedByUs(
                destiny.renderer.compat.Capability.FRAME_THROTTLE)) {
            return;
        }

        RendererConfig cfg = RendererConfig.get();
        MinecraftClient client = MinecraftClient.getInstance();

        // --- Main Menu / Home Screen Cap Only ---
        if (client != null && client.world == null) {
            // Main menu, server list, world select, loading screens.
            if (cfg.mainMenuFpsLimit > 0) {
                cir.setReturnValue(cfg.mainMenuFpsLimit);
                return;
            }
        }

        if (cfg.unfocusedFpsLimit != 0) {
            // A concrete cap was chosen; only apply it when we are actually being throttled.
            int vanillaLimit = cir.getReturnValue();
            MinecraftClient mc = MinecraftClient.getInstance();
            int normalMax = (mc != null && mc.options != null)
                ? mc.options.getMaxFps().getValue() : 260;
            if (vanillaLimit < normalMax) {
                cir.setReturnValue(cfg.unfocusedFpsLimit);
            }
            return;
        }

        // Never: always report the user's configured maximum, so no inactivity or GUI
        // condition can throttle the frame rate.
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.options != null) {
            cir.setReturnValue(mc.options.getMaxFps().getValue());
        }
    }
}
