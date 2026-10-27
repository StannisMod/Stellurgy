---
id: first-probe
owns: [probe/]
entrypoints: [ProbeFallDriver#onServerTick, ProbeFallDriver#onLogin, ProbeRecord#lived, EntryTrajectory#fly, RenderProbeFall#onRenderWorldLast, ItemRadio#onItemRightClick, BlockProbeWreck#breakBlock]
depends-on: [satellite, environment, dimension-planets, universe, network-wire, client-render]
depended-by: [satellite]
contracts: [C1, C2, C3, C4, C7]
confidence: medium
---

## Purpose

The one scripted find: a probe that somebody else put on an orbit about the player's planet long before
the world's clock began, that died in the planet's radiation belt, and that falls near the player once. This
subsystem owns the probe as an object — what it recorded before it died, how it falls through the air, its
fall over the world and in every client's sky, its crater and wreck, the radio that finds it, and its flight
recorder. Its STORY is a pack's: the core owns the object and the mechanism of studying it.

## Responsibility boundary

Owns: every class under `probe/` — the probe's orbit, record and entry, the fall's state and driver, the
wreck, the transmitter registry, the radio and the flight recorder, and the client's picture of the fall.
Does NOT own: the dose law and the accrual it runs on (satellite, INV-SAT-21..30), the terminal's table the
record is read on (satellite, `TerminalReadout`), the field and the belts (environment), a body's air,
gravity and atmosphere line (dimension-planets), the two packets' wire ids (network-wire), the client
world's drawings holder (client-render, `ClientWorldDrawings`).

## Key types

| class | role |
|---|---|
| `FirstProbe` | the probe as a flying object: its orbit, its entry state, when it is due and where it may land |
| `ProbeRecord` | what it recorded before it died, by the satellite accrual, with the orbit it was taken on |
| `EntryTrajectory` | a fall through an exponential atmosphere about a round planet, sampled per tick |
| `FallPlacement` | a fall laid over a flat world along a heading, ending at its landing point |
| `ProbeFall` | the save's one fall: waiting, falling or down, and the fall's inputs (global `WorldSavedData`) |
| `ProbeFallDriver` | first login, entry, touchdown, laying the crater; tells clients the fall and the transmitters |
| `ProbeImpact` | the crater, its scorched floor and rim, and the wreck at its bottom |
| `BlockProbeWreck` / `TileProbeWreck` | the wreck: holds the record, a transmitter while it stands |
| `TransmitterRegistry` | a world's transmitters, what a radio there hears (per-world `WorldSavedData`) |
| `RadioReading` / `ItemRadio` | a direction-finder's bearing and strength, and the item whose needle shows it |
| `ItemFlightRecorder` | the record out of the wreck, for a satellite terminal's data slot |
| `ProbePack` | what a pack file adds to the record: notes and readings, kept apart from the core |
| `client/ClientProbeFall`, `client/RenderProbeFall` | the fall in a client's sky, drawn by direction |

## Mechanics

- **MECH-PRB-01 The record is the probe's life under the dose law.** `ProbeRecord.lived` steps the satellite
  accrual over the probe's orbit one segment at a time, with the probe's parts — a dosimeter as its primary
  function and a solar panel — and stops at the segment the dosimeter stops in, or at the end tick
  (`ProbeRecord.java:79`). The probe is released `AGE_TICKS` before the clock's zero and its record runs to
  the zero (`FirstProbe.java:29`), so a probe that dies in the belt carries a record that breaks off at its
  death. Its orbit is circular, its radius drawn between 1.3 and 1.7 body radii and its inclination below 40°
  from the world's seed (`FirstProbe.java:99`) — inside the inner belt's shells. The record keeps the orbit
  and the body's turn, so a reader can place each entry under the probe. Measured 2026-10-26 about Earth's
  derived field, eight seeds at the physical wear rate: it dies 24 to 106 days of the clock after release,
  and its readings hold the last 1 024 segments before the stop (the satellite's own reading capacity).
  `tunable`: the orbit's range and the age.
- **MECH-PRB-02 The entry.** `FirstProbe.entry` (`FirstProbe.java:115`) puts the probe at the body's
  atmosphere line at that height's circular speed — an orbit decayed to the top of the air — descending at the
  angle that puts its vacuum periapsis as far below the surface as the line lies above it. The air is 1.225
  kg/m³ times the body's surface pressure in atmospheres, thinning with a scale height of 8.5/100 of the line's
  height; the probe's ballistic coefficient is 70 kg/m². `EntryTrajectory.fly` (`EntryTrajectory.java:128`)
  integrates gravity μ/r² and drag about a round planet at 1/20 s of flight per tick and reads it back as a
  downrange distance and a height. Measured 2026-10-26: about Earth the fall takes 13 174 ticks (11.0 min)
  over 1 008 km, peaks at 7.5 g at 62 km and lands at 33.6 m/s; at 0.53 Earth radii under 1 % of an
  atmosphere it takes 8 691 ticks and lands at 352 m/s; at 0.27 Earth radii with no air, 10 965 ticks and
  1 683 m/s. `tunable`: the coefficient and the scale height's ratio.
- **MECH-PRB-03 The one fall.** The save's first login stamps the overworld's time (`ProbeFallDriver`,
  `onLogin`). Each server tick while nothing has fallen, the first listed player standing on a planet — a
  world with a body radius and an atmosphere line, not space — for whom it is due (`FirstProbe.java:78`,
  `ProbeFallDriver.java:137`) gets the fall: a landing point drawn in the band around him
  (`FirstProbe.java:86`), tried up to 64 times for dry land — not an ocean or river biome, its top block not
  liquid — and otherwise the first point drawn, logged (`ProbeFallDriver.java:191`); a heading drawn; the
  record computed for his world's body. `ProbeFall.enter` refuses once anything has fallen
  (`ProbeFall.java:157`). Its world's time past the entry reaching the trajectory's ticks is the touchdown
  (`ProbeFallDriver.java:102`): the wreck's point becomes a transmitter. The crater and wreck are laid in one
  go once the ground around the landing is loaded (`ProbeFallDriver.java:113`).
- **MECH-PRB-04 The crater and the wreck.** `ProbeImpact.lay` (`ProbeImpact.java:40`) digs a bowl three
  blocks across its radius and two deep at its centre, clears above it, scorches its floor and rim to coarse
  dirt, leaves a block with a tile entity or an unbreakable one alone, and lays the wreck holding the record at
  its bottom (`ProbeImpact.java:32`). Broken by anything, the wreck drops its flight recorder and stops
  transmitting (`BlockProbeWreck.java:36`, `:39`); it never drops itself. `tunable`: the bowl's size.
- **MECH-PRB-05 The fall in the sky.** Each client in the fall's world is sent its inputs on the entry, on
  login and on a change of world (`ProbeFallDriver.java:142`); it computes the same trajectory. Each frame it
  is placed along its true direction from the eye at no more than three quarters of the loaded distance, and
  sized by the ratio of that distance to the true one, never under a quarter of a degree
  (`ClientProbeFall.java:67`, `RenderProbeFall.java:70`), with a ten-second trail; it glows by its speed down
  to an ember floor. Its chunks and the render distance decide nothing.
- **MECH-PRB-06 The radio.** A world's transmitters are sent to its clients on login, change of world and
  every start or stop (`ProbeFallDriver.java:268`). The radio's reading (`RadioReading.java:42`) is the bearing
  of the nearest transmitter and its strength by free-space loss of a one-watt beacon on 108 MHz; its needle
  eases toward the bearing as a compass's does, and use reports the strength in dBm
  (`ItemRadio.java:36`). With nothing to hear the needle wanders. A player's navigation beacon is not a
  transmitter.
- **MECH-PRB-07 The record on a terminal, and a pack's additions.** A flight recorder in a satellite
  terminal's data slot puts the record — and what a pack added — beside the satellite's rows
  (`TileSatelliteTerminal.java:339`, `TerminalReadout.java:192`; satellite owns the table). A pack's file,
  `firstProbe.json` in the mod's config folder, is read at the entry and stored beside the core record under
  its own key (`ProbeFallDriver.java:240`); a missing or unreadable file adds nothing, the second logged.

## State & persistence

| key | owner | holds |
|---|---|---|
| `orbit`, `sidereal`, `turnAtZero`, `journal`, `readings`, `diedAt` | `ProbeRecord#writeToNBT` | the record; `diedAt` absent while the probe outlived it, `orbit` absent for a body the universe does not name |
| `pack` (`notes`, `readings`) | `ProbePack#writeToNBT`, beside a record | what a pack added |
| `firstLogin`, `phase`, `fall`, `laid` | `ProbeFall` (global storage, `stellurgyFirstProbe`) | the save's one fall |
| `dim`, `entry`, `landing`, `heading`, `radius`, `gravity`, `atmosphere`, `line`, `player`, `record` | `ProbeFall.Fall` | the fall's inputs |
| `transmitters` | `TransmitterRegistry` (per world, `stellurgyTransmitters`) | the world's transmitters |
| `record` | `TileProbeWreck`, `ItemFlightRecorder`'s stack | the record the wreck or recorder holds |

## Invariants

- **INV-PRB-01 [T][SYS] The record breaks off where the dose law kills it.** A probe whose dosimeter
  stops before the record ends has its last reading in the segment it stopped in and none after it, and
  its journal's last entry is that stop, of the dosimeter's slot, at that segment; a probe that outlives
  the record keeps reading to its end and its journal holds no stop. REGIONS: `dies in the belt`,
  `outlives the record`. Pinned by `ProbeLawsTest#theRecordBreaksOffWhereTheDoseLawKillsIt`. FOR:
  maintainer 2026-10-26, *"Остальное по рекомендациям"* (the rest as recommended), which ruled the
  recommendation that the record is the dose the real field gives along the probe's own orbit through the
  belt until its electronics' budget is spent, so it breaks off where the model says it died.
- **INV-PRB-02 [T][SYS] The record reads the place.** Each of the probe's readings equals the trapped dose
  rate its body's environment gives at the probe's position on its orbit at that reading's tick. Pinned
  by `ProbeJointsTest#theProbeReadsThePlaceItFlew`. FOR: the same ruling, *"the dose the real field gives
  along the probe's own orbit"*.
- **INV-PRB-03 [T][SYS] Drag only takes energy.** Along a fall, the probe's specific orbital energy
  v²/2 − μ/r never rises by more than the integration's stated error, and with no air it keeps its start
  value to that error. REGIONS: `air`, `no air`. Pinned by `ProbeLawsTest#dragOnlyTakesEnergy`. FOR:
  maintainer 2026-10-26, the same ruling, on the recommendation of a ballistic entry with drag in an
  exponential atmosphere scaled to the body's orbit line.
- **INV-PRB-04 [T][SYS] It reaches the ground, and in air at its terminal speed.** Every entry
  `FirstProbe.entry` gives lands, its last sample at height zero; where the air is thick enough that the
  probe falls in near-equilibrium, it lands at the terminal speed √(2βg/ρ₀), above it by no more than
  twice the first-order lag v_t²/(4gH). REGIONS: `thick air`, `thin air`, `no air`. Pinned by
  `ProbeLawsTest#itReachesTheGroundAtItsTerminalSpeed`. FOR: maintainer 2026-10-26, the first-probe
  message, *"падение должно быть видно на протяжении всего маршрута - от входа в атмосферу до
  приземления. Чтобы было реалистичное падение"* (the fall must be visible the whole way, from atmospheric
  entry to landing — a realistic fall).
- **INV-PRB-05 [T][BEH] Exactly one probe falls per save.** Once it has entered, nothing enters again
  (`ProbeFall.java:157`), and the state is the save's, not a world's. Pinned by
  `FirstProbeFallTest#exactlyOneProbeFallsOverTwoNights` (client tier, own boot): over the night it falls in
  and over a later night once it is down, the save due on both, one entry is admitted. Not visited: a second
  planet, so the state being the save's rather than a world's rests on the global storage (`ProbeFall.java:48`).
- **INV-PRB-06 [T][BEH] It lands where its fall ends, in the band around the player, on dry land where any
  is drawn.** The touchdown is at the landing point the trajectory was laid to end on
  (`ProbeFallDriver.java:102`, INV-PRB-13), and the point is drawn in the band (INV-PRB-12) and tried for dry
  land (`ProbeFallDriver.java:171`). Pinned by `FirstProbeFallTest#itLandsWhereItsFallEndsInTheBandAroundThePlayer`
  (client tier, own boot): the crater is laid on the block the fall ended on, the wreck in that block's
  column, the block in the band around where the player stood at the entry. Owed: the dry-land half — around
  the test world's spawn every draw is dry, so preferring water still lands dry and the choice is not seen;
  it needs a player standing where the band holds water and land both.
- **INV-PRB-07 [V][BEH] A client in the fall's world draws it at every tick of the fall, whatever its chunks
  and render distance.** Its placement is by direction inside the far plane (`ClientProbeFall.java:67`). Owed:
  a client e2e on a test record of each sky point drawn.
- **INV-PRB-08 [V][BEH] The radio points at the wreck from anywhere in its world, and a broken wreck no
  longer transmits.** The wreck's point is a transmitter from touchdown (`ProbeFallDriver.java:102`) until
  it is broken (`BlockProbeWreck.java:39`). Owed: a client e2e of the needle's bearing.
- **INV-PRB-09 [V][BEH] The flight recorder in a satellite terminal's data slot shows the probe's record
  beside the terminal's own satellite's, and taken out, takes it away** (`TileSatelliteTerminal.java:339`).
  Owed: a server mechanics test through the terminal's slot.
- **INV-PRB-10 [V][BEH] A pack only adds, and the probe is whole without one.** A missing or unreadable pack
  file gives no additions (`ProbeFallDriver.java:251`, `:258`); what one adds is kept beside the core
  (INV-PRB-15). Owed: a server mechanics test with a pack file present and absent.
- **INV-PRB-11 [T][SYS] It falls at night, after the delay.** The probe is due exactly when somebody has
  logged in, the delay has passed since the first login, and the sun stands more than six degrees below the
  horizon. REGIONS: `nobody logged in`, `before the delay`, `day`, `twilight`, `night`. Pinned by
  `ProbeLawsTest#itFallsAtNightAfterTheDelay`. FOR: maintainer 2026-10-26, *"Остальное по рекомендациям"*,
  on the recommendation of the first night after a delay from the first player's first login.
- **INV-PRB-12 [T][SYS] It lands in the band.** Every landing offset drawn lies between 16 and 64 chunks of
  the player, spread evenly over the band's area. Pinned by `ProbeLawsTest#itLandsInTheBand`. FOR:
  maintainer 2026-10-26, *"в каком-то радиусе от игрока на момент входа в атмосферу, скажем... 64 чанка?"*
  (within some radius of the player at the moment of atmospheric entry, say... 64 chunks?), and the
  recommendation of a band from 16 to 64 chunks.
- **INV-PRB-13 [T][SYS] The fall laid over the world ends where it lands.** Laid along its heading, the fall
  is at the landing point on the ground at its last tick, at the entry height its whole downrange distance
  back along the heading at its first, and on that line in between. Pinned by
  `ProbeLawsTest#theFallEndsWhereItLands`. FOR: maintainer 2026-10-26, the first-probe message, *"падение
  должно быть видно на протяжении всего маршрута - от входа в атмосферу до приземления"* (the fall must be
  visible the whole way, from atmospheric entry to landing): the path drawn is the path that lands.
- **INV-PRB-14 [T][SYS] The radio reads a bearing and a strength.** From any place, its bearing points at
  the nearest transmitter it hears and its strength falls as that transmitter is farther; with none it reads
  nothing, and a reading carries no distance. REGIONS: `none`, `one`, `several`. Pinned by
  `ProbeLawsTest#theRadioReadsABearingAndAStrength`. FOR: maintainer 2026-10-26, *"Радио - некоторый
  приемник, который позволит запеленговать сигнал. Аналог метеоритного компаса из AE2"* (a radio, a
  receiver that takes a bearing on a signal, the AE2 meteorite compass's counterpart), and the
  recommendation of a bearing and a strength, never a distance.
- **INV-PRB-15 [T][SYS] A pack only adds.** The probe's rows on a satellite terminal with no pack are
  exactly its core record's; with a pack every core row is still there, beside the pack's.
  REGIONS: `no pack`, `pack`. Pinned by `ProbeJointsTest#aPackOnlyAddsToTheRecord`. FOR: maintainer
  2026-10-26, the same ruling, on the recommendation that a pack file may attach rows or text and that with
  no pack the core record is whole.

## Failure modes & edge cases

- With `satelliteRadiationWear` off no part wears (satellite, config surface), so the probe's dosimeter
  never stops and its record does not break off: it holds the last readings before the clock's zero.
- A body with no field gives readings of "absent here" and the probe never dies.
- A world the universe names no body for gives an empty record, logged.
- A tidally locked world's celestial angle is fixed, so its sky is never night there and the probe waits for
  a player on another planet.
- A fall that has not reached the ground in `EntryTrajectory.MAX_TICKS` is refused with an exception
  (`EntryTrajectory.java:165`), never cut short; the driver then logs and tries again later.
- With no dry land among the 64 points drawn it lands in the water at the first one, logged.

## Integration seams

`SatelliteAccrual.advance` and its `Body`, `SatelliteLife.bodyEnvironment` and `starDirection` (satellite)
carry the dose law and the body's environment; `TerminalReadout.holdRecord` (satellite) reads the record.
Packets `PacketProbeFall`, `PacketTransmitters` (network-wire, C2). Events: `PlayerLoggedInEvent`,
`PlayerChangedDimensionEvent`, `ServerTickEvent` (`ProbeFallDriver`), `RenderWorldLastEvent`
(`RenderProbeFall`). The client's picture lives on `ClientWorldDrawings` (client-render).

## Config surface

`firstProbeDelayTicks` — ticks after the save's first login before the probe may fall, on the first night
past them (C4). The probe itself is not switchable — maintainer 2026-10-26, *"F11 - не отключаемо, это
несущая часть"* (not switchable, it is load-bearing).

## Test coverage

INV-PRB-01, -03, -04, -11..-14 → `ProbeLawsTest` (unit); INV-PRB-02, -15 → `ProbeJointsTest` (integration,
the joints with `environment` and with the terminal's table); INV-PRB-05 and -06 (but its dry-land half) →
`FirstProbeFallTest` (client, own boot). INV-PRB-07..-10 are owed their server and client tests.

## Open questions

- The textures are borrowed (the wreck's, the recorder's, the radio's body and its needle — the compass's).
- No recipe is written for the radio: recipes are the maintainer's.
