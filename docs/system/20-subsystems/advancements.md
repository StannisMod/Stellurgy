---
id: advancements
owns: [advancements/]
entrypoints: [StellurgyAdvancements#register, CustomTrigger#trigger]
depends-on: []
depended-by: [rocket-entity, event-handlers, blocks, space-stations, commands-gameplay]
contracts: [C3, C7]
confidence: high
---

## Purpose

Defines the mod's custom advancement criteria triggers ("challenge unlocks"). Each trigger
is a server-side `ICriterionTrigger` that gameplay code fires imperatively (e.g. landing on
Luna, warping a station) to grant the matching advancement. The subsystem provides the eight
trigger singletons, registers them into vanilla's `CriteriaTriggers` registry via reflection,
and implements the listener bookkeeping that vanilla advancement JSON binds to.

## Responsibility boundary

Owns: the trigger-type registry (`StellurgyAdvancements`), the generic trigger implementation
(`CustomTrigger` + its `Instance`/`Listeners`), and the trigger→advancement contract with the
JSON assets under `assets/stellurgy/advancements/`.

Does NOT own: the *decision* to fire a trigger — that lives entirely in the callers
(`EntityRocket`, `PlanetEventHandler`, `BlockAtmosphereTerraformer`, `BlockOrbitalLaserDrill`,
`TileWarpController`, `TestProbeCommand`). Does NOT own advancement display/reward logic
(vanilla, driven by the JSON). Does NOT own the lang keys the JSON references (`C7`).

## Key types

| class | role |
|-------|------|
| `StellurgyAdvancements` | Holds the 8 `CustomTrigger` singletons + `TRIGGER_ARRAY`; `register()` installs them into vanilla `CriteriaTriggers` by reflection. |
| `CustomTrigger` | Generic `ICriterionTrigger` — one `ResourceLocation` id, per-`PlayerAdvancements` listener map, imperative `trigger(player)`. |
| `CustomTrigger.Instance` | `AbstractCriterionInstance` whose `test()` is unconditionally `true` — a fire-and-grant criterion. |
| `CustomTrigger.Listeners` | Inner holder: `Set<Listener>` for one player's advancements; `trigger()` grants every listener whose instance passes `test()`. |

## Mechanics

- **MECH-ADV-01 — reflective registration.** `StellurgyAdvancements.register()` reflects
  `CriteriaTriggers.register` (SRG `func_192118_a`) and invokes it for each of `TRIGGER_ARRAY`'s
  8 entries. Called once at init from `Stellurgy#register`. `StellurgyAdvancements.java:33-44`;
  call site `Stellurgy.java:985`.
- **MECH-ADV-02 — imperative fire.** A caller invokes `TRIGGER.trigger(EntityPlayerMP)`; the
  trigger looks up that player's `Listeners` and grants every listener whose `Instance.test()`
  returns true. `CustomTrigger.java:99-105`, `171-188`.
- **MECH-ADV-03 — listener lifecycle.** Vanilla `PlayerAdvancements` registers/unregisters
  criterion listeners per loaded advancement; `addListener`/`removeListener`/`removeAllListeners`
  maintain the `Map<PlayerAdvancements,Listeners>`, pruning empty entries.
  `CustomTrigger.java:45-79`.
- **MECH-ADV-04 — instance deserialization.** `deserializeInstance` builds a
  `CustomTrigger.Instance` from the trigger id, ignoring the JSON body and context — no
  conditions are ever read from the advancement file. `CustomTrigger.java:88-92`, `107-124`.
- **MECH-ADV-05 — trigger→advancement binding.** Each advancement JSON under
  `assets/stellurgy/advancements/normal/*.json` declares a criterion with a bare
  `"trigger": "<id>"` matching a `CustomTrigger` id. Verified: `moonlanding.json:14-17`.

### Trigger catalogue (id ↔ constant ↔ fire site)

| constant | id (contractual) | fired by |
|----------|------------------|----------|
| `MOON_LANDING` | `moonlanding` | `EntityRocket.java:2478` (land on "Luna") |
| `ONE_SMALL_STEP` | `onesmallstep` | `EntityRocket.java:2480` (first Luna landing) |
| `BEER` | `beer` | `PlanetEventHandler.java:290` |
| `WENT_TO_THE_MOON` | `wenttothemoon` | `PlanetEventHandler.java:199`; also `TestProbeCommand.java:11399` |
| `ALL_SHE_GOT` | `givingitallshesgot` | `TileWarpController.java:500` |
| `FLIGHT_OF_PHOENIX` | `flightofpheonix` (note: misspelled "phoenix") | `TileWarpController.java:502` |
| `ATM_TERRAFORMER` | `pressurize` | `BlockAtmosphereTerraformer.java:29` |
| `DEATH_STAR` | `deathstar` | `BlockOrbitalLaserDrill.java:53` |

## State & persistence

No NBT, no config. Runtime state is the transient `Map<PlayerAdvancements,Listeners>` per
trigger (`CustomTrigger.java:19`) — rebuilt from vanilla advancement (re)loading, never
persisted by this subsystem. Advancement completion itself is persisted by vanilla.

## Invariants

- **INV-ADV-01 [V]** All 8 trigger singletons are present in `TRIGGER_ARRAY`, hence all get
  registered (`StellurgyAdvancements.java:21-30`). No trigger is fired without being registered.
- **INV-ADV-02 [V]** Trigger ids are constructed with `new ResourceLocation(string)` with no
  explicit namespace, so they resolve into the **`minecraft:`** namespace, not
  `stellurgy:` (`CustomTrigger.java:23`). The advancement JSON uses the same bare form
  (`moonlanding.json:16`), so both sides agree.
- **INV-ADV-03 [V]** `Instance.test()` is unconditionally true (`CustomTrigger.java:121-123`);
  combined with MECH-ADV-04 this means firing a trigger always grants the criterion — advancement
  JSON conditions on these triggers are inert.
- **INV-ADV-04 [V]** `trigger()` requires an `EntityPlayerMP`; all fire sites cast the player to
  `EntityPlayerMP` (server-only type). Firing from a client entity would `ClassCastException`.
- **INV-ADV-05 [A]** Registration is idempotent-once: `register()` is called exactly once at
  init; a second call would re-register duplicates into vanilla `CriteriaTriggers`. Inferred from
  the single call site (`Stellurgy.java:985`); not guarded in code.

## Failure modes & edge cases

- **Silent registration failure.** `register()` wraps the whole reflection loop in `try{}catch(Exception e){}`
  with an **empty catch** (`StellurgyAdvancements.java:41-43`). If the SRG name `func_192118_a` ever fails
  to resolve (mapping drift) the triggers register nothing and every advancement in this subsystem
  silently never fires — no log, no crash. .
- **Namespace pollution.** Triggers land under `minecraft:` (INV-ADV-02); a vanilla/other-mod
  trigger of the same bare id would collide.
- **Dead constructor.** `CustomTrigger(ResourceLocation)` (`CustomTrigger.java:26-29`) is never
  called.

## Integration seams

- **Registry (C3):** injects into vanilla `net.minecraft.advancements.CriteriaTriggers` via
  reflection (not a Forge `IForgeRegistry`) — this is why the 8 ids do not appear in
  `coverage/seam-registry.tsv`. Ids are the contract in the table above.
- **Assets (C7):** 8 advancement JSON files (`assets/stellurgy/advancements/normal/`) whose
  criterion `trigger` strings must equal the trigger ids; titles/descriptions reference
  `advancement.*` lang keys.
- **No packets, no capabilities, no mixins, no config.**

## Config surface

None. There is no flag that disables triggers; disabling is only possible by removing the
advancement JSON assets.

## Test coverage

None found — no test in `src/test/java` references `StellurgyAdvancements`, `CustomTrigger`, or `.trigger(`.
All invariants are `[V]` (code-read) or `[A]`.

## Open questions

- Is a `world.isRemote` / server-side guard guaranteed at each fire site (esp.
  `EntityRocket.java:2478`, which runs on both sides)? Guarding lives in caller subsystems
  (rocket-entity, blocks, space-stations), so INV-ADV-04's crash risk is unverified here. → candidate finding, owner rocket-entity.
