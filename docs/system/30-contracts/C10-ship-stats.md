---
id: C10
covers: tier-2 ship characteristics derived from construction — the stat surface (mass, linear/angular authority, drive/shield/energy readouts), its live-recompute container, storage split, split-fragment identity, and pre-flight readout; and the tier-1 rocket launch gate (STAT-26..29)
confidence: mixed — the clause tag says how far each is built (see the status table at the end); balance magnitudes are `tunable`, never pinned
owner-subsystem: ship-flight-model (see the status table at the end)
see-also: [C9 (ship-control — SHIPCTL-*; STAT-7 amends SHIPCTL-2), C8 (crew any-attitude), C12 (ship heat — the `heat` slot), C25 (hyperphysics)]
---

# C10 — Ship stat surface contract (tier-2 ships), clauses STAT-1..25; tier-1 launch gate STAT-26..29

**RATIFIED by the maintainer 2026-07-24**; the central-calculator clauses were ruled 2026-08-17 and
the propulsion-physics clauses 2026-10-02. `STAT-n` are permanent anchors: never renumber, never
reuse. Balance magnitudes are `tunable`, never pinned. A clause is `[A]`
unless built; the tag is the only thing that says which, and a `[V]` citation pins the code a clause
reuses or is violated by.

**Terms.** **stat vector** = the per-ship, construction-derived characteristic set. **ship marker** =
the `TileAdvancedFlightComputer` (AFC), bearer of the durable `shipId`
(`TileAdvancedFlightComputer.java`) `[V]`. **live recompute** = re-derivation of the vector
from the hull on any block change. **derived stat** = re-derivable from the blocks (thrust,
drivePower, shield capacity, structural mass). **durable state** = non-derivable per-ship dynamic
state (capacitor charge, content-mass sample, wheel trim, damage). **Stellurgy ship** = a VS `PhysicsObject`
whose `ShipData.getUuid()` Stellurgy owns; **foreign ship** = any other physo.

## Clauses

**Derivation & recompute**
- **STAT-1** `[A][SYS]` Every tier-2 flight characteristic (linear authority per axis, angular authority,
  mass, drivePower, shield capacity-per-direction, energy gen/draw) is DERIVED from the built hull,
  never a compile-time constant; a force that cancels mass exactly (`force = accel·mass`) would make
  every craft fly identically. FOR: INV-SFM-10.
- **STAT-2** `[A][SYS]` The vector is a live-recomputed VIEW — it re-derives on hull change (build, damage,
  repair) with no re-assembly. No derived stat is persisted (persisting it re-introduces the
  staleness this design exists to avoid). FOR: INV-SFM-10.
- **STAT-3** `[A][SYS]` Durable, non-derivable state lives in its OWNING tile's NBT and rides the ship via
  `StorageChunk` cut/paste (AFC precedent `TileAdvancedFlightComputer.java`) `[V]`; the
  recompute READS it. A durable field NOT in its tile's NBT is a violation (lost across a crossing). FOR: save format: ship durable state rides the hull.

**Identity & split**
- **STAT-4** `[A][SYS]` Exactly one stat vector per ship, keyed on the AFC `shipId` (single ship marker).
  A hull with no AFC has no vector. FOR: INV-SFM-10.
- **STAT-5** `[A][BEH]` On a craft split, the AFC-bearing fragment keeps its `shipId` and live-recomputes
  its now-reduced vector; an AFC-less fragment is inert debris (no vector, no jump, no controllable
  flight). Installing an AFC into a debris fragment mints a fresh `shipId`
  (`getOrCreateShipId`) `[V]` and recomputes a vector from its blocks — the ordinary marker path, no
  split-time special logic.

**Mass**
- **STAT-6** `[A][SYS]` **One mass model for EVERY physo, in kilograms, with no calibration factor.** Mass is
  computed by Stellurgy's mass model (`ship/mass`, built on `WeightEngine`, `util/WeightEngine.java`) and
  written into the physics engine's own record by one boundary writer
  (`integration/vs/ShipInertiaWriter`, STAT-20); the engine's incremental accumulator
  (`BasicCenterOfMassProvider`, which `ShipDataMethods` holds) is the working path and the writer is the
  authority `[V]`. The model has no "is this ship ours" predicate and no delegation branch; the one hull
  the writer does not reach is a hull with no flight computer (STAT-20). The unit is the
  **kilogram**, because VS's own world is SI once `1 block = 1 m` is declared — its default block is
  500 (`BlockPhysicsDetails.java`), i.e. 500 kg/m³ — so a kilogram table and a kilogram integrator
  need nothing between them. What remains split is the **cadence, not the rule**: the per-block table
  applies to all; content (fluids, inventories) and crew re-sampling runs only for ships that have an
  AFC. Content mass AND occupant mass are re-sampled on a Stellurgy cadence, not the
  `Chunk.setBlockState` hook alone. Upstream `BasicCenterOfMassProvider` and
  `BlockPhysicsDetails.getMassFromState` drop blockstate and tile contents (a full tank would weigh an
  empty one's mass) `[V]`. A VS-built craft that is not a Stellurgy ship weighs what the table says
  rather than a flat 500/block.
  **Tier-1 half, in kilograms and newtons:** FOR: INV-SFM-10.

  | what | where | value |
  |---|---|---|
  | block mass table | `util/WeightEngine.java` `defaultMaterials()` | kilograms per block; ordinary block **500**, wood 750, stone 2000, iron 5000, anvil 7500; `fallback` 500 |
  | fluid mass | same file, `defaultFluids()` | a mB is a litre of the condensed form: density / 1000 kg (`weight-mass` INV-WGT-09) |
  | Stellurgy component masses | same file | an empty propellant tank is 10 % of what it holds full (1 m³ of its role's fluid): hydrazine 102.1, hydrogen (bipropellant fuel, nuclear working fluid) 7.08, oxidizer 114.1; motor 10 000, guidance 9000, pressure tank / satellite hatch 25 000 |
  | engine thrust | `block/BlockRocketMotor` and siblings (`getEngineDesign`) | **newtons**, rated `F_max`: basic and bipropellant 490 500, nuclear nozzle 1 716 750, advanced 2 452 500; a nuclear core is rated in WATTS, 1.1·10⁹ (STAT-35) |
  | the field | `api/StatsRocket` | `mass`; `getMass()`, `getDryMass()`; NBT key `mass` (C1) |
  | local weight | `api/StatsRocket.weightNewtons` | `mass · STANDARD_GRAVITY (9.81) · gravityMultiplier`, one expression every gravity-dependent quantity reads |

  - **Calibration anchor** `[V]` (arithmetic, not pinned by a test): one basic motor's 490 500 N is
    `100 · 500 kg · 9.81` — exactly a hundred ordinary blocks at one gee. Editing either table without
    the other moves what every rocket in the pack can lift.
  - **Thrust over weight is independent of the unit**: the mass table and the thrust table are scaled
    together (`thrust / (mass · g)` — TWR, `minLaunchTWR`, which rockets fly, the ascent profile).
  - **A player's `weights.json`** is versioned and a table from another schema is set aside, never read:
    `weight-mass` MECH-WGT-06.
  - **The gravity reference is VANILLA**: vanilla's `0.08` blocks/tick² already IS 9.81 m/s², because a
    tick is 90.3 ms — confirmed against four unrelated vanilla constants (terminal velocity, jump
    impulse, walk, sprint). `VSConfig.gravityVecY` is **-32**, `DRAG_CONSTANT` **0.98**, and Stellurgy's
    constants are one shared `StatsRocket.GRAVITY_BLOCKS_PER_TICK_SQUARED`. Ship, rocket and player fall
    identically by construction; a tier-2 player must not fall faster than the deck he stands on.
    **Units for every derived stat**: a per-tick quantity converts to m/s² by dividing by `0.0903²`,
    never by `0.05²`; a HUD printing m/s divides blocks/tick by 0.0903.
  - `WeightEngine`'s **toughness** column (what a block costs to DAMAGE rather than what it masses) is a
    separate quantity and is not scaled with the mass table: `weight-mass` MECH-WGT-07.

**Authority (couples C9)**
- **STAT-7** `[A][SYS]` Linear translation + braking authority is GEOMETRIC — thrust applied at each
  engine's own point (`PhysicsCalculations.addForceAtPoint`, public) so torque about the true centre
  of mass is produced for free; it must be BUILT, not granted. Attitude ADDITIONALLY admits
  an ADDITIVE, placement-independent, TORQUE-ONLY reaction wheel: count-additive,
  damage-degrading, with persistent player trim (durable, STAT-3). The thrust allocator
  combines geometric-thruster torque + additive wheel torque against pilot intent. **AMENDS C9
  SHIPCTL-2** ("motion corresponding to the input in axis and sign"): a hull is weak on an axis it
  cannot cleanly deliver and never forced to yaw when the pilot presses forward (STAT-7b). FOR: INV-SFM-10.
- **STAT-7a** `[A][SYS]` A stat-derived authority must never exceed Stellurgy's own solver-stability speed guard
  (a force-level ceiling bounding an OUTCOME, not merely a setpoint). In the built flight model the
  bound is the velocity SETPOINT cap (`SHIP_MAX_SPEED`), not authority. FOR: INV-SFM-10.
- **STAT-7b** `[A][SYS]` Realising pilot intent is a **PER-AXIS AUTHORITY** model, signed, inertia-aware and
  solved under constraints (STAT-15..18), not a coupled wrench solve: for each of 6 axes the hull
  exposes a max CLEAN force/torque from geometry + the LIVE working-thruster state (fuel/power this
  tick); input is a signed % per axis; the system delivers that % of the axis authority. A degenerate
  build is WEAK on an axis it cannot cleanly deliver, **never forced-spinny**. FA / X / manual are
  one unified % producer; external ship-on-ship push is a VS-level interaction OUTSIDE this model.
  Boost is a drive's low-exhaust-velocity REGIME — a different authority table, not a multiplier over
  one; STAT-7a still binds whatever regime is live. The intent→per-thruster mapping is a swappable
  `ControlScheme` seam: the clean per-axis force above is the DEFAULT implementation; alternative
  schemes plug into the same seam over the same substrate, and the player selects the active one via
  a synced setting (allocation stays server-side). FOR: INV-SFM-10.

**System-projected readouts (the vector is assembled, not authored)**
- **STAT-8** `[A][SYS]` Shield capacity enters the vector as a capacity-per-direction READOUT — a
  projection of the emitter-Voronoi field onto the hull's principal faces. The
  field GEOMETRY is owned by the shield subsystem, never duplicated into the vector. FOR: INV-SFM-10.
- **STAT-9** `[A][SYS]` Drive stats (`drivePower`, `inFlightDraw`, `burstCost`) are contributed by the
  hyperdrive field-generator scan; window coverage by the emitter scan.
  The vector carries the readouts; the drive machines own the numbers. FOR: INV-SFM-10.
- **STAT-10** `[A][SYS]` ONE stat vector, assembled via a REGISTRATION SEAM each designed system
  contributes its readout to. No parallel stat vocabulary exists — capacity /
  recharge / upkeep / coverage are components of THIS vector, with each system specifying behaviour
  only. FOR: INV-SFM-10.

**Survival & feedback**
- **STAT-11** `[A][BEH]` The vector survives the frame-crossing paths — crossing, transit,
  relog: durable inputs ride NBT (STAT-3) and derived stats recompute on
  load (STAT-2). Nothing is lost beyond what already rides the NBT.
- **STAT-12** `[T][BEH]` **Tier-2 warns and builds on confirmation; tier-1 refuses.** The vector + per-direction
  weak-spot flags are readable BEFORE flight on two co-primary surfaces: the AFC ship-info GUI
  (`IModularInventory`, LIVE) and the rocket-assembler GUI (snapshot on the existing Scan button,
  which already runs `scanRocket` `TileRocketAssemblingMachine.java`) `[V]`. Localization is
  per-axis / per-direction ("no +Z brake", "ventral shield gap", "emitter coverage 60%"), never a
  scalar aggregate. The readout is soft performance, distinct from the HARD assembly-failure
  `ErrorCode`s (a craft that did not materialise); they share the assembler surface, and the readout
  does not absorb that channel. **The gate differs by tier**: a tier-2 craft whose thrust cannot lift
  it off the body it was assembled on is built **after an explicit confirmation**; a tier-1 rocket is
  refused outright (STAT-26..29). **Both tiers judge the craft FULL and as BUILT** (maintainer ruling
  2026-10-18, *"всё по рекомендациям"*, on the fork "the assembler judges a tier-2 craft on DESIGN with its
  tanks full, as tier-1 already is"): the assembler's ship readout is solved on the pad's mass frame with
  every rocket fuel tank full — its room filled with the fluid it holds, or, holding none, with the
  heaviest its role accepts, STAT-29's choice (`HullSurvey#padMassWithTanksFull`) — and the gate, the
  screen's thrust-to-weight and the warning all read its DESIGN view
  (`TileRocketAssemblingMachine#assembleRocket`, `#updateText`). So whether the pad's tanks are filled
  yet, and which drives the pool feeds now (STAT-38), never decides a build; the LIVE view is the
  console's. `[T]` `TierTwoCraftFlightModelGroupTest#theAssemblerJudgesAShipFullAndAsBuilt`. REGIONS:
  `clears full`, `clears only empty`. The asymmetry is the tiers' actual difference: a tier-2 hull can be
  **built onto in place** — adding a thruster is an ordinary block placement, folded into mass and
  inertia by the same hook the mass model rides — so a low-TWR ship is one you finish, not one you
  lose. A rocket has no mid-flight building and no control authority, so an under-thrusted one is
  unrecoverable. The confirmation needs STAT-14's field to be meaningful (TWR against WHICH gravity).
- **STAT-13** `[A]` All balance magnitudes (thrust-per-block, angular rates, drivePower curve, shield
  regen, re-sample cadence, the reflection restitution, the wheel momentum budget (STAT-15), the gimbal
  cone (STAT-16), the reconciliation period (STAT-20), the residual tolerances (STAT-16)) are
  `tunable`, never pinned. There is no `weightMaterialScale` and no mass-scale calibration factor
  (STAT-6; C4).

## Clauses — gravity, authority shape, allocation, mass seam, channel

### STAT-14 `[V]``[T]` — Gravity is a per-world FIELD supplied by Stellurgy

A planet's `gravitationalMultiplier` reaches the physics loop: a tier-2 ship must not feel Earth gravity
on the Moon, on a gas giant and in a space cell alike. The physics loop asks Stellurgy for the world's
gravity vector through a port, edited in place in the vendored tree — `PhysicsCalculations.applyGravity`
calls `integration/vs/StellurgyWorldGravity#of(World)` (`PhysicsCalculations.java:252`), and the flight
computer's feed-forward calls the SAME function (`tile/TileAdvancedFlightComputer.java:1440`, `:1802`),
because the controller cancels what the solver adds and two answers would make a hovering craft climb or
sink by their difference. Three cases (`StellurgyWorldGravity.java:65-90`): a space cell gets **zero**; a
registered Stellurgy world gets the CONFIGURED vector scaled by its own multiplier; anything else —
vanilla, foreign, or a dimension Stellurgy does not know — gets the configured vector unchanged, which is
what makes this safe to apply to every craft rather than only ours. The lookup is the null-returning one
on purpose: the lenient form answers with Earth's properties for an unknown dimension and would hand a
foreign mod's world our idea of gravity. The entity half keeps its own mechanism
(`util/GravityHandler`). The per-world port scales the
CONFIGURED vector rather than a constant of its own, so the vanilla derivation under STAT-6 stays the
single source for what one standard gravity is worth.

**Witnessed, both halves**:
- that a craft falls at all when released —
  `TierTwoCraftFlightModelGroupTest#flightAssistDecidesWhetherAnUnpilotedCraftHoldsOrFalls`, which
  asserts the HOLD first as a control;
- that the MAGNITUDE is the body's own —
  `TierTwoCraftFlightModelGroupTest#aCraftOverALowGravityBodyFallsInProportionToThatBodysGravity`: two craft
  released in the same window in two worlds, Earth and an authored quarter-gravity body. Measured:
  **57.84 blocks against 15.04 in 40 ticks, a ratio of 0.260** where the body's multiplier is 0.25.
  Falsified by making the port ignore the multiplier: **ratio 1.041** (58.61 against 56.30).

Player form: the Moon stops being Earth; hovering stops being free; a cell stops being a place where
ships fall unnoticed (the configured field is `-32` blocks/s²).

### STAT-15 `[T]` — Authority is TWELVE signed directions, each SUSTAINED or BURST

Six scalars cannot express a hull whose `+X` is 10 MN and whose `−X` is 2 MN, which is the ordinary
case for a built ship. The surface is `±X, ±Y, ±Z, ±pitch, ±yaw, ±roll` with independent magnitudes.
Each direction carries **two** figures: **sustained** authority, holdable indefinitely,
and **burst** authority with a time budget — because a reaction wheel nulling a parasitic torque is
exchanging momentum, not producing it, and saturates in `t = I·ω_max / τ`. A hull that only
moves forward while its wheel winds up has forward authority *for N seconds*, and the readout must
say so. Player form: "my ship cannot brake" becomes visible before flight instead of discovered in it.

### STAT-16 `[T]` — Clean authority is a CONSTRAINED ALLOCATION, deterministic, with named tolerances

For arbitrary geometry, maximising force on an axis subject to zero residual force on the others and
zero torque is a constrained allocation over actuator throttles, not a sum of thrust components. A
small LP, a deterministic specialised allocator, or an analytic method for a restricted actuator model
are all admissible; the result must satisfy the constraints. Three riders. **Determinism**: identical
hull state must give identical recipes, tie-broken on a stable `ActuatorId`, or a relog jitters and
client and server disagree. **Tolerances**: residuals are compared against named central tolerances —
force, torque, solver, mass epsilon — never against exact zero, and never as magic numbers spread
through the code. **Gimbals** (not built): a thrust-vectoring actuator turns direction into a
variable, which under a small-angle linearisation (axial ≈ `T`, lateral ≈ `T·θ`) keeps the problem
LINEAR — three variables per actuator instead of one. A single off-centre engine can still only push
cleanly along its own radial line, so "turning and braking must be BUILT" survives. Player form: bad
geometry produces low clean authority instead of silent parasitic spin.

### STAT-17 `[T]` — Torque authority and angular PERFORMANCE are different quantities

`α = I⁻¹τ`, so equal torque on two hulls is not equal responsiveness, and if the inertia tensor is
not diagonal in the control frame a torque about local yaw does not produce pure yaw acceleration.
The rotational canonical recipe therefore targets a clean **angular acceleration** about
the requested axis — `τ_target = I·α_target` at rest — and the readout separates "what the actuators
can produce" from "what motion that produces on this hull". The shipped controller is inertia-aware in
exactly this shape (`tile/TileAdvancedFlightComputer.java`). The gyroscopic term `ω × Iω` is
left to the controller's feedback loop. Player form: a heavy ship feels heavy.

### STAT-18 `[T]` — Multi-axis commands compose, then saturate globally

Independent axis recipes may not simply be summed and applied: forward + right + yaw can demand one
thruster at 250 % or breach a shared resource budget. Composition is
`u_raw = Σ |p_k| · recipe(sign(p_k), axis_k)`, then a single global factor
`λ = min(1, min_i 1/u_raw,i, min_q budget_q/requested_q)` scales the whole command, preserving the
requested proportions and degrading predictably without a coupled per-tick solver.
The `ControlScheme` converts desired accelerations into signed-axis percentages against the authority
table — `p_k = clamp(desired / authority)` — so priority (attitude over translation, braking over
lateral) belongs to the scheme, and the allocator never sees physical units; schemes emit
percentages. A scheme that ever adds an integral term must add anti-windup — safe today only because
the law is purely proportional. Player form: a combined command degrades in proportion instead
of one axis eating the others.

### STAT-19 `[V]` — Canonical recipes are cached derived state under a revision graph

For each signed direction the system caches not only the scalar authority but the **recipe** that
achieves it — the per-actuator throttles and the achieved wrench. This keeps the runtime cheap (no
optimisation per tick), makes the allocator explainable ("+X used actuator 12 at 100 %, actuator 15 at 74 %"), and lets
asymmetric geometry be answered honestly. Recipes are derived state and are **never persisted** (STAT-2
holds). Invalidation follows the graph: construction change → mass contributors + actuator geometry
dirty; load change → mass frame dirty; mass frame changed → **CoM/inertia changed → recipes dirty**,
because the moment arms are taken about the centre of mass; operational state changed → live overlay
dirty; authority changed → performance and readout dirty. The built model collapses this to "rebuild
on any input change" under one revision counter; consumers — GUI, HUD, controller caches — test
staleness against it cheaply. A stale recipe is a wrong ship, so the CoM→recipe edge is load-bearing,
not an optimisation. Player form: cargo loaded asymmetrically changes handling, not just acceleration.

### STAT-20 `[V]``[T]` — The mass seam: delta by default, authoritative recompute as the check, drift REPORTED

VS's inertia is an **accumulator**: born block by block at paste
(`WorldServerShipManager.java`), maintained by deltas on block change (`ShipDataMethods.java`),
restored from the serialized record on load, and **never rescanned**. Four writers exist — those two,
`BasicCenterOfMassProvider`'s setters, and `QueryableShipData`'s record-to-record copy — and replacing
only the shared provider field leaves two of them on upstream behaviour.

Stellurgy computes `ShipMassFrame {structural, content, crew, total, CoM, body-frame inertia about the CoM}`
behind a port with no VS types, and one boundary writer applies all three fields together. **Deltas
remain the working path**; the full recompute is the AUTHORITY, run where it is independently
justified — on load, once after assembly/paste, after a crossing, and on a slow background round. A
disagreement between the two is a **defect and is reported** (ship id, magnitude, sign; a failure in
test builds), never silently corrected: substituting the right number turns the safety net into normal
operation and destroys the only signal that a trigger is missing.

**The port** `[A]`: `ship/mass/ShipMassSource`, keyed by the durable ship id, implemented
by `integration/vs/FlightComputerMassSource`; the event trigger, the background round and the hull survey
all read through it. A hull carrying no flight computer is answered `null` and stays on the upstream
accumulator (no computer is loss of CONTROLLABILITY, not of mass, and ships do not collide). Not
witnessed by a test: production makes no computer-less ship (the assembly refuses one), so a hull
reaches this branch only by having its computer broken off, and then only on a LOAD. What the branch
decides is invisible to a player: such a hull cannot be flown, its fall is mass-invariant, and ships do
not collide.

**Witnessed** `[T]` (`TierTwoCraftFlightModelGroupTest`): the CONTENT half for fluids — water
poured into a tank aboard makes the flight model heavier with no block changing
(`#aFilledTankOutweighsAnEmptyOne`; items are `#theSameShipCarryingCargoAcceleratesLessByItsMassRatio`);
and the CREW half with its frame — a body seated on the pilot seat grows the physics record by exactly
the readout's crew mass, and the record's centre of mass moves as if that mass sat inside the seat's own
SUBSPACE block (solved from the record before and after; measured 0.30 above the block's floor, 1.5e-6
off its middle). Placing the occupant at its world position instead moves the solved point to the world
coordinates, which is what the test refuses (`#aSeatedOccupantIsWeighedWhereItSits`).

**Precondition on every trigger**: the ship must be NAMED. Before VS names a ship its blocks are
already loaded and ticking while every coordinate they hold is a shipyard address (VS allocates ship
chunks past ~5.12 M on X), so a frame computed in that window is a frame about a point no player can
reach. The assembly trigger therefore hangs on the ship-assembly Forge event, never on a poll or a
positional probe.

Two platform facts this rests on: writing a new centre of mass mid-flight is safe, because VS shifts
the body origin by the same rotated vector (`PhysicsCalculations.java`); and the tensor must
be **non-degenerate**, since `physInvMOITensor = physMOITensor.invert(...)` runs every physics tick
(`PhysicsCalculations.java`) — which is why upstream smears each block over nine points
(`BasicCenterOfMassProvider.java`), and why our computation must regularise equivalently or a
stick-shaped hull yields NaN torque. Player form: a fuelled ship flies like a fuelled ship.

### STAT-21 `[V]` — What the client receives, and through which channel

Server-authoritative; the client draws. Four channels: **nothing** when nobody is looking;
the **console/AFC GUI**, whose subscription IS the container's listener set — anyone who opens the
tile, own ship or not, and the payload carries DESIGN and LIVE side by side; the **flight
HUD**, delivered to the occupant of the pilot seat only (a passenger sees no flight HUD; a console is
the surface for everyone else); and the **assembler Scan**, one request and one snapshot with no
revisions. Pushes ride revision bumps, not a fixed tick.

The payload carries **primitives** — mass, centre of mass, the twelve signed force/torque values with
their budgets, weak-axis flags — and the client derives `a = F/m`, TWR and stopping distance for
display. That is not authorship: it divides numbers it was given, and it keeps the primitives
single-sourced. Transport is one packet of primitives to the pilot and console viewers only
(`PacketShipReadout`, C2), not the vanilla window-property path, which needs an open container and 16
bits per property and therefore cannot carry the HUD channel at all. Reading a stranger's ship is
deliberately allowed — it is intelligence, and there is no reason to forbid it.

### STAT-22 `[A]` — Every reaction drive CONSUMES; availability is BUDGETED

An actuator that throws mass declares, per throttle, what it takes: **propellant mass flow** always, and
**power** where its energy is not in the propellant — nuclear thermal from its own reactor, the divertor
from the reactor's SUSTAINED rate, an electric drive from the ship's electricity,
sustained AND peak. Chemistry's power term is infinite. STAT-18's `budget_q/requested_q`
terms are these budgets: the propellant pool per fluid, the power the allocation grants
propulsion, and an electric drive's peak buffer. The pool draws only from holders whose contents the
mass frame COUNTS. A shortfall lowers LIVE authority and never DESIGN authority. A
config flag disables consumption entirely, and with it every consequence that reads consumption
(a flag must fully disable its mechanic; C12 HEAT-4's feed exit closes too). Built availability is
BINARY — nothing is consumed — as the first stage.

*Stress-test.* Falsifiable: a burn drains the pool by exactly what the allocator commanded; an empty
pool zeroes the drive's live authority and leaves its design authority. Code: the propellant budget is
built (STAT-38..40); the power budgets are not — power-drawing families stay binary. Consistent with STAT-15/18. Edges: an empty pool mid-burn is a live-capability change and
rebuilds through STAT-19's revision; the jump capacitor is never a propulsion budget.

### STAT-23 `[A]` — An engine is a PARAMETER SET, and its thrust is a law, not a constant

One actuator family with `P_max, ṁ_max, v_e` (per regime), `F_max, η`; per regime the thrust
is `F = min(ṁ_max·v_e, 2ηP/v_e, F_max)` and the mass flow `F/v_e`. `v_e` is fixed during a solve — a
discrete REGIME derived from what physically varies — so the allocation stays linear;
boost is the low-`v_e` regime (STAT-7b). (1) **The outside pressure is an input, every tick**: the throat is
choked, so `ṁ` per throttle does not depend on it, and thrust is `ṁ·v_e + (p_exit − p_amb)·A_exit` —
affine in `p_amb`; the expansion ratio is a build parameter and the chemistry its ceiling; flow separation
in thick air costs thrust and damage (a thin exhaust is eaten by the pressure term — no separate
rule for electric or plasma drives). (2) **Boost is a second COLUMN, not a second table**: an engine with a
variable `v_e` offers cruise and boost columns under `u_cruise + u_boost ≤ 1`; "boost" lets the allocator
use them, composed by STAT-18's sustained/burst rule — cruise first, boost past the cruise figure — so an
asymmetric hull boosts exactly its limiting engines. (3) **Recipes are cached per pressure band**,
the band derived from STAT-16's residual tolerance. `F_max` is
structural: exceeding it (an overdrive) is allowed by explicit command and paid in damage to the engine's
blocks (the block-damage budget); the control system argues against it and never refuses. `ṁ_max`
is per engine block; there is no feed network. Propellant `v_e` is the propellant's own,
for both tiers.

*Stress-test.* Falsifiable as properties: at fixed power, thrust falls as `v_e` rises for a
power-limited family and does not depend on power for chemistry. Code: the law, the built-in bell and
the families are written (STAT-30..32, `api/propulsion/`); boost columns and pressure bands are
(STAT-42, STAT-43); the regime switch and overdrive are not.
Consistent with STAT-16 (`u ≤ 1` against `F_max`; an overdrive is an explicit command above it, still
under STAT-7a). Edge: a regime switch is a construction-revision event (STAT-19), at most one rebuild per
`REBUILD_MIN_TICKS`.

### STAT-24 `[A]` — Reaction mass is its OWN term of the mass frame, and it is never scaled

The frame gains a `reaction` term beside `structural`, `content` and `crew`. It is never
multiplied by `contentMassScale`: a drive ejects `ṁ` real kilograms, so the frame loses
exactly that, or momentum is not conserved and Δv is free (infinite at a zero scale). **The term moves
with the drain, every tick of a burn** — it is the one term the STAT-20 resample cadence may not lag,
because the allocator already KNOWS the mass it spent; the resample stays the authority that catches
drift.

*Stress-test.* Falsifiable: across a burn, frame mass lost equals mass ejected, to the tolerance, at any
`contentMassScale`. Code: built — the frame is `{structural, content, reaction, crew}`
(`ship/mass/ShipMassFrame.java`; STAT-40, STAT-41). Consistent with STAT-6/20 (one mass model; the resample is
the authority). Edge: a holder the frame cannot count (NBT contents) is not in the pool at all (STAT-22),
so the term never sees mass it did not count.

### STAT-25 `[T][BEH]` — The readout computes and SHOWS the bill; it never refuses

Beside STAT-12's TWR, the console shows the **Δv aboard** per regime (STAT-45) and, for the body BELOW the
craft, three legs computed by the flight's own law run forward (STAT-46): the **ascent bill** from where
the craft stands to the line its takeoff fires on, the **landing bill** from there to the surface under its
hull, and the **climb back out** from that surface on what the landing leaves — each with its Δv and its
propellant, and, for a leg that does not make it, how far it gets — and a warning when the craft could not
climb back out after landing. The TWR at that body is STAT-12's own figure: the body is the one the
readout's field is taken in. A craft that cannot climb back out is told so before it descends, and may
descend: nothing reads the bill to decide anything (an unreasonable act stays possible and costs what it
costs; maintainer ruling 2026-10-02). In the world the bill and the flight agree (STAT-49).

**Body below only**: no navigation target exists, and the descent trigger's nearest body may have no world
to read a field, an air or a surface from yet, so a TARGETED body is not billed; where there is no body below
— no field (a space cell), no orbit line, or a pose that cannot be read — the bill says which and shows the
Δv aboard alone (`FlightBill.NoBody`). What the bill does not model is listed at STAT-46.

## The tier-1 launch gate — STAT-26..29

Tier-1 only: the tier-2 ship's readout is STAT-12 (warns, builds on confirmation) and does not refuse.

**Terms.** **the gate** = `StatsRocket#canLaunch(float)`. **local gravity** = the
`gravitationalMultiplier` of the world the craft stands in, through
`effectiveGravityMultiplier` (one gee when `gravityAffectsFuel` is off). **full** = every tank at
its capacity (`StatsRocket#withTanksFull`).

**Maintainer rulings, 2026-10-03, verbatim:** *"601 - 1) полный 2) мира, где идет сборка 3)
отказывает, ибо ракета бесполезна при TWR < 1"* — the assembler judges the craft (1) with FULL
tanks, (2) at the gravity of the world it is assembled in, and (3) REFUSES, because a rocket that
cannot climb is useless.

### STAT-26 `[V]``[T]` — The ratio is thrust over the weight at LOCAL gravity
`TWR = thrust / (wetWeight · local gravity)`; a craft with weight on a body with no gravity has an
infinite ratio, a weightless craft a ratio of 0. The flight model weighs the craft by the same
function, so the gate and the climb cannot disagree about which body the craft is on.
`[V]` `api/StatsRocket.java:260` (`effectiveGravityMultiplier`, also read by `getAcceleration` `:288` and
`getDryAcceleration` `:293`), `:298` (`getThrustToWeightRatio(float)`).
`[T]` `RocketLaunchDepthTest#aCraftOnALowGravityMoonIsWeighedAtThatMoonsGravity`.

### STAT-27 `[V]``[T]` — The boundary is inclusive at `minLaunchTWR`
A ratio EQUAL to the threshold launches; the next number above refuses. `[V]` `StatsRocket.java:323`.
`[T]` `RocketLaunchDepthTest#theWeightGateLetsACraftGoAtExactlyMinLaunchTwrAndRefusesItJustAbove`.

### STAT-28 `[V]``[T]` — `advancedWeightSystem` off ⇒ no weight gate anywhere
The gate answers yes; the assemblers therefore build anything with thrust. Out of this clause, by
design: a craft with NO thrust is still refused by the assembler as `NOENGINES` (it has no engines,
which is not a weight question), and the ascent forecast's "cannot climb here" (a craft whose first
burning tick does not rise is forecast short, `api/propulsion/ClassicAscent#run`) belongs to the fuel
mechanic, warns rather than refuses (STAT-34) and is disabled with `rocketRequireFuel`. `[V]` `StatsRocket.java:320-322`.
`[T]` `RocketLaunchDepthTest#turningTheWeightSystemOffLiftsTheWeightGate`.

### STAT-29 `[V]``[T]` — ONE decision: every surface that judges launchability asks the gate
The launch asks it at the gravity of the world the craft stands in
(`entity/EntityRocket.java:2880`). The rocket assembler and the unmanned-vehicle assembler ask
it of the scanned craft FULL at the gravity of the world they stand in (`canLaunchFullFromHere`,
`tile/TileRocketAssemblingMachine.java:332`), and refuse a craft it answers no for as `NOENGINES`
("Not enough thrust!") — rulings 1-3 (`TileRocketAssemblingMachine.java:721`,
`TileUnmannedVehicleAssembler.java:382`). The assembler's TWR readout is the same full-tank,
local-gravity ratio (`getThrustToWeightRatio`, `TileRocketAssemblingMachine.java:337`). So a craft the
assembler builds can launch full from where it was built. A tank with no fluid chosen yet is
weighed full of the HEAVIEST fluid the fuel registry accepts for it
(`StatsRocket.withTanksFull`, `:332`; `api/fuel/FuelRegistry.java`): ruling 1 says "full" and leaves the
fluid open, and only the heaviest makes "built ⇒ launchable full" hold for every fuel the craft can
take; every shipped fuel has its own density row (INV-WGT-09). The choice is made in one place,
`WeightEngine#heaviestFluidOf`, which the tier-2 assembler's full-tank weighing reads too (STAT-12). The heaviest-fluid choice and the absence of a
one-gee no-arg `canLaunch()` / `getThrustToWeightRatio()` in the public API were ratified by the
maintainer 2026-10-03 (*"всё утверждаю"*).
`[T]` `RocketLaunchDepthTest#theAssemblerAndTheWeightGateGiveOneVerdictOnOneCraft` (refusal verdict
witnessed; the agreement verdict is `NOT YET`).

**Outside the gate, on purpose:** free-flight start (`EntityRocket#canStartFreeFlight`,
`entity/EntityRocket.java:1016`) asks climb authority (`getAcceleration(g) > 0`, i.e. TWR > 1 at
local gravity), not `minLaunchTWR`; the station-deployed rocket's launch
(`EntityStationDeployedRocket#launch`) asks no weight gate at all — its assembler's verdict is the
only one. Neither is ruled; a change to either must name it.

Cost, in one line each: one gravity argument, and the assemblers ask the gate instead of keeping a
copy; the assembler's verdict and the launch's are the same function on the same state; every tier-1
launch and every assembler scan is affected, and a pack whose fuels are denser sees fewer crafts
build; a moon is easier to leave than Earth, and a rocket that cannot launch full is
refused at the assembler instead of on the pad.

## The engine law and the propellant bill — STAT-30..37 (both tiers)

STAT-23's law made concrete. **Terms.** **design** = what an engine block is built as, independent of
what feeds it: family, `F_max`, the fraction of the propellant's exhaust velocity its cycle reaches,
its built-in bell, and `η` for a power-limited family (`api/propulsion/EngineDesign`). **regime** = the
design fed one propellant: `v_e`, `ṁ_max = F_max / v_e`, `F_max`, `η`, `A_exit`
(`api/propulsion/EngineRegime`). **the tick** = the duration one tier-1 flight tick integrates over,
`τ = sqrt(0.08 / 9.81) ≈ 0.0903 s` (STAT-6's vanilla gravity reference).

Maintainer rulings these rest on, verbatim: *"1a, 2a, 3 - а кто такой гидролокс?"* and *"3a, гидразин"*
(2026-10-16: a millibucket is a litre of the fluid's condensed form; one player-editable table; the
inherited monopropellant is hydrazine); *"ок"* to the four family shapes, the touching core stack, the
pair rows and the rename, and *"да-да"* to the advanced motor as a higher chamber pressure with a
built-in bell (2026-10-17); *"По рекомендациям"* to the drain law, the warning fuel check, the built-in
bell and the retired knobs (2026-10-17).

- **STAT-30** `[T][SYS]` **The thrust law.** A regime fed power `P` gives
  `F = min(ṁ_max·v_e, 2ηP/v_e, F_max)` and takes `F / v_e` of mass flow; a CHEMICAL regime's power
  term is infinite, so its thrust does not depend on `P`; at a fixed `P` a power-limited regime's
  thrust FALLS as its `v_e` rises wherever the power term binds; `P = 0` gives no thrust to a
  power-limited family. The four family shapes are one type: chemical, nuclear thermal (the power of
  its core stack, STAT-35), electric (a fixed `v_e` set by the build) and the divertor (two regimes at
  the ends of its `v_e` range). REGIONS: `chemical`, `nuclear thermal`, `electric`, `divertor`,
  `power binds`, `structure binds`, `no power`. FOR: STAT-23; maintainer ruling 2026-10-17 ("ок"). Pinned by `PropulsionLawsTest#theThrustLawHoldsForEveryFamilyShape`, `PropulsionLawsTest#atFixedPowerAPowerLimitedEngineLosesThrustAsItsExhaustQuickens`.
- **STAT-31** `[T][SYS]` **The outside pressure.** Mass flow per throttle does not depend on the
  ambient pressure; the force is `F − p_amb·A_exit`, never below zero (a separated nozzle pushes
  nothing; separation's cost in damage is not built). A built-in bell's exit area is measured as
  `A_exit = F_max·(1 − f_sl)/p₀`, `f_sl` the sea-level-to-vacuum thrust ratio of a measured engine of
  its class and `p₀` one standard atmosphere, so a motor gives `F_max` in vacuum and `f_sl·F_max` at
  one atmosphere. REGIONS: `vacuum`, `one atmosphere`, `past separation`. FOR: STAT-23 (1);
  maintainer ruling 2026-10-17 ("По рекомендациям"). Pinned by `PropulsionLawsTest#theOutsidePressureMovesTheForceAndNeverTheFlow`.
- **STAT-32** `[T][SYS]` **`v_e` is the propellant's.** A monopropellant's ceiling is its fluid row's
  `monopropellantExhaustVelocity`; a pair's is its `propellantPairs` row, with the row's O/F by mass;
  a nuclear-thermal regime's is `v_e = 8 500 m/s · sqrt((T / 2 700 K)·(2.016 / M))` from the core
  temperature `T` and the working fluid's molar mass `M` (g/mol); the regime's `v_e` is that ceiling
  times the design's cycle fraction. A propellant the table has no row for has NO `v_e` — absent,
  never a guess: a pair with no row is refused at fill and a bipropellant craft whose configured fuel
  and oxidizer lists contain a pair with no row is refused at assembly, naming the pair. REGIONS:
  `monopropellant`, `pair`, `nuclear thermal`, `no row`. FOR: maintainer ruling 2026-10-17 ("ок"). Pinned by `PropulsionLawsTest#aPropellantsExhaustVelocityIsItsRowsAndNothingElse`, `PropulsionLawsTest#aNuclearExhaustGoesAsTheRootOfTemperatureOverMolarMass`.
- **STAT-33** `[T][SYS]` **The drain law.** A burning tier-1 engine ejects `F·τ / v_e` kilograms per
  tick at the force `F` it applied — `F_max` (after `thrustMultiplier`) in the classic ascent, the
  force the free-flight step applied in free flight; the kilograms become millibuckets by the fluid's
  density (INV-WGT-09), a pair split by its O/F by mass; the fraction of a millibucket left over is
  CARRIED to the next tick and never rounded up, so over any burn the millibuckets drained times the
  mass per millibucket equal the mass ejected to within one millibucket per tank. A tick that applies
  no force drains nothing. `rocketsRequireFuel` off drains nothing. REGIONS: `monopropellant`, `pair`,
  `less than a millibucket a tick`, `no force`. FOR: STAT-24; maintainer ruling 2026-10-17
  ("По рекомендациям"). Pinned by `PropulsionLawsTest#theDrainTakesWhatTheBurnEjectedToWithinAMillibucket` (the law); the free flight's wiring of it — a full burn drains `F·τ/v_e` a flight tick with τ = 90.3 ms, a half-throttle burn half of that — by `FreeFlightCycleTest#aFreeFlightBurnDrainsThePropellantOfTheForceItApplied`; the classic ascent's wiring has no test.
- **STAT-34** `[T][BEH]` **The fuel check is the rocket equation run through the flight's own law, and
  it warns.** Before a tier-1 launch, and at the assembler, the craft's ascent is run tick by tick
  through the same per-tick climb, drain and coast as the flight, from where it stands to the
  transfer line; a craft that would not reach the line is TOLD so — how high it would get against the
  line — and still launches or builds. So the propellant a rocket needs to reach a height grows with
  its loaded mass as the rocket equation says. The TWR gate (STAT-26..29) is unaffected and still
  refuses. REGIONS: `reaches the line`, `runs dry short of it`. FOR: STAT-25; maintainer ruling
  2026-10-17 ("По рекомендациям"). Pinned on the law, which is pure (`api/propulsion/ClassicAscent`), by `PropulsionLawsTest#thePropellantAClimbNeedsGrowsWithTheLoadAsTheRocketEquationSays`, `PropulsionLawsTest#aClimbReachesTheLineExactlyWhenItsPropellantCanBuyIt`; the launch's wiring by `RocketFlightFailureModesTest#launchWithZeroFuelStillTransitionsToInFlight`, the assembler's (a rocket's only) by `RocketRequireFuelDisableAssemblesTest#aShipIsNotHeldToTheClimbToOrbitThatARocketIs`.
- **STAT-35** `[T][SYS]` **A nuclear core is rated in thermal power and temperature, and its fuel is
  consumed.** A core delivers its rated power (× `nuclearCorePowerRatio`) at its rated temperature
  while it holds a fuel element with burn-up left, and NO power otherwise. Energy produced advances
  the element's burn-up; an element holds `mass × enrichment × 8.2·10¹³ J/kg` (complete fission of
  U-235); a burned-out element becomes spent fuel of the same mass, which stays in the core until the
  player takes it out. REGIONS: `fuelled`, `burns out this tick`, `spent`, `empty`. FOR: maintainer
  ruling 2026-10-17 ("ок"). Pinned on a tier-1 free flight by
  `NuclearEngineRocketAssemblyTest#anElementBurnsOutAtItsCapacityAndItsCoreGivesNoMorePower` (burn-up
  advances by `P·τ` a flight tick, an element is fresh short of its capacity and spent past it, a spent
  core gives no power and the thrust loses its share), the empty core by
  `NuclearEngineRocketAssemblyTest#anEmptyCoreGivesTheNozzleNothing`; the spent element's mass and the
  exact tick of the burn-out are not pinned.
- **STAT-36** `[T][SYS]` **A nuclear nozzle draws from the core stack it touches.** On both tiers the
  power a nuclear nozzle may use is that of the contiguous column of cores behind it — the side
  opposite its exhaust — and no other; tier-1 sums the stacks of the nozzles it counts. A nozzle with
  no fuelled core behind it gives no thrust. FOR: maintainer ruling 2026-10-17 ("ок"). Pinned on tier 1
  by `NuclearEngineRocketAssemblyTest#nuclearCoreAboveMotorContributesNuclearThrust` and
  `#misplacedNuclearCoreFailsAssemblyWithNoEngines`; on tier 2 by
  `TierTwoCraftFlightModelGroupTest#aNuclearNozzleDrawsOnTheCoreColumnBehindItAndOnNothingElse` (no fuelled
  core: no force; one behind it: one core's power-bound force; a fuelled core beside it: nothing more; the
  whole column: two); a SIDEWAYS nozzle — its nozzle drawn pointing away from the one core beside it — by
  `TierTwoCraftFlightModelGroupTest#aSidewaysMotorPushesTowardTheTankOrCoreThatFeedsIt` (one core's force,
  toward the core). A sideways column of more than one core is not exercised.
- **STAT-37** `[T][SYS]` **A tier-2 reaction actuator declares what it takes.** Each motor actuator
  carries, per regime it can run (one per propellant the fuel registry accepts for its role that has
  a row), its mass flow and its power at full throttle, both linear in throttle; its force is the
  law's (STAT-30, STAT-31) at the world's pressure. Where a nuclear nozzle's force depends on which
  working fluid feeds it and nothing aboard decides that yet, its force is the LEAST over its regimes
  — authority never promised on a fluid the craft may not carry. Nothing is drained on tier-2 yet
  (STAT-22's binary first stage). FOR: STAT-22. Pinned by `PropulsionLawsTest#theOutsidePressureMovesTheForceAndNeverTheFlow`, `PropulsionLawsTest#aNozzleWhoseFluidIsUndecidedOffersTheLeastOfItsRegimes`.
  *Superseded in part by STAT-38..40*: on a metered craft the tier-2 drain is built; the sentence "nothing
  is drained on tier-2 yet" holds only with `rocketRequireFuel` off.

## Consumption on tier 2 — STAT-38..44

STAT-22, STAT-23 (2)(3) and STAT-24 made concrete. **Terms.** **the pool** = per fluid, the propellant in
the holders aboard (below). **metered** = `rocketRequireFuel` on — the one switch for both tiers; off, the
pool is not read, nothing is booked or drained, and every working drive is LIVE as designed
(`integration/vs/ShipPropellant#metered`). **the step** = one physics step of the substrate, `Δt` its SI
seconds (`dt · PhysicsUnits.SECONDS`) — the duration its force is integrated over, so the mass booked for a
step is the mass its impulse carried. **a boost column** = the second column of a drive with a variable
exhaust velocity (`Actuator#boostOf`).

Maintainer rulings these rest on: the 2026-10-02 propulsion-physics rulings (every reaction drive consumes;
reaction mass its own term, never scaled; the pool draws only from what the frame counts; boost as a second
column under one capacity, cruise first; recipes cached per pressure band) — the sources of STAT-22..24.

- **STAT-38** `[T][SYS]` **The pool, and what feeds a drive.** The pool is, per fluid, the contents of every
  holder aboard whose contents the mass frame counts — a tile's fluid capability on its unsided face, what
  `WeightEngine` weighs — holding a fluid some reaction drive can burn (`FuelRegistry#isEnginePropellant`);
  a fluid inside an item, or in NBT, is not in it. Metered, a drive is LIVE only when every fluid of one of
  its regimes has a positive pool, and then at the ONE regime among those with the least force at the solve
  pressure, ties by propellant name (`Actuator#fedBy`) — **an INTERIM rule** (maintainer ruling 2026-10-18):
  it stands only until a per-engine console setting names the fluid a drive burns, and is then replaced by
  that setting, not refined; a drive with none is absent from LIVE and stays in
  DESIGN, and the readout names the propellants it waits for (`ShipReadout#starvedFor`). A change in which
  fluids the pool holds, or in the switch, is a live-capability change: the model rebuilds (STAT-19), at most
  once per `SURVEY_MIN_TICKS`. REGIONS: `fed`, `one of two fed`, `none fed`. FOR: STAT-22. Pinned by
  `PropulsionLawsTest#aDriveRunsOnlyOnWhatIsAboard`; the survey's wiring by
  `TierTwoCraftFlightModelGroupTest#aBurnDrainsWhatItEjectsAndAnEmptyTankIdlesTheDrive`.
- **STAT-39** `[T][SYS]` **The budget is STAT-18's λ term.** A metered step's command — composed, scaled into
  range, clipped to the wheels, desaturated — is scaled by one more factor
  `min(1, min_q available_q / requested_q)`, `requested_q` the mass of fluid `q` its throttles would eject in
  the step (each drive's full-throttle flow × throttle × `Δt` × its regime's mass share of `q`, a pair split
  by its O/F), and is flagged saturated when that factor bites. The command reports the mass it ejects, which
  never exceeds what was available; unmetered, there is no factor. REGIONS: `covered`, `bites`, `empty`,
  `pair`. FOR: STAT-22, STAT-18. Pinned by `PropulsionLawsTest#aStepEjectsWhatItsThrottlesBurnAndNeverMoreThanIsAboard`.
- **STAT-40** `[T][SYS]` **What a step ejects is drained, and the frame loses it the same tick.** A step's
  ejection is booked at once, so the next step's budget is the pool net of everything ejected and not yet
  drained (`PropellantLedger`). The game tick takes it out of the holders as whole millibuckets per fluid,
  in position order, never rounded up, the fraction left owed — so over a burn the mass drained trails the
  mass ejected by under one millibucket per fluid plus one tick's ejection. The physics record loses exactly
  the drained mass at the holders it left, in that tick: mass, centre and tensor as the frame built without
  it (`ShipMassFrameBuilder#remove`, `ShipInertiaWriter#removeFrom`); the slow mass round stays the
  authority that catches drift (STAT-20). A pool that GROWS between two drains — a fill — is written to
  the record that same tick by the authoritative pass (`ShipMassTrigger#backgroundRound`), so a drain
  never takes off the record mass it was never given (`TileAdvancedFlightComputer#tickPropellant`; `[V]`
  — read, not witnessed by a test). What is owed when the flight computer unloads is lost — under one
  tick's ejection and a millibucket per fluid. REGIONS: `net of what was ejected`, `drained`, `the record`.
  FOR: STAT-24. Pinned by `PropulsionLawsTest#whatAStepEjectsIsOwedUntilDrainedAndLeavesTheBodyExactly`; the
  drain in the world by `TierTwoCraftFlightModelGroupTest#aBurnDrainsWhatItEjectsAndAnEmptyTankIdlesTheDrive`.
- **STAT-41** `[T][SYS]` **Reaction mass is its own term, at its real mass.** The pool's fluids are the
  frame's `reaction` term at their real mass for every `contentMassScale`; items and every other fluid are
  `content`, scaled (`ShipHullMass`, `WeightEngine#reactionWeight` / `#cargoWeight`). So the frame's total
  is `structural + k·cargo + reaction + crew`, and what a burn takes off the frame is the mass it drained,
  whatever `k`. Propellant is never counted twice: it is in `reaction` and in no other term. REGIONS:
  `scale one`, `scale zero`. FOR: STAT-24. Pinned by
  `TierTwoCraftFlightModelGroupTest#propellantWeighsItsRealMassWhateverTheContentScale`.
- **STAT-42** `[T][SYS]` **Boost columns share their engine's capacity, and cruise comes first.** A drive with
  a boost regime is two columns, cruise and boost: every recipe and every command keeps
  `u_cruise + u_boost ≤ 1` (a row of the programme; one more term of the range factor). The cruise tables
  leave boost columns out; every table is solved at one rebuild. With the ship-wide switch off no boost
  column is used; with it on an axis takes the cruise sustained recipe while that suffices, then the
  sustained recipe that may use boost, then a burst — so an asymmetric hull boosts the drive that limits a
  direction and raises the others to keep it clean. No engine has a boost regime yet: the switch has no key
  or profile, and the flight computer runs cruise. REGIONS: `switch off`, `within cruise`, `past cruise`,
  `asymmetric`. FOR: STAT-23 (2). Pinned by `PropulsionLawsTest#boostIsASecondColumnUnderOneCapacityTakenOnlyPastCruise`.
- **STAT-43** `[T][SYS]` **Recipes hold over a pressure band.** A capability is solved at one outside pressure
  `p₀` — the world's (`AtmosphereDensity#pascals`), which every drive aboard is evaluated at — and holds
  over `|p − p₀| ≤ w`, `w` the largest pressure change at which every cached recipe's added off-axis wrench
  (`|Δp|·Σ|uᵢ|Aᵢ` in force, `|Δp|·Σ|uᵢ|Aᵢ|rᵢ|` in torque) stays within STAT-16's residual tolerances, and
  never across a regime's separation pressure. Inside the band the applied force is each drive's affine law
  at the step's pressure (`ShipCapability#wrenchAt`); outside it the flight computer rebuilds the model. The
  pressure is one number per world today, so a band is left only by changing worlds. REGIONS: `inside`,
  `outside`. FOR: STAT-23 (3). Pinned by `PropulsionLawsTest#aRecipeHoldsOverItsPressureBandAndFollowsTheAffineLawInside`.
- **STAT-44** `[T][SYS]` **A rocket fuel tank holds its role's propellant, a cubic metre of it.** Maintainer
  ruling 2026-10-18 (*"всё по рекомендациям"*, on the fork "the rocket fuel tanks become real holders"). Every
  rocket fuel tank — `BlockFuelTank` and the bipropellant-fuel, oxidizer and nuclear-working-fluid tanks that
  extend it — holds up to one cubic metre, 1000 mB (`BlockFuelTank#CAPACITY_MB`, the volume the tank masses
  were derived for, `WeightEngine`), of the fluids its ROLE accepts: the fuel registry type the block stands
  for (`BlockFuelTank#role`), filled from the `rocketFuels` / `rocketBipropellants` / `rocketOxidizers` /
  `rocketNuclearWorkingFluids` lists (C4). Every other fluid is refused. It answers the Forge fluid
  capability on every face, so a pipe, a bucket (`BlockFuelTank#onBlockActivated`) and a drain reach it, and
  keeps its contents in its tile's NBT (`TileFuelTank`, C1). What it holds the frame weighs (STAT-41), so a
  ship's tanks are holders of its pool (STAT-38) and the tanks a ship is built with are what it burns. A
  tier-1 rocket burns its stats' propellant instead, loaded at the fuelling station, so its assemblers refuse
  a rocket whose tanks hold fluid and a gas harvest never fills them (rocket-assembly INV-RASM-15). REGIONS:
  `its role`, `another role`, `past its capacity`. Pinned by
  `TierTwoCraftFlightModelGroupTest#aRocketFuelTankHoldsOnlyItsRolesFluidAndACubicMetreOfIt`; the drain from
  one by `TierTwoCraftFlightModelGroupTest#aBurnDrainsWhatItEjectsAndAnEmptyTankIdlesTheDrive`.

**Not built, and why**: power budgets (STAT-22's second and third terms — no power allocation and no
electric drive exist; power-drawing families stay on the binary stage); the boost switch's key, profile and
HUD (no engine has two regimes). The feed-exit heat of C12
HEAT-4 does not exist; when it does, it reads the drain and is off with it.

## The bill — STAT-45..49 (tier 2)

STAT-25 made concrete (`ship/control/FlightBill`, `integration/vs/FlightBillSurvey`). **Terms.** **a leg** =
one stretch of flight the bill follows: the ascent, the landing, the climb back. **the step** = one physics
step, `VSConfig#getTimeSimulatedPerTick` engine seconds (a sixtieth: 0.0301 SI s). **the climb command** = what
full upward throttle with Flight Assist off commands, `SHIP_MAX_SPEED` straight up (40 blocks per engine
second, 22.15 m/s). Maintainer rulings these rest on: the 2026-10-02 propulsion-physics rulings — STAT-25's
own, and that a takeoff starts from the planet's real line — and that the ascent integrates its thrust over
the pressure the climb meets, step by step, never a vacuum figure.

- **STAT-45** `[T][SYS]` **Δv aboard is the rocket equation over what the live drives can burn.** One row per
  distinct regime a LIVE drive burns: its exhaust velocity `v_e` is its full-throttle force in vacuum over its
  full-throttle flow (the propellant's ceiling times the design's cycle, STAT-32); its usable propellant is the
  most of its mixture the pool supplies (a pair: the least of each fluid over its share by mass); its Δv is
  `v_e·ln(m0/(m0 − usable))`, `m0` the frame's whole mass. With consumption off there is no row: nothing is
  spent. REGIONS: `monopropellant`, `pair`, `consumption off`. FOR: STAT-25. Pinned by
  `PropulsionLawsTest#theDeltaVAboardIsTheRocketEquationOverWhatTheLiveDrivesBurn`; on a real ship's readout
  — the surveyed pool and the frame's whole mass handed to it — by
  `TierTwoCraftFlightModelGroupTest#aCraftCarryingItsAscentBillCrossesTheLineAndOneShortOfItDoesNot`.
- **STAT-46** `[T][SYS]` **A leg is the flight law run forward.** From rest, step by step in the order the
  physics step applies them: the air takes its share of the velocity (`FreeFlightPhysics#ambientDragFactor`
  at the world's density — the pressure is one number per world, STAT-43, so the thrust's pressure term is
  the model's own); the flight computer's velocity law asks the acceleration that brings the craft to the
  command against the field (`FreeFlightPhysics#shipControlAccel`, its gravity feed-forward included); the
  hull delivers the fraction of its LIVE sustained heave recipe that gives it, at most all, held inside the
  pool by the one factor the scarcest fluid allows, ejecting each fluid's flow by that fraction (STAT-47);
  and the field pulls (STAT-14). **The profiles**: the **ascent** climbs from the craft's pose on the climb
  command until a coast — nothing pushing, the air and the field acting — would carry it to the entry line
  (`TileAdvancedFlightComputer#entryCeiling`), then coasts: the least a climb on the command spends, and what
  a craft carrying no more than that does when it holds the throttle until its tanks run dry — `reaches the
  line`, or turns back below it, `runs dry short of it` (its apex) or `stalls` (thrust below weight,
  propellant left); the
  **landing** falls with no linear command and lights the brake (a command of zero) at the LATEST step from
  which it still stops at or above the surface — the pose at which the hull's lowest point rests on the
  highest ground under its footprint — and, a stop above the surface being a hover, goes on down from there
  the same way on what it has left until a descent gains no height: `stops at the surface` (within a step's
  fall of it), or `cannot stop` (the speed it strikes at — a craft whose propellant runs out while it brakes);
  the **climb back** is the ascent from that surface on what the landing left (the ascent itself for a
  craft already down). A bill follows at most `FlightBill.MAX_STEPS` = 2²⁰ steps over all its legs — room
  for seven of Earth's climbs at the command (100 000 blocks at 22.15 m/s is 150 000 steps) — and a leg cut
  there says so (`BEYOND_HORIZON`). **What it does not model**, each a way the flight differs: the scheme
  divides by the model's mass, rebuilt on a cadence (MECH-SFM-07), where a leg divides by its own — the
  deadbeat then holds the craft `(r − 1)·g·Δt/r` off the command, `r` the ratio of the two; the drain moves
  the physics record once per game tick, not per step (STAT-40); what the attitude hold spends, in flight and
  against a pad before it (a craft is billed for what it holds when the model is solved); a craft that
  is not upright (the heave axis is taken as straight up); collisions. REGIONS: `reaches the line`,
  `runs dry short of it`, `stalls`, `stops at the surface`, `cannot stop`. FOR: STAT-25. Pinned by
  `PropulsionLawsTest#aClimbSpendsWhatTheLawAsksAndSaysHowHighItGets`,
  `PropulsionLawsTest#aLandingBrakesAtTheLastStepThatStillStopsAboveTheSurface`.
- **STAT-47** `[T][SYS]` **A leg's step is the control scheme's step.** For the upward acceleration the law
  asks, a leg's step accelerates the craft by the force the scheme's command for that acceleration applies,
  over the craft's mass, and ejects what that command ejects — under the hull's authority, at it, and where
  the budget bites. FOR: STAT-25 (a bill that disagrees with the flight is a silent fallback). REGIONS:
  `under authority`, `at authority`, `the budget bites`. Pinned by
  `ShipMotionJointsTest#aLegsStepIsTheControlSchemesStep`.
- **STAT-48** `[T][SYS]` **The bill rides the readout.** It is computed when the flight computer solves its
  model (MECH-SFM-07), for where the craft stands then, at rest, and never per tick — at most `MAX_STEPS`
  steps of a few multiplications each on that game tick; it travels in the readout's wire after the
  starved propellants (C2) and arrives whole: every row, the start, line and surface, and every leg.
  REGIONS: `a body below`, `no body below`. FOR: STAT-25, STAT-21. Pinned by
  `ShipReadoutTest#theWireCarriesTheBill`.
- **STAT-49** `[T][BEH]` **In the world the bill and the flight agree, within a tenth.** A craft flown on the
  bill's climb — Flight Assist off, full upward throttle held — carrying the propellant a bill says the climb
  spends and a tenth more crosses the line, and one carrying a tenth less turns back short of it, told so by
  its own bill. REGIONS: `reaches the line`, `runs dry short of it`. Pinned by
  `TierTwoCraftFlightModelGroupTest#aCraftCarryingItsAscentBillCrossesTheLineAndOneShortOfItDoesNot`
  (measured 2026-10-19 at a quarter gee in the overworld's air, the line at Y 255 from a pad at Y 154: the
  bill of six full tanks spends 3 092 kg for 32.3 m/s; a craft carrying 3 401 kg crossed, one carrying
  2 782 kg was told it runs dry at Y 240.6 and turned back at Y 237.8).

## Status of the tier-2 clauses (read with `ship-flight-model`)

| clause | state | where |
|---|---|---|
| STAT-1 derived, never constant | `[V]` the constant authorities do not exist; the controller allocates over this hull's actuators | `TileAdvancedFlightComputer#onPhysicsTick`, MECH-SFM-08 |
| STAT-2 live view, not persisted | `[V]` flight model rebuilt on construction change / load round, never saved | MECH-SFM-07 |
| STAT-3 durable inputs in owning tile | `[V]` wheel stored momentum in `TileReactionWheel` NBT; nothing else durable exists yet | C1 |
| STAT-4 one vector per ship, AFC-keyed | `[V]` one model per flight computer | — |
| STAT-5 split | the substrate has no ship split | — |
| STAT-6 mass | `[V][T]` | MECH-SFM-01/02 |
| STAT-7 / 7b geometric, per-axis, clean | `[T]` LP per signed direction, weak-not-spinny | MECH-SFM-05, INV-SFM-01..03 |
| STAT-7a stability guard | `[V]` bounded by the velocity SETPOINT cap (`SHIP_MAX_SPEED`), not by authority | — |
| STAT-8 / 9 shield, drive readouts | seam only: `FlightModelChangedEvent` carries the readout; no system contributes yet | C5 |
| STAT-10 registration seam | `[V]` the event on the bus (an extension point) | C5 |
| STAT-11 survives crossing/relog | `[A]` derived state rebuilds on the new tile; no e2e pins this yet | — |
| STAT-12 pre-flight readout, tier-2 warns | `[T]` AFC console (DESIGN\|LIVE) + assembler Scan, judged on DESIGN with the tanks full; TWR < 1 warns, second press builds | MECH-SFM-09/10 |
| STAT-13 tunables | `SURVEY_MIN_TICKS`, `READOUT_PUSH_TICKS`, wheel `TORQUE`/`MOMENTUM_CAPACITY`, `AllocationTolerances` | — |
| STAT-14 per-world gravity | `[V][T]` | — |
| STAT-15 twelve signed, sustained/burst | `[T]` | INV-SFM-02/04 |
| STAT-15 burst wheels, desaturated | `[T][V]` an idle wheel is unloaded by the sustained devices with no net wrench | INV-SFM-11 |
| STAT-16 constrained, deterministic, tolerances | `[T]`; gimbals NOT built | INV-SFM-01/05 |
| STAT-17 torque vs angular performance | `[T]` rotation targets `I·axis`; readout carries both | `ShipMotionLawsTest#rotationIsAnAccelerationOnThisHull` |
| STAT-18 compose, then λ | `[T]`; per-axis clip, sustained first | INV-SFM-06 |
| STAT-19 cached recipes, revisions | `[V]` recipes cached per model; the model's revision is the one counter | MECH-SFM-07 |
| STAT-20 mass seam | `[V][T]` | MECH-SFM-02 |
| STAT-21 client channels | `[V]` one packet of primitives to the pilot and console viewers only (`PacketShipReadout`) | C2 |
| STAT-22..24 propulsion physics | `[A]` | made concrete by STAT-30..43 |
| STAT-25, STAT-45..49 the bill | `[T]` Δv aboard, the three legs, the wire, the agreement with the scheme (fast tiers); the bill and the flight in the world (`TierTwoCraftFlightModelGroupTest`); body below only, the assembler's scan carries no bill | ship-flight-model MECH-SFM-12 |
| STAT-38..43 tier-2 consumption, boost columns, pressure bands | `[T]` laws in `PropulsionLawsTest`; the survey, drain and frame in `TierTwoCraftFlightModelGroupTest`; power budgets and the boost switch not built | ship-flight-model MECH-SFM-11 |
| STAT-44 rocket fuel tanks hold their role | `[T]` `TileFuelTank`, in `TierTwoCraftFlightModelGroupTest` | blocks MECH-BLK-03 |
| STAT-30..34, 37 engine law, drain, forecast, declaration | `[T]` on the laws (`PropulsionLawsTest`); the launch warning and the assembler's forecast are pinned in the server tier; the free-flight drain wiring by `FreeFlightCycleTest`, the classic ascent's has no test | — |
| STAT-35, STAT-36 core fuel and stack | `[T]` — `TileNuclearCore`, `RocketEngineTally`, `ChemicalMotorActuators#addNuclear`; the tier-1 scan's cases, burn-up, burn-out and spent fuel in flight in `NuclearEngineRocketAssemblyTest`, the tier-2 nozzle's column, upright and sideways, in `TierTwoCraftFlightModelGroupTest` | — |

**Known not built**: gimbals (STAT-16), the power budgets of STAT-22 (the propellant budget is
STAT-38..40). The hyperdrive reads the real
mass (MECH-HYP-09).
