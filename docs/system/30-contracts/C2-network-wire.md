---
id: C2
covers: network wire format — the Stellurgy BasePacket subclasses in network/ and their registration (discriminator) order
confidence: high
owner-subsystem: network-wire
see-also: [network-wire (MECH-NW-*, INV-NW-*), C1 (nbt), C4 (config)]
---

# C2 — Network packets / wire format

Stellurgy's custom wire vocabulary is the `BasePacket` subclasses in `network/`. The netty channel,
id→class BiMap and `sendTo*` transport are the vendored `libvulpes.network` `PacketHandler` /
`BasePacket`; this contract pins only **which packets exist, their direction, their payload shape, and
their registration (discriminator) order**. Behavioural detail and citations are owned by subsystem doc
`network-wire` (anchors `MECH-NW-01..08`, `INV-NW-01..07`) — this file is the wire surface and the
cross-cutting risks.

## Registration order (= wire discriminator)

`PacketRegistry.registerAll` opens the channel with ONE ordered list (`PacketRegistry#declared`) during
`Stellurgy#preInit`; the codec numbers the classes in list order — MECH-NW-01, INV-NW-01. The list
carries three libVulpes packets (`PacketMachine`, `PacketEntity`, `PacketChangeKeyState`) at its head and
libVulpes' `PacketItemModifcation` interleaved at Reg#4 of Stellurgy's block; the one counter is shared
by both packages. The "Reg#" below is the 1-based position **within Stellurgy's block** (libVulpes' three
head packets excluded); the *absolute* wire id is readable from `declared()`. `PacketRegistry.verify`
rejects a null or duplicate entry before the channel exists. **Appending is safe; inserting or
reordering renumbers the tail.**

## Consolidated packet table

| Reg# | class | dir | payload shape (wire) | domain object / notes |
|-----|-------|-----|----------------------|-----------------------|
| 1 | `PacketDimInfo` | S→C | dimId int + `DimensionProperties` NBT (compound) + artifacts + custom icon; null props = delete | C1 dim NBT reused; write() may `deleteDimension` on NBT throw |
| 2 | `PacketSatellite` | S→C | `SatelliteBase` NBT (compound) → add to its dim | MECH-NW-04 |
| 3 | `PacketStellarInfo` | S→C | `StellarBody` NBT; null star = remove-star | MECH-NW-04 |
| 4 | `PacketItemModifcation` | — | *(foreign — libVulpes)* | not a Stellurgy packet; occupies a wire slot |
| 5 | `PacketOxygenState` | S→C | zero-byte ping | sets `lastSuffocationTime` in readClient (MECH-NW-06) |
| 6 | `PacketStationUpdate` | S→C | `type.ordinal()` int + type-branched body (`Type` enum) | multiplexer, MECH-NW-05; ordinal = wire value (INV-NW-06) |
| 7 | `PacketSpaceStationInfo` | S→C | station NBT + class-id + fuel + warp flag + forward dir | `flag` hardcoded false |
| 8 | `PacketAtmSync` | S→C | NBT: `"pressure"` int (centi-atm; an int because a short wraps above 327.67 atm) + `"breathable"` boolean + `"warning"` string (a lang key) + `"holds"` string list (the statements true of the air, by enum NAME, never by ordinal) | a READOUT, not a model: the client draws it and decides nothing with it. Sent once a second per player, phased on the player's own age so the server does not serve everyone on one tick |
| 9 | `PacketBiomeIDChange` | S→C | pos + 256-byte chunk biome array + terraform FX | |
| 10 | `PacketStorageTileUpdate` | S→C | rebroadcast a tile update inside rocket `StorageChunk` | reads vanilla field via SRG name reflection |
| 11 | `PacketLaserGun` | S→C | entity + Vec3d target → laser FX | FX (MECH-NW-06) |
| 12 | `PacketAsteroidInfo` | S→C | one `Asteroid` def → the client galaxy's `getAsteroidTypes()`; sent per kind at every login, local too | C4 |
| 13 | `PacketAirParticle` | S→C | block pos → oxygen cloud/trace FX | gated by particleSetting |
| 14 | `PacketInvalidLocationNotify` | S→C | pos → red error-block FX | FX |
| 15 | `PacketConfigSync` | S→C | whole `StellurgyConfiguration` via `writeConfigToNetwork` | C4; login push (MECH-NW-07) |
| 16 | `PacketFluidParticle` | S→C | from→to + color + time → fluid trail FX | FX |
| 17 | `PacketSatellitesUpdate` | S→C | all ticking satellites of a dim, keyed by id | MECH-NW-04 |
| 18 | `PacketSyncKnownPlanets` | S→C | station id + known-planet id set | dual-sourced (CON-C2-08) |
| 19 | `PacketBackToRocketGui` | **C→S** | 4 ints: dimId, x, y, z | the only real `executeServer` (MECH-NW-03) |
| 20 | `PacketDeckCapture` | S→C | UTF8 `shipId` + `double subX,subY,subZ` (the deck point in ship SUBSPACE) + `boolean restore` | C8; the server re-sends it until the client seeds its own capture |
| 21 | `PacketSlotDimSync` | S→C | `int typeId`, **`int hyperDim`**, `int count`, `count × int dimId` | registers the slot `DimensionType` + dims client-side BEFORE anything can move a player into one; see below |
| 22 | `PacketSystemBodiesSync` | S→C | see its own section below | the cell-sky feed |
| 23 | `PacketNavBodyInfo` | S→C | `int tierOrdinal` + `int count` + per field (`int fieldOrdinal`, `String value`) | the redacted nav channel; see below |
| 24 | `PacketSpaceClockSync` | S→C | one `long tick` | C16 — the space clock, whose readers are on both sides |
| 25 | `PacketShotSpawn` | S→C | see its own section below | the projectile drawing channel |
| 26 | `PacketShotEnd` | S→C | see its own section below | the projectile drawing channel |
| 27 | `PacketBeamState` | S→C | see its own section below | the held-beam drawing channel |
| 28 | `PacketKnownPlanets` | S→C | `int count`, `count × int dimId` | the save's globally known planets, added to the client galaxy's `knownPlanets` at every login, local too |
| 29 | `PacketShipReadout` | S→C | see its own section below | a ship's readout to one player |
| — | `PacketMoveRocketInSpace` | dead | 3 doubles + hasWorld bool (+int) + hasStar bool (+int) | **UNREGISTERED** — CON-C2-01 |

Every S→C packet has an `executeClient` body and an empty `executeServer`, so a spoofed inbound copy on
the server is inert (INV-NW-02).

## Collisions & risks

- **CON-C2-01 [V] — `PacketMoveRocketInSpace` is unregistered.** Absent from `PacketRegistry#declared`
  and never constructed in `src/main`. It therefore has no wire id and cannot be sent. Its `write`
  inverts its guard (`hasWorld = position.world == null`, then dereferences `position.world`) and `read`
  dereferences the null `position` from the no-arg ctor → NPE if ever received
  (`PacketMoveRocketInSpace.java:30-34,51-52`). Pinned by `integration/PacketSerializationTest`.

- **CON-C2-02 [V] — read without matching write (buffer desync).** `PacketStationUpdate.FUEL_UPDATE`
  writes the fuel int only `if (spaceObject instanceof SpaceStationObject)` but `readClient` reads it
  **unconditionally** → wire desync when the `ISpaceObject` is a different subtype
  (`PacketStationUpdate.java:52-54` vs `:94-96`).

- **CON-C2-03 [V] — discriminator index instability across builds.** Wire id is pure registration order
  (INV-NW-01). Inserting, removing or reordering **any** entry in `declared()` — including libVulpes
  adding or removing a packet before Stellurgy's block, or the interleaved `PacketItemModifcation`
  (Reg#4) — shifts every subsequent packet's id. Client and server must be the **exact same build** or
  ids silently map to the wrong class. No version handshake exists in `src/main`.

- **CON-C2-04 [V] — `PacketStationUpdate.Type` ordinal is the wire value.** `writeInt(type.ordinal())` /
  `Type.values()[readInt()]`; reordering the enum is a breaking wire change. INV-NW-06. A hostile
  out-of-range ordinal fails bounded (INV-NW-07, `[A]`: no test pins it).

- **CON-C2-05 [V] — client-trusted input surface.** `PacketBackToRocketGui` (the sole C→S Stellurgy
  packet) carries client-supplied `dimId,x,y,z` straight into `executeServer` →
  `RocketGuiNavigation.openRocketGuiFromReturnContext`. Trust is delegated entirely to
  `RocketGuiNavigation`'s re-validation (INV-NW-03); if that validation is incomplete, the coords are
  attacker-controlled.

- **CON-C2-06 [V] — world mutation inside `write()`.** `PacketDimInfo` / `PacketStationUpdate` /
  `PacketSpaceStationInfo` call `DimensionManager.deleteDimension(...)` from inside wire encode when NBT
  serialization throws (`PacketDimInfo.java:66`, `PacketSpaceStationInfo.java:60`,
  `PacketStationUpdate.java:76`).

- **CON-C2-07 [V] — dead null-guard / SRG-name trap.** `PacketSpaceStationInfo.write` hardcodes
  `boolean flag = false` so a null `spaceObject` NPEs in `writeToNbt` (`PacketSpaceStationInfo.java:40`);
  `PacketStorageTileUpdate.write` reads a vanilla private field by SRG name `"field_148860_e"` — a
  dev/prod obf trap (`PacketStorageTileUpdate.java:48`).

- **CON-C2-08 [A] — dual-source known-planet state.** `PacketSyncKnownPlanets` is sent both by
  Stellurgy's own sync path and per-station in the player-login handler (`Stellurgy.java:1889`) → two
  producers for one client state.

## `PacketNavBodyInfo` — the redacted nav channel

The **server drops every `PlanetInfoField` above the asking ship's `InfoTier` BEFORE writing**, so an
unearned field never crosses. It answers a `PacketMachine` request from `TileNavigationComputer`;
`read`/`executeServer` are unused. The client decodes it and drops it: it has no reader.

The nav computer's client→server actions ride the existing `PacketMachine` `INetworkMachine` channel
(ids 0-5: copy · erase source · clear target · pick address · aim typed coordinate · sync channel).

## `PacketSlotDimSync` — `hyperDim`

`hyperDim` is which of the synced dims is the hyperspace transit host, or `Integer.MIN_VALUE` when it is
not registered. The list alone cannot say: hyperspace and the pool cells share one
`WorldProviderSpaceSlot`, so a client holding only the ids cannot tell the world it is flying a jump in
from the world it was parked in. It is written and read SECOND (ahead of the list), and applied by the
client handler **ahead of every guard** in it — the sky's gate has nothing to do with the `DimensionType`
negotiation and must not be lost to a mod-set mismatch. Consumed by `ServerView.adoptHyperspaceDimId` /
`HyperspaceWorld.isHyperspace(World)`: the adopted id lives in the client's view of the server it is
connected to (dropped with that view) and deliberately NOT in the server's `dimId`: a client JVM that
joined a dedicated server and then opens a single-player world would otherwise carry the remote id into
`register()`, which skips registration whenever the id is already set. See C14 CON-C14-18 (the backdrop
is not gated on the seat entity's jump phase, so standing up does not empty the sky).

## `PacketSystemBodiesSync` — the cell-sky render channel

| class | dir | payload shape (wire) |
|---|---|---|
| `PacketSystemBodiesSync` | S→C | `int dimCount`, then per dim: `int slotDimId`, `int bodyCount`, then per body: `int kindOrdinal`, `long localX`, `long localY`, `long localZ`, `int dimId`, `boolean descendTarget`, `long boundaryRadius`, `long radiusBlocks`, `int parentIndex`; **then the NEBULA half** — `int dimCount`, then per dim: `int slotDimId`, `int nebulaCount`, then per cloud: `float dirX`, `float dirY`, `float dirZ`, `float angularRadius`, `int appearanceOrdinal`, `float opacity` |

**`kindOrdinal` is an ordinal, so `SystemBodyKind` is APPEND-ONLY** (`GAS_GIANT` and `ROGUE_PLANET` are
appended last for this reason): every kind keeps the number it has, so a client and a server one commit
apart still agree about what a body is. Reordering the enum — or inserting a kind — silently re-labels
every body in every sky.

**The nebula half rides this packet rather than one of its own** because it answers the same question —
what does the sky of this cell show — keyed by the same cell→slot binding, cleared by the same empty
payload and broadcast on the same tick; a second channel would be a second lifecycle to keep in step,
and the two skies could then disagree about which cell the viewer is in.

**A body carries a POSITION and a cloud carries a DIRECTION, and that asymmetry is the design.** A body
is a destination: the client needs its bearing AND its range, so the triple is a block delta. A nebula
is light years across and hundreds of light years away — it has no parallax across a cell, it is
deliberately not a destination and it has no cell name — so what crosses the wire is a unit vector, the
half-angle it subtends, its appearance and how thick it is. Sending a position for one would be
inventing an address the universe layer refuses to give it.

**`boundaryRadius`** is the distance in blocks from the body's address at which its atmosphere begins
(`0` for a body that is not a descend target). The shell differs per body (a body has a physical
radius), and a client that derived it from a shared constant would draw and label every approach wrong
with nothing to indicate it — a silent, per-body, gameplay-visible error. Sending it keeps the server
the single place that sizes the shell (`space/DescentShell.radiusAround`); the client never has to know
which body kinds have one. The range shown counts down to this surface, and every standoff and
proximity gate is measured from it, not from the body's centre. Same-version consistency only:
both sides ship together.

## `PacketShotSpawn` / `PacketShotEnd` — the projectile drawing channel

| class | dir | payload shape (wire) |
|---|---|---|
| `PacketShotSpawn` | S→C | `long id`, `double x/y/z`, `double vx/vy/vz`, `float radius`, `int lifetimeTicks`, `double gravityPerTickSquared` |
| `PacketShotEnd` | S→C | `long id`, `double x/y/z`, `byte endReasonOrdinal` |

**Two packets for a whole flight, deliberately.** A round's path is completely determined by the launch
numbers, so the client integrates its own copy rather than being sent a position twenty times a second
for up to a minute of flight. `PacketShotEnd` is the correction: without it a client would draw a round
sailing on through the hull that stopped it, because absorption and impact are decisions taken in a
simulation the client does not run.

**Neither packet is authoritative for anything.** The client's copies are drawings — nothing in the mod
reads them, no hit is resolved from them, and a player who received neither packet plays the same game.
That is what makes it safe for the client's integration to drift.

**`endReasonOrdinal` is an ordinal on the wire.** `ShotEndReason` may be appended to; reordering it
changes what a shipped client draws. Same-version consistency only.

## `PacketBeamState` — the held-beam drawing channel

| class | dir | payload shape (wire) |
|---|---|---|
| `PacketBeamState` | S→C | `long gunPos` (`BlockPos.toLong`), `boolean lit`, and **only when lit** an unsigned-byte point COUNT followed by that many `double x/y/z` triples — the path the beam occupies, muzzle first. Two points for the ordinary straight beam; more where something turned it, capped at 9 to match the server's segment budget. **Two fixed endpoints would draw a beam a mirror had bent as a straight line through the mirror** |

**One packet for a state, repeated, rather than two for an event.** A beam has no launch and no impact to
announce: it is a line that exists while a trigger is held, so what travels is the line. The gun's own
position is the whole identity — a gun holds at most one beam — which is why there is no id on the wire.
The endpoints are written only when it is lit; "it went out" needs no coordinates.

**The reader must tolerate the short form.** `write` returns after the boolean when the beam is dark, so
anything that reads this packet reads the flag FIRST and stops. A reader that always pulled six doubles
would desynchronise the buffer on the commonest packet of the pair.

**Not authoritative for anything.** The client's beams are drawings — no damage is resolved from them and
nothing reads them back. See `projectile-substrate` MECH-SHOT-29 for the cadence, and MECH-SHOT-30 for
who is told.

## `PacketShipReadout` — a ship's readout to one player

S→C only. Wire: the addressed tile's `BlockPos` as a long, the live slice (`saturated` boolean,
`wheelFill` double), then `ShipReadout.write` — revision, three masses, the field, and per view ×
endurance × twelve directions an authority and a torque, then per view the twelve burst endurances
(`ship/control/ShipReadout.java:190-206`, `network/PacketShipReadout.java`) `[V]`. An infinite endurance
or TWR travels as a double infinity; that is fine on this wire and is why the JSON probe spells it
differently.

**Who is told** (C10 STAT-21): the occupant of the ship's pilot seat and anyone with that flight
computer's console open, on a new model revision, and the live slice at most every `READOUT_PUSH_TICKS`
(`tunable`); whoever pressed Scan/Build on an assembler, once per scan. Nobody else receives a byte. The
client hands it to the tile at that position (`IShipReadoutReceiver`); nothing reads it back — the
server is the source of every figure.
