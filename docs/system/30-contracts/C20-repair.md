---
id: C20
covers: what repairing a structure means — the two outcomes, who pays, and how several bays share one hull
confidence: T0 built 2026-08-15 (REPAIR-1/2/3/7/9 witnessed); T1 ship bay built 2026-10-06 (REPAIR-4/5/6/7/8/10/12/13/14 and the bay's NO_RECIPE witnessed, tile-kept wear in the pool); REPAIR-11 built 2026-10-06 (the station's own repair runs the bay's `BayEngine`; witnessed)
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
- **bay** — any machine that repairs. Two exist: the ship bay `TileRepairBay` (T1, built 2026-10-06)
  and `TileRocketServiceStation`, whose own (assembler-less) repair runs the bay's mechanic (REPAIR-11);
  its assembler path is the rocket's T2.
- **reserve** — the inventory a bay draws repair material from.

## Clauses

- **REPAIR-1 (repair is damage inverted, on one axis)** `[T][BEH]` A repair lowers a stage or fills a hole
  and does nothing else. It writes through the same unified reader damage writes through
  (`DamageState.java` `[V]`), never into one of the two homes directly — a repair that reaches past
  it fixes tile-hosted wear and leaves map-hosted damage standing, or the reverse, and nothing in the
  game will say so. **Player form:** what a shot did, a repair undoes. *(One stated exception: a part
  inside a rocket's `StorageChunk` is at no position of a world the reader can see — its `WorldDummy`
  reports no chunk loaded — and has one home only, its own `IPartWear`; the station writes that home
  directly, `TileRocketServiceStation#restage` `[V]`. There is no map half to leave behind.)* Pinned by
  `RepairWelderTest#oneUseTakesOneStageAndIsPaidForTwice`, `RepairWelderTest#aWornEngineAboardIsRestagedByTheBayOutOfOneSpare`,
  and from a real client's hand by `WeaponClientGroupTest#aWelderInAPlayersHandTakesOneStageOff`.
- **REPAIR-2 (the floor is hand replacement, and it is already the price list)** `[T][BEH]` Breaking a
  damaged block and placing a fresh one repairs that position at the cost of one block, clearing its
  record (`DamageInvalidationHandler#onBlockBroken`, `#onBlockPlaced` `[V]`). Every rung is priced against this: a rung
  that costs MORE than hand replacement for the same result is dead content, and one that costs less
  must say what the player gave up instead — a machine, its energy, or the time it took. Pinned, each half
  by a real client's hand against a probe's silent change as the control, by
  `WeaponClientGroupTest#aBlockAPlayerPutsIntoAHoleIsNotRecordedAsDestroyed`,
  `…#aDamagedBlockAPlayerBreaksLeavesNoDamageBehind`.
- **REPAIR-3 (nothing is created from nothing)** `[T][BEH]` Every stage restored and every hole filled is paid
  for with material that leaves an inventory. No rung invents a generic "matter"; T2/T3 consume the
  real items the ship's economy already makes. **Falsifiable:** run a bay with a reserve that
  cannot fall — its output must be zero. Pinned by `RepairWelderTest#oneUseTakesOneStageAndIsPaidForTwice`, `RepairWelderTest#everyRefusalIsItsOwnAnswerAndCostsNothing`.
- **REPAIR-4 (a hole is filled with what stood there, or not at all)** `[T][BEH]` A rebuild reads the provenance
  and places THAT block. It never guesses, never substitutes a similar one, and never fills a hole
  whose provenance is missing. **Player form:** a repaired hull is the hull you built, not a patch of
  whatever the machine had. Pinned by `RepairWelderTest#aHoleIsRebuiltWithTheBlockItsRecordNamesAndTheRecordIsSpent`.
- **REPAIR-5 (provenance is spent exactly once)** `[T][BEH]` Filling a hole clears its record in the same step
  that places the block (`BlockDamageSavedData.java` `[V]` is the clear). A record that survives
  its own rebuild is a second free block on the next pass. **Falsifiable:** rebuild the same position
  twice; the second attempt must find nothing to do and consume nothing. Pinned by
  `RepairWelderTest#aHoleIsRebuiltWithTheBlockItsRecordNamesAndTheRecordIsSpent`.
- **REPAIR-6 (an unfillable hole is reported, not forgotten)** `[T][BEH]` When provenance names a block the
  registry no longer has, the position is refused and its record is KEPT. A lost name is detected by
  asking the registry whether it holds the name, never by a null: Forge's block registry is DEFAULTED and
  answers air for an absent name (`BlockDamageSavedData#blockFromName` asks membership first `[V]`). The
  record already survives a carry under its own name (INV-DMG-10). Dropping it destroys the only evidence
  of what the hull was, and does it silently, at exactly the moment a player has lost a mod. Pinned by
  `RepairWelderTest#aHoleWhoseBlockNoLongerExistsIsRefusedAndItsRecordKept`.
- **REPAIR-7 (a bay that cannot work says why)** `[T][BEH]` No silent idling. Out of energy, out of reserve,
  nothing damaged, and refused-by-REPAIR-6 are four different states and are distinguishable from
  outside the machine. A bay that looks identical while starved and while finished trains players to
  ignore it. Pinned by `RepairWelderTest#everyRefusalIsItsOwnAnswerAndCostsNothing`,
  `RepairWelderTest#everyRefusalOfTheBayIsItsOwnAnswerAndCostsNothing`,
  `RepairWelderTest#aDamagedBlockNoFinishedBlockStandsForIsRefusedAsUnpriced`,
  `RepairWelderTest#aBayThatIsNotOnAShipAnswersNoStructureAndLeavesTheWorldAlone`.
- **REPAIR-8 (repair is time and energy, and it is resumable)** `[T][BEH]` Progress is a function of elapsed time
  and energy actually delivered — never of how many ticks the bay was loaded for. A bay that spent an
  hour unloaded resumes where it stopped; it does not restart, and it does not bill the player for an
  hour of work it did not do. This is what makes the ladder lazy-catch-up compatible (INV-SPACE-01).
  **Falsifiable:** two bays with identical reserves, one unloaded for half the run, must differ by the
  energy each actually consumed and by nothing else. *(As pinned: one bay stopped half-way through a hole for
  want of energy, its hull let go, its chunk saved, dropped and read back — the bay that resumes is a new
  object built from what the old one wrote (`BayEngine#writeTo` `[V]`); it comes back with exactly the
  progress it left with, and once the rest of the energy is in, the hole costs exactly one hole's energy.
  That observes the same decision with the second bay replaced by production's own price. NOT observed:
  time passing unloaded — the bay keeps no clock, so there is no decision of ours in it — and the bay's
  chunk going with its hull on its own, which a hull's unload does not yet guarantee.)* Pinned by `RepairWelderTest#aBayUnloadedMidJobResumesItAndIsBilledForOneJob`.
- **REPAIR-9 (a staged block is cheaper to destroy, so repair is mechanical)** `[V][BEH]` Damage already
  makes a part-way-gone block cheaper to finish (`StructureDamageEngine.java` `[V]`), so leaving
  damage standing is a real disadvantage and repair is not cosmetic. The tile half of the same rule
  (a staged machine works at reduced capacity) is NOT built; until it is, repair's only
  mechanical bite is this one. Stated so the gap is not mistaken for a decision.

### The bay as a multiblock (maintainer ruling 2026-08-15)

- **REPAIR-10 (size buys speed, with diminishing returns and a ceiling)** `[T][BEH]` A bay is a multiblock, and
  building it larger makes it strictly faster: for any size N, size N+1 is never slower. The gain per
  added size SHRINKS as N grows, and the rate is bounded above — no size makes repair instantaneous.
  The shape is the contract; the constants are `tunable` and live with the balance pass, per
  balance rule (a clamp on a rate constant is a symptom). **Falsifiable, three separate assertions:** monotonic (N+1 faster than
  N), diminishing (the gain from N→N+1 is smaller than from N−1→N), bounded (rate at the largest
  buildable N is below a stated ceiling). Pinned by `RepairWelderTest#aBiggerBayIsFasterWithShrinkingGainsUnderACeiling`.
- **REPAIR-11 (one bay mechanic, two kinds of target)** `[T][BEH]` The tier-1 rocket station and the
  tier-2 ship bay are ONE machine with one price list, not two implementations that will drift. What
  differs is how it reaches its work: a rocket's parts through its `StorageChunk`, a ship's blocks
  through the damage map. **As built (2026-10-06):** the law — what a step costs (a finished block of
  the part's own kind, at its own item damage; one spare = `maxStage` stages of credit; a hole = the
  block placed), the energy a step takes (`repairBayEnergyPerStage` at `RepairBaySize.powerPerTick(N)`),
  and the banked progress — is ONE class, `BayEngine`, of which the bay and the station each hold an
  instance (`BayEngine#work` `[V]`); the station's size N is itself plus the frames touching it. The
  station's ASSEMBLER path is not this rung: it is the rocket's T2 (a fabricator — materials by the
  stage-matched `<kind>_repair_N` recipe), and the two never run at once. The station's former
  standalone repair-to-zero, priced from that recipe × a config multiplier, is gone (maintainer ruling
  2026-10-05: that full restore was legacy, to be brought onto the ladder). Pinned by
  `RepairWelderTest#aServiceStationRestagesAWornMotorAStageAtATimeOutOfOneSpare`,
  `RepairWelderTest#everyRefusalOfTheServiceStationIsItsOwnAnswerAndCostsNothing`.

### Several bays aboard one hull

- **REPAIR-12 (bays are not rationed)** `[T][BEH]` A ship may carry as many bays as it can fit. Nothing caps the
  count, and carrying two is a legitimate build decision, not an exploit to be closed. *(Pinned as a
  second bay working beside the first, REPAIR-14's witness; an absence of a cap has no larger form to
  pin.)* Pinned by `RepairWelderTest#twoBaysOnOneHullWorkTwoHolesAtOnce`.
- **REPAIR-13 (no position is repaired twice)** `[T][BEH]` Damaged positions of one structure form ONE pool of
  work, and a bay CLAIMS from it. Two bays never both pay for and both apply the same stage. A claim
  is released when its bay stops (unloaded, unpowered, broken), so a dead bay cannot park work
  forever. **Falsifiable:** two bays, one damaged block; total material consumed equals one repair.
  **Player form:** a second repair bay makes the hull mend faster, not the same hull twice. Pinned by
  `RepairWelderTest#twoBaysOnOneHullRepairItsOneDamagedBlockOnce`.
- **REPAIR-14 (parallel across bays, non-linear within one)** `[T][BEH]` Throughput adds up across bays — N bays
  drain the pool about N times faster, because they are N machines drawing N lots of energy — while
  each bay's own rate follows REPAIR-10's diminishing law. The two laws are deliberately different:
  size is one machine doing more with shared plumbing, count is more machines each paying full price.
  *(Pinned as two bays whose two jobs overlap — an order of records, never a rate: each bay claims its
  own position and draws its own energy.)* Pinned by `RepairWelderTest#twoBaysOnOneHullWorkTwoHolesAtOnce`.

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

- A bay serves the STRUCTURE it belongs to: for a ship, the hull whose block set contains it. **The
  target of repair is only ever a ship** (maintainer ruling 2026-10-05): a bay standing in an ordinary
  world answers `NO_STRUCTURE` and touches nothing. A bay on a BASE repairing a docked ship is wanted,
  and waits for a base region the game does not have yet.
- Says nothing about WHERE repair material comes from beyond "an inventory that empties": the T2
  fabricator and T3 field are deferred and add rungs, not exceptions.
- Says nothing about the crack-mask overlay — that is surfacing, not repair.

## Witnesses

| clause | witness |
| --- | --- |
| REPAIR-1 | `RepairWelderTest.oneUseTakesOneStageAndIsPaidForTwice` — one use, exactly one stage, written through `DamageState`; plus `ShipDamageSurvivesRelocationTest.breakingAndReplacingTheFlightComputerDoesNotRepairTheHull` for the record-clearing half from the other side |
| REPAIR-1 (tile home) | `RepairWelderTest.aWornEngineAboardIsRestagedByTheBayOutOfOneSpare` — an engine's tile-kept wear restaged through `DamageState`, six stages out of one spare engine |
| REPAIR-1 (a player's hand) | `WeaponClientGroupTest.aWelderInAPlayersHandTakesOneStageOff` — a real client's right-click with a charged welder reaches the repair and takes exactly one stage off |
| REPAIR-2 | the same flight-computer scenario: replacing a block clears ITS record and no other's; and a real client's hand, each half against a probe's silent change as the control — `WeaponClientGroupTest.aBlockAPlayerPutsIntoAHoleIsNotRecordedAsDestroyed` (a placement into a hole is a new block) and `WeaponClientGroupTest.aDamagedBlockAPlayerBreaksLeavesNoDamageBehind` (a break takes the record with it) |
| REPAIR-3 | `RepairWelderTest` — material and charge both leave on a repair, and NEITHER leaves on any refusal. Asserted as direction only (`after < before`), never as an amount: the fraction is tuned |
| REPAIR-4, REPAIR-5 | `RepairWelderTest.aHoleIsRebuiltWithTheBlockItsRecordNamesAndTheRecordIsSpent` — the provenance block put back, the record gone, one block drawn, and the bay's next look finds nothing and spends nothing |
| REPAIR-5, REPAIR-9 | `DamageLayerTest` pins provenance round-tripping; `StructuralDamageContractTest` pins the cheaper-to-finish ordering |
| REPAIR-6 | `RepairWelderTest.aHoleWhoseBlockNoLongerExistsIsRefusedAndItsRecordKept` — a hole whose record names an unregistered block: `UNFILLABLE`, the hole still air, the record intact, the reserve untouched, by a powered and stocked bay |
| REPAIR-7 | `RepairWelderTest.everyRefusalIsItsOwnAnswerAndCostsNothing` — four refusals, four distinct outcomes, none of them a silent no-op. `NO_RECIPE` is fired at a block nothing crafts (`minecraft:stone`) |
| REPAIR-7 (bay) | `RepairWelderTest.everyRefusalOfTheBayIsItsOwnAnswerAndCostsNothing` — `UNDAMAGED`, `NO_MATERIALS`, `NO_CHARGE` from one bay as its hull changes, none costing anything, then the same bay repairs; `UNFILLABLE` and `NO_STRUCTURE` in their own scenarios (`aBayThatIsNotOnAShipAnswersNoStructureAndLeavesTheWorldAlone` for the latter); `NO_RECIPE` in `aDamagedBlockNoFinishedBlockStandsForIsRefusedAsUnpriced` — a staged skull aboard, by a powered, stocked bay |
| REPAIR-8 | `RepairWelderTest.aBayUnloadedMidJobResumesItAndIsBilledForOneJob` — the bay's chunk saved, dropped and read back mid-hole; the bay's banked energy is the same leaving and returning, and the hole costs exactly one hole's energy |
| REPAIR-10 | `RepairWelderTest.aBiggerBayIsFasterWithShrinkingGainsUnderACeiling` — sizes 1, 2, 3 and the largest credited size, as orderings of production's own rate |
| REPAIR-11 | `RepairWelderTest.aServiceStationRestagesAWornMotorAStageAtATimeOutOfOneSpare` — a worn motor of a linked rocket taken down one stage per step, the first step drawing one spare into credit and that spare paying for every stage, each step taking energy; `RepairWelderTest.everyRefusalOfTheServiceStationIsItsOwnAnswerAndCostsNothing` — `NO_CHARGE`, `NO_MATERIALS` (a WORN spare of the motor's kind does not pay) and `UNDAMAGED`, none spending anything, then the same station paying with the pristine spare and leaving the worn one. The bay's REPAIR-1 (tile home) and REPAIR-8 witnesses were re-taken on `BayEngine`, the same code the station runs. The assembler path (the rocket's T2): `ServiceStationFullRepairCycleTest` |
| REPAIR-12, REPAIR-14 | `RepairWelderTest.twoBaysOnOneHullWorkTwoHolesAtOnce` — two bays, two holes, one each, each bay's `REPAIRING` before the other's rebuild |
| REPAIR-13 | `RepairWelderTest.twoBaysOnOneHullRepairItsOneDamagedBlockOnce` — one bay puts energy into the stage, the other neither spends nor pays |

## T0, as built

`ItemRepairWelder` — a Forge-Energy hand tool. One use removes one stage, charged in the block's own
recipe (`RepairCost.perStage`) and in stored energy; it holds FE and exposes the standard capability
so any charger in the pack fills it, because Stellurgy ships no item charger and the ratified energy
decision is "FE, no interface of our own".

Its answer is a TYPE, not a message — `RepairOutcome`, the ladder's one vocabulary (below); the welder
answers `REPAIRED / UNDAMAGED / NO_RECIPE / NO_MATERIALS / NO_CHARGE`, and the item only turns it into
words. That is REPAIR-7 made structural: a caller that must tell the four apart reads the enum instead
of comparing strings, and the probe (`stellurgytest damage weld`) drives the same silent decision the
player's right-click does.

**What the welder buys over a pickaxe**, given REPAIR-2's floor: the block stays. Breaking and
replacing a machine to mend a crack empties it — inventory, links, its own accrued wear. On plain
hull plate replacement is often cheaper and that is a legitimate outcome, not a balance failure.

## T1, as built (2026-10-06)

A controller block `repairBay` (`TileRepairBay`, `damage/repair/`) and a frame block `repairBayPart`
(any block implementing `api.damage.IRepairBayPart` counts). Mechanics are in structural-damage
MECH-DMG-36..40; the contract points they settle:

- **Size and rate (REPAIR-10).** Size = the controller plus the connected run of frames, at most
  `1 + RepairBaySize.MAX_PARTS`. Rate in FE/tick = `repairBayMaxPowerPerTick · N / (N +
  repairBayHalfRateSize)`. The controller counts so a lone one is a working bay of size 1.
- **Price (maintainer ruling 2026-10-05: spare parts).** The reserve holds finished blocks only. A hole
  draws one block of its recorded kind and places THAT block; a staged block draws one block of its own
  kind into `maxStage` stages of credit held by the bay — `maxStage` of the block's own home, so a spare
  engine buys an engine's ten stages and a hull block a plain block's four — spent a stage at a time in
  place. Only a finished block pays (`ItemBlock` / `ItemBlockSpecial`, one predicate for the reserve and
  for pricing), so a damaged block whose item is anything else — a door, a sign, a skull — answers
  `NO_RECIPE`: it has no price a reserve could hold, which is a different fact from a price not on hand.
  A spare equals the price only at the same item damage, so a worn spare engine does not pay. So the bay
  prices from no recipe at all, and `RepairCost` was not taught machine recipes: under the spare-part
  price nothing on this rung asks for one — the station's own repair is on the same price, and its
  assembler path runs its recipe in the assembler itself. `RepairCost` stays the welder's (T0) alone.
- **One law, two machines (REPAIR-11).** The price, credit, energy and banking above are
  `BayEngine`'s, held by the bay and by the rocket service station alike; this section's points hold
  for both. The station reaches every wear-bearing part of its linked rocket, restages it through the
  part's own `IPartWear` (a rocket part has no position the damage map knows), runs only while
  switched on by redstone and with no assembler beside it, and answers `NO_STRUCTURE` when no rocket is
  linked. Its screen shows its answer. No claims: every stage is paid as it lands, so two stations on
  one rocket each pay only for stages they took off.
- **Energy (REPAIR-8).** A stage costs `repairBayEnergyPerStage`; a hole a block's full run of stages.
  Energy goes in at the rate above, is banked toward the next step and saved; the bay never draws
  more than the step in hand still needs.
- **Claims (REPAIR-13).** Transient, in `BlockDamageSavedData`'s instance (never saved); a claim not
  renewed for two ticks is taken over; a bay gives its claim back on `invalidate` / `onChunkUnload`.
  The pool is per ship: the record walk over the ship's shipyard box, plus the tiles of that box's
  loaded chunks that keep their own wear (REPAIR-1's second home), and a claim covers either kind of
  position alike.
- **Answers (REPAIR-7).** `RepairOutcome` is the ladder's one vocabulary; the welder answers
  `REPAIRED/UNDAMAGED/NO_RECIPE/NO_MATERIALS/NO_CHARGE`, the bay
  `REPAIRING/UNDAMAGED/NO_RECIPE/NO_MATERIALS/NO_CHARGE/UNFILLABLE/NO_STRUCTURE`, shown on its screen.
  `UNDAMAGED` on a bay means "nothing this bay may take" — work another bay holds included.
  `NO_RECIPE` on a bay means "no finished block stands for it" (above).
- **Both homes.** Wear kept in a tile (`IPartWear`: the rocket motors, `TileWearable` tanks and seats)
  is in the work alongside holes and map-staged blocks, restaged through `DamageState.setStage` and
  paid exactly as a map-staged block. A tile in a chunk of the claim that is not loaded is not read —
  the walk never loads a chunk.
- **No recipes.** Neither block has one; recipes are the maintainer's.

## Open

- **Full restore vs one stage per action** — ruled (maintainer 2026-08-15 for T0; 2026-10-05 for the
  station): one stage per use/step. The station's ASSEMBLER path is the rocket's T2 and stays; its
  standalone repair-to-zero was brought onto the bay mechanic (2026-10-06) — one stage per step, out of
  spares, `BayEngine` — and `serviceStationStandaloneRepairMultiplier` went with it.
- **The world-side bound** — dissolved by the scope ruling (2026-10-05): the target is only ever a
  ship. Which ship a bay on a base serves waits for a base region.
- **What a stage COSTS in material** — ruled (maintainer 2026-08-15): a fraction of the block's
  own crafting recipe, `repairCostPerStageFraction` spread over the block's stages, rounded up per
  ingredient so no stage is free. Two consequences left open by it: a block nothing CRAFTS cannot be
  priced at all and is refused (`NO_RECIPE`), which rules out welding stone, ore and most gathered
  blocks; and where several recipes make the same block the first registered one is used, which is
  arbitrary but stable.
- **Whether the claim pool is per-structure or per-bay-reach** — ruled with the scope: per ship.
