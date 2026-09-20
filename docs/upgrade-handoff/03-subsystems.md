# Subsystem map

## Integration layer — `destiny.renderer`

- `DestinyRenderer`: lifecycle coordinator and static access point for mixins.
- `mixin/`: hooks into Minecraft client render, chunk, entity, particle, HUD, animation, and screen classes.
- `config/`, `gui/`, `hud/`, `hardware/`, `jvm/`: user-facing controls and diagnostics.
- `compat/`: capability ownership and resource sharing with other mods.
- `chunk/`: meshing data, deferred rebuilds, translucency sorting, LOD, and worker scheduling.
- `render/`: adapters and optimization renderers; `CaesiumRenderBackendAdapter` bridges to the engine.

## Engine layer — `caesium.engine`

- `world/`: section storage, scene state, deltas, and mesh/world representation.
- `graph/`: passes, resources, scheduling, and graph compilation.
- `scheduler/`: frame input, budgets, work stealing, and explosion responses.
- `backend/`: backend-neutral GPU interfaces plus OpenGL/Vulkan implementations.
- `device/`: frame context, camera matrices, and engine status.
- `debug/`: development passes excluded from the production JAR.

## Main boundaries to preserve

Minecraft/Yarn objects should enter through integration/extraction code. The engine should remain independent of Minecraft classes. Backend code should consume engine abstractions, not Fabric events. Ownership decisions belong in `WorkAllotment`, not scattered mod-ID checks.

## Tests and benchmarks

Tests are mostly standalone headless GL/Vulkan exit tests and JavaExec benchmarks, not full Minecraft integration tests. Important Gradle tasks are `build`, `headlessGlTest`, `headlessVulkanTest`, `headlessTerrainGlTest`, `headlessTerrainVulkanTest`, `benchmarkPipeline`, `benchmarkParticleStress`, and `benchmarkHardware`.
