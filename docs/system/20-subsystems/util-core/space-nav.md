# util-core / Space navigation & physics

Parent: [00-overview.md](./00-overview.md) · Files: `SpacePosition.java` (97),
`AstronomicalBodyHelper.java` (168), `GravityHandler.java` (110),
`PlanetaryTravelHelper.java` (112), `SpaceTravelInteraction.java` (73),
`MobileAABB.java` (301), `StationLandingLocation.java` (70),
`DimensionBlockPosition.java` (36), `TransitionEntity.java` (20)

The orbital-mechanics and per-entity physics helpers: where a body is, how bright/hot it
is, how gravity nudges entities, whether a trip stays inside a planetary system, and how
an entity collides with a moving structure. Mostly stateless static math plus a couple of
small state carriers.

## Key types

| type | role |
|------|------|
| `SpacePosition` | 6-DOF free-space position (`x y z yaw pitch roll` + star/world context); NBT round-trip; spherical + distance helpers |
| `AstronomicalBodyHelper` (static) | size/period/theta/temperature/brightness formulas |
| `GravityHandler` (singleton, `IGravityManager`) | per-tick `motionY` gravity application; GC-provider bridge via reflection |
| `PlanetaryTravelHelper` (static) | is-within-system predicates + trans-body injection burn length |
| `SpaceTravelInteraction` (static) | free-flight ↔ near-planet ↔ solar-nav transition on entity tick |
| `MobileAABB` | AABB subclass resolving Y collision against a `StorageChunk`'s blocks |
| `StationLandingLocation` | named landing pad slot (pos + occupied/auto-land flags) |
| `DimensionBlockPosition` | `(dimid, HashedBlockPosition)` value key |
| `TransitionEntity` | queued cross-dimension teleport record (time/entity/dim/loc/mount) |

## Mechanics

- **MECH-NAV-01 — SpacePosition NBT round-trip.** `writeToNBT`/`readFromNBT` store a
  `spacePosition` sub-compound (doubles `x y z yaw pitch roll`, ints `star`/`world` when
  present, bool `isInInterplanetarySpace`); star/world are re-resolved through
  `DimensionManager` on read; missing `spacePosition` tag ⇒ no-op read.
  `SpacePosition.java:25-70`.
- **MECH-NAV-02 — distance & vectors.** `distanceToSpacePosition2` (squared Euclid),
  `getNormalVectorTo` (unit vector), `getFromSpherical(radius,theta)` (polar offset in the
  x/z plane, y preserved, context carried). `SpacePosition.java:16-96`.
- **MECH-NAV-03 — orbital formulas.** `getBodySizeMultiplier` (AU/dist),
  `getMoonSizeMultiplier` (100/`moonViewUnits`) — and `moonViewUnits(d) = 150 × d /
  MOON_REFERENCE_UNITS`, the ONE reference every moon-level view reads a moon's distance through, so
  Luna shows as she did at 150 and other moons in proportion (ruling 2026-09-30;
  `AstronomicalBodyHelper.java:239-304`) `[T]` `AstronomicalBodyHelperTest`,
  `getOrbitalPeriod`/`getMoonOrbitalPeriod` (Kepler-ish `∝ dist^1.5`),
  `getOrbitalTheta`/`getMoonOrbitalTheta` (current angle from universal world time),
  `getParentPlanetThetaFromMoon` (render-side apparent angle). `AstronomicalBodyHelper.java:13-88`.
  - **A moon's period is derived from its parent's MASS in Earth masses**, via
    `DimensionProperties.getOrbitalMass()` (`:575`) — the stated mass, falling back to surface
    gravity only when nothing has stated one, which is exact at one Earth radius. Passing gravity
    unconditionally is correct for Earth and wrong by `sqrt(M/g)` elsewhere (a Jupiter's moons would
    run 11.2× too slowly). `[T]`
  - **A planet's period is derived from its star's MASS in solar masses**, via
    `StellarBody.getMass()`, which falls back to `M = R^1.25` (the main-sequence `R ≈ M^0.8`) where
    none is stated. Passing the star's RADIUS would give
    `P ∝ a^1.5 / R^1.5` against Kepler's `P ∝ a^1.5 / sqrt(M)` — exact for Sol, 1.83× too fast at
    2 R☉ and 2.87× too slow at 0.3 R☉. `[T]`
  - **A body's rotational period is DRAWN, not derived.** `PlanetDerivation.rotationalPeriodOf`
    draws it log-uniformly from `(seed, cell)` — 0.25×–4× the default day for a rocky world,
    0.20×–0.60× for a giant — and it rides on `BodyProfile`; tidal locking overrides it at
    realization. It was `(1/g)³ · DEFAULT` in BOTH the realizer and the legacy random generator: a
    fabricated law making spin a function of surface gravity, which does not bear on it. `[T]`
- **MECH-NAV-04 — temperature & brightness.** `getAverageTemperature` (grey body over the summed
  flux, albedo from the world's type, pressure greenhouse factor), `getStellarBrightness`
  (floored at `1e-9`, never NaN/∞), `getPlanetaryLightLevelMultiplier` (perceptual log curve).
  `AstronomicalBodyHelper.java:98-167`.
  - **Brightness sums the FLUX of every star in the system**: `Σ size²·(T/Sol)⁴ / dist²` over the
    primary and its `subStars`, each black hole ×0.25. Flux from incoherent sources adds, which is
    why the sum is over flux terms and not over luminosities — today every companion is given the
    primary's distance, because a companion's separation is stored as a sky ANGLE and it has none of
    its own. The companion walk sums every companion's flux; an ordinary companion does not
    switch the accretion-disc dimming off. `[T]`
  - **Temperature is the grey body written over that flux**, not a second copy of the arithmetic:
    `T = T₀ · (E·(1−a))^¼` with `T₀ = T☉·sqrt(R☉/2AU)` DERIVED from the constants above. Algebraically
    identical to the per-star form it replaced — expand `E` for one star and the radii and temperatures
    cancel — so no world's temperature moved, and a binary is warmed by both stars with no second code
    path. The pressure greenhouse factor is unchanged and still the acknowledged kludge.
  - **Albedo comes from the planet's TYPE** (`PlanetTypePreset.albedo()`, defaulting to
    `EARTH_ALBEDO = 0.3`), applied through `DimensionProperties.getAlbedo()`. It was hard-coded at 0.3
    for every surface, so an ice world and a lava world at one distance were the same temperature.
    Self-closing: high albedo → colder → the ice stays ice. `[T]`
  - **Zoning still selects a type using Earth's albedo**, because the type is what states the albedo —
    `PlanetDerivation.derive` computes the temperature that draws the type before a type exists. The
    realized world then uses its type's own value, which only ever moves it further into that type.
- **MECH-NAV-05 — gravity application.** `applyGravity(entity)` skips no-gravity and
  elytra/creative-flying entities; if a custom multiplier is registered in the handler's own
  `WeakHashMap` it adds `OFFSET·d` to `motionY`, else derives `gravitationalMultiplier`
  from the `IPlanetaryProvider`/`DimensionProperties` and subtracts a per-entity-class
  offset (living/fluid/throwable/arrow/other). Falls back to a reflected Galacticraft
  `getGravity()` when neither applies. `GravityHandler.java:47-94`.
- **MECH-NAV-06 — within-system predicates.** `isTravelBetweenBodiesWithinPlanetarySystem`
  (moon→parent/sibling or planet→moon), `isTravelWithinOrbit` (same dim),
  `isTravelAnywhereInPlanetarySystem` (union), `isTravelWithinGeostationaryOrbit`
  (same parent planet & station orbital dist ≥ 177). `PlanetaryTravelHelper.java:14-111`.
- **MECH-NAV-07 — injection burn length.** `getTransbodyInjectionBurn` = base
  `transBodyInjection` × √(body-distance-multiplier) for intra-system trips, else
  `warpTBIBurnMult × base`; asteroid trips use `asteroidTBIBurnMult`. Between a planet and its
  moon the multiplier is `moonViewUnits / 100` — Luna 1.5
  (`moonBurnMultiplier`, `:80-91`). `[T]`
  `PlanetaryTravelHelperTest#aBurnBetweenAPlanetAndItsMoonReadsTheMoonAsLunaWasReadAt150`.
  `PlanetaryTravelHelper.java:47-91`.
- **MECH-NAV-08 — free-flight transitions.** `SpaceTravelInteraction.tick` snaps an entity
  into a planet's local frame when it drifts within √200 of a planet's space position, and
  back out to solar navigation past `√(40000·8)`; entry/exit predicates mirror this.
  `SpaceTravelInteraction.java:9-72`.
- **MECH-NAV-09 — moving-structure collision.** `MobileAABB.calculateYOffset` resolves
  vertical collision by sampling the backing `StorageChunk` block collision boxes
  (with a client/server 1-block offset fudge); `intersects` brute-forces every solid
  block in the chunk. `MobileAABB.java:188-299`.

## State & persistence

`SpacePosition` and `DimensionBlockPosition`/`StationLandingLocation` are the only NBT/
value carriers here (SpacePosition round-trips `spacePosition`; the others are transient
keys). `GravityHandler` keeps an INSTANCE `WeakHashMap<Entity,Double>` of custom multipliers
(auto-evicted; not a static — every handler holds its own, and the mod installs one,
`GravityHandler.java:47`, `Stellurgy.java:1440`). All astro/travel math is stateless.

## Invariants

- **INV-NAV-01 [T]** SpacePosition survives NBT round-trip (populated and default); a read
  with no `spacePosition` tag leaves the object unchanged. `test/unit/SpacePositionTest.java:25,53,68`.
- **INV-NAV-02 [T]** `distanceToSpacePosition2` equals the Euclidean definition and is
  symmetric; `getNormalVectorTo` is unit-length and points at the target; `getFromSpherical`
  lands at the requested radius/axis and carries context. `test/unit/SpacePositionTest.java:78,91,101,115,131,144,153`.
- **INV-NAV-03 [T]** Orbital period grows with distance and matches the baseline at Earth
  distance; brightness is monotone in distance, equals 1 at Earth baseline, and is reduced
  for black holes. `test/unit/AstronomicalBodyHelperTest.java:41,47,63,74,81`; orbital
  theta pinned by `test/integration/AstronomicalBodyHelperOrbitalThetaTest.java`.
- **INV-NAV-04 [V]** `getStellarBrightness` never returns 0, NaN or ∞ (floored to `1e-9`).
  `AstronomicalBodyHelper.java:120,149-153`.
- **INV-NAV-07 [T]** Every star lights the world: two identical stars give exactly twice one star's
  brightness, and a black hole with an ordinary companion is lit by both on their own terms
  (`0.25·L_hole + L_companion`) — never as though the hole had stopped being one.
  `test/unit/AstronomicalBodyHelperTest.java:everyStarInASystemContributesItsOwnLight,
  aCompanionDoesNotTurnABlackHoleBackIntoAStar`.
- **INV-NAV-08 [T]** A moon's period follows its parent's mass, not its surface gravity: with a
  Jupiter parent the two readings are 11× apart, and the moon returns to its start after one
  mass-derived period and is on the far side after half of one.
  `test/integration/SystemContentTest.java:aMoonsPeriodFollowsItsParentsMassNotItsSurfaceGravity`.
- **INV-NAV-09 [T]** A planet's year follows its star's mass: a star of 2 R☉ masses more than 2 M☉,
  so keying the year on mass gives a shorter year than substituting the radius; a stated mass beats
  the derivation. `test/unit/AstronomicalBodyHelperTest.java:aYearIsKeyedOnStellarMassAndAStarWithoutOneDerivesItFromItsRadius`.
- **INV-NAV-10 [T]** A darker world runs hotter and a more reflective one colder, and the
  albedo-less call still means Earth's albedo.
  `test/unit/AstronomicalBodyHelperTest.java:albedoCoolsAWorldAndTheDefaultIsEarths`.
- **INV-NAV-11 [T]** A day is drawn, not computed from gravity: two worlds of equal gravity can have
  different days (impossible under any function of gravity alone), the draw stays in band, and the
  same body answers the same day twice.
  `test/unit/PlanetDerivationTest.java:aDayIsDrawnAndIsNotAFunctionOfGravity,aDrawnDayIsStillDeterministic`.
- **INV-NAV-05 [T]** Travel predicates: planet↔own-moon, moon↔parent, moon↔sibling are
  within-system; unrelated planets are not; same-dim is within-orbit; the "anywhere" form
  is the union. `test/unit/PlanetaryTravelHelperTest.java:138,147,156,166,178,188`.
- **INV-NAV-06 [T]** Injection burn is positive for intra-system travel and falls back to
  the warp multiplier for cross-system. `test/unit/PlanetaryTravelHelperTest.java:206,223`.
- **INV-NAV-07 [V]** The mod installs one `GravityHandler` as the `StellurgyAPI.gravityManager`
  service (`Stellurgy.java:1440`); each handler's overrides are its own — **[T]**
  `test/unit/GravityHandlerApiTest.java:44` (`twoHandlersDoNotShareTheirOverrides`). The set/clear
  round-trip is not separately pinned.

## Failure modes & edge cases

- `SpacePosition.readFromNBT` writes `pitch`/`roll` **back into** the sub-tag it
  is reading from (`subTag.setDouble(...)` at lines 56-57) — dead writes with no effect;
  harmless but indicates copy-paste rot.
- `MobileAABB`'s `calculateXOffset`/`calculateZOffset` are commented out, so only
  vertical collision against a moving `StorageChunk` is resolved — horizontal collision is
  unhandled, and `intersects` is O(sizeX·sizeY·sizeZ) per call. `MobileAABB.java:32-186, 264-299`.
- `GravityHandler.setGravityMultiplier` has a `//TODO: packet handling` note and does not
  sync the custom multiplier to clients. `GravityHandler.java:101-104`.

## Integration seams

Interfaces/caps: `IGravityManager` registered into `StellurgyAPI` (C5);
reads `IPlanetaryProvider`/`WorldProviderSpace` (api-public/world-gen). Reflection bridge
to Galacticraft `IGalacticraftWorldProvider` (integration-modcompat). `MobileAABB` binds
`StorageChunk` (rocket-assembly). No packets owned here.

## Config surface

Config: see `C4-config-surface`. No full-disable flag — the travel balance terms are pure math.

## Test coverage

`SpacePositionTest`, `AstronomicalBodyHelperTest`(+OrbitalTheta integration),
`PlanetaryTravelHelperTest`, `GravityHandlerApiTest`. `MobileAABB` and
`SpaceTravelInteraction` are untested.

## Open questions

- No test exercises `MobileAABB` collision or `SpaceTravelInteraction` transitions; the
  disabled horizontal-collision axes are the main risk.
