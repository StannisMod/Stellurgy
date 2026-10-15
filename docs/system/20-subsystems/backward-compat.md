---
id: backward-compat
owns: [backwardCompat/]
entrypoints: [WavefrontObject#<init>, WavefrontObject#loadObjModel, WavefrontObject#tessellateAll]
depends-on: []
depended-by: [client-render]
contracts: []
confidence: high
---

## Purpose

A self-contained Wavefront `.obj` mesh importer and OpenGL renderer. Despite the
package name `backwardCompat` (and the 00-index "legacy save/registry migration"
label), this subsystem performs **no save-format or registry migration** — it is a
vendored copy of the classic Forge `AdvancedModelLoader`/`WavefrontObject` code, kept
because Forge dropped the built-in OBJ classes these renderers were written against.
It parses `.obj` text into `Vertex`/`TextureCoordinate`/`Face`/`GroupObject` and streams
those faces into a `BufferBuilder` for entity/tile/armor renderers.

## Responsibility boundary

Owns: parsing `.obj` model text (vertices, normals, texture coords, faces, groups);
per-group tessellation into a `BufferBuilder`; the `ModelFormatException` type.
Does NOT own: model file lookup/caching (that is `ClientProxy.getModel`, subsystem
client-render), texture binding, GL state setup, or any world/save state. Holds **no
persistent state** — every instance lives only in a client-side static field.

## Key types

| class | role |
|-------|------|
| `WavefrontObject` | parser + render facade; holds parsed `vertices/vertexNormals/textureCoordinates/groupObjects` lists |
| `GroupObject` | one `g`/`o` group: name, list of `Face`, GL primitive mode (`glDrawingMode`), `VertexFormat drawMode` |
| `Face` | polygon: `Vertex[] vertices`, `Vertex[] vertexNormals`, `Vertex faceNormal`, `TextureCoordinate[]` |
| `Vertex` | mutable float `x,y,z` (also reused for normals) |
| `TextureCoordinate` | mutable float `u,v,w` |
| `ModelFormatException` | checked exception thrown on any parse/IO failure |

## Mechanics

- **MECH-BCMP-01 — Resource-backed construction.** Constructing `WavefrontObject(ResourceLocation)`
  fetches the resource through `Minecraft.getResourceManager()` and delegates to
  `loadObjModel`; IO failure is wrapped in `ModelFormatException`. A second ctor accepts a
  raw `(String, InputStream)`. `WavefrontObject.java:47,58`.
- **MECH-BCMP-02 — Line-classified parse.** `loadObjModel` reads line-by-line, collapses
  whitespace (`replaceAll("\\s+"," ").trim()`), and dispatches by prefix: `v `→vertex,
  `vn `→normal, `vt `→texcoord, `f `→face, `g `/`o `→group. Each parse method first
  validates the line against a precompiled regex, else throws `ModelFormatException`.
  `WavefrontObject.java:184,197-233`.
- **MECH-BCMP-03 — 1-based index resolution.** Face tokens (`v`, `v/vt`, `v//vn`,
  `v/vt/vn`) are split and each index resolved against the already-parsed lists with
  `list.get(idx-1)`, converting OBJ's 1-based indices to 0-based. Face normal is computed
  when absent via a cross product of two edges. `WavefrontObject.java:408-493`, `Face.java:63`.
- **MECH-BCMP-04 — Primitive-mode inference.** A group's `glDrawingMode` is inferred from
  the first face: 3 tokens → `GL_TRIANGLES`, 4 → `GL_QUADS`; a later face of the other
  arity in the same group throws `ModelFormatException`. `WavefrontObject.java:418-430`.
- **MECH-BCMP-05 — Texture-V flip.** `vt` V-coordinate is stored as `1 - v` to convert
  OBJ bottom-left origin to Minecraft top-left. `WavefrontObject.java:395,397`.
- **MECH-BCMP-06 — Group-scoped tessellation.** `tessellateAll/renderAll` and the
  `*Only`/`*Part`/`*AllExcept` selectors iterate `groupObjects` and push each `Face` into
  a `BufferBuilder` via `Face.addFaceForRender`, which emits pos/tex/normal per vertex with
  a small per-vertex texture inset (`textureOffset` default 0.0005). All render methods are
  `@SideOnly(CLIENT)`. `WavefrontObject.java:255-347`, `Face.java:15,42-60`.

## State & persistence

None. No NBT keys, no registry names, no config flags, no packets, no mixins — this
subsystem appears in zero `coverage/seam-*.tsv` rows. All fields are in-memory geometry;
instances are cached only client-side by `ClientProxy.getModel` and by static fields in
individual renderers.

## Invariants

- **INV-BCMP-01 [V]** Face vertex/texcoord/normal indices are 1-based in the file and
  dereferenced as `idx-1`; an out-of-range or non-existent index yields
  `IndexOutOfBounds`/parse failure rather than silent wrong geometry.
  `WavefrontObject.java:441-443,481`.
- **INV-BCMP-02 [V]** Every parse method rejects a malformed line by throwing
  `ModelFormatException` (checked), so a bad model cannot silently produce a half-built
  mesh mid-parse. `WavefrontObject.java:364,382,402,486,505`.
- **INV-BCMP-03 [V]** A group mixes only one primitive arity: once `glDrawingMode` is set,
  a face of the other arity throws. `WavefrontObject.java:421,427`.
- **INV-BCMP-04 [V]** Texture V is stored flipped (`1-v`); consumers must not re-flip.
  `WavefrontObject.java:395,397`.
- **INV-BCMP-05 [A]** The parser is intended for single-threaded client render/load; the
  `static` `Matcher` fields (`vertexMatcher` … `groupObjectMatcher`) are shared mutable
  state and are not safe for concurrent parsing. Unverified by any test.
  `WavefrontObject.java:36-38`.

## Failure modes & edge cases

- A `vt ` line encountered before any `g`/`o` group dereferences `currentGroupObject`
  (still `null`) at line 212 → `NullPointerException`. Faces guard this; texture
  coords do not.
- `parseGroupObject` may return `null` (empty name), after which line 230 unconditionally
  sets `group.drawMode` → NPE.
- A file with no groups and no faces leaves `currentGroupObject == null`, which is then
  added to `groupObjects` (line 236); later render iteration NPEs.
- On any `ModelFormatException`, `ClientProxy.getModel` swallows it via `printStackTrace`
  and returns `null`; each renderer must null-check before drawing.

## Integration seams

Purely a client-render library. Upstream: `Minecraft` resource manager (model bytes),
`Tessellator`/`BufferBuilder`/`DefaultVertexFormats` (GL output). Downstream consumer is
`client-render` — `ClientProxy.getModel` (cached) plus ~27 `Render*`/`Renderer*` classes
that new-up a `WavefrontObject` into a static field. No events, capabilities, packets, or
mixins.

## Config surface

None. No flag gates this subsystem; it cannot be disabled and has no balance tunables.

## Test coverage

None. No `src/test` file references `WavefrontObject`, `ModelFormatException`, or the
`backwardCompat` package. All invariants are `[V]`/`[A]`; none are `[T]`.

## Open questions

- Is concurrent model loading ever possible (resource reload on a worker thread)? If so
  INV-BCMP-05's static matchers are a live hazard. → needs a repro before promotion.
