# Metric boundary — CHART and WORLD

**Status:** the boundary was ratified by the maintainer (2026-08-14) and the constant exists in code:
`AstronomicalBodyHelper.METRES_PER_CHART_BLOCK = 250` (`AstronomicalBodyHelper.java:40`) `[V]`;
`EARTH_RADIUS_BLOCKS`, `BLOCKS_PER_AU` and `BLOCKS_PER_LIGHT_YEAR` are all derived from it. The boundary
has to be named because code can otherwise add a quantity from one side to a quantity from the other;
its DISCIPLINE (every consumer converting at the shell) is carried by the consumers below, not by the
constant.

This doc owns ONE thing: **which metric a length is in, and where a length changes metric.** It does not
own what exists (that is [universe-model](./universe-model.md)) or how a location becomes loadable (that
is [space-model](./space-model.md)).

**Consumers that convert:** the cell sky's range label converts before printing
(`ApparentSize.formatChartDistance`, `ApparentSize.java:98`); printing a chart-block count under metric
unit names would understate every range a pilot reads by 250×. `formatDistance` names metres in its
signature, so the two cannot be confused at a call site.

---

## The two metrics

**CHART** — where bodies are placed, sized, separated and drawn. One **chart block = `D` metres**,
`D = 250`. This includes the interior of every space cell: a pilot flying between planets flies in
CHART. Nothing in CHART is a block anyone stands on.

**WORLD** — any loaded world a player can touch: a VS ship's subspace, a planet dimension, a station's
interior. One **world block = 1 metre**, and it IS a Minecraft block. The *world frame* of C8 / C11 is a
WORLD-metric frame — that term is referenced here, not redefined.

### Why the cell interior is CHART and not WORLD `[A]`

If a cell's interior were metre-scale, a cell (32·10⁶ blocks) would be 32 000 km across and a body one AU
away would sit **4.7 million cells** off. Read as CHART, the cell is `32·10⁶ × 250 m = 8·10⁹ m` and one
AU is about 19 cells — a distance a ship can be flown across. The metre reading is not merely
inconvenient; it makes interplanetary flight unaddressable. The argument is scale-free: it holds at any
cell size.

## MECH-MET-01 — the boundary is materialization, never distance `[V]`

A body is CHART data until a craft enters its descent shell, at which point a dimension is minted or
loaded and the body becomes WORLD. `DescentShell.radiusAround` (`DescentShell.java:48`) answers in CHART
blocks; everything inside the minted dimension is WORLD blocks. **Crossing that shell is the only place
a length changes metric.**

`DescentShell` is the general CHART→WORLD transition, not only "landing on a planet"; belt mining needs
it too (INV-MET-03).

## MECH-MET-02 — takeoff and descent cross ONE surface, read in two metrics `[V]`

The shell is crossed at the same place in both directions. From outside, a descent fires when a craft is
within `DescentShell.radiusAround(body)` CHART blocks of the body (`DescentController.nearestDescentTarget`;
each body its own shell, never one flat radius). From inside, a ship's takeoff fires above
`DimensionProperties.orbitLine()` world Y, which is the planet file's `<orbitHeight>` when stated and
otherwise `DescentShell.orbitLineWorldY(radius)`: the shell's depth above the surface
(`ATMOSPHERE_FRACTION` of the radius) times `METRES_PER_CHART_BLOCK`, counted from Y 0 and never inside
the block band (floor `TerrainHeightFinder.MAX_BUILD_Y`). Earth 100 000, Luna about 27 300. There is no
global orbit-height config key: tier-1 rockets, the elevator capsule and the HUD read the same line
through `DimensionManager.transferLineOf` (the station dimension answers its clearance). A world with no
radius and no stated line has NO line — takeoff and launch refuse there and say so. Pinned by
`DescentShellTest.theTakeoffLineIsTheDescentShellSeenFromInsideTheWorld`,
`DescentControllerTest.eachBodyIsEnteredAtItsOwnShell` and
`VSShipEntryTest.aWorldsTakeoffLineIsItsBodysAtmosphereUnlessItsFileStatesOne`.

## INV-MET-01 — a length carries its metric, and the two are never added `[V]`

A galactic position is `sector·CELL + local`: `sector·CELL` places a cell among the stars — CHART — and
`local` is a position inside that cell, which is CHART too, so the sum is legal **only because this
document says so**; no other code states it.

`GalacticCoord` therefore exposes no materialised absolute: summing `sector × CELL` into one `long`
overflows at 2.9·10¹¹ while the sector itself reaches 9.2·10¹⁸ (the range of sector + local is
7.8·10¹² light years, the observable universe 168 times over, so no further addressing tier is needed).
Distances come from `(Δsector, Δlocal)` — `AbsolutePos.distanceTo`, which never clamps; the one place a
separation IS three block `long`s is `BlockDelta`, which reports `isSaturated()` (universe-model §3).

## INV-MET-02 — `CELL` is two quantities wearing one name `[V]`

`GalacticCoord.CELL = 32_000_000` is used as **the spacing between cell origins** (CHART) and as **the
size of a cell's loaded world**. They are equal today and need not stay equal; two constants that merely
happen to be equal are never merged.

## INV-MET-03 — anything mined, built or walked on is WORLD, therefore materialized `[A]`

A block that is 250 m on a side cannot be mined. So an asteroid a player works is not CHART content
placed in a cell; it is a WORLD that is entered through MECH-MET-01, exactly as a planet is. This is a
design consequence, not an implementation choice.

## A block-built craft is `D`× oversize in CHART, and that is inherent `[A]`

A craft's geometry is WORLD (its blocks are metres — a player walks on them), but the craft is *placed*
in a CHART cell. A 100-block craft therefore occupies 100 chart blocks = **25 km**. Any block-built
craft in a `D`-scaled chart is `D`× oversize, whatever `D` is. Beside Earth (25 484 chart blocks of
radius) it is unremarkable; beside a 1 km asteroid (4 chart blocks) the craft swallows it — which is
INV-MET-03's argument from the other end.

## CON-MET-01 — exaggeration lives in the renderer, never in the placement `[A]`

If bodies read too small, multiply their radius **at draw time**. Never introduce a second placement
scale: placement feeds physics (equilibrium temperature, insolation, period), and one number meaning two
distances desynchronises silently. A render multiplier is reversible and cannot desynchronise anything.

**A single `D` preserves every angle exactly** — numerator and denominator divide by the same number —
so one scale yields the true sky: the Sun 0.53° across at 1 AU, Jupiter 47″ at opposition, Earth 17.6″
from 1 AU. Telescopes, scans and the navigation computer are what make that sky playable.

## CON-MET-02 — proximity is decided by GEOMETRY, never by cell membership `[V]`

A body in CHART is exactly `(name, centre at tick t, radius)`. Drawing it, testing proximity, testing
whether a craft is inside it, and triggering its descent shell all need those three and nothing else —
in particular none of them needs the body to be in the observer's cell.

**So no consumer may filter candidate bodies by cell name.** The tempting shortcut — *"I am in cell X,
so only bodies named in X can matter"* — is wrong even for small bodies, because a craft near a cell
face is already closer to a neighbour's body than to its own. A body larger than a cell only makes the
error visible.

Two facts bound how far a consumer must look, and they are what make a system-wide feed sufficient:

| | blocks | in cells (`CELL = 32·10⁶`) |
|---|---|---|
| largest known star (~2 150 R☉ ≈ 10 AU) | 6·10⁹ | **187 cells** |
| a system's named-body reach (`r_max`, 50 AU) | 3·10¹⁰ | 937 cells |
| distance to the next star | 1.6·10¹⁴ | 5·10⁶ cells |

A cell count is only a number in the units of a cell; the blocks column is metric and does not move with
the cell, which is why the conclusions rest on it.

**A body may span many CELLS; it never leaves its SYSTEM.** A hypergiant is a fifth of its neighbourhood
and 0.004 % of the way to the next star. So feeding a consumer the whole system is always enough, and
feeding it one cell is always wrong. The cell sky's feed already does this
(`UniverseRegistry.java:748` — *"the whole SYSTEM, never just the cell"*).

**Named versus nameless matter.** `ADDR-3` requires that no two systems' neighbourhoods overlap, and
attribution (`anchorAt`) rests on it. A system's **named** bodies — planets, moons, belts — stay inside
`r_max` (maintainer ruling, 2026-08-14), while **diffuse, nameless** matter (an Oort cloud) may reach
past it and overlap a neighbour's. Both hold because attribution reads names, not matter.

## One metric for orbit and chart `[V]`

`D = 250` implies **1 AU = 5.98·10⁸ blocks**. The orbit distance unit is derived from the metric (100 km,
`BLOCKS_PER_DISTANCE_UNIT = 100 000 / METRES_PER_CHART_BLOCK`, `AstronomicalBodyHelper.java:113`; C15
ADDR-20), so an orbit and the chart agree about how long an AU is. A system extent defined as a fraction
of the interstellar step would force an orbit scale that disagrees with the metric; the derived unit
removes the cause.

---

## Related

- [space-model](./space-model.md) — Layer 2: cells, bubbles, transit. Owns *how a location loads*, not what a length means.
- [universe-model](./universe-model.md) — Layer 1: what exists and where.
- [C15](./30-contracts/C15-cell-address-and-frame.md) — a cell's address is a durable NAME; ADDR-9 (distance exists only at a tick) holds only within one metric.
- C8 / C11 — subspace / world / ship frames; all three are WORLD-metric.
