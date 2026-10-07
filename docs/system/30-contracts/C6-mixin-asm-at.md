---
id: C6-mixin-asm-at
covers: [mixins.stellurgy.json, stellurgy_at.cfg, StellurgyMixinPlugin, StellurgyPlugin]
confidence: high
owner-subsystem: mixins-asm-coremod
---

# C6 — Mixin targets / access transformer

The bytecode-weaving contract: what Stellurgy injects into vanilla classes, how the config is
registered, and which fields it widens via AT. All behavioural mechanics are **owned by** the subsystem
doc `mixins-asm-coremod` (`MECH-MIX-*`, `INV-MIX-*`); this contract is the consolidated seam table +
the dev-vs-prod risk surface. Do not re-derive mechanics here — cite the anchors. The set of mixins is
the `mixins` / `client` arrays of `mixins.stellurgy.json`; a mixin absent from the table below is
unaudited, not non-existent.

## Registration path (single, no manifest fallback)

- Config `mixins.stellurgy.json` is registered **only** through
  `StellurgyPlugin implements IEarlyMixinLoader.getMixinConfigs()` (`asm/StellurgyPlugin.java:31-38`),
  which queues it together with `mixins.valkyrienskies.json` — the vendored physics engine's own config,
  queued at the early coremod point so its interface-injection mixins apply before any world class is
  loaded → **MECH-MIX-01**. The coremod deliberately holds no `org.spongepowered.asm.*` reference →
  **INV-MIX-02 [V]**: referencing it from the AppClassLoader re-initiates `GlobalProperties$Keys` on a
  second classloader and ends in a `LinkageError` / "No mixin host service is available".
- The jar manifest carries **`FMLCorePlugin`** (from `coremod_plugin_class_name`) and **`FMLAT`**
  (`build.gradle:234-247`) and **no `MixinConfigs` attribute**, so MixinBooter's `IEarlyMixinLoader`
  queue is the sole load path in dev and in packaged jars. No double-registration risk. `[V]`
- `refmap: mixins.stellurgy.refmap.json` (`mixins.stellurgy.json`, from `mixin_refmap` in
  `gradle.properties:115`); every target is SRG-remapped and depends on the refmap.
- `"required": true`, no `injectors` block (so Mixin's `defaultRequire` is 0), `plugin` =
  `StellurgyMixinPlugin`, `client` = the client-only mixins, `server: []`.

## Consolidated seam table

All targets are vanilla or Forge classes. Behaviour anchor = `MECH-MIX-*` in `mixins-asm-coremod`
(`—` = described by the mixin's own javadoc and the subsystem doc's key-types table). Guard column:
`req 1` = the injector carries `require = 1` and fails the load on a miss; `req 0` = `require = 0`
explicitly; `cfg` = no explicit `require`, so the config default 0 applies and a miss is a silent no-op.

> **The standing rule:** a mixin target must be a class this mod does NOT compile — vanilla Minecraft or
> Forge. A target inside `src/` or `valkyrienskies/` is an edit waiting to be made, not an injection
> point. The flight-computer tile and the vendored physics tree are therefore changed in place, not
> woven.

| Mixin (`mixin/`) | Target | Injector @ point | Side | Anchor | Guard |
|---|---|---|---|---|---|
| MixinEntityGravity | Entity + EntityFallingBlock + EntityMinecart + EntityTNTPrimed | `@Inject onUpdate` HEAD | common | MECH-MIX-03 | cfg |
| MixinEntityPlayerInventoryAccess | EntityPlayer | `@Redirect` `Container.canInteractWith` in `onUpdate` | common | MECH-MIX-04 | cfg |
| MixinEntityPlayerMPInventoryAccess | EntityPlayerMP | `@Redirect` `Container.canInteractWith` in `onUpdate` | common | MECH-MIX-04 | cfg |
| MixinWorldSetBlockState | World | `@Inject setBlockState(BlockPos,IBlockState,I)Z` RETURN | common | MECH-MIX-05 | req 1 |
| MixinWorldServerMulti | WorldServerMulti | `@Inject <init>` RETURN | common | MECH-MIX-06 | cfg |
| MixinWorldServer | WorldServer | `@Redirect setWorldTime(J)` ordinal 0 in `tick` | common | MECH-MIX-07 | cfg |
| MixinPlayerList | PlayerList | `@Redirect updateTimeAndWeatherForPlayer` → `WorldServer.isRaining()`; `@Inject` same method at TAIL | common | MECH-MIX-08 | req 1 (both) |
| MixinWorldProvider | WorldProvider | `@Inject setWorld` HEAD — installs a Stellurgy dimension's per-dimension `WorldInfo` before the chunk provider reads it; deliberately NOT gated by `perDimWorldInfo` | common | — | cfg |
| MixinEntityCellVoid | Entity | `@Redirect` `outOfWorld()` in `onEntityUpdate` — skipped in a cell slot world (`WorldProviderSpaceSlot` and NOT hyperspace), so the centred cell's negative half is not the void `[V]` | common | — | cfg |
| MixinEntityShipFrameCapture | Entity | none — `@Unique` fields `stellurgy$shipFrame` / `stellurgy$captureEpoch` + the `ShipFrameBody` duck; the capture lives on the entity object, not in a weak map | common | — | n/a |
| MixinEntityLivingShipTravel | EntityLivingBase | `@Inject travel` HEAD + `jump` HEAD, cancellable — resolves an aboard entity's movement in the ship's frame; declines every case `ShipFrameTravel#handles` does not model | common | — | cfg |
| MixinEntityPlayerShipFlight | EntityPlayer | `@Inject travel` HEAD, cancellable — the creative-flying aboard case, which `EntityPlayer.travel`'s own post-`super.travel` motionY overwrite would otherwise corrupt | common | — | cfg |
| MixinEntityShipEyes | Entity | `@Inject getPositionEyes` HEAD, cancellable — an ABOARD body's eye where its camera renders, so raytraces start where the player looks | common | — | cfg |
| MixinEntityShipLocalMove | Entity (`priority = 1500`) | `@Inject move` HEAD, cancellable — claims `Entity.move` before the physics mod so an aboard entity's collision resolves in the ship's frame; inert unless armed through `ShipLocalMoveControl` | common | — | cfg |
| MixinEntityPlayerJetpackKeys | EntityPlayer | none — `@Unique` per-player jetpack thrust-key state | common | — | n/a |
| MixinEntityTrackerRiderSeesVehicle | EntityTrackerEntry | `@Inject isVisibleTo` HEAD, cancellable — a player never loses sight of the vehicle he is riding | common | — | req 1 |
| MixinNetHandlerDeckMovementBound | NetHandlerPlayServer | `@Inject processPlayer` HEAD (remember the accepted position) + TAIL (refuse a position outside `DeckMovementBound`, only while a deck capture holds) `[V]` | common (server logic) | — | cfg |
| MixinAcidRainRender | EntityRenderer | `@Redirect` `GlStateManager.color(FFFF)` in `renderRainSnow` | **client** | MECH-MIX-09 | req 0 |
| MixinNetHandlerFFCameraRepin | NetHandlerPlayClient | `@Inject handlePlayerPosLook` HEAD + RETURN | **client** | MECH-MIX-10 | cfg |
| MixinEntityRendererShipEye | EntityRenderer | `@Redirect` the `GlStateManager.translate(0,-eyeHeight,0)` in `orientCamera` — offsets the camera along the SHIP's up | **client** | — | req 0 |
| MixinRenderLivingBaseShipRoll | RenderLivingBase | `@Inject applyRotations` HEAD + `@ModifyVariable` (yaw) — stands an aboard model on its deck | **client** | — | req 0 |
| MixinEntityDeckLookTurn | Entity | `@Inject turn` HEAD, cancellable — raw mouse-look in the DECK frame for the local player resolved aboard | **client** | — | cfg |

Client/server split (`mixins.stellurgy.json`): `client` = the client mixins above (never woven on a
dedicated server → **INV-MIX-06 [V]**); `server: []`; the rest are in the common `mixins` array.

**No `@Accessor` / `@Invoker` anywhere** in the mixin package. Field widening is done via AT instead —
deliberate: an AT applies at classload independent of refmap state, whereas an `@Accessor` depends on the
refmap and can silently no-op in dev.

## Config-plugin gate (StellurgyMixinPlugin)

- `StellurgyMixinPlugin implements IMixinConfigPlugin` gates exactly three mixins on the
  `Planet.perDimWorldInfo` master flag → **MECH-MIX-02 / INV-MIX-01 [T]**: `MixinWorldServerMulti` and
  `MixinPlayerList` apply iff the flag is on; `MixinWorldServer` applies iff the flag is on OR a
  time-skip policy flag (`allowTimeSkipOnPlanets`, `allowTimeSkipOnOverworld`) asks for a refusal, so a
  skip policy is never a flag that silently does nothing. Off ⇒ the gated mixin is **not woven**
  (weave-time), not merely a no-op. Every other mixin always applies.
- The plugin reads the `.cfg` file **directly** rather than via `StellurgyConfiguration` (which
  populates in mod pre-init, after the coremod phase): `onLoad` resolves
  `config/advRocketry/stellurgy.cfg` against `Launch.minecraftHome`, falling back to the process cwd
  (`mixin/StellurgyMixinPlugin.java`). Category/key `"Planet"`/`"perDimWorldInfo"` match
  `StellurgyConfiguration` (`PLANET` at `api/StellurgyConfiguration.java:47`). Fail-open default `true`
  on any read error, logged; a missing file on first launch logs the default taken.
- `MixinWorldServer` additionally re-checks `StellurgyConfiguration.getCurrentConfig().perDimWorldInfo`
  at runtime — belt-and-suspenders over the weave-time gate (MECH-MIX-07).

## Access transformer

`src/main/resources/stellurgy_at.cfg`:

- **Stellurgy's own entry:** `public net.minecraft.world.World field_72986_A` (SRG for
  `World.worldInfo`), "For async weather". **Consumer:** `PlanetWeatherManager` assigns
  `world.worldInfo = wrapped` directly (`world/weather/PlanetWeatherManager.java:187`, `:219`) to swap
  the `StellurgyDimensionWorldInfo` wrapper in and out — possible only because the field is public.
  Contractual constant: **the SRG name `field_72986_A`** must not be renamed (a rename silently breaks the
  wrapper install).
- **The vendored physics tree's entries** (broad "all fields" widening of the `net.minecraft.*` classes
  the physics engine reads, plus `Entity.func_145775_I` and `Minecraft.field_193996_ah`) and the
  **AFFS entries** (`NBTTagLongArray`, `EntityLivingBase`) are in the same file. Their purpose and
  consumers are the vendored trees', not Stellurgy's own mixins'.
- An AT applies at classload independent of refmap state, in dev and in reobf. Wiring:
  `use_access_transformer=true`, `access_transformer_locations` = `stellurgy_at.cfg`
  (`gradle.properties:97-98`); applied to the SRG deobf tasks (`build.gradle:181-186`) and declared as
  the `FMLAT` manifest attribute (`build.gradle:246-247`).

## Dev-vs-prod run flags

- The dev `runs` JVM args set `-Dmixin.hotSwap`, `-Dmixin.checks.interfaces`, `-Dmixin.debug.export`
  when `use_mixins` is on (`build.gradle:94-97`).
- The mitigation `-Dmixin.env.disableRefMap=true` for the dev-only silent no-op is **not** in the
  committed `build.gradle` run block, and `extra_jvm_args` is empty (`gradle.properties:44`). See F2.

## Collisions & risks

- **`required: true` governs APPLICATION failure, not injector misses.** A mixin that fails to apply
  crashes the load and takes every Stellurgy mixin with it (INV-MIX-03 `[V]`); an injector that finds no
  target is silently dropped unless it carries `require = 1` (the config has no `injectors` block, so the
  default is 0). The injectors with `require = 1` are `MixinWorldSetBlockState`,
  `MixinEntityTrackerRiderSeesVehicle` and both of `MixinPlayerList`; the render mixins carry
  `require = 0` explicitly because OptiFine and other render mods rewrite `renderRainSnow` and
  `orientCamera` / `applyRotations`. Any coremod that reshapes the other targets
  (`WorldServer.tick`, `EntityPlayer.onUpdate`, …) silently disables gravity, atmosphere, rocket-GUI,
  weather and ship-frame handling together. Cross-cutting fragility — see F1.
- **dev refmap silent no-op.** With `disableRefMap` absent (F2), every `@Inject` / `@Redirect` here can
  silently no-op in the dev workspace; with no `@Accessor` there is no loud `InvalidAccessorException`
  early warning either.
- **The plugin's config path is resolved against the game directory.** `StellurgyMixinPlugin` reads the
  literal `config/advRocketry/stellurgy.cfg` under `Launch.minecraftHome` while `StellurgyConfiguration`
  uses `event.getModConfigurationDirectory()` (`Stellurgy.java:596`). A launcher / modpack that relocates
  the config directory makes the plugin fail open to `perDimWorldInfo=true`, weaving the gated mixins even
  when the user disabled the flag — silent divergence. See F3.
- **AT / wrapper coupling.** The AT-widened `World.worldInfo` and the `MixinWorldServerMulti` wrapper
  install (MECH-MIX-06) are two owners of the same field on planet worlds; correctness depends on the SRG
  name `field_72986_A` staying pinned.
- **A whole-method replacement re-issues packets by hand.** `MixinPlayerList` does not issue the
  world-border packet: it redirects the single call it disagrees with and vanilla sends its own (INV-MIX-08,
  retired). Replacing the whole method would require re-issuing every packet the method sends, including
  `SPacketSpawnPosition`.
- **Two mixins claim `Entity.move`.** The vendored physics mod injects at the same point, cancellably;
  `MixinEntityShipLocalMove` declares `priority = 1500` so its callback runs first, because Mixin emits an
  `if (ci.isCancelled()) return;` guard after each callback and the first to cancel prevents the rest.

## Risks stated as findings

F1 — `required:true` with `defaultRequire` 0: a default-guard injector whose target a third-party mod
reshapes is silently dropped, and a mixin that fails to apply takes the whole config down. Three
injectors fail loud by `require = 1` and the render ones are deliberately soft; the rest rely on the
default. Documented as INV-MIX-03. severity med · confidence likely.

F2 — `-Dmixin.env.disableRefMap=true` is absent from the committed dev run config
(`build.gradle:94-97`; `gradle.properties:44`). In a plain `runClient` / `runServer` the remapped mixins
can silently no-op in dev. May be layered by an external IDE / test run configuration not in the repo —
hence "likely", not "confirmed". severity med · confidence likely.

F3 — The plugin's config path (`Launch.minecraftHome` + `config/advRocketry/stellurgy.cfg`) diverges from
`StellurgyConfiguration`'s `getModConfigurationDirectory()`-based path (`Stellurgy.java:596`). If the
config directory is relocated the plugin cannot find the file and fail-opens to `perDimWorldInfo=true`,
weaving the gated mixins against the user's disabled setting. severity low · confidence confirmed.

## Cross-references

- Mechanics & invariants: `20-subsystems/mixins-asm-coremod.md` (`MECH-MIX-*`, `INV-MIX-*`).
- Config flag `Planet.perDimWorldInfo`: contract **C4** (config surface).
- Weather-wrapper consumer of the AT: `20-subsystems/world-gen/providers-weather.md`,
  `world/weather/PlanetWeatherManager.java`.
