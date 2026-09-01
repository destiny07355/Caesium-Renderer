# Caesium

An experimental client-side rendering engine for Minecraft 1.21.11 (Fabric / Java 25), focused on frame pacing, low-end hardware stability, and efficient memory management.

> ⚠️ Early Alpha
>
> Caesium is still under active development. Features, performance, configuration, and internal architecture may change significantly between releases.
>
> Performance claims and comparative benchmarks are intentionally not published yet. Testing is currently being performed across different systems before any formal benchmark results are released.

---

## Why I Built This

I started working on Caesium because I wanted to explore a different approach to Minecraft rendering performance.

Rather than focusing exclusively on maximum FPS in simple scenes, Caesium is designed around the problems that can appear during demanding gameplay:

- Fast movement and teleportation
- Large terrain updates
- Explosions and particle-heavy situations
- Complex terrain and underground areas
- Frame-time spikes
- Temporary memory allocations
- GPU buffer synchronization

The goal is to build a renderer that remains responsive and consistent when the workload suddenly changes.

Caesium is a ground-up experimental project focused on investigating these problems at the rendering, data, and memory-management levels.

---

## What Caesium Focuses On

### Frame Pacing

Caesium places a strong emphasis on consistent frame times rather than simply maximizing the average FPS number.

The renderer is designed to reduce unnecessary synchronization and expensive work on the critical rendering path.

### Particle Processing

Caesium includes a particle policy system designed to make decisions as early as possible.

`CaesiumParticlePolicy` can reject particles before unnecessary downstream processing occurs when the relevant particle type, category, or configuration is disabled.

### Asynchronous GPU Uploads

Caesium uses an asynchronous upload system based around persistently mapped OpenGL buffers and synchronization fences.

The goal is to reduce stalls when large amounts of chunk data need to reach the GPU at once.

### Visibility Processing

Caesium uses multiple visibility checks rather than relying on a single culling method.

The visibility system currently combines:

- Bounding-volume early outs
- Frustum culling
- Software depth-based occlusion
- Chunk visibility policies
- Entity visibility checks

### Memory Management

Hot rendering paths make use of Java 25's Foreign Function & Memory API where appropriate.

`RendererArenaManager` provides scoped off-heap memory used for temporary frame allocations, reducing unnecessary JVM heap activity in selected rendering paths.

---

# Architecture

                         CAESIUM ENGINE
                              │
              ┌───────────────┴───────────────┐
              │                               │
        CORE RENDERER                  POLICIES & FEATURES
              │                               │
       ┌──────┼──────┐                 ┌──────┼──────┐
       │      │      │                 │      │      │
    Meshing Visibility Upload       Particles  DRS    LOD
       │      │      │
       └──────┼──────┘
              │
        Draw Submission
              │
              ▼
         GPU Backend
              │
       ┌──────┴──────┐
       │             │
     OpenGL      Upload Ring
                     │
                FFM Memory

              SUPPORT SYSTEMS
                     │
          ┌──────────┼──────────┐
          │          │          │
       Config     Telemetry   Hardware

---

## Core Systems

### Memory Layer

`MemoryLayer.java` and `RendererArenaManager.java`

Provides infrastructure for temporary frame allocations and off-heap rendering buffers using Java 25 FFM.

### Chunk Meshing

`ChunkMesher.java`

Handles chunk geometry generation and includes occupancy-based processing and greedy meshing techniques.

### Visibility System

`VisibilitySystem.java`

Provides a unified entry point for visibility checks including frustum and software occlusion testing.

### GPU Upload Ring

`GpuUploadRing.java`

Uses persistently mapped buffers and synchronization primitives to reduce synchronization overhead during chunk uploads.

### Indirect Draw Submission

`IndirectDrawManager.java`

Handles indirect terrain draw submission through persistent GPU buffers.

---

## Performance Systems

### Dynamic Particle Policy

`CaesiumParticlePolicy.java`
`CaesiumParticleRegistry.java`

Provides configurable particle admission and filtering before unnecessary downstream work is performed.

### Lighting

`LightSystem.java`
`LightSampler.java`

Provides the renderer's lighting query infrastructure and caching.

### Dynamic Resolution Scaling

`DynamicResolutionScaler.java`

Adjusts internal rendering resolution according to the current workload.

### Contrast Adaptive Sharpening

`ContrastAdaptiveSharpener.java`

Provides sharpening when dynamic resolution scaling is active.

### Sky & Cloud Rendering

`FastSkyRenderer.java`

Provides cached rendering paths for static sky and cloud geometry.

### Entity Processing

`EntityMatrixCache.java`
`ChunkLodDecimator.java`

Provides entity matrix caching and experimental level-of-detail processing.

---

## Configuration & Interface

Caesium includes a custom configuration system designed around a clean and lightweight interface.

Current components include:

- `RendererConfig.java`
- `OptionRegistry.java`
- `TickBoxControlElement.java`

The interface is still evolving as the renderer develops.

---

## Telemetry

Caesium includes optional development telemetry for investigating renderer behavior.

Current telemetry systems include:

- Frame-time information
- FPS pacing information
- Culling information
- FFM memory information

These tools are primarily intended to help development and debugging.

---

## Benchmarking

Benchmark infrastructure is included in the project for local testing.

However, official benchmark results are not currently published.

The benchmark system is intended to allow testers to run workloads on their own hardware and provide reproducible results.

Current benchmark components include:

- `BenchmarkFramework`
- `CaesiumParticleBenchmarkTest`
- `EnginePipelineBenchmarkTest`
- `HardwareBenchmarkHarness`
- `ParticleStressBenchmarkTest`
- `ChunkMesherBenchmarkTest`

Benchmark results will be published separately once testing across different systems has been completed and the methodology has been finalized.

---

## Current Development Status

Caesium is currently in Early Alpha.

The renderer is actively being developed, and several systems are still experimental.

Areas currently being worked on include:

- Rendering stability
- Frame pacing
- Chunk processing
- GPU upload synchronization
- Visibility and occlusion
- Particle processing
- Memory management
- Dynamic resolution
- Configuration UI
- Telemetry
- Compatibility testing

Expect bugs, incomplete features, and architectural changes.

---

## Known Limitations

### Software Occlusion

The current software occlusion system uses a reduced-resolution depth representation to keep CPU overhead manageable.

Thin geometry may conservatively remain visible to avoid incorrect culling.

### Dynamic Resolution

Dynamic resolution scaling is still being tuned to balance responsiveness with visual stability.

### Sky & Cloud Caching

Some dynamic weather transitions require additional buffer updates.

### Compatibility

Caesium is an experimental renderer and compatibility with every Minecraft mod, resource pack, graphics configuration, or hardware configuration is not guaranteed.

---

## Building from Source

### Requirements

- Minecraft 1.21.11
- Fabric
- Java 25
- A working Gradle environment

### Build

    ./gradlew build -x test

The compiled JAR will be placed in:

    build/libs/

---

## Testing

If you are testing an Alpha build, please report:

- Minecraft version
- Fabric Loader version
- Java version
- CPU
- GPU
- RAM
- Installed rendering/performance mods
- Resource packs
- What you were doing when the issue occurred
- Any relevant logs

For performance issues, frame-time information is more useful than average FPS alone.

---

## Contributing

Caesium is currently undergoing rapid development.

If you want to experiment with the project, test it on different hardware, or investigate rendering behavior, feedback and reproducible test results are especially useful.

---

## Documentation

- `CHANGELOG.md` — Project history and release changes.
- `ATTRIBUTION.md` — Open-source acknowledgments.
- `LICENSE` — MIT License.

---

## License

MIT License.
