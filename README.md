Also, uhh i forgot to say that there are a lot of options right now in the config im thinking on changing the configuration menu for simple and advanced two seperate 
---
# Caesium

**Caesium** is a client-side performance and rendering engine for **Minecraft 1.21.11** on **Fabric**, built around stable frame times, efficient chunk processing, low allocation pressure, and predictable behavior under heavy load.

It targets both integrated and dedicated GPUs and is designed to keep rendering responsive during chunk loading, fast movement, combat, explosions, and crowded scenes.

---

## Features

### Chunk Pipeline

- Asynchronous section extraction and meshing
- Priority-based rebuild scheduling
- Visible, predictive, and maintenance work classes
- Stale-result rejection using section revisions and world generation identity
- Bounded queues and retry backoff
- Frame-budgeted mesh integration
- Adaptive GPU upload limits

### Frame-Aware Scheduling

Caesium adjusts background work using frame pressure, queue pressure, visibility, distance, and available CPU time. Critical rendering work stays ahead of speculative and maintenance work, while bounded budgets prevent large chunk bursts from monopolizing the render thread.

### GPU Uploads

Terrain uploads use a bounded staging pipeline with OpenGL synchronization. Upload work is limited by both time and data volume so completed meshes can be integrated without turning a large backlog into a single-frame stall.

### Visibility & Culling

Caesium combines frustum, distance, and conservative occlusion checks to reduce unnecessary rendering. When visibility is uncertain, geometry remains visible rather than risking false culling.

### Optimized Block Entities

Supported block entities can use a static section-owned path when safe. Animated, changing, unsupported, or modded block entities fall back to Minecraft's normal dynamic renderer.

Current optimization work covers block entities such as chests, signs, beds, bells, and campfires, with ownership and invalidation handled across rebuilds and world lifecycle changes.

### Particles & Effects

Particle admission, distance filtering, category controls, and workload limits reduce unnecessary CPU, allocation, and rendering cost during effect-heavy scenes.

### Memory & Allocation

Hot paths favor reusable buffers, primitive collections, packed data, bounded caches, and off-heap/native storage where appropriate. The goal is to reduce short-lived allocation and avoid unbounded renderer state.

### Profiling

Caesium includes instrumentation for frame pacing, chunk meshing, queue pressure, GPU uploads, culling, and memory behavior. Performance changes are intended to be measured rather than inferred from code alone.

---

## Compatibility

| Requirement | Support |
| --- | --- |
| Minecraft | `1.21.11` |
| Mod Loader | Fabric |
| Environment | Client |
| Java | `21+` |
| Fabric Loader | `0.18.0+` |
| Fabric API | Required |
| Rendering | OpenGL |

Caesium is currently a Fabric client mod. Other loaders should not be considered supported until they have a real implementation and test coverage.

---

## Current Status

The current development line is **2.0.5**. Frame pacing and normal gameplay are stable in current testing. The main active performance target is improving chunk appearance/generation throughput in both multiplayer and single-player without regressing frame-time stability.

---

## Building

### Requirements

- JDK 21+
- Gradle / Gradle Wrapper
- Fabric development environment

```bash
./gradlew build
```

Build output is written to:

```text
build/libs/
```

---

## Documentation

- [`ARCHITECTURE.md`](ARCHITECTURE.md) — current runtime architecture
- [`ENGINE_REFACTOR_PLAN.md`](ENGINE_REFACTOR_PLAN.md) — active engineering priorities
- [`PROGRESS.md`](PROGRESS.md) — implementation and verification status
- [`PROJECT.md`](PROJECT.md) — project scope and subsystem map
- [`ATTRIBUTION.md`](ATTRIBUTION.md) — third-party acknowledgements

---

## License

Caesium is licensed under the **MIT License**.
