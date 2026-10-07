---
id: api-public
owns: [api/, api/armor/IFillableArmor.java, api/armor/IProtectiveArmor.java, api/atmosphere/IAtmosphereSealHandler.java, api/dimension/IDimensionProperties.java, api/dimension/solar/IGalaxy.java, api/dimension/solar/StellarBody.java, api/satellite/IDataHandler.java, api/satellite/SatelliteBase.java, api/satellite/SatelliteProperties.java, api/stations/ISpaceObject.java, api/stations/IStorageChunk.java]
entrypoints: [StellurgyConfiguration#loadPreInit, StellurgyConfiguration#loadPostInit, StellurgyConfiguration#writeConfigToNetwork, StellurgyConfiguration#readConfigFromNetwork, StatsRocket#createFromNBT, SatelliteRegistry#createFromNBT, FuelRegistry#registerFuel]
depends-on: [dimension-planets, util-core, atmosphere-oxygen, items, integration-modcompat]
depended-by: [rocket-entity, rocket-assembly, satellite, space-stations, multiblock-machines, infrastructure-tiles, atmosphere-oxygen, dimension-planets, client-render, world-gen, event-handlers, commands-gameplay]
contracts: [C1, C2, C3, C4, C5]
confidence: high
---

## Purpose

`api-public` is the mod's contract layer: the classes every other subsystem imports and that
downstream addons compile against. It holds the master `StellurgyConfiguration` (one flat object of
**147** tunable/contract flags `[V]`, network-synced field-by-field — C4's
matrix is audited at 128 of them and says so), the static registries and holder
classes (satellites, fuels, atmospheres, fluids, biomes, blocks, items), the persisted value
objects that ride in NBT (`StatsRocket`, `SatelliteProperties`, `DataStorage`, `StellarBody`),
and the large set of `I*` interfaces + Forge capability/event declarations that decouple the
gameplay tiles/entities from the API surface.

It is almost entirely *depended-by*: it is the bottom of the dependency graph even though a few
of its concrete classes reach back up into `dimension`, `util` and `atmosphere` (see the edges
below for the resulting cyclic coupling).

## Responsibility boundary

**Owns** (`api/` minus free-flight — those live in rocket-entity):
- **Config** — `StellurgyConfiguration` + its `@ConfigProperty` annotation. Disk load, network sync,
  reflection copy. → [`config.md`](./config.md)
- **Rocket stat block** — `StatsRocket`, `EntityRocketBase`, `RocketEvent`, and the engine/fuel
  tile interfaces (`IRocketEngine`, `IRocketNuclearCore`, `IFuelTank`, `IIntake`, `IMiningDrill`).
  → [`rocket-stats.md`](./rocket-stats.md)
- **Registries & holders** — `SatelliteRegistry`, `fuel/FuelRegistry`,
  `StellurgyFluids/Biomes/Blocks/Items`, `StellurgyAPI`, `Constants`.
  → [`registries.md`](./registries.md)
- **Satellite / data value objects** — `satellite/SatelliteBase`, `satellite/SatelliteProperties`,
  `DataStorage`, `satellite/IDataHandler`, `ISatelliteIdItem`. → [`satellite-data.md`](./satellite-data.md)
- **Ship lifecycle announcement** — `event/ShipLifecycleEvent` (+ its `Cause` enum): the ONE family
  (ruled 2026-10-06) for the edges at which the physics engine names, readies and un-names a tier-2
  craft — `ShipNamed` (`ASSEMBLED` / `PASTED` / `LOADED`), `ShipUsable`, `ShipUnnamed` (`UNLOADED` /
  `DESTROYED`) and `ShipDeparted` (`DEPARTED` + `destinationDim`) — each posted at its source by
  `valkyrienskies/…/WorldServerShipManager` on the server game thread. The thread + handler contract
  is in the class javadoc; contract C5 B.10 routes. (`event/ShipEvent` now carries only the flight
  computer's two edges.)
- **Interfaces, capabilities, astronomy, sealing** — `dimension/IDimensionProperties`,
  `stations/ISpaceObject`, `stations/IStorageChunk`, `IPlanetaryProvider`, `IInfrastructure`,
  `IMission`, `IGravityManager`, `atmosphere/IAtmosphereSealHandler`,
  `dimension/solar/StellarBody` + `IGalaxy`, `capability/*`, `armor/*`, `AreaBlob` + `util/IBlobHandler`,
  `MaterialGeode`. → [`interfaces.md`](./interfaces.md)

**Does NOT own:** `api/FreeFlight*`, `api/RocketFlightMode` (→ rocket-entity); the concrete
`DimensionProperties` implementing `IDimensionProperties` (→ dimension-planets); the concrete
`SpaceStationObject` implementing `ISpaceObject` and `StorageChunk` implementing `IStorageChunk`
(→ space-stations / rocket-assembly); the `PacketConfigSync` wrapper that *calls*
`writeConfigToNetwork` (→ network-wire); the config *values*' disk file writing (Forge `Configuration`).

## Key types

| type | role |
|------|------|
| `StellurgyConfiguration` | the whole config object; disk load, reflective per-field network sync, copy ctor |
| `StellurgyConfiguration.ConfigProperty` | field annotation: `needsSync`, `internalType/keyType/valueType` for collections |
| `StatsRocket` | rocket stat/fuel block; per-fuel-type amounts/rates/caps, seats, engine locs, NBT |
| `EntityRocketBase` | abstract rocket entity: infrastructure links, fuel abstract API, orbit/dismantle events |
| `SatelliteRegistry` | static: name→class, itemstack→properties, id lookup from chip/chassis NBT |
| `FuelRegistry` | static singleton: 7 `FuelType`s, each a set of fluid/item `FuelEntry` + multiplier |
| `SatelliteBase` | abstract satellite: properties, battery, tick, NBT, GUI-sync hooks |
| `SatelliteProperties` | persisted satellite spec: power gen/storage, maxData, type, weight, id |
| `DataStorage` | typed data buffer (`DataType` enum) with lock/adopt/wildcard semantics |
| `StellarBody` | star/black-hole node: sub-stars, planets map, temperature→RGB, NBT |
| `IDimensionProperties` | read/write contract for a planet dimension (impl in dimension-planets) |
| `ISpaceObject` | contract for an orbiting station (impl in space-stations) |
| `AreaBlob` | adjacency-graph region (oxygen bubble) backed by an `IBlobHandler` tile |
| `StellurgyAPI` | five public static handles (seal handler, space-object mgr, galaxy, gravity, enchant) |

## Mechanic index

Detail (with `file:line` citations) lives in the cluster docs.

- **Config** — MECH-API-01 disk load · 02 network sync (write) · 03 network sync (read) ·
  04 server-config swap · 05 reflective copy. See [`config.md`](./config.md).
- **Rocket stats** — MECH-API-06 fuel-slot dispatch · 07 weight+fluid mass · 08 TWR launch gate ·
  09 stat NBT round-trip · 10 dynamic stat tags · 11 infrastructure linking · 12 orbit/dismantle
  events. See [`rocket-stats.md`](./rocket-stats.md).
- **Registries** — MECH-API-13 satellite class/property registry · 14 satellite id from stack ·
  15 fuel type registry & matching · 16 atmosphere registry · 17 gas-giant gas registry. See
  [`registries.md`](./registries.md).
- **Satellite/data** — MECH-API-18 satellite NBT & battery heal · 19 property flag bits ·
  20 data buffer lock/adopt · 21 simulate-vs-commit data transfer. See [`satellite-data.md`](./satellite-data.md).
- **Interfaces/caps** — MECH-API-22 wear capability · 23 protective-armor capability · 24 area
  blob graph · 25 star colour · 26 star NBT tree · 27 seal-handler contract. See
  [`interfaces.md`](./interfaces.md).

## Dependency edges

- **Downward (owned → other subsystems):** `StellurgyConfiguration` imports `dimension.DimensionManager`,
  `atmosphere.AtmosphereVacuum`, `util.Asteroid`, `util.SealableBlockHandler`,
  `integration.MatterOvedriveIntegration`; `SatelliteRegistry` imports `item.ItemSatellite*` and
  `dimension.DimensionManager`; `StatsRocket` imports `util.WeightEngine`; `StellarBody`/
  `IDimensionProperties` import the concrete `dimension.DimensionProperties`. These are the API→impl
  back-edges that make `api` non-leaf.
- **Upward (everyone → api):** every gameplay subsystem reads `StellurgyConfiguration.getCurrentConfig()`,
  the `FuelType`/`DataType` enums, the `I*` interfaces and the NBT value objects.

## Contracts touched

C1 NBT (`StatsRocket`, `SatelliteProperties`, `SatelliteBase`, `DataStorage`, `StellarBody`) ·
C2 wire (`StellurgyConfiguration` per-field sync format + `MAGIC_CODE` terminator) · C3 registry
(`SatelliteRegistry` string ids, `FuelType`/`DataType` ordinals) · C4 config (all `@ConfigProperty`
flags) · C5 capabilities (`PART_WEAR`, `PROTECTIVEARMOR`) & events (`RocketEvent.*`,
`AtmosphereEvent.*`, `ShipLifecycleEvent.ShipNamed`/`ShipUsable`/`ShipUnnamed`/`ShipDeparted`).

> File and line counts for `api/` are not stated here: they drift, and no single measure of "lines" is agreed.
