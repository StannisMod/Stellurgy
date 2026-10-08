#!/usr/bin/env python
"""Check the map between contract clauses and the tests that pin them, in both directions.

A CLAUSE is a promise: an `INV-` invariant or a contract clause (`HEAT-7`, `ADDR-1`, `CON-C14-11`), defined
as a list item that opens with its bold anchor (`- **INV-SFM-12** `[T][SYS]` ...`). A `MECH-` entry
describes a mechanism and is pinned through its invariants; it is counted, never owed.

Every clause is in exactly one of four states:

  PINNED     tagged [T], names at least one test METHOD (`ClassTest#method`, `test/.../ClassTest#method`,
             or `…#method` continuing the previous class — a bare class is not a pin), every named method
             exists under src/test/java, and at least one of them has its OWN javadoc naming the clause's
             anchor and carrying a `red-witnessed:` record. A clause that lists `REGIONS: a, b, c` is
             pinned only when its named methods' javadocs together say `Visits <ANCHOR> regions: …` for
             every one of them — coverage is counted per (clause, region) (maintainer ruling
             2026-10-08). Until that day a class that mentioned the anchor anywhere, comments included,
             was a pin, and 148 of the 259 PINNED clauses rested on no more than that.
  UNPINNED   carries `UNPINNED:` with a reason from the closed list:
               - a dated maintainer ruling: `UNPINNED: maintainer ruling YYYY-MM-DD ...`;
               - `UNPINNED: pinned by <ANCHOR>` -- that anchor exists, is PINNED, and its test names
                 this clause back.
             "No tier can observe it" is not a reason; such a clause is OWED (and owes a task to extend
             the harness).
  BROKEN     tagged [T] or carrying UNPINNED:, but the evidence does not hold: a named test or method
             that does not exist, no back-reference, a ruling without a date, a pinned-by anchor that is
             missing or not itself pinned.
  OWED       neither: no [T], no UNPINNED:. Debt, listed, never a parse error.
  PLANNED    tagged [PLANNED]: a requirement for a mechanic not built yet. Owes nothing until the commit
             that builds it, which retags it and pins it.

A clause whose `FOR:` cites an ANCHOR must be younger than that anchor: the source a clause serves is
committed BEFORE it. The order is read from git (the first commit whose diff adds the anchor's bold
definition under docs/system); a clause first defined in the commit that published the docs is exempt,
because every clause arrived in that one commit and their order is not recorded there. A clause younger
than nothing — its `FOR:` anchor defined in the same or a later commit, or not committed while the clause
is — is BROKEN.

The REVERSE direction: every clause anchor a test under src/test/java cites must exist in the docs.

WHAT THIS DOES NOT SEE: whether a test really pins the clause it names (a name is a claim; the red
witness on the test is the evidence); a clause defined other than as a bold list item; a test named
without the `Test` suffix; the age of a `FOR:` that cites a ruling or a design document rather than an
anchor (the reviewer's); the order of clauses that arrived together in the publishing commit.

Usage: python docs/system/tools/check-pins.py [--list STATE ...] [--self-test]
Exit 0 = nothing BROKEN and no test cites a missing anchor; OWED is debt and is reported, not failed.
"""
import os
import re
import shutil
import subprocess
import sys
import tempfile

SYSROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
REPO = os.path.normpath(os.path.join(SYSROOT, "..", ".."))

ANCHOR = r"[A-Z][A-Z0-9]*(?:-[A-Z0-9]+)*-[0-9]+[a-z]?"
# The bold title may wrap onto the next lines (`- **FRAME-3 (the boundary is a MASS ratio …\n  … radius)**`),
# so only the OPENING is matched per line; where the bold closes is found in the joined block.
DEF_RE = re.compile(r"^[ \t]*-[ \t]*\*\*(" + ANCHOR + r")\b(.*)$")
TAGS_RE = re.compile(r"\[([^\]\n]*)\]")
TEST_REF_RE = re.compile(r"(?:[\w./]*/)?([A-Z]\w*Test)(?:#(\w+))?|…#(\w+)")
UNPINNED_RE = re.compile(r"UNPINNED:\s*(.*)", re.DOTALL)
RULING_RE = re.compile(r"maintainer ruling\s+\d{4}-\d{2}-\d{2}", re.IGNORECASE)
PINNED_BY_RE = re.compile(r"pinned by\s+`?(" + ANCHOR + r")`?", re.IGNORECASE)
CITE_RE = re.compile(r"\b(" + ANCHOR + r")\b")
FOR_RE = re.compile(r"\bFOR:\s*(.*)", re.DOTALL)
REGIONS_RE = re.compile(r"\bREGIONS:\s*([^.\n]+(?:\n[ \t]+[^.\n]+)*)")
VISITS_RE = re.compile(r"\bVisits\s+(" + ANCHOR + r")\s+regions:\s*([^\n*]+)")


def regions_of(body):
    """The regions a clause lists after `REGIONS:`, up to the sentence's end; empty when it lists none."""
    m = REGIONS_RE.search(body)
    if not m:
        return set()
    return {r.strip() for r in re.sub(r"\s+", " ", m.group(1)).split(",") if r.strip()}


def method_javadoc(src, method):
    """The javadoc directly above `void method(` (annotations allowed between), "" when it has none, or
    None when the class declares no such method."""
    m = re.search(r"\bvoid\s+" + re.escape(method) + r"\s*\(", src)
    if not m:
        return None
    head = src[:m.start()]
    end = head.rfind("*/")
    if end < 0:
        return ""
    start = head.rfind("/**", 0, end)
    if start < 0 or re.search(r"[;{}]", head[end + 2:]):
        return ""
    return head[start:end]
ADDED_DEF_RE = re.compile(r"^\+[ \t]*-[ \t]*\*\*(" + ANCHOR + r")\b")


def first_defined(repo):
    """{anchor: commit index} -- the first commit (0 = the oldest touching docs/system) whose diff ADDS
    the anchor's bold definition; None when git cannot answer (then the age check is skipped)."""
    try:
        out = subprocess.check_output(
            ["git", "log", "--reverse", "-p", "--format=COMMIT %H", "--", "docs/system"],
            cwd=repo, stderr=subprocess.DEVNULL).decode("utf-8", "replace")
    except Exception:
        return None
    first, index = {}, -1
    for line in out.split("\n"):
        if line.startswith("COMMIT "):
            index += 1
            continue
        m = ADDED_DEF_RE.match(line)
        if m:
            first.setdefault(m.group(1), index)
    return first


def for_anchors(body, known):
    """The anchors a clause's `FOR:` cites, up to the end of the sentence that carries it."""
    m = FOR_RE.search(body)
    if not m:
        return []
    sentence = re.split(r"(?<=[.;])\s", m.group(1), maxsplit=1)[0]
    return [a for a in CITE_RE.findall(sentence) if a in known]


def docs(sysroot):
    for d, _dirs, fs in os.walk(sysroot):
        if os.sep + "tools" in d[len(sysroot):]:
            continue
        for f in fs:
            if f.endswith(".md"):
                yield os.path.join(d, f)


def clauses(sysroot):
    """{anchor: (doc, inside-bold, body-text)} for every bold list-item definition."""
    out = {}
    for doc in docs(sysroot):
        with open(doc, encoding="utf-8", errors="replace") as fh:
            lines = fh.read().split("\n")
        i = 0
        while i < len(lines):
            m = DEF_RE.match(lines[i])
            if not m:
                i += 1
                continue
            block = [m.group(2)]
            j = i + 1
            while j < len(lines) and lines[j].strip() and not DEF_RE.match(lines[j]) \
                    and not lines[j].lstrip().startswith("#"):
                block.append(lines[j])
                j += 1
            text = "\n".join(block)
            inside, _sep, body = text.partition("**")
            out.setdefault(m.group(1), (os.path.relpath(doc, sysroot), inside, body))
            i = j
    return out


def test_index(repo):
    """{SimpleClassName: [paths]} over src/test/java -- a list, because a unit and an integration class
    may share a simple name, and a clause naming that name is pinned if EITHER file holds the method."""
    idx = {}
    base = os.path.join(repo, "src", "test", "java")
    for d, _dirs, fs in os.walk(base):
        for f in fs:
            if f.endswith(".java"):
                idx.setdefault(f[:-5], []).append(os.path.join(d, f))
    return idx


def read(path):
    with open(path, encoding="utf-8", errors="replace") as fh:
        return fh.read()


def test_refs(text):
    refs, last = [], None
    for cls, method, cont in TEST_REF_RE.findall(text):
        if cls:
            last = cls
            refs.append((cls, method or None))
        elif cont and last:
            refs.append((last, cont))
    return refs


def classify(sysroot, repo, ages=None):
    defs = clauses(sysroot)
    idx = test_index(repo)
    cache = {}

    def source(cls):
        if cls not in cache:
            cache[cls] = "\n".join(read(p) for p in idx[cls]) if cls in idx else None
        return cache[cls]

    def pinned(anchor, inside, body):
        tokens = TAGS_RE.findall(inside) + TAGS_RE.findall(body[:40])
        if "T" not in tokens:
            return None, None
        refs = test_refs(body)
        if not refs:
            return False, "tagged [T] but names no test"
        evidence, visited = False, set()
        for cls, method in refs:
            src = source(cls)
            if src is None:
                return False, "names %s, which is not under src/test/java" % cls
            if not method:
                continue  # a class is not a pin; it is no evidence, and no error beside a method that is
            doc = method_javadoc(src, method)
            if doc is None:
                return False, "names %s#%s, which has no such method" % (cls, method)
            if re.search(r"\b" + re.escape(anchor) + r"\b", doc) and "red-witnessed" in doc:
                evidence = True
                for named, regions in VISITS_RE.findall(doc):
                    if named == anchor:
                        visited.update(r.strip() for r in regions.split(",") if r.strip())
        if not evidence:
            return False, "no named method's own javadoc names %s AND carries a red-witnessed record" % anchor
        wanted = regions_of(body)
        if wanted - visited:
            return False, "regions not visited by its named methods: %s" % ", ".join(sorted(wanted - visited))
        return True, None

    state = {}
    for anchor, (doc, inside, body) in defs.items():
        if anchor.startswith("MECH-"):
            state[anchor] = ("MECH", doc, None)
            continue
        if re.search(r"^\s*(?:—|-)?\s*retired\b", body):
            state[anchor] = ("RETIRED", doc, None)
            continue
        if ages is not None:
            mine = ages.get(anchor)
            if mine != 0:  # 0 = arrived in the publishing commit, whose internal order is unknown
                younger = [a for a in for_anchors(body, defs)
                           if ages.get(a) is None or (mine is not None and ages[a] >= mine)]
                if younger:
                    state[anchor] = ("BROKEN", doc, "FOR: cites %s, not committed before this clause"
                                     % ", ".join(younger))
                    continue
        tokens_here = TAGS_RE.findall(inside) + TAGS_RE.findall(body[:40])
        if "PLANNED" in tokens_here and "T" not in tokens_here:
            state[anchor] = ("PLANNED", doc, None)
            continue
        ok, why = pinned(anchor, inside, body)
        if ok:
            state[anchor] = ("PINNED", doc, None)
        elif ok is False:
            state[anchor] = ("BROKEN", doc, why)
        else:
            state[anchor] = ("UNPINNED?", doc, None)
    for anchor, (doc, inside, body) in defs.items():
        if state[anchor][0] != "UNPINNED?":
            continue
        m = UNPINNED_RE.search(body)
        if not m:
            state[anchor] = ("OWED", doc, None)
            continue
        reason = m.group(1)
        by = PINNED_BY_RE.search(reason)
        if RULING_RE.search(reason):
            state[anchor] = ("UNPINNED", doc, None)
        elif by:
            other = by.group(1)
            if other not in state or state[other][0] != "PINNED":
                state[anchor] = ("BROKEN", doc, "pinned by %s, which is not a PINNED clause" % other)
            else:
                refs = test_refs(defs[other][2])
                # The test BETWEEN the two is a method of the other clause's pin whose own javadoc names
                # this clause too — never a comment anywhere in that class.
                back = any(m and source(c) and re.search(r"\b" + re.escape(anchor) + r"\b",
                                                         method_javadoc(source(c), m) or "")
                           for c, m in refs)
                state[anchor] = ("UNPINNED", doc, None) if back else \
                    ("BROKEN", doc, "pinned by %s, whose test does not name %s back" % (other, anchor))
        else:
            state[anchor] = ("BROKEN", doc, "UNPINNED: without a dated ruling or a 'pinned by <ANCHOR>'")
    missing = []
    known = set(defs)
    # Only a token whose PREFIX is one the docs define counts as a citation: `C-1`, `RC-2` and
    # `RESULT-1` are a test's local labels, while `FRAME-12` against a doc defining FRAME-1..11 is not.
    prefixes = {a.rsplit("-", 1)[0] for a in known}
    for cls, paths in idx.items():
        for path in paths:
            for cited in set(CITE_RE.findall(read(path))):
                if cited not in known and cited.rsplit("-", 1)[0] in prefixes:
                    missing.append((os.path.relpath(path, repo), cited))
    return state, sorted(missing)


def report(state, missing, wanted):
    counts = {}
    for kind, _doc, _why in state.values():
        counts[kind] = counts.get(kind, 0) + 1
    for kind in ("PINNED", "UNPINNED", "BROKEN", "OWED", "PLANNED", "RETIRED", "MECH"):
        print("%-9s %4d" % (kind, counts.get(kind, 0)))
    print("tests citing an anchor no doc defines: %d" % len(missing))
    for kind in wanted:
        for anchor in sorted(a for a, s in state.items() if s[0] == kind):
            _k, doc, why = state[anchor]
            print("  %s %-16s %s%s" % (kind, anchor, doc, (" -- " + why) if why else ""))
    if "MISSING" in wanted:
        for path, cited in missing:
            print("  MISSING %-16s cited by %s" % (cited, path))
    return 1 if counts.get("BROKEN") or missing else 0


def self_test():
    tmp = tempfile.mkdtemp()
    try:
        files = {
            "docs/system/20-subsystems/a.md":
                "- **INV-A-01** `[T]` pinned (`test/unit/AlphaTest#pinsIt`).\n"
                "- **INV-A-02** `[T]` names a ghost (`GhostTest#x`).\n"
                "- **INV-A-03** `[A]` nothing at all.\n"
                "- **INV-A-04** `[A]` UNPINNED: maintainer ruling 2026-10-07 \"not user-facing\".\n"
                "- **INV-A-05** `[V]` UNPINNED: pinned by INV-A-01 on another tier.\n"
                "- **INV-A-06** `[A]` UNPINNED: no tier can observe it.\n"
                "- **INV-A-07** — retired.\n"
                "- **MECH-A-01** a mechanism, never owed.\n"
                "- **INV-A-08 (a title that wraps\n  onto the next line)** `[T]` (`AlphaTest#pinsIt`).\n"
                "- **INV-A-09** `[PLANNED][BEH]` a mechanic not built yet.\n"
                "- **INV-A-10** `[A][SYS]` serves an older clause. FOR: INV-A-03.\n"
                "- **INV-A-11** `[A][SYS]` serves a clause younger than itself. FOR: INV-A-12.\n"
                "- **INV-A-12** `[A][BEH]` written after INV-A-11.\n"
                "- **INV-A-13** `[T]` names only a class (`AlphaTest`).\n"
                "- **INV-A-14** `[T]` a method whose javadoc lacks a witness (`AlphaTest#unwitnessed`).\n"
                "- **INV-A-15** `[T]` every state. REGIONS: empty, full, sliver. (`AlphaTest#sweeps`).\n"
                "- **INV-A-16** `[T]` every state. REGIONS: empty, full. (`AlphaTest#sweepsHalf`).\n",
            "src/test/java/x/AlphaTest.java":
                "/** The class mentions INV-A-13, which pins nothing; cites INV-A-99; label C-1. */\n"
                "class AlphaTest {\n"
                "  /** Pins INV-A-01, INV-A-08 and stands in for INV-A-05.\n"
                "   * red-witnessed: with {@code X#y} at {@code a} replaced by {@code b}, fails: \"q\". */\n"
                "  void pinsIt() {}\n"
                "  /** Pins INV-A-14, never seen red. */\n"
                "  void unwitnessed() {}\n"
                "  /** Pins INV-A-15. Visits INV-A-15 regions: empty, full, sliver\n"
                "   * red-witnessed: with {@code X#y} at {@code a} replaced by {@code b}, fails: \"q\". */\n"
                "  void sweeps() {}\n"
                "  /** Pins INV-A-16. Visits INV-A-16 regions: empty\n"
                "   * red-witnessed: with {@code X#y} at {@code a} replaced by {@code b}, fails: \"q\". */\n"
                "  void sweepsHalf() {}\n"
                "}\n",
        }
        for rel, text in files.items():
            path = os.path.join(tmp, rel)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(text)
        # Commit indices as first_defined would give them: 0 is the publishing commit (exempt), the
        # rest later. INV-A-11 (index 2) cites INV-A-12, defined at index 3 -- younger, so BROKEN.
        ages = {"INV-A-01": 0, "INV-A-02": 0, "INV-A-03": 0, "INV-A-04": 0, "INV-A-05": 0, "INV-A-06": 0,
                "INV-A-07": 0, "MECH-A-01": 0, "INV-A-08": 0, "INV-A-09": 1, "INV-A-10": 1,
                "INV-A-11": 2, "INV-A-12": 3, "INV-A-13": 0, "INV-A-14": 0, "INV-A-15": 0, "INV-A-16": 0}
        state, missing = classify(os.path.join(tmp, "docs", "system"), tmp, ages)
        want = {"INV-A-01": "PINNED", "INV-A-02": "BROKEN", "INV-A-03": "OWED", "INV-A-04": "UNPINNED",
                "INV-A-05": "UNPINNED", "INV-A-06": "BROKEN", "INV-A-07": "RETIRED", "MECH-A-01": "MECH",
                "INV-A-08": "PINNED", "INV-A-09": "PLANNED", "INV-A-10": "OWED", "INV-A-11": "BROKEN",
                "INV-A-12": "OWED", "INV-A-13": "BROKEN", "INV-A-14": "BROKEN", "INV-A-15": "PINNED",
                "INV-A-16": "BROKEN"}
        ok = True
        for anchor, kind in want.items():
            if state.get(anchor, ("?",))[0] != kind:
                print("SELF-TEST FAIL: %s is %s, expected %s" % (anchor, state.get(anchor), kind))
                ok = False
        if [c for _p, c in missing] != ["INV-A-99"]:
            print("SELF-TEST FAIL: missing anchors %s" % missing)
            ok = False
        print("self-test " + ("PASS" if ok else "FAIL"))
        return 0 if ok else 1
    finally:
        shutil.rmtree(tmp)


def main(argv):
    if "--self-test" in argv:
        return self_test()
    if not any(True for _ in docs(SYSROOT)):
        print("FAIL: no docs under %s -- nothing was measured" % SYSROOT)
        return 1
    wanted = []
    if "--list" in argv:
        wanted = [a.upper() for a in argv[argv.index("--list") + 1:] if not a.startswith("--")]
    state, missing = classify(SYSROOT, REPO, first_defined(REPO))
    return report(state, missing, wanted)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
