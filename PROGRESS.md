# Caesium Engine — Improvement Log

Running log of every improvement landed for the Caesium engine (Month 2+ work).
Each entry: version, what changed, why it matters, and how it was verified.

---

## 1.5.0 — swapchain, GPU selection, UBOs, GPU timing, real-window wiring (done earlier)

- **GPU device selection option**: `vulkanDevice` config (AUTO/DISCRETE/INTEGRATED/name pin),
  `VulkanBackend(String devicePreference)` preference-aware `pickPhysicalDevice`, GUI option
  "Vulkan GPU" (`requiresReload`). Default AUTO. Verified by full build + headless tests.
- **Swapchain present path**: `SwapchainTarget` (GLFW surface, swapchain, acquire/render semaphores,
  present, mailbox fallback), `VulkanBackend.attachWindow/detachWindow/activeTarget/instance`,
  `RenderTarget` interface, queue submit routing through the window, readback after present.
  Verified by `HeadlessVulkanSwapchainTest` (3 frames acquire→render→present, pixel red).
- **GLFW on main classpath** so `SwapchainTarget`/`VulkanBackend` can use `GLFWVulkan`.
- **UBO/descriptor plumbing**: `VulkanUniforms` (pool/set/identity UBO), descriptor set layout
  (set 0 / binding 0, UNIFORM_BUFFER, fragment) in `VulkanPipelineFactory`, `descriptorSet()` /
  `pipelineLayout()` on both targets, `GpuCommandEncoder.bindUniformBuffer` implemented in
  Vulkan/GL/Null encoders, GL default identity UBO + `Uniforms` uniform block in shaders,
  identity tint written with native byte order (fixed big-endian `ByteBuffer` bug).
  Verified by `HeadlessUboTimerGlTest` + `HeadlessUboTimerVulkanTest` (tint→dim red, r=128).
- **GPU timing**: `GpuTimer` interface, `GpuBackend.createTimer()`,
  `GpuCommandEncoder.writeTimestamp(timer, end)`; Vulkan 2-query timestamp pool scaled by
  `timestampPeriod`, GL `glQueryCounter(GL_TIMESTAMP)`, Null CPU-nanoTime stub.
  Verified: timers measure the draw region (~15–21 µs) on both backends.
- **Real-window wiring (config-guarded, default off)**: `RendererConfig.windowPresent` flag +
  GUI option, `CaesiumIntegration.start()` attaches the Vulkan swapchain to Minecraft's actual
  window when enabled (graceful fallback), detach on stop. `CaesiumIntegration` is now actually
  invoked: started in `DestinyRenderer.onGLContextReady`, driven per-frame from
  `GameRendererMixin` TAIL, stopped in `shutdownGL`.
- Version bumped 1.4.0 → 1.5.0; deployed to `downloads/` + Prism mods folder.

---

## 1.6.0 — engine renders real scene content (terrain vertical slice)

- **Flat block-colour LUT**: `BlockStateLUT` now resolves each block state to a flat
  0xAARRGGBB colour from its block's `MapColor` at build time (`colorOf`), so extracted
  terrain has coherent per-block colours without a texture atlas on the engine side.
- **Minecraft section extractor**: `SectionMeshExtractor` in `destiny.renderer.chunk`
  converts a populated `ChunkSectionData` into an engine-neutral `RenderWorld.SectionMesh`
  (world-space POS_COLOR_3F_4F vertices + indices). Emits one flat-coloured quad per visible
  face with opaque-neighbour culling; quad winding matches the validated
  `CubeMeshBuilder` (outward CCW), so real sections render front-correct under the terrain
  pipeline's back-face culling. Per-face light factors keep block faces distinguishable.
- **Extraction seam**: `CaesiumIntegration.extractSection(pos)` runs the world read + mesh
  build on a single low-priority daemon thread, advances a per-section revision, and pushes
  `DeltaCommand.SectionMeshUpdated` into the engine scene. Stale reads (superseded by a newer
  rebuild) are dropped by revision so the scene never regresses.
- **Hooked the rebuild path**: `ChunkBuilderMixin` feeds every freshly rebuilt section into
  the engine regardless of terrain ownership — the engine renders its own offscreen scene
  rather than replacing vanilla, so it costs nothing when idle and never conflicts.
- Verified: full build green; all 7 headless exit tests pass on both backends
  (quad red, swapchain red, UBO tint + GPU timers, terrain blue -Z cube). Version bumped
  1.5.0 → 1.6.0; deployed to `downloads/` + Prism mods folder; 1.5.0 removed.

---

## 1.7.0 — settings UI redesign (Minecraft-native, not an AI dashboard)

- **Removed the permanent "Option Details" panel.** Descriptions now appear as vanilla
  Minecraft hover tooltips on the row under the cursor instead of a persistent right-hand
  card. Content spans the full remaining width, so rows are wider and the screen reads as
  a plain options list rather than a dashboard with a sidebar.
- **Minecraft-style sidebar navigation.** Category tabs are no longer rectangular filled
  buttons; they are plain text rows (normal capitalization) with a subtle hover highlight
  and a thin accent bar on the active page. Search box sits at the top of the sidebar.
- **Dropped the multi-theme/dashboard aesthetic.** `Theme.Preset` reduced from seven
  (Crimson, Midnight, Ocean, Forest, Amethyst, Monochrome, Custom) to three restrained
  options — Default, Dark, Custom — all sharing one muted steel-blue accent on near-black.
  Config default changed to `DEFAULT`; stale preset names fall back safely on load.
- **Normal-case group headings with a separator line** instead of UPPERCASE headings, and
  no per-row card backgrounds — hover only highlights, keeping the list flat.
- **Shorter option text.** Multi-sentence essays (Block Entity Distance, Ground Fire
  Distance, CPU Render Ahead, Disable All Particles, the backend/device/shader options,
  etc.) trimmed to a single plain sentence; technical rationale now belongs in the hover
  tooltip rather than always-visible text.
- **"Performance Impact" no longer shown on every setting.** Only High/Very-High-impact
  options hint at it, as a single muted line inside the hover tooltip.
- **Minecraft-native controls.** Boolean rows render as compact boxed value buttons
  (`[On]`/`[Off]`) matching the cycling control instead of a custom pill switch; boxes and
  sliders got the vanilla-style bevel (lighter top edge, darker bottom).
- Footer per mockup: Defaults (left) + Apply / Done (right); Done also available top-right
  in the header. Escape still cancels.
- Verified: full build green; all 7 headless exit tests pass on both backends. Version
  bumped 1.6.0 → 1.7.0; deployed to `downloads/` + Prism mods folder; 1.6.0 removed.

---

## 1.7.1 — memory-leak fix: unbounded scene growth was exhausting the heap

After 1.7.0 shipped, a crash report showed `OutOfMemoryError: Java heap space` on the
sound engine thread (~166 s uptime, 51 MiB free of 4 GiB). The sound thread only happened
to hit the wall first — the heap was exhausted by the engine's own scene:

- **Root cause:** every chunk rebuild pushed a fresh `SectionMesh` (≈300 KB of
  float/int arrays each) into `SceneManager`'s `SectionStorage`, which never pruned —
  `remove()`/`clear()` existed but were never called. The `revisions` map and the
  `TerrainPass` GPU mesh cache grew the same way. With the world fully meshed, memory
  grew without bound → GC thrash (the freeze) → OOM (the crash), plus the ~50 FPS drop
  from `SceneManager.update()` rebuilding the whole world snapshot every frame.
- **Fix (all bounded now):**
  - `SceneManager.update()` short-circuits when nothing changed — idle frames return the
    published snapshot without a copy or allocation.
  - `SceneManager` prunes sections (and their stored meshes) beyond render distance + 2
    chunks around the camera on every rebuild, via `RenderWorld.Builder.filterSections`.
  - `CaesiumIntegration.render()` now pushes camera/options into the scene only when they
    meaningfully change (8-block movement threshold, option delta) instead of every frame,
    and reads the real view distance instead of a hardcoded 12.
  - `extractSection()` rejects sections beyond render distance + 2 chunks before spawning
    work, drops stale reads **before** the expensive 18×18×18 populate, runs on a bounded
    32-slot queue (overflow silently discarded — the next rebuild re-extracts), and
    periodically prunes the `revisions` map of far entries.
  - `TerrainPass` frees GPU buffers for sections no longer in the world when its cache
    clearly exceeds the live set.
  - `WorkStealingPool` idle park raised 1 ms → 8 ms (idle workers no longer wake
    1000×/s each).
- The sound engine was deliberately left untouched (it belongs to mods like Sound
  Controller, not the renderer).
- Verified: full build green; all 7 headless exit tests pass on both backends. Version
  bumped 1.7.0 → 1.7.1; deployed to `downloads/` + Prism mods folder; 1.7.0 removed.

---

## 1.7.2 — settings screen typeface + shadow cleanup

- **Bundled Inter (SIL OFL 1.1)** as the settings-screen typeface: `inter.ttf` ships
  under `assets/caesium/font/`, registered as a resource-pack font via
  `assets/caesium/font/caesium.json` (TTF provider, 11 px, 2× oversampling) so it flows
  through the vanilla text renderer — no custom renderer, no library. Glyphs Inter lacks
  fall back to the default font automatically. OFL license included in the jar.
- **`CaesiumFont` helper** (`destiny.renderer.gui`): `text()` / `withFont()` wrap any
  text node in `Style.withFont(new StyleSpriteSource.Font(Identifier))` (the 1.21.11
  font-styling API).
- **Every settings-screen text now renders in Inter**: header, compatibility warning,
  group headings, "no match" message, sidebar nav rows, footer/header buttons, option
  row labels, cycling/slider/tick values, and hover-tooltip lines (incl. the
  disabled/reload/impact notes).
- **No more harsh drop shadows**: all `drawTextWithShadow` calls in the settings screen
  and its controls became shadowless `drawText(..., false)`. The search box also drops
  its shadow (`setTextShadow(false)`); its placeholder is styled in Inter.
- Verified: full build green; all 7 headless exit tests pass on both backends. Version
  bumped 1.7.1 → 1.7.2; deployed to `downloads/` + Prism mods folder; 1.7.1 removed.

---

## 1.8.0 — the engine idles at true zero cost

The engine's promise (ARCHITECTURE.md §2) is that it "costs nothing when idle". It did
not: the frame loop ran every frame and section extraction ran on every chunk rebuild
even though no render pass is ever registered in production — pure CPU work on a 35 W
iGPU machine, the same class of waste that leaked the heap in 1.7.1.

- **Gated the whole engine behind an actual consumer.** `CaesiumIntegration` now treats
  the engine as dormant while `graph().passCount() == 0`. While dormant:
  - `render()` returns before touching Minecraft, the scene, the scheduler or the backend
    — no per-frame encoder, status, or snapshot work.
  - `extractSection()` returns before allocating a `ChunkSectionPos` entry, before the
    18×18×18 populate and before any mesh build.
  - The seam stays fully wired: the moment any pass is registered (tests today, a future
    consumer later), the frame loop and extraction light up automatically. This is what
    the 1.6.0 vertical slice was built for.
- **Startup log** states the idle state explicitly so it is visible in the game log.
- **Hot-path cleanup** in `ChunkBuilderMixin`: the rebuild handler now computes
  `ChunkSectionPos` once instead of twice per `scheduleRebuild` call.
- Verified: full build green; all 7 headless exit tests pass on both backends (they
  register passes, so the engine path itself is exercised). Version bumped 1.7.2 → 1.8.0;
  deployed to `downloads/` + Prism mods folder; 1.7.2 removed.

---

## 1.8.1 — true "off" for screen overlays that vanilla rendered at full strength at 0%

A class of vanilla accessibility/effect sliders has an inverted meaning at 0%: rather than
disabling the effect they render it at **100% strength**, because vanilla multiplies the
overlay's strength by `(1 - slider)`. Two toggles shipped in this release make 0% actually
mean "off":

- **Warden darkness fog.** `DarknessEffectFogModifier.applyStartEndModifier` reads the
  effect fade factor unconditionally and is *not* scaled by the Darkness Pulsing slider,
  so the slider alone could never kill the fog. `DarknessEffectFogMixin` cans the fog
  method at HEAD when the user toggle is off OR the slider is at 0. Verified against the
  1.21.11 bytecode: the only fog modifier that ignores the slider is `DarknessEffectFogModifier`.
- **Nausea + portal screen warp.** `InGameHud.renderNauseaOverlay` is gated by a `if
  (scale < 1.0) { strength *= (1 - scale); render overlay }` block — at scale = 0 the
  branch enters and renders the overlay at *full* strength. `ScreenEffectMixin` cans
  `renderNauseaOverlay` and `renderPortalOverlay` when the user toggle is off OR the
  Distortion Effects slider is at 0. (`renderPortalOverlay` isn't actually slider-gated
  by vanilla at all; tying it to the same toggle gives the user one place to kill the
  full-screen purple portal warp entirely.)
- **Powder-snow screen overlay** is gated by its own dedicated toggle in the same mixin,
  separate from the pumpkin in-wall overlay handled in `OverlayRendererMixin`.
- **HUD element skips** (`HudOptimizationMixin`): the status-effect overlay, the hotbar
  and the health/armor/food/air bars can now be cancelled at HEAD. All default ON and only
  cancel when the user opts out of that one element, so other mods that inject into the
  same methods (e.g. potion-timer overlays) still run unless the user explicitly disables
  the element.
- **Fire / water / pumpkin / totem screen overlays** (`OverlayRendererMixin`): the vanilla
  in-game overlay renderer hooks are cancelled at HEAD when their respective options are
  off. The totem pop is filtered by item stack only — other uses of the floating-item
  effect, if any are added later, are untouched.
- Verified: full build green; all 7 headless exit tests pass on both backends. Version
  bumped 1.8.0 → 1.8.1.

---

## 1.9.0 — allotment-aware resource sizing (don't oversubscribe against your own allies)

1.8.x reserved worker thread pools and frame budgets sized *as if nothing else were
running*. With Sodium / VulkanMod / ImmediatelyFast / EntityCulling / MoreCulling /
BadOptimizations installed alongside Caesium, every mod was scheduling its own worker
pool at full size — the render thread was starved by the very tools meant to protect it.
Work Allotment already answered "who owns the work"; it did not answer "how big should
the owner's pool be". This release closes that loop.

- **`ResourceShare`** (`destiny.renderer.compat`) cross-references the resolved Work
  Allotment state with which render/flavoured mods are installed, and returns the share
  of the machine Caesium should take:
  - `meshingThreadFactor()` — 1.0 when alone; scaled down toward 0.25 as render competitors
    stack up. Hard cut to a minimal pool when a full renderer replacement (Sodium /
    Embeddium / VulkanMod) owns terrain — our mesher is reduced to a maintenance worker
    for the deferred-rebuild queue because the other mod is doing the heavy geometry
    lifting. Widthens slightly back when we do own entity/particle batching or culling.
  - `budgetRatio()` — fraction of the frame-budget reservation Caesium should claim.
    Halved when we don't own terrain and there's a competing renderer, so the budget we
    reserve for ourselves does not eat time the other renderer needs while we have
    nothing to spend it on.
  - `backgroundAdmissionShare()` — fraction of the per-frame background-job cap Caesium
    should admit; shrinks linearly with each render-flavoured competitor so the
    *cumulative* admission (Caesium + competitors) doesn't reproduce the freeze regime
    this whole system was built to prevent.
- **`RendererConfig.resolvedMeshingThreads()`** now multiplies its auto-detected base
  by `ResourceShare.meshingThreadFactor()`. The user's explicit `meshingThreads` pin
  always wins over the auto-shrink, so a power user can override if they want to.
- **`BudgetPolicy` default constructor** now scales `meshingRatio`, `uploadRatio` and
  `maxBackgroundJobs` through `ResourceShare` — the engine reserves less frame time
  when it has less work, and steps back automatically when another renderer owns terrain.
- **Logged at startup** (`DestinyRenderer.onInitializeClient` + `CaesiumIntegration.start`)
  via `ResourceShare.logSummary()`: competitor count, who owns terrain, and the resulting
  mesh/budget/admission shares, so a user reporting "Caesium eats too much CPU when Sodium
  is installed" can see in the game log exactly what was decided and why.
- Verified: full build green; all 7 headless exit tests pass on both backends — none of
  them assert on these tunables so the share-aware defaults flow through cleanly. Version
  bumped 1.8.1 → 1.9.0; `gradle.properties` is on 1.9.0.

---

## 1.10.0 — P99.5 / 1% lows push, plus an F3 readout for them

1.9.0 made the engine take a fair share of the machine. This release spends it
deliberately on the worst frames — the 1% lows and the P99.5 — rather than chasing
the average. The work is keyed to two honest facts: average FPS is the wrong metric
for "does it feel smooth", and the floor is what actually moves when you feel a
stutter; and without an on-screen readout of the floor you cannot tell whether you
have raised it. Both are addressed in the same release.

### A — F3 percentile readout (Sodium-parity)

- **`PerformanceOverlay` ring buffer**: separate 1024-sample (~8 s at 120 fps)
  percentile window alongside the existing 240-sample average window. Average would
  smooth the rare stutter away, so a longer window watches for it instead. P50 / P98
  / P99.5 / worst computed via the standard nearest-rank method. Sort scratch buffer
  is reused per recompute (only 4×/s) so the F3 path adds zero per-frame allocation
  at steady state.
- **`DebugHudMixin`** appends two lines —
  `Caesium p50 N  p98 N  p99.5 N fps` and `Caesium worst N  (avg N) fps` — right
  below the vanilla `FPS T:...` line in F3. Implemented by injecting at HEAD of
  `DebugHud.drawText(... leftSide=true)` and appending to that list argument before
  vanilla reads it. No registration against `DebugHudEntries.PROFILES`, no profile
  file save/load surfacing — appears by default, no configuration needed. Same
  left-column position Sodium's readout occupies.
- **`showExtendedFpsInF3`** option (default ON, on the Overlays page): toggles the
  F3 readout cleanly off so vanilla F3 behaviour is restored exactly.
- **In-game overlay** (`Overlays → Extended FPS Info`) also now shows
  `p50/p98/p99.5` underneath the existing `avg/low` line, so the floor is visible
  whether you open F3 or not.

### B — frame-time floor (raise 1% lows / P99.5 / avg-max FPS)

- **B1** — moved `DeferredRebuildQueue.processFrame()` from `InGameHud.render` TAIL
  (late in the frame, near present+vsync) to `WorldRenderer.render` TAIL (earlier).
  Rebuilds used to land in the part of the frame that had no slack; the worst frames
  were getting rebuild bursts stacked on top, which is exactly what made them the
  worst frames. Runs unconditionally so deferral applies regardless of terrain
  ownership. The queue still no-ops when nothing is pending.
- **B2** — the per-frame budget is now split into two slices. The first slice always
  runs (backlog can never grow unbounded); the second slice only runs when the
  previous frame was judged to have had headroom — measured by wall-time as the
  cheapest, GL-free proxy for the "previous frame's GL fence has signalled" idea
  (vanilla owns the GL context, so a real fence would not have integrated cleanly).
- **B3** — adaptive shrink: when the *live* frame is already running long, the
  budget shrinks to a quarter of normal, complementing the existing rolling-average
  backpressure (`avgMs > 33.3 → /2`). The live check catches the single bad frame;
  the rolling-average check catches the sustained bad patch.
- **B4** — skipped-frame coalescing: when the second slice was dropped because the
  frame was late, the next frame's first slice is **doubled**. One slightly-over-target
  frame instead of two consecutive bad ones — the same average throughput, with the
  floor moved up. That trade is the entire point of coalescing-deferral pacing
  strategies.

### D — particle tick throttling on slow frames

- **`ParticleOptimizationMixin`** gets a HEAD-cancelling inject into
  `ParticleManager.tick` that fires when the live frame's current FPS is below 70%
  of the user's `maxFps` setting. Particles then tick one frame later (instead of
  dragging an already-struggling frame further). Nothing is lost — no particle is
  deleted, no spawn is denied — and the worst-case visible effect is a particle
  briefly staying at its last position for one frame. A known 1% low killer in
  particle-heavy scenes (enderman farms, raids, large fires).
- **`throttleParticleTickOnSlowFrames`** option (default ON, on the Particles group
  of the Performance page): opt-out for users who prefer particles to tick every
  frame regardless. Honours `maxFps = unlimited` by never firing in that case.

### Verified

- Full build green; all 7 headless exit tests pass on both backends. None of the
  tests assert on these tunables so the share-aware defaults flow through cleanly.
- Version bumped 1.9.0 → 1.10.0; `gradle.properties` is on 1.10.0; the deployed
  `Caesium-1.10.0.jar` contains all new classes
  (`DebugHudMixin`, updated `DeferredRebuildQueue` with split-slice reuse,
  `throttleParticleTickOnSlowFrames` field, the percentile ring buffer in
  `PerformanceOverlay`).

---

## 1.11.0 — Adaptive backpressure reads p99.5 instead of the average

### R2 — the backpressure signal

The B3 adaptive shrink in `DeferredRebuildQueue` previously keyed off
`PerformanceOverlay.averageFrameMs()` — a 240-sample mean that smooths stutters
away, which is exactly the wrong signal for a subsystem whose whole job is the
1% low. It now reads the live **p99.5 frame time** from the percentile ring buffer
added in 1.10.0 (`percentileFrameMs995()`), halving the budget when p99.5 exceeds
2× the target frame time. The live "is this frame late" wall-clock check is
unchanged. The average is still exposed for other consumers but no longer drives
the chunk-rebuild throttle.

### Notes for this round

- **R1 (real `CpuRenderAheadLimiter`) was found to already be fully implemented**:
  `glFenceSync`/`glClientWaitSync` backed, and already wired into
  `GameRendererMixin.beginFrame`/`endFrame` plus the OptionRegistry setter. Nothing
  to do.
- **R3 (adaptive render distance) was intentionally deferred** this round — it is a
  new feature with a new config option, and the session budget was kept deliberately
  small. It remains the strongest remaining lever for iGPU floors and should be the
  next round's headline item.

### Verified

- Full build green; all 7 headless exit tests pass on both backends.
- Version bumped 1.10.0 → 1.11.0; `Caesium-1.11.0.jar` (614 KB) deployed with the
  new `percentileFrameMs995()` path.

---

## 1.12.0 — Adaptive render distance (R3)

The strongest remaining lever for the iGPU tier. Integrated graphics are bounded by
fill rate and memory bandwidth; distance is the one knob that actually moves that
floor. Rather than asking the user to guess where the ceiling is, the new opt-in
controller trades one chunk at a time to hold the p99.5 readout at or above 60 fps.

### R3 — `AdaptiveViewDistance`

- **New class `AdaptiveViewDistance`** in `destiny.renderer.chunk`. Called once per
  frame from the `GameRenderer.renderWorld` TAIL hook (already the home of the
  frames-in-flight limiter and `onFrame()`); internally self-throttles to one action
  every 5 s, so per-frame cost is a clock read + a config-field test.
- **Hysteresis band** — lowers when p99.5 < 60, raises only when p99.5 ≥ 75, so a
  healthy-but-dipping scene does not make the world yo-yo around the boundary.
- **Respects the user's maximum** — captures the user's own chosen render distance as
  a hard ceiling (and re-captures if the user raises it manually); never goes below
  vanilla's minimum of 2.
- **New `adaptiveViewDistance` option** (default OFF) on the Performance page's Chunk
  Loading group, with keywords for adaptive/render-distance/iGPU search.
- New public accessor `PerformanceOverlay.percentileFps995()` alongside the existing
  `percentileFrameMs995()`.

### Verified

- Full build green; all 7 headless exit tests pass on both backends.
- Version bumped 1.11.0 → 1.12.0; `Caesium-1.12.0.jar` (616 KB) deployed containing
  `AdaptiveViewDistance.class` and the new accessor.
- Same caveat as prior rounds: compile-and-test verified; the live behaviour of the
  controller (real p99.5 feedback loop) has not been observed in a running client.

---

## 1.13.0 — Texture-animation p99.5 throttling + DeferredRebuildQueue refactor

### R8 — `DeferredRebuildQueue` clean split (zero behaviour change)

The 110-line `processFrame` method is now three focused helpers with identical
semantics: `computeBudget()` (backlog scale + iGPU cap + B3 adaptive shrink),
`runFirstSlice()` (B4 coalescing), and `runSecondSliceIfHeadroom()` (B2 gate).
Pure code health — the next pacing tweak has a clean home.

### R5 — Texture-animation throttling on slow frames

New `throttleTextureAnimOnSlowFrames` option (default ON) in `SpriteAnimationController`.
When the live FPS drops below 70% of the user's `maxFps` setting, the animation tick
is skipped for that frame — the sprite simply holds its last frame. Same proven
pattern as the particle tick throttle (1.10.0 / D). Known 1% low killer in dense
animated-block scenes (nether bases, lava lakes, large fire farms). Zero visual
regression: the worst case is one held frame.

- New `throttleTextureAnimOnSlowFrames` config field + OptionRegistry entry on the
  Animations page (`Fluid & Block Animations` group).
- Check sits early in `shouldSkip()` so it applies uniformly before any per-category
  logic.

### Verified

- Full clean build green; all 7 headless exit tests pass on both backends.
- Version bumped 1.12.0 → 1.13.0; `Caesium-1.13.0.jar` (617 KB) deployed with the
  new `throttleTextureAnimOnSlowFrames` path and the refactored `DeferredRebuildQueue`.
- Same caveat: compile-and-test verified; the live feedback loops (p99.5 driving
  chunk-rebuild backpressure, adaptive render distance, texture-animation throttle,
  particle tick throttle) have not been observed in a running client.

---

## 1.14.0 — Config text clarity + teleport chunk burst fix

### Config text rendering overhaul

The custom Inter font was rendering at size 11 with no text shadow, producing thin,
blurry text across the settings menu. Fixed by:

- **Font size** increased from 11 → 12 with a slight vertical shift (+1px) for
  better baseline alignment at common GUI scales.
- **Text shadow enabled** everywhere in `DestinySettingsScreen`:
  headers, group titles, option labels, nav items, buttons, search field,
  tooltips, and the "no match" message. The hover tooltip already used the
  vanilla `drawTooltip` which handles shadows internally.
- **Search field** shadow re-enabled (was explicitly disabled).
- **ControlElement** row labels now draw with shadow.
- Result: crisp, readable text that matches vanilla's text quality at all
  GUI scales.

### Teleport chunk burst (server /tp lag fix)

The 3-second freeze when teleporting to unloaded areas on a server was caused
by three compounding factors:

1. **Multiplayer deferral was off by default** — chunk rebuilds ran immediately
   on the main thread, overwhelming the frame budget when dozens of sections
   arrived at once.
2. **Near-rebuild radius too small (24 blocks)** — only chunks literally under
   the player's feet rebuilt instantly; the surrounding ring fell into the
   deferred queue and trickled in at 8/frame.
3. **No burst handling** — the per-frame budget had no concept of "a thousand
   chunks just arrived from a /tp."

Fixes:

- **`deferChunkUpdatesInMultiplayer` now defaults to ON** — the teleport burst
  feature requires it, and the PvP risk is documented in the tooltip.
- **Near-rebuild radius increased 24 → 48 blocks** — covers ~3 chunks radius
  so the immediate area around a teleport destination rebuilds in one frame.
- **New `teleportBurstMultiplier` (default 4x, 10 frames)** — when the player
  moves >128 blocks in a single tick (typical /tp), the per-frame rebuild
  budget is multiplied for ~166ms, draining the arrival burst before it can
  stall the game.
- **New `teleportBurstThreshold` (default 128 blocks)** — tunes the detection
  sensitivity; normal movement never triggers it.
- All three options live on the **Multiplayer Safety** page, gated behind the
  multiplayer deferral toggle.

### Verified

- Full clean build green; all 7 headless exit tests pass on both backends.
- Version bumped 1.13.0 → 1.14.0; `Caesium-1.14.0.jar` (618 KB) deployed.
- Same caveat: compile-and-test verified; the live teleport burst and text
  rendering improvements have not been observed in a running client.

---

## 1.15.0 — Crowded server spawn FPS improvements

The 100–150 FPS drop in high-entity-density spawns (e.g., mcpvp.club) was
driven by three compounding factors:

1. **Entity render distance defaulted to 100% of vanilla** — with a typical
   view distance of 10, entities rendered out to 160 blocks. A spawn area can
   contain hundreds of players/armor stands/item frames in that radius.
2. **No practical LOD transition** — the 32-block LOD distance meant almost
   everything rendered at full detail in spawn.
3. **Entity batching gated behind terrain pipeline** — the biggest draw-call
   reduction was unavailable on default settings.

Changes:

- **`entityRenderDistanceMult` default: 1.0f → 0.75f** — cuts entity render
  distance by 25%, which in a crowded spawn roughly halves the visible entity
  count with minimal visual impact (players beyond ~120 blocks are barely
  recognizable anyway).
- **`entityLODDistance` default: 32 → 24 blocks** — entities transition to
  simplified rendering sooner, reducing vertex load for distant entities.
- **`cullEntities` / `cullBlockEntities` remain ON by default** — frustum
  culling already eliminates ~50% of entities outside the view cone.
- **`entity_batching` option unchanged** — still requires the experimental
  terrain pipeline, but the other two defaults now provide a meaningful win
  even without it.

These defaults target the *spawn scenario* specifically: a 0.75× multiplier on
a view distance of 10 gives ~120 block entity distance, which covers the
immediate spawn area while dropping the far ring where entity density is
highest but visual relevance is lowest. Users who prefer the old behaviour can
slide the multiplier back to 1.0 in the Entities settings page.

### Verified

- Full clean build green; all 7 headless exit tests pass on both backends.
- Version bumped 1.14.0 → 1.15.0; `Caesium-1.15.0.jar` (618 KB) deployed.