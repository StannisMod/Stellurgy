---
id: hyperdrive
owns: [hyperdrive/, tile/hyperdrive/, tile/TileShipComponent.java]
entrypoints: [TileAdvancedFlightComputer#onJumpKey, TileAdvancedFlightComputer#update (spool), TileNavigationComputer#update (forecast), JumpGate#check]
depends-on: [space-model, api-public, network-wire, blocks]
depended-by: [jump-hazards (planned), ship-damage (planned), client-render]
contracts: [C1, C3]
confidence: high
---

## Purpose

The machines that make a jump possible, and the act of jumping.

A ship crosses the galaxy by opening a hyperspace **window** and riding inside it for the whole
flight. Four things decide whether it can: a **field generator** (how deep a well it climbs out of,
and how fast it crosses), the **emitters** that size the window around the hull, a **capacitor** that
dumps the momentary burst opening it and is FILLED BY THE SHIP'S OWN POWER, and **dampeners** that stand between the crew and a flight that
ends badly. Every one of them is measured off what the player actually built.

> **There IS a tier, and it is exactly one number**: `DriveTier` is a coefficient on the speed law and nothing else
> (`C25` HYPER-9: no reach license). Size (coils) is what a player BUILDS and is measured off the blocks; the tier is what a
> later generator BLOCK declares. See MECH-HYP-09 and MECH-HYP-19.

## Responsibility boundary

**Owns**: the machine family and its scans; the capacitor's stored charge and the cooldown that falls
out of it; the window's geometry and its coverage against the hull; the speed formula; the jump gate's
drive/power/supply clauses; the arm→press→spool→commit sequence and where the first cost is incurred.

**Does NOT own**: the transit itself (`ShipTransitManager` — this subsystem is its first production
caller), where a target comes from (the navigation computer and its crystals), what happens on the way
(path hazards, misjumps, emergency exits — planned), how a hull is damaged (planned), or how much a
hull weighs (a named seam, see MECH-HYP-09).

## Key types

| type | role |
|---|---|
| `DriveTuning` | every balance number in the subsystem, in one place; nothing pins one |
| `ComponentScan` | 6-neighbour walk from a controller through its own component blocks, hard-capped |
| `ShipDriveStats` | `drivePower` / `inFlightDraw` / `burstCost`; NBT round-trip |
| `CapacitorCharge` | a deficit over a rate — the cooldown forecast, and nothing else |
| `JumpWindow` | union of envelopes + per-block coverage against a hull box |
| `JumpSpeed` | `f(drivePower / shipMass)` and the forecast's transit-tick arithmetic |
| `DampenerField` | residual exit speed after coverage + tier; crew impact |
| `ShipMassProvider` | the drive's read of hull mass, in kg, through the ship mass port; absent when the hull cannot be weighed |
| `JumpSpool` | wind-up state + the "read the warning before you answer it" rule |
| `JumpTrigger` | press / commit; owns where the free part ends and the paid part begins |
| `ShipDrive` | one real ship's machines: finds them, sums them, fires the burst |
| `TileHyperdriveGenerator` | scans its coils; `stats()` is computed, never stored |
| `TileJumpCapacitor` | a real Forge Energy buffer; the stored charge in NBT |
| `TileJumpFieldEmitter` | stateless; contributes an envelope |
| `TileGravityDampener` | FE buffer; powered/not at the exit tick |
| `TileShipComponent` | the shared offset-link back to a ship's flight computer |

## Mechanics

### MECH-HYP-01 — A machine's worth is scanned, not stamped
`ComponentScan.from(controller, world, cap)` walks 6-neighbour connectivity outward and tallies
component kinds, stopping at its own edge and at a hard visit cap which it reports
(`Result.truncated()`). The generator counts coils; the capacitor counts cells and heat sinks. A fixed
`Object[][][]` multiblock template was rejected at implementation: a template is one size by
construction and so cannot express "bigger machine, better machine" at all. [T]
`ComponentScan.java:78-116`, `test/unit/HyperdriveStatsTest.java`.

### MECH-HYP-02 — The drive's three stats all derive from its power
`ShipDriveStats.ofPower` produces `inFlightDraw` and `burstCost` from `drivePower`, so a player who
builds a stronger drive automatically signs up for the bigger capacitor and the heavier flight bill
that come with it. Ratios `tunable`. [T] `ShipDriveStats.java:35-46`.

### MECH-HYP-03 — Stats are computed on read, never cached
`TileHyperdriveGenerator.stats()` re-scans. The coils ride the ship exactly as the generator does, so
re-counting can never disagree with the ship that is actually flying — a number written at assembly
time eventually would. The scan runs on a gate check (a key press), not per tick. [V]
`TileHyperdriveGenerator.java:36-52`.

### MECH-HYP-04 — The capacitor holds REAL energy, pushed in by the ship
`TileJumpCapacitor` is a Forge Energy receiver like any other machine: reactors, arrays and cables push
into it. Cells set `capacity()`; heat sinks set `acceptRate()`, which is a **throughput ceiling on what
the buffer will swallow per tick and never a supply**. Extraction through the capability is REFUSED — a
jump bank is not the vessel's battery, and only the drive's own burst takes from it (`discharge`). The
rule lives on the tile (`acceptCharge`) with the capability port three lines of delegation over it, so it
is askable without a capability registry standing up around it. [T] `TileJumpCapacitor.java`,
`test/unit/CapacitorChargeTest.java`.

> **The charge is energy the machine STORES, not a closed form of the CLOCK.** A formula
> `min(cap, c0 + rate·(t − since))` would make the biggest single cost in this family, the window burst
> at 20× the drive's power, free — paid for in wall-clock time — and a capacitor in an unloaded cell
> would be exactly as charged as one in a busy chunk, manufacturing energy through an absence (an
> unloaded ship's reactors are not running either). `[V]`

### MECH-HYP-05 — The cooldown is a consequence, not a timer — and a BEST CASE
There is no cooldown state anywhere. The wait between jumps is
`CapacitorCharge.ticksToReach(charge, capacity, acceptRate, burst)`: a deficit over a rate. Heat sinks
(which raise `acceptRate`) are the whole of the "cooling system". A bank too small to ever hold a burst
answers `-1` rather than counting forever, and so does a bank with no inflow at all — zero is never, not
"a very long time".

**What the number MEANS.** It is not a prediction: it is what the bank could do *at full inflow*;
whether the ship's power plant delivers that is the plant's business, and a pilot who under-built his
reactors waits longer than the console says. [T] `CapacitorCharge.java`.

### MECH-HYP-06 — Capacitors must ADJOIN the generator
`ShipDrive.capacitors()` keeps only banks 6-neighbour-adjacent to the generator's footprint
(controller + counted coils). Several are allowed and they sum. [V] `ShipDrive.java:60-74`.

### MECH-HYP-07 — The window is a union of envelopes, tested per block
`JumpWindow.of(generator, emitters)` seeds a baseline envelope around the generator — so a first ship
with no emitters at all can still jump — and adds one per emitter. `cover(hull)` counts hull blocks in
no envelope. A bounding-box test would call a hull covered that pokes through the gap between two
emitters; that shortcut is pinned closed. [T] `JumpWindow.java:127-146`,
`test/unit/JumpWindowTest.java` (`aHullThroughTheGapBetweenTwoEmittersIsNotCovered`).
The baseline envelope is a cube of half-extent `GENERATOR_BASELINE_WINDOW_RADIUS` = **5** (11³) centred
on the GENERATOR, not the ship; an emitter's is 6 (13³). A radius of 2 (5³) fits no
playable craft — the generator stands at a hull's side — so every first jump without emitters
would read "does not fit". Measured 2026-10-05 on assembled craft (`HyperdriveTest`): the milestone's jump craft is 0 blocks outside with no emitter (90 at radius
2); the mast craft `with-jump-drive-and-mast` 50 outside bare, 25 with one emitter built on, 0 with its own.

### MECH-HYP-08 — The hull's extent is recorded at assembly
`TileRocketAssemblingMachine` writes the craft's bounding box onto the flight computer as OFFSETS
(`TileAdvancedFlightComputer#setHullExtent`) while the whole craft is still on the pad — the one
moment anything knows its full extent. Offsets survive every relocation; positions survive none. A
craft whose extent was never recorded raises no coverage objection (an unmeasured hull is not a
faulty one). [V] `TileRocketAssemblingMachine.java` (tier-2 branch), `ShipNavigation#hullOutsideWindow`.

### MECH-HYP-09 — Speed is drive power against hull mass, times the drive's GENERATION
`JumpSpeed.blocksPerTick(drivePower, mass, tier)`, floored at 1 (the transit integrator refuses a zero
step, and a stuck ship is a softlock rather than a slow one). `mass` comes from `ShipMassProvider.
massOf` — **the hull's real mass in kg, through the ship mass port** (`FlightComputerMassSource`, the
same one the flight model weighs by). The
speed law is normalised by `DriveTuning.BASELINE_SHIP_MASS` = **326 250 kg, the measured mass of the
milestone's jump craft** (`with-powered-jump-drive`: the `with-jump-drive` hull's 321 250 kg plus the
5 000 kg power plug that charges its capacitor), a calibration and not a knob: that craft flies at the
baseline speed, and every other hull flies by its mass ratio. **An unweighable hull is ABSENT, never a stand-in**:
the forecast reads speed 0 (so its energy reads 0 — the gate does not refuse it), and `JumpTrigger`
refuses above the commit line with `msg.jump.nomass`. **There is deliberately no overload without the
tier** — which generation is flying is something every caller knows, and a default would silently give
the baseline speed to whichever site forgot. [T] `JumpSpeed.java`,
`TierTwoCraftFlightModelGroupTest#theDriveForecastsALighterHullFasterByItsMassRatio` (321 250 → 311 250
kg, 1556 → 1606 blocks/tick against the old baseline).

> **One constant speed cannot cover every band.** Crossing a system and reaching the nearest star differ by ~×5 900, and one coefficient
> cannot serve both — calibrated for the star a system collapses to one tick; calibrated for the system
> the star costs months. The months figure was not an endgame gate, it was the coefficient failing at
> the far end of its range. Nothing became piecewise even so: the fix is a third INPUT (the drive's
> generation), never a classification of the distance. `[V]`

### MECH-HYP-19 — A generation buys EFFICIENCY; size buys POWER; each tier owns one band
`DriveTier` — `INTERSTELLAR` (efficiency 1, the unit) and `GALACTIC`. Three properties, all pinned by
`DriveLadderTest`: `[T]`
- **η is derived** (`C25` HYPER-7): `GALACTIC.efficiency()` is the star→galaxy band gap
  (`2·REFERENCE_GALAXY_RADIUS_LY / MEAN_STAR_SEPARATION_LY` ≈ **×23 641**).
- **The ladder identity** (`C25` HYPER-8): a full build of each generation crosses its own band in the same
  time — measured **2 496 306 ticks (34.7 h)** for both at the shipped constants; it holds at any
  exponent, which is why the test asserts it rather than a duration.
- **A route's total ENERGY does not read drive power** (`C25` HYPER-6). `JumpSpeed.routeEnergy` is the closed
  form and the test checks it against the flown one.

`P(n) = GENERATOR_BASE_POWER + POWER_PER_COIL · n^α` lives in `DriveTuning.powerForCoils` — one place,
so `BASELINE_DRIVE_POWER` and `MAX_DRIVE_POWER` are both readings of it rather than literals that go
stale when α moves.

> **α IS STILL 1, AND WHAT BLOCKS IT IS AN INVARIANT, NOT AN OPINION.** The design derived α ≈ 2 so that
> iron alone closes the first band. Every energy cost of a drive is ∝ its power — the window burst above
> all — while the capacitor that pays that burst grows only with its COMPONENT count, capped at 256. So
> power spans `(512/7)^α` and the bank spans a few hundred: **at α = 2 a full drive's burst is ~205× a
> full bank and `JumpGate` refuses the jump outright past ~35 coils.** Measured at α = 1 the margin is
> **×2.50** (burst 10 260 000 against a bank of 25 620 000). No single constant fixes it — raising the
> bank leaves the reload absurd, lowering the burst deletes the capacitor as an early-game requirement.
> Pinned by `DriveLadderTest.aFULLYBUILTdriveMustBeAbleToOpenItsOwnWindow` plus its opposite end
> (`aBaselineDriveStillNEEDSacapacitorBank`), so raising α turns a test red instead of shipping a drive
> that gets useless when finished. `[T]`

### MECH-HYP-10 — Ownership is the offset link, plus the ship's own claim
`ShipDrive` resolves machines by iterating loaded tiles, keeping those inside the ship's subspace claim
whose recorded offset points back at THIS flight computer. Both halves matter: the link alone would let
an unassembled machine claim membership, the claim alone would let a neighbour lend its capacitor. The
assembler records the link for every `TileShipComponent` in the build. [T] `ShipDrive.java:236-258`,
`test/server/HyperdriveTest.java` (`anotherShipsMachinesAreNotYours`).

**Which claim, and how it is resolved.** The claim is the one
BELONGING TO the flight computer's own ship, resolved from the block's managing ship record
(`VSIntegration.registeredShipIdManagingBlock` → `shipyardBoundsOf`). It is not "the claim of the
registered ship nearest the flight computer", which would rank ships by their WORLD transform position
against a SUBSPACE block — two frames, so with two ships loaded the drive could skip its own machines
and permanently adopt a neighbour's. A block's managing claim is exact: distinct ships' claims never
overlap. A flight computer on no ship yields a null claim, which constrains nothing and leaves the
offset link as the sole test — the unassembled-build case, unchanged.
[V] `ShipDrive.java#yardBounds`, `VSBridge.java:1231`.

### MECH-HYP-11 — The console arms, the helm fires
Choosing a destination is deliberate work at a console, with the forecast in front of you; committing
to leave belongs at the helm, where the pilot can see what is around him. The navigation computer holds
the `armed` flag (per-ship, NBT) and **re-aiming always disarms**. The helm's key (J) reaches
`TilePilotSeat.PACKET_JUMP` → the seat's pilot guard → `TileAdvancedFlightComputer#onJumpKey`. [T]
`TileNavigationComputer.java` (`arm`/`disarm`/`setTarget`), `HyperdriveTest`.

### MECH-HYP-12 — Everything before the burst is free
`JumpTrigger.press` refuses, warns, winds up or stops winding up — no branch spends anything. A second
press during the wind-up aborts. At the end of the spool `JumpTrigger.commit` re-asks the gate (a spool
is long enough for the world to change under it), resolves the ledger entry, then fires the burst — the
**commit point** — then `beginTransit`, then disarms. Nothing that can refuse runs after the burst, so a
failure past it is a paid refusal by construction. [T] `JumpTrigger.java:90-176`, `HyperdriveTest`
(`aRefusedJumpNeverSpendsTheCharge`, `pressingAgainDuringTheWindUpAbortsItAndCostsNothing`).

### MECH-HYP-13 — A warning must be read to be answered
The press that meets an advisory is the press that RAISES it; only a second press inside
`ADVISORY_CONFIRM_TICKS` commits, and a stale one does not count. One press can never both warn and
confirm. [T] `JumpSpool.java:66-72`, `test/unit/JumpSpoolTest.java`.

### MECH-HYP-14 — Dampeners: tier absorbs, coverage decides who
`DampenerField.residualSpeed` protects a body only if a powered dampener is within radius, then
subtracts what that dampener's tier absorbs; the best covering dampener applies, so a wall of cheap
ones never substitutes for a better one. Sampled once, at an emergency exit — never per tick. **No
production caller exists yet**: the emergency exit that would invoke it is a planned subsystem. [T]
`DampenerField.java:36-63`, `HyperdriveStatsTest`.
> **The design of record differs from the code** (`C25` HYPER-33): it makes a dampener a sub-threshold
> body-force field with a sustained draw as well as the collapse buffer, and dampeners phase-locked into
> one network ADD — only independent ones keep "the best covering applies". This entry describes the
> CODE as built.

### MECH-HYP-15 — The forecast is the server's answer, displayed by the client
`TileNavigationComputer#update` recomputes drive power, capacitor vs burst, cooldown, ETA, flight
energy, hull outside the window and the gate's verdict every 20 ticks server-side and syncs the result
as TEXT. Every number in it is server-authoritative; a client that recomputed it would be showing a
guess the pilot is about to commit to. [V] `TileNavigationComputer.java` (`computeForecast`).

## The gate

`JumpGate.Stage` runs NAVIGATION → **DRIVE** → POWER → SUPPLY. The drive clauses are **built into
`reset()`**, not registered by this subsystem: a missed registration would wave through a ship with no
drive, silently and forever. `ShipContext` exposes seven `default`-valued primitives so the gate never
has to know what a capacitor is, and every default is the answer a driveless ship gives — with ONE
deliberate exception, `driveCoolantKelvin()`, whose `0` means "nobody measured this drive" and raises
no objection: a missing thermometer is not a missing machine, and defaulting it the other way would
ground every ship built before the thermal system existed.

| stage | clause | tier | key |
|---|---|---|---|
| DRIVE | a field generator aboard | HARD | `msg.jumpgate.nodrive` |
| DRIVE | the window encloses the hull | ADVISORY | `msg.jumpgate.windowundersized` |
| DRIVE | the drive's coolant is below the refusal temperature | HARD | `msg.jumpgate.driveoverheated` |
| POWER | `charge ≥ burstCost` | HARD | `msg.jumpgate.capacitorlow` |
| SUPPLY | `storedEnergy ≥ inFlightDraw × transitTicks` | ADVISORY | `msg.jumpgate.energyshortfall` |

POWER and SUPPLY stay silent when there is no drive, so the one useful line is not buried under two
useless ones — and so does the thermal clause, which is about a machine that is there. [T]
`test/unit/JumpGateTest.java`.

The thermal clause is the failure ladder's hyperdrive rung (C12 HEAT-11). Its subject is the coolant
loop bolted to the generator's own footprint, resolved fresh by `ShipDrive#coolantKelvin` like every
other drive number, and it sits HERE — above the burst — precisely because this gate is free: a check
that could refuse after the commit point would be the paid refusal the whole sequence is built to
avoid. The threshold and the loop's physics belong to `ship-heat` (MECH-HEAT-26). [T]
`test/server/HeatFailureLadderTest`.

## State & persistence (C1)

| key | owner | meaning |
|---|---|---|
| `afcLinked` + `afcOffset` | `TileShipComponent` | which ship this machine belongs to |
| `capBaseCharge` + `capSince` | `TileJumpCapacitor` | the lazy charge's two persisted numbers |
| `dampenerEnergy` | `TileGravityDampener` | FE buffer level |
| `hullExtent` (int[6]) | `TileAdvancedFlightComputer` | the craft's box, as offsets |
| `navArmed`, `navForecast` | `TileNavigationComputer` | armed flag; last server-computed forecast |

The spool is **deliberately not persisted**: a wind-up a restart interrupted resolves exactly like an
abort, and free is the correct price for a jump that never happened.

Registry names (contractual within 0.1.0): `hyperdriveGenerator`, `hyperdriveCoil`, `jumpFieldEmitter`,
`jumpCapacitor`, `jumpCapacitorCell`, `jumpHeatSink`, `gravityDampener`. Tile ids `ARhyperdrive
Generator`, `ARjumpFieldEmitter`, `ARjumpCapacitor`, `ARgravityDampener`.

## Invariants

- **INV-HYP-01 [T][BEH]** Asking the gate is free and side-effect free; a refusal never costs the pilot
  charge. `HyperdriveTest#aRefusedJumpNeverSpendsTheCharge`.
- **INV-HYP-02 [V][BEH]** The capacitor burst is the FIRST and ONLY thing spent: every check that can
  refuse a jump sits above the commit line (`JumpTrigger.java:207`). A departure the transit manager
  refuses after the burst is a paid refusal, answered `FAILED` and logged (`:208-222`).
- **INV-HYP-03** — retired: the bank holds real energy fed by the ship, so time away is not time
  charging (see INV-HYP-07).
- **INV-HYP-04 [T][BEH]** A machine belongs to exactly one ship; a neighbour can never lend one.
  `HyperdriveTest#anotherShipsMachinesAreNotYours`.
- **INV-HYP-05 [T][BEH]** Re-aiming disarms: a ship is never armed at a destination the pilot has already
  changed his mind about. `HyperdriveTest#clearingTheTargetDisarmsTheJump`.
- **INV-HYP-06 [V]** No balance number in this subsystem is pinned by any test; all of them live in
  `DriveTuning`.
- **INV-HYP-07 [T]** The jump bank charges only from energy the ship pushes into it: a bank with
  nothing feeding it never fills however long anybody waits, it accepts no faster than its
  throughput allows, and it is not the ship's battery. Pinned by `CapacitorChargeTest#aBankWithNoInflowNeverGetsThere`, `CapacitorChargeTest#aBankAcceptsNoFasterThanItsThroughputAllows`, `CapacitorChargeTest#theJumpBankIsNOTtheShipsBattery`, `HyperdriveTest#aFRESHBANKSTAYSEMPTYWHILETIMEPASSES`.

## Failure modes & edge cases

- **The coverage SHEAR is unwired.** The design has the hull outside the window sheared at the
  departure commit, through a ship-damage service that **does not exist in the tree**. The predicate
  ships as the ADVISORY it was specified to be; the consequence arrives with that subsystem.
- **`inFlightDraw` is a forecast stat, not a drain.** Nothing deducts it per tick during a transit, and
  nothing ends a flight early for running dry — both belong to the planned jump-hazards subsystem.
- **Dampeners have no consumer.** See MECH-HYP-14.
- A generator whose coil count exceeds `MAX_COILS` is counted up to the cap and the scan reports
  `truncated`; nothing surfaces that to the player yet.
- The client key path (keybinding → packet → seat) has no client e2e; everything from `onJumpKey`
  inward is pinned server-side.

## Test coverage

Unit: `HyperdriveStatsTest`, `CapacitorChargeTest`, `JumpWindowTest`, `JumpSpoolTest`, `JumpGateTest`.
Server e2e: `HyperdriveTest` (14 contracts through `/stellurgytest drive`), `NavigationComputerTest`
(the gate's drive clauses against a real ship).

## Open questions

- Should the truncated-scan flag be surfaced to the player (a generator quietly at its cap reads as a
  generator that stopped improving)?
- **Coverage is O(hull volume × envelopes) and runs once a second per navigation computer.** Deliberately
  left exact and unbounded: the hull box comes from the assembler's pad scan, so a realistic worst case
  (≈64³ × ~10 envelopes) is single-digit ms per second, and nothing has been measured to hurt. If a build
  ever produces a genuinely huge hull box, the fix is a strided subsample — which turns the uncovered
  COUNT into an estimate, so it should not be adopted before there is a measurement asking for it.
- Does the baseline window floor want to grow mildly with generator footprint? Documented as a
  `tunable` alternative in the design, not a separate mechanism.
