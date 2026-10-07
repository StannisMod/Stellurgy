---
id: C24
covers: what a celestial object IS, and what the place around it offers — classification as composition, environment as a derived query
confidence: RULED DESIGN-STAGE 2026-09-02 — nothing here is built. `StellarBody` keeps no classification at all today, and there is no environment query
owner-subsystem: universe (StellarBody, GalaxyGenConfig, DimensionProperties) + a new environment layer
see-also: [C21 (atmosphere — the same "one model, predicates over it" shape, and its EXTENT clause), C14 (which sky a world draws), C15 (cell address vs position), C19 (reference frames)]
---

# C24 — A celestial body and its environment, clauses BODY-1..BODY-18

Ratified 2026-09-02: the composition axes, and the environment as a query returning one bundle, with
the descriptor rules below.

**Why this is a contract and not a subsystem doc.** A subsystem doc describes **built** mechanics, with
`file:line` and `[V]/[T]/[A]`, and is updated in the commit that changes the code. None of this is built.
The design lives here before implementation — the shape **C21** uses.

## What is wrong today, measured

- `GalaxyGenConfig.StarType` is a **weighted generation archetype** — `(temperature, minSize, maxSize,
  weight)` — **consumed at draw time and discarded** (`GalaxyGenConfig.java`).
- `StellarBody` keeps `mass`, `size`, `temperature`, `orbitalDistance`, `baseTheta`, `planets`, `subStars`,
  `parentStar`, `maxRetinueBodies`, position — **and `isBlackHole`, a boolean**. No spectral class, no
  luminosity class, no evolutionary state, no peculiarity, no compact-object state.
- There is **no environment layer** at all; a design of three nested spheres around a star (unbuilt)
  is the nearest thing.

So the physical core is already right — mass, size, temperature, and derivations that read them — and the
layers **above** and **below** it are missing.

## The classification — a composition, not a type

- **BODY-1** `[A]` A celestial object's identity is a **composition over independent axes**, never a single
  type. The axes are: physical family · spectral classification · luminosity class · evolutionary state ·
  peculiarities · compact-object state · system relation · variability. No axis is inferable from another.
- **BODY-2** `[A]` **Exactly one axis is a closed enum: the physical family** — prestellar core, protostar,
  pre-main-sequence, hydrogen-burning, stripped-helium, brown dwarf, white dwarf, neutron star, black hole.
  Every other axis is open and data-declared.
- **BODY-3** `[A]` **Peculiarities, compact-object state and variability are SETS.** Nature is not exclusive
  here, and the invariant that falsifies any enum-shaped regression is that **a pulsar may also be a
  magnetar**.
- **BODY-4** `[A]` **A spectral class never determines a physical family.** `L` spans very-low-mass stars
  and brown dwarfs; `M` overlaps hot brown dwarfs. Reading a family off a letter is a violation.
- **BODY-5** `[A]` **System relation lives on the SYSTEM, not on a star.** Multiplicity, mass transfer,
  Roche-lobe overflow, accretion discs and the interacting families (CV, nova, LMXB, HMXB, microquasar) are
  properties of a relation between bodies.
- **BODY-6** `[A]` **A black hole is a compact object, not a star** — it sits in the family axis, and
  nothing may read a boolean beside a star to find one.
- **BODY-7** `[A]` **Speculative objects are not generated.** Black dwarf, quark/strange star, boson star,
  gravastar, wormhole, quasi-star may exist as addon extension points and never as normal generated classes.

## What classification is FOR — and what it is not

- **BODY-8** `[A]` **No mechanic reads a classification axis.** Axes serve generation, catalogue, UI, lore,
  render, discovery, data authoring, population constraints and default parameter distributions. Mechanics
  read **physical parameters** and **the local environment**.
- **BODY-9** `[A]` **A kind carries its relations.** A family knows how to light its system; no derivation
  site may ask what kind it has. `isBlackHole`'s disappearance is structural, not a rename.
- **BODY-10** `[A]` **Classification SEEDS parameters and is then not consulted again.** Generation draws
  from the axes; everything downstream reads the parameters the draw produced.

## The environment — a query, not a store

- **BODY-11** `[A]` The local environment is a **query**: `sample(position, time)` returns **one bundle** —
  gravity/tidal, magnetic field, plasma, radiation, rotation/accretion. Not one call per family.
- **BODY-12** `[A]` **Nothing is voxelised.** Fields are analytic or parametric, transients are sparse
  events, derived state is cached. No grid over space, no MHD solver, no per-tick integration of a field
  nobody is reading.
- **BODY-13** `[A]` **The environment has ONE representation.** Nothing else may store a second answer to a
  question the sample can answer — the clause `C21`'s `CON-C21-01` exists for, applied one layer out.
  A stellar-geometry model (nested spheres around a star) is an **instance** of this query, never a
  parallel model.

## Descriptors and site predicates (§18)

- **BODY-14** `[A]` **A descriptor is DERIVED** from the sample or from the body's own parameters, and is
  **never stored**. Time statistics — field variability, reconnection activity — are **parameters of the
  body's model**, not accumulated history.
- **BODY-15** `[A]` **A site is a CONJUNCTION over descriptor ranges**, never a body type. A type test is a
  proxy for a conjunction nobody wrote down, and it fails the moment another object reproduces the
  conditions. The consequence is required, not incidental: **a predicate must be able to find a site nobody
  authored.**
- **BODY-16** `[A]` **A predicate is evaluated against TRUTH for a mechanic and against KNOWLEDGE for a
  readout.** One predicate, two inputs; never two predicate sets. A UI may not read the true environment.
- **BODY-17** `[A]` **A measured QUANTITY and a derived DESCRIPTOR are different vocabularies and never
  merge.** A quantity is what an instrument reads and what a carrier holds (`DataStorage.DataType`, extended —
  one vocabulary, no second scanner); a descriptor is computed from the sample or from the
  body's parameters and is never stored (BODY-14). **Anything derivable from two quantities is a descriptor**
  — surface gravity, bulk density, and a thermal *excess*, which is a residual against a prediction and
  therefore an anomaly rather than a reading. The test: delete the instrument that would read it; if no
  information is lost, it was a descriptor wearing a quantity's name.
- **BODY-18** `[A]` **The first descriptor set, ratified with units.** Twelve, from the original shield/field brief's
  §18. The set is **open** — unlike BODY-2's closed family axis, a later descriptor extends it, and a
  descriptor's absence from this list is not a claim that nature lacks it.

  | descriptor | unit |
  |---|---|
  | tidal gradient | s⁻² |
  | magnetic field | T |
  | field variability | dimensionless (relative deviation over the body's characteristic time) |
  | plasma density | cm⁻³ |
  | plasma flow | km/s |
  | radiation flux | W/m² |
  | compactness | dimensionless, `GM/(rc²)` |
  | rotation rate | Hz |
  | frame-dragging strength | dimensionless spin `a* = cJ/(GM²)`, 0..1 |
  | accretion state | dimensionless Eddington ratio `L/L_Edd` |
  | stellar-wind power | M☉/yr (mass-loss rate; a power in W is derived from it) |
  | reconnection activity | dimensionless reconnection rate (fraction of the Alfvén speed) |

  **Units are ratified here; VALUES are not.** Each
  shipped number is a reading stated in its own unit, defaulted into a tunable, with its derivation
  attached — and a number stated in cells, ticks or blocks instead of a physical unit is the failure
  this rule prevents.

## Status — ratified DESIGN-STAGE 2026-09-02

**This contract is the authority; the code is a snapshot of how much of it has been written**
(00-methodology, status rows). Nothing is built yet, so the table measures distance rather than reporting defects. What matters is
which rows the CURRENT code answers differently, since those are the shapes a half-implementation would
re-create:

| clause | state today |
|---|---|
| BODY-1..7 | **not written** — there is no classification layer at all; the archetype is discarded at generation |
| BODY-6, BODY-9 | **not written, and today's code answers differently**: `isBlackHole` is a boolean on `StellarBody` read at many call sites, and the light derivation reads it (`lightMultiplier *= 0.25`, with a comment admitting it is a substitute). Those call sites are the size of the rewrite, not a count of breaches |
| BODY-8, BODY-10 | **held by accident** — mechanics read mass/size/temperature because nothing else exists to read. The clause must survive the arrival of axes |
| BODY-11..13 | **not written**; the stellar-geometry design is the first intended provider |
| BODY-14..16 | **not written** |

**The shape this contract shares with `C21`**: C21 states what an atmosphere's EXTENT is (the holder's), so
a reader cannot fill the silence with an assumption. BODY-11 states the query's shape and BODY-13 its
uniqueness for the same reason.