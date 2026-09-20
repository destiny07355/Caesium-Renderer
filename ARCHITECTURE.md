# Caesium — Architecture

Minecraft 1.21.11 · Fabric · Java 21+

## 1. Runtime Model

Caesium separates frame-critical rendering from background chunk work. Expensive section extraction and meshing can run away from the render thread, but finished work is not allowed to bypass render-thread budgets.

```text
Minecraft world state
        |
        v
Rebuild request
        |
        v
MeshingJobSystem
        |
        v
Worker extraction / meshing
        |
        v
Bounded completion queue
        |
        v
Frame-budgeted integration
        |
        v
GpuUploadRing
        |
        v
Section / scene storage
        |
        v
Terrain rendering
```

## 2. Scheduling

`FrameScheduler` and its budget policy determine how much background work is safe for the current frame. Work is separated into `CRITICAL`, `VISIBLE`, `PREDICTIVE`, and `MAINTENANCE` classes.

The scheduler is intentionally bounded. Backlogs may increase throughput when frame time has sustained headroom, but a queue is never drained without a time/work limit.

Important scheduling properties:

- visible missing geometry outranks speculative work;
- old work gains urgency so it cannot starve indefinitely;
- worker pressure can change without constantly creating/destroying threads;
- stale or superseded work is discarded;
- retries are bounded and delayed rather than immediately resubmitted;
- render-thread integration is the authoritative path for completed meshes.

## 3. Section Identity & Lifecycle

Each asynchronous result is tied to the section revision that produced it. A renderer/world generation identity prevents work from an old world from integrating into a replacement world.

A result is accepted only when its world generation and section revision are still current.

World disconnect, replacement, chunk unload, resource changes, and section invalidation must clear or invalidate renderer-owned state as appropriate.

## 4. Meshing Pipeline

The meshing system owns queueing, worker execution, completion, cancellation, and stale-result handling. Extraction uses Minecraft block/model data to produce layered section meshes.

The worker side does **not** directly publish completed terrain into the active scene. It places results into the completion path, and render-thread draining performs the final bounded integration.

This separation prevents background completion bursts from turning into unbounded main-thread work.

## 5. GPU Upload Pipeline

`GpuUploadRing` stages completed geometry and applies upload work under bounded budgets.

The upload path tracks both work volume and elapsed time. When the frame is under pressure, upload work is reduced. When the frame has sustained headroom and a real backlog exists, the allowance may increase within hard limits.

OpenGL synchronization fences protect staged regions from unsafe reuse.

## 6. Visibility

Visibility decisions combine cheap early rejection with conservative culling. The system may use frustum, distance, and occlusion information, but correctness wins over aggressive rejection.

If the culler cannot prove that geometry is hidden, it remains visible.

## 7. Block Entity Ownership

Optimized block entities are classified as:

```text
STATIC       safe for section-owned static geometry
DYNAMIC      must use Minecraft's normal dynamic renderer
UNSUPPORTED  conservative fallback
```

Static ownership is invalidated when state changes make the entity dynamic or when its containing section/world is invalidated. Current optimization work includes chests, signs, beds, bells, and campfires.

The normal block-entity renderer is suppressed only when Caesium can prove that it owns valid static geometry for that position.

## 8. Memory

Renderer memory must remain bounded across long sessions, teleports, rebuild storms, and world changes.

Hot paths prefer primitive collections, reusable buffers, packed representations, and off-heap/native memory where they provide a measured benefit. Native memory is not treated as free: every long-lived allocation needs explicit ownership and lifecycle cleanup.

## 9. Particle & Effect Work

Particle policy is applied as early as practical so rejected effects do not continue through unnecessary allocation and rendering work. Category, distance, and priority controls are kept separate from terrain scheduling.

## 10. Multiplayer vs. Single-Player

In multiplayer, Caesium cannot control server terrain generation or packet delivery. The relevant renderer metric is **client chunk-to-visible latency** after usable chunk data reaches the client.

In single-player, the integrated server competes with renderer workers for CPU time. Increasing renderer concurrency can therefore reduce total chunk throughput. Worker pressure must preserve headroom for the integrated server.

## 11. Instrumentation

The chunk pipeline should expose low-overhead measurements for:

- pending and active mesh jobs;
- queue wait and oldest queue age;
- meshes completed per second;
- extraction/meshing time;
- completion queue depth;
- integration wait and throughput;
- upload queue depth, bytes, time, and wait;
- retries, rejections, deferrals, and stale discards;
- client chunk-to-visible latency.

Instrumentation must be cheap or disabled outside profiling/debug modes.

## 12. Compatibility Contract

Caesium targets Minecraft `1.21.11`, Fabric, Java `21+`, and the client environment. Unsupported or dynamic behavior should fall back conservatively rather than pretending to be optimized.

Other loaders or rendering backends are not considered supported until they have an implemented and tested runtime path.
