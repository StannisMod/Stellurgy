---
id: client-render/entity-armour-renderers
parent: client-render
files: [client/render/RendererRocket.java, client/render/entity/RenderPlanetUIEntity.java, client/render/entity/RenderStarUIEntity.java, client/render/entity/RenderButtonUIEntity.java, client/render/entity/RenderElevatorCapsule.java, client/render/entity/RenderHoverCraft.java, client/render/entity/RendererItem.java, client/render/armor/RenderJetPack.java, client/render/RenderLaser.java, client/render/RenderComponents.java, client/render/DelayedParticleRenderingEventHandler.java, client/render/ClientDynamicTexture.java]
sloc: ~1500
contracts: [C5, C7]
confidence: high
---

## Purpose

Entity renderers (the flyable rocket, the holographic UI planet/star/button entities used by the
planet-selector GUIs, the hovercraft and station elevator capsule, abducted items), the jetpack
armour model, the shared laser renderer, the render-time particle handlers, and the dynamic-texture
helper.

## Key types

| class | role |
|-------|------|
| `RendererRocket` | `Render<EntityRocket>` + `IRenderFactory`; block-mesh render of the `StorageChunk`, connection lines, FF attitude |
| `RenderPlanetUIEntity` | holographic planet entity for planet-selector GUIs (uses `RocketRenderHelper` orbits) |
| `RenderStarUIEntity` | holographic star entity |
| `RenderButtonUIEntity` | holographic UI button entity |
| `RenderElevatorCapsule` | `EntityElevatorCapsule` (space-elevator car) |
| `RenderHoverCraft` | `EntityHoverCraft` model |
| `RendererItem` | `EntityItemAbducted` (tractor-beamed item) renderer |
| `RenderLaser` | `Render<EntityLaserNode>` + laser-beam helper reused by beam TESRs |
| `RenderJetPack` | `ModelBiped` OBJ jetpack, returned as the space-chest armour model |
| `RenderComponents` | `RenderPlayerEvent.Post` handler — jetpack draw body is commented out (no-op) |
| `DelayedParticleRenderingEventHandler` | batches `RocketFx`/`InverseTrailFx` for `RenderWorldLastEvent` |
| `ClientDynamicTexture` | `BufferedImage`+`IntBuffer` GL texture for scanner/data readouts |

## Mechanics

**MECH-CLR-26 — Rocket block-mesh render.** `RendererRocket.doRender` returns early unless
`storage != null && storage.finalized`. It draws stippled `GL_LINE_LOOP` tethers to each connected
`IInfrastructure` that `canRenderConnection()` (only while grounded), then renders the whole
`StorageChunk` block-by-block into a **per-world display list** (`storage.world.displayListIndex`,
built once when `-1`, skipping `chiselsandbits` blocks). `RendererRocket.java:52-152`.

**MECH-CLR-27 — Free Flight attitude (quaternion slerp).** In FF-in-flight the model is oriented to
the craft body frame — yaw about world-up then pitch about the lateral axis (+90° maps the
vertically-built model's +Y nose onto forward) — by **slerping the attitude quaternion**
(`FreeFlightPhysics.Quat`) between last and current tick and deriving Euler for `glRotate`, giving
loop-apex-safe interpolation. The legacy `seatY` shim is skipped in FF (passenger sits at the real
seat block). `RendererRocket.java:66-73,154-171+`. Edge into rocket-entity.

**MECH-CLR-28 — Holographic UI entities.** `RenderPlanetUIEntity`/`RenderStarUIEntity`/
`RenderButtonUIEntity` are `Render<…> implements IRenderFactory` — self-registering renderers for
the fake entities the planet-selector / observatory GUIs spawn to draw orbiting bodies (using
`RocketRenderHelper.renderOrbit`/`renderPositionAlongOrbit`). `RenderPlanetUIEntity.java:21-51`.

**MECH-CLR-29 — Craft & item entity renderers.** `RenderHoverCraft` and `RenderElevatorCapsule`
draw their OBJ/model at the entity transform; `RendererItem` wraps the vanilla item renderer for
`EntityItemAbducted`. `RenderHoverCraft.java:58`, bindings in `ClientProxy.java:142-147`.

**MECH-CLR-30 — Jetpack armour model.** `RenderJetPack extends ModelBiped`; loads `jetPack.obj`
once (static) and is returned by `ItemSpaceArmor.getArmorModel` (reverse dep, atmosphere-oxygen) so
the pack renders on the wearer's back, translating/rotating with the sneak pose. `RenderJetPack.java:11-60`.

**MECH-CLR-31 — Delayed particle rendering.** `DelayedParticleRenderingEventHandler` keeps static
lists of `RocketFx`/`InverseTrailFx`, ticks them (`onUpdate2`) each client tick, renders them in
`RenderWorldLastEvent` (so exhaust draws after the world), and prunes dead particles + those whose
world unloaded (client side only). `DelayedParticleRenderingEventHandler.java:18-45`.

## State & persistence

No NBT. Per-world GL cache: `StorageChunk.world.displayListIndex` (built once per rocket world).
Static particle lists in `DelayedParticleRenderingEventHandler` (bounded by prune each tick +
world-unload). No config owned.

## Integration seams

- **← rocket-entity**: `EntityRocket`, `StorageChunk`, `FreeFlightPhysics.Quat`, `IInfrastructure`.
- **← atmosphere-oxygen** (reverse): `ItemSpaceArmor.getArmorModel` → `RenderJetPack`.
- **← rocket-entity `entity/fx/`** (reverse spawn): `RocketFx`/`InverseTrailFx` classes.
- **Events (C5)**: `RenderWorldLastEvent`, `ClientTickEvent`, `WorldEvent.Unload`
  (`DelayedParticleRenderingEventHandler`); `RenderPlayerEvent.Post` (`RenderComponents`).
- Entity-renderer bindings in `ClientProxy.registerRenderers` (`ClientProxy.java:140-147`).

## Invariants

- **INV-CLR-21 [V]** `RendererRocket` renders nothing until its `StorageChunk` is finalized, so a
  half-built/streaming rocket is not drawn. `RendererRocket.java:58-59`.
- **INV-CLR-22 [V]** The rocket block mesh is compiled to a display list once
  (`displayListIndex == -1` guard) and reused. `RendererRocket.java:112-116`.
- **INV-CLR-23 [V]** Infrastructure tether lines draw only while the rocket is not in flight.
  `RendererRocket.java:87`.
- **INV-CLR-24 [V]** FF attitude interpolates the quaternion (slerp) rather than Euler angles,
  staying continuous through ±90° pitch. `RendererRocket.java:157-171`.
- **INV-CLR-25 [V]** `DelayedParticleRenderingEventHandler.onWorldUnload` ignores server worlds
  (`!event.getWorld().isRemote` → return), touching only client particle lists. `:39-45`.

## Failure modes & edge cases

- Unrenderable blocks on a rocket (NPE in `renderBlock`) are caught and logged per-block, not fatal.
  `RendererRocket.java:137-139`.
- `RenderComponents.renderPostSpecial` runs on every player render but its actual jetpack draw is
  commented out — a registered handler that does nothing. `RenderComponents.java:14-30`.

- **No-op registered handler**: `RenderComponents` is registered
  (`ClientProxy.java:329`) but `renderPostSpecial` only sets up/tears down a matrix around a
  commented-out `pack.render(...)` — pure overhead. `RenderComponents.java:16-25`.

## Test coverage

- FF attitude / seat behaviour exercised end-to-end by `test/client/FreeFlightModeTest.java`
  (drives a real client; reads craft orientation). No unit tests for the GL draw paths.

## Open questions

- Whether `RenderJetPack` (an armour `ModelBiped`) and the dead `RenderComponents` jetpack path are
  two attempts at the same feature (SSOT smell) — one is live, one dead.
