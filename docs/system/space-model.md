# Space model — coordinates, bubbles, transit

**Scope.** Layer 2 (ship-space realization) of a 1:1-scale space subsystem for tier-2 Valkyrien Skies
ships: coordinates, cells, the slot pool, the crossing, transit hosting, the time base and the
ownership of the stack. The coupled **Layer 1 "universe"** model (galaxy / systems / discovery /
persistence) is in [universe-model.md](./universe-model.md). Built and tested: coordinates, cells,
slot pool, transit, the per-ship VS crossing, the production lifecycle and the config knobs. Design
only (not built): the star as cell content (§6), the pool-growth policy (§10 "Pool policy") and the
jump hazard model (§10).

> Epistemic note: "Elite" labels a *target UX* (1:1 scale, instancing, pop-in, a hyperspace transit
> mode), taken from observable behaviour and general large-world engineering, not from any
> proprietary internals. Every constraint below stands on MC / Forge / VS facts.

---

## 1. Player-facing model

Two states, ONE travel mechanic:
- **In a bubble** — normal play: a real, live, walkable local world (a VS ship you walk on, a station,
  asteroids, other ships). "Being somewhere in space."
- **In transit (hyperspace)** — the travel mechanic for any jump long enough to BE one (there is no
  separate "supercruise"). A long *automatic* flight to pre-set coordinates, computed and flown by the
  onboard computer; reach, speed and power are ship stats. No external world is loaded during transit,
  but the **ship itself stays live** (§3: a ship is always inside a loaded bubble).

**A jump the drive completes in at most `ShipTransitManager.DIRECT_CROSSING_MAX_TICKS` ticks is not
flown at all** — it is performed as ONE cell→cell crossing by the machinery that carries a ship across
a cell face (`CellCrossingController`), with no lane, no park, no snapshot and no `IN_TRANSIT` row.
The threshold is DERIVED: `DEPARTING_TICKS + ARRIVING_TICKS` (`ShipTransitManager.java:992`), the point
below which `phaseOf` never reports `CRUISING` — a flight with no middle. One predicate
(`ShipTransitManager.isDirectCrossing`, `:1006`) is read by the console's forecast and by the
departure, so the mechanism quoted is the mechanism flown. What is lost is lost by construction: a
short jump carries no transit hazard, cannot be intercepted in the lane, and is not a place the crew
walks around in. The rule keys on DURATION, never on route: a fast enough drive makes an interstellar
leg short too.

**Both routes arrive OFF their destination** `[V]`: a jump aimed at a cell holding bodies comes out on
the widest `ShipEntryController.entryRingAround(body)` of that cell's bodies —
`max(ENTRY_RING_BLOCKS, 2 × DescentShell.radiusAround(body))` — so the arrival is strictly outside every
body's descent shell (`SpaceSubsystem.arrivalStandoff`, `SpaceSubsystem.java:398`; wired into the
hyperspace arrival at `ShipTransitManager.java:652` and into the direct crosser at
`SpaceSubsystem.java:117` / `:128`). A cell with no body is returned untouched. The direct route passes
the standoff and never the raw target: a short jump at a body would land ON it, inside the descent
radius, and be taken down on its first settled tick with nobody asking; pinned by
`VSShortJumpCrossesDirectlyTest.aShortJumpAtABodyArrivesOnItsStandoffRingAndStaysInSpace`. The ring is
body-derived, never a flat 1 024 / 512: a flat ring would arrive inside the trigger of Luna (shell
7 066) or Earth (25 913); pinned in the milestone loop, `M1PlanetToPlanetMilestoneE2ETest` leg 7b.

Encounters happen when entities share a bubble; another party is seen only once you share a world —
a **pop-in at the cell boundary**.

## 2. Coordinates: sectorized fixed-point

Two decoupled coordinate systems:
- **Absolute galactic coordinate — sectorized fixed-point, not `double`.** Per axis a `long sector`
  index plus a local offset; `absolute = sector·CELL + local`. Exact integer arithmetic, **uniform**
  precision everywhere, unbounded range. A `double`'s spacing grows with magnitude
  (`ULP ≈ magnitude·2⁻⁵²`, ~220 km at galactic scale) and drifts when integrating position over a long
  transit; fixed-point is uniform and exact.
- **The sector grid IS the bubble grid** (§3): `CELL = 32,000,000` blocks (`GalacticCoord.java:53`; a
  32M cube, ±16M local offset). Distances are computed on the (small, near) local delta cast to
  `double`, which is precise because the delta is small.
- **Local MC frame — separate, near the cell origin.** MC blocks are `int`. Content that needs
  precision (stations, docking, building) is **snapped to the cell centre**; the rare empty outer
  reaches never carry precision-critical activity.
- **The cell is also the ADDRESSING RESOLUTION.** A body closer to its star than one cell shares the
  star's cell, and a cell is a destination, so it cannot be a separate address and is not generated.
  The floor is derived, not written down:
  `AstronomicalBodyHelper.MIN_ADDRESSABLE_ORBIT_UNITS` (`AstronomicalBodyHelper.java:132`) =
  `ceil(CELL / BLOCKS_PER_DISTANCE_UNIT)` distance units, about 0.05 AU at the shipped cell. Bodies inside it — contact binaries, the tightest hot Jupiters
  — are not expressible as destinations of their own.

**Why the cell can be 32M — what was measured** (2026-08-11 and 2026-08-12; vanilla / Forge behaviour,
`[A]`: no test in the tree re-proves these rows and they cite no source line, so they are dated
measurements to re-establish by hand if the cell bound or the render path moves). Each subject was
SPAWNED at the coordinate rather than moved there:

| mechanism | 2M | 8M | 16M | 24M | 28M |
|---|---|---|---|---|---|
| chunk generation (real biome, plausible `topY`) | ok | ok | ok | — | ok |
| block storage (place, read back) | ok | ok | ok | — | ok |
| entity doubles (spawn at `x.55`/`x.60`, read `posX`) | exact | exact | exact | — | exact |
| sub-block position round trip (asked → server → client) | exact | exact | exact | exact | — |
| render (camera walked 0.05 blk/step, frames compared) | clean | clean | clean | clean | — |

- **Sub-block round trip:** offsets `{0, 0.05, 0.1, 0.25, 0.5, 1.0}` from one base read back with server
  error `0.0000` and client-server delta `0.0000` at every rung including 24M.
- **Render:** 12 camera steps of 0.05 block per rung all landed, with no byte-identical frames anywhere
  (`x = 0` as the control). A float in the absolute render path would have quantized to ~2 blocks at 16M;
  the viewer position is subtracted in `double` before the cast.
- **A connected player walks, stands and collides identically out to 24M** (client with a real held `W`):
  40 ticks walk 8.37–8.59 blocks at every rung (vanilla 0.2159 blk/tick predicts 8.6), the collision
  stand-off from a wall is 0.3000 (half the 0.6-wide player box), client and server `posX` identical.
  `VSShipExtremeCoordinatesTest` `[T]`. `2²⁴ = 16 777 216` is not a wall: the vanilla symptom documented
  there (sound positioning) does not touch movement or collision.
- **A tier-2 ship** assembled at 16M flies and keeps its client-rendered rider exactly as one at the
  origin, and so does a ship whose blocks live at subspace X 19,200,001 under
  `CHUNK_X_START = 1,200,000`.
- **Known cost, accepted:** the far shell crosses 2²⁴, where vanilla documents sound-positioning
  degradation — cosmetic, unmeasured.

**The wall that exists is Valkyrien Skies', not Minecraft's.** `MixinNetHandlerPlayServer` cancels any
teleport into the reserved "shipyard", and `ShipChunkAllocator` defines that as the half-open quadrant
`chunkX >= CHUNK_X_START - MAX_CHUNK_RADIUS && chunkZ >= CHUNK_Z_START - MAX_CHUNK_RADIUS`
(`ShipChunkAllocator.java:47-59`) `[V]`. A half-cell `H` needs `CHUNK_X_START >= H/16 + 1599`; the
shipped value is `1,200,000` (block X 19,174,416). The vanilla server's `moved too quickly!` check does
not apply to a teleport: every dimension change arms `invulnerableDimensionChange` until the client
acknowledges it.

**Open at the shipyard move.** Two tier-2 contract tests were reproducibly red at
`CHUNK_X_START = 1,200,000` and green at `320000` (6 of 6 moved runs, 0 of 6 baseline) while the
far-subspace spike passed at the same magnitude; the cause is unexplained. The allocator's cursor
`lastChunkX`/`lastChunkZ` is serialized with the ship registry (VS's mapper serializes fields) while
`CHUNK_X_START` is `static final`, so an existing world restores a cursor outside the current predicate
and `VSChunkClaim.writeToNBT` stores absolute chunk coordinates; the constant therefore cannot move once
a world exists `[V]`.

## 3. The bubble grid: grid cell + refcount

Space is a **grid of `CELL` cells**; a **bubble = one loaded cell's world**.
- **Same cell → same world → entities meet.** Deterministic: the frame is the cell, a fixed function of
  coordinate — no race, no per-entity anchoring.
- **Lifecycle = refcount, no anchor handoff.** First arrival in a cell → instantiate its world (frame
  fixed by the grid, independent of any occupant); join → refcount++; leave (jump / dematerialize) →
  refcount−−; refcount 0 → eligible for unload. Because the frame is grid-fixed an occupant leaving
  never needs re-anchoring; there is no "who hosts / host-handoff / dissolution" protocol.
- **Cost accepted:** a **seam** at cell boundaries (two entities either side of a boundary do not see
  each other until one crosses) — rare with big cells and centre-snapped content.
- **A ship is always in a loaded bubble** (its current cell, or — mid-jump — the shared **hyperspace
  world**, §10 "Transit hosting"), so tile entities tick and passengers walk the whole transit. The only
  freezes are the two momentary pack/paste **crossings** (§4): origin→hyperspace at departure and
  hyperspace→target at arrival, each sub-second.

## 4. The crossing: the moment of transition

Every bubble↔bubble / planet↔bubble transition (jump, or flying across a cell boundary) is the
momentary pack/paste helper:
1. enumerate on-board entities by the shipyard AABB + `EntityShipMovementData` (VS keeps no per-ship
   roster);
2. `StorageChunk.cutWorldBB` the shipyard AABB → snapshot (block states + TE NBT as-is);
3. VS `removeShip` + `deleteShipChunksFromWorld`;
4. move snapshot + entities to the target world via `BasicTeleporter` + the
   `TransitionEntity`/`PlanetEventHandler` queue;
5. `pasteInWorld` at the arrival point → `queueShipSpawn` (re-VS);
6. re-`fixEntityToShip`.

VS-independent during the instant (a mid-crossing save is a StorageChunk, which opens without VS).
**StorageChunk is the moment-of-transition and offline form only** — never a carry-frozen-for-the-whole-
trip transport (that would freeze tile entities and drop entities).

> **A CROSSING MAY NOT FAIL** (maintainer ruling, 2026-07-30). The six steps are the whole design and
> there is deliberately no seventh describing what happens when one of them does not work, because none
> of them is allowed not to work. A paste into a cell is a block write; it has no precondition a correct
> caller can miss.
>
> - **"Momentary" describes the FREEZE, not the operation.** The paste is synchronous, but VS's assembly
>   is queued and its `PhysicsObject` loading is a POLICY (player proximity), so a step that waits for
>   either is waiting on something the design does not model.
> - **A recovery branch is a defect report, not a mode.** `ShipTransitManager.tickTransits` finishes a
>   stalled arrival from the transit's own snapshot and, failing that, keeps the ship in transit rather
>   than dropping it — and every such path logs at ERROR and tells the crew to report it. Do not tune a
>   retry budget to make such a symptom go away; delete whatever the arrival is waiting for.

**Readiness is read off the crossing's own anchor, never off loadedness.** Loadedness is not progress:
VS re-decides it every tick from PLAYER PROXIMITY (`WorldShipLoadingController.determineLoadAndUnload`),
and an arrival is by definition the case with nobody aboard and nobody near. VS's spawn relocates the
pasted blocks and DELETES them from the world synchronously inside one tick
(`WorldServerShipManager.spawnNewShips`), and the anchor is always the assembly's seed
(`SpatialDetector.calculateSpatialOccupation`), so `world.isAirBlock(anchor)` is exactly "my ship has
been claimed" — one exact position Stellurgy itself wrote, no lookup, no load. `[V]`

**The crew re-seat reads the ship's TRANSFORM, which lives on `ShipData` and outlives every load.**
`CrewTransfer.reseat` moves a player into the destination only after his seat resolves, so the crew who
would keep the ship loaded are the ones it is carrying; the lookup goes through the load-independent
`ValkyrienUtils.getShipManagingBlock`, and only the shipyard CHUNKS it scans are force-loaded
(`PhysicsObject.unload` drops those, which is what would otherwise hide the seat tiles).
**Stellurgy force-loads a ship nowhere in a crossing.** `[V][T]`

## 5. Slot pool + cell store + GC: two tiers

The **physical loaded world (slot)** is decoupled from the **logical cell (coord + content)**. A fixed
pool of slots is the working set; a coord-keyed disk store is the backing store. (A fixed pool de-risks
both dynamic-dimension creation and VS-in-a-new-world; runtime dimension creation is the alternative
kept in reserve, §10 "Rejected".)

- **Tier 1 — slot pool (working set, ticking).** N pre-registered `WorldServer`s at startup ("blank
  canvases"), rebindable to any cell. Config `spaceCellPoolSize` (default 10, range 1–64,
  `StellurgyConfiguration.java:100`). **These are the only worlds that tick** (main thread), so N is the
  direct performance knob.
- **Tier 2 — cell store (backing, on disk).** Coord-keyed, **sparse** persistence of **modified** cells
  only (mined asteroids, parked ships, built stations). Unbounded by the pool. Empty / regenerable cells
  are **not** stored (regenerated from `(seed, cell)`, discarded on evict). A cell stores only its
  non-empty / modified chunks + entities / VS ships.
- **Binding (controller).** `materialize(coord)`: cell loaded in a slot → use it; else pick a free / LRU
  slot (evicting its current cell first) → load target (regenerate if clean / new, else deserialize
  from the store). Refcount per loaded cell; 0 → LRU-evict candidate.
- **Rebind mechanism: a retargetable `SaveHandler`.** A slot's `SaveHandler` is pointed at the target
  cell's directory, so MC Anvil + VS's own per-world save do the load / store; empty regenerable cells
  use a directory that is **deleted** on evict. Verified: a ship (ShipData + shipyard chunks + TEs)
  round-trips A → B → A through a slot rebind and VS keeps ticking.
- **Eviction ≠ deletion.** Evict a slot: cell dirty → **flush to store** (keep) → clear slot; clean →
  discard. GC: **delete from store** (gone).
- **Dirty tracking.** A cell is dirty once it diverges from its procedural seed (block change / ship
  parked / station). `dirty` is load-bearing only for the first flush — unload always re-saves a stored
  cell.
- **GC (anti-save-bloat, over the store, independent of slot eviction).** Config
  `spaceCellGcPolicy = age | count | both | never` (default `both`), `spaceCellMaxAgeTicks` (default
  1 728 000 = 24 h at 20 tps), `spaceMaxStoredCells` (default 4096) (`StellurgyConfiguration.java:101-103`):
  *age* deletes stored cells not visited for longer than the max age; *count* deletes oldest (LRU by
  last visit) until under the cap; *protection*: a currently-loaded cell is never collected and a
  `claimed` cell (a player-built station) is exempt. Requires a per-cell **last-visit timestamp**.

## 6. Cheap worlds

- **Void by default:** a `ChunkGeneratorSpace` returns all-air chunks — near-free.
- **Content by stamping:** asteroids / dust = seeded placement of small prefab templates
  (`ExtendedBlockStorage` copy), no vanilla noise or features.
- **Regenerable ⇒ no save:** deterministic from `(seed, cell)`; unmodified cells are discarded on evict
  and regenerated identically. Only **modified** cells hit the store (§5).
- **The star as cell content (design, not built).** A star's anchor cell is not empty void: the star
  has a centre (= the anchor-cell centre via `CellWorldMapper.poseWorldOf`), a radius, three nested
  contact shells (approach / corona / inside), a continuous per-tick flux the shields absorb,
  directional plasma hull damage when they fall, a corona-collector multiblock yielding a `coronal
  plasma` fluid, and an active gravitational pull (`wellStrength(mass)`). None of these exist in the
  source today.

## 7. Threading: main-thread pool; thread-per-bubble rejected

The actor idea (one owner thread per bubble, cross-thread calls queued) is infeasible on MC 1.12.2 — a
`WorldServer` is not an island:
- **Forge event bus:** every block update / entity spawn / world tick fires GLOBAL events, so arbitrary
  other mods' handlers would run on the bubble thread touching global and other-world state.
- **Player list + netty** (packets, entity tracking) are global.
- **`DimensionManager` / `MinecraftServer` singletons** (world-by-id, main-thread task queue, lighting
  temp state).
- **VS thread guards:** VS throws `CalledFromWrongThreadException` expecting THE server main thread; VS
  ships are the bubble content.
- The vanilla model is the opposite: one main thread, with `IThreadListener.addScheduledTask` funnelling
  work INTO it.

**What we do (VS's own model):** the pool's N slots tick on the MAIN thread (N bounded → cost bounded;
empty space is not a world), idle slots are tick-gated, ISOLATABLE work is offloaded to workers and
spliced on main (chunk generation, VS physics, pack/paste serialization, content precompute), and the
controller bookkeeping runs off-thread with a **queue back to main** for world ops.

### Time base — INV-SPACE-01 (maintainer-ratified 2026-07-23; the clock itself is C16's)

> **Every space-subsystem elapsed-time computation — transit progress, capacitor charge, drive
> cooldown, scan and re-localization deadlines — reads the SPACE CLOCK (C16 CLOCK-1). Never real time
> (`System.currentTimeMillis` / `Instant.now`), never a slot dim's own clock, never a world's total
> time.**

The maintainer's model: **Δ-catch-up exists for a pilot who logged off while the server keeps running;
if the server is off (including a single-player world nobody has opened), nothing ticks at all.** The
space clock — the subsystem's own counter, advanced once per server tick and persisted (C16 CLOCK-5) —
already discriminates the two cases:

| Situation | Space clock | Transit |
|---|---|---|
| Server up, crew online | advances | advances |
| Server up, crew offline (`spaceTransitOfflineProgress=always`) | advances | advances — the case Δ-catch-up exists for |
| Server up, the ship's zone tick-gated / evicted | advances | frozen while gated, Δ-catch-up on resume |
| Server down / SP world closed | frozen | frozen |

- There is no `currentTimeMillis` / `nanoTime` / `Instant.now` anywhere under `space/`. `[V]`
- The clock is **gamerule-immune**: it is not a world's time, so a server running with the day cycle off
  — or a per-dimension gamerule — cannot freeze a transit (vanilla's own counter is advanced
  unconditionally, `WorldServer.java:223`, but is not what is read). `[V]`
- A slot dim's own clock is the wrong source: pool slots are recycled and their own clocks may differ or
  reset, which is why Stellurgy wraps every dimension except the overworld in a `WorldInfo` of its own
  (C16 terms). `[V]`
- `MinecraftServer.tickCounter` (`MinecraftServer.java:120`, incremented `:732`) is **not persisted** and
  restarts at 0, so a Δ spanning a restart would be garbage and negative; the space clock is persisted
  with the ship ledger (C16 CLOCK-5.4). `[V]`
- A single-player world is the pause case by construction (the integrated server ticks only while the
  world is open); a server-hibernation setup that stops ticking an empty server produces the same
  pause. `[A]`

**Failure mode to watch:** a change that reads a slot dim's clock. Δ would go negative or jump on every
pool rebind, and the `Δ < 0 ⇒ clamp to 0 + WARN` rule would mask it as "save corruption" rather than a
wrong clock. The clamp is a guard, not a diagnosis. `[A]`

**Lazy catch-up doctrine** (maintainer-ratified; the complement of tick-gating). Our subsystem processes
store `lastTicked` and advance by `Δ = curTick − lastTicked` in one step when re-observed. This needs
autonomy over the gap and a closed form: `transit pos(t)` is linear with a computable `arrivalTick`, so
the transit manager is an event QUEUE, O(1)/tick; capacitor charge and cooldowns saturate (compute on
read, never tick); scan timers are deadlines; per-tick randomness is one Binomial(Δ,p) sample. Mid-gap
events execute AT the catch-up moment. Time base = the space clock only (monotonic, persisted;
C16). Third-party cargo TEs pause (no closed form; vanilla-consistent
unloaded behaviour). Invariants: a process is in the queue XOR ticking; Δ<0 clamps to 0 with a WARN;
catch-up applies only to the unobserved. `CellMeta.lastVisitTick` plus the injected clock use this
pattern.

## 8. Reuse of existing Stellurgy

- Absolute coordinate = `space.GalacticCoord` (sectorized fixed-point 3D), NOT a widened
  `SpacePosition`: `SpacePosition` is the legacy `double` solar-map render coordinate — wire-serialized
  (`PacketMoveRocketInSpace`), bound to `StellarBody` / `DimensionProperties` / spherical render helpers,
  and the very solar-cruise layer this model avoids for ships. It is untouched; the galactic frame is a
  clean value type.
- Planet `DimensionProperties` → each carries its cell coordinate → **planet dims are POIs** in the grid
  alongside pool bubbles.
- `SpaceObjectManager` (stations) → stations are persistent (claimed) cells.
- Rocket reuse: altitude orbit trigger, `TileGuidanceComputer` targeting, station
  `getSpawnLocation` / `getNextLandingPad`, `BasicTeleporter` + `TransitionEntity` for entity + rider
  moves, `StorageChunk.cutWorldBB` / `pasteInWorld`.
- **Avoided:** the rocket solar-map cruise (`getInSpaceFlight`, client-motion-driven) — it fights VS
  server-authoritative physics; ships travel by the automatic transit only. The solar-map cruise is
  retired for ships; tier-1 rocket travel moves to the station-as-ship model (§10).

### Ownership of the stack — INV-SPACE-02 (maintainer-ratified 2026-08-25)

**The server's space subsystem belongs to the mod object, and there is exactly one way to reach it.**
`SpaceSubsystem` is a state object with a public constructor — six services wired by one constructor,
living and dying together — so it is not a singleton and may not decide which instance of itself is the
server's. `Stellurgy` holds that instance in a private, non-static field written only by its four
server-lifecycle handlers, and `Stellurgy.spaceSubsystem()` (`Stellurgy.java:350`) is the whole read
surface: a caller takes the object and reads the services off it. `[V]`

Three consequences, each separately falsifiable and pinned by `SpaceSubsystemOwnershipTest`:

1. **No static `current`, and no per-service static accessor.** With them, two subsystems could be alive
   at once and which one a caller saw would depend on WHICH accessor it used.
2. **No writer from outside, so no swap.** There is no setter and no install / restore handle; a scenario
   cannot leave a running server without a subsystem.
3. **A fixture ARRANGES the server's subsystem, never substitutes for it.** `stellurgytest space
   entry-setup` appends scratch slot worlds to the pool the server binds from; `entry-clear` gives back
   the ledger rows and cells the scenario made. A fixture that needs an isolated instance
   (`transit-setup`) builds one and ticks it by hand, binding through a slot binder narrowed to the slots
   it appended; it can never be mistaken for the server's.

## 9. The controller

`SpaceManager` generalizes `SpaceObjectManager`: coord↔cell resolution, the pool (LRU / refcount), the
cell store + GC, per-slot tick cadence, the worker-offload + main-thread splice queue. Its verbs:
`materialize` (resolve cell → get-or-load slot → place, with VS re-spawn), `dematerialize` (refcount--,
empty → evict-eligible), `bindSlot` (retarget `SaveHandler` → load: regenerate or from store),
`evictSlot` (dirty → flush, clean → discard), `gc()` (policy over the store); transit position is
advanced by `ShipTransit`.

Parts and what pins them:
- `space.GalacticCoord` — sectorized fixed-point value type (absolute↔sector/local identity, canonical
  local range, cell identity / key, exact cross-cell distance, drift-free integration, NBT round trip).
- `space.SpaceManager` — `materialize` / `dematerialize` (refcount + lazy LRU eviction), `markDirty` /
  `setClaimed`, dirty-flush vs clean-discard on evict, `gc()` policies with loaded / claimed protection.
  Pure: every world op goes through the `space.SlotBinder` seam; time via an injected clock.
- `space.ShipTransit` — immutable per-tick fixed-point flight integrator toward a target
  `GalacticCoord`; recomputes direction each tick, steps via `plusLocal`, snaps on arrival, never drifts.
- `space.PoolSlotBinder` + `SpaceSlotPool.discard` / `deleteStore` — the live-world glue.
  `SpaceManagerRoundTripTest` (testServer, `stellurgytest space manager`) proves the controller against
  real dimensions: a dirty cell's marker flushes and reloads, a clean cell in the freed slot is
  isolated, and a GC'd idle stored cell's on-disk folder is deleted.

**Production lifecycle** (maintainer-ratified):
- A production `SpaceManager` is registered from a Stellurgy server-start `@EventHandler`
  (`registerPool(spaceCellPoolSize)`, idempotent). The hook **no-ops under the testServer harness** so it
  never collides with the spike / probe tests that register their own pools (chosen over lazy
  registration on first `materialize`).
- `gc()` cadence: a periodic N-tick sweep plus a pool-pressure trigger, with a single **WARN on a forced
  tier-1 eviction** (a live bubble slot evicted under pressure — the overload signal); tier-2 count / age
  GC over the disk store stays quiet. `SpaceManager.GcPolicy` maps 1:1 to `spaceCellGcPolicy`.
- The transit-hosting model (§10), the **per-ship VS crossing** (`VSIntegration.crossShip` applies §4 to a
  VS ship; `VSShipCrosser` carries depart→park / arrive→unpark) and `ShipTransitManager`'s
  depart / advance / arrive state machine with the origin→target refcount handoff are wired, and
  `SpaceSubsystem` ticks them every server tick. A full origin→hyperspace→target round trip of a live VS
  ship is e2e-green.

## 10. Decision ledger

**DECIDED (maintainer-approved):**
- Two states (bubble / transit); one travel mechanic (the jump); the ship always live.
- Coordinates: sectorized fixed-point (not `double`); sector = bubble cell = **32M (±16M local)**;
  content snapped to the cell centre.
- Bubble = grid cell + refcount lifecycle; the dynamic entity-anchor + merge + hysteresis model is
  unnecessary with big cells.
- Two-tier storage: fixed slot pool (`spaceCellPoolSize` — the only ticking worlds, the perf knob) +
  coord-keyed disk store of modified cells only.
- Slot rebind via a retargetable `SaveHandler` (fallback: manual pack/paste).
- Eviction (flush-to-store) ≠ GC (delete-from-store); dirty = diverged from seed.
- GC policies `age | count | both | never` + loaded / claimed protection + last-visit timestamp.
- Threading: main-thread pool + tick-gating + offload of generation / physics / serialization + queue.
- Crossing = momentary pack/paste; StorageChunk = transition / offline form only.
- **Transit hosting.** A single shared **hyperspace world** (permanent) holds ALL in-transit ships at
  once. Ships are **spaced ~128 chunks (2048 blocks) apart** by the `SpaceObjectManager` spiral
  allocator — beyond any render (~64-chunk modded max) and entity-tracking (~512-block) range, so
  passengers never see, collide with or track across ships on pure vanilla mechanics. Each ship is
  **parked**: `ShipData.setPhysicsEnabled(false)` (`VSBridge.java:1030`) while `ShipTransit` advances its
  `GalacticCoord` **logically** (the ship does not physically fly, else parked lanes would drift
  together); the star tunnel is client animation. Spacing is near-free (the void between ships is never
  chunk-loaded). Two sub-second crossings per jump (§3).
  **The lane allocator belongs to the WORLD, not to a transit manager:** the server's `HyperspaceWorld`
  (held by its `ServerState`) owns the one `HyperspaceTiles` every stack parks through, and the allocator
  goes with that object when the server stops. A per-manager allocator is one scope too narrow — its
  promise is "no two ships in one lane", which it can only keep against every ship in the world — and a
  second manager over the same world would start at lane 0 and hand out an occupied lane, leaving two
  registered ships at one position and every position-keyed lookup at that anchor ambiguous.
- **Ship identity and the ledger.** Canonical ship identity = a **Stellurgy ship UUID in the AFC tile's
  NBT** (crossings carry TE NBT verbatim so it survives every jump; the VS UUID is re-minted per
  re-assembly and MUST NOT key anything durable). ONE **`ShipLedger`** owned by `SpaceSubsystem`
  (`shipId → {coord, cellKey, state}`) is the single ship↔cell source (in-memory, NBT-persisted;
  consumed by entry, descent and navigation). The atmosphere↔orbit line has ONE owner,
  `DimensionProperties.orbitLine()`, derived from the body when the planet file states none; the descent
  trigger is the body's own `DescentShell`; the two are one surface in two metrics (`metric-boundary`
  MECH-MET-02). Entry / descent share a generalized `ShipCrossingService` (`ShipTransitManager` keeps
  hyperspace legs only) + a shared `CrewTransfer` (crossShip moves no riders; seat links bind absolute
  subspace coords → re-bind at the new anchor). Slot-world pos ↔ cell-local offset mapping is code
  (origin = cell centre). Entry spawns OUTSIDE the descent radius (hysteresis). Entry paste / descent cut
  call `markDirty`. Client slot-dim registration is a prerequisite for any player in a slot world. Helm
  input is ignored while parked in hyperspace.
- **Durable store.** `ShipLedgerData` is a `WorldSavedData` (ShipLedger + transit records + per-cell
  lastVisit; `claimed` is a DERIVED predicate over parked station entries, no stored flag;
  `UniverseRegistry` stays placement-only). The transit snapshot is re-cut from the parked hyperspace
  blocks at save points (`WorldEvent.Save` dim 0 + server-stop flush); restored records advance
  logically and unpack only when an aboard player needs the world.
  `spaceTransitOfflineProgress` gates offline advance (universe-model §7). `markDirty` = explicit crossing calls + a
  slot-world BlockEvent listener. Login restore = load-time slot-dim interception +
  `PlayerLoggedInEvent` relocation via `getEntityData` tags (C13). `stored` is derived from cell-folder
  existence. Slot dim ids are never persisted (cellKey only). Eviction ignores offline players.
- **Pool policy (design; the pool today is the fixed N of `spaceCellPoolSize`).** Owned by
  [universe-model.md](./universe-model.md) §9: M slot dims pre-registered, a soft target N as an
  eligibility gate in `SpaceManager`, eviction only of UNOBSERVED park-only cells (an observer derived at
  decision time via `SlotBinder.hasOnlineObserver`), growth N→M with warnings, refusal at M. The
  controller-side rules are this doc's: eviction releases the `keepDimensionLoaded` pin, Forge's
  auto-unload divergence is reconciled by re-init on next use, `setClaimed(true)` ⇒ `markDirty`, and
  `SpaceManager.Config` stays immutable with live N / M in a supplier-based holder.
- **Navigation realization.** Memory crystals are the ONLY tier-2 address store (unlimited capacity,
  physical item, ops = duplication / exchange); `JumpGate` in the production caller (nav computer aboard
  ∧ `ShipLedger.positionKnown` ∧ crystal target; `beginTransit` stays trusting; ship-stations pass the
  same gate); nav-computer block = AFC pattern + assembler scan + relative-offset link; on-arrival scan
  module + always-succeeding re-localization (no softlock); server-redacted nav-GUI info channel over the
  `InfoTier` schema; telescope = observatory extension over `systemsInRegion`, un-gated by
  `isSystemKnown`. A login must not contaminate per-station known lists via a live reference.
- **Stations are ships.** A station = a big parked VS ship (ShipLedger entry, claimed zone cell, optional
  hyperdrive via drive stats, transits through the SAME `ShipTransitManager` as any shipId; docking via
  `TileDockingPort`). The legacy `spaceDimId` + spiral allocator + `TileWarpController` travel +
  station-as-dimension + `getEffectiveDimId` model is not part of 0.1.0. Tier-1 rocket docking:
  orbit→dim selector where "dim" = the station's zone cell, materialize-on-demand (usually refcount++ on
  the already-claimed zone).
  **Cell = body ZONE**: a system = an anchored neighbourhood (star anchor + per-body cells at the
  body's orbital offset, in chart blocks: one distance unit = 100 km = `SystemContent.ORBIT_UNIT_BLOCKS`,
  C15 ADDR-20); interplanetary = cells of void → jump-only. A moon is flyable-to from
  its planet and is not ADDRESSLESS — it has a cell of its own inside its parent's zone, and that cell
  rides the moon (C15 ADDR-17). "Local" never means "inside the parent's cell": that reading leaves a
  craft parked beside a moon riding the PARENT and drifting 42 descent shells a day. A moon is reached
  by FLIGHT, not by a jump. **The cube seam is the galactic lattice's only:** `CellSeam.shouldCarry`
  (`CellSeam.java:175`) and `CellWorldMapper.poseEscapesCell` / `coordOfPoseWithin`
  (`CellWorldMapper.java:93`) measure against the galactic `CELL`; inside a zone the carry is the sphere
  (below), and a zone cell is entered by a jump (`JumpGate` accepts a zoned target) or a launch (C15
  ADDR-19, which names `CellWorldMapper`'s zone-width parameterisation as not yet done). Bubble
  mechanics are unchanged (bubble = cell + refcount); zone content sits near its cell centre, so VS
  precision at large coordinates is a sanity check, not an architecture gate.
- **Realization is honest-3D and CENTRED on all three axes**, `world Y = localY`, so a cell occupies
  `[-HALF_CELL, HALF_CELL)` in the world on every axis `[V]` (`CellWorldMapper.poseWorldOf`). The 256
  build height caps blocks, not entity poses (the full pilot path at world Y ~2M is e2e-green). A
  positive Y offset would make Y spend the WHOLE cell going up while X/Z spend half each way, which puts
  the top of every cell past a hard vanilla line: the server DISCONNECTS a player whose position packet
  exceeds 3.0e7 on any axis (`NetHandlerPlayServer.isMovePlayerPacketInvalid`, `:325`), so at
  `CELL = 32M` the top two million blocks could hold no pilot. X/Z are centred; 30M is the coordinate
  system's own root constant (`BlockPos.NUM_X_BITS` is computed from it, `Entity.setPositionAndRotation`
  clamps X and Z to ±3.0e7 and leaves Y alone). Consequences: the vanilla void-kill is GATED inside cell
  worlds (`MixinEntityCellVoid` — in a cell, below zero is the lower half, not the void), and the
  substrate's altitude range is widened at BOTH ends (each server world's `ShipAltitudeBand`, floor beside
  ceiling, held by the world, not written into `VSConfig`). In-cell Y quantization does not exist and
  Y-face seam crossings use the same axis-agnostic machinery. BLOCK content still lives at ordinary block
  Y, and an arrival's blocks are STAGED OUTSIDE the cell at
  `VSShipCrosser.ARRIVAL_STAGING_X = HALF_CELL + CARRY_MARGIN` (`VSShipCrosser.java:64`), so the staging
  band is separated from parked craft by distance.
- **The seam crossing is axis-agnostic and is one machine.** The carry on the galactic lattice
  (`CellSeam` = the arithmetic, `CellCrossingController` = the world half, driven by the flight
  computer's own per-tick report) and the carry out of / into a ZONE (a sphere, not a cube face, with one
  call that both arms and aims the crossing: `CellCrossingController.ZoneMembership.reAddress` →
  `SpaceSubsystem.zoneMembershipOf`) are stated in C15 ADDR-10 and pinned in C19 FRAME-2. The crossing
  itself is the shared `ShipCrossingService`, so entry, descent and the seam are one machine, and the
  short jump is a fourth caller of the same controller, differing only in how the destination cell is
  chosen. Saturate-and-warn is the refusal path when the pool has no slot for the neighbour. Pool sizing:
  a busy system = 2–4 zone slots; N=10 covers a clustered 20–40-player server at M=20; cell = body zone
  also makes eviction FINER (an observed zone no longer pins the whole system's world).
- **Jump hazard model (design, not built).** Hazard driver = strain = `wellStrength(body MASS; black
  holes heaviest) / hyperdrive POWER`; departure + path wells (the integrator checks cells on entry);
  strain<1 = clean at energy=f(strain) (late-game black-hole crossings), strain>1 = defect odds grow.
  Exits are CAUSE-split: emergency (near-line + speed / direction bias, `positionKnown=false` in the
  ShipLedger, power-biased secondary damage via the structural-damage API, crew slammed unless gravity
  dampeners) vs deliberate (helm / auto action, live mid-transit; position known, no secondary roll).
  Roll once at depart (path deterministic); Transit stores declared + actual (persisted). Cooldown is
  dynamic = f(cooling, capacitor charge rate); the capacitor is a separate multiblock beside the drive.
- **Jump trigger, fuel, atmosphere ceiling (decided 2026-07-23).** The automation ADVISES and never
  FORBIDS (`JumpGate` has HARD + ADVISORY tiers); **no range limit** — any coordinate is jumpable, an
  unscanned one is a leap of faith (a DETERMINISTIC arrival-site hazard family); cost = Forge Energy
  only, capacitor burst + in-flight draw; `speed = f(drive power / ship mass)` — the producer of
  `beginTransit`'s `speedBlocksPerTick`; insufficient energy warns rather than refuses.
- **World model (Layer 1 — the universe)**, in [universe-model.md](./universe-model.md): `UniverseRegistry`
  (coord↔system) + `IGalaxyGenerator` + system content / terrain (A), entry ascent (B), planet↔grid seam
  + graded gravity well (C), coord-keyed persistence + transit record (D), discovery + nav-computer /
  memory crystals (E), pool policy (F). This doc is Layer 2; universe-model defines the two-layer
  boundary.

**OPEN (not decided):**
- Cheap-world details under (A): prefab library, seed scheme, modified-cell diff format.
- Transit DURATION scale, and the arrival-hazard resolution mechanics (deliberately undesigned).
- Seam mitigation beyond centre-snapping (if boundary encounters prove common); the encounter
  POPULATION model — nothing spawns NPCs or traffic, so the accepted seam cost buys nothing in
  single-player until an encounter model is decided.

**REJECTED (reason attached):**
- `double` for absolute coords — ULP grows with magnitude; drifts on long transit.
- StorageChunk as whole-trip transport — freezes TEs, drops entities.
- Live `WorldServer` merge / split — cannot fuse worlds; VS `ShipData` is per-world.
- Dynamic entity-anchor + merge + hysteresis — unneeded once cells are big.
- Thread-per-bubble actor — Forge event bus fires global handlers on the bubble thread; player set /
  netty global; VS `CalledFromWrongThreadException`.
- Runtime dimension creation as the primary path — the fixed pool de-risks it (kept only as an
  alternative if the pool proves limiting).
- Solar-map cruise reuse for VS ships — client-motion, fights VS physics.
- A single `spaceDimId` shared space world — superseded by the cell grid.
- Per-ship transit cell / a moving bubble per transiting ship — starves the N-slot pool (one slot per
  in-flight ship); superseded by the one shared hyperspace world.
- Sub-cell bubble quantization (`BUBBLE_SIZE < CELL`, a slot per visited quant) — turns intra-system
  flight into ROUTINE pack/paste seam crossings (the rider-rebinding fragility class on a routine path)
  and multiplies pool demand ×2–4; superseded by cell = body zone (the inter-body void is never flown).
- One world per SYSTEM with compressed distances — makes interplanetary sub-light flight viable
  (~100k blocks), regressing to solar-map casualness; killed geometrically by cell = body zone
  (maintainer, 2026-07-16).
- Stacking transiting ships at one point + toggling ship collision / render off — passengers are
  **world-frame**, so stacked ships' riders overlap and mutually track / push; ship-ship-collision-off
  and other-ship render-cull are **VS-internal** capabilities Stellurgy does not have (it only reads
  `getShipBB()`, VS renders its own ships). Spacing ships 128 chunks apart makes both unnecessary:
  distance beats unproven VS-internal toggles.

## 11. Risks / spikes

1. **Retargetable `SaveHandler` + VS survives a slot content swap** — verified (§5).
2. **VS capability on a pool world — yes.** VS attaches, assembles and ticks in a dynamically
   registered pool-slot cell AND in the permanent hyperspace world (`WorldProviderSpaceSlot`). The
   hazard is a Forge gotcha, not a VS limit: `DimensionManager.initDimension` re-run on the already
   loaded hyperspace world reloads it and wipes VS's per-world ship registry (`HyperspaceWorld.getOrCreate`
   inits only when unloaded).
3. **Tick-gating** idle pool slots (out of / throttled in the vanilla tick loop) without breaking
   chunk / entity save.
4. Empty-chunk generation + lighting cost under a fast-moving ship near a cell edge.
5. Client rendering of transit (skybox + distant POIs) and the drop / arrival.
6. (Only if the pool proves limiting) runtime dimension register / unregister stability in Forge 1.12.2
   (id recycling, save-folder cleanup).
7. **VS precision at large world coords** (|X| ≈ 1.9M vs an origin control) — zone content sits near its
   cell centre, so this is a sanity check and not an architecture gate.
8. **VS shipyard / claim capacity per world** — parked stations-as-ships density + loaded-chunk cost;
   informs the loaded-stations budget.
9. **Ship controllability at extreme world Y (≈ 2M)** — entities are not capped by the 256 build height
   and vanilla void-kill applies only below −64; verified for the centred mapping.

## Related
- `space-model` is the tier-2 ship-space layer; the earlier single-`spaceDimId` model is not part of it.
