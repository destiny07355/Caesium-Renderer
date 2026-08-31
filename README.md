# Caesium

A client-side rendering engine for Minecraft 1.21.11 (Fabric / Java 25) built around frame-pacing, low-end hardware stability, and zero-allocation memory management.

---

## Why I Built This

I started writing Caesium because Minecraft 1.21 on integrated graphics (especially Intel UHD 630 and Iris Xe) and budget discrete cards turns into a stuttering mess during fast movement, teleporting (`/tp`), or combat with lots of particle and explosion events.

While existing performance mods do a great job pushing raw maximum FPS in a static scene, my actual gameplay experience was plagued by severe frame-time spikes:
- Moving fast or flying caused massive micro-stutters from synchronous GPU buffer uploads.
- Explosions and mob grinders flooded the JVM garbage collector with short-lived particle objects, tanking 1% lows.
- Looking toward underground caves or complex terrain rendered thousands of occluded faces that the player never actually sees.

Caesium was built over the last 5 months as a ground-up attempt to fix these specific rendering bottlenecks directly at the data and memory level.

---

## What Failed, What I Learned, and How the Architecture Evolved

### 1. The Particle Problem: Rejecting at the Source
- **What I tried first**: Initially, I tried disabling particles by cancelling their rendering pass or skipping `particle.tick()` when FPS dropped.
- **Why that failed**: The CPU was still doing 90% of the work. Minecraft was still instantiating heap objects, calculating velocity, evaluating lifetime counters, and buffering geometry—only to throw it away at the last millisecond.
- **The fix**: I moved the rejection logic to the very head of the pipeline (`CaesiumParticlePolicy` at `ParticleManager.addParticle()`). If a particle type, category, or distance threshold is disabled, it returns immediately before a single Java object is created or a single coordinate is calculated. This cut particle heap allocation to literally 0 bytes when disabled.

### 2. Chunk Upload Stalls: Persistently Mapped Rings
- **What I tried first**: Meshing chunk sections on background worker threads and calling standard OpenGL buffer uploads (`glBufferSubData`) on the render thread.
- **Why that failed**: The OpenGL driver thread would periodically stall waiting for buffer synchronization whenever large batches of chunks arrived simultaneously (like after a `/tp` command), causing 2 to 3-second freezes.
- **The fix**: Replaced standard uploads with a triple-buffered off-heap staging ring using persistent memory mapping (`GL_MAP_PERSISTENT_BIT | GL_MAP_COHERENT_BIT`) and OpenGL sync fences (`glClientWaitSync`). Meshing threads write packed vertex data directly into mapped memory off-thread, and the main thread simply dispatches the transfer without driver lockups.

### 3. Occlusion Culling: Why the Depth Buffer is 128x72
- **What I tried first**: Testing full-resolution frustum and depth culling on the CPU.
- **The trade-off**: Running rasterization tests at native 1080p or 1440p resolution on the CPU consumed more frame time than the GPU time it saved.
- **The compromise**: I implemented a downsampled $128 \times 72$ hierarchical software depth buffer (`SoftwareOcclusionCuller`). Front faces of nearby opaque chunk occluders are conservatively rasterized into this low-res buffer, and distant underground caves and mountain-blocked sections are tested in under 2 microseconds.

### 4. Memory Churn: Foreign Function & Memory (FFM) Arenas
- **The issue**: Allocating temporary `byte[]` and `float[]` arrays for vertex generation and matrix transformations created gigabytes of heap garbage per minute, causing frequent Java Garbage Collection pauses.
- **The fix**: Migrated hot buffers to Java 25 Foreign Function & Memory (FFM) scoped native arenas (`RendererArenaManager`). Frame buffers bump-allocate from off-heap native memory and reset instantly at frame boundaries without touching the JVM garbage collector.

---

## Architecture & Layering

```
                         CAESIUM ENGINE
                               │
                 ┌─────────────┴─────────────┐
                 │                           │
           CORE RENDERER                POLICIES & FEATURES
                 │                           │
        ┌────────┼────────┐          ┌───────┼────────┐
        │        │        │          │       │        │
     Meshing  Visibility Upload     Particles DRS   LOD / Decimation
        │        │        │          │
        └────────┼────────┘          │
                 │                   │
            Draw Submission          │
                 │                   │
                 └─────────┬─────────┘
                           │
                     GPU Backend
                           │
                 ┌─────────┴─────────┐
                 │                   │
             OpenGL MDI         Staging Ring
                                     │
                             FFM Memory Layer

                 SUPPORT SYSTEMS
                        │
             ┌──────────┼──────────┐
             │          │          │
           Config    Telemetry   Hardware
```

### 1. Foundational Core Architecture
- **Memory Layer (`MemoryLayer.java`, `RendererArenaManager.java`)**: Infrastructure layer providing zero-allocation frame bump allocators and persistently mapped native buffers via Java 25 FFM.
- **Chunk Meshing & Occupancy (`ChunkMesher.java`, `OccupancyCache.java`)**: 18x18x18 bitplane occupancy caching eliminates registry queries during face culling. 2D slice greedy meshing merges matching coplanar faces, cutting vertex counts by over 80%.
- **Unified Visibility System (`VisibilitySystem.java`)**: Single entry point for all culling queries. Integrates bounding sphere early-out, 6-plane AABB frustum, and 128x72 software depth occlusion into a clean policy facade (`isSectionVisible`, `isEntityVisible`).
- **Asynchronous Upload Ring (`GpuUploadRing.java`)**: Triple-buffered staging ring with OpenGL sync fences for zero-stall GPU uploads.
- **Indirect Draw Submission (`IndirectDrawManager.java`)**: Packs terrain draw commands into persistent indirect buffers, submitting entire terrain passes in a single GPU dispatch.

### 2. Performance Policies & Features
- **Dynamic Particle Policy (`CaesiumParticlePolicy.java`, `CaesiumParticleRegistry.java`)**: Source-level zero-allocation particle early rejection with 11 categories and 4 priority tiers.
- **Light Query System (`LightSystem.java`, `LightSampler.java`)**: Decoupled lighting query interface with 1024-entry hash cache lookups.
- **Dynamic Resolution Scaling & CAS (`DynamicResolutionScaler.java`, `ContrastAdaptiveSharpener.java`)**: Smoothly scales internal viewport resolution (70%–100%) during explosive frame spikes, paired with single-pass FidelityFX CAS sharpening.
- **Fast Sky & Cloud Caching (`FastSkyRenderer.java`)**: Static GPU VAO/VBO cloud geometry caching offloaded to background threads.
- **Entity Matrix Cache & LOD (`EntityMatrixCache.java`, `ChunkLodDecimator.java`)**: Contiguous 4,096-entity matrix buffer and distant quad decimation.

### 3. Support Systems
- **Configuration & GUI (`RendererConfig.java`, `OptionRegistry.java`, `TickBoxControlElement.java`)**: Centered 14x14 box-fill checkboxes, spring animation cards, and graceful legacy migration.
- **Telemetry HUD (`GraphTelemetryController.java`, `PerformanceOverlay.java`)**: Live histogram graphs (Frametime, FPS Pacing, Culling Ratio, FFM Memory).
- **Hardware Probing & Benchmarking (`HardwareBenchmarkHarness.java`, `ParticleStressBenchmarkTest.java`)**.

---

## Verified Benchmarks (Tested on Reference Host)

All measurements below were generated directly on the reference host (Windows 11, 12 Logical Cores, Eclipse Adoptium OpenJDK 25.0.2).

### 1. Engine Pipeline Benchmark

Measured via `./gradlew benchmarkPipeline` on 20,000 terrain chunk sections:

| Metric | Vanilla Baseline | Caesium (v2.0.1) | Measured Improvement |
| :--- | :--- | :--- | :--- |
| **Chunk Meshing Latency** | 2.400 ms / section | **0.413 ms / section** | **5.8x faster meshing** |
| **Meshing Throughput** | 416 sections / sec | **2,422 sections / sec** | **5.8x higher throughput** |
| **Terrain Vertex Count** | 1,024 verts / slice | **96 verts / slice** | **-90.6% vertex reduction** |
| **Occupancy Cache Build** | N/A (Registry scans) | **8.65 μs / chunk** | **115,626 builds / sec** |
| **Frustum Culling Speed** | 1.2M tests / sec | **54.6M tests / sec** | **45.5x faster culling** |
| **GPU Draw Calls (MDI)** | ~1,124 calls / frame | **1 single MDI call** | **-99.9% driver call overhead** |

---

### 2. Particle Pipeline Admission & Allocation Microbenchmark

Measured via `./gradlew benchmarkParticleStress` across 100 to 100,000 spawn requests (steady-state median of 25 timed iterations):

```
Count    | Reference Vanilla        | Caesium Engine           | Measured Benefit    
---------+--------------------------+--------------------------+---------------------
100      |  0.008 ms (10.9 KB)      |  0.001 ms (    0 B)      | -87.5% CPU, -100.0% RAM
500      |  0.021 ms (54.7 KB)      |  0.001 ms (    0 B)      | -92.8% CPU, -100.0% RAM
1,000    |  0.026 ms (109.4 KB)     |  0.003 ms (    0 B)      | -88.7% CPU, -100.0% RAM
5,000    |  0.111 ms (546.9 KB)     |  0.017 ms (171.6 KB)     | -85.1% CPU, -68.6% RAM
10,000   |  0.296 ms (1.07 MB)      |  0.034 ms (268.8 KB)     | -88.5% CPU, -75.4% RAM
25,000   |  0.963 ms (2.67 MB)      |  0.082 ms (826.3 KB)     | -91.5% CPU, -69.8% RAM
50,000   |  1.403 ms (5.34 MB)      |  0.164 ms (1.59 MB)      | -88.3% CPU, -70.2% RAM
100,000  |  3.153 ms (10.68 MB)     |  0.328 ms (3.12 MB)      | -89.6% CPU, -70.8% RAM
```

*Note: This microbenchmark measures CPU admission latency, object instantiation, and geometry generation overhead. It isolates CPU pipeline cost and does not represent overall full-game framerates.*

---

## Known Compromises & What I'm Still Working On

- **Sub-Chunk Occlusion Culler**: Currently downsampled to 128x72 to preserve CPU budget. Works reliably for terrain and caves, but thin geometry (fences, single glass panes) is conservatively treated as visible to avoid false culling artifacts.
- **Dynamic Resolution Scaling (DRS)**: Uses a 10% smoothing window to prevent rapid scale changes. Fast transitions between high-load and low-load scenes take ~3 frames to smoothly adapt.
- **Fast Sky Caching**: Static VAO cloud caching is active during standard weather; dynamic volumetric weather transitions regenerate the buffer asynchronously across 2 frames.

---

## Building from Source

### Requirements
- JDK 25 (Adoptium / Temurin recommended)
- Fabric Loader 0.18.4+

### Commands

```bash
# Build production JAR
./gradlew build -x test

# Run pipeline benchmark (Mesher, Frustum, Occupancy)
./gradlew benchmarkPipeline

# Run particle stress profiler
./gradlew benchmarkParticleStress

# Run multi-tier hardware harness
./gradlew benchmarkHardware
```

Compiled JAR will be placed in `build/libs/Caesium-2.0.1.jar`.

---

## Documentation Links

- [CHANGELOG.md](CHANGELOG.md) — Full version history and release notes.
- [BENCHMARKS.md](BENCHMARKS.md) — Extended performance report and methodology details.
- [ATTRIBUTION.md](ATTRIBUTION.md) — Open-source community acknowledgments.
- [LICENSE](LICENSE) — MIT License.

---

## License

MIT License.
