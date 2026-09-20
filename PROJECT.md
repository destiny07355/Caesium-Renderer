# Caesium — Project Overview

## Scope

Caesium is a **client-side Minecraft 1.21.11 Fabric performance and rendering engine**. Its current development line is `2.0.5` and its baseline runtime is **Java 21+**.

The project focuses on frame-time consistency, chunk processing, render-thread pressure, memory behavior, visibility work, particles, block entities, and hardware-aware scheduling.

## Current Subsystems

| Subsystem | Responsibility |
| --- | --- |
| Frame scheduler | Controls background work from frame pressure and available budget |
| Meshing job system | Prioritizes, executes, retries, cancels, and discards section work |
| Section extraction | Converts Minecraft section data into renderer-owned mesh data |
| Completion pipeline | Moves finished worker results into bounded render-thread integration |
| GPU upload ring | Applies bounded time/byte upload budgets and synchronization |
| Scene / section storage | Tracks renderer-owned world state and section lifecycle |
| Visibility | Frustum, distance, and conservative occlusion decisions |
| Block entity optimization | Static/dynamic ownership with safe fallback and invalidation |
| Particle policy | Admission, category, distance, and workload controls |
| Telemetry | Frame, meshing, upload, culling, and queue measurements |
| Configuration | Categorized client settings and compatibility controls |

## Work Priorities

Chunk work is classified by urgency:

```text
CRITICAL    frame-critical rendering work
VISIBLE     missing or outdated visible geometry
PREDICTIVE  geometry likely to become visible soon
MAINTENANCE cleanup, compaction, and low-priority work
```

The scheduler protects frame delivery first, then spends remaining CPU/GPU budget on useful background work.

## Current Engineering Target

The renderer is stable in current gameplay testing. The remaining observed issue is **slow chunk throughput in both multiplayer and single-player**.

The next optimization pass is limited to the chunk-to-visible pipeline:

```text
chunk available
    -> rebuild admission
    -> queue wait
    -> extraction / meshing
    -> completion queue
    -> render-thread integration
    -> GPU upload
    -> visible section
```

Changes should increase throughput only when measurements show spare capacity. Existing bounds, revision checks, world-generation checks, retry limits, and stale-result rejection must remain intact.

## Supported Platform

- Minecraft `1.21.11`
- Fabric
- Client side
- Java `21+`
- OpenGL

NeoForge, Vulkan, or other loader/backend support is not part of the supported public surface unless a tested implementation is present in the current source tree.

## Validation Rule

A performance change is accepted only when it produces a reproducible improvement in at least one relevant metric without introducing visual, lifecycle, or frame-pacing regressions.

Useful metrics include average FPS, 1%/0.1% lows, worst-frame time, chunk-to-visible latency, meshes per second, queue age, integration wait, upload wait, and memory growth.
