# Caesium — Active Engineering Plan

## Objective

Preserve the current stable frame pacing while improving the only remaining reproduced performance issue: **slow chunk appearance/generation throughput in multiplayer and single-player**.

This is a focused pipeline pass, not a renderer rewrite.

## 1. Measure the Complete Chunk-to-Visible Path

Instrument these timestamps/stages:

```text
chunk data available
-> rebuild admitted
-> queued
-> extraction started
-> mesh completed
-> completion queued
-> render-thread integration
-> GPU upload
-> section visible
```

Track queue depth, queue age, active workers, meshes/sec, average mesh cost, completion backlog, integration wait, upload wait, retries, deferrals, rejections, and stale discards.

For multiplayer, separate server/network arrival time from Caesium's client-side chunk-to-visible latency.

## 2. Find the Actual Bottleneck

Do not increase thread counts or budgets by assumption. Determine whether throughput is limited by:

- rebuild admission;
- meshing queue latency;
- worker utilization;
- extraction/meshing cost;
- completion queue pressure;
- render-thread integration budget;
- GPU upload budget;
- retry/defer behavior;
- redundant rebuilds;
- single-player integrated-server contention.

## 3. Backlog-Aware Scheduling

Frame pressure and backlog pressure should both affect admission.

```text
healthy frame + low backlog   -> normal background work
healthy frame + high backlog  -> gradually increase throughput
slow frame    + high backlog  -> protect frame time
slow frame    + low backlog   -> strongly reduce optional work
```

Use queue age as well as queue depth. A visible section that has waited hundreds of milliseconds should outrank fresh speculative work.

## 4. Worker Utilization

When a useful backlog exists, measure active/idle workers and CPU pressure. Fix artificial idling or admission limits before adding concurrency.

In single-player, leave CPU headroom for the integrated server. Maximum renderer worker utilization is not the goal; maximum end-to-end chunk throughput is.

## 5. Extraction / Meshing

Profile `BakedSectionExtractor` and related hot paths before changing them. Break down time spent in block-state access, model lookup, quad retrieval/decoding, render-layer decisions, lighting/AO, vertex packing, and block-entity optimization.

Cache repeated work only when lifecycle and resource-reload invalidation are correct.

## 6. Completion & Integration

Maintain one authoritative completion path:

```text
worker -> bounded completion queue -> budgeted render-thread drain -> scene
```

No worker may bypass the integration budget with a direct scene push.

If healthy frames coexist with a large completion backlog, allow the integration budget to rise gradually within hard bounds. Reduce it quickly when frame pressure returns.

## 7. GPU Upload Throughput

Keep both byte and time limits. Make the working allowance adaptive rather than unbounded.

Integrated GPUs require conservative behavior because CPU and GPU share memory bandwidth.

## 8. Preserve Correctness

Do not regress:

- world-generation identity;
- section revision validation;
- bounded retry/backoff;
- pending-job cleanup;
- bounded queues;
- stale-result rejection;
- block-entity ownership/invalidation;
- frame-time protection;
- upload throttling;
- disconnect/world cleanup.

## 9. Validation

Compare every candidate change against the current `2.0.5` stable baseline.

Record:

- average FPS;
- 1% and 0.1% lows;
- worst-frame time;
- client chunk-to-visible latency;
- meshes/sec;
- queue wait/age;
- integration wait;
- upload wait;
- single-player generation behavior;
- multiplayer arrival-to-visible behavior.

A change is successful only if chunk throughput improves without a meaningful regression in frame pacing or correctness.
