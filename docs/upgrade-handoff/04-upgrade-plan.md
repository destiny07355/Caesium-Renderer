# Upgrade plan

## Phase 0 — establish truth

- Build from a clean checkout and record Java/Gradle/Fabric versions.
- Run the existing headless tests and benchmarks; distinguish “engine test passed” from “Minecraft terrain rendered.”
- Produce a capability matrix: active, dormant, delegated, or unverified.
- Reconcile README, PROJECT, CHANGELOG, and `docs/ARCHITECTURE.md` before changing architecture.

## Phase 1 — make the engine demonstrably useful

- Add a minimal registered render-graph pass that consumes a synthetic or extracted section mesh.
- Add a test proving the pass is registered and executed, not only that backend primitives work.
- Define ownership and fallback behavior for OpenGL, Vulkan, Sodium, Iris, and vanilla.
- Keep `windowPresent` off until presentation and state restoration are verified in a real client.

## Phase 2 — port terrain to 1.21.11’s Blaze3D contract

The architecture doc identifies the one-way door: vanilla now submits through `GpuDevice`/`RenderPass`, with no raw terrain MDI entry point. The correct migration is to model the custom pipeline using supported Blaze3D render pipeline/pass objects, or explicitly retain vanilla terrain and limit Caesium to adjacent optimizations. Do not re-enable raw GL terrain draws merely by flipping `experimentalTerrainPipeline`.

Required acceptance tests:

- world join, dimension change, F3+A, unload, and shutdown;
- opaque, cutout, translucent, block entities, particles, and shader-pack combinations;
- stale section revision rejection and bounded queue behavior;
- GPU context loss/failure fallback;
- visual comparison against vanilla in all three presets.

## Phase 3 — production hardening

- Replace static mutable global state with explicit lifecycle ownership where feasible.
- Add client integration tests for each high-risk mixin and config migration.
- Pin/document dependency compatibility and verify mixin targets against the exact Yarn/Minecraft version.
- Keep performance claims tied to reproducible commands, hardware, scene, sample size, and whether the path is actually active in-game.
