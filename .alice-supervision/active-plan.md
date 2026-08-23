# Alice Active Work Plan

- Plan ID: `20260824-soft-surface-physics-observation-p1-v1`
- Status: APPROVED_FOR_IMPLEMENTATION
- Baseline: `19af345`
- User approval: explicit approval received (2026-08-24); user approves P1 (physics observation and disturbance recovery) from soft/hard boundary research
- Implementation plan: `.alice-supervision/research/soft-surface-p1-implementation-plan-20260824.md` (planner report, supervisor accepted)

## Objective

Extend SOFT_SURFACE observation and disturbance recovery for isolated probes (SoftPathProbeTask/FollowTask), preparing for future player-like physics while preserving hard-path mining/pickup/road/tunnel stability.

## Allowed Scope

### Observation Extensions (new fields + logs)
- Record `deltaMovement` (velocity), `onGround`, `fallDistance`, support topY, collision direction (horizontalCollision/verticalCollisionBelow)
- Horizontal/vertical deviation from planned segment
- Entity contact, damage/knockback velocity changes (velocityDisruption flag)
- Log keywords: `soft_phys_obs`, `soft_phys_disruption`, `soft_phys_revalidate`, `soft_phys_replan`

### Disturbance Recovery (settle → revalidate → replan)
- Deviation detection: hDev >2.0, vDev >1.5, noProgressTicks >20, velocity disruption (horizontal Δ >0.3, vertical Δ >0.5)
- REVALIDATE: re-check current segment geometry (canTraverse/Ascend/Descend), support topY, collision; if blocked → REPLAN
- REPLAN: call `SurfacePathfinder.find` from current `bot.blockPosition()`, MAX_REPLAN=3, stable failure codes (`soft_phys_max_replan`, `soft_phys_replan_no_path`, `soft_phys_replan_search_limit`)
- Never use `setPos` correction; never auto-fallback to HARD_PATH

### Files to Modify
- `SoftPathProbeTask.java`: add observation fields, revalidateSegment(), replanFromCurrent()
- `FollowTask.java`: add observation, immediate replan on collision/velocity disruption (preserving 10-tick periodic replan priority)
- `SoftMovementPrimitive.java`: optional observation callback in settle (if needed)

### Verification
- Server: new `SoftPhysicsObservationTest.java` focused fixture (observation fields non-null, settle no setPos, revalidate called on deviation, replan changes path, velocity disruption triggers, MAX_REPLAN protection); existing suites PASS
- Client: Windows test matrix P1-G1~G13 (flat/slab/stair/drop, wall/corner/entity collision, jump/low ceiling, knockback, support dynamic, follow collision replan, hazards, MAX_REPLAN protection, isolation)

## Explicitly Forbidden

- Do not touch MineTask, DropCollectionTask, roads, tunnels, fluids, escape, HARD_PATH execution
- Do not change PathExecutor, BotMiner, PlaceTask, ContinuousRoadCurve, TunnelObstaclePolicy
- Soft failure never authorizes digging/tunneling (SEARCH_LIMIT != UNREACHABLE)
- Do not extend to SoftMoveProbeTask (8-grid flat probe remains unchanged)
- Do not make P2-P5 changes (primitive extensions, independent SoftTravelTask, task-chain integration)

## Stop Conditions

Stop and return for replanning if:
- Observation fields need cross-tick persistence (List<PhysicsObservation> buffer)
- Settle chain conflicts with BotSession SurvivalSystem interrupt priority
- Replan triggers A* search blocking >50ms
- MAX_REPLAN=3 insufficient or too permissive
- velocityDisruption thresholds misfire
- P1 observation requires changing PathExecutor/HARD_PATH
- Any client scenario P1-G1~G13 fails (teleport/wallhack/crash/deadlock)

## Background Research (completed)

- Alice movement-system map: `.alice-supervision/research/alice-movement-system-inventory-20260824.md`
- Soft/hard boundary deep research: `.alice-supervision/research/bot-pathfinding-soft-hard-boundary-research-20260824.md`
- P1 implementation plan: `.alice-supervision/research/soft-surface-p1-implementation-plan-20260824.md`
