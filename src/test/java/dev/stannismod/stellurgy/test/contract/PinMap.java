package dev.stannismod.stellurgy.test.contract;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The map between contract clauses ({@code docs/system}) and the test methods that pin them, judged in
 * both directions.
 *
 * <p>A CLAUSE is an {@code INV-} invariant or a contract clause ({@code HEAT-7}, {@code CON-C14-11}),
 * defined as a list item that opens with its bold anchor; a {@code MECH-} entry is counted, never owed.
 * Each clause is in one state:</p>
 * <ul>
 *   <li>PINNED -- tagged {@code [T]}, names test METHODS ({@code ClassTest#method}, or
 *       {@code …#method} continuing the previous class; a bare class pins nothing), every named class
 *       and method exists, and at least one named method carries {@link Pins} with the anchor AND a
 *       witness, a {@link RedWitness} or a {@link LegacyWitness}. A clause listing {@code REGIONS:} is pinned only when those methods'
 *       {@link Regions} cover every listed region.</li>
 *   <li>UNPINNED -- carries {@code UNPINNED:} with a dated maintainer ruling, or "pinned by
 *       {@code <ANCHOR>}" where that anchor is PINNED and one of its named methods pins this one too.</li>
 *   <li>BROKEN -- the evidence does not hold (a missing test or method, no pin, no witness, an
 *       unvisited or misspelt region, a malformed region list, a reason off the closed list, or a
 *       {@code FOR:} anchor not committed before the clause).</li>
 *   <li>OWED -- none of the above: debt, reported, never a failure. PLANNED -- tagged {@code [PLANNED]}
 *       for a mechanic not built yet. RETIRED, MECH -- counted.</li>
 * </ul>
 *
 * <p>The test side is given as {@link MethodFacts}, read off the compiled classes by reflection
 * ({@link TestBindingsCheck}); nothing here reads a comment.</p>
 *
 * <p>WHAT THIS DOES NOT SEE: whether a test really guards the clause it names (the red witness is that
 * evidence, and replaying it is a tool's job); whether a clause with a quantifier lists its regions at
 * all; the age of a {@code FOR:} that cites a ruling or a design document rather than an anchor; a
 * clause defined other than as a bold list item.</p>
 */
public final class PinMap {

    /** The state of one clause. */
    public enum State { PINNED, UNPINNED, BROKEN, OWED, PLANNED, RETIRED, MECH }

    static final String ANCHOR = "[A-Z][A-Z0-9]*(?:-[A-Z0-9]+)*-[0-9]+[a-z]?";
    /** A constant: a compiled {@link Pattern} is immutable (every matcher holds its own state). */
    private static final Pattern DEF = Pattern.compile("^[ \\t]*-[ \\t]*\\*\\*(" + ANCHOR + ")\\b(.*)$");
    /** A constant: a compiled {@link Pattern} is immutable. */
    private static final Pattern TAGS = Pattern.compile("\\[([^\\]\\n]*)\\]");
    /** A constant: a compiled {@link Pattern} is immutable. */
    private static final Pattern TEST_REF = Pattern.compile("(?:[\\w./]*/)?([A-Z]\\w*Test)(?:#(\\w+))?|…#(\\w+)");
    /** A constant: a compiled {@link Pattern} is immutable. */
    private static final Pattern UNPINNED = Pattern.compile("UNPINNED:\\s*(.*)", Pattern.DOTALL);
    /** A constant: a compiled {@link Pattern} is immutable. */
    private static final Pattern RULING = Pattern.compile("maintainer ruling\\s+\\d{4}-\\d{2}-\\d{2}",
            Pattern.CASE_INSENSITIVE);
    /** A constant: a compiled {@link Pattern} is immutable. */
    private static final Pattern PINNED_BY = Pattern.compile("pinned by\\s+`?(" + ANCHOR + ")`?",
            Pattern.CASE_INSENSITIVE);
    /** A constant: a compiled {@link Pattern} is immutable. */
    private static final Pattern CITE = Pattern.compile("\\b(" + ANCHOR + ")\\b");
    /** A constant: a compiled {@link Pattern} is immutable. */
    private static final Pattern FOR = Pattern.compile("\\bFOR:\\s*(.*)", Pattern.DOTALL);
    /** A constant: a compiled {@link Pattern} is immutable. */
    private static final Pattern RETIRED = Pattern.compile("^\\s*(?:—|-)?\\s*retired\\b");
    /**
     * `REGIONS:` followed by one or more code spans, comma-separated; the list is every span.
     * A constant: a compiled {@link Pattern} is immutable.
     */
    private static final Pattern REGIONS_OPEN = Pattern.compile("\\bREGIONS:");
    /** A constant: a compiled {@link Pattern} is immutable. */
    private static final Pattern REGION_SPAN = Pattern.compile("\\s*`([^`\\n]+)`\\s*(,)?");

    private PinMap() {
    }

    /** One clause as its doc defines it: the text inside the bold, and the rest of the item. */
    public static final class Clause {
        final String anchor;
        final String doc;
        final String inside;
        final String body;

        Clause(String anchor, String doc, String inside, String body) {
            this.anchor = anchor;
            this.doc = doc;
            this.inside = inside;
            this.body = body;
        }
    }

    /** What the compiled tests say about one method. */
    public static final class MethodFacts {
        final Set<String> pins;
        final int witnesses;
        final Map<String, Set<String>> regions;
        final boolean runs;

        /**
         * @param pins      the anchors of its {@link Pins}
         * @param witnesses how many {@link RedWitness} and {@link LegacyWitness} records it carries ({@link NotYetRed}
         *                  is not one)
         * @param regions   per anchor, the regions its {@link Regions} name
         * @param runs      whether a test task RUNS it as a test: a {@code @Test} not {@code @Ignore}d, in a
         *                  concrete class a task includes (or, declared in an abstract class, inherited by one)
         */
        public MethodFacts(Set<String> pins, int witnesses, Map<String, Set<String>> regions, boolean runs) {
            this.pins = pins;
            this.witnesses = witnesses;
            this.regions = regions;
            this.runs = runs;
        }
    }

    /**
     * The test side. A clause names a test by its SIMPLE name, so the corpus answers which compiled classes
     * carry that name — more than one is an ambiguity a clause cannot resolve, and is refused, never merged.
     */
    public interface Tests {
        /** The qualified names of every compiled test class with this simple name. */
        List<String> classesNamed(String simpleName);

        /** The facts of every declared method of that name in the class of that qualified name. */
        List<MethodFacts> methods(String qualifiedName, String method);
    }

    /** The one class a simple name means, or why it means none or several. */
    private static String oneClass(Tests tests, String simpleName, String[] out) {
        List<String> named = tests.classesNamed(simpleName);
        if (named.isEmpty()) {
            return "names " + simpleName + ", which is no compiled test class";
        }
        if (named.size() > 1) {
            return "names " + simpleName + ", which is " + named.size() + " compiled test classes " + named
                    + " -- a clause cannot say which, so neither pins it";
        }
        out[0] = named.get(0);
        return null;
    }

    /** One clause's verdict, and why when it is BROKEN. */
    public static final class Verdict {
        public final State state;
        public final String doc;
        public final String why;

        Verdict(State state, String doc, String why) {
            this.state = state;
            this.doc = doc;
            this.why = why;
        }
    }

    /** Parses every bold list-item definition; the first definition of an anchor wins. */
    public static Map<String, Clause> parse(Map<String, String> docs) {
        Map<String, Clause> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> doc : docs.entrySet()) {
            String[] lines = doc.getValue().split("\n", -1);
            int i = 0;
            while (i < lines.length) {
                Matcher m = DEF.matcher(lines[i]);
                if (!m.matches()) {
                    i++;
                    continue;
                }
                StringBuilder block = new StringBuilder(m.group(2));
                int j = i + 1;
                while (j < lines.length && !lines[j].trim().isEmpty() && !DEF.matcher(lines[j]).matches()
                        && !lines[j].replaceAll("^\\s+", "").startsWith("#")) {
                    block.append('\n').append(lines[j]);
                    j++;
                }
                String text = block.toString();
                int bold = text.indexOf("**");
                String inside = bold < 0 ? text : text.substring(0, bold);
                String body = bold < 0 ? "" : text.substring(bold + 2);
                if (!out.containsKey(m.group(1))) {
                    out.put(m.group(1), new Clause(m.group(1), doc.getKey(), inside, body));
                }
                i = j;
            }
        }
        return out;
    }

    /**
     * The regions a clause lists: empty when it has no {@code REGIONS:}; {@code null} when it has one
     * whose list is not one or more comma-separated code spans ending at the first thing that is not one.
     * A region is a code span so that nothing inside it -- a {@code .}, a comma -- can end the list early.
     */
    static Set<String> regions(String body) {
        Matcher open = REGIONS_OPEN.matcher(body);
        if (!open.find()) {
            return Collections.emptySet();
        }
        Set<String> out = new LinkedHashSet<>();
        Matcher span = REGION_SPAN.matcher(body);
        int at = open.end();
        boolean more = true;
        while (more) {
            span.region(at, body.length());
            if (!span.lookingAt()) {
                break;
            }
            out.add(span.group(1).trim());
            more = span.group(2) != null;
            at = span.end();
        }
        if (out.isEmpty() || more) {
            return null;
        }
        return out;
    }

    private static List<String> tags(Clause c) {
        List<String> out = new ArrayList<>();
        Matcher m = TAGS.matcher(c.inside);
        while (m.find()) {
            out.add(m.group(1));
        }
        m = TAGS.matcher(c.body.substring(0, Math.min(40, c.body.length())));
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /** (class, method-or-null) pairs a clause names, a {@code …#m} continuing the class before it. */
    static List<String[]> testRefs(String text) {
        List<String[]> out = new ArrayList<>();
        String last = null;
        Matcher m = TEST_REF.matcher(text);
        while (m.find()) {
            if (m.group(1) != null) {
                last = m.group(1);
                out.add(new String[]{last, m.group(2)});
            } else if (m.group(3) != null && last != null) {
                out.add(new String[]{last, m.group(3)});
            }
        }
        return out;
    }

    private static List<String> forAnchors(String body, Set<String> known) {
        Matcher m = FOR.matcher(body);
        if (!m.find()) {
            return Collections.emptyList();
        }
        String sentence = m.group(1).split("(?<=[.;])\\s", 2)[0];
        List<String> out = new ArrayList<>();
        Matcher c = CITE.matcher(sentence);
        while (c.find()) {
            if (known.contains(c.group(1))) {
                out.add(c.group(1));
            }
        }
        return out;
    }

    /**
     * Classifies every clause.
     *
     * @param ages per anchor, the index of the commit that first defined it (0 = the commit that published
     *             the docs, whose internal order is not recorded; absent = not committed). Required: a map
     *             of the clause order that could not be read is a failure of the caller, never a skip.
     */
    public static Map<String, Verdict> classify(Map<String, Clause> defs, Tests tests, Map<String, Integer> ages) {
        if (ages == null) {
            throw new IllegalArgumentException("the age of every clause is required; an unread order is a failure");
        }
        Map<String, Verdict> state = new LinkedHashMap<>();
        for (Clause c : defs.values()) {
            state.put(c.anchor, judgeOwn(c, defs, tests, ages));
        }
        for (Clause c : defs.values()) {
            if (state.get(c.anchor) == null) {
                state.put(c.anchor, judgeUnpinned(c, defs, tests, state));
            }
        }
        return state;
    }

    private static Verdict judgeOwn(Clause c, Map<String, Clause> defs, Tests tests, Map<String, Integer> ages) {
        if (c.anchor.startsWith("MECH-")) {
            return new Verdict(State.MECH, c.doc, null);
        }
        if (RETIRED.matcher(c.body).find()) {
            return new Verdict(State.RETIRED, c.doc, null);
        }
        Integer mine = ages.get(c.anchor);
        if (mine == null || mine != 0) {
            List<String> younger = new ArrayList<>();
            for (String a : forAnchors(c.body, defs.keySet())) {
                Integer theirs = ages.get(a);
                if (theirs == null || (mine != null && theirs >= mine)) {
                    younger.add(a);
                }
            }
            if (!younger.isEmpty()) {
                return new Verdict(State.BROKEN, c.doc, "FOR: cites " + String.join(", ", younger)
                        + ", not committed before this clause");
            }
        }
        List<String> tags = tags(c);
        if (tags.contains("PLANNED") && !tags.contains("T")) {
            return new Verdict(State.PLANNED, c.doc, null);
        }
        if (!tags.contains("T")) {
            return null;
        }
        String why = whyNotPinned(c, tests);
        return why == null ? new Verdict(State.PINNED, c.doc, null) : new Verdict(State.BROKEN, c.doc, why);
    }

    private static String whyNotPinned(Clause c, Tests tests) {
        List<String[]> refs = testRefs(c.body);
        if (refs.isEmpty()) {
            return "tagged [T] but names no test";
        }
        boolean evidence = false;
        boolean pinnedButNotRun = false;
        Set<String> visited = new TreeSet<>();
        Set<String> named = new TreeSet<>();
        for (String[] ref : refs) {
            String[] qualified = new String[1];
            String unresolved = oneClass(tests, ref[0], qualified);
            if (unresolved != null) {
                return unresolved;
            }
            if (ref[1] == null) {
                continue;
            }
            List<MethodFacts> methods = tests.methods(qualified[0], ref[1]);
            if (methods.isEmpty()) {
                return "names " + ref[0] + "#" + ref[1] + ", which has no such method";
            }
            for (MethodFacts f : methods) {
                Set<String> regions = f.regions.get(c.anchor);
                if (regions != null) {
                    named.addAll(regions);
                }
                if (f.pins.contains(c.anchor) && f.witnesses > 0) {
                    if (!f.runs) {
                        pinnedButNotRun = true;
                        continue;
                    }
                    evidence = true;
                    if (regions != null) {
                        visited.addAll(regions);
                    }
                }
            }
        }
        if (!evidence) {
            return pinnedButNotRun
                    ? "the named method carries @Pins(\"" + c.anchor + "\") and a witness, but no test task"
                            + " runs it as a test (not a @Test, @Ignore'd, or in a class no task includes)"
                    : "no named method carries @Pins(\"" + c.anchor + "\") together with a witness"
                            + " (@RedWitness or @LegacyWitness)";
        }
        Set<String> wanted = regions(c.body);
        if (wanted == null) {
            return "REGIONS: is not a list of code spans (`a`, `b`, `c`)";
        }
        Set<String> unknown = new TreeSet<>(named);
        unknown.removeAll(wanted);
        if (!unknown.isEmpty()) {
            return "@Regions names regions the clause does not list: " + String.join(", ", unknown);
        }
        Set<String> missing = new TreeSet<>(wanted);
        missing.removeAll(visited);
        if (!missing.isEmpty()) {
            return "regions not visited by its named methods: " + String.join(", ", missing);
        }
        return null;
    }

    private static Verdict judgeUnpinned(Clause c, Map<String, Clause> defs, Tests tests, Map<String, Verdict> state) {
        Matcher m = UNPINNED.matcher(c.body);
        if (!m.find()) {
            return new Verdict(State.OWED, c.doc, null);
        }
        String reason = m.group(1);
        if (RULING.matcher(reason).find()) {
            return new Verdict(State.UNPINNED, c.doc, null);
        }
        Matcher by = PINNED_BY.matcher(reason);
        if (!by.find()) {
            return new Verdict(State.BROKEN, c.doc, "UNPINNED: without a dated ruling or a 'pinned by <ANCHOR>'");
        }
        String other = by.group(1);
        Verdict theirs = state.get(other);
        if (theirs == null || theirs.state != State.PINNED) {
            return new Verdict(State.BROKEN, c.doc, "pinned by " + other + ", which is not a PINNED clause");
        }
        for (String[] ref : testRefs(defs.get(other).body)) {
            String[] qualified = new String[1];
            if (ref[1] == null || oneClass(tests, ref[0], qualified) != null) {
                continue;
            }
            for (MethodFacts f : tests.methods(qualified[0], ref[1])) {
                if (f.runs && f.pins.contains(c.anchor)) {
                    return new Verdict(State.UNPINNED, c.doc, null);
                }
            }
        }
        return new Verdict(State.BROKEN, c.doc, "pinned by " + other + ", whose test does not pin " + c.anchor + " too");
    }
}
