# Project: DestinyRenderer

## Architecture
DestinyRenderer is a high-performance GPU-driven Minecraft 1.21.11 (Fabric / Yarn mappings) rendering mod optimized for integrated graphics (specifically Intel UHD Graphics).
Key components:
1. **Chunk Meshing & Upload Engine**: Thread-local zero-allocation meshers, greedy quad merging, spiral priority queue, frame budget throttle (≤ 2.5ms upload), neighbor dependency graph.
2. **GPU Batching & Indirect Draw Pipeline**: Multi-Draw Indirect (MDI), 128^3 spatial hierarchy culling, persistent command buffer, CPU frustum/occlusion culling, distance translucency sorting.
3. **Memory Allocator & Pool System**: Slab/pool allocation for chunk VBOs/IBOs, free-list recycling, persistent coherent buffer mapping (zero-copy), separate VBO pools for Opaque, Cutout, Translucent, Entity, Particle.
4. **Shader Pipeline & Presets**: Intel UHD compatible GLSL shaders (`#version 330 core`), 3 presets (Performance, Balanced, Quality), smooth AO, soft shadows, volumetric fog hints, FXAA pass.
5. **Entity, Particle & PvP Renderer**: Instanced mob draw batching, entity distance AO LOD, particle VBO pools, NBT caching, frame-start chunk upload processing for zero-latency PvP.
6. **Startup & World Join**: Inner-ring spiral priority loading, async palette snapshot, retry-with-backoff for top-height chunk section population.
7. **Config Screen**: Option GUI compatible with GUI scales 1x-4x, visual preset selector, drag-only sliders, 7 particle toggles.

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| M1 | Research & Gap Analysis Report | Read Sodium, VulkanMod, Lithium, ImmediatelyFast, Iris, DR code; produce gap report | none | IN_PROGRESS |
| M2 | Chunk Meshing & Upload Pipeline | Spiral queue, upload budget throttling, greedy meshing, section graph, zero-alloc meshers | M1 | PLANNED |
| M3 | GPU Batching & Draw Call Reduction | MDI pipeline, frustum/occlusion culling, 128^3 hierarchy, translucency sort, fix ExplosionOptimizationMixin | M1, M2 | PLANNED |
| M4 | Memory Layout & Buffer Allocations | Slab/pool allocator, free-list recycling, coherent buffers, separate pools | M2 | PLANNED |
| M5 | Shader Quality & Visual Presets | Performance/Balanced/Quality shaders (#version 330 core), FXAA, smooth AO, fog | M3 | PLANNED |
| M6 | Entity, Particle & PvP Rendering | Mob instancing, entity AO LOD, particle VBO pools, NBT caching, frame-start upload processing | M3, M4 | PLANNED |
| M7 | Startup & World Join Speed | Spiral initial load, async palette snapshot, ConcurrentModificationException retry | M2 | PLANNED |
| M8 | Config Screen Polish | Presets GUI, setting controls, 1x-4x GUI scale, drag-only sliders, particle toggles | M5 | PLANNED |
| M9 | Final E2E Test Pass & Benchmarks | E2E test verification, 60s superflat benchmark, benchmark_results.txt, Victory Audit | M1-M8 | PLANNED |

## Interface Contracts
### RenderEngine ↔ ChunkManager
- `enqueueSectionUpdate(BlockPos pos)`: Enqueues section and flags neighbors for graph update.
- `processUploadQueue(long maxTimeNanos)`: Uploads completed chunk meshes respecting maximum frame time budget (default 2.5ms).

### MemoryPool ↔ MeshUploader
- `allocateSlice(PassType pass, int byteSize)`: Returns a `BufferSlice` from the slab allocator.
- `freeSlice(BufferSlice slice)`: Recycler returns slice to pool free-list immediately.

### ShaderManager ↔ RenderPipeline
- `applyPreset(PresetLevel preset)`: Rebinds shaders and uniform configurations for Performance / Balanced / Quality modes.

## Code Layout
```
src/main/java/destinyrenderer/
├── DestinyRenderer.java
├── client/
│   ├── DestinyRendererClient.java
│   ├── render/
│   │   ├── chunk/
│   │   │   ├── ChunkMesher.java
│   │   │   ├── ChunkUploadQueue.java
│   │   │   ├── SectionGraph.java
│   │   │   └── GreedyMesher.java
│   │   ├── batch/
│   │   │   ├── MultiDrawIndirectPipeline.java
│   │   │   ├── SpatialHierarchy.java
│   │   │   └── FrustumCuller.java
│   │   ├── memory/
│   │   │   ├── SlabAllocator.java
│   │   │   └── CoherentBuffer.java
│   │   ├── shader/
│   │   │   ├── ShaderPreset.java
│   │   │   └── ShaderManager.java
│   │   ├── entity/
│   │   │   ├── EntityBatchRenderer.java
│   │   │   └── ParticlePoolManager.java
│   │   └── gui/
│   │       └── DestinyConfigScreen.java
│   └── mixin/
│       ├── ExplosionOptimizationMixin.java
│       └── ...
```
