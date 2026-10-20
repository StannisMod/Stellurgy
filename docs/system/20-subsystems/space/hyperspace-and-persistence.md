---
id: space/hyperspace-and-persistence
parent: space/00-overview.md
covers: MECH-SPC-09, 10, 15..19 · INV-SPC-03, 05, 07, 08, 09, 11, 12, 13, 14, 18
---

# space — hyperspace, the ledger, the crew and a restart

What survives: the ledger that says where every ship is, the space clock, a hyperspace jump in flight
and across a restart, the people carried through a cut, and the record that puts a player back aboard
at login. Boundary, lifecycle and config are in the [overview](00-overview.md); addresses, the slot
pool and the in-galaxy crossings are in [cells-and-crossings](cells-and-crossings.md).

## Mechanics

### MECH-SPC-09 — The ledger: where a ship is, never which world
`ShipLedger` maps the **durable** ship id (the flight computer's UUID, never the physics mod's) to `{coord,
SETTLED|IN_TRANSIT, positionKnown}` (`ShipLedger.java:35-68`) [V]. A SETTLED ship owns one occupant refcount on
its cell; whoever moves it releases that and re-settles (`ShipLedger.java:23-26`; for example
`CellCrossingController.java:260,338,348`) [V]. Every `settle` tells the ledger's `SettleObserver` after the
entry is written (`ShipLedger.java:95-98`); production's observer realizes a gas giant whose zone the address
names (`SpaceSubsystem.java:105, 552`; universe MECH-UNI-13) [V][T]. `settle` is therefore the one place zone
membership is WRITTEN, and a new way into a cell must end in it. `updatePosition` — which never changes the
cell — is the flight computer's per-tick self-report and
is a no-op unless SETTLED (`ShipLedger.java:114-120`; caller `TileAdvancedFlightComputer.java:529-530`) [V][T]
`ShipLedgerTest.java:53`. No slot dimension is stored anywhere (`ShipLedgerDataTest.java:80`) [T].
`ShipLedgerData` is a `WorldSavedData` named `stellurgy_shipledger` on the overworld map storage; keys
`ships[{shipId,galacticCoord}]`, `transits[]`, `cellVisits[{cell,visit}]`, `spaceClock`
(`ShipLedgerData.java:42-46,224-285`) [V]. Only SETTLED ships are in `ships`; a flying one is carried by its
`TransitRecord`, and `replaceAll` **refuses** (returns the ship ids, touches nothing) a snapshot that would store a
flying ship nowhere (`ShipLedgerData.java:127-169`) [T] `ShipLedgerDataTest.java:102,134`. Save point: dim-0
`WorldEvent.Save` stages the clock always, the fleet only while the subsystem is up, and writes `saveAllData()` in a
`finally`; neither step propagates (`SpaceSubsystemEvents.java:131-238`) [V][T]
`SpaceRestartPersistenceTest.java:224,303`.

### MECH-SPC-10 — The space clock is the subsystem's own counter, on both sides
`ServerState.advanceSpaceClock()` runs **once per server tick**, in `onServerTick` *before* the null-subsystem
return (`SpaceSubsystemEvents.java:64-80`) [V]; nothing else increments it (the tick in `SpaceEventHandler` only
reads, `SpaceEventHandler.java:360-373`). `SpaceSubsystem.spaceClock()` answers the server counter on the server
and the synced baseline on a client (`SpaceSubsystem.java:804-808`) [V]. It is restored on start *before* the
`live == null` return, because it dates stamps outside this subsystem (`SpaceSubsystem.java:290-306`), and stored
monotonically, never rolled back by a refused fleet write (`ShipLedgerData.java:216-219`) [V][T]
`ShipLedgerDataTest.java:199`, `SpaceClockIsTheSubsystemsOwnTest.java:110`. `SpaceClockSync` is a baseline plus
local client ticks, `hasSync()` tells "not told" from tick 0, and a later baseline may move it backwards
(`SpaceClockSync.java:35-61`) [T] `SpaceClockSyncTest.java:26,68`. The server pushes a baseline at login and every
200 ticks, each player on his own phase (`SpaceEventHandler.java:243-247,345-373`) [V]. Contract:
[C16](../../30-contracts/C16-space-clock.md) CLOCK-1..5.

### MECH-SPC-15 — Hyperspace transit: priced once, parked in a lane, finished only with everyone aboard
`beginTransit` prices the flight **once** through both cells' frames at the departure tick
(`ShipTransitManager.java:445-446`) [V]; if `JumpSpeed.transitTicks ≤ 160` (= 60 departing + 100 arriving) **and
the target cell can get a slot now** (`jumpsDirect`, the one predicate the departure and the console forecast both
ask) the jump is a **direct crossing** and nothing below runs (`ShipTransitManager.java:447-472,1000-1044`) [V][T]
`VSShortJumpCrossesDirectlyTest.java:44,72`. A short jump into a full pool is flown through hyperspace instead: the
direct crossing loads the destination while the origin is still held and could only refuse after the burst was paid,
while this path releases the origin first and waits at arrival (2026-10-06) [T] `ShipTransitManagerTest.java:350`.
Otherwise: allocate a lane; zero the origin computer's cruise
(`ShipTransitManager.java:491,1329-1364`) [T] `VSJumpDumpsTheCruiseTest.java:79`; capture crew **before** the cut;
floor snapshot; depart crossing (`ShipTransitManager.java:495-501`). A failed depart frees the lane and re-seats
the crew at the origin (`ShipTransitManager.java:501-522`) [T] `ShipTransitManagerTest.java:424,966`; success
releases the origin cell, marks the ledger `IN_TRANSIT` against the TARGET coordinate and queues seating onto the
parked hull (`ShipTransitManager.java:524-560,1282-1290`) [T] `ShipTransitManagerTest.java:256,442`. Flight
progress is a scalar (`ShipTransit.java:82-91`) [T] `ShipTransitTest.java:30,43`, paused by `OfflineProgress` —
unmanned always advances; `CREW_ONLINE` needs ≥ 1 aboard member online (`OfflineProgress.java:45-58`) [T]
`OfflineProgressTest.java:48,55`. **A full pool at arrival makes the ship WAIT**, never fail: the target cell's
`materialize` is retried each tick while the ship stays parked in its lane with its crew aboard and the ledger at
`IN_TRANSIT`; the crew is told once (`msg.shiptransit.arrivaldelayed`), nothing is charged — the burst was the
jump's only price ([hyperdrive](../hyperdrive.md) MECH-HYP-12) — and it lands the tick a slot frees
(`ShipTransitManager#tickTransits`) [T]
`ShipTransitManagerTest#anArrivalIntoAFullPoolWaitsInItsLaneAndLandsOnceASlotFrees`. The jump gate warns of it before the burst (ADVISORY `msg.jumpgate.destinationfull`). On arrival:
materialize the target **once**, paste **once**, resolve the arrival
coordinate **once** (`SpaceSubsystem.arrivalStandoff`, a ring off every body of the cell; a cell with no body is
left untouched, `SpaceSubsystem.java:398-426`), settle the pose, and *stay in the map* until `crewIsAboard`
(`ShipTransitManager.java:627-703`) [V][T] `ShipTransitManagerTest.java:1057,679`. Finish: free the lane,
`ledgerSettle` the **placed** coordinate, mark dirty, announce (`ShipTransitManager.java:704-730`) [T]
`ShipTransitManagerTest.java:275,461`. Lanes: square spiral, 2048 blocks apart at Y 128
(`HyperspaceTiles.java:25-27,159-183`); the allocator lives on the one `HyperspaceWorld`, not on a manager
(`HyperspaceWorld.java:52-59`); `laneIndexAt(x,z)` is a pure function of position, `-1` between lanes and for NaN
(`HyperspaceTiles.java:89-114`) [V][T] `HyperspaceTilesTest.java:61,76,93`. Contract: C18 JUMP-1..6, 13, 14.

### MECH-SPC-16 — A jump survives a restart; boot reconciles what hyperspace kept
A `TransitRecord` is `{shipId, org, tgt, distance, travelled, arrivalTick, lastTicked, speed, crew[], snapshot?,
lane?, hyperAnchor?}` — origin name, target name, progress, **never a raw absolute** (`TransitRecord.java:86-122`)
[V]. Export does no world work (`ShipTransitManager.java:1109-1119`) [T]
`ShipTransitManagerTest.java:760`; the parked hull's block snapshot is re-cut every 600 ticks while anything flies
and once at server stop (`ShipTransitManager.java:594-597,1139-1168`; `SpaceSubsystem.java:727-741`) [T]
`ShipTransitManagerTest.java:734,801`. `importTransit` reclaims the lane first, then resumes as the parked hull if one
stands there, else rebuilds from the snapshot, else drops the record (`ShipTransitManager.java:1179-1219`) [T]
`ShipTransitManagerTest.java:1117,1134,1232,1247`. Boot order: clock → ledger → visits → load hyperspace **only if its
folder exists** → import records → `reconcileParkedShips`, which deregisters every hull in a lane no record claims and
retires the lane (`SpaceSubsystem.java:290-349`; `ShipTransitManager.java:1240-1271`) [T]
`ShipTransitManagerTest.java:1153,1182,1209`. Hyperspace content is keyed by a world-named folder, so a different
dimension id next boot finds the same ships (`SpaceSlotPool.java:403-405`) [T]
`HyperspaceSurvivesARestartTest.java:201`. A restored (snapshot-only) transit has no world holding its crew:
`crewDimensionOf` answers `-1` (`ShipTransitManager.java:812-823`) and login falls back to spawn
(`LoginRestore.java:175-179`) [T] `LoginRestoreTest.java:254`. Contract: C18 JUMP-9/10.

### MECH-SPC-17 — Hyperspace is one world; leaving the ship in it kills
`HyperspaceWorld.register()` runs at boot, the world loads lazily and is kept loaded; `isHyperspace(world)` asks the
*side's* answer and a null world is never hyperspace; `release()` withdraws the registration at stop
(`HyperspaceWorld.java:77-112,159-181`) [V][T] `HyperspaceDimIdIsAskedPerSideTest.java:22`. `HyperspaceVoid`
counts consecutive ticks a player in hyperspace is aboard nothing (a seat dummy with a seat, or the deck resolver);
at 200 it kills with damage that bypasses armour; creative and spectator are exempt; the counter is server state,
never persisted (`HyperspaceVoid.java:75-165`) [V] — its only scenario test sits in an `@Ignore`d class (Test
coverage). Contract: C18 JUMP-8.

### MECH-SPC-18 — Crew and loose bodies cross a cut by their flight-computer-relative point
`CrewTransfer.capture` finds seated crew through `EntityDummy`s within 8 blocks whose seat links to *this* computer,
plus standing crew by the deck resolver's identity; it dismounts, retires the dummy and pins standing crew in
place (`CrewTransfer.java:61,142-280`) [V]. `reseat` matches a seat by AFC-link offset **and** durable ship id; a
seated rider is mounted on a bound dummy, a standing one is placed at his deck point and held
(`CrewTransfer.java:297-392,458-500,870-890`) [V][T] `CrewTransferSeatMatchTest.java:54,62`. A seat whose fresh
mount the world refuses leaves that rider NOT seated: `reseat` answers not-all-seated with a `mountRefused` block,
and the caller comes back exactly as for a seat not yet up (`CrewTransfer.java:379-389`) [T]
`VSShipEntryClientGroupTest.java:701`. `rebindAcrossAssembly` secures the fresh mount BEFORE retiring the stale one
and answers `MOUNT_REFUSED` when the world refuses it, leaving the pilot on his stale mount for the queue's next pass
 [T] `VSPreAssemblyBoardingPilotControlTest#aPilotWhoseRebindIsRefusedAMountIsReboundOnceTheWorldTakesOne`.
A second cut re-reads
postures (`CrewTransfer.java:177-204`; called at `VSShipCrosser.java:363-369`) [V]. Offline crew are skipped — the
login restore owns them (`CrewTransfer.java:429-436`). `AboardBodies` stows every non-crew, non-riding body inside
the hull's stay region (+1 block) as NBT and kills it at the cut, then releases it at the same ship-relative offset,
motion zeroed, its chunk loaded by `ArrivalSpawn`, and *held* to the deck; release is all-or-nothing on readiness
(`AboardBodies.java:80-231`; `ArrivalSpawn.java:52-70`) [V][T] `VSJumpCarriesLooseBodiesTest.java:53`,
`VSShipCellSeamTest.java:262,470`. `ShipRelativePoint` measures from the computer's raw block origin both ways
(`ShipRelativePoint.java:38-52`) [T] `ShipRelativePointTest.java:43`. `AssemblyCrewRebind` carries a seated pilot
across the *asynchronous assembly* (1200 ticks, 3-tick off-mount debounce) (`AssemblyCrewRebind.java:40-159`) [V].
Contract: C18 JUMP-3/4/11/12.

### MECH-SPC-19 — The aboard record has one deriver; login restore is a pure, total decision
`AboardRecord.reconcile` derives the record from state (seated on a seat dummy bound to a seat linked to a computer →
seat offset; standing → deck offset), stamps it when it changed, and **clears only on positive evidence** (his ship is
loaded here and he is not on it) (`AboardRecord.java:82-201`) [V]; it runs every 20 ticks and at logout
(`SpaceEventHandler.java:81,262-268,394-398`). The record lives in the player's bindings capability, NBT key
`stellurgyShipAboard` `{shipId, galacticCoord?, inTransit?, afcDx/Dy/Dz, standing?+standDx/Dy/Dz}`; an absent posture
reads SEATED, an absent coordinate means "in no cell" (`ShipAboardTag.java:55-61,221-251,265-288,313-331`) [V][T]
`ShipAboardTagTest.java:41,111,131`; `saysSpaceborne = presence || inTransit` (`ShipAboardTag.java:151-163`).
`onPlayerLoadFromFile` opens the restore when the record says spaceborne or the saved dimension is a subsystem world
(`SpaceEventHandler.java:148-175`). `LoginRestore.resolve` never returns null or throws: no tag → orphan; ledger
unknown → `SHIP_UNKNOWN`; `IN_TRANSIT` → hyperspace, **no cell claimed**; `SETTLED` → materialize the **ledger**
coordinate, never the tag's; orphan = bed, else overworld spawn (`LoginRestore.java:159-234`) [T]
`LoginRestoreTest.java:145,161,226,240,270`. A SETTLED restore names the cell it claimed (`Placement.claimedCell`,
`LoginRestore.java:133-149,197-205`); `heldCells` keeps **that coordinate** per player and logout or a release
dematerializes it — never the ledger's coordinate at the time, which a carried, jumped or descended ship has already
moved (`SpaceEventHandler.java:192-197,264-272,329-336`) [V][T] `SpaceCellRelogGroupTest.java:511`; `SHIP_UNKNOWN`
clears the tag, queues a notice and calls
`PlayerRelease.toTheWorld` (`SpaceEventHandler.java:196-225`) [T] `PlayerReleaseContractTest.java:184`. A seated
returner is re-seated by a retry of up to 200 ticks (`SpaceEventHandler.java:384-451`). Contract:
[C13](../../30-contracts/C13-space-presence.md) PRES-1..11.

## State & persistence

| key / location | owner | notes |
|---|---|---|
| `stellurgy_shipledger` (MapStorage, overworld): `ships[]`, `transits[]`, `cellVisits[]`, `spaceClock` | `ShipLedgerData` | `ShipLedgerData.java:42-46,224-285`; `positionKnown` is **not** stored (Open question 6) |
| `TransitRecord`: `shipId arrivalTick lastTicked speed distance travelled org tgt crew snapshot lane hyperAnchor` | `TransitRecord` | `lane`/`hyperAnchor` absent when none (`TransitRecord.java:113-120`) |
| player capability: `stellurgyShipAboard` | `ShipAboardTag` via `IPlayerBindings` | survives death; shape in MECH-SPC-19 |
| `<world>/advRocketry/spacepool/cell_<key>/`, `…/hyperspace/`, `…/spacepool/slot<dim>/` | `SpaceSlotPool`, `WorldProviderSpaceSlot` | the first two are durable by *name*, the last is scratch (`SpaceSlotPool.java:389-405,435-439`) |
| in memory per server: ledger, transit map, `heldCells`, `pendingSeats`, adrift counters, `SlotBindings`, lane allocator, crew/body stashes | `SpaceSubsystem`, `ServerState` parts | die with the server; stashes deliberately not persisted (`VSShipCrosser.java:75-88`) |

Pre-0.1.0: no migration anywhere in this root; a changed format changes in place (project rule).

## Invariants

- **INV-SPC-03 [V]**  The transit map and the ledger never disagree: in the map ⇔ `IN_TRANSIT`
  (`ShipTransitManagerTest.java:679`). Every put into the map is followed by the ledger's `beginTransit` and every removal by `settle`: `ShipTransitManager.java:535-536,699-705,769-770,1210-1211`.
- **INV-SPC-05** [V] The ledger holds a coordinate, never a slot dimension; the dimension is derived
  (`ShipLedger.java:11-16`; `SpaceManager.java:284-290`; `ShipLedgerDataTest.java:80`).
- **INV-SPC-07** [V] The space clock advances exactly once per server tick, is monotonic across saves and is not
  rolled back by a declined fleet write (`SpaceSubsystemEvents.java:76,166-176`; `ShipLedgerDataTest.java:199`).
- **INV-SPC-08** [V] The fleet write is all-or-nothing and a save-point failure never reaches the server
  (`ShipLedgerData.java:127-169`; `SpaceSubsystemEvents.java:208-220`; `ShipLedgerDataTest.java:134`;
  `SpaceRestartPersistenceTest.java:303`).
- **INV-SPC-09** [V] A transit whose crew is not aboard stays open; it never reports an arrival around a stranded
  player (`ShipTransitManager.java:690-703`; `ShipTransitManagerTest.java:1057`).
- **INV-SPC-11** [V] A hyperspace lane holds at most one ship: the allocator belongs to the world and a reclaimed
  lane is never re-issued (`HyperspaceWorld.java:52-59`; `ShipTransitManagerTest.java:1134`).
- **INV-SPC-12** [V] The aboard record has one *deriver*; a clear needs the ship present and the player demonstrably
  not on it (`AboardRecord.java:100-110`).
- **INV-SPC-13 [V]**  `LoginRestore.resolve` is total — no null, no throw, a loggable `Reason` for every fallback
  (`LoginRestore.java:159-234`; `LoginRestoreTest.java:270`).
- **INV-SPC-14** [V] Hyperspace's saved content is named after the world, never after the per-boot dimension id
  (`SpaceSlotPool.java:403-405`; `HyperspaceSurvivesARestartTest.java:201`).
- **INV-SPC-18** [V] Statics here are effectively final and written once by the mod in pre-init (`SpaceSlotPool.java:69`,
  `HyperspaceWorld.java:35`); every other per-server fact lives in `SpaceSubsystem` / `ServerState` and dies with the
  server (`SlotBindings.java:17`; `SpaceSubsystemOwnershipTest.java:58,86`).

## Failure modes & edge cases

- **An arrival that cannot finish** keeps retrying in the normal path; after 200 attempts the transit settles "the hard
  way" at the paste site and messages the crew (`ShipTransitManager.java:731-789`), and the shared crossing calls
  `abandoned` after 200 (`ShipCrossingService.java:281-295`). C18 JUMP-15 rules both out as a design; the code is not
  yet rewritten (the design is the authority).
- **A hull no record claims** in hyperspace is deregistered at boot; if that fails the lane is still retired
  (`ShipTransitManager.java:1258-1268`).
- **A seat held by someone else** leaves the rider standing aboard and tells him once (`CrewTransfer.java:375-391`).
- **A restored transit with logged-in crew**: no physical ship exists, he is placed at spawn (MECH-SPC-16).
- **A world refusing a body**: `ArrivalSpawn` logs ERROR naming the body now in no world (`ArrivalSpawn.java:61-69`).

## Test coverage

`INV-SPC-03` ShipTransitManagerTest.java:679 · `-05` ShipLedgerDataTest.java:80 · `-07` ShipLedgerDataTest.java:199,
SpaceClockIsTheSubsystemsOwnTest.java:110 · `-08` ShipLedgerDataTest.java:134, SpaceRestartPersistenceTest.java:224,303 ·
`-09` ShipTransitManagerTest.java:1057 · `-11` ShipTransitManagerTest.java:1134 · `-13` LoginRestoreTest.java:270 · `-14`
HyperspaceSurvivesARestartTest.java:201 · `-18` SpaceSubsystemOwnershipTest.java:58,71,86 · `-12` is `[V]` only.

**What the corpus does not hold, or holds only behind `@Ignore`:**
- `HyperspaceVoid`: the only scenario (`VSTransitCrewGroupTest.java:1096`) is in a class `@Ignore`d at
  `VSTransitCrewGroupTest.java:56` ("HELD FOR THE DECK CONTRACT"); so are the crew-carry scenarios C18 cites for
  JUMP-1/2/3/4/8/11.
- The standing-pilot relog scenario is `@Ignore`d (`SpaceLoginRestoreSeatedPilotTest.java:99`), as are two relog
  scenarios in `SpaceCellRelogGroupTest.java:205,305`.
- `AssemblyCrewRebind` has only a mixin watcher (`MixinAssemblyCrewRebindEvents.java`). The `heldCells` pairing is
  pinned by `SpaceCellRelogGroupTest.java:511`, the refused mount by `VSShipEntryClientGroupTest.java:701`, and
  `linkMoonsToTheirParents` by `MoonParentInTheSkyFeedTest.java:69`.

## Open questions

Leads by reading, not reproduced.

4. **`ShipTransitManager.java:731-789` and `ShipCrossingService.java:281-295`** keep give-up paths that C18 JUMP-15
   forbids (a distance).
6. **One-way state.** `ShipLedger.setPositionKnown` has no production caller yet and `positionKnown` is not
   persisted: "position unknown" is a PLANNED mechanic — a misjump leaves a ship position-unknown until it
   re-localizes (`universe-model.md`, `space-model.md`) — so the field waits for its writer rather than
   being dead. `ReferenceFrames.frameOf` and `CellWorldMapper.coordOfPose` (which would rename a zoned cell to the
   galactic lattice) have no production caller.
8. **Width-less target.** `arrivalStandoff` → `StandoffRing` → `plusLocalSaturating` throws for a width-less target
   (`GalacticCoord.java:325`) inside the transit tick; ADDR-22 lists only the descent scan as a guarded tick caller.
   Whether any production target arrives width-less is not established.
