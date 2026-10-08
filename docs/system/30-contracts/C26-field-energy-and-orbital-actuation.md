---
id: C26
covers: where a harvested joule comes from, what taking it does to the source, and the same electrodynamics run backwards as an orbital actuator
confidence: RULED DESIGN-STAGE (2026-09-02) — nothing built. The design-locked corona collector is the one neighbouring mechanic
owner-subsystem: a new electrodynamic layer + ship/station characteristics (actuator envelope) + ship-heat
see-also: [C24 (the environment a harvester reads), C25 (the hyperfield may not become a reservoir), C12 (ship heat — where the losses land), C10 (ship stats)]
---

# C26 — Field energy and orbital actuation, clauses ENERGY-1..ENERGY-13

Ratified 2026-09-02: two products from one site means two machines, and the altitude control surface
is a UI over one array, not physics.

**The rule the whole contract exists to hold**: *"а static magnetic field is not a free reservoir. It is a
channel."* Every joule names its source.

## Conservation — the clauses that make a harvester honest

- **ENERGY-1** `[A][SYS]` **Every joule names a RESERVOIR.** The admissible ones are orbital mechanical energy,
  body rotation, plasma/wind kinetic energy, electromagnetic (Poynting) flux, magnetic free energy released
  by reconnection, accretion, and black-hole rotation. A mechanic that produces energy without naming one is
  a defect, not a balance choice. FOR: ENERGY-3.
- **ENERGY-2** `[A][BEH]` **A field is a CHANNEL, never a store.** Coupling requires **relative motion, a flux,
  or a circuit that closes**. Strong `B` with no plasma and no circuit closure yields **nothing**, and says
  so rather than yielding a little.
- **ENERGY-3** `[A][BEH]` **Extraction CHANGES the source, observably.** Taking orbital energy changes the orbit;
  taking rotational energy spins the body down; intercepting a flux casts a wake. The observable is what
  makes the reservoir real instead of asserted.
- **ENERGY-4** `[A][BEH]` **The orbital torque's sign is not always negative.** Whether a rotational coupling
  raises or lowers an orbit depends on the body's corotation radius `r_c = (GM/Ω²)^{1/3}` against the
  station's own. A design that assumes decay everywhere is wrong at half the sites.
- **ENERGY-5** `[A][SYS]` **No double counting.** Accretion power and black-hole spin power are different
  reservoirs and may not both be charged for one joule; the same holds for a wind's kinetic energy and its
  Poynting flux. FOR: ENERGY-3.
- **ENERGY-6** `[A][SYS]` **Output is bounded by what is physically INTERCEPTED** — an area against a flux, a
  current against a field — never by a multiplier keyed to a body type. `if (star.type == MAGNETAR)
  output *= 1000` is the shape this clause forbids (`C24` BODY-8's consequence here). FOR: ENERGY-3.
- **ENERGY-7** `[A][SYS]` **The hyperfield is not a reservoir** (`C25` HYPER-1). Hyperphysics may improve
  interaction geometry, impedance matching, field-line access or control precision — and if output rises,
  the clause owes the name of the **external** reservoir now being tapped more efficiently. FOR: ENERGY-3.

## Generator and motor are one device

- **ENERGY-8** `[A][BEH]` **One electrodynamic array, four modes** — `GENERATOR`, `BOOST`, `BRAKE`, `HOLD`. The
  same physics runs both directions: mechanical → electrical and electrical → Lorentz force → mechanical.
- **ENERGY-9** `[A][BEH]` **`BRAKE` recovers strictly less than the orbit loses**, and no loop of
  `GENERATOR`→`BOOST` returns more than it spent. Rounding is not a licence: the round trip is lossy by
  construction, not by tuning.
- **ENERGY-10** `[A][BEH]` **A control surface states INTENT; the environment decides FEASIBILITY.** An ordered
  altitude the local field cannot reach is **refused with a reason**, never silently satisfied and never
  quietly approximated. The altitude controller is that surface, not the physics behind it.

## Neighbours this contract does not own

- **ENERGY-11** `[A][BEH]` **Two products from one place means two machines.** A collector takes **matter** and
  an array takes **EM/kinetic flux**; they are different reservoirs and may share a site without sharing a
  device. The design-locked corona collector is not reopened by this contract.
- **ENERGY-12** `[A][BEH]` **Momentum from a reflecting shield is an ACTUATOR contribution, not propulsion.**
  External plasma reflecting off a wall pushes emitters, then the hull. It is **not reactionless** — the
  reaction medium is the external flow — and with no external flux there is no force. It enters the
  actuator envelope, not shield physics.
- **ENERGY-13** `[A][SYS]` **Every collector makes heat, and it lands in the EXISTING model.** There is no
  universal `thermalEfficiency`: conversion loss, particle and radiation heating are sources feeding the
  ship-heat model (`C12`), never a second thermal accounting. FOR: HEAT-4.

## Status — design stage

**This contract is the authority; the code is a snapshot of how much of it has been written.**
Nothing here is built. Two neighbours bound it:

| | |
|---|---|
| **the one neighbouring mechanic is LOCKED** | the corona's harvest is designed as a collector multiblock yielding a **fluid** (`coronal plasma`) with a depth/shield-drain risk-reward core, design-locked and unbuilt. ENERGY-11 exists so that a later electrical harvester does not silently replace it |
| **the one existing control surface is real** | `TileStationAltitudeController`, with a server test. ENERGY-10 makes it a UI over the array rather than deleting it |

**The exploit list is the test list.** The exploit rows are this contract's falsifiers, and the ones
that map directly: no FE from a static field without motion or flux (ENERGY-2) · an orbital generator that
runs forever without changing the orbit (ENERGY-3) · a rotational generator that transfers no angular
momentum (ENERGY-3) · `GENERATOR`↔`BOOST` perpetual motion from rounding (ENERGY-9) · `BRAKE` returning
more than the orbit loses (ENERGY-9) · a wrong corotation torque sign (ENERGY-4) · shield reflection giving
free thrust with no external flux (ENERGY-12) · accretion and spin double-counted (ENERGY-5) · wind kinetic
and Poynting double-counted (ENERGY-5) · a shielded station that cannot reject heat (ENERGY-13).