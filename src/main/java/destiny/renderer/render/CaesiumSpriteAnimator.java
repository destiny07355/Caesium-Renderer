package destiny.renderer.render;

/**
 * Duck-typed interface injected into {@link net.minecraft.client.texture.SpriteContents.Animator}
 * to cache classification directly on the instance, bypassing all synchronized map lookups.
 */
public interface CaesiumSpriteAnimator {
    int caesium$getCategory();
    void caesium$setCategory(int category);
    boolean caesium$isBlock();
    void caesium$setIsBlock(boolean isBlock);
}
