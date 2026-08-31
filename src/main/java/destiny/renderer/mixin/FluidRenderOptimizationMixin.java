package destiny.renderer.mixin;

import destiny.renderer.compat.Capability;
import destiny.renderer.compat.WorkAllotment;
import destiny.renderer.config.RendererConfig;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.block.FluidRenderer;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Removes invisible fluid geometry.
 *
 * <h2>The problem</h2>
 * Vanilla decides fluid face visibility per side, but still emits a large number of faces
 * between neighbouring fluid blocks of the same type. Inside a body of water every one of
 * those interior faces is completely hidden by the water around it, yet each is a
 * translucent quad that must be sorted and blended. An ocean is therefore mostly overdraw.
 *
 * <h2>The fix</h2>
 * When both sides of a face are the same fluid at full height, the face cannot be seen and
 * is culled. Faces at the surface, at the edge of a body, and against air or transparent
 * blocks are all preserved, so the fluid looks identical.
 */
@Mixin(FluidRenderer.class)
public abstract class FluidRenderOptimizationMixin {

    @Inject(method = "shouldRenderSide", at = @At("HEAD"), cancellable = true)
    private static void destinyrenderer$cullInteriorFluidFaces(
            FluidState state, BlockState blockState,
            Direction direction, FluidState neighbourFluid,
            CallbackInfoReturnable<Boolean> cir) {

        if (!RendererConfig.get().optimizeFluidRendering) return;

        // Stand down when a specialised block/fluid culler owns the job (MoreCulling,
        // BadOptimizations). Running our interior-face pass on top of theirs is wasted
        // work per fluid face.
        if (!WorkAllotment.isOwnedByUs(Capability.BLOCK_CULLING)) return;

        // Only consider faces where both sides are the same fluid type.
        if (neighbourFluid.isEmpty() || state.isEmpty()) return;
        if (!neighbourFluid.getFluid().matchesType(state.getFluid())) return;

        // A source block against another source block of the same fluid has no visible
        // boundary. Flowing blocks are left alone because their surface heights differ
        // and a real edge can be visible between them.
        if (state.isStill() && neighbourFluid.isStill()) {
            cir.setReturnValue(false);
            return;
        }

        // Same-height flowing fluid also has no visible seam.
        if (state.getHeight() >= 1.0f && neighbourFluid.getHeight() >= 1.0f) {
            cir.setReturnValue(false);
        }
    }
}
