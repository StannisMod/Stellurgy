---
id: ship-flight-model
owns: [ship/, api/IShipActuatorBlock.java, block/BlockReactionWheel.java, block/ChemicalMotorActuators.java, integration/vs/HullSurvey.java, integration/vs/PhysicsUnits.java, integration/vs/ShipHullMass.java, integration/vs/ShipInertiaWriter.java, integration/vs/ShipMassTrigger.java, integration/vs/StellurgyBlockMass.java, integration/vs/StellurgyWorldGravity.java, tile/TileReactionWheel.java]
entrypoints: [TileAdvancedFlightComputer#update→tickFlightModel, TileAdvancedFlightComputer#onPhysicsTick, ShipMassTrigger.Hooks#onShipNamed, TileRocketAssemblingMachine#scanRocket (tier-2 readout)]
depends-on: [util-core (WeightEngine), dimension-planets, network-wire, rocket-assembly]
depended-by: [hyperdrive, client-render (HUD lines)]
contracts: [C1, C2, C3, C5, C7, C9, C10, C22]
confidence: high for the kernel (unit-pinned), medium for the in-world wiring (e2e owed)
---
## Purpose

Turns what a tier-2 hull IS — its blocks, what they hold, who rides it, which devices aboard can push
or turn it — into what it can DO, and makes the flight controller fly by that. One mass model for
every physics object; one actuator abstraction; one readout every surface draws from.

## Responsibility boundary

Owns: the mass frame and its write into the physics record; the per-world gravity port; the actuator
abstraction and the two devices that implement it; the clean-authority solve, recipes and command
composition; the derived flight model on the flight computer, its rebuild triggers and readout
channels; the controller's allocation step. Does NOT own: the flight LAW (velocity/attitude targets —
`FreeFlightPhysics`, rocket-entity), pilot input and seat binding (C9), the assembler's scan walk
(rocket-assembly), the drive/shield readouts (hyperdrive, shields), crossing (C18/C22).

## Key types

| type | role |
|---|---|
| `ShipMassFrame` / `ShipMassFrameBuilder` / `MassContributor` | structural/content/crew kg, CoM, body-frame inertia about CoM; order-independent, regularised tensor |
| `ShipHullMass` | authoritative pass over a ship (structure+content+crew) or a pad box (no crew), subspace frame |
| `ShipMassSource` / `FlightComputerMassSource` | the port every mass reader asks, by durable id; today's implementation answers only for a craft that carries a flight computer |
| `ShipInertiaWriter` / `ShipMassTrigger` | write mass+CoM+tensor together; recompute on `ShipNamed`, background round |
| `StellurgyBlockMass` / `StellurgyWorldGravity` / `PhysicsUnits` | block mass for the engine's delta path; per-world gravity; SI↔engine factor |
| `IShipActuatorBlock` | block contributes actuators (design) and says whether it works (live) |
| `Actuator` / `ActuatorId` | a bounded linear wrench contribution: point force `[0,1]` or pure torque `[-1,1]` + momentum capacity |
| `ReactionWheel` / `ChemicalMotor` | what each device IS to the physics system, with no world: a wheel's three torques and figures, a motor's force at its block centre away from a given nozzle |
| `ControlFrame.HELM` | the helm frame every flight model is solved in (the flight law's body basis at identity) |
| `ShipCapability` / `BoundedSimplex` / `Recipe` | 12 directions × {sustained, burst} clean authority by LP, cached recipes |
| `ControlScheme` / `CleanAxisScheme` / `ActuatorCommand` / `MomentumStore` | wanted accel → throttles → wrench; stored-momentum bookkeeping |
| `ShipFlightModel` / `ShipReadout` | immutable DESIGN+LIVE capability at a revision; the primitives + arithmetic all surfaces use |
| `HullSurvey` | one visit: mass frame + design/live actuators + construction count |
| `BlockReactionWheel` / `TileReactionWheel` | three pure-torque actuators; stored momentum in tile NBT |

## Mechanics

- **MECH-SFM-01 — mass frame.** One accumulating pass shifted to the CoM by the parallel-axis theorem;
  a block is smeared over its extent so a single cube or a mast stays invertible
  (`ship/mass/ShipMassFrameBuilder.java`) `[T]` (`ShipMassFrameTest`). Frame is the ship's SUBSPACE.
- **MECH-SFM-02 — the write seam.** `ShipNamed` ASSEMBLED/PASTED compares then writes; LOADED writes
  without comparing; the AFC's round writes content+crew every `MASS_ROUND_TICKS` (`tunable`, phased
  by ship id) (`integration/vs/ShipMassTrigger.java`, `TileAdvancedFlightComputer.java:1204`) `[T]`.
  Every frame it writes, and the one `HullSurvey` builds the flight model on, comes through the port
  `ShipMassSource` (`integration/vs/FlightComputerMassSource.java`): a craft WITHOUT a flight computer
  is answered `null` and keeps the physics engine's own mass (ruled 2026-08-17) `[A]` — no test
  weighs a computer-less hull, because every tier-2 fixture carries a computer.
- **MECH-SFM-03 — gravity field.** Space cell 0; a registered Stellurgy world the configured vector ×
  its multiplier; anything else the configured vector (`integration/vs/StellurgyWorldGravity.java`) `[T]`.
- **MECH-SFM-04 — actuators.** A chemical motor is one force at its block centre pushing AWAY from its
  nozzle (`ship/control/ChemicalMotor.java`) `[T]`; the nozzle is the ACTUAL-state facing (follows the
  feeding tank) and it works unless worn to its last stage — the two things the world tells it
  (`block/ChemicalMotorActuators.java`) `[V]`. A reaction wheel is three pure torques (`TORQUE`,
  `MOMENTUM_CAPACITY`, `tunable`) (`ship/control/ReactionWheel.java`) `[T]`; its block only places it
  (`block/BlockReactionWheel.java`). The device definitions live in the physics system,
  so the kernel is tested with production's own devices and no world.
- **MECH-SFM-05 — clean authority.** Per signed direction and endurance: maximise `s` subject to
  `W u = s t` (t = unit force, or `I·axis` for a rotation), each throttle in range; sustained solves
  over sustained devices only; burst over all, and IS the sustained recipe when no better
  (`ship/control/ShipCapability.java:75-95,190-338`) `[T]` (`ShipMotionLawsTest`).
- **MECH-SFM-06 — composition.** Per axis `p = want/authority`, sustained recipe if it suffices else
  burst, clipped at 1; one global λ over the summed throttles; then each stored-momentum device
  clipped to what its capacity still allows (`ship/control/CleanAxisScheme.java`) `[T]`. The burst is
  taken only if every wheel it leans on can take THIS step's share of it on top of what the axes
  already composed took from the same wheel; otherwise the sustained recipe (`burstIfItCanDeliver`)
  — so the final clip never bites a balanced recipe and a spent wheel stops
  short of full by up to one step's share.
- **MECH-SFM-07 — rebuild.** The AFC re-surveys when the physics record's construction count moved
  (coalesced to `SURVEY_MIN_TICKS`, `tunable`), on the load round, or when it has no model; posts
  `FlightModelChangedEvent`; seeds wheel momentum from wheel tiles once and writes it back every tick
  (`tile/TileAdvancedFlightComputer.java:1304-1360`, `ShipDataMethods.java:39`) `[V]`.
- **MECH-SFM-08 — the controller.** Wanted linear accel (deadbeat + gravity feed-forward, uncapped)
  and angular accel (attitude law, rate capped at `min(old cap, max(sqrt(2·α·angle), |ff|))`) →
  SI ÷ `PhysicsUnits.ACCELERATION` → ship frame → `scheme.allocate(live, …)` → wrench ×
  factor → world → applied (`TileAdvancedFlightComputer.java:1725-1850,1891`) `[V]`. No model ⇒ no
  force, saturated.
- **MECH-SFM-09 — readout channels.** Pilot-seat occupant and open-console viewers get
  `PacketShipReadout` on a new revision, and the live slice at most every `READOUT_PUSH_TICKS`
  (`:1414`) `[V]`; the assembler sends its scan readout to whoever pressed Scan/Build
  (`TileRocketAssemblingMachine.java:740`) `[V]`.
- **MECH-SFM-10 — tier-2 assembly gate.** A build with an AFC skips the rocket thrust/fuel checks
  (`:697,721`); under TWR 1 in the local field the first press warns and remembers the TWR, a press on
  the same build assembles (`:827`) `[V]`.

## State & persistence

| key | owner | note |
|---|---|---|
| `storedMomentumX/Y/Z` | `TileReactionWheel` | N·m·s given to the hull per ship axis; rides tile NBT through crossings |
| — | flight model, recipes, readout | derived, never saved (STAT-2) |
| `constructionRevision` | `ShipData` (vendored VS) | transient; only CHANGE is meaningful |

## Invariants

- **INV-SFM-01** `[T][SYS]` Every recipe keeps each throttle in its device's range and leaves off-axis
  force/torque ≤ `AllocationTolerances` (1e-6 of the largest column; measured worst 1.9e-15)
  (`ShipMotionLawsTest#everyRecipeStaysInRangeAndDoesOnlyWhatItNames`). FOR: INV-SFM-10.
- **INV-SFM-02** `[T][SYS]` A sign is not a symmetry: 2 aft + 1 fore engines give surge 2T : T. Pinned by `ShipMotionLawsTest#aSignIsNotASymmetry`. FOR: INV-SFM-10.
- **INV-SFM-03** `[T][SYS]` A bad build is weak, never spinny: an off-centre engine is not clean thrust. Pinned by `ShipMotionLawsTest#anOffCentreEngineIsNotCleanThrust`. FOR: INV-SFM-10.
- **INV-SFM-04** `[T][SYS]` A wheel turns, never pushes, and only for `capacity/torque` seconds; a full
  wheel turns no more in that sense and can unwind. Pinned by `ShipMotionLawsTest#aWheelTurnsButDoesNotPush`, `ShipMotionLawsTest#aFullWheelTurnsNothingMore`. FOR: INV-SFM-10.
- **INV-SFM-05** `[T][SYS]` Recipes are bit-identical under any listing order (`ActuatorId` sort + Bland). Pinned by `ShipMotionLawsTest#theAnswerDoesNotDependOnListingOrder`. FOR: INV-SFM-10.
- **INV-SFM-06** `[T][SYS]` A combined command never drives a device past full and keeps the requested
  proportions (λ); the live momentum clip is a SECOND defence of the range, not of the proportions. Pinned by `ShipMotionLawsTest#aCombinedCommandIsScaledNotClipped`. FOR: INV-SFM-10.
- **INV-SFM-07** `[T][BEH]` Cargo lowers acceleration, never force; TWR is about the local field and is
  infinite where there is none (`ShipReadoutTest`). Pinned by `ShipReadoutTest#cargoLowersAccelerationNotForce`, `ShipReadoutTest#thrustToWeightIsAboutTheLocalField`.
- **INV-SFM-08** `[V][BEH]` Mass is planet-independent; gravity enters only the field and TWR.
- **INV-SFM-09** `[A][BEH]` A hull with no actuators does not move under command — no e2e pins this yet.
- **INV-SFM-10** `[A][BEH]` A built ship accelerates as its readout predicts — no e2e pins this yet.
- **INV-SFM-11** `[T][V][SYS]` A wheel the command leaves idle is given back its momentum: run
  toward empty, never past, while the sustained rotation recipes cancel its moment, in the room the
  command left (one common factor, the command never scaled for it); a wheel the command uses is not
  touched, and one whose moment nothing sustained can cancel keeps what it holds
  (`CleanAxisScheme#desaturate`; `ShipMotionLawsTest#anIdleWheelIsGivenBackByTheThrustersWithoutTurningTheHull`,
  `…#aWheelInUseIsNotUnloadedUnderTheCommand`; in-world `TierTwoCraftFlightModelGroupTest#anIdleCraftGivesItsWheelBack`,
  a wound fixture wheel 0.0588 → 0 of capacity in 20 ticks). Propellant is not charged for it until
  STAT-22 lands. FOR: INV-SFM-10.
- **INV-SFM-12** `[T][SYS]` A command is delivered exactly, or less and flagged saturated — never on an
  axis not asked for, never the wrong sign, never more; every throttle in range and every wheel inside
  its capacity, on every step of a held command from any wheel state, a wheel inside its last step
  included; and a command the hull can hold is delivered exactly, never flagged. FOR: a pilot's
  command does what it names and nothing else (C9 ship control) — the craft does not turn under a
  straight push. (`test/unit/ShipMotionLawsTest#everyCommandIsDeliveredCleanlyOrLessAndSaidSo`:
  300 generated hulls × 12 commands × 90 steps, arrival counted at the call;
  `…#whatTheHullCanHoldIsDeliveredExactlyFromAnyWheelState`, judged by the symmetric hull's geometry;
  in-world
  `TierTwoCraftFlightModelGroupTest#aCraftWhoseWheelIsSpentDoesNotTurnUnderAStraightCommand`, max
  |ω| 3e-13 rad/s against 7.4 with the burst taken unconditionally).
- **INV-SFM-13** `[T][SYS]` What the readout states for a LIVE direction and endurance is what the
  scheme delivers: a command in that direction at the readout's figure is delivered whole — for a
  translation the figure is the force over the hull's whole mass, for a rotation the angular
  acceleration itself — and a direction the readout calls `NO_AUTHORITY` is delivered nothing. FOR:
  INV-SFM-10 (a built ship accelerates as its readout predicts). The joint readout ↔ scheme; each side's
  own law is INV-SFM-07 and INV-SFM-12. Pinned at the handoff, each witnessed by breaking the handover
  in `ShipReadout#of` (`test/integration/ShipMotionJointsTest#aReadoutFigureIsDeliveredWhole`,
  `…#aDirectionTheReadoutCallsUnavailableIsDeliveredNothing`); the cargo joint MECH-SFM-01 ↔ INV-SFM-07
  by `…#cargoTheFrameWeighsIsTheMassTheReadoutDividesBy`.
- **INV-SFM-14** `[T][BEH]` A motor worn to its last stage puts no force into the craft: it stays in
  DESIGN, so the readout shows what was lost, and is absent from LIVE, the only set the flight computer
  allocates over. The kernel knows no wear — it takes each device as a force interval from zero to its
  maximum — so the decision is the world side's, in three places: the wear threshold
  (`block/ChemicalMotorActuators.java`, `working`), the routing into LIVE (`integration/vs/HullSurvey.java`,
  `collect`) and the controller allocating over `model.live()` (`TileAdvancedFlightComputer.java:1869`).
  Wear is not a hull change, so a motor worn in flight is dropped at the next load round of the survey
  (`MASS_ROUND_TICKS`, 100 ticks, `TileAdvancedFlightComputer.java:1355`) and pushes until then `[V]` —
  a divergence from this clause, open: either the latency becomes part of it, or a wear change marks the
  model stale.
  Pinned by `test/server/TierTwoCraftFlightModelGroupTest#aMotorWornToItsLastStagePutsNoForceIntoTheCraft`,
  witnessed at each of the three places (maintainer 2026-10-07: *"Ядро не знает про износы, в том числе про
  полностью сломанное. Оно оперирует силами, точнее, промежутками от нуля до максимума силы. Переход из
  мотора в силу делает Minecraft"*).
- **INV-SFM-15** `[T][V][SYS]` A physics step flies ONE command whole — never the velocity of one with the
  attitude or rate of another. The flight computer's command is written on the server game thread and
  flown on the physics thread on every step until the next, so its parts (velocity to hold, angular
  velocity, attitude target) cross as one value: an immutable `ship/control/FlightCommand`, published
  through one `volatile` reference per channel (`TileAdvancedFlightComputer#flightCommand`, and
  `#probeCommand`, which outranks it while set), and read once per step at the top of
  `TileAdvancedFlightComputer#onPhysicsTick`, which flies that object alone. The flight model crosses the
  same way (`#flightModel`). FOR: a pilot's command does what it names and nothing else (C9 ship
  control) — a half-updated command is one nobody gave. **Pinned half**: the value cannot change after it
  is published — nothing its publisher still holds and nothing a reader is handed reaches inside it
  (`test/unit/ShipMotionLawsTest#aPublishedCommandCannotChangeUnderItsReader`). **Read, not tested** `[V]`:
  the one reference and the single read — no tier stages a publication between two reads of the
  controller today; that needs a seam into it (or a test mixin between the reads).
- **INV-SFM-16** `[V][SYS]` No physics step books a wheel while its momentum is being seeded. The physics
  step books what each wheel gives (`MomentumStore#absorb`, a read-modify-write) on the physics thread,
  and the game thread seeds a newly surveyed wheel from its tile (`MomentumStore#restore`); the map is
  concurrent, which makes each read and write safe but not the pair. The two never meet on one wheel
  because of an ORDER: the survey seeds every new wheel BEFORE it publishes the model that lists it
  (`TileAdvancedFlightComputer#rebuildFlightModel`: the seeding loop, then `ShipFlightModel.solve`, then
  `flightModel = model`), and a step books only the wheels of the model it read. A change that seeds
  after publishing, or books a wheel absent from its model, breaks it. FOR: INV-SFM-11 (a wheel's
  momentum is conserved — given back, never invented or lost). Not pinned: no tier can play the seed
  against a step today; it needs a seam into the survey.

## Failure modes & edge cases

A non-converging or residual-breaking solve gives that direction ZERO authority and logs it
(announced, never silent). A massless or unsurveyable hull has no model: the controller applies no
force. The first few ticks after naming, before the first survey, the ship is un-driven. A stored
momentum restored from a wheel tile is seeded once per AFC instance.

## Integration seams

Events: `ShipLifecycleEvent.ShipNamed` (consumed), `ShipEvent.FlightModelChangedEvent` (posted).
Packet: `PacketShipReadout` (S→C, addressed to a tile). Vendored VS edits: `BlockPhysicsDetails`
(block mass), `PhysicsCalculations.applyGravity` (field), `ShipDataMethods`/`ShipData`
(construction count). Probe: `vs flight-model`.

## Config surface

None of its own. `contentMassScale` scales content mass (util-core); `VSConfig.doGravity`
gates the field.

## Test coverage

`ShipMassFrameTest`, `ShipInertiaWriterTest`, `ShipReadoutTest`; **`ShipMotionLawsTest`** (unit — the
owner's LAWS, one class: the capability solve's, each red-witnessed, and the scheme's over time on
production-built hulls — INV-SFM-12 and its liveness twin, burst endurance and buy-back, hulls without
the means); **`ShipMotionJointsTest`** (integration — the owner's JOINTS, each witnessed at the handoff:
readout ↔ delivery INV-SFM-13, mass frame ↔ readout MECH-SFM-01 / INV-SFM-07); shared instruments `test/ShipMotionCases`, `test/CleanCommandLaw`; e2e for the mass round and gravity. No in-world e2e pins
MECH-SFM-07..10 yet.

## Open questions

- There is no live `shipId → AFC` index of our own, because the physics engine already keeps it: the
  flight computer is an `IPhysicsBlockController`, and `PhysicsObject.getPhysicsControllersInShip()` is
  the live, replaced-on-reload set of a loaded ship's controllers. `VSBridge.flightComputerOfLoadedShip`
  reads it; `VSIntegration.flightComputerOf` and `FlightComputerMassSource` both go through
  it. An UNLOADED ship answers `null` rather than force-loading the yard. The positional
  `flightComputerAt` still scans.
- The magnetic-moment form of wheel desaturation near a magnetised planet is not built (no planet
  carries a field); the thruster form is INV-SFM-11.
- Budgeted resource availability is a seam only.
