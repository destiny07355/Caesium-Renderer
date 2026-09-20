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
import destiny.renderer.gui.options.control.LabelControlElement;
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
import java.util.Locale;

/**
 * Caesium Main Settings Screen.
 *
 * <h2>Layout & Ratio System</h2>
 * <ul>
 *   <li>Normalized, centered dialog sizing across all Minecraft GUI scales (1, 2, 3, 4).</li>
 *   <li>When description is open: 1 (Sidebar) : 3 (Main Content) : 1 (Description).</li>
 *   <li>When description is closed: 1 (Sidebar) : 4 (Main Content).</li>
 *   <li>Clicking a setting's name opens the description panel for 10 seconds.</li>
 *   <li>First-open quick tip tutorial popup.</li>
 *   <li>Bottom-left circular quick-action button.</li>
 * </ul>
 */
public final class DestinySettingsScreen extends Screen {

    private static final int TUTORIAL_W = 260;
    private static final int TUTORIAL_H = 100;
    private static final int TUTORIAL_CLOSE_SIZE = 18;

    private final Screen parent;
    private List<OptionPage> pages;

    // ── Core components ─────────────────────────────────────────────────────
    private OptionPage         activePage;
    private CategorySidebar    sidebar;
    private CaesiumSearchBox   searchBox;
    private final CaesiumScrollContainer contentScroll = new CaesiumScrollContainer();
    private final DetailPanel  detailPanel = new DetailPanel();

    // ── Centered Menu Bounds ─────────────────────────────────────────────────
    private int menuX, menuY, menuW, menuH;
    private int sidebarX, sidebarY, sidebarW, sidebarH;
    private int contentX, contentY, contentW, contentH;
    private int detailX, detailY, detailW, detailH;

    // ── Row widgets ──────────────────────────────────────────────────────────
    private final List<ControlElement<?>> rows       = new ArrayList<>();
    private final List<Integer>           rowVirtualY = new ArrayList<>();

    // ── Deferred page switch ─────────────────────────────────────────────────
    private OptionPage pendingPage = null;

    // ── Animations ───────────────────────────────────────────────────────────
    private final GuiAnimator tabAnim = new GuiAnimator();
    private final GuiAnimator openAnim = new GuiAnimator();

    // ── Tutorial Popup ───────────────────────────────────────────────────────
    private boolean showTutorialPopup = false;

    // ── Search & Timing ──────────────────────────────────────────────────────
    private String searchQuery = "";
    private long   lastFrameNano = System.nanoTime();

    // ── Persistent state ─────────────────────────────────────────────────────
    private static String lastPageId      = null;
    private static float  lastScrollOffset = 0f;

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

        // First-open tutorial check
        this.showTutorialPopup = !RendererConfig.get().firstOpenTutorialShown;

        for (OptionPage p : pages) {
            for (Option<?> o : p.allOptions()) o.markBaseline();
        }
    }

    @Override
    protected void init() {
        // Compute fixed visual dialog bounds centered on screen
        int marginX = Math.max(12, this.width / 18);
        int marginY = Math.max(10, this.height / 18);
        // Do not let the preferred desktop size spill beyond the logical
        // Minecraft screen. At GUI scale 3/4 a 420px minimum can otherwise
        // produce negative menu coordinates and unreachable controls.
        int minMenuW = Math.min(340, Math.max(1, this.width - 24));
        int minMenuH = Math.min(200, Math.max(1, this.height - 20));
        menuW = Math.min(480, Math.max(minMenuW, this.width - marginX * 2));
        menuH = Math.min(270, Math.max(minMenuH, this.height - marginY * 2));
        menuX = (this.width - menuW) / 2;
        menuY = (this.height - menuH) / 2;

        recalculateLayout();

        // Header buttons
        int headerBtnY = menuY + (CaesiumTheme.HEADER_H - 18) / 2;

        addDrawableChild(new CaesiumButton(menuX + menuW - 44, headerBtnY, 18, 18,
            CaesiumFont.text("?"), false, false, () -> this.showTutorialPopup = true));

        addDrawableChild(new CaesiumButton(menuX + menuW - 22, headerBtnY, 18, 18,
            CaesiumFont.text("×"), false, false, this::saveAndClose));

        // Footer buttons
        int footerY = menuY + menuH - CaesiumTheme.FOOTER_H + (CaesiumTheme.FOOTER_H - 18) / 2;

        // Bottom-left Advanced / Simple mode toggle
        RendererConfig cfg = RendererConfig.get();
        int toggleW = 76;
        addDrawableChild(new CaesiumButton(menuX + 8, footerY, toggleW, 18,
            CaesiumFont.text(cfg.advancedMenu ? "Advanced" : "Simple"),
            cfg.advancedMenu, false, () -> {
                cfg.advancedMenu = !cfg.advancedMenu;
                this.pages = OptionRegistry.buildPages(cfg.advancedMenu);
                this.activePage = this.pages.isEmpty() ? null : this.pages.get(0);
                this.sidebar = null;
                for (OptionPage p : pages) {
                    for (Option<?> o : p.allOptions()) o.markBaseline();
                }
                this.clearAndInit();
            }));

        int footerActionsX = menuX + menuW - 176;
        int searchBoxX = menuX + 8 + toggleW + 6;
        int searchW = Math.min(140, Math.max(48, footerActionsX - searchBoxX - 8));

        searchBox = new CaesiumSearchBox(this.textRenderer,
            searchBoxX, menuY + menuH - CaesiumTheme.FOOTER_H + (CaesiumTheme.FOOTER_H - CaesiumTheme.SEARCH_H) / 2,
            searchW, CaesiumTheme.SEARCH_H,
            searchQuery, q -> {
                searchQuery = q == null ? "" : q.trim();
                if (sidebar != null) sidebar.setFilterQuery(searchQuery);
                contentScroll.setScrollInstant(0);
                rebuildRows();
            });

        addDrawableChild(new CaesiumButton(menuX + menuW - 176, footerY, 52, 18,
            CaesiumFont.text("Reset"), false, false, this::resetPageToDefaults));
        addDrawableChild(new CaesiumButton(menuX + menuW - 120, footerY, 54, 18,
            CaesiumFont.text("Apply"), false, false, this::applyWithoutClosing));
        addDrawableChild(new CaesiumButton(menuX + menuW - 62, footerY, 54, 18,
            CaesiumFont.text("Done"), true, false, this::saveAndClose));

        rebuildRows();

        if (lastScrollOffset > 0) contentScroll.setScrollInstant(lastScrollOffset);

        // The shell, child widgets, and hitboxes must share the same bounds.
        // Opening atomically avoids a brief state where only the drawn panels
        // are translated while buttons and input targets remain in place.
        openAnim.setPanelOpen(true);
        tabAnim.setTabVisible(true);
        tabAnim.snapTabVisible();
    }

    private void recalculateLayout() {
        int innerW = menuW - (CaesiumTheme.PANEL_MARGIN * 2);
        int topY   = menuY + CaesiumTheme.HEADER_H + 4;
        int botY   = menuY + menuH - CaesiumTheme.FOOTER_H - 4;
        int innerH = botY - topY;

        // A three-column view needs enough room for a useful description.
        // On smaller logical resolutions keep the main list full-width and
        // show details as a modal overlay instead.
        boolean descOpen = detailPanel.isOpen();
        boolean overlayDetail = useDetailOverlay(innerW);
        if (descOpen && !overlayDetail) {
            // Ratio 1 : 3 : 1 (Sidebar : Main : Description)
            sidebarW = innerW / 5;
            contentW = (innerW * 3) / 5;
            detailW  = innerW - sidebarW - contentW - 8;
        } else {
            // Ratio 1 : 4 (Sidebar : Main)
            sidebarW = innerW / 5;
            contentW = innerW - sidebarW - 4;
            detailW  = 0;
        }

        sidebarX = menuX + CaesiumTheme.PANEL_MARGIN;
        sidebarY = topY;
        sidebarH = innerH;

        contentX = sidebarX + sidebarW + 4;
        contentY = topY;
        contentH = innerH;

        if (overlayDetail) {
            detailX = contentX;
            detailY = contentY;
            detailW = contentW;
            detailH = contentH;
        } else {
            detailX = contentX + contentW + 4;
            detailY = topY;
            detailH = innerH;
        }

        if (sidebar == null) {
            sidebar = new CategorySidebar(pages, activePage, page -> {
                pendingPage = page;
                tabAnim.resetTab();
            });
        }
        sidebar.setBounds(sidebarX, sidebarY, sidebarW, sidebarH);
        sidebar.setFilterQuery(searchQuery);

        contentScroll.setBounds(contentX, contentY, contentW, contentH);
        detailPanel.setBounds(detailX, detailY, detailW, detailH);
    }

    /** Below this width the former three-way split left the details panel unreadable. */
    private static boolean useDetailOverlay(int innerWidth) {
        return innerWidth < 520;
    }

    private void rebuildRows() {
        for (ControlElement<?> row : rows) remove(row);
        rows.clear();
        rowVirtualY.clear();

        if (activePage == null) return;

        String lq = searchQuery.toLowerCase(Locale.ROOT);
        List<OptionGroup> groups = activePage.getMatchingGroups(lq);

        int y = 0;
        for (OptionGroup group : groups) {
            y += CaesiumTheme.SECTION_H;
            for (Option<?> opt : group.getOptions()) {
                ControlElement<?> el = createControl(opt, contentX, y, contentW - 10, CaesiumTheme.ROW_H);
                if (el != null) {
                    el.onTitleClick = o -> {
                        detailPanel.open(o);
                        recalculateLayout();
                        rebuildRows();
                    };
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
        if (opt.isBoolean()) return new TickBoxControlElement((Option<Boolean>) opt, x, y, w, h);
        if (opt.isNumeric()) return new SliderControlElement(opt, x, y, w, h);
        if (!opt.getAllowedValues().isEmpty()) return new CyclingControlElement((Option) opt, x, y, w, h);
        return new LabelControlElement(opt, x, y, w, h);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hAmount, double vAmount) {
        if (showTutorialPopup) return true;
        if (sidebar != null && sidebar.mouseScrolled(mx, my, vAmount)) return true;
        if (contentScroll.mouseScrolled(mx, my, vAmount)) return true;
        return super.mouseScrolled(mx, my, hAmount, vAmount);
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean doubled) {
        if (showTutorialPopup) {
            // Dismiss tutorial on button click or click outside
            int popX = (this.width - TUTORIAL_W) / 2, popY = (this.height - TUTORIAL_H) / 2;
            int btnX = popX + (TUTORIAL_W - 60) / 2, btnY = popY + TUTORIAL_H - 24;
            int closeX = popX + TUTORIAL_W - TUTORIAL_CLOSE_SIZE - 6;
            int closeY = popY + 6;
            if (click.x() >= closeX && click.x() <= closeX + TUTORIAL_CLOSE_SIZE
                    && click.y() >= closeY && click.y() <= closeY + TUTORIAL_CLOSE_SIZE) {
                dismissTutorial();
                return true;
            }
            if (click.x() >= btnX && click.x() <= btnX + 60 && click.y() >= btnY && click.y() <= btnY + 18) {
                dismissTutorial();
                return true;
            }
            dismissTutorial();
            return true;
        }

        if (detailPanel.mouseClicked(click.x(), click.y(), click.button())) {
            recalculateLayout();
            rebuildRows();
            return true;
        }

        if (searchBox != null && searchBox.mouseClicked(click, doubled)) {
            setFocused(searchBox.getTextField());
            return true;
        }

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

    private void dismissTutorial() {
        showTutorialPopup = false;
        RendererConfig.get().firstOpenTutorialShown = true;
        try { RendererConfig.save(FabricLoader.getInstance().getConfigDir()); } catch (Throwable ignored) {}
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.gui.Click click, double ox, double oy) {
        if (showTutorialPopup) return true;
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
        if (showTutorialPopup) {
            dismissTutorial();
            return true;
        }
        if (input.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            cancelAndClose();
            return true;
        }
        if (searchBox != null && searchBox.getTextField().keyPressed(input)) return true;
        return super.keyPressed(input);
    }

    @Override
    public boolean charTyped(net.minecraft.client.input.CharInput input) {
        if (showTutorialPopup) return true;
        if (searchBox != null && searchBox.getTextField().charTyped(input)) return true;
        return super.charTyped(input);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        long now = System.nanoTime();
        float dt = Math.max(0.001f, Math.min(0.1f, (now - lastFrameNano) / 1_000_000_000.0f));
        lastFrameNano = now;

        if (pendingPage != null) {
            activePage  = pendingPage;
            pendingPage = null;
            contentScroll.setScrollInstant(0);
            if (sidebar != null) sidebar.setSelectedPage(activePage);
            rebuildRows();
            tabAnim.resetTab();
            tabAnim.setTabVisible(true);
        }

        boolean prevDescOpen = detailPanel.isOpen();
        openAnim.tick(dt);
        tabAnim.tick(dt);
        contentScroll.updateAnimation(dt);
        detailPanel.tick(dt);
        if (sidebar != null) sidebar.update(dt);

        if (prevDescOpen != detailPanel.isOpen()) {
            recalculateLayout();
            rebuildRows();
        }

        float openProgress = openAnim.panelOpen();
        // Keep visual and input coordinates identical during entry motion.
        int yOffset = 0;

        // 1. Full solid opaque backdrop (100% blocking game world/chat/hotbar)
        GuiRenderer.screenBackground(context, this.width, this.height);

        // 2. Main dialog card base
        GuiRenderer.frostedPanel(context, menuX, menuY + yOffset, menuW, menuH,
            CaesiumTheme.bgContent(), CaesiumTheme.borderStrong(),
            CaesiumTheme.RADIUS_PANEL, true);

        // 3. Left category sidebar
        if (sidebar != null) {
            GuiRenderer.animatedSidebarPanel(context,
                sidebarX, sidebarY + yOffset, sidebarW, sidebarH, openProgress);
            sidebar.render(context, mouseX, mouseY);
        }

        // 4. Middle settings list panel
        GuiRenderer.animatedContentPanel(context,
            contentX, contentY + yOffset, contentW, contentH, openProgress);

        // 5. Right description panel on wide screens. Narrow screens render
        // it after the list as an overlay, so it stays readable.
        boolean detailOverlay = useDetailOverlay(menuW - (CaesiumTheme.PANEL_MARGIN * 2));
        if (detailPanel.isOpen() && !detailOverlay) {
            detailPanel.render(context, this.textRenderer, mouseX, mouseY);
        }

        // 6. Header and footer chrome bands (rounded to match card)
        drawHeaderFooter(context, yOffset, mouseX, mouseY);

        // 7. Buttons and Search box
        super.render(context, mouseX, mouseY, delta);
        if (searchBox != null) searchBox.render(context, mouseX, mouseY, delta);

        // 8. Content list rows
        drawContent(context, mouseX, mouseY, dt, tabAnim);

        if (detailPanel.isOpen() && detailOverlay) {
            detailPanel.render(context, this.textRenderer, mouseX, mouseY);
        }

        // 9. First-open Tutorial Popup Modal
        if (showTutorialPopup) {
            drawTutorialPopup(context, mouseX, mouseY);
        }
    }

    private void drawHeaderFooter(DrawContext ctx, int yOff, int mx, int my) {
        // Header
        GuiRenderer.frostedPanel(ctx,
            menuX, menuY + yOff, menuW, CaesiumTheme.HEADER_H,
            CaesiumTheme.bgSidebar(), CaesiumTheme.borderSubtle(),
            CaesiumTheme.RADIUS_PANEL, false);

        ctx.drawText(this.textRenderer, CaesiumFont.text("CAESIUM"),
            menuX + CaesiumTheme.PANEL_MARGIN + 2, menuY + yOff + (CaesiumTheme.HEADER_H - 8) / 2,
            CaesiumTheme.TEXT_PRIMARY, false);

        if (activePage != null) {
            String sub = " / " + activePage.getName().getString();
            int titleW = this.textRenderer.getWidth("CAESIUM");
            ctx.drawText(this.textRenderer, CaesiumFont.text(sub),
                menuX + CaesiumTheme.PANEL_MARGIN + 2 + titleW, menuY + yOff + (CaesiumTheme.HEADER_H - 8) / 2,
                CaesiumTheme.accent(), false);
        }

        // Footer
        int footY = menuY + menuH - CaesiumTheme.FOOTER_H + yOff;
        GuiRenderer.frostedPanel(ctx,
            menuX, footY, menuW, CaesiumTheme.FOOTER_H,
            CaesiumTheme.bgSidebar(), CaesiumTheme.borderSubtle(),
            CaesiumTheme.RADIUS_PANEL, false);
    }

    private void drawContent(DrawContext ctx, int mouseX, int mouseY, float delta,
                             GuiAnimator tabAnim) {
        if (activePage == null) return;

        int tabXOff = tabAnim.tabXOffset();
        contentScroll.beginScissor(ctx);

        String lq = searchQuery.toLowerCase(Locale.ROOT);
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
                    row.setX(contentX + tabXOff);
                    row.setY(screenY);
                    row.setWidth(contentW - 8);
                    row.render(ctx, mouseX, mouseY, delta);
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
    }

    private void drawTutorialPopup(DrawContext ctx, int mx, int my) {
        // Dimmed backdrop over menu
        ctx.fill(0, 0, this.width, this.height, 0x80000000);

        int popW = TUTORIAL_W, popH = TUTORIAL_H;
        int popX = (this.width - popW) / 2;
        int popY = (this.height - popH) / 2;

        GuiRenderer.frostedPanel(ctx, popX, popY, popW, popH,
            CaesiumTheme.bgSidebar(), CaesiumTheme.borderAccent(),
            CaesiumTheme.RADIUS_PANEL, true);

        ctx.drawText(this.textRenderer, CaesiumFont.text("QUICK TIP"),
            popX + 12, popY + 10, CaesiumTheme.accentBright(), false);

        int closeX = popX + popW - TUTORIAL_CLOSE_SIZE - 6;
        int closeY = popY + 6;
        boolean closeHover = mx >= closeX && mx <= closeX + TUTORIAL_CLOSE_SIZE
                && my >= closeY && my <= closeY + TUTORIAL_CLOSE_SIZE;
        GuiRenderer.filledRoundedBox(ctx, closeX, closeY, TUTORIAL_CLOSE_SIZE, TUTORIAL_CLOSE_SIZE,
            CaesiumTheme.RADIUS_BADGE, closeHover ? 0x443D7DAD : 0x22000000);
        ctx.drawText(this.textRenderer, CaesiumFont.text("×"), closeX + 5, closeY + 4,
            closeHover ? CaesiumTheme.accentBright() : CaesiumTheme.TEXT_DISABLED, false);

        String msg = "Click a setting's name to open its detailed description panel.";
        List<net.minecraft.text.OrderedText> lines = this.textRenderer.wrapLines(CaesiumFont.text(msg), popW - 24);
        int ty = popY + 28;
        for (net.minecraft.text.OrderedText line : lines) {
            ctx.drawText(this.textRenderer, line, popX + 12, ty, CaesiumTheme.TEXT_PRIMARY, false);
            ty += 11;
        }

        int btnW = 60, btnH = 18;
        int btnX = popX + (popW - btnW) / 2;
        int btnY = popY + popH - 24;
        boolean btnHover = mx >= btnX && mx <= btnX + btnW && my >= btnY && my <= btnY + btnH;
        GuiRenderer.button(ctx, this.textRenderer, btnX, btnY, btnW, btnH,
            "Got it!", btnHover, true, false, true, 0f);
    }

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
        boolean needsResource = false;
        boolean needsWorld = false;

        for (OptionPage p : pages) {
            for (Option<?> o : p.allOptions()) {
                if (o.isModified()) {
                    if (o.isRequiresResourceReload()) needsResource = true;
                    if (o.isRequiresWorldReload()) needsWorld = true;
                }
            }
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return;

        if (needsResource) {
            try { mc.reloadResources(); } catch (Throwable ignored) {}
        } else if (needsWorld && mc.worldRenderer != null) {
            try { mc.worldRenderer.reload(); } catch (Throwable ignored) {}
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
