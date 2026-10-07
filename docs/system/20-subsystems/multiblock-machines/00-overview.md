---
id: multiblock-machines
owns: [tile/multiblock/]
entrypoints: [TileOrbitalLaserDrill#update, TileAtmosphereTerraformer#onRunningPoweredTick, TileObservatory#useNetworkData, TileRailgun#useEnergy, TileMicrowaveReciever#update]
depends-on: [dimension-planets, space-stations, atmosphere-oxygen, api-public, network-wire, world-gen, satellite]
depended-by: [client-render, blocks, inventory-containers, commands-gameplay]
contracts: [C1, C2, C3, C4, C5]
confidence: high
---

## Purpose

The end-game machinery of Stellurgy: every controller tile under `tile/multiblock/`.
These are multi-block structures (a controller block plus a fixed 3-D `Object[][][]` template
of structural/hatch blocks) that harvest planets, terraform atmospheres, generate power,
process asteroid data, transport cargo/players between dimensions, and run production recipes.

## Responsibility boundary

Owns: the 28 controller `TileEntity` classes, their structure templates, their tick logic,
their GUI module lists, and their NBT/network state. Owns the three orbital-laser drill
*strategies* (`AbstractDrill` + `MiningDrill`/`VoidDrill`/`terraformingdrill`) and the machine
`registerRecipes` hooks.

Does NOT own: the libVulpes base classes that implement multiblock formation, power storage,
hatch wiring and the recipe engine (`TileMultiBlock`, `TileMultiPowerConsumer`,
`TileMultiPowerProducer`, `TileMultiblockMachine`, `RecipesMachine`) — only our seam against
them. Does NOT own the blocks/items that back these tiles (→ `blocks`, `items`), the
`DimensionProperties` model it mutates (→ `dimension-planets`), `SpaceObjectManager`/station
lookup (→ `space-stations`), `TerraformingHelper`/`BiomeHandler`/`ChunkManagerPlanet`
(→ `world-gen`, `util-core`), satellite energy transmitters (→ `satellite`), or the entities
these tiles spawn (`EntityLaserNode`, `EntityItemAbducted`, `EntityElevatorCapsule` → `rocket-entity`/entity).

## Shared multiblock pattern (libVulpes seam)

All tiles override `getStructure()` (the template), `getMachineName()`, and usually
`shouldHideBlock()` / `getRenderBoundingBox()`. Formation is driven by libVulpes: on first tick
(`timeAlive == 0`) or on GUI open the tile calls `attemptCompleteStructure(state)`; structural
blocks are swapped for hidden placeholders and hatches are wired into `itemInPorts` /
`itemOutPorts` / `fluidInPorts` / `batteries`. Power consumers draw from `batteries`; producers
call `producePower(n)`. Networking uses the libVulpes generic `PacketMachine` (a single
registered packet carrying a per-tile `byte` discriminator) plus vanilla `SPacketUpdateTileEntity`
for the description packet — see [C2](../../30-contracts/C2-network-wire.md). This subsystem
registers **no** Stellurgy-owned packets.

## Mechanic index

| id | mechanic | doc |
|----|----------|-----|
| MECH-MBM-01 | Multiblock formation + hidden-block replacement (libVulpes seam) | this file |
| MECH-MBM-02 | Hatch / data-bus inventory snapshot & restore across (de)construction | this file |
| MECH-MBM-03 | Drill strategy dispatch (SINGLE / SPIRAL / T_FORM) | [orbital-laser-drill](./orbital-laser-drill.md) |
| MECH-MBM-04 | Mining operation — 3×3 column break to hatches, jam on full | [orbital-laser-drill](./orbital-laser-drill.md) |
| MECH-MBM-05 | Void-drill ore synthesis (config + dim ores, voidCobble toggle) | [orbital-laser-drill](./orbital-laser-drill.md) |
| MECH-MBM-06 | Terraforming drill — terrain heightmap terraform via queue | [orbital-laser-drill](./orbital-laser-drill.md) |
| MECH-MBM-07 | Laser run-gating (redstone + orbit + power + dim blacklist) & spiral advance | [orbital-laser-drill](./orbital-laser-drill.md) |
| MECH-MBM-08 | Laser chunk-loading ticket lifecycle | [orbital-laser-drill](./orbital-laser-drill.md) |
| MECH-MBM-09 | Atmosphere density step toward 1600 / 0 | [atmosphere-terraformer](./atmosphere-terraformer.md) |
| MECH-MBM-10 | N₂/O₂ fluid consumption + out-of-fluid state | [atmosphere-terraformer](./atmosphere-terraformer.md) |
| MECH-MBM-11 | Terraform eligibility guards (native/Stellurgy-planet + config) | [atmosphere-terraformer](./atmosphere-terraformer.md) |
| MECH-MBM-12 | Solar array power (panels × insolation, sky/day) | [energy-generation](./energy-generation.md) |
| MECH-MBM-13 | Microwave receiver (satellite energy + burn obstructions/entities) | [energy-generation](./energy-generation.md) |
| MECH-MBM-14 | Black-hole generator (item burn near black hole → power) | [energy-generation](./energy-generation.md) |
| MECH-MBM-15 | Recipe → power → completion cycle (shared bench) | [production-bench](./production-bench.md) |
| MECH-MBM-16 | Chemical-reactor special suit-seal recipe generation | [production-bench](./production-bench.md) |
| MECH-MBM-17 | Crystallizer gravity gate | [production-bench](./production-bench.md) |
| MECH-MBM-18 | Observatory open/close + view-distance accrual from optics | [planetary-data](./planetary-data.md) |
| MECH-MBM-19 | Observatory asteroid discovery — seed scan, chip printing, dedup | [planetary-data](./planetary-data.md) |
| MECH-MBM-20 | Observatory data-bus store / load to chips | [planetary-data](./planetary-data.md) |
| MECH-MBM-21 | Astrobody data processor — 3-type research onto asteroid chip | [planetary-data](./planetary-data.md) |
| MECH-MBM-22 | Planet selector (visible planets, system selection) | [planetary-data](./planetary-data.md) |
| MECH-MBM-23 | Biome scanner readout | [planetary-data](./planetary-data.md) |
| MECH-MBM-24 | Railgun cargo transfer (linked dest, dim-load, fire status) | [transport-and-field](./transport-and-field.md) |
| MECH-MBM-25 | Space-elevator tether link + capsule summon | [transport-and-field](./transport-and-field.md) |
| MECH-MBM-26 | Warp core — dilithium → station warp fuel | [transport-and-field](./transport-and-field.md) |
| MECH-MBM-27 | Area gravity controller — per-side field, radius, slider | [transport-and-field](./transport-and-field.md) |
| MECH-MBM-28 | Beacon — register/unregister landing location | [transport-and-field](./transport-and-field.md) |
| MECH-MBM-29 | Observatory region survey — aim in star territories, sweep, write a crystal | [planetary-data](./planetary-data.md) |

### MECH-MBM-01 — Multiblock formation

`update()` on most tiles guards `timeAlive == 0` to run one deferred `attemptCompleteStructure`,
then sets `completeStructure`/`canRender` and (server) `checkCanRun`. `shouldHideBlock` returns
true so the structural blocks disappear once formed. [V] `TileOrbitalLaserDrill.java:475-481`,
`TileSolarArray.java:128-131`, `TileAstrobodyDataProcessor.java:239-258`.

### MECH-MBM-02 — Inventory snapshot/restore across (de)construction

Because libVulpes replaces hatch/data-bus TEs with placeholders during formation and destroys
them on teardown, three tiles hand-roll a save/restore of the *contents*:
`TileMicrowaveReciever` snapshots hatch item stacks keyed by `BlockPos.toLong()` and re-pushes
them into placeholders (persisted under NBT `savedHatchInv`/`pos`/`slot`/`items`);
`TileObservatory` snapshots each data-bus's full NBT; `TileAstrobodyDataProcessor` re-locks
data-bus types on integrate/deconstruct. [V] `TileMicrowaveReciever.java:166-197,449-695`,
`TileObservatory.java:131-229`, `TileAstrobodyDataProcessor.java:80-128`.

## Dependency edges

- **→ space-stations**: `SpaceObjectManager.getSpaceStationFromBlockCoords(pos)` for orbit
  identity / insolation (laser drill, solar, microwave, black hole, biome scanner, elevator,
  warp core). `WARPDIMID` sentinel guards.
- **→ dimension-planets**: `DimensionManager.getInstance().getDimensionProperties(dim)` read for
  insolation/atmosphere/gravity; the terraformer **writes** the planet's air (adds N₂+O₂ / rescales); the beacon
  writes beacon locations; void-drill reads `laserDrillOres`.
- **→ world-gen / util-core**: `TerraformingHelper`, `BiomeHandler.terraform`,
  `ChunkManagerPlanet`, `PlanetaryTravelHelper`.
- **→ satellite**: microwave receiver pulls `IUniversalEnergyTransmitter.transmitEnergy`.
- **→ atmosphere-oxygen**: terraformer/chemical-reactor gate on `enableOxygen`/oxygen fluids.
- **→ Forge**: `ForgeChunkManager` tickets (laser drill, railgun); `MinecraftForge.EVENT_BUS`
  posts `LaserBreakEvent` for block-protection (→ `event-handlers`).

## Config surface (whole-subsystem)

Config: see `C4-config-surface`. Per-mechanic disable paths are in each sub-doc's Config section.

## Open questions

- `microwaveRecieverMulitplier` is applied on the **transmit** side
  (`SatelliteMicrowaveEnergy.java:67`), not in `TileMicrowaveReciever`; the receiver instead
  scales by a hardcoded `2 × insolation`. Whether the double-source scaling is intended is
  unverified (→ satellite subsystem owns the flag).
- libVulpes internals of `attemptCompleteStructure` / whether `readFromNBT` invokes
  `readNetworkData` are out of scope; several claims below assume it does **not**.
