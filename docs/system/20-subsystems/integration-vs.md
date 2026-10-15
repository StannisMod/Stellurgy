---
id: integration-vs
owns: [integration/vs/]
entrypoints: [VSIntegration#init, VSIntegration#assembleTier2Ship, VSIntegration#crossShip, DeckFrameTick#update, DeckFrameTick#updatePlayer, DeckFrameTick#followShipPoses, DeckHold#onPlayerTick, DeckHold#onShipUsable, ShipMeetingStop#onWorldTick]
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
(2) the deck — running a body's own update in the frame of the craft that holds it, so it stands, walks, falls and
rides on a deck that is rolled, inverted or moving. Neither the physics nor the crew rules are
described here — C11, C8 and C9 own the clauses; this doc maps where in the code they are kept.

## Responsibility boundary

**Owns**: the port surface and its containment (C11 PORT-1..13); a craft's single identity at assembly
and its durable-id index; the per-ship crossing recipe (cut, paste, re-assemble); the registry watch
that publishes ship events; the crude ship-meets-ship hold; the held-body state and its per-tick
update in the deck's frame (the one mechanism that holds a body); the server-side bound on what a deck client may
claim; the pending deck seeds; the login/crossing deck hold.

**Does NOT own**: the engine (`valkyrienskies/`, ours to edit but described by C11 as the substrate);
the force controller that actually flies a craft (the flight computer tile, `TileAdvancedFlightComputer`, see
MECH-VSI-11); the mixins that hook the entity update, `Entity.move` and the packet handlers (they call into
this package — `mixins-asm-coremod`); the client's deck look and camera (`client-render`); the durable
"aboard" record and the transit/crossing orchestration (`space/` — `ShipAboardTag`, `CrewTransfer`,
`VSShipCrosser`; C18); gravity under the deck (`util/GravityHandler`, which asks `DeckFrameTick#holds`); what a
weapon or shield does with a ship's pose (their own docs).

## Key types

| type | role |
|---|---|
| `VSBridge` | package-private; the ONE class importing engine types (with `VSFlightBackend`); ≈90 static members, all primitives/MC types out |
| `VSIntegration` | public facade over the bridge, no engine type in any signature; also the crossing recipe, the assembly entry, the scans of a shipyard, `init()` |
| `DeckFrameTick` | the deck frame: admission, stay and release, running a held body's own update in the craft's frame, the server player's update/claim/replayed step, pose follow, seeds |
| `DeckBoarding` | the questions around a hold: excluded states, the keep test, the enclosed interior, the pending seed state machine, the deck heading the client steers by, probes |
| `DeckHeld` | per-`Entity` slot (installed by `MixinEntityDeckHeld`) holding that body's deck episode, hold stamp and teleport count |
| `DeckFollowsItsShip` | server `WorldTickEvent` END handler at `LOWEST`: calls `DeckFrameTick#followShipPoses` after the engine has moved the ships |
| `DeckHold` | login/crossing hold that pins a body and re-asks its client to capture; `Holds` is the per-server registry |
| `DeckMovementBound` | server's per-packet region test for a player a deck holds |
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
`:1586`); on the client it reads the interpolator's shown velocity, not the declared one
`[T]` `test/server/TurretOnAShipTest.java:178`. `declaredShipVelocityAtPointFor` answers the same
`v + ω×r` from the craft's DECLARED motion (`ITransformInterpolator#getDeclaredVelocity` on the client;
the physics state on the server, where the two are one number) — what a body's own velocity relative to
the craft is measured against (C23 DECKSYNC-6). `[V]` An exception there is not swallowed silently: it logs
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
(`:500`, `:967-991`) → read the source's durable name and **declare the departure** to the announcer
BEFORE cutting (`VSIntegration.java:538-555`) → cut a tight box (`:558-560`; `abandonDeparture` if nothing was cut, `:561-566`)
→ mark the source FINISHED so the engine collects it on its next tick, loaded or not (`:571`,
`VSBridge.java:854-864`; the source is deliberately not deregistered by hand first — `VSIntegration.java:509-537`)
→ retire the pilot-seat dummies bound to the cut seats (`:583-588`) → force-load the destination footprint,
paste in clear sky, anchor on the first non-air block, and `assembleTier2Ship` the pasted snapshot (`:592-625`).
`snapshotShipOf`/`snapshotShipAt` re-cut a parked ship non-destructively for the transit persistence
(`:648-685`); `pasteAndAssemble` is the paste tail for a restored transit and now returns the ship under
its own name (`:699-744`). If the anchor scan of the pasted box finds only air, the source is already cut and
the ship exists as loose blocks registered nowhere: a `CrossResult` with a null anchor and an ERROR log, the
one destructive failure (`:626-637`). `[T]` a crossing leaves no ship behind:
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

**MECH-VSI-12 — A body is held by an episode on ONE ship; the state lives on the Entity.** The hold
(`DeckFrameTick.Episode`: ship id, deck point, deck-frame velocity, the world motion last written, install
epoch, seed flag) is a field of the entity object through the duck interface `DeckHeld`, installed by
`MixinEntityDeckHeld` (`DeckFrameTick.java:51-86`, `DeckHeld.java:9-28`), so the client's and the server's
copies of one player hold two slots by construction and a hold dies with its object. The state changes at
EDGES (open, release, a seed) and is never re-decided per tick; the deck point is the held value, re-derived
from the world position only after somebody else wrote it (MECH-VSI-14). Every transform of the episode
resolves through its ship id, never re-picked by containment (CREW-C2; `DeckFrameTick#admissionFor` weighs the
candidates once, at admission). `[V]` `DeckBoarding#explainDeckCapture` replicates the admission read-only for
the probe (`DeckBoarding.java:449`).

**MECH-VSI-13 — Admission, stay and release.** `DeckFrameTick#admissionFor` (`DeckFrameTick.java:870-929`)
asks, in order: may a deck hold this body at all (`admissible`, `:753` — an item not in water or lava; a
living body that is not riding or ridden, on the server and on a client only the player it plays; and none of
`DeckBoarding#excludedStateOf`, `DeckBoarding.java:43-66`: no-gravity, riding, elytra, water, lava, ladder,
levitation, and creative flight unless a deck already holds the flyer or may claim him); a DECLARED craft
(`DeckHold.Holds#heldShipId`) first; a body supported by world terrain is never admitted (`:889`); each
candidate craft tested in its own frame for STANDING support (`SUPPORT_PROBE` 0.30 extended by fall speed,
`STANDING_TOLERANCE` 0.05, `:917`) — a declared craft is boarded by the keep test instead; else an enclosed
interior (`DeckBoarding#interiorCandidate`, `:750`: inside the margin-0 region, a deck below and a roof
above). **Stay**: the craft's own subspace region grown by `STAY_REGION_MARGIN` = 4.0, so a jump above the
deck never exits (`:45`, `:543-548`). **Release reasons**, each reported through the `noteReleased` seam:
`excludedState`, `declaredElsewhere`, `shipUnloaded`, `leftShipRegion`, `noDeckBelow` (the keep test
`DeckBoarding#deckMayKeep`, `:719`: a floor within `FLOOR_PROBE_DEPTH` = 6.0 below the feet, or inside the
craft's own region under a roof; a flyer is let go only by leaving the region), `shipGoneDuringUpdate`
(`DeckFrameTick.java:549-551`, `:623`). `[T]` jump keeps the hold
`VSCrewCaptureContractTest#jumpingOnTheTopDeckKeepsTheCaptureAndLandsBackOnIt`; flyer not snapped back
`#aCreativeFlyingExPilotIsNeverSnappedBackByTheDismountHold`; interior reclaim of a body lost mid-cavity
`VSCrewInteriorBoardingTest#aBodyLostMidCavityOfAnEnclosedInvertedShipIsReclaimedByTheDeck`; a flyer ascends
along the deck normal `#aFlyingCrewMemberAscendsAlongTheDeckNormalAndReseatsOnFlightOff`.

**MECH-VSI-14 — The deck frame runs the body's own update.** `DeckFrameTick#runInDeckFrame`
(`DeckFrameTick.java:493-589`): the body is placed at its deck point (the held point, unless somebody else
wrote its position since — `Entity.setPosition` reports every such write through `DeckFrameTick#noteWrite`,
`:597`, and a written position is mapped in), given its deck-frame velocity plus whatever the world added
since (the difference from the world motion the deck itself last wrote, rotated in — an impulse survives
whole; on the tick it is ADMITTED, "what the deck wrote" is the craft's DECLARED velocity at its point,
`declaredShipVelocityAtPointFor`, so a client retiring a misprediction hands the body nothing — C23
DECKSYNC-6), and its UNMODIFIED update runs there: gravity is deck-down, drag lands on the deck's axes, `move`
collides against the deck's real blocks. `mapOut` (`:614-660`) maps position and velocity back to the world,
re-adds the craft's carry at the point (`shipVelocityAtPointFor`), declares the movement claim of the player
this client plays, and suppresses the substrate's own drag anchor. The update of an item or mob is
redirected at `World.updateEntityWithOptionalForce` (`MixinWorldDeckFrameTick`). A position mapped in is lifted
out of any block it overlaps by no more than 8 ulps of the largest coordinate (`MAPPING_NOISE_ULPS`, `:683`;
measured 2026-09-29: worst deck→world→deck round trip 1.49e-8, i.e. 4 ulps there). If the craft vanishes
during the update the body is put back where the world had it and released (`:620-629`). `[T]` still crew on a
steeply rolled deck `VSShipFlightTelemetryTest#crewStaysOnASteeplyRolledDeck…`; an item, an armor stand and a
cow on rolling decks `VSItemOnADeckTest`, `VSLivingBodyOnADeckTest`.

- **MECH-VSI-15** — retired: the outer hull holds no body; it is the physics substrate's (C8 CREW-C11).

**MECH-VSI-16 — The deck is put back after the ships have moved.** The engine advances every ship pose
at `WorldTickEvent` END at `HIGHEST` (`valkyrienskies/.../EventsCommon.java:230-250`), after the entities
have moved, so a held body stands where the deck WAS one tick ago — a tangential lag that grows with its
distance from the rotation axis. `DeckFollowsItsShip` runs at `LOWEST` on END (`DeckFollowsItsShip.java:26`)
and `DeckFrameTick#followShipPoses` (`:99`) re-images every held body through the now-current transform; the
client does the same from its client tick (`ClientDeckFollowsItsShip.java:23`). It skips a player this side
does not play (his client decides), riders and the dead (`DeckFrameTick.java:103-106`). `[T]`
`test/client/VSCrewRidesRollingDeckTest.java` (red-witnessed with `followShipPoses` doing nothing).

**MECH-VSI-17 — Seeds: placing a body on an explicit deck point.** A server placement of a non-player body
(a crossing's carried cargo) is `DeckFrameTick#holdAt`, refused for a body the deck would not take
(`DeckFrameTick.java:185`; caller `AboardBodies.java:245`). For the player a client plays, a deck point sent
as `PacketDeckCapture` is a **pending seed** per body in each client world (`DeckBoarding#pendingSeedsIn`,
`DeckBoarding.java:207`), driven by a pure decision (`pendingSeedDecision`, `:229`): already taken → done;
void when the body was teleported since or mounts something it was not on; a hold that predates the slot wins
(a dismount only); expired after `PENDING_SEED_TTL_TICKS` = 40 dissolves without snapping; an excluded body
waits; else apply once through `DeckFrameTick#holdSeeded` (`:801`), which opens the hold, anchors it
(`Episode.seedAnchored`, so a re-send is absorbed) and maps the point through the pose the client reads now.
A **restore** seed (login, crossing arrival) outranks a hold the client made for itself, and while it waits for
its craft to stream in the body is kept still (`DeckBoarding#holdStillForRestore`, `:390`). A seed the deck
refuses is spent (`noteSeedOutcome` `refused-by-deck`). `[T]` `test/unit/DismountPendingSeedTest` (state
machine). Lifecycle: C31.

**MECH-VSI-18 — The server bounds what a held player may claim.** Movement is client-authoritative and
vanilla's speed check is written for ground, so a body on a moving deck could claim 30 blocks in a tick.
`DeckMovementBound.accepts` applies **only** while a deck holds the player; the region is
`(PLAYER_MAX_OWN_BLOCKS_PER_TICK + carry) × ticks + REGION_FLOOR_BLOCKS`, carry from the same
`shipVelocityAtPointFor` the client was told, ticks the world time since the player's last judged packet
capped at `MAX_CLAIMED_TICKS` (2.0, 1.0 and 10.0, `DeckMovementBound.java`). The positions are read from the
server immediately before and after the packet, so a server-side teleport moves both and never looks like a
claim (`MixinNetHandlerDeckMovementBound`). For a player the bound judges, vanilla's own speed check stands
down (`DeckMovementBound#judges`, via the redirect of the first `isInvulnerableDimensionChange` call in
`processPlayer`); a body no deck holds faces vanilla's check alone. A refused step puts him back; it does not
ban him. Per-server (`ServerState#deckMovement`, `forget` on logout). The constants are `tunable` (the javadoc
records a measured maximum of ≈0.4). `[T]` `VSCrewCaptureContractTest#aWildClientSideStepOnADeckNeverBecomesADeclaredPosition`
(a 6-block step is refused; red-witnessed with `accepts` ratifying all). C23 DECKSYNC-7.

**MECH-VSI-19 — Hold a body on its deck while nothing else can.** `DeckHold` puts a body on its deck point
with ONE teleport and re-sends the SUBSPACE deck point in `PacketDeckCapture` (a restore seed) until the deck
holds the body on **the hold's own ship** (`DeckHold#onPlayerTick`). It starts at (a) login of a player whose
durable aboard record says STANDING (`DeckHold#onPlayerLoggedIn`); (b) `Holds#pinInPlace` — a crew member whose
ship is being cut out from under him, no ship to resolve; (c) `Holds#holdOnDeck` — the far side of a crossing,
ship and point known (callers `CrewTransfer`). A hold with only a durable id resolves **lazily**: at arm time,
and again on `ShipLifecycleEvent.ShipUsable` and `FlightComputerLiveEvent`, never on a clock; an unresolved tick
keeps the body from falling and nothing more (`DeckHold#holdUnresolved`), stands down entirely when a deck
already carries him, and says so once. It ends on: the deck holding him on its own ship, an excluded state, a
teleport after its own (`voidHold`), `ShipLifecycleEvent.ShipUnnamed` for its craft (destroyed — logged and shown
on the action bar, `DeckHold#onShipGone`), or the **give-up** after `HOLD_WINDOW_TICKS` = 200 ticks, which is a
refusal, logged at ERROR with the cause and shown to the player (`DeckHold#giveUp`). A **departure is
deliberately not subscribed**: the hold must outlive the cut and the arrival re-arms it. Per-server registry
`ServerState#deckHolds`; `PlayerRelease` drops it. `DeckHold#reconcileSeatMount` folds a relogged pilot's
re-spawned dummy into the seat's resident, or — seat taken while he was away — stands him on the deck and tells
him who took it. `[T]` seat taken offline: `test/client/VSPilotSeatTakenWhileOfflineTest`; one teleport:
`VSCrewRelogPersistenceTest#aDeclaredHoldPutsHimOnItsDeckPointByOneTeleport`. Lifecycle: C31.

**MECH-VSI-20 — Seams for observers are empty production methods.** `DeckFrameTick#noteHeldTick`,
`#noteEntered`, `#noteReleased` and `DeckBoarding#noteSeedOutcome`, `#clientCensusTick`, `EntityDummy#noteWindowEnded`
have empty bodies in production (`DeckFrameTick.java:668`, `:775`, `:779`; `DeckBoarding.java:115`, `:536`);
the test tree injects at their HEAD to read the values that are parameters there, so no telemetry ships. A
change to their parameter lists is a change to the test harness's mixin targets. `ShipLocalMove.resolve` is a
separate, off-by-default experiment: `ShipLocalMoveControl` is inert until the probe arms it for one entity id
(`ServerState#shipLocalMove`).

**MECH-VSI-21 — A held player on the server: his update, his packet, his replayed step.** His living update is
not run by the world tick: his network handler runs it, once a tick, and puts him back where his client last had
him. `MixinNetHandlerPlayerDeckFrame` redirects that call to `DeckFrameTick#updatePlayer` (`:221`), which runs the
update in the deck's frame from the deck point his client's last packet DECLARED — taken onto the deck as the
packet is handled (`DeckFrameTick#claimArrived`, `:338`, at the tail of `processPlayer`), held between packets,
re-derived from his world position only after somebody else has written it — and which hands his velocity back
by rotation alone. The handler's own write of him back after his update is the deck's bookkeeping
(`DeckFrameTick#restoreAfterUpdate`, `:317`). His `move(PLAYER)` replay collides against the deck's blocks in
the deck's frame (`DeckFrameTick#moveHeld`, `:367`, called from `MixinEntityShipLocalMove`, `priority = 0`,
which must run before the physics mod's own `move` callback). On his own CLIENT the living update of the player
the client plays runs in the deck's frame (`MixinEntityLivingDeckFrame`, `DeckFrameTick#updateLocalPlayerLiving`,
`:447`), steered by the heading he holds on the deck (`DeckBoarding#deckYawDeg`), so the position his client
then sends is already mapped back. A player a deck lets go leaves with the craft's carry at his point added to
his server velocity (`DeckFrameTick#keepCarry`, `:845`). `[T]` `VSCrewRidesRollingDeckTest`,
`VSCrewCaptureContractTest#aTeleportAlongTheDeckIsWhereTheDeckHoldsHimFromThen`.

**MECH-VSI-22 — A teleport is counted on the body, per side.** `DeckHeld#stellurgy$teleports` is raised by
`MixinNetHandlerServerTeleportCount` (every `setPlayerLocation` that puts the player somewhere the server does
not already have him) and `MixinNetHandlerClientTeleportCount` (every applied position-look that puts him
somewhere other than the previous one did); vanilla's re-send of an unconfirmed teleport and its "moved too
quickly" correction are not counted. A seed, the dismount window and a resolved declared hold remember the
count they began at, and a different count voids them (C31 HOLD-2). `[V]` `DeckHeld.java:26-28`,
`DeckBoarding.java:148-197`, `DeckHold.java:397-398`. `[T]` `DismountPendingSeedTest#aSeedWhoseBodyWasPutElsewhereOrMountedIsVoidAndNeverApplies`.

## State & persistence

Nothing in this package writes NBT of its own. What it touches: the engine's per-ship record
(`ShipData.stellurgyDurableId` — written at assembly `VSBridge.java:110`, re-bound `:232`); the flight
computer's `shipId` (read, minted on a duplicate — `VSIntegration.java:176-182`, `:238`); the snapshot NBT
a transit re-cuts (`:667-685`, owned by `space/`). The durable "aboard" record the deck hold reads is
`space/ShipAboardTag` (`DeckHold.java:216`). Per-body hold, pending seeds, `Holds`, `DeckMovementBound`'s
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
- **INV-VSI-06 [A]** A velocity read at a point is in blocks per tick, on both sides, with the same
  expression (`TurretOnAShipTest.java:178`).
- **INV-VSI-07 [V]** A held body resolves every transform through its episode's ship id for the whole
  episode; containment weighs candidates only at admission (`DeckFrameTick#admissionFor`, `DeckFrameTick.java:870-929`).
- **INV-VSI-08 [V]** What the world adds to a held body between two updates is the difference from the world
  motion the deck itself last wrote, taken into the deck's frame and added; the carry is never re-sampled
  into the body's own velocity (`DeckFrameTick.java:553-568`, `:638-645`).
- **INV-VSI-09 [V]** A held body is re-imaged on its deck point after the substrate has advanced the
  ships (`DeckFollowsItsShip.java:26` priority; `VSCrewRidesRollingDeckTest.java:95`).
- **INV-VSI-10 [A]** The pending-seed state machine: a taken seed no-ops every re-send; a capture that
  predates the slot wins for a dismount and loses for a restore; an excluded body is waited for; an expired
  slot never snaps (`DismountPendingSeedTest.java:38-114`).
- **INV-VSI-11 [V]** The server's deck bound refuses a step no input plus the deck's carry could produce and
  has no opinion about a body no deck holds (`VSCrewCaptureContractTest#aWildClientSideStepOnADeckNeverBecomesADeclaredPosition`; `DeckMovementBound#accepts`).
- **INV-VSI-12 [V]** A declared deck hold ends only by the deck holding the body on its own ship, an excluded
  state, a teleport after its own, the destruction of its craft, or an announced give-up; it survives a
  departure (`DeckHold#onPlayerTick`, `#onShipGone`).
- **INV-VSI-13 [A]** A machine on a shipyard block no ship claims does nothing
  (`TurretStandaloneTest.java:334`).
- **INV-VSI-14** — retired: the outer hull is the substrate's (C8 CREW-C11).
- **INV-VSI-15 [A]** A seat bound to blocks a crossing cuts is retired with them, matched on the seat and
  never on where the dummy rides (`CutLeavesNothingBoundTest.java:38`, `:75`).
- **INV-VSI-16 [V]** The one mutable static in the aboard code is the client look port: written once by
  `DeckBoarding#installClientLookSource` (`DeckBoarding.java:884`), and a second install throws (caller
  `ClientProxy#preinit`). A null port therefore means "dedicated server" and nothing else.
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
  (`VSShipCrosser.java:240`), `parkShipAt` (`:317`), `snapshotShipAt` (`:527`) and `unparkShipAt`
  (`VSShipCrossingOps.java:409`). Each has an id-keyed twin; the callers listed are not on it.
- **A crossing whose re-assembly is refused reports success.** `CrossResult.ok()` is `anchor != null`
  (`VSIntegration.java:433-435`), but `assembleTier2Ship` returns null on a footprint with no flight computer
  (`:155-163`) while the anchor was found by the air-scan (`:603-625`); the source is already cut. `Crossed`
  is then built with a null `shipUuid` (`VSShipCrosser.java:459`, `:563`, `VSShipCrossingOps.java:144`).
- **A spawn the engine refuses leaves no trace on this side.** The drain skips a ship that is too big or
  attached to bedrock with one `System.err` line and clears the queue
  (`WorldServerShipManager.java:195-207`, `:376`), while `assembleTier2Ship` has already returned the uuid
  (`VSBridge.java:112-115`), so the caller holds the id of a ship that was never spawned.
- **A craft that does not map releases the body.** A deck update that cannot get the ship transform releases
  the hold (`shipUnloaded` / `shipGoneDuringUpdate`, `DeckFrameTick.java:545`, `:623`) and puts the body back
  where the world had it; production logs nothing there.
- **A gate that reads "unloaded" releases.** A ship that unloads on this side releases its crew
  (`shipUnloaded`); the declared deck hold exists for the window where that is wrong.
- **A held body whose chunk is not loaded is not re-seated**: `followShipPoses` walks `world.loadedEntityList`
  (`DeckFrameTick.java:100`).
- **Some deck edges are not recorded**: a declared hold's capture and excluded-state ends leave no record, and
  an unresolved hold is not voided by a teleport (C31 HOLD-4, HOLD-2).
- **Weak spots of the crude hold:** boxes not hulls, nothing conserved, a craft nobody flies never stops
  (class javadoc `ShipMeetingStop.java:23-52`).

## Integration seams

- **Mixins calling in**: `MixinWorldDeckFrameTick` (item and mob update), `MixinNetHandlerPlayerDeckFrame`
  (server player update and claim), `MixinEntityLivingDeckFrame` (the local player's living update),
  `MixinEntityShipLocalMove` (`moveHeld`), `MixinEntityDeckHeld` (the `DeckHeld` slot, `setPosition` writes),
  `MixinNetHandlerDeckMovementBound`, `MixinNetHandlerServerTeleportCount`, `MixinNetHandlerClientTeleportCount`. The force controller is the flight computer tile itself (`TileAdvancedFlightComputer` implements
  `IPhysicsBlockController`); no production mixin targets the vendored tree or our own tiles.
- **Events published**: `ShipCollisionEvent` (`ShipMeetingStop.java:119`); consumed: `ShipLifecycleEvent.ShipUsable`,
  `ShipLifecycleEvent.ShipUnnamed`, `FlightComputerLiveEvent` by `DeckHold` (`DeckHold#onShipUsable`,
  `#onFlightComputerLive`, `#onShipGone`).
- **Callers outside**: `space/VSShipCrosser`, `VSShipCrossingOps`, `CrewTransfer`, `AboardBodies`,
  `SpaceSubsystemEvents` (altitude band), `TileAdvancedFlightComputer` (`ensureShipPhysicsEnabled`, velocity),
  weapons/sensor/shield/damage (ids, frames, per-tick velocity), `PlayerRelease` (hold release),
  `GravityHandler` (the `DeckFrameTick#holds` gate).
- **Wire**: `PacketDeckCapture` (ship id + SUBSPACE triple + restore flag) — C2/C23 own it.
- **Registration**: `DeckHold` from `Stellurgy.java:1619`; the client look port from `ClientProxy#preinit`.

## Config surface

| flag | mechanic | full-disable path |
|---|---|---|
| `shipsCollide` (ROCKET, synced, default true) | MECH-VSI-09 | false ⇒ ships pass through each other, no event (`ShipMeetingStop.java:83-87`) |

The deck bound's three constants and the stay margin are code constants (`tunable`), not config.

## Test coverage

Unit: `VSBoundaryContainmentTest`, `DismountPendingSeedTest`, `DeckFrameLookTest`, `CutLeavesNothingBoundTest`.
Server e2e: `VSCrossingLeavesNoShipBehindTest`, `VSShipTransitPersistTest` (restored transit),
`TurretOnAShipTest`, `TurretStandaloneTest`. Client e2e: `VSCrewCaptureContractTest`,
`VSCrewRidesRollingDeckTest`, `VSCrewInteriorBoardingTest`, `VSPilotSeatTakenWhileOfflineTest`,
`VSCrewRelogPersistenceTest`, `VSDeckCaptureAndDismountTest`; server: `VSItemOnADeckTest`, `VSLivingBodyOnADeckTest`.
**No test found** for: `ShipMeetingStop` and `shipsCollide`; `shipUuidOfDurableId` and its rebind; `flightComputerOfNamedShip`; `carryHeldBodies`;
`DeckHold`'s give-up and ship-gone ends.

## Open questions

- Should the facade's positional forms be removed (their id-keyed twins exist) — or is a first-contact use
  for them still intended? The comment at `VSIntegration.java:1291` says they are gone.
- Is `VSFlightBackend` to be deleted, or is it the intended seam for force application when the controller
  mixin moves behind the port (C11 PORT-11)?
- `ShipMeetingStop` has no pinning test; a mechanic with a config flag and an event should owe one.
**Bug leads (by reading)**
- The positional nearest-ship lookups still run in production: `VSShipCrosser.java:240,317,527` and
  `VSShipCrossingOps.java:409` go through `VSBridge.nearestQueryableShip` (`VSBridge.java:902`). A second
  ship or a blockless remnant nearer the probe point is parked, snapshotted or searched instead.
- A crossing whose paste re-assembles no ship reports `ok()`, verified.
- The engine refuses an over-size or bedrock-attached spawn with one `System.err` line while
  `assembleTier2Ship` has already returned the uuid (`VSBridge.java:112-115`; engine side in
  [valkyrienskies](./valkyrienskies.md)).

**Doc drift found while mapping** (code first, doc second)
- C11 PORT-3 counts `VSIntegration` at 966 lines (now 1636); PORT-4 and the javadocs at `VSBridge.java:39`,
  `VSIntegration.java:1107` cite the deleted `isAvailable()`; PORT-5 names `setShipsPermanentlyLoaded` and
  PORT-8 a nearest-ship command family, none of which exist in `src/`; PORT-2/9/13 line numbers are stale.
- C7 cites `DeckHold.java:473` for `msg.deckhold.lost`; it is at `:469`.
- `weapons.md:204` cites `TileTurret.java:86-93` for `isOnUnnamedShip`; it is at `:118`.
