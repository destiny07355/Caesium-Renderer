# DestinyRenderer E2E Analysis & Test Case Catalog — Requirements R1–R5

**Agent**: `explorer_e2e_reqs1`  
**Date**: 2026-07-22  
**Target Scope**: Requirements R1 (Research Phase), R2 (Chunk Meshing & Upload), R3 (GPU Batching & Exposure Mixin), R4 (Memory Allocator & Pools), R5 (Shaders & Presets).  
**Downstream Integration Targets**: Requirements R6 (Entity/Particle/PvP), R7 (Startup/Join), R8 (Config GUI), R9 (Verification & Benchmark).

---

## 1. Executive Summary

DestinyRenderer is a next-generation GPU-driven Minecraft 1.21.11 (Fabric / Yarn mappings) rendering engine optimized for integrated Intel UHD graphics and modern dGPUs. This analysis establishes the feature specification and full 3-tier test catalog for Requirements R1 through R5.

### Summary of Sub-Features Enumerated (25 Sub-Features)
- **R1 (Research & Gap Analysis)**: R1.1 Sodium Chunk Graph/Pipeline, R1.2 VulkanMod Staging & GPU Indirect, R1.3 Lithium Explosion & Entity Optimizations, R1.4 ImmediatelyFast & Iris Hooks, R1.5 DestinyRenderer Gap & FPS Impact Matrix.
- **R2 (Chunk Meshing & Upload)**: R2.1 Radial Spiral Section Queue, R2.2 Frame Budget Throttling (≤2.5ms), R2.3 Greedy Meshing & Quad Merging, R2.4 Chunk Section Dependency Graph, R2.5 Zero-Allocation Thread-Local Meshers.
- **R3 (GPU Batching & Exposure Mixin)**: R3.1 Multi-Draw Indirect (MDI) Pipeline, R3.2 CPU Frustum & Occlusion Culling, R3.3 128³ Spatial Hierarchy Culling, R3.4 Distance Translucency Sorting, R3.5 Yarn 1.21.11 `Explosion.getExposure` Mixin Fix.
- **R4 (Memory Allocator & Pools)**: R4.1 Slab/TLSF Pool Allocator, R4.2 Free-List Recycling, R4.3 Persistent Coherent Mapping (Zero-Copy iGPU), R4.4 Multi-Pool Separation (Opaque/Cutout/Translucent/Entity/Particle), R4.5 Arena Buffer Compaction & Off-Heap Management.
- **R5 (Shaders & Presets)**: R5.1 Performance Preset Shaders (`#version 330 core`), R5.2 Balanced Preset Shaders (`#version 330 core`), R5.3 Quality Preset Shaders (`#version 330 core`), R5.4 FXAA Post-Processing Pass, R5.5 Shader Uniform Management & Preset Switching.

---

## 2. Architectural Mapping & Requirements Breakdown

### R1. Research & Gap Analysis
1. **R1.1 Sodium Architecture**: Analyzes Sodium's `RenderSectionManager`, `ChunkUpdateTask`, radial spiral section queues, arena allocations, and MDI terrain command creation.
2. **R1.2 VulkanMod Architecture**: Analyzes VulkanMod's `VkWorld`, `VkChunkRenderer`, Vulkan staging buffers, descriptor sets, and GPU-driven compute dispatch.
3. **R1.3 Lithium Architecture**: Analyzes Lithium's explosion exposure caching (`Explosion.getExposure`), entity tick suppression, block entity sleep, and fast chunk accessors.
4. **R1.4 ImmediatelyFast & Iris Hooks**: Analyzes ImmediatelyFast's entity quad batching and Iris's uniform shadow/GBuffer hooks.
5. **R1.5 Gap & FPS Impact Matrix**: Cross-references missing features in DestinyRenderer with reference implementations and expected FPS gains.

### R2. Chunk Meshing & Upload Pipeline
1. **R2.1 Radial Spiral Section Prioritization**: Sorts chunk build queue by distance from camera $(x - cameraX)^2 + (z - cameraZ)^2$.
2. **R2.2 Frame-Budget Upload Throttling**: Limits per-frame GPU buffer upload execution time to $\le 2.5\text{ ms}$ to eliminate frame drops.
3. **R2.3 Greedy Meshing & Quad Merging**: Merges coplanar, adjacent block faces with identical textures/light/AO into larger quads.
4. **R2.4 Section Dependency Graph**: Tracks block changes; only schedules re-mesh jobs for modified sections and their immediate boundary neighbors.
5. **R2.5 Zero-Allocation Thread-Local Meshers**: Uses pre-allocated thread-local primitive arrays (`long[]`, `int[]`) per meshing worker thread.

### R3. GPU Batching & Draw Call Reduction
1. **R3.1 Multi-Draw Indirect (MDI) Pipeline**: Uses `glMultiDrawElementsIndirect` with persistent off-heap command buffers rebuilt per frame.
2. **R3.2 CPU Frustum & Occlusion Culling**: Evaluates section bounding boxes against view frustum planes and Hi-Z depth pyramid before populating MDI command list.
3. **R3.3 128³ Spatial Hierarchy Culling**: Groups $8\times 8\times 8$ chunk sections into 128³ regions; early-rejects entire region if bounding box is outside frustum.
4. **R3.4 Distance Translucency Sorting**: Sorts translucent quads back-to-front by camera distance; triggers re-sort only when camera section changes or block breaks.
5. **R3.5 Yarn 1.21.11 `Explosion.getExposure` Mixin**: Fixes target method signature in `ExplosionOptimizationMixin` to point to valid Yarn 1.21.11 method (`getExposure` in `Explosion.class` / `ExplosionBehavior`).

### R4. Memory Layout & Buffer Allocations
1. **R4.1 Slab/TLSF Pool Allocator**: Off-heap Two-Level Segregated Fit / Slab allocator managing VBO/IBO ranges without Java GC or C `malloc`/`free` overhead.
2. **R4.2 Free-List Recycling**: Immediately returns freed section slices back to pool free-lists for instant reuse by new section updates.
3. **R4.3 Persistent Coherent Buffer Mapping**: Uses `GL_MAP_PERSISTENT_BIT | GL_MAP_COHERENT_BIT` for zero-copy CPU-to-GPU uploads on integrated GPUs.
4. **R4.4 Multi-Pool Separation**: Isolates geometry buffers into distinct pools (Opaque, Cutout, Translucent, Entity, Particle) to prevent state change pipeline stalls.
5. **R4.5 Arena Buffer Compaction & Off-Heap Management**: `RendererArenaManager` handles off-heap memory growth, alignment padding, and defragmentation compaction.

### R5. Shader Quality & Presets
1. **R5.1 Performance Preset**: Minimal `#version 330 core` shader, flat diffuse lighting, disabled AO, minimal linear fog, optimized for low-end Intel UHD 620/630.
2. **R5.2 Balanced Preset**: Standard `#version 330 core` shader, per-vertex ambient occlusion, directional light, exponential distance fog.
3. **R5.3 Quality Preset**: Advanced `#version 330 core` shader, per-fragment smooth AO, soft shadow map approximation, volumetric fog hint.
4. **R5.4 FXAA Post-Processing Pass**: Fast Approximate Anti-Aliasing full-screen quad pass to eliminate geometric aliasing without MSAA performance penalty.
5. **R5.5 Shader Uniform Management**: Dynamically updates matrices, light vectors, and fog density uniforms in `ShaderManager` without rebinding shader programs.

---

## 3. Tier 1 Test Case Catalog: Feature Coverage (125 Test Cases)

| Test ID | Sub-Feature | Test Case Title | Input / Precondition | Expected Outcome | Verification Method |
|---|---|---|---|---|---|
| TC-T1-R1.1-01 | R1.1 | Sodium RenderSectionManager Parity | Load superflat world, check section list | All rendered sections tracked in active render section list | Inspect log / debugger |
| TC-T1-R1.1-02 | R1.1 | ChunkUpdateTask Queue Ordering | Enqueue 20 chunk update tasks | Tasks sorted by radial distance before execution | Task queue priority inspection |
| TC-T1-R1.1-03 | R1.1 | Terrain Pipeline Render Pass Separation | Render frame with mixed geometry | Opaque, Cutout, and Translucent passes executed in order | GL call capture / debug trace |
| TC-T1-R1.1-04 | R1.1 | Sodium MDI Format Parity | Build command buffer | Command buffer uses 5-int layout (`count`, `instanceCount`, etc.) | Memory segment inspection |
| TC-T1-R1.1-05 | R1.1 | Section Visibility Graph Traversal | Camera enclosed in room | Occluded neighbor sections skipped in traversal | Visibility graph unit test |
| TC-T1-R1.2-01 | R1.2 | VulkanMod Staging Buffer Strategy Study | Benchmark staging allocation | Staging allocation latency documented in R1 report | Analysis report review |
| TC-T1-R1.2-02 | R1.2 | Descriptor Set Binding Abstraction | Check shader resource bindings | Textures and uniform buffers bound to uniform binding slots | GL state verification |
| TC-T1-R1.2-03 | R1.2 | GPU-Driven Indirect Command Pre-fill | Initialize MDI buffer | Buffer populated with default section draw commands | Direct memory byte read |
| TC-T1-R1.2-04 | R1.2 | VkWorld Chunk Grid Struct Parity | Map 16x16 chunk region | Coordinates mapped directly to flat array index | Morton / Grid index unit test |
| TC-T1-R1.2-05 | R1.2 | Staging Buffer Memory Barrier Simulation | Flush section uploads | GPU fence/barrier issued before rendering updated sections | GL sync object check |
| TC-T1-R1.3-01 | R1.3 | Lithium Exposure Cache Hit Ratio | Trigger TNT blast next to 10 entities | `getExposure` executed once per unique entity | Cache hit counter assertion |
| TC-T1-R1.3-02 | R1.3 | Block Entity Tick Suppression Check | Move 100 blocks away from chest | Block entity rendering skipped when out of range | Block entity render list count |
| TC-T1-R1.3-03 | R1.3 | Lithium Precomputed Ray Table Integrity | Classload `ExplosionOptimizationMixin` | Ray direction table initialized with 1,536 normalized vectors | Unit test vector norm == 1.0 |
| TC-T1-R1.3-04 | R1.3 | Hard AABB Pre-reject Distance Filter | TNT explosion at (0,64,0), block at (50,64,0) | Block at distance 50 skipped before raycast | Raycast invocation count == 0 |
| TC-T1-R1.3-05 | R1.3 | Thread-Local Exposure Map Reset | Complete explosion tick | `EXPOSURE_CACHE.get().isEmpty()` is true after tick | Thread-local state assertion |
| TC-T1-R1.4-01 | R1.4 | ImmediatelyFast Entity Quad Batching Study | Render 50 pigs | Entity quads merged into unified batch VBO | Batch draw call count == 1 |
| TC-T1-R1.4-02 | R1.4 | GUI Batching Buffer Retention | Render complex HUD screen | GUI elements rendered without intermediate GL state resets | Draw call count comparison |
| TC-T1-R1.4-03 | R1.4 | Iris GBuffer Pipeline Intercept Hook | Enable custom shader program | GBuffer uniforms correctly dispatched to active shader | Program uniform query |
| TC-T1-R1.4-04 | R1.4 | Shadow Map Render Pass Suppression | Disable shadow pass in config | Shadow pass skipped in render loop | Render pipeline pass count |
| TC-T1-R1.4-05 | R1.4 | Deferred Shader Texture Unit Assignment | Bind terrain textures | Albedo, normal, specular units assigned to GL units 0, 1, 2 | GL texture unit query |
| TC-T1-R1.5-01 | R1.5 | Gap Analysis Report Complete Categorization | Read `analysis.md` gap report | Contains categories: chunk, GPU, culling, memory, shaders, entities | Document completeness check |
| TC-T1-R1.5-02 | R1.5 | Gap FPS Impact Estimates Included | Review gap matrix | Every missing optimization has estimated FPS impact | Quantitative field check |
| TC-T1-R1.5-03 | R1.5 | Reference Mechanism Documentation | Review gap matrix | Sodium/VulkanMod source files cited for each feature | Reference link check |
| TC-T1-R1.5-04 | R1.5 | Priority Order Definition for Implementation | Check milestone planning | Missing optimizations ranked by ROI (FPS gain vs complexity) | Priority list check |
| TC-T1-R1.5-05 | R1.5 | Hardware Compatibility Profiling | Run gap analysis on Intel UHD 620 | Features flagged compatible or incompatible with GL 3.3 | Compatibility matrix check |
| TC-T1-R2.1-01 | R2.1 | Spiral Queue Initial Ordering | Set camera at (0,64,0), enqueue 10 sections | Sections enqueued in strictly increasing distance order | Distance assertion |
| TC-T1-R2.1-02 | R2.1 | Camera Movement Re-sorting | Teleport player to (100,64,100) | Build queue instantly re-sorted around new camera position | Queue head distance check |
| TC-T1-R2.1-03 | R2.1 | Radial Layer Rings Generation | Enqueue render distance 12 chunks | Chunks organized into concentric ring levels 0..12 | Ring level validation |
| TC-T1-R2.1-04 | R2.1 | Yaw/Pitch Priority Bias | Turn camera towards +X direction | Chunks in front frustum cone prioritized over rear chunks | Angle prioritization check |
| TC-T1-R2.1-05 | R2.1 | Inner Ring Completion Gate | Join world | Rings 0-2 meshed before outer ring scheduling | Initial frame load trace |
| TC-T1-R2.2-01 | R2.2 | Upload Budget Enforcement | Submit 50 section meshes | Upload processing stops when total time exceeds 2.5 ms | Microsecond timer check |
| TC-T1-R2.2-02 | R2.2 | Remaining Upload Retention | Enqueue 30 sections (10ms total upload) | 10 sections uploaded in frame 1, remaining deferred to frame 2+ | Deferred queue size check |
| TC-T1-R2.2-03 | R2.2 | High Frame Rate Dynamic Scaling | FPS = 120 (frame time 8.3ms) | Budget automatically throttled to retain 60+ FPS | Dynamic budget log check |
| TC-T1-R2.2-04 | R2.2 | Zero-Upload Idle Frame Overhead | World stationary, no updates | Upload queue processing takes 0.0 ms | Nanosecond profiler assertion |
| TC-T1-R2.2-05 | R2.2 | Emergency Budget Override on Teleport | Player teleports 500 blocks | Budget temporarily raised to 10ms for 3 frames | Teleport flag override test |
| TC-T2-R2.3-01 | R2.3 | Flat Wall Quad Merging | 16x16 plane of stone blocks | 256 individual quad faces merged into single 16x16 quad | Vertex count == 4 |
| TC-T1-R2.3-02 | R2.3 | Different Texture Merging Rejection | Stone next to Granite face | Faces not merged together | Vertex count == 8 |
| TC-T1-R2.3-03 | R2.3 | Light Level Merging Boundary | Block light 15 next to block light 14 | Faces not merged due to lighting mismatch | Quad separation check |
| TC-T1-R2.3-04 | R2.3 | AO Gradient Quad Splitting | Corner block with AO=0.5 next to AO=1.0 | Greedy mesher respects AO boundaries | AO array inspection |
| TC-T1-R2.3-05 | R2.3 | Index Buffer Reduction Verification | Merge 4 quads into 1 quad | Index buffer count drops from 24 indices to 6 indices | Index count check |
| TC-T1-R2.4-01 | R2.4 | Single Block Break Section Re-mesh | Break block at (5,5,5) | Only section containing (5,5,5) scheduled for re-mesh | Re-mesh count == 1 |
| TC-T1-R2.4-02 | R2.4 | Boundary Block Break Neighbor Notification | Break block at section boundary (15,5,5) | Target section AND adjacent neighbor section (+X) re-meshed | Re-mesh count == 2 |
| TC-T1-R2.4-03 | R2.4 | Internal Block Place Suppression | Place block inside solid 3x3 stone box | Outer neighbor sections NOT scheduled for re-mesh | Dependency graph edge test |
| TC-T1-R2.4-04 | R2.4 | Section Graph Invalidation | Explosions affect 4 sections | Dependency graph invalidates all 4 sections in single tick | Invalidation set size == 4 |
| TC-T1-R2.4-05 | R2.4 | Unloaded Section Graph Removal | Unload chunk (X,Z) | Section graph nodes for (X,Z) removed from memory | Node lookup returns null |
| TC-T1-R2.5-01 | R2.5 | Thread-Local Mesher Instance Isolation | Run 4 meshing threads concurrently | Threads write to independent thread-local output arrays | Zero race condition / corruption |
| TC-T1-R2.5-02 | R2.5 | Zero Allocation During Meshing Loop | Mesh 100 sections on worker thread | Java heap allocations == 0 bytes during meshing loop | GC allocation profiler check |
| TC-T1-R2.5-03 | R2.5 | Buffer Capacity Auto-Expansion | Mesh section with max complexity | Temp arrays grow if needed and remain allocated for reuse | Array size retention test |
| TC-T1-R2.5-04 | R2.5 | Thread Local Reset Efficiency | Complete meshing job | Worker thread resets pointer indices in < 1 microsecond | Microbench execution time |
| TC-T1-R2.5-05 | R2.5 | Packed Vertex Format Output Integrity | Emit vertex via `PackedVertexFormat.pack` | 64-bit long correctly packs X,Y,Z, Normal, UV, Light, AO, Tint | Bitwise unpack assertion |
| TC-T1-R3.1-01 | R3.1 | MDI Buffer Population | 100 visible sections | Command buffer contains 100 `DrawElementsIndirectCommand` structs | Command count == 100 |
| TC-T1-R3.1-02 | R3.1 | Single MultiDraw Call Dispatch | Render terrain frame | `glMultiDrawElementsIndirect` called exactly once per render pass | GL hook call count == 1 |
| TC-T1-R3.1-03 | R3.1 | Base Vertex Offset Accuracy | Draw section with VBO offset 4096 | `baseVertex` in MDI command equals `4096 / stride` | Command struct inspection |
| TC-T1-R3.1-04 | R3.1 | First Index Offset Calculation | Draw section with IBO offset 1024 | `firstIndex` in MDI command equals `1024 / sizeof(int)` | Command struct inspection |
| TC-T1-R3.1-05 | R3.1 | MDI Persistent Buffer Update | Modify section visibility | MDI command buffer updated in-place via direct memory write | Memory segment check |
| TC-T1-R3.2-01 | R3.2 | Frustum Cull Outside Section | Place camera facing East, test West section | West section marked invisible by `FrustumCuller` | Visibility boolean == false |
| TC-T1-R3.2-02 | R3.2 | Frustum Cull Inside Section | Test section directly in front of camera | Section marked visible by `FrustumCuller` | Visibility boolean == true |
| TC-T1-R3.2-03 | R3.2 | Occlusion Pyramids Depth Testing | Section completely occluded behind mountain | Hi-Z depth pyramid test rejects section | Occlusion test boolean == false |
| TC-T1-R3.3-04 | R3.2 | Camera Rotation Frustum Invalidation | Rotate camera 180 degrees | Previous visible sections culled, new sections added | Visible count updated |
| TC-T1-R3.2-05 | R3.2 | Sub-Frustum Precision Verification | Section intersecting frustum edge | Partially visible section retained (no false culling) | Edge section rendered |
| TC-T1-R3.3-01 | R3.3 | 128³ Region Boundary Hierarchy Creation | Load 512 sections | Grouped into single 128x128x128 spatial region node | Spatial hierarchy node count |
| TC-T1-R3.3-02 | R3.3 | Early 128³ Region Frustum Cull | Entire 128³ region behind camera | Single frustum test rejects all 512 child sections | Child test count == 0 |
| TC-T1-R3.3-03 | R3.3 | Partial Region Hierarchy Traversal | Region intersects frustum boundary | Hierarchy recurses to test child 16³ sections | Child test count > 0 |
| TC-T1-R3.3-04 | R3.3 | Region Occlusion Culling | 128³ region behind massive wall | Region bounding box fails Hi-Z depth test | Entire region culled |
| TC-T1-R3.3-05 | R3.3 | Dynamic Hierarchy Node Update | Break block in section | Region bounding box updated if section bounds changed | Hierarchy bounding box check |
| TC-T1-R3.4-01 | R3.4 | Back-to-Front Translucent Sorting Order | Glass blocks at distance 10, 20, 30 | Sorted indices emitted in order: dist 30, then 20, then 10 | Index order assertion |
| TC-T1-R3.4-02 | R3.4 | Camera Section Change Re-sort Trigger | Move camera within section | No translucent re-sort executed | Re-sort count == 0 |
| TC-T1-R3.4-03 | R3.4 | Camera Section Crossing Trigger | Camera moves from section (0,0) to (1,0) | Translucent sorter rebuilds index list | Re-sort execution trace |
| TC-T1-R3.4-04 | R3.4 | Translucent Block Break Invalidation | Break stained glass block | Translucent quad buffer updated for affected section | Quad count decremented |
| TC-T1-R3.4-05 | R3.4 | Sub-Section Translucent Quad Depth Sorting | 2 translucent quads inside same section | Quads sorted relative to camera position | Quad distance comparison |
| TC-T1-R3.5-01 | R3.5 | Target Method Signature Resolution | Load Yarn 1.21.11 mappings | `ExplosionOptimizationMixin` successfully injects without error | Mixin initialization log check |
| TC-T1-R3.5-02 | R3.5 | Exposure Cache HEAD Injection Execution | Trigger explosion near armor stand | HEAD injection intercepts `getExposure` and checks cache | Intercept log assertion |
| TC-T1-R3.5-03 | R3.5 | Exposure Cache RETURN Injection Execution | Calculate exposure for entity | RETURN injection stores result in `EXPOSURE_CACHE` | Map key presence test |
| TC-T1-R3.5-04 | R3.5 | Multi-Entity Explosion Accuracy | TNT damages 5 creepers | Damage values identical to vanilla explosion calculation | Health loss comparison |
| TC-T1-R3.5-05 | R3.5 | Mixin Compatibility With Vanilla ExplosionBehavior | Explosion with custom `ExplosionBehavior` | Exposure optimization respects custom block resistance | Resistance calculation test |
| TC-T1-R4.1-01 | R4.1 | TLSF Pool Allocation Range | Request 64KB slice from `TLSFAllocator` | Returns valid non-overlapping offset range $[O, O + 65536]$ | Address range assertion |
| TC-T1-R4.1-02 | R4.1 | Small Allocation Slab Management | Allocate 100 x 1KB section buffers | Allocations fit within slab pages without fragmentation | Fragmentation ratio < 5% |
| TC-T1-R4.1-03 | R4.1 | Zero Allocation Overhead On Allocation | Allocate slice | Executed via fast bitfield lookup in $O(1)$ time | Allocation nanosecond check |
| TC-T1-R4.1-04 | R4.1 | Off-Heap Segment Alignment | Request slice | Returned offset is 16-byte aligned | `offset % 16 == 0` assertion |
| TC-T1-R4.1-05 | R4.1 | Pool Exhaustion Exception | Request allocation exceeding total arena capacity | Throws `OutOfMemoryException` with diagnostic log | Exception catch verification |
| TC-T1-R4.2-01 | R4.2 | Immediate Slice Recycling | Allocate and free slice A | Slice A immediately available in free-list for next allocation | Allocation offset equality |
| TC-T1-R4.2-02 | R4.2 | Adjacent Free Block Coalescing | Free slice A, then free adjacent slice B | Slices A and B merged into single larger contiguous free block | Merged block size check |
| TC-T1-R4.2-03 | R4.2 | Free-List Leak Check | Allocate and free 1,000 sections | Available free memory returns to 100% initial capacity | Arena free bytes assertion |
| TC-T1-R4.2-04 | R4.2 | Out-of-Order Free Recycling | Free slices in random order | Free-list maintains valid doubly-linked list pointers | Free list integrity check |
| TC-T1-R4.2-05 | R4.2 | Double Free Safeguard | Call `freeSlice` twice on same handle | Throws `IllegalStateException` preventing heap corruption | Exception catch verification |
| TC-T1-R4.3-01 | R4.3 | Persistent Mapped Buffer Creation | Initialize `GpuBuffer` | Buffer created with `GL_MAP_PERSISTENT_BIT \| GL_MAP_COHERENT_BIT` | GL flag assertion |
| TC-T1-R4.3-02 | R4.3 | Zero-Copy Memory Segment Copy | Write vertex array to foreign `MemorySegment` | Data readable by GPU without `glBufferSubData` calls | GL call trace check |
| TC-T1-R4.3-03 | R4.3 | CPU Cache Flush Barrier Execution | Call `gpuBuffer.flush(offset, size)` | Flushes mapped memory region to GPU coherent domain | System call validation |
| TC-T1-R4.3-04 | R4.3 | iGPU Shared Memory Bus Verification | Render frame on Intel UHD Graphics | Zero pipeline stalls during buffer update | Frame time graph analysis |
| TC-T1-R4.3-05 | R4.3 | Unmap On Engine Shutdown | Shutdown renderer | Mapped off-heap memory cleanly unmapped without crash | JVM clean exit check |
| TC-T1-R4.4-01 | R4.4 | Separate Opaque Pool Isolation | Allocate opaque mesh | Slice allocated strictly from `opaquePool` | Pool handle assertion |
| TC-T1-R4.4-02 | R4.4 | Separate Translucent Pool Isolation | Allocate translucent mesh | Slice allocated strictly from `translucentPool` | Pool handle assertion |
| TC-T1-R4.4-03 | R4.4 | Separate Entity Pool Isolation | Allocate entity batch mesh | Slice allocated strictly from `entityPool` | Pool handle assertion |
| TC-T1-R4.4-04 | R4.4 | Separate Particle Pool Isolation | Allocate particle batch mesh | Slice allocated strictly from `particlePool` | Pool handle assertion |
| TC-T1-R4.4-05 | R4.4 | Cross-Pool Non-Interference | Fill opaque pool to 100% capacity | Translucent and Entity pools remain fully operational | Independent pool test |
| TC-T1-R4.5-01 | R4.5 | Off-Heap Arena Initialization | Launch game | `RendererArenaManager` allocates off-heap native memory segment | Off-heap size log check |
| TC-T1-R4.5-02 | R4.5 | Arena Defragmentation Compaction | Trigger defragmentation on fragmented pool | Active slices compacted to start of arena, free memory consolidated | Max contiguous block check |
| TC-T1-R4.5-03 | R4.5 | Arena Re-allocation Growth | Fill arena capacity | Arena grows by allocating secondary segment | Segment list length == 2 |
| TC-T1-R4.5-04 | R4.5 | Page Protection Safeguard | Write past slice offset limit | Memory segment bounds check throws `IndexOutOfBoundsException` | Foreign memory safeguard |
| TC-T1-R4.5-05 | R4.5 | Off-Heap Memory Leak Audit | Reload world 10 times | Native memory usage stays stable | Memory profiler check |
| TC-T1-R5.1-01 | R5.1 | Performance Shader Compilation | Load Performance preset | Shader compiles cleanly on `#version 330 core` | GL compile log check |
| TC-T1-R5.1-02 | R5.1 | Disabled AO Shader Flag | Load Performance preset | Ambient occlusion calculation skipped in vertex/fragment shader | AO uniform == 0.0 |
| TC-T1-R5.1-03 | R5.1 | Flat Diffuse Shading Output | Render stone cube in Performance preset | All faces rendered with basic flat diffuse lighting | Pixel color sample test |
| TC-T1-R5.1-04 | R5.1 | Minimal Fog Pipeline Output | Load Performance preset | Fog calculations restricted to simple linear end-plane | Fog formula verification |
| TC-T1-R5.1-05 | R5.1 | Intel UHD 620 Frame Time Target | Benchmark Performance preset on iGPU | Render time per frame $\le 8.3\text{ ms}$ (120 FPS target) | FPS profiler assertion |
| TC-T1-R5.2-01 | R5.2 | Balanced Shader Compilation | Load Balanced preset | Shader compiles cleanly on `#version 330 core` | GL compile log check |
| TC-T1-R5.2-02 | R5.2 | Per-Vertex AO Lighting Output | Render corner block in Balanced preset | Vertices display per-vertex ambient shading gradients | Vertex color check |
| TC-T1-R5.2-03 | R5.2 | Directional Light Vector Binding | Render world with sun at noon | Sun direction vector bound to shader uniform | Uniform value query |
| TC-T1-R5.2-04 | R5.2 | Exponential Distance Fog Render | Load Balanced preset | Distance fog calculated using $e^{-density \cdot d}$ formula | Pixel color depth decay |
| TC-T1-R5.2-05 | R5.2 | Balanced Preset Default Selector | Initial game launch | Engine defaults to Balanced preset level | `RendererConfig` preset state |
| TC-T1-R5.3-01 | R5.3 | Quality Shader Compilation | Load Quality preset | Shader compiles cleanly on `#version 330 core` | GL compile log check |
| TC-T1-R5.3-02 | R5.3 | Per-Fragment Smooth AO Output | Load Quality preset | AO calculated in fragment shader with smooth interpolation | Fragment shader inspection |
| TC-T1-R5.3-03 | R5.3 | Soft Shadow Approximation | Load Quality preset | Blocks cast soft contact shadow approximations | Shadow factor calculation |
| TC-T1-R5.3-04 | R5.3 | Volumetric Fog Hint Render | Load Quality preset | Fog density scales with height $Y$ producing volumetric effect | Fog density evaluation |
| TC-T1-R5.3-05 | R5.3 | Visual Quality Superiority Audit | Compare Quality preset to Vanilla | Quality preset exhibits visually smoother lighting/shadows | Image screenshot comparison |
| TC-T1-R5.4-01 | R5.4 | FXAA Shader Program Compilation | Enable FXAA pass | FXAA fragment shader compiles cleanly on `#version 330 core` | GL compile log check |
| TC-T1-R5.4-02 | R5.4 | FXAA Post-Processing Pass Dispatch | Render frame | Full-screen quad pass dispatched after translucent rendering | Render pass sequence check |
| TC-T1-R5.4-03 | R5.4 | High Contrast Edge Detection | Render staircase structure | FXAA detects specular contrast edges | Edge blend weight > 0 |
| TC-T1-R5.4-04 | R5.4 | Edge Softening Sub-Pixel Anti-Aliasing | Render thin fence wire | Aliasing jaggies smoothed across adjacent pixels | Pixel luminance variance check |
| TC-T1-R5.4-05 | R5.4 | FXAA iGPU Overhead Cap | Measure FXAA pass duration | FXAA execution takes $< 0.4\text{ ms}$ on Intel UHD | Frame profiler assertion |
| TC-T1-R5.5-01 | R5.5 | Preset Level Switching At Runtime | Switch preset from Balanced to Quality in GUI | Shaders re-bound instantly without rendering crash | Active program ID check |
| TC-T1-R5.5-02 | R5.5 | Matrix Uniform Buffer Binding | Update view projection matrix | Uniform block updated via single `glBufferSubData` | Uniform buffer content |
| TC-T1-R5.5-03 | R5.5 | Zero Pipeline Stall Preset Change | Rapidly cycle presets 10 times | GPU driver pipeline does not crash or leak resources | GL error code == GL_NO_ERROR |
| TC-T1-R5.5-04 | R5.5 | Light Color Uniform Dispatch | Change world time to midnight | Sun/Moon light color uniforms updated in active shader | Light color uniform query |
| TC-T1-R5.5-05 | R5.5 | Uniform Location Cache Integrity | Query `getUniformLocation` | Locations cached on shader bind to eliminate string lookups | Location map size check |

---

## 4. Tier 2 Test Case Catalog: Boundary & Corner Cases (125 Test Cases)

| Test ID | Boundary / Corner Focus | Failure Mode Prevented | Edge Condition / Input | Expected Behavior | Invalidation Trigger |
|---|---|---|---|---|---|
| TC-T2-R1.1-01 | Empty Chunk Render Section | Null Pointer Exception | Chunk section containing 100% air blocks | Mesher returns `false`, section excluded from draw list | Render empty section |
| TC-T2-R1.1-02 | Maximum Vertices Per Section | VBO Buffer Overflow | Section filled with 2,048 checkerboard glass/air blocks | Buffer automatically splits or caps quad count safely | VBO array index overflow |
| TC-T2-R1.1-03 | Rapid Camera Teleportation | Race condition in RenderSectionManager | Teleport camera 10,000 blocks across world border | Active section list flushed instantly without thread lockup | Section map corruption |
| TC-T2-R1.1-04 | Zero Render Distance Setting | Array Index Out Of Bounds | Set render distance slider to 0 (or minimum 2) | Pipeline handles minimum distance gracefully | Out of bounds exception |
| TC-T2-R1.1-05 | 512+ MDI Command Buffer Limit | MDI Command Overflow | Render distance 32 with 1,000+ visible sections | Command buffer resizes dynamically off-heap | Driver crash / truncation |
| TC-T2-R1.2-01 | Zero Length Staging Transfer | Driver Validation Layer Error | Submit zero-geometry section update | Staging transfer skipped, zero byte copy issued | Vulkan/GL error log |
| TC-T2-R1.2-02 | Concurrent Staging Allocation | Race condition in buffer mapping | 8 meshing threads submit staging buffers simultaneously | Synchronized allocation or thread-local staging pages | Buffer overlap / corruption |
| TC-T2-R1.2-03 | Unaligned Staging Memory Offset | Alignment Exception | Request staging slice of odd size (e.g. 1,003 bytes) | Memory offset automatically padded to 16-byte boundary | Driver alignment error |
| TC-T2-R1.2-04 | Maximum Descriptor Set Limits | Descriptor Pool Exhaustion | Bind 256 unique block texture atlases | Textures batched into single array sampler | Descriptor bind error |
| TC-T2-R1.2-05 | GPU Device Loss / Reset | Crash on Device Loss | Simulate GL context loss (`GL_CONTEXT_LOST`) | Engine safely shuts down or re-initializes resources | Hard crash / JVM freeze |
| TC-T2-R1.3-01 | Concurrent Explosion Raytracing | Thread Local Map Corruption | 5 TNT blocks explode simultaneously on 5 server threads | Each thread accesses independent `EXPOSURE_CACHE` instance | ConcurrentModException |
| TC-T2-R1.3-02 | Null Entity In Exposure Check | Null Pointer Exception | Call `getExposure(vec, null)` | Interceptor returns early without touching cache | NPE thrown in mixin |
| TC-T2-R1.3-03 | Extreme Explosion Radius | Integer Overflow / Loop Stall | Custom explosion with radius 250 blocks | AABB pre-reject prunes 99% blocks, raycount capped safely | Server tick freeze > 1s |
| TC-T2-R1.3-04 | Dead Entity UUID Access | Stale Map Accumulation | Entity dies during explosion calculation | Dead entity UUID cleaned from exposure map | Memory leak in threadlocal |
| TC-T2-R1.3-05 | Zero Ray Intersection Edge Case | NaN / Division By Zero | Ray intersects block corner exact coordinate $(0.0, 0.0, 0.0)$ | Normalized direction vector check handles zero magnitude | NaN floating point math |
| TC-T2-R1.4-01 | 10,000+ Particle Render Stress | VBO Heap Exhaustion | Summon 10,000 splash potion particles | Particles batched into fixed-size VBO slabs without crash | Out of memory error |
| TC-T2-R1.4-02 | Custom Entity Model ModelParts | Mixed Draw Pipeline Breakdown | Render entity with 50 custom geometry parts | Parts batched into unified vertex stream | Missing mob body parts |
| TC-T2-R1.4-03 | Iris Shader Switch Mid-Frame | Pipeline State Inconsistency | Reload Iris shader pack during active rendering | DestinyRenderer pipeline pauses and flushes cleanly | Visual artifact / glitch |
| TC-T2-R1.4-04 | Entity Off-Screen Culling Edge | Visual Pop-in | Dragon entity with giant AABB partially on screen | Entity bounding box check retains rendering | Mob disappearing on edge |
| TC-T2-R1.4-05 | High DPI Scale Scale Factor | Scissor Rect Overflow | GUI scale factor set to 4x on 4K monitor | GUI batching scissor rectangle scaled accurately | Off-screen GUI clipping |
| TC-T2-R1.5-01 | Unknown GPU Vendor String | Crash on Hardware Detection | GPU renderer string returns `"Custom Virtual GPU 9999"` | Hardware detector falls back to safe MDI preset | NullPointerException |
| TC-T2-R1.5-02 | Missing OpenGL 3.3 Extension | Driver Launch Failure | Run on GL 3.0 driver (legacy) | Displays friendly error dialog instead of JVM crash | Uncaught GL exception |
| TC-T2-R1.5-03 | Zero VRAM Detected | Arithmetic Exception | Hardware query returns 0 MB VRAM | Default conservative 512MB allocation cap applied | Division by zero |
| TC-T2-R1.5-04 | Driver Vendor Bug Overrides | Intel Driver Crash | Intel driver version known for MDI bug detected | Engine automatically enables driver bug workaround flag | Driver TDR freeze |
| TC-T2-R1.5-05 | Incomplete Gap Matrix File | Pipeline Build Failure | `analysis.md` missing required table column | Parser / checker flags missing requirement | Missing report fields |
| TC-T2-R2.1-01 | Camera Beyond World Height | Queue Index Out Of Bounds | Camera Y position = 500 (above world build limit Y=319) | Spiral queue calculates distance from Y=319 section gracefully | Negative array index |
| TC-T2-R2.1-02 | Camera Below Bedrock | Queue Index Out Of Bounds | Camera Y position = -100 (below Y=-64) | Spiral queue bounds Y index to valid section range [-4..19] | Out of bounds exception |
| TC-T2-R2.1-03 | Rapid 360-Degree Camera Spin | Queue Thrashing | Camera rotated at 1,000 deg/sec | Queue re-sorting rate-limited to once per frame | CPU spike during spin |
| TC-T2-R2.1-04 | 1,000 Sections Enqueued Simultaneously | Queue Lock Contention | World join enqueues 1,000 sections in single tick | Queue uses lock-free ring buffer or concurrent priority queue | Thread deadlock |
| TC-T2-R2.1-05 | Duplicate Section Enqueue | Queue Corruption | Enqueue section key $(10,2,5)$ 10 times in same frame | Duplicate entry rejected by set filter | Queue size blowup |
| TC-T2-R2.2-01 | Single Section Exceeding 2.5ms Budget | Frame Budget Lockout | Massive complex section takes 3.0ms to upload | Section completed, budget flag stops subsequent sections | Pipeline stall loop |
| TC-T2-R2.2-02 | Zero Time Elapsed Nano Timer | Division By Zero | `System.nanoTime()` returns identical value twice | Upload counter increments cleanly without arithmetic error | Division by zero |
| TC-T2-R2.2-03 | 0.1ms Micro Budget Config | Starvation Of Upload Queue | User config sets frame upload budget to 0.1ms | Upload processing uploads at least 1 section per frame | Total section freeze |
| TC-T2-R2.2-04 | 100ms Burst Teleport Upload | Spiking Frame Time | Teleport to ungenerated chunk area | Frame budget cap (2.5ms) remains enforced during teleport | 100ms stutter spike |
| TC-T2-R2.2-05 | Clock Drift / System Time Reset | Negative Upload Duration | System NTP clock sync alters nanoTime backwards | Upload loop handles negative delta gracefully | Infinite upload loop |
| TC-T2-R2.3-01 | Max Quad Merge Dimension (16x16) | Quad Over-Merge Artifacts | 32x32 continuous wall of stone blocks across 2 sections | Quad merging stops at section boundary (16x16 max) | Geometry spanning sections |
| TC-T2-R2.3-02 | Complex Custom Model Block (Stairs) | Missing Geometry Faces | Mesh Quartz Stairs with complex shapes | Non-cube faces skipped by greedy merger, emitted normally | Missing stair steps |
| TC-T2-R2.3-03 | Water/Glass Boundary Quad Merge | Alpha Blending Artifacts | Water block adjacent to Stained Glass | Merging respects block state identity; water and glass unmerged | Sorting visual artifacts |
| TC-T2-R2.3-04 | Smooth Lighting Checkerboard Pattern | Incorrect AO Merging | AO values alternating 0, 1, 0, 1 across wall | AO mismatch prevents merging adjacent faces | Lighting discoloration |
| TC-T2-R2.3-05 | Zero Visible Quad Section | Invalid Index Range | Section where all block faces are hidden by neighbors | Quad count == 0, mesher outputs empty buffer safely | Zero byte allocation bug |
| TC-T2-R2.4-01 | Top World Height Section Update (Y=304) | ConcurrentModificationException | Place block at Y=319 during chunk loading burst | Section graph catches exception, applies retry-with-backoff | Block invisible at Y=304+ |
| TC-T2-R2.4-02 | 8 Section Corner Block Break | Graph Update Dropped | Break block at exact corner $(15,15,15)$ of section | All 7 neighboring sections notified for graph update | Missing face updates |
| TC-T2-R2.4-03 | Rapid Block Place/Break Spam | Cyclic Graph Scheduling | Redstone clock toggles block 60 times/sec | Section updates debounced to max once per tick | CPU meshing overload |
| TC-T2-R2.4-04 | World Unload Graph Reset | Memory Leak | Exit world to main menu | Section graph cleared completely from heap | Stale chunk references |
| TC-T2-R2.4-05 | Biome Change Section Invalidation | Incorrect Foliage Color | Biome modified via command `/fillbiome` | Affected sections invalidated and re-meshed for color | Stale foliage tint |
| TC-T2-R2.5-01 | Thread Pool Worker Exception | Worker Thread Death | Meshing job throws unexpected exception | Worker thread catches error, resets state, remains alive | Worker thread count drops |
| TC-T2-R2.5-02 | 100,000 Vertices Section Overflow | Index Out Of Bounds | Section with extreme geometry exceeding `MAX_VERTS` | Mesh output safely truncated or split into sub-buffers | Array bounds crash |
| TC-T2-R2.5-03 | Zero Thread-Local Cache Flushed | Stale Geometry Leaked | Worker thread assigned new section after completing previous | Thread-local arrays reset counters to 0 before meshing | Previous section bleeding |
| TC-T2-R2.5-04 | Garbage Collector Kick Mid-Mesh | Thread Interrupt | GC pauses meshing thread mid-execution | Meshing job resumes cleanly after GC pause | Corrupted mesh stream |
| TC-T2-R2.5-05 | Out-of-Bounds Morton Lookup | Array Index Out Of Bounds | Query Morton encoder with coordinate (17,5,5) | Morton encoder throws bounds check or clamps coordinate | Memory corruption |
| TC-T2-R3.1-01 | 0 Visible Sections (Total Darkness) | GL Invalid Operation | Camera looking at empty void, 0 sections culled visible | `glMultiDrawElementsIndirect` skipped when draw count == 0 | GL error `GL_INVALID_VALUE` |
| TC-T2-R3.1-02 | MDI Command Buffer Re-allocation | Off-Heap Memory Leak | Visible sections increase from 100 to 2,000 | Old command buffer slice freed back to arena pool | Arena memory leak |
| TC-T2-R3.1-03 | 65,536 Index Overflow | Short Index Truncation | Section index count exceeds 65,535 indices | Index buffer uses `GL_UNSIGNED_INT` (32-bit indices) | Index wrap artifact |
| TC-T2-R3.1-04 | Unaligned Command Struct Offset | Driver MDI Fault | MDI command stride not aligned to 32 bytes | Stride padded to 32-byte GL requirement | Driver crash on draw |
| TC-T2-R3.1-05 | Concurrent MDI Buffer Write/Draw | GPU Memory Race | CPU writes MDI buffer while GPU executing draw call | Double-buffered MDI commands or GL sync fence applied | Screen tearing / flickering |
| TC-T2-R3.2-01 | Camera Inside Solid Block | Incorrect Culling | Camera inside solid obsidian block | Frustum culler handles inside-box state, renders surrounding | Black screen lockup |
| TC-T2-R3.2-02 | Infinite Projection Matrix FOV | Matrix Singularity | FOV set to extreme 179 degrees | Frustum plane equations handle near-180 degree angles | NaN matrix frustum planes |
| TC-T2-R3.2-03 | Hi-Z Pyramid Resolution Mismatch | Depth Texture Artifacts | Window resized from 1080p to 4K mid-frame | Hi-Z depth pyramid re-initialized to match framebuffer dimensions | Occlusion false positives |
| TC-T2-R3.2-04 | Z-Far Plane Distance Clip | Chunk Disappearance | Render distance 32 (Z-far = 512m) | Frustum culling Z-far plane matches render distance exactly | Chunks culled prematurely |
| TC-T2-R3.2-05 | Zero Area Section Bounding Box | Culling Bypass | Section with 1 single block at origin | Bounding box volume $> 0$, tested correctly by frustum | Single block culled |
| TC-T2-R3.3-01 | Single Section Region Bounding Box | Degenerate Region Node | 128³ region containing only 1 section in bottom corner | Bounding box tight-fitted to active child sections | Empty region space tested |
| TC-T2-R3.3-02 | Region Spanning World Min/Max | Coordinate Wrapping | 128³ region spanning Y=-64 to Y=64 boundary | Y coordinates calculated correctly across negative space | Negative coordinate bug |
| TC-T2-R3.3-03 | 64 Region Frustum Traversal | Stack Overflow | Traverse 64 nested region nodes | Non-recursive or shallow tree depth (max 3 levels) | StackOverflowError |
| TC-T2-R3.3-04 | Dynamic Region Node Destruction | Dangling Node Reference | Unload 512 chunks in region | Spatial hierarchy parent node deleted cleanly | Null pointer in culling |
| TC-T2-R3.3-05 | Region Bounding Box Alignment | Floating Point Precision | Region bounding box tested with 32-bit float matrix | Epsilon margin added to box to prevent precision false culling | Edge chunk flickering |
| TC-T2-R3.4-01 | 10,000 Translucent Quads Sort | Sorting Latency Spike | Render 10,000 glass quads | `TranslucencySorter` completes radix/quick sort in $< 1.0\text{ ms}$ | Frame stutter during sort |
| TC-T2-R3.4-02 | Equal Distance Quad Tie | Index Flapping | 2 translucent quads at exact same distance from camera | Stable sort preserves deterministic order | Translucent z-fighting |
| TC-T2-R3.4-03 | Co-Planar Glass-Water Overlap | Blending Failure | Water block level with stained glass block | Sorter orders water before glass based on normal vector | Incorrect color blend |
| TC-T2-R3.4-04 | Camera Stationary Sorting Bypass | Unnecessary CPU Work | Camera does not move or rotate | Translucent index buffer untouched | Per-frame sorter overhead |
| TC-T2-R3.4-05 | Translucent IBO Slice Exhaustion | Memory Buffer Overflow | Section filled with 4,096 water quads | Translucent IBO slice automatically expanded from pool | IBO allocation crash |
| TC-T2-R3.5-01 | Obfuscated Minecraft Run | Mixin Mapping Failure | Run mod in obfuscated production environment | Yarn 1.21.11 refmap maps target to obfuscated intermediary | ClassNotFoundException |
| TC-T2-R3.5-02 | Synthetic Entity Exposure Query | NPE in Exposure Fix | Entity without valid world reference queried for exposure | Interceptor guards against null world/UUID | Exception in getExposure |
| TC-T2-R3.5-03 | Explosion Damage Outside World | Coordinate Out of Bounds | TNT launched to Y=500 | Exposure calculation handles out-of-world positions safely | Chunk loading crash |
| TC-T2-R3.5-04 | Concurrent Mod Mixin Target Collision | Mixin Application Error | Another mod mixins into `Explosion.getExposure` | Mixin uses priority 1000 and `@Inject` to coexist | MixinApplyError |
| TC-T2-R3.5-05 | 1,000 Concurrent TNT Entities | Thread Local Memory Growth | 1,000 TNT entities explode in single tick | `EXPOSURE_CACHE` size cleared immediately after tick | ThreadLocal memory leak |
| TC-T2-R4.1-01 | 100MB Arena Allocation Stress | Native Heap Fragmentation | Allocate 5,000 random slices (1KB to 2MB) | TLSF allocator maintains $> 90\%$ allocation efficiency | Arena fragmentation failure |
| TC-T2-R4.1-02 | Zero Size Allocation Request | Invalid Argument Exception | Call `allocateSlice(pass, 0)` | Returns empty slice handle without modifying pool | Native memory fault |
| TC-T2-R4.1-03 | 16MB Single Mesh Allocation | Large Block Allocation | Request 16MB contiguous slice for giant mesh | Allocator assigns dedicated large block page | Allocation return null |
| TC-T2-R4.1-04 | 1,000 Rapid Alloc/Free Cycles | Free-List Pointer Corruption | Rapidly allocate and free slices on 4 threads | Pointer integrity checks pass without corruption | Segfault in native memory |
| TC-T2-R4.1-05 | Pool Off-Heap Address Alignment | Bus Error / Unaligned Access | Query memory segment raw address | Native address aligned to 64-byte SIMD boundary | Unaligned SIMD crash |
| TC-T2-R4.2-01 | Free Invalid Pointer Handle | Pool State Corruption | Pass arbitrary integer handle to `freeSlice` | Free list validator rejects invalid handle with error log | Native crash / corruption |
| TC-T2-R4.2-02 | Coalesce Block Left and Right | Linked List Disconnection | Free middle block between two free blocks | All 3 blocks merged into 1 continuous free block | Free block fragment leak |
| TC-T2-R4.2-03 | Pool Shutdown With Active Slices | Resource Leak | Shutdown engine while 500 slices allocated | Arena manager unmaps total native segment cleanly | Memory leak on exit |
| TC-T2-R4.2-04 | Free-List Search Best Fit | Suboptimal Memory Use | Request 4KB slice when 4KB and 64KB free blocks exist | Allocator selects 4KB free block (best-fit strategy) | Excessive block splitting |
| TC-T2-R4.2-05 | Max Segregated Bitmap Range | Bitmask Truncation | Request slice size near maximum 2GB limit | Second-level bitmap index calculated accurately | Bitmap shift overflow |
| TC-T2-R4.3-01 | GPU Read During CPU Coherent Copy | GPU Pipeline Freeze | CPU writes to coherent mapped segment while GPU rendering | Coherent memory spec guarantees no driver crash | Graphics driver crash |
| TC-T2-R4.3-02 | Non-Coherent Hardware Fallback | Suboptimal Performance | Driver reports lack of `GL_MAP_COHERENT_BIT` | Engine falls back to `glBufferSubData` staging pool | Engine failure on legacy GL |
| TC-T2-R4.3-03 | Flush Outside Segment Bounds | Buffer Range Exception | Call `gpuBuffer.flush(100, 5000)` on 1000-byte buffer | Bounds check throws `IllegalArgumentException` | Driver segfault |
| TC-T2-R4.3-04 | Mapped Buffer Resize | Memory Segment Invalidation | Coherent buffer filled to capacity, needs expansion | Old segment unmapped, new larger segment mapped, pointers updated | Invalid pointer crash |
| TC-T2-R4.3-05 | Coherent Write Alignment Cap | Cache Line Mismatch | Write 1 single byte to mapped segment | Flush range rounded up to GPU cache-line size (64 bytes) | Partial cache line write loss |
| TC-T2-R4.4-01 | Opaque Pool Exhaustion Fallback | Pipeline Lockout | Opaque pool reaches 100% capacity | Opaque pool auto-expands segment without taking from Translucent | Translucent rendering stall |
| TC-T2-R4.4-02 | Cutout Geometry Migration | Cross-Pool State Bleed | Foliage cutout blocks rendered | Cutout quads isolated in Cutout pool with alpha-testing shader | Alpha transparency glitch |
| TC-T2-R4.4-03 | Particle Pool Sizing Under Heavy Combat | Buffer Overflow | 50 Mob Spawners generating maximum particles | Particle pool dynamically allocates secondary slab | Particle render drop |
| TC-T2-R4.4-04 | Entity Pool Defragmentation | Mob Instancing Lockout | 200 mobs despawn simultaneously | Entity VBO pool defragmented without interrupting active mob draw | Mob rendering flicker |
| TC-T2-R4.4-05 | Invalid Pass Pool Lookup | Null Pointer Exception | Query pool for `PassType.UNKNOWN` | Throws `IllegalArgumentException` with clear diagnostic | NPE in renderer |
| TC-T2-R4.5-01 | Off-Heap Segment Allocation Failure | Fatal Initialization Error | System OS out of native memory | Displays graceful error dialog explaining insufficient RAM | Silent JVM crash |
| TC-T2-R4.5-02 | Arena Compaction Under Active Draw | GPU Memory Corruption | Defragmentation triggered while GPU rendering frame | Compaction deferred until frame boundary (post-swap) | Rendering garbage geometry |
| TC-T2-R4.5-03 | Foreign Memory Unsafe Spill | Security Exception | Java 21 Foreign Function API memory segment access | Memory segments scoped to engine lifecycle | JVM security crash |
| TC-T2-R4.5-04 | Max Arena Size Exceeded (4GB) | 32-Bit Pointer Overflow | Arena memory exceeds 4GB on 64-bit JVM | Offsets stored as 64-bit `long` primitive values | Integer overflow |
| TC-T2-R4.5-05 | Multi-Threaded Compaction Request | Race Condition | 2 threads request arena compaction simultaneously | Compaction synchronized via atomic state CAS | Compaction loop deadlock |
| TC-T2-R5.1-01 | Performance Shader Compile Error | Shader Fallback Trigger | Intel GPU driver fails shader compilation | Shader manager catches log, falls back to barebones GLSL | Game crash on launch |
| TC-T2-R5.1-02 | Negative Light Map Coordinates | Visual Artifacts | Lightmap coordinates received as negative numbers | Clamped to $[0.0, 1.0]$ range in vertex shader | Pitch black terrain |
| TC-T2-R5.1-03 | Performance Preset Zero Fog Range | Division By Zero | Fog start = 10, Fog end = 10 (zero range) | Shader guards division `1.0 / max(0.001, end - start)` | Rendering NaN white screen |
| TC-T2-R5.1-04 | Missing Texture Sampler Uniform | GL Shader Warning | Texture sampler uniform `u_Sampler` unassigned | Defaults to texture unit 0 binding | White untextured blocks |
| TC-T2-R5.1-05 | Performance Preset Shading Consistency | Color Bleed | Render Nether dimension lava in Performance preset | Brightness levels rendered correctly without AO darkening | Nether terrain pitch dark |
| TC-T2-R5.2-01 | Vertex AO Precision Loss | Quantization Artifacts | AO value packed into 2-bit vertex integer | Vertex shader unpacks float accurately in $[0.0, 1.0]$ | Block lighting banding |
| TC-T2-R5.2-02 | Zero Directional Light Vector | Black World Output | Sun direction vector passed as $(0,0,0)$ | Shader clamps light vector length to minimum $(0, 1, 0)$ | Completely black screen |
| TC-T2-R5.2-03 | Max Fog Density Overcast | Black Screen Output | Fog density set to 1.0 (blindness effect) | Exponential fog calculation renders smooth solid fog color | Visual graphics glitch |
| TC-T2-R5.2-04 | Balanced Preset Shader Re-compilation | GL Context Leak | Toggle Balanced preset 50 times | Existing GL shader handles deleted before compilation | Shader handle leak |
| TC-T2-R5.2-05 | Multi-Texture Unit Binding Collision | Texture Bleed | Atlas texture bound to unit 0, lightmap to unit 1 | Sampler uniforms bound explicitly to unit 0 and unit 1 | Lightmap rendered as terrain |
| TC-T2-R5.3-01 | Fragment AO Derivative Failure | Fragment Shader Compile Error | Smooth AO fragment shader uses `dFdx`/`dFdy` functions | `#extension GL_OES_standard_derivatives` or standard 330 core used | Shader compile failure |
| TC-T2-R5.3-02 | Quality Soft Shadow Precision | Shadow Acne / Striping | Soft shadow ray step size set too small | Bias factor added to shadow depth comparison | Shadow acne artifacts |
| TC-T2-R5.3-03 | Volumetric Fog Height Underflow | Fog Calculation Flip | Player at Y=-64 (minimum height) | Volumetric height fog clamped to minimum world Y boundary | Inverted fog rendering |
| TC-T2-R5.3-04 | Intel UHD Quality Shader Overload | FPS Drop < 30 FPS | Run Quality preset on Intel UHD 620 at 4K | Config displays performance warning banner | Uninformed low FPS |
| TC-T2-R5.3-05 | Smooth AO Block Edge Discontinuity | Lighting Seams | Smooth AO calculated across section border blocks | Border block AO values fetched from neighbor section data | Dark lines between sections |
| TC-T2-R5.4-01 | FXAA Framebuffer Size (1x1 Pixel) | Division By Zero | Window minimized to 1x1 pixels | FXAA inverse screen size uniform clamped to safe minimum | Division by zero in FXAA |
| TC-T2-R5.4-02 | FXAA Luminance Threshold Edge | Over-Blurring | Low contrast scene with dark textures | FXAA threshold avoids blurring low-contrast texture details | Blurry text and textures |
| TC-T2-R5.4-03 | FXAA Off-Screen Quad Vertex Bounds | Off-Screen Rendering Failure | Render FXAA full-screen triangle quad | Vertex coordinates $(-1,-1)$ to $(3,3)$ cover viewport exactly | Half-screen anti-aliasing |
| TC-T2-R5.4-04 | FXAA Depth Buffer Collision | Post-Process Overwrite | FXAA pass dispatches depth write | FXAA pass disables depth writing (`glDepthMask(false)`) | Depth buffer overwritten |
| TC-T2-R5.4-05 | FXAA Toggle Mid-Frame | Framebuffer Texture Lock | Toggle FXAA on/off while in world | Viewport framebuffer target switched cleanly | Screen freeze / black display |
| TC-T2-R5.5-01 | Uniform Location -1 Assignment | Silent Uniform Drop | Uniform `u_NonExistent` queried | Shader manager skips `glUniform` call if location is -1 | GL error `GL_INVALID_OPERATION` |
| TC-T2-R5.5-02 | Invalid Matrix Uniform Dimension | GL State Error | Pass 3x3 matrix to 4x4 matrix uniform call | Uniform dispatcher checks matrix dimensions | Driver crash |
| TC-T2-R5.5-03 | Concurrent Uniform Dispatch | State Race Condition | 2 threads call `ShaderManager.setUniform` | Uniform dispatch restricted strictly to GL render thread | Multithreaded GL error |
| TC-T2-R5.5-04 | Shader Program ID Zero Swap | Rendering Black Frame | Bind shader program ID 0 | Shader manager validates program handle $> 0$ before bind | Black frame rendered |
| TC-T2-R5.5-05 | 100 Uniform Updates Per Frame | CPU Overhead Spike | Update 100 individual uniforms per frame | Uniforms grouped into Uniform Buffer Object (UBO) | CPU bound render loop |

---

## 5. Tier 3 Test Case Catalog: Pairwise Combinations (36 Test Cases)

Tier 3 test cases evaluate complex cross-system interactions between core Requirements (R1–R5) and downstream features (R6–R9).

| Test ID | Primary Feature (R1–R5) | Secondary Feature (R6–R9) | Interaction Focus / Objective | Expected Pairwise Outcome |
|---|---|---|---|---|
| TC-T3-PAIR-01 | R2.1 (Spiral Queue) | R6.5 (PvP Frame-Start Upload) | Camera movement during rapid PvP motion | Frame-start upload processes inner spiral ring first; zero stale render frames |
| TC-T3-PAIR-02 | R2.2 (Frame Budget ≤2.5ms) | R6.3 (Particle VBO Pools) | Burst particle spawning under heavy chunk uploads | Particle VBO uploads share frame upload budget without frame stutter |
| TC-T3-PAIR-03 | R2.3 (Greedy Meshing) | R6.1 (Mob Instancing) | High-density mob herd standing against wall | Wall greedy quads and mob instance buffers rendered in distinct passes |
| TC-T3-PAIR-04 | R2.4 (Dependency Graph) | R6.4 (Entity NBT Cache) | Explosion destroying blocks and damaging mobs | Section dependency graph triggers re-mesh while mob NBT cache reuses cached render state |
| TC-T3-PAIR-05 | R2.5 (Zero-Alloc Mesher) | R7.2 (Async Palette Snapshot) | World load meshing burst on background threads | Zero-allocation meshers process async palette snapshots without locking main thread |
| TC-T3-PAIR-06 | R3.1 (MDI Pipeline) | R6.1 (Mob Instancing) | Terrain MDI and Mob Instancing combined render pass | Terrain MDI issued via `glMultiDrawElementsIndirect`, mobs rendered via instanced batch |
| TC-T3-PAIR-07 | R3.2 (Frustum/Occlusion) | R6.2 (Entity AO LOD) | Entity occlusion culling beyond LOD distance | Mobs culled by frustum/occlusion skip AO calculation entirely |
| TC-T3-PAIR-08 | R3.3 (128³ Hierarchy) | R7.1 (Inner-Ring Spiral Join) | World join initial section visibility hierarchy | 128³ spatial hierarchy built progressively as inner spiral rings populate |
| TC-T3-PAIR-09 | R3.4 (Translucency Sort) | R6.3 (Particle Toggles) | Translucent water blocks with active splash particles | Translucent terrain and particle pools sorted and rendered in correct depth order |
| TC-T3-PAIR-10 | R3.5 (Explosion Mixin) | R6.4 (Entity NBT Cache) | TNT chain explosion damaging 50 mobs | Explosion exposure cache eliminates raycasts while mob NBT cache eliminates tick reads |
| TC-T3-PAIR-11 | R4.1 (Slab/TLSF Allocator) | R6.3 (Particle VBO Pools) | Shared off-heap arena allocation for terrain and particles | Particle VBO pools allocate slices from dedicated slab pool within `RendererArenaManager` |
| TC-T3-PAIR-12 | R4.2 (Free-List Recycling) | R7.3 (Top-Height Retry Backoff) | Rapid load/unload of Y=304 sections under retry loop | Memory slices returned immediately to free-list during population retry attempts |
| TC-T3-PAIR-13 | R4.3 (Coherent Zero-Copy) | R6.5 (PvP Frame-Start Upload) | Zero-latency PvP input mapping on Intel UHD | PvP frame-start upload writes directly to mapped coherent VBO without driver stall |
| TC-T3-PAIR-14 | R4.4 (Multi-Pool Separation) | R6.1 (Mob Instancing) | Independent buffer pools for terrain and mob instancing | Mob instance buffer allocations do not fragment or displace terrain opaque pool |
| TC-T3-PAIR-15 | R4.5 (Arena Compaction) | R7.2 (Async Palette Snapshot) | World join arena compaction during background palette snapshot | Off-heap arena compaction executed safely without corrupting background snapshot data |
| TC-T3-PAIR-16 | R5.1 (Performance Preset) | R8.1 (Config Screen Selector) | Selecting Performance preset in Config GUI | GUI instantly switches shaders to `#version 330 core` flat diffuse mode |
| TC-T3-PAIR-17 | R5.2 (Balanced Preset) | R8.2 (GUI Scale 1x-4x) | Balanced preset shader active with GUI Scale set to 4x | Config screen rendered cleanly at 4x scale without shader uniform corruption |
| TC-T3-PAIR-18 | R5.3 (Quality Preset) | R6.2 (Entity AO LOD) | Quality smooth AO active with mob distance AO LOD | Mobs beyond LOD distance skip per-vertex AO; smooth AO applied to nearby terrain |
| TC-T3-PAIR-19 | R5.4 (FXAA Pass) | R8.4 (7 Particle Toggles) | FXAA enabled with all 7 particle toggles active | Full-screen FXAA post-process pass applies anti-aliasing to rendered particle geometry |
| TC-T3-PAIR-20 | R5.5 (Uniform Management) | R8.3 (Drag-Only Sliders) | Adjusting fog density slider in Config GUI | Dragging slider updates shader fog uniform in real-time without pipeline re-compilation |
| TC-T3-PAIR-21 | R1.5 (Gap Analysis Matrix) | R9.1 (Gradle Build Zero Errors) | Gap analysis implementation plan code verification | All missing optimization implementations compile cleanly under `./gradlew build` |
| TC-T3-PAIR-22 | R2.2 (Frame Upload Budget) | R9.2 (60s Superflat Benchmark) | Running automated 60s benchmark | Benchmark log confirms chunk upload time per frame stays $\le 2.5\text{ ms}$ at steady state |
| TC-T3-PAIR-23 | R3.1 (MDI Pipeline) | R9.2 (60s Superflat Benchmark) | Automated benchmark average FPS evaluation | Benchmark demonstrates average FPS $\ge 20\%$ higher than vanilla Sodium baseline |
| TC-T3-PAIR-24 | R5.3 (Quality Preset) | R9.3 (benchmark_results.txt) | Recording benchmark results under Quality preset | Benchmark output writes valid FPS and frame time metrics to `benchmark_results.txt` |
| TC-T3-PAIR-25 | R3.5 (Explosion Mixin) | R9.1 (Gradle Build & Log Audit) | Gradle build and mixin log verification | Mixin applies without injection errors in `latest.log`; `Explosion` target method verified |
| TC-T3-PAIR-26 | R2.1 (Spiral Queue) | R7.1 (Inner-Ring World Join) | Teleport to new world spawn location | Innermost ring (0-2) uploaded within 1 second of join via spiral prioritization |
| TC-T3-PAIR-27 | R4.1 (TLSF Allocator) | R6.4 (Entity NBT Cache) | High entity density world join memory management | NBT render caching reduces allocation pressure on TLSF off-heap pool |
| TC-T3-PAIR-28 | R5.1 (Performance Preset) | R9.2 (60s Superflat Benchmark) | Max FPS benchmark run under Performance preset | Benchmarks maximum possible FPS on Intel UHD 620 with flat shading |
| TC-T3-PAIR-29 | R2.3 (Greedy Meshing) | R8.3 (Drag-Only Sliders) | Toggling greedy meshing setting in Config GUI | Dynamic toggle rebuilds section meshes using greedy quad merging |
| TC-T3-PAIR-30 | R3.4 (Translucency Sort) | R6.5 (PvP Frame-Start Upload) | Translucent block updates during high-speed PvP fight | Translucent quad sort and buffer upload processed at frame-start for immediate rendering |
| TC-T3-PAIR-31 | R4.3 (Coherent Zero-Copy) | R9.2 (60s Superflat Benchmark) | iGPU zero-copy performance evaluation during benchmark | 0ms `glBufferSubData` stalls recorded in benchmark execution profiler |
| TC-T3-PAIR-32 | R5.4 (FXAA Pass) | R9.3 (benchmark_results.txt) | Recording FXAA frame time impact in benchmark results | FXAA pass execution time ($<0.4\text{ ms}$) documented in `benchmark_results.txt` |
| TC-T3-PAIR-33 | R2.4 (Dependency Graph) | R7.3 (Top-Height Retry Backoff) | Block breaking at Y=319 during world population retry | Dependency graph updates boundary sections while retry backoff completes |
| TC-T3-PAIR-34 | R3.3 (128³ Hierarchy) | R6.1 (Mob Instancing) | Mob herd inside 128³ region culled by frustum | Culling 128³ region skips draw calls for all enclosed terrain AND mob instances |
| TC-T3-PAIR-35 | R4.4 (Multi-Pool Separation) | R8.4 (7 Particle Toggles) | Toggling individual particle types in Config GUI | Particle VBO pool releases memory slices when particle types disabled |
| TC-T3-PAIR-36 | R5.5 (Uniform Management) | R7.2 (Async Palette Snapshot) | World join shader initialization during palette snapshot | Shader uniforms bound on GL thread while block state palette snap completes async |

---

## 6. Invalidation Conditions & Verification Strategy

### Independent Verification Method
1. **Gradle Compilation & Mixin Audit**:
   - Execute `./gradlew build --no-daemon` to confirm zero compilation errors.
   - Inspect `run/logs/latest.log` to verify zero Mixin injection failures or refmap errors for `ExplosionOptimizationMixin`.
2. **Automated E2E Benchmark**:
   - Run system with `-Ddestiny.benchmark=true` for automated 60-second superflat world testing.
   - Verify generated `benchmark_results.txt` confirms average FPS $\ge 20\%$ over vanilla baseline and chunk upload time per frame $\le 2.5\text{ ms}$.
3. **OpenGL Memory & State Validation**:
   - Enable KHR_debug GL callback mode to verify zero `GL_INVALID_ENUM`, `GL_INVALID_VALUE`, or unaligned memory access errors.
4. **Memory Allocation Audit**:
   - Monitor JVM off-heap memory usage via Java Flight Recorder (JFR) to confirm `TLSFAllocator` and `RendererArenaManager` prevent native heap leaks.

---
