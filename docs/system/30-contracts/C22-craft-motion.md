---
id: C22
covers: what a tier-2 craft does with momentum — commanded, uncommanded, manned and not
confidence: RULED; MOTION-7 implemented (MOTION-3 is retired in place) — production still violates MOTION-2, MOTION-5 and MOTION-6, and MOTION-1's angular half
owner-subsystem: rocket-entity (TileAdvancedFlightComputer, MixinTileAdvancedFlightComputer) + the vendored physics substrate
see-also: [C9 (ship control — the input CHAIN; this says what the input does to the craft), C11 (physics-substrate port), C19 (reference frames — which body a velocity is measured AGAINST), C10 (ship stats)]
---

# C22 — Craft motion, clauses MOTION-1..MOTION-7

Maintainer direction, 2026-08-22, on being shown that the attitude law
holds whatever tilt the ground gave a craft: *"то есть мы в своей модели не в состоянии выразить
свободное вращение? … А она очевидно не должна. Это баг... но для того, чтобы завести это как баг,
нам нужен контракт на движение корабля... с управлением и без."*

**The defect, in one line.** There is no state in which a craft simply keeps the momentum it has:
every state either fights it or adopts it, and two independent mechanisms do the fighting.

## Terms

- **craft** — an assembled tier-2 ship: a physics body with a linked flight computer. **A station is
  a craft**; there is one model and no special case (maintainer, 2026-08-22).
- **commanded** motion — motion a controller asked for on behalf of a pilot or an autopilot.
- **uncommanded** motion — momentum the craft acquired any other way: a collision, a docking impact,
  a weapon hit, a shove. Not an error state; a fact about the world.
- **hold** — any mode in which a controller drives ω or v toward a reference: attitude hold, cruise
  (FA), station-keeping. *(What a hold COSTS the craft is out of scope here — see Rationale.)*
- **substrate** — the vendored physics engine that integrates the body
  (`valkyrienskies/…/PhysicsCalculations.java`); the port to it is C11's subject, its BEHAVIOUR is
  this contract's.

## Clauses

- **MOTION-1** *(conservation)* `[A][BEH]` Angular and linear momentum a craft did not command PERSIST.
  They decay only by a cause the model NAMES — a commanded hold, an atmosphere (MOTION-7), a
  collision. Ruled: *"крутится, конечно"*.
  **Violated today, once.** The attitude law below drives ω to zero unconditionally. The ambient
  decay is NOT a violation: `PhysicsCalculations.applyAirDrag` takes
  `FreeFlightPhysics.ambientDragFactor(AtmosphereDensity.inAtmospheres(world), dt)`, exactly 1.0 in
  vacuum (MOTION-7) `[V]`. **Note what "vacuum" covers**: `AtmosphereDensity` answers VACUUM for any
  world Stellurgy does not describe — the Nether, the End, another mod's dimension — so a craft there has no
  drag at all (`api/AtmosphereDensity.java`) `[V]`.
- **MOTION-2** *(a hold is a MODE, never the law)* `[A][BEH]` No controller drives ω or v toward a
  reference unless a mode is engaged that says so. With no mode engaged, centred controls command NO
  torque and NO force — not zero rate, not zero velocity. Ruled: *"режим … поэтому мы не можем
  по-умолчанию это делать"*.
  **Violated today for the ANGULAR channel only.** The linear channel already obeys this: FA-off
  returns `null` — "apply no force, coast on momentum"
  (`FreeFlightPhysics.java`) `[V]`, pinned by
  `unit/ShipVelocityCommandTest.faOffIdleCoastsWithNoForce` `[T]`. The angular channel has no such
  branch: attitude handling sits BEFORE and OUTSIDE `if (flightAssistEnabled)`
  (`TileAdvancedFlightComputer#update`: the unmanned release still publishes a `FlightCommand` with an
  attitude, and the piloted branch publishes one whatever the assist mode) — the attitude part is
  published unconditionally `[V]`.
- ~~**MOTION-3**~~ — **RETIRED BEFORE RATIFICATION, 2026-08-22.** It priced the hold. Ruled out of
  scope: *"затрачиваемый ресурс — это не предмет контракта, это обоснование. В этом контракте не
  будет цены удержания."* The anchor is burned and stays empty. Nothing else moves: MOTION-1 already
  says momentum persists unless a NAMED cause takes it, and "no hold is engaged" is such a cause
  whatever the reason.
- **MOTION-4** *(mode state belongs to the CRAFT)* `[A][BEH]` Engagement is the craft's own state and
  capability, not the pilot's: it persists across dismount, reload and crossing, applies to a station
  exactly as to a ship, and a pilot taking the seat is SHOWN the craft's current state on his HUD
  before he acts. Ruled: *"состояние (и способности) корабля, переключать может пилот. Когда он
  садится в кресло, на своём HUD он получает актуальную информацию с корабля."*
  Half-held today: FA is already a persisted craft setting (C9 SHIPCTL-18, NBT key `NBT_FLIGHT_ASSIST`) `[V]`
  — but it defaults to `true` (`TileAdvancedFlightComputer.java:1556`) `[V]`, and the angular half is not
  a setting at all.
- **MOTION-5** *(cause, not magnitude)* `[A][BEH]` Rotation a craft did not command is never treated
  differently by its SIZE alone.
  **Violated today.** `ATTITUDE_REFERENCE_RESEED = π/3` (`TileAdvancedFlightComputer.java:413`)
  `[V]`: below ~60° of uncommanded error the craft is dragged back, above it the rotation is ADOPTED
  and then held. Neither branch is a decision about what happened.
- **MOTION-6** *(reachability)* `[A][BEH]` From any attitude a craft can enter, the pilot's own controls
  can reach any other attitude.
  **Violated today**, and this is its player-facing shape: from `up ≈ 0` he cannot
  reach `up ≈ 1`, because the throttle is a BODY-frame command
  (`FreeFlightPhysics.shipVelocityCommand` → `bodyToWorldQ`) `[V]` so "climb" is horizontal thrust,
  and the law re-pins its reference to the tilt on every tick he is not steering.
- **MOTION-7** *(ambient drag is an ATMOSPHERE)* `[A][BEH]` Any velocity decay attributable to a medium is
  scaled by that medium's density and is ZERO in vacuum — **angular decay included**. Ruled: *"Да,
  должно. И зависеть от плотности атмосферы ещё"*.
  The density concept exists and tier 1 already consumes it: `IDimensionProperties`
  `getAtmosphereDensity()` (0–100 per planet) and `getAtmosphereDensityAtHeight(posY)` `[V]`, read by
  `EntityRocket.java` as `getAtmosphereDensity() / 100.0` with drag skipped entirely at 0 — so a
  dimension with no `DimensionProperties` (a cell) is vacuum by construction `[V]`.
  **Built**: `FreeFlightPhysics.ambientDragFactor(density, dt)` (`FreeFlightPhysics.java:149`) is the
  law, `AtmosphereDensity.inAtmospheres(World)` (`api/AtmosphereDensity.java:39`) the single source both
  tiers read, and the vendored `applyAirDrag` calls that one Stellurgy method. Retention is interpolated
  (not the loss), so vacuum is exactly 1.0 and the full-atmosphere end keeps the substrate's own
  constant. Angular decay is covered, which tier 1's own linear-only drag
  (`FreeFlightPhysics.atmosphericDrag`) is not `[V]`.

## Rationale — recorded because it is the REASON, not a clause

A hold spends the craft's resources, which is why it cannot be the default: *"Удержание ориентации
тратит ресурсы корабля (ну или станции, которая тоже корабль)"*. What it spends, at what rate, and
what a craft that cannot pay does are balance questions with their own life, deliberately outside
this contract. MOTION-1 already covers the outcome: a craft that is not holding keeps its momentum.

## Test coverage

| clause | pinned by | state |
|---|---|---|
| MOTION-1 | partially: MOTION-7's law tests `[T]`, plus `server/VSJumpingShipDoesNotFlingBystanders` and `server/VSRelocatedBodyIsNotFlungByItsLastShip` `[T]` | the ambient-drag half is pinned. The **bystander** half is pinned: a craft's jump invents no velocity for a body it was carrying — A/B measured at ≤ 1.0 block with the guard against 23 832 blocks without it. Its **mirror** is pinned: the two ways a lever arm can appear are the ship moving and the BODY moving, and only the first was guarded — a body relocated 4 500 blocks with its 20-tick association intact was written 3 145 blocks in 20 ticks by the hull it left, with its own motion reading zero. The bound is the ship's own AABB (`ValkyrienUtils.isEntityWithinShipBounds`), so the timer is asked for TIME and the ship for PLACE. **Uncovered**: the craft's OWN ω — nothing rams a craft and then measures it, and the attitude law drives ω to zero unconditionally. The same run measured that law from a new angle: a directly-written 0.5 rad/s spin on an assembled craft reads back `omega=0.000` on every tick, so a test that needs rotation must command it THROUGH the controller |
| MOTION-2 (linear) | `unit/ShipVelocityCommandTest.faOffIdleCoastsWithNoForce`, `.faOffCutCoasts` `[T]` | held, and the template for the angular half |
| MOTION-2 (angular) | `unit/ShipDeckCameraAndAttitudeHoldTest.zeroErrorWithResidualSpinCommandsAccelerationOpposingTheSpin` `[T]` | it pins the law OF THE HOLD, not a claim about the CRAFT. The clause itself is still unpinned, but it is now OBSERVABLE: `stellurgytest vs …` reports `pilotCmdAtt` and `pilotCmdAngVel` beside the linear `pilotCmdVel` `[V]`, so "is an attitude target published at all?" can be asked from a test. A pin needs the mode gate to exist first — it would be red today |
| MOTION-4 | C9 SHIPCTL-18 (FA as a retained setting) `[T]`; `VSShipUnmannedCruise` | half-held — the angular channel is not a setting |
| MOTION-5 | — | uncovered |
| MOTION-6 | — | uncovered; a launch from a pad that tips the craft demonstrates that it fails |
| MOTION-7 | `unit/FreeFlightPhysicsTest.vacuumTakesNoMomentumAtAll`, `.aFullAtmosphereRetainsExactlyWhatTheSubstrateAlwaysDid`, `.thickerAirNeverTakesLessMomentum`, `.twoHalfStepsTakeTheSameAsOneWholeStep`, `.anAbsurdDensityIsClampedNotObeyed` `[T]` | **HELD** at the law level. The CRAFT-level half is still uncovered: nothing measures a real coast in vacuum at the e2e tier |

**Six e2e arrangements silently assume the hold is the default** and will need to ENGAGE it once it
is not: `VSShipFlightTelemetry.aStationKeepingShipHoldsAltitudeInsteadOfSinking`,
`VSDeckCaptureAndDismount.standingUpWhileHoveringKeepsTheShipUpAndThePilotOnTheDeck` and
`.aHoveringShipKeepsHoveringAcrossAReloadInsteadOfFalling`,
`VSPilotStationDestruction.breakingTheOccupiedSeatDismountsThePilotAndHoldsTheShip`, two in
`VSCrewCaptureContract`, and the whole of `VSShipUnmannedCruise`. None is wrong about its own
subject.

## Open questions

- **The unmanned auto-level has no test, and no such behaviour exists.** No test asserts that an
  unmanned craft levels itself, and the unmanned branch commands the ship's CURRENT attitude
  (measured: no levelling is wired into the flight computer).
- **An assembled craft that has never been piloted IS SIMULATED — ruled 2026-09-29.** Maintainer:
  *"Симулируется (наша ветка)"* ("simulated"). Physics is on from assembly, and the retained Flight
  Assist setting decides the rest: with it the craft holds, without it the craft falls. Tests that
  relied on a never-flown craft being inert arrange their stillness explicitly. What follows is the
  part this ruling does NOT settle.
- **What an unmanned craft does** — hold, level, or drift — is one named behaviour per environment
  and is not yet chosen. The choice is between three behaviours none of which is implemented
  (the present one commands the CURRENT attitude), and "square the deck against world gravity" would
  still be undefined in a cell, which has no down.
- **The angular stop** a pilot needs in order to cancel an uncommanded rotation does not exist
  (additive; the linear cut/brake has no angular twin).
- **The launch torque itself.** MOTION-1 makes the tip legal, not desirable: the substrate's
  collision solver imparts it on every takeoff from a pad (measured).
