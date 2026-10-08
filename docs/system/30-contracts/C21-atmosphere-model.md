---
id: C21-atmosphere-model
kind: contract
subject: what an atmosphere IS, and what may be derived from it
owners: [atmosphere-oxygen, dimension-planets]
clauses: CON-C21-01 .. CON-C21-18
ratified: 2026-08-19
confidence: mixed — a clause is `[A]` unless the CURRENT code satisfies or violates it, which the
  status table cites per clause
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

## Status

The registry, the composition and the predicates are built (CON-C21-03 and 07 are built and pinned;
04 is honest — an unknown substance is dropped rather than kept under a null key). 01 and 05 are
half-built: the state answers, and the types still carry their own flags.

| clause | state | evidence |
|---|---|---|
| 01 one representation | **half-built** | the composition is a sparse map over the gas registry and every derived question is answered from it (`AirState.deriveAtmosphere`). The hazard-type singletons are a table; what survives is one concrete `Atmosphere` carrying `isBreathable`/`allowsCombustion` as hand-assigned `final` fields, unchangeable after construction. A second answer still exists — immutable and in one place — until the flags become derivations of a composition |
| 02 presence is availability | **BUILT `[T]` over a gas giant only, except depletion. VIOLATED for every other body**: the only harvest mechanic, `EntityStationDeployedRocket`, is gated to giants twice (`offeredGases`, and the in-orbit check), so a gas in a rocky world's air is present and unextractable. Maintainer ruled 2026-10-05 to close it with a new mechanic, not by narrowing this clause | the offer is `DimensionProperties.getHarvestableGases` (`DimensionProperties.java:694`): every gas present in the world's air with a registered fluid, and nothing else — pinned by `server/MissionGasCompletionTest.aHarvesterIsOfferedExactlyTheGasesTheAirHolds` (a one-nano-atm trace is offered; empty air offers nothing). The yield follows the partial pressure: `GasHarvest.missionSeconds` draws at intake × pp, pinned by `unit/GasHarvestTest`. There is no second list of harvestable or spawnable gases, no `AtmosphereRegister`, no planet NBT `fluids` key and no planet-level XML `<gas>`; a file carrying `<gas>` outside `<atmosphere>` is refused for that body (`integration/PlanetFileStatesItsAirTest`). **Open**: the harvest does not deplete the air (a planet has no amounts yet; maintainer 2026-10-04); there is no depth axis, the pp is the body's reference level (maintainer 2026-10-04); the `onOrbitReached` → `GasHarvest` wiring is unpinned |
| 03 a gas earns its slot | **built `[T]`** | `GasRegistry` — eleven substances, each declaring at least one role, enforced at registration and pinned by `unit/AtmospherePredicatesTest.everyGasHasAJobAndEveryHazardHasAGas`, which also refuses a hazard role no substance can raise |
| 04 floor is honest | **BUILT, both halves `[T]`** | SUBSTANCE: an unknown gas is dropped rather than stored under a null key (which would count silently toward the pressure). UNITS: nano-atmospheres in a `long`, so the resolution and range are STATED and a Martian trace survives being stored — pinned by `unit/AirStateTest.aTraceOnAThinWorldKeepsItsDigits` (falsified by millionths: Mars reads 0.118% instead of 0.13%) and `.oneCompositionHoldsAGasGiantAndATraceAtTheSameTime` (falsified by narrowing the store to an int). What is gone is absent from the composition, the pressure and every predicate alike (`.whatIsGoneIsAbsentEverywhereRatherThanKeptAsAZero`) |
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

**Edge cases, each classified**: a gas that is both fuel and toxin (answerable from code — roles
are a set, not a choice) · an atmosphere with an oxidiser that is not oxygen (needs a ruling only when
one is added; CON-C21-07 is already worded for it) · a compartment open to a planet's air (answerable:
two instances, one boundary — the sealing rules already decide which applies) · a body with no
atmosphere at all (answerable: an empty composition is a vacuum, and CON-C21-04's floor makes that
the same answer everywhere).
