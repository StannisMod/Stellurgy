---
id: infrastructure-tiles
owns: [tile/infrastructure/, tile/hatch/, tile/TileBrokenPart.java, tile/TileFluidTank.java, tile/TilePump.java, tile/TileSolarPanel.java]
entrypoints: [TileRocketMonitoringStation#update, TileFuelingStation#performFunction, TileRocketLoader#update, TileRocketServiceStation#performFunction, TilePump#performFunction]
depends-on: [rocket-entity, rocket-assembly, dimension-planets, space-stations, network-wire, api-public, mission]
depended-by: [blocks, client-render, integration-jei]
contracts: [C1, C2, C3, C4, C5]
confidence: high
---

## Purpose

Ground-side (and station-side) tile entities that *service a linked rocket*: fuel it,
load/unload cargo and fluids, feed guidance chips, repair worn parts, and monitor its
flight — plus a handful of standalone utility tiles (pump, pressure tank column, solar
panel, force-field projector, data bus). Every rocket-facing tile is an
`IInfrastructure`: it is linked to a rocket via the Linker item or claimed by an
assembler, does per-tick work only while linked, and emits redstone reflecting its state.

## Responsibility boundary

**Owns**: the `IInfrastructure` server-side behaviour of each station/hatch (link
lifecycle, per-tick transfer/fuel/repair loops, redstone emission, their NBT), the fluid
pump + gravity-fed pressure-tank column, the RF solar panel, the force-field projector,
and the data-bus hatches.

**Does NOT own**: the rocket entity, its `StorageChunk`, fuel/stat model
(`rocket-entity`, `rocket-assembly`); the assembler that *claims* infrastructure
(`TileRocketAssemblingMachine` → `rocket-assembly`); `TilePrecisionAssembler` and the
repair *recipe* (`multiblock-machines`, `recipe`); the `RocketEvent` classes, packets,
capabilities and config *definitions* (`api-public`, `network-wire`); block/item
registration and models (`blocks`, `items`, `client-render`); GUI `Module*` widgets
(libVulpes). `TileWearable` (base of `TileBrokenPart`) and its `stage/maxStage/
transitionProb` NBT are owned by `atmosphere-oxygen`/wear code, not here.

## Shared patterns (stated once)

- **`IInfrastructure` link lifecycle** — `linkRocket` / `unlinkRocket` set/clear a
  `linkedRocket` (or `rocket`) field; `onLinkStart` writes master coords into the Linker
  item; `disconnectOnLiftOff` decides whether the link survives launch (monitor=false,
  all others=true); `getMaxLinkDistance` bounds the Linker reach. `invalidate` unlinks
  from both the rocket and, if `hasMaster()`, the owning assembler.
  [V TileRocketLoader.java:298-344]
- **Redstone control via `RedstoneState` enum** (`ON`/`OFF`/`INVERTED`) — a
  `ModuleRedstoneOutputButton` toggles it; `isStateActive`/`setRedstoneState` maps the
  desired boolean through the enum before driving the block. The enum ordinal is
  persisted as NBT byte `redstoneState`. [V TileRocketLoader.java:285-296]
- **Server-only tick guard** — every `update`/`performFunction` early-returns on
  `world.isRemote`; client rows exist only to refresh GUI text. [V TilePump.java:79]
- **TE-sync via `getUpdatePacket`/`onDataPacket`** wrapping `writeToNBT`/`readFromNBT`
  (or `handleUpdateTag`) — the same NBT is both the save format and the wire format.
  [V TileRocketMonitoringStation.java:612-626]

## Mechanic index

| cluster | doc | mechanics |
|---------|-----|-----------|
| Monitoring station (status FSM, comparator, mission tab, assembler claim) | [monitoring-station.md](./monitoring-station.md) | MECH-INFRA-01..07 |
| Service station + broken-part repair | [service-station.md](./service-station.md) | MECH-INFRA-08..11 |
| Fueling station + item/fluid loaders & unloaders | [fueling-and-loaders.md](./fueling-and-loaders.md) | MECH-INFRA-12..17 |
| Guidance hatch, data bus, satellite/inv hatches | [guidance-and-hatches.md](./guidance-and-hatches.md) | MECH-INFRA-18..22 |
| Pump, pressure-tank column, solar, force-field | [fluid-and-utility.md](./fluid-and-utility.md) | MECH-INFRA-23..27 |

## Dependency edges

- **→ rocket-entity**: reads/writes `EntityRocket.storage`, `getRocketFuelType`,
  `getFuelAmount/Capacity`, `addFuelAmount`, `recalculateStats`, `prepareLaunch`,
  `getRelativeHeightFraction`, `isInOrbit/isInFlight`; subscribes to `RocketEvent.*`.
- **→ rocket-assembly**: `TileRocketAssemblingMachine.removeConnectedInfrastructure`;
  monitor's assembler-claim window (`markRocketFromAssembler`).
- **→ space-stations / dimension-planets**: solar-panel insolation
  (`SpaceObjectManager`, `DimensionProperties`); monitor SD-rocket restriction.
- **→ mission**: monitor resolves `IMission` from `DimensionManager.getSatellite`.
- **→ network-wire**: `PacketFluidParticle`, `PacketBackToRocketGui`, and libVulpes
  `PacketMachine`/`PacketEntity`/`PacketHandler`.

## Contracts touched

C1 NBT keys (see per-doc *State & persistence*), C2 packets, C3 registry (blocks/items/TE
registered externally in `Stellurgy.java`), C4 config flags
(`fuelPointsPer10Mb`, `orbit`, `spaceDimId`, `solarGeneratorMult`, `dataBusBigMultiplier`,
`serviceStationStandaloneRepairMultiplier`, `stationSize`), C5 capabilities
(`ITEM_HANDLER`, `FLUID_HANDLER`) + `RocketEvent` Forge bus.

## Open questions

- `assemblerPoses`/`partsProcessing`/`statesProcessing` NBT keys (service station) were
  not in the P0 seam inventory (written via `NBTHelper.writeCollection`); reconcile in P3.
- Monitor `readDataFromNetwork` decodes packetId==2 into NBT `state`, but `useNetworkData`
  id==2 is a no-op — a dead redstone-control wire path (see monitoring-station.md).
