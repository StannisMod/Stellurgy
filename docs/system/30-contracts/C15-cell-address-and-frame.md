---
id: C15
covers: what a cell's address MEANS — a durable name vs a position that moves with its body
confidence: implemented (the tag on each clause says how far; ADDR-5 is retired in place, its number permanent)
owner-subsystem: none — universe addressing; code in universe/ + space/GalacticCoord
see-also: [C13 (space presence — cell is ITS term), C14 (the cell sky renders these frames), universe-model §2 + INV-UNI-01, space-model §2/§3]
---

# C15 — A cell's address and its frame, clauses ADDR-1..ADDR-25

Maintainer model, ratified 2026-08-01:

> **A cell is the neighbourhood OF a body and it rides with that body.** Its name is eternal; where
> it *is* stays a function of time.

**The failure this prevents.** `GalacticCoord` is `absolute = sector·CELL + local`
(`GalacticCoord.java` `[V]`), so a body's *name* and its *place* are one number; if the name is
derived from the live orbital angle on every query (`positionFor(this.orbitTheta)`, rewritten every
tick by `updateOrbit():1147-1150` `[V]`) a body changes cell with the clock. Measured over the
shipped layout (7 systems / 38 non-moon bodies, 2 000 samples across 20 M ticks): **median 237
distinct names per body**, worst `dim3` at **514**. No body would hold a name for even one orbit.

## Terms — Layer 1 (universe)

C13's Layer-1 terms (**cell**, **slot dimension**, **presence**) keep their meanings. This doc
splits **cell** in three; a clause that conflates the three is a bug in the clause.

- **cell name** — the sector triple, `GalacticCoord.cellKey()` `[V]`. An identifier,
  not a place.
- **primary** — the single REAL body whose durable name is that cell, of any kind (star, planet,
  gas giant, belt). A cell with no primary is a **void cell**.
- **frame** — the coordinate system a cell's contents live in; its **origin** has an absolute
  position which MAY be a function of world time.
- **in-cell position** — the `local` triple, canonically `[-HALF_CELL, HALF_CELL)` `[V]`.
  What a slot world realizes as a pose, and it already carries no absolute component
  (`CellWorldMapper.poseWorldOf` drops the sector entirely) `[V]`.
- **absolute position** — `frameOriginAt(name, t) + local`. Defined only AT A STATED TICK.
- **ephemeris** — the body's orbital law, `DimensionProperties.orbitThetaAt` `[V]`.

## Clauses — the name

- **ADDR-1 (a name, not a place)** `[T]` The cell name of a body **with a durable identity** (a
  dimension id; a star id for the star proxy; its own stored name for a POI) is fixed for the life
  of the save. No tick changes it. **Player form:** a coordinate the player wrote down still names
  what he wrote it down for.
- **ADDR-2 (derived from the layout, never from the clock)** `[T]` A cell name is derived by
  evaluating the ephemeris at the reference angle `θ_time = 0` — `positionFor(orbitThetaAt(0))` —
  from time-INVARIANT inputs only: the system anchor, `orbitalDist`, `baseOrbitTheta`,
  `orbitalPhi`, `isRetrograde` (`DimensionProperties.java`) `[V]`. Note the sign
  multiplies the SUM (`orbitThetaAt` `[V]`), so a retrograde body's name is
  `positionFor(−baseOrbitTheta)`. `orbitTheta` and world time may not reach a name.
  **The first derivation is PERSISTED and thereafter authoritative**: a name that
  is only ever re-derived is hostage to the precision of its inputs and to every change of
  the layout maths (`baseOrbitTheta` is persisted as integer degrees — `XMLPlanetLoader.java`
  (read and write) `[V]` — and `orbitalPhi` truncates on the first save; one degree is `d/57.3`
  orbit-units against a 4-unit cell, so `orbitalDist ≥ 229` flips a cell on a save round-trip).
  The XML carries fractional angles (authority for a NEW world) and the derived name is persisted
  in `UniverseRegistry` on first derivation (authority for an EXISTING one), as star anchors are.
- **ADDR-3 (names are CONTAINED, and a sector delta is not a distance)** `[T]` Every body's name
  lies within `minSpacing/2 − BOX_MARGIN_CELLS` sectors of its system's anchor on every axis, and
  no two systems' neighbourhoods overlap. That containment is what member→anchor attribution reads
  (`UniverseRegistry.withinNeighbourhood`, `SystemContent.clampIntoBox`) `[V]`, and
  attribution may order candidate anchors by sector delta `[V]`. Nothing else may read a
  sector delta as a physical distance (ADDR-9).
- **ADDR-4 (one real body per name)** `[V]` INV-UNI-01 over names: at most one REAL body per cell
  name. A moon has its own cell, and a zone-qualified key can never equal a galactic one, so the
  audit compares a moon only against its siblings in the same zone. Station slots stay exempt (no
  mass, no zone: ADDR-17). Under ADDR-1/2 this is a STATIC property, decidable at load — though
  nothing evaluates it at load (the audit runs inside `bodiesOf` on every query,
  `SystemContent.auditOneRealBodyPerCell`) `[V]`. Violated structurally in one place:
  `HALF_CELL/ORBIT_UNIT = 2`, so any orbit radius < 2 is permanently inside its star's cell (one
  permanent collision in the shipped save); POIs are never audited.
- **ADDR-5** — retired. (Superseded by ADDR-17: a moon has a name of its own.)

## Clauses — the frame

- **ADDR-6 (every cell of a zone rides its body)** `[T]` Every cell of a zone rides the zone's body,
  because a zone is a SET of cells (ADDR-18) and a body's influence can span many.
  **Ruling, maintainer 2026-08-01: every cell
  moves except a void one.** `frameOriginAt(name, t)` is the primary's position at `t`, whatever
  produced that primary — authored, **procedural** (a generated asteroid moves too) or **pinned**.
  A primary whose position is constant in time — a star today — has the constant origin
  `sector·CELL`; that is a degenerate frame, not an exemption. The primary is at its own frame's
  origin by construction: its in-cell position is `(0,0,0)`, already true of every non-moon address
  (`SystemContent`) `[V]`.
  **Discharged by carrying a LAW on the body rather than a position.** A `SystemBody` holds a
  `CellFrame` (a static base plus a `BodyEphemeris`), and both round-trip through NBT as ELEMENTS, so
  **a pin freezes the elements and never the positions** and a pinned system keeps moving after
  `addPoi`'s pin-on-touch has snapshotted it
  `[T] SystemBodyTest.aBodyStillMovesAfterAnNbtRoundTrip`. **The gap that remains:** a PROCEDURAL body
  carries no orbital elements (`ClusteredGalaxyGenerator` derives from `hash(seed, cell)` alone), so
  it is built with a static frame. That is correct under ADDR-7's degenerate reading and it is a
  perfectly addressable body, but a generated asteroid does not yet move, which the ruling says it
  should. Authoring generator orbits is the one piece of ADDR-6 still owed.
- **ADDR-7 (a void cell's frame is static)** `[T]` A cell is VOID iff it holds no entity whose
  position is computable in time — i.e. it has no primary. Then
  `frameOriginAt(name, t) = sector·CELL`, at every tick. (Satisfied by construction — that formula
  *is* `GalacticCoord.absoluteX/Y/Z` `[V]` — so its witness is only meaningful as ADDR-6's control.)
- **ADDR-8 (a cell name never changes as a result of motion)** `[T]` Membership is decided by name.
  A ship's own flight, and its frame's motion, may not re-derive its name; it keeps its in-cell
  position and is carried by the frame. This is free, not a per-tick write: the slot world's frame
  IS the cell frame.
- **ADDR-9 (distance exists only at a tick)** `[T]` Within one cell, distance is the local delta,
  evaluated at a stated tick whenever either endpoint's in-cell position is itself live (a POI or a
  station standing in a cell it is not the primary of — a moon IS its cell's primary and sits at its
  origin); a tick is redundant only between two settled objects.
  ACROSS cells, distance must be evaluated through both frame origins at a stated tick.
  `GalacticCoord.staticFrameDistanceSqTo` `[V]` is a valid cross-cell distance ONLY between two
  static frames, and it **REFUSES** two names in different lattices outright: a
  sector index counts ITS lattice's cells, and the two in play differ by four orders of magnitude,
  so any number it could return would describe nothing (`GalacticCoordZoneTest`).
- **ADDR-10 (a cell is a neighbourhood with faces, and flight CARRIES a ship through one)** `[T]` A
  cell's contents stay within ±`HALF_CELL` of its frame origin; a ship that flies past that bound is
  **carried into the neighbour it left through** (`CellSeamController.requestCarry`, arithmetic in
  `CellSeam`), not stopped and not left named in a cell it is no longer in
  (maintainer: *"carry the ship across honestly, with hysteresis"*). A ship saturated at the face
  would be in one place and named in another, so it could not descend, its jumps would be refused,
  and the cell it was really in would lose the ledger's GC protection. Saturation survives as the
  REFUSAL path only (no free pool slot).
  Two margins, both **fractions of the cell** so they move with it: the carry arms at
  `HALF_CELL/10 000` past the face and places the ship `HALF_CELL/1 000` inside the neighbour's
  opposite face, so a return costs the sum and a ship loitering on a face cannot ping-pong.
  *No invariant rests on ship speed: `REENTRY_DEPTH > CARRY_MARGIN`, which is the whole of the
  hysteresis, is a relation between two cell fractions. The flight-time figures in `CellSeam`'s
  javadoc are a sanity check on the ratified ratio, not a dependency of it.*

  **The SPHERE form** `[V]`. Inside a ZONE the boundary is not a cube face:
  a body's influence ends at a radius, so `CellSeam.hasLeftZone` fires at
  `r > R_zone * (1 + 1/10 000)` and `hasEnteredZone` at `r < R * (1 - 1/1 000)` — the SAME two
  ratified fractions, of the sphere's radius instead of the half-cell. The galactic lattice keeps the
  cube for LEAVING, because a galactic cell has no sphere and its extent IS the cube. **It is asked
  the INWARD question all the same**: a galactic cell a body stands in is that body's zone seen from
  the galactic lattice, so a craft there that is inside one of the body's children's spheres is
  re-addressed into that child's zone (`SpaceSubsystem.zoneMembershipIn`, the `galactic` branch).
  `r` is measured from the BODY (`sector * cellBlocks + local`), never from the cell a craft happens
  to sit in — a sphere is centred on a body and never on a lattice.

  **The sphere is INSCRIBED in the cell** (ADDR-19 sizes the cell to contain it), so where both
  apply the sphere fires first and a craft is never carried by the cube out of a zone it had not
  already left. An EMPTY cell of a zone rides the zone's body too (`originAt`): every cell of a zone
  rides the body, occupied or not, so a craft that flies out of a moon's cell is handed another frame.

## Clauses — zones

- **ADDR-17 (a body with mass has a ZONE, and its name is its cell in its parent's zone)** `[A]`
  A body that has mass and orbits a heavier primary defines a zone; its
  NAME is the cell it occupies in its parent's zone, derived once at `NAME_TICK` (ADDR-2) and frozen.
  The recursion bottoms out at the galactic lattice, which names the system anchor. **No separate
  identifier is minted**: a body is named by where it sits, at every level, which is what ADDR-2
  already says applied once. A body with NO mass — a POI, a station slot, a belt — defines no zone and
  rides the innermost zone containing it (maintainer 2026-08-25: *"POI, пояса и станции едут в своей
  SOI — это разумно, если подумать"*).
  *Why a moon has a name of its own* (maintainer, asked whether a moon becomes a destination in its
  own right: *"Да."*): a moon sharing its parent's name is ADDRESSLESS, and a craft parked beside one
  would be carried by the PARENT and left behind by the moon — measured over 20 000 ticks as
  **7 066 blocks becoming 294 996**, against a descent shell of 7 066
  (`ParkedCraftKeepsStationTest`). Carrying such a craft is not an option: the game's Luna moves
  14.75 blocks/tick and the physics substrate freezes a ship above 223.6 blocks/s
  (`PhysicsCalculations.isPhysicsBroken`, `lengthSquared > 50000`), i.e. 11.18 blocks/tick. The
  speed is not a modelling error — the period is right (27.29 game days against a real 27.32); a game
  day is 24 000 ticks, so time runs 72× and every honest period is 72× faster in blocks per tick than
  physical intuition suggests.
- **ADDR-18 (a zone is a SET of cells, all of which ride the body)** `[A]` A zone's EXTENT is the
  body's sphere of influence (C19 FRAME-3), which is live at a tick; the cell is the granularity of
  REALIZATION, not the extent. Every cell of the zone rides the body (ADDR-6). **Measured**, at
  `CELL = 32·10⁶` chart blocks: Luna 0.02 cells across, Earth 0.23, Jupiter 12.05 (~916 cells),
  Neptune 21.65 (~5 313). Only occupied cells are ever materialized, so the slot pool is unaffected —
  what the count constrains is that the naming scheme must GENERATE cells, never enumerate them.
  Capping the zone at one cell (`min(SOI, HALF_CELL)`) is ruled out: it makes station-keeping end at
  a distance that is a property of the CELL — a technical constant — rather than of the body (a flat
  512 would be 1/50 of an Earth and 1/548 of a Jupiter).
  `ZoneScale.extentRadiusBlocks` (the uncapped SOI; HALF_CELL for a body with no primary; 0 for a
  massless body) is the radius a craft is carried OUT of and INTO a zone by
  (`SpaceSubsystem#sphereRadiusOf`, the inward branch of `zoneMembershipIn`);
  `realizedRadiusBlocks` = `min(extent, HALF_CELL)` is the REALIZATION bound for `cellBlocks` /
  `cellsAcrossZone`. Pinned by `VSShipZoneSphereCrossingTest` on a planet with a 2 838 272 986-block
  sphere. `[T]` for the EXIT; the ENTRY half is `[V]` only — no shipped child has a sphere past
  HALF_CELL (largest measured ~253 000 blocks), so no test can tell the two forms apart without an
  authored heavy wide-orbit moon; maintainer 2026-10-06: *"Ладно, а."* (accept the written reason,
  no fixture).

- **ADDR-19 (a zone's cell size is a property of the zone)** `[V]` A single global `CELL` collapses
  the ADDR-17 recursion at the first step: Earth's whole zone is 0.23 of a 32·10⁶-block cell, so its
  local lattice would hold ONE cell and Luna would be named by that same cell. The lattice inside a
  zone is therefore sized to the zone (`ZoneScale.cellBlocks`) `[V]`:

  ```
  span  = 2 · min(r_SOI, HALF_CELL)
  count = the SMALLEST power of two ≥ span / (2 · innermostChildOffset)   -- per ZONE
        = 1 when the zone has no child to name apart
  cellBlocks = ceil(span / count)
  ```

  **Three bounds act on the count, and only one of them decides:**
  - **A, from below — the innermost child must land on an index of its own**, or a moon would share
    its parent's name one level down. `count ≥ span / (2·orbit)`. **This is the one that decides**, and
    the SMALLEST power of two above it is taken: nothing wants the lattice finer, and everything wants
    the cells as large as they can be.
  - **C, from above — a cell must CONTAIN the sphere of the body it names.** Otherwise the cube face
    fires before the sphere ever can and C19 FRAME-2 cannot be decided by geometry however the
    crossing is written. A satisfies C automatically: `r_SOI = a·(m/M)^(2/5) < a` while A makes the
    cell about twice `a`. Worst real case Callisto — 301 383 blocks of sphere in a 1 000 000-block
    cell.
  - **B — a body's descent shell must fit inside its own cell** — is C with a much smaller number (a
    shell is 1.0157 radii, a sphere is thousands), so anything satisfying C satisfies it.

  The orbit must be measured against the span the zone is REALIZED over (`min(r_SOI, HALF_CELL)`),
  not the body's unclamped sphere; for a giant those differ by six to twelve times.

  **A zone with no children gets ONE cell**: for every moon in the game the cell and the sphere become
  the same region, so "which body carries this craft" and "which cell is it in" cannot disagree.
  Rounded table (the `ZoneScale` javadoc): Luna ~528 000 (1 cell); Earth ~7 408 000 span, innermost
  child at 1 537 600, count 4, cell ~1 852 000; Mars 64; Jupiter 32. As computed on the reference
  system: Earth's cell **1 849 294** blocks (span 2 x floor(SOI) / 4), Luna's span 2 x 264 731 =
  529 462.

  **The width is derived ONCE, by the naming pass, and every later caller READS it.** The size
  depends on the zone's innermost child, which only that pass sees in full — so a crossing, which has
  a craft and a body, asks the registry (`UniverseRegistry.zoneLatticeBlocks`, read off the recorded
  names) rather than sizing a second lattice. Two lattices do not conflict when they disagree: a cell
  key carries no width (below), so the second one silently renames the cell (a craft standing exactly
  where Luna stands, addressed on a 7 397 280-block lattice while Luna was named on the 1 849 294-block
  one, would arrive in the cell holding EARTH).

  **The span is the REALIZED region, and that is not a cap on the zone.** ADDR-18 rules out capping
  the EXTENT at one cell, and this does not: the extent stays the live sphere, station-keeping still
  ends at a property of the BODY, and the lattice GENERATES cells beyond the span for anything
  outside the realized region. What `min(…, HALF_CELL)` decides is only the GRANULARITY, which
  ADDR-18 itself separates from the extent — and it has to be decided, because Neptune's sphere is
  21.65 cells and a lattice reference-spanning it would name most of its cells after places no slot
  world can hold.
  **A body with NO PRIMARY** (a rogue) has no Laplace sphere for the first term to exist, so its
  bound is the realized one alone — the same rule with a term absent, not a special case. Reading the
  missing sphere as "no zone" instead would leave every rogue's moons sharing their parent's address.

  Consequences: `HALF_CELL` is a function of the zone (`GalacticCoord.cellBlocks()`, carried on the
  coordinate) `[V]`; `CellWorldMapper` is parameterised by it (NOT DONE — it still reads the global
  `CELL`); **the seam margins are unchanged** because they are FRACTIONS (`HALF_CELL/10 000`,
  `HALF_CELL/1 000`) — no invariant rests on ship speed.

  **A cell KEY does not carry the width** and deliberately never will: it is derived at `NAME_TICK`
  and would sit inside the address a player writes down, where a re-derivation reads as a different
  place. A coordinate recovered from a key therefore carries `WIDTH_UNKNOWN` and **REFUSES** every
  operation that would need it (`plusLocal`, `plusLocalSaturating`, `staticFrameDistanceSqTo`,
  `AbsolutePos.ofCellName`) rather than assuming the galactic one — an assumed width does not fail,
  it renames the cell. NBT does carry it (`"zone"` + `"cw"`), and a galactic coordinate writes
  neither tag. `inLattice(long)` re-attaches it from the zone's own body.

- **ADDR-21 (a seam carries the COORDINATE, never its key, wherever arithmetic follows)** `[V]`
  ADDR-19 says a key cannot carry the width. The consequence for every boundary the address crosses:
  **a seam that hands on a cell hands on the `GalacticCoord`.** Handing on `cellKey()` and rebuilding
  with `fromCellKey` at the far side is not a shortcut, it is a silent downgrade to `WIDTH_UNKNOWN` —
  and it cannot be undone downstream, because `ZoneScale.cellBlocks(body, primary, tick)` is a
  function of the zone AT A TICK: re-attaching later attaches a DIFFERENT moment's width, which
  `inLattice`'s own contract calls a way to say something false.

  Carried by `[V]`: `SlotBinder.load(int, GalacticCoord)`; `SpaceSlotPool`'s per-slot binding
  (`cellCoordFor(dim)` answers the cell, `cellKeyFor(dim)` the store folder, derived — the bindings
  themselves live in the running server's `SpaceSubsystem.slotBindings`, so none outlives its
  server); `SpaceManager.loadedCells()` → `Map<GalacticCoord, Integer>`. A slot bound
  to something that is NOT a cell — a probe staging a scratch world — says so by answering `null`
  from `cellCoordFor`, an absence a reader can act on rather than a plausible address it cannot.
  *Measured: a pipeline that carried the key rewrote a ship's ledger row width-less one tick after
  it settled in a moon's cell, and the descent scan multiplied by the missing width on the next —
  `IllegalStateException` inside `World.updateEntities`, i.e. a crash report and a stopped dedicated
  server.*

- **ADDR-22 (an unanswerable coordinate refuses; a caller in a TICK asks first)** `[V]`
  Every operation needing the width refuses a `WIDTH_UNKNOWN` coordinate rather than assuming one
  (ADDR-19) — correct, and it makes the refusal a THROW. Where that throw lands inside a tile
  entity's tick, vanilla turns it into a crash report and stops the server, so the honest refusal of
  one coordinate ends the session for everyone in it.
  **So a caller on a tick path asks `GalacticCoord.knowsItsLattice()` before doing the arithmetic**,
  and says once, in the log, what it did NOT measure — naming the difference between "nothing was
  close enough" and "nothing was measured". The mechanic degrades to unavailable for that craft; the
  server keeps running and the pilot keeps control.
  *Applied at `TileAdvancedFlightComputer`'s descent proximity scan; it is the only tick-path caller
  of a coordinate that can arrive width-less.*

- **ADDR-20 (one distance unit, derived from the metric)** `[V]` One quantity, one unit: **100 km**,
  with `BLOCKS_PER_DISTANCE_UNIT = 100 000 / METRES_PER_CHART_BLOCK` (400 at `D = 250`). The unit is
  stated as a LENGTH and the block count follows from the metric, so changing `D` moves everything
  together. There is no second (moon) unit: two units for one quantity (an orbit unit of 1 495 979 km
  beside a moon unit of 50 km, a factor of 29 920) is why `ReferenceFrames.soiRadiusBlocks` takes a
  live block displacement instead of either field. 100 km is chosen against the FINEST thing that
  depends on it, not the widest: it leaves a descent shell 17.7 units of resolution where 250 km
  leaves 7. The field is a `long` (`DimensionProperties`, `SystemBody`, `StellarBody`, the public
  getters, NBT and the planet file), so the named reach of 5 000 AU and the 2 000 AU companion band
  are representable (`UniverseScale`, `ClusteredGalaxyGenerator`) `[V]`. Every authored value is
  in this unit (Earth 1 495 979, Luna 3 844); a half-converted catalogue would be a field meaning
  different things on different rows.

  **A bound on a physical quantity is written as that quantity**, or a change of unit moves it
  without moving a character of it. `PlanetDerivation.orbitalDistanceOf` clamps at
  `1_000 * DISTANCE_UNITS_PER_AU`-style expressions (10 000 AU), and `PlanetDerivation.referenceDistance`
  at 1 000 AU, not at bare numbers: a bare `1_000_000` would be 0.67 AU in this unit and silently cap
  every procedural orbit in the galaxy. An AU is an ODD number of 100 km units, so half an AU and one
  and a half AU are not representable in the field; test pins sample whole AU instead of widening a
  tolerance until the law is no longer pinned.

**The metric boundary is unchanged by any of this.** A zone is CHART throughout; nothing above alters
where a length changes metric, which is the descent shell and only the descent shell
([metric-boundary](../metric-boundary.md) MECH-MET-01). An asteroid worked by a player is not zone
content at a finer scale — it is a WORLD entered through that shell (INV-MET-03), and a body with no
mass has no zone to be the owner of one anyway (ADDR-17).

## Clauses — what persists, what stays live

- **ADDR-11 (a stored address keeps its meaning)** `[V]` Because names are layout-derived (ADDR-2),
  every persisted `GalacticCoord` — ship ledger rows (`ShipLedgerData.java`), the nav target
  (`TileNavigationComputer.java`), a navigation crystal (`CrystalEntry.java`), a POI, a
  `cell_<key>` store folder (`SpaceSlotPool.java`) `[V]` — denotes the same primary at every
  later tick. Pre-0.1.0 saves are not read, so no migration exists; a ship settled where a body no
  longer is stays where it is, in void.
- **ADDR-12 (no coordinate whose meaning depends on when it was written)** `[T]` Every stored
  coordinate is a cell name plus an in-cell offset. **One exception, stated so it is not smuggled
  in elsewhere:** a mid-transit position, which is stored as (origin name, target name, progress),
  never as a raw absolute. (`navTarget` persisting a FUTURE absolute, `TransitRecord` persisting a raw
  mid-flight `position` and `CrystalEntry.coord` persisting an observation-tick absolute are the
  places to check: `TileNavigationComputer.java`, `TransitRecord.java`,
  `CrystalEntry.java`.)
- **ADDR-13 (the geometry the player feels stays live)** `[T]` The distance — hence the cost and
  duration — between two bodies **whose frames both move** changes with time. A body's distance from
  its own system anchor does NOT: `positionFor` is `(d·cosθ, d·sinφ, d·sinθ)` `[V]`,
  whose norm `d·√(1+sin²φ)` is θ-free, so an orbit is a circle about the anchor and **the star is
  never one endpoint of this observable.** A body seen from a cell the observer's frame does not
  carry visibly recedes. The third ratified observable — moons and the star moving on the sky — is
  C14's (CON-C14-14/15/16). Cross-cell distance measured over the static grid is the failure mode:
  check `ShipNavigation.java`, `TargetPrediction.java`, `ShipTransitManager.java`.
- **ADDR-14 (an aim resolves to a name)** `[T]` Aiming at a body resolves to that body's durable
  name, so the arrival CELL equals the aimed cell at every tick and needs no projection. What still
  needs one is the rendezvous POINT and the flight it prices: the primary's frame origin at the
  ARRIVAL tick, plus a moon's in-cell offset at that tick. **`TargetPrediction`'s iteration
  survives; its convergence test moves from the cell to the point.** (Measured: over a ≤300-tick
  jump a destination planet's frame moves ~1.2×10⁵ blocks = 226 descent radii, while a moon's own
  offset moves ~225 — the frame term dominates, and a test `next.sameCell(aim)`
  (`TargetPrediction.java`) `[V]` would be satisfied on pass 1.) ADDR-14 is void without ADDR-6.
- **ADDR-16 (a PLACEMENT is measured against a position, never against a name)** `[V]` Aiming
  resolves to a name (ADDR-14) because the arrival CELL is what a jump needs. **Putting a craft
  somewhere is the other question**, and it takes the body's position at that tick: a standoff ring
  drawn around a name stands where the body would be only if it never moved (a ship entering space from
  Earth, ringed around Earth's name, arrived **5 657 554 chart blocks** from Earth — 1.41 M km, 26 h of
  flight). `ShipEntryController.aimPoint` resolves the launch DIMENSION's body among those sharing
  the address and rings `SystemBody.absoluteAt(tick)` (a POI still shares a cell it is not the
  primary of, and an aim at a BODY is the honest thing to state). A body that cannot be resolved still
  places the ship — an arrival is never refused — but says so in the log, because falling back to the
  name IS the defect and a silent one reads as a working aim.
  This clause is about placement only; **holding station once placed is C19's subject**, not this
  one's.

- **ADDR-15 (nearness does not create co-location, but it DOES reveal)** `[T]` Two objects with
  different names never share a world, however close their frames pass — a world is resolved from a
  name and from nothing else. **What nearness does do is inform:** proximity keeps granting
  information regardless of names (maintainer ruling 2026-08-01), so the existing distance-gated
  survey tier stays as it is (`NavInfoRedaction.java` `[V]`), and the sky may name a body the
  observer cannot reach (C14 CON-C14-17). A pilot who can read a world he cannot land on is not a bug
  report. (C13's PRESENCE is a different subject and is not used here.)

  > **Not settled — a body's frame sweeping through an occupied void cell.** The maintainer wants the
  > event "a planet flew into a standing ship" kept ("такая система позволит сохранить и «планета
  > влетела в стоящий корабль», и все возможные движения тел"), and flagged that it needs working
  > through from every side. As drafted the clause makes the encounter purely VISUAL — the planet
  > looms and nothing else happens — which is coherent but is not yet a decision. It needs a design
  > pass of its own before anything is built on either reading.

## Clauses — an authored neighbourhood

Maintainer model, ruling 2026-10-03, verbatim:

> "XML конечно такого не говорит, но когда я пишу "вот эта звезда имеет эти 7 планет", я подразумеваю,
> что больше там ничего нет. И просто объявленная звезда это тоже звезда без планет."

*Gloss*: an authored star with N planets has exactly those and nothing else around it; a bare declared
star is a star with no planets.

Terms. An **authored** system is a stored placement that is not a pin — placed from the pack, from the
legacy fallback catalogue, or by a probe through `UniverseRegistry#place` — with no entry in
`pinnedSystems`. A **pinned** system is a procedural one frozen on touch (`pinSystem`). The **authored
neighbourhood** is ADDR-3's box, `minSpacing/2` sectors to each side of the anchor
(`UniverseRegistry#neighbourhoodReach`, `#storedAnchorNear`) `[V]`. A **seat's neighbourhood**
is what the generator says the seat owns (`IGalaxyGenerator#neighbourhoodOf`; for
`ClusteredGalaxyGenerator` its whole lattice cell) `[V]`.

- **ADDR-23 (nothing procedural in an authored neighbourhood)** `[T]` No procedural seat whose OWN
  neighbourhood intersects an authored neighbourhood is a system. The NEIGHBOURHOOD and not only the
  seat (maintainer ruling): a seat outside the box whose cells cross into it would otherwise hand the
  authored system its bodies. It is so for every answer the registry gives —
  attribution (`anchorForCell` answers such a seat's cells outside the box as void), a survey's
  territory (`anchorsInTerritory`), a region (`systemsInRegion`) — through one predicate,
  `UniverseRegistry#clearOfAuthored` `[V]`. **The generator is never told**: it seats
  its field as if authored systems did not exist and stays a pure function of `(seed, config)`, so the
  save's `<galaxyGen>` fingerprint is unchanged. The mask reads the save's own placements at query
  time (`authoredBySuperIndex`), so a moved authored anchor moves its mask with it.
  **A pin is NOT authored** (maintainer ruling): it clears nothing around it. *What a player sees*:
  around Sol in the shipped field, the 27 territories a radar reads hold 94 seats (111 without the
  mask; the region holds Sol and a fallback star); at seed 0 around the origin, 15 seats — 6 inside
  the box and 9 more whose lattice cells cross into it (`AuthoredNeighbourhoodTest`'s own
  measurement). Those seats are unreachable (every cell inside the box already attributes to the
  authored system); the mask is what stops a survey writing them down as phantom addresses under the
  authored star's name.
- **ADDR-24 (an authored system holds what its pack declares)** `[T]` (that the N derived worlds bring
  their own moons and belts was ratified 2026-10-03, *"всё утверждаю"*) Its bodies are exactly its
  declared `<planet>` entries, its companion stars, and — when the pack states `numPlanets` +
  `numGasGiants` = N > 0 — N major worlds derived from `(seed, anchor)` with the moons and belts that
  same derivation brings (`UniverseRegistry#withDerivedRetinue` →
  `ClusteredGalaxyGenerator#authoredRetinueFor` → `appendRetinue`) `[V]`. A star declared with
  neither is its star(s) alone: an absent `numPlanets` reads as 0 with a warning
  (`XMLPlanetLoader#getMaxNumPlanets`) `[V]`, and a count of 0 derives nothing — not even the
  outer belt every procedural system gets, which `appendRetinue` lays only when it runs. The count is
  carried on every load (`DimensionManager.java`) `[V]`. `numPlanets="N"` IS the
  pack's declaration. The no-XML stock universe keeps its code-declared counts, set in
  `DimensionManager.createAndLoadDimensions` `[V]`. Nothing else is attributed to an
  authored system — ADDR-23 is what keeps the procedural field out of its box. *Measured*:
  a count of 3 derived 2 planets, 1 giant, 2 moons, 2 belts.
- **ADDR-25 (one seat, one system, one answer)** `[T]` Every query that names a system for a cell
  names the same one: attribution, a survey's territory, and the photometry a survey reads
  (`TelescopeScan#detect` asks `starAt`, which attributes) `[V]`. Were a seat inside an authored
  box a system of its own to `anchorsInTerritory` and the authored system to `anchorForCell`, a
  survey would register it with the authored star's light and write it down as one more address of
  that star.
  **Known open against this clause**: a PINNED procedural system keeps the
  stored box `minSpacing/2` wide, not its own lattice cell, so pinning a seat attributes its lattice
  siblings to it — measured, 20 pins absorbed 65 of the 67 seats inside their boxes.

## Rulings

1. **Which populations get a moving frame?** → **All of them except void cells.** ADDR-6/7.
2. **What does `SystemBody.address()` return?** → Nothing: it SPLITS into `name()`,
   `inCellOffsetAt(tick)` and `absoluteAt(tick)`. Three consumers want three different things
   (membership wants the name, the sky wants the live absolute, descent wants the offset) and
   collapsing them re-causes the failure above: if `address()` became the name, the observer→body
   vector would go to zero for a body in the observer's own cell and `BoundarySky` `[V]`
   drops it. The spelling is `inCellOffsetAt(long)`, never a no-arg `inCellOffset()`: POIs and
   stations have live offsets, and a no-arg accessor would be a coordinate whose value depends on when
   it was read — ADDR-12's exact prohibition. A fourth accessor `addressAt(tick)` packages
   (name + offset) as a `GalacticCoord`: the canonical STORED form under ADDR-12. **An absolute is
   `AbsolutePos`, never a `GalacticCoord`** — the latter's `ofSectorLocal` carries an out-of-range
   offset into the sector triple, so expressing a frame-displaced position as one silently RENAMES the
   cell the moment the frame origin drifts more than half a cell from `sector*CELL`, which is
   routine orbital travel.
3. **Information vs reachability** → proximity keeps revealing. ADDR-15.
4. **Existing saves** → abandoned: the 0.1.0 clean break covers this relabel too; no migration.
5. **`baseOrbitTheta` is not durable through a save** → both fixes in ADDR-2.
6. **Authored neighbourhoods** (2026-10-03) — the maintainer chose "keep the generator off an authored
   neighbourhood" over "drop shadowed seats from the survey":
   - **R1** — `numPlanets="N"` (+ `numGasGiants`) is the pack's own declaration: an authored star keeps N
     derived worlds. **R1b** — the no-XML stock universe keeps its code-declared retinues.
   - **R2** — NEIGHBOURHOODS, not seats: a seat whose own neighbourhood intersects an authored one is not a
     system, which needs the generator to say what a seat owns (`IGalaxyGenerator#neighbourhoodOf`).
   - **R3** — a registry-owned mask: no fingerprint change, the generator stays a pure function of
     `(seed, config)`.
   - **R4** — a pinned procedural system is NOT authored and clears nothing around it.

## Witnesses - what each `[T]` above is pinned by

Cited by CLASS + METHOD rather than `test:line`: these are the assertions that fail if the clause
breaks, and a method name survives the edits a line number does not.

| clause | witness |
| --- | --- |
| ADDR-1 | `SystemBodyTest.aNameIsTheSameAtEveryTickWhileThePlaceIsNot` (with the "the place is not" leg as its control); `SystemContentTest.aBodysCellIsTheSameCellHalfAnOrbitLater` |
| ADDR-2 | `SystemContentTest.aDifferentAuthoredOrbitIsADifferentCell` (the negative leg - without it the clause is satisfiable by a constant); `UniverseRegistryTest.cellNamesRoundTripThroughNbtAndBeatALaterDerivation` |
| ADDR-3 | `SystemContentTest.authoredPlanetsGetTheirOwnCellsInsideTheSuperCellBox`; `UniverseRegistryTest.aRecordedNameThatLeftItsSystemsBoxIsReDerivedRatherThanServed` + its `...InsideItsBoxSurvivesASmallAnchorMove` control |
| ADDR-4 | audit only, no witness - the collision is REPORTED, never repaired, so there is no behaviour to assert. The moon exemption's absence is witnessed indirectly by `SystemRetinueTest.moonsExistAndGetTheirOwnCellsInsideTheirParentsZone`, which pins that no moon's key is a major body's |
| ADDR-17 | `SystemContentTest.aMoonsCellRidesItSoItsOffsetIsZeroWhileItsPositionIsLive`, `SystemContentTest.aMoonIsAddressedByItsOwnCellInsideItsParentsZone`, `SystemRetinueTest.moonsExistAndGetTheirOwnCellsInsideTheirParentsZone` and `...aMoonIsSomewhereElseThanItsParentAndKeepsMoving`; `SystemBodyTest.onlyBodiesWithMassDefineACellsFrame`; `ParkedCraftKeepsStationTest.aCraftInsideAMoonsOwnCellKeepsStationWithIt` (the acceptance half that holds) and `...aCraftOneDescentShellOutFromAMoonIsNotYetAddressedInsideItsZone` (the half that does not) |
| ADDR-19 | `ZoneScaleTest` (the three bounds on real bodies; and `aCraftIsReAddressedOnTheLatticeItsZonesBodiesAreNamedIn`, that a craft is addressed on the lattice the naming pass recorded); `GalacticCoordZoneTest` (the carry at the zone's width, the refusal with no width, the refusal across two lattices, and the galactic-cell question); `ZoneCrossingAimsAtTheRightCellTest` (the same question through the whole crossing, against a registry that has NAMED the bodies — the level at which a second-lattice defect is reachable) |
| ADDR-6 | `UniverseRegistryTest.aBodyCellRidesItsPrimaryWhileAVoidCellStandsStill`; `SystemBodyTest.aPrimarySitsAtItsOwnFramesOrigin`; `SystemBodyTest.aBodyStillMovesAfterAnNbtRoundTrip` (the pin-freezes-ELEMENTS half) |
| ADDR-7 | `CellFramesTest.aVoidCellSitsWhereItsNameSaysForever`; the void half of `UniverseRegistryTest.aBodyCellRidesItsPrimary...`, which is ADDR-6's control |
| ADDR-8 | `CellWorldMapperTest` (the `coordOfPoseWithin` / `poseEscapesCell` legs) |
| ADDR-9 | `CellFramesTest.aDistanceBetweenTwoCellsChangesWithTimeWhenOneOfThemMoves` + its static control; `CellFramesTest.twoCellsInOneMovingSystemKeepTheirDistanceIfBothRide` |
| ADDR-10 | `CellSeamTest` (the pure layer: the margin, the neighbour it lands in, the axes that did not cross, and the return trip measured FROM the arrival); `VSShipCellSeamTest` (a real ship, moved past a real face, is carried into the neighbour, arrives inside it and stays — driven through `space seam-carry`, because a headless slot world does not tick its tiles, so the two lines in `TileAdvancedFlightComputer` that JOIN the predicate to the carry are covered by neither test); `StandoffRingTest` (the saturation that survives as the refusal path); `SystemBody.inCellOffsetAt`'s clamp |
| ADDR-11 | `[V]` - the bytes round-trip (`SystemBodyTest.nbtRoundTripPreservesEveryField`), but "the meaning survives" is a statement about the whole save and has no single assertion |
| ADDR-12 | `TransitRecordTest.roundTripPreservesLogicalStateAndCrew` (origin name + progress, no raw absolute); `ShipTransitTest.bothEndsOfTheFlightSurviveIt` |
| ADDR-13 | `CellFramesTest.aDistanceBetweenTwoCellsChangesWithTimeWhenOneOfThemMoves`; `SystemBodiesProducerTest.aBodyInAMovingCellIsFedFromWhereItIsNotFromWhereItsNameSays` |
| ADDR-14 | `TargetPredictionTest.theAimedCellIsTheBodysDurableNameWhateverTheFlightCosts`; `...theAimIsWhereTheBodyWillBeWhenTheFlightEnds`; `...aMovingFrameChangesTheAnswer` (which is what fails if the pricing stops reading the frames) |
| ADDR-15 | `NavRedactionAndSyncTest.theOrbitTierFollowsTheMeasuredDistanceNotTheCellName` |
| ADDR-23 | `AuthoredNeighbourhoodTest.noSeatThatReachesAnAuthoredNeighbourhoodIsASystem` (territory and attribution, a seat inside the box and one crossing into it); `AuthoredNeighbourhoodTest.aPinnedSystemClearsNothingAroundIt` (R4); `TelescopeRegionScanServerTest.aSurveyWritesASystemOnceAndNoNeighbouringSeatUnderItsName` (the survey's crystal, through a running observatory) |
| ADDR-24 | `PlanetDefsAuthoringTest.anAuthoredStarHoldsWhatItsPackDeclaresAndNothingElse` (a bare star, and a star with one declared body and a count of 3) |
| ADDR-25 | `TelescopeRegionScanServerTest.aSurveyWritesASystemOnceAndNoNeighbouringSeatUnderItsName` (no record under a system's name at another cell); the pinned half is not pinned |

**Not pinned:** no SERVER e2e asserts a parked ship keeps its bodies across a long dwell, and no CLIENT
e2e asserts a body visibly recedes on the real sky.

## Open — not blocking

- **Extended structures.** An asteroid belt is the size of an ORBIT; "a primary owns one name"
  cannot express it, and neither can this contract. Unowned.
- **Procedural bodies do not move** (ADDR-6): generator orbits are unauthored.
- **ADDR-4's structural collision** (orbit radius < 2 inside the star's cell) and the load-time audit
  site that does not exist.
