---
id: C21-atmosphere-model
kind: contract
subject: what an atmosphere IS, and what may be derived from it
owners: [atmosphere-oxygen, dimension-planets]
clauses: CON-C21-01 .. CON-C21-34
ratified: 2026-08-19 (CON-C21-19 .. 22: 2026-10-05; CON-C21-23 .. 34, rooms, design stage: 2026-10-12)
confidence: mixed — a clause is `[A]` unless the CURRENT code satisfies or violates it, which the
  status table cites per clause; CON-C21-23 .. 34 are `[PLANNED]` requirements except where the table says a part is built
---

## Purpose

One model of air, for a compartment and for a planet alike: a composition, a temperature, and
predicates over them. This contract says what an atmosphere IS and what may be concluded from it — it
does not say how thick Mars is, which gases the pack ships, or where a threshold sits. Those are
`tunable`; the relations below are not.

Ruled by the maintainer on 2026-08-19. The clause numbers here are permanent identifiers.

## The state

- **CON-C21-01** `[A][SYS]` An atmosphere is a **composition** — a partial pressure per gas — plus a
  temperature. There is exactly ONE representation of what air is; nothing else may store a second
  answer to any question this one can answer. FOR: INV-ATM-18.

  > **Extent.** The composition is **intensive** and says nothing about how much there is; extent
  > belongs to the HOLDER. A sealed zone and a tank know their volume, so amounts and mass derive from
  > the same composition. **A planet's atmosphere is FINITE** (maintainer ruling 2026-09-30,
  > "просто огромной по объёму" — simply enormous in volume): an infinite outdoor reservoir would turn
  > one shipload of vented oxygen into an endless well, so a planet holds its atmosphere over an extent
  > exactly as a zone does, and every exchange with it conserves the amount. The size of that extent is
  > an open measurement, not yet a clause. (A nebula is the one atmosphere-like medium that is a FIELD.)
  > The code reflects the compartment half: `AtmosphereBlob` is a flood-filled sealed volume, while
  > `AtmosphereHandler` over a planet's `DimensionProperties` density answers a field-style readout
  > with no amount.
- **CON-C21-02** `[A][BEH]` **Presence is availability.** A gas present in an atmosphere is a gas that can
  be extracted from it, and what is extracted follows its partial pressure. A gas that is present and
  unextractable, or extractable and absent, is a violation — there is no second list of "what is
  harvestable here".
- **CON-C21-03** `[A][SYS]` A gas is in the model only when a mechanic READS it: every registry row
  declares its roles, and every declared role is read by at least one predicate. Storage without a
  consumer is what keeps a model from growing to chemistry's size. FOR: INV-ATM-18.
- **CON-C21-04** `[A][SYS]` Resolution and range are stated, and the FLOOR is honest: what falls below the
  smallest representable partial pressure is zero, never secretly retained. A model that rounds a
  trace to zero must answer "there is none" consistently everywhere. FOR: INV-ATM-18.

## Derivation — everything else is a predicate

- **CON-C21-05** `[A][SYS]` Breathability, combustion, toxicity, corrosivity, the need for a suit and the
  set of active hazards are **predicates over composition and temperature**. No flag, field or
  registry row may assert any of them independently of the state. FOR: INV-ATM-18.
- **CON-C21-06** `[A][BEH]` **Monotonicity.** A strictly better atmosphere never reads as more hazardous:
  adding oxygen never makes a room less breathable, removing a toxin never makes it more toxic, and
  the same holds in reverse. This is the clause that survives every rebalance, and it is the one a
  property test can falsify without naming a number.
- **CON-C21-07** `[A][BEH]` Combustion is decided by the **oxidiser**, never by breathability. The two
  bands are independent facts about the same gas and may not be collapsed into one.
- **CON-C21-08** `[A][BEH]` Immunity is a property of an ENTITY against a HAZARD, never of an atmosphere.
  What a suit protects against is a statement about the suit.
- **CON-C21-09** `[A][SYS]` The NAME of an atmosphere is DERIVED — the ordered set of assertions that are
  true of it — and nothing may key behaviour on a name. A name is what a player reads, never what the
  game branches on. FOR: INV-ATM-18.

## One model, planets included

- **CON-C21-10** `[A][SYS]` A planet's atmosphere is the **same object** as a compartment's. There is one
  atmosphere model in the mod, and the boundary between indoors and outdoors is a boundary between two
  instances of it, not between two vocabularies. FOR: INV-ATM-18.
- **CON-C21-11** `[A][BEH]` Where a composition is not authored it is **derived**, from the body's own
  properties (gravity and temperature — thermal escape), by the same rule everywhere. Authoring
  overrides derivation; derivation never overrides authoring.
- **CON-C21-11b** `[A][SYS]` Authoring happens ONCE, at creation, and the body's own saved state is the
  authority ever after. An authored body either states a composition or **names another body of the
  same definition file**, which is resolved by COPY — never by alias, so nothing that changes the
  exemplar later reaches through the reference. A cycle, a dangling name and an ambiguous name are
  load errors, never a silent default. There is no partial override: a composition is stated whole,
  because a total pressure is the SUM of its parts and "change one gas" has no single meaning. FOR: INV-ATM-18.

## The client

- **CON-C21-12** `[A][SYS]` The client **decides nothing** about an atmosphere. Every threshold, damage,
  consumption and gate is resolved server-side; what crosses to the client exists to be displayed, and
  its staleness may never change an outcome. FOR: INV-ATM-18.
- **CON-C21-13** `[A][SYS]` Because of CON-C21-12 the routine sync is **cheap and per-player phased** — no
  shared clock may stack every player's update onto one tick — and the full composition crosses only
  when something that displays numbers asks for it. FOR: INV-ATM-18.

## Hazards

- **CON-C21-14** `[A][BEH]` A toxic gas harms by its OWN limit, independently of total pressure and of
  oxygen. "Enough air to breathe" is not a defence against a poison in it.
- **CON-C21-15** `[A][BEH]` Corrosion acts on EXPOSED blocks and is spent through the block-damage budget,
  never as ad-hoc block replacement. A raised shield removes the EXPOSURE; it does not acquire a
  corrosion mechanic of its own.
- **CON-C21-16** `[A][BEH]` The ground inherits the world's chemistry. Burial escapes weather and radiation
  and never contamination, so the counter to a poisonous world is decontamination at the boundary
  rather than depth.

## Configuration and extension

- **CON-C21-17** `[A][BEH]` Thresholds are config; the RELATIONS are not. **No configuration may make an
  atmosphere lie about its composition** — a zone with no oxygen may not be configured breathable, and
  a vacuum may not be configured combustible.
- **CON-C21-18** `[A][SYS]` Adding a gas is a **registry row plus thresholds** — never a class, never a
  per-type lang key, never a name on the wire. The cost of a new substance is data. FOR: INV-ATM-18.

## Zones — where a compartment's air comes from

Terms. A **zone** is the sealed volume a port's flood fill finds; it holds one composition. A
**port** is the block whose fill defines it — the T1 oxygen vent or the T4 ventilation port. A
**supply** ADDS gas to a zone (a vent's tank, a separator combining); a **consumer** removes or
converts it (respiration, a breach); regeneration converts CO2 back to O2 and adds nothing.
Ratified by the maintainer on 2026-10-05 (*"Ну да."* to the three-part proposal, and *"сеть должна
использовать свой, отдельный вент без бака (ибо сеть вообще не передаёт кислород, она передает
воздух)"* for the fourth).

- **CON-C21-19** `[T][BEH]` A zone's air is LIVE exactly while the zone is sealed. Every mechanic that
  reads or moves a zone's gases — respiration, regeneration, separation, a recirculator, publishing the
  derived atmosphere — acts on it whether or not any supply is running. A supply that stops is a
  supply that stopped, never a freeze. Pinned by
  `LifeSupportZoneTest#aRoomWhoseVentHasNoOxygenIsStillBreathedDown`,
  `LifeSupportZoneTest#aScrubberTakesItsRoomsCarbonDioxideAndPaysForItInCharges`.
  > **Superseded in part by CON-C21-25 / 26 (design stage).** Its second sentence survives
  > unchanged — every mechanic acts whether or not a supply runs. "Live exactly while sealed" does
  > not: a room's air is live while the room is LOADED, sealed or breached, and a breached room is
  > live precisely because it is losing its air.
- **CON-C21-20** `[T][BEH]` A new zone starts with the air that was in its place: the body's outdoor air
  at its anchor, vacuum where there is none. Sealing creates no gas, and the zone's published
  atmosphere is derived from that air from the moment the zone exists. Pinned by
  `LifeSupportZoneTest#aRoomSealedOnAnAirlessWorldStartsAsVacuum`.
  > **Widened by CON-C21-28 (design stage).** Survives as written for a room found where no room was; a
  > room found where one or more rooms were (a split, a merge) carries their air instead of the outdoors'.
- **CON-C21-21** `[T][BEH]` Gas is created only by a supply, and a supply pays for exactly what it
  adds: the T1 vent spends its tank only for oxygen it admits, at `lifeSupportFluidPerAtmBlock`, and no
  faster than its own rate. A room that lacks nothing costs nothing. Pinned by
  `LifeSupportZoneTest#aVentSpendsNothingOnARoomThatLacksNothing`,
  `LifeSupportZoneTest#aVentWithTwoScrubbersStillSuppliesOxygen`.
- **CON-C21-22** `[T][SYS]` The ventilation network carries regeneration WORK on a zone's air, never
  gas. Its member at a zone is the ventilation port, which has no tank and is powered through the ducts
  by the plants on its network; the T1 vent is not a network member. Pinned by
  `VentilationNetworkTest#aPortIsPoweredThroughItsDuctsByAPlant`.

## Rooms — the air belongs to the room (DESIGN STAGE)

Terms, used for everything below in place of the zone's. A **room** is a closed volume of **cells**
measured from the world. A **port** (the vents above) and a **breather** (a living body that respires)
stand IN a room; neither owns it. A room is **loaded** when every chunk its cells touch is loaded. The
**world** is the game thread's side — blocks, chunks, entities, machines; the **air** is the solver's
side — rooms, gases, verdicts. The design decisions behind the clauses (maintainer, 2026-10-08..12): the
atmosphere is recomputed from the room's closure and loading, not from the vents in it; the room is
first-class (no windowless, doorless room may be breathed in forever); the catch-up covers the machines'
own work and the breathing of bodies; the air runs on its own thread; a cell's seal is its blockstate; an
unobserved body only consumes; offline players count in every room.

- **CON-C21-23** `[PLANNED][BEH]` A room is a **record of the world**, not of a machine: it outlives
  every port and every body in it, and it is saved with the world. A cell belongs to at most one room —
  so a closed volume is findable without a machine standing in it (CON-C21-27).
- **CON-C21-24** `[PLANNED][BEH]` **SEAL-STATE.** Whether a cell lets air through is a function of that
  cell's blockstate ALONE — never of its neighbours, a tile entity or the world. A machine that opens or
  closes a seal (an airlock) does it by changing its blockstate, and its rendering reads the same state;
  so the solver can read copies sent to it rather than the live world (CON-C21-33).
- **CON-C21-25** `[PLANNED][BEH]` A room's life — that it exists, whether it is closed, whether it is
  losing air — is decided by its closure and its loading, never by a machine's power or switch. Power
  gates only a machine's WORK on the room (supply, scrubbing, regeneration).
- **CON-C21-26** `[PLANNED][BEH]` A cell in an unloaded chunk is UNKNOWN: it neither closes nor opens a
  room. A room that is not wholly loaded keeps its last verdict, and nothing acts on it until it is
  loaded again.
- **CON-C21-27** `[PLANNED][BEH]` **No body breathes a closed volume as the outdoors.** A closed volume a
  breather stands in, or a port stands in, is found as a room within one discovery period (a second). A
  block change re-measures rooms that exist; it does not look for new ones.
- **CON-C21-28** `[PLANNED][BEH]` Measuring a room again **conserves its gases**: a part split off keeps
  the partial pressures it had; rooms joined sum their amounts over their summed volume; a room found
  where no room was holds the outdoors' air (CON-C21-20).
- **CON-C21-29** `[PLANNED][BEH]` A room open to the outdoors exchanges with it toward equality, every
  gas at `lifeSupportBreachVentRate`, whatever any machine's power; it stops being a record when it
  equals the outdoors. The exchange CONSERVES against a planet that holds amounts; until the planet does
  (CON-C21-01's extent note), the outdoors is its reference composition.
- **CON-C21-30** `[PLANNED][BEH]` **Time catches up.** A room that was not loaded for Δ ticks of the
  overworld's `getTotalWorldTime()` is advanced over Δ before anything acts on it, by the SAME per-second
  rules the live air runs: its exchange with the outdoors, each port's work out of what that port holds
  (no input arrives over the gap), and the breathing of every body saved in it and every player offline
  in it.
- **CON-C21-31** `[PLANNED][BEH]` **Nothing unobserved is harmed by the air.** A body not loaded, or a
  player not logged in, consumes at the full rate and takes no harm; harm is applied only to a body the
  world is ticking, from the air it then breathes.
- **CON-C21-32** `[PLANNED][BEH]` A player logged out in a room is a breather of that room at the full
  rate, in every room.
- **CON-C21-33** `[PLANNED][BEH]` **The air is solved off the game thread and published whole.**
  Everything the world decides from the air reads the latest published view; how far that view lags the
  world is bounded and stated (a watermark of the last tick it reflects). The solver reads no live world
  object — it holds its own copies, sent to it. No outcome depends on wall-clock time: the air advances
  only by the ticks the world sends it, so a paused game is a paused air. Finding every closed volume near
  every breather (CON-C21-27) thereby costs the game thread none of its tick.
- **CON-C21-34** `[PLANNED][BEH]` Everything the world sends the air periodically is phased by the
  SENDER's identity, never by a shared `% N` clock, and a burst — a chunk load, an explosion — is bounded
  per tick, never paid in one; that is what keeps the lag CON-C21-33 states bounded.

## Status

The registry, the composition and the predicates are built (CON-C21-03 and 07 are built and pinned;
04 is honest — an unknown substance is dropped rather than kept under a null key). 01 and 05 are
half-built: the state answers, and the types still carry their own flags.

| clause | state | evidence |
|---|---|---|
| 01 one representation | **half-built** | the composition is a sparse map over the gas registry and every derived question is answered from it (`AirState.deriveAtmosphere`). The hazard-type singletons are a table; what survives is one concrete `Atmosphere` carrying `isBreathable`/`allowsCombustion` as hand-assigned `final` fields, unchangeable after construction. A second answer still exists — immutable and in one place — until the flags become derivations of a composition |
| 02 presence is availability | **BUILT `[T]` over a gas giant only, except depletion. VIOLATED for every other body**: the only harvest mechanic, `EntityStationDeployedRocket`, is gated to giants twice (`offeredGases`, and the in-orbit check), so a gas in a rocky world's air is present and unextractable. Maintainer ruled 2026-10-05 to close it with a new mechanic, not by narrowing this clause | the offer is `DimensionProperties.getHarvestableGases` (`DimensionProperties.java:694`): every gas present in the world's air with a registered fluid, and nothing else — pinned by `server/MissionGasCompletionTest.aHarvesterIsOfferedExactlyTheGasesTheAirHolds` (a one-nano-atm trace is offered; empty air offers nothing). The yield follows the partial pressure: `GasHarvest.missionSeconds` draws at intake × pp, pinned by `unit/GasHarvestTest`. There is no second list of harvestable or spawnable gases, no `AtmosphereRegister`, no planet NBT `fluids` key and no planet-level XML `<gas>`; a file carrying `<gas>` outside `<atmosphere>` is refused for that body (`integration/PlanetFileStatesItsAirTest`). **Open**: the harvest does not deplete the air (a planet has no amounts yet; maintainer 2026-10-04); there is no depth axis, the pp is the body's reference level (maintainer 2026-10-04); the `onOrbitReached` → `GasHarvest` wiring is unpinned |
| 03 a gas earns its slot | **built `[T]`** | `GasRegistry` — eleven substances, each declaring at least one role, enforced at registration and pinned by `unit/AtmospherePredicatesTest.everyGasHasAJobAndEveryHazardHasAGas`, which also refuses a hazard role no substance can raise |
| 04 floor is honest | **BUILT, both halves `[T]`** | SUBSTANCE: an unknown gas is dropped rather than stored under a null key (which would count silently toward the pressure). UNITS: nano-atmospheres in a `long`, so the resolution and range are STATED and a Martian trace survives being stored (`[V]` `AirState.java:75,102`, amounts kept as `long`, unrounded) — the range is pinned by `unit/AirStateTest.oneCompositionHoldsAGasGiantAndATraceAtTheSameTime` (falsified by summing through an int). What is gone is absent from the composition, the pressure and every predicate alike (`.whatIsGoneIsAbsentEverywhereRatherThanKeptAsAZero`) |
| 05 predicates, not flags | **half-built** | every ignition path asks `AtmosphereHandler.allowsCombustionAt`, which reads the air; no consumer branches on the type's combustion FLAG (the chest decides whether to spend its tank by asking about the hazard). The two flags survive as final fields of each `Atmosphere` value and still pick the blob default |
| 06 monotonicity | **half pinned `[T]`** | the poison half: `unit/AtmospherePredicatesTest.aPoisonMakesAirToxicAndDrawingItOffClearsIt` (adding and removing a poison). The oxidiser half — the sweep across the configured bands — needs a loaded configuration and is **unpinned** |
| 07 oxidiser, not breathability | **BUILT and pinned `[T]`** | `AirState.allowsCombustion()` reads the OXIDISER role against its own config band. Pinned on the production path by `server/CombustionFollowsTheOxidiserTest`; the gap BETWEEN the two bands needs a loaded configuration and is unpinned. Falsified both ways: tying the band back to breathing, and making the handler obey the label again |
| 08 immunity is the entity's | **BUILT `[T]`** | the entity is asked — suit, creative, riding, grace, android — and what the SUIT is asked is a HAZARD rather than a named atmosphere (`api/armor/IProtectiveArmor.protectsFrom`). The piece requirement is the strictest active hazard, which reproduces all fourteen hand-written answers with no exception needed. Pinned by `unit/AtmosphereHazardTableTest` and the two armour contract tests, which sweep the hazard enum rather than a written-out list. **One half deliberately not taken**: immunity is all-or-nothing per zone rather than per hazard, because per-hazard protection changes what a partially suited player suffers |
| 09 the name is derived | **BUILT `[T]`** | nothing branches on a name and nothing looks one up. The detector watches an `AtmosphereAssertion` and persists that (pinned by `server/AtmosphereDetectorWatchesAStatementTest` — a poisonous room, a condition no named atmosphere could express — falsified by making the statement never hold); the wire carries a readout; there is no name-to-atmosphere map. What a player sees as the atmosphere's name is literally the ordered set of statements true of it |
| 10 one model, planets too | **BUILT for the outdoor air `[T]`** | a planet holds an `AirState` (`DimensionProperties.air`); pressure and `hasOxygen()` are readouts of it, and below the planet's own heat/pressure rungs its label comes from `AirState.oxygenRung` — the band a zone is judged by. Outdoor combustion asks the planet's air too (`AtmosphereHandler.allowsCombustionAt`). Pinned by `server/ATraceOfOxygenOutdoorsReadsAsTooLittleTest` (a trace reads `lowO2`, not `NoO2`) only; the `highO2` side (an oxygen world past the ceiling) is unpinned (setting the band needs a loaded configuration). **Not yet**: the zone↔outdoors EXCHANGE (a breach venting a room into the planet's air) |
| 11 / 11b derived where unauthored; authored once, then NBT | **BUILT `[T]`** | the derivation rule is one function, `BodyAtmosphere.derive`, applied at creation by every creator through `realizeAtmosphere` (procedural, XML, Earth). 11b: the saved air is the authority after creation, INCLUDING for a world re-read from its planet file — `copyData` carries `air` across that reload (otherwise the file would re-derive Earth's mix from the oxygen flag). Every XML body must state its bulk. `<atmosphere>` states the gases whole or `copyOf` another body (a copy, resolved in a second pass); cycle / dangling / ambiguous refuse the load; pinned by `integration/PlanetFileStatesItsAirTest` (6, each red-witnessed) |
| 12 the client decides nothing | **BUILT and structural** | the client is handed a READOUT — a pressure, a breathable flag, a warning key, the statements that hold — and has nothing to interrogate. A client handed a NAME would look it up in the registry and so hold a model it could ask questions of; this removes the possibility rather than the practice |
| 13 cheap, phased sync | **BUILT and pinned `[T]`** | once a second per player, phased on the PLAYER's own age rather than on a world clock. The decision is a named function (`AtmosphereHandler.isSyncTick`) so it can be asked without a world, and `unit/AtmosphereSyncIsCheapAndPhasedTest` pins all three halves: the rate, that no two ages inside a period collide, and — the mirror that stops "phasing" from meaning "never" — that every offset is still served. Falsified by removing the phase: the rate and the collision tests go red together, the mirror correctly stays green |
| 14 a toxin harms by its own limit | **BUILT `[T]`** | a dose (concentration × time) of Σ pp/limit, harm past 30 limit-seconds, clearance with a 300 s half-life, full suit only, CO2 toxic past 5 %; pinned by `unit/PoisoningTest`, `unit/AtmospherePredicatesTest.poisonsAddUpAndCarbonDioxidePastFivePercentIsOne`, `server/PoisonedAirTest`. Outdoors the planet's air is read (the TOXIC statement answers there too). The suit half — a whole suit with a supply takes in no dose, the same air doses him bare — is pinned by `client/VacuumAndSuitClientGroupTest.aSealedSuitKeepsPoisonedAirOut`, and a helmet and chest without legs and boots being refused by `…aHelmetAndTankWithoutTheRestOfTheSuitDoNotKeepPoisonedAirOut`. Five poisons are modelled, each with its own limit; `worstToxin`/`isToxic` are pinned by `unit/AtmospherePredicatesTest.aPoisonIsJudgedAgainstItsOwnLimitAndNotAgainstTheAirAroundIt` |
| 15 corrosion through the damage budget | **unimplemented** | no corrosion; blocked on a block-damage budget, exactly as C12 HEAT-11's block-damage rung is |
| 16 the ground inherits the chemistry | **unimplemented** | no surface chemistry of any kind |
| 17 config may not make air lie | **`[A]`, undecided** | the intent: no setter lets anyone take the SHARED value every zone and planet resolves to and declare it breathable, and no interface lets a dependent mod assert the same by implementing it. `api/atmosphere/Atmosphere` has no setters: `isBreathable` and `allowsCombustion` are `final` fields set by its constructor (`Atmosphere.java:59-70`). No test fails if a setter returns. **Not fully honest even in intent**: the two flags are assigned by hand at construction, so an atmosphere can still be BUILT saying the wrong thing; it just cannot be changed afterwards. The last step is the flags becoming derivations of a composition |
| 18 a new gas is data | **BUILT for the atmosphere half** | a new atmosphere is not a class, a registry row, a lang key or a wire name. It is what a COMPOSITION derives to. Adding a substance is what CON-C21-18 says it should be: a row in the gas registry and a threshold |
| 19 a zone lives while sealed | **BUILT `[T]`** | `SealedZone`; both ports answer `isMaintainingAtmosphere()` = sealed. Pinned by `server/LifeSupportZoneTest.aRoomWhoseVentHasNoOxygenIsStillBreathedDown` |
| 20 a new zone holds what was there | **BUILT `[T]`** | `SealedZone#register` / `#airAround`. Pinned by `server/LifeSupportZoneTest.aRoomSealedOnAnAirlessWorldStartsAsVacuum` (Luna). The zone↔outdoors exchange of an OPEN room is row 29 |
| 21 a supply pays for what it adds | **BUILT `[T]`** | `TileOxygenVent#supplyOxygen`. Pinned by `server/LifeSupportZoneTest.aVentSpendsNothingOnARoomThatLacksNothing`. There is no mode in which a vent burns gas it does not add |
| 22 the network carries work, through its own port | **BUILT `[T]`** | `TileVentilationPort` is the sink; `LifeSupportNetwork#payPortUpkeep` powers it. Pinned by `server/VentilationNetworkTest` (all four scenarios on the port; `aPortIsPoweredThroughItsDuctsByAPlant` against a port that ignores payment) |
| 19/21 at T2 — the scrubber | **BUILT `[T]`** | a scrubber acts on its vent's LIVE zone (19) and removes CO2 without returning gas, so it creates none (21); its cartridge pays one charge per `lifeSupportScrubberCo2PerCharge` of what it took, and the vent's supply rate does not read the scrubbers. `TileCO2Scrubber#absorb`, `TileOxygenVent#scrub` (atmosphere-oxygen MECH-ATM-08). Pinned by `server/LifeSupportZoneTest.aScrubberTakesItsRoomsCarbonDioxideAndPaysForItInCharges` and `.aVentWithTwoScrubbersStillSuppliesOxygen`. A sealed room whose chunk LOADS keeps its air: the off-thread fill's "not yet" is not read as "open" (pinned by `server/LifeSupportZoneTest.aSealedRoomKeepsItsAirAcrossAChunkReload`) |
| 23 a room is a record of the world | **not written** | a zone exists only as a port's blob (`AtmosphereHandler#blobs`, keyed by `IBlobHandler`); breaking the port deletes the zone and its air outright (`TileOxygenVent#invalidate` unregisters the blob). Already right, and the evidence this is buildable: the air is persisted by value, never by reference (`SealedZone#writeToNBT` / `#readFromNBT`) |
| 24 SEAL-STATE | **not written** | `SealableBlockHandler#isBlockSealed` reads the world: `chunkExists`, an airlock's neighbours (`#checkDoorIsSealed`), a neighbour-dependent collision box (`#isFullBlock(world, pos, …)`). Already a function of the state: the allow / ban lists by block and material. Breaks the public `IAtmosphereSealHandler#isBlockSealed(World, BlockPos)` |
| 25 life by closure, work by power | **written the other way round** | `SealedZone#tick` drops the zone when the port cannot hold it (`canHold` = switched on and paid), and `AtmosphereBlob#addBlock` will not fill at all unless `canFormBlob()`. Already right: CON-C21-19's second sentence — every mechanic acts on the air whether or not a supply runs — and the vent's supply and scrubbers are already the only things its power buys; dropping a zone for want of power is not a breach, so its gases stay with the vent until the seal is retaken |
| 26 unloaded is unknown | **not written** | an unloaded wall reads as open |
| 27 no closed volume breathed as the outdoors | **not written** | outside a port's zone `AtmosphereHandler#getAirAround` answers the planet's air, so a closed room with no port breathes forever on a breathable world |
| 28 measuring conserves | **partly written, in a different shape** | a split today keeps only the part joined to the port (`AtmosphereBlob#removeBlock` → `removeAllNodesConnectedTo`), and the other part's air simply stops existing; two zones never merge (`canBlobsOverlap` false). The composition being intensive (CON-C21-01's note) is what makes the conserving rule one line |
| 29 exchange toward the outdoors | **written for today's zone `[T]`** | `SealedZone#ventBreachedAir` moves every gas toward `AtmosphereHandler#getOutdoorAir` by at most the rate a second, both ways, whatever the power. Pinned by `server/BreachAndIsolationTest.aBreachedRoomExchangesItsAirWithTheOutdoorsInsteadOfLosingItToBookkeeping`. Not yet: the record ending when it equals the outdoors (a room record, 23) and conservation against a planet that holds amounts |
| 30 time catches up | **not written** | nothing records when a zone was last observed, and the breach state is not saved (`SealedZone`, `breached` "not persisted"). The doctrine and its clock exist (`space-model.md`, lazy catch-up; C16) |
| 31 nothing unobserved is harmed | **already true, for the trivial reason** | nothing unobserved does anything to the air today. The clause has content only once 30 and 32 exist |
| 32 offline players breathe | **not written** | a player logged out breathes nothing and takes no harm |
| 33 solved off-thread, published whole | **not written; the fill is off-thread in the wrong way** | the flood fill already runs on a pool (`AtmosphereBlob#run`, `atmosphereFillPool`) — but reads the LIVE world from it (`world.getBlockState`, `chunkExists`); every other read and write of the air is on the game thread, and finding a zone by position scans every zone (`AtmosphereHandler#getBlobContaining`) |
| 34 phased by identity | **half-written** | the player readout is (row 13); respiration is phased on `ticksExisted`, which vanilla does not save, so a chunk's entities share a phase |

## Violations that matter most

Which clauses are red *because the current design says the opposite*, since those are the ones a
half-implementation would quietly re-create:

- **CON-C21-05 / 17** share a root: the singleton's mutable flags. Deleting the setters is not enough;
  the answer has to come from the state or the second source returns.
- **CON-C21-15** is blocked outside this contract (a block-damage budget), the same block C12 HEAT-11
  carries.
- **CON-C21-02** is violated outside gas giants (see the table).

## Reading notes on individual clauses

1. **CON-C21-03 is stated in the checkable form**: *every registry row declares its roles, and every
   declared role is read by at least one predicate.* A clause "a gas earns a slot when a mechanic
   reads it" is a statement about intent and unfalsifiable.
2. **CON-C21-04 is not a units clause.** What is contractual is that the floor is HONEST — a trace
   below it reads as absent everywhere, rather than as present in one path and absent in another. The
   unit (nano-atm in a `long`) is an implementation choice.
3. **CON-C21-13**: the packet is per-player, so a periodic replacement must carry its own phase —
   jitter the phase, never the period.
4. **CON-C21-27 on a breathable world — the numbers.** At the defaults one breather turns 2000
   ppm·block of O2 into CO2 per second (`lifeSupportRespirationRate`), a room stops being breathable
   below 160 000 ppm O2 (`lifeSupportMinPartialO2`) and CO2 turns toxic past 5 %: about **25 s per block
   of volume per breather**. A 1×2 hole a player walls himself into for the night: ~50 s. A 5×5×3 room:
   ~30 min. A 10×10×4 house: ~2.8 h. On the overworld this is new, and it applies to every shelter.
   Ruled 2026-10-12: *"да, так задумано"* — the numbers stand, on the overworld too.
5. **CON-C21-27 — every breather finds rooms, mobs included.** Narrowing the finders to players and ports
   would keep every built room and drop the caves, but a mob that stands in a sealed cave pocket would
   otherwise be spared. Ruled 2026-10-12: *"Если сделать только игроков, можно будет перевозить скот на
   корабле без СЖО вообще. А отсутствие мобов в замкнутых полостях.. так-то разумно, им же буквально
   неоткуда там взяться. Да, это переделка ванили, но это норм. При этом бОльшая часть подземки остается
   дышабельной, ведь в основном это система пещер, которая где-то да находит выход. Ну и да, дома придется
   делать с вентотверстиями. Даже блок отдельно делать не надо - сойдет любой неполный блок, например
   железные прутья."* A house is ventilated by any non-full block in its shell (iron bars) — which
   CON-C21-24 already makes an open cell; a room so opened exchanges with the outdoors (CON-C21-29) and,
   once equal, is the outdoors.
6. **The size bound and the sky test.** The fill is bounded (`AtmosphereBlob#run`, `maxSize`); a closed
   volume larger than the bound is answered OPEN, so a big enough sealed hall would be breathed as the
   outdoors forever — the exploit CON-C21-27 forbids, at scale. A fill that reaches a cell OPEN TO THE SKY
   is open at once, which makes most outdoor fills short and lets the bound be raised far on the air's
   thread; what stays is a bound on enclosed volume, a cost limit that belongs to the fill and is named so.
   The sky test is NOT `canSeeSky` (ruled 2026-10-12: *"canSeeSky() не является признаком "top of the
   world""*): vanilla's height map is a LIGHT map — `Chunk#canSeeSky` is `y >= heightMap[…]`, and
   `Chunk#relightBlock` builds `heightMap` from light opacity, so a cell under a glass roof "sees the
   sky" while the roof seals it. The air therefore keeps its OWN height map per column, in SEAL terms
   (CON-C21-24): the highest sealing cell of the column, built from its chunk copies; a cell above it is
   open to the sky, and a fill reaching one answers OPEN at once.
7. **CON-C21-29 and a finite planet.** A planet's air is finite and every exchange conserves
   (CON-C21-01's extent note), so the clause is worded conditionally: the exchange conserves against a
   planet that holds amounts, and until the planet does the outdoors is a reference.
8. **Rooms aboard an assembled VS ship** are outside this design (the world-frame / subspace-frame
   defect the handler documents). The world sends no find-request for a body ABOARD a ship (C8); such a
   body keeps today's answer until the frame is fixed.
9. **A player offline in a room that later splits or merges**: his breathing is resolved to the room
   that holds his logout cell at the time it is applied — keyed by position, not by a room id that can
   die. **A dimension unloaded whole** (a planet nobody is on): all its rooms are unloaded and catch up
   on the overworld clock when it loads; nothing special.
10. **Contradictions checked for the room clauses**: C12 (heat reads compartment air — it must read the
    published view, no clause changes), C13 (PRES clauses say nothing about air), C1 (the room record is
    new saved data — C1 must list it when it lands), C4 (new keys: the per-tick snapshot and change
    budgets). None found.

**Edge cases, each classified**: a gas that is both fuel and toxin (answerable from code — roles
are a set, not a choice) · an atmosphere with an oxidiser that is not oxygen (needs a ruling only when
one is added; CON-C21-07 is already worded for it) · a compartment open to a planet's air (answerable:
two instances, one boundary — the sealing rules already decide which applies) · a body with no
atmosphere at all (answerable: an empty composition is a vacuum, and CON-C21-04's floor makes that
the same answer everywhere).
