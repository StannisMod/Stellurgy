---
id: rocket-entity
owns: [entity/, api/FreeFlight*, api/RocketFlightMode*, tile/TileGuidanceComputer.java]
entrypoints: [EntityRocket#onUpdate, EntityRocket#prepareLaunch, EntityRocket#launch, EntityRocket#tickFreeFlight, EntityRocket#useNetworkData, TileGuidanceComputer#getDestinationDimId]
depends-on: [dimension-planets, rocket-assembly, api-public, network-wire, space-stations, satellite, mission]
depended-by: [rocket-assembly, client-render, infrastructure-tiles, integration-probe-waila]
contracts: [C1, C2, C4, C5]
confidence: high
---

## Purpose

The flying rocket. `EntityRocket` is the in-world entity a built rocket becomes:
it owns the launch countdown, ascent/orbit/descent state machine, cross-dimension
travel, the interplanetary solar-map flight, and — additively — the opt-in
arcade **Free Flight** control mode (attitude quaternion + Flight Assist). The
subsystem also carries the sibling vehicles (station-deployed gas harvester,
space-elevator capsule, hovercraft), the destination-resolving Guidance Computer
tile, and the entity/particle effects.

## Responsibility boundary

**Owns**: `EntityRocket` (3855 LOC) and its subclass `EntityStationDeployedRocket`;
the free-flight decision layer (`api/FreeFlightPhysics`, `api/FreeFlightInput`,
`api/RocketFlightMode`); the Guidance Computer tile that resolves *where* a rocket
goes; the other rideable/effect entities; and all `entity/fx/*` particles. Owns
the rocket-entity NBT keys and the `EntityRocket.PacketType` sub-packet routing.

**Does NOT own**: rocket assembly / block-scan → `StorageChunk`, which is
`rocket-assembly` (this entity *holds* a `StorageChunk` but does not build it);
`StatsRocket` / `FuelRegistry` / `RocketEvent` / `StellurgyConfiguration` → `api-public`;
the `PacketEntity` wire wrapper and `PacketHandler` → `network-wire`; dimension
gravity/orbit numbers → `dimension-planets`; space-station pads & warp →
`space-stations`; missions spawned on orbit → `mission`; the rocket GUI modules &
renderer → `client-render`.

## Key types

| type | role |
|------|------|
| `EntityRocket` | the rocket entity; classic + free-flight state machine, inventory, network |
| `EntityStationDeployedRocket` | subclass: automated gas-giant harvester launched from a station |
| `RocketFlightMode` | enum `CLASSIC_LAUNCH` / `FREE_FLIGHT`; NBT-tolerant read; DEFAULT=CLASSIC |
| `FreeFlightInput` | immutable per-tick pilot intent (7 floats + cut flag), self-clamping, wire codec |
| `FreeFlightPhysics` | pure-Java arcade kinematics: quaternion attitude, FA setpoint, Newtonian, liftoff |
| `FreeFlightPhysics.Quat` | unit quaternion, body→world; FF attitude source of truth |
| `FreeFlightPhysics.Step` | immutable post-step kinematics snapshot (motion, yaw/pitch/roll, thrustApplied) |
| `TileGuidanceComputer` | inventory hatch resolving destination dim + landing coords from a chip/linker |
| `EntityElevatorCapsule` | space-elevator car; vertical transit + cross-dim teleport |
| `EntityHoverCraft` | independent surface hover vehicle (submarine/blimp), fuel-burning |
| `EntityItemAbducted` | short-lived visual: item rising into a UFO/laser |
| `EntityLaserNode` | positional anchor for a mining/space laser beam (unsaved) |
| `EntityDummy` | invisible seat proxy so a player can "sit" |
| `entity/fx/*`, `RocketFx`, `TrailFx`… | client-only particle effects (engine trail, laser, oxygen, arcs) |

## Mechanic index

**[Classic flight](./classic-flight.md)** — the scripted launch→orbit→travel→land path. The height a flight climbs to is the world's physical transfer line, not a
per-planet number (classic-flight, MECH-RKT-03's entry-height note).
- MECH-RKT-01 Launch countdown, gating & abort
- MECH-RKT-02 Ascent burn & fuel consumption
- MECH-RKT-03 Orbit-reached dispatch (satellite / manned / unmanned)
- MECH-RKT-04 Descent phase & auto-retro landing detection
- MECH-RKT-05 Cross-dimension transfer on orbit
- MECH-RKT-06 Interplanetary solar-map navigation
- MECH-RKT-07 RCS deprecation shim
- MECH-RKT-08 Guidance-computer destination & landing resolution
- MECH-RKT-09 Infrastructure linking & hand-fueling interact

**[Free flight](./free-flight.md)** — opt-in arcade pilot mode.
- MECH-RKT-10 Flight-mode selection & authority
- MECH-RKT-11 Engine-start ritual & liftoff gate
- MECH-RKT-12 Attitude-quaternion integration
- MECH-RKT-13 Flight Assist velocity-setpoint control
- MECH-RKT-14 Newtonian (FA-off) direct thrust
- MECH-RKT-15 Engine-start liftoff hover assist
- MECH-RKT-16 FF thrust authority & fuel burn
- MECH-RKT-17 FF landing auto-shutdown
- MECH-RKT-18 Client predict-then-correct smoothing
- MECH-RKT-19 FF pilot-input wire codec

**[Persistence & wire](./persistence-wire.md)** — how the entity round-trips.
- MECH-RKT-20 Entity NBT round-trip
- MECH-RKT-21 `PacketEntity` sub-packet routing

**[Vehicles & FX](./vehicles-fx.md)** — sibling entities and effects.
- MECH-RKT-22 Station-deployed gas-harvest rocket
- MECH-RKT-23 Space-elevator capsule transit
- MECH-RKT-24 Hovercraft vehicle
- MECH-RKT-25 Ancillary entities & particle FX

## Dependency edges

- **← rocket-assembly**: holds a `StorageChunk` (the packed blocks + tiles + Guidance
  Computer) built there; `deconstructRocket` hands blocks back.
- **← dimension-planets**: `DimensionManager.getDimensionProperties(dim)` supplies
  `getGravitationalMultiplier`, `orbit`, `renderSize*`, gas-giant flags used by both
  flight paths.
- **← space-stations / satellite / mission**: orbit-reached spawns `MissionOreMining` /
  `MissionGasCollection`, deploys satellites, and resolves station landing pads.
- **← network-wire**: every client↔server exchange is a `PacketEntity` carrying an
  `EntityRocket.PacketType` ordinal (see persistence-wire).
- **→ client-render**: renderer/camera/HUD read `getFfQuat`, `getFreeFlightRoll`,
  `getEnginePower`, FA setpoint DataParameters.

## Contracts touched

C1 (NBT keys — see persistence-wire), C2 (the `PacketType` sub-packets, `FreeFlightInput`
wire, `PacketBackToRocketGui`), C4 (config flags — see each sub-doc's Config surface),
C5 (`RocketEvent.*` posted: PreLaunch, Launch, ReachesOrbit, DeOrbiting, Landed).
