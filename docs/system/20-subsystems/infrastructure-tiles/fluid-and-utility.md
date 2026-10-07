# infrastructure-tiles / fluid & utility tiles

Standalone tiles that are *not* rocket-linked: a fluid pump, a gravity-fed pressure-tank
column, an RF solar panel, and a redstone force-field projector.

## Key types

| class | role |
|-------|------|
| TilePump | RF consumer; BFS-drains a fluid body below, ejects to adjacent handlers |
| TileFluidTank | column-aware pressure tank (`TileFluidHatch`); fluid settles downward |
| TileSolarPanel | RF generator scaled by dimension/station insolation |
| TileForceFieldProjector | extends/retracts a line of force-field blocks on redstone |

## Mechanics

- **MECH-INFRA-23 — Pump source search.** `TilePump` scans straight down to the first
  liquid, then BFS-floods (up/N/S/E/W, `RANGE`=64, tunable) collecting drainable source
  blocks into `cache`; `canPerformFunction` lazily populates the cache and only authorises
  the 100-RF operation if a drainable source exists. Every `world.isBlockLoaded` is checked
  first — the flood never force-loads chunks. [V TilePump.java:197-321]
- **MECH-INFRA-24 — Pump drain + eject.** `performFunction` (throttled
  `PUMP_INTERVAL_TICKS`, tunable) drains one `IFluidBlock` or vanilla source into the
  16000-mB tank (setting vanilla sources to air), emitting a `PacketFluidParticle`;
  `update` separately ejects up to 1000 mB/`EJECT_INTERVAL_TICKS` to adjacent
  fluid handlers. Redstone power *disables* the pump. [V TilePump.java:77-181, 282-284]
- **MECH-INFRA-25 — Pressure-tank column.** `TileFluidTank` overrides fill/drain to be
  column-aware: `fill` climbs to the topmost tank then fills top-down (`fillInternal2`
  recurses down); `drain` prefers the tank above; `onAdjacentBlockUpdated` cascades fluid
  downward / pulls upward with an `inColumnOp` re-entrancy guard. It exposes **only** its
  own `FLUID_HANDLER` (never the parent's) and gates all ops on `!isRemote && !removing`.
  [V TileFluidTank.java:110-368]
- **MECH-INFRA-26 — Solar generation.** `TileSolarPanel` generates only when it can see
  sky and it is daytime; `getPowerPerOperation` = `1.0005 * 2 * solarGeneratorMult *
  insolation`, capped at 10 RF/t, where insolation is the space-station multiplier in the
  space dim or the dimension's peak insolation elsewhere.
  [V TileSolarPanel.java:26-65]
- **MECH-INFRA-27 — Force-field projection.** Every 5 ticks, redstone-powered projector
  extends a line of `blockForceField` in its facing up to `MAX_RANGE`=32; unpowered it
  retracts, with a self-heal that re-deletes a field block that "didn't stay deleted".
  `extensionRange` persists. [V TileForceFieldProjector.java:33-97]

## State & persistence

NBT keys (`TilePump` `tank`, `TileForceFieldProjector` `ext`, `TileFluidTank` via `write/readFromNBTHelper`): see `C1-nbt-persistence`.
`TileSolarPanel` persists only its libVulpes RF-machine parent state; the `cache` in `TilePump` is transient and cleared on
`onChunkUnload`/`invalidate`. [V TilePump.java:362-381]

## Invariants

- **INV-INFRA-22 [T]** A pump above an adjacent water source fills its tank (>0 mB) within
  ~60 ticks. [T TilePumpFillsFromAdjacentWaterSourceTest.java:77-82]
- **INV-INFRA-23 [V]** The pump only spends 100 RF when a drainable source is confirmed in
  `cache` (`canPerformFunction` returns `!cache.isEmpty()`). [V TilePump.java:287-320]
- **INV-INFRA-24 [T]** Fluid injected into a pressure tank survives a server restart at the
  same coords. [T FluidTankNBTRoundTripsAcrossRestartTest.java:93]
- **INV-INFRA-25 [T]** Stacked pressure tanks distribute a fill correctly (top-down column
  fill). [T FluidTankStackedFillTest.java]
- **INV-INFRA-26 [T]** A solar panel in aStellurgy dimension gains energy on a forced tick,
  scaled by insolation. [T SolarPanelInsolationTest.java:118]
- **INV-INFRA-27 [T]** The force-field projector projects a block when powered and retracts
  it to air when unpowered. [T ForceFieldProjectorProjectsAndRetractsTest.java:63-81]

## Failure modes & edge cases

- `TileFluidTank` sets `removing=true` on `invalidate`/`onChunkUnload` so an in-flight
  column op cannot touch a dying tile. [V TileFluidTank.java:266-276]
- Pump flood-fill and eject both respect chunk-loaded checks; no chunk load inside the
  tick. [V TilePump.java:239-241]
- **Solar panel in the space dimension NPEs if not on a station**:
  `getPowerPerOperation` calls `getSpaceStationFromBlockCoords(pos).getInsolationMultiplier()`
  with no null check, and `getSpaceStationFromBlockCoords` can return null.
  [V TileSolarPanel.java:60; SpaceObjectManager.java:119-137] (unconfirmed).

## Integration seams

Packets: `PacketFluidParticle` (C2, network-wire). Caps: `FLUID_HANDLER` on pump and
pressure tank (C5). Blocks: `blockForceField`/`blockForceFieldProjector`,
`blockPressureTank`, `blockSolarGenerator` (C3, registered externally). Solar depends on
`space-stations` (`SpaceObjectManager`) and `dimension-planets` (`DimensionProperties`).

## Config surface

Config: see `C4-config-surface`. Pump/force-field constants are `tunable`; there is no config to disable them (redstone is the off-switch).

## Test coverage

INV-INFRA-22 → TilePumpFillsFromAdjacentWaterSourceTest:77-82 · INV-INFRA-24/25 →
FluidTankNBTRoundTripsAcrossRestartTest:93, FluidTankStackedFillTest · INV-INFRA-26 →
SolarPanelInsolationTest:118 · INV-INFRA-27 → ForceFieldProjectorProjectsAndRetractsTest,
ForceFieldProjectionSmokeTest.
