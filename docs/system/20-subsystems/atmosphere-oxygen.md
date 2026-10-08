---
id: atmosphere-oxygen
owns: [atmosphere/, tile/atmosphere/, armor/, api/atmosphere/, api/armor/, tile/TileSuitWorkStation.java, tile/TileWearable.java]
entrypoints: [AtmosphereHandler#onTick, AtmosphereHandler#onBlockChange, TileOxygenVent#performFunction, AtmosphereNeedsSuit#isImmune]
depends-on: [dimension-planets, api-public, network-wire, util-core, client-render]
depended-by: [rocket-entity, event-handlers, blocks, items]
contracts: [C1, C2, C3, C4, C5]
confidence: high
---

## Purpose

Models breathable vs. hostile air on a per-dimension basis, applies per-tick damage/potion
effects to unprotected entities, and provides the machines (oxygen vent, CO2 scrubber, gas
charge pad, atmosphere detector) and worn gear (space suit / chest) that let a player survive
and pressurise a sealed room. The unit of "air" is an `AreaBlob` flood-fill anchored on a vent;
the unit of "type" is a registered `AtmosphereType` singleton carried as the blob's data.

## Responsibility boundary

**Owns:** the `AtmosphereType` singleton hierarchy and their tick effects; the per-dimension
`AtmosphereHandler` registry and blob bookkeeping; the four atmosphere machines
(`tile/atmosphere/*`); the space-suit armor items (`armor/*`) and their protection contract;
the suit workstation and the generic wear tile.
**Does NOT own:** the flood-fill graph engine `AreaBlob`/`AtmosphereBlob` and
`SealableBlockHandler` (util-core / api-public — described here only at the seam);
`DimensionProperties.getAtmosphere()` and the density/pressure fields (dimension-planets);
`ItemAirUtils` wrapper for third-party air containers (util-core); the packet classes
themselves (network-wire); `CapabilitySpaceArmor`/`CapabilityWear` declarations (api-public).

## Key types

| type | role |
|------|------|
| `AtmosphereHandler` | Per-dimension singleton; holds `IBlobHandler→AreaBlob` map; Forge-event ticker for entity effects; block-change reactor. |
| `AtmosphereAssertion` (`api/atmosphere/`) | What a detector can watch for: a statement about the air, not a name. Eleven of them, two of which — poisonous, corrosive — no named atmosphere ever expressed. |
| `AtmosphereAssertions` | Evaluates a statement at a position: the AIR answers wherever there is a zone, the published atmosphere answers outdoors, and the two questions a name cannot answer read NO there rather than guessing. |
| `Atmosphere` (`api/atmosphere/`) | The concrete named atmosphere; flags `canTick / isBreathable / allowsCombustion`; registers all singletons in a static block. |
| `AtmosphereNeedsSuit` | Adds `isImmune(entity)` = suit/creative/riding/grace check; base for every hostile type. |
| `AtmosphereVacuum` / `AtmosphereNoOxygen` / `AtmosphereLowOxygen` | O2-deprivation effects (damage + blindness/weakness/nausea); vacuum also uses config `vacuumDamage`. |
| `AtmosphereHighPressure` / `AtmosphereSuperHighPressure` (+`…NoOxygen`) | Pressure effects (poison, toxicity damage, jump-block narcosis). |
| `AtmosphereVeryHot` / `AtmosphereSuperheated` (+`…NoOxygen`) | Set fire + heat damage. |
| `TileOxygenVent` | RF+O2 consumer; registers a blob, floods a sealed room to `PRESSURIZEDAIR`, drives adjacent scrubbers. |
| `TileCO2Scrubber` | Inventory hatch holding a cartridge; `useCharge()` damages it; comparator reports charge. |
| `TileLifeSupportPlant` | T4 central regeneration; a network source, carbon into its own slot. |
| `TileVentilationDuct` | the two-channel duct: a throughput and nothing else — no gas is stored in a pipe. |
| `TileJettisonPort` | the carbon's exit: an unpowered airlock that throws its slot overboard when the way ahead is clear. |
| `EjectionPort` (`subsystem/ejection/`) | the shared act of throwing an item out of a hull — clearance walk, world-frame spawn, ship carry. Life support's only caller today; the thermal dump is the second. |
| `LifeSupportNetwork` | the ventilation domain, its unit (ppm×blocks/tick), and the conversion between that and the composition's finer unit; holds no graph code of its own. |
| `TileGasChargePad` | Refills worn suit O2 (and armor-module H2) from its tank for players standing on it. |
| `TileAtmosphereDetector` | Emits redstone when an adjacent cell matches a player-selected atmosphere. |
| `TileSeal` | Fires `BlockSeal` seal-checks on its neighbours once per placement. |
| `ItemSpaceArmor` | Modular armor (`EmbeddedInventory` in stack NBT); `protectsFromSubstance` whitelists hostile types. |
| `ItemSpaceChest` | `ItemSpaceArmor` + `IFillableArmor`; O2 stored in inner fluid-tank component stacks. |
| `TileSuitWorkStation` | GUI proxy inventory mapping slots onto the held armor's component slots. |
| `TileWearable` | Generic part-wear staging tile (`stage`/`maxStage`/`transitionProb`) via `CapabilityWear`. |

## Mechanics

- **MECH-ATM-01 handler lifecycle** — `registerWorld(world)` on world load creates one
  `AtmosphereHandler` and subscribes it to the event bus, *iff* oxygen enabled, dim has a
  surface, and it is not the (non-native, non-override) Moon; `unregisterWorld(world)` clears its
  blobs and unsubscribes it. **The handler is a PART OF THE WORLD (`WorldRuntime`)**:
  it is held in the world object's own `WorldAtmosphere` part (with the world's structure-paste
  depth), so there is no static dimension-to-handler map; it dies with the world, a second
  `registerWorld` on the same world releases the first handler before building the new one, and a
  client world never has one (`hasAtmosphereHandler`). `AtmosphereHandler.java:46-55,73-110`.
- **MECH-ATM-02 blob seal flood-fill** — a vent's `AreaBlob` (`AtmosphereBlob`) does a BFS from
  the root cell over non-sealed, non-overlapping cells; radius mode vs. threaded-volume mode
  chosen by `atmosphereHandleBitMask`; on reaching a cell past the cap it `clearBlob()`s the
  *whole* blob rather than partial-filling. `util/AtmosphereBlob.java:73-170` (seam).
- **MECH-ATM-03 block-change reaction** — `onBlockChange` (called from `World.setBlock`) ignites
  flammable blocks in `SUPERHEATED`, vaporises gaseous fluid blocks in `VACUUM`, and
  adds/removes the changed cell from nearby blobs. Server-only, chunk-loaded-guarded.
  `AtmosphereHandler.java:120-223`.
- **MECH-ATM-04 entity tick effects** — `onTick(LivingUpdateEvent)` resolves the entity's
  atmosphere; if `canTick()` and not in lava/water, posts a cancellable `AtmosphereTickEvent`
  then calls `atmosType.onTick(entity)` unless the type is immune to that entity class. Each
  hostile type applies its own damage source + potion cocktail on a modulo cadence.
  `AtmosphereHandler.java:235-264`; effects in each `Atmosphere*.onTick`.
- **MECH-ATM-05 suit / immunity check** — `AtmosphereNeedsSuit.isImmune(entity)` returns true
  for the rocket-transfer grace window, MatterOverdrive androids, creative/spectator, riding a
  rocket/elevator capsule, or wearing protection: hazards that act on what you BREATHE need
  helm+chest, hazards that act on the whole body additionally need legs+feet, and where several are
  present the strictest decides (MECH-ATM-16d). Protection resolved via `ItemAirUtils` wrapper or
  `CapabilitySpaceArmor.PROTECTIVEARMOR`, and asked about HAZARDS rather than named air.
  `AtmosphereHazards.java`.
- **MECH-ATM-06 client sync** — once a second per player, phased on the player's own age
  (`AtmosphereHandler.isSyncTick`), the handler sends `PacketAtmSync` carrying a READOUT: pressure,
  breathable, a warning lang key, and the statements true of the air. Hostile ticks additionally send
  `PacketOxygenState`. The client mirrors it into `currentSummary` and decides nothing with it.
  Periodic rather than edge-triggered because a composition has no edge — the old packet fired when a
  coarse LABEL changed, which only worked while the answer was one of fourteen names. **Nothing is
  remembered between sends**: the edge needed the previous answer and kept a per-player map to hold
  it, and when the edge went the map lost its only reader and went with it.
  `AtmosphereHandler.java:292-294`; `Atmosphere.sendToRealPlayer:29-37`.
- **MECH-ATM-07 vent operating cycle** — while turned on, fuelled and powered, the vent
  periodically `addBlock`s to seal (every 100t), drains O2 proportional to blob size ×
  `getGasUsageMultiplier()`, activates adjacent scrubbers, and reverts the blob to the dim's
  default atmosphere when the tank runs dry. Loss of power/seal clears the blob.
  `tile/atmosphere/TileOxygenVent.java:184-372`.
- **MECH-ATM-08 scrubber charge** — `useCharge()` increments cartridge item-damage (returns true
  while charge remains, reducing gas usage); `getComparatorOverride()` maps remaining damage to
  a 0..15 redstone level. `tile/atmosphere/TileCO2Scrubber.java:26-47`.
- **MECH-ATM-09 gas charge pad** — for each player standing in its 1×2×1 box, tops up a fillable
  chest with tank O2, or fills H2 into fillable armor-module component stacks; O2 and H2 paths
  are mutually gated on tank fluid type. Runs every 2 ticks. `tile/atmosphere/TileGasChargePad.java:94-174`.
- **MECH-ATM-10 atmosphere detector** — every 10t emits redstone via `BlockRedstoneEmitter` when
  any non-opaque neighbour cell's atmosphere equals the player-selected `atmosphereToDetect`
  (selection synced by unlocalized name). `tile/atmosphere/TileAtmosphereDetector.java:46-67`.
- **MECH-ATM-11 modular space armor** — `EmbeddedInventory` of components serialised into stack
  NBT; `onArmorTick`/`damageArmor` dispatch to each `IArmorComponent`; `protectsFromSubstance`
  whitelists all hostile atmosphere singletons. `armor/ItemSpaceArmor.java:119-245`.
- **MECH-ATM-12 fillable chest O2** — air is the sum of oxygen held in inner fluid-tank
  components; `decrementAir`/`increment`/`getMaxAir` iterate the embedded inventory's fluid
  capabilities; `protectsFromSubstance` consumes 1 O2 per protection commit unless the
  atmosphere `allowsCombustion()`. `armor/ItemSpaceChest.java:56-286`.
- **MECH-ATM-13 suit workstation** — slot 0 holds the armor; slots 1..N proxy onto the armor's
  component slots via `IModularArmor`, enabling/disabling textured slot modules on insert.
  `tile/TileSuitWorkStation.java:56-128`.
- **MECH-ATM-14 pipe seal tile** — on first server tick fires `BlockSeal.fireCheckAllDirections`
  on all 6 neighbours; removes the seal on chunk-unload. `tile/atmosphere/TileSeal.java:13-27`.
- **MECH-ATM-15 part-wear staging** — `transition()` probabilistically advances `stage` toward
  `maxStage` using `transitionProb`-derived per-stage odds; exposed via `CapabilityWear`.
  `tile/TileWearable.java:64-89`.
- **MECH-ATM-16 zone gas contents** — an `AtmosphereBlob` carries an `AirState`: a partial pressure
  per substance, held in **nano-atmospheres in a `long`** (`ONE_ATM = 1_000_000_000`). `getPressure()`
  is derived from their sum instead of the constant `100` it returned before; `AirState.earthLike()`
  totals exactly one atmosphere, so an untouched zone reports the same figure as before. Nitrogen is
  inert — nothing produces or consumes it — and exists to make the oxygen FRACTION a governable
  quantity. `atmosphere/AirState.java`; `util/AtmosphereBlob.java:41-56`. [V]
- **MECH-ATM-16c what an atmosphere DOES is a table of hazards, not a class per kind of air** —
  six hazards in `api/atmosphere/AtmosphereHazard` (decompression, suffocation, oxygen toxicity,
  pressure, heat, poison — poison is applied as a carried dose by `hazard/Poisoning`, NOT as a row of
  the table; corrosion is a detector statement only, with no hazard behind it) with rows carrying
  `(period, damage source, amount, potions, ignition, narcosis, whether the client is told)`. A named
  atmosphere maps to the SET of rows it raises, and the rows that fire on a tick are MERGED — the
  strongest amplifier per potion, applied once — so a room that is two kinds of dangerous is not
  noisier than a room that is one. Replaces fourteen `onTick` methods that were the cells of one
  product (an oxygen rung × a hot-or-dense rung) written out by hand.
  <b>Four places where those cells disagreed with what composing their hazards produces are PRESERVED
  as named rows</b> — heat ignites only where the air was breathable; the deepest pressure rung
  injures only where it is breathable and adds narcosis only where it is not; suffocation is more
  nauseating under pressure. Each says in its own comment what it is preserving.
  `atmosphere/hazard/AtmosphereHazards.java`; `atmosphere/hazard/HazardExposure.java`. [V/T]
- **MECH-ATM-16d immunity is keyed by HAZARD, and spending is keyed by what the air lacks** — what a
  suit is asked is "do you stop this kind of harm", never "do you recognise this named atmosphere";
  the piece requirement is the STRICTEST among the active hazards, which reproduces all fourteen
  hand-written mask-or-full-suit answers. A suit spends its tank only where there is no oxidiser
  outside to concentrate — said of the hazard rather than read off the label's hand-assigned
  combustion flag, which is the wrong source.
  <b>Immunity remains all-or-nothing per ZONE</b>: an entity that meets the strictest requirement is
  untouched, one that does not takes everything. Per-hazard protection is a live behavioural change
  for a partially suited player and is deliberately not taken here.
  `api/armor/IProtectiveArmor.java`; `atmosphere/hazard/AtmosphereHazards.java`. [V/T]
- **MECH-ATM-16b the unit a person writes is not the unit the model stores** — thresholds, rates and
  a gas's hazard limit are AUTHORED in parts per million of an atmosphere, because that is what an
  oxygen fraction and a real exposure limit are quoted in; the state is a thousand times finer so a
  trace still has digits left (Mars's 0.13% oxygen at 6 mbar is eight whole units in ppm and four
  significant digits here). `AirState.PER_PPM` is the ONLY bridge, and it is crossed at exactly two
  boundaries: the config loader (`StellurgyConfiguration.partialPressure`) and the gas registry's
  constructor. What falls below one unit is zero everywhere — it is not breathable, not harvestable
  and not a hazard, which is the honest reading of an exosphere that thin.
  `atmosphere/AirState.java`; `api/StellurgyConfiguration.java`; `atmosphere/gas/Gas.java`. [V/T]
- **MECH-ATM-17 respiration** — once a second, phased on `entity.ticksExisted` rather than world
  time so that living things do not all breathe on the same tick (jitter the
  phase, keep the period), a living entity inside a zone the vent has declared breathable converts `lifeSupportRespirationRate / blobSize` of the zone's oxygen into CO2, and
  the blob's data is then set to `AirState.deriveAtmosphere()`. Total pressure is unchanged:
  breathing rearranges air rather than consuming it. Dividing by the zone volume is what makes a
  bigger cabin last proportionally longer on the same crew. `AtmosphereHandler.java:respire`. [V/T]
- **MECH-ATM-18 the derived atmosphere** — the gas state is the model, the registered
  `AtmosphereType` singletons stay the interface: `deriveAtmosphere()` maps a zone to `VACUUM`
  (no gas), `NOO2` (no oxygen at all), `LOWOXYGEN` (below the band), `HIGHOXYGEN` (above it) or
  `PRESSURIZEDAIR`. Nothing downstream — damage, suit immunity, `PacketAtmSync`, the detector —
  learns about partial pressures. An unconfigured band (`max <= min`) governs nothing rather than
  reading as universally toxic. `atmosphere/AirState.java:deriveAtmosphere`. [T]
- **MECH-ATM-18b heat is asked FIRST, and it answers with the same singletons** — since the thermal
  system's crew rung, the derivation reads the air's TEMPERATURE before any question about oxygen and
  returns `VERYHOT`/`SUPERHEATED` (or their `NoO2` variants) above the configured thresholds. This
  subsystem gains no new damage path from it: the hostile types, their tick and the suit chain are the
  ones a scorching planet already used. The gases only choose which variant, and an unusable oxygen
  band still governs nothing — a missing config may not invent a second hazard on top of the heat. The
  thresholds and the reason they are not the planet ladder's belong to `ship-heat`
  (MECH-HEAT-25, 25b). `atmosphere/AirState.java:deriveAtmosphere`. [V/T]
- **MECH-ATM-20 T3 recirculator** — `TileAirRecirculator` is a powered block serving the zone it
  **touches**: it resolves its air cell by scanning its six neighbours, because a machine occupies a
  solid block and a zone is made of AIR cells, so its own position is never in one. Measured: with
  the machine reading its own position it never ran at all, in any placement. Once a second — on its
  OWN tick counter, never `world.getTotalWorldTime() % 20`, which would wake every recirculator in
  the world together and is invisible to a force-ticking harness — it converts `lifeSupportRecirculatorRate` of that zone's
  CO2 back into oxygen (`AirState.regenerate`, the net Bosch result) and buffers the carbon that
  left the air until a whole `lifeSupportCarbonPerDust` is owed, then emits `itemCarbonDust` into
  its single output slot. No fluid I/O and no pipes — the whole-ship ventilation belongs to T4
  (maintainer ruling). A full output slot **stops** conversion rather than voiding
  the carbon, so a blocked machine backs up. `tile/atmosphere/TileAirRecirculator.java`. [V/T]
- **MECH-ATM-19 oxygen-rich hazard** — `AtmosphereHighOxygen` is a needs-suit type applying
  `oxygenToxicityDamage` every 40t and deliberately keeping `allowsCombustion() == true`: the
  flammability IS the hazard. It needs only a mask, the danger being what is breathed rather than
  pressure or heat. `atmosphere/AtmosphereHighOxygen.java`. [T]
- **MECH-ATM-21 gas separator** — `TileGasSeparator` is one block with a direction, flipped by
  **shift-right-click** (`BlockGasSeparator.onBlockActivated`; a plain click still opens the GUI).
  *Split* draws gas from the zone it touches into its tank — CO2 first, nitrogen only once the air
  is clean, so a separator left running never strips the oxygen the crew are breathing. *Combine*
  pushes tank gas back, and is the loop's **safety governor**: oxygen enters only up to
  `AirState.oxygenHeadroom()`, the toxicity ceiling, while nitrogen (inert) is uncapped. Two
  instances are what an automated loop needs, one per direction — the mode exists for the manual
  utility case. Room↔tank conversion is `lifeSupportFluidPerAtmBlock` millibuckets per
  atmosphere per block of zone volume, so a bigger cabin costs proportionally more gas to fill.
  Like the recirculator it serves a NEIGHBOURING cell and runs on its own tick counter.
  `tile/atmosphere/TileGasSeparator.java`. [V/T]
- **MECH-ATM-22 T4 ventilation network** — the centralised tier. `TileLifeSupportPlant` is a
  network SOURCE of regeneration work, `TileVentilationDuct` a CABLE with a throughput and no
  contents, and **`TileOxygenVent` is its zone's SINK** — the vent already owns the zone, so "one
  vent per zone connects to the plant" needed no new object that knows what a zone is. All of the
  graph, the solve and the limits are the shared [subsystem-network](./subsystem-network.md)
  primitive; ventilation is a domain over it, which is why a duct and a shield cable laid through
  one wall never join (INV-NET-01). The plant serves rooms it does not stand in and takes the carbon
  into its OWN slot; a full slot backs it up rather than deleting carbon, as one tier down.
  `atmosphere/LifeSupportNetwork.java`, `tile/atmosphere/TileLifeSupportPlant.java`,
  `TileVentilationDuct.java`, `TileOxygenVent.java:308-395`. [V/T]
- **MECH-ATM-25 a breach LOSES the air, it does not delete it** — when a hull is opened the
  flood-fill drops the room's cells, but the gases are not the cells: `AreaBlob.clearBlob()` empties
  the GRAPH while `AtmosphereBlob.airState` survives, and the handler hands that state back keyed by
  VENT rather than by membership. So the vent drains all three gases proportionally at
  `lifeSupportBreachVentRate` per second, on its own tick counter and regardless of power (a hole
  needs no electricity), until the room is vacuum. A hull breach therefore costs a tankful, paid
  when the hull is patched and the vent refills (a lost seal does not make the zone and its gases cease in the same tick). `TileOxygenVent.ventBreachedAir`. [V/T]
- **MECH-ATM-26 a breached zone stops asking the plant for air** — the automatic isolation,
  under the 2026-08-15 ruling that "cutting" a zone means DISCONNECTION FROM THE NETWORK: the zone
  keeps its air, its vent and its degradation, it simply stops requesting. It falls out of the sink
  contract rather than being a mechanism of its own — `getRequested()` returns 0 whenever the vent is
  not maintaining — which is why the ship answers a hole by not pumping its reserves into space.
  The MANUAL half is older than life support: `SealableBlockHandler:133` counts a closed airlock door
  as a sealing block, so closing a bulkhead divides the hull into separately-maintained zones.
  `TileOxygenVent.getRequested`, `SealableBlockHandler.java:133`. [V/T]
- **MECH-ATM-29 the air is a COMPOSITION, keyed by substance** — a zone holds a sparse map of
  partial pressures over `GasRegistry`'s eleven substances rather than three named fields, so a room
  can contain something the old vocabulary could not name. Sparse on purpose: a vacuum holds an empty
  map. The key is the SUBSTANCE, and a substance carries the fluid it becomes when it is extracted, so
  what is here and what can be collected here are one object. A gas the running game no longer knows
  is dropped on load (C1, zone air). `AirState.java`,
  `atmosphere/gas/` `[V][T]`
- **MECH-ATM-30 what air IS, everything else is derived from** — breathability, combustion, how
  poisonous the air is and how hard it is working on metal are PREDICATES over that map plus the
  temperature. `deriveAtmosphere` now answers from them rather than from fields, so the label a zone
  presents and the facts it reports come from one place. Poison is a SUM, Σ pp/limit over the TOXIC
  gases (the standard mixture rule), and toxic means the sum ≥ 1; CO2 is TOXIC past its 5 % limit
  (ruled 2026-10-04). `AirState.allowsCombustion/isBreathableAir/toxicIndex/corrosionIndex`
  (`:452`) `[V][T]`
- **MECH-ATM-32 poisoning is a DOSE the entity carries** — once a second (`AtmosphereHandler:312`)
  every living entity not on the bypass list adds the toxic index of the air it breathes to a dose
  kept in its own persistent data (`Poisoning.DOSE_KEY`), when the index is ≥ 1; any other second
  (clean air, under water, in lava, or kept out by a suit) clears it with a 300 s half-life. Past
  30 limit-seconds it does dose/30 damage per second, damage type `Poison`. Only a whole suit
  breathing its own supply keeps it out (`AtmosphereHazard.POISON`, full suit, supplied oxygen — it
  spends the chest's air while the air is poisoned). The air breathed is the zone's or, outdoors,
  the planet's (`getAirAround`, `:382`). Off with `enableOxygen` (no handler). The readout warns
  `msg.poison` when nothing worse is wrong (`AtmosphereSummary`). Rulings 2026-10-04.
  `Poisoning.java` `[T]` — `unit/PoisoningTest`, `server/PoisonedAirTest`; the whole suit keeping
  it out by `client/VacuumAndSuitClientGroupTest.aSealedSuitKeepsPoisonedAirOut` (outdoors, on the test mixin's `poison_breathed` record), and a helmet and a chest without legs and
  boots NOT keeping it out by `…aHelmetAndTankWithoutTheRestOfTheSuitDoNotKeepPoisonedAirOut`. NOT
  pinned: poison inside a sealed zone
- **MECH-ATM-31 fire asks the OXIDISER, and it is a different question from breathing** — combustion
  has its own config band (`lifeSupportCombustionMinPartialO2`) and asks for a ROLE rather than for
  oxygen, so an atmosphere whose oxidiser is not oxygen needs no code. Every ignition path goes
  through `AtmosphereHandler.allowsCombustionAt`, which reads the zone's air and, where there is no
  zone, the PLANET's air by the same predicate (there is no label fallback: a thin
  oxygen world whose label says `lowO2` and "combustible" burns only if its oxidiser clears the
  combustion band). **The type's own combustion flag survives and still disagrees**: the suit
  (`ItemSpaceChest:279`) and the blob default still read it, which is the seam the type-deletion slice
  closes. `AtmosphereHandler.allowsCombustionAt`, `BlockTorchUnlit:67`, `PlanetEventHandler:262,281`
  `[V][T]`
- **MECH-ATM-27 the carbon LEAVES, through a port that refuses rather than voids** — regeneration
  buffers carbon into an output slot and a full slot backs the machine up, so a closed air loop is
  only closed while the dust has an exit. `TileJettisonPort` is that exit: unpowered, one slot,
  fires on its own counter when `jettisonPortClearance` blocks ahead of its facing are empty. A
  blocked port HOLDS its cargo — voiding it would be indistinguishable, from inside the ship, from
  the port working. The accounting stays honest: that carbon entered the air as exhaled CO2 whose
  carbon came from imported food, so venting exports imported mass rather than opening the oxygen
  loop. `tile/infrastructure/TileJettisonPort.java`, `subsystem/ejection/EjectionPort.java`. [V/T]
- **MECH-ATM-28 the clearance walk is block-frame, the ejection is not** — a ship's blocks live in a
  stationary subspace and that is where the world stores them, so "are the next blocks along my
  facing empty?" is ordinary block arithmetic and is right on a flying hull with no transform. The
  item ENTITY is world-frame, so its spawn point, its direction and the ship's own velocity all
  cross the seam. The direction is taken as the difference of two SUBSPACE points mapped through the
  same transform, which needs no ship identity and degrades to the plain facing vector off a ship,
  by construction. The carry matters as much as the aim: an item released without the hull's motion
  simply reappears inside it a tick later. `subsystem/ejection/EjectionPort.java`. [V]
- **MECH-ATM-24 zone priority is set per vent, from its own screen** — maintainer ruling 2026-08-15:
  assignment through the GUI, and **every zone equal by default**, so a ship nobody has configured
  gets a plant that simply shares what it has. Three steps (low / normal / high) cycled by one
  button; the value rides the vent's existing `INetworkMachine` packet channel, is clamped
  SERVER-side rather than trusted, and persists in the vent's NBT (`zonePriority`). Under a deficit
  the network fills the higher tier completely before the next one gets anything — that is
  MECH-NET-04 acting through this domain, not a second mechanism.
  `tile/atmosphere/TileOxygenVent.java`. [V/T]
- **MECH-ATM-23 the ventilation commodity is ABSOLUTE, not a partial pressure** — the network moves
  *regeneration work* in **ppm·blocks per tick**: a partial pressure describes one room, so a plant
  quoting one could not say what it is worth to a ship of rooms, and a large cabin would be scrubbed
  as fast as a cupboard on the same number. The vent multiplies by its zone volume when it asks and
  divides when it receives. This is what makes a duct's capacity mean "supports this much crew".
  The commodity keeps the COARSER unit on purpose: the shared network primitive carries an `int`, and
  a real cabin's carbon dioxide across a real volume would saturate one in the composition's own
  unit, so `LifeSupportNetwork` converts in both directions and is the single place the two meet.
  `atmosphere/LifeSupportNetwork.java`. [V]

## State & persistence

The persisted and wire-only NBT keys of the vent, recirculator, detector, wearable and suit tiles and items are
inventoried in `C1-nbt-persistence`. Not in C1: `Items` (EmbeddedInventory, `ItemSpaceArmor`/`ItemSpaceChest`) — the modular
component slots, the entire suit persistence surface; and the vent's `airState` (the zone's gas contents), which is held in
`pendingAirState` until `registerBlob` on the first tick and then applied once (the zone's SHAPE is rebuilt from the world on load,
but its contents are not derivable from blocks — without them a half-used cabin would reload full of fresh air).

Registry names (C3): blocks `oxygenVent`, `oxygenScrubber` (unlocalized `scrubber`),
`airRecirculator`, `oxygenCharger`, `oxygenDetection`, `suitWorkStation`; item `carbonDust`
(ore-dictionary `dustCarbon`, joined on purpose so tech-mod carbon and ours are one thing);
TE id `ARairRecirculator`. No atmosphere is registered by name any more: there is no name registry.

## Invariants

- **INV-ATM-01 [V][BEH]** A handler exists for a dim only when `enableOxygen && hasSurface() &&
  (overrideGCAir || dimId!=getMoonId() || isNativeDimension)` (the galaxy's Moon id). `AtmosphereHandler.java:65`.
- **INV-ATM-02 [V][BEH]** With `enableOxygen=false`, every type query returns `AtmosphereType.AIR` and
  entities never take atmosphere damage. `AtmosphereHandler.java:413-457`.
- **INV-ATM-03 [V][SYS]** Breathable types (`AIR`,`PRESSURIZEDAIR`) do not tick; hostile types
  (`VACUUM`,`LOWOXYGEN`,`HIGHPRESSURE`,`VERYHOT`,…) do — by the `canTick` constructor argument of each
  built-in. `AtmosphereType.java:25-37,69-74`. FOR: INV-ATM-05.
- **INV-ATM-04 [V][SYS]** `AIR`/`PRESSURIZEDAIR` breathable; `VACUUM` and all `*NOO2` not breathable —
  the `isBreathable` constructor argument of each built-in; the constructor keeps the given name.
  `AtmosphereType.java:25-37,69-74`. (Was `[T]`, same deletion.) FOR: INV-ATM-05.
- **INV-ATM-05 [V][BEH]** Unprotected entities are affected; immunity requires creative/spectator, a
  rocket/capsule ride, the grace window, or helm+chest (+legs+feet where a hazard acts on the whole
  body rather than on what is breathed). All of it or none of it: an entity that fails the strictest
  requirement takes every active hazard. `AtmosphereHazards.java`.
- **INV-ATM-06 [V][SYS]** Tick effects are suppressed when `AtmosphereTickEvent` is cancelled or the
  type is immune to the entity class. `AtmosphereHandler.java:256-262`. FOR: INV-ATM-05.
- **INV-ATM-07 [A][BEH]** A vent seals only with BOTH oxygen and power; missing either → no seal, zero
  blob. `OxygenVentRequiresFuelAndPowerTest.java:96-109`. Pinned by `OxygenVentRequiresFuelAndPowerTest#ventWithoutOxygenLosesHasFluidAndRevertsAtmosphere`, `OxygenVentRequiresFuelAndPowerTest#ventWithoutPowerDoesNotSealEvenWhenFueled`.
- **INV-ATM-08 [A][BEH]** The blob is bounded by `oxygenVentSize`: a sealed volume within the cap
  pressurises, one past it voids to the dim baseline (binary, no partial fill).
  `OxygenVentBoundedByBlobCapTest.java:88-109`. Pinned by `OxygenVentBoundedByBlobCapTest#ventSealsWithinCapButNotBeyondIt`.
- **INV-ATM-09 [A][BEH]** When the tank cannot supply `blobSize × gasUsageMult` O2 the vent flips
  `hasFluid=false` and sets the blob to the dim default atmosphere. `TileOxygenVent.java:269-287`;
  pinned `OxygenVentRequiresFuelAndPowerTest.java:71-91`. Pinned by `OxygenVentRequiresFuelAndPowerTest#ventWithoutOxygenLosesHasFluidAndRevertsAtmosphere`.
- **INV-ATM-10 [A][BEH]** Empty scrubber → comparator 0; fresh cartridge → comparator > 0.
  `CO2ScrubberComparatorOutputTest.java:41-74`. Pinned by `CO2ScrubberComparatorOutputTest#emptyScrubberReportsZeroComparatorOutput`, `CO2ScrubberComparatorOutputTest#freshCartridgeReportsNonZeroComparatorOutput`.
- **INV-ATM-11 [V][BEH]** Gas pad transfers O2 only when tank fluid is oxygen and H2 only when it is
  not, never both in one tick. `TileGasChargePad.java:120-168`.
- **INV-ATM-12 [V][SYS]** Blob membership excludes cells that are sealed or already owned by another
  blob. `AtmosphereBlob.java:64-71` (seam). FOR: INV-ATM-08.
- **INV-ATM-13 [V][BEH]** Chest protection is free in a combustible (O2-bearing) atmosphere but costs
  1 O2 per commit otherwise; zero air → no protection. `ItemSpaceChest.java:273-286`.
- **INV-ATM-14 [V]** Connectionless player entities (FakePlayer/headless) get cache/sync
  bookkeeping but no packet-bearing effects — prevents a netty NPE crashing the tick loop.
  `AtmosphereHandler.java:251-254`; `Atmosphere.java:33-40`.
- **INV-ATM-15** — retired.
- **INV-ATM-16 [T][SYS]** Breathing conserves zone pressure: oxygen falls by exactly the amount that
  appears as CO2, and never below zero. `AirStateTest`. Pinned by `AirStateTest#breathingConvertsOxygenIntoCarbonDioxideWithoutChangingPressure`, `AirStateTest#breathingCannotTakeOxygenThatIsNotThere`. FOR: INV-ATM-18.
- **INV-ATM-17 [T][SYS]** An untouched zone reports 100 (= 1.00 atm), the figure the analyser and
  `PacketAtmSync` carried when zone pressure was a constant. `AirStateTest`. Pinned by `AirStateTest#breathableAirReadsAsOneAtmosphere`. FOR: INV-ATM-18.
- **INV-ATM-18 [V][BEH]** Oxygen inside the configured band is breathable; below it `LOWOXYGEN` (or
  `NOO2` at exactly zero), above it `HIGHOXYGEN`; no gas at all is `VACUUM` whatever the
  composition (`AirState.java:506-507`, `oxygenRung` at `:544-557`). Only the vacuum half has a test
  (`AirStateTest#aZoneWithNoGasInItIsVacuumWhateverItsComposition`); the band bounds are read from code.
- **INV-ATM-19 [V][SYS]** Life support acts on a zone exactly while the MACHINE holding it says it is
  maintaining the air — `IBlobHandler.isMaintainingAtmosphere()`, which `TileOxygenVent` answers as
  `isSealed && hasFluid` and every other handler leaves at its `false` default. A planet's own
  atmosphere is therefore excluded because nothing is maintaining it, not because of anything about
  its type. `AtmosphereHandler.isLifeSupportManaged`, `TileOxygenVent.java:308-317`.
  The test is deliberately not against the atmosphere the zone PUBLISHES: that agrees with the gases
  only in the one case a refresh is not for, and would latch the zone on the first hazard it reached. FOR: INV-ATM-09.
- **INV-ATM-21 [T][BEH]** Fire and lungs are two different questions about the same gas: in the band
  BETWEEN the two thresholds a room burns and cannot be breathed, and far below both it does neither.
  `test/server/CombustionFollowsTheOxidiserTest` (no unit pin of the in-between band: it needs a loaded
  configuration) Pinned by `CombustionFollowsTheOxidiserTest#aRoomTooThinToBurnRefusesFireWhileItsLabelStillSaysOtherwise`, `LifeSupportZoneTest#theCombinerRefusesToPushOxygenPastTheSafeCeiling`.
- **INV-ATM-22 [T][A][SYS]** A strictly better atmosphere never reads as worse — adding oxidiser never takes
  breathability or combustion away, and removing a poison never leaves the room toxic. The poison half
  `[T]`: `test/unit/AtmospherePredicatesTest#aPoisonMakesAirToxicAndDrawingItOffClearsIt`. The oxidiser
  half `[A]`, unpinned: no test sweeps the configured bands. FOR: INV-ATM-18.
- **INV-ATM-23 [T][SYS]** A poison is judged against ITS OWN limit, so the same amount of two different
  gases is not the same hazard, and good air is no defence.
  `test/unit/AtmospherePredicatesTest#aPoisonIsJudgedAgainstItsOwnLimitAndNotAgainstTheAirAroundIt` FOR: INV-ATM-18.
- **INV-ATM-24 [T][SYS]** A composition survives a save including a substance the three old keys could not
  name, and a substance this game no longer knows is dropped rather than guessed at.
  `test/unit/AtmospherePredicatesTest#aCompositionSurvivesASaveAndAnUnknownGasIsDropped` FOR: INV-ATM-18.
- **INV-ATM-20 [T][SYS]** Regeneration is respiration run backwards: CO2 becomes oxygen one for one,
  total pressure is unchanged (the solid carbon never held any), and it cannot process CO2 that is
  not there. A zone driven out of the band by breathing can be brought back into it. `AirStateTest`. Pinned by `AirStateTest#regenerationIsBreathingRunBackwards`, `AirStateTest#regenerationCannotInventCarbonDioxide`. FOR: INV-ATM-18.

## Failure modes & edge cases

- Vent `firstRun` deliberately starts `isSealed=true` to count scrubbers, then forces a reseal;
  a blob of size 0 or an off/underpowered vent immediately clears the blob and deactivates
  scrubbers. `TileOxygenVent.java:221-253,362-372`.
- `onBlockChange` runs a nearby-blob scan on *every* server block edit; the source comment admits
  the reaction paths "were NEVER tested" and several are commented out for prior stack-overflows.
  `AtmosphereHandler.java:122,162-202`.
- Threaded blob fill (`atmosphereHandleBitMask & 1`) drops calculations on an oversized queue with
  only a warning; the executor is `ServerState.atmosphereFillPool` (per server, shut down on release),
  not a class-load static. `AtmosphereBlob.java:34,92-98`, `ServerState.java:56,121`.
- `TileGasChargePad` performs all its side-effects inside `canPerformFunction()` (a predicate);
  `performFunction()` is empty. `TileGasChargePad.java:94-179`.
- **The entity gate is world-keyed while a ship's blob is subspace-keyed [T]** — the three `Entity`
  overloads build the key from `entity.posX/Y/Z` (`AtmosphereHandler.java:448,468,484`), but a vent on
  an assembled VS ship seeds its blob at the vent's SUBSPACE `BlockPos` (`TileOxygenVent.java:398-400`).
  Measured 2026-07-26: the aboard vent seals identically to a ground control (`blobSize=28`), the
  subspace cell reads `PressurizedAir`, and a player standing inside that same pressurised cabin
  resolves the dimension default (`air`) — `test/client/VSShipAtmosphereFrameSpikeTest.java`, which pins
  the current wrong behaviour. Latent while host worlds default to breathable air; the sealing half is
  NOT implicated. The intended fix is blob-anchored conversion at the three overloads;
  overlapping-blob precedence is `HashMap` order today (`:449`) and becomes deterministic there.

## Integration seams

- **Events (C5):** subscribes `LivingUpdateEvent`, `PlayerChangedDimensionEvent`,
  `PlayerLoggedOutEvent`; posts `AtmosphereEvent.AtmosphereTickEvent` (cancellable).
- **Capabilities (C5):** `CapabilitySpaceArmor.PROTECTIVEARMOR` (armor items provide themselves),
  `CapabilityWear.PART_WEAR` (`TileWearable`), consumes `CapabilityFluidHandler` on inner stacks.
- **Packets (C2):** `PacketAtmSync`, `PacketOxygenState`, `PacketAirParticle` (from blob trace),
  `PacketMachine` (vent redstone/trace + detector selection), `SPacketUpdateTileEntity` (vent seal).
- **Damage sources:** `Vacuum`, `LowOxygen`, `Heat`, `OxygenToxicity` (all bypass armor, absolute).
- **Cross-subsystem:** `DimensionProperties.getAtmosphere()` supplies the default type;
  `stellurgyRocketTransferGrace` written by rocket-entity/event-handlers; `BlockSeal`,
  `BlockRedstoneEmitter`, `SealableBlockHandler` from blocks/util.

## Config surface

Every `enableOxygen`, `lifeSupport*`, `oxygenVent*`, `jettisonPort*`, `atmosphereHandleBitMask`,
`vacuumDamage`, `enableNausea` and `scrubberRequiresCartrige` flag is specified in `C4-config-surface`
with the mechanic that reads it and its full-disable path. Only two entries are not there:
`bypassEntity` / `entityAtmBypass` (MECH-ATM-04) — entity classes immune to all effects — and
`spaceSuitOxygenTime`, which is legacy and referenced only in a commented-out `getMaxAir` path.

## Test coverage

| invariant | test |
|-----------|------|
| INV-ATM-03/04 | none — `[V]` above |
| INV-ATM-07/09 | `test/server/OxygenVentRequiresFuelAndPowerTest.java` |
| INV-ATM-08 | `test/server/OxygenVentBoundedByBlobCapTest.java` |
| INV-ATM-10 | `test/server/CO2ScrubberComparatorOutputTest.java` |
| INV-ATM-16/17/18/20 | `test/unit/AirStateTest.java` |
| INV-ATM-21 | `test/unit/AirStateTest.java` — the governor's ceiling, an already-enriched room getting nothing, and splitting leaving oxygen alone. |
| MECH-ATM-21 (both paths) | `test/server/LifeSupportZoneTest.java` — a placed separator draws its room's CO2 into its own tank and leaves the oxygen alone; flipped to combine by a real sneak-click it gives that oxygen back and the room reads breathable again. |
| INV-ATM-21 (governor, e2e) | `test/server/LifeSupportZoneTest.java` — oxygen climbs to `lifeSupportMaxPartialO2` **exactly** and stops there with gas still in the tank, so a machine that merely did nothing cannot pass. The ceiling is read from the server's config, not restated. |
| INV-ATM-19, MECH-ATM-16/20 | `test/server/LifeSupportZoneTest.java` — a maintained room holds sea-level air at 1 atm; an unpowered one has no zone at all (`airO2 == -1`); a powered recirculator clears its room's CO2, returns the oxygen, holds pressure, drops dust, **and the zone publishes a breathable atmosphere afterwards**. |
| MECH-ATM-22/23, INV-NET-01 | `test/server/VentilationNetworkTest.java` — a plant three duct-blocks away clears a room's CO2 and takes the carbon into its own slot; and the same run with a shield cable in place of one duct moves no air at all, with the first scenario as its positive control. |
| MECH-ATM-27/28 | `test/server/LifeSupportZoneTest.aJettisonPortThrowsItsCargoOverboard` and `.aBlockedJettisonPortHoldsItsCargo` — the pair, because an emptied slot alone is what VOIDING the cargo also looks like: the green asserts a loose item entity beside the port, and the counter-test walls the port in on every side so the outcome cannot depend on which way it was placed. |
| MECH-ATM-25/26 | `test/server/BreachAndIsolationTest` — a breached room's air is still reachable from its vent and drains to vacuum instead of vanishing; a breached zone stops requesting from the plant; a closed bulkhead divides one hall into two zones. |
| MECH-ATM-24 zone priority | `test/server/VentilationNetworkTest.underADeficitTheHigherPriorityZoneIsServedFirst` — two rooms on one plant with the supply deliberately cut below what either alone could absorb: the prioritised room is served and the other gets NOTHING, which is what separates a priority from a share-out. Measured against a snapshot taken immediately before the solve, because the server ticks between commands. |
| MECH-ATM-29/30/31, INV-ATM-21..24 | `test/unit/AtmospherePredicatesTest` (7 scenarios) and `test/server/CombustionFollowsTheOxidiserTest`. Falsified both ways: tying the combustion band back to breathing left ONLY the between-bands assertion red, and making the handler obey the label again turned the server room combustible with `combustible:true` beside `breathableAir:false` in one readout. The save scenario found a real defect in the implementation — an unknown substance was being stored under a null key and counted toward the pressure. |
| MECH-ATM-20 (partly) | **not pinned**: the full-output back-up and the dust-buffer rounding are still unverified — the e2e proves dust appears, not that a jammed slot stops conversion. |
| (suit NBT round-trip) | none — no test pins the suit capability's NBT round trip; the legacy `air` key production no longer writes is dead `[V]`. |
| entity-effect wiring | `test/server/AtmospherePlayerEventTest.java`, `AtmosphereOxygenSmokeTest.java` |
| suit fluid/drain | `test/client/ItemSpaceArmorUseFluidE2ETest.java`, `ItemSpaceChestSubInventoryDrainE2ETest.java`, `GasChargePadFillsPressureTankE2ETest.java`, `OxygenSuitClientStateE2ETest.java` |

## Open questions

- Does any live caller depend on `ItemSpaceChest.setAirRemaining` actually storing air? It is a
  no-op stub; the `/stellurgytest` harness calls it.
- Is `enableNausea`'s class-load capture reached before or after config parse at real launch?
  Determines whether the config flag has any effect.

## The vent supplies GAS, not a label

`TileOxygenVent.replenishOxygen` puts the oxygen the vent has just paid for into the zone's
`AirState`, at the one exchange rate the rest of life support uses and up to the same safe band the
separator obeys. Without it the machine would drain its tank, publish `PRESSURIZEDAIR` and leave
the composition untouched — so a question asked of the GAS rather than of the name
would answer "unbreathable", inside a sealed, powered, fuelled room.

The same entry's other half is `TileOxygenVent.breached`. Venting a zone's air must not fire merely
because the vent is not `isSealed && hasFluid`, which is true on the first tick after every load (the seal is
deliberately dropped so it gets re-checked), while the machine is switched off, and during a brownout.
None of those is a hole in the hull. The flag is set only where the room is actually gone — a seal
that HELD and then found its zone empty, or a seal check that ran and failed — and is deliberately not
persisted, because after a load the check answers for itself within a hundred ticks.
