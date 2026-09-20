package destiny.renderer.mixin;

import destiny.renderer.render.CaesiumSpriteAnimator;
import destiny.renderer.render.SpriteAnimationController;
import net.minecraft.client.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Freezes animated textures according to the Animations page toggles with zero lookup overhead.
 */
@Mixin(SpriteContents.Animator.class)
public abstract class SpriteAnimatorTickMixin implements CaesiumSpriteAnimator {

    @Unique
    private int caesium$category = 0;

    @Unique
    private boolean caesium$isBlock = false;

    @Override
    public int caesium$getCategory() {
        return caesium$category;
    }

    @Override
    public void caesium$setCategory(int category) {
        this.caesium$category = category;
    }

    @Override
    public boolean caesium$isBlock() {
        return caesium$isBlock;
    }

    @Override
    public void caesium$setIsBlock(boolean isBlock) {
        this.caesium$isBlock = isBlock;
    }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void caesium$skipWhenFrozen(CallbackInfo ci) {
        if (SpriteAnimationController.shouldSkipFast((CaesiumSpriteAnimator) this)) {
            ci.cancel();
        }
    }
}
