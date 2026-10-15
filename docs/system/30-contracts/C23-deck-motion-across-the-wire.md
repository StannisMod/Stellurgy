---
id: C23
covers: what a client must be TOLD about a craft's motion so that a body standing on it lands in the right place — and what the server may accept back
confidence: RULED (model taken verbatim 2026-08-24; DECKSYNC-5's mechanism ruled the same day). Built: -1, -2, -3, -4, -5's mechanism (the pose source is `DeclaredMotionTransformInterpolator`), -6, -7. Pinned: -6, -7's false-positive leg. Open on -5: the "no jerk" acceptance is a RENDER judgement and is owed a human look
owner-subsystem: the Stellurgy↔substrate seam (C11 owns the port's shape; this owns what crosses it per SIDE) + crew (C8)
see-also: [C8 (what a body does aboard — this says what the client needs to know to do it), C11 (physics-substrate port), C19 (which body a velocity is measured against), C2 (the wire format any new packet lands in), C22 (what a CRAFT does with momentum; this is about the BODY on it)]
---

# C23 — Deck motion across the wire, clauses DECKSYNC-1..DECKSYNC-7

A body standing on an inverted hull must not be launched ~30 blocks/tick because the client had to
RECONSTRUCT the craft's velocity — no packet carried it — by dividing a 200-tick-old observation by
one tick. The arithmetic is fixable in a line. The reason the client reconstructs at all is the
subject of this contract.

**The failure this prevents.** Player movement is client-authoritative, but the information the client
needs to compute that movement correctly does not reach it.

## Layers and direction

| layer | owns | how the other side learns of it |
|---|---|---|
| substrate (VS), SERVER | a craft's motion | the client is SENT a transform and renders it |
| Stellurgy body/crew | what a body does | for a PLAYER the client DECLARES it and the server accepts |

The seam is the carry: a body on a deck moves with the deck, so Stellurgy must express the craft's motion in
the body's own frame. The server answers that from physics; a client that answered it by differencing
its own observations would be a second authority, one of which is guessing.

**Vocabulary.** *Deck motion* is the craft's motion expressed where the body stands (6DOF: linear plus
angular). *Carry* is the part of a body's velocity that comes from the deck rather than from the body.
"Transform" belongs to the substrate and is referenced here, never redefined.

## Clauses

- **DECKSYNC-1** *(the client is entitled to what it must compute)* `[A][SYS]` Player movement is
  client-authoritative; therefore **100% of the information needed to compute the correct player
  position is present on the client**. A quantity the client must use and is not told is a defect in
  this contract, not a client-side problem to be worked around. Ruled: *"движение игрока
  клиент-авторитетно, значит 100% информации, которая нужна для того, чтобы посчитать правильную
  позицию игрока, должно быть на клиенте"*.
  **Held for the deck carry** `[V]`: the craft's 6DOF motion crosses with its pose (DECKSYNC-3) and
  there is no client reconstruction. The entitlement is a general one, so this clause is never "done"
  — it is the test every future quantity a client must compute has to pass. FOR: DECKSYNC-5.

- **DECKSYNC-2** *(scope is what the client SEES)* `[A][SYS]` The entitlement covers the craft a client can
  see — its own and the ones it renders — and no more. Shipping every craft in the world to every
  client is not reasonable and is not asked for. Ruled: *"клиент должен знать только о тех кораблях,
  которые видит — не только свой, чтобы рисовать их правильно. Грузить его всеми кораблями
  неразумно"*.
  **Built** `[V]`: the pose packet is built PER PLAYER and carries the craft that
  player watches (`EventsCommon.sendShipTransformUpdates` over `PhysicsObject.getWatchingPlayers`),
  not every loaded craft to every client in the dimension (which would make DECKSYNC-3's six extra
  numbers per craft a cost every client paid for craft it cannot see). No new machinery: the watcher
  set is the one the ship INDEX packet maintains from the watch/unwatch distance. FOR: DECKSYNC-5.

- **DECKSYNC-3** *(what crosses, and in what units)* `[A][SYS]` Periodically — every N — the craft sends its
  **deck motion in 6DOF** together with the **current transform**. The two travel together because a
  velocity without the pose it belongs to cannot be applied to a point.
  **Built** `[V]`: `ShipTransformUpdateMessage` carries six doubles per craft beside
  the pose — linear and angular velocity, world axes, blocks and radians per second — read where the
  pose is read (`PhysicsCalculations` publishes both in the same physics step) and stored on arrival
  into the client's `ShipPhysicsData`, which is where every consumer already looks.
  **`N = 1`**: the sender is the game tick (`EventsCommon.sendShipTransformUpdates`).
  The ship INDEX packet updates transform, inertia and the physics flag and skips `ShipPhysicsData`,
  so a client copy of a craft's velocity that only that packet fed would sit at the zero it was
  constructed with — which is why the client would otherwise reconstruct a rate at all. FOR: DECKSYNC-5.

- **DECKSYNC-4** *(what the client does between packets)* `[A][BEH]` Until the next packet the client
  APPLIES the motion it was given and interpolates from it; on arrival the client's state and the
  packet RE-SYNC. The client extrapolates from a told value — it never derives a new one.
  **Built** `[V]`: `PhysicsObject` builds `DeclaredMotionTransformInterpolator` on the
  client. Measured on a craft slewing at 2 rad/s with the rotational trace at full precision: the
  shown orientation matches the declared one on EVERY tick (`behindAngle = 0.00000`) and the shown
  pose steps at exactly the declared rate (`0.10000` rad a tick), where a filter chasing the newest
  pose alternates `0.1333` / `0.0667` around the same mean — short of the truth on the tick a pose
  lands, past it on the tick after. A pose fails to arrive one tick in five to eight.
  **The pose source is not load-bearing for whether a body stays on its deck.** A body aboard left
  standing on the pose its ship held a TICK AGO (ship poses advance at tick phase END after the
  entities have already moved) stands 0.1974 blocks a tick behind either pose source; C8 CREW-C15
  fixes that, so the seat miss is zero behind either source and the pose source can be chosen on its
  own merits. Two traps when changing the mechanism: a carry reported as ZERO on the tick a packet
  arrives, and a residual cap stated in RADIANS (which becomes a 1.6-block step at whatever arm it
  acts through).

- **DECKSYNC-5** *(the re-sync is not felt)* `[A][BEH]` A body must not JERK when a packet lands. The
  maintainer named this as the hard part of the design and it is an acceptance criterion, not a
  nicety: *"главное, чтобы это не вызывало рывков"*.

  **Mechanism built; the acceptance is NOT discharged.** What is measured is that the
  prediction has nothing to snap to — `behindAngle = 0.00000` on every tick of a driven slew — and
  that no body is displaced by an arrival (seat miss 0.001, zero capture releases). What is NOT
  measured is what the clause actually asks: whether a PILOT feels a jerk. That is a render
  judgement about a moving camera, it is the one thing the harness cannot answer, and it is owed a
  human look before this clause goes past `[A]`. Do not promote it on the numbers above — they
  describe the body and the pose, not the felt motion.

  **RULED 2026-08-24 — EXTRAPOLATION from the declared state, not blending toward it.** A packet
  carries the craft's pose and 6DOF motion valid at a stated tick; the client advances from that
  state, so by the time the next packet arrives its own prediction already agrees with it and there is
  nothing to snap. Chosen over correcting-over-the-interval and over a per-tick correction cap for a
  reason that is DECKSYNC-1 restated: extrapolating from a value you were told is COMPUTING the
  position, while blending or capping is HIDING an error. It is also not the more expensive option:
  the same packet plus a tick stamp.

  **The tick stamp** `[V]`: each `ShipTransformUpdateMessage` carries `serverTick` (the world's total time
  at the send, `ShipTransformUpdateMessage.java:69`), and `DeclaredMotionTransformInterpolator` places each
  pose at its own tick rather than at the tick it ARRIVED: a pose newer than the shown tick moves the shown
  tick to its own (a burst is caught up to continuously, through the residual); a pose for a tick already
  shown by prediction corrects it as a residual; and when that pose arrives with nothing newer, the shown
  tick stays on it rather than predicting a further tick, so the client does not settle a tick AHEAD of the
  stream, where every change of the craft's motion is an overshoot. Without the stamp, two poses landing
  inside one client tick and none in the next stepped the shown pose 4.0, 2.0 (predicted), then 0.0 — a
  seated pilot's freeze-and-catch-up. **The residual retires by fraction alone** (survives 0.5 a tick,
  `DeclaredMotionTransformInterpolator.java:131`), never by an absolute cap: measured 2026-10-05 under load,
  the server's physics fell behind the game tick, every prediction overshot by about a block, and a residual
  capped at 0.2 grew to 11.7 blocks. The game tick only SAMPLES the physics thread, which steps 0–4 times
  between samples, so a repeated or bunched pose still reaches the client as prediction error.

  **Its limitation**: a prediction is only as good as the assumption that acceleration holds between
  packets. When the craft's acceleration CHANGES mid-interval the prediction diverges, and that
  residual still has to be absorbed.

  **The residual is absorbed by blending it away over the following interval** (maintainer, same
  ruling) — not as the mechanism for staying in sync, but as the mechanism for retiring the error the
  mechanism leaves behind. The blend constant is a TUNABLE whose default must be measured on a craft
  that changes acceleration under a real client, never picked to look smooth.

  **The RENDER and the CARRY are driven from one source.** Taking the DECLARED velocity as the carry
  is not the same as taking the motion of the pose actually being SHOWN: the pose also retires the
  residual, and that retirement is real deck movement. A body carried by the declared number alone
  slides by exactly the difference (measured: the shown pose stepped 0.5 blocks in a tick while the
  carry said 0.07, and the capture guard read the difference as a teleport). So the interpolator
  answers `getShownVelocity` — the rate of the pose it shows — and every consumer asks IT.

  **The blend constant is 0.5 per tick** (`RESIDUAL_SURVIVES_PER_TICK`): a mispredicted tick is under a
  tenth of itself after four, which is inside the fifth of a second a player cannot resolve. It is NOT
  yet the measured default — the e2e that changes a craft's acceleration mid-interval does not exist,
  and until it does the number is a starting value with its reasoning written down, not a measurement.

  **A large residual is adopted, never faded**: a teleport, a jump arrival or a ship load is a
  discontinuity, and fading across one would sweep the deck — and anything standing on it — through
  the space between. The bound scales with what the craft itself declares it can cover, plus a floor.

- **DECKSYNC-6** *(no self-invented numbers)* `[T][SYS]` A body is never moved by a quantity only its own
  client derived. Differencing a sequence of observations is not a substitute for being told: the
  first member of such a sequence is whatever the client last happened to look at, and the interval
  between them is whatever the code happened to ask.
  **Satisfied** `[V]`: both sides evaluate `v + omega x r` (in blocks per tick: the port converts the
  substrate's per-second numbers, so no deck reader converts), and the client asks two different
  questions of its interpolator. The CARRY that keeps a held body on the pose it stands on is the rate
  that shown pose moves at (`VSBridge.shipVelocityAtPointFor`, the interpolator's own record of its
  step, not an inference). A body's velocity RELATIVE to the craft — what admission subtracts from a body
  arriving from the world — is measured against the craft's DECLARED motion
  (`VSBridge.declaredShipVelocityAtPointFor`), because the shown rate includes the client retiring its
  own misprediction. Measured 2026-10-15 before that split: a player teleported over a STILL craft was
  admitted while his client retired 0.37 / −0.32 / 0.13 blocks a tick of error, was handed its negative
  as his velocity on the deck, rose 0.46, slid 2.4 blocks in the deck's own frame and was released off its
  edge. Measured on a driven climb, at the fastest sample: server declares `1.5333` blocks/tick of carry,
  the client applies `1.4700` — one number in two places.
  *Why a derivation cannot be rescued:*
  - *The divisor* `[V]`: a derivation that counted CALLS rather than ticks reported a 0.279 rad/s roll
    as 55.5 rad/s — inflated by exactly the 200-tick gap since the previous query. The divisor is
    real elapsed time (`VSBridge.observationSeconds`), and an interval wider than a formula is written
    for must be REFUSED rather than averaged (a body then gets nothing rather than the average of a
    manoeuvre that has ended).
  - *The bound is measured, not chosen* `[V]`: 2275 derivations on a real client running the
    crew-capture suite split into two populations with nothing between them — 2266 at one or two
    ticks (the steady state) and 9 at 26–237 ticks (a body meeting a deck again after an interval
    nobody queried through). Pinned by `VSCrewCaptureContractTest#aBodyMeetingADeckThatManoeuvredUnwatchedIsNotCarriedByIt`. FOR: DECKSYNC-5.

- **DECKSYNC-7** `[T][BEH]` *(the server bounds what it accepts)* `[A]` The server does not simply ratify a
  client-declared position. Knowing the player's own movement vector — slightly lagging, which is
  enough — and the craft's motion in full, it computes the REGION the player could occupy had he moved
  at his maximum allowed speed, and that region caps the accepted movement. Ruled: *"может вычислить
  бокс, обозначающий область, в которой игрок может оказаться, используй он свою максимальную
  разрешенную скорость. Это и будет капом на клиентское движение"*.
  **Built** `[V]`: `DeckMovementBound`, consulted from Stellurgy's own mixin at the tail of
  the movement packet's handling. The region is the body's own maximum speed plus the deck's carry at
  its point, scaled by the ticks that actually passed; a position outside it is refused and the
  server's own previous position stands. The anchor is read from the server on either side of the
  packet rather than remembered (a remembered record reads every staging teleport as a body crossing
  3925 blocks in a tick).
  **Where its state lives** `[V]`: the only thing the bound remembers — when each player's
  movement was last judged — is an instance of `DeckMovementBound` held by the SERVER
  (`ServerState.deckMovement`), keyed by player id and forgotten at that player's
  logout (`ServerState.playerLoggedOut`, `DeckMovementBound.forget`); the mixin reaches it through the
  server, so a second server in a JVM has its own and nothing of a player outlives his connection
  (`DeckMovementBound.java`). The deck holds (`ServerState.deckHolds`) and the
  `Entity.move` takeover experiment (`ServerState.shipLocalMove`) are on `ServerState` too.
  **Per-world pose state**: the pose sender's clock (`PoseSendClock`, read by the physics watchdog
  `poseSendIsOverdue`) and the last-safe-position record the movement check sends a player back to
  (`LastPlayerPositions`) are parts of the WORLD they describe (`WorldRuntime`) in
  `valkyrienskies/…/EventsCommon.java` — none is a JVM-wide field. The usable edge of
  a ship is posted by the ship manager itself.
  **The bound on a body's own power is MEASURED**: 2.0 blocks/tick, against 0.89 and 1.47 recorded
  across real walking, jumping and riding a climbing deck in the crew suite.
  **Nothing fires ahead of the bound**: there is no tighter external-move guard, so the refusal path is
  reachable. Gated on the deck's hold alone (`DeckBoarding#aboardShipId`, `DeckBoarding.java:438`): riding
  and elytra end a hold, but a creative flyer the deck already holds stays held and is bounded too
  (`DeckBoarding#excludedStateOf`, `:43`) `[V]`. Pinned by `VSCrewCaptureContractTest#walkingAndJumpingOnAHoveringShipDoesNotChurnTheCapture`, `VSCrewCaptureContractTest#aWildClientSideStepOnADeckNeverBecomesADeclaredPosition`.
  **The bound REPLACES vanilla's speed check, and judges every held body** `[V]`: `DeckMovementBound#judges`
  (`DeckMovementBound.java:131`; a deck's hold only — a player on the outer hull is the physics substrate's
  and is judged by vanilla's check like any body the world moves, which bites only past its 10 blocks a
  tick) answers the condition of `processPlayer`'s "moved too quickly" check
  (`MixinNetHandlerDeckMovementBound`, the first `isInvulnerableDimensionChange` call), so no speed of the
  craft under a held body can trip a check that does not know the craft is there. A body nothing holds
  faces vanilla's check; one the deck lets go of leaves with the craft's carry at his point added to his
  server velocity (`DeckFrameTick#keepCarry`, `DeckFrameTick.java:845`), as his client always had it, so the
  two sides agree on his momentum.
  **What a held player's packet declares** `[V]`: his point ON THE DECK, not a world position. His client
  declares it where its deck frame left him (`DeckFrameTick#mapOut` → `IDraggable#setMovementClaimInShip`,
  valid for that tick and that world position only), the physics mod's packet carries it as a ship-frame
  position and maps it through the server's pose (`MixinNetHandlerPlayServer#preProcessPlayer`), and the
  server takes it onto the deck in the same phase of its tick (`DeckFrameTick#claimArrived`, at the tail
  of `processPlayer`, `DeckFrameTick.java:338`) — his next update comes after the world tick has moved the
  craft, and re-derived there from a world position it would land 0.08–0.15 off the floor at a roll's
  onset.

## What this contract does NOT decide

- The packet's place in the wire format, its id and its cadence `N` — C2's subject, and a number that
  is measured rather than invented.
- What a body does aboard once it HAS a correct carry — C8's subject.
- Which body a velocity is measured against — C19's.
- What the craft itself does with momentum — C22's.

## Test coverage

A green suite is not evidence about a clause with no implementation behind it — there is nothing for
an assertion to fail against; read the rows for what they are.

| clause | pinned by | state |
|---|---|---|
| DECKSYNC-1 | unpinned `[A]` | The measurement is real (at the fastest sample of a driven climb the craft declared `1.5333` blocks/tick and the client applied `1.4700`, one number in two places) but no live test re-establishes it. A replacement needs a driven climb that reads the craft's declared velocity against the client's applied carry (`deck_carry` carries the second) |
| DECKSYNC-2 | — | unpinned. Built (per-watcher send); what a pin would have to show is a client NOT receiving poses for a craft outside its watch distance, which needs a second client or a probe on the send |
| DECKSYNC-3 | same as DECKSYNC-1 — unpinned `[A]` | the cross-side agreement above is only possible if the motion crossed; it inherits DECKSYNC-1's missing pin |
| DECKSYNC-4 | unpinned `[A]` | nothing is derived, and the carry is the pose's own step, so a body does not slide; the client APPLIES the declared motion between packets (measurement above). Neither reading has a live pin |
| DECKSYNC-5 | unpinned `[A]` | The per-tick trace windows (`test/trace/DeckPoseTraceWindow`) exist: per tick, whether a pose arrived, what it said, what was shown, the step, how far behind, and what the capture guard measured. First reading: gaps are **one tick in five to eight**, not the 4% a counter suggested; on a gap the shown pose holds and the tick after takes a **double step** (0.100 → 0.200). Still needs a drive in the FAST regime (churn lived at 3–5 blocks/tick) and a client e2e that can SEE a jerk — a per-tick position trace across a packet boundary, never a settled reading — and a second that changes the craft's acceleration mid-interval, the only case the chosen mechanism does not handle by construction |
| DECKSYNC-6 (a manoeuvre that ended) | `client/VSCrewCaptureContractTest.aBodyMeetingADeckThatManoeuvredUnwatchedIsNotCarriedByIt` `[T]` | A body stands on the deck once, leaves, the craft rolls 40° unwatched and settles, and the body returns: its carry is what the craft DECLARES (`5.4e-4`/tick, a craft at rest) and not the window's average (`3.9e-3`/tick) |
| DECKSYNC-7 | `client/VSCrewCaptureContractTest.walkingAndJumpingOnAHoveringShipDoesNotChurnTheCapture` (false-positive leg) and `…aWildClientSideStepOnADeckNeverBecomesADeclaredPosition` `[T]` | for what can be established: ordinary walking and jumping are never refused (0 refusals against a measured 0.89–1.47 blocks/tick of own movement, bound 2.0), and the bound is consulted on every packet from a captured body. Its REFUSAL path is unpinned, though reachable, so pinning it is ordinary work |

## Open questions

1. **The residual blend constant** (DECKSYNC-5) is 0.5 per tick — a mispredicted tick is under a
   tenth of itself after four. It has not been measured against a craft that changes acceleration
   under a real client, because that e2e does not exist yet; what IS measured is that carrying the
   residual's own decay into the deck's reported motion was necessary — without it the shown pose
   stepped 0.5 blocks while the carry said 0.07 and the capture guard read the difference as a
   teleport.
2. **`N`** is 1 because the pose packet is sent from the game tick. It becomes a real question again
   only if DECKSYNC-2's watcher scope is traded for bandwidth by lowering the cadence — at which
   point DECKSYNC-4/-5 stop being theoretical.
3. DECKSYNC-7's region is computed only for a body Stellurgy is carrying: the check begins with the
   capture lookup and returns immediately for everyone else.
