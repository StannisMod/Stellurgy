---
id: C4-config-surface
covers: [api/StellurgyConfiguration.java]
confidence: high
---

# Contract C4 — Config surface (`StellurgyConfiguration`)

> **The table can lag the source.** The coverage checker (`docs/system/tools/check-coverage.py`)
> lists the `@ConfigProperty` fields no row names. Treat a flag's ABSENCE here as "not yet audited",
> never as "does not exist".

Consolidates the field-level `@ConfigProperty` annotations of `StellurgyConfiguration` with the mechanics catalogue in
[`20-subsystems/api-public/config.md`]. That doc owns the **loading/sync/copy mechanics**
(MECH-API-01…05, INV-API-01…05); this contract owns the **flag → mechanic → full-disable-path**
matrix and the cross-subsystem collisions.

Load/sync facts reused (not re-derived): flags are read into the live singleton in `loadPreInit`
(MECH-API-01) and resolved into registries/lists in `loadPostInit`; only `needsSync=true` fields cross
the wire keyed by `field.getName().hashCode()` (MECH-API-02/03, INV-API-02). Line numbers are
deliberately absent from this matrix: find a flag's readers with a grep for its field name.

## Legend

- **Type** is the Java field type. `†` = `needsSync=true` (server value overrides client on join).
- **Disable path** semantics (a flag must fully disable its mechanic):
  - *boolean gate* → the flag, when off, must return the vanilla/classic baseline (both **accrual**
    and **consequence** gated). Verified gates are cited; a gate that only covers one side is a
    **FLAG**.
  - *multiplier / scalar* → has **no true off-switch**; the calculation always runs. Neutral value
    noted where one exists (identity `1.0`, or a documented "set 0 to disable"). Marked
    `tunable — no disable semantics` otherwise. Balance numbers are tunable, never contractual.
  - **contract** = value other subsystems hardcode-assume; changing it is save/protocol-breaking.
- **FLAG** column: `⚑ DEAD` (declared but no functional read), `⚑ SPLIT` (accrual gated but not
  consequence, or vice-versa), `⚑ STALE` (captured into a static at class-load, disconnected from the
  live singleton / from sync). Empty = clean.

## Rockets (`ROCKET`; owned by rocket-entity MECH-RKT-*, rocket-assembly MECH-RASM-*)

> **There is no `weightMaterialScale` flag** (C10 STAT-13). A multiplier applied by one consumer and
> not the other means two answers to "what does this block weigh", and the block-weight table is
> already player-editable (`config/advRocketry/weights.json`), so such a knob would duplicate it.


| Flag | Type | Disk key | Gates (consumer) | Disable path | FLAG |
|---|---|---|---|---|---|
| rocketRequireFuel | boolean† | rocketsRequireFuel | fuel requirement for flight (MECH-RKT fuel gate) | `false` = fly without fuel (classic) | |
| canBeFueledByHand | boolean | canBeFueledByHand | hand-fuelling of rockets | `false` = no manual fuel | |
| nuclearRocketsRespectArtifactGating | boolean† | nuclearRocketsRespectArtifactGating | nuclear-rocket artifact gating (mission) | `false` = ignore gating | |
| nuclearRocketsRequireArtifactForGatedStations | boolean† | nuclearRocketsRequireArtifactForGatedStations | station-destination artifact rule | `false` = stations exempt | |
| rocketThrustMultiplier | double† | thrustMultiplier | engine thrust scale (StatsRocket) | identity `1.0`; tunable — no off | |
| fuelCapacityMultiplier | double† | fuelCapacityMultiplier | tank capacity scale | identity `1.0`; tunable — no off | |
| nuclearCoreThrustRatio | double† | nuclearCoreThrustRatio | nuclear core thrust scale | identity `1.0`; tunable | |
| automaticRetroRockets | boolean | autoRetroRockets | auto retro-burn on reentry | `false` = disabled | |
| ~~orbit~~ | — | ~~orbitHeight~~ | **No such flag**: the orbit line is per WORLD — the planet file's `<orbitHeight>`, else derived from the body's radius (`metric-boundary` MECH-MET-02); ships, tier-1 rockets, the elevator and the HUD all read it | — | |
| stationClearanceHeight | int† | stationClearance | station clearance burn | author-warned: **not synced with `orbit`**, wrong on monitors if unequal | |
| transBodyInjection | int† | transBodyInjection | TBI burn distance | author-warned: **not read by flight-worthiness machines** — see Collisions | |
| asteroidTBIBurnMult | float† | asteroidTBIBurnMult | asteroid TBI distance | identity `1.0`; tunable | |
| warpTBIBurnMult | float† | warpTBIBurnMult | warp TBI distance | identity; tunable | |
| experimentalSpaceFlight | boolean† | experimentalSpaceFlight | free-flight-in-space | `false` = classic flight | |
| gravityAffectsFuel | boolean | gravityAffectsFuels | gravity term in accel (StatsRocket) | `false` = gravity-independent | |
| launchingDestroysBlocks | boolean | launchBlockDestruction | launch block damage (EntityRocket) | `false` = no damage | |
| buildSpeedMultiplier | float† | buildSpeedMultiplier | assembler speed (rocket-assembly) | identity `1.0`; tunable | |
| advancedWeightSystem | boolean | advancedWeightSystem | weight calc + TWR gate; gated in StatsRocket, EntityRocket, assembler, UVA, StorageChunk | `false` = classic (both calc and launch decision gated) | |
| contentMassScale | double | contentMassScale | multiplier on held content (inventories, tanks); blocks and people unscaled | default `1.0`; `0` = weightless cargo; negative/NaN refused (logged, 1.0 used); synced | |
| fuelMassScale | double† | fuelMassScale | fuel-mass multiplier | identity `1.0`; tunable | |
| minLaunchTWR | double† | minLaunchTWR | min thrust/weight to launch | tunable; `advancedWeightSystem=false` bypasses TWR gate | |
| wearThrustPenaltyMax | double† | wearThrustPenaltyMax | motor thrust loss with CONDITION (wear or battle damage alike) | `0` = no penalty. **Not gated by `partsWearSystem`** — one stage axis, so gating the consequence would exempt battle damage | |
| wearWarnProbability | double† | wearWarnProbability | pre-launch condition warn / block threshold (EntityRocket) | tunable; **not disabled by `partsWearSystem`** | |
| wearCriticalBlocksLaunch | boolean† | wearCriticalBlocksLaunch | refuse launch on critical wear (EntityRocket) | `false` = warn-only | |
| serviceStationStandaloneRepairMultiplier | double† | serviceStationStandaloneRepairMultiplier | standalone repair cost | tunable | |
| repairCostPerStageFraction | double† | repairCostPerStageFraction | share of a block's recipe charged per FULL hand repair, spread over its stages (C20 REPAIR-3) | tunable; `1.0` = a full weld costs about what the block costs | |
| repairWelderEnergyPerStage | int† | repairWelderEnergyPerStage | welder charge spent per stage removed | tunable; `0` = welding is free of charge, materials still charged | |
| repairWelderCapacity | int† | repairWelderCapacity | welder charge when full | tunable | |
| wearTankLeakChanceMax | double† | wearTankLeakChanceMax | damaged-tank leak chance (EntityRocket) | `0` = no leak. **Not gated by `partsWearSystem`** | |
| wearTankLeakFuelLoss | double† | wearTankLeakFuelLoss | leak fuel loss fraction (EntityRocket) | `0` = no loss | |
| wearSeatBlockStageFraction | double† | wearSeatBlockStageFraction | damaged-seat crewed-launch block (EntityRocket) | tunable; **not gated by `partsWearSystem`** — a shot-up seat is unsafe whatever the wear flag says | |
| partsWearSystem | boolean | partsWearSystem | wear **ACCRUAL ONLY** (`StorageChunk.damageParts`) | `false` = no part ever advances a stage from use, so nothing to consequence. It may NOT gate a consequence: damage and wear share one stage axis, so a consequence-side gate would make battle damage free and ships unkillable | |
| increaseWearIntensityProb | double | increaseWearIntensityProb | per-part wear gain chance | `0` = no accrual; else `partsWearSystem=false` | |
| blackListRocketBlocks | List<Block> | rocketBlockBlackList | blocks forbidden in rockets (postInit) | empty list = allow all | |

## Weapons (`WEAPONS`; owned by projectile-substrate MECH-SHOT-*)

| flag | type | config key | governs | disable path | FLAG |
|---|---|---|---|---|---|
| ~~enableWeapons~~ | — | — | **There is no weapons gate**, by maintainer ruling 2026-10-03: *"Убираем этот гейт, слишком жирно. Война всегда включена."* (remove this gate, too heavy; the war is always on). A config file still carrying the key is ignored. Combat has no switch. The anchors that described a gate are retired in place (INV-SHOT-06, MECH-SHOT-31, MECH-GUN-35) | none — there is nothing to disable |
| shotReflectionSpeedFloor | double† | shotReflectionSpeedFloor | speed under which a shield-mirrored shot is ENDED at the shell (MECH-SHOT-06) | tunable; `0` = a reflected shot is never ended for slowness | |
| shotPenetrationSpeedFloor | double† | shotPenetrationSpeedFloor | speed under which a round BORING through a hull is treated as having come to rest inside it | tunable; `0` = a spent round creeps forward forever instead of stopping. Twin of the row above, read in the same method, and annotated like it so the copy constructor and the sync see it | |
| shotBodyRadiusCap | double† | shotBodyRadiusCap | the widest a shot's body is SWEPT as, in blocks (MECH-SHOT-25) | tunable, 0..8; caps the geometry only — the declared cross-section still prices the shot, so `0` makes every round meet the world as a line without making any of them cheaper | |
| ablationResistanceFactor | double† | ablationResistanceFactor | how much dearer a block is to boil away than to push through, when it has no ablation row (MECH-DMG-23) | tunable, 0.01..1000; `1.0` collapses the two columns and makes a beam dig exactly like a slug | |
| beamAblationIntensityThreshold | double† | beamAblationIntensityThreshold | energy per unit of a beam's cross-section below which it removes nothing and is absorbed (MECH-DMG-24) | tunable; ships at `50000`, above the affordability line of metal (~38 500 for an iron block). `0` disables it and a sub-threshold beam then passes clean through the plate with all it arrived with | |
| ricochetIncidenceDegrees | double† | ricochetIncidenceDegrees | how glancing a hit must be before a solid round skips off METAL (MECH-SHOT-26) | tunable, 0..90; `90` disables ricochet entirely, `0` makes every hit on metal a bounce | |
| ricochetRestitution | double† | ricochetRestitution | how much of its speed a ricocheting round keeps (MECH-SHOT-26) | tunable, 0..1; below 1 a bounce costs something, which is what stops a round skipping between two plates forever | |
| maxShotsPerWorld | int† | maxShotsPerWorld | how many shots one world carries at once (MECH-SHOT-10) | tunable, minimum 1; over the cap a launch is REFUSED, never traded against a round already in flight | |

## World & ore gen (`WORLDGEN`; owned by world-gen MECH-WGEN-*)

| Flag | Type | Disk key | Gates | Disable path | FLAG |
|---|---|---|---|---|---|
| generateCopper | boolean | GenerateCopper (`&& EnableOreGen`) | copper ore gen | `false` **or** `EnableOreGen=false` | |
| copperPerChunk / copperClumpSize | int | CopperPerChunk / CopperPerClump | copper vein density/size | tunable | |
| generateTin | boolean | GenerateTin `&& master` | tin ore gen | `false`/master | |
| tinPerChunk / tinClumpSize | int | TinPerChunk / TinPerClump | tin density/size | tunable | |
| generateDilithium | boolean | generateDilithium `&& master` | dilithium ore gen | `false`/master | |
| dilithiumClumpSize / dilithiumPerChunk / dilithiumPerChunkMoon | int | DilithiumPerClump / DilithiumPerChunk / DilithiumPerChunkLuna | dilithium density/size | tunable | |
| generateAluminum | boolean | generateAluminum `&& master` | aluminium ore gen | `false`/master | |
| aluminumClumpSize | int | AluminumPerClump | aluminium size | tunable | |
| aluminumPerChunk | int | AluminumPerChunk | aluminium density | tunable | |
| generateRutile | boolean | GenerateRutile `&& master` | rutile ore gen | `false`/master | |
| rutilePerChunk / rutileClumpSize | int | RutilePerChunk / RutilePerClump | rutile density/size | tunable | |
| generateIridium | boolean | generateIridium `&& master` | iridium ore gen (default off) | `false`/master | |
| IridiumClumpSize / IridiumPerChunk | int | IridiumPerClump / IridiumPerChunk | iridium density/size | tunable | |
| laserDrillOresBlackList | boolean | laserDrillOres_blacklist | list-mode for standard laser ores (resolved in `loadPostInit`) | flips whitelist/blacklist; not a mechanic toggle | |
| geodeOresBlackList | boolean | geodeOres_blacklist | list-mode for standard geode ores (`loadPostInit`) | flips whitelist/blacklist | |
| generateGeodes | boolean | generateGeodes | geode gen (overrides planetDefs) | `false` = none | |
| geodeBaseSize / geodeVariation | int | geodeBaseSize / geodeVariation | geode size | tunable | |
| standardGeodeOres | List<String> | (from geodeOres) | default geode ore pool (MapGenGeode) | list content | |
| standardLaserDrillOres | List<String> | (from laserDrillOres) | default laser ore pool (VoidDrill, JEI) | list content | |
| generateCraters | boolean | generateCraters | crater gen (overrides planetDefs) | `false` = none | |
| generateVolcanos | boolean | generateVolcanos | volcano gen | `false` = none | |
| generateVanillaStructures | boolean | generateVanillaStructures | vanilla structures on planets | `false` = none | |

## Station (`STATION`; owned by space-stations MECH-STN-*)

| Flag | Type | Disk key | Gates | Disable path | FLAG |
|---|---|---|---|---|---|
| spaceDimId | int† | spaceStationId | space-station dimension id (**contract**; default `-2`) | contract — INV-API-03 | |
| stationSize | int† | SpaceStationBuildRadius | build radius (**contract**, save-breaking, INV-API-04) | contract — do not change post-station | |
| allowZeroGSpacestations | boolean | allowZeroGSpacestations | zero-g station option | `false` = min gravity kept | |
| fuelPointsPerDilithium | int | pointsPerDilithium | warp fuel per crystal | tunable | |
| travelTimeMultiplier | float | warpTravelTime | warp travel time | identity `1.0`; tunable | |
| dataBusBigMultiplier | int† | dataBusBigMultiplier (GENERAL) | advanced data-bus capacity (ItemBlockDataBusBig, TileDataBusBig) | tunable | |

## Planet (`PLANET`; owned by dimension-planets MECH-DIM-*, weather MECH-*)

| Flag | Type | Disk key | Gates | Disable path | FLAG |
|---|---|---|---|---|---|
| planetsMustBeDiscovered | boolean† | planetsMustBeDiscovered | discovery gating in warp controller | `false` = all visible | |
| planetDiscoveryChance | int | planetDiscoveryChance | 1/n discovery chance | tunable | |
| canPlayerRespawnInSpace | boolean | allowPlanetRespawn | bed respawn on breathable planets | `false` = no | |
| forcePlayerRespawnInSpace | boolean† | forcePlanetRespawn | bed respawn even without air | `false` = no (needs allowPlanetRespawn) | |
| perDimWorldInfo | boolean | perDimWorldInfo | **master** for per-dim WorldInfo (weather+time mixins). `false` = mixins not woven (classic) | `false` = full un-weave; see Rule 4 / mixins-asm-coremod | |
| enableCustomPlanetWeather | boolean | enableCustomPlanetWeather | per-planet weather; sub-toggle of perDimWorldInfo; also gates mixin weave (StellurgyMixinPlugin) + PlanetWeatherManager | `false` = weather delegates to overworld | |
| logPlanetWeatherWrapping | boolean | logPlanetWeatherWrapping | diagnostic logging | `false` = quiet | |
| forcePlanetWeatherWorldInfoWrapper | boolean | forcePlanetWeatherWorldInfoWrapper | force wrap on every secondary dim (PlanetWeatherManager) | `false` = normal | |
| minAtmosphereDensityForRain | float† | minAtmosphereDensityForRain | rain/thunder density threshold | tunable | |
| acidRainDamage | float | acidRainDamage | acid-rain damage | `0` = disable acid-rain damage | |
| acidRainDamageInterval | int | acidRainDamageInterval | acid-rain damage cadence | tunable | |
| blackListAllVanillaBiomes | boolean | blackListVanillaBiomes | vanilla biomes on planets | `true` = block vanilla biomes | |
| maxBiomesPerPlanet | int | maxBiomesPerPlanet | biome cap per planet | tunable | |
| ~~initiallyKnownPlanets~~ | — | — | **No such flag**: per-galaxy state, `DimensionManager.getInitiallyKnownPlanets()` | — | |
| ~~MoonId~~ | — | — | **No such flag**: per-galaxy state, `DimensionManager.getMoonId()` | — | |
| minDimension | int | minDimension (Planet) | lowest planet dimension id; seeds each new manager | tunable | |

### Telescope survey

**There is no reach flag.** An instrument reaches a BRIGHTNESS, and how far
that carries is derived from the aperture against the brightest archetype the active generator can
produce (`StellarMagnitude.instrumentReachLightYears`). A configured length was the wrong quantity
twice over: it made one number stand for a red dwarf and a blue giant, whose ranges differ by eighty
times, and it moved whenever the star spacing or the cell edge was retuned (a reach of `24` cells
reads as 0.16 AU, so no aim inside the horizon could leave the solar system). What remains
configurable is the aperture, the opening of the cone, and what a step costs in time.
`RegionScan.Tuning.fromConfig` (`RegionScan.java`) is the only reader.

| Flag | Type | Disk key | Gates | Disable path | FLAG |
|---|---|---|---|---|---|
| telescopeLimitingMagnitude | double† | telescopeLimitingMagnitude | how faint a star registers, in APPARENT MAGNITUDE (smaller is brighter); the instrument's REACH is derived from it | tunable — an aperture has no off | |
| telescopeConeHalfAngleDegrees | double† | telescopeConeHalfAngleDegrees | how wide a patch of sky one pointing covers, axis to edge; work grows as the SQUARE | tunable — min 0.001° | |
| telescopeResolveMarginMagnitudes | double† | telescopeResolveMarginMagnitudes | how much BRIGHTER than `telescopeLimitingMagnitude` a system must be before the instrument can make out what is IN it; DERIVED, not chosen — detection at SNR ~5 against a usable spectrum at ~100, and SNR grows as the square root of the photons, so the flux ratio is 400 = **6.5 mag** | **`0` = anything detectable is also resolvable** — no detection/resolution distinction | |
| telescopeScanMaxCells | int† | telescopeScanMaxCells | ceiling on how many LOOKS one survey holds (INV-SCAN); a pointing that would exceed it is SHORTENED | tunable — never unbounded | |
| telescopeScanBaseTicks | int† | telescopeScanBaseTicks | cost of one step; only with `planetsMustBeDiscovered` | `0` = free step | |
| telescopeScanCellsPerStep | int† | telescopeScanCellsPerStep | how many looks one step resolves (INV-SCAN's per-step bound) | tunable — min 1 | |
| telescopeSurveyDataPerStep | int† | telescopeSurveyDataPerStep | distance data one step consumes; too little = the sweep stalls | `0` (default) = a survey costs nothing | |
| telescopePassiveRadiusSteps | int† | telescopePassiveRadiusSteps | radius of the passive local radar, in STAR TERRITORIES | `0` = the observatory's own territory only | |
| telescopeObscuredAtMagnitudes | double† | telescopeObscuredAtMagnitudes | how much dust a survey sees THROUGH, in magnitudes of visual extinction; past it a look writes the bare coordinate instead of the system's bodies | **`0` = concealment off** — every look resolves whatever dust stands in the way | |

**`telescopeObscuredAtMagnitudes` is a threshold stated in a physical unit.** It is stated in the unit
astronomy measures dust in, and its default (5) is the real boundary at which faint objects behind a
cloud disappear — not a number chosen to feel right. The CALIBRATION
that turns this model's density into magnitudes is deliberately **not** here: it is
`Nebula.MAGNITUDES_PER_DENSITY_LIGHT_YEAR`, a constant with its anchor written out, because moving it
would silently redefine what every magnitude-stated threshold means.

## Oxygen (`OXYGEN`; owned by atmosphere-oxygen MECH-ATM-*/MECH-SEAL-*)

| Flag | Type | Disk key | Gates | Disable path | FLAG |
|---|---|---|---|---|---|
| enableOxygen | boolean | EnableAtmosphericEffects | atmosphere effects/damage master (AtmosphereHandler) | `false` = no atmosphere damage | |
| overrideGCAir | boolean | OverrideGCAir | replace Galacticraft air (Stellurgy, AtmosphereHandler) | `false` = defer to GC | |
| oxygenVentConsumptionMult | double | oxygenVentConsumptionMultiplier | vent O2 use scale | identity `1.0`; tunable | |
| oxygenVentPowerMultiplier | double | OxygenVentPowerMultiplier | vent power use scale | identity `1.0`; tunable | |
| spaceSuitOxygenTime | int | spaceSuitO2Buffer | suit O2 buffer minutes | tunable | |
| suitTankCapacity | float | suitTankCapacity | suit tank capacity scale | identity `1.0`; tunable | |
| scrubberRequiresCartrige | boolean | scrubberRequiresCartrige | scrubber cartridge requirement | `false` = no cartridge | |
| vacuumDamage | int | vacuumDamage | damage a vacuum deals per second (MECH-ATM-16c) | tunable; READ from the config object where the damage is dealt, so a runtime change reaches it | |
| lifeSupportZones | boolean | lifeSupportZones | per-zone N2/O2/CO2 tracking and crew respiration (MECH-ATM-17) | `false` = zones keep the vent's declared atmosphere, gases never move | |
| lifeSupportMinPartialO2 | long | lifeSupportMinPartialO2 | hypoxia threshold; AUTHORED in millionths of an atm, HELD in the composition's own unit (MECH-ATM-18/16b) | tunable; `max <= min` disables the governor | |
| lifeSupportMaxPartialO2 | long | lifeSupportMaxPartialO2 | oxygen-toxicity threshold; same authored/held split (MECH-ATM-18/19, 16b) | tunable; see above | |
| lifeSupportRespirationRate | long | lifeSupportRespirationRate | O2→CO2 per crew per second × zone volume; authored in millionths (MECH-ATM-17/16b) | `0` = breathing moves nothing; tunable | |
| lifeSupportRecirculatorRate | long | lifeSupportRecirculatorRate | CO2→O2 per T3 unit per second; authored in millionths (MECH-ATM-20/16b) | `0` = machine idles; tunable | |
| lifeSupportRecirculatorPower | int | lifeSupportRecirculatorPower | RF per recirculator operation (MECH-ATM-20) | tunable | |
| lifeSupportCarbonPerDust | long | lifeSupportCarbonPerDust | CO2 regenerated per carbon dust; authored in millionths (MECH-ATM-20/16b) | tunable; whole mechanic gated by `lifeSupportZones` | |
| lifeSupportFluidPerAtmBlock | int | lifeSupportFluidPerAtmBlock | mB per atm per block — the room/tank exchange rate (MECH-ATM-21) | tunable | |
| lifeSupportSeparatorRate | long | lifeSupportSeparatorRate | partial pressure a separator moves per second; authored in millionths (MECH-ATM-21/16b) | `0` = idles; tunable | |
| lifeSupportSeparatorPower | int | lifeSupportSeparatorPower | RF per separator operation (MECH-ATM-21) | tunable | |
| lifeSupportPlantRate | int | lifeSupportPlantRate | regeneration a central plant supplies per second, in ppm×blocks — the NETWORK's unit, which stays coarse and stays `int` (MECH-ATM-22/23) | `0` = plant offers nothing; tunable | |
| lifeSupportPlantPower | int | lifeSupportPlantPower | RF per second at full rate, charged in proportion to work done (MECH-ATM-22) | tunable | |
| lifeSupportPlantCarbonPerDust | int | lifeSupportPlantCarbonPerDust | work per carbon dust, ppm×blocks (MECH-ATM-22) | min 1; tunable | |
| lifeSupportDuctThroughput | int | lifeSupportDuctThroughput | work one duct carries per second (MECH-ATM-22/23) | `0` = duct carries nothing; tunable | |
| lifeSupportBreachVentRate | long | lifeSupportBreachVentRate | how fast a breached zone loses its air; authored in millionths/s (MECH-ATM-25/16b) | `0` = a breached room keeps its air; tunable | |
| jettisonPortIntervalTicks | int | jettisonPortIntervalTicks | how often a jettison port tries to fire, ticks | duty cycle, not a throughput cap; tunable | |
| jettisonPortClearance | int | jettisonPortClearance | blocks that must be empty ahead of a jettison port | blocked ⇒ the port HOLDS its cargo, never voids it; tunable | |
| shipHeat | bool | shipHeat | the ship thermal system (MECH-HEAT-01..07) | `false` ⇒ no block stores heat, no machine accrues it, every loop reads ambient (INV-HEAT-03) | |
| shipHeatAmbientKelvin | int | shipHeatAmbientKelvin | what an empty coolant loop reads, K — the CABIN and nothing else | tunable | |
| shipHeatPipeCapacity | int | shipHeatPipeCapacity | thermal mass of one pipe, heat units per K | `0` ⇒ a pipe is not a reservoir; tunable | |
| shipHeatAccumulatorCapacity | int | shipHeatAccumulatorCapacity | thermal mass of one accumulator, heat units per K | tunable | |
| shipHeatPipeThroughput | int | shipHeatPipeThroughput | heat one pipe carries per second at the loop's boundary | tunable; read as the pipe's network line capacity (`TileHeatLoopBlock.getThroughputPerTick`); heat does not flow inside a loop (ship-heat MECH-HEAT-01) | |
| shipHeatWasteFraction | int | shipHeatWasteFraction | share of spent energy that becomes collectable heat, per mille | `0` ⇒ machines make no collectable heat; tunable | |
| shipHeatRadiatorCellPower | int | shipHeatRadiatorCellPower | one radiating cell's power at the reference temperature (MECH-HEAT-08) | `0` ⇒ nothing sheds; tunable. A POINT ON THE CURVE, not a coefficient | |
| shipHeatRadiatorReferenceKelvin | int | shipHeatRadiatorReferenceKelvin | the temperature that power is quoted at | tunable; rescales every cell without changing the curve's shape | |
| shipHeatRadiatorClearance | int | shipHeatRadiatorClearance | blocks that must be empty ahead of a cell (MECH-HEAT-10) | blocked ⇒ that cell contributes zero area, never the whole array; tunable | |
| shipHeatChillerThroughput | int | shipHeatChillerThroughput | heat one chiller shifts between its two loops per second (MECH-HEAT-12) | `0` ⇒ the chiller does nothing; tunable. A SIZE, never a temperature | |
| shipHeatChillerCopFraction | int | shipHeatChillerCopFraction | fraction of the Carnot ideal a real machine reaches, thousandths (MECH-HEAT-16) | cannot exceed physics, only approach it; tunable | |
| shipHeatChillerCapacity | int | shipHeatChillerCapacity | the chiller's own thermal mass, heat units per K (MECH-HEAT-17) | `0` ⇒ a massless machine; tunable | |
| lifeSupportAirHeatCapacity | int | lifeSupportAirHeatCapacity | heat one block of air at one atmosphere holds per kelvin (MECH-HEAT-22) | `0` ⇒ air carries no heat and every zone reads ambient; tunable |  |
| shipHeatStarFluxReferenceKelvin | int | shipHeatStarFluxReferenceKelvin | starlight as the temperature a cell settles at under an Earth-normal star (MECH-HEAT-19) | `0` ⇒ starlight warms no ship; tunable. A TEMPERATURE, so it sits on the radiator's own curve | |
| shipHeatShieldAttenuation | int | shipHeatShieldAttenuation | how much incident flux a raised shield keeps off, per mille (MECH-HEAT-21) | `0` ⇒ thermally transparent; tunable. `1000` is ACCEPTED by the range and then refused where it is read — no configuration reaches total immunity (C12 HEAT-10) | |
| planetGreenhouseCeilingAtm | int | planetGreenhouseCeilingAtm | the thickest atmosphere whose greenhouse warming is still computed from the correlation rather than held | `0` ⇒ no bound, extrapolate without limit; tunable. The correlation is a two-point fit through Earth and Venus, so the default of 100 is just past the outer point | |
| shipHeatChillerMaxCop | int | shipHeatChillerMaxCop | the most heat one unit of a chiller's work can move, whatever the gradient (C12 HEAT-6b) | a ceiling on the Carnot curve where it climbs without bound; tunable | |
| shipHeatCabinConductionFraction | int | shipHeatCabinConductionFraction | how much of the loop-to-cabin temperature gap crosses into a sealed room's air each second, per mille (MECH-HEAT-25c) | `0` ⇒ a loop never warms the air around it and the crew rungs never fire; tunable. The only path by which a compartment's air gets hotter than the cabin | |
| shipHeatCrewVeryHotKelvin | int | shipHeatCrewVeryHotKelvin | how hot a compartment's AIR is before it is a hostile atmosphere, K (MECH-HEAT-25) | `0` ⇒ the rung never fires; tunable. NOT the planet ladder's 450 K — see MECH-HEAT-25b | |
| shipHeatCrewSuperheatedKelvin | int | shipHeatCrewSuperheatedKelvin | how hot that air is before it is lethal rather than merely hostile, K (MECH-HEAT-25) | `0` ⇒ the rung never fires; tunable | |
| shipHeatDriveRefusalKelvin | int | shipHeatDriveRefusalKelvin | how hot the coolant bolted to a hyperspace drive may run before it refuses to fire, K (MECH-HEAT-26) | `0` ⇒ no clause at all; tunable. The refusal is free — raised above the burst, never after it | |
| shipHeatSlugMarginKelvin | int | shipHeatSlugMarginKelvin | how far below its ceiling a heat slug is charged, K (MECH-HEAT-27) | `0` ⇒ charged to the edge of melting; tunable | |
| shipHeatSlugJoulesPerUnit | int | shipHeatSlugJoulesPerUnit | what one heat unit is worth in real joules, where the material table becomes game currency (MECH-HEAT-27) | tunable; scales every slug at once and changes no material's standing | |
| shipHeatMeltCheckTicks | int | shipHeatMeltCheckTicks | how often a coolant loop sweeps what it is cooking, ticks (MECH-HEAT-29b) | tunable; freshness of the melting rung, not a safety margin. Phased per loop so several do not stack on one tick | |
| shipHeatDumpTriggerKelvin | int | shipHeatDumpTriggerKelvin | how hot the loop must be before the emergency dump runs, K (MECH-HEAT-30) | tunable; below it the dump is inert, which is what keeps it an emergency | |
| shipHeatDumpThroughput | int | shipHeatDumpThroughput | heat one dump pushes into its slug per second (MECH-HEAT-30) | `0` ⇒ the dump does nothing; tunable, and kept below a radiator array by design (C12 HEAT-17) | |
| lifeSupportCombustionMinPartialO2 | long | lifeSupportCombustionMinPartialO2 | oxidiser partial pressure below which nothing burns; AUTHORED in millionths of an atm, HELD in the composition's own unit (C21 CON-C21-07, MECH-ATM-16b) | tunable; `0` ⇒ nothing burns anywhere. **Deliberately NOT the breathing threshold**: a room can be too thin to breathe and still light a torch, and tying the two would make combustion follow the breathing band | |
| shipHeatHullSkinFraction | int | shipHeatHullSkinFraction | how much of the gap between the air a ship encloses and the outside reaches its outer skin, thousandths (MECH-HEAT-33) | tunable; clamped above zero where it is READ (ship-heat MECH-HEAT-33, INV-HEAT-31) | |
| dropExTorches | boolean | dropExtinguishedTorches | drop extinguished torch | `false` = vanilla torch | |
| torchBlocks | List<Block> | torchBlocks | torch-like blocks in non-combustible atmos (postInit) | empty = none | |
| atmosphereHandleBitMask | int | atmosphereCalculationMethod (PERFORMANCE) | threading/volume calc method; read live into the blob search radius by `AtmosphereHandler.maxBlobRadius()` | tunable | |
| oxygenVentSize | int | oxygenVentSize (PERFORMANCE) | vent radius; also read live by `AtmosphereHandler.maxBlobRadius()` | tunable | |
| enableNausea | boolean | EnableAtmosphericNausea (CLIENT) | nausea in bad atmos; read from the live singleton where the effect is applied (`atmosphere/hazard/HazardExposure.java:161`) | `false` = no nausea; a runtime change reaches it | |

## Energy (`ENERGY`; owned by infrastructure-tiles / multiblock-machines)

| Flag | Type | Disk key | Gates | Disable path | FLAG |
|---|---|---|---|---|---|
| solarGeneratorMult | int | solarGeneratorMultiplier | solar output per tick | tunable | |
| microwaveRecieverMulitplier | float | MicrowaveRecieverMultiplier | microwave receiver output | identity `1.0`; tunable | |
| defaultItemTimeBlackHole | int | defaultBurnTime | default burn time for unlisted items | tunable | |
| blackHolePowerMultiplier | int | blackHoleGeneratorMultiplier | black-hole generator output | identity `1`; tunable | |
| blackHoleGeneratorBlocks | Map<ItemStack,Integer> | blackHoleTimings | per-item burn times (postInit) | map content | |
| electricPlantsSpawnLightning | boolean | electricPlantsSpawnLightning (CLIENT) | electric-mushroom lightning | `false` = no lightning | |

## Missions (`MISSION`; owned by mission MECH-MSN-*)

| Flag | Type | Disk key | Gates | Disable path | FLAG |
|---|---|---|---|---|---|
| asteroidMiningTimeMult | double | miningMissionTmeMultiplier | mining mission time | identity `1.0`; tunable | |
| gasCollectionMult | double† | gasMissionMultiplier | gas mission time | identity `1.0`; tunable | |
| gasHarvestAmountMultiplier | double† | gasHarvestAmountMultiplier | per-mission harvest cap (× 64000 mB) | identity `1.0`; ignored if infinite | |
| gasHarvestInfinite | boolean† | gasHarvestInfinite | uncap gas harvest | `false` = capped | |
| ~~asteroidTypes~~ | — | — | **No such flag**: per-galaxy state, `DimensionManager.getAsteroidTypes()`, synced by `PacketAsteroidInfo` | — | |

## Terraform / laser / general (`GENERAL`; owned by world-gen / terraform MECH-TERRA-*)

| Flag | Type | Disk key | Gates | Disable path | FLAG |
|---|---|---|---|---|---|
| allowMakingItemsForOtherMods | boolean | makeMaterialsForOtherMods | Stellurgy machines make rods/plates | `false` = Stellurgy-only | |
| allowSawmillVanillaWood | boolean | sawMillCutVanillaWood | sawmill vanilla wood | `false` = no | |
| lowGravityBoots | boolean | lowGravityBoots | low-g boots restriction | `false` = work everywhere | |
| jetPackThrust | float | jetPackForce | jetpack force | tunable (`<1` = low-g only) | |
| blockTankCapacity | float | blockTankCapacity | pressurized-tank capacity scale | identity `1.0`; tunable | |
| blockEnergyHatchCapacityMultiplier | float | blockEnergyHatchCapacityMultiplier | energy-hatch capacity (Stellurgy → TilePlugBase.energy_multiplier, class-load) | identity `1.0`; tunable | |
| blockLiquidHatchCapacityMultiplier | float | blockLiquidHatchCapacityMultiplier | liquid-hatch capacity (Stellurgy) | identity `1.0`; tunable | |
| crystalliserMaximumGravity | float | crystalliserMaximumGravity | crystalliser gravity ceiling | `0.0` = disable crystalliser | |
| enableLaserDrill | boolean | EnableLaserDrill | laser drill | `false` = disabled | |
| spaceLaserPowerMult | float | LaserDrillPowerMultiplier | laser drill power use | identity `1.0`; tunable | |
| laserDrillPlanet | boolean | laserDrillPlanet | drill-down vs void-miner mode | mode switch, not disable | |
| laserBlackListDims | List<Integer> | spaceLaserDimIdBlackList | dims where laser can't mine (parsed) | empty = allow all | |
| enableTerraforming | boolean | EnableTerraforming | terraforming blocks/items | `false` = disabled | |
| terraformSpeed | double† | terraformMult | atmosphere-change speed (TileAtmosphereTerraformer) | identity `1.0`; tunable | |
| terraformRequiresFluid | boolean | TerraformerRequiresFluids | terraformer fluid requirement (TileAtmosphereTerraformer) | `false` = no fluid | |
| terraformliquidRate | int | TerraformerFluidConsumeRate | terraformer mB/t (TileAtmosphereTerraformer) | tunable | |
| allowTerraformNonStellurgy | boolean | allowTerraformingNonStellurgyWorlds | terraform in non-Stellurgy dims (TileAtmosphereTerraformer) | `false` = Stellurgy dims only | |
| enableGravityController | boolean | enableGravityMachine | gravity controller | `false` = disabled | |
| allowNonStellurgyBiomesInTerraforming | boolean | allowNonStellurgyBiomesInTerraforming | non-Stellurgy biome decoration in terraform | `false` = default biomes only | |
| enableOrbitalRegistry | boolean | EnableOrbitalRegistry | orbital registry | `false` = disabled | |
| lavaCentrifugeOutputs | String[] | lavaCentrifugeOutputs | enriched-lava centrifuge outputs | list content | |
| lavaCentrifugePower | int | lavaCentrifugePower | centrifuge power/tick | tunable | |
| lavaCentrifugeTime | int | lavaCentrifugeTime | centrifuge process time | tunable | |
| **terraformPlanetSpeed** | int | terraformBlockPerTick (loader **commented out**) | **none** — no functional read anywhere | keeps `0` default forever | ⚑ DEAD |
| **overworldsealevelterraforming** | boolean | overworldSealvlTerraforming (loader **commented out**) | **none** — no functional read anywhere | keeps `false` default forever | ⚑ DEAD |

## Client (`CLIENT`; owned by client-render MECH-CLR-*)

| Flag | Type | Disk key | Gates | Disable path | FLAG |
|---|---|---|---|---|---|
| stationSkyOverride | boolean† | StationSkyOverride | Stellurgy skybox on stations | `false` = vanilla sky | |
| planetSkyOverride | boolean | PlanetSkyOverride | Stellurgy skybox on planets | `false` = vanilla sky | |
| skyOverride | boolean | overworldSkyOverride | Stellurgy skybox in overworld | `false` = vanilla sky | |
| advancedVFX | boolean | advancedVFX | advanced visual effects | `false` = basic | |

## Internal / non-flag surface fields (not player toggles)

- `config` (`net.minecraftforge.common.config.Configuration`) and `configFolder` (`String`) —
  loader plumbing, not player-facing.
- `addSealedBlock(...)` / `addTorchblock(...)` — **methods**, not fields; a scan for `ConfigProperty`
  identifiers may match them.

## Collisions & risks

1. **Dead config fields.** `terraformPlanetSpeed` and `overworldsealevelterraforming` are declared
   `@ConfigProperty` but their disk loaders are commented out (`StellurgyConfiguration.java:816`,
   `:945`) and no consumer reads them anywhere in `src/main` (full-tree grep: only the declaration
   remains). They are permanently pinned at their zero/false defaults. Player-visible symptom: two
   config knobs that silently do nothing.

2. **A flag copied into a `static` at class-initialisation is a defect.** It freezes at whatever the
   live singleton held when the class first loaded, so the answer depends on class-load order rather
   than on the file. A boolean gate reads the LIVE flag at the point of consequence: `enableNausea` is
   read where the effect is APPLIED (`atmosphere/hazard/HazardExposure.java:161`), `vacuumDamage` off
   the config object, and `AtmosphereHandler.maxBlobRadius()` reads `atmosphereHandleBitMask` /
   `oxygenVentSize` at each use, so a runtime change reaches all of them.

3. **Wire id is `field.getName().hashCode()` (INV-API-02).** Renaming any `needsSync` field silently
   changes its 32-bit wire key with no migration; and `readConfigFromNetwork` bails on the first
   hash mismatch, desyncing the rest of the buffer (INV-API-05). Cross-subsystem save/wire risk —
   already owned by api-public; C2 (wire) should cite it.

4. **Flight-worthiness vs burn config disagree (author-documented).** `transBodyInjection` and
   `stationClearanceHeight` are used by the flight sequence but, per the author comments on the
   fields, are **not** consulted by the machines that decide whether a rocket is fit to fly (the
   monitor reads the launch world's own transfer line). Producer (config) and
   consumer (flight-worthiness check) disagree — a rocket flagged flightworthy can still fall back
   to the parent planet. Owned narratively by rocket-entity; recorded here as a config-surface risk.

5. **Accrual and consequence are split on purpose for `partsWearSystem`.** The flag gates ACCRUAL
   only (`StorageChunk.damageParts`); the consequences — the launch warning, the `wearCriticalBlocks`
   refusal, the stochastic explosion, the tank leaks and `wearThrustFactor` — run unconditionally.
   Wear and battle damage share a single stage axis, so a consequence-side gate would make shot-up
   ships unkillable. What the flag promises: off means a save stops getting WORSE, not that the
   damage already on it stops mattering. `advancedWeightSystem` gates calc **and** the launch
   decision across StatsRocket/EntityRocket/assembler/StorageChunk. The only disableability defects
   on this surface are the DEAD class (row-flagged) and risk 2.

## Weapons

| key | section | default | what OFF/zero does |
|---|---|---|---|
| `shotVisibilityRadius` | `Weapons` | `256` | `0` switches the whole weapon drawing channel off — rounds AND held beams: the mechanics still work, guns still fire and hit, and nothing is drawn on any client. Every other value is the distance from a player at which a round's PATH, or a beam's lit LENGTH, must pass before that player is told (`projectile-substrate` MECH-SHOT-14, MECH-SHOT-29/30). One knob for both on purpose: they are the same question about the same weapons, and two would be two answers |

Synced (`@ConfigProperty(needsSync = true)`) because the client uses it for nothing but the server
uses it to decide who is told — a client with a different value would not disagree about anything it
can observe, and syncing keeps the readout honest.

## Fire-control sensor

| key | section | default | what OFF/zero does |
|---|---|---|---|
| `enableFireControlSensor` | `Weapons` | `true` | The master switch. `false` = the sensor acquires nothing, publishes nothing and draws no power; a battery is pointed by hand, which is a supported configuration rather than a degraded one (`fire-control-sensor` INV-FCS-02) |
| `fireControlSensorRadius` | `Weapons` | `96.0` | How far the device can look at all. Not its lock range: a contact inside the envelope may still be resolved too poorly to shoot at |
| `fireControlSensorScanIntervalTicks` | `Weapons` | `10` | Ticks between sweeps — the cadence at which the sensor reconsiders WHICH contact to hand its battery. The mount still follows the contact every tick |
| `fireControlSensorMaxTracks` | `Weapons` | `8` | How many contacts one sensor holds. A bound on work |
| `fireControlSensorActiveEnergyPerTick` | `Weapons` | `40` | FE per tick an illuminating sensor draws. `0` makes the active mode free; listening is always free, and a sensor that cannot pay falls back to listening rather than reporting a lock it does not have |
| `fireControlSensorActiveLockQuality` | `Weapons` | `0.95` | The quality an illuminated contact is held at inside the envelope — this number IS the passive/active gap |
| `fireControlSensorLockQualityToFire` | `Weapons` | `0.25` | How well a contact must be resolved before a gun fires at it. `0` = any contact may be engaged, which deletes the detect-but-cannot-hold state the active mode exists to fix |
| `fireControlSensorAcquireHostilesOnly` | `Weapons` | `true` | `false` = a battery engages any living thing that is not a friend, livestock included |

All synced: the server decides everything here and the client only ever displays it, so a disagreeing
client would produce a readout that contradicts the guns.

## Turret condition

| key | section | default | what OFF/zero does |
|---|---|---|---|
| `turretDerateDamageFraction` | `Weapons` | `0.25` | How far gone a mount's own block must be, 0..1, before its traverse slows. `0` = a mount is slow from the first scratch |
| `turretJamDamageFraction` | `Weapons` | `0.75` | The fraction at which it seizes entirely — and still fires down the bearing it stopped at |

AFFS config (`affs/config/ModConfig.java`):

| key | section | default | what OFF/zero does |
|---|---|---|---|
| `shieldNodeDamagePenaltyMax` | shield | `0.75` | Share of a generator's conversion, a cable's transport or an accumulator's reserve lost one stage from destruction. `0` = battle damage costs these blocks nothing |
| `emitterRadiusDamagePenaltyMax` | shield | `0.5` | Share of an emitter's radius lost one stage from destruction; still billed for the declared radius. `0` = no shrink |
| `shieldStrikeReflectionRestitution` | `Weapons` | `1.0` | Share of a declared travelling body's relative speed kept on reflection off a shell. `0` = reflected bodies stop at the shell |

**There is deliberately no flag that switches this off.** Damage and wear advance the same stage
counter, so a switch here would be a switch that makes ships unkillable; `partsWearSystem`
gates wear where wear ACCRUES and may never gate a consequence. The ORDER of the two
rungs is the mechanic and is not configurable; where they sit is balance.

## Ship meeting, space registration

| key | section | default | what OFF/zero does |
|---|---|---|---|
| `shipsCollide` | `ROCKET`, synced (`@ConfigProperty(needsSync = true)`, field `api/StellurgyConfiguration.java:541`) | `true` | Two craft whose world boxes overlap are no longer held at rest and no `ShipCollisionEvent` is posted — they pass through each other (`integration/vs/ShipMeetingStop`) `[V]`. Being synced, it is part of the join-time config packet's layout. |

**There is no `enableSpaceSubsystem` flag** (`PERFORMANCE`). Space registers on every server
(`SpaceSubsystem.shouldRegister` answers only "not already built", `space/SpaceSubsystem.java:202`)
`[V]`; a leftover key in a config file is not read. There is no way to boot a server with no space
worlds: `spaceCellPoolSize` has a floor of 1 (`api/StellurgyConfiguration.java:959`), and hyperspace
registers beside the pool `[V]`. See C16 CLOCK-5.1.
