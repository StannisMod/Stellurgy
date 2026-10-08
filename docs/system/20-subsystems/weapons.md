---
id: weapons
owns: [api/weapon/, weapon/, tile/weapon/, block/weapon/]
entrypoints: [TileTurret#update, GunAssembly#scan, TurretFireControl#fire, WeaponNetworkDomain#INSTANCE]
depends-on: [projectile-substrate, subsystem-network, fire-control-sensor, integration-vs, dimension-planets, api-public]
depended-by: [fire-control-sensor, turret-archetypes (planned)]
contracts: [C1, C2, C3, C5, C7]
confidence: high
---

## Purpose

A **gun**: the block a player builds a weapon around, the parts that decide what it is worth, the mount
that points it, and the decision to pull the trigger. It ends where the round leaves the muzzle —
everything after that belongs to `projectile-substrate`.

## The two weapon families, and what separates them 

**MECH-GUN-30 — A gun either THROWS a round or HOLDS a beam, and one field decides which.**
`GunSpec.getBeamPowerPerTick()` above zero makes the assembly a beam; there is no separate "type"
field, because a type and a power could disagree and then two places would decide what a gun is. A
thrower spends energy in lumps at intervals; a beam spends every tick it is lit, and its depth grows
with dwell rather than arriving in a budget. `GunSpec.java:42-62`, `TileTurret#holdBeam` [V]
[T `ABeamIsHeldNotThrownTest`]

**MECH-GUN-31 — "Operable" means CAN DELIVER, never "has a barrel".** A beam has no muzzle speed and
no round worth firing, by nature. The first version of `isOperable()` was a list of required fields
written around a thrower, and a beam that was built, wired, charged and aimed reported itself
inoperable while every layer above dutifully refused to fire it. A list of fields is a definition that
quietly excludes the next family. `GunSpec.java:84-105` [V] [T].

**MECH-GUN-32 — One muzzle for every family.** Where a body leaves a gun — the standoff past the gun's
own blocks, the frame conversion, the hull's inherited motion, and the refusal when the line of fire is
not clear — is `TurretFireControl.muzzleOf`, asked by the round path and the beam path alike. It was
inside `fire()`, reachable only by something launching a shot; a beam that computed its own origin
started inside the barrel and **cut its own weapon apart on the first tick**.
`TurretFireControl.java:145-215` [V] [T `TurretStandaloneTest`, `ABeamIsHeldNotThrownTest`].

**MECH-GUN-33 — A starved weapon DUTY-CYCLES: dark, saving, then lit.** Below the price of one tick a
beam goes out, accumulates a quantum — one second of unaided burn — and only then relights, so a weak
feed produces real bursts a player can read instead of a stutter at whatever rate energy happened to
arrive. The buffer is sized from that quantum: sized from the price of a SHOT, as a thrower's is, a
beam gun can never hold what it goes dark to accumulate and is dark forever — which reports identically
to one that is correctly recharging. `TileTurret#holdBeam`, `TileTurret#resizeBufferFor` [V] [T].
**Lit** and **recharging** are separate answers on purpose: fire control cannot be built if
"not shooting" and "cannot shoot yet" are one state.

**MECH-GUN-35 — There is no war switch; the war is always
on (maintainer ruling 2026-10-03).** *"Убираем этот гейт, слишком жирно. Война всегда включена."* (remove this gate, too heavy; the
war is always on). No config key switches weapons off, and nothing reads one: no gate in
`TileTurret#canFireNow` / `#burnOneTick`, `HeldBeam#emit`, `ShotSubstrate#launch` / `#tick` or
`TileFireControlSensor#update`; no `TileTurret#isDisabledByConfig` and no `weaponsDisabled` field in
the `turret read` probe. A gun's silent states are only "holding fire" and "nothing left". The acquisition flag
`enableFireControlSensor` is a different question and survives (`fire-control-sensor` INV-FCS-02).
[V]

**MECH-GUN-34 — The gun tells the players around it what its beam is doing, on every path.** The beam
has no existence apart from the gun holding it, so there is no register of live beams to walk and no
static map keyed by position: each controller keeps its own replication channel and offers it this
tick's state. Every way of NOT burning — no trigger, too hot, saving up, no line of fire, rebuilt into a
thrower, blown up, chunk unloaded — reaches that channel by the same road burning does, because a path
that ended without saying so would leave a beam drawn on a gun that had stopped firing. The channel
decides what is worth a packet (`projectile-substrate` MECH-SHOT-29); the gun decides only what is true.
`TileTurret#holdBeam`, `#burnOneTick`, `#extinguishBeam` [V]
[T `BeamReachesClientTest`, `BeamReplicationCadenceTest`]

**MECH-GUN-37 — What the gun offers the channel is a PATH, not two ends.** A beam can be turned — a mirror sends it back along the reflected direction as a further leg of the same tick — so the corner is a real point on the line it occupies. The tick's emission carries the whole path, the channel compares the whole path when deciding whether the drawing has moved (two ends can stay put while a corner walks along the plating), and the packet carries a point count. A beam nothing turned is exactly its two ends, which is the overwhelming case and costs one extra byte. `HeldBeam.Emission#path`, `TileTurret#replicatedPath`, `BeamReplication.Channel#offer` [V]
[T `ABentBeamIsDrawnBentTest` — falsified by capping the wire at two points, which draws the beam through the mirror that bent it]

## Responsibility boundary

**Owns**: what a built gun is worth (the derived spec); the assembly walk that derives it; the traverse
mechanism and its failure vocabulary; the energy buffer and heat a gun carries; when a round is fired;
the frame conversion at the muzzle.

**Does NOT own**: how a round flies, what it crosses first, or what it does on arrival (the substrate,
the field layer, the damage service); who is a legitimate target (the `fire-control-sensor` layer — a gun is
told what to shoot at, and works out where to aim from what it was told); how energy reaches the gun (Forge Energy from any source, or the weapons network).

## Key types

| type | role |
|---|---|
| `api.weapon.GunSpec` | what a built gun is worth: muzzle speed, impact energy, interval, FE/shot, heat, spread, traverse rate, reach. Immutable, built by `GunSpec.Builder` |
| `api.weapon.IGunPart` | implemented by a BLOCK; contributes to a spec being assembled. The addon seam |
| `api.weapon.GunInput` | what a build needs delivered: `FORGE_ENERGY` (implemented) or `GAS` (declared, reserved) |
| `api.weapon.TurretDriveState` | working / derated / jammed / freewheeling / locked / dead — and what each permits |
| `weapon.GunAssembly` | the connectivity walk from the controller, and the spec + reach it produces |
| `weapon.TurretMechanism` | bearing, command, declared arc, rate limit, saturation, drive state; NBT |
| `weapon.TurretFireControl` | the world/ship frame seam and the launch call |
| `weapon.WeaponNetworkDomain` | the `SubsystemNetworkDomain` for guns; commodity is Forge Energy |
| `weapon.WeaponNetworkState` | the network's shared target and hold-fire switch |
| `tile.weapon.TileTurret` | the controller tile: buffer, heat, cooldown, assembly, the tick |
| `tile.weapon.TileWeaponConsole` | the network's editor: target, hold-fire, readouts. Owns no state of its own |
| `block.weapon.BlockTurret` / `BlockGunPart` | the controller block, and one class for every part |

## The parts a player can place, and what each is worth

One class, `BlockGunPart`, holds a lambda per registry entry; a part IS its contribution and has no
other behaviour, which is why adding one is a registration line rather than a class. Declared at
`Stellurgy.java:766-789`, registered at `:994-1000`. [V]

| registry name | contributes |
|---|---|
| `turret` | the controller itself (`BlockTurret` + `TileTurret`); contributes nothing to the spec |
| `gunBarrel` | muzzle speed +0.9, impact energy +8, spread **−0.8°**, lifetime +20 ticks, +50 FE/shot, +1 heat/shot |
| `gunAmmoFeed` | fire interval −3 ticks, impact energy +6, +75 FE/shot, +2 heat/shot; declares `FORGE_ENERGY` |
| `gunBeamEmitter` | beam power **+4 000 FE/tick**, declares `ImpactKind.BEAM`, +1 heat/shot, heat capacity +20; declares `FORGE_ENERGY` |
| `gunCooling` | heat capacity +40, cooling +2/tick, traverse +0.5°/tick |

The emitter is the one part that changes what KIND of weapon the build is (MECH-GUN-30), and its
power is per TICK rather than per shot: a bigger laser is a laser with more emitters, not a larger
number written beside one. Its declared kind is what prices it against the ablation column and gets
it absorbed whole by a shell instead of thrown back off it.

A registered block with no blockstate fails `test/unit/ModelAssetsAreAddressableTest`, so a gun part cannot draw as the missing-model checkerboard.

## Mechanics

**MECH-GUN-01 — A gun works with no network, and the network is a convenience.** Nothing on the firing
path consults a network before aiming or firing: `TileTurret#update` reads a target that is the
network's if it has one and the gun's own otherwise, and every other condition — charge, heat, cooldown,
drive — is the gun's own state. A battery of one, wired to nothing, is a supported build.
`TileTurret.java:107-133`, `:148-154` [V]
[T `TurretStandaloneTest#aGunWithNoNetworkFiresAtWhatItWasPointedAt`]

**MECH-GUN-02 — A gun's numbers are DERIVED from what was built, never authored.** `GunAssembly.scan`
walks block adjacency from the controller, asks every `IGunPart` it meets to contribute, and sums. Two
guns with the same parts have the same spec wherever they stand, and adding a barrel is a change the
player can measure. `GunAssembly.java:62-108`, `GunSpec.java:134-232` [V]
[T `GunSpecTest`, `TurretStandaloneTest#theRoundItFiresIsTheRoundItsBuildDescribes`]

**MECH-GUN-03 — Connectivity, not a template.** There is no fixed shape to match, so a part shipped by
an addon joins a gun by being placed against one and the walk that finds it was not written knowing it
exists. The walk is bounded at `MAX_PARTS` = 256 — a bound on work; a build past it is still a gun,
just not credited further. `GunAssembly.java:34,77` [V]

**MECH-GUN-04 — Parts add; only spread may subtract, and it floors at true.** A part never resets what
another contributed. More barrel makes a gun truer, which is the one place a negative contribution is
meaningful, and the result is floored at zero rather than becoming an undeclared aim bonus.
`GunSpec.java:186-190`, `:146-160` [V] [T `GunSpecTest#spreadTightensTowardsZeroAndStopsThere`]

**MECH-GUN-05 — The build is re-walked when it CHANGES, never on a timer.** A block cannot enter or
leave the world without its own `onBlockAdded` / `breakBlock` running — whoever put it there, a player,
a fill command or another mod — so a part tells the guns around it and the walk happens on the next
tick. Idle guns cost one boolean per tick. The notification is a TRIGGER, not a source of truth: what
it causes is the same full walk, so a missed one costs latency and never a wrong number.
`BlockGunPart.java:41-58`, `GunAssembly.java:113-161`, `TileTurret.java:86-92,177-186` [V]

**MECH-GUN-05a — The marker is a walk, not a look at six neighbours.** A section added at the far end
of a ten-block barrel is nowhere near the controller whose numbers it changes, so the mark propagates
out through the parts still standing. Bounded by the same `MAX_PARTS`. On a break the part is already
gone from the world, so a removal that SPLITS a gun marks only the halves still reachable — which is
the right answer, since the other half's controller does not have it either.
`GunAssembly.java:113-161` [V]

**MECH-GUN-05b — The re-walk is deferred one tick.** Laying a run of barrel sections would otherwise
pay for a full walk per block placed, and a walk running inside `breakBlock` would be reading a world
in the middle of being changed. `TileTurret.java:177-186` [V]

**MECH-GUN-06 — A commanded bearing outside the arc SATURATES visibly.** The mount goes as far as it
may and reports saturation for as long as the command is out of reach; `isOnTarget` is measured against
what was ASKED for, not against the clamped version, so a gun at the edge of its arc does not report a
hit it cannot take. `TurretMechanism.java:97-124`, `:126-138` [V]
[T `TurretMechanismTest#anUnreachableCommandSaturatesInsteadOfClampingSilently`]

**MECH-GUN-07 — The declared traverse rate is a hard ceiling, derated by the drive state.** One tick
moves the mount by at most `rate × TurretDriveState.getRateFactor()`.
`TurretMechanism.java:114-122`, `TurretDriveState.java:16-40` [V]
[T `TurretMechanismTest#aTickNeverTurnsFurtherThanTheDeclaredRate`]

**MECH-GUN-08 — A killed drive leaves the barrel somewhere definite, and the aim path reads it as
truth.** Jammed holds its bearing and still fires down it; freewheeling drifts and holds nothing;
locked is a player's decision rather than a fault; dead alone stops the shooting.
`TurretDriveState.java:16-66`, `TurretMechanism.java:98-104` [V]
[T `TurretMechanismTest#aJammedDriveHoldsItsBearingAndStillFires`,
`TurretStandaloneTest#aDeadDriveStopsTheGunFiring`]

**MECH-GUN-09 — A ship gun aims in the ship's frame; a ground gun aims in the world's.** The mount's
bearing is held in whichever frame the gun sits in, so a rolling hull carries the barrel exactly as it
carries the deck. The ship id comes from the REGISTRY (`registeredShipIdManagingBlock`), so a gun does
not stop belonging to its ship when nobody is near enough for it to be simulated.
`TurretFireControl.java:48-73`, `TileTurret.java:115-121` [V]

**MECH-GUN-10 — At the muzzle, point and direction are converted separately, and ship motion is
inherited.** `toWorldFrameFor` maps the muzzle point, `rotateToWorldFrameFor` maps the aim direction,
and `shipVelocityAtPointFor` is ADDED to the round's velocity — a gun on a hull doing forty blocks a
tick that fired with only its own muzzle speed would be firing backwards.
`TurretFireControl.java:80-124` [V] Both are blocks per TICK: the conversion from the per-second figure lives in
the port (`VSBridge#shipVelocityAtPointFor`).

**MECH-GUN-16 — A gun aboard an unnamed ship does NOTHING.** Its tick returns before the assembly
walk, before cooling, before network registration and before aiming. A ship's chunks load before its
ship object exists, and in that window every coordinate the gun holds is a shipyard address rather
than a place in the world — so there is no partial behaviour that is correct, only waiting.
`TileTurret.java:86-93`, `VSIntegration#isOnUnnamedShip` [V]
[T `TurretStandaloneTest#aGunAboardAnUnnamedShipDoesNothingAtAll` — the same STATE, reached by a
different road: the real load race is not pinned yet]

**MECH-GUN-10a — The launch path refuses the same case again, as depth.** MECH-GUN-16 means a gun
aboard an unnamed ship never reaches a tick; this second check is inside `fire`, which is callable
from anywhere, and the failure it prevents is severe out of all proportion to its cost. `TurretFireControl.java:96-102`,
`VSIntegration#isBlockInShipyard` [V]
[T `TurretStandaloneTest#aGunAboardAnUnnamedShipDoesNothingAtAll`]

**MECH-GUN-10b — A blocked line of fire is a HOLD, not a demolition.** The first three blocks past
the muzzle are tested with the substrate's own crossing test; anything solid there — a hull the
turret is recessed into, a superstructure its arc crosses, the wall a ground battery sits behind —
refuses the shot. Deliberately short: this is a self-shelling check, not a clear-shot guarantee, and
a target behind a distant wall remains a legitimate miss. Reusing `StructureCrossing` rather than
asking the world directly means the gun and the round cannot disagree about what counts as structure.
`TurretFireControl.java:41-47,133-140`, `StructureCrossing.java:68-79` [V]
[T `TurretStandaloneTest#aGunWithItsOwnHullInFrontOfTheBarrelHoldsFire`]

**MECH-GUN-11 — The round is born clear of its own gun.** The muzzle is placed `reach + 1.5` blocks
along the aim, where `reach` is how far the furthest counted part sits from the controller. A round
spawned inside the barrel resolves a structure crossing on its first tick and the weapon destroys
itself. `TurretFireControl.java:92-96`, `GunAssembly.java:54,105` [V]

**MECH-GUN-12 — The environment is read from the world once, at the muzzle.** AStellurgy planet contributes
its own gravity multiplier; a vanilla world gets vanilla's 0.03 per tick squared. The shot then carries
it. `TurretFireControl.java:127-143` [V]

**MECH-GUN-13 — A gun is a network SINK, and only ever asks for what its buffer can hold.** It is
registered into the weapons domain on its first server tick and unregistered on invalidate or chunk
unload; its requested amount is its free capacity and its stated consumption is FE-per-shot over the
fire interval, so a readout can tell a topping-up buffer from a firing gun.
`TileTurret.java:82-88`, `:236-268`, `:274-289` [V]

**MECH-GUN-14 — The network's target wins when it has one; its silence is not an order to stop.** A
console assigning a target points the whole component; when the network has no target the gun uses its
own. Hold-fire is a separate switch from having a target, so a battery can track without shooting.
`TileTurret.java:148-160`, `WeaponNetworkState.java:19-45` [V]

**MECH-GUN-15 — A network that loses its last console forgets its target.** Otherwise a battery would
be left firing at a point nobody can retract — the one failure a player cannot fix by breaking
something. `WeaponNetworkDomain.java:41-51` [V]
[T `WeaponConsoleTest#losingTheLastConsoleClearsTheTarget`, falsified: with the clear disabled
that test goes red and the other two stay green]

**MECH-GUN-17 — The barrel is DRAWN, not built, because a block cannot be turned.** A block occupies
a grid cell at one of a handful of fixed orientations, so a gun made of blocks cannot point anywhere
off-axis. The bearing lives as two doubles and `RendererTurret` is the only thing that turns it into
something a player sees; the parts a player places are the gun's EQUIPMENT and the drawn barrel is as
long as the build earned (capped at 5 segments). Rejected alternative: moving the barrel into a
`StorageChunk` — its rotation is 90-degree steps over an array, its blocks live in a `WorldDummy`
outside the real world, and both the shot substrate and the damage engine read the real world, so the
barrel would stop being something a round could hit. `RendererTurret.java:13-25`,
`TileTurret#getBarrelLength` [V]

**MECH-GUN-18 — The client is sent the COMMAND, not the pose.** The bearing changes every tick while
a mount swings and the command changes once per engagement, so the tile's update tag carries the
commanded bearing, the traverse rate and the drive state, and the client runs the same
`TurretMechanism` against them. Sent only when the command has moved more than 2 degrees or the drive
state changed — a battery would otherwise put its barrels on the connection at the same rate as its
rounds. `TileTurret.java:198-224`, `:330-360` [V]
[T `TurretAimReachesClientTest` — falsified against a build with the sync disabled]

**MECH-GUN-44 — Only a player within reach operates a console or a sensor.**
`TileWeaponConsole#canInteractWithContainer` and `TileFireControlSensor#canInteractWithContainer`
answer vanilla's usability rule — same world, tile not invalid, `getDistanceSq(centre) <= 64.0`
(vanilla's `TileEntityFurnace` / `TileEntityLockableLoot#isUsableByPlayer`; on a VS ship VS's
`MixinEntity` measures to where the block really is) — and the server branch of `useNetworkData`
refuses a press that fails it, with one WARN naming the player, position, dimension and packet id.
Needed because `PacketMachine` resolves the CLIENT's dimension and position (a gap that stays open for
every other machine). Pinned by `MachineGuiClientGroupTest#aWeaponConsolePressFromBeyondReachChangesNothing`
(a forged press from 24 blocks is judged out of reach and changes nothing). `[T]`

**MECH-GUN-19 — A console is a stateless EDITOR, not an owner.** It keeps no target, no hold-fire
flag and no copy of the network's state; every button writes to `WeaponNetworkState` and every
readout reads from it. Two consoles on one network therefore cannot disagree — they are looking at
the same object — and breaking one loses the window, not the setting. Its `writeToNBT` deliberately
saves nothing of its own. `TileWeaponConsole.java:39-49,96-161,275-282` [V]
[T `WeaponConsoleTest#aConsolePointsEveryGunOnItsNetwork`]
Its BUTTONS reach that state through a packet: a GUI button fires on the client, where the network
state does not exist, so Hold Fire and Clear Target send a `PacketMachine` and the server applies
them in `useNetworkData` (the toggle computed from the server's own flag), under the GUI's
`canInteractWithContainer`. A button press only sends a `PacketMachine` (`TileWeaponConsole.java:595-603`); nothing is written to the client tile, which a dedicated server would never hear. `TileWeaponConsole#onInventoryButtonPressed`, `#useNetworkData` [V]
[T `WeaponGuiButtonsReachTheServerTest#theConsolesHoldFireButtonHoldsTheBatteryOnTheServer`]

**MECH-GUN-38 — The console's screen is TOLD by the server, while it is open.** Every line the screen
shows — network status, hold-fire, gun and on-target counts, the target, the sensor's contact — is
the network's or the guns' state, which exists only in the server session. So the server builds the
readout (`readoutTag`) and a module in the open container (`ReadoutSync`) sends it to that player as a
`PacketMachine` on opening and again whenever it differs from the last one sent; the client only
translates it into lines. A window property cannot carry it (a `short`; a target coordinate is not),
and an update tag would make every console push forever for screens nobody has open. The client
builds its lines BLANK until the first readout lands, rather than answering "no network" from its own
empty JVM. The client never derives a line itself (`TileWeaponConsole.java:432-440`): on a dedicated server the panel would always read "no network" / not holding, and in single player it would read the integrated server's objects
across threads. `TileWeaponConsole#getModules`, `#readoutTag`, `#showReadout`,
`ReadoutSync` [V]
[T `WeaponGuiButtonsReachTheServerTest#theConsolesScreenShowsTheServersNetwork`]

**MECH-GUN-39 — A player names a target by LOOKING at it through a linker bound to the weapon
(ruled 2026-10-04).** Two acts. **Bind**: a linker clicked on a console (a SNEAKING right-click — a
plain one opens the console's screen, as on every machine with one) or on a gun is bound to that
tile; a linker already bound elsewhere is re-bound, because the second click has no other meaning
here. **Designate**: a right-click with the bound linker anywhere that is not a linkable machine — into
the air, or on an ordinary block within arm's reach — traces the player's line of sight and hands the
tile the first thing it crosses. A creature becomes an entity order (followed as it moves, MECH-GUN-21);
a block becomes a POINT order at the spot the sight landed on, in WORLD coordinates — a ship block's
point is reported globally by the physics substrate, so no frame conversion is needed. One replaces
the other, because a followed entity outranks a point and a stale order would win. Bound to a CONSOLE
the order goes to the network (every gun on it); bound to a GUN it is that gun's own order, which a
network order still outranks (MECH-GUN-14). A console on no network refuses and says so. The trace
reaches the server's view distance — the farthest the server keeps the world loaded around a player,
so nothing he can see is beyond it and a trace past it would load chunks for a click. Every refusal
the player could not otherwise tell from success is a chat line: nothing in sight, a binding whose
machine is not loaded here, a console with no network. The design question (a click on a block reaches
only ~5 blocks, useless for a battery) was put to the maintainer, who chose the line-of-sight form.
Both `onLinkStart` and `onLinkComplete` bind the linker to the console (`TileWeaponConsole.java:397-408`); the order itself is given by `onLinkAimed` (`:415`). `ItemLinker#onItemRightClick`, `#lineOfSight`, `ILinkAimedTile`,
`LinkerDesignation`, `TileWeaponConsole#onLinkAimed`, `TileTurret#onLinkAimed` [V]
[T `ALinkerNamesTheBatteryItsTargetTest` — a real client binds, looks and clicks; the battery turns
onto a block 22 blocks off and fires, then onto a creature]

**MECH-GUN-40 — A pilot names a SHIP as his battery's target with T (rulings 2026-10-05).**
*"по нажатию T выдавать целеуказание на корабль, находящийся в окрестности курсора игрока (по сути же
"вперёд" от него, ибо в кресле он сидит в фиксированной позиции)"*. Only from the helm: T is the
`PILOTING` binding, vanilla chat the `NOT_PILOTING` one (`client-render/input-hud` MECH-CLR-09). The
server takes the pilot's eye and his ship's HEADING — read from the hull's attitude
(`FreeFlightPhysics#bodyBasisFromQuat`), since a seated pilot's view is pinned to it — and names, among
the loaded ships other than his own, the one whose world bounds lie at the smallest angle from that
line (zero when it passes through them); a ship behind him (>= 90 degrees) is never named, and there is no
further cone, the chat line saying how far the named ship is. Every weapons network with a console
aboard the pilot's ship takes a SHIP order, replacing any point, creature or ship order. Refusals are
chat lines: no ship ahead, no battery aboard, a seat on no ship. `ShipDesignation#designate`,
`TilePilotSeat` `PACKET_DESIGNATE_SHIP` [V]
[T `HelmControlsClientGroupTest#aPilotsTNamesTheShipAheadAndTheCrewRuleSparesItOnceHeBoardsIt` — a
nearer ship 45 degrees off is not named over the one dead ahead; the one ahead, moved behind him, is not
named at all]

**MECH-GUN-41 — A ship order aims at the hull as a whole, wherever it is, led.** Hull-as-a-whole by
ruling (aiming at modules and compartments does not exist). Each tick the gun reads the target's WORLD bounds
(`shipWorldBoundsOf`) and aims at their middle, led by the ship's velocity there minus the shooter's own
(MECH-GUN-26's intercept, blocks per tick). A ship order is a NETWORK order and outranks
every order the gun holds itself; a ship that is not loaded on this side (no bounds, or no velocity) is
aimed at nowhere — the mount holds its bearing — rather than falling through to an older order.
`TileTurret#shipIntercept`, `#getEffectiveTarget` [V]
[T `TurretOnAShipTest#aBatteryToldToEngageAShipAimsAtItsHullWhereverItIs` — moved 120 blocks, the
aim follows; red with the shipyard bounds read instead]

**MECH-GUN-42 — A HULL's friend-or-foe is a RULE the installation picks (ruling 2026-10-05).** *"надо
сделать все три через общий интерфейс и дать игроку выбирать в оружейной консоли"*. A hull carries no
credential, so `HullAllegiance#isFriend(world, ship, code)` decides, and the console's "Friendly Ships"
button cycles the network through `HullAllegianceRule`: `CODE_ON_WEAPONS` (a console or gun aboard that
ship carries our code — a new network's rule, the one its owner controls), `CODE_ON_CREW` (a player
aboard carries our code), `NONE`. An empty code recognises no hull, as it recognises no creature. A
friend is not fired on (`TileTurret#targetIsFriendly`).

**MECH-GUN-43 — A battery's orders live on its CONSOLES and come back after a restart (rulings 2026-10-05).** The network is still never saved — it has no durable name and is rebuilt from
the world — but every order (access code, hold-fire, hull rule, and the target: point, creature or
ship) is copied onto every weapon console of the network, which writes it as `weaponOrders` with the
stamp `weaponOrdersStamp` (the world's total time of the order). Every order setter of
`WeaponNetworkState` bumps a revision, and each console re-copies on the next solve when it changed
(`TileWeaponConsole#applyNetworkState`); an order a console itself issues is copied at once, so a cable
join in the same tick cannot lose it. A rebuilt component adopts the orders of its FRESHEST console
(`WeaponNetworkDomain#onComponentRebuilt`, `seedFromLatestOrders`, ties keep the inherited state): the
last order wins when two batteries are joined. The sensor's acquired track is not saved — it is
re-acquired. Pinned by `SubsystemNetworkRestartTest#aWeaponNetworksOrdersComeBackFromItsConsolesAndTheBatteryObeysThem`
(two real boots; the gun holds fire after the restart) and
`WeaponConsoleTest#theLastOrderWinsWhenTwoBatteriesAreJoined`. Bound: a gun whose chunk loads
before its console's runs on a fresh, order-less network until the console loads.
`HullAllegianceRule`, `WeaponNetworkState#getHullAllegiance`, `TileWeaponConsole.java:90-91,704-721` (`weaponOrders`, `weaponOrdersStamp`) [V]
[T `TurretOnAShipTest#aHullWhoseWeaponsCarryOurCodeIsSparedOnlyUnderThatRule` (CODE_ON_WEAPONS,
NONE); `HelmControlsClientGroupTest` (CODE_ON_CREW)]

**MECH-GUN-20 — Tracking and shooting are separate switches.** Hold-fire stops the firing and keeps
the target, so a battery watching an approaching ship does not have to forget where it is in order to
stop shooting. Releasing it resumes. `TileWeaponConsole.java:133-150`, `WeaponNetworkState.java:36-45`
[V] [T `WeaponConsoleTest#holdFireStopsTheShootingAndKeepsTheTarget`]

**MECH-GUN-21 — A gun tracks an ENTITY, not only a point.** A target that moves is followed: the
aim is recomputed each tick from the entity's body centre, and an entity that dies or logs out simply
stops being found, which leaves the mount holding its bearing rather than swinging to a remembered
position. `TileTurret.java:172-206` [V]
[T `TurretFriendOrFoeTest`]

**MECH-GUN-22 — Friend-or-foe is a credential the TARGET carries.** *(Only a
PLAYER can present it — `CodeUtils.entityHasMatchingCode` returns false for anything else,
`affs/util/CodeUtils.java:43`. The network's code survives a restart on its consoles — MECH-GUN-43.)* An entity presenting the
installation's access code is a friend for exactly as long as it carries it; nothing keeps a list of
who is friendly. A gun with no code recognises nobody, deliberately: a battery that shoots nothing is
indistinguishable from a broken one. The code lives on the NETWORK when there is one, because "whose
side are we on" is a property of the installation and guns that disagreed would shoot the crew at
random. `TileTurret.java:208-230`, `TileWeaponConsole.java:133-160`, `CodeUtils#entityHasMatchingCode` [V]
[T `TurretFriendOrFoeTest`, falsified: with the check removed the gun shoots the code-carrying
player]

**MECH-GUN-36 — Commanding a battery is UNGUARDED, and that is the ruling (2026-08-20).** The
console's commands — `assignTarget`, `assignTargetEntity`, `setAccessCode`, `clearTarget`,
`setHoldFire` — take no player and ask nothing; a linker's designation (`onLinkAimed`, MECH-GUN-39)
likewise. Anybody who can
reach the block can point the guns. This is deliberate and matches how nearly every machine in this
game behaves: keeping strangers away from a block is a protection mod's job, and one is already
consulted before any block is TAKEN (`structural-damage` MECH-DMG-33). A bespoke permission layer here
would duplicate that and still lose to a pickaxe.

**Do not read `owner` / `faction` as that layer.** Both fields exist on `TileTurret`, both persist,
and both are stamped onto every round — but `setOwner` and `setFaction` have **zero callers anywhere
in the tree**, so `owner` is always null and `faction` always falls through to the network's access
code. The access code is therefore what actually marks a round as ours, and it is a target-side
credential (MECH-GUN-22), not a permission to command. The two setters are the seam a future
attribution layer would use and are documented at their declarations as unwired.
`TileWeaponConsole.java:117-181`, `TileTurret.java:354-356,688-706` [V]

**MECH-GUN-23 — A build DECLARES what it needs delivered.** `GunSpec.getDeclaredInputs()` is never
empty (Forge Energy is the floor), and a part shipped by an addon declares its own. `GAS` is declared
and reserved — nothing consumes it, which is what makes adding the supply later a change to one place
rather than to every gun. `GunInput.java:5-19`, `GunSpec.java:133-139` [V]

**MECH-GUN-24 — Manual control is a MODE, not a second gun.** Under a hand nothing assigns a target,
the mount obeys the bearing it is handed, and firing is an explicit act — through the same `launch`
the automatic path uses, so heat, cooldown, charge, line of fire and friend-or-foe all still apply. A
manned gun that skipped any of them would be strictly better than the same gun on a console, which is
a balance decision nobody made. The seat and the first-person view are a later wave; this is the half
that has to exist for them not to be a rewrite. `TileTurret.java:150-158,196-247` [V]
[T `TurretStandaloneTest#aGunUnderManualControlIgnoresItsTargetAndFiresOnlyWhenTold`]

**MECH-GUN-25 — Mount telemetry is READ off the guns, never accumulated.** The console counts how
many of its members are on target and how many are saturated by asking them; a second copy on a
different cadence would be a readout that disagrees with the guns it describes. A saturated count
above zero is the console's answer to "why is nothing being hit" — the target is outside somebody's
arc, a fact about the build rather than a fault. `TileWeaponConsole.java:163-197` [V]

**MECH-GUN-26 — An acquired contact is LED; a named one is not.** Given a track, the mount is pointed
at where the target and the round arrive together, solved from the track's velocity and this gun's own
muzzle speed. Aboard a moving hull the shooter's own motion is subtracted first, because the round
inherits it — the lead that matters is relative. A target a human named carries no velocity, so it is
aimed at directly; that is not an oversight but the absence of the measurement.
`TileTurret.java:281-301`, `TurretFireControl.java:101-134` [V]
[T `TurretInterceptTest` — the contract is a ARRIVAL, not a formula: round and target reach the aim
point together]

**MECH-GUN-27 — A poor lock tracks without shooting, and only ever for an ACQUISITION.** A contact
resolved below `fireControlSensorLockQualityToFire` is followed and not fired at, which is the state
that makes illuminating worth its emission. An order a human gave is never vetoed this way: the
sensor's opinion of a target is not authority over a player's. Precedence is console target → gun's
own target → acquisition, with manual control outranking all three.
`TileTurret.java:234-242,315-342` [V]
[T `FireControlSensorTest#aCoolTargetTooFarToHoldByListeningIsHeldByIlluminating`, falsified: with
the gate forced true the battery puts six rounds into a contact held at 0.038 and only that test
reddens]

## Invariants

**INV-GUN-01 — Server only.** The gun's tick returns immediately on a client world; nothing about
firing exists on the client except the drawing of the round.
`TileTurret.java:79-81`, `TurretFireControl.java:83` [V]

**INV-GUN-02 — A spec that is not operable never fires.** "Complete" is decided once, on the sum
(`GunSpec#isOperable`), and both the fire gate and the launch path check it — a bare controller is not
a gun. `GunSpec.java:63-65`, `TileTurret.java:135-141`, `TurretFireControl.java:83-85` [V]
[T `TurretStandaloneTest#anUnbuiltControllerIsNotAGunAndFiresNothing`]

**INV-GUN-03 — A round is paid for before it counts as fired.** Cooldown, heat and energy are only
spent when the substrate accepted the launch; a refused launch (`-1`) leaves the gun exactly as it was.
`TileTurret.java:123-133` [V]

**INV-GUN-04 — A part is a block, not a tile entity.** A hundred-block gun costs a hundred block
lookups and no tile entities, and an addon adds a part by implementing one interface with nothing to
register. `IGunPart.java:6-21`, `BlockGunPart.java:23-38` [V]

**INV-GUN-05 — The mount's bearing survives a save.** Yaw, pitch, the standing command and the drive
state round-trip; a reloaded gun points where it was left.
`TurretMechanism.java:170-193`, `TileTurret.java:330-375` [V]
[T `TurretMechanismTest#theBearingSurvivesARoundTrip`]

## Failure modes & edge cases

**A target a human named is a point (open).** An ACQUIRED contact is led (MECH-GUN-26), but a named
target is a point: nothing measures its velocity, and a gun cannot measure one on its own — which
is what a fire-control sensor is for. Neither path compensates for the round's fall
(see `fire-control-sensor`, the ballistic-drop gap). [A]

**Friend-or-foe screening has one open edge.** Acquisition screens at LIST level: an ally carrying the
code, and anybody standing on the sensor's own ship, never becomes a contact at all
(`fire-control-sensor` MECH-FCS-03/04). A SHIP order is screened by the
installation's hull rule (MECH-GUN-42), so a gun pointed at an allied ship spares it. A gun pointed at a
POINT on an allied hull (a linker's block click, MECH-GUN-39) still fires — a point carries no ship —
and nothing ACQUIRES a hull (see `fire-control-sensor`, hull acquisition). [A]

**MECH-GUN-28 — A shot-up mount walks DOWN a ladder of named states, and the last rung still
fires.** The controller reads its own block's condition every tick and derives a drive state from it:
turning, turning slowly, seized. Seized is not dead — it holds the bearing it stopped at and shoots
down it, which is why the ladder ends in a name and not in a rate of zero. Nothing pushes: the stage
is a fact in the world and one lookup a tick is cheaper than a subscription, so this survives a save,
a chunk reload and a ship reassembly for free, exactly as the stage does. The two rungs' positions
are config; their ORDER is the mechanic. `TileTurret.java:190-201`, `TurretDriveState.java:57-64` [V]
[T `TurretDamageDegradesTest`, falsified: with the read removed only that test reddens and all
seven standalone gun tests stay green; `TurretConditionTest`]

**MECH-GUN-28a — Condition never overrules a DECISION.** An explicitly set state — a player's lock, a
probe's kill, whatever later wave adds a power failure — outranks the condition-derived one, and the
condition speaks only for a mount nobody has said anything about. The alternative loses a lock the
first time the gun is scratched and can never give it back after a repair.
`TurretMechanism.java:181-195` [V] [T `TurretConditionTest#aDecisionOutranksCondition`]

**MECH-GUN-29 — A damaged PART gives less of what it gives.** The assembly walk scales each part's
contribution by its own condition, so a battered barrel adds less speed AND tightens the cone less —
the negative contribution scales too, which is the case a plain multiply gets backwards. A part
damaged to nothing still counts as a part: it is bolted on, it is in the way, and it is something to
repair. Read during the walk, so it takes effect on the next re-walk rather than instantly.
`GunAssembly.java:96-104`, `GunSpec.java:183-195` [V] [T `GunPartConditionTest`]

## Relationships

- **projectile-substrate** — the only thing this hands a round to: `ShotSubstrate.launch(world, spec)`.
  The gun decides nothing about the flight and the substrate decides nothing about the gun.
- **subsystem-network** — one domain (`Weapon`) over the shared solver; a gun is a sink. Absence of a
  network is a supported configuration, which is why nothing here treats a null state as an error.
- **fire-control-sensor** — the only thing that ACQUIRES. It publishes one contact into the weapons
  network's state; a gun reads it last, after every order a human gave, and refuses to fire on one it
  cannot hold.
- **integration-vs** — the ship-frame port at the muzzle, by ship id.
- **dimension-planets** — the gravity multiplier that becomes the round's declared environment.
- **structural-damage** — read-only, one way: the gun asks `DamageState` how far gone its own blocks
  are. The damage engine knows nothing about guns, and there is no event between them.
- **stellurgytest-probe-catalog** — `/stellurgytest turret read|target|cleartarget|charge|drive`.
