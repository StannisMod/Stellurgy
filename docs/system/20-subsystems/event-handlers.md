---
id: event-handlers
owns: [event/]
entrypoints: [PlanetEventHandler (EVENT_BUS + ORE_GEN_BUS), RocketEventHandler (client EVENT_BUS), EntityEventHandler, AcidRainHandler, WirelessDataTickHandler, WirelessNetworkRegistryHandler, WorldEvents (ForgeChunkManager callback)]
depends-on: [dimension-planets, atmosphere-oxygen, space-stations, network-wire, wirelessdata, api-public, advancements, client-render]
depended-by: [multiblock-machines]
contracts: [C1, C2, C4, C5]
confidence: high
---

## Purpose
Central home for Forge `@SubscribeEvent` handlers that graft Stellurgy behaviour onto
vanilla/Forge lifecycle hooks: mob-spawn gating, ore-gen suppression, low-gravity falls, oxygen
combustion rules, acid rain, per-tick dimension/satellite/network driving, login state sync,
world save/load atmosphere registration, and the rocket/free-flight HUD. Handlers hold almost no
state of their own; each translates one Forge event into a call on the owning subsystem.

## Responsibility boundary
Owns: the `event/` package — 6 registered handler objects, 1 custom bus event (`BlockBreakEvent`),
1 `ForgeChunkManager.LoadingCallback` (`WorldEvents`). Also the "delayed warp transition"
queue, held per server in `PlanetEventHandler.ServerPart` (`ServerState.planetEvents`, `ServerState.java`), and the client HUD render for rockets/free-flight.
Does NOT own: the subsystems it calls (dimensions, atmosphere, stations, wireless, advancements),
the packets it dispatches (network-wire), or the config values it reads (api-public). Other
`@SubscribeEvent` classes live outside this package (e.g. `world.weather.PlanetWeatherEventHandler`,
`InputSyncHandler`, `MapGenLander`) and belong to their own subsystems.

## Key types

The handler classes (`PlanetEventHandler`, `RocketEventHandler`, `EntityEventHandler`, `AcidRainHandler`,
`WirelessDataTickHandler`, `WirelessNetworkRegistryHandler`) and what each subscribes to are inventoried in
`C5-capabilities-events`; this doc owns what each handler does (MECH-EVT-*). Not in C5:

| class | role |
|-------|------|
| `WorldEvents` | `LoadingCallback` for forced chunks — body is **empty** (`:12`) |
| `BlockBreakEvent` / `.LaserBreakEvent` | `@Cancelable` bus event fired by the orbital laser drill (`MiningDrill.java:43`) |

## Mechanics
- **MECH-EVT-01** mob-spawn gating: `CheckSpawn`/`PotentialSpawns` DENY hostiles not atmosphere-immune and add the planet's custom spawn list (non-monster only) — `PlanetEventHandler.java:158,172`.
- **MECH-EVT-02** ore-gen suppression: on a `WorldProviderPlanet` with custom oregen, vanilla COAL/DIAMOND/…/CUSTOM generation is DENYed so only Stellurgy oregen runs — `:184` (on `ORE_GEN_BUS`).
- **MECH-EVT-03** low-gravity falls: fall distance is scaled by the planet's `getGravitationalMultiplier` before damage — `:614`.
- **MECH-EVT-04** off-station eviction: a player in `spaceDimId` not on any station and not riding a rocket is teleported to a station spawn, else back to dim 0 via `BasicTeleporter` — at the END of the space world's `WorldTickEvent`, `spaceDimensionGuard` → `evictIfOffStation`, `PlanetEventHandler.java:243,254` (not run from `playerTick`'s `LivingUpdateEvent` — see INV-EVT-07).
- **MECH-EVT-05** misc player tick: refill air to 300 for LOWOXYGEN-immune entities in water; trigger `WENT_TO_THE_MOON` near a hardcoded Luna coord — `:217,223`.
- **MECH-EVT-06** sleep gating: `PlayerSleepInBedEvent` → `OTHER_PROBLEM` on a planet whose atmosphere at the bed is not breathable, unless `forcePlayerRespawnInSpace` — `:282`.
- **MECH-EVT-07** combustion suppression: in a non-combustible atmosphere, torch placement is swapped to `blockUnlitTorch`, configured `torchBlocks` placement is cancelled, and flint&steel/fire-charge/blaze right-clicks are cancelled — `:307,324`.
- **MECH-EVT-08** water-source lock: `CreateFluidSourceEvent` DENYed at positions in the dimension's `water_source_locked_positions` — `:479`.
- **MECH-EVT-09** server tick (END): `tickDimensions()`, increment the tick count of `ServerState.planetEvents`, and drain that part's delayed-transition list — each due `TransitionEntity` (due when its entity's world clock reaches `time`) is cross-dim `changeDimension`ed, stamped with `stellurgyRocketTransferGrace`, and re-mounted on its rocket — `:344,348`.
- **MECH-EVT-10** client tick (END): `view.dimensions.tickDimensionsClient()` on the current `ServerView`, in `ClientProxy.tickServerView` (not in this package any more) — `ClientProxy.java:387`.
- **MECH-EVT-11** login sync: on `ServerConnectionFromClientEvent`, dispatch (non-local only) `PacketConfigSync`, then `PacketStellarInfo` per star, `PacketDimInfo` per dimension, `PacketSpaceStationInfo` per station, then `PacketDimInfo(0)` — `:387`. (Also sent, local client included: `PacketAsteroidInfo` per asteroid type and `PacketKnownPlanets`, between the stations and `PacketDimInfo(0)` — `:408-410`.)
- **MECH-EVT-12** world load/unload: server-side `AtmosphereHandler.registerWorld`/`unregisterWorld` — the handler is a per-world part (`WorldRuntime`) of the world it was registered on, so registration is that world's own state and `unregisterWorld` releases it — plus `TemplateImporter.importIfNeeded` on load; client-side, if `skyOverride`, install `RenderPlanetarySky` — `:417,429`.
- **MECH-EVT-13** world save: on dim-0 `WorldEvent.Save`, persist all dimensions to `DimensionManager.workingPath` — `:600`.
- **MECH-EVT-14** chunk load: enqueue the chunk into the dimension's terraforming list (no-op unless a terraform helper exists for that dim) — `:531`.
- **MECH-EVT-15** fog override (client): `FogColors` sets planet fog color (black at zero atmosphere) and animates a warp burst — the burst is a `WorldRuntime` part of the client world it was started in, stamped with that world's clock (`runBurst`); `RenderFogEvent` sets linear fog range from atmosphere density, the helmet module and the pressure reported for that world (`ClientAtmosphere.of(world)`) — `:437,547`.
- **MECH-EVT-16** — retired: leaving a server is the release of the client's `ServerView` (dimension-planets MECH-DIM-23), and the warp flash is a part of the client world (MECH-EVT-15).
- **MECH-EVT-17** acid rain: server tick, every `acidRainDamageInterval` ticks, a player on an `acidicRain` planet under open sky without a full PROTECTIVEARMOR suit takes `acidRainDamage` — `AcidRainHandler.java:33`.
- **MECH-EVT-18** weather resync: on join / dimension change, MP players with a live connection are re-sent vanilla rain/thunder `SPacketChangeGameState` packets — `EntityEventHandler.java:14,41`.
- **MECH-EVT-19** rocket HUD (client): altitude/velocity/fuel instrument overlay, suffocation warning, O2 bar, module icons, and the Free-Flight HUD (bars, turn-rate/flight cursor) — `RocketEventHandler.java:166`.
- **MECH-EVT-20** free-flight camera: `CameraSetup` slerps the craft attitude quaternion and hard-locks camera yaw/pitch/roll each frame; `RenderSpecificHandEvent` cancels the held-item render while piloting — `:114,153`.
- **MECH-EVT-21** wireless tick: server tick END → `Stellurgy.serverState().tickWirelessNetworks()`, a no-op until the overworld has brought the networks up — `WirelessDataTickHandler.java:15`, `ServerState.java:107-111`.
- **MECH-EVT-22** wireless registry lifecycle: dim-0 world load builds the server's `HandlerDataNetwork` over the overworld's `WirelessNetworkSavedData` (`ServerState.wirelessNetworks(world)`); there is no unload clear — the networks are `ServerState` state, so they die with that server and the next server builds its own (the former static `wirelessdata/NetworkRegistry` is deleted) — `WirelessNetworkRegistryHandler.java:11-13`, `ServerState.java:99-104`.

## State & persistence
| key | owner | notes |
|-----|-------|-------|
| `stellurgyRocketTransferGrace` (long, entity `ForgeData`) | stamped through `RocketTransferGrace` (its owner); a PLAYER's window lives in his `IPlayerBindings` (`graceUntil`, C1), only a non-player keeps this key; read by AtmosphereNeedsSuit | post-warp-transfer suit-suffocation grace = `worldTime + 100` [V] |

Non-persisted server state: the server-tick counter and the delayed-warp list are fields of
`PlanetEventHandler.ServerPart`, held by `ServerState.planetEvents` (`PlanetEventHandler.java:81-104`,
`ServerState.java`) — written only by this handler's server tick, and dropped with the server (the
former statics `time` / `transitionMap` are gone). The oxygen handler is not a static map either:
`AtmosphereHandler` is a part of the world it serves (`WorldRuntime`), and `registerWorld` /
`unregisterWorld` (MECH-EVT-12) are that part's start and release. The warp-burst fade is the client world's `WarpFlash` part (`WorldRuntime`).
`RocketEventHandler` static fields
(`lastFreeFlightHud`, `maxCameraLockErrorDeg`) are client render/telemetry state only; the HUD panel
positions are not state at all — `client/HudLayout` computes them from the frame's screen size. The overlay message and the suffocation-warning
hold are NOT static: they are the client world's `HudState` part (`WorldRuntime`),
stamped with that world's clock and dropped with it.

## Invariants
- **INV-EVT-01** [V] `getDimensionProperties(int)` never returns null — it falls back to the manager's `getOverworldProperties()`/`getDefaultSpaceProperties()` (`DimensionManager.java:550-567`), so the many unguarded `getDimensionProperties(...).X` calls here cannot NPE on dim id alone.
- **INV-EVT-02** [V] all mutating handlers are server-guarded (`!world.isRemote`) or intrinsically server-side (`ServerTickEvent`, `WorldEvent.Save`); render/fog handlers carry `@SideOnly(Side.CLIENT)` — `PlanetEventHandler.java:418,430,436,546`, `AcidRainHandler.java:37`.
- **INV-EVT-03** [V] login sync ordering is config → stars → dims → stations → dim-0, so the client has config and star data before dimension/station packets resolve against it — `:387-413`.
- **INV-EVT-04** [V][BEH] acid-rain damage requires ALL FOUR armor slots to carry `PROTECTIVEARMOR`; creative/spectator are exempt — `AcidRainHandler.java:71-84`.
- **INV-EVT-05** [V] weather-resync and acid-rain handlers null-check `player.connection` / world side before sending, tolerating FakePlayers — `EntityEventHandler.java:21,45`.
- **INV-EVT-07** [T][BEH] a server-side teleport of a PLAYER is never made from a handler that runs inside `NetHandlerPlayServer.update` — `LivingUpdateEvent` and `PlayerTickEvent` both do, via `player.onUpdateEntity()` (`:185`), and the very next statement (`:186`) writes the pre-tick position back, so the server keeps the old position until the client confirms and the handler re-fires every tick until then. The world tick runs before the network tick (`MinecraftServer.updateTimeLightAndEntities`: `onPostWorldTick` precedes `networkTick()`), so a teleport at `WorldTickEvent` END holds. MECH-EVT-04 obeys it; pinned by `SpaceDimGuardTest#registeredStationTeleportTargetsStationSpawn` ("ONCE per position", red-witnessed). **Not yet obeyed**: `SpaceObjectManager.onPlayerTick:270` (fell-out-of-the-world rescue) and its station-boundary `setPosition` bounce `:279-300` run from `PlayerTickEvent` — same shape, not measured.
- **INV-EVT-06** [A] the delayed-transition list is drained monotonically (each entity removed once its `time` elapses) and is a field of the per-server part, so it cannot outlive its server, nor grow unbounded under normal warp use — `PlanetEventHandler.java:348-380` (no test pins the drain).

## Failure modes & edge cases
- `WorldEvents.ticketsLoaded` is empty: forced-chunk tickets are not re-activated on load (see Open questions).
- MECH-EVT-04 selects the **farthest** station spawn (`distanceTo > distance`, seeded 0), not the nearest — a player evicted from space is sent to the most distant station.
- MECH-EVT-05 hardcodes planet name `"Luna"` and coord `(2347,80,67)` for the moon advancement.
- MECH-EVT-14 runs for every chunk load in every world; the guard against wasted work lives inside `add_chunk_to_terraforming_list` (helper-exists check), not at the event.

## Integration seams
- **Events (C5):** primary owner of the C5 Forge-event surface — subscribes `LivingSpawnEvent.CheckSpawn`, `WorldEvent.PotentialSpawns`, `OreGenEvent.GenerateMinable` (ORE_GEN_BUS), `LivingUpdateEvent`, `PlayerSleepInBedEvent`, `PlaceEvent`, `RightClickBlock`, `ClientDisconnectionFromServerEvent`, `ServerTickEvent`, `ClientTickEvent`, `ServerConnectionFromClientEvent`, `WorldEvent.Load/Unload/Save`, `EntityViewRenderEvent.FogColors/RenderFogEvent/CameraSetup`, `BlockEvent.CreateFluidSourceEvent`, `ChunkEvent.Load`, `LivingFallEvent`, `RenderGameOverlayEvent.Post`, `RenderSpecificHandEvent`, `PlayerEvent.ItemCraftedEvent`, `PlayerChangedDimensionEvent`, `EntityJoinWorldEvent`. Defines the `BlockBreakEvent.LaserBreakEvent` bus event (consumer/fire: `MiningDrill.java:43`).
- **Packets (C2):** dispatches (does not register) `PacketConfigSync`, `PacketStellarInfo`, `PacketDimInfo`, `PacketSpaceStationInfo` at login; resends vanilla `SPacketChangeGameState`.
- **Capability (C5):** reads `CapabilitySpaceArmor.PROTECTIVEARMOR` (acid rain) and libVulpes `IModularArmor`/`IFillableArmor` (HUD/fog).
- **Callbacks:** `WorldEvents` registered via `ForgeChunkManager.setForcedChunkLoadingCallback` (`Stellurgy.java:1164`).

## Config surface

Config: see `C4-config-surface`.

## Test coverage
No dedicated `event/` unit tests found; behaviour is exercised indirectly by the `/stellurgytest` harness
(free-flight HUD/camera fields `lastFreeFlightHud`, `maxCameraLockErrorDeg`, `ffClient*` are read
reflectively by client e2e — `RocketEventHandler.java:57-82`). INV-EVT-01..06 are code-verified/assumed,
none test-pinned.

## Open questions
- `WorldEvents.ticketsLoaded` empty (`:12`): are Stellurgy forced chunks meant to survive reload, or is ticket-less loading intentional?
- MECH-EVT-04 farthest-station selection: intended, or should it evict to the nearest station?
