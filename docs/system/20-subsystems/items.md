---
id: items
owns: [item/]
entrypoints: [ItemSatellite#setSatellite, ItemStationChip#useNetworkData, ItemJetpack#onTick, ItemData#getDataStorage, ItemBasicLaserGun#onUsingTick]
depends-on: [api-public, dimension-planets, satellite, util-core, atmosphere-oxygen, rocket-entity, network-wire, client-render]
depended-by: [inventory-containers, multiblock-machines, infrastructure-tiles, rocket-assembly, commands-gameplay, misc-oddities]
contracts: [C1, C2, C3, C4, C5]
confidence: high
---

## Purpose

The `item/` package holds the mod's 27 gameplay item classes: data-carrier chips, satellite/planet/station identification chips, remote-control satellite terminals, suit components (`IArmorComponent`), fluid/data `ItemBlock` wrappers, and a handful of tools/gadgets. Every item is a thin behavioural shell — its entire runtime state lives in the `ItemStack` NBT tag, and almost all business logic is delegated to `api-public`, `satellite`, `dimension`, and `util` types. This doc is a catalogue: the shared patterns are described once in prose, then one table row per item.

## Responsibility boundary

**Owns:** the item classes, their per-stack NBT read/write helpers, their tooltip rendering, their `INetworkItem` GUI round-trips, and the `IDataItem` interface (`IDataItem.java:22`).
**Does NOT own:** item *registration* / registry names (done in `Stellurgy.java` + `api/StellurgyItems.java`, owned by `misc-oddities`/`api-public`); the `DataStorage`/`MultiData`/`SatelliteProperties`/`StorageChunk` NBT *schemas* (owned by `api-public`/`util-core`); satellite *behaviour* (`SatelliteBiomeChanger`, `SatelliteWeatherController`, `SatelliteOreMapping` — owned by `satellite`); `TooltipInjector` (owned by `client-render`).

## Shared patterns (described once)

- **NBT-as-state.** No item holds mutable instance fields for stack state. Getters return a default (or `null`/sentinel) when `!stack.hasTagCompound()`; setters lazily create the compound and re-attach it. This is the single dominant mechanic (`MECH-ITM-01`).
- **Tooltip.** Client-only `addInformation` (`@SideOnly(Side.CLIENT)`). Simple items delegate to `TooltipInjector.renderShiftAlt(...)` for a shift/alt-gated hint; stateful items render their programmed state (`MECH-ITM-02`).
- **Chip programming.** ID chips bind to a live `SatelliteBase` / `DimensionProperties` by storing an id, then resolve it through `DimensionManager` / `SatelliteRegistry` at read time (`MECH-ITM-04`).
- **`INetworkItem` GUI.** Modular-inventory items round-trip button presses client→server via libVulpes `PacketItemModifcation` and the `writeDataToNetwork`/`readDataFromNetwork`/`useNetworkData` triple (`MECH-ITM-05`).
- **`IArmorComponent` suit parts** implement `onTick`/`renderScreen`/`isAllowedInSlot`/`getComponentIcon` and are driven by the armor tile, not by the item (`MECH-ITM-06`).

## Key types & catalogue

Base classes: `ItemIdWithName` (stores `"name"` string) is the parent of the chip family; `ItemMultiData`→`ItemData`-style carriers; libVulpes `ItemIngredient`/`ItemBlockMeta`/`ItemTool` for the rest.

| class | base / interfaces | role | stack NBT keys | key cite |
|-------|-------------------|------|----------------|----------|
| `IDataItem` | interface | data-container contract (get/add/remove/setData, getMaxData) | — | IDataItem.java:22 |
| `ItemData` | ItemIngredient, IDataItem | data "stick"; max 1000 @dmg0, inert otherwise | DataStorage root | ItemData.java:26,51 |
| `ItemMultiData` | Item | multi-type data carrier (COMPOSITION/MASS/DISTANCE) | MultiData root | ItemMultiData.java:54 |
| `ItemBlockDataBusBig` | ItemBlock, IDataItem | data-bus block item; cap = 2000×`dataBusBigMultiplier`; merges NBT into TE on place | DataStorage root | ItemBlockDataBusBig.java:54,130 |
| `ItemIdWithName` | Item | base chip: stores display `"name"` | `name` | ItemIdWithName.java:17 |
| `ItemAsteroidChip` | ItemMultiData | asteroid data chip; deterministic display id | `UUID`(long), `astype` | ItemAsteroidChip.java:47,70 |
| `ItemPlanetIdentificationChip` | ItemIdWithName | binds a planet dimId | `dimId`, `DimensionName`, `UUID`(long) | ItemPlanetIdentificationChip.java:70,102 |
| `ItemStationChip` | ItemIdWithName, IModularInventory, IButtonInventory, INetworkItem | station id + per-dim landing-location list GUI | `UUID`(int), `dimid<N>`→{`dests`,`selectionId`}, `TmpName` | ItemStationChip.java:58,242,274 |
| `ItemSatelliteIdentificationChip` | Item, ISatelliteIdItem | binds a live satellite by id | `satelliteId`(long), `dimId`, `satelliteName`, `name`, `weight`(float) | ItemSatelliteIdentificationChip.java:54,74 |
| `ItemSatellite` | ItemIdWithName | satellite chassis: 7-slot embedded inv, assembles into `SatelliteProperties` | `inv` (EmbeddedInventory) + SatelliteProperties | ItemSatellite.java:92,113 |
| `ItemBiomeChanger` | ItemSatelliteIdentificationChip, IModularInventory, INetworkItem | remote for `SatelliteBiomeChanger` | net-only `biome`(int) | ItemBiomeChanger.java:160,175 |
| `ItemWeatherController` | ItemSatelliteIdentificationChip, IModularInventory, INetworkItem | remote for `SatelliteWeatherController` | net-only `mode_id`,`floodlevel`,`last_mode_id` | ItemWeatherController.java:159,175 |
| `ItemOreScanner` | Item, IModularInventory | opens ore-map GUI for a bound `SatelliteOreMapping` | `id`(long) | ItemOreScanner.java:54,78 |
| `ItemSpaceElevatorChip` | Item | stores a list of `DimensionBlockPosition` | `list` (NBTStorableListList) | ItemSpaceElevatorChip.java:26,36 |
| `ItemPackedStructure` | Item | stores a captured `StorageChunk` | `chunk` | ItemPackedStructure.java:24,39 |
| `ItemJetpack` | Item, IArmorComponent, IJetPack | chest suit thruster; NORMAL/HOVER modes, burns hydrogen | `enabled`,`mode`(int),`height`(float),`modeSwitch` | ItemJetpack.java:49,140,242 |
| `ItemPressureTank` | ItemIngredient, IArmorComponent | chest suit air tank; cap=base×2^dmg; item capability | Forge fluid capability | ItemPressureTank.java:102,124 |
| `ItemUpgrade` | ItemIngredient, IArmorComponent | leg/boots/speed/hover suit upgrades (by damage) | — (reflection sets walkSpeed) | ItemUpgrade.java:40,92 |
| `ItemAtmosphereAnalzer` | Item, IArmorComponent | head suit HUD; right-click prints atmosphere | — | ItemAtmosphereAnalzer.java:79,120 |
| `ItemBeaconFinder` | Item, IArmorComponent | head suit HUD; renders beacon direction markers | — | ItemBeaconFinder.java:65 |
| `ItemSealDetector` | Item | probes whether a block seals a room | — | ItemSealDetector.java:38 |
| `ItemHovercraft` | Item | ray-traces & spawns `EntityHoverCraft` | — | ItemHovercraft.java:38 |
| `ItemBasicLaserGun` | Item | channelled ray-trace laser; mines/attacks | transient per-user target (a per-world `WorldRuntime` part, not NBT) | ItemBasicLaserGun.java:49,125 |
| `ItemJackHammer` | ItemTool | fast rock/iron/geode digger; titanium-repairable | — | ItemJackHammer.java:29,43 |
| `ItemThermite` | Item | fuel item, 6000-tick burn time | — | ItemThermite.java:19 |
| `ItemBlockFluidTank` | ItemBlock | portable fluid tank; cap=64000×`blockTankCapacity`; dumps to TE on place | Forge FluidTank NBT | ItemBlockFluidTank.java:40,93 |
| `ItemBlockCrystal` | ItemBlockMeta | metadata name delegate for crystal block | — | ItemBlockCrystal.java:17 |

## Mechanics

- **MECH-ITM-01 — NBT-as-stack-state.** Every stateful item reads a default when the stack has no tag and lazily attaches a compound on write. `ItemData.getDataStorage` (ItemData.java:51), `ItemStationChip.setLandingLocations` (ItemStationChip.java:274), `ItemJetpack.setEnabledState` (ItemJetpack.java:145).
- **MECH-ITM-02 — Tooltip rendering.** Client-only `addInformation`; simple items call `TooltipInjector.renderShiftAlt` (ItemJackHammer.java:54, ItemThermite.java:24), stateful items render programmed state (ItemSatellite.java:134, ItemStationChip.java:343).
- **MECH-ITM-03 — Data-container contract.** `IDataItem` unifies `ItemData` and `ItemBlockDataBusBig` so commands/slots/tiles accept either (IDataItem.java:22, ItemBlockDataBusBig.java:60). `ItemData` max is 1000 at damage 0, 0 otherwise (ItemData.java:26).
- **MECH-ITM-04 — Chip binding & resolution.** ID chips store an id and resolve a live object at read time: planet via `DimensionManager.getDimensionProperties` (ItemPlanetIdentificationChip.java:34), satellite via `DimensionManager.getSatellite` (ItemSatelliteIdentificationChip.java:31, ItemOreScanner.java:83).
- **MECH-ITM-05 — INetworkItem GUI round-trip.** Button press → `PacketItemModifcation` → `writeDataToNetwork`/`readDataFromNetwork` (into a scratch NBT) → server `useNetworkData` mutates stack or bound satellite (ItemStationChip.java:157-212, ItemBiomeChanger.java:160-188, ItemWeatherController.java:159-180).
- **MECH-ITM-06 — Suit component tick.** `IArmorComponent.onTick` runs from the armor tile: jetpack applies thrust & drains hydrogen (ItemJetpack.java:49,177); `ItemUpgrade` reflects into `PlayerCapabilities.walkSpeed` for leg upgrades and zeroes fall distance for boots (ItemUpgrade.java:40).
- **MECH-ITM-07 — ItemBlock place → TE transfer.** `placeBlockAt` copies stack NBT into the freshly placed tile: data bus merges its `DataStorage` (ItemBlockDataBusBig.java:130), fluid tank fills the TE from its item tank (ItemBlockFluidTank.java:71).
- **MECH-ITM-08 — Satellite chassis assembly.** `ItemSatellite` holds a 7-slot guarded inventory (slot 0 = MAIN core, 1-6 = POWER_GEN/BATTERY/DATA); `setSatellite` writes finished `SatelliteProperties` into the stack (ItemSatellite.java:60,113).
- **MECH-ITM-09 — Ray-trace action items.** `ItemHovercraft` ray-traces a placement point and spawns `EntityHoverCraft` server-side (ItemHovercraft.java:38); `ItemBasicLaserGun` channels over `getMaxItemUseDuration`=16, ray-tracing entities then blocks each tick (ItemBasicLaserGun.java:93).

## State & persistence (NBT — contract C1)

All keys are read from / written to `ItemStack` tag compounds. Contractual keys (survive save/wire): `name`, `UUID` (int on StationChip, long on Asteroid/PlanetID chips), `dimId`/`dimid<N>`, `dests`, `selectionId`, `satelliteId`, `satelliteName`, `weight`, `astype`, `DimensionName`, `id` (ore scanner), `list`, `chunk`, `inv`, `enabled`, `mode`, `height`, `modeSwitch`. Nested schemas (`DataStorage`, `MultiData`, `SatelliteProperties`, `StorageChunk`, Forge `FluidTank`) are owned upstream. Transient network-only keys (never persisted, live inside `PacketItemModifcation`'s scratch NBT): `biome`, `mode_id`, `floodlevel`, `last_mode_id`, `TmpName`.

Backward-compat: `ItemStationChip.getLandingLocations` migrates legacy flat `x`/`y`/`z` floats under `dimid<N>` into a `"Last"` `LandingLocation` and removes the old tags (ItemStationChip.java:252-264).

## Integration seams

- **Packets (C2):** libVulpes `PacketItemModifcation` (all `INetworkItem` items), `PacketSatellite` sent to sync satellite state before opening remote GUIs (ItemBiomeChanger.java:107, ItemWeatherController.java:106).
- **Capabilities (C5):** `ItemPressureTank.initCapabilities` exposes a `TankCapabilityItemStack` (ItemPressureTank.java:124); `ItemBlockFluidTank` uses Forge `CapabilityFluidHandler` on the placed TE (ItemBlockFluidTank.java:80).
- **Registry (C3):** items are instantiated & registered in `Stellurgy.java`; fields live in `StellurgyItems` (e.g. `itemUpgrade`, `itemJetpack`) — referenced back by `ItemJetpack.onTick` to detect helmet upgrades (ItemJetpack.java:66).
- **Cross-subsystem calls:** `AtmosphereHandler` (ItemAtmosphereAnalzer.java:84), `SealableBlockHandler` (ItemSealDetector.java:42), `WeightEngine` (ItemSatellite.java:245), `EntityHoverCraft` (ItemHovercraft.java:81).

## Config surface

Config: see `C4-config-surface`.

## Invariants

- **INV-ITM-01 [A][SYS]** Fresh chip with no NBT returns its sentinel default: PlanetID → `INVALID_PLANET` (ChipNBTRoundTripTest.java:54), Asteroid UUID/type → `null` (ChipNBTRoundTripTest.java:100), StationChip UUID → `0` (ChipNBTRoundTripTest.java:127). Pinned by `ChipNBTRoundTripTest#planetChipDimIdReadDefaultsToInvalidPlanetWithoutNbt`, `ChipNBTRoundTripTest#asteroidChipUuidAndTypeRoundTrip`, `ChipNBTRoundTripTest#stationChipUuidDefaultsToZero`. FOR: save format: chip NBT survives reload.
- **INV-ITM-02 [A][SYS]** `set…` then `get…` round-trips through NBT and survives `ItemStack.copy()` without aliasing the original (ChipNBTRoundTripTest.java:66,150; the `ItemStack.copy()` survival for station, satellite and arbitrary chip NBT has no test, so the no-aliasing half is `[A]`). Pinned by `ChipNBTRoundTripTest#planetChipDimIdRoundTripsForRegisteredDim`, `ChipNBTRoundTripTest#asteroidChipUuidAndTypeRoundTrip`. FOR: save format: chip NBT survives reload.
- **INV-ITM-03 [A][BEH]** `erase()` / `setTagCompound(null)` drops the entire compound (ChipNBTRoundTripTest.java:115). Pinned by `ChipNBTRoundTripTest#planetChipEraseClearsAllNbt`.
- **INV-ITM-04 [A][SYS]** `setDimensionId(INVALID_PLANET)` still attaches a tag carrying the sentinel (ChipNBTRoundTripTest.java:89) — mirrors the code note at ItemPlanetIdentificationChip.java:73. Pinned by `ChipNBTRoundTripTest#planetChipSetDimensionIdWithInvalidPlanetAttachesNbtSentinel`. FOR: save format: chip NBT survives reload.
- **INV-ITM-05 [A][BEH]** `ItemData` max-data table: dmg0→1000, any non-zero dmg→0 (ItemDataCarrierNBTRoundTripTest.java:192); a programmed data stick never stacks past 1 (ItemDataCarrierNBTRoundTripTest.java:173; ItemData.java:37). Pinned by `ItemDataCarrierNBTRoundTripTest#dataStickMaxDataIsZeroForNonZeroDamage`, `ItemDataCarrierNBTRoundTripTest#dataStickNeverStacksPastOne`.
- **INV-ITM-06 [A][SYS]** `ItemSpaceElevatorChip.getBlockPositions` never returns null and yields an empty list on a fresh stack; `setBlockPositions([])` clears the `list` key and does not attach NBT to a tagless stack (ItemDataCarrierNBTRoundTripTest.java:59,93). The clear removes `"list"`, matching the fix noted at ItemSpaceElevatorChip.java:43. Pinned by `ItemDataCarrierNBTRoundTripTest#elevatorChipEmptyStackReturnsEmptyPositionList`, `ItemDataCarrierNBTRoundTripTest#elevatorChipSetEmptyOnFreshStackDoesNotAttachNbt`, `ItemDataCarrierNBTRoundTripTest#elevatorChipSetEmptyAfterNonEmptyClearsList`. FOR: save format: chip NBT survives reload.
- **INV-ITM-07 [A][BEH]** `ItemPressureTank.getCapacity` = base×2^damage (ArmorComponentContractTest.java:77). Pinned by `ArmorComponentContractTest#pressureTankCapacityScalesAsPowerOfTwoWithItemDamage`.
- **INV-ITM-08 [A][SYS]** `ItemJetpack.setEnabledState(true)` writes the `enabled` key; `(false)` clears it; a fresh jetpack is disabled (ArmorComponentContractTest.java:92). Pinned by `ArmorComponentContractTest#jetpackEnabledStateToggleStoresAndClearsNbtFlag`. FOR: save format: jetpack state survives reload.
- **INV-ITM-09 [A][BEH]** Suit slot eligibility is fixed: jetpack/pressure-tank → CHEST only (ArmorComponentContractTest.java:53,65); `ItemUpgrade` leg/speed→LEGS, boots→FEET, else HEAD (ItemUpgrade.java:92). Pinned by `ArmorComponentContractTest#jetpackIsAllowedOnlyInChestSlot`, `ArmorComponentContractTest#pressureTankIsAllowedOnlyInChestSlot`.
- **INV-ITM-10 [A][BEH]** `ItemThermite.getItemBurnTime` = 6000 regardless of count/NBT (SpecialPurposeItemContractTest.java:57). Pinned by `SpecialPurposeItemContractTest#thermiteBurnTimeMatchesFurnaceContract`, `SpecialPurposeItemContractTest#thermiteBurnTimeIsStackInsensitive`.
- **INV-ITM-11 [A][SYS]** `ItemBiomeChanger.readDataFromNetwork` persists packet id 0 into NBT key `biome`, and leaves NBT untouched for unknown ids (SpecialPurposeItemContractTest.java:106,121). Pinned by `SpecialPurposeItemContractTest#biomeChangerReadDataFromNetworkPersistsBiomeIdToNbt`, `SpecialPurposeItemContractTest#biomeChangerReadDataFromNetworkOtherPacketIdIsNoOp`. FOR: wire format: biome-changer packet.
- **INV-ITM-12 [A][SYS]** `ItemWeatherController.readDataFromNetwork` maps the three ints to `mode_id`/`floodlevel`/`last_mode_id` in order (SpecialPurposeItemContractTest.java:158). Pinned by `SpecialPurposeItemContractTest#weatherControllerReadDataFromNetworkPersistsAllThreeFieldsToNbt`. FOR: wire format: weather-controller packet.
- **INV-ITM-13 [V][BEH]** `ItemSatellite.SatelliteModuleInventory` rejects invalid stacks: slot 0 accepts only MAIN, slots 1-6 only POWER_GEN/BATTERY/DATA (ItemSatellite.java:64-88).
- **INV-ITM-14 [V]** Server-authoritative mutation is guarded by `!world.isRemote` in `useNetworkData`/`onItemRightClick` (ItemStationChip.java:179, ItemOreScanner.java:80); client `onInventoryButtonPressed` only sends the packet.
- **INV-ITM-15 [A]** Landing-location coordinates persist as `float` (ItemStationChip.java:414); assumed acceptable because they are block-granular near-origin overworld coords, not high-precision physics.

## Failure modes & edge cases

- `ItemWeatherController`/`ItemBiomeChanger.useNetworkData` cast `getSatellite(stack)` to a concrete satellite type and dereference it without a null/instanceof guard — a stale chip whose satellite no longer exists NPEs on the server (ItemWeatherController.java:161,176; ItemBiomeChanger.java:178).
- `ItemWeatherController.useNetworkData` writes `floodlevel`/`mode_id` from the client packet with no server-side bounds check; the 1..180 clamp exists only on the client button path (ItemWeatherController.java:143 vs 175).
- `ItemData.getDataStorage` deliberately does **not** attach a tag on the tagless path (ItemData.java:59), so `getData` on a fresh stack is a pure read — relied on by INV-ITM-05.
- `ItemSatelliteIdentificationChip.getSatellite` mutates stack NBT (`dimId`,`name`) inside what reads like a getter, and is invoked from the client tooltip path (ItemSatelliteIdentificationChip.java:31-41).

## Test coverage

INV-ITM-01..12 pinned by `test/unit/ChipNBTRoundTripTest`, `ItemDataCarrierNBTRoundTripTest`, `SpecialPurposeItemContractTest`, `ArmorComponentContractTest`; satellite assembly by `test/server/SatelliteBuilderPressBuildContractTest`. INV-ITM-13/14 verified in code only. INV-ITM-15 is an assumption.

## Open questions

- `ItemSealDetector` prints `msg.sealdetector.notfullblock` when `isFullBlock` is **true** (ItemSealDetector.java:51) — the message reads inverted vs the predicate; needs a maintainer call on intended wording. (unconfirmed).
- `ItemWeatherController.floodlevel` instance field (=63) is written nowhere and read nowhere (ItemWeatherController.java:37); the real value lives on the satellite. (unconfirmed).
