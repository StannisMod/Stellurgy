---
id: C8
covers: any-attitude crew behaviour on a tier-2 (VS) ship — capture, frames, camera, interaction
confidence: high
owner-subsystem: none — the code is tier-2 VS integration in integration/vs + client + mixin; CREW-C17 is a ruled design, unbuilt
see-also: [C2 (wire — PacketDeckCapture ship id)]
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
**ABOARD(e,S)** = `ShipFrameTravel` resolves e's movement in S's frame, anchored to S
(`integration/vs/ShipFrameTravel.java`: `handles`, `isResolving`, `isResolvingAboard`). A ship block's `BlockPos` is SUBSPACE, entities are WORLD-frame, and an aboard
body's own math is SHIP-frame.

**Where the capture LIVES.** A body's capture (`ShipFrameState`) and its install stamp
(the per-body capture epoch) are `@Unique` fields of the `Entity` OBJECT, added by
`mixin/MixinEntityShipFrameCapture` through the duck interface `ShipFrameBody` and read/written only
by `ShipFrameTravel.stateOf` (`ShipFrameTravel.java`) — there is no weak map, so the client's and
the server's copies of one player in a JVM hold two slots by construction and a capture dies with its
object. Consequence: the whole-ship passes `carryHeldBodies` and `followShipPoses`
(`ShipFrameTravel.java`) walk `world.loadedEntityList`, so a captured body whose chunk is
NOT loaded is not carried or re-seated by them: they reach LOADED entities only. The pending
dismount/boarding seed is per client world (`pendingSeedsIn(world)`, `ShipFrameTravel.java`),
not process-wide. `[V]`

> **Open — the capture SEAMS are widened by a gravity mismatch if one exists.** A captured body is
> immune by construction: `ShipFrameTravel` holds the ship-frame position as authoritative, so the
> deck is static in that frame and gravity is plain vanilla `0.08`. A body that is aboard while NOT in
> ship-frame semantics — the tick before capture, the tick after release, every category `handles`
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
  (STANDING support — box top at/below feet: `ShipFrameTravel.java`) AND NOT
  simultaneously supported by world terrain. A body on world terrain never
  becomes ABOARD. Pinned: `VSCrewCaptureContractTest.walkingOnTheGroundBesideAParkedShipNeverEntersItsFrame`.
  The (a) path DELIVERS the SEAT's deck point: the seed is a client-side pending
  boarding intent (`ShipFrameTravel.installPendingSeed`) that waits out the
  post-dismount riding tail, applies exactly once, supersedes a first-contact capture
  installed during its window (a vanilla-spot interim stand is a mis-boarding for a
  dismount), respects captures that predate it, and expires without snapping when the
  exclusion is real (C4). State machine pinned: `DismountPendingSeedTest` (7 units);
  end-to-end: the dismount e2es + `VSCrewInteriorBoardingTest` (stand = seat point,
  deterministic).
- **CREW-C2 (anchor)** `[T]` While ABOARD(S) every transform resolves through S — by
  ship id (`VSIntegration.rotateToShipFrameFor`, `aboardShipId`), never re-picked
  from containment mid-episode; `PacketDeckCapture` carries the ship id (wire — C2 doc).
  Pinned: still-crew / fast-climb kinematics tests (same class).
- **CREW-C3 (stay)** `[T]` ABOARD persists inside S's subspace region grown by a margin
  (measured in SUBSPACE — attitude-invariant; `ShipFrameTravel.java`). Jumping/
  falling above the deck does not release. Pinned: `jumpingOnTheTopDeckKeepsTheCaptureAndLandsBackOnIt`.
- **CREW-C4 (release)** `[T]` ABOARD(S) ends only on: S unloading; stepping onto world
  terrain; leaving the grown region; entering an excluded state (riding, elytra,
  creative flight — only when NOT aboard/claimable (`flyCaptureEligible`) —, water/ladder, levitation — `excludedStateOf`). Release is
  EXPLICIT (`release`, reason logged). Pinned: `aCreativeFlyingExPilotIsNeverSnappedBackByTheDismountHold`.
- **CREW-C5 (gravity)** `[T]` ABOARD bodies accelerate toward subspace −Y at vanilla
  living magnitude at ANY attitude (`travel`); no other system applies gravity to an
  ABOARD body (the Area Gravity Controller skips it). Pinned:
  `VSShipFlightTelemetryTest.crewStaysOnASteeplyRolledDeck…` (75°).
  > **Design — the `[T]` above describes the code and stays true under `shipGravityByDefault = true`.**
  > With the config at its default `false`, an aboard body
  > accelerates by **`g_felt = (g_world − a_ship) + g_field`** in the ship frame: the world's field minus the
  > ship's own acceleration (landed → the planet's gravity; coasting → zero; thrusting → toward the stern),
  > plus the gravity device's body force (`C25` HYPER-33), which steers the sum to its group's setpoint along
  > the deck's −Y within its capacity and coverage. **No device ⇒ no deck gravity.** The direction and
  > magnitude both come from `g_felt`, through `ShipFrameTravel`'s gravity input (CREW-C11's seam); rotational
  > pseudo-forces are left out. `shipGravityByDefault = true` restores exactly the clause above — the
  > test harness sets it. *Stress-test*: falsifiable (a coasting ship with no device holds no one to its deck;
  > a landed one does; a thrusting one pushes its crew aft; with a 1 g device every case reads 1 g toward the
  > deck within capacity; with the config true the 75° pin passes unchanged); code: absent; consistent with
  > CREW-C11 (the seam), CREW-C16 (harm reads `|g_felt|`), HYPER-33, STAT-14; edge — a body with `|g_felt|` ≈ 0
  > needs zero-g locomotion, which does not exist yet.
- **CREW-C6 (walking + look)** `[T]` On the deck at any attitude: WASD walks the deck
  plane along the HELD deck heading, jump is deck-up, step assist works. The local
  player's look is STORED deck-frame (`client/DeckLook.java`); the mouse turns it
  directly (`MixinEntityDeckLookTurn`), the walk basis consumes it through the client port
  (`ShipFrameTravel.ClientLookSource.deckYaw`, with a world→deck fallback mapping; the port is
  installed once from `ClientProxy.preinit`, so a `null` port means
  "dedicated server" and nothing else), and
  world `rotationYaw/Pitch` are DERIVED (`world = shipQuat·deckLook`) each tick+frame —
  the wire and the server keep world semantics unchanged. Pinned:
  `theMouseTurnsTheWalkingCrewsAimInTheDeckFrameAndTheAimRidesTheDeck` (real mouse +
  real key at 60→85° roll) + `DeckFrameLookTest` (4 unit pins on the composition math).
- **CREW-C7 (camera)** `[T]` First person while ABOARD: the eye sits along the SHIP's up
  (`MixinEntityRendererShipEye`), and the view is the composition
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
  interacted with: the raytrace originates from the SAME eye the camera renders
  (`MixinEntityShipEyes`, `ShipFrameTravel.aboardShipUpWorld`). Pinned:
  `theCrosshairPicksTheSameDeckBlockAtAnyAttitude` (ray-origin == rendered eye at 60°).
- **CREW-C11 (outer hull)** `[T]` The OUTER hull is walkable at ANY attitude with
  WORLD-frame semantics: a body there is NOT ABOARD (in subspace that surface has no
  floor beneath it), keeps world gravity/movement/camera, and is SUPPORTED against the
  ship's geometry (hull-stand mode: `ShipFrameTravel.hullStandTravel`,
  `hullContactFor`, `hasDeckBelowFor`); hatch transitions aboard↔hull-stand via
  C1's gates. Pinned: `aHullTopEncounterNeverEntersTheShipFrame` +
  `standingOnTheWorldTopOfAnInvertedShipKeepsWorldFrameSemantics` (160°).
  **Collision solid `[T]`:** the collision solid of a
  hull-stand body IS its real WORLD-upright volume — contacts with the ship happen where
  the rendered surface is, at any attitude (`HullSweep` world-axis swept-SAT OBB sweep; a
  subspace-aligned box would displace every contact by `h·sin(tilt/2)`, so the body would walk a
  block beside the visible blocks). Stand-vs-slide follows the LOCAL
  gravity with unit friction: a contact face within 45° of gravity-up holds statically,
  steeper faces shed the body tangentially (`HullSweep.slideOfBlocked`); the gravity
  vector is an input seam (`ShipFrameTravel.WORLD_UP`) so zero-g later disables the
  mechanic honestly. The hull first-contact/hold gate reads the SAME real volume grown by
  a capture margin (`HULL_CONTACT_MARGIN` 0.5) that outruns the physics mod's own world
  collision parking a faller 0.15–0.4 off the face. Pinned: `HullSweepTest` (13 unit pins
  incl. hand-computed true-geometry contacts at 45°/160° and both sides of the 45°
  threshold) + `VSDeckCaptureAndDismountTest` (box-mismatch tripwire ~0 + render-pose
  consistency on a real hull-stand).
  **Gate point `[T]`:** for a body the deck ALREADY owns, every
  aboard↔hull-stand gate is asked at the ship-frame point that is AUTHORITATIVE on the asking side
  (`ShipFrameTravel.gatePointFor`, passed to `shipSupportObstacleCountAt` / `hasDeckBelowAt` /
  `hasRoofAboveAt` from `handles`) — the point that side last COMMITTED, except where it merely
  FOLLOWS a client-authoritative player (the server's copy), where the live position is the truth.
  `followsRemoteOwner` is the one predicate, shared with the follow branch of `heldShipFramePos`.
  The two readings differ on a MOVING ship: the world position is written once per game tick from
  the committed subspace point while the physics mod advances the ship transform on its own thread,
  so re-deriving returns the committed point plus however far the transform has stepped (measured up
  to ~0.15 blocks/tick on a ship settling after a client rejoin). That skew would dip a standing body a
  few centimetres "below" its own deck, `hasDeckBelow` would find no floor, and the deck would hand it to
  hull mode — which re-bases the held deck point onto the body's world position and banks the skew.
  Alternating the two modes would ratchet a standing crew member along his own deck with no input.
  The stay-region gate deliberately keeps the LIVE position: leaving the ship is a world-frame fact.
  **The follow exception is not cosmetic** — asking the server's own committed point costs
  `VSCrewInteriorBoardingTest` its ABOARD verdict, because a follower's committed point may sit
  up to the external-move guard's slack from where the owner actually is. Pinned:
  `VSCrewRelogPersistenceTest.aCrewMemberWhoLogsOutWalkingComesBackStandingStillOnHisDeckSpot`
  — the client's own per-tick record must show ZERO hull-stand ticks and a held deck point that
  travels &lt;0.2 blocks; with the gate on the live point the same run measures 0.36.

- **CREW-C12 (interior boarding — maintainer ruling 2026-07-16, enclosure term ruled
  2026-07-18)** `[T]` A body INSIDE a
  ship's subspace block region **that is ENCLOSED — ship blocks overhead in the SHIP
  frame (`hasRoofAboveFor`) —** and has a deck below it IN THE SHIP FRAME (within a
  bounded reach — `hasDeckBelowFor`) becomes ABOARD by first contact even without
  standing support: deck gravity (C5) then carries it to the deck, where it stands at
  any attitude (inverted ship: stands on the world ceiling, camera flipped per C7). The
  enclosure term is a ruled RESTRICTION: the margin-0 block-bounds
  region over-covers (it contains the OPEN air between a deck and the ship's topmost
  blocks), and "region + deck below" alone would hijack a body merely flying through that
  airspace — violating C9's pin (`flyingIntoAShipsAirspaceWithoutStandingOnItDoesNotHijackTheCamera`).
  A roofed body past the deck probe's reach is likewise
  never released by `noDeckBelow` (interior-stay, `ShipFrameTravel.java`). Without this, hull-stand
  would hold an interior body to the world-floor with a world camera; C11 therefore applies to
  contacts with NO ship-frame deck below (the true outer hull), and an enclosed body with a deck
  below is C12's. Pinned (capture site `ShipFrameTravel.java`): the released-interior
  re-seat (`VSCrewInteriorBoardingTest.aBodyReleasedInsideAnInvertedShipIsSeatedBackOnTheDeck`)
  + the POSITIVE roofed-cavity capture —
  `aBodyLostMidCavityOfAnEnclosedInvertedShipIsReclaimedByTheDeck` on the
  `with-roofed-deck` fixture: a body displaced UNSUPPORTED mid-cavity of the 170° ship
  (off the deck past the support probe, under the roof) is claimed ABOARD by the
  interior gate — never the outer-hull fallback — and deck gravity carries it back
  AGAINST world gravity to its deck stand with the ship camera on.
- **CREW-C13 (flying-aboard — maintainer ruling 2026-07-16, variant A)** `[T]`
  A creative-flying body meeting C12's interior condition (or
  standing-contact on a deck) is captured ABOARD while STILL FLYING: its flight
  kinematics resolve in the DECK frame (thrust on deck axes via the deck yaw, the
  ±flySpeed·3 vertical intent along the DECK normal, vanilla's own 0.6 vertical / 0.91
  lateral drags on their deck axes, NO gravity while flying —
  `ShipFrameTravel.flyingAboardTravel`), and its look/camera are deck-frame
  (C6/C7) — one transform for input, aim and view, airborne included. Flight-off while
  aboard hands over to C5/C6 seamlessly. The C4 flight exclusion is conditional:
  creative flight is
  excluded only for a body that is neither ABOARD nor claimable where it is
  (`flyCaptureEligible` — standing contact | enclosed interior, never on world
  terrain); a flyer who LEAVES the stay region is released mid-flight and never yanked
  back (C4's region-exit release, unchanged). Vanilla's world-frame flight machinery is
  intercepted for an aboard flyer: the client's world-Y input impulse is undone and
  re-applied along the deck normal (`ClientLookSource.flyIntent`, sampled at
  CALL time in `DeckLook.Port`), and `EntityPlayer.travel`'s flying wrapper — whose `motionY = d3*0.6`
  runs AFTER the resolution and is a world-frame vertical writer — is claimed whole
  (`MixinEntityPlayerShipFlight`). No partial capture-without-deck-
  kinematics state exists (the forbidden split). Pinned:
  `VSCrewInteriorBoardingTest.aFlyingCrewMemberAscendsAlongTheDeckNormalAndReseatsOnFlightOff`
  (60° roll: capture+camera held while flying, ascend/descend along subspace ±Y with
  dxz=0, flight-off reseats) + `aCreativeFlyingExPilotIsNeverSnappedBackByTheDismountHold`
  (no yank-down; region exit releases).

- **CREW-C14 (persistence)** `[T]` ABOARD survives a relog: a player who logs out
  ABOARD(S) logs back in ABOARD(S) at the same subspace point, at any ship attitude —
  never handed to world gravity while the capture re-seeds. The anchor persists on the
  player (`ShipFrameTravel.persistAnchor` → `getEntityData`, refreshed per resolved
  aboard tick, cleared on release/hull-stand); `RelogDeckHold` pins the returning body
  (200-tick window) and re-sends `PacketDeckCapture` until the owning client seeds; ends
  on capture / excluded state / timeout (clean vanilla handover). Pinned:
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
  redone once the poses are current: `ShipFrameTravel.followShipPoses`, registered at
  `EventPriority.LOWEST` so it lands after the substrate's own END handler
  (`DeckFollowsItsShip` on the world tick; `ClientDeckFollowsItsShip` on the client tick, which
  is where the client ticks its ships — Forge fires no world tick event there). Excluded: a
  body this side only FOLLOWS (`followsRemoteOwner` — a real player's position is decided on his
  own client), and a rider, whose vehicle owns its position.
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
  a body anywhere. Consistent with CREW-C1..C15 (deck capture holds a body at any attitude; this clause
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
  hold IS the craft's artificial gravity, and the world-collision paragraph amends the deck-hold
  contract's collision rule.
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
  boxes too. Seen from S's frame a world block is an oriented box, so this is the swept-SAT that
  `HullSweep` already performs for a hull-stand body (world-upright body against S-oriented boxes),
  with the rotation inverted; the step is the shorter of the two sweeps. A world face is stood on or slid
  down by `HullSweep.slideOfBlocked`'s rule against the deck's gravity; a world block that the craft's
  own motion carries INTO a resting body is resolved by depenetration, which a sweep of the body's own
  motion cannot see. Without this a body held over world ground with no deck of S beneath it in S's
  frame has nothing to stand on: `ShipFrameTravel#sweepShipFrame` asks `world.getCollisionBoxes` at the
  body's SUBSPACE coordinates — the shipyard — and never at its real position.
  *Stress-test — consequences derived, not ruled:*
  1. **C4's "stepping onto world terrain" release** contradicts the predicate (a body on terrain in the
     field is held again the same tick) — retire it; release on leaving the C3 region and the other C4
     causes. Whether C3's region (block box + 4.0) should become "the field, grown" is open.
  2. **A body on the ground within R of an inverted hull is held, and deck gravity pulls it toward
     subspace −Y — world UP for a ship rolled past 90°.** With world collision it is held against the
     ground no more; it falls UP to the deck. A cow under a 159° `with-pilot-deck` is such a
     body. Accepted by the rule as stated, or the field needs a direction.
  3. **The airspace hijack C12 was written against returns within R of a block**, and is gone beyond it.
  4. **Hull-stand ↔ held at a hatch** (C11): a body standing on the outer hull has its feet within R of
     the hull, so it is IN the field. C11's "outer hull keeps world semantics" contradicts the predicate
     unless the outer hull is carved out of FIELD(S) — e.g. the field is on the deck side only — and that
     is a question about the field's SHAPE, which the coarse SDF does not answer.
  Falsifiable: feet at d_S = 1.9 → held and drawn ship-aligned; 2.1 → neither; a held body over world
  ground with no deck beneath it in S's frame stands on the ground. Code: absent — first contact is
  support-based (`ShipFrameTravel.java`); no SDF exists (`FieldSurfaceMath.
  compositeHullDistance` is a union of spheres around shield emitters, not a hull); the held sweep is
  subspace-only.

## Open questions

- CREW-C8 is unimplemented/unverified — promote to `[T]` when pinned.
- Remote-crew model rotation still gates on containment until CREW-C17's spatial gate exists
  (`client/ShipFrameCamera.java` comment) — a deliberate, documented exception to
  CREW-C7's gate rule for non-local bodies.
