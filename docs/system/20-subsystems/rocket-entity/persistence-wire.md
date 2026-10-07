# rocket-entity — Persistence & wire

Parent: [00-overview.md](./00-overview.md). The complete NBT and network contract
for `EntityRocket` (+ `EntityStationDeployedRocket`) and `TileGuidanceComputer`.
Consolidated into C1 (NBT) and C2 (wire) at P3.

## MECH-RKT-20 — Entity NBT round-trip

Three-layer split in `EntityRocket`:
- `writeEntityToNBT` / `readEntityFromNBT` — the disk contract (Forge save)
  `EntityRocket.java:3145`, `:2998`.
- `writeNetworkableNBT` / `readNetworkableNBT` — the subset re-sent over the wire on
  `REQUESTNBT`/`RECIEVENBT` (everything except `data`/`lastDimensionFrom`) `:3082`,`:2992`.
- `writeMissionPersistentNBT` / `readMissionPersistentNBT` — subclass hook (empty in
  the base; `EntityStationDeployedRocket` fills it) `:3136`.

`writeEntityToNBT` = `writeNetworkableNBT` + `data` (StorageChunk) + `lastDimensionFrom`.

### NBT key table (rocket-entity owned)

| key | type | owner | read default / migration |
|-----|------|-------|---------------------------|
| `orbit` | bool | EntityRocket | `getBoolean`→false |
| `flight` | bool | EntityRocket | false |
| `inSpaceFlight` | bool | EntityRocket | false |
| `rcs_mode` | bool | EntityRocket | false; forced true if `inSpaceFlight` |
| `rcs_mode_cnt` | int | EntityRocket | 0 |
| `motionX/Y/Z` | double | EntityRocket | 0.0 |
| `destinationDimId` | int | EntityRocket | 0 |
| `lastDimensionFrom` | int | EntityRocket | 0 (disk only) |
| `infrastructure` | list⟨`loc` int[3]⟩ | EntityRocket | absent→none |
| `satallite` [sic] | compound (+`DataType`) | EntityRocket | absent→none |
| `data` | compound | EntityRocket→StorageChunk | disk only |
| `flightMode` | string | RocketFlightMode | missing/unknown→CLASSIC_LAUNCH |
| `ffQuatW/X/Y/Z` | float | FreeFlightPhysics.Quat | missing→identity; renormalised |
| `flightAssistOn` | bool | EntityRocket | missing→true |
| `ffLiftoffTargetY` | double | EntityRocket | written only if !NaN; missing→NaN |
| `ffHasLeftGround` | bool | EntityRocket | missing→true (detector armed) |
| `faSetpointFwd/Right/Up` | float | EntityRocket | missing→0 (hover) |
| `fwd` | int (facing ord) | StationDeployedRocket | mission-persistent |
| `launchX/Y/Z` | int | StationDeployedRocket | into `launchLocation` |
| `AlaunchX/Y/Z` | double | StationDeployedRocket | into `actualLaunchLocation` |
| `gas` | short | StationDeployedRocket | 0 (clamped to gas list) |
| `plannedHarvestMb` | long | StationDeployedRocket | **written, never read** |
| `destDimId` | int | TileGuidanceComputer | `getInteger`→0 |
| `landingx/y/z` | float | TileGuidanceComputer | 0.0 |
| `stationMapping` | list⟨`pos` int[3]+`id`⟩ | TileGuidanceComputer | per-station pads |

`StatsRocket` and `SpacePosition` also serialise their own keys into the same
compound (owned by api-public / util-core respectively).

Transient NBT keys used *only* as a scratch channel inside `readDataFromNetwork`
(never persisted): `selection`, `left/right/up/down`, `ffFwd/Vert/Strafe/Yaw/Pitch/
Roll/Brake/Cut`, `pos`. These are decoded from the ByteBuf into a temp NBT then
consumed by `useNetworkData` `EntityRocket.java:3170`–`3210`.

## MECH-RKT-21 — `PacketEntity` sub-packet routing

All rocket network traffic rides `libVulpes` `PacketEntity`, which carries a single
`byte` id = an `EntityRocket.PacketType` ordinal (or the magic `9987` = tile-entity
block update). Three methods implement `INetworkEntity`:
`writeDataToNetwork(out,id)` serialises the payload, `readDataFromNetwork(in,id,nbt)`
deserialises into scratch NBT, `useNetworkData(player,side,id,nbt)` acts on it
`EntityRocket.java:3158`,`:3214`,`:3268`.

`PacketType` (ordinals are the wire contract — **append-only**, `:3824`):
`RECIEVENBT, SENDINTERACT, REQUESTNBT, FORCEMOUNT, LAUNCH, DECONSTRUCT, OPENGUI,
CHANGEWORLD, REVERTWORLD, OPENPLANETSELECTION, SENDPLANETDATA, DISCONNECTINFRASTRUCTURE,
CONNECTINFRASTRUCTURE, ROCKETLANDEVENT, MENU_CHANGE, UPDATE_ATM, UPDATE_ORBIT,
UPDATE_FLIGHT, DISMOUNTCLIENT, TOGGLE_RCS, TURNUPDATE, ABORTLAUNCH, SENDSPACEPOS,
SET_FLIGHT_MODE, FREE_FLIGHT_INPUT, SET_FLIGHT_ASSIST, ENGINE_START`. The last four
were appended for Free Flight so earlier ids stay stable. Ids ≥ `BUTTON_ID_OFFSET(25)`
and ≥ `STATION_LOC_OFFSET(50)+25` are reused as a numeric range for GUI-tile buttons
and station-pad selection `:3405`,`:3412`.

Server-authority checks live in `useNetworkData`: `SET_FLIGHT_MODE`/`FREE_FLIGHT_INPUT`/
`SET_FLIGHT_ASSIST`/`ENGINE_START` verify the sender is a passenger of this rocket
(and FF mode / not-in-flight where relevant) before mutating `:3348`–`3404`.

`EntityStationDeployedRocket` extends the routing for `MENU_CHANGE` (gas selector)
`EntityStationDeployedRocket.java:524`,`:534`,`:545`. `EntityElevatorCapsule` uses its
own private packet ids (0–4) not the `PacketType` enum `EntityElevatorCapsule.java:47`.

## Invariants

- **INV-RKT-15 [V]** `PacketType` is append-only; the four FF ids sit at the end so
  saved/old-client wire ids do not shift `EntityRocket.java:3848`.
- **INV-RKT-16 [V]** Every state-mutating sub-packet re-verifies passenger authority
  server-side before acting `EntityRocket.java:3352,3371,3385,3397`.
- **INV-RKT-17 [V]** `flightMode` is written unconditionally (even for CLASSIC) so
  the field round-trips without "missing == default" ambiguity `:3094`; missing key
  still degrades safely to CLASSIC via `readFromNBT` `RocketFlightMode.java:29`.
- **INV-RKT-18 [V]** `writeNetworkableNBT` omits `data`/`lastDimensionFrom`; a
  `RECIEVENBT` client rebuild pulls `data` separately over the `9987`/`RECIEVENBT`
  channels `EntityRocket.java:3162`,`:3221`.

## Failure modes & edge cases

- `plannedHarvestMb` is written in `writeMissionPersistentNBT`
  (`EntityStationDeployedRocket.java:585`) but never read back in
  `readMissionPersistentNBT` (`:679`) — dead persisted write (harmless: the entity
  `setDead`s right after computing it). (low).
- `EntityStationDeployedRocket.writeDataToNetwork` calls `super` at the top **and**
  again in the `else` branch, double-serialising the payload for any non-`MENU_CHANGE`
  id while the reader reads once → wire desync for inherited packets on this subclass
  `EntityStationDeployedRocket.java:525`,`:530`. (med).

## Config surface

None owned here; NBT/wire are contractual, not config-gated.

## Test coverage

`RocketFlightModeNbtTest` covers `flightMode` round-trip and back-compat
(`RocketFlightModeNbtTest.java:30,50,58,72`). FF NBT defaults (missing-key →
armed/hover/FA-on) are exercised indirectly by `FreeFlightCycleTest`. No direct
round-trip test asserts the full `EntityRocket` NBT table → open question.
