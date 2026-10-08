---
id: commands-gameplay
owns: [command/]
entrypoints: [StellurgyCommandRoot#<init>, Stellurgy#serverStarting (registers StellurgyCommandRoot)]
depends-on: [dimension-planets, space-stations, network-wire, api-public, atmosphere-oxygen, world-gen, items]
depended-by: []
contracts: [C2, C3, C4, C7]
confidence: high
---

## Purpose

The `/stellurgy` (aliases `advrocketry`, `ar`) admin/OP command tree. Every
gameplay-facing operator verb — spawn a planet or star, teleport, hand out a station
chip, edit `DimensionProperties`/`StellarBody`, force weather, reload recipes — is a leaf
under this one Forge `CommandTreeBase`. Purely a thin driver layer: each leaf parses args
and delegates to a manager/registry; the command classes hold almost no state.

## Responsibility boundary

Owns: the `/ar` command tree registration and all gameplay subcommands (`command/`,
`command/sub/**` minus `test/`), including the vanilla-`/weather` override for Stellurgy planets.
Does NOT own: the `/stellurgytest` harness (`command/test/`, subsystem `stellurgytest-probe-catalog`);
the managers the commands call into (`DimensionManager`, `SpaceObjectManager`,
`PlanetWeatherManager`, `machineRecipes`, `gravityManager`) — those live in their own
subsystems. Commands never persist their own NBT and own no registry names or packets;
they only *drive* other subsystems' seams.

## Key types

| class | role |
|-------|------|
| `StellurgyCommandRoot` | root `CommandTreeBase`; registers all subcommands; perm level 2; `shiftArgs` helper |
| `StellurgyCommand` (abstract) | base for leaves; `invalidValue`/`wrongUsage` lang-keyed `CommandException` factories |
| `PlanetCommand` / `StarCommand` / `StationCommand` / `GoToCommand` / `DevCommand` | intermediate `CommandTreeBase` group nodes |
| `PlanetGenerateCommand` | build planet/moon/gas-giant via `DimensionManager.generateRandom*` |
| `PlanetGetCommand` / `PlanetSetCommand` | reflective get/set of `DimensionProperties` via `PropLookup` MethodHandles |
| `PlanetListCommand` / `PlanetResetCommand` / `PlanetDeleteCommand` / `PlanetWeatherCommand` | list / reset / delete / read-weather-state |
| `StarGenerateCommand` / `StarGetCommand` / `StarSetCommand` / `StarListCommand` | `StellarBody` CRUD; `StarCommand.ActionType` enum = temp/pos/planets verbs |
| `CreateStationCommand` / `GiveStationCommand` | make station + platform + chip / give chip |
| `GoToDimensionCommand` / `GoToStationCommand` / `FetchCommand` | teleport self to dim/station / pull a player to you |
| `WeatherCommand` (redirect) | overrides vanilla `/ar weather` for `WorldProviderPlanet` with revert-aware refusal |
| `AddSealantCommand` / `AddTorchCommand` / `SetGravityCommand` / `FillDataCommand` / `ReloadRecipesCommand` | config/item/recipe operator verbs |
| `UniverseCommand` | intermediate group node — the world model a save was generated under |
| `UniverseStatusCommand` / `UniverseUpgradeCommand` | report the schema + configuration stamp / freeze what has been seen and move the world on |
| `DumpBiomesCommand` / `RunTestsCommand` | dev-only helpers under `/ar dev` |

## Mechanics

- **MECH-CMD-01 registration & dispatch.** `serverStarting` registers one `StellurgyCommandRoot`;
  Forge's `CommandTreeBase` walks the subcommand map by first arg, recursing into group
  nodes, else `CommandTreeHelp`. Required permission level 2. `Stellurgy.java:1204`, `StellurgyCommandRoot.java:26-66`. [V]
- **MECH-CMD-02 planet generate.** Parses `<starId|parentDim> [moon] <name>` and derives the body
  through the active generator's `IBodyDerivation` (there is no `generateRandom*` delegation). A PLANET takes the star's zone draw
  (`orbitalDistanceOf`) for both its orbit and its climate. A MOON is made the way the generator makes
  every procedural moon: its orbit is `ClusteredGalaxyGenerator#moonOrbitOf` — a multiple of the
  parent's radius — and its climate is derived at the parent's distance from the star
  (`PlanetGenerateCommand#execute`). A moon never takes the star-level draw into its own
  field (it would stand many AU from its planet). `[T]`
  `WorldCommandPlanetLifecycleContractTest#aGeneratedMoonOrbitsInsideItsParentsSphereOfInfluence`.
  It then states the profile's facts onto fresh `DimensionProperties` — bulk, gravity, temperature,
  giant-ness — and realizes its air from the profile's oxygen roll and pressure
  (`realizeAtmosphere`, MECH-DIM-10). It sets the profile's
  oxygen and giant-ness as well as the pressure. [V]
- **MECH-CMD-03 reflective property get/set.** `PlanetGetCommand`/`PlanetSetCommand` resolve
  a `DimensionProperties.PropLookup` MethodHandle by name, box/parse args (scalar, array,
  bool, number, string), then broadcast `PacketDimInfo`. `atmosphereDensity` is special-cased
  to `setAtmosphereDensity` — a proportional rescale of the planet's mix; on an airless world that
  refusal surfaces as a `CommandException`. On the get side `atmosphereDensity` and `hasOxygen` are
  special-cased too — they are readouts of the planet's air, no field holds them.
  `PlanetSetCommand.java:63-120`, `PlanetGetCommand.java:52-62`. [V]
- **MECH-CMD-04 star CRUD.** `StellarBody` created/edited via `StarCommand.ActionType`
  (temp/pos/planets); generate allocates `getNextFreeStarId`, broadcasts `PacketStellarInfo`.
  `StarGenerateCommand.java:33-45`, `StarCommand.java:34-106`. [V]
- **MECH-CMD-05 station create.** Registers a `SpaceStationObject` (calls `beginTransition(0)`
  BEFORE `registerSpaceObject` so the outgoing `PacketSpaceStationInfo` is correct), inits the
  space dim, builds a 3×3 cobble platform under spawn, gives a chip, optional `tp`.
  `CreateStationCommand.java:64-110`. [V]
- **MECH-CMD-06 teleport verbs.** `goto dim` inits + `TeleporterSeekBlock`; `goto station`
  moves to `spaceDimId` + spawn loc; `fetch` pulls a player to sender via `BasicTeleporter`.
  `GoToDimensionCommand.java:31-45`, `GoToStationCommand.java:34-51`, `FetchCommand.java:27-34`. [V]
- **MECH-CMD-07 Stellurgy weather override.** `/ar weather clear|rain|thunder [dur]` on a planet;
  refuses actions `WorldProviderPlanet.updateWeather()` would revert next tick via the pure
  `weatherRefusalKey(action, rainMarker, thunderMarker, canRain)`. `WeatherCommand.java:33-133`. [V][T]
- **MECH-CMD-08 planet weather read-out.** `PlanetWeatherCommand` prints static markers +
  live `PlanetWeatherState` (raining/thundering + tick countdowns), the runtime state not
  stored on `DimensionProperties`. `PlanetWeatherCommand.java:36-68`. [V]
- **MECH-CMD-10 universe status.** `/stellurgy universe status` prints the save's schema version
  against the one this build ships, the world's `<galaxyGen>` fingerprint against the pack's, whether
  they agree, how many systems are frozen, every schema version this build can still read, and whether
  an upgrade is armed. Read-only, and the first thing to run when a load has been refused: it names
  both sides of the comparison that refused it. `UniverseStatusCommand.java:39-64`. [V]
- **MECH-CMD-11 universe upgrade.** `/stellurgy universe upgrade` previews (old → new fingerprint,
  systems already frozen, players online whose crystals are readable) and asks for `confirm`. With
  `confirm` it walks every online player's inventory, off-hand, armour and ender chest for memory
  crystals, pins the system of every address on them, adopts the current schema, reinstalls that
  schema's generator live, and ARMS the world for one configuration change at its next start. The
  arming is the asymmetric half: a changed `<galaxyGen>` refuses the boot, so the permission has to be
  given by the session before it. `UniverseUpgradeCommand.java:56-110`. [V]
- **MECH-CMD-09 config/item/recipe verbs.** `addSealant`/`addTorch` add held block to a
  persisted whitelist; `setGravity` drives `gravityManager`; `fillData` writes item
  `DataStorage`; `reloadRecipes` refreshes machine + XML recipes (NOT auto-genned — frozen
  registry). `AddSealantCommand.java:33-46`, `SetGravityCommand.java:31-49`, `FillDataCommand.java:46-100`, `ReloadRecipesCommand.java:34-60`. [V]

## State & persistence

No NBT owned. Command-driven persistence is delegated:
- `addSealant`/`addTorch` mutate `StellurgyConfiguration` in-memory sets AND rewrite config keys
  `sealableBlockWhiteList` / torch whitelist, then `save()` — via `addSealedBlock`
  (`StellurgyConfiguration.java:1071-1081`) / `addTorchblock`. [V] (contract C4)
- planet/star edits broadcast to clients and rely on the target manager to persist on world
  save (see dimension-planets / space-stations).

## Invariants

- **INV-CMD-01 [V][BEH]** Root command requires OP permission level 2; every leaf is server-side
  (`CommandBase.execute`), none client-registered. `StellurgyCommandRoot.java:64`. [V]
- **INV-CMD-02 [T][BEH]** Player-requiring leaves obtain the player via `getCommandSenderAsPlayer`,
  so a console/non-player sender is refused with the vanilla "must be a player" message,
  not an NPE. Pinned for addTorch/setGravity/fillData/goto/fetch. `WorldCommandGuardContractTest.java:29-62`. Pinned by `WorldCommandGuardContractTest#addTorchRefusesConsoleSender`, `WorldCommandGuardContractTest#setGravityRefusesConsoleSenderWithUsage`, `WorldCommandGuardContractTest#fillDataRefusesConsoleSender`, `WorldCommandGuardContractTest#gotoRefusesConsoleSender`, `WorldCommandGuardContractTest#fetchRefusesConsoleSender`.
- **INV-CMD-03 [T][BEH]** `weatherRefusalKey` is a pure function of (action, rainMarker, thunderMarker,
  canRain) and returns a refusal lang-key (or null) matching what `updateWeather` would
  revert. `WeatherCommand.java:118-133`, `WeatherCommandRefusalTest.java:26-92`. Pinned by `WeatherCommandRefusalTest#rainAllowedWhenDynamicMarkerAndAtmosphereOk`, `WeatherCommandRefusalTest#rainRefusedByNeverMarker`, `WeatherCommandRefusalTest#rainRefusedByThinAtmosphere`, `WeatherCommandRefusalTest#clearRefusedByAlwaysRainMarker`.
- **INV-CMD-04 [V][BEH]** `planet set atmosphereDensity` bypasses reflection and calls
  `setAtmosphereDensity` (rescale, refused on an airless world); all other props go through
  `PropLookup` MethodHandles. `PlanetSetCommand.java:64-78`.
- **INV-CMD-05 [V]** Station creation flips `created=true` (`beginTransition(0)`) BEFORE
  `registerSpaceObject`, so the broadcast `PacketSpaceStationInfo` carries a valid station.
  `CreateStationCommand.java:66-70`.
- **INV-CMD-06 [V][BEH]** `reloadRecipes` deliberately never calls `createAutoGennedRecipes` — those
  register into Forge's frozen recipe registry and would throw at runtime. `ReloadRecipesCommand.java:41-46`.
- **INV-CMD-07 [T][A][BEH]** `/ar` primary command and its help/usage survive server registration and a
  malformed invocation without crashing the server. `[T]` for registration and help only —
  `CommandsSmokeTest.java:21,36`; the malformed-invocation half is `[A]` (no test pins it). Pinned by `CommandsSmokeTest#primaryCommandsAreRegistered`, `CommandsSmokeTest#stellurgyHelpCommandPrintsUsageWithoutCrash`.
- **INV-CMD-08 [A][BEH]** Planet/star edit commands assume the target dimension/star is
  registered (guarded by `isDimensionCreated`/`getStar != null`); unloaded worlds are not
  re-created by get/set (unlike goto/create which init the dim). Inferred from guards, not
  a dedicated test.

## Failure modes & edge cases

- `planet delete` refuses while players are present, listing them instead of yanking them out.
  `PlanetDeleteCommand.java:36-48`. [V]
- `station create` rejects an orbit dim that falls back to the galaxy's `getOverworldProperties()` (i.e. the
  requested dim does not resolve to a distinct Stellurgy dim). `CreateStationCommand.java:46-50`. [V]
- `planet generate moon` re-derives the star id from the parent planet's `getStarId()` and
  then dereferences `getStar(starId)`. `PlanetGenerateCommand.java:58,102`. [V]
- `fillData` silently no-ops when the held item is neither `IDataItem` nor `ItemMultiData`
  and args are well-formed (no error message on the empty-else path). `FillDataCommand.java:72-99`. [V]

## Integration seams

- **Packets (C2, consumed):** `PacketDimInfo` (planet set/reset/delete), `PacketStellarInfo`
  (star generate), `PacketSpaceStationInfo` (station create, via `registerSpaceObject`).
- **Config (C4, consumed/mutated):** `spaceDimId` (goto/create), `minAtmosphereDensityForRain`
  (weather gates), `sealableBlockWhiteList` + torch whitelist (addSealant/addTorch write).
- **Managers/APIs:** `DimensionManager`, `SpaceObjectManager`, `PlanetWeatherManager`,
  `StellurgyAPI.gravityManager`, `Stellurgy.instance.machineRecipes`,
  `DimensionProperties.PropLookup`, `StellarBody`, `BasicTeleporter`/`TeleporterSeekBlock`.
- **Lang (C7):** all user output is `commands.stellurgy.*` translation keys plus
  vanilla `commands.weather.*` keys reused by the redirect.
- No mixins, no capabilities, no registry names owned.

## Config surface

`spaceDimId` and `minAtmosphereDensityForRain` gate behaviour but are read-only here — no
command disables the tree. `addSealant`/`addTorch` are the only commands that *write* config;
disabling those blocks is a matter of not running the command (their effect persists via
`save()`). The `/ar dev` group (`dumpBiomes`, `runTests`) is unconditionally present — no
config/dev flag gates it (see Open questions).

## Test coverage

- INV-CMD-02 → `src/test/java/.../server/WorldCommandGuardContractTest.java:29-80`
- INV-CMD-03 → `src/test/java/.../unit/WeatherCommandRefusalTest.java:26-92`
- INV-CMD-07 → `src/test/java/.../server/CommandsSmokeTest.java:21,36` (registration and help only)
- MECH-CMD-02/03/04/05/06/08/09 have no dedicated test (driver logic exercised only
  indirectly, if at all).

## Open questions

- `PlanetGenerateCommand.java:102` `getStar(starId).removePlanet(props)` can
  NPE if the parent planet's `getStarId()` (line 58) resolves to no `StellarBody` on the
  `moon` path. Needs a repro with an orphaned parent planet..
- `/ar dev` (`dumpBiomes`, `runTests`) ships in production with no dev/debug config gate —
  `RunTestsCommand` registers `IngameTestOrchestrator` on the live event bus. Intended?.
