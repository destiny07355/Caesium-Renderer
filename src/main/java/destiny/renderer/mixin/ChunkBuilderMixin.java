package destiny.renderer.mixin;

import destiny.renderer.DestinyRenderer;
import destiny.renderer.chunk.MeshingJobSystem;
import destiny.renderer.compat.WorkAllotment;
import net.minecraft.client.render.chunk.ChunkBuilder;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkBuilder.BuiltChunk.class)
public abstract class ChunkBuilderMixin {

    @Shadow public abstract BlockPos getOrigin();

    @Inject(
        method = "scheduleRebuild(Z)V",
        at = @At("HEAD")
    )
    private void destinyrenderer$onScheduleRebuild(boolean important, CallbackInfo ci) {
        BlockPos origin = this.getOrigin();
        ChunkSectionPos pos = origin != null ? ChunkSectionPos.from(origin) : null;
        if (pos != null) {
            destiny.renderer.render.SpriteVisibilityTracker.invalidateSection(
                packSectionKey(pos.getSectionX(), pos.getSectionY(), pos.getSectionZ()));
        }

        if (!DestinyRenderer.isActive()) return;

        // ONLY extract or mesh if Caesium actually owns terrain rendering!
        // When Vanilla, Sodium, or Embeddium owns terrain, standing down prevents 
        // duplicate meshing pools from thrashing the CPU and tanking FPS.
        if (!WorkAllotment.ownsTerrain()) return;

        if (caesium.integration.CaesiumIntegration.started() && pos != null) {
            caesium.integration.CaesiumIntegration.extractSection(pos);
        }

        if (MeshingJobSystem.get() != null && pos != null) {
            MeshingJobSystem.get().submit(pos, null);
        }
    }

    private static long packSectionKey(int x, int y, int z) {
        return ((long)(x & 0x1FFFFF)) | (((long)(y & 0x1FFFFF)) << 21) | (((long)(z & 0x1FFFFF)) << 42);
    }
}