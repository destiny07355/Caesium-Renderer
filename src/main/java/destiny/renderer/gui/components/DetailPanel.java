package destiny.renderer.gui.components;

import destiny.renderer.gui.CaesiumFont;
import destiny.renderer.gui.anim.GuiAnimationHelper;
import destiny.renderer.gui.options.Option;
import destiny.renderer.gui.render.GuiRenderer;
import destiny.renderer.gui.theme.CaesiumTheme;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.OrderedText;

import java.util.List;

/**
 * Animated description panel activated by clicking/tapping a setting's name.
 * Stays open for 10 seconds (with a smooth countdown indicator) and automatically closes.
 */
public final class DetailPanel {

    private static final int PAD = 8;
    private static final int LINE_H = 9;
    private static final int CLOSE_SIZE = 18;

    private Option<?> activeOption = null;
    private boolean   isOpen       = false;

    private float animProgress = 0f; // 0 = closed, 1 = fully open

    private int x, y, width, height;

    /** Opens or refreshes the detail panel for the given option with a 10-second timeout. */
    public void open(Option<?> option) {
        if (option == null) return;
        this.activeOption = option;
        this.isOpen       = true;
    }

    public void close() {
        this.isOpen = false;
    }

    public boolean isOpen() {
        return isOpen || animProgress > 0.05f;
    }

    public float getAnimProgress() {
        return animProgress;
    }

    public void tick(float deltaSec) {
        float target = isOpen ? 1f : 0f;
        animProgress = GuiAnimationHelper.smoothDamp(animProgress, target, 18f, deltaSec);
        if (Math.abs(animProgress - target) < 0.01f) animProgress = target;
        if (animProgress <= 0.01f && !isOpen) {
            activeOption = null;
        }
    }

    public void setBounds(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public int getX()      { return x; }
    public int getY()      { return y; }
    public int getWidth()  { return width; }
    public int getHeight() { return height; }

    public boolean mouseClicked(double mx, double my, int btn) {
        if (!isOpen() || width <= 10) return false;
        // Close button check in top right of panel
        int closeBtnX = x + width - PAD - CLOSE_SIZE;
        int closeBtnY = y + 4;
        if (mx >= closeBtnX && mx <= closeBtnX + CLOSE_SIZE && my >= closeBtnY && my <= closeBtnY + CLOSE_SIZE) {
            close();
            return true;
        }
        return mx >= x && mx <= x + width && my >= y && my <= y + height;
    }

    public void render(DrawContext ctx, TextRenderer tr, int mouseX, int mouseY) {
        if (animProgress < 0.05f || width <= 10) return;

        ctx.enableScissor(x, y, x + width, y + height);

        // 1. Frosted background panel
        GuiRenderer.frostedPanel(ctx, x, y, width, height,
            CaesiumTheme.bgSidebar(), CaesiumTheme.borderSubtle(),
            CaesiumTheme.RADIUS_PANEL, false);

        // Accent left edge line
        ctx.fill(x, y + 4, x + 2, y + height - 4,
            (0xCC << 24) | CaesiumTheme.ACCENT_RGB);

        // Close button [X] in top-right
        int closeBtnX = x + width - PAD - CLOSE_SIZE;
        int closeBtnY = y + 4;
        boolean closeHovered = mouseX >= closeBtnX && mouseX <= closeBtnX + CLOSE_SIZE
                            && mouseY >= closeBtnY && mouseY <= closeBtnY + CLOSE_SIZE;
        GuiRenderer.filledRoundedBox(ctx, closeBtnX, closeBtnY, CLOSE_SIZE, CLOSE_SIZE,
            CaesiumTheme.RADIUS_BADGE, closeHovered ? 0x443D7DAD : 0x22000000);
        ctx.drawText(tr, CaesiumFont.text("×"), closeBtnX + 5, closeBtnY + 4,
            closeHovered ? CaesiumTheme.accentBright() : CaesiumTheme.TEXT_DISABLED, false);

        if (activeOption != null) {
            int innerX = x + PAD;
            int innerW = width - PAD * 2;
            int curY = y + PAD;

            // ---- Setting Name ----
            String name = activeOption.getName().getString();
            if (tr.getWidth(name) > innerW - 14) {
                name = tr.trimToWidth(name, Math.max(10, innerW - 18)) + "...";
            }
            ctx.drawText(tr, CaesiumFont.text(name), innerX, curY, CaesiumTheme.TEXT_PRIMARY, false);
            curY += 12;

            // ---- Impact Badge & Restart Tag ----
            String impactLabel = activeOption.getImpact().label().toUpperCase();
            int impactColor = activeOption.getImpact().color();
            int impactW = tr.getWidth(impactLabel) + 6;
            GuiRenderer.filledRoundedBox(ctx, innerX, curY, impactW, 10,
                CaesiumTheme.RADIUS_BADGE,
                0x40000000 | (impactColor & 0x00FFFFFF));
            ctx.drawText(tr, CaesiumFont.text(impactLabel), innerX + 3, curY + 1, impactColor, false);

            if (activeOption.isRequiresRestart()) {
                String restartLabel = "RESTART";
                int rw = tr.getWidth(restartLabel) + 6;
                int rx = innerX + impactW + 4;
                if (rx + rw <= x + width - PAD) {
                    GuiRenderer.filledRoundedBox(ctx, rx, curY, rw, 10,
                        CaesiumTheme.RADIUS_BADGE, 0x50FF4040);
                    ctx.drawText(tr, CaesiumFont.text(restartLabel), rx + 3, curY + 1,
                        0xFFFF9090, false);
                }
            }
            curY += 14;

            // ---- Divider ----
            ctx.fill(innerX, curY, innerX + innerW, curY + 1, (0x44 << 24) | CaesiumTheme.ACCENT_RGB);
            curY += 5;

            // ---- Concise Description ----
            String desc = activeOption.getDetailedExplanation();
            if (desc != null && !desc.isEmpty()) {
                List<OrderedText> lines = tr.wrapLines(CaesiumFont.text(desc), innerW);
                int maxLines = (y + height - curY - 32) / LINE_H;
                int lineCount = 0;
                for (OrderedText line : lines) {
                    if (lineCount++ >= maxLines) break;
                    ctx.drawText(tr, line, innerX, curY, CaesiumTheme.TEXT_SECONDARY, false);
                    curY += LINE_H;
                }
                curY += 3;
            }

            // ---- Additional Rationale / Recommendation ----
            String reason = !activeOption.isEnabled() ? activeOption.getDisabledReason() : activeOption.getDefaultReason();
            if (reason != null && !reason.isEmpty() && curY + LINE_H < y + height - 24) {
                ctx.fill(innerX, curY, innerX + innerW, curY + 1, (0x20 << 24) | CaesiumTheme.BORDER_STRONG_COLOR);
                curY += 4;
                List<OrderedText> rLines = tr.wrapLines(CaesiumFont.text(reason), innerW);
                int maxRLines = (y + height - curY - 20) / LINE_H;
                int count = 0;
                for (OrderedText line : rLines) {
                    if (count++ >= maxRLines) break;
                    ctx.drawText(tr, line, innerX, curY,
                        !activeOption.isEnabled() ? 0xFFFF8888 : CaesiumTheme.TEXT_DISABLED, false);
                    curY += LINE_H;
                }
            }

            // ---- 10-Second Auto-Close Progress Bar (at bottom) ----
        }

        ctx.disableScissor();
    }
}
