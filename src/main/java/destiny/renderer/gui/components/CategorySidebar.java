package destiny.renderer.gui.components;

import destiny.renderer.gui.CaesiumFont;
import destiny.renderer.gui.options.OptionPage;
import destiny.renderer.gui.render.GuiRenderer;
import destiny.renderer.gui.theme.CaesiumTheme;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ClickableWidget;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Scrollable sidebar category list.
 * Renders via {@link GuiRenderer} — no raw fill calls.
 */
public final class CategorySidebar {

    private final List<OptionPage> pages;
    private final Consumer<OptionPage> onSelect;
    private final CaesiumScrollContainer scroll = new CaesiumScrollContainer();
    private final List<OptionPage> itemList = new ArrayList<>();

    private int x, y, width, height;
    private OptionPage selectedPage;
    private String filterQuery = "";

    private static final int ITEM_H   = 26;
    private static final int ITEM_GAP = 2;
    private static final int PAD_TOP  = 6;

    public CategorySidebar(List<OptionPage> pages, OptionPage initial, Consumer<OptionPage> onSelect) {
        this.pages = pages;
        this.selectedPage = initial;
        this.onSelect = onSelect;
        this.itemList.addAll(pages);
    }

    public void setBounds(int x, int y, int width, int height) {
        this.x = x; this.y = y; this.width = width; this.height = height;
        scroll.setBounds(x, y, width, height);
        updateHeight();
    }

    public void setSelectedPage(OptionPage page) { this.selectedPage = page; }
    public OptionPage getSelectedPage()           { return selectedPage; }

    public void setFilterQuery(String q) {
        this.filterQuery = q == null ? "" : q.toLowerCase().trim();
        updateHeight();
    }

    private void updateHeight() {
        int h = PAD_TOP + itemList.size() * (ITEM_H + ITEM_GAP) + 4;
        scroll.setContentHeight(h);
    }

    public boolean mouseScrolled(double mx, double my, double amount) {
        return scroll.mouseScrolled(mx, my, amount);
    }

    public boolean mouseClicked(double mx, double my, int button) {
        if (!scroll.isOver(mx, my)) return false;
        if (scroll.mouseClicked(mx, my, button)) return true;

        int curY = y + PAD_TOP - (int) scroll.getScrollOffset();
        for (OptionPage page : itemList) {
            int rowY = Math.max(y, curY);
            int rowY2 = Math.min(y + height, curY + ITEM_H);
            if (my >= rowY && my <= rowY2 && mx >= x + 4 && mx <= x + width - 4) {
                if (page.hasMatch(filterQuery)) {
                    // sound
                    MinecraftClient mc = MinecraftClient.getInstance();
                    if (mc != null) {
                        try { mc.getSoundManager().play(
                            net.minecraft.client.sound.PositionedSoundInstance.ui(
                                net.minecraft.sound.SoundEvents.UI_BUTTON_CLICK, 1.0f)); }
                        catch (Throwable ignored) {}
                    }
                    selectedPage = page;
                    onSelect.accept(page);
                    return true;
                }
            }
            curY += ITEM_H + ITEM_GAP;
        }
        return true;
    }

    public boolean mouseDragged(double my) { return scroll.mouseDragged(my); }
    public void mouseReleased()            { scroll.mouseReleased(); }
    public void update(float dt)           { scroll.updateAnimation(dt); }

    public void render(DrawContext ctx, int mx, int my) {
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;

        scroll.beginScissor(ctx);

        int curY = y + PAD_TOP - (int) scroll.getScrollOffset();
        for (OptionPage page : itemList) {
            int itemX = x + 4;
            int itemW = width - 8;

            if (curY + ITEM_H >= y && curY <= y + height) {
                boolean selected = page == selectedPage;
                boolean hasMatch = page.hasMatch(filterQuery);
                boolean hovered  = mx >= itemX && mx <= itemX + itemW
                                && my >= Math.max(y, curY) && my <= Math.min(y + height, curY + ITEM_H);

                if (selected) {
                    GuiRenderer.rowSelected(ctx, itemX, curY, itemW, ITEM_H);
                } else if (hovered && hasMatch) {
                    GuiRenderer.rowHover(ctx, itemX, curY, itemW, ITEM_H);
                }

                int textColor = !hasMatch    ? CaesiumTheme.TEXT_DISABLED
                              : selected     ? CaesiumTheme.TEXT_PRIMARY
                              : hovered      ? CaesiumTheme.TEXT_PRIMARY
                              :                CaesiumTheme.TEXT_SECONDARY;

                String label = page.getName().getString().toUpperCase();
                int textX = itemX + (selected ? 9 : 7);
                int textY = curY + (ITEM_H - 8) / 2;

                String trimmed = tr.getWidth(label) > itemW - 16
                    ? tr.trimToWidth(label, itemW - 20) + "..."
                    : label;
                ctx.drawText(tr, CaesiumFont.text(trimmed), textX, textY, textColor, false);

                // Search match badge
                int matchCount = page.countMatches(filterQuery);
                if (matchCount > 0 && !filterQuery.isEmpty()) {
                    String badge = String.valueOf(matchCount);
                    int bw = tr.getWidth(badge);
                    int bx = itemX + itemW - bw - 8;
                    int by = curY + (ITEM_H - 10) / 2;
                    GuiRenderer.filledRoundedBox(ctx, bx - 3, by - 1, bw + 6, 12, 3, CaesiumTheme.accent());
                    ctx.drawText(tr, CaesiumFont.text(badge), bx, by + 1, CaesiumTheme.TEXT_PRIMARY, false);
                }
            }
            curY += ITEM_H + ITEM_GAP;
        }

        scroll.endScissor(ctx);
        scroll.renderScrollbar(ctx, mx, my);
    }
}
