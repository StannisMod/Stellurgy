# infrastructure-tiles / service station

`TileRocketServiceStation` (832 sloc) — an RF-consuming station that repairs a linked
rocket's worn parts on two rungs of the repair ladder (C20): by handing worn motors to
nearby `TilePrecisionAssembler`s, which rebuild them by recipe (the rocket's T2), or, with no
assembler, on its own as a repair bay does (T1, C20 REPAIR-11), out of spare parts in its six
reserve slots. `TileBrokenPart` (39 sloc) is the wear-host tile a worn rocket motor becomes.

## Key types

| class | role |
|-------|------|
| TileRocketServiceStation | RF consumer; assembler repair-queue driver; its own T1 repair through a `BayEngine` |
| TileBrokenPart | `TileWearable` subclass; renders breaking overlay; drops staged item |
| BayEngine (`damage/repair/`, owned by structural-damage) | the T1 law — price, credit, energy per stage, banking — shared with the ship's repair bay |

## Mechanics

- **MECH-INFRA-08 — Repair-list scan.** On link (`updateRepairList`) or button-triggered
  `scanForAssemblers`, the station walks `rocket.storage.getTileEntityList()` collecting
  every `TileBrokenPart` with `getStage()>0` into `partsToRepair`/`statesToRepair`;
  `initialPartToRepairCount` snapshots the count for the progress bar. This queue is the
  ASSEMBLER path's; the station's own repair walks the rocket itself (MECH-INFRA-10).
  [V TileRocketServiceStation.java:151-194]
- **MECH-INFRA-09 — Assembler-backed repair (the rocket's T2).** `giveWorkToAssemblers`
  (throttled to every 20 game ticks via `canPerformFunction`) feeds one worn part per idle
  assembler: it inserts the worn item (its item damage is its stage) into the assembler's
  in-ports, where the stage-matched `<kind>_repair_N` recipe runs, removes the part from rocket
  storage, and on a finished "rocket" output stack resets the part `setStage(0)` and re-adds it.
  A vanished assembler re-queues its in-flight part. Changes call `syncRocket` (PacketEntity to
  nearby). Each run costs `getPowerPerOperation()` = 10 FE — and 0 when there is no valid
  assembler, so the scan a station with none still runs is not a fee for nothing.
  [V TileRocketServiceStation.java:195-345] [T ServiceStationFullRepairCycleTest]
- **MECH-INFRA-10 — The station's own repair is the repair bay's (T1, since 2026-10-06).** While switched on by redstone and with no valid assembler, the station, every
  tick (`update` → `serviceStandalone`), works the linked rocket as a bay works a hull: an idle
  station looks every `LOOK_INTERVAL_TICKS` (20) over every tile of the rocket's storage that
  carries `IPartWear` with a stage above 0 — motors, tanks, seats — takes the first it can pay for,
  and restages it ONE stage per step through its `BayEngine`: a spare of the part's own kind at the
  same item damage (pristine) is drawn into `maxStage` stages of credit, a stage costs
  `repairBayEnergyPerStage` FE put in at `RepairBaySize.powerPerTick(N)`, N being the station plus
  the `IRepairBayPart` frames touching it, and the banked energy is saved. A rocket part has no
  position the damage map knows (its storage is a `WorldDummy` whose chunks are never "loaded"), so
  its stage is written through its own `IPartWear` — the only home it has. A part restaged to 0
  leaves the assembler queue. Answers are `RepairOutcome`: `REPAIRING / UNDAMAGED / NO_RECIPE /
  NO_MATERIALS / NO_CHARGE`, `NO_STRUCTURE` when no rocket is linked, and NO answer (null) while
  switched off or while an assembler has the work. A refusal the player can cure by stocking is
  reported over one he cannot. No claims: payment happens at each stage that lands, so two stations
  on one rocket each pay only for stages they took off.
  [V TileRocketServiceStation.java:345-492, BayEngine#work]
  [T RepairWelderTest.aServiceStationRestagesAWornMotorAStageAtATimeOutOfOneSpare,
  .everyRefusalOfTheServiceStationIsItsOwnAnswerAndCostsNothing]
  *(Until 2026-10-06: `tryStandaloneRepair` found the motor's stage-matched Precision Assembler
  recipe and consumed its non-part ingredients × `serviceStationStandaloneRepairMultiplier`
  (default 3.0), restoring the part to 0 in one step — a second, recipe-priced rung beside the
  bay. Retired by maintainer fork F2 = (a), 2026-10-05.)*
- **MECH-INFRA-11 — Reserve capability and screen.** The `repairInventory`
  (`EmbeddedInventory`, 6 slots) — the station's reserve of spares — is exposed via
  `ITEM_HANDLER` capability on all sides so hoppers/pipes can feed it. It takes any item; one that
  is not a spare of a worn part simply never pays. The screen's last text line shows the station's
  own answer (`msg.serviceStation.<outcome>`, synced by `ModuleSync` through `IDataSync`).
  Worn-part counts (motors/seats/tanks) are computed via `CapabilityWear.get(te)`.
  [V TileRocketServiceStation.java:494-516, 678-700, 756-765]

## State & persistence

NBT keys: see `C1-nbt-persistence` (service-station). Notes: `repairInv` (the six-slot reserve) absent ⇒ empty;
`credits` and `banked` are the `BayEngine`'s credit and banked energy (`BayEngine#writeTo`, the same keys as the repair bay);
not saved are the part the station's own repair is working, its answer and its size — the next look restores all three;
`assemblerPoses`, `partsProcessing` and
`statesProcessing` are lazily rehydrated on the next powered tick (`assemblerPoses` mapped to live TEs, then nulled); `was_powered` is
the rising-edge detector for assembler rescan (shared key name with the monitoring station); `TileBrokenPart` wear NBT
(`stage`/`maxStage`/`transitionProb`) is inherited from `TileWearable`. [V TileRocketServiceStation.java:601-632, 306-320]

## Invariants

- **INV-INFRA-06 [A][BEH]** `performFunction` on an unlinked, powered service station is a
  safe no-op: repair queue stays empty, no crash.
  [T ServiceStationUnlinkedPerformFunctionTest.java:60-66] Pinned by `ServiceStationUnlinkedPerformFunctionTest#performFunctionOnUnlinkedPoweredStationIsSafeNoOp`.
- **INV-INFRA-07 [A][BEH]** An unlinked service station reports `linkedRocketId==-1` and 0
  parts-to-repair. [T RocketServiceStationLinkAndStateTest.java:83-85] Pinned by `RocketServiceStationLinkAndStateTest#serviceStationTicksWithoutLinkedRocketWithoutCrash`.
- **INV-INFRA-08 [A][BEH]** The broken-part scan collects exactly the worn parts of the linked
  rocket. [T ServiceStationBrokenPartScanContractTest.java] Pinned by `ServiceStationBrokenPartScanContractTest#injectedBrokenPartAppearsInPartsToRepairAfterLink`, `ServiceStationBrokenPartScanContractTest#multipleInjectionsAreAllScanned`.
- **INV-INFRA-09 [T][BEH]** The station's own repair spends nothing on a refusal: no spare that pays ⇒
  `NO_MATERIALS`, energy, reserve and part untouched; no energy ⇒ `NO_CHARGE`, reserve and part
  untouched; nothing worn ⇒ `UNDAMAGED`, nothing spent. *(Was [V], "standalone repair simulates
  material consumption before committing", for the retired recipe path.)* Pinned by
  `RepairWelderTest#everyRefusalOfTheServiceStationIsItsOwnAnswerAndCostsNothing`.
- **INV-INFRA-10 [V]** A part whose block is not `IBrokenPartBlock` is logged and dropped
  from the assembler queue rather than crashing the loop. [V TileRocketServiceStation.java:236-240]

## Failure modes & edge cases

- Assembler in-port overflow on a repaired part is logged (`error`) and the part is lost —
  a `tunable` capacity assumption, not a crash. [V …:251]
- Only `EntityRocket` (not bare `EntityRocketBase`) is repairable; `linkRocket` guards
  `instanceof EntityRocket` before building the list. [V …:587-593]
- `canPerformFunction` clears the block's `STATE` visual when idle, sets it when work
  exists — the assembler queue, an in-flight part, or a part the station's own repair holds.
  [V …:528-544]
- An assembler appearing while the station's own repair works a part takes over: the part keeps
  the stages already taken off, and the banked energy waits for the next own-repair step.

## Integration seams

RF: `TileEntityRFConsumer` (`getPowerPerOperation` 10 with an assembler, 0 without). Caps:
`ITEM_HANDLER` (C5). Packets: `PacketEntity`/`PacketMachine` (C2); `ModuleSync` for the answer.
Depends on `multiblock-machines.TilePrecisionAssembler` (the assembler path) and on
structural-damage's `BayEngine` / `RepairBaySize` (its own repair).

## Config surface

Config: see `C4-config-surface` — the station's own repair is priced by the repair bay's keys
(`repairBayEnergyPerStage`, `repairBayMaxPowerPerTick`, `repairBayHalfRateSize`);
`serviceStationStandaloneRepairMultiplier` was removed 2026-10-06 with the recipe-priced path it scaled.
There is no full-disable path — repair is always possible if a spare or an assembler exists.

## Test coverage

INV-INFRA-06 → ServiceStationUnlinkedPerformFunctionTest:60-66 · INV-INFRA-07 →
RocketServiceStationLinkAndStateTest:83-85 · INV-INFRA-08 →
ServiceStationBrokenPartScanContractTest · MECH-INFRA-09 → ServiceStationFullRepairCycleTest ·
MECH-INFRA-10, INV-INFRA-09 → RepairWelderTest (the service station methods).
*(`WearSystemTest.standaloneRepairResetsMotorWear` — "standalone repair resets the motor to 0 from
recipe materials" — was deleted 2026-10-06 with the contract it pinned.)*
