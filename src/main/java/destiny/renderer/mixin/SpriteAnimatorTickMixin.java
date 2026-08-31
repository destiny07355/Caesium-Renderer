package destiny.renderer.mixin;

import destiny.renderer.render.SpriteAnimationController;
import net.minecraft.client.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Freezes animated textures according to the Animations page toggles.
 *
 * <p>Animated sprites are advanced once per frame by {@code Animator.tick()}. Cancelling
 * it pins the current frame and marks the animator clean, so no upload happens and the
 * texture stays static — the same visual and cost as having never animated it.
 */
@Mixin(SpriteContents.Animator.class)
public abstract class SpriteAnimatorTickMixin {

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void caesium$skipWhenFrozen(CallbackInfo ci) {
        if (SpriteAnimationController.shouldSkip((SpriteContents.Animator) (Object) this)) {
            ci.cancel();
        }
    }
}
