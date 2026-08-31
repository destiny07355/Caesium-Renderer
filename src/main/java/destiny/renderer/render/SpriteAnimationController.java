package destiny.renderer.render;

import destiny.renderer.config.RendererConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.SpriteContents;
import net.minecraft.util.Identifier;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Central policy for the texture-animation toggles on the Animations page.
 *
 * <p>Pre-classifies texture categories at registration time so that per-frame evaluation
 * performs zero string allocations or substring searches.
 */
public final class SpriteAnimationController {

    private record SpriteInfo(int category, boolean isBlock) {}

    private static final Map<SpriteContents.Animator, SpriteInfo> SPRITE_INFO =
        Collections.synchronizedMap(new WeakHashMap<>());

    private SpriteAnimationController() {}

    /** Records which texture a freshly created animator belongs to and pre-classifies its category. */
    public static void register(SpriteContents contents, SpriteContents.Animator animator) {
        if (contents == null || animator == null) return;
        Identifier id = contents.getId();
        if (id != null) {
            String path = id.getPath();
            boolean isBlock = path.startsWith("block/");
            int cat = categoryOf(path);
            SPRITE_INFO.put(animator, new SpriteInfo(cat, isBlock));
        }
    }

    /**
     * @return {@code true} when this animator's tick must be skipped for this frame.
     */
    public static boolean shouldSkip(SpriteContents.Animator animator) {
        RendererConfig c = RendererConfig.get();

        if (!c.enableTextureAnimations) return true;
        if (c.animateOnlyVisibleTextures && !worldIsActive()) return true;

        if (c.throttleTextureAnimOnSlowFrames) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc != null && mc.options != null) {
                int maxCap = mc.options.getMaxFps().getValue();
                if (maxCap > 0 && maxCap < 260) {
                    int current = mc.getCurrentFps();
                    if (current > 0 && current < maxCap * 0.7) {
                        return true;
                    }
                }
            }
        }

        SpriteInfo info = SPRITE_INFO.get(animator);
        if (info == null || !info.isBlock) return false;

        if (c.animateOnlyVisibleTextures && info.category != 0) {
            if (!SpriteVisibilityTracker.isVisible(info.category)) return true;
        }

        return switch (info.category) {
            case SpriteVisibilityTracker.CAT_WATER -> !c.enableWaterAnim;
            case SpriteVisibilityTracker.CAT_LAVA -> !c.enableLavaAnim;
            case SpriteVisibilityTracker.CAT_FIRE -> !c.enableFireAnim;
            case SpriteVisibilityTracker.CAT_PORTAL -> !c.enablePortalAnim;
            case SpriteVisibilityTracker.CAT_SCULK -> !c.enableSculkSensorAnim;
            default -> !c.enableBlockAnimations;
        };
    }

    private static int categoryOf(String path) {
        if (path.contains("/fire_") || path.endsWith("/fire") || path.contains("/soul_fire")) return SpriteVisibilityTracker.CAT_FIRE;
        if (path.contains("/water_flow") || path.contains("/water_still")) return SpriteVisibilityTracker.CAT_WATER;
        if (path.contains("/lava_flow") || path.contains("/lava_still")) return SpriteVisibilityTracker.CAT_LAVA;
        if (path.contains("portal")) return SpriteVisibilityTracker.CAT_PORTAL;
        if (path.contains("sculk_sensor")) return SpriteVisibilityTracker.CAT_SCULK;
        return 0;
    }

    private static boolean worldIsActive() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc != null && mc.world != null && !mc.isPaused();
    }
}
