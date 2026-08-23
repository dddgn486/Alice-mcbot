# Alice Agent Instructions

This repository uses a supervisor-controlled work-package protocol. Do not begin implementation based only on a conversational request when it can change architecture, behavior, or client-visible results.

## Before Implementation

1. Run `./tools/work-session-start.sh` from the repository root.
2. Read the printed `.alice-supervision/active-plan.md`, `docs/HANDOVER.md`, every design document named by the active plan, and every report named under the plan's `Research Decision`.
3. Only implement the active plan's Allowed Scope. If deep research is marked required but not completed, the plan is missing, not `APPROVED_FOR_IMPLEMENTATION`, or needs expansion, stop and ask the Alice project supervisor to replan.
4. `--allow-no-plan` is only for user-authorized maintenance that cannot change project direction. Record the exception in `docs/HANDOVER.md`.

## Non-Negotiable Boundaries

- `MineTask`, `DropCollectionTask`, and ordinary mining remain `HARD_PATH`.
- Experimental `SOFT_SURFACE` does not enter mining, collection, roads, tunnels, fluids, or escape behavior without an explicit approved plan and the required client evidence.
- `SEARCH_LIMIT` is not `UNREACHABLE`; it cannot authorize tunneling.
- Compiling, headless logs, and supervisor review do not replace user client acceptance.

## At Handoff

1. Run the plan's required checks; code changes require `./gradlew compileJava` at minimum.
2. Update `docs/HANDOVER.md` with the actual revision, completed work, evidence, limitations, and next safe step.
3. Commit and run `git push origin master`.
4. Run `./tools/session-complete.sh` to create the supervisor review packet.

The full protocol and templates are in `docs/SUPERVISION_PROTOCOL.md` and `docs/supervision/`.
