# api-public / satellite-data — `SatelliteBase`, `SatelliteProperties`, `DataStorage`

Files: `api/satellite/SatelliteBase.java` (243), `api/satellite/SatelliteProperties.java` (168),
`api/DataStorage.java` (194), `api/satellite/IDataHandler.java` (32), `api/ISatelliteIdItem.java` (10).

## Key types

| type | role |
|------|------|
| `SatelliteBase` | abstract orbiting satellite: properties, `UniversalBattery`, tick, NBT, GUI-sync stubs |
| `SatelliteProperties` | value object `implements IWeighted`: powerGen/Storage, maxData, type, id, weight |
| `DataStorage` | typed int buffer with `DataType` enum + lock/adopt/wildcard rules |
| `IDataHandler` | tile contract: `extractData`/`addData(max, type, dir, commit)` |
| `ISatelliteIdItem` | item contract: `setSatellite(stack, props)` |

## Mechanics

### MECH-API-18 — satellite NBT & battery heal
`SatelliteBase.writeToNBT` stores `dataType` (its registry key), a nested `properties` compound,
`dimId`, the battery, and the source `item` stack (`SatelliteBase:182-197`). `readFromNBT` restores
them and, if `powerStorage == 0` (legacy/blank), heals to a default 720 and rebuilds the battery —
**overwriting the just-read battery energy** (`:199-208`). Base `tickEntity` charges
`powerPerTick − 1` per tick as upkeep (`:111-114`). `setDimensionId` cannot yet change dimension
(TODO stubs, `:164-177`).

### MECH-API-19 — property flag bits
`SatelliteProperties.getPropertyFlag()` composes a bitmask from four `Property` enum flags
(`MAIN|DATA|POWER_GEN|BATTERY`, `1<<ordinal`) based on which fields are non-zero/non-null
(`SatelliteProperties:31-44,:154-166`). `SatelliteBase.acceptsItemInConstruction` uses it to decide
which components a chassis accepts (`SatelliteBase:40-43`). `setId` is write-once: it only assigns
when the current id is `-1` and returns whether it did (`SatelliteProperties:59-66`).

### MECH-API-20 — data buffer lock / adopt / wildcard
`DataStorage` holds `data`, `maxData`, a `DataType`, and a `locked` flag. When unlocked and empty it
*adopts* the incoming type; when it drains to 0 it reverts to `UNDEFINED` (`DataStorage:22-38,:134-142`).
`lockDataType(type)` pins the type and wipes mismatched data; `null` unlocks (`:69-79`). `readFromNBT`
heals a stale non-`UNDEFINED` type on an empty unlocked buffer (`:151-169`).

### MECH-API-21 — simulate-vs-commit transfer
`addData(amount, type, commit)` computes the accepted amount without mutating state when
`commit=false`, so callers can probe capacity before moving data; only on `commit=true` does it adopt
type and increment (`DataStorage:90-126`). `removeData(amount, commit)` mirrors it (`:134-142`). This
two-phase contract is the `IDataHandler` protocol (`extractData`/`addData` both take a `commit` flag).

## Invariants

- **INV-API-18 [V][SYS]** `SatelliteProperties.id` is write-once (`setId` guarded on `== -1`)
  (`SatelliteProperties:59-66`); a satellite keeps its first assigned id for life. FOR: public API: satellite identity is for life.
- **INV-API-19 [V][SYS]** `DataType` ordinals 0..6 (`UNDEFINED..MASS`) are a save contract: NBT stores
  `dataType.ordinal()` and read indexes `values()[...]`, guarded by a catch that falls back to
  `UNDEFINED` on out-of-range (`DataStorage:144-158,:171-193`). FOR: save format: DataType ordinals.
- **INV-API-20 [V][SYS]** An empty unlocked `DataStorage` always normalises its type to `UNDEFINED` on
  write, read, and drain (`:32-34,:138-139,:166-168`) — no stale type survives an empty buffer. FOR: save format: DataStorage.
- **INV-API-21 [V][SYS]** `DataType.toString()` returns a lang key `data.<name>.name`, not the enum name
  (`:190-192`) — it is a display contract, do not use as an id. FOR: public API: DataType display key.
- **INV-API-22 [V]** `SatelliteBase.acceptsItemInConstruction` dereferences
  `getSatelliteProperty(item)` with no null guard (`SatelliteBase:41`), while `getSatelliteProperty`
  is documented to return `null` for an unregistered stack (`SatelliteRegistry:48`) — NPE on an
  unregistered component. .

## State & persistence (C1)

Satellite, `SatelliteProperties` and `DataStorage` keys: see `C1-nbt-persistence`. One shared key name is the thing to know:
`dataType` is used by `SatelliteBase` for the satellite type key and by `SatelliteProperties.writeToNBT` for the satellite type string
(adjacent compounds); `DataStorage` separately writes capitalised `DataType` for its enum, and its `locked` key is optional.

## Integration seams

- `IDataHandler` (tile contract in infrastructure/satellite subsystems) — the `commit` two-phase
  protocol above.
- `ISatelliteIdItem.setSatellite` — implemented by the ID-chip item; `SatelliteBase.getControllerItemStack`
  casts to it (`SatelliteBase:87-90`).
- GUI sync stubs (`numberChangesToSend`/`sendChanges`/`onChangeReceived`) let a satellite push
  changes through a modular container (`SatelliteBase:226-240`).

## Config surface

Config: see `C4-config-surface`. No config flag gates `DataStorage`/`SatelliteProperties` directly.

## Open questions

- Whether the battery-heal path (MECH-API-18) ever discards live energy in practice, or only fires on
  truly blank legacy satellites .
