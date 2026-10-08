# api-public / config — `StellurgyConfiguration`

Single file: `api/StellurgyConfiguration.java` (1096 sloc). This is the C4 config surface. The
canonical, de-duplicated flag catalogue is [`30-contracts/C4-config-surface.md`]; this doc
describes the *mechanics* (how the object is loaded, synced, copied) and lists the owned flags
by category so P2 coverage is complete.

## Model

One flat mutable object. Every field tagged `@ConfigProperty` is part of the surface
(`StellurgyConfiguration:1086`). A private `static final ownConfig` is this process's own
configuration, read from its file at pre-init and never replaced. `getCurrentConfig()` answers the
CALLER's side through the proxy (`configInForce`): a server's own; on a client the configuration the
connected server sent (kept by the client's view of that server, `ServerView.serverConfig`), else its
own — a local server sends none, and runs this very configuration.

## Mechanics

### MECH-API-01 — disk load (`loadPreInit` / `loadPostInit`)
`loadPreInit` reads every option from the Forge `Configuration` file into the current config, using
per-category string constants `WORLDGEN/ROCKET/STATION/PLANET/OXYGEN/ENERGY/MISSION/PERFORMANCE/CLIENT/COMPAT`
(`:45-54`, body `:405-596`). A few options are written straight into *other* subsystems' statics
rather than a field: `AtmosphereVacuum.damageValue` (`:443`), `DimensionManager.dimOffset` from
`minDimension` (`:489`). `loadPostInit` (`:598-854`) resolves the string lists collected in preinit
into live objects — registers fuels into `FuelRegistry`, sealable/torch/rocket-blacklist blocks,
black-hole generator items, and entity atmosphere-bypass classes —
then nulls the temporary string arrays to free memory.

- Trigger: FML preinit / postinit. State: mutates `currentConfig` + downstream registries.
- The ore master toggle `EnableOreGen` gates every per-ore `generate*` with `&& masterToggle` (`:553`).

### MECH-API-02 — network sync, write (`writeConfigToNetwork`)
Server serialises **only** `needsSync=true` fields into a `PacketBuffer` (`:856-885`). Fields are
sorted by name (`getDeclaredFields` is unordered, `:867`) and each is written as
`int fieldNameHashCode` then the value via `writeDatum`. `writeDatum` (`:887`) handles int/float/
double/boolean/String/`Asteroid`/List/Set/Map, using the annotation's `internalType`/`keyType`/
`valueType` for element types. A trailing `MAGIC_CODE` byte + `MAGIC_CODE_PT2` long frame the packet
(`:881-882`).

### MECH-API-03 — network sync, read (`readConfigFromNetwork`)
Client reads the same sorted `needsSync` field list; for each it reads an int and **bails
(returns `this`) if it does not equal the field-name hashCode** (`:1038-1039`), then reads the datum
and sets the field. Terminates on the magic sentinel (`:1050`).

### MECH-API-04 — the server's configuration on a client
On join, `PacketConfigSync` hands the deserialised server config to the client's view of the server
(`proxy.adoptServerConfig` → `ServerView.adoptServerConfig`, loud on a second adopt —
`ServerView.java:89` `[V]`); it is in force for client-thread readers while that view lasts and goes
with it — nothing is swapped or restored. `save()` writes only `ownConfig`, so a client never overwrites its file with server values.

### MECH-API-05 — reflective copy (`StellurgyConfiguration(StellurgyConfiguration)`)
Copy constructor iterates `@ConfigProperty` fields sorted by name and shallow-copies each
(`:346-379`). It *intends* to deep-copy Map fields but the guard tests `field.getClass()` (always
`java.lang.reflect.Field`) instead of `field.getType()`, so the branch is dead .

## Invariants

- **INV-API-01 [V][SYS]** Only `needsSync=true` fields cross the wire; both write and read iterate the
  identical name-sorted filtered list, so order matches by construction (`StellurgyConfiguration:862-867`,
  `:1028-1033`). FOR: wire format: synced config fields (INV-NW-01).
- **INV-API-02 [V][SYS]** The wire key per field is `field.getName().hashCode()` (a 32-bit int), not the
  disk option string; renaming a synced field silently changes its wire id (`:872`, `:1037`). Safe
  only because client and server run the same jar within a session. FOR: wire format: synced config fields (INV-NW-01).
- **INV-API-03 [V][SYS]** `spaceDimId` defaults to `-2` and is the contract dimension id for all space
  stations. (`MoonId` is not config: it is the galaxy's `getMoonId()`.) FOR: public API: the space dimension id is a promise to dependent mods.
- **INV-API-04 [V][BEH]** `stationSize` (build radius, default 1024) is documented as save-breaking if
  changed after stations exist (`:458`); it is synced so monitors show the server value.
- **INV-API-05 [V][SYS]** `readConfigFromNetwork` returns early (`return this`) on the first field whose
  hashCode does not match (`:1038-1039`), and the terminator loop
  `while(readByte()!=MAGIC_CODE && readLong()==MAGIC_CODE_PT2)` short-circuits *before* reading the
  long once the magic byte is seen (`:1050`) — so a mismatch leaves the buffer partially consumed and
  the trailing long unread. Benign only because the config packet is the whole payload. FOR: wire format: synced config fields (INV-NW-01).

## Owned flag catalogue (all `@ConfigProperty`)

`†` = `needsSync=true`. Disk keys live in `loadPreInit`; values are `tunable` unless marked contract.

**Rockets** (`ROCKET`, `:515-550`): `rocketRequireFuel`, `canBeFueledByHand`,
`nuclearRocketsRespectArtifactGating†`, `nuclearRocketsRequireArtifactForGatedStations†`,
`rocketThrustMultiplier†`, `fuelCapacityMultiplier†`, `nuclearCoreThrustRatio†`,
`automaticRetroRockets`, `orbit†` (contract: orbit height), `stationClearanceHeight†`,
`transBodyInjection†`, `asteroidTBIBurnMult†`, `warpTBIBurnMult†`, `experimentalSpaceFlight†`,
`gravityAffectsFuel`, `launchingDestroysBlocks†`, `buildSpeedMultiplier†`, `advancedWeightSystem`,
`contentMassScale†`, `fuelMassScale†`, `minLaunchTWR†`,
`wearThrustPenaltyMax†`, `wearWarnProbability†`, `wearCriticalBlocksLaunch†`,
`serviceStationStandaloneRepairMultiplier†`, `wearTankLeakChanceMax†`, `wearTankLeakFuelLoss†`,
`wearSeatBlockStageFraction†`, `partsWearSystem`, `increaseWearIntensityProb`, `blackListRocketBlocks`.

**World & ore gen** (`WORLDGEN`, `:552-585`): `generateCopper`,`copperPerChunk`,`copperClumpSize`,
`generateTin`,`tinPerChunk`,`tinClumpSize`, `generateDilithium`,`dilithiumClumpSize`,
`dilithiumPerChunk`,`dilithiumPerChunkMoon`, `aluminumPerChunk` (no annotation — disk-loaded but not
part of surface), `aluminumClumpSize`,`generateAluminum`, `generateIridium`,`IridiumClumpSize`,
`IridiumPerChunk`, `generateRutile`,`rutilePerChunk`,`rutileClumpSize`, `generateGeodes`,
`geodeBaseSize`,`geodeVariation`,`geodeOresBlackList`,`standardGeodeOres`, `laserDrillOresBlackList`,
`standardLaserDrillOres`, `generateCraters`,`generateVolcanos`,`generateVanillaStructures`.

**Station** (`STATION`, `:456-461`): `spaceDimId†` (contract), `stationSize†` (contract),
`allowZeroGSpacestations`, `travelTimeMultiplier`, `stationClearanceHeight†`,
`dataBusBigMultiplier†`.

**Planet** (`PLANET`, `:486-500`): `planetsMustBeDiscovered†`, `planetDiscoveryChance`,
`canPlayerRespawnInSpace`, `forcePlayerRespawnInSpace`, `perDimWorldInfo`, `enableCustomPlanetWeather`,
`logPlanetWeatherWrapping`, `forcePlanetWeatherWorldInfoWrapper`, `minAtmosphereDensityForRain†`,
`acidRainDamage`, `acidRainDamageInterval`, `blackListAllVanillaBiomes`, `maxBiomesPerPlanet`,
`minDimension`, and the telescope-survey block
`telescopeLimitingMagnitude†`, `telescopeConeHalfAngleDegrees†`, `telescopeScanMaxCells†`,
`telescopeScanBaseTicks†`, `telescopeScanCellsPerStep†`, `telescopeSurveyDataPerStep†`,
`telescopePassiveRadiusSteps†` (a survey is a CONE and its reach is DERIVED
from the aperture; there is no horizon, patch half-width or per-light-year tick rate —
see C4).

**Oxygen** (`OXYGEN`, `:441-454`): `enableOxygen`, `overrideGCAir`, `oxygenVentConsumptionMult`,
`oxygenVentPowerMultiplier`, `spaceSuitOxygenTime`, `suitTankCapacity`, `scrubberRequiresCartrige`,
`dropExTorches`, `torchBlocks`, `atmosphereHandleBitMask` (PERFORMANCE), `oxygenVentSize` (PERFORMANCE),
`enableNausea` (CLIENT).

**Energy** (`ENERGY`, `:479-484`): `solarGeneratorMult`, `microwaveRecieverMulitplier`,
`defaultItemTimeBlackHole`, `blackHolePowerMultiplier`, `blackHoleGeneratorBlocks`,
`electricPlantsSpawnLightning` (CLIENT).

**Missions** (`MISSION`, `:463-476`): `asteroidMiningTimeMult`, `gasCollectionMult†`,
`gasHarvestAmountMultiplier†`, `gasHarvestInfinite†`. (`asteroidTypes` is not config.)

**Terraform/laser/general** (`GENERAL`, `:410-438`): `allowMakingItemsForOtherMods`,
`allowSawmillVanillaWood`, `lowGravityBoots`, `jetPackThrust`, `blockTankCapacity`,
`blockEnergyHatchCapacityMultiplier`, `blockLiquidHatchCapacityMultiplier`, `crystalliserMaximumGravity`,
`enableLaserDrill`, `spaceLaserPowerMult`, `laserDrillPlanet`, `laserBlackListDims`,
`enableTerraforming`, `terraformSpeed†`, `terraformRequiresFluid`, `terraformliquidRate`,
`allowTerraformNonStellurgy`, `enableGravityController`, `allowNonStellurgyBiomesInTerraforming`,
`enableOrbitalRegistry`, `lavaCentrifugeOutputs`, `lavaCentrifugePower`, `lavaCentrifugeTime`.

**Client** (`CLIENT`, `:502-509`): `stationSkyOverride`, `planetSkyOverride`, `skyOverride`,
`advancedVFX`.

**Declared but unbound in `loadPreInit`** (default-valued fields with no disk read found):
`asteroidTBIBurnMult`/`warpTBIBurnMult` (bound `:531-532`), `terraformPlanetSpeed`,
`overworldsealevelterraforming`, `allowTerraformNonStellurgy` — `terraformPlanetSpeed` and
`overworldsealevelterraforming` have commented-out loaders (`:432,:506`) so they keep their zero/false
defaults. Both are dead config fields.

## Failure modes & edge cases

- Copy ctor never deep-copies collections; mutating a copied config's list/map mutates the
  source's.
- A `needsSync` field whose type is not handled by `writeDatum` throws `InvalidClassException` at
  sync time (`:941`).
- Config sync read desyncs the remainder of the buffer on any hash mismatch (INV-API-05).

## Config surface

This whole doc *is* the config surface for the subsystem; all owned flags are listed above. Global
disable paths: `enableOxygen=false` disables atmosphere damage; `partsWearSystem`/`advancedWeightSystem`
gate the wear/TWR systems (consumed in rocket-entity & rocket-assembly, cross-checked there);
`EnableOreGen=false` master-disables all ore gen; `perDimWorldInfo=false` un-weaves the planet weather
mixins entirely (`:492`).

## Open questions

- Whether any live path actually calls the copy constructor `StellurgyConfiguration(StellurgyConfiguration)` (no
  in-tree caller found) — if unused, the deep-copy bug is latent only.
