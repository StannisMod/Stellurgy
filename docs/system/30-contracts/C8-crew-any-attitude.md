---
id: C8
covers: any-attitude crew behaviour on a tier-2 (VS) ship — the deck's hold, frames, camera, interaction
owner-subsystem: integration-vs — the code is tier-2 VS integration in integration/vs + client + mixin; CREW-C17 is a ruled design, unbuilt
see-also: [C2 (wire — PacketDeckCapture ship id), C30 (what a deck does to any entity it holds), C31 (the hold lifecycle)]
confidence: high
---

# C8 — Any-attitude crew contract (tier-2 ships), clauses CREW-C1..C17

Ground: the maintainer ruling of 2026-07-14 ("full body-frame crew"); the outer-hull clause (C11) and
the deck-frame look (C6/C7) rest on rulings of 2026-07-15 and 2026-07-16. **In-code references of the
form "contract Cn" in the tier-2 crew context mean THESE clauses** (the short names are permanent
anchors). This contract is *behavioural* (player-visible semantics), unlike C1-C7 which pin data
surfaces.

**Terms.** **ship** = one VS `PhysicsObject`, identity = its `ShipData` UUID string.
**subspace** = the ship's shipyard region (blocks as-built, axis-aligned; regions of
distinct ships never overlap). **deck** = subspace block surfaces walkable as-built.
**ABOARD(e,S)** = a deck of S holds e: `DeckFrameTick` runs e's own update in S's frame, anchored
to S (`integration/vs/DeckFrameTick.java:128` holds, `:133` heldShipId; asked through
`DeckBoarding.isResolvingAboard:410` / `aboardShipId:438`). **The deck frame is the ONLY mechanism that
holds a body**: a body no deck holds — the outer hull included — is the physics substrate's. A ship
block's `BlockPos` is SUBSPACE, entities are WORLD-frame, and an aboard body's own math is SHIP-frame.

**Where the hold LIVES.** A body's deck episode (`DeckFrameTick.Episode`) and its hold stamp are
`@Unique` fields of the `Entity` OBJECT (`mixin/MixinEntityDeckHeld`, duck interface `DeckHeld`), so the
client's and the server's copies of one player in a JVM hold two slots by construction and a hold dies
with its object. Each opening stamps the episode with the body's next hold stamp
(`DeckFrameTick.open:783`), which a pending seed compares to tell a hold opened during its window from
one that predates it. The end-of-tick re-image walks `world.loadedEntityList`
(`DeckFrameTick.followShipPoses:99`), so a held body whose chunk is NOT loaded is not re-seated: it
reaches LOADED entities only. The pending dismount/boarding seed is per client world
(`DeckBoarding.pendingSeedsIn:207`), not process-wide. `[V]`

> **Open — the hold SEAMS are widened by a gravity mismatch if one exists.** A held body is immune by
> construction: the deck frame holds the ship-frame position as authoritative, so the deck is static in
> that frame and gravity is plain vanilla `0.08`. A body that is aboard while NOT in ship-frame
> semantics — the tick before the hold opens, the tick after it ends, every category the deck's admission
> refuses (water, lava, ladders, elytra, levitation, creative flight, passengers), and a body in the
> surrounding airspace — falls at the vanilla rate while its ship falls at the substrate's. The
> reference is vanilla: the substrate's gravity is -32 blocks/s² and its drag 0.98, so a ship and a
> player fall at the same acceleration and terminal velocity (vanilla's 0.08 blocks/tick² IS 9.81 m/s²,
> because a tick is 90.3 ms). A residual mismatch would be 0.0555 blocks of relative sink per tick of
> gap, so three ticks would clear a one-block deck.
> **Nothing here is WITNESSED yet.** An unpiloted ship at altitude does not fall (measured at y=156
> after a real Shift dismount: `shipDrop = 0.0` over 30 ticks), so the falling-deck state has no
> reachable arrangement on this build; the falling arm of `VSDeckCaptureAndDismountTest` gates on a
> sustained descent and skips itself.

## Clauses

- **CREW-C1 (boarding)** `[T]` A body becomes ABOARD only by (a) dismounting a linked
  pilot seat of S, or (b) first contact: feet supported by S's blocks in S's subspace
  (STANDING support — box top at/below feet: `DeckFrameTick.java:917`) AND NOT
  simultaneously supported by world terrain (`:889`), both in the deck's admission
  (`DeckFrameTick.admissionFor:870`). A body on world terrain never
  becomes ABOARD. Pinned: `VSCrewCaptureContractTest.walkingOnTheGroundBesideAParkedShipNeverEntersItsFrame`
  (its positive precondition is the deck frame's own admission decision, recorded
  per tick at `admissionFor`'s return).
  The (a) path DELIVERS the SEAT's deck point: the seed is a client-side pending
  boarding intent (`DeckBoarding.installPendingSeed:258`) that waits out the
  post-dismount riding tail, applies exactly once, supersedes a first-contact hold
  installed during its window (a vanilla-spot interim stand is a mis-boarding for a
  dismount), respects holds that predate it, expires without snapping when the
  exclusion is real (C4), and is void once the body is teleported or mounts something
  after it was made — and the server's dismount window closes on that teleport too (C31
  HOLD-2, HOLD-7). State machine pinned:
  `DismountPendingSeedTest`;
  end-to-end: the dismount e2es + `VSCrewInteriorBoardingTest` (stand = seat point,
  deterministic).
- **CREW-C2 (anchor)** `[T]` While ABOARD(S) every transform resolves through S — by
  ship id (the episode's `shipId`, `DeckBoarding.aboardShipId:438`), never re-picked
  from containment mid-episode; `PacketDeckCapture` carries the ship id (wire — C2 doc).
  Pinned: still-crew / fast-climb kinematics tests (same class).
- **CREW-C3 (stay)** `[T]` ABOARD persists inside S's subspace region grown by a margin
  (measured in SUBSPACE — attitude-invariant; `DeckFrameTick.java:543-548`). Jumping/
  falling above the deck does not release. Pinned: `jumpingOnTheTopDeckKeepsTheCaptureAndLandsBackOnIt`.
- **CREW-C4 (release)** `[T]` ABOARD(S) ends only on: S unloading; leaving the grown region; no deck
  below and no enclosure (`noDeckBelow`, `DeckBoarding.deckMayKeep:719`); entering an excluded state
  (riding, elytra, creative flight — only when NOT aboard/claimable
  (`DeckBoarding.flyCaptureEligible:68`) —, water/ladder, levitation — `excludedStateOf:43`). Release
  is EXPLICIT (`DeckFrameTick.release:827`, reason recorded). Pinned:
  `aCreativeFlyingExPilotIsNeverSnappedBackByTheDismountHold`.
  A body stepping onto world terrain is not released by this clause: the deck refuses terrain at
  admission only, and a held body over world ground is held by the keep test — consistent with
  CREW-C17's consequence 1.
- **CREW-C5 (gravity)** `[T]` ABOARD bodies accelerate toward subspace −Y at vanilla
  living magnitude at ANY attitude — their own vanilla update runs in the deck's frame
  (`DeckFrameTick.runInDeckFrame:493`); the dimension's gravity is not applied to them
  (`GravityHandler.java:91`, the deck-hold early return). Pinned:
  `VSShipFlightTelemetryTest.crewStaysOnASteeplyRolledDeck…` (75°).
  > **Design — the `[T]` above describes the code and stays true under `shipGravityByDefault = true`.**
  > With the config at its default `false`, an aboard body
  > accelerates by **`g_felt = (g_world − a_ship) + g_field`** in the ship frame: the world's field minus the
  > ship's own acceleration (landed → the planet's gravity; coasting → zero; thrusting → toward the stern),
  > plus the gravity device's body force (`C25` HYPER-33), which steers the sum to its group's setpoint along
  > the deck's −Y within its capacity and coverage. **No device ⇒ no deck gravity.** The direction and
  > magnitude both come from `g_felt`, through the deck frame's gravity input (not built); rotational
  > pseudo-forces are left out. `shipGravityByDefault = true` restores exactly the clause above — the
  > test harness sets it. *Stress-test*: falsifiable (a coasting ship with no device holds no one to its deck;
  > a landed one does; a thrusting one pushes its crew aft; with a 1 g device every case reads 1 g toward the
  > deck within capacity; with the config true the 75° pin passes unchanged); code: absent; consistent with
  > CREW-C16 (harm reads `|g_felt|`), HYPER-33, STAT-14; edge — a body with `|g_felt|` ≈ 0
  > needs zero-g locomotion, which does not exist yet.
- **CREW-C6 (walking + look)** `[T]` On the deck at any attitude: WASD walks the deck
  plane along the HELD deck heading, jump is deck-up, step assist works. The local
  player's look is STORED deck-frame (`client/DeckLook.java`); the mouse turns it
  directly (`MixinEntityDeckLookTurn`), the walk basis consumes it through the client port
  (`DeckBoarding.ClientLookSource.deckYaw`, asked at `DeckBoarding.java:911`, fallback world→deck
  mapping `:915-923`; the port is installed once from `ClientProxy.preinit`, so a `null` port means
  "dedicated server" and nothing else), and
  world `rotationYaw/Pitch` are DERIVED (`world = shipQuat·deckLook`) each tick+frame —
  the wire and the server keep world semantics unchanged. Pinned:
  `theMouseTurnsTheWalkingCrewsAimInTheDeckFrameAndTheAimRidesTheDeck` (real mouse +
  real key at 60→85° roll) + `DeckFrameLookTest` (4 unit pins on the composition math).
- **CREW-C7 (camera)** `[T]` First person while ABOARD: the eye sits along the SHIP's up
  (`MixinEntityRendererShipEye.java:35-38`), and the view is the composition
  `shipQuat ∘ deckLookQuat` (`event/RocketEventHandler.java` crew branch;
  `FreeFlightPhysics.lookQuat`) — no singular attitude; camera == derived aim by
  construction. Eye/model/camera gate on the MOVEMENT truth (`isResolvingAboard`),
  never containment. Pinned: same e2e (camera-vs-aim leg) + telemetry camera tests.
- **CREW-C8 (no suffocation)** `[A]` An ABOARD body standing on a deck open in the ship
  frame takes no suffocation damage and no inside-block overlay at any attitude (its
  world capsule may legally intersect hull blocks). NOT yet verified/pinned.
- **CREW-C9 (bystanders)** `[T]` A body on world terrain near/under/inside any ship's
  world AABB keeps world-frame gravity, movement and camera untouched. Pinned:
  `walkingOnTheGround…` + `flyingIntoAShipsAirspaceWithoutStandingOnItDoesNotHijackTheCamera`.
- **CREW-C10 (interaction)** `[T]` The block outlined under the crosshair is the block
  interacted with: the raytrace originates from the SAME eye the camera renders. Pinned:
  `theCrosshairPicksTheSameDeckBlockAtAnyAttitude`. Which code gives a deck-held body the property
  is not named here: not read.
- **CREW-C11 (outer hull)** `[T]` The OUTER hull is walkable at ANY attitude with
  WORLD-frame semantics: no deck holds a body there — in S's frame that face has no floor beneath
  it, so the admission's standing probe never takes it (`DeckFrameTick.java:917`) and the keep test
  lets a held body go there (`DeckBoarding.deckMayKeep:719`) — so its gravity, movement and camera
  are the world's, and the PHYSICS SUBSTRATE collides it with S's geometry
  (`valkyrienskies/…/EntityCollisionInjector.java:158`, `:167`): it stands on the hull as on terrain,
  rides the moving craft, and does not tunnel. Stand-vs-slide is the substrate's own limit: a face
  within ~40.4° of level holds, a steeper one sheds the body (`VSMath.STANDING_TOLERANCE = 0.42` =
  sin² of the limit, `valkyrienskies/…/VSMath.java:20`, read by `canStandOnNormal`). Ruled 2026-10-07:
  *"давай вернем допуск на старый VS-овский. Это чуть более логично, да и разница небольшая"* (let us
  return the tolerance to the old VS one; it is slightly more logical, and the difference is small).
  Pinned (client):
  `VSCrewCaptureContractTest.aPlayerOnTheOuterHullKeepsWorldFrameSemantics` (no deck takes him, the
  deck camera never engages) and the outer-hull scenarios beside it — still (`…StaysWhereHeLanded`),
  flying (`…RidesWithIt`), spinning, a 38° face (`aPlayerOnASteepOuterHullFace…`), a 20-block fall
  (`aPlayerFallingFastOntoTheOuterHullLandsOnIt`), walking (`aPlayerWalkingOnTheOuterHullStaysOnIt`);
  (server) `VSLivingBodyOnADeckTest.anArmorStandOnTheOuterHull…` (still, flying). No pin
  remains for "contacts happen where the rendered surface is" on the outer hull: under the substrate
  that is VS's collision against its game-tick pose, and no instrument records it.
- **CREW-C12 (interior boarding — maintainer ruling 2026-07-16, enclosure term ruled
  2026-07-18)** `[T]` A body INSIDE a
  ship's subspace block region **that is ENCLOSED — ship blocks overhead in the SHIP
  frame (`DeckBoarding.hasRoofAboveFor:779`) —** and has a deck below it IN THE SHIP FRAME (within a
  bounded reach — `hasDeckBelowFor:709`; the whole test is `DeckBoarding.interiorCandidate:750`,
  asked by the deck's admission at `DeckFrameTick.java:928`) becomes ABOARD by first contact even without
  standing support: deck gravity (C5) then carries it to the deck, where it stands at
  any attitude (inverted ship: stands on the world ceiling, camera flipped per C7). The
  enclosure term is a ruled RESTRICTION: the margin-0 block-bounds
  region over-covers (it contains the OPEN air between a deck and the ship's topmost
  blocks), and "region + deck below" alone would hijack a body merely flying through that
  airspace — violating C9's pin (`flyingIntoAShipsAirspaceWithoutStandingOnItDoesNotHijackTheCamera`).
  A roofed body past the deck probe's reach is likewise
  never released by `noDeckBelow` (interior-stay, `DeckBoarding.deckMayKeep:719`). The outer hull
  (C11) is a contact with NO ship-frame deck below; an enclosed body with a deck below is
  C12's. Pinned (admission site `DeckFrameTick.java:928`): the released-interior
  re-seat (`VSCrewInteriorBoardingTest.aBodyReleasedInsideAnInvertedShipIsSeatedBackOnTheDeck`)
  + the POSITIVE roofed-cavity hold —
  `aBodyLostMidCavityOfAnEnclosedInvertedShipIsReclaimedByTheDeck` on the
  `with-roofed-deck` fixture: a body displaced UNSUPPORTED mid-cavity of the 170° ship
  (off the deck past the support probe, under the roof) is claimed ABOARD by the
  interior gate — never the outer-hull fallback — and deck gravity carries it back
  AGAINST world gravity to its deck stand with the ship camera on.
- **CREW-C13 (flying-aboard — maintainer ruling 2026-07-16, variant A)** `[T]`
  A creative-flying body meeting C12's interior condition (or
  standing-contact on a deck) is held ABOARD while STILL FLYING: its flight
  kinematics resolve in the DECK frame (thrust on deck axes via the deck yaw, the
  ±flySpeed·3 vertical intent along the DECK normal, vanilla's own 0.6 vertical / 0.91
  lateral drags on their deck axes, NO gravity while flying), and its look/camera are deck-frame
  (C6/C7) — one transform for input, aim and view, airborne included. Flight-off while
  aboard hands over to C5/C6 seamlessly. The C4 flight exclusion is conditional: creative flight is
  excluded only for a body that is neither ABOARD nor claimable where it is
  (`DeckBoarding.flyCaptureEligible:68` — standing contact | enclosed interior, never on world
  terrain); a flyer who LEAVES the stay region is released mid-flight and never yanked
  back (C4's region-exit release, unchanged).
  The clause is met by the DECK FRAME `[V]`: `DeckFrameTick` keeps a flyer in the same episode
  (`admissible` does not refuse flight; a flying held body is let go only by leaving the stay region,
  `DeckFrameTick.isFlying:483`), and runs his UNMODIFIED vanilla update in the deck's frame — where
  subspace +Y is the deck normal, so vanilla's own creative flight IS variant A. Flight over the outer
  hull is world flight. No partial hold-without-deck-kinematics state exists (the forbidden split).
  Pinned: `VSCrewInteriorBoardingTest.aFlyingCrewMemberAscendsAlongTheDeckNormalAndReseatsOnFlightOff`
  (60° roll: hold+camera kept while flying, ascend/descend along subspace ±Y with
  dxz=0, flight-off reseats) + `aCreativeFlyingExPilotIsNeverSnappedBackByTheDismountHold` (no
  yank-down; region exit releases).
- **CREW-C14 (persistence)** `[T]` ABOARD survives a relog: a player who logs out
  ABOARD(S) logs back in ABOARD(S) at the same subspace point, at any ship attitude —
  never handed to world gravity while the hold re-seeds. The anchor is the durable aboard
  record (`space/AboardRecord`, read through `DeckBoarding.aboardShipId` /
  `aboardShipFramePoint:126`); `DeckHold` (armed by `onPlayerLoggedIn`) teleports the returning body
  onto its deck point ONCE and re-sends `PacketDeckCapture` (200-tick window) until the owning
  client seeds, while the client's pending restore seed keeps the body still; ends on hold /
  excluded state / a teleport after its own / timeout (clean vanilla handover) — the lifecycle is
  C31's (HOLD-2, HOLD-3, HOLD-6). Pinned:
  `VSCrewRelogPersistenceTest.aPlayerWhoRelogsOnAnInvertedDeckStaysAboardIt` (real
  FTF relog, 170° deck, dY=0.03).
  **Scope limit: this clause is `[T]` for a SAME-DIMENSION relog only.**
  Across a server restart in a space cell it does not hold, and the anchor it relies on cannot
  make it hold — the anchor is keyed by the VS ship id and stores a raw subspace point, both of
  which a re-assembly invalidates, and it is read after vanilla has already chosen the world.
  That case is owned by **C13 (space presence)**, clauses PRES-2/3/5. Do not extend this clause to
  cover it — the cross-dimension case is Layer 1 and belongs there, not in the ship-frame contract.
- **CREW-C15 (the seat is current)** `[T]` An ABOARD body stands at the image of its held deck
  point under S's pose **as S holds it now** — never under the pose S held a tick ago. The
  substrate advances every ship's pose at tick phase END, i.e. AFTER the world's entities have
  moved, on both sides (`EventsCommon.onWorldTickEvent`, `EventsClient.onClientTick`), so the
  derivation a body performs during its own movement tick is against the previous pose and is
  redone once the poses are current: `DeckFrameTick.followShipPoses:99`, registered at
  `EventPriority.LOWEST` so it lands after the substrate's own END handler
  (`DeckFollowsItsShip` on the world tick; `ClientDeckFollowsItsShip` on the client tick, which
  is where the client ticks its ships — Forge fires no world tick event there). Excluded: a
  player this side does not play (a real player's position is decided on his own client), and a
  rider, whose vehicle owns its position.
  **The quantity, measured 2026-08-25**: without this, a body stands one full tick of ship
  motion off its deck spot, every tick, for as long as the craft moves — 0.1974 blocks at
  2 rad/s for a body 1.974 blocks off the roll axis, and the same angle through a longer arm
  on a wider craft. It does not accumulate (the deck point is re-imaged each tick) and no carry
  can remove it: a body's position never comes from a velocity. Pinned:
  `VSCrewRelogPersistenceTest.aCrewMemberStandingOnADeckIsNotReleasedWhileTheShipRolls`
  (seat miss 0.001 against a 0.02 bar, with the deck stepping 0.197 a tick as the arrangement
  witness).
- **CREW-C16 (acceleration hurts)** `[A]` A body aboard takes damage when the craft's **proper
  acceleration** — thrust and drag over mass, so a ship hovering on Earth reads 1 g and one in free fall
  reads 0 — stays above a tolerance for long enough (what is read is the
  body's FELT acceleration `|g_felt|` from CREW-C5's design equation, so the device that holds a crew to its
  deck and the dampener that spares it are one number); the tolerance depends on **magnitude, duration and
  posture** (seated bears more than standing), and built dampeners RAISE it (dampener physics is in C25).
  This is the real ceiling the arcade caps stood in for (C10 STAT-1): a craft pulls what its drives give,
  and the crew is what fails. A jump collapse is NOT this clause — it is `DampenerField`'s (`C25` HYPER-23).
  *Stress-test.* Falsifiable: above the threshold for longer than the duration, an unseated, undampened
  body loses health and a seated one later; below it, nothing. Code: absent — no acceleration term harms
  a body anywhere. Consistent with CREW-C1..C15 (the deck's hold keeps a body at any attitude; this clause
  prices what holding it costs) and with C10's rider (the control system argues against high g and
  never refuses). Edge: creative and spectator players are exempt as for every other damage source.

- **CREW-C17 (a deck holds where its artificial gravity reaches — RULED 2026-10-03, design, unbuilt)** `[A]`
  The rulings, in order, verbatim. First a definition, then its refinement, which supersedes it:
  *"Если в BB корабля - на корабле, иначе - не на корабле..."*; *"BB, а не AABB. OBB у нас вроде
  существуют"*; *"И захват тоже. Ступни, да"* — then: *"в идеале тело должно захватываться только в
  случае попадания в область работы искусственной гравитации корабля. В дизайне такое есть. [...] пока
  для тестов мы будем использовать "всегда активную" искусственную гравитацию, область которой будем
  приближать как SDF корабля, довольно грубый. Вот где поле есть - там захват. Где нет - захвата нет."*
  (Ideally a body is captured only where the craft's artificial gravity works — the design has it; for
  now, for tests, the gravity is "always on" and its region is a coarse SDF of the craft. Where the
  field is, capture; where it is not, none.) The radius: *"1 - ок"* to R = 2 blocks. The same day, on
  falling through the world: *"А в чем проблема добавить телу коллизию и с миром тоже? И то только в
  случае, если известно, что сам корабль (его AABB) в коллизии с миром"*, refined to the per-body gate
  by *"по телу, хорошо"*. This is the SPATIAL half of the lore ruling of 2026-09-30 that the deck's
  hold IS the craft's artificial gravity (C30 ENT-1), and the world-collision paragraph is folded into
  C30 ENT-2.
  **FIELD(S)** — the region where S's artificial gravity acts. *Design*: the gravity device's coverage
  (`C25` HYPER-33). *Interim, until a device exists*: the field is always on and
  FIELD(S) = { p : d_S(p) ≤ R }, where d_S(p) is the distance, measured in S's frame
  (`VSIntegration.toShipFrameFor`), from p to the nearest block of S — a coarse SDF over S's block set —
  and **R = 2.0 blocks**: enough to cover a body jumping on a deck (apex ~1.25) and a narrow
  compartment; it reaches world ground beside a hull only within two blocks of it.
  **HELD(e,S) may begin only while e's FEET** (`posX, posY, posZ`) **are in FIELD(S)**, and every
  consumer asks this one predicate:
  - **C1 (b) first contact** becomes: feet in FIELD(S). The standing-support term and "a body on world
    terrain never becomes ABOARD" are retired.
  - **C9** is retired as worded: a body whose feet are in no ship's field keeps world semantics; one in a
    field is held, terrain or not.
  - **C12**'s roof and deck-below terms and **C13**'s eligibility (`flyCaptureEligible`) become the same
    predicate.
  - **C7, remote bodies**: a model is drawn ship-aligned iff its feet are in FIELD(S) as the client sees
    S — replacing the support-memory gate (`ShipFrameCamera.recentlySupportingShipId`) and closing the
    open question below. Client side must hold what d_S needs; whether `ShipData.blockPositions`
    reaches the client is NOT yet established.
  **World collision while held**: a held body's displacement is resolved against
  S's blocks AND, whenever the body's own world box swept by the displacement meets any world collision
  box (asked per body, the query vanilla already makes for every entity each tick), against those world
  boxes too. Seen from S's frame a world block is an oriented box, so this is a swept-SAT of a box
  against oriented boxes (world-upright body against S-oriented boxes, the rotation inverted); the step
  is the shorter of the two sweeps. A world face is stood on or slid down by the unit-friction 45° rule
  against the deck's gravity; a world block that the craft's own motion carries INTO a resting body is
  resolved by depenetration, which a sweep of the body's own motion cannot see. Without this a body held
  over world ground with no deck of S beneath it in S's frame has nothing to stand on: the deck frame's
  own `move` in subspace asks `world.getCollisionBoxes` at the body's SUBSPACE coordinates — the
  shipyard — and never at its real position.
  *Stress-test — consequences derived, not ruled:*
  1. **C4's "stepping onto world terrain" release** would contradict the predicate (a body on terrain in
     the field is held again the same tick) — it does not exist; release is on leaving the C3 region and
     the other C4 causes. Whether C3's region (block box + 4.0) should become "the field, grown" is open.
  2. **A body on the ground within R of an inverted hull is held, and deck gravity pulls it toward
     subspace −Y — world UP for a ship rolled past 90°.** With world collision it is held against the
     ground no more; it falls UP to the deck. A cow under a 159° `with-pilot-deck` is such a
     body. Accepted by the rule as stated, or the field needs a direction.
  3. **The airspace hijack C12 was written against returns within R of a block**, and is gone beyond it.
  4. **Outer hull ↔ held at a hatch** (C11): a body standing on the outer hull has its feet within R of
     the hull, so it is IN the field. C11's "outer hull keeps world semantics" contradicts the predicate
     unless the outer hull is carved out of FIELD(S) — e.g. the field is on the deck side only — and that
     is a question about the field's SHAPE, which the coarse SDF does not answer.
  Falsifiable: feet at d_S = 1.9 → held and drawn ship-aligned; 2.1 → neither; a held body over world
  ground with no deck beneath it in S's frame stands on the ground. Code: absent — first contact is
  support-based (`DeckFrameTick.java:889`, `:917`); no SDF exists (`FieldSurfaceMath.
  compositeHullDistance` is a union of spheres around shield emitters, not a hull); the held sweep is
  subspace-only.

## Beyond crew — a deck holds items, and every living body

The clauses above are written about a living body. The same mechanism holds items and every other living
body, players included: `DeckFrameTick` runs the body's own unmodified
update in the deck's frame (`MixinWorldDeckFrameTick` redirects the `onUpdate` call in
`World.updateEntityWithOptionalForce`) and maps the result back; the episode lives on the entity
(`MixinEntityDeckHeld`), a `setPosition` from anyone else is adopted rather than re-derived, and the
held point is re-imaged through the craft's current pose at tick end (`DeckFollowsItsShip`).

- **Who** `[V]` `DeckFrameTick.admissible:753`: an `EntityItem` not in water or lava, and any
  `EntityLivingBase` that is not riding or ridden — on the SERVER (a client moves
  a mob by interpolating toward the server's world positions; run in the deck's frame it walked a cow
  off the screen), and on a client the one player it plays — and in none of CREW-C4's
  excluded states — asked through `DeckBoarding.isExcludedFromCapture:34`, the one predicate the seeds
  ask too. The same predicate is asked on admission and on every held update (a held body that
  enters one is released).
- **The player, on the SERVER** `[T]`: his living update (from
  `NetHandlerPlayServer.update`, not the world tick) runs in the deck frame (`DeckFrameTick.updatePlayer:221`)
  from the deck point his client's last packet DECLARED — taken onto the deck as the packet is handled
  (`DeckFrameTick.claimArrived:338`, C23 DECKSYNC-7), held between packets, and re-derived from his world
  position only after somebody else has written it (the network handler's own write of him back
  after his update is the deck's bookkeeping, `DeckFrameTick.restoreAfterUpdate:317` — a write cannot be
  told by its VALUE, since vanilla's teleports assign the position fields first and then write those
  same values); his `move(PLAYER)` replay collides
  against the deck's blocks (`DeckFrameTick.moveHeld:367`) with his velocity rotated into the deck's frame
  for the move and both ends of the step cleared of the transform's noise; a flyer's too. That
  hook (`MixinEntityShipLocalMove`, priority 0) must run BEFORE the physics mod's own `move` HEAD
  (`MixinEntityIntrinsic`, priority 1) `[T]` — a lower priority is applied first: run second, the
  physics mod pushes a held player's step out of the hull's world polygons, re-enters `move`, and writes
  that step into his velocity. **And on his own CLIENT** `[T]`: the living update of the player the
  client plays runs in the deck frame (`MixinEntityLivingDeckFrame` redirects `onLivingUpdate` inside
  `EntityLivingBase.onUpdate`, `DeckFrameTick.updateLocalPlayerLiving:447`), so the position his client
  then SENDS is already mapped back to the world; he is re-imaged at tick end with the rest
  (`ClientDeckFollowsItsShip` → `DeckFrameTick.followShipPoses`, local player only). A client
  census of him standing reads `heldBy=deckFrame`. A flyer stays in the episode on both sides (CREW-C13
  above), and the deck frame also admits CREW-C12's enclosed interior with nothing under the feet
  (`DeckFrameTick.admissionFor` → `DeckBoarding.interiorCandidate`).
  A server-sent deck point (CREW-C1's seed) opens the DECK FRAME's hold for him
  (`DeckFrameTick.holdSeeded:801`), and a re-sent copy is recognised under that hold (`Episode.seedAnchored`),
  so a re-send never puts him back on his dismount point. A seed the deck frame refuses is SPENT
  (`DeckBoarding.noteSeedOutcome` `refused-by-deck`): there is no second holder to fall back on.
- **No second holder** `[V]`: a player on the outer hull is moved by the world pipeline like any
  body no deck holds: his `move(PLAYER)` replay is vanilla's, under the physics mod's widened "moved
  wrongly" bound (`valkyrienskies/…/MixinNetHandlerPlayServer.java:119-128`), and vanilla's "moved too
  quickly" check judges him (`DeckMovementBound` stands it down only for a body a deck holds).
- **One holder, asked in one place** `[V]`: `DeckBoarding.aboardShipId:438` /
  `aboardShipFramePoint:126` / `isResolvingAboard:410` answer from the deck frame, so the durable
  aboard record, the deck hold, the movement bound, the hyperspace void and the crossings read the one
  mechanism that holds a body. A declared hold (`DeckHold`) outranks a spatial pick in the deck frame's
  admission — and boards by the declaration: the declared craft is admitted
  by the KEEP test (`DeckBoarding.deckMayKeep:719`: a deck below within 6.0, or its enclosure), not by
  the 0.30 standing probe that takes a body arriving on its own (a crossing pins a crew member 0.5 over
  his deck, and the probe would refuse him every tick the hold re-teleports him).
- **A held body off the deck's edge or on the outer hull is let go** `[V]`
  (`DeckBoarding.deckMayKeep:719`, asked at `DeckFrameTick.java:549`): it is the world's, collided by
  the substrate.
- **Velocity is held in the deck's frame** `[T]`: what the world adds between two updates is the
  difference from the world motion the deck wrote, taken in and added (an impulse survives: a 0.3
  push carried an item 0.7282 against a predicted 0.728). Crossing the whole world motion through the
  pose each tick instead turns a resting body's gravity residue into a slide during a turn: measured
  0.41 blocks for an armor stand slewing to 150°, 7.5e-9 held.
- **Deck gravity for a held body is the craft's, not the dimension's** `[T]` `GravityHandler.apply`:
  vanilla's own per-class pull, which in the deck's frame points deck-down (CREW-C5's ~1G for the
  living, 0.04 for an item), so an item in a world without gravity does not hang above its deck.
- **A mob plans in the deck's frame** `[T]`: its pathfinder sees the deck where its blocks are; a
  panicking cow ran 1.2 blocks about a 60° deck and stayed on it.
- **Environment (C30 ENT-9, ruled)**: a held body's ENCLOSURE facts are its shipyard point's
  (a sealed cabin reaches its crew `[T]`, `VSShipAtmosphereFrameSpikeTest`), its WORLD facts its world
  point's — built by the frame swap for the atmosphere, and for acid rain by
  `AcidRainHandler#isExposedToAcidRain` (`DeckFrameTick#worldPositionOf`) `[V]`.
- **Not yet read**: entity queries across the two frames; on the client, the rider.

Its contracts are **C30** (`ENT-1..9`, what a deck does to any body it holds) and **C31**
(`HOLD-1..7`, who owns a body's position when several mechanisms want it). Measured `[T]` by
`test/server/VSItemOnADeckTest` and `test/server/VSLivingBodyOnADeckTest`.

## Open questions

- CREW-C8 is unimplemented/unverified — promote to `[T]` when pinned.
- Remote-crew model rotation still gates on containment until CREW-C17's spatial gate exists
  (`client/ShipFrameCamera.java` comment) — a deliberate, documented exception to
  CREW-C7's gate rule for non-local bodies.
