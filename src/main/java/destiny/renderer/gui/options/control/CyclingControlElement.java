package destiny.renderer.gui.options.control;

import destiny.renderer.gui.CaesiumFont;
import destiny.renderer.gui.render.GuiRenderer;
import destiny.renderer.gui.theme.CaesiumTheme;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.util.List;

/**
 * Cycling (multi-value) control with title click detection.
 */
public final class CyclingControlElement<T> extends ControlElement<T> {

    private static final int BOX_W = 76;

    public CyclingControlElement(destiny.renderer.gui.options.Option<T> option,
                                 int x, int y, int width, int height) {
        super(option, x, y, width, height);
    }

    @Override
    protected void renderControl(DrawContext context, int mouseX, int mouseY,
                                 float delta, boolean enabled) {
        boolean hovered = isHovered() && enabled;

        int bx = getX() + width - BOX_W - 8;
        int by = getY() + 2;
        int bh = height - 4;

        GuiRenderer.controlBox(context, bx, by, BOX_W, bh, hovered, false, enabled);

        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        String text = option.getFormattedValue();
        if (tr.getWidth(text) > BOX_W - 8) {
            text = tr.trimToWidth(text, BOX_W - 14) + "...";
        }
        int tw = tr.getWidth(text);

        int textColor = !enabled ? CaesiumTheme.TEXT_DISABLED
                      : hovered  ? CaesiumTheme.TEXT_PRIMARY
                      :            CaesiumTheme.TEXT_SECONDARY;
        context.drawText(tr, CaesiumFont.text(text),
            bx + (BOX_W - tw) / 2, by + (bh - 8) / 2, textColor, false);

        if (hovered && enabled) {
            context.drawText(tr, CaesiumFont.text("<"), bx + 3, by + (bh - 8) / 2,
                CaesiumTheme.TEXT_SECONDARY, false);
            context.drawText(tr, CaesiumFont.text(">"), bx + BOX_W - 8, by + (bh - 8) / 2,
                CaesiumTheme.TEXT_SECONDARY, false);
        }
    }

    private void cycle(int direction) {
        List<T> values = option.getAllowedValues();
        if (values.isEmpty()) return;
        int idx  = values.indexOf(option.getValue());
        if (idx < 0) idx = 0;
        option.setValue(values.get(Math.floorMod(idx + direction, values.size())));
    }

    @Override
    public void onClick(net.minecraft.client.gui.Click click, boolean doubled) {
        if (!this.active || !this.visible || !isOptionEnabled()) return;
        if (isTitleArea(click.x())) {
            if (onTitleClick != null) onTitleClick.accept(option);
            return;
        }
        this.playDownSound(MinecraftClient.getInstance().getSoundManager());
        cycle(click.button() == 1 ? -1 : 1);
    }
}
