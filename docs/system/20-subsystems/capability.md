---
id: capability
owns: [capability/]
entrypoints: [CapabilityProtectiveArmor#attachCapabilities, TankCapabilityItemStack#getCapability, DimensionCompat#getDefaultSpawnDimension]
depends-on: [api-public, atmosphere-oxygen]
depended-by: [items, atmosphere-oxygen, world-gen, misc-oddities]
contracts: [C5]
confidence: high
---

## Purpose

Glue between Stellurgy item/tile types and Forge's `ICapabilityProvider` system.
Three small, unrelated adapters: one wires the `IProtectiveArmor` capability onto space-suit
item stacks, one wraps a fluid tank onto pressure-tank item stacks, and one is a reflection
shim that reads the JustEnoughDimensions default-spawn dimension when that mod is present. The
capability *tokens* (`PROTECTIVEARMOR`, `PART_WEAR`) and interfaces (`IProtectiveArmor`,
`IPartWear`) are declared and registered in `api-public`; this package only provides/attaches.

## Responsibility boundary

Owns: the `AttachCapabilitiesEvent<ItemStack>` subscriber for space armor
(`CapabilityProtectiveArmor`); the item-stack fluid-handler provider
(`TankCapabilityItemStack`); the JED soft-dependency reflection shim (`DimensionCompat`).
Does NOT own: the `Capability<>` token declarations, `CapabilityManager.register` calls, or the
`IProtectiveArmor` / `IPartWear` interfaces (all in `api/capability`, `api/armor` → api-public);
the armor's protection logic (`ItemSpaceArmor`, atmosphere-oxygen); wear consequence formulas
(`StorageChunk`, `TileWearable`); the JED config values themselves (foreign mod).

## Key types

`CapabilityProtectiveArmor` (attaches the `IProtectiveArmor` cap to `ItemSpaceArmor` stacks) is inventoried in
`C5-capabilities-events`. Not in C5:

| class | role |
|-------|------|
| `TankCapabilityItemStack` | `ICapabilityProvider` exposing `FLUID_HANDLER_ITEM_CAPABILITY` backed by a `FluidHandlerItemStack` |
| `DimensionCompat` | stateless reflection accessor (no static fields) for JustEnoughDimensions' initial-spawn dimension, read per respawn |

## Mechanics

- **MECH-CAP-01 — attach protective-armor cap to suit stacks.** On each
  `AttachCapabilitiesEvent<ItemStack>`, if the stack's item is an `ItemSpaceArmor` and the key is
  not already present, the item instance itself (an `ICapabilityProvider`) is registered as the
  stack's capability provider under key `stellurgy:ProtectiveArmor`.
  `CapabilityProtectiveArmor.java:20-29`. The event handler is registered at mod init:
  `Stellurgy.java:331`. Resolution then goes through `ItemSpaceArmor.getCapability`, which
  returns the item singleton for `CapabilitySpaceArmor.PROTECTIVEARMOR` `ItemSpaceArmor.java:275-278`.
- **MECH-CAP-02 — expose fluid tank on pressure-tank stacks.** `ItemPressureTank.initCapabilities`
  returns a fresh `TankCapabilityItemStack(stack, capacity)` per stack `ItemPressureTank.java:124-125`;
  the provider answers only `FLUID_HANDLER_ITEM_CAPABILITY`, casting an internal
  `FluidHandlerItemStack` `TankCapabilityItemStack.java:23-32`.
- **MECH-CAP-03 — JED default-spawn dimension.** `getDefaultSpawnDimension` reads the
  `fi.dy.masa.justenoughdimensions.config.Configs` fields BY REFLECTION ON EACH CALL (there is no static
  initializer and no cached static field) and returns the JED
  spawn id when override is enabled, else `0`; it is asked on respawn only, so JED's config is read where
  it is needed, not cached at a class load that happens mid-game. `DimensionCompat.java:15-32`. Consumed
  by `WorldProviderPlanet.getSpawnPoint` path `WorldProviderPlanet.java:336`.

## State & persistence

None. No NBT keys, registry names, config flags, packets, or mixins are owned here (none of the seam inventories lists these files). Capability *storage* is a no-op: both `IProtectiveArmor` and
`IPartWear` register `IStorage` implementations whose `writeNBT` returns `null` / `readNBT` does
nothing `CapabilitySpaceArmor.java:20-33` (owned by api-public), so these caps are never
serialized — they are pure live views over item/tile state.

## Invariants

- **INV-CAP-01 [V]** The attach handler is idempotent: it early-returns if `KEY` is already on the
  stack, so re-fired events never double-register. `CapabilityProtectiveArmor.java:22-24`.
- **INV-CAP-02 [V]** The capability key string is the contractual literal
  `stellurgy:ProtectiveArmor`. `CapabilityProtectiveArmor.java:13`.
- **INV-CAP-03 [V]** The provider attached for a suit stack is the shared `Item` singleton (not a
  per-stack object); it is safe only because `ItemSpaceArmor`'s cap resolution is stateless and
  takes the `ItemStack` as a parameter (`IProtectiveArmor.protectsFromSubstance(atm, stack, …)`).
  `CapabilityProtectiveArmor.java:26-27`, `IProtectiveArmor.java:15`.
- **INV-CAP-04 [V]** `TankCapabilityItemStack` advertises exactly one capability,
  `FLUID_HANDLER_ITEM_CAPABILITY`, and returns `null` for all others. `TankCapabilityItemStack.java:23-32`.
- **INV-CAP-05 [V]** `DimensionCompat.getDefaultSpawnDimension` returns `0` on every failure path
  (JED class absent; override disabled; reflection or the cast throws). `DimensionCompat.java:15-32`.
- **INV-CAP-06 [V]** JED binding is best-effort and no longer a load-time step: nothing is bound at
  mod load, so nothing can fail it. A JED that is present but whose two settings cannot be read is a
  DEGRADATION that announces itself — a `warn` on every respawn naming that JED is installed but
  unreadable and that the overworld is used instead — while JED ABSENT is silent, because that is not
  a failure. `DimensionCompat.java:19-21,27-30`.
- **INV-CAP-07 [T]** All four suit pieces resolve the `IProtectiveArmor` capability at runtime —
  asserted by the machine-domain smoke suite. `MachineDomainSmokeSuite.java:388-399`.
- **INV-CAP-08 [A]** The `IProtectiveArmor`/`IPartWear` caps are never persisted, so removing this
  mod's items from a save cannot leave dangling capability NBT (follows from the no-op `IStorage`;
  not exercised by a round-trip test).

## Failure modes & edge cases

- **Dead code.** `CapabilityProtectiveArmor.registerCap()` is a fully commented-out no-op and is
  never called; the real registration is `EVENT_BUS.register` at `Stellurgy.java:331`.
  `CapabilityProtectiveArmor.java:15-18`.
- **Reflection drift.** `DimensionCompat` hardcodes JED field names `initialSpawnDimensionId` /
  `enableInitialSpawnDimensionOverride`; a JED rename degrades to spawn-dimension `0` but no longer
  silently: the `NoSuchFieldException` is caught and warned on each respawn
  (`DimensionCompat.java:23-24,27-30`).
- **Shared-provider aliasing.** Every stack of a given suit piece shares one provider instance
  (INV-CAP-03); any future per-stack state on the provider would be a cross-stack leak.

## Integration seams

- **C5 capabilities/events.** Subscribes `AttachCapabilitiesEvent<ItemStack>`
  (`CapabilityProtectiveArmor#attachCapabilities`); handler instance registered on the Forge event
  bus at `Stellurgy.java:331`. Provides the two Forge capability tokens declared in
  api-public: `CapabilitySpaceArmor.PROTECTIVEARMOR` (`IProtectiveArmor`) and
  `CapabilityWear.PART_WEAR` (`IPartWear`); both registered at `Stellurgy.java:1084-1085`.
- **Item hook.** `TankCapabilityItemStack` is instantiated from `Item#initCapabilities`
  (`ItemPressureTank.java:124`), Forge's per-stack capability entry point.
- **Soft mod dependency.** `DimensionCompat` reflects into JustEnoughDimensions; consumed by
  `WorldProviderPlanet.java:336` (world-gen).

## Config surface

None owned. Behaviour is gated only by the presence of foreign mods (JED for MECH-CAP-03); there
is no `StellurgyConfiguration` flag in these files.

## Test coverage

- INV-CAP-07 → `src/test/java/dev/stannismod/stellurgy/test/server/MachineDomainSmokeSuite.java:388-399`
  (also referenced by `OxygenSuitClientStateE2ETest.java:24`).
- INV-CAP-01..06, 08 — no dedicated unit test; verified by code read.

## Open questions

- Is `CapabilityProtectiveArmor.registerCap()` intended to be revived (its commented body suggests
  a Forge-Energy integration path), or is it removable dead code? (unconfirmed).
- No test pins the `TankCapabilityItemStack` fluid round-trip on a pressure-tank stack, nor the
  JED-present branch of `DimensionCompat` (JED not on the test classpath).
