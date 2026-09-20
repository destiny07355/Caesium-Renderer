package destiny.renderer.gui.components;

import destiny.renderer.gui.CaesiumFont;
import destiny.renderer.gui.render.GuiRenderer;
import destiny.renderer.gui.theme.CaesiumTheme;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.function.Consumer;

/**
 * Styled search input field.
 * Uses {@link GuiRenderer#inputField} for rendering with animated focus glow.
 */
public final class CaesiumSearchBox {

    private final TextFieldWidget field;
    private int x, y, width, height;
    private float focusProgress = 0f;

    public CaesiumSearchBox(TextRenderer textRenderer, int x, int y, int width, int height,
                            String initialQuery, Consumer<String> onQueryChanged) {
        this.x = x; this.y = y; this.width = width; this.height = height;

        int padY = Math.max(0, (height - 9) / 2);
        this.field = new TextFieldWidget(textRenderer, x + 8, y + padY, width - 16, height - 4,
            Text.literal("Search"));
        this.field.setPlaceholder(CaesiumFont.text("Search settings..."));
        this.field.setTextShadow(false);
        this.field.setDrawsBackground(false);
        this.field.setMaxLength(64);
        this.field.setText(initialQuery != null ? initialQuery : "");
        this.field.setChangedListener(onQueryChanged);
    }

    public void setBounds(int x, int y, int width, int height) {
        this.x = x; this.y = y; this.width = width; this.height = height;
        int padY = Math.max(0, (height - 9) / 2);
        this.field.setX(x + 8);
        this.field.setY(y + padY);
        this.field.setWidth(width - 16);
    }

    public TextFieldWidget getTextField() { return field; }
    public String getQuery()              { return field.getText(); }
    public void   setQuery(String q)      { field.setText(q != null ? q : ""); }

    public boolean isOver(double mx, double my) {
        return mx >= x && mx <= x + width && my >= y && my <= y + height;
    }

    public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean doubled) {
        if (isOver(click.x(), click.y())) {
            field.setFocused(true);
            field.onClick(click, doubled);
            return true;
        }
        field.setFocused(false);
        return false;
    }

    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        boolean focused = field.isFocused();
        boolean hovered = isOver(mouseX, mouseY);

        // Animate focus glow
        float target = focused ? 1f : 0f;
        float speed  = focused ? CaesiumTheme.ANIM_SPEED_FOCUS : CaesiumTheme.ANIM_SPEED_FOCUS;
        focusProgress = focusProgress + (target - focusProgress)
            * (float)(1.0 - Math.exp(-speed * Math.max(0.001f, delta)));

        GuiRenderer.inputField(ctx, x, y, width, height, hovered, focused, focusProgress);
        field.render(ctx, mouseX, mouseY, delta);
    }
}
