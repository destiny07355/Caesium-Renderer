# 🚀 Caesium / DestinyRenderer — Master Engineering & Refactoring Blueprint

> **Core Engineering Philosophy**: *"Do the minimum amount of work necessary to produce the exact same image."*  
> If 200,000 vertices produce the exact same visual output as 500,000 vertices, render 200,000.  
> If CPU culling costs 0.2ms and GPU culling costs 0.5ms on a given scene, use CPU culling.  
> If Hi-Z saves 1ms of rasterization but costs 1.2ms to compute on an iGPU, skip Hi-Z.  
> **Every single optimization MUST be validated against a measurable, reproducible baseline.**

---

## 📊 0. Performance Baseline & Telemetry Instrumentation (Step 1)

Before modifying any rendering code, establish a strict, repeatable benchmarking methodology:

### Standardized Telemetry Schema
Caesium must track and log these 11 real-time metrics per test run:
```
┌─────────────────────────────────────────────────────────────┐
│                   Caesium Telemetry Report                  │
├────────────────────────────┬────────────────────────────────┤
│ FPS (Average / 1% Lows)    │ 142 FPS (p99.5: 7.04 ms)       │
│ CPU Render Time            │ 3.1 ms                         │
│ GPU Render Time (Timer)    │ 5.8 ms                         │
│ Chunk Meshing Time         │ 2.4 ms / section               │
│ Chunk Upload Time          │ 0.4 ms / frame                 │
│ Visible Chunk Sections     │ 1,842                          │
│ Rendered Vertex Count      │ 18.2M                          │
│ Total Draw Calls           │ 1,124                          │
│ Mesh Rebuilds / Sec        │ 48 sections/s                  │
│ GPU / VRAM Memory Usage    │ 412 MB                         │
│ Heap Allocation Rate       │ 12.4 MB/s                      │
└────────────────────────────┴────────────────────────────────┘
```

### Standardized Benchmark Conditions
Every A/B comparison test (Caesium vs. Sodium vs. Baseline) must use:
1. **Identical World & Seed**: Fixed coordinate spawn in benchmark test world.
2. **Fixed Settings**: Render Distance = 16, Simulation Distance = 12, FOV = 70, Shaders = OFF, Default Resource Pack.
3. **Locked Camera Position**: Stationary camera for 30 seconds followed by a scripted 100-block flythrough.
4. **Validation Criteria**: An optimization is only accepted if it improves average FPS, reduces 1% low frame spikes, drops vertex/draw counts, or lowers memory bandwidth without visual regressions.

---

## 🧭 The 12-Step Implementation Sequence

```
 1. Benchmark Instrumentation & Baseline Capture
        ↓
 2. Wire & unify core terrain rendering pipeline
        ↓
 3. Eliminate all object allocations from meshing hot path
        ↓
 4. Implement SectionSnapshot & Bitmask Occupancy Cache
        ↓
 5. Real 2D-Slice Greedy Meshing for Opaque Geometry
        ↓
 6. Priority-weighted adaptive chunk rebuild scheduler
        ↓
 7. Staging / Ring-buffered zero-copy GPU uploads
        ↓
 8. CPU frustum culling for LowBandwidth profile (iGPUs)
        ↓
 9. GPU-driven MDI pipeline for powerful dGPUs
        ↓
10. Profitability-gated adaptive Hi-Z occlusion culling
        ↓
11. Multi-metric hardware capability scoring
        ↓
12. 5-Tier User Presets & release artifact cleanup
```

---

## 🛠️ The 16 Core Architectural Pillars

### 1. Section Occupancy Bitmasks (`OccupancyCache`)
Instead of testing 6 individual face neighbors with expensive `getBlockState()` lookups:
* Build three compact $16\times 16\times 16$ bitmasks per section:
  * `solid[x][y][z]`
  * `opaque[x][y][z]`
  * `translucent[x][y][z]`
* Calculate face existence via bitwise shifts:
  $$\text{FaceEast} = \text{opaque}[x][y][z] \ \& \sim\text{opaque}[x+1][y][z]$$
* Eliminates millions of repeated block state lookups on weak single-thread CPU cores.

---

### 2. Real 2D Slice Greedy Quad Merging (Opaque First)
* For each axis ($X, Y, Z$), iterate through the 16 2D slices.
* Form a 64-bit `FaceKey`:
  ```java
  record FaceKey(
      int materialId,    // 16 bits
      int textureId,     // 12 bits
      int lightLevel,    // 8 bits (block + sky)
      int bakedAO,       // 8 bits (4 corners x 2 bits)
      int biomeTintId,   // 8 bits
      int renderLayer    // 4 bits
  ) {}
  ```
* Merge contiguous matching cells horizontally, then extend vertically into a single large quad ($W \times H$).
* **Target**: 40%–60% reduction in vertex and index counts, dropping VRAM usage and shared memory bandwidth bottlenecks on Intel UHD / Iris Xe.

---

### 3. Specialized Sub-Meshers (No Universal Giant Function)
Separate chunk meshing into 4 dedicated, specialized pipelines:
1. **`OpaqueMesher`**: Ultra-aggressive greedy quad merging, 64-bit packed vertices, large batching.
2. **`CutoutMesher`**: Foliage/leaves with selective merging, alpha-tested quads.
3. **`TranslucentMesher`**: Conservative non-merged geometry with strict depth sorting.
4. **`FluidMesher`**: Specialized surface/flow height-interpolated liquid mesh generator.

---

### 4. `SectionSnapshot` (Cache-Friendly Primitive Arrays)
Eliminate pointer chasing and Minecraft object references during meshing:
```
SectionSnapshot
 ├── short[] blockStateIds (4096 entries)
 ├── long[]  opacityMask   (16x16 bitplanes)
 ├── byte[]  packedLight   (block + sky)
 └── byte[]  tintIndexMap
```
Meshing threads execute entirely against contiguous CPU cache-line friendly primitive arrays.

---

### 5. Zero-Allocation Hot Path
* Strictly ban `new Face()`, `new Vertex()`, `new Vector3f()`, and `new Box()` inside chunk extraction, meshing, and frustum culling.
* Hot path flow:
  $$\text{SectionSnapshot} \longrightarrow \text{Primitive Calculations} \longrightarrow \text{long[] Packed Vertices} \longrightarrow \text{GPU Ring Buffer}$$

---

### 6. Stratified Dual-Renderer Architecture

```
                       Caesium Unified Engine
                                 │
         ┌───────────────────────┴───────────────────────┐
         ▼                                               ▼
┌─────────────────────────────────┐     ┌─────────────────────────────────┐
│     LowBandwidthRenderer        │     │       GPUDrivenRenderer         │
├─────────────────────────────────┤     ├─────────────────────────────────┤
│ • Targets: Intel UHD/HD, Vega   │     │ • Targets: RTX, RX, Arc, Apple M│
│ • Aggressive Greedy Meshing     │     │ • GPU-Resident Mesh Metadata    │
│ • CPU-Side Fast Frustum Tests   │     │ • Compute Shader Frustum Culling│
│ • Compact VBOs (1-2 Buffers)    │     │ • Hi-Z Depth Pyramid Occlusion  │
│ • Zero Compute Pass Overhead    │     │ • MultiDrawIndirect (MDI)       │
│ • Shared System RAM Protection  │     │ • Persistent Mapped Ring Buffers│
└─────────────────────────────────┘     └─────────────────────────────────┘
```

---

### 7. Profitability-Gated Adaptive Compute Culling (Hi-Z Threshold)
Compute passes cost GPU dispatch time. On small scenes or weak iGPUs, drawing 200 chunks directly is faster than computing a depth pyramid:
```java
if (hardware.isDiscrete() && visibleChunks > HIZ_PROFITABILITY_THRESHOLD) {
    executeGpuHiZPass();
} else {
    executeCpuFrustumCull();
}
```

---

### 8. Workload-Aware Adaptive Meshing Scheduler
Replace fixed `threads = cores - 2` with a dynamic pacing loop:
* If the main render thread frame time spikes ($>16.6\text{ms}$ at 60Hz or $>4.1\text{ms}$ at 240Hz), worker concurrency dynamically scales down.
* If the chunk queue explodes (teleport or high-speed elytra flight), worker threads temporarily burst.

---

### 9. Priority-Weighted Chunk Rebuild Queue
Replace FIFO processing with distance & view cone weighting:
$$\text{Priority} = 1000 \times \text{IsCurrentChunk} + 500 \times \text{InFrustumCone} + \frac{250}{\text{DistanceSq} + 1}$$
* When the player breaks or places a block in front of them, that chunk section rebuilds instantly on the next frame.

---

### 10. Multi-Buffered Zero-Copy Upload Ring
* Eliminate driver stalls and buffer allocations during terrain updates by using a 3-region staging ring:
  * **Frame $N$**: CPU writes to Region A $\rightarrow$ GPU draws Region C.
  * **Frame $N+1$**: CPU writes to Region B $\rightarrow$ GPU draws Region A.
  * **Frame $N+2$**: CPU writes to Region C $\rightarrow$ GPU draws Region B.

---

### 11. Hardware-Aware Memory Allocation (Dedicated vs. Shared RAM)
* **iGPUs**: Operate under strict memory pressure budgets; avoid oversized persistent allocations.
* **dGPUs**: Allocate large contiguous VRAM blocks (TLSF arena) for direct resident GPU draws.

---

### 12. Multi-Metric Hardware Capability Scoring
Replace brittle vendor string matches (`"contains intel uhd"`) with capability probes:
* Dedicated VRAM vs. Shared System Memory.
* Memory Bandwidth estimate.
* Compute shader capability & Indirect command support.
* Logical vs. physical CPU core topology.

---

### 13. Metric-Driven Profiling (CPU-Bound vs. GPU-Bound vs. Bandwidth-Bound)
* **CPU-Bound** (GPU 40%, Main Thread 100%): Cut draw calls, optimize CPU culling and tick logic.
* **GPU-Bound** (GPU 98%, CPU 35%): Reduce fragment shader overdraw, enable Hi-Z.
* **Bandwidth-Bound** (iGPU 90%, Bus saturated): Greedy mesh faces, compact vertex bitfields.

---

### 14. Dedicated 6-Scene Caesium Benchmark Suite
Instrument standardized automated benchmarks against Sodium:
1. **Scene A**: Empty terrain (Draw submission overhead).
2. **Scene B**: Dense forest (Leaf geometry & alpha overdraw).
3. **Scene C**: Village (Entities, tile entities, terrain).
4. **Scene D**: Redstone clock / TNT (Dynamic mesh rebuild throughput).
5. **Scene E**: Nether (Dense terrain, volumetric fog, particles).
6. **Scene F**: Ocean / Transparent water (Translucent sorting & blending).

---

### 15. Clean 5-Tier User-Facing Presets
Replace 100+ granular switches in the UI with intuitive profiles:
* `Potato` $\rightarrow$ Maximum bandwidth conservation, minimal particle load.
* `Low` $\rightarrow$ Solid 60 FPS target for integrated graphics laptops.
* `Balanced` $\rightarrow$ Standard high-quality gameplay.
* `Competitive` $\rightarrow$ High-refresh rate (144Hz–360Hz+), instant crystal blast response.
* `Cinematic` $\rightarrow$ Max LOD fidelity, full draw distance, Hi-Z occlusion.
* `Custom` $\rightarrow$ Developer toggle matrix.

---

### 16. Lean Release Artifact
* Strip test harnesses (`TestQuadPass`, `HeadlessGlTest`, `BenchmarkFramework`) out of the release mod JAR into a separate test source set.
