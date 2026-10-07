# util-core / Terraforming pipeline

Parent: [00-overview.md](./00-overview.md) · Files: `TerraformingHelper.java` (383),
`BiomeHandler.java` (387), `chunkdata.java` (143)

The stateful engine behind the terraforming laser: it re-derives what a chunk's terrain
*should* look like under a new biome set, then incrementally morphs the live world toward
that target across many ticks, driving three work queues (terraform / biome-change /
decorate). One `TerraformingHelper` instance lives per terraformed WORLD, as a `WorldRuntime` part
(`TerraformingHelper.of(world)`, `TerraformingHelper.java:64`); `BiomeHandler` is the stateless
worker invoked per block position; `chunkdata` is the per-chunk progress record.

## Key types

| type | role |
|------|------|
| `TerraformingHelper` | per-dim queues + chunk map + protected/border/allowed zone logic |
| `chunkdata` | per-chunk state: type, per-position `fully_generated/decorated/biomechanged` grids, cached target `IBlockState[16][16][256]` |
| `BiomeHandler` (static) | applies one terraform/decoration step at a `BlockPos`; sends biome packets |
| `TerraformingType` (enum) | `ALLOWED` / `BORDER` / `PROTECTED` |

## Mechanics

- **MECH-TERRA-01 — zone classification.** `get_chunk_type(x,z)` scans the dimension's
  protecting-block list: within `safe_zone_radius` (3) chunks ⇒ `PROTECTED`; within
  `safe_zone_radius+border_zone` (6) ⇒ `BORDER`; else `ALLOWED`. Recomputed by
  `recalculate_chunk_status`, which drops now-unprotected fully-generated chunks from the
  map. `TerraformingHelper.java:206-242`.
- **MECH-TERRA-02 — target-terrain derivation.** `getBlocksAt(x,z)` lazily builds the
  chunk's target column set by running a fresh `ChunkProviderPlanet` primer against the
  terraformed `ChunkManagerPlanet` biome provider, cached in `chunkdata.blockStates`
  (nulled once the chunk is done, to free memory). `TerraformingHelper.java:348-376`.
- **MECH-TERRA-03 — heavy terraform step (ALLOWED).** `BiomeHandler.do_heavy_terraforming`
  replaces up to `y_per_iteration` (3) mismatched blocks per tick from both ends of the
  column toward the target height; if the column height still ≠ target, re-queues the
  position and notifies borders; on match, marks the position fully generated.
  `BiomeHandler.java:90-162`.
- **MECH-TERRA-04 — border blending.** For `BORDER` chunks, height is a distance-weighted
  average of a 5-block neighbourhood, floored to sea level, and the column is rewritten in
  one pass (fill with `biomeId.topBlock` below, air above). `BiomeHandler.java:163-230`.
- **MECH-TERRA-05 — protected pass-through.** `PROTECTED` positions only change the top
  block via `decorate_simple` and never re-queue (biome changes once).
  `BiomeHandler.java:85-89`.
- **MECH-TERRA-06 — decoration gate.** `can_populate(x,z)` returns `1` only when the
  chunk and its +x/+z/+xz neighbours are all terrain-complete and none are `PROTECTED`
  (`-1`); `do_decoration` then shifts tree gen by +8,+8 to overlap chunk borders and
  scatters grass/flowers/mushrooms/cacti by the biome decorator rates.
  `TerraformingHelper.java:95-112`, `BiomeHandler.java:234-273, 335-383`.
- **MECH-TERRA-07 — completion cascade.** Per-position setters on `chunkdata`
  (`set_position_fully_generated` / `_decorated` / `_biomechanged`) flip the chunk flag
  when all 256 cells are done, then call back into the helper to advance border chunks,
  trigger `populate`, and register the chunk as fully terraformed/biome-changed in the world's `TerraformingRecord`.
  `chunkdata.java:62-141`.

## State & persistence

`TerraformingHelper` state is transient/in-RAM and dies with the world object; the durable record
is `TerraformingRecord`, a `WorldSavedData` named `stellurgy_terraforming` in the planet world's
per-world storage (`DIM<n>/data/`): fully-terraformed and fully-biome-changed `ChunkPos` sets and the
protecting blocks, under the keys `fullyGeneratedChunks`, `fullyBiomeChangedChunks`,
`terraformingProtectedBlocks` (`TerraformingRecord.java:26`, `:38`). Queues (`terraformingqueue`,
`decorationqueue`, `biomechangingqueue`) are unbounded `ArrayList<Vec3i>`.

## Invariants

- **INV-TERRA-01 [V]** A `PROTECTED` chunk is never re-added to the terraform queue;
  `add_position_to_queue` on a protected chunk instead marks the position generated.
  `TerraformingHelper.java:262-270`.
- **INV-TERRA-02 [V]** A chunk becomes decoration-eligible only when it and its three
  positive-diagonal neighbours are terrain-complete. `TerraformingHelper.java:95-109`.
- **INV-TERRA-03 [V]** Target-terrain `blockStates` are freed (set null) the moment a
  chunk is fully generated. `chunkdata.java:75`, `TerraformingHelper.java:173`.
- **INV-TERRA-04 [V]** Terraforming a position broadcasts `PacketBiomeIDChange` to
  clients within 1024 blocks. `BiomeHandler.java:295, 310` (C2).
- **INV-TERRA-05 [A]** The generate→decorate→biome-change cascade eventually marks every
  in-range chunk fully generated (progress monotone per position). No terraform
  integration test found — behavioural, unverified.

## Failure modes & edge cases

- queues are unbounded and the pipeline runs from tile ticks; a large
  terraform radius grows `terraformingqueue`/`decorationqueue` without a cap
  (`TerraformingHelper.java:47-49, 269, 292`). Chunk-load (`world.getChunkFromBlockCoords`)
  happens *inside* the border step (`BiomeHandler.java:181-184`) — chunk load in a tick.
- Heavy use of `System.out.println` on the hot path (dozens of call sites) — log spam.
- `chunkdata` allocates a `IBlockState[16][16][256]` (~64k refs) per active chunk until
  freed — memory pressure on wide terraform fronts.

## Integration seams

Packets: `PacketBiomeIDChange` (C2). Reads the world's `TerraformingRecord` for protected-block/
chunk lists; constructs
`ChunkProviderPlanet`/`ChunkManagerPlanet` (world-gen). Driven by the terraforming
multiblock tile (multiblock-machines) and `PlanetEventHandler` (event-handlers).

## Config surface

None directly (`generateTerraform*`-style flags live elsewhere; the two dead terraform
flags are in `api-public` config, not owned here).

## Test coverage

None dedicated to the pipeline itself; `TerraformingTerminalChipRecognitionTest`
exercises the driving terminal, not the block morph. INV-TERRA-05 is the weakest link.

## Open questions

- No test pins terraform convergence or queue-bound behaviour (INV-TERRA-05); the
  in-tick chunk load and unbounded queues are the highest-risk items here.
