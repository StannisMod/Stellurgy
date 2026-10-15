---
id: integration-probe-waila
owns: [integration/theoneprobe/, integration/waila/]
entrypoints: [TopIntegration#register, WailaIntegration#register]
depends-on: [integration-dataloaders, rocket-entity, wirelessdata, api-public, structural-damage]
depended-by: []
contracts: [C1, C7]
confidence: high
---

## Purpose

Adapts Stellurgy's shared tooltip-data loaders (`integration/dataloaders/`) to two
external HUD mods: **The One Probe** (TOP) and **Waila/HWYLA**. These packages own no
gameplay data of their own — they are the two concrete `AbstractDataContext` back-ends plus
the glue that registers rockets, data blocks and wireless transceivers as probe/tooltip
targets. All actual value extraction lives in the `integration-dataloaders` subsystem.

## Responsibility boundary

- **Owns**: the TOP and Waila registration handshakes; two `AbstractDataContext`
  implementations (`TOPDataContext`, `WailaDataContext`); the four provider classes that
  bind rockets/blocks to each mod's callbacks; the Waila server→client NBT sync payload
  (transient, not disk-persisted).
- **Does NOT own**: what a line of tooltip text *says* or *how a value is computed* — that
  is `RocketDataLoader` / `DataBlockDataLoader` / `WirelessTransceiverDataLoader` in
  `integration-dataloaders`. Does not own rocket/fuel/data-storage state (rocket-entity,
  api-public). Does not own the `msg.top.*` / `entity.*` lang keys (C7, resources).
  **One exception**: the damage line (MECH-IPW-08) decides here what it says, because no data
  loader exists for it; the damage it reports is `structural-damage`'s.

## Key types

| class | role |
|-------|------|
| `TopIntegration` | TOP entry: IMC `getTheOneProbe` handshake, registers 3 TOP callbacks. Static, no-op if TOP absent |
| `TopIntegration$GetTheOneProbe` | `Function<ITheOneProbe,Void>` invoked by TOP to register providers/overrides |
| `RocketEntityDisplayOverride` | TOP `IEntityDisplayOverride` — replaces standard rocket name/modname line |
| `RocketEntityProbeProvider` | TOP `IProbeInfoEntityProvider` — rocket guidance (+fuel in EXTENDED) |
| `DataBlockProbeProvider` | TOP `IProbeInfoProvider` — data-storage + transceiver info (EXTENDED only) |
| `DamageProbeProvider` | TOP `IProbeInfoProvider` — a block position's structural damage, every probe mode (MECH-IPW-08) |
| `TOPDataContext` | `AbstractDataContext` over `IProbeInfo`; rich (progress bars, item rows via a probe stack) |
| `WailaIntegration` | `@WailaPlugin` — registers rocket + block NBT/head/body providers |
| `RocketEntityProvider` | Waila `IWailaEntityProvider` — head name override + body guidance/fuel + NBT sync |
| `DataBlockProvider` | Waila `IWailaDataProvider` — data/transceiver body + NBT sync; inner client-side loaders |
| `WailaDataContext` | `AbstractDataContext` over a `List<String>`; text-only, client `I18n` |

## Mechanics

- **MECH-IPW-01 — TOP registration (IMC).** `TopIntegration.register()` returns early unless
  `theoneprobe` is loaded, else sends an `FMLInterModComms` function message naming the
  `GetTheOneProbe` function class; TOP calls it back with `ITheOneProbe`, which registers the
  display override, entity provider and the two block providers (data, damage). Called from mod
  `preInit`. `TopIntegration#register`, `TopIntegration$GetTheOneProbe#apply`.
- **MECH-IPW-02 — Waila registration (@WailaPlugin).** Waila auto-discovers `WailaIntegration`
  via the annotation; `register()` binds one `RocketEntityProvider` as NBT+head+body provider
  for `EntityRocket.class` and one `DataBlockProvider` as NBT+body provider for `Block.class`
  (i.e. every block). `WailaIntegration.java:12-22`.
- **MECH-IPW-03 — Rocket name override.** Maps `EntityRocket.getRocketFuelType()` →
  monopropellant / bipropellant / nuclear / default lang key. TOP replaces the whole standard
  info block and returns `true`; Waila replaces head element 0.
  `RocketEntityDisplayOverride.java:34-47`, `RocketEntityProvider.java:113-134`.
- **MECH-IPW-04 — Rocket body info.** Delegates to `RocketDataLoader`: guidance info always;
  fuel info gated on `ProbeMode.EXTENDED` for TOP but shown unconditionally for Waila.
  `RocketEntityProbeProvider.java:29-35`, `RocketEntityProvider.java:94-111`.
- **MECH-IPW-05 — Data-block / transceiver info.** For a hit tile: if
  `TileWirelessTransceiver`, add wireless info and suppress the "locked" line; then if
  `DataBlockDataLoader.getDataStorage(tile) != null`, add common data info. TOP is gated on
  `EXTENDED`; Waila is always-on. `DataBlockProbeProvider.java:29-50`,
  `DataBlockProvider.java:26-50,105-119`.
- **MECH-IPW-06 — Context adapters.** `TOPDataContext` renders rich rows (progress bars,
  item-stack columns via a `Stack<IProbeInfo>`), `supportsRichData()==true`, and defers
  localization. `WailaDataContext` renders `amount/capacity suffix` plain text, ignores item
  stacks, `supportsRichData()==false`, and localizes immediately.
  `TOPDataContext.java:20-73`, `WailaDataContext.java:20-53`.
- **MECH-IPW-07 — Waila server→client sync.** `getNBTData` (server, `EntityPlayerMP`) packs
  loader output into the accessor tag; `getWailaBody/Head` (client) rebuild throwaway loaders
  (`WailaRocketDataLoader`, `WailaDataBlockLoader`, `WailaWirelessTransceiverLoader`) that read
  those keys back and feed a `WailaDataContext`. `RocketEntityProvider.java:63-92,26-61`,
  `DataBlockProvider.java:26-50,52-103`.
- **MECH-IPW-08 — The damage line (TOP only).** For the probed POSITION, in every probe mode:
  nothing when the position has no damage record; otherwise one line — the stage out of the
  block's maximum (`DamageState#getStage` / `#getMaxStage`), or, where the damage map records a
  DESTRUCTION at that position, "destroyed here, recorded as" and the recorded block's name
  instead of a stage — plus "cannot be welded" when `RepairCost#perStage` cannot price the block
  standing there (the welder's `NO_RECIPE`). Read by position, so a block put back over a record by
  anything other than a player's own placement (which clears it, `structural-damage` MECH-DMG-15)
  is told the record. A HOLE is never told: a destroyed position is air and the probe cannot be
  aimed at air. Keys `msg.top.stellurgy.damage.{stage,destroyed,noRecipe}`, translated on the
  client. `DamageProbeProvider#addProbeInfo` [T `BlockProbeLineClientGroupTest`].
  **Not on Waila, and not buildable there without a client copy of the damage map**: Hwyla asks
  the server for a block's data only when the client holds a TILE at that position
  (`MetaDataProvider`, measured on hwyla-253449-2568751: the tile-request branch is skipped on a
  null tile), and plain hull blocks — the blocks the damage map exists for — have none; the client
  holds no damage of its own (`DamageState#setStage` returns on a client world). A client channel for
  position-keyed damage, not built, is what would change that.

## State & persistence

**All NBT here is transient Waila sync payload** (accessor tag), never written to world save.
TOP passes no NBT (server-side collection). Keys (all owned/written by this subsystem):

| container | keys | writer → reader | type |
|-----------|------|-----------------|------|
| `stellurgy_transceiver` | `linked` bool, `extracting` bool, `networkId` int | `DataBlockProvider.getNBTData` → `WailaWirelessTransceiverLoader` | wire |
| `stellurgy_data` | `locked` bool, `data` int, `maxData` int, `type` int (`DataType.id`) | `DataBlockProvider.getNBTData` → `WailaDataBlockLoader` | wire |
| `landing` | `x` int, `y` short, `z` int, `name` str | `RocketEntityProvider.getNBTData` → `WailaRocketDataLoader.getLandingLocation` | wire |
| root | `stack` compound (guidance computer), `dest` str, `fuelType` int (`FuelType.id`) | `RocketEntityProvider.getNBTData` → body/head | wire |

`data`/`maxData`/`type`/`locked` and `x`/`y`/`z`/`name` collide by *string* with other
subsystems in `seam-nbt.tsv`, but here they live inside private sub-compounds of a transient
Waila tag, so there is no persistence-key conflict.

## Invariants

- **INV-IPW-01 [V]** TOP registration is a no-op when `theoneprobe` is not loaded; no TOP
  classes are touched otherwise. `TopIntegration.java:15-17`.
- **INV-IPW-02 [V][BEH]** TOP block/data info only renders in `ProbeMode.EXTENDED`; Waila renders
  it unconditionally — an intentional UX asymmetry between the two mods.
  `DataBlockProbeProvider.java:29`, `DataBlockProvider.java:105-119`.
- **INV-IPW-03 [V]** The Waila sync keys in State & persistence are the sole contract between
  server `getNBTData` and client body/head; a key written but absent from `hasKey` guards is
  silently skipped client-side. `RocketEntityProvider.java:100,116`, `DataBlockProvider.java:110,115`.
- **INV-IPW-04 [V]** Localization side matches each mod's collection side: `TOPDataContext.translate`
  emits `STARTLOC+key+ENDLOC` for client-side resolution (TOP collects on server);
  `WailaDataContext.translate` calls client `I18n.format` directly (Waila collects on client).
  `TOPDataContext.java:65-68`, `WailaDataContext.java:44-48`.
- **INV-IPW-05 [V][BEH]** The data-storage "locked" line is suppressed whenever the same tile is a
  `TileWirelessTransceiver` (`showLockedLine=false`). `DataBlockProbeProvider.java:39-44`,
  `DataBlockProvider.java:108-114`.
- **INV-IPW-07 [T][BEH]** A position with no damage record gets no damage line at all, in any probe
  mode. `DamageProbeProvider#addProbeInfo`. Pinned by
  `BlockProbeLineClientGroupTest#aStruckBlockIsToldItsStageAndKeepsItWhenTheBlockIsPutBack`.
- **INV-IPW-08 [T][BEH]** A position with a damage record gets exactly one damage line, decided on the
  server from that record: its stage out of the block's maximum, or — where the record is a
  DESTRUCTION — "destroyed here" and the recorded block's name instead of a stage; plus "cannot be
  welded" exactly when the welder cannot price the block standing there, and nothing else.
  `DamageProbeProvider#addProbeInfo`. Pinned by
  `BlockProbeLineClientGroupTest#aStruckBlockIsToldItsStageAndKeepsItWhenTheBlockIsPutBack`,
  `BlockProbeLineClientGroupTest#aStruckBlockNothingCraftsIsToldItCannotBeWelded`,
  `BlockProbeLineClientGroupTest#aBlockStandingOnARecordedDestructionIsToldSo`.
- **INV-IPW-06 [A]** `getWailaHead` assumes `currenttip` has an element 0 to `remove` when
  `fuelType` is present; relies on Waila always supplying the default name line first.
  `RocketEntityProvider.java:116-118`.

## Failure modes & edge cases

- `getWailaHead` calls `currenttip.remove(0)` unguarded; `fuelType` is written for every
  `EntityRocket` (`RocketEntityProvider.java:89`), so the branch always runs — an empty head
  list would throw `IndexOutOfBoundsException`.
- `FuelType.getById` / `DataType.getById` on a bad id could return null; comparisons fall
  through to the default name / behaviour rather than NPE (`RocketEntityProvider.java:118,123`).
- TOP `DataBlockProbeProvider` shows nothing outside EXTENDED mode; a user in normal probe mode
  sees no Stellurgy data-block info at all.

## Integration seams

- **Foreign APIs**: TOP `ITheOneProbe`/`IProbeInfo*`/`IEntityDisplayOverride`; Waila
  `IWailaPlugin`/`IWailaRegistrar`/`IWailaDataProvider`/`IWailaEntityProvider`. Discovery: TOP
  via `FMLInterModComms` IMC function message; Waila via `@WailaPlugin` annotation scan.
- **Internal**: `AbstractDataContext` and the `*DataLoader` / `*DataLoaderServer` hierarchy
  (integration-dataloaders); `EntityRocket` (rocket-entity), `DataStorage`/`FuelType`/`DataType`
  (api-public), `TileWirelessTransceiver` (wirelessdata).
- **No packets, mixins, capabilities, or Forge event handlers** owned here.

## Config surface

No `StellurgyConfiguration` flags. Full-disable path is external only: TOP disabled by not installing
the mod (guarded by `Loader.isModLoaded`), Waila disabled by not installing Waila (plugin never
discovered). There is no in-mod toggle to suppress these integrations while the HUD mod is present.

## Test coverage

`BlockProbeLineClientGroupTest` (client) pins MECH-IPW-08 on what a client RECEIVES from the TOP
server (a test-only recorder on the reply packet, `top_block_info`): no line on an untouched block,
stage/max on a struck one and on the same kind put back over it, "cannot be welded" on a block the
welder refuses `NO_RECIPE`, and the destruction line on a block standing on a recorded destruction.
It does not see the overlay being drawn. The other adapters have no dedicated tests; loader
behaviour they delegate to is covered by the integration-dataloaders suite.

## Open questions

- Whether Waila ever calls `getWailaHead` with an empty tip list (bounds of INV-IPW-06) — not
  determinable from this subsystem; depends on Waila internals.
