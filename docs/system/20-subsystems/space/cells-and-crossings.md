---
id: space/cells-and-crossings
parent: space/00-overview.md
covers: MECH-SPC-01..08, 11..14, 20, 22 · INV-SPC-01, 02, 04, 06, 10, 15, 16, 17
---

# space — cells and the crossings between them

Where a ship *is*: the address and its lattice, the frames a position is measured through, the slot
pool that makes a few cells live worlds at once, and the crossings that move a ship between a planet,
a cell and the next cell — plus the sky those cells are fed. Boundary, lifecycle and config are in the
[overview](00-overview.md); hyperspace, the ledger and the crew are in
[hyperspace-and-persistence](hyperspace-and-persistence.md).

## Mechanics

### MECH-SPC-01 — A cell address is zone + sector triple + local offset + lattice width
`GalacticCoord` holds `long` sectors, a canonical `int` local in `[-half, half)` of *its own* lattice, a
`zone` (the parent cell's key, `null` = the galactic lattice) and `cellBlocks`, the lattice width
(`GalacticCoord.java:53-56,85,103`) [V]. One place decides the carry (`GalacticCoord.java:157-168`) [V]. The key is
`zone.sx_sy_sz`, dot-separated (`GalacticCoord.java:402-405,415`) [V] and **carries no width**: a zoned
coordinate rebuilt by `fromCellKey` is `WIDTH_UNKNOWN`, and every operation that needs a width — `plusLocal`,
`plusLocalSaturating`, `staticFrameDistanceSqTo` (which also refuses two different lattices) — throws rather
than assume the galactic one (`GalacticCoord.java:235-242,299-305,379-390,429-446`) [T]
`GalacticCoordZoneTest.java:140,187`. A caller on a tick path asks `knowsItsLattice()` first
(`GalacticCoord.java:189`; used at `TileAdvancedFlightComputer.java:591-604`) [V]. Equality and `sameCell`
ignore the width, so a key round-trip equals the original (`GalacticCoord.java:268,494-505`) [T]
`GalacticCoordZoneTest.java:61`. NBT is a `galacticCoord` sub-tag `{sx,sy,sz,lx,ly,lz}` plus `zone`,`cw` only
when zoned; an *absent* sub-tag reads as `ORIGIN` (`GalacticCoord.java:456-491`) [V][T]
`GalacticCoordTest.java:201`. Contract: [C15](../../30-contracts/C15-cell-address-and-frame.md) ADDR-19/21/22.

### MECH-SPC-02 — A position exists only at a tick; distance goes through both frames
`AbsolutePos` is a sector triple + `long` local, so a sector index near the `long` ceiling stays expressible
(`AbsolutePos.java:48-69`) [V]. `minus` saturates at the `long` bound and the `BlockDelta` it returns *says*
it saturated; `distanceTo` is computed from the sector delta and never clamps (`AbsolutePos.java:140-208`)
[V][T] `CellFramesTest.java:128`. `CellFrames.originAt(name, tick)` is the frame seam: `STATIC` is `sector·CELL`
(a void cell); production asks the universe registry at the space clock and falls back to static without one
(`CellFrames.java:26-55`; `SpaceSubsystem.java:708-713`) [V]. A zoned name's static position resolves the zone
first and adds the triple at the zone width (`AbsolutePos.java:84-108`) [V]. A body in a moving cell is fed
from where it *is*, not where its name says (`SystemBodiesProducerTest.java:335`) [T]. Contract: C15 ADDR-9/13/14.

### MECH-SPC-03 — Pose realization: cell-centred on all three axes
World position = local offset on X, Y and Z; the cell centre is the world origin
(`CellWorldMapper.java:45-50`) [V]. `coordOfPoseWithin` saturates a pose past a face, so a report never names a
cell nobody materialized (`CellWorldMapper.java:85-97`) [V][T] `CellWorldMapperTest.java:114`. Arrival *blocks*
are staged beyond the cell face and below the physics shipyard: `ARRIVAL_STAGING_X = HALF_CELL + CARRY_MARGIN`
(`VSShipCrosser.java:64-65`) [V][T] `CellWorldMapperTest.java:137`. The pose band `[floor, ceiling]` is declared
to the substrate on every server-world load (`SpaceSubsystem.java:216-232`; `SpaceSubsystemEvents.java:55-61`)
[V], and `MixinEntityCellVoid` stops the below-world kill inside slot worlds, hyperspace included (same
provider) (`MixinEntityCellVoid.java:55-61`) [V].

### MECH-SPC-04 — A zone's lattice is sized to the zone, once, by the naming pass
`ZoneScale`: span = `2·min(extent, HALF_CELL)`; count = smallest power of two ≥ `span / (2·innermost child
offset)`, `1` when there is no child; cell = `ceil(2r / count)`; a massless body has *no* lattice and answers `0`
(`ZoneScale.java:89-133,156-176`) [V][T] `ZoneScaleTest.java:135,173`. Floor-division indexing keeps the central
cell as wide as its neighbours, and `addressOnLattice` keeps the in-cell remainder instead of snapping to a cell
centre (`ZoneScale.java:186-241`) [V][T] `ZoneScaleTest.java:337,386`. A crossing **reads** the width the
naming pass recorded (`UniverseRegistry.zoneLatticeBlocks`) and sizes a lattice itself only for a childless
zone (`SpaceSubsystem.java:611-618`) [V]. The EXTENT (uncapped SOI) is what a craft is carried out of; the
realized (capped) radius only sizes cells. Contract: C15 ADDR-17..19.

### MECH-SPC-05 — Membership is decided by spheres; the cube is the fallback; both have hysteresis
`SpaceSubsystem.zoneMembershipIn` asks the *inward* question first against each child zone's extent sphere
(innermost containing sphere governs), then — for a zoned cell only — the *outward* question against its own
sphere; a galactic cell is asked inward only (`SpaceSubsystem.java:460-536`) [V][T]
`VSShipZoneSphereCrossingTest.java:196,272,357`. The answer keeps the in-cell remainder measured from the origin
the target cell rides (`SpaceSubsystem.java:555-564`) [V]. A sphere is Laplace `r = a·(m/M)^0.4` with `a` the
instantaneous orbital distance, `0` when there is no mass or the primary is not heavier
(`ReferenceFrames.java:49,68-110`) [V]; the primary is the parent zone's body, else the star
(`SpaceSubsystem.java:581-591`) [V]. Thresholds are **fractions**: leave at `r > R·(1+10⁻⁴)`, enter at
`r < R·(1−10⁻³)`; the cube carry arms `HALF_CELL/10 000` past a face and lands `HALF_CELL/1 000` inside the
neighbour, moving only the axes that crossed (`CellSeam.java:37-44,66-77,128-144,168-215`) [V][T]
`CellSeamTest.java:61,97,170,207`. Where sphere and cube both fire the sphere aims the carry
(`CellCrossingController.java:189-216`) [V][T] `CellSeamTest.java:239`. Contract: C15 ADDR-10, C19 FRAME-2/3.

### MECH-SPC-06 — The slot pool: registered once per server, bound per cell, folder follows the binding
The slot `DimensionType` is registered once in pre-init (a second call throws); the pool's dimension ids are
minted per server from a scan that asks both Forge and Stellurgy's own registry
(`SpaceSlotPool.java:35-45,103-110,166-202`) [V]. `SlotBindings` (per subsystem) maps dim → `{store name,
optional GalacticCoord}`; a scratch binding names a store and *no cell* (`SlotBindings.java:40-79`) [V].
`WorldProviderSpaceSlot.getSaveFolder`: bound → `advRocketry/spacepool/cell_<key>`, hyperspace →
`advRocketry/hyperspace`, otherwise `slot<dim>` (`WorldProviderSpaceSlot.java:37-52`) [V]. `load` = bind +
`initDimension` + keep-loaded; `unload` = synchronous flush + remove; `discard` = remove + delete the folder
(`SpaceSlotPool.java:229-299`) [V][T] `SpaceSlotPoolRebindTest.java:22`. "Stored" means a non-empty region file,
**not** a folder (a load `mkdirs` one) (`SpaceSlotPool.java:341-381`) [V]. The slot-dim list reaches a client
before anything can move a player into a slot (`SpaceSlotPool.java:205-223`; `SpaceSubsystemEvents.java:30-44`;
`SpaceEventHandler.java:133-139`) [V].

### MECH-SPC-07 — Cells are refcounted and evicted lazily, never under a player
`materialize(coord)` binds a free slot or LRU-evicts an idle one, adds an occupant, and re-inits a slot whose
world Forge dropped (`SpaceManager.java:160-189`) [V][T] `SpaceManagerTest.java:162,177`. `dematerialize` leaves
the cell loaded at zero (`SpaceManager.java:207-219`) [T] `SpaceManagerTest.java:208`. `canMaterialize` forecasts
`materialize` without binding, evicting or counting — the jump gate's "destination full" advisory reads it — and is a
snapshot, not a reservation (`SpaceManager.java:198-201`) [T] `SpaceManagerTest.java:141`. A victim needs refcount 0
**and** no player in the world (`SpaceManager.java:362-379`; `PoolSlotBinder.java:58-62`) [T]
`SpaceManagerTest.java:253,275`; no victim ⇒ `PoolExhaustedException` (`SpaceManager.java:322-326`) [T]
`SpaceManagerTest.java:297,317`. Eviction: dirty → flush, already stored → unload, else discard
(`SpaceManager.java:382-395`) [T] `SpaceManagerTest.java:380,395,409`; a forced eviction first notifies the
pool-pressure listener (`SpaceManager.java:330`) [T] `SpaceManagerTest.java:335,351`, which logs one WARN and
requests an extra GC (`SpaceSubsystem.java:99-104`) [V]. `slotDimOf` is the **only** cell→dimension answer and
`loadedCells()` hands out coordinates, not keys (`SpaceManager.java:284-313`) [V].

### MECH-SPC-08 — Cell-store GC: periodic + pressure, derived protections, one try per victim
`tickGc` fires every 600 server ticks or on a pressure request (`SpaceSubsystem.java:43,150-161`;
`SpaceSubsystemEvents.java:99-101`) [V]. A candidate is stored, **not claimed** (the ledger holds a SETTLED
ship in it: `SpaceSubsystem.java:108`, `ShipLedger.java:139-149`) and not loaded (`SpaceManager.java:489-491`)
[V][T] `SpaceManagerTest.java:567,544`. Policy `AGE|COUNT|BOTH|NEVER`; a stored cell first seen this session is
stamped *now*, never tick 0 (`SpaceManager.java:401-451,474-487`) [T] `SpaceManagerTest.java:500,529,468`; each
victim gets one attempt, so a stuck delete cannot hang the main thread (`SpaceManager.java:432-448`) [T]
`SpaceManagerTest.java:444`; `SpaceCellEvent.CollectPre` can veto (`SpaceManager.java:461-467`) [V]. Last-visit
ticks persist with the ledger (`SpaceManager.java:247-263`; `ShipLedgerData.java:55`) [V]. A player block edit in
a slot world marks its cell dirty so it flushes instead of being discarded (`SpaceEventHandler.java:471-485`)
[V]. Config: see Config surface.

### MECH-SPC-11 — The shared crossing: pin, cut, then settle in a fixed order
`ShipCrossingService.begin` pins the destination (load, then hold) and calls `ops.cross`; failure returns `null` and
the caller undoes its bookkeeping (`ShipCrossingService.java:222-236`; `VSShipCrossingOps.java:118-159`) [V].
`tick` advances each pending crossing one stage per tick — **pose teleport, then crew re-seat, then unpark** — and
calls `Completion.settled`; after 200 attempts it logs and calls `abandoned` (`ShipCrossingService.java:39,246-297`)
[V]. The pose stage waits for the paste anchor to become air ("the physics mod claimed it",
`VSShipCrossingOps.java:263-288`) and carries seat dummies by the pose delta; standing crew are *not* carried here
(`VSShipCrossingOps.java:301-357`) [V]. `cross` refuses when the durable name resolves to no registered ship
(`VSShipCrossingOps.java:131-140`) [V]. Re-seat places the crew **and** releases stowed bodies, done only when both
are (`VSShipCrossingOps.java:162-194`) [V]. Contract: [C18](../../30-contracts/C18-living-through-a-jump.md) JUMP-3/11/13.

### MECH-SPC-12 — Entry (ascent): an orbit line crossed from a planet-side world
`shouldTriggerEntry` = not a slot world and pose Y above the line; the line is `min(orbit height, physics clamp −
16)` (`ShipEntryController.java:202-229`) [V][T] `ShipEntryControllerTest.java:365,380`, driven from the flight
computer (`TileAdvancedFlightComputer.java:675-691`) [V]. `requestEntry` returns one `Decision`
(`ShipEntryController.java:124-141`); a full pool **refuses, reads the crew without dismounting, messages them and
arms a 100-tick cooldown** (`ShipEntryController.java:238-277`) [V][T] `ShipEntryControllerTest.java:212`; a failed
cut hands the cell back and re-seats the captured crew (`ShipEntryController.java:323-333`) [T]
`ShipEntryControllerTest.java:244`. The entry coordinate is a ring around where the launch body **is** at this tick,
named in the body's OWN cell — `aimPoint` answers `addressAt(tick)`, the body's named cell at the offset it stands
at inside that cell's frame, never its absolute position re-read on the static grid, which names a cell no zone
body stands in once the body has drifted past its static face (`ShipEntryController.java:386-402`) [V][T]
(INV-SPC-19) — matched on the launch dimension, radius `max(2·512, 2·shell)`, saturating
inside the cell (`ShipEntryController.java:60-70,344-415`; `StandoffRing.java:38-43,90-100`) [V][T]
`ShipEntryControllerTest.java:172`. On settle the ledger takes the entry coordinate
(`ShipEntryController.java:290`). After a descent the entry latch holds until the ship has been below the line
(`DescentController.java:230`) [T] `DescentControllerTest.java:358`. Contract: C15 ADDR-16.

### MECH-SPC-13 — Descent: the nearest body whose own atmosphere shell has been crossed
Each flight-computer tick a SETTLED ship's ledger coordinate becomes an absolute position at the space clock;
`nearestDescentTarget` picks the nearest `canDescend()` body whose `DescentShell.radiusAround` contains it
(`DescentController.java:144-167`) [V][T] `DescentControllerTest.java:486,539,581`. Shell = `R·(1 + 100/6371)` chart
blocks, floored at 512 (`DescentShell.java:48-64`) [V][T] `DescentShellTest.java:33,184`; the in-world takeoff line is
the same surface seen from inside, never inside the block band (`DescentShell.java:84-94`) [T]
`DescentShellTest.java:149,172`. `requestDescent` pins the destination dimension, resolves the landing (blocks
pasted just under `MAX_BUILD_Y`, pose far above), latches entry, captures crew, cuts — and **only then** marks the
source dirty, dematerializes it and `ledger.remove`s the ship (`DescentController.java:175-293`) [V][T]
`DescentControllerTest.java:190,262,282,312`. Arrival altitude is `max(entry line − 100, 255 + 100)`
(`VSDescentPasteResolver.java:54,71-76`) [T] `DescentControllerTest.java:401,431`; the resolver measures the hull *by
durable name or refuses* (`VSDescentPasteResolver.java:107-116`) [V]. A procedural body gets its dimension minted by
the flight computer at descent time (`TileAdvancedFlightComputer.java:629-635`) [V].

### MECH-SPC-14 — Cell-seam carry and direct jump: one crossing, destination acquired first
`requestCarry` runs only for a SETTLED ship and only after the cooldown; the destination is `carryDestination`
(MECH-SPC-05) (`CellCrossingController.java:158-216`) [V]. `cross`: **materialize the destination before anything
is cut**, capture crew, announce, cut, then release/dirty the source and `ledger.settle` at the destination —
deliberately *not* `IN_TRANSIT` (login reads that as "parked in hyperspace") (`CellCrossingController.java:255-352`)
[V]. A pool-refused crossing only *reads* the crew and messages them; a failed cut hands the destination back
and re-seats the captured crew; for a carry (not a jump) either arms a 100-tick cooldown
(`CellCrossingController.java:261-273,319-331`) [V]. `requestDirectJump` has no cooldown and reads the
pose itself (`CellCrossingController.java:231-246`) [V]. The flight computer asks for the carry **before**
reporting position (`TileAdvancedFlightComputer.java:520-530`) [V][T] `VSShipCellSeamTest.java:161,262,470`.

### MECH-SPC-20 — The sky feed is keyed by cells, observed from a ship in the cell
`SystemBodiesProducer.buildByDim` keys every **loaded cell** (never a ship's state) to its slot dim; observer = a
ledger ship's coordinate in that cell (SETTLED preferred) else the cell centre; each body = `absoluteAt(tick) −
observer` (direction **and** true distance) plus `descendTarget = kind.canDescend()`, `shell` (0 unless
descendable), `radiusBlocks` and the star's proxy render dimension (`SystemBodiesProducer.java:74-199`) [V][T]
`SystemBodiesProducerTest.java:132,178,195,215,335`. A live cell with no body is keyed *present and empty*; each
player is sent only the dimension he is in; the feed is rebroadcast every 20 ticks and sent at login
(`SystemBodiesProducer.java:53,232-290`; `SpaceSubsystemEvents.java:43`) [V]. `SkyNebulaeProducer`: clouds within
1000 ly, drop those under 0.01 rad, at most 12 largest first, a 64-entry derived cache
(`SkyNebulaeProducer.java:43-111,171-214`) [T] `SkyNebulaeProducerTest.java:108,130`. Contract:
[C14](../../30-contracts/C14-cell-sky-and-bodies.md) CON-C14-06..11, 14..16.

### MECH-SPC-22 — Takeoff corridor planner and the build-band constant (peripheral)
`AutoTakeoffPlanner` is a pure diagonal-climb direction/length and a sampled corridor check — declined, not errored,
when blocked (`AutoTakeoffPlanner.java:18-95`) [T] `AutoTakeoffPlannerTest.java:56,64`; used at
`TileAdvancedFlightComputer.java:1031-1052`. `TerrainHeightFinder.MAX_BUILD_Y` is live; `pasteY` and
`terrainTopOfFootprint` have no production caller (`TerrainHeightFinder.java:36,56,76`; grep of `src/main`) [V].

## Invariants

- **INV-SPC-01** [V] A coordinate whose lattice width is unknown never does width arithmetic — it refuses
  (`GalacticCoord.java:235-242`; `GalacticCoordZoneTest.java:140`). A seam that hands on a cell hands on the
  coordinate, never its key (C15 ADDR-21).
- **INV-SPC-02** [V] A crossing acquires the destination cell **before** the source is released, and a failed cut
  hands the destination back (`CellCrossingController.java:260,324,338`; `ShipTransitManagerTest.java:275,424`).
- **INV-SPC-04 [V]**  A cell with somebody standing in it is never evicted; a pool with no victim refuses
  (`SpaceManager.java:362-379`; `SpaceManagerTest.java:253,297`).
- **INV-SPC-06** [V] The cube/sphere hysteresis is a relation between *fractions* (`REENTRY_DEPTH > CARRY_MARGIN`,
  10⁻⁴ vs 10⁻³), so nothing rests on ship speed (`CellSeam.java:37-44,66-77`; `CellSeamTest.java:97,170`).
- **INV-SPC-10** [V] A jump and a crossing act on the ship they **name**, or refuse (`VSShipCrosser.java:196-235`;
  `VSShipCrossingOps.java:131-140`; `ShipTransitManagerTest.java:543`).
- **INV-SPC-15** [V][T] The entry ring is strictly outside the body's descent shell, so an entering ship never spawns
  inside the descent trigger (`ShipEntryController.java:60-70`; `ShipEntryControllerTest.java:183`).
- **INV-SPC-16** [V] Settle order is pose → re-seat → unpark; a rider is never mounted onto a mount about to move
  (`ShipCrossingService.java:259-280`).
- **INV-SPC-17** [V] "Stored" is derived from region data on disk, never remembered in memory — an in-memory flag is
  empty after a restart and would let eviction delete built-in cells (`SpaceSlotPool.java:341-352`;
  `SpaceManager.java:97-100`).
- **INV-SPC-19** [T][BEH] An entering ship is aimed into its launch body's OWN named cell, at the point that, read
  through the frame that cell rides, is where the body stands at that tick, however far its orbit has carried the
  cell from the static cube its name occupies; the body is the one whose dimension the ship launched from
  (`ShipEntryController.java:386-402`; MECH-SPC-12). Pinned by
  `ShipEntryControllerTest#anEntryIsAimedIntoTheLaunchBodysOwnCellWhereverItsOrbitHasCarriedIt`.
- **INV-SPC-20** [T][BEH] In the sky feed every moon points at the body it orbits: a moon's parent is the body whose
  cell its ZONE names, never a body sharing the moon's own cell (`SystemBodiesProducer.java:296-324`; MECH-SPC-20).
  Pinned by `MoonParentInTheSkyFeedTest#everyMoonInTheFeedPointsAtTheBodyItOrbits`.

## Failure modes & edge cases

- **Pool exhaustion** is a normal outcome and each *caller* answers it differently: entry and carry refuse and keep
  flying (cooldown 100), login restore degrades to spawn (`SpaceEventHandler.java:544-550`), and a transit arrival
  waits in its lane (hyperspace-and-persistence, MECH-SPC-15). A short jump whose target has no slot is not
  handed to the direct crossing at all — it is flown through hyperspace and waits there (`jumpsDirect`), so the
  direct crossing's own refusal after the burst is no longer reached from a jump.
- **A width-less ledger row**: the descent scan refuses once and logs, the pilot keeps control
  (`TileAdvancedFlightComputer.java:591-604`).

## Test coverage

`INV-SPC-01` GalacticCoordZoneTest.java:140 · `-02` ShipTransitManagerTest.java:275,424 · `-04`
SpaceManagerTest.java:253,297 · `-06` CellSeamTest.java:97,170 · `-10` ShipTransitManagerTest.java:543 · `-15`
ShipEntryControllerTest.java:172 · `-16`, `-17` are `[V]` only · `-19`, `-20` name their methods.

## Open questions

Leads by reading, not reproduced.

3. **`parentIndex` has no reader** outside the packet class (grep of `src`); a moon's parent is the body whose cell its
   ZONE names (`SystemBodiesProducer.java:296-324`).
