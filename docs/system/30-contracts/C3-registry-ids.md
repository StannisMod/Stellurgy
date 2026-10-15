---
id: C3-registry-ids
covers: [blocks, items, multiblock-machines, infrastructure-tiles, space-stations, atmosphere-oxygen, rocket-entity, rocket-assembly, dimension-planets, world-gen, api-public, misc-oddities]
confidence: high
---

# C3 — Registry names

The registry ids the mod mints. **The exhaustive list of ids is computed from the live tree by
`docs/system/tools/check-coverage.py` and is not copied here**: a hand-kept table of ~250 ids is an
inventory that decays in one direction (the source gains a member and nothing goes red). This contract
keeps what the source cannot say — where ids are minted, the naming rules, and the hazards.

**Where ids are minted.** Every id is minted in `Stellurgy.java` (subsystem `misc-oddities`): entities
via `EntityNetworkIds.register` (`Stellurgy.java:655-670`), tile entities via
`GameRegistry.registerTileEntity` in `preInit`, items in `registerItems`, blocks and fluid blocks in
`registerBlocks`, biomes in `registerBiomes`. The block / item / biome classes are owned by their
behavioural subsystem (blocks → `blocks`, items → `items`, …); the **id string** and its namespace are
owned here.

**Namespace rule [V].** No `setRegistryName(String)` / `setUnlocalizedName` call carries an explicit
modid; ids are auto-namespaced to `stellurgy:` by the active mod container during the `RegistryEvent`.
The only explicitly-namespaced id is the enchantment `stellurgy:spacebreathing`
(`Stellurgy.java:828`). So **no id is missing its namespace at runtime** — but several
`setUnlocalizedName("stellurgy:…")` calls bake the modid into the *translation key* instead, a C7 lang
oddity, not a C3 defect.

**Registry paths are lowercased by Forge** [V]: the item registered as `carbonDust`
(`Stellurgy.java:946`) is stored as `stellurgy:carbondust` (a server test asserting the camelCase form
failed). It is also registered in the ore dictionary as `dustCarbon` (`:950`) — an un-namespaced
registry joined ON PURPOSE so tech-mod carbon and ours unify.

**Fluid ids.** The fluid-block registry names (`oxygenFluid`, `hydrogenFluid`, `nitrogenFluid`,
`carbonDioxideFluid`, `rocketFuel`, `enrichedLavaFluid`) are separate from the underlying `Fluid` ids
(`oxygen`, `hydrogen`, `nitrogen`, `carbon_dioxide`, `rocketFuel`, `enrichedLava`). All suppress their
auto-ItemBlock. `carbon_dioxide` is snake_case on purpose: it is GregTechCEu's own material-fluid
registry name (`gregtechId("carbon_dioxide")`, `.gas()`; `prefixedRegistryName` returns the bare name for
a material's primary key), so the two mods unify into ONE `Fluid` by name, the way `oxygen` does.
Renaming it breaks that unify silently.

**Owner column of the checker's output** = the subsystem whose runtime object the id denotes; the
registration site is uniformly `misc-oddities`.

## Naming rules

- **Tile-entity ids carry a prefix, and the prefix is not uniform:** `Stellurgy…` for the older set and
  `AR…` for the rest; a tile id is the string in `registerTileEntity`, written as the tile's NBT `"id"`
  into every saved chunk and into packed rockets and stations (`StorageChunk`). A mismatch drops the
  tile silently on load: the block survives, its state does not. The wireless transceiver's tile id is
  `StellurgyTransciever` and its block registry name `wirelessTransciever` (`Stellurgy.java:759`,
  `:1263`) — both spelt `Transciever`; a rename is a save break that must also reach every packed
  `StorageChunk`.
- **Item id ≠ block id for the same object is intentional in places** (R3, R4).
- **Entity ids** are the `ResourceLocation` path passed to `EntityNetworkIds.register`; the legacy
  name string of the hover-craft entity is `hovercraft` (R9).
- A gun's PARTS carry no tile entity: the contribution is asked of the block, so a hundred-block gun adds
  a hundred registry entries of nothing and no tick load (`weapons` INV-GUN-04). The armour family
  (mirror plating by metal, reactive plate / block) is one registry entry per tier — the tiers differ by
  one number each, and a pack retunes a tier by naming it.

## Collisions & risks

- **R1 — config-gated ids drop out when the flag is off [V].** `biomeChanger` / `weatherController`
  items (gated on `enableTerraforming`, `Stellurgy.java:933`), `terraformer` (`enableTerraforming`,
  `:1291`), `gravityMachine` (`enableGravityController`, `:1293`), `spaceLaser` (`enableLaserDrill`,
  `:1295`) and `orbitalRegistry` (`enableOrbitalRegistry`, `:1349`) are registered only when their flag
  is on. With the flag off the id is never registered and any world with that block / item placed loses
  it (no remapper — R6). Reversible only by re-enabling the flag. **Not safely disableable.**
- **R2 — `rocketFuel` string reused across two registries [V].** Registered as a `Fluid`
  (`Stellurgy.java:1196`) *and* as the fluid-block registry id (`:1403`). Different Forge registries and
  the block suppresses its ItemBlock, so no hard collision — but the shared literal is a rename hazard.
  LOW.
- **R3 — item id ≠ block id for the same object (intentional) [V].** `lens` (item) vs `blockLens`
  (block); `sawBladeIron` (item) vs `sawBlade` (block); `smallAirlockDoor` (item) vs `airlock_door`
  (block). Recipes / JEI / pick-block must use the correct per-registry id. LOW.
- **R4 — `quartzcrucible` block has no matching ItemBlock; the item id is `iquartzcrucible` [V].** The
  block is registered with a suppressed ItemBlock (`Stellurgy.java:1246`, `null, false`) and a
  *separate* item is minted under `iquartzcrucible` (`:942`). Pick-block and any recipe referencing the
  crucible **item** must use `stellurgy:iquartzcrucible`, not the block id. MED.
- **R5 — `orbitalRegistry` in two registries [V].** Block id and TileEntity id share the string
  (`Stellurgy.java:765`, `:1350`). Separate registries; intentional (a block sharing its TE id). LOW.
- **R6 — no remap handler for any Stellurgy id [V].** The tree has exactly one
  `RegistryEvent.MissingMappings` handler, libVulpes' item remap of `libvulpes:productcrystal`
  (`libvulpes/LibVulpes.java:285`). Any Stellurgy id that is renamed, or that R1 disables, is silently
  dropped from old saves rather than remapped; `MissingMappings` does not cover `TileEntity.REGISTRY`
  either. The `backward-compat` subsystem does **not** cover this. MED.
- **R7 — string-form ids are easy to miss [V].** The tile-entity ids (`registerTileEntity(Class,
  "name")`), the `Fluid` ids and the enchant id `spacebreathing` are not `setRegistryName` calls; a scan
  for registry names alone misses them, and they are contract-relevant for save compatibility.
- **R8 — registry id vs unlocalized-name skew [V].** Several blocks carry an unlocalized name that
  differs from their registry id: engine `rocket` → `rocketmotor`, `chemreactor` → `chemicalReactor`
  (`Stellurgy.java:1016`), `scrubber` → `oxygenScrubber` (`:1146`), `atmosphereDetector` →
  `oxygenDetection` (`:1160`), `stationmonitor` → `warpMonitor` (`:1139`), `satelliteMonitor` →
  `satelliteControlCenter` (`:1133`), `pad` → `launchpad`, `dockingPad` → `landingPad` (`:1048`). The
  registry id is the save contract, so the skew breaks no save by itself. LOW.
- **R9 — `StellurgyHoverCraft` entity id is inconsistent with its siblings [V].** `Stellurgy`+PascalCase
  path with a divergent legacy name string `hovercraft` (`Stellurgy.java:670`), while `mountDummy` /
  `rocket` / `laserNode` / `deployedRocket` use camelCase without the prefix. Cosmetic. LOW.
