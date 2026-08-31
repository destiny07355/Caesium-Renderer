# Original User Request

## Initial Request — 2026-07-22T23:23:07Z

DestinyRenderer is a custom GPU-driven Fabric Minecraft mod for 1.21.11 targeting integrated Intel UHD graphics. The goal is to research exactly how Sodium and VulkanMod achieve their performance and visual quality, identify every gap in DestinyRenderer, implement all missing optimisations (with full rewrites where needed), add configurable quality/performance profiles, and verify via automated benchmarks that DestinyRenderer measurably outperforms Sodium in both FPS and visual quality.

Working directory: `d:/Special Mods-1.21.11/DestinyRenderer`

Integrity mode: **development** — use any available open-source references, mods, and research.

---

## Context & Reference Material

- **Sodium source:** https://github.com/CaffeineMC/sodium (study: chunk graph, section render list, ChunkUpdateTask, RenderSectionManager, indirect draw path, terrain pipeline)
- **VulkanMod source:** https://github.com/xCollateral/VulkanMod (study: VkWorld, VkChunkRenderer, staging buffer strategy, descriptor sets, GPU-driven indirect dispatch)
- **Lithium source:** https://github.com/CaffeineMC/lithium-fabric (study: explosion caching, block entity tick suppression, fluid tick, entity AI)
- **ImmediatelyFast source:** https://github.com/RaphiMC/ImmediatelyFast (entity & GUI batching)
- **Iris source:** https://github.com/IrisShaders/Iris (shader pipeline, deferred rendering hooks)
- **Existing DestinyRenderer code** in `d:/Special Mods-1.21.11/DestinyRenderer/src/main/java/` — read every file thoroughly before proposing changes.
- **Fabric Yarn 1.21.11 mappings** — use for all method signatures.

---

## Requirements

### R1. Research Phase — Gap Analysis Report
Read the full source code of Sodium and VulkanMod. Produce a written gap analysis listing every technique they use that DestinyRenderer is currently missing or implementing worse. Organise by category (chunk meshing, GPU batching, culling, memory, shaders, entities, particles, PvP/gameplay smoothness, startup). For each gap state: what it does, how Sodium/Vulkan implements it, and what the estimated FPS impact is.

### R2. Chunk Meshing & Upload Pipeline
Implement Sodium-equivalent or better chunk meshing optimisations:
- Radial spiral chunk section prioritisation (camera-out ordering in the build queue)
- Frame-budget upload throttling (cap chunk uploads to ≤2.5 ms per frame to prevent stutters)
- Greedy meshing or quad merging to reduce vertex count per section
- Chunk section dependency graph (only re-mesh sections whose neighbours changed)
- Thread-local mesher instances with zero allocation per mesh call

### R3. GPU Batching & Draw Call Reduction
Match or exceed Sodium's and VulkanMod's indirect rendering path:
- Multi-Draw Indirect (MDI) with a persistent GPU-side command buffer rebuilt each frame
- Per-frame frustum + occlusion culling on CPU feeding the MDI command list
- Region-based 128³ spatial hierarchy so region-level culling skips 512 chunks in one test
- Translucency sort by back-to-front distance, rebuilt only when camera chunk changes
- Correct Yarn 1.21.11 mixin targets for `Explosion.getExposure` equivalent — find and fix the `ExplosionOptimizationMixin` which currently targets a non-existent method name

### R4. Memory Layout & Buffer Management
- Slab/pool allocator for chunk VBO/IBO regions (avoid per-section malloc/free fragmentation)
- Free-list recycling so destroyed chunk sections return memory to pool immediately
- Persistent coherent buffer mapping for iGPU zero-copy path (no glBufferSubData stalls)
- Separate opaque / cutout / translucent / entity / particle VBO pools

### R5. Shader Quality — User-Selectable Visual Presets
Add configurable visual quality presets selectable in the config screen:
- **Performance** preset: flat diffuse, no AO, minimal fog
- **Balanced** preset (default): per-vertex AO, directional diffuse, distance fog
- **Quality** preset: per-fragment smooth AO, soft shadow approximation, volumetric fog hint, FXAA-style edge softening pass
All presets must compile on `#version 330 core` (Intel UHD compatible).

### R6. Entity, Particle & PvP Rendering
- Batch entity draw calls by model type (instanced rendering for identical mob models)
- Skip entity AO calculation for mobs farther than configurable LOD distance
- Particle VBO batching with per-type pools
- Reduce per-entity NBT reads during render tick (cache read values for N frames)
- For PvP: minimise input-to-render latency by processing pending chunk uploads at frame-start not frame-end, ensuring the render state is never stale when a player moves

### R7. Startup & World Join Speed
- Spiral-priority initial chunk load (innermost ring meshed and uploaded before outer rings)
- Async palette snapshot: capture block state palette on a background thread snapshot, not while holding the chunk read lock
- Retry-with-backoff for top-world-height sections that fail populate() due to ConcurrentModificationException (currently implemented — verify it works correctly)

### R8. Config Screen Polish
- Visual quality preset selector (Performance / Balanced / Quality) in the General tab
- All new features (greedy meshing, frame budget, LOD distance) exposed in config screen
- Config screen must compile and open without errors at all GUI scale factors (1x–4x)

### R9. Verification — Automated Benchmark
- Build the mod via `gradlew build` — must produce BUILD SUCCESSFUL with zero errors
- Run a 60-second FPS benchmark in a flat superflat world at render distance 12
- Record average FPS, 1% low FPS, and chunk upload time per frame
- Compare to the Sodium baseline (or vanilla if Sodium is not installed)
- Produce `benchmark_results.txt` with the final numbers

---

## Acceptance Criteria

### Build
- [ ] `gradlew build` completes with **BUILD SUCCESSFUL** and **zero compile errors**
- [ ] No mixin injection failures in `run/logs/latest.log`
- [ ] `ExplosionOptimizationMixin` targets a valid method in the 1.21.11 Yarn mappings

### Chunk Rendering
- [ ] All chunk sections within render distance load within 10 seconds of world join
- [ ] Top-height sections (y ≥ 304) render correctly without missing blocks
- [ ] Block placement, break, and explosion updates appear within 1 second visually

### Performance
- [ ] Average FPS in a superflat world at render distance 12 is **≥ 20% higher than vanilla** on Intel UHD integrated graphics
- [ ] No frame time spikes > 50 ms during chunk loading burst
- [ ] Chunk upload time per frame stays ≤ 2.5 ms at steady state

### Visual Quality
- [ ] All three visual presets (Performance / Balanced / Quality) are selectable in config screen
- [ ] Quality preset shows noticeably better AO / lighting than vanilla
- [ ] No Z-fighting, missing faces, or incorrect texture alpha on any block type

### Config Screen
- [ ] Config screen opens without errors at GUI scale 1x, 2x, 3x, 4x
- [ ] Particle tab shows all 7 individual particle toggles
- [ ] Sliders only move when actively held (drag-only behaviour)
