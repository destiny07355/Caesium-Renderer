package destiny.renderer.light;

import destiny.renderer.chunk.PackedLightMap;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.LightType;

public final class FastLightSampler implements LightSampler {

    private static final int CACHE_SIZE = 1024;
    private static final int CACHE_MASK = CACHE_SIZE - 1;

    private final long[] posKeys = new long[CACHE_SIZE];
    private final int[] packedLights = new int[CACHE_SIZE];
    private long lastWorldTime = -1;

    public FastLightSampler() {
        clear();
    }

    @Override
    public int sample(double x, double y, double z) {
        return sample((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    @Override
    public int sample(int bx, int by, int bz) {
        long key = (((long) bx & 0x3FFFFF) << 42) | (((long) bz & 0x3FFFFF) << 20) | ((long) by & 0xFFFFF);
        int hash = (int) ((key ^ (key >>> 16)) & CACHE_MASK);

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.world == null) return 0x00F000F0;

        ClientWorld world = mc.world;
        long time = world.getTime();

        if (time != lastWorldTime) {
            lastWorldTime = time;
        }

        if (posKeys[hash] == key) {
            return packedLights[hash];
        }

        BlockPos pos = new BlockPos(bx, by, bz);
        int blockLight = world.getLightLevel(LightType.BLOCK, pos);
        int skyLight = world.getLightLevel(LightType.SKY, pos);

        int packed = PackedLightMap.pack(blockLight, skyLight);

        posKeys[hash] = key;
        packedLights[hash] = packed;

        return packed;
    }

    @Override
    public void clear() {
        for (int i = 0; i < CACHE_SIZE; i++) {
            posKeys[i] = Long.MIN_VALUE;
        }
    }
}
