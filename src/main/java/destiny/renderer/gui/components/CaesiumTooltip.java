package destiny.renderer.gui.components;

import destiny.renderer.gui.CaesiumFont;
import destiny.renderer.gui.anim.GuiAnimationHelper;
import destiny.renderer.gui.options.Option;
import destiny.renderer.gui.render.GuiRenderer;
import destiny.renderer.gui.theme.CaesiumTheme;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.OrderedText;

import java.util.List;

/**
 * Legacy floating tooltip — kept for compatibility but superseded by {@link DetailPanel}.
 * Only used as a fallback; the main screen wires up DetailPanel instead.
 */
public final class CaesiumTooltip {

    private Option<?> lastOption  = null;
    private float     animProgress = 0.0f;

    public void update(Option<?> hovered, float deltaSec) {
        if (hovered != null) {
            lastOption   = hovered;
            animProgress = GuiAnimationHelper.smoothDamp(animProgress, 1.0f,
                CaesiumTheme.ANIM_SPEED_TOOLTIP_IN, deltaSec);
        } else {
            animProgress = GuiAnimationHelper.smoothDamp(animProgress, 0.0f,
                CaesiumTheme.ANIM_SPEED_TOOLTIP_OUT, deltaSec);
        }
    }

    public void render(DrawContext context, TextRenderer tr,
                       int screenWidth, int screenHeight,
                       int contentX, int contentY,
                       int mouseX, int mouseY) {
        if (animProgress < 0.02f || lastOption == null) return;

        Option<?> opt = lastOption;
        int cardW   = Math.min(CaesiumTheme.TOOLTIP_MAX_W, screenWidth - contentX - 16);
        int padding = CaesiumTheme.TOOLTIP_PADDING;
        int innerW  = cardW - padding * 2;

        String exp = opt.getDetailedExplanation();
        List<OrderedText> descLines   = tr.wrapLines(CaesiumFont.text(exp), innerW);
        String reason = opt.getDefaultReason();
        List<OrderedText> reasonLines = (reason == null || reason.isEmpty())
            ? List.of() : tr.wrapLines(CaesiumFont.text(reason), innerW);

        int headerH = 14;
        int descH   = descLines.size() * 9;
        int reasonH = reasonLines.isEmpty() ? 0 : reasonLines.size() * 9 + 6;
        int cardH   = padding * 2 + headerH + descH + reasonH;

        int cardX = mouseX + 12;
        int cardY = mouseY - cardH / 2;

        if (cardX + cardW > screenWidth - 8) cardX = mouseX - cardW - 8;
        if (cardY < contentY) cardY = contentY;
        if (cardY + cardH > screenHeight - CaesiumTheme.FOOTER_H - 4) {
            cardY = screenHeight - CaesiumTheme.FOOTER_H - cardH - 4;
        }

        int animAlpha = (int)(animProgress * 248.0f);
        int slideY    = (int)((1f - animProgress) * CaesiumTheme.TOOLTIP_SLIDE);

        // Frosted card
        GuiRenderer.animatedFrostedPanel(context, cardX, cardY + slideY, cardW, cardH,
            CaesiumTheme.bgElevated(), CaesiumTheme.borderLight(),
            CaesiumTheme.RADIUS_TOOLTIP, false, animProgress);

        // Top accent rule
        context.fill(cardX + 1, cardY + slideY + 1,
            cardX + cardW - 1, cardY + slideY + 2,
            (animAlpha << 24) | CaesiumTheme.ACCENT_RGB);

        int curY = cardY + slideY + padding;

        // Title + Impact
        context.drawText(tr, CaesiumFont.withFont(opt.getName()),
            cardX + padding, curY,
            (animAlpha << 24) | (CaesiumTheme.TEXT_PRIMARY & 0x00FFFFFF), false);

        String impactStr = opt.getImpact().label().toUpperCase();
        int impactW = tr.getWidth(impactStr);
        context.drawText(tr, CaesiumFont.text(impactStr),
            cardX + cardW - padding - impactW - 2, curY,
            (animAlpha << 24) | (opt.getImpact().color() & 0x00FFFFFF), false);
        curY += headerH;

        // Description
        for (OrderedText line : descLines) {
            context.drawText(tr, line, cardX + padding, curY,
                (animAlpha << 24) | (CaesiumTheme.TEXT_SECONDARY & 0x00FFFFFF), false);
            curY += 9;
        }

        // Rationale
        if (!reasonLines.isEmpty()) {
            curY += 2;
            for (OrderedText line : reasonLines) {
                context.drawText(tr, line, cardX + padding, curY,
                    (animAlpha << 24) | (CaesiumTheme.TEXT_DISABLED & 0x00FFFFFF), false);
                curY += 9;
            }
        }
    }
}
