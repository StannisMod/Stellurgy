# api-public / rocket-stats — `StatsRocket`, `EntityRocketBase`, rocket events & tile interfaces

Files: `api/StatsRocket.java` (808), `api/EntityRocketBase.java` (156), `api/RocketEvent.java` (81),
`api/IRocketEngine.java`, `api/IRocketNuclearCore.java`, `api/IFuelTank.java`, `api/IIntake.java`,
`api/IMiningDrill.java`.

## Key types

| type | role |
|------|------|
| `StatsRocket` | per-fuel-type amount/rate/capacity/base-rate ints, thrust (newtons), dry mass (kg), seats, engine locs, dyn tags |
| `EntityRocketBase` | abstract MC entity: `StatsRocket stats`, infrastructure link list, abstract fuel API |
| `RocketEvent` | Forge `EntityEvent` family: launch/land/orbit/dismantle lifecycle |
| `IRocketEngine` / `IRocketNuclearCore` | block tile contract: thrust + fuel-rate / max-thrust, all in newtons |
| `IFuelTank` / `IIntake` / `IMiningDrill` | block tile contracts: fill volume / intake amt / mining speed+power |

## Mechanics

### MECH-API-06 — per-fuel-type slot dispatch
`StatsRocket` stores seven parallel int quadruples (amount / rate / capacity / base-rate) keyed by
`FuelRegistry.FuelType`; every accessor is a `switch(type)` over the enum
(`StatsRocket:304-501`). `addFuelAmount` clamps to remaining capacity and returns the delta
(`:539-578`). `getFuelRate`/`getBaseFuelRate` short-circuit to 0 when `rocketRequireFuel=false`
(`:359,:388`) — the config flag gates *consumption* at the single read point.

### MECH-API-07 — weight with fluid weight, and the one gravity expression
**On this line the units migration has NOT landed** (it lives on `feature/ship-construction-and-stats`,
`d221ccc91`): the field is `weight`, the accessors `getWeight()` / `getWeight_NoFuel()`
(`StatsRocket.java:133-135`), and neither weight nor thrust carries SI units. `getWeight()` returns the
dry weight plus, when `advancedWeightSystem` is on, the weight of loaded mono/bi-propellant, oxidizer
and nuclear working fluid via `Stellurgy.weights().getWeight(fluid, amount)` (`:135-153`); a fluid slot
holding the `"null"` sentinel weighs nothing.

Gravity enters through ONE function, `effectiveGravityMultiplier` (`:197-199`): the body's multiplier,
or one gee when `gravityAffectsFuel` is off. The flight model (`getAcceleration` `:201`,
`getDryAcceleration` `:211`, `(thrust − weight·g) / weight / 20` per tick) and the launch gate
(MECH-API-08) both read it, which is what stops them disagreeing about which body the rocket is on.
⚠️ Gravity is still read through several disagreeing paths elsewhere in the tree (open).

### MECH-API-08 — TWR launch gate (SSOT), against LOCAL gravity — C10 STAT-26..29
`canLaunch(gravitationalMultiplier)` (`:248-253`) is the single source of truth for weight-based
launch gating: `true` unconditionally when `advancedWeightSystem` is off, else
`getThrustToWeightRatio(gravitationalMultiplier) ≥ minLaunchTWR`, inclusive.
`getThrustToWeightRatio(g)` (`:227-237`) is `thrust / (wetWeight · effectiveGravityMultiplier(g))`; a
weighted craft on a body with no gravity answers `+∞` for any thrust, a weightless one `0`.
**There is no no-arg overload**: a form that judged at one gee
was the defect, and keeping it under any name would leave a second door to it; the API is unreleased.
`withTanksFull()` (`:261-293`) is the craft as it will stand fully fuelled — every tank at capacity, an
unchosen fluid replaced by the heaviest the registry accepts for that tank (`FuelRegistry#getFluids`) —
and is what the assemblers ask the gate about (C10 STAT-29).

### MECH-API-09 — stat NBT round-trip
`writeToNBT` nests all stats under one compound named `TAGNAME = "rocketStats"` (`:22,:640-724`);
`readFromNBT` calls `reset()` then unwraps the same `rocketStats` sub-tag (`:727-806`). Seats and
engine locations are packed as flat `int[]` triples; engine coords are stored as `×2` and read
back `/2` to preserve half-block offsets (`:699-708,:789-795`). All `fuelBaseRate*` are written as
float (`:674-680`) and read via `getFloat`/`getInteger` — both work because Forge numeric tags are
cross-readable.

### MECH-API-10 — dynamic stat tags
Arbitrary named `float`/`int` stats live in a `HashMap<String,Object>` (`statTags`), persisted
under sub-tag `dynStats` only when non-empty (`:623-638,:682-692,:771-782`). Unknown/absent tag
reads return `0`.

### MECH-API-11 — infrastructure linking (`EntityRocketBase`)
`linkInfrastructure` adds an `IInfrastructure` only if `tile.linkRocket(this)` accepts
(`EntityRocketBase:47-50`); `unlinkInfrastructure` removes it. The list plus a
`Set<HashedBlockPosition> infrastructureCoords` is the reconnect-on-load state (concrete save in
rocket-entity).

### MECH-API-12 — orbit & dismantle events
`onOrbitReached()` posts `RocketReachesOrbitEvent`, then, if in `spaceDimId`, frees the station
landing pad it occupies (`EntityRocketBase:130-140`). `deconstructRocket()` posts
`RocketDismantleEvent` inside a `try/catch(Throwable)` so a throwing handler cannot abort the
deconstruction (`:146-155`). The full event family (`RocketPreLaunchEvent` `@Cancelable`,
`RocketLaunchEvent`, `RocketAbortEvent(reason)`, `RocketDeOrbitingEvent`, `RocketLandedEvent`,
`RocketDismantleEvent`, `RocketReachesOrbitEvent`) is posted from rocket-entity; only their
declarations are owned here (`RocketEvent.java`).

## Invariants

- **INV-API-06 [V][SYS]** `INVALID_SEAT = Integer.MIN_VALUE`; `hasSeat()` ⇔ `pilotSeatPos.x != INVALID_SEAT`
  (`StatsRocket:23,:583-585`). Seat NBT stores the pilot seat as `playerXPos/Y/Z` (`:694-696`). FOR: save format: rocket stats NBT.
- **INV-API-07 [V][BEH]** Fuel rate/base-rate are forced to 0 when `rocketRequireFuel=false` — the only
  gate (`StatsRocket:359,:388`); amount/capacity are unaffected.
- **INV-API-08 [V][BEH]`[T]`** `canLaunch(g)` returns true whenever `advancedWeightSystem` is disabled
  (`StatsRocket.java:249-251`; `RocketLaunchDepthTest#turningTheWeightSystemOffLiftsTheWeightGate`),
  and judges the weight at the gravity it is handed
  (`RocketLaunchDepthTest#aCraftOnALowGravityMoonIsWeighedAtThatMoonsGravity`). The
  `gravityAffectsFuel`-off half (one gee everywhere) is `[V]` only: no test pins it. Pinned by `RocketLaunchDepthTest#turningTheWeightSystemOffLiftsTheWeightGate`, `RocketLaunchDepthTest#aCraftOnALowGravityMoonIsWeighedAtThatMoonsGravity`.
- **INV-API-09 [V][SYS]** `readFromNBT` first `reset()`s, so a missing `rocketStats` tag yields a clean
  zeroed stat rather than stale values (`:728-729`). FOR: save format: rocket stats NBT.
- **INV-API-10 [T][SYS]** `writeToNBT` wraps in `rocketStats`; `readFromNBT` unwraps `rocketStats`. The
  round-trip pair used by all live callers passes the *outer* nbt (`EntityRocket:3004,:3109`;
  `TileRocketAssemblingMachine:816,:854`). The static API factory `createFromNBT` (`StatsRocket.java:94-103`)
  passes the outer nbt to `readFromNBT` too (unwrapping twice would return an empty stat) — pinned by
  `FreeFlightNbtRoundTripTest#statsReadThroughTheApiFactoryWriteWhatTheRocketWrote`. No live
  production caller uses it. FOR: save format: rocket stats NBT.
- **INV-API-11 [A]** Engine `×2`/`÷2` packing assumes engine offsets are integer or half-integer;
  a quarter-block offset would round. No test pins this; assumed from the assembler placing engines
  on block/half-block grid.

## State & persistence (C1)

`StatsRocket` NBT keys: see `C1-nbt-persistence`. Notes: the whole block sits in the `rocketStats` wrapper compound; `thrust` is an int
in newtons and `mass` a float in kg (no migration); `fuelFluid`/`oxidizerFluid`/`workingFluid` default to the string `"null"`;
`fuelBaseRate{Type}` is written float and read float or int (cross-readable); `dynStats` is optional; `engineLoc` is encoded twice
over as `int[]` triples.

## Integration seams

- Events C5: the seven `RocketEvent` subclasses (`RocketPreLaunchEvent` cancelable) on
  `MinecraftForge.EVENT_BUS`.
- `EntityRocketBase.onOrbitReached` reaches into `StellurgyAPI.spaceObjectManager` (interface
  seam) to release a pad.

## Config surface

Config: see `C4-config-surface`. `gravityAffectsFuel` is read only in `effectiveGravityMultiplier`, so it gates the launch gate and the
flight model together. Full-disable: with both `rocketRequireFuel=false` and `advancedWeightSystem=false` the stat block imposes no
launch constraint at all.

## Test coverage

The `createFromNBT` reads through the outer tag exactly once, pinned through a real save by
`FreeFlightNbtRoundTripTest` (`:132-148`). INV-API-10 is [T].

## Open questions

- None outstanding for this cluster; `createFromNBT` is confirmed broken-but-unused (only test
  callers). Whether it should be deleted or fixed is a maintainer decision.
