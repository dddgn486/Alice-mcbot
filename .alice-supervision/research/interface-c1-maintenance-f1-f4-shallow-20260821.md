# Interface C1 Maintenance F1-F4 Shallow Research

- Date: 2026-08-21
- Current baseline: `ba63d15b7dec9bf4887f099bd45e407af5e99a19`
- Preceding implementation: `8bb2d7b1641c0b6e6063d4c78ae0357896fd0c90`
- Preceding plan state: `20260821-interface-readonly-snapshot-v1 / USER_ACCEPTED`
- Research type: supervisor local shallow research; not implementation authorization.

## Question

Can the four accepted C1 maintenance findings (F1–F4) safely form one smallest, isolated maintenance package before any next functional capability is planned?

## Local Evidence

1. F1 is limited to `BotSelftest.runInterfaceSnapshotRegression` (`src/main/java/com/dddgn/alice/bot/BotSelftest.java:349-371`). Current execution captures a known chest and dirt and checks only initial status/content. It does not invoke `InterfaceScanner.format`, mutate the source chest after capture, or prove returned collections reject mutation.
2. F2 is confined to `InterfaceScanner.capture` (`src/main/java/com/dddgn/alice/capability/InterfaceScanner.java:41-52`). `getBlockState` currently precedes `hasChunkAt`. The unloaded result has no justified block identifier because it should not access target world state.
3. F3 is documentation-only. `docs/HANDOVER.md:4-17, 42-50` and `docs/SUPERVISOR_HANDOFF.md:8-13` retain pre-acceptance/old-HEAD claims despite current Git `ba63d15` and accepted client evidence. The work must update actual facts without claiming the historical old-save issue is solved.
4. F4 is confined to `InterfaceScanner.java:18-29, 155-193, 316-337`. `scanItems`, `scanEnergy`, `scanFluid`, and `slotHint` have no current callers. The live formatter already uses raw index facts at lines 85-105 and labels the Mek-only projection as non-generic at lines 107-109.
5. `InterfaceSnapshot` uses a record and `List.copyOf` (`InterfaceSnapshot.java:7-45`), so F1 is a focused regression-proof gap rather than a schema redesign.
6. Scanner client evidence is accepted only for identity, readonly scan, no-block-entity scan, and vanilla-shovel isolation. It does not grant C2+, transfer, endpoint semantics, machine writes, task/movement changes, or a conclusion about the historical old-save hang.

## Scope Assessment

### Allowed candidate scope

- Move `hasChunkAt(pos)` before target `getBlockState`/`getBlockEntity`; emit a conservative constant block id such as `unknown` for `CHUNK_NOT_LOADED` without dereferencing target state.
- Remove only unreachable old generic scan methods and the heuristic Javadoc/slot-hint text.
- Update C1 class documentation to state raw unsided readonly facts and explicitly retain `legacyProjection` as non-generic.
- Extend only `runInterfaceSnapshotRegression` to assert:
  - `format(snapshot)` does not mutate captured world/container state;
  - a snapshot stays unchanged after the source chest slot changes;
  - item/fluid lists reject mutation where such a list is present;
  - any assertion failure produces `INTERFACE_SNAPSHOT_SELFTEST FAIL`.
- Correct HANDOVER and supervisor handoff facts to current accepted status, Git baselines, actual tool identity, and retained limitations.

### Forbidden scope

- Any capability write, simulated insert/extract, endpoint/side traversal, persistence/network schema change, C2+ descriptor, adapter expansion, machine GUI automation, LLM integration, inventory transaction, task/movement change, or changes to mining/collection/road/follow/soft-surface behavior.
- Diagnosing or attempting to fix the historical old-save hang.
- Treating the maintenance selftest as client acceptance or broad GitHub CI proof.

## Verification Matrix

1. `./gradlew compileJava` must pass.
2. `./gradlew runServer -Dalice.selftest.auto=true` may remain subject to existing outer timeout, but logs must contain a focused `INTERFACE_SNAPSHOT_SELFTEST PASS` demonstrating all newly added F1 assertions. Timeout or exit 143 cannot be reported as whole-suite success.
3. Review source/diff confirms `hasChunkAt` precedes target state access and dead heuristic functions are absent.
4. Documentation review checks every stated HEAD/status against actual Git and client evidence.
5. No repeat Windows test is required if the item interaction contract is unchanged. Any changed client-visible behavior discovered during implementation stops the package for a new client matrix.

## Research Decision

Deep research is not required. This maintenance package reuses APIs already compiled in the checkout and alters no external-version-sensitive semantics. The package is small enough to plan directly, but implementation must wait for an independently reviewed route and explicit user approval.

## Stop Conditions

Stop and return to supervision if proving an unloaded target requires loading/changing chunks, if the selftest requires a new runtime fixture that expands beyond C1, if removal reveals a live caller, or if documentation correction exposes contradictory Git/client facts.
