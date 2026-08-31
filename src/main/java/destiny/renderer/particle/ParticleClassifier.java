package destiny.renderer.particle;

import destiny.renderer.config.RendererConfig;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleType;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Maps a {@link ParticleEffect} to the settings toggle that governs it.
 *
 * <p>Resolution is a single {@link IdentityHashMap} lookup keyed on the particle type
 * instance. The map is built lazily on first use and then reused, so the per-particle cost
 * is one hash lookup — important, because this runs on every particle spawn attempt and
 * a busy scene can attempt thousands per second.
 *
 * <p>Particle types with no entry are always allowed. Being permissive by default means a
 * modded particle we have never seen still renders normally.
 */
public final class ParticleClassifier {

    /** Which config flag governs a given particle type. */
    private static volatile Map<ParticleType<?>, Predicate<RendererConfig>> RULES;

    private ParticleClassifier() {}

    /** @return true if this particle is permitted by the current configuration. */
    public static boolean isEnabled(ParticleEffect effect, RendererConfig cfg) {
        if (effect == null) return true;
        Map<ParticleType<?>, Predicate<RendererConfig>> rules = RULES;
        if (rules == null) {
            rules = build();
            RULES = rules;
        }
        Predicate<RendererConfig> rule = rules.get(effect.getType());
        return rule == null || rule.test(cfg);
    }

    private static synchronized Map<ParticleType<?>, Predicate<RendererConfig>> build() {
        if (RULES != null) return RULES;
        Map<ParticleType<?>, Predicate<RendererConfig>> m = new IdentityHashMap<>(128);

        // --- Explosions ---
        put(m, ParticleTypes.EXPLOSION,          c -> c.enableExplosionParticles);
        put(m, ParticleTypes.EXPLOSION_EMITTER,  c -> c.enableExplosionParticles);
        put(m, ParticleTypes.GUST,               c -> c.enableExplosionParticles);
        put(m, ParticleTypes.GUST_EMITTER_LARGE, c -> c.enableExplosionParticles);
        put(m, ParticleTypes.GUST_EMITTER_SMALL, c -> c.enableExplosionParticles);
        put(m, ParticleTypes.SONIC_BOOM,         c -> c.enableExplosionParticles);

        // --- Combat ---
        put(m, ParticleTypes.CRIT,               c -> c.enableCritParticles);
        put(m, ParticleTypes.ENCHANTED_HIT,      c -> c.enableCritParticles);
        put(m, ParticleTypes.SWEEP_ATTACK,       c -> c.enableSweepParticles);
        put(m, ParticleTypes.DAMAGE_INDICATOR,   c -> c.enableDamageParticles);

        // --- Potion / status effects ---
        put(m, ParticleTypes.ENTITY_EFFECT,      c -> c.enablePotionParticles);
        put(m, ParticleTypes.EFFECT,             c -> c.enablePotionParticles);
        put(m, ParticleTypes.INSTANT_EFFECT,     c -> c.enablePotionParticles);
        put(m, ParticleTypes.WITCH,              c -> c.enablePotionParticles);

        // --- Fireworks ---
        put(m, ParticleTypes.FIREWORK,           c -> c.enableFireworkParticles);
        put(m, ParticleTypes.FLASH,              c -> c.enableFireworkParticles);

        // --- Weather ---
        put(m, ParticleTypes.RAIN,               c -> c.enableRainParticles);
        put(m, ParticleTypes.SNOWFLAKE,          c -> c.enableRainParticles);
        put(m, ParticleTypes.WHITE_ASH,          c -> c.enableRainParticles);
        put(m, ParticleTypes.ASH,                c -> c.enableRainParticles);
        put(m, ParticleTypes.FALLING_DUST,       c -> c.enableRainParticles);

        // --- Block breaking / dust ---
        put(m, ParticleTypes.BLOCK,              c -> c.enableBlockBreakParticles);
        put(m, ParticleTypes.BLOCK_MARKER,       c -> c.enableBlockBreakParticles);
        put(m, ParticleTypes.ITEM,               c -> c.enableBlockBreakParticles);

        // --- Smoke & fire ---
        put(m, ParticleTypes.SMOKE,              c -> c.enableSmokeParticles);
        put(m, ParticleTypes.LARGE_SMOKE,        c -> c.enableSmokeParticles);
        put(m, ParticleTypes.CAMPFIRE_COSY_SMOKE,   c -> c.enableCampfireParticles);
        put(m, ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, c -> c.enableCampfireParticles);
        put(m, ParticleTypes.FLAME,              c -> c.enableSmokeParticles);
        put(m, ParticleTypes.SMALL_FLAME,        c -> c.enableSmokeParticles);
        put(m, ParticleTypes.SOUL_FIRE_FLAME,    c -> c.enableSmokeParticles);
        put(m, ParticleTypes.LAVA,               c -> c.enableSmokeParticles);

        // --- Dripping liquids ---
        put(m, ParticleTypes.DRIPPING_WATER,     c -> c.enableDrippingParticles);
        put(m, ParticleTypes.FALLING_WATER,      c -> c.enableDrippingParticles);
        put(m, ParticleTypes.DRIPPING_LAVA,      c -> c.enableDrippingParticles);
        put(m, ParticleTypes.FALLING_LAVA,       c -> c.enableDrippingParticles);
        put(m, ParticleTypes.LANDING_LAVA,       c -> c.enableDrippingParticles);
        put(m, ParticleTypes.DRIPPING_HONEY,     c -> c.enableDrippingParticles);
        put(m, ParticleTypes.FALLING_HONEY,      c -> c.enableDrippingParticles);
        put(m, ParticleTypes.LANDING_HONEY,      c -> c.enableDrippingParticles);
        put(m, ParticleTypes.DRIPPING_OBSIDIAN_TEAR, c -> c.enableDrippingParticles);
        put(m, ParticleTypes.FALLING_OBSIDIAN_TEAR,  c -> c.enableDrippingParticles);
        put(m, ParticleTypes.LANDING_OBSIDIAN_TEAR,  c -> c.enableDrippingParticles);

        // --- Water ---
        put(m, ParticleTypes.SPLASH,             c -> c.enableSplashParticles);
        put(m, ParticleTypes.FISHING,            c -> c.enableSplashParticles);
        put(m, ParticleTypes.BUBBLE,             c -> c.enableBubbleParticles);
        put(m, ParticleTypes.BUBBLE_COLUMN_UP,   c -> c.enableBubbleParticles);
        put(m, ParticleTypes.BUBBLE_POP,         c -> c.enableBubbleParticles);
        put(m, ParticleTypes.CURRENT_DOWN,       c -> c.enableBubbleParticles);
        put(m, ParticleTypes.UNDERWATER,         c -> c.enableBubbleParticles);

        // --- Redstone ---
        put(m, ParticleTypes.DUST,               c -> c.enableRedstoneParticles);
        put(m, ParticleTypes.DUST_COLOR_TRANSITION, c -> c.enableRedstoneParticles);

        // --- Enchanting ---
        put(m, ParticleTypes.ENCHANT,            c -> c.enableEnchantParticles);

        // --- Portals ---
        put(m, ParticleTypes.PORTAL,             c -> c.enablePortalParticles);
        put(m, ParticleTypes.REVERSE_PORTAL,     c -> c.enablePortalParticles);

        // --- 1.20 / 1.21 additions ---
        try { put(m, (ParticleType<?>) ParticleTypes.class.getField("SCULK_SOUL").get(null), c -> c.enableRedstoneParticles); } catch (Throwable ignored) {}
        try { put(m, (ParticleType<?>) ParticleTypes.class.getField("SCULK_CHARGE_POP").get(null), c -> c.enableRedstoneParticles); } catch (Throwable ignored) {}
        try { put(m, (ParticleType<?>) ParticleTypes.class.getField("SHRIEK").get(null), c -> c.enableRedstoneParticles); } catch (Throwable ignored) {}
        try { put(m, (ParticleType<?>) ParticleTypes.class.getField("CHERRY_LEAVES").get(null), c -> c.enableRainParticles); } catch (Throwable ignored) {}

        return m;
    }

    private static void put(Map<ParticleType<?>, Predicate<RendererConfig>> m,
                            ParticleType<?> type,
                            Predicate<RendererConfig> rule) {
        if (type != null) m.put(type, rule);
    }

    /** Clears the cache so a config or registry reload rebuilds it. */
    public static void invalidate() {
        RULES = null;
    }
}
