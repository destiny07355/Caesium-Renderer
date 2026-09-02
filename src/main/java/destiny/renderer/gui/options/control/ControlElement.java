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

import java.util.function.Consumer;

/**
 * Base class for all option row widgets.
 * Features a single clean centered label without duplicated bottom descriptions,
 * strict hover clipping, and click-to-open description support.
 */
public abstract class ControlElement<T> extends ClickableWidget {

    protected final Option<T> option;
    protected static final int CONTROL_WIDTH = 90;

    /** Per-row hover animation state. */
    protected final GuiAnimator anim = new GuiAnimator();

    /** Callback invoked when the user clicks the setting's name / label area. */
    public Consumer<Option<?>> onTitleClick = null;

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

        // Animated hover highlight strictly clipped to row rect
        if (anim.hover() > 0.01f) {
            GuiRenderer.rowHoverAnimated(context, getX(), getY(), width, height, anim.hover());
        }

        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        int titleColor = !enabled ? CaesiumTheme.TEXT_DISABLED
                       : (hovered && mouseX < controlX() ? CaesiumTheme.accentBright() : CaesiumTheme.TEXT_PRIMARY);

        int labelMaxW = width - CONTROL_WIDTH - 16;
        String title = option.getName().getString();
        if (tr.getWidth(title) > labelMaxW) {
            title = tr.trimToWidth(title, Math.max(10, labelMaxW - tr.getWidth("..."))) + "...";
        }

        // Single clean vertically centered title without clutter
        int titleY = getY() + (height - 8) / 2;
        context.drawText(tr, CaesiumFont.text(title), getX() + CaesiumTheme.ITEM_INDENT,
            titleY, titleColor, false);

        renderControl(context, mouseX, mouseY, delta, enabled);
    }

    protected abstract void renderControl(DrawContext context, int mouseX, int mouseY,
                                          float delta, boolean enabled);

    public int controlX() {
        return getX() + width - CONTROL_WIDTH - 6;
    }

    public boolean isTitleArea(double mx) {
        return mx >= getX() && mx < controlX();
    }

    @Override
    public void onClick(net.minecraft.client.gui.Click click, boolean doubled) {
        if (isTitleArea(click.x()) && onTitleClick != null) {
            onTitleClick.accept(option);
            return;
        }
        super.onClick(click, doubled);
    }

    @Override
    protected void appendClickableNarrations(NarrationMessageBuilder builder) {
        builder.put(net.minecraft.client.gui.screen.narration.NarrationPart.TITLE,
            option.getName().getString() + ": " + option.getFormattedValue());
    }
}
