# world-gen · providers, weather & time

Parent: [00-overview.md](./00-overview.md). Covers the `WorldProvider` family's
environment hooks and the per-dimension weather + time-of-day system
(`world/weather/*`). This is the highest-risk cluster in the subsystem: it owns
persisted state, a `WorldInfo` wrapper installed by a Mixin, and client sync.

## Key types

| class | role |
|-------|------|
| `WorldProviderPlanet` | environment hooks: sky/fog/sun colour, gravity, atmosphere, celestial angle, respawn/sleep, and the custom weather cycle |
| `WorldProviderSpace` / `WorldProviderAsteroid` | subclasses; atmosphere 0, own sky renderers, fixed celestial angle |
| `PlanetWeatherManager` | static service: wrap policy, saved-data lookup, legacy migration, client sync, unwrap |
| `StellurgyDimensionWorldInfo` | `WorldInfo` wrapper: weather + time-of-day served per-dim, everything else delegated |
| `PlanetWeatherState` | per-dim mutable weather + clock; NBT round-trip |
| `PlanetWeatherSavedData` | one `WorldSavedData` on overworld storage, map dim→state |
| `PlanetWeatherEventHandler` | `WorldEvent.Load` wrap-fallback, `/weather` redirect, player-sync events |

## Mechanics

### MECH-WGEN-16 — planet environment hooks
`WorldProviderPlanet` overrides read `DimensionProperties` for every environmental
quantity: gravity (`getGravitationalMultiplier`, `:505-507`), atmosphere density
(`getAtmosphereDensity`, `:531-534`), average temp, rotational period
(`calculateCelestialAngle` uses it as the day length, `:481-502`), sky/fog/sun
colours (`:436-465`), cloud height (128 only if density>0.75, else −200000 to hide
clouds, `:426-428`). `getSunBrightness` folds star luminosity, orbital distance and
eclipse geometry (`:362-422`). `canRespawnHere()==false`; respawn/sleep gated on
breathable atmosphere + config (`:286-316`).

### MECH-WGEN-17 — wrap policy (should this world get per-dim WorldInfo)
`PlanetWeatherManager.shouldWrap` is the **single source of truth**: requires
`cfg.perDimWorldInfo` master on, server side, dim≠0, dim≠`spaceDimId`, not already
wrapped; then accepts if `forcePlanetWeatherWorldInfoWrapper`, or the provider is a
`WorldProviderPlanet`, or the Stellurgy `DimensionManager` says the dim is created
(`world/weather/PlanetWeatherManager.java:121-151`). `isWeatherManaged` is a
**separate** gate (`perDimWorldInfo && (enableCustomPlanetWeather || force)`,
`:160-164`) — time-of-day is always per-dim once wrapped, but weather is only
served from the per-dim state when weather is managed.

### MECH-WGEN-18 — installing the wrapper
`wrapWorldInfoIfNeeded` (idempotent) looks up the shared saved-data, runs legacy
migration, builds `StellurgyDimensionWorldInfo(current, state, dirtyMarker, weatherManaged)`
and assigns it to `world.worldInfo` — a field widened to public by Stellurgy's access
transformer (C6) (`PlanetWeatherManager.java:174-216`). It then re-seeds
`world.rainingStrength/thunderingStrength` from the *wrapped* state to kill the
"phantom rain fade" a planet born during overworld rain would otherwise stream
(`:191-209`, direct field writes because `World.setRainStrength` is client-only).
Called from two paths: the Mixin at `WorldServerMulti` ctor RETURN, and the
`WorldEvent.Load` fallback in `PlanetWeatherEventHandler.onWorldLoad:60-64` (after
the provider is installed). Both safe because it is idempotent.

### MECH-WGEN-19 — WorldInfo delegation split
`StellurgyDimensionWorldInfo` overrides the five weather getters/setters
(`clean/rain/thunder` time, `raining`, `thundering`) to route to
`PlanetWeatherState` when `weatherManaged`, else to the delegate
(`world/weather/StellurgyDimensionWorldInfo.java:94-167`). It **always** owns
`getWorldTime`/`getWorldTotalTime` (per-dim clock, `:175-195`) — vanilla
`DerivedWorldInfo` delegates those to the overworld and no-ops the setters, which
swallows the sleep skip. Everything else delegates; `cloneNBTCompound`
forwards so vanilla's hidden state is preserved (`:204-207`), and `getGeneratorOptions` forwards
too — un-overridden it would answer from this wrapper's own inert `super()` state and hand every
planet's chunk generator an empty settings string (MECH-WGEN-28). The wrapped delegate on aStellurgy
dimension is `StellurgyPlanetWorldInfo`, not vanilla's `DerivedWorldInfo`. Mutations run
`dirtyMarker` to schedule a save.

### MECH-WGEN-20 — sleep wake-up rounding
`StellurgyDimensionWorldInfo.computeSleepWakeTime(current, rotationalPeriod)` returns the
smallest multiple of the planet's day length strictly after `current` (dawn),
falling back to 24000 for non-positive periods
(`StellurgyDimensionWorldInfo.java:79-85`). Used by `MixinWorldServer` at the sleep site
so beds bring the planet's morning, not vanilla's 24000-rounded night.

### MECH-WGEN-21 — the custom weather cycle
`WorldProviderPlanet.updateWeather` fully replaces vanilla when
`perDimWorldInfo && enableCustomPlanetWeather && props.usesCustomWorldInfo()`,
else defers to `super.updateWeather` (`:117-130`). Server-only. It reads the
planet's rain/thunder **markers** (−1 never / 0 cycle / 1 always) from
`DimensionProperties`, force-clears precipitation below
`minAtmosphereDensityForRain` (`:150-157`), and otherwise runs a vanilla-shaped
rain/thunder timer using the planet's prolongation/start lengths (clamped to
avoid `Random.nextInt(≤0)`, `:196-235`). Thunder is forced off whenever it isn't
raining (`:243-246`). Warns once per dim if it finds itself running against an
un-wrapped `WorldInfo` (`:140-143` → `warnUnwrappedOnce`).

### MECH-WGEN-22 — client sync
`PlanetWeatherManager.syncToPlayer` sends three vanilla `SPacketChangeGameState`
packets (begin/end-raining code, rain-strength code 7, thunder-strength code 8)
so a client entering/rejoining a dim sees the wrapped weather
(`PlanetWeatherManager.java:296-313`). **Contract:** state codes 1=begin,2=end are
per *actual client behaviour*, not the wiki names (`:42-54`) — an earlier swap
produced inverted weather. `PlanetWeatherEventHandler` re-broadcasts on login /
dim-change / respawn (`:73-92`); these are now belt-and-suspenders behind
`MixinPlayerList`'s fix.

### MECH-WGEN-23 — /weather redirect
`PlanetWeatherEventHandler.redirectWeatherCommand` cancels vanilla `/weather`
(which hard-codes `server.worlds[0]` and would mutate the overworld) and re-runs
it as `/stellurgy weather …` when the sender stands on aStellurgy planet
(`world/weather/PlanetWeatherEventHandler.java:41-57`).

### MECH-WGEN-24 — legacy weather migration
On first wrap per dim per run, `migrateLegacyIfNeeded` reads the deleted
`WorldInfoSavedData` NBT out of the secondary world's `perWorldStorage` via a
throwaway `MigrationProbe`, copying `clearWeatherTime`/`rainTime`/`thunderTime`/
`raining`/`thundering` into the new `PlanetWeatherState`
(`PlanetWeatherManager.java:235-263`). Wrapped in try/catch so migration never
crashes world load.

## State & persistence

Saved-data `stellurgy_planet_weather` (`PlanetWeatherSavedData.STORAGE_KEY`, `:23`) lives on the **overworld** MapStorage; its keys are
in `C1-nbt-persistence` (world-gen). Notes: `worldTime`/`worldTotalTime` are written only if `timeInitialized` and read guarded by
`hasKey("worldTime")`; the legacy key `clearWeatherTime` (read at `PlanetWeatherManager.java:251`) maps to `cleanWeatherTime` and the
new store never reads it again; the legacy source `WorldInfoSavedData` is read-only via `MigrationProbe` (`:245`). The gamerule
`doWeatherCycle` is read in the cycle (`WorldProviderPlanet.java:144`).

## Invariants

- **INV-WGEN-11 [T]** `computeSleepWakeTime` always lands on a dawn (multiple of
  `rotationalPeriod`), moves strictly forward, and skips <1 full extra day;
  rp=24000 equals vanilla rounding; non-positive rp falls back to 24000 —
  `unit/SleepWakeTimeTest.java:28,38,47,55`.
- **INV-WGEN-12 [T]** `PlanetWeatherState` NBT round-trips all five weather fields
  and preserves clean-weather time — `unit/PlanetWeatherStateTest.java:19,41`;
  the fresh-state-defaults-all-zero claim is `[A]` (no test pins it).
- **INV-WGEN-13 [V][T]** `STORAGE_KEY` is the stable string
  `stellurgy_planet_weather` — `[V]` `PlanetWeatherSavedData.java:25` (no test pins the string); `getOrCreate` is idempotent and isolates dimensions —
  `[T]` `unit/PlanetWeatherSavedDataTest.java:26,38`.
- **INV-WGEN-14 [T]** With `perDimWorldInfo` on, one dim's weather does not leak to
  another / to the overworld — `server/PerDimensionWeatherIsolationTest`,
  `server/PlanetDimensionLoadTest`; master-toggle off ⇒ fully vanilla —
  `server/PerDimWorldInfoMasterToggleTest`.
- **INV-WGEN-15 [T]** Custom weather disabled ⇒ cycle defers to vanilla even for
  planets carrying rain/thunder markers — `server/WeatherCycleDisableTest`,
  `server/PlanetWeatherGateTest`; persistence across restart —
  `server/WeatherPersistenceTest#planetRainSurvivesRestartOnSameWorkDir:84`.
- **INV-WGEN-16 [V]** `shouldWrap` never wraps dim 0 or the space dim, and
  `isWeatherManaged` is independent of `shouldWrap` so time-per-dim works with
  vanilla weather (`PlanetWeatherManager.java:130-164`).
- **INV-WGEN-17 [V]** Thunder can never be set without rain in the custom cycle
  (`WorldProviderPlanet.java:243-246`).

## Failure modes & edge cases

- **Once-per-dimension latches.** Legacy weather migration and the unwrapped-weather warning are
  once per dimension per server, never static sets (which would skip migration for a dim id already
  migrated in a previous save loaded in the same JVM): the two latches are transient (non-persisted) fields of `PlanetWeatherSavedData`
  (`legacyMigrationTried`, `unwrappedWarned`, `PlanetWeatherSavedData.java:34-35`), asked through
  `firstLegacyMigration` / `firstUnwrappedWarning` (`:65,70`; callers
  `PlanetWeatherManager.java:231,330`). That object is loaded once per server from that server's
  save, so each latch is "once per dimension per server" and the next save starts with empty ones.
- If the overworld MapStorage isn't ready, `wrapWorldInfoIfNeeded` silently
  returns and relies on the `WorldEvent.Load` retry (`:179-182`) — a dim whose
  provider never becomes `WorldProviderPlanet` and isn't in the Stellurgy registry stays
  unwrapped (falls back to shared weather, warned once).
- `WorldProviderPlanet.updateWeather` mutates `world.getWorldInfo()`; if the
  wrapper failed to install, it warns but still writes — on an un-wrapped world
  those writes hit the shared overworld `WorldInfo` (the exact bug the wrap
  prevents). Guarded by the `usesCustomWorldInfo()` precondition (`:127`).

## Config surface

Config: see `C4-config-surface`. `perDimWorldInfo` is the master switch: off ⇒ no wrapper, the mixins are not woven, and weather is the vanilla shared `WorldInfo`.

## Test coverage

- Sleep → `unit/SleepWakeTimeTest`, `client/PlanetBedSleepClientGroupTest`,
  `server/PlanetDimensionLoadTest`.
- Weather isolation/gate/persistence → the six `server/…Weather*`/`…Isolation*`/
  `…MasterToggle*` tests above.
- Wrapper delegation → `integration/StellurgyDimensionWorldInfoTest`.
- Event wiring → `server/EventHandlerWiringTest`,
  `server/PlayerEventHandlerWiringTest`.
- Command redirect → `client/PlanetWorldOnTheClientGroupTest`,
  `unit/WeatherCommandRefusalTest`.
- Client sync → `client/PlanetWorldOnTheClientGroupTest`.

## Open questions

- `WorldProviderSpace.getSunBrightness` NPEs on `Minecraft.getMinecraft()` if ever
  reached server-side; it is `@SideOnly`-adjacent but not annotated — assumed
  client-only by call context (unconfirmed).
