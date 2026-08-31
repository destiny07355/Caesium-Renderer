package destiny.renderer.particle;

import net.minecraft.particle.ParticleType;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * Registry-driven particle management subsystem.
 *
 * <p>Discovers 100% of particles dynamically from {@link Registries#PARTICLE_TYPE},
 * categorises them, assigns priority, and manages tri-state override rules (INHERIT / ON / OFF).
 */
public final class CaesiumParticleRegistry {

    private static final Logger LOGGER = Logger.getLogger("Caesium/ParticleRegistry");

    public enum Category {
        BLOCK_EFFECTS("Block Effects"),
        COMBAT("Combat"),
        FIRE_SMOKE("Fire & Smoke"),
        FLUIDS("Fluids"),
        MAGIC("Magic"),
        WEATHER("Weather"),
        ENVIRONMENT("Environment"),
        REDSTONE("Redstone"),
        EXPLOSIONS("Explosions"),
        ENTITY("Entity"),
        OTHER("Other");

        private final String displayName;
        Category(String name) { this.displayName = name; }
        public String getDisplayName() { return displayName; }
    }

    public enum Priority {
        CRITICAL,
        HIGH,
        NORMAL,
        LOW
    }

    public enum OverrideState {
        INHERIT,
        ON,
        OFF
    }

    public enum QualityPreset {
        ALL("All"),
        DECREASED("Decreased"),
        MINIMAL("Minimal"),
        OFF("Off"),
        CUSTOM("Custom");

        private final String title;
        QualityPreset(String title) { this.title = title; }
        public String getTitle() { return title; }
    }

    public enum CategoryTriState {
        ALL_ON("☑"),
        ALL_OFF("☐"),
        MIXED("—");

        private final String symbol;
        CategoryTriState(String s) { this.symbol = s; }
        public String getSymbol() { return symbol; }
    }

    public static final class ParticleEntry {
        private final ParticleType<?> type;
        private final Identifier id;
        private final String displayName;
        private final Category category;
        private final Priority priority;
        private volatile OverrideState override = OverrideState.INHERIT;

        public ParticleEntry(ParticleType<?> type, Identifier id, String displayName, Category category, Priority priority) {
            this.type = type;
            this.id = id;
            this.displayName = displayName;
            this.category = category;
            this.priority = priority;
        }

        public ParticleType<?> getType()     { return type; }
        public Identifier getId()            { return id; }
        public String getDisplayName()       { return displayName; }
        public Category getCategory()        { return category; }
        public Priority getPriority()        { return priority; }
        public OverrideState getOverride()   { return override; }
        public void setOverride(OverrideState state) { this.override = state; }

        public boolean isEffectiveEnabled(QualityPreset preset) {
            if (override == OverrideState.ON) return true;
            if (override == OverrideState.OFF) return false;

            // Inherit from preset
            return switch (preset) {
                case ALL -> true;
                case DECREASED -> true; // Handled by frequency sampling
                case MINIMAL -> priority == Priority.CRITICAL || priority == Priority.HIGH;
                case OFF -> priority == Priority.CRITICAL; // Only gameplay critical
                case CUSTOM -> true;
            };
        }
    }

    private static final List<ParticleEntry> ENTRIES = new CopyOnWriteArrayList<>();
    private static final Map<ParticleType<?>, ParticleEntry> BY_TYPE = new ConcurrentHashMap<>();
    private static volatile QualityPreset activePreset = QualityPreset.ALL;
    private static volatile boolean initialized = false;

    private CaesiumParticleRegistry() {}

    /**
     * Discovers all particles registered in Minecraft and categorises them dynamically.
     */
    public static synchronized void initialize() {
        if (initialized) return;
        ENTRIES.clear();
        BY_TYPE.clear();

        try {
            try {
                net.minecraft.Bootstrap.initialize();
            } catch (Throwable ignored) {}

            for (Identifier id : Registries.PARTICLE_TYPE.getIds()) {
                ParticleType<?> type = Registries.PARTICLE_TYPE.get(id);
                if (type == null) continue;

                String path = id.getPath();
                Category cat = classifyCategory(path);
                Priority prio = classifyPriority(type, path);
                String name = formatDisplayName(id);

                ParticleEntry entry = new ParticleEntry(type, id, name, cat, prio);
                ENTRIES.add(entry);
                BY_TYPE.put(type, entry);
            }
        } catch (Throwable t) {
            LOGGER.warning("[Caesium] Failed to enumerate particle registry: " + t);
        }

        // Sort entries alphabetically by display name
        ENTRIES.sort(Comparator.comparing(ParticleEntry::getDisplayName));
        initialized = true;
        LOGGER.info("[Caesium] Discovered " + ENTRIES.size() + " particles dynamically from registry.");
    }

    public static List<ParticleEntry> getAllParticles() {
        if (!initialized) initialize();
        return ENTRIES;
    }

    public static List<ParticleEntry> getFiltered(Category category, String search) {
        if (!initialized) initialize();
        String q = (search == null) ? "" : search.trim().toLowerCase(Locale.ROOT);

        List<ParticleEntry> result = new ArrayList<>();
        for (ParticleEntry e : ENTRIES) {
            if (category != null && e.getCategory() != category) continue;
            if (!q.isEmpty()) {
                if (!e.getDisplayName().toLowerCase(Locale.ROOT).contains(q) &&
                    !e.getId().toString().toLowerCase(Locale.ROOT).contains(q)) {
                    continue;
                }
            }
            result.add(e);
        }
        return result;
    }

    public static QualityPreset getPreset() {
        return activePreset;
    }

    public static void setPreset(QualityPreset preset) {
        activePreset = preset;
        if (preset != QualityPreset.CUSTOM) {
            // Reset overrides to INHERIT so preset takes full control
            for (ParticleEntry e : ENTRIES) {
                e.setOverride(OverrideState.INHERIT);
            }
        }
    }

    public static void toggleParticle(ParticleEntry entry) {
        if (entry == null) return;
        boolean currentlyEnabled = entry.isEffectiveEnabled(activePreset);
        entry.setOverride(currentlyEnabled ? OverrideState.OFF : OverrideState.ON);
        activePreset = QualityPreset.CUSTOM;
    }

    public static void enableAll(Category category, String search) {
        List<ParticleEntry> list = getFiltered(category, search);
        for (ParticleEntry e : list) {
            e.setOverride(OverrideState.ON);
        }
        activePreset = QualityPreset.CUSTOM;
    }

    public static void disableAll(Category category, String search) {
        List<ParticleEntry> list = getFiltered(category, search);
        for (ParticleEntry e : list) {
            e.setOverride(OverrideState.OFF);
        }
        activePreset = QualityPreset.CUSTOM;
    }

    public static CategoryTriState getCategoryState(Category cat) {
        List<ParticleEntry> inCat = getFiltered(cat, null);
        if (inCat.isEmpty()) return CategoryTriState.ALL_ON;

        int enabled = 0;
        for (ParticleEntry e : inCat) {
            if (e.isEffectiveEnabled(activePreset)) enabled++;
        }

        if (enabled == 0) return CategoryTriState.ALL_OFF;
        if (enabled == inCat.size()) return CategoryTriState.ALL_ON;
        return CategoryTriState.MIXED;
    }

    public static boolean isParticleAllowed(ParticleType<?> type) {
        if (!initialized) initialize();
        ParticleEntry entry = BY_TYPE.get(type);
        if (entry == null) return true; // Unrecognized modded particle defaults to allowed
        return entry.isEffectiveEnabled(activePreset);
    }

    // -------------------------------------------------------------------------
    // Categorization & Priority Heuristics
    // -------------------------------------------------------------------------

    private static Category classifyCategory(String path) {
        if (path.contains("block") || path.contains("dust") || path.contains("break") || path.contains("hit")) {
            return Category.BLOCK_EFFECTS;
        }
        if (path.contains("crit") || path.contains("sweep") || path.contains("damage") || path.contains("attack")) {
            return Category.COMBAT;
        }
        if (path.contains("smoke") || path.contains("flame") || path.contains("fire") || path.contains("campfire") || path.contains("ash") || path.contains("lava")) {
            return Category.FIRE_SMOKE;
        }
        if (path.contains("water") || path.contains("bubble") || path.contains("splash") || path.contains("drip") || path.contains("landing") || path.contains("falling") || path.contains("fluid") || path.contains("honey")) {
            return Category.FLUIDS;
        }
        if (path.contains("potion") || path.contains("enchant") || path.contains("portal") || path.contains("dragon") || path.contains("witch") || path.contains("spell") || path.contains("magic")) {
            return Category.MAGIC;
        }
        if (path.contains("rain") || path.contains("snow") || path.contains("cloud") || path.contains("weather")) {
            return Category.WEATHER;
        }
        if (path.contains("leaf") || path.contains("leaves") || path.contains("ambient") || path.contains("mycelium") || path.contains("underwater") || path.contains("spore")) {
            return Category.ENVIRONMENT;
        }
        if (path.contains("redstone") || path.contains("sculk") || path.contains("shriek")) {
            return Category.REDSTONE;
        }
        if (path.contains("explosion") || path.contains("gust") || path.contains("sonic_boom")) {
            return Category.EXPLOSIONS;
        }
        if (path.contains("heart") || path.contains("villager") || path.contains("item") || path.contains("entity") || path.contains("mob") || path.contains("guardian")) {
            return Category.ENTITY;
        }
        return Category.OTHER;
    }

    private static Priority classifyPriority(ParticleType<?> type, String path) {
        if (type == ParticleTypes.ELDER_GUARDIAN || type == ParticleTypes.SONIC_BOOM ||
            path.contains("trial_spawner") || path.contains("raid_omen") || path.contains("ominous")) {
            return Priority.CRITICAL;
        }
        if (path.contains("explosion") || path.contains("crit") || path.contains("damage") || path.contains("sweep") || path.contains("break")) {
            return Priority.HIGH;
        }
        if (path.contains("ambient") || path.contains("drip") || path.contains("ash") || path.contains("leaf") || path.contains("mycelium") || path.contains("underwater")) {
            return Priority.LOW;
        }
        return Priority.NORMAL;
    }

    private static String formatDisplayName(Identifier id) {
        String path = id.getPath();
        String[] parts = path.split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1)).append(" ");
        }
        if (!"minecraft".equals(id.getNamespace())) {
            sb.append("(").append(id.getNamespace()).append(")");
        }
        return sb.toString().trim();
    }
}
