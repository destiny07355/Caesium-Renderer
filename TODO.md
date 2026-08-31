# DestinyRenderer / Caesium — Active Work Checklist

This file is the persisted, power-cut-safe progress list for the current session.
When the next model starts work it should read this file first to see exactly where
the previous session left off. Update statuses in real time.

Session: "Crowded server spawn FPS improvements" (1.15.0)

---

## Done — 1.15.0 (this session)

- [x] **Entity render distance multiplier default: 1.0f → 0.75f** — cuts entity
      render distance by 25%, roughly halving visible entity count in crowded
      spawns with minimal visual impact.
- [x] **Entity LOD distance default: 32 → 24 blocks** — entities transition to
      simplified rendering sooner, reducing vertex load for distant entities.
- [x] **Entity culling (frustum) remains ON by default** — eliminates ~50% of
      entities outside view cone.
- [x] Build green (`gradlew clean build test`); `Caesium-1.15.0.jar` (618 KB) deployed.
- [x] `gradle.properties` and startup banner on 1.15.0.
- [x] `PROGRESS.md` updated with 1.15.0 entry; `TODO.md` final status.

---

## Deferred (explicitly NOT done — recorded for the next session)

- [ ] **R6 — Adaptive entity/block-entity cull distance tied to p99.5** (adaptive mode,
      off by default). Shrinks entity render distance in steps when p99.5 is under target.
      Helps crowded-server spikes. Off by default, no regression when off.
- [ ] **R7 — Live `ResourceShare` re-scale** on override change. Right now changing a
      Work Allotment override mid-session doesn't shrink the meshing pool until restart.
      Usability polish, not perf.
- [ ] **C — GPU pre-pass elimination** (held back since 1.10.0 planning). Experimental
      opt-in, mid/high-end only, documented z-fighting trade-off.
- [ ] **Entity batching accessibility** — currently gated behind terrain pipeline.
      Could add a fallback mode for non-experimental setups.
- [ ] Iris shaderpack-path detection (open gap, noted in ARCHITECTURE.md).

---

## Verifiable gaps (do these before anything new)

- [ ] **Live in-game verification of everything built since 1.10.0.** The percentile
      F3 readout, B2/B3/B4 pacing, particle tick throttle, p99.5 backpressure,
      adaptive render distance, texture-animation throttle, teleport burst, and
      entity distance defaults have all only been compile-tested. Launching and
      watching the F3 readout respond is the single most valuable "improvement"
      right now.
- [ ] **Confirm `CpuRenderAheadLimiter` behaves.** It's implemented and wired, but a
      wedged/driver-unsupported `glClientWaitSync` hang is the kind of thing that
      only shows in-game.