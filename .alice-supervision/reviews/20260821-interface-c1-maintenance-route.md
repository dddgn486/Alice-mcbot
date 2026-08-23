# Alice Supervisor Route Review

- Review ID: `20260821-interface-c1-maintenance-route`
- Baseline: `ba63d15b7dec9bf4887f099bd45e407af5e99a19`
- Preceding plan: `20260821-interface-readonly-snapshot-v1 / USER_ACCEPTED`
- Proposed plan ID: `20260821-interface-c1-maintenance-f1-f4-v1`
- Verdict: CONDITIONAL_PASS
- Stage status: NEEDS_REPLAN
- User scope authorization: received in current session; F1-F4 maintenance scope is authorized for planning and subsequent separate active-plan review, not yet implementation.

## Planning Source and Substitution

The protocol routes implementation planning to the Alice implementation planner. The planner is now reachable and has the read-only task `a6ab5dba-27ff-4fde-b80f-3879e22f0df8`; this supervisor route is the pre-report review baseline and remains subject to planner challenge before implementation authorization. The route is based on `.alice-supervision/research/interface-c1-maintenance-f1-f4-shallow-20260821.md`; it is not implementation authorization.

## Evidence Reviewed

- Current active plan and accepted Windows C1 evidence: `ba63d15` Windows run, S1-S4 `USER_ACCEPTED`.
- F1-F4 review: `.alice-supervision/reviews/20260821-8bb2d7b.md`.
- Local shallow research: `.alice-supervision/research/interface-c1-maintenance-f1-f4-shallow-20260821.md`.
- Current sources: `BotSelftest.java:349-371`, `InterfaceScanner.java:18-193, 316-337`, `InterfaceSnapshot.java:7-45`.
- Architecture/R# boundaries: `docs/SUPERVISION_PROTOCOL.md:5-18, 77-96`; product C1/C2 separation in `docs/PRODUCT_ARCHITECTURE_ROADMAP.md:261-271`.

## Proposed Minimal Route

### Objective

Close only F1-F4 so accepted C1 readonly observation has accurate documents, conservative unloaded-chunk behavior, removal of stale generic role-heuristic code, and focused runtime proof of snapshot/formatter non-mutation.

### Lane and Dependency

- Lane: `maintenance`.
- Relationship to prior old-save observation: isolated; it neither diagnoses nor repairs the historical hang.
- Dependency: user must approve this exact route. No deep research is required because all affected APIs are already in this checkout and no version-sensitive external behavior is introduced.

### Allowed Scope

1. `src/main/java/com/dddgn/alice/capability/InterfaceScanner.java`
   - check `hasChunkAt(pos)` before dereferencing target state;
   - return `CHUNK_NOT_LOADED` with a constant conservative id such as `unknown`;
   - delete only unreachable `scanItems`, `scanEnergy`, `scanFluid`, and `slotHint`;
   - replace stale class documentation with C1 raw unsided readonly facts and explicit non-generic Mek legacy projection wording.
2. `src/main/java/com/dddgn/alice/bot/BotSelftest.java`
   - extend `runInterfaceSnapshotRegression` only, proving formatter non-mutation, post-capture stability after source chest mutation, and immutable snapshot collection behavior;
   - retain stable `INTERFACE_SNAPSHOT_SELFTEST PASS|FAIL` output and make each new assertion participate in FAIL.
3. `docs/HANDOVER.md` and `docs/SUPERVISOR_HANDOFF.md`
   - correct actual baseline, client acceptance, item identity, F1-F4 status, and retained old-save limitation;
   - do not claim the old hang is repaired or CI is fully green.

### Explicitly Forbidden

- Any C2+ interface semantics; transfer, simulate, endpoint/side traversal, persistence, network format, machine write, GUI automation, LLM, inventory transaction, adapter expansion, or process contract.
- Any mining, collection, road, tunnel, FollowTask, survival, `HARD_PATH`, or `SOFT_SURFACE` behavior change.
- Any historical old-save hang diagnosis/fix, test retry mandate, or new client interaction.

### Verification and Stop Conditions

1. `./gradlew compileJava` passes.
2. `./gradlew runServer -Dalice.selftest.auto=true` produces a focused `INTERFACE_SNAPSHOT_SELFTEST PASS` covering all F1 assertions. Existing global timeout/143 may not be presented as full-suite success.
3. Source/diff review verifies `hasChunkAt` precedes target state reads, dead generic heuristic code has no remaining references, and generic formatter emits raw index facts only.
4. Documentation statements are checked against actual Git refs and accepted Windows evidence.
5. Stop and return to supervision if a new fixture, chunk-load behavior, a live caller, client-visible interaction change, or broader semantic requirement appears.

### Client Gate

No new Windows matrix is planned because this route must not change the scanner item interaction contract. If implementation reveals client-visible behavior change, stop before release and create a new `CLIENT_TEST_PENDING` matrix. The preceding C1 S1-S4 acceptance is not broadened.

## Supervisory Verdict

`CONDITIONAL_PASS`: the user explicitly authorized the stated F1-F4 maintenance scope. The route remains C1-only and deep research is not required. A new active plan may be published as `APPROVED_FOR_IMPLEMENTATION` only after the implementation-planner report is received and independently accepted by the supervisor; developer dispatch remains prohibited until then.
