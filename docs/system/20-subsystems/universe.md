---
id: universe
owns: [universe/]
entrypoints: [UniverseRegistry#populate, UniverseRegistry#get, UniverseRegistry#systemBodiesAt, UniverseRegistry#pinSystem, PlanetRealizer#realize, PlanetRealizer#realizeOnZoneEntry, TelescopeScan#resolveBatch, ClusteredGalaxyGenerator#anchorAt]
depends-on: [space-model (GalacticCoord / AbsolutePos / ZoneScale in space/), dimension-planets (StellarBody, DimensionProperties, DimensionManager), api-public]
depended-by: [navigation (crystals, nav console), space (SystemBodiesProducer, ShipEntryController, ReferenceFrames, DescentShell), tile/multiblock (TileObservatory), tile (TileAdvancedFlightComputer, TileNavigationComputer), client-render (sky), hyperdrive (DriveTier), commands-gameplay (/stellurgy universe)]
contracts: [C1, C14, C15, C16, C24, C29]
confidence: high
---

> Written 2026-10-06 against `feature/life_support`; `source-sha` is `git rev-parse HEAD:src/main/java/dev/stannismod/stellurgy/universe`
> (no uncommitted change under that root). `sloc` is `wc -l` (47 files). **Citation grammar**: a file name opens a group,
> `File.java:12-20, 44, 90` — every number belongs to the nearest file name to its left, nothing is bare. A `*Test.java`
> lives under `src/test/java/dev/stannismod/stellurgy/test/{unit,integration,server}/`; everything else is a `src/main` file
> (`universe/` unless a package is written).

## Purpose

The **Layer-1 universe**: what exists in the galaxy and where it is, derived on demand from a world seed
and a pack configuration, plus the few things that are *stored* — authored placements, durable body
names, and the snapshot of any system a player has touched. Nothing here loads a world: a system, a
body and a planet's physics are answered for places nobody has visited, and a **descent** is the one
act that turns an answer into a Forge dimension. The design root is [`universe-model.md`](../universe-model.md);
this doc maps what the code in `universe/` actually does. The clauses it must keep are in
[C15](../30-contracts/C15-cell-address-and-frame.md) (names, frames, neighbourhoods) and
[C1](../30-contracts/C1-nbt-persistence.md) (every NBT key).

## Responsibility boundary

**Owns**: the galaxy → galaxy-cell → star-territory → cluster lattices and their placement draws; system
fabrication (stars, companions, rogue worlds); the retinue (planets, moons, belts) and body physics
(`PlanetDerivation`); the body value types (`SystemBody`, `CellFrame`, `BodyEphemeris`) and the cell-name
rule; the registry — placement store, pin-on-touch store, recorded cell names, member→anchor
attribution; the world-model **schema** and its save stamp; the telescope's detection / survey geometry
(`TelescopeScan`, `RegionScan`, `ConeWalk`, `StellarMagnitude`); the descent's realization step
(`PlanetRealizer`).

**Does NOT own**: the address type and its arithmetic (`GalacticCoord`, `AbsolutePos`, `ZoneScale` — `space/`,
C15's terms); dimensions, `DimensionProperties` and the legacy star catalogue (`dimension-planets`); the
space clock (C16 — this layer takes a `tick` and never reads a clock); the descent *trigger*, ship
crossing and arrival (`space/`); what a nebula *does* (no consumer is built); the crystal / nav console
(`navigation/`); the `/stellurgy universe` commands (`command/sub/universe/`, consumers of the registry's
stamp API); the sky renderer (C14 owns the feed contract; `SystemBodiesProducer` / `SkyNebulaeProducer`
call this layer).

## Key types

| type | role |
|---|---|
| `UniverseRegistry` | the one `WorldSavedData` (`stellurgy_universe`) on the overworld's map storage; placement store, pins, recorded names, schema stamp; the single owner of galactic PLACEMENT |
| `IGalaxyGenerator` / `ClusteredGalaxyGenerator` / `EmptyGalaxyGenerator` | the pure `(seed, cell)` placement seam; the shipped procedural generator; the void answer for `procedural="false"` |
| `GalaxyField` / `Galaxy` / `GalaxyKey` / `GalacticAnchor` / `GalacticFrame` / `LightYearVector` / `Cosmology` | the galaxy tier: seating, density profile, satellites, rotation law, expansion, authored declarations, the bound-vs-comoving frame |
| `ClusterField` / `StarCluster` / `NebulaField` / `Nebula` | the tier below the star lattice (finer sub-lattices) and the diffuse gas derived from a cluster |
| `SystemBody` / `SystemBodyKind` / `PlanetarySystem` | one addressable object (name + frame + law + bulk); its kinds; the query-time handle to what stands at an anchor |
| `CellFrame` / `BodyEphemeris` | a cell's origin as a function of tick (nestable); a body's orbit frozen as elements, never as a position |
| `SystemContent` | derives an AUTHORED system's bodies from its `StellarBody` / dimension tree; owns the cell-name rule and the box clamp |
| `PlanetDerivation` / `BodyDerivationV0` / `IBodyDerivation` / `BodyProfile` | body physics from `(seed, cell, variant)`; the version-0 forwarder; the seam a schema selects; the immutable result that crosses to the dimension layer |
| `PlanetTypes` / `PlanetTypePreset` / `TerrainOption` | the weighted type table (stock in code, `<planetType>` overrides) and its terrain draw |
| `PlanetRealizer` | an arrival's step — a descent (`realize`) or, for a gas giant, a craft recorded in its zone (`realizeOnZoneEntry`): pin, find the body's variant, derive, write a `DimensionProperties`, record the dimension on the pinned body |
| `UniverseSchema` / `UniverseSchemaV0` / `UniverseSchemas` / `UniverseSchemaMismatchException` | one released world model (generator + derivation + laws), the catalogue of them, the refusal |
| `UniverseScale` / `IUniverseLaws` / `UniverseLawsV0` / `GalaxyGenConfig` / `Fingerprint` / `CellHash` | the metric (light years ↔ cells), its versioned handle, the pack's `<galaxyGen>` knobs and their digest, the 64-bit mixer every draw uses |
| `TelescopeScan` / `RegionScan` / `ConeWalk` / `StellarMagnitude` / `InfoTier` / `PlanetInfoField` | detection + characterisation, the resumable survey, the cone walk, photometry, the graded-discovery classification |
| `SectorBox` / `ReportOnce` | an inclusive box of galactic cells (a neighbourhood); the per-galaxy "report each fault once" memory |

## Mechanics

### MECH-UNI-01 — Everything procedural is a pure function of (seed, config, schema); nothing is stored
Every placement and body draw goes through one mixer, `CellHash.of` (`CellHash.java:29`; per-cell `ofCell` and per-body
`ofBody`, `CellHash.java:70, 81`), keyed by the world seed, a sector triple and a per-field **salt**. A ZONED cell's triple is
counted in its parent's zone lattice and so repeats in every zone; its zone chain is folded into the seed first
(`seedIn`, salt `SALT_ZONE`, `CellHash.java:53-67`), so the same zone-local cell of two planets is two draws while a galactic
cell's draw is untouched `[T]` `PlanetDerivationTest` (the same zone cell of two planets names two different moons). Salts are never reused: a retired draw leaves its number burned
(`ClusteredGalaxyGenerator.java:53-56, 118-120, 122-124`). The arithmetic is a save-compatibility surface for the life of a
world — an unpinned system is re-derived on every query — so a changed constant is a changed universe, caught by the golden
corpus and the stamp (MECH-UNI-14). `[V]` `[T]` `ClusteredGalaxyGeneratorTest.java:179` (point queries are stable), `ClusteredGalaxyGeneratorTest.java:1084`
(a fixed corpus of seeds renders byte-identically; a diff means a new schema version).

### MECH-UNI-02 — The lattices, and where the numbers come from
Space is partitioned four ways, each "one object per cube, seated at a hash offset inside it, never straddling a face":
**galaxy cells** (`galaxySpacing`, at most one galaxy, probability `galaxyDensity`), **star territories** (`minSpacing`
cells, at most one seat), **cluster cells** (`CLUSTER_SPACING_LY` = 300 ly of coarse super-cells, at most one cluster —
`GalaxyGenConfig.java:541`) and the **sub-lattice** a cluster or the uniform rogue division cuts a territory into (`k³` seats;
MECH-UNI-04). Every length is stated physically and converted, never written as a cell count (`UniverseScale.java:6-14`):
mean star separation 4.23 ly (`UniverseScale.java:58`), occupancy 0.35 (`UniverseScale.java:69`), the derived edge
`DEFAULT_SPACING_CELLS` (`UniverseScale.java:112`), the 10 000 AU separation floor (`UniverseScale.java:79`) whose half is
the reach of a system's NAMED bodies (`UniverseScale.java:92`), a 50 000 ly reference galaxy and 25-diameter galaxy spacing
(`UniverseScale.java:155, 163, 173`). The galaxy index is offset by half a cell so the ORIGIN is a cell centre
(`GalaxyField.java:142`). Balance numbers are `tunable`; the metric itself is versioned through `IUniverseLaws`
(`UniverseLawsV0.java:15`), a pure forwarder to `UniverseScale` / `Cosmology`. `[V]`

### MECH-UNI-03 — A galaxy: profile, satellites, rotation, expansion, frame
`GalaxyField.galaxyAtIndex` (`GalaxyField.java:512`) seats a galaxy from `hash(seed, gx, gy, gz)`: type by weight, radius
**inside the type's band** (type and size are one draw, not two), isotropic orientation, arm pitch and phase, a peculiar
velocity clamped so the group cannot leave its cell within `Cosmology.DRIFT_HORIZON_TICKS` (`GalaxyField.java:551`,
`Cosmology.java:62`). Cell `(0,0,0)` is **reserved**: always a galaxy, drawn only among types large enough for authored
content, seated *around* the origin (origin at 0.55 R in the disc), and it does not move (`GalaxyField.java:490-495, 662-671`,
`UniverseScale.java:255`). Authored declarations name a `GalaxyKey` and force their cell to hold a galaxy; existence is
guaranteed, parameters stay drawn (`GalaxyKey.java:13-16`, `GalaxyField.java:472`). A cube also holds a **retinue of satellite
galaxies** (`GalaxyField.java:307`; type drawn among those whose band fits under 0.3 of the primary, seated 1-3 diameters
out), so *which galaxy is this point in* has at most one answer (`GalaxyField.java:196`). `Galaxy.densityAt`
(`Galaxy.java:272`) is the ONE function that places stars and shapes the galaxy: exponential disc × arms + bulge, normalised so
a sun-like radius reads 1. Outside the declared radius the material is the isotropic **ejecta halo** `(R/r)^falloff`
(`Galaxy.java:319`) — the intergalactic population is a consequence of the galaxies, not a second field
(`GalaxyField.java:269`). Rotation is `θ₀ + ω(r)·t`, analytic (`Galaxy.java:369, 415`); expansion `a(t)=exp(H·t)` scales only
the galaxy *centre*, never bound contents (`Galaxy.java:403`, `Cosmology.java:71`). A bound cell rides its galaxy; a void cell
is comoving; the frame is asked **once at a crossing and stored by the caller**, never per tick (`GalaxyField.java:588`,
`GalacticFrame.java:15-20`); `GalaxyField.java:600` is where the two laws meet. The cosmic web is a named slot (`webDensity`
is the constant 1, `GalaxyField.java:639`). `[V]` `[T]` `GalaxyFieldTest.java:503` (no two galaxies in a cube overlap), `GalaxyFieldTest.java:709` (a galaxy cannot drift
out of its cell), `GalaxyFieldTest.java:635` (a satellite's interior is bound to the satellite).

### MECH-UNI-04 — A star territory: occupancy, the seat, the uniform and cluster sub-lattice
A coarse super-cell is divided `k³` ways, `k = uniform × cluster`. `uniform = ceil(cbrt(rogue.abundance))` capped at 4
(`ClusteredGalaxyGenerator.java:1380, 1392`; the cap equals `TelescopeScan.MAX_SEATS_PER_LOOK` = 4³ — one bound seen from
placement and from observing, `TelescopeScan.java:67`); a cluster multiplies it by its own `k` (a nucleus scales with its galaxy,
`ClusterField.java:117`), and neither may refine below `MIN_LATTICE_EDGE_CELLS`
(`ClusteredGalaxyGenerator.java:1409`, `UniverseScale.java:276`). Membership of a cluster is per coarse cell, so the fine
lattice tiles exactly (`StarCluster.java:91, 105`). A lattice cell hosts a **star system** when
`hash < min(1, density × galaxy-bound / dilution)` (`ClusteredGalaxyGenerator.java:1040-1043, 1077-1079`); otherwise a
**second, independent draw** on the same cube decides whether an unbound world sits there, scaled by `bound + ejecta`
(`ClusteredGalaxyGenerator.java:1045-1054, 1122`) — so the void's population thins with distance from the galaxies with no
rule of its own. The seat is anywhere in the cube except a margin `laws.seatMarginCells(edge)` at the faces
(`ClusteredGalaxyGenerator.java:1157, 1189`); the margin is the guaranteed clear space, sized by what a system needs rather
than a fraction of the cube, and shrinks with the local lattice. A cluster outside every galaxy brings its own field
(`ClusteredGalaxyGenerator.java:1348`) and only self-bound types are drawn there (`ClusterField.java:155-158, 196`).
`anchorAt` answers the SEAT without fabricating the system (`ClusteredGalaxyGenerator.java:928, 1039`); `systemAt` answers
only at the seat cell (`ClusteredGalaxyGenerator.java:301-309`). `[V]` `[T]` `StarClusterTest.java:293` (attribution never
crosses a coarse super-cell), `VoidContentTest.java:120` (a cube with no galaxy is empty).

### MECH-UNI-05 — Fabricating a system
`fabricate` (`ClusteredGalaxyGenerator.java:1462`) draws star type by weight (stock table = observed number abundances, M
dwarfs about 76 %: `GalaxyGenConfig.java:445`), size inside the type's band, a **negative synthetic id** from a reserved slot of
`ID_SLOTS_PER_SYSTEM` (`ClusteredGalaxyGenerator.java:1590`; never collides with a catalogue id or a dim id), name `PGS-x.y.z`.
About 45 % are multiple; 1-3 companions (the falling distribution is the private enum `CompanionCount`,
`ClusteredGalaxyGenerator.java:84-104, 1551-1582`) are real `StellarBody`s with ids in the primary's slot, a log-uniform
separation bounded by the system's clear space, never larger than the primary. A companion is seated once, in
`fabricate` (`seatCompanions`, `ClusteredGalaxyGenerator.java:1498, 1515-1530`): a companion whose first cell is taken is
walked round its ring like any body, and the STAR is handed the angle of the seat it ends up in, so every sky, chart and
selector that draws it at `getBaseTheta()` draws it where its body stands `[T]`
(INV-UNI-14). An unbound
seat holds a **rogue**: a weighted table, mostly a starless `ROGUE_PLANET` system (`PlanetarySystem.java:60`), about one in a
thousand a whole star thrown out (`GalaxyGenConfig.java:656-660`). A system's primary is `Optional<StellarBody>` — a starless
system makes the caller decide, instead of receiving a 30 K zero-radius "star" (`PlanetarySystem.java:73`). `[V]` `[T]`
`VoidContentTest.java:147, 183` (void systems are mostly starless; a starless system names itself).

### MECH-UNI-06 — The retinue: orbit first, cell second, drop rather than squeeze
`appendRetinue` (`ClusteredGalaxyGenerator.java:553`) walks `count` bodies; `retinueSize` is a shifted exponential, 3 + tail,
hard-capped at 24 because the derivation runs on every query (`ClusteredGalaxyGenerator.java:148-155, 663`). For each body the
**orbit is drawn first** (log-uniform over the star's own zone, a slot per body so order never swaps:
`PlanetDerivation.java:241`) and the cell **follows from the orbit** by evaluating the body's own law at `NAME_TICK`
(`ClusteredGalaxyGenerator.java:710`). A body whose orbit lies past the system's clear space is **dropped, never moved
inward**, and one inside an unstable band around a companion (⅓–3× its separation) is dropped too
(`ClusteredGalaxyGenerator.java:573-577, 1553`); a body whose first cell is taken is walked round its ring by the golden angle
(96 tries) and costs its *angle*, never its distance, else it is dropped (`ClusteredGalaxyGenerator.java:189-191, 710-726`).
Planet-vs-giant is not a roll: it falls out of `PlanetDerivation` (cold orbit ⇒ likely giant, MECH-UNI-12). Moons: 0-2 (rocky)
or 0-5 (giant), biased low (`ClusteredGalaxyGenerator.java:158-160`), orbit 2.5-12 parent radii (`ClusteredGalaxyGenerator.java:172-173`),
**named in their parent's ZONE lattice** (`SystemContent.java:351`) with a frame NESTED in the parent's
(`ClusteredGalaxyGenerator.java:837-890`). A moon's bulk is derived under the key a descent realizes it with — its OWN cell
and its variant in that cell (`variantInCell`, the generator-side `UniverseRegistry#variantOf`) — never the parent's cell
and the moon's number, so the moon the sky shows is the moon landed on (`ClusteredGalaxyGenerator.java:870-877, 893`; a
rogue's moons `:550-555`) `[T]` (INV-UNI-15). An inner belt exists only inside a giant's resonance gap; the **outer belt is
mandatory** at 1.6× the outermost world (`ClusteredGalaxyGenerator.java:611-627`). The same code gives an authored system a
derived retinue bounded by the pack's `numPlanets` (`ClusteredGalaxyGenerator.java:641`; registry side
`UniverseRegistry.java:2135`). A rogue system holds the rogue, ≤ 2 moons, no belt, no orbit, no zone
(`ClusteredGalaxyGenerator.java:496-538`). `[V]` `[T]` `SystemRetinueTest.java:155` (no two real bodies of a system share a
cell — moons skipped there), `ClusteredGalaxyGeneratorTest.java:636` (every body attributes back to its anchor),
`ClusteredGalaxyGeneratorTest.java:744` (an authored system's derived retinue is the same every save).

### MECH-UNI-07 — An authored system: names derived at tick 0, clamped into the box
`SystemContent.bodiesOf` (`SystemContent.java:164`) turns a `StellarBody` and its dimension tree into `SystemBody`s: the star at
the anchor (static frame, its MASS carried because every zone is sized against it), each planet at its own cell, each moon at a
cell of its parent's zone. A body's **name** is its orbital elements evaluated at `NAME_TICK = 0` (`SystemContent.java:97`) — the
one instant whose time term is zero for every period — snapped to a cell centre and clamped into the anchor's box
(`SystemContent.java:307, 426`); where it IS stays a function of time via the frame (MECH-UNI-08). A moon's authored orbit is a
**floor, not a replacement**: lifted to 2.5 parent radii, and its period is that of the orbit it is actually on
(`SystemContent.java:271-292`). The innermost moon decides the parent's lattice and is settled before any moon is named
(`SystemContent.java:255`). A body with no surface is typed `GAS_GIANT` here, at the one place bodies are made
(`SystemContent.java:377`). A parent with no mass has no zone: its moons FALL BACK to the parent's cell and one report says so
(`SystemContent.java:351-367`). Declared positions are galaxy-local `GalacticAnchor`s resolved **once** to an absolute cell
(`GalacticAnchor.java:54`, `UniverseRegistry.java:1430` — beyond the guaranteed radius is a loud error, never a clamp). `[V]`
`[T]` `UniverseRegistryTest.java:138` (a recorded name beats a later derivation).

### MECH-UNI-08 — A body is a NAME plus a FRAME plus a LAW
A `SystemBody` carries an immutable **name** (the sector triple of its cell, at cell centre), a `CellFrame` (its cell's origin as
a function of tick), a `BodyEphemeris` (offset inside that cell) and bulk (radius, mass in Earth units; unknown is a sentinel,
not a guess). `addressAt(tick)` = name + in-cell offset — the canonical stored/aimed form; `absoluteAt(tick)` is an
intermediate only, never stored (`SystemBody.java:268, 277`). A cell's PRIMARY (star, planet, giant, belt, rogue **and moon**)
is at its own frame's origin (`SystemBody.java:336`); a POI persists as name + offset and is re-bound to its cell's frame when
served (`SystemBody.java:347`, `UniverseRegistry.java:666`). `CellFrame` nests: `within(parent, law)` takes the parent's origin
AT THE TICK (`CellFrame.java:57, 65`), so a craft beside a moon keeps station. A law is frozen as elements (distance, base
angle, inclination, retrograde, period): `offsetAt` rounds to blocks (`BodyEphemeris.java:129`), so the velocity is the
analytic derivative (`BodyEphemeris.java:159`), not a difference of two readings. A **pin freezes elements, never positions**.
A body whose law reaches outside its own cell is refused at construction (`SystemBody.java:154`). An unknown kind reads back
as an inert `STATION_SLOT` (`SystemBody.java:377`). `[V]` `[T]` `SystemBodyTest.java:233` (an offset outside the cell is
refused). Frame semantics are C15's (ADDR-6..ADDR-16, zones ADDR-17..ADDR-19).

### MECH-UNI-09 — The registry resolves a cell: pinned → authored → generator, member → anchor
`UniverseRegistry` answers coord→system and system→coord (`UniverseRegistry.java:306, 1209`). **Attribution** is
`anchorForCell` (`UniverseRegistry.java:319`): the cell's *galactic* cell (a zoned name asks through its zone) → an
authored/pinned anchor whose neighbourhood — the box `minSpacing/2` sectors either side of the anchor
(`UniverseRegistry.java:387`) — contains it, looked up over the 27 super-cells around it, nearest then lowest key
(`UniverseRegistry.java:357`; the index is keyed by super-cell and holds ONE anchor per super-cell,
`UniverseRegistry.java:511-533`) → the generator's seat, **only if it is `clearOfAuthored`** (`UniverseRegistry.java:408`): a
procedural seat whose own neighbourhood (`IGalaxyGenerator.java:133`) reaches an authored one is not a system for ANY question
(attribution, a survey's territory, a region). The generator is never told where authored systems are, so it stays a function of
`(seed, config)`. A pin is NOT authored and clears nothing (`UniverseRegistry.java:459-479`). Content is read pinned →
catalogued star (`SystemContent` + derived retinue) → generator (`UniverseRegistry.java:776, 495`). The reads differ:
`bodiesAt` is the CELL read (a moon is NOT in its planet's list; `UniverseRegistry.java:635`), `systemBodiesAt` the SYSTEM read
plus the POIs of the cells that host bodies (`UniverseRegistry.java:693`), `skyBodiesAt` the union with the observer's own cell,
so a station in a void cell survives and interstellar void is empty (`UniverseRegistry.java:757`). `originAt(name, tick)` is
the frame origin of a cell: its primary's position, else the zone's body displaced by the cell's fixed lattice offset, else
static (`UniverseRegistry.java:930`). `anchorsInTerritory` (`UniverseRegistry.java:554`) is what one survey look owes its
direction. `[V]` `[T]` `AuthoredNeighbourhoodTest.java:85, 153`; `UniverseRegistryTest.java:682` (a body cell rides its
primary, a void cell stands still), `UniverseRegistryTest.java:715, 757`. Clauses: C15 ADDR-3, ADDR-23..ADDR-25.

### MECH-UNI-10 — A cell name is recorded once, served only while it is true
`durableName` (`UniverseRegistry.java:822`) is the `CellNames` hook the registry hands `SystemContent`: a body keyed by
dimension id gets its derived name recorded on first sight (`namesByDim`, persisted as `cellNames`) and is served the RECORDED
name thereafter — surviving XML angle round-trips, a spacing change, any later edit to the derivation. The record carries the
owning star id because **dimension ids are recycled**: a name recorded for another system is reported and re-derived; so is one
that has left its system's box (the body would stay listed and be impossible to arrive at). `forgetName` runs from the
dimension lifecycle (`UniverseRegistry.java:868`, `dimension/DimensionManager.java:586`). Bodies with no dimension (star proxy,
belts, POIs) are not keyed and keep the derivation, which is time-invariant for them. A config XML reset re-places anchors and
leaves names alone (`UniverseRegistry.java:1395`). `[V]` `[T]` `UniverseRegistryTest.java:550` (a recycled id does not inherit a name), `UniverseRegistryTest.java:612` (a name
that left its box is re-derived), `UniverseRegistryTest.java:138`. See C15 ADDR-1/ADDR-2, C29 DIMID-5.

### MECH-UNI-11 — Pin-on-touch, and the variant that is a body's identity inside a cell
A **touch** pins the procedural system whose neighbourhood holds the cell (`UniverseRegistry.java:982`): its drawn star
properties and its full body list are snapshotted into `pinnedSystems`, keyed by anchor cell, immune to later seed / config /
XML edits; a pin is placed into `byCell` / `byStar` like an authored placement (`UniverseRegistry.java:1348`). Triggers:
`addPoi` (`UniverseRegistry.java:964`), every telescope `characterise` (`TelescopeScan.java:253`), the realizer
(`PlanetRealizer.java:82`), and the upgrade command's crystal sweep (`command/sub/universe/UniverseUpgradeCommand.java:87`). A
starless system pins as a `ROGUE_PLANET` primary (`UniverseRegistry.java:1004-1006`). Within a cell the only identity a
realizable body has is its **variant** = its index among `realizableBodiesAt(cell)` (stars, station slots and belts
excluded, `UniverseRegistry.java:1105-1120`); `variantOf` matches by cell, kind, orbit, offset law AND frame (siblings share
the parent's orbital distance) and **refuses an ambiguous match** (`UniverseRegistry.java:1149-1172`). The cell and that
variant are the body's derivation key: `derivedProfileOf` (`UniverseRegistry.java:1186-1190`) is the one call a descent
materializes from, and the generator draws a moon's size under the same key (MECH-UNI-06). `realizeBody` rewrites
only a PINNED body, is idempotent, and records the dimension's cell name (`UniverseRegistry.java:1056`). `[V]` `[T]`
`UniverseRegistryTest.java:332` (a pin is a snapshot against a seed change), `TelescopeRegionScanTest.java:571` (a scanned
system is frozen against a later retune), `TelescopeRegionScanTest.java:624` (a look at nothing freezes nothing),
`PlanetRealizationTest.java:373` (an unpinned system refuses the rewrite), `PlanetRealizationTest.java:343, 154`.

### MECH-UNI-12 — A body's physics is derived in a fixed order
`PlanetDerivation.derive` (`PlanetDerivation.java:330`) draws, from `(seed, body cell, variant)` and the star: metallicity (a
property of the STAR, keyed on the anchor, `PlanetDerivation.java:196`), the orbit's bare temperature, giant-or-not (chance by
snow line, `PlanetDerivation.java:85, 301`), radius, mass (rock `M=ρR^3.7`, giant from Jupiter), gravity `M/R²` (derived,
clamped), pressure from atmospheric retention (`PlanetDerivation.java:529`), then **type = a weighted draw among every preset
that admits the world, each tested at the temperature it would have if it were that type** (albedo is the type's;
`PlanetTypes.java:129, 151`), then what air the world actually KEEPS (`PlanetDerivation.java:368` — a world that keeps none
reports pressure 0 and its bare temperature, type kept if it admits that, else redrawn among airless types), terrain from the
type's list, oxygen as an independent rare biology roll, rings, a drawn (not gravity-derived) day (`PlanetDerivation.java:492`).
**Zoning emerges; nothing is placed by hand.** `deriveRogue` (`PlanetDerivation.java:428`) drops everything that needs a star:
one kind `ROGUE_PLANET`, residual temperature `35 K·g^¼` (`PlanetDerivation.java:471`), no oxygen, no lock. No type admitting a
world yields `null` → `unclassified`, **reported, never substituted** (`PlanetTypes.java:151-168`). Overlap is by weight, never
first match; the terrain draw drops entries naming a `WorldType` the modset lacks BEFORE drawing (`PlanetTypes.java:189-207`).
Every constant is `tunable` and unpinned; relations are pinned. `[V]` `[T]` `PlanetDerivationTest.java:660` (an unadmitted
world is reported, not substituted), `PlanetDerivationTest.java:681` (a scanned pressure is air the world keeps),
`PlanetDerivationTest.java:263` (a moon is not a copy of its planet).

### MECH-UNI-13 — Realization: an arrival materializes, never rolls
Two entries, one per kind of arrival, both ending in `materializeVariant` (`PlanetRealizer.java:258`). **A gas giant is realized
when a craft is recorded in its ZONE** (ruling 2026-10-05): `ShipLedger#settle` tells its observer
(`ShipLedger.java:97`), which `SpaceSubsystem` wires (`SpaceSubsystem.java:105`) to hand the body whose zone the address names —
the craft's own galactic cell, or the cell its zone key names (`SpaceSubsystem.java:538`, the same reading C19's membership
uses) — to `PlanetRealizer.realizeOnZoneEntry` (`PlanetRealizer.java:205`), which answers `INVALID_PLANET` for every kind but
`GAS_GIANT`, pins, and materializes properties with no Forge world (INV-DIM-09). Every way into a cell ends in that `settle`
(seam and zone crossings, hyperspace and short-jump arrivals, entry, restore), so none needs a hook of its own; idempotent, so
every settle may report. Before it a moonless giant never became a place and the gas-harvest loop could not start over it.
`[V]` `[T]` `ProceduralPlanetRealizationTest#aGasGiantBecomesAPlaceWhenACraftIsRecordedInItsZone`.
**A descent realizes everything else**: `PlanetRealizer.realize` (`PlanetRealizer.java:73`). Order: **pin first**; refuse a
kind that cannot descend (`PlanetRealizer.java:91`); find the variant (`PlanetRealizer.java:92`); return the existing dimension
if the body has one (`PlanetRealizer.java:98-101`); a moon reached before its parent first realizes its parent — a gas giant's
moons always take this path (`PlanetRealizer.java:162-185`); else allocate a dimension id, derive the profile through
`UniverseRegistry#derivedProfileOf` (`PlanetRealizer.java:222`),
`materialize` it into `DimensionProperties` (profile numbers STATED, never recomputed; the type's albedo, biomes, sea level, ore
table and terrain fixed here — from then on the SAVE owns how the world generates), register it, and write the dimension onto
the pinned body (`PlanetRealizer.java:216-237`). A moon's orbital distance comes from the law its cell rides, its star-distance
stays the parent's (`PlanetRealizer.java:264-271`). A procedural star keeps its synthetic negative id and is added to the
catalogue on first realization (`PlanetRealizer.java:207-214`). There is no un-realize path. `[V]` `[T]`
`ProceduralPlanetRealizationTest.java:77` (a realized planet matches `derived`, field by field — Open questions for the moon
case), `ProceduralPlanetRealizationTest.java:175` (a moon realized before its parent is still a moon).

### MECH-UNI-14 — The world model a save was generated under, and its refusals
A **schema** = generator + `IBodyDerivation` + `IUniverseLaws`, selected by the SAVE's version, never by the pack
(`UniverseSchema.java:55`; the only released one is `UniverseSchemaV0`, label `"0.1"`, an ALPHA — the leading zero says it may
be replaced outright: `UniverseSchemaV0.java:49-52`, `UniverseSchema.java:83`). `UniverseRegistry.populate`
(`UniverseRegistry.java:1800`) binds the world seed, calls `reconcileSchema(packConfig)`, installs that schema's generator
(`attachSchemaGenerator` refuses to run before a model is in force, `UniverseRegistry.java:1871`), drains staged anchors
through it, then gives every catalogued star without a placement a deterministic fallback cell
(`UniverseRegistry.java:1448`). `reconcileSchema` (`UniverseRegistry.java:1657`): UNSTAMPED (−1, read through `hasKey` because
0 is a real version; `UniverseRegistry.java:71, 1948`) adopts the current model and stamps; otherwise a stamp this build does
not carry **refuses**; the stamped schema's laws and derivation are re-measured (`UniverseRegistry.java:1529, 1574` — fixed
inputs through every conversion / a probe of rogue and bound bodies, hashed with `Fingerprint.hex16`) and a mismatch against
the stored value **refuses** ("a released version was edited in place": a developer error no upgrade may accept); a
`<galaxyGen>` fingerprint that differs **refuses** unless `upgradeArmed`, which is spent once and only when the config
actually differs (`UniverseRegistry.java:1711-1721`). An empty stored laws / derivation stamp means "never measured" and is
adopted. The one door out is `/stellurgy universe upgrade confirm` (pin everything on online players' crystals,
`adoptSchema` `UniverseRegistry.java:1761`, re-attach the generator, `armUpgrade` `UniverseRegistry.java:1754`;
`command/sub/universe/UniverseUpgradeCommand.java:76-104`). New versions are written as DECORATORS over the previous one
(`UniverseSchema.java:37-50`). `[V]` `[T]` `UniverseRegistryTest.java:830, 853, 1031, 917, 1080`;
`ClusteredGalaxyGeneratorTest.java:1084`. (`derivationFingerprintOf` has no test: Open questions.)

### MECH-UNI-15 — Clusters and nebulae: a cloud is a cluster at another age
A `Nebula` is derived from a `StarCluster` and its type's residual gas (a draw around `nebulaFraction`); appearance is an age
sequence DARK → EMISSION → REFLECTION by gas left, not a choice (`NebulaField.java:55`, `Nebula.java:182`); it is Gaussian
with scale `R/2`, zero beyond `R` (`Nebula.java:145`), and reaches 1.5–3× its cluster's radius (`Nebula.java:36-37`). Diffuse
matter has no cell name and is not a body. A density reading counts **every cloud that reaches the point**, not the cloud of
the cluster the point is in: clouds are gathered over the super-cells around it widened by the farthest a cloud can reach
(`cloudsReaching`, `cloudReachSuperCells`), and overlapping clouds add, capped at the scale's 1 (`densityAmong`,
`NebulaField.java:204-230, 248`; maintainer ruling 2026-10-06: sum capped at 1, not max) `[T]`
(INV-UNI-17). `columnDensityBetween` gathers the clouds once for the whole line and integrates
along it (trapezoid, 1 ly step, ≤ 512 samples: `NebulaField.java:153-199, 36`) `[T]`
(INV-UNI-17), and `UniverseRegistry.extinctionBetween`
(`UniverseRegistry.java:1318`) converts density-light-years to magnitudes of visual extinction at 0.6 mag per density-ly
(`Nebula.java:60`) — the unit astronomy states dust in. Its only consumers are the telescope (obscuration, MECH-UNI-16) and the
sky feed. `[V]` `[T]` `NebulaTest.java:78` (a cloud reaches beyond its cluster), `NebulaTest.java:135, 189` (smooth falloff
to zero at the radius; no diffuse matter outside a galaxy).

### MECH-UNI-16 — The telescope: two stages, bounded by brightness, never by distance
**Detect** (`TelescopeScan.java:165`): the territory's anchors (`UniverseRegistry.anchorsInTerritory`, so a look is independent
of how finely the field is divided; more than 64 seats falls back to the one anchor at the look,
`ClusteredGalaxyGenerator.java:957-970`) are tested by apparent magnitude `m = M + 5·log10(d/10pc) + A`
(`StellarMagnitude.java:118`); the clear-sky test runs FIRST because extinction can only dim (`TelescopeScan.java:193-196`). A
starless system emits nothing and is never detected (with no observer, everything present registers:
`TelescopeScan.java:177-179`); an unstated temperature reads as Sol's; a black hole is dark (`StellarMagnitude.java:83-94`). A
detection carries whether it is `resolvable` (brighter than the limit by the resolve margin), decided where the aperture is
known (`TelescopeScan.java:119`). **Characterise** (`TelescopeScan.java:232`) pins the system, then writes the system's bodies
as `TELESCOPE`-tier entries only if the look is resolvable and not obscured; otherwise the bare address and primary kind —
**concealment costs detail, never the look** (`TelescopeScan.java:253-272`). A survey (`RegionScan`) is a resumable
deadline-and-cursor, never a counter (`RegionScan.java:47, 335`): either a **pointing** (`ConeWalk` — apex, direction,
half-angle, shell by shell so an aborted survey is a shorter cone, `ConeWalk.java:240`) whose reach is DERIVED from the
aperture against the brightest star the sky can produce (`StellarMagnitude.java:170`), or the local radar box
(`RegionScan.java:167`). Counts that will not fit an `int` cursor are **refused, not clamped**
(`ConeWalk.java:147-166`, `RegionScan.java:109`); a pointing too big for `maxCells` is **shortened by halving its depth**
(`RegionScan.java:540`) and refused only when its first shell does not fit. `InfoTier` / `PlanetInfoField` classify graded
discovery (global fields at `TELESCOPE`, terrain / biomes at `APPROACH`, resources / life at `ORBIT`; a higher tier shows a
superset: `PlanetInfoField.java:57`). `[V]` `[T]` `TelescopeRegionScanTest.java:304` (refused, not clamped),
`TelescopeRegionScanTest.java:335` (resumes after unload), `ConeWalkTest.java:158` (an aborted pointing is a shorter cone),
`TelescopeReachTest.java:50` (an over-fine aperture is shortened).

## State & persistence

One `WorldSavedData`, `stellurgy_universe` (`UniverseRegistry.java:59`), on the overworld's global map storage; **server-side
only**. The world seed is NOT persisted (re-derived from the overworld on load, `UniverseRegistry.java:1805-1811`). The key
spec is owned by [C1](../30-contracts/C1-nbt-persistence.md); here only the owner and the decision each answers:

| NBT key (root unless noted) | owner field | what a reader may conclude |
|---|---|---|
| `placements` `{starId, galacticCoord}` | `byStar` / `byCell` (`UniverseRegistry.java:80, 82`) | authored + fallback + pinned anchors; one coord per system, one system per cell (`UniverseRegistry.java:1348`) |
| `cellNames` `{dimId, starId, coord}` | `namesByDim` (`UniverseRegistry.java:102`) | durable names; valid only for the owning star id and inside its box |
| `pois` | `poiOverrides` (`UniverseRegistry.java:84`) | player/authored POIs, name + offset only; frame re-bound when served |
| `pinnedSystems` `{coord, starId, primaryKind?, temperature, size, name, bodies[]}` | `pinnedSystems` (`UniverseRegistry.java:90`) | the frozen drawn star + full body list; `primaryKind` written only when not `STAR` (`UniverseRegistry.java:2047-2049`); an entry whose placement was removed is dropped on write (`UniverseRegistry.java:2038-2041`) |
| `anchorsSeeded` | `anchorsSeeded` (`UniverseRegistry.java:104`) | authored anchors drain once |
| `schemaVersion` / `galaxyConfigFingerprint` / `universeLawsFingerprint` / `universeDerivationFingerprint` / `universeUpgradeArmed` | `UniverseRegistry.java:114-152` | MECH-UNI-14 |
| `version` | `NBT_VERSION = 4` (`UniverseRegistry.java:63`) | the TAG LAYOUT only; **written, never read** (`UniverseRegistry.java:1935` has no `version` branch) — consistent with the 0.1.0 clean break, no migration |

`SystemBody`, `CellFrame` and `BodyEphemeris` have symmetric write/read (`SystemBody.java:354, 372`, `CellFrame.java:92, 119`,
`BodyEphemeris.java:183, 198`); a missing `frame` or `ephemeris` reads as a STATIC one (`CellFrame.java:119-122`), the honest
default for a void cell. `RegionScan` and `ConeWalk` persist inside the observatory tile (`RegionScan.java:347`,
`ConeWalk.java:290`) as a same-version save contract. **Transient, re-derived per load**: the super-cell indices
(`UniverseRegistry.java:161-165`), `declaredAnchors` (`UniverseRegistry.java:240`), and the generator, star lookup, active
schema, report memory and schema catalogue (`UniverseRegistry.java:173-181, 209`) — fields of THIS save's registry, so a second
server in one JVM never sees another's model.

## Invariants

- **INV-UNI-01 — at most one REAL body per cell** `[V]` (locked 2026-07-28, [universe-model](../universe-model.md) §4, C15 ADDR-4). Every real body, a moon included, owns its own cell; a zone-qualified key never equals a galactic one, so moons are compared against siblings of their zone only. The generator enforces it by claiming cells (`ClusteredGalaxyGenerator.java:437, 721`); for authored systems it is only AUDITED and reported, never repaired (`SystemContent.java:397-420`). `[V]` `[T]` `SystemRetinueTest.java:155` (procedural; moons skipped there).
- **INV-UNI-02 — every procedural answer is a pure function of (seed, config, schema).** `[V]` No `Random`, no clock, no registry read inside the generator or `PlanetDerivation`; a body's profile is keyed on its CELL, not its list index, so it survives a pin. `[V]` `PlanetDerivation.java:312-320` `[T]` `ClusteredGalaxyGeneratorTest.java:179, 1084`. **Qualification**: `PlanetTypes.drawTerrain` consults the running modset (`PlanetTypes.java:196`), so the *terrain* leg holds only for a fixed modset (Open questions).
- **INV-UNI-03 — a name never depends on time; where a body IS does.** `[V]` A cell name is derived at `NAME_TICK = 0`, then recorded; position is a law evaluated at a stated tick, never stored as an absolute. `[V]` `SystemContent.java:97`, `SystemBody.java:268-279` `[T]` `UniverseRegistryTest.java:138`, `SystemContentTest.java:391`. C15 ADDR-1, ADDR-2, ADDR-12.
- **INV-UNI-04 — a body's name lies inside its system's neighbourhood, and every body attributes back to its anchor.** `[V]` Authored names are clamped into `min(s/2, SEAT_MARGIN) − 2` cells of the anchor (`SystemContent.java:474-478, 495-502`); procedural names into the seat's own lattice cell (`ClusteredGalaxyGenerator.java:1007-1025`); a recorded name outside its box is re-derived. `[V]` `[T]` `ClusteredGalaxyGeneratorTest.java:636`, `UniverseRegistryTest.java:612`.
- **INV-UNI-05 — a point is inside at most one galaxy, and a galaxy never leaves its cube.** `[V]` Satellites are ≥ 1 primary diameter out and ≤ 0.3 R across; the seat margin is the whole retinue's reach, not the primary's radius; the drift is clamped. `[V]` `GalaxyField.java:196-214, 662-684` `[T]` `GalaxyFieldTest.java:503, 709`.
- **INV-UNI-06 — nothing procedural stands inside an authored neighbourhood; a pin clears nothing.** `[V]` One predicate (`UniverseRegistry.java:408`) serves attribution, survey territory and region. `[V]` `[T]` `AuthoredNeighbourhoodTest.java:85, 153`. C15 ADDR-23. (ADDR-25's pinned-box clause is KNOWN OPEN — Open questions.)
- **INV-UNI-07 — a touched system is frozen, a realized body is never un-realized, realization writes only into a pin.** `[V]` `realizeBody` refuses an unpinned system; a second descent into the same cell reuses the world. `[V]` `UniverseRegistry.java:1056-1064`, `PlanetRealizer.java:98-101` `[T]` `PlanetRealizationTest.java:373, 343`.
- **INV-UNI-08 — a stamped save is never opened under a different universe.** `[V]` An unknown schema version, a changed `<galaxyGen>` fingerprint (unless armed), an edited released metric, an edited released derivation: each throws `UniverseSchemaMismatchException` and aborts the load. `[V]` `UniverseRegistry.java:1657-1732` `[T]` `UniverseRegistryTest.java:830, 853, 1031, 1080` (the derivation refusal has no test).
- **INV-UNI-09 — a body cannot be built with an offset outside its own cell.** `[V]` Refused at construction, never clamped. `[V]` `SystemBody.java:154-173` `[T]` `SystemBodyTest.java:233`.
- **INV-UNI-10 — an unwalkable survey is refused, not clamped; a pointing that merely exceeds the look budget is shortened.** `[V]` `ConeWalk.java:147-166`, `RegionScan.java:109-118, 540-566` `[T]` `TelescopeRegionScanTest.java:304`, `TelescopeReachTest.java:50`.
- **INV-UNI-11 — an observation freezes only what it reported.** `[V]` A look into the void pins nothing; every characterised system is pinned first so the crystal and the sky cannot disagree. `[V]` `TelescopeScan.java:253` `[T]` `TelescopeRegionScanTest.java:624, 571`.
- **INV-UNI-12 — the package holds no mutable static state.** `[V]` Every static field is `static final` and either a primitive/`String` constant, an immutable value (`BodyDerivationV0.java:23`, `UniverseLawsV0.java:18`) or a logger; the companion-count distribution is the private enum `CompanionCount` whose weights are final fields, not an array any holder of the reference could rewrite (`ClusteredGalaxyGenerator.java:72-90`; maintainer ruling 2026-10-06, "immutable form"). The only outside dependency is a read of `StellurgyConfiguration.getCurrentConfig()` (`TelescopeScan.java:374-398`, `RegionScan.java:459`). `[V]` by grep of `static` declarations without `final` across `universe/*.java` (zero hits, 2026-10-06).
- **INV-UNI-13 — diffuse matter is not a body.** `[V]` A nebula has no cell name, is never in `bodiesAt`, and may overlap anything. `[V]` `Nebula.java:24-26`, `UniverseRegistry.java:635-648` (bodies only).
- **INV-UNI-14** [T][BEH] A companion star is drawn where its body stands: the star carries the angle of the seat its body ends up in, a first cell already taken included (`seatCompanions`, `ClusteredGalaxyGenerator.java:1508-1518`; MECH-UNI-05). Pinned by `ACompanionIsDrawnWhereItsBodyStandsTest#everyCompanionStarCarriesTheAngleItsBodyStandsAt`.
- **INV-UNI-15** [T][BEH] The moon the sky shows is the moon landed on: a procedural moon's mass and radius are drawn under the key a descent realizes it with, its own cell and its variant in that cell, so `UniverseRegistry#derivedProfileOf` answers the same bulk (`ClusteredGalaxyGenerator.java:869-870, 886-894`; MECH-UNI-06, MECH-UNI-11). Pinned by `TheMoonInTheSkyIsTheMoonLandedOnTest#aMoonsSizeInTheSkyIsTheSizeOfTheWorldRealizedForIt`.
- **INV-UNI-16** [T][BEH] Two moons named by the same zone-local cell of two different planets' zones are two worlds: a zoned cell's draw folds its zone into the seed, so a small triple counted in every zone of the galaxy does not hand every such moon one mass and radius (`CellHash.java:53-64`). Pinned by `PlanetDerivationTest#theSameZoneCellOfTwoPlanetsNamesTwoDifferentMoons`.
- **INV-UNI-17** [T][BEH] A cloud is read wherever it reaches, past its own cluster's ball: a density reading and a sight line's column both gather every cloud that can reach them (`cloudsReaching`, `NebulaField.java:177-183, 204-222`; MECH-UNI-15). Pinned by `NebulaReachesPastItsClusterTest#aPointOutsideTheClusterButInsideItsCloudHasMatter`, `…#aLineThroughACloudOutsideItsClusterCrossesMatter`.
- **INV-UNI-18** [T][BEH] A galaxy table with no type large enough to hold authored content promises authored content no more reach than its smallest type holds, `(1 − HOME_GALAXY_ORIGIN_FRACTION)` of that type's smallest radius, so an anchor past it is reported to the pack author at load (`GalaxyField.java:713-722`, `UniverseRegistry.java:1438-1445`). Pinned by `AuthoredReachGuaranteeTest#aTableOfDwarfsIsPromisedNoMoreThanItsSmallestDwarfHolds`.
- **INV-UNI-19** [T][SYS] A survey of a sky whose generator states no star table is ranged against the stock table, and its tuning says so (`reachAssumesStockSky`, `RegionScan.java:484-505, 517-519`); a sky that states its own table is not marked. FOR: MECH-UNI-16, a pointing's reach is derived from the brightest star the sky can produce. Pinned by `ASurveyReachSaysWhoseSkyItWasMeasuredAgainstTest#aSkyWithNoStarTableIsSurveyedAgainstTheStockOneAndSaysSo`.
- **INV-UNI-20** [T][BEH] A gas giant becomes a place — properties of its own — when a craft is recorded in its zone: every recording of a craft in a cell tells the ledger's observer, which hands the body whose zone the address names to the realizer (`ShipLedger.java:95-97`, `SpaceSubsystem.java:550-557`, `PlanetRealizer.java:205-211`; MECH-UNI-13). Pinned by `ProceduralPlanetRealizationTest#aGasGiantBecomesAPlaceWhenACraftIsRecordedInItsZone`.

## Failure modes & edge cases

- **A fallback that announces itself**: a moon whose parent has no mass shares its parent's cell and logs once per parent (`SystemContent.java:359-367`); `coordForPlanet` returns EMPTY (and logs once) for a body its system cannot account for, never the anchor (`UniverseRegistry.java:1245-1259`); a recorded name that cannot be served is reported and re-derived (`UniverseRegistry.java:830-846`); no admitting preset → `unclassified`, reported (`PlanetTypes.java:157-168`).
- **Two fallbacks that now announce themselves**: a galaxy table with nothing large enough to hold authored content draws such a galaxy from the whole table (maintainer ruling 2026-10-06: announced, never refused), logs an ERROR once per generator naming the reach it can still promise, and `guaranteedAuthoredReachLy` shrinks to what the table's smallest type holds instead of vouching for the shipped figure (`GalaxyField.java:700-722`; `ClusteredGalaxyGenerator.java:265-270`) `[T]` (INV-UNI-18); `RegionScan.Tuning.fromConfig` falls back to the stock star table when the generator states none, WARNs once, and the tuning says so (`reachAssumesStockSky`, `RegionScan.java:485-518`) `[T]` (INV-UNI-19).
- **A fallback that does not**: `luminositySuns` reads an unstated temperature as Sol's (`StellarMagnitude.java:92-94`); `drawTerrain` falls back to native generation for a null preset without a report (`PlanetTypes.java:190-192`).
- **Bounded work, not bounded fiction**: region queries cap at 20 000 systems and LOG the cap (`ClusteredGalaxyGenerator.java:205, 386-390`); retinue ≤ 24, nebula column ≤ 512 samples, ≤ 96 seat retries, ≤ 10⁶ shells (`ConeWalk.java:51`) — all stated as bounds on WORK.
- **Per-query cost**: `allSystemBodies` runs on every registry query; `ReportOnce` exists because an unguarded report floods the log (28 061 clamp warnings in one playtest: `ReportOnce.java:10-14`).
- **A zoned cell cannot be placed without its lattice width** (`AbsolutePos.ofCellName` throws); hence `addPoisOf` resolves a frame only when a POI exists (`UniverseRegistry.java:666-675`) and `zoneLatticeBlocks` READS the width from bodies' names rather than recomputing it (`UniverseRegistry.java:733-745`).
- **Starless primaries cannot descend yet** (`SystemBodyKind.java:42-48`); `materializeVariant` also refuses a system with no star (`PlanetRealizer.java:200-204`).
- **No server / no registry**: `UniverseRegistry.get` returns `null`; `bodiesAtOnServer` returns an empty list when Forge is not bootstrapped (`UniverseRegistry.java:885-900`) — the caller's own fallback (the flat ring) is the contract.

## Integration seams

- **Boot**: `Stellurgy#serverStarted` → `UniverseRegistry.populate` (`Stellurgy.java:1715`); `DimensionManager` stages the pack's `<galaxyGen>` and authored anchors and owns the `ReportOnce` (`dimension/DimensionManager.java:94`; `dimension/DimensionManager.java:586` forgets a deleted dimension's name).
- **XML**: `XMLPlanetLoader` reads and writes `<galaxyGen>` (`util/XMLPlanetLoader.java:68, 335, 2085, 2150` — the last folds declared galaxies into the config).
- **Zone entry**: `space/SpaceSubsystem.java:552` (the ledger's settle observer) calls `PlanetRealizer.realizeOnZoneEntry`.
- **Descent**: `tile/TileAdvancedFlightComputer.java:633` calls `PlanetRealizer.realize`; `space/ShipEntryController.java:83, 378` read `bodiesAtOnServer`.
- **Survey**: `tile/multiblock/TileObservatory.java:1156` → `TelescopeScan.resolveBatch`; crystals are `navigation/` types (`CrystalEntry`, `ItemMemoryCrystal`).
- **Feeds**: `space/SystemBodiesProducer` and `space/SkyNebulaeProducer` read `skyBodiesAt` / `nebulaeAround` (C14 CON-C14-06..CON-C14-11 own the wire); `IGalaxyGenerator.nebulaeAround` / `columnDensityBetween` default to empty / zero, the correct answer for a generator without clouds.
- **Addon seam**: `UniverseRegistry.attachGenerator` accepts any `IGalaxyGenerator` (replacing is legitimate up to three times per load; `null` is refused; `detachGenerator` is the explicit void; `UniverseRegistry.java:1853-1889`). `setStarLookup` lets a test supply fabricated stars (`UniverseRegistry.java:1895`).
- **Commands**: `command/sub/universe/UniverseUpgradeCommand` and `UniverseStatusCommand` use the stamp API (`adoptSchema`, `armUpgrade`, `isUpgradeArmed`).
- **Hyperdrive**: `DriveTier` reads the metric; the drive-band constants price a machine and are deliberately NOT part of a world model (`UniverseSchema.java:20-24`).

## Config surface

- **`<galaxyGen>` (planetDefs.xml)** → MECH-UNI-02..05; its digest is the world's stamp, so a retune after the world exists is a refusal, not a tweak. `procedural="false"` → `EmptyGalaxyGenerator` and the fingerprint `none` (`GalaxyGenConfig.java:302, 353`); NO `<galaxyGen>` at all is the shipped `defaults()` configuration, not the void (`GalaxyGenConfig.java:408`). The cluster table, the nucleus and `CLUSTER_*` are fingerprinted but not authorable (`GalaxyGenConfig.java:273, 538-551`). Full-disable path of procedural generation: `procedural="false"`.
- **`StellurgyConfiguration` telescope keys** (`telescopeLimitingMagnitude`, `ConeHalfAngleDegrees`, `ResolveMarginMagnitudes`, `ScanMaxCells`, `ScanBaseTicks`, `ScanCellsPerStep`, `ObscuredAtMagnitudes`: `api/StellurgyConfiguration.java:462-476`) → MECH-UNI-16. Balance, never contract, and in no fingerprint. `telescopeObscuredAtMagnitudes ≤ 0` turns concealment off (`TelescopeScan.java:423-426`).
- **`<planetType>` elements** override the stock type table wholesale (`PlanetTypes.java:80`); not fingerprinted (Open questions).
- **System property `stellurgy.universe.corpus.write=true`** regenerates the golden corpus (`ClusteredGalaxyGeneratorTest.java:1087`).

## Test coverage

| invariant / mechanic | test |
|---|---|
| INV-UNI-02, MECH-UNI-01 | `ClusteredGalaxyGeneratorTest.java:179, 316, 1084` (316: region agrees with point query; 1084: golden corpus) |
| INV-UNI-01 | `SystemRetinueTest.java:155, 180, 232` (180: cramped neighbourhood; 232: a cramped system drops bodies, never moves the ones it keeps) |
| INV-UNI-03/04, MECH-UNI-10 | `UniverseRegistryTest.java:138, 550, 612, 650`; `SystemContentTest.java:391`; `ClusteredGalaxyGeneratorTest.java:636` |
| INV-UNI-05, MECH-UNI-03 | `GalaxyFieldTest.java:83, 503, 635, 709, 806` (83: home galaxy under every seed; 806: a declared galaxy is seated) |
| INV-UNI-06, MECH-UNI-09 | `AuthoredNeighbourhoodTest.java:85, 153`; `UniverseRegistryTest.java:277, 682, 715, 757`; `StarClusterTest.java:293` |
| INV-UNI-07/08, MECH-UNI-11/13/14 | `UniverseRegistryTest.java:332, 830, 853, 917, 1031, 1080`; `PlanetRealizationTest.java:154, 343, 373`; `ProceduralPlanetRealizationTest.java:77, 175` |
| INV-UNI-09, MECH-UNI-08 | `SystemBodyTest.java:54, 117, 233` |
| INV-UNI-10/11, MECH-UNI-16 | `TelescopeRegionScanTest.java:304, 335, 571, 624`; `ConeWalkTest.java:85, 158`; `TelescopeReachTest.java:50`; `StellarMagnitudeTest.java:149, 173, 200` |
| MECH-UNI-12 | `PlanetDerivationTest.java:229, 263, 660, 681` |
| MECH-UNI-15, INV-UNI-17 | `NebulaTest.java:78, 135, 172, 189`; `NebulaReachesPastItsClusterTest` (the field reads a cloud past its cluster ball, point and column) |
| MECH-UNI-01 (zone seed), MECH-UNI-05/06, INV-UNI-14/15/16 | the clauses name their methods (`TheMoonInTheSkyIsTheMoonLandedOnTest` is integration: sky bulk = realized bulk) |
| Failure modes (announced fallbacks), INV-UNI-18/19 | the clauses name their methods |
| **gaps** | the `universeDerivationFingerprint` refusal (no test names it) |

## Open questions

Leads only (reported to the caller; no `FIND-` filed from this pass).

5. **Pinned neighbours misattribute (named in C15 ADDR-25).** The stored-anchor index holds one anchor per super-cell (`UniverseRegistry.java:511-533`) while a territory holds up to `k³ = 27` seats under the shipped abundance, so pinning two seats of one territory attributes the second system's *other* cells to the first. The warning it prints tells the author to "fix the XML anchors" even when the colliding anchors are pins (`UniverseRegistry.java:524-526`).
7. **Terrain is not a pure function of the seed — and is not promised to be.** `PlanetTypes.drawTerrain` filters by the live modset (`PlanetTypes.java:189-207`) and is not part of `derivationFingerprintOf`, so removing a mod changes which terrain a never-realized body will get; realized worlds are fixed (`PlanetRealizer.java:337`). `<planetType>` edits are likewise outside every fingerprint. **Contract: nothing about a body's terrain is promised before it is landed on**; the realization is what fixes it. Not established: whether a graded-discovery reading (`PlanetInfoField` terrain at `APPROACH`, MECH-UNI-16) shows terrain before a landing, which this contract would make a reading of a draw rather than of the world.
9. **Stale citations in neighbouring docs** (recorded here because this doc now owns the source root): C15 ADDR-23 / ADDR-24 cite `UniverseRegistry#clearOfAuthored:398`, `anchorForCell:309`, `anchorsInTerritory:544`, `systemsInRegion:581`, `authoredBySuperIndex:449`, `neighbourhoodReach:377`, `storedAnchorNear:347`, `withDerivedRetinue:2024` and `IGalaxyGenerator#neighbourhoodOf:131` — now `UniverseRegistry.java:408, 319, 554, 591, 459, 387, 357, 2135` and `IGalaxyGenerator.java:133`; `universe-model.md` §7.1 cites lines 158-164, 1534-1570 and 167-190 of `UniverseRegistry.java` for the instance-field model, the populate pairing and `bindReports` — they are now at `UniverseRegistry.java:173-181` (fields), `UniverseRegistry.java:1800-1834` (populate) and `UniverseRegistry.java:183-206` (bind); and its "two refusals" count is four in the code (`UniverseRegistry.java:1674, 1688, 1702, 1722`). C15 ADDR-3 states the box as `minSpacing/2 − BOX_MARGIN_CELLS`, but the clamp is `min(s/2, SEAT_MARGIN_CELLS) − 2` (`SystemContent.java:474-478`) while attribution uses the un-reduced `s/2` (`UniverseRegistry.java:387-389`); `00-index.md:15` counts `universe/` as 45 files / 12 568 loc (now 47 / 13 104).
