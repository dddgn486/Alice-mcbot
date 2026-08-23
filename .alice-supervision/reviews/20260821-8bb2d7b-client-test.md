# Alice Supervisor Client-Test Review

- Review ID: `20260821-8bb2d7b-client-test`
- Reviewed implementation: `8bb2d7b1641c0b6e6063d4c78ae0357896fd0c90`
- Windows test baseline: `ba63d15b7dec9bf4887f099bd45e407af5e99a19`
- Plan ID: `20260821-interface-readonly-snapshot-v1`
- Verdict: CONDITIONAL_PASS
- Stage status: USER_ACCEPTED

## Evidence Reviewed

- User report and fixed-name Windows evidence directory: `D:\JAVA_projects\alice\.alice-supervision\client-tests\evidence\8bb2d7b\`.
- `evidence-report.md`: old superflat save entered normally; S1-S4 each marked PASS; no crash, hang, world mutation, repeated event, or bot task was reported during the scanner scenarios.
- Four non-empty PNG files use the required S1-S4 names. They cannot be visually inspected by the current review runtime, so visual claims remain user-provided evidence.
- Full Windows `latest.log` and `debug.log` are present. The Windows checkout resolves to `ba63d15b7dec9bf4887f099bd45e407af5e99a19`.
- `latest.log` lines 66-76 shows the old save loaded, fake-player recovery completed, and the human player connected. This is a non-reproduction observation only, not a root-cause finding for the earlier hang.
- `latest.log` lines 204-225 and 391-412 show `alice:interface_scanner` captures with `schema=v1`, `status=OK`, raw indexed slots, energy facts, and the explicit `【legacy Mek projection; 非 C1 通用事实】` boundary.
- `latest.log` lines 414-494 shows repeated non-block-entity scans with `status=NO_BLOCK_ENTITY` and no exception or world-mutation evidence.
- `latest.log` contains no Alice scan line mentioning `minecraft:diamond_shovel`; user reports its vanilla terrain and GUI behavior remains available.

## Architecture Review

The available client evidence supports the independent C1 scanner boundary: an Alice-owned item triggers server-side readonly capture; a known container/machine is observed as raw indexed facts; no-block-entity targets return the expected status; and the report states the vanilla diamond shovel remains outside the Alice scan hook. This does not grant C2/C3/C4, transfer, endpoint semantics, machine writes, LLM tools, bot task controls, or movement expansion.

The Mek `INPUT`/`OUTPUT` labels in the log are inside the explicitly labeled legacy Mek projection, not C1 generic snapshot facts. They are therefore not a C1 role-inference violation. The accepted evidence report explicitly records this distinction.

## Acceptance Conditions and Scope

1. The user selected `USER_ACCEPTED` after all four scenarios were reviewed.
2. S2 was corrected to state that generic C1 facts did not infer roles; observed `INPUT`/`OUTPUT` labels appear only in the explicitly marked legacy Mek projection.
3. SoftPathProbeTask entries in `latest.log` occurred before scanner evidence and are excluded from this acceptance. They do not show the scanner assigned a task and do not validate SOFT_SURFACE behavior.

No scanner scenario requires rerun. C1 client interaction/identity acceptance is closed within S1-S4. Existing maintenance findings F1-F4 in `.alice-supervision/reviews/20260821-8bb2d7b.md` remain a separate prerequisite before any next functional package.

## Verification Limits

- The current reviewer runtime cannot render PNG evidence; screenshot appearance and vanilla shovel interaction are accepted as user-observed evidence under the explicit `USER_ACCEPTED` decision.
- The scan log is duplicated for several interactions in the integrated-client log. The report says no repeated event was observed. This review does not classify it as a defect without a reproduced double use effect, but future work must not treat log duplication as evidence of intentional repeat semantics.
- `latest.log` includes unrelated SoftPathProbeTask execution before scanner testing. This confirms neither scanner task isolation nor any soft-path client behavior beyond the already frozen status.

## Next State

- `20260821-interface-readonly-snapshot-v1` is `USER_ACCEPTED` for its narrow C1 scanner interaction scope.
- Preserve the earlier old-save hang as an un-attributed observation; normal entry in this run does not close it.
- Do not release new functional work. Only the separate F1-F4 C1 maintenance plan may be prepared next, subject to its own review and user approval; C2+ expansion remains prohibited.
