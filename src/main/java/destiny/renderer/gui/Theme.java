package destiny.renderer.gui;

import destiny.renderer.gui.theme.CaesiumTheme;

/**
 * Thin compatibility shim — maps old Theme.* references used by PerformanceOverlay
 * and legacy code to the new CaesiumTheme constants.
 *
 * <p>This class only exposes ARGB int constants. It does NOT instantiate any
 * animation objects or rendering components, so it is safe to reference from
 * any context including the gameplay render thread (InGameHudMixin).
 */
public final class Theme {

    private Theme() {}

    // ── Accent ───────────────────────────────────────────────────────────────
    public static int accent()      { return CaesiumTheme.accent(); }
    public static int accentHover() { return CaesiumTheme.accentBright(); }

    // ── Backgrounds ──────────────────────────────────────────────────────────
    public static int screenBg()    { return CaesiumTheme.bgScreen(); }
    public static int panelBg()     { return CaesiumTheme.bgSidebar(); }
    public static int contentBg()   { return CaesiumTheme.bgContent(); }

    // ── Borders ───────────────────────────────────────────────────────────────
    public static final int BORDER           = 0xFF3A1820;
    public static final int BORDER_LIGHT     = 0xFF4A2028;

    // ── Controls ─────────────────────────────────────────────────────────────
    public static final int CONTROL_BG       = 0xCC201218;
    public static final int CONTROL_BG_HOVER = 0x28FFFFFF;

    // ── Text (always white-family) ────────────────────────────────────────────
    public static final int TEXT_BRIGHT   = CaesiumTheme.TEXT_PRIMARY;
    public static final int TEXT_NORMAL   = CaesiumTheme.TEXT_SECONDARY;
    public static final int TEXT_MUTED    = CaesiumTheme.TEXT_SECONDARY;
    public static final int TEXT_DISABLED = CaesiumTheme.TEXT_DISABLED;

    // ── Row / Nav highlights ──────────────────────────────────────────────────
    public static final int ROW_HOVER  = 0x28FFFFFF;
    public static final int NAV_HOVER  = 0x28FFFFFF;
    public static final int NAV_ACTIVE = 0x30DC143C;

    // ── Status ────────────────────────────────────────────────────────────────
    public static final int RECOMMENDED = CaesiumTheme.STATUS_SUCCESS;
    public static final int WARNING     = CaesiumTheme.STATUS_WARNING;
    public static final int DANGER      = CaesiumTheme.STATUS_ERROR;

    // ── Scrollbar ─────────────────────────────────────────────────────────────
    public static final int SCROLL_TRACK       = 0x18FFFFFF;
    public static final int SCROLL_THUMB       = 0xFF4A2028;
    public static final int SCROLL_THUMB_HOVER = 0xFFDC143C;

    // ── Layout constants (read by legacy code) ────────────────────────────────
    public static int SIDEBAR_WIDTH  = CaesiumTheme.SIDEBAR_W;
    public static int HEADER_HEIGHT  = CaesiumTheme.HEADER_H;
    public static int FOOTER_HEIGHT  = CaesiumTheme.FOOTER_H;
    public static int ROW_HEIGHT     = CaesiumTheme.ROW_H;
    public static int ROW_SPACING    = CaesiumTheme.ROW_GAP;
    public static int GROUP_SPACING  = CaesiumTheme.GROUP_GAP;
}