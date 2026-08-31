package destiny.renderer.chunk.biome;

import net.minecraft.world.biome.Biome;

import java.util.concurrent.ConcurrentHashMap;

public final class PackedBiomeColorMap {

    private static final ConcurrentHashMap<Biome, PackedColors> CACHE = new ConcurrentHashMap<>();

    public record PackedColors(int grassColor, int foliageColor, int waterColor) {}

    private PackedBiomeColorMap() {}

    public static PackedColors get(Biome biome) {
        if (biome == null) {
            return DEFAULT_COLORS;
        }
        return CACHE.computeIfAbsent(biome, PackedBiomeColorMap::compute);
    }

    private static final PackedColors DEFAULT_COLORS = new PackedColors(
        0xFF79C05A,
        0xFF59C93C,
        0xFF3F76E4
    );

    private static PackedColors compute(Biome biome) {
        int grass = 0xFF79C05A;
        int foliage = 0xFF59C93C;
        int water = 0xFF3F76E4;

        try {
            grass = 0xFF000000 | (biome.getGrassColorAt(0.0, 0.0) & 0x00FFFFFF);
            foliage = 0xFF000000 | (biome.getFoliageColor() & 0x00FFFFFF);
            water = 0xFF000000 | (biome.getWaterColor() & 0x00FFFFFF);
        } catch (Throwable ignored) {}

        return new PackedColors(grass, foliage, water);
    }

    public static int packColorWithAo(int rgb, float aoFloat) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;

        int ao = (int) (aoFloat * 255.0f);
        int finalR = (r * ao) >> 8;
        int finalG = (g * ao) >> 8;
        int finalB = (b * ao) >> 8;

        return 0xFF000000 | (finalR << 16) | (finalG << 8) | finalB;
    }

    public static void clear() {
        CACHE.clear();
    }
}
