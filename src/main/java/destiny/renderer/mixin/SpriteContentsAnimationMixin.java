package destiny.renderer.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import destiny.renderer.render.SpriteAnimationController;
import net.minecraft.client.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Registers every freshly created sprite animator with
 * {@link SpriteAnimationController} so the per-frame tick policy can identify which
 * texture each animator belongs to.
 */
@Mixin(SpriteContents.class)
public abstract class SpriteContentsAnimationMixin {

    @Inject(method = "createAnimator", at = @At("RETURN"))
    private void caesium$registerAnimator(GpuBufferSlice slice, int frameCount,
                                          CallbackInfoReturnable<SpriteContents.Animator> cir) {
        SpriteAnimationController.register((SpriteContents) (Object) this, cir.getReturnValue());
    }
}
