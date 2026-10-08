# util-core / Misc helpers

Parent: [00-overview.md](./00-overview.md) · Files: `ItemAirUtils.java` (177),
`RocketInventoryHelper.java` (92), `RocketGuiNavigation.java` (167),
`RecipeHandler.java` (228), `InventoryUtil.java` (47), `GraphicsHelper.java` (102),
`AudioRegistry.java` (38), `IDataInventory.java` (20), `IBreakable.java` (8),
`IBrokenPartBlock.java` (13), `ITilePlanetSystemSelectable.java` (15)

The remaining small helpers that don't cluster with a bigger mechanic: suit-air NBT
accessors, the rocket-inventory distance-bypass bookkeeping (backing a mixin), machine
recipe registration, generic inventory scans, client draw helpers, sound registration,
and a few marker interfaces.

## Key types

| type | role |
|------|------|
| `ItemAirUtils` (singleton, `IFillableArmor`) | get/set/inc/dec the `air` int on a suit ItemStack; `ItemAirWrapper` adds `IProtectiveArmor` |
| `RocketInventoryHelper` (static) | weak-ref set of players allowed to bypass container distance checks; SSOT for the mixin redirect |
| `RocketGuiNavigation` (static) | server-side "return to rocket GUI" context (TTL 60 s) + back-button module injection |
| `RecipeHandler` | registers multiblock machine classes and auto-generates ore→product recipes |
| `InventoryUtil` (static) | has/add item across `IInventory`s by unlocalized-name substring |
| `GraphicsHelper` (static, client) | scaled/centered string + textured/colored quad draw + GUI glScissor |
| `AudioRegistry` | declares 15 `SoundEvent`s + a registration handler |
| `IDataInventory` / `IBreakable` / `IBrokenPartBlock` / `ITilePlanetSystemSelectable` | marker/contract interfaces |

## Mechanics

- **MECH-HLP-01 — suit-air accessors.** `getAirRemaining`/`setAirRemaining`/`increment`/
  `decrementAir` read-modify-write the `air` integer on the ItemStack tag; `increment`
  clamps at `getMaxAir` = `spaceSuitOxygenTime × 1200`, `decrementAir` clamps at 0,
  `setAirRemaining` does **not** clamp (documented). A tag-less stack is lazily given an
  `air=0` tag. `ItemAirUtils.java:27-115`.
- **MECH-HLP-02 — air-container validity.** `isStackValidAirContainer` returns true only if
  the stack carries the `enchantmentSpaceProtection` enchant; `ItemAirWrapper.protectsFromSubstance`
  spends 1 air per protective tick on a chest-slot armor. `ItemAirUtils.java:117-174`.
- **MECH-HLP-03 — inventory-check bypass (mixin SSOT).** `shouldAllowContainerInteract`
  returns true (skip vanilla `canInteractWith`) when the player is in the weak-ref bypass
  set, else delegates to the container; `allowAccess` evicts a player who has moved >3
  blocks and >10 ticks since last rocket interaction. Weak refs prevent leaks on
  logout/death. `RocketInventoryHelper.java:38-91`.
- **MECH-HLP-04 — rocket-GUI return context.** `rememberIfRocketGuiReturnTile` (server-only,
  `isRemote` guarded) records a `ReturnContext{rocket dim/id, source-tile dim/pos,
  expiry}` when a player opens a guidance-computer/satellite-hatch GUI from a rocket;
  `openRocketGuiFromReturnContext` re-opens the rocket GUI if the context is unexpired,
  matches the source tile, same dim, rocket alive with storage, within 64 blocks.
  `RocketGuiNavigation.java:46-139`.
- **MECH-HLP-05 — recipe registration.** `RecipeHandler` registers machine classes into
  `RecipesMachine`, loads XML recipes for 11 machine types, and `createAutoGennedRecipes`
  derives smelting/plate/stick/gear/… recipes from every `MaterialRegistry` material plus
  optional vanilla-wood sawmill and cross-mod plate/stick recipes.
  `RecipeHandler.java:27-227`.
- **MECH-HLP-06 — inventory scans.** `InventoryUtil.hasItemInInventory(inv,substr,consume)`
  matches by lowercase unlocalized-name substring, optionally clearing the slot; add-item
  fills the first empty slot. `InventoryUtil.java:8-46`.
- **MECH-HLP-07 — sound registration.** `AudioRegistry` declares 15 `SoundEvent`s with
  `stellurgy:` registry names. `AudioRegistry.java:10-37`.

## State & persistence

`ItemAirUtils` reads/writes the `air` int on item NBT (C1). `RocketInventoryHelper` holds
static weak-ref/weak-map bypass state; `RocketGuiNavigation` holds a static
`Map<UUID,ReturnContext>` (TTL-expired, server-only). Everything else is stateless.

## Invariants

- **INV-HLP-01 [V][BEH]** Air set→get round-trips; `decrementAir` clamps at 0 and reports the
  amount actually extracted; `increment` clamps at max and reports amount inserted;
  `setAirRemaining` does not clamp; a fresh stack reads as full (`ItemAirUtils.java:29-130`).
  No unit test drives these; the suit client scenarios reach only the drained case of `decrementAir`.
- **INV-HLP-02 [A][BEH]** A bypass player skips `canInteractWith` regardless of distance. A
  non-bypass player delegates to the container, and bypass is scoped to the specific player
  instance and restored on removal `[V]` (`RocketInventoryHelper.java:22,50-53,65-70`); no test
  drives the delegation or the removal. Pinned by `MachineGuiClientGroupTest#mixinRedirectKeepsContainerOpenAcrossDistance`.
- **INV-HLP-03 [V]** `rememberIfRocketGuiReturnTile` is a no-op on the client / null world
  (`world.isRemote` guarded). `RocketGuiNavigation.java:48`.
- **INV-HLP-04 [V][BEH]** Air-container validity requires the space-protection enchant, not just
  the item type. `ItemAirUtils.java:117-131`.
- **INV-HLP-05 [A][BEH]** Suit-armor protective ticks are pinned by
  `test/client/ItemSpaceArmorUseFluidE2ETest.java` / `ItemSpaceChestSubInventoryDrainE2ETest.java`
  (chest-slot air spend). Assumed to match MECH-HLP-02.

## Failure modes & edge cases

- `AudioRegistry.RegistrationHandler.registerSoundEvents` calls
  `registerAll(electricShockSmall)` — only **1 of the 15** declared `SoundEvent`s is
  registered; the other 14 (`laserDrill`, `airHissLoop`, `railgunFire`, `machineLarge`,
  `combustionRocket`, …) are never added to the registry and will fail to play / warn as
  unregistered. `AudioRegistry.java:34-36`.
- `InventoryUtil.hasItemInInventory` calls `getUnlocalizedName()` on an
  empty slot (`ItemStack.EMPTY` → `"tile.air"`) with no `isEmpty()` guard, so a search for
  a substring of `"tile.air"` (e.g. `"air"`) false-positives on empty slots and, with
  `consume=true`, "consumes" an already-empty slot. `InventoryUtil.java:19`.
- `RecipeHandler.registerMachine` and the auto-gen loop mutate the global
  `RecipesMachine` singleton; ordering vs other mods' recipe registration is load-order
  sensitive.

## Integration seams

- Mixin SSOT: `RocketInventoryHelper.shouldAllowContainerInteract` is the redirect target
  of `MixinEntityPlayer(MP)InventoryAccess` (C6, mixins-asm-coremod).
- Registry names: `AudioRegistry` sound events (C3); recipe registry names
  `stellurgy:unpacknugget*`, `packblock*`, `stick*`, `coil*`, `fan*`, `gear*`, …
  (C3, `RecipeHandler.java:76-149`).
- Client-only: `GraphicsHelper` imports `net.minecraft.client.*` — must not be referenced
  from common code.

## Config surface

Config: see `C4-config-surface`.

## Test coverage

`ItemAirUtilsTest`, `RocketInventoryHelperRedirectTest` (+E2E), several
`ItemSpace*E2ETest` for suit air. `AudioRegistry`, `RecipeHandler`, `InventoryUtil`,
`GraphicsHelper` are untested.

## Open questions

- The `AudioRegistry` single-registration looks like a real bug but no test asserts
  all 15 sounds register — needs confirmation against actual sound playback.
