# Runtime flow

## Startup

1. Fabric invokes `DestinyRenderer.onInitializeClient()`.
2. `RendererConfig.load()` reads and sanitizes `config/caesium.json`, with legacy migration from `destinyrenderer.json`.
3. Compatibility detection and `WorkAllotment.resolve()` choose an owner per capability.
4. Fabric keybind, tick, world-render, and lifecycle callbacks are registered.
5. On the first `GameRenderer.renderWorld` head, `onGLContextReady()` detects hardware and applies a preset only once.
6. Terrain resources are allocated only if `WorkAllotment.ownsTerrain()` is true. Entity batching is similarly ownership-gated.
7. `CaesiumIntegration.start()` selects OpenGL/Vulkan and starts the engine plus bounded section-extraction workers.

## Per-frame path

`GameRendererMixin` head: frame limiter and (only for owned terrain) mesher camera update.

`WorldRenderEvents.END_MAIN`: deferred rebuild queue processing and profiler timing.

`GameRendererMixin` tail: frame limiter completion, adaptive view-distance tick, then `DestinyRenderer.onFrame()`.

`onFrame()` calls `CaesiumIntegration.render()`. The integration pushes camera/options deltas, updates the published `RenderWorld`, and executes the scheduler only when a render graph pass exists.

## World reload and shutdown

- F3+A/world reload clears `DeferredRebuildQueue` and calls backend `reset()`.
- Client stopping calls `shutdownGL()` on the render lifecycle; the JVM shutdown hook only stops worker threads.
- Backend, entity renderer, frame limiter, arenas, and engine are shut down with guarded actions.

## Important invariants

- OpenGL/Vulkan resource creation and deletion must remain on the correct render/context thread.
- The terrain renderer must have exclusive ownership before it allocates or submits geometry.
- Section extraction is bounded and revision-checked; stale jobs must not publish meshes.
- A new engine pass must be registered before removing the dormant gate.
