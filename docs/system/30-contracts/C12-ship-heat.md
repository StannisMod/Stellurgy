---
id: C12
covers: ship thermal system — heat as energy in three reservoirs, radiative rejection against an incident-flux environment, the chiller heat pump, the failure ladder, the thermal signature, and the emergency slug dump
confidence: mixed — most clauses are built and pinned; HEAT-11 is partly built (three rungs of five, its ORDER clause not pinned) and HEAT-13 remains [A]. The two missing rungs are missing callers: nothing in the heat subsystem spends through the structural-damage engine, and ignition is driven only by a block change. See the status table for the per-clause state
owner-subsystem: ship-heat; the unbuilt clauses also touch atmosphere-oxygen and the ship subsystems, and HEAT-10 additionally reads the shields subsystem
see-also: [C10 (ship stats — the `heat` slot), C9 (ship control — the pilot-side sink toggle), C21 (atmosphere model), C20 (repair)]
---

# C12 — Ship thermal contract, clauses HEAT-1..18

**RATIFIED by the maintainer 2026-07-26.** `HEAT-n` are permanent anchors: never renumber, never
reuse. The clause tags below are the state of each clause (`[A]` asserted, `[V]` verified at source,
`[T]` pinned by a test); `[V]` citations pin the code each clause reuses or contradicts. Balance
magnitudes are `tunable` and are never pinned.

**Terms.** **reservoir** = a body that stores heat energy and has a temperature. **network** = one
connected graph of heat pipes plus everything clipped to it. **rejection** = energy leaving the ship
through a radiator. **incident flux** = energy arriving from outside. **signature** = the passively
detectable emission a ship cannot avoid. **slug** = a chargeable, ejectable heat-sink item.

## Clauses

**Quantity and reservoirs**

- **HEAT-1** `[A][BEH]` Heat is ENERGY, carried internally in the same unit as electrical energy (RF/FE), so
  a machine's draw, the chiller's work and stored heat are one currency. **Every player-facing surface
  labels it a heat unit and never RF/FE** — naming a familiar energy unit asserts the heat is
  harvestable, which it is not.
- **HEAT-2** `[A][BEH]` Temperature exists at exactly two places: a **network** and a **radiator**. There is
  no global ship temperature scalar. Introducing one is a violation.
- **HEAT-3** `[T][SYS]` Three reservoir kinds only: **zone air**, **network**, **radiator**. A whole
  connected network is ONE thermodynamic object — one `Q`, one `C`, one `T = T₀ + Q/C`. Per-pipe
  temperature is a violation (explicitly rejected as unaffordable). Pinned by `HeatLoopTest#theSameHeatInALongerLoopIsALowerTemperature`. FOR: HEAT-2.

  > **Why a PLANET is not on that list.** Not an
  > omission: **a reservoir is something whose `T` rises when `Q` is added to it, and a planet's does
  > not.** An infinite sink cannot be a reservoir by definition. The planet participates as a TERM
  > instead — `T_amb` in HEAT-7's law, HEAT-8's single incident-flux term, HEAT-4's convection
  > channel — evaluated per radiating cell by `HeatEnvironment`. **That per-cell evaluation is what
  > lets one place outside be hotter than another**; a single planetary reservoir at one `T` would
  > make a ship beside a lava lake and one on an ice cap read identically. This is the same property
  > C21 leaves unstated about an atmosphere's extent: an environmental medium is a FIELD, not a body.
- **HEAT-4** `[A][BEH]` **Conservation.** Heat energy leaves a ship through exactly **four**
  channels: a radiator's net rejection, an ejected slug, convection into an external atmosphere, **and
  the propellant feed of a burning engine (below)**. Energy that
  disappears by any other route — including being *dropped* because a container is full — is a
  violation. Violated by the upstream cut, whose `addHeat` returns `false` and adds nothing on
  overflow `[V]`.
  > **The FOURTH channel: the propellant feed of an engine that
  > is BURNING.** A heat exchanger on the feed carries loop heat into the reaction mass on its way out:
  > capacity `ṁ · c · (T_loop − T_propellant)` (plus latent heat for a cryogen), where `ṁ` is the mass
  > ACTUALLY leaving — zero with no burn, and zero when consumption is disabled (a propellant that is not
  > spent carries nothing out, or the creative flag would open a free exit). The exchanger couples by
  > temperature in both directions: a propellant warmer than the loop heats it. Any OTHER ejection of
  > heat-carrying mass — coolant vented with no burn — is a dump and is bound by HEAT-17's relation.
  > *Stress-test*: falsifiable (the loop's energy ledger with and without a burn); unbuilt — no engine
  > heat model and no heat network on this line; consistent with HEAT-17 by scope (reaction mass is not a
  > slug: it is expensive) and with the no-throttling rule (HEAT-12); edge — `T_propellant` is the fluid's
  > own temperature, so the exit rewards a cryogen and does little for a warm propellant.

**Sources**

- **HEAT-5** `[A][BEH]` Generation is DERIVED, never an authored per-ship constant: machines through the
  tile-heat registry, blocks through the material table. The registry must actually be populated —
  the upstream `HeatHandler.heatHandlers` map has no registration path and therefore always answers
  zero `[V]`.
- **HEAT-6** `[T][BEH]` The chiller is a **heat pump**: it consumes work `W` to move heat `Q` to a higher
  temperature, and the hot side receives `Q + W`. A chiller whose own work does not enter the loop is
  a violation. Pinned by `HeatChillerTest#theHotLoopReceivesTheHeatPlusTheWork`.

**Rejection and environment**

- **HEAT-7** `[T][BEH]` `netReject = k · A · (T_rad⁴ − T_amb⁴)`, in Kelvin, evaluated in floating point.
  Area scales rejection linearly, temperature to the fourth power. Pinned by `HeatRejectionTest#rejectionScalesWithTheAreaBuilt`, `HeatRejectionTest#rejectionFollowsTheFourthPowerOfTemperature`.
- **HEAT-8** `[T][BEH]` The environment couples through a **single incident-flux term** accumulating every
  source — star, planet, and other ships' radiators — never through per-source mechanisms. Where
  incident flux exceeds rejection the net is negative and the ship heats regardless of its own state. Pinned by `HeatEnvironmentTest#aWorldsWarmthAndAStarArriveThroughTheSameTerm`, `HeatEnvironmentTest#aShipUnderAFierceStarHeatsThroughItsRadiators`.
- **HEAT-9** `[T][BEH]` A radiator radiates one way and requires N empty blocks in front of it (default 10,
  `tunable`), checked **against the ship's own blocks in the ship frame**, never against the world.
  Blocked ⇒ zero rejection from that radiator, and the blocked state is reported to the player. Pinned by `HeatRejectionTest#anObstructedCellShedsNothingAndSaysWhereTheBlockIs`.
- **HEAT-10** `[T][BEH]` A shield ATTENUATES incident flux, never eliminates it:
  `flux_eff = flux · (1 − a)` with `a` strictly less than 1, hard-capped in code. No configuration may
  produce total thermal immunity.
  > The attenuated `a·flux` splits into SCATTERED (it leaves)
  > and ABSORBED (a source term in this contract, plus the shield's own decoherence loss), by `C28`
  > WALL-17: diffuse starlight is mostly scattered, a coherent or very bright source mostly absorbed. The
  > cap `a < 1` stands. *Stress-test*: see WALL-17's row in C28. Pinned by `HeatEnvironmentTest#aShieldThinsTheFluxAndNeverRemovesIt`.

**Failure ladder**

- **HEAT-11** `[A][BEH]` When generation exceeds rejection the consequences fire in this ORDER, each keyed
  to this SUBJECT: **crew damage** (zone air) → **block damage** (the reservoir the block touches;
  spent through the block-damage budget, never ad-hoc block replacement) → **fire** (zone air) →
  **hyperdrive refusal** (the network the drive is attached to) → **hull melting** (the maximum of
  zone air, network and incident flux, against the block's material melting point). Thresholds are
  `tunable`; the order and the subjects are the contract.
- **HEAT-12** `[A][BEH]` Machine throttling is NOT a consequence of overheating. Block damage carries that
  role.
- **HEAT-13** `[A][BEH]` Fire requires an atmosphere: no heat-driven ignition in vacuum.
- **HEAT-14** `[A][BEH]` Crew heat damage REUSES the existing hostile-atmosphere types and their suit
  immunity chain (the `Atmosphere.VERYHOT` / `SUPERHEATED` values and their suit-immunity chain, C21) `[V]` rather than
  introducing a parallel damage path. This makes the first rung depend on per-entity atmosphere resolving
  in the ship frame — which it does not: `AtmosphereHandler.getAtmosphereType(Entity)`
  (`AtmosphereHandler.java:706-716`) keys the entity's WORLD position while a ship's blob is seeded in
  subspace `[V]`, measured by `test/client/VSShipAtmosphereFrameSpikeTest`. **Violated aboard a ship.**

**Signature**

- **HEAT-15** `[A][BEH]` The signature is two distinct terms with distinct consumers: **detection range
  scales with the square root of total radiated power**, and **lock/track quality scales with radiance
  `σT⁴`, a function of temperature alone**. Collapsing them into one number is a violation — the whole
  build trade (compact hot array vs large cool array) lives in their difference.
- **HEAT-16** `[T][BEH]` Silence is never invisibility. With every sink closed the hull still radiates far
  above the cosmic background, so running silent reduces detection RANGE and never drives the
  signature to zero. Pinned by `RunningSilentTest#aShipRunningSilentIsFoundCloserAndIsStillFound`.

**Emergency dump**

- **HEAT-17** `[A][BEH]` `E_slug / t_charge < P_radiator(cheapest continuous tier)`. Sustained throughput
  from ejected slugs must stay below the cheapest continuous radiator tier, so no quantity of carried
  slugs can substitute for radiators. **The cheapest continuous tier is ONE radiating cell** (ruling
  2026-09-29). This is a RELATION, not a value: it survives rebalancing,
  including by a modpack author, and is the clause that keeps the dump an emergency.
- **HEAT-18** `[A][BEH]` A slug's capacity is derived from its MATERIAL (`ρ · c · (T_melt − margin − T₀)`),
  never authored per item, and the same material table drives HEAT-11's melting rung. An ejected slug
  remains a physical object on the same physics: it melts and ignites what it lands on by spending its
  own remaining energy (hence self-limiting), injures whoever picks it up hot, cools by HEAT-7's law,
  and is a legitimate passive target for anything that homes on HEAT-15's terms.

## Status

The quantity, the reservoir, rejection, the environment it happens against, the signature and silent
running are built and pinned. The failure ladder is PARTLY built: the crew's air, the drive's loop and
hull melting fire, and the clause that is the ladder's actual contract (the ORDER) is not pinned because
two rungs have no heat-driven trigger. Owning subsystem doc: `20-subsystems/ship-heat.md`.

| clause | state | pinned by |
|---|---|---|
| HEAT-1 heat is energy, labelled a heat unit | `[T]` built | `test/server/HeatLoopTest` (the probe reports heat units; no RF/FE reaches a player surface) |
| HEAT-2 temperature exists per loop, no ship scalar | `[V]` built | `HeatNetworkState` is the only carrier; there is no ship-wide field to violate it with |
| HEAT-3 one `Q`, one `C`, one `T` per connected loop | `[T]` built | `HeatLoopTest.theSameHeatInALongerLoopIsALowerTemperature` — falsified by reverting the capacity term |
| HEAT-4 conservation | `[T]` built for the reservoir | distribution loses nothing (the last member takes the remainder), and the reload round-trip is pinned by `SubsystemNetworkRestartTest.aCoolantLoopsEnergyComesBackFromItsBlocks`. The exits are pinned under HEAT-6/7/17 |
| HEAT-5 generation derived, the registry actually populated | `[A]` built, unpinned | a Forge capability rather than a registry — a foreign tile can be GIVEN one. Every powered machine carries it through `WasteHeat` and the two `TileWasteHeat*` bases. A unit pin would need a loaded configuration (an unloaded one has `shipHeat` off and a waste fraction of 0); the pin belongs in a server group and is not written |
| HEAT-6b **which** coefficient prices the pump | `[T]` built | The duty is COOLING, so the price is `W = Qc · (Th − Tc) / Tc` — the coefficient is `Tc / (Th − Tc)`, scaled by `shipHeatChillerCopFraction`. **A cooling coefficient below one is legal and must stay legal**: it says the work costs more than the heat it moves, which is the regime a wide gradient puts you in, and it is what makes "driving the hot side further costs more for less" true rather than decorative. Any floor at 1.0 removes the Carnot ceiling this mechanic is built on. HEAT-6 pins only `Qh = Qc + W`, an identity that holds for ANY coefficient, so this clause is what pins the coefficient. The ceiling on the curve is `shipHeatChillerMaxCop` |
| HEAT-7 rejection is `k·A·(T_rad⁴ − T_amb⁴)`, in floating point | `[T]` built | `HeatRejectionTest` — area linear and temperature quartic, each falsified separately in production |
| HEAT-9 one-way, N blocks clear, checked in the SHIP frame, blocked reported | `[T]` built | `HeatRejectionTest.anObstructedCellShedsNothingAndSaysWhereTheBlockIs`. The ship-frame half is ship-heat MECH-HEAT-10, and it is not exercised on a moving ship |
| HEAT-6 the chiller is a heat pump and the hot side receives `Q + W` | `[T]` built | `HeatChillerTest` — TWO loops with the pump between them, so the hot side is a real reservoir rather than an offset. Falsified by delivering the heat without the work: the pump invented 240 units a tick |
| HEAT-8 a single incident-flux term accumulating every source | `[T]` built | `HeatEnvironmentTest.aWorldsWarmthAndAStarArriveThroughTheSameTerm` — a warm world and a real star in a live cell, one term, the loop's response tracking the difference between the two reported fluxes. Falsified by giving the star a pathway of its own, which showed up as a shield that could not stop it |
| HEAT-10 a shield attenuates and never eliminates, hard-capped in code | `[T]` built | `HeatEnvironmentTest.aShieldThinsTheFluxAndNeverRemovesIt` — asked for 100 % and refused it. Falsified by trusting the config's own range: the shielded loop then took exactly zero |
| HEAT-11 the ORDER and the SUBJECT of each rung | `[A]` **partial — three rungs of five** | the crew rung is fed by `AirState.addHeat` and `HeatNetwork.conductIntoCabins` — a loop warms the sealed rooms its blocks stand in (the warming half is unpinned). Crew damage (`HeatFailureLadderTest`, `client/VacuumAndSuitClientGroupTest.overheatedZoneAirHurtsAnUnsuitedCrewman`), the hyperdrive refusal (`HeatFailureLadderTest`) and hull melting (`HullMeltsPastItsMaterialTest`, `unit/HullMeltingTest`) are built and pinned per rung — the melting rung taking the MAXIMUM of zone air, the loop and the incident flux, against the block's own material. **The ORDER clause itself is NOT pinned**: the block-damage rung has no caller (the structural-damage engine exists and carries a thermal channel, but nothing in the heat subsystem spends through it) and the fire rung has no heat-driven ignition path (HEAT-13), so an order with two holes cannot be exercised end to end |
| HEAT-12 throttling is not a consequence | `[V]` built by construction | nothing anywhere throttles a machine on temperature; the ladder's only machine-facing rung is a refusal to jump |
| HEAT-13 fire requires an atmosphere | `[A]` unbuilt, and small | the clause's own subject is already satisfied by the type flags — `SUPERHEATED` allows combustion, `SUPERHEATEDNOO2` and `VACUUM` do not — and `onBlockChange` already carries the conversion table. What is unbuilt is the TRIGGER: `AtmosphereHandler.onBlockChange` (`AtmosphereHandler.java:172-213`) fires on a block CHANGE, never on the temperature moving, so a room that heats around its furniture ignites nothing; and its extinguish branch is COMMENTED OUT (`:214-233`), so fire outlives the air it needs |
| HEAT-14 crew damage REUSES the hostile types and the suit chain | `[T]` built | the derivation answers with the existing `VERYHOT`/`SUPERHEATED` singletons, so no second damage path exists to violate it. Violated on the ship frame: a blob aboard an assembled ship is seeded in subspace while the entity lookup is world-frame |
| HEAT-17 sustained slug throughput stays under the cheapest continuous radiator tier | `[T]` **VIOLATED at the defaults** | The cheapest tier is ONE cell — maintainer ruling 2026-09-29, *"одну ячейку"*, matching `TileHeatRadiator`'s "a cell, not a plate". At the defaults a dump sustains 40 000/s against one cell's 6 000/s. Pinned as it stands by `server/HeatDumpBuysSecondsTest.atTheShippedDefaultsOneDumpOutshedsOneRadiatingCell`, which reads both sides off the booted server; the balance fix is the maintainer's and flips the pin |
| HEAT-18 a slug's capacity is derived from its MATERIAL, and it stays a physical object | `[T]` built | `unit/ThermalMaterialsTest`, `server/ThermalMaterialVolumeTest`, `HotSlugPhysics` (cools by the same curve, melts by spending, burns on pickup). The decoy half (one white-hot cell beats a 64-cell array on BOTH terms, and it shows exactly the surface it cools through) is unpinned |
| HEAT-15 the signature is TWO terms with distinct consumers | `[A]` built, unpinned | a matched pair computed from the running config (an 8x8 plate at the reference temperature against four times the area a quarter as bright) sheds the SAME power at the SAME detection range and is four times the lock; a pin needs a loaded configuration (at an unloaded reference of 0 K every surface radiates nothing) |
| HEAT-16 silence is never invisibility | `[T]` built | `server/RunningSilentTest` — shutting every sink takes most of the signature and leaves the hull's own glow, which a second reading with the loop EMPTIED shows is a floor rather than leakage. Falsified separately by deleting the hull term (`radiatedPowerMilli:0`, a ship that cannot be seen at all), by a cell that keeps radiating when shut, and by a skin that reads the loop instead of the cabin |

## Implementation notes

The mechanisms that meet these clauses are the subsystem's (`20-subsystems/ship-heat.md`); each clause
is the promise, the mechanism is cited:

| clause | met by | note |
|---|---|---|
| HEAT-5 | MECH-HEAT-02 and the `IHeatEmitter` capability (C5) | a capability, not a registry: a registry keyed by machine class cannot be populated by the mod that owns the machine, and the machines this system exists for belong to other mods. The word "registry" in the clause describes a mechanism, not the contract |
| HEAT-7 | MECH-HEAT-08, MECH-HEAT-18 | no ambient constant: `k·A·T_amb⁴` is an incident flux spelled as a temperature, so HEAT-8's single term IS the subtraction's second half |
| HEAT-8 | MECH-HEAT-18, MECH-HEAT-19 | on a body the term is the body's temperature and the star is not added (it would be counted twice); a ship in a cell sees the star |
| HEAT-9 | MECH-HEAT-10 | |
| HEAT-15 | MECH-HEAT-32 | the signature sums GROSS emission, never net rejection, or a ship would be invisible beside a star |
| HEAT-16 | MECH-HEAT-33, MECH-HEAT-33b, INV-HEAT-31 | the hull-skin term, its read-side clamp above zero (the mirror of HEAT-10's cap below one), and the declared hull-area proxy |
| HEAT-18 | MECH-HEAT-30, MECH-HEAT-31 | the dump's slug is the loudest thing a ship can do: one white-hot cell is brighter than a whole radiator array in both terms (power and radiance) |

## Known violations

- **HEAT-14 is violated aboard a ship** — the crew rung cannot fire aboard a ship until the
  atmosphere gate resolves in the ship frame.
- **HEAT-17 is violated at the shipped defaults** (see the table).
- **HEAT-11's order** is not exercisable end to end (see the table).
