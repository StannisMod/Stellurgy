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
  | block mass table | `util/WeightEngine.java` `defaultMaterials()` | kilograms per block; ordinary block **500**, wood 750, stone 2000, iron 5000, anvil 7500; `fallback` 500, `fluidFallback` 5 kg per mB |
  | Stellurgy component masses | same file | tank 1000, motor 10 000, guidance 9000, pressure tank / satellite hatch 25 000 |
  | engine thrust | `block/BlockRocketMotor` and siblings | **newtons**: basic and bipropellant 490 500, nuclear nozzle 1 716 750, advanced 2 452 500, nuclear core limit 49 050 000 |
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
- **STAT-12** `[A][BEH]` **Tier-2 warns and builds on confirmation; tier-1 refuses.** The vector + per-direction
  weak-spot flags are readable BEFORE flight on two co-primary surfaces: the AFC ship-info GUI
  (`IModularInventory`, LIVE) and the rocket-assembler GUI (snapshot on the existing Scan button,
  which already runs `scanRocket` `TileRocketAssemblingMachine.java`) `[V]`. Localization is
  per-axis / per-direction ("no +Z brake", "ventral shield gap", "emitter coverage 60%"), never a
  scalar aggregate. The readout is soft performance, distinct from the HARD assembly-failure
  `ErrorCode`s (a craft that did not materialise); they share the assembler surface, and the readout
  does not absorb that channel. **The gate differs by tier**: a tier-2 craft whose thrust cannot lift
  it off the body it was assembled on is built **after an explicit confirmation**; a tier-1 rocket is
  refused outright (STAT-26..29). The asymmetry is the tiers' actual difference: a tier-2 hull can be
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
pool zeroes the drive's live authority and leaves its design authority. Code: violated — availability
is binary. Consistent with STAT-15/18. Edges: an empty pool mid-burn is a live-capability change and
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
power-limited family and does not depend on power for chemistry. Code: violated — `IRocketEngine`
carries an integer thrust and a per-tick fuel rate that imply 24.5–86 km/s (measured).
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
`contentMassScale`. Code: violated — the frame is `{structural, content, crew}` and propellant sits in
`content` (`ship/mass/ShipMassFrame.java`). Consistent with STAT-6/20 (one mass model; the resample is
the authority). Edge: a holder the frame cannot count (NBT contents) is not in the pool at all (STAT-22),
so the term never sees mass it did not count.

### STAT-25 `[A]` — The readout computes and SHOWS the bill; it never refuses

Beside STAT-12's TWR: available **Δv** per regime by the rocket equation over the counted propellant;
the **ascent bill** to the shell for the body below or targeted, under the same drag and gravity
model the flight uses; the **landing bill**; and the TWR at that body. A craft that cannot climb back
out is told so before it descends, and may descend (an unreasonable act stays possible and costs what
it costs; maintainer ruling 2026-10-02).

*Stress-test.* Falsifiable: the shown Δv equals `v_e·ln(m0/m1)` for the counted pool; a flight that
spends the shown ascent bill reaches the shell within tolerance. Code: absent. Consistent with STAT-12
(a readout raised to a confirmation, never a refusal) and STAT-21 (the same channels). Edge: the bill is
computed by the flight's own model, never a second approximation — a readout that disagrees with the
physics is a silent fallback.

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
which is not a weight question), and `hasMissionFuelFor`'s "cannot lift itself here"
(`TWR ≤ 1` at local gravity, `entity/EntityRocket.java:1404`) belongs to the fuel mechanic and is
disabled with `rocketRequireFuel`. `[V]` `StatsRocket.java:320-322`.
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
take. In the shipped config every fuel weighs the weight engine's `fluidFallback`, so the choice is
invisible until a pack gives fuels their own weights. The heaviest-fluid choice and the absence of a
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
launch and every assembler scan is affected, and a pack whose fuels weigh more than `fluidFallback`
sees fewer crafts build; a moon is easier to leave than Earth, and a rocket that cannot launch full is
refused at the assembler instead of on the pad.

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
| STAT-12 pre-flight readout, tier-2 warns | `[V]` AFC console (DESIGN\|LIVE) + assembler Scan; TWR < 1 warns, second press builds | MECH-SFM-09/10 |
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
| STAT-22..25 propulsion physics | `[A]` | — |

**Known not built**: gimbals (STAT-16), budgeted resources (STAT-22). The hyperdrive reads the real
mass (MECH-HYP-09).
