package destiny.renderer.gui.options.control;

import destiny.renderer.gui.CaesiumFont;
import destiny.renderer.gui.options.Option;
import destiny.renderer.gui.theme.CaesiumTheme;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;

/**
 * Informational readout or action label control.
 * Used for Hardware, Telemetry, JVM stats and informational rows.
 */
public final class LabelControlElement extends ControlElement<Object> {

    @SuppressWarnings("unchecked")
    public LabelControlElement(Option<?> option, int x, int y, int width, int height) {
        super((Option<Object>) option, x, y, width, height);
    }

    @Override
    protected void renderControl(DrawContext context, int mouseX, int mouseY,
                                 float delta, boolean enabled) {
        MinecraftClient mc = MinecraftClient.getInstance();
        TextRenderer tr = mc.textRenderer;
        String valText = option.getFormattedValue();
        if (valText == null || valText.isEmpty()) {
            Object v = option.getValue();
            valText = v != null ? v.toString() : "";
        }

        int maxW = Math.max(60, CONTROL_WIDTH + 60);
        if (tr.getWidth(valText) > maxW) {
            while (valText.length() > 3 && tr.getWidth(valText + "...") > maxW) {
                valText = valText.substring(0, valText.length() - 1);
            }
            valText = valText + "...";
        }

        int tw = tr.getWidth(valText);
        int tx = getX() + getWidth() - tw - 10;
        int ty = getY() + (getHeight() - 8) / 2;

        boolean hovered = isHovered() && enabled;
        int color = !enabled ? CaesiumTheme.TEXT_DISABLED
                  : hovered  ? CaesiumTheme.accentBright()
                  :            CaesiumTheme.accent();

        context.drawText(tr, CaesiumFont.text(valText), tx, ty, color, false);
    }

    @Override
    public void onClick(Click click, boolean doubled) {
        if (!this.visible || !this.active || !isOptionEnabled()) return;
        try {
            option.setValue(option.getValue());
        } catch (Throwable ignored) {}
    }
}
