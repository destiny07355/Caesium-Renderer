package destiny.renderer.gui.options;

import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A single configurable setting: its metadata, its binding to backing state, and the
 * constraints the UI needs to render an appropriate control.
 *
 * <h2>Changes from the original implementation</h2>
 * <ul>
 *   <li><b>Values are no longer snapshotted at construction.</b> The old version cached
 *       {@code getter.get()} in the constructor, so any change made elsewhere (a preset
 *       being applied, another screen, a keybind) was invisible until the screen was
 *       recreated. {@link #refresh()} re-reads on demand.</li>
 *   <li><b>Ranges are explicit.</b> Slider bounds used to be guessed by string-matching
 *       the option's display name, which broke the moment an option was renamed.</li>
 *   <li><b>Revert is supported.</b> {@link #revert()} restores the value present when the
 *       screen opened, so Cancel can genuinely cancel.</li>
 *   <li><b>Impact is a typed enum</b> rather than a pre-coloured string.</li>
 * </ul>
 *
 * @param <T> the value type (Boolean, Integer, Float, Double, or an Enum)
 */
public final class Option<T> {

    /** Performance characterisation shown in the details panel. */
    public enum Impact {
        NONE     ("Cosmetic",      0xFF9E9E9E),
        LOW      ("Low",           0xFF8BC34A),
        MEDIUM   ("Medium",        0xFFFFC107),
        HIGH     ("High",          0xFFFF9800),
        EXTREME  ("Very High",     0xFFFF5252),
        VARIES   ("Varies",        0xFF64B5F6);

        private final String label;
        private final int color;

        Impact(String label, int color) { this.label = label; this.color = color; }
        public String label() { return label; }
        public int color()    { return color; }
    }

    private final String id;
    private final Text name;
    private final Text tooltip;
    private final Impact impact;
    private final Supplier<T> getter;
    private final Consumer<T> setter;
    private final T defaultValue;

    /** Value at screen-open time, used by {@link #revert()}. */
    private T originalValue;

    private T value;

    // Numeric constraints (unused for non-numeric options)
    private double min = 0.0;
    private double max = 1.0;
    private double step = 1.0;

    /** Allowed values for cycling controls. */
    private List<T> allowedValues = Collections.emptyList();

    /** Renders a value as display text. */
    private Function<T, String> formatter = String::valueOf;

    /** Additional search keywords beyond the display name. */
    private final List<String> keywords = new ArrayList<>();

    /** When false the control is drawn greyed out and cannot be changed. */
    private Supplier<Boolean> enabledPredicate = () -> true;

    /** Explanation shown when the option is disabled. */
    private String disabledReason = "";

    /** Detailed explanation of what the option does. */
    private String detailedExplanation = "";

    /** Why the option is enabled or disabled by default. */
    private String defaultReason = "";

    /** Global override switch to unlock all locked settings for power users. */
    public static volatile boolean unlockAllSettings = false;

    /** True when changing this requires a world/renderer reload to take effect. */
    private boolean requiresReload = false;
    private boolean requiresResourceReload = false;
    private boolean requiresWorldReload = false;
    private boolean requiresRestart = false;

    public Option(String id, Text name, Text tooltip, Impact impact,
                  Supplier<T> getter, Consumer<T> setter, T defaultValue) {
        this.id = id;
        this.name = name;
        this.tooltip = tooltip;
        this.impact = impact;
        this.getter = getter;
        this.setter = setter;
        this.defaultValue = defaultValue;
        this.value = getter.get();
        this.originalValue = this.value;
    }

    // -------------------------------------------------------------------------
    // Builder-style configuration
    // -------------------------------------------------------------------------

    public Option<T> range(double min, double max, double step) {
        this.min = min;
        this.max = max;
        this.step = step;
        return this;
    }

    public Option<T> values(List<T> allowed) {
        this.allowedValues = List.copyOf(allowed);
        return this;
    }

    public Option<T> format(Function<T, String> formatter) {
        this.formatter = formatter;
        return this;
    }

    public Option<T> keywords(String... words) {
        Collections.addAll(this.keywords, words);
        return this;
    }

    public Option<T> enabledWhen(Supplier<Boolean> predicate, String reasonWhenDisabled) {
        this.enabledPredicate = predicate;
        this.disabledReason = reasonWhenDisabled;
        return this;
    }

    public Option<T> explanation(String explanation) {
        this.detailedExplanation = explanation;
        return this;
    }

    public Option<T> defaultReason(String reason) {
        this.defaultReason = reason;
        return this;
    }

    public Option<T> requiresReload() {
        this.requiresReload = true;
        this.requiresWorldReload = true;
        return this;
    }

    public Option<T> requiresResourceReload() {
        this.requiresResourceReload = true;
        return this;
    }

    public Option<T> requiresWorldReload() {
        this.requiresWorldReload = true;
        return this;
    }

    public Option<T> requiresRestart() {
        this.requiresRestart = true;
        return this;
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public String  getId()      { return id; }
    public Text    getName()    { return name; }
    public Text    getTooltip() { return tooltip; }
    public Impact  getImpact()  { return impact; }
    public T       getValue()   { return value; }
    public T       getDefaultValue() { return defaultValue; }
    public double  getMin()     { return min; }
    public double  getMax()     { return max; }
    public double  getStep()    { return step; }
    public List<T> getAllowedValues() { return allowedValues; }
    public boolean isRequiresReload() { return requiresReload; }
    public boolean isRequiresResourceReload() { return requiresResourceReload; }
    public boolean isRequiresWorldReload() { return requiresWorldReload || requiresReload; }
    public boolean isRequiresRestart() { return requiresRestart; }
    public String  getDisabledReason() { return disabledReason; }
    public String  getDetailedExplanation() { return detailedExplanation != null && !detailedExplanation.isEmpty() ? detailedExplanation : tooltip != null ? tooltip.getString() : ""; }
    public String  getDefaultReason() { return defaultReason != null && !defaultReason.isEmpty() ? defaultReason : disabledReason; }

    public boolean isEnabled() {
        if (unlockAllSettings) return true;
        try {
            return enabledPredicate.get();
        } catch (Exception e) {
            return true;
        }
    }

    public String getFormattedValue() {
        try {
            return formatter.apply(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    /** @return true when the current value differs from the value at screen open. */
    public boolean isModified() {
        return value != null && !value.equals(originalValue);
    }

    /** @return true when the current value differs from the shipped default. */
    public boolean isNonDefault() {
        return value != null && !value.equals(defaultValue);
    }

    // -------------------------------------------------------------------------
    // Mutation
    // -------------------------------------------------------------------------

    /**
     * Sets the value and applies it immediately to the backing state, so the effect is
     * visible behind the settings screen without needing to close it.
     */
    public void setValue(T newValue) {
        if (newValue == null) return;
        this.value = newValue;
        apply();
    }

    /** Writes the current value through to the backing state. */
    public void apply() {
        try {
            setter.accept(value);
            // Keep the hot-path fast-rejection flags in sync with whatever just changed.
            destiny.renderer.config.RendererConfig.get().recomputeDerivedFlags();
        } catch (Exception e) {
            // A single bad setter must never take down the whole settings screen.
            java.util.logging.Logger.getLogger("Caesium/Option")
                .warning("Failed to apply option '" + id + "': " + e);
        }
    }

    /** Re-reads the value from the backing state, discarding any local edit. */
    public void refresh() {
        try {
            this.value = getter.get();
        } catch (Exception ignored) {
            // Keep the last known good value.
        }
    }

    /** Marks the current value as the baseline that {@link #revert()} returns to. */
    public void markBaseline() {
        refresh();
        this.originalValue = this.value;
    }

    /** Restores and applies the value captured by the last {@link #markBaseline()}. */
    public void revert() {
        if (originalValue != null && !originalValue.equals(value)) {
            this.value = originalValue;
            apply();
        }
    }

    /** Restores and applies the shipped default. */
    public void resetToDefault() {
        setValue(defaultValue);
    }

    // -------------------------------------------------------------------------
    // Search
    // -------------------------------------------------------------------------

    /** @return true when this option matches a lowercase search query. */
    public boolean matches(String lowerQuery) {
        if (lowerQuery == null || lowerQuery.isEmpty()) return true;
        if (name.getString().toLowerCase().contains(lowerQuery)) return true;
        if (tooltip.getString().toLowerCase().contains(lowerQuery)) return true;
        for (String kw : keywords) {
            if (kw.toLowerCase().contains(lowerQuery)) return true;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Type helpers used by the control factory
    // -------------------------------------------------------------------------

    public boolean isBoolean() { return value instanceof Boolean; }
    public boolean isInteger() { return value instanceof Integer; }
    public boolean isFloat()   { return value instanceof Float; }
    public boolean isDouble()  { return value instanceof Double; }
    public boolean isEnum()    { return value instanceof Enum<?>; }
    public boolean isNumeric() { return isInteger() || isFloat() || isDouble(); }

    /** @return the current value as a double, for slider rendering. */
    public double asDouble() {
        if (value instanceof Number n) return n.doubleValue();
        return 0.0;
    }
}
