# Alice Client Test Matrix

- Commit: `8bb2d7b1641c0b6e6063d4c78ae0357896fd0c90`
- Plan: `20260821-interface-readonly-snapshot-v1`
- Status: USER_ACCEPTED
- Scope: independent scanner item only; C1 snapshot semantics are primarily server/headless reviewed.
- Client execution status: user accepted S1-S4 after normal entry into the old superflat save. The earlier old-save hang is retained as an un-attributed observation; this run does not establish that the prior issue is resolved.
- Evidence source: copy `latest.log` and `debug.log` directly from the Windows client run that executes this matrix. Keep the complete files if a hang, crash, exception, or unexpected interaction occurs.
- Windows evidence folder: `D:\\JAVA_projects\\alice\\.alice-supervision\\client-tests\\evidence\\8bb2d7b\\`. It is pre-created locally, ignored by Git, and must not be committed.
- Simplified reporting: fill in `evidence-report.md` in that folder; copy the actual evidence over the reserved names listed there. Do not send per-scenario details in the chat.
- Minimum normal-run evidence: `evidence-report.md`, `latest.log`, `debug.log`, `S1-item.png`, `S2-scan.png`, `S3-no-block-entity.png`, `S4-vanilla-shovel.png`.
- Extra evidence only on hang/crash: `hang-client-thread-dump.txt`, `hang-server-thread-dump.txt` when available.

## Preparation

1. Confirm the Windows checkout contains the scanner implementation commit `8bb2d7b` and is at current shared baseline `ba63d15` or a descendant that does not modify scanner behavior.
2. Start the Forge client and enter a disposable or known-safe test world. Record whether player control is reached normally.
3. Before testing, preserve the Windows client run start time and identify that run's `latest.log` and `debug.log` paths for later collection.
4. Obtain the item with `/give @s alice:interface_scanner`.
5. Keep a normal `/give @s minecraft:diamond_shovel` for isolation tests.
6. Do not run `/alice selftest`, mining, follow, road, soft-path, or any bot command during this matrix.

## Scenario 1 - Item identity and resources

- Action: inspect `alice:interface_scanner` in inventory and hand.
- Expected:
  - Chinese name is `接口扫描器` under zh_cn; English is `Interface Scanner` under en_us.
  - Appearance uses the diamond-shovel model/texture.
  - It is an Alice item, not a vanilla shovel, and has no shovel mining/terrain behavior.
- Record: fill the S1 section of `evidence-report.md`; replace `S1-item.png` with the screenshot.
- Fail if: missing/purple model, untranslated key, or it behaves as a normal shovel.

## Scenario 2 - Known container or machine

- Setup: place a chest with a known item/count; optionally use a known Mek machine for manual projection inspection.
- Action: right-click it while holding `alice:interface_scanner`.
- Expected:
  - Normal GUI does not open for this click.
  - Player receives `[alice] 已扫描 ... → 见日志`.
  - Log key contains `接口扫描(alice:interface_scanner)` and `schema=v1 ... status=OK`.
  - Chest contents and machine state do not change.
  - Slot output uses indices/raw contents and does not claim input/output/energy role semantics.
- Record: fill the S2 section of `evidence-report.md`; replace `S2-scan.png`; keep the complete `latest.log` and `debug.log`.
- Fail if: GUI opens, contents change, scan throws, or role guesses appear as generic C1 facts.

## Scenario 3 - Non-block-entity target

- Action: right-click stone/dirt with `alice:interface_scanner`.
- Expected:
  - No block is changed and no dirt path is created.
  - Scan completes without exception.
  - Log projection reports `status=NO_BLOCK_ENTITY` and no interfaces.
- Record: fill the S3 section of `evidence-report.md`; replace `S3-no-block-entity.png`; keep the complete `latest.log` and `debug.log`.
- Fail if: block changes, path forms, task starts, or exception occurs.

## Scenario 4 - Vanilla diamond shovel isolation

- Action A: use `minecraft:diamond_shovel` on dirt where vanilla can create a path.
- Action B: use the same vanilla shovel on a chest/machine.
- Expected:
  - Action A retains vanilla shovel behavior.
  - Action B retains normal vanilla/mod interaction, including opening the GUI where applicable.
  - Neither action emits `接口扫描(alice:interface_scanner)` or an Alice scan message.
- Record: fill the S4 section of `evidence-report.md`; replace `S4-vanilla-shovel.png`; note the absence of matching scan logs in the report.
- Fail if: Alice intercepts either action or vanilla behavior is cancelled.

## Isolation and Stop Conditions

- No scenario may spawn/assign a bot, move a bot, alter bot task state, invoke mining/collection/roads/FollowTask/SOFT_SURFACE, or mutate a machine inventory.
- Stop immediately on map-load hang, crash, inventory change, unexpected world mutation, repeated event firing or task assignment. Preserve the complete Windows-run `latest.log` and `debug.log`; for a hang also collect client and integrated-server thread dumps when possible. Do not retry in a valuable world.
- A normal map entry is recorded only as a non-reproduction observation. It does not close, attribute, or repair the earlier old-save hang.

## Expected Log Keywords

```text
接口扫描(alice:interface_scanner)
schema=v1
status=OK
status=NO_BLOCK_ENTITY
【legacy Mek projection; 非 C1 通用事实】
```

The legacy Mek line is expected only for a supported Mek block entity.

## User Decision

Reporting rule: after testing, fill only `evidence-report.md` in the evidence folder and replace the reserved evidence files. The supervisor reads those files directly; no per-scenario chat report is required.

- User decision: `USER_ACCEPTED` (explicit confirmation after S1-S4 review).
- Acceptance scope: independent C1 scanner item identity, readonly chest scan, no-block-entity scan, and vanilla diamond-shovel isolation only.
- Exclusions: legacy Mek projection is not generic C1 role evidence; prior old-save hang remains un-attributed; F1-F4 maintenance findings remain open.

Please return one explicit result:

- `USER_ACCEPTED`: all four scenarios match expectations.
- `NEEDS_FIX`: reproducible implementation defect; include scenario and evidence.
- `NEEDS_REPLAN`: the interaction/product behavior should change.
- `NEEDS_DISCUSSION`: evidence is ambiguous.

- Prior user result: `NEEDS_FIX` / test blocked before scanner scenarios.
- Prior evidence/notes: user reported an old-save load hang at 2026-08-21 03:29. Last visible lines: `假人已生成(玩家化): name=tango pos=17, -59, 18`, `已从世界存档恢复假人: name=tango pos=(17, -59, 18)`, `SELFTEST 待命(手动 /alice selftest 触发)`. No exception stack, thread dump, new-world control or scanner interaction evidence was retained. Do not attribute root cause.
- Resume note: user reports normal map entry after today's restart. Scanner scenarios 1-4 are now authorized for this independent C1 package. The earlier load observation remains open; obtain `latest.log` and `debug.log` from the Windows client run used for this matrix and report any recurrence before continuing.
