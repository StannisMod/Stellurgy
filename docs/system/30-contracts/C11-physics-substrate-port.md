---
id: C11
covers: the Stellurgy↔physics-substrate boundary — which code may name engine types, the port's operation inventory (the engine-version migration map), id-vs-positional ship resolution, and optional-capability soft-fail
confidence: mostly [V] — the boundary is BUILT and compiler-enforced today; the version-agnostic and optional-capability clauses are [A] (one backend exists)
owner-subsystem: (none — cross-cutting; the port is integration/vs/{VSBridge,VSIntegration,VSFlightBackend}, entity/IFlightBackend, plus tile/TileAdvancedFlightComputer (the force-controller callback))
see-also: [C9 (ship-control), C10 (ship-stats — STAT reads mass over this port)]
---

# C11 — Physics-substrate port contract, clauses PORT-1..15

`PORT-n` are permanent anchors: never renumber, never reuse. VS is a swappable substrate behind
a Stellurgy-owned port; no new Stellurgy↔VS coupling bypasses it.

Unlike C9/C10 this is **not** a design-stage contract — the boundary already exists and is enforced
by the compiler. Its job is therefore (a) to make the containment invariant *checkable* rather than
remembered, and (b) to be the **engine-version migration map**: every operation Stellurgy needs from any
physics substrate, in one place, so a future backend swap has a finite, known surface.

**Terms.** **substrate** = the physics engine (Valkyrien Skies today, vendored in-tree at
`valkyrienskies/`, imported from tag `1.12.2-1.1.7`, `valkyrienskies/NOTICE`) `[V]`.
**port** = the Stellurgy-owned code permitted to name substrate types. **facade** = `VSIntegration`, the
Stellurgy-facing API that carries no substrate types. **business logic** = everything else (space/, crew,
tiles, events, GUI, commands). **ship id** = the substrate's ship identity as a string
(`VSBridge.shipIdManagingBlock`) `[V]` — and it IS Stellurgy's own durable
`shipId` (C10), the same value, not a second one (PORT-13).

## Clauses

**Containment — who may name substrate types**

- **PORT-1** `[T]` Only four roles may import `org.valkyrienskies` in `src/main`: the port package
  (`integration/vs/`), the mixins that weave into substrate internals (`mixin/`), the mod entry
  point that gates/registers the integration (`Stellurgy.java`), and the ONE tile the engine calls back
  into (`tile/TileAdvancedFlightComputer`, the force-controller callback). Pinned by
  `test/unit/VSBoundaryContainmentTest.java`, which asserts by ROLE (not a file list) and carries an
  instrument-fire control so a broken scan cannot read as a pass. (The test source set names engine types
  freely; the rule is about business logic.)
- **PORT-2** `[V]` The bridge is **package-private** — `final class VSBridge`
  (`integration/vs/VSBridge.java`), referenced only from classes of its own package (the facade
  `VSIntegration` and the port's hull / mass classes). Business logic therefore *cannot* call it even by
  accident; PORT-1 is enforced by the compiler, not only by review.
- **PORT-3** `[V]` The facade carries no substrate types: `VSIntegration` imports `org.valkyrienskies`
  **zero** times. The rule is stated in the bridge's own javadoc
  (`VSBridge.java`): *"Every reference to an `org.valkyrienskies.*` type lives in this package's
  bridge classes, never in `VSIntegration`."*
- **PORT-4** `[V]` The bridge class is loaded only when `VSIntegration.isAvailable()` is true
  (`VSBridge.java`), so substrate imports never need to resolve on aStellurgy install without the
  substrate. Absence of the engine is a class-loading concern, never a business-logic branch.

**Operation inventory — the migration map**

- **PORT-5** `[V]` The port surface is the following operation groups; a backend swap must supply all
  of them and nothing else. (Signatures in `VSBridge`; the Stellurgy-facing names are `VSIntegration`'s.)

  | group | operations (examples; the exact set is `VSBridge`'s non-private members) |
  |---|---|
  | frame conversion, entity-keyed | `toShipFrame`, `toWorldFrame`, `rotateToShipFrame`, `rotateToWorldFrame` |
  | frame conversion, **id-keyed** | `toShipFrameFor`, `toWorldFrameFor`, `rotateToShipFrameFor`, `rotateToWorldFrameFor`, **`renderToWorldFrameFor`** |
  | attitude / transform reads | `getShipAttitude`, `shipAttitudeAt`, `shipAttitudeForId`, `shipWorldPosition`, `seatWorldPosition`, `shipDownDirection` |
  | velocity reads | `shipLinearVelocity` (blocks/second), `shipVelocityAtPointFor` (blocks/**tick** — converted in the port; there is no per-second variant), `shipAngularVelocityById` |
  | ownership / identity | `shipIdManagingBlock`, `shipIdsAt`, `shipyardBoundsAt`, `subspaceStayRegion`, `shipBlockCount` |
  | kinematic transform-set | `teleportShipTo` (guard-flag recipe — see PORT-9) |
  | lifecycle / assembly | `assembleTier2Ship`, `assembleBuiltTier2Ship`, `parkShipAt`, `unparkShipAt`, `declareDeparture`, `loadAllShipsCounted`, `ensureShipPhysicsEnabled`, `loadedShipCount`, `queryableShipCount` |
  | substrate configuration | `shipYPositionMaximum(World)`, `coverShipAltitudeBand(World, floor, ceiling)` (per-world band, not config statics) |
  | entity association | `clearEntityShipAssociation`, `entityShipMovementData`, `transformConsistency` |
  | capability query | `hasShipSupport` |
  | force / flight application | `IFlightBackend.applyFlightState`, `ownsTransform` |

- **PORT-6** `[V]` The port distinguishes the substrate's **render** transform from its **tick**
  transform (`renderToWorldFrameFor`, `VSBridge.java`). Any operation whose result is compared
  against something client-rendered must say which transform it used; the two diverge on a moving ship.

**Ship resolution — id, not position**

- **PORT-7** `[V]` Read/convert operations expose an **id-keyed** variant (the `…For` family).
  Positional resolution is the MINTING event at first contact only; per-tick consumers resolve by id.
  Rationale: positional first-match is non-deterministic under overlapping ship AABBs.
- **PORT-8** `[V]` The **command and state-read** family is id-keyed — `pushShipById`, `spinShipById`,
  `shipStateById`, `shipAngularVelocityById`, `enableShipPhysicsById` — and **every caller is
  `command/test/TestProbeCommand`**: zero production callers. It is a **probe surface**, not a
  production hazard. The one positional lookup left, `VSBridge.shipUuidNear`, answers only for a PARKED
  ship (a parked ship does not move, and hyperspace lanes are 2048 blocks apart, so a radius well under
  half that spacing admits no neighbour). A PRODUCTION command path must be id-keyed (PORT-7); adding
  a production caller of the probe family is a violation of this clause.

**Wrapping substrate operations**

- **PORT-9** `[V]` A port operation that WRITES substrate state must reproduce the substrate's own
  write-recipe, not compose it from public setters: a naive `setShipTransform` is silently overwritten
  by the physics loop in the same tick — the working recipe needs the force-transform guard flags,
  copied from the substrate's own `MainCommand.teleportShipToPosition` (`teleportShipTo`,
  `VSBridge.java`). Disassemble the upstream recipe before wrapping.
- **PORT-10** `[V]` Substrate physics runs **off the main thread**, and the substrate updates ship mass /
  centre-of-mass / MOI **incrementally on every block change** (`MixinChunk.java` →
  `ShipDataMethods.onSetBlockState` → `BasicCenterOfMassProvider`). Stellurgy keeps no second mass
  accumulator beside it: its own mass frame reaches the physics record only through the one boundary writer
  `ShipInertiaWriter` as the authority over the engine's deltas (C10 STAT-20), and C10's stat vector is a
  Stellurgy-facing VIEW, not a physics input.

**Version-agnosticism & optional capabilities**

- **PORT-11** `[A]` A capability Stellurgy wants from the substrate is FIRST an explicit port addition, then a
  backend implementation — never a reach-in from the call site. This is what keeps a version migration a
  backend swap. **Precedent, and the standard for when an interface is warranted**: `IFlightBackend`
  (`entity/IFlightBackend.java`) was extracted because a SECOND implementation genuinely exists
  (legacy entity-move vs ship-physics) `[V]`. A single-implementation interface is ceremony;
  extract when the second backend arrives.
- **PORT-13** `[V]` **ONE SHIP, ONE IDENTITY.** A craft's substrate identity IS the durable name its
  own flight computer carries; there is no second value and no translation. Enforced at the single
  assembly entry point — `VSIntegration.assembleTier2Ship(world, pastedSnapshot, x0, y0, z0)`
  (`VSIntegration.java`) SCANS the footprint for the flight computer, anchors there, reads
  `TileAdvancedFlightComputer.getOrCreateShipId()` and passes it down as BOTH the identity and the
  durable name. A footprint with no flight computer is REFUSED (loud log, null): blocks with no
  computer are not a tier-2 craft, and one assembled nameless would take a substrate-minted id
  nothing else in the game knows. **The signature is the mechanism**: it takes no anchor and no identity
  (each would be a way for a caller to break the equality without seeing that it had) and no extent — the
  extent is DERIVED from the snapshot every caller has just pasted `[V]`, never passed, so there is no
  second place for the snapshot's own layout to be wrong (an anchor on a deck block would return two ids
  for one ship — maintainer ruling 2026-09-11). A name that must cross a world boundary crosses IN THE
  BLOCKS, including the restored-transit paste, which therefore mints no fresh identity.
- **PORT-14** `[V]` **A SHIP IS THE BLOCKS THAT WERE PASTED.** The same footprint PORT-13 scans for the
  flight computer BOUNDS the substrate's block search: `assembleInFootprint` builds it from the
  snapshot's own extent (`VSIntegration.java`), the bridge queues it with the spawn
  (`VSBridge.java`), and the search does not expand outside it (`SpatialDetector.java`) — so a
  neighbour neither joins the ship nor counts toward the substrate's size cap. The footprint is a
  REQUIRED argument of the one queueing method (`WorldServerShipManager.java`); the substrate has no
  unbounded player-assembly path. The search is 26-connected
  (`WorldServerShipManager.java`, `checkCorners = true`), which is why the footprint bound is
  needed: a diagonal neighbour (snow on a pad's rim, foliage) would otherwise take the pad, tower,
  builder and plug with the ship. Pinned by `TierTwoCraftFlightModelGroupTest#aShipAssembledOnASnowedOnPadLeavesTheLaunchSiteBehind`
  `[T]`, red-witnessed with the bound disabled.
  **There is no assembly lift, no cut and no snapshot copy:** the
  assembler hands its scanned region to `VSIntegration.assembleBuiltTier2Ship`, which FITS it to the
  non-air blocks itself (`fittedToBlocks`) and leaves the world as built for the substrate to relocate,
  so the caller does no extent arithmetic and nothing is copied. The snapshot entry
  (`assembleTier2Ship`/`pasteTier2Ship`, footprint from the snapshot's size) stays for crossings and
  probes, which have a pasted snapshot anyway. A ship's flight computer is at its built address — `#anAssembledShipStaysWhereItWasBuilt` `[T]`, measured
  offset (0,0,0) and a resting hull moving 0.0 blocks over 41 ticks.
  **A refusal is decided at the press, not dropped at the drain.** The substrate's drop rule is ONE
  method (`WorldServerShipManager.refusalOf`), read by the drain and by `refusalFor`, which runs the same
  search on the world as it stands; the facade asks it through `VSIntegration.builtTier2ShipRefusal`
  before handing a craft over, and the assembler shows `SHIP_TOO_LARGE` (or `FAIL_CUT` when the snapshot
  holds no flight computer) instead of "finished" and no ship `[V]`. Test gap: no test pins the
  `SHIP_TOO_LARGE` refusal, because seeding the substrate's size limit needs a harness probe that writes
  that configuration field through its declared writer. The substrate's other refusal, bedrock, cannot
  arise on this search: `FIND_ALL_BLOCKS` takes every block but air and fluid, bedrock included `[V]`.
- **PORT-15** `[V]` **A LOADED SHIP HOLDS ITS CHUNKS.** While a ship's physics object lives, every
  chunk of its claim stays in the world, whoever is or is not in that world; it is released only by the
  ship unloading. "Loaded" therefore means the whole ship — its simulation, its blocks and its ticking
  tiles — never a physics object over chunks the world has dropped. The physics object reads its blocks
  from its OWN references to the claim's `Chunk` objects, taken once at load
  (`ClaimedChunkCacheController.java`) and used by collision, water and mass; a claim chunk the world
  drops behind it splits the ship in two — physics keeps the dropped object, the world re-loads a new one
  at the next access, edits land in the new one, and the tiles in it stop ticking. **Mechanism**: the
  substrate's one chunk-provider hook (`MixinChunkProviderServer.preTick`) takes every chunk named
  by `WorldServerShipManager.getHeldShipChunks` — the claims of the loaded ships and of the ships
  loading in the background — out of the provider's unload set at the head of each tick; a ship that
  unloads queues its claim (`PhysicsObject.unload`) and leaves `loadedShips` in the same pass
  (`WorldServerShipManager.java`), so the next tick no longer holds it. **Why the hook is needed at
  all**: nothing else holds these chunks, and the default config only hides it — the substrate loads a
  ship only with a player near it in the same world, and a player's presence stops vanilla's mass unload.
  Without one, a world whose provider forbids respawning queues EVERY loaded chunk for unload on every
  tick (`PlayerChunkMap#tick`), which is a space slot holding a permanently loaded ship
  (`VSConfig.SHIP_LOADING_SETTINGS.permanentlyLoaded`, the probe's `vs permaload`) and nobody in it.
  A hook that held only the background claims would let such a ship keep its tiles only where
  something happened to touch their chunk every tick: measured on a settled 25-block craft, 691 chunks of
  its slot dropped at 100 a tick, the AFC's chunk alone surviving. UNPINNED: maintainer ruling
  2026-10-07, *"не user-facing поломка, а херовый контракт"* (not a user-facing breakage but a bad
  contract) — fixed without a red test.

- **PORT-12** `[A]` AStellurgy feature MAY depend on a capability the current backend lacks, provided the port
  exposes a **capability query** (`hasShipSupport` is the built instance, in `VSBridge` `[V]`), Stellurgy
  gates on it at ONE point, and the fallback is a **COMPLETE** behaviour — never a half-state another
  system can silently assume away. Cheapest correct case: the fallback IS today's baseline (ship-ship
  collision absent ⇒ ships pass through, as they already do).

## Known violated / residue

- **Naming is inconsistent across the attitude readers** — `getShipAttitude(World, BlockPos)` (the only
  `get`-prefixed member), `shipAttitudeAt(World, x,y,z)` and `shipAttitudeForId` are three conventions
  for one concept. Cosmetic; it is not a semantic distinction.
- **No interface over the bridge surface.** Deliberate, per PORT-11 — one backend exists.
