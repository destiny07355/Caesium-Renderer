package destiny.renderer.mixin;

import destiny.renderer.config.RendererConfig;
import net.minecraft.client.toast.AdvancementToast;
import net.minecraft.client.toast.RecipeToast;
import net.minecraft.client.toast.Toast;
import net.minecraft.client.toast.ToastManager;
import net.minecraft.client.toast.TutorialToast;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Filters out toasts and popup notifications based on configuration.
 */
@Mixin(ToastManager.class)
public class ToastManagerMixin {

    @Inject(method = "add", at = @At("HEAD"), cancellable = true)
    private void destinyrenderer$onAddToast(Toast toast, CallbackInfo ci) {
        RendererConfig cfg = RendererConfig.get();
        if (cfg == null) return;

        if (!cfg.enableAllToasts) {
            ci.cancel();
            return;
        }

        if (!cfg.enableAdvancementToasts && (toast instanceof AdvancementToast || toast instanceof RecipeToast)) {
            ci.cancel();
            return;
        }

        if (!cfg.enableTutorialToasts && toast instanceof TutorialToast) {
            ci.cancel();
        }
    }
}
