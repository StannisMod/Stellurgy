---
id: C13
covers: what survives a logout for a player who is IN A SPACE CELL — presence, aboard-ness, and which record wins
confidence: medium
owner-subsystem: none — the movable-ship space subsystem; code in space/ + integration/vs + player/
see-also: [C8 (crew any-attitude — ABOARD is ITS term, never redefined here), C1 (NBT)]
---

# C13 — Space-presence persistence, clauses PRES-1..PRES-11

Maintainer rulings 2026-07-26 (R1-R9).

**Why the authority is explicit.** Two independent durable records of "this player is aboard" would
exist if both the space subsystem's `ShipAboardTag` `[V]` and a crew-system deck anchor were persisted,
with no stated authority between them. The unified aboard record (PRES-6) is the one authority; the crew
system keeps no persisted deck anchor.

## Terms — tagged by layer

The two vocabularies stay apart; a clause that mixes them is a bug in the clause.

**Layer 1 — universe.** **cell** = a region of space addressed by a `GalacticCoord`. **slot
dimension** = a transient binding of a pool world to a cell, re-minted every boot, therefore never
an identity. **PRESENCE** = the durable fact "this player was in cell C".

**Layer 2 — ship space.** **ship**, **subspace**, **deck** and **ABOARD(e,S)** all keep exactly
their C8 meanings and are not redefined here. Layer-2 position is a position relative to a ship.

Presence is the wider term: every aboard player has presence; not every present player is aboard.

## Clauses

- **PRES-1 (presence)** `[A]` A player whose world is a space cell HAS presence in that cell,
  aboard a ship or not. Presence names the cell by `GalacticCoord`, never by slot dimension id —
  the id is re-minted every boot
  (`SpaceEventHandler.java` already reasons this way for the aboard case) `[V]`.
- **PRES-2 (aboard refines presence)** `[A]` A player who is ABOARD(S) per CREW-C1 additionally
  carries a Layer-2 position relative to S. Seated and standing are two SHAPES of that position,
  not two records (R1, R5). Any passenger the ship's transform carries is aboard, not only a pilot
  and not only an occupant of a pilot seat (R5).
- **PRES-3 (authority)** `[A]` On restore, Layer 2 wins wherever it exists; Layer 1 answers for a
  present-but-not-aboard player. A restore may never place a player from his Layer-1 position while
  a Layer-2 one is available — that is precisely what puts a player where his ship used to be.
  (The same precedence the aboard record's own javadoc states for coordinates, `ShipAboardTag` `[V]`,
  honoured by `LoginRestore` `[V]`.)
- **PRES-4 (same point)** `[A]` An aboard player returns to the point he left (R2). If S was
  re-assembled while he was offline his stored point no longer denotes, and **every** returning
  crew member — pilot, seated passenger, or someone on his feet — falls back to S's PILOT SEAT
  point (R7). One rule for all roles; simultaneous returners may overlap for an instant, accepted
  explicitly.
- **PRES-5 (durability of the reference)** `[A]` Every stored reference must survive a
  re-assembly: ship identity is the Stellurgy durable ship id
  (`TileAdvancedFlightComputer.shipIdOrNull`) `[V]`, never the VS ship id; and a
  ship-relative point is stored relative to the flight computer, never as a raw subspace
  coordinate. A VS id and a raw subspace point are what a re-assembly invalidates, so the crew system
  persists neither.
- **PRES-6 (write cadence, ONE writer)** `[A]` The durable record has exactly ONE writer and is
  refreshed at most once a second (20 ticks), plus on the events that precede a read — logout and
  world save. It is NOT maintained per tick (R6). Ruling R9 (2026-07-26): the crew system has no deck
  anchor of its own — `RelogDeckHold` reads the unified record — rather than two records kept in sync.
  CREW-C14's same-dimension relog reads the unified record and is pinned against it
  (`VSCrewRelogPersistenceTest`). The one writer is `AboardRecord.reconcile`.
- **PRES-7 (hull)** `[A]` A hull-stander is NOT aboard — CREW-C11 keeps its meaning — but HAS
  presence, and returns where he stood (R4). **No live producer today**: the record is dropped
  exactly on the hull-stand transition (`ShipFrameTravel`) `[V]`.
- **PRES-8 (dismantle ejects)** `[A]` Dismantling or landing a ship with crew aboard puts each crew
  member out at a safe position beside the site AT THAT MOMENT (R3). No record is expected to
  describe a ship that no longer exists — the problem is solved at write time, not at read time.
- **PRES-9 (a present player claims his cell)** `[A]` A player restored into a cell that is not
  materialized causes it to be materialized and takes an occupant refcount, exactly as a restored
  ship's owner does (R8). The claim MUST be paired with his logout — the existing pairing is
  `SpaceEventHandler.ServerPart.heldCells` (claimed at login, `SpaceEventHandler.java:194`; given back
  at logout) `[V]`, held in the server's `ServerState.spaceEvents` so it dies with the server.
  An unpaired claim pins one of a small fixed pool of slot worlds for the life of the server.

- **PRES-10 (a release lets go of EVERYTHING, and says what it let go)** `[A]` Returning a player to
  the plain world is ONE operation, not a coincidence of subscribers: every subsystem that binds him
  to a ship or a cell releases its own binding, and the operation answers with the list of what was
  released. **A release is the opposite of a logout and the two must never share a path** — a logout
  PRESERVES the record (PRES-6 refreshes it on exactly that event) because that is how a player keeps
  his ship across a restart; a release destroys it. Carried by `PlayerRelease.toTheWorld`
  (`player/PlayerRelease.java`) `[V]`, which holds no per-player state: each subsystem stays the
  single source of truth for what it bound, or two places would know where a player is and be free
  to disagree. What the release owns is the ORDER and the report.
  **It is a DIRECT CALL on a fixed participant list, not a bus post**, and that is the clause and not
  an implementation note. A Forge event is a statement about the world — *this happened*, or *this is
  about to and you may veto it*. A request that asks unknown handlers to do work the caller then
  depends on is neither, and it gives up the three things this operation needs: an ORDER over five
  mutations, COMPLETENESS (nothing makes an owner subscribe, and a missing one is silent in exactly
  the mechanism built to end silence), and a FAILURE path. A bus is also open, so any mod could
  subscribe to an internal invariant of this one. The release is therefore a direct operation, not an event.
  Five bindings are answered by their owners — the aboard record (`ShipAboardTag`), the cell claim of
  PRES-9 and any queued seating (`SpaceEventHandler.releaseClaims`), the deck hold
  (`DeckHold.releaseHold`), the hyperspace drift run (`HyperspaceVoid.releaseDrift`), and the
  post-transfer suit-check grace (`AtmosphereHandler.releasePlayer` over
  `atmosphere/RocketTransferGrace`) `[V]`. The sixth, a ridden mount, is
  the CALLER's: it is the player's own field rather than state a subsystem holds for him, and where
  the body should END UP differs by situation.
  **Live producer**: `LoginRestore.Reason.SHIP_UNKNOWN` — a player who returns aboard a ship the
  ship registry has no record of (`SpaceEventHandler.java` `[V]`).

- **PRES-11 (the record survives what the PLAYER survives)** `[A]` The aboard record is a fact about
  the player, so it outlives every event he outlives — a logout, a restart and **a death**. It lives
  in his own `IPlayerBindings` capability (`player/`), written by that capability's storage through
  `ShipAboardTag`'s NBT codec so the shape on disk has ONE definition, and copied on
  `PlayerEvent.Clone` `[V]`.
  **Death is why it is a capability.** A record directly under ForgeData would be lost on death:
  `EntityPlayerMP.copyFrom` copies only the `PlayerPersisted` sub-tag and Forge copies no capability
  at all, so a player who died aboard his ship would lose the only record of which ship it was — and
  PRES-3's authority and PRES-4's same-point return, which are written about "a returning crew
  member", would quietly not hold for him. To a player the two cases are indistinguishable.
  **What this clause does NOT decide**: whether a RESPAWN then places him aboard. Keeping the record
  is what makes that possible; where a death puts you is a separate mechanic and is not ruled here.
  **Not every per-player binding is durable** — see PRES-10's list. THREE are live state and stay
  with their owners: the deck hold, the cell claim, and the hyperspace adrift run. The last is
  **ruled NOT persisted (maintainer, 2026-09-15)**: the case for persisting
  it is that a relog resets a death countdown, but the countdown is 200 ticks, so a relog buys ten
  seconds of falling in the same void — it postpones rather than saves — while a returning player is placed by the login
  restore, *a fresh judgement, not a continuation* (`HyperspaceVoid.pruneDeparted`). The capability has no accessors for it.

## Reachability — what is live, what is a forward requirement

Stated because a contract that does not distinguish these reads as a description of present
behaviour and quietly becomes fiction.

- PRES-1..PRES-6 and PRES-9 constrain code paths that exist now.
- **PRES-7 has no producer** (see its citation) — a hull-stander currently has no durable position.
- **The asteroid case behind R4 is unreachable today**: slot worlds are void
  (`WorldProviderSpaceSlot.java`, "void by default") `[V]`, so there is nothing in a cell to
  stand on except a ship. Written for the cell content that is coming.
- **PRES-8's trigger may not exist**: C9 records disassembly as conditional ("WHEN disassembly
  exists"). If there is no dismantle path, PRES-8 is a requirement on the one that gets built.
- **PRES-6's "world save" write point does not exist in 1.12** `[V]`. The logout half
  is real — `PlayerList.playerLoggedOut` fires Forge's logout event BEFORE `writePlayerData`
  (vanilla `PlayerList`), so a refresh there lands in the saved file. The
  world-save half has no hook: `MinecraftServer` writes ALL player data before any world is saved
  (`saveAllPlayerData()` then `saveAllWorlds()`, same order on shutdown), and
  `SaveHandler.writePlayerData` fires its Forge event only after writing. What bounds a crash is
  therefore the cadence alone — ≤1 s, which is the price R6 explicitly accepted.
- **A record may carry NO presence** `[V]`. ABOARD is not confined to cells: a ship
  parked on a planet carries crew, and CREW-C14's relog promise covers them
  (`VSCrewRelogPersistenceTest` runs in dim 0). Since R9 makes this record the only durable one,
  it has to be expressible without a `GalacticCoord` — Layer 2 without Layer 1. Such a record
  answers "which ship", never "which world", and the dimension decision must not consult it
  (`SpaceEventHandler.onPlayerLoadFromFile` gates on `hasPresence()`). PRES-2's "aboard refines
  presence" holds INSIDE the cell vocabulary and is not a claim that every aboard player is in a
  cell.

## How the standing and durability clauses are met

- **PRES-2/PRES-3 (standing posture).** The record is derived from state by one writer
  (`AboardRecord.reconcile`) `[V]` — a dismount does not clear it — and expresses a standing posture as
  a flight-computer-relative deck point; the login deck hold puts him back on it.
  `SpaceLoginRestoreClientE2ETest.aPilotWhoStoodUpBeforeLoggingOutComesBackAboardOnHisFeet` `[T]`
  (not seated, not the overworld, at his ship, realizing his own cell).
- **PRES-5/PRES-6 (one record, durable reference).** There is no persisted deck anchor:
  `RelogDeckHold` reads the unified record, and the record stores the durable ship id plus a
  computer-relative point. CREW-C14 holds on that source: `VSCrewRelogPersistenceTest` `[T]`.
- **CREW-C14 holds across a slot-cell restart too** `[T]`
  (`…aPilotWhoStoodUpBeforeLoggingOutComesBackAboardOnHisFeet`), the cross-dimension case C8
  defers to this doc for.

## Coverage

- PRES-1/3 (aboard, seated) `[T]`
  `SpaceLoginRestoreClientE2ETest.aPilotWhoLoggedOutSeatedOnHisShipComesBackAboardItAfterAServerRestart`
  and `…aPilotWhoBoardedOnThePlanetAndFlewUpComesBackAboardAfterAServerRestart` (the latter also
  witnesses that the record is produced BY the flight: absent on the ground, present on arrival).
- PRES-2/PRES-3 (standing) `[T]` `…aPilotWhoStoodUpBeforeLoggingOutComesBackAboardOnHisFeet`.
- PRES-5 (durability of the reference) `[T]` `ShipRelativePointTest` pins the round trip and its
  invariance under a re-assembly that moves the whole ship elsewhere in subspace; the durable-id half
  is `[T]` through the restart legs above, which survive the slot dimension being re-minted.
- **Falsifiability witness** for every "he is aboard" oracle in that class `[T]`
  `…aPlayerWhoWasNeverAboardIsNotRestoredOntoTheShip` — same world, same entry, same ship, nobody
  boards, and the oracles answer no.
- PRES-4, PRES-6..PRES-9 — no coverage. (PRES-6's cadence is exercised incidentally: the legs above
  poll for the record rather than sampling it on the mount tick.)
