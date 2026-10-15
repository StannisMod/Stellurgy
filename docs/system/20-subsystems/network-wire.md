---
id: network-wire
owns: [network/]
entrypoints: [PacketRegistry#registerAll, PacketHandler#openChannel, BasePacket#write/read/readClient/executeClient/executeServer]
depends-on: [dimension-planets, space-stations, satellite, atmosphere-oxygen, api-public, util-core]
depended-by: [rocket-entity, atmosphere-oxygen, dimension-planets, space-stations, satellite, event-handlers, commands-gameplay, client-render]
contracts: [C2, C4, C1]
confidence: high
---

## Purpose

The 19 concrete `BasePacket` subclasses in `network/` are Stellurgy's entire custom
wire vocabulary. They carry server→client world/registry state (planets, stars, stations,
satellites, config, atmosphere) and a handful of FX/signal messages, plus one client→server
GUI-navigation request. The dispatch channel, id assignment and side routing live in
libVulpes' `PacketHandler`/`BasePacket`; this subsystem owns only the payloads and their
registration order.

## Responsibility boundary

Owns: the payload `write`/`read`/`readClient` serialization and `executeClient`/`executeServer`
handlers of each `Packet*` class; the `addDiscriminator` registration block in
`Stellurgy.java`; and, since libVulpes is part of the tree, the TRUST rule of the two generic
channels `libvulpes/network/PacketMachine` and `PacketEntity` (MECH-NW-10, INV-NW-08) — what each
machine's bytes mean stays its own subsystem's. Does NOT own: the netty channel, id→class BiMap, or the `sendTo*`
transport (libVulpes `PacketHandler`, foreign — described only as a seam); nor the domain
objects it serializes (`DimensionProperties`, `SpaceStationObject`, `SatelliteBase`,
`StellurgyConfiguration`, `Asteroid`, `StellarBody`) — those are other subsystems' NBT contracts,
reused here verbatim over the wire.

## Key types

The packet inventory (class, direction, payload shape) is `C2-network-wire`'s consolidated packet table; this doc owns
what the dispatch does with them (MECH-NW-*). On a REMOTE client the `PacketDimInfo` delete signal also withdraws the Forge
registration (`DimensionManager#withdrawDimension`; dimension-planets).

## Mechanics

- **MECH-NW-01 — discriminator registration = wire id; the channel is FML's.** `Stellurgy#preInit`
  calls `PacketRegistry.registerAll`, which hands the ONE ordered list — libVulpes' three packets at
  its head, then the mod's — to `PacketHandler.openChannel`. That call registers the `libVulpes`
  channel with FML and numbers every class in list order on a codec that lives only inside the call.
  Nothing of ours holds the channel: senders look it up by name (`NetworkRegistry.getChannel`), which
  is loud before `preInit`, and FML refuses a second opening. The channel is neither created by a static initialiser nor held in a field of ours: FML owns it. `PacketRegistry#registerAll`, `PacketHandler#openChannel`, `#channel` [V]
- **MECH-NW-02 — server→client dispatch (dominant pattern).** Sender builds packet, calls a
  `PacketHandler.sendTo*`; on receipt libVulpes calls `readClient(ByteBuf)` then
  `executeClient(EntityPlayer)`. 17 of 19 packets are this shape; their `read`/`executeServer`
  are empty guards ("should never be read on the server"). e.g. `PacketDimInfo.java:80,113`.
- **MECH-NW-09 — every packet EXECUTES on the game thread; the player is read when it runs.** The
  codec only decodes, on the netty thread. Each packet class is registered with a Forge
  `SimpleChannelHandlerWrapper` whose handler (`BasePacket.BasePacketHandlerClient` /
  `BasePacketHandlerServer`) queues `executeClient` / `executeServer` on that side's game thread, in
  arrival order, in the queue vanilla's packets are applied from. The player handed to it is read at
  that moment (`mc.player` / the connection's `player`), not on arrival: a respawn or dimension change
  queued ahead of it replaces the player, and one captured on arrival would send the packet to the
  world he left. `BasePacket.java` handlers, `PacketHandler.Codec#decodeInto` [V]
  [T `ModPacketDeliveryClientGroupTest#aModPacketQueuedBehindARespawnRunsForTheNewPlayer`, client
  half only: a test mixin hands a packet to the client handler in the window between a respawn being
  queued and applied; the server handler's half has no test]
- **MECH-NW-03 — client→server request.** `PacketBackToRocketGui` is the sole server-bound Stellurgy
  packet: `write` on client, `read`+`executeServer` on server (calls
  `RocketGuiNavigation.openRocketGuiFromReturnContext`); its `readClient` merely delegates to
  `read`. `PacketBackToRocketGui.java:41,58`; senders `TileGuidanceComputer.java:72`,
  `TileSatelliteHatch.java:50`.
- **MECH-NW-04 — NBT-envelope encoding.** Domain packets serialize their object to an
  `NBTTagCompound` and push it with `PacketBuffer.writeCompoundTag`, reversed with
  `readCompoundTag`. Used by Dim/Stellar/SpaceStationInfo/Satellite/SatellitesUpdate/Atm and
  `StationUpdate.DIM_PROPERTY_UPDATE`. e.g. `PacketDimInfo.java:56`, `PacketSatellite.java:41`.
- **MECH-NW-05 — typed sub-command multiplex.** `PacketStationUpdate` writes
  `type.ordinal()` then branches on a 7-value `Type` enum (orbit/dest/fuel/rotation/altitude/
  dim-property/white-burst); reader rebuilds via `Type.values()[readInt()]`.
  `PacketStationUpdate.java:44,88,229` (enum `:229`).
- **MECH-NW-06 — FX/signal packets.** Particle packets spawn client effects gated by
  `gameSettings.particleSetting`; `PacketOxygenState` is a zero-byte ping whose entire effect
  is writing `AtmosphereHandler.lastSuffocationTime` inside `readClient`.
  `PacketAirParticle.java:44`, `PacketOxygenState.java:24`.
- **MECH-NW-07 — config push on login.** `PlanetEventHandler` sends `PacketConfigSync` (+ per-
  dim `PacketDimInfo`) to a joining connection; the client hands it to that connection
  (`proxy.adoptServerConfig`, api-public MECH-API-04). `PacketConfigSync.java:39`, `PlanetEventHandler.java:359`.
- **MECH-NW-08 — dispatch verbs.** Callers pick reach via libVulpes:
  `sendToServer` (119×), `sendToNearby` (45×), `sendToAll` (32×), `sendToPlayer` (18×),
  `sendToPlayersTrackingEntity` (17×), `sendToDispatcher` (5×, login-time). Counts from grep
  over `network/` callers.
- **MECH-NW-10 — a client's packet to a machine or an entity is that player USING it, and the
  server asks before reading a byte (2026-10-06).** The two generic channels,
  `libvulpes/network/PacketMachine` (a tile, addressed by dimension + position) and `PacketEntity`
  (an `INetworkEntity`, addressed by dimension + entity id), carry an address the CLIENT wrote. On the
  server both resolve the target on the game thread and call `INetworkMachine#canBeUsedBy(player)`
  first; a refusal is one WARN naming packet id, target, place and player, and `readDataFromNetwork`
  / `useNetworkData` never run. `PacketMachine.java:132-145`, `PacketEntity.java:131-144`,
  `INetworkMachine.java:30` [V]. Maintainer ruling 2026-10-06: *"открытое GUI - это абстракция "я
  использую эту машину". Использовать машину издалека нельзя"* — so the same predicate answers a
  machine's container question (`canInteractWithContainer`), and its screen closes when the player
  walks out of reach.
  The predicate is ABSTRACT (no default), and it is deliberately not `IInventory#isUsableByPlayer`:
  sharing that signature would let an inventory's answer (often a bare `true`) answer this without a
  decision. The usual answer is `MachineReach#reaches` — vanilla's rule, distanceSq to the block
  centre ≤ 64.0 in the same world, still the tile there; for an entity, riding it or within 8 blocks of
  its bounding box (`MachineReach.java:32,46`) [V]. A multiblock answers `isComplete()` AND reach to
  the controller (`TileMultiPowerConsumer.java:401`); the pilot seat answers "seated on THIS seat" AND
  reach (`TilePilotSeat.java:229`) [V]. On a VS ship the tile's position is a shipyard address and VS's
  `MixinEntity#getDistanceSq(double,double,double)` measures to where the block really is [V].
  **A request for STATE is not a use, so there is none**: what a player needs to see a machine or an
  entity is pushed by the server when he starts seeing it — `EntityRocket#addTrackingPlayer` and
  `EntityElevatorCapsule#addTrackingPlayer` (called by vanilla's tracker after the spawn packet, on the
  same connection), and `TileOrbitalLaserDrill#getUpdateTag` / `#handleUpdateTag` (sent with the chunk)
  replaced three client→server requests (`REQUESTNBT`, capsule `PACKET_RECIEVE_NBT`, drill id 13)
  that any viewer used to send (`EntityRocket.java:844`, `EntityElevatorCapsule.java:151`,
  `TileOrbitalLaserDrill.java:588,599`) [V]. `REQUESTNBT` stays in `EntityRocket.PacketType` only to
  keep the later ordinals.

## State & persistence

No packet persists state; all NBT here is ephemeral wire framing that reuses each domain's
own persisted schema (C1). The only packet-local wire NBT keys are in `PacketAtmSync.write`, which
sends a finished `AtmosphereSummary` rather than the
atmosphere's name: `"pressure"` (int centi-atm; a short would wrap above
327.67 atm), `"breathable"` (boolean), `"warning"` (a lang key) and
`"holds"` (string list) — the full row is C2's `PacketAtmSync` line. These are C2 wire keys, never
written to disk.

## Invariants

- **INV-NW-01 [V][SYS]** Wire discriminator id is determined solely by `addDiscriminator` call
  order; client and server must register the identical set in the identical order (same mod
  build) or ids desync. `Stellurgy.java:334-352`; libVulpes `BasePacket.idMap`. FOR: wire format: same build on both sides.
- **INV-NW-02 [V]** Client-bound packets treat `read`/`executeServer` as no-ops, so a spoofed
  inbound copy on the server does nothing (`PacketAtmSync.java:55` "we don't want hackers").
- **INV-NW-03 [V]** `PacketBackToRocketGui` is the only Stellurgy packet with a real `executeServer`;
  it re-validates via `RocketGuiNavigation`, not trusting client-sent coords blindly.
  `PacketBackToRocketGui.java:58`.
- **INV-NW-04 [A][SYS]** NBT-envelope packets round-trip byte-for-byte through
  `writeCompoundTag`/`readCompoundTag` — pinned for Dim/Satellite/Station/Config/Asteroid/
  SpaceStationInfo. `integration/PacketSerializationTest.java:100,169,216,264,335,458`. Pinned by `PacketSerializationTest#packetSatelliteRoundTrip`, `PacketSerializationTest#packetConfigSyncRoundTrip`, `PacketSerializationTest#packetAsteroidInfoRoundTrip`, `PacketSerializationTest#packetDimInfoNullPropertiesIsDeleteSignal`. FOR: wire format: same build on both sides.
- **INV-NW-05 [V]** `PacketMoveRocketInSpace` is dead: never `addDiscriminator`'d
  (absent from `Stellurgy.java:334-352`) and never constructed anywhere in `src/main`. A sentinel
  records only that its `read` NPEs (`PacketSerializationTest#packetMoveRocketInSpaceDocumentsKnownBugs`),
  not that it stays unregistered.
- **INV-NW-06 [V][SYS]** `PacketStationUpdate.Type` ordinal is the wire value
  (`writeInt(type.ordinal())` / `Type.values()[readInt()]`); reordering the enum is a
  breaking wire change. `PacketStationUpdate.java:44,88`. FOR: wire format: same build on both sides.
- **INV-NW-07 [A]** A hostile out-of-range `Type` ordinal fails bounded (no OOB read past a
  bad buffer) — inferred from the read at `PacketStationUpdate.java:86` (`Type.values()[in.readInt()]`,
  which throws on an out-of-range index rather than reading past anything). No test pins it.
- **INV-NW-08 [V][T][BEH]** No byte of a client's `PacketMachine` / `PacketEntity` reaches its target unless
  the target's `canBeUsedBy(sender)` is true, and neither decoder touches a world: both copy the
  address and the payload on the netty thread and resolve on the game thread, so an address naming an
  unloaded dimension is dropped instead of disconnecting the sender.
  `PacketMachine.java:89,107,132-145`, `PacketEntity.java:90,117,131-144`. Pinned by
  `MachineGuiClientGroupTest#aWeaponConsolePressFromBeyondReachChangesNothing`,
  `MachineGuiClientGroupTest#aFireControlSensorPressFromBeyondReachChangesNothing`,
  `MachineGuiClientGroupTest#aRocketRefusesAPacketFromBeyondReach`.
- **INV-NW-09 [T][BEH]** A machine's open screen is the player USING it, so it closes when he walks out
  of the machine's reach, on the server's own check of the same `canBeUsedBy` the packets ask
  (`canInteractWithContainer` answers with it). Pinned by
  `MachineGuiClientGroupTest#aMachineScreenClosesWhenItsUserWalksOutOfReach`.
- **INV-NW-10 [T][BEH]** Being shown a craft is not using it: the server PUSHES a rocket's blocks to a
  player when he starts tracking it (`EntityRocket#addTrackingPlayer`), from wherever he stands, and no
  client request is needed or accepted for them. Pinned by
  `MachineGuiClientGroupTest#aRocketIsShownToAPlayerWhoSeesItFromBeyondReach`.

## Failure modes & edge cases

- `PacketStationUpdate.FUEL_UPDATE` writes the fuel int only `if (spaceObject instanceof
  SpaceStationObject)`, but `readClient` reads it unconditionally → buffer desync when the
  object is not a `SpaceStationObject`. `PacketStationUpdate.java:52-55,98-100`..
- `PacketMoveRocketInSpace.write` inverts its own guard: `hasWorld = position.world == null`
  then `if (hasWorld) writeInt(position.world.getId())` → NPE when world is null; `read`
  dereferences a null `position`. Dead code so unreachable in practice.
  `PacketMoveRocketInSpace.java:26-38,47`..
- `PacketSpaceStationInfo.write` hardcodes `boolean flag = false` (TODO), so the null-guard is
  dead and a null `spaceObject` NPEs in `writeToNbt`. `PacketSpaceStationInfo.java:40`..
- `PacketStorageTileUpdate.write` reads the vanilla `SPacketUpdateTileEntity` private field via
  SRG name `"field_148860_e"` reflection — a dev/prod obf-name trap. `PacketStorageTileUpdate.java:52`..
- Serialization side effects: `PacketDimInfo.write` / `PacketStationUpdate.write` /
  `PacketSpaceStationInfo.write` call `DimensionManager.deleteDimension(...)` from inside
  `write()` when NBT throws — world mutation during wire encode. `PacketDimInfo.java:66`.

## Integration seams

- **Packets (C2):** all 19 classes; registration `Stellurgy.java:334-352`. Note
  `PacketItemModifcation` (libVulpes) is registered in the same Stellurgy block.
- **Config (C4):** `PacketConfigSync` ferries the entire `StellurgyConfiguration` server→client on
  login (`writeConfigToNetwork`/`readConfigFromNetwork`); `PacketAsteroidInfo` injects one
  entry into the connection galaxy's `getAsteroidTypes()`, one per kind at every login.
- **NBT (C1):** every envelope packet reuses a domain object's own `writeToNBT`/`readFromNBT`
  contract; no new persisted keys are introduced here.
- **Events:** `PlanetEventHandler` is the login-time driver (`sendToDispatcher`).
- **Transport (foreign):** libVulpes `PacketHandler.sendTo{Server,All,Player,Nearby,
  PlayersTrackingEntity,Dispatcher}` and `BasePacket.{toBytes,fromBytes,getPacketId,
  constructPacket}`.

## Config surface

- `StellurgyConfiguration` (whole struct) — `PacketConfigSync` → MECH-NW-07. Full-disable path: none;
  config sync is unconditional on player login.
- `gameSettings.particleSetting` (vanilla client option) gates FX packet output volume
  (MECH-NW-06); does not disable the packet, only particle count.

## Test coverage

- INV-NW-04 → `test/integration/PacketSerializationTest.java:100,169,216,264,335,458`
- INV-NW-05 → `integration/PacketSerializationTest.java:500` (`packetMoveRocketInSpaceDocumentsKnownBugs`)
- INV-NW-07 → none (`[A]` above)
- INV-NW-08 / MECH-NW-10 → `client/MachineGuiClientGroupTest`: `aWeaponConsolePressFromBeyondReachChangesNothing`,
  `aFireControlSensorPressFromBeyondReachChangesNothing` (a forged `PacketMachine` from 24 blocks is
  judged unusable and changes nothing; from beside, accepted), `aMachineScreenClosesWhenItsUserWalksOutOfReach`
  (an air recirculator's screen closes on the server's own reach check), `aRocketRefusesAPacketFromBeyondReach`
  (the `PacketEntity` half), `aRocketIsShownToAPlayerWhoSeesItFromBeyondReach` (the push). Each
  red-witnessed 2026-10-06. Not seen: the decoder half of INV-NW-08 (an unloaded dimension), the
  capsule's and the laser drill's push.
- Empty-buffer safety of `readClient` for Laser/AirParticle/InvalidLocation/Fluid/BiomeID/
  DimInfo/SpaceStationInfo/StationUpdate/Asteroid/ConfigSync/Satellite/SatellitesUpdate/AtmSync/
  StellarInfo/SyncKnownPlanets → not tested (the delete-flag short-circuit tests exist, `integration/PacketSerializationTest.java:521,539`).

## Open questions

- libVulpes assigns discriminator numbers from a shared static counter that also numbers
  libVulpes' own packets; the exact numeric id of each Stellurgy packet is a libVulpes-internal detail
  not verifiable from `src/main` (foreign). Only the relative order (INV-NW-01) is asserted here.
