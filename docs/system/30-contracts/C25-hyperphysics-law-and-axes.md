---
id: C25
covers: the one fictional law under shields and hyperdrive, and the axes a craft is built along — drive, control, knowledge
confidence: RULED DESIGN-STAGE (2026-09-02; HYPER-15..30 ruled 2026-09-03). This is the authority; the status table measures how much of it the code has written. HYPER-5, 6 and 17 are realised and pinned; the control axis and the propulsion mechanism are not written
owner-subsystem: hyperdrive (JumpSpeed, JumpWindow, DriveTier) + shields (the same field, other regime)
see-also: [C24 (the bodies whose environments excite the field), C11 (physics-substrate port), C18 (living through a jump), C19 (reference frames)]
---

# C25 — Hyperphysics: one law, two regimes, three axes — HYPER-1..HYPER-33

Ratified 2026-09-02: one law for both regimes, and a research chain that is constants rather than
hardware.

**Why one law at all.** The shield and the hyperdrive independently built the **same geometry** — a union
of one region per emitter (`compositeHullDistance` and `JumpWindow.of`) — with the same nucleating
capacitor burst and the same phase-locked emitters. Two teams arriving at one construction is evidence
about the physics, which is what makes this law **derived** rather than invented.

## The law — the whole of the fiction, in one clause

- **HYPER-1** `[FICTION]` The vacuum carries a **single metastable degree of freedom `φ`**. Emitters excite
  and phase-lock it; where the local order parameter crosses its threshold, a region sits in the excited
  phase. **What that phase DOES is set by the mode it is driven in.** Nothing else in this contract is
  fiction: the environments that excite `φ` are `[REAL]` astrophysics, and every threshold, coefficient and
  approximation below is `[GAME]`.
- **HYPER-2** `[A][SYS]` **Two regimes, and they are the two subsystems.** *Wall mode*: the **boundary** between
  phases is a nonlinear domain wall coupling to matter and radiation — the shield. *Metric mode*: the
  **interior's** effective metric is modulated so the enclosed volume traverses distance differently — the
  hyperspace window. FOR: HYPER-1.
- **HYPER-3** `[A][SYS]` **One field, two READS.** The shield takes the **level set** `Q(x) = Q_c`; the window
  takes **`min Q` over the hull**. The shield uses its boundary and the window its volume — and neither may
  grow a second field. FOR: HYPER-1.
- **HYPER-4** `[A][BEH]` **A lone emitter is not a defect in metric mode.** Sustaining a *small* window is the
  graded rule at its floor: a small hull fits, a large one does not. Only a *macroscopic wall* from one
  emitter is forbidden, and that is a wall-mode statement.

## The drive axis — what a generation buys

- **HYPER-5** `[T][BEH]` **A generation buys EFFICIENCY; size buys POWER.** `v_H ∝ η·P/m`; transit ticks go as
  `d·m/(η·P)`. **BUILT** — `JumpSpeed.blocksPerTick(drivePower, mass, tier)`. Pinned by `DriveLadderTest#aFullBuildOfEachGenerationCrossesITSOWNBandInTheSameTime`, `HyperdriveTest#aStrongerDriveIsFasterAndCostsMoreToStart`.
- **HYPER-6** `[T][BEH]` **A route's total ENERGY does not read drive power at all.** Ticks go as `d·m/(η·P)`
  and the draw as `P`, so power cancels: a bigger drive changes how fast the bill is paid, never its size.
  **BUILT** — `JumpSpeed.routeEnergy`, checked against the flown route. Pinned by `DriveLadderTest#aRoutesENERGYdoesNotDependOnHowBigTheDriveIs`.
- **HYPER-7** `[A][SYS]` **η is DERIVED, never picked.** A generation's efficiency **is** the ratio of the bands
  it spans, so it keeps meaning "one band" when either constant is retuned. A third generation's η falls
  out of the galaxy→intergalactic ratio the same way or the property is gone. FOR: HYPER-8.
- **HYPER-8** `[A][BEH]` **The ladder identity**: a full build of each generation crosses **its own** band in the
  same time. That identity, not any duration, is what a test asserts, and a new generation inherits it.
- **HYPER-9** `[A][BEH]` **NO REACH LICENSE.** A tier is a coefficient on the speed law and nothing else — no
  permission, no cap, no refusal. A first-generation drive aimed across a galaxy departs and takes what it
  takes. **Under HYPER-1 this is not a rule but a consequence**: a coupling efficiency has no place to
  express a prohibition.

## The control axis — independent in capability, coupled through risk

- **HYPER-10** `[A][BEH]` **Control is its own axis.** Drive is longitudinal capability, control is transverse
  and topological, sensors and compute are knowledge and bandwidth. **No `C2 requires T2`**: every
  combination of a drive generation and a control rung must be buildable and must fly.
- **HYPER-11** `[A][BEH]` **Control authority is FINITE and its limit is felt, never enforced.** A turn tighter
  than the craft can make is **not achieved**, not refused, and the craft says why
  (`UNREASONABLE IS NOT IMPLEMENTED AS IMPOSSIBLE`).
- **HYPER-12** `[A][BEH]` **Topological routing moves between route FAMILIES, never between points.** Choosing a
  branch, crossing a basin, working a path well as relief — never teleportation.
- **HYPER-13** `[A][BEH]` **T and C are coupled through RISK, not permission.** `strain = wellStrength(mass) /
  drivePower` already grades hazard by drive power, so a bigger drive buys **tolerance of the terrain you
  turn in** — it does not buy turning.

## Research — the chain is information

- **HYPER-14** `[A][BEH]` **A site teaches a COEFFICIENT, and ordering is a property of information.** Each
  experiment yields the constant the next needs to subtract a term it cannot otherwise isolate; a
  measurement taken out of order is **real and unreducible**, never refused. No unlock is stored anywhere
  (`C24` BODY-8's sibling).

## How the drive MOVES anything — ruled 2026-09-03

The two BUILT laws are the evidence. `v = v₀·(P/P₀)/(m/m₀)·η` is a **steady rate at constant power**, and
`E = k·d·(m/m₀)·P₀/(η·v₀)` is **linear in distance and in mass** with power absent. Together they refute
the obvious readings: a craft flying sublight through a shrunken space would pay `½mv²` once and nothing
per block, and a thrusting craft would accelerate rather than hold a rate.

- **HYPER-15** `[A][BEH]` **A craft's velocity inside its own window is ZERO.** Nothing aboard accelerates, in
  any frame — no load, no dilation, nothing to feel. What advances is the **boundary**: `φ` ahead is driven
  into the excited phase and `φ` behind relaxes, so the region is **re-nucleated forward**. A phase front
  is a transition propagating, not a body travelling, and carries no light-speed bound. C18's promise to
  the crew is therefore structural rather than a mercy.
- **HYPER-16** `[A][BEH]` **The wall carries its contents because it is the SAME wall that stops matter.** The
  coupling that makes wall mode a shield is what keeps the contents from crossing; where the wall goes,
  they go. The wall must therefore impart momentum to inertia `m` continuously, so `P = F·v` with `F ∝ m`
  gives `v ∝ ηP/m` and `E = F·d ∝ m·d`. **HYPER-5 and HYPER-6 are consequences of this mechanism, not
  independent tunings** — which is what makes them safe to keep.

## What a jump COSTS, and where acceleration lives

- **HYPER-17** `[A][BEH]` **Two costs of two physical kinds.** *Nucleation* crosses the metastable barrier and
  is paid in one instant from a capacitor; *propagation* is the per-block convert-and-carry bill. The
  capacitor is required for a physical reason: **a barrier cannot be crossed by a rate.**
- **HYPER-18** `[A][BEH]` **The burst pays for VOLUME.** The barrier is a property of how much region is
  excited, so the departure cost is keyed to the window, never to drive power. The code charges
  `ceil(P · BURST_COST_PER_POWER)` (`hyperdrive/DriveTuning.java:75`), keyed to power, so hull volume is
  free at departure and gated only by `JumpWindow.cover`'s pass/fail — keying it to the window is the
  clause's first step.
- **HYPER-19** `[A][BEH]` **Spool is a COMMITMENT window, not physics.** `SPOOL_TICKS` (`hyperdrive/DriveTuning.java:178`) is flat and its own
  javadoc says aborting inside it costs nothing. A flat spool may never be cited as the front's spin-up:
  if a physical one is ever wanted it is a separate quantity and it goes as `m/P`.

## The floor — why hyperspace has a minimum speed

- **HYPER-20** `[A][BEH]` **The excited phase is sustained by MOTION.** Relaxation eats the region from behind;
  below a rate `v_min` the bubble consumes itself faster than the front regenerates it, and collapses.
  **`v_min` is a property of hyperspace** — one constant, derived from the relaxation rate, and it does
  **not** scale with the bubble's volume.
- **HYPER-21** `[A][BEH]` **`v ≥ v_min` is a BUILDABILITY condition, never a reach permission.** It yields
  `m_max = ηP / v_min`, the heaviest hull a drive can lift into hyperspace at all — so a generation buys
  **mass capacity** as well as speed. HYPER-9 is untouched: nothing about the destination is constrained,
  and `v/v_min` is the craft's margin against power loss.
- **HYPER-22** `[A][BEH]` **Falling below the floor is a COLLAPSE, not a refusal.** Power lost in flight, or a
  threshold raised by a gravity well (HYPER-13's `strain`), drops the craft out **where it is**, between
  stars. The burst is spent either way: the player may attempt what he cannot sustain and pays for it.
- **HYPER-31** `[T][BEH]` **A craft LEAVES hyperspace at rest, and keeps its cruise across every other
  crossing.** The flight computer's cruise setpoint is dumped at the hyperspace boundary — and ONLY
  there. Leaving a planet, landing on one and moving from one cell to the next all retain it
  (`C9` SHIPCTL-18), so this is a carve-out and not an instance of a general
  rule. **Maintainer, 2026-09-11**: *«Теряется скорость только при входе в гипер и выходе из него -
  там есть причина, корабль выходит из гипера с нулевой скоростью из-за гиперпространственного
  сброса скорости»*.
  **The dump is applied ONCE, on the way IN** (`ShipTransitManager`, after the direct-crossing branch
  has returned — the call site is the route) and **before the floor snapshot**, which is what makes a
  single site cover both ends: a transit RESTORED after a restart has no hyperspace ship left and
  pastes that snapshot, so an exit-side dump alone would miss exactly the arrival nobody watches.
  Consistent with HYPER-15 rather than additional to it: a setpoint riding the lane is a live command
  for a flight that is not happening.
  **Pinned by** `VSJumpDumpsTheCruiseTest`, whose two legs differ in the jump SPEED alone — the
  direct cell-to-cell crossing must KEEP the cruise, because "zero after a jump" is satisfied just as
  well by a regression that empties every setpoint everywhere, and that is the likelier defect of the
  two. Pinned by `VSJumpDumpsTheCruiseTest#aHyperspaceJumpLeavesTheCraftAtRest`, `VSJumpDumpsTheCruiseTest#aDirectCrossingKeepsTheCruise`.
- **HYPER-23** `[A][BEH]` **A collapse dumps the FRONT's energy into the contents** — not the craft's velocity,
  which is zero. `DampenerField`'s mechanic and every one of its numbers stand unchanged; only its
  javadoc's "dumps the transit's speed" is a fiction the law contradicts. **Consequence: speed is
  exposure** — a faster flight has a deadlier collapse, so a bigger drive is not a free good.

## Isolation — the interior is a Faraday cage in `φ`

- **HYPER-24** `[A][BEH]` **An excited region's interior is TOTALLY isolated**: no matter, no radiation, no
  information, in either direction. The mechanism is a **band gap**, not an obstacle — a channel signal is
  a small oscillation about the **ground** state and has no propagating solution in the excited phase, so
  it decays evanescently within a thin layer of the boundary. Consequence: **to reach the inside you must
  be inside**, i.e. in hyperspace yourself. Nothing in ordinary space may be given a reading of a craft in
  transit, sensors included.
- **HYPER-25** `[A][BEH]` **Fronts are FELT where waves are not.** A front is a phase transition, not a small
  oscillation, so no gap applies to it: a craft in hyperspace feels another front through the ground-phase
  field its own wall stands in. **Detection yes, communication no.**

## The wake, `η`, and a drive's locking frequency

- **HYPER-26** `[A][BEH]` **`η` is the SPLIT.** The share of drive power entering the wall is work; the
  remainder is heat and **the wake**. A less efficient generation is therefore **louder**, and the wake's
  amplitude is derived rather than tuned. Departure and arrival are concentrated, the route diffuse — with
  the built constants a burst is worth several hundred ticks of in-flight draw, so a short hop is two
  flashes and a long jump is a trail.
- **HYPER-27** `[A][SYS]` **The wake is COMPUTED from the transit record and elapsed time, never stored.**
  Nothing is voxelised and no galaxy-wide residue is persisted (`C24` BODY-12 and BODY-14, one layer out). FOR: HYPER-26.
- **HYPER-28** `[A][SYS]` **One field constant sets three observables** — `v_min`, the wake's loudness, and the
  propagation speed `c_φ` of a sub-threshold disturbance. Hence `c_φ ≈ v_min`; and since flight requires
  `v ≥ v_min`, **any craft that can fly at all outruns its own signal.** "A jump beats the message" is a
  theorem, not a balance choice. A front may exceed `c_φ` because a phase front is not a wave in the
  medium — a detonation outruns sound in the unburnt gas. FOR: HYPER-26.
- **HYPER-29** `[A][BEH]` **A drive has a locking frequency `ω`**, and it is a property of how the emitter array
  is CONFIGURED — never a stored faction id. **Mismatched `ω` ⇒ no coupling**: two craft occupy the same
  medium and pass without meeting or colliding. **Matched `ω` ⇒ coupling**, and coupling can unseat a
  lock, which under HYPER-20 is a collapse. Flying another's frequency is therefore hazardous by physics.
- **HYPER-30** `[A][BEH]` **`ω` leaks only through the WAKE, and is MEASURED, not read.** It is not a message,
  so no encryption bears on it; having measured it, a fleet can retune and **meet** the other. `ω` is
  **independent of the communication channel's key** — different devices, different numbers (ruled
  2026-09-03): tracking drives and intercepting traffic are separate disciplines.

## A sealed pocket — where the field STORES energy

- **HYPER-32** `[A][BEH]` **A metric-mode region closed by a wall-mode wall is a STORE, and its interior may be
  larger than its outside.** Metric mode modulates the interior's effective metric (HYPER-2); this clause
  extends that from the distance the interior traverses to the VOLUME it holds. A sealed pocket keeps its
  interior in the excited phase, and the energy it holds is the phases' energy-density difference times
  the interior volume, plus the wall's own. **It is a store, never a source** (`C26` ENERGY-7): it is
  filled from outside and gives back strictly less (ENERGY-9). It is isolated as any excited interior is
  (HYPER-24), so it gives energy only through its wall's controlled relaxation; if the wall loses support
  the pocket collapses and releases everything it holds at once. This is the MNT.
  *Stress-test.* Falsifiable as properties: a charge followed by a discharge returns strictly less; a
  pocket whose support is removed releases its whole content in one event, never a remainder that vanishes.
  Code: absent. Consistent with HYPER-1 (still one field, one more READ of it), HYPER-3 (no second field),
  HYPER-24 (the isolation that makes it sealed); it EXTENDS HYPER-2, which spoke of distance only — recorded
  as an amendment, not a reading. Edge: the interior-to-exterior volume ratio is the design's number
  (`tunable`), and it is what lets a small device hold an intergalactic jump.

## Below the threshold — a field that carries bodies

- **HYPER-33** `[A][BEH]` **A sub-threshold metric-mode field carries the bodies in it as a BODY force.** HYPER-16's
  carrying, without exciting a region: the field acts on every particle of a body at once, so the body
  feels only the field's NON-uniformity (the equivalence principle — a uniform body force is not felt). It
  moves no energy the drive did not supply: it changes how force reaches a body, not how much. It costs a
  draw to SUSTAIN — never less than proportional to the covered volume and the acceleration carried — its
  losses are `C12` heat, and its reaction runs field → emitters → structure. Fields from emitters
  phase-locked into one network add coherently; fields from independent devices do not, and the strongest
  covering one applies. This is the gravity dampener and the basis of a ship's own
  gravity.
  *Stress-test.* Falsifiable: a body inside a uniform field under the craft's acceleration takes no harm
  where an uncovered one does; at the field's edge harm rises with the gradient; doubling the carried
  acceleration over the same volume at least doubles the draw; two locked dampeners cover what one cannot,
  two independent ones do not. Code: the collapse half exists (`DampenerField`, sampled once, no stacking);
  the sustained half and coherent stacking are absent. Consistent with HYPER-16 (the same carrying), HYPER-28
  (a sub-threshold regime already exists), HYPER-3 (one field) and WALL-7's reaction path. Edge: a collapse
  is HYPER-23's — the dampener absorbs the front's energy at once from its buffer and dumps it as heat.

## Status — design stage

| clause | state |
|---|---|
| HYPER-1..4 | **absent.** No physical rationale for hyperspace is stated in `hyperdrive.md` or `space-model.md`; this contract is where the law lives |
| HYPER-3 | **not written**: both subsystems hold a union of per-emitter regions and neither holds a `Q`; the window's second read is a further step |
| HYPER-5, 6 | **BUILT and pinned** `[T]` |
| HYPER-7, 8 | **BUILT for two generations**; the third is ruled design and its numbers are open |
| HYPER-9 | **BUILT** — the amendment that made `DriveTier` a coefficient says so in as many words |
| HYPER-10..13 | **absent** — the control axis has no implementation at all |
| HYPER-14 | **absent** |
| HYPER-15, 16 | **absent as prose, held in fact**: the two built laws already have exactly this shape, which is why they are safe. No code states the mechanism |
| HYPER-17 | **BUILT** — `BURST_COST_PER_POWER` vs `IN_FLIGHT_DRAW_PER_POWER`, and the capacitor exists |
| HYPER-18 | **not written**: the burst is keyed to `P` today, so hull volume is free at departure. Re-keying it to volume is the first step |
| HYPER-19 | **held**: `SPOOL_TICKS` is flat and documented as the abort window. The physical spin-up is simply absent, which this clause permits |
| HYPER-20..22 | **absent** — no floor, so a drive too weak for its hull has no defined behaviour |
| HYPER-23 | **the mechanic is already realised**: `DampenerField` handles a mid-flight collapse with two levers and a residual-damage term, and every number stands. Its javadoc's "the transit's speed" contradicts HYPER-15 (the front's energy is what is dumped) |
| HYPER-24 | **unverified**: `HyperspaceWorld` suggests transit leaves the ordinary world, which would give isolation structurally. The risk is the registries spanning both sides. Measured 2026-09-03: `sensor/` and `api/sensor/` contain **zero** references to transit or hyperspace, so no filter exists either way |
| HYPER-25..28 | **absent** — there is no sub-threshold regime, no wake and no `c_φ` |
| HYPER-29, 30 | **absent** — a drive has no locking frequency |

**The constraint a half-implementation would break**: HYPER-9 is currently true because a tier is *only* a
coefficient. The control axis (HYPER-10) is the natural place for a reach gate to reappear wearing a
different name, and HYPER-11 is what forbids it. **HYPER-21 is the second such place**: a minimum speed is
a real physical floor, and the moment it is written as "you may not go there" instead of "this hull is too
heavy for this drive" it has become the reach gate under another name.
