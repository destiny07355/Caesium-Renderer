package destiny.renderer.gui.anim;

import destiny.renderer.gui.theme.CaesiumTheme;

/**
 * Per-component animation state machine for Caesium GUI.
 *
 * <p>Each component that needs animation creates its own {@code GuiAnimator} instance.
 * All slots are updated every frame via {@link #tick(float)}.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 *   // In your component:
 *   private final GuiAnimator anim = new GuiAnimator();
 *
 *   // In render:
 *   anim.setHovered(isHovered);
 *   anim.tick(deltaSec);
 *   float alpha = anim.hoverProgress();  // 0..1
 * }</pre>
 *
 * <h2>Available Slots</h2>
 * <ul>
 *   <li>{@code hover}      — row / item hover fade-in/out</li>
 *   <li>{@code selection}  — sidebar selected item accent bar</li>
 *   <li>{@code tabSwitch}  — content area slide + fade for tab changes</li>
 *   <li>{@code panelOpen}  — screen entry slide + fade</li>
 *   <li>{@code tooltip}    — tooltip card fade + slide-up</li>
 *   <li>{@code focus}      — input focus border glow</li>
 *   <li>{@code checkFill}  — checkbox inner fill grow</li>
 *   <li>{@code buttonPress}— button press flash</li>
 *   <li>{@code badge}      — badge pop-in scale</li>
 *   <li>{@code custom}     — general-purpose slot for one-off animations</li>
 * </ul>
 */
public final class GuiAnimator {

    // -------------------------------------------------------------------------
    // Internal state for each slot
    // -------------------------------------------------------------------------

    private float hover       = 0f;
    private float selection   = 0f;
    private float tabSwitch   = 0f;
    private float panelOpen   = 0f;
    private float tooltip     = 0f;
    private float focus       = 0f;
    private float checkFill   = 0f;
    private float buttonPress = 0f;
    private float badge       = 0f;
    private float custom      = 0f;

    // -------------------------------------------------------------------------
    // Target setters — call these before tick() each frame
    // -------------------------------------------------------------------------

    private boolean hoverTarget       = false;
    private boolean selectionTarget   = false;
    private boolean tabSwitchTarget   = false;
    private boolean panelOpenTarget   = false;
    private boolean tooltipTarget     = false;
    private boolean focusTarget       = false;
    private boolean checkFillTarget   = false;
    private boolean buttonPressTarget = false;
    private boolean badgeTarget       = false;
    private float   customTarget      = 0f;

    public void setHovered(boolean v)       { this.hoverTarget = v; }
    public void setSelected(boolean v)      { this.selectionTarget = v; }
    public void setTabVisible(boolean v)    { this.tabSwitchTarget = v; }
    public void setPanelOpen(boolean v)     { this.panelOpenTarget = v; }
    public void setTooltipVisible(boolean v){ this.tooltipTarget = v; }
    public void setFocused(boolean v)       { this.focusTarget = v; }
    public void setChecked(boolean v)       { this.checkFillTarget = v; }
    public void triggerPress()              { this.buttonPressTarget = true; }
    public void setBadgeVisible(boolean v)  { this.badgeTarget = v; }
    public void setCustomTarget(float v)    { this.customTarget = Math.max(0f, Math.min(1f, v)); }

    // -------------------------------------------------------------------------
    // Tick — advances all active slots. Call once per frame with deltaSec.
    // -------------------------------------------------------------------------

    public void tick(float deltaSec) {
        float dt = Math.max(0.001f, Math.min(0.1f, deltaSec));

        hover       = damp(hover,       hoverTarget       ? 1f : 0f,
                           hoverTarget  ? CaesiumTheme.ANIM_SPEED_HOVER_IN : CaesiumTheme.ANIM_SPEED_HOVER_OUT, dt);
        selection   = damp(selection,   selectionTarget   ? 1f : 0f, CaesiumTheme.ANIM_SPEED_SELECTION,    dt);
        tabSwitch   = damp(tabSwitch,   tabSwitchTarget   ? 1f : 0f, CaesiumTheme.ANIM_SPEED_TAB_SWITCH,   dt);
        panelOpen   = damp(panelOpen,   panelOpenTarget   ? 1f : 0f, CaesiumTheme.ANIM_SPEED_PANEL_OPEN,   dt);
        focus       = damp(focus,       focusTarget       ? 1f : 0f, CaesiumTheme.ANIM_SPEED_FOCUS,        dt);
        checkFill   = damp(checkFill,   checkFillTarget   ? 1f : 0f, CaesiumTheme.ANIM_SPEED_CHECKBOX,     dt);
        badge       = damp(badge,       badgeTarget       ? 1f : 0f, CaesiumTheme.ANIM_SPEED_BADGE,        dt);
        custom      = damp(custom,      customTarget,                 CaesiumTheme.ANIM_SPEED_HOVER_IN,     dt);

        // Tooltip uses split in/out speeds
        float tooltipSpd = tooltipTarget ? CaesiumTheme.ANIM_SPEED_TOOLTIP_IN : CaesiumTheme.ANIM_SPEED_TOOLTIP_OUT;
        tooltip = damp(tooltip, tooltipTarget ? 1f : 0f, tooltipSpd, dt);

        // Button press decays back to 0 after trigger
        if (buttonPressTarget) { buttonPress = 1f; buttonPressTarget = false; }
        buttonPress = damp(buttonPress, 0f, CaesiumTheme.ANIM_SPEED_BUTTON_PRESS, dt);
    }

    // -------------------------------------------------------------------------
    // Progress getters — [0..1] — use these in your rendering code
    // -------------------------------------------------------------------------

    /** Row/item hover progress [0..1]. Animate background alpha, scale, glow. */
    public float hover()        { return hover; }

    /** Sidebar selected-item progress [0..1]. Animate accent bar height + wash. */
    public float selection()    { return selection; }

    /** Tab content visibility [0..1]. Use for slide + fade when switching tabs. */
    public float tabSwitch()    { return tabSwitch; }

    /** Screen/panel open progress [0..1]. Use for entry slide + fade on open. */
    public float panelOpen()    { return panelOpen; }

    /** Tooltip card visibility [0..1]. Use for fade + slide-up. */
    public float tooltip()      { return tooltip; }

    /** Input focus glow [0..1]. Use for border glow alpha on text fields. */
    public float focus()        { return focus; }

    /** Checkbox inner fill size [0..1]. Animate box fill on check/uncheck. */
    public float checkFill()    { return checkFill; }

    /** Button press flash [0..1]. Brief spike on click, decays to 0. */
    public float buttonPress()  { return buttonPress; }

    /** Badge pop-in scale [0..1]. Start from 0, pop to 1 when badge appears. */
    public float badge()        { return badge; }

    /** Custom-purpose [0..1]. Drive with {@link #setCustomTarget(float)}. */
    public float custom()       { return custom; }

    // -------------------------------------------------------------------------
    // Convenience — derived values for common rendering operations
    // -------------------------------------------------------------------------

    /** Alpha [0..255] for hover background overlay, based on hover progress. */
    public int hoverAlpha() {
        return (int)(CaesiumTheme.BG_HOVER_ALPHA * hover);
    }

    /** ARGB for hover background wash. */
    public int hoverBg() {
        int a = hoverAlpha();
        return (a << 24) | 0xFFFFFF;
    }

    /** ARGB for tooltip card background with animated alpha. */
    public int tooltipBg(int baseArgb) {
        int a = (int)((baseArgb >> 24 & 0xFF) * tooltip);
        return (a << 24) | (baseArgb & 0x00FFFFFF);
    }

    /** ARGB for accent border during focus glow. Blends border light → accent. */
    public int focusBorder() {
        return GuiAnimationHelper.lerpColor(CaesiumTheme.borderLight(), CaesiumTheme.borderAccent(), focus);
    }

    /** Tab content X offset (slides in from right). Use as x + tabXOffset() in rendering. */
    public int tabXOffset() {
        float eased = 1f - easeOutCubic(tabSwitch);
        return (int)(eased * CaesiumTheme.TAB_SLIDE_DISTANCE);
    }

    /** Tab content alpha [0..255]. Fades in with tab switch. */
    public int tabAlpha() {
        return (int)(255 * easeOutCubic(tabSwitch));
    }

    /** Panel open Y offset (slides up from below). Use as y + panelYOffset(). */
    public int panelYOffset() {
        float eased = 1f - easeOutCubic(panelOpen);
        return (int)(eased * CaesiumTheme.PANEL_OPEN_SLIDE);
    }

    /** Panel open alpha [0..255]. */
    public int panelAlpha() {
        return (int)(255 * easeOutCubic(panelOpen));
    }

    /** Tooltip Y offset (slides up by TOOLTIP_SLIDE px on appear). */
    public int tooltipYOffset() {
        float eased = 1f - easeOutCubic(tooltip);
        return (int)(eased * CaesiumTheme.TOOLTIP_SLIDE);
    }

    /** Checkbox inner fill inset (grows from center). Returns pixels of inset from each edge. */
    public int checkboxInset(int boxSize) {
        float eased = easeOutCubic(checkFill);
        int halfGap = (int)((boxSize / 2 - 2) * (1f - eased));
        return 3 + halfGap;
    }

    /** Button press flash color blended with given base color. */
    public int buttonPressBlend(int baseColor) {
        return GuiAnimationHelper.lerpColor(baseColor, CaesiumTheme.accentBright(), buttonPress);
    }

    /** Badge scale factor [0..1] with bounce easing. */
    public float badgeScale() {
        return easeOutBack(badge);
    }

    // -------------------------------------------------------------------------
    // Instant-set helpers (no animation — jump directly to target value)
    // -------------------------------------------------------------------------

    /** Jump panel to fully open without animating. Call on screen init. */
    public void snapPanelOpen()    { panelOpen = 1f;  panelOpenTarget = true; }
    /** Jump tab to visible without animating. */
    public void snapTabVisible()   { tabSwitch = 1f;  tabSwitchTarget = true; }
    /** Jump hover to target without animating. */
    public void snapHover(boolean v){ hover = v ? 1f : 0f; hoverTarget = v; }
    /** Reset tab (hide instantly, then let it fade back in on next frame). */
    public void resetTab()         { tabSwitch = 0f; }

    // -------------------------------------------------------------------------
    // Internal math
    // -------------------------------------------------------------------------

    private static float damp(float current, float target, float speed, float dt) {
        if (Math.abs(target - current) < 0.0003f) return target;
        return current + (target - current) * (float)(1.0 - Math.exp(-speed * dt));
    }

    private static float easeOutCubic(float x) {
        float inv = 1f - Math.max(0f, Math.min(1f, x));
        return 1f - inv * inv * inv;
    }

    private static float easeOutBack(float x) {
        float c1 = 1.70158f;
        float c3 = c1 + 1f;
        float inv = Math.max(0f, Math.min(1f, x)) - 1f;
        return 1f + c3 * inv * inv * inv + c1 * inv * inv;
    }
}
