---
id: integration-modcompat
owns: [integration/*.java]
entrypoints: [MatterOvedriveIntegration#isAndroidNeedNoOxygen, MatterOvedriveIntegration#addAndroidsToBypassList, GalacticCraftHandler#GCSuffocationEvent]
depends-on: [atmosphere-oxygen, api-public]
depended-by: [atmosphere-oxygen, api-public, misc-oddities]
contracts: [C4, C5]
confidence: high
---

## Purpose

Thin, optional glue against three foreign mods so Stellurgy's oxygen/atmosphere
system does not fight them: Matter Overdrive androids that need no air, GalactiCraft's
competing suffocation/oxygen overlay, and a legacy mod-presence probe. Every hook is
guarded by `Loader.isModLoaded(...)` at the call site so the classes are never touched
when the target mod is absent.

## Responsibility boundary

Owns: the `integration/` root — the cross-mod behavioural shims (`MatterOvedriveIntegration`,
`GalacticCraftHandler`) and the vestigial mod-presence flags (`CompatibilityMgr`).
Does NOT own: JEI (`integration/jei/` → integration-jei), TheOneProbe/WAILA
(→ integration-probe-waila), datapack loaders (→ integration-dataloaders). Does NOT own
`StellurgyConfiguration.bypassEntity` / `overrideGCAir` themselves (owned by api-public) — only
mutates/reads them across the seam.

## Key types

| class | role |
|-------|------|
| `MatterOvedriveIntegration` | Static shim: is-android-immune query + registers MO android entities into the oxygen bypass list. |
| `GalacticCraftHandler` | Forge event subscriber that neutralises GC's oxygen suffocation + client oxygen-overlay warning. |
| `CompatibilityMgr` | Legacy static mod-presence flags (gregtech/thermal/sponge). Largely vestigial. |

## Mechanics

- **MECH-MODC-01 — Android air-immunity** [V]. `AtmosphereNeedsSuit.isImmune`, when
  `matteroverdrive` is loaded, delegates to `isAndroidNeedNoOxygen`: true iff the entity
  holds an MO android capability that is an unlocked android with the `oxygen` biotic stat
  at level ≥1. Grants full suit immunity, skipping the armour checks.
  `atmosphere/AtmosphereNeedsSuit.java:33-35`, `integration/MatterOvedriveIntegration.java:12-16`.
- **MECH-MODC-02 — Android bypass-list registration** [V]. During
  `StellurgyConfiguration.registerEntities`, when `matteroverdrive` is loaded,
  `addAndroidsToBypassList` adds `EntityDrone`, `EntityRangedRogueAndroidMob`,
  `EntityMeleeRougeAndroidMob` to `stellurgyConfig.bypassEntity`. Comment states Stellurgy could not
  register MO entities by name, so this is a hard-coded class-list fallback.
  `api/StellurgyConfiguration.java:785-787`, `integration/MatterOvedriveIntegration.java:18-24`.
- **MECH-MODC-03 — GC suffocation cancel** [V]. On `GCCoreOxygenSuffocationEvent.Pre`,
  marks the player's `GCPlayerStats` oxygen-setup as valid and unconditionally cancels the
  event — GC never suffocates the player while Stellurgy governs the atmosphere. Registered only
  when `galacticraftcore` is loaded AND config `overrideGCAir` is true.
  `integration/GalacticCraftHandler.java:16-26`, `Stellurgy.java:1149-1154`.
- **MECH-MODC-04 — GC overlay suppression (client)** [V]. `@SideOnly(CLIENT)` render-tick
  handler that each frame forces `GCPlayerStatsClient` oxygen-setup-valid, hiding GC's
  "invalid oxygen setup" HUD warning. Extra-registered on the FML client bus only.
  `integration/GalacticCraftHandler.java:28-37`, `Stellurgy.java:1152-1153`.
- **MECH-MODC-05 — Sponge presence flag** [V]. preInit sets
  `CompatibilityMgr.isSpongeInstalled = Loader.isModLoaded("sponge")`. The only live
  consumer is commented out (`WorldProviderPlanet.java:272`) — write with no active read.
  `Stellurgy.java:1155`.

## State & persistence

None. This subsystem holds no NBT and no persisted state. `CompatibilityMgr` exposes three
`public static boolean` in-memory flags (`gregtechLoaded`, `thermalExpansionLoaded`,
`isSpongeInstalled`); a singleton `Stellurgy.compat` is constructed but only the
static flags are ever touched. No registry names, packets, or mixins are owned (all
seam-*.tsv greps for these files returned empty).

## Invariants

- **INV-MODC-01** [V] Every foreign-mod entry point is reached only behind a
  `Loader.isModLoaded` guard at the caller, so absent mods never trigger classloading of
  MO/GC types. `AtmosphereNeedsSuit.java:33`, `StellurgyConfiguration.java:785`,
  `Stellurgy.java:1149`.
- **INV-MODC-02** [V] GC suffocation is cancelled for ALL entities passed to
  `GCSuffocationEvent`, not just players — the `setCanceled(true)` is outside the
  `instanceof EntityPlayer` block; only the stats mutation is player-gated.
  `GalacticCraftHandler.java:19-25`.
- **INV-MODC-03** [V] GC handlers are inert unless BOTH `galacticraftcore` present and
  `overrideGCAir==true`; disabling the config flag fully removes the behaviour (no
  registration path). `Stellurgy.java:1149-1154`.
- **INV-MODC-04** [V] `CompatibilityMgr.gregtechLoaded`/`thermalExpansionLoaded` are never
  read anywhere in `src/`; they are set false in the ctor and only updated by
  `getLoadedMods()`, which has no caller. `CompatibilityMgr.java:8-20` (grep: 0 readers).
- **INV-MODC-05** [A] MO android immunity is intended to be an early short-circuit before
  the armour checks; inferred from ordering, no test pins the MO branch (no `matteroverdrive`
  on the test classpath). `AtmosphereNeedsSuit.java:33-35`.

## Failure modes & edge cases

- Stale mod IDs: `getLoadedMods` probes `"ThermalExpansion"` and `"gregtech_addon"`
  (1.7-era IDs) — dead code, but would silently mis-detect if ever revived. `CompatibilityMgr.java:18-19`.
- `MatterOvedriveIntegration` chains three capability calls without null-guarding between
  the `GetAndroidCapability` result and the `.isUnlocked` call; the leading null check
  covers it, so `&&` short-circuits safely. `MatterOvedriveIntegration.java:13-15`.
- `GalacticCraftHandler` is a common-side object carrying a `@SideOnly(CLIENT)` method that
  references the client-only `GCPlayerStatsClient`; safe because that method is only bus-
  registered on the client and `RenderTickEvent` never fires server-side. `GalacticCraftHandler.java:28-37`.

## Integration seams

- Forge events (C5): subscribes `GCCoreOxygenSuffocationEvent.Pre` (foreign, GC) and
  `TickEvent.RenderTickEvent`. No Stellurgy event is published.
- Cross-subsystem: mutates `StellurgyConfiguration.bypassEntity` (api-public) and reads
  `StellurgyConfiguration.overrideGCAir`; called by `AtmosphereNeedsSuit` (atmosphere-oxygen) and
  by `Stellurgy` preInit (misc-oddities).
- No packets, no NBT, no registry, no mixins/AT.

## Config surface

Config: see `C4-config-surface` (`overrideGCAir` false ⇒ the GC handler is never registered, MECH-MODC-03/04). Not in C4:
`bypassEntity` — the list is the consequence surface (MECH-MODC-02); MO entities are added only when the mod is present.

## Test coverage

No direct tests — `matteroverdrive`/`galacticraftcore` are not on the test classpath.
No test pins `bypassEntity` being empty in the default (no-mod) build. INV-MODC-01 is `[V]` at source; the bypass list is
filled at `StellurgyConfiguration.java:901,924`.

## Open questions

- Whether `CompatibilityMgr` should be deleted outright (it looks vestigial —
  only `isSpongeInstalled` has a live writer and even it has no live
  reader).
