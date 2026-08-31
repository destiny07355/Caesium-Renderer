package destiny.renderer.gui.options.control;

import destiny.renderer.gui.CaesiumFont;
import destiny.renderer.gui.anim.GuiAnimator;
import destiny.renderer.gui.options.Option;
import destiny.renderer.gui.render.GuiRenderer;
import destiny.renderer.gui.theme.CaesiumTheme;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.ClickableWidget;

/**
 * Base class for all option row widgets.
 * Uses {@link GuiAnimator} for hover animation and {@link GuiRenderer} for drawing.
 */
public abstract class ControlElement<T> extends ClickableWidget {

    protected final Option<T> option;
    protected static final int CONTROL_WIDTH = 100;

    /** Per-row hover animation state. */
    protected final GuiAnimator anim = new GuiAnimator();

    protected ControlElement(Option<T> option, int x, int y, int width, int height) {
        super(x, y, width, height, option.getName());
        this.option = option;
    }

    public Option<T> getOption()      { return option; }
    protected boolean isOptionEnabled() { return option.isEnabled(); }

    @Override
    protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
        if (!this.visible) return;

        boolean enabled = isOptionEnabled();
        boolean hovered = this.isHovered() && enabled;

        // Tick hover animation
        anim.setHovered(hovered);
        anim.tick(Math.max(0.001f, Math.min(0.1f, delta)));

        // Animated hover highlight
        GuiRenderer.rowHoverAnimated(context, getX(), getY(), width, height, anim.hover());

        // Non-default accent bar on left edge
        if (option.isNonDefault() && enabled) {
            GuiRenderer.rowModifiedBar(context, getX(), getY(), height);
        }

        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        int titleColor = !enabled ? CaesiumTheme.TEXT_DISABLED : CaesiumTheme.TEXT_PRIMARY;
        int descColor  = !enabled ? CaesiumTheme.TEXT_DISABLED : CaesiumTheme.TEXT_SECONDARY;

        int labelMaxW = width - CONTROL_WIDTH - 20;
        String title = option.getName().getString();
        if (tr.getWidth(title) > labelMaxW) {
            title = tr.trimToWidth(title, labelMaxW - tr.getWidth("...")) + "...";
        }

        String desc = option.getTooltip() != null ? option.getTooltip().getString() : "";
        if (tr.getWidth(desc) > labelMaxW) {
            desc = tr.trimToWidth(desc, labelMaxW - tr.getWidth("...")) + "...";
        }

        if (height >= 30 && !desc.isEmpty()) {
            context.drawText(tr, CaesiumFont.text(title), getX() + CaesiumTheme.ITEM_INDENT, getY() + 6,  titleColor, false);
            context.drawText(tr, CaesiumFont.text(desc),  getX() + CaesiumTheme.ITEM_INDENT, getY() + 18, descColor,  false);
        } else {
            context.drawText(tr, CaesiumFont.text(title), getX() + CaesiumTheme.ITEM_INDENT,
                getY() + (height - 8) / 2, titleColor, false);
        }

        renderControl(context, mouseX, mouseY, delta, enabled);
    }

    protected abstract void renderControl(DrawContext context, int mouseX, int mouseY,
                                          float delta, boolean enabled);

    protected int controlX() {
        return getX() + width - CONTROL_WIDTH - 8;
    }

    @Override
    protected void appendClickableNarrations(NarrationMessageBuilder builder) {
        builder.put(net.minecraft.client.gui.screen.narration.NarrationPart.TITLE,
            option.getName().getString() + ": " + option.getFormattedValue());
    }
}
