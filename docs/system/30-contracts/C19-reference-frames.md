---
id: C19
covers: which body a craft's velocity is measured AGAINST, how that choice changes, and what a change of it may and may not alter
confidence: DESIGN — the craft-side frame is not implemented; the zone-sphere crossing half of FRAME-2 and the position continuity of FRAME-7 are built and pinned. Tags mark what is verified about the code, not about this contract.
owner-subsystem: space (SpaceSubsystem, FreeFlightPhysics, TileAdvancedFlightComputer)
see-also: [C15 (a cell's address and frame — this contract says which frame a SHIP is in; C15 says which frame a BODY rides), C16 (the space clock — a frame is evaluated AT a tick and this says whose), C10 (ship stats), metric-boundary (CHART vs WORLD — an SOI radius is a CHART length)]
---

# C19 — Reference frames, clauses FRAME-1..FRAME-11

A ship placed at its planet's NAME is found 1.41 million km from the planet (measured). Placing it
correctly buys one correct arrival and not a stable one, because **nothing carries a parked craft
along with the body it is parked at** unless the craft has a frame.

The maintainer's framing, which this document ratifies: *"в настоящем космосе нет скорости
относительно пространства — есть скорость относительно тела"*. Flight Assist HOLDS a velocity, so it
must hold it against something; held against the world it holds it against nothing, and the planet
leaves.

**The clause that makes the rest cheap: with the assist OFF there is no defect at all.** Newtonian
motion is already frame-independent — nobody is adjusting the craft's velocity, so a frame change
alters what the READOUT says and not what the ship does. The whole contract is therefore about (a)
what the assist holds against and (b) what a pilot is told.

## Terms

- **reference frame** — a body plus its motion: an origin that moves. The code has this
  shape — `CellFrame.of(AbsolutePos origin, BodyEphemeris law)`, which a `SystemBody` rides and
  `SystemBody.absoluteAt(tick)` evaluates (`SystemBody.java:277`) `[V]`. What has no frame is a
  CRAFT: a ship's position in the ledger is a bare `GalacticCoord`, a static point `[V]`.
- **sphere of influence (SOI)** — the region in which a body, rather than its primary, is the one a
  craft's motion is naturally described against. Radius `r_SOI = a·(m/M)^(2/5)` (Laplace); the Hill
  radius `a·(m/3M)^(1/3)` is the alternative and either is acceptable, the choice being a tuning
  decision and not a contract one.
- **frame handover** — the instant a craft's current frame changes because it crossed an SOI
  boundary.
- **setpoint** — the velocity Flight Assist holds, capped at
  `FreeFlightPhysics.FA_SETPOINT_MAX_SPEED = 3.0` blocks/tick (`FreeFlightPhysics.java:75`) `[V]`.
  With the assist off there is no ceiling and the craft accelerates for as long as it burns, by that
  field's own javadoc `[V]`.

## Clauses

- **FRAME-1 (a craft is always in exactly one frame)** `[A][SYS]` At every tick a craft has one reference
  frame, named by a body. There is no "no frame": outside every planetary SOI the frame is the star's,
  and outside a system's it is the system barycentre. "Deep space" is a frame like any other, and a
  craft may never be in two. FOR: FRAME-5.

- **FRAME-2 (the frame is the INNERMOST SOI containing the craft)** `[A][SYS]` Frames nest — a moon's
  inside its planet's inside its star's. Membership is decided by geometry at a tick and by nothing
  else: not by cell membership (`CON-MET-02` forbids that for bodies and it holds here), not
  by where the craft launched from, not by what it is aimed at. FOR: FRAME-5.

  **Built: the zone-sphere crossing; not built: the craft's own frame.** Frames genuinely nest
  (`CellFrame.within`), a moon's cell rides the moon, and the zone lattice is coarse enough that a
  craft one descent shell out from Luna is inside Luna's own cell: **zero drift over 20 000 ticks**
  where a craft addressed in its parent's frame is left **294 996** blocks behind, pinned by
  `ParkedCraftKeepsStationTest.aCraftParkedOneDescentShellOutFromAMoonKeepsStationWithIt`.

  **The OUTWARD half**: a craft that has left a body's sphere is carried out of its zone at the
  sphere, not at the cube face (C15 ADDR-10's sphere form), and the lattice is sized so the sphere is
  inscribed in the cell (C15 ADDR-19) — which is what makes any sphere rule expressible at all, since
  the cube would otherwise fire first.

  **The INWARD half**: a craft inside a child's sphere is re-addressed into that child's own zone,
  innermost sphere tested first, so capture is decided by the sphere on both sides and not by
  whichever cube face the craft's address fell in. **Including from a GALACTIC cell**: a craft
  addressed in a planet's own galactic cell — every craft fresh from the planet — is asked the inward
  question against that planet's children (`zoneMembershipIn`). Pinned by
  `ZoneCrossingAimsAtTheRightCellTest` (both the capture and its control) and, on a live craft, by
  `VSShipZoneSphereCrossingTest.aCraftFreshFromThePlanetFlownIntoItsMoonsSphereIsCarriedIntoTheMoonsZone`.

  **Where the joints are.** The geometry in `CellSeam` and `ZoneScale` is plain arithmetic; the
  places that can go wrong are the joint between that arithmetic and the universe that names cells.
  `ZoneCrossingAimsAtTheRightCellTest` (integration) pins four of them:
  - the crossing reads the lattice the naming pass recorded instead of sizing its OWN (7 397 280
    blocks against Luna's 1 849 294 would arrive in the cell holding EARTH);
  - reading any cell two zones deep must not throw out of the tick (an eagerly-built static frame for
    a key that cannot state one);
  - a moon's sphere is measured against its PLANET, not the star (264 731 blocks — 66 183 km, the
    published value — rather than 638 428);
  - the in-cell offset is measured from the origin the destination cell actually rides (its BODY),
    not from the lattice slot (which displaced the craft **321 994** blocks, a FRAME-7 violation).

  **Proven in a world, BOTH WAYS** `[T]` — `VSShipZoneSphereCrossingTest` (it places and moves the
  craft by probe, so it is a mechanics test, not an end-to-end one), on a live craft and Luna (sphere
  264 731 blocks against Earth). The INWARD crossing on a player's own path — a jump beside Luna,
  carried into her zone, seated, keeping station — is `M1PlanetToPlanetMilestoneE2ETest` leg 7b:
  - OUT: settled in Luna's zone at half the radius, moved to `R·(1 + 1/1000)`, carried — named by
    Luna's own cell in Earth's lattice (`19_0_0.1_0_0`) at exactly the offset it was decided on
    (264 996, 0, 0 → 264 996, 0, 0).
  - IN: settled in Luna's own cell of Earth's lattice at `1.5·R`, moved to half the radius,
    carried — named in Luna's zone (`19_0_0.1_0_0.0_0_0`) at exactly the offset it was decided on.
  - Both: the controller leaves the craft alone BEFORE (the control) and AFTER (the hysteresis), and
    the arrived hull stands within 1.4 blocks of its address after the physics takes it back.
  Each of `hasLeftZone` (both ways), `hasEnteredZone`, `addressIn` and `latticeOf` is exercised by an
  inversion in the test. **Not covered**: the trigger wiring in
  `TileAdvancedFlightComputer`, which the carry is driven around — though the same class measured the
  computer carrying a craft through two spheres by itself in the moment after a paste.

  A coarser lattice does not put a planet's inner moons back into one address: the naming bound is
  computed against the realized span, not the unclamped sphere (C15 ADDR-19), so "coarser" and "still
  names the moons apart" do not conflict.

- **FRAME-3 (the boundary is a MASS ratio and an orbital radius — never a radius, never a surface
  gravity)** `[A][SYS]` `r_SOI` depends on `m/M` and on `a`. A body's own radius does not appear in it, and
  surface gravity `g = GM/R²` conflates the two inputs, so two worlds with equal `g` can have SOIs
  differing by orders of magnitude. Both inputs exist per body: mass from `setBulk`, `a` from
  `orbitalDistance`; a body without bulk has no SOI. Reference values, converted at
  `METRES_PER_CHART_BLOCK = 250` (`AstronomicalBodyHelper.java:40`) `[V]`: FOR: FRAME-5.

  | body | `r_SOI` | chart blocks |
  |---|---|---|
  | Luna | 66 000 km | 264 000 |
  | Earth | 926 000 km | 3 700 000 |
  | Jupiter | 48.2 M km | 193 000 000 |

- **FRAME-4 (the assist's setpoint is IN the current frame)** `[A][BEH]` Flight Assist holds the commanded
  velocity relative to the craft's frame. A craft that launched from a world and asked for zero is
  asking to be *stationary with respect to that world*, which is what a pilot means and what an orbit
  is.

- **FRAME-5 (zero setpoint means CO-MOVING, and that is what stops the orphaning)** `[A][BEH]` Under
  FRAME-4, a parked ship keeps station with its body for as long as it is in that body's frame,
  without anything "carrying" it and without a special parked state.

- **FRAME-6 (the readout names its frame)** `[A][BEH]` Any velocity or range shown to a pilot states which
  body it is measured against. A speed with no named frame is not a speed; neither is a number whose
  unit is wrong.

- **FRAME-7 (a handover changes the DESCRIPTION and never the state)** `[A][BEH]` At a handover the
  craft's position and momentum are continuous. Nothing is re-aimed, nothing is scaled, no impulse is
  applied. What changes is the number on the screen — and that jump is TRUE, being the difference
  between two frames' velocities, not an artefact to be smoothed away.

  **Position continuity is measured** `[T]`, both ways across a sphere, to within one block —
  `ZoneCrossingAimsAtTheRightCellTest`, and on a live craft in a world by
  `VSShipZoneSphereCrossingTest`. A re-address is a renaming, so the
  offset is measured from the origin the destination cell actually rides — one rule, applied at all
  three destination branches (`SpaceSubsystem.addressIn`).

- **FRAME-8 (Newtonian flight is frame-independent)** `[A][BEH]` With the assist off, a handover has no
  effect on the trajectory whatever; only FRAME-6's label changes. This is a statement about what the
  contract may NOT do: no clause here is permitted to make raw Newtonian motion depend on which frame
  the craft is in, and nothing clamps inside a law documented as raw Newtonian.

- **FRAME-9 (the assist re-expresses its setpoint at a handover; it does not re-fly the craft)** `[A][BEH]`
  When the frame changes with the assist ON, the setpoint is converted into the new frame so the
  craft's motion is unchanged at that instant. It is then held against the NEW frame, which is what
  makes leaving a planet's SOI a real event: the same held command now means something else, and the
  pilot must re-issue it if he wanted the old meaning. **A handover may never be the moment the assist
  silently accelerates a craft.**

- **FRAME-10 (a frame is DERIVED, never stored as truth)** `[A][SYS]` The current frame is a function of
  position and the body ephemerides at a tick, so it is recomputed and never persisted as an
  independent fact — a stored frame would be a second source of truth for something already
  determined. What may be persisted is the SETPOINT, and it is
  persisted *with the name of the frame it was expressed in*, or it means nothing after a reload. FOR: FRAME-7.

- **FRAME-11 (a handover is EVALUATED on the space clock)** `[A][SYS]` SOI membership depends on where the
  bodies are, which is a question with no answer except at a tick; per C16 CLOCK-1 that tick is the
  space clock. A handover decided on a world clock would fire at different times in different
  dimensions. FOR: CLOCK-2.

## What exists, and what does not

| piece | state |
|---|---|
| a frame as origin + motion | **exists** — `CellFrame`, `BodyEphemeris`, `SystemBody.absoluteAt` `[V]` |
| a body's mass and radius | **exists** |
| a body's orbital radius | **exists** (`orbitalDistance`) |
| a craft's frame | **does not exist** — a ship is a static `GalacticCoord` `[V]` |
| a velocity's frame | **does not exist** — the assist holds against the world `[V]` |

## Related

- [C15](./C15-cell-address-and-frame.md) — ADDR-6 (every cell of a zone rides its body) is the
  body-side statement of the same idea; this contract is its craft-side counterpart.
- [metric-boundary](../metric-boundary.md) — an SOI radius is a CHART length.
