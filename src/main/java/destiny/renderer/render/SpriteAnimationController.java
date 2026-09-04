package destiny.renderer.render;

import destiny.renderer.config.RendererConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.SpriteContents;
import net.minecraft.util.Identifier;

/**
 * Ultra-fast, zero-allocation policy for texture-animation ticking.
 * Directly queries inlined instance fields on animators without any synchronized map overhead.
 */
public final class SpriteAnimationController {

    private static volatile int frameSkipMask = 0;
    private static volatile boolean globalSkip = false;
    private static volatile boolean blockAnimationsEnabled = true;
    private static long lastUpdateMs = 0L;

    private SpriteAnimationController() {}

    /** Pre-classifies the category onto the animator instance upon creation. */
    public static void classify(SpriteContents contents, CaesiumSpriteAnimator animator) {
        if (contents == null || animator == null) return;
        Identifier id = contents.getId();
        if (id != null) {
            String path = id.getPath();
            boolean isBlock = path.startsWith("block/");
            int cat = categoryOf(path);
            animator.caesium$setCategory(cat);
            animator.caesium$setIsBlock(isBlock);
        }
    }

    /** Compatibility overload for legacy call sites. */
    public static void register(SpriteContents contents, SpriteContents.Animator animator) {
        if (animator instanceof CaesiumSpriteAnimator csa) {
            classify(contents, csa);
        }
    }

    /** Precomputes frame skip mask once per frame/tick (~50ms throttle). */
    public static void updateFramePolicy() {
        long now = System.currentTimeMillis();
        if (now - lastUpdateMs < 200L) return;
        lastUpdateMs = now;

        RendererConfig c = RendererConfig.get();
        if (!c.enableTextureAnimations) {
            globalSkip = true;
            return;
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        boolean worldActive = mc != null && mc.world != null && !mc.isPaused();
        if (c.animateOnlyVisibleTextures && !worldActive) {
            globalSkip = true;
            return;
        }

        if (c.throttleTextureAnimOnSlowFrames && mc != null && mc.options != null) {
            int maxCap = mc.options.getMaxFps().getValue();
            if (maxCap > 0 && maxCap < 260) {
                int current = mc.getCurrentFps();
                if (current > 0 && current < maxCap * 0.7) {
                    globalSkip = true;
                    return;
                }
            }
        }

        globalSkip = false;
        blockAnimationsEnabled = c.enableBlockAnimations;

        int mask = 0;
        if (!c.enableWaterAnim) mask |= SpriteVisibilityTracker.CAT_WATER;
        if (!c.enableLavaAnim) mask |= SpriteVisibilityTracker.CAT_LAVA;
        if (!c.enableFireAnim) mask |= SpriteVisibilityTracker.CAT_FIRE;
        if (!c.enablePortalAnim) mask |= SpriteVisibilityTracker.CAT_PORTAL;
        if (!c.enableSculkSensorAnim) mask |= SpriteVisibilityTracker.CAT_SCULK;

        if (c.animateOnlyVisibleTextures) {
            if (!SpriteVisibilityTracker.isVisible(SpriteVisibilityTracker.CAT_WATER)) mask |= SpriteVisibilityTracker.CAT_WATER;
            if (!SpriteVisibilityTracker.isVisible(SpriteVisibilityTracker.CAT_LAVA)) mask |= SpriteVisibilityTracker.CAT_LAVA;
            if (!SpriteVisibilityTracker.isVisible(SpriteVisibilityTracker.CAT_FIRE)) mask |= SpriteVisibilityTracker.CAT_FIRE;
            if (!SpriteVisibilityTracker.isVisible(SpriteVisibilityTracker.CAT_PORTAL)) mask |= SpriteVisibilityTracker.CAT_PORTAL;
            if (!SpriteVisibilityTracker.isVisible(SpriteVisibilityTracker.CAT_SCULK)) mask |= SpriteVisibilityTracker.CAT_SCULK;
        }

        frameSkipMask = mask;
    }

    /**
     * Inlined ultra-fast check called on every animator.tick().
     * Uses zero map lookups and zero allocations.
     */
    public static boolean shouldSkipFast(CaesiumSpriteAnimator animator) {
        if (globalSkip) return true;
        if (!animator.caesium$isBlock()) return false;

        int cat = animator.caesium$getCategory();
        if (cat == 0) return !blockAnimationsEnabled;

        return (cat & frameSkipMask) != 0;
    }

    /** Backward compatibility fallback. */
    public static boolean shouldSkip(SpriteContents.Animator animator) {
        if (animator instanceof CaesiumSpriteAnimator csa) {
            return shouldSkipFast(csa);
        }
        return false;
    }

    private static int categoryOf(String path) {
        if (path.contains("fire") || path.contains("flame") || path.contains("campfire")) return SpriteVisibilityTracker.CAT_FIRE;
        if (path.contains("water")) return SpriteVisibilityTracker.CAT_WATER;
        if (path.contains("lava")) return SpriteVisibilityTracker.CAT_LAVA;
        if (path.contains("portal")) return SpriteVisibilityTracker.CAT_PORTAL;
        if (path.contains("sculk")) return SpriteVisibilityTracker.CAT_SCULK;
        return 0;
    }
}
