# Code-review notes

This is a baseline review of the current working tree, not a review of one isolated feature diff. The working tree has many modified/untracked files, so conclusions are intentionally focused on the important runtime and architecture files.

## Critical

- **`src/main/java/destiny/renderer/compat/WorkAllotment.java:226-241`** — `TERRAIN_PIPELINE_PORTED` is derived directly from the user’s `experimentalTerrainPipeline` setting, but `ownsTerrain()` immediately returns false while that flag is false. Enabling the config cannot make the pipeline actually safe or ported; it only creates an ambiguous “experimental” state. Keep the ported capability as an explicit implementation gate, separate from user preference, and add a test for each state. Confidence: 9/10.

- **`src/main/java/caesium/integration/CaesiumIntegration.java:44,69`** — the engine starts and is announced as active, but `dormant()` stops both frame execution and section extraction when the graph has no passes. This makes the integration path operationally inert until a pass is registered. Either register the intended production pass during startup or label/guard the engine as prototype and expose a clear fallback. Confidence: 10/10.

## Suggestions

- **`README.md:1,106,168` vs `docs/ARCHITECTURE.md:13`** — public documentation describes a completed GPU-driven terrain renderer and v2.0.1 benchmarks while the architecture document and runtime gates describe vanilla terrain plus optimization passes. This is a contract/documentation mismatch that will mislead the next model and users. Publish one capability matrix and update version/path claims from reproducible current runs. Confidence: 10/10.

- **`src/main/java/destiny/renderer/DestinyRenderer.java:251-256`** — `CaesiumRenderBackendAdapter` is created after `CaesiumIntegration.start()` regardless of whether the engine is dormant, so debug/UI consumers can observe a backend that cannot render a frame. Make adapter availability reflect a registered/usable pass, or expose explicit states (`STARTED`, `DORMANT`, `FAILED`). Confidence: 8/10.

- **`src/main/resources/caesium.mixins.json:2`** — `compatibilityLevel` is `JAVA_22` while the build and mod metadata require Java 25. This may be intentional because mixin compatibility levels are bounded differently from compiler release, but it needs verification against the exact Mixin version and Minecraft runtime. Align it if supported, otherwise document why it remains 22. Confidence: 7/10.

## Not verified

- No clean build or Minecraft client run was performed in this pass.
- The full set of modified/untracked files was not reviewed line-by-line; only key docs, metadata, entrypoint, integration, ownership, render hook, particle hook, and build files were read.
- Vulkan/OpenGL correctness, mixin application at runtime, and visual parity require integration execution on the target client/GPU.
