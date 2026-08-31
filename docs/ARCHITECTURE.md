# DestinyRenderer — Architecture

Minecraft 1.21.11 · Fabric · Yarn mappings

---

## 1. What this mod is

An optimization and configuration mod. It reduces redundant rendering work in vanilla's
pipeline and exposes a large, fully-functional settings interface covering the vanilla
video options plus the feature set of Sodium, Sodium Extra and Reese's Sodium Options.

**It is not currently a terrain renderer.** The reason is documented in §2, because it is
the single most important architectural fact about this codebase.

---

## 2. The blaze3d constraint (read this first)

The project was originally written to replace vanilla terrain rendering with a GPU-driven
`glMultiDrawElementsIndirect` pipeline. **That approach is not viable on 1.21.11.**

Verified by decompiling the shipped classes:

```java
// net.minecraft.client.render.WorldRenderer
private SectionRenderState renderBlockLayers(Matrix4fc, double, double, double)

// com.mojang.blaze3d.systems.RenderPass
void drawIndexed(int, int, int, int);
<T> void drawMultipleIndexed(Collection<RenderObject<T>>, GpuBuffer, IndexType, ...);
// ...no multi-draw-indirect entry point exists
```

Two consequences:

1. **No way to suppress the vanilla terrain draw cleanly.** `renderBlockLayers` returns a
   `SectionRenderState` record built from a `GpuTextureView` and an
   `EnumMap<BlockRenderLayer, List<RenderPass.RenderObject>>`. There is no `EMPTY`
   constant and no supported way to construct a valid empty instance.

2. **No MDI entry point.** `RenderPass` exposes only `drawIndexed` and
   `drawMultipleIndexed`. The indirect command buffer that the whole `MDIRenderBackend`
   design depends on cannot be bound. Issuing raw LWJGL calls alongside blaze3d corrupts
   its cached pipeline state and produces driver-level errors.

Replacing terrain on 1.21.11 therefore requires porting the entire geometry pipeline onto
blaze3d `RenderPipeline` objects — a rewrite, not a patch. This is what Sodium maintains
full time.

### How this is handled in code

`WorkAllotment.TERRAIN_PIPELINE_PORTED` is a compile-time `false`. `ownsTerrain()` returns
`false` unconditionally while it stays false, and every terrain code path is gated behind
it. The MDI backend, mesher, Hi-Z pyramid and compute cull shader all remain compiled and
reviewable rather than rotting behind a deleted branch — flip the constant once the port
exists.

Critically, this gate also means **none of those GPU resources are allocated**. Before this
was fixed the mod reserved roughly 192 MB of persistently mapped buffers for a pipeline
that never drew anything, which on integrated GPUs directly starved vanilla's renderer.

---

## 3. Work Allotment

The central compatibility mechanism. Rather than scattering `isModLoaded` checks across the
codebase, every subsystem asks one question: *do I own this job?*

```
Capability  — a unit of work (TERRAIN_RENDERING, ENTITY_CULLING, FRAME_THROTTLE, ...)
Provider    — a mod that can perform it (DESTINY, SODIUM, IMMEDIATELYFAST, ...)
WorkAllotment — resolves exactly one Provider per Capability at startup
```

Resolution order:
1. A user pin from the Work Allotment settings page, if that mod is installed.
2. Otherwise the first installed provider in the capability's priority list.
3. Otherwise `Provider.NONE`.

Where a dedicated mod genuinely does the job better it is placed **ahead of us** in the
priority list. Examples: EntityCulling uses async occlusion raycasting and beats our
frustum test; Dynamic FPS handles frame throttling more thoroughly than our limiter; if
Sodium, Embeddium or VulkanMod is present, terrain is theirs and we stand down entirely.

`ownsTerrain()` is cached in a volatile field because it is queried from per-frame and
per-entity hot paths.

---

## 4. Package layout

```
destiny/renderer/
├── DestinyRenderer.java          entrypoint, lifecycle, GL-thread init
├── chunk/
│   ├── BlockStateLUT.java        precomputed per-state tables (see §5)
│   ├── ChunkMesher.java          face meshing        [gated: terrain]
│   ├── ChunkSectionData.java     SoA padded 18³ block data
│   ├── MeshingJobSystem.java     priority thread pool [gated: terrain]
│   └── PackedVertexFormat.java   64-bit vertex packing
├── compat/
│   ├── Capability.java           units of work
│   ├── Provider.java             mods that can own them
│   └── WorkAllotment.java        the resolver
├── config/
│   └── RendererConfig.java       JSON-backed, self-sanitising
├── explosion/
│   └── ExplosionRebuildBatcher.java   coalesces blast rebuild storms
├── gui/
│   ├── DestinySettingsScreen.java     the settings UI
│   ├── OptionRegistry.java            the option catalogue
│   ├── Theme.java                     themeable palette
│   └── options/                       Option / Group / Page / controls
├── hud/
│   └── PerformanceOverlay.java        FPS, coords, memory readout
├── memory/
│   ├── BufferPoolAllocator.java       coalescing free-list
│   └── GpuBuffer.java                 persistent mapping
├── render/                            terrain backends [gated: terrain]
└── mixin/                             18 mixins, all verified applying
```

---

## 5. Active optimizations

These run regardless of the terrain gate — they are what the mod actually delivers today.

| Area | Mechanism | File |
|---|---|---|
| **Fluid interior culling** | Faces between two same-type fluid blocks are invisible and are now culled. Oceans and lava lakes were emitting a wall of hidden translucent quads. | `FluidRenderOptimizationMixin` |
| **Explosion rebuild batching** | An 80-TNT burst queues the same section hundreds of times, each a full 4096-block re-mesh. Batched into one bulk rebuild per blast. | `ExplosionRebuildBatcher` |
| **Explosion particle cap** | Burst-limited over a 250 ms window. | `ExplosionOptimizationMixin` |
| **Fire overlay cap** | Burning entities past a threshold skip the overlay — past a handful it is an opaque wall of overdraw. | `FireRenderOptimizationMixin` |
| **Deferred chunk rebuilds** | Non-urgent rebuilds go into a nearest-first priority queue and are re-submitted over following frames. Targets the two-second freeze when a blast exposes a cave system. Nothing is dropped — see below. | `ChunkUpdateThrottleMixin`, `DeferredRebuildQueue` |
| **Block entity distance cull** | Chests, signs, banners and beacons bypass the batched chunk path and render individually. Distance-limiting them is one of the largest remaining average-FPS wins. | `BlockEntityCullMixin` |
| **Ground fire cull** | Fire blocks are animated, emissive, alpha-blended non-cubes. A burning field is a fill-rate wall. Radius-limited. | `GroundFireCullMixin` |
| **Granular particle control** | Per-type toggles, distance cull, population cap, even-sampled density. | `ParticleOptimizationMixin`, `ParticleClassifier` |
| **Entity/world toggles** | Item frames, armor stands, paintings, dropped items, block entities, sky, clouds, weather. | `EntityRendererMixin`, `WorldRenderFeatureMixin` |
| **Context FPS caps** | Main menu and pause/inventory capped separately; "Never" option for unfocused. | `InactivityFpsMixin` |
| **Chat compaction** | Repeated lines collapse to `(xN)`, avoiding repeated chat re-layout. | `ChatCompactMixin` |
| **State LUTs** | Translucency and tint were resolved by registry lookup + `String.contains` per block — 5832× per section. Now one array read. | `BlockStateLUT` |

---

## 6. Bugs fixed during this work

Ordered by severity.

1. **Invisible chunks.** `ChunkBuilderMixin` cancelled `scheduleRebuild(false)` — the
   normal chunk-loading path — so vanilla never built terrain, while our own draw call was
   commented out. The world was empty.
2. **Init NPE disabling everything.** `activeBackend.name()` was logged unconditionally
   after the backend became conditional. The resulting NPE set `active = false`, silently
   disabling *every* optimization.
3. **192 MB wasted allocation.** GPU buffers, Hi-Z pyramid, compute cull and batch
   renderers were allocated for a pipeline that never draws.
4. **Buffer allocator corruption.** The allocator was a bump pointer that wrapped to offset
   0 when full, overwriting geometry that live draw commands still referenced. Replaced
   with a coalescing free-list; `removeSection` now actually frees.
5. **`GL_INVALID_ENUM` spam.** Hi-Z rebuilt every frame against a depth handle that was
   never set (always 0).
6. **Render thread starvation.** Mesher threads ran at `NORM_PRIORITY + 1` — above the
   render thread — directly under a comment claiming they used `MIN_PRIORITY`.
7. **Thread-per-retry leak.** Every palette read failure spawned a new `Thread` and a fresh
   ~1.8 MB mesher. Replaced with a shared `ScheduledExecutorService` and bounded retries.
8. **Fake texture UVs.** Every face used hardcoded `{0,1,1,0}`, sampling the entire atlas
   instead of its own sprite. Now resolved from the block model.
9. **Translucent faces never culled.** Water-against-water emitted geometry for every
   interior face.
10. **Video Settings mixin never applied.** Targeted `init`, which is inherited from
    `GameOptionsScreen` and absent from `VideoOptionsScreen`'s bytecode. Moved to
    `addOptions`. A prior attempt also `@Shadow`-ed a `parent` field that does not exist on
    the subclass.
11. **Cancel button saved changes.** `removed()` called `applyAll()` unconditionally.
12. **Null config crash.** Gson returns `null` for an empty file; every `get()` then NPE'd.
13. **GL teardown off-thread.** Ran from a JVM shutdown hook after the context may be gone.
14. **Broken particle logic.** The OFF branch tested `MINIMAL`, and the density quota
    dropped particles in contiguous runs rather than sampling evenly.
15. **Dead mixins.** `FireCommandRendererMixin` cancelled vanilla fire rendering and
    delegated to a now-null renderer, which would have made fire invisible.
16. **Infinite recursion crash on anchor/crystal explosions.** An explosion rebuild
    batcher called `scheduleBlockRenders()`, which its own mixin intercepted and fed back
    into itself. One respawn anchor blast locked the render thread. The whole batcher was
    removed — see below.
17. **Invisible block faces after explosions.** The chunk update throttle *cancelled*
    excess rebuild requests. A cancelled request is never re-issued, so sections kept
    stale geometry and newly exposed faces were never built. Replaced with a real deferral
    queue that re-submits everything; only the timing changes, nothing is discarded.
18. **Click targeting hit the wrong option.** `render()` assigned each row its on-screen Y,
    and `mouseClicked()` then subtracted the scroll offset from that same value — applying
    it twice. Row layout positions are now tracked separately from widget positions.

---

## 7. Settings interface

Nine pages: General, Quality, Details, Animations, Particles, Performance, Overlays,
Work Allotment, Appearance.

Rebuilt from scratch because the previous two screens (which duplicated each other with
diverging defaults) shared structural problems:

- **No scrolling** — options past the window height were unreachable.
- **Search box filtered nothing.**
- **Slider ranges guessed by substring-matching the option's display name.**
- **Values snapshotted at construction**, so external changes were invisible.
- **Only Boolean/Integer/three enums supported**; every float option was silently dropped.

The current implementation has a scrollable content region, working search across all
pages, explicit `range(min, max, step)` per option, live-apply with genuine revert, and
six colour themes (default Crimson — red on black) plus a custom option.

Options that another mod owns render disabled with an explanation pointing at the Work
Allotment page, rather than appearing functional and doing nothing.

---

## 7a. Multiplayer / PvP safety

Several optimizations are unsafe on a server if applied naively, because they let what you
see drift from what the server is simulating. These are handled explicitly:

| Risk | Handling |
|---|---|
| **Deferred chunk updates** hide a wall someone just broke, so you get shot through geometry that no longer exists. | Disabled on servers by default (`deferChunkUpdatesInMultiplayer = false`). Single player is unaffected because the client is authoritative. |
| **Entity culling** hides an opponent behind a corner or an arrow in flight. | `alwaysRenderCombatEntities` (on by default) exempts players, all projectiles, TNT and end crystals from every filter and distance check. Mobs are deliberately *not* exempt, or culling would be pointless. |
| **Block entity culling** hides a chest you are looting. | Distance-based only; the frustum check still runs first, and interaction is server-side regardless. |

**No network code is touched.** Every mixin in this mod hooks client rendering only —
there is nothing that reads, writes, delays or fabricates packets. That matters both for
anticheat compatibility and for the mod being defensible on a server.

---

## 8. Performance expectations — be realistic

Measured target hardware: **Intel UHD 630** (integrated, shared memory), **i5-10400T**
(35 W, throttles under sustained load).

This mod trims redundant work *around* vanilla's renderer. It cannot match a mod that
*owns* terrain submission, because §2 makes that impossible on this version. The honest
framing:

- The fixes above should substantially raise the **frame time floor** — the drops to 12,
  50 and 100 that come from rebuild storms, explosion spikes and fluid overdraw.
- They will **not** multiply average FPS several times over. Nothing at this layer can.
- On integrated graphics the ceiling is fill rate and memory bandwidth in silicon.

If a large FPS increase is the goal on this hardware, running DestinyRenderer *alongside*
Sodium, ImmediatelyFast and FerriteCore will beat DestinyRenderer alone. Work Allotment
exists precisely to make that combination clean — it detects them and hands off the
overlapping work automatically.

---

## 8a. iGPU optimization proposals — status

A set of integrated-GPU optimizations was proposed. Assessed honestly against what is
reachable on 1.21.11:

| Proposal | Status | Why |
|---|---|---|
| Adaptive rebuild throttling on iGPU | **Implemented** | `DeferredRebuildQueue` caps the per-frame upload burst on shared-memory parts and halves it further when recent frames exceed 20 ms. This is the one proposal that acts on code we actually control. |
| Vertex quantization (`GL_INT_2_10_10_10_REV`, half-float) | **Not applicable** | Applies to `PackedVertexFormat`, which feeds the terrain pipeline. That pipeline never runs (§2), so this optimizes dead code. `PackedVertexFormat` is already an 8-byte packed format — the problem is the opposite one: it lacks the precision for non-cube models. |
| 16-bit index buffers | **Not applicable** | Same reason. Our index buffers are never bound. |
| Coalesced persistent mapped buffers | **Already done, unused** | `GpuBuffer` already uses `GL_MAP_PERSISTENT_BIT \| GL_MAP_COHERENT_BIT`. It is no longer allocated at all, because allocating 192 MB for a pipeline that never draws was a measured regression on this exact hardware. |
| Compute cull + `DrawElementsIndirect` over mesh shaders | **Blocked** | Correct in principle, and `ComputeCullShader` exists. But `RenderPass` exposes no indirect draw entry point (§2), so neither path can be bound. |
| Hi-Z pyramid depth limiting | **Blocked** | `HiZDepthPyramid` is not instantiated — it only fed the terrain culler. |
| Depth pre-pass / front-to-back sorting | **Not reachable** | Vanilla owns opaque submission through `SectionRenderState`. We cannot reorder or inject a pre-pass without owning that path. |
| FP16 / `mediump` shader hints | **Not reachable** | `terrain.frag`, `translucent.frag` and `fire.frag` are our shaders, and none of them are ever compiled or bound. Vanilla's shaders are managed by blaze3d. |
| Adaptive meshlet LOD | **Not reachable** | Requires owning meshing. |

**The pattern:** eight of the nine proposals target the GPU-driven terrain pipeline, and
that pipeline is unreachable on this Minecraft version for the reasons in §2. They are
sound techniques — they are what you would implement *after* the blaze3d port, not before
it. Applying them now would mean carefully tuning code that never executes.

---

## 9. Known gaps

- `PackedVertexFormat` uses 6 bits per axis at whole-block precision. Slabs, stairs,
  fences and every non-cube model cannot be represented. Must be widened before the
  terrain pipeline can be enabled.
- Greedy meshing is exposed as an option and implemented as a config flag, but the merge
  pass itself is not written. It is gated behind the terrain pipeline, so it is inert.
- `cpuRenderAhead` is stored and surfaced but not yet applied to the driver.
- ModMenu integration is not included; it requires a compile-time dependency on ModMenu.

---

## 10. Building

```powershell
cd "D:\Special Mods-1.21.11\DestinyRenderer"
.\gradlew.bat build        # jar -> build/libs/
.\gradlew.bat runClient    # dev client, loads run/mods
```

Requires JDK 25 (`build.gradle` toolchain). Mixins target `JAVA_21`.

`injectors.defaultRequire` is `0` so a single failed injection degrades that one feature
rather than crashing the game — important given the volume of version-sensitive hooks.

Verify mixins apply after any change:

```powershell
Get-Content run_log.txt | Select-String "Mixin apply.*failed|InvalidMixinException"
```

Last verified: all 18 mixins apply cleanly, no exceptions, LUT builds for 29,671 states.
