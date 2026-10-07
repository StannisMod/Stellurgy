---
id: C7-lang-assets
covers: [en_US.lang, ru_RU.lang, client-render, items, blocks, infrastructure-tiles, atmosphere-oxygen, integration-jei, integration-probe-waila, rocket-entity, misc-oddities]
confidence: high
---

# C7 — Lang / asset keys

The translation contract lives in `src/main/resources/assets/stellurgy/lang/`. `en_US.lang`
is the **authoritative surface** (about twenty key namespaces). Minecraft's
`I18n` falls back to `en_US` for any key absent from the active locale, so **a key missing from
`en_US` shows as its raw string to every player** — that is the only hard-break class here; a key
missing from `ru_RU` merely renders in English.

Row counts are never stored in this document: a count is an inventory of one row and goes stale
without any gate noticing. Count with `grep -c '^<namespace>\.' en_US.lang` when a number is
needed. The gradle task `checkLangKeys` fails a string-literal key that `en_US` does not define (what
it reads is stated under "Console + sensor readouts").

Keys that carry a mechanic of their own are named where that mechanic is described. Three examples
of why a key is not interchangeable with a similar one:
`msg.observetory.upload.result` takes two `%s` (landed and total), because an upload that skips
every address of a world nobody has landed on must say so or it cannot be told from a dead button;
`msg.observetory.scan.obscured` tells an operator the dust took his detail, without which
concealment is indistinguishable from a broken instrument; and `msg.shiptransit.directfailed`
exists because a short jump is performed as one crossing, and a crossing that abandons its settle
leaves the ship placed but not cleanly — a different sentence from the hyperspace path's "it stays
in transit and will arrive", which would tell the crew a flight is continuing when there is none.

Nine locales ship (`de_DE`, `en_US`, `es_ES`, `fi_FI`, `fr_FR`, `pt_br`, `ru_RU`, `ua_UA`,
`zh_CN`); this contract audits **en_US ↔ ru_RU**.

## How names bind to keys

- **Blocks** — `setUnlocalizedName("x")` ⇒ lang key `tile.x.name`; metadata subtypes append the
  meta index (`tile.x.<meta>.name`, e.g. `item.circuitIC.0.name`). Registry id (C3) and unlocalized
  name are **independent** strings and frequently differ (C3 §R8): the *unlocalized* name drives the
  lang key, the *registry* name drives the model/id. Registration site is uniformly
  `Stellurgy.java` (misc-oddities), the same file C3 owns.
- **Items** — `setUnlocalizedName("x")` ⇒ `item.x.name` (or `item.x.<meta>.name`).
- **Modid-baked unlocalized names [V].** The ingredient items call
  `setUnlocalizedName("stellurgy:wafer"|"…:circuitIC"|"…:circuitplate"|"…:dataUnit"|
  "…:itemUpgrade"|"…:lens"|"…:miscpart"|"…:pressureTank"|"…:satellitePowerSource"|
  "…:satellitePrimaryFunction"|"…:sawBlade")`. Yet **no key of those carries a colon** in `en_US`; the
  keys are `item.wafer.0.name` … `item.sawBlade.0.name`. These items extend the vendored libVulpes
  `ItemIngredient` (`ItemPressureTank`, `ItemUpgrade`, `ItemData`), which strips the modid prefix before
  the lang lookup. So display works — but the lang key is coupled to that stripping behaviour, not to the
  literal in this repo (C3 names it "a C7 lang oddity"). §R5. The two items that bake the modid and do
  NOT extend `ItemIngredient` (`item.stellurgy:repairWelder.name`, `item.stellurgy:memoryCrystal.name`)
  carry the colon in their lang rows, spelled exactly so.
- **Enum-derived keys [V].** `BlockCrystal.getUnlocalizedName(int)` returns `"tile."+EnumCrystal[meta]`
  and `EnumCrystal.toString()` returns the lowercase `name` field (`BlockCrystal.java`),
  so the six crystals bind `tile.amethyst.name`…`tile.wulfentite.name` (all present) — **not** the base
  `tile.crystal.name` (absent, and correctly so). Same enum-as-key idiom as `INV-API-21`
  (`DataType.toString()` ⇒ `data.<name>.name`).
- **Doors bind the ITEM name [V].** `blockAirLock` unlocalized `smallAirlockDoor` but the door renders
  via its `ItemDoor` unlocalized `smallAirlock` ⇒ `item.smallAirlock.name` (present,
  `Stellurgy.java`); `tile.smallAirlockDoor.name` is unused.
- **Container / GUI titles** — a tile's `getName()` / `getModularInventoryName()` returns a **full lang
  key string** that the LibVulpes modular GUI localizes (e.g. `TileRocketServiceStation.java`,
  `TileStationAssembler.java`). These are *not* derived from the block key and are the main source
  of the missing-key bugs below (§R6).
- **Tooltips** — `TooltipInjector` (client-render, `MECH-CLR-16`) maps a registry id → base tooltip key
  via `KEY_BY_ID`/`KEY_BY_SUFFIX`, then expands `base` + `.0..N`, `.shift.1..8`, `.alt.1..8`, **each line
  guarded by `I18n.hasKey`** (`TooltipInjector.java`). Missing tooltip lines therefore degrade
  silently — never a raw-key leak. Simple items delegate here (`MECH-ITM-02`, `items.md:25,67`).

## Consolidated namespace table (en_US authoritative)

Counts are intentionally absent (see the opening paragraph). `ru_RU` is a partial translation:
`tooltip` and `stellurgy` have no ru rows, `commands`, `key`, `error`, `death`, `entity`, `warning`
and `jei` are mostly English-only, `advancement` is fully translated. Because en is the fallback
source, an untranslated ru row is a visible-English row, not a break.

| namespace | owner(s) / binding | notes |
|-----------|--------------------|-------|
| tooltip | client-render `TooltipInjector` (MECH-CLR-16) | guarded by `hasKey`; ru wholly untranslated → English tooltips |
| msg | chat/HUD/GUI via `LibVulpes.proxy.getLocalizedString` & `I18n` | Includes Free-Flight HUD `msg.ff.*` (input-hud.md), `msg.highOxygen`, the jump gate's thermal refusal `msg.jumpgate.driveoverheated` (both locales, C12 HEAT-11), and the player-facing world-model notice `msg.stellurgy.universe.alpha` — chat, so it lives here rather than under `commands` |
| tile | blocks / infrastructure-tiles / mb / stn (bind = `tile.<unloc>.name`) | id≠unloc common (C3 §R8). Includes the ship-heat blocks (`tile.heatPipe.name`, `tile.heatAccumulator.name`, `tile.heatRadiator.name`, `tile.heatChiller.name`, `tile.heatIntakeDuct.name`) and the life-support blocks (recirculator, separator, plant, ventilation duct, jettison port), in both locales |
| commands | command subsystem `commands.stellurgy.*` | `reloadrecipes.error`+i ⇒ `.error1..3`; `universe.*` carries the world-model status / upgrade read-out. The refusal text a player meets is NOT here: it is the exception message in `UniverseSchemaMismatchException`, thrown before a world (and therefore a language) exists |
| item | items / atmosphere-oxygen (`item.<unloc>.name`) | meta subtypes `.N.name` |
| advancement | advancements subsystem | fully translated |
| jei | integration-jei (MECH-JEI-*) | recipe/gasgiant category labels |
| key | client-render key bindings (input-hud.md) | keybinding display names |
| error | GUI/command error feedback | `error.rocket.notSameSystem` ru-only (stale, §R4) |
| data | api-public `DataType` (`data.<name>.name`, INV-API-21) | enum-derived |
| death | atmosphere-oxygen damage sources | `death.attack.LowOxygen*` |
| entity | rocket-entity / UI entities (C3) | `entity.stellurgy.rocket.name` is lowercase `rocket`; `I18n` is case-sensitive |
| stellurgy | side-selector direction labels (`stellurgy.sideselector.*`) | ru untranslated |
| fluid | api-public fluids (`fluid.<id>`) | `carbon_dioxide` is snake_case = GT's id (C3); enrichedLava is the ru gap |
| container | GUI titles (`container.*`) | **ru has MORE than en** — §R4 |
| material | material registry | `material.Dilithium.name` ru-only stale (§R4) |
| mission / itemGroup / enchantment | misc | fully translated |
| warning | GUI warning | untranslated |

## Collisions & risks

- **R1, R2, R3, R7** — retired (the numbers stay burned).
- **R4 — `ru_RU` keys with no `en_US` referent [V].** ru defines keys absent from en; because en is
  the fallback source, these ru rows are dead weight. No code referent (grep over `src/main/java`):
  `tile.controlComp.name`, `tile.dataPipe.name`, `tile.energyPipe.name`, `tile.liquidPipe.name`,
  `error.rocket.notSameSystem`, `container.crystallizer`, `container.cuttingmachine`,
  `container.observatory`, `container.precisionassemblingmachine`, `container.satelliteMonitor`,
  `material.Dilithium.name`, `tile.Dilithium.name`, `tile.coil.dilithium.name`, `item.battery.0.name`,
  `item.battery.1.name`, `msg.atmosphere.higho2` — translations for blocks or messages the code does not
  use (the five `container.*` machine titles are served by `TileMultiblockMachine.getMachineName()`,
  not `container.*`). LOW.
- **R5 — modid-baked unlocalized names depend on LibVulpes stripping [V].** The
  `setUnlocalizedName("stellurgy:…")` ingredients (see "How names bind") resolve to colon-free keys
  (`item.wafer.0.name`, …) only because `ItemIngredient` strips the modid. An item that bakes the modid
  but does **not** extend `ItemIngredient` resolves to `item.stellurgy:x.name`, and its lang row must
  spell the colon (`item.stellurgy:repairWelder.name`, `item.stellurgy:memoryCrystal.name` do). Fragile
  convention, no current breakage. LOW.
- **R6 — a GUI-title `getName()` key missing from lang (raw key in the title bar) [V].**
  `TileStationAssembler.getName()` returns `"tile.stationBuilder.name"` (`TileStationAssembler.java:280`)
  — absent from en/ru (the block's own name `tile.stationAssembler.name` *is* present, but the container
  title uses the divergent `stationBuilder` string). MED.
- **R8 — JEI copy-strip set built from non-existent keys (feature no-ops) [V].**
  `SatelliteBuilderWrapper.copyStripStrings()` seeds a strip-set through `I18n.format` with
  `msg.itemchip.unprogrammed`, `msg.satelliteidchip.unprogrammed`, `msg.planetidchip.unprogrammed`,
  `msg.stationchip.unprogrammed`, `msg.orescanner.unprogrammed` and `msg.itemsatellite.empty`. Only the
  last exists in en (with the generic `msg.unprogrammed`); `I18n.format` returns the key unchanged, so the
  set stores raw key strings that never match the displayed "Unprogrammed" text — the intended strip
  silently fails for those chips. Not a visible raw-key leak, a latent functional gap. LOW.
- **R9 — `ru_RU` is a partial translation [V].** The entire `tooltip` namespace and most of
  `commands` / `key` are English-only in a Russian client. Not a break (en fallback) but the largest
  divergence surface; translation debt, not a contract defect. LOW.


## Weapons

| lang key | asset files (LOWERCASE registry name) |
|---|---|
| `tile.turret.name` | `blockstates/turret.json`, `recipes/turret.json` |
| `tile.gunBarrel.name` | `blockstates/gunbarrel.json`, `recipes/gunbarrel.json` |
| `tile.gunAmmoFeed.name` | `blockstates/gunammofeed.json`, `recipes/gunammofeed.json` |
| `tile.gunCooling.name` | `blockstates/guncooling.json`, `recipes/guncooling.json` |
| `tile.weaponConsole.name`, `msg.weaponConsole.holdFire`, `msg.weaponConsole.clearTarget` | `blockstates/weaponconsole.json`, `recipes/weaponconsole.json` |

`en_US` only; the other locales fall back. The four blockstates reuse existing Stellurgy textures
(`machineorientationcontrol`, `railgun`, `intake`, `machinevent`) rather than shipping new PNGs.

## Fire-control sensor

| lang key | asset files (LOWERCASE registry name) |
|---|---|
| `tile.fireControlSensor.name`, `msg.fireControlSensor.mode` | `blockstates/firecontrolsensor.json`, `recipes/firecontrolsensor.json` |

The blockstate is the console's own `minecraft:orientable` with `atmospheredetector` as its face, so
nothing new ships and the block still reads as an instrument.

## Beam emitter, armour, welder

| lang key | asset files (LOWERCASE registry name) |
|---|---|
| `tile.gunBeamEmitter.name` | `blockstates/gunbeamemitter.json`, `recipes/gunbeamemitter.json` |
| `tile.mirrorPlatingAluminium.name` | `blockstates/mirrorplatingaluminium.json` |
| `tile.mirrorPlatingSilver.name` | `blockstates/mirrorplatingsilver.json` |
| `tile.mirrorPlatingGold.name` | `blockstates/mirrorplatinggold.json` |
| `tile.reactivePlate.name` | `blockstates/reactiveplate.json` |
| `tile.reactiveBlock.name` | `blockstates/reactiveblock.json` |
| `item.stellurgy:repairWelder.name`, `msg.welder.repaired`, `msg.welder.undamaged`, `msg.welder.nocost`, `msg.welder.nomaterials`, `msg.welder.nocharge` | `models/item/repairwelder.json` |

**The LOWERCASE in this table's header is the whole rule.** An asset file named in the case a
registry name is DECLARED in (`mirrorPlatingAluminium.json`) is not found: a lookup lowercases,
loose files on a case-insensitive dev filesystem answer anyway, and a jar's entry names do not — so
the dev client works and the shipped jar has no model. Pinned mechanically by
`test/unit/ModelAssetsAreAddressableTest`, which also fails a registered block that has no
blockstate at all.

`en_US` only; the other locales fall back. The armour blockstates carry Stellurgy's existing plating models
and the emitter reuses `blocks/lens1`.

## Console + sensor readouts

Every readout line of the weapon console and the sensor GUI ("Network: balanced",
"Guns: 3  on target: 1", "Lock: 0.82 at 41m") is a lang key, like the buttons beside them.

| lang keys | where |
|---|---|
| `msg.weaponConsole.status.{noNetwork,disconnected,powerLimited,idle,cableLimited,balanced,unknown}` | `TileWeaponConsole#networkStatusKey` |
| `msg.weaponConsole.line.{network,networkHolding,guns,gunsOutOfArc,targetNone,target,sensorNone,sensor,sensorTooPoor}` | `TileWeaponConsole` readout lines |
| `msg.fireControlSensor.mode.{passive,active}`, `msg.fireControlSensor.line.{mode,modeUnderpowered,contacts,lockNone,lock,lockTooPoor}` | `TileFireControlSensor` readout lines |

**A whole sentence per key, with its placeholders in it** — never a label concatenated with a value.
Word order is not the same in every language, so a line assembled from fragments can only ever come
out in English order however many of its fragments are translated. That is why there are two keys for
a line that has an optional clause (`network` / `networkHolding`) rather than one key plus a
translated suffix.

The key check is the gradle task `checkLangKeys` (`build.gradle`, run by `testUnit` and `test`),
which reads string literals passed to `getLocalizedString`, `I18n.format`, `translateToLocal`,
`new TextComponentTranslation`, `tr` and `messagePilot`; a key built by CONCATENATION, or passed
through any other wrapper, is invisible to it.

`en_US` only; the other locales fall back, as with every weapons key.

**`key.designateShip`** — the display name of the helm's `T` binding,
"Designate the ship ahead (tier-2 ship)" (`assets/stellurgy/lang/en_US.lang`), registered in
`KeyBindings` under the `PILOTING` conflict context (`client-render/input-hud` MECH-CLR-34).

## Deck hold give-up

| lang keys | where |
|---|---|
| `msg.deckhold.lost` | `DeckHold` give-up — the held-for ship never came within the hold window (`integration/vs/DeckHold.java`) `[V]` |
| `msg.deckhold.shipgone` | `DeckHold` release on `ShipLifecycleEvent.ShipUnnamed` with cause `DESTROYED` — the ship was destroyed under the held player (`DeckHold.java`) `[V]` |

`en_US` only.

## Ship flight model

| lang keys | where |
|---|---|
| `tile.reactionWheel.name` | the reaction wheel block |
| `msg.ship.readout.*`, `msg.ship.dir.*` (twelve), `msg.ship.warn.{no_authority,burst_only,cannot_hover}` | the flight computer's console (`client/ShipReadoutText#console`) `[V]` |
| `msg.ship.hud.{mass,accel,saturated,wheel}` | the pilot's HUD flight slice (`ShipReadoutText#hud`, appended in `KeyBindings.freeFlightHudLines`) `[V]` |
| `msg.rocketbuilder.shiptwr`, `msg.rocketbuilder.lowtwr.gui`, `msg.rocketbuilder.lowtwr` | the assembler's tier-2 readout and the TWR < 1 warning (GUI line and chat) `[V]` |

`en_US` and `ru_RU`.
