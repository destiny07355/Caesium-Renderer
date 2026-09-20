package destiny.renderer.mixin;

import destiny.renderer.math.CompactSineLUT;
import net.minecraft.util.math.MathHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * Accelerates all game mathematics with an L1-cache-resident compact Sine/Cosine LUT.
 */
@Mixin(MathHelper.class)
public abstract class MathHelperMixin {

    /**
     * @author Caesium / Lithium (jellysquid3 & coderbot16)
     * @reason Compact 16KB L1-cache friendly sine LUT for max FPS
     */
    @Overwrite
    public static float sin(double value) {
        return CompactSineLUT.sin(value);
    }

    /**
     * @author Caesium / Lithium (jellysquid3 & coderbot16)
     * @reason Compact 16KB L1-cache friendly cosine LUT for max FPS
     */
    @Overwrite
    public static float cos(double value) {
        return CompactSineLUT.cos(value);
    }
}
