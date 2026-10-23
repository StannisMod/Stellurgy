---
id: space/00-overview
owns: [space/]
entrypoints: [SpaceSubsystemEvents#onServerTick, SpaceSubsystemEvents#onWorldSave, SpaceEventHandler#onPlayerLoadFromFile, ShipTransitManager#beginTransit, ShipEntryController#requestEntry, DescentController#requestDescent, CellCrossingController#requestCarry, SpaceSubsystem#buildForServer / #onServerStarted / #onServerStopping]
depends-on: [universe (UniverseRegistry, SystemBody), integration/vs + valkyrienskies (VSIntegration), tile (TileAdvancedFlightComputer), network-wire, api-public (ShipCrossingEvent, SpaceCellEvent, StellurgyConfiguration), player (CapabilityPlayerBindings, PlayerRelease), hyperdrive (JumpSpeed)]
depended-by: [hyperdrive (JumpTrigger), navigation, client-render (BoundarySky feed), tile flight computer (carry, descent, entry callers)]
contracts: [C13, C14, C15, C16, C18, C19]
confidence: medium-high   # every file read; the leads in Open questions are by reading, not reproduced
---

## Purpose

The movable-ship space subsystem: how a ship that has left an atmosphere is *somewhere* in a galaxy of
named cells, how few of those cells are live worlds at once, and how a ship and the people aboard cross
between a planet, a cell, a neighbouring cell and the shared hyperspace world — and come back after a
restart. It is the state-and-crossing half of the design in [space-model](../../space-model.md);
`universe/` decides what is *named* where, `integration/vs` + `valkyrienskies/` move the blocks.

## Responsibility boundary

**Owns**: the address value types (`GalacticCoord`, `AbsolutePos`, `BlockDelta`) and the cell/zone
arithmetic (lattice width, seams, spheres); the fixed pool of slot worlds, the cell↔slot refcount/LRU
manager and the cell-store GC; the ship ledger and its durable store; the four crossing controllers
(entry, descent, cell-seam/direct jump, hyperspace transit) and the shared crossing state machine; the
crew/cargo carry across a cut; the per-player aboard record and login restore; the hyperspace world, its
lanes and its void; the server-side sky feed for slot worlds; the space clock.

**Does NOT own**: who is named in which cell and a body's ephemeris (`universe/`); cutting, pasting and
parking a ship (`integration/vs`, `VSIntegration.crossShip`); the flight computer's per-tick drive that
*calls* carry / descent / entry (`tile/TileAdvancedFlightComputer`); drive stats, spool and the jump key
([hyperdrive](../hyperdrive.md); `JumpTrigger` is the only production caller of `beginTransit`,
`JumpTrigger.java:203`); deck motion of an aboard body (`DeckHold`, `ShipFrameTravel`); how the cell sky is
*drawn* ([C14](../../30-contracts/C14-cell-sky-and-bodies.md)); the `PlayerRelease` operation (`player/`).

## Key types

| group | role |
|---|---|
| `SpaceSubsystem` | state object holding the services; wiring, boot/stop lifecycle, zone-membership rule, launch/arrival address resolvers, clock accessor |
| `GalacticCoord`, `AbsolutePos`, `BlockDelta`, `CellFrames`, `CellWorldMapper` | address = zone + sector + local + lattice width; position at a tick; displacement; frame-origin seam; pose↔coordinate map |
| `ZoneScale`, `ReferenceFrames`, `CellSeam` | zone lattice sizing; Laplace sphere of influence; cube/sphere seam arithmetic |
| `SpaceManager`, `SlotBinder` / `PoolSlotBinder` | cell→slot bindings, refcounts, LRU eviction, store GC; the world-lifecycle seam |
| `SpaceSlotPool`, `SlotBindings`, `WorldProviderSpaceSlot` | the registered slot dimensions, what each is bound to, the on-disk folder a slot reads |
| `ShipLedger`, `ShipLedgerData`, `TransitRecord` | durable-ship-id → (coordinate, state); its `WorldSavedData`; one in-flight jump as NBT |
| `ShipTransitManager`, `ShipTransit`, `OfflineProgress`, `VSShipCrosser` | hyperspace jump state machine, scalar flight progress, crew-online gate, production crosser |
| `HyperspaceWorld`, `HyperspaceTiles`, `HyperspaceVoid` | the one shared parking world, its lane allocator, the lethal-void rule |
| `ShipCrossingService`, `VSShipCrossingOps` | shared pin → cut → settle (pose, re-seat, unpark) machine and its production seam |
| `ShipEntryController`, `DescentController`, `CellCrossingController`, `VSDescentPasteResolver` | planet→cell, cell→planet, cell→cell (seam carry / direct jump) |
| `DescentShell`, `StandoffRing`, `TerrainHeightFinder`, `AutoTakeoffPlanner` | atmosphere shell geometry, placement off a body, build-band constant, the take-off column check (the climb is ship-guidance's) |
| `CrewTransfer`, `AboardBodies`, `ShipRelativePoint`, `ArrivalSpawn`, `AssemblyCrewRebind` | carry crew and loose bodies across a cut by flight-computer-relative point |
| `ShipAboardTag`, `AboardRecord`, `LoginRestore`, `SpaceEventHandler` | per-player durable "aboard ship X" record, its one deriver, the pure restore decision, the Forge wiring |
| `SpaceSubsystemEvents`, `SpaceClockSync`, `SystemBodiesProducer`, `SkyNebulaeProducer` | tick/save subscriptions, client clock copy, server-side sky feed |


## Mechanic index

Two sub-docs, cut where the subsystem's own two halves meet — where a ship IS versus what SURVIVES:

- [cells-and-crossings](cells-and-crossings.md) — MECH-SPC-01 address · 02 position at a tick · 03 pose
  realization · 04 zone lattice · 05 sphere/cube membership · 06 slot pool · 07 refcount and eviction · 08 store GC
  · 11 the shared crossing · 12 entry · 13 descent · 14 cell-seam carry and direct jump · 20 sky feed · 22 takeoff
  planner. INV-SPC-01, 02, 04, 06, 10, 15, 16, 17.
- [hyperspace-and-persistence](hyperspace-and-persistence.md) — MECH-SPC-09 ledger · 10 space clock · 15 hyperspace
  transit · 16 a jump across a restart · 17 the hyperspace world and its void · 18 crew and loose-body carry · 19 the
  aboard record and login restore. INV-SPC-03, 05, 07, 08, 09, 11, 12, 13, 14, 18. Holds the State & persistence
  table.

MECH-SPC-21, the subsystem's composition and lifecycle, is below because both halves hang off it.

### MECH-SPC-21 — Composition and lifecycle: one construction site, owned by the mod
`new SpaceSubsystem(binder, clock, config)` is the only way a stack is built; `null` means "what production uses"
(`SpaceSubsystem.java:84-147`) [V]. The mod holds the server's instance and drives `buildForServer` →
`onServerStarted` → `onServerStopping` (`Stellurgy.java:1720,1747,1852,1858`) [V][T]
`SpaceSubsystemOwnershipTest.java:58,71,86`. There is **no enable flag** and no VS condition: the only stand-down is
"already built in this JVM" (`SpaceSubsystem.java:237, 202-204`) [V]. Per-tick order is clock, transit, entry, descent,
cell crossings, crew rebind, sky producer, GC (`SpaceSubsystemEvents.java:76-101`) [V]. `launchBodyAddress` resolves a
launch dimension to the body's address now, else the configured home anchor (`SpaceSubsystem.java:359-380`) [V].

## Integration seams

- **Events** (`api/event`): `ShipCrossingEvent` `LeftCell`/`EnteredCell` (`CellCrossingController.java:372-420`),
  `TransitBegan`/`TransitEnded` with `Route.HYPERSPACE` (`ShipTransitManager.java:1366-1397`) or `Route.DIRECT`,
  `LeftPlanet` (`ShipEntryController.java:303-307`), `EnteredPlanet` (`DescentController.java:254-258`);
  `SpaceCellEvent.CollectPre` (veto, `SpaceManager.java:461-467`).
- **Packets**: `PacketSlotDimSync`, `PacketSpaceClockSync`, `PacketSystemBodiesSync` (its `parentIndex` is produced and
  never read, Open question 3).
- **Forge handlers** registered at `Stellurgy.java:1660-1674` (`SpaceSubsystemEvents`, `SpaceEventHandler`,
  `HyperspaceVoid`). **Mixin**: `MixinEntityCellVoid` (MECH-SPC-03).
- **Callers from outside**: `TileAdvancedFlightComputer` (carry, descent, entry, position report), `JumpTrigger`
  (`beginTransit`), `PlayerRelease` (`holdsCellClaimFor`, `releaseCellClaim`, `releaseQueuedSeating`, `releaseDrift`).
- **Test seams**: `SlotBinder`, `ShipTransitManager.Crosser` / `DirectCrosser` / `ArrivalPlacement`,
  `ShipCrossingService.Ops`, `LoginRestore.Ops`, `CellCrossingController.ZoneMembership`,
  `ShipEntryController.LaunchCoordResolver`, `DescentController.PasteResolver`.

## Config surface

| flag (`StellurgyConfiguration.java:100-110`) | mechanic | notes |
|---|---|---|
| `spaceCellPoolSize` (10, range 1..64, `StellurgyConfiguration.java:975`) | MECH-SPC-06/07 | pool size is the direct performance knob; `tunable` |
| `spaceCellGcPolicy` (`both`), `spaceCellMaxAgeTicks` (1 728 000), `spaceMaxStoredCells` (4096) | MECH-SPC-08 | unknown policy → `BOTH` with a warning (`SpaceSubsystem.java:775-785`); `tunable` |
| `spaceHomeSystemCoord` (`0,0,0`) | MECH-SPC-21 | launch fallback when the dimension has no placement (`SpaceSubsystem.java:377-379`) |
| `spaceTransitOfflineProgress` (`always`) | MECH-SPC-15 | `crew-online` pauses a manned flight with every aboard member offline (`OfflineProgress.java:65-74`) |

**Full-disable path: none, by decision** — `enableSpaceSubsystem` was removed 2026-09-18
(`SpaceSubsystem.java:179-185`).

## Open questions

Each sub-doc keeps its own leads. Doc drift found while mapping (code first, doc second):

- `SpaceSubsystem.java:804-808` and `ServerState.java:88-98` (own counter) vs [space-model](../../space-model.md) lines
  340-342 and 417 (INV-SPACE-01 and the lazy catch-up doctrine say `overworld.getTotalWorldTime()`); and
  space-model line 363 cites `spaceClock` at line 820 of that file, where `SpaceSubsystem.java:804` now has it.
- `CellCrossingController.java:158` vs C15 ADDR-10 naming `CellSeamController`.
- C13 `[T]` for PRES-2/3 (standing) cites `SpaceLoginRestoreClientE2ETest…StoodUp…` (C13 lines 173, 186); that class is
  now `SpaceLoginRestoreSeatedPilotTest` and the method is `@Ignore`d (`SpaceLoginRestoreSeatedPilotTest.java:99`).
  C18's Coverage `[T]` rows for the crew clauses rest on `VSTransitCrewGroupTest`, `@Ignore`d at
  `VSTransitCrewGroupTest.java:56`.
- C18 JUMP-15 vs code: Open question 4.
