# Alice Supervisor Route Review — Original Chest Bot Transfer A1.1

- Review ID: `20260821-original-chest-bot-transfer-a1-route-review`
- Baseline: `3644429d2d88e1bb6fd1eb24654d828d1451d7f8`
- Deep research task: `69c19c92-9709-4f3b-a9a4-1542fbc5a1b9` — settled success
- Planner task: `d135a8b1-df59-4013-9b34-bcd9ecc5ffc4` — settled success
- Research: `.alice-supervision/research/original-chest-bot-transfer-research-report-20260821.md`
- Planner route: `.alice-supervision/research/original-chest-bot-transfer-a1-implementation-plan-20260821.md`
- Verdict: NEEDS_USER_DECISION
- Stage status: DRAFT; no A1.1 active plan and no development authorization

## Adopted Findings

1. A1.1 may be planned as real inventory transport only for a fixed original single chest source, explicit bot, and fixed original single chest destination. Both endpoints must be loaded, same dimension, and explicitly identified by immutable dimension plus block position.
2. An end-to-end request is sequential composition, not a three-party atomic transaction: `source -> bot` and `bot -> destination` must each use `validate -> fresh pre -> simulate -> freshness check -> actual -> post delta`. A failed destination leg leaves the proven remainder at `bot_inventory`; it never re-extracts or claims rollback.
3. C1 remains raw readonly evidence. It cannot select roles, sides, write paths, or generic container support.
4. A1.1 must use an independent persistent request ledger, not `TaskExecutionRecord` or the existing `BotWorldData` summary. Current bot persistence stores main hand only, so it cannot itself prove restart recovery for a complete inventory or in-transit transaction.
5. The first entry must be a narrow explicit command, not a binding item. The binding tool proposed by the user is a legitimate later UX package, but it adds registration/packet/right-click/GUI-isolation/profile persistence surface and must not obscure C2 transaction proof.
6. Full/unspecified transfer, tags, component/NBT matching, partial success, multi-batch, deficit fulfilment, storage labels, owner/admin ACL, mod containers, fluids, generic capability, side semantics, direct endpoint transfer, LLM recovery, and automatic fallback work remain out of A1.1.

## Required Decisions Before Publishing an A1.1 Plan

All seven require user confirmation; absent confirmation keeps the verdict `NEEDS_USER_DECISION`.

1. Exact predicate: accept only default no-component/NBT vanilla stacks matched by exact `namespace:item`, rejecting stacks with components/NBT as `unsupported_item_components`.
2. Dimension: source and destination must be in the same dimension; cross-dimension transfer is rejected.
3. Bootstrap authority: use `permission level 2` only in a controlled/single-player experiment. A1.1 does not model, persist, infer, or enforce a bot owner. No general owner/admin/ACL is implemented; it remains a later dedicated policy package.
4. Restart policy: choose either (A) add narrow full bot-inventory persistence plus ledger reconciliation for `restart -> SUSPENDED -> manual takeover`, or (B) first implementation rejects/blocks a server stop while an active request exists and treats a restart as manual recovery with no automatic continuation. Recommendation: B, because A mixes full inventory persistence into the first transfer write package.
5. Manual takeover UX: first implementation exposes read-only request status plus explicit administrator abort/report only. It does not expose resume or finish-insert, because either is a new write command. Recommendation: accept.
6. Destination capacity: require full destination simulation before source extraction, then revalidate immediately before destination actual. A later capacity change suspends with item proven in bot inventory. Recommendation: accept.
7. Fixed timeouts: select phase no-progress, absolute request deadline, and maximum suspension duration. Recommendation: 200 ticks (10 seconds) per movement/write phase; 2,400 ticks (2 minutes) active deadline; 12,000 ticks (10 minutes) maximum suspension. Survival suspension does not advance the active deadline but does consume the separate suspension maximum. Values are experimental and must be logged.

## Developer Gate and Verification

After all seven decisions, this review may be converted into a new `APPROVED_FOR_IMPLEMENTATION` A1.1 plan. Only that new plan can allow a developer task. It must require:

- exact item/count, same-dimension single-chest fixture;
- independent SavedData ledger and in-transit replacement guard limited to TransferTask;
- `HARD_PATH` only; no change to mining/collection/roads/tunnels/soft movement;
- focused headless conservation/recovery fixtures listed in the planner report;
- `./gradlew compileJava`;
- `CLIENT_TEST_PENDING` Windows matrix before user acceptance.

Compile, fixture logs, and supervisor review are not client acceptance. No A1.2+ capability is implied by a pass.
