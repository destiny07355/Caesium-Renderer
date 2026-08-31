package destiny.renderer.compat;

import net.fabricmc.loader.api.FabricLoader;

/**
 * A mod that is able to own one or more {@link Capability capabilities}.
 *
 * <p>Each provider knows its Fabric mod id so presence can be tested at runtime.
 * {@link #DESTINY} is always present; {@link #NONE} represents "nobody handles this",
 * which is a legitimate outcome for capabilities we do not implement ourselves.
 */
public enum Provider {

    /** Caesium's own implementation. */
    DESTINY("Caesium", "caesium"),

    /** Sodium — chunk rendering, block colours. */
    SODIUM("Sodium", "sodium"),

    /** Embeddium — Sodium fork. */
    EMBEDDIUM("Embeddium", "embeddium"),

    /** VulkanMod — full Vulkan renderer replacement. */
    VULKANMOD("VulkanMod", "vulkanmod"),

    /** ImmediatelyFast — entity/particle/HUD batching. */
    IMMEDIATELYFAST("ImmediatelyFast", "immediatelyfast"),

    /** EntityCulling — async raycast entity culling. */
    ENTITYCULLING("EntityCulling", "entityculling"),

    /** FerriteCore — block state memory deduplication. */
    FERRITECORE("FerriteCore", "ferritecore"),

    /** C2ME — concurrent chunk management. */
    C2ME("C2ME", "c2me"),

    /** Lithium — server tick and AI optimization. */
    LITHIUM("Lithium", "lithium"),

    /** Krypton — network stack optimization. */
    KRYPTON("Krypton", "krypton"),

    /** Dynamic FPS — unfocused frame throttling. */
    DYNAMICFPS("Dynamic FPS", "dynamic_fps"),

    /** ModernFix — startup and loading optimization. */
    MODERNFIX("ModernFix", "modernfix"),

    /** Iris — shader pipeline. */
    IRIS("Iris Shaders", "iris"),

    /** BadOptimizations — misc render micro-optimizations. */
    BADOPTIMIZATIONS("BadOptimizations", "badoptimizations"),

    /** MoreCulling — block and fluid face culling. */
    MORECULLING("MoreCulling", "moreculling"),

    /** Noisium — faster world generation. */
    NOISIUM("Noisium", "noisium"),

    /** Very Many Players — server-side player/entity optimization. */
    VMP("Very Many Players", "vmp"),

    /** Nobody handles this capability. */
    NONE("Not Handled", null);

    private final String displayName;
    private final String modId;

    Provider(String displayName, String modId) {
        this.displayName = displayName;
        this.modId = modId;
    }

    public String displayName() { return displayName; }
    public String modId()       { return modId; }

    /** @return true if this provider's mod is currently loaded. */
    public boolean isPresent() {
        if (this == NONE)    return true;
        if (this == DESTINY) return true;
        if (modId == null)   return false;
        try {
            // FerriteCore has shipped under two different ids across versions.
            if (this == FERRITECORE) {
                return FabricLoader.getInstance().isModLoaded("ferritecore")
                    || FabricLoader.getInstance().isModLoaded("ferrite-core");
            }
            // Very Many Players uses different ids on Modrinth vs CurseForge.
            if (this == VMP) {
                return FabricLoader.getInstance().isModLoaded("vmp")
                    || FabricLoader.getInstance().isModLoaded("very-many-players");
            }
            return FabricLoader.getInstance().isModLoaded(modId);
        } catch (Throwable t) {
            return false;
        }
    }
}
