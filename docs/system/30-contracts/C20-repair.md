---
id: C20
covers: what repairing a structure means — the two outcomes, who pays, and how several bays share one hull
confidence: T0 built (REPAIR-1/2/3/7/9 witnessed); T1 and the bay clauses (10-14) are the contract to build against
owner-subsystem: structural-damage (with infrastructure-tiles for the bay itself)
see-also: [structural-damage (MECH-DMG-13a, 14, 15 — how damage travels and how a player's pickaxe clears it), C10 (ship stats — a bay is construction the stat surface will see)]
---

# C20 — Repair, clauses REPAIR-1..REPAIR-15

What it means to put a damaged structure back, stated before any machine is built. The repair
ladder is T0 hand tool, T1 bay, T2 fabricator, T3 field; this fixes what every rung of it must obey, so
that four mechanics cannot end up with four different ideas of what a repair costs and what it leaves
behind.

**What anchors it.** One rung is real — replacing a block by hand is a working repair (MECH-DMG-15) —
and one machine does T1's job for tier-1 rockets (`TileRocketServiceStation`). So the ladder is
designed against a floor price and a working ancestor, and both constrain it.

## Terms

- **stage** — the damage axis, 0 = pristine, `getMaxStage` = destroyed. One axis for wear and for
  battle damage, read through `DamageState` (`DamageState.java` `[V]`).
- **hole** — a position whose block is gone and whose record survives it, carrying the registry name
  and meta of what stood there (`BlockDamageSavedData.java` `[V]`).
- **provenance** — that record. It is the only statement of what a hole should be filled with.
- **bay** — any machine that repairs. `TileRocketServiceStation` is the one that exists; the ladder's
  T1 is its generalisation, not a second machine (REPAIR-11).
- **reserve** — the inventory a bay draws repair material from.

## Clauses

- **REPAIR-1 (repair is damage inverted, on one axis)** `[T][BEH]` A repair lowers a stage or fills a hole
  and does nothing else. It writes through the same unified reader damage writes through
  (`DamageState.java` `[V]`), never into one of the two homes directly — a repair that reaches past
  it fixes tile-hosted wear and leaves map-hosted damage standing, or the reverse, and nothing in the
  game will say so. **Player form:** what a shot did, a repair undoes. Pinned by `RepairWelderTest#oneUseTakesOneStageAndIsPaidForTwice`.
- **REPAIR-2 (the floor is hand replacement, and it is already the price list)** `[V][BEH]` Breaking a
  damaged block and placing a fresh one repairs that position at the cost of one block, clearing its
  record (`DamageInvalidationHandler.java` `[V]`). Every rung is priced against this: a rung
  that costs MORE than hand replacement for the same result is dead content, and one that costs less
  must say what the player gave up instead — a machine, its energy, or the time it took.
- **REPAIR-3 (nothing is created from nothing)** `[T][BEH]` Every stage restored and every hole filled is paid
  for with material that leaves an inventory. No rung invents a generic "matter"; T2/T3 consume the
  real items the ship's economy already makes. **Falsifiable:** run a bay with a reserve that
  cannot fall — its output must be zero. Pinned by `RepairWelderTest#oneUseTakesOneStageAndIsPaidForTwice`, `RepairWelderTest#everyRefusalIsItsOwnAnswerAndCostsNothing`.
- **REPAIR-4 (a hole is filled with what stood there, or not at all)** `[PLANNED][BEH]` A rebuild reads the provenance
  and places THAT block. It never guesses, never substitutes a similar one, and never fills a hole
  whose provenance is missing. **Player form:** a repaired hull is the hull you built, not a patch of
  whatever the machine had.
- **REPAIR-5 (provenance is spent exactly once)** `[PLANNED][BEH]` Filling a hole clears its record in the same step
  that places the block (`BlockDamageSavedData.java` `[V]` is the clear). A record that survives
  its own rebuild is a second free block on the next pass. **Falsifiable:** rebuild the same position
  twice; the second attempt must find nothing to do and consume nothing.
- **REPAIR-6 (an unfillable hole is reported, not forgotten)** `[PLANNED][BEH]` When provenance names a block the
  registry no longer has, the position is refused and its record is KEPT. A lost name is detected by
  asking the registry whether it holds the name, never by a null: Forge's block registry is DEFAULTED and
  answers air for an absent name. The record already survives a carry under its own name (INV-DMG-10). Dropping it destroys the only evidence of what the hull
  was, and does it silently, at exactly the moment a player has lost a mod.
- **REPAIR-7 (a bay that cannot work says why)** `[T][BEH]` No silent idling. Out of energy, out of reserve,
  nothing damaged, and refused-by-REPAIR-6 are four different states and are distinguishable from
  outside the machine. A bay that looks identical while starved and while finished trains players to
  ignore it. Pinned by `RepairWelderTest#everyRefusalIsItsOwnAnswerAndCostsNothing`.
- **REPAIR-8 (repair is time and energy, and it is resumable)** `[PLANNED][BEH]` Progress is a function of elapsed time
  and energy actually delivered — never of how many ticks the bay was loaded for. A bay that spent an
  hour unloaded resumes where it stopped; it does not restart, and it does not bill the player for an
  hour of work it did not do. This is what makes the ladder lazy-catch-up compatible (INV-SPACE-01).
  **Falsifiable:** two bays with identical reserves, one unloaded for half the run, must differ by the
  energy each actually consumed and by nothing else.
- **REPAIR-9 (a staged block is cheaper to destroy, so repair is mechanical)** `[V][BEH]` Damage already
  makes a part-way-gone block cheaper to finish (`StructureDamageEngine.java` `[V]`), so leaving
  damage standing is a real disadvantage and repair is not cosmetic. The tile half of the same rule
  (a staged machine works at reduced capacity) is NOT built; until it is, repair's only
  mechanical bite is this one. Stated so the gap is not mistaken for a decision.

### The bay as a multiblock (maintainer ruling 2026-08-15)

- **REPAIR-10 (size buys speed, with diminishing returns and a ceiling)** `[PLANNED][BEH]` A bay is a multiblock, and
  building it larger makes it strictly faster: for any size N, size N+1 is never slower. The gain per
  added size SHRINKS as N grows, and the rate is bounded above — no size makes repair instantaneous.
  The shape is the contract; the constants are `tunable` and live with the balance pass, per
  balance rule (a clamp on a rate constant is a symptom). **Falsifiable, three separate assertions:** monotonic (N+1 faster than
  N), diminishing (the gain from N→N+1 is smaller than from N−1→N), bounded (rate at the largest
  buildable N is below a stated ceiling).
- **REPAIR-11 (one bay mechanic, two kinds of target)** `[PLANNED][BEH]` The tier-1 rocket station and the tier-2 ship
  bay are ONE machine with one price list, not two implementations that will drift. What differs is
  how it reaches its work: a rocket's parts through its `StorageChunk`
  (`TileRocketServiceStation.java` `[V]`), a ship's blocks through the damage map. Today's
  station repairs straight to stage 0 and consumes a recipe's materials scaled by a config multiplier
  (`TileRocketServiceStation`) `[V]`; the generalisation must state whether that full-restore is the tier-1 rung or a
  legacy behaviour to be brought onto the ladder — see Open.

### Several bays aboard one hull

- **REPAIR-12 (bays are not rationed)** `[PLANNED][BEH]` A ship may carry as many bays as it can fit. Nothing caps the
  count, and carrying two is a legitimate build decision, not an exploit to be closed.
- **REPAIR-13 (no position is repaired twice)** `[PLANNED][BEH]` Damaged positions of one structure form ONE pool of
  work, and a bay CLAIMS from it. Two bays never both pay for and both apply the same stage. A claim
  is released when its bay stops (unloaded, unpowered, broken), so a dead bay cannot park work
  forever. **Falsifiable:** two bays, one damaged block; total material consumed equals one repair.
  **Player form:** a second repair bay makes the hull mend faster, not the same hull twice.
- **REPAIR-14 (parallel across bays, non-linear within one)** `[PLANNED][BEH]` Throughput adds up across bays — N bays
  drain the pool about N times faster, because they are N machines drawing N lots of energy — while
  each bay's own rate follows REPAIR-10's diminishing law. The two laws are deliberately different:
  size is one machine doing more with shared plumbing, count is more machines each paying full price.

### The T3 rung — the integrity field

- **REPAIR-15 (the field is a mold, run by the shield network)** `[PLANNED][BEH]` The T3 rung is a PROGRAM of the ship's shield
  network (`C28` WALL-14), not a device: an emitter group assigned to it raises a wall in the
  exact shape of the provenance block and casts real feedstock — made by the T2 fabricator — into it; matter
  reflects from the wall (WALL-4), so the mold holds it until it sets (containerless casting). Every clause
  above binds it: it consumes real material (REPAIR-3), fills a hole only with what stood there (REPAIR-4),
  progresses by energy actually delivered (REPAIR-8) and claims from the one pool (REPAIR-13). Its energy is
  the feedstock's real forming energy plus the field capacity diverted, both landing as `C12` heat; it
  **never repairs a position for less energy than a T1 bay would** — what it saves is reach and attention,
  and what the player gives up (REPAIR-2) is SHIELD CAPACITY, because repair and protection draw the same
  field. "MNT-tier" is placement in progression, not a requirement: an MNT is the practical source for
  hull-scale repair under fire, never a gate.
  *Stress-test.* Falsifiable: a position the field mends consumes the same material a bay would and at least
  the same energy; field capacity assigned to the program is missing from the shield's coverage while it
  runs; with no fabricated feedstock the field mends nothing (REPAIR-3's own falsifier); two claimants never
  mend one position (REPAIR-13). Code: absent — no fabricator, no program object. Consistent with
  REPAIR-1 (it writes through the same damage reader), WALL-4/6/14. Edge: a hole whose provenance the
  registry lost is refused and kept (REPAIR-6), by the field as by a bay.

## Scope

- A bay serves the STRUCTURE it belongs to: for a ship, the hull whose block set contains it. The
  planetary-base case (a bay standing in an ordinary world) needs a bound instead of a block set and
  does not have one yet — see Open. The weapon side already requires one path for both target kinds
  (weapons and repair share one block-set bound), so this cannot stay open forever.
- Says nothing about WHERE repair material comes from beyond "an inventory that empties": the T2
  fabricator and T3 field are deferred and add rungs, not exceptions.
- Says nothing about the crack-mask overlay — that is surfacing, not repair.

## Witnesses

| clause | witness |
| --- | --- |
| REPAIR-1 | `RepairWelderTest.oneUseTakesOneStageAndIsPaidForTwice` — one use, exactly one stage, written through `DamageState`; plus `ShipDamageSurvivesRelocationTest.breakingAndReplacingTheFlightComputerDoesNotRepairTheHull` for the record-clearing half from the other side |
| REPAIR-2 | the same flight-computer scenario: replacing a block clears ITS record and no other's |
| REPAIR-3 | `RepairWelderTest` — material and charge both leave on a repair, and NEITHER leaves on any refusal. Asserted as direction only (`after < before`), never as an amount: the fraction is tuned |
| REPAIR-5, REPAIR-9 | `DamageLayerTest` pins provenance round-tripping; `StructuralDamageContractTest` pins the cheaper-to-finish ordering |
| REPAIR-7 | `RepairWelderTest.everyRefusalIsItsOwnAnswerAndCostsNothing` — four refusals, four distinct outcomes, none of them a silent no-op. `NO_RECIPE` is fired at a block nothing crafts (`minecraft:stone`) |
| REPAIR-4, 6, 8, 10–14 | none — those are T1's, and no bay is built. Each is a test to be written WITH its rung |

## T0, as built

`ItemRepairWelder` — a Forge-Energy hand tool. One use removes one stage, charged in the block's own
recipe (`RepairCost.perStage`) and in stored energy; it holds FE and exposes the standard capability
so any charger in the pack fills it, because Stellurgy ships no item charger and the ratified energy
decision is "FE, no interface of our own".

Its answer is a TYPE, not a message — `ItemRepairWelder.Outcome` is
`REPAIRED / UNDAMAGED / NO_RECIPE / NO_MATERIALS / NO_CHARGE`, and the item only turns it into words.
That is REPAIR-7 made structural: a caller that must tell the four apart reads the enum instead of
comparing strings, and the probe (`stellurgytest damage weld`) drives the same silent decision the player's
right-click does.

**What the welder buys over a pickaxe**, given REPAIR-2's floor: the block stays. Breaking and
replacing a machine to mend a crack empties it — inventory, links, its own accrued wear. On plain
hull plate replacement is often cheaper and that is a legitimate outcome, not a balance failure.

## Open

- **Full restore vs one stage per action** — ruled for T0 (maintainer 2026-08-15): one stage per
  use. The shipped station still repairs to 0 in one step (`TileRocketServiceStation.java` `[V]`);
  which rung THAT is stays open until T1 folds it in.
- **The world-side bound.** A bay on a planetary base has no block set to serve. A radius is the
  obvious answer and the obvious way to get it wrong (a bay repairing a neighbour's wall); derive it
  from something real before picking a number.
- **What a stage COSTS in material** — ruled (maintainer 2026-08-15): a fraction of the block's
  own crafting recipe, `repairCostPerStageFraction` spread over the block's stages, rounded up per
  ingredient so no stage is free. Two consequences left open by it: a block nothing CRAFTS cannot be
  priced at all and is refused (`NO_RECIPE`), which rules out welding stone, ore and most gathered
  blocks; and where several recipes make the same block the first registered one is used, which is
  arbitrary but stable.
- **Whether the claim pool is per-structure or per-bay-reach**, once the world-side bound exists.
