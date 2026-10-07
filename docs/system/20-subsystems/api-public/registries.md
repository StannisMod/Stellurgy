# api-public / registries — satellite, fuel, atmosphere & holder statics

Files: `api/SatelliteRegistry.java` (148), `api/fuel/FuelRegistry.java` (259),
`api/StellurgyFluids.java` (15),
`api/StellurgyBiomes.java` (154), `api/StellurgyBlocks.java` (124),
`api/StellurgyItems.java` (54), `api/StellurgyAPI.java` (20), `api/Constants.java` (9),
`api/MaterialGeode.java` (15).

All are process-global mutable statics populated during mod init. They are the C3 registry surface.

> The per-file line counts on this line are UNVERIFIED; recount before citing one.

## Key types

| type | role |
|------|------|
| `SatelliteRegistry` | `name→Class<SatelliteBase>` + `ItemStack→SatelliteProperties`, id lookup from chip NBT |
| `FuelRegistry` | singleton `instance`; enum `FuelType` (7), each a `HashSet<FuelEntry>` |
| `StellurgyFluids` | 6 static `Fluid` handles |
| `StellurgyBiomes` | singleton; Stellurgy biome registration, blacklist, high-pressure, single-biome lists |
| `StellurgyBlocks` / `StellurgyItems` | public static block/item handles + `spaceSuit` armor material |
| `StellurgyAPI` | 5 public static service handles wired in init |
| `Constants` | modId, invalid-planet sentinel, star-id offset, asteroid gentype |

## Mechanics

### MECH-API-13 — satellite class & property registry
`registerSatellite(name, clazz)` maps a string id to a `SatelliteBase` subclass; `getKey(clazz)`
reverse-looks-up and returns the literal `"poo"` on miss (`SatelliteRegistry:57-73`).
`registerSatelliteProperty(stack, props)` maps a component ItemStack (stacksize-insensitive) to the
`SatelliteProperties` it contributes; `getSatelliteProperty` matches by item + (subtype-aware)
metadata (`:27-49`). `createFromNBT` instantiates by the `dataType` string then `readFromNBT`
(`:81-104`); `getNewSatellite` returns `null` on unknown name or reflection failure.

### MECH-API-14 — satellite id from an itemstack
`getSatelliteId(stack)` reads `satelliteId` when the item is an `ItemSatelliteIdentificationChip`,
else `satId` (`SatelliteRegistry:110-122`) — two different NBT keys for the same concept, by item
type. `getSatellite` resolves the id through `DimensionManager.getSatellite`; `getSatelliteProperties`
rebuilds a `SatelliteProperties` from the chassis NBT (`powerGeneration/powerStorage/dataType/maxData/
weight`) (`:134-147`).

### MECH-API-15 — fuel registry & matching
`FuelType` enum has fixed ordinal ids 0..6 (`LIQUID_MONOPROPELLANT..IMPULSE`, `:95-102`) exposed via
`id` and `getById(id)`. `registerFuel(type, fluid|item, multiplier)` wraps in a `FuelEntry` and adds
to that type's `HashSet` (`:21-38`). Matching (`fuelMatches`) compares ItemStacks with
`areItemStacksEqual` and Fluids by identity-or-name (`:212-227`); `FuelEntry.equals/hashCode` are
overridden so the set de-dupes on fuel+type (`:230-257`). `getMultiplier` returns the fuel-point
multiplier, 0 if unregistered (`:83-93`).

### MECH-API-16 — atmosphere registry (retired)
There is no such class. What can be
harvested is `DimensionProperties.getHarvestableGases()`, read off the planet's air — see
[`rocket-entity/vehicles-fx.md`](../rocket-entity/vehicles-fx.md) MECH-RKT-22.

### MECH-API-17 — gas-giant gas registry (retired)
`StellurgyFluids.registerGasGiantGas` / `FluidGasGiantGas` and the `spawnableGasses` config list do not
exist: the set would have no reader; what a giant holds is derived by
`BodyAtmosphere.derive` (CON-C21-11).

## Invariants

- **INV-API-12 [V]** `FuelType` ordinals are a wire/save contract: `getById` indexes
  `values()[id]` with no bounds check (`FuelRegistry:113-115`) — reordering the enum reassigns saved
  fuel ids.
- **INV-API-13 [V]** `getSatelliteId` returns `-1` when the stack has no tag or id; all callers treat
  `-1` as "no satellite" (`SatelliteRegistry:121,:129,:135`).
- **INV-API-14 [V]** The chip id key is `satelliteId`, the chassis id key is `satId` — divergent by
  item class (`:115-118`); `SatelliteProperties` persists `satId` (`SatelliteProperties:140`).
- **INV-API-15** — retired.
- **INV-API-16 [V]** `Constants.INVALID_PLANET = Integer.MIN_VALUE + 1`; `Integer.MIN_VALUE` is
  reserved for warp; `STAR_ID_OFFSET = 10000` (`Constants:6-8`). These are id-space contracts.
- **INV-API-17 [V]** `getKey(clazz)` returns the literal string `"poo"` for a class not in the
  registry (`SatelliteRegistry:72`); that value is what `SatelliteBase.writeToNBT` would persist as
  the `dataType` discriminator for an unregistered satellite class. .

## State & persistence (C1) — keys read/written by these statics

`satelliteId` (chip), `satId` (chassis / `SatelliteProperties`), `powerGeneration`, `powerStorage`,
`dataType`, `maxData`, `weight` — all read in `getSatelliteProperties` (`SatelliteRegistry:140`).
`dataType` (string satellite-type id) is the discriminator for `createFromNBT`.

## Integration seams

- C3 registry names: satellite string ids (registered by satellite subsystem), `FuelType` ordinals,
  `DataType` ordinals (see satellite-data), atmosphere unlocalized names.
- `StellurgyAPI` static handles wired in init: `atomsphereSealHandler` (`IAtmosphereSealHandler`),
  `spaceObjectManager` (`ISpaceObjectManager`), `dimensionManager` (`IGalaxy`), `gravityManager`
  (`IGravityManager`), `enchantmentSpaceProtection` (`Enchantment`) (`StellurgyAPI:14-19`).
- `StellurgyBiomes.registerBiome(biome, IForgeRegistry)` participates in Forge biome registry;
  `blackListVanillaBiomes()` bulk-blacklists (`StellurgyBiomes:62,:105`).

## Config surface

`loadPostInit` drives MECH-API-15 from `rocketFuels`/`rocketBipropellants`/`rocketOxidizers`/
`rocketNuclearWorkingFluids`. `blackListAllVanillaBiomes`
gates `StellurgyBiomes.blackListVanillaBiomes()`.

## Open questions

- `StellurgyBlocks`/`StellurgyItems` are bare public-static handle bags populated by
  the blocks/items subsystems during registration; their *population* is documented there, not here.
