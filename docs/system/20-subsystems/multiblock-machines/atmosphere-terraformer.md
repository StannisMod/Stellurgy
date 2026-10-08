# Atmosphere Terraformer

Part of [multiblock-machines](./00-overview.md). File:
`TileAtmosphereTerraformer.java` (610, most of it the 12-layer structure template).

## Purpose

A large multiblock (`TileMultiPowerConsumer`) that slowly raises or lowers the orbited/local
planet's air toward 1600 (increase) or 0 (decrease), consuming N₂/O₂ gas. Distinct from the
orbital laser's terraforming drill (which reshapes *terrain*); this one mutates the planet's
composition on `DimensionProperties`. **Interim form (to be replaced by a CO2→O2
converter)**: a step up ADDS what it drained — `ONE_ATM/200` of N₂ and of O₂, one centi-atm in all —
through `addToAtmosphere`, so an oxygen-free world it thickens gains oxygen; a step down rescales the
whole mix by one centi-atm (`setAtmosphereDensity`). The 1600 target is a literal in the tile.
`TileAtmosphereTerraformer.java:511`.

## Key types

| class | role |
|-------|------|
| `TileAtmosphereTerraformer` | controller; increase/decrease radio switch, fluid gate, density step |

Two `ModuleToggleSwitch` (increase / decrease) bound by a `ModuleRadioButton` (mutually
exclusive). `completionTime = 18000 × terraformSpeed` ticks per density unit. [V] lines 261-297.

## Mechanics

### MECH-MBM-09 — Atmosphere density step
The libVulpes running-powered cycle counts to `completionTime`, then `processComplete()` moves
density by ±1 per completion. Increase stops at 1600 (auto-disables machine); decrease stops at 0.
`completionTime` is recomputed from config each completion. [V] `TileAtmosphereTerraformer.java:434-520`.

### MECH-MBM-10 — Fluid consumption & out-of-fluid state
When `terraformRequiresFluid` and the increase mode is selected, each powered tick drains
`terraformliquidRate` of `fluidNitrogen` and `fluidOxygen` from the fluid input hatches; if either
requirement is unmet the tile enters `outOfFluid` (halts the timer and blocks power use). Decrease
mode and `terraformRequiresFluid=false` skip the gas requirement entirely. `outOfFluid` toggling
fires a block-update so the client/waila reflects it; it is **not** persisted (reset to false in
`readFromNBT`). [V] `TileAtmosphereTerraformer.java:391-440,469-481,601-604`.

### MECH-MBM-11 — Terraform eligibility guards
`isRunning()` requires the machine enabled **and** `enableTerraforming`; if false it zeroes the
timer. `processComplete()` only mutates density when the dim is the local dim and either the
provider is exactly `WorldProviderPlanet` with `isNativeDimension`, or `allowTerraformNonStellurgy` is
set. [V] `TileAtmosphereTerraformer.java:456-467,491-519`.

## State & persistence

Base state (enabled/running/currentTime) is handled by libVulpes NBT. The terraformer-specific keys `selected` (radio-button option,
0=increase, 1=decrease) and `oofluid` (out-of-fluid status; network only, disk reset to false) are network-only
(`writeNetworkData`/`readNetworkData`, lines 572-592; `C1-nbt-persistence`). The mutated air itself (`air`, an `AirState`) lives on
`DimensionProperties` ([dimension-planets](../dimension-planets.md)).

## Integration seams

- **Packets**: reuses libVulpes `TileMultiblockMachine.NetworkPackets.TOGGLE` discriminator to
  sync the radio selection; button ids 1/2 send TOGGLE to server. [V] lines 522-568.
- **Fluids**: drains `StellurgyFluids.fluidNitrogen` / `fluidOxygen` via `fluidInPorts`.
- **Writes** `DimensionProperties.setAtmosphereDensity`.
- **Sound/particles**: `AudioRegistry.machineLarge`; client rocketSmoke(+Inverse) particles.

## Config surface

Config: see `C4-config-surface`. Full disable: `enableTerraforming=false` (isRunning false ⇒ no accrual).

## Invariants

- **INV-MBM-06** [A][BEH] On a native Stellurgy planet, with power **and** fuel the density moves; without
  fuel or without power it does **not** move.
  `TerraformerPoweredCycleOnStellurgyPlanetTest.java:111-213`. Pinned by `TerraformerPoweredCycleOnStellurgyPlanetTest#nativePlanetTerraformerWithFuelAndPowerStepsDensity`, `TerraformerPoweredCycleOnStellurgyPlanetTest#nativePlanetTerraformerWithoutFuelDoesNotStep`, `TerraformerPoweredCycleOnStellurgyPlanetTest#nativePlanetTerraformerWithoutPowerDoesNotStep`.
- **INV-MBM-07** [V][BEH] Density is bounded: increase never exceeds 1600, decrease never below 0, and
  hitting the bound auto-disables the machine. `TileAtmosphereTerraformer.java:499-518`.
- **INV-MBM-08** [V][BEH] `enableTerraforming=false` forces `currentTime=0` and blocks all accrual —
  the flag gates the consequence, not just the display. `TileAtmosphereTerraformer.java:456-467`.

## Test coverage

`TerraformerMultiblockTest` (validation, the lone controller's tick, the helper an atmosphere change starts),
`TerraformerPoweredCycleOnStellurgyPlanetTest` (INV-MBM-06),
`TerraformerPoweredCycleOnOverworldTest` (allowTerraformNonStellurgy path).

## Failure modes & edge cases

None specific beyond the shared "float in physics" watch — `currentTime` is int, density is int,
so no persisted-float risk here.
