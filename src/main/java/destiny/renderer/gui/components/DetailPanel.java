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
 * Animated right-side detail panel.
 *
 * <p>Slides in from the right edge of the screen when an option row is hovered.
 * The content area notifies this panel of the hovered option each frame,
 * and this panel reports its current animated width back so the content
 * area can shrink its own layout accordingly.
 *
 * <h2>Layout contract</h2>
 * <pre>
 *   [ Sidebar | Content Area (shrinks) | Detail Panel (slides in) ]
 * </pre>
 *
 * The content area shrinks by {@link #currentWidth()} pixels.
 * The detail panel is positioned at {@code screenWidth - currentWidth()}.
 *
 * <h2>Customization</h2>
 * All visual constants come from {@link CaesiumTheme}. To change the panel:
 * <ul>
 *   <li>{@code EXPANDED_W} — maximum panel width in pixels</li>
 *   <li>Animation speed — {@code CaesiumTheme.ANIM_SPEED_TOOLTIP_IN / OUT}</li>
 *   <li>Colors — sidebar/content colors in {@link CaesiumTheme}</li>
 * </ul>
 */
public final class DetailPanel {

    /** Maximum width of the expanded detail panel (px). */
    public static int EXPANDED_W = 180;

    /** Minimum width — collapses to 0 (fully hidden off-screen). */
    private static final int COLLAPSED_W = 0;

    /** Vertical padding inside the panel. */
    private static final int PAD = 12;
    /** Line height for wrapped description text. */
    private static final int LINE_H = 10;

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private final GuiAnimator anim = new GuiAnimator();

    /** Currently displayed option (held even when animating out so we can keep rendering it). */
    private Option<?> displayedOption = null;
    /** Option being targeted (set by setHovered). */
    private Option<?> targetOption    = null;

    /** Animated panel width [0..EXPANDED_W]. */
    private float animWidth = 0f;

    private int screenW, screenH, panelY, panelH;

    // -------------------------------------------------------------------------
    // API
    // -------------------------------------------------------------------------

    /**
     * Called every frame with the currently hovered option (or null if nothing is hovered).
     * Must be called before {@link #tick(float)}.
     */
    public void setHovered(Option<?> option) {
        this.targetOption = option;
    }

    /**
     * Advances animations. Call once per frame with delta time in seconds.
     */
    public void tick(float deltaSec) {
        boolean showing = targetOption != null;

        // When a new option appears, reset so the new content slides in clean.
        if (targetOption != null && targetOption != displayedOption) {
            // Snap width to 0 briefly only if we were at 0 (first appearance).
            // If already open from a different option, keep the panel open.
            if (animWidth < 2f) {
                displayedOption = targetOption;
            } else {
                // Already open — swap content instantly (no collapse/reopen)
                displayedOption = targetOption;
            }
        }

        if (!showing && animWidth < 0.5f) {
            displayedOption = null;
        }

        float target = showing ? EXPANDED_W : COLLAPSED_W;
        float speed  = showing ? CaesiumTheme.ANIM_SPEED_TOOLTIP_IN : CaesiumTheme.ANIM_SPEED_TOOLTIP_OUT;

        animWidth = GuiAnimationHelper.smoothDamp(animWidth, target, speed, deltaSec);
        if (Math.abs(animWidth - target) < 0.3f) animWidth = target;
    }

    /**
     * Set the vertical bounds this panel occupies (matches the content area bounds).
     */
    public void setBounds(int screenW, int screenH, int panelY, int panelH) {
        this.screenW = screenW;
        this.screenH = screenH;
        this.panelY  = panelY;
        this.panelH  = panelH;
    }

    /**
     * Returns the current animated width of the panel in pixels.
     * The content area should subtract this from its right edge each frame.
     */
    public int currentWidth() {
        return (int) animWidth;
    }

    /**
     * Returns true when the panel is fully collapsed and invisible.
     */
    public boolean isInvisible() {
        return animWidth < 1f;
    }

    // -------------------------------------------------------------------------
    // Rendering
    // -------------------------------------------------------------------------

    /**
     * Renders the detail panel.
     *
     * @param ctx     draw context
     * @param tr      text renderer
     */
    public void render(DrawContext ctx, TextRenderer tr) {
        if (isInvisible() || displayedOption == null) return;

        float alpha01 = Math.min(1f, animWidth / (float) EXPANDED_W);
        // Slide: panel X starts from right edge and slides left as it opens
        int panelW = (int) animWidth;
        int panelX = screenW - panelW;

        // Draw frosted panel — slightly more opaque than content to feel elevated
        GuiRenderer.animatedFrostedPanel(ctx, panelX, panelY, panelW, panelH,
            CaesiumTheme.bgSidebar(), CaesiumTheme.borderStrong(),
            CaesiumTheme.RADIUS_PANEL, false, alpha01);

        // Accent left border line (indicates this is a detail, not a panel on its own)
        if (alpha01 > 0.3f) {
            int lineAlpha = (int)(0xFF * Math.min(1f, (alpha01 - 0.3f) / 0.7f));
            ctx.fill(panelX, panelY + 8, panelX + 2, panelY + panelH - 8,
                (lineAlpha << 24) | CaesiumTheme.ACCENT_RGB);
        }

        // Only render text content when panel is wide enough to read
        if (panelW < 40 || alpha01 < 0.4f) return;

        int innerX   = panelX + PAD;
        int innerW   = panelW - PAD * 2;
        int textAlpha = (int)(0xFF * Math.min(1f, (alpha01 - 0.4f) / 0.6f));
        if (textAlpha < 10) return;

        int curY = panelY + PAD;

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
                if (curY + LINE_H > panelY + panelH - PAD) break;
                ctx.drawText(tr, line, innerX, curY, secColor, false);
                curY += LINE_H;
            }
            curY += 4;
        }

        // ---- Default rationale ----
        String reason = opt.getDefaultReason();
        if (reason != null && !reason.isEmpty() && curY + LINE_H < panelY + panelH - PAD) {
            ctx.fill(innerX, curY, innerX + innerW, curY + 1,
                (int)(divAlpha * 0.4f) << 24 | (CaesiumTheme.BORDER_STRONG_COLOR & 0x00FFFFFF));
            curY += 6;
            List<OrderedText> reasonLines = tr.wrapLines(CaesiumFont.text(reason), innerW);
            int mutedColor = (textAlpha << 24) | (CaesiumTheme.TEXT_DISABLED & 0x00FFFFFF);
            for (OrderedText line : reasonLines) {
                if (curY + LINE_H > panelY + panelH - PAD) break;
                ctx.drawText(tr, line, innerX, curY, mutedColor, false);
                curY += LINE_H;
            }
        }

        // ---- Current value (bottom of panel) ----
        String val = opt.getFormattedValue();
        if (val != null && !val.isEmpty()) {
            int valY = panelY + panelH - PAD - 8;
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
