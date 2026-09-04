package destiny.renderer.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import destiny.renderer.render.CaesiumSpriteAnimator;
import destiny.renderer.render.SpriteAnimationController;
import net.minecraft.client.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Classifies freshly created sprite animators directly on the instance, bypassing maps.
 */
@Mixin(SpriteContents.class)
public abstract class SpriteContentsAnimationMixin {

    @Inject(method = "createAnimator", at = @At("RETURN"))
    private void caesium$registerAnimator(GpuBufferSlice slice, int frameCount,
                                          CallbackInfoReturnable<SpriteContents.Animator> cir) {
        SpriteContents.Animator animator = cir.getReturnValue();
        if (animator instanceof CaesiumSpriteAnimator csa) {
            SpriteAnimationController.classify((SpriteContents) (Object) this, csa);
        }
    }
}
