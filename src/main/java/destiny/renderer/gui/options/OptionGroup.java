package destiny.renderer.gui.options;

import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * A titled cluster of related options rendered as one block within a page.
 */
public final class OptionGroup {

    private final Text title;
    private final List<Option<?>> options;

    private OptionGroup(Text title, List<Option<?>> options) {
        this.title = title;
        this.options = options;
    }

    public Text getTitle() { return title; }

    public List<Option<?>> getOptions() { return options; }

    /** @return only the options matching a lowercase search query. */
    public List<Option<?>> getMatching(String lowerQuery) {
        if (lowerQuery == null || lowerQuery.isEmpty()) return options;
        List<Option<?>> out = new ArrayList<>();
        for (Option<?> o : options) {
            if (o.matches(lowerQuery)) out.add(o);
        }
        return out;
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private Text title = Text.empty();
        private final List<Option<?>> options = new ArrayList<>();

        public Builder title(String text) {
            this.title = Text.literal(text);
            return this;
        }

        public Builder title(Text text) {
            this.title = text;
            return this;
        }

        public Builder add(Option<?> option) {
            if (option != null) this.options.add(option);
            return this;
        }

        public OptionGroup build() {
            return new OptionGroup(title, List.copyOf(options));
        }
    }
}
