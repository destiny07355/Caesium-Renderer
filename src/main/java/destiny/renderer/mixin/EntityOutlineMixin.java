package destiny.renderer.mixin;

import destiny.renderer.config.RendererConfig;
import net.minecraft.client.render.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Disables the entity outline (glow) pass.
 *
 * <p>Outlines require rendering affected entities a second time into a dedicated
 * framebuffer, then running a post-process pass to extract and blit the edges. On
 * integrated graphics the extra framebuffer alone is meaningful, and the effect only
 * applies to spectral arrows, glowing mobs and spectator targets.
 *
 * <p>{@code canDrawEntityOutlines} is the correct gate: returning false skips both the
 * second geometry pass and the post-process, rather than doing the work and discarding it.
 */
@Mixin(WorldRenderer.class)
public abstract class EntityOutlineMixin {

    @Inject(method = "canDrawEntityOutlines", at = @At("HEAD"), cancellable = true)
    private void destinyrenderer$skipOutlines(CallbackInfoReturnable<Boolean> cir) {
        if (!RendererConfig.get().enableEntityOutlines) {
            cir.setReturnValue(false);
        }
    }
}
