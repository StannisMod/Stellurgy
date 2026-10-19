---
id: valkyrienskies
owns: [valkyrienskies:org/valkyrienskies/]
entrypoints: [WorldServerShipManager#tick, VSWorldPhysicsLoop#run, EventsCommon#onWorldTickEvent, WorldServerShipManager#queueShipSpawn, MoveBlocks#copyBlockToPos, QueryableShipData]
depends-on: [space-model (WorldRuntime, AtmosphereDensity, FreeFlightPhysics), structural-damage (DamageState carry), api-public (Constants.modId), mixins-asm-coremod]
depended-by: [integration-vs, hyperdrive, space-model, crew, ship-combat, client-render]
contracts: [C11, C19, C1]
confidence: medium-high — lifecycle, threading and relocation read line by line [V]; collision, water, rendering and the command tree are named, not mapped
---

> **Citation convention.** `VS/` = `valkyrienskies/src/main/java/org/valkyrienskies/`. Every other path
> is relative to the repo root. Tags: `[V]` code read · `[T]` a test in `src/test/**` asserts it ·
> `[A]` inferred. Lines checked mechanically after the last edit.

## Purpose

The rigid-body engine under every Stellurgy ship. A ship is a block set cut out of a world, pasted
into a far-away **shipyard** of ordinary chunks, and posed in the world by a transform that a
per-world **physics thread** integrates. Stellurgy compiles this tree as part of its own jar and
edits it in place; this doc is the owner of the tree, and `integration-vs.md` owns Stellurgy's port onto it.

## Responsibility boundary

**Owns**: ship lifecycle (spawn, load, unload, destroy), the shipyard and its chunk claims, the
physics tick and its thread, ship↔world and entity↔ship collision, entity carrying, pose broadcast,
ship persistence, block relocation, the control-seat blocks (captain / passenger / boat chair, water pump).

**Does NOT own**: how Stellurgy resolves, names or commands a ship (`integration-vs.md`, C11 — it is the
only code allowed to import this package, [C11](../30-contracts/C11-physics-substrate-port.md)
PORT-1); what happens to a ship in space (cells, crossings — `space-model`, [space](./space/00-overview.md));
what damage a block holds (carried here, owned by structural damage); the `valkyrienwarfare.api`
interfaces (`TransformType`, `IPhysicsEntity`) — those come from a binary jar, not this tree
(`gradle/scripts/dependencies.gradle:146`) [V].

## Key types

| type | role |
|---|---|
| `ValkyrienSkiesMod` | not an `@Mod`; Stellurgy drives its pre-init/init and owns its network channels, blocks and physics pool |
| `ship_world/WorldServerShipManager` | per-world: queues, loaded-ship map, the game-thread tick; starts the physics thread |
| `ship_world/WorldClientShipManager` | client mirror: loads/unloads what the server announces, ticks interpolation |
| `ship_world/WorldShipLoadingController` | which ships a world loads, and which players watch which ship |
| `ship_world/PhysicsObject` | one LOADED ship: transforms, claimed-chunk cache, physics controllers, destroy |
| `ShipData` / `QueryableShipData` | the persisted record of a ship / the per-world indexed registry of records |
| `ship_world/VSWorldData` | what a world saves: the registry plus the chunk-claim allocator |
| `chunk_claims/ShipChunkAllocator`, `VSChunkClaim` | where the shipyard is, and the chunks a ship owns |
| `ship_transform/ShipTransform`, `ShipTransformationManager` | the immutable pose, and the tick / physics / render poses of one ship |
| `physics/PhysicsCalculations` | the rigid-body integrator for one ship (forces, torque, integration) |
| `util/multithreaded/VSWorldPhysicsLoop` | the per-world physics thread and its step order |
| `block_relocation/MoveBlocks`, `SpatialDetector` | block/tile copy between frames; flood-fill of the assembling structure |
| `collision/WorldPhysicsCollider` + `entity_interaction/EntityCollisionInjector` | ship-vs-terrain on the physics thread; entity-vs-ship on the game thread |
| `entity_interaction/EntityDraggable` | carries entities with the hull they stand on |
| `EventsCommon` | Forge-bus glue: manager creation, the world-tick drive, pose send, shipyard guards |
| `mixin/**` (`mixins.valkyrienskies.json`) | the vanilla seams: `World`, `Chunk`, `Entity`, `ChunkProviderServer`, `WorldBorder`, net handlers, rendering |

## Mechanics

### MECH-VSK-01 — Hosted, not a mod
There is no `valkyrienskies` mod container: Stellurgy's lifecycle handlers call this class's pre-init,
init, post-init and server-start; its `@EventBusSubscriber` handlers and `@SidedProxy` are owned by
Stellurgy's modid [V] `VS/mod/common/ValkyrienSkiesMod.java:53-57`, `:96-97`, `:140-143`, `:191-200`. Its
mixin config rides in beside Stellurgy's [V] `src/main/java/dev/stannismod/stellurgy/asm/StellurgyPlugin.java:37`,
`required: true` [V] `src/main/resources/mixins.valkyrienskies.json:2`. The pre-init and init each throw on a
second call [V] `ValkyrienSkiesMod.java:141-143`, `:194-196`. Three network channels (`valkyrien_skies`,
`valkyrien_piloting`, `vs_ship_transforms`) are created in pre-init [V] `:211-221`. Registry domain is split:
tile entities register as `valkyrienskies:…`, blocks and items as the host's `stellurgy:…`, while assets sit
under `assets/valkyrienskies/` — so VS blocks have no model at the domain the client looks in [V] `:241-246`,
[T] `src/test/java/dev/stannismod/stellurgy/test/unit/VendoredAssetDomainTest.java:87` (pins the *wrong* state; fixing the bug turns it red).

### MECH-VSK-02 — A ship is two stores
A **record** (`ShipData`: uuid, name, pose, AABB, velocities, inertia, claim, block set, force positions,
Stellurgy's durable id) lives in the per-world registry; the **blocks and tile entities** live as ordinary
chunks at shipyard coordinates, saved in the world's region files. The record is saved by a world
capability; the chunks by vanilla [V] `VS/mod/common/ships/ShipData.java:35-82`, `VS/mod/common/capability/VSCapabilityRegistry.java:32-36`,
`VS/mod/common/ships/chunk_claims/ClaimedChunkCacheController.java:104`. A **physics object** (`PhysicsObject`)
exists only while a ship is loaded; destroying a record never needs one (MECH-VSK-07).

### MECH-VSK-03 — The shipyard and the claim
Ship N's claim is centred at chunk `(CHUNK_X_START, 3200·N)` — one fixed strip in +Z. A claim given back by a
ship that no longer exists (`WorldServerShipManager#removeRecord`, the one place every removal of a ship record
goes through — it also announces the lifecycle event — and `refuseSpawn` for a spawn that never held anything)
is handed out again, lowest first, before the cursor
advances; the cursor and the given-back set are both saved with the world (`ShipChunkAllocator#releasedClaimZ`,
`#allocateNextChunkClaim`, `#releaseChunkClaim`). A spawn unloads any chunk still loaded where it injects its
own, so nothing of the previous holder survives in a reused claim (`WorldServerShipManager#injectChunkIntoWorldServer`).
[T] `VSShipAssemblyExtentTest#aCollectedShipsClaimIsTheNextShipsClaim`. A claim
is 17×17 chunks: `VSChunkClaim.RADIUS` is DERIVED from the detection reach (`SpatialDetector.MAX_REACH` = 128
blocks along X/Z from the anchor, which lands on the centre chunk's first block, so radius 8), and the three
places that ask read that one constant [V] `VS/mod/common/ships/chunk_claims/VSChunkClaim.java:32`, `:75`,
`VS/mod/common/ships/ship_world/WorldServerShipManager.java:205`, `VS/mod/common/ships/chunk_claims/ClaimedChunkCacheController.java:45`,
`VS/mod/common/ships/block_relocation/SpatialDetector.java:29`. "Is this chunk shipyard" is a
**half-plane** (`x ≥ CHUNK_X_START − 1599 ∧ z ≥ −1599`), not the claimed strip [V] `ShipChunkAllocator.java:58-60`.
Three vanilla seams honour that predicate: the world border lets every shipyard position through, terrain
population is cancelled there, and a player teleport into it is redirected to the ship's world position or cancelled [V]
`VS/mixin/world/border/MixinWorldBorder.java:17-22`, `VS/mixin/world/chunk/MixinChunk.java:75-81`,
`VS/mixin/network/MixinNetHandlerPlayServer.java:61-86`. `CHUNK_X_START` was raised for Stellurgy's cell geometry
(its javadoc pairs it with `GalacticCoord.CELL`) and the saved cursor does not follow a change [V] `ShipChunkAllocator.java:26-46`.

### MECH-VSK-04 — Assembly is a queued, game-thread cut-and-paste
`queueShipSpawn(record, pos, finder)` only queues [V] `WorldServerShipManager.java:536-539`. The queue is drained by
`spawnNewShips()` inside the manager tick, on the game thread [V] `WorldServerShipManager.java:167`, `WorldServerShipManager.java:182-377`.
For each entry: drop this uuid's own blockless remnant, throw if a *loaded* holder of the uuid remains
[V] `WorldServerShipManager.java:187-190`; flood-fill from the infuser, bounded by the queued spawn's FOOTPRINT box (a block outside it is not part of the craft:
it neither joins the result, counts toward the size cap, nor counts as the structure continuing), which stops at `MAX_REACH` and
flags a structure that continues past it rather than truncating it [V] `SpatialDetector.java:45-60`, `:207-215`; refuse when over
`maxDetectedShipSize` (`TOO_LARGE`), touching bedrock (`BEDROCK`), or past the reach (`REACH_EXCEEDED`) — one decision,
`WorldServerShipManager#refusalOf`, which `#refusalFor(pos, finderType, footprint)` asks ahead of the queue on the world as it
stands; a refused queued spawn is logged with its reason by `refuseSpawn`, gives its claim back and leaves every block
standing [V] `WorldServerShipManager.java:326-333`, `:549`, `:870-909`;
give the record its claim [V] `WorldServerShipManager.java:204-210`; copy each block and tile entity into fresh `Chunk` objects at
`src + offset`, where the offset puts the infuser on the claim centre in X/Z and on y = 128 unless the hull would leave 0..255 there,
in which case it is moved the least that keeps the hull inside — and the start pose maps the infuser to wherever that put it
[V] `WorldServerShipManager.java:216-222`, `:431-450`; carrying its damage record and updating mass/inertia [V] `WorldServerShipManager.java:233-302`; sweep
the damage records of the holes between blocks, within a margin of 8 blocks around the found blocks [V] `WorldServerShipManager.java:42`,
`WorldServerShipManager.java:308-314`; delete the sources and relight [V] `WorldServerShipManager.java:319-360`; inject the new chunks
straight into the chunk provider's map [V] `WorldServerShipManager.java:361-369`, `WorldServerShipManager.java:416-422`; **only then**
`addShip`, then build the `PhysicsObject` [V] `WorldServerShipManager.java:371-374`. A tile entity implementing `IRelocationAwareTile`
builds its own copy; any other is NBT-copied with rewritten x/y/z [V] `WorldServerShipManager.java:289-298`,
`VS/mod/common/ships/block_relocation/IRelocationAwareTile.java:7-25`. `ValkyrienUtils.createNewShip` mints the record,
allocating the claim at creation, uuid caller-chosen or random [V] `VS/mod/common/util/ValkyrienUtils.java:164-190`.

### MECH-VSK-05 — The manager tick: one fixed order, driven by the world tick
`WorldTickEvent` END calls `physObjectWorld.tick()`, then the pose send, then entity carrying [V]
`VS/mod/common/EventsCommon.java:247-251`. Order inside the tick [V] `WorldServerShipManager.java:101-180`:
(1) destroy loaded ships that want it; (2) collect unloaded records that are dead or blockless; (3) drain the spawn
queue; (4) decide load/unload by player distance; (5) drain load/unload queues; (6) tick every loaded ship;
(7) send watcher updates; (8) publish the thread-safe ship list. The client has its own, shorter tick, run from the
client tick event [V] `VS/mod/client/EventsClient.java:100`, `VS/mod/common/ships/ship_world/WorldClientShipManager.java:44-52`.
The managers are created on `WorldEvent.Load` through a mixin-added field on `World` [V] `EventsCommon.java:282-292`,
`VS/mixin/world/MixinWorld.java:481-492`.

### MECH-VSK-06 — Load and unload follow the nearest player
A ship with no physics object is queued for an **immediate** load if a player is within `loadDistance`, else for a
**background** load within `loadBackgroundDistance` (or always when `permanentlyLoaded`); a loaded ship is queued to unload
beyond `unloadDistance` [V] `VS/mod/common/ships/ship_world/WorldShipLoadingController.java:36-56`. Immediate load builds the
`PhysicsObject`, whose constructor synchronously loads all 289 claim chunks (17×17) on the game thread [V]
`WorldServerShipManager.java:450`, `ClaimedChunkCacheController.java:96-129`. Background load only asks the chunk provider for the
claim's chunks, and a chunk-provider mixin keeps them out of the drop set [V] `WorldServerShipManager.java:485-493`, `VS/mixin/world/gen/MixinChunkProviderServer.java:27-32`.
A request already satisfied — load of a loaded ship, unload of an unloaded one — is a no-op on the server [V] `WorldServerShipManager.java:440-443`,
`WorldServerShipManager.java:472-474`, `WorldServerShipManager.java:504-506`,
[T] `src/test/java/dev/stannismod/stellurgy/test/server/SpaceSlotVsShipPersistTest.java:113`; on the client it logs an error and continues [V] `WorldClientShipManager.java:59-62`, `WorldClientShipManager.java:85-88`.
The distances are config (`tunable`); their defaults are ordered watch < load < unwatch < unload [V] `VS/mod/common/config/VSConfig.java:154-174`.

### MECH-VSK-07 — Disposal: dead means discard, empty means collect
A loaded ship is destroyed when its record is **dead**, its block set is empty, or a deconstruct state asks (aligned within 2° unless
immediate) [V] `VS/mod/common/ships/ship_world/PhysicsObject.java:229-251`. `destroyShip` tells watchers to unload its chunks, **copies the blocks
back into the world only if the ship is neither dead nor empty and the state says copy**, removes the shipyard tile entities and deletes the
claimed chunks outright [V] `:253-315`. Unloaded records get the same judgement in a second sweep, because the first only walks loaded ships;
a record that is in use (loaded, queued, or streaming) is skipped, and a null block set is "does not say", never "empty" [V]
`WorldServerShipManager.java:114-165`, `:553-559`. `markDead` is transient and never un-set [V] `VS/mod/common/ships/ShipData.java:99`, `:297-299`.
A crossing that cuts a ship out and re-assembles it leaves exactly one ship [T]
`src/test/java/dev/stannismod/stellurgy/test/server/VSCrossingLeavesNoShipBehindTest.java:78`.

### MECH-VSK-08 — The physics thread
Each server world starts one thread (never marked daemon), "VS World Physics Task dim N", in the manager's constructor; the loop runs a step, sleeps up to
`1/targetTps` (default 60 Hz), and idles at 100 ms when the server is down or a single-player game is paused [V]
`WorldServerShipManager.java:56-69`, `VS/mod/common/util/multithreaded/VSWorldPhysicsLoop.java:43`, `:76-122`. It dies by a flag the world-unload
event sets, after the current step [V] `:212-215`, `EventsCommon.java:294-298`. **It never sees a ship the game thread has not published**:
each step reads the immutable list the manager built at the end of its last tick [V] `VSWorldPhysicsLoop.java:126`,
`WorldServerShipManager.java:179`. Ships step only after 20 existing ticks, with physics enabled and a surrounding chunk cache [V]
`VSWorldPhysicsLoop.java:133-137`, `PhysicsObject.java:51`, `:441-443`. Step order [V] `VSWorldPhysicsLoop.java:124-157`, `:162-206`: recurring and queued tasks;
per ship, `rawPhysTickPreCol` (forces), water and terrain collision caches; the collision and water tasks fan out on the pool
(`threadCount` threads, built in pre-init); results are applied sequentially; `rawPhysTickPostCol` integrates. A step that produces
velocity² > 50 000 or a non-finite value freezes the ship and clears its velocities [V] `VS/mod/common/physics/PhysicsCalculations.java:99-109`, `:131-137`.
Per-ship exceptions are caught and printed, never rethrown [V] `VSWorldPhysicsLoop.java:177-179`, `:202-204`.

### MECH-VSK-09 — Forces: block controllers and Stellurgy's environment
Each step, in priority order, every registered `IPhysicsBlockController` gets `onPhysicsTick(ship, calc, dt)` — **on the physics thread**; this is
the seam Stellurgy's flight computer uses (the Stellurgy side is `integration-vs.md`) [V] `PhysicsCalculations.java:182-188`,
`VS/mod/common/physics/IPhysicsBlockController.java:15-16`. Controllers are keyed by position: a tile re-created by a chunk reload replaces
its stale predecessor instead of joining it [V] `PhysicsObject.java:209-214`, `VS/mixin/world/chunk/MixinChunk.java:45-56`. Gravity, force- and
torque-providing blocks follow [V] `PhysicsCalculations.java:175-238`. Two Stellurgy hooks sit inside the integrator: drag is
`FreeFlightPhysics.ambientDragFactor(AtmosphereDensity.inAtmospheres(world), dt)` — exactly 1.0 (no damping) in vacuum — and position Y is clamped to a
per-world band `[min(configLower, declaredFloor), max(configUpper, declaredCeiling)]` that Stellurgy may only widen [V] `:294-304`, `:364-372`,
`VS/mod/common/physics/ShipAltitudeBand.java:21-47`. Block mass/inertia are updated by every block change on a shipyard chunk
(MECH-VSK-11).

### MECH-VSK-10 — Frames: subspace, world, and three poses
A ship block's `BlockPos` is a **subspace** address (the shipyard); entities and everything the player sees are **world**. `ShipTransform` is an
immutable `T(pos)·R·T(−centre-of-mass)` with its inverse, applied with `SUBSPACE_TO_GLOBAL` / `GLOBAL_TO_SUBSPACE` [V]
`VS/mod/common/ships/ship_transform/ShipTransform.java:26-27`, `:59-65`. `ShipTransformationManager` holds three poses: **tick** (what the
game thread and entities use), **physics** (what the physics thread integrates; the one cross-thread field, volatile) and **render**
(client, slerped per frame) [V] `ShipTransformationManager.java:19-32`, `:163-168`, `:194-218`. Client poses are advanced from declared
velocity, not filtered toward the last packet [V] `PhysicsObject.java:138`. An entity that joins the world while standing in subspace is
transformed to world frame on join [V] `EventsCommon.java:94-119`. A ship block's position is in subspace,
an entity's in the world frame; the reference-frame design above ship level is C19.

### MECH-VSK-11 — Block edits on a ship are tracked at the chunk
A `setBlockState` on a shipyard chunk (server only) updates the record's block set, force positions and mass/inertia before the write; a
position outside the claim is an exception [V] `VS/mixin/world/chunk/MixinChunk.java:62-71`,
`VS/mod/common/ships/ShipDataMethods.java:20-38`. The ship's world AABB is recomputed from the block set each tick (server) [V]
`ShipTransformationManager.java:138-151`. Tile entities added to a shipyard chunk are registered as controllers [V] `MixinChunk.java:45-49`.

### MECH-VSK-12 — Block relocation back to the world
`MoveBlocks.copyBlockToPos` writes the block into the target chunk's storage directly (no neighbour updates, clients notified with flag 2), registers force
blocks, carries the position-keyed damage record, then copies the tile entity (relocation-aware or NBT) [V]
`VS/mod/common/ships/block_relocation/MoveBlocks.java:23-91`. It is the copy-back used by deconstruction (MECH-VSK-07); the *assembly* paste is
the inline loop of MECH-VSK-04, which has its own copy of the same steps. A per-ship **crossing** (cut here, paste elsewhere, re-assemble under the
same uuid) is composed by Stellurgy from these pieces (`integration-vs`); a damaged hull keeps its damage through it [T]
`src/test/java/dev/stannismod/stellurgy/test/server/ShipDamageSurvivesRelocationTest.java:93`.

### MECH-VSK-13 — Entities and ships: collision and carrying (game thread)
Entity movement is intercepted: `move` is re-issued with a ship-adjusted delta and the ship touched is remembered on the entity; world queries
(`getCollisionBoxes`, entity-in-AABB, ray-trace) are widened to ship space by `World` mixins [V] `VS/mixin/entity/MixinEntityIntrinsic.java:47-72`,
`VS/mixin/world/MixinWorld.java:171`, `:281`, `:306`, `:374`. Which ships an entity may touch is the ships intersecting its box grown by 1 [V]
`VS/mod/common/ships/entity_interaction/EntityCollisionInjector.java:377-380`. After the manager tick every loaded entity is offered the velocity of
the hull it last touched, **if** the touch timer has not run out (`ticksToStickToShip`, 20) **and** the body is still within 1 block of that hull's box,
and **not** within 50 ticks of that ship being teleported [V] `VS/mod/common/ships/entity_interaction/EntityDraggable.java:73-77`, `:118-122`,
`VS/mod/common/util/ValkyrienUtils.java:58`, `:325-327`, `VS/mod/common/ships/ship_world/PhysicsObject.java:53`. The "at the hull" boundary counts a body standing on the
top face [T] `src/test/java/dev/stannismod/stellurgy/test/unit/StandingOnAHullCountsAsBeingAtItTest.java:47`. The ship-vs-terrain
collider (physics thread) samples a cached `ChunkCache` of the world around the hull, not live chunks [V] `VS/mod/common/ships/chunk_claims/SurroundingChunkCacheController.java:17-43`,
`VS/mod/common/collision/WorldPhysicsCollider.java:274`.

### MECH-VSK-14 — Pose broadcast: the game tick sends, the physics thread backstops
Once per game tick, per world, each player gets ONE message carrying the pose, AABB and both velocities of the ships **that player watches** [V]
`EventsCommon.java:180-228`. If no send has happened for 60 ms (the tick has stalled while physics runs) the physics thread sends itself [V] `:140-149`,
`VSWorldPhysicsLoop.java:153-156`. The client drops a pose packet addressed to another dimension [V] `VS/mod/common/network/ShipTransformUpdateMessageHandler.java:36`,
and stores the told velocities where its consumers look [V] `:57-70`. Watchers are recomputed each manager tick by distance
(`watchDistance` in, `unwatchDistance` out); new watchers are sent the ship's chunks and a load id, leavers an unload id [V]
`WorldShipLoadingController.java:61-186`.

### MECH-VSK-15 — Persistence: CBOR in a world capability
`VSWorldData` (registry + allocator) is a world capability, written as a CBOR byte array by Jackson over **fields** (getters and setters ignored; `transient` fields
dropped by Jackson's default `[A]`, so `dead` and the registry back-pointer are not saved) [V] `VS/mod/common/ships/ship_world/VSWorldData.java:7-9`,
`VS/mod/common/capability/framework/VSDefaultCapability.java:90-101`, `VS/mod/common/util/jackson/VSJacksonUtil.java:60-67`, `ShipData.java:99`. A round trip through
a Stellurgy slot unload/reload keeps the registry [T] `SpaceSlotVsShipPersistTest.java:65`. A failed read installs an **empty** `VSWorldData` and logs at FATAL; a failed
write stores an empty array [V] `VSDefaultCapability.java:96-98`, `:108-117`. The block set and force positions are not sent to clients (`@PacketIgnore`) [V] `ShipData.java:51-63`.

### MECH-VSK-16 — Identity added by Stellurgy
`ShipData` carries Stellurgy's durable ship id beside the uuid, indexed (hash, nullable) so id→ship is one probe; two ships claiming one id is logged and answers empty [V]
`ShipData.java:100-117`, `:174-178`, `VS/mod/common/ships/QueryableShipData.java:65-71`, `:142-158`. `setStellurgyDurableIdBeforeRegistration` exists because the indexed setter
would register an unspawned ship [V] `ShipData.java:189-192`. A uuid may be re-used when a ship crosses worlds (MECH-VSK-04's remnant rule).

## State & persistence

| state | owner | where it is saved |
|---|---|---|
| `ShipData` records, claim cursor | `VSWorldData` (world capability `valkyrienskies:world_data_capability`) | CBOR byte array in the world's capability NBT `[A]` that Forge writes it with the world data |
| ship blocks, tile entities | ordinary chunks at shipyard coordinates | region files of the same world |
| loaded set, queues, watch map | `WorldServerShipManager`, `WorldShipLoadingController` | never saved |
| pose/velocity of a live ship | `PhysicsCalculations` → `ShipData` | saved through the record |
| entity "last ship touched" and added velocity | a field mixed into `Entity` (`IDraggable`) | not saved |
| altitude band, pose-send clock, last-player-positions | `WorldRuntime` parts of the world | never saved; dies with the world object |

## Invariants

- **INV-VSK-01** [V] Every registry/manager entry point except `getAllLoadedThreadSafe` throws `CalledFromWrongThreadException` off the game thread
  (`WorldServerShipManager.java:71-75`, `:83-86`, `:529-531`). Physics-thread code asks the snapshot list or
  `getPhysoManagingBlockThreadSafe` (`VS/mod/common/util/ValkyrienUtils.java:73-80`).
- **INV-VSK-02** [V] The physics thread learns of a ship, and of its destruction, only through the snapshot published at the END of the manager tick (`WorldServerShipManager.java:179`);
  the pose it hands back is an immutable `ShipTransform` in a volatile field (`ShipTransformationManager.java:31`). Everything else crossing the threads is
  unsynchronised (see Failure modes).
- **INV-VSK-03** [V] A spawn is drained on the game thread inside the world tick (`EventsCommon.java:248` → `WorldServerShipManager.java:167`); the physics thread never
  assembles, loads or destroys.
- **INV-VSK-04** [V] Every id in the load/unload/background queues must resolve in the registry or the world tick throws, uncaught
  (`WorldServerShipManager.java:429-431`, `:477-479`, `:589-592`). Anything deregistering a ship must ask `isShipInUse`, not `getPhysObjectFromUUID` (`:553-559`).
- **INV-VSK-05** [V] A chunk belongs to at most one ship; two claimants throw (`QueryableShipData.java:107-109`). A uuid may be held by one ship that owns blocks; a
  blockless holder is a remnant and is dropped at spawn (`WorldServerShipManager.java:187-190`, `:399-414`).
- **INV-VSK-06** [V] A dead ship's blocks are never copied back; they are deleted with its chunks (`PhysicsObject.java:280-315`).
- **INV-VSK-07** [V] Shipyard addresses are subspace; no world position inside `x ≥ (CHUNK_X_START−1599)·16, z ≥ −25 584` is a real-world position the
  substrate will leave alone (`ShipChunkAllocator.java:58-60`). Stellurgy's cell geometry is built around this (its javadoc `:26-46`).
- **INV-VSK-08** [V] A claim is the same size everywhere it is asked, and it holds everything a detection can find: one constant,
  `VSChunkClaim.RADIUS`, derived from `SpatialDetector.MAX_REACH` (`VSChunkClaim.java:32`, `:75`, `WorldServerShipManager.java:205`,
  `ClaimedChunkCacheController.java:45`). [T] `src/test/java/dev/stannismod/stellurgy/test/server/VSShipAssemblyExtentTest.java`
  (`aHullReachingTheFullDetectionReachKeepsEveryBlock`).
- **INV-VSK-14** [V] An assembly either copies every block it found or refuses and moves none: a hull past the reach is refused
  whole, and a hull taller than the default placement allows is placed lower in the shipyard rather than off its top
  (`WorldServerShipManager.java:199-203`, `:431-450`). [T] `VSShipAssemblyExtentTest` (the other two scenarios).
- **INV-VSK-09** [V] Vacuum does not damp: air drag retention is exactly 1.0 at zero atmospheric density (`PhysicsCalculations.java:299-301`).
- **INV-VSK-10** [V] A ship's Y never leaves the world's altitude band, which can only be widened (`ShipAltitudeBand.java:31-45`, `PhysicsCalculations.java:370-371`).
- **INV-VSK-11** [V] A body is carried only by a hull it is within 1 block of and that has not just been teleported
  (`EntityDraggable.java:73-77`, `:118-122`); a ship that jumps does not fling its passengers.
- **INV-VSK-12** [V] Block damage records travel with their blocks through assembly and copy-back (`WorldServerShipManager.java:272`, `:308-314`; `MoveBlocks.java:54`).
- **INV-VSK-13 [V]**  A VS ship is present and the registry answers on a loaded server (`src/test/java/dev/stannismod/stellurgy/test/server/VsVendorSmokeTest.java:42`) and
  a commanded ship translates under the physics loop (`src/test/java/dev/stannismod/stellurgy/test/server/VSShipMotionServerTest.java:94`).
- **INV-VSK-15 [T][BEH]** A ship the client unloads leaves none of its tiles among those the client draws. A ship's render
  chunks put its tiles into the world renderer's global tile set, and that set draws a tile wherever the ship managing its
  POSITION stands (`MixinTileEntityRendererDispatcher#preRender`), so a tile left there after its ship is gone is drawn inside
  whichever ship is next handed the claim. `PhysRenderChunk#killRenderChunk`, reached by a ship's unload
  (`PhysObjectRenderManager#killRenderers`) and by a chunk's replacement (`PhysObjectRenderManager#updateChunk`). Pinned by
  `VSShipIndexClientGroupTest#anUnloadedShipLeavesNoTileForTheClientToDraw`.

## Failure modes & edge cases

- **Refused assembly is silent to the caller.** Too big, bedrock reached or past the reach: logged with its reason, the entry is skipped, and
  the claim allocated at `createNewShip` stays spent; the caller that queued it has already been handed the uuid
  [V] `WorldServerShipManager.java:199-203`, `:416-420`, `ShipChunkAllocator.java:84-86`.
- **The ground an assembly is cut from is never relit** — the relight loop computes its chunk from the stale paste position
  [V] `WorldServerShipManager.java`, the "Then relight the original chunks" block of `spawnNewShips`.
- **The number of ships ALIVE in one world at once is bounded**: ship N sits at block Z ≈ 51 200·N, and vanilla's
  light code stops treating Z ≥ 30 000 000 as valid, so a world holding ~586 ships at once runs past it
  [V] `build/rfg/minecraft-src/java/net/minecraft/world/World.java:261`. Claims are given back (MECH-VSK-03), so
  it is the count alive at once that is bounded, not a world's lifetime count of assemblies.
- **An unreadable saved ship record stops the world from loading**, and a record that cannot be serialized
  stops the save, leaving the file on disk as the last save that succeeded (`VSDefaultCapability#readNBT`,
  `#writeNBT`, `#unreadable`): neither installs or writes an EMPTY record, which the next save would make
  permanent. [T] `test/unit/VSWorldDataRecordTest` (the read half; a serializer that fails cannot
  be arranged without a stand-in, so the write half is unpinned).
- **Immediate load stalls the game thread** for 289 chunk loads (`ClaimedChunkCacheController.java:104`).
- **Cross-thread reads are unsynchronised beyond the pose.** The physics thread iterates `activeForcePositions` while the game thread edits it (the source says so,
  `PhysicsCalculations.java:191-193`). The pose watchdog no longer reads the live `watchingPlayers` list: it iterates an immutable
  copy the game thread publishes after each refill, destroy and unload (`PhysicsObject#getPublishedWatchingPlayers`;
  unpinned — a two-thread race).
- **A ship destroyed this tick leaves the physics snapshot at once**: `WorldServerShipManager#tick` republishes the (`volatile`) snapshot
  right after its destroy loop; a step already in flight still holds the old list (unpinned — thread timing).
- **The client index handler drops an index addressed to another world**, as the pose handler does (`ShipIndexDataMessageHandler#onMessage`) [T] `VSShipIndexClientGroupTest#aShipIndexForAnotherWorldLeavesThisWorldsShipsAlone`.
- **The client is never told a ship was forgotten** — the index message only adds, loads and unloads. Since claims are handed out
  again, a stale record would leave two client records on one claim, and `QueryableShipData#getShipFromChunk` throws on two, which
  `VSBridge#shipIdManagingBlock` reads as "no ship": every pilot gate on a reused claim would stay shut. The handler therefore lets
  a record arriving for a claim unload and remove any other record holding it [T] the VS client group
  (`VSShipFlightTelemetryTest`, `VSCrewCaptureContractTest`), no dedicated method.
- **Non-vanilla world providers**: a world that never fired `WorldEvent.Load` throws on first manager access (`MixinWorld.java:481-486`); one without the capability throws (`ValkyrienUtils.java:124-133`).

## Integration seams

- **Stellurgy → VS (the port).** Exclusively `integration-vs.md` / C11; the flight computer tile `TileAdvancedFlightComputer` implements
  `IPhysicsBlockController` itself.
- **VS → Stellurgy (calls this tree makes into Stellurgy code).** Regenerate the list with
  `grep -rn "dev.stannismod.stellurgy" valkyrienskies/` — it is the hosting (`Stellurgy.instance.valkyrienSkies`), `WorldRuntime` parts (altitude band, pose clock, client
  render state), `DamageState` (carry, MECH-VSK-04/12), and `AtmosphereDensity`/`FreeFlightPhysics` (drag, MECH-VSK-09). Each new such call is a coupling this doc and C11 should name.
- **Mixins (our own, against vanilla).** `mixins.valkyrienskies.json` — see `mixins-asm-coremod` and C6. No production mixin targets
  this tree: it is edited in place, and VS's own `loadAndUnloadShips` already treats a ship that is already loaded as a satisfied request.
- **Forge bus.** `EventsCommon` subscribes at HIGHEST priority to world load/unload/tick, entity join, player tick/login, block, explosion and interact events (`EventsCommon.java:78-352`).
- **Wire.** Channels in MECH-VSK-01; the manager sends `ShipIndexDataMessage` (load/unload/index) and `ShipTransformUpdateMessage` (poses) — contract C2.

## Config surface

`VSConfig` is a Forge `@Config` rewritten on reload [V] `VSConfig.java:21`, `:201-215`. Load-bearing for Stellurgy: `targetTps` 57, `threadCount` 75, `maxDetectedShipSize` 83,
`ticksToStickToShip` 100, `SHIP_LOADING_SETTINGS` 133-174 (`permanentlyLoaded` is flipped by Stellurgy's port), `shipLowerLimit`/`shipUpperLimit` 25/28 (widened per world, MECH-VSK-09), `doGravity`, `doPhysicsBlocks`.
Tunable; values are not pinned here.

## Test coverage

| anchor | test |
|---|---|
| MECH-VSK-01 | `test/unit/VendoredAssetDomainTest.java:87` |
| MECH-VSK-06 | `test/server/SpaceSlotVsShipPersistTest.java:113` |
| MECH-VSK-07 | `test/server/VSCrossingLeavesNoShipBehindTest.java:78` |
| MECH-VSK-12 | `test/server/ShipDamageSurvivesRelocationTest.java:93` |
| MECH-VSK-13 | `test/unit/StandingOnAHullCountsAsBeingAtItTest.java:47` |
| MECH-VSK-15 | `test/server/SpaceSlotVsShipPersistTest.java:65` |
| INV-VSK-13 | `test/server/VsVendorSmokeTest.java:42`, `test/server/VSShipMotionServerTest.java:94` |
| INV-VSK-15 | `test/client/VSShipIndexClientGroupTest#anUnloadedShipLeavesNoTileForTheClientToDraw` |

(All under `src/test/java/dev/stannismod/stellurgy/`.) **No test found** for: the pose-send watchdog, the deconstruct states, or the
terrain collider — a grep of `src/test` and `testframework` for `poseSendIsOverdue`, `DeconstructState`,
`WorldPhysicsCollider` returns no file (2026-10-06). A test-only mixin records each refused spawn with VS's own reason
(`src/test/java/dev/stannismod/stellurgy/test/mixin/MixinWorldServerShipManagerDiag.java:78`, `ship_spawn_refused`); it asserts nothing.

## Open questions

- Does Forge write a world capability for every dimension, or only dimensions with their own `WorldInfo`? `[A]` — `SpaceSlotVsShipPersistTest.java:65` shows a slot world round-trips, nothing more.
