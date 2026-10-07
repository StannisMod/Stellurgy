# util-core / Sealing & atmosphere blob

Parent: [00-overview.md](./00-overview.md) · Files: `SealableBlockHandler.java` (234),
`AtmosphereBlob.java` (210)

Two halves of the "is this room airtight, and how big is the sealed volume" question.
`SealableBlockHandler` (singleton, implements `IAtmosphereSealHandler` from api-public)
decides whether one block seals; `AtmosphereBlob` (extends `AreaBlob`) flood-fills the
sealed interior from a root, optionally on a worker thread.

## Key types

| type | role |
|------|------|
| `SealableBlockHandler` (singleton `INSTANCE`) | per-block/material seal verdict + ban/allow lists + airlock-door logic |
| `AtmosphereBlob` | BFS flood-fill of breathable space; runs sync or on shared thread pool; snuffs torches when air is lost |

## Mechanics

- **MECH-SEAL-01 — seal verdict.** `isBlockSealed(world,pos)`: reject outside y∈[0,256] or
  unloaded chunk; allow-list (block/material) wins, then ban-list, then reject liquids/
  non-solids/air/`IFluidBlock`; airlock blocks defer to door logic; otherwise require a
  full 1×1×1 collision box (`isFullBlock`). `SealableBlockHandler.java:108-155`.
- **MECH-SEAL-02 — full-block test.** `isFullBlock` multiplies the collision AABB by 100
  and checks it equals exactly `[0,0,0]..[100,100,100]` — an integer-rounded "is it a
  complete cube" heuristic. `SealableBlockHandler.java:84-98`.
- **MECH-SEAL-03 — airlock door sealing.** `checkDoorIsSealed` treats a door as sealed
  only if the blocks flanking it (accounting for open/closed state, facing stored in the
  lower half, and an edge-of-wall special case for closed single doors) are
  themselves sealed. Recursion is guarded by a set of the airlocks the ONE check is already inside
  (`doorsInCheck`): created per top-level `isBlockSealed` call, threaded through the
  recursion as a parameter and dropped with the call, so nothing of one check survives into the
  next, a thrown one included. `SealableBlockHandler.java:118-151,181-204`.
- **MECH-SEAL-04 — blob flood-fill.** `AtmosphereBlob.run()` does a non-recursive BFS from
  the seed, adding positions whose neighbours are not sealed, bounded by
  `getBlobMaxRadius()` (linear distance) or, when bit 2 of `atmosphereHandleBitMask` is
  set, by volumetric block count; hitting the bound voids the whole blob (leak).
  `AtmosphereBlob.java:107-176`.
- **MECH-SEAL-05 — depressurisation effect.** When air is removed and the dimension's
  default atmosphere disallows combustion, `runEffectOnWorldBlocks` converts lit torches
  to the unlit variant and pops configured `torchBlocks` as item drops.
  `AtmosphereBlob.java:183-212`.

## State & persistence

Both are process state, not world NBT. `SealableBlockHandler` holds four ban/allow lists
(seeded by `loadDefaultData` — 12 banned materials); the door-recursion guard is not state of
the handler (per check, MECH-SEAL-03). The threaded-fill executor is not a static of
`AtmosphereBlob` either: it is `ServerState.atmosphereFillPool` — created with the server
(`AtmosphereBlob.newFillPool`, `AtmosphereBlob.java:34`) and `shutdownNow()` when the server is
released (`ServerState.java:121`), so a fill queued at stop never runs against the next world.
`AtmosphereBlob` extends `AreaBlob`'s graph (owned by atmosphere-oxygen).

## Invariants

- **INV-SEAL-01 [T]** Default banned materials load and gate detection; explicit
  allow-list overrides detection, ban-list overrides allow; re-adding a block does not
  duplicate. `test/integration/SealableBlockHandlerTest.java:34,53,69,82`.
- **INV-SEAL-02 [V]** `addUnsealableBlock`/`addSealableBlock` are mutually exclusive:
  adding to one list removes from the other. `SealableBlockHandler.java:161-174`.
- **INV-SEAL-03 [V]** A server-side unloaded chunk is treated as unsealed (never
  force-loaded for a seal check). `SealableBlockHandler.java:123-125`.
- **INV-SEAL-04 [V]** Airlock-door seal recursion cannot loop: a position already on the
  recursion stack short-circuits to sealed. `SealableBlockHandler.java:146-148`.
- **INV-SEAL-05 [A]** Blob BFS terminates and either seals a bounded volume or voids
  entirely on leak; assumed correct, no direct unit test (covered indirectly by
  `test/server/AtmosphereOxygenSmokeTest.java`). `AtmosphereBlob.java:113-176`.

## Failure modes & edge cases

- **The fill pool is per server, not static.** A static `ThreadPoolExecutor` initialised once at class load from
  `atmosphereHandleBitMask & 1` would be `null` when the bit was 0 at load, and a config reload flipping it to 1
  would NPE in `addBlock`. The executor is `ServerState.atmosphereFillPool`, built unconditionally with each server (its
  threads start on first use) and shut down by `ServerState.release()`; `addBlock` reads the bit
  per call and either submits to it or runs the fill inline (`AtmosphereBlob.java:92-98`,
  `ServerState.java:56,121`). A full queue (32) still drops the calculation with a warning
  (`:95-96`); the one pool is still shared by every dimension's blobs of that server.
- `isFullBlock`'s 100× integer heuristic mis-classifies blocks whose bounds are full but
  not axis-aligned to hundredths (documented limitation in the source comment).

## Integration seams

Capability/interface: implements `IAtmosphereSealHandler` (C5, api-public). Packets:
`PacketAirParticle` (C2). Calls `AtmosphereHandler.getOxygenHandler` (atmosphere-oxygen);
uses `StellurgyBlocks.blockAirLock`/`blockUnlitTorch` (blocks).

## Config surface

Config: see `C4-config-surface` (`atmosphereHandleBitMask` bit 0 = 0 ⇒ synchronous flood-fill, not submitted to `ServerState.atmosphereFillPool`; bit 1 selects volumetric vs linear-radius bound; MECH-SEAL-04).

## Test coverage

`SealableBlockHandlerTest` (integration) pins INV-SEAL-01; `AtmosphereOxygenSmokeTest`
indirectly exercises the blob.

## Open questions

- None open on the pool: it has no NPE window, so no test needs to toggle
  `atmosphereHandleBitMask` at runtime for it.
