# Scope: Milestone 1 — Research & Gap Analysis Report (R1)

## Objective
Perform a deep-dive research and gap analysis comparing DestinyRenderer against Sodium, VulkanMod, Lithium, ImmediatelyFast, and Iris. Produce a comprehensive `GAP_ANALYSIS.md` report.

## Deliverables
- `GAP_ANALYSIS.md` saved in `d:/Special Mods-1.21.11/DestinyRenderer/docs/GAP_ANALYSIS.md` (or workspace root / docs).
- Full analysis of chunk meshing, GPU batching/MDI, culling, memory allocators, GLSL shaders, entity/particle batching, startup speed, and mixin target fixes.

## Instructions
1. Explore all reference sources mentioned in `ORIGINAL_REQUEST.md`.
2. Inspect existing DestinyRenderer code in `d:/Special Mods-1.21.11/DestinyRenderer/src/main/java/`.
3. Highlight missing techniques, current flaws, expected FPS gains, and recommended design.
4. Verify all Yarn 1.21.11 mapping requirements for Explosion mixin.
