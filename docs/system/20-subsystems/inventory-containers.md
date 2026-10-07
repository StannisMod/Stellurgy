---
id: inventory-containers
owns: [inventory/]
entrypoints: [GuiHandler#getServerGuiElement, GuiHandler#getClientGuiElement, ModulePlanetSelector#onInventoryButtonPressed, ContainerOreMappingSatellite#slotClick]
depends-on: [api-public, dimension-planets, satellite, util-core, client-render]
depended-by: [items, multiblock-machines, infrastructure-tiles, space-stations, wirelessdata, client-render]
contracts: [C2, C5, C7]
confidence: high
---

## Purpose

`inventory/` is the Stellurgy **GUI-backing layer**: the pieces that populate libVulpes' modular-inventory
framework (`ModuleBase`/`GuiModular`/`ModuleContainerPan`) with Stellurgy-specific widgets, plus one
hand-rolled `Container`/`GuiContainer` pair for the ore-mapping satellite and one Forge
`IGuiHandler`. Almost every class here is a **client-side render/interaction module** or a static
resource table; only the property-sync plumbing (`numberOfChangesToSend`/`needsUpdate`/`sendChanges`/
`onChangeRecieved`) crosses the wire, and it rides vanilla `Container` window properties, not Stellurgy packets.

## Responsibility boundary

**Owns:** the Stellurgy `GuiHandler` + its one id `ORE_MAPPING_SATELLITE`; the ore-mapping `Container`/`Gui`; all `modules/*`
widgets (data bars, planet selector, satellite terminal, side-selector overlay, scroll cache); the
`SlotData` slot type; `IPlanetDefiner` (planet-knowledge gate); and `TextureResources` (the GUI
texture/`ProgressBarImage`/`IconResource` constant registry).
**Does NOT own:** the libVulpes GUI framework itself (`ModuleBase`, `GuiModular`, `ModuleButton`,
`ModuleContainerPan*`, `ModuleSlotArray`, `ModuleNumericTextbox`, `ModuleBlockSideSelector` — the
package `dev.stannismod.stellurgy.libvulpes`, owned by no subsystem doc yet);
the *tiles/items* that assemble module lists and open the GUIs (owned by their subsystems);
`DataStorage`/`SatelliteData` schemas (`api-public`/`satellite`); GUI *registration* order in
`Stellurgy.java` (`misc-oddities`).

## Shared patterns (described once)

- **Module = render + sync.** Each `Module*` extends a libVulpes `ModuleBase`. Client draws in
  `renderBackground`/`renderForeground`/`renderToolTip`; server↔client value transport is the
  4-method window-property protocol `numberOfChangesToSend / needsUpdate(localId) / sendChanges /
  onChangeRecieved` — the enclosing `GuiModular` container polls it (`MECH-IVC-04`).
- **`DataStorage` bar family.** `ModuleData`, `ModuleAutoData`, `ModuleWirelessBufferBar` share one
  visual (8×40 frame + green fill) and one sync shape (`data.length+1` lanes: N amounts + 1 type
  ordinal; buffer-bar uses amount/max/type). They differ only in buttons/slots (`MECH-IVC-08`).
- **`@SideOnly(Side.CLIENT)`** guards the render-only modules; the two modules that also run on the
  server (`ModuleData`/`ModuleSatelliteTerminal`) branch on `!world.isRemote` before computing state.
- **Item-render buttons.** `ModuleItemSlotButton`/`ModuleBrokenPart` are `ModuleButton` subclasses
  that draw an arbitrary `ItemStack` via `RenderItem` (libVulpes' `ModuleSlotButton` handles blocks only).
- **Static resources.** `TextureResources` is a pure constant holder — every `ResourceLocation`,
  `ProgressBarImage`, and `IconResource` used by Stellurgy GUIs (`C7`).

## Key types & catalogue

ONE GUI-open path: every GUI opens on Stellurgy's container (libVulpes is folded into it and has no
container of its own), and FML keeps one handler per container. `AffsGuiRouter` peels off the AFFS range
(`>= AFFS_GUI_BASE`); everything else reaches the **Stellurgy** `GuiHandler` below, which serves its own one
id and forwards every other id to libVulpes' `GuiHandler` (`guiId.MODULAR*`, ordinals 0-3). All `Module*`
rows are client widgets unless noted "sync" (crosses wire).

| class | base / kind | role | sync lanes | key cite |
|-------|-------------|------|-----------|----------|
| `GuiHandler` | Forge `IGuiHandler` | serves `ORE_MAPPING_SATELLITE`; forwards every other id to libVulpes' handler; item-held guard when `x==-1 && y<-1` | — | GuiHandler.java:23,45,56,72,82 |
| `ContainerOreMappingSatellite` | `Container` | 9 hotbar slots; `slotClick` tags a slot "selected" if its OreDict name starts `ore`/`gem`/`dust` | — | ContainerOreMappingSatellite.java:20,44 |
| `GuiOreMappingSatellite` | `GuiContainer` | async ore-scan viewport: worker `Thread`→`scanChunk`, WASD pan, ↑/↓ zoom, dynamic texture | — | GuiOreMappingSatellite.java:48,84,128 |
| `GuiProgressBarContainer` | abstract `GuiContainer` | directional progress-bar draw helpers (dead: no subclass) | — | GuiProgressBarContainer.java:18 |
| `GuiPlanetButton` | libVulpes `GuiImageButton` | button that renders a live 3D planet via `RenderPlanetarySky` | — | GuiPlanetButton.java:22,52 |
| `GuiSpySatellite` | `GuiScreen` | empty stub (holds a `TileEntity`, no render) | — | GuiSpySatellite.java:7 |
| `IPlanetDefiner` | interface | `isPlanetKnown`/`isStarKnown` gate for selector visibility | — | IPlanetDefiner.java:6 |
| `TextureResources` | constants | GUI textures, `ProgressBarImage`, `IconResource` registry | — | TextureResources.java:13 |
| `ContainerTypes` | — | empty placeholder class | — | ContainerTypes.java:3 |
| `SlotData` | `Slot` | accepts only `IDataItem`; stack limit 1 | — | SlotData.java:19,24 |
| `ModulePlanetSelector` | `ModuleContainerPan`, `IButtonInventory` | full planet/star/galaxy map: build button tree, zoom/pan, up/select/list verbs, selection ring; forwards `staticModuleList` sync | delegated | ModulePlanetSelector.java:65,613,752 |
| `ModuleButtonPlanet` | libVulpes `ModuleButton` | selector node that spawns a `GuiPlanetButton` | — | ModuleButtonPlanet.java:28 |
| `ModuleSatelliteTerminal` | `ModuleBase` | Satellite Control Center status text; server computes, 9-tick forced burst | 4 (status,ppt,data,max) | ModuleSatelliteTerminal.java:60,107,157 |
| `ModuleSatellite` | libVulpes `ModuleSlotArray` | chip slot that delegates all sync to the held `SatelliteBase` | satellite-defined | ModuleSatellite.java:35,43 |
| `ModuleData` | `ModuleBase`, `IButtonInventory` | data-chip bar + store/load buttons + `SlotData` | data.len+1 | ModuleData.java:38,78,108 |
| `ModuleAutoData` | `ModuleBase` | auto in/out data bar, two `SlotData`, no buttons | data.len+1 | ModuleAutoData.java:35,53 |
| `ModuleWirelessBufferBar` | `ModuleBase` | read-only transceiver buffer bar | 3 (amount,max,type) | ModuleWirelessBufferBar.java:43,49 |
| `ModuleContainerPanYOnlyWithScrollCache` | `ModuleContainerPanYOnly` | persists Y-scroll across GUI reopens in the owning machine's `ScrollMemory`; a stateless Forge wheel router | — | ModuleContainerPanYOnlyWithScrollCache.java:27,75,143 |
| `ModuleItemSlotButton` | `ModuleButton` | slot-like button rendering any (non-block) `ItemStack` | — | ModuleItemSlotButton.java:29,49 |
| `ModuleBrokenPart` | `ModuleBase` | renders a broken rocket-part stack + damage-stage tooltip | — | ModuleBrokenPart.java:24,63 |
| `ModulePlanetImage` | `ModuleBase` | draws one planet icon; `properties` set post-construction via `setDimProperties` | — | ModulePlanetImage.java:21,53 |
| `ModuleNumericTextboxWithTooltip` | `ModuleNumericTextbox` | numeric field with a hover tooltip | — | ModuleNumericTextboxWithTooltip.java:19,36 |
| `ModuleSideSelectorTooltipOverlay` | `ModuleBase` | per-face mode tooltip over a `ModuleBlockSideSelector` | — | ModuleSideSelectorTooltipOverlay.java:38,57 |
| `ModuleStellarBackground` | `ModuleBase` | full-screen starfield backdrop | — | ModuleStellarBackground.java:19 |
| `ModuleOreMapper` | `ModuleBase` | dead widget-port of the ore GUI (only referenced in a comment) | — | ModuleOreMapper.java:25,58 |

## Mechanics

- **MECH-IVC-01 — Stellurgy GUI dispatch.** `player.openGui(Stellurgy.instance, GuiHandler.ORE_MAPPING_SATELLITE, world, posX, satId, posZ)` → `GuiHandler` resolves `DimensionManager.getSatellite(y)`, verifies it is a `SatelliteOreMapping` in the right dimension, and returns the `Container`/`Gui`; every other id is forwarded to libVulpes' handler [V] (GuiHandler.java:45-56,72-82; ItemOreScanner.java:86). **The two id spaces are one**: `ORE_MAPPING_SATELLITE` = libVulpes' `guiId.values().length` (4), past libVulpes' ordinals 0-3 [V] (GuiHandler.java:23). Ordinal 2 (libVulpes' `MODULARCENTEREDFULLSCREEN`) would collide the moment libVulpes' GUIs move onto this container; the station chip's first open arrives on exactly that id [T] (`ItemRightClickClientGroupTest`, first chain).
- **MECH-IVC-02 — Ore-slot selection.** `slotClick(slot, dragType==0, …)`: if the clicked stack's OreDict name starts `ore`/`gem`/`dust` the container calls `inv.setSelectedSlot(slot)`, else `-1`; the scan reads that selection each frame (ContainerOreMappingSatellite.java:32-52).
- **MECH-IVC-03 — Async ore scan.** The GUI runs `SatelliteOreMapping.scanChunk(...)` on a named worker `Thread`; WASD re-centres, ↑/↓ halves/doubles `scanSize` (clamped to `maxZoom`), each restart interrupts the prior thread and rebuilds a `ClientDynamicTexture`; texture deleted in `onGuiClosed` (GuiOreMappingSatellite.java:84-205).
- **MECH-IVC-04 — Window-property sync.** Container polls each module: `needsUpdate(localId)` flags a dirty lane, `sendChanges` pushes it as a `sendWindowProperty`, client `onChangeRecieved(localId,value)` writes it back. `ModulePlanetSelector` fans this out across its `staticModuleList` by localId ranges (ModulePlanetSelector.java:752-795; ModuleData.java:78-113).
- **MECH-IVC-05 — Terminal forced burst.** Server-only (`!world.isRemote`): every `PERIOD_TICKS=9` world-time bucket (or on satellite-id change) it recomputes `{status,ppt,data,max}` and arms `burstPending` so all 4 lanes ship in one pass; status ∈ {0 no-link,1 no-power,2 out-of-range,3 ok}; client rebuilds localized text and prepends the chip's satellite name (ModuleSatelliteTerminal.java:106-187).
- **MECH-IVC-06 — Planet-selector navigation.** Ctor renders a planetary or star system into `planetList`/`moduleList`; scroll zooms (0.36–4.0); buttons `INVALID_PLANET`=up-a-level, `+1`=confirm (`hostTile.onSelectionConfirmed`+close), `+2`=toggle side list; clicking a body sets `selectedSystem`; re-clicking the selected body descends (`currentSystem`). `redrawSystem` rebuilds the tree; `IPlanetDefiner` hides unknown bodies (ModulePlanetSelector.java:161-176,613-665,668-749). **Scale**: in the star view a planet's button stands `MAP_PIXELS_PER_AU = 100` px per AU of orbit (× zoom) beyond the star's 50-px disc, and a companion star at half that (ModulePlanetSelector.java:46,200-203,341-343) — [T] `test/client/MachineGuiClientGroupTest.java` (`theStarMapDrawsAPlanetAtAHundredPixelsPerAu`); companions unpinned. In the planetary view a MOON's button stands `moonViewUnits` px (Luna 150, `AstronomicalBodyHelper.moonViewUnits`) × the view's 0.5 zoom-1 multiplier beyond the planet's disc — where Luna stood before her distance was corrected (ModulePlanetSelector.java:52,378-381) — [T] `MachineGuiClientGroupTest#thePlanetaryViewDrawsAMoonWhereItDrewLunaAt150`.
- **MECH-IVC-07 — Scroll-position memory.** Each machine whose GUI has a scrolling list owns a `ScrollMemory` (client copy of `TileOrbitalRegistry`, `TileObservatory`), handed to the list on every GUI build; the list restores from it on first render and saves into it on movement, so reopening shows the same place and one machine's position never lands in another's list. The machine clears it when the list's contents change (a new scan). One `WheelRouter`, registered at client init, routes a wheel notch over a `GuiModular` to the scrolling list under the pointer, found among that screen's own modules; the once-per-tick notch coalescing is the list's own state (ModuleContainerPanYOnlyWithScrollCache.java:27,41,75,143-180; ClientProxy.java:469; TileOrbitalRegistry.java:66,1177; TileObservatory.java:71,470,1269) `[V]`.
- **MECH-IVC-08 — Data chip store/load.** `ModuleData` "store"/"load" buttons call `IDataInventory.storeData(slot)`/`loadData(slot)`; `SlotData` restricts the slot to `IDataItem` stacks of size 1; the bar sums `DataStorage[].getData()/getMaxData()` for fill + tooltip (ModuleData.java:57-75; SlotData.java:19-31).

## State & persistence

No NBT is owned here — modules read/mutate `DataStorage`/`SatelliteData`/`DimensionProperties` whose
persistence lives in their home subsystems. All cross-side state is **transient vanilla window
properties** (int lanes) rebuilt every GUI session; a list's scroll position is the client machine's
`ScrollMemory`, never saved.

## Invariants

- **INV-IVC-01 [V]** Stellurgy `GuiHandler` returns non-null only for `guiId.OreMappingSatellite`; every other id (and a null/desynced satellite) yields null (GuiHandler.java:31-39,55-64).
- **INV-IVC-02 [V]** `guiId` order is `{RocketBuilder, BlastFurnace, OreMappingSatellite, StationChip}`; `OreMappingSatellite.ordinal()==2` is the wire value both `openGui` sites and the handler agree on (GuiHandler.java:67-72; ItemOreScanner.java:86).
- **INV-IVC-03 [T]** Clicking a selector button whose id is a planet dim (`0 ≤ id < STAR_ID_OFFSET=10000`) registers a server-side selection (`hasSelection:true`, `selectedDim`) (PlanetSelectorGuiE2ETest.java:50-63).
- **INV-IVC-04 [V]** `ModuleSatelliteTerminal` transports exactly 4 int lanes `{status,ppt,data,max}` and force-refreshes on each `PERIOD_TICKS=9` bucket edge (ModuleSatelliteTerminal.java:37-39,84,118-133).
- **INV-IVC-05 [V]** `SlotData` accepts only `IDataItem` stacks and caps both stack limits at 1 (SlotData.java:19-31).
- **INV-IVC-06 [V]** Terminal/data status is computed only under `!world.isRemote`; the client path only rebuilds display text (ModuleSatelliteTerminal.java:92,108,150-153).
- **INV-IVC-07 [V]** A scroll position belongs to the machine whose list it is: two machines never share one (each holds its own `ScrollMemory`), and it dies with the client copy of that machine (TileOrbitalRegistry.java:66; TileObservatory.java:71). There is no static slot shared by every GUI.
- **INV-IVC-08 [V]** `Constants.STAR_ID_OFFSET` partitions the selector id space: `currentSystem < STAR_ID_OFFSET` ⇒ planet dim, else star system; star buttons carry `id + STAR_ID_OFFSET` (ModulePlanetSelector.java:103,218,436-440).

## Failure modes & edge cases

- `ModulePlanetSelector.renderBackground` calls `redrawSystem()` whenever `Minecraft.getSystemTime() % 5 == 0`, rebuilding the whole button tree several times a second (allocation churn) (ModulePlanetSelector.java:523).
- The ore-scan worker thread swallows `IndexOutOfBoundsException` while blitting `oreMap` if the array shape and texture size disagree (GuiOreMappingSatellite.java:283).
- `ModuleWirelessBufferBar.onChangeRecieved` clamps the type ordinal into `DataType.values()` range, so an out-of-range wire value degrades gracefully instead of throwing (ModuleWirelessBufferBar.java:84).

## Integration seams

- **Forge event (C5):** `ModuleContainerPanYOnlyWithScrollCache.WheelRouter` is registered once by `ClientProxy.registerEventHandlers` and handles `GuiScreenEvent.MouseInputEvent.Pre` (ClientProxy.java:469; ModuleContainerPanYOnlyWithScrollCache.java:146).
- **GUI-open wire (C2):** `guiId` ordinals are the client↔server GUI-open contract; Stellurgy `GuiHandler` is registered via `NetworkRegistry.registerGuiHandler(this, …)` (Stellurgy.java:995).
- **Assets (C7):** `TextureResources` ResourceLocations + `msg.satctrlcenter.*`, `msg.stellurgy.planetselector.*`, `stellurgy.sideselector.direction.*`, `data.label.*`, `msg.wirelessTransceiver.type` lang keys.
- **Consumers:** `ModulePlanetSelector`/`ModulePlanetImage` (TileWarpController, TilePlanetSelector), `ModuleSatelliteTerminal` (TileSatelliteTerminal), `ModuleWirelessBufferBar` (TileWirelessTransceiver), `ModuleData`/`ModuleAutoData` (data-bus tiles), `ModuleOreMapper` (dead, ItemOreScanner comment).

## Config surface

No `StellurgyConfiguration` flag is read here except `spaceDimId`, used by `ModulePlanetSelector.refreshSideBar`
to hide the space dimension from the star's planet list (ModulePlanetSelector.java:701). There is no
config gate that disables these GUIs; they are reachable whenever their host tile/item is.

## Test coverage

- INV-IVC-03 → `src/test/java/.../client/PlanetSelectorGuiE2ETest.java:35-64` (place selector, click a planet button, assert server-side selection).
- INV-IVC-01/02/04/05/07 → no direct unit test found (open question below).

## Open questions

- Are `GuiSpySatellite`, `ContainerTypes`, `GuiOrbitalLaserDrill`/`ContainerOrbitalLaserDrill`
  (0-SLOC), and `GuiProgressBarContainer` (no subclass) reachable, or fully dead scaffolding? (unconfirmed: dead code).
- Does the double `registerGuiHandler` on `Stellurgy.instance` actually break `ItemStationChip`'s
  `MODULARFULLSCREEN` GUI at runtime? (needs a runtime check of Forge put-overwrite).
