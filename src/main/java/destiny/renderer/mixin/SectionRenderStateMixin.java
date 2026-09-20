package destiny.renderer.mixin;
    
import caesium.integration.CaesiumIntegration;
import net.minecraft.client.gl.GpuSampler;
import net.minecraft.client.render.BlockRenderLayerGroup;
import net.minecraft.client.render.SectionRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Exact vanilla terrain submission seam; cancellation is success-gated by Caesium. */
@Mixin(SectionRenderState.class)
public abstract class SectionRenderStateMixin {
    @Inject(method = "renderSection", at = @At("HEAD"), cancellable = true)
    private void caesium$renderTerrainGroup(BlockRenderLayerGroup group,
                                             GpuSampler terrainSampler,
                                             CallbackInfo ci) {
        if (CaesiumIntegration.renderTerrainGroup(
                (SectionRenderState) (Object) this, group, terrainSampler)) {
            ci.cancel();
        }
    }
}
