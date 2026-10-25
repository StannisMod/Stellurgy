---
id: environment
owns: [environment/]
entrypoints: [PlanetEnvironment#sample, PlanetEnvironment#accruedDose, MagnetosphereDerivation#derive]
depends-on: [util-core]
depended-by: [satellite]
contracts: [C24]
confidence: high
---

## Purpose

What is physically present at a point near a body — its magnetic field and the radiation that field
traps — asked as a query at a position and a moment, never stored and never ticked. The first and so
far only provider is a planet's magnetosphere: a tilted, offset dipole whose trapped-particle belts
give one low orbit a high-dose and a low-dose segment by geometry alone.

## Responsibility boundary

Owns: the environment bundle, the planetary dipole and its belts, the dose accrued along a path, the
derivation of a body's dipole from its bulk, spin and seed, and a body's turn and day (how it turns, the
sidereal turn its solar day implies, the local solar time at a place).
Does NOT own: where a body's mass, radius, spin and seed come from (`universe`, `dimension-planets`),
the position of anything on an orbit (whoever holds the orbit hands in a path), what a dose DOES to
the thing that takes it (its consumer), or any reading a player holds (a measurement is the
vocabulary of `DataType`).

## Key types

| class | role |
|---|---|
| `PlanetEnvironment` | one body's environment: `sample(position, tick)` and `accruedDose(path, from, to)` |
| `LocalEnvironment` | the bundle a sample returns: field (tesla) and trapped dose rate (Gy/s), whole and per belt |
| `TrappedDose` | a trapped dose rate split by belt: inner-belt protons, outer-belt electrons |
| `PlanetaryDipole` | a tilted, offset dipole fixed to the body; its field, its belts' dose, its magnetopause |
| `RadiationBelts` | the belt profile over the shell parameter `L` and the fall toward the loss cone; Earth-calibrated |
| `MagnetosphereDerivation` | a body's dipole from mass, radius, spin, kind, distance from its star and seed |
| `Trajectory` | a path about a body over time, as the dose integration consumes it |
| `BodyRotation` | how a body turns: meridian 0's angle at a tick — the one rule the field and a satellite's release share |
| `SolarDay` | a body's day: the sidereal turn its solar day implies, and the local solar time at a longitude and tick |

## Mechanics

- **MECH-ENV-01 [V][SYS] A sample is ONE bundle.** `sample` returns the field and the trapped dose
  rate together — whole, and split into the inner belt's protons and the outer belt's electrons, which
  matter shields thin differently and of which only the protons upset memory — at a position in the body-centred frame whose axes do not turn with the body (z the
  spin axis). The field is fixed to the body, so the body's rotation (its period and phase at tick
  zero, `BodyRotation`) carries it round. `PlanetEnvironment.java:48-56,79-81`, `BodyRotation.java:27-30`. FOR: maintainer 2026-09-02, on the local
  environment as one query or one call per family: *"один пакет."*
- **MECH-ENV-02 [V][SYS] The dipole.** A moment, an axis tilted from the spin axis, and a centre offset
  from the body's. The magnetosphere ends at the Chapman–Ferraro stand-off — where the dipole's field,
  doubled by compression, balances the stellar wind's dynamic pressure — stated in body radii.
  `PlanetaryDipole.java:75-94`. FOR: as MECH-ENV-03.
- **MECH-ENV-03 [V][SYS] Trapped dose.** At a point on field shell `L` the dose rate is the belt
  profile at `L` times `(1 − B/B_cut)^n`, where `B` is the local field and `B_cut` the weakest field at
  which that shell's lines reach the absorbing sphere (the body plus its absorbing atmosphere) at ANY
  drift longitude: a trapped particle drifts round the whole shell and is lost at its lowest mirror
  point. A shell whose equator passes inside the absorber anywhere keeps nothing, as does any shell
  below `L = 1` or past the magnetopause. `B_cut` is tabulated per shell when the dipole is built.
  `PlanetaryDipole.java:100-135,142-183`, `RadiationBelts.java:65-74`. FOR: maintainer 2026-10-20, choosing
  that a satellite's failures follow the place it is in — asked *"фазу считать по времени из элементов
  орбиты … Сбой зависит от поля в точке, где спутник сейчас"*, answered *"этот"*.
- **MECH-ENV-04 [V][SYS] Dose along a path.** `accruedDose` integrates the trapped dose rate along a
  trajectory between two ticks with a fixed number of samples per period of the path, one game day
  standing for one Earth day (3.6 s a tick, `AstronomicalBodyHelper.SECONDS_PER_TICK`). `PlanetEnvironment.java:64-77`. FOR: maintainer 2026-10-20,
  on what the field does to a satellite: *"и то и другое в зависимости от дозы"* (both data loss and
  wear, by dose).
- **MECH-ENV-05 [V][SYS] Where a dipole comes from.** Strength by the empirical magnetic Bode's law,
  the moment growing with spin angular momentum `M R² ω` to the power 0.86 through Earth's moment;
  tilt and offset drawn uniformly within ranges bounded by measured planets — rocky by Mercury and
  Earth, giants by Saturn, Jupiter, Uranus and Neptune; axis longitude, offset direction and polarity
  uniform; the stellar wind falling as `1/d²` from its value at 1 AU. Equal seeds give equal dipoles.
  A rocky body keeps a dynamo only when its bulk density — the size of its metallic core — reaches
  4.7 g/cm³, midway between Mars (3.93, field dead) and Mercury (5.43, field alive); below it the body
  has no field, and its environment reports none. A giant always has one.
  `MagnetosphereDerivation.java:76-110`. FOR: maintainer 2026-10-20, asked where tilt and offset come
  from: *"Диапазон по каменным/гигантам"*; and on whether a dead dynamo is modelled: *"добавить кнч"*
  (add it, of course).
- **MECH-ENV-06 [T][SYS] A body turns with its orbits.** In the body-centred frame (z the spin axis: the
  cell's Y, with the cell's Z as −y) a prograde orbit runs clockwise, and a body turns the same way:
  `BodyRotation` advances meridian 0 clockwise once every sidereal period, a negative period the other way.
  East is the way the body turns. Pinned by `PlanetEnvironmentLawsTest#aBodyTurnsTheWayItsOrbitRuns`.
  `BodyRotation.java`. FOR: C16 CLOCK-6; maintainer 2026-10-24, choosing to turn bodies with their orbits so
  the day law holds and a moon can keep one face to its planet: *"этот"*.
- **MECH-ENV-07 [T][SYS] A body's day.** A body's `rotationalPeriod` is its solar day. The star moves round
  its sky with the orbit that carries the body round it — a planet's own, a moon's planet's — so the
  sidereal rate is the solar rate plus that orbit's rate (a prograde body's sidereal period is shorter than
  its day); a body locked to its star turns with its orbit and has no solar rate. Local solar time at a
  longitude (east positive) is the angle from the star's meridian to the place's, zero at noon, and is given
  in vanilla's world-time convention — a day of `rotationalPeriod` ticks, 0 sunrise, a quarter noon: it is the
  space tick plus a constant of the place, the constant for a body locked to its star. A moon facing its
  planet has the solar day its orbit and its planet's make (1/day = 1/orbit − 1/planet's orbit).
  `SolarDay.java`. Pinned by `PlanetEnvironmentLawsTest#aBodysDayIsItsTurnAgainstItsStar`. FOR: C16 CLOCK-6,
  which states it for a world's time of day; maintainer 2026-10-24, *"важно, чтобы игра была согласована
  внутри себя"*, *"ок"* to `rotationalPeriod` as the solar day.

## State & persistence

None. Every value is derived on demand from its inputs; nothing reaches NBT, the wire or a registry.

## Invariants

- **INV-ENV-01 [T][SYS] One low orbit has a high-dose segment, on the weak-field side.** Along a
  circular low orbit about a body whose dipole is offset, the dose rate spans orders of magnitude, and
  its high-dose segment lies on the side of the body away from the dipole's offset. Pinned by
  `PlanetEnvironmentLawsTest#aLowOrbitHasItsHighDoseSegmentOnTheWeakFieldSide`. FOR: maintainer
  2026-10-20, asked *"фазу считать по времени из элементов орбиты … Сбой зависит от поля в точке, где
  спутник сейчас"*, answered *"этот"*.
- **INV-ENV-02 [T][SYS] A seed decides where, and the same seed decides the same.** Two derivations
  with equal inputs give equal samples everywhere; two that differ only in seed put the high-dose
  segment of the same orbit in different places. REGIONS: `rocky`, `giant`. Pinned by
  `PlanetEnvironmentLawsTest#aSeedDecidesWhereAndTheSameSeedDecidesTheSame`. FOR: maintainer 2026-10-20,
  asked where tilt and offset come from with directions taken from the world's seed: *"Диапазон по
  каменным/гигантам"*.
- **INV-ENV-03 [T][SYS] No magnetosphere above the surface, no trapped dose.** A body whose stand-off
  is under one body radius reports its field and a trapped dose of zero at every point. Pinned by
  `PlanetEnvironmentLawsTest#noMagnetosphereAboveTheSurfaceTrapsNothing`. FOR: maintainer
  2026-10-20, on what the field does to a satellite: *"и то и другое в зависимости от дозы"* — where
  nothing is trapped there is no dose to do either.
- **INV-ENV-04 [T][SYS] Accrual agrees with the sample.** Over a day or more, the dose accrued along a
  path agrees with the sampled dose rate integrated tick by tick, and so does the sum of any two
  consecutive intervals that make up the day, both within 5 % — a fifth of INV-ENV-05's own
  tolerance, so integration error never decides a calibration verdict. Pinned by
  `PlanetEnvironmentLawsTest#accrualAgreesWithTheSample`. FOR: maintainer 2026-10-20:
  *"и то и другое в зависимости от дозы"*.
- **INV-ENV-05 [T][SYS] The model reproduces what was measured.** About Earth's eccentric dipole
  (2020: 7.72e22 A·m², moment toward 80.7°S 107.3°E, centre 570 km toward 22°N 141°E), a 400 km, 51.6°
  orbit averages 426 µGy/day within a quarter — the anchor — and two orbits the fit never saw land in
  their published ranges: geostationary orbit at 5–10 krad(Si)/yr and the 20 200 km, 55° navigation
  orbit at 20–50 krad/yr. Pinned by `PlanetEnvironmentLawsTest#theLowOrbitReproducesItsMeasuredDose`,
  `PlanetEnvironmentLawsTest#geostationaryOrbitLandsInItsPublishedRange`,
  `PlanetEnvironmentLawsTest#theNavigationOrbitLandsInItsPublishedRange`. FOR: maintainer 2026-10-20 on
  the dose quantity: *"Я бы взял то, что по си"*.

- **INV-ENV-06 [T][SYS] Only a core large enough keeps a dynamo.** A rocky body below the dynamo density
  derives no field, and its environment reports neither a field nor a trapped dose and accrues nothing;
  a rocky body at or above it, and every giant, derives a field. Pinned by
  `PlanetEnvironmentLawsTest#onlyACoreLargeEnoughKeepsADynamo`. FOR: maintainer 2026-10-21, asked
  whether Mars and the Moon should keep the field their spin would sustain or a working dynamo should
  be required: *"добавить кнч"*.

## Failure modes & edge cases

- A point inside the absorbing sphere, or exactly on the dipole's axis, has no trapped dose.
- Inputs that cannot describe a body (non-positive mass, radius, spin period, distance or moment) are
  refused with an exception rather than answered.

## Integration seams

The first consumer is `satellite`: `SatelliteLife` derives a body's environment from its world's
properties and the universe's body (mass, radius, spin, kind, distance from its star, the world's seed),
and `SatelliteAccrual` samples it at each segment of a satellite's orbit for the dose on its parts, the
data it loses and what a dosimeter reads. The quantities are `C24`'s environment in the sense of BODY-14 —
derived, never stored: a satellite persists the doses its parts took and its journal, never a sample.

## Config surface

None. The calibration constants map the model onto measured units and are not knobs
(`RadiationBelts`); the derivation's ranges are bounded by measured planets.

## Test coverage

INV-ENV-01..06, MECH-ENV-06..07 → `PlanetEnvironmentLawsTest`.

## Open questions

- How a belt's intensity scales with a body's field: every body keeps Earth's profile in `L`, and only
  its geometry and its magnetopause differ.
- Only trapped particles are modelled; galactic cosmic rays and stellar particle events are not.
- The stellar wind is a Sun-like star's at every star.
