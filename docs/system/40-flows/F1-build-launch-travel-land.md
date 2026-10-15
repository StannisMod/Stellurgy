# F1 — Build → Launch → Travel → Land

End-to-end trace of a player-built pad rocket. Each step names the owning subsystem's MECH- anchor
(mechanics live there, not here); the value of this doc is the **seam** — where one subsystem writes
state a different one reads. The station-deployed (UV) variant diverges where noted.

Subsystems crossed: `rocket-assembly` (RASM) → `rocket-entity` (RKT) → `dimension-planets` (DIM) →
`infrastructure-tiles` (INFRA).

## Ordered steps

**S1 — Pad scan → StatsRocket. [RASM]** The scan press ticks the progress machine (MECH-RASM-04)
until `scanRocket` has walked the pad into a `StatsRocket`; the error-code ladder gates SUCCESS
(MECH-RASM-02/03). *Seam produced:* the persisted verdict `status` and the `StatsRocket` model (owned
by api-public) that the build gate and the flying entity both consume.

**S2 — Assemble: world → entity + blob. [RASM]** `assembleRocket` re-scans, requires `SUCCESS`
(INV-RASM-03), lifts blocks and tiles into a `StorageChunk` blob (MECH-RASM-06) and spawns an
`EntityRocket` over it, synced by `PacketEntity` (MECH-RASM-05). *Seam produced:* the blob — **the**
cross-subsystem handoff: `rocket-assembly` builds it, `rocket-entity` only holds it. The UV variant
spawns `EntityStationDeployedRocket` launching downward (INV-RASM-04).

**S3 — Infrastructure claim + link. [RASM ↔ INFRA]** The assembler's link retry loop (MECH-RASM-14)
and the monitoring station's claim check (MECH-INFRA-05) must agree on timing: the monitor accepts the
rocket only inside the assembler's short claim window and otherwise rejects it and strips it from its
own infra list.

**S4 — Fuelling. [INFRA → RKT]** The fuelling station (MECH-INFRA-12) or a hand fluid-item
interact when `canBeFueledByHand` (MECH-RKT-09) transfers mB into the first matching tank and, while
the fuel name is unset, locks it. *Seam:* INFRA writes the rocket's fuel fluid name and level; RKT
reads them at burn time. Fuel levels and names survive a stats rebuild.

**S5 — Launch trigger. [INFRA → RKT]** A rising redstone edge or the GUI launch button on a linked
monitor calls the entity's `prepareLaunch` (MECH-INFRA-01): the monitor is the remote actuator for the
entity's countdown.

**S6 — Countdown + launch gate. [RKT]** `prepareLaunch` posts `RocketPreLaunchEvent` and starts the
countdown; at 0 `launch()` fires once (MECH-RKT-01, INV-RKT-01), rebuilds stats from the blob, applies
the weight gate (C10 STAT-26..29) and the parts-wear gates, and **resolves the destination through
the guidance computer**, rejecting an unreachable one (INV-RKT-03).

**S7 — Destination resolve. [RKT → DIM]** `TileGuidanceComputer` turns the inserted chip into a dim
id and a landing location (MECH-RKT-08). *Seam:* the dim is validated against
`DimensionManager.getDimensionProperties`, which never returns null (INV-DIM-10), for reachability, the
transfer line and gravity.

**S8 — Ascent burn. [RKT ← DIM]** Each server tick the burn drains fuel and accelerates the rocket
against DIM's gravitational multiplier (MECH-RKT-02, INV-RKT-02). *Seam:* the monitor mirrors the
flight live — server samples into `snap*` fields for the client GUI (MECH-INFRA-02), and its status
machine tracks the launch off the `RocketEvent` bus (MECH-INFRA-03).

**S9 — Orbit reached → dispatch. [RKT → DIM / mission / satellite]** Past the launch world's transfer
line (the body's own atmosphere, 100 000 on Earth; a world with no line refuses the launch,
`error.rocket.noOrbitLine`; `metric-boundary` MECH-MET-02) `onOrbitReached` dispatches by payload:
satellite chip, seatless (asteroid → `MissionOreMining`), or crewed (MECH-RKT-03). *Seam:* the orbit
threshold is a DIM stat; the mission / satellite spawn is the hand-off to those subsystems.

**S10 — Cross-dimension transfer. [RKT → DIM]** A different reachable dim runs `changeDimension`,
a custom teleporter that rewrites the entity into the target world and re-mounts passengers
(MECH-RKT-05); a chunk-load ticket for the destination is held from the launch event to land / abort.
*Seam:* the blob rides inside the entity NBT across the world boundary and is **not** re-sent over the
wire (INV-RKT-18); a client rebuild pulls it separately.

**S11 — Descent + land detection. [RKT → INFRA / RASM]** The descent phase bleeds motion, landing is
detected from the motion test and posts `RocketLandedEvent`, zeroing motion and clearing flight state
(MECH-RKT-04). *Seam (fan-out):* the event is consumed by the monitor FSM (`landed`, MECH-INFRA-03)
**and** by the assembler's re-link handler (MECH-RASM-14).

**S12 — Unpack: blob → world. [RKT → RASM]** Blocks return only when the player triggers
`deconstructRocket` (the `DECONSTRUCT` packet), which unlinks infra and calls
`StorageChunk.pasteInWorld`, re-applying each tile's NBT (MECH-RASM-07). *Seam:* closes the S2
round trip — `rocket-assembly` reads its own blob back out of the `rocket-entity`-owned NBT.

## NBT round-trip summary

The blob is authored server-side by RASM (`StorageChunk.writeToNBT`), embedded under the `data` key of
the entity (MECH-RKT-20), carried through `changeDimension` and disk saves, and read back by RASM at
paste (MECH-RASM-07); the blob index formula is identical on read and write (INV-RASM-08). Entity
flight state and the guidance destination round-trip alongside it (C1).

## Gaps & mismatches

- **G1 — "Landed" ≠ "blocks restored".** `RocketLandedEvent` (S11) flips the monitor FSM and
  re-links the assembler, but nothing auto-pastes the blob; `pasteInWorld` runs only from the
  player-driven `DECONSTRUCT` packet. The rocket persists as an entity until manual deconstruct. By
  design, but a real state gap (low).
- **G2 — The assembler's own stats go stale after launch.** `EntityRocket.launch()` recomputes the
  entity's mass from the blob (`stats.setMass(storage.recalculateMass())`), but the assembler tile's own
  `StatsRocket` is deliberately not updated back (the `setMass(getMass())` that would do it accumulates
  the fuel again), so the assembler GUI keeps the pre-launch value. Producer (entity) / consumer
  (assembler GUI) diverge (low, cosmetic).
