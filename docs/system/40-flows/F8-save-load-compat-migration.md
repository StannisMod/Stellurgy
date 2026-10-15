# F8 — World save/load & the per-body upgrade guards

End-to-end trace of what Stellurgy runs when a world is loaded and saved, the tolerant reads that
survive a missing or older key, and the lifetime of the state involved (every manager is built per
server lifetime). Mechanics live in the owning subsystem docs and are cited by anchor; the value here is
the seams — where one subsystem produces persisted state another later consumes. There is no
pre-0.1.0 migration: saves from before 0.1.0 are not loaded (the 0.1.0 clean break), so "upgrade"
below means a tolerant read of a key an older build of THIS version may not have written.

Naming: "the galaxy file" = the gzipped `temp.dat` under the save dir; the human-editable overrides live
in `planetDefs.xml`.

## Load sequence (server start / world switch)

1. **`FMLServerAboutToStart` → galaxy bootstrap.** `Stellurgy.serverAboutToStart` calls
   `DimensionManager.createAndLoadDimensions(resetFromXml)` **before any world loads** (MECH-DIM-04). It
   resolves `planetDefs.xml` (world dir first, else the `config/` copy), parses it via `XMLPlanetLoader`
   (util-core), then either loads the galaxy file or generates the default galaxy (Sol + Luna).
2. **Galaxy load.** `loadDimensions` (MECH-DIM-03) reads the galaxy file into the manager this server
   lifetime built (MECH-DIM-23), so nothing of a prior session is there to drop. It reads
   `stat` {`hasReachedMoon`, `hasReachedWarp`}, `starSystems` (`StellarBody.readFromNBT`, api-public),
   `nextSatelliteId`, each `dimList` entry via `createFromNBT`, then `spaceObjects`. A missing file
   returns early; `EOFException` and empty `starSystems` yield a silent empty galaxy.
3. **Per-body tolerant reads.** `DimensionProperties.readFromNBT` (MECH-DIM-01) applies:
   - **`isNative` absent ⇒ true**, so a body written without the key keeps loading (INV-DIM-11);
   - **biome lists:** `biomeNames` (registry names) and `craterBiomeNames` are authoritative when
     present; the integer-id lists are read only when the names are absent, skipping an unresolvable id
     (INV-DIM-12 / MECH-DIM-12);
   - **frequency clamps** re-applied on load: crater / geode / volcano ∈ [0.01, 10] (INV-DIM-13); sea
     level clamped 0–255.
   The `backwardCompat/` package is only the OBJ mesh loader (backward-compat.md).
4. **Space-object + satellite rehydrate.** `SpaceObjectManager.readFromNBT` consumes the `spaceObjects`
   compound (space-stations, object-model.md); each station's satellites and the per-dim `satallites`
   [sic] bag are rebuilt via `SatelliteRegistry` (satellite.md).
5. **`serverStarted` MoonId native fix-up.** After worlds load, `Stellurgy.serverStarted` forces
   `isNativeDimension = true` for the galaxy's `getMoonId()` when Galacticraft is absent — a
   registry-compat patch for a moon saved as non-native.
6. **Per-world `WorldInfo` wrap + weather state.** As each non-overworld world constructs,
   `MixinWorldServerMulti` (C6) at the constructor's RETURN (and the `WorldEvent.Load` fallback in
   `PlanetWeatherEventHandler`) calls `PlanetWeatherManager.wrapWorldInfoIfNeeded` (MECH-WGEN-18). On the
   first wrap per dim it runs `migrateLegacyIfNeeded` (MECH-WGEN-24), copying the older
   `WorldInfoSavedData` weather fields (`clearWeatherTime → cleanWeatherTime`, …) into the
   `PlanetWeatherState`. Weather state itself lives in a single `PlanetWeatherSavedData` on the
   **overworld** MapStorage (INV-WGEN-13).
7. **Chunk generation biome pool.** `ChunkManagerPlanet` hands that dim's biome set to a new
   `GenLayerBiomePlanet` as a constructor argument (world-gen, terrain.md) — a `final` instance field of
   that layer; there is no shared state between planets.

## Save sequence

8. **`WorldEvent.Save` (overworld only).** `PlanetEventHandler.worldSaveEvent` fires on every world save
   but gates on `dimension == 0`, then calls `DimensionManager.saveDimensions(workingPath)`.
9. **Galaxy write.** `saveDimensions` (MECH-DIM-02) serialises `starSystems`, `nextSatelliteId`, per-dim
   `writeToNBT`, `stat`, `spaceObjects` to `temp.dat` and rewrites `planetDefs.xml`, both via fsync'd
   tmp-file + `ATOMIC_MOVE` (INV-DIM-14). It **refuses to write** when there are zero stars or zero dims,
   guarding against clobbering a good file with an empty galaxy (INV-DIM-15). The per-body writer emits
   the name lists for native surface dims (INV-DIM-12).

## State lifetime across saves

On an integrated server, loading save A then save B **without quitting** carries over nothing the new
server did not build: the galaxy manager (`nextSatelliteId`, `hasReachedMoon`, `hasReachedWarp`), the
weather once-per-dimension latches (transient fields of `PlanetWeatherSavedData`, loaded from each save's
own storage) and `GenLayerBiomePlanet`'s biome entries are all instance state with a per-server or
per-planet lifetime; the client drops its `ServerView` (and its dimension registrations) when it leaves a
server (C5, `ClientProxy#releaseLeftServer`).

## Gaps & mismatches

- **`prevVersion` is a key nobody reads.** `loadDimensions` writes the current version onto the
  just-read NBT (`nbt.setString("prevVersion", …)`), which is then discarded; no code reads the key or
  persists it. A version-gated migration hook exists in name only: the producer writes, nothing
  consumes.
- **`status` is persisted by enum ordinal.** `TileRocketAssemblingMachine` saves `ErrorCodes.ordinal()`
  with no name mapping, so reordering the enum silently reassigns saved verdicts; a missing key reads the
  neutral `UNSCANNED` and an out-of-range ordinal does too (`errorCodeFromOrdinal`) (C1).
