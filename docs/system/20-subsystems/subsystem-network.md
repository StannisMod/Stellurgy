---
id: subsystem-network
owns: [subsystem/network/]
entrypoints: [SubsystemNetworkEvents#onWorldTick, SubsystemNetworkEvents#onWorldUnload, SubsystemNetworkManager#of, SubsystemNetworkManager#getState, SubsystemNetworkManager#markDirty, SubsystemNetworkManager#register]
depends-on: [api-public]
depended-by: [shields, atmosphere-oxygen, ship-heat, weapons, fire-control-sensor]
contracts: [C5]
confidence: high
---

## Purpose

The one distribution network every built-block subsystem shares: sources give a commodity, sinks ask
for it, cables limit what can pass between them, and once a tick each connected component is solved
as a max flow. Shields were its first carrier; ventilation, heat rejection and turret control are the
others the design already names (*"A network holds the state; everything else reads/writes
it… extracted into a code abstraction — a `subsystem-network` primitive"*).

## Responsibility boundary

**Owns:** node membership per domain; connected-component discovery over block adjacency; the
max-flow solve with priority tiers; the per-component statistics and status; the state object that
survives a topology rebuild.
**Does NOT own:** what the commodity IS. The unit belongs to the domain — the solver moves whole
integers per tick and never learns whether they are shield energy, air exchange or watts. It also
does not own persistence: a domain saves its own settings through whatever tile holds them.

## Key types

| type | role |
|------|------|
| `SubsystemNetworkDomain` | one commodity and its identity; hooks for a custom state and controller seeding. Domains never merge graphs. |
| `ISubsystemNetworkNode` | membership: world, position, and the domain the node names for ITSELF. |
| `ISubsystemSource` | `getAvailable` / `extract`, plus `getGenerationPerTick` (what it MAKES, not what it holds). |
| `ISubsystemSink` | `getRequested` / `getFreeCapacity` / `receive`, plus `getPriority` and `getConsumptionPerTick`. |
| `ISubsystemCable` | `getThroughputPerTick` / `addTransferred`, plus an optional `onNetworkStats` readout. |
| `ISubsystemNetworkController` | a console: reads and edits state, carries nothing. |
| `SubsystemNetworkState` | the network's SSOT — members, root, and last tick's statistics. |
| `SubsystemNetworkManager` | ONE PER RUNNING SERVER (INV-NET-07): holds that session's node registry and each world's solved topology; registry snapshot → components → solve. Reached by `of(world)` (server worlds only, loud otherwise); `getState` reads it for a world of either side. `nodesIn(domain, world, type)` answers "every loaded X" for a domain that would otherwise keep its own list (shields' INV-SHD-23). |
| `SubsystemNetworkRegistry` | package-private, one per manager: which nodes exist, per domain. |
| `SubsystemNetworkEvents` | the `@Mod.EventBusSubscriber`: `WorldTickEvent` END → `tick(world)`, server `WorldEvent.Unload` → `releaseWorld(world)`, both on `of(world)`. |
| `SubsystemNetworkStatus` | `DISCONNECTED/SOURCE_LIMITED/SINK_LIMITED/CABLE_LIMITED/BALANCED`. |

## Mechanics

- **MECH-NET-01 adjacency is the network** — a component is a BFS over the six neighbours of that
  domain's node positions. Two touching nodes are one network with no cable between them, so cables
  are a reach-and-capacity tool, never a requirement. `SubsystemNetworkManager.java:169-196`.
- **MECH-NET-02 the unified port model** — every node gets a supply port and/or a demand port;
  adjacent supply→demand links are opened at INF, and a cable's own in→out edge carries its finite
  throughput. That is what makes a cable the ONLY throttled link in the graph. `:305-330`.
- **MECH-NET-03 delivery is a max flow, not a share-out** — Dinic over the component. A route that
  cannot carry the commodity therefore does not silently cap the whole network at its own capacity.
  `:429-500`.
- **MECH-NET-04 priority tiers** — sink demand edges open at 0 and are raised tier by tier in
  descending priority, re-augmenting between tiers: a starved supply fills what the player marked
  important first, equal priorities share the rest. One priority = one pass = plain max flow. `:355-372`.
  *Share is MAX-MIN FAIR (plain max flow inside a tier would give the
  remainder to whichever sinks the augmenting paths reached first, in hash order).*
  `SubsystemNetworkManager#shareTier`: a tier that can be fully served is one max flow; otherwise each
  round binary-searches the largest EQUAL raise all still-rising sinks can take together, each raise
  tried as a real max flow and undone if it does not fit, so it never claims more than the cables
  carry; a sink retires when served or when a one-unit trial shows it cannot take more alone; an
  indivisible remainder goes one unit at a time in position order (deterministic). The tier total
  still equals its max flow. Cost O(n·(n + log R)) max flows per SCARCE tier (n sinks, R the largest
  request). Pinned by `SubsystemNetworkFairShareTest` (50/50, 30/70 capped, a 10-cable sink gets 10,
  tier order). `[T]`
- **MECH-NET-05 topology is cached, capacities are not** — the component graph is rebuilt only on
  `markDirty` (a node placed or broken); the solve runs every tick against the cached topology,
  because availability and demand move constantly while adjacency does not. `:78-92`.
- **MECH-NET-06 state survives a rebuild** — a rebuilt component inherits the state object of
  whichever old network its members came from; a component that SPLIT takes a copy, so two networks
  never share one settings object. This is what lets console settings live through re-laying a line.
  `:246-266`.
- **MECH-NET-07 the status names the binding constraint** — not merely how much arrived, but which of
  supply / demand / transport bound it, which is the single thing a player needs before deciding what
  to build next. `:518-533`.
- **MECH-NET-08 a domain gets the component itself, on both paths** — `onComponentRebuilt` receives
  the membership when topology changes and `onComponentTicked` runs every tick just before the state
  is published, disconnected components included. This exists because the solver's residue is not
  universally discardable: shields and ventilation drop what no sink asked for, and heat cannot,
  because a machine does not get to keep its waste heat just because no radiator wanted it. Both
  hooks are no-ops by default, so a domain that does not need them is unaffected.
  `SubsystemNetworkDomain.java:43-63`, `SubsystemNetworkManager.java:250,492`.

## Invariants

- **INV-NET-01 [T]** Domains are isolated: the registry is keyed by domain, so nodes of two domains
  laid through the same wall never join one graph. A node states its own domain
  (`ISubsystemNetworkNode.getNetworkDomain`), so registering one into the wrong graph is not
  expressible, and anything holding a node — a cable deciding whether to draw an arm to its
  neighbour, a readout walking the world — can ask without a per-domain marker interface to test
  against. `SubsystemNetworkRegistry.java:30-46`, `ISubsystemNetworkNode.java:23`.
- **INV-NET-02 [T]** Roles are not exclusive — a store registers as both source and sink and lands in
  both maps; a cable is only ever transport. `SubsystemNetworkManager.java:135-155`.
- **INV-NET-03 [V]** `extract`/`receive` are called with the solved flow only, once per tick per
  node, after the whole component is solved — never speculatively during the solve. `:394-410`.
- **INV-NET-04 [T]** A component with no source or no sink publishes `DISCONNECTED`, moves no
  commodity, and reports that to controllers AND cables alike — one publish path, both states.
  *A console pulls the state on its own tick, so the push to controllers changes nothing displayed.*
  `SubsystemNetworkManager.java:455-478`,
  `test/server/ShieldConsoleReportsCollapseTest.java`.

## Test coverage

| what | test |
|------|------|
| the solve, through the shield domain | `test/server/Shield{PriorityRedistribution,ZoneThroughput,LimiterBalance,Accumulator,PriorityGroupControl}Test` — the shield tiles implement the primitive's interfaces directly, so this row exercises its own vocabulary rather than a per-domain translation of it |
| domain isolation, INV-NET-01 | `test/server/VentilationNetworkTest.aShieldCableIsNotADuctAndCarriesNoAir` — a shield cable standing in a duct run carries no air, with the plant scenario beside it as the positive control. |
| the solve driven deterministically | `/stellurgytest subnet solve <domain> <dim> <ticks>` runs the same tick the event handler calls. Needed because a probe holds the server thread: waiting on wall-clock buys no world ticks, and 300 requested ticks of waiting once produced FOUR solves |
| reconstruction + settings, across a real restart | `test/server/SubsystemNetworkRestartTest` — two boots on one world: the ventilation graph comes back with the same cables/sources/sinks/members having been saved NOWHERE, while the vent's zone priority and the console's resistance bias come back with the same values because their own tiles persisted them. Both halves in one test, because the split is the design. |
| the domain hooks, through heat | `test/server/HeatLoopTest` — the heat domain is the only user of MECH-NET-08, and its three scenarios are what pins that the per-tick hook runs on the DISCONNECTED path too: a coolant loop has neither source nor sink, so every one of its ticks takes that exit |
| **not pinned** | more than one source or sink per network; what a node does when its chunk unloads while the rest of the network stays loaded; a node playing two roles at one position — not merely untested but currently broken |

## Open questions

- `DISCONNECTED` pushes to controllers, which changes nothing
  observable, because every controller today pulls. The open part is the inverse — a
  controller written to rely on the push alone would have been fine either way, and nothing states
  which of the two is the contract. Say so before a second controller exists.
- **A network is deliberately NOT persisted, and that is a property rather than a debt.** It has no
  durable name to be saved under: it is not an object but "the blocks that happen to be connected
  right now", and breaking one cable turns one network into two, both new. That is exactly why
  MECH-NET-06 inherits state by MEMBERSHIP OVERLAP instead of by id. Saving it would mean inventing
  a key (an anchor position, say) that lies the first time a player re-routes a line — and would
  import the whole class of "the loaded network no longer matches the world" defects, whose only
  cure is the rebuild-from-world this design already does unconditionally. No save format also means
  no migration for it at a version bump.
  **The consequence, stated as a rule and not a gap:** a setting lives on a BLOCK, because only a
  block has a name (its position). The shield console persists its bias, the vent its zone priority.
  A domain wanting a network-wide setting must give it a block to live in; that is the answer, not a
  workaround.
  **Pinned** by `SubsystemNetworkRestartTest`, which asserts both halves at once:
  rebuilt-not-restored for the graph, restored-exactly for the settings.

- **INV-NET-05 [V]** Server only — the writers (`register`, `unregister`, `markDirty`, the tick and
  the unload) are reached through `SubsystemNetworkManager.of(world)`, which REFUSES a null world with
  `IllegalArgumentException` and a client world with `WrongSideException` (`util/WrongSideException`,
  ours: Forge 1.12.2 has none); every tile call site guards on `!world.isRemote` first. The
  static `getState` refuses a client world the same way (ruling: no cross-side read,
  so single-player and a dedicated server cannot answer differently); it still answers null for a
  null argument or when no server session runs. `SubsystemNetworkManager.java:62-86`, `SubsystemNetworkEvents.java:21-39`.
- **INV-NET-06 [V]** A world unload drops that world's state and its nodes.
  `SubsystemNetworkManager.java:136-144`, `SubsystemNetworkRegistry.java:60-72`.
  `getState` throws `WrongSideException` for a client world (INV-NET-05), by ruling.
- **INV-NET-07 [V]** Network state lives exactly as long as the server session that built it. The
  manager is a field of the server's `ServerState` (`subsystemNetworks`, final), built with it in
  `beginServerLifetime` from `serverAboutToStart`, before the first world loads and its tiles register
  (a second begin over a live server state is a loud `IllegalStateException`), and dropped with it in
  `endServerLifetime` from `serverStopped`. `Stellurgy.subsystemNetworks()` reads it, null when no
  server runs. A server world asking for its networks with none
  attached is therefore a lifecycle bug and `of(world)` throws rather than answering with an empty
  manager. **Why it matters only for an integrated server**: a dedicated server's restart is a new
  JVM, so the old JVM-wide maps could only ever leak across an integrated server reopened from the
  title screen — and there, only what the per-world unload missed (a node whose tile skipped
  `invalidate`/`onChunkUnload`). `Stellurgy.java:316-331,1561-1565,1696`. **Not pinned by a test,
  and cannot be with today's harness**: both tiers run a DEDICATED server (a restart is a new
  process) and `ClientBot` has no verb that opens an integrated world, so no tier ever runs two
  sessions in one JVM.

## Failure modes & edge cases

**A leaked node entry.** The registry and the per-world states are instance state of the per-server
`SubsystemNetworkManager` (INV-NET-07). A tile destroyed without
`invalidate` or `onChunkUnload` running still leaks its entry until its world unloads, bounded
by the server session. [V]

## Relationships

- **shields** — the first carrier; generators are sources, field generators and injectors are sinks, cables are cables and
  the console is a controller, and the domain keeps its own state subclass for the resistance bias.
- **atmosphere-oxygen** — the ventilation domain: its own domain object and tiles.
- **ship-heat** — the coolant loop, and the reason MECH-NET-08 exists at all: heat is the domain that
  may NOT discard the solver's residue.
- **weapons** — guns are sinks, and the shared state carries a target.
- **fire-control-sensor** — a second node kind in the weapons domain: a sink (priority 1, above the
  guns) that writes an acquired contact into the shared state. Deliberately NOT a controller — the
  domain clears a battery's target when the last CONTROLLER goes (MECH-GUN-15), and a sensor counting
  as one would quietly weaken that.
- **api-public** — `Constants` for the mod id on the event subscriber.

Four domains now share this solver and none of them can see another's graph; that is INV-NET-01, and
this is the first build with enough domains for the claim to be worth anything.
