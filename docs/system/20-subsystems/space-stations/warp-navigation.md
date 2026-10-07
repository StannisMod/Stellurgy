# space-stations · warp & navigation

`TileWarpController` (the "warp monitor") computes travel cost, triggers a warp, and discovers new
planets from collected data; `TileHolographicPlanetSelector` is the holographic destination picker
that spawns UI entities. Both resolve their station via `getSpaceStationFromBlockCoords(pos)` —
the warp controller caches the result and clears it on unload/invalidate.

## Mechanics

### MECH-STN-12 — Travel-cost computation
`getTravelCost()` (and the mirror `getTravelCostToDimension`) compares the station's parent planet
`DimensionProperties` with the destination's (`TileWarpController.java:96-176`):
- default/undefined space props ⇒ `Integer.MAX_VALUE` (unreachable);
- different star ⇒ flat `1000` (`500` in the dimension variant) — decided by `getStar() !=`, an
  object IDENTITY test. Earth's `getStar()` must not stay the placeholder Sol `seedEarthDefaults` builds
  (`DimensionManager.java:160-189`), or a station
  about Earth would be quoted the cross-star rate for planets of its own system. The
  default-universe path BINDS Earth to the registered Sol (`DimensionManager#createAndLoadDimensions`,
  `overworldProperties.setStar(sol)`) `[T]` `WorldCommandStarMiscContractTest#earthBelongsToTheRegisteredSolNotToACopyOfIt`
  (probe `planet info` → `starIsRegistered`). A reloaded save is not measured;
- moon↔its parent planet ⇒ `1`;
- same star ⇒ `intraSystemCost` (`:140-148`): the separation of the two bodies' in-plane
  positions `(orbitalDist, orbitTheta)` **in AU**, times `WARP_FUEL_PER_AU = 100` (`:131`), floored
  at `1`. One fuel per hundredth of an AU is the price from when a distance unit WAS a hundredth
  of an AU; the unit became 100 km and the price is carried across in the constant, not re-tuned.
  [T] `test/server/WarpControllerDepthTest.java` (`aWarpBetweenTwoPlanetsIsPricedByTheirSeparationInAu`).
The GUI's distance gauge reads `50` per AU of the cached body's `orbitalDist`, and a MOON
`moonViewUnits / 2` of its distance from its planet — Luna 75
(`:137-143`, `:662-666`). [V] unpinned: the server's `dimCache` is set only by GUI construction.
Balance constants here are `tunable`. `warpCost` is cached and shown in the GUI.

### MECH-STN-13 — Warp execution — **UNREACHABLE since 0.1.0**
The warp button still sends `PacketMachine` id 2, and the server-side branch still requires that the
station is not anchored, **`canTravel()`**, the destination's artifact requirement, and
`useFuel(getTravelCost()) != 0` before calling `moveStationToBody` and firing `ALL_SHE_GOT` /
`FLIGHT_OF_PHOENIX`. `canTravel()` is a flat false (object-model MECH-STN-05), so **no station
departs**: FTL is the craft-borne hyperdrive now, and stations regain travel when they become craft.
The code path is kept rather than deleted because everything except the gate — cost, artifacts, the
timed move, the advancements — is what a station-as-craft will re-use. [T]
`test/server/WarpControllerDepthTest.java` (`aFullyFuelledStationStillDoesNotDepart`, which satisfies
every OTHER gate first so it cannot pass for the wrong reason). `meetsArtifactReq` checks that the
artifact slots (indices 4..8) satisfy the destination's `getRequiredArtifacts()` (`:877-893`).

### MECH-STN-14 — Data-search planet discovery
The "data" tab loads DISTANCE/MASS/COMPOSITION into a `MultiData` (cap 10000). The SEARCH button
(`:521-525`) starts a `progress` run only when all three data types ≥ 100; `update()` increments
`progress` server-side to `MAX_PROGRESS=1000`, then with probability `1/planetDiscoveryChance`
picks an as-yet-unknown planet (preferring planets that gate on artifacts the player already has)
and calls `station.discoverPlanet(id)`, stamping the id onto the planet chip and consuming 100 of
each data type (`:913-962`). `PROGRAMFROMCHIP` (id 6) directly discovers the dim id written on an
inserted `ItemPlanetIdentificationChip` (`:526-535`). `isPlanetKnown` delegates to the station,
which honours `planetsMustBeDiscovered` (`SpaceStationObject.java:874-877`).

### MECH-STN-15 — Holographic system render & selection
`TileHolographicPlanetSelector` spawns `EntityUIStar`/`EntityUIPlanet`/`EntityUIButton` and, each
enabled tick, repositions them polar-around the block (planets at `0.1 + orbitAU` hologram
radii along `orbitTheta`, `:115`; companion stars at `5` per AU of separation, `:357-359`), scaled
by an eased `getInterpHologramSize()` (`onTime` ramp × slider `size`)
(`TileHolographicPlanetSelector.java:104-163`). A centred planet's MOONS stand
`0.1 + moonViewUnits/100` out — Luna 1.6; through the planet
law she would stand at her parent's edge (`projectionRadius`, `:361-381`). Centring needs a station at the
projector's coordinates and two right-clicks of the projected planet. [T]
`test/server/SelectorServerSmokeTest.java` (`theHologramProjectsAPlanetOneHologramBlockPerAu`,
`theHologramCentredOnAPlanetProjectsItsMoonAsItProjectedLunaAt150`); companions unpinned (no arrangement gives Sol
a companion). `rebuildSystem` populates the current star's
planets, a centred planet's children, or the full star list in `stellarMode` (`:243-328`).
`selectSystem(id)` sets the station's destination via `station.setDestOrbitingBody(id)` and toggles
selection/centering; a second click on a star drills into it; the back button pops back to stellar
mode (`:165-205,440-452`). Redstone gating: `isEnabled()` respects the `RedstoneState`
(`:98-101`).

## State & persistence (NBT / wire — C1/C2)

- `TileWarpController`: `progress` (int) plus the 9-slot `inv` and `MultiData` (both written through
  libVulpes helpers into the same compound); `getUpdateTag` ships the full write to clients
  (`:538-559`). It pushes `PacketSpaceStationInfo` to the opening player each `getModules`
  (`:267-269`). Network ids: 0 open selector, 1/3 focus/confirm system (int dim id), 2 warp,
  `TAB_SWITCH=4`, `SEARCH=5`, `PROGRAMFROMCHIP=6`, `10..19` store-data, `20..29` load-data.
- `TileHolographicPlanetSelector`: persists only the `RedstoneState` (via `state.writeToNBT` /
  `RedstoneState.createFromNBT`, keys owned by libVulpes). Network: `SCALEPACKET=0` (float
  `scale`), `STATEUPDATE=1` (byte `state`). Its `size`/selection are **not persisted** across
  reload (see open questions).
- Planet discovery mutates `SpaceStationObject.knownPlanets` (persisted, object-model MECH-STN-07)
  and broadcasts `PacketSpaceStationInfo` from `discoverPlanet` (`SpaceStationObject.java:113-116`).

## Config surface

Config: see `C4-config-surface`.

## Invariants

- **INV-STN-16 [V]** A warp is refused unless station is unanchored **and** has a usable warp core
  **and** artifacts are met **and** fuel ≥ travel cost; the fuel is spent atomically before the
  move (`TileWarpController.java:496-497`).
- **INV-STN-17 [T]** Same-star travel cost is the in-plane separation in AU × 100 (floored at 1),
  independent of the unit orbits are stored in; a cross-star hop is a flat constant, and an
  unreachable/default parent is `Integer.MAX_VALUE` (`:96-176`).
  `test/server/WarpControllerDepthTest.java` (`aWarpBetweenTwoPlanetsIsPricedByTheirSeparationInAu`).
- **INV-STN-18 [V]** A data-search discovery run cannot start unless all three data types are
  ≥ 100, and completing one consumes exactly 100 of each (`:521-525,954-956`).
- **INV-STN-19 [V]** Destination selection routes through `ISpaceObject.setDestOrbitingBody`,
  which broadcasts `DEST_ORBIT_UPDATE` server-side, keeping clients' warp preview consistent
  (`TileHolographicPlanetSelector.java:189`; `SpaceStationObject.java:613-619`).
- **INV-STN-20 [V]** The warp controller drops its cached station on chunk unload / invalidate,
  so a moved or reloaded station is not served stale (`:992-1003`).
- **INV-STN-21 [A]** `getData(id)` returns the destination body for `id==0` and the current orbit
  for `id==1`; the naming of the two `setData` slots (`srcPlanet`/`dstPlanet`) is confusing but the
  observed GUI values are correct (`:670-701`) — unverified against a test.

## Failure modes & edge cases

- `TileHolographicPlanetSelector.getCurrentPlanetID()` dereferences `selectedPlanet.dimension`
  with no null guard (`:400-402`); only safe because callers gate on a prior selection.
- Every enabled tick that finds the hologram empty calls `rebuildSystem`, which spawns entities;
  combined with the `onTime`/`allowUpdate` one-tick delay this is a per-tick respawn guard, not a
  leak, but worth noting for client-render coupling.

## Open questions

- `TileHolographicPlanetSelector` does not persist `size` or the current star/selection; after a
  reload the holo resets to defaults. Is that intended (transient UI) or a missing save? (behaviourally cosmetic).
