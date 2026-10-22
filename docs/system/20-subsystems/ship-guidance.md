---
id: ship-guidance
owns: [ship/guidance/]
entrypoints: [TileAdvancedFlightComputer#update→flyMotionIntent, TileAdvancedFlightComputer#issueMotion, TileAdvancedFlightComputer#setPilotInput]
depends-on: [ship-flight-model, space, integration-vs]
depended-by: [none]
contracts: [C1, C5, C7, C9, C10, C11]
confidence: medium — the laws are unit-pinned, the in-world flights are pinned by mechanics tests on void space cells and one overworld plot
---
## Purpose

Flies a tier-2 craft to what an ORDER names, with nobody at the stick: a motion slot on the flight
computer holds one motion intent, and guidance turns it, tick by tick, into the same `FlightCommand`
every other producer on that computer publishes. An order names a THING — a ship, or a point on the
surface of the planet the craft is on — never a bare point of space. Nothing issues an intent from a
player's screen yet; the slot is filled through the flight computer's own entry.

## Responsibility boundary

Owns: the motion slot and its states, the two motion verbs (`GO_TO`, `HOLD`), the two target kinds and
how each is placed in the craft's world, the approach law (which attitude, how fast, when to turn over),
the arrival, landing and refusal decisions, what a refused craft is commanded, and the slot's NBT form.
Does NOT own: the flight law below the `FlightCommand` (ship-flight-model MECH-SFM-08), what the hull
can do (C10), pilot input delivery (C9), cell crossings (they carry the slot; they do not know it), the
substrate's ship records (C11; read through the port), and what decides to issue an intent. A target in
another world is a crossing, and flying one is not this slice's.

## Key types

| type | role |
|---|---|
| `GuidanceTarget` | what an intent wants: a ship by its durable id, or a surface point (world + column); placed in the craft's world per tick |
| `MotionIntent` | one order: `GO_TO(target)` or `HOLD(target, range)`; immutable |
| `MotionSlot` | at most one intent, its state (EMPTY / ACTIVE / REFUSED), and the per-tick law (`fly`) |
| `MotionSlot.Craft` | the craft as guidance reads it on one tick, all in its world's frame, with its hull's reach and the attitude law's gain and slew cap |
| `Surroundings` | what guidance may ask of the world around the craft: where a ship stands, how high the ground is |
| `WorldSurroundings` | `Surroundings` in a running game: the craft's world's ship registry, the ship ledger through the cell frames, the ground |

## Mechanics

- **MECH-GDN-01 — the slot.** One intent at most; issuing replaces whatever was held and starts it
  ACTIVE (`ship/guidance/MotionSlot.java:190`). While the slot holds anything — active or refused — it
  outranks the pilot's latched input and the unmanned hold in the flight computer's tick, and runs after
  the entry, descent and auto-takeoff branches (`tile/TileAdvancedFlightComputer.java:706`) `[V]`.
  Persisted in the computer's NBT (`motionSlot`, `:1727`, `:1761`); a slot that cannot be read back loads
  EMPTY and is logged `[V]`.
- **MECH-GDN-02** — retired: a space point of a cell name plus offset is no longer a target kind;
  MECH-GDN-08 places targets.
- **MECH-GDN-03 — the generators.** The aim is the nearest point of a shell about the placed target:
  nothing nearer than its keep-out plus `ARRIVAL_RADIUS` (zero keep-out for a surface point), nothing
  farther than that for a `GO_TO`, or than the larger of that and the range for a `HOLD`; inside the
  shell the aim is the craft itself (`ship/guidance/MotionIntent.java:90`) `[V]`. An intent stays in the
  slot after it is satisfied and goes on holding the craft there.
- **MECH-GDN-04 — the approach.** Beyond the arrival radius guidance picks two attitudes — one to close
  in, one to brake in — each the candidate whose signed translation axis delivers most along the way
  (toward the aim, or away from it), holding the weight; equal reaches go to the smaller turn
  (`ship/guidance/MotionSlot.java:431`). Candidates: in space, each of the six signed axes turned onto
  the way by the shortest arc; in a field, the LEVEL attitude — the axis up now turned straight up, or
  the nearest other axis that can hold the weight (`:493`) — turned about the vertical so an axis
  across it lies along the horizontal part of the way. Only a candidate the hull can turn into counts;
  a hull that can turn into none flies in the attitude it has. Reach is read from the live capability,
  SUSTAINED, per helm axis (`:593-648`). The turn from one attitude to the other is timed on the flight
  computer's own attitude law — rate `min(slew cap, gain·φ, √(2αφ))`, α the burst angular authority
  about the turn axis — until within `TURNED_WITHIN` (`tunable`), plus the spin-up (`:533`). With
  `a = STOP_MARGIN` (`tunable`) `×` braking reach and `T` that turn time, a craft closing slower than
  `v_T(d) = √((aT)² + 2ad) − aT` is pointed to close and commanded `min(top, v_T, POSITION_GAIN·d)`;
  from `v_T(d)` on it is pointed to brake and commanded `min(top, √(2ad), POSITION_GAIN·d, closing)`, so
  the turnover is coasted and the stop starts where the plan allowed for it (`:391`) `[V]`. A braking
  attitude the hull cannot turn into is replaced by braking in the closing attitude (`:348`). Inside the
  arrival radius no turn is asked for. The command is published as the computer's `flightCommand`,
  its attitude becomes the computer's attitude reference (`tile/TileAdvancedFlightComputer.java:1107-1162`)
  `[V]`.
- **MECH-GDN-05 — refusal and the refused craft.** A tick on which the target cannot be placed, or no
  candidate attitude lets the hull hold its weight, close or brake, is SHORT, and the craft is told to
  hover where it is, in the attitude it has. `REFUSAL_WINDOW_TICKS` (`tunable`) short ticks in a row
  refuse the intent: the slot reads REFUSED with that tick's reason, the seated pilot is told
  (`msg.guidance.refused.<reason>`), and `ShipEvent.MotionIntentRefusedEvent` is posted
  (`ship/guidance/MotionSlot.java:283`, `tile/TileAdvancedFlightComputer.java:1141-1147`) `[V]`. From
  then on the craft HOVERS — zero velocity, in the attitude it was refused in — until a pilot takes it
  back; it never falls to the unmanned hold and its retained cruise (`MotionSlot.java:250`) `[V]`.
- **MECH-GDN-06 — arrival.** Within `ARRIVAL_RADIUS` of the aim (a landing: within `TOUCHDOWN`,
  `ARRIVAL_SPEED / POSITION_GAIN`) and slower than `ARRIVAL_SPEED`, once per intent,
  `ShipEvent.MotionIntentArrivedEvent` is posted (`ship/guidance/MotionSlot.java:294`) `[V]`.
- **MECH-GDN-07 — reclaim.** C9 SHIPCTL-21.
- **MECH-GDN-08 — placing a target.** A SHIP is looked for in the craft's world's ship registry first
  (exact position, and its hull's reach about it); from space, then in the ship ledger, whose SETTLED
  address is placed in the craft's world through both cells' frames at the space clock's tick, the
  hull measured in the ship's own cell world when that is loaded
  (`ship/guidance/WorldSurroundings.java:48`). A ship mid-jump, on another world, or seen across the
  planet/space line answers NEEDS_A_CROSSING; one found nowhere, TARGET_NOT_FOUND; the craft itself,
  TARGET_IS_THIS_CRAFT. Its keep-out is the sum of the two hulls' radii — each the smallest sphere about
  the ship's position holding its block box, read off the registry (`integration/vs/VSBridge.java:899`).
  A SURFACE point placed from another world or from space answers NEEDS_A_CROSSING; otherwise it is the
  craft's position with its lowest point pressed half of `TOUCHDOWN` into the highest solid-or-liquid
  block top in the square of the craft's radius about the column, so a landing can only count as down
  once the ground has stopped it (`ship/guidance/GuidanceTarget.java:99`, `WorldSurroundings.java:86`)
  `[V]`.
- **MECH-GDN-09 — landing.** A `GO_TO` a surface point that arrives releases the craft: from then the
  computer commands no velocity, only the attitude it landed in, so it lies on the ground under its own
  weight (`ship/guidance/MotionSlot.java:253,294-300`) `[V]`.

## State & persistence

| key | owner | note |
|---|---|---|
| `motionSlot` {`state`, `refusal`, `arrived`, `intent` {`kind`, `range`, `target` {`kind` `ship` + `ship`, or `surface` + `dim`, `x`, `z`}}} | `TileAdvancedFlightComputer` | rides tile NBT through every crossing; the refusal window's count and the held hover/landing attitude are not saved |

## Invariants

- **INV-GDN-01** `[T][BEH]` An ordinary hull told `GO_TO` a parked ship about 200 blocks off ends with
  its position at the standoff from the ship's — the two hulls' radii plus the arrival radius — within
  2 blocks, never nearer than the two radii, slower than 0.2 blocks/s. FOR: the acceptance of the guidance
  slice, maintainer 2026-10-21 (*"а, делай"*), with its target restated as a ship the same day
  (*"Есть "лети на точку на планете", или "к кораблю", или "к базе""*). Pinned by
  `test/server/MotionIntentGroupTest#anOrdinaryHullToldToGoToAShipEndsAlongsideItAtRest`.
- **INV-GDN-02** — retired: a hull with no reverse thrust is no longer refused; INV-GDN-07.
- **INV-GDN-03** `[T][BEH]` A craft whose `GO_TO` a ship in the neighbouring cell takes it across the
  face arrives alongside the ship on the far side, as INV-GDN-01 measures arrival. FOR: the same
  acceptance, and the maintainer's line of 2026-10-21 that a target in ANOTHER world is a request for a
  crossing (*"цель в другом мире - это же запрос на гиперпрыжок"*) — a neighbouring cell of the same
  lattice is not another world, and is reached by flying.
  Pinned by `test/server/MotionIntentGroupTest#aGoToAShipInTheNeighbouringCellCrossesAndArrives`.
- **INV-GDN-04** `[T][SYS]` Beyond the arrival radius guidance never commands a speed above the craft's
  top speed, nor one all the hull's axes together could not stop from in the distance left; the velocity points at the
  aim; in space the commanded attitude lays the hull's strongest signed translation axis along the way —
  toward the aim, or away from it; a hull that must turn over to brake is never commanded faster than it
  could turn through a right angle at its slew cap and then stop; a hull that can close is flown; one
  with no translational authority is told to hold still in the attitude it has; in a field the commanded
  attitude keeps one of the hull's axes straight up. REGIONS: `closing`, `braking after a turnover`,
  `the turn binds`, `no thrust`, `in a field`. FOR: maintainer 2026-10-21, *"да"* — turnover is in the
  slice: a hull with no reverse thrust arrives by turning over and braking on its main engine — and his
  ruling the same day that motion owns velocity and an attitude no order holds follows the thrust (*"Как
  лучше выражается, так и сделай. Твой вариант вроде ок."* — do it however it is best expressed; your
  version seems fine). Pinned by
  `test/unit/MotionGuidanceLawsTest#theApproachIsOneTheHullCanStopFromOnItsStrongAxis`.
- **INV-GDN-05** `[T][SYS]` An intent is refused on exactly the `REFUSAL_WINDOW_TICKS`-th consecutive
  short tick and never sooner; a flyable tick restarts the count; a short tick and a refused slot both
  command a hover — zero velocity — and a refused slot holds the attitude it was refused in, whatever the
  craft does next. FOR: the slice as ruled 2026-10-21 (*"а, делай"*: unreachability is authority short
  for a sustained window, and the slot then holds the intent marked refused), and the ruling the same
  day that a refused craft hovers until its pilot returns (*"Да, пусть зависает"* — yes, let it hover). Pinned by
  `test/unit/MotionGuidanceLawsTest#aRefusalTakesASustainedWindowAndTheRefusedCraftHovers`.
- **INV-GDN-06** — retired: the space point is no longer a target kind; INV-GDN-11.
- **INV-GDN-07** `[T][BEH]` A hull with no reverse thrust given the order of INV-GDN-01 arrives as
  INV-GDN-01 measures it. FOR: maintainer 2026-10-21, *"да"* — turnover is in the slice, and such a
  hull arrives by turning over and braking on its main engine. Pinned by
  `test/server/MotionIntentGroupTest#aHullWithNoReverseThrustTurnsOverAndArrives`.
- **INV-GDN-08** `[T][BEH]` In space a hull with no translational authority at all, told `GO_TO` a ship,
  is refused within 10 s for want of thrust toward it, and its slot reads REFUSED. FOR: the same
  turnover ruling, which keeps refusal for the genuinely unflyable — a hull that cannot hold its weight
  or on which no axis delivers thrust — and the slice's "refused within 10 s", maintainer 2026-10-21.
  Pinned by `test/server/MotionIntentGroupTest#aHullWithNoThrustIsRefused`.
- **INV-GDN-09** `[T][BEH]` A craft in space told `GO_TO` a point of a planet's surface is refused
  within 10 s as needing a crossing, and then hovers — slower than 0.2 blocks/s — although its pilot left
  it a cruise. FOR: maintainer 2026-10-21, *"цель в другом мире - это же запрос на гиперпрыжок"* (a
  target in another world is a request for a jump) — refused until a crossing can be flown, under a
  reason that says so — and *"Да, пусть зависает"* (yes, let it hover) the same day. Pinned by
  `test/server/MotionIntentGroupTest#aTargetInAnotherWorldIsRefusedForACrossingAndTheCraftHovers`.
- **INV-GDN-10** `[T][BEH]` On a planet, a craft told `GO_TO` a point of the surface ends resting on the
  ground there: its lowest point within half a block of the ground's top, its position within 2 blocks
  of the column, slower than 0.2 blocks/s. FOR: the maintainer's ruling that landing is a `GO_TO` a point
  on a surface (*"Так это изначально и думалось"*), and his of 2026-10-21 that a target is a thing —
  *"Есть "лети на точку на планете", или "к кораблю""* (there is "fly to a point on the planet", or "to a
  ship"). Pinned by
  `test/server/MotionIntentGroupTest#aGoToASurfacePointLandsThere`.
- **INV-GDN-11** `[T][SYS]` A `GO_TO` a ship aims at the standoff — the two hulls' radii plus the arrival
  radius — from the ship's position; a `HOLD` leaves the craft anywhere between that and its range and
  pushes it back into that shell; a `GO_TO` a surface point aims at the pose that rests the craft's
  lowest point on the ground there, and on arriving releases the craft (no velocity command); a surface
  point of another world, or one asked from space, and a ship across a crossing are refused as needing a
  crossing; the craft as its own target is refused as such. FOR: maintainer 2026-10-21, that a target
  is a thing — *"Есть "лети на точку на планете", или "к кораблю", или "к базе". То есть это "к телу", а
  не "в точку пространства""* (there is "fly to a point on the planet", "to a ship", "to a base": "to a
  body", not "to a point of space") — and that one in another world is a request for a crossing
  (*"цель в другом мире - это же запрос на гиперпрыжок"*). Pinned by `test/unit/MotionGuidanceLawsTest#aGoToAShipAimsAtTheStandoffOutsideBothHulls`,
  `test/unit/MotionGuidanceLawsTest#aHoldKeepsTheCraftInItsShell`,
  `test/unit/MotionGuidanceLawsTest#aGoToASurfacePointAimsAtTheGroundAndLetsTheCraftDown`,
  `test/unit/MotionGuidanceLawsTest#aTargetInAnotherWorldOrTheCraftItselfIsRefusedForWhatItIs`.

## Failure modes & edge cases

A craft whose flight model is not built yet reads as having no authority and is short. A `GO_TO`
across a galactic cell face is carried by the seam, which places the craft `REENTRY_DEPTH` inside the
neighbour (`space/CellSeam.java:44`): the target is re-read there, now in the same world. A ship
target in a cell whose world is not loaded cannot be measured and is refused as TARGET_NOT_MEASURED
after the window. A ledger address is whole blocks, so a target in another cell is placed to within
half a block on each axis until the craft shares its world. In a field the hull is kept level: a hull
that could fly only tilted into its thrust is refused rather than tilted (an open question below).

## Integration seams

Events posted: `ShipEvent.MotionIntentArrivedEvent`, `ShipEvent.MotionIntentRefusedEvent` (C5). Lang:
`msg.guidance.refused.*` (C7). Port: `VSIntegration#hullAboutPosition` (C11). Probe:
`vs motion-go-to-ship-by-id`, `vs motion-hold-ship-by-id`, `vs motion-go-to-surface-by-id`,
`vs motion-read-by-id`.

## Config surface

None.

## Test coverage

`test/unit/MotionGuidanceLawsTest` (the laws: INV-GDN-04, -05, -11, SHIPCTL-21);
`test/server/MotionIntentGroupTest` (the in-world flights: INV-GDN-01, -03, -07..10).

## Open questions

- Whether `HOLD(ship, range)` keeps the craft anywhere in the shell (built) or at the range itself.
- In a field the hull is kept level and turned about the vertical only; a hull whose only strong axis
  must be tilted into the thrust (a heave-only hull flown sideways) is refused. Whether guidance should
  tilt such a hull is unruled.
- How "another system" is told apart from a far cell of the same lattice: today any ship SETTLED in
  space is flown to, however far.
