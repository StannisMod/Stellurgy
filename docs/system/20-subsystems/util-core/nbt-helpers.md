# util-core / NBT toolkit

Parent: [00-overview.md](./00-overview.md) · Files: `nbt/NBTHelper.java` (257),
`nbt/NBTTagCompoundBuilder.java` (161), `nbt/NBTTagListCollector.java` (48),
`nbt/Factory.java` (6), `nbt/ParametrizedFactory.java` (6), `NBTStorableListList.java` (51)

A generic, functional-style NBT (de)serialization toolkit used across the mod for
collections, maps, block-positions, AABBs, blockstates, tile-entities and entities. It
defines several **contractual wire shapes** (C1) that many subsystems reuse, so its key
names are load-bearing beyond `util/`.

## Key types

| type | role |
|------|------|
| `NBTHelper` (static) | write/read collections, maps, `BlockPos`, `AxisAlignedBB`, `IBlockState`, `TileEntity`, `Entity` |
| `NBTTagCompoundBuilder` | fluent builder wrapping an `NBTTagCompound` (`setX(...).build()`) |
| `NBTTagListCollector` | `Collector<NBTBase, NBTTagList, NBTTagList>` for stream→list |
| `Factory<T>` / `ParametrizedFactory<I,O>` | `create()` / `create(I)` functional SAMs used as (de)serializers |
| `NBTStorableListList` | round-trips a list of `DimensionBlockPosition` |

## Mechanics

- **MECH-NBT-01 — collection & map (de)serialization.** `writeCollection`/`readCollection`
  and `writeMap`/`readMap` convert between `Collection`/`Map` and `NBTTagList`/
  `NBTTagCompound` using a `ParametrizedFactory` serializer and a `Factory` collection
  supplier; `INBTSerializable` elements serialize themselves.
  `NBTHelper.java:29-99`.
- **MECH-NBT-02 — primitive dispatch.** `write(key,Object,compound)` type-switches over
  Integer/Long/String/Boolean/Float/Double/Byte/NBTBase/byte[]/int[]; `read(key,compound)`
  reverse-dispatches by tag type (returns null for unknown). `NBTHelper.java:101-153`.
- **MECH-NBT-03 — contractual value shapes.** `BlockPos` ⇄ a single `long`
  (`pos.toLong()`); `AxisAlignedBB` ⇄ compound of doubles `minX minY minZ maxX maxY maxZ`;
  `IBlockState` ⇄ compound `{name:string, meta:short}` (null state ⇒ sentinel
  `NBT_NULL="null"` string). `NBTHelper.java:163-221, 179-197`.
- **MECH-NBT-04 — TE/entity carriers.** `writeTileEntity`/`readTileEntity` and
  `writeEntityToCompound`/`readEntityFromCompound` wrap vanilla NBT with a `NBT_NULL`
  sentinel for null TEs. `NBTHelper.java:223-256`.
- **MECH-NBT-05 — fluent build + stream collect.** `NBTTagCompoundBuilder` chains typed
  setters (incl. `setBlockPos`/`setAABB`/`setCollection`/`setMap`/`setResourceLocation`)
  delegating to `NBTHelper`; `NBTTagListCollector` folds a stream of `NBTBase` into an
  `NBTTagList` (unordered, identity-finish). `NBTTagCompoundBuilder.java:34-160`,
  `NBTTagListCollector.java:15-47`.

## State & persistence (C1 wire shapes owned here)

| shape | keys | owner |
|-------|------|-------|
| AABB | `minX minY minZ maxX maxY maxZ` (double) | `NBTHelper.writeAABB` :199 |
| blockstate | `name` (string), `meta` (short) | `NBTHelper.writeState` :179 |
| null sentinel | `NBT_NULL` = `NBTTagString("null")` | `NBTHelper.java:27` |
| `NBTStorableListList` | tag `list` of `{loc:int[3], dim:int}` | `NBTStorableListList.java:23-45` |

These key names are consumed by many subsystems (rocket-assembly, station, mission);
restructure them freely for the 0.1.0 clean break (pre-0.1.0 saves are abandoned); just keep every consuming subsystem in step within a version.

## Invariants

- **INV-NBT-01 [V][SYS]** A null `IBlockState`/`TileEntity` serializes to the `NBT_NULL`
  sentinel and deserializes back to null. `NBTHelper.java:180-181, 190-191, 224, 238`. FOR: save format: tile and state NBT helpers.
- **INV-NBT-02 [V]** `getTagList` throws `IllegalArgumentException` if the named tag is not
  an `NBTTagList` (fail-fast, no silent empty). `NBTHelper.java:155-161`.
- **INV-NBT-03 [V][SYS]** `BlockPos` is stored as a single `long`, so any reader must use the
  long form (not a 3-int array). `NBTHelper.java:163-177`. FOR: save format: BlockPos as one long.
- **INV-NBT-04 [V]** `NBTStorableListList.readFromNBT` clears its list before repopulating,
  so a re-read is idempotent (no accumulation). `NBTStorableListList.java:38`.
- **INV-NBT-05 [A][SYS]** The AABB double shape round-trips. No test asserts it directly: the fluid-tank
  restart scenario reaches it only through tank contents. FOR: save format: tile and state NBT helpers.

## Failure modes & edge cases

- `write(key,Object,...)` silently no-ops for a type outside its switch (e.g. `short`,
  `long[]`, a `List`) — a caller passing an unsupported type gets no tag and no error.
  `NBTHelper.java:105-127`.
- `readState` calls `block.getStateFromMeta` without null-checking the looked-up block, so
  a blockstate saved for a now-uninstalled mod block NPEs on read.
  `NBTHelper.java:195-196`.
- `NBT_NULL` is a shared mutable static `NBTBase` reference; equality is by value
  (`"null"` string) so this is safe, but the field is not `final`.

## Integration seams

Pure NBT/C1; no packets, config or events of its own. `ParametrizedFactory`/`Factory` are
the SAM types threaded through `EntityRocket`, station and mission serializers.

## Config surface

None.

## Test coverage

Round-trip shapes pinned indirectly by `FluidTankNBTRoundTripsAcrossRestartTest` and
`PacketSerializationTest`; no dedicated `NBTHelper` unit test.

## Open questions

- `readState` NPE on a missing block (uninstalled mod) is unverified — a save/wire compat
  risk worth a targeted test.
