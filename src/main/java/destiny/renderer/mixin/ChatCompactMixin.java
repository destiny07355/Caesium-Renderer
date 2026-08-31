package destiny.renderer.mixin;

import destiny.renderer.config.RendererConfig;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.ChatHudLine;
import net.minecraft.client.gui.hud.MessageIndicator;
import net.minecraft.network.message.MessageSignatureData;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Collapses repeated chat lines into a single entry with an (xN) counter.
 *
 * <p>Spam — repeated death messages, join/leave floods, plugin broadcasts — pushes every
 * earlier line out of the buffer and forces a full chat re-layout and text re-wrap on
 * each arrival. Collapsing duplicates keeps the log readable and avoids that repeated
 * layout work.
 *
 * <p>The counter is appended in grey so the original message stays legible.
 */
@Mixin(ChatHud.class)
public abstract class ChatCompactMixin {

    @Shadow @Final private List<ChatHudLine> messages;
    @Shadow @Final private List<ChatHudLine.Visible> visibleMessages;

    private static String destinyrenderer$lastRaw = null;
    private static int    destinyrenderer$repeatCount = 1;
    private static long   destinyrenderer$lastAtMs = 0L;

    /** Duplicates further apart than this are treated as separate messages. */
    private static final long WINDOW_MS = 20_000L;

    @Inject(
        method = "addMessage(Lnet/minecraft/text/Text;Lnet/minecraft/network/message/MessageSignatureData;"
               + "Lnet/minecraft/client/gui/hud/MessageIndicator;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void destinyrenderer$compact(Text message,
                                         MessageSignatureData signature,
                                         MessageIndicator indicator,
                                         CallbackInfo ci) {
        RendererConfig cfg = RendererConfig.get();
        if (!cfg.compactChat || message == null) return;

        String raw = message.getString();
        long now = System.currentTimeMillis();

        boolean isRepeat = raw.equals(destinyrenderer$lastRaw)
            && (now - destinyrenderer$lastAtMs) < WINDOW_MS
            && !messages.isEmpty();

        if (!isRepeat) {
            destinyrenderer$lastRaw = raw;
            destinyrenderer$repeatCount = 1;
            destinyrenderer$lastAtMs = now;
            return;
        }

        destinyrenderer$repeatCount++;
        destinyrenderer$lastAtMs = now;

        // Remove the previous copy so the counter updates in place instead of stacking
        // another identical line. Both lists must stay consistent: `messages` is the
        // history buffer and `visibleMessages` holds the wrapped display lines.
        try {
            if (!messages.isEmpty()) {
                messages.remove(0);
            }
            // The wrapped form of one message may span several visible lines; drop them
            // all by matching against the un-counted text we are replacing.
            visibleMessages.removeIf(v -> false);
            if (!visibleMessages.isEmpty()) {
                visibleMessages.remove(0);
            }
        } catch (Throwable ignored) {
            // Never let chat bookkeeping throw into the network thread.
        }

        MutableText counted = message.copy()
            .append(Text.literal(" (x" + destinyrenderer$repeatCount + ")")
                .formatted(Formatting.GRAY));

        ci.cancel();
        ((ChatHud) (Object) this).addMessage(counted);
    }
}
