---
id: F7
title: Multiblock machine power + recipe cycle
subsystems: [multiblock-machines, recipe, inventory-containers, integration-dataloaders, network-wire]
anchors: [MECH-MBM-01, MECH-MBM-12, MECH-MBM-13, MECH-MBM-14, MECH-MBM-15, MECH-RCP-01, MECH-RCP-02, MECH-IVC-04]
confidence: high
---

## Scenario

An orbiting / base power producer (solar array, microwave receiver, or black-hole generator) generates
RF; libVulpes energy conduits carry it to a processing bench (e.g. chemical reactor); the bench matches a
JSON recipe against its input hatches and runs it to completion, emitting outputs. This flow traces the
**seams** between energy generation → distribution → recipe → inventory, where one subsystem's produced
state is another's consumed input. Mechanics live in the owning docs and are cited by anchor.

## Step sequence

**S1 — Formation (both machines), MECH-MBM-01.** Each controller's first server tick
(`initialCheck` / `timeAlive == 0`) calls `attemptCompleteStructure`; libVulpes swaps structural blocks
for hidden placeholders and wires hatch tiles into `batteries` (power), `itemInPorts`, `itemOutPorts`.
[V] The producer and the consumer form **independently** — neither knows the other exists; the only
coupling is the RF buffer + the conduit between them.

**S2 — Energy generation (producer), MECH-MBM-12/13/14.** Each server tick the producer computes
`powerMadeLastTick` from live inputs (solar: `min(4096, numPanels × 1.0005 × 2 × insolation) ×
solarGeneratorMult`) and calls `producePower(n)`, which deposits RF into the tile's own libVulpes
`MultiBattery` (the `batteries` group). [V] Power is recomputed each tick and never persisted
(INV-MBM-12), so this side is stateless across a restart. Off a registered station in the space
dimension the solar array's insolation is 0.

**S3 — Distribution (foreign seam).** `producePower` fills the producer's internal `MultiBattery`,
exposed as a Forge `IEnergyStorage`. libVulpes energy conduits extract from the producer and push into
the consumer bench's `batteries` hatches. **Stellurgy owns no cable / conduit tile**; the entire
producer→consumer transport is libVulpes-owned — the widest seam in the flow, visible to Stellurgy docs
only as "producers call `producePower`, consumers draw from `batteries`" (multiblock-machines overview,
"Shared multiblock pattern").

**S4 — Recipe table population (load-time), MECH-RCP-01.** At resource load Forge reads
`assets/stellurgy/recipes/_factories.json`, instantiates each `Recipe*` factory, and files every
`"type": "stellurgy:<id>"` JSON recipe under the tile class its `getMachine()` names, into
`RecipesMachine.getRecipes(Tile*.class)`. [V] Code-generated recipes are added on top: the chemical
reactor's suit-seal set (MECH-MBM-16) and the centrifuge's lava recipe are built in `registerRecipes()`.

**S5 — Recipe match (consumer), MECH-MBM-15.** When a player inserts ingredients the hatch fires
`onInventoryUpdated`; the bench calls `getRecipe(getMachineRecipeList())` (keyed by its own class),
checks `canProcessRecipe`, and — if matched and `enabled` — sets `powerPerTick = ceil(mult ×
recipe.getPower())`, `completionTime = max(mult × recipe.getTime(), 1)`, then `setMachineRunning(true)`.
[V] **Seam:** the recipe's `powerPerTick` is the demand the S2/S3 supply must meet; if `batteries` is
empty the libVulpes running-powered tick simply does not advance `currentTime` (no error, the machine
stalls).

**S6 — Powered processing tick (consumer, libVulpes).** Each tick while running, the base
`onRunningPoweredTick` withdraws `powerPerTick` from `batteries` and increments `currentTime` toward
`completionTime`; a gravity / oxygen gate can suppress the advance (MECH-MBM-17 crystallizer,
`enableOxygen` reactor). [V]

**S7 — Completion, MECH-MBM-15.** At `currentTime == completionTime`, `processComplete` consumes the
matched ingredients from `itemInPorts` and pushes outputs to `itemOutPorts`. The chemical reactor
overrides consumption (`consumeItemsSpecial`) to enchant the actual input stack rather than emit a fresh
one. [V]

**S8 — Client sync, MECH-MBM (energy) + MECH-IVC-04 (bench GUI).** Power changes ship via the libVulpes
generic `PacketMachine` id 1 (`sendToNearby`, radius 128) — a per-tile `byte` discriminator, **no
Stellurgy-owned packet** (C2). [V] The bench GUI's `ModuleProgress` bar and text modules sync via the
vanilla window-property protocol (MECH-IVC-04), not packets. The description packet carries render-only
NBT (`amtPwr`, `canRender`, `numPanels`).

## Gaps & mismatches

- **G1 — Distribution is entirely foreign and has no contract.** No Stellurgy subsystem owns the
  producer→consumer RF transport; both sides only touch libVulpes (`producePower` / `batteries`
  `MultiBattery`). A producer that is built but not conduit-connected to any consumer silently makes
  power that goes nowhere, and a starved consumer stalls with no signal. By design, but the seam with
  zero Stellurgy-side observability (energy generation ↔ production bench).
- **G2 — Recipe contract: two benches can never load JSON recipes (MECH-RCP-02).** `RecipeCentrifuge`
  and `RecipeLathe` factory classes exist, but neither id is present in `_factories.json`, so Forge
  cannot resolve a `stellurgy:centrifuge` / `stellurgy:lathe` recipe `type`. The centrifuge is salvaged
  by a code-generated recipe (`registerRecipes` from `lavaCentrifugeOutputs`) and XML, but `TileLathe`
  has neither an override nor a wired factory — any JSON lathe recipe silently fails to load. This is a
  producer / consumer drift across four declaration sites (recipe classes, `_factories.json`,
  `registerMachine`, the `RecipeHandler` XML list) (recipe ↔ integration-dataloaders).
- **G3 — Recipe key is a class literal shared by three subsystems with no compile-time link.** Recipes
  are filed and fetched by `Tile*.class` across recipe (`getMachine()`), multiblock-machines
  (`getMachineRecipeList()`), and `Stellurgy.registerMachine`. Renaming a `Recipe*` class or a factory id
  breaks recipe routing with **no compile error**, and no test guards the class↔name mapping or the
  `_factories.json` registration — which is how G2 slips through (recipe ↔ multiblock-machines ↔
  integration-dataloaders).
