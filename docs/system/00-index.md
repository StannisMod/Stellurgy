# System Documentation — Index & Boundary Map

**THIS FILE IS THE ROUTER, and it is the only copy.** Every doc under `system/` is reachable from
here — directly, or through the `00-overview.md` of a two-level subsystem, which is how a subsystem is
entered. `tools/check-index.py` checks that and fails on an unreachable doc or a dead link; run it
in the commit that adds, renames, splits or retires a doc.

**Coverage** is computed from the live source tree by `tools/check-coverage.py`, not stored: every
`.java` file under the source roots has exactly one owning subsystem (the `owns:` globs in each
subsystem doc's front matter), and every registry id, packet and config flag is referenced from a
contract. The "owns" column below is a human summary; the checker does not read it, and the file and
line counts it could carry are the checker's output, not this table's. Source roots outside
`src/main/java`: the vendored physics tree (`valkyrienskies/`) and the test harness (`testframework/`).

See [`00-methodology.md`](./00-methodology.md) for how these docs are written.

The unit is the **subsystem** (a responsibility boundary), not the java package. Oversized subsystems
become **two-level docs**: a short overview (boundary + mechanic index) plus mechanic sub-docs, each
sub-doc held at ≤ ~10:1 compression. The `shields` subsystem maps the AFFS code, the package
`src/main/java/dev/stannismod/stellurgy/affs/`.

Legend — **split**: `1` single doc · `2L` two-level (overview + subs) ·
`cat` catalogue-shaped (repetitive defs, higher compression is intentional).

## Tier A — core subsystems

| subsystem | split | owns |
|-----------|:-----:|------|
| [client-render](./20-subsystems/client-render/00-overview.md) | 2L | `client/` — proxy, keybinds, tooltip, all `render/` (planet-sky, machines, entity) |
| [rocket-entity](./20-subsystems/rocket-entity/00-overview.md) | 2L | `entity/`, `api/FreeFlight*`, `api/RocketFlightMode`, `TileGuidanceComputer` |
| [multiblock-machines](./20-subsystems/multiblock-machines/00-overview.md) | 2L | `tile/multiblock/` — drills, terraformer, railgun, observatory, machine bench, energy |
| [world-gen](./20-subsystems/world-gen/00-overview.md) | 2L | `world/` — terrain, decoration, providers, weather, biome |
| [space-stations](./20-subsystems/space-stations/00-overview.md) | 2L | `stations/`, `tile/station/`, `TileOrbitalRegistry`, `TileStationAssembler` |
| [util-core](./20-subsystems/util-core/00-overview.md) | 2L | `util/` — XML loaders, weight engine, world helpers (`StorageChunk` → rocket-assembly) |
| [infrastructure-tiles](./20-subsystems/infrastructure-tiles/00-overview.md) | 2L | `tile/infrastructure/`, `tile/hatch/`, pump/tank/solar/forcefield roots |
| [api-public](./20-subsystems/api-public/00-overview.md) | 2L | `api/` minus free-flight — config, registries, interfaces, capability decls |
| [dimension-planets](./20-subsystems/dimension-planets.md) | 1 | `dimension/` — `DimensionProperties` |
| [shields](./20-subsystems/shields.md) | 1 | **AFFS** (`dev.stannismod.stellurgy.affs`) — shield network solve, SDF field, impact absorption, world/ship frame seam, priority-group control |
| [hyperdrive](./20-subsystems/hyperdrive.md) | 1 | `hyperdrive/`, `tile/hyperdrive/`, `JumpGate` — the machines a jump needs, the window, the capacitor's lazy charge, and the arm→press→spool→commit act |
| [structural-damage](./20-subsystems/structural-damage.md) | 1 | `api/damage/`, `damage/` — the budget-and-spend engine, damage stages and where they live, destruction with provenance, target resolution and the world↔subspace conversion for an impact |
| [projectile-substrate](./20-subsystems/projectile-substrate.md) | 1 | `api/projectile/`, `projectile/` — what a shot IS between muzzle and impact: the record, its per-world registry, swept-segment integration, earliest-crossing resolution across the field and structure layers |
| [weapons](./20-subsystems/weapons.md) | 1 | `api/weapon/`, `weapon/`, `tile/weapon/`, `block/weapon/` — a gun: the spec derived from what was built, the assembly walk, the traverse mechanism and its failure vocabulary, the world/ship frame seam at the muzzle. Works with no network |
| [fire-control-sensor](./20-subsystems/fire-control-sensor.md) | 1 | `api/sensor/`, `sensor/`, `tile/sensor/` — the step before a gun: what is out there, which of it is a legitimate target, how well each is held (radiance vs total power), and the one contact handed to a weapons network. Passive listens, active illuminates |
| [subsystem-network](./20-subsystems/subsystem-network.md) | 1 | `subsystem/network/` — one max-flow solver for every subsystem that distributes something over built blocks; domains, sources, sinks, capacity-limited lines |
| [atmosphere-oxygen](./20-subsystems/atmosphere-oxygen.md) | 1 | `atmosphere/`, `tile/atmosphere/`, `armor/`, suit workstation/wearable |
| [ship-heat](./20-subsystems/ship-heat.md) | 1 | `subsystem/heat/`, `tile/heat/` — the coolant loop: heat as energy, one temperature per loop, the blocks that are its thermal mass |
| [rocket-assembly](./20-subsystems/rocket-assembly.md) | 1 | `TileRocketAssemblingMachine`, `TileUnmannedVehicleAssembler`, `StorageChunk` |
| [ship-flight-model](./20-subsystems/ship-flight-model.md) | 1 | `ship/mass/`, `ship/control/`, the mass/gravity/survey half of `integration/vs/`, `IShipActuatorBlock`, the reaction wheel, the flight computer's flight-model and controller sections — what a tier-2 hull can do, and flying by it |

## Tier B — periphery

| subsystem | split | owns |
|-----------|:-----:|------|
| [blocks](./20-subsystems/blocks.md) | cat | `block/` |
| [integration-jei](./20-subsystems/integration-jei.md) | cat | `integration/jei/` |
| [items](./20-subsystems/items.md) | cat | `item/` |
| [inventory-containers](./20-subsystems/inventory-containers.md) | cat | `inventory/` |
| [satellite](./20-subsystems/satellite.md) | 1 | `satellite/`, `tile/satellite/` |
| [misc-oddities](./20-subsystems/misc-oddities.md) | 1 | `common/`, `unit/`, `enchant/`, ISC-var-sharing-fix, `Stellurgy.java`, root |
| [commands-gameplay](./20-subsystems/commands-gameplay.md) | 1 | `command/sub/`, `command/` root (minus `test/`) |
| [network-wire](./20-subsystems/network-wire.md) | 1 | `network/` |
| [wirelessdata](./20-subsystems/wirelessdata.md) | 1 | `wirelessdata/`, `TileWirelessTransceiver` |
| [event-handlers](./20-subsystems/event-handlers.md) | 1 | `event/` |
| [backward-compat](./20-subsystems/backward-compat.md) | 1 | `backwardCompat/` |
| [mission](./20-subsystems/mission.md) | 1 | `mission/` |
| [integration-dataloaders](./20-subsystems/integration-dataloaders.md) | 1 | `integration/dataloaders/` |
| [integration-probe-waila](./20-subsystems/integration-probe-waila.md) | 1 | `integration/theoneprobe/`, `integration/waila/` |
| [mixins-asm-coremod](./20-subsystems/mixins-asm-coremod.md) | 1 | `mixin/`, `asm/` |
| [advancements](./20-subsystems/advancements.md) | 1 | `advancements/` |
| [recipe](./20-subsystems/recipe.md) | 1 | `recipe/` |
| [capability](./20-subsystems/capability.md) | 1 | `capability/` |
| [integration-modcompat](./20-subsystems/integration-modcompat.md) | 1 | `integration/` root — `CompatibilityMgr`, GalactiCraft, MatterOverdrive |

## Harness

| bucket | split | owns |
|--------|:-----:|------|
| [stellurgytest-probe-catalog](./20-subsystems/stellurgytest-probe-catalog.md) | cat | `command/test/` — `/stellurgytest` verb catalogue (contract, not gameplay) |
| [test-suite-map](./20-subsystems/test-suite-map.md) | 1 | `src/test/` — read as a second contract oracle, documented as one overview |

## Cross-cutting contracts (`30-contracts/`)

C1–C7 consolidate the mod's data surfaces (NBT, wire, registry, config, capabilities/events, mixins,
lang); C8 onward are behavioural or architectural contracts. All of them are cited **by clause
anchor** — `HEAT-7`, `BODY-4`, `CON-C21-11` — from source code, tests and other docs, so the anchor
namespace in the last column is the part you need before citing one. The exhaustive key / id / flag
lists behind C1–C7 are computed from the live tree by `tools/check-coverage.py`, not stored.

**The clause ranges below are each doc's OWN front matter.** This table does not carry counts of
keys, packets or flags: they would be a snapshot that goes stale without any gate noticing.

| id | covers | anchors |
|----|--------|---------|
| [C1](./30-contracts/C1-nbt-persistence.md) | NBT persistence keys | key tables |
| [C2](./30-contracts/C2-network-wire.md) | network packets / wire format | `CON-C2-*`, `INV-NW-*` |
| [C3](./30-contracts/C3-registry-ids.md) | registry names | `R1..R9` |
| [C4](./30-contracts/C4-config-surface.md) | config surface (`StellurgyConfiguration`) | flag tables |
| [C5](./30-contracts/C5-capabilities-events.md) | capabilities & Forge event subscriptions | capability + event tables |
| [C6](./30-contracts/C6-mixin-asm-at.md) | mixin targets / AT | seam table |
| [C7](./30-contracts/C7-lang-assets.md) | lang / asset keys | namespace table |
| [C8](./30-contracts/C8-crew-any-attitude.md) | any-attitude crew behaviour aboard a tier-2 (VS) ship — capture, frames, camera, interaction | `CREW-C1..C17` |
| [C9](./30-contracts/C9-ship-control.md) | tier-2 pilot control — input delivery to the linked computer, seat binding and link lifecycle, FA autopilot | `SHIPCTL-1..19` |
| [C10](./30-contracts/C10-ship-stats.md) | the tier-2 stat surface derived from construction — mass, authority, readouts, live recompute; and the tier-1 rocket launch gate (local gravity, inclusive, off with the weight system, one decision for launch and assemblers) | `STAT-1..25`, `STAT-26..29` |
| [C11](./30-contracts/C11-physics-substrate-port.md) | the Stellurgy↔physics-substrate boundary — who may name engine types, the port's operation inventory, ONE SHIP ONE IDENTITY, a ship is the blocks that were pasted, and a loaded ship holds its chunks | `PORT-1..15` |
| [C12](./30-contracts/C12-ship-heat.md) | the ship thermal contract — heat as energy, rejection, the environment, the failure ladder, the signature | `HEAT-1..18` |
| [C13](./30-contracts/C13-space-presence.md) | what survives a logout in a space cell — presence vs aboard-ness, which record wins, what a RELEASE lets go, and what survives a DEATH | `PRES-1..11` |
| [C14](./30-contracts/C14-cell-sky-and-bodies.md) | which sky a world draws, and when a cell's bodies feed it | `CON-C14-01..20` |
| [C15](./30-contracts/C15-cell-address-and-frame.md) | what a cell's address MEANS — a durable name vs a position that moves with its body, and what an authored neighbourhood holds | `ADDR-1..25` (ADDR-5 retired) |
| [C16](./30-contracts/C16-space-clock.md) | which clock the space subsystem reads, on either side | `CLOCK-1..5` |
| [C18](./30-contracts/C18-living-through-a-jump.md) | what a jump promises the people ABOARD — where they are, what they may do, what each crossing carries, and that an arrival has no failure path | `JUMP-1..15` (JUMP-7 retired) |
| [C19](./30-contracts/C19-reference-frames.md) | which body a craft's velocity is measured AGAINST, and what a change of frame may not alter | `FRAME-1..11` |
| [C20](./30-contracts/C20-repair.md) | what repairing a structure means — the two outcomes, who pays, how bays share a hull | `REPAIR-1..15` |
| [C21](./30-contracts/C21-atmosphere-model.md) | what an atmosphere IS and what may be derived from it — one model for a compartment and a planet | `CON-C21-01..18` |
| [C22](./30-contracts/C22-craft-motion.md) | what a craft does with momentum it did not command, manned and not | `MOTION-1..7` (MOTION-3 retired) |
| [C23](./30-contracts/C23-deck-motion-across-the-wire.md) | what a client must be TOLD about a craft's motion, and what the server may accept back | `DECKSYNC-1..7` |
| [C24](./30-contracts/C24-celestial-body-and-its-environment.md) | what a celestial object IS, and what the place around it offers | `BODY-1..18` |
| [C25](./30-contracts/C25-hyperphysics-law-and-axes.md) | the one law under shields and hyperdrive, and the axes a craft is built along | `HYPER-1..33` |
| [C26](./30-contracts/C26-field-energy-and-orbital-actuation.md) | where a harvested joule comes from, and what taking it does | `ENERGY-1..13` |
| [C27](./30-contracts/C27-the-shield-field-where-a-wall-exists.md) | what a shield IS before anything hits it — WHERE the wall exists, who sustains it, what shapes it may take | `FIELD-1..19` |
| [C28](./30-contracts/C28-the-shield-program-how-a-wall-responds.md) | HOW the wall responds to what arrives, and the rule that it may never read intent | `WALL-1..18` |
| [C29](./30-contracts/C29-dimension-ids.md) | what a dimension id MEANS — one body per id in every holder, a stated id honoured, an allocated id never a stated one, a refusal loud, an id outliving the server | `DIMID-1..5` |

**Taking a contract id.** C17 is unassigned. Scan every reference to a contract id across the whole
repository before taking one; a directory listing of the checked-out tree does not show an id in use
elsewhere.

## End-to-end flows (`40-flows/`)

One player-visible path each, followed end to end across whatever subsystems it crosses.

[F1](./40-flows/F1-build-launch-travel-land.md) build→launch→travel→land ·
[F2](./40-flows/F2-free-flight-loop.md) free-flight control loop ·
[F3](./40-flows/F3-dimension-planet-lifecycle.md) dimension/planet lifecycle ·
[F4](./40-flows/F4-oxygen-sealing-tick.md) oxygen/sealing tick ·
[F5](./40-flows/F5-satellite-deploy-data-terminal.md) satellite deploy→data→terminal ·
[F6](./40-flows/F6-station-assembly-warp.md) station assembly→warp ·
[F7](./40-flows/F7-machine-power-recipe-cycle.md) machine power/recipe cycle ·
[F8](./40-flows/F8-save-load-compat-migration.md) save/load & compat migration.

## Design roots — the model, not the boundary

These describe a MODEL rather than a source root, which is why no subsystem row owns them.

- [space-model](./space-model.md) — the tier-2 space layer: cells, hyperspace, transit, presence. Describes `space/`.
- [universe-model](./universe-model.md) — galaxy generation, systems, bodies, addresses, discovery. Describes `universe/`.
- [metric-boundary](./metric-boundary.md) — CHART vs WORLD: which metric a length is in. Read before any distance, radius or scale constant.
- [00-methodology](./00-methodology.md) — how every doc under `system/` is written: template, anchors, confidence tags, line budgets, coverage.
