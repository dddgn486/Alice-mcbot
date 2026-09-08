# Inventory Transfer Transaction A0 Shallow Research

- Date: 2026-08-21
- Baseline: `3644429d2d88e1bb6fd1eb24654d828d1451d7f8`
- Plan: `20260821-inventory-transfer-transaction-design-v1 / APPROVED_FOR_IMPLEMENTATION`
- Scope: readonly local source/design research only; no code, simulation, actual transfer, endpoint descriptor, task, client test, or C2 authorization.
- User decisions: first research endpoint is original chest <-> bot inventory; compare future real bot-inventory transport with endpoint-to-endpoint direct transfer; do not choose a mode; A0 stays readonly.

## Question

What minimum transaction contract must exist before Alice may propose a separate C2 implementation for an original chest and bot inventory, without treating C1 facts as endpoint roles or allowing item loss/duplication?

## Fixed Local Facts

1. `BotPlayer` extends `ServerPlayer` (`src/main/java/com/dddgn/alice/bot/BotPlayer.java:22-26`), so a bot has the normal player inventory model. This does not by itself establish any C2 write permission.
2. Existing collection uses the original item-entity path: `DropCollectionTask.forcePickup` calls `item.playerTouch(bot)` (`src/main/java/com/dddgn/alice/task/DropCollectionTask.java:231-240`). It does not transfer from a container and offers no reservation, endpoint, or recovery contract.
3. Existing code directly sets only the selected bot inventory slot for tool assignment/restoration (`MineTask.java:40-42`, `BotManager.java:154-158`). `BotManager.syncMainHand` also documents that direct `Inventory.setItem` needs explicit client packets for the selected slot (`BotManager.java:99-105`). These are implementation-specific tool behaviors, not an inventory transaction API.
4. C1 `InterfaceSnapshot` is immutable readonly observation. It captures unsided raw slot facts and does not retain handlers; its generic slots do not encode input/output/side semantics. It must remain a pre/post observation source only.
5. `TaskExecutionRecord` records one terminal task outcome only (`TaskExecutionRecord.java:6-35`). It has no per-stage transaction state, quantity ledger, endpoint identity, reservation, in-transit location, replay key, or recovery ownership.
6. Local fixed Forge source is available at `~/.gradle/caches/forge_gradle/maven_downloader/net/minecraftforge/forge/1.20.1-47.4.10/forge-1.20.1-47.4.10-sources.jar`, `net/minecraftforge/items/IItemHandler.java`:
   - `getStackInSlot` returns an `ItemStack` that callers must not modify (lines 25-45).
   - `insertItem(slot, stack, simulate)` returns the remainder; `simulate=true` only simulates and does not imply a later actual insert will still have the same result (lines 47-62).
   - `extractItem(slot, amount, simulate)` returns the extracted stack; `simulate=true` only simulates (lines 64-78).
   - `isItemValid(slot, stack)` excludes current contents/fullness/state. True means no insertion guarantee; insertion must be simulated case by case (lines 88-107).
7. No current Alice source calls `IItemHandler.insertItem` or `extractItem`; current `IItemHandler` use is C1 capture only. This was confirmed by repository search at the stated baseline.
8. The existing deterministic architecture requires server facts and explicit recovery, not LLM inference (`docs/AI_PLAYER_DESIGN.md:12-18, 45-78`). The roadmap requires transaction states and location knowledge after every failure (`docs/PRODUCT_ARCHITECTURE_ROADMAP.md:206-218`; `docs/AI_PLAYER_DESIGN.md:238-245`).

## Fact Versus Inference

- Fact: Forge exposes slot-level simulate and actual calls, and a simulation result is not a lease/reservation.
- Fact: C1 has no endpoint role, side, permission, write, or transaction state.
- Inference adopted for A1 planning: every actual write must be surrounded by a fresh pre-observation and post-observation, and a changed world between simulation and actual application is a recoverable transaction conflict, not a reason to retry blindly.
- Inference adopted for A1 planning: `isItemValid` can be a negative rejection hint only. It is never an acceptance proof and cannot replace a simulate/actual/post verification sequence.
- Unknown: the exact capability exposure and side behavior of a specific original chest in this Forge runtime has not been exercised. A0 must not claim that original chest exposes a particular `IItemHandler` path; A1 must use a fixed local source proof or a dedicated deep-research decision before writing.

## Candidate A1 Transaction Model (Design Only)

The future C2 transaction identity must include immutable request id, actor/bot UUID, source endpoint identity, destination endpoint identity, requested item predicate, maximum amount, fixed dimension/position/version facts, and an explicit user-authorized direction. It must never use a generic C1 slot index as a semantic role.

### States and Required Evidence

1. `planned`
   - Evidence: user-approved request, endpoint identities, item predicate, max amount, policy/permission decision, unique request id.
   - No inventory is changed.
2. `reserved`
   - Evidence: fresh source/destination observations plus any available handler simulations recorded with exact proposed delta and observation tick/version.
   - A simulation is evidence only, not a lock. If endpoint state changes, leave `reserved` for conflict/replan; do not reuse a stale result.
3. `extracted`
   - Evidence: actual source delta proves exactly what left source; the detached item amount and identity are recorded as Alice-controlled `in_transit` facts before any destination write.
   - Failure rule: if source result cannot be reconciled with requested delta, stop and report `source_delta_mismatch`; do not attempt destination insertion.
4. `in_transit`
   - Evidence: location is exactly one of `bot_inventory` or an explicit server-held transfer buffer, never an undocumented temporary stack. A1 must select one only after a new user decision.
   - No duplicate retry is permitted while this state is unresolved.
5. `inserted`
   - Evidence: actual destination result and remaining stack are recorded; source and in-transit facts are updated only by measured delta.
6. `verified`
   - Evidence: fresh post-observation proves source loss plus destination/bot gain equal the committed amount, adjusted only by an explicitly modeled known rule. Otherwise enter recoverable discrepancy, preserve request id and all snapshots, and require deterministic recovery or manual takeover.

### Mandatory Failure/Recovery Facts

- `capacity_rejected`, `source_unavailable`, `destination_unavailable`, `simulation_conflict`, `source_delta_mismatch`, `destination_delta_mismatch`, `external_interference`, `timeout`, `actor_disconnect`, `duplicate_request`, `permission_denied`, `manual_takeover_required`.
- Every terminal or suspended result must report item location as `source`, `bot_inventory`, `destination`, `unknown_discrepancy`, or `not_moved`. `unknown_discrepancy` blocks retry and needs manual recovery; it must never be silently converted into success/failure with a fresh transfer attempt.

## Future Transport Mode Comparison (No Choice Made)

### Real Bot-Inventory Transport

- Proposed sequence: extract source -> verified bot inventory gain/in_transit -> ordinary movement under a separately approved safe movement contract -> insert destination -> post-verify both ends.
- Advantages: visible possession, server/player inventory authority, natural interruption location, compatible with player-facing transport expectations.
- Risks: movement hazards, death/disconnect, inventory capacity, client inventory synchronization, long-lived in-transit recovery, and future route/task coupling.
- Required future client matrix: source chest state, bot inventory state, interruption/disconnect or blocked movement recovery, destination state, exact final item ownership, no duplication/loss.

### Endpoint-to-Endpoint Direct Transfer

- Proposed sequence: source actual extract -> explicit server transfer buffer/in_transit -> destination actual insert -> post-verify.
- Advantages: no movement dependency and shorter transaction lifetime.
- Risks: less visible, requires an explicit authoritative buffer/ledger and strict permission policy, can become an implicit machine/automation write path, and makes side/endpoint identity more sensitive.
- Required future client matrix: both endpoint GUIs, exact source/destination delta, rejected capacity, duplicate request, external mutation, log/request identity, and no unauthorized machine action.

### A0 Conclusion

The modes have different recoverability and user experience. Neither is selected, modeled as implemented, or authorized. A later A1 plan must ask the user to choose exactly one first mode and define an allowed one-way first endpoint direction.

## A1 Preconditions and Deep Research Gate

Before an A1 implementation plan, all must be true:

1. User chooses one transport mode, one first direction, endpoint identity, permission subject, quantity/item predicate, and manual-takeover policy.
2. A fixed source proof establishes the actual original chest/bot inventory access path for Forge 1.20.1-47.4.10 without relying on assumptions from C1 or generic handler presence.
3. A transaction ledger/recovery design can prove one item location after each interruption.
4. Headless fixtures can exercise simulated result, actual result, capacity rejection, duplicate request, external change, timeout, and post-delta verification.

Deep research is conditionally required before A1. It becomes mandatory if the selected endpoint access path, capability side semantics, container/player inventory mutation semantics, or recovery behavior cannot be proven from Alice local source plus fixed Forge 1.20.1 sources. It must be a bounded version-specific task, not general automation research.

## Stop Conditions

Stop A0 and return to supervision if any answer requires actual/simulated invocation, code/schema/command/GUI change, generic machine roles, side traversal, Mek/AE behavior, persistence/network design, LLM, batch transfer, or a client test. Those are A1-or-later concerns and require a separate plan.

## Verification Boundary

A0 has only source/document evidence. No `compileJava`, headless run, screenshots, or client acceptance is claimed. A future A1 cannot promote this report into implementation authorization; it requires a separate user-approved C2 plan, fresh research decision, verification fixtures, and Windows client matrix.
