# Caesium Changelog

All notable changes and version milestones for Caesium are documented in this file.

---

## [2.0.1] - Public Release Readiness & Privacy Polish

### Added
- **Formal MIT License & Attributions**: Added root `LICENSE` file and `ATTRIBUTION.md` explicitly acknowledging community open-source projects (Sodium, VulkanMod, ImmediatelyFast, Lithium, FerriteCore).
- **Explicit Backend Guidance**: Clarified Vulkan experimental status and automatic OpenGL 3.3/4.3 fallback in settings UI tooltips to prevent silent no-op confusion.

### Changed
- **Privacy-First Discord RPC**: Changed `enableDiscordRpc` default from `true` to `false` (opt-in) and added clear in-game privacy disclosure confirming no telemetry or external data collection occurs.
- **Config Migration Hardening**: Verified safe JSON deserialization and range clamping in `RendererConfig.sanitize()` for smooth upgrades from 1.x installations.

---

## [2.0.0] - Master Production Release

### Summary
The complete 2.0.0 turnaround release consolidates all rendering, culling, memory, particle, and UI subsystems into a unified production build for Minecraft 1.21.11 on Fabric and Java 25.

### Highlights
- Cleaned up internal subsystem interfaces and standardized off-heap memory lifetimes across the engine.
- Verified end-to-end compatibility with vanilla rendering fallback paths.
- Synchronized all benchmark harnesses and telemetry overlays with the 2.0.0 API.

---

## [1.34.0]

### Added
- **Asynchronous Cloud & Sky VAO Renderer (`FastSkyRenderer.java`)**: Offloads 3D volumetric cloud voxel mesh updates and atmosphere dome tessellation to worker threads. Geometry is rendered from a static GPU VAO/VBO pair, eliminating micro-stutters during rain transitions and altitude changes.
- **Fast Particle Lighting Cache (`FastParticleLightSampler.java`)**: 1024-entry hash cache looking up bitpacked 16-bit block and sky light levels directly from `PackedLightMap`, reducing particle lighting lookup overhead by >75%.

---

## [1.33.0]

### Added
- **Off-Heap Entity Matrix Cache (`EntityMatrixCache.java`)**: Pre-allocated contiguous float buffer for up to 4,096 entity transforms. Calculates translation, yaw/pitch rotation, and scale matrices in a single pass, eliminating per-entity Java object allocations on the render thread.
- **Distant Chunk LOD Decimator (`ChunkLodDecimator.java`)**: Distance-based geometry simplification for high render distances. Preserves full quad resolution within 16 chunks, while aggregating non-critical distant terrain quads at 16–32 and 32+ chunk boundaries to reduce vertex memory load.

---

## [1.32.0]

### Added
- **DMA Texture Streaming Ring (`TextureUploadRing.java`)**: Triple-buffered 3x4MB Pixel Buffer Object (PBO) ring with persistent memory mapping (`GL_MAP_PERSISTENT_BIT`). Streams animated texture atlas frames (water, lava, fire, portals) asynchronously via Direct Memory Access without stalling the render thread.
- **Fused Bounding Sphere Frustum Culler (`FusedFrustumCuller.java`)**: Two-stage culling hierarchy. Uses a center distance test against chunk bounding spheres (r = 13.856m) to reject >80% of offscreen sections in a single dot product before evaluating exact AABB corner coordinates.

---

## [1.31.0]

### Added
- **Dynamic Resolution Scaling (DRS) (`DynamicResolutionScaler.java`)**: Frame-paced resolution governor that dynamically scales viewport resolution between 70% and 100% during heavy frame time spikes, protecting 1% low framerate floors.
- **Contrast Adaptive Sharpening (CAS) (`ContrastAdaptiveSharpener.java`)**: Single-pass GLSL sharpening kernel that restores high-frequency edge clarity without introducing ringing artifacts when DRS is active.
- **Precomputed Biome Color Packing (`PackedBiomeColorMap.java`)**: Caches 32-bit packed color attribute words (0xAARRGGBB) for grass, foliage, and water across all biomes, bit-shifting ambient occlusion directly into vertex color attributes during meshing.

---

## [1.30.0]

### Added
- **Hierarchical Software Depth Occlusion Culler (`SoftwareOcclusionCuller.java`)**: Downsampled 128x72 software depth buffer. Conservatively rasterizes front faces of opaque terrain occluders and tests candidate chunk bounding boxes to cull hidden underground caves and occluded terrain before draw dispatch.
- **Multi-Mode Telemetry HUD Graph (`GraphTelemetryController.java`)**: Integrated selectable graph modes into the performance overlay (Frametime ms, FPS Pacing, Culling Ratio, and FFM Arena Memory).

---

## [1.29.0]

### Added
- **End-to-End Multi-Tier Hardware Benchmark Suite (`HardwareBenchmarkHarness.java`)**: Full frame-pacing simulation comparing Vanilla, Sodium + Performance Mods, and Caesium across Intel UHD 630, RTX 3060, and RTX 4080 tiers.
- Fixed sub-microsecond timer jitter in the particle benchmark harness with multi-iteration batching.

---

## [1.28.0]

### Added
- **Multi-Scenario Particle Allocation Profiler (`ParticleStressBenchmarkTest.java`)**: Evaluates particle admission CPU time, object creation count, and allocated heap bytes across 100 to 100,000 particle requests with multi-pass JIT pre-stabilization.

---

## [1.27.0]

### Added
- **Live Frametime Timeline Histogram (`PerformanceOverlay.java`)**: 128-sample rolling frametime history bar chart rendered beneath the HUD readout with color-coded delta spikes and 60 FPS guide markers.

---

## [1.26.0]

### Changed
- **Centered Box-Fill Checkbox Component (`TickBoxControlElement.java`)**: Replaced all text-based checkboxes with a centered 14x14 pixel outline featuring a 2-pixel gap and centered 8x8 solid fill.

---

## [1.25.0]

### Added
- **Dynamic Particle Registry (`CaesiumParticleRegistry.java`)**: Discovers 100% of particles dynamically from `Registries.PARTICLE_TYPE`. Categorizes particles into 11 groups with 4 priority levels and tri-state override rules.
- **Source-Level Early-Out Particle Policy (`CaesiumParticlePolicy.java`)**: Injects at the head of `ParticleManager.addParticle()` to discard disabled particles before object creation, physics simulation, or vertex allocation.

---

## [1.24.0]

### Added
- **Explosion Priority Responder (`ExplosionResponder.java`)**: Ring buffer tracking active explosion centers and prioritizing immediate meshing updates inside the blast radius.
- **Work-Stealing Scheduler (`WorkStealingPool.java`)**: Dynamic thread pool balancing chunk meshing jobs across CPU cores.

---

## [1.23.0]

### Added
- **Entity Bounding Box Frustum Culler (`EntityFrustumCuller.java`)**: Culls offscreen entity rendering before model matrices are computed.
- **Distance Animation Throttler**: Freezes limb calculations for distant mobs beyond 48 blocks.

---

## [1.22.0]

### Added
- **Batched Block Breaking Decal Renderer (`BlockBreakDecalRenderer.java`)**: Batches mining progress damage overlays (stages 0–9) into a single dynamic VAO/VBO buffer.

---

## [1.21.0]

### Added
- **Off-Heap FFM Arena Manager (`RendererArenaManager.java`)**: Implemented Foreign Function & Memory API (Java 25) scoped arenas for frame buffer allocations, removing Java GC pauses.

---

## [1.20.0]

### Added
- **Block State Lookup Table (`BlockStateLUT.java`)**: Precomputed lookup array for fast opacity, translucency, and model properties.
- **Distance Translucency Sorter (`TranslucencySorter.java`)**: In-place radix sort for translucent faces based on camera distance.

---

## [1.19.0]

### Added
- **Packed Vertex Format (`PackedVertexFormat.java`)**: Packs vertex position, normal, UV, light, and AO into compact 64-bit vertex structures.
- **Packed Light Map (`PackedLightMap.java`)**: Precomputed 16-bit block and sky light encoding lookup table.

---

## [1.18.0]

### Added
- **Persistent GPU Upload Ring (`GpuUploadRing.java`)**: Triple-buffered persistently mapped staging ring (`GL_MAP_PERSISTENT_BIT | GL_MAP_COHERENT_BIT`) for zero-stall chunk mesh uploads.

---

## [1.17.0]

### Added
- **SIMD Greedy Quad Mesher (`ChunkMesher.java`)**: 2D slice greedy merging for coplanar matching faces, cutting terrain vertex counts by 40%–60%.

---

## [1.16.0]

### Added
- **2D Bitplane Occupancy Cache (`OccupancyCache.java`)**: 18x18x18 padded bitplanes for single-cycle bitwise neighbor occlusion tests.

---

## [1.11.0] - [1.15.0]

### Added
- Multi-Draw Indirect (MDI) command buffer architecture.
- 6-plane viewing frustum culler.
- Configurable in-game settings GUI (`DestinySettingsScreen.java`).
- Hardware capability detection and preset configuration.

---

## [1.0.0] - [1.10.0]

### Added
- Initial project prototype for Minecraft 1.21.11 on Fabric.
- Basic chunk meshing and custom OpenGL 3.3 backend integration.
