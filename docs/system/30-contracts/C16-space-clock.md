---
id: C16
covers: which clock the space subsystem reads, on either side; a world's time of day and the time skip
confidence: CLOCK-1..5 implemented (CLOCK-3 is pinned at the unit tier plus one client e2e); CLOCK-6..9 design stage
owner-subsystem: space (SpaceSubsystem, SpaceClockSync)
see-also: [C15 (a cell's address and frame — ADDR-9 says distance exists only AT A TICK; this says WHOSE tick), C13 (space presence), C1 (NBT persistence — the clock is stored with the ship ledger)]
---

# C16 — The space clock, clauses CLOCK-1..CLOCK-9

Maintainer direction, 2026-08-04: *"Структурную половину берем сейчас и закрываем баг
целиком."*

**The failure this prevents.** A jump's aim evaluated on one clock and its arrival priced on another
stands the ship off from where the target was, by the difference between the two clocks (measured:
14 912 ticks, ~12 minutes of world time).

## Terms

- **space clock** — the single monotonic counter against which every space-subsystem quantity that
  depends on time is evaluated: body ephemerides, transit progress, arrival standoff, descent
  proximity, drive charge, crystal-entry freshness. The running server's OWN counter
  (`ServerState.spaceTick`, one per server lifetime), advanced once per server tick and persisted with
  the ship ledger; read through `SpaceSubsystem.spaceClock()` `[V]`.
- **world clock** — a `World`'s own `getTotalWorldTime()`. NOT the space clock: Stellurgy
  wraps every dimension except the overworld in a `WorldInfo` whose counter advances only while that
  dimension ticks (`StellurgyDimensionWorldInfo.java`, `PlanetWeatherManager.shouldWrap`
  excludes dim 0 only) `[V]`. Measured divergence in one playtest save: slot dim 98 at
  `worldTotalTime = 18 492` against the overworld's 33 331 — **14 912 ticks** `[V]`.
- **proxy clock** — `Stellurgy.proxy.getWorldTimeUniversal(id)`. Its declared meaning is "the
  total time of dimension `id`"; `CommonProxy` honours that (`CommonProxy.java`) `[V]` and
  `ClientProxy` does not (`ClientProxy.java` returns the CLIENT's current world) `[V]`.

## Clauses

- **CLOCK-1 (one clock)** `[A][SYS]` Every space-subsystem evaluation of a time-dependent quantity reads
  the space clock and nothing else. A per-dimension world clock, a render clock, and "the total time
  of whatever world I am in" are different quantities that merely look like this one.
  This is structural: the space clock is not derived from any world, so there is no world clock in
  the picture to be accidentally right about.
  **Player form:** two things the game computes about the same moment agree about when that moment is. Pinned by `AimAndArrivalShareOneClockTest#theAimMovesWithTheSpaceClockAndWithNoOtherClock`. FOR: CLOCK-2.
- **CLOCK-2 (the aim and the arrival agree)** `[A][BEH]` The tick a jump's aim is evaluated at and the
  tick its arrival is priced at come from the same source, so the only difference between them is the
  flight's own predicted duration. **Falsifiable:** drive a non-space clock away from the space clock
  and the aim must not move. **Player form:** a jump aimed at a body arrives beside that body. Pinned by `AimAndArrivalShareOneClockTest#theAimMovesWithTheSpaceClockAndWithNoOtherClock`.
- **CLOCK-3 (side-agnostic)** `[A][SYS]` The space clock is readable on both logical sides and answers the
  same value on each, to within the sync period. No caller needs to know which side it is on. On the
  server it is the subsystem's own counter; on a client it is
  a synced baseline advanced locally (`SpaceClockSync.java`), one copy per connection, owned by the
  client's `ServerView` and read through `proxy.clientSpaceClock()` (`ClientProxy.java`) `[V]`. A
  client that has never been synced answers 0 and says so through `hasSync()` `[T]`; a new connection
  starts unsynced because its view is new. Pinned by `SpaceClockSyncTest#aClientNobodyHasToldIsDistinguishableFromOneToldItIsTickZero`, `SpaceClockSyncTest#theAnswerIsTheBaselinePlusTheClientTicksSinceIt`, `SpaceClockSyncTest#aLaterBaselineWinsEvenWhenItMovesTheAnswerBackwards`, `SpaceSubsystemClientSyncGroupTest#theClientsSpaceClockFollowsTheServers`. FOR: CLOCK-2.
- **CLOCK-4 (a proxy accessor is not the space clock)** `[V][SYS]` No space-subsystem code may read
  `proxy.getWorldTimeUniversal`. The accessor's client implementation does not honour its argument,
  and dimension time in Stellurgy is per-dimension **by design** (`perDimWorldInfo`, working beds), so
  "universal" describes nothing about it. Its other consumers (mission timers, satellite gates,
  station transitions) are outside this contract. Planet orbital theta reads the space clock. FOR: CLOCK-2.
- **CLOCK-5 (the subsystem owns the counter)** `[A][SYS]` The space clock is the subsystem's own state,
  not a reading of anything else. Four consequences, each separately falsifiable:
  1. **It advances by itself**, once per server tick, whether or not the controller was built — it is
     never derived from an object that can be absent, so it cannot answer **tick zero** because a
     world was not resolvable. The only way for the controller to be absent is Valkyrien Skies
     missing from the classpath, and VS is vendored into this jar; the clause still holds and is the
     reason the advance sits above the null return. See CLOCK-5.1.
  2. **Two writers, both forward.** The ADVANCE, exactly once per server tick
     (`SpaceSubsystemEvents.onServerTick` calling `ServerState.advanceSpaceClock()`; the subsystem's other
     server-tick handler only reads), and the TIME SKIP (CLOCK-7), which moves the clock forward by the
     amount CLOCK-7 fixes at the moment a skip happens. Nothing else writes it, and nothing moves it back.
     A second per-tick writer would run the clock at double the tick rate and silently change what every
     persisted tick value means. (Until the skip was ruled, 2026-10-23, the advance was the only writer.)
  3. **No world's AGE moves it, and moving it ages no world.** A world's total time
     (`getTotalWorldTime`) is not the space clock, and a skip leaves it where it was: every vanilla and
     third-party gate keyed on `totalTime % N` stays where it was. A world's TIME OF DAY is not a clock at
     all — it is read off the space clock (CLOCK-6), so it is not moved by anything but the space clock.
  4. **It is durable, and MONOTONIC — deliberately not part of any snapshot.** The counter is written
     to the ship-ledger store on every dim-0 save and read back on server start. It is written by its
     own writer (`ShipLedgerData.setClock`), **outside** `replaceAll`'s all-or-nothing fleet write, and
     the write-out happens in a `finally` so a fleet step that refuses or fails cannot take the clock
     with it.
  **Player form:** restarting the server does not age the fleet, and a jump's ETA survives it. Pinned by `AimAndArrivalShareOneClockTest#theClockAdvancesWithoutBeingTold`, `SpaceClockIsTheSubsystemsOwnTest#neitherClockMovesTheOther`. FOR: CLOCK-2.
- **CLOCK-6 (a world's time of day is its body's turn)** `[A][BEH]` The time of day at a place on a world
  that is a body — the overworld included — is the LOCAL SOLAR TIME at that place's longitude (its block x
  over the body's radius, as the space model places it) at the space clock: noon when the body's star
  stands on that meridian, sunrise a quarter of a solar day before. A body's `rotationalPeriod` IS its
  solar day; the space model turns the body by the sidereal period that solar day and the body's orbit
  give (1/sidereal = 1/solar + 1/orbital). A tidally locked body's time of day stands still. No world keeps
  a time-of-day counter of its own. Both layers follow the place: what a client draws (the sky, the sun,
  the light) for the place its player stands, and what the server decides (the sky light a block reads,
  daytime, spawning, the daylight sensor, a bed) for the place it is decided at. Vanilla's `doDaylightCycle`
  rule has no effect on a body's time of day. Vanilla's day counter (`worldTime / 24000`) keeps vanilla's
  arithmetic: it matches a body whose solar day is 24 000, the overworld's. **Player form:** the sky over a planet shows where its star really is, and two players on two
  worlds agree on how much time has passed. FOR: maintainer 2026-10-23, *"Вообще, не должно "время мира"
  расходиться с космологическим. Вот как планета повернута, такое и время"* (a world's time must not drift
  from the cosmological one; as the planet is turned, so is the time).
- **CLOCK-7 (the time skip)** `[A][BEH]` A skip moves the space clock forward (CLOCK-5.2), and with it every
  world's time of day (CLOCK-6). It happens in two ways:
  1. **Sleep.** With the server-wide mode (the default), when every non-spectator player on the server is
     asleep, in whatever worlds; with the per-world mode, when every non-spectator player of one world is.
     Either way the clock moves to the next local dawn of the world where the sleep completed — with
     several, the earliest of their dawns — and the weather is reset in each of those worlds. A world
     whose skip is locked (`TimeSkipPolicy`: `allowTimeSkipOnPlanets`, `allowTimeSkipOnOverworld`, both on by
     default) never completes a sleep. In a world that is not a body (a station, a slot world, hyperspace)
     there is no night: a bed there may be slept in at any time, and its sleeper counts. A ship in transit is
     carried through the skipped span: the blocks it has flown grow by its speed times the span, up to the
     priced distance, while its crew counts as online; the ship's own systems (heat, air, power) are world
     simulation and are not carried, as nothing a world simulates tick by tick is.
  2. **`/time add` and `/time set`**, an operator's command, from any world: `add N` moves the clock N
     ticks; `set T` moves it to the next moment that world reads T. Never backwards (CLOCK-5.4).
  **Player form:** when everyone has gone to bed the night is over — on every world at once, because the
  universe turned — and a ship, a satellite and a planet's orbit are where that much time puts them.
  FOR: maintainer 2026-10-23 — the skip ages the universe (*"Игровой пропуск"*), sleeping in any world
  (*"Сон в любом мире"*), the mode a config with the server-wide one by default (*"в конфиге можно
  настроить варианты: для пропуска ночи все должны спать (во всех мирах), или спать должны все в мире …
  По-умолчанию первое"*), `/time add` too (*"да"*), the amount the skipped world's own (*"да, давай
  относительно планеты"*), the whole universe in either mode (*"В режиме "весь мир" - стареет вся
  вселенная, да"*).
- **CLOCK-8 (a client is told at a skip)** `[A][SYS]` At a skip every connected client is sent the new
  clock at once, not at its next periodic sync. FOR: CLOCK-3, CLOCK-6 — a client's sky and its space clock
  must not disagree for up to a sync period.
- **CLOCK-9 (a skip is time passing)** `[A][SYS]` Every deadline the skip carries the clock past has passed
  — a jump spool, a retry cooldown, a stored cell's age — and every lazy accrual (a satellite's life) catches
  up over the skipped span. No reader of the clock may assume it moves one tick at a time unless it
  integrates per tick by design and says so. Gates on the time of day (`getWorldTime() % N`) move to the
  world's total time, which a skip does not touch. FOR: CLOCK-7.

## Witnesses

| clause | witness |
| --- | --- |
| CLOCK-1, CLOCK-2 | `AimAndArrivalShareOneClockTest.theAimMovesWithTheSpaceClockAndWithNoOtherClock` — two legs: a positive control that moves the SPACE clock and requires the aim to follow, then the contract leg that moves only a non-space clock and requires it not to. Red against its own revert at **29 718 blocks** of drift against an 8-block allowance; green with the fix |
| CLOCK-3 | `SpaceClockSyncTest` (baseline + local advance, un-synced is distinguishable from tick 0, a later baseline wins including backwards; a copy is owned by one connection's view and dropped with it, which `PlanetWorldOnTheClientGroupTest` reads as "no view after leaving"), plus `TheClientKnowsTheSpaceClockE2ETest` — a real client, required to have been told before anything else is asked, then to FOLLOW a server clock jumped a million ticks rather than merely tick along beside it |
| CLOCK-4 | no behavioural witness and none is possible — it is a prohibition on reading an accessor, which is a review rule, not an observable. The observable consequence is CLOCK-2's |
| CLOCK-5.1 | `theClockAdvancesWithoutBeingTold` (it moves at all). **The ADVANCE-SITE half is UNWITNESSED**: moving the increment below the null return would go green everywhere, because no server can be booted without the controller. Recovering it needs a SEAM, not a flag — `advanceClock()` and `onServerStarted(live)` both already take the subsystem as a parameter, so a unit test passing `null` would pin the order |
| CLOCK-5.2 | the RATE leg of `theClockAdvancesWithoutBeingTold` — the two clocks' DELTAS over one window must match (a rate comparison, not a value one, so it does not couple them). **Red at `space=198 world=99`** with a second writer added to the other server-tick handler |
| CLOCK-5.3 | `neitherClockMovesTheOther` — both directions, each with its own arrangement assertion that the clock it drove really jumped. The class owns its server: one leg hammers the overworld's counter by 20 M ticks, which is exactly the collateral this clause removes from shared servers. **Red at "the overworld jumped 20 000 000 and the space clock moved 20 000 001"** with `spaceClock()` re-pointed at the overworld |
| CLOCK-5.4 | no restart witness: the durability half is `[V]` only — staged on save at `SpaceSubsystemEvents.java:164-169`, written and read under `spaceClock` at `ShipLedgerData.java:248,276`, restored on start at `SpaceSubsystem.java:302-303`. The unit tier pins the decisions around it: `ShipLedgerDataTest.aSaveWithNoClockInItReadsBackAsTickZero` and `.aRefusedFleetWriteDoesNotRollTheClockBack` |

| CLOCK-6..9 | not built — design-stage clauses, ratified before code; the distance below |

## CLOCK-6..9 — the distance from the code (design stage)

What the code already does that these rest on: orbits already run on the space clock
(`DimensionProperties#updateOrbit`); the space model already turns every body by it
(`BodyRotation#angleAt`, `SatellitePlacement#rotationOf`); a satellite's life already catches up over any
span (`SatelliteLife#bringUpTo`); a planet's celestial angle is already a pure function of a time and a
period (`WorldProviderPlanet#calculateCelestialAngle`); the skip locks exist (`TimeSkipPolicy`).

Not written yet:

- **CLOCK-6** — a world's time of day is its own counter (`PlanetWeatherState` under `perDimWorldInfo`,
  advanced by vanilla only while the world ticks; the overworld's is level.dat's), and its phase is not the
  space model's: sky and model share the period, not the phase. Nothing yet computes a local solar time.
- **CLOCK-7** — sleep is per world (vanilla `WorldServer#areAllPlayersAsleep`); the skip writes the
  sleeping world's own counter (`MixinWorldServer`), `/time` writes every loaded world's
  (`TimeCommandGuard`); neither touches the space clock. No server-wide sleep check and no mode config.
- **CLOCK-8** — the client's clock is re-synced every `CLOCK_SYNC_TICKS` (200) only.
- **CLOCK-9** — two time-of-day gates: `TileAtmosphereDetector` (`getWorldTime() % 10`),
  `TileRocketServiceStation` (`% 20`).

More not written: `allowTimeSkipOnPlanets` defaults to off; the space model turns a body by
`rotationalPeriod` as if it were the sidereal period (`SatellitePlacement#rotationOf`, `PlanetEnvironment`,
`SatelliteLife`); the server's sky light is one number per world (vanilla `skylightSubtracted`, read in
`World`, `WorldServer`, `WorldProvider`, `EntityMob`, `BlockDaylightDetector`); a ship's transit is integrated
per tick (`ShipTransitManager`) and a skip does not carry it.

## CLOCK-6..9 — edge cases of the stress-test, as ruled (2026-10-24)

1. A player in a locked world — moot: the locks are on by default now (*"Раз мы вводим концепцию
   синхронизованного сна, надо поставить там по-умолчанию true"*).
2. The place, not meridian 0 — both layers (*"Хочется нормально"*; the recommendation, *"ок"*).
3. `rotationalPeriod` is the solar day (*"важно, чтобы игра была согласована внутри себя"*; *"ок"*).
4. `doDaylightCycle` has no effect, documented (*"Документируем, что это правило с модом не работает"*).
5. A ship in transit is carried through a skip (*"Надо продвигать"*). Its design pass found that nobody in
   a space-type world could sleep (permanently noon on the server); ruled with it: a bed in a world with no
   night may be slept in at any time and counts, the ship's systems are not carried, several arrivals at the
   end of a skip are accepted (*"по рекомендациям"*).
6. Worlds that are not bodies keep what they do, *"если их жизнь - функция от времени"* — each is checked
   for that when CLOCK-6 is written.

## Scope, stated so it is not over-claimed

- The wrong-clock symptom is **single-player / LAN-host only**: a dedicated server binds `CommonProxy`,
  which answers correctly `[V]`. The harness has no integrated-server tier, so what the witness above
  reproduces is the DRIVER — an accessor answering with a clock that is not the space clock — not
  the condition.
- The defect is invisible in the IN-CELL reading of any body: every framing body carries
  `BodyEphemeris.STATIC` as its in-cell law and sits at its own frame's origin (planets, and moons
  since a moon has a cell of its own: C15 ADDR-17). The witness therefore reads the target's ABSOLUTE
  position at the space clock (`stellurgytest nav status` → `targetAbs` / `bodyNowAbs`; the two
  `*Local` fields are components, never the miss). What a stale clock costs: the aim's CELL rides its
  body, so an aim resolved on the wrong clock lands where the body was, not where it is.

## Open

- **A client consumer.** CLOCK-3 installs a seam with no user today; its value is that the next
  client-side "where is that body now" has a correct answer to reach for instead of
  `Minecraft.getMinecraft().world`. The seam itself is pinned end to end; what is unpinned is
  whatever eventually reads it.
- **The sync period** (`SpaceEventHandler.CLOCK_SYNC_TICKS`, 200) is `tunable` and derived from what
  the clock is USED for, not from precision: a body moves ~0.5 blocks/tick against a 512-block
  descent trigger.

## Why the clock is monotonic and not part of the fleet snapshot

**The fleet is not the only thing this clock dates.** A memory crystal's `observedTick` lives in ITEM
NBT (`CrystalEntry.java`) `[V]`, and Minecraft commits it BEFORE a world-save event reaches this
subsystem — `WorldServer.saveAllChunks` saves the chunks and only then posts `WorldEvent.Save` `[V]`.

A clock rolled back with a declined fleet write would therefore come back EARLIER than stamps already
on disk, and `CrystalEntry.mergedWith`, which keeps the newer entry, would then prefer an entry stamped
in the future `[V]`. (A jump capacitor no longer dates its charge by this clock: it holds a Forge Energy
charge, `TileJumpCapacitor.java` `[V]`.)

Written forward instead, the worst case is a clock at most one save cycle AHEAD of a stale fleet: a
cell reads a cycle older, a jump lands a cycle sooner. **A clock that runs backwards breaks
arithmetic; a clock slightly ahead of one stale snapshot does not.** So the clock is monotonic, the
fleet is atomic, and they are written by different writers because they are different kinds of state.

## What the clock counts

Every persisted tick value in this subsystem is the subsystem's own counter: ledger ages, transit
`arrivalTick` / `lastTicked`, cell `lastVisit` and crystal `observedTick`. A save
that carries no clock key reads its clock back as **0**, and its stamps would therefore be in the future;
such a save is not loaded.

No production code compares a space-clock value with a world-clock value `[V]`. Two places that could
have read a second clock read the space clock:

- `CrystalSeeding` stamps a crystal entry's `observedTick` with the space clock, so two crystals
  merged by freshness compare one counter `[V]`.
- `DimensionProperties.updateOrbit()` writes the LIVE `orbitTheta` from the space clock. Its own
  javadoc states the invariant "the sky a player looks at and the address a navigation computer
  extrapolates to must be the same orbit, or the ship arrives somewhere the planet is not drawn"; a
  second clock here would make that invariant hold by coincidence, with two independent restore
  points.
