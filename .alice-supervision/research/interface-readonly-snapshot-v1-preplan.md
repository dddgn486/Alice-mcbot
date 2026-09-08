# Interface Readonly Snapshot v1 Preplan

- Status: PREPARATION_ONLY
- Lane: independent
- Not implementation authorization.
- Depends on: completion and supervisor review of `20260821-task-observability-v1` only for shared result/audit conventions; it does not depend on SOFT_SURFACE.

## Intended Objective

Convert existing server-side `InterfaceScanner` capability reads into a versioned structured readonly snapshot for item, energy, and fluid interfaces. Keep the current command as a human-readable projection. Do not add GUI interaction, writes, LLM networking, or permission-changing behavior.

## Shallow Research Facts

- `InterfaceScanner.scan` currently constructs a presentation string directly while reading Forge item/energy/fluid capabilities and Mekanism-specific states.
- Design decision R13 in `docs/AI_PLAYER_DESIGN.md` selects capability-first scanning, static block-adjacent interface discovery, and default-open readonly access.
- `docs/MEK_GUI_SEMANTICS.md` confirms slot hints are heuristics and Mek-specific chemical content is not yet a safe v1 scope.

## Required Next Planning Check

Before approval, inspect the `/alice scan` command call site and decide the v1 serialization boundary: internal Java records first, then a stable textual or JSON-compatible projection. A deep investigation is not currently required because Forge 1.20.1 capability APIs are already compiled in the project; trigger one if the chosen representation requires cross-version serialization, capability invalidation semantics, or a write/permission API.

## Frozen Boundaries

- Readonly only.
- No GUI clicks or menu automation.
- No `setMode`, upgrade, security, transmission, inventory extraction, or fluid transfer action.
- No LLM call, network request, or client authority.
- Do not claim slot hints are per-machine truth.
