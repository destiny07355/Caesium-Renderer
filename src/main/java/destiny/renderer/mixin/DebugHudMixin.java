package destiny.renderer.mixin;

import destiny.renderer.config.RendererConfig;
import destiny.renderer.hud.PerformanceOverlay;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.DebugHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Surface the Caesium percentile readouts (p50 / p98 / p99.5 + worst + avg) right under
 * the vanilla {@code FPS} line in the F3 debug overlay, mirroring how Sodium surfaces its
 * own {@code ms/f} summary. This is the user-visible half of the frame-time floor work
 * done in {@link PerformanceOverlay}; without an on-screen readout the only way to verify
 * the wins is a profiler run, which is exactly what the readout is meant to replace.
 *
 * <h2>Injection strategy</h2>
 * Vanilla's {@link DebugHud#render} builds two lists and passes each one into the private
 * {@code drawText(DrawContext, List<String>, boolean)} helper. The boolean is
 * {@code true} for the LEFT-side list (which is the one that holds the {@code FPS T:...}
 * line emitted by {@code FpsDebugHudEntry}), {@code false} for the right-side list.
 *
 * <p>Injecting at the HEAD of {@code drawText} lets us mutate the list argument directly,
 * before the loop reads it, with no fragile local-variable indexing. We add our lines
 * only on the {@code true} (left) target so the readout sits directly below the vanilla
 * FPS line, the same position Sodium's readout occupies.
 *
 * <h2>Cost</h2>
 * One method-call per render of the F3 overlay (so 60–120 Hz while F3 is open; not at all
 * while F3 is hidden). The append itself is an {@link ArrayList#add} of two cached
 * pre-formatted strings — there is no allocation cost per appended line beyond the
 * strings, which are already produced by {@link PerformanceOverlay#percentileLines()} on
 * its 4×/s recompute clock. So this mixin has no meaningful per-frame cost whatsoever.
 *
 * <h2>Toggle</h2>
 * Honours {@link RendererConfig#showExtendedFpsInF3}. Defaults ON — the whole reason the
 * in-tree readout exists is to give users a way to see the wins that would otherwise
 * only show in a profiler. Turning it off restores stock vanilla F3 behaviour exactly.
 */
@Mixin(DebugHud.class)
public abstract class DebugHudMixin {

    @Inject(
        method = "drawText(Lnet/minecraft/client/gui/DrawContext;Ljava/util/List;Z)V",
        at = @At("HEAD")
    )
    private void caesium$appendPercentiles(DrawContext context,
                                          List<String> lines,
                                          boolean leftSide,
                                          CallbackInfo ci) {
        if (!leftSide) return;
        if (!RendererConfig.get().showExtendedFpsInF3) return;

        List<String> extra = PerformanceOverlay.percentileLines();
        if (extra.isEmpty()) return;
        // The list is an ArrayList built by vanilla — MutableList, safe to append to
        // here, and the iteration loop in drawText runs AFTER this inject returns.
        lines.addAll(extra);
    }
}
