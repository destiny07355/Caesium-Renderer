package destiny.renderer.gui;

import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

/**
 * Minecraft-native text helper.
 * Uses vanilla Minecraft's native font renderer directly without injecting custom fonts.
 */
public final class CaesiumFont {

    private CaesiumFont() {}

    /** A plain text node rendered in the standard Minecraft font. */
    public static MutableText text(String string) {
        return Text.literal(string != null ? string : "");
    }

    /** Returns the text node directly for vanilla font rendering. */
    public static Text withFont(Text text) {
        return text != null ? text : Text.empty();
    }
}

