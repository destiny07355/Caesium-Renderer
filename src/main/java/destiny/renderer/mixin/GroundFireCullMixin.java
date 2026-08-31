package destiny.renderer.mixin;

import destiny.renderer.config.RendererConfig;
import net.minecraft.block.AbstractFireBlock;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.render.model.BlockModelPart;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockRenderView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Limits how far away ground fire blocks are drawn.
 *
 * <h2>Why fire is disproportionately expensive</h2>
 * A fire block is not a cube. It is several intersecting alpha-blended quads carrying an
 * animated texture, and it is emissive so it never benefits from face culling against its
 * neighbours. After a large explosion or in a burning forest there can be hundreds of them
 * overlapping in the same view, each one blending over everything behind it. On integrated
 * graphics that is a fill-rate wall, and it is the usual cause of frame rate collapsing
 * when the ground catches fire.
 *
 * <h2>The fix</h2>
 * Skip fire geometry past a configurable radius. Fire close enough to matter for gameplay
 * still renders; the distant wall of flame that was eating the frame budget does not.
 */
@Mixin(BlockRenderManager.class)
public abstract class GroundFireCullMixin {

    @Inject(method = "renderBlock", at = @At("HEAD"), cancellable = true)
    private void destinyrenderer$cullDistantFire(BlockState state,
                                                 BlockPos pos,
                                                 BlockRenderView world,
                                                 MatrixStack matrices,
                                                 VertexConsumer vertexConsumer,
                                                 boolean cull,
                                                 List<BlockModelPart> parts,
                                                 CallbackInfo ci) {
        if (!(state.getBlock() instanceof AbstractFireBlock)) return;

        RendererConfig cfg = RendererConfig.get();

        if (!cfg.renderGroundFire) {
            ci.cancel();
            return;
        }

        int limit = cfg.groundFireRenderDistance;
        if (limit <= 0) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null) return;

        double dx = pos.getX() + 0.5 - mc.player.getX();
        double dy = pos.getY() + 0.5 - mc.player.getY();
        double dz = pos.getZ() + 0.5 - mc.player.getZ();

        if (dx * dx + dy * dy + dz * dz > (double) limit * limit) {
            ci.cancel();
        }
    }
}
