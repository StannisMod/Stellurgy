---
id: test-suite-map
packages: [test, test/unit, test/server, test/client, test/integration]
files: 466            sloc: 102917  # both RECOUNTED live 2026-08-20 on the merged tree — never incremented, always re-taken
entrypoints: [MinecraftBootstrap#ensure, AbstractSharedServerTest#startSharedHarness, AbstractHeadlessServerTest (ext lib), AbstractClientE2ETest (ext lib)]
depends-on: [ALL — the suite is the second oracle for every gameplay subsystem]
depended-by: []
contracts: [C1, C2, C3, C4, C5, C6]
source-sha: 71368140 (tier COUNTS re-measured 2026-08-17 against the tree; they had drifted
  far - unit 46->124, server 151->221, client 27->67 - so treat any other number here as of
  its own sha, not as current)
confidence: high
---

## Purpose

Maps the 235-file / 39 607-LOC `src/test/java` tree — the second, independent
oracle for the mod's contracts (methodology §8). Every gameplay subsystem doc's
`Test coverage` section should point back here. This doc is a *coverage map*: it
groups the ~1040 `@Test` methods across 231 test classes (+4 infra/helper files)
by harness tier and by the subsystem/contract each pins. It does **not** restate
assertions — those live in the individual subsystem docs' `INV-* → test` rows.

## Responsibility boundary

Owns: the test-harness architecture (bootstrap, three lifecycle base classes,
the `/stellurgytest` probe as the sole world-mutation surface, the recipe kit,
fixtures), the tier taxonomy, and the `_documentsKnownBug` / `@Ignore` inventory.
Does NOT own: the `/stellurgytest` verb *catalogue* (that is `stellurgytest-probe-catalog`,
`command/test/` in `src/main`) — tests are its clients, not its definition.

## Key types (harness infrastructure)

| type | file | role |
|------|------|------|
| `MinecraftBootstrap` | `test/MinecraftBootstrap.java:33` | idempotent pure-JVM bootstrap: vanilla `Bootstrap.register()`, injects `CommonProxy` into `Stellurgy.proxy` + `LibVulpes.proxy`, registers deterministic star Sol id=0 |
| `StellurgyTestConstants` | `test/StellurgyTestConstants.java:10` | stable seeds/dim-ids: `stellurgy.tests` gate, seed `0x4151544553544C`, dims 9001-9004 |
| `AbstractSharedServerTest` | `test/server/AbstractSharedServerTest.java:67` | class-scoped single dedicated-server JVM (`@BeforeClass`/`@AfterClass`); 172 subclasses |
| `AbstractHeadlessServerTest` | ext `com.github.stannismod.forge.testing.junit` | per-`@Test` fresh dedicated-server JVM; 26 direct subclasses (restart/isolation tests) |
| `AbstractClientE2ETest` | ext lib | boots server + real MC client bot; 25 subclasses (`client/`) |
| `RealDedicatedServerHarness` / `TestClient` | ext lib | server process + `/stellurgytest` command client (`client().execute(...)`) |
| `Events` | `test/Events.java` | waits for an EVENT instead of sampling a value: `mark()` before the action, then `await(type)` / `assertChain(A,B,C)` over the side's ordered log — the server's is `test/trace/ServerEventLog`, an instance field of that server's `SideTrace` (a test mixin puts the `SideTrace` on the `MinecraftServer`), fed by the stateless `ServerEventRecorder` and read through the probe's `invoke-static` (`ServerEventLog.markReply` / `sinceReply`); it replaced the production `command/test/TestEventLog`, now deleted, so a shipped game carries no log. `markInstrumented()` additionally asserts the test-only mixins were installed, without which a mixin-sourced absence means nothing |
| `TestTrace` / `test/mixin/` | `test/trace/TestTrace.java`, `test/mixin/*` | the test-only mixin tree and its side router. Queued in a harness-launched JVM only (`mixins.stellurgy.test.json`, declared to the harness coremod through `META-INF/forge-test-mixins.txt`), so an observation a test needs never becomes a line a shipped game runs |
| `MachineRecipeEndToEndKit` | `test/server/MachineRecipeEndToEndKit.java:44` | generic recipe-pipeline fixture driver; parses `/stellurgytest` JSON hatch positions, no hardcoded item/fluid identities |
| `WorldCommandFixtures` | `test/server/WorldCommandFixtures.java` | shared `/stellurgytest world`/`dim` command fixtures (no `@Test`) |
| `ClientGuiTestSupport` | `test/client/ClientGuiTestSupport.java` | client GUI helper (no `@Test`) |

## Tier taxonomy

| tier (dir) | classes | harness | boots | side | typical role |
|------------|--------:|---------|-------|------|--------------|
| `unit/` | 137 | none / `MinecraftBootstrap` | JVM only | common | pure math, NBT round-trip, contract pins, no world |
| `integration/` | 15 | `MinecraftBootstrap` | static registries | common | packet wire round-trip, XML loader, atmosphere/dim logic |
| `server/` | 242 | `AbstractSharedServerTest` (186) / `AbstractHeadlessServerTest` (26) | real dedicated server | server | probe-driven world scenarios, recipe E2E, persistence-restart |
| `client/` | 68 | `AbstractSharedClientTest` (12) / `AbstractClientE2ETest` (25) + helpers | server + client bot | both | cross-side replication, GUI, item right-click, keybind→packet |
| root | 2 | — | — | — | `MinecraftBootstrap`, `StellurgyTestConstants` |

Class counts are a scan (`grep -l '@Test' <dir>/*.java`: the classes that contain a `@Test`) and drift; they are a lower bound,
not a measurement. Recount against the product tree before citing one.

Gating (tests `Assume.assumeTrue`, skip when off): server harness needs
`-D<AbstractHeadlessServerTest.PROP_HARNESS_ENABLED>=true`; client E2E additionally
needs `-Dforge.test.client.enabled=true` (a.k.a. `forge.test.client`); probe
commands need `stellurgy.tests`. Headless CI skips server+client tiers.

## Mechanics

- **MECH-TST-01 — per-method dedicated-server harness.** `AbstractHeadlessServerTest`
  cold-starts a fresh server JVM per `@Test`; used by restart/isolation/pristine-registry
  tests that cannot tolerate shared state. `test/server/AbstractSharedServerTest.java:13`.
- **MECH-TST-02 — class-scoped shared harness.** `AbstractSharedServerTest` starts one
  server in `@BeforeClass`, shared by all methods; subclass contract = position-isolated,
  id-fresh, no cross-method state leak, probe-only mutations. One method crash fails the
  whole class. `test/server/AbstractSharedServerTest.java:67`.
- **MECH-TST-03 — real-client E2E bot.** `AbstractClientE2ETest` boots server + a real MC
  client that connects, mounts entities, presses keys; asserts cross-side replication via
  `/stellurgytest * info` polling. `test/client/FreeFlightModeTest.java:17`.
- **MECH-TST-04 — pure-unit bootstrap.** `MinecraftBootstrap.ensure()` wires just enough
  static state (proxies, Sol) for common-side NBT/packet round-trips without a server;
  idempotent double-checked lock. `test/MinecraftBootstrap.java:39`.
- **MECH-TST-05 — probe as sole world-mutation surface.** Server/client tiers mutate world
  state only through `/stellurgytest` (`client().execute(...)`), parsing JSON responses with regex;
  never via reflected Forge APIs. `test/server/AbstractSharedServerTest.java:71-72` .
- **MECH-TST-06 — recipe end-to-end kit.** Shared protocol for the 9 industrial machines:
  discovers the first registered recipe, computes hatch positions from the structure-probe
  JSON, feeds inputs, ticks, asserts output. `test/server/MachineRecipeEndToEndKit.java:44`.
- **MECH-TST-07 — `_documentsKnownBug` pinning.** A test whose assertions pin *current buggy*
  behaviour and are designed to fail when the bug is fixed (forcing a manual flip), so the
  contract violation stays visible. `test/unit/SatelliteRegistryFallbackTest.java` is not one: its two tests
  (`:40`, `:51`) assert the by-design `null` for an unknown type and that `createFromNBT` returns `null`
  instead of NPE-ing (the fix noted in the test's own comment), so they pin the FIXED behaviour.
  The pattern's surviving instance is `integration/PacketSerializationTest.java:500`.
- **MECH-TST-08 — positive-control guard.** Known-bug pairs ship a happy-path positive control
  so a registry-wide breakage can't make the bug test pass vacuously. The satellite registry's
  pair has no such control — an unregistered-everything registry would pass both `null` assertions
  (`test/unit/SatelliteRegistryFallbackTest.java:40-58`).

## Subsystem → test coverage map

Each row: the gameplay subsystem, then the test classes that pin it, grouped by tier
(u=unit, i=integration, s=server, c=client). Class basenames only; `*E2ETest`/`*Test` suffix implied.

**This map has a hole, and it is the largest subsystem in the suite** `[V, measured 2026-08-22]`:
there is **no row for tier 2** — the VS-backed craft, its flight computer, crew capture, cells,
crossings and transits. the `VS*` prefix alone is **49 classes** (20 server + 29 client, counted
2026-08-22), none of them listed below, and that is a LOWER BOUND — tier-2 pins that do not carry the
prefix (`SpaceLoginRestore*`, `ShipArrivalKeepsItsPilotSeat*`, `InterstellarJumpLeg*`) are not in it.
A reader asking "what pins ship control?" is told nothing rather than told little.
Do not read a missing row here as missing coverage. Filling it is its own pass; until then the
tier-2 oracles are C8-C22 and `stellurgytest-probe-catalog`.

| subsystem | tests |
|-----------|-------|
| rocket-entity / free-flight | u:FreeFlight{Assists,Attitude,Input,Physics}, RocketFlightModeNbt, RocketInventoryHelperRedirect, RocketLoaderRedstonePolarity, StatsRocket, GravityHandlerApi, PlanetaryTravelHelper · s:FreeFlight{AssistsE2E,Cycle,LaunchGate,NbtRoundTrip}, Rocket{DescentLanding,DimensionTransition,EventPayloadContract,FlightCycleDepth,FlightCycleIntegration,FlightFailureModes,LaunchDepth,PreLaunchEventCancellation,RequireFuelDisableAssembles,StationCauseEffect}, LowGravFallDamage, ElevatorCapsuleStateAndNbt, HovercraftEntitySmoke · c:FreeFlightMode, HovercraftRide, ItemHovercraftSpawn, GuidanceComputerGui, RocketBuilderGui, ElevatorCapsuleRide |
| rocket-assembly | s:RocketAssemblySmoke, RocketAssemblerMiningDrillStat, NuclearEngineRocketAssembly, UvAssembler{DivergesFromRocketAssembler,OutputEntityClass}, SatelliteBuilderPressBuildContract |
| rocket-infrastructure (fueling/service/monitoring/railgun/loaders) | s:FuelingStationFuelsAdjacentRocket, RocketInfrastructure{LinkPersistence,Smoke}, RocketItemUnloaderActiveTransfer, RocketServiceStationLinkAndState, RocketMonitoringStationLaunchTrigger, MonitoringStationComparatorOverride, ServiceStation{AssemblerScan,BrokenPartScanContract,FullRepairCycle,UnlinkedPerformFunction}, FluidLoaderActiveTransfer, Railgun{CargoReceiveContract,FiringContract,Multiblock} · c:RailgunCargoTransit |
| multiblock-machines | s:{ArcFurnace,Centrifuge,ChemicalReactor,Crystallizer,Electrolyser,Lathe,PlatePress,PrecisionAssembler,PrecisionLaserEtcher,RollingMachine}RecipeEndToEnd, MachineRecipe{EndToEndKit,Integration}, MachineDomainSmokeSuite, AreaGravityController{Multiblock,FallDistanceReset}, Beacon{Multiblock,EnableCycle,LocationProbeSmoke}, BlackHoleGenerator{Multiblock,PoweredCycle}, MicrowaveReceiverMultiblock, Observatory Multiblock, OrbitalLaserDrill{ModeDispatch,Multiblock}, PlanetAnalyser{Multiblock,ResearchContract}, Solar{ArrayMultiblock,PanelInsolation}, WarpControllerDepth, MultiblockControllerPreAssembly, TileMachineDepth{,Round2}, Terraformer{MultiBlockCycle,Multiblock,PoweredCycleOnStellurgyPlanet,PoweredCycleOnOverworld}, Terraforming{Smoke,TerminalChipRecognition,TerminalSmoke} |
| infrastructure-tiles | s:FluidTank{NBTRoundTripsAcrossRestart,StackedFill}, ForceField{ProjectionSmoke,ProjectorProjectsAndRetracts}, TilePumpFillsFromAdjacentWaterSource, PipeNetwork{MultiBlock,Smoke}, CO2ScrubberComparatorOutput, OxygenVent{BoundedByBlobCap,RequiresFuelAndPower}, SpaceElevatorMultiblock, MicrowaveReceiverMultiblock · c:GasChargePadFillsPressureTank |
| space-stations | s:SpaceStation{Depth,DockUndock,LifecycleSmoke,PadPersistence}, StationControllers{Smoke,TickContract}, DockingPortNbtAndPacket, NonStellurgyDimensionIsolation · u:StationLandingLocation · c:SpaceDimGuard |
| atmosphere-oxygen | u:ArmorComponentContract, ItemAirUtils, SpaceArmor{Contract,ProtectionContract}, SpaceBreathingEnchantmentContract, SpecialPurposeItemContract, AirState · i:SealableBlockHandler · s:AtmosphereOxygenSmoke, AtmospherePlayerEvent, OxygenVent{BoundedByBlobCap,RequiresFuelAndPower}, VacuumGuards, CO2ScrubberComparatorOutput, SealDetectorDispatch, SuitWorkStationAssemblesSuit, LifeSupportZone, VentilationNetwork, BreachAndIsolation, SubsystemNetworkRestart, ShieldConsoleReportsCollapse · c:ItemAtmosphereAnalzerReadout, ItemSealDetectorPlayerMessages, ItemSpaceArmorUseFluid, OxygenSuitClientState, ItemSpaceChestSubInventoryDrain |
| ship-heat | the per-invariant scenario table lives in `ship-heat` (Test coverage) |
| ↳ *the pair worth knowing about*: `u:AirState` pins the gas arithmetic with no world at all; `s:LifeSupportZone` places the machines in one. Production defects in this subsystem are found by the second and invisible to the first — the arithmetic was never the part that was wrong. |
| dimension-planets | i:DimensionProperties, StellurgyDimensionWorldInfo, AstronomicalBodyHelperOrbitalTheta · u:AstronomicalBodyHelper, OreGenProperties · s:PlanetDimensionLoad, PlanetDefsFaultTolerance, PlanetXmlConfigIntegration, PerDimWorldInfoMasterToggle · c:PlanetBedSleepClientGroup, PlanetSelectorGui |
| world-gen / weather | s:Worldgen{DeterminismAndSampling,Smoke}, AsteroidDimensionContainsAsteroids, PerDimensionWeatherIsolation, PlanetWeatherGate, Weather{Baseline,CycleDisable,Persistence} · u:PlanetWeather{SavedData,State}, WeatherCommandRefusal · c:WeatherClientSync, WeatherCommandRedirect |
| util-core | u:WeightEngineUnit, SleepWakeTime, SpacePosition, XMLPlanetLoader · s:WeightSystem, WearSystem, WearAccrualDisable · i:XMLPlanetLoader |
| satellite | u:SatelliteProperties, SatelliteRegistryFallback, SatelliteWeatherAndMicrowaveNbt, ScanningSatelliteContract, ChipNBTRoundTrip, ItemDataCarrierNBTRoundTrip, BeaconFinderAndOreScannerContract, ScannerDetectorItemContract · i:ScanningSatelliteNameContract · s:SatelliteCoverageGaps, SatelliteIdChipPersistence, SatelliteLifecycleSmoke, SatelliteTerminalChipRecognition, SatelliteTickBehaviour, SatelliteTypeBehaviour, ScanningSatelliteTickContract · c:ItemBiomeChangerSatelliteAction, OreScannerRightClickClient |
| mission | u:MissionResourceCollectionContract · s:Mission{GasCompletion,InfrastructureLifecycle,LifecyclePyramid,OreCompletion,PersistenceRestart} |
| wirelessdata | s:WirelessTransceiver{Contract,Restart} |
| network-wire | i:PacketSerialization, DockingPortNbtAndPacket, ItemPackedStructureNbt · u:PacketSerialization, ItemPackedStructureNbtRoundTrip |
| commands-gameplay / world-cmd | s:CommandsSmoke, WorldCommand{GuardContract,PlanetLifecycleContract,PlanetSetGetContract,StarMiscContract}, SelectorServerSmoke · c:WorldCommand{Fetch,FetchModerator,PlayerEquipped} |
| event-handlers / advancements | s:EventHandlerWiring, PlayerEventHandlerWiring, AdvancementsTrigger · u:(via mixin) |
| mixins-asm-coremod | u:StellurgyMixinPlugin, StellurgyKeyConflictContext · c:InventoryBypassRedirect |
| api-public / config | u:StellurgyConfiguration, FuelRegistry · s:PersistenceRestartSmoke |
| items | u:JackHammerContract, ItemUpgradeSlotEligibility(s) · c:Item*RightClick/Use E2E (see atmosphere/satellite rows) |
| projectile-substrate | u:SweptSegment · s:ShotSubstrateE2E, ShotHitsShipHullE2E, DiagonalBoreE2E (shared with structural-damage: the traversal is) — the swept-traversal properties are pinned as GEOMETRY at the unit tier (nothing skipped, steps face-adjacent, parameters ordered); the server tier pins what only a running world can show: a flight that loads no chunks, a stated end reason, a fast round stopped by a one-block wall, and per-world isolation. The e2e asserts position against the shot's OWN age rather than a tick count — the harness server really ticks, so how many steps a shot has taken is not something a test gets to decide. `ShotHitsShipHullE2E` is the ship-frame leg and is the one test here that has been FALSIFIED rather than merely observed green: with the mapping-back-out disabled it goes red at the end-point assertion alone, with every earlier assertion still passing |
| harness self-test | c:ClientConnectSmoke, ModCountParity. No test pins the harness's own seed, the flat dry ground the client tier's ground fixtures stand on, or the probe's reply shape: their subject is the harness or the world's terrain, not a decision of ours, so a change that breaks them is caught only by the e2e that stands on them. |
| instrument calibration | u:TestEventLogRing — a chatty event type must not evict a rare one, and order must survive the per-type split; it builds its own `ServerEventLog` (`test/trace/ServerEventLog`). Not pinned: that the timeline every crossing e2e reads stays silent on ordinary motion, and that it can NAME the code that moved a player |

## State & persistence (contract-oracle role)

Tests independently exercise the same seams the contract docs own:
- **C1 NBT round-trip**: every `*NbtRoundTrip`/`*NBTRoundTrip`/`*Persistence`/`*Restart`
  test writes→reads→asserts equality (e.g. `unit/RocketFlightModeNbtTest`,
  `unit/ChipNBTRoundTripTest`, `server/FluidTankNBTRoundTripsAcrossRestartTest`,
  `server/MissionPersistenceRestartTest`). Restart tests use per-method
  `AbstractHeadlessServerTest` to force a real save/load cycle across two boots.
- **C2 packets**: `integration/PacketSerializationTest` + `unit/PacketSerializationTest`
  round-trip all wire packets and pin negative-input safety.
- **C3 registry**: `unit/FuelRegistryTest`, `unit/SatelliteRegistryFallbackTest`
- **C4 config**: `unit/StellurgyConfigurationTest`, plus `*DisableTest`/`*GateTest` pairs
  (`WearAccrualDisableTest`, `WeatherCycleDisableTest`, `RocketRequireFuelDisableAssemblesTest`,
  `PlanetWeatherGateTest`) — the rule that a config flag fully disables its mechanic.
- **C5 events**: `server/EventHandlerWiringTest`, `PlayerEventHandlerWiringTest`,
  `AdvancementsTriggerTest`.
- **C6 mixins/AT**: `unit/StellurgyMixinPluginTest`, `unit/StellurgyKeyConflictContextTest`,
  `client/InventoryBypassRedirectE2ETest`.

## Known-bug & disabled inventory

**The whole server tier, measured 2026-08-22**: 226 classes, 0 failures, **0 arrangement skips**, 2
parked (both `AdvancedFlightComputerTierGateTest`, deliberately retired 2026-08-21 on an unreachable
premise). Note what this kills: a `grep` for
`Assume.assumeFalse("No Stellurgy dimensions registered")` finds it in ten classes and reads as ten hidden
skips — none of them fire, because the harness does register Stellurgy dimensions. A guard's EXISTENCE says
nothing about whether it triggers.

`_documentsKnownBug` / bug-pinning tests (assert *current* behaviour, flip on fix):
- `unit/SatelliteRegistryFallbackTest.java:40,51` — not a bug-pin: `getNewSatellite` returns
  `null` by design and `createFromNBT` returns `null` rather than NPE-ing.
- `integration/PacketSerializationTest.java:500` `packetMoveRocketInSpaceDocumentsKnownBugs`
  — pins the unregistered `PacketMoveRocketInSpace`: `read()` NPEs on default-ctor instance;
  `hasWorld`/`hasStar` write inverted — of which the test pins the `read()` NPE only
  (`PacketSerializationTest.java:500-515`).

`@Ignore`d / superseded:
- `server/PlanetDimensionLoadTest.java:30` — `@Ignore` (hangs at suite scale,
  ~44th testServer class). **Live disabled coverage gap.**
- `client/InventoryBypassRedirectE2ETest.java:102` — un-ignored rewrite of an earlier ignored test.
- `server/AreaGravityControllerFallDistanceResetTest.java:22`, `ModRegistrationsAfterBootTest.java:23`,
  `TilePumpFillsFromAdjacentWaterSourceTest.java:27`, `unit/XMLPlanetLoaderTest.java:149` —
  each documents a former `@Ignore`d test now superseded by a discriminating server-tier version.

## Invariants

- **INV-TST-01 [V]** Server + client tiers `Assume.assumeTrue` on the harness-enabled system
  property and *skip* (not fail) when unset; headless CI runs only unit+integration.
  `test/server/AbstractSharedServerTest.java:73`, and the shared CLIENT base's own lazy boot
  (`client/AbstractSharedClientTest.java`, `ensureHarnessBooted`) — the guards live there, not in
  `@BeforeClass`, so the boot can ask the subclass what to seed.
- **INV-TST-02 [V]** `MinecraftBootstrap.ensure()` is idempotent under concurrency
  (double-checked `volatile done` + `synchronized`). `test/MinecraftBootstrap.java:39-88`.
- **INV-TST-03 [V]** Shared-harness subclasses must not leak cross-method state; the base
  class documents this as a hard contract and lists opt-outs (persistence/global-mutation
  tests stay per-method). `test/server/AbstractSharedServerTest.java:25-58`.
- **INV-TST-04 [V]** World mutations in server/client tiers go only through `/stellurgytest`
  (`client().execute`), never reflected Forge APIs. `test/server/AbstractSharedServerTest.java:71-72`.
- **INV-TST-05 [V]** The recipe kit hardcodes no item/fluid identity — it reads the first
  registered recipe and hatch positions from probe JSON, so it stays valid as recipes change.
  `test/server/MachineRecipeEndToEndKit.java:31-38`.
- **INV-TST-06 [V]** Known-bug tests pin current behaviour and are written to fail on fix
  (documented flip instruction), keeping the violation visible.
  `integration/PacketSerializationTest.java:500-515` (the satellite-registry pair does not pin a bug: see MECH-TST-07).
- **INV-TST-07 [A]** Each `*DisableTest`/`*GateTest` pins that a config flag disables both
  accrual *and* consequence (per §10). Assumed uniform across the ~5 pairs; only sampled
  (`WearAccrualDisableTest`, `WeatherCycleDisableTest`).
- **INV-TST-08 [V]** Test dim-ids (9001-9004) and world seed are fixed constants so
  worldgen/round-trip snapshots stay deterministic. `test/StellurgyTestConstants.java:16-22`.

## Failure modes & edge cases

- Shared harness: one method's server-JVM crash cascades — JUnit reports all remaining
  methods in the class failed against the same root cause
  (`AbstractSharedServerTest.java:60-65`).
- `PlanetDimensionLoadTest` is silently absent from CI (`@Ignore`) — the non-Stellurgy
  dimension-isolation contract has no active gate at suite scale.
- Known-bug tests invert normal semantics: a *green* suite means the bugs still exist;
  a red `*DocumentsKnownBug` means someone fixed the bug without flipping the test.

- **Wire bug (confirmed by test)** `PacketMoveRocketInSpace` unregistered + `read()` NPE +
  inverted `hasWorld`/`hasStar` — the `read()` NPE is pinned by `PacketSerializationTest.java:500`.
- **Coverage gap** `PlanetDimensionLoadTest` `@Ignore`d for suite-scale hang;
  non-Stellurgy dimension isolation has no running gate. `server/PlanetDimensionLoadTest.java:30`.
- **SatelliteRegistry SSOT/null-contract bug** `getNewSatellite` returns `null` vs javadoc
  `SatelliteDefunct`, cascading to `createFromNBT` NPE — the `createFromNBT` half is FIXED (returns `null`,
  `SatelliteRegistryFallbackTest.java:51-58`); whether `getNewSatellite` should return `SatelliteDefunct`
  is still open.

## Config surface

Harness system properties (not `StellurgyConfiguration`): `stellurgy.tests` (probe gate,
`StellurgyTestConstants:13`), `<PROP_HARNESS_ENABLED>` (server harness),
`forge.test.client.enabled` / `forge.test.client` (client bot). All default off → tiers skip.

## Open questions

- Exact string value of `AbstractHeadlessServerTest.PROP_HARNESS_ENABLED` and the client
  gate lives in the external `com.github.stannismod.forge.testing` lib (not in-repo) —
  referenced but not readable here.
- Whether every one of the ~5 `*DisableTest`/`*GateTest` pairs asserts consequence-disable
  (not just accrual-disable) is sampled, not exhaustively verified → INV-TST-07 is `[A]`.
- `SpecialPurposeItemContractTest`, `ItemUpgradeSlotEligibilityTest` subsystem assignment
  (items vs upgrades) inferred from name, not read.

## Weapons suites

| suite | tier | what it pins |
|---|---|---|
| `unit/GunSpecTest` (7) | unit | parts ADD UP; an empty build is not a gun; spread floors at true and the fire interval at one tick; a negative contribution is refused. The properties an addon's part relies on when it joins a build it knows nothing about |
| `unit/TurretMechanismTest` (8) | unit | the declared traverse rate is never exceeded; an out-of-arc command saturates VISIBLY instead of clamping silently; a jammed drive holds its bearing and still fires; a dead one does neither; the bearing survives a save |
| `server/TurretStandaloneTest` (7) | server | a gun with NO network fires at what it was pointed at; the round it fires is the one its build describes; a bare controller is not a gun; a dead drive stops it; a gun with its own hull in front of the barrel HOLDS (with the wall removed as a control); a gun aboard an unnamed ship does NOTHING — it does not even count its own build, because a ship's chunks load before its ship object exists and every coordinate it holds until then is a shipyard address. The last two were falsified against the unguarded build before being believed — both go red on their own assertions with the guards disabled |
| `server/TurretOnAShipTest` (1) | server | a gun in the SHIPYARD fires into the world: the round is located afterwards and must not be at a shipyard address. Falsified by skipping the muzzle conversion — the round then appears at x=5 120 293, exactly where the defect predicts |
| `client/TurretFriendOrFoeTest` (1) | client | a gun holds fire on a player carrying its access code and fires on one carrying somebody else's. Only reachable on a client: the credential is carried by a PLAYER, and a dedicated-server suite has none |
| `server/WeaponConsoleTest` (3) | server | one console points a whole battery; hold-fire stops the shooting and KEEPS the target (and releasing it resumes); losing the last console clears the target rather than leaving a battery firing at a point nobody can retract. Falsified twice: disabling the console-loss clear reddens only the third, and making guns ignore the network target reddens all three while leaving all six standalone tests green — which is the sharpest statement that the network is convenience and not capability |
| `client/TurretAimReachesClientTest` (1) | client | the bearing the server COMMANDS reaches the client and the client's own mount converges on it — the only honest observable for "a player can see where the gun points", since a renderer's output cannot be read from a test. Falsified against a build with the sync disabled |
| `unit/SignatureModelTest` (6) | unit | detection range and lock quality are two terms with two inputs: two targets shedding the SAME watts are noticed at the same distance and held sixteen-to-one differently; quality falls with the square of range; a cold target unresolvable by listening is held by illuminating; nothing is invisible. No constant is pinned — the SHAPE of each law is |
| `unit/TurretInterceptTest` (4) | unit | the lead's contract is a ARRIVAL, not a formula: the round and the target reach the aim point together, a still target is aimed at exactly, a receding one is led further out and an approaching one nearer, and a gun with no muzzle speed leads nothing instead of dividing by zero |
| `server/FireControlSensorTest` (2) | server | a battery nobody told anything engages a hostile its own sensor found — with acquisition DISABLED as the control in the same run, same battery, same zombie; and a cool target too far to hold by listening is tracked without being shot at until the sensor illuminates it. Falsified twice: forcing the lock gate open reddens only the second (six rounds into a contact held at 0.038), and disabling the sensor's publish reddens both |
| `client/SensorFriendIsNeverAcquiredTest` (1) | client | an ally never enters the target LIST — the probe is asked whether THIS player is a contact rather than whether the list is empty, and the control flips only the code carried. Falsified: with the screen removed the code-carrying player is acquired at quality 0.30 |
| `server/ShieldConsoleReportsCollapseTest` (1) | server | a console stops reporting a network that lost its last source, and does NOT wipe the setting it owns while doing so. Cherry-picked with the shield network move; it is the reason the console keeps its resistance bias across a world load |
| `server/TurretDamageDegradesTest` (1) | server | a gun shot through the damage engine's own entry point turns slowly, then seizes, and still fires down the bearing it stopped at — each rung asserted against the one before it. Falsified: with the condition read removed only this test reddens and all seven standalone gun tests stay green |
| `unit/TurretConditionTest` (5) · `unit/GunPartConditionTest` (5) | unit | the ladder goes one way and never improves; an explicit state outranks condition in both directions; a damaged part gives less of what it gives, negative contributions included |
| `client/ShotReachesClientTest` (1) | client | a round fired 40 blocks from the player is drawn by the real client, and one fired four kilometres away is NOT — the second half is what makes the first mean anything |

The turret e2e polls rather than counting ticks (the harness server really runs), and its dead-drive
case waits for the gun to be assembled BEFORE killing the drive — an un-assembled gun is silent too,
and without that wait the test would pass without ever exercising its subject.
