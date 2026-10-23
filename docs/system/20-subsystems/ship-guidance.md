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
  ACTIVE (`ship/guidance/MotionSlot.java:202`). While the slot holds anything — active or refused — it
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
  (`ship/guidance/MotionSlot.java:528`). Candidates: in space, each of the six signed axes turned onto
  the way by the shortest arc; in a field, the LEVEL attitude — the axis up now turned straight up, or
  the nearest other axis that can hold the weight (`:721`) — turned about the vertical so an axis
  across it lies along the horizontal part of the way. Only a candidate the hull can turn into counts;
  a hull that can turn into none flies in the attitude it has. Reach is read from the live capability,
  SUSTAINED, per helm axis, a share of the field below rounding counting as none (`:821`). The turn from one attitude to the other is timed on the flight
  computer's own attitude law — rate `min(slew cap, gain·φ, √(2αφ))`, α the burst angular authority
  about the turn axis — until within `TURNED_WITHIN` (`tunable`), plus the spin-up (`:761`). With
  `a = STOP_MARGIN` (`tunable`) `×` braking reach and `T` that turn time, a craft closing slower than
  `v_T(d) = √((aT)² + 2ad) − aT` is pointed to close and commanded `min(top, v_T, POSITION_GAIN·d)`;
  from `v_T(d)` on it is pointed to brake and commanded `min(top, √(2ad), POSITION_GAIN·d, closing)`, so
  the turnover is coasted and the stop starts where the plan allowed for it (`:484`) `[V]`. A braking
  attitude the hull cannot turn into is replaced by braking in the closing attitude (`:413`). When no
  such approach exists in a field — no level attitude closes, or none stops — the hull LEANS instead
  (MECH-GDN-10, `:402`). Inside the arrival radius no turn is asked for, unless the attitude the craft
  has cannot push toward the aim and the hull can lean (`:349`). The command is published as the
  computer's `flightCommand`, its attitude becomes the computer's attitude reference
  (`tile/TileAdvancedFlightComputer.java:1107-1162`) `[V]`.
- **MECH-GDN-05 — refusal and the refused craft.** A tick on which the target cannot be placed, or no
  candidate attitude lets the hull hold its weight, close or brake, is SHORT, and the craft is told to
  hover where it is, in the attitude it has. `REFUSAL_WINDOW_TICKS` (`tunable`) short ticks in a row
  refuse the intent: the slot reads REFUSED with that tick's reason, the seated pilot is told
  (`msg.guidance.refused.<reason>`), and `ShipEvent.MotionIntentRefusedEvent` is posted
  (`ship/guidance/MotionSlot.java:313`, `tile/TileAdvancedFlightComputer.java:1141-1147`) `[V]`. From
  then on the craft HOVERS — zero velocity, in the attitude it was refused in — until a pilot takes it
  back; it never falls to the unmanned hold and its retained cruise (`MotionSlot.java:262`) `[V]`.
- **MECH-GDN-06 — arrival.** Within `ARRIVAL_RADIUS` of the aim (a landing: let down, and within
  `TOUCHDOWN`, `ARRIVAL_SPEED / POSITION_GAIN`, of its height — MECH-GDN-09) and slower than
  `ARRIVAL_SPEED`, once per intent,
  `ShipEvent.MotionIntentArrivedEvent` is posted — never against a PROVISIONAL placement
  (MECH-GDN-08), whose hull nobody measured (`ship/guidance/MotionSlot.java:325`) `[V]`.
- **MECH-GDN-07 — reclaim.** C9 SHIPCTL-21.
- **MECH-GDN-08 — placing a target.** A SHIP is looked for in the craft's world's ship registry first
  (exact position, and its hull's reach about it); from space, then in the ship ledger, whose SETTLED
  address is placed in the craft's world through both cells' frames at the space clock's tick, as a
  ship in ANOTHER cell's world, whose hull is not measured from the craft's — loaded or not
  (`ship/guidance/WorldSurroundings.java:47`). A ship mid-jump, on another world, or seen across the
  planet/space line answers NEEDS_A_CROSSING; one found nowhere, TARGET_NOT_FOUND; the craft itself,
  TARGET_IS_THIS_CRAFT. Its keep-out is the sum of the two hulls' radii — each the smallest sphere about
  the ship's position holding its block box, read off the registry (`integration/vs/VSBridge.java:899`).
  A ship whose hull cannot be measured — in another cell's world, or in the craft's own with a record
  holding no blocks — is placed PROVISIONALLY, with no keep-out, since none of its hull is in the
  craft's world: the craft makes for the placed point itself, for a `GO_TO` and a `HOLD` alike
  (`ship/guidance/MotionSlot.java:281`). A ship in another cell of the same lattice is made for THROUGH
  THE SEAM: on each axis along which its cell lies beyond the craft's, the aim is taken at least to the
  first whole-block offset the seam carries a craft from, `HALF_CELL + CARRY_MARGIN + 1`
  (`ship/guidance/GuidanceTarget.java:160`), so reaching the aim carries the craft into the ship's
  world even when the ship is parked inside the band past the face in which the seam does not carry
  yet. Once the hull can be measured — at the latest when the craft shares its world — the real
  keep-out takes over on that tick (`GuidanceTarget.java:129-131`) `[V]`. A craft whose OWN hull cannot
  be measured answers CRAFT_NOT_MEASURED.
  A SURFACE point placed from another world or from space answers NEEDS_A_CROSSING; otherwise it is the
  craft's position with its lowest point pressed half of `TOUCHDOWN` into the highest solid-or-liquid
  block top in the square of the craft's radius about the column, so a landing can only count as down
  once the ground has stopped it (`ship/guidance/GuidanceTarget.java:147`, `WorldSurroundings.java:85`)
  `[V]`.
- **MECH-GDN-09 — landing.** A landing is let down only over its column: while the craft is farther
  across from the column (in X and Z, the column's own frame) than half the arrival radius, its aim is
  the point over the column where the sphere holding its hull just clears the ground, so no attitude
  puts the hull into the ground while it closes across; nearer, it is let down, the other half of the
  radius left for what it drifts on the way (`ship/guidance/MotionSlot.java:283-295`,
  `GuidanceTarget.java:147`). It has landed when, let down, it is within `TOUCHDOWN` of its aim's
  HEIGHT and slower than `ARRIVAL_SPEED` — across, a landing is judged by the arrival radius like any
  arrival (`MotionSlot.java:322`) `[V]`. A `GO_TO` a surface point that arrives releases the craft: from then the
  computer commands no velocity, only the attitude it landed in, so it lies on the ground under its own
  weight (`ship/guidance/MotionSlot.java:265,325-330`) `[V]`.
- **MECH-GDN-10 — leaning.** In a field, when no level approach exists (MECH-GDN-04) and the axis the
  level attitude holds up — the LIFTING axis — pushes harder than the weight, the hull leans
  (`ship/guidance/MotionSlot.java:595`). On every tick the acceleration the craft lacks is
  `K·(v_cmd − v)`, `K` a quarter of the attitude law's gain — critical damping of a lean loop that the
  attitude law lags; the lifting axis is laid along that acceleration plus the field's cancelled pull,
  turned from the level attitude about the horizontal axis square to the lean, and never further from
  the vertical than `acos(g / lift)`, past which its hardest push no longer holds the weight (`:690`).
  However fine, the lean asked is the lean commanded: nothing rounds it up to what the flight law's own
  attitude dead band would fly (Failure modes).
  The reach a lean gives along a way is the largest `a` with `|a·w + g·up| ≤ lift` within that
  angle (`:668`). The plan closes leaned at its full reach and brakes leaned back at `STOP_MARGIN` of
  it; the swing between the two leans is timed as a turnover is, and the speeds are MECH-GDN-04's with the
  close-in gain `2k/27` — the fastest a position loop over that lagged velocity loop can close without
  overshooting (`POSITION_GAIN` is `k`, its stability edge), along the vertical too, since how hard the
  lift pushes is what a lean turns into push across (`:446`, `:484`) `[V]`.

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
  with no translational authority is told to hold still in the attitude it has; in a field, a hull that
  pushes both ways along every axis — for which a level attitude always delivers — is commanded an
  attitude keeping one of its axes straight up (the rest is INV-GDN-12's). REGIONS: `closing`, `braking after a turnover`,
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
  lowest point on the ground there, and on arriving releases the craft (no velocity command); a craft
  resting on the ground farther across from that column than half the arrival radius has not landed and
  is sent up to the point over it where its hull clears the ground, never on along or into the ground,
  while one resting nearer than that has landed; a surface
  point of another world, or one asked from space, and a ship across a crossing are refused as needing a
  crossing; the craft as its own target is refused as such. FOR: maintainer 2026-10-21, that a target
  is a thing — *"Есть "лети на точку на планете", или "к кораблю", или "к базе". То есть это "к телу", а
  не "в точку пространства""* (there is "fly to a point on the planet", "to a ship", "to a base": "to a
  body", not "to a point of space") — and that one in another world is a request for a crossing
  (*"цель в другом мире - это же запрос на гиперпрыжок"*). Pinned by `test/unit/MotionGuidanceLawsTest#aGoToAShipAimsAtTheStandoffOutsideBothHulls`,
  `test/unit/MotionGuidanceLawsTest#aHoldKeepsTheCraftInItsShell`,
  `test/unit/MotionGuidanceLawsTest#aGoToASurfacePointAimsAtTheGroundAndLetsTheCraftDown`,
  `test/unit/MotionGuidanceLawsTest#aLandingIsLetDownOnlyOverItsColumn`,
  `test/unit/MotionGuidanceLawsTest#aTargetInAnotherWorldOrTheCraftItselfIsRefusedForWhatItIs`.
- **INV-GDN-12** `[T][SYS]` In a field, a hull for which a level attitude delivers thrust toward the aim
  — however weakly, and however much harder leaning would push — is kept level; a hull for which none
  does, and whose lifting axis can push harder than its weight, is flown and not refused: its lifting
  axis leans toward the aim, never further from the vertical than `acos(g / lift)`, whatever the craft
  is doing; and the
  swing of the lean from closing to braking is planned as a turnover is — a hull that swings slower is
  flown slower. FOR: the proposal handed back with the slice and taken forward on the
  maintainer's *"коммить и продолжай"* (commit and carry on), 2026-10-22 — accepted by continuing, not
  ruled explicitly: level by default so a hull never lands nose-first, a lean when level cannot
  deliver, since refusing such a hull is the lock the turnover ruling of 2026-10-21 (*"да"*) removed for
  reverse thrust. Pinned by
  `test/unit/MotionGuidanceLawsTest#inAFieldTheHullStaysLevelWhileLevelDeliversAndLeansOnlyWhenItCannot`,
  `test/unit/MotionGuidanceLawsTest#swingingTheLeanFromClosingToBrakingIsPlannedLikeATurnover`.
- **INV-GDN-13** `[T][SYS]` A ship target whose hull cannot be measured is flown toward and never
  refused for that; no shell is made up for it — the craft makes for the placed point itself, a `HOLD`
  inside its range included — and it never counts as arrived there; the same ship measured is arrived
  at on its standoff; a ship found nowhere is refused as TARGET_NOT_FOUND. FOR: the same proposal, taken
  forward the same way — an unmeasurable target is approached, not refused, and only a ship that no
  longer exists is refused — and the maintainer's ruling of 2026-10-22 (*"давай"*) that no stand-in
  hull is assumed: a hull is unmeasurable only while it is in another cell's world, and reaching it is
  crossing into that cell, where it is measured. Pinned by
  `test/unit/MotionGuidanceLawsTest#aShipWhoseHullCannotBeMeasuredIsMadeForNotRefusedAndNeverArrivedAt`.
- **INV-GDN-14** `[T][BEH]` On a planet, a hull that pushes along heave alone, told `GO_TO` a point of the
  surface off to its side, lands there as INV-GDN-10 measures a landing. FOR: the proposal handed back
  with the slice and taken forward on *"коммить и продолжай"* (2026-10-22), whose purpose is that a hull
  with only that axis gets where it is sent instead of being refused. Pinned by
  `test/server/MotionIntentGroupTest#aHeaveOnlyHullLeansIntoItsThrustAndLandsAtAPointToItsSide`.
- **INV-GDN-15** `[T][SYS]` A ship in the world of a neighbouring cell of the same lattice is made for
  through the seam: on each axis along which its cell lies beyond the craft's, the craft is sent at
  least to the first offset the seam carries a craft from, keeping the ship's position on the other
  axes — so a craft that has reached a ship parked inside the band past the face, where the seam does
  not carry yet, is sent on across and never held there; a ship already past that line is made for
  where it is. FOR: the ruling of 2026-10-22 (*"давай"*) that reaching such a ship is crossing into its
  cell, its one edge — a ship parked inside the carry band just past a face — to be pinned by a test,
  not by a number; and INV-GDN-03's ground, that a neighbouring cell of the same lattice is reached by
  flying. Pinned by `test/unit/MotionGuidanceLawsTest#aShipInTheNeighbouringCellIsMadeForThroughTheSeam`.

## Failure modes & edge cases

A craft whose flight model is not built yet reads as having no authority and is short. A `GO_TO`
across a galactic cell face is carried by the seam, which places the craft `REENTRY_DEPTH` inside the
neighbour (`space/CellSeam.java:44`): the target is re-read there, now in the same world. A ship
target in another cell is made for through the seam (MECH-GDN-08); a craft that crosses for one parked
just past the face is carried `REENTRY_DEPTH` deep and flies back that far to it. Across lattices — a
ship in another zone — the aim is the ship's position unchanged: that boundary is a sphere, and a ship
parked just inside one, where the zone does not take a craft in yet, is made for and never reached
(an open question). A ledger address is whole blocks, so a target in another cell is placed to
within half a block on each axis until the craft shares its world. A leaning hull closes in at `2k/27`,
some 0.15/s against a level hull's 2/s, so it settles more than ten times slower; and it can lean only
as far as its hull can HOLD a turn: a hull whose mass is lopsided enough that a clean roll or pitch needs
a yaw share it cannot sustain has no sustained authority about that axis, and drifts along it.
The flight law leaves the attitude alone while its error is under `ATTITUDE_ANGLE_EPSILON` (1 mrad) and
the hull turns slower than `ANGULAR_RATE_EPSILON` (`api/FreeFlightPhysics.java:1208-1211`), so a change
of lean finer than 1 mrad is not flown and a leaning craft may carry that much lean it was not asked
for — up to 0.032 blocks/s² across in standard gravity (32 blocks/s²). Guidance does not round its
leans around that band; measured 2026-10-23 with and without such rounding, ten runs each, INV-GDN-14
landed in every run (about 83 s a run without it, 87 s with it). The physics substrate turns a hull at
any non-zero rate (`valkyrienskies/.../physics/PhysicsCalculations.java:356-376`); until 2026-10-23 it
dropped every rotation under 0.0316 rad/s, which froze a lean wherever its last larger turn stopped and
left INV-GDN-14 unlanded in about one run in six.

## Integration seams

Events posted: `ShipEvent.MotionIntentArrivedEvent`, `ShipEvent.MotionIntentRefusedEvent` (C5). Lang:
`msg.guidance.refused.*` (C7). Port: `VSIntegration#hullAboutPosition` (C11). The seam's constants
`GalacticCoord.HALF_CELL`, `CellSeam.CARRY_MARGIN` (space). Probe:
`vs motion-go-to-ship-by-id`, `vs motion-hold-ship-by-id`, `vs motion-go-to-surface-by-id`,
`vs motion-read-by-id`.

## Config surface

None.

## Test coverage

`test/unit/MotionGuidanceLawsTest` (the laws: INV-GDN-04, -05, -11..13, -15, SHIPCTL-21);
`test/server/MotionIntentGroupTest` (the in-world flights: INV-GDN-01, -03, -07..10, -14). Not seen
in a running game: a ship parked inside the carry band past a face, and a ship record with no blocks
(INV-GDN-13 and -15 are pinned on the law alone). That the substrate turns a hull at a slow rate,
which INV-GDN-14 rests on, is pinned on the substrate's side (valkyrienskies INV-VSK-14).

## Open questions

- Whether `HOLD(ship, range)` keeps the craft anywhere in the shell (built) or at the range itself.
- A ship in another ZONE parked inside the band at its sphere where the zone does not yet take a craft
  in is made for and never reached: only the cube faces of one lattice are planned through.
- A lean is planned on the hull's BURST angular authority, as a turnover is, but held for as long as
  the craft leans: a hull that can turn about an axis only for its wheel's seconds leans along it and
  then drifts, rather than being refused for want of a sustained turn. Unruled.
- How "another system" is told apart from a far cell of the same lattice: today any ship SETTLED in
  space is flown to, however far.
