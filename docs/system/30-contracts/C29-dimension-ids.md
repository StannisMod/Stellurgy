---
id: C29
covers: what a dimension id MEANS — one id names one body everywhere it is held, a pack's stated id is honoured, an allocated id never takes a stated one, a refused registration is loud and leaves nothing behind, and an id outlives the server
confidence: DIMID-1..4 pinned on the planetDefs.xml load path; DIMID-5 read at source, not pinned
owner-subsystem: dimension-planets (registration, MECH-DIM-06) with util-core/xml-loaders (allocation, MECH-XML-03)
see-also: [C3 (registry NAMES — not ids), C1 (NBT keys, incl. temp.dat's dimList), C15 (cell address — what a body's durable NAME is)]
---

# C29 — Dimension ids, clauses DIMID-1..DIMID-5

Says what an id is supposed to mean, so that a fix to dimension-id allocation can name the clause it
restores. The failure modes known to violate it are listed under **Failure modes** below.

## Terms

- **dimension id** — the integer a body is registered under. Forge keys a WORLD by it; Stellurgy
  keys the BODY's properties by it.
- **holder** — anything that keys bodies by id. Three are in scope: the dimension registry
  (`DimensionManager.dimensionList`, `DimensionManager.java:440-452` `[V]`), a star's body map
  (`StellarBody#addPlanet`, `planets.put(planet.getId(), planet)`, `StellarBody.java:245-249` `[V]`),
  and a parent's child list (`DimensionProperties#addChildPlanet`, `DimensionProperties.java:1313`
  `[V]`). The universe registry's recorded names (`namesByDim`) are a fourth, outside the load path.
- **stated id** — a `DIMID` attribute a body's own `<planet>` element carries in planetDefs.xml.
- **allocated id** — an id the game chooses for a body that states none.
- **refusal** — a registration that does not take place because the id is already held
  (`registerDimNoUpdate` answering `false`, `DimensionManager.java:440` `[V]`).

## Clauses

- **DIMID-1 (one id, one body, one answer)** A dimension id names at most one body, and every holder
  that keys bodies by id names the SAME body under it. Two holders disagreeing is not a lesser
  failure than a duplicate: it is two bodies under one id, each visible to a different reader.
  **Falsifiable:** for any id, the registry's body and the star's body under that id are one object
  (by name, from outside).
- **DIMID-2 (a stated id is honoured)** A body whose element states a `DIMID` is registered under
  that id — wherever in the file it is written, and whatever any other body states or is given —
  unless an EARLIER body of the same file stated the same id (then DIMID-4 decides).
- **DIMID-3 (an allocated id is never a stated one)** The loader gives an id only to a body that
  states none, and never one that any body of the same file states, one it has already given in the
  same parse, or one Forge or the registry already holds. The whole file is known before any id is
  chosen; the ORDER of elements cannot change which ids are free.
- **DIMID-4 (a refusal is loud and leaves nothing behind)** When a registration is refused because its
  id is held, the refused body is bound to NO holder — not to its star, and its state is not poured
  into the body that holds the id — and the refusal is logged at WARN naming the id and BOTH bodies.
  The world goes on loading. **Ruling 2026-10-02:** two equal stated `DIMID`s — the earlier in the
  file holds the id, the later is refused under this clause; failing the whole load was rejected
  (an unreasonable file stays loadable: what is merely absurd stays possible and costs what it costs).
- **DIMID-5 (an id outlives the server)** Once a world has been saved, every body it holds keeps its id
  across a restart. **Ruling 2026-10-02:** no promise is made about the id of a body that states none
  when the PACK's file is edited before the world's first save; the promise starts at the save.

## Status at a glance

| clause | state | evidence |
|---|---|---|
| DIMID-1 | `[T]` on the planetDefs.xml load path | `PlanetDefsAuthoringTest#aPlanetThatStatesNoDimensionIsGivenAFreeOne` (`Sol.bodiesUnder2`, `ownerOf2`); `#aSecondBodyStatingAHeldIdIsRefusedAndNotBoundToItsStar` |
| DIMID-2 | `[T]` | `#aPlanetThatStatesNoDimensionIsGivenAFreeOne` (`StatesTheFirstFreeId.dims=[2]`) |
| DIMID-3 | `[T]` | the same method (`itsIdTakenByAnother=false`); `XMLPlanetLoader#claimStatedDims` + `#allocateUnstatedDim` |
| DIMID-4 | `[T]` for the star binding and the load continuing; the WARN is `[V]` only (a log line is never a test's source) | `#aSecondBodyStatingAHeldIdIsRefusedAndNotBoundToItsStar`; `DimensionManager#createAndLoadDimensions` refusal branch |
| DIMID-5 | `[V]`, not pinned — no restart test reads an allocated id back | see below |

**DIMID-5, what already holds it** `[V]`: the world's own planetDefs.xml is written on every save with a
`DIMID` attribute on EVERY body a star lists, moons included (`XMLPlanetLoader#writePlanet`,
`nodePlanet.setAttribute(ATTR_DIMID, Integer.toString(properties.getId()))`), and on load that
world-local copy is read in preference to the pack's config (`DimensionManager#createAndLoadDimensions`,
`if (!file.exists() || resetFromXml)`); `temp.dat` keys every body's NBT by its id
(`DimensionManager#saveDimensions`, `dimListnbt.setTag(dimSet.getKey().toString(), dimNbt)`). So after the
first save an allocated id is pinned by the file itself. **What it does NOT cover**: a world that never
saved (a crash before the first save re-parses the pack's file — deterministic for an unchanged file),
`resetFromXml`, and a body no star lists (the writer walks `star.getPlanets()`, so such a body is not
written back at all).

## Failure modes (a fix names its clause)

| what | clause | state |
|---|---|---|
| a body without `DIMID` is given an id a LATER body states; the stated body is refused silently and the star keeps it while the registry keeps the other | DIMID-1, DIMID-2, DIMID-3, DIMID-4 | pinned on the planetDefs.xml load path |
| a space-slot pool takes an id a catalogued (surface-less) body already holds, so one id names a body and an empty slot world | DIMID-1 (across allocators) | `SpaceSlotPool#nextFreeDimensionId` asks Stellurgy's registry as well as Forge's `[V]` — not pinned |
| a deleted body's id, reissued, inherits the old body's recorded name | DIMID-1 (a name record is a holder) | not re-derived here |
| slot dimension ids minted per JVM, so a restored ship's stored slot id names no live slot | DIMID-5 (for slot ids) | not re-derived here |

## Coverage gaps

- DIMID-5 has no restart test that reads an allocated id back on the second boot.
- DIMID-1 outside the load path: `PlanetGenerateCommand` (`getNextFreeDim(2)`), `PlanetRealizer`
  (`getNextFreeDim(getDimOffset())`) and `SpaceSlotPool#nextFreeDimensionId` each allocate on their own;
  nothing pins that they cannot collide with each other or with a body realized later.
- A refused body that is a MOON stays in its parent's child list by id (`addChildPlanet` links at parse
  time); DIMID-4's "bound to no holder" is pinned for the star only.
