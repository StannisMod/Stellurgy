---
id: projectile-substrate
owns: [api/projectile/, projectile/]
entrypoints: [ShotSubstrate#launch, ShotSubstrate#tick, ShotRegistry#get, ShotReplication#announceSpawn]
depends-on: [shields, structural-damage, integration-vs, api-public, network-wire]
depended-by: [weapons, fire-control (planned)]
contracts: [C1, C2, C4, C5]
confidence: high
---

## Purpose

What a shot **is** between muzzle and impact. A weapon declares a launch; from then on the round exists
as a server-side record in its world's registry, is advanced once per tick along a **swept segment**, and
ends for exactly one stated reason. It is the first layer of the impact chain: this owns the shot, the
shield owns whether it arrives, and `structural-damage` owns what arriving does.

## Responsibility boundary

**Owns**: the shot record and its per-world store; the integration step (gravity, position, velocity);
the exact voxel traversal; **which layer a shot meets first**; the reflection speed floor; the launch
admission rule; the end reason.

**Does NOT own**: what a shell does to a body that reaches it (the field layer computes absorption and
the mirrored velocity); what an impact does to blocks (the damage service); friend-or-foe decisions,
aim, lead, or what a weapon costs to fire. It carries an owner and a faction token and **never reads
either** — a shot does not decline to hit its shooter, because deciding that is not this layer's job.

## Key types

| type | role |
|---|---|
| `ShotSpec` | the launch declaration: origin, velocity (blocks/tick), body, energy, kind, tokens, environment |
| `ShotEnvironment` | what acts on the round in flight; gravity only, declared at the muzzle |
| `ShotEndReason` | the FIVE ways a shot stops existing — `EXPIRED`, `FIELD_ABSORBED`, `REFLECTED_TOO_SLOW`, `STRUCTURE_IMPACT`, `WORLD_UNLOADED`. One of the five is never emitted: see Known gaps. A round stopped by armour it cannot pay for ends as `STRUCTURE_IMPACT`, at the face of the block that held (`structural-damage` MECH-DMG-35) |
| `Shot` | the in-flight record: fixed properties plus position, velocity, age, remaining energy |
| `ShotRegistry` | `WorldSavedData` — every shot of one world, the id counter, and recent end reasons |
| `ShotSubstrate` | `launch` / `tick` / `step`: the integrator and the crossing resolver |
| `ShotSubstrateEvents` | the `WorldTickEvent` END subscriber, and nothing else |
| `util.SweptSegment` | exact voxel traversal of a segment (Amanatides & Woo); pure, world-free. Lives in `util` because the damage engine bores with it too — the substrate must not become a dependency of the layer beneath it |
| `StructureCrossing` | the first solid block along a segment, searched in the world frame and in every candidate ship's frame |

## Mechanics

**MECH-SHOT-01 — A shot is a registry record, not an entity.** An entity is simulated only where the
world is loaded and tracked only near a player, so a long-range round as an entity either dies outside
the shooter's bubble or forces a corridor of chunks to stay loaded. A record is stepped by its world's
own tick regardless of who is watching and costs three vectors.
`Shot.java:14-33`, `ShotRegistry.java:21-33` [V] [T `ShotSubstrateTest#aShotCrossesEmptySpaceWithoutLoadingAnyWorld`]

**MECH-SHOT-02 — Every test is over the segment old→new, never a point.** The step is integrated first,
then the whole segment is traversed voxel-exactly. A per-tick position test walks past a wall whenever
the step is longer than the wall is thick, and past a voxel whose corner the ray clips at any speed.
`util/SweptSegment.java:47-99`, `ShotSubstrate.java:119-131` [V] [T `SweptSegmentTest`, `ShotSubstrateTest#aFastShotCannotPassThroughAOneBlockWall`]

**MECH-SHOT-03 — Layers are ordered by earliest crossing, geometrically.** Each layer is asked where it
would be crossed, in blocks along this tick's segment; the smallest distance wins. There is no
"shield first, then hull" precedence — a shot fired from inside a shell meets the hull with no shield in
between, and the field layer says so in its own vocabulary by refusing a crossing to a ray that starts
inside. `ShotSubstrate.java:128-141`, `FieldSurfaceMath.java:123-137` [V]

**MECH-SHOT-04 — A crossing is probed before it is committed.** `ShieldStrikeService.nearestShellCrossing`
answers where the field is without absorbing anything, so ordering can be decided before a shield is
charged; `resolve` then finds its shell through the same search, so the probe and the commit cannot
disagree about where the shell is. `ShieldStrikeService.java:32-88` [V]

**MECH-SHOT-05 — A reflected shot resumes inside the same tick, with what is left of its step.** The
shield computes the mirrored velocity (it owns the surface normal and the moving-shell correction); the
substrate moves the shot to the crossing point, nudges it past by an epsilon, subtracts the time
consumed and carries on. At most four crossings resolve per tick — a bound on work, not on how many
times a shot may bounce. `ShotSubstrate.java:47-60`, `:165-177` [V]

**MECH-SHOT-06 — Below the speed floor a reflected shot is ENDED, not parked.** An entity must end up
somewhere and so gets a minimum kick; a record has the better option of ceasing to exist, and a cloud of
near-motionless rounds loitering against a shell is both a cost and a lie about what is in the air.
`ShotSubstrate.java:165-173`, `StellurgyConfiguration#shotReflectionSpeedFloor` [V]

**MECH-SHOT-07 — Graceful penetration lowers the round's worth and lets it through.** A shell that spent
everything it had and still could not cover the cost hands back a residual; that residual becomes the
shot's impact energy and the shot carries on from the crossing point. It never reflects on a short pay.
`ShotSubstrate.java:178-186`, `ShieldStrikeService.java:113-119` [V]

**MECH-SHOT-20 — A shot is declared to a shell at its OWN kind, carrying a body only if it IS one.**
The strike's kind comes from the single hull-kind→shield-kind mapping, so a beam is billed against the
shell's energy resistance rather than its physical one; the travelling body is declared only for the
kinds that are a lump of something travelling (the same `carriesMass` set the deceleration law of
MECH-SHOT-18 uses). Both halves are load-bearing and they are separate questions: the kind picks the
resistance bias (MECH-SHD-16), the body's PRESENCE is what makes a fully-paid shell mirror the strike
(MECH-SHD-20) — so a beam declared with a velocity bounces light off a shield.
`ShotSubstrate.java:119-124`, `:246-247`, `ImpactKindMapping.java:24-40` [V] [T]

**MECH-SHOT-21 — A round inside a hull is kept in THAT hull's frame between ticks.** A hull
manoeuvres, so a position in world coordinates describes where the plate used to be: one tick later
the round is hanging in the hole's wake or buried in a part of the ship it never reached. A shot that
ends a tick inside a ship's material is therefore stored in that ship's subspace, and rejoins the
world at the hull's CURRENT pose when the next tick starts — boring through a hard-turning ship and
boring through a parked one become the same arithmetic. The world's own blocks need none of this: the
world does not manoeuvre, so a round in world material stays a world-frame body.
`ShotFrame.java:80-114`, `ShotSubstrate.java:163-168`, `:303-307` [V] [T]

**MECH-SHOT-22 — The frame change is a whole change or none of it: position through the transform,
velocity through the rotation PLUS the hull's own motion.** The hull's motion is subtracted going in
and added coming out, so what bores is the velocity RELATIVE to the plate and what leaves the far side
still carries the ship's speed. Every conversion may answer null (an unloaded or unregistered ship);
each entry point then changes nothing and says so, because a half-converted body is worse than an
unconverted one. `ShotFrame.java:38-131` [V]

**MECH-SHOT-23 — A shot that began the tick inside a hull asks only THAT hull.** For the first
crossing of such a tick the search skips the world frame and every other ship: the answer is known in
advance, because a body inside somebody's material is not simultaneously inside anybody else's. After
a deflection, or once it is out, it is an ordinary body again and asks everything.
`StructureCrossing.java:100-111`, `ShotSubstrate.java:196-199` [V]

**MECH-SHOT-24 — What a shot MEETS is its body, not its centre line.** The crossing test sweeps the
round's own cylinder, so a wide round meets what it passes beside — which is the contact a graze is
made of, and a round tested as a line could never have one. The block reported is the axis block when
the centre went through material, and a side block only when the centre passed through air; the entry
face follows, being the axis's own face for a head-on meeting and the face turned towards the axis for
a block reached sideways. Below half a block the sweep IS the ray, so the reference body meets exactly
what it always met. `StructureCrossing.java:104-118`, `:180-226`, `SweptVolume.java` [V] [T]

**MECH-SHOT-25 — The width a shot MEETS things with is capped; the width it is PRICED at is not.**
`shotBodyRadiusCap` bounds the geometry alone, because the voxels one slice examines grow with the
cube of the neighbourhood it considers and an absurd calibre would otherwise be a way of making the
server do arbitrary work. The declared cross-section still prices the round in full, so capping the
sweep never makes a shot cheaper — only less wide-reaching than it claimed.
`ShotSubstrate.java:104-112`, `StellurgyConfiguration#shotBodyRadiusCap` [V]

**MECH-SHOT-26 — A glancing solid round skips off METAL, and off nothing else.** Three narrowings, and
each is what keeps a bounce from being a surprise: only a body with MASS deflects, because a beam has
nothing to reflect and its energy is absorbed; only METAL deflects, so a player meets skipping rounds
off a steel hull and never off a plank wall; and only a hit shallower than the configured incidence
angle deflects, which preserves the one ordering that matters — a squarer hit never bounces where a
shallower one did not. The mirrored velocity is computed in the BLOCK's frame, where the normal and the
velocity are the same kind of thing, and rotated back out; restitution takes its cut there, so two
facing plates cannot keep a round forever. A ship that stops answering mid-contact makes the body dig
in instead, which is the recoverable mistake. `ContactResolver.java:74-140`,
`StellurgyConfiguration#ricochetIncidenceDegrees` [V] [T]

**MECH-SHOT-08 — Ship blocks are met by transforming the segment, not by sampling it.** A ship's blocks
stay in its shipyard subspace while the hull flies elsewhere, so both endpoints are mapped into the
ship's frame and the same exact traversal runs there. Candidates come from the loaded ships' grown world
boxes intersecting the segment's own box — over-inclusive by construction, then confirmed in each
candidate's own frame. `StructureCrossing.java:75-121`, `VSBridge#loadedShipWorldBounds` [V]

**MECH-SHOT-09 — An impact is handed over exactly once, with an identity minted by the WORLD.** The
damage service is called with a point, a direction, a budget and a kind — no block, no stage, no ship —
and a fresh identity per contact, so a round that strikes a shell it was let through and then the hull
behind it is not refused the second time by the dedup memory. The identity comes from one monotonic
counter on `ShotRegistry`, never from the shot: an identity assembled out of a shot id and a per-shot
counter has to give each a field, and a round that bores for a few hundred ticks outgrows its own field
and mints the identities the NEXT round will mint. Since the contact seam the call is made by the
resolver's default law rather than by the substrate itself. `ContactResolver.java:196-215`,
`ShotRegistry#nextImpactId`, `ShotSubstrate.java:247-249` [V] [T `ImpactIdentityTest`,
`ArmourAnswersByKindAndAngleTest`]

**MECH-SHOT-27 — Which layer a segment meets first is asked in ONE place.** `LayerCrossing.along`
answers "field or structure, and how far" for everything that travels a line — a shot stepping its
tick, and a beam being held for one. It was four lines inside the shot's own step, correct only while
exactly one thing asked: a weapon that resolved the ordering one way while the substrate resolved it
the other would fire through its own shield, or into a shell it had decided was not there, and no
reproduction would find it. **Ordering is geometric, never a pipeline** — both layers are asked where
they would be crossed and the smaller distance wins, because "shield then hull" is wrong for a body
emitted inside a shell. `LayerCrossing.java` [V] [T].

**MECH-SHOT-28 — A HELD beam is not a shot and has no record.** It does not travel, so there is no
flight to step and nothing to persist: its lifetime is exactly as long as its gun is lit.
`HeldBeam.emit` resolves one tick — the same layer ordering, the same shield seam (priced through the
declared kind mapping and carrying NO body, because a beam has nothing to mirror), and the same contact
seam, so armour answers it exactly as it answers everything else. What does not travel is the BODY; the
STRIKE happens every tick, and a beam that resolved once would be a weapon a player could never see
working. `HeldBeam.java` [V] [T `ABeamIsHeldNotThrownTest`]

**MECH-SHOT-29 — A beam is replicated as a STATE that is repeated, not as two events.** A round is
announced at the muzzle and at the end because its path is determined by the launch numbers; a beam has
no such determinism — it is wherever its gun points THIS tick and it stops the instant the trigger is
released. So the client is sent the current segment whenever it moves or the light goes on or off, plus
a heartbeat while it burns, and it DROPS a beam nobody has mentioned lately. The staleness rule is the
load-bearing half: a gun that is blown up, unloaded, or left behind by a player walking out of range
stops burning with no tick in which to say so, and an edge-triggered channel would leave a beam drawn
across the sky until the player relogged. The heartbeat must therefore be quicker than the client's
timeout, which is a two-sided arrangement the test reads from both ends rather than restating.
`BeamReplication.java`, `PacketBeamState.java`, `ClientBeamTracker.java`, `RenderBeams.java` [V]
[T `BeamReplicationCadenceTest`, `BeamReachesClientTest`]

**MECH-SHOT-32 — A shell that could not pay the whole price does not stop a beam.** `isIntercepted`
is true on an UNDERPAY as well as on a full stop; `isFullyAbsorbed` is the one that means the shell
bought all of it. Asking the first would invert the entire laser line — a beam that OVERPOWERED a shell
would die at it, which is the exact opposite of the reason the family exists (`shields`: a beam whose power
the shell cannot pay for gets through). What the shell could not buy carries on into whatever is
behind it, exactly as a round's residual does. `HeldBeam#emit` [V]

**MECH-SHOT-31** — retired: the substrate has no off switch (see INV-SHOT-06).

**MECH-SHOT-17a — "It used the tick" is true of a bore that STALLED, not of one that got out.** The two are one answer (`passedThrough` with energy left) and two situations, and only the resolution says which: `Resolution.leftTheStructure` is set from the damage walk's stop REASON — `EXITED_FAR_SIDE` and not merely the `EXITED` outcome — and by a responder that answered anything other than "stopped". A round still inside the material has spent this tick's travel and resumes next tick from where it stopped — that is MECH-SHOT-17. A round that came out the far side has NOT spent the tick: it is advanced by the distance the walk actually covered, the rest of the tick's travel is still owing, and whatever stands in it is asked in the same tick.
`ShotSubstrate.java` (the pass-through branch), `ContactResolver.Resolution` [V]
**"The distance the walk actually covered" is to where the body LEFT the last solid slice**, not where it ENTERED it: for a bore resuming inside a block the entry distance is zero, so the round would be advanced by the crossing epsilon and meet the same plate again. `StructureDamageEngine.Walk#exitedFarSide` [V]
A round too poor to buy a stage does not cross a block and come out in 1 / speed ticks: it is STOPPED at the block's face (`structural-damage` MECH-DMG-35, maintainer ruling 2026-10-03) `[T `ShotBoresOverTimeTest#aRoundTooPoorForAStageIsStoppedByTheBlockItMeets`]`.
[T `SpacedArmourIsAskedTwiceTest` — two legs, slow and fast, and the slow one is the control that keeps a "nothing ever arrived" pass from reading as a green]

**MECH-SHOT-18 — Boring costs a body its SPEED, where speed and energy are coupled at all.** Kinetic
energy goes as the square of speed, so a round that has spent a fraction of its energy keeps the square
root of what remains (`v' = v·sqrt(E'/E)`) — written as a ratio, which needs no mass and so cannot
divide by one that is zero. `KINETIC` and `EXPLOSIVE` slow. A `BEAM` does not: a beam that has spent
half its energy is dimmer, not slower, so there is no relationship to apply — it pays for depth like
everything else (MECH-DMG-01a) and ends by running out. `ShotSubstrate.java:104-130` [V]

> **Ruled 2026-08-18, and it is not a gap**: a `BEAM`-kind shot is a PULSE — a bolt of light, which is
> a legitimate weapon family and is what this substrate models. A CONTINUOUS beam, which burns while it
> is held and whose depth grows with dwell, is a different family, modelled by `HeldBeam` (`HeldBeam.java:21-40`);
> rapid pulses are the same substrate with a short fire interval and need nothing new. So the lump here
> is one of three weapons rather than one of two theories.

**MECH-SHOT-19 — A block is paid for once per bore, not once per tick.** A round that spent the tick
inside a block it could not leave declares its next impact as a RESUMED one, and the engine does not
charge for the voxel the walk starts in. Without it a slow round grinds a block to dust without moving
and is strictly deadlier than a fast one, which is the opposite of what the speed law says.
`ImpactRequest.java:85-99`, `StructureDamageEngine.java:158-166`, `ShotSubstrate.java:150-153` [V]

**MECH-SHOT-16 — A contact carries the geometry of the meeting, in the block's OWN frame.** The entry
face comes from the traversal, which is the only thing that knows which axis it stepped — deriving it
afterwards from the entry point is ambiguous at a corner. Face, block position and the body's velocity
are all expressed in the block's frame (subspace on a ship), because an incidence angle between a
world-frame velocity and a subspace face is not an angle at all; only the hit POINT stays world, which
is where the flash goes. `SweptSegment.java:28-40,95-107`, `Contact.java:22-28`,
`ContactResolver.java:96-114` [V] [T `SweptSegmentTest`, `ContactGeometryTest`]

**MECH-SHOT-10 — A launch is refused, never traded for.** Over the per-world cap the registry answers
`-1` and admits nothing; it never drops a round already in flight to make room, because that would turn
a burst of cheap fire into a way of deleting incoming fire. `ShotRegistry.java:74-85` [V]

**MECH-SHOT-11 — The environment is declared at the muzzle.** Gravity travels with the shot rather than
being looked up per tick, because the lookup's answer changes when a region unloads and a round that
curves differently depending on who is watching cannot be aimed. `ShotEnvironment.java:6-16` [V]

**MECH-SHOT-13 — A client is TOLD about a round whose path passes near it, once.** The path is fully
determined by the launch numbers, so replication is one packet at the muzzle plus one at the end rather
than a position stream: the client integrates its own copy with the same arithmetic and is corrected
when the server says the round stopped. The end packet is what keeps a client from drawing a round
sailing through the hull that stopped it. `ShotReplication.java:43-79`, `PacketShotSpawn.java:12-27`,
`PacketShotEnd.java:12-21`, `ClientShotTracker.java:13-28` [V]
[T `ShotReachesClientTest`]

**MECH-SHOT-14 — Who is told is decided by the PATH, not by the muzzle.** A player is told when the
round's forward segment (capped at 200 ticks of flight) passes within `shotVisibilityRadius` of them —
which is what makes a round fired from four kilometres away visible to the person it is fired AT, the
case a muzzle-distance filter gets exactly backwards. The end packet is keyed on the end point instead:
a player too far to be in range there cannot see the impact either, and their copy ages out on its
stated lifetime. `ShotReplication.java:33-40,43-79`, `ProximityBroadcast.java` (MECH-SHOT-30) [V]
[T `ShotReachesClientTest` — the far-shot control]

**MECH-SHOT-12 — A shot ends for a stated reason at a stated place, remembered briefly after it is
gone.** Absence from the registry cannot tell a hit from a timeout, so the last 64 endings keep their
reason **and the WORLD point they ended at** — world-frame even when what stopped the round was a
ship's block filed under a shipyard address millions of blocks away, because a weapon showing an
impact needs the place a player can see. Not persisted: it answers a question asked seconds later, not
world state. `ShotRegistry.java:41-52`, `:100-140` [V]
[T `ShotSubstrateTest#aShotEndsForAStatedReasonRatherThanJustDisappearing`,
`ShotHitsShipHullTest`]

## Invariants

**INV-SHOT-01 — Server only.** Shots are admitted and stepped on the logical server. The client keeps
a parallel set of DRAWINGS (`ClientShotTracker`) which it steps with the same arithmetic; nothing in the
mod reads them, no hit is resolved from them, and a client that received no packet plays the same game
with less to look at. The drawings — rounds and beams alike — are held BY the client world, through
the `ClientWorldDrawings` capability attached to every client world: a new world starts with new,
empty ones, and nothing clears a shared set. The spawn/end/beam packets are applied on the client
thread, to the drawings of the world current at that moment. `ShotSubstrate.java:70,79`,
`ClientShotTracker.java:13-28`, `ClientWorldDrawings.java` [V]

**INV-SHOT-02 — A shot belongs to exactly one world for its whole life.** The registries are per-world
objects with no reference to each other, so two shots in different worlds cannot interact — structurally,
not by comparing dimension ids anywhere. `ShotRegistry.java:21-27` [V]
[T `ShotSubstrateTest#aShotIsOnlyEverInTheWorldItWasFiredIn`]

**INV-SHOT-03 — The traversal never skips a voxel it passes through.** Consecutive reported voxels are
face-adjacent and entry parameters are non-decreasing within `[0,1]`. `util/SweptSegment.java:75-98` [V]
[T `SweptSegmentTest#consecutiveVoxelsTouchFaceToFace`]

**INV-SHOT-08 — A round is still there the tick after it met a hull.** Meeting structure is not an
ending; running out of budget or of speed inside it is. [T] `ShotBoresOverTimeTest`, falsified
(make the meeting terminal and the test reddens on its own assertion)

**INV-SHOT-07 — A block that answers nothing is treated exactly as it was before the contract.** The
default law is the old behaviour verbatim, so the seam is falsifiable without any armour existing: with
it neutered, the damage corpus reddens on its own assertions. [T] the damage corpus

**INV-SHOT-04 — "There is structure here" has one definition.** The crossing test and the damage engine
both call `StructureDamageEngine.isStructure`, so a shot cannot stop where the engine would spend
nothing, or bore through what it already passed. `StructureDamageEngine.java:179-187`,
`StructureCrossing.java:137` [V]

**INV-SHOT-05 — An unloaded voxel is skipped, never read as empty and never as solid.** Nobody looked.
See the gap below. `StructureCrossing.java:134-136` [V]

**INV-SHOT-06** — retired: the war is always on (maintainer ruling 2026-10-03, *"Убираем этот гейт, слишком жирно. Война всегда включена."*). No key switches the mechanic off and the substrate has no off state: a launch is refused only for a client world or a full registry.

## Failure modes & edge cases

**Unloaded terrain is transparent.** Stage 1 tests only loaded chunks, which is honest in
the pose band ships fly in (there are no world blocks there) and NOT honest on a planet: a round crosses
unloaded terrain untouched. Closing it is stage 2's conservative per-chunk occupancy summary plus a
short, capped load request for candidates — and until that lands, a ground battery is not a shipped
feature. [A]

**`WORLD_UNLOADED` is declared and never emitted.** One of the five end reasons has
zero producers in the tree; a round whose world goes away is simply left in that world's
`WorldSavedData` and resumes when the world comes back, which is defensible behaviour but is not the
one the constant describes. So the enum promises an outcome nobody can ever observe, and any consumer
switching on it has a dead branch. It is RESERVED rather than dead: the stage that
would produce it is the terrain-occupancy work, whose capped load request has to be able to give up
on a region that never resolves. Whether the give-up ends a shot for this reason or the constant goes
is undecided. `ShotEndReason.java:27` [V]

**Replication is by proximity, not by DETECTION.** A round is drawn for anybody whose
view its path crosses; there is no sensing layer deciding who has EARNED knowledge of a shot (a radar
contact, a spotter, line of sight). Existence and detection were meant to be separate, and today
proximity stands in for both. A player who comes into range mid-flight is told nothing — the spawn
packet has already been sent. `ShotReplication.java:28-40` [A]

**Guidance is a reserved token nothing reads.** It round-trips a save so that the layer
which eventually steers has somewhere to put its target without every round in flight becoming
unreadable. [V]

## Relationships

- **shields** — the field layer. This subsystem builds a `ShieldStrike` at the shot's own kind
  (MECH-SHOT-20) and reads `ShieldStrikeResult`; it never computes a deflection or spends shield
  energy.
- **structural-damage** — the structure layer. This subsystem builds `ImpactRequest.penetrating(...)`
  and ignores the report; it names no block and no ship.
- **integration-vs** — the ship-frame port: `loadedShipWorldBounds` for the broad phase,
  `toShipFrameFor` / `toWorldFrameFor` for the segment, and the same pair plus
  `rotateTo*FrameFor` / `shipVelocityAtPointFor` for a round that is riding a hull (MECH-SHOT-21).
- **structural-damage** — shares `util.SweptSegment`: the blocks a shot STOPS at and the blocks a
  budget is SPENT into are found by the same traversal, so they cannot be two different sets
  (MECH-DMG-17).
- **network-wire** — `PacketShotSpawn` / `PacketShotEnd`, the two packets a flight costs.
- **weapons** — the first thing that fires one. It declares a `ShotSpec` and reads nothing back but the id.
- **stellurgytest-probe-catalog** — `/stellurgytest shot fire|list|read|clear`, and `/stellurgytest chunk loaded`.
