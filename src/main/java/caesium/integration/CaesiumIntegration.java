package caesium.integration;

import caesium.engine.CaesiumEngine;
import caesium.engine.backend.BackendSelector;
import caesium.engine.backend.GpuBackend;
import caesium.engine.backend.opengl.OpenGLBackend;
import caesium.engine.backend.vulkan.VulkanBackend;
import caesium.engine.scheduler.FrameInput;
import caesium.engine.render.TerrainPass;
import caesium.engine.world.DeltaCommand;
import caesium.engine.world.RenderWorld;
import destiny.renderer.chunk.ChunkSectionData;
import destiny.renderer.chunk.BakedSectionExtractor;
import destiny.renderer.compat.ResourceShare;
import destiny.renderer.config.RendererConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.GlBackend;
import net.minecraft.client.gl.GlSampler;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.BlockRenderLayer;
import net.minecraft.client.render.BlockRenderLayerGroup;
import net.minecraft.client.render.SectionRenderState;
import net.minecraft.client.texture.GlTexture;
import net.minecraft.client.texture.GlTextureView;
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

public final class CaesiumIntegration{

private static final Logger LOGGER=Logger.getLogger("Caesium");
private static final int MAX_EXTRACT_QUEUE=2048;
private static final float CAMERA_PUSH_THRESHOLD=8f;
private static final float CAMERA_POSITION_EPSILON=0.05f;
private static final float CAMERA_ROTATION_EPSILON=0.05f;
private static CaesiumEngine engine;
private static boolean started;
private static final ConcurrentHashMap<Long,AtomicInteger> revisions=new ConcurrentHashMap<>();
private static final ConcurrentHashMap<Long, PrioritizedExtractionTask> pendingExtractions = new ConcurrentHashMap<>();
private static ThreadPoolExecutor extractPool;
private static ScheduledExecutorService extractRetryScheduler;
private static float lastCamX,lastCamY,lastCamZ;
private static float lastPitch=Float.NaN,lastYaw=Float.NaN;
private static RenderWorld.Options lastOptions;
private static TerrainPass terrainPass;
private static volatile int vanillaSectionCount;
private static volatile int vanillaLayerMask;
private static final TerrainOwnershipGate ownershipGate=new TerrainOwnershipGate();
private static long lastLiveFailureLogMs;
private static final float[] liveMvp=new float[16];
private static final org.joml.Matrix4f LIVE_MATRIX = new org.joml.Matrix4f();

/** Hard cap on extraction retry attempts — prevents unlimited retry chains. */
private static final int MAX_EXTRACT_RETRIES = 3;

/** Rate-limit extraction error log spam (max one log per 500ms). */
private static volatile long lastExtractErrorLogMs = 0L;
private static final long MIN_EXTRACT_LOG_INTERVAL_MS = 500L;

/** Rate-limit extractPool resize to once per second to prevent thread churn. */
private static volatile long lastExtractPoolResizeMs = 0L;

/**
 * Monotonically increasing renderer/world generation counter.
 *
 * <p>This closes the World-A → World-B stale-task integration gap that revision-only
 * checks cannot prevent. When a task is created it captures the current generation
 * ({@link #worldGeneration}). Before integrating any completed mesh, the worker verifies
 * {@code taskGeneration == worldGeneration.get()}.
 *
 * <p>The gap: if World-A had a section at (0,0,0) with revision 3, and the player
 * disconnects ({@code revisions.clear()}) then World-B starts and a new section at (0,0,0)
 * is registered at revision 1, the World-A task (revision=3) correctly fails the revision
 * check (rev.get()=1 != 3). BUT if World-A's last revision for that section happened to be
 * 1, both revision checks pass and the World-A geometry leaks into World-B.
 *
 * <p>The generation counter increments on every {@code resetWorld()} and {@code stop()}.
 * The cost is a single volatile long read per job (no locking needed).
 */
private static final java.util.concurrent.atomic.AtomicLong worldGeneration =
        new java.util.concurrent.atomic.AtomicLong(0L);

public static final class BoundedExtractionPriorityQueue extends PriorityBlockingQueue<Runnable> {
    private final int capacity;
    private double currentWorstDistSq = Double.MIN_VALUE;

    public BoundedExtractionPriorityQueue(int capacity) {
        super(capacity);
        this.capacity = capacity;
    }

    @Override
    public synchronized boolean offer(Runnable e) {
        if (size() >= capacity) {
            if (e instanceof PrioritizedExtractionTask incoming) {
                if (currentWorstDistSq > 0.0 && incoming.distSq >= currentWorstDistSq) {
                    return false;
                }

                Object[] elements = this.toArray();
                PrioritizedExtractionTask worst = null;
                int start = elements.length / 2;
                double nextWorst = Double.MIN_VALUE;
                for (int i = start; i < elements.length; i++) {
                    if (elements[i] instanceof PrioritizedExtractionTask task) {
                        if (worst == null || task.distSq > worst.distSq) {
                            if (worst != null) nextWorst = Math.max(nextWorst, worst.distSq);
                            worst = task;
                        } else {
                            nextWorst = Math.max(nextWorst, task.distSq);
                        }
                    }
                }
                if (worst != null) {
                    currentWorstDistSq = worst.distSq;
                }
                if (worst != null && incoming.distSq < worst.distSq) {
                    this.remove(worst);
                    worst.cancel();
                    pendingExtractions.remove(worst.posKey, worst);
                    destiny.renderer.hud.CaesiumFrameProfiler.recordExtractRejected();
                    // Only retry displaced tasks that have not already hit the retry ceiling.
                    // This prevents a retry chain where a task displaced at attempt=2 gets
                    // another scheduleExtractRetry, producing attempt=3 → re-displaced → attempt=4 etc.
                    if (worst.attempt < MAX_EXTRACT_RETRIES - 1) {
                        destiny.renderer.hud.CaesiumFrameProfiler.recordExtractDeferred();
                        scheduleExtractRetry(worst);
                    } else {
                        // Retry ceiling reached for displaced task — clean up its MeshJob.
                        if (worst.meshJob != null)
                            destiny.renderer.chunk.MeshingJobSystem.completeJob(worst.posKey, worst.meshJob);
                    }
                    currentWorstDistSq = Math.max(nextWorst, incoming.distSq);
                    return super.offer(incoming);
                }
            }
            return false;
        }
        if (e instanceof PrioritizedExtractionTask task) {
            currentWorstDistSq = Math.max(currentWorstDistSq, task.distSq);
        }
        return super.offer(e);
    }

    @Override
    public synchronized Runnable poll() {
        Runnable r = super.poll();
        if (isEmpty()) {
            currentWorstDistSq = Double.MIN_VALUE;
        }
        return r;
    }
}

private static final class PrioritizedExtractionTask implements Runnable, Comparable<PrioritizedExtractionTask> {
    final ChunkSectionPos pos;
    final long posKey;
    final int revision;
    final double distSq;
    final long submitNs;
    final int attempt;
    /** The MeshJob registered for this extraction, for proper completeJob() cleanup. */
    final destiny.renderer.chunk.MeshJob meshJob;
    /**
     * World/renderer generation at the time this task was created.
     * Validated against {@link CaesiumIntegration#worldGeneration} before integration
     * to prevent World-A tasks from completing into World-B when revisions accidentally match.
     */
    final long generation;
    private volatile boolean cancelled = false;

    PrioritizedExtractionTask(ChunkSectionPos pos, long posKey, int revision, double distSq, int attempt,
                              destiny.renderer.chunk.MeshJob meshJob) {
        this.pos = pos;
        this.posKey = posKey;
        this.revision = revision;
        this.distSq = distSq;
        this.submitNs = System.nanoTime();
        this.attempt = attempt;
        this.meshJob = meshJob;
        // Capture the current world generation at task-creation time, not at run time.
        this.generation = worldGeneration.get();
    }

    void cancel() {
        this.cancelled = true;
    }

    @Override
    public void run() {
        if (com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread()) {
            destiny.renderer.hud.CaesiumFrameProfiler.recordCallerExecutedHeavyJob();
            LOGGER.warning("[Caesium] CRITICAL VIOLATION: Section extraction executed on render thread!");
        }
        if (cancelled || !started) {
            pendingExtractions.remove(posKey, this);
            if (meshJob != null) destiny.renderer.chunk.MeshingJobSystem.completeJob(posKey, meshJob);
            return;
        }
        // World-generation guard (Item 3): drop immediately if we are from a different world
        // lifetime. This cannot be spoofed by revision coincidence.
        if (worldGeneration.get() != generation) {
            return; // finally block will handle cleanup
        }
        Thread.currentThread().setPriority(destiny.renderer.scheduler.ThreadPriorityManager.getWorkerPriority());
        long extractStartNs = System.nanoTime();
        long waitNs = extractStartNs - submitNs;
        try {
            MinecraftClient mc2 = MinecraftClient.getInstance();
            ClientWorld world = mc2 == null ? null : mc2.world;
            if (world == null) return;
            AtomicInteger rev = revisions.get(posKey);
            if (rev == null || rev.get() != revision) return;

            net.minecraft.world.chunk.WorldChunk chunk = world.getChunk(pos.getSectionX(), pos.getSectionZ());
            if (chunk != null) {
                int secIdx = pos.getSectionY() - world.getBottomSectionCoord();
                net.minecraft.world.chunk.ChunkSection[] sections = chunk.getSectionArray();
                if (secIdx >= 0 && secIdx < sections.length) {
                    net.minecraft.world.chunk.ChunkSection section = sections[secIdx];
                    if (section == null || section.isEmpty()) {
                        // Re-validate generation before integrating the empty mesh.
                        if (worldGeneration.get() != generation) return;
                        RenderWorld.LayeredSectionMesh emptyMesh = new RenderWorld.LayeredSectionMesh(
                            pos.getSectionX(), pos.getSectionZ(), pos.getSectionY(), revision, java.util.List.of(), true, true);
                        destiny.renderer.chunk.CompletedMesh completed = destiny.renderer.chunk.CompletedMesh.from(
                            posKey, pos.getSectionX(), pos.getSectionZ(), pos.getSectionY(), revision, emptyMesh, 0L);
                        destiny.renderer.chunk.MeshingJobSystem.offerCompleted(completed);
                        long extractEndNs = System.nanoTime();
                        destiny.renderer.hud.CaesiumFrameProfiler.recordPipelineLatencies(waitNs, extractEndNs - extractStartNs, 0L, 0L);
                        return;
                    }
                }
            }

            ChunkSectionData data = threadLocalSectionData.get();
            data.populate(world, pos.getMinX() - 1, pos.getMinY() - 1, pos.getMinZ() - 1);
            // World-switch race guard: if the world changed during populate(), the data is
            // from a different world — drop without retry to avoid corrupting the new world.
            if (mc2.world != world) return;
            // Check both generation and revision after populate() to cover the race where:
            // 1. populate() reads World-A data, 2. disconnect fires (generation increments),
            // 3. World-B loads the same section. Both checks are needed independently.
            if (worldGeneration.get() != generation) return;
            if (rev.get() != revision) return;
            RenderWorld.LayeredSectionMesh mesh = BakedSectionExtractor.extract(pos, data, world,
                    mc2.getBlockRenderManager(), mc2.getBlockColors(), revision);
            long extractEndNs = System.nanoTime();
            long durationNs = extractEndNs - extractStartNs;
            destiny.renderer.hud.CaesiumFrameProfiler.recordPipelineLatencies(waitNs, durationNs, 0L, 0L);
            if (engine != null && engine.policy() != null) {
                engine.policy().recordMeshingCost(durationNs);
            }
            if (mesh != null) {
                // Final generation gate before we hand the mesh to the render-thread queue.
                if (worldGeneration.get() != generation) return;
                destiny.renderer.chunk.CompletedMesh completed =
                    destiny.renderer.chunk.CompletedMesh.from(posKey, pos.getSectionX(), pos.getSectionZ(), pos.getSectionY(), revision, mesh, durationNs);
                destiny.renderer.chunk.MeshingJobSystem.offerCompleted(completed);
            }
        } catch (Throwable t) {
            // Rate-limit extraction error logs to avoid log spam during explosion bursts.
            long now = System.currentTimeMillis();
            if (now - lastExtractErrorLogMs > MIN_EXTRACT_LOG_INTERVAL_MS) {
                lastExtractErrorLogMs = now;
                LOGGER.warning("[Caesium] Section extraction failed for " + pos + " (attempt " + attempt + "): " + t);
            }
            if (attempt < MAX_EXTRACT_RETRIES) {
                destiny.renderer.hud.CaesiumFrameProfiler.recordExtractDeferred();
                scheduleExtractRetry(this);
            }
        } finally {
            pendingExtractions.remove(posKey, this);
            // Always clean up MeshJob tracking so pendingJobs doesn't grow unboundedly.
            if (meshJob != null) destiny.renderer.chunk.MeshingJobSystem.completeJob(posKey, meshJob);
        }
    }

    @Override
    public int compareTo(PrioritizedExtractionTask other) {
        int cmp = Double.compare(this.distSq, other.distSq);
        if (cmp != 0) return cmp;
        return Long.compare(this.submitNs, other.submitNs);
    }
}

private static void scheduleExtractRetry(PrioritizedExtractionTask task) {

    if (!started || extractRetryScheduler == null || task.cancelled) return;
    // Staleness check: if the revision has already advanced, this task's result would be
    // discarded on completion anyway — drop it without scheduling another retry.
    AtomicInteger currentRev = revisions.get(task.posKey);
    if (currentRev == null || currentRev.get() != task.revision) return;
    if (task.attempt >= MAX_EXTRACT_RETRIES) return;
    long delayMs = 50L * (1L << task.attempt);
    try {
        extractRetryScheduler.schedule(() -> {
            if (!started || task.cancelled) return;
            AtomicInteger rev = revisions.get(task.posKey);
            // Staleness check inside the scheduled body as well — the revision may have
            // advanced while the retry was waiting in the scheduler.
            if (rev == null || rev.get() != task.revision) return;
            int nextAttempt = task.attempt + 1;
            PrioritizedExtractionTask retryTask = new PrioritizedExtractionTask(
                    task.pos, task.posKey, task.revision, task.distSq, nextAttempt, task.meshJob);
            pendingExtractions.put(task.posKey, retryTask);
            try {
                if (extractPool != null && !extractPool.isShutdown()) {
                    extractPool.execute(retryTask);
                }
            } catch (RejectedExecutionException ignored) {
                destiny.renderer.hud.CaesiumFrameProfiler.recordExtractRejected();
                // Only schedule a further retry if still within the cap.
                if (nextAttempt < MAX_EXTRACT_RETRIES) {
                    destiny.renderer.hud.CaesiumFrameProfiler.recordExtractDeferred();
                    scheduleExtractRetry(retryTask);
                } else {
                    pendingExtractions.remove(task.posKey, retryTask);
                    if (retryTask.meshJob != null)
                        destiny.renderer.chunk.MeshingJobSystem.completeJob(task.posKey, retryTask.meshJob);
                }
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    } catch (RejectedExecutionException ignored) {
        // Scheduler shutting down
    }
}

private CaesiumIntegration(){}

private static boolean dormant(){
return engine==null||engine.graph().activePassCount()==0||!destiny.renderer.compat.WorkAllotment.ownsTerrain();
}

public static void start(){
if(started)return;
    GpuBackend backend=BackendSelector.select(preference(),devicePreference());
    RendererConfig cfg=RendererConfig.get();
    int meshThreads=Math.max(1,cfg.resolvedMeshingThreads());
    int framesInFlight=Math.max(1,cfg.cpuRenderAhead);
    engine=new CaesiumEngine(backend,meshThreads,framesInFlight);
    terrainPass=new TerrainPass(backend, engine.scene());
    boolean enableTerrain = cfg.experimentalTerrainPipeline && destiny.renderer.compat.WorkAllotment.ownsTerrain();
    terrainPass.setEnabled(enableTerrain);
    engine.graph().addPass(terrainPass);
    engine.start();
    boolean singleplayer = false;
    try {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null) singleplayer = mc.isInSingleplayer();
    } catch (Throwable ignored) {}
    int cores = Runtime.getRuntime().availableProcessors();
    int extractThreads = destiny.renderer.scheduler.ThreadPriorityManager.calculateSafeWorkerLimit(cores, singleplayer);
    int queueSize = Math.max(2048, MAX_EXTRACT_QUEUE * extractThreads);
    extractRetryScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Caesium-ExtractRetry");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    extractPool=new ThreadPoolExecutor(extractThreads,extractThreads,0L,TimeUnit.MILLISECONDS,
    new BoundedExtractionPriorityQueue(queueSize),
    r->{
        Thread t=new Thread(r,"Caesium-SectionExtract");
        t.setDaemon(true);
        t.setPriority(destiny.renderer.scheduler.ThreadPriorityManager.getWorkerPriority());
        return t;
    },
    (r, exec) -> {
        destiny.renderer.hud.CaesiumFrameProfiler.recordExtractRejected();
        if (r instanceof PrioritizedExtractionTask task) {
            if (task.attempt < MAX_EXTRACT_RETRIES) {
                destiny.renderer.hud.CaesiumFrameProfiler.recordExtractDeferred();
                scheduleExtractRetry(task);
            } else {
                pendingExtractions.remove(task.posKey, task);
                if (task.meshJob != null)
                    destiny.renderer.chunk.MeshingJobSystem.completeJob(task.posKey, task.meshJob);
            }
        }
    });
    started=true;
    ResourceShare.logSummary();
    if(dormant()){
        LOGGER.info("[Caesium] Engine idle — no active render pass registered. Frame loop and section extraction are gated off until a pass consumes the scene.");
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
if(RendererConfig.get().experimentalTerrainPipeline){
    return BackendSelector.Preference.OPENGL;
}
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

private static final List<FrameInput.Explosion> EMPTY_EXPLOSIONS = List.of();

public static void render(){
updateScene();
}

private static RenderWorld updateScene(){
if(!started||engine==null)return null;
if(dormant())return null;
MinecraftClient mc=MinecraftClient.getInstance();
if(mc==null||mc.world==null||mc.gameRenderer==null)return null;
Camera camera=mc.gameRenderer.getCamera();
Vec3d pos=camera.getCameraPos();
int vd=mc.options!=null?mc.options.getViewDistance().getValue():12;
boolean fullbright = mc.options != null && mc.options.getGamma().getValue() > 0.99;
if (lastOptions == null || lastOptions.fullbright() != fullbright || lastOptions.renderDistance() != vd) {
    RenderWorld.Options opts = new RenderWorld.Options(fullbright, vd, 1000, 300);
    engine.scene().push(new DeltaCommand.OptionChanged(opts));
    lastOptions = opts;
}
float pitch=camera.getPitch();
float yaw=camera.getYaw();
float movement=Math.abs((float)pos.x-lastCamX)+Math.abs((float)pos.y-lastCamY)+Math.abs((float)pos.z-lastCamZ);
float rotation=Math.abs(pitch-lastPitch)+Math.abs(yaw-lastYaw);
if(Float.isNaN(lastPitch)||movement>=CAMERA_POSITION_EPSILON||rotation>=CAMERA_ROTATION_EPSILON){
RenderWorld.Camera cam=new RenderWorld.Camera((float)pos.x,(float)pos.y,(float)pos.z,pitch,yaw,70f,mc.world.getTime());
engine.scene().push(new DeltaCommand.CameraMoved(cam));
lastCamX=(float)pos.x;
lastCamY=(float)pos.y;
lastCamZ=(float)pos.z;
lastPitch=pitch;
lastYaw=yaw;
}
destiny.renderer.hud.CaesiumFrameProfiler.beginWorldUpdate();
float targetMs = 6.94f;
if (mc != null && mc.options != null) {
    int maxFps = mc.options.getMaxFps().getValue();
    if (maxFps > 0 && maxFps < 260) {
        targetMs = 1000.0f / (float) maxFps;
    }
}
double lastFrameMs = destiny.renderer.hud.PerformanceOverlay.averageFrameMs();
if (engine != null && engine.policy() != null) {
    engine.policy().adjustWorkerPressure(lastFrameMs, targetMs, engine.pool());
    int desiredWorkers = engine.policy().dynamicWorkerLimit();
    if (extractPool != null && desiredWorkers >= 1) {
        long nowMs = System.currentTimeMillis();
        // Rate-limit pool resizes to once per second — frequent setCorePoolSize calls
        // can cause thread churn on pools that support dynamic sizing.
        if (nowMs - lastExtractPoolResizeMs >= 1000L) {
            int cur = extractPool.getCorePoolSize();
            if (cur != desiredWorkers) {
                if (desiredWorkers > cur) {
                    extractPool.setMaximumPoolSize(desiredWorkers);
                    extractPool.setCorePoolSize(desiredWorkers);
                } else {
                    extractPool.setCorePoolSize(desiredWorkers);
                    extractPool.setMaximumPoolSize(desiredWorkers);
                }
                lastExtractPoolResizeMs = nowMs;
            }
        }
    }
}
float extraBudgetMs = (engine != null && engine.policy() != null)
        ? engine.policy().availableExtraBudgetMillis(lastFrameMs, targetMs)
        : 1.5f;
destiny.renderer.chunk.MeshingJobSystem.drainCompletedMeshes(engine.scene(), extraBudgetMs);
RenderWorld world=engine.scene().update(engine.scene().published());
destiny.renderer.hud.CaesiumFrameProfiler.endWorldUpdate();
// Live terrain submission is driven from SectionRenderState.renderSection so it
// inherits the exact framebuffer/depth ordering of vanilla's three layer groups.
// The end-of-world hook only publishes the newest camera/section snapshot.
return world;
}

/** Captures the authoritative layer/section contract produced by vanilla extraction. */
public static void observeVanillaTerrainState(SectionRenderState state){
if(state==null||dormant())return;
vanillaSectionCount=state.chunkSectionInfos()==null?0:state.chunkSectionInfos().length;
int mask=0;
for(BlockRenderLayer layer:BlockRenderLayer.values()){
var draws=state.drawsPerLayer().get(layer);
if(draws!=null&&!draws.isEmpty())mask|=1<<layer.ordinal();
}
vanillaLayerMask=mask;
ownershipGate.beginFrame();
}

public static void captureFrameMatrices(Matrix4fc projection,Matrix4fc view){
if(dormant()||terrainPass==null||projection==null||view==null)return;
LIVE_MATRIX.set(projection).mul(view).get(liveMvp);
terrainPass.setExternalMvp(liveMvp);
}

/**
 * Staged live submission seam. It deliberately returns false until the backend has
 * rendered this group and the previous complete frame passed the coverage gate.
 */
public static boolean renderTerrainGroup(SectionRenderState state,
                                         BlockRenderLayerGroup group,
                                         net.minecraft.client.gl.GpuSampler terrainSampler){
if(!started||engine==null||terrainPass==null||state==null||group==null)return false;
if(dormant())return false;
int groupBit=groupBit(group);
boolean wasArmed=ownershipGate.mayCancelVanilla();
boolean success=false;
OpenGLBackend.ExternalTerrainState savedState=null;
try{
    if(!(engine.backend() instanceof OpenGLBackend glBackend))return false;
    if(!(com.mojang.blaze3d.systems.RenderSystem.getDevice() instanceof GlBackend minecraftGl))return false;
    Framebuffer target=group.getFramebuffer();
    if(target==null||target.getDepthAttachment()==null)return false;
    if(!(target.getColorAttachmentView() instanceof GlTextureView colorView))return false;
    if(!(state.textureView() instanceof GlTextureView atlasView))return false;
    MinecraftClient mc=MinecraftClient.getInstance();
    if(mc==null||mc.gameRenderer==null)return false;
    if(!(mc.gameRenderer.getLightmapTextureManager().getGlTextureView() instanceof GlTextureView lightView))return false;
    savedState=OpenGLBackend.ExternalTerrainState.capture();
    int framebuffer=colorView.getOrCreateFramebuffer(minecraftGl.getBufferManager(),target.getDepthAttachment());
    int atlas=((GlTexture)atlasView.texture()).getGlId();
    int lightmap=((GlTexture)lightView.texture()).getGlId();
    if(!(terrainSampler instanceof GlSampler atlasSampler))return false;
    var lightSamplerBase=com.mojang.blaze3d.systems.RenderSystem.getSamplerCache().get(FilterMode.LINEAR);
    if(!(lightSamplerBase instanceof GlSampler lightSampler))return false;
    int layerMask=groupLayerMask(group);
    terrainPass.configureLiveGroup(layerMask,vanillaLayerMask,vanillaSectionCount);
    RenderWorld world=updateScene();
    if(world==null)return false;
    glBackend.bindExternalTerrainPass(framebuffer,target.textureWidth,target.textureHeight,
            atlas,lightmap,atlasSampler.getSamplerId(),lightSampler.getSamplerId(),
            group!=BlockRenderLayerGroup.OPAQUE);
    executeGraph(world);
    success=terrainPass.lastExecution().valid();
    // Per-quad translucent resorting is not yet connected to the live upload cache.
    // Draw it alongside vanilla for validation, but never claim ownership of a frame
    // containing that layer until ordering parity is implemented.
    int translucentBit=1<<BlockRenderLayer.TRANSLUCENT.ordinal();
    if(group==BlockRenderLayerGroup.TRANSLUCENT&&(vanillaLayerMask&translucentBit)!=0){
        success=false;
    }
}catch(Throwable t){
    long now=System.currentTimeMillis();
    if(now-lastLiveFailureLogMs>5000L){
        lastLiveFailureLogMs=now;
        LOGGER.warning("[Caesium] Live terrain group failed; using vanilla fallback: "+t);
    }
}finally{
    if(savedState!=null&&engine!=null&&engine.backend() instanceof OpenGLBackend glBackend){
        glBackend.endExternalTerrainPass(savedState);
    }
    ownershipGate.recordGroup(groupBit,success);
}
return wasArmed&&success;
}

private static int groupBit(BlockRenderLayerGroup group){
if(group==BlockRenderLayerGroup.OPAQUE)return TerrainOwnershipGate.OPAQUE;
if(group==BlockRenderLayerGroup.TRANSLUCENT)return TerrainOwnershipGate.TRANSLUCENT;
if(group==BlockRenderLayerGroup.TRIPWIRE)return TerrainOwnershipGate.TRIPWIRE;
return 0;
}

private static int groupLayerMask(BlockRenderLayerGroup group){
int mask=0;
for(BlockRenderLayer layer:group.getLayers())mask|=1<<layer.ordinal();
return mask;
}

public static void resetTerrainOwnership(){ownershipGate.reset();}

private static void executeGraph(RenderWorld world){
float deltaMs = (float) Math.max(1.0, destiny.renderer.hud.PerformanceOverlay.averageFrameMs());
FrameInput input=new FrameInput(world,deltaMs,System.currentTimeMillis(),false,EMPTY_EXPLOSIONS);
engine.scheduler().beginFrame(input);
destiny.renderer.hud.CaesiumFrameProfiler.beginRenderGraph();
engine.scheduler().execute(input);
destiny.renderer.hud.CaesiumFrameProfiler.endRenderGraph();
destiny.renderer.hud.CaesiumFrameProfiler.beginBackend();
engine.scheduler().endFrame(input);
destiny.renderer.hud.CaesiumFrameProfiler.endBackend();
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
float dx=cx-(float)camPos.x;
float dy=cy-(float)camPos.y;
float dz=cz-(float)camPos.z;
if(Math.abs(dx)>limit||Math.abs(dy)>512f||Math.abs(dz)>limit){
return;
}
double distSq = dx*dx + dy*dy + dz*dz;
pruneRevisions(camPos,limit);
long posKey=pos.asLong();


AtomicInteger rev=revisions.computeIfAbsent(posKey,k->new AtomicInteger());
int nextRev=rev.incrementAndGet();
destiny.renderer.chunk.MeshingJobSystem.setVersion(posKey, nextRev);

PrioritizedExtractionTask existing = pendingExtractions.remove(posKey);
if (existing != null) {
    existing.cancel();
}

destiny.renderer.chunk.MeshJob meshJob = new destiny.renderer.chunk.MeshJob(
    pos, posKey, nextRev,
    (distSq < 256.0 ? destiny.renderer.chunk.MeshJob.Urgency.CRITICAL
                    : (distSq < 4096.0 ? destiny.renderer.chunk.MeshJob.Urgency.VISIBLE
                                       : destiny.renderer.chunk.MeshJob.Urgency.PREDICTIVE)),
    destiny.renderer.chunk.MeshJob.JobReason.CHUNK_LOAD);
destiny.renderer.chunk.MeshingJobSystem.registerJob(meshJob);
destiny.renderer.chunk.MeshingJobSystem.tickAging();

PrioritizedExtractionTask task = new PrioritizedExtractionTask(pos, posKey, nextRev, distSq, 0, meshJob);
pendingExtractions.put(posKey, task);
try{
    extractPool.execute(task);
}catch(java.util.concurrent.RejectedExecutionException ignored){
    destiny.renderer.hud.CaesiumFrameProfiler.recordExtractRejected();
    destiny.renderer.hud.CaesiumFrameProfiler.recordExtractDeferred();
    scheduleExtractRetry(task);
}
}

public static void updateWorkerLimits(boolean singleplayer) {
    if (extractPool != null) {
        int cores = Runtime.getRuntime().availableProcessors();
        int targetThreads = destiny.renderer.scheduler.ThreadPriorityManager.calculateSafeWorkerLimit(cores, singleplayer);
        int current = extractPool.getCorePoolSize();
        if (current != targetThreads) {
            if (targetThreads > current) {
                extractPool.setMaximumPoolSize(targetThreads);
                extractPool.setCorePoolSize(targetThreads);
            } else {
                extractPool.setCorePoolSize(targetThreads);
                extractPool.setMaximumPoolSize(targetThreads);
            }
        }
    }
}

public static void resetWorld() {
    // Increment the world-generation counter BEFORE clearing revisions.
    // This ensures any in-flight extraction tasks from the previous world fail
    // the generation check in run(), even if their section revision accidentally
    // matches a newly registered section in the new world.
    worldGeneration.incrementAndGet();
    pendingExtractions.clear();
    revisions.clear();
    if (engine != null && engine.scene() != null) {
        engine.scene().clear();
    }
    if (terrainPass != null) {
        terrainPass.clear();
    }
    vanillaSectionCount = 0;
    vanillaLayerMask = 0;
    ownershipGate.reset();
}

public static void unloadChunk(long chunkX, long chunkZ) {
    if (engine != null && engine.scene() != null) {
        engine.scene().removeChunk(chunkX, chunkZ);
    }
    if (terrainPass != null) {
        terrainPass.unloadChunk(chunkX, chunkZ);
    }
}

private static void pruneRevisions(Vec3d camPos,float limit){
if(revisions.size()<4096)return;
revisions.entrySet().removeIf(e->{
long k=e.getKey();
float px=(ChunkSectionPos.unpackX(k)<<4)+8f;
float py=(ChunkSectionPos.unpackY(k)<<4)+8f;
float pz=(ChunkSectionPos.unpackZ(k)<<4)+8f;
return Math.abs(px-(float)camPos.x)>limit||Math.abs(py-(float)camPos.y)>limit||Math.abs(pz-(float)camPos.z)>limit;
});
}

public static void stop(){
worldGeneration.incrementAndGet();
if(extractPool!=null){
extractPool.shutdownNow();
extractPool=null;
}
if(extractRetryScheduler!=null){
extractRetryScheduler.shutdownNow();
extractRetryScheduler=null;
}
pendingExtractions.clear();
revisions.clear();
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
if(terrainPass!=null){
terrainPass.clear();
terrainPass=null;
}
vanillaSectionCount=0;
vanillaLayerMask=0;
ownershipGate.reset();
lastPitch=Float.NaN;
lastYaw=Float.NaN;
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

public static int vanillaSectionCount(){return vanillaSectionCount;}
public static int vanillaLayerMask(){return vanillaLayerMask;}
}
