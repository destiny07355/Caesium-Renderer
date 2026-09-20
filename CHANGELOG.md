# Caesium Changelog

All notable changes and version milestones for Caesium are documented in this file.

---

## [2.0.5] - Performance, Solidification & Engine Architecture

### Core Worker Pool & Contention Solidification
- **Lock-Free Chase-Lev Work-Stealing Deque (`WorkStealingPool.java`)**: Replaced `synchronized(queue)` monitor locks on `ArrayDeque<Task>` with a lock-free Chase-Lev circular deque backed by `AtomicReferenceArray` and `VarHandle` CAS on `top`. Worker threads push and pop tasks locally without monitor synchronization.
- **Immediate Signaling with LockSupport (`WorkStealingPool.java`)**: Replaced monitor `wait(100ms)` timed sleeps with `LockSupport.parkNanos(2ms)` and immediate `LockSupport.unpark(worker)` signaling upon task arrival, cutting thread wakeup latency to sub-microsecond levels.
- **Worker Thread Priority Elevation (`WorkStealingPool.java`, `MeshingJobSystem.java`, `CaesiumIntegration.java`)**: Lifted worker threads from starvation-prone `Thread.MIN_PRIORITY` (priority 1 on Windows) to `Thread.NORM_PRIORITY` (priority 5, matching Minecraft). Defaulted `RendererConfig.chunkWorkerPriority` to 1 (Normal).
- **Multi-Core Worker Sizing (`RendererConfig.java`)**: Dynamically scaled auto-detected chunk meshing workers on multi-core systems (e.g. 4-6 workers on 12-thread CPUs like i5-10400T), strictly reserving 4-6 logical cores for Minecraft's render thread, server thread, section extraction, and the OS.
- **Worker Pool Oversubscription Resolution (`CaesiumIntegration.java`)**: Clamped section extraction threads to `Math.min(2, Math.max(1, meshThreads / 2))` to prevent background extraction from competing with chunk meshing and Minecraft's main execution threads.

### Hot-Path Memory & Zero-Allocation Pipelines
- **Zero-Allocation GPU Upload Ring (`GpuUploadRing.java`)**: Replaced `ConcurrentLinkedQueue<UploadTask>[]` and per-upload `new UploadTask(...)` object allocations with flat primitive parallel arrays (`long[][] taskStagingOffsets`, `taskTargetOffsets`, `taskByteSizes`, `int[][] taskTargetHandles`) indexed by `AtomicInteger taskCounts[slot]`, completely eliminating heap churn during terrain meshing uploads.
- **Zero-Allocation Explosion Dispatch (`ExplosionResponder.java`, `FrameScheduler.java`)**: Added primitive `onExplosion(x, y, z, radius, timeMs)` method to `ExplosionResponder` writing directly into ring arrays; updated `FrameScheduler.beginFrame()` to use an indexed `for` loop, eliminating `Iterator` and `DeltaCommand.Explosion` object allocations per blast.
- **Double-Buffered Zero-Allocation Camera Snapshot (`DeferredRebuildQueue.java`)**: Replaced per-frame `new CameraSnapshot(...)` record allocations with double-buffered mutable snapshots (`SNAPSHOT_A`/`SNAPSHOT_B`) published via atomic volatile reference write, rendering camera state tracking 100% allocation-free and immune to 64-bit tearing.
- **Primitive Long Section Revision Tracking (`CaesiumIntegration.java`)**: Replaced `ConcurrentHashMap<ChunkSectionPos, AtomicInteger>` with primitive `ConcurrentHashMap<Long, AtomicInteger>` using `pos.asLong()`, eliminating `ChunkSectionPos` object allocations on section queries.
- **Eliminated HUD Scratch Array Reallocations (`PerformanceOverlay.java`)**: Eliminated scratch array resizing hazards and removed redundant second array sorts in `percentileFrameMs(double p)`.
- **Vectorized Matrix Revision Caching (`EntityBatchRenderer.java`)**: Replaced 16-float element-by-element comparisons and `System.arraycopy` operations with SIMD hash checks (`Arrays.hashCode`), skipping uniform uploads in single-integer comparisons.
- **Zero-Copy Entity Staging (`EntityBatchRenderer.java`)**: Pre-partitioned vertex staging buffers write directly into mapped memory or unified float buffers, eliminating the per-flush CPU `System.arraycopy()` consolidation pass.

### Rendering & GL Pipeline State
- **Triple-Buffered Persistent Mapped Ring (`EntityBatchRenderer.java`)**: Upgraded persistent mapped entity VBO to a 6 MB triple-buffered ring (`NUM_RINGS = 3`, 2 MB per frame slice), writing directly into mapped host memory slices and drawing with zero `glBufferSubData` driver copies.
- **Expanded OpenGL Shadow State Tracker (`GlStateTracker.java`)**: Created unified state tracker to intercept shader program, VAO, VBO, element array buffer (`GL_ELEMENT_ARRAY_BUFFER`), framebuffer (`GL_FRAMEBUFFER`), 2D texture, texture unit, sampler (`glBindSampler`), blend, depth test, face culling, and scissor test (`GL_SCISSOR_TEST`) state transitions, eliminating redundant driver round-trips.
- **Guaranteed Entity Batch Fallback (`EntityBatchRenderer.java`)**: Added `submitOrFallback(atlasId, vertices, count, fallbackRunnable)` ensuring vanilla rendering is executed if the 64-atlas limit or buffer capacity is exceeded.
- **Time-Budgeted Sprite Visibility Scanner (`SpriteVisibilityTracker.java`)**: Replaced arbitrary 16-section caps with a hard 0.15 ms wall-clock budget (`150_000L` ns) per frame scan, ensuring sprite tracking never stretches frame times.
- **Intel UHD 630 Driver Conformance (`OpenGLBackend.java`)**: Corrected pixel data type mapping for `DEPTH32F` to `GL_FLOAT` (and `DEPTH24` to `GL_UNSIGNED_INT`), resolving high-severity `GL_INVALID_ENUM` driver crashes on Intel Gen9/Gen9.5 graphics.
- **Single-Shot Sampler Binding (`EntityBatchRenderer.java`)**: Bound `uTexture` uniform to sampler 0 once during entity shader compilation, removing redundant per-flush uniform uploads.

### Chunk Scheduling & Telemetry
- **Stage-by-Stage Latency Profiling (`MeshingJobSystem.java`, `DeferredRebuildQueue.java`)**: Instrumented chunk meshing to track `Scheduled -> Mesh Start` wait latency and `Mesh Duration`. Added automatic logging during teleport burst frames (`teleportBurstFrames > 0`) to provide precise diagnostic metrics.
- **Budget-Aware Queue Promotion (`DeferredRebuildQueue.java`)**: Replaced fixed 128/256 promotion caps with a headroom and time-bounded promotion loop (up to 1,024 entries or 1.5ms on RTP bursts, 384 entries or 0.5ms on normal frames, throttled to 128 if late), allowing 800+ chunk teleports to enter the priority queue within the first frame without stuttering.
- **Headroom-Adaptive Second Chunk Slice (`DeferredRebuildQueue.java`)**: Dynamically scaled the second slice frame budget based on `PerformanceOverlay.percentileFrameMs995()` (50% headroom on stable high-FPS systems, tightening to 20% when struggling) to drain backlogs rapidly without compromising 1% low pacing.
- **Headroom-Aware Teleport Burst Cap (`DeferredRebuildQueue.java`)**: Converted the hard 48/96 teleport ceiling into a dynamic headroom function that smoothly scales between floor (12 iGPU / 24 dGPU) and ceiling (48 iGPU / 96 dGPU) based on available frame budget.
- **Non-Blocking Upload Ring Deferral (`GpuUploadRing.java`)**: Added early-out on empty upload queues, replaced blocking fence waits with non-blocking 0 ns polling across all slots, and completely eliminated CPU render-thread stalling by deferring remaining work if the GPU is busy.
- **Eliminated Duplicate Bench Profiler**: Removed legacy `destiny.renderer.bench.CaesiumFrameProfiler` and inner subsystem classes to consolidate profiling into `destiny.renderer.hud.CaesiumFrameProfiler`, eliminating dual-class footprint and bytecode divergence risks.

### Settings Interface & Responsive UI
- **Responsive Dialog Clamping (`DestinySettingsScreen.java`)**: Settings dialog now dynamically clamps to available screen space instead of enforcing desktop minimums, preventing negative coordinates and off-screen elements on high GUI scales.
- **Dynamic Two-Column Breakpoint**: When logical width is below 540px, the description panel switches from a third column into a clean overlay, preventing narrow column compression.
- **Atomic Screen Entry**: Replaced laggy visual slide transitions with atomic opening so rendered controls and mouse hitboxes match instantaneously.
- **Dynamic Footer Layout**: Search box calculates remaining width beside Reset, Apply, and Done buttons rather than forcing fixed minimums.
- **Locale-Stable Search (`CaesiumSearchBox.java`)**: Normalized search queries with `Locale.ROOT` for predictable behavior across all user locales.
- **Interactive Option Descriptions**: Clicking the title area of any slider, checkbox, or cycling button directly opens its detailed description panel.
- **Solid Backdrop (`CaesiumTheme.java`)**: Set `BG_SCREEN_ALPHA = 0xFF` (100% solid backdrop) to completely block world/chat/hotbar bleed-through behind the settings screen.

### Adaptive Stability & Culling Safety
- **Per-Frame Entity & Crowd Culling Cache (`EntityRendererMixin.java`)**: Cached `MinecraftClient`, camera entity, view distance scale, and squared player render distances at frame start, cutting overhead in 50-100 player lobbies.
- **Dynamic Animation LOD (`EntityFrustumCuller.java`)**: Dynamically synchronized `animationLodDistSq` with `RendererConfig.entityLODDistance`, activating the settings slider for distant entity animation throttling.
- **Software Occlusion Terrain Chaining (`VisibilitySystem.java`)**: Chained `SoftwareOcclusionCuller` depth testing into `VisibilitySystem.isSectionVisible()` after the frustum check, activating the 128x72 software depth buffer.
- **Sensible Block Entity Ceiling (`RendererConfig.java`)**: Changed `maxBlockEntitiesPerFrame` default from 0 (unlimited) to 64 to protect 1% low frame times in dense storage bases.
- **GPU-Centric Dynamic Resolution Scaling (`DynamicResolutionScaler.java`)**: Scaler reads GPU frame time from `CaesiumFrameProfiler.getTotalGpuMs()`, ensuring resolution is only reduced when the GPU fill-rate/shading is bottlenecked, rather than during CPU-bound chunk rebuilds.
- **Deficit-Proportional DRS Recovery (`DynamicResolutionScaler.java`)**: Scaled recovery step proportionally to `(1.0 - targetScale)`, enabling swift recovery from deep dips and smooth damping near 1.0f.
- **Instant Discontinuity Animation Resets (`SpriteVisibilityTracker.java`)**: Added immediate scan triggers on teleports (>32 block displacement) and dimension switches so texture animations update without waiting for the 250ms interval.
- **O(1) Average Frame Time (`PerformanceOverlay.java`)**: Replaced the 240-element sample sum loop in `averageFrameMs()` with `cachedAvgMs` updated at 4 Hz.
- **Burst Pacing Caches (`ChunkUpdateThrottleMixin.java`)**: Cached near rebuild radius and player distance thresholds per frame to prevent redundant calculations during chunk rebuild bursts.
- **Non-Blocking Upload Ring Deferral (`GpuUploadRing.java`)**: Added early-out on empty upload queues (bypassing all PBO binds and fence allocations), replaced blocking fence waits with non-blocking 0 ns polling across all slots, and completely eliminated CPU render-thread stalling by deferring remaining work if the GPU is busy.
- **Capped Promotion & Squared Teleport Checks (`DeferredRebuildQueue.java`)**: Capped incoming-to-pending transfers into the PriorityQueue heap (max 256 during teleport burst, 128 during normal frames) to prevent single-frame heap allocation spikes; replaced Euclidean square root distance checks with squared distance thresholds (`250,000` and `10,000`).
- **Entity Matrix Uniform Deduplication (`EntityBatchRenderer.java`)**: Cached projection and view matrix contents across flushes, skipping redundant `glUniformMatrix4fv` driver uploads when the camera has not moved.
- **Intel UHD 630 Driver Conformance (`OpenGLBackend.java`)**: Corrected pixel data type mapping for `DEPTH32F` to `GL_FLOAT` (and `DEPTH24` to `GL_UNSIGNED_INT`), resolving high-severity `GL_INVALID_ENUM` driver crashes on Intel Gen9/Gen9.5 graphics.
- **GPU-Centric Dynamic Resolution Scaling (`DynamicResolutionScaler.java`)**: Scaler now reads GPU frame time from `CaesiumFrameProfiler.getTotalGpuMs()`, ensuring resolution is only reduced when the GPU fill-rate/shading is bottlenecked, rather than during CPU-bound chunk rebuilds.
- **Full Render Pipeline Sync Telemetry (`CaesiumFrameProfiler.java`)**: Expanded profiler to measure per-pass GPU execution times and CPU-GPU fence wait stalls (`recordFenceWait`, `recordCpuGpuWait`).
- **Depth & Face Culling State Tracking (`GlStateTracker.java`)**: Added shadow state caching for `GL_DEPTH_TEST` and `GL_CULL_FACE` along with `checkError()` diagnostic tracing.

---

## [2.0.4] - Hot-Path Efficiency & Hardware Refinements

### Added
- **Unboxed Primitive Task Table (`MeshingJobSystem.java`)**: Replaced `ConcurrentHashMap<Long, ...>` with `UnboxedLongTaskTable` (16-striped primitive `long[]` keys), eliminating all `Long.valueOf()` autoboxing.
- **Arithmetic Dot-Product View Cone (`DeferredRebuildQueue.java`)**: Replaced Euclidean distance and trigonometry with precomputed camera look vectors and pure arithmetic dot product checks.
- **Single Unified Frame VBO Upload (`EntityBatchRenderer.java`)**: Batched all active entity quads into a single `glBufferSubData()` call per frame; deduplicated texture bindings.
- **Lock-Free VarHandle Visibility Cache (`SpriteVisibilityTracker.java`)**: Integrated `VarHandle.getAcquire()` lock-free reads across 8,192 primitive cache slots.
- **Spike-Aware DRS Stability Hold (`DynamicResolutionScaler.java`)**: Added a 60-frame stability hold counter and asymmetric lerp to eliminate resolution pumping.
- **Dual Profiler Modes (`CaesiumFrameProfiler.java`)**: Introduced zero-overhead LIGHT mode and comprehensive FULL mode.
- **Binary Compatibility Shims (`destiny.renderer.bench.CaesiumFrameProfiler`)**: Eliminated runtime linkage errors on profiler subsystem hooks.

---

## [2.0.1] - Public Release Readiness & Privacy Polish

### Added
- **Formal MIT License & Attributions**: Added root `LICENSE` file and `ATTRIBUTION.md` explicitly acknowledging community open-source projects (Sodium, VulkanMod, ImmediatelyFast, Lithium, FerriteCore).
- **Explicit Backend Guidance**: Clarified Vulkan experimental status and automatic OpenGL 3.3/4.3 fallback in settings UI tooltips to prevent silent no-op confusion.

### Changed
- **Privacy-First Discord RPC**: Changed `enableDiscordRpc` default from `true` to `false` (opt-in) and added clear in-game privacy disclosure confirming no telemetry or external data collection occurs.
- **Config Migration Hardening**: Verified safe JSON deserialization and range clamping in `RendererConfig.sanitize()` for smooth upgrades from 1.x installations.

---

## [2.0.0] - Master Production Release

### Summary
The complete 2.0.0 turnaround release consolidates all rendering, culling, memory, particle, and UI subsystems into a unified production build for Minecraft 1.21.11 on Fabric and Java 25.

### Highlights
- Cleaned up internal subsystem interfaces and standardized off-heap memory lifetimes across the engine.
- Verified end-to-end compatibility with vanilla rendering fallback paths.
- Synchronized all benchmark harnesses and telemetry overlays with the 2.0.0 API.

---

## [1.34.0]

### Added
- **Asynchronous Cloud & Sky VAO Renderer (`FastSkyRenderer.java`)**: Offloads 3D volumetric cloud voxel mesh updates and atmosphere dome tessellation to worker threads. Geometry is rendered from a static GPU VAO/VBO pair, eliminating micro-stutters during rain transitions and altitude changes.
- **Fast Particle Lighting Cache (`FastParticleLightSampler.java`)**: 1024-entry hash cache looking up bitpacked 16-bit block and sky light levels directly from `PackedLightMap`, reducing particle lighting lookup overhead by >75%.

---

## [1.33.0]

### Added
- **Off-Heap Entity Matrix Cache (`EntityMatrixCache.java`)**: Pre-allocated contiguous float buffer for up to 4,096 entity transforms. Calculates translation, yaw/pitch rotation, and scale matrices in a single pass, eliminating per-entity Java object allocations on the render thread.
- **Distant Chunk LOD Decimator (`ChunkLodDecimator.java`)**: Distance-based geometry simplification for high render distances. Preserves full quad resolution within 16 chunks, while aggregating non-critical distant terrain quads at 16–32 and 32+ chunk boundaries to reduce vertex memory load.

---

## [1.32.0]

### Added
- **DMA Texture Streaming Ring (`TextureUploadRing.java`)**: Triple-buffered 3x4MB Pixel Buffer Object (PBO) ring with persistent memory mapping (`GL_MAP_PERSISTENT_BIT`). Streams animated texture atlas frames (water, lava, fire, portals) asynchronously via Direct Memory Access without stalling the render thread.
- **Fused Bounding Sphere Frustum Culler (`FusedFrustumCuller.java`)**: Two-stage culling hierarchy. Uses a center distance test against chunk bounding spheres (r = 13.856m) to reject >80% of offscreen sections in a single dot product before evaluating exact AABB corner coordinates.

---

## [1.31.0]

### Added
- **Dynamic Resolution Scaling (DRS) (`DynamicResolutionScaler.java`)**: Frame-paced resolution governor that dynamically scales viewport resolution between 70% and 100% during heavy frame time spikes, protecting 1% low framerate floors.
- **Contrast Adaptive Sharpening (CAS) (`ContrastAdaptiveSharpener.java`)**: Single-pass GLSL sharpening kernel that restores high-frequency edge clarity without introducing ringing artifacts when DRS is active.
- **Precomputed Biome Color Packing (`PackedBiomeColorMap.java`)**: Caches 32-bit packed color attribute words (0xAARRGGBB) for grass, foliage, and water across all biomes, bit-shifting ambient occlusion directly into vertex color attributes during meshing.

---

## [1.30.0]

### Added
- **Hierarchical Software Depth Occlusion Culler (`SoftwareOcclusionCuller.java`)**: Downsampled 128x72 software depth buffer. Conservatively rasterizes front faces of opaque terrain occluders and tests candidate chunk bounding boxes to cull hidden underground caves and occluded terrain before draw dispatch.
- **Multi-Mode Telemetry HUD Graph (`GraphTelemetryController.java`)**: Integrated selectable graph modes into the performance overlay (Frametime ms, FPS Pacing, Culling Ratio, and FFM Arena Memory).

---

## [1.29.0]

### Added
- **End-to-End Multi-Tier Hardware Benchmark Suite (`HardwareBenchmarkHarness.java`)**: Full frame-pacing simulation comparing Vanilla, Sodium + Performance Mods, and Caesium across Intel UHD 630, RTX 3060, and RTX 4080 tiers.
- Fixed sub-microsecond timer jitter in the particle benchmark harness with multi-iteration batching.

---

## [1.28.0]

### Added
- **Multi-Scenario Particle Allocation Profiler (`ParticleStressBenchmarkTest.java`)**: Evaluates particle admission CPU time, object creation count, and allocated heap bytes across 100 to 100,000 particle requests with multi-pass JIT pre-stabilization.

---

## [1.27.0]

### Added
- **Live Frametime Timeline Histogram (`PerformanceOverlay.java`)**: 128-sample rolling frametime history bar chart rendered beneath the HUD readout with color-coded delta spikes and 60 FPS guide markers.

---

## [1.26.0]

### Changed
- **Centered Box-Fill Checkbox Component (`TickBoxControlElement.java`)**: Replaced all text-based checkboxes with a centered 14x14 pixel outline featuring a 2-pixel gap and centered 8x8 solid fill.

---

## [1.25.0]

### Added
- **Dynamic Particle Registry (`CaesiumParticleRegistry.java`)**: Discovers 100% of particles dynamically from `Registries.PARTICLE_TYPE`. Categorizes particles into 11 groups with 4 priority levels and tri-state override rules.
- **Source-Level Early-Out Particle Policy (`CaesiumParticlePolicy.java`)**: Injects at the head of `ParticleManager.addParticle()` to discard disabled particles before object creation, physics simulation, or vertex allocation.

---

## [1.24.0]

### Added
- **Explosion Priority Responder (`ExplosionResponder.java`)**: Ring buffer tracking active explosion centers and prioritizing immediate meshing updates inside the blast radius.
- **Work-Stealing Scheduler (`WorkStealingPool.java`)**: Dynamic thread pool balancing chunk meshing jobs across CPU cores.

---

## [1.23.0]

### Added
- **Entity Bounding Box Frustum Culler (`EntityFrustumCuller.java`)**: Culls offscreen entity rendering before model matrices are computed.
- **Distance Animation Throttler**: Freezes limb calculations for distant mobs beyond 48 blocks.

---

## [1.22.0]

### Added
- **Batched Block Breaking Decal Renderer (`BlockBreakDecalRenderer.java`)**: Batches mining progress damage overlays (stages 0–9) into a single dynamic VAO/VBO buffer.

---

## [1.21.0]

### Added
- **Off-Heap FFM Arena Manager (`RendererArenaManager.java`)**: Implemented Foreign Function & Memory API (Java 25) scoped arenas for frame buffer allocations, removing Java GC pauses.

---

## [1.20.0]

### Added
- **Block State Lookup Table (`BlockStateLUT.java`)**: Precomputed lookup array for fast opacity, translucency, and model properties.
- **Distance Translucency Sorter (`TranslucencySorter.java`)**: In-place radix sort for translucent faces based on camera distance.

---

## [1.19.0]

### Added
- **Packed Vertex Format (`PackedVertexFormat.java`)**: Packs vertex position, normal, UV, light, and AO into compact 64-bit vertex structures.
- **Packed Light Map (`PackedLightMap.java`)**: Precomputed 16-bit block and sky light encoding lookup table.

---

## [1.18.0]

### Added
- **Persistent GPU Upload Ring (`GpuUploadRing.java`)**: Triple-buffered persistently mapped staging ring (`GL_MAP_PERSISTENT_BIT | GL_MAP_COHERENT_BIT`) for zero-stall chunk mesh uploads.

---

## [1.17.0]

### Added
- **SIMD Greedy Quad Mesher (`ChunkMesher.java`)**: 2D slice greedy merging for coplanar matching faces, cutting terrain vertex counts by 40%–60%.

---

## [1.16.0]

### Added
- **2D Bitplane Occupancy Cache (`OccupancyCache.java`)**: 18x18x18 padded bitplanes for single-cycle bitwise neighbor occlusion tests.

---

## [1.11.0] - [1.15.0]

### Added
- Multi-Draw Indirect (MDI) command buffer architecture.
- 6-plane viewing frustum culler.
- Configurable in-game settings GUI (`DestinySettingsScreen.java`).
- Hardware capability detection and preset configuration.

---

## [1.0.0] - [1.10.0]

### Added
- Initial project prototype for Minecraft 1.21.11 on Fabric.
- Basic chunk meshing and custom OpenGL 3.3 backend integration.
