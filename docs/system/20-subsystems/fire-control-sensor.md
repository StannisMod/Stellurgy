---
id: fire-control-sensor
owns: [api/sensor/, sensor/, tile/sensor/]
entrypoints: [TileFireControlSensor#update, TacticalScan#sweep, SignatureModel#passiveQuality, WeaponNetworkState#getAcquiredTrack]
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
| `api.sensor.TargetTrack` | one contact: entity, world position, velocity, quality 0..1, mode, radiance, distance. Immutable |
| `sensor.SignatureModel` | `σT⁴`, the two terms it feeds, and the estimate used while nothing states its own signature |
| `sensor.TacticalScan` | one sweep: candidates, the two exclusions, the per-mode quality, the ranking |
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
[T `FireControlSensorE2ETest#aCoolTargetTooFarToHoldByListeningIsHeldByIlluminating`,
`SignatureModelTest#illuminatingHoldsAColdTargetThatListeningCannot`]

**MECH-FCS-03 — A friend never becomes a CONTACT.** The credential is screened during the sweep, not
at the trigger, so an ally's position is never written anywhere a gun could read it and no stale
order, race or second console can produce a shot at them. The gun checks again at the trigger as
depth. `TacticalScan.java:93-95`, `TileTurret.java:388-402` [V]
[T `SensorFriendIsNeverAcquiredE2ETest`, falsified: with the screen removed the code-carrying player
enters the list at quality 0.30 and the test reddens]

**MECH-FCS-04 — Standing on our own deck is itself the credential.** An entity inside the sensor's
own ship's bounds is excluded whatever it is carrying: a crew does not acquire code devices before a
boarding action, and a battery that shells its own deck is the failure this subsystem exists to make
impossible rather than merely unlikely. A ground installation has no such frame and no such
exclusion. `TacticalScan.java:96-100,147-152` [V]

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

**MECH-FCS-08 — The sensor ranks; it does not decide.** Contacts are sorted by quality, nearest
breaking a tie, and only the first is published. A battery that has to choose should choose the one
it can actually hit, and among equals the one arriving first. The rest of the list exists for the
readout and for the probe. `TacticalScan.java:76-79` [V]

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
one's quality and distance, and whether that is a lock — travel in the tile's update tag and the
client draws its own copy. A readout composed from server-only state renders as zeroes over a real
connection and looks correct only in single player, where both sides share one JVM.
Sent on a threshold — a contact appearing or going, the mode changing, the illuminator losing power,
or the lock moving by more than a twentieth. `TileFireControlSensor.java:159-180,482-520` [V]

**MECH-FCS-14 — The mode button is a PACKET, because the button runs where the sweep does not.** A
GUI button fires on the client (`ModuleButton.actionPerform` is client-only), and the mode it flips
is read by the sweep on the server; so the press travels as a `PacketMachine` and the server flips
its OWN mode in `useNetworkData`, answering to the GUI's `canInteractWithContainer`. A button press only sends a `PacketMachine` (`TileFireControlSensor.java:505-509`); setting the client tile's field alone would never reach the server and the sensor would never go active
on a dedicated server. `TileFireControlSensor#onInventoryButtonPressed`,
`#useNetworkData` [V] [T `WeaponGuiButtonsReachTheServerE2ETest#theSensorsModeButtonSwitchesTheSensorOnTheServer`]
`canInteractWithContainer` is vanilla's usability rule (same world, not invalid,
distanceSq ≤ 64.0) and the server branch refuses a press that fails it — weapons MECH-GUN-44 [T `MachineGuiClientGroupTest#aFireControlSensorPressFromBeyondReachChangesNothing`]

## Invariants

**INV-FCS-01 — Server only.** The tick returns immediately on a client world; a sweep is never run
client-side. `TileFireControlSensor.java:91-94` [V]

**INV-FCS-02 — The config flag fully disables the mechanic.** With `enableFireControlSensor` false
the sensor acquires nothing, publishes nothing, draws no power and leaves the network entirely — a
disabled device is not a node that quietly keeps its own buffer topped up. Anything it had published
expires. A battery is then pointed by hand, the supported configuration it was before sensors existed.
`TileFireControlSensor.java:122-133` [V]
[T `FireControlSensorE2ETest#aSensorAcquiresAHostileThatNobodyNamed` runs the disabled case as its
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

**INV-FCS-05 — An unnamed ship's sensor does nothing.** Same rule as a gun's (`weapons` MECH-GUN-16):
before the ship object exists every coordinate the block holds is a shipyard address.
`TileFireControlSensor.java:95-100` [V]

## Failure modes & edge cases

**Nothing blocks line of sight.** A contact behind a mountain or a hull is detected and
held exactly as one in the open. The gun's own line-of-fire check still refuses a shot into
structure, so the consequence is a battery that knows about something it cannot hit rather than one
that shoots through a wall — but a radar seeing through a planet is wrong, and the fix (a swept test
per candidate per sweep) has a cost that has not been measured. [A]

**Only entities can be contacts.** A hostile SHIP is not acquirable, because nothing
here converts a hull into a target; the same absence means an ALLIED hull can never be acquired
either, which is why a gun pointed at a point on an allied hull still fires (`weapons`, friend-or-foe screening). A hull CAN
be a target — by a pilot's designation (`weapons` MECH-GUN-40) — and the installation has a rule for
telling a friendly one (MECH-GUN-42); acquiring one is not built. [A]

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
  velocity, and refuses to fire below the lock threshold. The gun still works with no sensor.
- **subsystem-network** — the sensor is a `Weapon`-domain sink and its contact rides that domain's
  shared state; it is deliberately NOT a controller, so it does not count as the console whose
  absence clears a battery's target.
- **integration-vs** — one conversion, at the origin of the sweep.
- **shields** — `CodeUtils` (the vendored AFFS access-code layer) is the credential this screens on.
- **stellurgytest-probe-catalog** — `/stellurgytest sensor read|mode|code|charge|sees`.
