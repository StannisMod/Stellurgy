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
every other producer on that computer publishes. An order names a THING — a ship, a point on the
surface of a planet, or a body's orbit — never a bare point of space; a thing in another world is
reached by flying the legs of one fixed chain between (MECH-GDN-12), a jump among them when it is
farther than the next cell (MECH-GDN-13); a ship on its way there is waited for (MECH-GDN-15). The one
order a
player issues today is take-off: the pilot's auto-takeoff key hands the slot `GO_TO` the orbit of the body
the craft stands on (C9 SHIPCTL-22); everything else fills the slot through the flight computer's own entry.

## Responsibility boundary

Owns: the motion slot and its states, the two motion verbs (`GO_TO`, `HOLD`), the three target kinds and
how each is placed in the craft's world (the climb to an orbit and its corridor included), which leg of
the way to a target in another world the craft flies and where its jump is aimed, the way around the
descent shells it is not led into, the approach law (which attitude, how fast, when to turn over),
the arrival, landing and refusal decisions, what a refused craft is commanded, and the slot's NBT form.
Does NOT own: the flight law below the `FlightCommand` (ship-flight-model MECH-SFM-08), what the hull
can do (C10), pilot input delivery (C9), cell crossings (they carry the slot; they do not know it), the
substrate's ship records (C11; read through the port), the orbit line (the flight computer's
`entryCeiling`, handed in), and what decides to issue an intent. The crossings themselves are not
guidance's: it flies a craft UP to the entry line and INTO a body's descent shell, and the entry and the
descent — each decided by its own one condition, for any craft — take it on. The jump is the
hyperdrive's (`hyperdrive`): guidance names where it is aimed and asks for it; the flight
computer aims and arms the navigation computer and presses the helm's key unattended, through the one
jump gate (hyperdrive MECH-HYP-20).

## Key types

| type | role |
|---|---|
| `GuidanceTarget` | what an intent wants: a ship by its durable id, a surface point (world + column), or a body's orbit (the body's planet world); placed in the craft's world per tick |
| `MotionIntent` | one order: `GO_TO(target)` or `HOLD(target, range)`; immutable |
| `MotionSlot` | at most one intent, its state (EMPTY / ACTIVE / REFUSED), the leg it flies (`Leg`), and the per-tick law (`fly`) |
| `MotionSlot.Craft` | the craft as guidance reads it on one tick, all in its world's frame, with its hull's reach and the attitude law's gain and slew cap |
| `JumpDestination` | where a jump leg is aimed: a body (its planet world and last-seen address) or a bare place — the pair the navigation computer is pointed at |
| `Surroundings` | what guidance may ask of the world around the craft: where a ship stands (or that it is in space, on another planet, far off, or on its way), how high the ground is, where its world's orbit line is and whether the column above is open, where a body's orbit and descent shell lie (or where the jump to it is aimed), every descent shell about the craft; and the one act besides flying — asking the craft's drive to jump |
| `WorldSurroundings` | `Surroundings` in a running game: the craft's world's ship registry, the ship ledger through the cell frames (and the hull each ship last measured), the loaded worlds, the ground, the flight computer's orbit line and its jump, the universe's bodies |
| `AutoTakeoffPlanner` (`space/`) | the take-off corridor: whether a column of blocks holds anything solid, every block asked |

## Mechanics

- **MECH-GDN-01 — the slot.** One intent at most; issuing replaces whatever was held and starts it
  ACTIVE (`ship/guidance/MotionSlot.java:202`). While the slot holds anything — active or refused — it
  outranks the pilot's latched input and the unmanned hold in the flight computer's tick, and runs after
  the entry and descent branches (`tile/TileAdvancedFlightComputer.java:695`) `[V]`. Nothing else in that
  tick climbs a craft to orbit: take-off is an intent in this slot (MECH-GDN-11). While the substrate does
  not step the hull's physics yet — just assembled, or just pasted by a crossing or a jump, waiting out
  its initial ticks or its surrounding chunks (`VSIntegration#shipPhysicsSteps`) — the slot holds the craft
  and is not flown: nothing is judged against a hull that reads at rest wherever it was put, so no
  arrival and no shortfall, and nothing else takes the craft (`TileAdvancedFlightComputer.java:1068`) `[V]`.
  Persisted in the computer's NBT (`motionSlot`, `:1804`, `:1838`); a slot that cannot be read back loads
  EMPTY and is logged `[V]`.
- **MECH-GDN-02** — retired: a space point of a cell name plus offset is no longer a target kind;
  MECH-GDN-08 places targets.
- **MECH-GDN-03 — the generators.** The aim is the nearest point of a shell about the placed target:
  nothing nearer than its keep-out plus `ARRIVAL_RADIUS` (zero keep-out for a surface point; an orbit's
  sphere is its keep-out alone, there being no hull to keep apart), nothing
  farther than that for a `GO_TO`, or than the larger of that and the range for a `HOLD`; inside the
  shell the aim is the craft itself (`ship/guidance/MotionIntent.java:93`) `[V]`. An intent stays in the
  slot after it is satisfied and goes on holding the craft there.
- **MECH-GDN-04 — the approach.** Beyond the arrival radius guidance picks two attitudes — one to close
  in, one to brake in — each the candidate whose signed translation axis delivers most along the way
  (toward the aim, or away from it), holding the weight; equal reaches go to the smaller turn
  (`ship/guidance/MotionSlot.java:538`). Candidates: in space, each of the six signed axes turned onto
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
  refuse the intent: the slot reads REFUSED with that tick's reason — for JUMP_DECLINED (MECH-GDN-13)
  with what declined the jump, `Reading#declinedBy`, a lang key — the seated pilot is told
  (`msg.guidance.refused.<reason>`, then that key), and `ShipEvent.MotionIntentRefusedEvent` is posted,
  carrying it too
  (`ship/guidance/MotionSlot.java:319`, `tile/TileAdvancedFlightComputer.java:1141-1147`) `[V]`. From
  then on the craft HOVERS — zero velocity, in the attitude it was refused in — until a pilot takes it
  back; it never falls to the unmanned hold and its retained cruise (`MotionSlot.java:267`) `[V]`.
- **MECH-GDN-06 — arrival.** Within `ARRIVAL_RADIUS` of the aim (a landing: let down, and within
  `TOUCHDOWN`, `ARRIVAL_SPEED / POSITION_GAIN`, of its height — MECH-GDN-09) and slower than
  `ARRIVAL_SPEED`, once per intent,
  `ShipEvent.MotionIntentArrivedEvent` is posted — never against a PROVISIONAL placement: a ship whose
  hull nobody measured (MECH-GDN-08), the climb to an orbit, which the entry crossing takes the craft on
  from (MECH-GDN-11), or a leg a crossing takes over (MECH-GDN-12, -13); never while the target is waited
  for (MECH-GDN-15); and never at a waypoint around a shell (MECH-GDN-14)
  (`ship/guidance/MotionSlot.java:412`) `[V]`.
- **MECH-GDN-07 — reclaim.** C9 SHIPCTL-21.
- **MECH-GDN-08 — placing a target.** A SHIP is looked for in the craft's world's ship registry first
  (exact position, and its hull's reach about it); then in the ship ledger, whose SETTLED address is,
  from space, placed in the craft's world through both cells' frames at the space clock's tick, as a
  ship in ANOTHER cell's world, whose hull is not measured from the craft's — loaded or not — and, from a
  planet, answered as IN SPACE; then in every loaded world — a space cell's world placed as a settled
  ship is, from its cell and its pose there; a planet world answered as ON THAT PLANET
  (`ship/guidance/WorldSurroundings.java:90-160`). A ship in space whose cell is farther from the craft's
  than the next one (MECH-GDN-12) answers its address and the hull it last measured itself — the
  ledger's, so it is known whether or not its world is loaded (space/hyperspace-and-persistence
  MECH-SPC-09); a ship no ledger row holds, measured where it is — placed as the jump (MECH-GDN-13). One
  the ledger holds in transit, or in the hyperspace world, or far off and never measured yet, is waited
  for (MECH-GDN-15); one found nowhere, TARGET_NOT_FOUND; the craft itself, TARGET_IS_THIS_CRAFT. A ship
  in space seen from a planet, or on another planet, is placed as its leg (MECH-GDN-12). Its keep-out is the sum of the two hulls' radii — each the smallest sphere about
  the ship's position holding its block box, read off the registry (`integration/vs/VSBridge.java:899`).
  A ship whose hull cannot be measured — in another cell's world, or in the craft's own with a record
  holding no blocks — is placed PROVISIONALLY, with no keep-out, since none of its hull is in the
  craft's world: the craft makes for the placed point itself, for a `GO_TO` and a `HOLD` alike
  (`ship/guidance/MotionSlot.java:287`). A ship in another cell of the same lattice is made for THROUGH
  THE SEAM: on each axis along which its cell lies beyond the craft's, the aim is taken at least to the
  first whole-block offset the seam carries a craft from, `HALF_CELL + CARRY_MARGIN + 1`
  (`ship/guidance/GuidanceTarget.java:160`), so reaching the aim carries the craft into the ship's
  world even when the ship is parked inside the band past the face in which the seam does not carry
  yet. Once the hull can be measured — at the latest when the craft shares its world — the real
  keep-out takes over on that tick (`GuidanceTarget.java:129-131`) `[V]`. A craft whose OWN hull cannot
  be measured answers CRAFT_NOT_MEASURED.
  A SURFACE point asked from space or from another planet is placed as its leg (MECH-GDN-12); on its own
  planet it is the
  craft's position with its lowest point pressed half of `TOUCHDOWN` into the highest solid-or-liquid
  block top in the square of the craft's radius about the column, so a landing can only count as down
  once the ground has stopped it (`ship/guidance/GuidanceTarget.java:147`, `WorldSurroundings.java:85`)
  `[V]`.
- **MECH-GDN-09 — landing.** A landing is let down only over its column: while the craft is farther
  across from the column (in X and Z, the column's own frame) than half the arrival radius, its aim is
  the point over the column where the sphere holding its hull just clears the ground, so no attitude
  puts the hull into the ground while it closes across; nearer, it is let down, the other half of the
  radius left for what it drifts on the way (`ship/guidance/MotionSlot.java:289-301`,
  `GuidanceTarget.java:147`). It has landed when, let down, it is within `TOUCHDOWN` of its aim's
  HEIGHT and slower than `ARRIVAL_SPEED` — across, a landing is judged by the arrival radius like any
  arrival (`MotionSlot.java:328`) `[V]`. A `GO_TO` a surface point that arrives releases the craft: from then the
  computer commands no velocity, only the attitude it landed in, so it lies on the ground under its own
  weight (`ship/guidance/MotionSlot.java:270,331-336`) `[V]`.
- **MECH-GDN-10 — leaning.** In a field, when no level approach exists (MECH-GDN-04) and the axis the
  level attitude holds up — the LIFTING axis — pushes harder than the weight, the hull leans
  (`ship/guidance/MotionSlot.java:605`). On every tick the acceleration the craft lacks is
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
- **MECH-GDN-11 — a body's orbit, and take-off.** An ORBIT target names the planet world that is the
  body's surface (`ship/guidance/GuidanceTarget.java:201`). On that world it is placed straight over the
  craft, `CLIMB_PAST_THE_LINE` above the world's orbit line — half the room the entry line is kept below
  the physics clamp (`ShipEntryController.PHYSICS_CLAMP_ENTRY_MARGIN`), so it is reachable and the line
  is crossed before it — re-placed over the column the craft is in on every tick, so the climb is
  vertical, flown by the ordinary approach (MECH-GDN-04); PROVISIONAL, since the entry crossing takes the
  craft on as it passes the line (`tile/TileAdvancedFlightComputer.java:667-684`) and the slot rides the
  crossing in the computer's NBT. The line is the flight computer's own `entryCeiling`, handed in, so the
  climb and the crossing read one line (`:1085`). While anything of solid material stands in the
  craft's column from its height to the aim — every block asked, none above the build height
  (`space/AutoTakeoffPlanner.java`, `ship/guidance/WorldSurroundings.java:114`) — the tick is short for
  PATH_BLOCKED; on a world with no orbit line, for NO_ORBIT_LINE; both refuse through the common window
  (MECH-GDN-05). From another planet of the body's system it is the same climb, out of that planet's
  world (MECH-GDN-12). In space the orbit is the sphere about the body
  — the body the dimension's zone cell lists for that world (`SpaceSubsystem.launchBody`), placed where
  it stands at the space clock's tick (its position, not its address) against where the craft's cell
  centre stands — of the body's own entry ring (`ShipEntryController.entryRingAround(SystemBody)`)
  (`WorldSurroundings.java:165`): a craft on it at rest has arrived and holds, and the same order from
  far off sends the craft to the body. A body nobody can find is TARGET_NOT_FOUND; one whose cell is
  farther than the next one is the jump to it (MECH-GDN-13) `[V]`. The entry puts a
  craft on that ring, about the body placed in the same cell's frame by the same function
  (`CellFrames.positionInCell`; space/cells-and-crossings INV-SPC-19), so a taken-off craft has arrived as
  it comes to rest there `[T]`.
  The pilot's auto-takeoff key issues `GO_TO` this orbit on a planet's surface; pressed while the slot
  flies that very order it empties the slot, and anywhere else it does nothing
  (`tile/TileAdvancedFlightComputer.java:1017`) `[V]`.
- **MECH-GDN-12 — the legs: a target in another world.** One fixed chain of worlds — up out of a
  planet's world, a jump when the target is farther than the next cell (MECH-GDN-13), through space, down
  into a planet's world — and the LEG the craft flies is derived on
  every tick from where the craft is against where the target is (`ship/guidance/GuidanceTarget.java:195`);
  nothing of it is stored, so the order rides each crossing unchanged in the computer's NBT and the leg
  after a crossing is what the new world answers. On a planet, a target outside the planet's world — a
  ship in space, the orbit or a surface point of another body, a ship on another planet, however far off
  — is the CLIMB:
  MECH-GDN-11's placement straight up past the world's orbit line, refused as it is
  (`GuidanceTarget.java:255-275`). In space, a target in a planet's world — a surface point, a ship
  standing there — is the way INTO THE SHELL: the point `INTO_THE_SHELL` (the arrival radius plus two
  blocks, past the √3 the ledger's whole-block position can lie off the pose) inside that body's
  descent shell (`space/DescentShell.radiusAround`), nearest the craft, placed as an orbit is; a craft
  already that deep is held where it is (`GuidanceTarget.java:286`). Both are PROVISIONAL — made for,
  never arrived at — because a crossing takes the craft on first: the entry above the line, and the
  descent inside the shell, which is the one proximity condition every craft descends by
  (space/cells-and-crossings MECH-SPC-13), not an act of guidance; so ANY order that brings a craft inside a body's shell
  descends it, and orbits lie outside every shell. After the descent the target is in the craft's world
  and is flown as such. In space, how far a place is is counted in CELLS, never in systems: a place in the
  craft's own cell or the NEXT one is flown to, anything farther is jumped to — within the craft's system,
  across systems and across the void's cells alike. In one lattice the next cell is one whose sector
  triple is at most one off on every axis (a face, an edge or a corner away, each crossed by the seam);
  across lattices it is a zone's cell and the cell that zone is nested in, which one crossing of the
  zone's sphere joins; a body's cell is its name (`ship/guidance/GuidanceTarget.java:353-365`,
  `WorldSurroundings.java:137-141,243`). The leg flown is readable: `MotionSlot.Reading#leg`
  (`TO_THE_TARGET` / `CLIMB_TO_SPACE` / `INTO_THE_SHELL` / `JUMP` / `WAITING_FOR_THE_TARGET`), the leg of
  the last tick the target could be placed (`ship/guidance/MotionSlot.java:342,356,359`) `[V]`.
- **MECH-GDN-13 — the jump: a target beyond the next cell.** In space a target farther than the next
  cell (MECH-GDN-12) is the JUMP leg: no point of the craft's world, a DESTINATION — a ship: a place of its
  own cell
  `⌈craft hull + ship hull + ARRIVAL_RADIUS + OUT_OF_THE_JUMP_PAST_THE_STANDOFF⌉` off its address on a
  bearing the craft's name picks (`space/StandoffRing.pointAround`) — the standoff and three arrival radii
  beyond it, past the arrival radius about the aim, a ring point's rounding and the measured offset of a
  pasted hull's pose from its placement — so the craft comes out near the ship, never in its hull, and its
  arrival is FLOWN, never declared on the tick it comes out (`ship/guidance/GuidanceTarget.java:292`); a surface point or orbit of a body: the body, its planet world
  and address, which the navigation computer follows and a jump's arrival stands off by itself
  (`WorldSurroundings.java:243`). The ship's hull is the one it last measured itself (MECH-GDN-08); a
  craft whose own hull cannot be measured answers CRAFT_NOT_MEASURED. On every tick of the leg guidance
  asks the craft's drive to make the jump (`Surroundings#jump`, `MotionSlot.java:343`) and holds the craft
  still — zero velocity, the attitude it has; the flight computer aims, arms and presses unattended
  (hyperdrive MECH-HYP-20), going through the gate's ADVISORY objections as a pilot who confirms them
  does — only what would have stopped that pilot declines the jump. The drive answers each ask
  (`Surroundings.JumpAnswer`): declined, naming what declines it; under way; or BEGUN — this ask started the
  wind-up — with the advisories it went through. A tick the drive answers with what declines the jump is
  SHORT for JUMP_DECLINED and refuses through the common window (MECH-GDN-05) naming it; a tick it answers
  under way or begun is flown, and a begun answer sets the slot's `Reading#advisoriesGoneThrough` to the
  advisories it carries (empty for none), kept across the jump and in NBT until the slot is given another
  intent or taken back (`ship/guidance/MotionSlot.java:343-352`). The flight computer reports each
  advisory it went through where a pilot would have read it: a seated pilot is told
  (`msg.guidance.jumpadvised`, then each advisory's own key, as the helm shows a warning), and
  `ShipEvent.MotionIntentJumpAdvisedEvent` is posted with them (`tile/TileAdvancedFlightComputer.java:1721-1738`)
  — never silent `[V]`. Never arrived. Mid-jump, in hyperspace, the order is flown nothing, reads JUMP and
  refuses nothing (`MotionSlot#carriedByAJump`, called from the computer's hyperspace branch,
  `tile/TileAdvancedFlightComputer.java:489`); after the arrival the same order is flown in the
  destination's world. The pilot's own jump key pressed during a wind-up the order started stops it and
  takes the craft back (C9 SHIPCTL-23) `[V]`.
- **MECH-GDN-14 — around the shells.** In space guidance never sends a craft into the descent shell of a
  body the order does not lead into — every shell about the craft (`Surroundings#shellsBut`, the bodies
  `UniverseRegistry.skyBodiesAt` lists for its cell that a craft can descend to, placed as MECH-GDN-11
  places one) but the one the INTO_THE_SHELL leg flies into. While the straight way from the craft to its
  aim comes nearer a body than its shell plus `CLEAR_OF_THE_SHELL` (the arrival radius plus two blocks,
  past the √3 the ledger's position can lie off the pose) and nearer than the craft is now, the aim is
  replaced by a waypoint as far off as the aim along the line from the craft that grazes that widened
  shell on the aim's side — its tangent; from inside the widened shell, along the shell's surface; the
  first shell met is rounded first, at most once per shell (`ship/guidance/MotionSlot.java:468-540`). A
  craft within the widening and leaving it is not held back. Re-chosen every tick, so the craft rounds the
  shell and is sent straight on once nothing stands in the way. The descent condition itself is untouched
  (space/cells-and-crossings MECH-SPC-13) `[V]`.
- **MECH-GDN-15 — waiting for a ship on its way.** A ship target that is on its way to somewhere it can
  be aimed at — held IN TRANSIT by the ledger, standing in the hyperspace world, or settled farther than
  the next cell without a hull measured yet (an entry or a jump's arrival settles it; its own flight
  computer measures it on its first tick in its cell) — is WAITED FOR: the leg reads
  `WAITING_FOR_THE_TARGET`, the craft is commanded no speed in the attitude it has, the tick is flown —
  never short, so no refusal comes of it however long it lasts — no jump is asked, and nothing is arrived
  at (`ship/guidance/WorldSurroundings.java:100,109,137-141`, `GuidanceTarget.java:211-212`,
  `MotionSlot.java:353-357`). Nothing of the wait is stored: the first tick the ship can be placed again
  places it, and the leg toward where it now is is flown — a jump, when it came out far off `[V]`.

## State & persistence

| key | owner | note |
|---|---|---|
| `motionSlot` {`state`, `refusal`, `declinedBy` (JUMP_DECLINED only), `advisoriesGoneThrough` (a list of lang keys, absent when empty), `arrived`, `intent` {`kind`, `range`, `target` {`kind` `ship` + `ship`, or `surface` + `dim`, `x`, `z`, or `orbit` + `dim`}}} | `TileAdvancedFlightComputer` | rides tile NBT through every crossing, a jump included; the refusal window's count, the held hover/landing attitude and the leg (derived per tick) are not saved, nor are the computer's own notes of a wind-up the order started and of a commit that failed |

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
- **INV-GDN-09** `[T][BEH]` A craft in space in a cell of another system told `GO_TO` a point of the
  surface of a planet, whose drive cannot make the jump — its bank never charged — is refused within
  10 s as JUMP_DECLINED, naming the clause the jump gate itself declines that ship's jump with; its slot
  reads REFUSED; and it then hovers — slower than 0.2 blocks/s — although its pilot left it a cruise.
  FOR: the acceptance of the jump leg stated before the work (2026-10-23, *"по рекомендациям"*): "a jump
  the gate declines is reported with the declining clause; the slot reads refused" — a HARD objection,
  as an uncharged bank's is (an ADVISORY one is gone through, INV-GDN-31); and *"Да, пусть зависает"* (yes, let it hover), 2026-10-21. Pinned by
  `test/server/MotionIntentGroupTest#aJumpTheGateDeclinesRefusesTheOrderNamingTheClauseAndTheCraftHovers`.
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
  while one resting nearer than that has landed; the craft as its own target is refused as such. FOR:
  maintainer 2026-10-21, that a target
  is a thing — *"Есть "лети на точку на планете", или "к кораблю", или "к базе". То есть это "к телу", а
  не "в точку пространства""* (there is "fly to a point on the planet", "to a ship", "to a base": "to a
  body", not "to a point of space"). Pinned by `test/unit/MotionGuidanceLawsTest#aGoToAShipAimsAtTheStandoffOutsideBothHulls`,
  `test/unit/MotionGuidanceLawsTest#aHoldKeepsTheCraftInItsShell`,
  `test/unit/MotionGuidanceLawsTest#aGoToASurfacePointAimsAtTheGroundAndLetsTheCraftDown`,
  `test/unit/MotionGuidanceLawsTest#aLandingIsLetDownOnlyOverItsColumn`,
  `test/unit/MotionGuidanceLawsTest#theCraftItselfIsRefusedAsItsOwnTarget`.
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
- **INV-GDN-16** `[T][SYS]` On its body's surface a `GO_TO` an orbit sends the craft straight up and is
  never arrived at there, wherever between the orbit line and the room the physics clamp leaves above it
  the craft comes to rest; it is refused as PATH_BLOCKED while something solid stands in the column over
  the craft — never for what lies under it — and as NO_ORBIT_LINE on a world with none. In space it is
  the sphere about the body at the radius it is given: a
  craft at rest on it has arrived, one far off is sent at the body, and a body nobody can find is refused
  as TARGET_NOT_FOUND. FOR: the maintainer's rulings of 2026-10-23 (*"по рекомендациям"*) — take-off is
  a `GO_TO` a new target kind, the orbit; the climb is vertical and flown by guidance, the corridor
  check staying a predicate along it; in space the orbit is the sphere at the ring an entry puts a craft
  on; a blocked corridor and a world with no orbit line refuse through the common window — on his of
  2026-10-21 that take-off is `GO_TO(orbit)` (*"Авто-взлёт — это GO_TO(орбита) - да"*). Pinned by
  `test/unit/MotionGuidanceLawsTest#anOrbitIsClimbedStraightUpFromItsBodyAndIsTheSphereAboutItInSpace`.
- **INV-GDN-17** `[T][BEH]` A craft on the overworld whose pilot presses the take-off key climbs across
  the entry line into space, and there the order has arrived at its body's orbit — the entry put the craft
  on that body's ring — and the slot holds it. FOR: the maintainer's rulings of 2026-10-23 (*"по рекомендациям"*) that take-off is a `GO_TO`
  an orbit, climbed vertically by guidance, with the orbit in space the sphere an entry puts a craft on —
  so the craft that has just entered is there and holds — and his of 2026-10-21 (*"Авто-взлёт — это
  GO_TO(орбита) - да"*). Pinned by
  `test/server/VSShipAutoTakeoffTest#theTakeoffKeyClimbsTheCraftIntoSpaceWhereItHoldsInOrbit`.
- **INV-GDN-18** `[T][BEH]` A craft whose column is blocked overhead, told to take off, has the climb
  refused for PATH_BLOCKED — announced — and its slot reads REFUSED, holding the order, not empty. FOR:
  the ruling of 2026-10-23 (*"по рекомендациям"*) that a blocked corridor is an ordinary refusal through
  the common window, which INV-GDN-05 states. Pinned by `test/server/VSShipAutoTakeoffTest#aBlockedColumnRefusesTheTakeoffAndTheSlotSaysSo`.
- **INV-GDN-19** `[T][SYS]` A target in another world of the craft's system is flown as its leg, never
  refused for being elsewhere: from a planet — a ship in space, a ship on another planet, a surface point
  or the orbit of another body — the craft is sent straight up, reads `CLIMB_TO_SPACE`, and is never
  arrived there; from space — a surface point of a body, a ship on a planet — it is sent toward the
  point `INTO_THE_SHELL` inside that body's descent shell along the line from the body, reads
  `INTO_THE_SHELL`, is never arrived there, and once that deep is held where it is; and a target
  in the craft's world reads `TO_THE_TARGET`. FOR: the maintainer's rulings of 2026-10-23 (*"по
  рекомендациям"*): a fixed chain of legs — climb, jump to another system, fly through cells, descend
  onto a planet — not a search; and of 2026-10-24, *"GO_TO не может скомандовать спуск на планету, а он
  и не должен. Он должен вести к планете. Спуском продолжает управлять одно условие"* (a GO_TO cannot
  command a descent, nor should it: it must lead to the planet; one condition goes on governing the
  descent). Pinned by `test/unit/MotionGuidanceLawsTest#aTargetInAnotherWorldOfTheSystemIsClimbedToFromAPlanet`,
  `test/unit/MotionGuidanceLawsTest#aTargetOnAPlanetIsFlownIntoItsBodysShellFromSpace`.
- **INV-GDN-20** `[T][BEH]` A craft on the overworld told `GO_TO` a ship in space of its system climbs,
  enters space with the order in its slot, and arrives alongside the ship as INV-GDN-01 measures it —
  with no pilot input anywhere on the way. FOR: the acceptance of the crossing legs, stated before the
  work (*"по рекомендациям"*, 2026-10-23): "a craft on a planet given `GO_TO` a ship in space climbs,
  enters, and arrives". Pinned by
  `test/server/MotionIntentGroupTest#aCraftOnAPlanetToldToGoToAShipInSpaceClimbsEntersAndArrives`.
- **INV-GDN-21** `[T][BEH]` A craft in space told `GO_TO` a point of a planet's surface flies into
  that planet's descent shell, descends onto it with the order in its slot, and lands at the point as
  INV-GDN-10 measures a landing — with no pilot input anywhere on the way. FOR: the same acceptance, "a
  craft in space given `GO_TO` a point on a planet's surface descends and arrives there", and the
  maintainer's of 2026-10-24, *"GO_TO не может скомандовать спуск на планету, а он и не должен. Он должен
  вести к планете. Спуском продолжает управлять одно условие"* (a GO_TO must lead to the planet; one
  condition goes on governing the descent). Pinned by
  `test/server/MotionIntentGroupTest#aCraftInSpaceToldToGoToAPointOfAPlanetDescendsAndLandsThere`.
- **INV-GDN-22** `[T][SYS]` From space a target the world answers far off is the JUMP leg: for a ship
  there, with the hull it last measured, the craft's drive is asked on every tick to take it to a place in
  the ship's own cell farther off it than the arrival radius beyond the standoff, so its arrival is flown;
  the craft is commanded no speed, reads `JUMP`, and is flown past the refusal window while the drive
  answers that the jump is under way; a drive that declines for the window refuses the order as
  JUMP_DECLINED on exactly the window-th tick, naming what declined it; and a surface point or the orbit
  of a far-off body has the drive asked for the destination the world aims the jump to that body at. FOR:
  the rulings of 2026-10-23 (*"по рекомендациям"*): the fixed chain — climb, jump to another
  system, fly through cells, descend — and that the autopilot commits the jump itself, what would have
  stopped a pilot becoming the leg's refusal with its reason; and the maintainer's of 2026-10-21, *"цель в другом
  мире - это же запрос на гиперпрыжок. ИИ должен это уметь"* (a target in another world is a request for a
  jump; the AI must be able to do it). Pinned by
  `test/unit/MotionGuidanceLawsTest#aTargetInAnotherSystemIsTheJumpFromSpaceAndTheClimbFromAPlanet`.
- **INV-GDN-23** `[T][SYS]` In space guidance never sends a craft into the descent shell of a body its
  order does not lead into: a straight way to a ship through such a shell is replaced by one that comes no
  nearer that body than its shell plus `CLEAR_OF_THE_SHELL`, toward the ship's side; a clear straight way
  is flown straight; a craft inside that clearance and leaving it is sent straight on, one there whose aim
  lies across the body is sent along the shell, no nearer; and the shell an `INTO_THE_SHELL` leg leads into
  is flown into. FOR: the decision of 2026-10-25, proposed with part one and taken forward on *"коммить и
  продолжай"* without objection — accepted by continuing, not ruled explicitly: the path is guidance's,
  the one descent condition untouched; the craft is simply never led into a shell that is not its
  target's. Pinned by
  `test/unit/MotionGuidanceLawsTest#aWayThroughTheShellOfABodyTheOrderDoesNotLeadIntoGoesAroundIt`.
- **INV-GDN-24** `[T][BEH]` A craft told `GO_TO` a ship two cells off, in the craft's OWN system, jumps
  there with no pilot input and arrives alongside the ship as INV-GDN-01 measures it. FOR: the acceptance
  stated before the work: "a craft given `GO_TO` a ship in another system jumps (no pilot input) and
  arrives (outside both hulls ± 2 blocks, < 0.2 blocks/s)"; and the decision of 2026-10-25, proposed with
  part two and taken forward on *"Коммить и продолжай"* without objection — accepted by continuing, not
  ruled explicitly — that anything farther than the next cell is jumped to, within the system too. Pinned
  by `test/server/MotionIntentGroupTest#aCraftToldToGoToAShipTwoCellsOffInItsOwnSystemJumpsThereAndArrives`.
- **INV-GDN-26** — retired: a cell of the void is no longer a system of its own; how far a place is is
  counted in cells (MECH-GDN-12), and INV-GDN-30 states the void's case.
- **INV-GDN-27** `[T][SYS]` A ship target the world answers as on its way is waited for: from space and
  from a planet alike, the craft is commanded no speed, reads `WAITING_FOR_THE_TARGET`, is flown past the
  refusal window and never refused for it, asks the drive for no jump, and is never arrived; and on the
  first tick the world places the ship again, the order flies the leg toward it. FOR: the decision of
  2026-10-25, proposed with part two and taken forward on *"Коммить и продолжай"* without objection —
  accepted by continuing, not ruled explicitly: a ship target that is itself in hyperspace is WAITED FOR,
  the craft holds, the leg reads "waiting for the target", the order resumes when the target arrives; no
  refusal. Pinned by `test/unit/MotionGuidanceLawsTest#aShipOnItsWayIsWaitedForAndTheOrderGoesOnWhenItGetsThere`.
- **INV-GDN-28** `[T][BEH]` A craft told `GO_TO` a ship that then jumps two cells away is not refused
  while that ship is in hyperspace, and once it has come out the order goes on toward where it now is —
  the jump leg, which a craft with no drive is refused on as JUMP_DECLINED after the ship's arrival and
  never before it. FOR: the decision of 2026-10-25, proposed with part two and taken forward on *"Коммить
  и продолжай"* without objection — accepted by continuing, not ruled explicitly: a ship target that is
  itself in hyperspace is WAITED FOR, and the order resumes when the target arrives; no refusal. Pinned by
  `test/server/MotionIntentGroupTest#aCraftWhoseShipJumpsAwayWaitsForItAndThenGoesOnTowardWhereItCameOut`.
- **INV-GDN-29** `[T][BEH]` A craft told `GO_TO` a ship two cells off whose cell's world is not loaded
  jumps there, beside the hull the ship last measured itself, and arrives alongside it as INV-GDN-01
  measures it. FOR: the decision of 2026-10-25, proposed with part two and taken forward on *"Коммить и
  продолжай"* without objection — accepted by continuing, not ruled explicitly: the ship ledger stores
  each ship's measured hull radius, so a target in an unloaded cell has a measured standoff, not a
  stand-in. Pinned by
  `test/server/MotionIntentGroupTest#aCraftToldToGoToAShipTwoCellsOffInItsOwnSystemJumpsThereAndArrives`.
- **INV-GDN-30** `[T][BEH]` A craft in the void told `GO_TO` a ship two cells of the void off jumps there
  and arrives alongside it, as INV-GDN-24 measures it. FOR: the decision of 2026-10-25, proposed with part two
  and taken forward on *"Коммить и продолжай"* without objection — accepted by continuing, not ruled
  explicitly — that anything farther than the next cell is jumped to, across void cells alike, which replaced the letter of
  the ruling of the same day (*"Берем (б)"*) that a point in the void is reached by a jump, not by flying
  cell to cell for years. Pinned by
  `test/server/MotionIntentGroupTest#aCraftInTheVoidToldToGoToAShipTwoVoidCellsOffJumpsThereAndArrives`.
- **INV-GDN-25** `[T][BEH]` A craft in space told `GO_TO` a ship whose straight way from it dips into the
  descent shell of a body the order does not lead into goes around that shell, is never descended onto
  the body, and arrives alongside the ship in space as INV-GDN-01 measures it. FOR: the acceptance stated
  before the work: "a craft whose straight line crosses a non-target body's shell arrives without
  descending"; and the decision INV-GDN-23 cites. Pinned by
  `test/server/MotionIntentGroupTest#aCraftWhoseWayCrossesAnotherBodysShellGoesAroundItAndArrives`.
- **INV-GDN-31** `[T][BEH]` A craft whose stored energy is below what the flight of its order's jump costs
  — the jump gate's SUPPLY advisory — jumps all the same with no pilot input, and says so: the order's
  `ShipEvent.MotionIntentJumpAdvisedEvent` names that advisory, and so does the slot's
  `Reading#advisoriesGoneThrough`, read in the world the jump carried the craft to. FOR: the maintainer's
  ruling of 2026-10-26, *"сделай предупреждения"* (make them warnings): an unattended commit goes through
  the gate's advisories as a pilot who confirms them does, each reported, never silent — matching the
  gate's own rulings that none of its advisories is a veto (`navigation/JumpGate.java:332`, `:349`,
  `:400`). Pinned by
  `test/server/MotionIntentGroupTest#aCraftShortOfTheEnergyItsJumpCostsJumpsAllTheSameAndSaysSo`.

## Failure modes & edge cases

A take-off issued while the post-descent entry latch holds (the craft above the line, not yet below it
since its descent) climbs to its aim and hovers there: the entry does not fire until the latch clears.

A ship two cells off is jumped to although it may be a few hundred blocks past the far face of the
next cell, and a ship in the next cell is flown to although it may be nearly two cells' width away: the
measure is the cell, not the distance. A ship target that sits inside the
clearance of another body's shell, or whose standoff does, is never reached: the craft is kept outside
that clearance and rounds the shell. A jump destination beside a ship near its cell's face is held inside
that cell, so it may lie nearer the ship than intended, when the face cuts the ring short. A commit that FAILED is not retried by the order: it declines every later
ask until the order is refused for it or replaced. The pilot's own jump key during a wind-up the order
started stops it and takes the craft back (C9 SHIPCTL-23); pressed while the order is on its jump leg but
not winding up — the gate declining, inside the refusal window — it is an ordinary press, and a wind-up it
starts is taken up by the order on its next ask.

A ship the craft waits for is waited for as long as it is on its way: a transit that never ends, or an
entry whose settle the crossing gave up on and whose ship never ticks in its cell again, holds the craft
until its pilot takes it back. A ship far off that has never measured itself is waited for, not jumped
to beside a hull nobody measured; the ledger keeps the last measurement across a save, so this is the
state of a ship in its first tick in space, not of one whose world is unloaded.

A craft held deep inside a body's shell for a descent that does not come — refused and cooling down, or
the craft not yet settled in the ledger — hovers there and is not refused: the leg stays placed. A ship
target standing in a world that is no body's surface (no body has it for a world) reads as
TARGET_NOT_FOUND, though the ship exists.

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

Events posted: `ShipEvent.MotionIntentArrivedEvent`, `ShipEvent.MotionIntentRefusedEvent`,
`ShipEvent.MotionIntentJumpAdvisedEvent` (C5). Lang: `msg.guidance.refused.*`, `msg.guidance.jumpadvised` (C7). Port: `VSIntegration#hullAboutPosition`, `#shipPhysicsSteps` (C11). The seam's constants
`GalacticCoord.HALF_CELL`, `CellSeam.CARRY_MARGIN` (space). Probe:
`vs motion-go-to-ship-by-id`, `vs motion-hold-ship-by-id`, `vs motion-go-to-surface-by-id`,
`vs motion-read-by-id`, `space auto-takeoff` (the take-off key). Space: `SpaceSubsystem.launchBody` and
`launchBodyAddress`, `ShipEntryController.entryRingAround` and `PHYSICS_CLAMP_ENTRY_MARGIN`,
`DescentShell.radiusAround`, the ship ledger (its rows and the hull each ship last measured,
`ShipLedger.Entry#hullRadius`), `SpaceSlotPool.cellCoordFor`, `StandoffRing.pointAround`, `HyperspaceWorld`;
universe: `UniverseRegistry.skyBodiesAt`; the flight computer's `entryCeiling` and its jump (`JumpTrigger.pressUnattended`,
`TileNavigationComputer#setTargetBody` / `arm`, hyperdrive MECH-HYP-20). Key: `key.autoTakeoffToggle` → `TilePilotSeat.PACKET_AUTO_TAKEOFF_TOGGLE` (C9 SHIPCTL-22).

## Config surface

None.

## Test coverage

`test/unit/MotionGuidanceLawsTest` (the laws: INV-GDN-04, -05, -11..13, -15, -16, -19, -22, -23, -27, SHIPCTL-21);
`test/unit/AutoTakeoffPlannerTest` (the corridor);
`test/server/MotionIntentGroupTest` (the in-world flights: INV-GDN-01, -03, -07..10, -14; across worlds:
-20 up to a ship in space, -21 down to a point of the overworld — each with the target, or the craft, moved
in space by the test across a distance no scenario affords; -24 and -29 a jump two cells along within the
overworld's system to a ship whose world was unloaded, -30 one two cells along the void, -28 an order
waiting for its ship through that ship's own jump, -25 a way around the overworld's shell; SHIPCTL-23 the
jump key during the order's wind-up);
`test/server/VSShipAutoTakeoffTest` (take-off: INV-GDN-17 — arrival at the orbit it was entered onto —
-18, SHIPCTL-21 mid-climb, SHIPCTL-22). Not seen in a running game: an orbit flown to from far off in
space (INV-GDN-16 pins it on the law), and a MOON's orbit — a moon moves
inside the cell it shares with its parent, so a craft held on its ring follows it and is not declared
arrived while that is faster than the arrival speed. Not seen
in a running game: a ship parked inside the carry band past a face, and a ship record with no blocks
(INV-GDN-13 and -15 are pinned on the law alone); the order reading `JUMP` while the craft is in
hyperspace (INV-GDN-24, -29 and -30 jump through it, and see only the arrival), and a jump short enough to
be crossed directly; a jump to a far-off body; a jump refused on a DRIVE clause, or for a failed commit (INV-GDN-09 is
refused on the POWER clause); the DESTINATION_FULL and WINDOW_UNDERSIZED advisories gone through
(INV-GDN-31 goes through SUPPLY's), and what a seated pilot is told of one; a ship in the next cell across a system's border or into the void, flown to
(INV-GDN-03 flies to the next cell within one system); a zone's cell and the cell it is nested in, which
count as next to each other; and the wait for a ship that came into space far off and has not measured
itself yet (INV-GDN-27 pins it on the law). That the substrate turns a hull at a slow rate,
which INV-GDN-14 rests on, is pinned on the substrate's side (valkyrienskies INV-VSK-14).

## Open questions

- Whether `HOLD(ship, range)` keeps the craft anywhere in the shell (built) or at the range itself.
- A ship in another ZONE parked inside the band at its sphere where the zone does not yet take a craft
  in is made for and never reached: only the cube faces of one lattice are planned through.
- A lean is planned on the hull's BURST angular authority, as a turnover is, but held for as long as
  the craft leans: a hull that can turn about an axis only for its wheel's seconds leans along it and
  then drifts, rather than being refused for want of a sustained turn. Unruled.
- Which cells are NEXT to each other across lattices: a zone's cell and the cell the zone is nested in
  are (one crossing of the zone's sphere joins them); a zone's cell and a neighbour of that outer cell are
  not, although the zone's sphere may reach past its own cell. Chosen from the code, not ruled.
- A wait has no end of its own: a ship that never comes out of its transit, or never ticks in its cell
  again after an entry the crossing gave up on, holds the waiting craft until its pilot takes it back.
  Whether such a wait should ever turn into a refusal is unruled.
- The jump key pressed while the order is on its jump leg but no wind-up runs (the gate declining) is an
  ordinary press; a wind-up it starts is taken up by the order. Whether that press, too, should take the
  craft back is unruled — the decision of 2026-10-25 names a press DURING the order's wind-up.
