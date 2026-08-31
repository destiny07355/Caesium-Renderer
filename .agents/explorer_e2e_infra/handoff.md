# Handoff Report: E2E Test Infrastructure Analysis & Design

**Agent**: `explorer_e2e_infra`  
**Date**: 2026-07-22  
**Target Path**: `d:/Special Mods-1.21.11/DestinyRenderer/.agents/explorer_e2e_infra/handoff.md`  

---

## 1. Observation

Direct observations from inspecting the codebase and build environment:
1. **Project Root Build Configuration (`build.gradle`)**:
   - Lines 31-36: Java toolchain is configured for Java 25 (`languageVersion = JavaLanguageVersion.of(25)`).
   - Lines 38-45: `JavaCompile` options pass `--add-modules jdk.incubator.vector`.
   - Lines 48-57: `sharedJvmArgs` includes `-XX:+UseZGC`, `--enable-native-access=ALL-UNNAMED`, `--add-modules=jdk.incubator.vector`, `-Xms4G`, `-Xmx4G`.
   - Line 13: `repositories {}` block contains only a comment (`// Mojang bundles LWJGL — no extra repos needed for core engine`). `mavenCentral()` is missing.
   - Lines 17-22: `dependencies {}` block contains Minecraft, Yarn, Loader, and Fabric API, but no testing dependencies (e.g. `junit-jupiter`).
   - Lines 59-72: `loom.runs {}` contains `client` and `server` blocks, but no `test {}` task configuration exists in `build.gradle`.
2. **Directory Structure (`src/`)**:
   - `src/main/java/destiny/renderer/` contains 51 Java source files covering meshing, batching, memory allocation, shaders, config, hardware detection, mixins, and benchmarking.
   - `src/test/` directory does not exist anywhere in `src/`.
3. **Existing Benchmark Suite**:
   - `benchmark_suite.py` (lines 120-123): Executes `gradlew runClient -Pbenchmark --no-daemon`.
   - `benchmark_suite.py` (lines 89-109): Parses stdout regex for `World Reload Time`, `Min Chunks (2) FPS`, `Max Chunks (32) FPS`, `Moving FPS`, and Spark links.
   - `BenchmarkFramework.java` (lines 31-92): Tracks frame times ring buffer (300 frames), 1% low, 0.1% low, chunk rebuild ns, heap delta via `MemoryMXBean`, and VRAM size.
   - `SelfTuningProfiler.java` (lines 13-161): Benchmarks 3 rendering pipeline phases (GPU Compute, CPU SIMD, Fallback MDI) over 120 frames each to select the fastest configuration.

---

## 2. Logic Chain

1. **Gradle Test Execution Setup**:
   - Standard `./gradlew test` requires JUnit 5 Jupiter engine on the test classpath.
   - Adding `mavenCentral()` to `repositories {}` and `testImplementation 'org.junit.jupiter:junit-jupiter-api:5.10.2'`, `testImplementation 'org.junit.jupiter:junit-jupiter-params:5.10.2'`, `testRuntimeOnly 'org.junit.jupiter:junit-jupiter-engine:5.10.2'`, `testImplementation 'org.mockito:mockito-core:5.11.0'` to `dependencies {}` provides JUnit 5 testing capability.
   - Configuring `test { useJUnitPlatform(); jvmArgs sharedJvmArgs }` ensures test workers execute with incubator vector module access and ZGC/FFM flags under Java 25.

2. **Integration of Loom and Python Benchmark Harness**:
   - Loom provides `gametest` task capabilities for headless Minecraft world state testing.
   - Python benchmark harness (`benchmark_suite.py`) provides Tier 4 E2E workload verification by running quickplay client instances and validating telemetry output against performance thresholds.

3. **`TEST_INFRA.md` Design Pattern Compliance**:
   - Applying Opaque-Box Testing guarantees tests interact with public interfaces (APIs, JSON configs, telemetry stdout).
   - Category-Partition Method (CPM) + Boundary Value Analysis (BVA) + Pairwise Matrix ensures all 8 engine subsystems (Meshing, Batching, Memory, Shaders, Entities, Startup, Config GUI, Mixin/FRAPI) are covered across Tiers 1-4.
   - Detailed directory layout under `src/test/java/destiny/renderer/{unit, integration, pairwise, e2e}` mirrors the main package layout.

---

## 3. Caveats

- **Headless OpenGL Hardware Context**: Standard JUnit 5 JVM execution runs headlessly and cannot create an actual GLFW/OpenGL window or execute native GL calls (e.g. `glMultiDrawElementsIndirect`). Tests in `src/test/java` for rendering pipelines must test state setup, command buffer packing logic, math, allocators, and data structures. Native rendering integration is validated via Loom GameTest or full quickplay client runs (`benchmark_suite.py`).
- **Java 25 Incubator Modules**: Test JVMs must receive `--add-modules jdk.incubator.vector` and `--enable-native-access=ALL-UNNAMED` or vector culling/TLSF allocator tests will throw module access errors.

---

## 4. Conclusion

The build system and test harness strategy for DestinyRenderer has been fully mapped and designed.
- **Gradle Test Configuration**: Fully detailed step-by-step additions for `build.gradle` (JUnit 5 dependencies, `useJUnitPlatform()`, JVM options, `mavenCentral()`).
- **`TEST_INFRA.md` Specification**: Complete template specification ready for instantiation at project root covering philosophy, Category-Partition, BVA, Pairwise, 8-feature 4-tier inventory, directory layout, and coverage thresholds (Line ≥80%, Pass Rate 100%, Budget ≤2.5ms, 0 B/frame heap alloc).
- Analysis report delivered at `.agents/explorer_e2e_infra/analysis.md`.

---

## 5. Verification Method

1. Inspect `d:/Special Mods-1.21.11/DestinyRenderer/.agents/explorer_e2e_infra/analysis.md` to review the full build modification guide, JUnit 5 configuration, and complete `TEST_INFRA.md` template design.
2. Verify that `build.gradle` modification snippets include `mavenCentral()`, JUnit 5 dependencies, and `useJUnitPlatform()`.
3. Verify that the designed directory layout covers `src/test/java/destiny/renderer/` subpackages: `unit`, `integration`, `pairwise`, `e2e`.
