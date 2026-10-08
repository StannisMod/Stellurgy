---
id: mixins-asm-coremod
owns: [mixin/, asm/]
entrypoints: [StellurgyPlugin#getMixinConfigs, StellurgyMixinPlugin#shouldApplyMixin]
depends-on: [dimension-planets, atmosphere-oxygen, world-gen, api-public, client-render, util-core]
depended-by: [rocket-entity, atmosphere-oxygen, world-gen, space-stations]
contracts: [C4, C6]
confidence: high
---

## Purpose

The whole-mod bytecode-weaving layer. A single Forge coremod (`asm/`) registers
one Mixin config (`mixins.stellurgy.json`); nine `remap=true` mixins graft
Stellurgy behaviour onto vanilla `Entity`, `World`, `WorldServer(Multi)`, `PlayerList`,
`EntityPlayer(MP)`, `NetHandlerPlayClient` and `EntityRenderer`. One access
transformer widens `World.worldInfo`. Every mixin body is a thin delegation to a
handler that lives in another subsystem — this layer owns the *seam*, not the logic.

## Responsibility boundary

Owns: the coremod plugin, the Mixin config plugin (config-gated weaving), the nine
mixin classes, and `stellurgy_at.cfg`. **Does NOT own** the behaviour behind
each hook: gravity math (`util/GravityHandler`), atmosphere recompute
(`atmosphere/AtmosphereHandler`), GUI-bypass rules (`util/RocketInventoryHelper`),
per-dim weather/time (`world/weather/*`, `dimension/`), FF camera
(`client/KeyBindings`). The `asm/` package contains no `IClassTransformer`
— every hook is a mixin (see class javadocs).

## Key types

| class | role |
|-------|------|
| `asm/StellurgyPlugin` | `IFMLLoadingPlugin` + MixinBooter `IEarlyMixinLoader`; returns the one config name, no AT/transformer/ModContainer |
| `mixin/StellurgyMixinPlugin` | `IMixinConfigPlugin`; reads `.cfg` at `onLoad`, gates 3 WorldInfo mixins on `perDimWorldInfo` (`MixinWorldProvider` is deliberately not among them — terrain identity is not weather) |

> **The two mixins over code this mod COMPILES.** `MixinWorldServerShipManager` (over the vendored
> `valkyrienskies/…/WorldServerShipManager`) and `MixinTileAdvancedFlightComputer` (over Stellurgy's OWN tile) both exist in
> `src/main/java/dev/stannismod/stellurgy/mixin/` and both are listed in `mixins.stellurgy.json` (`:22,26`).
> `TileAdvancedFlightComputer` has no `onPhysicsTick`: the force controller is `MixinTileAdvancedFlightComputer`, which
> `implements IPhysicsBlockController`, and the double-load guard is `MixinWorldServerShipManager`'s `loadAndUnloadShips` HEAD inject.
>
> **The flight recorder and the spawn diagnostics are test-side.** Production carries the control law and nothing that records; the
> per-physics-step sample is taken by the TEST mixin `MixinPhysicsCalculationsMotionSample` around the physics loop's own call into
> each controller (`src/test/.../test/mixin/`). The spawn diagnostics are `test/trace/SpawnMemory` (an instance of the server's
> `SideTrace`), fed by the test mixin `MixinWorldServerShipManagerDiag`, which reads the private `ShipSpawnDetector.blacklist` size
> reflectively (`MixinWorldServerShipManagerDiag.java:144-157`; there is no `blacklistSize()` accessor).
>
> **Valkyrien Skies statics (vendored tree, `valkyrienskies/`).** `ShipSpawnDetector.blacklist` and `BlockPhysicsDetails.blockToMass`
> are `volatile` IMMUTABLE collections, built whole by `syncWithConfig()` and swapped in — at the mod's `init` once every block is
> registered (`ValkyrienSkiesMod.java:197` → `VSConfig.rederive()`), and again on a config reload (`VSConfig.java:212-226`) — never
> edited in place, so an assembly or a physics step reads either the old table or the new, never a half-built one; a read before init
> throws (`ShipSpawnDetector.java:54-57`). `ValkyrienSkiesMod`'s pre-init and init throw on a SECOND run
> (`ValkyrienSkiesMod.java:140,192-194`) and `isSpongePresent()` throws before init rather than answering a default (`:278-285`).
>
> **This inventory never listed them.** It tables 9 mixins against the 19 + 5 client entries now in
> `mixins.stellurgy.json`, so the physics-line ones — and the new `MixinEntityShipFrameCapture`
> (below) — are undocumented here. The gap predates this change and is recorded rather than quietly
> closed: filling it is a re-audit.

| `MixinEntityGravity` | `@Inject(HEAD)` `onUpdate` on `Entity`+3 non-super-calling subclasses → `GravityHandler.applyGravity` |
| `MixinEntityPlayerInventoryAccess` | `@Redirect` `canInteractWith` in `EntityPlayer.onUpdate` → keep rocket GUI open |
| `MixinEntityPlayerMPInventoryAccess` | server twin of the above (`EntityPlayerMP.onUpdate` re-checks independently) |
| `MixinWorldSetBlockState` | `@Inject(RETURN)` `setBlockState(BlockPos,IBlockState,int)Z` → `AtmosphereHandler.onBlockChange` |
| `MixinWorldProvider` | `@Inject(HEAD)` `setWorld` → install `StellurgyPlanetWorldInfo` before the provider caches world type / generator options. NOT gated by `perDimWorldInfo` |
| `MixinWorldServerMulti` | `@Inject(RETURN)` `<init>` → swap `DerivedWorldInfo` for `StellurgyDimensionWorldInfo` |
| `MixinWorldServer` | `@Redirect` `setWorldTime` ordinal 0 in `tick()` → round sleep-wake to `rotationalPeriod` |
| `MixinPlayerList` | `@Redirect` the rain gate (`WorldServer.isRaining()`) + `@Inject(TAIL)` the stale-weather clear, in `updateTimeAndWeatherForPlayer`; both `require = 1`. **Never HEAD+cancel**: that form silently dropped vanilla's `SPacketSpawnPosition` and every client kept the placeholder spawn `(8,64,8)` after login and every dim transfer |
| `MixinAcidRainRender` (client) | `@Redirect` `GlStateManager.color` in `renderRainSnow`, `require=0` → green acid tint |
| `MixinNetHandlerFFCameraRepin` (client) | `@Inject(HEAD+RETURN)` `handlePlayerPosLook` → re-pin FF camera around the riding echo |
| `MixinEntityShipFrameCapture` | adds, to `Entity`, two `@Unique` fields (the body's ship-frame capture and its capture epoch) behind the duck interface `ShipFrameBody` — no injector, so no `require` (`MixinEntityShipFrameCapture.java:14-41`, listed `mixins.stellurgy.json:15`) |

## Mechanics

- **MECH-MIX-01 — coremod registration.** `getMixinConfigs()` returns the single
  `mixins.stellurgy.json`; MixinBooter (present in dev AND packaged) queues it
  on the `LaunchClassLoader`. The plugin deliberately references no `spongepowered.asm`
  class and returns null AT/ModContainer/setup. `asm/StellurgyPlugin.java:32-46`
- **MECH-MIX-02 — config-gated weaving.** `StellurgyMixinPlugin.shouldApply(perDim, allowSkipOnPlanets,
  allowSkipOnOverworld, name)` is a pure function: `MixinWorldServerMulti` and `MixinPlayerList`
  weave iff `perDimWorldInfo`; `MixinWorldServer` weaves iff EITHER the per-dim clock or a
  non-default time-skip policy needs it (it carries both seams); the two VS-targeting mixins weave
  iff Valkyrien Skies resolves on the classpath; all others always weave. The flags are read once at
  `onLoad` straight from the `.cfg` file (not `StellurgyConfiguration`, which is populated after the
  coremod phase), fail-open to the shipped defaults. `mixin/StellurgyMixinPlugin.java:57-180`
- **MECH-MIX-03 — per-dim gravity.** `onUpdate` HEAD-inject on `Entity` plus
  `EntityFallingBlock`/`EntityMinecart`/`EntityTNTPrimed` (they override `onUpdate`
  without `super`), delegating to `GravityHandler.applyGravity`. `mixin/MixinEntityGravity.java:26-32`
- **MECH-MIX-04 — rocket GUI bypass.** Redirect of `Container.canInteractWith` inside both
  `EntityPlayer.onUpdate` and `EntityPlayerMP.onUpdate` to
  `RocketInventoryHelper.shouldAllowContainerInteract`, so a moving rocket's GUI is not
  auto-closed at the 64-block range. `mixin/MixinEntityPlayerInventoryAccess.java:36-42`,
  `mixin/MixinEntityPlayerMPInventoryAccess.java:23-29`
- **MECH-MIX-05 — atmosphere recompute.** RETURN-inject on `World.setBlockState(...I)Z`
  → `AtmosphereHandler.onBlockChange`; the handler self-guards on `!world.isRemote`,
  `enableOxygen`, and chunk-loaded. `mixin/MixinWorldSetBlockState.java:27-33`,
  `atmosphere/AtmosphereHandler.java:124`
- **MECH-MIX-06 — WorldInfo wrapper install.** After each `WorldServerMulti` ctor,
  `PlanetWeatherManager.wrapWorldInfoIfNeeded` may replace `DerivedWorldInfo` with
  `StellurgyDimensionWorldInfo`; provider-not-ready is tolerated (WorldEvent.Load fallback in
  event-handlers). `mixin/MixinWorldServerMulti.java:29-41`
- **MECH-MIX-07 — sleep-wake rounding.** Redirect of the ordinal-0 `setWorldTime` in
  `WorldServer.tick()` (the sleep-skip, not the +1 increment); for `IPlanetaryProvider`
  dims rounds to `rotationalPeriod` via `StellurgyDimensionWorldInfo.computeSleepWakeTime`.
  Runtime re-checks `cfg.perDimWorldInfo` (belt-and-suspenders over MECH-MIX-02).
  `mixin/MixinWorldServer.java:32-49`
- **MECH-MIX-08 — weather-sync fix.** Redirects the single `WorldServer.isRaining()` call inside
  vanilla `updateTimeAndWeatherForPlayer` to the `WorldInfo` flag (not the lerped
  `getRainStrength`), so a joining / transitioning player sees rain immediately; a TAIL inject adds
  the "not raining" branch vanilla lacks, clearing a partial-rain state carried in from another dim.
  Rain codes: 1=start, 2=stop, 7=rainStrength, 8=thunderStrength.
  **It redirects rather than cancelling on purpose.** Cancelling at HEAD and
  re-issuing vanilla's packets from a copy silently drops
  `SPacketSpawnPosition(worldIn.getSpawnPoint())`, so no client learns the world spawn on login or
  on a cross-dim transfer. Redirecting the one call Stellurgy disagrees with leaves every other packet, present and
  future, owned by vanilla. Both injections carry `require = 1`: the config's `defaultRequire` is 0,
  so a selector that matched nothing would re-introduce the bug as a silent no-op.
  `mixin/MixinPlayerList.java` (the two injectors; line numbers deliberately dropped — this file has
  been rewritten twice by the same defect and a pinned range rots faster than the claim)
- **MECH-MIX-09 — acid-rain tint.** Client redirect of the single `GlStateManager.color`
  before `renderRainSnow`; multiplies by (0.45,0.95,0.30) when
  `DimensionProperties.isAcidicRain()`. `require=0` → silently skips if OptiFine rewrote
  the method. `mixin/MixinAcidRainRender.java:33-57`
- **MECH-MIX-10 — FF camera re-pin.** Client HEAD+RETURN inject on `handlePlayerPosLook`
  captures the pending mouse delta before, and re-pins the Free-Flight camera after, the
  server's riding-echo; all gating lives in `KeyBindings`. `mixin/MixinNetHandlerFFCameraRepin.java:26-36`

## State & persistence

No NBT owned. Reads config flag `Planet.perDimWorldInfo` (default true) directly from
`advRocketry/stellurgy.cfg` at coremod load; also reads the vanilla
`doDaylightCycle` game-rule when re-issuing the time packet (seam-nbt row, read-only).
`mixin/StellurgyMixinPlugin.java:55-61`, `mixin/MixinPlayerList.java` (the `@Redirect`)

## Invariants

- **INV-MIX-01 [A][BEH]** The 3 WorldInfo mixins weave iff `perDimWorldInfo` is on; all other
  mixins always weave. `StellurgyMixinPluginTest.java:46-69` Pinned by `StellurgyMixinPluginTest#worldInfoMixinsApplyWhenPerDimWorldInfoEnabled`, `StellurgyMixinPluginTest#worldInfoMixinsSkippedWhenPerDimWorldInfoDisabled`, `StellurgyMixinPluginTest#nonWorldInfoMixinsAlwaysApplyRegardlessOfFlag`.
- **INV-MIX-02 [V]** Coremod never touches `spongepowered.asm.*` (avoids the AppClassLoader
  LinkageError / "no mixin host service" crash). `asm/StellurgyPlugin.java:14-34`
- **INV-MIX-03 [V]** Config is `"required": true` with a `plugin` — a single failed mixin
  aborts the whole config; this makes the silent-no-op mode structurally
  impossible in reobf. `mixins.stellurgy.json`
- **INV-MIX-04 [V][BEH]** Gravity hook is woven on `Entity` and all three non-super-calling
  subclasses (`@Mixin({Entity, EntityFallingBlock, EntityMinecart, EntityTNTPrimed})`, `@Inject onUpdate`
  HEAD). `mixin/MixinEntityGravity.java:26-29`. The per-dimension behaviour (Stellurgy and vanilla dims) is unpinned.
- **INV-MIX-05 [V]** Atmosphere hook is common-side safe: side/chunk/oxygen guards live in
  `AtmosphereHandler.onBlockChange`, not the mixin. `atmosphere/AtmosphereHandler.java:124`
- **INV-MIX-06 [V]** `MixinAcidRainRender` and `MixinNetHandlerFFCameraRepin` are in the
  config's `client` array only — never woven server-side. `mixins.stellurgy.json`
- **INV-MIX-07 [V][BEH]** Sleep-wake redirect fires only on `IPlanetaryProvider` dims with
  `perDimWorldInfo`; non-Stellurgy worlds keep vanilla rounding. `mixin/MixinWorldServer.java:43-47`
- **INV-MIX-08 [A][BEH]** `MixinPlayerList` initialises the world border from dim 0's border,
  matching vanilla's overworld-border semantics: the mixin does not issue
  the border packet at all — vanilla's own body does, which is the point of redirecting one call
  instead of replacing the method. `mixin/MixinPlayerList.java`

## Failure modes & edge cases

- Fail-open config read: any error → `perDimWorldInfo=true`, i.e. WorldInfo mixins weave
  (pre-plugin behaviour). `mixin/StellurgyMixinPlugin.java:63-66`
- OptiFine/render mods rewrite `renderRainSnow` → acid tint silently absent (`require=0`),
  config still applies. `mixin/MixinAcidRainRender.java:38-40`
- `WorldServerMulti` ctor may return before its provider is ready → wrapper skipped,
  caught by the `WorldEvent.Load` fallback in event-handlers. `mixin/MixinWorldServerMulti.java:22-24`
- Dev vs prod: `@Inject`/`@Redirect` silently no-op under a wrong dev refmap; run dev with
  `-Dmixin.env.disableRefMap=true`. 

## Integration seams

- **C6 mixin/AT.** 9 mixins (`seam-mixin.tsv`), all `remap=true`; AT
  `stellurgy_at.cfg` = `public net.minecraft.world.World field_72986_A` (widens
  `worldInfo` for the async-weather wrapper swap). Registered via `FMLAT` +
  `FMLCorePlugin` manifest attrs (`build.gradle:219-229`, `gradle.properties:98,126`).
- Hooked vanilla targets: `Entity(+3)`, `World`, `WorldServer`, `WorldServerMulti`,
  `PlayerList`, `EntityPlayer`, `EntityPlayerMP`, `NetHandlerPlayClient`, `EntityRenderer`.
- Downstream handlers: `GravityHandler`, `AtmosphereHandler`, `RocketInventoryHelper`,
  `PlanetWeatherManager`, `StellurgyDimensionWorldInfo`, `KeyBindings`, `DimensionProperties`.

## Config surface

`Planet.perDimWorldInfo` (C4, default true) — master switch. Off → MECH-MIX-06/07/08
mixins are **not woven** (weave-time via MECH-MIX-02) and MECH-MIX-07 also re-checks at
runtime. Full-disable path verified by `PerDimWorldInfoMasterToggleTest`. No other config
gates this subsystem; gravity/atmosphere/GUI-bypass hooks are always woven.

## Test coverage

| invariant | test |
|-----------|------|
| INV-MIX-01 | `test/unit/StellurgyMixinPluginTest.java:46-69` |
| INV-MIX-04 | none — `[V]` above |
| MECH-MIX-07 | `test/unit/SleepWakeTimeTest.java`, `test/client/PlanetBedSleepClientGroupTest.java` |
| MECH-MIX-08 | `test/client/PlanetWorldOnTheClientGroupTest.java`, `PlanetWorldOnTheClientGroupTest.java` |
| MECH-MIX-06 | `test/server/WeatherBaselineTest.java`, `WeatherCycleDisableTest.java` |

## Open questions

- `StellurgyMixinPlugin` hardcodes the relative path `config/advRocketry/stellurgy.cfg`
  and the section/key `Planet.perDimWorldInfo`; if FML's mod-config dir differs from
  `./config` or the key is renamed in `StellurgyConfiguration`, the read silently fails-open.
  Behaviour under a custom `--gameDir`/config dir not verified (unconfirmed).
