---
id: wirelessdata
owns: [wirelessdata/, tile/TileWirelessTransceiver.java]
entrypoints: [HandlerDataNetwork#tickAllNetworks, TileWirelessTransceiver#update, TileWirelessTransceiver#onLinkComplete]
depends-on: [api-public, util-core, network-wire, event-handlers]
depended-by: [satellite, blocks, event-handlers]
contracts: [C1, C2, C3, C5]
confidence: high
---

## Purpose

Wireless data transport for satellite-style `DataType` payloads (DISTANCE, HUMIDITY,
TEMPERATURE, COMPOSITION, ATMOSPHEREDENSITY, MASS). A `TileWirelessTransceiver` buffers
data pulled from / pushed to one adjacent wired `IDataHandler`, and transceivers sharing
a numeric **network id** exchange that buffer wirelessly through a server-global
`HandlerDataNetwork` scheduler. Networks are anonymous integer channels linked with the
libVulpes linker item; only the id and id-merge redirects persist.

## Responsibility boundary

Owns: the transceiver tile, the per-id `DataNetwork` transfer engine (priority bands +
fair split), the global `HandlerDataNetwork` scheduler/backoff, id allocation & redirect
resolution, and the `WirelessNetworkSavedData` overworld save blob.
Does NOT own: the `IDataHandler` / `DataType` / `DataStorage` API (api-public), the
`MultiData` buffer serializer (util-core), `ModuleWirelessBufferBar` GUI (inventory), the
two Forge event handlers that drive registration & ticking (event-handlers), or the
`PacketMachine` wire framing (network-wire / libVulpes).

## Key types

| type | role |
|------|------|
| `TileWirelessTransceiver` | tile: buffer, mode/enabled/priority state, GUI, wired hop, network (de)registration |
| `HandlerDataNetwork` | the server's manager (one per server, a field of `ServerState`): id allocation, redirect resolution, per-network scheduling & backoff |
| `DataNetwork` | one channel: source/sink membership, priority-band selection, fair budget allocation, commit |
| `ServerState.wirelessNetworks(world)` | there is no static registry; it builds and holds the server's `HandlerDataNetwork` over the overworld's saved data (`ServerState.java:66,99-104`) |
| `WirelessNetworkSavedData` | `WorldSavedData` on overworld: persists `nextNetworkId` + redirect map only |
| `DataNetwork.EndpointRef` | membership entry: tile + side + priority (identity by `BlockPos`) |
| `DataNetwork.EndpointOffer` | per-tick snapshot: handler + side + priority + dry-run offered amount |

## Mechanics

- **MECH-WDT-01 Linker pairing / id merge** — `onLinkComplete` resolves the two-endpoint
  case matrix: both unlinked → fresh shared id; one linked → the other inherits it (with a
  fresh id minted when only the *first* endpoint was already linked); both linked & differing
  → the target adopts this tile's id. Then both `joinNetwork` and block-sync.
  `TileWirelessTransceiver.java:344-399`. [V]
- **MECH-WDT-02 Wired hop (tile `update`)** — every `transferIntervalTicks` (phase-staggered
  by `pos.toLong()`), the enabled tile moves each `DataType` between its buffer and the single
  `IDataHandler` on the block's back face (`resolveTransferFacing`), direction chosen by
  `extractMode`; never transfers to another transceiver. `TileWirelessTransceiver.java:586-636`,
  `226-239`. [V]
- **MECH-WDT-03 Network registration lifecycle** — `joinNetwork` adds this tile as source
  (extract) or sink (inject) on `EnumFacing.UP`; `leaveNetwork` removes it and prunes the
  network if empty. Driven by `onLoad` (rejoin if linked), `invalidate`/`onChunkUnload`
  (leave), mode flip and priority change. `TileWirelessTransceiver.java:255-283,401-436`. [V]
- **MECH-WDT-04 Scheduler tick + backoff** — `tickAllNetworks` (server tick END) advances
  `schedulerTick`, syncs each network's eligibility, skips one-sided networks, and ticks a
  network only when its idle-age interval bucket (ACTIVE 10 / IDLE 20 / COLD 100 ticks) is
  phase-aligned by `(schedulerTick + networkId) % interval`. Budget passed to `tick` equals
  the interval so mean throughput stays ~1/tick. A successful move resets idle age.
  `HandlerDataNetwork.java:107-162`. [V]
- **MECH-WDT-05 Priority-band transfer** — per `DataType`, `DataNetwork.tick` dry-runs every
  sink/source, keeps only the single highest-priority band with non-zero capacity on each
  side, fair-splits the min(demand,supply) into that band (round-robin remainder via
  fair cursors), then commits `addData`/`extractData`; commit shortfalls re-trim the paired
  side so inserted==extracted. `DataNetwork.java:134-235,275-436`. [V]
- **MECH-WDT-06 Id allocation & redirect resolution** — `getNewNetworkID()` mints an id
  unused by both `networks` and `redirects`; `getNewNetworkID(int)` reuses/creates a network
  for a persisted id; `resolveNetworkID` follows the redirect chain to its terminus with a
  visited-set cycle guard and path-compresses the result. `HandlerDataNetwork.java:38-172`. [V]

## State & persistence

Per-tile keys (`TileWirelessTransceiver.writeToNBT`, also the update-packet payload) and the global keys of
`WirelessNetworkSavedData` (overworld `getPerWorldStorage`, name `stellurgyWirelessNetworks`): see `C1-nbt-persistence`
(wirelessdata). Notes: an unlinked tile's sentinel is `-1`, but a missing `networkID` reads 0; `priority` is guarded by `hasKey`
(default `DEFAULT_PRIORITY`); per-`DataType` buffer contents are `MultiData` subtags keyed by `DataType.name()`; `nextNetworkId`
is clamped `max(1, …)` on read and write, and redirect keys are stringified ints.

NOT persisted: `DataNetwork` source/sink membership (rebuilt from tile `onLoad`),
`schedulerTick`, `phase`, idle-age tracking, fair cursors. `state` appears only as a
transient wire field in `readDataFromNetwork` (never written to disk).

## Invariants

- **INV-WDT-01 [V][SYS]** `-1` is the unlinked sentinel; a tile is a network member iff
  `networkID != -1`. `TileWirelessTransceiver.java:49,135,241` FOR: INV-WDT-02.
- **INV-WDT-02 [V][BEH]** A freshly paired pair shares one non-sentinel id, both registered on that
  network. `TileWirelessTransceiver.java:366-370,387-388`.
- **INV-WDT-03 [V][BEH]** Merging two already-linked transceivers collapses them to a single shared
  id. `TileWirelessTransceiver.java:382-385`.
- **INV-WDT-04 [V][BEH]** Extract mode ⇒ registered as source only; inject ⇒ sink only; a mode flip
  swaps and clears the prior role. `TileWirelessTransceiver.java:272-281` (`removeFromAll`
  then the one add).
- **INV-WDT-05 [A][BEH]** `mode`, `enabled`, `networkID` survive an NBT round-trip and the tile
  re-registers its role on `onLoad`. `WirelessTransceiverRestartTest.java:75-109` Pinned by `WirelessTransceiverRestartTest#modeEnabledAndNetworkIdSurviveRestartWithRoleReRegistration`.
- **INV-WDT-06 [V]** Server-authoritative writes: GUI/`useNetworkData` mutations apply only when
  `side.isServer()`. `TileWirelessTransceiver.java:536`
- **INV-WDT-07 [V][BEH]** Within a `DataType` tick, committed inserted == committed extracted (paired
  re-trim). `DataNetwork.java:203-231`
- **INV-WDT-08 [V][SYS]** Redirect resolution terminates even on a corrupt cycle (visited-set guard,
  returns original id). `HandlerDataNetwork.java:59-92` FOR: INV-WDT-03.
- **INV-WDT-09 [V][BEH]** Only the single highest priority band with capacity transfers on each side
  per type; lower bands are inert while it is active. `DataNetwork.java:158-166,275-289`
- **INV-WDT-10 [V][BEH]** Disabled tile offers/accepts zero (`addData`/`extractData` early-return),
  so a disabled member stays registered but inert. `TileWirelessTransceiver.java:562-583`
- **INV-WDT-11 [V]** `HandlerDataNetwork` exists only server-side: it is a field of `ServerState`
  (the client has none) and every tile path that reaches it returns early on `world.isRemote`
  (`TileWirelessTransceiver.java:241,255`). `ServerState.java:66,99-104`. It cannot be shared
  across two integrated-server sessions: the manager belongs to the server's `ServerState`, built
  lazily on first ask and dropped with it — there is no `clear()` any more because there is no
  static to clear. `TileWirelessTransceiver.java:154-156`

## Failure modes & edge cases

- Wired hop and wireless hop are independent stages; the tile buffer is the shared handoff
  point. No conflict, but a full buffer stalls extract-mode wired intake silently.
- The tile's `nets()` cannot meet an unregistered handler: `ServerState.wirelessNetworks(world)`
  builds it on first ask from whichever world asks (the overworld's saved data either way), so there is no
  "non-overworld load order / cleared registry ⇒ no-op `join/leave`" window
  (`ServerState.java:99-104`). A tile still returns `null` / skips for an unlinked id or on the client
  (`TileWirelessTransceiver.java:241,255`).
- `removeIfEmpty` is the only network reaper; a network whose members all unload without
  `onChunkUnload`/`invalidate` firing would linger in the `networks` map until re-resolved.
- Fair-split remainder ordering depends on runtime `sinkFairCursor`/`sourceFairCursor`, which
  reset to 0 on server restart (throughput fairness, not correctness).

## Integration seams

- **Events (C5):** `WirelessNetworkRegistryHandler` (WorldEvent.Load, dim 0 only) brings the server's
  handler up (there is no unload clear); `WirelessDataTickHandler` (ServerTickEvent END) calls
  `ServerState.tickWirelessNetworks` → `tickAllNetworks`. Both live in event-handlers.
- **Packets (C2):** GUI edits ride libVulpes `PacketMachine` — ids `PACKET_MODE=0`,
  `PACKET_ENABLED=1`, `PACKET_PRIORITY=2` via `writeDataToNetwork`/`readDataFromNetwork`/
  `useNetworkData`. Block/state sync via vanilla `SPacketUpdateTileEntity`
  (`getUpdatePacket`). No subsystem-owned custom packet.
- **Registry (C3):** block/tile registered as `wirelessTransceiver` (`Stellurgy.java:662,836`).
- **Capabilities/interfaces:** implements libVulpes `ILinkableTile`, `INetworkMachine`,
  `IModularInventory`, `IToggleButton`, `IGuiCallback`, and Stellurgy `IDataHandler`.
- **Harness:** `/stellurgytest` probes `wireless-role-on-network`, pair/info verbs
  (`TestProbeCommand.java:3681,3762,3832`) back the contract tests.

## Config surface

None. No `StellurgyConfiguration` flag gates this subsystem (seam-config empty for these files).
Transfer interval (20), buffer capacity (100), backoff intervals/thresholds and default
priority (0) are `tunable` compile-time constants, not pinned.

## Test coverage

| invariant | test |
|-----------|------|
| INV-WDT-02..04 | none — `[V]` above |
| INV-WDT-05 | `WirelessTransceiverRestartTest#modeEnabledAndNetworkIdSurviveRestartWithRoleReRegistration:75` |
| transmit smoke | none — `PipeNetworkSmokeTest` pins only `forgeEnergyStorageContractMatches` (`PipeNetworkSmokeTest.java:27`) |

## Open questions

- Whether any code path calls `getNewNetworkID(int)` with an id already present as a redirect
  *key* (would create a network the redirect then shadows). Not observed in read paths;
  a possible SSOT overlap, unconfirmed.
- Exact `DataStorage.addData/extractData` commit semantics under a partially-full lane are in
  api-public; the paired re-trim (INV-WDT-07) assumes commit never exceeds the dry-run offer.
