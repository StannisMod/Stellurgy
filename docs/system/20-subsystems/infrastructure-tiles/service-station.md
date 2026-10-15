# infrastructure-tiles / service station

`TileRocketServiceStation` (730 sloc) — an RF-consuming station that repairs a linked
rocket's worn parts, either by handing broken parts to nearby `TilePrecisionAssembler`s
or, with no assembler, by consuming repair materials from its own six input slots.
`TileBrokenPart` (39 sloc) is the wear-host tile a worn rocket motor becomes.

## Key types

| class | role |
|-------|------|
| TileRocketServiceStation | RF consumer; repair-queue driver; standalone-repair fallback |
| TileBrokenPart | `TileWearable` subclass; renders breaking overlay; drops staged item |

## Mechanics

- **MECH-INFRA-08 — Repair-list scan.** On link (`updateRepairList`) or button-triggered
  `scanForAssemblers`, the station walks `rocket.storage.getTileEntityList()` collecting
  every `TileBrokenPart` with `getStage()>0` into `partsToRepair`/`statesToRepair`;
  `initialPartToRepairCount` snapshots the count for the progress bar.
  [V TileRocketServiceStation.java:129-171]
- **MECH-INFRA-09 — Assembler-backed repair.** `giveWorkToAssemblers` (throttled to
  every 20 game ticks via `canPerformFunction`) feeds one worn part per idle assembler:
  it inserts the worn item into the assembler's in-ports, removes the part from rocket
  storage, and on a finished "rocket" output stack resets the part `setStage(0)` and
  re-adds it. A vanished assembler re-queues its in-flight part. Changes call
  `syncRocket` (PacketEntity to nearby). [V TileRocketServiceStation.java:173-273, 445-462]
- **MECH-INFRA-10 — Standalone repair fallback.** With no valid assembler,
  `tryStandaloneRepair` finds the `TilePrecisionAssembler` recipe whose ingredients match
  the worn item, then consumes its *non-part* ingredients ×
  `serviceStationStandaloneRepairMultiplier` (C4, tunable) from the six-slot
  `repairInventory`, simulating first; success resets the part. Parts with no recipe are
  dropped from the queue so it advances. [V TileRocketServiceStation.java:355-443]
- **MECH-INFRA-11 — Repair-material capability.** The `repairInventory`
  (`EmbeddedInventory`, 6 slots) is exposed via `ITEM_HANDLER` capability on all sides so
  hoppers/pipes can feed it regardless of GUI layout. Worn-part counts (motors/seats/
  tanks) shown in GUI are computed via `CapabilityWear.get(te)`, not just
  `TileBrokenPart`. [V TileRocketServiceStation.java:316-338, 596-629]

## State & persistence

NBT keys: see `C1-nbt-persistence` (service-station). Notes: `repairInv` absent ⇒ empty; `assemblerPoses`, `partsProcessing` and
`statesProcessing` are lazily rehydrated on the next powered tick (`assemblerPoses` mapped to live TEs, then nulled); `was_powered` is
the rising-edge detector for assembler rescan (shared key name with the monitoring station); `TileBrokenPart` wear NBT
(`stage`/`maxStage`/`transitionProb`) is inherited from `TileWearable`. [V TileRocketServiceStation.java:522-551, 285-299]

## Invariants

- **INV-INFRA-06 [T][BEH]** `performFunction` on an unlinked, powered service station is a
  safe no-op: repair queue stays empty, no crash.
  [T ServiceStationUnlinkedPerformFunctionTest.java:60-66] Pinned by `ServiceStationUnlinkedPerformFunctionTest#performFunctionOnUnlinkedPoweredStationIsSafeNoOp`.
- **INV-INFRA-07 [T][BEH]** An unlinked service station reports `linkedRocketId==-1` and 0
  parts-to-repair. [T RocketServiceStationLinkAndStateTest.java:83-85] Pinned by `RocketServiceStationLinkAndStateTest#serviceStationTicksWithoutLinkedRocketWithoutCrash`.
- **INV-INFRA-08 [T][BEH]** The broken-part scan collects exactly the worn parts of the linked
  rocket. [T ServiceStationBrokenPartScanContractTest.java] Pinned by `ServiceStationBrokenPartScanContractTest#injectedBrokenPartAppearsInPartsToRepairAfterLink`, `ServiceStationBrokenPartScanContractTest#multipleInjectionsAreAllScanned`.
- **INV-INFRA-09 [V][BEH]** Standalone repair simulates material consumption before committing;
  insufficient materials leave the part queued and consume nothing.
  [V TileRocketServiceStation.java:376-379]
- **INV-INFRA-10 [V]** A part whose block is not `IBrokenPartBlock` is logged and dropped
  from the queue rather than crashing the loop.
  [V TileRocketServiceStation.java:214-219, 361-365]

## Failure modes & edge cases

- Assembler in-port overflow on a repaired part is logged (`error`) and the part is lost —
  a `tunable` capacity assumption, not a crash. [V …:229]
- Only `EntityRocket` (not bare `EntityRocketBase`) is repairable; `linkRocket` guards
  `instanceof EntityRocket` before building the list. [V …:508-514]
- `canPerformFunction` clears the block's `STATE` visual when idle, sets it when work
  exists. [V …:446-462]

## Integration seams

RF: `TileEntityRFConsumer` (`getPowerPerOperation`=10, tunable). Caps: `ITEM_HANDLER`
(C5). Packets: `PacketEntity`/`PacketMachine` (C2). Depends on
`multiblock-machines.TilePrecisionAssembler` and the `recipe` registry
(`RecipesMachine.getRecipes(TilePrecisionAssembler.class)`).

## Config surface

Config: see `C4-config-surface`. Disabling assemblers falls back to standalone repair, so there is no full-disable path — repair is always possible if materials exist.

## Test coverage

INV-INFRA-06 → ServiceStationUnlinkedPerformFunctionTest:60-66 · INV-INFRA-07 →
RocketServiceStationLinkAndStateTest:83-85 · INV-INFRA-08 →
ServiceStationBrokenPartScanContractTest.
