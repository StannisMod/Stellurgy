---
id: libvulpes
owns: [libvulpes/]
entrypoints: [LibVulpes#preInit, LibVulpes#init, LibVulpes#postInit, TileMultiBlock#attemptCompleteStructure, TileMultiblockMachine#onInventoryUpdated, PacketHandler#openChannel, GuiHandler#getServerGuiElement, ContainerModular#detectAndSendChanges, MaterialRegistry#registerOres]
depends-on: [api-public]
depended-by: [multiblock-machines, infrastructure-tiles, satellite, wirelessdata, recipe, items, blocks, inventory-containers, network-wire, client-render, integration-jei, rocket-assembly, space-stations]
contracts: [C1, C2, C3, C4, C5]
confidence: medium
---

# libVulpes — the vendored machine library

`sloc` is the physical line count of `src/main/java/dev/stannismod/stellurgy/libvulpes/` (`wc -l`, 185 files); `source-sha` is
`git rev-parse HEAD:src/main/java/dev/stannismod/stellurgy/libvulpes`. Citations are relative to that root unless the file is
named bare (host, test or Minecraft source, each located by file name). This doc is the **overview level**: it maps the mechanics
other subsystems stand on and says in *Open questions* which areas would need a sub-doc of their own.

## Purpose

libVulpes is the library Advanced Rocketry was built on, imported on 2026-09-29 and folded into Stellurgy's single `@Mod` (it is
no longer a mod: no jar, no container, no `@Instance`). It supplies four things the rest of the mod leans on: the **multiblock
engine** (structure scan, hatch/plug wiring, recipe cycle, power gate), the **modular GUI** (module list → container + screen +
window-property sync), the **packet channel** (one FML channel, one codec, game-thread execution) and the **material registry**
(ores/metals/coils/products). It keeps its own registry domain `libvulpes:` for its blocks, items and assets. The tree is
vendored source, edited in place like the rest of the mod; it is what compiles, not any upstream checkout.

## Responsibility boundary

**Owns:** everything under `libvulpes/`: the multiblock base classes and their blocks/tiles (formation, placeholders, hatches, plugs);
the Forge-energy/Tesla/GT energy adapters and the `IUniversalEnergy` batteries; the recipe registry (`RecipesMachine`), its JSON
factory base and the XML override loader; `PacketHandler`/`BasePacket` and the four libVulpes packets; the modular GUI framework
(`GuiHandler`, `ContainerModular`, `GuiModular`, ~30 `Module*`); `EmbeddedInventory`; the linker and holo-projector items; the
`MaterialRegistry`/`AllowedProducts`/`Material` model and the ore/metal/coil/product blocks and items it creates; the
`libvulpes.cfg` file; the jetpack key relay (`InputSyncHandler`).

**Does NOT own:** the machines that extend these bases (→ [multiblock-machines](./multiblock-machines/00-overview.md),
[infrastructure-tiles](./infrastructure-tiles/00-overview.md), [satellite](./satellite.md)); the packet *payloads* Stellurgy adds on
the channel and their order in `PacketRegistry` (→ [network-wire](./network-wire.md), [C2](../30-contracts/C2-network-wire.md));
the Stellurgy GUI handler that fronts libVulpes' ids (→ [inventory-containers](./inventory-containers.md) `MECH-IVC-01`); the
recipe *data* and the Stellurgy `Recipe*` factories (→ [recipe](./recipe.md)); the shared handler wiring in `Stellurgy.java` (host).

## Key types

| class | role |
|-------|------|
| `LibVulpes` | lifecycle shim: `preInit`/`init`/`postInit` are driven by the host; creates and registers blocks/items/tiles, the `P/p/I/O/L/l` mappings, the missing-mapping remap, `libvulpes.cfg` |
| `tile/multiblock/TileMultiBlock` | controller base: structure scan, assimilation, deconstruction, port lists, `charMapping` |
| `tile/multiblock/TileMultiPowerConsumer` | controller with a power gate, progress counters, speed multiplier, toggle, GUI modules |
| `tile/multiblock/TileMultiblockMachine` | consumer + recipe engine (`onInventoryUpdated` → consume → run → `dumpOutputToInventory`) |
| `tile/multiblock/TileMultiPowerProducer` | controller that pushes generated power into `p` plugs |
| `tile/TilePointer` / `tile/multiblock/TilePlaceholder` | block that forwards to a master / block that stands in for a replaced block and its tile |
| `block/multiblock/Block{MultiblockStructure,Hatch,MultiMachineBattery,MultiblockPlaceHolder,MultiblockMachine}` | the structure-block roles (variant-bit "formed" state, hatches, plugs, placeholder, controller block) |
| `tile/multiblock/hatch/Tile{Input,Output,Inventory,Fluid}Hatch` | item and fluid ports over `EmbeddedInventory`/`FluidTank` |
| `tile/energy/TilePlugBase` + plugs; `util/{UniversalBattery,MultiBattery,CreativeBattery}`; `cap/*` | energy storage, aggregation and the Forge/Tesla/GT capability adapters |
| `tile/TileEntityRFConsumer` family (`TileInventoried*`, `TileEntity{Machine,PowerMachine,ForgeProducer}`) | non-multiblock powered machines |
| `recipe/{RecipesMachine,RecipeMachineFactory}` + `util/XMLRecipeLoader` | per-tile-class recipe lists, JSON factory base, user-editable XML |
| `network/{PacketHandler,BasePacket,PacketMachine,PacketEntity,PacketItemModifcation,PacketChangeKeyState}` | channel, codec, game-thread dispatch, the four libVulpes packets |
| `inventory/{GuiHandler,ContainerModular,GuiModular}` + `inventory/modules/*` | modular GUI: id space, container, screen, `ModuleBase` sync protocol |
| `util/EmbeddedInventory` | `ItemStackHandler` + `ISidedInventory` with per-slot insert/extract flags and an update callback |
| `api/material/{MaterialRegistry,AllowedProducts,Material}` + `block/BlockOre`, `items/ItemOreProduct` | material model and the blocks/items generated from it |
| `items/{ItemLinker,ItemProjector}` | position linker (bind + aimed designation); holo-projector structure ghosts |

## Mechanics

### MECH-LVP-01 — lifecycle: libVulpes is driven, not loaded

The host calls `libVulpes.preInit/init/postInit` from its own handlers; `preInit` runs **first** in the host's pre-init, so libVulpes'
blocks, products, materials and registry listeners exist before the host's multiblocks name them
[V] `H:Stellurgy.java:574`, `LibVulpes.java:300-309`. `preInit` builds the content (`createContent`), registers the object on the Forge
bus (HIGH-priority `registerBlocks`/`registerItems`/`missingMappings`), runs the side proxy, reads `libvulpes.cfg`, and registers the
13 allowed products and the 11 base materials [V] `LibVulpes.java:137-163,300-365`. `postInit` registers the structure-char mappings
(MECH-LVP-02) [V] `LibVulpes.java:385-424`. The `@SidedProxy` is injected against the host's modid because this class is not a `@Mod`
[V] `LibVulpes.java:97`. Block/item registration goes through `LibVulpesBlocks.registerBlock/registerItem`, which writes the Forge
registry directly (`GameData.register_impl`) and also **serves the host's own blocks**; it records every block in `blocks` and, when
asked, in `itemBlocks` (the client model-registration list) [V] `api/LibVulpesBlocks.java:74-115`.

### MECH-LVP-02 — structure language and orientation

A machine's `getStructure()` returns an `Object[][][]` indexed `[y][z][x]`, **layer 0 on top**; the cell holding the character `'c'`
is the controller and the origin of the scan [V] `tile/multiblock/TileMultiBlock.java:270-272,525-535`. Cells are rotated about the
controller by the controller block's facing: `globalX = pos.x + (x-ox)*frontZ - (z-oz)*frontX`, `globalZ = pos.z - (x-ox)*frontX -
(z-oz)*frontZ`, `globalY = pos.y - y + oy` [V] `TileMultiBlock.java:337-339`. A cell is: `null` (ignored); `Blocks.AIR` (must be air, or a
replaceable block that is cleared on success); `'c'`; a `Block` (any meta); a `BlockMeta`; a `Block[]`; a `List<BlockMeta>`; an
ore-dictionary `String`; or a **mapping character** resolved through the shared `charMapping` table; `'*'` asks the subclass
(`getAllowableWildCardBlocks`, empty by default, so an unoverridden `'*'` matches nothing) [V] `TileMultiBlock.java:284-287,447-483`. The
library registers six characters, each over *both* the unformed and formed (`|8`) hatch metas: `I`/`O` item in/out
(hatch metas 0,8 / 1,9), `L`/`l` fluid in/out (2,10 / 3,11), `P` power in (creative, Forge, IC2-if-loaded plugs, any meta), `p`
power out (Forge output plug) [V] `LibVulpes.java:385-424`; a second `addMapping` of a character logs a warning and **replaces** it
[V] `TileMultiBlock.java:67-71`. `BlockMeta.equals` treats meta `-1` as a wildcard on **either** side and is not consistent with its
`hashCode`, so lookups must go through `List.contains`, never a hash container [V] `block/BlockMeta.java:23-38`.

### MECH-LVP-03 — formation: validate everything, then mutate

`attemptCompleteStructure(state)` sets `completeStructure` and `canRender` to the result of `completeStructure(state)`
[V] `TileMultiBlock.java:274-278`. That method (a) **clears the port and battery caches first** [V] `:317`; (b) walks every non-null cell
and returns `false` on the first of: a chunk reporting not-loaded, a `blockPhantom` cell, or a block not in the cell's allowed list
(the failing cell raises the client-side `errorBox` particle) [V] `:329-393`; (c) only then mutates, in this order: structure blocks are
hidden or marked formed, **foreign blocks are replaced by placeholders**, every tile gathered during the scan is `integrateTile`d, and
replaceable `AIR` cells are cleared [V] `:396-439`. A `TilePointer` already mastered by this controller is trusted without checking its
block (`continue`), and a pointer mastered by a *different* controller is re-checked as an ordinary block and, on success,
**re-pointed at this controller** by `integrateTile` — a hatch belongs to the last controller that formed around it, and the previous
one is never told [V] `:357-372,508-510`. Formation can run **on the client too**: a right-click on a controller whose server state is
complete falls through to `attemptCompleteStructure` on the client so the client tile owns port/battery references for its GUI
[V] `block/multiblock/BlockMultiblockMachine.java:84-97`, `TileMultiBlock.java:99-103`.

### MECH-LVP-04 — block roles inside a formed structure

`BlockMultiblockStructure` carries a 4-bit `varient` property whose bit 3 means *formed/hidden*; `hideBlock` sets it, `destroyStructure`
clears it, and `damageDropped` masks it off [V] `block/multiblock/BlockMultiblockStructure.java:24,53-68`. Three roles follow:
**component-visible** blocks get a `TilePointer` only while formed and keep rendering [V] `block/multiblock/BlockMultiBlockComponentVisible.java:19-38`;
**hatches/plugs** always carry their tile (variants 0-3 = item in, item out, fluid in, fluid out; `|8` = formed) and a formed hatch
stops rendering [V] `block/multiblock/BlockHatch.java:56-77,141-151`; **any other block** is swapped for the invisible
`BlockMultiblockPlaceHolder`, whose `TilePlaceholder` stores the replaced state and the replaced tile, forwards drops to the replaced
block's loot for non-creative breakers, and breaking it deconstructs the master [V] `TileMultiBlock.java:494-502`,
`block/multiblock/BlockMultiblockPlaceHolder.java:93-130`. A time-multiplier block therefore never has a tile of its own once formed
(MECH-LVP-07). `getItemInPorts`/`getItemOutPorts` **re-resolve each stored tile by position on every call**, so a tile replaced since
formation is picked up [V] `TileMultiBlock.java:77-97`.

### MECH-LVP-05 — deconstruction and restoration

`deconstructMultiBlock(world, destroyedPos, blockBroken, state)` clears `completeStructure`/`canRender`, turns the controller's `STATE`
property off (unless the controller itself is the block being broken), then walks the same cells (with the **destroyed controller's current facing**) and calls `destroyBlockAt` on each: a
structure block has its formed bit cleared; a placeholder is marked incomplete, replaced by its stored state, and its stored tile is
rebuilt by a **NBT write/read round trip** onto the new tile; any other pointer is marked incomplete **and `invalidate()`d**
[V] `TileMultiBlock.java:178-264`. `TileMultiPowerConsumer` additionally zeroes progress, disables the machine and resets the speed
multiplier; `TileMultiblockMachine` first nulls its pending outputs [V] `tile/multiblock/TileMultiPowerConsumer.java:256-265`,
`tile/multiblock/TileMultiblockMachine.java:170-175`. Triggers: breaking the controller (`BlockMultiblockMachine.breakBlock`, only if
complete), breaking any structure block or hatch (`BlockMultiblockStructure.breakBlock`), breaking a placeholder
[V] `block/multiblock/BlockMultiblockMachine.java:37-48`, `block/multiblock/BlockMultiblockStructure.java:83-96`,
`block/multiblock/BlockMultiblockPlaceHolder.java:121-130`. A chunk unload of a hatch **un-completes its master without
deconstructing** (flag only) [V] `tile/multiblock/hatch/TileInventoryHatch.java:173-181`, `TileMultiBlock.java:163-165`.

### MECH-LVP-06 — the machine tick and the power gate

Both consumer bases run one deferred re-validation on the first server tick **only if the tile loaded as complete**
[V] `TileMultiPowerConsumer.java:141-157`, `TileMultiblockMachine.java:105-123`. A machine `isRunning` when `completionTime > 0` and
the structure is complete [V] `TileMultiPowerConsumer.java:279-281`. The two bases then **differ**:
`TileMultiPowerConsumer.update` retries a failed formation every 1000 ticks and gates the tick on `requiredPowerPerTick()`
(`powerPerTick × PowerMultiplier`, floor 1) [V] `:159-166,194-196`; `TileMultiblockMachine.update` (which does not call `super`) has no
retry, only a 1000-tick `onInventoryUpdated` poke for a jammed machine, and gates on the raw `powerPerTick`
[V] `TileMultiblockMachine.java:105-151`. Both then call the shared `onRunningPoweredTick`, which on the server **spends the raw
`powerPerTick`**, advances `currentTime` on both sides, and calls `processComplete` at `currentTime == completionTime`
[V] `TileMultiPowerConsumer.java:205-221`. Power state is synced by `hadPowerLastTick`: the server flips it and sends a `POWERERROR`
`PacketMachine` to everything within 256 blocks; the client keeps ticking progress only while the last-known flag is true
[V] `TileMultiPowerConsumer.java:165-188`, `TileMultiblockMachine.java:129-150`.

### MECH-LVP-07 — the recipe cycle

`TileMultiblockMachine.onInventoryUpdated` is the single trigger (hatch change, toggle, formation, the jam poke). If nothing is
pending it picks the **first recipe in the tile class's list that `canProcessRecipe`** (list order is priority), **consumes the
ingredients immediately**, fixes `powerPerTick` and `completionTime` (time = recipe time × structure multiplier, min 1), stores the
recipe's outputs, and turns the controller block's `STATE` on; otherwise it turns `STATE` off
[V] `TileMultiblockMachine.java:223-232,494-528`. At `processComplete` the stored outputs are written to the output hatches (item
slots from the **end** of each hatch, merging equal stacks) and the fluid outputs `fillInternal`ed into the out-ports, then
`onInventoryUpdated` runs again [V] `:177-219`. `canProcessRecipe` re-entrancy is blocked by a flag; an ingredient is satisfied when
**any** input slot holds a matching stack of at least the needed count (ore-dict wildcard damage allowed) — the check does not track
slots already claimed by an earlier ingredient — and fluids are summed across all `L` hatches by a simulated drain
[V] `:301-473`. Chance recipes (`maxoutputsize`) draw their outputs with a `System.currentTimeMillis`-seeded `Random` and need that
many *empty* output slots [V] `recipe/RecipesMachine.java:144-195`, `TileMultiblockMachine.java:307-310,419-430`.

### MECH-LVP-08 — speed multipliers from the structure

`timeMultiplier` starts at 1 and is multiplied, at formation, by `getTimeMultiplierForBlock` for **every cell that gets replaced by a
placeholder**: a block implementing `ITimeModifier` (the four motors: ×1, ×1/1.5, ×1/2, ×1/4), else ore-dictionary coils (gold/electrum
0.9, aluminium/high-voltage 0.8, titanium 0.75, iridium 0.5), else 1 [V] `TileMultiPowerConsumer.java:109-138`,
`LibVulpes.java:152-155`. Several modifiers multiply. It is persisted as `timeMult` only when ≠ 1 and read back only when present
[V] `TileMultiPowerConsumer.java:309-322`; it resets on deconstruction (`:256-265`). The recipe applies it as
`recipe.time × getTimeMultiplierForRecipe` (`TileMultiblockMachine.java:503`).

### MECH-LVP-09 — energy: batteries, aggregation, adapters

`UniversalBattery` clamps both ways and persists `energy`/`maxEnergy` [V] `util/UniversalBattery.java:19-81`. `MultiBattery` is a
list of `IUniversalEnergy`: `acceptEnergy` fills sequentially with the remainder; `getUniversalEnergyStored` sums; **`extractEnergy`
asks every battery for the full amount and sums the answers** [V] `util/MultiBattery.java:26-33,63-68`. A consumer fills its
`batteries` at integrate time with every tile whose block is in the `P` mapping, a producer with the `p` mapping
[V] `TileMultiPowerConsumer.java:291-299`, `TileMultiPowerProducer.java:115-127`. `TilePlugBase` (the plug tiles) holds a battery of
`10^tier × 10000 × energy_multiplier` (tier fixed to 1 by the plug block), exposes **Forge Energy always and Tesla when present** by
wrapping itself in a fresh `ForgePowerCapability` per call, and keeps the **saved** `maxEnergy` over a recomputed one
[V] `tile/energy/TilePlugBase.java:26-72,87-104`, `block/multiblock/BlockMultiMachineBattery.java:44-53`. The output plug pushes its
whole store into every adjacent energy-capable tile each tick; the creative input plug (`CreativeBattery`: always `Integer.MAX>>4`,
extraction never depletes) does the same [V] `tile/energy/TileForgePowerOutput.java:49-59`, `util/CreativeBattery.java:3-17`,
`tile/energy/TileCreativePowerInput.java:91-103`. `ForgePowerCapability` forwards straight to `acceptEnergy`/`extractEnergy` and does
**not** consult `canReceive`/`canExtract` [V] `cap/ForgePowerCapability.java:14-42`. `libvulpes.cfg` `EUPowerMultiplier` (default 4)
converts IC2/GT EU to FE; `PowerMultiplier` is read by the two gates above (MECH-LVP-06, `tile/TileEntityRFConsumer.java:115-121`).

### MECH-LVP-10 — hatches and embedded inventories

`TileInventoryHatch`/`TileFluidHatch` keep their stacks in an `EmbeddedInventory`: an `ItemStackHandler` that also implements
`ISidedInventory`, with per-slot `canInsert`/`canExtract` flags that are **persisted** (`slotInsert`/`slotExtract`) and enforced on the
capability path (`insertItem`/`extractItem`) and in `canInsertItem`/`canExtractItem` [V] `util/EmbeddedInventory.java:206-231`. A
change reaches the master controller by **two different routes**: the hatch's `setInventorySlotContents` calls the master directly,
while the capability path fires `onContentsChanged` → the update callback → master; `setInventorySlotContents`, `decrStackSize` and
`removeStackFromSlot` on the embedded inventory itself notify nothing [V] `tile/multiblock/hatch/TileInventoryHatch.java:84-89,211-217`, `util/EmbeddedInventory.java:72-79,129-146,244-248`.
The fluid hatch (default 16000 mB × `capacityMultiplier`) refuses external `fill` when output-only but accepts `fillInternal`; at
integration an output-only hatch is classed as an out-port, any other as an in-port; it drains a filled container in slot 0 into the
tank and is filled from containers by right-click [V] `tile/multiblock/hatch/TileFluidHatch.java:36-48,98-143`, `TileMultiBlock.java:508-523`,
`block/multiblock/BlockHatch.java:154-178`. The item hatch is exposed as an item-handler capability that **is** the embedded
inventory (`TileInventoryHatch.java:49-64`).

### MECH-LVP-11 — the packet channel

`PacketHandler.openChannel(list)` creates the FML channel named `libVulpes`, one codec, and registers each class with the **index in the
list as its wire id**; the list is the host's (`PacketRegistry`), which places `PacketMachine`, `PacketEntity`, `PacketChangeKeyState` at
ids 0-2 and `PacketItemModifcation` at 6 [V] `network/PacketHandler.java:42-66`, `PacketRegistry.java:43-49,91`. Sending before opening
throws `IllegalStateException` [V] `PacketHandler.java:99-105`; the `sendTo*` family selects reach through FML outbound attributes
[V] `:107-148`. The codec decodes on the netty thread — client side through `readClient`, server side through `read` — and **nothing
else**; per-class handlers then queue `executeClient`/`executeServer` on that side's game thread, resolving the **player when it runs**
(a respawn queued ahead replaces him) [V] `:150-178`, `network/BasePacket.java:106-120`. `BasePacket.idMap` is an empty map that nothing
fills, so `getPacketId` always throws — the wire id never comes from it [V] `network/BasePacket.java:19-24,57-62`.

### MECH-LVP-12 — the four libVulpes packets

**`PacketMachine`** addresses an `INetworkMachine` tile by `(dimension, x, y, z, byte id)` plus a payload that machine itself writes;
decoding copies the bytes and touches no world; the game thread then looks up the world and tile, drops the packet silently when the
world, the block or an `INetworkMachine` is missing, and calls `readDataFromNetwork` then `useNetworkData` [V]
`network/PacketMachine.java:65-135`. **`PacketEntity`** does the same for `INetworkEntity` by dimension + entity id, but resolves the
world and entity **inside the decoder** [V] `network/PacketEntity.java:64-98`. **`PacketItemModifcation`** targets the sender's
**main-hand** item if it is an `INetworkItem` (dimension and entity id are sent but the executor ignores them) [V]
`network/PacketItemModifcation.java:114-125`. **`PacketChangeKeyState`** (C→S) relays `(int key, boolean)` to `InputSyncHandler`
[V] `network/PacketChangeKeyState.java:44-46`: key 0 toggles the chest jetpack (or every jetpack inside a modular chestplate), key 1
changes the mode of jetpacks inside a modular chestplate (and toggles a bare jetpack exactly as key 0 does), key 57 sets the player's SPACE flag (stored by a mixin on `EntityPlayer`, cleared on dimension
change) [V] `util/InputSyncHandler.java:18-80`, `MixinEntityPlayerJetpackKeys.java:14`.

### MECH-LVP-13 — the modular GUI

**Opening.** One handler serves four ids (`MODULAR`, `MODULARNOINV`, `MODULARCENTEREDFULLSCREEN`, `MODULARFULLSCREEN` = 0..3); the
target is addressed as `y > -1` → tile at `(x,y,z)`; else `x == -1` → the **held main-hand item** (must implement
`IModularInventory`); else → entity id in `x`. Both sides build `getModules(id, player)` from the target and wrap it in a
`ContainerModular` (server) / `GuiModular` (client); the player-inventory/hotbar flags per id are the same on both sides
[V] `inventory/GuiHandler.java:13-80`. Registration is the host's (`H:Stellurgy.java:1459`, one handler chain).
**Container.** Every module contributes its slots; the 27 inventory slots are added for id 0 and the hotbar for ids 0 and 1
[V] `inventory/ContainerModular.java:24-49`. `canInteractWith` is the target's `canInteractWithContainer` (a multiblock: `isComplete()`)
[V] `inventory/ContainerModular.java:144-146`, `TileMultiPowerConsumer.java:397-400`. Shift-click moves between the module slots and
the player slots with vanilla `mergeItemStack` [V] `inventory/ContainerModular.java:107-141`.
**Sync.** A module exposes `numberOfChangesToSend()` variables; the container numbers them consecutively across modules and sends each
as a **window property** (`sendInitialChanges` when a listener attaches, `isUpdateRequired` each tick); the client routes property `n`
back to `onChangeRecieved(local, value)` of the owning module [V] `inventory/ContainerModular.java:57-102`, `inventory/modules/ModuleBase.java:128-143,193-212`.
A window property is a **16-bit value on the wire** (`writeShort`), so a module that carries a wider number must split it — `ModulePower`
does (low/high halves); `ModuleProgress` and `ModuleSync` send an `int` unsplit [V] `SPacketWindowProperty.java:52`,
`inventory/modules/ModulePower.java:66-118`, `inventory/modules/ModuleProgress.java:88-95`, `inventory/modules/ModuleSync.java:35`. The
change detector advances a variable's baseline only on a call in a **later tick**, so a changed value is sent on at least two
consecutive ticks [V] `ModuleBase.java:128-143`. A button press reaches the tile through its `IToggleButton`/`IButtonInventory`
callback on the client, then by an explicit `PacketMachine` to the server (`TileMultiPowerConsumer.java:383-389`). A slot's
acceptance rule belongs to its `Slot` subclass: `ModuleSlotArray` builds plain `Slot`s, which never ask the inventory's
`isItemValidForSlot` [V] `inventory/modules/ModuleSlotArray.java:61`.

### MECH-LVP-14 — materials

`AllowedProducts.registerProduct` hands out one bit per product (13 are registered; `ordinal()` is the registration index) [V]
`api/material/AllowedProducts.java:15-42,74-78`. A `MaterialRegistry` is created per domain (libVulpes' and Stellurgy's own — 11 + 2
materials) and **registers itself in a process-wide list at construction** [V] `api/material/MaterialRegistry.java:65`,
`H:Stellurgy.java:311`. `registerMaterial` assigns the material's `index` = its position in that registry; `registerOres` then creates,
per group of 16 materials, an `ore<i>`, `metal<i>` and `coil<i>` `BlockOre` (block meta = material slot) and one `product<name>` item
per non-block product (item damage = material index), all under the registry's own domain, and registers the ore-dictionary names
`ore/block/coil<Name>` [V] `api/material/MaterialRegistry.java:68-72,105-229`. Lookups by name or stack search **every** registry in
construction order (`getMaterialFromName`, `getItemStackFromMaterialAndType`); `getMaterialFromItemStack` additionally matches by
**substring** of the ore-dictionary name [V] `api/material/MaterialRegistry.java:250-302,374-383`. The one remap shipped: `libvulpes:productcrystal` → Dilithium gem
[V] `LibVulpes.java:284-293`. Mixed (alloy) materials are a static map keyed by input stack or string
[V] `MaterialRegistry.java:308-330`.

### MECH-LVP-15 — machine recipes

`RecipesMachine` (a singleton) maps a **tile class** to its `IRecipe` list. `addRecipe` accepts outputs as item/fluid stacks and inputs
as ore-dictionary strings, numbered ore stacks, items, blocks, stacks or fluid stacks; **an ore-dictionary input is expanded to its
members at the moment of the call**, and a recipe equal to an existing one (same ingredient lists and fluids) replaces it
[V] `recipe/RecipesMachine.java:244-319`. `RecipeMachineFactory` is the base of the Stellurgy JSON factories: it parses
`itemingredients/itemresults/fluidingredients/fluidresults/time/energy/maxoutputsize`, appends the recipe to
`recipeList.get(getMachine())` itself and returns a dummy vanilla recipe so Forge's own registry stays empty
[V] `recipe/RecipeMachineFactory.java:25-89`. A user file per class (registered with `registerRecipeHandler`) can add to or, with
`useDefault="false"`, replace the list: the first run writes the defaults out, later runs parse it
[V] `LibVulpes.java:123-125,431-483`, `util/XMLRecipeLoader.java:58-66`.

### MECH-LVP-16 — linker and projector

`ItemLinker` stores a binding in item NBT: `MasterPos{MasterX,MasterY,MasterZ}` plus a top-level `dimId`; **"unset" is `MasterY == 0`**
[V] `items/ItemLinker.java:62-63,137-152,92-114`. A use on an `ILinkableTile` calls `onLinkStart` when unset, else `onLinkComplete`;
a use on a tile-less block while sneaking clears the binding [V] `:181-208`. A right-click that is not a link use, with the linker bound
(here, or with `dimId -1`) to an `ILinkAimedTile`, traces the player's line of sight (blocks and entities, out to the server's view
distance) and hands the first hit to `onLinkAimed`; an unloaded or other-dimension binding answers with a chat line instead
[V] `:210-288`. `ItemProjector` keeps an in-process list of registered machine prototypes (indexed by `machineId`, kept in the item's
NBT) and, on a server-side request, writes `blockPhantom` ghost blocks (a self-deleting `TileSchematic`, 6000 ticks) over air or
replaceable cells at the position the client sent [V] `items/ItemProjector.java:58-59,80,172-226`, `tile/TileSchematic.java:17`.

## State & persistence

libVulpes persists in the **host's** chunk/item NBT; keys below are in the tile's own compound unless stated. The 0.1.0 clean break
applies (no migration); cross-reference [C1](../30-contracts/C1-nbt-persistence.md).

| owner | keys | note |
|-------|------|------|
| `TileMultiBlock` | `completeStructure`, `canRender` | `TileMultiBlock.java:546-560` |
| `TileMultiPowerConsumer` | `completionTime`, `currentTime`, `powerPerTick`, `enabled`, `timeMult` (only if ≠ 1) | `TileMultiPowerConsumer.java:301-327`; also the network-data path |
| `TileMultiblockMachine` | `outputItems`, `inputItems`, `outputFluids` (pending recipe); update packet adds `built`, `hadPowerLastTick` | `TileMultiblockMachine.java:538-620,66-98` |
| `TileMultiPowerProducer` | `enabled` | `TileMultiPowerProducer.java:129-139` |
| `TilePointer` (every hatch/plug/placeholder) | `masterX/Y/Z` (absent = none) | `TilePointer.java:138-169` |
| `TilePlaceholder` | `ID` (**numeric** block id), `damage`, `tile` (replaced tile compound) | `TilePlaceholder.java:68-96` |
| `TileSchematic` | `timeAlive`, `blockIds`, `blockMetas` (**numeric** ids) | `tile/TileSchematic.java:60-80` |
| `EmbeddedInventory` | `size`, `outputItems` (`Slot`+stack), `slotInsert`, `slotExtract` (byte arrays) | `util/EmbeddedInventory.java:38-100` |
| `UniversalBattery` / `TilePlugBase` | `energy`, `maxEnergy` / `teir` | `util/UniversalBattery.java:73-81`, `TilePlugBase.java:87-104` |
| `TileFluidHatch` | `outputOnly`, `capacity` + `FluidTank` keys | `TileFluidHatch.java:225-244` |
| `ItemLinker` item | `MasterPos{MasterX,MasterY,MasterZ}`, `dimId` | `ItemLinker.java:92-152` |
| `ItemProjector` item | `machineId`, `machineIdPrev`, `yOffset`, `x`, `y`, `z`, `dir` | `ItemProjector.java:545-650` |

Registry identities this tree owns (not in [C3](../30-contracts/C3-registry-ids.md), which was written before the import — see
*Contradictions*): blocks `libvulpes:{hatch, blockPhantom, placeHolder, structureMachine, advStructureMachine, creativePowerBattery,
forgePowerInput, forgePowerOutput, coalGenerator, motor, advancedMotor, enhancedMotor, eliteMotor, IC2Plug*, GTPlug*}`
(*only if the mod is loaded), items `libvulpes:{linker, battery, holoProjector}`, per-registry `ore<i>/metal<i>/coil<i>` and
`product<name>`; tile ids `vulpesoutputHatch, vulpesinputHatch, vulpesplaceHolder, vulpesFluidHatch, vulpesTileSchematic,
vulpesCreativeBattery, vulpesForgePowerInput, vulpesForgePowerOutput, vulpesCoalGenerator, vulpesTilePointer, vulpesTileInvPointer,
ARIC2Plug*, ARGTPlug*` [V] `LibVulpes.java:140-161,214-261`. The tile ids are registered with the legacy two-argument
`registerTileEntity(Class, String)` with **no namespace**, which resolves to the vanilla domain [A] (Forge `GameRegistry.java:230-235`
and vanilla `TileEntity.java:36-39` read; the resulting key was not printed in a running game).

## Invariants

- **INV-LVP-01 [V]** Everything libVulpes registers names its domain explicitly (`REGISTRY_DOMAIN = "libvulpes"`); a bare
  `setRegistryName` would resolve against the active container, which is the host's. `LibVulpes.java:85,158-160,214-227`,
  `api/material/MaterialRegistry.java:120,159-161`.
- **INV-LVP-02 [V]** libVulpes' content and registry listeners are created before the host's, by call order in the host's pre-init, not
  by class-load order. `LibVulpes.java:300-309`, `H:Stellurgy.java:574`.
- **INV-LVP-03 [V]** A packet's wire id is its index in the one list passed to `openChannel`; the channel opens once (FML refuses a
  second), and every sender is loud before it opens. `network/PacketHandler.java:51-66,99-105`. Reordering renumbers the tail
  ([C2](../30-contracts/C2-network-wire.md) `CON-C2-03`).
- **INV-LVP-04 [V]** Every packet of the channel executes on the receiving side's game thread, and the player is read when it executes.
  `network/BasePacket.java:106-120`.
- **INV-LVP-05 [V]** `PacketMachine` decoding reads bytes only; an addressee that cannot be resolved on the game thread is dropped.
  `network/PacketMachine.java:88-100,106-116`.
- **INV-LVP-06 [V]** A structure is validated in full before the first world mutation of that attempt; the cell list and the port caches
  are cleared before validation, so a failed attempt leaves the machine with no ports. `tile/multiblock/TileMultiBlock.java:317-439`.
- **INV-LVP-07 [A]** A controller placed alone is not complete; a fixture-built correct structure validates, and replacing any one
  required cell (lens, motor, the air-required centre) makes validation refuse. `MultiblockControllerPreAssemblyTest.java:101,134`,
  `ObservatoryMultiblockTest.java:61,84,108,133`.
- **INV-LVP-08 [V]** A block replaced at formation is held by a placeholder (state + a copy of its tile) and put back on
  deconstruction. `TileMultiBlock.java:241-258,494-502`.
- **INV-LVP-09 [V]** A placeholder and a ghost block persist the replaced block by its **numeric registry id**, so those saves are tied
  to the id table of that session's mod set. `tile/multiblock/TilePlaceholder.java:72,88`, `tile/TileSchematic.java:74`.
- **INV-LVP-10 [V]** A recipe's inputs leave the hatches when the recipe **starts**; its outputs reach the hatches when it **completes**;
  deconstruction in between discards the pending outputs. `TileMultiblockMachine.java:172,181,501`.
- **INV-LVP-11 [A]** A powered, enabled, formed machine consumes at least one input and ends with the recipe's item or fluid output in an
  output hatch, for the nine recipe machines. `MachineRecipeEndToEndKit.java:293,311,337` (run by the `*RecipeEndToEndTest` classes);
  cargo transits between linked railguns through the hatches, `MachineGuiClientGroupTest.java:551,553`.
- **INV-LVP-12 [V]** Machine speed is the product of the multipliers of the cells replaced at formation, reset to 1 on deconstruction;
  recipe time never drops below one tick. `TileMultiPowerConsumer.java:109-138,261-262`, `TileMultiblockMachine.java:503`.
- **INV-LVP-13 [V]** `acceptEnergy` conserves (remainder passed on); `extractEnergy` on a `MultiBattery` does not (each battery is asked for
  the full request). `util/MultiBattery.java:26-33,63-68`.
- **INV-LVP-14 [V]** A `PowerMultiplier` ≠ 1 changes what a consumer must **hold** to run, not what it **spends**; recipe machines
  (`TileMultiblockMachine`) ignore it altogether. `TileMultiPowerConsumer.java:194-196,208`, `TileMultiblockMachine.java:130`,
  `tile/TileEntityRFConsumer.java:119-120`.
- **INV-LVP-15 [V]** Window-property values are 16 bits on the wire; a module sending more must split. `SPacketWindowProperty.java:52`,
  `inventory/modules/ModulePower.java:66-104`.
- **INV-LVP-16 [V]** A modular-GUI target is addressed by `y > -1` (tile), else `x == -1` (held item), else entity id in `x`; ids 0-3.
  `inventory/GuiHandler.java:13-41`. The host forwards exactly these ids ([inventory-containers](./inventory-containers.md) `MECH-IVC-01`).
- **INV-LVP-17 [V]** Slot acceptance is the `Slot` subclass's, never the inventory's `isItemValidForSlot`, for slots built by
  `ModuleSlotArray` (quick-move included). `inventory/modules/ModuleSlotArray.java:61`.
- **INV-LVP-18 [A]** Shift-click from the player inventory moves a stack into a machine's module slot and the move is server state.
  `MachineGuiClientGroupTest.java:648,651,653`; the screen class under a machine GUI is `GuiModular` (`:264,411`).
- **INV-LVP-19 [V]** A material's identity is its registration index (product damage, ore meta, block group `index/16`); registration
  order is therefore a save contract. `api/material/MaterialRegistry.java:68-72,170-186,299`.
- **INV-LVP-20 [V]** A pointer persists only its master's coordinates; the master tile is resolved lazily and re-fetched when the cached
  one is invalid. `tile/TilePointer.java:116-121,147-165`.
- **INV-LVP-21 [V]** A hatch is mastered by the last controller that integrated it; the earlier master is not notified.
  `TileMultiBlock.java:357-372,508-510`.
- **INV-LVP-22 [A]** A linker bound to an `ILinkAimedTile` hands it the block or creature the player's line of sight lands on.
  `WeaponClientGroupTest#aPlayerNamesTheBatteryItsTargetByLookingAtItThroughALinker`.
- **INV-LVP-23 [V]** An output-only fluid hatch refuses external fills and accepts internal ones; at integration it is classed by
  `isOutputOnly`. `TileFluidHatch.java:98-103,135-143`, `TileMultiBlock.java:516-521`.
- **INV-LVP-24 [V]** Plug capacity is `10^tier × 10000 × energy_multiplier` at construction; a saved `maxEnergy` overrides it on load.
  `TilePlugBase.java:47-49,100-104`.

Assumptions: one of 24 (`[A]` on the tile-id domain above) — within the 10 % bound of the methodology's P3.

## Failure modes & edge cases

- **Hatch contents on teardown.** Deconstruction `invalidate()`s hatch tiles (`TileMultiBlock.java:260-263`); the next
  `getTileEntity` discards an invalid tile and builds a fresh one (`Chunk.java:844-848`) through `BlockHatch.createTileEntity`
  (`BlockHatch.java:65-76`), so items and fluid in a hatch are not carried over (and not dropped). The host already hand-rolls a
  snapshot in three machines for exactly this ([multiblock-machines](./multiblock-machines/00-overview.md) `MECH-MBM-02`).
- **Chunk behaviour inside a tick.** `completeStructure` calls `getChunkFromBlockCoords(...).isLoaded()` (`TileMultiBlock.java:342`);
  on the server `provideChunk` loads or **generates** the chunk before the flag is read (`ChunkProviderServer.java:144-169`), so the
  check never refuses there and a structure across a chunk border forces its neighbour to load from a tile tick. A harness comment
  already records "on the server it loads as it asks" (`MachineRecipeEndToEndKit.java:111-118`).
- **Recipe machines do not re-form.** After a hatch's chunk unloads the master is incomplete (`TileInventoryHatch.java:173-181`);
  `TileMultiblockMachine` has no periodic retry (`TileMultiblockMachine.java:105-151`), so only a right-click re-forms it.
- **Mid-recipe teardown** loses the batch: inputs were taken at start (INV-LVP-10).
- **Fluid ingredients over several `L` hatches** are drained by asking each hatch for the *whole* ingredient (`TileMultiblockMachine.java:285-296`,
  where the per-hatch remainder is computed at `:287-288` and never used for the drain).
- **`MultiBattery` + several `P` plugs** multiplies the draw by the plug count (INV-LVP-13) while the gate checks the sum.
- **Same item in two ingredient slots** of one recipe can be satisfied by one stack in `canProcessRecipe` (no claim tracking, `:318-345`).
- **Forge-energy exposure.** `ForgePowerCapability` ignores `canReceive`/`canExtract`, so an input plug can be drained and an output
  plug filled from outside (`cap/ForgePowerCapability.java:14-42`); `TileEntityRFConsumer.extractEnergy` ignores `simulate`
  (`tile/TileEntityRFConsumer.java:151-153`) and its `setMaxEnergyStored` writes the **stored** value (`:165-167`).
- **Latent material-grouping bug.** `registerOres` fills a block group with `j < 16*i + (len % 16)` (`MaterialRegistry.java:170`): with 11
  and 2 materials the one group is right, but a registry of 16 or more would leave group 0 short.
- **`HashedBlockPosition.y` is a `short`** (`util/HashedBlockPosition.java:11,16,22`); heights beyond ±32767 wrap silently.
- **`ItemLinker.resetPosition` writes `dimId` inside `MasterPos`** while `getDimId` reads the top-level key (`ItemLinker.java:169-178,105-114`),
  so a reset keeps the previous top-level dimension for the next binding that does not set one.
- **`ModuleProgress.setTooltip(null/"")` dereferences an unset list** (`inventory/modules/ModuleProgress.java:23,38-40`).
- **Decoder exposure.** `PacketEntity` and `PacketItemModifcation` look the world and entity up inside the netty decoder and dereference
  `DimensionManager.getWorld` unchecked (`PacketEntity.java:72-77`, `PacketItemModifcation.java:76-81`) — the failure `PacketMachine`'s
  class comment describes and fixed only for itself.

## Integration seams

- **Host (`Stellurgy`)**: drives the lifecycle (`H:Stellurgy.java:574`, `H:Stellurgy.java:1441`, `H:Stellurgy.java:1551`), builds the
  one GUI-handler chain (`H:Stellurgy.java:1459`), registers recipe files (`H:Stellurgy.java:778-788`), registers prototypes with the
  projector (`H:Stellurgy.java:1576-1581`), sets `TilePlugBase.energy_multiplier` and `TileFluidHatch.capacityMultiplier`
  (`H:Stellurgy.java:1697-1698`), owns the second `MaterialRegistry` (`H:Stellurgy.java:311`), and owns packet order
  (`PacketRegistry`). Only `ClientProxy` (`client/ClientProxy.java:33`) reaches back into the host object; the only other host package
  libVulpes imports is `api` (`Constants`).
- **Capabilities ([C5](../30-contracts/C5-capabilities-events.md))**: Forge Energy, item handler (hatches), fluid handler (fluid
  hatch), Tesla (optional), GregTech `IEnergyContainer` adapter (`compat/GTEnergyCapability.java`, optional).
- **Mixin ([C6](../30-contracts/C6-mixin-asm-at.md))**: `JetpackKeys` is implemented on `EntityPlayer` by Stellurgy's
  `MixinEntityPlayerJetpackKeys` (`MixinEntityPlayerJetpackKeys.java:14`; listed in `mixins.stellurgy.json`).
- **Wire ([C2](../30-contracts/C2-network-wire.md))**: the libVulpes packets are wire ids 0, 1, 2, 6 (INV-LVP-03).
- **JEI**: `compat/jei/LibVulpesPlugin.java` hides the phantom and placeholder blocks.
- **Assets**: `assets/libvulpes/**` (blockstates, models, five lang files, `defaultrecipe.xml`).

## Config surface

| flag | where | mechanic | full-disable path |
|------|-------|----------|-------------------|
| `general.PowerMultiplier` (default 1) | `config/libvulpes.cfg` | MECH-LVP-06, INV-LVP-14 | none; `1` is identity |
| `general.EUPowerMultiplier` (default 4) | `config/libvulpes.cfg` | MECH-LVP-09 (EU→FE for IC2/GT plugs) | none |
| `blockEnergyHatchCapacityMultiplier`, `blockLiquidHatchCapacityMultiplier` | host `StellurgyConfiguration` | `TilePlugBase.energy_multiplier`, `TileFluidHatch.capacityMultiplier` | none; read once by the host |

The file is libVulpes' own (`<REGISTRY_DOMAIN>.cfg`, not the host's) and is read in `preInit` [V] `LibVulpes.java:310-318`. It is
**not** listed in [C4](../30-contracts/C4-config-surface.md).

## Test coverage

| invariant | test |
|-----------|------|
| INV-LVP-07 | `MultiblockControllerPreAssemblyTest.java:101,134`; `ObservatoryMultiblockTest.java:61,84,108,133` (server) |
| INV-LVP-11 | `MachineRecipeEndToEndKit.java:293,311,337` via `ArcFurnace/Centrifuge/ChemicalReactor/Crystallizer/Electrolyser/Lathe/PrecisionAssembler/PrecisionLaserEtcher/RollingMachine` `RecipeEndToEndTest`; `MachineGuiClientGroupTest.java:551,553` |
| INV-LVP-18 | `MachineGuiClientGroupTest.java:648,651,653,264,411` (client) |
| INV-LVP-22 | `WeaponClientGroupTest#aPlayerNamesTheBatteryItsTargetByLookingAtItThroughALinker` (client) |
| teardown does not throw | `SpaceElevatorUnlinkedDeconstructTest.java:71` (a machine whose master was never linked) |

No test located for: placeholder restoration, hatch-content survival across deconstruction, the `PacketMachine` drop paths, the
`MultiBattery` draw, `ModuleBase` sync cadence, the material registry, the linker's unbound/other-dimension replies, the projector.
(Searched `src/test/**` for `MultiBattery|TilePlaceholder|deconstructMultiBlock|MaterialRegistry|ModulePower|EmbeddedInventory|InputSyncHandler`:
only the files above plus unrelated hits.)

## Contradictions with existing docs

- [C3](../30-contracts/C3-registry-ids.md) `R6` says no `MissingMappings` handler exists anywhere (`C3-registry-ids.md:193`); one does
  (`LibVulpes.java:284-293`, registered at `:307`) — the doc predates the import.
- C3 `:225` counts 63 tile ids "all Stellurgy-prefixed" and lists none of the libVulpes tile, block, item or material ids above.
- [network-wire](./network-wire.md) `:194-196` (Open questions) says the discriminator comes from a counter inside libVulpes and is
  "not verifiable from `src/main`"; it is the list index in `PacketHandler.java:56-58`. Its `INV-NW-01` (`:122`) cites
  `BasePacket.idMap`, which is empty (`BasePacket.java:19-24`). Both it (`:27`) and C2 (`C2-network-wire.md:14`) still call the
  transport "foreign"; it is in-repo since 2026-09-29.
- C2 `CON-C2-05` (`C2-network-wire.md:85`) calls `PacketBackToRocketGui` the sole C→S packet; libVulpes' `PacketMachine`,
  `PacketEntity`, `PacketItemModifcation` and `PacketChangeKeyState` are also C→S, and their server executors do not check range,
  ownership or permission (`PacketMachine.java:128-132`, `TileMultiPowerConsumer.java:351-365`).
- [inventory-containers](./inventory-containers.md) `:30` says the framework is "owned by no subsystem doc yet"; this doc is its owner.
- [C4](../30-contracts/C4-config-surface.md) has no row for `libvulpes.cfg`.

## Open questions

Written as leads: none was reproduced in a running game.

1. **Hatch contents lost on deconstruction** — see *Failure modes*; confirm in-game, then decide between carrying the contents in
   `destroyBlockAt` or dropping the `invalidate()`.
2. **`MultiBattery.extractEnergy` over-draw** (`util/MultiBattery.java:30`) with more than one `P` plug.
3. **Fluid over-drain across several `L` hatches** (`TileMultiblockMachine.java:285-296`).
4. **Recipe machines never re-form** after a hatch-chunk unload (`TileMultiblockMachine.java:105-151` vs `TileMultiPowerConsumer.java:159-163`).
5. **Server executors of `PacketMachine`/`PacketItemModifcation` trust the client's coordinates**: `ItemProjector.useNetworkData`
   (`ItemProjector.java:709-730`) lets a holder of the projector place ghost blocks, and force chunk loads, at any position, and index
   `machineList` with a client-sent id (`:714`).
6. **`PowerMultiplier` charges nothing** it gates (INV-LVP-14) — decide whether the config is meant to scale spend.
7. Areas that would need their own sub-doc: the ~30 `Module*` widgets (`ModuleContainerPan*`, `ModuleLiquidIndicator`,
   `ModuleBlockSideSelector`), `ItemProjector`'s client-side preview/GUI, `render/RenderHelper`, and the non-multiblock `TileEntity*`
   machine family. Their state is thin and mostly client rendering; they are covered here only as far as other subsystems depend on them.
