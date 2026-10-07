#!/usr/bin/env python
"""Compute the system docs' coverage of the LIVE source tree: P1 files, P2 seams, P3 invariants.

Nothing here is stored: every figure is computed from the source and the docs at run time, so the
report cannot go stale the way a committed inventory does.

P1 FILE COVERAGE. Every .java file under the compiled roots has exactly one owning doc. A doc
declares what it owns in its YAML frontmatter (a two-level subsystem: in its 00-overview.md only):

    owns: [api/, entity/, tile/TileGuidanceComputer.java, !api/FreeFlight*]

A list of path globs relative to src/main/java/dev/stannismod/stellurgy/; a glob
for the vendored root is prefixed `valkyrienskies:` and is relative to valkyrienskies/src/main/java/.
`*` matches within one path segment, a trailing `/` means the whole directory, `!` excludes. The MOST
SPECIFIC matching glob decides the owner (the longest literal prefix before any `*`); an exclusion
removes a file from that doc only. Reported: files with no owner, and files claimed by two docs at
equal specificity.

P2 SEAM COVERAGE. Every seam identifier below appears, verbatim, somewhere in 30-contracts/:
  packets   classes declared `implements IMessage` or `extends BasePacket`
  config    public instance fields of api/StellurgyConfiguration.java
  mixins    class names listed in src/main/resources/mixins.*.json
  nbt       string literals passed to an NBT accessor (setInteger("key", ...), getTagList("key", ...),
            hasKey("key"), ...)
  registry  string literals passed to setRegistryName(...) -- LISTED, never a failure: a registry id
            has no meaning of its own beyond the naming rules C3 states, so C3 keeps the rules and
            this checker keeps the list (a hand-kept table of ids is an inventory that decays).

P3 INVARIANT TAGS. Every `**INV-...**` definition carries a confidence tag ([V], [T] or [A]); at most
10 % of them are [A] (assumed); a doc that defines a MECH- anchor defines at least one INV- anchor.

WHAT THIS DOES NOT SEE: whether a doc's prose is TRUE; seams of kinds not listed above (Forge events,
capabilities, recipes, lang keys); an NBT key built at run time rather than written as a literal; a
contract that mentions a key only to say it is unused.

Usage: python docs/system/tools/check-coverage.py [--list] [--only P1|P2|P3] [--self-test]
Exit 0 = every check clean, 1 = something uncovered. ASCII-only output.
"""
import fnmatch
import json
import os
import re
import shutil
import sys
import tempfile

SYSROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
REPO = os.path.normpath(os.path.join(SYSROOT, "..", ".."))

FRONTMATTER_RE = re.compile(r"\A---[ \t]*\r?\n(.*?)\r?\n---[ \t]*(?:\r?\n|\Z)", re.DOTALL)
OWNS_RE = re.compile(r"^owns:[ \t]*\[(.*)\][ \t]*$", re.MULTILINE)


def owns_globs(text):
    """The `owns:` list of a doc's frontmatter, or None when it declares none."""
    fm = FRONTMATTER_RE.match(text)
    if not fm:
        return None
    m = OWNS_RE.search(fm.group(1))
    if not m:
        return None
    return [g.strip().strip("`'\"") for g in m.group(1).split(",") if g.strip()]
NBT_RE = re.compile(r"\b(?:set|get|has|remove)(?:Integer|Int|Boolean|String|Double|Float|Long|Short|Byte|"
                    r"Tag|CompoundTag|IntArray|ByteArray|UniqueId|TagList|Key)\s*\(\s*\"([^\"]+)\"")
PACKET_RE = re.compile(r"\bclass\s+(\w+)[^{]*\b(?:implements\s+[\w.,\s]*\bIMessage\b|extends\s+BasePacket\b)")
CONFIG_FIELD_RE = re.compile(r"^\s*public\s+(?!static\b)(?!final\b)[\w<>\[\],.\s]+?\s+(\w+)\s*(?:=[^;]*)?;",
                             re.MULTILINE)
REGISTRY_RE = re.compile(r"setRegistryName\s*\(([^)]*)\)")
STRING_RE = re.compile(r"\"([^\"]+)\"")
# A definition is `- **INV-X** `[T]` …` or `- **INV-X [T]** …`: the tag sits right after the anchor,
# inside or outside the bold. A [V] further along the line is prose, not the clause's tag.
# Anything between the anchor and the closing bold is read, so a malformed tag (`[T/A]`) cannot make
# the whole definition invisible to P3 -- it is reported instead.
INV_DEF_RE = re.compile(r"^[ \t]*-[ \t]*\*\*(INV-[\w-]+)([^*\n]*)\*\*(.*)$", re.MULTILINE)
MECH_DEF_RE = re.compile(r"^[ \t]*-[ \t]*\*\*(MECH-[\w-]+)", re.MULTILINE)
LEADING_TAGS_RE = re.compile(r"^[ \t]*`?((?:\[[^\]\n]*\])+)`?")
BRACKET_RE = re.compile(r"\[([^\]\n]*)\]")
LISTED_ONLY = {"registry"}
CONFIDENCE = {"V", "T", "A"}
LEVELS = {"SYS", "BEH"}


def roots(repo):
    return [("", os.path.join(repo, "src", "main", "java", "dev", "stannismod", "stellurgy")),
            ("valkyrienskies:", os.path.join(repo, "valkyrienskies", "src", "main", "java"))]


def java_files(repo):
    out = []
    for prefix, base in roots(repo):
        for d, _dirs, fs in os.walk(base):
            for f in fs:
                if f.endswith(".java"):
                    rel = os.path.relpath(os.path.join(d, f), base).replace(os.sep, "/")
                    out.append(prefix + rel)
    return out


def docs(sysroot):
    for d, _dirs, fs in os.walk(sysroot):
        for f in fs:
            if f.endswith(".md"):
                yield os.path.join(d, f)


def read(path):
    with open(path, encoding="utf-8", errors="replace") as fh:
        return fh.read()


# ---- P1 -----------------------------------------------------------------------------------------

def glob_matches(glob, path):
    if glob.endswith("/"):
        return path.startswith(glob)
    return fnmatch.fnmatchcase(path, glob) and path.count("/") == glob.count("/")


def specificity(glob):
    literal = glob.split("*", 1)[0]
    return len(literal)


def p1(repo, sysroot):
    claims = []  # (doc, include globs, exclude globs)
    for doc in docs(sysroot):
        globs = owns_globs(read(doc))
        if not globs:
            continue
        inc = [g for g in globs if not g.startswith("!")]
        exc = [g[1:] for g in globs if g.startswith("!")]
        claims.append((os.path.relpath(doc, sysroot), inc, exc))
    orphans, ties = [], []
    for path in java_files(repo):
        best, owners = -1, []
        for doc, inc, exc in claims:
            if any(glob_matches(g, path) for g in exc):
                continue
            score = max([specificity(g) for g in inc if glob_matches(g, path)] or [-1])
            if score < 0:
                continue
            if score > best:
                best, owners = score, [doc]
            elif score == best:
                owners.append(doc)
        if not owners:
            orphans.append(path)
        elif len(owners) > 1:
            ties.append((path, owners))
    return claims, orphans, ties


# ---- P2 -----------------------------------------------------------------------------------------

def seams(repo):
    found = {"packets": set(), "config": set(), "mixins": set(), "nbt": set(), "registry": set()}
    for prefix, base in roots(repo):
        for d, _dirs, fs in os.walk(base):
            for f in fs:
                if not f.endswith(".java"):
                    continue
                text = read(os.path.join(d, f))
                found["packets"].update(PACKET_RE.findall(text))
                found["nbt"].update(NBT_RE.findall(text))
                for args in REGISTRY_RE.findall(text):
                    lits = STRING_RE.findall(args)
                    if lits:
                        found["registry"].add(lits[-1])
    config = os.path.join(roots(repo)[0][1], "api", "StellurgyConfiguration.java")
    if os.path.isfile(config):
        found["config"].update(CONFIG_FIELD_RE.findall(read(config)))
    res = os.path.join(repo, "src", "main", "resources")
    if os.path.isdir(res):
        for f in os.listdir(res):
            if f.startswith("mixins.") and f.endswith(".json"):
                data = json.loads(read(os.path.join(res, f)))
                for key in ("mixins", "client", "server"):
                    for name in data.get(key, []) or []:
                        found["mixins"].add(name.split(".")[-1])
    return found


def p2(repo, sysroot):
    corpus = "\n".join(read(p) for p in docs(os.path.join(sysroot, "30-contracts")))
    found = seams(repo)
    missing = {kind: sorted(k for k in keys if k not in corpus) for kind, keys in found.items()}
    return found, missing


# ---- P3 -----------------------------------------------------------------------------------------

def p3(sysroot):
    total, assumed, untagged, mech_without_inv = 0, 0, [], []
    for doc in docs(sysroot):
        text = read(doc)
        invs = INV_DEF_RE.findall(text)
        for anchor, inside, rest in invs:
            if re.match(r"^[ \t]*(?:—|-)?[ \t]*retired\b", rest):
                continue
            total += 1
            lead = LEADING_TAGS_RE.match(rest)
            tokens = BRACKET_RE.findall(inside) + BRACKET_RE.findall(lead.group(1) if lead else "")
            bad = [t for t in tokens if t not in CONFIDENCE | LEVELS]
            tags = {t for t in tokens if t in CONFIDENCE}
            if bad:
                untagged.append("%s %s (malformed tag %s)" % (os.path.relpath(doc, sysroot), anchor, bad))
            elif not tags:
                untagged.append("%s %s" % (os.path.relpath(doc, sysroot), anchor))
            elif tags == {"A"}:
                assumed += 1
        if MECH_DEF_RE.search(text) and not invs:
            mech_without_inv.append(os.path.relpath(doc, sysroot))
    return total, assumed, untagged, mech_without_inv


# ---- report -------------------------------------------------------------------------------------

def report(repo, sysroot, listing, only):
    if not any(True for _ in docs(sysroot)):
        # An empty doc tree would otherwise report zero of everything, which reads as clean.
        print("FAIL: no docs under %s -- nothing was measured" % sysroot)
        return 1
    bad = False
    if only in (None, "P1"):
        claims, orphans, ties = p1(repo, sysroot)
        n = len(java_files(repo))
        print("P1 files: %d .java, %d docs declaring owns:, %d without owner, %d contested"
              % (n, len(claims), len(orphans), len(ties)))
        if listing:
            for o in orphans:
                print("  ORPHAN %s" % o)
            for path, owners in ties:
                print("  CONTESTED %s <- %s" % (path, ", ".join(owners)))
        bad = bad or bool(orphans or ties)
    if only in (None, "P2"):
        found, missing = p2(repo, sysroot)
        for kind in sorted(found):
            if kind in LISTED_ONLY:
                print("P2 %-8s %4d found (listed, governed by its contract's rules)" % (kind, len(found[kind])))
                if listing:
                    for k in sorted(found[kind]):
                        print("  LISTED %s %s" % (kind, k))
                continue
            print("P2 %-8s %4d found, %4d not named in 30-contracts" % (kind, len(found[kind]), len(missing[kind])))
            if listing:
                for k in missing[kind]:
                    print("  UNNAMED %s %s" % (kind, k))
            bad = bad or bool(missing[kind])
    if only in (None, "P3"):
        total, assumed, untagged, mech = p3(sysroot)
        share = (100.0 * assumed / total) if total else 0.0
        print("P3 invariants: %d, untagged %d, assumed %d (%.1f%%, bound 10%%), docs with MECH but no INV %d"
              % (total, len(untagged), assumed, share, len(mech)))
        if listing:
            for u in untagged:
                print("  UNTAGGED %s" % u)
            for d in mech:
                print("  NO-INV %s" % d)
        bad = bad or bool(untagged or mech) or share > 10.0
    return 1 if bad else 0


def self_test():
    tmp = tempfile.mkdtemp()
    try:
        base = os.path.join(tmp, "src", "main", "java", "dev", "stannismod", "stellurgy")
        files = {
            os.path.join(base, "a", "Owned.java"): 'class Owned { void w(N t){ t.setInteger("covered", 1); t.setInteger("bare", 2);} }',
            os.path.join(base, "a", "Special.java"): "class Special {}",
            os.path.join(base, "b", "Orphan.java"): "class Orphan {}",
            os.path.join(base, "net", "PacketX.java"): "public class PacketX implements IMessage {}",
            os.path.join(tmp, "docs", "system", "20-subsystems", "one.md"):
                "---\nid: one\nowns: [a/, net/]\n---\n# One\n\nOwns: `b/` (prose, never read)\n"
                "- **MECH-ONE-01** x\n- **INV-ONE-01** `[T]` y\n",
            os.path.join(tmp, "docs", "system", "20-subsystems", "two.md"):
                "---\nowns: [a/Special.java]\n---\n- **INV-TWO-01** z, a [V] in prose is no tag\n"
                "- **INV-TWO-02 [A]** assumed, tag inside the bold\n"
                "- **INV-TWO-03 [T/A]** a malformed tag is reported, not skipped\n"
                "- **INV-TWO-04** `[T][SYS]` a level beside the confidence is fine\n",
            os.path.join(tmp, "docs", "system", "30-contracts", "c.md"): "keys: `covered`, `PacketX`\n",
        }
        for path, text in files.items():
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(text)
        sysroot = os.path.join(tmp, "docs", "system")
        _, orphans, ties = p1(tmp, sysroot)
        _, missing = p2(tmp, sysroot)
        total, assumed, untagged, mech = p3(sysroot)
        checks = [
            ("orphan found", orphans == ["b/Orphan.java"]),
            ("most specific wins, no tie", ties == []),
            ("unnamed nbt key found", missing["nbt"] == ["bare"]),
            ("named packet not reported", missing["packets"] == []),
            ("untagged and malformed INV found",
             untagged == [os.path.join("20-subsystems", "two.md") + " INV-TWO-01",
                          os.path.join("20-subsystems", "two.md") + " INV-TWO-03 (malformed tag ['T/A'])"]),
            ("both tag positions read", total == 5 and assumed == 1 and mech == []),
        ]
        ok = all(c for _, c in checks)
        for name, c in checks:
            if not c:
                print("SELF-TEST FAIL: %s" % name)
        print("self-test " + ("PASS" if ok else "FAIL"))
        return 0 if ok else 1
    finally:
        shutil.rmtree(tmp)


def main(argv):
    if "--self-test" in argv:
        return self_test()
    only = None
    if "--only" in argv:
        only = argv[argv.index("--only") + 1]
    sysroot = SYSROOT
    if "--docs" in argv:
        sysroot = os.path.abspath(argv[argv.index("--docs") + 1])
    return report(REPO, sysroot, "--list" in argv, only)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
