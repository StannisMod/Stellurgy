---
id: rocket-assembly
owns: [tile/TileRocketAssemblingMachine.java, tile/TileUnmannedVehicleAssembler.java, util/StorageChunk.java]
entrypoints: [TileRocketAssemblingMachine#performFunction, TileRocketAssemblingMachine#scanRocket, TileRocketAssemblingMachine#assembleRocket, StorageChunk#cutWorldBB, StorageChunk#readFromNetwork]
depends-on: [rocket-entity, dimension-planets, api-public, network-wire, infrastructure-tiles, util-core]
depended-by: [rocket-entity, client-render, multiblock-machines]
contracts: [C1, C2, C3, C4, C5]
confidence: high
---

## Purpose

Turns a player-built block structure sitting on a launch/landing pad into a flyable rocket
entity, and provides the reusable "blob" container (`StorageChunk`) that captures the
structure's blocks/tiles, ships them over the wire, persists them, rebuilds them into a
dummy world for rendering/logic, and pastes them back when the rocket lands. Also runs the
GUI validation loop that tells the player why a structure is not yet a valid rocket.

## Responsibility boundary

Owns: pad-bounds detection, the block scan → `StatsRocket` computation, error-code gating,
the scan/build progress machine, world-cut/paste of the rocket blob, blob NBT + wire
serialization, blob-side wear/breakage/weight helpers, and the unmanned (station-deployed)
assembler variant.

Does NOT own: the `EntityRocket` / `EntityStationDeployedRocket` flight, orbit and
land logic (→ rocket-entity); the `StatsRocket` model and its own NBT keys (→ api-public);
`WeightEngine` mass tables (→ util-core); `TileGuidanceComputer` destination selection
(→ rocket-entity); `TileRocketMonitoringStation` / service-station infra (→
infrastructure-tiles); the packet transport layer (→ network-wire).

## Key types

| class | role |
|-------|------|
| `TileRocketAssemblingMachine` | Pad-based rocket builder tile: RF consumer, GUI, scan/build state machine, infra linking, `RocketLandedEvent` listener |
| `TileRocketAssemblingMachine.ErrorCodes` | Enum of scan verdicts; each maps to a localized GUI message. **Persisted by ordinal.** |
| `TileUnmannedVehicleAssembler` | Subclass building a station-deployed (descending) rocket from a vertical `blockStructureTower` cage; overrides bounds, scan, assemble; requires intake+fluid tank, no guidance |
| `StorageChunk` | `IBlockAccess`+`IStorageChunk`+`IWeighted`+`IBreakable` blob: block/meta arrays + tile list in a `WorldDummy`; capture, NBT, wire, paste, stat/wear helpers |
| `StorageChunk.WornTank` | Value struct (fuel type + worn fraction) for GUI damage view |

## Mechanics

**MECH-RASM-01 — Pad bounds detection.** `getRocketPadBounds` walks continuous
launch/landing-pad blocks in the tile's facing to size the pad, then scans the perimeter
for `blockStructureTower` columns to get height; returns `null` unless
`xSize/zSize ≥ MIN_SIZE(3)` and tower `≥ MIN_SIZE_Y(4)`. Caps at `MAX_SIZE(16)` /
`MAX_SIZE_Y(128)`. UV override uses a vertical tower cage and caps at `17`/`17`.
Trigger: GUI scan/build press, load, land. `TileRocketAssemblingMachine.java:702`,
`TileUnmannedVehicleAssembler.java:43`.

**MECH-RASM-02 — Block scan → StatsRocket.** `scanRocket` first tightens the pad AABB to
non-air, calls `verifyScan` (every column floor is a viable pad block), then per non-air
block accumulates thrust / fuel-use / fuel-capacity per fuel family, engine & seat
locations, drilling power, mass in kg (via `WeightEngine` or `+1` when `advancedWeightSystem`
off), and detects guidance/satellite tiles. Thrust accumulates in `long` and the nuclear derating
runs in `NuclearEngineLimit` (see MECH-RASM-10). Blacklisted non-replaceable blocks set
`invalidBlock` and fire `PacketInvalidLocationNotify`. `TileRocketAssemblingMachine.java:336`.

**MECH-RASM-03 — Error-code gating.** After accumulation, a single if/else ladder picks the
verdict: `INVALIDBLOCK` → `COMBINEDTHRUST` (mixed fuel families) → `NOGUIDANCE` →
`NOENGINES` (no thrust at all, or the launch gate says no — below) → `NOFUEL` (biprop needs both
tanks / insufficient burn distance, the latter for a rocket only — INV-RASM-13) → `SUCCESS`. UV variant replaces the guidance check with
`NOINTAKE` and `NOTANK` gates. `TileRocketAssemblingMachine.java:551`, `TileUnmannedVehicleAssembler.java:379`.
**`NOENGINES` asks the launch gate (C10 STAT-29)**: `getThrust() <= 0 ||
!canLaunchFullFromHere()` (`TileRocketAssemblingMachine.java:652`, `TileUnmannedVehicleAssembler.java:388`),
where `canLaunchFullFromHere()` (`:276-278`) is `StatsRocket#canLaunch` on the scanned craft with its
tanks FULL (`StatsRocket#withTanksFull`) at the gravity of the world the assembler stands in
(`getGravityMultiplier` `:310`). It is not `getNeededThrust()` (`weight × minLaunchTWR`, strict, dry
weight), which would be a second copy of the gate and build crafts the launch then refuses full. The TWR readout
(`getThrustToWeightRatio` `:281-283`) shows the same full-tank, local-gravity ratio.

**MECH-RASM-04 — Scan/build progress machine.** `useNetworkData` id 0 (scan) / id 1 (build)
sets `totalProgress = buildSpeedMultiplier * volume/10`; `performFunction` (an RF op costing
`ENERGYFOROP`) ticks `progress` up to `totalProgress*MAXSCANDELAY`, then runs `scanRocket`
or `assembleRocket` on the server and resets. Energy-starved server pushes a `PacketMachine`
id 2 sync. `TileRocketAssemblingMachine.java:289`, `:924`.

**MECH-RASM-05 — Assemble (world → entity).** `assembleRocket` re-scans, requires
`status==SUCCESS`, normalizes/guards the AABB (`FAIL_CUT` on degenerate), removes
replaceable blacklisted blocks, `StorageChunk.cutWorldBB` to lift the blob, spawns an
`EntityRocket` centred on the tight AABB, syncs via `PacketEntity`, links infrastructure,
sets `FINISHED`, and rescans. UV override spawns `EntityStationDeployedRocket`, sets
`forwardDirection`/`launchDirection=DOWN`, and rotates engine blocks to face forward.
`TileRocketAssemblingMachine.java:636`, `TileUnmannedVehicleAssembler.java:104`.

**MECH-RASM-06 — Blob capture / cut.** `StorageChunk.copyWorldBB` shrinks the box to
non-air, copies block+meta into 3-D arrays, deep-copies each tile via NBT (coords rebased
to blob origin, chisels-and-bits id remapped) into a fresh `WorldDummy`, classifying each as
inventory / fluid tile. `cutWorldBB` = copy + clear source inventories (dupe guard) + set
source to air + kill dropped items in a grown box. `StorageChunk.java:349`, `:441`.

**MECH-RASM-07 — Blob paste (land).** `pasteInWorld` writes every non-air block back at the
target origin, then re-applies each tile's NBT (coords re-offset) to the freshly created
tiles. `StorageChunk.java:754`.

**MECH-RASM-08 — Blob stat recompute.** `StorageChunk.recalculateStats` re-derives the same
thrust/fuel/weight/seat/drill/intake stats as the tile scan, but from inside the blob's own
`WorldDummy`, adds `liquidCapacity` (saturating at `Integer.MAX_VALUE`), applies per-motor
wear thrust penalty, and flags `hasServiceMonitor`. Used by the flying entity, not the tile.
`StorageChunk.java:169`.

**MECH-RASM-10 — Nuclear cluster derating (shared).** Nuclear nozzles cannot be throttled
individually, so a cluster runs only as many as its reactor cores can feed and its working-fluid
draw drops in proportion: `fed = min(nozzleThrust, reactorThrust)`,
`use = floor(useMax · fed / nozzleThrust)`, then thrust is re-derived from the fluid actually spent
as `use · nozzleThrust / useMax` — truncating `use` is what limits a cluster to the nozzles it can
keep FULLY powered. All three scan paths call `NuclearEngineLimit.derive`, which computes in `long`
(thrust is newtons, so a stack of cores runs into tens of millions and `use · nozzleThrust`
overflows `int` inside buildable sizes) and answers all-zero when there are no nozzles or no fluid
draw. It is the one shared copy of what were three divergent ones. `util/NuclearEngineLimit.java`.

**MECH-RASM-09 — Wear / breakage (parts-wear system).** `damageParts` advances every part's
`IPartWear` stage (gated off entirely by `partsWearSystem`); `getBreakingProbability`,
`getWornTanks`, `hasCriticallyWornSeat`, `getWornPartDisplayStacks`, `getBrokenBlocks`
surface wear state for the flight/GUI. `StorageChunk.java:796`, `:909`, `:954`.

**MECH-RASM-14 — Infrastructure linking.** `onLinkComplete` registers an `IInfrastructure`
within 15 blocks into `blockPos` and links any rocket on the pad; `onLoad`, `update`
(retry loop, ≤15 tries), and `onRocketLand` (`@SubscribeEvent`) re-link and mark rockets to
monitoring stations. `TileRocketAssemblingMachine.java:1208`, `:1279`, `:1373`.

**MECH-RASM-11 — Blob rotation.** `rotateBy` remaps block/meta arrays and tile positions for
a facing (used when the SD assembler orients its descending rocket / entity load).
`StorageChunk.java:538`.

**MECH-RASM-12 — Blob wire transport.** `writeToNetwork`/`readFromNetwork` stream
size bytes + per-cell block-id(int)+meta(short) + per-tile compound tags + `hasServiceMonitor`
boolean; `writetiles`/`readtiles` do a tile-only delta update (positions matched, not
list-mutated, to avoid render-thread `ConcurrentModificationException`).
`StorageChunk.java:1133`, `:1178`, `:1061`.

**MECH-RASM-13 — a ship is not gated like a rocket.** A build with an
Advanced Flight Computer skips the tier-1 biprop-tank, COMBINEDTHRUST, NOENGINES and NOFUEL checks
(`TileRocketAssemblingMachine.java:697,721`) `[V]`; its scan surveys the blocks on the pad into a ship
readout (`ship-flight-model`) and sends it to whoever pressed Scan/Build (`:740`) `[V]`. If its
holdable upward thrust is below its weight in the local field, the first assemble WARNS (chat + GUI
line) and remembers the TWR; a press on the same build (same TWR) assembles it (`:827`) `[V]`. A
rocket keeps its hard refusal. e2e owed.

## State & persistence

Assembler-tile keys (`TileRocketAssemblingMachine.writeToNBT` / `readFromNBT`, `:812` / `:850`) and `StorageChunk` blob keys
(`:640` / `:685`, embedded in the `EntityRocket` NBT): see `C1-nbt-persistence` (rocket-assembly). Notes: `status` is
`ErrorCodes.ordinal()` — there is no migration, so reordering the enum silently reassigns saved verdicts, and a missing key
reads 0 = SUCCESS; `pwr`, `tik` and `id` are network-only (id 2/3 sync), never persisted; `scanTotalBlocks` ≤ 0 means idle.
`StorageChunk.mass` is the cached blob mass in kg, re-computed on scan but persisted as a `float` `[A]`; block data is indexed
`z + zSize*y + zSize*ySize*x`, and each entry of `tiles` carries vanilla `x`/`y`/`z` rebased to the blob origin.

## Invariants

- **INV-RASM-01 [V]** `getRocketPadBounds` returns `null` unless both horizontal sizes
  `≥ MIN_SIZE` and the tower `≥ MIN_SIZE_Y`; all downstream scan/build early-outs on
  `bbCache==null` (`canScan`). `TileRocketAssemblingMachine.java:776`, `:920`.
- **INV-RASM-02 [T]** A valid pad structure assembles to a spawned entity with a positive-axis
  storage chunk whose `storageChunkSize == sx*sy*sz`. `RocketAssemblySmokeTest.java:56`.
- **INV-RASM-03 [T]** `assembleRocket` only proceeds when `status==SUCCESS`; a scan verdict of
  anything else aborts the build. `TileRocketAssemblingMachine.java:642`;
  `RocketAssemblySmokeTest.java:33`.
- **INV-RASM-04 [T]** The pad assembler spawns `EntityRocket` (never
  `EntityStationDeployedRocket`); the UV assembler spawns `EntityStationDeployedRocket`.
  `UvAssemblerOutputEntityClassTest.java:81,106`.
- **INV-RASM-05 [V]** UV size caps are `MAX_SIZE=MAX_SIZE_Y=17` and are strictly smaller than
  the pad assembler's `MAX_SIZE_Y=128`, so the two tiles remain distinct machines.
  `TileUnmannedVehicleAssembler.java:33`; `TileRocketAssemblingMachine.java:70`. Unpinned on
  purpose: a relation between two constants executes no decision.
- **INV-RASM-06 [T]** With `rocketRequireFuel=false`, `hasEnoughFuel` returns `true`
  unconditionally so a valid structure still assembles (never collapses to `NOFUEL`).
  `TileRocketAssemblingMachine.java:237`; `RocketRequireFuelDisableAssemblesTest.java:77`.
- **INV-RASM-07 [T]** Wear accrual is single-gated by `partsWearSystem`: system on ⇒
  `damageParts` raises breaking probability > 0; system off ⇒ probability stays 0.
  `StorageChunk.java:796`; `WearAccrualDisableTest.java:76,81`.
- **INV-RASM-08 [V]** Blob NBT read reallocates `blocks`/`metas` from `xSize·ySize·zSize`
  using index `z + zSize*y + zSize*ySize*x`; write uses the identical formula ⇒ round-trip
  stable for a well-formed tag. `StorageChunk.java:666`, `:704`.
- **INV-RASM-09 [V]** `writeToNBT`/`writeToNetwork` no-op on the client
  (`world.isRemote` guard) — the blob is authored only server-side. `StorageChunk.java:642`,
  `:1135`.
- **INV-RASM-10 [V]** `cutWorldBB` empties source inventories and kills dropped items before
  clearing blocks, preventing item duplication on capture. `StorageChunk.java:451`, `:464`.
- **INV-RASM-11 [V]** Biprop engines gate assembly on BOTH bipropellant and oxidizer capacity
  (`NOFUEL` otherwise), but only when `rocketRequireFuel` is on.
  `TileRocketAssemblingMachine.java:544`, `:570`.
- **INV-RASM-12 [A]** The stat ladder assumes the three scan implementations (pad tile, UV
  tile, `StorageChunk.recalculateStats`) stay numerically consistent; nothing enforces it
 . `StorageChunk.java:169`.
- **INV-RASM-13 [T]** The burn-distance `NOFUEL` ("can its tanks carry it to orbit",
  `hasEnoughFuel`) is asked of a ROCKET only — a build with no Advanced Flight Computer. A ship is
  flown, and is a legitimate craft for flights that never leave the planet (maintainer 2026-10-05;
  the climb is the body's own line: 100 000 blocks on Earth). The biprop-tanks
  check (INV-RASM-11) still applies to both. `TileRocketAssemblingMachine#scanRocket`;
  `RocketRequireFuelDisableAssemblesTest.aShipIsNotHeldToTheClimbToOrbitThatARocketIs`.

## Failure modes & edge cases

- `scanRocket` returning `null` on the ALREADY_ASSEMBLED fast path (`:352`) or UV incomplete
  path (`TileUnmannedVehicleAssembler.java:187`) — callers must null-check before build.
- Degenerate/inverted AABB ⇒ `FAIL_CUT`; `cutWorldBB` wrapped in `try/catch(Throwable)` to
  survive `NegativeArraySizeException` (`:658`). UV's catch silently `return`s without a
  status.
- `StorageChunk.isAirBlock` guards only the upper array bound, not negative coordinates
  — a vanilla `IBlockAccess` caller with a negative pos throws.
- Blob tile that throws during write is dropped and its cell zeroed (`:658`, `:1126`) —
  self-healing but silently lossy.
- `getBlockState`/`recalculateStats` loop uses `<= sizeX/Y/Z` (one past the array); bounds are
  masked by `getBlockState` returning AIR out of range (`:190`, `:516`).

- Rocket-stat accumulation (thrust/fuel/mass/seat/drill) is
  triplicated across `TileRocketAssemblingMachine.scanRocket:336`,
  `TileUnmannedVehicleAssembler.scanRocket:182`, and `StorageChunk.recalculateStats:169`;
  they already diverge (UV adds intake/fluid gates; blob adds wear penalty & liquidCapacity),
  so a balance change to one silently desyncs the others.
  **Partly resolved**: the *nuclear derating* leg of the triplication is factored
  into `util/NuclearEngineLimit.derive` (MECH-RASM-10), because newton-scale thrust makes its
  intermediate products overflow `int`. It guards division by
  `workingFluidUseMax` against zero — the shared one
  guards it once. The rest of the accumulation is still triplicated.
- `status` is persisted by `ErrorCodes.ordinal()`
  (`TileRocketAssemblingMachine.java:820`/`:858`) with no migration; reordering/inserting an
  enum constant reassigns every saved verdict, and a missing `status` key reads back as
  ordinal 0 = `SUCCESS` instead of `UNSCANNED`.
- `TileUnmannedVehicleAssembler.assembleRocket` catches a
  cut failure and `return`s without setting `ErrorCodes.FAIL_CUT`
  (`TileUnmannedVehicleAssembler.java:119`), unlike the parent (`:659`), so the GUI shows a
  stale status after a failed UV build.
- `StorageChunk.isAirBlock` (`:817`) checks only
  the upper array bound; a negative `BlockPos` from any `IBlockAccess` consumer throws
  `ArrayIndexOutOfBoundsException`.
- Service-monitor detection uses two different predicates:
  `state.getBlock()==blockServiceMonitor` in `copyWorldBB:399` vs
  `getUnlocalizedName().contains("servicemonitor")` in `recalculateStats:278`.
- `writeToNetwork` gates a debug `println` on
  `DimensionManager.getWorld(0).isRemote` (`:1135`), a hardcoded dim-0 assumption that NPEs
  if the overworld is unloaded.
- Blob `mass` persists as `float` NBT (`:647`); it is a
  derived stat recomputed on scan, so low risk, but it is a `float` in a persisted physics
  field per the wear/free-flight watch-list.

## Integration seams

- **Packets (C2):** sends `PacketInvalidLocationNotify` (own, registered) on blacklisted
  blocks; uses libVulpes `PacketMachine` (ids 0=scan,1=build,2=power/tick sync,3=rocket-id
  link) and `PacketEntity` for entity spawn sync. Tile update via vanilla
  `SPacketUpdateTileEntity` carrying full `writeToNBT`.
- **Events (C5):** `@SubscribeEvent onRocketLand(RocketLandedEvent)` — registered/unregistered
  on the Forge event bus in `onLoad`/`invalidate`/`onChunkUnload`.
- **Capabilities (C5):** consumes `CapabilityWear`/`IPartWear`, `CapabilityItemHandler`,
  `CapabilityFluidHandler` (to classify blob tiles and drive wear).
- **libVulpes seams:** extends `TileEntityRFConsumer`; implements `INetworkMachine`,
  `IModularInventory`, `IProgressBar`, `ILinkableTile`, `IButtonInventory`, `IDataSync`.
- **Registry (C3):** references blocks `blockLaunchpad`, `blockLandingPad`,
  `blockStructureTower`, `blockServiceMonitor`, and the fuel-tank / rocket-motor block
  families for stat classification.

## Config surface

Config: see `C4-config-surface`. The burn-distance check of `hasEnoughFuel` has no `orbit` key: its distance is the
assembler's world's transfer line, `DimensionManager.transferLineOf` (`metric-boundary` MECH-MET-02).

## Test coverage

| invariant | test:line |
|-----------|-----------|
| INV-RASM-02 | `RocketAssemblySmokeTest.java:56` |
| INV-RASM-03 | `RocketAssemblySmokeTest.java:33` |
| INV-RASM-04 | `UvAssemblerOutputEntityClassTest.java:81,106` |
| INV-RASM-05 | — (constants only; see the invariant) |
| INV-RASM-06 | `RocketRequireFuelDisableAssemblesTest.java:77` |
| INV-RASM-07 | `WearAccrualDisableTest.java:76,81` |
| (stat/nbt) | `NuclearEngineRocketAssemblyTest`, `RocketAssemblerMiningDrillStatTest`, `ItemPackedStructureNbtRoundTripTest`, `RocketInfrastructureSmokeTest`, `ServiceStationFullRepairCycleTest` |

## Open questions

- `readtiles` (`:1061`) is a delta update whose commented-out list-clears leave a documented
  render-thread CME hazard — no test pins the concurrent path; is any caller still live?
- `lastRocketID` is written to NBT via `writeDataToNetwork` id 3 but never persisted nor read
  back on the tile side beyond the transient sync — intended lifetime unclear.
