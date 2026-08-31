# E2E Test Infrastructure Analysis & Configuration Report

**Agent**: `explorer_e2e_infra`  
**Date**: 2026-07-22  
**Target Project**: DestinyRenderer (Minecraft 1.21.11 Fabric / Yarn Mappings)  
**Project Root**: `d:/Special Mods-1.21.11/DestinyRenderer`  

---

## 1. Executive Summary

DestinyRenderer is a high-performance GPU-driven Minecraft 1.21.11 rendering mod targeting integrated graphics (specifically Intel UHD Graphics). To guarantee long-term stability, performance budget adherence (e.g., ≤ 2.5ms chunk upload budget, 0 B/frame heap allocation, 60s superflat stability), and regression prevention, the project requires a robust, multi-tiered end-to-end (E2E) testing infrastructure.

Currently, the project lacks JUnit 5 test dependencies, a configured `test` task in `build.gradle`, and a formal `src/test/` directory. However, it possesses an advanced client performance benchmarking framework (`benchmark_suite.py`, `benchmark.bat`, `BenchmarkFramework.java`, and `SelfTuningProfiler.java`).

This report provides the architectural design and configuration blueprint for:
1. Configuring **JUnit 5 Jupiter** and **Fabric Loom test runners** inside `build.gradle` so `./gradlew test` executes unit, integration, and algorithmic test suites seamlessly under Java 25.
2. Integrating the **Python Automated Benchmark Harness** into the Tier 4 E2E testing pipeline.
3. Specifying the structure and full content template for `TEST_INFRA.md` following Project Patterns (Opaque-box testing, Category-Partition, BVA, Pairwise, Workload testing, 4-tier Feature Inventory, Directory Layout, and Coverage Thresholds).

---

## 2. Codebase & Build Configuration Findings

### 2.1 Build System Inspection (`build.gradle`, `gradle.properties`, `settings.gradle`)
- **Java Toolchain & Environment**: Java 25 (`JavaLanguageVersion.of(25)` and `release = 25`). Requires JVM arguments for incubating features: `--add-modules jdk.incubator.vector`, `--enable-native-access=ALL-UNNAMED`, `-XX:+UseZGC`, `-XX:+AlwaysPreTouch`.
- **Fabric Loom & Fabric API**: Uses Loom `1.15-SNAPSHOT`, Minecraft `1.21.11`, Yarn `1.21.11+build.4`, Loader `0.18.4`, Fabric API `0.141.3+1.21.11`.
- **Current Test Gap**:
  - `build.gradle` lacks `repositories { mavenCentral() }`.
  - No `testImplementation` or `testRuntimeOnly` dependencies are defined.
  - No `test { useJUnitPlatform() }` task block exists in `build.gradle`.
  - `src/test/` directory is completely absent.

### 2.2 Existing Benchmark Infrastructure Inspection (`benchmark_suite.py`, `benchmark.bat`, `BenchmarkFramework.java`)
- **Automated Client Launch**: `benchmark.bat` and `benchmark_suite.py` launch `./gradlew runClient -Pbenchmark --no-daemon`, starting Minecraft with `-Ddestiny.benchmark=true` and quick-play auto-join (`--quickPlaySingleplayer`, `New World`).
- **Telemetry Parsing**: `benchmark_suite.py` parses console stdout for key metrics:
  - `World Reload Time: <ms>`
  - `Min Chunks (2) FPS: <fps>`
  - `Max Chunks (32) FPS: <fps>`
  - `Moving FPS: <fps>`
  - Spark profile URLs (`https://spark.lucko.me/...`)
- **In-Game Telemetry Engine**: `BenchmarkFramework.java` measures 300-frame rolling averages, 1% low FPS, 0.1% low FPS, chunk rebuild duration (`totalRebuildNs`), heap allocation rate (`MemoryMXBean`), and VRAM footprint estimate.
- **Auto-Tuning Engine**: `SelfTuningProfiler.java` benchmarks GPU Compute Culling, CPU SIMD Culling, and Fallback MDI over 120-frame phases to automatically select the optimal backend.

---

## 3. Gradle Test Harness & JUnit 5 Configuration Strategy

To enable `./gradlew test` to execute cleanly alongside Loom and the benchmark suite, `build.gradle` must be enhanced with JUnit 5 Jupiter support and JVM environment flags matching the runtime engine.

### 3.1 `build.gradle` Modification Plan

#### A. Repositories
Add `mavenCentral()` to enable fetching JUnit 5 artifacts:
```groovy
repositories {
    mavenCentral()
    // Mojang & Fabric repos configured via plugin / settings
}
```

#### B. Dependencies
Add JUnit 5 Jupiter API, Engine, Parameterized testing, and Mockito dependencies:
```groovy
dependencies {
    minecraft "com.mojang:minecraft:${project.minecraft_version}"
    mappings "net.fabricmc:yarn:${project.yarn_mappings}:v2"
    modImplementation "net.fabricmc:fabric-loader:${project.loader_version}"
    modImplementation "net.fabricmc.fabric-api:fabric-api:${project.fabric_version}"

    // JUnit 5 & Testing Dependencies
    testImplementation 'org.junit.jupiter:junit-jupiter-api:5.10.2'
    testImplementation 'org.junit.jupiter:junit-jupiter-params:5.10.2'
    testRuntimeOnly 'org.junit.jupiter:junit-jupiter-engine:5.10.2'
    testImplementation 'org.mockito:mockito-core:5.11.0'
}
```

#### C. Test Task Configuration
Configure the standard `test` task to use JUnit Platform and inherit JVM arguments (Incubator Vector API and native access):
```groovy
test {
    useJUnitPlatform()
    
    // Pass Java 25 vector module and native access flags to test worker JVMs
    jvmArgs sharedJvmArgs
    
    testLogging {
        events "passed", "skipped", "failed", "standardOut", "standardError"
        exceptionFormat "full"
        showExceptions true
        showCauses true
        showStackTraces true
    }
    
    // Fail fast on CI if required or control parallelism
    maxParallelForks = Runtime.runtime.availableProcessors().intdiv(2) ?: 1
}
```

#### D. Loom Fabric GameTest Integration
Configure Loom to expose a `runGameTest` task for headless server-side Minecraft world tests:
```groovy
loom {
    runs {
        client {
            vmArgs sharedJvmArgs
            if (project.hasProperty('benchmark')) {
                vmArg "-Ddestiny.benchmark=true"
                programArgs "--quickPlaySingleplayer", "New World"
            }
        }
        server {
            vmArgs sharedJvmArgs
        }
        gametest {
            server()
            name "Game Test"
            vmArgs sharedJvmArgs
        }
    }
}
```

---

## 4. `TEST_INFRA.md` Template Specification & Design

The `TEST_INFRA.md` file defines the project's testing pattern, guidelines, feature inventory, directory layout, execution commands, and quality thresholds.

Below is the complete structural specification designed according to project patterns.

```markdown
# DestinyRenderer — Test Infrastructure Specification (`TEST_INFRA.md`)

## 1. Testing Philosophy & Opaque-Box Methodology
DestinyRenderer enforces **Opaque-Box Testing** across all test tiers. Tests must observe and validate system state, outputs, invariants, telemetry log metrics, and side effects via public APIs, configuration interfaces, and external benchmark outputs, without relying on internal private implementation details.

### Test Design Techniques Applied:
1. **Category-Partition Method (CPM)**: Features are partitioned into independent categories (e.g., render distance, preset level, graphics pipeline mode, thread count) with discrete equivalence classes.
2. **Boundary Value Analysis (BVA)**: Stress testing edge cases (e.g., render distance 2 vs 32, max frame upload budget 0ms vs 2.5ms vs 100ms, empty mesh vs 10,000 quad mesh, 0 mobs vs 1,000 instanced mobs).
3. **Pairwise Testing (All-Pairs)**: Multi-dimensional configuration testing ensuring all 2-way combinations of presets, pipelines, AO modes, and graphics settings are covered without exponential state explosion.
4. **Workload & Stress Testing**: Real-world rendering scenarios including 60-second superflat stress runs, 20-explosion chain reaction stress, rapid world reload cycles, and VRAM slab pool exhaustion recovery.

---

## 2. Multi-Tiered Feature & Test Inventory

The test suite is structured across 4 distinct Tiers:
- **Tier 1: Feature Coverage** (≥5 unit/integration tests per feature)
- **Tier 2: Boundary & Corner Cases** (≥5 boundary tests per feature)
- **Tier 3: Cross-Feature Combinations** (Pairwise option matrix tests)
- **Tier 4: Real-World Application Scenarios** (Automated E2E benchmark & workload runs)

### Feature Map (8 Engine Subsystems):
1. **Feature 1: Chunk Meshing & Upload Engine** (`destiny.renderer.chunk`)
   - Sub-components: `MortonEncoder`, `GreedyMesher`, `ChunkSectionData`, `MeshingJobSystem`, `AmbientOcclusionCalculator`, `TranslucencySorter`, `PackedVertexFormat`.
   - Tier 1 Tests: Morton indexing spatial order, quad merging efficiency, section graph neighbor propagation, translucent quad depth sort order, packed vertex bit shifts.
   - Tier 2 Tests: Empty section meshing, 4096 solid block meshing, max budget throttle (2.5ms), thread count 1 vs 16 concurrency, invalid section coordinate overflow.
2. **Feature 2: GPU Batching & Indirect Draw Pipeline** (`destiny.renderer.render`)
   - Sub-components: `MDIRenderBackend`, `ComputeCullShader`, `HiZDepthPyramid`, `IndirectCommandBuffer`, `SpatialHierarchy`, `MeshShaderBackend`.
   - Tier 1 Tests: Indirect command buffer encoding, spatial hierarchy 128^3 bounding box culling, frustum plane intersection, culling mode toggles.
   - Tier 2 Tests: Zero draw calls (all culled), 10,000 chunk draw calls (max buffer capacity), frustum edge boundary intersection, HiZ depth pyramid mip map bounds.
3. **Feature 3: Memory Allocator & Pool System** (`destiny.renderer.memory`)
   - Sub-components: `TLSFAllocator`, `RendererArenaManager`, `GpuBuffer`.
   - Tier 1 Tests: First-fit TLSF block allocation, free-list immediate recycling, arena memory alignment, slice handle validity.
   - Tier 2 Tests: Single byte allocation, max slab size allocation, 10,000 rapid alloc/free iterations (fragmentation stress), allocation under out-of-memory condition.
4. **Feature 4: Shader Pipeline & Presets** (`destiny.renderer.hardware`, `destiny.renderer.config`)
   - Sub-components: `HardwareCapabilityDetector`, `HardwarePreset`, `HardwareProfile`.
   - Tier 1 Tests: GPU capability detection fallback, preset apply (Performance, Balanced, Quality), uniform state snapshot.
   - Tier 2 Tests: Intel UHD fallback mode enforcement, invalid GL version override, preset dynamic switching during active render frame.
5. **Feature 5: Entity, Particle & PvP Renderer** (`destiny.renderer.render`)
   - Sub-components: `EntityBatchRenderer`, `InstancedFireRenderer`, `ParticlePoolManager` (via render package).
   - Tier 1 Tests: Instanced mob transform buffer assembly, entity AO LOD distance thresholds, particle pool recycling.
   - Tier 2 Tests: 0 entities rendered, 1,000 mob instancing stress, entity distance at exact LOD boundary, frame-start chunk upload processing under high entity count.
6. **Feature 6: Startup & World Join Speed** (`destiny.renderer.mixin`)
   - Sub-components: `ChunkBuilderMixin`, CME Retry Engine, Spiral Loading Queue.
   - Tier 1 Tests: Inner-ring spiral section priority sorting, async snapshot creation, CME retry loop recovery.
   - Tier 2 Tests: Initial join zero-chunk state, top-height Y=319 section population retry, rapid disconnect/rejoin within 100ms.
7. **Feature 7: Config Screen & GUI Options System** (`destiny.renderer.gui`, `destiny.renderer.config`)
   - Sub-components: `SodiumOptionsGUI`, `RendererConfig`, Option controls (`CyclingControlElement`, `SliderControlElement`, `TickBoxControlElement`).
   - Tier 1 Tests: Option value serialization/deserialization to JSON, preset button state update, slider drag step clamping.
   - Tier 2 Tests: GUI scale 1x, 2x, 3x, 4x layout bounds verification, corrupted JSON config recover-to-defaults, extreme slider boundary values.
8. **Feature 8: Compatibility & Mixin Layer** (`destiny.renderer.compat`, `destiny.renderer.mixin`)
   - Sub-components: `FRAPICompatLayer`, `DestinyRenderContext`, `ExplosionOptimizationMixin`, `DynamicCompatibilityManager`.
   - Tier 1 Tests: FRAPI quad emitter state pass-through, material finder creation, explosion mixin block update throttle.
   - Tier 2 Tests: 50 simultaneous block updates from T.N.T. explosion chain, unsupported FRAPI extension fallback, third-party mod mixin override detection.

---

## 3. Test Runner & Execution Architecture

| Test Level | Scope | Framework / Runner | Execution Command | Target Duration |
|------------|-------|-------------------|-------------------|-----------------|
| **Unit Tests (Tier 1 & 2)** | Core math, allocators, meshing, config | JUnit 5 Jupiter | `./gradlew test` | < 5 seconds |
| **GameTests (Tier 1 & 2)** | Headless Fabric server world state | Fabric Loom GameTest | `./gradlew runGameTest` | < 30 seconds |
| **Pairwise Matrix (Tier 3)** | Config & preset combinations | JUnit 5 Parameterized | `./gradlew test --tests "*Pairwise*"` | < 10 seconds |
| **E2E Workloads (Tier 4)** | Client performance, reload & FPS | Python / Gradle Harness | `python benchmark_suite.py` | 60–120 seconds |

---

## 4. Directory Layout

```
src/
├── main/java/destiny/renderer/...
└── test/
    ├── java/
    │   └── destiny/
    │       └── renderer/
    │           ├── unit/
    │           │   ├── chunk/
    │           │   │   ├── MortonEncoderTest.java
    │           │   │   ├── GreedyMesherTest.java
    │           │   │   ├── ChunkSectionDataTest.java
    │           │   │   ├── AmbientOcclusionCalculatorTest.java
    │           │   │   └── TranslucencySorterTest.java
    │           │   ├── batch/
    │           │   │   ├── SpatialHierarchyTest.java
    │           │   │   └── FrustumCullerTest.java
    │           │   ├── memory/
    │           │   │   ├── TLSFAllocatorTest.java
    │           │   │   └── RendererArenaManagerTest.java
    │           │   ├── config/
    │           │   │   ├── RendererConfigTest.java
    │           │   │   └── HardwarePresetTest.java
    │           │   └── compat/
    │           │       └── FRAPICompatLayerTest.java
    │           ├── integration/
    │           │   ├── render/
    │           │   │   ├── MDIRenderBackendTest.java
    │           │   │   └── InstancedFireRendererTest.java
    │           │   ├── mixin/
    │           │   │   ├── ExplosionOptimizationMixinTest.java
    │           │   │   └── ChunkBuilderMixinTest.java
    │           │   └── gui/
    │           │       └── ConfigScreenStateTest.java
    │           ├── pairwise/
    │           │   └── PresetCombinationPairwiseTest.java
    │           └── e2e/
    │               ├── BenchmarkTelemetryTest.java
    │               └── SelfTuningProfilerTest.java
    └── resources/
        ├── test_configs/
        │   ├── default_config.json
        │   ├── extreme_config.json
        │   └── invalid_config.json
        └── baselines/
            └── superflat_benchmark_baseline.json
```

---

## 5. Coverage & Quality Thresholds

1. **Code Coverage Targets**:
   - Algorithmic & Logic Packages (`chunk`, `memory`, `config`, `hardware`): **≥ 85% Line Coverage**, **≥ 80% Branch Coverage**.
   - Overall Project Non-GL Logic: **≥ 80% Line Coverage**.
2. **Pass Rate Requirement**: **100% pass rate** required across all test suites prior to release tag / PR merge.
3. **Performance Metrics Thresholds (Tier 4 E2E Benchmark)**:
   - **Frame Upload Budget**: `processUploadQueue` must not exceed **2.5ms** frame budget throttle.
   - **Heap Allocation Rate**: **0 B/frame** allocation during steady-state rendering (zero GC pressure).
   - **World Reload Time**: Standard superflat reload ≤ **1500ms**.
   - **Frame Stability**: 1% Low FPS ≥ **60%** of Average FPS under 32 chunk render distance.
```

---

## 5. Summary of Recommended Implementation Steps

To fulfill the E2E infrastructure setup:
1. **Modify `build.gradle`**: Add `mavenCentral()`, JUnit 5 Jupiter dependencies (`junit-jupiter-api`, `junit-jupiter-params`, `junit-jupiter-engine`, `mockito-core`), and the `test {}` configuration block with `sharedJvmArgs`.
2. **Generate `TEST_INFRA.md`**: Create `TEST_INFRA.md` at project root (`d:/Special Mods-1.21.11/DestinyRenderer/TEST_INFRA.md`).
3. **Establish `src/test/` Directory Structure**: Create packages `destiny.renderer.unit`, `destiny.renderer.integration`, `destiny.renderer.pairwise`, and `destiny.renderer.e2e`.
4. **Implement Test Harness Classes**: Build baseline test runner classes validating core logic and performance telemetry.

---
