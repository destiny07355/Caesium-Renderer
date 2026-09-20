package destiny.renderer.chunk;

import caesium.engine.world.LayerMeshBuilder;
import caesium.engine.world.RenderWorld;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.render.BlockRenderLayer;
import net.minecraft.client.render.BlockRenderLayers;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.render.model.BlockModelPart;
import net.minecraft.client.render.model.BlockStateModel;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.BlockRenderView;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

/** Extracts Minecraft baked block-model geometry into engine-neutral render layers. */
public final class BakedSectionExtractor {
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final RenderWorld.TerrainLayer[] LAYERS = RenderWorld.TerrainLayer.values();

    private static final ThreadLocal<LayerMeshBuilder[]> THREAD_LOCAL_BUILDERS = ThreadLocal.withInitial(() -> {
        LayerMeshBuilder[] builders = new LayerMeshBuilder[LAYERS.length];
        for (int i = 0; i < LAYERS.length; i++) {
            builders[i] = new LayerMeshBuilder(LAYERS[i]);
        }
        return builders;
    });

    private BakedSectionExtractor() {}

    public static RenderWorld.LayeredSectionMesh extract(ChunkSectionPos sectionPos,
                                                          ChunkSectionData data,
                                                          BlockRenderView world,
                                                          BlockRenderManager models,
                                                          BlockColors blockColors,
                                                          int revision) {
        if (sectionPos == null || data == null || world == null || models == null || blockColors == null) {
            throw new IllegalArgumentException("baked section extraction dependencies are required");
        }
        LayerMeshBuilder[] builders = THREAD_LOCAL_BUILDERS.get();
        for (int i = 0; i < builders.length; i++) {
            builders[i].reset();
        }

        long sectionKey = packSectionKey(sectionPos.getSectionX(), sectionPos.getSectionY(), sectionPos.getSectionZ());
        destiny.renderer.blockentity.BlockEntityOptimizationRegistry.invalidateSection(sectionKey);

        float[] posScratch = BakedQuadDecoder.positionScratch();
        float[] uvScratch = BakedQuadDecoder.uvScratch();

        BlockPos.Mutable worldPos = new BlockPos.Mutable();
        Random random = Random.create(0L);
        boolean fullyOpaque = true;
        boolean coverageComplete = true;
        for (int ly = 1; ly <= 16; ly++) {
            for (int lz = 1; lz <= 16; lz++) {
                for (int lx = 1; lx <= 16; lx++) {
                    int stateId = data.getStateId(lx, ly, lz);
                    if (stateId == 0 || BlockStateLUT.isEmpty(stateId)) {
                        fullyOpaque = false;
                        continue;
                    }
                    BlockState state = Block.getStateFromRawId(stateId);
                    if (state == null || state.isAir()) {
                        fullyOpaque = false;
                        continue;
                    }

                    int x = data.originX + lx - 1;
                    int y = data.originY + ly - 1;
                    int z = data.originZ + lz - 1;
                    worldPos.set(x, y, z);
                    if (!state.isOpaqueFullCube()) fullyOpaque = false;
                    if (!state.getFluidState().isEmpty()) coverageComplete = false;
                    random.setSeed(state.getRenderingSeed(worldPos));
                    BlockStateModel model = models.getModel(state);
                    List<BlockModelPart> parts = model.getParts(random);
                    BlockRenderLayer minecraftLayer = BlockRenderLayers.getBlockLayer(state);
                    LayerMeshBuilder target = builders[BakedQuadDecoder.layer(minecraftLayer).ordinal()];
                    int packedLight = data.getLightPacked(lx, ly, lz) & 0xFF;

                    int visibleFaces = 0;
                    for (int d = 0; d < 6; d++) {
                        Direction direction = DIRECTIONS[d];
                        int nlx = lx + direction.getOffsetX();
                        int nly = ly + direction.getOffsetY();
                        int nlz = lz + direction.getOffsetZ();
                        if (state.isOpaqueFullCube() && data.isOpaque(nlx, nly, nlz)) {
                            continue;
                        }
                        int neighborId = data.getStateId(nlx, nly, nlz);
                        if (neighborId == 0 || BlockStateLUT.isEmpty(neighborId)) {
                            visibleFaces |= (1 << d);
                        } else {
                            BlockState neighbor = Block.getStateFromRawId(neighborId);
                            if (neighbor == null || Block.shouldDrawSide(state, neighbor, direction)) {
                                visibleFaces |= (1 << d);
                            }
                        }
                    }

                    for (int p = 0; p < parts.size(); p++) {
                        BlockModelPart part = parts.get(p);
                        List<BakedQuad> unculled = part.getQuads(null);
                        if (unculled != null && !unculled.isEmpty()) {
                            append(unculled, state, world, worldPos, blockColors,
                                x, y, z, packedLight, target, posScratch, uvScratch);
                        }
                        if (visibleFaces != 0) {
                            for (int d = 0; d < 6; d++) {
                                if ((visibleFaces & (1 << d)) != 0) {
                                    List<BakedQuad> culled = part.getQuads(DIRECTIONS[d]);
                                    if (culled != null && !culled.isEmpty()) {
                                        append(culled, state, world, worldPos, blockColors,
                                            x, y, z, packedLight, target, posScratch, uvScratch);
                                    }
                                }
                            }
                        }
                    }

                    if (state.hasBlockEntity()) {
                        net.minecraft.block.entity.BlockEntity be = world.getBlockEntity(worldPos);
                        if (be != null) {
                            destiny.renderer.blockentity.BlockEntityClassification classification =
                                destiny.renderer.blockentity.BlockEntityOptimizationRegistry.classify(be, state);
                            if (classification == destiny.renderer.blockentity.BlockEntityClassification.STATIC) {
                                boolean baked = destiny.renderer.blockentity.BlockEntityOptimizationRegistry.bake(
                                    be, state, world, worldPos, x, y, z, packedLight, models, builders, posScratch, uvScratch);
                                if (baked) {
                                    long posKey = worldPos.asLong();
                                    destiny.renderer.blockentity.BlockEntityOptimizationRegistry.recordStatic(posKey, sectionKey);
                                }
                            }
                        }
                    }
                }
            }
        }

        List<RenderWorld.LayerMesh> output = new ArrayList<>(4);
        for (int i = 0; i < builders.length; i++) {
            LayerMeshBuilder builder = builders[i];
            if (!builder.isEmpty()) output.add(builder.build());
        }
        return new RenderWorld.LayeredSectionMesh(sectionPos.getSectionX(), sectionPos.getSectionZ(),
            sectionPos.getSectionY(), revision, output, fullyOpaque, coverageComplete);
    }

    private static void append(List<BakedQuad> quads, BlockState state, BlockRenderView world,
                               BlockPos pos, BlockColors blockColors, int x, int y, int z,
                               int packedLight, LayerMeshBuilder target,
                               float[] posScratch, float[] uvScratch) {
        for (int i = 0; i < quads.size(); i++) {
            BakedQuad quad = quads.get(i);
            int color = 0xFFFFFFFF;
            if (quad.hasTint()) {
                int rgb = blockColors.getColor(state, world, pos, quad.tintIndex());
                color = 0xFF000000 | (rgb & 0x00FFFFFF);
            }
            BakedQuadDecoder.append(quad, x, y, z, color, packedLight, target, posScratch, uvScratch);
        }
    }

    private static long packSectionKey(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF))
            | (((long) (y & 0x1FFFFF)) << 21)
            | (((long) (z & 0x1FFFFF)) << 42);
    }
}
