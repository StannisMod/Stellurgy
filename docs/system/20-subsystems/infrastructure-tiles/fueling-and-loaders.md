# infrastructure-tiles / fueling & loaders

The transfer stations that move fuel, items and fluids between world storage and a linked
rocket. All are `IInfrastructure` with `disconnectOnLiftOff()==true`.

## Key types

| class | role |
|-------|------|
| TileFuelingStation | RF+tank consumer; fills rocket fuel/oxidizer/working-fluid tanks; locks rocket fluids |
| TileRocketLoader | pushes items from own inventory → rocket cargo tiles |
| TileRocketUnloader | subclass; pulls items rocket → own inventory (insert/extract flipped) |
| TileRocketFluidLoader | pushes fluid own tank → rocket fluid tiles |
| TileRocketFluidUnloader | subclass; pulls fluid rocket → own tank (`setOutputOnly`) |

## Mechanics

- **MECH-INFRA-12 — Fuel transfer & fluid lock-in.** `TileFuelingStation.performFunction`
  (throttled `OP_THROTTLE_TICKS`, tunable) picks the first matching rocket tank
  (oxidizer → bipropellant → monopropellant → nuclear-working) with room and transfers up
  to `fuelPointsPer10Mb * OP_THROTTLE_TICKS` (C4) mB, draining exactly the delta the
  rocket accepted. If the rocket's fuel/oxidizer/working fluid is still `"null"`, the
  first compatible station fluid *locks* the rocket's `stats.set*Fluid`.
  [V TileFuelingStation.java:189-338]
- **MECH-INFRA-13 — Fueling redstone semantics.** Emits redstone (via
  `BlockTileRedstoneEmitter`, duplicate-suppressed by `lastRs`) when the tank fluid is
  *relevant* to the rocket but the rocket is *full*; `fuelingActive` arms only while there
  is room and is deliberately **not persisted** (re-derived in `onLoad`).
  [V TileFuelingStation.java:86-101, 192-206, 578-599, 652-670]
- **MECH-INFRA-14 — Bucket intake.** A 10-tick poll and `setInventorySlotContents` drain
  fluid containers from input slot 0 into the internal tank via
  `FluidUtils.attemptDrainContainerIInv`; `getSlotsForFace(DOWN)` exposes output slot 1.
  [V TileFuelingStation.java:172-185, 422-441, 544-550]
- **MECH-INFRA-15 — Item loader transfer.** `TileRocketLoader.update` (throttled
  `TRANSFER_INTERVAL_TICKS`, `MAX_TRANSFER_PER_OPERATION` per op, both tunable) does a
  simulate-then-commit `ItemHandlerHelper` insert into rocket inventory tiles (skipping
  guidance/satellite hatches), one transfer per operation. It uses its **own**
  `InvWrapper(inventory)` handler, explicitly bypassing the libVulpes capability which
  "returns the EmbeddedInventory". [V TileRocketLoader.java:59-120, 172-268]
- **MECH-INFRA-16 — Loader redstone + input gate.** `redstoneState` reflects "rocket has
  no empty slot" (loader) / "rocket empty" (unloader); an independent `inputRedstoneState`
  + per-side `ModuleBlockSideSelector` gate *whether* transfer is allowed. Loader default
  `inputstate=OFF` means always-allowed; when a redstone-input side is chosen, transfer
  needs strong power on that side. [V TileRocketLoader.java:164-268, 383-390]
- **MECH-INFRA-17 — Fluid loader/unloader transfer.** `TileRocketFluidLoader.update`
  drains own tank → best-fill rocket fluid handler (any side), one accepted transfer per
  `TRANSFER_INTERVAL_TICKS`; the unloader flips direction (rocket → own tank) via
  `setOutputOnly(true)`. Both probe a 1-mB sample to set the redstone "full/empty" signal
  and notify the rocket with `PacketEntity(rocket, 9987)`. [V TileRocketFluidLoader.java:180-235; TileRocketFluidUnloader.java:59-115]

## State & persistence

NBT keys: see `C1-nbt-persistence` (fueling-and-loaders). Notes: `masterPos` only if `hasMaster()`; `state`, `inputstate` and `bytes`
are wire-only (loader `readDataFromNetwork`); item/fluid loaders also persist the `ModuleBlockSideSelector` per-side states through
`sideSelectorModule.write/readToNBT`; the fueling tank/inventory persist via the libVulpes parent (`TileInventoriedRFConsumerTank`).
[V TileRocketLoader.java:370-390; TileFuelingStation.java:578-599]

## Invariants

- **INV-INFRA-11 [A][BEH]** With `state=ON`, `setRedstoneState(true)` emits and
  `setRedstoneState(false)` does not; `INVERTED` flips it. [T RocketLoaderRedstonePolarityTest.java:84-98] Pinned by `RocketLoaderRedstonePolarityTest#onStateEmitsRedstoneWhenConditionTrue`, `RocketLoaderRedstonePolarityTest#onStateStaysOffWhenConditionFalse`, `RocketLoaderRedstonePolarityTest#invertedStateFlipsTruthOutput`, `RocketLoaderRedstonePolarityTest#invertedStateFlipsFalseOutput`.
- **INV-INFRA-12 [A][BEH]** A powered fueling station adjacent to a rocket transfers fuel: the
  station tank drops and the rocket's fuel rises.
  [T RocketInfrastructureSmokeTest.java:31] Pinned by `RocketInfrastructureSmokeTest#stationDrainsTankAndRocketFuelRisesAfterLinkAndTick`.
- **INV-INFRA-13 [A][BEH]** The fluid loader actively transfers fluid into a rocket fluid tile
  when allowed. [T FluidLoaderActiveTransferTest.java] Pinned by `FluidLoaderActiveTransferTest#loaderTransfersOxygenIntoRocketStorageLiquidTanks`.
- **INV-INFRA-14 [A][BEH]** The item unloader actively pulls items out of a rocket.
  [T RocketItemUnloaderActiveTransferTest.java] Pinned by `RocketItemUnloaderActiveTransferTest#unloaderPullsItemsFromRocketStorage`.
- **INV-INFRA-15 [V][BEH]** Item transfer is simulate-then-commit and clamped to `accepted`,
  with a put-back fallback, so items cannot duplicate under a well-behaved handler.
  [V TileRocketLoader.java:233-257]
- **INV-INFRA-16 [V][BEH]** Fueling drains exactly the accepted delta
  (`max(0, ret-before)`, falling back to `min(toOffer, ret)`), never more than the tank
  holds. [V TileFuelingStation.java:302-328]

## Failure modes & edge cases

- Loaders cast the linked base to `EntityRocket`; safe because the only station-deployed
  variant `EntityStationDeployedRocket extends EntityRocket`.
  [V TileRocketLoader.java:342; EntityStationDeployedRocket.java:54]
- `TileRocketFluidUnloader` imports the optional GalactiCraft
  `PacketEntityUpdate` (unused) — harmless (no classload) but an accidental soft-dep
  smell. [V TileRocketFluidUnloader.java:3] (unconfirmed).
- Fueling `fuelingActive` not persisted: after reload the station idles until `onLoad`
  re-arms from tank contents — matches original continuous behaviour by design.
  [V TileFuelingStation.java:584]

## Integration seams

Blocks: `StellurgyBlocks.blockLoader` (BlockStellurgyHatch) and `blockFuelingStation`
(BlockTileRedstoneEmitter) drive redstone output (C3). Packets: `PacketMachine`,
`PacketEntity` id 9987, `PacketHandler` (C2). Caps: `ITEM_HANDLER`, `FLUID_HANDLER` (C5).

## Config surface

`fuelPointsPer10Mb` (int, C4) is the per-throttle fuel step; setting it to 0 stops fuel
accrual while the redstone "relevant/full" signalling still runs. Throttle/interval
constants are `tunable`.

## Test coverage

INV-INFRA-11 → RocketLoaderRedstonePolarityTest:84-98 · INV-INFRA-12 →
RocketInfrastructureSmokeTest · INV-INFRA-13/14 → FluidLoaderActiveTransferTest,
RocketItemUnloaderActiveTransferTest.
