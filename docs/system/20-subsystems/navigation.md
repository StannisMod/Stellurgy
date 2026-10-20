---
id: navigation
owns: [navigation/]
entrypoints: [TileNavigationComputer#refreshTarget, TileNavigationComputer#answerBodyInfo, TileNavigationComputer#syncOnChannel, ItemMemoryCrystal#ensureSeeded, JumpTrigger (builds ShipNavigation)]
depends-on: [space-model, universe-model, dimension-planets, hyperdrive, items]
depended-by: [hyperdrive, multiblock-machines (observatory survey), client-render]
contracts: [C1, C2, C4, C15, C16]
confidence: high
---

## Purpose

What a tier-2 ship KNOWS about where it can go, and how it points at it. Knowledge lives in an item, the
**memory crystal**: a list of one-record-per-body addresses that merge by freshness, copy add-only, sync
between ships, and are seeded once. Around that sit the pure rules for aiming at a moving body, for
deciding how much about a body the pilot has earned, and the adapter that lets the jump gate ask one real
ship about its navigation.

## Responsibility boundary

**Owns**: the crystal's data model and merge laws (`CrystalEntry`, `CrystalMemory`, `CrystalSync`), the
starter set (`CrystalSeeding`), the aim-lead iteration (`TargetPrediction`), the info-tier rule and its
field filter (`NavInfoRedaction`), the unredacted body readout (`NavBodyView`), and the ship-side
answers to the jump gate (`ShipNavigation`).

**Does NOT own**: the gate's clauses and refusal text (`JumpGate` — [hyperdrive](./hyperdrive.md)); the
console tile that holds the slots, the target and the buttons (`TileNavigationComputer`, mapped in
hyperdrive.md); the item shell (`ItemMemoryCrystal`, [items](./items.md)); the observatory survey that
WRITES entries ([planetary-data](./multiblock-machines/planetary-data.md), `TelescopeScan`); the
cell/name/frame model ([C15](../30-contracts/C15-cell-address-and-frame.md),
[space-model](../space-model.md)); the clock ([C16](../30-contracts/C16-space-clock.md)); the
`InfoTier` / `PlanetInfoField` ladder (universe layer, [universe-model](../universe-model.md)). The
older Advanced-Rocketry `SpacePosition` navigation is [space-nav](./util-core/space-nav.md) (`MECH-NAV-*`)
and shares nothing with this package.

## Key types

| type | role |
|---|---|
| `CrystalEntry` | immutable address: coord, name, kind, `InfoTier`, `observedTick`, `dimId`; freshness merge; NBT |
| `CrystalMemory` | insertion-ordered map identity-key → entry; `record`, `copyFrom`, NBT under `navAddresses` |
| `CrystalSync` | symmetric union of two `CrystalMemory` |
| `CrystalSeeding` | `starterFor(World)` → the first-use address set |
| `TargetPrediction` | `aimAt(dim, origin, now, Ephemeris, Flight, CellFrames)` bounded fixed-point lead |
| `NavInfoRedaction` | `tierFor(...)` and `redact(map, tier)` |
| `NavBodyView` | `of(SystemBody, CrystalEntry)` → ordered `PlanetInfoField → String` |
| `ShipNavigation` | `JumpGate.ShipContext` for one ship: nav-computer lookup, ledger position, drive answers, planned transit |

## Mechanics

### MECH-NVG-01 — An entry names a body, not a point
An entry's identity is `dim:<dimId>` when it carries a dimension, else `cell:<cellKey>` for a hand-noted
bare coordinate. The coordinate it carries is the observation, never the identity; so one planet seen at
two orbital positions is one entry, and a body and a bare point at the same coordinate are two. [V]
`CrystalEntry.java:89-94`, `CrystalMemory.java:48-52`. [T] `CrystalMemoryTest.java:35`, `:55`.

### MECH-NVG-02 — Merge by freshness
Same identity: the larger `observedTick` wins outright; on equal ticks the entry whose detail tier is
`atLeast` the other's wins (the incoming one on an exact tier tie). Merging different identities throws.
`record` returns whether the crystal changed. [V] `CrystalEntry.java:124-139`, `CrystalMemory.java:48-64`.
[T] `CrystalMemoryTest.java:78`, `:90`.

### MECH-NVG-03 — Copy is add-only; sync is the symmetric union
`copyFrom` records every source entry into the target (never removes, never mutates the source); `sync`
copies B into A, then a snapshot of A's pre-merge state into B, so both end as the union with the
fresher record of each shared body. Returns the number of entries changed (0 = already in step). [V]
`CrystalMemory.java:71-82`, `CrystalSync.java:23-33`. [T] `CrystalMemoryTest.java:101`, `:119`;
`NavRedactionAndSyncTest.java:136`, `:151`, `:165`. Exception to "already in step": see Failure modes.

### MECH-NVG-04 — Unlimited capacity, wholesale blanking only
No size cap. In production a crystal is emptied only by the console's "erase source" writing a fresh
`CrystalMemory` onto the source slot; the ship slot is never touched by it. `erase(coord)`, `eraseAll`,
`knows` and `forBody` have no production caller (tests only). [V] `CrystalMemory.java:32-34`,
`TileNavigationComputer.java:449-455`; callers by Grep over `src/main` (2026-10-06).

### MECH-NVG-05 — Seeded once, on first use, server side
`ItemMemoryCrystal.ensureSeeded` sets `navSeeded` BEFORE seeding and merges `starterFor` into whatever the
stack already holds; a flagged crystal is never re-seeded, so a blanked one stays blank. Skipped on the
client and with no world. The console calls it when a crystal is inserted. [V]
`ItemMemoryCrystal.java:29,65-78`, `TileNavigationComputer.java:418-423` (`setInventorySlotContents`).

### MECH-NVG-06 — What the starter set contains
Empty if there is no world, no server or no `UniverseRegistry`. Otherwise: the home world (dim 0) when the
registry can name it, plus candidate bodies from the discovery flag — `planetsMustBeDiscovered` OFF (the
config default) → every registered dimension; ON → only `knownPlanets`. Dim 0 and any body whose cell is
the home body's cell or inside its zone (moons) are skipped; a body the registry cannot name is skipped.
Everything is stamped `InfoTier.TELESCOPE` and with the SPACE clock, with `dimId` as identity. [V]
`CrystalSeeding.java:42-103` (flag `:73-76`, skips `:81-96`, clock `:58`, zone test `:109-113`),
`StellurgyConfiguration.java:929`. [T] only at milestone level, not pinned per clause: the moon-skip
and home-record are red-witnessed in `M1PlanetToPlanetMilestoneE2ETest.java:357-365` (a witness recorded
in a javadoc, not an assertion this author located and read).

### MECH-NVG-07 — Aim at where the body will be when the flight ends
`aimAt` starts from the body's address now, then up to `MAX_PASSES` times: price the flight to the
current aim through the cell frames at (now + previous flight ticks), re-ask the ephemeris for the body at
(now + new ticks), and stop when both the ticks and the point repeat. A body that cannot be located at
the start returns `null` (never a coordinate); one that vanishes mid-pass keeps the best aim so far; a
null origin or flight law returns the present address; null frames means the static reading. The cell is
the body's durable name at every tick; only the in-cell point leads. [V] `TargetPrediction.java:38,64-91`.
[T] `TargetPredictionTest.java:78`, `:98`, `:109`, `:124`, `:148`, `:169`, `:177`.

### MECH-NVG-08 — The console re-aims on the server, with the space clock
`refreshTarget` runs only server-side and only for a body target (not a hand-typed cell). It feeds
`aimAt` a STRICT dimension lookup (unknown dim → no answer, never the overworld), the body's full address
(not just its cell), `JumpSpeed.transitTicks` at the ship's planned speed, and the registry as frames.
Unlocatable → the last known target is kept and `targetResolved` goes false (the gate then refuses). [V]
`TileNavigationComputer.java:234-297` (clock `:252`, strict `:258-264`, keep-last `:286-294`). Owned
by hyperdrive; recorded here because it is `TargetPrediction`'s only production caller.

### MECH-NVG-09 — Information is earned by proximity; memory counts too
`tierFor`: ORBIT when the measured distance ≤ `ORBIT_ZONE_BLOCKS` (= the entry ring); else APPROACH when
the ship's cell key equals the body's name's cell key; else TELESCOPE. The result is the better of that and
the crystal's recorded tier. Distance is an INPUT measured by the caller through both frames at one tick;
the cell test needs no tick. `redact` keeps only fields visible at the tier, preserving order. [V]
`NavInfoRedaction.java:28-29,50-64,70-81`. [T] `NavRedactionAndSyncTest.java:49`, `:58`, `:66`, `:74`,
`:82`, `:97` (ORBIT follows the metre, not the cell name), `:110`, `:124`. Sanctioned by
[C15](../30-contracts/C15-cell-address-and-frame.md) ADDR-15 (maintainer ruling 2026-08-01).

### MECH-NVG-10 — The redacted answer is built on the server
On a pick the console aims at the entry's body, finds the `SystemBody` (by `dimId` at the target's
address when the entry names a body; by shared cell for a bare coordinate), computes the tier from the
ship's ledger coordinate, the measured registry distance at the space clock and the entry's detail, then
sends `PacketNavBodyInfo` of `redact(NavBodyView.of(body, entry), tier)` to that player only. No body at
the address → silence. The client decodes and drops the packet today ([C2](../30-contracts/C2-network-wire.md)
row `PacketNavBodyInfo`), so the channel has no visible effect yet. [V]
`TileNavigationComputer.java:851-860,978-1011`.

### MECH-NVG-11 — The unredacted readout
`NavBodyView.of` always carries COORDINATE (the body's durable name cell key, never an offset), NAME
(crystal's name, else the dimension's, else the kind) and TOPOLOGY (kind). For a body with a dimension
(not stars/belts/slots; landability irrelevant) it adds atmosphere density and presence, average
temperature, the body's mass in Earth masses under `MASS` — absent when nobody stated one, never its surface gravity
under that label — and biome count. A field nobody can supply is absent, not blank. [V][T]
`NavBodyView.java:26-54`; `NavigationReadoutServerTest.java:42`.

### MECH-NVG-12 — A ship answers the gate from its own console and ledger
`ShipNavigation` is a thin per-call view: the nav computer is the loaded `TileNavigationComputer` whose own
flight-computer link equals this ship's flight-computer block (no region/position filter); position and
"known" come from the durable ledger; drive answers are read fresh from `ShipDrive` each call. An
unmeasured hull raises no coverage objection (0). The in-flight energy cost is draw × planned transit
ticks (0 with no drive); the "direct crossing?" answer is delegated to
`ShipTransitManager.jumpsDirect` (short enough AND the target cell can get a slot), the predicate departure
itself reads; `plannedJumpIsDirect` has no caller today. [V]
`ShipNavigation.java:51-54,109-115,128-139,149-158,168-178,181-188,194-212`;
`ShipTransitManager.java:1030`. [T] the lookup's per-ship identity under two craft:
`NavLookupNamesItsOwnShipTest.java:62` (requires VS; skips otherwise).

### MECH-NVG-13 — Sync across consoles is opt-in by channel, server-only
`syncOnChannel` does nothing on the client or at channel 0; otherwise it syncs this console's ship crystal
with every other loaded console (all dimensions) on the same channel that holds a crystal, writing back
only the sides that changed. Copy-source-into-ship is likewise server-side. [V]
`TileNavigationComputer.java:929-969` (channel-0 guard `:930`), `:434-446`, `:841-844`.

## State & persistence

| key | owner | note |
|---|---|---|
| `navAddresses` | crystal item NBT | list of entry compounds, insertion order = learned order |
| entry: coord tags, `name`, `kind`, `detail`, `observedTick`, `dim` | `CrystalEntry` | `kind`/`detail` stored by enum NAME; `dim` absent reads as "no body" |
| `navSeeded` | crystal item NBT | one-shot seeding flag |

Read is forgiving: an unreadable `kind` → `PLANET`, an unreadable `detail` → `TELESCOPE`, a missing tag →
empty crystal, a null entry skipped; entries re-enter through `record`, so duplicate identities in stored
NBT merge on load. [V] `CrystalEntry.java:33-37,141-188`, `CrystalMemory.java:131-157`,
`ItemMemoryCrystal.java:29`. No test asserts the round trip of name/kind/detail/tick/coord/`dim`. The item is stack-size 1
(`ItemMemoryCrystal.java:32`). Persisted `observedTick` is a SPACE-clock value (C16); nothing here reconciles
a rolled-back clock.

## Invariants

- **INV-NVG-01** `[V]` A `CrystalMemory` holds at most one entry per identity key; the key is the body's
  `dimId` when it has one, else the coordinate's cell. `CrystalMemory.java:48-64`; `CrystalEntry.java:89`.
  `[T]` `CrystalMemoryTest.java:35`, `:68`.
- **INV-NVG-02** `[V]` `record`/`copyFrom`/`sync` never remove an address and never replace a record with
  an older observation. `CrystalMemory.java:71-82`, `CrystalEntry.java:132-137`. `[T]`
  `CrystalMemoryTest.java:90`, `:101`.
- **INV-NVG-03** `[V]` Merging two different identities throws; callers must key first. `CrystalEntry.java:128-131`.
- **INV-NVG-04** `[V]` A `CrystalEntry` always has a coordinate; null `name`/`kind`/`detail` are normalised
  (`""`, `PLANET`, `TELESCOPE`). `CrystalEntry.java:54-60`.
- **INV-NVG-05** `[V]` Starter entries are stamped at `TELESCOPE` and with the space clock, never a world's
  own, so seeded stamps are comparable with each other. `CrystalSeeding.java:58-62,99-100`.
- **INV-NVG-06** `[V]` The redaction is applied by the server before the packet is built; an unearned field
  is never written to the wire. `TileNavigationComputer.java:1008-1010`; `NavInfoRedaction.java:70-81`.
- **INV-NVG-07** `[V]` The tier is never lower than the crystal's recorded tier and never lower than
  proximity gives. `NavInfoRedaction.java:60-63`. `[T]` `NavRedactionAndSyncTest.java:74`, `:82`.
- **INV-NVG-08** `[V]` `TargetPrediction.aimAt` returns `null` only when the body has no address at the
  start; never an invented coordinate; it runs at most `MAX_PASSES` refinement passes.
  `TargetPrediction.java:64-91`. `[T]` `TargetPredictionTest.java:169`.
- **INV-NVG-09** `[V]` A ship's navigation computer is found by the console's link to THIS ship's flight
  computer block, and by nothing else. `ShipNavigation.java:194-212`. `[T]` `NavLookupNamesItsOwnShipTest.java:62`.
- **INV-NVG-10** `[V]` Crystal mutation requests are honoured on the server only. `TileNavigationComputer.java:841-844`.
- **INV-NVG-11** `[T][BEH]` The readout's MASS line carries the body's mass in Earth masses, never its surface
  gravity, and a body nobody stated a mass for has no MASS line (`NavBodyView.java:46-51`; MECH-NVG-11). Pinned by
  `NavigationReadoutServerTest#theMassLineCarriesTheBodysMassNotItsGravity`.

## Failure modes & edge cases

- **No universe registry / no world at seeding**: empty (blank, not broken) crystal; with `ensureSeeded`
  the `navSeeded` flag is already set, so such a crystal is never re-seeded (`ItemMemoryCrystal.java:73` before
  `:76`). A crystal first used before the registry is up stays blank for good unless filled by copy/sync/survey.
  [V]
- **Equal tick, equal tier, different content for one body**: the incoming record always wins, so
  `sync` swaps the two records instead of converging (`CrystalEntry.java:138`, `CrystalSync.java:29-31`);
  repeated syncs flip them each time. Rare (needs identical stamps). [V] reasoned from the code; not tested.
- **Aim target vanishes**: `targetResolved=false`, last coordinate kept; the jump gate refuses. [V]
- **Typed coordinate**: aimed without vetting, by design; not a body, so not followed. `TileNavigationComputer.java:865-869`. [V]
- **Body lookup at pick** uses the console's current target address as the search point; correct only
  because the pick sets the target immediately before (`:858` before `:859`). [V]

## Integration seams

- **Packet**: `PacketNavBodyInfo` S→C (C2). **Item NBT**: `navAddresses`, `navSeeded` (C1:296-297 — see
  Contradictions). **Writers of entries besides seeding**: the observatory survey (`TelescopeScan.entryFor`
  `:429-447`) through `ItemMemoryCrystal.record` (`:81`). **Readers**: console, observatory
  (`TileObservatory.java:1246`), `UniverseUpgradeCommand.java:85`.
- **Gate**: `JumpTrigger` builds a `ShipNavigation` per press/commit (`JumpTrigger.java:113,152`);
  `TileAdvancedFlightComputer.java:950` builds one too.
- **Clock**: stamps and aims read `SpaceSubsystem.spaceClock()` ([C16](../30-contracts/C16-space-clock.md)).

## Config surface

| flag | effect here | full-disable |
|---|---|---|
| `planetsMustBeDiscovered` | which bodies the starter crystal carries (all registered vs known only) | n/a |
| `ORBIT_ZONE_BLOCKS` | `tunable`, derived from `ShipEntryController.ENTRY_RING_BLOCKS` | not a flag |
| `TargetPrediction.MAX_PASSES` | `tunable` bound | not a flag |

## Test coverage

`CrystalMemoryTest` (store, merge, copy, NBT), `NavRedactionAndSyncTest` (tier, redact, sync),
`TargetPredictionTest` (aim), `NavLookupNamesItsOwnShipTest` (server; ship lookup). No test found for:
`NavBodyView`, `ShipNavigation`'s drive/transit answers, `CrystalSeeding` as a clause (only the milestone's
red-witness notes), `dim` NBT round-trip, `ensureSeeded`, the equal-tick sync case.

## Contradictions with existing docs (code wins; not edited here)

- C16 says `CrystalEntry.mergedWith` (`C16-space-clock.md:136`); the method is `mergeWith`
  (`CrystalEntry.java:124`). C16 `:157-159` says nothing but the seeding writes a crystal entry; the
  survey writes entries too (`TelescopeScan.java:429-447`).
- C1 `:296` lists the entry as coord + `name` + `kind` + `detail` + `observedTick`; the entry also writes `dim`
  (`CrystalEntry.java:37,148`).
- C15's status table (`:517,:521,:523`) cites `ShipNavigation.java:136`, `TargetPrediction.java:66` and
  `NavInfoRedaction.java:45` as static-grid / no-name-check sites; the code measures through
  `SpaceSubsystem.frames()` (`ShipNavigation.java:156,176`) and the injected `CellFrames`
  (`TargetPrediction.java:73,79`), and `NavInfoRedaction` takes the measured distance as a parameter
  (`:50-54`). ADDR-14's `next.sameCell(aim)` note (`C15:373-377`) is likewise already replaced by the
  point test (`TargetPrediction.java:84`). `C15:355-356` line cites `CrystalEntry.java:65-96` are stale.

## Open questions

- Whether `getRegisteredDimensions` (`CrystalSeeding.java:76`) can hold non-planet dimensions that
  `coordForPlanet` would still name was not established.
