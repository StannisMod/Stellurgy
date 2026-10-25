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

### MECH-WGEN-31 — a body world's time of day is its body's turn
For the overworld and every planet (`TimeSkipPolicy.isPlanet`: not a station, a slot world or an asteroid
field), the time of day is not a counter: `MixinWorldProvider` answers Forge's `WorldProvider#getWorldTime`
— through which `World#getWorldTime`, the celestial angle and the moon phase all read it — with
`TimeOfDay.of(world)`, the body's local solar time at meridian 0 at the space clock (`environment`
MECH-ENV-07, C16 CLOCK-6). Both sides: the server's clock on the server, the synced one on a client, so a
client's sky does not wait on vanilla's time packet. The counter MECH-WGEN-19 keeps is still written (by the
vanilla tick, the sleep rounding and `/time`) and is not read for a body; worlds that are not bodies keep it.
A body this side has no properties for keeps vanilla's counter. `world/TimeOfDay.java`,
`mixin/MixinWorldProvider.java`. `[V]`

- **MECH-WGEN-32 [T][SYS] A sleep's decision.** `TimeSkip.skipFor` decides what a sleep completing in one world comes to, over every occupied world's part
(all its non-spectator players asleep; its skip allowed by `TimeSkipPolicy`; its next dawn, if it has one):
nothing unless the completing world is all asleep and allowed; with `sleepSkipNeedsEveryPlayer` (the default)
nothing unless EVERY occupied world is, and then the universe moves to the earliest of their dawns; without
it, the completing world alone, to its dawn. A sleep that takes no world with a dawn moves the universe to the
overworld's next dawn. `TimeSkip.nightEnds` answers vanilla's per-world sleep check with it
(`MixinWorldServerTimeSkip`), moves the space clock (`TimeSkip.skip`, telling every client) and wakes the other
worlds it took; a bed in a Stellurgy world that is not a body may be slept in at any time
(`PlanetEventHandler#sleepingTimeCheck`, Forge's `SleepingTimeCheckEvent`). The operator's skip is
`TimeCommandGuard`: `/time add N` moves the universe N ticks, `/time set T` to the next moment the sender's
world (the overworld when it is no body) reads T, and a world that is no body gets vanilla's write (C16
CLOCK-7.2). Pinned by `SleepWakeTimeTest#aSleepEndsTheNightOnlyWhenItsModeIsMet`.
FOR: C16 CLOCK-7.

### MECH-WGEN-20 — sleep wake-up rounding
`StellurgyDimensionWorldInfo.computeSleepWakeTime(current, rotationalPeriod)` returns the
smallest multiple of the planet's day length strictly after `current` (dawn),
falling back to 24000 for non-positive periods
(`StellurgyDimensionWorldInfo.java:79-85`). Used by `MixinWorldServer` at the sleep site.
Since MECH-WGEN-31 the value it writes is the stored counter, which a body's time of day no longer reads:
on a body a sleep wakes the sleepers and brings no morning until the skip moves the space clock (C16
CLOCK-7, not written yet). `[V]`

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

- **INV-WGEN-11 [T][BEH]** `computeSleepWakeTime` always lands on a dawn (multiple of
  `rotationalPeriod`), moves strictly forward, and skips <1 full extra day;
  rp=24000 equals vanilla rounding; non-positive rp falls back to 24000 —
  `unit/SleepWakeTimeTest.java:28,38,47,55`. Pinned by `SleepWakeTimeTest#rp24000MatchesVanillaRounding`, `SleepWakeTimeTest#nonVanillaPeriodsLandOnDawn`, `SleepWakeTimeTest#alreadyAtDawnSkipsToNextDay`, `SleepWakeTimeTest#nonPositivePeriodFallsBackTo24000`.
- **INV-WGEN-12 [V][SYS]** `PlanetWeatherState` NBT writes and reads all five weather fields,
  clean-weather time among them, under one key each — `PlanetWeatherState.java:127-149`;
  the fresh-state-defaults-all-zero claim is `[A]` (no test pins it). FOR: save format: planet weather.
- **INV-WGEN-13 [V][SYS]** `STORAGE_KEY` is the stable string
  `stellurgy_planet_weather` — `[V]` `PlanetWeatherSavedData.java:25` (no test pins the string); `getOrCreate` is idempotent and isolates dimensions —
  `[T]` `unit/PlanetWeatherSavedDataTest.java:26,38`. Pinned by `PlanetWeatherSavedDataTest#getOrCreateInsertsFreshStateAndIsIdempotent`, `PlanetWeatherSavedDataTest#getOrCreateIsolatesDimensions`. FOR: save format: planet weather.
- **INV-WGEN-14 [T][BEH]** With `perDimWorldInfo` on, one dim's weather does not leak to
  another / to the overworld — `server/PerDimensionWeatherIsolationTest`,
  `server/PlanetDimensionLoadTest`; master-toggle off ⇒ fully vanilla —
  `server/PerDimWorldInfoMasterToggleTest`. Pinned by `PerDimensionWeatherIsolationTest#rainOnPlanetADoesNotLeakToBOrOverworld`, `PerDimensionWeatherIsolationTest#rainOnPlanetBDoesNotLeakToAOrOverworld`, `PerDimensionWeatherIsolationTest#clearOnPlanetADoesNotClearB`, `PerDimWorldInfoMasterToggleTest#masterOffLeavesPlanetOnVanillaWorldInfo`.
- **INV-WGEN-15 [T][BEH]** Custom weather disabled ⇒ cycle defers to vanilla even for
  planets carrying rain/thunder markers — `server/WeatherCycleDisableTest`,
  `server/PlanetWeatherGateTest`; persistence across restart — `[V]` the per-dim states are
  written to and read from the overworld saved data, `PlanetWeatherSavedData.java:75-98`. Pinned by `WeatherCycleDisableTest#customWeatherCycleRunsOnlyWhenConfigEnabled`.
- **INV-WGEN-16 [V][BEH]** `shouldWrap` never wraps dim 0 or the space dim, and
  `isWeatherManaged` is independent of `shouldWrap` so time-per-dim works with
  vanilla weather (`PlanetWeatherManager.java:130-164`).
- **INV-WGEN-17 [V][BEH]** Thunder can never be set without rain in the custom cycle
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
- Weather isolation/gate (planet-rain persistence is `[V]` only) → the six `server/…Weather*`/`…Isolation*`/
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
