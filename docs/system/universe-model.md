# Universe model — galaxy, systems, discovery

**Status:** a decision ledger and model description, NOT a code-mapping subsystem doc (it carries no
`MECH-` / `INV-` anchors beyond the two it names). The Layer-1 registry, generator and schema are built;
each section states which parts are built and which are design only. Balance numbers are `tunable`,
never pinned.

> **TWO-LAYER BOUNDARY — read first.** This doc is **Layer 1 (Universe)** only: the LOGICAL / DATA model
> — a registry of what exists and where, systems, planets-as-POIs, discovery. It DECLARES content.
> **Layer 2 (Space realization)** — bubbles, the slot pool, transit hosting, the physical loading of a
> location as a walkable world — is [`space-model.md`](./space-model.md); it REALIZES what the universe
> declares. Several decisions made here are OWNED by Layer 2 / infrastructure (flagged per section): the
> entry pathfinder and materialize (§5), the crossing mechanism (§6), the dirty-hook (§7), the scan tech
> and nav-computer block (§8), the pool policy (§9). This doc records the MODEL those pieces implement.

---

## 1. Two-layer model

- **Layer 1 — Universe (logical / data), THIS doc.** A registry `GalacticCoord → StarSystem`; a system →
  its planets / bodies (XML / `DimensionProperties`); every body addressed by
  `(GalacticCoord, in-system position)`. **No worlds are loaded** — the layer answers only "what exists
  and where".
- **Layer 2 — Space realization (physical), `space-model.md`.** When a ship arrives, the bubble /
  slot-pool / hyperspace machinery loads that location as a walkable world.

The relationship is **DECLARE (universe) → REALIZE (ship)**. The "bubble / cell / slot-pool" vocabulary
belongs to Layer 2; this doc references `space-model.md` and never redefines it.

## 2. Cosmology and registry

`dev.stannismod.stellurgy.universe`: `UniverseRegistry` (a `WorldSavedData`: cell-keyed
`GalacticCoord ↔ star-id` two-way index + sparse NBT override store + the `IGalaxyGenerator` seam +
`StarSystem` wrapper). Anchors are declared via `<star galacticCoord="sx,sy,sz">`. The legacy
`StellarBody` / `DimensionManager.starList` catalogue, its NBT / XML and `PacketStellarInfo` keep their
int star-id identity; the registry attaches the coordinate.

**Cell = BODY ZONE, not the whole system.** A system is an **anchored NEIGHBOURHOOD of cells**: the star
sits in the system's anchor cell (the registry's placement coord); every planet / belt gets its **own
cell** at a sector offset from the anchor (the body's orbital offset in chart blocks, one distance unit =
100 km = `SystemContent.ORBIT_UNIT_BLOCKS`, C15 ADDR-20). A moon is named
by a cell of its own inside its parent's ZONE (C15 ADDR-17). Inter-body distances are CELLS of void, so
sub-light interplanetary flight is **UNREASONABLE, NOT IMPOSSIBLE** (maintainer 2026-08-15: it stays
possible and costs what it costs) and the hyperjump is the only SENSIBLE travel mechanic. The BODY itself
is never physical in a cell: a cell hosts only the orbital ZONE (proximity radius, stations, ships; the
body = POI + client render — Y 0..256 / block space is irrelevant to celestial size). `coordForPlanet`
returns the body's OWN cell. Generator `minSpacing` must exceed 2× the max neighbourhood radius.
`SystemContent` places each body in its own cell. Sub-cell bubble quantization is rejected
(`space-model.md` §10).

**The moon band is a FRACTION OF THE CELL RADIUS, and no invariant rests on ship speed.** Free flight is
bounded by ACCELERATION, not by an absolute speed (C10 STAT-1/2: acceleration is thrust ÷ mass, both
derived from construction), so a distance in blocks is judged by flight time — with a turnover burn,
`t = 2·√(d/a)`. A band expressed as a fraction of `HALF_CELL` scales with the cell and cannot silently
encode an assumed speed. The exact fraction is `tunable`.

**What a jump may TARGET. The only ban is INTRA-CELL.** From one cell a ship may aim at any body in
another cell, and more generally at any signal source or known point in the universe. The `sameCell`
refusal (`msg.jumpgate.alreadythere`) is the whole of the restriction; everything inside a cell is
ordinary flight. `JumpGate` does not block a body-less target: `targetResolved()` defaults to `true`, so a
hand-typed coordinate needs no prediction and never trips `MSG_TARGET_LOST`
(`navigation/JumpGate.java:100-106`) `[V]`. The navigation computer's INTERFACE offers a flat list of
addresses, which is awkward once points of interest multiply; an interactive map supersedes it (not
built). `INV-UNI-01` (one REAL body per cell) bounds what a cell CONTAINS, not what may be AIMED at. A
moon is aimable because it has a cell of its own.

**What the CELL SKY must communicate.** The body is "POI + client render", and the descent asks the
pilot to judge a DISTANCE (the trigger needs him inside the descent shell), so: there is no fixed-radius
band around the camera (it is stationary relative to the pilot and marks nothing while looking like a
line); a body's apparent SIZE falls off with range so that closing on one is legible by eye; a descent
cue, if drawn, belongs to the TARGET BODY (a halo that grows on approach), never to a line around the
player. The hyperspace world shares `WorldProviderSpaceSlot` and therefore this sky, so it carries no
false descent cue either. `[A]` The sky's contract is C14.

**Placement and attribution.** Honest raw-distance scaling (a body's orbit in distance units times
`ORBIT_UNIT_BLOCKS`), guarded by a FIXED large `minSpacing` default (`UniverseScale.DEFAULT_SPACING_CELLS`, derived from the mean star separation and the cell size, never from the catalogue: deriving would
re-partition the super-cells and relocate the procedural galaxy on any XML edit) plus a load-time
clamp / WARN on any body whose cell offset exceeds `minSpacing/2 − margin` (the orbit field is a `long`
with no upper cap in the reader, `XMLPlanetLoader`). Member-cell attribution = **derive + pin-on-touch**:
untouched space is a pure function (super-cell → anchor; a transient authored-anchor map rebuilt at
load); on FIRST TOUCH (visit / POI / claim / modify, **and a telescope resolving it — a look is a
touch**) the system's placement is PINNED into the sparse override store — visited systems are immune to
config / seed / XML edits, and NBT grows only with the visited set. The read API splits: `bodiesAt(cell)`
= ZONE-local; `systemBodiesAt(anyMemberCell)` resolves the anchor (system-semantics reads, including
`isSystemKnown`, resolve member→anchor first; POIs stay keyed by their own cell). Body addresses use
full 3D offsets (inclination real). The Y realization is honest-3D and centred, `world Y = local Y` on
every axis (`CellWorldMapper`; `space-model.md` §10); the only guard it needs is vanilla's void kill
below Y −64, suppressed in cell slot worlds only (`MixinEntityCellVoid`). A ship saved in a cell is not
re-seated by any Y shift on load, because there is none.

**UNIVERSE-layer (owned here).** The single owner of placement is **`UniverseRegistry`** holding a
**two-way `GalacticCoord ↔ StarSystem` map**:
- **A system is LOCATION-AGNOSTIC** — it does not store its own coordinate. The registry is the sole
  index; a reverse lookup (a ship at a planet dim → its `GalacticCoord`) is answered by the registry,
  never by the system object (storing the coordinate on the body / system is rejected, §10).
- **Procedural-with-overrides.** `coord → system` is a deterministic function of `(seed, coord)` for
  procedural systems, PLUS a **sparse override store** for authored (XML) and player-modified systems. A
  body's address resolves `GalacticCoord → system → body by in-system id / position`.
- **Reuse.** The existing `StellarBody` (a star system = one star + `HashMap<Integer,DimensionProperties>
  planets` + `subStars`; moons are child `DimensionProperties`) and `XMLPlanetLoader →
  DimensionProperties → WorldProviderPlanet` are folded in; `DimensionManager.starList` is bridged, not
  extended in place.
- **No explicit galaxy tier in the ADDRESS.** Cells index systems directly; a galaxy is a seated object
  derived from the cell (§3), not an address level.
- **Addressing rule.** Star-id and DIMID share a namespace via `STAR_ID_OFFSET=10000`, so systems MUST be
  addressed by `GalacticCoord`, never by the small int star-id.

`GalacticCoord` (the sectorized fixed-point value type) lives in Layer 2 — `space-model.md` §2.

## 3. Galaxy generation

`ClusteredGalaxyGenerator` is a pure function of `(seed, cell)`: a super-cell partition for minimum
spacing plus seated galaxies, clusters and nebulae; O(1) per query. `GalaxyGenConfig` (`density`,
`minSpacing`, `galaxySpacing`, `galaxyDensity`, star / galaxy / cluster / rogue type tables, all `tunable`)
is read and written back by the `<galaxyGen>` XML element. Procedural systems are bare stars with a
synthetic NEGATIVE id (a durable save key); planet CONTENT is §4.

**The procedural galaxy is ON by default.** A pack with no `<galaxyGen>`, and a server with no
`planetDefs.xml`, is given `GalaxyGenConfig.defaults()`. An authored-anchors-only universe is the pack's
explicit `<galaxyGen procedural="false"/>` (`GalaxyGenConfig#procedural`, read by
`XMLPlanetLoader#readGalaxyGen`; any other knob beside it, or a value other than `true` / `false`, is a
load error); `UniverseSchemaV0#generator` answers it with `EmptyGalaxyGenerator` — the pack states the
property, the version picks the class. A `null` configuration is not a meaning anywhere: the schema and
`UniverseRegistry#fingerprintOf` refuse it, and `writeXML` writes the opt-out back. Pinned:
`integration/XMLPlanetLoaderTest`, `unit/UniverseSchemaGeneratorChoiceTest`,
`server/PlanetXmlConfigIntegrationTest`, `server/TheShippedGalaxyTest`. `[T]`

**Galaxies are SEATED OBJECTS.** `[V]`
- **`GalaxyField`** partitions space into `galaxySpacing`-cubes (default 2 956 478 272 682 cells = 2.5
  Mly = 25 galaxy diameters), at most one galaxy each, occupied with probability `galaxyDensity` (default
  0.5). **`Galaxy`** is a VALUE — centre, type, radius, orientation, arm pitch and phase — produced from
  `hash(seed, gx, gy, gz)` and stored nowhere, exactly as `StarSystem` is. The blob field of an earlier
  design does not exist; `clusterScale` / `voidFraction` are not parameters.
- **Radius is drawn CONDITIONAL ON TYPE** (`GalaxyGenConfig.GalaxyType`, the analogue of `StarType`):
  dwarf spheroidal / dwarf irregular / spiral / barred spiral / elliptical, weighted 700/290/7/2/1 so
  dwarfs outnumber giants by two orders. The type bands are ABSOLUTE light years so they can be checked
  against a catalogue — dSph 500–3 000, dIrr 2 000–10 000, spiral 15 000–60 000, barred 20 000–75 000,
  elliptical 30 000–150 000 ly — and the reference radius does not derive them; a test pins that the
  reference is a size an ordinary spiral is.
- **Occupancy is decided in the galaxy's own frame:** a super-cell hosts a system with probability
  `density × profile(r, θ, z)` — an exponential disc × an exponential in height, modulated by
  logarithmic-spiral arms, plus a bulge, or one isotropic exponential for a spheroid — evaluated at the
  super-cell CENTRE and at `t = 0` only. Outside every galaxy the profile is zero, so **the intergalactic
  void is what the profile leaves empty, not a second rule**. The profile is normalised at the SUN-LIKE
  radius, not at the nucleus (the mean star separation is the primary quantity of the layer and it is
  real), so `density` means "how full a sky like ours is"; the centre exceeds 1 and is clamped where the
  probability is used.
- **Inside / outside is the DECLARED RADIUS** (a SPHERE, so a disc galaxy's halo is inside its galaxy),
  never a level of the profile: a continuous profile has no boundary, and the frame a thing rides may
  not flap tick to tick.
- **Rotation is analytic:** `θ(t) = θ₀ + ω(r)·t`, `ω(r) = v∞/√(r² + r_core²)` — solid-body in the core,
  flat outside; the type's `coreRadiusFraction` makes a dwarf shear less than a massive spiral. A turn at
  half-radius of the stock spiral is ~7.5·10¹² ticks, invisible within a save, and rotation is not wired
  into the frame chain.
- **The galaxy index is DERIVED, never a stored tier:** `GalaxyField.galaxyIndex(sector, spacing)`; no
  coordinate gains a field. The lattice is offset by half a cell so the ORIGIN is a cell CENTRE (with a
  corner convention every negative sector would fall in a neighbouring cell).
- **Galaxy cell (0,0,0) is RESERVED:** the home galaxy always exists, drawn only among types whose
  `minRadius` clears `MIN_AUTHORED_GALAXY_RADIUS_LY = 15 000` (the qualifying set is "the spirals and the
  ellipticals", pinned as a SET). It is seated AROUND the origin, not on it: the origin lands at
  `HOME_GALAXY_ORIGIN_FRACTION = 0.55` of the radius, in the plane, and its DECLARATION ORIGIN is the
  universe origin. Guaranteed authored reach is `(1 − 0.55) × MIN_AUTHORED_GALAXY_RADIUS_LY = 6 750 ly`,
  derived rather than chosen.
- **The cosmic web is a SLOT:** `GalaxyField.webDensity` is the constant 1, the one place
  non-uniformity will live.

**Real scale** (`UniverseScale`). `REFERENCE_GALAXY_RADIUS_LY = 50 000` (a galaxy is 100 000 ly across;
the cube that holds one is 2.5 Mly = 2.96·10¹² cells). `[V]`
- **Nothing stores a position as a block `long`.** A position is a cell NAME (a sector triple) plus an
  in-cell offset; out in the void a name plus a light-year vector. The cube's diagonal is 5.1·10¹² cells
  against the sector space's 9.2·10¹⁸ — 1.8·10⁶× of headroom `[T]`.
- **The one place a separation IS three block `long`s is `BlockDelta`** (reaching ~244 000 ly); it
  reports `isSaturated()`, and `AbsolutePos.minus` decides value and flag from one predicate so they
  cannot disagree. A distance at any magnitude comes from `AbsolutePos.distanceTo`, computed from the
  sector delta, never clamped.
- **Size, separation and population are ONE fact.** `MEAN_STAR_SEPARATION_LY` (4.23) is real; πR²h at
  h ≈ 1 000 ly over ~76 ly³ per seat gives 10¹¹ systems, and a reference spiral measures ~8.7·10¹⁰
  through the shipped profile. `scaleHeightRatio` is a FRACTION of the radius (a spiral's 0.02 is 1 000
  ly at 50 000 ly). `[T]`
- **A survey's look count is REFUSED, not clamped,** when it will not fit an `int` (`RegionScan` computes
  it once at construction; `TileObservatory` turns the refusal into "the machine did not start" plus a
  log line naming the setting).
- **Nothing enumerates a galaxy:** a survey of 27 looks asks the generator 39 questions. `[T]`
- **A telescope's reach does not scale with the galaxy radius** — it is derived from the aperture (§8).
  `PacketSystemBodiesSync`'s per-body `localX` stays near-field.

**The galaxy layer:**
- **Expansion and peculiar motion.** `Cosmology.scaleFactorAt(t) = exp(H₀·t)` with the REAL Hubble
  constant, `a(0) = 1` (the universe's age IS the save's age), monotone. `C(t) = a(t)·(C₀ + v·t)` applies
  to the galaxy CENTRE and to nothing inside it. Each galaxy draws a comoving velocity from 50–600 km/s,
  clamped so it cannot leave its own cell within `DRIFT_HORIZON_TICKS` (measured: the clamp sits at
  4.7·10⁻⁵ of the available room, four orders from binding). The HOME galaxy alone has zero peculiar
  velocity: it is the rest frame everything else moves against. The clock is the ORBITAL calendar
  (`TICKS_PER_YEAR`), not real-time seconds (the two differ by 548×). `[V]`
- **The intergalactic regime.** `GalacticFrame {GALACTIC, COMOVING}` and `GalaxyField.frameAt` /
  `positionAt`. Bound to a galaxy: an offset from its CENTRE, turning at `ω(r)`, not expanding. Out in
  the void: an offset from the galaxy CELL's origin, comoving with `a(t)`, not rotating. Every point is
  in exactly one galaxy CELL. The frame is LATCHED at a crossing and never re-derived per tick; the
  query is Layer 1's, the latching is the craft's. `[A]`
- **Clusters with a COMMENSURATE sub-lattice.** `StarCluster` + `ClusterField`: each coarse super-cell
  inside a cluster is divided into `k³` sub-cells whose bounds are PROPORTIONED (`floor(i·s/k)`), so they
  tile a coarse cell of any edge exactly. Membership is a property of the COARSE cell, so ownership is
  O(1) with one answer. The separation floor follows the LOCAL lattice level. Seated types: open (`k=4`),
  globular (`k=14`) and a NUCLEUS at every galaxy's own centre. A nucleus's contrast is relative to its
  own galaxy's POPULATION while every other cluster's is relative to the FIELD, so it is derived
  `k = k_ref · R / R_ref` with `k_ref = 215` (at 10¹¹ systems, 10⁷× the field over a few light years is a
  real nuclear star cluster), `StarCluster` taking its subdivision explicitly; a dwarf gets `k = 4`.
  Star separation is primary and real; the galaxy is compressed to it; the contrast follows that choice.
  A cluster may not refine below `MIN_LATTICE_EDGE_CELLS`. `systemsInRegion` walks only the sub-cells the
  query box reaches. `[V]`
- **Authored content is declared GALAXY-LOCAL.** `GalaxyKey` (`home` or `gx,gy,gz`) + `GalacticAnchor`
  (key + local cell) + `IGalaxyGenerator.declarationOriginOf`. A declared key RESERVES its cell.
  Resolution happens once, at population, when the seed is known; an anchor past the guaranteed reach is a
  loud error, never a clamp. The catalogue is written back in the language it was declared in, or a
  resolved absolute would be re-read as an offset and shift on every save. `<galaxyType>` is authorable,
  which makes disc thickness a configurable quantity.
- **Nebulae.** `Nebula` + `NebulaField`: there is no nebula lattice and no nebula spacing. A cloud is
  DERIVED from the star cluster it wraps and that cluster type's `nebulaFraction` — a molecular cloud,
  the young cluster condensing out of it and the ancient cluster that has blown it away are one object at
  three ages. A starless dark cloud is a cluster type whose subdivision is 1; a real globular correctly
  has no cloud. Appearance (`DARK` / `EMISSION` / `REFLECTION`) is that age sequence, derived from the
  residual gas. **Diffuse matter is not a body:** no cell name, not a destination, outside `INV-UNI-01`.
  `Nebula.densityAt` has two consumers: a cell's sky DRAWS the clouds around it
  (`IGalaxyGenerator.nebulaeAround(seed, cell, radiusLy)` enumerated on the CLUSTER lattice, so the cost
  is cluster cells crossed; `SkyNebulaeProducer` → a direction and a half-angle on the nebula half of
  `PacketSystemBodiesSync`, C2), and a cloud CONCEALS: `NebulaField.columnDensityBetween` integrates it
  along a sight line and `UniverseRegistry.extinctionBetween` reads the column as magnitudes of visual
  extinction (`Nebula.MAGNITUDES_PER_DENSITY_LIGHT_YEAR`, anchored so a typical dark cloud reads
  `A_V ~ 10`). Past `telescopeObscuredAtMagnitudes` (C4, default 5) a survey writes the system's **bare
  coordinate** instead of its bodies. The integral is built ONCE, in this layer. The cost is DETAIL and
  never the look: the address is always written and the observatory reports how many looks the dust took
  (`msg.observetory.scan.obscured`). What a cloud does to a ship INSIDE it (drag, a minable resource,
  sensors) is unbuilt. A **DARK** cloud is drawn AFTER the starfield and the other two before it: a
  molecular cloud is visible by BLOTTING OUT what is behind it. `[V]`
- **Satellite galaxies.** The lattice seats one galaxy per cube 25 diameters wide, the distance to the
  nearest equal GIANT; a galaxy draws **satellites as CHILDREN inside its own cube**. `[V]`
  - The representation does not move: the cube keeps its size and no coordinate gains a field. A
    satellite is a `Galaxy` value drawn from `(seed, primary cell, ordinal)` and stored nowhere; the star
    field is populated by the same generator (placement is `galaxyProfileAt → densityAtSector`). A 921 ly
    dwarf satellite holds 660 systems in the 7×7×7 territories around its core `[T]`.
  - **Three questions:** `galaxyOwningSector` names the cube's PRIMARY (identity, declaration, naming);
    `Galaxy.containsSector` asks about one named galaxy; **`galaxyContainingSector`** answers which of the
    cube's galaxies a point is actually in — what the PROFILE, the FRAME and the CLUSTER lattice ask.
  - Count and size are properties of the TYPE: `minSatellites` / `maxSatellites` (dwarfs 0–0; spiral
    1–3, barred 1–4, elliptical 2–5) — a handful, because a satellite is resolved on the placement path
    and the count is a cost per query. A satellite's TYPE is drawn among archetypes whose whole band fits
    under `MAX_SATELLITE_RADIUS_FRACTION` (0.3), a constraint on the DRAW, never a clamp.
  - No two galaxies in a cube overlap by geometry: a satellite is at least one full DIAMETER out and at
    most 0.3 R across (measured nearest over 40 seeds: 1.02 diameters, against the lattice's 25).
  - The seat margin and the peculiar-velocity drift budget are the whole RETINUE's reach
    (`UniverseScale.retinueReachLy`). A satellite's centre does not move relative to its primary (a real
    orbit runs to 10⁹ years) and it carries the primary's peculiar velocity.
  - Identity: `Galaxy.satelliteIndex()` (0 = primary) and a `-S<n>` suffix on the designation;
    `ClusterField.nucleusOf` keys on the galaxy's own CENTRE.
- **The void has content: rogue worlds, rogue stars, and globulars thrown clear of a galaxy.** `[V]`
  - The void's population is the galaxies' EJECTA: `Galaxy.ejectaDensityAt` is zero inside the declared
    radius and falls as `EDGE_LEVEL · (R/r)³` outside (`Galaxy.EDGE_LEVEL` = what the profile reads at a
    galaxy's edge, ≈ 0.259, scale-free). A power law, because an exponential in units of the radius is
    dead within a few of them and the void is twenty-five across; ISOTROPIC, because ejection randomises
    a direction long before a body has crossed the void.
  - **One walk, two answers:** `GalaxyField.materialAtSector` returns `Material {bound, unbound}` (one
    walk per lattice cell of every placement query). Inside a galaxy the retinue's halo is never drawn
    (a couple of percent of the primary's disc there). `unbound` is 0 inside a galaxy, and a cube holding
    NO galaxy is `{0, 0}` `[T]`.
  - A **second draw** on the same star lattice, on the cubes the first passed over, at
    `density × rogue.abundance × material.total()`, with its own salts.
  - The numbers are measured and authorable (`GalaxyGenConfig.RogueTuning`; `<galaxyGen>` attributes
    `rogueAbundance`, `rogueGiantFraction`, `ejectaFalloff`, read and written back): `abundance = 21`
    free-floating terrestrial-mass worlds per star (microlensing survey), `giantFraction = 0.012` (the
    ratio of the two measured populations; a giant is the body DOING the scattering, so inheriting the
    bound outer-zone 0.34 would give half a free-floating giant per star), `ejectaFalloff = 3`.
  - **The lattice saturates that, and the saturation is the honest reading:** a cube holds at most one
    seat, so any abundance past `1/density` means "every territory the stars left empty holds
    something". Measured at the shipped density, a sun-like neighbourhood reads occupancy 1.000000 (1574
    lit systems and 3339 starless), so a count of OCCUPIED SEATS stops measuring the star field; a test
    that the density or profile drives the stars must count STELLAR seats `[T]`. The multiplier re-uses
    the star lattice and does not model rogues: it turns a number DENSITY into an occupancy PROBABILITY,
    bounded by one. The real quantity needs the `subdivision` mechanism clusters use
    (`k = ⌈abundance^⅓⌉`); that costs +5.6 % on the point path `anchorAt` (1.878 → 1.983 µs) and 27× the
    cells on region enumeration (which has no production caller) `[T]`.
  - The seat holds a WEIGHTED DRAW (`GalaxyGenConfig.RogueType`): `Rogue Planet` 1050, `Rogue Star` 1. A
    rogue star is a `STAR` and nothing else (rogue-ness is where it stands), so it gets an ordinary
    retinue.
  - **An anchor holds a PRIMARY BODY, which need not be a star:** `StarSystem` → `PlanetarySystem` with
    `primaryKind()` and `Optional<StellarBody> star()`; `starId()` → `systemId()`. The Optional makes a
    caller decide what to do about a starless system instead of receiving a 30 K zero-radius
    `StellarBody` whose name is a lie. `SystemBodyKind.ROGUE_PLANET` is appended last so the render wire's
    ordinals do not move.
  - `PlanetDerivation.deriveRogue`: no inherited metallicity, orbit, insolation, snow line or tidal lock.
    Temperature is `35 K · g^¼` (Earth's geothermal flux 0.087 W/m² radiates at `(F/σ)^¼ = 35 K`, and
    flux goes as mass over area, the `M/R²` already computed as gravity). It does NOT model a young
    giant's contraction heat (Jupiter would read 45 K against a real 124 K): that is an age term. A rocky
    rogue reads MIN pressure (volatiles on the ground as ice); only a hydrogen-bearing bulk is thick; no
    oxygen, ever.
  - **A rogue system is the world, its moons, and nothing else:** no belt, no companion, at most
    `MAX_MOONS_ROCKY` moons. `INV-UNI-01` holds. A rogue has no primary and so no Laplace sphere, so its
    zone lattice is bounded by the realized region alone (C15 ADDR-19).
  - **A rogue is NOT a descend target** — a bound of the DIMENSION model: a realized dimension resolves
    sky colour, insolation, year and temperature through a star it is required to have, in ~30
    unguarded call sites. `ROGUE_PLANET.canDescend()` is false, so the descent trigger never fires on
    one. `[A]`
  - `ClusterField` seats clusters outside galaxies too: only a **self-bound** type (`ClusterType.selfBound`:
    globular yes, open cluster and molecular cloud no), expressed as a constraint on the DRAW with the
    excluded weights removed from the total; and such a cluster **supplies its own field**
    (`INTERGALACTIC_CLUSTER_FIELD = 1`, i.e. an ordinary stellar neighbourhood), because a cluster's
    density is a CONTRAST against what surrounds it. `ClusterField` takes the `GalaxyField` so it can read
    the material at its own cell centre.
  - **Gradient, shipped config** (`VoidContentTest` + a sweep of 4913 cubes per point): occupancy 0.569
    and 5.1 ly between systems at 1.5 R past the edge, 10.3 ly at 3 R, 25.3 ly at 8 R, and nothing at
    the void's midpoint or in a galaxy-less cube. Stepping out of a galaxy is a gradient, not a wall, and
    the void is crossable by an interstellar drive at a cost `[T]`.

**UNIVERSE-layer (owned here), with an addon seam.** Generation goes through **`IGalaxyGenerator`,
addon-replaceable**:
- **API:** `systemAt(seed, GalacticCoord) → Optional<StarSystem>` (deterministic) and
  `systemsInRegion(seed, min, max)` (enumeration).
- **Hybrid placement.** Authored XML systems are placed as **anchors**; the generator fills the rest
  procedurally, seeded from the coord. Addons register or replace the sampler.
- **`<galaxyGen>` parameters** (with defaults): `density`, `minSpacing`, `galaxySpacing`,
  `galaxyDensity` and `<starType>` children.
- **Distribution is CLUSTERED** — dense galaxies separated by void — so a bounded scan range yields a
  natural "your galaxy" horizon (maintainer-confirmed); the horizon is the galaxy's own declared edge.

## 4. System content and planet terrain

**Content.** `SystemBody` (+ `SystemBodyKind` STAR / PLANET / MOON / ASTEROID_BELT / STATION_SLOT /
ROGUE_PLANET): a body's full address is a single `GalacticCoord` — sector = system cell, **local offset =
in-system position** (star at the cell centre; every body clamped inside the cell).
`UniverseRegistry.bodiesAt(coord)` = authored bodies (`SystemContent.bodiesOf` from the catalogued
`StellarBody` planets / moons) OR procedural (`IGalaxyGenerator.bodiesFor`, default empty;
`ClusteredGalaxyGenerator` fabricates star + planets + belt) + player POIs (`addPoi` / `poisAt`,
NBT-persisted in the override store). Only PLANET / MOON with a real dim are descend targets; other POIs
(stations, belts, the star) are in-space objects you share the bubble with. `planet → coord` =
`coordForPlanet`. A body's full address is `(GalacticCoord of its system) + (in-system position)`; inter-
AND intra-system jumps use the SAME address type — one hyperjump mechanic, no solar map.

**Terrain.** `TerrainSource` (`NATIVE | MOD_WORLDTYPE | TEMPLATE`) on `DimensionProperties` (+ XML + NBT,
written non-default-only so a NATIVE planet's bytes are unchanged), dispatched in
`WorldProviderPlanet.init` / `createChunkGenerator`: `MOD_WORLDTYPE` delegates to a foreign `WorldType`
(native fallback if unregistered or blank; `biomeProvider` flips to the foreign one), `TEMPLATE` loads
pre-generated region files verbatim via a void `ChunkProviderTemplate` + a sandboxed `TemplateImporter`
(`config/advRocketry/templates/<name>/`, on `WorldEvent.Load`). `genType` stays the NATIVE sub-flavour
selector. **Orthogonality invariant:** the provider stays `WorldProviderPlanet` in every mode;
`terrainSource` is read at exactly two sites inside it; atmosphere / gravity / ore are dim-keyed and
apply regardless of the chunk generator (server-e2e-pinned — gravity preserved under the foreign
generator). Atmosphere (`AtmosphereHandler`, per-dim), gravity (per-dim `DimensionProperties`) and ore
(`OreGenerator` / `CustomizableOreGen`, an `IWorldGenerator` post-pass AFTER terrain) make a
foreign-generated or template world a space planet automatically. TEMPLATE-imported chunks are
`TerrainPopulated`, so Stellurgy ore does not spawn in them (authors bake ore into the export). Open:
whether Stellurgy forces atmosphere / gravity on a foreign world (lean yes) and whether both Stellurgy's
and the mod's ores spawn.

> **INV-UNI-01 (maintainer-locked 2026-07-28): AT MOST ONE REAL BODY PER CELL.** Every real body owns its
> own cell. A moon is named in its PARENT's zone, and a zone-qualified key can never equal a galactic
> one, so the audit compares a moon only against its siblings in that zone. A cell is the unit a jump is
> AIMED at and the unit a ship ARRIVES into, so two real bodies in one cell are two destinations a
> player can neither tell apart nor choose between, and an arrival that cannot say which body it came
> for. `[V]` Enforced as an audit, `SystemContent.auditOneRealBodyPerCell` (reports, deduplicated per
> session; never silently relocates an authored body — an address a player wrote down must not come to
> mean something else). **Violated today:** authored orbits exceed the neighbourhood bound, so
> `clampIntoBox` collapses body cells into the anchor's super-cell (measured: dim 0's own cell clamped
> from `(-3,0,25)`); the audit makes that measurable, and whether XML gains an explicit per-body cell is
> open.

**Planet properties, realization and the retinue.** A procedural body has physics, a world and
neighbours:
- **A body's PROPERTIES are derived from `(seed, cell)`** — `PlanetDerivation` → `BodyProfile`, a pure
  function with no world, no `Random` and no tick. Order: star metallicity → orbital radius (drawn
  LOGARITHMICALLY over a range anchored on the star's own reference distance) → bare temperature →
  radius and mass (giants past the snow line) → gravity **derived** as `M/R²` → pressure from atmospheric
  retention → temperature again, with that atmosphere → type → the oxygen roll → **what of that air the
  world KEEPS** (`BodyAtmosphere.derive`, per-gas escape and cold trap: a body that keeps nothing reports
  pressure 0 and its bare temperature, its type kept if it admits that or redrawn among airless types) →
  terrain. The profile's pressure is exactly the total the landing realizes. Zoning EMERGES from the
  physics. `[T]` `PlanetDerivationTest.aPressureTheScanReportsIsAirTheWorldKeeps`.
- **A planet TYPE is an XML preset** — `PlanetTypePreset` + `PlanetTypes` (stock table in code, the
  `<planetType>` element overrides it wholesale). The table is an immutable value owned by the server's
  galaxy (`DimensionManager.getPlanetTypes()`) and handed to the schema's derivation
  (`UniverseSchema.generator(config, types)` → `new BodyDerivationV0(types)`); there is no process-wide
  "current table". A preset declares its admission ranges, weighted terrain generators, ore table and
  native biome palette. Overlap is resolved by a **weighted draw among every admitting preset**, never
  first match (which would make the XML's document ORDER load-bearing). A `<gen>` naming a `WorldType`
  this modset lacks is dropped **before** the draw, so the remaining weights renormalize.
- **A DESCENT realizes a body into a dimension** — `PlanetRealizer.realize`, driven from the flight
  computer's proximity check. It pins the system, mints a dimension, **materializes** the derived profile
  (never rolls fresh values — a scan promised those numbers from across the system), rewrites the pinned
  `SystemBody`'s `dimId`, and is idempotent so a per-tick trigger cannot allocate a dimension per tick.
  Nothing else realizes: a telescope sweep would otherwise mint by the dozen. A realized MOON is bound to
  its parent BEFORE its star, so `DimensionProperties#setStar` — which lists only non-moons among a
  star's planets — leaves it out `[T]`
  `ProceduralPlanetRealizationTest#aRealizedMoonIsNotListedAmongItsStarsPlanets`.
- **Realization is keyed on the BODY, never on its address** `[V]`. Two siblings can land in one cell of
  their parent's lattice, and POIs stand in cells they are not the primary of; `realize` takes the
  `SystemBody` that was approached, and the registry addresses a body by its **`variant`** — its rank
  among that cell's realizable bodies, the same number the derivation is keyed on.
  `realizableBodiesAt` is the single place that decides who is counted; stars, station slots and belts
  are excluded, because counting them would shift every variant and materialize the wrong world. Keyed on
  the cell alone a moon could never be realized and a descent aimed at one would land on its planet —
  `PlanetRealizationTest.aMoonGetsItsOwnWorldAndNotItsPlanetsOne`.
- **A variant is RECOVERED by matching, and the match includes BOTH of the body's laws** `[V]`.
  `variantOf` compares kind, orbital distance, cell, **`offsetLaw()` and `frame()`** (the caller holds one
  instance and the pinned snapshot another). Address, kind and orbit do not separate SIBLINGS: two moons
  of one parent share the kind and the parent's distance from the star, and can land in one cell. The
  moon's own orbit — radius, angle, period — makes a sibling a sibling, and it lives in the FRAME (a
  moon's cell rides the moon, so its `offsetLaw` is `STATIC` like a planet's); matching on the offset law
  alone would answer "the first moon" for every moon of a family. Both laws compare by value and
  round-trip through NBT. **An ambiguous match is refused rather than guessed.**
  `PlanetRealizationTest.twoMoonsOfOnePlanetAreTwoDifferentBodies`.
- **Realizing a MOON realizes its parent first** `[V]`. Moon-ness is carried by a parent DIMENSION id
  (`DimensionProperties.isMoon()` reads `parentPlanet`), so a moon minted while its parent has no world
  would be written down as a plain planet at the parent's own distance from the star, permanently.
  `realize` materializes the parent variant before the moon and re-reads the family. This adds at most
  one parent per moon-first landing. `ProceduralPlanetRealizationTest.aMoonRealizedBeforeItsParentIsStillAMoon`.
- **The descent refusal is asked BEFORE the idempotent lookup** `[V]`: `realize` refuses a body whose
  kind cannot be descended into at entry, not after checking whether it already has a world (a gas giant
  with moons HAS one, and the idempotent answer would read as permission to land).
- **The retinue** — a long-tailed body count (median ~5, a thin tail past 15, hard ceiling 24 to bound
  the per-query cost), moons in cells of their own inside their parent's ZONE, a **mandatory** outer belt
  on every system, an inner belt derived from a giant's resonance gap, rings on giants. Bodies claim
  DISTINCT cells (two real bodies in one cell would trip `INV-UNI-01` on the generator's own output).
  Inner belts are DERIVED from a giant's resonances, rings belong to giants via the Roche limit, orbital
  radii are drawn logarithmically; a belt is material that never accreted, not a planet that broke.
- **A gas giant is not a descent target and never gets a WALKABLE dimension**, but a giant WITH MOONS gets
  a `DimensionProperties` record, because its moons need a parent to hang off. `registerDimNoUpdate`
  registers the properties always and a Forge dimension only for a body with a surface, so the giant
  becomes a PLACE without becoming a world. A giant with no moons has no dimension at all, so its derived
  `hasRings` has nowhere to render; the authored-giant path (`generateRandomGasGiant`) is the channel that
  shows rings. `ProceduralPlanetRealizationTest.aGasGiantsMoonIsAMoonAndTheGiantStaysUnlandable`.

**Planet content ratifications (maintainer, 2026-08-10).** The SPINE is *ценность ресурса = сложность
планеты* (the value of a resource is the difficulty of its planet): gating, resource economy and
prize-hunt are ONE gradient, legible from a scan BEFORE it is reachable (a hard world advertises its
payoff and its 900 kPa together). Difficulty is expressed only in forms a deployed base can OVERCOME,
never as an absolute wall. Planet TYPE is derived from physics (insolation × mass / radius), not drawn
from a flat weight table, so the snow line and habitable band emerge and move with the star's
temperature. Earth-likeness is a conjunction and oxygen is an independent rare roll over an
already-suitable world, so *almost-Earth* (warm, wet, unbreathable) is the common intermediate and
terraforming has a target. Radius and mass are primary; gravity is derived (`g ∝ M/R²`) with
`gravitationalMultiplier` kept as an explicit override so no authored planet changes. Third-party world
generators are first-class, weighted per preset, filtered for registration BEFORE the draw and fixed at
realization.

## 5. Entry on-ramp

**MODEL owned here; the ascent flight, pathfinder and materialize are SHIP-layer.** Entry into space is
**ASCENT, distinct from the hyperjump**: ascent is takeoff (planet → orbit), the hyperjump is
space↔space travel.
- **Manual or automated.** Manual: the pilot at the helm climbs under thrust; crossing the planet's
  ceiling enters the home system's space. Automated: a helm action triggers a basic auto-takeoff
  pathfinder to orbit — a DIAGONAL climb. If the pathfinder finds no way up, that is a NORMAL surfaced
  outcome (fall back to manual), not an error.
- **Ascent is the SAFE exit, not the only one.** A hyperjump from within the gravity well is also legal,
  costlier and more dangerous (§6).
- **First `GalacticCoord` = the launch planet's system.** On entering space the ship materializes at the
  coordinate of the system containing the planet it launched from; the registry resolves
  `planet dim → DimensionProperties → star / system → GalacticCoord`.
- **Plumbing = a dedicated tier-2 entry handler.** The trigger (ascent crosses the ceiling / auto-takeoff
  completes) fires a server-side handler that resolves the coord via `UniverseRegistry`, then hands off
  to Layer-2 `materialize`. The universe layer exposes only the `planet/pos → GalacticCoord` lookup. Only
  tier-2 VS ships acquire a coord — tier-1 rockets stay intra-system via a direct orbit→dim selector and
  never enter the grid.

## 6. Planet↔grid seam

**Data mapping, the descent-trigger MODEL and the legality gradient are owned here; the crossing
MECHANISM and the boundary RENDER are SHIP / client-layer.**
- **planet → `GalacticCoord` via `starId` + the registry reverse index.** A planet knows its star
  (`DimensionProperties.starId`); the registry owns `system ↔ coord`. So
  `planet → starId → system → registry.coordOf(system) → GalacticCoord`; no coord field on the planet or
  the system.
- **Descent = by PROXIMITY across the atmosphere↔orbit boundary.** A tier-2 ship that closes on a planet
  and crosses its natural atmosphere↔orbit boundary transitions into that planet's dim (surface). The
  boundary is RENDERED so descent is deliberate. **Asymmetry:** hyperjump exit into space is AUTOMATIC
  (the target is pre-set; no proximity trigger); only **planet descent** is proximity-driven. A planet
  POI *is* a `DimensionProperties` (its `dimId`), so the POI↔dim binding is trivial. Ascent is the
  inverse: leaving planet dim D drops you into the system's space at D's in-system POI position.
  **That "exit is always into space" holds only for a KNOWN destination** (arrival model, maintainer
  2026-07-23): with a scanned target the nav computer computes a safe exit point; with an UNSCANNED target
  the ship is dropped at a RANDOM point in the cell, which can put it inside a planet — an arrival CAN
  land in a planet's dim / terrain (instant death). Safe exit is the reward for having scanned.
- **Jump legality = a GRADED gravity well, not a hard gate.** A hyperjump is legal anywhere, but the
  deeper in a planet's gravity well the **costlier AND more dangerous** (surface = worst → orbit =
  safe / free). Well strength scales with the body's MASS (black holes heaviest; maintainer 2026-07-16);
  the counter-stat is hyperdrive POWER (strain = wellStrength / drivePower; a late-game drive crosses a
  black-hole well cleanly at colossal energy cost). Wells apply BOTH at departure and ALONG the transit
  path. Layer-1 additions needed by the hazard model (design, not built): `SystemBody.mass`,
  `SystemBodyKind.BLACK_HOLE` (non-descend), a `<galaxyGen>` black-hole weight, and a wells query for the
  ship layer. The well boundary reuses the orbit-altitude / atmosphere line; all cost / danger numbers
  are `tunable`. The atmosphere↔orbit line has ONE owner — `DimensionProperties.orbitLine()`, derived
  from the body (no config default); the cell-side descent trigger is the body's own `DescentShell`
  (`space-model.md` §10, `metric-boundary` MECH-MET-02).
- **The crossing mechanism is Layer 2's:** the per-ship pack/paste (`VSIntegration.crossShip` +
  `VSShipCrosser` + `ShipTransitManager`) — `space-model.md` §4 and §9. The hazard model is
  `space-model.md` §10.

## 7. Persistence

**Durable LOGICAL position + transit persistence owned here; the physical re-materialize and the
dirty-hook are SHIP / infrastructure-layer.** MC saves a player by dim + pos, but our dim is a TRANSIENT
pool slot that may hold a different cell after a reboot — only the coordinate is meaningful across a
restart.
- **Position keyed by `GalacticCoord`, re-materialized on login.** A player / ship's durable position is
  `(GalacticCoord [+ in-system pos])`, stored per occupant (player NBT / the universe registry), NEVER
  the transient slot dim id. On login a handler resolves the last coord → materializes that cell into a
  slot → places the occupant, ignoring MC's stale slot-dim restore. (A permanent dim per cell is
  rejected: it throws away the finite slot pool.)
- **Transit is PERSISTED as a lightweight record; the ship rides as a `StorageChunk`; hyperspace-the-world
  stays ephemeral.** A persisted transit record (`WorldSavedData`) holds, per in-flight ship, its
  `StorageChunk` snapshot + params (origin / target / progress / ship id). The hyperspace WORLD is never
  saved with live parked ships.
  - **Online:** ships transit live-parked in the hyperspace world (`space-model.md` §10).
  - **Save / logout:** each in-flight ship is packed to a `StorageChunk` in the record.
  - **Restart:** the record restores; the ship unpacks back into hyperspace when it must be live (owner
    present) or the transit completes to its target cell. `spaceTransitOfflineProgress ∈ always |
    crew-online` (default `always`): `crew-online` pauses while NO aboard crew member is online; unmanned
    transits always advance; nothing advances while the server is off.
- **The dirty-hook fires at content-DIVERGENCE events:** mark a cell dirty only when it diverges from its
  seed (a ship parks, a block changes, a station is built) so `evict()` flushes it instead of discarding
  (`space-model.md` §5; lives in `SpaceManager`).
- **Stations:** `SpaceObjectManager` stations persist as their own dims; only their REPRESENTATION in the
  grid as claimed POIs is a content / registry concern, owned by the stations-are-ships model
  (`space-model.md` §10).

### 7.1 The world model a save was generated under

The universe is DERIVED, not stored, so the generator's own arithmetic is a save-compatibility surface:
the same seed run through changed code or changed knobs answers a different universe under an unchanged
save file. `[V]`

- **The versioned unit is the SCHEMA, not the generator.** `IGalaxyGenerator` + the body derivation +
  `UniverseScale` + `Cosmology` version together — everything the telescope promises
  (`universe/UniverseSchema.java`, `UniverseSchemaV0.java`). `[V]`
- **All four members are versioned the same way: BY IMPLEMENTATION.** A schema hands out a generator, and
  the generator carries the other two — `IGalaxyGenerator.derivation()` and `IGalaxyGenerator.laws()`
  (`IBodyDerivation` / `IUniverseLaws`, with `BodyDerivationV0` and `UniverseLawsV0` forwarding to
  `PlanetDerivation` / `UniverseScale` / `Cosmology`, so every law stays where its constants are
  documented). Nothing about a released world model reads a global. `[V]`
  - **The purpose:** one build holds a new model for new worlds and the old model for the worlds already
    made under it, so the mod ships features — blocks, machines, balance, mechanics — to a player whose
    sky never moves. Wanting to change the generator is answered by *writing a new version*, not by
    weighing backward compatibility.
  - `GalacticCoord` and `AbsolutePos` convert nothing, which is what made threading the schema through
    the generator possible: the metric was never welded into the type that lives in NBT.
  - **What stays global:** the lattice DEFAULTS (`DEFAULT_SPACING_CELLS`, `DEFAULT_GALAXY_SPACING_CELLS`)
    decide only what a NEW world is given (an existing one carries its own numbers in its
    `GalaxyGenConfig`), and `DriveTier`'s band ratio prices a machine rather than measures space.
  - **The laws stamp is a TRIPWIRE, not a barrier.** `UniverseRegistry.lawsFingerprintOf` measures a
    version's laws (fixed inputs through every conversion, the expansion at fixed ticks) rather than
    listing constants, so it covers any implementation and catches an internal constant that moved while a
    declaration did not. A mismatch means a RELEASED version was edited in place — a developer error,
    which is why the upgrade door may not accept it. `[T]`
  - **The derivation is not a decoration on a fixed layout:** a body's cell follows its orbital distance,
    so changing the orbit law changes which seats are claimed and how many fit — pinned by
    `aGeneratorDerivesItsBodiesThroughTheDerivationItWasGiven` (a +7 shift moved a retinue from 5 bodies
    to 8). `[T]`
- **Every RELEASED version stays in the jar.** `UniverseSchemas` is an immutable catalogue
  (`Map<Integer, Supplier<…>>`) built by `UniverseSchemas.builtIn()`; a `UniverseRegistry` answers from the
  catalogue BOUND to it (`bindSchemas`, done by `UniverseRegistry.get(World)` in production, by
  `TestUniverse` in a test). A released version is added and never removed, because dropping one makes
  every save carrying its stamp unopenable. **A version that has not shipped may be edited in place or
  replaced outright** — no world outside the branch was generated under it. "Shipped" means merged to the
  release branch (maintainer, 2026-08-19). **That freeze binds a STABLE version; an ALPHA (`0.x`) that has
  shipped is still edited in place** (maintainer, 2026-10-01): the leading zero is the promise that it may
  be, and the player is told so on every load, provided each such edit is recorded in the version's own
  javadoc with its measured golden-corpus blast radius and the corpus is regenerated. New versions are
  DECORATORS over the previous with delegation, so invariants are inherited. `[V]`
- **The shipped model is version 0, labelled `"0.1"`; the leading zero is a PROMISE ABOUT MATURITY: an
  alpha may be REPLACED outright rather than extended.** `version()` is the identity (stamped, keys the
  registry, never reused); `label()` is the maturity statement; `isStable()` is
  `!label().startsWith("0.")`. The player is told on every login to such a world
  (`msg.stellurgy.universe.alpha`, gold), the server says it at WARN on every boot, and
  `/stellurgy universe status` repeats it. `[V]`
- **`UNSTAMPED` is `-1`, and the stamp is read through `hasKey`:** NBT answers 0 for an absent integer, so
  reading the VALUE would report every stampless save as "generated by the alpha" and skip the adoption a
  fresh world is owed. Pinned by `anAbsentStampIsNotReadAsVersionZero` and
  `anAlphaWorldIsRecognisedAsStampedAfterAReload`. `[T]`
- **The stamp lives in the save** — `schemaVersion` + `galaxyConfigFingerprint`, written by
  `UniverseRegistry.writeToNBT` and deliberately separate from `NBT_VERSION` (the tag layout). `[V]`
- **The version is raised from the SAVE, not from the pack.** The server's own `DimensionManager` stages
  the pack's `<galaxyGen>` knobs (`getPackGalaxyConfig`, kept for the session) and the planet file's
  authored anchors (`stagedAnchors`, drained exactly once by `drainStagedAnchors`);
  `UniverseRegistry.populate(server, galaxy)` — an explicit argument, no static galaxy — pairs the knobs
  with the save's stamp, installs that version's generator, and only then drains anchors (anchors resolve
  THROUGH the generator, so an anchor placed under the wrong model would be placed wrongly and
  persisted). The generator, the star lookup and the active schema are instance fields of that save's
  registry, so a second server in one JVM never sees this one's model. `[V]`
- **Layout reports are the galaxy's, not the process's.** The report-once memory the body derivation
  consults (`universe/ReportOnce.java`) is owned by each side's `DimensionManager`, handed to
  `UniverseRegistry` by `bindReports` (a registry binds one galaxy only; rebinding another throws) and
  threaded as a parameter through derivation (`PlanetDerivation`, `SystemContent`,
  `ClusteredGalaxyGenerator`), so a layout problem is reported once per galaxy and a dropped galaxy takes
  its memory with it. `[V]`
- **Two refusals, both `UniverseSchemaMismatchException` and both fatal to the load:** a stamp naming a
  version this build does not carry, and a `<galaxyGen>` fingerprint that has changed since the world was
  made. Continuing would silently answer a different universe; a refusal is recoverable from outside the
  game, a regenerated sky is not. An UNSTAMPED save (no content, or predating the stamp) adopts the
  current model and says so in the log. `[V]`
- **A look is a touch** — `TelescopeScan.resolveCell` pins the system before writing a word of it onto a
  crystal, so what the crystal holds and what the sky holds cannot come apart. The unit is the whole
  system (an obscured look still yields an address and a primary kind, which ARE the system's identity).
  Measured: a 27-look sweep froze 17 systems in 27 ms, ~2.6 kB of rendered NBT each. `[T]`
- **The door out** is `/stellurgy universe upgrade confirm` — freeze everything already seen (every
  address on the memory crystals of players online), then move the world on. The seam at the frontier of
  the explored is the player's choice; mechanics of a newer model arrive in an old world without content.
  Crystals in chests, in unloaded chunks or on offline players are NOT reachable, and the command says so
  before it asks for confirmation. `[V]`
- **The two halves of an upgrade are not symmetric, because a fingerprint is one-way.** A SCHEMA version
  can be moved in place: this build carries the new one, so the command adopts it and reinstalls the
  generator live. A CONFIGURATION change cannot — a changed `<galaxyGen>` refuses the boot, so the
  command that would accept it cannot be typed, and the old universe cannot be reconstructed from a hash.
  So the command also ARMS the world (`universeUpgradeArmed`), a one-shot permission the next load
  spends. The ritual, which the refusal message spells out: restore the old configuration → start →
  `upgrade confirm` → stop → install the new configuration → start. The permission is consumed only when
  the fingerprint actually differs. `[V]`
- **Golden corpus.** `ClusteredGalaxyGeneratorTest.theGoldenCorpusIsByteIdentical` renders 7 fixed seeds
  over a fixed region — placement, bodies, a derivation sample per cell, the scale constants and the
  expansion factor — and byte-compares against `src/test/resources/universe/golden-corpus-v1.txt`. No
  diff → minor release; a diff → a NEW schema version, without discussion. Regenerate deliberately with
  `-Dstellurgy.universe.corpus.write=true`. `[T]`

## 8. Discovery and navigation

**The info-tier schema, the visibility flag, the known-address FORMAT and the jump rule are owned here;
the scan TECH, the nav-computer block, the crystal item and the finding mechanics are SHIP / gameplay.**

- **Initial visibility = a per-system / planet XML flag.** Authored (XML) entries set `<isKnown>`;
  procedural systems default UNKNOWN (synthetic negative ids / `INVALID_PLANET` bodies never enter the
  int-dim-keyed sets). HOW an unknown entry becomes known is a gameplay FINDING mechanic. The flag
  covers the finite authored set, so there is no contradiction with the infinite galaxy.
- **System-known is a QUERY, never a stored flag** (maintainer 2026-07-16): `UniverseRegistry
  .isSystemKnown(GalacticCoord)` is true iff any member body with a real dim is known (star-proxy
  excluded); `DimensionManager.isPlanetKnown(int)` is raw membership of the legacy set. **`InfoTier
  { TELESCOPE | APPROACH | ORBIT }`** + `PlanetInfoField` (23 logical fields, each a minimum tier) +
  `isVisible` / `fieldsVisibleAt` in `.universe` are pure classification with no per-planet data.
  Atmosphere DENSITY is GLOBAL; star-level info is uniformly GLOBAL. Tier redaction goes to the nav GUI
  over a separate server-redacted channel (`fieldsVisibleAt`); the legacy full-NBT sync is untouched.
  **The telescope is NOT gated by `isSystemKnown` — it IS the discoverer.**
- **Knowledge is crystal-derived over an innate home-system floor (target model).** Tier-2 address
  knowledge lives in memory crystals ONLY (unlimited capacity — a crystal, not a flash drive;
  maintainer-fixed). The global known-set stops being the discovery / progression authority
  (`DimensionManager.knownPlanets` is still present in code); tier-1 planet-selector visibility reads the
  INNATE home-system set (home bodies + authored `<isKnown>` inside the home system); tier-1 never
  touches crystals. Authored `<isKnown>` bodies OUTSIDE the home system pre-seed the STARTER crystal (with
  the overworld coord) for the first tier-2 jump. `isSystemKnown(coord, knowledgeCtx)` is scoped to the
  inserted crystal / nav computer; per-station lists and `PacketSyncKnownPlanets` end with the
  station-as-ship model.
- **`planetsMustBeDiscovered` is the research MASTER-SWITCH.** ON = the full scan + compute model (raw
  over scan-time → processed to detail). OFF = **instant-on-observe, NOT all-known:** you instantly know
  only what a scanner REACHED. **Boundary B (maintainer):** in OFF only the passive LOCAL radar and an
  explicit DIRECTED scan reveal instantly; the far AUTO-SWEEP is part of the research system and does
  not auto-map in OFF.
- **INV-SCAN** `[A]` (locked): no scan ever enumerates an unbounded region — bounded by (1) a max RANGE from the
  scanner and (2) auto-sweep advancing a bounded sector count per lazy-deadline step, never all-at-once.
  It holds identically in ON and OFF; it is the structural guard against "the whole procedural universe
  revealed in a tick".
- **The detail ladder is orthogonal to `InfoTier`:** `InfoTier` is the PROXIMITY axis; sensor-tier ×
  compute gating refines the TELESCOPE tier internally; both apply. The observatory is the shared raw-
  acquisition layer both branches build on.
- **The crystal NBT schema** carries address + detail-LEVEL + observation TIMESTAMP + merge-by-freshness,
  not just an address list.
- **Research pipeline stages:** **ACQUIRE** = the observatory (and base) plus ship sensors (including the
  on-arrival scan module); **PROCESS** = the compute engine (detail ladder, `isSystemKnown(coord, ctx)`);
  **STORE** = the crystal item + NBT schema + merge-by-freshness; **CONSUME** = the nav computer /
  `JumpGate` and the hyperjump. INV-SCAN and boundary B are DEFINED here; the stage implementations only
  ENFORCE them.
- **The home world's MOONS are found with a telescope, not handed out** (maintainer: *"Да, теперь Луну
  надо смотреть в телескоп, чтобы она появилась в кристалле."* — the moon must be looked at through the
  telescope to appear in the crystal). The STARTER crystal skips the home body's whole ZONE — the home
  world (seeded as its own line) and every body whose cell lies inside its zone, at any depth
  (`CrystalSeeding`, `isInZoneOf`) `[V]`. The other planets of the home SYSTEM are not in that zone (each
  has its own galactic cell) and stay common knowledge. The home moon's address comes from the
  observatory's LOCAL RADAR, which characterises the system the machine stands in down to its moons
  (`TelescopeScan.characterise` → `systemBodiesAt`) `[T]` `M1PlanetToPlanetMilestoneE2ETest` leg T. With
  characterisation set to addresses-only it is not named. The rule is per ZONE: a pack whose home world
  has several moons gets none of them in a starter crystal.

**Discovery is a full hybrid.**
- **Telescope (region scan).** Reveals systems; the farther the target, the longer the scan. A survey
  resolves each cell it looks at through the system that OWNS that cell (`anchorForCell`), because
  `systemsInRegion` answers "which cells hold a seated star", and a star holds one cell of a territory
  millions of cells wide — a sweep gated on it would find a system only by landing on the star's own
  address. The owner question is also the one a telescope physically asks: it sees light from a
  direction, not an address.
  - **A survey's STRIDE is one star's territory** (`IGalaxyGenerator.minSpacingCells`), so N looks are N
    candidate systems rather than N cells of the same one. **A look owes that whole territory**
    (`anchorsInTerritory`): the lattice is divided below the territory edge, so a look that resolved only
    its own point would report one seat in `k³` and present it as the sky. Past
    `TelescopeScan.MAX_SEATS_PER_LOOK` (64) the divider is a star CLUSTER and the survey goes back to
    **sampling** it rather than counting it, consistent with INV-SCAN. **Less every seat whose own
    neighbourhood reaches an AUTHORED one (C15 ADDR-23):** the registry masks the generator's seats at
    `anchorForCell`, `anchorsInTerritory` and `systemsInRegion` through one predicate,
    `UniverseRegistry#clearOfAuthored` `[V]`; the generator itself is never told. A pinned procedural
    system is not authored and clears nothing, but it keeps the stored `minSpacing/2` box, so it absorbs
    its own lattice siblings (open).
  - **The passive local radar strides by TERRITORY too:** one look already yields every body of the
    system that owns it, and no radius a cell-strided box could afford ever reached a NEIGHBOUR.
  - **A telescope looks at a PATCH OF SKY, and what it can see is a BRIGHTNESS.** A survey is a CONE
    (`ConeWalk`): an apex at the observatory, a direction, a half-angle (`telescopeConeHalfAngleDegrees`),
    walked shell by shell outwards so an aborted survey covered a shorter cone rather than a scatter. The
    local radar keeps its box (it is not aimed).
  - **The reach is DERIVED from the aperture**, never configured. A star registers when its apparent
    magnitude from the observatory is above `telescopeLimitingMagnitude` (default 8):
    `m = M + 5·log₁₀(d/10pc) + A_V`, with `M` from the star's size and temperature through
    `L/L☉ = R²(T/T☉)⁴` (`StellarMagnitude`). A pointing walks only as far as the brightest archetype the
    generator can produce would still be visible. Measured at the shipped defaults `[T]`: reach 1 767 ly
    (592 territories), a full pointing is 57 212 looks in 441 steps (~7 minutes of clear night),
    registering 14 systems, 715 ms of CPU spread over those steps. Consequences: (a) one instrument
    reaches far less for a red dwarf than for a blue giant at the same limit, which no single configured
    length could say; (b) **dust and distance are one sum** — extinction is in magnitudes, so
    `telescopeObscuredAtMagnitudes` composes with the aperture; (c) **a starless world is not something a
    telescope finds** — an unbound world emits nothing, finding a rogue means going there; (d) "the radius
    of a galaxy" is a **progression axis**: a better aperture reaches farther by seeing more.
  - **Detection is split from characterisation** (`TelescopeScan.detect` / `characterise`): the cheap
    question — is anything there, and bright enough — is an anchor lookup and a magnitude with no body
    derived (a detection sweep derives **zero** bodies `[T]`); the expensive one is paid only where the
    first found something. The operator chooses on the instrument whether a detection is followed to the
    system's bodies or recorded as an address alone.
  - **The INSTRUMENT has a gate too, the resolve margin.** A system must be
    `telescopeResolveMarginMagnitudes` brighter than the aperture's limit before its bodies can be made
    out; below that the look yields the address whatever the operator asked
    (`TelescopeScan.resolveLimitMagnitude`; `detect` sets `Detection.resolvable`). **The 6.5 is DERIVED
    and a retune must argue with the derivation:** detection is conventionally called at a signal-to-noise
    of ~5 and a usable spectrum wants ~100; S/N grows as the square root of the photons collected, so the
    flux ratio is `(100/5)² = 400`, i.e. `2.5·log10(400) = 6.5` magnitudes — a factor of 20 in distance,
    so the volume a pointing can characterise is ~8 000× smaller than the volume it can register `[T]`.
    Measured at the shipped aperture: a full pointing registers 14 systems and resolves 1 `[T]`; the knob
    that changes it is the APERTURE, and `0` disables the distinction (C4). Extinction is added to the
    apparent magnitude, so a cloud can push a system below the resolve limit, below the detection limit,
    or neither.
- **On-arrival scan.** Arriving in a system auto-scans its bodies, only with the appropriate MODULE, and
  not instantly: a fixed per-body time (config). Star-charts as items are an optional later hook.
- **Known addresses live in a MEMORY-CRYSTAL item held by a NAVIGATION COMPUTER.** The data is owned by
  the ship's computer, not the player. Every hyperjump-capable ship carries, besides the hyperdrive, a
  **navigation computer** that computes the jump parameters from `(current position, target, known
  hyperspace distortions between them)`.
  - **A jump needs BOTH a known TARGET (an address in a crystal) AND a known CURRENT position** — an
    unknown current coord makes the jump uncomputable. (A misjump can leave the ship position-unknown and
    it must re-localize first.)
  - Storage = a memory-crystal ITEM whose NBT holds a list of addresses; the nav computer has a section
    for crystal ops — copy (ADD-ONLY, no duplicates), erase.
  - The address DATA format is the universe layer's; the nav-computer block, crystal item and operations
    are ship / gameplay.
- **Information is TIERED by proximity.** A planet's GLOBAL params (coordinate, atmosphere, presence of
  water) are obtainable by TELESCOPE from afar; terrain types, life, etc. only on APPROACH; **100% of a
  planet's info requires being at its orbit.** The universe layer owns the info-tier schema; the scanners
  realize it.

## 9. Pool availability policy (design)

**INFRASTRUCTURE / ship-layer POLICY — decided here, implemented in `SpaceManager` + config. Not built:
the pool today is the fixed N of `spaceCellPoolSize` (`space-model.md` §5, §10 "Pool policy").** Layered,
most-graceful-first; under pressure we NEVER force-evict an OBSERVED live bubble out from under a player.
- **Layer 1 — a slot is hard-held ONLY by an ONLINE OBSERVER.** A cell occupied only by parked ships with
  no online player is EVICTABLE — flush it to the coord store and unload; it re-materializes when next
  observed. Transit ships do not consume pool slots (they live in the shared hyperspace world). So the
  effective limit is "N cells each holding an ONLINE player, in DISTINCT cells" — practically never hit
  at N=10 in a modpack. The primary, invisible relief.
- **Layer 2 — dynamic growth N→M with console warnings.** If Layer 1 cannot free a slot, the pool GROWS
  beyond soft target N up to hard max M, with a console WARN on each growth.
- **Layer 3 — reject at hard max M (last resort).** At M, a new distinct-cell entry is refused. Pilot:
  "space is at capacity — wait and try again shortly"; log / admin: "space pool hard max reached — raise
  `spaceCellPoolMax` (/ `spaceCellPoolSize`)".
- **Config:** `spaceCellPoolSize` = soft target N (default 10, the comfortable concurrent-distinct-cells
  floor); a `spaceCellPoolMax` = hard max M (default 20).
- **Runtime-mutable thresholds.** Config values are read ONCE at startup and never re-read or written
  back; an admin verb `/ar space pool <N> [M]` sets the LIVE values directly without recreating or
  destroying any world; a restart re-seeds from config. Raising is free; lowering below the current live
  count is safe (the pool stops growing and drains as cells vacate). Validate `1 ≤ N ≤ M`.

## 10. Decision ledger

**DECIDED (maintainer-approved):**
- **A (cosmology):** `UniverseRegistry` (`WorldSavedData`) owns a two-way `GalacticCoord ↔ StarSystem` map;
  systems are location-agnostic; procedural `(seed, coord)` + sparse override store; no explicit galaxy
  address tier; reuse `StellarBody` / `DimensionProperties` / `XMLPlanetLoader`.
- **A (generation):** `IGalaxyGenerator`, addon-replaceable; authored XML anchors + procedural fill;
  `<galaxyGen>` params; distribution clustered.
- **A (content / terrain):** systems carry bodies + POIs as addressable data; only planets are descent
  targets; `terrainSource ∈ NATIVE | MOD_WORLDTYPE | TEMPLATE`; Stellurgy planet-ness orthogonal in all
  modes.
- **A (solar map):** retired — the hyperjump is the one travel mechanic (it works intra-system); only the
  system RENDER stays for the nav / targeting UI. `EntityRocket.getInSpaceFlight()` cruise is deleted;
  tier-1 rocket travel is the station-as-ship model.
- **A (planet content, system retinue):** §4.
- **B (entry):** entry = ascent (manual or auto-pathfinder-to-orbit, diagonal, declinable), distinct from
  the hyperjump; first coord = launch planet's system; a tier-2 entry handler resolves coord → Layer-2
  materialize.
- **C (seam):** planet→coord via `starId` + registry reverse index; descent by proximity across the
  atmosphere↔orbit boundary (hyperjump exit automatic); jump legality = a graded gravity well.
- **D (persistence):** position keyed by `GalacticCoord`, re-materialized on login; transit persisted as
  a lightweight record with the ship as a `StorageChunk`, hyperspace ephemeral; dirty-hook at
  content-divergence; stations are ships; offline transit by `spaceTransitOfflineProgress`.
- **E (discovery):** per-entry XML visibility flag; hybrid telescope region scan + on-arrival per-body
  auto-scan; known addresses in memory-crystal items held by a required navigation computer; a jump needs
  BOTH a known current AND a known target; info tiered by proximity; crystal-derived knowledge over an
  innate home-system floor, `planetsMustBeDiscovered` as the research master-switch (OFF =
  instant-on-observe), INV-SCAN bounding every scan, tier-1 never touching crystals.
- **F (pool):** §9.

**OPEN (residual sub-details only):**
- Foreign-generator ore overlap (§4).
- Star-charts as tradeable items — an optional later hook under the finding mechanic.
- Encounter / NPC / traffic POPULATION — nothing spawns encounters yet; the accepted seam cost buys
  nothing in single-player until this is decided (shared with `space-model.md`).

**REJECTED (reason attached):**
- A coordinate stored on the body / system — the `UniverseRegistry` owns placement; systems stay
  location-agnostic.
- "All systems known" / a finite fixed POI set — the galaxy is infinite / procedural; only the authored
  set is default-visible, the rest is revealed by finding mechanics.
- A permanent dim per cell — throws away the finite slot pool; unbounded cells cannot be permanent dims.
- An explicit galaxy addressing tier — a galaxy is a derived seated object, not an address level.

## 11. Cross-refs

- [`space-model.md`](./space-model.md) — Layer 2 (bubbles, slot pool, transit hosting, the crossing). This
  doc references that vocabulary and does not duplicate it.
- The Layer-1→Layer-2 seam is the minting of a dimension for a procedural body so it becomes a descent
  target; pin-on-touch, not id-minting, is the divergence mechanism — the synthetic negative star id is a
  durable save key.
- Tier-1 chemical rockets have a separate intra-system progression (direct orbit→dim selector, never
  enter the grid).
- Ship-mechanic surfaces that consume these decisions: jump trigger / fuel / atmosphere-cost UX, the
  client transit render, the pre-implementation performance spikes (`space-model.md` §11).
