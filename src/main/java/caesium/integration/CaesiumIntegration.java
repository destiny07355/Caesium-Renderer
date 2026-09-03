package caesium.integration;

import caesium.engine.CaesiumEngine;
import caesium.engine.backend.BackendSelector;
import caesium.engine.backend.GpuBackend;
import caesium.engine.backend.vulkan.VulkanBackend;
import caesium.engine.scheduler.FrameInput;
import caesium.engine.world.DeltaCommand;
import caesium.engine.world.RenderWorld;
import destiny.renderer.chunk.ChunkSectionData;
import destiny.renderer.chunk.SectionMeshExtractor;
import destiny.renderer.compat.ResourceShare;
import destiny.renderer.config.RendererConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

public final class CaesiumIntegration{

private static final Logger LOGGER=Logger.getLogger("Caesium");
private static final int MAX_EXTRACT_QUEUE=32;
private static final float CAMERA_PUSH_THRESHOLD=8f;
private static CaesiumEngine engine;
private static boolean started;
private static final ConcurrentHashMap<ChunkSectionPos,AtomicInteger> revisions=new ConcurrentHashMap<>();
private static ExecutorService extractPool;
private static float lastCamX,lastCamY,lastCamZ;
private static RenderWorld.Options lastOptions;

private CaesiumIntegration(){}

private static boolean dormant(){
return engine==null||engine.graph().passCount()==0;
}

public static void start(){
if(started)return;
    GpuBackend backend=BackendSelector.select(preference(),devicePreference());
    RendererConfig cfg=RendererConfig.get();
    int meshThreads=Math.max(1,cfg.resolvedMeshingThreads());
    int framesInFlight=Math.max(1,cfg.cpuRenderAhead);
    engine=new CaesiumEngine(backend,meshThreads,framesInFlight);
engine.start();
    int extractThreads = Math.max(1, meshThreads);
    int queueSize = Math.max(128, MAX_EXTRACT_QUEUE * extractThreads);
    extractPool=new ThreadPoolExecutor(extractThreads,extractThreads,0L,TimeUnit.MILLISECONDS,
    new ArrayBlockingQueue<>(queueSize),
    r->{
    Thread t=new Thread(r,"Caesium-SectionExtract");
    t.setDaemon(true);
    t.setPriority(Thread.MIN_PRIORITY);
    return t;
    },
    new ThreadPoolExecutor.DiscardPolicy());
    started=true;
ResourceShare.logSummary();
if(dormant()){
    LOGGER.info("[Caesium] Engine idle — no render pass registered. Frame loop and section extraction are gated off until a pass consumes the scene.");
}
if(RendererConfig.get().windowPresent&&backend instanceof VulkanBackend vulkan){
MinecraftClient mc=MinecraftClient.getInstance();
if(mc!=null&&mc.getWindow()!=null&&mc.getWindow().getHandle()!=0L){
try{
vulkan.attachWindow(mc.getWindow().getHandle());
LOGGER.info("[Caesium] Swapchain attached to the game window.");
}catch(Throwable t){
LOGGER.warning("[Caesium] Failed to attach swapchain to game window: "+t);
}
}else{
LOGGER.warning("[Caesium] windowPresent is on but no game window is available — "+"keeping offscreen rendering.");
}
}
}

private static BackendSelector.Preference preference(){
try{
return BackendSelector.Preference.valueOf(RendererConfig.get().renderingBackend);
}catch(IllegalArgumentException e){
return BackendSelector.Preference.OPENGL;
}
}

private static String devicePreference(){
String value=RendererConfig.get().vulkanDevice;
return value==null||value.isBlank()?"AUTO":value;
}

public static void render(){
if(!started||engine==null)return;
if(dormant())return;
MinecraftClient mc=MinecraftClient.getInstance();
if(mc==null||mc.world==null||mc.gameRenderer==null)return;
Camera camera=mc.gameRenderer.getCamera();
Vec3d pos=camera.getCameraPos();
int vd=mc.options!=null?mc.options.getViewDistance().getValue():12;
RenderWorld.Options opts=new RenderWorld.Options(mc.options.getGamma().getValue()>0.99,vd,1000,300);
if(!opts.equals(lastOptions)){
engine.scene().push(new DeltaCommand.OptionChanged(opts));
lastOptions=opts;
}
if(Math.abs((float)pos.x-lastCamX)+Math.abs((float)pos.y-lastCamY)+Math.abs((float)pos.z-lastCamZ)>=CAMERA_PUSH_THRESHOLD){
RenderWorld.Camera cam=new RenderWorld.Camera((float)pos.x,(float)pos.y,(float)pos.z,camera.getPitch(),camera.getYaw(),70f,mc.world.getTime());
engine.scene().push(new DeltaCommand.CameraMoved(cam));
lastCamX=(float)pos.x;
lastCamY=(float)pos.y;
lastCamZ=(float)pos.z;
}
RenderWorld world=engine.scene().update(engine.scene().published());
float deltaMs = (float) Math.max(1.0, destiny.renderer.hud.PerformanceOverlay.averageFrameMs());
FrameInput input=new FrameInput(world,deltaMs,System.currentTimeMillis(),false,List.of());
engine.scheduler().beginFrame(input);
engine.scheduler().execute(input);
engine.scheduler().endFrame(input);
}

private static final ThreadLocal<ChunkSectionData> threadLocalSectionData = ThreadLocal.withInitial(ChunkSectionData::new);

public static void extractSection(ChunkSectionPos pos){
if(!started||engine==null||extractPool==null)return;
if(dormant())return;
if(!destiny.renderer.compat.WorkAllotment.ownsTerrain())return;
MinecraftClient mc=MinecraftClient.getInstance();
if(mc==null||mc.world==null||mc.gameRenderer==null)return;
Vec3d camPos=mc.gameRenderer.getCamera().getCameraPos();
int vd=mc.options!=null?mc.options.getViewDistance().getValue():12;
float limit=(vd+2)*16f;
float cx=pos.getMinX()+8f;
float cy=pos.getMinY()+8f;
float cz=pos.getMinZ()+8f;
if(Math.abs(cx-(float)camPos.x)>limit||Math.abs(cy-(float)camPos.y)>512f||Math.abs(cz-(float)camPos.z)>limit){
return;
}
pruneRevisions(camPos,limit);
AtomicInteger rev=revisions.computeIfAbsent(pos,k->new AtomicInteger());
int nextRev=rev.incrementAndGet();
try{
extractPool.execute(()->{
try{
MinecraftClient mc2=MinecraftClient.getInstance();
ClientWorld world=mc2==null?null:mc2.world;
if(world==null)return;
if(rev.get()!=nextRev)return;
ChunkSectionData data=threadLocalSectionData.get();
data.populate(world,pos.getMinX()-1,pos.getMinY()-1,pos.getMinZ()-1);
if(rev.get()!=nextRev)return;
RenderWorld.SectionMesh mesh=SectionMeshExtractor.extract(pos,data,nextRev);
if(mesh!=null){
engine.scene().push(new DeltaCommand.SectionMeshUpdated(mesh));
}
}catch(Throwable t){
LOGGER.warning("[Caesium] Section extraction failed for "+pos+": "+t);
}
});
}catch(java.util.concurrent.RejectedExecutionException ignored){
}
}

private static void pruneRevisions(Vec3d camPos,float limit){
if(revisions.size()<4096)return;
revisions.entrySet().removeIf(e->{
ChunkSectionPos p=e.getKey();
float px=p.getMinX()+8f;
float py=p.getMinY()+8f;
float pz=p.getMinZ()+8f;
return Math.abs(px-(float)camPos.x)>limit||Math.abs(py-(float)camPos.y)>limit||Math.abs(pz-(float)camPos.z)>limit;
});
}

public static void stop(){
if(extractPool!=null){
extractPool.shutdownNow();
extractPool=null;
}
if(engine!=null){
if(engine.backend()instanceof VulkanBackend vulkan){
try{
vulkan.detachWindow();
}catch(Throwable t){
LOGGER.warning("[Caesium] Error detaching swapchain: "+t);
}
}
engine.stop();
engine=null;
}
started=false;
}

public static boolean started(){
return started;
}

public static CaesiumEngine getEngine() {
    return engine;
}

public static GpuBackend getBackend() {
    return engine != null ? engine.backend() : null;
}
}