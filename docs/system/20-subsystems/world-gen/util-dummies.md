# world-gen · util (dummy worlds, teleporters, scanner store)

Parent: [00-overview.md](./00-overview.md). Covers `world/util/*`: the fake
off-world used to render a rocket's block snapshot, the two teleporters, and the
`MultiData` scanner-data container. These are support types, not world generation.

## Key types

| class | role |
|-------|------|
| `WorldDummy` | minimal `World` backed by a single `StorageChunk`; used to render rocket contents without a real dimension |
| `ChunkProviderDummy` | serves `storage.chunk` at (0,0), empty chunks elsewhere |
| `DummySaveHandler` | `ISaveHandler` that persists nothing; clones overworld `WorldInfo` |
| `MapStorageDummy` | `MapStorage` that stores/loads nothing |
| `ProviderDummy` | trivial `WorldProvider` (reports `DimensionType.NETHER`) |
| `BasicTeleporter` | `ITeleporter` that drops an entity at a fixed `BlockPos` |
| `TeleporterSeekBlock` | `BasicTeleporter` that scans upward for the first 2-high air gap |
| `MultiData` | `IDataHandler` holding one `DataStorage` lane per `DataType` (satellite/scanner readouts) |

## Mechanics

### MECH-WGEN-25 — dummy off-world for rocket rendering
`WorldDummy` extends `World` with a `DummySaveHandler` + fresh `WorldInfo`
(cloned from overworld) + `ProviderDummy`, and installs a `ChunkProviderDummy`
that returns the supplied `StorageChunk`'s chunk at (0,0)
(`world/util/WorldDummy.java:32-38`, `ChunkProviderDummy.java:23-28`). All block/
tile lookups delegate to the `StorageChunk`; ticking, entity iteration and chunk
loading are stubbed out; sky light is forced to 15 and biome to `spaceBiome`
(`WorldDummy.java:59-141`). Capabilities are gathered once in `init` so the fake
world satisfies capability queries (`:41-57`). This is how the client renders the
contents of a built rocket (`StorageChunk` is owned by `rocket-assembly`).

### MECH-WGEN-26 — teleporters
`BasicTeleporter.placeEntity` moves the entity to a fixed target
(`world/util/BasicTeleporter.java:19-22`). `TeleporterSeekBlock` overrides the
target resolution to walk up from the base position to the first column with two
stacked air blocks, so an entity isn't teleported into terrain
(`world/util/TeleporterSeekBlock.java:12-24`). Used by dimension/rocket transfer.

### MECH-WGEN-27 — MultiData scanner store
`MultiData` keeps a `HashMap<DataType, DataStorage>` with one lane per non-
`UNDEFINED` `DataType`, created in `reset()`
(`world/util/MultiData.java:30-35`). `addData`/`extractData` route to the lane for
the requested type; `writeToNBT`/`readFromNBT` persist each lane under the
`DataType.name()` key (`:82-123`). `readFromNBT` rebuilds each lane from the map
key so a lane stays permanently typed even if the persisted amount is 0
(`:93-123`). `getMaxData` reads the `ATMOSPHEREDENSITY` lane's max (`:62-64`).

## State & persistence (C1)

| key | owner | file:line | notes |
|-----|-------|-----------|-------|
| per-`DataType` compound (`COMPOSITION`,`MASS`,`DISTANCE`,`ATMOSPHEREDENSITY`,…) | `MultiData` | `MultiData.java:82-91` | one sub-compound per lane, keyed by enum name |

`DataStorage`'s own NBT layout is owned by `api-public`; `MultiData` only nests
one compound per lane. `WorldDummy`/`MapStorageDummy`/`DummySaveHandler` persist
**nothing** by design.

## Invariants

- **INV-WGEN-18 [V]** `WorldDummy` never ticks, never loads chunks, and reports a
  constant sky light of 15 (`WorldDummy.java:87-100,127-141`) — a render-only
  world.
- **INV-WGEN-19 [V]** `TeleporterSeekBlock` returns the base position unchanged if
  no 2-high air gap exists below world height (`TeleporterSeekBlock.java:17-23`).
- **INV-WGEN-20 [V]** `MultiData` lanes survive an NBT round-trip with their type
  preserved even at amount 0 (`MultiData.java:112-121`).
- **INV-WGEN-21 [A]** `MultiData.SUPPORTED_TYPES` (`:22-27`) is declared but never
  referenced; assumed dead / documentation-only. Low impact.

## Failure modes & edge cases

- `DummySaveHandler.loadWorldInfo` dereferences `DimensionManager.getWorld(0)`
  (`world/util/DummySaveHandler.java:18`) — NPE if the overworld isn't loaded when
  a `WorldDummy` is constructed. In practice the dummy is only built client-side
  after world join.
- `MultiData.setDataAmount(0, type)` is avoided in `readFromNBT` because
  `setData(0, …)` may clear the lane's type (`MultiData.java:116-119`) — a subtle
  ordering dependency in the `DataStorage` API.

## Config surface

None — this cluster reads no `StellurgyConfiguration` flags.

## Test coverage

- `MultiData`/`DataStorage` NBT → `unit/ItemDataCarrierNBTRoundTripTest`,
  `unit/SpecialPurposeItemContractTest` (indirect, via the satellite data path).
- No direct tests for `WorldDummy` / teleporters found.

## Open questions

- `ProviderDummy.getDimensionType()==NETHER` — chosen arbitrarily? Any code that
  branches on the dummy world's dimension type would misbehave; no such caller
  found, so assumed harmless.
