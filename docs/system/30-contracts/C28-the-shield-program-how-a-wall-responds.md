---
id: C28
covers: how a wall responds to what arrives — the coupling law over an incident state, and the rule that it may never read intent
confidence: RULED DESIGN-STAGE (2026-09-03). This is the design of record and it is the authority. The AFFS implementation was written before it and is not rewritten to it; the status table measures that distance rather than reporting defects
owner-subsystem: shields (vendored AFFS); weapons and fire-control are its callers, ship-heat its downstream
see-also: [C27 (WHERE the wall exists — the other half of the geometry/program split), C12 (ship heat — where absorbed energy lands), C26 (ENERGY-12 — reflected momentum is an actuator contribution), C20 (repair)]
---

# C28 — The shield program: how a wall responds, clauses WALL-1..WALL-18

The second of the shield's **two instruments**. [C27](./C27-the-shield-field-where-a-wall-exists.md)
answers *where the wall is*; this contract answers *what happens when something reaches it*. The two are
independent on purpose: a craft may re-shape its bubble without changing its response, and re-program its
response without moving a single emitter.

**The one rule the whole contract exists to hold**: *the physical law does not know who you are.*

## The coupling law

- **WALL-1** `[A][SYS]` The wall's response is a function of the **incident physical state alone**:
  `Γ = Γ(ω, k, polarisation, coherence, spectral brightness, modulation, particle mass, charge,
  kinetic energy, momentum, velocity relative to the wall)`. A program selects among physically
  distinguishable states; it never selects among *identities*. FOR: WALL-3.
- **WALL-2** `[A][SYS]` **The law may not read UUID, faction, "enemy", "friendly", "valuable plasma", or any
  gameplay intent.** A response that consults one of these is not a strict program — it is a permission
  check wearing a physics costume, and it fails the moment a neutral party reproduces the same physical
  state. FOR: WALL-3.
- **WALL-3** `[A][BEH]` **Similar states get similar coupling, necessarily.** A program tuned to admit one
  plasma will admit a physically similar plasma someone else brought. That is not a leak to be patched;
  it is what makes WALL-2 true, and any "fix" for it re-introduces intent.

## Matter and radiation

- **WALL-4** `[A][BEH]` **Kinetic matter primarily REFLECTS** from a supported wall; **radiant energy
  primarily undergoes absorption, scattering, polarisation change or mode conversion**
 . The clause's content is that the split is **physical** — a property of what
  arrived — rather than a lookup on which of two code paths delivered it, which is how
  `MECH-SHD-09` reaches the same answer today.
- **WALL-5** `[A][BEH]` **Incoming energy does not disappear.** Shield loss represents **decoherence and its
  recovery cost**, and the rest of the incident energy leaves as heat, scattered radiation, mechanical
  work or residual coherence. A program that makes energy vanish is a defect.
- **WALL-6** `[A][SYS]` **The heat lands in the EXISTING model.** Absorbed energy is a source term for
  `C12`'s ship-heat, never a second thermal accounting private to shields. FOR: WALL-5.
- **WALL-7** `[A][BEH]` **Reflected momentum is real and it goes somewhere**: incident flow → wall → emitters
  → structure → rigid body. It is an **actuator contribution** and never propulsion (`C26` ENERGY-12) —
  the reaction medium is the external flow, so with no external flux there is no force.
- **WALL-17** `[A][BEH]` **What the wall absorbs is what DRIVES it; the rest is scattered**. The wall is the emitter-supported resonance of `φ` (`C27` FIELD-2), so its response to
  radiation is a driven resonator's (coupled-mode theory). A mode decays two ways — re-radiation `γ_r`
  (scattering) and internal loss `γ_d` (decoherence, WALL-5) — and the **absorbed share is
  `γ_d / (γ_r + γ_d)`**. The drive is the **spectral brightness at the resonance**, within its linewidth,
  never the total power; a THERMAL source's brightness per mode is capped by the Planck law at its
  temperature, diluted by the solid angle it fills. `γ_d` **grows with the mode amplitude**
  (`γ_d0 + γ_2·|a|²`, the form of two-photon absorption); past the support threshold `Q_c` the wall
  collapses locally. Absorbed energy is a `C12` source plus the shield's own energy loss (WALL-5/6);
  scattered energy leaves. Only `γ_r`, `γ_d0`, `γ_2` are chosen (`tunable`). **Falsifiers, as
  properties**: the absorbed share never decreases as the drive grows; no thermal source at temperature
  `T` drives the wall harder than a blackbody of `T` filling the sky; a source off the resonance couples
  more weakly than the same power on it.
- **WALL-18** `[A][BEH]` **A neutron is matter and REFLECTS**. WALL-4 needs no charge: a wall
  stops a neutral projectile, and a neutron is one. The consequence a walled reactor cannot escape: every
  reflection costs the wall decoherence (WALL-5), and reflected neutrons do not vanish — they accumulate
  until something absorbs them (free decay is slow, a mean life of about fifteen minutes). A design that
  walls a neutron source owes the place where the neutrons are REMOVED, and that place heats (`C12`).

## The thermal problem, and why wavelength alone cannot solve it

- **WALL-8** `[A][BEH]` **A shield must not make a craft a thermos.** An ordinary operating program couples
  weakly to a radiator's thermal output, so a shielded station can still reject heat; a shielded station that cannot
  reject heat is this clause's falsifier.
- **WALL-9** `[A][BEH]` **"Infrared passes" is FORBIDDEN as the rule.** A hostile coherent infrared laser
  exists, and a wavelength-keyed exemption admits it. The discrimination is **coherence, spectral
  brightness, polarisation, modulation and mode** — a broadband, incoherent, low-brightness thermal
  emission is physically unlike a beam, and *that* difference is what the program reads.
- **WALL-10** `[A][BEH]` **An outgoing friendly weapon uses a coordinated transparent MODE**, phase-tagged by
  the same network that holds the wall — not an origin test. Today's code exempts any ray whose origin
  is already inside (`MECH-SHD-15`); that is a position check standing in for a physical mode, and this
  clause is what replaces it.

## Authorisation is not a law of nature

- **WALL-11** `[A][BEH]` **Authorisation changes an object's COUPLING to the wall; it does not disable the
  wall**. The physics has no concept of permission — a transponder synchronises with
  the field and makes its carrier locally transparent.
- **WALL-12** `[A][BEH]` **There is no global hole.** Authorised transit is local to the carrier and **must
  not vent the surrounding atmosphere** — which is exactly why it is a coupling change
  and not an aperture. `C27` FIELD-15's aperture is the other thing, and it *is* a universal hole.
- **WALL-13** `[V][BEH]` **The credential is a rotatable carried code, not an identity.** Already realised as
  `MECH-SHD-19`, and in the right shape: the wall asks what is carried, never who carries it. **A
  dynamic key waits on the weapon implementation** and **transit is not charged**.

## Programs are a capability, and they are learned

- **WALL-14** `[A][BEH]` **A program is a built and researched capability, not a config toggle.** What a
  network can discriminate is bounded by its control bandwidth and by what its owner has learned to
  measure — the same knowledge ladder the rest of the physics uses (`C25` HYPER-14).
- **WALL-15** `[A][BEH]` **Selective plasma coupling and active decoupling are PROGRAMS, not station
  shapes.** External collector, local `V0` throat, toroidal domain, selective coupling and active
  decoupling are five architectures that must all remain viable; declaring any one mandatory is a defect.
- **WALL-16** `[A][BEH]` **A program's failure is legible.** When a wall cannot discriminate what is arriving
  it says so and couples strongly — the safe direction — rather than guessing and admitting. A
  degradation that resembles success is a silent fallback, which is forbidden: a fallback announces itself.

## Status — design stage

**This contract is the authority; the code is a snapshot of how much of it has been written.** The AFFS
implementation was not derived from this model, so where it does something else it is **not yet
rewritten**, never in breach. The table measures distance, not defects.

| clause | how far today's code is from it |
|---|---|
| WALL-1, 2, 3 | **not written, and nothing rival exists.** No shipped code reads a faction — because none reads anything beyond the damage source. The work is to keep that true once a real program arrives |
| WALL-4 | **half-realised as a binary** (`MECH-SHD-09/10/14/20`), keyed on how a hit was delivered rather than on what it physically is. The clause's content is that the split becomes physical |
| WALL-5, 6 | **partly written**: impacts cost the field energy (`MECH-SHD-14`); routing the remainder into `C12` is still to do |
| WALL-7 | **not written** — no momentum reaches the hull from the wall (`C26` ENERGY-12) |
| WALL-8, 9 | **not written**, and this is the open design question. There is no radiant program at all, so neither the thermos problem nor its answer exists yet |
| WALL-10 | **not written.** Today an outgoing ray is exempted by origin (`MECH-SHD-15`); the coordinated transparent mode is what replaces it |
| WALL-11, 12 | **not written**; `MECH-SHD-19` already supplies the credential half |
| WALL-13 | **already realised** `[V]` |
| WALL-14, 15, 16 | **not written** — a program is not yet an object at all |
| WALL-17 | **not written.** Today the attenuated share is simply dropped (`flux·(1−a)`, ship-heat MECH-HEAT-21) — which equals "everything scattered", so it already satisfies the clause for diffuse starlight and nothing else. Falsifiable as the three properties; consistent with HEAT-10's cap below 1, the generator's draw still heating, and the corona's stronger absorption falling out of the dilution going to one; edge — several sources inside one linewidth add as intensities, not amplitudes, because they are mutually incoherent |
| WALL-18 | **not written** — no walled reactor exists. No contradiction with WALL-1 (charge modulates the coupling, it does not gate reflection — shields already stop neutral projectiles); edge — the sink is owed by the DESIGN that walls a neutron source (a walled reactor), not by the wall |

**The half-implementation to watch for.** WALL-2 is currently true because the wall reads almost
nothing. The first real program is where a faction test will appear, wearing the name of an
optimisation — and WALL-3 is what forbids the "fix" that follows it, because the first complaint about a
program will be that someone else exploited it.

