package dev.stannismod.stellurgy.test.contract;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Replays every REPLAYABLE red witness of the tiers it is given, for real: applies the record's replacement
 * to its member, runs the record's own test method, and requires the failure the record quotes. A witness the
 * build does not replay is a claim about a run nobody can see; one it does is a measurement taken again.
 *
 * <p><b>Two ways to run a method.</b> In process (the fast tiers, a plain JUnit method with no game running):
 * the replaced source file is compiled by itself into a scratch directory and the method runs in a class
 * loader that puts that directory FIRST, so the working tree is never written. {@code --gradle} (the harness
 * tiers, whose methods boot servers and clients only their Gradle task knows how to start): the file is written
 * with the replacement, the record's tier task runs the one method, and the file's exact bytes are put back in
 * a {@code finally} -- the tree is edited for the length of one run.</p>
 *
 * <p>Records come from the dump {@code checkTestBindings} writes ({@code build/reports/test-bindings/
 * test-bindings.json}); a {@code @LegacyWitness} is not replayable and is not counted here (its own list
 * ratchets it). Exit: 0 when every record REPRODUCED, 1 otherwise.</p>
 *
 * <p><b>What it does not see.</b> Whether the replacement is a SEMANTIC inversion of the member's decision
 * (a reader's question); a record whose quote is so short that any failure contains it.</p>
 */
final class WitnessReplay {

    /** Where a member may be declared: the roots the test runtime compiles, the test root last. */
    private static String[] roots() {
        return new String[]{"src/main/java", "valkyrienskies/src/main/java", "testframework/src/main/java",
                "src/test/java"};
    }

    /**
     * How many records one JVM replays in process. Each in-process record loads the whole test runtime -- the
     * game included -- into a class loader of its own, and the loaders are not collected: measured 2026-10-17,
     * ~390 records filled the default 1 GB compressed class space and the run sat in back-to-back full GCs.
     * Past this many, the replay forks one JVM per slice, so what one run holds is bounded by the slice.
     */
    private static final int RECORDS_PER_JVM = 100;

    /** The branch a change is measured against, as the bindings check measures debt growth. */
    private static final String MERGE_TARGET = "origin/1.12";

    private final Path root;
    private final List<String> tiers;
    private final boolean throughGradle;
    private final boolean changedOnly;
    /** This JVM's share, {slice, of}; {0, 1} for the whole set. */
    private final int[] slice;

    private WitnessReplay(Path root, List<String> tiers, boolean throughGradle) {
        this(root, tiers, throughGradle, false, new int[]{0, 1});
    }

    private WitnessReplay(Path root, List<String> tiers, boolean throughGradle, boolean changedOnly, int[] slice) {
        this.root = root;
        this.tiers = tiers;
        this.throughGradle = throughGradle;
        this.changedOnly = changedOnly;
        this.slice = slice;
    }

    /**
     * {@code --tiers t,…} (required), {@code --gradle} (through each tier's task), {@code --root DIR},
     * {@code --changed} (only the records whose test file or member's file differs from the merge base with
     * {@value #MERGE_TARGET}, the working tree and untracked files included) and {@code --slice K/N} (the K-th
     * of N equal shares of the record list; what a forked child is handed).
     */
    public static void main(String[] args) throws Exception {
        Path root = Paths.get("").toAbsolutePath();
        List<String> tiers = new ArrayList<>();
        boolean gradle = false;
        boolean changed = false;
        int[] slice = null;
        for (int i = 0; i < args.length; i++) {
            if ("--tiers".equals(args[i])) {
                tiers.addAll(Arrays.asList(args[++i].split(",")));
            } else if ("--gradle".equals(args[i])) {
                gradle = true;
            } else if ("--root".equals(args[i])) {
                root = Paths.get(args[++i]).toAbsolutePath();
            } else if ("--changed".equals(args[i])) {
                changed = true;
            } else if ("--slice".equals(args[i])) {
                String[] kn = args[++i].split("/");
                slice = new int[]{Integer.parseInt(kn[0]), Integer.parseInt(kn[1])};
            }
        }
        if (tiers.isEmpty()) {
            throw new IllegalArgumentException("--tiers unit,integration|server,client,milestone is required");
        }
        if (slice != null) {
            System.exit(new WitnessReplay(root, tiers, gradle, changed, slice).run() ? 0 : 1);
        }
        WitnessReplay whole = new WitnessReplay(root, tiers, gradle, changed, new int[]{0, 1});
        int count = whole.records().size();
        if (gradle || count <= RECORDS_PER_JVM) {
            System.exit(whole.run() ? 0 : 1);
        }
        System.exit(whole.forkSlices(args, (count + RECORDS_PER_JVM - 1) / RECORDS_PER_JVM) ? 0 : 1);
    }

    /**
     * Replays the record list in {@code slices} child JVMs, one after another, each on this JVM's classpath and
     * arguments plus its {@code --slice}; their rows are passed through, and the summary is the children's sum.
     */
    private boolean forkSlices(String[] args, int slices) throws Exception {
        String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        int total = 0;
        int bad = 0;
        boolean ok = true;
        for (int k = 0; k < slices; k++) {
            List<String> cmd = new ArrayList<>(Arrays.asList(java, "-cp", System.getProperty("java.class.path"),
                    "-Dstellurgy.tests=" + System.getProperty("stellurgy.tests", "true"), WitnessReplay.class.getName()));
            cmd.addAll(Arrays.asList(args));
            cmd.addAll(Arrays.asList("--root", root.toString(), "--slice", k + "/" + slices));
            Process p = new ProcessBuilder(cmd).directory(root.toFile()).redirectErrorStream(true).start();
            try (java.io.BufferedReader out = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = out.readLine()) != null) {
                    Matcher summary = SUMMARY.matcher(line);
                    if (summary.find()) {
                        total += Integer.parseInt(summary.group(1));
                        bad += Integer.parseInt(summary.group(2));
                    } else {
                        System.out.println(line);
                    }
                }
            }
            ok &= p.waitFor() == 0;
        }
        System.out.println("witness replay (" + String.join(",", tiers) + (changedOnly ? ", changed" : "") + "): "
                + total + " record(s), " + bad + " not REPRODUCED, in " + slices + " JVMs");
        return ok && bad == 0;
    }

    /**
     * A slice's summary line, read back by the parent: the records it replayed and how many were not REPRODUCED.
     * A constant: a compiled {@link Pattern} is immutable (every matcher holds its own state).
     */
    private static final Pattern SUMMARY = Pattern.compile("^witness replay \\([^)]*\\): (\\d+) record\\(s\\), (\\d+) not REPRODUCED");

    /**
     * Every replayable record of every tier LOCATED, nothing run: its member exists and its fragment occurs
     * exactly once in it. Run by the bindings check on every build, so a record that a later edit of its member
     * left stale fails there, and not first at the merge that replays it. Answers the line it prints.
     */
    static String locateAll(Path root, List<String> failures) throws IOException {
        WitnessReplay all = new WitnessReplay(root,
                Arrays.asList("unit", "integration", "server", "client", "milestone"), false);
        List<Record> records = all.records();
        int stale = 0;
        for (Record r : records) {
            String where = all.locate(r);
            if (where != null) {
                stale++;
                failures.add("the red witness on " + r.testClass + "#" + r.method + " names " + r.member + " `" + r.at
                        + "`, which does not locate: " + where + " -- the member changed under its record; re-take"
                        + " the witness on the code as it is");
            }
        }
        return "red witnesses: " + records.size() + " replayable record(s) located, " + stale + " stale";
    }

    /** Null when the record's fragment occurs exactly once in its member; otherwise why not. */
    private String locate(Record r) throws IOException {
        String cls = r.member.substring(0, r.member.indexOf('#'));
        Path file = typeFile(cls);
        if (file == null) {
            return "MISSING no type " + cls;
        }
        String src = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        int[] body = memberBody(src, r.member.substring(r.member.indexOf('#') + 1));
        if (body == null) {
            return "MISSING no member " + r.member;
        }
        int hits = occurrences(src, body, r.at);
        return hits == 1 ? null : (hits == 0 ? "MISSING" : "AMBIGUOUS") + " the fragment occurs " + hits + " times";
    }

    private static int occurrences(String src, int[] body, String at) {
        // NOT-A-PROBE-REPLY: Java source -- the record's fragment, whitespace-insensitive, inside its member.
        Matcher m = Pattern.compile(Arrays.stream(at.trim().split("\\s+")).map(Pattern::quote)
                .collect(Collectors.joining("\\s*"))).matcher(src);
        m.region(body[0], body[1]);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    /** One replayable record: its test, and the member, fragment, replacement and quote it carries. */
    private static final class Record {
        String testClass;
        String method;
        String file;
        String tier;
        String member;
        String at;
        String replacedBy;
        String fails;
    }

    private boolean run() throws Exception {
        List<Record> records = records();
        int bad = 0;
        for (Record r : records) {
            String verdict;
            try {
                verdict = replay(r);
            } catch (Exception | AssertionError e) {
                verdict = "ERROR " + e;
            }
            bad += verdict.startsWith("REPRODUCED") ? 0 : 1;
            System.out.println(String.format(Locale.ROOT, "  %-70s %s", r.testClass + "#" + r.method + " ["
                    + r.member + "]", verdict));
        }
        System.out.println("witness replay (" + String.join(",", tiers) + (changedOnly ? ", changed" : "") + "): "
                + records.size() + " record(s), " + bad + " not REPRODUCED");
        return bad == 0;
    }

    /** The record list this JVM replays: the tiers' records, narrowed to the changed ones and to its slice. */
    private List<Record> records() throws IOException {
        List<Record> all = allRecords();
        if (changedOnly) {
            java.util.Set<String> changed = changedFiles();
            if (changed == null) {
                System.out.println("witness replay: the merge base with " + MERGE_TARGET + " could not be read, so"
                        + " every record is replayed -- an unchanged record is not known to be unchanged");
            } else {
                List<Record> kept = new ArrayList<>();
                for (Record r : all) {
                    Path member = typeFile(r.member.substring(0, r.member.indexOf('#')));
                    if (changed.contains(relative(Paths.get(r.file))) || (member != null && changed.contains(relative(member)))) {
                        kept.add(r);
                    }
                }
                all = kept;
            }
        }
        List<Record> mine = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            if (i * slice[1] / Math.max(1, all.size()) == slice[0]) {
                mine.add(all.get(i));
            }
        }
        return mine;
    }

    private String relative(Path p) {
        Path abs = p.isAbsolute() ? p : root.resolve(p);
        return root.relativize(abs.normalize()).toString().replace('\\', '/');
    }

    /**
     * Every file that differs from the merge base with {@value #MERGE_TARGET}: committed since it, changed in the
     * working tree, or untracked. Null when git cannot answer, which the caller reports and treats as "all".
     */
    private java.util.Set<String> changedFiles() {
        try {
            String base = git("merge-base", "HEAD", MERGE_TARGET).trim();
            if (base.isEmpty()) {
                return null;
            }
            java.util.Set<String> out = new java.util.HashSet<>();
            for (String listing : new String[]{git("diff", "--name-only", base), git("ls-files", "--others", "--exclude-standard")}) {
                for (String line : listing.split("\\R")) {
                    if (!line.trim().isEmpty()) {
                        out.add(line.trim());
                    }
                }
            }
            return out;
        } catch (IOException | InterruptedException e) {
            return null;
        }
    }

    private String git(String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(Arrays.asList(args));
        Process p = new ProcessBuilder(cmd).directory(root.toFile()).redirectErrorStream(false).start();
        byte[] out = readAll(p.getInputStream());
        if (p.waitFor() != 0) {
            throw new IOException("git " + String.join(" ", args) + " failed");
        }
        return new String(out, StandardCharsets.UTF_8);
    }

    private static byte[] readAll(java.io.InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) > 0) {
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }

    private List<Record> allRecords() throws IOException {
        Path dump = root.resolve("build/reports/test-bindings/test-bindings.json");
        JsonElement parsed = new JsonParser().parse(new String(Files.readAllBytes(dump), StandardCharsets.UTF_8));
        JsonArray rows = parsed.isJsonArray() ? parsed.getAsJsonArray() : parsed.getAsJsonObject().getAsJsonArray("rows");
        List<Record> out = new ArrayList<>();
        for (JsonElement e : rows) {
            JsonObject row = e.getAsJsonObject();
            if (!"RedWitness".equals(field(row, "kind")) || field(row, "replacedBy") == null) {
                continue;
            }
            String file = field(row, "file");
            String tier = file == null ? null : tierOf(file);
            if (tier == null || !tiers.contains(tier)) {
                continue;
            }
            Record r = new Record();
            String cls = field(row, "class");
            r.testClass = cls.substring(cls.lastIndexOf('.') + 1).split("\\$")[0];
            r.method = field(row, "method");
            r.file = file;
            r.tier = tier;
            r.member = field(row, "member");
            r.at = field(row, "at");
            r.replacedBy = field(row, "replacedBy");
            r.fails = field(row, "fails");
            out.add(r);
        }
        return out;
    }

    private static String field(JsonObject row, String name) {
        JsonElement v = row.get(name);
        return v == null || v.isJsonNull() ? null : v.getAsString();
    }

    private static String tierOf(String file) {
        for (String t : new String[]{"unit", "integration", "server", "client", "milestone"}) {
            if (file.replace('\\', '/').contains("/test/" + t + "/")) {
                return t;
            }
        }
        return null;
    }

    private String replay(Record r) throws Exception {
        String cls = r.member.substring(0, r.member.indexOf('#'));
        String member = r.member.substring(r.member.indexOf('#') + 1);
        Path file = typeFile(cls);
        if (file == null) {
            return "MISSING no type " + cls;
        }
        String src = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        int[] body = memberBody(src, member);
        if (body == null) {
            return "MISSING no member " + r.member;
        }
        // NOT-A-PROBE-REPLY: Java source -- the record's fragment, whitespace-insensitive, inside its member.
        Pattern fragment = Pattern.compile(Arrays.stream(r.at.trim().split("\\s+")).map(Pattern::quote)
                .collect(Collectors.joining("\\s*")));
        Matcher m = fragment.matcher(src);
        m.region(body[0], body[1]);
        List<int[]> hits = new ArrayList<>();
        while (m.find()) {
            hits.add(new int[]{m.start(), m.end()});
        }
        if (hits.size() != 1) {
            return (hits.isEmpty() ? "MISSING" : "AMBIGUOUS") + " the fragment occurs " + hits.size() + " times";
        }
        String replaced = src.substring(0, hits.get(0)[0]) + r.replacedBy + src.substring(hits.get(0)[1]);
        String failure = throughGradle ? throughGradle(file, replaced, r) : inProcess(file, replaced, r);
        if (failure == null) {
            return "NOT-RED the method passed with the replacement applied";
        }
        String quote = norm(r.fails.replaceAll("[.… ]+$", ""));
        quote = quote.substring(0, Math.min(60, quote.length()));
        return norm(failure).contains(quote) ? "REPRODUCED" : "DIFFERENT failed with: "
                + failure.substring(0, Math.min(200, failure.length()));
    }

    private static String norm(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }

    private Path typeFile(String cls) throws IOException {
        for (String base : roots()) {
            try (Stream<Path> walk = Files.walk(root.resolve(base))) {
                List<Path> hit = walk.filter(p -> p.getFileName().toString().equals(cls + ".java"))
                        .collect(Collectors.toList());
                if (!hit.isEmpty()) {
                    return hit.get(0);
                }
            }
        }
        // NOT-A-PROBE-REPLY: Java source -- a nested type's declaration, to find the file that holds it.
        Pattern decl = Pattern.compile("\\b(?:class|enum|interface)\\s+" + Pattern.quote(cls) + "\\b");
        List<Path> found = new ArrayList<>();
        for (String base : roots()) {
            try (Stream<Path> walk = Files.walk(root.resolve(base))) {
                for (Path p : walk.filter(q -> q.toString().endsWith(".java")).collect(Collectors.toList())) {
                    if (decl.matcher(new String(Files.readAllBytes(p), StandardCharsets.UTF_8)).find()) {
                        found.add(p);
                    }
                }
            }
        }
        return found.size() == 1 ? found.get(0) : null;
    }

    /**
     * The span inside the braces of the first declaration of {@code member}, or null. A declaration's name
     * follows its return type or a modifier, separated by whitespace; a call site's name follows an operator
     * or a bracket. A call such as "if (!canFire()) {" has the shape of a declaration otherwise, and was taken
     * for one, so a record on the member it called was looked for in the method that called it.
     */
    private static int[] memberBody(String src, String member) {
        // NOT-A-PROBE-REPLY: Java source -- a member's declaration.
        Matcher m = Pattern.compile("(?<=[\\w>\\]])\\s+" + Pattern.quote(member)
                + "\\s*\\([^;{]*\\)\\s*(?:throws[^{;]*)?\\{").matcher(src);
        if (!m.find()) {
            return null;
        }
        int depth = 1;
        int i = m.end();
        while (i < src.length() && depth > 0) {
            char c = src.charAt(i);
            depth += c == '{' ? 1 : c == '}' ? -1 : 0;
            i++;
        }
        return new int[]{m.end(), i - 1};
    }

    /** The failure message of the record's method with the replaced file compiled first; null if it passed. */
    private String inProcess(Path file, String replaced, Record r) throws Exception {
        Path scratch = Files.createTempDirectory("witness-replay");
        try {
            Path srcDir = scratch.resolve("src").resolve(packageOf(replaced).replace('.', File.separatorChar));
            Files.createDirectories(srcDir);
            Path copy = srcDir.resolve(file.getFileName());
            Files.write(copy, replaced.getBytes(StandardCharsets.UTF_8));
            Path classes = Files.createDirectories(scratch.resolve("classes"));
            String classpath = System.getProperty("java.class.path");
            JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
            if (javac == null) {
                throw new IllegalStateException("no system Java compiler: the replay runs on a JDK");
            }
            ByteArrayOutputStream errors = new ByteArrayOutputStream();
            int code = javac.run(null, null, errors, "-d", classes.toString(), "-cp", classpath, "-encoding",
                    "UTF-8", "-nowarn", "-proc:none", copy.toString());
            if (code != 0) {
                throw new IllegalStateException("the replaced file does not compile: "
                        + new String(errors.toByteArray(), StandardCharsets.UTF_8));
            }
            List<URL> urls = new ArrayList<>();
            urls.add(classes.toUri().toURL());
            for (String entry : classpath.split(File.pathSeparator)) {
                urls.add(new File(entry).toURI().toURL());
            }
            try (URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]),
                    ClassLoader.getSystemClassLoader().getParent())) {
                Class<?> request = loader.loadClass("org.junit.runner.Request");
                Object req = request.getMethod("method", Class.class, String.class)
                        .invoke(null, loader.loadClass(testClassName(r)), r.method);
                Object core = loader.loadClass("org.junit.runner.JUnitCore").newInstance();
                Object result = core.getClass().getMethod("run", request).invoke(core, req);
                List<?> failures = (List<?>) result.getClass().getMethod("getFailures").invoke(result);
                if (failures.isEmpty()) {
                    return null;
                }
                Object first = failures.get(0);
                Method message = first.getClass().getMethod("getMessage");
                return String.valueOf(message.invoke(first));
            }
        } finally {
            deleteTree(scratch);
        }
    }

    /** The failure message of the record's method run by its Gradle task over the replaced file; null if it passed. */
    private String throughGradle(Path file, String replaced, Record r) throws Exception {
        String task = "unit".equals(r.tier) ? "testUnit" : "integration".equals(r.tier) ? "testIntegration"
                : "server".equals(r.tier) ? "testServer" : "client".equals(r.tier) ? "testClient" : "testMilestone";
        Path xml = root.resolve("build/test-results/" + task + "/TEST-" + testClassName(r) + ".xml");
        Files.deleteIfExists(xml);
        byte[] original = Files.readAllBytes(file);
        try {
            Files.write(file, replaced.getBytes(StandardCharsets.UTF_8));
            // The wrapper itself, each argument its own: a whole command line handed to a shell gets its quotes
            // re-quoted on the way, and on Windows the shell then finds no `gradlew.bat` at all.
            String wrapper = root.resolve(File.separatorChar == '\\' ? "gradlew.bat" : "gradlew").toString();
            List<String> cmd = Arrays.asList(wrapper, task, "--tests", "*" + r.testClass + "." + r.method,
                    "--rerun", "-x", "checkTestBindings", "--no-daemon");
            Process p = new ProcessBuilder(cmd).directory(root.toFile()).redirectErrorStream(true)
                    .redirectOutput(root.resolve("build/witness-replay-" + r.testClass + ".log").toFile()).start();
            if (!p.waitFor(40, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                throw new IllegalStateException("the run exceeded 40 minutes");
            }
        } finally {
            Files.write(file, original);
        }
        if (!Files.isRegularFile(xml)) {
            throw new IllegalStateException("no result for " + testClassName(r) + " (a compile error under the"
                    + " replacement, or a task that failed first)");
        }
        NodeList cases = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.toFile())
                .getElementsByTagName("testcase");
        for (int i = 0; i < cases.getLength(); i++) {
            Element testcase = (Element) cases.item(i);
            if (!r.method.equals(testcase.getAttribute("name"))) {
                continue;
            }
            NodeList failures = testcase.getElementsByTagName("failure");
            return failures.getLength() == 0 ? null : ((Element) failures.item(0)).getAttribute("message");
        }
        throw new IllegalStateException("the method did not run");
    }

    private String testClassName(Record r) {
        String path = r.file.replace('\\', '/');
        String rel = path.substring(path.indexOf("src/test/java/") + "src/test/java/".length());
        return rel.substring(0, rel.length() - ".java".length()).replace('/', '.');
    }

    private static String packageOf(String src) {
        // NOT-A-PROBE-REPLY: Java source -- its package declaration.
        Matcher m = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE).matcher(src);
        return m.find() ? m.group(1) : "";
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).collect(Collectors.toList())) {
                Files.deleteIfExists(p);
            }
        }
    }
}
