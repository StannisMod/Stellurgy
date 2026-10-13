package dev.stannismod.stellurgy.test.contract;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The build's rule for the loops that advance a game clock, read off the compiled test source (ASM) and
 * its text -- a wait is a link on a published record or a claimed shape ({@link Wait}), never a poll.
 *
 * <p><b>A TICK LOOP</b> is a loop (a backward branch) of a test-source method whose instructions call a
 * clock primitive -- {@code ClientBot} / {@code ClientReads} {@code waitTicks} and {@code waitWorldTicks},
 * {@code GameTicks} {@code advance}, {@code advanceObserved}, {@code advanceWorld} and {@code observe}, an
 * {@code Events.Step}, the WALL clock ({@code Thread.sleep}, {@code TimeUnit.sleep}, {@code Object.wait}, a
 * lock's park) -- or a test-source method that calls one, at any depth -- outside any loop nested in
 * it. A LINK (a wait on the event log, {@code Events}) spends the clock as a deadline on a record, not as a
 * dose: a loop whose waits are links, or sit in claimed inner loops, is a pass over cases and names no
 * shape -- but it is held by the exit rule like any other.</p>
 *
 * <p><b>It fails the build on</b></p>
 * <ul>
 *   <li>a tick loop standing on no line a {@link Wait} of its method names ({@link Wait#at()}; for a loop
 *       inside a lambda, of the method that writes the lambda) -- an unnamed loop is a claim nobody made;</li>
 *   <li>a loop that waits at all -- tick or link, here or nested -- leaving its body by more than ONE edge:
 *       every jump out of the loop and every {@code return} inside it is an exit, so a {@code break} or a
 *       {@code return} on what it read, or a second condition in its header, makes a second one. A loop
 *       that can end on a value it read is waiting for that value -- save ONE case, THE GOAL EXIT: a loop a
 *       {@code STIMULUS} claim names may leave by a second edge, its goal, when no pass of it can come
 *       round to its header without sending player input. Input is a harness verb of a player's hands
 *       ({@link #inputVerb}), a fed-input instrument the harness's own table names ({@link #fedInput}),
 *       a {@code stellurgytest} command the probe's table calls a TRIGGER, a DECIDE or an ARRANGE
 *       ({@link #stimulusCommand}), or a test-source method every normal return of which has sent one.
 *       "Every pass" is a path property of the bytecode, not "somewhere in the body": input on one branch
 *       of an {@code if} leaves the other pass reading only. And the input must STEER: it varies with what
 *       the loop read -- an argument computed from a reading, or one a branch on a reading picked; the same
 *       push every pass until a goal holds is a dose, and dosing until a goal is a poll;</li>
 *   <li>a loop that waits whose exit TESTS what it read. Of the branches that decide whether the loop ends,
 *       none may test a value a read made inside the loop shaped -- computed from it, or chosen by a branch on
 *       it ({@code ready = read() > 1}, {@code if (read() > 1) i = n;}); a READ is any call into the test's,
 *       the harness's or the game's code, and the JDK's clocks. One edge whose bound is a reading is a poll as
 *       much as a {@code break} is. The goal exit of a steering STIMULUS loop is the one exit that may;</li>
 *   <li>a {@link Wait} or {@link ServerOnly} whose {@code at} does not occur exactly once in its method's
 *       CODE -- its words on ONE line, comments blanked, so a fragment that crosses a line break or sits in
 *       a comment names nothing (the claims that crossed one when the rule was set are listed in
 *       {@value #SPANNING_DEBT} and name only the line their match starts on) -- or whose {@code at} or
 *       {@code why} is blank.</li>
 * </ul>
 * <p>The methods whose loops broke it when it was set are listed in {@value #DEBT} with how many, and the
 * list only shrinks ({@link #checkDebt}).</p>
 *
 * <p><b>Not judged</b>: the classes that ARE the clock ({@link #instrument}) -- their loops are how a wait
 * is built -- and the harness, which is the tests' surface.</p>
 *
 * <p><b>What it does not see.</b> A bound read BEFORE the loop ({@code n = readN(); for (i < n)}) -- fixed
 * while it runs, so not a poll; a reading kept in a FIELD rather than a local; a throw inside a loop, which is
 * an assertion and indistinguishable from an {@code assertTrue} there; a clock advanced by a lambda handed to
 * another method, or by a probe verb that steps a subsystem ({@code transit-tick}); a recursive retry; whether
 * a claimed shape is the true one. Of the goal exit: whether an input sent every pass DOES anything (a key
 * re-held, a delta into a clamped cursor); whether its steering is real -- a branch on a reading that picks
 * the same push either way ({@code x < goal ? 60 : 60}) passes; a command handed to the console through a
 * functional interface ({@code c -> act(c).raw()}) or built without a constant start, which counts as no
 * input; exception edges.</p>
 */
final class WaitLoops {

    /** The committed list of the methods whose tick loops broke the rule when it was set. */
    static final String DEBT = "src/test/bindings/wait-loop-debt.txt";
    /** The committed list of the claims whose fragment crossed line breaks when the one-line rule was set. */
    static final String SPANNING_DEBT = "src/test/bindings/multi-line-wait-claims.txt";

    private static final String TEST_ROOT = "src/test/java";
    private static final String GAME_TICKS = "dev/stannismod/stellurgy/test/GameTicks";
    private static final String EVENTS_STEP = "dev/stannismod/stellurgy/test/Events$Step";
    private static final String HARNESS_CLIENT = "com/github/stannismod/forge/testing/client/";
    private static final String PLAYER_INPUT = HARNESS_CLIENT + "PlayerInput";
    private static final String CLIENT_BOT = HARNESS_CLIENT + "ClientBot";
    /** The harness's one way a command reaches the server's console; every test-side sender wraps it. */
    private static final String TEST_CLIENT = "com/github/stannismod/forge/testing/server/TestClient";
    private static final String PROBE_ROOT = "stellurgytest ";
    private static final String PROBE_VERB_KIND = "dev.stannismod.stellurgy.command.test.ProbeVerbKind";
    private static final String STRING_BUILDER = "java/lang/StringBuilder";
    private static final String STIMULUS = "STIMULUS";
    private static final String WAIT = "Ldev/stannismod/stellurgy/test/contract/Wait;";
    private static final String WAIT_LIST = "Ldev/stannismod/stellurgy/test/contract/Wait$List;";
    private static final String SERVER_ONLY = "Ldev/stannismod/stellurgy/test/contract/ServerOnly;";
    private static final String SERVER_ONLY_LIST = "Ldev/stannismod/stellurgy/test/contract/ServerOnly$List;";

    /**
     * The test-source classes that ARE the clock, whose loops build the waits and are not judged: the
     * server clock's advance and sampling primitives, and the event log's link waits.
     */
    private static boolean instrument(String internal) {
        String top = internal.indexOf('$') < 0 ? internal : internal.substring(0, internal.indexOf('$'));
        switch (top) {
            case GAME_TICKS:
            case "dev/stannismod/stellurgy/test/Events":
                return true;
            default:
                return false;
        }
    }

    /**
     * A call that advances a clock by itself, whoever makes it: a game clock's primitive, or the WALL clock
     * -- {@code Thread.sleep}, {@code TimeUnit.sleep / timedWait / timedJoin}, {@code Object.wait}, a member of
     * {@code java.util.concurrent.locks}. The confinement refuses the wall clock in both harness tiers; here it
     * is a clock like any other, so a loop that sleeps between two reads is a loop that WAITS, held by the
     * same rule, wherever in the test source it stands.
     */
    private static boolean primitive(String owner, String name, String desc) {
        if (owner.startsWith(HARNESS_CLIENT) && ("waitTicks".equals(name) || "waitWorldTicks".equals(name))) {
            return true;
        }
        if (("sleep".equals(name) && ("java/lang/Thread".equals(owner) || "java/util/concurrent/TimeUnit".equals(owner)))
                || (("timedWait".equals(name) || "timedJoin".equals(name)) && "java/util/concurrent/TimeUnit".equals(owner))
                || ("wait".equals(name) && ("()V".equals(desc) || "(J)V".equals(desc) || "(JI)V".equals(desc)))
                || owner.startsWith("java/util/concurrent/locks/")) {
            return true;
        }
        if (GAME_TICKS.equals(owner)) {
            switch (name) {
                case "advance":
                case "advanceObserved":
                case "advanceWorld":
                case "observe":
                    return true;
                default:
                    return false;
            }
        }
        return EVENTS_STEP.equals(owner) && "ticks".equals(name);
    }

    /**
     * A harness verb that sends the client what a player's hands do. Every public verb of
     * {@code PlayerInput} -- the class holds nothing else -- and the {@code ClientBot} verbs that send the
     * client a key, a button, a slot click, a hotbar pick, typing, a chat line, a look, a use or a screen
     * close. Measured off {@code ClientBot} on 2026-10-13: every other verb of it reads, waits, connects,
     * calls an instrument, or sets a client option (render distance, frame rate, framebuffer, HUD).
     */
    private static boolean inputVerb(String owner, String name) {
        if (PLAYER_INPUT.equals(owner)) {
            return !"<init>".equals(name);
        }
        if (!CLIENT_BOT.equals(owner)) {
            return false;
        }
        switch (name) {
            case "selectHotbar":
            case "rightClickBlock":
            case "clickScreenPoint":
            case "clickButton":
            case "clickButtonAtRatio":
            case "clickButtonById":
            case "clickButtonAt":
            case "clickSlotAt":
            case "clickSlot":
            case "dragScreenPoint":
            case "pressScreenKey":
            case "focusField":
            case "typeText":
            case "pressEnterAfterTyping":
            case "sendChat":
            case "setKey":
            case "holdKey":
            case "releaseKey":
            case "setLook":
            case "turnLook":
            case "useItem":
            case "useMouseOver":
            case "interactBlock":
            case "closeScreen":
                return true;
            default:
                return false;
        }
    }

    /**
     * Whether {@code className#method} is a production member the client tier feeds raw input into --
     * the harness's own table of the mechanics tier's instruments, its {@code FEED_INPUT} rows.
     */
    private static boolean fedInput(String className, String method) {
        for (String[] row : com.github.stannismod.forge.testing.client.bridge.ClientInstruments.mechanicsRows()) {
            if (row[0].equals(className) && row[1].equals(method) && "FEED_INPUT".equals(row[2])) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a probe command whose text starts with {@code prefix} is a TRIGGER, a DECIDE or an ARRANGE -- by the
     * probe's own table ({@code ProbeVerbKind}, read reflectively: it is the server's, package-private).
     * A READ, a BUILD, a verb nobody has classified, or a command that is not a {@code stellurgytest}
     * verb is not a stimulus.
     */
    private static boolean stimulusCommand(String prefix) {
        if (prefix == null || !prefix.startsWith(PROBE_ROOT)) {
            return false;
        }
        String[] words = prefix.substring(PROBE_ROOT.length()).trim().split("\\s+");
        try {
            Class<?> kinds = Class.forName(PROBE_VERB_KIND);
            java.lang.reflect.Method of = kinds.getDeclaredMethod("of", String[].class);
            of.setAccessible(true);
            String kind = String.valueOf(of.invoke(null, (Object) words));
            return "TRIGGER".equals(kind) || "DECIDE".equals(kind) || "ARRANGE".equals(kind);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("the probe's verb table " + PROBE_VERB_KIND + " could not be read", e);
        }
    }

    private final Path root;
    private final List<String> failures;
    /** Internal name -> node, for the classes compiled from src/test/java. */
    private final Map<String, ClassNode> nodes = new TreeMap<>();
    /** owner#name+desc -> method, for those classes. */
    private final Map<String, MethodNode> methods = new HashMap<>();
    /** owner#name+desc of a lambda body -> owner#name+desc of the method that writes it. */
    private final Map<String, String> lambdaCreator = new HashMap<>();
    /** owner#name+desc of every test-source method that advances a clock by a number, at any depth. */
    private final Set<String> ticking = new HashSet<>();
    /** ...and of every one that waits at all, a link on the event log included. */
    private final Set<String> waiting = new HashSet<>();
    /** The source text of each top-level class file, by internal name of the top-level class. */
    private final Map<String, String> sources = new HashMap<>();
    /** Every tick loop found, for the report: method, line, exits, named. */
    private final List<String> report = new ArrayList<>();
    /** Violations, held until the debt list is read: {method, message}. */
    private final List<String[]> pending = new ArrayList<>();
    /** Lines named by the WAIT claims of each method, by method key, with the shape each claims. */
    private final Map<String, List<Claim>> waitLines = new HashMap<>();
    /** owner#name+desc -> the ordinal of the parameter it hands on to the server's console, at any depth. */
    private final Map<String, Integer> senders = new HashMap<>();
    /** owner#name+desc of every test-source method whose every normal return has sent player input. */
    private final Set<String> alwaysSends = new HashSet<>();
    /** The lines of {@value #SPANNING_DEBT}. */
    private final Set<String> spanningDebt = new TreeSet<>();
    /** ...and those a claim resolved through on this run. */
    private final Set<String> spanningUsed = new TreeSet<>();
    /** Every claim that does not resolve on one line now, as a debt line would name it (the report). */
    private final Set<String> spanningNow = new TreeSet<>();
    /** The stack sources of each method's instructions, computed when a call's arguments are asked. */
    private final Map<String, Frame<SourceValue>[]> frames = new HashMap<>();

    /** One {@link Wait} claim: the lines its {@code at} spans, and the shape it claims for them. */
    private static final class Claim {
        final int first;
        final int last;
        final String shape;

        Claim(int first, int last, String shape) {
            this.first = first;
            this.last = last;
            this.shape = shape;
        }
    }

    private WaitLoops(Path root, List<String> failures) {
        this.root = root;
        this.failures = failures;
    }

    /** Runs the rule over the compiled test output; answers the line it prints. */
    static String check(Path root, Path testClasses, List<String> failures) throws IOException {
        WaitLoops w = new WaitLoops(root, failures);
        Path spanningFile = root.resolve(SPANNING_DEBT);
        if (!Files.isRegularFile(spanningFile)) {
            failures.add("the multi-line claim list " + SPANNING_DEBT + " is missing -- without it every claim that"
                    + " crosses a line break is new");
        } else {
            for (String line : Files.readAllLines(spanningFile, StandardCharsets.UTF_8)) {
                String entry = line.trim();
                if (!entry.isEmpty() && !entry.startsWith("#") && !w.spanningDebt.add(entry)) {
                    failures.add(SPANNING_DEBT + " lists `" + entry + "` twice");
                }
            }
        }
        w.read(testClasses);
        w.closeTicking();
        w.closeSenders();
        w.closeAlwaysSends();
        Map<String, Integer> violating = new TreeMap<>();
        int[] counts = w.judge(violating);
        String debt = w.checkDebt(violating);
        String spanning = w.checkSpanningDebt();
        Path out = root.resolve("build").resolve("reports").resolve("test-bindings").resolve("wait-loops.txt");
        Files.createDirectories(out.getParent());
        Files.write(out, (String.join("\n", w.report) + "\n").getBytes(StandardCharsets.UTF_8));
        Files.write(out.resolveSibling("multi-line-wait-claims.txt"),
                (String.join("\n", w.spanningNow) + "\n").getBytes(StandardCharsets.UTF_8));
        return String.format("wait loops: %d loop(s) that wait, in %d method(s), %d of them advancing the clock"
                + " themselves; %d claim(s) resolved; %s; %s", counts[0], counts[1], counts[3], counts[2], debt, spanning);
    }

    // ------------------------------------------------------------------------------------------------
    // reading

    @SuppressWarnings("unchecked")
    private void read(Path testClasses) throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(testClasses)) {
            files = walk.filter(p -> p.toString().endsWith(".class")).sorted().collect(Collectors.toList());
        }
        for (Path p : files) {
            ClassNode node = new ClassNode();
            new ClassReader(Files.readAllBytes(p)).accept(node, 0);
            if (node.sourceFile == null || node.name.endsWith("package-info")) {
                continue;
            }
            String pkg = node.name.lastIndexOf('/') < 0 ? "" : node.name.substring(0, node.name.lastIndexOf('/') + 1);
            if (!Files.isRegularFile(root.resolve(TEST_ROOT).resolve(pkg + node.sourceFile))) {
                continue;
            }
            nodes.put(node.name, node);
            for (MethodNode m : (List<MethodNode>) node.methods) {
                methods.put(key(node.name, m), m);
            }
        }
        for (ClassNode node : nodes.values()) {
            for (MethodNode m : (List<MethodNode>) node.methods) {
                for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn instanceof InvokeDynamicInsnNode) {
                        for (Object arg : ((InvokeDynamicInsnNode) insn).bsmArgs) {
                            if (arg instanceof Handle && ((Handle) arg).getOwner().equals(node.name)
                                    && ((Handle) arg).getName().startsWith("lambda$")) {
                                Handle h = (Handle) arg;
                                lambdaCreator.put(h.getOwner() + "#" + h.getName() + h.getDesc(), key(node.name, m));
                            }
                        }
                    }
                }
            }
        }
    }

    private static String key(String owner, MethodNode m) {
        return owner + "#" + m.name + m.desc;
    }

    /** The test-source method a call names, climbing to the class that declares it; null when none does. */
    private String declared(String owner, String name, String desc) {
        String at = owner;
        for (int depth = 0; at != null && depth < 32; depth++) {
            String k = at + "#" + name + desc;
            if (methods.containsKey(k)) {
                return k;
            }
            ClassNode node = nodes.get(at);
            at = node == null ? null : node.superName;
        }
        return null;
    }

    /** Whether {@code insn} calls a primitive, or a test-source method {@code closure} holds. */
    private boolean calls(AbstractInsnNode insn, Set<String> closure) {
        if (!(insn instanceof MethodInsnNode)) {
            return false;
        }
        MethodInsnNode mi = (MethodInsnNode) insn;
        if (primitive(mi.owner, mi.name, mi.desc)) {
            return true;
        }
        String target = declared(mi.owner, mi.name, mi.desc);
        return target != null && closure.contains(target);
    }

    /** Whether {@code insn} advances a clock by a number: a primitive, or a test method that does. */
    private boolean advances(AbstractInsnNode insn) {
        return calls(insn, ticking);
    }

    /** Whether {@code insn} waits at all: an advance, or a LINK -- a wait on the event log. */
    private boolean waits(AbstractInsnNode insn) {
        return calls(insn, waiting);
    }

    /**
     * Fills {@link #ticking} and {@link #waiting} to their fixpoints. A link wait ({@code Events}) spends the
     * clock too, but as a deadline on a record, not a dose: it makes a method WAIT, never TICK.
     */
    private void closeTicking() {
        close(ticking, false);
        close(waiting, true);
    }

    private void close(Set<String> closure, boolean throughLinks) {
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Map.Entry<String, MethodNode> e : methods.entrySet()) {
                String owner = e.getKey().substring(0, e.getKey().indexOf('#'));
                if (closure.contains(e.getKey()) || (!throughLinks && link(owner))) {
                    continue;
                }
                for (AbstractInsnNode insn = e.getValue().instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (calls(insn, closure)) {
                        closure.add(e.getKey());
                        grew = true;
                        break;
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------
    // input

    /**
     * Fills {@link #senders}: the harness's console calls in a writing role ({@code TestClient#arrange},
     * {@code #act}), then every test-source method that hands one of its own parameters, unchanged, to a
     * sender -- {@code arrange(cmd)}, {@code act(cmd)}, and so on up.
     */
    private void closeSenders() {
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Map.Entry<String, MethodNode> e : methods.entrySet()) {
                if (senders.containsKey(e.getKey())) {
                    continue;
                }
                MethodNode m = e.getValue();
                AbstractInsnNode[] code = m.instructions.toArray();
                for (int i = 0; i < code.length; i++) {
                    SourceValue command = sentCommand(e.getKey(), code, i);
                    Integer param = command == null ? null : parameterHandedOn(m, command);
                    if (param != null) {
                        senders.put(e.getKey(), param);
                        grew = true;
                        break;
                    }
                }
            }
        }
    }

    /** The ordinal of the parameter {@code value} is, when it is one {@code m} never reassigns; else null. */
    private static Integer parameterHandedOn(MethodNode m, SourceValue value) {
        if (value.insns.size() != 1 || !(value.insns.iterator().next() instanceof VarInsnNode)) {
            return null;
        }
        VarInsnNode load = (VarInsnNode) value.insns.iterator().next();
        if (load.getOpcode() != Opcodes.ALOAD) {
            return null;
        }
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() == Opcodes.ASTORE && ((VarInsnNode) insn).var == load.var) {
                return null;
            }
        }
        int slot = (m.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
        Type[] args = Type.getArgumentTypes(m.desc);
        for (int p = 0; p < args.length; p++) {
            if (slot == load.var) {
                return p;
            }
            slot += args[p].getSize();
        }
        return null;
    }

    /** The command a call at {@code i} hands to the server's console, as its stack source; null for none. */
    private SourceValue sentCommand(String methodKey, AbstractInsnNode[] code, int i) {
        if (!(code[i] instanceof MethodInsnNode)) {
            return null;
        }
        MethodInsnNode mi = (MethodInsnNode) code[i];
        Integer param;
        // `read` is never input: its role admits only what observes. `arrange` and `act` carry what the
        // command's own leading words then say it is (`stimulusCommand`).
        if (TEST_CLIENT.equals(mi.owner) && ("arrange".equals(mi.name) || "act".equals(mi.name))) {
            param = 0;
        } else {
            String target = declared(mi.owner, mi.name, mi.desc);
            param = target == null ? null : senders.get(target);
        }
        return param == null ? null : argument(methodKey, code, i, param);
    }

    /** The stack source of argument {@code ordinal} of the call at {@code i}; null when it cannot be read. */
    private SourceValue argument(String methodKey, AbstractInsnNode[] code, int i, int ordinal) {
        Frame<SourceValue>[] f = framesOf(methodKey);
        if (f == null || f[i] == null) {
            return null;
        }
        int n = Type.getArgumentTypes(((MethodInsnNode) code[i]).desc).length;
        return f[i].getStack(f[i].getStackSize() - n + ordinal);
    }

    @SuppressWarnings("unchecked")
    private Frame<SourceValue>[] framesOf(String methodKey) {
        if (!frames.containsKey(methodKey)) {
            Frame<SourceValue>[] f = null;
            try {
                f = new Analyzer<>(new SourceInterpreter())
                        .analyze(methodKey.substring(0, methodKey.indexOf('#')), methods.get(methodKey));
            } catch (AnalyzerException unreadable) {
                // a method the analyzer cannot follow names no argument, so it sends no command this
                // check can read -- it is judged by its other instructions
            }
            frames.put(methodKey, f);
        }
        return frames.get(methodKey);
    }

    /**
     * The text a string value starts with, when that start is a constant: the constant itself, or the
     * first constant appended to a {@code StringBuilder} whose {@code toString} it is. Null otherwise.
     */
    private String leadingText(String methodKey, SourceValue value, int depth) {
        if (value == null || value.insns.size() != 1 || depth > 64) {
            return null;
        }
        AbstractInsnNode producer = value.insns.iterator().next();
        if (producer instanceof LdcInsnNode) {
            Object cst = ((LdcInsnNode) producer).cst;
            return cst instanceof String ? (String) cst : null;
        }
        if (!(producer instanceof MethodInsnNode) || !STRING_BUILDER.equals(((MethodInsnNode) producer).owner)) {
            return null;
        }
        MethodInsnNode call = (MethodInsnNode) producer;
        MethodNode m = methods.get(methodKey);
        AbstractInsnNode[] code = m.instructions.toArray();
        int at = m.instructions.indexOf(call);
        if ("toString".equals(call.name)) {
            return leadingText(methodKey, receiver(methodKey, code, at), depth + 1);
        }
        if (!"append".equals(call.name)) {
            return null;
        }
        SourceValue onto = receiver(methodKey, code, at);
        AbstractInsnNode below = onto == null || onto.insns.size() != 1 ? null : onto.insns.iterator().next();
        if (below instanceof MethodInsnNode && STRING_BUILDER.equals(((MethodInsnNode) below).owner)
                && "append".equals(((MethodInsnNode) below).name)) {
            return leadingText(methodKey, onto, depth + 1);
        }
        // The first append: unless the builder was constructed around a string, its start is this argument.
        if (below != null && below.getOpcode() == Opcodes.DUP) {
            for (AbstractInsnNode next = below.getNext(); next != null && next != call; next = next.getNext()) {
                if (next.getOpcode() == Opcodes.INVOKESPECIAL && STRING_BUILDER.equals(((MethodInsnNode) next).owner)) {
                    if ("(Ljava/lang/String;)V".equals(((MethodInsnNode) next).desc)) {
                        return leadingText(methodKey, argument(methodKey, code, m.instructions.indexOf(next), 0),
                                depth + 1);
                    }
                    break;
                }
            }
        }
        return leadingText(methodKey, argument(methodKey, code, at, 0), depth + 1);
    }

    /** The stack source of the receiver of the instance call at {@code i}. */
    private SourceValue receiver(String methodKey, AbstractInsnNode[] code, int i) {
        Frame<SourceValue>[] f = framesOf(methodKey);
        if (f == null || f[i] == null) {
            return null;
        }
        int n = Type.getArgumentTypes(((MethodInsnNode) code[i]).desc).length;
        return f[i].getStack(f[i].getStackSize() - n - 1);
    }

    /**
     * Whether the instruction at {@code i} sends player input: a harness input verb ({@link #inputVerb}), a
     * fed-input instrument called with its constant name ({@link #fedInput}), a probe command whose
     * constant start is a TRIGGER, a DECIDE or an ARRANGE ({@link #stimulusCommand}), or a test-source method every
     * normal return of which has sent one.
     */
    private boolean sends(String methodKey, AbstractInsnNode[] code, int i) {
        if (!(code[i] instanceof MethodInsnNode)) {
            return false;
        }
        MethodInsnNode mi = (MethodInsnNode) code[i];
        if (inputVerb(mi.owner, mi.name)) {
            return true;
        }
        if (mi.owner.startsWith(HARNESS_CLIENT) && "invokeStaticInt".equals(mi.name)) {
            String className = leadingText(methodKey, argument(methodKey, code, i, 0), 0);
            String method = leadingText(methodKey, argument(methodKey, code, i, 1), 0);
            return className != null && method != null && fedInput(className, method);
        }
        SourceValue command = sentCommand(methodKey, code, i);
        if (command != null) {
            return stimulusCommand(leadingText(methodKey, command, 0));
        }
        String target = declared(mi.owner, mi.name, mi.desc);
        return target != null && alwaysSends.contains(target);
    }

    /** Fills {@link #alwaysSends} to its fixpoint: a method no normal return of which avoids input. */
    private void closeAlwaysSends() {
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Map.Entry<String, MethodNode> e : methods.entrySet()) {
                if (alwaysSends.contains(e.getKey())) {
                    continue;
                }
                AbstractInsnNode[] code = e.getValue().instructions.toArray();
                if (code.length > 0 && !inputAvoidable(e.getKey(), code, 0, code.length - 1, -1)) {
                    alwaysSends.add(e.getKey());
                    grew = true;
                }
            }
        }
    }

    /**
     * Whether a path from instruction {@code from} reaches {@code target} -- or, for {@code target} -1, a
     * return -- inside [{@code from}, {@code to}] without passing an instruction that sends input. Exception
     * edges are not followed: a throw ends the test.
     */
    private boolean inputAvoidable(String methodKey, AbstractInsnNode[] code, int from, int to, int target) {
        Map<LabelNode, Integer> index = labels(code);
        Deque<Integer> work = new ArrayDeque<>();
        Set<Integer> seen = new HashSet<>();
        work.push(from);
        seen.add(from);
        while (!work.isEmpty()) {
            int i = work.pop();
            if (sends(methodKey, code, i)) {
                continue;
            }
            int op = code[i].getOpcode();
            if (target < 0 && op >= Opcodes.IRETURN && op <= Opcodes.RETURN) {
                return true;
            }
            for (int s : successors(code, index, i)) {
                if (target >= 0 && s == target) {
                    return true;
                }
                if (s >= from && s <= to && seen.add(s)) {
                    work.push(s);
                }
            }
        }
        return false;
    }

    private static Map<LabelNode, Integer> labels(AbstractInsnNode[] code) {
        Map<LabelNode, Integer> index = new HashMap<>();
        for (int i = 0; i < code.length; i++) {
            if (code[i] instanceof LabelNode) {
                index.put((LabelNode) code[i], i);
            }
        }
        return index;
    }

    /** Where control goes after instruction {@code i}, exception edges aside. */
    private static List<Integer> successors(AbstractInsnNode[] code, Map<LabelNode, Integer> index, int i) {
        List<Integer> out = new ArrayList<>();
        int op = code[i].getOpcode();
        if ((op >= Opcodes.IRETURN && op <= Opcodes.RETURN) || op == Opcodes.ATHROW) {
            return out;
        }
        for (LabelNode target : targets(code[i])) {
            Integer t = index.get(target);
            if (t != null) {
                out.add(t);
            }
        }
        boolean falls = op != Opcodes.GOTO && !(code[i] instanceof TableSwitchInsnNode)
                && !(code[i] instanceof LookupSwitchInsnNode);
        if (falls && i + 1 < code.length) {
            out.add(i + 1);
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------
    // what a loop read

    /** A value as the read analysis sees it: its basic type, and whether a read made in the loop shaped it. */
    private static final class Taint implements Value {
        final BasicValue basic;
        final boolean read;

        Taint(BasicValue basic, boolean read) {
            this.basic = basic;
            this.read = read;
        }

        @Override
        public int getSize() {
            return basic.getSize();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Taint && ((Taint) o).basic.equals(basic) && ((Taint) o).read == read;
        }

        @Override
        public int hashCode() {
            return basic.hashCode() * 2 + (read ? 1 : 0);
        }
    }

    /**
     * Carries "was shaped by a read" through a method: a value is READ when a {@code sources} instruction made
     * it, or any value it was computed from was -- through locals, the stack and across the loop's own back
     * edge. Everything else (constants, parameters, what was computed before the loop) is not.
     */
    private static final class ReadInterpreter extends Interpreter<Taint> {
        private final BasicInterpreter basic = new BasicInterpreter();
        private final Set<AbstractInsnNode> sources;

        ReadInterpreter(Set<AbstractInsnNode> sources) {
            super(Opcodes.ASM5);
            this.sources = sources;
        }

        private static Taint wrap(BasicValue v, boolean read) {
            return v == null ? null : new Taint(v, read);
        }

        @Override
        public Taint newValue(Type type) {
            return wrap(basic.newValue(type), false);
        }

        @Override
        public Taint newOperation(AbstractInsnNode insn) throws AnalyzerException {
            return wrap(basic.newOperation(insn), false);
        }

        @Override
        public Taint copyOperation(AbstractInsnNode insn, Taint v) throws AnalyzerException {
            return wrap(basic.copyOperation(insn, v.basic), v.read);
        }

        @Override
        public Taint unaryOperation(AbstractInsnNode insn, Taint v) throws AnalyzerException {
            return wrap(basic.unaryOperation(insn, v.basic), v.read);
        }

        @Override
        public Taint binaryOperation(AbstractInsnNode insn, Taint a, Taint b) throws AnalyzerException {
            return wrap(basic.binaryOperation(insn, a.basic, b.basic), a.read || b.read);
        }

        @Override
        public Taint ternaryOperation(AbstractInsnNode insn, Taint a, Taint b, Taint c) throws AnalyzerException {
            return wrap(basic.ternaryOperation(insn, a.basic, b.basic, c.basic), a.read || b.read || c.read);
        }

        @Override
        public Taint naryOperation(AbstractInsnNode insn, List<? extends Taint> values) throws AnalyzerException {
            List<BasicValue> basics = new ArrayList<>();
            boolean read = sources.contains(insn);
            for (Taint v : values) {
                basics.add(v.basic);
                read |= v.read;
            }
            return wrap(basic.naryOperation(insn, basics), read);
        }

        @Override
        public void returnOperation(AbstractInsnNode insn, Taint value, Taint expected) {
        }

        @Override
        public Taint merge(Taint a, Taint b) {
            if (a.equals(b)) {
                return a;
            }
            return new Taint(basic.merge(a.basic, b.basic), a.read || b.read);
        }
    }

    /**
     * Whether the call {@code insn} READS -- answers something about the world or the clock: any call into code
     * that is not the JDK's (the test's helpers, the harness, the game), and the JDK's own clocks. A JDK call
     * on values the loop did not read (an iterator over a list built before it, a {@code Math.max}) reads
     * nothing.
     */
    private static boolean reads(AbstractInsnNode insn) {
        if (!(insn instanceof MethodInsnNode)) {
            return false;
        }
        MethodInsnNode mi = (MethodInsnNode) insn;
        if ("java/lang/System".equals(mi.owner)
                && ("nanoTime".equals(mi.name) || "currentTimeMillis".equals(mi.name))) {
            return true;
        }
        if (mi.owner.startsWith("java/time/")) {
            return true;
        }
        return !(mi.owner.startsWith("java/") || mi.owner.startsWith("javax/") || mi.owner.startsWith("jdk/")
                || mi.owner.startsWith("sun/"));
    }

    /** The read analysis of {@code m} for {@code loop}: its sources are the reads made inside the loop. */
    private Frame<Taint>[] readFrames(String owner, MethodNode m, AbstractInsnNode[] code, Loop loop) {
        Set<AbstractInsnNode> sources = new HashSet<>();
        for (int i = loop.from; i <= loop.to; i++) {
            if (reads(code[i])) {
                sources.add(code[i]);
            }
        }
        try {
            return new Analyzer<>(new ReadInterpreter(sources)).analyze(owner, m);
        } catch (AnalyzerException unreadable) {
            return null;
        }
    }

    private static boolean conditional(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        return (op >= Opcodes.IFEQ && op <= Opcodes.IF_ACMPNE) || op == Opcodes.IFNULL || op == Opcodes.IFNONNULL
                || insn instanceof TableSwitchInsnNode || insn instanceof LookupSwitchInsnNode;
    }

    /** How many stack operands the branch at {@code insn} tests. */
    private static int operands(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        return op >= Opcodes.IF_ICMPEQ && op <= Opcodes.IF_ACMPNE ? 2 : 1;
    }

    /** Whether a path from {@code s} leaves {@code loop} -- a jump out or a return -- before coming round. */
    private static boolean reachesExit(AbstractInsnNode[] code, Map<LabelNode, Integer> index, Loop loop, int s) {
        Deque<Integer> work = new ArrayDeque<>();
        Set<Integer> seen = new HashSet<>();
        work.push(s);
        while (!work.isEmpty()) {
            int i = work.pop();
            if (i < loop.from || i > loop.to) {
                return true;
            }
            if (i == loop.from && i != s || !seen.add(i)) {
                continue;
            }
            int op = code[i].getOpcode();
            if (op >= Opcodes.IRETURN && op <= Opcodes.RETURN) {
                return true;
            }
            for (int t : successors(code, index, i)) {
                work.push(t);
            }
        }
        return false;
    }

    /**
     * The branches of {@code loop} that DECIDE whether it ends -- one way leads out before the loop comes
     * round, the other does not -- and test a value the loop read: the source lines of each, empty when every
     * exit of the loop is decided by what it did not read (its counter, its bound, its cases).
     */
    private List<Integer> exitsOnReads(String methodKey, AbstractInsnNode[] code, Loop loop, Frame<Taint>[] f) {
        List<Integer> lines = new ArrayList<>();
        if (f == null) {
            return lines;
        }
        Map<LabelNode, Integer> index = labels(code);
        List<Integer> readBranches = new ArrayList<>();
        for (int j = loop.from; j <= loop.to; j++) {
            if (conditional(code[j]) && testsRead(code, j, f)) {
                readBranches.add(j);
            }
        }
        Frame<SourceValue>[] sources = framesOf(methodKey);
        for (int j = loop.from; j <= loop.to; j++) {
            if (!conditional(code[j]) || !decidesExit(code, index, loop, j)) {
                continue;
            }
            if (testsRead(code, j, f) || chosenByRead(code, index, loop, sources, readBranches, j)) {
                lines.add(lineAt(code, j));
            }
        }
        return lines;
    }

    /**
     * Whether a value the branch at {@code j} tests was CHOSEN by a reading: one of the instructions that made
     * it -- followed back through the stack and through the loop's own stores to a local it loads -- runs only
     * on one side of a branch on a value the loop read. {@code ready = read() > 1} stores a constant the
     * comparison picked, and {@code if (read() > 1) i = 100;} rewrites the counter: neither value is computed
     * FROM the reading, both are decided BY it.
     */
    private static boolean chosenByRead(AbstractInsnNode[] code, Map<LabelNode, Integer> index, Loop loop,
                                        Frame<SourceValue>[] sources, List<Integer> readBranches, int j) {
        if (sources == null || sources[j] == null || readBranches.isEmpty()) {
            return false;
        }
        Deque<AbstractInsnNode> work = new ArrayDeque<>();
        Set<AbstractInsnNode> seen = new HashSet<>();
        for (int k = 0; k < operands(code[j]); k++) {
            work.addAll(sources[j].getStack(sources[j].getStackSize() - 1 - k).insns);
        }
        List<AbstractInsnNode> all = Arrays.asList(code);
        while (!work.isEmpty()) {
            AbstractInsnNode insn = work.pop();
            if (!seen.add(insn)) {
                continue;
            }
            int at = all.indexOf(insn);
            if (at < loop.from || at > loop.to) {
                continue;
            }
            for (int b : readBranches) {
                if (decides(code, index, loop, b, at)) {
                    return true;
                }
            }
            if (insn instanceof VarInsnNode && insn.getOpcode() >= Opcodes.ILOAD && insn.getOpcode() <= Opcodes.ALOAD) {
                int var = ((VarInsnNode) insn).var;
                for (int s = loop.from; s <= loop.to; s++) {
                    boolean store = code[s] instanceof VarInsnNode && ((VarInsnNode) code[s]).var == var
                            && code[s].getOpcode() >= Opcodes.ISTORE && code[s].getOpcode() <= Opcodes.ASTORE;
                    boolean inc = code[s] instanceof IincInsnNode && ((IincInsnNode) code[s]).var == var;
                    if (store || inc) {
                        work.push(code[s]);
                    }
                }
            }
            Frame<SourceValue> before = sources[at];
            if (before != null) {
                int consumed = consumed(insn);
                for (int k = 0; k < consumed && k < before.getStackSize(); k++) {
                    work.addAll(before.getStack(before.getStackSize() - 1 - k).insns);
                }
            }
        }
        return false;
    }

    /** How many stack values the instruction takes: enough of them to follow a value back to what made it. */
    private static int consumed(AbstractInsnNode insn) {
        if (insn instanceof MethodInsnNode) {
            int n = Type.getArgumentTypes(((MethodInsnNode) insn).desc).length;
            return insn.getOpcode() == Opcodes.INVOKESTATIC ? n : n + 1;
        }
        if (insn instanceof VarInsnNode && insn.getOpcode() >= Opcodes.ISTORE) {
            return 1;
        }
        if (insn instanceof InsnNode) {
            int op = insn.getOpcode();
            if (op >= Opcodes.IADD && op <= Opcodes.LXOR || op >= Opcodes.LCMP && op <= Opcodes.DCMPG) {
                return 2;
            }
            if (op >= Opcodes.INEG && op <= Opcodes.DNEG || op >= Opcodes.I2L && op <= Opcodes.I2S) {
                return 1;
            }
        }
        return 0;
    }

    /**
     * Whether the branch at {@code b} decides whether the instruction at {@code target} runs in this pass: it is
     * reached from one side of the branch and not from the other, before the loop comes round.
     */
    private static boolean decides(AbstractInsnNode[] code, Map<LabelNode, Integer> index, Loop loop, int b,
                                   int target) {
        Set<Boolean> reach = new HashSet<>();
        for (int s : successors(code, index, b)) {
            reach.add(reachesWithinPass(code, index, loop, s, target));
        }
        return reach.size() > 1;
    }

    private static boolean reachesWithinPass(AbstractInsnNode[] code, Map<LabelNode, Integer> index, Loop loop,
                                             int from, int target) {
        Deque<Integer> work = new ArrayDeque<>();
        Set<Integer> seen = new HashSet<>();
        work.push(from);
        while (!work.isEmpty()) {
            int i = work.pop();
            if (i == target) {
                return true;
            }
            if (i < loop.from || i > loop.to || (i == loop.from && i != from) || !seen.add(i)) {
                continue;
            }
            for (int t : successors(code, index, i)) {
                work.push(t);
            }
        }
        return false;
    }

    /** Whether the branch at {@code j} decides whether {@code loop} ends: one way leads out, the other round. */
    private static boolean decidesExit(AbstractInsnNode[] code, Map<LabelNode, Integer> index, Loop loop, int j) {
        Set<Boolean> ends = new HashSet<>();
        for (int s : successors(code, index, j)) {
            ends.add(reachesExit(code, index, loop, s));
        }
        return ends.size() > 1;
    }

    /** Whether the branch at {@code j} tests a value a read in the loop shaped. */
    private static boolean testsRead(AbstractInsnNode[] code, int j, Frame<Taint>[] f) {
        if (f == null || f[j] == null) {
            return false;
        }
        for (int k = 0; k < operands(code[j]); k++) {
            Taint v = f[j].getStack(f[j].getStackSize() - 1 - k);
            if (v != null && v.read) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the input sent at {@code i} VARIES WITH WHAT THE LOOP READ: one of the call's arguments was shaped
     * by a read made inside it -- an aim computed from where the target was seen, a command built from a reading.
     * An input that is the same every pass whatever the loop saw is a dose, and a loop that doses until a goal
     * holds is a poll with a stimulus in it.
     */
    private static boolean argumentRead(AbstractInsnNode[] code, int i, Frame<Taint>[] f) {
        if (f == null || f[i] == null || !(code[i] instanceof MethodInsnNode)) {
            return false;
        }
        int n = Type.getArgumentTypes(((MethodInsnNode) code[i]).desc).length;
        for (int k = 0; k < n; k++) {
            Taint v = f[i].getStack(f[i].getStackSize() - 1 - k);
            if (v != null && v.read) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a pass of {@code loop} can come round to its header without sending an input that varies with what
     * it read: one whose argument a read shaped ({@link #argumentRead}), or one the pass reached through a branch
     * on a reading that does not decide the loop's end -- {@code delta(cursor > 0 ? -step : step)} picks its
     * constant by what it read, and is steering as much as {@code delta(-cursor)} is.
     */
    private boolean steeringAvoidable(String methodKey, AbstractInsnNode[] code, Loop loop, Frame<Taint>[] f) {
        Map<LabelNode, Integer> index = labels(code);
        Deque<int[]> work = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        work.push(new int[]{loop.from, 0});
        seen.add((long) loop.from << 1);
        while (!work.isEmpty()) {
            int[] at = work.pop();
            int i = at[0];
            boolean chosen = at[1] == 1;
            if (sends(methodKey, code, i) && (chosen || argumentRead(code, i, f))) {
                continue;
            }
            boolean picks = chosen || (conditional(code[i]) && !decidesExit(code, index, loop, i)
                    && testsRead(code, i, f));
            for (int s : successors(code, index, i)) {
                if (s == loop.from) {
                    return true;
                }
                if (s >= loop.from && s <= loop.to && seen.add(((long) s << 1) | (picks ? 1 : 0))) {
                    work.push(new int[]{s, picks ? 1 : 0});
                }
            }
        }
        return false;
    }

    /** The event log's own classes: a wait through them is a LINK. */
    private static boolean link(String internal) {
        String top = internal.indexOf('$') < 0 ? internal : internal.substring(0, internal.indexOf('$'));
        return "dev/stannismod/stellurgy/test/Events".equals(top);
    }

    // ------------------------------------------------------------------------------------------------
    // judging

    /** One loop: the instructions from its header to its last backward branch. */
    private static final class Loop {
        final int from;
        int to;

        Loop(int from, int to) {
            this.from = from;
            this.to = to;
        }
    }

    /** @return {loops that wait, methods holding one, claims resolved, loops that advance the clock themselves} */
    @SuppressWarnings("unchecked")
    private int[] judge(Map<String, Integer> violating) throws IOException {
        int loops = 0;
        int tickLoops = 0;
        int claims = 0;
        Set<String> withLoops = new TreeSet<>();
        for (ClassNode node : nodes.values()) {
            for (MethodNode m : (List<MethodNode>) node.methods) {
                claims += resolveClaims(node, m);
                if (instrument(node.name)) {
                    continue;
                }
                AbstractInsnNode[] code = m.instructions.toArray();
                List<Loop> all = loops(code);
                for (Loop loop : all) {
                    // TICKS: the loop's own instructions advance the clock -- outside any loop nested in it,
                    // whose own claim says what its ticks are. A loop whose clock is spent only inside
                    // claimed inner loops, or on links, is a loop over cases: it names no shape, and is held
                    // by the exit rule alone.
                    boolean ticks = false;
                    boolean waitsAtAll = false;
                    for (int i = loop.from; i <= loop.to; i++) {
                        boolean advance = advances(code[i]);
                        waitsAtAll |= advance || waits(code[i]);
                        ticks |= advance && !nested(all, loop, i);
                    }
                    if (!waitsAtAll) {
                        continue;
                    }
                    loops++;
                    tickLoops += ticks ? 1 : 0;
                    String owner = claimOwner(node.name, m);
                    withLoops.add(owner);
                    int line = lineAt(code, loop.from);
                    int exits = exits(code, loop);
                    boolean named = !ticks || namedAt(owner, line, null);
                    // THE GOAL EXIT: a loop claimed STIMULUS may leave by a second edge -- its goal -- when
                    // no pass of it can come round without sending input. A loop that only waits keeps one.
                    boolean stimulus = namedAt(owner, line, STIMULUS);
                    boolean everyPassSends = exits == 2 && stimulus
                            && !inputAvoidable(key(node.name, m), code, loop.from, loop.to, loop.from);
                    // WHAT AN EXIT TESTS: the branches that decide whether the loop ends may test only what it
                    // did not read -- its counter, its bound, its cases. A goal exit tests a reading by nature,
                    // and is admitted only when every pass STEERS: sends an input shaped by what it read.
                    Frame<Taint>[] readFrames = readFrames(node.name, m, code, loop);
                    List<Integer> onReads = exitsOnReads(key(node.name, m), code, loop, readFrames);
                    boolean steers = everyPassSends
                            && !steeringAvoidable(key(node.name, m), code, loop, readFrames);
                    String where = display(owner);
                    report.add(where + ":" + line + (ticks ? " TICK" : " CASES") + " exits=" + exits
                            + (named ? (ticks ? " named" : "") : " UNNAMED") + (everyPassSends ? " goal" : "")
                            + (steers ? " steers" : "") + (onReads.isEmpty() ? "" : " exit-on-read" + onReads));
                    if (named && ((exits == 1 && onReads.isEmpty()) || steers)) {
                        continue;
                    }
                    violating.merge(where, 1, Integer::sum);
                    List<String> why = new ArrayList<>();
                    if (!named) {
                        why.add("stands on no line a @Wait of its method names -- a loop that spends the clock"
                                + " says which shape it is, at = the text of its line");
                    }
                    if (everyPassSends && !steers) {
                        why.add("is claimed STIMULUS and leaves by its goal, but a pass of it can come round sending"
                                + " only input that is the same whatever it read -- a dose, not a steer: a loop that"
                                + " doses until a goal holds is a poll with a stimulus in it. Steer by what was read"
                                + " (the input's argument computed from the reading), or claim the doses: an"
                                + " EXPERIMENT of a fixed count, its outcome read once after");
                    } else if (exits == 1 && !onReads.isEmpty()) {
                        why.add("ends on a value it read (the branch at line " + onReads.get(0) + ") -- its one exit"
                                + " is a poll whatever its count: the exit of a loop that waits tests only its"
                                + " counter, its bound or its cases; a link on the record, or a window that runs its"
                                + " length and reads once after");
                    } else if (exits == 2 && stimulus) {
                        why.add("is claimed STIMULUS and leaves by a second edge, its goal, but a pass of it can come"
                                + " round without sending input -- a goal exit is admitted only when every pass is"
                                + " input (a PlayerInput or ClientBot input verb, a fed-input instrument, a"
                                + " stellurgytest TRIGGER, DECIDE or ARRANGE command); a pass that only reads is a"
                                + " poll");
                    } else if (exits != 1) {
                        why.add("leaves its body by " + exits + " edges -- a break, a return or a second header"
                                + " condition on a value it read is a second exit, and a loop that ends on what it"
                                + " read is a poll: a link on the record, or a window that runs its length"
                                + (exits == 2 ? " (a goal exit is admitted for a loop claimed STIMULUS alone)" : ""));
                    }
                    pending.add(new String[]{where, (ticks ? "the tick loop at " : "the loop over waits at ")
                            + node.sourceFile + ":" + line + " in "
                            + where + " " + String.join("; and ", why)});
                }
            }
        }
        return new int[]{loops, withLoops.size(), claims, tickLoops};
    }

    /** Whether instruction {@code i} of {@code outer} lies inside a loop nested in it. */
    private static boolean nested(List<Loop> all, Loop outer, int i) {
        for (Loop inner : all) {
            if (inner != outer && inner.from >= outer.from && inner.to <= outer.to
                    && (inner.from > outer.from || inner.to < outer.to) && inner.from <= i && i <= inner.to) {
                return true;
            }
        }
        return false;
    }

    /** The method whose claims cover {@code m}: the method that writes it, for a lambda body. */
    private String claimOwner(String owner, MethodNode m) {
        String k = key(owner, m);
        for (int depth = 0; depth < 16 && lambdaCreator.containsKey(k); depth++) {
            k = lambdaCreator.get(k);
        }
        return k;
    }

    /** class#method, as the debt list and a failure name it. */
    private static String display(String methodKey) {
        String owner = methodKey.substring(0, methodKey.indexOf('#'));
        String rest = methodKey.substring(methodKey.indexOf('#') + 1);
        return owner.replace('/', '.') + "#" + rest.substring(0, rest.indexOf('('));
    }

    /** Whether a {@link Wait} of the method names {@code line} -- one claiming {@code shape}, when given. */
    private boolean namedAt(String methodKey, int line, String shape) {
        for (Claim claim : waitLines.getOrDefault(methodKey, Collections.<Claim>emptyList())) {
            if (claim.first <= line && line <= claim.last && (shape == null || shape.equals(claim.shape))) {
                return true;
            }
        }
        return false;
    }

    /** Every loop of {@code code}, one per header: the region from a backward branch's target to it. */
    private static List<Loop> loops(AbstractInsnNode[] code) {
        Map<LabelNode, Integer> index = new HashMap<>();
        for (int i = 0; i < code.length; i++) {
            if (code[i] instanceof LabelNode) {
                index.put((LabelNode) code[i], i);
            }
        }
        Map<Integer, Loop> byHeader = new TreeMap<>();
        for (int i = 0; i < code.length; i++) {
            for (LabelNode target : targets(code[i])) {
                Integer t = index.get(target);
                if (t != null && t <= i) {
                    Loop l = byHeader.get(t);
                    if (l == null) {
                        byHeader.put(t, new Loop(t, i));
                    } else {
                        l.to = Math.max(l.to, i);
                    }
                }
            }
        }
        return new ArrayList<>(byHeader.values());
    }

    @SuppressWarnings("unchecked")
    private static List<LabelNode> targets(AbstractInsnNode insn) {
        if (insn instanceof JumpInsnNode) {
            return Collections.singletonList(((JumpInsnNode) insn).label);
        }
        if (insn instanceof TableSwitchInsnNode) {
            List<LabelNode> out = new ArrayList<>(((TableSwitchInsnNode) insn).labels);
            out.add(((TableSwitchInsnNode) insn).dflt);
            return out;
        }
        if (insn instanceof LookupSwitchInsnNode) {
            List<LabelNode> out = new ArrayList<>(((LookupSwitchInsnNode) insn).labels);
            out.add(((LookupSwitchInsnNode) insn).dflt);
            return out;
        }
        return Collections.emptyList();
    }

    /** The edges that leave the loop: each jump to outside it, and each return inside it. */
    private static int exits(AbstractInsnNode[] code, Loop loop) {
        Map<LabelNode, Integer> index = new HashMap<>();
        for (int i = 0; i < code.length; i++) {
            if (code[i] instanceof LabelNode) {
                index.put((LabelNode) code[i], i);
            }
        }
        int exits = 0;
        for (int i = loop.from; i <= loop.to; i++) {
            int op = code[i].getOpcode();
            if (op >= Opcodes.IRETURN && op <= Opcodes.RETURN) {
                exits++;
                continue;
            }
            Set<LabelNode> seen = new HashSet<>();
            for (LabelNode target : targets(code[i])) {
                Integer t = index.get(target);
                if (t != null && (t < loop.from || t > loop.to) && seen.add(target)) {
                    exits++;
                }
            }
        }
        return exits;
    }

    /**
     * The source line of the instruction at {@code i}: a line number that opens at it -- a label is
     * followed by the line it starts, so a {@code while} header's label names the {@code while} line and
     * not the statement before it -- else the nearest line number before it.
     */
    private static int lineAt(AbstractInsnNode[] code, int i) {
        for (int k = i; k < code.length && code[k].getOpcode() < 0; k++) {
            if (code[k] instanceof LineNumberNode) {
                return ((LineNumberNode) code[k]).line;
            }
        }
        for (int k = i; k >= 0; k--) {
            if (code[k] instanceof LineNumberNode) {
                return ((LineNumberNode) code[k]).line;
            }
        }
        for (int k = i; k < code.length; k++) {
            if (code[k] instanceof LineNumberNode) {
                return ((LineNumberNode) code[k]).line;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------------------------------------
    // claims

    /**
     * Resolves the {@link Wait} and {@link ServerOnly} claims on {@code m} against its source: each {@code at}
     * must occur exactly once in the method's text. The WAIT spans land in {@link #waitLines}.
     *
     * @return the number of claims that resolved
     */
    @SuppressWarnings("unchecked")
    private int resolveClaims(ClassNode node, MethodNode m) throws IOException {
        List<AnnotationNode> claims = new ArrayList<>();
        List<Boolean> isWait = new ArrayList<>();
        for (AnnotationNode a : annotations(m)) {
            if (WAIT.equals(a.desc) || SERVER_ONLY.equals(a.desc)) {
                claims.add(a);
                isWait.add(WAIT.equals(a.desc));
            } else if (WAIT_LIST.equals(a.desc) || SERVER_ONLY_LIST.equals(a.desc)) {
                Object v = value(a, "value");
                if (v instanceof List) {
                    for (Object inner : (List<Object>) v) {
                        claims.add((AnnotationNode) inner);
                        isWait.add(WAIT_LIST.equals(a.desc));
                    }
                }
            }
        }
        if (claims.isEmpty()) {
            return 0;
        }
        String where = display(key(node.name, m));
        int[] span = methodSpan(node, m);
        if (span == null) {
            failures.add(where + ": carries a @Wait/@ServerOnly, and its declaration could not be found in "
                    + node.sourceFile + " -- a claim names a line of its method, so the method must be read");
            return 0;
        }
        // The CODE of the method: a fragment inside a comment names nothing, so the match runs on the text
        // with its comments blanked (strings kept -- a call's argument is part of the line it names).
        String text = stripComments(source(node));
        int resolved = 0;
        for (int c = 0; c < claims.size(); c++) {
            AnnotationNode a = claims.get(c);
            String kind = isWait.get(c) ? "@Wait" : "@ServerOnly";
            String at = String.valueOf(value(a, "at"));
            String why = String.valueOf(value(a, "why"));
            if (at.trim().isEmpty() || why.trim().isEmpty()) {
                failures.add(where + ": a " + kind + " with a blank " + (at.trim().isEmpty() ? "at" : "why")
                        + " -- a claim names its line and says why no link answers");
                continue;
            }
            List<int[]> found = matches(fragment(at), text, span);
            String debtKey = where + " " + kind + " " + at;
            if (found.size() != 1 && spanningDebt.contains(debtKey)) {
                // A claim of the debt list: its fragment may cross line breaks, and it still names ONE line,
                // the one its match starts on -- never the span, so no loop below that line is named by it.
                found = matches(spanning(at), text, span);
                if (found.size() == 1) {
                    spanningUsed.add(debtKey);
                    found.set(0, new int[]{found.get(0)[0], found.get(0)[0]});
                }
            } else if (found.size() != 1) {
                spanningNow.add(debtKey);
            }
            if (found.size() != 1) {
                failures.add(where + ": " + kind + "(at = \"" + at + "\") occurs " + found.size() + " times in the"
                        + " method's code -- a claim names exactly one line of it, its words on that one line and"
                        + " outside any comment (a stale claim names none)");
                continue;
            }
            resolved++;
            if (isWait.get(c)) {
                // An enum value reads back as {descriptor, constant name}.
                Object shape = value(a, "shape");
                waitLines.computeIfAbsent(key(node.name, m), x -> new ArrayList<>()).add(new Claim(found.get(0)[0],
                        found.get(0)[1], shape instanceof String[] ? ((String[]) shape)[1] : null));
            }
        }
        return resolved;
    }

    @SuppressWarnings("unchecked")
    private static List<AnnotationNode> annotations(MethodNode m) {
        List<AnnotationNode> out = new ArrayList<>();
        if (m.visibleAnnotations != null) {
            out.addAll(m.visibleAnnotations);
        }
        if (m.invisibleAnnotations != null) {
            out.addAll(m.invisibleAnnotations);
        }
        return out;
    }

    private static Object value(AnnotationNode a, String name) {
        if (a.values == null) {
            return null;
        }
        for (int i = 0; i + 1 < a.values.size(); i += 2) {
            if (name.equals(a.values.get(i))) {
                return a.values.get(i + 1);
            }
        }
        return null;
    }

    /** The [first, last] lines of every match of {@code p} inside {@code span} of {@code text}. */
    private static List<int[]> matches(Pattern p, String text, int[] span) {
        Matcher hit = p.matcher(text);
        hit.region(span[0], span[1]);
        List<int[]> found = new ArrayList<>();
        while (hit.find()) {
            found.add(new int[]{lineOf(text, hit.start()), lineOf(text, hit.end() - 1)});
        }
        return found;
    }

    /** A DEBT claim's fragment: its words in order, any whitespace between them, line breaks included. */
    private static Pattern spanning(String at) {
        List<String> words = new ArrayList<>();
        for (String w : at.trim().split("\\s+")) {
            words.add(Pattern.quote(w));
        }
        return Pattern.compile(String.join("\\s*", words));
    }

    /**
     * The ratchet of the claims whose fragment crossed line breaks when the one-line rule was set: each line
     * of {@value #SPANNING_DEBT} is {@code class#method @Wait|@ServerOnly <at>}. A listed claim that now
     * resolves on one line, or no longer exists, fails until its line is deleted; a claim not listed that
     * does not resolve on one line fails where it is resolved.
     */
    private String checkSpanningDebt() {
        for (String listed : spanningDebt) {
            if (!spanningUsed.contains(listed)) {
                failures.add(SPANNING_DEBT + " lists `" + listed + "`, which no longer needs it (its fragment is on one"
                        + " line now, or the claim is gone or does not resolve) -- delete the line; the list only shrinks");
            }
        }
        return String.format("multi-line claims: %d listed, %d resolved through the list", spanningDebt.size(),
                spanningUsed.size());
    }

    /**
     * A claim's fragment as a pattern: its words in order, spaces and tabs between them free -- and nothing
     * else, so a match lies on ONE source line. A fragment free to cross line breaks spans as many lines as
     * it likes, and a claim on a span names every loop in it, a loop written there later included.
     */
    static Pattern fragment(String at) {
        List<String> words = new ArrayList<>();
        for (String w : at.trim().split("\\s+")) {
            words.add(Pattern.quote(w));
        }
        return Pattern.compile(String.join("[ \\t]*", words));
    }

    /** {@code text} with comments blanked, strings and character literals kept, every offset kept. */
    static String stripComments(String text) {
        String all = strip(text);
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        int n = text.length();
        while (i < n) {
            char ch = text.charAt(i);
            int j;
            if (ch == '"' || ch == '\'') {
                j = i + 1;
                while (j < n && text.charAt(j) != ch && text.charAt(j) != '\n') {
                    j += text.charAt(j) == '\\' ? 2 : 1;
                }
                j = Math.min(j + 1, n);
                out.append(text, i, j);
            } else if (ch == '/' && i + 1 < n && text.charAt(i + 1) == '/') {
                j = text.indexOf('\n', i);
                j = j < 0 ? n : j;
                out.append(all, i, j);
            } else if (ch == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
                j = text.indexOf("*/", i + 2);
                j = j < 0 ? n : j + 2;
                out.append(all, i, j);
            } else {
                j = i + 1;
                out.append(ch);
            }
            i = j;
        }
        return out.toString();
    }

    private String source(ClassNode node) throws IOException {
        String top = node.name.indexOf('$') < 0 ? node.name : node.name.substring(0, node.name.indexOf('$'));
        String text = sources.get(top);
        if (text == null) {
            String pkg = node.name.lastIndexOf('/') < 0 ? "" : node.name.substring(0, node.name.lastIndexOf('/') + 1);
            text = new String(Files.readAllBytes(root.resolve(TEST_ROOT).resolve(pkg + node.sourceFile)),
                    StandardCharsets.UTF_8).replace("\r\n", "\n");
            sources.put(top, text);
        }
        return text;
    }

    private static int lineOf(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /**
     * The offsets [start, end) of {@code m}'s text, from its declaration to its closing brace: the nearest
     * declaration of its name (a constructor's is its class's simple name) at or before its first line of
     * code, its parameter list, its {@code throws}, and its body. Null when there is none.
     */
    @SuppressWarnings("unchecked")
    private int[] methodSpan(ClassNode node, MethodNode m) throws IOException {
        int first = Integer.MAX_VALUE;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LineNumberNode) {
                first = Math.min(first, ((LineNumberNode) insn).line);
            }
        }
        if (first == Integer.MAX_VALUE) {
            return null;
        }
        String name = m.name;
        if ("<init>".equals(name)) {
            String simple = node.name.substring(Math.max(node.name.lastIndexOf('/'), node.name.lastIndexOf('$')) + 1);
            name = simple;
        }
        String text = source(node);
        String clean = strip(text);
        Matcher decl = Pattern.compile("(?<![\\w$.])" + Pattern.quote(name) + "\\s*\\(").matcher(clean);
        int[] best = null;
        while (decl.find()) {
            if (lineOf(clean, decl.start()) > first) {
                break;
            }
            if (clean.substring(0, decl.start()).trim().endsWith("new")) {
                continue;
            }
            int close = balanced(clean, decl.end() - 1, '(', ')');
            if (close < 0) {
                continue;
            }
            Matcher tail = Pattern.compile("\\G\\s*(?:throws\\s+[\\w$.,\\s]+)?\\{").matcher(clean);
            if (!tail.find(close)) {
                continue;
            }
            int open = tail.end() - 1;
            int end = balanced(clean, open, '{', '}');
            if (end > 0 && lineOf(clean, end) >= first) {
                best = new int[]{decl.start(), end};
            }
        }
        return best;
    }

    /** Index just past the group opening at {@code open}; -1 when it does not close. */
    private static int balanced(String s, int open, char o, char c) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == o) {
                depth++;
            } else if (ch == c) {
                depth--;
                if (depth == 0) {
                    return i + 1;
                }
            }
        }
        return -1;
    }

    /** {@code text} with comments, strings and character literals blanked, every offset kept. */
    static String strip(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        int n = text.length();
        while (i < n) {
            char ch = text.charAt(i);
            if (ch == '"' || ch == '\'') {
                int j = i + 1;
                while (j < n && text.charAt(j) != ch && text.charAt(j) != '\n') {
                    j += text.charAt(j) == '\\' ? 2 : 1;
                }
                j = Math.min(j + 1, n);
                for (int k = i; k < j; k++) {
                    out.append(text.charAt(k) == '\n' ? '\n' : ' ');
                }
                i = j;
            } else if (ch == '/' && i + 1 < n && text.charAt(i + 1) == '/') {
                int j = text.indexOf('\n', i);
                j = j < 0 ? n : j;
                for (int k = i; k < j; k++) {
                    out.append(' ');
                }
                i = j;
            } else if (ch == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
                int j = text.indexOf("*/", i + 2);
                j = j < 0 ? n : j + 2;
                for (int k = i; k < j; k++) {
                    out.append(text.charAt(k) == '\n' ? '\n' : ' ');
                }
                i = j;
            } else {
                out.append(ch);
                i++;
            }
        }
        return out.toString();
    }

    // ------------------------------------------------------------------------------------------------
    // the debt

    /**
     * The ratchet: each line of {@value #DEBT} is {@code class#method N}, the number of loops of that method
     * that broke the rule when it was set. A method breaking it more often than its line says -- or not
     * listed -- fails, naming each loop; one breaking it less often fails until its line is lowered.
     */
    private String checkDebt(Map<String, Integer> violating) throws IOException {
        Path file = root.resolve(DEBT);
        if (!Files.isRegularFile(file)) {
            failures.add("the wait-loop debt list " + DEBT + " is missing -- without it every loop that breaks the"
                    + " rule is new");
            for (String[] p : pending) {
                failures.add(p[1]);
            }
            return "wait-loop debt: MISSING";
        }
        Map<String, Integer> debt = new TreeMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String entry = line.trim();
            if (entry.isEmpty() || entry.startsWith("#")) {
                continue;
            }
            String[] parts = entry.split("\\s+");
            if (parts.length != 2 || !parts[1].matches("[1-9]\\d*")) {
                failures.add(DEBT + ": `" + entry + "` is not `class#method N`");
            } else if (debt.put(parts[0], Integer.parseInt(parts[1])) != null) {
                failures.add(DEBT + " lists " + parts[0] + " twice");
            }
        }
        for (Map.Entry<String, Integer> v : violating.entrySet()) {
            Integer owed = debt.get(v.getKey());
            if (owed == null || v.getValue() > owed) {
                for (String[] p : pending) {
                    if (p[0].equals(v.getKey())) {
                        failures.add(p[1] + " (" + DEBT + " holds " + (owed == null ? "no line for" : owed + " for")
                                + " this method, which breaks the rule " + v.getValue() + " time(s); the list only"
                                + " shrinks)");
                    }
                }
            }
        }
        for (Map.Entry<String, Integer> d : debt.entrySet()) {
            int now = violating.getOrDefault(d.getKey(), 0);
            if (now < d.getValue()) {
                failures.add(DEBT + " lists " + d.getKey() + " " + d.getValue() + ", which breaks the rule " + now
                        + " time(s) now (fixed, renamed or gone) -- " + (now == 0 ? "delete the line" : "lower it to "
                        + now));
            }
        }
        int owed = 0;
        for (int n : debt.values()) {
            owed += n;
        }
        int now = 0;
        for (int n : violating.values()) {
            now += n;
        }
        return String.format("wait-loop debt: %d loop(s) in %d method(s) listed, %d breaking the rule now", owed,
                debt.size(), now);
    }
}
