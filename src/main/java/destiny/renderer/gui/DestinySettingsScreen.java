package destiny.renderer.gui;

import destiny.renderer.config.RendererConfig;
import destiny.renderer.gui.anim.GuiAnimator;
import destiny.renderer.gui.components.CaesiumButton;
import destiny.renderer.gui.components.CaesiumScrollContainer;
import destiny.renderer.gui.components.CaesiumSearchBox;
import destiny.renderer.gui.components.CategorySidebar;
import destiny.renderer.gui.components.DetailPanel;
import destiny.renderer.gui.options.Option;
import destiny.renderer.gui.options.OptionGroup;
import destiny.renderer.gui.options.OptionPage;
import destiny.renderer.gui.options.control.ControlElement;
import destiny.renderer.gui.options.control.CyclingControlElement;
import destiny.renderer.gui.options.control.SliderControlElement;
import destiny.renderer.gui.options.control.TickBoxControlElement;
import destiny.renderer.gui.render.GuiRenderer;
import destiny.renderer.gui.theme.CaesiumTheme;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Caesium Main Settings Screen.
 *
 * <h2>Layout</h2>
 * <pre>
 *   ┌─────────────────────────────────────────────┐
 *   │  Header  (HEADER_H px)                      │
 *   ├──────────┬────────────────────┬─────────────┤
 *   │ Sidebar  │  Content Area      │ Detail Panel│
 *   │ (fixed W)│  (shrinks right ←) │ (slides in) │
 *   ├──────────┴────────────────────┴─────────────┤
 *   │  Footer  (FOOTER_H px)                      │
 *   └─────────────────────────────────────────────┘
 * </pre>
 *
 * <p>The detail panel slides in from the right when an option row is hovered.
 * The content area's right edge tracks the detail panel's left edge so they
 * never overlap — they share the available space cleanly.
 */
public final class DestinySettingsScreen extends Screen {

    private final Screen parent;
    private final List<OptionPage> pages;

    // ── Core components ─────────────────────────────────────────────────────
    private OptionPage         activePage;
    private CategorySidebar    sidebar;
    private CaesiumSearchBox   searchBox;
    private final CaesiumScrollContainer contentScroll = new CaesiumScrollContainer();
    private final DetailPanel  detailPanel = new DetailPanel();

    // ── Layout ──────────────────────────────────────────────────────────────
    /** Left edge of content area (fixed). */
    private int contentX;
    /** Top edge of content area (fixed). */
    private int contentY;
    /** Base width of content area when detail panel is fully closed. */
    private int baseContentW;
    /** Height of content area (fixed). */
    private int contentH;
    /**
     * Effective content width this frame — equals baseContentW minus the
     * current detail panel width. Updated every frame in render().
     */
    private int contentW;

    // ── Row widgets ──────────────────────────────────────────────────────────
    private final List<ControlElement<?>> rows       = new ArrayList<>();
    private final List<Integer>           rowVirtualY = new ArrayList<>();

    // ── Deferred page switch ─────────────────────────────────────────────────
    private OptionPage pendingPage = null;

    // ── Tab-switch animation ─────────────────────────────────────────────────
    private final GuiAnimator tabAnim = new GuiAnimator();

    // ── Panel open animation ─────────────────────────────────────────────────
    private final GuiAnimator openAnim = new GuiAnimator();

    // ── Misc ─────────────────────────────────────────────────────────────────
    private String searchQuery = "";
    private long   lastFrameNano = System.nanoTime();

    // ── Persistent state ─────────────────────────────────────────────────────
    private static String lastPageId      = null;
    private static float  lastScrollOffset = 0f;

    // =========================================================================
    // Construction
    // =========================================================================

    public DestinySettingsScreen(Screen parent) {
        super(Text.literal("Caesium Settings"));
        this.parent = parent;
        this.pages  = OptionRegistry.buildPages();

        OptionPage restored = null;
        if (lastPageId != null) {
            for (OptionPage p : pages) {
                if (p.getId().equals(lastPageId)) { restored = p; break; }
            }
        }
        this.activePage   = restored != null ? restored : (pages.isEmpty() ? null : pages.get(0));
        this.searchQuery  = "";

        for (OptionPage p : pages) {
            for (Option<?> o : p.allOptions()) o.markBaseline();
        }
    }

    // =========================================================================
    // init — called on screen open and on window resize
    // =========================================================================

    @Override
    protected void init() {
        int sidebarW = CaesiumTheme.SIDEBAR_W;
        int panelMargin = CaesiumTheme.PANEL_MARGIN;

        contentX   = sidebarW + panelMargin + 2;
        contentY   = CaesiumTheme.HEADER_H + panelMargin;
        // baseContentW leaves a gap on the right for the detail panel's open state
        // but the actual right constraint is just screenW - panelMargin
        baseContentW = this.width - contentX - panelMargin;
        contentH   = this.height - contentY - CaesiumTheme.FOOTER_H - panelMargin;
        contentW   = baseContentW;

        contentScroll.setBounds(contentX, contentY, contentW, contentH);

        // Sidebar
        sidebar = new CategorySidebar(pages, activePage, page -> {
            pendingPage = page;
            tabAnim.resetTab();
        });
        sidebar.setBounds(
            panelMargin,
            CaesiumTheme.HEADER_H + panelMargin,
            sidebarW,
            this.height - CaesiumTheme.HEADER_H - CaesiumTheme.FOOTER_H - panelMargin * 2
        );
        sidebar.setFilterQuery(searchQuery);

        // Header buttons
        int headerBtnY = (CaesiumTheme.HEADER_H - 20) / 2;
        int unlockW = 86;
        addDrawableChild(new CaesiumButton(this.width - unlockW - 28, headerBtnY, unlockW, 20,
            CaesiumFont.text(Option.unlockAllSettings ? "Unlocked" : "Unlock All"),
            Option.unlockAllSettings, false, () -> {
                Option.unlockAllSettings = !Option.unlockAllSettings;
                this.clearAndInit();
            }));

        addDrawableChild(new CaesiumButton(this.width - 24, headerBtnY, 18, 20,
            CaesiumFont.text("X"), false, false, this::saveAndClose));

        // Footer: search left, actions right
        int footerY  = this.height - CaesiumTheme.FOOTER_H + (CaesiumTheme.FOOTER_H - 22) / 2;
        int searchW  = Math.min(180, Math.max(110, this.width / 4));

        searchBox = new CaesiumSearchBox(this.textRenderer,
            contentX, footerY, searchW, CaesiumTheme.SEARCH_H,
            searchQuery, q -> {
                searchQuery = q == null ? "" : q.trim();
                if (sidebar != null) sidebar.setFilterQuery(searchQuery);
                contentScroll.setScrollInstant(0);
                rebuildRows();
            });

        addDrawableChild(new CaesiumButton(this.width - 210, footerY, 62, 22,
            CaesiumFont.text("Reset"), false, false, this::resetPageToDefaults));
        addDrawableChild(new CaesiumButton(this.width - 142, footerY, 64, 22,
            CaesiumFont.text("Apply"), false, false, this::applyWithoutClosing));
        addDrawableChild(new CaesiumButton(this.width - 72, footerY, 64, 22,
            CaesiumFont.text("Done"), true, false, this::saveAndClose));

        rebuildRows();

        if (lastScrollOffset > 0) contentScroll.setScrollInstant(lastScrollOffset);

        // Kick off open animation
        openAnim.setPanelOpen(true);
        // Tab starts visible
        tabAnim.setTabVisible(true);
        tabAnim.snapTabVisible();

        // Detail panel bounds — same as content area
        detailPanel.setBounds(this.width, this.height,
            contentY, contentH);
    }

    // =========================================================================
    // Row building
    // =========================================================================

    private void rebuildRows() {
        for (ControlElement<?> row : rows) remove(row);
        rows.clear();
        rowVirtualY.clear();

        if (activePage == null) return;

        String lq = searchQuery.toLowerCase();
        List<OptionGroup> groups = activePage.getMatchingGroups(lq);

        int y = 0;
        for (OptionGroup group : groups) {
            y += CaesiumTheme.SECTION_H;
            for (Option<?> opt : group.getOptions()) {
                ControlElement<?> el = createControl(opt, contentX, y, contentW - 14, CaesiumTheme.ROW_H);
                if (el != null) {
                    rows.add(el);
                    rowVirtualY.add(y);
                    addSelectableChild(el);
                    y += CaesiumTheme.ROW_H + CaesiumTheme.ROW_GAP;
                }
            }
            y += CaesiumTheme.GROUP_GAP;
        }
        contentScroll.setContentHeight(y);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ControlElement<?> createControl(Option<?> opt, int x, int y, int w, int h) {
        if (opt.isBoolean())               return new TickBoxControlElement((Option<Boolean>) opt, x, y, w, h);
        if (opt.isNumeric())               return new SliderControlElement(opt, x, y, w, h);
        if (!opt.getAllowedValues().isEmpty()) return new CyclingControlElement((Option) opt, x, y, w, h);
        return null;
    }

    // =========================================================================
    // Input
    // =========================================================================

    @Override
    public boolean mouseScrolled(double mx, double my, double hAmount, double vAmount) {
        if (sidebar != null && sidebar.mouseScrolled(mx, my, vAmount)) return true;
        if (contentScroll.mouseScrolled(mx, my, vAmount)) return true;
        return super.mouseScrolled(mx, my, hAmount, vAmount);
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean doubled) {
        if (searchBox != null && searchBox.mouseClicked(click, doubled)) return true;
        if (sidebar != null && sidebar.mouseClicked(click.x(), click.y(), click.button())) return true;
        if (contentScroll.mouseClicked(click.x(), click.y(), click.button())) return true;

        if (contentScroll.isOver(click.x(), click.y())) {
            float scroll = contentScroll.getScrollOffset();
            for (int i = 0; i < rows.size(); i++) {
                ControlElement<?> row = rows.get(i);
                int screenY = contentY + rowVirtualY.get(i) - (int) scroll;
                if (screenY + row.getHeight() <= contentY) continue;
                if (screenY >= contentY + contentH) break;
                if (click.x() >= row.getX() && click.x() < row.getX() + row.getWidth()
                 && click.y() >= screenY && click.y() < screenY + row.getHeight()) {
                    row.setY(screenY);
                    row.onClick(click, doubled);
                    setFocused(row);
                    return true;
                }
            }
            setFocused(null);
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.gui.Click click, double ox, double oy) {
        if (sidebar != null && sidebar.mouseDragged(click.y())) return true;
        if (contentScroll.mouseDragged(click.y())) return true;
        if (getFocused() instanceof SliderControlElement slider && slider.isDragging()) {
            slider.mouseDragged(click, ox, oy);
            return true;
        }
        return super.mouseDragged(click, ox, oy);
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.gui.Click click) {
        if (sidebar != null) sidebar.mouseReleased();
        contentScroll.mouseReleased();
        for (ControlElement<?> row : rows) {
            if (row instanceof SliderControlElement s) s.stopDragging();
        }
        return super.mouseReleased(click);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
        if (input.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) { cancelAndClose(); return true; }
        if (searchBox != null && searchBox.getTextField().keyPressed(input)) return true;
        return super.keyPressed(input);
    }

    @Override
    public boolean charTyped(net.minecraft.client.input.CharInput input) {
        if (searchBox != null && searchBox.getTextField().charTyped(input)) return true;
        return super.charTyped(input);
    }

    // =========================================================================
    // Rendering
    // =========================================================================

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // ── Delta time ──────────────────────────────────────────────────────
        long now = System.nanoTime();
        float dt = Math.max(0.001f, Math.min(0.1f, (now - lastFrameNano) / 1_000_000_000.0f));
        lastFrameNano = now;

        // ── Deferred page switch ─────────────────────────────────────────────
        if (pendingPage != null) {
            activePage  = pendingPage;
            pendingPage = null;
            contentScroll.setScrollInstant(0);
            if (sidebar != null) sidebar.setSelectedPage(activePage);
            rebuildRows();
            // Kick the tab animation
            tabAnim.resetTab();
            tabAnim.setTabVisible(true);
        }

        // ── Tick animations ──────────────────────────────────────────────────
        openAnim.tick(dt);
        tabAnim.tick(dt);
        contentScroll.updateAnimation(dt);
        if (sidebar != null) sidebar.update(dt);

        // ── Compute live content width based on detail panel ─────────────────
        // (detail panel ticks below, but we need last frame's width here)
        int dpW = detailPanel.currentWidth();
        contentW = baseContentW - dpW;
        // Clamp: content area must be at least 60px wide
        contentW = Math.max(60, contentW);
        contentScroll.setBounds(contentX, contentY, contentW - 6, contentH);

        // ── Panel open animation offsets ─────────────────────────────────────
        float openProgress = openAnim.panelOpen();
        int yOffset = openAnim.panelYOffset(); // 0 when fully open

        // ── 1. Screen background ─────────────────────────────────────────────
        GuiRenderer.screenBackground(context, this.width, this.height);

        // ── 2. Sidebar frosted panel ─────────────────────────────────────────
        if (sidebar != null) {
            int sx = CaesiumTheme.PANEL_MARGIN;
            int sy = CaesiumTheme.HEADER_H + CaesiumTheme.PANEL_MARGIN + yOffset;
            int sw = CaesiumTheme.SIDEBAR_W;
            int sh = this.height - CaesiumTheme.HEADER_H - CaesiumTheme.FOOTER_H - CaesiumTheme.PANEL_MARGIN * 2;
            GuiRenderer.animatedSidebarPanel(context, sx, sy, sw, sh, openProgress);
            sidebar.render(context, mouseX, mouseY);
        }

        // ── 3. Content frosted panel ─────────────────────────────────────────
        GuiRenderer.animatedContentPanel(context,
            contentX, contentY + yOffset,
            contentW, contentH,
            openProgress);

        // ── 4. Header & Footer bands ─────────────────────────────────────────
        drawHeaderFooter(context);

        // ── 5. Buttons, search box (via super) ───────────────────────────────
        super.render(context, mouseX, mouseY, delta);
        if (searchBox != null) searchBox.render(context, mouseX, mouseY, delta);

        // ── 6. Content rows (with tab slide animation) ────────────────────────
        Option<?> hoveredOption = drawContent(context, mouseX, mouseY, delta, tabAnim);

        // ── 7. Detail panel ───────────────────────────────────────────────────
        detailPanel.setHovered(hoveredOption);
        detailPanel.tick(dt);
        detailPanel.setBounds(this.width, this.height, contentY, contentH);
        detailPanel.render(context, this.textRenderer);
    }

    // ── Header / Footer ──────────────────────────────────────────────────────

    private void drawHeaderFooter(DrawContext ctx) {
        // Header band
        GuiRenderer.frostedPanel(ctx,
            0, 0, this.width, CaesiumTheme.HEADER_H,
            CaesiumTheme.bgSidebar(), CaesiumTheme.borderSubtle(),
            0, false);

        ctx.drawText(this.textRenderer, CaesiumFont.text("CAESIUM"),
            CaesiumTheme.PANEL_MARGIN + 2, (CaesiumTheme.HEADER_H - 8) / 2,
            CaesiumTheme.TEXT_PRIMARY, false);

        if (activePage != null) {
            String sub = "  •  " + activePage.getName().getString();
            int titleW = this.textRenderer.getWidth("CAESIUM");
            ctx.drawText(this.textRenderer, CaesiumFont.text(sub),
                CaesiumTheme.PANEL_MARGIN + 2 + titleW, (CaesiumTheme.HEADER_H - 8) / 2,
                CaesiumTheme.accent(), false);
        }

        // Footer band
        GuiRenderer.frostedPanel(ctx,
            0, this.height - CaesiumTheme.FOOTER_H, this.width, CaesiumTheme.FOOTER_H,
            CaesiumTheme.bgSidebar(), CaesiumTheme.borderSubtle(),
            0, false);
    }

    // ── Content rows ─────────────────────────────────────────────────────────

    private Option<?> drawContent(DrawContext ctx, int mouseX, int mouseY, float delta,
                                  GuiAnimator tabAnim) {
        if (activePage == null) return null;

        // Tab animation offsets — content slides in from the right
        int tabXOff  = tabAnim.tabXOffset();
        int tabAlpha = tabAnim.tabAlpha();

        contentScroll.beginScissor(ctx);

        Option<?> hovered = null;
        String lq = searchQuery.toLowerCase();
        List<OptionGroup> groups = activePage.getMatchingGroups(lq);
        float scroll = contentScroll.getScrollOffset();

        int y = 0;
        int rowIndex = 0;

        for (OptionGroup group : groups) {
            int headY = contentY + y - (int) scroll;
            if (headY + CaesiumTheme.SECTION_H >= contentY && headY < contentY + contentH) {
                GuiRenderer.sectionHeader(ctx, this.textRenderer,
                    contentX + CaesiumTheme.ITEM_INDENT + tabXOff,
                    headY, contentW - CaesiumTheme.ITEM_INDENT,
                    group.getTitle().getString());
            }
            y += CaesiumTheme.SECTION_H;

            for (int i = 0; i < group.getOptions().size(); i++) {
                if (rowIndex >= rows.size()) break;
                ControlElement<?> row = rows.get(rowIndex);
                int screenY = contentY + rowVirtualY.get(rowIndex) - (int) scroll;
                rowIndex++;

                if (screenY + CaesiumTheme.ROW_H >= contentY && screenY < contentY + contentH) {
                    // Apply tab slide X offset
                    row.setX(contentX + tabXOff);
                    row.setY(screenY);
                    // Adjust row width to current (possibly shrunken) content area
                    row.setWidth(contentW - 14);
                    row.render(ctx, mouseX, mouseY, delta);

                    boolean inX = mouseX >= contentX && mouseX < contentX + contentW;
                    boolean inY = mouseY >= Math.max(screenY, contentY)
                               && mouseY <  Math.min(screenY + row.getHeight(), contentY + contentH);
                    if (inX && inY) hovered = row.getOption();
                }
                y += CaesiumTheme.ROW_H + CaesiumTheme.ROW_GAP;
            }
            y += CaesiumTheme.GROUP_GAP;
        }

        if (groups.isEmpty()) {
            GuiRenderer.text(ctx, this.textRenderer,
                "No settings match \"" + searchQuery + "\"",
                contentX + 8, contentY + 12, CaesiumTheme.TEXT_DISABLED);
        }

        contentScroll.endScissor(ctx);
        contentScroll.renderScrollbar(ctx, mouseX, mouseY);

        return hovered;
    }

    // =========================================================================
    // Persistence & Actions
    // =========================================================================

    private void resetPageToDefaults() {
        if (activePage == null) return;
        for (Option<?> o : activePage.allOptions()) o.resetToDefault();
    }

    private void applyWithoutClosing() {
        persist();
        reloadIfNeeded();
        for (OptionPage p : pages) for (Option<?> o : p.allOptions()) o.markBaseline();
    }

    private void saveAndClose() {
        persist();
        reloadIfNeeded();
        rememberPosition();
        if (this.client != null) this.client.setScreen(parent);
    }

    private void cancelAndClose() {
        for (OptionPage p : pages) for (Option<?> o : p.allOptions()) o.revert();
        persist();
        rememberPosition();
        if (this.client != null) this.client.setScreen(parent);
    }

    private void rememberPosition() {
        if (activePage != null) lastPageId = activePage.getId();
        lastScrollOffset = contentScroll.getScrollOffset();
    }

    private void reloadIfNeeded() {
        for (OptionPage p : pages) {
            for (Option<?> o : p.allOptions()) {
                if (o.isRequiresReload() && o.isModified()) {
                    MinecraftClient mc = MinecraftClient.getInstance();
                    if (mc != null) { try { mc.reloadResources(); } catch (Throwable ignored) {} }
                    return;
                }
            }
        }
    }

    private void persist() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.options != null) mc.options.write();
        try { RendererConfig.save(FabricLoader.getInstance().getConfigDir()); } catch (Throwable ignored) {}
    }

    @Override public void removed()        { persist(); }
    @Override public boolean shouldPause() { return false; }
}