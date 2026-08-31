package destiny.renderer.gui.options;

import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * One tab of the settings screen: a named collection of {@link OptionGroup}s.
 */
public final class OptionPage {

    private final String id;
    private final Text name;
    private final List<OptionGroup> groups;

    public OptionPage(String id, Text name, List<OptionGroup> groups) {
        this.id = id;
        this.name = name;
        this.groups = List.copyOf(groups);
    }

    public String getId()               { return id; }
    public Text   getName()             { return name; }
    public List<OptionGroup> getGroups() { return groups; }

    public OptionPage withAppendedGroups(List<OptionGroup> additional) {
        if (additional == null || additional.isEmpty()) return this;
        List<OptionGroup> combined = new ArrayList<>(this.groups);
        combined.addAll(additional);
        return new OptionPage(this.id, this.name, combined);
    }

    /** @return every option on this page, flattened. */
    public List<Option<?>> allOptions() {
        List<Option<?>> out = new ArrayList<>();
        for (OptionGroup g : groups) out.addAll(g.getOptions());
        return out;
    }

    /** @return groups filtered by search query, omitting groups with no matches. */
    public List<OptionGroup> getMatchingGroups(String lowerQuery) {
        if (lowerQuery == null || lowerQuery.isEmpty()) return groups;
        List<OptionGroup> out = new ArrayList<>();
        for (OptionGroup g : groups) {
            List<Option<?>> matching = g.getMatching(lowerQuery);
            if (matching.isEmpty()) continue;
            OptionGroup.Builder b = OptionGroup.builder().title(g.getTitle());
            matching.forEach(b::add);
            out.add(b.build());
        }
        return out;
    }

    /** @return true when at least one option on this page matches the query. */
    public boolean hasMatch(String lowerQuery) {
        if (lowerQuery == null || lowerQuery.isEmpty()) return true;
        for (OptionGroup g : groups) {
            for (Option<?> o : g.getOptions()) {
                if (o.matches(lowerQuery)) return true;
            }
        }
        return false;
    }

    /** @return total number of matching options on this page for a search query. */
    public int countMatches(String lowerQuery) {
        if (lowerQuery == null || lowerQuery.isEmpty()) return 0;
        int count = 0;
        for (OptionGroup g : groups) {
            for (Option<?> o : g.getOptions()) {
                if (o.matches(lowerQuery)) count++;
            }
        }
        return count;
    }

    /** Re-reads every option's value from its backing state. */
    public void refreshAll() {
        for (OptionGroup g : groups) {
            for (Option<?> o : g.getOptions()) o.refresh();
        }
    }
}
