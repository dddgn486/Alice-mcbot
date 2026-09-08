# Alice Deep Research Report

- Topic: `m5-jump-and-m3-knockback-investigation`
- Date: `2026-08-26`
- Task source: `.alice-supervision/research/m5-jump-and-m3-knockback-investigation-task-20260826.txt`
- Scope: `Read-only research; does not authorize implementation.`

## 1. Projects and Versions / Commits

| Project | Repository | Version / commit | Version certainty |
|---|---|---|---|
| Alice | `/home/fb486/projects/alice` | Git current `f4f6ca6890f1b1dbc166cf32afd7725b8c764897`; working tree contains supervision/runtime files and current source also includes later F1/C-1 work; requested code comparison: `65f4863f6407d406d9417a41939ecf023e7644a8`, `ab510fdd4cc9c4ae4f73e5661b1b2b76fa8214d6`, `f4f6ca6890f1b1dbc166cf32afd7725b8c764897` | A: local Git objects/source; do not treat current dirty tree as a clean Windows build baseline |
| Minecraft/Forge | Alice Gradle configuration / local mapped jar | Forge `1.20.1-47.4.10`, Parchment `2023.09.03-1.20.1` | A: fixed project dependency; packet classes inspected from local mapped jar |
| Windows video evidence | `videos/2026-08-26 00-04-09.mp4` | File exists, `8,386,673` bytes; filesystem timestamp `2026-08-26 00:04:36 +0800` | C: metadata only; `ffprobe` unavailable and no frame extraction was performed, so this report does not claim to have watched or visually decoded the video |

## 2. Source Evidence

| Source file / revision | Class / function | Confirmed fact | Evidence level | Notes |
|---|---|---|---|---|
| `src/main/java/com/dddgn/alice/bot/FakeConnection.java:26-75` current source | `FakeConnection.send`, `broadcastToRealPlayers` | `send` forwards only `ClientboundMoveEntityPacket`, `ClientboundSetEntityMotionPacket`, `ClientboundTeleportEntityPacket`; `broadcastToRealPlayers` loops every `PlayerList` player, excludes `BotPlayer`, and sends to every same-level real player's `connection`; no distance/track-range/entityId validation | A | Static code fact; does not prove runtime packet delivery or packet contents |
| `src/main/java/com/dddgn/alice/bot/FakeConnection.java:45-48` | packet filter | `ClientboundMoveEntityPacket` is matched as its abstract base, therefore all its concrete position/rotation variants accepted by `instanceof`; code does not distinguish `hasPosition`, `hasRotation`, relative delta, or absolute teleport semantics | A | Fixed mapped packet class has `entityId`, relative fields, rotation flags, `hasPosition`, `hasRotation` |
| `src/main/java/com/dddgn/alice/bot/FakeConnection.java:71-74` | recipient loop | The same packet object is passed to multiple real-player connections; recipient is based only on non-bot + same `Level` object | A | No nearby/tracking filter; potential duplicate path remains an inference until runtime sends are logged |
| `src/main/java/com/dddgn/alice/bot/BotManager.java:71-83` | `spawn` | Creates `BotPlayer`, calls `placeNewPlayer(new FakeConnection(..., bot), bot)`, then `teleportTo` and sets SURVIVAL | A | The FakeConnection is attached to bot's `ServerPlayer.connection`; `placeNewPlayer` registration can itself send packets through that connection |
| `65f4863:src/main/java/com/dddgn/alice/bot/FakeConnection.java` | pre-P1 `send` | `send(Packet)` was empty; callback overload only calls success; no movement/motion/teleport broadcast | A | Good comparison baseline for jump regression; not a proof of all other behavioral equality |
| `ab510fd:src/main/java/com/dddgn/alice/bot/FakeConnection.java` | P1 sync change | Introduced bot field, three packet type checks in both send overloads, and all same-level-real-player broadcast | A | Exact implementation change associated with reported jump regression |
| `f4f6ca6` | comparison commit | Documentation-only F1 closure commit; FakeConnection behavior remains the `ab510fd` implementation | A | Current requested comparison does not show a later broadcast correction |
| `.alice-supervision/progress-snapshot-20260824.md:28-39,50-67` | rollback record | Client observation: `ab510fd` could reproduce player 1.5-block jump; reverting to `65f4863` restored normal jump; bot physical failure remained on `65f4863` | A/B | Project record of user experiment; no packet trace attached |
| `.alice-supervision/research/review-batch1-bot-20260825.md:46-53,156-177` | prior review | Identifies no distance/track filter, wide base-class filter, shared packet instance, and potential normal-tracker + manual-broadcast duplication; records jump mechanism as unresolved until packet evidence | A | Prior read-only review; corroborating, not independent runtime packet proof |
| `.alice-supervision/research/f1-hurt-false-fix-a-result-20260825.md:46-70,93-103` | F1/M3 server evidence | After F1 fix, generic/Zombie `hurtOk=true`, health falls `20→19→17.5`, but `d1/d2=(0,0,0)`; F2/F4/F5 and C1 residual consume remain; M3 client visibility is pending | A | Explicit evidence conflict: hurt success/health change does not establish knockback delta or client movement |
| `run/logs/latest.log:119-136` | local headless physics log | Current local run records `gHurtOk=true`, `mHurtOk=true`, health changes, but `d1/d2=0`; C1 residual logs show displacement values; no packet recipient/entityId logs | A | This is local run evidence, not the supplied Windows 00:00–00:04:40 log; do not merge timestamps |
| fixed mapped Forge jar `ClientboundMoveEntityPacket` | packet model | Has protected `entityId`, `xa/ya/za`, rotations, `onGround`, `hasRot`, `hasPos`; public accessors expose movement flags/values | A | Packet-level experiment must log entity ID and flags, not only Java class name |
| fixed mapped Forge jar `ClientboundSetEntityMotionPacket` | motion packet | Has `id`, encoded `xa/ya/za`; public `getId/getXa/getYa/getZa` | A | Required packet log fields |
| fixed mapped Forge jar `ClientboundTeleportEntityPacket` | teleport packet | Has `id`, absolute x/y/z, rotations, `onGround`; public ID/position accessors | A | Required packet log fields |
| `.alice-supervision/client-tests` glob | Windows evidence inventory | Existing client evidence folders contain older matrices; no task-specific M5 evidence-report/latest/debug log was found by filename search, only the referenced video under `videos/` | B/C | The task states Windows latest/debug time range, but those exact files were not present under a clearly named task-specific path in this checkout |
| skill catalog calls | required methods | `debugging-root-cause-analysis`, `test-coverage-matrix`, and `evidence-collection-standard` all returned “unknown or no longer available” | A | Required by task to report honestly; no `skill_usage` is claimed or fabricated |

## 3. Model or Execution Comparison

### 3.1 Timeline and Git contrast

| Point | Evidence | What it establishes | What it does not establish |
|---|---|---|---|
| `65f4863` (`2026-08-23 19:50:24`) | FakeConnection drops packets; progress snapshot says jump normal, bot physical failure remains | Jump regression is absent in this comparison baseline; bot physical issue predates `ab510fd` | Does not prove every bot sync path was correct or that no other change affected physics |
| `ab510fd` (`2026-08-24 00:24:48`) | Adds manual broadcast of three packet classes | Change point is consistent with jump regression introduction | Does not identify whether cause is duplication, wrong recipient, packet reuse, or packet type |
| Windows observation | Bot generated around 00:00:49, 00:01:46, 00:04:12; cleared around 00:01:05, 00:03:33, 00:04:33; jump reported while bot exists and normal after clear | Temporal correlation between bot PlayerList presence and jump symptom | Correlation is not causation; no packet receive log proves causal packet |
| F1-fixed M3 server | hurt returns true and health drops, delta remains zero | Server-side evidence conflict: damage accepted but no knockback vector observed | Does not prove FakeConnection caused zero delta; does not prove client-only sync failure |

### 3.2 Is the broadcast sufficient to explain the 1.5-block player jump?

**Confirmed facts:**

1. `ab510fd` is the first requested comparison commit that forwards bot movement/motion/teleport packets from a fake bot connection to real players.
2. The recipient filter excludes `BotPlayer` instances, so a normal `ServerPlayer` is not intentionally excluded; it sends to every real same-dimension player, including the observer itself if the observer is in the PlayerList.
3. It has no entity ID check. The implementation trusts that every packet received by this FakeConnection belongs to the associated bot. This is normally expected for a bot connection, but it is not asserted or logged.
4. It has no tracking/distance filter, so a same-dimension player outside ordinary tracking range can receive bot packets.
5. It uses a shared `Packet<?>` instance for every recipient. Whether Minecraft packet encoding is safely immutable/reusable across connections must be verified at runtime or against fixed implementation; the current code has no guard or evidence.
6. The normal `ServerEntity`/tracking route may also deliver bot packets to tracked players. The current code does not log whether manual forwarding duplicates a normal route packet.

**Inference:** The broadcast is a credible causal candidate for the jump regression because it is the only code change between the good/bad comparison that changes bot movement packet routing, and it has four risk surfaces (wide recipient set, no tracking filter, wide packet base type, possible duplicate/shared-instance delivery). However, source inspection alone cannot tell whether the player's client received a bot packet at the exact jump tick, whether it had the player's entity ID, or whether the client applied it to the player's entity. Therefore the exact jump mechanism remains **unconfirmed**.

### 3.3 Is M3 the same broadcast problem?

The evidence currently separates at least two layers:

- **Server hurt layer:** F1-fixed server logs show `hurtOk=true` and health decreases. The same log reports `delta=0` immediately after both generic and Zombie attacks. This is an actual server-side missing-knockback observation, not merely a client rendering failure.
- **Client sync layer:** Even if a later correction produces nonzero server delta/position, a fake connection can hide it from the client unless a valid movement/motion packet reaches the observer. Thus FakeConnection can explain a client-visible M3 failure when server state changes, but it cannot explain the current `delta=0` server observation.
- **Historical baseline:** M3/bot physical failure existed on `65f4863`, where FakeConnection dropped all packets. That proves the new `ab510fd` broadcast is not the sole root cause of the original M3 failure.

**Conclusion:** M3 and M5 are independent at the currently evidenced level. M3 has an unresolved server physics cause (hurt succeeds but knockback delta is zero) plus a separate client synchronization risk. Do not label M3 “fixed by broadcast” or “only a packet problem.” `BOT_PHYSICS_C1_CONSUME` records residual delta consumption; it is not proof that hurt generated knockback and must not be used as M3 PASS evidence.

### 3.4 Possible self/incorrect recipient and duplicate paths

- **Self recipient:** The explicit loop excludes only `BotPlayer`; it intentionally includes ordinary real players. The bot's own FakeConnection is not in the real-player branch. There is no source evidence that the fake connection itself receives its own forwarded packet.
- **Wrong entity ID:** No code rewrites packet ID. If the packet object came from the bot's connection correctly, its ID should be the bot ID; but no assertion logs the ID, so this is unverified.
- **Wrong recipient:** Same-dimension real players are all recipients, not only tracking players. This is confirmed static behavior. Whether the observer in the reported video was in that set is not recorded in the available files.
- **Duplicate packet:** A manual forward can coexist with normal tracking delivery. This is a credible hypothesis, not a confirmed runtime fact; no packet send/receive counts exist.
- **Packet class overmatch:** `ClientboundMoveEntityPacket` base matching includes pure rotation and position/rotation variants. This is confirmed code behavior; whether a particular variant contributed to jump requires packet fields and tick evidence.

## 4. Transferable Principles

1. **Separate server state from client presentation.** A nonzero server delta/position plus absent client packet is a sync failure; `hurtOk=true` plus server `delta=0` is a server physics failure. These are distinct assertions.
2. **Correlate every forwarded packet.** At minimum record `corr`, server tick, packet class, `entityId`, movement flags/encoded values, source bot ID, recipient player UUID/name/entity ID, dimension, and whether the normal tracker also sent a packet in the same tick.
3. **Use a clean good/bad/current contrast.** The `65f4863 → ab510fd → f4f6ca6` sequence is useful only when Windows build SHA and world/test scene are recorded; do not infer mechanism from commit adjacency alone.
4. **Make recipient policy observable.** A same-level loop is not equivalent to vanilla tracking. Before any implementation decision, compare the intended recipients to `ServerEntity` tracking recipients and identify duplicate delivery.
5. **Treat client video as behavioral evidence, not packet evidence.** A video can show a symptom and timing but cannot establish packet entity ID, duplicate count, or recipient. If extraction tools are unavailable, report metadata only.

## 5. Non-transferable Risks and Unknowns

- The available Windows `latest.log`/`debug.log` pair for the specified 00:00:10–00:04:40 run was not found under a task-specific path in this checkout. Existing older evidence logs must not be substituted without proving run identity.
- The supplied MP4 exists, but `ffprobe` is unavailable and no frame extraction was performed. I cannot claim to have watched the video or verify visual frames.
- No packet capture or packet send/receive observer records `entityId`, recipients, tick, packet subtype, or duplicate count. Therefore the exact M5 mechanism is not closed.
- No direct server trace records a player jump's position/velocity alongside bot broadcasts. A temporal relation in the user report remains correlation only.
- No packet-level evidence proves that a bot packet was decoded as the real player's entity. If such evidence appears, it would be a severe protocol/ID conflict; until then, do not claim it.
- Current local `run/logs/latest.log` is a separate headless run: it shows F1 `hurtOk=true` and delta zero, but cannot substitute for the stated Windows run.
- `FakeConnection.send(Packet, PacketSendListener)` broadcasts then invokes callback success. Callback success only acknowledges the fake send path; it is not client receipt.
- Same Packet instance reuse may be harmless if packet encoding is immutable and per-connection safe, or harmful if encoding/consumption mutates state; no fixed runtime experiment was run in this read-only task.
- The current tree has F1 reflection clearing `spawnInvulnerableTime` and C-1 implementation artifacts; this investigation does not attribute M5 to those later changes.

## 6. Smallest Alice Plan and Client Matrix

### 6.1 Minimum next evidence package (no implementation authorization)

1. **Build identity:** run each contrast from clean, recorded SHA `65f4863`, `ab510fd`, and current `f4f6ca6`-descendant; record Windows client/server log start times and world hash/scene.
2. **Server packet observer:** in an approved read-only diagnostic seam or test harness, record each bot-originated movement packet at send boundary with `corr`, `serverTick`, source bot UUID/entity ID, concrete packet class, packet entity ID, `hasPosition`, `hasRotation`, `xa/ya/za`, absolute teleport coordinates, and `onGround`.
3. **Recipient log:** record every target connection's player UUID/name/entity ID, dimension, distance, same-level status, and whether the packet came from manual FakeConnection forwarding or normal `ServerEntity` tracking. Count per `(tick, packet entityId, recipient)`.
4. **Client receive evidence:** on Windows, capture the client's inbound packet trace if available, or a narrow instrumented receive log, with the same packet fields and client tick; preserve `latest.log`, `debug.log`, and the exact video/screenshot evidence.
5. **M5 scene:** reproduce the fixed fence + block + slab jump scene with bot absent, `65f4863`, `ab510fd`, and current; hold all other variables constant. Record player position/velocity/onGround before and after each jump and bot spawn/clear ticks.
6. **M3 server/client split:** use a controlled attack after F1 initialization. Record pre/post bot health, `getDeltaMovement`, position, `hurtOk`, attack source, server tick; separately record observer client's bot movement/motion packet and rendered position. A server `delta=0` result stops the sync-only explanation.
7. **No code change in this report:** the observer/harness itself requires supervisor scope approval; this report recommends evidence fields only and does not add the observer.

### 6.2 Falsifiable hypotheses

| ID | Hypothesis | Falsifier | Minimal targeted test |
|---|---|---|---|
| H1: duplicate/wide broadcast causes M5 | During the bad run, the observing player's client receives two or more bot movement/teleport packets for the same `(tick, entityId)`, or receives a packet outside tracking range that is absent in good baseline; removing the extra delivery in a controlled comparison removes the jump | Exactly one correctly addressed bot packet per tick, no duplicate/out-of-range delivery, and jump persists with same server player kinematics | Compare `65f4863` vs `ab510fd` with packet send+receive logs; count per recipient/tick/entity ID; hold scene fixed |
| H2: wrong entity ID or packet decode targets player | A forwarded packet has `entityId == real player's entity ID`, or client receive trace shows bot packet applied to player entity; jump disappears when IDs are validated | Every packet ID equals bot entity ID, client applies it only to bot, and jump persists | Log packet ID at FakeConnection send, real connection receive, and client handler; compare against bot/player IDs |
| H3: shared Packet instance encoding race | Sending the same packet object to multiple recipients produces differing encoded fields, recipient-dependent IDs, or extra decode/handler effects; fresh per-recipient encoding removes divergence | Encoded bytes/decoded fields are identical for all recipients and no duplicate/ordering difference exists | Use two real recipients (or a capture harness), log decoded packet fields and object identity per send; compare shared vs per-recipient diagnostic mode only if separately authorized |
| H4: M3 server knockback is absent independently of sync | After `hurtOk=true`, server `deltaMovement` remains zero and position does not change before any S2C packet is sent; M3 fails even with packet forwarding disabled | Server delta/position changes but client does not, indicating sync-only failure | Headless controlled hurt with packet boundary timestamp; compare server pre/post and client receipt/render |
| H5: M3 is only client sync after later server fix | Server delta/position is nonzero and motion/position packet is missing or reaches no observer; client stays stationary; restoring a valid packet path makes client move | Server delta remains zero, or client receives/applies valid packet yet stays stationary | Separate server state log and client packet receive/render log; never infer from `hurtOk` alone |

### 6.3 Client matrix

| Scenario | Good/bad/current | Required evidence | Stop condition |
|---|---|---|---|
| M5-A bot absent | all | player position/velocity/onGround; video or screenshot | Stop if baseline jump is abnormal; do not investigate bot broadcast until baseline is valid |
| M5-B bot present, no jump action | `65f4863`, `ab510fd`, current | bot spawn tick, player packet receive counts, player position/velocity | Stop on crash, map hang, unexpected world mutation, or missing run identity |
| M5-C fixed fence+block+slab jump | all | player movement timeline + packet entity IDs/recipient/tick | Stop if packet identity cannot be established; mark mechanism unresolved |
| M5-D bot clear | all | clear tick, subsequent jump timeline and packet counts | Correlation only; no causal closure without packet data |
| M3-A server hurt | current | `hurtOk`, health, delta, position, source, tick | If `hurtOk=true` but delta=0, retain server-physics conflict and do not call sync fix |
| M3-B client view | current | observer receive packet class/id/fields/recipient/tick and rendered bot displacement | If server moves but client does not, classify sync conflict; if server does not move, classify server physics conflict |
| M3-C `BOT_PHYSICS_C1_CONSUME` | current | residual and actual movement fields | Never count this log alone as knockback evidence |

## 7. Adoption and Freshness Notes

- Evidence confidence: **A** local source and fixed Git diffs; **B** prior project rollback record and fixed packet API inspection; **C** supplied Windows symptom/video metadata without packet trace.
- Assumptions that invalidate this report: a different Windows build than the three requested revisions; a different server/client protocol; a task-specific latest/debug log later being proven to contain packet-level evidence not available during this investigation; or a runtime observer changing packet timing.
- Conclusions that should not be adopted without new evidence: “FakeConnection definitely makes the player jump,” “M3 is only a client sync problem,” “M3 is fixed because `hurtOk=true`,” “C-1 residual consumption proves knockback,” or “same-level recipients are equivalent to vanilla tracking recipients.”
- Parallel work that remains safe: read-only packet API/source inspection, evidence collection planning, independent server-side M3 hurt/knockback fixture design (without implementation), and Windows log/video inventory. Do not modify FakeConnection or active plan from this report.

## 8. Delivery Boundary

本报告不构成实现授权，不修改 `.alice-supervision/active-plan.md`，不标记 M3/M5 通过，也不修改客户端状态。只有 Alice 项目监督员可以审核证据、决定是否建立新的验证/实现计划并取得用户批准。

**本报告不构成实现授权。**
