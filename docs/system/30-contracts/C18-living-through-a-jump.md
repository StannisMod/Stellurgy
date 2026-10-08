---
id: C18
covers: what a jump promises the people ABOARD the ship making it — where they are during the flight, what they may do there, and what carries them across each crossing
confidence: high
owner-subsystem: none — the movable-ship space subsystem; code in space/ + integration/vs (JUMP-7 is retired in place, number permanent)
see-also: [C8 (ABOARD is ITS term, never redefined here), C13 (PRESENCE; PRES-2/4/5/9), C9 (ship control)]
---

# C18 — Living through a jump, clauses JUMP-1..JUMP-15

Maintainer rulings 2026-08-05.

**Why the interval is a contract.** The transit is a ship-moving mechanic and the crew is handled
by its two crossings `[V]`; the interval BETWEEN them is something a player experiences, so it is
written down and tested rather than left empty in the middle.

## Terms — tagged by layer

The two vocabularies stay apart.

**Layer 1 — universe.** **hyperspace** = the single permanent world holding every in-flight ship
(`HyperspaceWorld`). **lane** = a ship's parking tile in it, 2048 apart (`HyperspaceTiles.java:25`)
`[V]`. **transit** = the interval between a ship's two crossings.

**Layer 2 — ship space.** **ship**, **deck** and **ABOARD(e,S)** keep exactly their C8 meanings.
**PRESENCE** keeps its C13 meaning. **transit crew** = the set of players ABOARD ship S when its
departure crossing begins; *seated* and *standing* are two SHAPES of membership, not two sets
(PRES-2), and membership does not lapse during the flight.

## Status at a glance

One row per clause; `[T]` pinned by a test · `[V]` verified against code · `[A]` assumed.

| clause | promise, in a line | state |
|---|---|---|
| JUMP-1 | the crew is in the same world as its ship, the whole flight | `[T]` |
| JUMP-2 | the interval is livable: stand up, walk, use the ship — and it still looks like a flight | `[T]` livable, machinery and backdrop; only the measured WALK is unpinned here |
| JUMP-3 | both crossings carry the crew in whatever posture it is in | `[T]` |
| JUMP-4 | a seat is returned; the posture the crew is IN at the arrival cut is returned | `[T]` both halves |
| JUMP-5 | no world is unbound under someone standing in it | `[T]` |
| JUMP-6 | an aborted jump is a no-op for the crew | `[T]` |
| JUMP-7 | retired | — |
| JUMP-8 | leaving your ship in hyperspace kills you | `[T]` |
| JUMP-9 | hyperspace is durable: a restart is survivable BY a jump | `[T]` ship; `[T]` the crew's durable evidence |
| JUMP-10 | nothing is left in hyperspace unaccounted for | `[T]` |
| JUMP-11 | every body aboard is carried by its ship-relative point | `[T]` crew and loose bodies alike |
| JUMP-12 | a cut leaves nothing bound to what it removed | `[T]` the match; the sweep itself `[V]` |
| JUMP-13 | a jump acts on the ship it NAMES, at every step | `[V]` |
| JUMP-14 | one lane, one ship | `[V]` |
| JUMP-15 | an arrival has NO failure path | `[A]` |

## Clauses

- **JUMP-1 (the crew travels with the ship)** `[A][BEH]` Every member of the transit crew is in the same
  world as its ship for the whole transit; a jump never leaves one behind in the cell it departed
  from. The departure hands the crew to the same per-tick retry the arrival uses, seating them on
  the parked hull without releasing the capture the far end still needs. Pinned by `VSTransitCrewGroupTest#aSeatedCrewMemberIsAboardHisShipInHyperspaceWhileItIsStillFlying`.
- **JUMP-2 (the interval is livable)** `[T][BEH]` (partly) During the transit the ship is an ordinary live
  world to those aboard: its tile entities tick, its blocks are usable, and a crew member may leave
  his seat and walk the deck. This is what makes a jump a journey rather than a cutscene.
  Pinned for the load-bearing half — a crew member stands up MID-FLIGHT with the real sneak key, is
  resolved on his deck in hyperspace, and is still there and alive after longer than the void's whole
  budget. **And his ship keeps RUNNING under him** `[T]` — the flight computer's own per-tick
  recorder, keyed on dimension AND subspace position, goes on taking server-tick samples while the
  hull is parked. Two controls make that reading mean something: the same instrument on the same ship
  in its origin cell (or silence in hyperspace could be a key nobody writes under), and a subspace
  address a thousand blocks along that must report nothing (or a rising count could be the server's
  rather than this ship's). **Not pinned**: walking a measured distance along the deck — the
  transit fixture's deck is 3×3, so a walk leg on it measures the edge; the walk-the-deck contract is
  pinned on a ground fixture by `VSCrewRidesRollingDeckTest`.
  **And it still LOOKS like a flight, in every posture** `[T]` — the corridor is drawn for anyone
  whose client is in hyperspace, not for whoever happens to be sitting on a seat (C14 CON-C14-18).
  Hyperspace has no synced bodies and its descent ring is deliberately suppressed, so the corridor is
  the entire visible statement that the ship is moving. The gate is the CLIENT'S OWN WORLD — the same
  primary fact the server derives the phase from — which costs one named int in `PacketSlotDimSync`,
  since a remote client cannot otherwise tell hyperspace from a pool slot. The seat has one job: it is
  the interpolated source of the corridor's AXIS for a seated pilot, with a standing crew member's axis
  read off the ship he is on. The HUD's departing/arriving refinement is seat-only on purpose — it is
  a cockpit panel, not a backdrop. Pinned by `VSTransitCrewGroupTest#aCrewMemberLivesInHyperspaceUntilHeStepsOffHisShip`.
- **JUMP-3 (a crossing carries whoever is aboard)** `[A][BEH]` Both crossings — departure and arrival —
  carry every member of the transit crew in whatever posture he is in; a crew member on his feet is
  never silently dropped. The capture enumerates STANDING crew beside the seated, keyed by
  the deck resolver's own answer rather than by a box (`CrewTransfer.walkStanding`), records each
  one's deck point against the flight computer, and the far side puts him back at that point, held
  there until his ship exists. Pinned by `VSTransitCrewGroupTest#aWalkingCrewMemberTravelsWithHisShipThroughHyperspace`.
- **JUMP-4 (a seat is returned, a posture is preserved)** `[A][BEH]` On arrival a crew member who is seated
  is seated again on the same seat — identified by its flight-computer-link offset, PRES-5's durable
  reference, invariant under re-assembly — and one who is on his feet arrives standing aboard, not
  seated late.
  **The posture that must be returned is the one he is IN when the arrival cut happens**, not the one
  recorded at departure: the crew is captured once, at the departure cut, so replaying that record
  would force-mount a crew member who stood up in the corridor (which JUMP-2 explicitly invites) back
  into his chair on arrival. `CrewTransfer.refreshPostures` re-reads postures at the second cut;
  pinned by `VSTransitCrewGroupTest.aCrewMemberWhoStoodUpMidFlightArrivesOnHisFeet`,
  red-witnessed. Not yet playtest-accepted.
- **JUMP-5 (a world is not unbound under an occupant)** `[A][BEH]` While a player is in a cell or in
  hyperspace, that world is not evicted or rebound beneath him. The guarantee is DERIVED, not
  claimed: eviction asks the world who is standing in it (`SlotBinder.hasOccupants`) instead of
  trusting a paired counter — a crew member carried in aboard a ship holds no claim, and a jump
  releases the origin cell's own count one line after dismounting its crew into it, so a claim-based
  rule is only as good as the pairing nobody forgot. With every idle cell occupied the pool refuses to
  bind (recoverable, visible) rather than emptying one under someone. Hyperspace needs nothing here:
  it is force-kept-loaded and never evicted. Pinned by `SpaceManagerTest#aCellWithSomebodyStandingInItIsNotEvictedUnderHim`, `SpaceManagerTest#aCellWithNobodyInItIsEvictedByTheSameArrangement`.
- **JUMP-6 (an aborted jump is a no-op for the crew)** `[A][BEH]` A failed departure leaves every crew
  member seated where he was — the abort re-seats them onto the still-present origin ship.
  The obligation exists because the capture runs FIRST, and has to: the crossing is about to cut the
  blocks the crew is standing on. So by the time the cut refuses, everyone aboard is already
  dismounted beside a ship that never went anywhere, and doing nothing at that point ejects a whole
  crew for a jump that did not happen. Pinned in `ShipTransitManagerTest`: the put-back is aimed at
  the ORIGIN anchor rather than at a far end, no flight is created, nobody is left queued for a later
  re-seat, the origin cell stays loaded, and the lane the attempt reserved comes back to the
  allocator (the crossing seam's `failDepart` switch is what the test flips). Pinned by `ShipTransitManagerTest#anAbortedDepartureIsANoOpForTheCrew`.
- **JUMP-7** — retired. (A restart must not remove a ship from hyperspace: see JUMP-9.)
- **JUMP-8 (the void is lethal)** `[T][BEH]` A crew member who leaves his ship's volume in hyperspace
  dies. Nothing prevents him from trying: the danger is the mechanic, not an invisible wall.
  Lanes are 2048 blocks apart (`HyperspaceTiles.java:25`) `[V]`, so no neighbour case is needed. The
  kill is a consecutive-tick countdown on the same 200-tick budget the crossing's retries spend, so
  nobody dies for the deck resolver's legitimate silence; it bypasses armour, creative and spectator
  exempt. Pinned by `VSTransitCrewGroupTest#aCrewMemberLivesInHyperspaceUntilHeStepsOffHisShip`.
- **JUMP-9 (hyperspace is durable)** `[A][BEH]` A server that stops with ships in transit brings them back
  **into hyperspace** — same lanes, ships physically present, flights resumed from where they were
  persisted — and a crew member who returns mid-jump is placed at his ship there, never at spawn.
  A restart is not an event a jump can be removed by.
  **HOLDS for the SHIP `[T]`; the CREW half is `[A]`.** A real
  two-boot restart puts one ship into hyperspace and gets one back, with a different dimension id on
  each boot, and the restored jump is carrying its HULL rather than its snapshot
  (`HyperspaceSurvivesARestartTest`, asserting `transitsParked >= 1` before the ship count).
  Nothing is wiped: the chunk folder is named after the world, and the physics mod's per-world ship
  registry lives inside it and round-trips (`hyperspace/data/capabilities.dat` ≈ 1.3 KB holding a
  ship). Two things make this work, and neither is the registry: the boot restore LOADS hyperspace
  before consulting it (both readers ask only a world that is already loaded, and hyperspace loads
  lazily on the first crossing — otherwise every record sees an empty lane), and a hull may not be
  deregistered while the physics mod is still streaming its chunks.
  **The crew half**: a crew member who logs out mid-jump carries durable evidence of it. Hyperspace is
  in no cell so his record would hold no coordinate, and the only other evidence, the
  dimension he was saved in, is re-minted by a free-id scan every boot and so expires at exactly the
  event it is needed for. A SEATED crew member needs the same record: vanilla brings a seated player
  back on his own mount — true beside a planet, false when the mount is a seat dummy on a hull in a
  world he cannot name. The record therefore says "mid-jump" itself, both postures, and the login
  gate opens on it (`ShipAboardTagTest`, verified by mutation: dropping the flag from the write reds
  the pin).
  **What is NOT covered**: the transport itself across a restart. The two-boot witness parks an
  UNMANNED hull, and the client harness cannot span a server restart (its reconnect is a logout/login
  against the same running server), so "he opens the space restore and it resolves to his ship in
  hyperspace" is pinned as a decision, not driven end to end with a player.
  **Who restores what**: the durable record owns IDENTITY — which ship belongs in which
  lane and how far along its flight is — while the physics mod's per-world data supplies the BODY; a
  disagreement resolves in favour of the record. The block snapshot stays the repair path for a
  record whose ship did not come back, not the normal way a jump resumes.
- **JUMP-10 (nothing is left in hyperspace unaccounted for)** `[A][BEH]` At boot every ship in hyperspace
  is matched against the restored records: matched ships resume their flight, an unmatched one is
  disposed of rather than left an untracked, keep-loaded ghost. This is the obligation JUMP-9 takes
  on: a durable hyperspace reconciles instead of wiping.
  `ShipTransitManager.reconcileParkedShips` matches by LANE and disposes by deregistration plus
  retiring the lane (`hyperspace lane 0 held a ship no transit record claims - disposed of it`).
  An unclaimed hull is debris by definition, so a durability test must give production the claim.
  Deregistering a ship whose chunks are still queued must not throw out of the world tick in the
  physics chunk provider: "nothing loaded" is one question — `WorldServerShipManager.isShipInUse` —
  covering loaded, queued and streaming. Pinned by `ShipTransitManagerTest#bootDisposesOfEveryParkedShipNoRecordClaims`, `ShipTransitManagerTest#anOrphanIsFoundInALaneNoSurvivingRecordCameNear`.
- **JUMP-11 (a body aboard is carried by its ship-relative point, at rest)** `[A][BEH]` A crossing carries
  every body aboard the ship — not only its crew, and not only what sits on a seat: each one's
  position is taken as an offset from the ship's flight computer before the cut and re-established
  from that offset after the re-assembly, with its motion zeroed. The offset is the binding
  that survives, because a re-assembly re-mints the ship's subspace and an absolute subspace
  coordinate then names nothing; `ShipRelativePoint` is that one definition and `ShipRelativePointTest`
  pins its invariance under a re-assembly `[T]`.
  **The two populations are carried differently, and the difference is client authority.** A crew
  member's movement is owned by his client, so he is placed and then HELD until that client takes the
  deck capture over (`DeckHold`). A mob or a dropped item is server-owned, so it is STOWED — written
  down, taken out of the world at the cut, re-created at its offset on the far side, no window at all
  (`AboardBodies`). Both are aboard by ONE definition: the ship's stay region, the volume the void
  judges a crew member by.
  **Why a held body needs holding at all**: the far-side re-assembly is asynchronous,
  so for a window there is no ship under the body, and under world gravity — on a non-upright ship
  world-down is not the deck — it falls off or through first. A seated rider is immune: the seat dummy
  is the anchor. The hold is server-authoritative and may cost the client a couple of ticks of its own
  movement, and it must HOLD rather than suppress input: the far side is retried for up to 200 ticks,
  and a rare slow case would otherwise read as a hang. Pinned by `VSTransitCrewGroupTest#aWalkingCrewMemberTravelsWithHisShipThroughHyperspace`, `VSJumpCarriesLooseBodiesTest#aJumpCarriesTheBodiesLyingOnItsDeck`.
- **JUMP-12 (a cut leaves nothing bound to what it removed)** `[V][BEH]` A crossing that cuts a ship's
  blocks also removes the entities bound to those blocks — the seat dummies above all. A rider left
  mounted on a chair whose ship no longer exists is an inconsistent state, and it needs no
  measurement to be a defect. `VSIntegration.crossShip` retires them immediately after the
  cut, matching by the SEAT BINDING rather than by position — a dummy is glued to its ship's world
  position, nowhere near the subspace shipyard box, so an AABB query over the cut box finds none.
  **The boundary is "nothing comes back", not "a cut happened"**, and it is load-bearing: the generic
  cut also serves rocket and station assembly, where the blocks ARE re-pasted and the pilot must stay
  seated — what `BlockPilotSeat.breakBlock`'s `isRelocationInProgress` guard buys
  (`BlockPilotSeat.java:124`) `[V]`. A sweep there would unseat a pilot on every assembly.
- **JUMP-13 (a jump acts on the ship it NAMES, at every step)** `[V][BEH]` Departure, park, arrival cut,
  pose settle and crew placement each act on the craft the jump is keyed by — never on "whichever
  ship is nearest" the anchor it happens to hold. The entry/station resolution, the departure and the
  ARRIVAL cut all resolve through one rule, `VSShipCrosser.identifyShipToCut`, and the arrival
  passes its answer to the identity-keyed `crossShip` overload. **The clause AIMS rather than merely
  refusing**: a crossing carries the craft's durable name onto the record it creates, so the arrival
  resolves its own hull by identity. (Re-establishing the name only from the ship's own tick would
  fail, because a parked hull gets zero ticks over a whole jump; the clause would then be upheld only
  by REFUSING a positively-wrong hull.) Measured: `byDurableId=<uuid> cutting=<same uuid>` with the
  computer's tick counter still at zero. **Its precondition is JUMP-14**: a position is a valid handle
  on a ship only where the position is unambiguous.
- **JUMP-14 (one lane, one ship)** `[V][SYS]` A hyperspace parking lane holds at most one registered
  craft. The lane allocator is therefore a property of the hyperspace WORLD
  (`HyperspaceWorld#lanes()` on the server's one `HyperspaceWorld`, held by its `ServerState`)
  rather than of a transit manager: an allocator can only
  promise this against every ship in the world it parks in, and a second manager over the same world
  starts at lane 0 and hands out an occupied one (measured: two registered ships at the identical
  position, with the durable id of a third jump on the computer standing there).
  **A violation is silent** — nothing throws, and the next position-keyed lookup
  simply answers about the wrong hull. FOR: JUMP-13.
- **JUMP-15 (an arrival has NO failure path)** `[A][BEH]` A crossing that has departed ARRIVES. There is no
  outcome in which it does not — no budget after which it stops trying, and nothing the crew is told
  about an arrival that never happened. An arrival that does not complete is a DEFECT to be found,
  never a case to be handled.
  Maintainer ruling 2026-09-15, asked as an explicit question with three candidate shapes and
  answered *"не может"*: *"«прибытие не состоялось» — пиздёж, такого не должно быть, у нас есть
  контракт."*
  **The code holds the opposite belief** `[V]`: `ShipCrossingService` carries
  `MAX_SETTLE_ATTEMPTS = 200` (`ShipCrossingService.java:39`; the give-up at `:281`) and an `abandoned()`
  callback (`:170`), and all three arrival controllers implement it (`CellCrossingController.java:305`,
  `DescentController.java:262`, `ShipEntryController.java:311`) — settling the ledger anyway and
  messaging the crew with a `…failedKey`. That courtesy makes the breach look like a feature: a give-up
  dressed as a handled case reads, to everyone downstream, as an outcome the design admits. While it
  stands the clause is violated.
  **What this does NOT license**: an unbounded retry nobody can see. The settle keeps working because
  the arrival is owed, and if it stops making progress that is a diagnostic to be surfaced as one —
  loud and about the machinery, not a message to a player about his ship.

## Interaction with the offline-progress policy

`ShipTransitManager` pauses a transit when no aboard crew member is online, under the
`spaceTransitOfflineProgress` policy (`ShipTransitManager.java:607`) `[V]`. Because the crew physically
lives in hyperspace, "aboard" and "online" are not independent: a logout removes the player from the
world his ship is in. This contract does not change the policy; the interaction is recorded, not
resolved.

## Coverage — what each pin actually witnessed

Three things are `[V]` INSIDE otherwise-pinned clauses, and are written down here rather
than hidden behind a green row: JUMP-2's measured walk along a deck (the transit fixture is 3×3),
JUMP-12's sweep as opposed to its match (it needs a real rider on a real crossing), and JUMP-9's
transport of a live player across a restart — the two-boot witness parks an unmanned hull and the
client harness cannot span a server restart at all.

- **JUMP-1** — `VSCrewRidesItsShipThroughHyperspaceE2ETest`. Red against a build where the client
  reported dim 105 for all **40** in-flight samples — the cell it departed from, against hyperspace
  115 — while the run's own `crewDim` oracle answered 115 throughout; green with the departure-side
  boarding, crew aboard by the FIRST sample; the test's sample-count gate rules out a vacuous pass.
- **JUMP-3 + JUMP-11 (crew half)** — `VSTransitCrewGroupTest`'s walking-crew scenario. A real
  client boards, stands up with the SNEAK key, and is observed twice: mid-flight (own dimension =
  hyperspace, un-seated) and at the far end (target cell, resolved on a deck again, still on his
  feet). Red-witnessed at **40** in-flight samples in the departed cell; green after the carry, three
  pre-stimulus controls firing. *Not pinned*: the arrangement re-drops the bot when the dismount lands
  him off the 3×3 deck, so "a dismount leaves you standing aboard" is another test's contract.
- **JUMP-11 (loose bodies)** — `VSJumpCarriesLooseBodiesTest`, on the SERVER tier because there is
  no client in this contract. Its control is production's own aboard test rather than a proximity
  proxy, and both ends are asserted: present at the destination, absent where the ship used to be.
- **JUMP-2 + JUMP-8** — one client scenario, because the livable interval is the only honest control
  for the lethal void: the same body, on the same hull, alive across the same span, then off it and
  dead. It asserts WHICH death — the chat must name the void of hyperspace — because a body stepping
  off a lane at Y=128 meets vanilla's out-of-world damage inside the same window, so without that
  discriminator the scenario is green on a build where the mechanic does nothing.
- **JUMP-4 (posture half)** — `VSTransitCrewGroupTest.aCrewMemberWhoStoodUpMidFlightArrivesOnHisFeet`.
  Boards SEATED, commits the jump from the chair, stands up in hyperspace, finishes the
  jump, and reads the client's riding state at the far end. Red-witnessed on the reverted tree, where the
  deck probe answered `"isRiding":true`. **Its control is a sibling that must stay green on BOTH trees**:
  `aWalkingCrewMemberTravelsWithHisShipThroughHyperspace` stands up BEFORE the jump, so the departure
  record already said STANDING and replaying it is accidentally correct — which is precisely why a
  posture test that does not change a posture mid-transit measures nothing.
- **JUMP-4 (seat half)** — `VSMidTransitRelogControlTest`. Red-witnessed, green 5/5
  serially: the arrival re-seat's "already seated" test must not accept ANY `EntityDummy`, or a
  pilot carried into the target dimension still bound to the DEPARTURE hull's dummy is booked as
  seated and never mounted. **Scope**: it pins that a login REPLACES the player's server entity and
  the re-seat must find the returned one; it does NOT reach Stellurgy's mid-transit login restore.
- **JUMP-5** — `SpaceManagerTest`, with its own control and verified by MUTATION: removing the guard
  reds exactly the two pins, nothing else.
- **JUMP-9/JUMP-10** — `HyperspaceSurvivesARestartTest`, two real server
  JVMs on one world root. It asserts the SHIP half of JUMP-9 (`transitsParked >= 1`, then the ship
  count) and JUMP-10 through its arrangement: the hull has to be CLAIMED for it to survive, and the
  run that forgot the claim is the run in which the reconciliation collected it. The CREW half of
  JUMP-9 is uncovered — the parked ship is unmanned.
  `VSShipTransitPersistTest` pins the snapshot path JUMP-9 demotes to repair.
- **JUMP-6** — `ShipTransitManagerTest.anAbortedDepartureIsANoOpForTheCrew`.
- **One instrument looks like coverage it is not**: `VSShipTransitCrewE2ETest` asserts riding
  before the jump and riding in the target dimension after it `[T]`, and never observes the client
  between them. Both assertions are true whatever happens in the middle — the ends are exactly the
  part that IS restored.
