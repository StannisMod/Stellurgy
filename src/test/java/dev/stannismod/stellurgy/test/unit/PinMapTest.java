package dev.stannismod.stellurgy.test.unit;

import dev.stannismod.stellurgy.test.contract.PinMap;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;

/**
 * How the build judges a contract clause from what the compiled tests carry: a clause is pinned only
 * through a method it NAMES that itself carries the anchor and a red witness, its listed regions are a
 * closed list nothing inside a region can cut short, and the order of the clauses is required, never
 * skipped. Each scenario is one of the forms an adversarial reading found the javadoc checker letting
 * through (a neighbour's anchor, a "not yet red" record, a region list cut at its first full stop, an
 * age check dropped when git could not answer), stated as facts about methods -- which is all
 * {@link PinMap} is ever given.
 */
public class PinMapTest {

    private static final String WITNESSED = "AlphaTest";
    /** A constant: {@code Collections.emptyMap()} refuses every write. */
    private static final Map<String, Set<String>> NO_REGIONS = Collections.emptyMap();

    /** A method a test task runs as a test. */
    private static PinMap.MethodFacts method(Set<String> pins, int witnesses, Map<String, Set<String>> regions) {
        return new PinMap.MethodFacts(pins, witnesses, regions, true);
    }

    /** A method no test task runs as a test — not a {@code @Test}, ignored, or in a class no task includes. */
    private static PinMap.MethodFacts notRun(Set<String> pins, int witnesses) {
        return new PinMap.MethodFacts(pins, witnesses, NO_REGIONS, false);
    }

    private static Set<String> set(String... values) {
        return new LinkedHashSet<>(Arrays.asList(values));
    }

    private static Map<String, Set<String>> visits(String anchor, String... regions) {
        Map<String, Set<String>> out = new HashMap<>();
        out.put(anchor, set(regions));
        return out;
    }

    /** A corpus of one class, {@link #WITNESSED} in {@link #PACKAGE}, with the given methods. */
    private static PinMap.Tests corpus(Map<String, PinMap.MethodFacts> methods) {
        return corpus(methods, Collections.singletonList(PACKAGE + WITNESSED));
    }

    /** The same methods, answered for every one of {@code classes} — which all carry the simple name. */
    private static PinMap.Tests corpus(Map<String, PinMap.MethodFacts> methods, List<String> classes) {
        return new PinMap.Tests() {
            @Override
            public List<String> classesNamed(String simpleName) {
                return WITNESSED.equals(simpleName) ? classes : Collections.<String>emptyList();
            }

            @Override
            public List<PinMap.MethodFacts> methods(String qualifiedName, String name) {
                PinMap.MethodFacts f = classes.contains(qualifiedName) ? methods.get(name) : null;
                return f == null ? Collections.<PinMap.MethodFacts>emptyList() : Collections.singletonList(f);
            }
        };
    }

    private static final String PACKAGE = "dev.stannismod.stellurgy.test.unit.";

    /** Every clause of one doc, judged against the corpus, each defined in the publishing commit. */
    private static Map<String, PinMap.Verdict> judge(String doc, Map<String, PinMap.MethodFacts> methods) {
        Map<String, String> docs = new LinkedHashMap<>();
        docs.put("a.md", doc);
        Map<String, PinMap.Clause> defs = PinMap.parse(docs);
        Map<String, Integer> ages = new HashMap<>();
        for (String anchor : defs.keySet()) {
            ages.put(anchor, 0);
        }
        return PinMap.classify(defs, corpus(methods), ages);
    }

    private static PinMap.Verdict assertState(Map<String, PinMap.Verdict> state, String anchor, PinMap.State expected) {
        PinMap.Verdict v = state.get(anchor);
        assertTrue(anchor + " was not judged at all", v != null);
        assertEquals(anchor + " is " + v.state + (v.why == null ? "" : " (" + v.why + ")"), expected, v.state);
        return v;
    }

    @Test
    @RedWitness(member = "PinMap#whyNotPinned", at = "f.pins.contains(c.anchor) && f.witnesses > 0",
            replacedBy = "false", fails = "INV-A-01 is BROKEN", on = "2026-10-08")
    public void aClauseIsPinnedByTheNamedMethodThatCarriesItsAnchorAndAWitness() {
        Map<String, PinMap.MethodFacts> methods = new HashMap<>();
        methods.put("pinsIt", method(set("INV-A-01"), 1, NO_REGIONS));
        assertState(judge("- **INV-A-01** `[T]` pinned (`test/unit/AlphaTest#pinsIt`).\n", methods),
                "INV-A-01", PinMap.State.PINNED);
    }

    @Test
    @RedWitness(member = "PinMap#whyNotPinned", at = "f.pins.contains(c.anchor) && f.witnesses > 0",
            replacedBy = "f.witnesses > 0", fails = "INV-A-02 is PINNED", on = "2026-10-08")
    public void aNeighboursAnchorDoesNotPinTheMethodTheClauseNames() {
        Map<String, PinMap.MethodFacts> methods = new HashMap<>();
        // The neighbour carries the anchor and a witness; the named method was witnessed for something else.
        methods.put("neighbour", method(set("INV-A-02"), 1, NO_REGIONS));
        methods.put("named", method(set("INV-A-99"), 1, NO_REGIONS));
        assertState(judge("- **INV-A-02** `[T]` names the method beside the pin (`AlphaTest#named`).\n", methods),
                "INV-A-02", PinMap.State.BROKEN);
    }

    @Test
    @RedWitness(member = "PinMap#whyNotPinned", at = "f.pins.contains(c.anchor) && f.witnesses > 0",
            replacedBy = "f.pins.contains(c.anchor)", fails = "INV-A-05 is PINNED", on = "2026-10-08")
    public void aMethodWithAnAnchorAndNoRedWitnessPinsNothing() {
        Map<String, PinMap.MethodFacts> methods = new HashMap<>();
        // Its only record was an attempt that stayed green: no witness at all.
        methods.put("triedButGreen", method(set("INV-A-05"), 0, NO_REGIONS));
        assertState(judge("- **INV-A-05** `[T]` (`AlphaTest#triedButGreen`).\n", methods),
                "INV-A-05", PinMap.State.BROKEN);
    }

    @Test
    @RedWitness(member = "PinMap#oneClass", at = "return \"names \" + simpleName + \", which is no compiled test class\";",
            replacedBy = "return \"names a class that is missing\";",
            fails = "the refusal must name the class that is not there", on = "2026-10-09")
    public void aClauseNamingAClassNoTestDeclaresIsRefusedByThatName() {
        PinMap.Verdict v = assertState(judge("- **INV-A-04** `[T]` names a ghost (`GhostTest#x`).\n",
                new HashMap<>()), "INV-A-04", PinMap.State.BROKEN);
        assertTrue("the refusal must name the class that is not there: " + v.why,
                v.why.contains("GhostTest, which is no compiled test class"));
    }

    @Test
    @RedWitness(verdict = "the half-visited clause is refused", member = "PinMap#whyNotPinned",
            at = "missing.removeAll(visited);", replacedBy = "missing.clear();", fails = "INV-A-06 is PINNED",
            on = "2026-10-08")
    @RedWitness(verdict = "the refusal names the region whole", member = "PinMap#regions",
            at = "out.add(span.group(1).trim());", replacedBy = "out.add(span.group(1).split(\"\\\\.\")[0].trim());",
            fails = "the unvisited region must be named whole", on = "2026-10-08")
    public void aListedRegionWithAFullStopInsideItIsOwedLikeAnyOther() {
        Map<String, PinMap.MethodFacts> methods = new HashMap<>();
        methods.put("half", method(set("INV-A-06"), 1, visits("INV-A-06", "empty")));
        PinMap.Verdict half = assertState(judge(
                "- **INV-A-06** `[T]` every fill. REGIONS: `0.5 full`, `empty`. (`AlphaTest#half`).\n", methods),
                "INV-A-06", PinMap.State.BROKEN);
        assertTrue("the unvisited region must be named whole: " + half.why,
                half.why.endsWith("not visited by its named methods: 0.5 full"));
    }

    @Test
    @RedWitness(member = "PinMap#whyNotPinned", at = "visited.addAll(regions);", replacedBy = "",
            fails = "INV-A-07 is BROKEN", on = "2026-10-08")
    public void aClauseWhoseEveryRegionIsVisitedIsPinned() {
        Map<String, PinMap.MethodFacts> methods = new HashMap<>();
        methods.put("whole", method(set("INV-A-07"), 1, visits("INV-A-07", "0.5 full", "empty")));
        assertState(judge("- **INV-A-07** `[T]` every fill. REGIONS: `0.5 full`, `empty`. (`AlphaTest#whole`).\n",
                methods), "INV-A-07", PinMap.State.PINNED);
    }

    @Test
    @RedWitness(member = "PinMap#regions", at = "return null;", replacedBy = "return out;",
            fails = "the refusal must name the form", on = "2026-10-08")
    public void aRegionListThatIsNotCodeSpansIsRefusedRatherThanReadPartly() {
        Map<String, PinMap.MethodFacts> methods = new HashMap<>();
        methods.put("half", method(set("INV-A-08"), 1, visits("INV-A-08", "0")));
        PinMap.Verdict v = assertState(judge(
                "- **INV-A-08** `[T]` every fill. REGIONS: 0.5 full, empty. (`AlphaTest#half`).\n", methods),
                "INV-A-08", PinMap.State.BROKEN);
        assertTrue("the refusal must name the form: " + v.why, v.why.contains("code spans"));
    }

    @Test
    @RedWitness(member = "PinMap#whyNotPinned", at = "unknown.removeAll(wanted);", replacedBy = "unknown.clear();",
            fails = "the refusal must name the stray region", on = "2026-10-08")
    public void aVisitedRegionTheClauseDoesNotListIsRefused() {
        Map<String, PinMap.MethodFacts> methods = new HashMap<>();
        methods.put("typo", method(set("INV-A-09"), 1, visits("INV-A-09", "empty", "ful")));
        PinMap.Verdict v = assertState(judge(
                "- **INV-A-09** `[T]` every fill. REGIONS: `empty`, `full`. (`AlphaTest#typo`).\n", methods),
                "INV-A-09", PinMap.State.BROKEN);
        assertTrue("the refusal must name the stray region: " + v.why, v.why.endsWith("does not list: ful"));
    }

    @Test
    @RedWitness(verdict = "a clause serving a younger one", member = "PinMap#judgeOwn",
            at = "if (theirs == null || (mine != null && theirs >= mine)) {", replacedBy = "if (theirs == null) {",
            fails = "INV-A-11 is OWED", on = "2026-10-08")
    @RedWitness(verdict = "an unread order", member = "PinMap#classify", at = "throw new IllegalArgumentException(",
            replacedBy = "ages = java.util.Collections.emptyMap(); new IllegalArgumentException(",
            fails = "a clause order that could not be read was skipped instead of refused", on = "2026-10-08")
    public void aClauseServingAYoungerOneIsRefusedAndAnUnreadOrderIsNeverSkipped() {
        Map<String, String> docs = new LinkedHashMap<>();
        docs.put("a.md", "- **INV-A-10** `[A][SYS]` serves an older clause. FOR: INV-A-12.\n"
                + "- **INV-A-11** `[A][SYS]` serves a younger clause. FOR: INV-A-13.\n"
                + "- **INV-A-12** `[A][BEH]` written first.\n"
                + "- **INV-A-13** `[A][BEH]` written after INV-A-11.\n");
        Map<String, PinMap.Clause> defs = PinMap.parse(docs);
        Map<String, Integer> ages = new HashMap<>();
        ages.put("INV-A-12", 1);
        ages.put("INV-A-10", 2);
        ages.put("INV-A-11", 2);
        ages.put("INV-A-13", 3);
        Map<String, PinMap.Verdict> state = PinMap.classify(defs, corpus(new HashMap<>()), ages);
        assertState(state, "INV-A-10", PinMap.State.OWED);
        assertState(state, "INV-A-11", PinMap.State.BROKEN);
        try {
            PinMap.classify(defs, corpus(new HashMap<>()), null);
            fail("a clause order that could not be read was skipped instead of refused");
        } catch (IllegalArgumentException expected) {
            assertTrue("the refusal must say the order is required: " + expected.getMessage(),
                    expected.getMessage().contains("required"));
        }
    }

    @Test
    @RedWitness(verdict = "a pin that names it back", member = "PinMap#judgeUnpinned",
            at = "if (f.runs && f.pins.contains(c.anchor)) {", replacedBy = "if (false) {", fails = "INV-A-21 is BROKEN",
            on = "2026-10-09")
    @RedWitness(verdict = "a dated ruling", member = "PinMap#judgeUnpinned", at = "if (RULING.matcher(reason).find()) {",
            replacedBy = "if (false) {", fails = "INV-A-22 is BROKEN", on = "2026-10-08")
    @RedWitness(verdict = "a pin that does not name it back", member = "PinMap#judgeUnpinned",
            at = "if (f.runs && f.pins.contains(c.anchor)) {", replacedBy = "if (true) {", fails = "INV-A-24 is UNPINNED",
            on = "2026-10-09")
    @RedWitness(verdict = "a reason off the list", member = "PinMap#judgeUnpinned",
            at = "return new Verdict(State.BROKEN, c.doc, \"UNPINNED: without a dated ruling or a 'pinned by <ANCHOR>'\");",
            replacedBy = "return new Verdict(State.UNPINNED, c.doc, null);", fails = "INV-A-25 is UNPINNED",
            on = "2026-10-08")
    public void anUnpinnedClauseNeedsADatedRulingOrAPinThatNamesItBack() {
        Map<String, PinMap.MethodFacts> methods = new HashMap<>();
        methods.put("pinsBoth", method(set("INV-A-20", "INV-A-21"), 1, NO_REGIONS));
        methods.put("pinsOne", method(set("INV-A-23"), 1, NO_REGIONS));
        Map<String, PinMap.Verdict> state = judge(
                "- **INV-A-20** `[T]` (`AlphaTest#pinsBoth`).\n"
                        + "- **INV-A-21** `[V]` UNPINNED: pinned by INV-A-20 on another tier.\n"
                        + "- **INV-A-22** `[A]` UNPINNED: maintainer ruling 2026-10-07 \"not user-facing\".\n"
                        + "- **INV-A-23** `[T]` (`AlphaTest#pinsOne`).\n"
                        + "- **INV-A-24** `[V]` UNPINNED: pinned by INV-A-23 on another tier.\n"
                        + "- **INV-A-25** `[A]` UNPINNED: no tier can observe it.\n",
                methods);
        assertState(state, "INV-A-21", PinMap.State.UNPINNED);
        assertState(state, "INV-A-22", PinMap.State.UNPINNED);
        assertState(state, "INV-A-24", PinMap.State.BROKEN);
        assertState(state, "INV-A-25", PinMap.State.BROKEN);
    }

    @Test
    @RedWitness(verdict = "a title that wraps", member = "PinMap#parse", at = "!lines[j].trim().isEmpty()",
            replacedBy = "false", fails = "INV-A-30 is OWED", on = "2026-10-08")
    @RedWitness(verdict = "a planned clause", member = "PinMap#judgeOwn",
            at = "if (tags.contains(\"PLANNED\") && !tags.contains(\"T\")) {", replacedBy = "if (false) {",
            fails = "INV-A-31 is OWED", on = "2026-10-08")
    public void aWrappedTitleIsOneClauseAndAPlannedOneOwesNothing() {
        Map<String, PinMap.MethodFacts> methods = new HashMap<>();
        methods.put("pinsIt", method(set("INV-A-30"), 1, NO_REGIONS));
        Map<String, PinMap.Verdict> state = judge(
                "- **INV-A-30 (a title that wraps\n  onto the next line)** `[T]` (`AlphaTest#pinsIt`).\n"
                        + "- **INV-A-31** `[PLANNED][BEH]` a mechanic not built yet.\n",
                methods);
        assertState(state, "INV-A-30", PinMap.State.PINNED);
        assertState(state, "INV-A-31", PinMap.State.PLANNED);
    }

    @Test
    @RedWitness(member = "PinMap#whyNotPinned", at = "if (!f.runs) {", replacedBy = "if (false) {",
            fails = "INV-A-40 is PINNED", on = "2026-10-09")
    public void aPinOnAMethodNoTaskRunsPinsNothingAndSaysWhy() {
        Map<String, PinMap.MethodFacts> methods = new HashMap<>();
        // The anchor and the witness are there; the method is no test a task runs.
        methods.put("helper", notRun(set("INV-A-40"), 1));
        PinMap.Verdict v = assertState(judge("- **INV-A-40** `[T]` (`AlphaTest#helper`).\n", methods),
                "INV-A-40", PinMap.State.BROKEN);
        assertTrue("the refusal must say no task runs it: " + v.why, v.why.contains("no test task runs it"));
    }

    @Test
    @RedWitness(member = "PinMap#oneClass", at = "if (named.size() > 1) {", replacedBy = "if (false) {",
            fails = "INV-A-41 is PINNED", on = "2026-10-09")
    public void aSimpleNameTwoClassesShareIsRefusedRatherThanMerged() {
        Map<String, PinMap.MethodFacts> methods = new HashMap<>();
        methods.put("pinsIt", method(set("INV-A-41"), 1, NO_REGIONS));
        Map<String, String> docs = new LinkedHashMap<>();
        docs.put("a.md", "- **INV-A-41** `[T]` (`AlphaTest#pinsIt`).\n");
        Map<String, PinMap.Clause> defs = PinMap.parse(docs);
        Map<String, Integer> ages = new HashMap<>();
        ages.put("INV-A-41", 0);
        PinMap.Verdict v = assertState(PinMap.classify(defs, corpus(methods, Arrays.asList(
                        PACKAGE + WITNESSED, "dev.stannismod.stellurgy.test.integration." + WITNESSED)), ages),
                "INV-A-41", PinMap.State.BROKEN);
        assertTrue("the refusal must name both classes: " + v.why,
                v.why.contains("2 compiled test classes"));
    }
}
