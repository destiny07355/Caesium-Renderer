package destiny.renderer.gui.render;

import destiny.renderer.gui.theme.CaesiumTheme;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/**
 * Caesium GUI Primitive Renderer Library.
 *
 * <p>Every component in Caesium draws via this library — never raw {@code DrawContext.fill()} calls.
 * This ensures that visual changes to the design system need exactly one edit here.
 *
 * <h2>Design Invariants</h2>
 * <ul>
 *   <li>All text is white-family. Black text or dark shadows are explicitly forbidden.</li>
 *   <li>All panels are frosted translucent with simulated depth blur (darker = deeper).</li>
 *   <li>Rounded corners are drawn by rasterizing a quarter-circle using fill operations.</li>
 *   <li>Every method takes a {@link DrawContext}; no raw GL calls are made.</li>
 * </ul>
 */
public final class GuiRenderer {

    private GuiRenderer() {}

    // =========================================================================
    // CORE: ROUNDED FILLED RECTANGLE
    // =========================================================================

    /**
     * Draws a filled rounded rectangle using the given ARGB color.
     *
     * @param r corner radius in pixels. Set to 0 for a plain rectangle.
     */
    public static void filledRoundedBox(DrawContext ctx, int x, int y, int w, int h, int r, int color) {
        if (w <= 0 || h <= 0) return;
        r = Math.min(r, Math.min(w / 2, h / 2));
        if (r <= 0) { ctx.fill(x, y, x + w, y + h, color); return; }

        // Center + side flanks
        ctx.fill(x + r, y,     x + w - r, y + h, color);
        ctx.fill(x,     y + r, x + r,     y + h - r, color);
        ctx.fill(x + w - r, y + r, x + w, y + h - r, color);

        // 4 rounded corners (2-step staircase)
        int r2 = r / 2 + 1;
        // top-left
        ctx.fill(x + r2, y,     x + r,    y + r2, color);
        ctx.fill(x,      y + r2, x + r2,  y + r,  color);
        ctx.fill(x + r2, y + r2, x + r,   y + r,  color);
        // top-right
        ctx.fill(x + w - r,  y,     x + w - r2, y + r2, color);
        ctx.fill(x + w - r2, y + r2, x + w,     y + r,  color);
        ctx.fill(x + w - r,  y + r2, x + w - r2, y + r, color);
        // bottom-left
        ctx.fill(x + r2, y + h - r,  x + r,   y + h - r2, color);
        ctx.fill(x,      y + h - r,  x + r2,  y + h - r2, color);
        ctx.fill(x + r2, y + h - r2, x + r,   y + h,      color);
        // bottom-right
        ctx.fill(x + w - r,  y + h - r,  x + w - r2, y + h - r2, color);
        ctx.fill(x + w - r2, y + h - r,  x + w,      y + h - r2, color);
        ctx.fill(x + w - r,  y + h - r2, x + w - r2, y + h,      color);
    }

    // =========================================================================
    // CORE: ROUNDED BORDER (1px outline)
    // =========================================================================

    /** Draws a 1-pixel rounded border without a fill. */
    public static void roundedBorder(DrawContext ctx, int x, int y, int w, int h, int r, int color) {
        if (w <= 0 || h <= 0) return;
        r = Math.min(r, Math.min(w / 2, h / 2));
        if (r <= 0) {
            ctx.fill(x,         y,         x + w,     y + 1,         color);
            ctx.fill(x,         y + h - 1, x + w,     y + h,         color);
            ctx.fill(x,         y + 1,     x + 1,     y + h - 1,     color);
            ctx.fill(x + w - 1, y + 1,     x + w,     y + h - 1,     color);
            return;
        }
        ctx.fill(x + r,     y,         x + w - r, y + 1,         color); // top
        ctx.fill(x + r,     y + h - 1, x + w - r, y + h,         color); // bottom
        ctx.fill(x,         y + r,     x + 1,     y + h - r,     color); // left
        ctx.fill(x + w - 1, y + r,     x + w,     y + h - r,     color); // right
        int r2 = r / 2 + 1;
        // corner miter pixels
        ctx.fill(x + r2,      y + 1,         x + r,        y + 2,          color);
        ctx.fill(x + 1,       y + r2,         x + 2,        y + r,          color);
        ctx.fill(x + w - r,   y + 1,         x + w - r2,   y + 2,          color);
        ctx.fill(x + w - 2,   y + r2,         x + w - 1,   y + r,           color);
        ctx.fill(x + r2,      y + h - 2,     x + r,        y + h - 1,      color);
        ctx.fill(x + 1,       y + h - r,     x + 2,        y + h - r2,     color);
        ctx.fill(x + w - r,   y + h - 2,    x + w - r2,   y + h - 1,      color);
        ctx.fill(x + w - 2,   y + h - r,    x + w - 1,    y + h - r2,     color);
    }

    // =========================================================================
    // FROSTED PANEL (translucent + depth blur + optional glow)
    // =========================================================================

    /**
     * Draws a frosted panel with simulated depth blur.
     *
     * @param fill    base ARGB fill color (should have alpha < FF)
     * @param border  1px border ARGB color
     * @param r       corner radius
     * @param glow    when true, draws a faint accent halo (controlled by CaesiumTheme.GLOW_*)
     */
    public static void frostedPanel(DrawContext ctx, int x, int y, int w, int h,
                                    int fill, int border, int r, boolean glow) {
        // Outer glow halo
        if (glow && CaesiumTheme.GLOW_ENABLED) {
            int gs = CaesiumTheme.GLOW_SIZE;
            int glowColor = (CaesiumTheme.GLOW_ALPHA << 24) | CaesiumTheme.ACCENT_RGB;
            filledRoundedBox(ctx, x - gs, y - gs, w + gs * 2, h + gs * 2, r + gs, glowColor);
        }
        // Blur layers — larger → smaller, each more opaque than last
        int spread = CaesiumTheme.BLUR_SPREAD;
        for (int i = CaesiumTheme.BLUR_LAYERS; i >= 1; i--) {
            int s  = i * spread;
            int ba = CaesiumTheme.BLUR_ALPHA_PER_LAYER / (i + 1);
            filledRoundedBox(ctx, x - s, y - s, w + s * 2, h + s * 2, r + s,
                (ba << 24) | (fill & 0x00FFFFFF));
        }
        // Main fill
        filledRoundedBox(ctx, x, y, w, h, r, fill);
        // Border
        roundedBorder(ctx, x, y, w, h, r, border);
    }

    // =========================================================================
    // ANIMATED FROSTED PANEL (alpha driven by a [0..1] progress value)
    // =========================================================================

    /**
     * Same as {@link #frostedPanel} but overall alpha is multiplied by {@code alpha01}.
     * Used for fade-in/out animations (screen open, tab switch, tooltip).
     */
    public static void animatedFrostedPanel(DrawContext ctx, int x, int y, int w, int h,
                                            int fill, int border, int r, boolean glow,
                                            float alpha01) {
        alpha01 = Math.max(0f, Math.min(1f, alpha01));
        int af = (int)(((fill   >> 24) & 0xFF) * alpha01);
        int ab = (int)(((border >> 24) & 0xFF) * alpha01);
        int animFill   = (af << 24) | (fill   & 0x00FFFFFF);
        int animBorder = (ab << 24) | (border & 0x00FFFFFF);
        frostedPanel(ctx, x, y, w, h, animFill, animBorder, r, glow && alpha01 > 0.5f);
    }

    // =========================================================================
    // PANEL VARIANTS (convenience wrappers — no fill arguments needed)
    // =========================================================================

    public static void sidebarPanel(DrawContext ctx, int x, int y, int w, int h) {
        frostedPanel(ctx, x, y, w, h,
            CaesiumTheme.bgSidebar(), CaesiumTheme.borderStrong(),
            CaesiumTheme.RADIUS_PANEL, false);
    }

    public static void contentPanel(DrawContext ctx, int x, int y, int w, int h) {
        frostedPanel(ctx, x, y, w, h,
            CaesiumTheme.bgContent(), CaesiumTheme.borderStrong(),
            CaesiumTheme.RADIUS_PANEL, false);
    }

    public static void animatedSidebarPanel(DrawContext ctx, int x, int y, int w, int h, float alpha01) {
        animatedFrostedPanel(ctx, x, y, w, h,
            CaesiumTheme.bgSidebar(), CaesiumTheme.borderStrong(),
            CaesiumTheme.RADIUS_PANEL, false, alpha01);
    }

    public static void animatedContentPanel(DrawContext ctx, int x, int y, int w, int h, float alpha01) {
        animatedFrostedPanel(ctx, x, y, w, h,
            CaesiumTheme.bgContent(), CaesiumTheme.borderStrong(),
            CaesiumTheme.RADIUS_PANEL, false, alpha01);
    }

    // =========================================================================
    // GLOW BOX (accent halo around focused/active elements)
    // =========================================================================

    /**
     * Draws a colored outer glow around a box (for focused inputs, active buttons).
     *
     * @param glowColor ARGB glow color (alpha controls intensity)
     * @param glowSize  spread in pixels
     * @param r         corner radius of the inner element
     */
    public static void glowBox(DrawContext ctx, int x, int y, int w, int h,
                               int glowColor, int glowSize, int r) {
        for (int i = glowSize; i >= 1; i--) {
            int a = (int)(((glowColor >> 24) & 0xFF) * (1f - (float)(i - 1) / glowSize));
            int col = (a << 24) | (glowColor & 0x00FFFFFF);
            filledRoundedBox(ctx, x - i, y - i, w + i * 2, h + i * 2, r + i, col);
        }
    }

    // =========================================================================
    // CONTROL BOX (checkbox, cycling control backgrounds)
    // =========================================================================

    /**
     * Draws a control widget background with hover/active/disabled states.
     * All visual state is driven by the three boolean flags — no animation progress needed.
     */
    public static void controlBox(DrawContext ctx, int x, int y, int w, int h,
                                  boolean hovered, boolean active, boolean enabled) {
        int r = CaesiumTheme.RADIUS_CONTROL;
        int fill, border;
        if (!enabled) {
            fill   = CaesiumTheme.bgElevated();
            border = CaesiumTheme.borderSubtle();
        } else if (active) {
            fill   = CaesiumTheme.bgActive();
            border = CaesiumTheme.borderAccent();
        } else if (hovered) {
            fill   = CaesiumTheme.bgHover();
            border = CaesiumTheme.accent();
        } else {
            fill   = CaesiumTheme.bgElevated();
            border = CaesiumTheme.borderLight();
        }
        filledRoundedBox(ctx, x, y, w, h, r, fill);
        roundedBorder(ctx, x, y, w, h, r, border);
    }

    /**
     * Same as {@link #controlBox} but with animated focus glow for text inputs.
     *
     * @param focusProgress [0..1] from GuiAnimator.focus()
     */
    public static void inputField(DrawContext ctx, int x, int y, int w, int h,
                                  boolean hovered, boolean focused, float focusProgress) {
        int r = CaesiumTheme.RADIUS_INPUT;
        int fill = focused || hovered ? CaesiumTheme.bgElevated() : CaesiumTheme.bgElevated();
        int border = focused
            ? lerp(CaesiumTheme.borderLight(), CaesiumTheme.borderAccent(), focusProgress)
            : hovered ? CaesiumTheme.borderLight()
            : CaesiumTheme.borderStrong();

        // Glow behind border when focused
        if (focused && focusProgress > 0.05f && CaesiumTheme.GLOW_ENABLED) {
            int glowAlpha = (int)(CaesiumTheme.GLOW_ALPHA * focusProgress);
            glowBox(ctx, x, y, w, h, (glowAlpha << 24) | CaesiumTheme.ACCENT_RGB, 3, r);
        }
        filledRoundedBox(ctx, x, y, w, h, r, fill);
        roundedBorder(ctx, x, y, w, h, r, border);
    }

    // =========================================================================
    // BUTTON
    // =========================================================================

    /**
     * Draws a styled button with animated press flash.
     *
     * @param primary      true for the filled accent (crimson) style
     * @param danger       true for the red-outline danger style
     * @param pressFlash   [0..1] from GuiAnimator.buttonPress() — brief flash on click
     */
    public static void button(DrawContext ctx, TextRenderer tr,
                              int x, int y, int w, int h,
                              String label, boolean hovered, boolean primary,
                              boolean danger, boolean enabled, float pressFlash) {
        int r = CaesiumTheme.RADIUS_BUTTON;
        int fill, border, textColor;

        if (!enabled) {
            fill      = CaesiumTheme.bgElevated();
            border    = CaesiumTheme.borderSubtle();
            textColor = CaesiumTheme.TEXT_DISABLED;
        } else if (primary) {
            int baseFill = hovered ? CaesiumTheme.accentBright() : CaesiumTheme.accent();
            fill      = lerp(baseFill, CaesiumTheme.accentBright(), pressFlash);
            border    = fill;
            textColor = CaesiumTheme.TEXT_PRIMARY;
        } else if (danger) {
            fill      = hovered ? 0xCC3A0A0A : CaesiumTheme.bgElevated();
            border    = CaesiumTheme.STATUS_ERROR;
            textColor = hovered ? CaesiumTheme.STATUS_ERROR : CaesiumTheme.TEXT_SECONDARY;
        } else {
            int baseFill = hovered ? CaesiumTheme.bgHover() : CaesiumTheme.bgElevated();
            fill      = lerp(baseFill, CaesiumTheme.accentBright(), pressFlash * 0.4f);
            border    = hovered ? CaesiumTheme.accent() : CaesiumTheme.borderLight();
            textColor = hovered ? CaesiumTheme.TEXT_PRIMARY : CaesiumTheme.TEXT_SECONDARY;
        }

        filledRoundedBox(ctx, x, y, w, h, r, fill);
        roundedBorder(ctx, x, y, w, h, r, border);

        // Centered text, no shadow
        int tw = tr.getWidth(label);
        ctx.drawText(tr, Text.literal(label), x + Math.max(3, (w - tw) / 2), y + (h - 8) / 2,
            textColor, false);
    }

    // =========================================================================
    // ROW HIGHLIGHT (hover + selected states)
    // =========================================================================

    /** Draws an animated hover row highlight. {@code hoverProgress} is from GuiAnimator.hover(). */
    public static void rowHoverAnimated(DrawContext ctx, int x, int y, int w, int h, float hoverProgress) {
        if (hoverProgress < 0.01f) return;
        int a = (int)(CaesiumTheme.BG_HOVER_ALPHA * hoverProgress);
        filledRoundedBox(ctx, x, y, w, h, CaesiumTheme.RADIUS_ROW, (a << 24) | 0xFFFFFF);
    }

    /** Draws a selected row highlight with accent wash + left accent bar. {@code selProgress} from GuiAnimator.selection(). */
    public static void rowSelectedAnimated(DrawContext ctx, int x, int y, int w, int h, float selProgress) {
        if (selProgress < 0.01f) return;
        int a = (int)(CaesiumTheme.ACCENT_DIM_ALPHA * selProgress);
        filledRoundedBox(ctx, x, y, w, h, CaesiumTheme.RADIUS_ROW, (a << 24) | CaesiumTheme.ACCENT_RGB);
        // Accent bar — height grows from 0 to full with selection progress
        int barH = (int)((h - 8) * selProgress);
        int barY = y + 4 + (h - 8 - barH) / 2;
        ctx.fill(x, barY, x + 3, barY + barH, CaesiumTheme.accent());
    }

    /** Plain static row hover (for components without a GuiAnimator). */
    public static void rowHover(DrawContext ctx, int x, int y, int w, int h) {
        filledRoundedBox(ctx, x, y, w, h, CaesiumTheme.RADIUS_ROW, CaesiumTheme.bgHover());
    }

    /** Plain static row selected (for components without a GuiAnimator). */
    public static void rowSelected(DrawContext ctx, int x, int y, int w, int h) {
        filledRoundedBox(ctx, x, y, w, h, CaesiumTheme.RADIUS_ROW, CaesiumTheme.accentDim());
        ctx.fill(x, y + 4, x + 3, y + h - 4, CaesiumTheme.accent());
    }

    /** Non-default option indicator bar on left edge of a row. */
    public static void rowModifiedBar(DrawContext ctx, int x, int y, int h) {
        ctx.fill(x, y + 6, x + 2, y + h - 6, CaesiumTheme.accent());
    }

    // =========================================================================
    // SECTION HEADER (title + horizontal rule)
    // =========================================================================

    /**
     * Draws a section group header: uppercase title left, subtle rule extending right.
     */
    public static void sectionHeader(DrawContext ctx, TextRenderer tr,
                                     int x, int y, int maxW, String title) {
        ctx.drawText(tr, Text.literal(title.toUpperCase()), x, y + 4, CaesiumTheme.TEXT_SECONDARY, false);
        int tw = tr.getWidth(title.toUpperCase());
        int rx = x + tw + 8;
        int ry = y + 8;
        if (rx < x + maxW - 4) {
            ctx.fill(rx, ry, x + maxW - 4, ry + 1, CaesiumTheme.borderSubtle());
        }
    }

    // =========================================================================
    // PROGRESS BAR (reusable for sliders too)
    // =========================================================================

    /**
     * Draws a horizontal filled progress bar with track, filled portion, and a knob.
     *
     * @param frac       fill fraction [0..1]
     * @param accentColor ARGB color for the filled portion
     * @param trackColor ARGB color for the track background
     */
    public static void progressBar(DrawContext ctx,
                                   int x, int y, int w, int h,
                                   float frac, int accentColor, int trackColor) {
        frac = Math.max(0f, Math.min(1f, frac));
        ctx.fill(x, y, x + w, y + h, trackColor);
        ctx.fill(x, y, x + (int)(frac * w), y + h, accentColor);
    }

    // =========================================================================
    // BADGE (small rounded count label)
    // =========================================================================

    /**
     * Draws a badge — a small rounded label (e.g. search match count).
     * {@code scale} from GuiAnimator.badgeScale() adds a pop-in effect.
     */
    public static void badge(DrawContext ctx, TextRenderer tr,
                             int cx, int cy, String label, int bgColor, int textColor,
                             float scale) {
        if (scale < 0.05f) return;
        int tw = tr.getWidth(label);
        int bw = (int)((tw + 8) * scale);
        int bh = (int)(12 * scale);
        int bx = cx - bw / 2;
        int by = cy - bh / 2;
        filledRoundedBox(ctx, bx, by, bw, bh, CaesiumTheme.RADIUS_BADGE, bgColor);
        // Text centered (only draw when scale is large enough to be readable)
        if (scale > 0.7f) {
            ctx.drawText(tr, Text.literal(label), bx + (bw - tw) / 2, by + (bh - 8) / 2, textColor, false);
        }
    }

    // =========================================================================
    // DIVIDER
    // =========================================================================

    /** Draws a full-width horizontal divider with optional centred label. */
    public static void divider(DrawContext ctx, TextRenderer tr,
                               int x, int y, int w, String label) {
        if (label == null || label.isEmpty()) {
            ctx.fill(x, y, x + w, y + 1, CaesiumTheme.borderSubtle());
        } else {
            int tw = tr.getWidth(label);
            int mid = x + w / 2;
            ctx.fill(x, y + 4, mid - tw / 2 - 4, y + 5, CaesiumTheme.borderSubtle());
            ctx.drawText(tr, Text.literal(label), mid - tw / 2, y, CaesiumTheme.TEXT_SECONDARY, false);
            ctx.fill(mid + tw / 2 + 4, y + 4, x + w, y + 5, CaesiumTheme.borderSubtle());
        }
    }

    // =========================================================================
    // SCROLLBAR
    // =========================================================================

    /** Draws a scrollbar with animated thumb color on hover. */
    public static void scrollbar(DrawContext ctx, int x, int y, int h,
                                 float scrollOffset, int contentH, boolean hovered) {
        if (contentH <= h) return;
        ctx.fill(x, y, x + CaesiumTheme.SCROLLBAR_WIDTH, y + h, CaesiumTheme.scrollbarTrack());
        int thumbH = Math.max(16, (int)((h / (float) contentH) * h));
        int thumbY = y + (int)((scrollOffset / (float)(contentH - h)) * (h - thumbH));
        thumbY = Math.max(y, Math.min(y + h - thumbH, thumbY));
        ctx.fill(x, thumbY, x + CaesiumTheme.SCROLLBAR_WIDTH, thumbY + thumbH,
            hovered ? CaesiumTheme.SCROLLBAR_HOT_COLOR : CaesiumTheme.SCROLLBAR_THUMB_COLOR);
    }

    // =========================================================================
    // SCREEN BACKGROUND
    // =========================================================================

    /** Fills the entire screen with the configured background color. */
    public static void screenBackground(DrawContext ctx, int sw, int sh) {
        ctx.fill(0, 0, sw, sh, CaesiumTheme.bgScreen());
    }

    // =========================================================================
    // TEXT HELPERS (no shadow by default — controlled by CaesiumTheme flags)
    // =========================================================================

    public static void text(DrawContext ctx, TextRenderer tr, String s, int x, int y, int color) {
        if (s == null || s.isEmpty()) return;
        ctx.drawText(tr, Text.literal(s), x, y, color, false);
    }

    public static void textCentered(DrawContext ctx, TextRenderer tr,
                                    String s, int x, int y, int w, int color) {
        if (s == null || s.isEmpty()) return;
        int tw = tr.getWidth(s);
        ctx.drawText(tr, Text.literal(s), x + Math.max(0, (w - tw) / 2), y, color, false);
    }

    // =========================================================================
    // COLOR MATH HELPERS
    // =========================================================================

    /** Linearly blends two ARGB colors by factor t [0..1]. */
    public static int lerp(int from, int to, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int a1 = (from >> 24) & 0xFF, r1 = (from >> 16) & 0xFF, g1 = (from >> 8) & 0xFF, b1 = from & 0xFF;
        int a2 = (to   >> 24) & 0xFF, r2 = (to   >> 16) & 0xFF, g2 = (to   >> 8) & 0xFF, b2 = to   & 0xFF;
        return ((int)(a1 + (a2 - a1) * t) << 24) | ((int)(r1 + (r2 - r1) * t) << 16)
             | ((int)(g1 + (g2 - g1) * t) << 8)  |  (int)(b1 + (b2 - b1) * t);
    }

    /** Applies an alpha multiplier [0..1] to an ARGB color. */
    public static int withAlpha(int argb, float alpha) {
        int a = (int)(((argb >> 24) & 0xFF) * Math.max(0f, Math.min(1f, alpha)));
        return (a << 24) | (argb & 0x00FFFFFF);
    }
}
