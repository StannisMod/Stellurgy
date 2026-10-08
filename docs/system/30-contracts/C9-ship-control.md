---
id: C9
covers: tier-2 ship pilot control — input delivery to the linked flight computer, seat binding & link lifecycle across assembly/placement/destruction/relog/crossing, FA autopilot state
confidence: high
owner-subsystem: none — tier-2 VS integration; code in block/BlockPilotSeat, tile/TilePilotSeat, tile/TileAdvancedFlightComputer, entity/EntityDummy, tile/TileRocketAssemblingMachine, client/KeyBindings, space/CrewTransfer, mixin
see-also: [C8 (crew any-attitude — boarding/aboard-ness; cross-referenced, never restated)]
---

# C9 — Ship control contract (tier-2 ships), clauses SHIPCTL-1..19

**RATIFIED by the maintainer 2026-07-20** after a clause-by-clause review (rulings R1-R22 are all
the maintainer's). `SHIPCTL-n` are permanent anchors: never renumber, never reuse. **Every fix to
this mechanic names the clause it restores.**

**Terms.** **seat block** = `stellurgy:pilotSeat` (`BlockPilotSeat` + `TilePilotSeat`).
**seat position** = the seat's `BlockPos` in the frame it occupies: WORLD while UNASSEMBLED, ship
SUBSPACE once ASSEMBLED. **mount dummy** = `EntityDummy`, the WORLD-frame entity the player rides.
**the binding** = dummy→seat: `SEAT_POS` via `EntityDummy.setSeatPos` `[V]`; frame is implicit —
whatever the seat had at bind time. **the link** = seat→computer: a relative offset on the seat tile
(`TilePilotSeat.linkToFlightComputer`, resolved by `getFlightComputerPos`) `[V]`, written at assembly and by the
build probe. **the linked computer** = the `TileAdvancedFlightComputer` at
`TilePilotSeat.getFlightComputerPos()` — the craft's sole command authority. ("The ticking computer"
is retired: ALL AFCs tick, so the phrase denotes nothing on a multi-AFC craft.) **boarding paths**:
P1 = world-frame right-click (`BlockPilotSeat.onBlockActivated`) `[V]`; P2 = probe/subspace
bind. **INPUT** = the momentary per-tick command (the AFC's `pilotInput`). **FA SETTING** =
{flight-assist mode, engaged autopilot, cruise setpoint} — see SHIPCTL-18.

## Clauses

**Delivery**
- **SHIPCTL-1** `[A][BEH]` On an ASSEMBLED, loaded ship, a seated pilot's input reaches **the computer
  his seat is LINKED to**, and that computer's next tick consumes it. The linked computer is the
  craft's **sole command authority**, and it coincides with the assembly anchor and the durable
  ship-id bearer (`TileRocketAssemblingMachine.java`) `[V]` — the coincidence is
  contractual. Delivery to any other computer, or to none, is a violation **even when a seat-side
  guard reports success while the linked computer's `pilotInput` stays null**.
- **SHIPCTL-2** `[A][BEH]` On an ASSEMBLED ship a non-zero pilot input produces motion **corresponding
  to the input in axis and sign**, within the input-latency window (*tunable*). **Control leg,
  mandatory**: a comparable zero-input window on an otherwise-at-rest ship with no retained FA
  setpoint shows no such motion. The axis +
  sign correspondence holds, but the per-axis MAGNITUDE is geometry+fuel-limited (C10 STAT-7b, a
  per-axis clean authority model); a build that cannot cleanly deliver an axis is WEAK on it, never
  forced to yaw when the pilot presses a linear axis. External ship-on-ship push (VS-level) is
  outside this clause. The magnitude is this hull's clean authority in
  the signed direction (`ship-flight-model` MECH-SFM-08), and a hull with no actuator for an axis
  answers that axis with NO motion — the "control leg" of this clause is then satisfied trivially,
  which is exactly why the clause's positive leg must be arranged on a hull that has the actuators.
- **SHIPCTL-3** `[A][SYS]` A diagnostic of the named instrument family (`[FF-TRACE/*]`, `/stellurgytest` probe
  JSON) reporting the chain "resolved" must compute that report **from the same resolution objects
  the delivery path uses** — never a parallel re-resolution. A resolved-looking report that cannot
  move the ship is a false instrument, itself a defect. FOR: SHIPCTL-2.

**Binding & link**
- **SHIPCTL-4** `[A][BEH]` After any boarding path completes, **exactly one dummy per (world, seat)** is
  bound — enforced at EVERY dummy spawn site (right-click reuse, probes, crew transfer). On an
  ASSEMBLED ship the chain dummy→seat→link resolves the linked computer of that same ship
  (SHIPCTL-7); on an unassembled craft resolution is legitimately absent (SHIPCTL-9). Seat identity
  = the `TilePilotSeat` tile the binding resolves to in the seat's CURRENT frame; a binding
  resolving no seat tile is itself a violation.
- **SHIPCTL-5** `[A][BEH]` The binding and the link name the seat **at the coordinates and in the frame
  it currently occupies**, across **EVERY craft relocation**: (i) the world→world lift-gap
  translation (`assembleRocket`) `[V]`, (ii) the world→subspace relocation in the same method `[V]`,
  (iii) any future disassembly (SHIPCTL-13). A PRE-lift binding re-expressed for (ii) alone is off
  by the lift — a violation. Re-expression may be achieved by REBINDING (the crossing does,
  `CrewTransfer.java` `[V]`) — the clause pins the OUTCOME, not the mechanism.
- **SHIPCTL-6** `[A][BEH]` At most one dummy is bound to a seat at a time, and a riderless dummy never
  writes a **non-null** input to a flight computer — the single sanctioned riderless write is the
  **one-shot release** (clearing its own linked computer's input on the dismount edge,
  `EntityDummy.java` `[V]`), and it never overrides input attributable to a currently-seated
  pilot of the same computer.
- **SHIPCTL-7** `[A][BEH]` The control chain never resolves a seat, dummy or computer of a **different
  ship**; ship identity is the **durable ship id**. A proximity reseat is a breach; **ruling
  R12: a world-distance fallback in `isPilotOf` is a breach** — the exact-binding leg
  (`TilePilotSeat.java`) is the only legitimate resolution.

**Assembly stage**
- **SHIPCTL-8** *(R1)* `[A][BEH]` A player who boards BEFORE assembly STAYS SEATED and controls the ship
  IMMEDIATELY after a successful assembly — no re-seat. "Immediately" runs from the physics object
  going live. Aboard-ness is CREW-C1/C4's; this clause owns delivery. Falsifier: the red repro
  `VSPreAssemblyBoardingPilotControlTest` (both cells). Pinned by `VSPreAssemblyBoardingPilotControlTest#aPilotWhoRightClickedTheSeatBeforeAssemblyCanFlyTheShip`, `VSPreAssemblyBoardingPilotControlTest#aPilotBoardedByProbeBeforeAssemblyCanFlyTheShip`.
- **SHIPCTL-9** *(R2+R8)* `[A][BEH]` (`VSUnassembledCraftTakesNoOrdersTest`, client, both directions in
  one run) An UNASSEMBLED ship does NOT accept pilot input, and the refusal is
  SURFACED **server-side at mount time**: seating on an unassembled craft answers with an action-bar
  message ("ship not assembled — assemble it to fly"). Feedback for a pilot already seated when the
  craft becomes unassembled is SHIPCTL-13's. Pinned by `VSUnassembledCraftTakesNoOrdersTest#aCraftThatNeverBecameAShipTakesNoOrdersFromItsPilot`.
- **SHIPCTL-10** `[A][BEH]` Across any assembly-state transition (assembly, teardown by crossing,
  destruction, unload), a pilot INPUT tick is at worst **dropped** — no INPUT survives the
  transition or its rider. A non-zero INPUT still steering after key release + transition
  completion is a violation. Motion under a retained FA SETTING is NOT a violation (SHIPCTL-18).

**Independence axes**
- **SHIPCTL-11** `[A][BEH]` Every clause holds at any attitude the ship can OCCUPY (upright through
  fully inverted; exactly-180° is arranged by free spin — the controller cannot be commanded into
  the axis-angle singularity) and at any velocity within the controllable envelope. Attitude and
  velocity are never legitimate discriminators.
- **SHIPCTL-12** `[A][BEH]` Every clause holds at any world position at which the ship is LOADED and
  physics-ticking in a flyable world — in particular **away from its assembly site** (where a bind-time coincidence would mask a defect)
  and **after arbitrary translation/rotation since binding time** (staleness across motion is the
  real second axis). Carve-outs by design: the hyperspace park ignores flight input (transit
  contract's domain); entry/descent trigger bands behave per their own contracts.

**Lifecycle**
- **SHIPCTL-13** *(R3+R15; PRECONDITIONED — no tier-2 disassembly mechanic exists yet `[V][BEH]`)* `[A]`
  WHEN disassembly exists: disassembling with a seated pilot leaves him SEATED on the now-loose
  seat; at the disassembly moment he receives a message ("ship disassembled — controls offline");
  while unassembled his input is refused (SHIPCTL-9 semantics); re-assembly restores control with
  no re-seat. The binding follows the seat into the world frame (SHIPCTL-5 (iii)).
- **SHIPCTL-14** *(R4+R13+R14)* `[A][BEH]` Destroying an occupied pilot seat — or the linked computer —
  by any cause outside a crossing's own cut: the rider is dismounted (frame handling is CREW-Cn's),
  the seat's bound dummy is removed, and within a bounded window the computer's INPUT and the FA
  **setpoint** are zeroed — the ship reverts to unmanned station-hold (never a runaway). The FA **mode** is retained. On AFC destruction the pilot receives a
  "flight computer destroyed" message.
- **SHIPCTL-15** *(R5+R16)* `[A][BEH]` Right-clicking a pilot seat whose dummy carries a DIFFERENT
  passenger does not mount and answers with an action-bar message naming the occupant. Occupied
  refusal takes precedence over the SHIPCTL-9 message — exactly one message per click. Self-click:
  silent no-op.
- **SHIPCTL-16** *(R6+R18+R19)* `[T][BEH]` A pilot who logs out seated logs back in SEATED (CREW-C14)
  **with a working control chain** — held input moves the ship within a bounded window, no re-board
  — on planet-side and slot-cell ships ALIKE. If the seat was TAKEN while offline, the occupant
  keeps it; the returner is restored STANDING aboard with a message — never two dummies on one
  seat. A mid-transit relog restores control ON ARRIVAL. Pinned by `VSPilotSeatRelogControlTest#aPilotWhoRelogsSeatedKeepsControlOfHisShip`, `VSPilotSeatTakenWhileOfflineTest#aSeatTakenWhileThePilotWasOfflineStaysWithTheOccupant`, `VSMidTransitRelogControlTest#aPilotWhoRelogsMidTransitRegainsControlOnArrival`.
- **SHIPCTL-17** *(R7+R17)* `[A][BEH]` Across any per-ship crossing with a seated pilot: a GRANTED
  crossing ends, after settle, with **exactly one** binding for that player to the SAME seat
  (re-identified by its AFC-link offset), expressed in the seat's current frame, and his still-held
  or next input reaches the arrived ship's linked computer. The pre-crossing binding OBJECT need
  not survive (outcome-pinned). A REFUSED crossing leaves the pilot SEATED with a message — capture
  only after the grant. The only permitted non-seated end state is the surfaced abandoned-settle.
- **SHIPCTL-18** *(R9 STRONG + R14 boundary)* `[V][BEH]` **FA state is a SETTING, not an INPUT, and it is
  the unmanned MODE SWITCH.** On a VOLUNTARY dismount the INPUT is released (the SHIPCTL-6 one-shot)
  and the SETTING is retained **and keeps EXECUTING**:
  - **FA ON** — an unmanned ship with a non-zero retained setpoint KEEPS CRUISING at it; a zero
    setpoint degenerates to station-hold (hover).
  - **FA OFF** — the craft is **RELEASED and FALLS**. Only the linear command is dropped; attitude
    hold remains, so it falls flat rather than tumbling.

  A re-mounting pilot receives the saved SETTING back — never a reset-from-live-velocity. Destruction
  (SHIPCTL-14) zeroes the setpoint; the mode survives.

  > **FOR (maintainer ruling 2026-08-17):** *«Непилотируемый корабль в FA — висит… Без FA должен
  > падать»* (an unpiloted ship in FA hangs; without FA it must fall). FA is the discriminator: falling
  > with the assist off is the intended behaviour, not a coasting-hover defect. A craft nobody has
  > ever flown is simulated like any other: physics is enabled whether or not it has ever been flown
  > (a craft returning from its tick before physics is switched on would neither hold nor fall).
  >
  > `[V]`: `TierTwoCraftFlightModelGroupTest#flightAssistDecidesWhetherAnUnpilotedCraftHoldsOrFalls`
  > pins BOTH directions in one run — held with FA on (the control, asserted first), released with
  > FA off.
- **SHIPCTL-19** *(R20+R21; the placement door R10/R11 does not guard — repair when the slot is
  empty, refusal when it is filled)* `[A][BEH]` Control blocks placed on an ASSEMBLED ship: **(a)** an
  AFC placed on a ship with no live linked AFC is a REPAIR — connected, restoring the SHIPCTL-1
  coincidence invariant; placed while a live linked AFC exists — **refused with a message**. A
  second AFC aboard is an UNCONSTRUCTIBLE state. **(b)** a pilot seat
  placed on a ship with NO pilot seat **auto-links** (the replacement path after SHIPCTL-14); a
  second seat's placement is refused with a message. R10/R11 hold for the ship's LIFETIME. **(c)**
  Mid-flight building of non-control blocks is OUT of scope.

## Rulings index (R1-R22, all maintainer, 2026-07-19 / 2026-07-20)

R1 pre-assembly boarding survives (→8) · R2 unassembled refuses w/ feedback (→9) · R3 disassembly
keeps pilot (→13) · R4 seat destroyed: dismount+zero (→14) · R5 occupied: message (→15) · R6 relog
restores control everywhere (→16) · R7 crossing keeps binding (→17) · R8 feedback
server-side at mount (→9) · R9 FA persists, STRONG: unmanned keeps cruising (→18) · R10 multi-AFC
rejected at scan · R11 multi-seat rejected at scan · R12 isPilotOf fallback = breach to remove (→7)
· R13 AFC destroyed = R4 + message (→14) · R14 destruction zeroes input+setpoint, mode stays
(→14/18) · R15 disassembly-moment message (→13) · R16 self-click no-op (→15) · R17 refused crossing
leaves seated (→17) · R18 seat taken offline: occupant keeps (→16) · R19 mid-transit relog: control
on arrival (→16) · R20 AFC placement: repair-or-refuse, (→19) · R21 seat
placement: auto-link-or-refuse (→19) · R22 mid-flight building out of scope (→19c).

## Upstream limits we fly inside (physics mod)

Recorded because the numbers are load-bearing for tuning. The "moving too fast" freeze is not the
reason for the ship cruise cap, which sits five times below it: understand why an upstream limit
exists before treating it as a lever.

| Limit | Value | Where | What it actually binds |
| --- | --- | --- | --- |
| "moving too fast" freeze | `|v|² > 50000` → **~223 blocks/s**, same test on `|ω|` → ~223 rad/s | `PhysicsCalculations.isPhysicsBroken` (vendored VS) `[V]` | Freezes the ship and ZEROES both velocities. Non-finite values trip it too. It is a sanity guard, not a speed limit — Stellurgy sits far below it. |
| ship altitude range | the world's `ShipAltitudeBand` (vendored VS): the configured `VSConfig.shipLowerLimit`/`shipUpperLimit` widened by what the server declared for that world, per physics step | `VSBridge.shipYPositionMaximum(World)` / `coverShipAltitudeBand` `[V]` | Hard clamp on pose Y at BOTH ends. Stellurgy declares the cell pose band on every server world as it loads (`SpaceSubsystemEvents.onWorldLoad`); a ship can never pass either end under thrust. Held by the WORLD, not by the config statics (which would outlive the server). The floor is load-bearing: the band is centred on the world origin, so half of every cell is below zero — a ceiling-only widen leaves a silent clamp under every descent. |
| player position packet | `\|x\|`, `\|y\|` or `\|z\|` > `3.0e7`, or non-finite | `NetHandlerPlayServer.isMovePlayerPacketInvalid` (vanilla) `[V]` | DISCONNECTS the player — it does not correct him, unlike the "moved too quickly" check beside it, which only snaps him back. It is why the cell pose band is centred rather than shifted. X/Z are additionally clamped by `Entity.setPositionAndRotation` one layer below; Y is not clamped anywhere else, so this is the only thing bounding it. |

**Stellurgy's own cap, for contrast** (`tunable`, not upstream): cruise speed
`TileAdvancedFlightComputer.SHIP_MAX_SPEED` (40 blocks/s, `TileAdvancedFlightComputer.java:244`) `[V]`.
Linear and angular authority are not constants of the computer: they are derived from the hull
(`ship-flight-model`, C10 STAT-1/2).

**What DOES scale with the cruise cap** is collision, not the freeze: the ship advances
`SHIP_MAX_SPEED/20` blocks per physics step. **Measured 2026-08-15, and the ceiling is far away.**
The physics mod runs no swept test at all — the narrow phase zeroes its own velocity term under a
performance TODO (`WorldPhysicsCollider.java`), so detection is instantaneous overlap of a block
CENTRE with the ship's AABB grown by 3, sampled once per physics step at a FIXED
1/60 s (`VSConfig.targetTps`; the loop sleeps to hold the rate, so the step never grows under lag).
A wall is therefore seen iff the ship advances no more than its own extent along the motion plus the
two 3-block margins:

> `v_max = 60·(L+6)` blocks/s = `3·(L+6)` blocks/tick, `L` = the ship's extent along its velocity.

**The ship's SIZE is the margin, and the obstacle's thickness contributes nothing** — the opposite of
the "thinner than a collider per tick" rule one expects. Today's 40 blocks/s is 54× under the bound
for a 30-block hull and 9× under even the degenerate `L→0` floor of 360 blocks/s, so collision is not
what is holding this constant down. Two other walls exist and are not speeds: the terrain the collider
reads comes from a cache that goes STALE rather than empty when a chunk is unloaded
(`SurroundingChunkCacheController`), and the pose-Y clamp in the row above.

For the TIER-1 rocket the equivalent question does not arise: a vanilla entity resolves collision
against the swept box (`Entity.move`), and free flight there is bounded by acceleration alone
(INV-RKT-23).

## Witnesses and open breaches

Which test pins which clause, and what is still violated.

- **SHIPCTL-5/8** `[T]`: the binding is re-expressed across assembly (`AssemblyCrewRebind` +
  `CrewTransfer.rebindAcrossAssembly`; `VSPreAssemblyBoardingPilotControlTest`, both cells). The
  rebind queue is the running server's `SpaceSubsystem.crewRebind`, ticked by `SpaceSubsystemEvents`,
  so a rebind pending at server stop dies with that server.
- **SHIPCTL-10/14** `[T]`: seat/AFC destruction does not latch input. `BlockPilotSeat.breakBlock` +
  `BlockAdvancedFlightComputer.breakBlock` (dismount rider, remove dummy, message on AFC loss) +
  `TileAdvancedFlightComputer.onControlStationLost` (INPUT + setpoint zeroed, FA mode +
  stationKeeping retained) + `invalidate()` (channels die with the tile). Gated off during
  relocation cuts by `StorageChunk.isRelocationInProgress()`. Pinned by
  `VSPilotStationDestructionTest` (client, both targets).
- **SHIPCTL-18** `[T]`: the unmanned branch EXECUTES the retained FA setting (FA-on + setpoint =
  cruise via `shipVelocityCommand` over an idle input; zero setpoint = station-hold; FA-off =
  released and falls); no zeroing, no re-capture. Pinned by `VSShipUnmannedCruiseTest` (client).
- **SHIPCTL-4** `[T]`: every dummy-producing path (right-click, probes, crossing reseat, assembly
  rebind) reuses the seat's single bound dummy via `BlockPilotSeat.boundDummyAt`. Pinned by
  `VSPilotSeatDummyReuseTest` (server).
- **SHIPCTL-7** `[T]`: the world-distance fallback does not exist (exact binding only) and every
  reseat path filters candidate seats by the durable ship id
  (`CrewTransfer.matchSeat(..., expectedShipId, resolver)`). Pinned by `CrewTransferSeatMatchTest`
  (unit).
- **SHIPCTL-9/15** `[T]`: `BlockPilotSeat.onBlockActivated` — occupied refusal names the occupant
  (wins, one message per click), self-click silent, not-assembled action-bar notice on a successful
  sit (`msg.pilotseat.*`, en+ru). Pinned by `VSPilotSeatMountMessagesTest` (client).
- **SHIPCTL-9's "does NOT accept pilot input" half** `[T]`: the client condition exists once, as
  `TilePilotSeat.forShipPilot` (link AND a live ship resolve), and all eight client readers call it
  (`KeyBindings` ×6, `StellurgyKeyConflictContext`, `ShipFrameCamera`, `FreeFlightHudState`). A
  build-time `linked` flag would not do: a rejected assembly leaves it set forever, so an inert craft
  would be steered, commanded and camera-locked. Pinned by `VSUnassembledCraftTakesNoOrdersTest`
  (client), whose leg 1 is a REAL ship.
- **SHIPCTL-9's R10/R11 door** `[T]`: multi-AFC / multi-pilot-seat builds are REJECTED at scan
  (`MULTIPLEFLIGHTCOMPUTERS` / `MULTIPLEPILOTSEATS`, VS-gated). Pinned by
  `VSShipMultiControlScanTest` (server).
- **SHIPCTL-19(a) — VIOLATED**: nothing rejects a second AFC's/seat's PLACEMENT onto an
  already-assembled ship (the R20/R21 repair-or-refuse door). Whether VS accepts placement onto an
  assembled ship at all is unverified `[A]`.
- **SHIPCTL-17** `[T]`: capture runs AFTER the entry grant in BOTH crossings — entry
  (`ShipEntryController.requestEntry`: materialize → capture → cut) and descent
  (`DescentController.requestDescent`: landing-resolve → capture → cut). A refusal messages the crew
  through the read-only `Ops.peekCrew`/`CrewTransfer.peek` (no dismount), and a failed cut (null
  anchor, pre-cut) re-seats the already-captured crew back in the source world. Pinned:
  `ShipEntryControllerTest`/`DescentControllerTest` (order + reseat-back, unit) +
  `VSShipEntryClientGroupTest.aRefusedEntryLeavesThePilotSeatedWithAMessage` (client: real pool
  pressure, filled by MEASUREMENT through the `space occupy` probe until the pool answers exhausted;
  refusal message in the pilot's own chat, still seated, still in the launch dim, and its own entry
  ledgering nothing). **Its message leg is currently RED and cannot be reached**: the craft tips over
  and flies sideways, so the orbit line is never crossed and the refusal is never asked for.
- **SHIPCTL-16** `[T]`, all legs pinned: planet-side relog `VSPilotSeatRelogControlTest` (client) —
  held key lifts the ship post-relog; slot-cell post-RESTART control leg —
  `SpaceLoginRestoreClientE2ETest` holds the real vertical-up key on BOTH sides of the restart;
  **R18 seat-taken-while-offline**: `RelogDeckHold.reconcileSeatMount` (login-time dummy
  reconciliation: fold an empty resident, or occupant keeps + returner restored standing via the
  deck hold + `msg.pilotseat.taken` naming him; `CrewTransfer.reseat` occupied branch messages once) —
  pinned by `VSPilotSeatTakenWhileOfflineTest` (client, real offline window via the harness
  `disconnect`/`connect` split); **R19 mid-transit relog**: `CrewTransfer.reseat` re-resolves a
  disconnected rider by UUID — pinned by `VSMidTransitRelogControlTest` (client, incl. post-arrival
  held-key climb). Residual (unpinned): the slot-cell restart-with-taken-seat path's MESSAGE is
  code-shared with the pinned planet-side one but has no e2e of its own.
