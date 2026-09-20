package destiny.renderer.mixin;

import destiny.renderer.config.RendererConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.command.BatchingRenderCommandQueue;
import net.minecraft.client.render.command.FireCommandRenderer;
import net.minecraft.client.texture.AtlasManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Controls the fire overlay drawn on burning entities.
 *
 * <p>Ground-fire blocks remain vanilla-rendered. This mixin only exposes the default-on
 * entity-fire overlay override.
 */
@Mixin(FireCommandRenderer.class)
public abstract class FireRenderOptimizationMixin {

    @Inject(
        method = "render(Lnet/minecraft/client/render/command/BatchingRenderCommandQueue;"
               + "Lnet/minecraft/client/render/VertexConsumerProvider$Immediate;"
               + "Lnet/minecraft/client/texture/AtlasManager;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void destinyrenderer$skipEntityFire(BatchingRenderCommandQueue queue,
                                                VertexConsumerProvider.Immediate vertexConsumers,
                                                AtlasManager atlasManager,
                                                CallbackInfo ci) {
        if (!RendererConfig.get().enableEntityFireOverlay) {
            ci.cancel();
        }
    }
}
