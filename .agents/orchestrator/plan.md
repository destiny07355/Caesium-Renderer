# DestinyRenderer Execution Plan

## Architecture & Goals
DestinyRenderer is a custom GPU-driven Fabric Minecraft 1.21.11 mod targeting Intel UHD integrated graphics.
Our goal is to implement all missing optimisations from Sodium, VulkanMod, Lithium, and ImmediatelyFast, ensuring high FPS, low frame latency (≤2.5ms upload budget), and high visual quality (3 selectable presets).

## Execution Strategy: Dual Track
- **Implementation Track**: 9 sequential/parallel milestones covering requirements R1 to R9.
- **E2E Testing Track**: Parallel test suite creation producing `TEST_INFRA.md` and `TEST_READY.md` covering Tiers 1-4 requirement-driven opaque-box tests.

## Milestones Overview

### Milestone 1: Research & Gap Analysis Report (R1)
- **Objective**: Conduct comprehensive code analysis of Sodium, VulkanMod, Lithium, ImmediatelyFast, Iris, and current DestinyRenderer code. Produce a detailed gap report.
- **Deliverables**: Gap Analysis Report document detailing missing features, implementation mechanisms, and FPS impact.

### Milestone 2: Chunk Meshing & Upload Pipeline (R2)
- **Objective**: Implement radial spiral chunk section prioritization, frame-budget upload throttling (≤ 2.5ms/frame), greedy meshing / quad merging, section dependency graph, thread-local zero-allocation meshers.
- **Deliverables**: Replaced/upgraded chunk meshing pipeline in `destinyrenderer.client.render.chunk`.

### Milestone 3: GPU Batching & Draw Call Reduction (R3)
- **Objective**: Multi-Draw Indirect (MDI) with persistent GPU command buffer, per-frame frustum + occlusion culling, 128^3 spatial hierarchy, translucency back-to-front sorting, fix `ExplosionOptimizationMixin` Yarn 1.21.11 target.
- **Deliverables**: MDI pipeline, spatial hierarchy, fixed mixin targets.

### Milestone 4: Memory Layout & Buffer Allocations (R4)
- **Objective**: Slab/pool allocator for chunk VBO/IBO regions, free-list recycling, persistent coherent buffer mapping for iGPU, separate pools (opaque, cutout, translucent, entity, particle).
- **Deliverables**: Memory allocation subsystem in `destinyrenderer.client.memory`.

### Milestone 5: Shader Quality & Visual Presets (R5)
- **Objective**: User-selectable visual quality presets (Performance, Balanced, Quality) compiled on `#version 330 core` (Intel UHD compatible). Includes smooth AO, directional diffuse, volumetric fog hints, FXAA-style edge softening.
- **Deliverables**: GLSL shaders and shader manager preset integration.

### Milestone 6: Entity, Particle & PvP Rendering (R6)
- **Objective**: Batch mob draw calls (instanced rendering), entity AO LOD distance, particle VBO pools, per-entity NBT caching, frame-start chunk upload processing for zero-latency PvP.
- **Deliverables**: Entity & particle rendering overhaul.

### Milestone 7: Startup & World Join Speed (R7)
- **Objective**: Spiral-priority initial chunk load, async palette snapshotting on background thread, verify/fix ConcurrentModificationException retry backoff for top-height sections.
- **Deliverables**: Fast world join & chunk loading pipeline.

### Milestone 8: Config Screen Polish (R8)
- **Objective**: Config screen preset selector, expose all new settings, GUI scale 1x-4x compatibility, drag-only sliders, 7 particle toggles.
- **Deliverables**: Config screen GUI & option bindings.

### Milestone 9: Final E2E Test Pass, Benchmarking & Victory Audit (R9)
- **Objective**: Pass 100% E2E test suite, perform 60-second benchmark in superflat world at RD12, generate `benchmark_results.txt`, pass Forensic Audit, notify Sentinel.
- **Deliverables**: `gradlew build` success, `benchmark_results.txt`, Victory Audit clearance.

## E2E Testing Track
- Develop comprehensive test suite (Tier 1: Feature Coverage, Tier 2: Boundary/Corner, Tier 3: Combinations, Tier 4: Real-world Workloads).
- Publish `TEST_READY.md` when ready.
