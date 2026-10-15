---
id: C30
covers: what holding an entity on a craft's deck means for EVERY entity, not only the crew — who may be held, that every displacement of a held body is collided once in its mode's frame, that its own kinematics act on the deck's axes, that writes and impulses are adopted, how a foreign push is framed, that one mechanism moves a body per tick, that its seat follows the craft, and in which frame its environment is read
confidence: DESIGN-STAGE — ENT-3/4/5 pinned for the admitted classes; ENT-1/2/6/7/8/9 partly or wholly violated, listed below
owner-subsystem: integration-vs — the deck frame is `integration/vs/DeckFrameTick`, its admission predicates and seeds `integration/vs/DeckBoarding`
see-also: [C8 (crew at any attitude — the LIVING-body clauses this generalises; CREW-C17 the field), C23 (deck motion across the wire), C31 (the hold lifecycle), C11 (physics substrate port), C19 (reference frames)]
---

# C30 — An entity on a deck, clauses ENT-1..ENT-9

C8 says what a LIVING body aboard experiences; this contract says what the DECK does to any entity it
holds, and who may displace one. **The code is a snapshot; this document is the authority**
(methodology §7b).

## Terms

- **craft S**, **subspace**, **deck** — as C8.
- **HELD(e, S)** — S owns e's movement: this tick e's movement is resolved in S's frame and nowhere
  else. It has ONE mode, **ABOARD** — the deck frame (`DeckFrameTick`) runs e's own unmodified update
  in subspace. A body on S's outer, world-facing surface is NOT held: it is the substrate's (ENT-7, C8
  CREW-C11).
- **Own movement** — the entity moving itself: `MoverType.SELF`, and `PLAYER` (the server replaying
  what the player's own client already resolved).
- **Foreign push** — the world displacing the entity with a direction: `PISTON`, `SHULKER`,
  `SHULKER_BOX`.
- **Result write** — a position with no direction: `setPosition` and every teleport.
- **Impulse** — a velocity written into the entity: knockback, explosion, `addVelocity`.
- **Carry / held carry** — as C23 and the subspace/world-frame rule (a ship block's `BlockPos` is
  SUBSPACE, entities are WORLD-frame, an aboard body's own math is SHIP-frame).
- **The substrate (M4)** — the physics mod's own world-frame collision and entity drag, which moves
  every entity no deck holds.

## Clauses

- **ENT-1 (population) — who may be held is decided by STATE, never by CLASS.** Any `Entity` may be
  HELD; C8 CREW-C4's excluded states exclude for every class they apply to. Its SPATIAL half is C8
  CREW-C17: a body may be held only while its FEET are in S's artificial-gravity field (interim: the
  field is always on and is a coarse SDF of the hull, R = 2 blocks). The deck's hold IS the craft's
  artificial gravity (lore ruling 2026-09-30), so a gravity switch, when it exists, ends HELD for every
  body on that craft. *Out of this clause*: projectiles, which never call `move` and advance by their
  own raytrace (`EntityArrow:297`, `EntityThrowable:269`, `EntityFireball:107`); they stay the
  substrate's, and their interaction across the hull is out of this contract.
  **Open**: which states exclude a NON-living body (an item in water today is released). No default
  is assumed in code; the class gate is not widened until it is ruled.

- **ENT-2 (one resolution per mode) — while HELD, every displacement that carries a direction, own
  movement or foreign push, is resolved by the one collision of the body's MODE, in S's frame.**
  ABOARD: vanilla `move` run in subspace against S's axis-aligned blocks. No displacement of a HELD
  entity is ever applied without collision. **World collision (C8 CREW-C17's amendment, folded in)**:
  the displacement is also resolved against the WORLD's collision boxes wherever the body's own swept
  world box meets them, asked per body; seen from S a world block is an oriented box, so this is a
  swept SAT of a box against oriented boxes, and a world block the craft's own motion carries into a
  resting body is resolved by depenetration.

- **ENT-3 (kinematics on deck axes) — while HELD ABOARD, the entity's own kinematics — gravity, drag,
  input basis — act along S's axes with the magnitudes vanilla gives THAT CLASS** (an item falls toward
  the deck at 0.04, a living body at 0.08). Deck gravity is the craft's, never the dimension's.
  Generalises CREW-C5/C6. A body on the outer hull is not held and keeps world kinematics (C8
  CREW-C11).

- **ENT-4 (results are adopted) — a result write sets the position; the deck re-derives its frame from
  it and never compares it against a held copy.** A position mapped in from the world is lifted out of
  any block it overlaps by no more than the transform's own error (8 ulps of the largest coordinate,
  `DeckFrameTick.MAPPING_NOISE_ULPS`); a deeper overlap is the writer's.

- **ENT-5 (impulses survive) — an impulse is a world-frame vector and the held-carry subtraction
  preserves it exactly.** Velocity is held in the deck's frame between updates, and what the world adds
  is the difference from the motion the deck itself wrote — added, never arbitrated.

- **ENT-6 (a foreign push is expressed in the PUSHER's frame)** — ruled 2026-09-29. A pusher that is
  part of or HELD by craft S pushes along S's deck coordinates; a pusher in the world pushes a
  world-frame displacement, projected into S's frame and collided there, so the deck stops it; a pusher
  on another craft T is mapped from T's frame into S's. Either way the result goes through ENT-2's one
  collision. `MoverType` names the KIND of pusher, never where it stands, so the frame is recovered at
  the seam where the pusher calls `move`.

- **ENT-7 (no split) — an entity is moved by exactly one mechanism per tick.** An entity no deck holds
  is the substrate's until it is admitted; nothing is resolved by the deck AND by the substrate in the
  same tick. **One gravity source per body** (ruled 2026-10-06): while a deck holds a body, an Area
  Gravity Controller's field skips it. **The outer hull is the substrate's**: a body
  standing on S's world-facing surface is no deck's; the substrate collides it with S
  (`EntityCollisionInjector`), and it stands on a face within ~40.4° of level and slides off a steeper
  one (`VSMath.STANDING_TOLERANCE = 0.42`, the substrate's own limit).

- **ENT-8 (the seat is current, for every held entity) — every HELD entity is re-imaged through S's
  current pose at the end of each tick**, so it stands where its deck is and not where the deck was a
  tick ago (CREW-C15 for every class). This includes an entity that another RIDES: a HELD mount is
  re-imaged like any held body, and vanilla's rider glue positions its rider.

- **ENT-9 (environment is read in both frames, each for its own fact)** — ruled 2026-09-30. Whether a
  WORLD fact holds (weather, biome, the dimension's own atmosphere, sky light) is read at the body's
  world point; whether S ENCLOSES the body (under its roof, inside a sealed room) is read at its
  shipyard point. Neither frame alone is right.

**Out of this contract**: interaction ACROSS the hull — an arrow from outside hitting a mob inside, and
the reverse (a raytrace through subspace both ways).

## Known violated

| clause | what the code does instead | where |
|---|---|---|
| ENT-1 | admission by CLASS: `EntityItem` and `EntityLivingBase` only — a minecart, boat, XP orb, TNT or falling block is never held. The field (CREW-C17) is absent: admission is a 0.30 standing probe, an enclosed interior, or a declared craft | `DeckFrameTick.admissible:753-770`, `admissionFor:870-929` `[V]` |
| ENT-2 | foreign pushes are not replayed: `moveHeld` takes `PLAYER` only, so `PISTON` / `SHULKER` / `SHULKER_BOX` go through the world pipeline; no world collision for a held body (the subspace `move` sees shipyard blocks only) | `DeckFrameTick.moveHeld:367`; vanilla callers `TileEntityPiston:202,297`, `EntityShulker:386`, `TileEntityShulkerBox:191` `[V]` |
| ENT-6 | unimplemented — see ENT-2; a ship-mounted piston's subspace-facing delta is applied as a world translation | as above `[V]` |
| ENT-7 | the gravity controller skips no body: its field acts on every entity in its radius, a deck-held body included | `TileAreaGravityController.java:212`, the `getEntitiesWithinAABB` loop `[V]` |
| ENT-8 | a ridden mount is never held: admission refuses `isBeingRidden()` | `DeckFrameTick.admissible:754` `[V]` |
| ENT-9 | atmosphere both halves `[T]`; acid rain reads both points (`AcidRainHandler#isExposedToAcidRain`, `DeckFrameTick#worldPositionOf`) `[V]` with no e2e; biome, sky light and weather handlers not enumerated | — |

Not measured either way: the substrate's `move` HEAD (priority 1) still runs inside the deck frame's
swap for a held body's own `move`; no reading shows it acting on the result (along ≤ 1.9e-8),
none shows it inert (ENT-7).

## What pins it today

| clause | pinned by | tier |
|---|---|---|
| ENT-2 (player, ABOARD) | `VSCrewRidesRollingDeckTest` — the server's verdict over every replay of the roll | client |
| ENT-7 (the outer hull is the substrate's) | `VSCrewCaptureContractTest#aPlayerOnTheOuterHull…` (still, flying, spinning, keeps world semantics), `#aPlayerOnASteepOuterHullFace…` (38°), `#aPlayerFallingFastOntoTheOuterHullLandsOnIt`, `#aPlayerWalkingOnTheOuterHullStaysOnIt`; `VSLivingBodyOnADeckTest#anArmorStandOnTheOuterHull…` (still, flying) | client; server |
| ENT-3 | `VSItemOnADeckTest`, `VSLivingBodyOnADeckTest` — item, armor stand, cow; rolls, slews, gravity 0 | server |
| ENT-4 | `VSItemOnADeckTest#anItemPlacedElsewhereOnTheDeckStaysWhereItWasPut`; `VSCrewCaptureContractTest#aTeleportAlongTheDeckIsWhereTheDeckHoldsHimFromThen` | server; client |
| ENT-5 | `VSItemOnADeckTest#aPushMovesAnItemAlongTheDeckAndFrictionStopsIt` (0.7282 against a predicted 0.728) | server |
| ENT-7 | client and server census `heldBy=deckFrame` in `VSCrewInteriorBoardingTest` (printed, not asserted) | client |
| ENT-8 | the roll scenarios of `VSItemOnADeckTest` / `VSLivingBodyOnADeckTest`; the rider, `VSFlightSmoothnessAcrossJumpTest` (C23 DECKSYNC-5) | server; client |
| ENT-9 | `VSShipAtmosphereFrameSpikeTest#aSealedShipCabinReachesItsOwnCrew` (enclosure half) | client |

Nothing pins ENT-1 beyond the admitted classes, ENT-6, or the gravity-source half of ENT-7.

## Rulings

- **R1** (2026-09-29) the deck ticks a held body in its own frame, after a spike.
- **R2** (2026-09-29) foreign pushes in the pusher's frame — ENT-6.
- **R3** (2026-10-07) the outer hull is the substrate's for every class (ENT-7): the substrate held an item,
  an armor stand and the player on the outer hull of an inverted craft — still, flying, spinning, after a
  20-block fall, walking. Its stand-or-slide limit is its own (`VSMath.STANDING_TOLERANCE = 0.42`, ~40.4°),
  by the ruling *"давай вернем допуск на старый VS-овский. Это чуть более логично, да и разница
  небольшая"*; a 43° face sheds a body.
- **R4** (2026-09-29) excluded states of a non-living body — OPEN.
- **2026-10-06**: ENT-2 is per mode with CREW-C17's world collision; the held mount is a known violation
  (ENT-8); one gravity source per body (the controller skips a held body).
