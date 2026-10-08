---
id: misc-oddities
owns: [common/, unit/, enchant/, Stellurgy.java, tile/TileEntitySyncable.java]
entrypoints: [Stellurgy#preInit, Stellurgy#postInit, Stellurgy#serverStarting, Stellurgy#serverStopped, IngameTestOrchestrator#runTests]
depends-on: [api-public, dimension-planets, world-gen, network-wire, event-handlers, satellite, rocket-assembly, util-core, mission]
depended-by: [everything — this is the @Mod bootstrap]
contracts: [C1, C2, C3, C4, C5]
confidence: high
---

## Purpose

The junk-drawer subsystem: the `@Mod` main class that wires the *entire* mod together
(all block/item/entity/tile/fluid/biome/satellite/packet registration and the Forge
lifecycle handlers), plus a handful of small standalone helpers that fit nowhere else —
the dedicated-server proxy stub, the space-breathing marker enchant, a side-split
terraforming-state holder, a self-syncing tile base, and the in-game rocket integration
test harness. No single gameplay mechanic lives here; the value is the *boot sequence* and
the seams it opens.

## Responsibility boundary

Owns: `Stellurgy.java` (the `@Mod` entrypoint and every registration call in it);
`CommonProxy` (server-side no-op proxy base for `ClientProxy`); `EnchantmentSpaceBreathing`;
the `integrated_server_and_client_variable_sharing_fix` package (per-side terraforming
state); `TileEntitySyncable` (a self-syncing `TileEntity` base); the `unit/` in-game test
harness; the `Test.java` scratch `main()`.
Does NOT own: any block/item/entity/tile *class* it registers (those belong to `blocks`,
`items`, `rocket-entity`, `multiblock-machines`, … — this file merely instantiates and
names them); `StellurgyConfiguration` and its flags (`api-public`); `ClientProxy` (`client-render`);
the packets it registers (`network-wire`); `DimensionManager`/`SpaceObjectManager`/
`AtmosphereHandler` lifecycle internals it merely clears (their own subsystems).

## Key types

| class | role |
|-------|------|
| `Stellurgy` | the `@Mod` class; holds static singletons (`machineRecipes`, `proxy`, `compat`, `materialRegistry`) and all lifecycle `@EventHandler`/`@SubscribeEvent` methods `Stellurgy.java:154` |
| `CommonProxy` | dedicated-server proxy: particle/UI/keybind/message calls are no-ops; answers `getDimensionManager()` with the running server's galaxy (dimension-planets MECH-DIM-23) `CommonProxy.java:146` |
| `EnchantmentSpaceBreathing` | armor-only marker enchant, max level 1, not table/book obtainable `EnchantmentSpaceBreathing.java:11` |
| `TileEntitySyncable` | `TileEntity` base whose update packet is the full NBT; `markDirty` forces a block update `TileEntitySyncable.java:9` |
| `IngameTestOrchestrator` | tick-scheduled runner for in-game tests; one instance per server (`ServerState.ingameTests`, `ServerState.java:54`), driven from `ServerStateEvents.onWorldTick` (`ServerStateEvents.java:27`), not an event-bus subscriber of its own — `IngameTestOrchestrator.java:18` |
| `BuildRocketTest` / `BaseTest` / `RotationTest` | the phased build→launch→return integration test and helpers `BuildRocketTest.java:24` |
| `Test` | dead scratch `main()`; static block injects a "Test" asteroid into config on load `Test.java:11` |

## Mechanics

- **MECH-MISC-01 Registration pipeline.** The `@Mod` class registers *everything* across
  Forge `RegistryEvent` handlers (`register` biomes `:187`, `registerEnchants` `:501`,
  `registerItems` `:509`, `registerBlocks` `:640`, `registerRecipes` `:976`) and
  `@EventHandler` phases (`preInit` `:288` → `load`/init `:984` → `postInit` `:1082`). Blocks
  and items are instantiated then `setRegistryName`d and handed to `LibVulpesBlocks`; TEs via
  `GameRegistry.registerTileEntity` `:389+`; entities via `EntityRegistry.registerModEntity`
  `:371+`; 19 packets via `PacketHandler.addDiscriminator` `:334+`; satellite types `:358+`.
- **MECH-MISC-02 One-shot XML reset.** `preInit` reads `resetPlanetsFromXML`; if
  `ResetOnlyOnce` is true it immediately writes the flag back to false and saves, so an XML
  reload happens exactly once per toggle `Stellurgy.java:308-324`.
- **MECH-MISC-03 Server lifecycle bootstrap/teardown.** `serverAboutToStart` builds
  dimensions from XML (`createAndLoadDimensions(resetFromXml)`) `:1199`; `serverStarting`
  registers commands, reloads special recipes, and generates+loads `asteroidConfig.xml`/
  `oreConfig.xml` `:1203-1300`; `serverStarted` marks the Moon native when GC absent
  `:1187-1194`; `serverStopped` releases the server's galaxy and stations
  (`endServerLifetime`, dimension-planets MECH-DIM-23) and clears the remaining server statics.
- **MECH-MISC-04 Config-gated registration.** `enableTerraforming`/`enableGravityController`/
  `enableLaserDrill`/`enableOrbitalRegistry` gate whether the corresponding block *and* tile
  entity are registered at all `:457,604,691-699,1116-1121`.
- **MECH-MISC-05 Sided proxy dispatch.** `@SidedProxy proxy` picks `ClientProxy` on the
  client and `CommonProxy` (all-no-op) on the server, so common code can call rendering/UI
  freely `Stellurgy.java:166`, `CommonProxy.java:25`.
- **MECH-MISC-06** — retired.
- **MECH-MISC-07 Space-breathing enchant.** A marker enchant applicable only to armor,
  granted by machine recipe (not the enchanting table), consumed by the oxygen subsystem
  `EnchantmentSpaceBreathing.java:19-31`, registered at `Stellurgy.java:501-506`.
- **MECH-MISC-08 Self-syncing tile base.** `TileEntitySyncable` serializes its full NBT into
  the vanilla `SPacketUpdateTileEntity` and re-reads it on receipt; `markDirty` also fires
  `notifyBlockUpdate(...,3)` `TileEntitySyncable.java:11-29` (used by `TileWearable`).
- **MECH-MISC-09 In-game test harness.** `/stellurgy dev runTests` schedules
  `BuildRocketTest.Phase1` on the world tick; each phase reschedules the next N ticks later,
  driving build→fuel→launch→return and asserting the rocket lands where it took off
  Due steps are taken off the schedule before any runs, so a step's rescheduling never touches a map
  being walked `IngameTestOrchestrator.java:24-88`, `BuildRocketTest.java:47-104`.
- **MECH-MISC-10 Login planet resync.** `onPlayerLogin` pushes `PacketSyncKnownPlanets` for
  every space station to the joining player — an admittedly-redundant belt-and-braces sync
  `Stellurgy.java:1330-1342`.

## State & persistence

This subsystem writes no gameplay NBT of its own. `TileEntitySyncable` round-trips whatever
NBT its subclass writes (`writeToNBT`/`readFromNBT`, `TileEntitySyncable.java:22,28`). The
terraforming holders (`dimensionTerraformingInfo`) are **in-memory only**, keyed by dim id,
never serialized here. The three config *keys* the file owns directly (see Config surface)
sit in the raw `stellurgy.cfg`, not in world NBT.

## Invariants

- **INV-MISC-01 [V]** All object construction + `setRegistryName` happens inside Forge
  `RegistryEvent` handlers; blocks/items are built in `registerBlocks`/`registerItems` before
  being named `Stellurgy.java:640,509`.
- **INV-MISC-02 [V][BEH]** With `ResetOnlyOnce=true`, `resetPlanetsFromXML` is forced back to
  false on the boot that consumes it `Stellurgy.java:322-324`.
- **INV-MISC-03 [V][BEH]** `serverStopped` clears wirelessdata `NetworkRegistry`, `AtmosphereHandler`,
  the pipe-seal map, saves `WeightEngine`, and last RELEASES the server's `DimensionManager` and
  `SpaceObjectManager` (`endServerLifetime`) `Stellurgy.java:1634`.
- **INV-MISC-04 [V][BEH]** The terraformer / gravity-machine / orbital-laser / orbital-registry
  block+TE pairs exist in the registries only when their feature flag is enabled
  `Stellurgy.java:457,691-699`.
- **INV-MISC-05** — retired.
- **INV-MISC-06 [V][BEH]** `EnchantmentSpaceBreathing`: max level 1, `canApplyAtEnchantingTable`
  false, `isAllowedOnBooks` false, applies only to `ItemArmor`
  `EnchantmentSpaceBreathing.java:19-36`.
- **INV-MISC-07 [V]** Every `markDirty` on a `TileEntitySyncable` triggers a full-NBT block
  update packet `TileEntitySyncable.java:11-24`.
- **INV-MISC-08 [V][BEH]** A rocket built on-pad, fueled and launched reaches a space station and
  returns to its takeoff `BlockPos`: the in-game harness `unit/BuildRocketTest.java:78-104` asserts it
  in Phase3/Phase4. It lives in `src/main`, is run by hand and is not a JUnit test.
- **INV-MISC-09** — retired.

## Failure modes & edge cases

- Flipping a feature flag off after worlds contain the gated block leaves that block/TE
  unregistered → vanilla drops it on load (INV-MISC-04).
- `serverStopped` re-reads `minDimension`→`DimensionManager.dimOffset` and
  `spaceStationId`→`spaceDimId` from config *at shutdown*, so a mid-session edit only takes
  effect next launch `Stellurgy.java:1311-1312`.
- The asteroid/ore XML files are auto-created with hardcoded defaults on first
  `serverStarting`; a malformed edit is caught and logged, not fatal `:1247-1298`.

## Integration seams

- **Capabilities registered at pre-init** : `CapabilityDamageAware`, `CapabilityHeatEmitter` / `HeatPump` /
  `HeatSink`, `Stellurgy.java:1561-1564`. Line ranges cited elsewhere in this doc may have moved.
- **Registry (C3):** this file is the single registration site for ~11 entities `:371-386`,
  ~60 tile entities `:389-455`, 5 fluids `:780-813`, 12 biomes `:204-229`, 10 satellite
  types `:358-367`, 1 space-object type `:481`, and (via `LibVulpesBlocks`) the full
  block/item name set. The concrete block/item names are catalogued in `blocks`/`items`/C3;
  the entity/TE/fluid/biome/satellite names roll up into **C3** from here.
- **Packets (C2):** registers all 19 discriminators `Stellurgy.java:334-352`;
  `CommonProxy` sends `PacketLaserGun` `:120` and `PacketStationUpdate` `:95`; `onPlayerLogin`
  sends `PacketSyncKnownPlanets` `:1339`.
- **Capabilities & events (C5):** registers `CapabilityProtectiveArmor` `:331`,
  `CapabilitySpaceArmor`/`CapabilityWear` `:1084-1085`, and a swarm of `EVENT_BUS` handlers in
  `postInit` `:1128-1164` (planet, weather, acid-rain, wireless, input-sync, lander,
  space-object-manager, chunk-load callback).
- **Proxy:** `CommonProxy`/`ClientProxy` via `@SidedProxy` (MECH-MISC-05).

## Config surface

Config: see `C4-config-surface` for `StellurgyConfiguration` flags (the consequence side of several lives here).
Keys read directly through the raw `Configuration` object and NOT in C4:

| key | category | default | mechanic |
|-----|----------|---------|----------|
| `resetPlanetsFromXML` | Planet | false | MECH-MISC-02 (one-shot XML reload) |
| `ResetOnlyOnce` | Planet | true | MECH-MISC-02 (gates the auto-reset) |
| `BlacklistedBiomes`/`HighPressureBiomes`/`SingleBiomes` | Planet | — | biome classification for terraforming `:1001-1010` |

`minDimension` is `StellurgyConfiguration.minDimension`, read once at pre-init; it seeds each new manager's `dimOffset`.

## Test coverage

- INV-MISC-08 → `src/main/.../unit/BuildRocketTest.java:78-104` (in-game harness, run via
  `/stellurgy dev runTests`, not a JUnit test). `RotationTest`/`Test` are `main()`
  scratch, no assertions.

## Open questions

- Full enumeration of the 170+ registry names this file emits is deferred to **C3**; only the
  registration *mechanic* and per-category counts are captured here.
- The `IngameTestOrchestrator` is not registered on the event bus; it is a per-server instance (`ServerState.ingameTests`) driven by
  `ServerStateEvents.onWorldTick`, so a step pending when a server stops cannot fire in the next one.
- `IngameTestOrchestrator.eventScheduler` (now an instance map, `IngameTestOrchestrator.java:20`)
  still collides on same-tick keys and fires a step on whichever world ticks first; one `name` serves
  every run, so two runs in flight report to the last starter. The
  re-schedule-during-walk CME needs two pending steps, never a single run (`IngameTestOrchestrator.java:53-88`).
- config-gated registration save-compat risk (INV-MISC-04).
- redundant login planet resync (MECH-MISC-10) vs Stellurgy's own sync (SSOT).
