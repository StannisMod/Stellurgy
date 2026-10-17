---
id: integration-vs
owns: [integration/vs/]
entrypoints: [VSIntegration#init, VSIntegration#assembleTier2Ship, VSIntegration#crossShip, ShipFrameTravel#handles, ShipFrameTravel#travel, ShipFrameTravel#followShipPoses, DeckHold#onPlayerTick, DeckHold#onShipUsable, ShipMeetingStop#onWorldTick]
depends-on: [api-public, util-core, mixins-asm-coremod]
depended-by: [weapons, fire-control-sensor, projectile-substrate, shields, structural-damage, hyperdrive, client-render]
contracts: [C8, C9, C11, C18, C19, C23]
confidence: high for the port and the deck mechanics (read in full); the vendored substrate is quoted only where a claim depends on it
---

## Purpose

Stellurgy's **port onto the vendored Valkyrien Skies physics mod**, and the **deck** that rides on it.
Two jobs share one package because they share one frame discipline: (1) the port — the only code
allowed to name an engine type, answering "which ship, where, how fast, how oriented" and performing
the few writes Stellurgy needs (assemble, park, teleport, cut-and-paste a ship between worlds); and
(2) the deck — resolving a crew member's movement in his SHIP's frame so he stands, walks, falls and
rides on a deck that is rolled, inverted or moving. Neither the physics nor the crew rules are
described here — C11, C8 and C9 own the clauses; this doc maps where in the code they are kept.

## Responsibility boundary

**Owns**: the port surface and its containment (C11 PORT-1..13); a craft's single identity at assembly
and its durable-id index; the per-ship crossing recipe (cut, paste, re-assemble); the registry watch
that publishes ship events; the crude ship-meets-ship hold; the aboard-body state and its per-tick
resolution (deck, flying-aboard and hull-stand modes); the server-side bound on what a deck client may
claim; the login/crossing deck hold; the SAT hull sweep.

**Does NOT own**: the engine (`valkyrienskies/`, ours to edit but described by C11 as the substrate);
the force controller that actually flies a craft (the flight computer tile, `TileAdvancedFlightComputer`, see
MECH-VSI-12); the mixins that hook `Entity.move`/`travel`/`jump` and the packet handler (they call into
this package — `mixins-asm-coremod`); the client's deck look and camera (`client-render`); the durable
"aboard" record and the transit/crossing orchestration (`space/` — `ShipAboardTag`, `CrewTransfer`,
`VSShipCrosser`; C18); gravity under the deck (`util/GravityHandler`, which asks `handles`); what a
weapon or shield does with a ship's pose (their own docs).

## Key types

| type | role |
|---|---|
| `VSBridge` | package-private; the ONE class importing engine types (with `VSFlightBackend`); ≈90 static members, all primitives/MC types out |
| `VSIntegration` | public facade over the bridge, no engine type in any signature; also the crossing recipe, the assembly entry, the scans of a shipyard, `init()` |
| `ShipFrameTravel` | the deck: per-body capture state, the entry/stay/release gates, travel/jump in three modes, seeds, pose follow, probes |
| `ShipFrameBody` | per-`Entity` slot (installed by a mixin) holding that body's capture and its install stamp |
| `HullSweep` | pure-math SAT sweep of a world-upright box against ship-oriented boxes; stand/slide decision |
| `DeckFollowsItsShip` | server `WorldTickEvent` END handler at `LOWEST`: calls `followShipPoses` after the engine has moved the ships |
| `DeckHold` | login/crossing hold that pins a body and re-asks its client to capture; `Holds` is the per-server registry |
| `DeckMovementBound` | server's per-packet region test for a captured player |
| `ShipLifecycleEvent` (`api/event/`) | the ship lifecycle family `ShipNamed` / `ShipUsable` / `ShipUnnamed` / `ShipDeparted` / `ShipReleased`, posted by the vendored ship manager at the place each transition happens (valkyrienskies) |
| `ShipMeetingStop` | two overlapping loaded ship boxes are both held at rest; posts `ShipCollisionEvent` on the edge |
| `ShipLocalMove` / `ShipLocalMoveControl` | an experiment hook that resolves ONE entity's `Entity.move` in ship frame; armed only by the test probe |
| `VSFlightBackend` | an `IFlightBackend` that writes velocity setpoints to a ship — **has no caller** (see Failure modes) |

## Mechanics

### The port

**MECH-VSI-01 — Containment: the engine is named here and nowhere else.** `VSBridge` is `final`,
package-private (`VSBridge.java:46`); `VSIntegration` is the public face and carries no engine type — it
has no `org.valkyrienskies` import (`VSIntegration.java:1-10`) and its signatures use `World`, `UUID`,
`String`, `double[]`, `AxisAlignedBB` and `FreeFlightPhysics.Quat`. Only five main-tree files import the
engine: the bridge, `VSFlightBackend`, two mixins and `Stellurgy.java` `[V]`; the scan is by ROLE, with a
control that fails if it finds nobody `[T]` `test/unit/VSBoundaryContainmentTest.java:40`. The old promise
that the bridge loads only when VS is present is **withdrawn** — the substrate is compiled into the jar,
`isAvailable()` was deleted 2026-09-22, and no caller gates on it (`VSBridge.java:37-44`,
`VSIntegration.java:41-49`). Absence of the engine is a broken build that fails at class load, never a
branch. `VSIntegration.init` registers three watchers and the rest is registered from `Stellurgy.java`
(`VSIntegration.java:58-71`, `Stellurgy.java:1623`, `:1652`).

**MECH-VSI-02 — One ship, one identity, enforced by the assembly signature.** A tier-2 craft's engine
uuid IS the durable name its own flight computer carries. `VSIntegration.assembleTier2Ship(world, pasted,
x0, y0, z0)` takes no anchor and no identity (`VSIntegration.java:142-165`): it derives the footprint from
the pasted snapshot, scans it for the `TileAdvancedFlightComputer` (`:198-215`), anchors there, reads the
name, and **refuses with an ERROR log and `null` when no computer is found** — "blocks with no computer are
not a craft". Then (`:221-279`): a name already held by a LIVE ship means a duplicated computer, so it is
**re-minted** (`:235-244`); a missing name refuses (`:267-276`). The bridge in turn refuses a null name
(`VSBridge.java:86-92`), adopts the craft's own **blockless remnant** (the registry entry a crossing's cut
leaves behind) and keeps the identity (`:141-167`, branch `:152-158`), but if a LIVE ship holds the name it
assembles under a **fresh** uuid with a loud ERROR and the two ids diverge (`:160-166`, `:113-114`). The
durable name is written on the record before registration, without touching the index (`:110`); the spawn
is queued and drains on the engine's own world tick, so it lands next tick or is dropped, not retried
(`:112`; `valkyrienskies/.../WorldServerShipManager.java:183-187`, `:399`). `[V]` C11 PORT-13.

**MECH-VSI-03 — The durable-id index is a cache; a miss is answered from the flight computer.**
`shipUuidOfDurableId` probes the engine's reverse index (`VSBridge.java:204`); on a miss it walks the
world's registered ships, resolves each one's flight computer, reads its `shipId`, and **binds** the index
from what it finds (`VSIntegration.java:1390-1401`, `:1417-1439`, bind `:1456`). It answers `null` only when
no craft carries the name, which a caller must read as "could not establish". The canonical copy is the
computer's NBT; the engine-side field is the reverse index kept beside it. `[V]`

**MECH-VSI-04 — Three ways to name a ship, with different liveness.** (a) By **id** — the engine uuid as a
string; `physoById` walks LOADED ships (`VSBridge.java:1399`); every `…For` frame operation, the velocity
read and the stay region use it, and answer `null` when that ship is not loaded on this side. (b) By
**block** — `shipIdManagingBlock` asks the claim of a LOADED physics object (`:1420`, null for a ship nobody
is near); `registeredShipIdManagingBlock` asks the registry record (`:1445`, `VSIntegration.java:1119`) and
answers for unloaded ships too — the two are not interchangeable, and the second is the one for IDENTITY.
(c) By **containment** — `shipIdsAt` returns EVERY loaded ship whose world box grown by `ABOARD_MARGIN`
(1.0) contains the point (`VSBridge.java:1459`, `:1759`); the entity-keyed conversions (`toShipFrame`…) take the FIRST
such box (`:1327-1342`) and exist only for first contact. A **nearest-ship** lookup with no distance bound
remains private to the bridge (`VSBridge.java:902`, its NOTE `:892-901` records two failed attempts at bounding it) and
still backs the facade's positional forms (see Failure modes). `[V]` C11 PORT-7.

**MECH-VSI-05 — Frame conversion, attitude and velocity reads.** Point and direction conversions in both
directions exist keyed by entity and keyed by ship id (`VSBridge.java:1345-1390`, `:1496-1583`), plus a
**render** transform (`renderToWorldFrameFor`, `:1546`), meaningful on the client only. Attitude is read by
block (`:247`), by id (`:1513`) and by containment (`:1805`); the facade interpolates previous→current tick
by slerp for a camera (`VSIntegration.java:1025-1039`). `shipVelocityAtPointFor` answers `v + ω×r` in
**blocks per TICK** (the engine is per second; converted once at `VSBridge.java:1641-1645`, `SECONDS_PER_TICK`
`:1586`); on the client it reads the interpolator's shown velocity, not the declared one (`:1629-1634`)
`[T]` `test/server/TurretOnAShipTest.java:178`. An exception there is not swallowed silently: it logs
once per cause per world and answers `null` (`VSBridge.java:1646-1658`, `:1678-1690`). `[V]`

**MECH-VSI-06 — Writes: park, unpark, rigid teleport, altitude band.** `parkShip`/`unparkShip` flip the
engine's physics-enabled flag by id (`VSBridge.java:942-959`; positional twins `:922-939`).
`teleportShip` rewrites the transform position keeping rotation, and copies the engine's own teleport
recipe — the three guard flags on a loaded physics object (`:1016-1018`) — because a plain transform write
is overwritten by the physics step; the ship comes out **parked** and the caller re-enables it a tick
later (`:1025-1032`). The world's altitude band is widened to cover the whole space-cell pose range before
the first ship arrives (`:320`, called from `SpaceSubsystemEvents.java:58`); `teleportShip` widens it
again only for a destination outside the band (`VSBridge.java:996-1000`) and never narrows an operator's setting.
Velocity writes (`haltShipById`, `pushShipById`) are overwritten by the next physics step, so "stay
stopped" must be re-said every tick (`VSBridge.java:575-583`, `:1105-1123`). `[V]` C11 PORT-9.

**MECH-VSI-07 — The crossing recipe: cut a ship out of one world, paste and re-assemble it in another.**
`crossShip(src…, srcShipUuid, dst…)` (`VSIntegration.java:479-639`) in order: resolve the shipyard box
(by id when given, else nearest — `:486-487`) → scan the Y band of real blocks, force-loading the claim
(`:500`, `:967-991`) → **ask the substrate, before anything is cut, whether it will rebuild this hull**: find
the flight computer the far end will anchor on (the first in the box, in the order the far end scans it) and
run the spawn's own detection and rule from it, with the SOURCE's own shipyard box as the footprint
(`VSBridge#assemblyRefusal` → `WorldServerShipManager#refusalFor`: `TOO_LARGE`, `BEDROCK` or `REACH_EXCEEDED`);
no computer, or a refusal, returns a `CrossResult` carrying `refusal` with the source untouched (`VSIntegration.java:628-630`,
the block after the band scan) → read the source's durable name and **declare the departure** to the announcer
BEFORE cutting (`VSIntegration.java:538-555`) → cut a tight box (`:558-560`; `abandonDeparture` if nothing was cut, `:561-566`)
→ mark the source FINISHED so the engine collects it on its next tick, loaded or not (`:571`,
`VSBridge.java:854-864`; the source is deliberately not deregistered by hand first — `VSIntegration.java:509-537`)
→ retire the pilot-seat dummies bound to the cut seats (`:583-588`) → force-load the destination footprint,
paste in clear sky, anchor on the first non-air block, and `assembleTier2Ship` the pasted snapshot (`:592-625`).
`snapshotShipOf`/`snapshotShipAt` re-cut a parked ship non-destructively for the transit persistence
(`:648-685`); `pasteAndAssemble` is the paste tail for a restored transit and now returns the ship under
its own name (`:699-744`). If the anchor scan of the pasted box finds only air, or the assembly takes nothing,
the source is already cut and the ship exists as loose blocks registered nowhere: a `CrossResult` that is not
`ok()` — `ok()` is a pasted anchor AND a queued identity — and an ERROR log, the destructive failure the pre-cut
check exists to make unreachable. `[T]` a hull past the reach and a hull with its computer broken are refused
before the cut: `test/server/VSShipAssemblyExtentTest.java` (`aShipThatGrewPastTheReachIsNotCutByACrossing`,
`aShipWithItsComputerBrokenIsNotCutByACrossing`). `[T]` a crossing leaves no ship behind:
`test/server/VSCrossingLeavesNoShipBehindTest.java:78`, `:90`, `:141`. The dummy rule is `[T]`
`test/unit/CutLeavesNothingBoundTest.java:38` (and `:52`, `:75` — the match is on the SEAT, not where the
dummy rides).

**MECH-VSI-08 — Registry watch: named, usable, unnamed, departed.** The ship lifecycle is announced at its
source: the vendored ship manager queues one `ShipLifecycleEvent` per transition as it happens —
`ShipNamed` (assembled, pasted, loaded), `ShipUsable` once a loaded ship is ready to be acted on,
`ShipUnnamed` / `ShipDeparted` when a registration ends (a crossing declares the departure before it cuts,
so the removal is announced as what it was), `ShipReleased` when a ship object is let go — and posts the
queue after the pass that produced it (`WorldServerShipManager`, `pendingLifecycle`; the family and its
ordering rules are documented on `api/event/ShipLifecycleEvent`). A world that stops ticking announces
nothing, so the absence of an unnaming is never proof a craft still exists. This subsystem consumes the
family (`DeckHold#onShipUsable`, `#onShipGone`). `[V]`

**MECH-VSI-09 — Ships that meet are held at rest (crude, deletable whole).** While `shipsCollide` is on,
every server world tick compares the world boxes of all loaded ships pairwise; each ship in an overlapping
pair is zeroed (`haltShip`) **every tick** of the overlap, and `ShipCollisionEvent` is posted once on the
tick the pair first overlaps (`ShipMeetingStop.java:77-124`, key `:127`). Nothing is conserved; boxes, not
blocks; bodies aboard are not told. The config flag off clears the memory and does nothing else
(`:83-87`; flag `StellurgyConfiguration.java:554`). `[V]` — **no test in `src/test` pins this mechanic or its
flag** (see Open questions).

**MECH-VSI-10 — A machine aboard waits until its ship is named.** `isOnUnnamedShip` = the block is in the
shipyard region AND no registered ship claims it (`VSIntegration.java:1100-1102`; region test is pure
allocator arithmetic and answers truthfully without the engine, `VSBridge.java:812`). Weapons and fire
control skip their whole tick on it (`TileTurret.java:118`, `TileFireControlSensor.java:129`,
`TileWeaponConsole.java:111`) `[T]` `test/server/TurretStandaloneTest.java:334`.

**MECH-VSI-11 — The flight force is NOT applied from this package.** `VSFlightBackend` would write a
linear velocity setpoint (×20 ticks/s) and a proportional angular rate toward a target attitude
(`VSFlightBackend.java:52-80`, `ownsTransform` true `:83`), but nothing constructs it: `createShipFlightBackend`
(`VSIntegration.java:1000`) has zero callers in `src/` and in `valkyrienskies/` `[V]` (grep, 2026-10-06).
The force that flies a tier-2 craft is the flight computer tile acting as an engine force controller
(`TileAdvancedFlightComputer` implements `IPhysicsBlockController` and is ticked on the physics thread),
outside this package. C11 PORT-5/-11 name `IFlightBackend` as the migration seam for force application;
today the live seam is that interface on the tile.

### The deck

**MECH-VSI-12 — A body is captured by an episode on ONE ship; the state lives on the Entity.** The
capture (`ShipFrameState`: ship id, committed deck point, held carry velocity, mode, install stamp, seed
flag) is a field of the entity object via `ShipFrameBody` (`ShipFrameTravel.java:146-148`, `:167-199`,
`ShipFrameBody.java:11-22`). An object field, because vanilla `Entity` equality is by network id and a map
handed both logical sides of an integrated game ONE slot, so each deleted the other's captures
(`ShipFrameTravel.java:138-144`). The state changes at EDGES (enter, change ship) and is never re-decided per tick; the tick's
carry-over is written in place by `remember` (`:2183-2197`). Every transform of the episode resolves
through the anchor ship id, never re-picked by containment (CREW-C2; `:170-176`). `[V]` `explainHandles`
replicates the gate read-only for the probe (`:937-1034`).

**MECH-VSI-13 — Entry, stay and release.** `handles` is the single predicate three callers share
(`travel`, the gravity hook, the probe) (`ShipFrameTravel.java:218-394`; gravity hook
`GravityHandler.java:93`). **Excluded states** — no-gravity, riding, elytra, water, lava, ladder,
levitation, and creative flight unless the deck already owns the flyer or may claim it — keep world
semantics and RELEASE any capture (`ShipFrameTravel.java:409-433`); the same predicate refuses a seed. **Stay**: the ship's
own subspace block region grown by `STAY_REGION_MARGIN` = 4.0, so a jump above the deck never exits
(`:210`, `:254-263`). **Release reasons**, each logged (test mode): `notSimulated`, an excluded state,
`shipUnloaded` (`:251`), `leftShipRegion` (`:261`), `steppedOntoTerrain` (`:272`), `noHullContact`
(`:289`), `noDeckBelow` (`:324`) (`release`, `:520-530`). **First contact** (`:337-393`): a body on world
terrain is never captured (`:337`); otherwise the candidate is the ship it stands on, tested in each
candidate's own subspace for STANDING support (`firstContactCandidate`, `:448-468` — a declared hold
outranks the sweep), else an **interior** claim (roofed, deck below, inside the margin-0 region —
`:1359-1379`), else **hull-stand** where only the body's box touches the hull. Support probes are
`SUPPORT_PROBE`=0.30 extended by fall speed, `STANDING_TOLERANCE`=0.05, `FLOOR_PROBE_DEPTH`=6.0
(`:1449`, `:1285`, `:1291`). `[T]` jump keeps the capture `test/client/VSCrewCaptureContractTest.java:468`;
flyer not snapped back `:1390`; interior reclaim of a body lost mid-cavity
`test/client/VSCrewInteriorBoardingTest.java:438`; a flyer ascends along the deck normal `:675`.

**MECH-VSI-14 — Aboard travel: ship-frame kinematics, vanilla constants.** One resolved tick
(`travel`, `ShipFrameTravel.java:1564-1732`): derive the deck point from where the body IS; take motion
RELATIVE to the ship by subtracting **exactly the carry this class added at the last commit** (a fresh
sample would leak the deck's acceleration as inertia, `:1591-1603`); friction from the block under the
feet along the deck normal; walk input about the **deck yaw** (`:1637`, `:1652`; deck yaw is the client's
held look when it owns the body, else the world look mapped in, yaw-only — `:2320-2341`, port
`:1754-1798`); apply vanilla gravity (0.08) along deck −Y **before** the sweep (a deliberate reorder that
costs a jump one gravity step, `:1654-1662`); sweep the deck-aligned box with de-penetration and step
assist (`sweepShipFrame`, `:2378-2478`); drag 0.98 along the normal and `friction` in plane (`:1675-1677`);
commit the position through the ship transform, re-add a freshly sampled carry, write on-ground/collision
flags, fall state and limb swing, and **suppress the engine's own drag anchor** every resolved tick
(`ShipFrameTravel.java:1680-1728`, `VSBridge.java:1193-1221`). `jump` is deck-up with the same held-carry rule (`ShipFrameTravel.java:2103-2157`).
`flyingAboardTravel` is vanilla creative flight on deck axes — 0.6 vertical drag, no gravity (`:1813-1889`).
A declined tick (ship transform gone) hands the body to vanilla while the capture is held, and traces
(`:1604-1619`). `[T]` still crew on a steeply rolled deck, a hovering ship, and a body meeting a deck that
manoeuvred unwatched are not dragged: `VSCrewCaptureContractTest.java:783`, `:901`, `:2150`.

**MECH-VSI-15 — Hull-stand: world semantics with true-geometry collision.** On the outer surface the body
keeps world gravity, world walk basis and its own camera; only the collision is resolved against the ship's
blocks, as boxes in their TRUE world orientation (`hullStandTravel`, `ShipFrameTravel.java:1900-2039`;
`HullSweep`). `HullSweep.sweep` mirrors vanilla's Y→X→Z passes with a 15-axis SAT time-of-impact
(`HullSweep.java:91-130`, `:214-301`), a bounded start-lift ≤ 0.1 along "up" (`:34`, `:97-117`), step assist
along up (`:133-159`), and the **stand-vs-slide rule**: a clipped gravity move whose contact face is within
45° of up is a static hold, steeper sheds the body tangentially; zero gravity (`up == null`) means no lift,
step or slide (`:50`, `:59-76`). An obstacle already overlapping at the start of a pass never clips
(`:289-291`). Capture gate: the body's box moved by its motion and grown 0.5 touches the hull
(`ShipFrameTravel.java:1308-1328`). Deck↔hull transitions happen in `handles` (`:275-327`). `[T]`
`test/unit/HullSweepTest.java:136`, `:146`, `:159`, `:171`, `:184`; world-top of an inverted ship keeps
world semantics `VSCrewCaptureContractTest.java:1500`.

**MECH-VSI-16 — The deck is put back after the ships have moved.** The engine advances every ship pose
at `WorldTickEvent` END at `HIGHEST` (`valkyrienskies/.../EventsCommon.java:230-250`), after the entities
have moved, so an aboard body stands where the deck WAS one tick ago — a tangential lag that grows with its
distance from the rotation axis. `DeckFollowsItsShip` runs at `LOWEST` on END (`DeckFollowsItsShip.java:25-32`)
and `followShipPoses` re-images every held body through the now-current transform
(`ShipFrameTravel.java:2287-2310`); the client does the same from its client tick
(`ClientDeckFollowsItsShip.java:27`). It skips bodies whose position the server only FOLLOWS (a real
`EntityPlayerMP` — his client decides), riders and the dead (`ShipFrameTravel.java:1237-1241`, `:2294-2297`). A **teleport** is
different: `carryHeldBodies` shifts every body held on a ship by the pose delta without needing the craft
loaded, and a real player is TOLD (`setPositionAndUpdate`) rather than moved (`:2264-2285`; caller
`VSShipCrossingOps.java:387`). `[T]` `test/client/VSCrewRidesRollingDeckTest.java:95` (red-witnessed with
`followShipPoses` doing nothing).

**MECH-VSI-17 — Seeds: placing a body on an explicit deck point.** `seedShipFrameCapture` installs a capture
at a SUBSPACE triple (the client maps it through its OWN transform, so snapped body and stored anchor agree),
snaps the body, zeroes its motion and fall distance and suppresses the engine drag; it refuses an excluded
body and answers false for an unloaded ship (`ShipFrameTravel.java:564-628`; server caller
`AboardBodies.java:220`). On a client a seed is a **pending slot** per body (weak keys, per world,
`ShipFrameTravel.java:650-684`) driven by a pure decision (`pendingSeedDecision`, `:695-733`): taken → done; capture predating the
slot wins (dismount only); expired (TTL 40) dissolves without snapping; excluded waits; else apply once. A
**restore** seed (login) outranks a capture the client made for itself. Fed by `PacketDeckCapture`
(`PacketDeckCapture.java:99`) and `KeyBindings.java:452`. `[T]` `test/unit/DismountPendingSeedTest.java:30`
through `:114` (state machine).

**MECH-VSI-18 — The server bounds what a captured player may claim.** Movement is client-authoritative and
vanilla's speed check is written for ground, so a body on a moving deck could claim 30 blocks in a tick.
`DeckMovementBound.accepts` applies **only** while the server holds a capture on the player; the region is
`(PLAYER_MAX_OWN_BLOCKS_PER_TICK + carry) × ticks + REGION_FLOOR_BLOCKS`, carry from the same
`shipVelocityAtPointFor` the client was told, ticks the world time since the player's last judged packet
capped at `MAX_CLAIMED_TICKS` (`DeckMovementBound.java:52-60`, `:87-125`). The positions are read from the
server immediately before and after the packet, so a server-side teleport moves both and never looks like a
claim (`MixinNetHandlerDeckMovementBound.java:50-66`). A refused step puts him back; it does not ban him.
Per-server (`ServerState.java:59`, `forget` `:131`). The three constants are `tunable` (the javadoc records
a measured maximum of ≈0.4). `[T]` `VSCrewCaptureContractTest.java:1246` (a 6-block step is refused;
red-witnessed with `accepts` ratifying all).

**MECH-VSI-19 — Hold a body on its deck while nothing else can.** `DeckHold` pins a body each tick and
re-sends the SUBSPACE deck point in `PacketDeckCapture` (a restore seed) until the capture lands on **the
hold's own ship** (`DeckHold.java:327-396`, exit rule `:349-353`). It starts at (a) login of a player whose
durable aboard record says STANDING (`:209-226`); (b) `pinInPlace` — a crew member whose ship is being cut
out from under him, no ship to resolve (`:143`); (c) `holdOnDeck` — the far side of a crossing, ship and point
known (`:158`; callers `CrewTransfer.java:199`, `:276`, `:495`). A hold with only a durable id resolves
**lazily**: at arm time, and again on `ShipLifecycleEvent.ShipUsable` and `FlightComputerLiveEvent`, never on a clock
(`DeckHold.java:483-547`, `:610-625`); an unresolved tick keeps the body from falling and nothing more, and says so once
(`:422-438`). It ends on: the capture on its own ship, an excluded state, `ShipLifecycleEvent.ShipUnnamed` for its craft
(destroyed — logged and shown on the action bar, `:567-601`), or the **give-up** after
`HOLD_WINDOW_TICKS` = 200 ticks, which is a refusal, logged at ERROR with the cause and shown to the player
(`:76`, `:360-363`, `:453-470`). A **departure is deliberately not subscribed**: the hold must outlive the
cut and the arrival re-arms it (`:559-565`). Per-server registry `ServerState.java:55`; `PlayerRelease`
drops it (`PlayerRelease.java:90-94`). `reconcileSeatMount` folds a relogged pilot's re-spawned dummy into
the seat's resident, or — seat taken while he was away — stands him on the deck and tells him who took it
(`DeckHold.java:243-295`). `[T]` seat taken offline: `test/client/VSPilotSeatTakenWhileOfflineTest.java:98`. The hold's
relog scenarios are red or ignored (see Failure modes).

**MECH-VSI-20 — Seams for observers are empty production methods.** `noteCommittedPose`,
`noteTickHistory`, `noteWalkInputs`, `noteSeedOutcome`, `noteHullCollisionSolid`, `noteWorldMove`,
`clientCensusTick` have empty bodies (`ShipFrameTravel.java:97`, `:115`, `:132`, `:513`, `:1062`, `:1517`,
`:1552`); the test tree injects at their HEAD to read the values that are parameters there, so no
telemetry ships. A change to their parameter lists is a change to the test harness's mixin targets.
`ShipLocalMove.resolve` is a separate, off-by-default experiment: `ShipLocalMoveControl` is inert until the
probe arms it for one entity id (`ShipLocalMoveControl.java:45-65`, `ServerState.java:57`).

## State & persistence

Nothing in this package writes NBT of its own. What it touches: the engine's per-ship record
(`ShipData.stellurgyDurableId` — written at assembly `VSBridge.java:110`, re-bound `:232`); the flight
computer's `shipId` (read, minted on a duplicate — `VSIntegration.java:176-182`, `:238`); the snapshot NBT
a transit re-cuts (`:667-685`, owned by `space/`). The durable "aboard" record the deck hold reads is
`space/ShipAboardTag` (`DeckHold.java:216`). Per-body capture, pending seeds, `Holds`, `DeckMovementBound`'s
timestamps and the announcer's memory are runtime state — on the entity, the world (`WorldRuntime`) or
`ServerState` — and none survives a restart; a held body is re-established from the durable record.

## Invariants

- **INV-VSI-01 [V]** Only the port package, the engine mixins and `Stellurgy.java` import engine types.
  `VSBoundaryContainmentTest.java:40` (role scan with an instrument-fire control).
- **INV-VSI-02 [V]** No public signature of `VSIntegration` carries an engine type; the facade's file has
  no engine import. (C11 PORT-3.)
- **INV-VSI-03 [V]** A tier-2 assembly happens only through a flight computer found in the footprint, under
  its durable name; the one way the two ids can still differ is a live holder of the name, and that is logged
  at ERROR (`VSIntegration.java:155-163`, `:235-251`; `VSBridge.java:160-166`).
- **INV-VSI-04 [V]** A crossing leaves no registered ship behind in the world it left, whether the source
  was loaded or not (`VSCrossingLeavesNoShipBehindTest.java:78`, `:141`); the source is marked finished,
  never deregistered by hand first (`VSIntegration.java:509-537`).
- **INV-VSI-05 [V]** The departure is declared before the cut and taken back if nothing was cut; the
  announcer therefore never reports a crossing as a destruction (`VSIntegration.java:553-566`).
- **INV-VSI-06 [V]** A velocity read at a point is in blocks per tick, on both sides, with the same
  expression (`TurretOnAShipTest.java:178`). One function converts for both sides: `VSBridge.java:1628,1684-1686`.
- **INV-VSI-07 [V]** A captured body resolves every transform through its anchor ship id for the whole
  episode; first-match containment is used only to mint the first contact (`ShipFrameTravel.java:170-176`,
  `:448-468`).
- **INV-VSI-08 [V]** The relative motion of an aboard body is recovered by subtracting exactly the carry
  written at its last commit (`ShipFrameTravel.java:1597-1603`, `:2183-2197`).
- **INV-VSI-09 [V]** A held body is re-imaged on its deck point after the substrate has advanced the
  ships (`DeckFollowsItsShip.java:27` priority; `VSCrewRidesRollingDeckTest.java:95`).
- **INV-VSI-10 [V]** The pending-seed state machine: a taken seed no-ops every re-send; a capture that
  predates the slot wins for a dismount and loses for a restore; an excluded body is waited for; an expired
  slot never snaps (`DismountPendingSeedTest.java:38-114`). `ShipFrameTravel.java:714-731`.
- **INV-VSI-11 [V]** The server's deck bound refuses a step no input plus the deck's carry could produce and
  has no opinion about an uncaptured body (`VSCrewCaptureContractTest.java:1246`; `DeckMovementBound.java:93-96`).
- **INV-VSI-12 [V]** A deck hold ends only by capture on its own ship, an excluded state, the destruction
  of its craft, or an announced give-up; it survives a departure (`DeckHold.java:349-363`, `:559-565`).
- **INV-VSI-13 [V]** A machine on a shipyard block no ship claims does nothing
  (`TurretStandaloneTest.java:334`). `TileTurret.java:114`, `TileWeaponConsole.java:103`, `TileFireControlSensor.java:125`, `TileEntityFieldGenerator.java:108`, all on `VSIntegration.java:1168`.
- **INV-VSI-14 [V]** Hull sweep: an obstacle a pass starts inside never clips it; a shallow embed is lifted
  and then stands; a face within 45° of up holds, a steeper one sheds (`HullSweepTest.java:184`, `:171`,
  `:136`, `:146`). `HullSweep.java:34,59-70,104`, `:289-291`.
- **INV-VSI-15 [V]** A seat bound to blocks a crossing cuts is retired with them, matched on the seat and
  never on where the dummy rides (`CutLeavesNothingBoundTest.java:38`, `:75`). `VSIntegration.java:662-666,1695-1702`.
- **INV-VSI-16 [V]** The one mutable static in the aboard code is the client look port: written once by
  `installClientLookSource`, and a second install throws (`ShipFrameTravel.java:1772`, `:1789-1798`; caller
  `ClientProxy.java:265`). A null port therefore means "dedicated server" and nothing else.
- **INV-VSI-17 [V]** Ship-meets-ship holding is a no-op, with no event and no cost beyond one config read,
  while `shipsCollide` is false (`ShipMeetingStop.java:83-87`).

## Failure modes & edge cases

- **`VSFlightBackend` is dead code** (MECH-VSI-11). `createShipFlightBackend`'s javadoc still says "or null
  when VS is absent" — it never returns null (`VSIntegration.java:993-1002`).
- **Nearest-ship, unbounded, still on the facade.** `shipyardBoundsAt`, `parkShipAt`, `unparkShipAt`,
  `snapshotShipAt`, `shipBlockHeight`, `flightComputerAt` and the positional
  `crossShip` overload all go through `nearestQueryableShip` (`VSBridge.java:902`), which compares a
  WORLD-frame pose to whatever position it is handed. The facade's own comment says "the nearest-ship
  lookups are gone" (`VSIntegration.java:1291-1302`), but production still calls `flightComputerAt`
  (`VSShipCrosser.java:240`) and `unparkShipAt` (`VSShipCrossingOps.java:409`). Each has an id-keyed twin;
  the callers listed are not on it. The departure's `parkShipAt` and the re-cut's `snapshotShipAt` fallbacks are
  gone: a re-cut that finds no ship of its name takes NO snapshot and the jump keeps the
  one it has (`VSShipCrosser#snapshotOfNamedShip`), and the departure parks by name, finding a ship still queued
  to spawn (`VSBridge#parkShip` → `WorldServerShipManager#queuedSpawn`).
- **A crossing's re-assembly can still be refused after the cut.** The hull is checked against the spawn's own
  rule before the cut and `CrossResult.ok()` needs a queued identity; but the check and the spawn run on
  different ticks, so a world that changes between them (a block added to the pasted hull) can still be refused
  after the cut — logged by the substrate with its reason.
- **A spawn the engine refuses reaches this side only as a log line.** The drain refuses a ship that
  is too big, attached to bedrock or past the detection reach, logging the reason and giving the shipyard claim
  back (`WorldServerShipManager#refuseSpawn`), while `assembleTier2Ship` has already returned the uuid, so a
  caller that did not ask first holds the id of a ship that was never spawned. The crossing, the jump gate
  (with the ship's chunk claim, full column, as the footprint) and the rocket assembler ask first
  (`VSBridge#assemblyRefusal`).
- **Known-red deck scenarios (ignored with the maintainer's reason in the annotation):** hull-stand→aboard
  is still a per-tick geometric predicate with no edge (`ShipFrameTravel.java:283-287`;
  `VSCrewCaptureContractTest.java:1636`); a relogged player on an inverted deck is released
  `steppedOntoTerrain` ten ticks in (`VSCrewRelogPersistenceTest.java:772`); the walking-relog scenario's
  mode order moves with concurrency (`:928`); a returning client never takes the reloaded deck
  (`VSDeckCaptureAndDismountTest.java:539`); the whole `VSTransitCrewGroupTest` class is held for the deck
  contract (`VSTransitCrewGroupTest.java:56`). They are the open edge of C8/C23, not of this port.
- **The terrain half of first contact is unpinned** (the test is ignored as "no contract"; C8 CREW-C1).
- **Declined ticks are not silent in tests only.** A deck tick that cannot get the ship transform returns
  `false` and vanilla moves the body world-down for that tick with the capture still held
  (`ShipFrameTravel.java:1604-1619`, `:1683-1697`); production logs nothing there.
- **A gate that reads "unloaded" releases.** A ship that unloads on this side releases its crew
  (`shipUnloaded`, `:251`); the deck hold exists for the window where that is wrong.
- **Weak spots of the crude hold:** boxes not hulls, nothing conserved, a craft nobody flies never stops
  (class javadoc `ShipMeetingStop.java:23-52`).

## Integration seams

- **Mixins calling in**: `MixinEntityLivingShipTravel.java:39`, `:51` (travel, jump),
  `MixinEntityPlayerShipFlight.java:37`, `MixinEntityShipLocalMove.java:50` (`noteWorldMove`),
  `MixinNetHandlerDeckMovementBound.java:61`, `MixinEntityShipFrameCapture.java:15` (the `ShipFrameBody`
  slot). The force controller is the flight computer tile itself (`TileAdvancedFlightComputer` implements
  `IPhysicsBlockController`); no production mixin targets the vendored tree or our own tiles.
- **Events published**: `ShipCollisionEvent` (`ShipMeetingStop.java:119`); consumed: `ShipLifecycleEvent.ShipUsable`,
  `ShipLifecycleEvent.ShipUnnamed`, `FlightComputerLiveEvent` by `DeckHold` (`DeckHold#onShipUsable`,
  `#onFlightComputerLive`, `#onShipGone`).
- **Callers outside**: `space/VSShipCrosser`, `VSShipCrossingOps`, `CrewTransfer`, `AboardBodies`,
  `SpaceSubsystemEvents` (altitude band), `TileAdvancedFlightComputer` (`ensureShipPhysicsEnabled`, velocity),
  weapons/sensor/shield/damage (ids, frames, per-tick velocity), `PlayerRelease` (hold release),
  `GravityHandler` (the `handles` gate).
- **Wire**: `PacketDeckCapture` (ship id + SUBSPACE triple + restore flag) — C2/C23 own it.
- **Registration**: `DeckHold` from `Stellurgy.java:1623`; the client look port from `ClientProxy.java:265`.

## Config surface

| flag | mechanic | full-disable path |
|---|---|---|
| `shipsCollide` (ROCKET, synced, default true) | MECH-VSI-09 | false ⇒ ships pass through each other, no event (`ShipMeetingStop.java:83-87`) |

The deck bound's three constants and the stay margin are code constants (`tunable`), not config.

## Test coverage

Unit: `VSBoundaryContainmentTest`, `HullSweepTest`, `DismountPendingSeedTest`, `CutLeavesNothingBoundTest`.
Server e2e: `VSCrossingLeavesNoShipBehindTest`, `VSShipTransitPersistTest` (restored transit),
`TurretOnAShipTest`, `TurretStandaloneTest`. Client e2e: `VSCrewCaptureContractTest`,
`VSCrewRidesRollingDeckTest`, `VSCrewInteriorBoardingTest`, `VSPilotSeatTakenWhileOfflineTest`,
`VSCrewRelogPersistenceTest`, `VSDeckCaptureAndDismountTest` (several scenarios ignored — see above).
**No test found** for: `ShipMeetingStop` and `shipsCollide`; `shipUuidOfDurableId` and its rebind; `flightComputerOfNamedShip`; `carryHeldBodies`;
`DeckHold`'s give-up and ship-gone ends.

## Open questions

- Should the facade's positional forms be removed (their id-keyed twins exist) — or is a first-contact use
  for them still intended? The comment at `VSIntegration.java:1291` says they are gone.
- Is `VSFlightBackend` to be deleted, or is it the intended seam for force application when the controller
  mixin moves behind the port (C11 PORT-11)?
- `ShipMeetingStop` has no pinning test; a mechanic with a config flag and an event should owe one.
**Bug leads (by reading)**
- Two positional nearest-ship lookups still run in production: `VSShipCrosser.java:243` (`flightComputerAt`)
  and `VSShipCrossingOps.java:408` (`unparkShipAt`) go through `VSBridge.nearestQueryableShip`
  (`VSBridge.java:993`). A second ship or a blockless remnant nearer the probe point is searched or unparked
  instead. The departure parks by name, and a re-cut that finds no ship of its name takes no snapshot.
- The rocket assembler asks `VSIntegration#builtTier2ShipRefusal` before it hands the craft over
  (`TileRocketAssemblingMachine.java:860`), so an over-size or past-reach build is refused with the "ship too
  large" status for both `TOO_LARGE` and `REACH_EXCEEDED` (engine side in [valkyrienskies](./valkyrienskies.md)).

**Doc drift found while mapping** (code first, doc second)
- C11 PORT-3 counts `VSIntegration` at 966 lines (now 1636); PORT-4 and the javadocs at `VSBridge.java:39`,
  `VSIntegration.java:1107` cite the deleted `isAvailable()`; PORT-5 names `setShipsPermanentlyLoaded` and
  PORT-8 a nearest-ship command family, none of which exist in `src/`; PORT-2/9/13 line numbers are stale.
- C8's `ShipFrameTravel` cites are stale (`:225` → `:218`, `installPendingSeed` `:690` → `:740`).
- C7 cites `DeckHold.java:473` for `msg.deckhold.lost`; it is at `:469`.
- `weapons.md:204` cites `TileTurret.java:86-93` for `isOnUnnamedShip`; it is at `:118`.
