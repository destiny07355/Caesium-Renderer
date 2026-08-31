package destiny.renderer.gui.components;

import destiny.renderer.gui.render.GuiRenderer;
import destiny.renderer.gui.theme.CaesiumTheme;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;

/**
 * Caesium themed button widget.
 * All rendering delegated to {@link GuiRenderer}.
 */
public final class CaesiumButton extends ClickableWidget {

    private final Runnable onPress;
    private final boolean primary;
    private final boolean danger;
    private float pressFlash = 0f;

    public CaesiumButton(int x, int y, int width, int height, Text message,
                         boolean primary, boolean danger, Runnable onPress) {
        super(x, y, width, height, message);
        this.primary = primary;
        this.danger  = danger;
        this.onPress = onPress;
    }

    public CaesiumButton(int x, int y, int width, int height, Text message,
                         boolean primary, Runnable onPress) {
        this(x, y, width, height, message, primary, false, onPress);
    }

    @Override
    protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
        // Decay press flash
        pressFlash = Math.max(0f, pressFlash - delta * (CaesiumTheme.ANIM_SPEED_BUTTON_PRESS / 60f));

        GuiRenderer.button(context,
            MinecraftClient.getInstance().textRenderer,
            getX(), getY(), width, height,
            getMessage().getString(),
            isHovered() && this.active,
            primary, danger, this.active,
            pressFlash);
    }

    @Override
    public void onClick(net.minecraft.client.gui.Click click, boolean doubled) {
        if (!this.active || !this.visible) return;
        playDownSound(MinecraftClient.getInstance().getSoundManager());
        pressFlash = 1f;
        if (onPress != null) onPress.run();
    }

    @Override
    protected void appendClickableNarrations(
            net.minecraft.client.gui.screen.narration.NarrationMessageBuilder builder) {
        builder.put(net.minecraft.client.gui.screen.narration.NarrationPart.TITLE,
            getMessage().getString());
    }
}
