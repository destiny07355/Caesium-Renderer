package destiny.renderer.mixin;

import destiny.renderer.config.RendererConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.fog.DarknessEffectFogModifier;
import net.minecraft.client.render.fog.FogData;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * True-off gate for the Warden's darkness effect.
 *
 * <p>The darkness pulse has two visual components in 1.21.11: a lightmap
 * darkening (already scaled by the Darkness Pulsing accessibility slider, so it
 * vanishes at 0%) and a fog modifier ({@link DarknessEffectFogModifier}) whose
 * {@code applyStartEndModifier} reads the effect fade factor directly and is
 * NOT scaled by the slider. Canning the fog modifier when the slider is at 0%
 * or the user disabled the effect outright makes 0% behave as "off" rather than
 * "100% opacity".
 */
@Mixin(DarknessEffectFogModifier.class)
public abstract class DarknessEffectFogMixin {

    @Inject(method = "applyStartEndModifier", at = @At("HEAD"), cancellable = true)
    private void destinyrenderer$skipDarknessFog(FogData fogData, Camera camera, ClientWorld world,
                                                  float viewDistance, RenderTickCounter tickCounter,
                                                  CallbackInfo ci) {
        RendererConfig cfg = RendererConfig.get();
        if (!cfg.enableDarknessEffect) {
            ci.cancel();
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.options == null) return;
        GameOptions o = mc.options;
        if (o.getDarknessEffectScale().getValue() <= 0.0) {
            ci.cancel();
        }
    }
}
