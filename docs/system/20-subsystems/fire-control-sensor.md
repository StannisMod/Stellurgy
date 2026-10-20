---
id: fire-control-sensor
owns: [api/sensor/, sensor/, tile/sensor/]
entrypoints: [TileFireControlSensor#update, TacticalScan#sweep, ThreatOrder#of, SignatureModel#passiveQuality, WeaponNetworkState#getAcquiredTrack]
depends-on: [subsystem-network, weapons, integration-vs, shields, api-public]
depended-by: [weapons]
contracts: [C1, C3, C4, C7, C12]
confidence: high
---

## Purpose

The step before a gun: **finding something to shoot at.** This subsystem decides what is out there,
which of it is a legitimate target, how well each one is being held, and hands the battery ONE
contact. It ends there — it fires nothing, aims nothing and owns no gun.

## Responsibility boundary

**Owns**: the sweep and what may enter its result; the friend/foe screen at LIST level; the signature
model (what a thing radiates and what that is worth to a listener); the passive/active split and what
each mode costs; the contact published to a weapons network, and its expiry.

**Does NOT own**: aiming, leading or firing (`weapons` consumes the contact and does all three); what
a round does after it leaves (`projectile-substrate`); how a target's temperature is produced — that
is the unbuilt heat subsystem's, and until it exists a target's numbers are ESTIMATED here behind one
seam (`ITargetSignature`).

## Key types

| type | role |
|---|---|
| `api.sensor.SensorMode` | `PASSIVE` (listen, emit nothing) / `ACTIVE` (illuminate, emit). `isEmitting()` is the whole price of the second |
| `api.sensor.ITargetSignature` | the seam the heat subsystem will implement: a radiator temperature and a radiating area, kept separate |
| `api.sensor.TargetTrack` | one contact, an entity (`ofEntity`, by uuid) or a HULL (`ofHull`, by the substrate's ship id, carrying the ship's world bounds as its extent): world position (a hull's is the middle of its extent), velocity, quality 0..1, mode, radiance (NaN for a hull — no signature), distance (a hull's to the nearest point of its extent). Immutable |
| `sensor.SignatureModel` | `σT⁴`, the two terms it feeds, and the estimate used while nothing states its own signature |
| `sensor.TacticalScan` | one sweep: the two candidate sources (creatures, loaded hulls), the exclusions, the per-mode quality |
| `sensor.ThreatOrder` | the order the contacts are handed over in: lock, arrival time, range, quality, identity — strict priority |
| `tile.sensor.TileFireControlSensor` | the block: mode, energy, cadence, the published contact, the readout |

## Mechanics

**MECH-FCS-01 — Detection range and lock quality are two terms with two inputs, and never one
number.** Range comes from TOTAL radiated power (`σT⁴·A`) and grows with its square root; quality
comes from RADIANCE (`σT⁴`), a function of temperature alone. So a large cool array and a compact hot
one that shed identical watts are noticed at the same distance and are not the same target at all —
the compact one is a point beacon, the sprawling one a smear. Collapsing them deletes the entire
radiator-layout trade (C12 HEAT-15). `SignatureModel.java:65-95` [V]
[T `SignatureModelTest#twoTargetsSheddingTheSameWattsAreNoticedAlikeAndHeldNothingAlike` — equal
power, equal range, and a sixteenfold difference in lock]

**MECH-FCS-02 — Listening is bounded by the target; illuminating is bounded by the sensor.** In
`PASSIVE` a contact must be within the range its OWN output earns, and its quality is its own
radiance against range squared. In `ACTIVE` neither depends on the target: anything inside the radius
is held at the installation's plateau, tapering over the last quarter of the envelope. This is why a
cold, quiet ship can only be engaged by someone who has stopped being quiet themselves.
`TacticalScan.java:113-124`, `SignatureModel.java:92-121` [V]
[T `FireControlSensorTest#aCoolTargetTooFarToHoldByListeningIsHeldByIlluminating`,
`SignatureModelTest#illuminatingHoldsAColdTargetThatListeningCannot`]

**MECH-FCS-03 — A friend never becomes a CONTACT.** The credential is screened during the sweep, not
at the trigger, so an ally's position is never written anywhere a gun could read it and no stale
order, race or second console can produce a shot at them. The gun checks again at the trigger as
depth. `TacticalScan.java:163-165`, `TileTurret.java:622-644` [V]
A HULL is screened the same way, by the network's hull rule (`weapons` MECH-GUN-42) — the function the
gun asks at the trigger — so a ship the installation calls a friend never enters the list
(`TacticalScan.java:100-102`); and a creature aboard such a ship is screened with it, being aboard a
vouched-for hull counting as a credential the way being aboard our own ship does (MECH-FCS-04;
`TacticalScan.java:171-176`). The cost, stated: a boarder on an allied deck is shielded from the
allied battery. A sensor on NO network has no rule, holds no hull at all, and vouches for none
(MECH-FCS-16). [V] Not seen by a test: the occupant half.
[T `WeaponClientGroupTest#aPlayerCarryingTheCodeNeverBecomesAContactAndOneWhoIsNotDoes` (INV-FCS-07),
falsified: with the screen removed the code-carrying player enters the list and the test reddens]

- **MECH-FCS-04 [T][BEH] — Being ON our own ship is itself the credential; being near it is not.** An
  entity on the sensor's own ship is excluded whatever it is carrying: a crew does not acquire code
  devices before a boarding action, and a battery that shells its own deck is the failure this
  subsystem exists to make impossible rather than merely unlikely. "On" is one predicate,
  `ShipFrameTravel#isOnShip`: held by that ship's deck capture in either mode (which carries a crew
  member across a jump, when nothing is under his feet), or standing on its blocks (the support
  probe, for a body no capture holds). The ship's box grown by `ABOARD_MARGIN` is a candidate list,
  never this verdict: a body in an empty corner of a tilted hull, in the band around it, or on
  another craft whose box overlaps is a contact. A ship's allegiance by its crew's code
  (`HullAllegianceRule.CODE_ON_CREW`) asks the same predicate. A ground installation has no such
  frame and no such exclusion. The sensor's own HULL is excluded too, by its id, before any rule or
  credential is asked about, so a ship with no transponder never acquires itself
  (`TacticalScan.java:92-96`; INV-FCS-10). `TacticalScan#trackOf`. Pinned by
  `ASensorAboardAShipTest#aBodyBesideTheHullThatStandsOnNothingOfItIsAContact` (the band beside the
  hull; until 2026-10-18 the box itself was the verdict, and such a body was screened as crew).
  Not seen by a test: the capture half — a crew member mid-jump during a sweep.

**MECH-FCS-05 — The sweep is on a cadence; the tracking is every tick.** The sensor reconsiders WHICH
contact to hand over every `fireControlSensorScanIntervalTicks`, and the gun re-reads that contact's
position from the entity itself every tick — so a slow sweep costs the choice of target, never the
accuracy of the aim. `TileFireControlSensor.java:114-119`, `TileTurret.java:281-296` [V]
The period is EXACTLY the interval: after a sweep the cooldown is set to `interval − 1`, so the next
lands on `t + interval` and the floor of 1 means every tick.
`TileFireControlSensor.update` [V]

**MECH-FCS-06 — A published contact EXPIRES.** It is good for three sweep intervals, so a sensor that
stops publishing — broken, unpowered, its chunk unloaded — takes its battery's target with it instead
of leaving a battery firing at where something used to be. A sweep that finds nothing clears it
immediately rather than waiting the hold out. `TileFireControlSensor.java:77,163-176`,
`WeaponNetworkState.java:93-105` [V]

**MECH-FCS-07 — Illuminating costs power, and an unpaid illuminator LISTENS rather than lying.** The
active mode draws FE per tick; a sensor that cannot pay reports itself underpowered and produces
passive-quality tracks. The alternative — quietly reporting the lock it would have had — is a readout
that disagrees with the guns. `TileFireControlSensor.java:129-142,178-181` [V]

**MECH-FCS-08 — The sensor ranks by a THREAT ASSESSMENT; it does not decide (ruling 2026-10-07,
*«Оценка угрозы»* — threat assessment).** Only the first contact is published; the order is strict
priority with no weights (`ThreatOrder.java:71-75`): (1) a contact the battery can LOCK
(`fireControlSensorLockQualityToFire`) before one it cannot; (2) reserved — a contact this picture has
seen ACT before one it has not: nothing observes an act yet, so no input feeds it and it is not
written; (3) the smaller time to contact, range over closing speed — the contact's velocity relative to
the sensor's own, projected on the line of sight to the point its range is measured to; every contact
that is not closing compares equal here. "Closing" starts at a floor of 1e-9 blocks/tick
(`ThreatOrder.java:57`): a creature at rest reads exactly zero, but a parked hull reads the physics
substrate's numerical residue (measured: 1.5e-17 blocks/tick, pointed at the sensor), and a floor of
zero would make that residue an arrival; the floor is eight orders above it and a contact at it covers
a block in about thirty years; (4) the nearer; (5) the better held; (6)
the smaller identity, so equals never reorder between sweeps. It reads neither the contact's KIND, nor
its size or signature (a mob and a battleship differ by orders of magnitude, so any presence term is
"hull always first"), nor anything about whose it is. Before acts are observed the battery therefore
serves whatever ARRIVES first. `fireControlSensorMaxTracks` truncates AFTER the sort
(`TacticalScan.java:122-123`), so the order also decides which contacts a full sensor forgets. The rest
of the list exists for the readout and for the probe. [V] Not built: a stickiness against the first
contact flapping between sweeps — its need and its form are unmeasured.

**MECH-FCS-15 — A loaded HULL is a candidate, held only by illuminating, and in range by its
NEAREST point.** The sweep's second source is every loaded ship (`VSIntegration#loadedShipBoxes`,
world bounds UNgrown) other than the sensor's own (`TacticalScan.java:90-110`). Its extent is the world
bounding box, its range the distance from the sensor to the nearest point of that box, and its quality
`SignatureModel#activeQuality` at that distance (`TacticalScan.java:127-148`): measured to its centre,
a long ship whose bow is a few blocks off would be out of range. Its velocity is the substrate's at
the box's middle; a hull the substrate cannot measure is no contact. A LISTENING sensor holds no hull
(`TacticalScan.java:129`): what it would hear is the hull's heat, and no heat can be read by a ship's
identity yet — resolving one by a position the sensor guessed would be a stand-in, so it is absence.
[V]

**MECH-FCS-16 — A sensor on no weapons network holds no hull, and says so.** The hull rule is the
network's (`TileFireControlSensor.java:222-232`); without one there is no rule, no hull candidate and
no vouched-for hull — never a default rule nobody chose. Such a sensor publishes nothing anyway, so
only its readout differs, and the panel carries a line saying it holds no ships
(`TileFireControlSensor.java:483-486`, `msg.fireControlSensor.line.hullsUnscreened`). [V]

**MECH-FCS-17 — A sensor that cannot look says it is blind; it never reports an empty sky.** Two
states leave a sweep unable to measure anything: a sensor aboard a ship with no usable pose
(`TileFireControlSensor.java:215-221`), and one whose ship has a pose but no measurable motion yet — a
registered ship whose physics is not loaded, where every arrival time would be taken against an
unknown motion (`TacticalScan.java:83-88`, which answers EMPTY rather than an empty list). Either way
the sensor holds and publishes nothing, the same as for a clear sky, so the difference lives in the
readout: the tile carries `blind` to its client (`TileFireControlSensor.java:588,613`) and the panel
adds a line (`TileFireControlSensor.java:487-489`, `msg.fireControlSensor.line.blind`). Switching the
sensor off clears it (`:131`). [V] — no test reaches the pose-without-motion state; the arrangement
needs a ship registered with its physics object absent, which no probe produces today.

**MECH-FCS-09 — The credential and the network are the installation's, not the block's.** A sensor on
a network uses that network's access code, falling back to its own — the same rule a gun follows,
because a sensor that disagreed with the guns it feeds would hand them their own crew.
`TileFireControlSensor.java:222-232` [V]

**MECH-FCS-10 — Ahead of the guns under a deficit.** The sensor is a network sink at priority 1
against a gun's 0: a battery that keeps its rounds and loses its eyes is firing at nothing, while one
that keeps its eyes and runs a round short still knows where the enemy is.
`TileFireControlSensor.java:290-298`, `ISubsystemSink.java:23-25` [V]

**MECH-FCS-11 — Aboard a hull it converts itself out to the world before looking.** Entities live in
world coordinates and the sensor's block position is its ship's subspace, so a distance measured
between the two would be meaningless. One conversion at the origin, and the contact it produces is in
world coordinates for the gun to convert back through the seam it already uses.
`TileFireControlSensor.java:144-160`, `TurretFireControl.java:89-99` [V]

**MECH-FCS-12 — The target's lock warning has no code, and correctly does not fire for a passive
lock.** An active sensor states that it is emitting; being tracked passively emits nothing and is
therefore undetectable. When the EM-signature layer lands, the warning falls out of the ordinary
detection path rather than being written. `SensorMode.java:37-41`,
`TileFireControlSensor.java:198-206` [V]

**MECH-FCS-13 — The readout is REPLICATED, because the sweep happens where a player cannot see it.**
The panel's numbers — mode, whether the illuminator is being paid for, how many contacts, the best
one's quality and distance, whether that is a lock, and whether the sensor is on a network at all
(MECH-FCS-16) — travel in the tile's update tag and the
client draws its own copy. A readout composed from server-only state renders as zeroes over a real
connection and looks correct only in single player, where both sides share one JVM.
Sent on a threshold — a contact appearing or going, the mode changing, the illuminator losing power,
or the lock moving by more than a twentieth. `TileFireControlSensor.java:159-180,482-520` [V]

**MECH-FCS-14 — The mode button is a PACKET, because the button runs where the sweep does not.** A
GUI button fires on the client (`ModuleButton.actionPerform` is client-only), and the mode it flips
is read by the sweep on the server; so the press travels as a `PacketMachine` and the server flips
its OWN mode in `useNetworkData`, answering to the GUI's `canInteractWithContainer`. A button press only sends a `PacketMachine` (`TileFireControlSensor.java:505-509`); setting the client tile's field alone would never reach the server and the sensor would never go active
on a dedicated server. `TileFireControlSensor#onInventoryButtonPressed`,
`#useNetworkData` [V] [T `WeaponClientGroupTest#theSensorsModeButtonSwitchesTheSensorOnTheServer` (INV-FCS-08)]
`canBeUsedBy` is vanilla's usability rule (`MachineReach#reaches`: same world, still there,
distanceSq ≤ 64.0), `canInteractWithContainer` answers with it, and `PacketMachine` refuses a press
that fails it before the sensor sees it — weapons MECH-GUN-44, network-wire MECH-NW-10 [T `MachineGuiClientGroupTest#aFireControlSensorPressFromBeyondReachChangesNothing`]

## Invariants

**INV-FCS-01 — Server only.** The tick returns immediately on a client world; a sweep is never run
client-side. `TileFireControlSensor.java:91-94` [V]

**INV-FCS-02 — The config flag fully disables the mechanic.** With `enableFireControlSensor` false
the sensor acquires nothing, publishes nothing, draws no power and leaves the network entirely — a
disabled device is not a node that quietly keeps its own buffer topped up. Anything it had published
expires. A battery is then pointed by hand, the supported configuration it was before sensors existed.
`TileFireControlSensor.java:122-133` [V]
[T `FireControlSensorTest#aSensorAcquiresAHostileThatNobodyNamed` runs the disabled case as its
own control, in the same server, on the same battery and the same zombie]
The gate does not ask a war switch (maintainer ruling 2026-10-03, *"Убираем этот гейт, слишком жирно. Война
всегда включена."* — remove this gate, too heavy; the war is always on; `weapons` MECH-GUN-35), so
`enableFireControlSensor` is the gate's only question, and the `sensor_gate_refused` test record
carries only the `sensor` flag. `TileFireControlSensor#update` [V]

**INV-FCS-03 — A gun's own orders outrank an acquisition, always.** A console target, a linker
target, a gun's own target and manual control each take precedence; the acquisition is what is left
when nobody has said anything. `TileTurret.java:247-270,315-324` [V]

**INV-FCS-04 — No contact survives a save.** Nothing about a track is written to NBT: a reloaded
sensor sees nothing and sweeps within one interval, because a contact restored from disk is an
assertion about an entity that may not exist. `TileFireControlSensor.java:394-412` [V]

**INV-FCS-05 — RETIRED 2026-10-07, superseded by INV-FCS-06.** It said "an unnamed ship's
sensor does nothing", gated on `VSIntegration#isOnUnnamedShip`. There is no unnamed ship (C11 PORT-16);
the gate and the accessor are deleted.

**INV-FCS-06 — A sensor sweeps only from a place in the world.** The sweep's origin is
`TurretFireControl.worldPositionOf` (`TileFireControlSensor.java:331`), and a `null` origin sweeps
nothing and publishes the cleared track (`TileFireControlSensor.java:204-210`). It is `null` for a
sensor on a named ship that is not simulated (no transform) and for a sensor block in the shipyard that
no ship owns — a remnant, whose coordinates are a shipyard address (`weapons` MECH-GUN-46). [V]
Not pinned on the sensor itself: the remnant decision it shares is pinned through the gun
(`TurretStandaloneTest#aGunInTheShipyardThatNoShipOwnsNeitherTurnsNorFires`).

- **INV-FCS-07** [T][BEH] A player carrying the installation's code is in no sweep's contact list; the same
  player, in the same place, carrying another code is in one within the same number of sweeps, and the
  battery fires on him as an ACQUISITION (MECH-FCS-03). `TacticalScan#trackOf`,
  `TileFireControlSensor#publish` [V]. Pinned by
  `WeaponClientGroupTest#aPlayerCarryingTheCodeNeverBecomesAContactAndOneWhoIsNotDoes`.
- **INV-FCS-08** [T][BEH] A press of the sensor's mode button on a real client switches the server's
  sensor: its next sweeps are taken in `ACTIVE` mode (MECH-FCS-14).
  `TileFireControlSensor#onInventoryButtonPressed`, `#useNetworkData` [V]. Pinned by
  `WeaponClientGroupTest#theSensorsModeButtonSwitchesTheSensorOnTheServer`.
- **INV-FCS-09** [T][BEH] An illuminating sensor on a battery's network, with a hostile hull in range,
  publishes THAT hull as its contact — the contact names the ship's id — and the battery's gun fires on
  it as an acquisition, with nobody having named a target (MECH-FCS-15, `weapons` MECH-GUN-47).
  `TacticalScan#sweep`, `TileTurret#interceptOf` [V]. Pinned by
  `HullContactGroupTest#aHostileHullInRangeIsTheContactAndTheBatteryFiresOnIt`.
- **INV-FCS-10** [T][BEH] A sensor aboard a ship never holds that ship, with no transponder aboard and
  every rule's credential absent, while it holds another hull in the same sweeps (MECH-FCS-04).
  `TacticalScan#sweep` [V]. Pinned by `HullContactGroupTest#aSensorNeverHoldsItsOwnHull`.
- **INV-FCS-11** [T][BEH] Under the hull rule `TRANSPONDER` with the network's code X, a hull whose
  transponder holds a device coded X is in no sweep's list; the sweep after its device is recoded to
  another code, or taken out, holds it again, and the sweep after a device coded X is back holds it no
  more — the credential is read off the device at each screening (`weapons` MECH-GUN-42).
  `HullAllegianceRule#isFriend`, `TileHullTransponder#getCarriedCode` [V]. Pinned by
  `HullContactGroupTest#aTransponderCarryingOurCodeKeepsItsHullOffTheListUntilTheCredentialGoes`.
  Not seen by a test: the transponder BROKEN — it stops vouching twice over (the tile leaves the index
  on invalidation, and the block's drop empties the slot it leaves), so no single inversion reddens it.
- **INV-FCS-12** [T][BEH] A transponder vouches only for the hull it is ABOARD, by ship id: a second
  hull whose world bounds overlap the vouched-for one is a contact in the same sweep that leaves the
  first out. `HullAllegianceRule#aboard` [V]. Pinned by
  `HullContactGroupTest#aTransponderVouchesOnlyForTheHullItIsAboard`.
- **INV-FCS-13** [T][BEH] With a hull and a creature both held and lockable, the contact handed over
  is the one that arrives first: a closing hull farther than a creature at rest is handed over before
  it, and the same hull at rest is handed over after it — the same candidates in the same scan order
  both times (MECH-FCS-08). `TacticalScan#sweep`, `ThreatOrder#of` [V]. Pinned by
  `HullContactGroupTest#theContactHandedOverFollowsArrivalTimeNotScanOrder`.
- **INV-FCS-14** [T][SYS] The order of `ThreatOrder#of` is the stated threat assessment and nothing
  else: a lockable contact never ranks below an unlockable one; with everything else equal the nearer
  ranks first and the faster-closing ranks first; exchanging a creature contact for a hull contact of
  identical quality, range and motion leaves the order unchanged; and the order is total, the same
  whatever order the candidates were listed in (MECH-FCS-08). FOR: the maintainer's ruling of
  2026-10-07 that ranking across kinds is a threat assessment, *«Оценка угрозы»*. Pinned by
  `SignatureModelTest#aContactTheBatteryCanLockOutranksOneItCannot`,
  …#allElseEqualTheNearerRanksFirst, …#allElseEqualTheFasterClosingRanksFirst,
  …#theOrderIsBlindToWhetherAContactIsAHull, …#theOrderIsTotalAndIndependentOfEnumeration.
- **INV-FCS-15** [T][BEH] An illuminating sensor holds a hull whose NEAREST point is inside its radius
  while the hull's centre is beyond it, at the quality of the nearest point's distance, and does not
  hold the same hull once its nearest point is outside (MECH-FCS-15). `TacticalScan#hullTrackOf` [V].
  Pinned by `HullContactGroupTest#aHullIsInRangeWhenItsNearestPointIs`.
- **INV-FCS-16** [T][BEH] A listening sensor holds no hull — a hull with no coolant loop included —
  and the same sensor illuminating holds that hull (MECH-FCS-15). `TacticalScan#hullTrackOf` [V].
  Pinned by `HullContactGroupTest#aListeningSensorHoldsNoHullAndAnIlluminatingOneDoes`.
- **INV-FCS-17** [T][BEH] With `enableFireControlSensor` off a sensor acquires no hull: it never sweeps,
  so a hostile hull in range is never published and the battery does not fire on it; on again, the
  same sensor acquires the same hull (INV-FCS-02). `TileFireControlSensor#update` [V]. Pinned by
  `HullContactGroupTest#aHostileHullInRangeIsTheContactAndTheBatteryFiresOnIt`.

## Failure modes & edge cases

**Nothing blocks line of sight.** A contact behind a mountain or a hull is detected and
held exactly as one in the open. The gun's own line-of-fire check still refuses a shot into
structure, so the consequence is a battery that knows about something it cannot hit rather than one
that shoots through a wall — but a radar seeing through a planet is wrong, and the fix (a swept test
per candidate per sweep) has a cost that has not been measured. [A]

**A hull is heard by nobody, and is held only by its box.** Hulls are contacts (MECH-FCS-15), but
only an illuminating sensor holds one: a ship's heat is built (`ship-heat`) and reachable only by a
block position, so there is no way yet to ask what ONE ship radiates by its identity, and the unit
bridge between the heat model and the signature model is uncalibrated. Until both exist a listening
sensor hears no hull at all, hot or cold. The extent is the world bounding box, conservative for a
rotated hull, and the point a gun is handed is its middle — inside the envelope, not necessarily on
matter, so a round can pass through a hollow hull (a miss, which is a normal outcome). Choosing a
point on the hull's real blocks, and a part of it, are later work. [A]

**Every temperature is estimated.** No production object states its own signature, so
`SignatureModel` guesses from what the world knows (living body, burning body, bounding-box area).
The law is real and the numbers are placeholders; when the heat subsystem lands, ships implement
`ITargetSignature` and the guesses stop being consulted for them. [A]

**The active mode's emission is stated and heard by nothing.** `isEmitting()` is true
and no EM-signature layer exists to consume it, so today the cost of illuminating is power alone.
A later subsystem completes this half. [A]

**No ballistic drop in the lead.** The intercept solves for the target's motion and
ignores the round's own fall, so a long shot under gravity is led correctly in time and low in
elevation. [A]

## Relationships

- **weapons** — the only consumer. It reads the contact off `WeaponNetworkState`, leads with the
  velocity, and refuses to fire below the lock threshold; a HULL contact it re-reads by the ship's id
  every tick and screens with the hull rule at the trigger (MECH-GUN-47). The hull rule itself
  (`HullAllegiance`) is the weapons subsystem's, and the sensor asks the same function. The gun still
  works with no sensor.
- **subsystem-network** — the sensor is a `Weapon`-domain sink and its contact rides that domain's
  shared state; it is deliberately NOT a controller, so it does not count as the console whose
  absence clears a battery's target.
- **integration-vs** — one conversion, at the origin of the sweep; the loaded hulls, their world
  bounds and their motion, all through `VSIntegration`.
- **shields** — `CodeUtils` (the vendored AFFS access-code layer) is the credential this screens on.
- **stellurgytest-probe-catalog** — `/stellurgytest sensor read|mode|code|charge|sees`.
