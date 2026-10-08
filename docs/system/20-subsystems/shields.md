---
id: shields
owns: [affs/]
entrypoints: [ShieldNetworkManager#DOMAIN, TileEntityFieldGenerator#update, ShieldStrikeService#resolve]
depends-on: [subsystem-network, structural-damage, api-public, network-wire, integration-vs]
depended-by: [turrets (planned), client-render]
contracts: [C1, C3, C4]
confidence: high
---

## Purpose

The ship/base **shield subsystem**: a built network of blocks that converts FE into a distinct *shield
energy*, projects one blended force field from its emitters, and spends that energy absorbing impacts.
Everything about a shield is construction-derived — coverage, regeneration rate and reserve emerge from
which blocks the player placed and where, never from a single "shield power" setting. Vendored from the
maintainer's AFFS project and reworked to Stellurgy's design (registry domain stays `affs:`).

### What a shield is FOR — the intent every balance decision here answers to

**A shield is a way to stay alive long enough to destroy the other ship or to leave, and both of those
are supposed to be quick.** It is not an answer to every problem and it is not meant to hold
indefinitely: a shell that could hold a powerful laser for a long time would need a power source of the
top tier, and spending one of those on holding a shield is a bad trade by design.

Recorded here (maintainer 2026-08-19)
because it governs the whole line: it is the reason a beam whose power the shell cannot pay for gets
THROUGH, and the next person tuning a regeneration rate or a reserve is the one who most needs to have
read it. A tuning that makes a shield comfortable to sit behind has broken this intent even if every
individual number looks defensible.

## Responsibility boundary

**Owns**: the shield-energy network (supply/transport/demand solve), the field's geometry (an SDF
smooth-union of emitter spheres), impact absorption (entity, explosion, declared strike), the world- vs
ship-frame seam for the field, and the domain-level control layer (priority groups, access credential).

**Does NOT own**: FE generation (any mod's power system feeds the generator), the ship itself or its
identity (Valkyrien Skies / Stellurgy ship data — the shield only *reads* which ship manages a block), weapons
(a weapon declares a strike; the shield only absorbs it), and rendering of the field beyond supplying
the geometry.

## Key types

| type | role |
|---|---|
| `TileEntityShieldGenerator` | FE → shield energy conversion; a small smoothing buffer, not a reserve |
| `TileEntityShieldAccumulator` | bulk shield-energy reserve; both source and sink (dual-role) |
| `TileEntityShieldCable` | transport; the only link with a finite per-tick throughput |
| `TileEntityFieldGenerator` | the **emitter** — holds a coil, projects a sphere, absorbs impacts, owns its priority + access code |
| `TileEntityShieldConsole` | stateless diagnostic panel + editor of the domain config |
| `ISubsystemSource` / `ISubsystemSink` / `ISubsystemCable` | node roles, from the SHARED network — the tiles implement them directly; not exclusive (an accumulator is source **and** sink) |
| `IShieldNetworkController` | the one role that stayed shield-specific: a console additionally carries the resistance bias |
| `ShieldNetworkManager` | **the domain handle only** (73 lines): names the commodity, makes the state, and carries the console's bias across a rebuild. Everything structural is the shared solver. Its solver state is a per-WORLD part (`WorldRuntime`, `WorldState`, `ShieldNetworkManager.java:78-83`) — no JVM-wide map keyed by dimension number |
| `ShieldNetworkRegistry` | the shield nodes loaded in ONE world — `ShieldNetworkRegistry.of(world)` is that world's own part (`WorldRuntime`), reached by every tile's register / unregister, so it goes with the world and a later world reusing the dimension number starts empty (`ShieldNetworkRegistry.java:14-26`) |
| `ShieldNetworkState` | one network's live statistics + resistance bias |
| `ShieldCondition` | what a node's damage stage does to what it delivers — the scalar derate and the emitter's radius shrink |
| `FieldSurfaceMath` | the SDF: shell distance, smooth-union, reflection, ray/shell entry |
| `FieldZoneMath` | Voronoi "responsible area" — which emitter owns a surface point |
| `FieldFrame` / `WorldFieldFrame` / `ShipFieldFrame` / `FieldFrames` | the world-vs-ship frame seam |
| `ShieldStrike` / `ShieldStrikeKind` / `ShieldStrikeResult` / `ShieldStrikeService` | the cooperative strike interface |
| `ShieldCoverage` | "is this block under the field?" for subsystems that are not the shield — frame conversion and the volume-vs-membrane distinction kept on this side. A geometry READ: nothing is absorbed and no shield energy is spent, so a caller that needs the shield to pay declares a strike instead |
| `ShieldDomains` / `ShieldDomainConfig` / `ShieldPriorityGroup` / `ShieldControlData` / `ShieldControl` | the domain-level control layer |

## Mechanics

Network formation, the max-flow solve and the priority tiers are the shared
[subsystem-network](./subsystem-network.md) primitive's (MECH-NET-01…08); a shield node is an ordinary
node of it, and MECH-SHD-01…03 below only say what that means for a shield.

What is shield-specific there, and only this: the resistance bias a console edits
(`ShieldNetworkState`, `IShieldNetworkController`), and the two readouts that distinguish production
from stored and upkeep from requested (`TileEntityShieldGenerator.getGenerationPerTick`,
`TileEntityFieldGenerator.getConsumptionPerTick`). `ShieldNetworkManager.java:20-45` [V]
`ShieldNetworkManager.getState` narrows the shared state to the subclass carrying the bias; a node
names its own domain through `ISubsystemNetworkNode.getNetworkDomain`.

**MECH-SHD-01 — Network formation is block adjacency, not cabling.** A generator touching an emitter is
already a working shield; cables are a scaling tool, not a tax. Owned by `subsystem-network` MECH-NET-01/05.

**MECH-SHD-02 — Energy routing is a max-flow solve over a supply/demand port model.** Owned by
`subsystem-network` MECH-NET-02/03.

**MECH-SHD-03 — Redistribution is priority-tiered.** A scarce supply fills the highest-priority emitters
first and equal priorities share the remainder. Owned by `subsystem-network` MECH-NET-04
[T `ShieldPriorityRedistributionTest`]

**MECH-SHD-04 — The field's VOLUME is a smooth-union of emitter spheres; its MEMBRANE is each
emitter's own.** Two questions are asked of a shield and they have two answers. "Is this point under
the shield" is the volume: a signed distance with the per-emitter spheres blended by a smooth minimum,
which is what makes several emitters read — and render — as one continuous body
(`FieldSurfaceMath#compositeHullDistance`; the renderer and the `shield zone` probe read it). "Is this
box touching the shield" is the membrane, a sphere surface one block thick tested per emitter with no
blend (`FieldSurfaceMath#intersectsCompositeShell`); that is what a body crossing the field meets. A
point deep inside a bubble is inside the volume and nowhere near the membrane. There is no third question named for the volume that answers for the
membrane. [V]

**MECH-SHD-05 — An emitter regenerates at a finite, tier-scaled throughput.** Its advertised network
demand is `min(free capacity, throughput)`, and that advertised demand is the single source of truth for
the per-tick cap — the coil imposes no second throttle. Throughput scales with the block's tier.
`TileEntityFieldGenerator.java:336-356` [V] [T `ShieldZoneThroughputTest`]

**MECH-SHD-06 — Two draws.** Holding a powered field costs a small passive maintenance draw proportional
to surface area, spread over a 20-tick cycle; refilling after damage is the larger, throughput-capped
draw. `TileEntityFieldGenerator.java:659-663` (maintenance estimate) [V]

**MECH-SHD-07 — Activation is hysteretic.** A dark field must reach a configured fraction of coil
capacity to light; once lit it stays lit until the coil hits zero — so a shield does not flicker at the
threshold. `TileEntityFieldGenerator.java:694-707` [V]

**MECH-SHD-08 — Per-zone collapse.** Only powered, frame-ready emitters contribute to the composite
surface, so a starved emitter's zone drops out while its neighbours hold.
`FieldSurfaceMath.java:83-108` [V] [T `ShieldZoneThroughputTest`]
The emitters considered are the WORLD's own: each server world holds its loaded emitters and every
player's last safe side of a shell (`TileEntityFieldGenerator.WorldEmitters`, a `WorldRuntime` part),
so they end with the world. They are never JVM-wide: server stop unloads worlds without unloading chunks, so a JVM-wide set would leave the previous world's powered emitters standing
in the next world's dimension of the same number. `TileEntityFieldGenerator.java:52-66` [V]

**MECH-SHD-09 — Kinetic impacts are reflected, energy projectiles absorbed.** The emitter scans its
influence box each tick; a body sweeping inward through the shell is pushed back out along the surface
normal, while an energy projectile is consumed. Absorption is all-or-nothing: a hit the coil cannot fully
cover passes through without burning a partial charge.
`TileEntityFieldGenerator.java:403-422,484-520,602-620` [V] [T `ShieldImpactAbsorptionTest`]

**MECH-SHD-10 — Explosions are absorbed at the resolution layer.** A detonate handler charges the field
for the blast's protected blocks and then removes those blocks and shell-intersecting entities from the
explosion's effect lists. `ForceFieldExplosionHandler.java:22-64` [V] [T `ShieldImpactAbsorptionTest`]

**MECH-SHD-11 — The frame seam makes the field ship-capable without duplicating the geometry.** Because a
sphere is rotation-invariant, only each emitter's *centre* is mapped to world space; all SDF, collision
and reflection math stays world-frame. A standalone field uses the identity frame; a ship-managed block
uses its ship's frame; a block in the shipyard that no ship claims yet gets the never-ready
`UnnamedShipFieldFrame` (MECH-SHD-24). `FieldFrame.java:23-44`, `FieldFrames.java:19-30` [V]

**MECH-SHD-12 — Impact velocity is relative to the shell.** The impact cost and the deflection subtract
the shell's own velocity before reflecting and add it back on commit, so a cruising ship does not bill
its own crew. Standalone the shell velocity is zero, making the identity path unchanged.
`TileEntityFieldGenerator.java:245-249,757-761` [V] [T `VSShipFrameShieldTest`]

**MECH-SHD-13 — An unresolvable ship frame fails open.** A ship-framed emitter whose ship is not loaded
on this side contributes no shell at all, rather than projecting one at its subspace coordinates.
`FieldSurfaceMath.java:100-104` [V]

**MECH-SHD-14 — A cooperative strike is absorbed with its declared energy.** A weapon hands the shield a
strike (energy + kind + ray); the service finds the nearest powered shell the ray enters and spends
`min(stored, energy × rate × kindMultiplier / tierEfficiency)`. Full pay stops the strike at the shell;
a short pay lets the remainder through and drains the shield toward zero. A full pay additionally
*reflects* rather than stops, when the strike declares a body (MECH-SHD-20).
`ShieldStrikeService.java:28-90` [V] [T `ShieldStrikeAbsorptionTest`]

**MECH-SHD-15 — Only rays entering from outside are intercepted.** A ray whose origin is already inside
the shell is never billed, so a shooter under its own shield and interactions inside the bubble are
unaffected. `FieldSurfaceMath.java:116-152` [V] [T `FieldStrikeMathTest`]

**MECH-SHD-16 — Resistance bias trades energy protection against physical.** One network-level slider
sets the multiplier applied to radiant vs kinetic impacts; the same formula serves both the entity scan
and declared strikes. `TileEntityFieldGenerator.java:649-657` [V]

**MECH-SHD-17 — Priority groups push a setting the emitter owns.** A group is a named selection with a
priority; editing it writes that priority into member emitters. Deleting the group leaves the emitters
tuned as they were, because the setting itself is emitter-owned.
`ShieldControl.java:39-113`, `ShieldDomainConfig.java:65-90` [V] [T `ShieldPriorityGroupControlTest`]

**MECH-SHD-18 — Consoles are stateless editors of one domain config.** Configuration is authoritative at
the domain (ship or dimension) and persisted in world storage, so every console edits the same data and
destroying one loses nothing. `ShieldControlData.java:35-70`, `ShieldDomains.java:29-39` [V]
[T `ShieldPriorityGroupControlTest`]

**MECH-SHD-19 — The access credential is a rotatable carried code, not an identity.** Authorisation asks
whether the entity carries a code device matching the emitter's code; rotating regenerates it across the
domain, so a leak is answered without touching identity or grouping.
`CodeUtils.java:43-71`, `ShieldControl.java:119-130` [V] [T `ShieldPriorityGroupControlTest`]

**MECH-SHD-20 — A declared strike may carry its travelling BODY, and a full pay reflects it.** What the
field does with a kinetic impact is decided by whether a body exists, not by whether that body is a Forge
`Entity`: an entity is mirrored by the per-tick scan (MECH-SHD-09), a strike declaring a velocity is
mirrored by the service, and a strike declaring none is absorbed. The mirror is the entity path's own law
— shell velocity subtracted, reflected about the outward normal, shell velocity added back (MECH-SHD-12)
— scaled by a restitution tunable at 1.0, so at the default the two populations are identical. It scales
speed only: the bill stays MECH-SHD-14's. A short pay never reflects, and reflection is a specialisation
of full interception, so a reader that ignores it still sees "intercepted, nothing passed".
`ShieldStrikeService.java:75-84`, `TileEntityFieldGenerator.java:276-286`,
`ShieldStrikeResult.java:60-64` [V] [T `ShieldStrikeAbsorptionTest`]

**MECH-SHD-21 — A damaged emitter projects a smaller sphere, and is billed for the one it declared.**
The radius splits in two: the DECLARED radius is the player's setting, and the PROJECTED radius is that
one scaled by the emitter block's own damage stage (rounded down, floored at `MIN_RADIUS`, re-derived
each tick rather than accumulated, so mending the block gives the field back). Every geometric consumer
reads the projected one through `getRadius()` — SDF, zone partition, ray entry, influence box, the
replicated snapshot — while `getShieldCycleCost` prices the declared one, so damage never lowers the
bill. The stage is PULLED, exactly as a turret pulls its own (`weapons` MECH-GUN-28): the damage engine
knows nothing about shields. `ShieldCondition.java:56-79`, `TileEntityFieldGenerator.java:130-198,669-677`
[V] [T `ShieldDamageDegradesTest`, `ShieldConditionTest`]

**MECH-SHD-22 — A damaged scalar node delivers less of what it is for.** A generator converts less FE
per tick, a cable carries less, an accumulator accepts up to a smaller reserve (what is already banked
is not destroyed — it simply cannot be topped back up). One coefficient covers all three; it is the
motor-thrust shape applied to a second subsystem. `ShieldCondition.java:68-73`,
`TileEntityShieldGenerator.java:158-172`, `TileEntityShieldCable.java:103-112`,
`TileEntityShieldAccumulator.java:104-107,132-143` [V] [T `ShieldDamageDegradesTest`]

**MECH-SHD-23 — The hole a shrunken emitter leaves is closed by whichever neighbour still reaches.**
Not implemented anywhere: the composite surface is a smooth union of the ACTIVE emitters' spheres
(MECH-SHD-04), so a neighbour whose own sphere still covers the point covers it, and one that does not
leaves it open. Zone ownership and coverage come apart here — the damaged emitter is still the nearest,
and not the one holding the ground. `FieldSurfaceMath.java:37-47` [V] [T `ShieldDamageDegradesTest`]

**MECH-SHD-24 — Nothing of a shield runs on a ship that is not named yet.** A ship's chunks load before
its ship object exists (and anything that loads a region can pull a shipyard chunk in), so in that
window a shield block's position is a shipyard address. Two halves: the emitter waits at the top of its
server tick — no coil draw, no projection, no containment — and `FieldFrames.forBlock` answers the
never-ready `UnnamedShipFieldFrame` for such a block, so it contributes no shell (MECH-SHD-08's
frame-ready filter) and a block there is covered by none. The frame seam must not answer the
identity frame for it, which would put a live, world-framed shell millions of blocks from the ship.
`TileEntityFieldGenerator#update`, `FieldFrames#forBlock`, `VSIntegration#isOnUnnamedShip` [V]
[T `ShieldTwoBlockFloorTest#anEmitterInTheShipyardThatNoShipClaimsProjectsNothing`]. The other shield
tiles (generator, console, accumulator, cable) keep running `[A]`: their work is network energy and
configuration between blocks of one ship, all in one shipyard frame — not read at source, in particular
what `ShieldDomains.forBlock` answers for an unnamed ship's block (not pinned).

## State & persistence

| key | owner | note |
|---|---|---|
| `radius`, `energy`, `fieldPowered` | emitter TE | declared field size + coil charge, clamped to the storage max on read |
| `effectiveRadius` | emitter TE | the projected radius (MECH-SHD-21). Saved but derived; REPLICATED because the client cannot compute it — a block's damage stage is server-side state |
| `accessCode` | emitter TE | Layer-3 credential (rotatable) |
| `priority` | emitter TE | redistribution priority |
| `shieldDrainPhase`, `shieldReceivedThisTick`, `shieldConsumedThisTick` | emitter TE | maintenance cycle + per-tick telemetry |
| `domains` → `groups` → `{name, priority, members}` | `ShieldControlData` (world storage) | domain-level priority groups |

Emitter NBT: `TileEntityFieldGenerator.java:899-935` [V]. Control data:
`ShieldControlData.java:72-93` [V]. Network state is **not** persisted — it is rebuilt from the world's
blocks each session, so nothing about topology can rot in a save [V].

## Invariants

- **INV-SHD-01** [V][BEH] A network with no source or no sink publishes a disconnected state and moves no
  energy. Now the shared solver's rule, not a shield one (`subsystem-network`)
  `SubsystemNetworkManager.java:345-348`
- **INV-SHD-02** [A][BEH] Two adjacent shield blocks form a network with no cable; a one-block gap forms none.
  `ShieldTwoBlockFloorTest` Pinned by `ShieldTwoBlockFloorTest#twoBlockShieldPowersWithoutCable`, `ShieldTwoBlockFloorTest#nonAdjacentPairNeverPowers`.
- **INV-SHD-03** [A][BEH] An emitter never receives more than its recharge throughput in a tick, regardless of
  the size of the supply behind it. `ShieldZoneThroughputTest` Pinned by `ShieldZoneThroughputTest#regenerationIsThroughputCapped`.
- **INV-SHD-04** [A][BEH] Energy is conserved across the solve: a bulk store is not drained faster than the
  emitters actually intake. `ShieldAccumulatorTest` Pinned by `ShieldAccumulatorTest#accumulatorReserveIsConservedNotBled`.
- **INV-SHD-05** [A][BEH] A charged coil absorbs a single impact costing more than its per-tick intake.
  `ShieldImpactAbsorptionTest` Pinned by `ShieldImpactAbsorptionTest#chargedCoilAbsorbsEnergyProjectileCostingMoreThanIntake`.
- **INV-SHD-06** [A][BEH] A kinetic projectile is deflected (still alive, outside the shell), not consumed.
  `ShieldImpactAbsorptionTest` Pinned by `ShieldImpactAbsorptionTest#chargedShieldDeflectsAnArrow`.
- **INV-SHD-07** [A][BEH] A shield with no charge intercepts nothing and spends nothing.
  `ShieldStrikeAbsorptionTest` Pinned by `ShieldStrikeAbsorptionTest#chargedShieldFullyAbsorbsACooperativeStrike`.
- **INV-SHD-08** [T][BEH] A strike the shield cannot fully pay for is partially absorbed and leaves a residual.
  `ShieldStrikeAbsorptionTest` Pinned by `ShieldStrikeAbsorptionTest#strikeGracefullyPenetratesAShieldItOutmatches`.
- **INV-SHD-09** [A][BEH] Deleting a priority group does not change any member emitter's priority.
  `ShieldPriorityGroupControlTest` Pinned by `ShieldPriorityGroupControlTest#groupPushesPriorityIntoMemberEmitters`.
- **INV-SHD-10** [A][BEH] A group created at one console is visible and editable at another, and survives that
  console's destruction. `ShieldPriorityGroupControlTest` Pinned by `ShieldPriorityGroupControlTest#anyConsoleEditsTheSameDomainConfig`.
- **INV-SHD-11** [A][BEH] Rotating the access code changes the credential on every domain emitter and leaves
  grouping and priority untouched. `ShieldPriorityGroupControlTest` Pinned by `ShieldPriorityGroupControlTest#rotatingAccessCodeChangesCredentialButNotGrouping`.
- **INV-SHD-12** [A][BEH] A single cable carries more than a single emitter can absorb, so plumbing is not the
  ordinary limiter. `ShieldLimiterBalanceTest` Pinned by `ShieldLimiterBalanceTest#cableCarriesMoreThanASingleEmitterAbsorbs`.
- **INV-SHD-13** [V][SYS] With a single priority value the tiered solve is one augmentation pass, so the
  default configuration behaves exactly as plain max-flow. `SubsystemNetworkManager.java:421-429` FOR: INV-SHD-04.
- **INV-SHD-14** [A] The per-tick network solve cost stays acceptable on large hulls; the solve is run per
  dimension every tick against cached topology, but no load test exists. (unconfirmed)
- **INV-SHD-15** [T][BEH] Two strikes identical but for a declared body diverge only in outcome, never in
  price: the one with a body is reflected, the one without is stopped, both billed the same.
  `ShieldStrikeAbsorptionTest` Pinned by `ShieldStrikeAbsorptionTest#aDeclaredBodyIsReflectedWhereAnIdenticalBodilessStrikeIsStopped`.
- **INV-SHD-16** [T][BEH] A reflected body leaves along the outward normal and never faster than it arrived —
  restitution is clamped to `[0, 1]`, so the shell cannot return energy it never absorbed.
  `ShieldStrikeAbsorptionTest` Pinned by `ShieldStrikeAbsorptionTest#aDeclaredBodyIsReflectedWhereAnIdenticalBodilessStrikeIsStopped`.
- **INV-SHD-17** [T][BEH] A short pay never reflects, whatever it carries: the shield spent everything it had
  and the body continues downstream. `ShieldStrikeAbsorptionTest` Pinned by `ShieldStrikeAbsorptionTest#aBodyThatOutmatchesTheShieldPenetratesInsteadOfBouncing`.
- **INV-SHD-18** [T][BEH] A damaged emitter covers strictly less ground than the same emitter pristine, and
  a block on the old shell's edge stops being covered. `ShieldDamageDegradesTest` Pinned by `ShieldDamageDegradesTest#aDamagedEmitterCoversLessAndIsStillBilledForWhatItDeclared`.
- **INV-SHD-19** [T][BEH] Damage never moves the declared radius nor the cycle cost — being shot at cannot
  make a shield cheaper to hold. `ShieldDamageDegradesTest` Pinned by `ShieldDamageDegradesTest#aDamagedEmitterCoversLessAndIsStillBilledForWhatItDeclared`.
- **INV-SHD-20** [T][BEH] A neighbour that still reaches closes a shrunken emitter's hole; one that does not
  leaves it open. `ShieldDamageDegradesTest` Pinned by `ShieldDamageDegradesTest#aNeighbourThatStillReachesClosesTheHoleAndOneThatDoesNotLeavesIt`.
- **INV-SHD-21** [T][BEH] A generator, a cable and an accumulator each deliver less when damaged, as an
  ordering. `ShieldDamageDegradesTest` Pinned by `ShieldDamageDegradesTest#aDamagedGeneratorCableAndAccumulatorEachDeliverLess`.
- **INV-SHD-22** [A][BEH] An emitter that is still standing still projects something, and a mended one
  projects its whole declared field again. `ShieldConditionTest` Pinned by `ShieldConditionTest#aStandingEmitterAlwaysProjectsSomething`, `ShieldConditionTest#repairRestoresTheWholeFieldBecauseNothingIsAccumulated`.
- **INV-SHD-23** [V] "Which emitters are loaded in this world" has one answer and it lives in the
  server's network registry: `TileEntityFieldGenerator.loadedIn(world)` reads
  `SubsystemNetworkManager.nodesIn`, which an emitter joins on load and leaves on break, chunk unload
  or world unload — so the list cannot fall out of step with the network and cannot outlive the
  server. Empty for a client world. Every shell query, strike, explosion, sync snapshot and domain
  command reads it. No JVM-wide static set duplicates it. `TileEntityFieldGenerator#loadedIn`, `SubsystemNetworkRegistry#nodesIn` [V]
  [T `ShieldTwoBlockFloorTest#aWorldAnswersForTheEmittersLoadedInItAndNoOthers`: one emitter per world
  at the same coordinates, each world answering for its own; a chunk cycle leaves one, not two]
- **INV-SHD-25** [T][BEH] A charged emitter in the shipyard that no ship claims projects no shell and spends
  nothing from its coil, beside a control at ordinary coordinates that does both (MECH-SHD-24;
  `ShieldTwoBlockFloorTest#anEmitterInTheShipyardThatNoShipClaimsProjectsNothing`, each half
  red-witnessed). Silent about the race itself — a real ship loading in the background.
- **INV-SHD-24** [T][BEH] An emitter holds a player at its membrane on the side of ITS OWN shell he was last
  seen clear of — outside if he came from outside, inside if from inside, held out if it never saw him
  clear. The side is kept per emitter, per player, on the tile (not saved; dies with it), so a player
  inside one shield and outside an overlapping one has a side for each. A single JVM-wide
  map per player would hold whichever emitter wrote last, and a shield could take its in/out decision from a
  neighbour's record.
  `TileEntityFieldGenerator#shouldRepelEntity` [V]
  [T `ShieldMembraneClientGroupTest#aShieldHoldsOutAPlayerWhoCameFromOutsideEvenFromInsideAnother`]

## Failure modes & edge cases

- **Unloaded ship** — a ship-framed emitter degrades to "no shell" rather than projecting at the
  shipyard (MECH-SHD-13). The failure is silent by design; the emitters probe reports `frameReady`.
- **Starvation** — an emitter that cannot re-cross its activation threshold from its generator's small
  buffer stays dark; its zone collapses while others hold (MECH-SHD-08).
- **Partial absorption** — a strike or beam larger than the reserve penetrates with a reduced residual
  rather than being fully stopped or fully ignored (MECH-SHD-14).
- **Non-cooperating hitscan is not intercepted.** Explosions and travelling projectiles are covered, but
  an instantaneous third-party raytrace passes through the shell. Deliberate, deferred — the only general
  hook also fires on AI line-of-sight rays.

## Integration seams

- **Forge events**: the world-tick solve is not subscribed here — the shared
  `SubsystemNetworkEvents` is the one subscriber for every domain, driving the running server's
  `SubsystemNetworkManager` (see subsystem-network INV-NET-07), so shields and
  weapons do not pay for two tick handlers. Shield tiles reach that manager through
  `SubsystemNetworkManager.of(world)`. Explosion detonate stays
  `ForceFieldExplosionHandler.java:22-23`. All shield handlers are registered under **Stellurgy's**
  container, not the vendored guest id — a guest-id subscriber is silently skipped [V].
- **Valkyrien Skies**: read-only, through `VSIntegration` — which ship manages a block, and the
  transforms behind `ShipFieldFrame`.
- **Weapons**: `ShieldStrikeService.resolve` is the declared-strike entry point that turrets implement.
- **Client**: field geometry is fed to the render cache; a ship-framed field is rebuilt every draw
  because its centre moves, while a standalone field is cached.

## Config surface

All balance magnitudes are tunable and none are pinned by tests. Categories in
`affs/config/ModConfig.java`: `impact` (per-impact energy derivations), `shield` (activation threshold,
resistance bias, tier efficiency, emitter throughput base + tier step, maintenance coefficient, cable
throughput, and the two damage-consequence coefficients — `shieldNodeDamagePenaltyMax` for the scalar
nodes, `emitterRadiusDamagePenaltyMax` for the emitter's radius; either at 0 makes battle damage free
for that component and nothing else changes), `buffers` (generator / coil / accumulator capacities), `weapons` (strike absorption rate,
damage→energy factor, reflection restitution — clamped `[0, 1]`, default 1.0 = the entity path's perfect
mirror, and it scales the reflected speed only, never the cost).

**Full-disable path**: there is no master off-switch — a shield is disabled by not building one, which is
the construction-derived design intent rather than a config gap.

## Test coverage

`ShieldTwoBlockFloorTest` · `ShieldAccumulatorTest` · `ShieldImpactAbsorptionTest` ·
`ShieldZoneThroughputTest` · `ShieldPriorityRedistributionTest` · `ShieldStrikeAbsorptionTest` ·
`ShieldPriorityGroupControlTest` · `ShieldLimiterBalanceTest` · `ShieldDamageDegradesTest` ·
`VSShipFrameShieldTest` (client) · `ShieldMembraneClientGroupTest` (client) · `FieldZoneMathTest`, `FieldFrameTest`, `FieldStrikeMathTest`,
`ShieldConditionTest` (unit) · `AffsVendorSmokeTest`.

## Open questions

- The field **render** on a ship is correct-by-construction but never eyeballed: marching-cubes at
  fractional ship coordinates, per-frame rebuild cost, and render-pose vs game-transform jitter are all
  unverified. Needs a playtest.
- Solve cost on a large multi-emitter hull is unmeasured (INV-SHD-14).
- **No shield block is craftable, so none is repairable.** A repair is priced out of the block's own
  crafting recipe (`structural-damage` MECH-DMG-16) and no `affs:` block has one, so the welder answers
  `NO_RECIPE` for every block in this subsystem — a shield damaged in a fight stays damaged, and the
  subsystem is unobtainable outside creative.
  The shrink's own "repair restores it" law is pinned one tier down instead (INV-SHD-22).
- Console **GUI** widgets for the group editor do not exist yet; the editing API and its data layer do.
