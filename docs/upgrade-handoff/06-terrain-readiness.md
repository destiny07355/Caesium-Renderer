# Terrain backend readiness

Status: **BLOCKED from activation**. Keep `WorkAllotment.TERRAIN_PIPELINE_PORTED` false.

## What is verified

- Scene identity includes section X, Y, and Z. Vertical sections no longer overwrite one another.
- Scene publication rejects stale asynchronous mesh revisions.
- Camera publication reacts to both small position changes and look rotation.
- Section extraction allocates exact final arrays, emits no per-face objects, and culls internal faces from cached section opacity. A solid section now uses about 209 KiB of output instead of roughly 11.6 MiB of temporary capacity plus copies.
- A production `caesium.engine.render.TerrainPass` is registered by the integration and included in release jars.
- Render-graph shutdown owns pass cleanup; terrain GPU buffers, pipelines, and reusable staging state are released deterministically.
- `BakedSectionExtractor` now resolves deterministic multipart `BlockStateModel` output, applies vanilla neighbor-side culling, and preserves baked positions, atlas UVs, tint, light emission, normals, and SOLID/CUTOUT/TRANSLUCENT/TRIPWIRE separation in an engine-neutral contract.
- CPU benchmark on the current machine: 0.439 ms/section, 2,278 sections/s, and 52.4M frustum tests/s.
- Synthetic colored-cube terrain renders correctly in hidden OpenGL and Vulkan tests.

## Activation blockers

1. The production GPU pass still consumes the legacy position/color cube contract; it needs a textured/lighted layered vertex layout and shaders before `BakedSectionExtractor` can replace that path.
2. Fluids require `BlockRenderManager.renderFluid` capture and are not emitted by the baked block-model extractor.
3. The production pass issues one indexed draw per section. There is no implemented production MDI/indirect submission path, despite the old benchmark summary claiming one.
4. The pass targets engine-owned targets. It is not integrated into Minecraft 1.21.11's Blaze3D `RenderPipeline`/`RenderPass` terrain composition and depth targets.
5. Vanilla cancellation/fallback is absent. Caesium cannot safely suppress vanilla terrain until it can prove a complete frame and immediately fall back after initialization, allocation, context, or shader failure.
6. No parity suite covers fluids, block entities, damage overlays, Fabulous graphics, resource reload, dimension change, or shader/mod compatibility.

## Required implementation order

1. Define a Minecraft-complete immutable mesh contract split by render layer and backed by baked-model output.
2. Add atlas UV/sprite identity, tint, light, normals, and translucent-sort metadata.
3. Submit through supported Blaze3D pipeline/pass objects and composite into Minecraft's active color/depth targets.
4. Add frustum/occlusion draw-list generation, batched buffers, bounded uploads, and measured draw-call tests.
5. Add a guarded shadow mode that builds Caesium terrain while vanilla remains visible and compares section coverage/revisions.
6. Pass world join, F3+A, dimension change, unload, resource-pack, graphics-layer, and forced-backend-failure tests.
7. Only then set `TERRAIN_PIPELINE_PORTED` true; retain automatic delegation when another full renderer owns terrain.

Do not activate by merely registering the current debug pass or cancelling vanilla terrain. That path renders an incomplete colored-cube world and can corrupt Blaze3D state.
