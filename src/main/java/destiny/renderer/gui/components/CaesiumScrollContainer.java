package destiny.renderer.gui.components;

import destiny.renderer.gui.anim.GuiAnimationHelper;
import destiny.renderer.gui.theme.CaesiumTheme;
import destiny.renderer.gui.render.GuiRenderer;
import net.minecraft.client.gui.DrawContext;

/**
 * Reusable smooth-scroll viewport with scissor clipping and a scrollbar.
 * All rendering delegated to {@link GuiRenderer}.
 */
public final class CaesiumScrollContainer {

    private int x, y, width, height;
    private int contentHeight = 0;
    private float scrollOffset = 0;
    private float scrollTarget  = 0;
    private boolean draggingThumb = false;

    public void setBounds(int x, int y, int width, int height) {
        this.x = x; this.y = y; this.width = width; this.height = height;
        clampScroll();
    }

    public void setContentHeight(int contentHeight) {
        this.contentHeight = Math.max(0, contentHeight);
        clampScroll();
    }

    public int  getX()            { return x; }
    public int  getY()            { return y; }
    public int  getWidth()        { return width; }
    public int  getHeight()       { return height; }
    public int  getContentHeight(){ return contentHeight; }
    public float getScrollOffset(){ return scrollOffset; }

    public void setScrollInstant(float v) {
        scrollTarget = v; scrollOffset = v; clampScroll();
    }

    public int maxScroll() { return Math.max(0, contentHeight - height); }

    private void clampScroll() {
        scrollTarget = Math.max(0, Math.min(maxScroll(), scrollTarget));
        scrollOffset = Math.max(0, Math.min(maxScroll(), scrollOffset));
    }

    public boolean mouseScrolled(double mx, double my, double amount) {
        if (!isOver(mx, my)) return false;
        scrollTarget -= (float)(amount * 26.0);
        clampScroll();
        return true;
    }

    public boolean isOver(double mx, double my) {
        return mx >= x && mx <= x + width && my >= y && my <= y + height;
    }

    private int thumbX() { return x + width - 5; }

    private boolean overThumb(double mx, double my) {
        return maxScroll() > 0 && mx >= thumbX() - 1 && mx <= thumbX() + 6
            && my >= y && my <= y + height;
    }

    public boolean mouseClicked(double mx, double my, int btn) {
        if (overThumb(mx, my)) { draggingThumb = true; dragTo(my); return true; }
        return false;
    }

    public boolean mouseDragged(double my) {
        if (!draggingThumb) return false;
        dragTo(my); return true;
    }

    public void mouseReleased() { draggingThumb = false; }

    private void dragTo(double my) {
        double frac = (my - y) / (double) height;
        scrollTarget = (float)(frac * maxScroll());
        clampScroll();
    }

    public void updateAnimation(float dt) {
        scrollOffset = GuiAnimationHelper.smoothDamp(scrollOffset, scrollTarget, 22f, dt);
        if (Math.abs(scrollTarget - scrollOffset) < 0.3f) scrollOffset = scrollTarget;
    }

    public void beginScissor(DrawContext ctx) { ctx.enableScissor(x, y, x + width, y + height); }
    public void endScissor(DrawContext ctx)   { ctx.disableScissor(); }

    public void renderScrollbar(DrawContext ctx, int mx, int my) {
        boolean hot = draggingThumb || overThumb(mx, my);
        GuiRenderer.scrollbar(ctx, thumbX(), y, height, scrollOffset, contentHeight, hot);
    }
}
