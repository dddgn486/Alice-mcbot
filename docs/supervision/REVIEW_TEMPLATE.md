# Alice Supervisor Review

- Review ID: `YYYYMMDD-short-commit`
- Reviewed commit: `<full SHA>`
- Plan ID: `<active plan ID or none>`
- Verdict: PASS | CONDITIONAL_PASS | BLOCK_FURTHER_EXPANSION | NEEDS_USER_DECISION
- Stage status: IMPLEMENTED | HEADLESS_VERIFIED | CLIENT_TEST_PENDING | CLIENT_VERIFIED | USER_ACCEPTED | NEEDS_REPLAN

## Evidence Reviewed

- Git diff and current HEAD.
- `docs/HANDOVER.md` and relevant R# decisions.
- Relevant design documents.
- User-supplied client evidence, if any.
- Active plan `Research Decision`, cited report paths, and version boundaries.

## Architecture Review

State whether the change stays in the correct layer and preserves frozen boundaries.

## Plan and Complexity Review

State whether the completed slice was appropriately sized. Define the next smallest safe slice or state why a user decision is required.

## Research Review

- Was the required shallow/deep research completed before implementation?
- Are adopted report conclusions traceable, version-bounded, and applied only within plan scope?
- Did an evidence conflict, version change, or repeated implementation failure require research refresh or a conservative downgrade?
- Does the proposed next package advance the mainline or an isolated lane without relying on an unverified blocked capability?

## Verification Review

Separate compile/headless evidence from client evidence. Never infer client acceptance from logs alone.

## Required User Client Tests

### Test Entry Review

- Entry type and semantic input contract:
- Isolation from normal task chains:
- Required observable evidence:
- Stop/recovery contract:
- Promotion boundary after user acceptance:

| Scenario | User observes | Expected result / log | Failure stop condition |
|---|---|---|---|
| | | | |

## Next State

- Write `CLIENT_TEST_PENDING` when user evidence is required.
- Only write `USER_ACCEPTED` after the user explicitly confirms the expected effect.
- If implementation may proceed, create the next `.alice-supervision/active-plan.md` from `ACTIVE_PLAN_TEMPLATE.md`.
