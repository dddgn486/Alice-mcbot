# Interface Readonly Snapshot v1 Shallow Research

- Date: 2026-08-21
- Baseline: `d1def08676a3e74ef9927011edb7cf2a75e9a0ba`
- Compatibility target: C1 readonly observation.
- Scope: local Alice code/design review; no business-code authorization by this report alone.

## Evidence Reviewed

- `InterfaceScanner.scan`: directly interleaves unsided Forge capability reads with human-readable formatting and existing Mek-specific projection.
- Callers at baseline: `/alice scan <pos>` and server-side vanilla diamond-shovel `ScanWand`; both only log the returned string.
- User scope addition after initial approval: replace the vanilla-item hook with an Alice-owned `alice:interface_scanner` test item, following the existing `AliceItems` registration/model/localization pattern.
- Architecture decisions R38-R40 and `PRODUCT_ARCHITECTURE_ROADMAP.md`: C1 facts are distinct from slot semantics, executable adapters, knowledge and LLM tools.
- Forge 1.20.1 APIs already compiled in Alice: `IItemHandler`, `IEnergyStorage`, `IFluidHandler`, `LazyOptional` returned by `BlockEntity.getCapability`.

## Findings

1. The smallest useful change is `capture -> immutable snapshot -> formatter`, preserving the `scan` compatibility wrapper and command behavior while moving the item entry from vanilla `minecraft:diamond_shovel` to `alice:interface_scanner`.
2. Snapshot capture can stay synchronous on the server thread. It must copy values immediately and must not retain `LazyOptional`, handlers, mutable `ItemStack`, `FluidStack`, `CompoundTag`, `BlockEntity`, or `ServerLevel` references.
3. Unsided (`null` direction) capability discovery is the existing contract. Side-specific discovery would multiply/duplicate views and belongs to a later endpoint/adapter package.
4. Slot index is a fact; `slotHint` input/output/energy is a heuristic and must remain presentation-only with an explicit `heuristic` label or be removed from the generic projection. It must not enter the C1 fact model.
5. The generic v1 snapshot should include schema version, dimension, position, block id, optional block-entity type, observation tick, observation status, and item/energy/fluid facts.
6. Empty capability content and absent capability are different. No block entity, unloaded target and capture error must also be distinguishable.
7. Existing Mek-specific redstone/upgrade/security/config/chemical output is useful but not yet normalized. Preserve it as a clearly labeled legacy/adapter projection; do not claim it is part of the generic C1 schema.
8. No deep research is required: this package does not perform writes, hold capabilities across ticks, serialize across network/storage, or depend on external version semantics beyond APIs already used in the checkout.

## Proposed Fact Fields

- Snapshot: `schemaVersion`, `dimensionId`, immutable `BlockPos`, `blockId`, optional `blockEntityTypeId`, `observedServerTick`, `status`, descriptors/list.
- Item capability: slots with `index`, `itemId`, `count`, `damage`, optional tag/SNBT; empty slots represented explicitly.
- Energy capability: stored, capacity, canExtract, canReceive.
- Fluid capability: tanks with `index`, `fluidId`, amount, capacity, optional tag/SNBT; empty tanks represented explicitly.
- Observation status: at minimum `OK`, `NO_BLOCK_ENTITY`, `CHUNK_NOT_LOADED`, `CAPTURE_ERROR`. Capability absence is represented by an absent descriptor/list, not an error.

## Verification Boundary

- Deterministic headless coverage should capture a vanilla container with known contents and a non-block-entity block, then verify immutable structured values and stable formatting.
- Energy/fluid capture paths require compile coverage and, if practical, a known development machine/tank runtime scan. Lack of such a fixture must be recorded, not converted into a false PASS.
- Snapshot semantics do not require Windows client acceptance. The independent item does require client confirmation of model/name, scan activation, and absence of Alice interception for a vanilla diamond shovel. Neither validates machine operation semantics.

## Explicit Non-Goals

- No insert/extract, simulated transfer, slot acceptance probing, side traversal, caching, persistence, JSON/network serialization, LLM tool, permission changes, GUI automation, machine mode write, PipelineProfile or AE2 work.
