package destiny.renderer.api;

import destiny.renderer.gui.options.OptionGroup;
import destiny.renderer.gui.options.OptionPage;

/**
 * Public extension API allowing third-party Fabric mods to inject custom settings pages
 * and option groups directly into Caesium's settings interface.
 *
 * <h2>Usage in fabric.mod.json</h2>
 * <pre>
 * "entrypoints": {
 *   "caesium:settings": [
 *     "com.example.mod.CaesiumSettingsIntegration"
 *   ]
 * }
 * </pre>
 */
public interface CaesiumSettingsExtension {

    /**
     * Invoked when Caesium constructs the settings screen.
     *
     * @param registry registrar to add pages and groups
     */
    void registerSettings(SettingsRegistry registry);

    /**
     * Registration interface for adding custom pages and option groups.
     */
    interface SettingsRegistry {

        /**
         * Adds a brand new top-level page/tab in the sidebar.
         */
        void addPage(OptionPage page);

        /**
         * Injects an option group into an existing page (e.g. "performance", "quality", "general").
         */
        void addGroup(String targetPageId, OptionGroup group);
    }
}
