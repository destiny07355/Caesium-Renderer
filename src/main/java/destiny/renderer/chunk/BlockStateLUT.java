package destiny.renderer.chunk;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.MapColor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.Sprite;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.logging.Logger;

public final class BlockStateLUT{

private static final Logger LOGGER=Logger.getLogger("Caesium/LUT");
public static final byte LAYER_EMPTY=0;
public static final byte LAYER_OPAQUE=1;
public static final byte LAYER_CUTOUT=2;
public static final byte LAYER_TRANSLUCENT=3;
private static byte[] renderLayer=new byte[0];
private static byte[] tintIndex=new byte[0];
private static boolean[] opaqueCube=new boolean[0];
private static boolean[] emptyState=new boolean[0];
private static float[] spriteUV=new float[0];
private static int[] blockColor=new int[0];
private static volatile boolean built=false;

private BlockStateLUT(){}

public static boolean isBuilt(){return built;}

public static synchronized void build(){
if(built)return;
int stateCount=Block.STATE_IDS.size();
if(stateCount<=0){
LOGGER.warning("[Caesium] Block state registry empty — deferring LUT build.");
return;
}
renderLayer=new byte[stateCount];
tintIndex=new byte[stateCount];
opaqueCube=new boolean[stateCount];
emptyState=new boolean[stateCount];
spriteUV=new float[stateCount*4];
blockColor=new int[stateCount];
MinecraftClient client=MinecraftClient.getInstance();
for(int id=0;id<stateCount;id++){
BlockState state=Block.getStateFromRawId(id);
if(state==null||state.isAir()){
renderLayer[id]=LAYER_EMPTY;
emptyState[id]=true;
continue;
}
renderLayer[id]=classifyLayer(state);
tintIndex[id]=classifyTint(state);
opaqueCube[id]=state.isOpaque()&&state.isFullCube(net.minecraft.world.EmptyBlockView.INSTANCE,net.minecraft.util.math.BlockPos.ORIGIN);
emptyState[id]=renderLayer[id]==LAYER_EMPTY;
blockColor[id]=resolveColor(state);
resolveSprite(client,state,id);
}
built=true;
LOGGER.info("[Caesium] Block state LUT built for "+stateCount+" states.");
}

private static void resolveSprite(MinecraftClient client,BlockState state,int id){
int base=id*4;
spriteUV[base]=0.0f;
spriteUV[base+1]=0.0f;
spriteUV[base+2]=1.0f;
spriteUV[base+3]=1.0f;
if(client==null||client.getBlockRenderManager()==null)return;
try{
var models=client.getBlockRenderManager().getModels();
if(models==null)return;
var model=models.getModel(state);
if(model==null)return;
Sprite sprite=model.particleSprite();
if(sprite==null)return;
spriteUV[base]=sprite.getMinU();
spriteUV[base+1]=sprite.getMinV();
spriteUV[base+2]=sprite.getMaxU();
spriteUV[base+3]=sprite.getMaxV();
}catch(Throwable ignored){
}
}

private static byte classifyLayer(BlockState state){
Block block=state.getBlock();
if(!state.getFluidState().isEmpty()){
return LAYER_TRANSLUCENT;
}
if(block==Blocks.GLASS||block==Blocks.ICE||block==Blocks.FROSTED_ICE||block==Blocks.SLIME_BLOCK||block==Blocks.HONEY_BLOCK||block==Blocks.TINTED_GLASS){
return LAYER_TRANSLUCENT;
}
String path=Registries.BLOCK.getId(block).getPath();
if(path.endsWith("_stained_glass")||path.endsWith("_stained_glass_pane")){
return LAYER_TRANSLUCENT;
}
if(state.isOpaque()&&state.isFullCube(net.minecraft.world.EmptyBlockView.INSTANCE,net.minecraft.util.math.BlockPos.ORIGIN)){
return LAYER_OPAQUE;
}
return LAYER_CUTOUT;
}

private static byte classifyTint(BlockState state){
Block block=state.getBlock();
String path=Registries.BLOCK.getId(block).getPath();
if(!state.getFluidState().isEmpty()||path.contains("water")){
return (byte)PackedVertexFormat.TINT_WATER;
}
if(path.endsWith("_leaves")||path.equals("vine")||path.equals("mangrove_leaves")){
return (byte)PackedVertexFormat.TINT_FOLIAGE;
}
if(path.equals("grass_block")||path.equals("short_grass")||path.equals("tall_grass")||path.equals("fern")||path.equals("large_fern")||path.equals("sugar_cane")){
return (byte)PackedVertexFormat.TINT_GRASS;
}
return (byte)PackedVertexFormat.TINT_NONE;
}

private static int resolveColor(BlockState state){
try{
MapColor mapColor=state.getMapColor(net.minecraft.world.EmptyBlockView.INSTANCE,BlockPos.ORIGIN);
return 0xFF000000|(mapColor.color&0x00FFFFFF);
}catch(Throwable ignored){
return 0xFFFFFFFF;
}
}

public static byte layerOf(int stateId){
byte[] t=renderLayer;
return (stateId>=0&&stateId<t.length)?t[stateId]:LAYER_OPAQUE;
}

public static boolean isTranslucent(int stateId){
return layerOf(stateId)==LAYER_TRANSLUCENT;
}

public static boolean isEmpty(int stateId){
boolean[] t=emptyState;
return stateId>=0&&stateId<t.length&&t[stateId];
}

public static boolean isOpaqueCube(int stateId){
boolean[] t=opaqueCube;
return stateId>=0&&stateId<t.length&&t[stateId];
}

public static byte tintOf(int stateId){
byte[] t=tintIndex;
return (stateId>=0&&stateId<t.length)?t[stateId]:0;
}

public static int colorOf(int stateId){
int[] t=blockColor;
return (stateId>=0&&stateId<t.length)?t[stateId]:0xFFFFFFFF;
}

public static float minU(int stateId){return uv(stateId,0);}
public static float minV(int stateId){return uv(stateId,1);}
public static float maxU(int stateId){return uv(stateId,2);}
public static float maxV(int stateId){return uv(stateId,3);}

private static float uv(int stateId,int component){
float[] t=spriteUV;
int idx=stateId*4+component;
if(idx<0||idx>=t.length)return component<2?0.0f:1.0f;
return t[idx];
}

public static synchronized void invalidate(){
built=false;
}
}