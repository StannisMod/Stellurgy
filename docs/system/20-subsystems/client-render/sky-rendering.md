---
id: client-render/sky-rendering
parent: client-render
files: [client/render/planet/RenderPlanetarySky.java, client/render/planet/RenderSpaceSky.java, client/render/planet/RenderSpaceTravelSky.java, client/render/planet/RenderAsteroidSky.java, client/render/RocketRenderHelper.java]
sloc: ~3600
contracts: [C4, C7]
confidence: high
---

## Purpose

Four `IRenderHandler` sky renderers that replace the vanilla skybox on Stellurgy dimensions, painting
stars, the local star(s), the parent/child planets, rings, and (in space) the interplanetary
starfield and warp effects — all driven by `DimensionProperties` orbital geometry. This subsystem
owns the renderers; the `WorldProvider`s that install them are world-gen.

## Key types

| class | role |
|-------|------|
| `RenderPlanetarySky` | base sky for a planet surface; stars via GL display lists, sun, moons, rings, black-hole tint |
| `RenderSpaceSky` | `extends RenderPlanetarySky`; sky seen from a space station (adds `renderPlanet2` with shadow/ring) |
| `RenderSpaceTravelSky` | `extends RenderPlanetarySky`; interplanetary-travel view — planets & stars at `SpacePosition`s |
| `RenderAsteroidSky` | asteroid-belt sky (own `render` + `renderSphere`) |
| `RocketRenderHelper` | GL immediate-mode helpers to draw an orbit ellipse / a body's position on it |

## Installation (edge into world-gen)

The renderers are constructed by the world providers, not here:
`WorldProviderPlanet.getSkyRenderer` → `new RenderPlanetarySky()`;
`WorldProviderSpace` → `RenderSpaceTravelSky` (in transit) or `RenderSpaceSky` (station);
`WorldProviderAsteroid` → `RenderAsteroidSky`; and `PlanetEventHandler` re-installs
`RenderPlanetarySky` on world load. (`world/provider/*`, `event/PlanetEventHandler.java:386` — not
owned here, cited for the seam.)

## Mechanics

**MECH-CLR-17 — Star display-list precompile.** The `RenderPlanetarySky` constructor allocates 4
GL display lists via `GLAllocation.generateDisplayLists(4)` and compiles the random starfield
(`renderStars`, `renderStarsSmall`) plus two sky quads once, reused every frame. `RenderPlanetarySky.java:53-83`.

**MECH-CLR-18 — Planetary sky render.** `render(partialTicks, world, mc)` selects the property
source in three branches — `IPlanetaryProvider` (per-position props), a created dimension
(`DimensionManager.getDimensionProperties`), else the overworld fallback — then reads orbit angles
(`orbitalPhi/orbitTheta/rotationalPhi/prevOrbitalTheta`), rings, ring colour, child planets, solar
distance, moon parent, star, and sun colour. It paints the sky colour, rotates by the celestial
angle about a per-position rotation axis (`getRotationAxis`), and draws stars, sun, moons, rings,
and (for a black-hole star) a screen tint. `RenderPlanetarySky.java:485-624,1018-1147`. Balance sizes/
angles `tunable`; the geometry contract is `DimensionProperties` (dimension-planets).

**MECH-CLR-32 — A body is drawn at the ANGLE it subtends, not at its distance.** Both sky families size a body from its own radius over the distance to it:

- the cell sky feeds `ApparentSize.halfSizeFor(radiusBlocks, distance)` — the RATIO is compressed
  logarithmically and clamped to `[MIN_HALF_SIZE, MAX_HALF_SIZE]`, so a giant outdraws a moon beside
  it while a star still does not collapse to a pixel `ApparentSize.java`, `BoundarySky.java:431`;
- the planet-surface skies pass the drawn body's radius (not `gravitationalMultiplier^0.4`) — so a one-Earth-radius body draws
  at the scale constant `RenderPlanetarySky.java:1104`, `RenderAsteroidSky.java`.

The radius reaches the client on the render channel (`RenderBody.radiusBlocks`, C2) because nothing
downstream can recover it: a procedural world has no dimension to read one from until a descent mints
it. A body with no radius of its own — a belt, a station slot — sends zero and is drawn at the marker
size rather than at a guessed one. `[T]` `ApparentSizeTest`, `BoundarySkyRendersInSlotCellTest`.

**MECH-CLR-33 — Two size laws: by orbital distance, and by HEIGHT.** A planet-surface sky sizes a
body it sees across an orbit with `AstronomicalBodyHelper.getBodySizeMultiplier(d) =
DISTANCE_UNITS_PER_AU / d` (`AstronomicalBodyHelper.java:246`) — 1 at one AU, for the local sun at
the world's solar distance. The low-orbit views size a body by a height that is NOT a distance unit —
a station's altitude and the fixed `190` a station sky puts its sun at (`RenderSpaceSky.java:50-217`),
and the player's height above the horizon over ten for the planet-below quad
(`RenderPlanetarySky.java:1055`) — through `getSizeMultiplierAtHeight(h) = 100 / h`
(`AstronomicalBodyHelper.java:264`). The two are separate calls: sharing the numerator of 1 495 979 (the 100 km distance unit) would make every low-orbit body 14 960 times too large.
[T] the height law: `test/unit/AstronomicalBodyHelperTest.java`
(`aLowOrbitViewSizesABodyByHeightNotByTheDistanceUnit`); that the renderers call it is unpinned (GL).

**A MOON is sized on the moon-view scale.** The moons a planet's sky draws, and the parent a moon's
sky draws (`RenderPlanetarySky.java:906,941`, `RenderAsteroidSky.java:575,601`, through
`:1112`/`:693`), are sized by `getMoonSizeMultiplier(d) = 100 / moonViewUnits(d)`
(`AstronomicalBodyHelper.java:293-304`), where `moonViewUnits(d) = 150 × d / MOON_REFERENCE_UNITS`
(`:256-266`) — so Luna draws at 100 / 150, exactly as she was drawn at her old 150 distance, and any other moon inversely with its own distance (maintainer ruling "Вариант 1",
2026-09-30). Through the planet law Luna read 389 and covered the sky. The 150 is a VIEW scale, one
reference for every moon-level view, and nothing physical reads it. [T] the law:
`test/unit/AstronomicalBodyHelperTest.java` (`theSkyDrawsLunaAtTheSizeItDrewHerWhenSheStoodAt150`,
`aMoonAtHalfLunasDistanceDrawsTwiceHerSize`); that the renderers call it is unpinned (GL).

**MECH-CLR-19 — Warp / space-dimension detection.** When the dimension id equals config
`spaceDimId`, the renderer checks `properties.getParentPlanet() == SpaceObjectManager.WARPDIMID`
and, if warping, reads the station's forward direction to orient travel visuals. `RenderPlanetarySky.java:554-560,601-607`.
C4 (`spaceDimId`), edge into space-stations.

**MECH-CLR-20 — Space-station sky.** `RenderSpaceSky.renderPlanet2` renders the planet below the
station with a shadow angle, optional ring, and shadow/alpha colour multipliers — the station's
view of its parent body. `RenderSpaceSky.java:43`. `renderplanetbelow` in the base handles the
planet-below quad. `RenderPlanetarySky.java:1018`.

**MECH-CLR-21 — Interplanetary travel sky.** `RenderSpaceTravelSky.render` places each reachable
planet (`renderPlanet(props, SpacePosition, playerPosition, sizeOverride)`) and star
(`renderStar(StellarBody, …)`) at its real relative `SpacePosition`, so travelling between planets
shows them shrink/grow with distance. `RenderSpaceTravelSky.java:226-321,735`. The map is
`DimensionProperties.getSpacePosition`'s — a planet at 10 000 map units per AU
(`DimensionProperties.java:2894-2912`, [T] `test/integration/DimensionPropertiesTest.java`
`theFreeFlightMapLaysAPlanetOutTenThousandUnitsPerAu`), a moon at `75 × moonViewUnits + 100` about its
planet, so Luna stands where she stood at 150 (`DimensionProperties.java:2894-2914`, [T]
`theFreeFlightMapLaysAMoonOutAsItDidWhenLunaStoodAt150`; the rocket's moon capture and landing compare
against this position, `EntityRocket.java:2022`) — and a companion star is laid `4 000` per AU off its primary; the sun is drawn at
`sunScaleAt(d) = 2.02 − d/AU`, vanishing at 2.02 AU (`RenderSpaceTravelSky.java:886-888`). Only under
`experimentalSpaceFlight`. `StellarBody.getSpacePosition` lays a companion by the SAME
`AstronomicalBodyHelper.SPACE_MAP_UNITS_PER_AU` (10 000) a planet uses — not a private 100 `[T]` `DimensionPropertiesTest#aCompanionAndAPlanetAtOneDistanceLandAtOneRadiusOnTheMap`.
The renderer's own `COMPANION_MAP_UNITS_PER_AU` (4 000, drawn under a ×4 GL scale) is a separate
placement and was not reconciled [V].

**MECH-CLR-22 — Asteroid sky.** `RenderAsteroidSky.render` draws the asteroid-belt sky and bodies
(`renderSphere`), branching on `spaceDimId`. `RenderAsteroidSky.java:227-343,709`.

## State & persistence

No NBT and no config *owned* here. Reads: `spaceDimId` (C4). All render state is derived per-frame
from `DimensionProperties`; the only long-lived state is the GL display-list ids and three scratch fields
(`xrotangle`, `skycolor`, `currentplanetphi`) thread ring/black-hole params through
vanilla-shaped method signatures — INSTANCE fields of each sky renderer (never statics), one set per renderer: `RenderPlanetarySky.java:46-48`, `RenderAsteroidSky.java:58-60`.
The ring/disk draw takes its angles as parameters (`ringPhiDeg` / `ringXRotDeg`) and each caller passes
its OWN (`RenderPlanetarySky.java:112`, `RenderAsteroidSky.java:710`), so an asteroid-field sky never
rotates a ring by the last planetary sky's angles.

## Integration seams

- **← dimension-planets**: `DimensionProperties`, `DimensionManager`, `StellarBody`, `SpacePosition`,
  `IDimensionProperties`, `IPlanetaryProvider`.
- **← space-stations**: `SpaceObjectManager.getSpaceStationFromBlockCoords`, `WARPDIMID`,
  `SpaceStationObject.getForwardDirection`.
- **← api-public**: `StellurgyConfiguration.spaceDimId`.
- **← world-gen** (reverse): providers install these renderers (see above).
- **C7 assets**: star/planet/ring textures bound as `ResourceLocation`s inside the renderers.

## Invariants

- **INV-CLR-13 [V]** The starfield and sky quads are compiled to GL display lists once in the
  constructor and reused — not rebuilt per frame. `RenderPlanetarySky.java:53-83`.
- **INV-CLR-14 [V][BEH]** Sky rendering never assumes a created dimension: absent
  `DimensionProperties` it falls back to the connection galaxy's `getOverworldProperties()`. `RenderPlanetarySky.java:608-616`.
- **INV-CLR-15 [V][BEH]** Warp visuals are gated on both `spaceDimId` **and** parent == `WARPDIMID`, so
  a normal space station does not render travel motion. `RenderPlanetarySky.java:554-560`.
- **INV-CLR-16 [A]** `RenderSpaceSky`/`RenderSpaceTravelSky` are chosen by identity in
  `WorldProviderSpace.getSkyRenderer` (`instanceof` swap); this subsystem assumes that provider
  logic keeps them mutually exclusive per world state. `world/provider/WorldProviderSpace.java:64-72`.

## Failure modes & edge cases

- The scratch fields (`xrotangle`/`skycolor`/`currentplanetphi`) are per-renderer instance fields (not
  statics shared across skies); the ring/disk helper receives the caller's angles as
  parameters, which is what prevents a cross-sky leak (`RenderPlanetarySky.java:112`).
- Sky renderers dereference `mc.player`/`SpaceObjectManager` results; null runs are absorbed
  upstream (`ClientProxy.calculateCelestialAngleSpaceStation`) or by the provider fallback branch.

## Test coverage

No automated coverage of the drawing — GL sky rendering is not unit-testable in the headless
harness. The size LAWS it applies are unit-pinned (MECH-CLR-33); geometry inputs are covered by
dimension-planets tests.

## Open questions

- Whether `RocketRenderHelper.renderOrbit(x,y,z,xR,yR,phase)` dropping `phase` (delegates with
  `0,0`) is intentional. `RocketRenderHelper.java:6-8`.
