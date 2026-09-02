package destiny.renderer.gui.components;

import destiny.renderer.gui.CaesiumFont;
import destiny.renderer.gui.anim.GuiAnimationHelper;
import destiny.renderer.gui.anim.GuiAnimator;
import destiny.renderer.gui.options.Option;
import destiny.renderer.gui.render.GuiRenderer;
import destiny.renderer.gui.theme.CaesiumTheme;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.OrderedText;

import java.util.List;

/**
 * Animated right-side detail panel with smooth pop-down entry animation.
 */
public final class DetailPanel {

    public static int EXPANDED_W = 180;
    private static final int COLLAPSED_W = 0;
    private static final int PAD = 12;
    private static final int LINE_H = 10;

    private final GuiAnimator anim = new GuiAnimator();
    private Option<?> displayedOption = null;
    private Option<?> targetOption    = null;

    private float animWidth = 0f;
    private float popProg   = 0f;

    private int screenW, screenH, panelY, panelH;

    public void setHovered(Option<?> option) {
        if (this.targetOption != option) {
            this.popProg = 0f;
        }
        this.targetOption = option;
    }

    public void tick(float deltaSec) {
        boolean showing = targetOption != null;

        if (targetOption != null && targetOption != displayedOption) {
            displayedOption = targetOption;
        }

        if (!showing && animWidth < 0.5f) {
            displayedOption = null;
        }

        float target = showing ? EXPANDED_W : COLLAPSED_W;
        float speed  = showing ? CaesiumTheme.ANIM_SPEED_TOOLTIP_IN : CaesiumTheme.ANIM_SPEED_TOOLTIP_OUT;

        animWidth = GuiAnimationHelper.smoothDamp(animWidth, target, speed, deltaSec);
        if (Math.abs(animWidth - target) < 0.3f) animWidth = target;

        if (showing) {
            popProg = Math.min(1f, popProg + deltaSec * 12f);
        } else {
            popProg = 0f;
        }
    }

    public void setBounds(int screenW, int screenH, int panelY, int panelH) {
        this.screenW = screenW;
        this.screenH = screenH;
        this.panelY  = panelY;
        this.panelH  = panelH;
    }

    public int currentWidth() {
        return (int) animWidth;
    }

    public boolean isInvisible() {
        return animWidth < 1f;
    }

    public void render(DrawContext ctx, TextRenderer tr) {
        if (isInvisible() || displayedOption == null) return;

        float alpha01 = Math.min(1f, animWidth / (float) EXPANDED_W);
        int panelW = (int) animWidth;
        int panelX = screenW - panelW;

        float inv = 1f - popProg;
        int slideY = (int)((1f - (1f - inv * inv * inv)) * 12f);
        int effectivePanelY = panelY + slideY;

        // Draw frosted panel
        GuiRenderer.animatedFrostedPanel(ctx, panelX, effectivePanelY, panelW, panelH,
            CaesiumTheme.bgSidebar(), CaesiumTheme.borderStrong(),
            CaesiumTheme.RADIUS_PANEL, false, alpha01);

        // Accent left border line
        if (alpha01 > 0.3f) {
            int lineAlpha = (int)(0xFF * Math.min(1f, (alpha01 - 0.3f) / 0.7f));
            ctx.fill(panelX, effectivePanelY + 8, panelX + 2, effectivePanelY + panelH - 8,
                (lineAlpha << 24) | CaesiumTheme.ACCENT_RGB);
        }

        // Only render text content when panel is wide enough
        if (panelW >= 40 && alpha01 >= 0.4f) {
            int innerX   = panelX + PAD;
            int innerW   = panelW - PAD * 2;
            int textAlpha = (int)(0xFF * Math.min(1f, (alpha01 - 0.4f) / 0.6f));

            if (textAlpha >= 10) {
                int curY = effectivePanelY + PAD;
                Option<?> opt = displayedOption;

                // ---- Option name ----
                String name = opt.getName().getString();
                String trimmedName = tr.getWidth(name) > innerW
                    ? tr.trimToWidth(name, innerW - tr.getWidth("...")) + "..."
                    : name;
                int titleColor = (textAlpha << 24) | (CaesiumTheme.TEXT_PRIMARY & 0x00FFFFFF);
                ctx.drawText(tr, CaesiumFont.text(trimmedName), innerX, curY, titleColor, false);
                curY += 12;

                // ---- Impact badge ----
                String impactLabel = opt.getImpact().label().toUpperCase();
                int impactColor = (textAlpha << 24) | (opt.getImpact().color() & 0x00FFFFFF);
                int impactW = tr.getWidth(impactLabel) + 8;
                GuiRenderer.filledRoundedBox(ctx, innerX, curY, impactW, 11,
                    CaesiumTheme.RADIUS_BADGE,
                    (int)((textAlpha * 0.3f) * 0x100000) | (opt.getImpact().color() & 0x00FFFFFF));
                ctx.drawText(tr, CaesiumFont.text(impactLabel), innerX + 4, curY + 2, impactColor, false);
                curY += 16;

                // ---- Divider ----
                int divAlpha = (int)(0xFF * Math.min(1f, (alpha01 - 0.5f) / 0.5f));
                ctx.fill(innerX, curY, innerX + innerW, curY + 1,
                    (divAlpha << 24) | CaesiumTheme.ACCENT_RGB);
                curY += 6;

                // ---- Description ----
                String desc = opt.getDetailedExplanation();
                if (desc != null && !desc.isEmpty()) {
                    List<OrderedText> lines = tr.wrapLines(CaesiumFont.text(desc), innerW);
                    int secColor = (textAlpha << 24) | (CaesiumTheme.TEXT_SECONDARY & 0x00FFFFFF);
                    for (OrderedText line : lines) {
                        if (curY + LINE_H > effectivePanelY + panelH - PAD) break;
                        ctx.drawText(tr, line, innerX, curY, secColor, false);
                        curY += LINE_H;
                    }
                    curY += 4;
                }

                // ---- Default rationale ----
                String reason = opt.getDefaultReason();
                if (reason != null && !reason.isEmpty() && curY + LINE_H < effectivePanelY + panelH - PAD) {
                    ctx.fill(innerX, curY, innerX + innerW, curY + 1,
                        (int)(divAlpha * 0.4f) << 24 | (CaesiumTheme.BORDER_STRONG_COLOR & 0x00FFFFFF));
                    curY += 6;
                    List<OrderedText> reasonLines = tr.wrapLines(CaesiumFont.text(reason), innerW);
                    int mutedColor = (textAlpha << 24) | (CaesiumTheme.TEXT_DISABLED & 0x00FFFFFF);
                    for (OrderedText line : reasonLines) {
                        if (curY + LINE_H > effectivePanelY + panelH - PAD) break;
                        ctx.drawText(tr, line, innerX, curY, mutedColor, false);
                        curY += LINE_H;
                    }
                }

                // ---- Current value (bottom of panel) ----
                String val = opt.getFormattedValue();
                if (val != null && !val.isEmpty()) {
                    int valY = effectivePanelY + panelH - PAD - 8;
                    ctx.fill(innerX, valY - 5, innerX + innerW, valY - 4,
                        (divAlpha << 24) | (CaesiumTheme.BORDER_STRONG_COLOR & 0x00FFFFFF));
                    String valLabel = "Value: " + val;
                    String trimmedVal = tr.getWidth(valLabel) > innerW
                        ? tr.trimToWidth(valLabel, innerW) : valLabel;
                    int accentColor = (textAlpha << 24) | CaesiumTheme.ACCENT_RGB;
                    ctx.drawText(tr, CaesiumFont.text(trimmedVal), innerX, valY, accentColor, false);
                }
            }
        }
    }
}
