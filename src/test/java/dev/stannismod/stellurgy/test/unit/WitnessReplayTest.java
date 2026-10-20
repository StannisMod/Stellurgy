package dev.stannismod.stellurgy.test.unit;

import dev.stannismod.stellurgy.test.contract.Pins;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import dev.stannismod.stellurgy.test.contract.WitnessReplay;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Which member a red-witness record names, decided by the build's replayer over a source tree: one member or
 * a refusal, never a pick. Each test writes a small tree of its own and asks {@link WitnessReplay#locate}
 * whether a record locates there -- null when its fragment occurs exactly once in the one member it names.
 */
public class WitnessReplayTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private Path tree(String... pathsAndSources) throws IOException {
        Path root = folder.getRoot().toPath();
        for (int i = 0; i < pathsAndSources.length; i += 2) {
            Path file = root.resolve("src/main/java/x").resolve(pathsAndSources[i]);
            Files.createDirectories(file.getParent());
            Files.write(file, pathsAndSources[i + 1].getBytes(StandardCharsets.UTF_8));
        }
        return root;
    }

    /** A constant: a String is immutable -- the synthetic source with overloads the resolver is asked about. */
    private static final String PHYSICS = String.join("\n",
            "package x;",
            "public class Physics {",
            "    public static double step(double v) {",
            "        return step(v, true);",
            "    }",
            "    public static double step(final double v, boolean canThrust) {",
            "        double out = canThrust ? v * 2 : v;",
            "        return out;",
            "    }",
            "    static void each(java.util.List<String> names, int... more) {",
            "        names.clear();",
            "    }",
            "}");

    /** Pins INV-TST-11 (an overloaded name with no parameter list is refused, listing the overloads). */
    @Pins("INV-TST-11")
    @RedWitness(verdict = "refused", member = "MemberAddress#resolve", at = "if (methods.size() > 1) {",
            replacedBy = "if (false) {",
            fails = "a record naming an overloaded method by its bare name was not refused", on = "2026-10-20")
    @RedWitness(verdict = "names each overload", member = "MemberAddress#resolve",
            at = "\" is overloaded: \" + signatures(methods)", replacedBy = "\" is overloaded: \"",
            fails = "the refusal does not name each overload by its parameter types", on = "2026-10-20")
    @RedWitness(verdict = "a method declared once", member = "MemberAddress#resolve",
            at = "return methods.get(0).address(owner);", replacedBy = "return refuse(\"none\");",
            fails = "a method declared once did not locate by its bare name", on = "2026-10-20")
    @Test
    public void anOverloadedNameWithoutParametersIsRefusedNamingEachOverload() throws IOException {
        Path root = tree("Physics.java", PHYSICS);
        // The fragment occurs once, in the FIRST overload: a judge that took the first declaration would locate it.
        String verdict = WitnessReplay.locate(root, "Physics#step", "return step(v, true);");
        assertTrue("a record naming an overloaded method by its bare name was not refused: " + verdict,
                verdict != null && verdict.startsWith("REFUSED"));
        assertTrue("the refusal does not name each overload by its parameter types: " + verdict,
                verdict.contains("step(double)") && verdict.contains("step(double,boolean)"));
        // Positive control on the same judge: a name declared once needs no parameter list.
        assertNull("a method declared once did not locate by its bare name",
                WitnessReplay.locate(root, "Physics#each", "names.clear();"));
    }

    /** Pins INV-TST-12 (a parameter list names exactly the overload it matches, and its fragment is looked for there alone). */
    @Pins("INV-TST-12")
    @RedWitness(verdict = "the named overload", member = "MemberAddress#resolve",
            at = "if (m.params.equals(params)) {", replacedBy = "if (true) {",
            fails = "the record naming step(double,boolean) did not locate in that overload", on = "2026-10-20")
    @RedWitness(verdict = "that overload alone", member = "MemberAddress.Method#address",
            at = "new MemberAddress(bodyOpen + 1, bodyClose, null)", replacedBy = "new MemberAddress(0, bodyClose, null)",
            fails = "a fragment of the OTHER overload located in the one the record names", on = "2026-10-20")
    @RedWitness(verdict = "generic and varargs", member = "MemberAddress#normalisedType",
            at = ".replace(\"...\", \" [] \")", replacedBy = ".replace(\"...\", \" ... \")",
            fails = "a parameter list with a generic type and varargs did not name its overload", on = "2026-10-20")
    @RedWitness(verdict = "no overload matches", member = "MemberAddress#resolve",
            at = "\"; declared: \" + signatures(methods)", replacedBy = "\"\"",
            fails = "a parameter list matching no overload was not refused naming the declared ones", on = "2026-10-20")
    @Test
    public void aParameterListNamesThatOverloadAlone() throws IOException {
        Path root = tree("Physics.java", PHYSICS);
        assertNull("the record naming step(double,boolean) did not locate in that overload",
                WitnessReplay.locate(root, "Physics#step(double,boolean)", "canThrust ? v * 2 : v"));
        String other = WitnessReplay.locate(root, "Physics#step(double,boolean)", "return step(v, true);");
        assertTrue("a fragment of the OTHER overload located in the one the record names: " + other,
                other != null && other.startsWith("MISSING"));
        // Generic arguments are dropped and varargs are written as arrays.
        assertNull("a parameter list with a generic type and varargs did not name its overload",
                WitnessReplay.locate(root, "Physics#each(List,int[])", "names.clear();"));
        String none = WitnessReplay.locate(root, "Physics#step(int)", "return out;");
        assertTrue("a parameter list matching no overload was not refused naming the declared ones: " + none,
                none != null && none.contains("step(double,boolean)"));
    }

    /** A constant: a String is immutable -- the synthetic source with nested types the resolver is asked about. */
    private static final String NETWORK = String.join("\n",
            "package x;",
            "public class Network {",
            "    static final class WorldState {",
            "        void solve() {",
            "            int world = 1;",
            "        }",
            "    }",
            "    static final class ComponentTopology {",
            "        void solve() {",
            "            int topology = 2;",
            "        }",
            "    }",
            "    enum Mode {",
            "        ON {",
            "            boolean active() { return true; }",
            "        },",
            "        OFF {",
            "            boolean active() { return true; }",
            "        };",
            "        boolean active() { return false; }",
            "    }",
            "    void tick() {",
            "        if (solve(1)) {",
            "            int call = 3;",
            "        }",
            "    }",
            "    boolean solve(int n) {",
            "        return n > 0;",
            "    }",
            "}");

    /** Pins INV-TST-13 (a member belongs to the innermost named type around it). */
    @Pins("INV-TST-13")
    @RedWitness(verdict = "by its own name", member = "MemberAddress.Source#methods",
            at = "m.region(owner.open + 1, owner.close);", replacedBy = "m.region(owner.open + 1, owner.open + 1);",
            fails = "a nested type's method did not locate by the nested type's own name", on = "2026-10-20")
    @RedWitness(verdict = "not the first of its name", member = "MemberAddress.Method#address",
            at = "new MemberAddress(bodyOpen + 1, bodyClose, null)", replacedBy = "new MemberAddress(0, bodyClose, null)",
            fails = "the first same-named method in the file, in another nested type, was taken", on = "2026-10-20")
    @RedWitness(verdict = "through an Outer.Inner chain", member = "MemberAddress.Decl#chainEndsWith",
            at = "d = d.parent;", replacedBy = "",
            fails = "a nested type's method did not locate through an Outer.Inner chain", on = "2026-10-20")
    @RedWitness(verdict = "the outer type's own", member = "MemberAddress.Source#memberLevelOf",
            at = "return t == owner || (t != null && t.constant && t.parent == owner);", replacedBy = "return true;",
            fails = "the outer type's own solve(int) did not locate", on = "2026-10-20")
    @RedWitness(verdict = "through its constant", member = "MemberAddress.Source#Source",
            at = "constants.add(c);", replacedBy = "",
            fails = "an enum constant's method did not locate through its constant", on = "2026-10-20")
    @RedWitness(verdict = "a bare Enum#m", member = "MemberAddress.Source#memberLevelOf",
            at = "|| (t != null && t.constant && t.parent == owner)", replacedBy = "",
            fails = "a bare Enum#m was taken while its constants declare m too", on = "2026-10-20")
    @Test
    public void aMemberBelongsToTheInnermostNamedTypeAroundIt() throws IOException {
        Path root = tree("Network.java", NETWORK);
        assertNull("a nested type's method did not locate by the nested type's own name",
                WitnessReplay.locate(root, "ComponentTopology#solve", "int topology = 2;"));
        String first = WitnessReplay.locate(root, "ComponentTopology#solve", "int world = 1;");
        assertTrue("the first same-named method in the file, in another nested type, was taken: " + first,
                first != null && first.startsWith("MISSING"));
        assertNull("a nested type's method did not locate through an Outer.Inner chain",
                WitnessReplay.locate(root, "Network.WorldState#solve", "int world = 1;"));
        // The outer type declares solve(int) once; its nested types' solve() are not its members, and a call
        // `if (solve(1)) {` in tick() is no declaration.
        assertNull("the outer type's own solve(int) did not locate",
                WitnessReplay.locate(root, "Network#solve", "return n > 0;"));
        assertNull("an enum constant's method did not locate through its constant",
                WitnessReplay.locate(root, "Network.Mode.OFF#active", "return true;"));
        String bare = WitnessReplay.locate(root, "Mode#active", "return false;");
        assertTrue("a bare Enum#m was taken while its constants declare m too: " + bare,
                bare != null && bare.startsWith("REFUSED"));
    }

    private static String packet(String name) {
        return String.join("\n",
                "package x;",
                "public class " + name + " {",
                "    public static class Handler {",
                "        private static void apply(int v) {",
                "            int from" + name + " = v;",
                "        }",
                "    }",
                "}");
    }

    /** Pins INV-TST-13 (a nested type name declared in more than one file is refused, naming the files). */
    @Pins("INV-TST-13")
    @RedWitness(verdict = "refused", member = "WitnessReplay#typeFile", at = "if (pick.size() == 1) {",
            replacedBy = "if (!pick.isEmpty()) {",
            fails = "a nested type name declared in two files was not refused", on = "2026-10-20")
    @RedWitness(verdict = "names both files", member = "WitnessReplay#typeFile",
            at = "root.relativize(p).toString().replace('\\\\', '/')", replacedBy = "\"?\"",
            fails = "the refusal does not name both files", on = "2026-10-20")
    @RedWitness(verdict = "through its outer type", member = "WitnessReplay#typeFile",
            at = "String outer = type.split(\"\\\\.\")[0];", replacedBy = "String outer = type;",
            fails = "the nested type did not locate through its outer type", on = "2026-10-20")
    @Test
    public void aNestedTypeNameDeclaredInTwoFilesIsRefusedNamingThem() throws IOException {
        Path root = tree("PacketA.java", packet("PacketA"), "PacketB.java", packet("PacketB"));
        String verdict = WitnessReplay.locate(root, "Handler#apply", "int fromPacketA = v;");
        assertTrue("a nested type name declared in two files was not refused: " + verdict,
                verdict != null && verdict.startsWith("REFUSED"));
        assertTrue("the refusal does not name both files: " + verdict,
                verdict.contains("PacketA.java") && verdict.contains("PacketB.java"));
        assertNull("the nested type did not locate through its outer type",
                WitnessReplay.locate(root, "PacketB.Handler#apply", "int fromPacketB = v;"));
    }

    /** A constant: a String is immutable -- the synthetic source with a field the resolver is asked about. */
    private static final String RING = String.join("\n",
            "package x;",
            "public class Ring {",
            "    public static final long BASE = 512L;",
            "    /** Twice the base; a comment with a ; in it. */",
            "    public static final long RING = BASE * 2L;",
            "    static long around(long shell) {",
            "        long widest = RING;",
            "        long LOCAL = shell * 2L;",
            "        return Math.max(widest, LOCAL);",
            "    }",
            "}");

    /** Pins INV-TST-14 (a field is its declaration from its name through its `;`, and only a field of the class body). */
    @Pins("INV-TST-14")
    @RedWitness(verdict = "the field's declaration", member = "MemberAddress#resolve",
            at = "return new MemberAddress(fields.get(0)[0], fields.get(0)[1], null);",
            replacedBy = "return refuse(\"no field\");",
            fails = "a field's initializer did not locate in the field", on = "2026-10-20")
    @RedWitness(verdict = "through its semicolon", member = "MemberAddress.Source#fields",
            at = "int end = statementEnd(at);", replacedBy = "int end = owner.close;",
            fails = "a fragment of a method that reads the field located in the field", on = "2026-10-20")
    @RedWitness(verdict = "not a local", member = "MemberAddress.Source#fields",
            at = "if (!memberLevelOf(owner, at) || inConstants(at) || insideParens(at)) {",
            replacedBy = "if (inConstants(at) || insideParens(at)) {",
            fails = "a method's local variable was taken for a field", on = "2026-10-20")
    @Test
    public void aFieldIsItsDeclarationThroughItsSemicolon() throws IOException {
        Path root = tree("Ring.java", RING);
        assertNull("a field's initializer did not locate in the field",
                WitnessReplay.locate(root, "Ring#RING", "BASE * 2L"));
        String outside = WitnessReplay.locate(root, "Ring#RING", "long widest = RING;");
        assertTrue("a fragment of a method that reads the field located in the field: " + outside,
                outside != null && outside.startsWith("MISSING"));
        String local = WitnessReplay.locate(root, "Ring#LOCAL", "shell * 2L");
        assertTrue("a method's local variable was taken for a field: " + local,
                local != null && local.startsWith("MISSING"));
    }
}
