# infrastructure-tiles / guidance hatch & data/inventory hatches

Hatch tiles: the guidance-computer access hatch (chip auto-eject), the data-bus hatches
(satellite data storage), the satellite hatch (deploy weight + back-nav), and the plain
inventory hatch.

## Key types

| class | role |
|-------|------|
| TileGuidanceComputerAccessHatch | `TilePointer` + `IInfrastructure`; proxies the rocket's guidance-computer slot; auto-ejects landing chips |
| TileDataBus | `TileInventoryHatch` + `IDataInventory`; buffers `DataStorage`, moves data chip ↔ bus |
| TileDataBusBig | `TileDataBus` with `dataBusBigMultiplier`× capacity |
| TileSatelliteHatch | `TileInventoryHatch` + `IWeighted`; builds `SatelliteBase`; contributes deploy weight |
| TileInvHatch | thin named `TileInventoryHatch` |

## Mechanics

- **MECH-INFRA-18 — Guidance slot proxy.** `TileGuidanceComputerAccessHatch` implements
  `IInventory` by *forwarding every slot op* to the linked rocket's
  `storage.getGuidanceComputer()`; with no rocket the slot reads `EMPTY`. Redstone output
  reflects "guidance computer holds an item (and chip already ejected / auto-eject off)".
  [V TileGuidanceComputerAccessHatch.java:104-193, 311-318]
- **MECH-INFRA-19 — Chip auto-eject on link.** On `linkRocket`, if `buttonAutoEject` is
  on and the chip type matches the enabled per-type toggle (satellite/planet/station),
  the chip is merged into an adjacent `IInventory` and removed from the computer;
  `chipEjected` latches to avoid re-eject. A 4-bit `buttonState` mask holds the toggles.
  [V TileGuidanceComputerAccessHatch.java:232-262, 370-412]
- **MECH-INFRA-20 — Data bus transfer.** `TileDataBus` moves data between a
  `IDataItem` chip in slot 0 and its internal `DataStorage` (cap `BASE_MAX_DATA`, tunable):
  auto-loads chip→bus or stores bus→chip on `setInventorySlotContents`, moving exactly the
  accepted amount and ejecting the spent chip to slot 1. Exposes `addData`/`extractData`
  for adjacent machines; type-locked storage refuses mismatched types.
  [V TileDataBus.java:45-105, 149-181, 223-228]
- **MECH-INFRA-21 — Big data bus capacity.** `TileDataBusBig` re-derives its max as
  `BASE_MAX_DATA * dataBusBigMultiplier` (C4, clamped 1..20, default 4) in the ctor and
  after every NBT load, clamping stored data down if the multiplier shrank.
  [V TileDataBusBig.java:20-58]
- **MECH-INFRA-22 — Satellite hatch weight & build.** `TileSatelliteHatch.getSatellite`
  constructs a `SatelliteBase` from an `ItemSatellite` via `SatelliteRegistry`;
  `getWeight` returns the satellite's or packed-structure's weight into the rocket
  assembly's weight engine. A client-side back button routes to the rocket GUI via
  `PacketBackToRocketGui`. [V TileSatelliteHatch.java:46-100]

## State & persistence

NBT keys: see `C1-nbt-persistence` (guidance-and-hatches). Notes: `statuses` is the **persisted** 4-bit auto-eject toggle mask, `status`
is the same mask over the network (wire only, `readDataFromNetwork`); `TileDataBus` persists its `DataStorage` through the libVulpes
`*NBTHelper` hooks; `TileSatelliteHatch`/`TileInvHatch` persist only the parent inventory.
[V TileGuidanceComputerAccessHatch.java:414-441; TileDataBus.java:198-209]

## Invariants

- **INV-INFRA-17 [V]** The guidance hatch owns no chip storage: all slot access proxies
  the rocket's guidance computer, returning `EMPTY`/false when unlinked.
  [V TileGuidanceComputerAccessHatch.java:106-170]
- **INV-INFRA-18 [V]** Auto-eject fires at most once per link (`chipEjected` latch) and
  only for chip types whose per-type toggle is enabled.
  [V TileGuidanceComputerAccessHatch.java:236-247]
- **INV-INFRA-19 [V]** Data transfer moves exactly the accepted amount — chip and bus stay
  conserved (`addData(...,true)` returns moved, then `removeData(moved)`).
  [V TileDataBus.java:55-64]
- **INV-INFRA-20 [V]** Big-bus capacity is re-enforced after every load, clamping
  overfull stored data. [V TileDataBusBig.java:44-46, 55-57]
- **INV-INFRA-21 [A]** Persisted mask key `statuses` and wire key `status` intentionally
  differ; both encode the same 4 toggles. (Naming split, not a data mismatch.)
  [A TileGuidanceComputerAccessHatch.java:391, 425]

## Failure modes & edge cases

- Guidance hatch `writeToNBT` sets its keys **before** `super.writeToNBT` and `readFromNBT`
  reads them **before** `super.readFromNBT`; keys are disjoint from the parent so ordering
  is benign. [V TileGuidanceComputerAccessHatch.java:414-441]
- `TileSatelliteHatch.onInventoryButtonPressed` is `@SideOnly(CLIENT)` and only sends a
  packet — safe. [V TileSatelliteHatch.java:46-55]

## Integration seams

`TilePointer`/`TileInventoryHatch` (libVulpes). Packets: `PacketBackToRocketGui` (C2,
network-wire), `PacketMachine`. Registry: chip items (`ItemSatelliteIdentificationChip`,
`ItemPlanetIdentificationChip`, `ItemStationChip`), `SatelliteRegistry` (C3).

## Config surface

Config: see `C4-config-surface`. No other config gates these tiles.

## Test coverage

Data/guidance hatches are exercised indirectly via loader tests (guidance/satellite tiles
are skipped by loader transfer loops) and `RocketInfrastructureSmokeTest`; no dedicated
unit test — see Open questions.

## Open questions

- No direct test pins chip auto-eject (MECH-INFRA-19) or big-bus clamp (MECH-INFRA-21).
