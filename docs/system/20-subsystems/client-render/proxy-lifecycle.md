---
id: client-render/proxy-lifecycle
parent: client-render
files: [client/ClientProxy.java, client/ModelLoader.java, client/SoundRocketEngine.java, client/model/ModelRocket.java, client/gui/ModuleSelectableAtmosphereButton.java]
sloc: ~1660
contracts: [C4, C5, C7]
confidence: high
---

## Purpose

`ClientProxy` is the client-side implementation of `CommonProxy`: it is the single place
that binds every TESR / entity renderer, registers item & fluid models, wires colorizers,
and services the proxy verbs the common code calls to spawn particles, sounds, and status
messages. This cluster also holds the small client helpers it leans on.

## Key types

| class | role |
|-------|------|
| `ClientProxy` | client `CommonProxy` impl; renderer/model registration + particle/sound/UI proxy verbs |
| `SoundRocketEngine` | `MovingSound` tracking one `EntityRocket`; volume = engine power, pitch from motionY |
| `ModelRocket` | `IModel` wrapper baking the `stellurgy:rocket.obj` motor model |
| `ModelLoader` | custom `ICustomModelLoader` for `*rocketmotor*` models — **registration not found** |
| `ModuleSelectableAtmosphereButton` | libVulpes GUI button module for the atmosphere detector |

## Mechanics

**MECH-CLR-01 — TESR & entity-renderer registration.** `registerRenderers()` (called from
`preinit`) binds ~25 `TileEntitySpecialRenderer`s via `ClientRegistry.bindTileEntitySpecialRenderer`
and 8 entity renderers via `RenderingRegistry.registerEntityRenderingHandler`. Trigger: FML
client `preinit`. Effect: each tile/entity class gets its Stellurgy renderer. `ClientProxy.java:106-148`,
`:267-272`. Three bindings are commented out (drill TESR, orbital-laser-drill TESR variant).

**MECH-CLR-02 — Item / block / fluid model registration.** `preInitBlocks` + `preInitItems`
map each item meta to a `ModelResourceLocation` via Forge `ModelLoader.setCustomModelResourceLocation`;
fluids get a `FluidStateMapper` + `FluidItemMeshDefinition` so every fluid block/item resolves to
`stellurgy:fluid#<fluidName>`. `ClientProxy.java:168-312`. Contract C7 (asset names).

**MECH-CLR-03 — Colorizer registration.** `init()` registers `CrystalColorizer` as block+item
colour handler for `blockCrystal`, and an inline `IItemColor` giving the four space-suit armour
pieces their dyed-leather tint (tintIndex 0). `ClientProxy.java:150-165`.

**MECH-CLR-04 — Particle spawning proxy verbs.** `spawnParticle(name,…)` switch-dispatches a
string to a concrete `EntityFX` (`rocketFlame`, `smallRocketFlame`, `rocketSmoke[Inverse]`, `arc`,
`smallLazer`, `errorBox`, `gravityEffect`; default → vanilla `EnumParticleTypes`). `spawnDynamicRocketSmoke/Flame`
build multi-engine `TrailFx`/`RocketFx`; `spawnLaser` emits a laser + heat + 4 sparks. All add to
`Minecraft.effectRenderer`. `ClientProxy.java:363-443`. Balance counts/scales `tunable`.

**MECH-CLR-05 — Engine sound.** `SoundRocketEngine` is a looping `MovingSound` constructed by
`EntityRocket` (reverse dep) and handed to `LibVulpes.proxy.playSound`. Each tick `update()` sets
`volume = rocket.getEnginePower()` and derives pitch from `motionY` (orbit vs atmosphere branches);
self-terminates (`donePlaying=true`) when the rocket is dead / world mismatched. `SoundRocketEngine.java:19-45`.

**MECH-CLR-06** — retired.

**MECH-CLR-07 — misc proxy verbs.** HUD layout is not a proxy verb (no `loadUILayout`, no `Client`-category ints): the suit, oxygen, hydrogen and atmosphere
panels are placed by `client/HudLayout`, a pure function of the screen size each frame is drawn at
`[V]` (`HudLayout.java:26-56`). Verbs: `displayMessage` (overlay), `sendClientStatusMessage`
(action-bar `TextComponentTranslation`), `fireFogBurst`, `changeClientPlayerWorld`,
`calculateCelestialAngleSpaceStation`, `getWorldTimeUniversal`, scroll-pan factories. `ClientProxy.java:445-581`.

## State & persistence

- No config and no NBT owned by this subsystem. (There are no `Client`-category HUD layout ints; an old config file may keep the keys, and nothing reads them.)
- **Config reads** (C4): `oxygenVentSize` (tooltip arg, via `TooltipInjector`), no others here.
- No model cache on the proxy: there is no `ClientProxy.getModel` and no static map;
  the one renderer that loaded broken-part models keeps them as its own instance field
  (`RendererBrokenPart.models`), loaded in its constructor — one per `IBrokenPartBlock` in the block
  registry — which is why `ClientProxy.init` binds that TESR and not `registerRenderers` (pre-init,
  before the blocks exist).

## Integration seams

- **Events (C5)**: `@Mod.EventBusSubscriber(Side.CLIENT)`; `@SubscribeEvent modelBakeEvent`;
  `registerEventHandlers` registers `RocketEventHandler`, `DelayedParticleRenderingEventHandler`,
  `RenderComponents`, `ModuleContainerPan`, and (if JEI loaded) `JeiClientTickHandler`.
  `ClientProxy.java:314-336`.
- **Test hook**: `bootstrapTestClientBridge` reflectively starts the test-framework client
  bridge iff `-Dforge.test.client=true`; inert (and CNF-safe) in production. `ClientProxy.java:274-297`.

## Invariants

- **INV-CLR-03 [V]** All renderer/model registration is confined to `ClientProxy` overrides, so
  common code never touches a client-only class. `ClientProxy.java:105-165,267-312`.
- **INV-CLR-04 [V]** Proxy accessors that read replicated client state swallow `NullPointerException`
  and return a neutral value (angle 0 / time 0) while packets are still arriving — never crash the
  render thread. `ClientProxy.java:446-467`.
- **INV-CLR-05 [V]** `SoundRocketEngine` stops itself when its rocket dies or changes world, so a
  dead entity cannot leak a looping sound. `SoundRocketEngine.java:23-31`.
- **INV-CLR-06** — retired.

## Failure modes & edge cases

- `modelBakeEvent` builds a `ModelRocket customModel` but re-inserts the **original** baked model,
  discarding the custom one → the intended rocket-model override is a silent no-op.
- `fireFogBurst` / `getWorldTimeUniversal` reference `Minecraft…world` before it exists on the
  title screen; guarded by try/catch.

- **modelBakeEvent no-op**: `ClientProxy.java:315-320` constructs `new ModelRocket()` then
  `putObject(ModelRocket.resource, bakedModel)` (the original), so `customModel` is dead and the
  override never applies.
- **`ModelLoader` never registered**: no `ModelLoaderRegistry.registerLoader(new ModelLoader())`
  anywhere in `src/main` → the custom `*rocketmotor*` loader is unreachable dead code.
  `ModelLoader.java:12`.

## Test coverage

No direct unit test for `ClientProxy`; the test-client bridge (`bootstrapTestClientBridge`) is what
lets the e2e harness drive a real client. Proxy behaviour is exercised indirectly by
`FreeFlightModeTest`.
