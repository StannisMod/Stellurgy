---
id: C14
covers: which sky a world draws, what it shows of the surrounding system, and when a cell's bodies are fed to it
confidence: medium (01..11 mapped; 12..17 implemented, tags below)
owner-subsystem: none — the movable-ship space subsystem; render code in client/render/planet/
see-also: [C13 (space presence — cell is ITS term), C15 (the address/frame model this doc renders)]
---

# C14 — The cell sky and the body feed, clauses CON-C14-01..20

The cell sky is a statement about the player's situation. A starfield with no sun and no moon plus a
light-blue band around the horizon, drawn over ordinary forest terrain, tells the player he can begin
a descent on a world he has already descended to. The body FEED half is "the sky belongs to the cell,
not to a ship" (`SystemBodiesProducer`) and is keyed only to a live cell's slot world. The RENDERER half
says which provider may answer with which sky.

> **There is no descent-boundary ring in the cell sky.** A camera-frame band at a constant radius,
> unconditional for any cell, cannot express a boundary and asserts one even where none exists. A
> boundary belongs to a BODY and is drawn around that body's bearing at the angle its shell subtends
> (`space/DescentShell`, C2 `PacketSystemBodiesSync`).

## Terms

Layer-1 terms (**cell**, **slot dimension**) keep their C13 meanings and are not redefined.

- **cell sky** — `BoundarySky`: a starfield and one billboard per fed body. It replaces the ENTIRE
  sky `[V]`, so nothing it omits is drawn.
- **slot world** — a world whose CLIENT-side provider is `WorldProviderSpaceSlot`.
- **sky owner** — the `WorldProvider` instance the CLIENT holds for a dimension. Forge consults
  exactly one selector, `WorldProvider.getSkyRenderer()`, so the sky owner is the only thing that
  decides what a player sees `[V]`.
- **body feed** — the `PacketSystemBodiesSync` server→client channel, a map keyed by dimension id.

## Clauses — the renderer

- **CON-C14-01 (one owner)** `[V][SYS]` A world's sky is chosen by its client-side provider and by
  nothing else. Two mechanisms feed that choice and they are NOT equivalent: a provider that
  overrides `getSkyRenderer()` answers from its own state; one that does not answers from the
  `skyRenderer` FIELD, which `setSkyRenderer` writes. `WorldProviderSpaceSlot.getSkyRenderer():30`
  overrides and never reads the field; vanilla `WorldProviderSurface` (dim 0) has only the field. FOR: CON-C14-02.
- **CON-C14-02 (the cell sky is for cells)** `[A][BEH]` The cell sky is drawn **iff** the world is a slot
  world. Everything it draws is a claim about the player's situation — drawing it anywhere else
  states a falsehood. `BoundarySky` is constructed at exactly one site
  (`WorldProviderSpaceSlot.getSkyRenderer():31`) `[V]`, so any other world showing it means the
  world resolved to the wrong provider, not that a renderer leaked.
- **CON-C14-03 (a planet is never a cell)** `[A][BEH]` A world with terrain — the overworld, a Stellurgy
  planet, an asteroid — never draws the cell sky, whatever the space subsystem's state.
- **CON-C14-04 (no silent field write)** `[V][SYS]` `setSkyRenderer` on a provider that overrides
  `getSkyRenderer()` is a NO-OP that looks like a change. `PlanetEventHandler.worldLoadEvent`
  writes `RenderPlanetarySky` into every client world at load; for slot and planet providers that
  write is discarded. A change to a world's sky must go through the owner (CON-C14-01), never
  through a field write whose effect depends on the provider class. FOR: CON-C14-02.
- **CON-C14-05 (the flag means what it says)** `[V][BEH]` **VIOLATED TODAY.** `overworldSkyOverride` is
  documented "Use Stellurgy's custom skybox in the overworld" (`StellurgyConfiguration.java`) but its only
  reader applies to EVERY client world load (`PlanetEventHandler.java`). A flag must scope to
  what it names (C4 legend). Harmless in effect only because of CON-C14-04.
- **CON-C14-18 (hyperspace's backdrop belongs to the WORLD, not to a chair)** `[A][BEH]` One slot world is
  the transit host, and it draws the CORRIDOR in place of everything CON-C14-02 draws — a cell's sky
  would be a false statement there (no cell is loaded, so no body is ever synced, and a starfield alone
  says "parked" to a ship that is in a jump). The branch is taken on the client's OWN dimension,
  compared against the hyperspace dim id the server names in `PacketSlotDimSync` (C2), so it holds for
  every occupant of that world in every posture. `BoundarySky.render` →
  `HyperspaceWorld.isHyperspace(World)`. Pinned by
  `VSTransitCrewGroupTest.aStandingCrewMemberStillSeesTheHyperspaceCorridor`.
  The branch must not read the jump phase published on the SEAT entity: that answers 0 for anybody
  riding nothing, so a crew member who stood up mid-flight would get an empty, motionless sky and
  read his own jump as having stopped. The phase is derived posture-blind on the server. Pinned by `VSTransitCrewGroupTest#aStandingCrewMemberStillSeesTheHyperspaceCorridor`.

## Clauses — the feed

These state, as numbered clauses, the rules implemented in `SystemBodiesProducer`, so a later change
cannot quietly drop them.

- **CON-C14-06 (cell-keyed, not ship-keyed)** `[V][SYS]` The feed is built from the cell→slot bindings
  (`SpaceManager.loadedCells`), never from a ship's lifecycle state
  (`SystemBodiesProducer.java, 87-92`; pinned by `SystemBodiesFeedFollowsTheCellTest`).
  Everyone in a live cell sees that cell's surroundings: a pilot mid-jump, a passenger, a crew
  member who walked off the hull, someone a departing ship left behind. Pinned by `SystemBodiesFeedFollowsTheCellTest#aLiveCellWithNoShipInItIsStillToldWhatIsAroundIt`, `SystemBodiesFeedFollowsTheCellTest#aLiveCellWhoseOnlyShipIsMidJumpIsStillToldWhatIsAroundIt`, `SystemBodiesProducerTest#aLiveCellWhoseOnlyShipIsMidJumpStillShowsItsBodies`. FOR: CON-C14-14.
- **CON-C14-07 (only cells are keyed)** `[V][SYS]` A dimension that is not a live cell is keyed by
  nothing. A cell bound to no slot keys nothing, because there is no world whose sky it would be
  (`SystemBodiesProducer.java`). Pinned by `SystemBodiesProducerTest#aShipWhoseCellIsInNoSlotContributesNothing`, `SystemBodiesProducerTest#anUnboundOrMalformedBindingIsNeverKeyed`. FOR: CON-C14-14.
- **CON-C14-08 (an empty cell is keyed empty)** `[V][SYS]` A live cell holding no body still gets a
  present-but-empty entry, so the client clears stale bodies and draws bare sky
  (`SystemBodiesProducer.java, 108`). "Present and empty" and "absent" are different states
  and must not be collapsed. FOR: CON-C14-13.
- **CON-C14-09 (direction, not position)** `[A][SYS]` A body's `localX/Y/Z` is the **observer→body**
  vector, sector-aware (`SystemBodiesProducer.java`). A body's own in-cell offset is not that
  vector: a planet sits at its own cell centre, so sending the offset yields a zero vector and
  `BoundarySky.drawBody` bails at `|dir| < 1e-6` (`BoundarySky.java`) — the descend target
  renders nothing. (A ship block's `BlockPos` is subspace and entities are world-frame: the vector is
  computed in the universe frame, never from either.) Pinned by `SystemBodiesProducerTest#aBodyAtTheCellCentreIsCarriedAsTheDirectionFromTheShipThatIsThere`, `SystemBodiesProducerTest#crossCellBodyDirectionIncludesTheSectorTerm`. FOR: CON-C14-14.
- **CON-C14-10 (one observer per cell)** `[V][SYS]` The observer point is a ship the ship registry places
  in the cell, preferring `SETTLED`, else the cell centre (`SystemBodiesProducer.java`). It is
  one direction set per dimension — the sky is camera-centred, so every viewer in the cell shares it. FOR: CON-C14-14.
- **CON-C14-11 (the highlight means "you can land here", not "a world already exists")** `[V][BEH]`
  The feed's `descendTarget` flag is `body.kind().canDescend()` — a render HINT about the KIND of
  body — and not `isDescendTarget()`, which additionally requires a realized dimension
  (`SystemBodiesProducer.java`, the `RenderBody` construction). A procedural planet has no dimension
  until a descent mints one, so keying the highlight on the dimension would hide the descent boundary
  of every world nobody had visited yet — exactly the set a pilot is out there looking for. Logic that
  genuinely needs a world — the jump resolver, the arrival — still asks `isDescendTarget()`.

## Clauses — what a cell CONTAINS

Both clauses below are OWNED BY C15 (ADDR-1 "a name, not a place" and ADDR-13 "the geometry the
player feels stays live"); the anchors are permanent.

- **CON-C14-12 (a body's cell is durable)** `[A][SYS]` A body owns ONE cell; orbital motion moves it
  WITHIN its cell and never BETWEEN cells. See C15 ADDR-1. Scale, for why this is not a corner case:
  a cell is 32 000 000 blocks (`GalacticCoord.CELL`) and one AU is about 6·10⁸ chart blocks
  (`AstronomicalBodyHelper.BLOCKS_PER_AU`), so a body crosses a cell boundary within a few percent of an
  orbit `[V]`;
  an address derived from the LIVE ephemeris (`positionOf(planet, NOW)` →
  `DimensionProperties.getPlanetPosition()` → `positionFor(this.orbitTheta)`, with `orbitTheta`
  rewritten every tick by `updateOrbit():1147-1150`) would make `bodiesAt` (which filters
  `b.address().sameCell(cell)`, `UniverseRegistry.java`) drop a body out of the cell it was in. FOR: ADDR-11.
- **CON-C14-13 (what the sky shows is what the cell holds)** `[A][BEH]` A viewer parked in a cell sees a
  body appear or disappear only when something really changed about that body — never because time
  passed while he sat still. This is the player-facing form of CON-C14-12 and C15 ADDR-13.

## Clauses — what the sky SHOWS (maintainer-ratified 2026-08-01)

These are the render half of C15's model: C15 supplies durable names and moving frames, and these say
what the player is shown of them. A sky's content is the SYSTEM's bodies (CON-C14-14), not only the
cell's own occupants as CON-C14-08/09 describe the empty and vector cases; CON-C14-15 is the live form
of CON-C14-09's vector.

- **CON-C14-14 (the sky shows the SYSTEM, not the cell)** `[T][BEH]`
  Built by `UniverseRegistry.skyBodiesAt` + `SystemBodiesProducer.currentByDim`; pinned by
  `UniverseRegistryTest.theSkyFeedUnionsTheSystemWithTheObserversOwnCell` (whose control is that the
  system read ALONE drops the station) and `...interstellarVoidIsFedNothing`. The feed for a
  live cell is `systemBodiesAt(cell)` **unioned with** `bodiesAt(cell)` — the system's bodies, plus
  whatever is keyed at the observer's own cell. This holds for a **body cell and a void cell
  alike**: standing in interplanetary void you still see your star and your system's planets.
  **Interstellar void — a cell no anchor attributes — yields the union's empty case and is fed
  present-and-empty** (CON-C14-08 keeps its meaning there, and that emptiness is the point: the
  space between stars is black).
  *The union is not decoration.* `systemBodiesAt` (`UniverseRegistry.java`) `[V]` returns
  empty for an unattributed cell and aggregates POIs of BODY cells only — its own javadoc says a
  void cell's own POIs are readable via `bodiesAt`/`poisAt` (`:344-353`) `[V]` — so a straight
  swap would erase an orbital station standing in the observer's own void cell. Pinned by `UniverseRegistryTest#theSkyFeedUnionsTheSystemWithTheObserversOwnCell`, `UniverseRegistryTest#interstellarVoidIsFedNothing`.
- **CON-C14-15 (direction AND distance, evaluated live)** `[A][SYS]` The vector is
  `body.absoluteAt(tick)` minus `frames.absoluteOf(observer, tick)`; pinned by
  `SystemBodiesProducerTest.aBodyInAMovingCellIsFedFromWhereItIsNotFromWhereItsNameSays` and
  `...aBodyInTheObserversOwnMovingCellDoesNotDriftAwayFromHim`. Each fed body
  carries the observer→body vector computed through BOTH frames' origins at the broadcast tick
  (C15 ADDR-9), so its magnitude is the true distance at that moment. The wire needs no change: the
  payload is three `long`s per body (`PacketSystemBodiesSync`, C2) `[V]`.
  CON-C14-09's vector is therefore never computed over the static grid. Pinned by `SystemBodiesProducerTest#aBodyInAMovingCellIsFedFromWhereItIsNotFromWhereItsNameSays`, `SystemBodiesProducerTest#aBodyInTheObserversOwnMovingCellDoesNotDriftAwayFromHim`. FOR: CON-C14-14.
- **CON-C14-16 (apparent size falls with distance, clamped both ends)** `[A][BEH]` Built as
  `client/render/planet/ApparentSize` - a pure, GL-free function, so the RULE is checkable without a
  client; pinned by `ApparentSizeTest` (falls, clamped both ends, and nothing in the fed range
  leaves the clamps) and, in PIXELS off the real client, by
  `BoundarySkyRendersInSlotCellTest`'s size leg - which compares two bodies of the SAME kind,
  because a textured billboard and an untextured quad differ in FILL and an area comparison across
  those two reports the size relation backwards. The curve and its four numbers are `tunable` and
  deliberately unpinned. A
  body is drawn at an apparent size that is a **strictly decreasing** function of distance and is
  **clamped to a minimum and a maximum**, so that no fed body becomes invisible and none fills the
  sky. (A fixed screen distance and half-size — `BoundarySky.java`: `BODY_DISTANCE=90`,
  `BODY_HALF_SIZE=6`, `TARGET_HALF_SIZE=10` — would make a moon at 3 000 blocks and one at 59 000
  indistinguishable.)
  *Why the clamps are contract and not polish:* under CON-C14-14 the fed range runs from the nearest
  moon to the neighbourhood bound (10⁹ blocks and more), so an unclamped 1/d law draws the star at a
  fraction of a pixel; and at the near end `BoundarySky.drawBody` already bails at `|dir| < 1e-6`
  (`:141-142`) `[V]`, i.e. a body vanishes exactly when it is closest. Without this clause
  CON-C14-13 and C15 ADDR-13 have no rendering and "the planet crawls away" is unobservable. Pinned by `ApparentSizeTest#sizeFallsAsTheSameBodyRecedes`, `ApparentSizeTest#sizeIsClampedAtBothEnds`, `ApparentSizeTest#everyFedPairStaysInsideTheClamps`.
- **CON-C14-17 (a body says what it is and how far)** `[V][BEH]` `BoundarySky` writes
  the name + `ApparentSize.formatDistance` under each billboard, gated on
  `client/render/planet/SkyLabels` = the `skyBodyLabels` CONFIG flag AND the navigation computer's
  own toggle (default ON, persisted, applied client-side off the console's existing state sync - so
  nothing goes on the wire for it).
  `BoundarySky.labelsDrawnLastFrame` is the client-observable counter, and
  `BoundarySkyRendersInSlotCellTest` reads it to pin "one label per fed body, by default", with
  the same counter sampled before any body exists as its control. **The TOGGLE-OFF leg is not
  pinned** - driving it needs the navigation computer's GUI and the sky fixture has no ship in the
  cell to put one on.
  The sky labels a body at its centre with its NAME and its DISTANCE. The label is a toggle on the
  navigation computer, **default ON** — it is a diagnostic first (it is how a human confirms
  CON-C14-15/16 without a probe) and a player affordance second. The toggle must remove the label
  entirely, not merely dim it.
  *Ruling (2026-08-01), with its cost stated:* the feed and the client store are per-DIMENSION
  (`ServerView.skyBodies`, keyed by slot dim; the client's view of the server replaces it whole on
  every broadcast — `ServerView.java`) `[V]` while the toggle is per-SHIP; the label is a
  CLIENT-side render decision defaulted ON, and the navigation computer's toggle sets it for the
  players aboard that ship. The cost is that the render decision is per-CLIENT while the console is
  per-SHIP - so in a cell holding two ships the last console to sync wins for everyone in that
  world, and CON-C14-06's audience (a passenger, a crew member on the hull, a tier-1 craft, none of
  which owns a navigation computer) has only the default. A per-player channel is a bigger change
  than the affordance is worth.
  The toggle is held by the CLIENT WORLD the console synced into (`WorldRuntime` part,
  `SkyLabels.enabled(World)` / `setConsoleEnabled(World, boolean)`) `[V]`, so it ends with that
  world — a disconnect, a dimension change or a new server starts from the default.
- **CON-C14-19 (the sky shows the CLOUDS around the cell, as directions and nothing else)** `[V][BEH]`
  A star cluster is invisible from outside it — it is
  identifiable only by counting stars — so the nebula wrapping it is the one landmark the universe
  layer has, and a cell's sky draws the ones within reach.
  What crosses the wire is a **unit direction, a half-angle, an appearance and a thickness**, and
  never a position (C2). That asymmetry against the bodies beside it is the clause: a body is a
  destination and needs a range; a cloud has no cell name by design (attribution reads names, not
  matter), has no parallax across a cell — a cloud is hundreds of light years off, a cell is
  under a millionth of one across (`GalacticCoord.CELL` chart blocks) — and is deliberately not a place anything can be flown to. Sending a
  position for one would invent the address the universe layer refuses to give it.
  Consequences that follow and are therefore contract, not appearance:
  - The bearing is computed from the **CELL**, not from a ship inside it (unlike CON-C14-15's
    bodies), because at cloud range the two are the same answer.
  - A cell with no cloud gets a **present-and-EMPTY** list, exactly as CON-C14-08 requires of the
    bodies: absent would leave a stale sky standing.
  - A **DARK** cloud draws AFTER the starfield and the other two before it — a molecular cloud is
    visible because it blots out what is behind it, so drawn behind the stars one of the three
    appearances would silently render as nothing.
  - The feed **drops clouds too small to be a landmark and caps what is left, largest first**; the
    probe reports `seated` beside `drawn` so a working filter cannot be misread as a missing cloud.
  Pinned by `SkyNebulaeProducerTest` (unit: bearing, apparent size, the inside-a-cloud limit, the
  filter, the empty-generator case), `SystemBodiesFeedFollowsTheCellTest` (server: a real generated galaxy seats
  clouds; an authored-only pack seats none) and
  `BoundarySkyRendersInSlotCellTest.aPilotNearACloudSeesIt` (client: the renderer's own
  `nebulaeDrawnLastFrame`, read beside `skyFramesDrawn`, and shown to fail against a renderer that
  draws nothing).

## The invariant the sky must keep

- **CON-C14-20 (the sky states the player's situation)** `[A][BEH]` Every element the cell sky draws is
  an assertion the player is entitled to act on: a highlighted billboard says "this is the body you
  will descend into", a boundary drawn around it says "this is where you cross". A world that draws
  them while they are not true is not a cosmetic defect — it is the sky lying about where the player
  is. This is why CON-C14-02/03 are contract clauses and not render-appearance preferences, and why
  they belong in a client e2e rather than in a playtest.

  The clause is satisfiable by construction only for elements that are per-body and therefore
  falsifiable: a billboard exists iff a body was fed, a boundary is drawn iff that body is a descend
  target. A clause whose example is a decoration (a camera-fixed band) cannot be enforced, and reads
  as coverage anyway.

## Open — do not read as settled

- **A cell sky over a planet was reported once and has not been reproduced.** The SERVER-side world was
  the honest overworld and the feed was not keyed to it (both measured). Not established: which dimension
  the report was taken in, and what the CLIENT's provider for it was at that moment — the probe reads the
  server world, so it cannot answer either.
- **CON-C14-02/03 have no pin yet.** They are `[A]` for that reason. A client e2e that reads the
  client's own sky-owner class is the missing witness; until it exists, a recurrence is only
  visible to a human with a screenshot.
- **CON-C14-14..17 are implemented and pinned at unit AND e2e level**, except
  the label's toggle-off leg (above). `ParkedShipKeepsItsBodiesTest` is the server e2e for the
  feed's content surviving a long dwell, and it carries a control that REMOVES the recorded name -
  without which the whole test stayed green against a derivation re-pointed at the live
  clock. `BoundarySkyRendersInSlotCellTest` carries the size and label legs on the real client.
- **A trap for every client-facing config flag:** a `StellurgyConfiguration` field
  without `@ConfigProperty` is not copied by the config-sync copy constructor, so it reverts to its
  Java default for as long as the player is connected to a server. `skyBodyLabels` defaults ON and
  carries the annotation for exactly this reason.
- **The broadcast is per-player.** Sending every live cell's sky to everyone once a second would
  multiply under CON-C14-14, where an entry is a whole system. A player can only see one
  sky, so `SystemBodiesProducer.broadcastTo` sends him only the dimension he is in - and a
  present-and-EMPTY entry when he is in a slot world with no bodies, because CON-C14-08's distinction
  between "empty" and "absent" is what clears a stale sky.
- **The visibility horizon is the SYSTEM, and that is a ruling, not a tunable.** A radius in blocks
  was considered and rejected: it has no defensible value (small ⇒ the sky is empty, large ⇒ a
  neighbouring system leaks in), whereas the neighbourhood bound already bounds a system. What
  remains tunable is only how apparent size maps to distance (CON-C14-16).
