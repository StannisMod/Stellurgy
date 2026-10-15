# rocket-entity — Classic flight

Parent: [00-overview.md](./00-overview.md). Covers the default `CLASSIC_LAUNCH`
state machine in `EntityRocket` plus `TileGuidanceComputer`. Free-flight is a
separate branch that short-circuits everything here — see [free-flight.md](./free-flight.md).

State lives in three places: replicated `DataParameter`s (`INFLIGHT`, `INORBIT`,
`INSPACEFLIGHT`, `RCS_MODE`, `LAUNCH_COUNTER`, four fuel levels), plain server
fields mirrored into them, and NBT. `setInFlight/setInOrbit` write both the field
and the datamanager (`EntityRocket.java:853`, `:1244`).

## Mechanics

### MECH-RKT-01 — Launch countdown, gating & abort
`prepareLaunch()` is the single entry (Space key → `PacketType.LAUNCH`, or a
monitoring station). If a countdown is already running it aborts (sets
`LAUNCH_COUNTER=-1`, sends `ABORTLAUNCH`) `EntityRocket.java:2555`. Otherwise it
posts `RocketPreLaunchEvent`; if not cancelled, sets `LAUNCH_COUNTER=200`
`:2596`. `onUpdate` counts down and calls `launch()` at 0 `:1928`. `launch()`
(server only, `:2642`) recalculates stats, applies the optional mass / parts-wear
gates (worn seat refuses a crewed launch, wear failure probability can block or
explode the rocket, worn tanks leak fuel `:2673`–`:2713`), resolves the
destination through the guidance computer, and rejects with `setError` if the
target is unreachable `:2723`.

The TWR refusal (`error.rocket.tooHeavy`) calls `stats.canLaunch(g)` with the CURRENT
dimension's `gravitationalMultiplier` (`EntityRocket.java:2845-2849`, C10 STAT-26/29). A no-arg `canLaunch()` would divide by the weight
alone and give the same verdict on every world. `hasMissionFuelFor`'s own pre-check went the same
way: `getThrustToWeightRatio(gSrc) <= 1` (`:1400`) instead of `thrust <= weight` — that check is
the fuel mechanic's "cannot lift itself here", not the `minLaunchTWR` gate.

### MECH-RKT-02 — Ascent burn & fuel consumption
While `isInFlight()` and not in the space branch, each server tick: if
`isBurningFuel()` drains `getFuelConsumptionRate` from the active fuel type (plus
oxidizer for bipropellant), nulling the fluid name when a tank empties
`EntityRocket.java:2096`–`2108`; accelerates `motionY` by
`stats.getAcceleration(gravMult) * deltaTime` `:2126`. Out of fuel or in orbit,
`motionY` instead decays under gravity (`-0.1·9.81/20·gravMult`, floored at −2)
`:2121`. `deltaTime` = world-time delta since last tick `:1745` (catches up after
lag/unload). `isBurningFuel()` also requires either a forward-moving passenger or
not-yet-in-orbit `:1461`.

### MECH-RKT-03 — Orbit-reached dispatch
`onUpdate` calls `onOrbitReached()` once `posY > stats.orbitHeight` and not already
in orbit `EntityRocket.java:2158`. Dispatch `:2258`: a satellite-chip target loads
the satellite into a hatch then dives (`motionY=-2`, orbit=true); else no-seat →
`reachSpaceUnmanned` (asteroid chip spawns a `MissionOreMining` and kills the
entity, otherwise unpacks satellites and warps to the destination dimension); else
crewed → `reachSpaceManned`. Destination + entry height come from
`getEntryHeight` (`stationClearanceHeight` for the space dim, else `orbit`) `:2284`.
The height a flight
climbs to is `flightOrbitHeight()` — the rocket's own `stats.orbitHeight` when set, else
`getEntryHeight`, which answers the world's transfer line
(`DimensionManager.transferLineOf`) and, for a world with none, the top of the block band, reported
once (`EntityRocket.java:2308-2322`; readers `:1405`, `:2177`).

### MECH-RKT-04 — Descent phase & auto-retro landing detection
`isDescentPhase()` is a stepped altitude/velocity ladder, gated on
`automaticRetroRockets` and `isInOrbit()` `EntityRocket.java:1486`; on the client
every rung is force-true so the engine plume renders. In descent, `motionY` is bled
toward zero (`-motionY/120`) `:2115`. Landing = `(inOrbit || !burningFuel) &&
inFlight && (movedLessThanExpected || asteroid-below-64)`: posts
`RocketLandedEvent`, zeros motion, clears flight/orbit, releases the destination
preload `:2145`. A separate on-load path posts `RocketLandedEvent` after 5 ticks if
the rocket is neither in flight nor orbit `:1747`.

### MECH-RKT-05 — Cross-dimension transfer on orbit
Reaching a different, reachable dimension calls `changeDimension(dim, x, entryY, z)`
`EntityRocket.java:2370`, a custom teleporter that re-writes the entity into the
target world and re-mounts passengers via a `FORCEMOUNT` packet (client race
workaround) `:3294`. A preload `ForgeChunkManager` ticket for the destination is
grabbed on the launch event and auto-released after a timeout or on land/abort
`:1945`. If a rocket in orbit falls below y=0 in the space dim it re-enters its
orbiting planet (or `lastDimensionFrom`), else dies `:2164`.

### MECH-RKT-06 — Interplanetary solar-map navigation
When `getInSpaceFlight()` (legacy manned deep-space mode), `onUpdate` integrates a
separate `SpacePosition` (star-relative), steers by yaw + passenger forward input,
and applies `0.98` drag when idle `EntityRocket.java:1951`–`1972`. Proximity checks
capture the craft into a planet's local frame, then landing on a planet / moon /
station sets `destinationDimId`, calls `reachSpaceManned`, and clears space flight
`:1996`–`2064`. Position syncs to the server via the `SENDSPACEPOS` sub-packet
every 20 ticks `:2085`.

### MECH-RKT-07 — RCS deprecation shim
`toggleRCS()` (via `TOGGLE_RCS`) is now a no-op that only messages the pilot
(`msg.entity.rocket.rcsDeprecated`) — deliberately **not** `setError`, which would
carry launch-abort semantics `EntityRocket.java:435`. The `RCS_MODE` DataParameter,
`rcs_mode`/`rcs_mode_counter` fields and the seat-rotation animation
(`getRCSRotateProgress`) remain for legacy saves and solar-map flight; `rcs_mode`
is force-on whenever `getInSpaceFlight()` `:3002`.

### MECH-RKT-08 — Guidance-computer destination & landing resolution
`TileGuidanceComputer` (single inventory slot) resolves the trip from the inserted
chip. `getDestinationDimId` branches by item: planet-chip → its stored dim;
station-chip → the station's orbiting planet or the space dim; asteroid-chip →
current dim (side-effect: sets `landingPos`); satellite-chip → the satellite's dim;
Linker → its stored dim `TileGuidanceComputer.java:133`. `getLandingLocation`
mirrors that tree to produce coordinates, allocating a station landing pad and
optionally committing it as occupied `:182`, `:226`. `getLaunchSequence` /
`getTransBodyInjection` compute burn time via `PlanetaryTravelHelper` `:270`.
Per-station pad choices persist in `landingLoc` (NBT `stationMapping`).

### MECH-RKT-09 — Infrastructure linking & hand-fueling interact
Right-clicking with a Linker bound to an `IInfrastructure` within range links it
(`linkInfrastructure`, coords tracked in `infrastructureCoords`, re-linked at tick 20)
`EntityRocket.java:1291`, `:1792`. Right-clicking with a fluid item, when
`canBeFueledByHand`, fills the matching fuel type `:1333`. Sneak (or a seatless,
non-linking click) opens the modular GUI; a plain click on a seated rocket mounts
the pilot `:1355`.

## State & persistence

Owned NBT keys written in `writeNetworkableNBT` / read in `readEntityFromNBT`
(`EntityRocket.java:2998`, `:3082`) — full table in [persistence-wire.md](./persistence-wire.md).
Classic-relevant keys: `orbit`, `flight`, `inSpaceFlight`, `rcs_mode`,
`rcs_mode_cnt`, `motionX/Y/Z`, `destinationDimId`, `lastDimensionFrom`,
`infrastructure` (list of `loc` int[3]), `satallite` [sic], `data` (StorageChunk),
plus `SpacePosition` (`x/y/z`+world/star) and `StatsRocket` keys. `TileGuidanceComputer`
persists `destDimId`, `landingx/y/z`, `stationMapping` (list of `pos` int[3] + `id`).

## Invariants

- **INV-RKT-01 [V][BEH]** A countdown launch fires `launch()` exactly once, at
  `LAUNCH_COUNTER==0` `EntityRocket.java:1929`; `launch()` re-guards
  `if (isInFlight()) return` `:2646`.
- **INV-RKT-02 [V]** Fuel burn and downward gravity accrual run server-side only
  (`!world.isRemote`) — the client re-integrates `motionY` cosmetically
  `EntityRocket.java:2112`, `:2193`.
- **INV-RKT-03 [V][BEH]** An unreachable destination aborts the launch with
  `setError("error.rocket.cannotGetThere")` before flight begins
  `EntityRocket.java:2723`.
- **INV-RKT-04 [A][SYS]** `RocketFlightMode.DEFAULT == CLASSIC_LAUNCH`, so a legacy
  save with no `flightMode` key loads onto this path `RocketFlightMode.java:20`;
  pinned by `RocketFlightModeNbtTest` (missing-key → DEFAULT)
  `RocketFlightModeNbtTest.java:30`. Pinned by `RocketFlightModeNbtTest#defaultIsClassicLaunch`, `RocketFlightModeNbtTest#missingNbtKeyReadsDefault`. FOR: save format: flightMode key.
- **INV-RKT-05 [V][BEH]** `toggleRCS` never mutates `RCS_MODE` or launch state — it only
  messages `EntityRocket.java:441`; a deprecation notice cannot abort a launch.
- **INV-RKT-06 [A][BEH]** `deltaTime`-scaled acceleration assumes `getAcceleration`
  is a per-tick value; after a multi-tick stall the single catch-up step can
  overshoot altitude. Inferred from `:1745`/`:2126`; not test-pinned.

## Failure modes & edge cases

- Asteroid landing uses `posY < 64` as ground; combined with the "moved less than
  expected" check `:2143`, a rocket that clips terrain mid-ascent could read as
  landed. Guarded by `isInOrbit() || !burningFuel`.
- `getTransBodyInjection(cur,dst,pos)` dereferences `currentSpaceStation` without a
  null check `TileGuidanceComputer.java:296` — NPE if called with a block pos not
  inside a station.
- Falling below y=0 outside the space dim silently `setDead()`s the rocket (and any
  passenger dismount is implicit) `EntityRocket.java:2191`.

## Integration seams

Events posted: `RocketPreLaunchEvent`, `RocketLaunchEvent`, `RocketReachesOrbitEvent`,
`RocketDeOrbitingEvent`, `RocketLandedEvent` (C5). Sub-packets used:
`LAUNCH`, `ABORTLAUNCH`, `TURNUPDATE`, `SENDSPACEPOS`, `SENDPLANETDATA`,
`ROCKETLANDEVENT`, `FORCEMOUNT`, `DECONSTRUCT`. `PacketBackToRocketGui` (guidance
computer → rocket GUI back-button) is a standalone registered packet
`TileGuidanceComputer.java:72`.

## Config surface

Config: see `C4-config-surface`. Values are `tunable`. Disabling `rocketRequireFuel` makes every fuel gate pass.

## Test coverage

- INV-RKT-04 → `RocketFlightModeNbtTest.java:30,50,58`.
- Launch-gate behaviour (classic reject of `start-free-flight`) →
  `FreeFlightCycleTest.java:167`.
