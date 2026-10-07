# Orbital Laser Drill

Part of [multiblock-machines](./00-overview.md). Files: `orbitallaserdrill/TileOrbitalLaserDrill.java`
(1021), `AbstractDrill.java` (17), `MiningDrill.java` (164), `VoidDrill.java` (234),
`terraformingdrill.java` (150).

## Purpose

A space-station multiblock that fires a laser at the planet it orbits. One controller drives a
swappable **drill strategy** (Strategy pattern via `AbstractDrill`) chosen by the current `MODE`:
mine ore from the planet surface, synthesise ore "from nowhere" when planet-mining is disabled,
or terraform the planet's terrain heightmap.

## Key types

| class | role |
|-------|------|
| `TileOrbitalLaserDrill` | controller; owns mode, spiral state, power, GUI, run-gating |
| `AbstractDrill` | abstract strategy: `performOperation / activate / deactivate / isFinished / needsRestart` |
| `MiningDrill` | mines a 3×3 column downward on the orbited planet; spawns `EntityLaserNode` |
| `VoidDrill` | conjures ore/cobble without a real world (used when `laserDrillPlanet=false`) |
| `terraformingdrill` | walks the `TerraformingHelper` queue, calls `BiomeHandler.terraform` |

`MODE ∈ {SINGLE, SPIRAL, T_FORM}`. Which concrete mining strategy exists is fixed at
construction from config: `laserDrillPlanet ? MiningDrill : VoidDrill`; the `voidMiningMode`
field is the negation and forces `SINGLE`. [V] `TileOrbitalLaserDrill.java:110,154-160`.

## Mechanics

### MECH-MBM-03 — Drill strategy dispatch
GUI arrows change `mode`; `useNetworkData` id 14 rebinds `drill` to `terraformingDrill` when
`mode==T_FORM` else `miningDrill`, deactivates the old drill, resets running. On NBT load the
same rebinding runs. In `voidMiningMode` any non-`SINGLE` mode is coerced back to `SINGLE` on
both packet-in and NBT-load. [V] `TileOrbitalLaserDrill.java:313-337,285-287,633-651`.
[T] mode dispatch mining path — `OrbitalLaserDrillModeDispatchTest.java:38-58`.

### MECH-MBM-04 — Mining operation
Each fired op breaks the 3×3 around the laser node, posts a cancellable
`BlockBreakEvent.LaserBreakEvent` per block, collects `getDrops`, sets blocks to air, then
descends the node until it hits an opaque block (or `y<1` → finished). Non-opaque/bedrock are
skipped; empty-drop opaque blocks are voided. Results merge into output hatches via
`ZUtils.mergeInventory`; if the merge leaves leftovers the drill deactivates and the tile sets
`isJammed`. [V] `MiningDrill.java:33-115`, `TileOrbitalLaserDrill.java:498-512`.

### MECH-MBM-05 — Void-drill ore synthesis
When planet-mining is off, `VoidDrill` builds an ore list from `standardLaserDrillOres` (ore-dict
name, optional count, or `block/item:meta:size`) plus the orbited dim's
`DimensionProperties.laserDrillOres`, cached per source dim. Normal mode: 10 % roll an ore else
one cobblestone. **voidCobble** toggle (GUI button id 3 → packet 17, server-only): suppresses
cobble entirely and yields a single ore only every 10th op. [V] `VoidDrill.java:67-191`,
`TileOrbitalLaserDrill.java:342-353`.

### MECH-MBM-06 — Terraforming drill
`performOperation` pulls up to 6 positions per op from the planet's `TerraformingHelper` queue
(`get_next_position`), terraforms each via `BiomeHandler.terraform`, and force-loads the working
chunk (releasing the previous ticket when the chunk changes). Returns `null` (no items). The
helper is lazily fetched (`TerraformingHelper.of(world)`) or loaded. `isFinished()` is true
when the queue empties. [V] `terraformingdrill.java:34-110,143-145`.

### MECH-MBM-07 — Run-gating & spiral advance
`checkCanRun()` (server-only) decides whether the laser runs. `unableToRun()` blocks when: no
stored energy, not a `WorldProviderSpace`, cannot travel to the parent planet, or (non-T_FORM)
the parent planet is in `laserBlackListDims`. It also requires a `SpaceObject` at the tile and
redstone power. `voidMiningMode` takes a fast path (no station/dim needed beyond source id).
`WARPDIMID` orbit → never runs. `terraformingstatus` is synced (packet 16) from
`TerraformingHelper.has_blocks_in_tf_queue`. When a SINGLE op finishes it sets `finished`; a
SPIRAL that is redstone-powered advances the laser center outward (N→E→S→W, radius grows every
half-turn) and syncs new position (packet 15). [V] `TileOrbitalLaserDrill.java:681-818,519-552`.

### MECH-MBM-08 — Chunk-loading ticket lifecycle
`MiningDrill.activate` requests a `ForgeChunkManager` NORMAL ticket, force-loads the target
chunk, finds the top solid/liquid `y` over the 3×3, and spawns the `EntityLaserNode`.
`deactivate` releases the ticket and clears the node. The terraforming drill re-requests a
ticket every time the working chunk changes. Ticket release also happens in `onDestroy`,
`onChunkUnload`, `invalidate`. [V] `MiningDrill.java:117-155`, `terraformingdrill.java:82-137`,
`TileOrbitalLaserDrill.java:566-588,992-1002`.

## State & persistence

Persisted keys (`writeToNBT`/`readFromNBT`, `TileOrbitalLaserDrill.java:602-656`): see `C1-nbt-persistence`. Notes: `mode` is coerced to
SINGLE if `voidMiningMode`; `voidCobble` is re-applied to `VoidDrill` on read; `CenterY` really stores the Z centre; `radius`,
`numSteps`, `prevDir` (spiral walk state) are written only when `mode==SPIRAL && prevDir!=null`. Transient network keys (packet
payloads, never on disk): `currentX/currentZ`, `newX/newZ`, `isRunning`, `terraformingstatus`; the network key `radius` also appears
for the gravity controller elsewhere — no collision (different tile).

## Integration seams

- **Packets** (libVulpes `PacketMachine`, discriminators): 11 full-state, 12 running, 13 client
  requests update, 14 mode+center change, 15 laser position, 16 terraformingstatus, 17 toggle
  voidCobble. [V] `TileOrbitalLaserDrill.java:196-357`.
- **Events**: posts `BlockBreakEvent.LaserBreakEvent` (cancellable) per mined block.
- **Forge**: `ForgeChunkManager` tickets; `DimensionManager.initDimension` for the orbited world.
- **Entities**: `EntityLaserNode`.

## Config surface

Config: see `C4-config-surface`. Full disable: `enableLaserDrill=false` removes the machine. Not in C4: `voidMiningMode`, the GUI state that `laserDrillPlanet` drives; `POWER_PER_OPERATION` is `10000 × spaceLaserPowerMult` (`tunable`).

## Invariants

- **INV-MBM-01** [V] The concrete mining strategy is fixed at construction by `laserDrillPlanet`;
  it is never swapped at runtime — only mining↔terraforming is. `TileOrbitalLaserDrill.java:154-160,321-331`.
- **INV-MBM-02** [V] In `voidMiningMode`, `mode` can never persist or receive as anything but
  `SINGLE` (coerced on packet-in and NBT-read). `TileOrbitalLaserDrill.java:285-287,633-635`.
- **INV-MBM-03** [V] `setRunning`, `checkCanRun`, and the whole op loop are server-only guarded
  (`world.isRemote` early-return / `!world.isRemote` block). `TileOrbitalLaserDrill.java:417-420,697,483`.
- **INV-MBM-04** [T] Mining mode breaks the target block (→ air) and yields its drop with count
  > 0. `OrbitalLaserDrillModeDispatchTest.java:47-58`.
- **INV-MBM-05** [V] A drill ticket is released on every teardown path (deactivate/onDestroy/
  onChunkUnload/invalidate); no ticket outlives the tile. `TileOrbitalLaserDrill.java:566-588,992-1002`.

## Test coverage

`OrbitalLaserDrillMultiblockTest` (formation), `OrbitalLaserDrillModeDispatchTest` (INV-MBM-04),
`RocketAssemblerMiningDrillStatTest` (drill stat integration).

## Failure modes & edge cases

- Spiral state uses `CenterX`/`CenterY` where `CenterY` actually stores the Z center
  (`yCenter` bound to `newZ`) — cosmetic naming only, no defect.
