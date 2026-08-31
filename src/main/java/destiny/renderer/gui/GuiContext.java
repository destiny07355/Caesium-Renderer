package destiny.renderer.gui;

import net.minecraft.client.MinecraftClient;

/**
 * Utility: answers whether the Caesium settings screen is currently active.
 *
 * <p>All animation-heavy GUI code (GuiAnimator ticks, GuiRenderer frosted panels,
 * DetailPanel etc.) is already instantiated only inside {@link DestinySettingsScreen},
 * so they cannot run during gameplay by construction. This class provides an
 * explicit guard for any code that could <em>theoretically</em> be called from
 * both a screen context and a game-render context — such as shared utilities
 * or overlays that import from the gui package.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 *   if (!GuiContext.isSettingsOpen()) return; // skip — not in settings screen
 * }</pre>
 */
public final class GuiContext {

    private GuiContext() {}

    /**
     * @return true only when the Caesium settings screen is the current foreground screen.
     *         Always false during normal gameplay, loading screens, or any other screen.
     */
    public static boolean isSettingsOpen() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc != null && mc.currentScreen instanceof DestinySettingsScreen;
    }

    /**
     * @return true when ANY screen is open (gameplay is paused or overlaid).
     *         Safe to call from the render thread at any time.
     */
    public static boolean isAnyScreenOpen() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc != null && mc.currentScreen != null;
    }

    /**
     * @return true when the game world is active and the player is in the world
     *         with no screen open — i.e. normal gameplay.
     */
    public static boolean isInGameplay() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc != null && mc.world != null && mc.currentScreen == null;
    }
}
