# rocket-entity — Vehicles & FX

Parent: [00-overview.md](./00-overview.md). The sibling entities beyond the main
`EntityRocket` and the client-only particle effects.

## MECH-RKT-22 — Station-deployed gas-harvest rocket
`EntityStationDeployedRocket extends EntityRocket` — an automated (seatless,
`INVALID_SEAT`) rocket launched from a space station toward a gas giant to harvest
gas `EntityStationDeployedRocket.java:54`. It overrides `onUpdate` entirely (does
**not** call super): a directional coast-out then thrust away from the station along
`launchDirection`/`forwardDirection`, grabbing a 9-chunk `ForgeChunkManager` ticket
on takeoff and releasing it on landing/return `:200`,`:289`. `launch()` refuses
unless tanks are full and the destination is a gas giant `:113`. The gas selector offers
exactly the giant's `DimensionProperties.getHarvestableGases()` — the gases present in its
air that have a registered fluid (CON-C21-02; `offeredGases` `:562`); an empty offer shows
"no gas" instead of indexing a list. `onOrbitReached` plans the harvest: cap =
min(config `gasHarvestAmountMultiplier`×64k mB [or infinite], free tank capacity), duration
`GasHarvest.missionSeconds` — the sublinear capacity curve and the cap, both at an intake
rate of 25 mB/s × intake power × the chosen gas's partial pressure in atm, so a gas twice as
dense is collected in half the time and a trace gas is slow, never refused `:396`,`:457`;
`GasHarvest.java:44` `[T]` — then spawns a `MissionGasCollection` and `setDead`s. The
harvest does NOT yet deplete the planet's air (the planet carries no amounts to deplete). There is no depth axis: the partial pressure is the body's reference
level. Fuel burns per thrust tick via `tryConsumeAscentFuel` mirroring the classic biprop
pairing. Adds the `MENU_CHANGE` packet for the gas-type selector.

## MECH-RKT-23 — Space-elevator capsule transit
`EntityElevatorCapsule` — the car of a `TileSpaceElevator`; a 3×3 rider platform
that ascends/descends between a source and destination `DimensionBlockPosition` and
teleports across dimensions at the top `EntityElevatorCapsule.java:41`. Motion state
is a signed `motionDir` DataParameter (ascend/descend/idle) plus a `standTimeCounter`
dwell timer (`MAX_STANDTIME=200`) `:43`; it leaves a world and arrives in one at that world's
transfer line (`transferHeightIn` → `DimensionManager.transferLineOf`, `metric-boundary`
MECH-MET-02; a world with none gives the top of the block band, logged once). Cross-dim transit
uses a `BasicTeleporter`; the client requests its NBT on spawn via its own
`PACKET_RECIEVE_NBT` `:126`. Persists `motionDir`, `dstDimid`/`dstLoc`,
`srcDimid`/`srcLoc` (each guarded by `hasKey`) `:101`,`:131`.

## MECH-RKT-24 — Hovercraft vehicle
`EntityHoverCraft implements IInventory, INetworkEntity` — a standalone surface hover
vehicle (`VehicleType` submarine/blimp) independent of the rocket state machine
`EntityHoverCraft.java:26`. Burns a fuel item from its 1-slot inventory for
`currentBurnTime`; bounded by `MAX_HEIGHT=250`, `HORIZONTAL_VMAX`, `VERTICAL_VMAX`,
`MAX_ACCELERATION` (all `tunable`), with optional `cruiseControl`. Steering
(`turningUp`/`down`) rides the `up`/`down` scratch keys through the rocket
`PacketType.TURNUPDATE`/`MENU_CHANGE` ids it imports `:347`. Persists **only** its
embedded inventory (`inv.writeToNBT`); position/velocity are vanilla `Entity` state
`:438`. Client interpolation via `lerp*` fields.

## MECH-RKT-25 — Ancillary entities & particle FX

| type | role | persistence |
|------|------|-------------|
| `EntityItemAbducted` | item rising into a UFO/laser (motionY=2, `lifespan=200` ticks) | `Item`/`Age`/`Lifespan` |
| `EntityLaserNode` | positional anchor + validity flag for a mining/space laser beam | none (intentionally unsaved) |
| `EntityDummy` | invisible zero-height seat proxy so a player can sit on a chair | none |
| `FxSkyLaser`, `fx/FxLaser`, `FxLaserHeat`, `FxLaserSpark` | client laser-beam particles | client-only |
| `fx/FxElectricArc`, `FxSystemElectricArc`, `FxGravityEffect` | arc / gravity visual particles | client-only |
| `fx/OxygenCloudFX`, `OxygenTraceFX` | oxygen leak/vent particles | client-only |
| `RocketFx`, `TrailFx`, `InverseTrailFx`, `InverseTrailFluid` | engine plume / exhaust trail particles | client-only |

The `fx/*` classes are `@SideOnly(CLIENT)` particle subclasses with no server state
or NBT — pure render, out of the physics/wire contract. They are listed for file
coverage (P1) and not mechanically decomposed.

## Invariants

- **INV-RKT-19 [V][BEH]** `EntityStationDeployedRocket` overrides `onUpdate` without
  calling `super.onUpdate()`, so none of the classic launch-countdown / orbit branches
  run for it `EntityStationDeployedRocket.java:168`.
- **INV-RKT-20 [V][BEH]** Gas-harvest planned amount is capped by *simulated* free tank
  capacity (`fill(...,false)`) before the mission is created, so a full rocket does
  not over-plan `EntityStationDeployedRocket.java:455`.
- **INV-RKT-21 [V]** `EntityLaserNode.isValid` is a transient flag, deliberately not
  saved (chunk-loading a laser without an emitter would crash)
  `EntityLaserNode.java:23`.
- **INV-RKT-22 [A][BEH]** `EntityHoverCraft` persists only inventory; a save/reload during
  flight drops its velocity and burn timer to defaults. Inferred from `:438`; not
  test-pinned.

## Failure modes & edge cases

- `EntityItemAbducted` registers its `ITEM` DataParameter against `EntityItem.class`,
  not `EntityItemAbducted.class` `EntityItemAbducted.java:18` — the data-manager key
  is allocated under the wrong entity class (id-collision / wrong-owner risk).
  (med, likely).
- `EntityElevatorCapsule.setStandTime(int time)` ignores its `time` argument and
  re-stores the current field value `EntityElevatorCapsule.java:84` — dead parameter;
  callers cannot actually set the stand time through it. (low).

## Integration seams

Events: `EntityElevatorCapsule` posts `RocketLaunchEvent`/`RocketDeOrbitingEvent`
analogues via its `PACKET_LAUNCH_EVENT`/`PACKET_DEORBIT` ids.
`EntityStationDeployedRocket` posts `RocketReachesOrbitEvent`/`RocketDeOrbitingEvent`/
`RocketLandedEvent` and broadcasts a `PacketSatellite` for the spawned mission
`:417`,`:501`. TheOneProbe integration reads station-rocket harvest gas via
`getSelectedHarvestGas` `:697`.

## Config surface

Config: see `C4-config-surface`. The capsule's transfer height is not config. Hovercraft envelope constants are code-level `tunable`, not config.

## Test coverage

Harvest timing law: `unit/GasHarvestTest` (3, red-witnessed). The offer:
`server/MissionGasCompletionTest.aHarvesterIsOfferedExactlyTheGasesTheAirHolds` (reads the
planet's answer through `planet info`'s `harvestable`). **Not pinned**: the wiring inside
`onOrbitReached` from the selected gas to `GasHarvest` — no fixture builds a station
orbiting a gas giant with a station-deployed rocket on it.
