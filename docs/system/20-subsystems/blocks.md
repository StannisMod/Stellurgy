---
id: blocks
owns: [block/]
entrypoints: [Stellurgy#registerBlocks (owner: misc-oddities), Block#createTileEntity, Block#onBlockActivated, Block#getStateFromMeta]
depends-on: [atmosphere-oxygen, dimension-planets, space-stations, rocket-assembly, api-public, client-render, world-gen]
depended-by: [rocket-assembly, multiblock-machines, infrastructure-tiles, satellite, client-render]
contracts: [C1, C3, C4, C7]
confidence: high
---

## Purpose
The `block/` package holds 59 `Block` subclasses — the placed-world half of Stellurgy's items. Almost every
class is a **thin shell**: it declares metadata↔blockstate encoding, spawns its `TileEntity`, opens a GUI,
draws a tooltip, and forwards real behaviour to a tile, the `AtmosphereHandler`, a `SpaceObject`, or
`DimensionProperties`. A minority carry genuine block-local logic (custom fire, plate press piston,
seal blob tracking, connected textures, atmosphere-gated relighting).

## Responsibility boundary
Owns: block classes, their `PropertyEnum/Bool/Direction` blockstate properties, meta↔state serialization,
placement/rotation rules, block-drop shaping, and block-side tooltip injection.
Does NOT own: block **registration / registry names** (that is `Stellurgy#registerBlocks`, subsystem
misc-oddities — see C3), the tiles themselves (`tile/*`), items/`ItemBlock`s (subsystem items), atmosphere
sealing math (atmosphere-oxygen), station/dimension bookkeeping (space-stations, dimension-planets).

## Shared pattern (read once, applies to the whole table)
1. **Facade construction.** Most non-vanilla-derived blocks take `(Class<? extends TileEntity> tileClass,
   int guiId)` (libVulpes `BlockTile`/`RotatableBlock` family) or a `Material`, are configured fluently in
   `registerBlocks` with `setUnlocalizedName/setHardness/setCreativeTab`, and stored in
   `StellurgyBlocks.*` static fields. `Stellurgy.java:1`
2. **TE creation.** `hasTileEntity=true` + `createTileEntity` returns a fresh tile; no NBT lives on the block.
3. **GUI open.** `onBlockActivated` server-side calls `player.openGui(<Stellurgy's container>, guiId,…)` —
   `Stellurgy.instance` in Stellurgy's blocks, the modid string in libVulpes' own `BlockTile` family, which
   has no mod object since it was folded in — and returns `true`; client returns `true` without acting.
   [V] `BlockRedstoneEmitter.java:74`, `libvulpes/block/BlockTile.java:83`
4. **Tooltip.** Client-only `addInformation` calls `TooltipInjector.computeInsertIndex` +
   `renderShiftAlt(stack,tooltip,"tooltip.stellurgy.<key>",insertAt)` — the shift/alt reveal keys
   (C7). `BlockRocketMotor.java:152`
5. **Meta = save contract.** `getMetaFromState`/`getStateFromMeta` fix the on-disk 0-15 metadata for each
   block; these encodings are contractual (C1) and are pinned below.

## Type & mechanic catalogue (one row per file)

| # | class | base | role / distinctive behaviour | anchor |
|--:|-------|------|------------------------------|--------|
| 1 | BlockRocketMotor | BlockFullyRotatable · IRocketEngine,IBrokenPartBlock | mono-prop engine; thrust 490 500 N fuel 1 `tunable`; `getActualState` faces toward adjacent `BlockFuelTank`; `TileBrokenPart(10,2×wearProb)`; drops damaged item by wear stage | MECH-BLK-01 |
| 2 | BlockAdvancedRocketMotor | ↳ BlockRocketMotor | thrust 2 452 500 N fuel 3 `tunable` | MECH-BLK-01 |
| 3 | BlockNuclearRocketMotor | ↳ BlockRocketMotor | thrust 1 716 750 N; `getActualState` faces `IRocketNuclearCore`; wear ×4 | MECH-BLK-01 |
| 4 | BlockBipropellantRocketMotor | BlockFullyRotatable · IRocketEngine,IBrokenPartBlock | bi-prop engine; thrust 490 500 N fuel 1; same broken-part flow | MECH-BLK-01 |
| 5 | BlockAdvancedBipropellantRocketMotor | ↳ BlockBipropellantRocketMotor | thrust 2 452 500 N fuel 3 | MECH-BLK-01 |
| 6 | BlockNuclearCore | Block · IRocketNuclearCore | `getMaxThrust = 49 050 000 N ×nuclearCoreThrustRatio` `tunable` | MECH-BLK-02 |
| 7 | BlockFuelTank | BlockFullyRotatable · IFuelTank | rocket fuel tank; `TANKSTATES{top,bottom,middle}` auto from vertical/horizontal neighbours; `getMaxFill 1000` `tunable`; `TileWearable`; meta = `tankstate + rotation×3` | MECH-BLK-03 |
| 8 | BlockBipropellantFuelTank | ↳ BlockFuelTank | `getMaxFill 1000` | MECH-BLK-03 |
| 9 | BlockOxidizerFuelTank | ↳ BlockFuelTank | `getMaxFill 1000` | MECH-BLK-03 |
| 10 | BlockNuclearFuelTank | ↳ BlockFuelTank | `getMaxFill 1000` | MECH-BLK-03 |
| 11 | BlockPressurizedFluidTank | Block | standalone fluid tank; `TileFluidTank(64000×blockTankCapacity)` `tunable`; fluid-cap interaction then GUI; column-aware neighbour updates; drops via `ItemBlockFluidTank.fill` carrying contents | MECH-BLK-04 |
| 12 | BlockPump | BlockTile | `FluidUtil.interactWithFluidHandler` first, else super | MECH-BLK-15 |
| 13 | BlockFluid | BlockFluidClassic | generic fluid source wrapper | — |
| 14 | BlockEnrichedLava | ↳ BlockFluid | enriched-lava fluid wrapper | — |
| 15 | BlockSeal | Block | pipe-seal; tracks per-pos `BlobHandler` map, registers/unregisters `AreaBlob` with `AtmosphereHandler` on completeness of a 3-wide arch; retriggers neighbours | MECH-BLK-05 |
| 16 | BlockRedstoneEmitter | Block | atmosphere detector; `TileAtmosphereDetector`; `POWERED` bit8; strong+weak power 15 when set | MECH-BLK-06 |
| 17 | BlockTileRedstoneEmitter | BlockTile | fueling-station block; power 15 from `STATE`; `setRedstoneState` re-reads world state, chunk-loaded + server guarded | MECH-BLK-06 |
| 18 | BlockTorchUnlit | BlockTorch | relights to vanilla torch only if held igniter **and** `atmosphere.allowsCombustion()`; drop/pick swap on `dropExTorches` | MECH-BLK-07 |
| 19 | BlockAtmosphereTerraformer | BlockMultiblockMachine | facade; fires `ATM_TERRAFORMER` advancement when complete | MECH-BLK-08 |
| 20 | BlockOrbitalLaserDrill | BlockMultiblockMachine | facade; `DEATH_STAR` advancement; `onDestroy` on break/explosion; neighbour → `checkCanRun` | MECH-BLK-08 |
| 21 | BlockShipMachine | BlockTile | hyperdrive family (generator, emitter, capacitor, dampener); carries a tile, opens no GUI — the ship's readouts live at the navigation computer | MECH-BLK-13 |
| 22 | BlockBeacon | BlockMultiblockMachine | `removeBeaconLocation` from `DimensionProperties` on break; red particle plume when enabled | MECH-BLK-09 |
| 23 | BlockTileTerraformer | RotatableBlock | `STATE` bool; registers/unregisters protecting block in the world's `TerraformingRecord`; scatters inventory on break | MECH-BLK-10 |
| 24 | BlockTileNeighborUpdate | BlockTileComparatorOverride | forwards neighbour + `onNeighborChange` to `IAdjBlockUpdate` tile | MECH-BLK-11 |
| 25 | BlockWarpController | BlockTile | sets station `setForwardDirection(front.getOpposite())` on placement | MECH-BLK-09 |
| 26 | BlockTransceiver | BlockTile | wireless transceiver; 6-dir `FACING` + `STATE`; per-face AABB; meta = `facing \| (on?8:0)`; custom `rotateBlock` | MECH-BLK-12 |
| 27 | BlockSolarGenerator | BlockTile | forces `isOpaqueCube/isBlockNormalCube=true` | — |
| 28 | BlockSuitWorkstation | BlockTile | scatters **slot 0** inventory on break, removes TE | MECH-BLK-10 |
| 29 | BlockHalfTile | BlockTile | half-height AABB | — |
| 30 | BlockTileWithMultitooltip | BlockTile | appends `machine.tooltip.multiblock` line | — |
| 31 | BlockForceField | Block | `canEntityDestroy=false` (wither/dragon-proof); translucent; culls own faces | MECH-BLK-13 |
| 32 | BlockForceFieldProjector | BlockFullyRotatable | `TileForceFieldProjector`; `destroyField(front)` on break | MECH-BLK-13 |
| 33 | BlockLandingPad | Block | `TileLandingPad`; register/unregister tile with station on add/break; GUI | MECH-BLK-14 |
| 34 | BlockStationModuleDockingPort | BlockFullyRotatable | `TileDockingPort`; registers with station on place — **break-unregister is dead code** | MECH-BLK-14 |
| 35 | BlockSmallPlatePress | BlockPistonBase | recipe-driven press: extends over obsidian, consumes block below via `RecipesMachine`, spawns output; block-event driven | MECH-BLK-16 |
| 36 | BlockSmallPlatePressHead | BlockPistonExtension | validates a live extended `blockPlatePress` base; self-destructs / drops base as press item | MECH-BLK-16 |
| 37 | BlockMiningDrill | BlockFullyRotatable · IMiningDrill | `getMiningSpeed` 0.02 with 2-block clearance else 0.01 `tunable`; no TE | MECH-BLK-17 |
| 38 | BlockIntake | Block · IIntake | `getIntakeAmt = 1` `tunable` | MECH-BLK-17 |
| 39 | BlockVacuumLaser | BlockFullyRotatable | forces `FACING=UP` on placement | — |
| 40 | BlockLens | BlockGlass | glass with GLASS sound + tooltip | — |
| 41 | BlockQuartzCrucible | BlockCauldron | inert cauldron; no rain fill; drops `itemQuartzCrucible` | MECH-BLK-18 |
| 42 | BlockInvHatch | Block | `TileInvHatch(1)`; GUI | — |
| 43 | BlockStellurgyHatch | BlockHatch | 7 `VARIANT`s → dispatch `createTileEntity` to DataBus/SatelliteHatch/Rocket(Fluid)Loader/Unloader/GuidanceHatch; VARIANT bit8 = redstone; power 15 when VARIANT≥2 | MECH-BLK-19 |
| 44 | BlockDataBusBig | ↳ BlockStellurgyHatch | `TileDataBusBig(2)`; drop/pick carries `DataStorage` NBT only when data>0; delayed-harvest TE pattern | MECH-BLK-20 |
| 45 | BlockSeat | Block | spawns/mounts `EntityDummy`; `TileWearable` (0.25×wearProb); NULL_AABB; kills dummy on explosion | MECH-BLK-21 |
| 46 | BlockCrystal | Block · INamedMetaBlock | 6 metas `EnumCrystal`; per-meta color/mapcolor; translucent; face-cull vs same block | MECH-BLK-22 |
| 47 | CrystalColorizer | IBlockColor,IItemColor | tint provider reading `EnumCrystal.getColor()` | MECH-BLK-22 |
| 48 | BlockCharcoalLog | BlockLog | non-flammable log; drops COAL damage 1 | MECH-BLK-23 |
| 49 | BlockRegolith | Block | shovel-harvest ground with settable map color | — |
| 50 | BlockLightSource | Block | invisible, non-colliding, light 15 | — |
| 51 | BlockThermiteTorch | BlockTorch | vanilla torch + tooltip | — |
| 52 | BlockLinkedHorizontalTexture | Block | launch-pad; 16-value `TYPE` picked by 4-neighbour bitmask for connected textures; own shift/alt tooltip block | MECH-BLK-24 |
| 53 | BlockRocketFire | Block | vanilla-fire clone: faster spread, ages to vanilla FIRE at 15, spreads TNT, no collision | MECH-BLK-25 |
| 54 | BlockDoor2 | BlockDoor | airlock door; drops `itemSmallAirlockDoor`; `onBlockActivated=false` (no hand-open) | MECH-BLK-26 |
| 55 | BlockElectricMushroom | BlockMushroom · IGrowable | in storm biome + rain spawns lightning nearby; arc FX + sound on break/tick | MECH-BLK-27 |
| 56 | BlockLightwoodSapling | BlockBush · IGrowable | 2-stage grow → `WorldGenAlienTree`; bonemeal 45% | MECH-BLK-28 |
| 57 | BlockLightwoodLeaves | BlockLeaves | light 8; drops sapling; flammable 50; delegates render to vanilla LEAVES | MECH-BLK-28 |
| 58 | BlockLightwoodWood | BlockLog | alien log; flammable 50 | MECH-BLK-28 |
| 59 | BlockLightwoodPlanks | Block | light 4; single `ALIEN` variant | MECH-BLK-28 |

## Mechanics (trigger → state → effect; each cites code)
- **MECH-BLK-01 Broken-part engine.** Place → `TileBrokenPart(10,k×increaseWearIntensityProb)` seeded with
  item damage as wear stage; `getActualState` auto-orients toward adjacent fuel tank / nuclear core; harvest
  drops an item stamped with the tile's wear stage; `getDrops` empty so only the wear-stamped drop survives.
  `BlockRocketMotor.java:41,89,106,137` `BlockNuclearRocketMotor.java:38`
- **MECH-BLK-02 Nuclear core thrust.** `getMaxThrust = 49 050 000 N × nuclearCoreThrustRatio`, accumulated
  in `long` by every caller. `BlockNuclearCore.java:28`
- **Thrust is NEWTONS.** Every rating above is 49 050 (= 5000 · 9.81) times its per-gee figure, and the block MASS
  table is 5000 times its per-block figure, so
  `thrust / (mass · g)` — and therefore every rocket's behaviour — is that of the unscaled ratings. One basic motor
  holds exactly a hundred ordinary blocks at one gee, so these numbers may not be edited without the
  mass table. No test pins that pairing; C10 STAT-6 carries the full table as arithmetic.
- **MECH-BLK-03 Fuel-tank stacking state.** `TANKSTATES` derived each tick from same-axis neighbours (tank or
  engine) → top/bottom/middle for seamless column render; placement normalizes DOWN→UP, NORTH→SOUTH, WEST→EAST
  and copies a uniform neighbour facing. `BlockFuelTank.java:71,150`
- **MECH-BLK-04 Pressurized fluid tank.** Right-click routes through the tile's FLUID capability
  (`FluidUtil.interactWithFluidHandler`), else opens MODULAR GUI; drops/harvest emit one `blockPressureTank`
  item pre-filled from `getOwnContentsCopy`; `setRemoving(true)` blocks cross-tile moves during teardown.
  `BlockPressurizedFluidTank.java:49,117,147`
- **MECH-BLK-05 Seal blob.** On add/break a 3-block arch check registers/unregisters a zero-radius `AreaBlob`
  with the dimension `AtmosphereHandler`, keyed in an in-block `HashMap<HashedBlockPosition,BlobHandler>`.
  `BlockSeal.java:93,118,53`
- **MECH-BLK-06 Atmosphere/fueling redstone.** `POWERED`/`STATE` blockstate drives strong+weak power 15;
  `setRedstoneState` on `BlockTileRedstoneEmitter` re-reads world state, guards `isRemote` + `isBlockLoaded`,
  no-ops if unchanged. `BlockRedstoneEmitter.java:86` `BlockTileRedstoneEmitter.java:50`
- **MECH-BLK-07 Atmosphere-gated relight.** Unlit torch becomes vanilla torch only when
  `atmhandler.getAtmosphereType(pos).allowsCombustion()` and player holds torch/flint/fire-charge, server-side.
  `BlockTorchUnlit.java:57`
- **MECH-BLK-08 Multiblock advancement facade.** `onBlockActivated` super + fire advancement when
  `tile.isComplete()`; laser drill also `onDestroy` on break/explosion. `BlockAtmosphereTerraformer.java:22`
  `BlockOrbitalLaserDrill.java:47,61`
- **MECH-BLK-09 Station/dimension registration on place/break.** Warp controller sets forward direction,
  beacon removes beacon location. `BlockWarpController.java:29` `BlockBeacon.java:26`
- **MECH-BLK-10 Inventory scatter on break.** Terraformer scatters full inventory; suit workstation scatters
  slot 0 only, then removes the TE. `BlockTileTerraformer.java:129` `BlockSuitWorkstation.java:27`
- **MECH-BLK-11 Neighbour→tile forward.** `neighborChanged`/`onNeighborChange` call
  `IAdjBlockUpdate.onAdjacentBlockUpdated()`. `BlockTileNeighborUpdate.java:19,33`
- **MECH-BLK-13 Ship machines open nothing.** `BlockShipMachine.onBlockActivated` returns false: the
  hyperdrive family has no per-block panel, by design — one console (the navigation computer) reads the
  whole ship. `BlockShipMachine.java:28`
- **MECH-BLK-12 Transceiver orientation.** 6-dir facing + on/off packed to meta `facing|(on?8:0)`;
  face taken from clicked side; custom Y-rotation. `BlockTransceiver.java:80,100,116`
- **MECH-BLK-31 Force field.** Field block is entity-indestructible & face-culls its own faces; projector's
  `destroyField(front)` clears the field on break. `BlockForceField.java:27` `BlockForceFieldProjector.java:31`
- **MECH-BLK-14 Landing/docking station link.** `TileLandingPad`/`TileDockingPort` register with their station
  on add/place; landing pad unregisters on break. `BlockLandingPad.java:46,71`
  `BlockStationModuleDockingPort.java:57,73`
- **MECH-BLK-15 Pump.** Bucket interaction via `FluidUtil` before default GUI. `BlockPump.java:18`
- **MECH-BLK-16 Plate press.** Redstone-driven `BlockPistonBase`: if the block below matches a
  `RecipesMachine` recipe for `BlockSmallPlatePress` and sits on OBSIDIAN, it is consumed and the output
  spawned; extends a `blockPlatePressHead` that self-destructs when its base is no longer extended.
  `BlockSmallPlatePress.java:86,114,223` `BlockSmallPlatePressHead.java:21,30`
- **MECH-BLK-17 Passive tool blocks.** Mining drill mining speed varies with 2-block clearance; intake amount 1
  — read by rocket/machine tiles, block holds no TE. `BlockMiningDrill.java:32` `BlockIntake.java:32`
- **MECH-BLK-18 Quartz crucible.** Inert cauldron: no activation, no rain fill, drops `itemQuartzCrucible`.
  `BlockQuartzCrucible.java:27,36,41`
- **MECH-BLK-19 Hatch variant dispatch.** `VARIANT` low-3 bits select the hatch tile class; bit8 is redstone
  state; power 15 when VARIANT≥2. `BlockStellurgyHatch.java:84,58,67`
- **MECH-BLK-20 Data-bus NBT drop.** Drop/pick attaches `DataStorage` NBT to the item **only when data>0**
  (empty buses stack); delayed-harvest keeps the TE alive for the drop. `BlockDataBusBig.java:46,79,92`
- **MECH-BLK-21 Seat mount.** Activation mounts/creates an `EntityDummy` rider; explosion kills the dummy;
  `TileWearable` accrues slow wear that can block a crewed launch. `BlockSeat.java:100,79,43`
- **MECH-BLK-22 Crystal color.** 6-meta crystal, `CrystalColorizer` supplies per-meta tint to block+item.
  `BlockCrystal.java:92` `CrystalColorizer.java:14`
- **MECH-BLK-23 Charcoal log.** Non-flammable, drops COAL with damage 1. `BlockCharcoalLog.java:33,85,90`
- **MECH-BLK-24 Connected launch-pad texture.** `TYPE` (16 values) chosen from a 4-neighbour bitmask in
  `getActualState`. `BlockLinkedHorizontalTexture.java:52`
- **MECH-BLK-25 Rocket fire.** Fire clone that spreads faster to sides, ages via `AGE(0-15)` property, becomes
  vanilla FIRE at age 15, ignites TNT, non-colliding. `BlockRocketFire.java:58,96`
- **MECH-BLK-26 Airlock door.** Cannot be hand-opened (`onBlockActivated=false`); drops single airlock-door
  item from the lower half only. `BlockDoor2.java:40,46`
- **MECH-BLK-27 Electric mushroom.** In `stormLandsBiome` + rain, server spawns nearby lightning each tick;
  client shows arc FX + sound. `BlockElectricMushroom.java:47,60`
- **MECH-BLK-28 Alien tree lifecycle.** Sapling 2-stage grow → `WorldGenAlienTree`; leaves drop the sapling
  and delegate render to vanilla LEAVES. `BlockLightwoodSapling.java:40,67` `BlockLightwoodLeaves.java:67`
- **MECH-BLK-29 Rocket motors are ship actuators.** `BlockRocketMotor` (and so
  its advanced/nuclear subclasses) and `BlockBipropellantRocketMotor` implement `IShipActuatorBlock`:
  on a tier-2 hull each is one force of `getThrust` newtons at its block centre, pushing away from the
  nozzle its ACTUAL state shows; a motor worn to its last stage does not fire. `ChemicalMotorActuators.java`
  `[V]`. Tier-1 thrust is untouched. See `ship-flight-model` MECH-SFM-04.
- **MECH-BLK-30 Reaction wheel.** `BlockReactionWheel` (`reactionWheel`, tile
  `TileReactionWheel`): three pure-torque actuators about the ship's axes, `TORQUE` and
  `MOMENTUM_CAPACITY` `tunable`; no recipe. `BlockReactionWheel.java` `[V]`.

## State & persistence (contractual, C1)
Blocks store no NBT of their own; the on-disk contract is the **metadata↔blockstate encoding**:
- Fuel tank meta = `TANKSTATES.ordinal() + rotation×3` (rotation 0/1/2 = UP/SOUTH/EAST). [V] `BlockFuelTank.java:52`
- Redstone/atmosphere emitter `POWERED` = meta bit8. [V] `BlockRedstoneEmitter.java:52`
- Transceiver meta = `FACING.index | (STATE?8:0)`. [V] `BlockTransceiver.java:80`
- Terraformer meta = `FACING.index | (STATE?8:0)`. [V] `BlockTileTerraformer.java:70`
- Rocket-fire `AGE` property is literally named `"level"` (CCL/lava requirement) — renaming breaks render. [V] `BlockRocketFire.java:34`
- Crystal / lightwood-planks meta = enum ordinal. [V] `BlockCrystal.java:48` `BlockLightwoodPlanks.java:46`
- Broken-part engines: **no meta wear**; wear is stored only in `TileBrokenPart` (owner: infrastructure/rocket tiles). [V] `BlockRocketMotor.java:118`
- Data-bus drop NBT = `DataStorage.writeToNBT` (owner of the schema: api-public `DataStorage`). [V] `BlockDataBusBig.java:55`
Block-side NBT/lang keys owned here (C7): tooltip reveal keys `tooltip.stellurgy.hold_shift`,
`tooltip.stellurgy.hold_alt` plus per-block `tooltip.stellurgy.<name>`. [V] `BlockLinkedHorizontalTexture.java:116` `BlockRocketMotor.java:154`

## Integration seams
- **Registry (C3):** blocks are constructed here but **registered** by `Stellurgy#registerBlocks`
  (misc-oddities); registry names are that file's contract. [V] `Stellurgy.java:662`
- **Capabilities:** fluid tanks/pumps interact via `CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY`. [V] `BlockPressurizedFluidTank.java:60`
- **Events/advancements (C5):** `StellurgyAdvancements.ATM_TERRAFORMER`, `.DEATH_STAR`. [V] `BlockAtmosphereTerraformer.java:29`
- **GUIs:** libVulpes `GuiHandler.guiId` (MODULAR / MODULARNOINV). [V] `BlockRedstoneEmitter.java:74`
- **Cross-subsystem callbacks:** `AtmosphereHandler` (seal, unlit torch), `SpaceObjectManager`/`SpaceStationObject`
  (warp core/controller, landing pad, docking port), `DimensionProperties` (terraformer protect list, beacon),
  `RecipesMachine` (plate press). No packets or mixins owned by this package.

## Config surface

Config: see `C4-config-surface`.

## Invariants
- **INV-BLK-01 [V]** Every functional block delegates persistence to a tile — no `Block` field survives world
  save. `BlockSeal.java:41` (the seal frames' blob handlers are a per-world `WorldRuntime` part, rebuilt
  from the world on add and never persisted; never a map on the block object keyed by
  bare position, which every dimension would share).
- **INV-BLK-02 [V]** Fuel-tank meta round-trips only 3 rotations (UP/SOUTH/EAST); DOWN/NORTH/WEST are
  normalized to their opposite at place-time, so no lossy meta exists on disk. `BlockFuelTank.java:150,52`
- **INV-BLK-03 [V]** Broken-part engines carry their wear stage into the dropped item via the tile, and
  suppress the default block drop. `BlockRocketMotor.java:106,126`
- **INV-BLK-04 [V]** GUI-opening `onBlockActivated` handlers are server-guarded (`!world.isRemote`).
  `BlockLandingPad.java:55` `BlockStationModuleDockingPort.java:48`
- **INV-BLK-05 [V]** Rocket-fire `AGE` property is registry-named `"level"`; the string is contractual for
  CCL lava-material render. `BlockRocketFire.java:34`
- **INV-BLK-06 [A]** Data-bus items with no stored data are NBT-free and therefore stack — assumed the intended
  behaviour of the `data>0` guard; not test-pinned. `BlockDataBusBig.java:54`
- **INV-BLK-07 [V]** Landing pad registers on add and unregisters on break with its station (symmetric); the
  docking port's break path is **not** symmetric. `BlockLandingPad.java:46,71`

## Failure modes & edge cases
- Broken-part engines cast `world.getTileEntity(pos)` to `TileBrokenPart` in `onBlockPlacedBy`/`harvestBlock`
  with no null/instanceof guard — a missing TE throws. `BlockRocketMotor.java:92,108`
- `BlockOrbitalLaserDrill.onNeighborChange` casts the pos TE to `TileOrbitalLaserDrill` unguarded.
  `BlockOrbitalLaserDrill.java:43`

## Test coverage
No test in `src/test` targets `dev.stannismod.stellurgy.block.*` directly (grep: 0 hits); block behaviour is
exercised only indirectly through rocket-assembly and atmosphere suites. All invariants above are [V]/[A], none [T].

## Open questions
- `BlockStellurgyHatch.createTileEntity` returns `null` for `VARIANT&7 == 7`; is variant 7 ever registered/placed?
  `BlockStellurgyHatch.java:104` — resolution lives in the registration site (misc-oddities).

## Armour that answers a contact

Two block families implement `IContactResponder`.
Both are thin by design — armour is a surface you apply to a hull, not a cubic metre of it.

| block | registry name | what it answers |
|---|---|---|
| `BlockMirrorPlating` x3 | `mirrorPlatingAluminium` / `mirrorPlatingSilver` / `mirrorPlatingGold` | reflects `R x E` of a BEAM or THERMAL arrival and burns out when `(1-R) x E` exceeds its film; lets a slug through untouched (`structural-damage` MECH-DMG-26) |
| `BlockReactivePlating` x2 | `reactivePlate` / `reactiveBlock` | swallows up to its capacity of one impact and removes itself; the block is twice the plate (MECH-DMG-27) |

The three mirrors differ ONLY in reflectance — 0.90 aluminium, 0.96 silver, 0.97 gold in the infrared —
because that is what separates the real metals. They share one film dissipation, so a better mirror
buys survival by letting less of each hit into the same film rather than by having a better film.
