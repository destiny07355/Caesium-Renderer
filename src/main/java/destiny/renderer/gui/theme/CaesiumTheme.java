package destiny.renderer.gui.theme;

/**
 * Caesium Design System — Unified Configuration Block.
 *
 * <p>This is the SINGLE source of truth for every visual property in the Caesium GUI.
 * Change a field here and it reflects everywhere instantly — no hunting through components.
 *
 * <h2>How to customize</h2>
 * <ul>
 *   <li>Colors: Change any {@code int} ARGB constant (format: 0xAARRGGBB).</li>
 *   <li>Translucency: Change the {@code _ALPHA} fields (0 = invisible, 255 = opaque).</li>
 *   <li>Blur depth: Increase {@code BLUR_LAYERS} / {@code BLUR_SPREAD} for deeper frost effect.</li>
 *   <li>Animations: Increase speed values for snappier feel, decrease for smoother.</li>
 *   <li>Corners: Increase radius values for rounder panels/buttons.</li>
 *   <li>Spacing: Adjust gap/padding/margin values to tighten or loosen layout.</li>
 * </ul>
 */
public final class CaesiumTheme {

    private CaesiumTheme() {}

    // =========================================================================
    // TRANSLUCENCY (alpha per panel layer, 0=invisible 255=opaque)
    // Change these to make panels more or less frosted/transparent.
    // =========================================================================

    public static int BG_SCREEN_ALPHA    = 0xCC; // Full-screen vignette overlay
    public static int BG_SIDEBAR_ALPHA   = 0xCC; // Left sidebar panel
    public static int BG_CONTENT_ALPHA   = 0xBB; // Main content area panel (darker = deeper feel)
    public static int BG_ELEVATED_ALPHA  = 0xD0; // Raised control surfaces (checkboxes, sliders)
    public static int BG_HOVER_ALPHA     = 0x28; // Row hover wash (white overlay)
    public static int BG_ACTIVE_ALPHA    = 0x22; // Active/pressed state accent wash

    // =========================================================================
    // BACKGROUND COLORS (base RGB — alpha is applied from the fields above)
    // =========================================================================

    public static final int BG_SCREEN_RGB    = 0x0D0B0E; // Near-black with wine tint
    public static final int BG_SIDEBAR_RGB   = 0x180F12; // Deep dark red-black
    public static final int BG_CONTENT_RGB   = 0x14080F; // Slightly deeper than sidebar
    public static final int BG_ELEVATED_RGB  = 0x201218; // Raised card surface

    // Composed ARGB values (used by components — change via _ALPHA fields above)
    public static int bgScreen()   { return (BG_SCREEN_ALPHA   << 24) | BG_SCREEN_RGB; }
    public static int bgSidebar()  { return (BG_SIDEBAR_ALPHA  << 24) | BG_SIDEBAR_RGB; }
    public static int bgContent()  { return (BG_CONTENT_ALPHA  << 24) | BG_CONTENT_RGB; }
    public static int bgElevated() { return (BG_ELEVATED_ALPHA << 24) | BG_ELEVATED_RGB; }
    public static int bgHover()    { return (BG_HOVER_ALPHA    << 24) | 0xFFFFFF; }
    public static int bgActive()   { return (BG_ACTIVE_ALPHA   << 24) | ACCENT_RGB; }

    // =========================================================================
    // ACCENT — Deep Red / Crimson
    // =========================================================================

    public static final int ACCENT_RGB       = 0xDC143C; // Crimson red
    public static final int ACCENT_BRIGHT_RGB= 0xFF4060; // Hover/pressed highlight
    public static final int ACCENT_DIM_ALPHA = 0x30;     // Translucent wash for selected rows
    public static final int ACCENT_GLOW_ALPHA= 0x18;     // Very faint ambient glow

    public static int accent()        { return 0xFF000000 | ACCENT_RGB; }
    public static int accentBright()  { return 0xFF000000 | ACCENT_BRIGHT_RGB; }
    public static int accentDim()     { return (ACCENT_DIM_ALPHA  << 24) | ACCENT_RGB; }
    public static int accentGlow()    { return (ACCENT_GLOW_ALPHA << 24) | ACCENT_RGB; }

    // =========================================================================
    // BORDERS
    // =========================================================================

    public static int BORDER_STRONG_COLOR = 0xFF3A1820; // Panel outer edge
    public static int BORDER_SUBTLE_ALPHA = 0x60;       // Inner rules / section dividers
    public static int BORDER_LIGHT_COLOR  = 0xFF4A2028; // Bevel highlight on controls
    public static int BORDER_ACCENT_COLOR = 0xFFDC143C; // Focused / active border

    public static int borderStrong()  { return BORDER_STRONG_COLOR; }
    public static int borderSubtle()  { return (BORDER_SUBTLE_ALPHA << 24) | ACCENT_RGB; }
    public static int borderLight()   { return BORDER_LIGHT_COLOR; }
    public static int borderAccent()  { return BORDER_ACCENT_COLOR; }

    // =========================================================================
    // TEXT (always white-family — never black or dark)
    // =========================================================================

    public static int TEXT_PRIMARY   = 0xFFFFFFFF; // Crisp white — titles, active labels
    public static int TEXT_SECONDARY = 0xFFCCAAB0; // Desaturated pink-white — descriptions
    public static int TEXT_DISABLED  = 0xFF7A5060; // Muted rose-grey — locked/disabled
    public static int TEXT_ACCENT    = 0xFFFF607A; // Warm red — highlighted labels, badges

    // Text shadow control (false = no shadow, which is sharp & clean)
    public static boolean TEXT_SHADOW_TITLE   = false;
    public static boolean TEXT_SHADOW_LABEL   = false;
    public static boolean TEXT_SHADOW_SECONDARY = false;

    // =========================================================================
    // STATUS COLORS
    // =========================================================================

    public static int STATUS_WARNING = 0xFFE8B949;
    public static int STATUS_ERROR   = 0xFFFF4040;
    public static int STATUS_SUCCESS = 0xFF62C58B;

    // =========================================================================
    // SCROLLBAR
    // =========================================================================

    public static int SCROLLBAR_TRACK_ALPHA = 0x18; // Track fill alpha
    public static int SCROLLBAR_THUMB_COLOR = 0xFF4A2028;
    public static int SCROLLBAR_HOT_COLOR   = 0xFFDC143C;
    public static int SCROLLBAR_WIDTH       = 3;    // px

    public static int scrollbarTrack() { return (SCROLLBAR_TRACK_ALPHA << 24) | 0xFFFFFF; }

    // =========================================================================
    // CORNER RADII (pixels — set to 0 for sharp corners)
    // =========================================================================

    public static int RADIUS_PANEL   = 8;  // Main sidebar + content frosted panels
    public static int RADIUS_BUTTON  = 5;  // Buttons (Done, Apply, Reset, Unlock)
    public static int RADIUS_CONTROL = 4;  // Checkbox, cycling control box
    public static int RADIUS_TOOLTIP = 6;  // Tooltip card
    public static int RADIUS_BADGE   = 3;  // Search match count badge
    public static int RADIUS_ROW     = 4;  // Row hover/selected highlight
    public static int RADIUS_INPUT   = 5;  // Search input field

    // =========================================================================
    // BLUR (frosted glass depth simulation)
    // Increase BLUR_LAYERS for a deeper frost effect (costs a few extra fill calls)
    // =========================================================================

    public static int   BLUR_LAYERS          = 3;    // Number of layers behind each panel
    public static int   BLUR_ALPHA_PER_LAYER = 0x14; // Alpha of each blur layer
    public static int   BLUR_SPREAD         = 1;    // px each layer extends beyond the panel

    // =========================================================================
    // GLOW (accent halo around focused elements)
    // =========================================================================

    public static boolean GLOW_ENABLED  = true;  // Master toggle for all glows
    public static int     GLOW_SIZE     = 3;     // px spread around border
    public static int     GLOW_ALPHA    = 0x18;  // Alpha of the glow fill

    // =========================================================================
    // LAYOUT DIMENSIONS
    // =========================================================================

    public static int HEADER_H      = 38;  // px — top chrome band height
    public static int FOOTER_H      = 34;  // px — bottom chrome band height
    public static int SIDEBAR_W     = 120; // px — left category sidebar width
    public static int SCROLLBAR_W   = 5;   // px — scrollbar column width (reserved space)

    // =========================================================================
    // SPACING
    // =========================================================================

    public static int ROW_H         = 44;  // px — height of each setting row
    public static int ROW_GAP       = 3;   // px — gap between rows
    public static int GROUP_GAP     = 14;  // px — gap between section groups
    public static int SECTION_H     = 20;  // px — section header block height
    public static int PANEL_MARGIN  = 10;  // px — margin between panels and screen edge
    public static int PANEL_PADDING = 8;   // px — internal content padding inside panels
    public static int ITEM_INDENT   = 10;  // px — left indent for row content

    // =========================================================================
    // COMPONENT DEFAULTS (override per component if needed)
    // =========================================================================

    public static int CHECKBOX_SIZE      = 16;  // px — checkbox box side length
    public static int SLIDER_TRACK_W     = 68;  // px — slider track length
    public static int SLIDER_TRACK_H     = 4;   // px — slider track height
    public static int SLIDER_KNOB_W      = 4;   // px — slider knob width
    public static int SLIDER_VALUE_W     = 28;  // px — value readout gutter
    public static int CYCLING_BOX_W      = 88;  // px — cycling control box width
    public static int TOOLTIP_MAX_W      = 240; // px — maximum tooltip card width
    public static int TOOLTIP_PADDING    = 8;   // px — tooltip internal padding
    public static int SEARCH_H           = 22;  // px — search field height

    // =========================================================================
    // HOVER BEHAVIOR
    // =========================================================================

    /** When true, hovered rows show a subtle accent outer glow. */
    public static boolean HOVER_SHOW_GLOW = false;
    /** Scale factor applied to hovered sidebar items (1.0 = no scale). */
    public static float   HOVER_SCALE     = 1.0f;

    // =========================================================================
    // ANIMATION SPEEDS (higher = faster/snappier, lower = slower/smoother)
    // Values are the 'speed' parameter for exponential damp (GuiAnimationHelper.smoothDamp).
    // Typical range: 6 (slow/silky) to 30 (instant).
    // =========================================================================

    public static float ANIM_SPEED_HOVER_IN     = 18f;  // Row hover fade-in
    public static float ANIM_SPEED_HOVER_OUT    = 10f;  // Row hover fade-out (slower = feels natural)
    public static float ANIM_SPEED_SCROLL       = 22f;  // Scroll position chase
    public static float ANIM_SPEED_TAB_SWITCH   = 16f;  // Tab content slide + fade
    public static float ANIM_SPEED_TOOLTIP_IN   = 20f;  // Tooltip fade-in + slide-up
    public static float ANIM_SPEED_TOOLTIP_OUT  = 24f;  // Tooltip fade-out
    public static float ANIM_SPEED_PANEL_OPEN   = 14f;  // Screen open slide + fade
    public static float ANIM_SPEED_CHECKBOX     = 22f;  // Checkbox fill grow
    public static float ANIM_SPEED_SELECTION    = 18f;  // Sidebar selected item accent bar
    public static float ANIM_SPEED_BUTTON_PRESS = 28f;  // Button press flash decay
    public static float ANIM_SPEED_FOCUS        = 16f;  // Input focus glow
    public static float ANIM_SPEED_BADGE        = 20f;  // Badge pop-in

    // Tab-switch slide distance (px). Content slides in from this far right.
    public static int TAB_SLIDE_DISTANCE = 16;

    // Screen open slide distance (px). Panels slide up from this far below.
    public static int PANEL_OPEN_SLIDE   = 12;

    // Tooltip slide distance (px). Tooltip slides up by this amount on appear.
    public static int TOOLTIP_SLIDE      = 4;
}
