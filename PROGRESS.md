# Caesium — Development Status

## Current Baseline

**Version:** `2.0.5`  
**Minecraft:** `1.21.11`  
**Loader:** Fabric  
**Runtime:** Java `21+`  
**Environment:** Client

The current build is the stable comparison point for further work.

## Verified Current State

Current runtime testing reports:

- stable normal gameplay frame pacing;
- stable single-player rendering once chunks are available;
- stable behavior in crowded multiplayer scenes;
- bounded asynchronous chunk work;
- bounded retry behavior;
- world-generation identity checks for asynchronous work;
- section revision checks before stale work can integrate;
- bounded completion/integration flow;
- bounded GPU upload work;
- static/dynamic block-entity ownership with conservative fallback.

## Current Reproduced Issue

Chunk appearance/generation throughput is slower than desired in both multiplayer and single-player.

This is now the primary performance target. Rendering, culling, block entities, and unrelated systems should not be broadly changed unless profiling connects them directly to the chunk bottleneck.

## Current Investigation

The next profiling pass measures:

```text
chunk available
-> rebuild admission
-> queue wait
-> extraction / meshing
-> completion queue wait
-> render-thread integration
-> GPU upload wait
-> visible section
```

The goal is to identify whether the delay comes from admission, worker throughput, extraction cost, completion backlog, integration budget, upload budget, or single-player server contention.

## Recent Hardening

The 2.0.x hardening work established several invariants that future changes must preserve:

1. Worker-completed meshes integrate through the bounded render-thread completion path.
2. Asynchronous work is validated against both section revision and current world/renderer generation.
3. Retries are bounded and use delayed backoff rather than immediate resubmission.
4. Queues and renderer-owned state are bounded and cleaned across lifecycle changes.
5. GPU upload work is budgeted rather than drained without limit.
6. Static block-entity ownership is used only when Caesium has valid geometry for the current state.
7. Unsupported or uncertain rendering behavior falls back conservatively.

## Historical Notes

Earlier `1.x` documentation contains experiments, abandoned paths, temporary constraints, and intermediate architecture. Those records are useful engineering history but are **not authoritative descriptions of the current 2.0.5 runtime**.

Examples include old DestinyRenderer naming, Java 25-only assumptions, experimental Vulkan/window-present work, terrain ownership gates, fixed upload budgets, and early settings/UI designs.

Use `README.md`, `PROJECT.md`, and `ARCHITECTURE.md` as the current documentation set.

## Release Gate

Do not treat the current branch as release-ready solely because it compiles.

Before a public alpha, verify at minimum:

- fresh Java 21 launch;
- world creation and world replacement;
- chunk generation and high-speed movement;
- teleport bursts;
- multiplayer chunk arrival;
- dimension changes;
- disconnect/reconnect;
- resource reload;
- chests, signs, beds, bells, and campfires;
- explosion/crystal-heavy scenes;
- common Fabric compatibility;
- no unbounded memory growth;
- no major visual corruption;
- no regression from the 2.0.5 frame-time baseline.
