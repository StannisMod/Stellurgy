# infrastructure-tiles / monitoring station

`TileRocketMonitoringStation` (1237 sloc) — a multiblock-mountable, cross-dimension
monitor that mirrors a linked rocket's height/velocity/fuel, drives a launch button,
tracks an authoritative launch-status FSM off the Forge `RocketEvent` bus, exposes a
comparator override, and renders a two-tab GUI (status + mission). Also the only tile here
with `disconnectOnLiftOff()==false` (it follows the rocket to orbit).

## Key types

| class | role |
|-------|------|
| TileRocketMonitoringStation | server snapshots + status FSM; client GUI cache; IMultiblock slave |

## Mechanics

- **MECH-INFRA-01 — Launch trigger.** Rising redstone edge on a linked monitor calls
  `linkedRocket.prepareLaunch()`; the GUI launch button sends `PacketMachine` id 100 which
  re-primes stats and also calls `prepareLaunch`. Edge is detected from a cached power
  state, not polled every tick. [V TileRocketMonitoringStation.java:296-310, 784-798]
- **MECH-INFRA-02 — Snapshot throttling.** Server samples height/velocity every
  `T_HEIGHTVEL_TICKS` and fuel/oxidizer every `T_FUEL_TICKS` (both `tunable`) into
  `snap*` fields; clients read them via `ModuleProgress` polling (`getProgress` ids
  0/1/2/6). `snapVel = motionY*100` (int). [V TileRocketMonitoringStation.java:477-513]
- **MECH-INFRA-03 — Launch-status FSM.** `uiStatus` ∈ {0 idle,1 prelaunch,2 launching,
  3 orbit,4 deorbiting,5 landed,6 aborted} is written by `@SubscribeEvent` handlers on
  `RocketEvent.{RocketPreLaunch,RocketLaunch,RocketReachesOrbit,RocketDeOrbiting,
  RocketLanded,RocketAbort}` and pushed to clients via TE update. Stale statuses
  (`STATUS_STALE_TICKS`, tunable) time out in `update`. [V TileRocketMonitoringStation.java:517-577, 455-465]
- **MECH-INFRA-04 — Event-bus registration gated on link.** The tile registers on
  `MinecraftForge.EVENT_BUS` only while it holds a `linkedRocket`, and unregisters on
  `invalidate`/`onChunkUnload`; `registeredBus` guards double-register.
  [V TileRocketMonitoringStation.java:216-285, 371-374]
- **MECH-INFRA-05 — Assembler claim window.** When the monitor has a master
  (assembler), `linkRocket` accepts a rocket only if it is already the linked one or the
  assembler recently called `markRocketFromAssembler` (claim id + ~40-tick expiry); an
  unmanned-vehicle assembler master additionally restricts to
  `EntityStationDeployedRocket`. Rejected rockets are removed from their own infra list.
  [V TileRocketMonitoringStation.java:69-73, 134-159, 327-416]
- **MECH-INFRA-06 — Comparator override.** Comparator level = `15 *
  getRelativeHeightFraction()` of the linked `EntityRocket`, updated change-only every
  `T_COMPARATOR_TICKS` and on prime; `getComparatorOverride` also serves it live.
  [V TileRocketMonitoringStation.java:485-493, 1230-1236]
- **MECH-INFRA-07 — Mission tab + deferred resolve.** GUI tab 1 shows the linked
  `IMission` (gas vs ore) and a live countdown. A mission id can arrive (TE update)
  before the satellite object exists client-side; `pendingMissionId` is retried
  (client, every ~10 ticks while tab 1 is open) until `DimensionManager.getSatellite`
  resolves it. `linkMission` bumps `uiStatus` to 3 if below. [V TileRocketMonitoringStation.java:644-745, 1204-1217]

## State & persistence

NBT keys: see `C1-nbt-persistence` (monitoring-station). Notes: `missionID` is written only if a mission is linked (absent ⇒ mission
unchanged); `masterX/Y/Z` only if `hasMaster()` (the `masterY>-1` gate); `abortReason` is an i18n key `key|arg|arg` (absent ⇒ "");
`was_powered` shares its key name with the service station; `tab`, `id` and `state` are wire-only (packet `TAB_SWITCH`=10,
`readDataFromNetwork`), never persisted.

## Invariants

- **INV-INFRA-01 [A][BEH]** A freshly-placed, unlinked monitor reports `wasPowered=false` and
  `getComparatorOverride()==0`. [T MonitoringStationComparatorOverrideTest.java:73-77] Pinned by `MonitoringStationComparatorOverrideTest#unlinkedMonitorReportsZeroComparatorOverride`.
- **INV-INFRA-02 [A][BEH]** `monitor-info` exposes `wasPowered`/`equivalentPower`, and a
  redstone rising edge on a linked monitor triggers a launch attempt.
  [T RocketMonitoringStationLaunchTriggerTest.java:115-123] Pinned by `RocketMonitoringStationLaunchTriggerTest#risingRedstoneEdgeFiresPrepareLaunchExactlyOnce_andSustainedDoesNotRefire`, `RocketMonitoringStationLaunchTriggerTest#fallingRedstoneEdgeResetsTheGate_andSecondRisingEdgeRefires`.
- **INV-INFRA-03 [V]** The tile only touches the Forge event bus while a rocket is
  linked; no linked rocket ⇒ no subscription. [V TileRocketMonitoringStation.java:220-224]
- **INV-INFRA-04 [V][BEH]** `linkRocket` never accepts a foreign rocket while the monitor is
  owned by an assembler without a fresh claim or prior ownership.
  [V TileRocketMonitoringStation.java:335-364]
- **INV-INFRA-05 [A]** `snapVel` and `snapHeight` are ints; no float reaches persisted
  physics here. (No `setFloat` in this file.) [A TileRocketMonitoringStation.java:481-482]

## Failure modes & edge cases

- `onChunkUnload` unregisters the bus but keeps `linkedRocket`, so the monitor can follow
  a rocket across dimensions/reload (why `getMaxLinkDistance()` is 300000).
- `getProgress(id==0)` returns `getTotalProgress` when a mission exists — a deliberately
  preserved "oddity" (bar reads full during a mission). [V …:1163]
- Abort reason is a `key|args` string translated **client-side**; unknown keys fall back
  to the raw string. [V …:1021-1052]

## Integration seams

- Forge bus: `RocketEvent.*` (C5). Packets: libVulpes `PacketMachine` (ids 1, 2,
  `TAB_SWITCH`=10, 100) and `PacketHandler` (C2). Interfaces: `IInfrastructure`,
  `ILinkableTile`, `IComparatorOverride`, `IMultiblock`, `IProgressBar`.

## Config surface

The height-bar total (`getTotalProgress(0)`) is the launch world's transfer line,
`DimensionManager.transferLineOf` (the body's orbit line, or the station clearance in the station
dimension — `metric-boundary` MECH-MET-02; there is no global `orbit` key).
Disabling nothing here — it is a scale only. FSM/throttle constants are `tunable`.

## Test coverage

INV-INFRA-01 → MonitoringStationComparatorOverrideTest:73-77 · INV-INFRA-02 →
RocketMonitoringStationLaunchTriggerTest:115-123 · link/mission lifecycle →
MissionInfrastructureLifecycleTest, RocketInfrastructureSmokeTest.

## Open questions

- `readDataFromNetwork` packetId==2 → NBT `state`, but `useNetworkData` id==2 is an empty
  branch (commented-out redstone control): a dead wire path. (unconfirmed).
