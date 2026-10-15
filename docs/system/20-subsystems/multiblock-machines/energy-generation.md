# Energy Generation

Part of [multiblock-machines](./00-overview.md). Files: `energy/TileSolarArray.java` (235),
`energy/TileMicrowaveReciever.java` (698), `energy/TileBlackHoleGenerator.java` (292). All three
extend libVulpes `TileMultiPowerProducer` and call `producePower(n)`.

## Purpose

The three multiblock **power producers**: photovoltaic array (scales with orbit insolation),
microwave receiver (pulls beamed power from orbital solar satellites and fries anything above it),
and black-hole generator (feeds items into a black hole for burst power).

## Key types

| class | role |
|-------|------|
| `TileSolarArray` | counts `blockSolarArrayPanel` cells, produces `panels × insolation × mult` |
| `TileMicrowaveReciever` | sums `IUniversalEnergyTransmitter` satellites; also hatch save/restore |
| `TileBlackHoleGenerator` | consumes items on a timer while orbiting a black-hole star |

## Mechanics

### MECH-MBM-12 — Solar array power
On completion the array's `completeStructure` override counts panels into `numPanels`. Each server
tick, when `enabled` and (daytime & sky above, or in the space dim & sky below), it produces
`min(4096, numPanels × 1.0005 × 2 × insolation) × solarGeneratorMult`. Insolation comes from the
station's `getInsolationMultiplier()` in the space dim, else the dim's
`getPeakInsolationMultiplier()`. Power changes are pushed via `PacketMachine` id 1. [V]
`TileSolarArray.java:97-156`.

### MECH-MBM-13 — Microwave receiver
Recomputes an insolation multiplier when uninitialised or when the station's orbiting planet
changes. Each server tick it walks the satellite ids from `ItemSatelliteIdentificationChip`s in
its input hatches, and for each `IUniversalEnergyTransmitter` satellite in the same planetary
system (`PlanetaryTravelHelper.isTravelAnywhereInPlanetarySystem`) adds `transmitEnergy(UP,false)`,
scaled by `2 × insolation`. Every 100 ticks while producing it also **destroys** the first solid
block above each panel column and sets nearby entities on fire (`powerMadeLastTick/10`). Hatch
contents are snapshotted/restored across (de)construction (MECH-MBM-02). [V]
`TileMicrowaveReciever.java:200-324,245-270`.

### MECH-MBM-14 — Black-hole generator
Fires only when `enabled` **and** the tile orbits a star whose `getStarData().isBlackHole()`.
`attemptFire` consumes one item from an input hatch (if not energy-full) and sets
`last_usage = worldTime + timeFromStack`, where the burn time is looked up per item in
`blackHoleGeneratorBlocks` (default `defaultItemTimeBlackHole`). While `last_usage > worldTime` it
produces `500 × blackHolePowerMultiplier` RF/t. [V] `TileBlackHoleGenerator.java:138-214`.

## State & persistence

These producers persist almost nothing bespoke — power is recomputed each tick. The description-packet / update-tag keys (client
render only: `amtPwr`, `canRender`, `numPanels`, and the alternative `powerMadeLastTick` key on the `SolarArray` tag path) and the
`MicrowaveReciever` persisted hatch snapshot (MECH-MBM-02) are listed in `C1-nbt-persistence`.

## Integration seams

- **Packets**: `PacketMachine` id 1 (power value) + vanilla description packet.
- **→ satellite**: `IUniversalEnergyTransmitter.transmitEnergy`, `SatelliteRegistry.getSatelliteId`.
- **→ space-stations / dimension-planets**: station insolation, `getStarData().isBlackHole()`,
  `getOrbitingPlanet()`.
- **→ world-gen/util**: `PlanetaryTravelHelper`, `DimensionManager.getEffectiveDimId`.

## Config surface

Config: see `C4-config-surface`. There is no single "disable" flag for these producers other than not building them.

## Invariants

- **INV-MBM-09** [A][BEH] The solar array validates only with all panels + flanking `p` plug present,
  and a stone-filled `*` wildcard cell breaks formation.
  `SolarArrayMultiblockTest.java:29-88`. Pinned by `SolarArrayMultiblockTest#solarArrayMultiblockValidatesWhenFixtureIsBuilt`, `SolarArrayMultiblockTest#solarArrayMultiblockInvalidatesWhenFlankingPlugRemoved`, `SolarArrayMultiblockTest#solarArrayMultiblockInvalidatesWhenWildcardCellFilledWithStone`.
- **INV-MBM-10** [V][BEH] The black-hole generator produces power **iff** it orbits a black-hole star
  and is enabled; `isAroundBlackHole` returns false off a black hole. `TileBlackHoleGenerator.java:166-208`.
- **INV-MBM-11** [A][BEH] The microwave receiver validates as a multiblock and loses its structure when a
  corner or adjacent panel is removed. Production from a connected transmitter satellite is not asserted. Pinned by `MicrowaveReceiverMultiblockTest#microwaveReceiverMultiblockValidatesWhenFixtureIsBuilt`, `MicrowaveReceiverMultiblockTest#microwaveReceiverMultiblockInvalidatesWhenCornerPanelRemoved`, `MicrowaveReceiverMultiblockTest#microwaveReceiverMultiblockInvalidatesWhenAdjacentPanelRemoved`.
- **INV-MBM-12** [A] All power math is recomputed every tick from live inputs, so a server
  restart cannot desync stored "last tick power" — assumed, since no producer persists it to disk
  (only to the description packet). `TileSolarArray.java:126-156`.

## Test coverage

`SolarArrayMultiblockTest` (INV-MBM-09), `MicrowaveReceiverMultiblockTest` (INV-MBM-11),
`BlackHoleGeneratorMultiblockTest`, `BlackHoleGeneratorPoweredCycleTest` (INV-MBM-10).

## Failure modes & edge cases

- **SolarArray NPE.** `TileSolarArray.update:138` dereferences
  `getSpaceStationFromBlockCoords(this.pos).getInsolationMultiplier()` with no null guard when in
  the space dim. `TileMicrowaveReciever` guards the identical lookup (`station != null`); the solar
  array does not, so an early tick before the station object resolves (or a panel built in the
  space dim but not on a registered station) NPEs the tick loop.
- Microwave receiver's every-100-tick block destruction runs a `world.getHeight`/`setBlockToAir`
  scan inside the tick — bounded to the panel footprint, so not unbounded, but a per-tick world
  write worth noting.
