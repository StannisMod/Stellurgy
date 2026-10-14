# rocket-entity — Free flight

Parent: [00-overview.md](./00-overview.md). Covers the opt-in `FREE_FLIGHT`
arcade control mode ("true RCS"). The decision layer is
the Minecraft-free `FreeFlightPhysics` (unit-testable); `EntityRocket.tickFreeFlight`
is the thin server driver; `FreeFlightInput` is the wire/clamp codec.

**Design axiom**: input is *intent*, the server is the source of truth. Every
channel is clamped to [-1,1] on read; the FF attitude is a body→world unit
quaternion (no gimbal lock through loops/inversions); Euler yaw/pitch/roll are
*derived* every tick only for legacy readers (camera, HUD, seat) and never
integrated `FreeFlightPhysics.java:196`, `EntityRocket.java:186`.

## Mechanics

### MECH-RKT-10 — Flight-mode selection & authority
`SET_FLIGHT_MODE` sub-packet carries the enum ordinal. Server handler accepts it
only from a passenger of *this* rocket and only while **not** in flight, then
echoes to trackers `EntityRocket.java:3348`. Mode persists as the string NBT key
`flightMode`, written unconditionally so it round-trips even as CLASSIC
`:3094`. `RocketFlightMode.readFromNBT` tolerates missing/unknown values →
`DEFAULT` (CLASSIC) `RocketFlightMode.java:28`.

### MECH-RKT-11 — Engine-start ritual & liftoff gate
Two entries set flight: the `ENGINE_START` packet (pilot held the 3 s ritual) and
`prepareLaunch()` in FF mode `EntityRocket.java:2565`. Both funnel through
`canStartFreeFlight()`, which requires fuel **and** positive climb authority
(`stats.getAcceleration(gravMult) > 0`, i.e. TWR > 1) `:999`. Without this shared
gate a fuelless/underpowered FF rocket would set `isInFlight` yet never thrust,
never leave the ground, and thus never re-arm the landing detector — a permanent
on-pad dead-state. On success `startFreeFlight()` sets flight, resets latches,
zeroes input + FA setpoint, resets the attitude to identity, and arms the liftoff
target `posY+1` `:954`.

### MECH-RKT-12 — Attitude-quaternion integration
Each server tick, `integrateBodyRates(q, pitchRate, yawRate, rollRate)` composes a
small body-frame delta (pitch about +X/right, yaw about +Y/up, roll about +Z/nose)
and post-multiplies (`q⊗dq`), then renormalises `FreeFlightPhysics.java:266`. Rates
= input × `MAX_PITCH_RATE`/`MAX_YAW_RATE`/`MAX_ROLL_RATE` (tunable) `EntityRocket.java:1066`.
The new quaternion is committed, `prevFfQuat` snapshotted for render slerp, and the
Euler view derived via `eulerFromQuat` and pushed into `rotationYaw/Pitch` +
`freeFlightPitch/Roll` `:1102`. The quaternion replicates as four full-precision
floats `FF_QW/QX/QY/QZ`.

### MECH-RKT-13 — Flight Assist velocity-setpoint control
Default ON. Translation keys **ramp a body-frame velocity setpoint** rather than
thrust directly: `rampSetpoint` adds `input×SETPOINT_RAMP` per held tick, clamped
to `FA_SETPOINT_MAX_SPEED` `FreeFlightPhysics.java:651` — **the only speed ceiling free
flight has, and it bounds what the assist may be ASKED for, never what the craft may
reach**.
The cut flag (X) zeroes it instantly.
`faStep` then computes the world-space acceleration that tracks the setpoint plus a
gravity-compensation term, clamps it to the thrust budget and applies it — with **no**
velocity clamp, so a craft arriving faster than the setpoint is decelerated at its
thrust budget rather than having its velocity rewritten `:414`; zero setpoint =
strive-to-hover. The setpoint replicates
via `FA_SP_FWD/RIGHT/UP` for HUD bars. Re-enabling FA mid-flight captures current
world velocity into the body frame as the setpoint so the toggle never jerks
`EntityRocket.java:931`.

### MECH-RKT-14 — Newtonian (FA-off) direct thrust
FA off → `translateNewtonian`: translation channels are direct body-frame thrust
while held, coasting on release; the cut zeroes translation for the tick; the manual
brake (Shift) attenuates all motion by `BRAKE_RETENTION` `FreeFlightPhysics.java:448`.
**There is no speed cap**: the law is Newtonian and the only bound is
`MAX_THRUST_ACCEL`, so a craft gains speed for as long as it burns and must burn as
long again to shed it. Rotation still integrates via MECH-RKT-12 (the caller
advances the quaternion before translating).

Where a bound genuinely belongs it is the ENVIRONMENT's, not the craft's, and none is
imposed in this layer: a vanilla entity resolves collision against the SWEPT box it is
about to traverse (`Entity.move` queries `getCollisionBoxes(expand(x,y,z))`), so a rocket
cannot pass through terrain at any speed, and empty space has nothing to hit.

### MECH-RKT-15 — Engine-start liftoff hover assist
Until the pilot first gives translation input, `ffLiftoffTargetY` is set and
`liftoffStep` eases the craft onto `launchY+1` and holds: proportional climb
(`err×LIFTOFF_GAIN`, clamped to `±LIFTOFF_CLIMB_RATE`), gravity treated as cancelled
by the thrust budget, horizontal drift damped by `HOVER_RETENTION` with a
`STOP_SNAP` deadband `FreeFlightPhysics.java:674`. Any translation input sets the
target to `NaN`, permanently ending the assist for that flight `EntityRocket.java:1056`.

### MECH-RKT-16 — FF thrust authority & fuel burn
`thrustMag = stats.getAcceleration(gravMult) + gravity`, so at full vertical
throttle net thrust equals the classic ascent acceleration — the FF climb gate is
*exactly* the classic TWR>1 gate; no invented scale `EntityRocket.java:1045`.
`FreeFlightPhysics` clamps thrust into `[0, MAX_THRUST_ACCEL]`. Fuel is NOT accounted
in the pure layer: when `Step.thrustApplied` and `rocketRequireFuel`, the driver
drains `getFuelConsumptionRate` (plus oxidizer for bipropellant), mirroring the
classic burn `:1138`. `FF_ENGINE_POWER` = |thrust Δv minus gravity| / `MAX_THRUST_ACCEL`,
replicated so the engine sound tracks thrust in **any** direction (incl. a hover)
`:1123`.

### MECH-RKT-17 — FF landing auto-shutdown
The landing detector arms only once `freeFlightHasLeftGround` (the craft first left
`onGround`) — so the engine-start hover can never read as a touchdown, no timed
grace needed `EntityRocket.java:1160`. Then `FreeFlightPhysics.shouldLand(onGround,
motionY)` (`onGround && |motionY|<0.05` `FreeFlightPhysics.java:731`) latches the
landing: zeroes motion, `setInFlight(false)`, posts `RocketLandedEvent`, broadcasts
`ROCKETLANDEVENT` `:1164`.

### MECH-RKT-18 — Client predict-then-correct smoothing
FF is fast and the entity tracker samples pose ~once per tick; freezing then
snapping reads as jitter. Each **client** tick dead-reckons position from synced
velocity, then pulls the *residual* (`serverTarget − predicted`) toward the target
over `FF_CLIENT_CORRECT_TICKS` `EntityRocket.java:1883`. Correcting the residual, not
the raw gap, avoids double-counting this tick's motion (the FA-off sawtooth).
Attitude uses the same shape on the quaternion via `slerp(predicted, replicated,
1/ticks)` `:1903`. `setPositionAndRotationDirect` records only the server position in
FF (attitude comes from `FF_Q*`), suppressing the classic poscorrection smoothing
`:1541`.

### MECH-RKT-19 — FF pilot-input wire codec
`FreeFlightInput` = 7 big-endian floats (fwd, vert, strafe, yaw, pitch, roll, brake)
+ 1 flag byte (cut), `WIRE_SIZE=29` `FreeFlightInput.java:35`. `read` clamps every
channel and collapses NaN/Inf to 0 — malicious out-of-range floats are neutralised
`:114`. `FREE_FLIGHT_INPUT` server handler accepts input only from a passenger, only
in FF mode, silently dropping otherwise (`applyFreeFlightInput` re-checks mode)
`EntityRocket.java:3369`, `:890`. `SET_FLIGHT_ASSIST` toggles FA (passenger, allowed
in-flight) `:3383`.

## State & persistence

The free-flight NBT keys (`flightMode`, `ffQuatW/X/Y/Z`, `flightAssistOn`, `ffLiftoffTargetY`, `ffHasLeftGround`,
`faSetpointFwd/Right/Up`, with their read defaults) are inventoried in `persistence-wire`; `ffQuat` is the source of truth for attitude
(Euler is not persisted) `EntityRocket.java:3092`–`3108`. Replicated DataParameters: `FF_QW/QX/QY/QZ`, `FA_SP_FWD/RIGHT/UP`, `FF_ENGINE_POWER`,
plus classic `INFLIGHT`. The FF-input intent (`currentFreeFlightInput`) is transient,
re-seeded per flight.

## Invariants

- **INV-RKT-07 [A][SYS]** Attitude source of truth is the quaternion; Euler is derived
  each tick and never integrated `EntityRocket.java:1104`, `FreeFlightPhysics.java:301`.
  Pinned: `bodyBasis` reproduces the quaternion basis near identity
  `FreeFlightAttitudeTest.java:49`, orthonormality `:137`. Pinned by `FreeFlightAttitudeTest#eulerFromQuatRoundTripsBodyBasis`, `FreeFlightAttitudeTest#identityBasisMatchesEulerZero`, `FreeFlightAttitudeTest#derivedBasisIsOrthonormalRightHanded`. FOR: INV-RKT-23.
- **INV-RKT-08 [A][SYS]** Every `FreeFlightInput` channel is clamped to [-1,1] and NaN/Inf
  →0 on construct/read `FreeFlightInput.java:79`,`:94`; pinned
  `FreeFlightInputTest.java:41,52,70`. Pinned by `FreeFlightInputTest#constructorClampsAboveOne`, `FreeFlightInputTest#constructorClampsBelowMinusOne`, `FreeFlightInputTest#constructorCollapsesNanAndInfinityToZero`, `FreeFlightInputTest#clampStaticHelperBehaviour`, `FreeFlightInputTest#readReclampsOutOfRangeWireValues`. FOR: INV-RKT-23.
- **INV-RKT-09 [A][BEH]** FF entry requires fuel AND TWR>1 via `canStartFreeFlight`; a
  fuelless FF rocket stays grounded after `prepareLaunch`
  `EntityRocket.java:2575`; pinned `FreeFlightLaunchGateTest.java:82`. Pinned by `FreeFlightLaunchGateTest#fuellessFreeFlightRocketStaysGroundedOnPrepareLaunch`.
- **INV-RKT-10** — retired. Free flight is bounded by ACCELERATION, not by a speed cap; see INV-RKT-23.
- **INV-RKT-11 [A][SYS]** Setpoint ramp: held key adds `SETPOINT_RAMP`/tick, cut zeroes,
  magnitude clamped to `FA_SETPOINT_MAX_SPEED` `FreeFlightPhysics.java:651`; pinned
  `FreeFlightAssistsTest.java:41,55,63`. Pinned by `FreeFlightAssistsTest#holdingForwardRampsTheSetpoint`, `FreeFlightAssistsTest#releasingTheKeyKeepsTheSetpoint`, `FreeFlightAssistsTest#cutZeroesTheWholeSetpointInstantly`, `FreeFlightAssistsTest#setpointMagnitudeIsClampedToTheAssistCeiling`. FOR: INV-RKT-23.
- **INV-RKT-12 [A][BEH]** Zero input + gravity-only when thrust not permitted →
  Newtonian brick (`motionY -= gravity`) `FreeFlightPhysics.java:397,526`; pinned
  `FreeFlightPhysicsTest.java:64`. Pinned by `FreeFlightPhysicsTest#cannotThrustDisablesThrustButStillRotatesAndApplyGravity`, `FreeFlightAssistsTest#noFuelMeansNewtonianBrick`.
- **INV-RKT-13 [V]** FF physics/fuel run server-side only; `tickFreeFlight` early-returns
  on `world.isRemote` and on non-FF/not-in-flight `EntityRocket.java:1022`.
- **INV-RKT-14 [V][BEH]** FF mode selection is passenger-gated and refused in flight
  `EntityRocket.java:3348`. No test selects the mode with and without a passenger.
- **INV-RKT-23 [A][BEH]** Free flight bounds ACCELERATION and not speed: no step path caps
  velocity, and no tick may add more than `MAX_THRUST_ACCEL`
  `FreeFlightPhysics.java:414,448,683,747`. So burning for `n` ticks buys exactly
  `n × MAX_THRUST_ACCEL`, and a rocket at an ordinary 0.1 b/t² reaches first cosmic
  velocity (395 b/t) in 3 950 ticks; pinned by both legs together
  `FreeFlightPhysicsTest.java:172,208` and, for the assist, by the no-rewrite leg
  `FreeFlightAssistsTest.java:200`. Replaces the retired INV-RKT-10. Pinned by `FreeFlightPhysicsTest#newtonianFlightBoundsAccelerationAndNotSpeed`, `FreeFlightPhysicsTest#aRocketAtOrdinaryThrustReachesFirstCosmicVelocity`, `FreeFlightPhysicsTest#thrustAccelClampedToMaxThrustAccel`.
- **INV-RKT-24 [A][BEH]** A craft's speed is bounded by WHERE IT IS, not by the law: every free-flight
  law's result passes through `FreeFlightPhysics.atmosphericDrag` before it is applied
  `EntityRocket.java` (post-law, one call site), so the bound is the dimension's own atmospheric
  density and in vacuum there is none. Drag is quadratic in speed, linear in density, applied along
  the velocity vector (it slows, never turns) and clamped to the speed itself, so air brings a craft
  to rest and never through it. Terminal speed at full thrust in one atmosphere is
  `ATMOSPHERIC_TERMINAL_SPEED` = 100 b/t, and `DRAG_PER_DENSITY` is DERIVED from it
  (`MAX_THRUST_ACCEL / v_term²`) rather than tuned. This is the half of the removed speed cap that
  had to come back somewhere: INV-RKT-23 lets a craft arrive at a planet arbitrarily fast, and this
  is what charges it — in time, the only currency the acceleration law has.
  **The number is derived, NOT ratified**; heating/damage on entry is deliberately not implemented. Pinned by `FreeFlightPhysicsTest#vacuumTakesNoMomentumAtAll`, `FreeFlightPhysicsTest#thickerAirNeverTakesLessMomentum`, `FreeFlightPhysicsTest#anAbsurdDensityIsClampedNotObeyed`, `FreeFlightPhysicsTest#aFullAtmosphereRetainsExactlyWhatTheSubstrateAlwaysDid`.

## Failure modes & edge cases

- Attitude persists as `float` quaternion; re-normalised on load `:3024`, so
  precision loss is bounded — acceptable for orientation (not a physics-integration
  variable).
- `MAX_THRUST_ACCEL` (arcade cap) bites only on absurd TWR builds; normal rockets
  sit far below it `FreeFlightPhysics.java:60`.
- Toggling FA off→on mid-flight while `!isInFlight()` zeroes the setpoint rather than
  capturing velocity `EntityRocket.java:939` (correct: on the pad there is no velocity).

## Integration seams

Free-flight sub-packets (`SET_FLIGHT_MODE`, `FREE_FLIGHT_INPUT`, `SET_FLIGHT_ASSIST`, `ENGINE_START`; append-only ordinals, wire-stable,
`EntityRocket.java:3848`) and their server-authority checks: see `persistence-wire`. `FreeFlightInput` binary codec (C2). Events: `RocketLandedEvent` on touchdown.
Renderer/camera consume `getFfQuat`/`getPrevFfQuat`, `getFreeFlightRoll`,
`getEnginePower`; seat via `updateFreeFlightPassenger`.

## Config surface

Config: see `C4-config-surface` (`rocketRequireFuel` off ⇒ thrust is always available, `hasFreeFlightThrustFuel` returns true, `EntityRocket.java:980`).
Gravity per tick scales with the dimension `getGravitationalMultiplier`. Physics constants
(`MAX_*_RATE`, `FA_SETPOINT_MAX_SPEED`, `SETPOINT_RAMP`, `LIFTOFF_*`, `BRAKE_RETENTION`,
`HOVER_RETENTION`, `MAX_THRUST_ACCEL`) are code constants marked `tunable` — not
config, not pinned.

## Test coverage

`FreeFlightPhysicsTest`, `FreeFlightInputTest`, `FreeFlightAssistsTest`,
`FreeFlightAttitudeTest` (unit); `FreeFlightCycleTest`, `FreeFlightLaunchGateTest`
(server, driven through `/stellurgytest` probes); `FreeFlightModeTest` (client). See
the pinned invariants above.
