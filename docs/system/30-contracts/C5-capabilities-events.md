---
id: C5-capabilities-events
covers: [capability, event-handlers, atmosphere-oxygen, space-stations, infrastructure-tiles, client-render, integration-modcompat, integration-jei, world-gen, blocks, items, recipe]
confidence: high
---

## Purpose

Cross-cutting inventory of the Forge **capabilities** Stellurgy declares (tabled below) and the full
**Forge `@SubscribeEvent` surface** (the tables below do not list every subscription — see the grep basis). Per-handler behaviour is
owned by the subsystem docs (MECH-EVT-* in `event-handlers.md`, MECH-CAP-* in `capability.md`, plus
atmosphere/stations/tiles/client docs); this contract is the consolidated join table + collision
audit. No behaviour is re-derived here — rows cite the owning anchor or `file:line`.

Grep basis: `grep -rn @SubscribeEvent src/main/java`. The tables below enumerate the audited
subscriptions and B.10's, not every one on disk: a subscription absent from them is UNLISTED, not
non-existent, and closing the gap is a re-audit rather than an edit. Registration sites: `EVENT_BUS.register`
in `Stellurgy.java`, `ClientProxy.java`, per-tile constructors, `AtmosphereHandler.registerWorld`
(per world — the handler is registered when its world loads and unregistered when that world
unloads), plus 3 `@Mod.EventBusSubscriber` classes.

---

### `CapabilityDamageAware.DAMAGE_AWARE` — a unit's willingness to be told what broke it

| | |
|---|---|
| interface | `IDamageAware`, one method `onDamage(DamageOccurrence)`, returns nothing |
| registered | `Stellurgy` init, beside `CapabilityWear.register()` |
| storage | **none, deliberately** — an occurrence is news, not state, and has no persistent form. A unit that turns one into durable state persists that in its own tile |
| default factory | `DeafUnit`, which hears and does nothing. Required by `CapabilityManager.register`; a unit with no reaction should carry no capability at all rather than attach one |
| who calls it | `ShipDamageService`, server side only, after the stage is written |
| carried by | nothing in production yet — enrolling a unit means designing that unit's own consequence. The harness attaches one to every tile, which is also the route a foreign mod takes |

### `WorldRuntime` — state a world owns for as long as the world object exists

| | |
|---|---|
| interface | the class itself (`world/WorldRuntime.java`): `of(World, Class<T>, Supplier<T>)` hands each subsystem its own PART, keyed by the part's class; it knows none of them `[V]` |
| registered | `WorldRuntime.register()` in `Stellurgy.preInit` — before the first world is built `[V]` |
| attached | `AttachCapabilitiesEvent<World>` to EVERY world, server and client, under `stellurgy:world_runtime`, by `WorldRuntime.Attach` (registered on the bus in `preInit`) `[V]` |
| storage | **none, by definition** — what must survive a restart belongs in the save. The part map is concurrent: the VS physics thread reads one `[V]` |
| parts today | server world: `TileEntityFieldGenerator.WorldEmitters` (loaded emitters + last safe side per player), VS `ShipAltitudeBand`; client world: `RocketEventHandler.HudState` (overlay + suffocation-warning hold), `SkyLabels.ConsoleToggle`, `ClientAtmosphere` (the air reported at the player's position and the suffocation mark on that world's clock), `PlanetEventHandler.WarpFlash`, AFFS `ClientForceFieldRenderCache.Snapshot` and `ClientFieldTouchEffectCache.Effects` (a packet for another dimension than the one shown is dropped) `[V]` |
| why | a world is the owner of anything addressed by it; held anywhere longer-lived such state outlived its world into the next one a single-player client opened |

### `PilotInput` — the raw input of the local player

| | |
|---|---|
| interface | the class itself (`client/PilotInput.java`): mouse motion not yet consumed, the camera-pin baseline, the engine-start hold, the deck-frame look, the space-key edge `[V]` |
| registered | `PilotInput.register()` in `ClientProxy.preinit` `[V]` |
| attached | `AttachCapabilitiesEvent<Entity>` to every `EntityPlayerSP`, under `stellurgy:pilot_input`, by `ClientProxy.attachPilotInput` `[V]` |
| storage | none — Minecraft builds a new `EntityPlayerSP` on every dimension change and respawn and copies no capability, so the input starts over with the body: the pin is invalid and the first tick reads a zero delta, as when a pilot sits down `[V]` |
| beside it | what the pilot COMMANDS is the craft's (`PilotCommand` on `EntityRocket` and on the client `TileAdvancedFlightComputer`); the ship's attitude is read off the ship (`VSIntegration.getShipAttitude(world, pos, partialTicks)`), never mirrored `[V]` |

### `CapabilityPlayerBindings.PLAYER_BINDINGS` — what the mod durably holds on a player

| | |
|---|---|
| interface | `IPlayerBindings` (`player/`): the aboard record and the rocket-transfer grace deadline — the two bindings C13 PRES-10/11 rule durable |
| registered | `CapabilityManager.INSTANCE.register` in `CapabilityPlayerBindings` (`player/CapabilityPlayerBindings.java`) `[V]` |
| attached | `AttachCapabilitiesEvent<Entity>` to every `EntityPlayer`, under `stellurgy:player_bindings` `[V]` |
| storage | **real, unlike this mod's other capabilities** — a player has no host NBT of its own for these, so `PlayerBindings.serializeNBT` is the only thing that puts them on disk (keys: C1) `[V]` |
| death | copied on `PlayerEvent.Clone` (`CapabilityPlayerBindings.carryAcrossDeath`), because Forge copies no capability across a respawn `[V]` |
| who calls it | `ShipAboardTag.of/stamp/clear`, `RocketTransferGrace`, and `PlayerRelease` (which lets go of these and of the three live bindings) `[V]` |

### `ClientWorldDrawings.DRAWINGS` — what a client world is drawing of the war

| | |
|---|---|
| interface | the concrete class `client/ClientWorldDrawings` (itself the provider): one `ClientBeamTracker` and one `ClientShotTracker` |
| registered | `ClientWorldDrawings.register()` from `ClientProxy.registerEventHandlers` — CLIENT side only; the injected holder stays null on a dedicated server `[V]` |
| attached | `AttachCapabilitiesEvent<World>` to every world with `isRemote`, under `stellurgy:client_drawings` `[V]` |
| storage | **none, deliberately** — a drawing is a picture of this session's packets; the provider is not serializable `[V]` |
| who calls it | `RenderShots`/`RenderBeams` via `ClientWorldDrawings.of(mc.world)`; `PacketShotSpawn`/`PacketShotEnd`/`PacketBeamState` via `ClientWorldDrawings.apply`, from `executeClient` — already on the client thread, queued there by the channel (`BasePacket.BasePacketHandlerClient`); `apply` reads `mc.world` at that moment `[V]` |

**Published events (server bus, not cancellable unless stated)**, all in
`api/event/`: `ShipEvent.{FlightComputerLiveEvent, FlightModelChangedEvent}` (the flight computer's
own edges — NOT the lifecycle, which is B.10),
`ShipCrossingEvent.{LeftCell, EnteredCell, TransitBegan, TransitEnded}`
(extending `Departure`/`Arrival`) and `.{LeftPlanet, EnteredPlanet}` (extending `PlanetTrip`, NOT
`Departure`/`Arrival`), `ShipCollisionEvent` (carries the two SUBSTRATE ids), and
`SpaceCellEvent.CollectPre` (**cancellable**). The javadoc on each class is the contract; this row
only routes to them. `[V]`


## Part A — Capabilities

Both capability **tokens, interfaces, `IStorage`, and default impl** live in `api-public`
(`api/capability`, `api/armor`); only the *provider/attach* side lives in the `capability` package
(owned by MECH-CAP-01..03 in `capability.md`). Both are registered at mod init
(`Stellurgy.java`).

| aspect | `IProtectiveArmor` | `IPartWear` |
|---|---|---|
| token field | `CapabilitySpaceArmor.PROTECTIVEARMOR` (`@CapabilityInject`, `CapabilitySpaceArmor.java`) | `CapabilityWear.PART_WEAR` (`@CapabilityInject`, `CapabilityWear.java`) |
| register call | `CapabilitySpaceArmor.register()` → `CapabilityManager.INSTANCE.register` (`CapabilitySpaceArmor.java`); invoked `Stellurgy.java` | `CapabilityWear.register()` (`CapabilityWear.java`); invoked `Stellurgy.java` |
| interface contract | `protectsFromSubstance(atmosphere, stack, commitProtection)` (`IProtectiveArmor.java`) | `getStage/getMaxStage/setStage/transition` (`IPartWear.java`); stage 0 = pristine, `maxStage` = broken (contractual convention) |
| `IStorage` | **no-op** — `writeNBT`→`null`, `readNBT` empty (`CapabilitySpaceArmor.java`) | **no-op** — same (`CapabilityWear.java`) |
| default impl passed to register | inline lambda `(atm,stack,commit)->false` (`CapabilitySpaceArmor.java`) — a do-nothing "never protects" default | `DefaultPartWear` static class (`CapabilityWear.java`) — plain int-backed store, `transition()` always returns `false` |
| provider / attach site | `AttachCapabilitiesEvent<ItemStack>` on `ItemSpaceArmor` stacks → the `Item` singleton is the provider (MECH-CAP-01, `CapabilityProtectiveArmor.java`, registered `Stellurgy.java`); resolution via `ItemSpaceArmor.getCapability` (`ItemSpaceArmor.java`) | provided **directly by the tile**: `TileWearable implements IPartWear`, exposes itself via `getCapability` (`TileWearable.java`); no `AttachCapabilitiesEvent` handler |
| consumers | acid rain (INV-EVT-04, `AcidRainHandler.java`), atmosphere suit check (atmosphere-oxygen) | `StorageChunk` (wear roll on landing, `StorageChunk.java`), `TileRocketServiceStation` (repair), `CapabilityWear.get(te)` helper (`CapabilityWear.java`), `TestProbeCommand.java` |
| persistence | never serialized — no-op storage; live view over item state (INV-CAP-08) | never serialized via capability; host tile `TileWearable` persists stage in its **own** NBT (`TileWearable`), storage no-op |

Key literal (contractual): the space-armor attach key is `stellurgy:ProtectiveArmor`
(INV-CAP-02, `CapabilityProtectiveArmor.java`). `PART_WEAR` uses no string key (tile-hosted).

**`IHeatPump` — a ship-heat capability.** Token
`CapabilityHeatPump.HEAT_PUMP`, registered from `Stellurgy.java` beside the others; no-op
`IStorage`; `DefaultHeatPump` lifts nothing so an unimplemented host is inert. Contract:
`getTemperatureLift()/payWork(long)` — the machine declares a LIFT, never an amount of heat, for the
same reason the emitter never names its own drain. Hosted directly by `TileHeatChiller`; no
`AttachCapabilitiesEvent` handler yet.

**`IHeatEmitter` — a ship-heat capability.** Token
`CapabilityHeatEmitter.HEAT_EMITTER` (`CapabilityHeatEmitter.java`), registered by
`CapabilityHeatEmitter.register()` from `Stellurgy.java`; no-op `IStorage`,
`DefaultHeatEmitter` as the backing store for a foreign host. Contract:
`getPendingHeat()/takeHeat(int)` — a DRAIN, not a rate, so a machine two coolant loops can reach has
its output split rather than counted twice (`IHeatEmitter.java`). Hosted directly by the tile
today (`TileLifeSupportPlant.java`); no `AttachCapabilitiesEvent` handler exists yet, and the
event is the intended route for a foreign machine.

**Why this one is a capability rather than a registry:** the machines the thermal system exists for belong to other mods, and a table
keyed by machine class can only ever be filled in by us. A capability can be declared by the donor.
It also gives ONE read path — `CapabilityHeatEmitter.get(te)` — for our machines and foreign ones
alike, which is what keeps `instanceof` out of the subsystem entirely.

---

## Part B — Forge event surface (audited subset — see the grep basis above)

Side legend: **S** = server-effective (guarded `!world.isRemote` or intrinsically server-side),
**C** = client-only (`@SideOnly(CLIENT)` and/or client-bus registration), **B** = both/registration
event (fires common). Bus: **F** = `MinecraftForge.EVENT_BUS`, **O** = `ORE_GEN_BUS`, **FML** =
`FMLCommonHandler` bus (mod), **ESub** = `@Mod.EventBusSubscriber` auto-register. Registry/model
events in 1.12.2 fire on the Forge bus.

### B.1 `event/` package — owner: event-handlers.md (registered `Stellurgy.java` / `ClientProxy.java`)

| Forge event | handler `class#method:line` | side | what it does |
|---|---|---|---|
| `LivingSpawnEvent.CheckSpawn` | `PlanetEventHandler#CheckSpawn` | S | mob-spawn gating — MECH-EVT-01 |
| `WorldEvent.PotentialSpawns` | `PlanetEventHandler#SpawnEntity` | S | inject planet spawn list — MECH-EVT-01 |
| `OreGenEvent.GenerateMinable` | `PlanetEventHandler#onWorldGen` | S (**O** bus) | vanilla oregen suppression — MECH-EVT-02 |
| `LivingFallEvent` | `PlanetEventHandler#fallEvent` | S | low-gravity fall scaling — MECH-EVT-03 |
| `LivingUpdateEvent` | `PlanetEventHandler#playerTick` | S | air refill + Luna advancement — MECH-EVT-05 |
| `PlayerSleepInBedEvent` | `PlanetEventHandler#sleepEvent` | S | sleep gating in unbreathable atm — MECH-EVT-06 |
| `PlayerContainerEvent` | `PlanetEventHandler#containerOpen` | — | **COMMENTED OUT** (`PlanetEventHandler.java`) — no subscription; the rocket-inventory bypass is not driven from here |
| `BlockEvent.PlaceEvent` | `PlanetEventHandler#blockPlacedEvent` | S | torch→unlit swap / placement cancel — MECH-EVT-07 |
| `PlayerInteractEvent.RightClickBlock` | `PlanetEventHandler#blockRightClicked` | S | fire-starter cancel in non-combustible atm — MECH-EVT-07 |
| `BlockEvent.CreateFluidSourceEvent` | `PlanetEventHandler#handleSourcePlacement` | S | water-source lock — MECH-EVT-08 |
| `TickEvent.ServerTickEvent` | `PlanetEventHandler#tick` | S | `tickDimensions` + the per-server delayed-warp list drain (`ServerState.planetEvents`) — MECH-EVT-09 |
| `TickEvent.WorldTickEvent` | `PlanetEventHandler#serverTickEvent` | S | per-world driving (the body is commented out) |
| `TickEvent.WorldTickEvent` | `PlanetEventHandler#spaceDimensionGuard` | S | phase END, space dim only — off-station eviction MECH-EVT-04; here and not in a living update by INV-EVT-07 |
| `TickEvent.ClientTickEvent` | `ClientProxy#tickServerView` | C | `tickDimensionsClient` on the current `ServerView` — MECH-EVT-10 |
| `ServerConnectionFromClientEvent` | `PlanetEventHandler#playerLoggedInEvent` | S | login sync config→stars→dims→stations — MECH-EVT-11 / INV-EVT-03 |
| `WorldEvent.Load` | `PlanetEventHandler#worldLoadEvent` | S+C | atmosphere register (a part of that world) / sky override — MECH-EVT-12 |
| `WorldEvent.Unload` | `PlanetEventHandler#worldUnloadEvent` | S | atmosphere unregister (releases that world's handler) — MECH-EVT-12 |
| `WorldEvent.Save` | `PlanetEventHandler#worldSaveEvent` | S | persist all dimensions — MECH-EVT-13 |
| `ChunkEvent.Load` | `PlanetEventHandler#onChunkLoad` | S | enqueue terraform chunk — MECH-EVT-14 |
| `ChunkEvent.Load` | `HeatNetworkWatcher#onChunkLoad` | S | invalidate a coolant loop's cached machines — a machine is not a network node, so nothing else reports that one arrived with its chunk (MECH-HEAT-02c) |
| `EntityViewRenderEvent.FogColors` | `PlanetEventHandler#fogColor` | C | planet fog color + warp burst — MECH-EVT-15 |
| `EntityViewRenderEvent.RenderFogEvent` | `PlanetEventHandler#fogColor` | C | fog range from atm density — MECH-EVT-15 |
| `FMLNetworkEvent.ClientConnectedToServerEvent` | `ClientProxy#viewConnection` | C (netty thread) | build the client's `ServerView` of the server just connected to |
| `WorldEvent.Unload` | `ClientProxy#releaseLeftServer` | C | a client world unloaded with its connection closed: drop the `ServerView`, withdrawing a remote server's dimension registrations (MECH-EVT-16 is retired) |
| `TickEvent.ClientTickEvent` | `ClientProxy#tickServerView` | C | advance the view's space-clock copy and its galaxy's orbits |
| `AttachCapabilitiesEvent<Entity>` | `ClientProxy#attachPilotInput` | C | `PilotInput` on the local player |
| `PlayerEvent.ItemCraftedEvent` | `PlanetEventHandler#onCrafting` | S | crafting hook |
| `LivingUpdateEvent` | `AcidRainHandler#playerTick` | S | acid-rain damage — MECH-EVT-17 / INV-EVT-04 |
| `EntityJoinWorldEvent` | `EntityEventHandler#onJoinWorld` | S | weather-packet resync — MECH-EVT-18 |
| `PlayerEvent.PlayerChangedDimensionEvent` | `EntityEventHandler#onPlayerChangedDimension` | S | weather-packet resync — MECH-EVT-18 |
| `RenderGameOverlayEvent.Post` | `RocketEventHandler#onScreenRender` | C | rocket + free-flight HUD — MECH-EVT-19 |
| `EntityViewRenderEvent.CameraSetup` | `RocketEventHandler#onFreeFlightCameraSetup` | C | free-flight camera lock — MECH-EVT-20 |
| `RenderSpecificHandEvent` | `RocketEventHandler#onFreeFlightRenderHand` | C | cancel held-item render while piloting — MECH-EVT-20 |
| `PlayerEvent.PlayerChangedDimensionEvent` | `RocketEventHandler#playerTeleportEvent` | C | HUD/free-flight dim-change reset |
| `TickEvent.ServerTickEvent` | `WirelessDataTickHandler#onServerTick` | S | `ServerState.tickWirelessNetworks()` → `tickAllNetworks()` — MECH-EVT-21 |
| `WorldEvent.Load` | `WirelessNetworkRegistryHandler#onWorldLoad` | S | dim-0: brings the server's data network up (`ServerState.wirelessNetworks`) — MECH-EVT-22 |

### B.2 `atmosphere/` — owner: atmosphere-oxygen.md (registered per-dimension `AtmosphereHandler.java`)

| Forge event | handler | side | what it does |
|---|---|---|---|
| `LivingUpdateEvent` | `AtmosphereHandler#onTick` | S | per-tick respiration, suit/oxygen suffocation and the periodic readout send |

`AtmosphereHandler` subscribes only to `LivingUpdateEvent`: it keeps no per-player cache, because the
readout sync is periodic (C2 `PacketAtmSync`), so nothing needs evicting on a dimension change or a
logout.

### B.3 `stations/` — owner: space-stations / satellite (registered `Stellurgy.java`)

| Forge event | handler | side | what it does |
|---|---|---|---|
| `TickEvent.PlayerTickEvent` | `SpaceObjectManager#onPlayerTick` | S | per-player station bookkeeping |
| `TickEvent.ServerTickEvent` | `SpaceObjectManager#onServerTick` | S | station/manager server tick |
| `PlayerEvent.PlayerChangedDimensionEvent` | `SpaceObjectManager#onPlayerTransition` | S | station transfer on dim change |

### B.4 Tiles — owner: infrastructure-tiles / rocket-assembly (self-register in `onLoad`, `registeredBus` guard)

These subscribe **libVulpes `RocketEvent`** subtypes (Stellurgy-custom events, **not Forge**). Listed for
completeness. Each registers when it is added to a world and unregisters on whichever comes first of
`invalidate`, `onChunkUnload` and its own world's `WorldEvent.Unload` (`TileLandingPad.java`,
`TileRocketMonitoringStation.java`, `TileRocketAssemblingMachine.java`).

| event (libVulpes) | handler | side | what it does |
|---|---|---|---|
| `RocketEvent.RocketPreLaunchEvent` | `TileRocketMonitoringStation#onPreLaunch` | S | monitoring station status |
| `RocketEvent.RocketLaunchEvent` | `TileRocketMonitoringStation#onLaunch` | S | monitoring status |
| `RocketEvent.RocketReachesOrbitEvent` | `TileRocketMonitoringStation#onOrbit` | S | monitoring status |
| `RocketEvent.RocketDeOrbitingEvent` | `TileRocketMonitoringStation#onDeorbit` | S | monitoring status |
| `RocketEvent.RocketLandedEvent` | `TileRocketMonitoringStation#onLanded` | S | monitoring status |
| `RocketEvent.RocketAbortEvent` | `TileRocketMonitoringStation#onAbort` | S | monitoring status |
| `RocketLandedEvent` | `TileLandingPad#onRocketLand` | S+C | pad claims a rocket landed in ITS world |
| `RocketPreLaunchEvent` | `TileLandingPad#onRocketLaunch` | S+C | pad pre-launch hook, its world only |
| `RocketDismantleEvent` | `TileLandingPad#onRocketDismantle` | S | pad dismantle hook, its world only |
| `RocketLandedEvent` | `TileRocketAssemblingMachine#onRocketLand` | S | assembler reclaim on landing |

### B.5 Client render/input — owner: client-render (registered `ClientProxy.java` / `@Mod.EventBusSubscriber`)

| Forge event | handler | side | what it does |
|---|---|---|---|
| `RenderWorldLastEvent` | `DelayedParticleRenderingEventHandler#onRenderWorldLast` | C | deferred particle render |
| `TickEvent.ClientTickEvent` | `DelayedParticleRenderingEventHandler#onTick` | C | particle queue tick |
| `WorldEvent.Unload` | `DelayedParticleRenderingEventHandler#onWorldUnload` | C | clear particle queue |
| `RenderPlayerEvent.Post` | `RenderComponents#renderPostSpecial` | C | post-player component render |
| `TickEvent.ClientTickEvent` | `KeyBindings#onClientTick` | C | keybind polling |
| `InputEvent.KeyInputEvent` | `KeyBindings#onKeyInput` | C | key input handling |
| `GuiScreenEvent.MouseInputEvent.Pre` | `ModuleContainerPanYOnlyWithScrollCache.WheelRouter#onMouseInputPre` | C | container scroll (one instance registered `ClientProxy.java`) |
| `ModelBakeEvent` | `ClientProxy#modelBakeEvent` | C | model bake hook |
| `ModelRegistryEvent` | `TooltipInjector#onModels` | C (**ESub**) | model registration (`@Mod.EventBusSubscriber(CLIENT)`) |
| `ItemTooltipEvent` | `TooltipInjector#onTooltip` | C (**ESub**) | tooltip injection |

### B.6 Integration — owner: integration-modcompat / integration-jei (conditional register `Stellurgy.java` / JEI plugin)

| Forge event | handler | side | what it does |
|---|---|---|---|
| `GCCoreOxygenSuffocationEvent.Pre` (Galacticraft, **foreign**) | `GalacticCraftHandler#GCSuffocationEvent` | S | cancel GC suffocation when Stellurgy air present (gated on `overrideGCAir` + `galacticraftcore` loaded) |
| `TickEvent.RenderTickEvent` | `GalacticCraftHandler#tickFixAnnoyingOverlay` | C | suppress GC overlay (registered on FML bus, client only) |
| `TickEvent.ClientTickEvent` | `JeiClientTickHandler#onClientTick` | C | JEI client tick |

### B.7 World-gen / weather — owner: world-gen / dimension-planets (registered `Stellurgy.java`)

| Forge event | handler | side | what it does |
|---|---|---|---|
| `PopulateChunkEvent.Post` | `MapGenLander#populateChunkPostEvent` | S | place crashed-lander decoration |
| `CommandEvent` | `PlanetWeatherEventHandler#redirectWeatherCommand` | S | redirect `/weather` to Stellurgy planet weather |
| `WorldEvent.Load` | `PlanetWeatherEventHandler#onWorldLoad` | S | init planet weather state |
| `PlayerEvent.PlayerLoggedInEvent` | `PlanetWeatherEventHandler#onPlayerLogin` | S | sync weather to joining player |
| `PlayerEvent.PlayerChangedDimensionEvent` | `PlanetWeatherEventHandler#onPlayerChangedDimension` | S | weather sync on dim change |
| `PlayerEvent.PlayerRespawnEvent` | `PlanetWeatherEventHandler#onPlayerRespawn` | S | weather sync on respawn |

### B.8 Registration events — owner: blocks / items / recipe / world-gen (via `EVENT_BUS.register(this)` `Stellurgy.java`, or `@Mod.EventBusSubscriber`)

| Forge event | handler | side | what it does |
|---|---|---|---|
| `RegistryEvent.Register<Block>` | `Stellurgy#registerBlocks` | B | register Stellurgy blocks (blocks.md) |
| `RegistryEvent.Register<Item>` | `Stellurgy#registerItems` | B | register Stellurgy items (items.md) |
| `RegistryEvent.Register<Enchantment>` | `Stellurgy#registerEnchants` | B | register Stellurgy enchantments |
| `RegistryEvent.Register<Biome>` | `Stellurgy#register` | B | register Stellurgy biomes (world-gen) |
| `RegistryEvent<IRecipe>` | `Stellurgy#registerRecipes` | B | register recipes (recipe.md) |
| `ModelRegistryEvent` | `Stellurgy#registerModels` | C | register item models |
| `OreRegisterEvent` | `Stellurgy#registerOre` | B | ore-dictionary reaction |
| `PlayerEvent.PlayerLoggedInEvent` | `Stellurgy#onPlayerLogin` | S | mod-level login hook |
| `RegistryEvent.Register<SoundEvent>` | `AudioRegistry.RegistrationHandler#registerSoundEvents` | B (**ESub**) | register Stellurgy sounds (`@Mod.EventBusSubscriber`) |

### B.9 Test harness — owner: test-suite-map.md (started by `RunTestsCommand.java` on the server's own `ServerState.ingameTests`)

| Forge event | handler | side | what it does |
|---|---|---|---|
| `TickEvent.WorldTickEvent` | `ServerStateEvents#onWorldTick` → `IngameTestOrchestrator#onWorldTick` | S | drive the `/ar dev runtests` in-game suite from the per-server `ServerState.ingameTests`; not a bus subscriber of its own and not registered on demand |

### B.10 Ship lifecycle — owner: api-public (declaration) / util-core (the recorder), registered `Stellurgy.java`

Stellurgy **declares and posts** this one; it is not a Forge or libVulpes event. `ShipLifecycleEvent`
(`api/event/`) is the ONE ship-lifecycle family: `ShipNamed` (`ASSEMBLED` /
`PASTED` / `LOADED`), `ShipUsable` (physics will be stepped — once per ship object, i.e. per load),
`ShipUnnamed` (`UNLOADED` / `DESTROYED`) and its subclass `ShipDeparted` (`DEPARTED`, carrying
`destinationDim`). Every edge is posted by the vendored physics manager at the place the transition
happens (`valkyrienskies/…/WorldServerShipManager.java`), on the **server game thread** inside the
world tick, in two flushes of `publishLifecycleEvents` (`WorldServerShipManager.java:635`, called at
`:260` and `:289`): after the spawn/load/unload queues are drained (named/unnamed), and at the END of
the tick after the thread-safe ship list is rebuilt (usable — so a handler can resolve the craft it
hears about). A handler may touch the world
AND may queue ship work, which lands next tick. Not cancellable. Nothing posts it client-side. `[V]`

**Every registry removal is announced, and a departure is declared, not inferred.** The destroy
pass, the unloaded-record sweep, the spawn drain's blockless-remnant drop and
`deregisterBlocklessRemnant` (called by `VSBridge.adoptOwnRemnant`) all go through `noteRemoved`,
which consumes a departure the crossing declared BEFORE its cut (`VSIntegration.crossShip` →
`VSBridge.declareDeparture` → `WorldServerShipManager#declareDeparture`) and posts `ShipDeparted`, or
posts `DESTROYED`. A world that stops ticking announces nothing. `[V]`

| Forge event | handler | side | what it does |
|---|---|---|---|
| `ShipLifecycleEvent.ShipNamed` / `.ShipUnnamed` (Stellurgy-declared) | `ServerEventRecorder#onShipNamed` / `#onShipUnnamed` (test source set) | S | TESTS ONLY — the subscriber is not in a released jar: one `ship_lifecycle` record per announcement (`ship`, `durable`, `cause`, `edge`, `dim`, and `destinationDim` on a departure). |
| `ShipLifecycleEvent.ShipUsable` (Stellurgy-declared) | `ServerEventRecorder#onShipUsable` (test source set) | S | TESTS ONLY — one `ship_usable` record per load (`ship` = durable id, `vsShip` = physics id, `dim`) |
| `ShipLifecycleEvent.ShipNamed` (Stellurgy-declared) | `ShipMassTrigger.Hooks#onShipNamed` | S | the authoritative hull mass recompute: compares against the incremental path on ASSEMBLED/PASTED and LOGS a disagreement, writes silently on LOADED. Keeps no tally — a test observes it at `ShipInertiaWriter.compare`. Catches its own failures — the handler contract forbids throwing, and this one runs inside the ship manager's tick |
| `ShipLifecycleEvent.ShipUsable` (Stellurgy-declared) | `DeckHold#onShipUsable` (`DeckHold.java`) | S | resolves every deck hold waiting for that durable id |
| `ShipLifecycleEvent.ShipUnnamed` (Stellurgy-declared) | `DeckHold#onShipGone` (`DeckHold.java`) | S | ends the holds on that craft on `DESTROYED` ONLY — a `DEPARTED` craft is carrying its crew to the next cell and an `UNLOADED` one will be back |

B.4 subscribes to libVulpes `RocketEvent` subtypes and B.6 to Galacticraft's
`GCCoreOxygenSuffocationEvent`; B.10 is a subscription to an event **Stellurgy itself declares and
posts**, a kind apart from the Forge / FML / vanilla events of the rest. `PlayerEvent.PlayerChangedDimensionEvent`
is the most-shared event: independent handlers in `EntityEventHandler`, `RocketEventHandler`,
`SpaceObjectManager` and `PlanetWeatherEventHandler` (`AtmosphereHandler` does not subscribe to it).

---

## Posted BY us — `BlockEvent.BreakEvent`

Every other row in this doc is an event we LISTEN to. This one we fire: `StructureDamageEngine` posts
a break event before removing a block, attributed to the fixed synthetic player `[weapon-fire]`
(id `b6ab6a37-2b1a-4b0a-9d9f-6e2f2f5f0a11`, constant so a protection mod's whitelist can name it), and
honours a cancellation by leaving the block standing one stage short of destroyed. Vanilla spawn
protection is asked directly beside it, being no listener.

**Our own subscriber runs on our own post.** `DamageInvalidationHandler` listens to `BreakEvent` at
LOWEST priority and clears the position's record, so the engine writes its destruction record AFTER
the ask rather than before — reverse them and the record is wiped by our own handler.
See `structural-damage` MECH-DMG-33.

## Collisions & risks

- **Self-registering tiles release on their world's unload.** `TileLandingPad`,
  `TileRocketMonitoringStation`, `TileRocketAssemblingMachine` register `this` on the global
  `EVENT_BUS`. A release in `invalidate` / `onChunkUnload` alone does not RUN at a server stop —
  `MinecraftServer.stopServer` posts `WorldEvent.Unload` and flushes, and `onChunkUnload` runs only
  from the tick-time unload queue (`ChunkProviderServer.java`, `World.java`) — so a tile
  that released only there would stay on the bus, with its world, for the JVM. Each therefore also
  releases on its own world's `WorldEvent.Unload`, and the pad registers in `onLoad` and answers
  only its own world, so it never answers rockets in another dimension. [V] at source; not driven
  in game.
- **Per-dimension AtmosphereHandler churn.** Each dimension gets its own `AtmosphereHandler`
  registered on the bus; `registerWorld` unregisters any prior instance first,
  so `LivingUpdateEvent` is delivered to one handler per live dimension by design, not a duplicate. [V]
- **Shared-provider aliasing (armor cap).** Every stack of a suit piece shares one `Item`-singleton
  provider (INV-CAP-03, `CapabilityProtectiveArmor.java`); safe only because resolution is
  stateless. Any future per-stack field on the provider would leak across stacks.
- **`PART_WEAR` host drift.** `IPartWear.java` / `CapabilityWear.java` javadoc says the wear host
  is "always `TileBrokenPart`", but the only in-tree provider is `TileWearable`
  (`TileWearable.java`); no `TileBrokenPart` provider exists. Stale doc, not a runtime bug.
- **No `AttachCapabilitiesEvent` for `PART_WEAR`.** Unlike `PROTECTIVEARMOR` (attached via event),
  `PART_WEAR` is exposed only by tiles implementing `IPartWear` directly. Blocks without a
  `TileWearable` cannot carry wear — intentional today, but means the cap is tile-only.
- **Side-guard confidence is inherited.** INV-EVT-02 [V] pins that every mutating `event/` handler is
  `!world.isRemote`-guarded or intrinsically server-side, and render/fog handlers carry
  `@SideOnly(CLIENT)`. Handlers in B.2–B.9 rely on their own subsystem docs for side-guard proof; no
  cross-cutting unguarded-mutation was found in this pass, but B.2 `AtmosphereHandler#onTick`
  (`LivingUpdateEvent`, fires both sides) is the one to confirm against atmosphere-oxygen.md.
- **`CapabilityProtectiveArmor.registerCap()` is dead code.** It is fully commented out
  (`CapabilityProtectiveArmor.java`) and never called; live registration is
  `EVENT_BUS.register(new CapabilityProtectiveArmor())` at `Stellurgy.java`.

## Projectile substrate

| event | handler | side | purpose |
|---|---|---|---|
| `TickEvent.WorldTickEvent` | `ShotSubstrateEvents#onWorldTick` | S | phase END only — step every shot of that world (MECH-SHOT-01). Registered under `Constants.modId`; the handler holds no state, the world's `ShotRegistry` does |

Phase END is load-bearing, not incidental: a shot is stepped against the world as the tick leaves it,
so a shell raised this tick is up when the round arrives and a ship that moved this tick is tested
where it now is. There is no `WorldEvent.Unload` handler because there is nothing to detach — the
shots belong to the world's own saved data.

The AFFS handlers (`ShieldNetworkManager#onWorldTick`, `#onWorldUnload`, and the field generator's
entity scan) are also subscribed under `Constants.modId`; they are owned by
`20-subsystems/shields.md` and are not in the tables here.

## Network solver, shot and beam drawings

| event | handler | side | purpose |
|---|---|---|---|
| `TickEvent.WorldTickEvent` | `SubsystemNetworkEvents#onWorldTick` | S | phase END only — rebuild-if-dirty plus one max-flow solve per domain per world, on the running server's `SubsystemNetworkManager.of(world)`. Registered under `Constants.modId` via `@Mod.EventBusSubscriber` |
| `WorldEvent.Unload` | `SubsystemNetworkEvents#onWorldUnload` | S | drop that world's per-domain state and its registered nodes from the running server's manager |
| `TickEvent.ClientTickEvent` | `RenderShots#onClientTick`, `RenderBeams#onClientTick` | C | phase END only — step the current client world's DRAWINGS of shots / beams; paused game is skipped |
| `AttachCapabilitiesEvent<World>` | `ClientWorldDrawings.Attach#attach` | C | give every CLIENT world its own `ClientWorldDrawings` (beam + shot trackers): a new world brings new, empty drawings, so no unload clear is needed |
| `RenderWorldLastEvent` | `RenderShots#onRenderWorldLast` | C | draw the tracers and impact flashes |

`RenderShots` is registered on the Forge bus from `ClientProxy` beside the other client render
handlers, not by annotation — it is `@SideOnly(CLIENT)` and must never be constructed on a server.

**Capability**: `TileTurret` exposes `CapabilityEnergy.ENERGY` on every face (its own buffer), so any
FE source in the ecosystem can charge a gun without knowing what it is. `TileTurret.java`.

## Ship flight model

| event | posted by | side | meaning |
|---|---|---|---|
| `ShipEvent.FlightModelChangedEvent` | `TileAdvancedFlightComputer#rebuildFlightModel` | S | the ship's flight model was rebuilt (hull changed, or the load round); carries the flight computer's position and the new `ShipReadout`. The STAT-10 registration seam: a system with a characteristic of its own joins the readout by hearing this. May repeat identical figures on a load round; silence is not "unchanged" |
