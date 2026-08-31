package destiny.renderer.gui.options.control;

import destiny.renderer.gui.CaesiumFont;
import destiny.renderer.gui.render.GuiRenderer;
import destiny.renderer.gui.theme.CaesiumTheme;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

/**
 * Numeric slider control.
 * Uses GuiRenderer.progressBar for the track and GuiRenderer.controlBox for the knob.
 */
public final class SliderControlElement extends ControlElement<Object> {

    private static final int TRACK_W = CaesiumTheme.SLIDER_TRACK_W;
    private static final int VALUE_W = CaesiumTheme.SLIDER_VALUE_W;

    private boolean dragging = false;

    @SuppressWarnings("unchecked")
    public SliderControlElement(destiny.renderer.gui.options.Option<?> option,
                                int x, int y, int width, int height) {
        super((destiny.renderer.gui.options.Option<Object>) option, x, y, width, height);
    }

    private int trackX() { return getX() + width - TRACK_W - 8; }

    @Override
    protected void renderControl(DrawContext context, int mouseX, int mouseY,
                                 float delta, boolean enabled) {
        double min  = option.getMin();
        double max  = option.getMax();
        double val  = option.asDouble();
        float  frac = max > min ? (float)((val - min) / (max - min)) : 0f;
        frac = Math.max(0f, Math.min(1f, frac));

        boolean hovered = (isHovered() || dragging) && enabled;

        int tx = trackX();
        int ty = getY() + height / 2 - CaesiumTheme.SLIDER_TRACK_H / 2;
        int th = CaesiumTheme.SLIDER_TRACK_H;

        // Track (empty portion)
        int trackColor  = !enabled ? 0x40FFFFFF : 0x30FFFFFF;
        int accentColor = !enabled ? CaesiumTheme.TEXT_DISABLED
                        : hovered  ? CaesiumTheme.accentBright()
                        :            CaesiumTheme.accent();

        GuiRenderer.progressBar(context, tx, ty, TRACK_W, th, frac, accentColor, trackColor);

        // Knob
        int kw = CaesiumTheme.SLIDER_KNOB_W;
        int kx = tx + Math.max(0, Math.min(TRACK_W - kw, (int)(frac * TRACK_W) - kw / 2));
        int kColor = enabled ? CaesiumTheme.TEXT_PRIMARY : CaesiumTheme.TEXT_DISABLED;
        context.fill(kx, getY() + 4, kx + kw, getY() + height - 4, kColor);

        // Value readout
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        String text = option.getFormattedValue();
        int textW = tr.getWidth(text);
        int textX = tx - VALUE_W + Math.max(0, VALUE_W - textW) - 4;
        int textY = getY() + (height - 8) / 2;
        context.drawText(tr, CaesiumFont.text(text), textX, textY,
            enabled ? CaesiumTheme.TEXT_SECONDARY : CaesiumTheme.TEXT_DISABLED, false);
    }

    private void applyFromMouse(double mouseX) {
        int tx   = trackX();
        double frac = (mouseX - tx) / (double) TRACK_W;
        frac = Math.max(0.0, Math.min(1.0, frac));
        double min  = option.getMin();
        double max  = option.getMax();
        double step = option.getStep() > 0 ? option.getStep() : 1.0;
        double raw  = min + frac * (max - min);
        double snap = Math.max(min, Math.min(max, Math.round(raw / step) * step));
        setTyped(snap);
    }

    private void setTyped(double v) {
        Object current = option.getValue();
        if (current instanceof Integer) option.setValue((int) Math.round(v));
        else if (current instanceof Float)  option.setValue((float) v);
        else if (current instanceof Double) option.setValue(v);
        else if (current instanceof Long)   option.setValue(Math.round(v));
    }

    @Override
    public void onClick(net.minecraft.client.gui.Click click, boolean doubled) {
        if (!this.active || !this.visible || !isOptionEnabled()) return;
        int tx = trackX();
        if (click.x() >= tx - 4 && click.x() <= tx + TRACK_W + 4) {
            this.playDownSound(MinecraftClient.getInstance().getSoundManager());
            dragging = true;
            applyFromMouse(click.x());
        }
    }

    @Override
    protected void onDrag(net.minecraft.client.gui.Click click, double dx, double dy) {
        if (dragging && isOptionEnabled()) applyFromMouse(click.x());
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.gui.Click click, double dx, double dy) {
        if (!dragging || !isOptionEnabled()) return false;
        applyFromMouse(click.x());
        return true;
    }

    @Override
    public void onRelease(net.minecraft.client.gui.Click click) { dragging = false; }

    public void stopDragging()  { dragging = false; }
    public boolean isDragging() { return dragging; }

    public void nudge(int direction) {
        if (!isOptionEnabled()) return;
        double step = option.getStep() > 0 ? option.getStep() : 1.0;
        setTyped(option.asDouble() + direction * step);
    }
}
