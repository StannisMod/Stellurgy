---
id: structural-damage
owns: [api/damage/, damage/]
entrypoints: [ShipDamageService#apply, DamageState#getStage, StructureDamageEngine#penetrate]
depends-on: [api-public, integration-vs, util-core, shields]
depended-by: [weapons, shields, projectile-substrate]
contracts: [C1, C3, C20]
confidence: high
---

## Purpose

What a damage budget does to blocks. A weapon, a hazard or a collision declares an **impact** — a point,
a direction, an energy and a kind — and this subsystem decides which blocks it reaches, how far it gets,
what breaks, and hands back a **report** of facts the caller decides its own fate from. It is the third
layer of the impact chain: the weapon owns the shot, the shield owns whether the shot arrives at all,
and this owns what arriving does.

## Responsibility boundary

**Owns**: the budget-and-spend loop, the per-block cost model (toughness), damage stages and where they
are stored for blocks that cannot hold their own, block destruction with provenance, target resolution
(which ship — or no ship — occupies an impact point), the world↔subspace conversion for an impact, and
refusal of a repeated impact identity.

**Does NOT own**: the shot (trajectory, archetype, what happens after the report), interception (the
shield decides whether an impact reaches structure), block *weight* (`WeightEngine` owns the table; this
subsystem added a second column to it), and any statement about which ship was struck — the caller had
the point and can ask. It DOES own repair (C20): the hand rung is built here, and the bay that will
carry T1 lives in `infrastructure-tiles` while its rules stay in this subsystem's contract.

## Key types

| type | role |
|---|---|
| `ImpactRequest` / `DamageReport` | the request/report pair that crosses the weapon↔structure boundary |
| `ImpactKind` | Stellurgy's own kinds (kinetic, explosive, thermal, beam), mapped many-to-two onto the shield's |
| `SelectionMode` | which blocks a budget may spend on; only `PENETRATING` resolves today |
| `ShipDamageService` | the entry point: dedup, target resolution, frame conversion, report assembly |
| `StructureDamageEngine` | the spend loop, driving `util.SweptSegment` over whatever frame it is handed |
| `DamageState` | the unified stage reader across both storage homes |
| `BlockDamageSavedData` | per-world stage map for blocks with no tile of their own |
| `ImpactKindMapping` | the one declared hull-kind → shield-kind mapping |
| `TravellingBody` | the facts a body has when it meets a block — what the contact seam takes, in place of whatever record carries them |

## Mechanics

**MECH-DMG-01 — Damage is a budget spent into stages, not a number applied to a block.** Advancing one
block by one stage costs `(base + toughness × mult) / maxStage`; the budget is spent greedily, block by
block, and the terminal stage is destruction. A bigger budget therefore reaches FURTHER rather than
hurting one block more — which is what makes two weapons of equal energy behave differently.
`StructureDamageEngine.java:192-217` [V] [T `StructuralDamageContractTest`]

**MECH-DMG-01a — A budget is priced against the BODY'S CROSS-SECTION.** Material resists with a
pressure, so the energy a body spends per unit of depth is that pressure times the area it presents:
the same energy behind a wider face buys less depth, behind a narrower one buys more. The stage price
is scaled by `area / REFERENCE_AREA`, and at the reference area — which every shipped weapon and every
older caller uses — it is exactly the price it always was. Sectional density is not an input anywhere:
it is what this scaling MEANS once a body's energy is written as `½mv²`.
`StructureDamageEngine.java:157,287-292`, `ImpactRequest.java:47-52` [V] [T `ShotBoresOverTimeE2ETest`]

**MECH-DMG-01b — An impact may be granted a REACH, and a resumed one does not pay twice.** A caller
that penetrates over time hands over only the path its body travelled this tick; the engine's own
64-block limit stays as the backstop for callers with no notion of reach (an explosion, a collision). A
request that says it RESUMES inside the block it starts in is not charged for that block again — it
bought it on an earlier tick. `StructureDamageEngine.java:77-118,158-166`, `ImpactRequest.java:85-99`
[V] [T `ShotBoresOverTimeE2ETest`]

**MECH-DMG-02 — Damage and wear are one axis.** A block hit by a shot and a part worn by use advance the
same stage counter, so there is one consequence formula and one repair rather than two that disagree.
`DamageState.java:33-46` [V]

**MECH-DMG-03 — Stages live in one of two homes, read through one reader.** A tile that carries the wear
capability keeps its stage; everything else — plain hull, plating, a wall — has its stage in a per-world
map. Consumers never learn which. Giving every damaged block a tile is not available: vanilla stores a
tile only when the block itself declares one. `DamageState.java:33-77`, `BlockDamageSavedData.java` [V]

**MECH-DMG-04 — Destruction records what was there.** The block and metadata are written to the map
before the block becomes air, so a repair can restore what stood there rather than a guess. Stored as a
registry name, not a numeric state id, because ids are an install-local encoding.
`StructureDamageEngine.java:209-213`, `BlockDamageSavedData.java:96-110` [V]

**MECH-DMG-05 — The call takes a WORLD and a point, never a ship id.** The service resolves the target
itself: a ship whose subspace claim actually holds a block at the point, or the ordinary blocks of that
world. One shot path serves turret-vs-ship, ship-vs-base and base-vs-ship; the alternative is every
weapon growing two code paths. `ShipDamageService.java:132-146` [V]

**MECH-DMG-06 — Candidate ships are filtered by an actual block, not by a box.** Ships are found by
their grown world AABBs, which overlap and overstate, so each candidate is asked whether its own
subspace holds a block at the impact point. A near miss past one hull is not charged to it.
`ShipDamageService.java:137-145` [V]

**MECH-DMG-07 — The frame conversion happens once, inside.** The caller works in world coordinates; ship
blocks live at fixed subspace addresses while the ship flies. The point is translated and the direction
rotated into the ship's frame before the walk, and the report's points are mapped back, so neither the
caller nor the engine ever sees two frames. `ShipDamageService.java:100-118` [V]

**MECH-DMG-08 — A repeated impact identity is refused by the service, per WORLD.** Recently applied ids
are remembered for a bounded window, keyed by dimension as well as identity; a repeat spends nothing and
says `DUPLICATE_IMPACT`. The retry paths that make this real (an impact deferred because its region was
unloaded, a shot re-examined across a load transition) would otherwise damage twice with nothing in a
diff to show it. **The dimension is part of the key because identities are minted per world**: without
it, one world's round is refused for an impact another world declared, and a refusal is silent — the
budget comes back whole and the round flies on through what it was aimed at. **The memory is the
running server's** (`ShipDamageService.ImpactMemory`, a field of `ServerState`) and goes with it; it is never a JVM-wide static map, which would let an integrated client reopening a world
inherit the last server's spent identities. `ShipDamageService#recentImpacts`, `#isDuplicate`,
`#remember` [V] [T `StructuralDamageContractTest`]

**A refusal is indistinguishable from a miss from outside, and that is its cost.** Nothing in the
report, the shot or the wall says an identity was refused rather than nothing being there; both leave a
whole budget and an unmarked block. The instrument that closes the gap is `/stellurgytest shot trace` (what
each step decided) beside `/stellurgytest damage impact-memory` (whether an identity is already spent, and
since when) — see `stellurgytest-probe-catalog`.

**MECH-DMG-33 — A block is ASKED for before it is taken, and a refusal is honoured.** Destruction
posts a `BlockEvent.BreakEvent` attributed to a fixed synthetic player (`[weapon-fire]`, constant id so
a protection mod's whitelist can name it), and vanilla spawn protection is asked directly beside it
because it is not implemented as a listener. A refusal leaves the block standing at one stage short of
gone — it keeps the damage, the energy was really spent, and the next arrival asks again — so a claimed
structure ABSORBS fire rather than becoming transparent to it — **and stops it**: maintainer ruling
2026-10-04 ("да, останавливать"). A refused block at the body's CENTRE ends the walk at
its face, outcome `ABSORBED`, reason `REMOVAL_REFUSED`, the rest of the budget going nowhere, on every
channel (the refusal is the guard's, not a price, so the thermal exemption of MECH-DMG-35 does not
apply). A refused block BESIDE the centre eats its share and the body goes on, as an indestructible
one does. A round rich enough to pay for the refused block does not carry its remainder
through it to take what stands behind (`Touched#refused`, `Walk#visit`) [V]
[T `WeaponFireAsksBeforeItTakesE2ETest` — the block behind the claim]. Without the ask the engine would take
blocks with a bare `setBlockState` that no protection system could see: a turret would be a way
around the claim system rather than a weapon inside it.
`StructureDamageEngine#mayRemove` [V] [T `WeaponFireAsksBeforeItTakesE2ETest`]
**Every path that takes a block as weapon fire goes through it**, including the two that are not the
budget walk: reactive plating spending its charge and mirror plating burning its film both destroy a
block, and both did it with a bare `setBlockState` — which made the branch's own new armour the one
content a claim mod could not protect. `StructureDamageEngine#removeIfAllowed` is that entry point;
a refusal leaves the plate standing, spent and still absorbing.

**MECH-DMG-09 — An unloaded target is not a miss.** A walk that meets an unloaded position stops with
`TARGET_UNLOADED` rather than reporting empty space, so a caller able to retry knows to.
`StructureDamageEngine.java:129-133` [V]

**MECH-DMG-10 — Budget left at the far side is handed back.** A walk that leaves structure, or reaches
the path limit, reports `EXITED` with the unspent budget and an exit point; the engine never silently
absorbs what it did not spend. `StructureDamageEngine.java:136-140`, `:174-189` [V]
[T `StructuralDamageContractTest`] The two exceptions are a body with mass stopped by a block it could
not take out (MECH-DMG-35, `ARMOUR_HELD`) and any body stopped by a centre block a guard refused
(MECH-DMG-33, `REMOVAL_REFUSED`): the remainder is absorbed, not handed back.

**MECH-DMG-34 — "Budget left over" and "came out the other side" are two facts, and the stop REASON is where they are told apart.** A walk hands its budget back in both cases and reports `EXITED` for both, so the outcome alone cannot answer "is the body still in there". `EXITED_FAR_SIDE` means it left; **`REACH_EXHAUSTED` means the granted path ran out while it was still inside**, which is the ordinary state of a round boring through armour over several ticks. The distinction matters because the substrate decides a round's next step from it. `StructureDamageEngine.java` (`finish()`), `StopReason` [V]
[T `ShotHitsShipHullE2ETest#aRoundDrillingAHullGoesWhereTheShipGoes`, `ShotBoresOverTimeE2ETest` — the lodging family is what fails when the two are confused]

**MECH-DMG-11 — An indestructible block stops everything.** A block with negative hardness consumes the
whole remaining budget and ends the walk rather than being tunnelled past.
`StructureDamageEngine.java:151-156` [V]

**MECH-DMG-12 — Toughness is a second column of the weight table.** Same keys, same resolution chain
(individual → regex → material → fallback), same file. Weight answers what a block masses; toughness
answers what it costs to break. `WeightEngine.java:170-200` [V]

**MECH-DMG-13 — Damage is carried by whatever moves the block, through three channels.** The map is
keyed by position, so every mechanism that relocates a structure owes it a carry, and there are three
distinct ones. A `StorageChunk` capture harvests the records of the box it is about to empty and
replays them at the paste site (`StorageChunk.java:462-470,820-843` [V]); a `cutWorldBB` clears that
region afterwards (`:504-511` [V]). VS assembly carries each block's record with the block, in the
same loop that carries its tile (`WorldServerShipManager.java:215-227` [V]), and VS deconstruction
does the same (`MoveBlocks.java:51-55` [V]). Both VS calls go through one Stellurgy-owned seam,
`DamageState.blockMoved` (`DamageState.java:66-80` [V]).

**MECH-DMG-13a — Selection and origin are DIFFERENT boxes, and conflating them destroys damage.** A
capture measures its offsets from its tight BLOCK bounds, because that is where the blocks are laid
down again — but those bounds are drawn around blocks that still exist. Shoot a hull's outermost
column away and its records sit outside them, while the cut still clears the wider region: selecting
by the origin box drops exactly the records the carry exists for, and the cut then deletes them.
Selection therefore covers the caller's whole box and offsets may be negative
(`DamageLayer.java:44-72` [V]) [T `DamageLayerTest.aRecordOutsideTheBLOCKBoundsIsStillCarried`].

**MECH-DMG-14 — A relocation's HOLES are carried as a region, not as blocks.** A destroyed position
holds no block, so a channel that enumerates blocks cannot see it — and what it holds is the note of
what stood there, which is what a repair needs. After the per-block carry, assembly sweeps the vacated
region and moves the records of positions that are now air (`DamageState.java:82-105`,
`WorldServerShipManager.java:238-248` [V]). A position still holding a block is left alone: it belongs
to a neighbour standing inside the same box, not to the structure that left. The swept region is the
surviving blocks' bounds widened by `HOLE_SWEEP_MARGIN` (8), for the same reason MECH-DMG-13a exists —
and that margin is a BOUND, not a proof: a hole further out than 8 blocks from any surviving block is
left behind.

**MECH-DMG-15 — A player's pickaxe clears the record; the engine's does not.** `BlockEvent.BreakEvent`
and `PlaceEvent` forget the position's record (`DamageInvalidationHandler.java:28-42` [V]). Neither
fires for the damage engine's own destruction or for a relocation cut, both of which write through
`setBlockState` — so this reaches exactly the case it is for. It is also the price list for hand
repair: replacing a damaged block is a repair paid for with the block. [A: the machine repair ladder
is not built, so hand replacement is currently the only repair.]

**MECH-DMG-16 — One stage per use, priced from the block's own recipe (repair T0).** The repair
welder removes one stage per right-click, charging the ingredients of the block's crafting recipe
scaled by `repairCostPerStageFraction` over its stages, plus stored Forge Energy
(`RepairCost.java:57-88`, `ItemRepairWelder.java:74-113` [V]) [T `RepairWelderE2ETest`]. Its answer is
a type — `REPAIRED / UNDAMAGED / NO_RECIPE / NO_MATERIALS / NO_CHARGE` — and nothing is taken on any
refusal. A block nothing crafts cannot be priced and is refused; the pickaxe remains its only repair.
Full rules in contract C20. Neither damage nor repair asks a war switch (`weapons` MECH-GUN-35); the welder's own verdicts are pinned by `RepairWelderE2ETest`.

**MECH-DMG-18 — A block may ANSWER a travelling body, and that answer is not this subsystem's.** Since
the contact seam a block met by a shot is asked what happens (`projectile-substrate` MECH-SHOT-15) and
may say stopped / passed through / deflected. This subsystem is downstream of that answer: it still only
receives a point, a direction, a budget and a kind, and it neither knows nor cares that a block had an
opinion. The entry is worth an anchor because "the block has no voice" is true of the engine alone and not of the game.
`ContactResolver.java:42-60` [V]

**MECH-DMG-19 — A body with WIDTH is walked in layers, and one layer's budget is DIVIDED by
overlap share.** The walk drives `SweptVolume`, so a body wider than a voxel is offered the blocks
beside its axis as well as the one on it, grouped into the slice they were reached in. Each block of a
slice is charged against the pool the body had ON REACHING that slice — not against what the previous
block left — so the order they are listed in cannot change the outcome, and it receives the fraction
of that pool equal to how much of the cross-section it covers. The shares of a slice sum to one:
widening a body spreads what it has rather than multiplying it. `StructureDamageEngine.java:190-272`
[V] [T]

**MECH-DMG-20 — A block pays for the area IT is under, never for the whole body.** The pressure law
prices a stage against the cross-section behind it (MECH-DMG-01b), and for a layer that cross-section
is the block's own covered share, not the body's total. Charging every block of a wide slice for the
entire face while handing it a fraction of the budget would take the width out of the round twice, and
a wide shot would be feebler than any pressure argument makes it. What survives is the ordering the
law is for: with the shares cancelling, a body's depth goes as E over (A x perStage) — the same energy
behind a wider face bores less far, and the width shows up as a wider hole instead.
`StructureDamageEngine.java:260-264` [V]

**MECH-DMG-21 — The AXIS block tells the walk's story; the width only decides who else is paid.**
Whether the body is in material, whether it came out the far side, how deep it got and where it
entered are all questions about the CENTRE of the body, and they are answered from the axis block
exactly as they were when a body was a line. A slice counts as material if anything in it is solid, so
a graze registers; but an unloaded axis block still stops the walk, and an indestructible one still
kills the budget, while an indestructible block merely BESIDE the hole eats its own share and lets the
body past. `StructureDamageEngine.java:190-272` [V]

**MECH-DMG-22 — The contact seam takes a BODY's facts, not the record carrying them.** A shell out of
a gun, a bolt and a beam somebody is HOLDING on a hull are three things to own and one thing to answer:
the third has no lifetime, no position that survives a tick and nothing to step, so a seam keyed on the
shot registry would serve one weapon family and force every later one to choose between faking a shot
and duplicating the armour behind it. `TravellingBody` carries velocity, kind, remaining energy, radius
and the impact identity; the identity is GIVEN rather than minted at the seam, because only the owner
of the body's continuity across ticks knows how long it has existed. A side effect worth naming: the
declared direction now has ONE source instead of two that were kept equal by an assignment nobody was
watching. `TravellingBody.java`, `ContactResolver.java:36-70` [V]

**MECH-DMG-23 — Two COLUMNS of one law: pushed through, or boiled away.** A stage is priced against
the block's resistance to the KIND that arrived — mechanical for a slug or a blast, ablation for a beam
or sustained heat. Same law, same units (energy per unit of volume removed), different constant, which
is what makes a ceramic that shrugs off a beam and shatters under a slug two ROWS of the weight table
rather than two mechanics. A block with no ablation row has one DERIVED from its toughness by a single
factor, so the mechanical price of every block in the game is exactly what it always was, and the two
columns are nowhere near equal: per joule a kinetic round removes far more hull than a beam does, which
is why a laser buys precision rather than digging power. `WeightEngine.java:216-256`,
`StructureDamageEngine.java:302-323` [V] [T]

**MECH-DMG-24 — A beam below the intensity threshold removes nothing, and its energy stays in the
plate.** Two things refuse a faint beam and they are not the same thing. The PRICE refuses it first: a
stage is bought whole or not at all, and a body that cannot afford one keeps its energy rather than
banking it, so "can this beam afford a stage" is already an intensity question, answered at
`perStage × ablation / referenceArea` — a line each block sets for itself, of order 8 000 for stone and
38 500 for an iron block on the shipped table. What the price does NOT do is stop the beam: refused on
price, it passes clean THROUGH the plate carrying everything it arrived with, which is a free x-ray of
a hull. The threshold is what makes that energy absorbed instead, and the default (`50 000`) sits above
the affordability line of metal — so a small emitter does nothing whatever to a metal hull however long
it is held, which is the qualitative gap between a big emitter and a small one.
`StructureDamageEngine.java:325-345`, `StellurgyConfiguration#beamAblationIntensityThreshold` [V] [T]

**MECH-DMG-35 — A body with mass gets past a block only by taking it out: armour it cannot pay for
HOLDS.** Maintainer ruling 2026-10-03, verbatim: *"По кинетике без порога - такой снаряд не должен
делать повреждения. Будем считать, что "броня держит""* (a kinetic round below the threshold must do no
damage; consider that the armour holds). The mechanical channel's counterpart of MECH-DMG-24, by a
different road: there is no intensity number, the PRICE is the threshold. After a layer of the walk has
offered every block its share, the block at the body's CENTRE — if this layer charged it — is asked
whether it was left standing because what remained of its share could not pay for its next stage; if
so, the walk ends there — outcome `ABSORBED`, reason `ARMOUR_HELD`, what was left of the budget goes
nowhere (neither handed back nor carried on), and the distance walked is that block's entry face.
**The boundary, stated**: a block the round could not buy one stage of is left undamaged and stops it at
its face; a block it bought SOME stages of keeps them (they were paid for) and stops it at its face too,
because the stages it could not buy are the ones that would have let it through. A block a guard refused
to remove (MECH-DMG-33's `mayRemove`) is a DIFFERENT question with the same answer: see MECH-DMG-33 —
a centre block paid for in full and refused stops the body too (`REMOVAL_REFUSED`), while a guarded
block the round could not pay for holds on price like any other (`ARMOUR_HELD`). Not asked
either: a centre block the sweep reached EARLIER as a side block of a wide body, which the layer does
not list again (`SweptVolume.Layer#blocks`) — UNMEASURED whether a wide round can pass such a block
for free. A round PART-WAY through a block —
resuming a bore at distance zero — is in a block whose entry was paid on an earlier tick: that slice is
skipped as before, and the question is asked of the next block it reaches. The blocks BESIDE the centre
of a wide body still take whatever their shares buy; only the centre decides the body's fate, as it does
for an indestructible block. The thermal channel is not asked — a beam's fate is MECH-DMG-24's. In the
substrate the round ends `STRUCTURE_IMPACT` at that face (replicated by the ordinary end packet).
The round does not cross the block at
the speed it arrived with. `StructureDamageEngine.java:288-363`, `StopReason#ARMOUR_HELD` [V]
[T `StructuralDamageContractTest#aKineticImpactThatCannotBuyTheNextStageIsHeldByTheBlock`,
`ShotBoresOverTimeE2ETest#aRoundTooPoorForAStageIsStoppedByTheBlockItMeets`]

**MECH-DMG-25 — The responder is handed the WORLD, because a block that answers spends itself.** A
`Contact` states the facts of a meeting and carries no handle into the game — that is what lets a held
beam, which is no shot in any registry, use the same seam. But armour answers by DESTROYING itself: a
mirror whose film melts, a charge that has gone off. So the world travels as an argument to
`onContact` rather than on the contact, where every future caller would have had to produce one, and
rather than in a static, where the answer would depend on who asked last.
`IContactResponder.java:19-32`, `ContactResolver.java:60` [V]

**MECH-DMG-26 — Mirror plating reflects a FRACTION and dies by the rest.** Reflected `R x E` goes back
out along the mirrored direction; absorbed `(1 - R) x E` stays in the film; and when the absorbed part
exceeds what the film can shed, the plate is GONE in one hit and reflects nothing again — the metal
behind the glass melted, and an optic either is one or is not. Two consequences need no further rule: a
better mirror survives more hits because it absorbs less of each, and the tier ladder is nothing but
the reflectances of the metals mirrors are really made of. A solid body is DECLINED (MECH-DMG-29):
glass and foil have no OPTICAL opinion about it, so the ordinary law prices the film off the table and
the eighth of a voxel it fills and breaks it like the pane it is. It never answers `PASSED_THROUGH` with the
arriving energy, which would be not a declining but a free pass.
`BlockMirrorPlating.java:66-108` [V] [T `ArmourBlocksAnswerForThemselvesE2ETest`]

> **Where the reflection GOES.** "Back out along the mirrored direction" is a claim about the world, true for a thrown round and for a held beam alike: `HeldBeam` asks the contact whether it was DEFLECTED as well as STOPPED. A beam continues along the mirrored direction as a new leg of the same tick's path, bounded by `MAX_BEAM_SEGMENTS` (8) — a bound on WORK, for the tick where two mirrors face each other, not a law about beams. A plate met square-on therefore sends the beam back down its own line and into the gun that fired it, which is a real consequence and the thing the test pins.


> **Two layers stop more than one only because the second is ASKED.** The claim needs the round to meet each plate in turn, so a body that gets through one plate is not advanced by the whole of the tick's remaining travel — see `projectile-substrate` MECH-SHOT-17a.

**MECH-DMG-27 — Reactive plating eats a portion by VOLUME and spends itself locally.** A charge
swallows up to its capacity of one impact and then removes ITSELF — no neighbour, no explosion in the
world, nothing else touched, because the blast is outward into what struck it. Capacity scales with how
much plating there is, so a full block takes what a plate cannot and layering works without a rule
about layers. The ordering it exists to produce: ordinary fire is swallowed whole, and a round carrying
far more than one charge can take punches through as if it were not there — against that the answer is
a shield, and reactive plating is honest about not being one. `BlockReactivePlating.java:60-88` [V] [T]

**MECH-DMG-28 — A block is priced by how much of its VOXEL it fills, read from its collision list,
with no floor under it.** The law is an energy per unit of volume removed, so the stage price is scaled
by the fraction of the voxel the block actually occupies. The fraction comes from the collision LIST,
summed box by box and clamped at one — not from the bounding box, which over-states in two different
ways in vanilla alone (`BlockStairs` does not override it and reports a full cube; `BlockFence` reports
the envelope of post and arms). Without it, a glass pane would cost a solid block of glass to shoot through
and a carpet a block of wool; every hull the tests fire at is built of full cubes, which is the
one shape that hides it.

**No lower bound, and nothing is free regardless.** There is no floor on the multiplier (ruled 2026-08-19): a floor of `0.1` would be a MULTIPLIER, so "the least a block may cost" was priced out of
that block's own material — while what it was meant to stand for, the work of breaking a thing off its
mounting, has nothing to do with what the thing is made of. What actually holds the bottom is
`STAGE_COST_BASE`, material-independent and already inside the product: a standing torch answers
`0.024` and still costs 6 against the 1000 a full block of stone costs, and below that the price rounds
up to at least 1. The floor's stated reason — that a torch must not become a hole in a hull — does not
survive inspection: a voxel holding a torch is a voxel holding no hull block.
`StructureDamageEngine.java:413-461` [V] [T `StructuralDamageContractTest`,
`ArmourAnswersByKindAndAngleE2ETest`]

**MECH-DMG-29 — A responder may DECLINE, and declining is not an answer.** `ContactResult.noOpinion()`
means "the default law decides", exactly as it does for the two thousand blocks implementing nothing;
the resolver falls through on it as it does on null. It exists because there was no way to say it: the
interface instructed implementers to decline with `passedThrough(energy)`, which is a real answer
meaning "through, carrying this much" — so declining with what arrived said "through, for free". A
block with a law about one kind of arrival and none about the rest could not express the rest.
**Armour is exactly that shape**: a mirror has optics and no opinion about a solid round; the round must
still get through the glass. `ContactResult.java:55-62`, `ContactResolver.java:77-87`,
`IContactResponder.java:22-33` [V] [T `ArmourBlocksAnswerForThemselvesE2ETest`]

**MECH-DMG-30 — Mirror plating carries the one shipped toughness row; reactive plating deliberately
carries none.** Both families are declared `Material.IRON` — what they are mined and sounded like — and
the table resolves by material when no row exists, which priced a mirror film as hull plate. A regex
row prices the family as glass and foil, written as a regex so that the tiers are priced alike (what
separates them is the film's reflectance, not the hardness of the glass) and a fourth tier needs no
fifth row. Reactive plating has no row on purpose: its casing IS metal, and what makes it interesting
is the charge rather than what the charge is wrapped in. **Neither has an ablation row**, because both
intercept a radiant arrival before any price is consulted — the row would only ever govern a thermal
arrival that is not a weapon. The pattern is LOWERCASE: a registry name reaches the table already
lowercased, and one written in the case the block was declared in matches nothing and silently is not
there. `WeightEngine#defaultToughnessByRegex` [V]
[T `ArmourBlocksAnswerForThemselvesE2ETest`]

**MECH-DMG-31 — A unit is TOLD what broke it, and a unit that was killed is told that too.** The
stage answers *how broken am I* and is PULLED; it cannot answer *what just happened to me*, because a
shell and a collapsing hyperspace window leave the same stage behind. So a `DamageOccurrence` — cause,
kind (absent when the cause is not an arrival along a line), severity, stages before and after, place,
hull — is PUSHED once, to the unit it happened to and to nobody else. It is news, not state: there is
no storage on the capability, and a unit that turns it into durable state persists THAT itself.

**The unit decides everything that follows.** The occurrence carries no derate, no probability and no
verdict: what being damaged does to a machine is the machine's own to compute, and a damaged engine
throttles itself back because it decides to stay safe, not because a table above it lowered a number.

**Split along the layer boundary, and the split is forced.** The engine RECORDS (`WalkResult.touched`:
position, stages, budget spent) and names no ship and no cause — it walks in whatever frame it was
given and is handed a budget and a kind rather than the request. `ShipDamageService` PUBLISHES, adding
the cause and the hull, which live at its layer. **A dying unit's listener is captured by the engine
one line before the block becomes air**: publishing after the walk loses every fatal occurrence, and
the blow that ends a unit is the one its own failure mode is made of (MECH-DMG-32).
`StructureDamageEngine.java:346-400`, `ShipDamageService.java:118-160`,
`DamageOccurrence.java`, `CapabilityDamageAware.java` [V]
[T `AUnitHearsWhatBrokeItE2ETest`]

**MECH-DMG-32 — Two seams reach a block on a hit, and they are opposites.**
`IContactResponder.onContact` is ASKED before anything is spent and its answer decides the BODY's fate;
`IDamageAware.onDamage` is TOLD after a stage advanced and wants no answer. The first is about the
projectile and is asked of the BLOCK as much as the tile (two thousand vanilla blocks have no tile and
still have to be met); the second is about the unit and rides a capability, because only something with
state to change can react and anything with state has a tile. A block may implement both — armour that
answers a round and a machine that reacts to being holed are two sentences about one block.
`IDamageAware.java`, `IContactResponder.java` [V]

**Hull-wide occurrences are NOT built.** A cause with no position, reaching every loaded unit of one
hull — the emergency hyperspace exit is the exemplar — has no code path. The value already carries the shape
(`getWhere()` answers null, `HULL_WIDE` needs no point); what is missing is who enumerates a hull's
loaded units and on what thread. Named here so the absence reads as unbuilt rather than unnoticed.

**MECH-DMG-17 — The path is TRAVERSED, not sampled: every block the ray crosses is offered the
budget.** One unit of RAY is not one block of GRID unless the ray is parallel to an axis, so a walk
that steps a unit and reads the block under each sample skips voxels at any oblique angle — measured
at 30 degrees: three holes in a ten-block bore. Each skipped block keeps its budget and stands
pristine inside the crater, AND counts toward `GAP_TOLERANCE`, so six of them convince the walk it has
come out the far side of a hull it is still inside. The walk now drives `SweptSegment`, the same exact
traversal the projectile substrate uses, so the blocks a shot stops at and the blocks the budget is
spent into cannot be two different sets. Axis-aligned fire is unchanged by construction — there the
two agree exactly. `StructureDamageEngine.java:77-90`, `:120-171` [V]
[T `DiagonalBoreE2ETest`]

## State & persistence

`BlockDamageSavedData` is a per-world `WorldSavedData` (`stellurgyBlockDamage`) holding
`packed BlockPos → {stage, originalBlock, originalMeta}`. Keyed by packed long because the access
pattern that matters is a turret burst against blocks that are mostly pristine, so a miss must allocate
nothing. Tile-hosted stages persist in their own tile NBT, as they always have.

**No block owns the map.** The alternative home — the flight computer's NBT, which would ride a
relocation for free — was rejected: it makes one breakable block the custodian of every other block's
state, so mining it and putting it back returns a wrecked hull to the showroom. The travelling copy is
`DamageLayer`, a layer of the structure's own capture (`DamageLayer.java:37-127` [V]), and it carries
destroyed positions as well as damaged ones.

## Invariants

- **INV-DMG-01** [T][BEH] An impact that meets structure spends into it and reports a depth and an entry
  point. `StructuralDamageContractTest` Pinned by `StructuralDamageContractTest#anImpactIntoAWallSpendsIntoItAndReportsWhereItReached`.
- **INV-DMG-02** [T][BEH] An impact whose budget outlasts what it struck exits carrying the remainder, with
  an exit point. `StructuralDamageContractTest` Pinned by `StructuralDamageContractTest#anImpactThatOutlastsTheWallExitsCarryingTheRest`.
- **INV-DMG-03** [T][BEH] The same impact identity applied twice damages once; a different identity at the
  same place still lands. `StructuralDamageContractTest` Pinned by `StructuralDamageContractTest#theSameImpactIdentityAppliedTwiceDamagesOnce`.
- **INV-DMG-04** [T][BEH] At equal budget, a tougher wall is not penetrated as far as a flimsy one — the
  ordering the toughness table exists for, pinned as ordering rather than as any number.
  `StructuralDamageContractTest` Pinned by `StructuralDamageContractTest#aTougherWallIsNotPenetratedFurtherThanAFlimsyOneAtEqualBudget`.
- **INV-DMG-05** [T][BEH] Every `ImpactKind` declares how a shell bills it; a kind with no billing would pass
  a raised shield free. `ImpactDeclarationContractTest` Pinned by `ImpactDeclarationContractTest#everyHullImpactKindDeclaresHowAShellBillsIt`.


- **INV-DMG-09** [T][BEH] The damaged blocks of one impact form an UNBROKEN chain: no block the ray
  passed through is left untouched between two that were damaged. Stated as a property of the
  result rather than of the traversal, so it holds at every angle and pins no particular walk.
  `DiagonalBoreE2ETest#anObliqueImpactLeavesNoUntouchedBlockInsideItsOwnBore`
- **INV-DMG-06** [V] Damage is applied on the logical server only.
- **INV-DMG-10** [T][SYS] A hole's provenance crosses a carry unchanged: the layer that harvests a
  structure's records and applies them at the new origin writes back the very NAME and meta it read,
  a name the block registry no longer holds included — a removed mod's block stays that block's name,
  never `minecraft:air` and never dropped. The carry never resolves the name through the registry
  (`DamageLayer#applyTo` → `BlockDamageSavedData#recordDestroyedName`) `[V]`. Whoever does resolve one
  asks `containsKey` first: Forge's block registry is DEFAULTED and its lookup answers air, not null, for
  a name it does not hold. FOR: INV-DMG-07 (a relocated
  ship carries the damage it left with) and C20 REPAIR-6 (an unfillable hole keeps its record). Pinned by
  `test/unit/DamageLayerTest#aHoleOfABlockNoLongerRegisteredKeepsItsNameAcrossACarry`.
- **INV-DMG-07** [T][BEH] A relocated ship carries the damage it left with, and leaves none at the
  coordinates it vacated. `ShipDamageSurvivesRelocationE2ETest`. *Bounded*:
  assembly carries holes only within `HOLE_SWEEP_MARGIN` (8) of the surviving blocks
  (`WorldServerShipManager.java:35-42`), and deconstruction carries block records but not holes
  (`MoveBlocks.java:51-55` calls `blockMoved` only) — see "A deconstructed ship's holes lose their
  provenance" below. The invariant holds for staged blocks; for holes it is the assembly direction only. Pinned by `ShipDamageSurvivesRelocationE2ETest#aRelocatedShipCarriesItsDamageAndLeavesNoneBehind`.
- **INV-DMG-08** [T][BEH] Removing and replacing the flight computer changes no other position's record —
  no single block is the custodian of a hull's condition.
  `ShipDamageSurvivesRelocationE2ETest` Pinned by `ShipDamageSurvivesRelocationE2ETest#breakingAndReplacingTheFlightComputerDoesNotRepairTheHull`.

## Failure modes & edge cases

- **A deconstructed ship's holes lose their provenance.** Deconstruction carries records block by block
  (`MoveBlocks`), and a destroyed position has no block to ride with; unlike assembly, there is no
  region sweep behind it, because the caller iterates the ship's block set rather than a box. The
  surviving blocks keep their stages; what is lost is the note of what filled the holes.
- **Selection modes other than `PENETRATING` throw** rather than returning an undamaged hull, because a
  silent "nothing happened" reads as a clean miss. The by-ship overload throws for the same reason.
- **The path limit** (64 blocks) is a limit on how much world one impact walks, not a physical fact; a
  shot with budget left there is reported as exited rather than absorbed.

## Integration seams

- **Repair**: contract [C20](../30-contracts/C20-repair.md) owns what a repair costs and leaves
  behind; T0 (the welder) is built, T1 (the bay) is not.

- **Shields**: `ImpactKindMapping` is the one declared bridge, crossed by the shot substrate at the
  moment it declares a strike (`projectile-substrate` MECH-SHOT-20); a shield's residual impact energy is
  denominated in the same unit as a damage budget, so an overwhelmed shell hands its remainder straight
  through with no conversion between them.
- **Valkyrien Skies**: target resolution and both frame conversions go through `VSIntegration` only.
  The relocation carry runs the other way — the two vendored VS block-movers call
  `DamageState.blockMoved` / `holesMoved`. That is the only inbound coupling, and it is what a
  replacement substrate would have to call.
- **`util-core`**: the toughness column of `WeightEngine`; `StorageChunk` carries a `DamageLayer`.
- **Probes**: `stellurgytest damage impact | stage | records | weld | clear-impacts`.

## Test coverage

`StructuralDamageContractTest` (server) · `VSShipStructuralDamageE2ETest` (server, a MOVED ship) ·
`ShipDamageSurvivesRelocationE2ETest` (server, relocation + the flight-computer pin) ·
`ImpactDeclarationContractTest` (unit) · `DamageLayerTest` (unit, the carry's own arithmetic) ·
`RepairWelderE2ETest` (server, repair T0).

## Open questions

- Stage cost `base`/`mult` live as constants in the engine rather than in the config file.
- Degradation under load, the repair ladder, and crack-mask surfacing are designed and not built.
