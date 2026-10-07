# F3 — Dimension / planet lifecycle

Traces one celestial body from `DimensionProperties` creation through Forge registration,
`temp.dat` / `planetDefs.xml` save, reload, and finally `WorldProviderPlanet` + `ChunkManagerPlanet`
terrain generation. The value of this flow is the **seams**: which store is authoritative for the *dim
set* vs the *per-dim state*, and where biome identity crosses the id↔name boundary. Mechanics live in
the owning subsystem docs and are cited by anchor.

Owning subsystems: **dimension-planets** (MECH-DIM-*), **world-gen** (MECH-WGEN-*), **util-core**
(`XMLPlanetLoader`), **network-wire** (`PacketDimInfo`).

## Steps

1. **Create props.** A body begins as a `DimensionProperties` — either the default galaxy (Sol + Luna +
   named stars) built when no `temp.dat` exists, or a procedural body realized by a descent
   (universe-model §4; MECH-DIM-05). Creation seeds atmosphere, distance and gravity, derives
   temperature via `AstronomicalBodyHelper`, adds viable biomes and `initDefaultAttributes`. *Producer*
   of the in-memory `allowedBiomes` (`List<BiomeEntry>`) consumed at step 9.

2. **Register.** `registerDim` → `registerDimNoUpdate` inserts into `dimensionList` and, **iff
   `hasSurface()`** and not already registered, forwards to Forge's `DimensionManager.registerDimension`
   with `AsteroidDimensionType` or `PlanetDimensionType` (MECH-DIM-06 / INV-DIM-09). Gas giants and
   stars get a Stellurgy record but **no Forge dim**, so no `WorldProvider` spawns for them. `registerDim`
   also broadcasts `PacketDimInfo` to clients (network-wire owns the wire form; C2).

3. **Runtime mutation.** Setters that change persisted identity re-broadcast: `setAtmosphereDensity`
   re-runs `load_terraforming_helper` and sends `PacketDimInfo` (MECH-DIM-10); beacon add / remove
   toggles `knownPlanets` and broadcasts (MECH-DIM-17). `tickDimensions` advances orbit θ and pumps
   satellites / terraforming each server tick (MECH-DIM-11); θ is read off the space clock (C16).

4. **Save — two files, one call.** `saveDimensions` iterates **`dimensionList`** (every registered dim)
   and writes each via `writeToNBT` into gzipped `temp.dat`, alongside `starSystems`, `nextSatelliteId`,
   `stat`, `spaceObjects` (MECH-DIM-02). It then serialises **the same galaxy** to the human-editable
   `planetDefs.xml` via `XMLPlanetLoader.writeXML`, which walks `star.getPlanets()`. Both writes are
   crash-atomic (fsync + `ATOMIC_MOVE`, INV-DIM-14). It refuses to write with no stars or no dims
   (INV-DIM-15), guarding against clobbering with an empty galaxy.

5. **Biome persistence — the id↔name split (seam).** `writeToNBT` emits the surface biome list as
   **registry names** (`biomeNames` + `weights`, only for `isNativeDimension && hasSurface()` bodies) and
   the crater biomes as `craterBiomeNames`; each reader treats the name list as authoritative and falls
   back to the legacy integer-id list only when the names are absent, skipping an unresolvable id
   rather than carrying a null biome (INV-DIM-12).

6. **Load — state.** `loadDimensions` reads `temp.dat`: stars, `nextSatelliteId`, `stat`, then each dim
   via `createFromNBT` into a **local map** it *returns* (MECH-DIM-03). It swallows `EOFException` into
   an empty map (a truncated file looks like "no planets"). Its own Forge registration is commented out:
   the returned map is *not* registered here.

7. **Load — reconcile & register (seam).** `createAndLoadDimensions` parses `planetDefs.xml` into
   `dimCouplingList` (MECH-DIM-04), calls `loadDimensions`, then: if no dims were loaded it builds the
   default galaxy / applies XML dims; otherwise (a real reload) the **XML-driven** merge loop iterates
   `dimCouplingList.dims`, `copyData`s the matching `temp.dat` state onto each and `registerDim`s the
   native dims. **Registration, the dim *set* and almost all per-dim state come from `planetDefs.xml`**
   (the world's own copy, rewritten by `writeXML` at save); `copyData` carries only the satellites and
   the AIR from `temp.dat`. A field the writer does not emit and `copyData` does not carry is lost on
   every reload. Random planets are not regenerated on a reload: each XML star's body count is carried
   into the universe layer as its retinue bound (universe-model §4).

8. **Provider bootstrap.** When Forge loads a registered Stellurgy dim it instantiates the
   `WorldProvider` from the `DimensionType` of step 2. `WorldProviderPlanet` reads
   `DimensionManager.getDimensionProperties(dim)` for gravity, atmosphere, celestial angle, sky / fog
   colour (MECH-WGEN-16) and installs the per-dim weather / time `WorldInfo` wrapper (MECH-WGEN-17/18;
   C6 `MixinWorldProvider`). `getDimensionProperties` never returns null — unknown ids fall to the
   overworld's properties, space ids to the default space properties (INV-DIM-10).

9. **Generator bootstrap — biome consumer.** `WorldProviderPlanet#createChunkGenerator` picks the
   generator by `genType` (MECH-WGEN-01); the biome provider is `ChunkManagerPlanet`, built from the
   world seed + the *same* `DimensionProperties`. It builds the GenLayer stack, branching river layers
   on `hasRivers()` (MECH-WGEN-08), and `GenLayerBiomePlanet` does a weighted pick over `allowedBiomes`;
   an **empty list → 100 % OCEAN**, never a crash (INV-WGEN-04). The per-dim seed offset
   `super.getSeed() + getDimension()` is contractual — changing it re-rolls terrain. Terrain height /
   ocean / stone come from `DimensionProperties` block + sea-level fields (MECH-WGEN-02).

## Gaps & mismatches

- **G1 — split-brain SSOT between `temp.dat` and `planetDefs.xml`.** `saveDimensions` writes the full
  galaxy to *both* files (step 4), but reload registration is driven **only** by `planetDefs.xml`
  (step 7); `loadDimensions`' own Forge registration is commented out. A body present in `temp.dat` but
  absent from `planetDefs.xml` (hand-edited XML, or XML restored from an older backup) is never
  registered — its persisted state becomes unreachable while its `DIM<n>` region folder still exists on
  disk.
- **G2 — gas giants / stars have no Forge dim.** By INV-DIM-09 they never reach steps 8–9; any consumer
  that assumes every `DimensionProperties` id maps to a loadable world will get props with no world
  behind them. Documented, not a defect here.
