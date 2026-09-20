# Caesium / DestinyRenderer upgrade handoff

This folder is a compact map for another model or developer taking over the mod.

## Read order

1. `01-current-state.md` — what is actually active today.
2. `02-runtime-flow.md` — startup, render-frame, reload, and shutdown paths.
3. `03-subsystems.md` — important packages and ownership boundaries.
4. `04-upgrade-plan.md` — safest sequence for future renderer work.
5. `05-review.md` — evidence-based code-review findings and verification limits.
6. `06-terrain-readiness.md` — activation gate, verified fixes, and remaining parity work.

## Identity

- Mod ID: `caesium`; display name: Caesium.
- Package roots: `destiny.renderer` (Minecraft/Fabric integration) and `caesium.engine` (standalone engine).
- Target: Minecraft 1.21.11, Fabric, Java 25.
- Client-only entrypoint: `destiny.renderer.DestinyRenderer`.
- Current branch contains many uncommitted changes; do not assume every working-tree file is part of the last committed release.

## Ground truth rule

When README, plans, comments, and runtime code disagree, trust the executable path and verify with a minimal client run. In particular, the current architecture doc says terrain replacement is inactive on 1.21.11.
