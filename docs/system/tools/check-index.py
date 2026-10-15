#!/usr/bin/env python
"""Check that 00-index.md is an exhaustive map of the system docs, and that no link is dead.

REACHABLE: a doc is reachable if 00-index.md links to it, or if the 00-overview.md of its own
directory links to it and that overview is itself reachable. A two-level subsystem is entered
through its overview, so the index routes to the overview and the overview to its sub-docs.

DEAD: every relative .md link in EVERY doc under docs/system must resolve to a file.

WHAT THIS DOES NOT SEE: whether a row's prose is true or current (it sees a link, not a
description); a doc that should exist and does not; a link written without a .md extension.

Usage: python docs/system/tools/check-index.py [--self-test]
Exit 0 = complete, 1 = unreachable docs or dead links. ASCII-only output.
"""
import os
import re
import shutil
import sys
import tempfile

LINK_RE = re.compile(r"\]\(([^)#\s]+?\.md)(?:#[^)]*)?\)")
SYSROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))


def links_from(path):
    with open(path, encoding="utf-8", errors="replace") as fh:
        text = fh.read()
    base = os.path.dirname(path)
    return {os.path.normpath(os.path.join(base, rel)) for rel in LINK_RE.findall(text)
            if not rel.startswith(("http://", "https://"))}


def check(sysroot):
    docs = set()
    for dirpath, _dirs, files in os.walk(sysroot):
        for name in files:
            if name.endswith(".md"):
                docs.add(os.path.join(dirpath, name))
    index = os.path.join(sysroot, "00-index.md")
    if index not in docs:
        return None, [], []
    dead = []
    for doc in docs:
        for target in links_from(doc):
            if not os.path.exists(target):
                dead.append((os.path.relpath(doc, sysroot), os.path.relpath(target, sysroot)))
    reachable = {index}
    frontier = [index]
    while frontier:
        cur = frontier.pop()
        for target in links_from(cur):
            if not target.startswith(sysroot + os.sep) or not os.path.exists(target) or target in reachable:
                continue
            reachable.add(target)
            if os.path.basename(target) == "00-overview.md":
                frontier.append(target)
    missing = sorted(os.path.relpath(p, sysroot) for p in docs - reachable)
    return len(docs), missing, sorted(set(dead))


def self_test():
    tmp = tempfile.mkdtemp()
    try:
        os.makedirs(os.path.join(tmp, "sub"))
        files = {
            "00-index.md": "[a](./a.md) [sub](./sub/00-overview.md) [gone](./gone.md)",
            "a.md": "leaf",
            "orphan.md": "nobody links here",
            "sub/00-overview.md": "[leaf](./leaf.md)",
            "sub/leaf.md": "leaf",
        }
        for rel, text in files.items():
            with open(os.path.join(tmp, rel), "w", encoding="utf-8") as fh:
                fh.write(text)
        n, missing, dead = check(tmp)
        ok = (n == 5 and missing == ["orphan.md"]
              and dead == [("00-index.md", "gone.md")])
        print("self-test " + ("PASS" if ok else "FAIL: n=%s missing=%s dead=%s" % (n, missing, dead)))
        return 0 if ok else 1
    finally:
        shutil.rmtree(tmp)


def main(argv):
    if "--self-test" in argv:
        return self_test()
    sysroot = SYSROOT
    if "--docs" in argv:
        sysroot = os.path.abspath(argv[argv.index("--docs") + 1])
    n, missing, dead = check(sysroot)
    if n is None:
        print("FAIL: 00-index.md is missing -- there is no map")
        return 1
    if not missing and not dead:
        print("system-doc index COMPLETE: %d doc(s), all reachable from 00-index.md, no dead link" % n)
        return 0
    if missing:
        print("UNREACHABLE from 00-index.md -- %d doc(s):" % len(missing))
        for rel in missing:
            print("  %s" % rel)
    if dead:
        print("DEAD LINKS -- %d:" % len(dead))
        for src, tgt in dead:
            print("  %s -> %s" % (src, tgt))
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
