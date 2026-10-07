---
id: integration-dataloaders
owns: [integration/dataloaders/]
entrypoints: [RocketDataLoader#addGuidanceInfo, RocketDataLoader#addFuelInfo, DataBlockDataLoader#addCommonDataInfo, WirelessTransceiverDataLoader#addWirelessDataInfo]
depends-on: [rocket-entity, dimension-planets, space-stations, api-public, satellite, infrastructure-tiles, items]
depended-by: [integration-probe-waila]
contracts: [C4, C7]
confidence: high
---

## Purpose

A tooltip-rendering **abstraction layer** shared by the WAILA and The One Probe (TOP)
integrations. It turns Stellurgy game objects (rockets, data blocks, wireless transceivers) into
ordered "add a line / add a progress bar" calls against a mod-agnostic sink, so the two
probe mods do not each re-implement the same fuel-gauge / guidance / data-buffer formatting.
Owns no persistence, no packets, no registrations — it is pure presentation glue.

## Responsibility boundary

Owns: the `AbstractDataContext` sink interface; the three loader hierarchies
(`RocketDataLoader`, `DataBlockDataLoader`, `WirelessTransceiverDataLoader`) and their
`*Server` implementations that read live game state and emit formatted tooltip lines.

Does NOT own: the concrete `AbstractDataContext` subclasses `WailaDataContext` /
`TOPDataContext` and the provider classes that instantiate the loaders — those live in
`integration-probe-waila`. Does NOT own any of the game state it reads (rocket stats,
`DataStorage`, `DimensionProperties`, space objects, guidance items).

## Key types

| class | role |
|-------|------|
| `AbstractDataContext` | Sink SPI: `addMessage`, `addProgressBar`, `pushStack/popStack`, `supportsRichData`, `translate`. Side of execution is mod-defined (WAILA=client, TOP=server). |
| `RocketDataLoader` (abstract) | Formats a rocket's guidance destination + fuel gauges; 4 abstract getters bind it to a data source. |
| `RocketDataLoaderServer` | Binds `RocketDataLoader` to a live `EntityRocket` via `rocket.storage`. |
| `DataBlockDataLoader` (abstract) | Formats data-type + data buffer bar for data-bearing tiles; static `getDataStorage(TileEntity)` resolves the source. |
| `DataBlockDataLoaderServer` | Binds `DataBlockDataLoader` to a `DataStorage`. |
| `WirelessTransceiverDataLoader` (abstract) | Formats extract/insert mode badge + link status + network id. |
| `WirelessTransceiverDataLoaderServer` | Binds to a `TileWirelessTransceiver`. |

## Mechanics

- **MECH-DL-01 Guidance readout** — `addGuidanceInfo` inspects the guidance-computer stack
  and dispatches by item type (asteroid chip / station chip / planet chip / linker / bare),
  emitting one coloured destination line. Empty/absent computer → "no computer / no
  destination" line. Station-deployed rockets divert to harvest-gas info instead.
  `RocketDataLoader.java:49`, dispatch `:193`, fallthrough `:242`.
- **MECH-DL-02 Destination resolution** — for a bare/linker stack it resolves the live
  destination dimension via `rocket.storage.getDestinationDimId(...)`, special-casing orbit
  (experimental space flight), warp dim, and space-station targets (with optional landing
  pad), else prints the `DimensionProperties` name + optional coords.
  `RocketDataLoader.java:242-305`.
- **MECH-DL-03 Fuel gauges** — `addFuelInfo` switches on the rocket's primary `FuelType`
  and emits one progress bar per relevant fluid (mono: fuel; bi: fuel + oxidizer; nuclear:
  working fluid). Bars carry Stellurgy's fixed gauge colours. `RocketDataLoader.java:72`,
  `addFuelSection:166`.
- **MECH-DL-04 Fluid name prettify** — `getPrettyFluidName` maps a fluid registry name to a
  localized display name, degrading to the raw registry name, then to an "unknown fluid" or
  "no fuel" line when capacity>0 but no fluid resolves. `RocketDataLoader.java:141-185`.
- **MECH-DL-05 Data-buffer readout** — `addCommonDataInfo` emits a data-type line
  (`type.toString()` used as a lang key) plus a buffer progress bar, and an optional
  "locked" line; `getDataStorage` resolves the buffer from transceiver / data-bus /
  satellite-terminal tiles. `DataBlockDataLoader.java:21-65`.
- **MECH-DL-06 Wireless status readout** — `addWirelessDataInfo` emits a coloured
  extract/insert mode badge + green/red link badge, and the network id line only when
  linked. `WirelessTransceiverDataLoader.java:8-21`.
- **MECH-DL-07 Rich-vs-plain rendering** — `supportsRichData()` selects behaviour:
  TOP (rich) renders the guidance item icon via `pushStack`/`popStack` and native progress
  bars; WAILA (plain) collapses stacks to no-ops and renders a "dest:" prefix + text bar.
  `RocketDataLoader.java:187` (`wrapDestination`), sink impls in `integration-probe-waila`.

## State & persistence

None. This subsystem holds no fields that persist and writes no NBT. All state is read
transiently from other subsystems during a single tooltip render.

## Invariants

- **INV-DL-01 [V]** No line is emitted through a channel other than
  `AbstractDataContext.addMessage`/`addProgressBar` — the loaders never touch WAILA/TOP
  APIs directly, keeping them mod-agnostic. `RocketDataLoader.java` (whole file uses only
  `context.*`).
- **INV-DL-02 [V]** Progress bars are suppressed when `capacity <= 0` (fuel) and clamp
  `max = Math.max(1, getMaxData())` (data), so no divide-by-zero / empty bar reaches the
  sink. `RocketDataLoader.java:167`, `DataBlockDataLoader.java:53`.
- **INV-DL-03 [V]** `translate` is always routed through the context, never
  `net.minecraft.client...I18n` directly, so the loaders stay side-neutral (TOP resolves on
  server via STARTLOC/ENDLOC markers, WAILA on client via I18n). `AbstractDataContext.java:24`.
- **INV-DL-04 [V]** Station-deployed rockets are handled before the guidance-computer path,
  so they always show harvest-gas info, never a guidance line. `RocketDataLoader.java:51-54`.
- **INV-DL-05 [A]** Only `*Server` concrete loaders exist; the abstract bases were designed
  for a client/server split that was never realized, so WAILA (client) reads server-shaped
  state (`rocket.storage`) on the client. Inferred from the absence of any `*Client` class.

## Failure modes & edge cases

- `getDestinationName()` returns `null` when the guidance computer is absent
  (`RocketDataLoaderServer.java:58`), but its consumer calls `name.isEmpty()` without a null
  guard (`RocketDataLoader.java:295-296`). Not currently reachable because that code path is
  only entered while a non-empty guidance stack (hence non-null gc) exists — a latent NPE,
  .
- `getPrettyFluidName` returns `null` for the literal string `"null"` and empty names,
  which `addFuelSection` treats as "no resolvable fluid". `RocketDataLoader.java:142`.
- `getFluidDisplayName` triple-falls-back (localized stack name → localized base name → raw
  `fluid.getName()`) so a mod fluid with broken lang keys still prints something.
  `RocketDataLoader.java:154-164`.

## Integration seams

- **No owned contract seams.** These files register nothing and persist nothing: no NBT
  keys, no registry names, no packets, no mixins (confirmed against `coverage/seam-*.tsv` —
  zero rows reference these basenames).
- **Consumed by** `integration-probe-waila`: `WailaDataContext` / `TOPDataContext`
  implement `AbstractDataContext`; the WAILA/TOP provider classes construct the `*Server`
  loaders and drive the entrypoints.
- **Reads across subsystems**: `EntityRocket` + `StorageChunk` (rocket-entity),
  `DimensionManager`/`DimensionProperties` (dimension-planets), `SpaceObjectManager` +
  `ISpaceObject` (space-stations), `DataStorage` + `StatsRocket` + `FuelType` (api-public),
  guidance items `ItemAsteroidChip`/`ItemStationChip`/`ItemPlanetIdentificationChip` +
  libVulpes `ItemLinker` (items), `TileWirelessTransceiver`/`TileDataBus`/
  `TileSatelliteTerminal` (satellite, infrastructure-tiles).

## Config surface

Config: see `C4-config-surface` (this subsystem is a read-only consumer and gates nothing itself).

Lang keys (C7): all under `msg.top.stellurgy.guidance.*`, `.fuel.*`, `.harvest.*`,
`.data.*` plus `data.undefined.name`; passed to `context.translate`, never resolved here.

## Test coverage

None. No `src/test` file references any of these classes (grep dry). All invariants are
`[V]` from code read except INV-DL-05 `[A]`. This is presentation-only glue with no test
oracle.

## Open questions

- Is INV-DL-05 a real side hazard? On WAILA (client) `RocketDataLoaderServer` reads
  `rocket.storage.getGuidanceComputer()`; whether the client entity carries a populated
  `StorageChunk` (guidance computer tile + destination NBT) is decided by rocket-entity
  sync, outside this subsystem. If the client copy is thinner, WAILA guidance lines could
  differ from TOP's server-resolved ones. → verify against rocket-entity F1/F2 sync.
