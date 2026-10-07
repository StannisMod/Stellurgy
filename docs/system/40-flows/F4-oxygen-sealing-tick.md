---
id: F4-oxygen-sealing-tick
title: Oxygen / sealing tick — blob fill → vent → seal → player damage
subsystems: [atmosphere-oxygen, dimension-planets, util-core, network-wire, blocks]
anchors: [MECH-ATM-01, MECH-ATM-02, MECH-ATM-04, MECH-ATM-05, MECH-ATM-06, MECH-ATM-07, MECH-ATM-11, MECH-ATM-12]
confidence: high
---

## Scenario

A powered, fuelled oxygen vent in a sealed room floods `PRESSURIZEDAIR` into that room; a player who
steps outside the seal (or whose seal fails) resolves a hostile atmosphere and takes armour-bypassing
damage. This flow follows the **server tick** producer→consumer chain and marks every `isRemote` fork,
because the entire sealing model is server-authoritative and only the resolved result is mirrored to
the client. Mechanics are the atmosphere-oxygen doc's; the model of what an atmosphere is is C21.

## Preconditions (owner: atmosphere-oxygen)

- P0. A per-dimension `AtmosphereHandler` must exist for the vent's dim — created at world load only
  when `enableOxygen && hasSurface() && (overrideGCAir || dim != getMoonId() || native)` (**MECH-ATM-01**;
  the handler is a part of the world object it serves, via `WorldRuntime`; **INV-ATM-01**). No handler ⇒
  the vent's `getOxygenHandler` returns null and `performFunction` returns early, so **no blob, no seal,
  no effects** — the same short-circuit that gives **INV-ATM-02**.

## Step sequence (server)

1. **Vent tick entry, client fork.** `TileOxygenVent.performFunction` runs each active machine tick,
   but all blob / seal logic is gated on `!world.isRemote`; on the client it only drives sound and
   particles. **Seam split #1.** (**MECH-ATM-07**)
2. **Blob registration (once).** On `firstRun` the vent calls `atmhandler.registerBlob(this, pos)` and
   forces a reseal by starting `isSealed = true` (to count scrubbers) then `setSealed(false)`. The vent
   is the `IBlobHandler` key into the handler's `blobs` map.
3. **Seal attempt every 100 t.** When off-seal, turned on, and holding `getPowerPerOperation()` energy,
   the vent calls `setSealed(atmhandler.addBlock(this, pos))` on the 100-tick cadence. Power and fuel are
   BOTH required or no seal forms (**INV-ATM-07**). **Producer of the seal decision.** (**MECH-ATM-07**)
4. **Handler → blob hand-off (the key SSOT seam).** `AtmosphereHandler.addBlock` looks up the vent's
   `AreaBlob` and calls `blob.addBlock(pos, getBlobWithinRadius(pos, maxBlobRadius()))`. The
   neighbour / overlap list is bounded by `maxBlobRadius()` — 256 in threaded mode, else `oxygenVentSize`
   — read from the config in force at each call. (seam into util-core)
5. **Flood-fill in AtmosphereBlob.** The BFS floods over non-sealed, non-overlapping cells; the fill cap
   `maxSize` is derived **live** from `getBlobMaxRadius()` → the vent's `getMaxBlobRadius()` = live
   `oxygenVentSize`. Radius mode vs threaded-volume mode is chosen by `atmosphereHandleBitMask`.
   Reaching a cell past the cap **voids the whole blob** (`clearBlob()`) — binary, no partial fill
   (**INV-ATM-08**, **MECH-ATM-02**). Blob membership excludes sealed / already-owned cells
   (**INV-ATM-12**).
6. **Fill result → atmosphere.** If the room sealed and the tank can supply `blobSize × gasUsageMult`
   O2, the vent flips `hasFluid = true` and sets the blob's data to `Atmosphere.PRESSURIZEDAIR`. If the
   tank runs dry it reverts the blob to `DimensionProperties.getAtmosphere()` — the dim default from
   dimension-planets (**INV-ATM-09**). **Producer writes the blob's atmosphere.**
7. **Seal state to client.** `isSealed` is pushed only via the vent's update tag
   (`getUpdateTag` / `handleUpdateTag`), never persisted in `writeToNBT`; re-derived on first server
   run. **Seam split #2.**
8. **Entity effect tick — the consumer.** Separately, on every `LivingUpdateEvent`,
   `AtmosphereHandler.onTick` runs — again server-only (`!entity.world.isRemote`). It resolves the
   entity's atmosphere by asking the **same** `blobs` map: `getAtmosphereType(entity)` returns the data
   of the first blob whose cells `contain` the entity, else `DimensionProperties.getAtmosphere()`.
   **This is where the vent's produced state is consumed.** (**MECH-ATM-04**) Both the blob key and the
   entity lookup are world-frame, which is the ship-frame violation of C12 HEAT-14.
9. **Client sync.** Once a second per player — phased on that player's own `ticksExisted`, never on the
   world clock — the handler sends a finished `PacketAtmSync` readout and remembers nothing between
   sends (**MECH-ATM-06**; C2 clause 8, C21 CON-C21-13). Connectionless player-shaped entities
   (FakePlayer / `EntityPlayerMP` with null `connection`) return before any packet-bearing effect, to
   avoid a netty NPE taking down the tick loop (**INV-ATM-14**).
10. **Tick gate + cancellable event.** Effects run only if `atmosType.canTick()` and the entity is not
    in lava / water; a cancellable `AtmosphereTickEvent` is posted, and effects are skipped if
    cancelled or the type is immune to the entity class (**INV-ATM-03**, **INV-ATM-06**).
11. **Suit / immunity resolution.** The entity is asked — creative / spectator, android, rocket /
    capsule ride, the rocket-transfer grace window (`RocketTransferGrace`: a player's in his
    `IPlayerBindings`, any other entity's under ForgeData), then armour; what the SUIT is asked is a
    HAZARD, not a named atmosphere (`IProtectiveArmor.protectsFrom`; C21 CON-C21-08) (**MECH-ATM-05**,
    **INV-ATM-05**). Protection is read via the `ItemAirUtils` wrapper or
    `CapabilitySpaceArmor.PROTECTIVEARMOR` (seam into api-public / armor).
12. **Armour cost & damage.** For a fillable chest, `protectsFrom` is free in a combustible (O2-bearing)
    atmosphere but costs 1 unit of air per commit otherwise; zero air ⇒ no protection
    (`ItemSpaceChest.protectsFrom`; **MECH-ATM-12**, **INV-ATM-13**). Unprotected ⇒ the hostile
    atmosphere applies its damage source (`Vacuum` / `LowOxygen` / `Heat` / `OxygenToxicity`, all
    `bypassesArmor` + absolute) plus its potion cocktail on a per-type modulo cadence (the nausea
    effect reads the live `enableNausea` flag, C4); vacuum also sends `PacketOxygenState`.

## Producer / consumer summary

| state | produced by | consumed by | seam |
|-------|-------------|-------------|------|
| blob atmosphere | vent `setAtmosphereType` (step 6) | `onTick` → `getAtmosphereType` (step 8) | same `blobs` map, different tick |
| seal (`isSealed`) | vent (server, step 3) | client render (update tag only) | not persisted |
| fill cap radius | live `oxygenVentSize` via `getMaxBlobRadius` | overlap search via `maxBlobRadius()`, also live | two formulas — see Gaps |
| default atmosphere | `DimensionProperties.getAtmosphere()` (dimension-planets) | vent revert + handler fallback | cross-subsystem |

## Gaps & mismatches

- **Blob radius has two formulas.** The neighbour / overlap search radius is `maxBlobRadius()` (256 in
  threaded mode, else the vent size), read at each use. The BFS fill cap is always the vent's
  `getMaxBlobRadius()` = live `oxygenVentSize`. Both follow a runtime config change, but they are not
  one source. Lands on steps 4-5.
- **One fill executor per server.** The fill executor is `ServerState.atmosphereFillPool`, built
  unconditionally with each server and shut down by `ServerState.release()`; it is shared across that
  server's dimensions. Because it always exists, toggling the threading bit at runtime cannot reach a
  missing pool. Lands on step 5.
- **Per-block reaction cost is unbounded and untested.** `AtmosphereHandler.onBlockChange` runs a
  `getBlobWithinRadius` scan + reactions on *every* server `setBlock`; its source comment states these
  paths "were NEVER tested" and several are commented out. This is a parallel producer that can mutate
  the same blobs the vent owns, on a much hotter path than the 100-tick vent cadence. `[A]`
- **`redstoneState` is read without a `hasKey` guard.** `TileOxygenVent.readFromNBT` reads the
  contractual `redstoneState` byte with `getByte` (default 0) and indexes `RedstoneState.values()[0]`;
  any enum reordering silently reinterprets persisted vent state, changing whether the vent even
  reaches step 3 (C1).

Seams: 3 `isRemote` forks (steps 1, 8, and the block-change hook), 1 persistence split (`isSealed`),
1 SSOT split (radius search vs fill cap).
