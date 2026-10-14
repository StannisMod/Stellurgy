package dev.stannismod.stellurgy.test.contract;

import com.github.stannismod.forge.testing.junit.AbstractPlayerE2ETest;
import com.google.gson.GsonBuilder;

import java.io.*;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The build's check of what binds a test to the contract, read off the COMPILED test classes by
 * reflection -- never off their source text. Run by the {@code checkTestBindings} task, which EVERY test
 * task (the fast tiers, the harness tiers, the milestone tier) and {@code check} depend on, so it holds
 * inside {@code gradle build} and every tier run, for every executor and with nothing but the main
 * repository checked out.
 *
 * <p><b>What pins.</b> A method counts toward a PINNED clause only if a test task RUNS it as a test: a
 * public {@code @Test} not {@code @Ignore}d, declared in a concrete public class one of the tasks' includes
 * ({@code --included}) matches and that is not {@code @Ignore}d — or in an abstract class such a class
 * inherits. A clause names a test by its simple name; a simple name two compiled classes share is refused,
 * never merged.</p>
 *
 * <p>It fails the build on:</p>
 * <ul>
 *   <li>a BROKEN clause ({@link PinMap});</li>
 *   <li>a mixin of the test configuration that {@code StellurgyTestMixinPlugin} declares neither an
 *       observation nor a behaviour, or both;</li>
 *   <li>a {@link Pins} or {@link Regions} anchor no doc defines, a {@link Regions} for an anchor its
 *       method does not pin;</li>
 *   <li>a {@link RedWitness} that lacks a part of the replayable form, or whose member is neither
 *       production code nor one of the test system's own judges; a {@link LegacyWitness} or a
 *       {@link NotYetRed} with nothing in it;</li>
 *   <li>A PROSE WITNESS NOT IN ITS DEBT LIST: a {@link LegacyWitness} on a method the committed list does
 *       not hold, and a listed method that no longer carries one (the list only shrinks);</li>
 *   <li>THE KIND OF A CLASS, which is the base it extends and never its name: a concrete class named
 *       {@code *E2ETest} that does not extend {@link AbstractPlayerE2ETest}, a class that does, outside
 *       the {@code test.milestone} package (the one tier that fails on a skipped test), and a class with
 *       tests in {@code test.milestone} that does not (a milestone is a player's whole loop);</li>
 *   <li>A DEBT LIST THAT GREW: a line of a ratchet list the list did not hold at this branch's base
 *       ({@link #checkDebtGrowth});</li>
 *   <li>A BOOT NOT DECLARED WHERE IT IS TAKEN: a class of an own-boot base without {@link OwnBoot} that
 *       the committed debt list does not hold (the list only shrinks), an e2e without {@link NewPath}, and
 *       either on a class that does not take that boot;</li>
 *   <li>what the e2e and the mechanics tier's own code reaches outside the gates ({@code E2EConfinement});</li>
 *   <li>A WAIT THAT IS A POLL: a loop that advances the clock and no {@link Wait} names, a loop that waits and
 *       leaves its body by more than one edge, and a {@link Wait} / {@link ServerOnly} claim that does not
 *       resolve -- beyond the committed debt list, which only shrinks ({@code WaitLoops});</li>
 *   <li>a compiled test class it cannot read, and a clause order git cannot give -- each is a failure,
 *       never a skip.</li>
 * </ul>
 * <p>It reports, never fails on: OWED clauses, and the {@code @Test} methods that pin no anchor.</p>
 *
 * <p>It writes every record and pin, with the method and source file it belongs to, to
 * {@code build/reports/test-bindings/test-bindings.json}: the tools that resolve and replay red
 * witnesses read that, not the source.</p>
 *
 * <p>Usage: {@code TestBindingsCheck [--root DIR] [--list STATE ...]} -- the root holds
 * {@code docs/system} and the git history; the classes are the ones this class was loaded beside.</p>
 */
public final class TestBindingsCheck {

    /** A constant: a compiled {@link Pattern} is immutable (every matcher holds its own state). */
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    /** A constant: a compiled {@link Pattern} is immutable. */
    private static final Pattern MEMBER = Pattern.compile(
            "[A-Za-z_$][\\w$]*#[A-Za-z_$][\\w$]*|[\\w./-]+\\.(?:lang|json|cfg|xml|txt)#[\\w.:-]+");
    /** A constant: a compiled {@link Pattern} is immutable. */
    private static final Pattern ADDED_DEF = Pattern.compile("^\\+[ \\t]*-[ \\t]*\\*\\*(" + PinMap.ANCHOR + ")\\b");
    private static final String E2E_NAME = "E2ETest";

    private TestBindingsCheck() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(".").toAbsolutePath().normalize();
        List<String> listed = new ArrayList<>();
        List<String> included = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if ("--root".equals(args[i])) {
                root = Paths.get(args[++i]).toAbsolutePath().normalize();
            } else if ("--list".equals(args[i])) {
                while (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    listed.add(args[++i].toUpperCase());
                }
            } else if ("--included".equals(args[i])) {
                while (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    included.add(args[++i]);
                }
            }
        }
        System.exit(run(root, listed, included));
    }

    /**
     * @param included the class-file patterns of every test task ({@code dev/x/test/unit/**}, Ant style): a
     *                 method runs only in a class one of them includes. None given is a failure — which
     *                 methods run would otherwise be a guess.
     */
    static int run(Path root, List<String> listed, List<String> included) throws IOException {
        List<String> failures = new ArrayList<>();
        if (included.isEmpty()) {
            System.out.println("FAIL: no test task's includes were given (--included) -- which methods any task"
                    + " runs cannot be decided, so no clause can be judged pinned");
            return 1;
        }
        Map<String, String> docs = readDocs(root.resolve("docs").resolve("system"));
        if (docs.isEmpty()) {
            System.out.println("FAIL: no docs under " + root.resolve("docs/system") + " -- nothing was measured");
            return 1;
        }
        Map<String, PinMap.Clause> defs = PinMap.parse(docs);
        // A malformed region list fails wherever it is written, not only once a test pins the clause: an
        // OWED clause's list is the list its first pin will be held to.
        for (PinMap.Clause c : defs.values()) {
            if (PinMap.regions(c.body) == null) {
                failures.add(c.anchor + " (" + c.doc + "): REGIONS: is not a list of code spans (`a`, `b`, `c`)");
            }
        }

        Corpus corpus = Corpus.read(classesRoot(), included, failures);
        if (corpus.classes == 0) {
            System.out.println("FAIL: no compiled test class beside " + TestBindingsCheck.class.getName()
                    + " -- nothing was measured");
            return 1;
        }
        corpus.checkAnchors(defs.keySet(), failures);
        corpus.checkKinds(failures);
        checkTestMixinClassification(failures);
        System.out.println(E2EConfinement.check(root, classesRoot(), TestBindingsCheck.class.getClassLoader(), failures,
                serverInstruments("E2EInstrument", failures),
                com.github.stannismod.forge.testing.client.bridge.ClientInstruments.rows(),
                serverInstruments("MechanicsInstrument", failures),
                com.github.stannismod.forge.testing.client.bridge.ClientInstruments.mechanicsRows(),
                dev.stannismod.stellurgy.test.trace.StellurgyTestMixinPlugin.OBSERVATION));
        System.out.println(WaitLoops.check(root, classesRoot(), failures));

        Map<String, Integer> ages = new HashMap<>();
        String gitProblem = clauseAges(root, ages);
        if (gitProblem != null) {
            failures.add("the order of the clauses could not be read from git (" + gitProblem + "); a FOR: "
                    + "that cites a younger clause cannot be told apart -- run inside the repository's checkout");
        }
        Map<String, PinMap.Verdict> state = gitProblem == null
                ? PinMap.classify(defs, corpus, ages) : Collections.<String, PinMap.Verdict>emptyMap();

        Map<PinMap.State, Integer> counts = new TreeMap<>();
        for (PinMap.Verdict v : state.values()) {
            counts.merge(v.state, 1, Integer::sum);
        }
        if (gitProblem != null) {
            System.out.println("clauses: NOT CLASSIFIED, so there is no state to count");
        } else {
            for (PinMap.State s : PinMap.State.values()) {
                System.out.println(String.format("%-9s %4d", s, counts.getOrDefault(s, 0)));
            }
        }
        System.out.println(String.format("test methods: %d @Test, %d of them pin no anchor; %d run by a test task",
                corpus.testMethods, corpus.testMethodsWithoutPins, corpus.testMethodsRun));
        System.out.println(checkUnpinnedBaseline(root, corpus.unpinned, failures));
        System.out.println(checkOwnBootDebt(root, corpus.ownBootWithoutReason, failures));
        System.out.println(checkLegacyWitnessDebt(root, corpus.legacyWitnessed, failures));
        for (String debtList : new String[]{UNPINNED_BASELINE, OWN_BOOT_DEBT, LEGACY_WITNESS_DEBT, WaitLoops.DEBT,
                WaitLoops.SPANNING_DEBT, WaitLoops.STRAIGHT_DEBT, WaitLoops.STIMULUS_DEBT}) {
            System.out.println(checkDebtGrowth(root, debtList, failures));
        }
        checkWitnessMembers(root, corpus.witnessMembers, failures);
        System.out.println(String.format("records: %d witnesses (%d @RedWitness, %d @LegacyWitness), %d @NotYetRed;"
                        + " %d @Pins anchors",
                corpus.witnesses + corpus.legacy, corpus.witnesses, corpus.legacy, corpus.notYet, corpus.pinCount));
        for (String kind : listed) {
            new TreeMap<>(state).forEach((anchor, v) -> {
                if (v.state.name().equals(kind)) {
                    System.out.println(String.format("  %s %-16s %s%s", kind, anchor, v.doc,
                            v.why == null ? "" : " -- " + v.why));
                }
            });
        }
        new TreeMap<>(state).forEach((anchor, v) -> {
            if (v.state == PinMap.State.BROKEN) {
                failures.add("BROKEN " + anchor + " (" + v.doc + "): " + v.why);
            }
        });

        Path dump = root.resolve("build").resolve("reports").resolve("test-bindings").resolve("test-bindings.json");
        Files.createDirectories(dump.getParent());
        try (Writer w = Files.newBufferedWriter(dump, StandardCharsets.UTF_8)) {
            new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(corpus.dump(root), w);
        }
        System.out.println("bindings written to " + root.relativize(dump));
        System.out.println(WitnessReplay.locateAll(root, failures));

        for (String f : failures) {
            System.out.println("FAIL " + f);
        }
        System.out.println(failures.isEmpty() ? "test bindings: OK" : "test bindings: " + failures.size() + " failure(s)");
        return failures.isEmpty() ? 0 : 1;
    }

    /** The committed list of the {@code @Test} methods that pinned no anchor when the ratchet was set. */
    static final String UNPINNED_BASELINE = "src/test/bindings/unpinned-test-methods.txt";

    /**
     * Every test names an anchor, held as a RATCHET: the methods that pinned none when the rule was set are
     * listed in {@value #UNPINNED_BASELINE}, and the list only shrinks. An unpinned {@code @Test} the list does
     * not hold fails (a new test names its anchor; a renamed or moved one is new); a listed method that now
     * pins an anchor, or no longer exists, fails until its line is deleted. The current set is written beside
     * the bindings report on every run, for that deletion and for nothing else — the check never writes the
     * baseline. Answers the line it prints.
     */
    static String checkUnpinnedBaseline(Path root, Set<String> unpinned, List<String> failures) throws IOException {
        Path current = root.resolve("build").resolve("reports").resolve("test-bindings").resolve("unpinned-test-methods.txt");
        Files.createDirectories(current.getParent());
        Files.write(current, (String.join("\n", unpinned) + "\n").getBytes(StandardCharsets.UTF_8));
        Path baselineFile = root.resolve(UNPINNED_BASELINE);
        if (!Files.isRegularFile(baselineFile)) {
            failures.add("the unpinned-test baseline " + UNPINNED_BASELINE + " is missing -- without it every"
                    + " unpinned @Test is new; the current set is in " + root.relativize(current));
            return "unpinned baseline: MISSING";
        }
        Set<String> baseline = new TreeSet<>();
        for (String line : Files.readAllLines(baselineFile, StandardCharsets.UTF_8)) {
            String entry = line.trim();
            if (!entry.isEmpty() && !entry.startsWith("#") && !baseline.add(entry)) {
                failures.add(UNPINNED_BASELINE + " lists " + entry + " twice");
            }
        }
        for (String method : unpinned) {
            if (!baseline.contains(method)) {
                failures.add("R24: the @Test " + method + " pins no anchor and is not in " + UNPINNED_BASELINE
                        + " -- a new test names the anchor it pins (@Pins); the baseline only shrinks");
            }
        }
        for (String entry : baseline) {
            if (!unpinned.contains(entry)) {
                failures.add("R24: " + UNPINNED_BASELINE + " lists " + entry + ", which is no longer an unpinned"
                        + " @Test (it pins an anchor now, or is gone) -- delete the line; the baseline only shrinks");
            }
        }
        return String.format("unpinned baseline: %d listed, %d unpinned now", baseline.size(), unpinned.size());
    }

    /** The committed list of the own-boot classes that carried no closed-list reason when the rule was set. */
    static final String OWN_BOOT_DEBT = "src/test/bindings/own-boot-without-reason.txt";

    /**
     * A class of an own-boot base names its reason ({@link OwnBoot}), held as a RATCHET: the classes that
     * had none when the rule was set — a cluster that should be a group on a shared base and is not yet —
     * are listed in {@value #OWN_BOOT_DEBT}, and the list only shrinks. An own-boot class without a reason the
     * list does not hold fails; a listed class that now carries a reason, or no longer takes a boot of its
     * own, or is gone, fails until its line is deleted. Answers the line it prints.
     */
    static String checkOwnBootDebt(Path root, Set<String> withoutReason, List<String> failures) throws IOException {
        Path debtFile = root.resolve(OWN_BOOT_DEBT);
        if (!Files.isRegularFile(debtFile)) {
            failures.add("the own-boot debt list " + OWN_BOOT_DEBT + " is missing -- without it every own-boot class"
                    + " with no @OwnBoot is new");
            return "own-boot debt: MISSING";
        }
        Set<String> debt = new TreeSet<>();
        for (String line : Files.readAllLines(debtFile, StandardCharsets.UTF_8)) {
            String entry = line.trim();
            if (!entry.isEmpty() && !entry.startsWith("#") && !debt.add(entry)) {
                failures.add(OWN_BOOT_DEBT + " lists " + entry + " twice");
            }
        }
        for (String k : withoutReason) {
            if (!debt.contains(k)) {
                failures.add(k + " runs on a boot of its own and names no @OwnBoot(reason = …) -- a mechanics test"
                        + " is a method of a group; a class of its own says which reason on the closed list forces"
                        + " its boot, and " + OWN_BOOT_DEBT + " only shrinks");
            }
        }
        for (String entry : debt) {
            if (!withoutReason.contains(entry)) {
                failures.add(OWN_BOOT_DEBT + " lists " + entry + ", which is no longer an own-boot class without a"
                        + " reason (it names one now, moved to a shared base, or is gone) -- delete the line");
            }
        }
        return String.format("own-boot debt: %d listed, %d without a reason now", debt.size(), withoutReason.size());
    }

    /** The committed list of the methods (a class, for a record on the type) that carried a prose witness at the split. */
    static final String LEGACY_WITNESS_DEBT = "src/test/bindings/legacy-witness-methods.txt";

    /**
     * A witness is replayable, held as a RATCHET over the older prose form: the methods that carried a
     * {@link LegacyWitness} when it was split from {@link RedWitness} are listed in {@value #LEGACY_WITNESS_DEBT},
     * and the list only shrinks. A {@link LegacyWitness} on a method the list does not hold fails (a new record
     * is taken replayable; a renamed or moved method is new); a listed method that no longer carries one fails
     * until its line is deleted. The current set is written beside the bindings report, for that deletion and
     * nothing else. A {@link LegacyWitness} still counts as its method's witness for {@link PinMap}: dropping
     * it would un-pin clauses whose tests were seen red, a loss of what the map shows, not a gain in what is
     * true. Answers the line it prints.
     */
    static String checkLegacyWitnessDebt(Path root, Set<String> carrying, List<String> failures) throws IOException {
        Path current = root.resolve("build").resolve("reports").resolve("test-bindings").resolve("legacy-witness-methods.txt");
        Files.createDirectories(current.getParent());
        Files.write(current, (String.join("\n", carrying) + "\n").getBytes(StandardCharsets.UTF_8));
        Path debtFile = root.resolve(LEGACY_WITNESS_DEBT);
        if (!Files.isRegularFile(debtFile)) {
            failures.add("the prose-witness debt list " + LEGACY_WITNESS_DEBT + " is missing -- without it every"
                    + " @LegacyWitness is new; the current set is in " + root.relativize(current));
            return "legacy-witness debt: MISSING";
        }
        Set<String> debt = new TreeSet<>();
        for (String line : Files.readAllLines(debtFile, StandardCharsets.UTF_8)) {
            String entry = line.trim();
            if (!entry.isEmpty() && !entry.startsWith("#") && !debt.add(entry)) {
                failures.add(LEGACY_WITNESS_DEBT + " lists " + entry + " twice");
            }
        }
        for (String where : carrying) {
            if (!debt.contains(where)) {
                failures.add(where + " carries a @LegacyWitness and is not in " + LEGACY_WITNESS_DEBT + " -- a new"
                        + " record is taken in @RedWitness's replayable form; the list only shrinks");
            }
        }
        for (String entry : debt) {
            if (!carrying.contains(entry)) {
                failures.add(LEGACY_WITNESS_DEBT + " lists " + entry + ", which carries no @LegacyWitness now (re-taken"
                        + " replayable, renamed, or gone) -- delete the line");
            }
        }
        return String.format("legacy-witness debt: %d listed, %d carrying one now", debt.size(), carrying.size());
    }

    /** The branch every debt list's base is taken against: the line the work merges into. */
    static final String MERGE_TARGET = "origin/1.12";

    /**
     * A debt list only SHRINKS, held against what the list said before this branch: each check above compares
     * the list with the tree as it is now, so a line added beside a new debt is green there. Here a line the
     * BASE does not hold is growth and fails, naming it. The base is the list at
     * {@code git merge-base HEAD} {@value #MERGE_TARGET} when it exists there; a list this branch created is
     * held against its own FIRST commit on the branch (the commit that seeded it), and one no commit holds
     * yet is its own base until its first commit. A {@code class#method N} line (the wait-loop debt) also
     * grows when its N rises. Git that cannot answer is a failure, never a skip -- without the base, growth
     * cannot be told from the debt that was there. Answers the line it prints.
     */
    static String checkDebtGrowth(Path root, String debtList, List<String> failures) {
        Path file = root.resolve(debtList);
        if (!Files.isRegularFile(file)) {
            return "debt growth " + debtList + ": the list is missing (its own check fails)";
        }
        String base;
        String baseName;
        try {
            String mergeBase = git(root, "merge-base", "HEAD", MERGE_TARGET).trim();
            if (!git(root, "ls-tree", "--name-only", mergeBase, "--", debtList).trim().isEmpty()) {
                baseName = "the merge base " + mergeBase.substring(0, 10) + " with " + MERGE_TARGET;
                base = git(root, "show", mergeBase + ":" + debtList);
            } else {
                String[] adding = git(root, "log", "--diff-filter=A", "--format=%H", "--reverse",
                        mergeBase + "..HEAD", "--", debtList).trim().split("\\s+");
                if (adding[0].isEmpty()) {
                    return "debt growth " + debtList + ": in no commit yet -- its first commit will be its base";
                }
                baseName = "its first commit on this branch, " + adding[0].substring(0, 10);
                base = git(root, "show", adding[0] + ":" + debtList);
            }
        } catch (IOException e) {
            failures.add("the debt list " + debtList + " could not be compared with its base (" + e.getMessage()
                    + ") -- growth cannot be told from the debt that was there; run inside the repository's checkout"
                    + " with " + MERGE_TARGET + " fetched");
            return "debt growth " + debtList + ": NOT COMPARED";
        }
        Map<String, Integer> was = debtLines(Arrays.asList(base.replace("\r\n", "\n").split("\n")));
        Map<String, Integer> now;
        try {
            now = debtLines(Files.readAllLines(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            failures.add("the debt list " + debtList + " could not be read: " + e);
            return "debt growth " + debtList + ": NOT READ";
        }
        int grown = 0;
        for (Map.Entry<String, Integer> line : now.entrySet()) {
            Integer before = was.get(line.getKey());
            if (before == null || line.getValue() > before) {
                grown++;
                failures.add(debtList + " GREW: `" + line.getKey() + (line.getValue() > 0 ? " " + line.getValue() : "")
                        + "` " + (before == null ? "is not in" : "stands above the " + before + " of") + " the list at "
                        + baseName + " -- a debt list only shrinks; the new case meets the rule instead");
            }
        }
        return String.format("debt growth %s: %d line(s) now, %d at %s, %d grown", debtList, now.size(), was.size(),
                baseName, grown);
    }

    /** A debt list's entries: {@code entry -> N} for a {@code class#method N} line, {@code entry -> 0} else. */
    private static Map<String, Integer> debtLines(List<String> lines) {
        Map<String, Integer> out = new TreeMap<>();
        for (String line : lines) {
            String entry = line.trim();
            if (entry.isEmpty() || entry.startsWith("#")) {
                continue;
            }
            String[] parts = entry.split("\\s+");
            if (parts.length == 2 && parts[1].matches("\\d+")) {
                out.put(parts[0], Integer.parseInt(parts[1]));
            } else {
                out.put(entry, 0);
            }
        }
        return out;
    }

    /** {@code git <args>} in {@code root}: its standard output, or an {@code IOException} naming its exit. */
    private static String git(Path root, String... args) throws IOException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(Arrays.asList(args));
        Process p = new ProcessBuilder(command).directory(root.toFile())
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                out.append(line).append('\n');
            }
        }
        try {
            int exit = p.waitFor();
            if (exit != 0) {
                throw new IOException("git " + String.join(" ", args) + " exited " + exit);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while git " + String.join(" ", args) + " ran");
        }
        return out.toString();
    }

    /**
     * The test system's own judges, whose decisions a test may break as its subject: what they refuse is
     * a decision of code, not the reply of an instrument. A member is admitted by its class, or, where the
     * class also holds instruments, by the member itself. A list, not a pattern -- a new judge is named here.
     */
    static boolean isJudge(String member) {
        switch (member.substring(0, member.indexOf('#'))) {
            case "E2EModeGate":         // the server child's mode gate: what a command may do, per mode
            case "BridgeCommand":       // the client bridge's command table and the kind of each
            case "E2EConfinement":      // the build's confinement of e2e and mechanics test code
            case "PinMap":              // what pins a clause
            case "TestBindingsCheck":   // this check
                return true;
            default:
                break;
        }
        switch (member) {
            // The client bridge's admission; the rest of the class serves the commands it admits.
            case "ForgeTestClientBootstrap#handleCommand":
            case "ForgeTestClientBootstrap#refusedInE2E":
            case "ForgeTestClientBootstrap#refusedInMechanics":
            case "ForgeTestClientBootstrap#offTheSlider":
            case "ForgeTestClientBootstrap#inputNoHandProduces":
                return true;
            default:
                return false;
        }
    }

    /**
     * A replayable record breaks a decision of production: its member is a type declared in
     * {@code src/main/java} outside the probe package {@code command/test}, or in
     * {@code valkyrienskies/src/main/java}, or a resource under {@code src/main/resources} -- or one of the
     * test system's own judges ({@link #isJudge}). A probe or a harness instrument is never the decision, so
     * a record naming one fails, naming its method. Types are found by their declaration in the source (an
     * inner type by its own simple name, as the record names it).
     */
    static void checkWitnessMembers(Path root, List<String[]> records, List<String> failures) throws IOException {
        Set<String> production = productionTypes(root);
        for (String[] record : records) {
            String member = record[1];
            String owner = member.substring(0, member.indexOf('#'));
            boolean isProduction = owner.contains("/")
                    ? Files.isRegularFile(root.resolve("src/main/resources").resolve(owner))
                    : production.contains(owner);
            if (!isProduction && !isJudge(member)) {
                failures.add(record[0] + ": @RedWitness names " + member
                        + ", which is no production code (src/main/java outside command/test,"
                        + " valkyrienskies/src/main/java, src/main/resources) and none of the test system's own"
                        + " judges -- a probe or a harness instrument is not the decision a witness breaks");
            }
        }
    }

    /** A constant: a compiled {@link Pattern} is immutable. */
    private static final Pattern TYPE_DECLARATION = Pattern.compile("^[ \\t]*(?:(?:public|protected|private|static"
            + "|final|abstract|strictfp)\\s+)*(?:class|interface|enum|@interface)\\s+([A-Za-z_$][\\w$]*)", Pattern.MULTILINE);

    /** The simple names of every type declared in the production source roots, probes excluded. */
    static Set<String> productionTypes(Path root) throws IOException {
        Set<String> names = new HashSet<>();
        for (String base : new String[]{"src/main/java", "valkyrienskies/src/main/java"}) {
            Path dir = root.resolve(base);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            List<Path> files;
            try (Stream<Path> walk = Files.walk(dir)) {
                files = walk.filter(p -> p.toString().endsWith(".java"))
                        .filter(p -> !dir.relativize(p).toString().replace('\\', '/')
                                .startsWith("dev/stannismod/stellurgy/command/test/"))
                        .collect(Collectors.toList());
            }
            for (Path p : files) {
                Matcher m = TYPE_DECLARATION.matcher(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
                while (m.find()) {
                    names.add(m.group(1));
                }
            }
        }
        return names;
    }

    /** {@code docs/system/**}{@code .md} outside {@code tools/}, by path relative to it, in path order. */
    static Map<String, String> readDocs(Path sysroot) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.isDirectory(sysroot)) {
            return out;
        }
        List<Path> files;
        try (Stream<Path> walk = Files.walk(sysroot)) {
            files = walk.filter(p -> p.toString().endsWith(".md"))
                    .filter(p -> !sysroot.relativize(p).toString().replace('\\', '/').matches("(.*/)?tools/.*"))
                    .sorted().collect(Collectors.toList());
        }
        for (Path p : files) {
            String text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8).replace("\r\n", "\n");
            out.put(sysroot.relativize(p).toString().replace('\\', '/'), text);
        }
        return out;
    }

    /**
     * Fills {@code ages} with the index of the commit that first ADDS each anchor's bold definition under
     * docs/system (0 = the oldest such commit); answers why not, when git cannot say.
     */
    static String clauseAges(Path root, Map<String, Integer> ages) {
        ProcessBuilder pb = new ProcessBuilder("git", "log", "--reverse", "-p", "--format=COMMIT %H", "--", "docs/system")
                .directory(root.toFile()).redirectError(ProcessBuilder.Redirect.INHERIT);
        int commits = 0;
        try {
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                int index = -1;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("COMMIT ")) {
                        index++;
                        commits++;
                        continue;
                    }
                    Matcher m = ADDED_DEF.matcher(line);
                    if (m.find()) {
                        ages.putIfAbsent(m.group(1), index);
                    }
                }
            }
            int exit = p.waitFor();
            if (exit != 0) {
                return "git log exited " + exit;
            }
        } catch (IOException e) {
            return "git could not be run: " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted";
        }
        return commits == 0 ? "git knows no commit touching docs/system (a shallow or foreign checkout)" : null;
    }

    /** What is wrong with a red-witness record's form, or null when it is the complete replayable form. */
    public static String recordProblem(String verdict, String member, String at, String replacedBy, String fails,
                                String on) {
        boolean structured = !member.isEmpty() || !at.isEmpty() || !RedWitness.NOT_GIVEN.equals(replacedBy)
                || !fails.isEmpty() || !on.isEmpty() || !verdict.isEmpty();
        if (!structured) {
            return "an empty record";
        }
        List<String> missing = new ArrayList<>();
        if (!MEMBER.matcher(member).matches()) {
            missing.add("member as Class#member or a resource path#key");
        }
        if (at.trim().isEmpty()) {
            missing.add("at");
        }
        if (RedWitness.NOT_GIVEN.equals(replacedBy)) {
            missing.add("replacedBy (\"\" to drop the fragment)");
        }
        if (fails.trim().isEmpty()) {
            missing.add("fails");
        }
        if (!DATE.matcher(on).matches()) {
            missing.add("on as yyyy-mm-dd");
        }
        return missing.isEmpty() ? null : "a replayable record without " + String.join(", ", missing);
    }

    /** What is wrong with a prose record, or null when it says something. */
    public static String legacyProblem(String prose) {
        if (prose.trim().isEmpty()) {
            return "a prose record that says nothing";
        }
        return null;
    }

    private static Path classesRoot() {
        try {
            return Paths.get(TestBindingsCheck.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("the compiled test classes have no file location", e);
        }
    }

    /** The compiled test classes, as {@link PinMap.Tests}, with what the dump and the kind checks need. */
    static final class Corpus implements PinMap.Tests {
        /** Simple name -> the qualified names that carry it: a clause names the first, never merges them. */
        private final Map<String, List<String>> bySimpleName = new HashMap<>();
        /** Qualified name -> method name -> the facts of each declared method of that name. */
        private final Map<String, Map<String, List<PinMap.MethodFacts>>> byClass = new HashMap<>();
        private final List<Class<?>> loaded = new ArrayList<>();
        private final List<Pattern> included = new ArrayList<>();
        private final List<Map<String, Object>> records = new ArrayList<>();
        private final Map<String, Set<String>> citedBy = new TreeMap<>();
        private final List<String> regionProblems = new ArrayList<>();
        /** Every {@code @Test} method that pins no anchor, as {@code class#method}. */
        final Set<String> unpinned = new TreeSet<>();
        /** Every concrete class of an own-boot base that carries no {@link OwnBoot}, by name. */
        final Set<String> ownBootWithoutReason = new TreeSet<>();
        /** Every method (a class, for a record on the type) that carries a {@link LegacyWitness}. */
        final Set<String> legacyWitnessed = new TreeSet<>();
        /** Every {@link RedWitness}, as {where, member}. */
        final List<String[]> witnessMembers = new ArrayList<>();
        int classes;
        int testMethods;
        int testMethodsRun;
        int testMethodsWithoutPins;
        int witnesses;
        int legacy;
        int notYet;
        int pinCount;

        static Corpus read(Path classesRoot, List<String> includes, List<String> failures) throws IOException {
            Corpus c = new Corpus();
            for (String include : includes) {
                c.included.add(antPattern(include));
            }
            List<String> names;
            try (Stream<Path> walk = Files.walk(classesRoot)) {
                names = walk.filter(p -> p.toString().endsWith(".class"))
                        .map(p -> classesRoot.relativize(p).toString().replace(File.separatorChar, '.'))
                        .map(n -> n.substring(0, n.length() - ".class".length()))
                        .filter(n -> !n.endsWith("package-info") && !n.endsWith("module-info"))
                        .sorted().collect(Collectors.toList());
            }
            ClassLoader loader = TestBindingsCheck.class.getClassLoader();
            for (String name : names) {
                try {
                    c.loaded.add(Class.forName(name, false, loader));
                } catch (Throwable t) {
                    failures.add("a compiled test class could not be read, so its bindings are unknown: " + name
                            + " (" + t + ")");
                }
            }
            // Every class is loaded before any is judged: whether a method of an abstract class runs depends
            // on the classes that inherit it.
            for (Class<?> k : c.loaded) {
                c.add(k, failures);
            }
            return c;
        }

        /** An Ant-style include ({@code **}, {@code *}, {@code ?}) as a pattern over a class file's path. */
        static Pattern antPattern(String include) {
            StringBuilder regex = new StringBuilder();
            String glob = include.replace('\\', '/');
            for (int i = 0; i < glob.length(); i++) {
                char ch = glob.charAt(i);
                if (ch == '*' && i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    boolean slash = i + 2 < glob.length() && glob.charAt(i + 2) == '/';
                    regex.append(slash ? "(?:.*/)?" : ".*");
                    i += slash ? 2 : 1;
                } else if (ch == '*') {
                    regex.append("[^/]*");
                } else if (ch == '?') {
                    regex.append("[^/]");
                } else {
                    regex.append(Pattern.quote(String.valueOf(ch)));
                }
            }
            return Pattern.compile(regex.toString());
        }

        /** Whether a test task runs {@code k} itself as a test class: concrete, included, not ignored. */
        private boolean taskRuns(Class<?> k) {
            if (Modifier.isAbstract(k.getModifiers()) || k.isInterface() || k.isAnonymousClass()
                    || k.isLocalClass() || !Modifier.isPublic(k.getModifiers())
                    || k.isAnnotationPresent(org.junit.Ignore.class)) {
                return false;
            }
            String file = k.getName().replace('.', '/') + ".class";
            for (Pattern p : included) {
                if (p.matcher(file).matches()) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Whether the methods DECLARED in {@code k} are run: {@code k} is run itself, or (abstract) a class a
         * task runs inherits from it.
         */
        private boolean declaredMethodsRun(Class<?> k) {
            if (taskRuns(k)) {
                return true;
            }
            if (!Modifier.isAbstract(k.getModifiers())) {
                return false;
            }
            for (Class<?> other : loaded) {
                if (other != k && k.isAssignableFrom(other) && taskRuns(other)) {
                    return true;
                }
            }
            return false;
        }

        private void add(Class<?> k, List<String> failures) {
            classes++;
            for (RedWitness w : k.getAnnotationsByType(RedWitness.class)) {
                witness(k, null, w, failures);
            }
            for (LegacyWitness w : k.getAnnotationsByType(LegacyWitness.class)) {
                legacy(k, null, w, failures);
            }
            for (NotYetRed n : k.getAnnotationsByType(NotYetRed.class)) {
                attempt(k, null, n, failures);
            }
            if (k.isAnonymousClass() || k.isSynthetic()) {
                return;
            }
            bySimpleName.computeIfAbsent(k.getSimpleName(), s -> new ArrayList<>()).add(k.getName());
            Map<String, List<PinMap.MethodFacts>> methods = byClass.computeIfAbsent(k.getName(), s -> new HashMap<>());
            boolean classRuns = declaredMethodsRun(k);
            for (Method m : k.getDeclaredMethods()) {
                if (m.isSynthetic() || m.isBridge()) {
                    continue;
                }
                Set<String> pins = new LinkedHashSet<>();
                Pins p = m.getAnnotation(Pins.class);
                if (p != null) {
                    pins.addAll(Arrays.asList(p.value()));
                    if (p.value().length == 0) {
                        failures.add(where(k, m) + ": @Pins names no anchor");
                    }
                    for (String a : p.value()) {
                        pinCount++;
                        citedBy.computeIfAbsent(a, x -> new TreeSet<>()).add(where(k, m));
                    }
                }
                boolean runs = classRuns && m.isAnnotationPresent(org.junit.Test.class)
                        && !m.isAnnotationPresent(org.junit.Ignore.class) && Modifier.isPublic(m.getModifiers())
                        && !Modifier.isStatic(m.getModifiers());
                if (m.isAnnotationPresent(org.junit.Test.class)) {
                    testMethods++;
                    if (pins.isEmpty()) {
                        testMethodsWithoutPins++;
                        unpinned.add(where(k, m));
                    }
                    if (runs) {
                        testMethodsRun++;
                    }
                }
                int seen = 0;
                for (RedWitness w : m.getAnnotationsByType(RedWitness.class)) {
                    witness(k, m, w, failures);
                    seen++;
                }
                // A prose record is still the method's witness: debt, not absence (see checkLegacyWitnessDebt).
                for (LegacyWitness w : m.getAnnotationsByType(LegacyWitness.class)) {
                    legacy(k, m, w, failures);
                    seen++;
                }
                for (NotYetRed n : m.getAnnotationsByType(NotYetRed.class)) {
                    attempt(k, m, n, failures);
                }
                Map<String, Set<String>> regions = new HashMap<>();
                for (Regions r : m.getAnnotationsByType(Regions.class)) {
                    citedBy.computeIfAbsent(r.of(), x -> new TreeSet<>()).add(where(k, m));
                    if (!pins.contains(r.of())) {
                        regionProblems.add(where(k, m) + ": @Regions(of = \"" + r.of() + "\") on a method that does "
                                + "not @Pins it");
                    }
                    regions.computeIfAbsent(r.of(), x -> new LinkedHashSet<>()).addAll(Arrays.asList(r.value()));
                }
                if (!pins.isEmpty()) {
                    Map<String, Object> row = base(k, m, "Pins");
                    row.put("anchors", new ArrayList<>(pins));
                    records.add(row);
                }
                methods.computeIfAbsent(m.getName(), s -> new ArrayList<>())
                        .add(new PinMap.MethodFacts(pins, seen, regions, runs));
            }
        }

        private void witness(Class<?> k, Method m, RedWitness w, List<String> failures) {
            witnesses++;
            String problem = recordProblem(w.verdict(), w.member(), w.at(), w.replacedBy(), w.fails(), w.on());
            if (problem != null) {
                failures.add(where(k, m) + ": @RedWitness: " + problem);
            } else {
                witnessMembers.add(new String[]{where(k, m), w.member()});
            }
            Map<String, Object> row = base(k, m, "RedWitness");
            row.put("verdict", w.verdict());
            row.put("member", w.member());
            row.put("at", w.at());
            row.put("replacedBy", RedWitness.NOT_GIVEN.equals(w.replacedBy()) ? null : w.replacedBy());
            row.put("fails", w.fails());
            row.put("on", w.on());
            records.add(row);
        }

        private void legacy(Class<?> k, Method m, LegacyWitness w, List<String> failures) {
            legacy++;
            legacyWitnessed.add(where(k, m));
            String problem = legacyProblem(w.prose());
            if (problem != null) {
                failures.add(where(k, m) + ": @LegacyWitness: " + problem);
            }
            Map<String, Object> row = base(k, m, "LegacyWitness");
            row.put("prose", w.prose());
            records.add(row);
        }

        private void attempt(Class<?> k, Method m, NotYetRed n, List<String> failures) {
            notYet++;
            if (n.prose().trim().isEmpty()) {
                failures.add(where(k, m) + ": @NotYetRed says nothing");
            }
            Map<String, Object> row = base(k, m, "NotYetRed");
            row.put("prose", n.prose());
            records.add(row);
        }

        private static Map<String, Object> base(Class<?> k, Method m, String kind) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kind", kind);
            row.put("class", k.getName());
            row.put("method", m == null ? null : m.getName());
            return row;
        }

        private static String where(Class<?> k, Method m) {
            return k.getName() + (m == null ? "" : "#" + m.getName());
        }

        void checkAnchors(Set<String> defined, List<String> failures) {
            citedBy.forEach((anchor, sites) -> {
                if (!defined.contains(anchor)) {
                    failures.add("an anchor no doc defines: " + anchor + ", cited by " + String.join(", ", sites));
                }
            });
            failures.addAll(regionProblems);
        }

        /** The kind of a class is the base it extends: checked against its name and its package. */
        void checkKinds(List<String> failures) {
            for (Class<?> k : loaded) {
                if (Modifier.isAbstract(k.getModifiers()) || k.isInterface() || k.isAnonymousClass()) {
                    continue;
                }
                boolean onBase = AbstractPlayerE2ETest.class.isAssignableFrom(k);
                if (k.getSimpleName().endsWith(E2E_NAME) && !onBase) {
                    failures.add(k.getName() + " is named an e2e and does not extend "
                            + AbstractPlayerE2ETest.class.getSimpleName() + ": the base, not the name, makes an "
                            + "e2e -- extend it, or drop E2E from the name");
                }
                String pkg = k.getPackage() == null ? "" : k.getPackage().getName();
                boolean milestone = pkg.endsWith(".test.milestone") || pkg.contains(".test.milestone.");
                // The milestone tier is the one that fails on a skipped test: an e2e skipped by its base's
                // assumption anywhere else ends green, which reads exactly like a path that was walked. And
                // only there is no other test code in its fork's JVM.
                if (onBase && !milestone) {
                    failures.add(k.getName() + " extends " + AbstractPlayerE2ETest.class.getSimpleName()
                            + " outside test.milestone: an e2e runs in the tier that fails on a skip (testMilestone);"
                            + " testClient ends a skipped e2e green");
                }
                if (milestone && !onBase && hasTests(k)) {
                    failures.add(k.getName() + " runs in the milestone tier and does not extend "
                            + AbstractPlayerE2ETest.class.getSimpleName() + ": a milestone is a player's whole "
                            + "loop, so it is an e2e, and only the base starts its children in e2e mode");
                }
                checkBootDeclaration(k, onBase, ownBootWithoutReason, failures);
            }
        }

        /**
         * A boot is declared where it is taken: a concrete class of an own-boot base carries {@link OwnBoot}
         * (a reason on the closed list) — one that does not is collected into {@code withoutReason} for the
         * debt ratchet; an e2e carries {@link NewPath}; neither is admitted anywhere else, so a shared-base
         * class cannot claim a boot it does not take. A class on a shared base declares nothing: sharing the
         * boot is the default, and why it is a new group rather than a method of an old one is a judgement
         * no annotation could check. Declared on the class itself: neither is inherited.
         */
        static void checkBootDeclaration(Class<?> k, boolean e2e, Set<String> withoutReason, List<String> failures) {
            // The bases that boot a server (and a client) for a mechanics class alone.
            Class<?>[] ownBootBases = {
                    com.github.stannismod.forge.testing.junit.AbstractMechanicsTest.class,
                    com.github.stannismod.forge.testing.junit.AbstractOwnBootClientTest.class,
                    com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest.class};
            boolean ownBoot = false;
            for (Class<?> base : ownBootBases) {
                ownBoot |= base.isAssignableFrom(k);
            }
            OwnBoot own = k.getAnnotation(OwnBoot.class);
            NewPath path = k.getAnnotation(NewPath.class);
            if (own != null && own.reason().length == 0) {
                failures.add(k.getName() + ": @OwnBoot names no reason");
            }
            if (path != null && path.value().trim().isEmpty()) {
                failures.add(k.getName() + ": @NewPath names no path");
            }
            if (ownBoot) {
                if (own == null) {
                    withoutReason.add(k.getName());
                }
            } else if (own != null) {
                failures.add(k.getName() + " carries @OwnBoot but runs on "
                        + (e2e ? "the e2e base" : "a shared base") + ", not on a boot of its own -- the declaration"
                        + " belongs to the class that takes the boot");
            }
            if (e2e && path == null) {
                failures.add(k.getName() + " is an e2e and declares no @NewPath(…) -- an e2e is extended before one"
                        + " is added, so a new one names the path no scenario walks");
            } else if (!e2e && path != null) {
                failures.add(k.getName() + " carries @NewPath but is no e2e (it does not extend "
                        + AbstractPlayerE2ETest.class.getSimpleName() + ")");
            }
        }

        private static boolean hasTests(Class<?> k) {
            for (Method m : k.getMethods()) {
                if (m.isAnnotationPresent(org.junit.Test.class)) {
                    return true;
                }
            }
            return false;
        }

        List<Map<String, Object>> dump(Path root) {
            for (Map<String, Object> row : records) {
                String top = ((String) row.get("class")).split("\\$", 2)[0];
                String rel = top.replace('.', '/') + ".java";
                String file = null;
                for (String r : new String[]{"src/test/java", "testframework/src/main/java"}) {
                    if (Files.isRegularFile(root.resolve(r).resolve(rel))) {
                        file = r + "/" + rel;
                        break;
                    }
                }
                row.put("file", file);
            }
            return records;
        }

        @Override
        public List<String> classesNamed(String simpleName) {
            List<String> named = bySimpleName.get(simpleName);
            return named == null ? Collections.<String>emptyList() : named;
        }

        @Override
        public List<PinMap.MethodFacts> methods(String qualifiedName, String method) {
            Map<String, List<PinMap.MethodFacts>> m = byClass.get(qualifiedName);
            if (m == null || !m.containsKey(method)) {
                return Collections.emptyList();
            }
            return m.get(method);
        }
    }

    /** The test mixin configuration whose every mixin the plugin must declare. */
    private static final String TEST_MIXIN_CONFIG = "mixins.stellurgy.test.json";

    /**
     * Every mixin of the test configuration is declared, in {@code StellurgyTestMixinPlugin}, an OBSERVATION
     * (applied in an e2e's child) or a BEHAVIOUR (not) — exactly one, and nothing is declared that the
     * configuration does not hold. An undeclared mixin is not applied in an e2e at all; this is what keeps
     * that default from being where a new mixin silently ends up.
     */
    static void checkTestMixinClassification(List<String> failures) throws IOException {
        Set<String> configured = new TreeSet<>();
        try (java.io.InputStream in = TestBindingsCheck.class.getClassLoader().getResourceAsStream(TEST_MIXIN_CONFIG)) {
            if (in == null) {
                failures.add("the test mixin configuration " + TEST_MIXIN_CONFIG + " is not on the test classpath");
                return;
            }
            com.google.gson.JsonObject config = new com.google.gson.JsonParser()
                    .parse(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            for (String side : new String[]{"mixins", "client", "server"}) {
                if (config.has(side)) {
                    for (com.google.gson.JsonElement name : config.getAsJsonArray(side)) {
                        configured.add(name.getAsString());
                    }
                }
            }
        }
        Set<String> observation = dev.stannismod.stellurgy.test.trace.StellurgyTestMixinPlugin.OBSERVATION;
        java.util.Map<String, String> reasons = dev.stannismod.stellurgy.test.trace.StellurgyTestMixinPlugin.BEHAVIOUR;
        Set<String> behaviour = reasons.keySet();
        reasons.forEach((mixin, reason) -> {
            if (reason == null || reason.trim().isEmpty()) {
                failures.add("the BEHAVIOUR mixin " + mixin + " carries no reason -- a mixin that changes what the"
                        + " game does is a mechanics child's declared environment, and says why it is needed");
            }
        });
        for (String mixin : configured) {
            boolean obs = observation.contains(mixin);
            boolean beh = behaviour.contains(mixin);
            if (obs == beh) {
                failures.add("the test mixin " + mixin + " is declared " + (obs ? "BOTH" : "NEITHER")
                        + " an OBSERVATION and a BEHAVIOUR in StellurgyTestMixinPlugin -- say which it is");
            }
        }
        Set<String> declared = new TreeSet<>(observation);
        declared.addAll(behaviour);
        declared.removeAll(configured);
        for (String stray : declared) {
            failures.add("StellurgyTestMixinPlugin declares " + stray + ", which " + TEST_MIXIN_CONFIG + " does not hold");
        }
        checkTheOnlyTestMixinConfig(failures);
    }

    /**
     * The test JVM's children weave exactly two configurations: the harness's own and
     * {@value #TEST_MIXIN_CONFIG}, whose every mixin is classified above. So the harness coremod names that
     * one (and only it), the configuration names {@code StellurgyTestMixinPlugin} as its plugin — the plugin
     * that keeps BEHAVIOUR mixins out of an e2e's children — and no other mixin configuration is on the
     * test classpath.
     */
    static void checkTheOnlyTestMixinConfig(List<String> failures) throws IOException {
        if (!TEST_MIXIN_CONFIG.equals(com.github.stannismod.forge.testing.mixin.ForgeTestCoreMod.CONSUMER_CONFIG)) {
            failures.add("the harness coremod queues " + com.github.stannismod.forge.testing.mixin.ForgeTestCoreMod.CONSUMER_CONFIG
                    + ", not the classified test configuration " + TEST_MIXIN_CONFIG);
        }
        try (java.io.InputStream in = TestBindingsCheck.class.getClassLoader().getResourceAsStream(TEST_MIXIN_CONFIG)) {
            if (in != null) {
                com.google.gson.JsonObject config = new com.google.gson.JsonParser()
                        .parse(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                String plugin = config.has("plugin") ? config.get("plugin").getAsString() : null;
                if (!dev.stannismod.stellurgy.test.trace.StellurgyTestMixinPlugin.class.getName().equals(plugin)) {
                    failures.add(TEST_MIXIN_CONFIG + " names the plugin " + plugin + ", not StellurgyTestMixinPlugin,"
                            + " which alone keeps its BEHAVIOUR mixins out of an e2e's children");
                }
            }
        }
        Path resources = Paths.get(System.getProperty("user.dir")).resolve("build").resolve("resources").resolve("test");
        if (Files.isDirectory(resources)) {
            try (Stream<Path> walk = Files.walk(resources)) {
                for (Path p : (Iterable<Path>) walk::iterator) {
                    String name = p.getFileName().toString();
                    if (name.startsWith("mixins.") && name.endsWith(".json") && !TEST_MIXIN_CONFIG.equals(name)
                            && !"mixins.forgetestframework.json".equals(name)) {
                        failures.add("a mixin configuration nobody classified is on the test classpath: "
                                + resources.relativize(p) + " -- the children weave the harness's own and "
                                + TEST_MIXIN_CONFIG + " only");
                    }
                }
            }
        }
    }

    /**
     * The server's instrument table, {@code E2EInstrument} — a package-private enum of the mod, read here by
     * reflection: this is the build's check, not a test, and the table has no other reader to ask.
     */
    static List<String[]> serverInstruments(String table, List<String> failures) {
        List<String[]> rows = new ArrayList<>();
        try {
            Class<?> enumClass = Class.forName("dev.stannismod.stellurgy.command.test." + table, true,
                    TestBindingsCheck.class.getClassLoader());
            java.lang.reflect.Field className = enumClass.getDeclaredField("className");
            java.lang.reflect.Field methodName = enumClass.getDeclaredField("methodName");
            className.setAccessible(true);
            methodName.setAccessible(true);
            for (Object row : enumClass.getEnumConstants()) {
                rows.add(new String[]{(String) className.get(row), (String) methodName.get(row)});
            }
        } catch (ReflectiveOperationException e) {
            failures.add("the server's instrument table " + table + " could not be read, so its instruments' bodies are unchecked: " + e);
        }
        return rows;
    }
}
