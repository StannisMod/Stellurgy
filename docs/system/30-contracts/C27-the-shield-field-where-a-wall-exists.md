---
id: C27
covers: what a shield IS before anything hits it — a boundary between vacuum phases, who sustains it, what it costs, and what shapes it may take
confidence: RULED DESIGN-STAGE (2026-09-03). This is the design of record and it is the authority. The AFFS implementation was written before it and is not rewritten to it; the status table measures that distance rather than reporting defects
owner-subsystem: shields (vendored AFFS) + the hyperdrive, which drives the same field in the other regime
see-also: [C28 (how the wall RESPONDS — the other half of the geometry/program split), C25 (HYPER-1..4 — the same field, metric mode), C21 (atmosphere — what a bounded region contains), C12 (ship heat)]
---

# C27 — The shield field: where a wall exists, clauses FIELD-1..FIELD-19

The shield is **two independent instruments**: *geometry* answers **where the
wall exists**, *program* answers **how it responds to what arrives**. This contract owns the first;
[C28](./C28-the-shield-program-how-a-wall-responds.md) owns the second. They are separate because a
craft can change one without touching the other, and a design that fuses them cannot express that.

**Why a contract rather than a subsystem doc.** `20-subsystems/shields.md` describes the BUILT mechanics
of the AFFS implementation, with `file:line`. This document is the **physical model those mechanics are
meant to follow from**, rather than a second description of the code.

## What a shield IS

- **FIELD-1** `[FICTION]` A shield is a **boundary between two vacuum phases** — ordinary `V0` and a
  coherently polarised metastable `V*` — and never a placed solid, a hitbox, or a pool of hit points.
  This is the same metastable degree of freedom the hyperdrive drives (`C25` HYPER-1) in its other
  regime; **one law, two modes**, and that identity is the reason neither subsystem may grow a field of
  its own (HYPER-3).
- **FIELD-2** `[A][BEH]` **The wall is sustained by COHERENT SUPPORT from several emitters, and a lone emitter
  cannot hold a macroscopic domain.** An emitter contributes amplitude, phase and mode to a shared
  resonance; it does not project a private bubble that happens to overlap its neighbours'.
- **FIELD-3** `[A][SYS]` **The signed-distance field is a REPRESENTATION, not the law.** The shipped
  smooth-union of per-emitter spheres (`MECH-SHD-04`) is a perfectly good numerical stand-in for a
  support value and stays legal. What this clause settles is that it is not the physics, so nobody
  reasons from spheres where the model has a resonance. **The law is a conceptual support `Q(x)` with a
  threshold `Q_c`, and the wall is the level set `Q(x) = Q_c`.** FOR: FIELD-2.
- **FIELD-4** `[A][BEH]` **A lone emitter is not a defect in the OTHER regime.** Sustaining a *small* window
  is the graded rule at its floor (`C25` HYPER-4). Only a *macroscopic wall* from one emitter is
  forbidden, and that is a statement about this contract's mode alone.
- **FIELD-5** `[REAL→FICTION]` **The phenomenon is natural first and engineered second.** The effect is
  discovered around strongly magnetised compact objects, and an artificial shield is the same physics
  reproduced. A natural occurrence that the artificial model cannot express is a defect in the model.

## Energy, roles and support

- **FIELD-6** `[A][SYS]` **Shield energy is COHERENT EXCITATION energy, not a second electricity.** It is not
  interchangeable with the ordinary energy network's units by fiat, and the conversion has a place and a
  cost. FOR: FIELD-7.
- **FIELD-7** `[V][BEH]` **Generator, accumulator and emitter stay physically distinct roles**, and their
  three limits — production, storage, projection — remain independently binding. Built: the routing
  (`subsystem-network` MECH-NET-02/03) and `MECH-SHD-05/06`.
- **FIELD-8** `[A][BEH]` **Coverage is DERIVED from construction and emitter geometry**, never authored as a
  radius on the network. What a craft protects is a consequence of what was built.
- **FIELD-9** `[A][BEH]` **Damage reduces physical SUPPORT; it never applies abstract sectional hit points.**
  A weakened emitter holds less of the boundary, and the boundary responds locally
 . Built in the direction of this clause as `MECH-SHD-21/22/23`.
- **FIELD-10** `[A][BEH]` **Priority redistribution moves SUPPORT AVAILABILITY, not sectional durability.** A
  group setting changes what an emitter can hold, and the geometry follows.

## Geometry — a freedom bounded by support, never a menu

- **FIELD-11** `[A][BEH]` **Any geometry the support can hold is legal**: a sphere, a stretched or asymmetric
  bubble, concave regions, a throat, a tunnel of ordinary `V0`, a torus, several handles. **No shape is
  mandatory and none is forbidden by name** — in particular a toroidal station with a plasma channel is
  an *emergent engineering answer*, never the required form.
- **FIELD-12** `[A][BEH]` **Complex geometry costs what it costs, and the costs are engineering ones**:
  emitter placement and count, phase-control bandwidth, support margin, surface area, curvature,
  stability, control burden, failure modes. A topology that is cheaper than a sphere for no stated
  reason is a defect.
- **FIELD-13** `[A][BEH]` **One resonant network, one frame**. A network's emitters must share a frame
  for the resonance to be defined at all, so the failure of an unresolvable ship frame is *"no place for
  the field"*, not *"no field"*. Today's code fails **open** (`MECH-SHD-13`); this clause is what the
  rewrite implements.

## The aperture — a channel, and the one thing that is a hole

- **FIELD-14** `[A][BEH]` **Losing emitters cannot PUNCTURE a shield**: the region is a union of supported neighbourhoods, so withdrawing one shrinks the set, it does
  not perforate it. Whatever a degraded shield is, it is closed.
- **FIELD-15** `[A][BEH]` **An aperture is a declared CHANNEL, anchored by a face**, not a gate block and
  not a global disabling of the wall. It is the only construct that makes a genuine hole, and it is
  therefore **universal**: it admits what is hostile as readily as what is welcome, and it exposes
  whatever the region contained.
- **FIELD-16** `[A][SYS]` **The bubble is a SET, not a container** — its extent is the region satisfying the
  support predicate, and its price is the size of that set. An aperture **removes points from a set**;
  it does not breach a vessel. Three retractions in one session came from importing container
  vocabulary here, and the vocabulary is what did the damage: *molecules*, *venting a
  stored volume*, *an opening*. **A connectivity question about the interior is not answerable in this
  model at all**, because connectivity is a flood fill and the model has none. FOR: FIELD-15.
- **FIELD-17** `[A][BEH]` **The shield may be an atmospheric containment boundary**, and what
  that containment means belongs to `C21` and the life-support design, not here. This contract states
  only that the boundary exists and where; it never states what the enclosed air does.

## The bound on the fiction

- **FIELD-18** `[A]` **The microscopic theory is not expanded beyond what gameplay needs to be derived**
 . A deeper physical story is permitted in the design of record and must not become a
  simulation requirement: `C25` HYPER-1 is the whole of the fiction, and everything here is either
  `[REAL]` astrophysics or a `[GAME]` approximation of it.

## Containment — the same law in a compact device

- **FIELD-19** `[A][BEH]` **An ENERGY-CONTAINMENT SYSTEM is a wall-mode instrument of the same law**. It is a compact, specialised version of the shield system — its own emitters,
  generator and accumulator in one machine — that holds a wall around a working volume INSIDE the ship:
  a torch's chamber and nozzle, a field-walled reactor. It is **not a second field** (HYPER-3): it drives
  `φ`'s wall mode like any shield, so everything here and in `C28` binds it — what its wall absorbs is
  heat in the ship's loop (WALL-6), a neutron is reflected and must be removed (WALL-18), and a
  macroscopic wall needs coherent support from SEVERAL emitters (FIELD-2), however compact the device.
  Two things make it a device rather than a shield: it is **built as a component** (a torch or a walled
  reactor does not assemble without one — no "field technology" gate, the knowledge comes from the shield
  line), and it is **modelled by construction estimates**, never by simulating a second wall inside the
  hull. Its failure is legible (WALL-16) and lands as contact damage on the structure it was holding off.

## Status — design stage

**This contract is the authority; the code is a snapshot of how much of it has been written.** The
AFFS implementation is a working shield network that was not derived from this physics, so where it does
something else it is **not yet rewritten**, never in breach. Nothing below is a defect report; it is a
distance measurement, and the distance is the implementation's to close.

| clause | how far today's code is from it |
|---|---|
| FIELD-1, 2, 5, 6 | **nothing built, nothing rival.** The code holds no competing model of what a shield is; it simply does not model it |
| FIELD-3 | `MECH-SHD-04`'s smooth-union is a **legitimate representation** and stays legal under this clause. What the clause settles is that it is not the law, so nobody reasons from spheres. replaces it with a support value |
| FIELD-7, 9, 10 | **already realised** — `subsystem-network` MECH-NET-02/03, `MECH-SHD-05/06`, `MECH-SHD-21/22/23` and `MECH-SHD-17` all point this way. Useful evidence that the clauses are buildable |
| FIELD-11, 12 | **not written.** Geometry is whatever the sphere union yields and there is no support-margin accounting to price a shape against |
| FIELD-13 | **not written.** Today an unresolvable ship frame fails open; the failure is a missing *place*, not a missing field, and this clause is what the rewrite implements |
| FIELD-14, 15, 16 | **not written** — no aperture exists. a face-anchored channel with shaping and cycling is the design it will be built to |
| FIELD-17 | **held by C21.** What the enclosed air does is C21's subject |
| FIELD-19 | **not written** — no torch, no walled reactor, no containment machine. Falsifiable (a torch or walled reactor refuses to assemble without one; what it absorbs appears in the loop; no second field type exists in code); consistent with HYPER-3 (same law), FIELD-2 (several emitters even when compact), FIELD-11/12 (a construction estimate IS a priced shape — it reads the build, it does not simulate support); edge — it never joins or overlaps the ship's shield network, being a separate machine with its own volume |

**The trap this contract exists to close** is not a missing mechanic. It is that an SDF *reads* like a
physical model, so a later reader reasons about **spheres** — and two subsystems independently building a
union of per-emitter regions (this one and the hyperdrive's window) is the evidence that such reasoning
spreads on its own.

