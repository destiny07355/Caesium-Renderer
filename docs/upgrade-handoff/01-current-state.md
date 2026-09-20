# Current state

## What is active

`DestinyRenderer.onInitializeClient()` loads configuration, resolves compatibility ownership, registers FRAPI/settings/keybind/event hooks, starts Discord presence, and installs lifecycle callbacks. `GameRendererMixin` completes deferred initialization once a render context exists and calls the frame hook at the end of `renderWorld`.

The active feature set is primarily optimization around vanilla rendering:

- particle admission/filtering and population limits;
- entity/block-entity visibility controls;
- chunk rebuild throttling and deferred rebuild scheduling;
- sprite animation controls;
- frame-ahead limiting and inactivity FPS behavior;
- configuration GUI, hardware presets, telemetry, and compatibility ownership.

These are implemented through the mixin list in `src/main/resources/caesium.mixins.json`.

## What is not active

`docs/ARCHITECTURE.md` explicitly states: “It is not currently a terrain renderer.” `WorkAllotment.ownsTerrain()` returns false while `TERRAIN_PIPELINE_PORTED` is false (`src/main/java/destiny/renderer/compat/WorkAllotment.java:226`). Therefore the custom meshing/terrain allocation branch in `DestinyRenderer.onGLContextReady()` is skipped.

The standalone `caesium.engine` can initialize, but `CaesiumIntegration.dormant()` returns true when `engine.graph().passCount() == 0` (`src/main/java/caesium/integration/CaesiumIntegration.java:44`). In that state `render()` and section extraction return without doing work. Treat the engine/backend code as a prototype foundation until a real game-facing render pass consumes the scene.

## Documentation drift

README claims and benchmark tables describe a completed GPU-driven renderer and cite Caesium v2.0.1, while the code and architecture document describe a delegated/inactive terrain path and a v2.0.5 fallback. Upgrade work should first establish one authoritative capability matrix and update README/PROJECT/CHANGELOG accordingly.
