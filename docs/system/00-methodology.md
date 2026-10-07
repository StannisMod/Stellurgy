# System Documentation — Methodology

This file is the maintenance guide for `docs/system/`: how a system doc is written, what it may
contain, how its completeness is checked, and when it must change. Source code and tests cite the
anchors defined here (`MECH-`, `INV-`, `CON-`, clause anchors such as `HEAT-7`), so the rules
below are part of the project's public contract.

## 0. What a system doc is

A system doc describes **how the system works now** — mechanics, invariants, contracts, flows — so
that an experienced Forge developer, reading only the docs, can reconstruct the behaviour of a
subsystem, not the text of its code.

**It never describes how the system came to be that way.** The rule, stated so it is checkable:

- no task ids, ledger or issue numbers, design-decision ids, review or audit names;
- no references to private working files, planning documents or process documents — a doc cites
  source (`file:line`), tests (class and method), other system docs (anchors) and, where a ruling is
  the ground of a clause, the maintainer's dated words quoted verbatim;
- no narration of change: no "until DATE X did Y", "RESOLVED … was:", amendment logs, "this clause
  was added after …", who found what. State the current behaviour. A defect that exists today is
  stated as current behaviour with its `file:line`; one that no longer exists is not mentioned;
- no snapshots that go stale without a gate noticing: no file counts, line counts, test counts, key
  counts or "as of DATE" figures. A number belongs in a doc only as a dated MEASUREMENT that grounds
  a tuned value or a relation (`measured 2026-08-15: …`), never as an inventory.

The reference classes above are mechanically scannable; prose that narrates history without naming an
artefact is not, and stays a reader's job.

## 1. Location and layout

```
docs/system/
├── 00-methodology.md        this file
├── 00-index.md              the router; the only always-load document
├── 20-subsystems/           one doc per responsibility boundary (a two-level subsystem is a directory)
├── 30-contracts/            cross-cutting contracts C1…; one doc each, clauses cited by anchor
├── 40-flows/                one player-visible path each, followed end to end
├── space-model.md           design roots: a MODEL, not a source-root boundary
├── universe-model.md
├── metric-boundary.md
└── tools/                   check-coverage.py, check-index.py
```

There is no directory of stored coverage ledgers and none of findings. Coverage is computed from the
live tree (section 2); a defect found while mapping is reported in the project's bug tracker, never
kept in the docs.

Working set per session: the index, plus one or two subsystem docs. Never the whole tree.

## 2. What "exhaustive" means, operationally

Completeness is not judged by eye. Each invariant is computed from the live tree by a script, so
nothing is stored that can drift:

- **P1 — file coverage.** Every `.java` file under the source roots (`src/main/java`, the vendored
  `valkyrienskies/src/main/java`, the test harness `testframework/src/main/java`, and `src/test`) is
  owned by exactly one subsystem doc. Ownership is machine-readable: the FRONT MATTER of every
  subsystem doc (of a two-level subsystem: its `00-overview.md` only) carries
  `owns: [ship/, integration/vs/HullSurvey.java, api/IShipActuatorBlock.java, !ship/legacy/]`, a list
  of path globs relative to `src/main/java/dev/stannismod/stellurgy/`; a vendored root is written
  with its own prefix (`valkyrienskies:org/valkyrienskies/`). `*` matches within a path segment, a
  trailing `/` means the whole directory recursively, and a leading `!` excludes. The MOST SPECIFIC
  matching glob (longest literal prefix) decides a file's owner, so `tile/TileGuidanceComputer.java`
  in one doc beats `tile/` in another. One file has ONE owner: a doc that describes part of another
  subsystem's file cites it, it does not co-own it. `tools/check-coverage.py` reports every file with
  no owner and every file two docs claim at equal specificity. A body line starting "Owns:" is prose
  and is read by no tool; the index's "owns" column is a human summary and is not what the checker
  reads.
- **P2 — contract coverage.** Every NBT key, registry id, network packet, config flag, mixin target
  and Forge event found in the source is referenced from a contract document.
  `tools/check-coverage.py` lists the ones no contract mentions.
- **P3 — mechanic coverage.** Every mechanic carries at least one invariant; every invariant is
  tagged verified-by-code, pinned-by-test, or *assumed*. Assumptions may not exceed 10 % of
  invariants — the rest are proven or promoted to defects in the bug tracker.
  `tools/check-coverage.py` computes the ratio.
- **P4 — the index is exhaustive.** Every `.md` under `docs/system/` is either listed in
  `00-index.md` or a child of a directory that is. A two-level subsystem is entered through its
  `00-overview.md`, and its mechanic sub-docs are reached in one hop from there.
  `tools/check-index.py` fails on an unreachable doc or a dead link; run it in the commit that adds,
  renames, splits or retires a doc.

A doc and the behaviour it describes are **one change**. Deferring the doc to a later commit
produces something worse than no doc: it reads as ground truth while describing a game nobody runs.

## 3. Compression rules

10:1 against gameplay code comes from prohibitions, not from terse prose.

- **No code in the docs.** A method signature inside a table is the maximum. Not one copied block.
- **One line per class** in the types table. Prose is spent on mechanics, never on classes.
- **Describe the rule, not the branching.** An `if/else` chain collapses into a single statement
  about the decision it makes.
- **Only contractual constants are pinned.** Anything reaching NBT, wire, registry or recipes is
  fixed. Balance numbers (RF/tick, speeds) are written as `tunable` and their values are not pinned.
- **Zero restatement of foreign APIs.** Forge / Vanilla / libVulpes behaviour is never described;
  only our seam against it is.
- **Zero restatement of another doc.** A clause belongs to exactly one doc: a cross-cutting rule to
  its contract, a mechanic or invariant to the subsystem owning the source. Every other doc cites the
  anchor in one line. A restatement acquires an independent lifetime and one of the two copies goes
  false.
- **No inventory in prose** of what another artefact lists. Route to the file that owns the list and
  say how to ask it; keep only what the source cannot say — the rule, how to choose, why a member is
  there at all.
- **Hard line budget per document.** Overflow means *split the subsystem*, not *write smaller*.
  Budgets: a Tier-A subsystem doc ≤ 400 lines, a Tier-B ≤ 200, a harness bucket ≤ 350, a flow ≤ 120;
  the index stays small enough to be read whole. A catalogue-shaped subsystem (`blocks`, `items`,
  `inventory-containers`, `integration-jei`, the probe catalogue) is a table of repetitive
  definitions, one row per entry, so a denser doc is intentional, not overflow.

## 4. Unit of description: subsystem, not package

Packages do not align with responsibility in this codebase: a rocket lives across `entity` + `api` +
`tile`; atmosphere across `atmosphere` + `tile/atmosphere` + `armor`. So the unit is the
**subsystem** (a responsibility boundary), and inside it the **mechanic** (trigger → state → effect).
The subsystem map and which doc owns which source live in `00-index.md` and in each doc's `owns:`
front matter. An oversized subsystem becomes a **two-level doc**: an overview (boundary + mechanic
index) plus mechanic sub-docs, each ≤ ~10:1.

**Flows** (`40-flows/`) follow one player-visible path end to end across the subsystems it crosses,
marking every producer→consumer seam and every `isRemote` fork: where "A expects X, B never supplies
it" gaps show up.

**Design roots** (`space-model`, `universe-model`, `metric-boundary`) describe a MODEL rather than a
source-root boundary, which is why no subsystem owns them.

## 5. Subsystem document template

```markdown
---
id: rocket-entity
owns: [entity/, api/FreeFlight*, api/RocketFlightMode.java, !entity/legacy/]
entrypoints: [EntityRocket#onUpdate, EntityRocket#launch]
depends-on: [dimension-planets, network-wire, api-public]
depended-by: [rocket-assembly, client-render]
contracts: [C1, C2, C4, C6]
confidence: high
---
## Purpose                  2–3 sentences
## Responsibility boundary  Owns: … | Does NOT own: …
## Key types                table, ≤ 15 rows: class → role
## Mechanics                MECH-RKT-01 …: trigger → state → effect
## State & persistence      NBT key → owner
## Invariants               INV-RKT-01 [V|T|A] …
## Failure modes & edge cases
## Integration seams        events, capabilities, mixins, packets
## Config surface           flag → mechanic → full-disable path
## Test coverage            INV-* → test class and method
## Open questions
```

Front-matter keys are never snapshots. A file count, a size or a source hash goes stale the moment
anyone touches a file, and no gate notices; `tools/check-coverage.py` computes counts and size from
the live tree. `owns:` replaces any older key that mixed paths with prose.

**Open questions** keep only genuinely open questions about the system, written without reference to
who raised them or when.

## 6. Contract document shape

A contract doc states cross-cutting clauses cited by clause anchor (`HEAT-7`, `BODY-4`,
`CON-C21-11`). A clause carries: its anchor, its confidence tag, its LEVEL (section 7), the rule, and
where it applies the `FOR:` ground. A status table beside the clauses says, per clause, which test
pins it and what is not built; it is re-derived from the test corpus whenever the contract is
touched (section 8), never carried forward.

## 7. Anchors, levels and traceability

Stable ids let docs, tests and source reference each other:

`MECH-<SUB>-NN` mechanic · `INV-<SUB>-NN` invariant · `CON-<C#>-NN` or `<PREFIX>-NN` contract clause.

This yields the chain **mechanic → invariant → test → defect**. A test names the anchor it pins in its
javadoc; source may cite an anchor where it implements one. The anchors are public API of the docs.

**An anchor is a permanent identifier — never renumber it and never reuse it.** Treat these exactly
like registry names and NBT keys: a reused number silently re-points somebody else's reference.
Retire an anchor **in place** with one line, `- **X-N** — retired.`; the number stays burned.

**Level.** Every `MECH-`/`INV-`/contract clause carries its LEVEL beside its confidence tag:

- `[BEH]` **behavioural** — what a player can see the game do; pinned by mechanics tests and e2e.
- `[SYS]` **system** — a law of our own code that a behaviour rests on, a public API, a save or wire
  format; pinned by unit and integration tests first. A `[SYS]` clause carries a `FOR:` line naming
  the `[BEH]` clause or the design decision it serves — a clause with nothing to name is the
  implementation describing itself, not a contract. A design decision is named by quoting the
  maintainer's dated ruling verbatim (in the language it was given, with a gloss beside it if
  needed), never by pointing at a private file.

A clause that no test pins carries `UNPINNED:` and one of exactly two reasons — the list is closed:

1. **a dated maintainer ruling that this clause goes unpinned**, quoted verbatim beside the date;
2. **"pinned by `<anchor>` on another tier"**, where that anchor exists, is itself tagged `[T]`, and the
   test that pins it names THIS clause back.

"No tier can observe it" is not a reason. It is a reason to extend the test harness until a tier can.

## 7a. When a doc must be updated

Any production change that alters a mechanic, an invariant, an NBT key, a packet, a registry id or a
config flag **must** update the owning subsystem doc and every affected contract doc in the **same
commit** as the code. A mechanic is a group of user-facing behaviour, so the same commit owes an e2e
that pins it, or a written reason why not. A system doc is not a follow-up chore, and it is never
generated from a template: system docs are written by hand against this file.

## 7b. A DESIGN-STAGE contract is the authority; the code is a snapshot

A contract ratified before its implementation exists is **the design of record**. Existing code that
does something else has not *violated* it — code written before a decision cannot break it. It is
**not yet rewritten**; the first framing makes the shipped behaviour the reference point and the
design the deviation, which is backwards and invites the next reader to "reconcile" the contract
towards the code.

So in a design-stage contract's status section:

- **state the clause first and unqualified.** It is what the system will do.
- **describe the code as a DISTANCE, not a verdict** — *not written* · *partly written* · *already
  realised* · *half-realised in a different shape*. Closing that distance is implementation work, not
  remediation.
- **name what the code already gets right, as evidence** the clause is buildable. A status table that
  lists only shortfalls reads as an indictment of work that was correct when it was done.

This is about the STANCE, not the word. A heading such as "Known violated" is right where a clause
prescribes and the code does not satisfy it yet: the list is the implementation checklist. Keep the
clause first and never let the shortfall list be the frame.

## 8. Confidence tagging — the anti-fabrication mechanism

At 10:1 compression a document degenerates into plausible fiction unless every behavioural claim is
sourced. Each claim carries a tag:

- `[V]` **verified** — code read, `file:line` cited
- `[T]` **pinned** — asserted by a test, class and method cited
- `[A]` **assumption** — inferred, not confirmed in code

**Citation rule: a mechanic with no `file:line` citation does not enter the document.** It goes to
*Open questions*.

**Verification rule: every `file:line` is re-checked MECHANICALLY after the last code edit, never
from memory.** Line numbers are the one part of a doc that rots the instant anyone touches the file —
including you, in the same session. After the final edit, script a pass that prints the cited line of
each citation, and read them. A citation nobody checked is exactly the "plausible fiction" the tags
exist to prevent — a wrong `file:line` is worse than none, because it *looks* verified.

Two defect classes fall out of this for free: an `[A]` that contradicts a `[T]`, and a `[T]` that
contradicts a `[V]`. The test suite is a second, independent oracle of the contracts; where oracle and
code disagree, one of them is a bug.

**Status rule: a clause-state table is RE-DERIVED from the test corpus, never carried forward.** A
"status at a glance" table is a *claim about the tests*, and it rots exactly like a `file:line` does,
because tests land without anyone walking back to the table. It rots in BOTH directions: a clause
sits at `[A]` while its assertion has been in the tree the whole time, and a `[T]` outlives a test
that was deleted, renamed or disabled — a green row over nothing. Cheapest discharge: grep the
clause's anchor and its own words across `src/test/**` before believing any row you did not write
this session.

## 9. Maintenance procedure

| Step | Work | Form |
|------|------|------|
| **Census** | Inventory NBT keys, registry ids, packets, config flags, mixin targets, events, ATs by grep over the source. No method bodies read. | `tools/check-coverage.py` |
| **Boundaries** | Assign every file to one subsystem (`owns:`). Zero orphans, zero duplicates. | solo, checked |
| **Deep read** | One subsystem at a time: read, fill the template, note defect candidates. | per subsystem |
| **Contracts** | Consolidate NBT/wire/registry/config from every subsystem into the data-surface contracts. Collisions surface here — one key with two owners, an id renamed without migration. | barrier |
| **Flows** | Stitch mechanics together end to end; expose producer/consumer gaps. | per flow |
| **Verify** | A defect candidate is confirmed only if independent reviewers with distinct lenses (correctness / save-wire compatibility / reproducibility) each try to refute it and it survives. | fan-out |
| **Completeness** | Files with no subsystem, mechanics with no invariant, invariants with no test, NBT keys with no round-trip. Whatever it finds is the next round; loop until two dry iterations. | loop-until-dry |

## 10. Defects found while mapping

### Target classes (checked per subsystem)

Chosen from what this codebase has actually suffered:

- **save/wire**: key written but never read · read with no default · renamed without migration ·
  `float` in persisted physics
- **side-safety**: client-only class referenced from common code · missing `world.isRemote` guard ·
  client-trusted input in a packet handler
- **config**: a flag gates accrual but not consequences; a flag copied into a static at class load
- **single source of truth**: two places deciding the same thing
- **mixin/coremod**: dev↔prod traps, the most expensive bug class here
- **other**: registry id collisions · chunk load inside a tick · unbounded collections · hardcoded
  dimension-id assumptions

### Discipline — do not fix during mapping

A defect found while mapping is reported in the project's bug tracker with symptom, evidence
`file:line`, the invariant violated, a reproduction sketch, severity and confidence — never fixed in
the same change as a doc and never recorded as a finding file inside `docs/system/`. Where the
defect is current behaviour of the system, the doc states it as current behaviour (one plain
sentence with `file:line`), with no anchor of its own. A reproduction test comes **before** production
code is touched. Mixing mapping with fixes produces both bad docs and bad fixes — the diffs
interleave and neither is reviewable.
