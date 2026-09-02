package destiny.renderer.gui.options.control;

import destiny.renderer.gui.options.Option;
import destiny.renderer.gui.render.GuiRenderer;
import destiny.renderer.gui.theme.CaesiumTheme;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

/**
 * Animated checkbox control with title-click support for description opening.
 */
public final class TickBoxControlElement extends ControlElement<Boolean> {

    private static final int BOX_SIZE = 14;

    public TickBoxControlElement(Option<Boolean> option, int x, int y, int width, int height) {
        super(option, x, y, width, height);
    }

    @Override
    protected void renderControl(DrawContext context, int mouseX, int mouseY,
                                 float delta, boolean enabled) {
        boolean on      = Boolean.TRUE.equals(option.getValue());
        boolean hovered = isHovered() && enabled;

        anim.setChecked(on);

        int bx = getX() + width - BOX_SIZE - 12;
        int by = getY() + (height - BOX_SIZE) / 2;

        GuiRenderer.controlBox(context, bx, by, BOX_SIZE, BOX_SIZE, hovered, on, enabled);

        if (enabled) {
            float progress = anim.checkFill();
            if (progress > 0.02f) {
                int inset = anim.checkboxInset(BOX_SIZE);
                int innerSize = BOX_SIZE - inset * 2;
                if (innerSize > 0) {
                    int innerColor = hovered ? CaesiumTheme.accentBright() : CaesiumTheme.accent();
                    GuiRenderer.filledRoundedBox(context,
                        bx + inset, by + inset, innerSize, innerSize,
                        CaesiumTheme.RADIUS_CONTROL / 2, innerColor);
                }
            }
        }
    }

    @Override
    public void onClick(net.minecraft.client.gui.Click click, boolean doubled) {
        if (!this.active || !this.visible || !isOptionEnabled()) return;
        if (isTitleArea(click.x())) {
            if (onTitleClick != null) onTitleClick.accept(option);
            return;
        }
        this.playDownSound(MinecraftClient.getInstance().getSoundManager());
        option.setValue(!Boolean.TRUE.equals(option.getValue()));
    }
}