---
id: C31
covers: who owns a body's position at each tick when several mechanisms want it — the deck, a mount, and the intents that work to put a body on a deck (the dismount window, the pending seed, the declared hold) — what ends each of them, and what a foreign teleport does to an intent
confidence: DESIGN-STAGE — HOLD-2 (dismount window, pending seed, resolved declared hold), HOLD-3, HOLD-6 and HOLD-7 built; HOLD-1, HOLD-4, HOLD-5 partly realised
owner-subsystem: integration-vs — the code is `integration/vs/DeckFrameTick`, `integration/vs/DeckBoarding` (the pending seed), `integration/vs/DeckHold` (the declared hold), `entity/EntityDummy` (the dismount window), `network/PacketDeckCapture`
see-also: [C30 (what a deck does to a body it holds — ENT-4 result writes), C8 (CREW-C1 boarding by dismount, CREW-C4 release, CREW-C14 relog), C9 (SHIPCTL-1: whether a seated body pilots), C18 (a crossing carries its crew), C23 (deck motion across the wire)]
---

# C31 — The hold lifecycle, clauses HOLD-1..HOLD-7

C30 says what a deck does to a body it HOLDS; C8 what a crew member aboard experiences; C9 that a seated
pilot's input reaches his computer. None of them says who owns a body's position when more than one
mechanism wants it, or what ends a hold that is only INTENDED — and five mechanisms in the code do. This
contract is that. **The code is a snapshot; this document is the authority** (methodology §7b).

## Terms

- **Owner** of a body, on one side, at one tick — the one mechanism that decides its position:
  - **WORLD** — vanilla movement and the physics substrate (C30's M4);
  - **DECK(S)** — a deck episode on craft S (`DeckFrameTick.Episode`, one per side, on the `Entity`
    object — C30 HELD);
  - **MOUNT(m)** — the body rides m and m's rider glue places it; a pilot seat's mount is an
    `EntityDummy`.
- **Intent** — a mechanism that does not own the body but works to make DECK(S) own it:
  - **dismount window** — server, `EntityDummy.keepDismountedPilotOnDeck`: for `DISMOUNT_HOLD_TICKS`
    after a pilot leaves a linked seat it re-sends `PacketDeckCapture` to him every tick;
  - **pending seed** — client, `DeckBoarding.installPendingSeed`: the client half of that packet; waits
    `PENDING_SEED_TTL_TICKS` for the body to be capturable, then opens DECK(S) through
    `DeckFrameTick.holdSeeded`; a **restore** seed (`PacketDeckCapture.restore`) re-establishes a
    recorded state;
  - **declared hold** — server, `DeckHold.Holds`, armed by a relog, a crossing (`pinInPlace` at the
    departure, `holdOnDeck` at the arrival) or a pilot seat taken while its pilot was away; it re-sends
    a restore seed until DECK(S) holds the body on its own craft.
- **Result write** — a position with no direction: `setPosition`, every teleport (C30). **Foreign**
  to an intent — made by anything but its owner, the intent itself, or the mechanism that ARMED the
  intent while it is still carrying the body (a crossing's own transfer of the crew it pinned: it pins
  its standing crew and THEN transfers them, so voiding there would drop the pin it just made).
- **Teleport** — a result write the other side must apply: on the server, every
  `NetHandlerPlayServer.setPlayerLocation`; on the client, every `handlePlayerPosLook`.

## Clauses

- **HOLD-1 (one owner)** On each side, at every tick, a body has exactly one owner, and every change of
  owner is an edge recorded with `from`, `to` and the reason.
- **HOLD-2 (an intent belongs to one presence)** An intent is made for the body as it was when the intent
  began. A foreign teleport, a dimension change the intent did not make, a death or a new mount VOIDS
  every intent the body has, on the side that sees it; the void is recorded, and a voided intent never
  applies. Ruled 2026-10-07: EVERY foreign teleport, not only one that leaves the craft's reach — an
  intent is a promise about where the body will be, made from where it was, and a teleport replaces the
  premise.
- **HOLD-3 (no teleport war)** No owner and no intent writes a body's position to undo a result write
  made after it began. A result write is adopted (C30 ENT-4) or, if it takes the body out of its owner's
  reach, ends that owner (C8 CREW-C4); it is never fought. **An intent teleports a body at most once** —
  to put it where the intent is for — and then waits.
- **HOLD-4 (an intent ends once)** Every intent ends exactly once — TAKEN (DECK(S) now holds the body),
  VOID (HOLD-2), REFUSED (the deck would not take it) or EXPIRED — and the end is recorded. A window is
  a give-up, never a wait.
- **HOLD-5 (the sides converge)** For a player, whose movement his client owns, the server's owner
  follows the client's within one exchange. A server intent the client never takes ends by HOLD-4; it is
  never held open by re-sending.
- **HOLD-6 (holding does not disarm the player)** No owner and no intent makes the held player's own
  acts inert: a click, an item use or a seat right-click made while an intent runs is handled as it would
  be without it. While a restore seed waits for its craft the CLIENT keeps the body where the intent put
  it — the body is still, and the player is not. (A server write of a player's position reaches his client
  only as a teleport, so with the server pinning once, a returning player would fall on a client that has
  not streamed his craft yet unless the client holds him still.)
- **HOLD-7 (a mount ends the rest)** Mounting ends DECK(S) (C8 CREW-C4) and voids every intent
  (HOLD-2). Dismounting a linked pilot seat opens exactly one intent, the dismount window and the seed
  it sends (C8 CREW-C1(a)). Whether a seated body is piloting is C9's (SHIPCTL-1), not this contract's.

**Out of this contract**: what a deck does to a body it holds (C30); whether a seated body steers (C9);
a crossing's choice of where its crew lands (C18).

## How it is built

- **A teleport is counted on the body, per side** `[V]`: `DeckHeld.stellurgy$teleports`, raised by
  `MixinNetHandlerServerTeleportCount` (every `setPlayerLocation` that puts the player somewhere the
  server does not already have him) and `MixinNetHandlerClientTeleportCount` (every applied
  position-look that puts him somewhere other than the previous one did). The two exclusions are
  vanilla's re-send of an unconfirmed teleport and its "moved too quickly" correction after a long one
  — both aimed at the point the last teleport gave him. *Measured 2026-10-07*: the declared hold's one
  teleport ten blocks up was followed, every run, by that correction (`moved too quickly! 3.0,12.1,3.0`),
  and counting it voided the hold a tick after it began.
- **Each intent remembers the count it began at** `[V]`: the dismount window
  (`EntityDummy.keepDismountedPilotOnDeck`, `dismountTeleports`), the pending seed
  (`PendingSeed.teleports`, plus the mount it was made on, `ridingAtInstall`), and a resolved declared
  hold (`DeckHold.Hold.teleportsAtPin`, taken after its own teleport). A different count ends it as VOID.
- **A pending seed ends once and absorbs its re-sends** `[V]`: an ended slot (`PendingSeed.ended`) stays
  while its sender keeps re-sending the same intent and goes after one TTL of silence; a new intent
  always arrives with a teleport of its own, so it never matches an old slot. Every end is reported
  through `DeckBoarding.noteSeedOutcome`; the window's through `EntityDummy.noteWindowEnded`.
- **The declared hold teleports once** `[V]` (`DeckHold.onPlayerTick`, `Hold.pinned`), and while the
  client's restore seed waits for its craft the client keeps the body still
  (`DeckBoarding.holdStillForRestore`, asked from `DeckFrameTick.updateLocalPlayerLiving`).

## Known violated

| clause | the distance to the design | where |
|---|---|---|
| HOLD-2 | an UNRESOLVED declared hold (a relog before its craft is found, a crossing's `pinInPlace`) is not voided by a teleport: it cannot tell a crossing's own transfer of the crew it pinned from a foreign one, and it does not teleport the body, so it fights nothing | `DeckHold.holdUnresolved` `[V]` |
| HOLD-4 | a declared hold's ends are logged for give-up, destruction and void, not for a capture or an excluded state | `DeckHold.onPlayerTick` `[V]` |
| HOLD-1 | owner edges are recorded for DECK (`deck_entered` / `deck_released`, test seams) and MOUNT (`mount` / `dismount`, test mixins); WORLD has no edge of its own (it is the absence of the other two), so an edge is complete only as a pair | `DeckFrameTick.noteEntered` / `noteReleased` `[V]` |

Already realised, and the evidence the clauses are buildable: a deck adopts a result write and
re-derives its frame from it (C30 ENT-4, pinned by `aTeleportAlongTheDeckIsWhereTheDeckHoldsHimFromThen`);
a deck releases a body teleported out of its region (C8 CREW-C4, `leftShipRegion`); a seed already
taken is not re-applied by a re-send (`Episode.seedAnchored`); the declared hold ends when the body is
held on its OWN craft, not any craft (`DeckHold.onPlayerTick`, the `aboardShipId` comparison).

## What pins it today

| clause | pinned by | tier |
|---|---|---|
| HOLD-2 (dismount window + pending seed) | `VSDeckCaptureAndDismountTest#aPilotTeleportedAwayRightAfterStandingUpIsNotPutBackOnHisDeck` | client |
| HOLD-2, HOLD-7 (the seed's law) | `DismountPendingSeedTest#aSeedWhoseBodyWasPutElsewhereOrMountedIsVoidAndNeverApplies` | unit |
| HOLD-3 (server: one teleport; client: one application) | `VSCrewRelogPersistenceTest#aDeclaredHoldPutsHimOnItsDeckPointByOneTeleport` | client |
| HOLD-6 (the restore seed keeps the body still) | the relog scenarios of `VSCrewRelogPersistenceTest` (they read his position across the return) | client |

Nothing pins HOLD-1, HOLD-5, a voided RESOLVED declared hold, or HOLD-6's first sentence directly (a
click during a hold).

## Rulings

- **2026-10-07**, on the draft that carried three questions with recommendations: *"Пиши контракт и делай
  фиксы от него"* (write the contract and make the fixes from it) — taken as the three recommendations:
  - **R1** HOLD-2 voids on EVERY foreign teleport;
  - **R2** the declared hold pins once and leaves the rest to the restore seed;
  - **R3** a mount is in this contract only as an owner (HOLD-7); "is he piloting" stays C9's.
