---
id: F5-satellite-deploy-data-terminal
kind: flow
scenario: Satellite deploy → data collection → terminal readout
subsystems: [satellite, dimension-planets, api-public, items, wirelessdata, rocket-entity, infrastructure-tiles]
anchors-referenced: [MECH-SAT-08, MECH-SAT-01, MECH-SAT-03, MECH-SAT-10, MECH-SAT-02, MECH-SAT-11, MECH-SAT-13, MECH-INFRA-22, MECH-DIM-12, MECH-API-13, MECH-API-14, MECH-ITM-04, MECH-WDT-02, MECH-WDT-04, MECH-WDT-05]
confidence: high
---

## Scope

One satellite chip's life: built on the ground, flown up and released into a planet's orbital list,
ticked into data, then read back down through a control-center terminal and (optionally) piped out over
a wireless data network. The value is the four hand-offs where one subsystem writes state a *different*
subsystem later reads: the assembly→chassis id stamp, the chassis→live-object rebuild at deploy, the
orbital-list→terminal id resolution, and the terminal-buffer→wireless-transceiver drain. All
server-authoritative.

## Steps

1. **Build the satellite (items ⇄ satellite).** `TileSatelliteBuilder.assembleSatellite` sums power /
   data / weight from the part slots, mints a `SatelliteProperties` with a fresh id from
   `DimensionManager.getNextSatelliteId()`, and stamps that id into **both** the chassis
   (`ItemSatellite`, key `satId`) and the id chip (`ItemSatelliteIdentificationChip`, key
   `satelliteId`) — MECH-SAT-08 / INV-SAT-05. [V]
   *Seam A (id SSOT):* the two stamps use **divergent NBT keys** (INV-API-14 / MECH-API-14);
   everything downstream depends on both carrying the *same* long.
2. **Load the chassis into the rocket's satellite hatch (items → infrastructure-tiles).** The chassis
   stack sits in `TileSatelliteHatch` slot 0; `getWeight` reads its `SatelliteProperties.weight` into
   the assembly weight engine (MECH-INFRA-22). [V]
3. **Fly + release (rocket-entity → api-public → dimension-planets).** On arrival
   `EntityRocket.unpackSatellites` walks each hatch and calls `tile.getSatellite()`, which reads the
   chassis `SatelliteProperties` and rebuilds a live `SatelliteBase` via
   `SatelliteRegistry.getNewSatellite(properties.getSatelliteType())`, then `setProperties(chassis)`.
   This is where the persisted `dataType` string becomes a class again (MECH-API-13). A chassis whose
   `dataType` resolves to no class yields no satellite and the hatch is left as it was.
4. **Insert into the destination's orbital list (dimension-planets).** `unpackSatellites` computes the
   destination dim, then `properties.addSatellite(satellite, world2, isRemote)` puts it in that
   `DimensionProperties.satellites` map, sets its dimension id, adds it to `tickingSatellites` if
   `canTick()`, and broadcasts `PacketSatellite` to all clients (MECH-DIM-12). The hatch slot is then
   emptied: the chassis item is consumed and only the **chip** (kept by the player) still references
   the id.
5. **Collect data (satellite).** Each server tick `SatelliteData.tickEntity` drains `powerConsumption`
   from the on-board battery and adds one data point every `collectionTime` ticks up to `maxData`
   (MECH-SAT-01); `SatelliteBase.tickEntity` tops the battery up by `powerPerTick-1` (MECH-SAT-03). The
   buffer lives in the satellite's nested `data` `DataStorage` NBT. [V]
6. **Bring the chip to a Satellite Control Center (items → satellite).** Slot 0 accepts only an
   `ItemSatelliteIdentificationChip`. On connect (button 0 / packet id 100) `TileSatelliteTerminal`
   resolves the satellite: `getSatelliteId(chip)` reads the `satelliteId` key, then
   `DimensionManager.getSatellite(id)` **scans every loaded dimension's** orbital list for that id
   (MECH-SAT-10 / MECH-ITM-04 / MECH-API-14). [V]
7. **Gate + offload (satellite).** The terminal requires the resolved object to be a `SatelliteData`,
   in the same planetary system (`PlanetaryTravelHelper.isTravelAnywhereInPlanetarySystem(satDim,
   hereDim)`), and `energyStored ≥ getPowerPerOperation()` (INV-SAT-08). If so it calls
   `sat.performAction(player, world, pos)` and burns the power; `performAction` fetches the tile at
   `pos` (the terminal itself, an `IDataHandler`) and moves the buffer into it via
   `addData` / `removeData` (MECH-SAT-10 → MECH-SAT-02). [V]
8. **Auto-poll variant (satellite).** Independently, `performFunction` (every 16 ticks) runs
   `maybeAutoDownloadFromSatellite`: it needs an adjacent enabled + extract `TileWirelessTransceiver`
   (`hasExtractPlugAdjacent`), a fresh chip re-resolve, link and power; the interval doubles 64→512 on
   each miss and resets on success. The same `performAction` drain fills the terminal buffer
   (MECH-SAT-11). [V]
9. **Read out (satellite).** Terminal-buffer data goes one of two ways:
   - **To a data chip:** button −1 / −2 `storeData` / `loadData` move between the terminal buffer and an
     `IDataItem` in slot 1, only what the receiver accepts (MECH-SAT-13). [V]
   - **To a wireless network:** the adjacent extract `TileWirelessTransceiver` treats the terminal as the
     single `IDataHandler` on its back face and, on its own `transferIntervalTicks`, pulls each
     `DataType` out of the terminal into its buffer (MECH-WDT-02); `HandlerDataNetwork.tickAllNetworks`
     then fair-splits it to same-id sink transceivers (MECH-WDT-04/05). [V]

## Gaps & mismatches

- **Dual-key id (med).** Steps 1 / 3 / 6 hinge on the chassis (`satId`) and the chip (`satelliteId`)
  carrying the *same* id. The rebuild in step 3 reads the **chassis** id space; the readout in step 6
  reads the **chip** id space. Nothing at runtime re-checks they match after assembly — only INV-SAT-05
  (`SatelliteBuilderPressBuildContractTest`) pins the equality at build time. A chip programmed by any
  path other than the builder yields a chip that cannot resolve its deployed satellite (INV-API-14).
- **Non-defect seam note.** The terminal is *both* the offload sink (step 7, `addData` DOWN) and the
  wireless source (step 9). The wireless transceiver must sit on the terminal such that the terminal is
  the transceiver's resolved back-face `IDataHandler` (MECH-WDT-02 `resolveTransferFacing`);
  orientation, not a code disagreement, but the one placement constraint that silently yields "collects
  but never exports" if wrong.
