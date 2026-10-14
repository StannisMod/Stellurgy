---
id: client-render
owns: [client/]
entrypoints: [ClientProxy#preinit, ClientProxy#registerRenderers, KeyBindings#onClientTick, KeyBindings#onKeyInput, TooltipInjector#onTooltip, RenderPlanetarySky#render]
depends-on: [dimension-planets, rocket-entity, space-stations, api-public, multiblock-machines, infrastructure-tiles, world-gen, event-handlers, network-wire]
depended-by: [rocket-entity, atmosphere-oxygen, event-handlers, blocks, items, backward-compat]
contracts: [C2, C4, C5, C7]
confidence: high
---

## Purpose

The whole client half of the mod: the `ClientProxy` lifecycle (renderer / model / colorizer
registration, particle + sound spawning), keyboard input translation into rocket/craft
control packets and the Free Flight HUD, shift/alt tooltip injection, and every custom
renderer — per-dimension skies, tile-entity special renderers (TESRs) for the multiblock
machines, entity renderers (rocket, UI planets/stars, hovercraft), and the jetpack armour
model. It owns no gameplay state; it reads server-replicated state and paints it.

## Responsibility boundary

**Owns**: `client/` — `ClientProxy`, `KeyBindings`, `StellurgyKeyConflictContext`, `TooltipInjector`,
`SoundRocketEngine`, `ModelLoader`, `model/ModelRocket` (no render-distance helper and no `ClientHelper`; see proxy-lifecycle),
`gui/ModuleSelectableAtmosphereButton`; all of `client/render/**` (sky, multiblock TESRs, entity,
armour, particle-render event handlers, shared GL helpers, `ClientDynamicTexture`).

**Does NOT own**: the **weapon-fire renderers** — `RenderShots` and `RenderBeams` sit under
`client/render/` but are documented with the subsystem that feeds them, because what they draw is
decided entirely by a replication channel (`projectile-substrate` MECH-SHOT-13/29, and the client-side
stores `ClientShotTracker` / `ClientBeamTracker`, held per client world by the `ClientWorldDrawings`
capability); the entities/tiles being drawn (rocket-entity,
multiblock-machines, infrastructure-tiles) — only their renderers; `DimensionProperties`/`DimensionManager` and the
`WorldProvider`s that *install* the sky renderers (dimension-planets, world-gen); the HUD
overlay draw loop and UI-panel layout state (`RocketEventHandler` in event-handlers — this
subsystem only supplies its HUD text via `KeyBindings.freeFlightHudLines`); the `EntityFX`
particle classes (`entity/fx/`, owned by rocket-entity) — `ClientProxy` only spawns them; the
GUI screens/containers (inventory-containers); the OBJ parser (`backwardCompat.WavefrontObject`).

## Mechanic index

| cluster | doc | mechanics |
|---------|-----|-----------|
| Proxy lifecycle & spawners | [proxy-lifecycle.md](./proxy-lifecycle.md) | MECH-CLR-01..07 — renderer/model/colorizer registration, particle & sound spawning, render-distance override, UI-layout config load, dead loaders |
| Input → control & HUD | [input-hud.md](./input-hud.md) | MECH-CLR-08..16 — key registration, cockpit key-conflict scoping, classic key input, Free-Flight per-tick input sampling, camera-nose lock + PosLook re-pin, engine-start hold, HUD text, tooltip injection |
| Sky rendering | [sky-rendering.md](./sky-rendering.md) | MECH-CLR-17..22 — star display-list precompile, planetary sky, warp detection, space-station sky, interplanetary travel sky, asteroid sky |
| Tile (machine) renderers | [tile-renderers.md](./tile-renderers.md) | MECH-CLR-23..25 — shared OBJ TESR pattern, progress/real-time animation drivers, VFX config gate |
| Entity & armour renderers | [entity-armour-renderers.md](./entity-armour-renderers.md) | MECH-CLR-26..31 — rocket block-mesh render, FF attitude slerp, UI planet/star/button entities, craft/item entities, jetpack armour model, delayed particle render |

## Dependency edges

- **← dimension-planets**: sky renderers read `DimensionProperties` (orbit angles, rings,
  star, sun colour, atmosphere) via `DimensionManager`; `ClientProxy` holds a client-side
  `DimensionManager` instance.
- **← rocket-entity**: `KeyBindings` reads `EntityRocket` FF state and calls
  `applyFreeFlightInput`; `RendererRocket` reads `StorageChunk` + FF quaternion; `SoundRocketEngine`
  reads engine power. Reverse edge: `EntityRocket`/`EntityStationDeployedRocket` construct
  `SoundRocketEngine`; `event-handlers.RocketEventHandler` calls `KeyBindings.freeFlightHudLines`.
- **← space-stations**: `ClientProxy.calculateCelestialAngleSpaceStation` and the sky renderers
  read `SpaceObjectManager` for station rotation / warp travel direction.
- **← api-public**: `StellurgyConfiguration` flags (`spaceDimId`, `advancedVFX`, `oxygenVentSize`),
  `StellurgyItems/Blocks`, `Constants`, fuel registry (tooltip fluid lists).
- **← multiblock-machines / infrastructure-tiles**: every TESR casts to its tile and reads
  `canRender()` / `isRunning()` / `getProgress()` / `getMachineEnabled()`.
- **← world-gen**: `WorldProvider{Planet,Space,Asteroid}` install the sky renderers;
  `PlanetEventHandler` re-installs `RenderPlanetarySky` on world load.
- **← network-wire (via libVulpes)**: input packets `PacketEntity`, `PacketChangeKeyState`
  sent to the server (client→server only; see C2).

## Contract surface (see cluster docs for line cites)

- **C2 packets (out only)**: `PacketEntity(rocket, PacketType.*)` for the rocket sub-packets (`rocket-entity` `persistence-wire`; `C2`) plus `OPENGUI` and `TOGGLE_RCS`;
  `PacketChangeKeyState` for jetpack toggle + space-bar sync. No packet *handlers* live here.
- **C4 config**: `spaceDimId`, `advancedVFX`, `oxygenVentSize` (read). The suit-HUD layout is NOT
  config: it is a function of the screen, computed in the drawing code from the
  frame's `ScaledResolution` (`client/HudLayout.java`) — maintainer ruling, *"Расположение элементов
  любого графического интерфейса является функцией не мода, а ЭКРАНА"*.
- **C5 events**: `@SubscribeEvent` on `ModelBakeEvent`, `ItemTooltipEvent`, `ClientTickEvent`,
  `KeyInputEvent`, `RenderWorldLastEvent`, `RenderPlayerEvent.Post`, `WorldEvent.Unload`;
  `@Mod.EventBusSubscriber(Side.CLIENT)` on `ClientProxy` and `TooltipInjector`.
- **C7 lang/assets**: tooltip base keys `tooltip.stellurgy.*` + `.shift.N/.alt.N`
  and `hold_shift`/`hold_alt`/`none`; HUD keys `msg.ff.hud.*` / `msg.ff.engines.*`; keybind
  lang `key.*`; OBJ/PNG `ResourceLocation`s and item/block `ModelResourceLocation`s.

## Invariants (headline; full lists in cluster docs)

- **INV-CLR-01 [V]** Every Stellurgy client renderer is side-safe: registration is reached only via
  the `ClientProxy` (never `CommonProxy`), and `TooltipInjector`/`KeyBindings`/`StellurgyKeyConflictContext`
  are `@SideOnly(CLIENT)` or client-bus-only. `ClientProxy.java:87,314-336`, `KeyBindings.java:32`.
- **INV-CLR-02 [V]** Renderers treat replicated state as possibly-null and bail rather than
  crash (station rotation, world time, sky `DimensionProperties`). `ClientProxy.java:446-467`.

## Open questions

- Whether `client.ModelLoader` (a custom `ICustomModelLoader`) is dead — no
  `ModelLoaderRegistry.registerLoader` call was found.
