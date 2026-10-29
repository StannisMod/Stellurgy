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

**MECH-CLR-32 — Every sky draws a body as a sphere at the ANGLE it truly subtends.** One law,
`ApparentSize.halfAngleFor` = `asin(r / d)`, with one departure: a floor of
`MARKER_DIAMETER_PIXELS` (2) screen pixels, read each frame from the player's vertical field of view over
the frame's height (`SkySphere.markerHalfAngle`). The universe's distances are real (a distance unit is
100 km, `AstronomicalBodyHelper`), so the Sun and Luna seen from Earth are each about half a degree across —
some ten pixels — and are drawn at that truth. Every sky draws with `SkySphere`: a camera-facing hemisphere
with the body's picture projected onto it; a ring and an accretion disk are flat discs; a star also carries
short rays in its own colour, at least six pixels long, so it is told from a planet at any size
(`SkySphere.emitRays`). No body is drawn as a quad. By sky:

- the cell sky: `BoundarySky.drawBody`, from the fed radius and distance (C14 CON-C14-16);
- a planet's and an asteroid's sky: a moon, or the planet seen from its moon, from its radius over its
  moon-orbit distance in chart blocks; the local star from its radius over the world's solar distance
  (`RenderPlanetarySky.renderPlanet`, `starHalfAngle`, `drawStarSphere`; `RenderAsteroidSky`); the world
  below a high flyer from its radius over the radius plus the height above the horizon, a metre a block
  (`RenderPlanetarySky.renderplanetbelow`);
- a station's sky: the world below from its radius over the radius plus the station's altitude, the one
  reading of that altitude the altitude controller also shows (`AstronomicalBodyHelper.stationAltitudeKm`,
  `RenderSpaceSky.renderPlanet2`);
- the travel sky: bodies stand at their `SpacePosition`s in a 3D scene, so a sphere there subtends its
  scene angle by construction (`RenderSpaceTravelSky.renderStar`).

A world with no stated radius is not drawn at a size invented for it: the sky logs it once and draws
nothing (`RenderPlanetarySky.reportSizeless`). `[T]` the law: `ApparentSizeTest`; the cell sky in pixels:
`BoundarySkyRendersInSlotCellTest`; the other skies are unpinned (GL).

**MECH-CLR-33 — RETIRED.** Its two size laws — by orbital distance (`getBodySizeMultiplier`) and by
height (`getSizeMultiplierAtHeight`), each multiplying a picked base size — and the moon-view scale beside
it are no longer what any sky draws with; MECH-CLR-32's true angle replaced all three.

**MECH-CLR-19 — Warp / space-dimension detection.** When the dimension id equals config
`spaceDimId`, the renderer checks `properties.getParentPlanet() == SpaceObjectManager.WARPDIMID`
and, if warping, reads the station's forward direction to orient travel visuals. `RenderPlanetarySky.java:554-560,601-607`.
C4 (`spaceDimId`), edge into space-stations.

**MECH-CLR-20 — Space-station sky.** `RenderSpaceSky.renderPlanet2` renders the world below the
station as a sphere at the angle the station's altitude gives, with its atmosphere and cloud bands as
shells over it, and a station round a star draws that star the same way (MECH-CLR-32). `renderplanetbelow`
in the base draws the world below a high flyer.

**MECH-CLR-21 — Interplanetary travel sky.** `RenderSpaceTravelSky.render` places each reachable
planet (`renderPlanet(props, SpacePosition, playerPosition, sizeOverride)`) and star
(`renderStar(StellarBody, …)`) at its real relative `SpacePosition`, so travelling between planets
shows them shrink/grow with distance. `RenderSpaceTravelSky.java:226-321,735`. The map is
`DimensionProperties.getSpacePosition`'s — a planet at 10 000 map units per AU
(`DimensionProperties.java:2894-2912`, [T] `test/integration/DimensionPropertiesTest.java`
`theFreeFlightMapLaysAPlanetOutTenThousandUnitsPerAu`), a moon at `75 × moonViewUnits + 100` about its
planet, so Luna stands where she stood at 150 (`DimensionProperties.java:2894-2914`, [T]
`theFreeFlightMapLaysAMoonOutAsItDidWhenLunaStoodAt150`; the rocket's moon capture and landing compare
against this position, `EntityRocket.java:2022`) — and a companion star is laid `4 000` per AU off its
primary; the local sun is drawn as every sky draws it (MECH-CLR-32). Only under
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
- **INV-CLR-16 [V]** `RenderSpaceSky`/`RenderSpaceTravelSky` are chosen by identity in
  `WorldProviderSpace.getSkyRenderer` (`instanceof` swap); this subsystem assumes that provider
  logic keeps them mutually exclusive per world state. `world/provider/WorldProviderSpace.java:64-72`. `WorldProviderSpace.java:55-75` returns exactly one renderer per call.

## Failure modes & edge cases

- The scratch fields (`xrotangle`/`skycolor`/`currentplanetphi`) are per-renderer instance fields (not
  statics shared across skies); the ring/disk helper receives the caller's angles as
  parameters, which is what prevents a cross-sky leak (`RenderPlanetarySky.java:112`).
- Sky renderers dereference `mc.player`/`SpaceObjectManager` results; null runs are absorbed
  upstream (`ClientProxy.calculateCelestialAngleSpaceStation`) or by the provider fallback branch.

## Test coverage

No automated coverage of the drawing — GL sky rendering is not unit-testable in the headless
harness. The size LAW it applies is unit-pinned (MECH-CLR-32, `ApparentSizeTest`); geometry inputs are covered by
dimension-planets tests.

## Open questions

- Whether `RocketRenderHelper.renderOrbit(x,y,z,xR,yR,phase)` dropping `phase` (delegates with
  `0,0`) is intentional. `RocketRenderHelper.java:6-8`.
