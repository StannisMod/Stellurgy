---
id: ship-heat
owns: [subsystem/heat/, tile/heat/]
entrypoints: [HeatNetwork#DOMAIN, HeatNetwork#tickThermodynamics, HeatNetwork#cacheNeighbours, CapabilityHeatEmitter#get, CapabilityHeatPump#get]
depends-on: [subsystem-network, atmosphere-oxygen]
depended-by: []
contracts: [C12, C1, C3, C4, C5, C7]
confidence: high
---

## Purpose

The ship's coolant loop: where a machine's waste heat goes, how hot that makes the ship, how it gets
off again — and what the getting off makes the ship visible as. This document covers **every shipped
slice**: the quantity, the reservoir, radiative rejection, the chiller that decides what temperature
the rejection happens at, the environment the rejection happens AGAINST, the material table, the
emergency dump and the slug it throws, the failure-ladder rungs the game can express today (a
compartment that turns on its crew, a drive that will not fire, a hull that melts), and the thermal
SIGNATURE those exits produce — with the choice to shut them and the floor that shutting them cannot
get below.

## Responsibility boundary

**Owns:** what a heat unit IS; a loop's energy, capacity and temperature; the blocks that make up a
loop and their share of its energy; which machines make heat and how much; radiative rejection and the
clearance a radiating cell needs; what a chiller shifts between two loops and what it costs.
**Owns, additionally:** what the outside is doing to the ship — one incident-flux term, its
contributors, and how much of it a raised shield keeps off.
**Does NOT own:** membership and connectivity (the shared `subsystem-network` primitive); the damage a
hostile atmosphere does, which belongs to `atmosphere-oxygen` and is merely REACHED from here; the
two ladder rungs that have no subject yet — block damage and fire (hull melting, the dump block, the
slug as a physical object and the thermal signature ARE built and owned here: MECH-HEAT-29,
`TileHeatDump`, `HotSlugPhysics`, `ThermalSignature`); what a material IS thermally is owned HERE (the
table); who READS the thermal signature (no sensor does yet); the power a machine draws (its own
subsystem); how bright a star IS or where a body sits (the universe layer — this only reads them);
the field's geometry (`shields`); whether a jump may happen at all, which is the jump gate's — this
supplies one number and one clause to it.

## Key types

| type | role |
|------|------|
| `HeatNetwork` | the domain, the unit, and the loop's per-tick physics. |
| `HeatNetworkState` | one loop's `Q`, `C`, `T` and last tick's generation — a readout, not the record. |
| `IHeatNode` | a block that is thermal mass: a capacity, and its own share of the energy. |
| `IHeatEmitter` | a machine with waste heat to give up. Drain-shaped on purpose. |
| `CapabilityHeatEmitter` | the ONE way a loop learns a machine makes heat — ours and foreign alike. |
| `HeatNetworkWatcher` | the invalidation a machine cannot send for itself: a chunk arriving. |
| `TileHeatLoopBlock` | what a pipe and an accumulator have in common — membership, mass, persistence. |
| `TileHeatPipe` / `TileHeatAccumulator` | a little mass and reach; a lot of mass and nothing else. |
| `IHeatExchanger` | a machine that moves heat OUT of its loop. It never says how much. |
| `TileHeatRadiator` | one radiating cell: a facing, a clearance, and the only way heat leaves for good. |
| `HullClearance` | "are the next N blocks along my facing empty?", shared with the ejection ports. |
| `IHeatPump` | a chiller: it declares a SIZE and where its hot side is. It never says how much heat, and never a temperature. |
| `TileHeatChiller` | the chiller, standing between two loops and belonging to neither. |
| `HeatEnvironment` | what the outside delivers to one radiating cell: one term, its contributors, and the shield. |
| `ShieldCoverage` | AFFS-side facade: "is this block under the field?", frame conversion included. |
| `TileHeatIntakeDuct` / `BlockHeatIntakeDuct` | the chiller's mouth: it answers which zone is on the other side, and stores nothing. |
| `AirState` | a zone's gas AND its heat: a temperature, a capacity from pressure x volume, and the mixing rule. |
| `ShipDrive#coolantKelvin` | the one number the jump gate is given: how hot the loop bolted to this ship's generator is running. |
| `ThermalMaterial` | one substance, thermally: density, specific heat, and the temperature past which it is no longer a solid. |
| `TileHeatDump` / `IHeatSink` | the emergency dump, and the seam a loop finds any heat-taking machine through. |
| `HotSlugPhysics` | what a charged slug does once it is out: cools, melts what it lands on, burns whoever grabs it. |
| `ThermalMaterials` | the table of them, player-editable JSON, resolved by ore dictionary; and the one place joules become heat units. |

## Mechanics

- **MECH-HEAT-01 heat does not flow inside a loop** — a connected loop is ONE body at one
  temperature (C12 HEAT-3), so there is nothing for the max-flow solver to distribute between its
  members. The domain runs its own per-tick step instead: collect, pump, reject, then spread.
  `HeatNetwork.java:175-262` `[V]`
- **MECH-HEAT-02 generation is collected by ADJACENCY, not delivered as flow** — a machine is not a
  network node at all, which is what lets a foreign reactor feed a loop without implementing
  anything. The seam is a Forge capability, so a machine of ours hosts it and a machine that is not
  can be GIVEN it; there is one read path and no `instanceof`.
  `HeatNetwork.java:547-563`, `api/capability/CapabilityHeatEmitter.java:31-36` `[V]`
- **MECH-HEAT-02b the neighbours are worked out at REBUILD, not every tick** — the set changes only
  when a block changes, so re-deriving it every tick would spend hundreds of world lookups on an
  answer that did not move. Only how much each machine is HOLDING is read per tick.
  `HeatNetwork.java:128-172` `[V]`
- **MECH-HEAT-02c a machine cannot invalidate that cache, so the LOOP does it** — placing a reactor
  against a finished pipe marks nothing dirty, because the reactor is not a node. Two signals close
  it: the loop block's own vanilla neighbour notification, which covers everything placed by anyone,
  and a chunk load, which covers a machine that was already there and simply came back.
  `block/BlockHeatLoop.java:33-37`, `HeatNetwork.java:109-114`, `HeatNetworkWatcher.java:29-36` `[V][T]`
- **MECH-HEAT-03 an emitter is DRAINED, never sampled** — the taker removes what it took, so a
  machine standing between two loops has its output split between them instead of counted twice.
  A capability implementation that answers the same number after being drained breaks this, which is
  why the interface says so. `api/capability/IHeatEmitter.java:23-27`,
  `TileLifeSupportPlant.java:145-156` `[V]`
- **MECH-HEAT-04 `T = T_ambient + Q / C`** — with `C` summed over every member of the loop and
  recomputed each tick, so laying more pipe is immediately a colder ship.
  `HeatNetwork.java:96-101,175-262` `[V][T]`
- **MECH-HEAT-05 energy is written down per BLOCK, temperature is not** — each member holds a share
  proportional to its capacity, which is the same statement as "one temperature". A loop has no
  durable name to be saved under; a block has its position. `TileHeatLoopBlock.java:112-123`,
  `HeatNetwork.java:571-585` `[V]`
- **MECH-HEAT-06 waste heat is derived from energy SPENT** — a machine that ran at a tenth of its
  rate makes a tenth of the heat, because the number comes from what it actually paid rather than
  from its rating. `TileLifeSupportPlant.java:132-143` `[V]`
- **MECH-HEAT-07 an uncollected emitter sheds to the air** — its buffer is capped at about a
  second's worth, so a machine nobody plumbed does not bank heat for a loop built later. This is
  C12 HEAT-4's third exit (convection), not a leak. `TileLifeSupportPlant.java:139-142` `[V]`

- **MECH-HEAT-08 what a cell RADIATES is `k · A · T⁴`, in floating point** — area buys it linearly,
  temperature to the fourth power, and that difference is the whole reason a chiller is worth building.
  `k` is expressed as a reference power at a reference temperature, so the config states a point on the
  curve instead of a bare coefficient. The clause is not `T⁴ − T_amb⁴` against a config
  constant: the subtracted half is MECH-HEAT-18's environment term and there is no such constant.
  `HeatNetwork.java:460-505,507-514` `[V][T]`
- **MECH-HEAT-09 a radiator is a CELL, not a validated plate** — an array's area is how many cells the
  player built, so linearity needs nothing scanned, and one obstructed cell costs one cell instead of
  the whole array. That is what F6 means by a degradation rather than a failure.
  `TileHeatRadiator.java:59-64` `[V][T]`
- **MECH-HEAT-10 a cell radiates ONE way and needs open space ahead** — default 10 blocks, `tunable`.
  Anything in the way means the heat returns to the ship, a case deliberately not modelled: the cell
  simply stops working and reports how far away the obstruction is, so a player can go and find it.
  The walk is ordinary block arithmetic and needs no frame conversion, because a ship's blocks live in
  the subspace the world stores them in. `TileHeatRadiator.java:51-56`,
  `subsystem/hull/HullClearance.java:29-42` `[V][T]`
- **MECH-HEAT-11 the machine never says HOW MUCH** — an exchanger declares only its working surface,
  and the thermal system computes the energy from the loop's temperature. Emitting heat is a cost and
  so an open extension point for it polices itself; REMOVING heat is a benefit, and an interface that
  let a machine name the amount would let one mod ship a "heat absorber" and switch the mechanic off.
  `IHeatExchanger.java:20-41`, `HeatNetwork.java:460-505` `[V]`

- **MECH-HEAT-12 a chiller moves heat between TWO loops, and sets no temperature at all** — the ship's
  machines heat the cold loop, the pump shifts that heat into the hot one, and the hot loop's
  temperature is whatever its own capacity makes of the energy it has been given. So the hot side is a
  real reservoir: a burst heats it, a bigger one climbs slower, and its temperature is state rather
  than an offset added to another number. Rejection is quartic in temperature, which is what makes the
  climb worth paying for. `HeatNetwork.java:320-396`, `TileHeatChiller.java:71-106`,
  `api/capability/IHeatPump.java:33-50` `[V][T]`
- **MECH-HEAT-13 the pump's own work joins the HOT side** — the radiators shed `Q + W` while only `Q`
  comes off the coolant, so `W = Q / COP` is a real load and the gain is neither free nor linear. A
  pump written the obvious way moves `Q` and radiates `Q`, which looks right in every readout and
  hands the player free thermodynamics. `HeatNetwork.java:320-396` `[V][T]`
- **MECH-HEAT-14 an underpowered chiller shifts LESS, never for free** — only what was paid for moves,
  so a starved pump degrades toward doing nothing instead of working for nothing.
  `TileHeatChiller.java:87-106`, `HeatNetwork.java:376-392` `[V][T]`
- **MECH-HEAT-17 a bolted machine is part of its loop's thermal mass without being a member** — a
  chiller is a lump of metal and refrigerant in contact with the coolant, so it heats and cools with the
  loop. The loop folds its capacity in and hands it back a share at one common temperature, which is
  the calorimeter rule the gas model already runs on. Only the loop on the pump's HOT face
  carries its metal; the cold side merely feeds it. Membership would additionally make it conduct and be
  routed through, and a machine between two temperatures must do neither.
  `HeatNetwork.java:269-297`, `TileHeatChiller.java:146-150` `[V][T]`
- **MECH-HEAT-16 the price is Carnot, not a setting** — a pump's efficiency is bounded by
  `COP ≤ T_hot / (T_hot − T_cold)` and the config says only what fraction of that ideal a real machine
  manages. Driving the hot loop further therefore costs more for less, and the ceiling the design asks
  for appears instead of being placed. Capped absolutely as well, so a hot side that has crept down to
  its cold side cannot divide by nearly zero. `HeatNetwork.java:398-405` `[V]`
- **MECH-HEAT-15 a chiller belongs to NEITHER loop, and it has to be that way** — two properties of the
  primitive force it: adjacency is what joins a component, so a node touching both sides merges them;
  and one position maps to one component, so a member cannot be in two. Membership would buy a node
  three things — capacity, routing, a share of the energy — and a chiller wants none of them, being a
  machine rather than coolant. It is found by the same adjacency walk that finds emitters: one walk,
  two answers. `HeatNetwork.java:128-172` `[V][T]`

- **MECH-HEAT-18 the environment is ONE term, and there is no `T_amb`** — the classical
  `k·A·(T⁴ − T_amb⁴)` hides an incident flux inside a temperature. Written out, the second half is the
  environment: a real quantity with real contributors, summed, never a mechanism per source. So the
  loop's surface computes `A·(k·T⁴ − flux)` and the constant it would subtract does not exist rather than
  corrected. A heat weapon or another ship's radiators therefore need no new pathway to exist — they
  add to this sum. `HeatEnvironment.java:76-98`, `HeatNetwork.java:460-505` `[V][T]`
- **MECH-HEAT-19 where the term comes FROM has three named cases and a zero default** — a body answers
  with its own temperature; a live space cell answers with its star, by the real distance to it; and
  anything we cannot place answers zero, which is what interstellar void really is. The star is
  deliberately NOT added on a body: `averageTemperature` is DERIVED from that same star, so adding
  insolation on top counts it twice — measured, and it left a loop standing still on Earth drifting
  upward forever. The resolution goes through `getDimensionPropertiesOrNull` because the lenient
  accessor answers an unknown id with EARTH, which would hand a ship in deep space Earth's sky.
  `HeatEnvironment.java:136-201`, `dimension/DimensionManager.java:564-573` `[V][T]`
- **MECH-HEAT-20 the surface runs BOTH ways, and the radiators are the only coupling** — where more
  arrives than a cell can shed, the net is negative and the loop heats however cold it is. A radiating
  cell is the ship's deliberate high-emissivity surface, so it absorbs as well as sheds; a hull does
  neither, which is why a ship that built no radiators is not warmed by a star and why shutting the
  sinks is protection as well as silence. Only the positive side is capped against what the loop holds:
  a loop can always absorb. `HeatNetwork.java:483-499`, `TileHeatRadiator.java:66-73` `[V][T]`
- **MECH-HEAT-24 a duct on the chiller's COLD face makes it breathe a room** — the machine alone
  talks only to coolant; the duct occupies the position the chiller already computes for the loop it
  would otherwise draw from, so "coolant cold side" and "air cold side" are mutually exclusive by
  GEOMETRY rather than by a precedence rule. The exchange is driven by the HOT loop, and it has to be:
  every other exchange here is run by the loop being drawn from, and an air-cooled chiller has no cold
  loop for that to be. The work joins the hot side whether or not the air had heat to give — a
  compressor that was paid still dissipated its electricity — but a duct with no air at all is skipped
  rather than billed. `HeatNetwork.java:398-462`, `tile/heat/TileHeatIntakeDuct.java:35-70` `[V][T]`
- **MECH-HEAT-22 zone air is the THIRD reservoir, and it lives in the gas model** — a compartment's
  air carries a temperature and a capacity of `pressure x volume`, quoted in the same heat unit a
  coolant loop's is, because C12 HEAT-1 says there is one currency. A half-pressurised room therefore
  holds half the heat and swings twice as fast, which is physics rather than a rule. Air that is not
  there has no temperature: a zone at vacuum reads ambient instead of remembering what it held.
  `atmosphere/AirState.java:149-186` `[V][T]`
- **MECH-HEAT-23 gas arriving MIXES, by the calorimeter rule, and may not omit its temperature** — the
  incoming temperature is a required argument with no overload beside it, so a caller moving gas from
  a tank or another room has to decide what it is at. A signature that let it be omitted would mix
  silently at the receiving room's own reading, which is the same class of defect as a machine being
  allowed to declare how much heat it removes. Taking gas OUT moves nothing: what is left is the same
  gas at the same temperature, only less of it. `atmosphere/AirState.java:200-260` `[V][T]`
- **MECH-HEAT-21 a shield thins the incident flux and is refused the last of it IN CODE** — the cap
  sits where the value is READ, not in the config's declared range, so no file, no pack and no future
  key that forgets a bound can reach total thermal immunity. Coverage is asked per CELL, because one
  cell can be under a field its neighbour on the same run is outside of, and the question goes to the
  shield through a facade of its own: the frame conversion a ship-mounted emitter needs, and the
  distinction between the field's VOLUME and its membrane, are both AFFS's knowledge.
  `HeatEnvironment.java:89-116`, `affs/…/shield/ShieldCoverage.java:39-72` `[V][T]`
- **MECH-HEAT-25 overheated zone air IS a hostile atmosphere, and the gases only pick the variant** —
  the crew rung is not a damage path of its own: the derivation that turns a zone's gases into an
  `IAtmosphere` reads the air's temperature FIRST and answers with the same `VERYHOT` / `SUPERHEATED`
  singletons a scorching planet answers with, so the suit chain, the tick damage and the sync packet
  are all reached unchanged (C12 HEAT-14). Whether it is the plain or the `NoO2` variant is the
  oxygen question the cold path asks anyway. A threshold of `0` is no rung rather than a rung every
  room trips, and the whole ladder is skipped when `shipHeat` is off — with it off nothing can warm a
  room, and a hazard nothing can cause must not be reachable from leftover state.
  `atmosphere/AirState.java:274-314` `[V][T]`
- **MECH-HEAT-25b the ship's crew thresholds are NOT the planet's** — `DimensionProperties
  .getAtmosphere` keeps its own 450 K / 900 K ladder, and the two are deliberately separate readings
  rather than one shared constant: a planet's `averageTemperature` is an average over a globe with
  cold latitudes in it, while a zone's is the air a person is standing in. Sharing the number would
  either make a 50 °C cabin harmless or make every warm desert planet demand a suit.
  `dimension/DimensionProperties.java:854-879` `[V]`
- **MECH-HEAT-27 a material's thermal worth is three real properties, and everything else is derived**
  — density, specific heat and a CEILING temperature (melting for a metal, boiling for water,
  sublimation for graphite: what they share is that past it there is no solid object left). A slug's
  capacity is `rho · c · (ceiling − margin − ambient) · V`, so it is a consequence of the substance
  rather than a number authored beside an item, and the same three figures are what the melting rung
  will read. The margin is what buys back a lump you can still pick up. `ThermalMaterial.java:57-72`,
  `ThermalMaterials.java:94-117` `[V][T]`
- **MECH-HEAT-28 the VOLUME is derived too, from the block's own collision boxes** — capacity is
  `rho · c · dT · V`, and V is the last place an authored number could have hidden. It does not: a
  block in the world is measured by summing its COLLISION boxes, so a slab answers half a cubic metre
  and a staircase three quarters. The distinction matters and is why the collision list is read rather
  than the outline — `Block.getBoundingBox` defaults to the full cube, and a staircase returns one, so
  an outline read calls a staircase a whole block and calls AIR a whole block too. A block with no
  collision at all carries no heat, which is the honest answer rather than a gap. An ITEM has no box
  to measure — unless it PLACES one, and then that block answers for itself and OUTRANKS the ore
  dictionary rather than backing it up: `blockIron` lets a prefix table only assume a full cube, while
  the block knows. What is left for the ore-dictionary shape is everything that places nothing, on the
  ecosystem's own arithmetic —
  nine ingots to a block, nine nuggets to an ingot — and where the ore dictionary says nothing at all,
  That is what makes a stone slab in
  the hand the same half cubic metre as a stone slab on the ground; its stated limit is that without a
  world it can only ask for the state's single box, so a multi-piece shape reads as its outline until
  it is placed. `ThermalMaterials.java:144-260` `[V][T]`
- **MECH-HEAT-30 the emergency dump is a SINK on the loop, and it is deliberately a bad deal** — a
  new capability (`IHeatSink`) mirrors the emitter read backwards: the loop asks what a machine beside
  it wants at THIS temperature and hands over what it can, so the loop decides how much moves and a
  sink standing between two loops is fed by both rather than counted twice. The dump answers zero
  until the loop is past `shipHeatDumpTriggerKelvin`, which is what keeps it an emergency and not a
  cooling system; what it charges is whatever is in its slot, at the capacity the material table gives
  that substance and shape; and when the slug is full it goes out of the port. It is drained BEFORE
  rejection on purpose - what the pilot spent iron on should not be quietly undone by a radiator that
  was going to shed anyway. `IHeatSink.java`, `TileHeatDump.java`, `HeatNetwork.java:280-320` `[V][T]`
- **MECH-HEAT-31 an ejected slug is an ordinary hot object, and its mess is self-limiting** — what is
  written on the stack is ENERGY, so how hot it is depends on how much lump there is: a nugget and a
  block carrying the same charge are not the same temperature, and storing a temperature instead would
  say they were. A loose slug melts what it lands on by SPENDING that energy — the block's own
  capacity is the bill — so a slug that cannot pay melts nothing and one that can leaves a short trail
  and stops. It radiates on the same quartic curve as everything else here - which at a slug's
  own temperature is FAST, not slow: one white-hot cell sheds about 822 000 units a second against a
  block of iron's ~3.5 M charge, so it dumps most of what it took within seconds and then lingers a
  long time at a dull heat. It burns whoever picks it up while it is still holding anything, through
  the atmosphere subsystem's own heat damage rather than a second source invented for it. Once spent
  it is an ordinary block again: what the ship lost is the trip, not the metal.
  `HotSlugPhysics.java` `[V][T]`
- **MECH-HEAT-30b a blocked port holds the slug** — the same clearance a radiating cell needs, through
  the ejection port life support built first: firing a near-molten lump into a wall is not an
  emergency measure, so an obstructed dump keeps the slug and reports the distance rather than
  dropping it inside the hull. `TileHeatDump.java:111-129` `[V]`
- **MECH-HEAT-29 the melting rung: the MAXIMUM of three things acting on a block, against its own
  material** — zone air, the coolant loop it is welded to, and what the outside is delivering, and the
  rung takes the hottest rather than any one of them: a hull plate inside a star does not care that
  the pipes behind it are cold. The environment's term is `cellPowerAt` read backwards (a fourth
  root), so all three are quoted on one curve and can simply be compared. Past its material's ceiling
  a block is GONE rather than damaged — rock and metal leave lava, everything else leaves nothing —
  and a substance the table cannot name has no ceiling and therefore never melts. Blocks the world
  refuses to let a player break are skipped outright: bedrock has no thermal story.
  `HullMelting.java:45-126` `[V][T]`
- **MECH-HEAT-29b the sweep is paced and phased, and reaches what the loop TOUCHES** — it runs from
  the loop's own tick (that is where the loop temperature exists), every `shipHeatMeltCheckTicks`,
  phased off the loop's anchor so two loops on one ship do not land on the same tick. What it reaches
  is the loop's members and their immediate neighbours — which includes the radiating cells, and those
  sit on the hull facing out, so a star reaches the ship through them. **A hull plate far from any
  coolant is not swept**, and will not melt until something walks the hull itself.
  `HullMelting.java:135-165`, `HeatNetwork.java:262-270` `[V][T]`
- **MECH-HEAT-32 the signature is TWO terms, and neither may be derived from the other** - a passive
  sensor asks two questions and gets two answers: TOTAL RADIATED POWER, which sets the range a ship is
  found from (as its square root), and RADIANCE, what one cell of the hottest surface sheds, which is
  a function of temperature alone. A compact hot array and a sprawling cool one shed the same power
  and are nothing alike to lock onto, and that difference IS the build trade. What is summed is GROSS
  emission and never net rejection: a ship beside a star takes in more than it sheds while glowing
  hard enough to be seen from anywhere in the cell, and netting the environment off would hide it
  exactly where it is brightest. The RANGE law lives here and the sensor supplies only its own
  quality, so a better dish sees further without re-deriving the falloff.
  `ThermalSignature.java` `[V][T]`
- **MECH-HEAT-33 the hull is the floor, and it is insulated** - with every sink shut a ship still
  glows, because a hull sits far above the background. Its skin is NOT at cabin temperature: real
  hulls are insulated, so `skin = outside + f x (cabin - outside)` with `f` = `shipHeatHullSkinFraction`
  and the outside taken from the same incident flux read backwards that the melting rung uses. Without
  that term the floor is ~87 % of a reference array and silence buys nothing; with it, a silent ship is
  found at about a tenth of the range. `f` is **clamped above zero where it is read** - the mirror of
  the shield's cap below one. And the floor RISES as a ship cooks itself, because the cabin is what it
  is a fraction of: silence gets louder the longer it is held, which nobody wrote down.
  `ThermalBody.java` `[V][T]`
- **MECH-HEAT-33b the hull's AREA is a declared proxy** - the heat model reads no hull survey (one
  exists, `HullSurvey`, but heat is not wired to it), so a body's size is what it can demonstrate - the air it encloses plus
  the blocks its loops are made of - and its skin is the surface of a CUBE of that size. A cube is the
  tightest shape there is, so this understates a real hull rather than flattering it.
  `ThermalBody.sizeBlocks` is the single method a survey replaces. `ThermalBody.java` `[V]`
- **MECH-HEAT-34 running silent is a shut cell, through the same door an obstructed one uses** - a
  closed radiator reports zero working surface, which is the one number everything downstream already
  reads: it sheds nothing, the environment cannot reach the loop through it, and a sensor sees none of
  it. The order is ship-wide because the decision is (a pilot goes dark, he does not shut cell 34), and
  the flag is persisted per cell because a ship left dark comes back dark. Closing is free; what it
  costs is that the heat now has nowhere to go, which the failure ladder collects on its own schedule.
  `TileHeatRadiator.java`, `ThermalBody.setSinksClosed` `[V][T]`
- **MECH-HEAT-35 what a signature is OF is the ship's own claim, or the air two loops share** - a ship
  with a chiller has two loops and ONE hull, so reporting either alone would both understate it and
  let a sensor count the same hull twice. On a ship the grouping is the substrate's subspace claim -
  its answer, not a second definition of a ship. Off a ship there is none to ask, so a loop belongs to
  this body when it runs through a compartment the anchor's loop also runs through: two installations
  enclosing different air are two bodies, and a loop in the open is a body by itself.
  `ThermalBody.at` `[V][T]`
- **MECH-HEAT-27c a block the ore dictionary never named still knows what it is made of** — the
  substance chain is ore dictionary FIRST, then the block's own vanilla `Material`, then nothing. The
  order is the whole of it: the ore dictionary is specific (`blockGold` says gold), while a vanilla
  material is coarse by construction — `Material.IRON` covers every metal-looking block in the game,
  gold included — so it answers only where the specific source is silent. Vanilla names no stone in
  the ore dictionary at all, and without this link a stone slab would have a size and no identity,
  which answers nothing: capacity is the two multiplied. `ThermalMaterials.java:87-121` `[V][T]`
- **MECH-HEAT-27b the table is keyed by SUBSTANCE and reached through the ore dictionary** — an ingot,
  a block and a dust of one metal are one row, from any mod, with no integration code on either side.
  A stack that matches nothing resolves to nothing and callers treat that as "this cannot be a slug":
  inventing a capacity for an unknown substance is how a table stops describing the game.
  `ThermalMaterials.java:59-92` `[V][T]`
- **MECH-HEAT-27e GregTech cannot supply these numbers, so Stellurgy carries them** — GT's material registry
  has a mass and, for materials needing a blast furnace, a blast temperature; it has no density and no
  specific heat at all (checked against the pinned 2.8.10 sources), which is two of the three figures
  missing. The JSON is the extension point instead: a pack or an addon writes a row.
  `ThermalMaterials.java:36-41` `[V]`
- **MECH-HEAT-27d the file on disk and the shipped table MERGE — the file's rows win, the shipped
  rows it lacks are added** — `config/advRocketry/thermalMaterials.json` is written from the shipped
  table when absent; when present, every row it holds keeps the file's values, and every shipped row it
  does not hold is added with its shipped values, logged once by name, and written back so the player
  can see and edit it. An empty or unreadable file answers the shipped table. The consequence to keep in
  mind: DELETING a row from the file does not remove the material — the next load restores it; a player
  who wants a substance ignored must give it a ceiling of 0. The file never REPLACES the table (an older file would hide every material shipped since).
  **Read ONCE per launch, and immutable:** `ThermalMaterials.INSTANCE` is loaded when the class loads, on whichever side
  loads it, and nothing reloads it — a file edit takes effect on the next launch. It is a constant, not
  state: every reader sees the table the install started with. A caller needing another table builds
  one with `ThermalMaterials.load(file)` and holds it itself; the unit tests do exactly that and do not
  read the run directory's file. `ThermalMaterials.java:86,358-446` `[V][T]`
- **MECH-HEAT-26 the drive's refusal reads the loop bolted to its FOOTPRINT** — the hyperdrive rung
  asks `ShipDrive` how hot the coolant against the generator AND its welded coils is running, takes
  the HOTTER answer where two loops touch it, and hands that number to the jump gate as a plain
  temperature (`JumpGate.ShipContext#driveCoolantKelvin`). The clause then lives with the other DRIVE
  objections, above the commit line, so a refusal is free — a check that could refuse after the burst
  is exactly the paid refusal the jump sequence is built around. `0` means nobody measured this drive
  (no coolant against it, or `shipHeat` off) and raises no objection at all: unlike its neighbours in
  that interface, the safe direction here is to stay quiet, or every ship built before the thermal
  system existed would be grounded. `hyperdrive/ShipDrive.java:175-214`,
  `navigation/JumpGate.java:352-370` `[V][T]`

## State & persistence

| NBT key | owner | shape |
|---|---|---|
| `heatStored` | `TileHeatLoopBlock` (pipe, accumulator) | `long`, this block's share of its loop's energy |

A network itself is not persisted and deliberately has no format (see `subsystem-network`); the
per-block share is what survives a reload, and splitting a loop divides its energy correctly for
free because each half keeps what its own blocks held.

## Invariants

- **INV-HEAT-01 [T][BEH]** A machine's waste heat ends up in the loop touching it, and raises its
  temperature above ambient. `test/server/HeatLoopTest#aMachineOnACoolantLoopWarmsIt`
- **INV-HEAT-02 [T][BEH]** Capacity is a real quantity: the same heat in a loop of twice the mass is a
  markedly smaller temperature rise. The test asserts the heat each loop RECEIVED as a premise, so
  a loop that is merely starved cannot pass as a loop that is large.
  `test/server/HeatLoopTest#theSameHeatInALongerLoopIsALowerTemperature`
- **INV-HEAT-03 [T][BEH]** With `shipHeat` off nothing stores heat, no loop reports capacity, and every
  loop reads ambient — with the same rig driven again, flag on, as the control.
  `test/server/HeatLoopTest#withTheThermalSystemOffNothingHeats`
- **INV-HEAT-04 [V][SYS]** Nothing is dropped on distribution: the last member takes the remainder, so
  what is written back sums to exactly what was there. `HeatNetwork.java:571-585` FOR: INV-HEAT-01.
- **INV-HEAT-05 [V][SYS]** A loop with no thermal mass collects no heat at all — taking energy it has
  nowhere to put is the one thing conservation forbids. `HeatNetwork.java:233-240` FOR: INV-HEAT-01.
- **INV-HEAT-06 [T][BEH]** A loop's energy survives a server restart, and comes back OFF THE BLOCKS: the
  loop itself is rebuilt from the world with the same membership and capacity, while every heat unit
  is read back from the members that were holding it. This is the pin under MECH-HEAT-05 — without
  it, storing energy per block rather than in the network state was only an argument.
  `test/server/SubsystemNetworkRestartTest#aCoolantLoopsEnergyComesBackFromItsBlocks`

- **INV-HEAT-07 [T][BEH]** Rejection scales linearly with the number of radiating cells, at equal loop
  capacity and equal stored energy. `test/server/HeatRejectionTest#rejectionScalesWithTheAreaBuilt`
- **INV-HEAT-08 [T][BEH]** Rejection follows the fourth power of temperature: doubling a loop's rise above
  ambient more than doubles what it sheds, by the ratio the law predicts.
  `test/server/HeatRejectionTest#rejectionFollowsTheFourthPowerOfTemperature`
- **INV-HEAT-09 [T][BEH]** An obstructed cell sheds NOTHING and reports the obstruction's distance, and the
  loop keeps its energy — asserted from the loop's side, because a cell reporting zero while the heat
  left anyway would pass a test that only read the cell.
  `test/server/HeatRejectionTest#anObstructedCellShedsNothingAndSaysWhereTheBlockIs`

- **INV-HEAT-10 [T][BEH]** The hot loop receives what came off the cold loop PLUS the work that was paid —
  all three read from one tick of the COLD loop, so the clause does not depend on which of two
  components the solver visited first.
  `test/server/HeatChillerTest#theHotLoopReceivesTheHeatPlusTheWork`
- **INV-HEAT-11 [T][BEH]** A chiller between two runs leaves them TWO loops: neither absorbs the other, and
  both see the pump beside them. Same test's premises — and it is the assertion that would fail if the
  chiller were ever made a network member. Pinned by `HeatChillerTest#theHotLoopReceivesTheHeatPlusTheWork`.
- **INV-HEAT-12 [T][BEH]** The hot loop ends up hotter than the cold one it is fed from, because the energy
  accumulates in it against its own capacity — nobody assigns it a temperature. With an unpowered
  chiller shifting nothing as the control.
  `test/server/HeatChillerTest#theHotLoopIsHotterBecauseEnergyAccumulatesInIt`
- **INV-HEAT-13 [T][BEH]** A bolted chiller's own thermal mass counts toward its HOT loop and toward no
  other: two runs of equal length come out with unequal capacity, and the cold one carries exactly its
  own pipes. `test/server/HeatChillerTest#theHotLoopReceivesTheHeatPlusTheWork`

- **INV-HEAT-14 [T][BEH]** A warm world and a distant star reach a radiator through the SAME term: the same
  loop at the same temperature, built once on a world and once in a live space cell, nets a difference
  exactly equal to the difference between the two reported fluxes. Stated as a difference so what the
  cell radiates cancels and no config number is restated.
  `test/server/HeatEnvironmentTest#aWorldsWarmthAndAStarArriveThroughTheSameTerm`
- **INV-HEAT-15 [T][BEH]** Under a star strong enough, the net runs backwards and an empty loop GAINS heat —
  with a loop of the same size that built no radiators as the control, which must gain nothing, and an
  ordinary star as the second control, under which the same rig still sheds.
  `test/server/HeatEnvironmentTest#aShipUnderAFierceStarHeatsThroughItsRadiators`
- **INV-HEAT-20 [T][BEH]** A powered chiller breathing a room cools it and heats its loop, and the loop
  receives exactly what left the air PLUS the work — all three read from ONE tick of the hot loop,
  because across probe calls the room's temperature carries the natural ticks in the gap and the
  loop's energy does not. `test/server/HeatIntakeDuctTest#aChillerBreathingARoomCoolsItAndHeatsItsLoop`
- **INV-HEAT-21 [T][BEH]** An unpowered chiller leaves the room exactly where it was, with the same rig
  powered afterwards as the control.
  `test/server/HeatIntakeDuctTest#anUnpoweredChillerLeavesTheRoomAlone`
- **INV-HEAT-17 [T][SYS]** Gas arriving at a different temperature mixes by how much of each there is —
  the enthalpy-weighted mean, deliberately arranged with unequal sides, since a plain average agrees
  with the rule exactly when they are equal.
  `test/server/ZoneAirIsAReservoirTest#gasArrivingMixesByHowMuchOfEachThereIs` FOR: INV-HEAT-22.
- **INV-HEAT-18 [T][SYS]** Drawing gas out leaves the temperature alone and lowers the capacity. The
  capacity half is what stops the assertion also passing on a rig where nothing happened.
  `test/server/ZoneAirIsAReservoirTest#drawingGasOutLeavesTheTemperatureAndLowersTheCapacity` FOR: INV-HEAT-22.
- **INV-HEAT-19 [T][SYS]** A zone holding no gas reports ambient and no capacity, rather than the
  temperature it was at when it still had air.
  `test/server/ZoneAirIsAReservoirTest#airThatIsNotThereHasNoTemperature` FOR: INV-HEAT-22.

- **INV-HEAT-16 [T][BEH]** A raised shield takes most of the incident flux and never all of it, with the
  configuration set to demand a hundred percent: the shielded loop gains under a tenth of what an
  identical unshielded one does, and strictly more than zero.
  `test/server/HeatEnvironmentTest#aShieldThinsTheFluxAndNeverRemovesIt`

- **INV-HEAT-22 [T][BEH]** A compartment past the crew threshold presents a hostile atmosphere, and
  cooling it gives the room back — a rung that latched would leave a repaired ship still killing its
  crew. `test/server/HeatFailureLadderTest#anOverheatedCompartmentTurnsHostileAndCoolingItGivesTheRoomBack`
- **INV-HEAT-23 [T][BEH]** Temperature picks the rung and the gases pick the variant: hot with nothing to
  breathe is the `NoO2` type, and the same temperature with air is the plain one. The pair is what
  stops a build that always answers `NoO2` from passing.
  `test/server/HeatFailureLadderTest#hotAirWithNothingToBreatheIsBothHazardsAtOnce`
- **INV-HEAT-24 [T][BEH]** The rung reaches a real person: on the client, a player in an overheated
  compartment loses health, while the same sealed room at cabin temperature leaves him untouched.
  `test/client/VacuumAndSuitClientGroupTest#overheatedZoneAirHurtsAnUnsuitedCrewman`
- **INV-HEAT-25 [T][BEH]** A drive whose loop is past the refusal threshold does not fire, says which
  refusal it is, and costs the pilot nothing — his bank still holds the burst afterwards. Cooling the
  loop restores the jump, so the gate remembers nothing.
  `test/server/HeatFailureLadderTest#anOverheatedDriveRefusesToFireAndTheRefusalIsFree`
- **INV-HEAT-26 [T][BEH]** A drive with no coolant against it reads zero and is NOT refused: an unmeasured
  drive is not a hot one.
  `test/server/HeatFailureLadderTest#aDriveWithNoCoolantAgainstItIsNotMeasuredAndNotRefused`
- **INV-HEAT-27 [A][BEH]** The same total power arranged two ways is ONE detection range and TWO different
  locks: an array four times the size and a quarter as bright is found at the same distance and is a
  quarter of the target. Unpinned: a pin needs a loaded
  configuration.
- **INV-HEAT-28 [A][BEH]** Range goes as the square root of power, exactly - twice the shedding buys 1.41x
  the distance and no more. Unpinned.
- **INV-HEAT-29 [T][BEH]** With every sink shut, most of what a ship radiates goes with them and what is
  left is a FLOOR: it is above zero, it is still lockable, and it does not change when the loop is
  emptied - so it is the hull and not leakage.
  `test/server/RunningSilentTest#aShipRunningSilentIsFoundCloserAndIsStillFound`
- **INV-HEAT-30 [T][BEH]** A hotter cabin is a hotter skin and a brighter ship, and two loops threading one
  sealed room are ONE body. `test/server/RunningSilentTest#theHullGlowsWithTheAirItEncloses`
- **INV-HEAT-31 [A][BEH]** A config asking for a perfectly cold skin is refused, so no configuration makes
  a ship invisible. Unpinned: a pin would have to write the configuration.

## Integration seams

- **`subsystem-network`** — heat is its third domain. It contributed one hook to the primitive
  (`SubsystemNetworkDomain#onComponentTicked`), because heat is the first commodity whose residue is
  physical rather than discardable.
- **`atmosphere-oxygen`** — `TileLifeSupportPlant` is the first `IHeatEmitter`, which names the
  regeneration plant as a heat source rather than a convenience.
- **`CapabilityHeatEmitter`** — the extension point a GregTech reactor will arrive through, by
  `AttachCapabilitiesEvent` from our side or by the donor mod declaring it. Nothing foreign carries
  it today, which is a statement about scope and not about the mechanism. A registry keyed by
  machine class was the first shape and was replaced: such a table can only ever be filled in by us,
  and the machines this system exists for are not ours.

## Where the heat comes FROM, and where it goes into a room

The loop has two directions of contact with the rest of the ship: supply from running machines, and conduction into cabins.

- **Supply.** `subsystem/heat/WasteHeat` is the component a machine holds; `tile/heat/TileWasteHeat`
  `{Machine,PowerConsumer}` are the two bases that hold one and catch `useEnergy`, which is the single
  point at which energy actually leaves a machine's buffer. Every powered Stellurgy machine extends one of
  them.
- **Into the cabin.** `HeatNetwork.conductIntoCabins` walks the loop's members on the melt clock and
  warms the sealed room each one stands in, by `shipHeatCabinConductionFraction` of the gap, once per
  ZONE rather than once per block. Downhill only: a cabin hotter than the loop is the chiller's
  business, through a duct somebody had to place. This is the only path by which a hot ship becomes
  hot to be INSIDE.

## Config surface

Every flag this subsystem reads (`shipHeat*`, `lifeSupportAirHeatCapacity`) is specified in
`C4-config-surface` with the mechanic that reads it and its full-disable path (`shipHeat` off ⇒ capacity
reads 0, generation is not accrued, every loop reports ambient). Two refusals live in code, not in the
flag: `shipHeatShieldAttenuation` of 1000 and `shipHeatHullSkinFraction` of 0 are accepted and then
REFUSED, because silence may never become invisibility. `shipHeatPipeThroughput` is unused — rejection is
not a network flow (MECH-HEAT-01).

## Test coverage

| what | test |
|---|---|
| INV-HEAT-01, 02, 03 | `test/server/HeatLoopTest` — 3 scenarios. Scenario 2 was verified by REVERTING the capacity term in production and watching it go red on exactly its own assertion (both loops 4530 units, capacities 60 vs 120) |
| MECH-HEAT-02c | `HeatLoopTest.aMachineBuiltAfterTheLoopIsStillPickedUp` — verified by removing the neighbour signal in production, which left the loop at exactly ambient with `heatGeneration:0` |
| INV-HEAT-06 | `test/server/SubsystemNetworkRestartTest.aCoolantLoopsEnergyComesBackFromItsBlocks` — two boots on one world, energy read per BLOCK before the loop is consulted, because the loop's own figure could not tell restored energy from energy that was never gone. Verified by deleting the NBT write: `expected:<4531> but was:<0>` |
| INV-HEAT-07, 08, 09 | `test/server/HeatRejectionTest` — 3 scenarios. Verified by breaking each law separately in production: a linear law gave a measured ratio of 1.95 against the quartic's 2.48, and a cell that ignored its obstruction shed 60 where zero was required. The area scenario stayed GREEN through both, which is what shows the three are independent. The quartic scenario adds the incident flux back on, and that identity was falsified on its own by reporting a flux that is not the one that acts |
| INV-HEAT-10..13 | `test/server/HeatChillerTest` — 2 scenarios, each law falsified separately in production: delivering only the heat and not the work gave `expected 6000 but was 6240` (the pump inventing 240 units a tick), and dropping the bolted mass gave `cold=60 hot=60`, two loops indistinguishable where one carries a machine |
| INV-HEAT-20, 21 | `test/server/HeatIntakeDuctTest` — 2 scenarios. Falsified by delivering the heat without the work: `expected:<6240> but was:<6000>`, the machine quietly destroying its own electricity |
| INV-HEAT-17, 18, 19 | `test/server/ZoneAirIsAReservoirTest` — 3 scenarios on the one live gas-arrival path there is. Each falsified separately in production: splitting the difference instead of weighting gave 293 against the law's 380; a draw that cooled the room gave 380000 against 400000; and a vacuum that remembered gave 400000 where ambient was required |
| INV-HEAT-14, 15, 16 | `test/server/HeatEnvironmentTest` — 3 scenarios, most of them in a materialized cell of the overworld's own system, because a loop on a world cannot be driven backwards at all. Verified by breaking each separately in production: clamping the backwards case, dropping the code-side attenuation cap (`shielded=0`), giving the star a pathway of its own (`shielded=64969 unshielded=64969` — a star the shield cannot stop), and letting the environment reach a loop with no radiators (`expected:<0> but was:<76800>`) |
| INV-HEAT-22, 23, 25, 26 | `test/server/HeatFailureLadderTest` — 4 scenarios, every threshold READ off the server rather than named. Falsified in production separately: a derive that ignores temperature left both room scenarios red on their own assertions, and a drive that looks for coolant against its CONTROLLER instead of its footprint reported `driveCoolantMilliK:0` with the loop right on top of the generator |
| INV-HEAT-27, 28, 31 | **Unpinned** — a pin needs a loaded configuration, which the fast tier does not have |
| INV-HEAT-29, 30 | `test/server/RunningSilentTest` - 2 scenarios. Falsified three ways in production, each red on its own assertion: deleting the hull term left a silent ship at `radiatedPowerMilli:0` (found nowhere at all); a cell that ignored its own shut flag reported `radiatingCells:3` while silent; and a skin that read the LOOP instead of the cabin turned the floor into leakage. A body cut back to its own loop reported `loops:1` where two runs share a room |
| INV-HEAT-24 | `test/client/VacuumAndSuitClientGroupTest.overheatedZoneAirHurtsAnUnsuitedCrewman` — the same sealed room twice, cabin temperature then overheated, health read off the real client. Falsified by the same derive break: the arrangement stopped at `PressurizedAir`, which is the test refusing to run rather than passing for the wrong reason |
| C12 HEAT-17 (the relation) | **Violated at the defaults.** `test/server/HeatDumpBuysSecondsTest.atTheShippedDefaultsOneDumpOutshedsOneRadiatingCell` pins it as it stands, both sides read off the booted server: a dump's 40 000/s against ONE cell's 6 000/s — one cell being the cheapest tier by ruling (2026-09-29). That a slug buys only a bounded number of seconds has no test: the threshold is unruled and a pin needs a loaded configuration |
| MECH-HEAT-30 | `test/server/HeatDumpBuysSecondsTest` — 2 scenarios: past the trigger the loop loses heat into the slug and the slug is thrown out; below it the dump does nothing at all. Falsified by removing the trigger, which turns the emergency into a cooling system and reddens the control leg alone |
| MECH-HEAT-29, 29b | `test/server/HullMeltsPastItsMaterialTest` — 4 scenarios on a real loop: past the victim's ceiling the block is gone and rock leaves lava; the same rig 200 K cooler leaves it standing; a substance with no ceiling is never taken; and a loop past its OWN pipes' material consumes itself. Falsified by removing the threshold: the fixture cannot even be built, because the pipes melt on the first solve · `test/unit/HullMeltingTest` — now only that no incident flux is no temperature; the environment term as the radiation curve inverted (at its reference point, and the quartic: sixteen times the flux is twice the temperature) is unpinned (it needs a loaded configuration) |
| MECH-HEAT-27c, 28 | `test/server/ThermalMaterialVolumeTest` — 7 scenarios on a real world, through the new `heat material` probe verb. The STAIRS scenario is the discriminator and was chosen for it: falsified by reading the outline instead of the collision list, stairs reported a whole cubic metre and empty air reported one too, while the slab scenario stayed green — a slab's outline and its collision agree, so it alone cannot tell the two reads apart. The HELD-slab scenario is falsified separately by removing the placed-block fallback: the item then reads zero while the same block on the ground still reads half, which is exactly the inconsistency the fallback closes. Two more links falsified separately: with the vanilla-material fallback removed the stone slab loses its substance, and with the precedence flipped a GOLD block reports `iron` — gold is the subject precisely because iron would pass either way, which is a test that cannot fail |
| MECH-HEAT-27d | `test/unit/ThermalTableFileTest` — an older file (iron only, edited values) loaded over the real path: the shipped wood resolves with its shipped values, iron keeps the file's, and the file gains `wood`. Each verdict falsified separately (the missing row not added; the shipped row put over the file's; the write-back removed). The class works on the run's own table file and restores it byte for byte |
| MECH-HEAT-27, 27b | `test/unit/ThermalMaterialsTest` — 12 scenarios, each expected value COMPUTED from the three stored properties rather than quoted. Falsified by making the derivation ignore the ceiling (a flat span for every material): five of the nine went red, and the resolution scenarios stayed green, which is what shows the two halves are independent |
| **not pinned** | the dump block, the charge rate and therefore HEAT-17's relation; a slug as a physical object; the ORDER of the failure ladder (C12 HEAT-11's actual clause) — three of its five rungs have no subject in the game yet, so the sequence cannot be exercised end to end and is knowingly owed; the suit protecting against the crew rung specifically (the type is in the suit's protected set, pinned by `unit/SpaceArmorProtectionContractTest`, but no scenario wears one in a hot ROOM); a drive touching TWO loops (the hotter answers, untested); a machine between two loops; an accumulator inline in a run; the chunk-load half of MECH-HEAT-02c (the neighbour half carries the test); a radiator on a MOVING ship (the clearance walk is frame-correct by construction, but nothing flies it yet); more than one chiller on one loop (the lifts sum, untested); a SHIP-mounted shield attenuating flux (the frame conversion is exercised by the shield's own e2e, not by a heat test); more than one star in a cell (they sum, untested) |

## Open questions

- **The crew rung reaches the published atmosphere only when something re-derives the zone.** The
  derivation is correct the moment it is asked, and what asks it is a gas change or a living entity
  breathing in the zone (once a second, `AtmosphereHandler.respire`). Nothing re-derives on a
  temperature change alone — so a room heated with nobody in it publishes its old atmosphere until
  someone walks in, which is within a second of the rung mattering. It becomes a defect the moment
  something reads a zone's hazard without a person in it (an alarm, a door interlock) — which is
  exactly what the block-damage and fire rungs are. One path is already exposed: block conversion
  reads the published value with no entity in the loop at all.
- **A working loop still reports `DISCONNECTED`** — because rejection is NOT a network flow. The
  status describes distribution and this domain does not distribute: no node plays source or sink,
  and MECH-HEAT-01 says why. It is truthful and it reads wrong, so the status needs a heat-specific
  meaning rather than the primitive's.
- **A planetside loop cannot be cooled below its world's temperature**, which is right: the
  environment on a body is that body's `averageTemperature`. Nothing models CONVECTION at a radiator,
  so a cell on a windy world sheds no better than one in a vacuum at the same temperature.
  Deliberate for now — the machine-side convection exit (MECH-HEAT-07) is what keeps a planetside
  base from needing a thermal build at all.
- **A duct in a room being cooled below the cabin has no consequence**, because cold has none
  anywhere — the temperature bands exist and only the hot end is wired. An air conditioner left
  running will chill a compartment indefinitely; the price is Carnot, which makes it slow, not
  impossible.
- **There is no zone-to-zone air mixing in the game**, measured 2026-08-17: the recirculator serves
  one zone, a duct stores nothing, and the only route by which gas ARRIVES anywhere is a separator's
  tank. So the gas model's "when zones exchange" rule has no call site yet; the seam is correct by
  construction, since the arriving temperature cannot be omitted.
- **The environment is sampled at ONE member's position per loop tick.** Correct while the sources
  are bodies and stars (millions of blocks away, so a ship spans nothing); it will stop being correct
  the first time a source is close, which is exactly what ship-to-ship radiators would be. The shield
  half is already per cell, so the seam for it exists.
