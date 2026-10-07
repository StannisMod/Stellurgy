---
id: client-render/tile-renderers
parent: client-render
files: [client/render/multiblocks/*.java (22), client/render/RenderTank.java, client/render/RendererRocketAssemblingMachine.java, client/render/RendererPhantomBlock.java, client/render/RendererBrokenPart.java, client/render/RenderOrbitalLaserDrillTile.java]
sloc: ~2200
contracts: [C4, C7]
confidence: high
---

## Purpose

The `TileEntitySpecialRenderer`s for Stellurgy's multiblock machines and a handful of special tiles.
Catalogue cluster: one shared OBJ-model pattern reused ~27 times, described once here with a
per-renderer table.

## Shared pattern (MECH-CLR-23)

Every machine TESR follows the same shape, verified against `RendererLathe` and `RenderBeacon`:

1. **Ctor** loads a `WavefrontObject` OBJ (`backwardCompat.WavefrontObject`) + a texture
   `ResourceLocation`, once. `RendererLathe.java:20-30`, `RenderBeacon.java:20-27`. (C7 asset names.)
2. **`render(tile,x,y,z,f,damage,a)`** casts to the tile's libVulpes base
   (`TileMultiblockMachine` / `TileMultiPowerConsumer`), and **early-returns unless `canRender()`**.
   `RendererLathe.java:35-38`, `RenderBeacon.java:32-35`.
3. Push matrix, translate to block centre, rotate by `RotatableBlock.getFront(blockState)`,
   `bindTexture`, render named model parts (`renderOnly`/`renderPart`). `RendererLathe.java:40-55`.
4. **Animate**: either progress-driven (`getProgress(0)/getTotalProgress(0)` → part translate/spin,
   output-material colour tint) or enabled-driven (`getMachineEnabled()` → real-time spin).
   `RendererLathe.java:50-99`, `RenderBeacon.java:51-59`.

**MECH-CLR-24 — animation drivers.** Two families: *progress-driven* (recipe progress) —
Lathe, Crystallizer, CuttingMachine, PrecisionAssembler, PrecisionLaserEtcher, RollingMachine; and
*real-time-driven* (`System.currentTimeMillis()`) — Beacon, BlackHoleGenerator, SpaceElevator,
the lathe. `RenderBeacon.java:52`, `RendererLathe.java:52`. (The warp core's renderer was
deleted with the machine in 0.1.0; the `warpcore.obj` ring it loaded now is
loaded into an `orbitRing` instance field of
`RenderPlanetUIEntity` and of `RenderStarUIEntity` (`RenderPlanetUIEntity.java:25-26`,
`RenderStarUIEntity.java:23-24`), one `WavefrontObject` per renderer, not a shared static.)

**MECH-CLR-25 — VFX config gate.** `RendererMicrowaveReciever` only draws its beam/particles when
`StellurgyConfiguration.getCurrentConfig().advancedVFX && getPowerMadeLastTick() > 0`. `RendererMicrowaveReciever.java:46`. C4.

### Renderer table (tile ← TESR, from `ClientProxy.registerRenderers` bindings)

| tile class | renderer | model / notes |
|------------|----------|---------------|
| `TileRocketAssemblingMachine` | `RendererRocketAssemblingMachine` | draws the scanned rocket bounds/build preview (`:24`) |
| `TilePrecisionAssembler` | `RendererPrecisionAssembler` | progress-driven |
| `TileCuttingMachine` | `RendererCuttingMachine` | progress-driven |
| `TileCrystallizer` | `RendererCrystallizer` | progress-driven |
| `TileObservatory` | `RendererObservatory` | |
| `TileAstrobodyDataProcessor` | `RenderAstrobodyDataProcessor` | |
| `TileLathe` | `RendererLathe` | progress-driven; tool/shaft/rod spin + material colour |
| `TileRollingMachine` | `RendererRollingMachine` | progress-driven |
| `TileElectrolyser` | `RendererElectrolyser` | |
| `TileChemicalReactor` | `RendererChemicalReactor` | OBJ path passed to ctor |
| `TileSchematic` (libVulpes) | `RendererPhantomBlock` | ghost preview block |
| `TileMicrowaveReciever` | `RendererMicrowaveReciever` | `advancedVFX`-gated beam |
| `TileBiomeScanner` | `RenderBiomeScanner` | |
| `TileBlackHoleGenerator` | `RenderBlackHoleGenerator` | real-time spin |
| `TileAtmosphereTerraformer` | `RenderTerraformerAtm` | |
| `TileFluidTank` | `RenderTank` | draws fluid sprite quads from `getFluid()` (`:29-44`) |
| `TileOrbitalLaserDrill` | `RenderOrbitalLaserDrill` | (`RenderOrbitalLaserDrillTile` variant commented out in proxy) |
| `TileRailgun` | `RendererRailgun` | |
| `TileAreaGravityController` | `RenderAreaGravityController` | |
| `TileSpaceElevator` | `RendererSpaceElevator` | real-time spin |
| `TileBeacon` | `RenderBeacon` | real-time dual spin + `RenderLaser` |
| `TileCentrifuge` | `RenderCentrifuge` | |
| `TilePrecisionLaserEtcher` | `RendererPrecisionLaserEtcher` | progress-driven |
| `TileSolarArray` | `RendererSolarArray` | |
| `TileBrokenPart` | `RendererBrokenPart` | renders the broken multiblock part model |

`RenderLaser` is a shared helper (also an entity renderer for `EntityLaserNode`, see
entity-armour-renderers) used by beam-drawing TESRs like `RenderBeacon`. `ClientDynamicTexture`
is a `BufferedImage`-backed dynamic GL texture used by data-processor / scanner readouts.

## State & persistence

No NBT. Config read: `advancedVFX` (C4, MECH-CLR-25). All animation state is derived per-frame from
the tile (progress/enabled) or wall-clock; no client-side persistence.

## Integration seams

- **← multiblock-machines / infrastructure-tiles**: each TESR reads its tile's `canRender()`,
  `isRunning()`, `getProgress()/getTotalProgress()`, `getOutputs()`, `getMachineEnabled()`,
  `getPowerMadeLastTick()`, `getFluid()`.
- **← backward-compat**: `WavefrontObject` OBJ parser.
- **← libVulpes**: `RotatableBlock.getFront`, `TileMultiblockMachine`, `TileMultiPowerConsumer`,
  `MaterialRegistry.getColorFromItemMaterial`.
- **← api-public**: `StellurgyConfiguration.advancedVFX`.
- Bindings registered in `ClientProxy.registerRenderers` (`ClientProxy.java:107-134`).

## Invariants

- **INV-CLR-17 [V]** No machine model renders unless `canRender()` is true (multiblock formed &
  ready) — the universal first guard. `RendererLathe.java:37-38`, `RenderBeacon.java:34-35`.
- **INV-CLR-18 [V]** Progress animation is normalised `getProgress(0)/getTotalProgress(0)`, so it is
  independent of the (tunable) recipe duration. `RendererLathe.java:52`.
- **INV-CLR-19 [V]** `RendererLathe` defends against a null `getOutputs()` (open within the first
  tick) before reading the material colour. `RendererLathe.java:74-78`.
- **INV-CLR-20 [A]** Real-time (`System.currentTimeMillis()`) animations keep spinning while the
  game is paused and are not tick-synced — assumed cosmetic-only. `RenderBeacon.java:52`.

## Failure modes & edge cases

- `RendererLathe` ctor compiles a GL display list whose id is discarded — a small one-time GL-list
  leak. `RendererLathe.java:27-30`.
- `RenderTank` no-ops cleanly when the tank fluid is null. `RenderTank.java:29`.

- **Leaked GL display list**: `RendererLathe.java:27-30` — `glNewList(glGenLists(1),…)`/`glEndList`
  with the list id never stored or called.
- **Pause-independent animation**: several TESRs animate on `System.currentTimeMillis()` rather than
  world time (`RenderBeacon.java:52` et al.) — cosmetic; noted for consistency.

## Test coverage

None — TESR GL output is not exercised in the headless harness.
