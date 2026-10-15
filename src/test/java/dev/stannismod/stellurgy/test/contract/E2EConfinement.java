package dev.stannismod.stellurgy.test.contract;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * What the code an e2e RUNS may reach, read off the compiled bytecode inside the build -- so a form the
 * gates refuse at the call cannot be performed instead by the e2e's own Java, or by test code it reaches.
 *
 * <p><b>The subject.</b> An e2e is a class on {@code AbstractPlayerE2ETest}. Its code runs in the test JVM,
 * where no child gate sees it: a reflective call on the harness, a property written before the children
 * are launched, a second harness started through a rule, a file rewritten under the build directory. Each
 * of those is a REFERENCE in the class file -- to an owner and a member -- and javac writes every
 * reference the code makes, whatever its source looked like. So this walks, for every e2e, the transitive
 * closure of the test-source classes it references (helpers, nested and anonymous classes, lambdas' bodies,
 * field and annotation types, an intermediate base), and refuses every reference that leaves the test
 * source for an owner outside the e2e's ALLOWLIST: the player's input, the client reads, the e2e base's own
 * protected API, the server facade's {@code read}, {@code arrange} and {@code act}, JUnit's assertions, and plain JDK value, collection,
 * string and math types. Reflection, class loading, system properties and the process, threads, files and
 * sockets, JUnit rules and runners, and every other member of the harness are refused by name.</p>
 *
 * <p><b>Code that runs in the CHILDREN, judged the same way.</b> The instruments an e2e may call by name
 * (the server's {@code E2EInstrument}, the client's {@code ClientInstrument}) are walked from the named
 * methods: they may record and read, and may not write a field outside the test source set nor call the
 * game beyond the reads named here. The test mixins declared OBSERVATIONS may only observe: an
 * {@code @Inject} that cannot cancel, a {@code @Redirect} that forwards the original call unchanged and
 * returns what it returned, a {@code @ModifyArg} that returns its argument unchanged, a reading
 * {@code @Accessor}, an {@code @Invoker}; no write to a shadowed field. A bus subscriber of the test source
 * set is registered only as a class literal, and none of its handlers calls a setter of a Forge event (so
 * none can un-cancel a refusal). No test-source class is a {@code @Mod}, an {@code EventBusSubscriber}, a
 * coremod or a mixin loader, and no test-source class has the name of a class on the runtime classpath
 * (the test output precedes the mod's jar in every child, so such a class would replace it there).</p>
 *
 * <p><b>Also refused.</b> The e2e may not wait on the wall clock, declare {@code @Test(expected = …)}, catch a
 * {@code Throwable} or an {@code Error} and carry on, or return early from a {@code @Test}. A child-side walk
 * follows calls into the harness as well as the test source; a body run on the test's demand publishes no
 * record type a recorder publishes; an observation's helpers write nothing, call no setter, write or call
 * nothing through reflection; a subscriber never takes a command's argument array. No test-source class sits
 * in the harness's packages, and no test RESOURCE has the path of one on the runtime classpath. Mechanics code
 * leaves the JVM's properties, exit, security manager and streams alone; {@code GameTicks}' sleep is admitted
 * in its one advance method only.</p>
 *
 * <p><b>What it does not see.</b> What an ALLOWED call does with its arguments (a string sent through
 * {@code read}, {@code arrange} or {@code act} is judged by the server's gate, an input event by the client bridge); a call a mixin or
 * an instrument makes to a game method that writes, beyond the field writes and the setters (by name)
 * refused here -- the game's methods are too many to classify, and an instrument's calls into the game are
 * listed by name instead; a record type a recorder hands on through a parameter; a failure an e2e turns
 * into a value and never asserts, beyond the handler forms above; native code; and code that is not in a
 * class file the test output holds.</p>
 */
final class E2EConfinement {

    /** The e2e base, as an internal name. */
    static final String E2E_BASE = "com/github/stannismod/forge/testing/junit/AbstractPlayerE2ETest";
    private static final String FRAMEWORK_ROOT = "testframework/src/main/java";
    /** The harness's packages: only {@value #FRAMEWORK_ROOT} declares classes in them. */
    private static final String HARNESS_PACKAGE = "com/github/stannismod/forge/testing/";
    private static final String TEST_ROOT = "src/test/java";
    private static final String SUBSCRIBE = "Lnet/minecraftforge/fml/common/eventhandler/SubscribeEvent;";
    private static final String EVENT = "net/minecraftforge/fml/common/eventhandler/Event";
    private static final String EVENT_BUS = "net/minecraftforge/fml/common/eventhandler/EventBus";
    private static final String MIXIN_PACKAGE = "dev/stannismod/stellurgy/test/mixin/";
    private static final String INJECTION = "Lorg/spongepowered/asm/mixin/injection/";
    private static final String CALLBACK = "org/spongepowered/asm/mixin/injection/callback/";

    private final Path root;
    private final Path testClasses;
    private final ClassLoader loader;
    private final List<String> failures;
    /** Internal name -> the class node, for every class the test output holds. */
    private final Map<String, ClassNode> testNodes = new TreeMap<>();
    /** Internal name -> whether it is test source (src/test/java) rather than the harness. */
    private final Map<String, Boolean> testSource = new HashMap<>();
    /** Classes read off the classpath outside the test output, for the event-hierarchy question. */
    private final Map<String, ClassNode> outside = new HashMap<>();
    /** Internal name -> the classes of the test output below it, as {@link #subtypes} computed them. */
    private final Map<String, Set<String>> subtypesOf = new HashMap<>();
    /** The record types the recorders publish, once {@link #recorderTypes} has read them. */
    private Set<String> recorderTypes;

    private E2EConfinement(Path root, Path testClasses, ClassLoader loader, List<String> failures) {
        this.root = root;
        this.testClasses = testClasses;
        this.loader = loader;
        this.failures = failures;
    }

    /** Every check of this class, appended to {@code failures}; answers a one-line summary to print. */
    static String check(Path root, Path testClasses, ClassLoader loader, List<String> failures,
                        List<String[]> serverInstruments, List<String[]> clientInstruments,
                        List<String[]> mechanicsServerInstruments, List<String[]> mechanicsClientInstruments,
                        Set<String> observationMixins) throws IOException {
        E2EConfinement c = new E2EConfinement(root, testClasses, loader, failures);
        c.readTestOutput();
        int e2es = c.confineE2Es();
        int instruments = c.confineInstruments(serverInstruments, "server");
        instruments += c.confineInstruments(clientInstruments, "client");
        int mechanics = c.confineMechanicsInstruments(mechanicsServerInstruments, "server");
        mechanics += c.confineMechanicsInstruments(mechanicsClientInstruments, "client");
        int mixins = c.confineObservationMixins(observationMixins);
        int subscribers = c.confineSubscribers();
        int shadows = c.refuseShadows();
        int[] mechanicsTests = c.confineMechanicsTests();
        return String.format("confinement: %d e2e class(es) walked, %d instrument method(s), %d mechanics"
                + " instrument method(s), %d observation mixin(s), %d bus subscriber class(es); %d class and"
                + " resource name(s) checked against the runtime classpath; %d mechanics-tier class(es) seeded, %d test-source"
                + " class(es) walked from them", e2es, instruments, mechanics, mixins, subscribers, shadows,
                mechanicsTests[0], mechanicsTests[1]);
    }

    // ------------------------------------------------------------------------------------------------
    // reading

    private void readTestOutput() throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(testClasses)) {
            files = walk.filter(p -> p.toString().endsWith(".class")).sorted().collect(
                    java.util.stream.Collectors.toList());
        }
        for (Path p : files) {
            ClassNode node = new ClassNode();
            new ClassReader(Files.readAllBytes(p)).accept(node, 0);
            if (node.name.endsWith("package-info") || node.name.endsWith("module-info")) {
                continue;
            }
            testNodes.put(node.name, node);
            String pkg = node.name.lastIndexOf('/') < 0 ? "" : node.name.substring(0, node.name.lastIndexOf('/') + 1);
            String source = node.sourceFile == null ? null : pkg + node.sourceFile;
            boolean framework = source != null && Files.isRegularFile(root.resolve(FRAMEWORK_ROOT).resolve(source));
            boolean test = source != null && Files.isRegularFile(root.resolve(TEST_ROOT).resolve(source));
            if (framework == test) {
                failures.add("confinement: the compiled test class " + node.name + " (source " + source + ") cannot"
                        + " be attributed to exactly one of " + TEST_ROOT + " and " + FRAMEWORK_ROOT
                        + " -- what it may reach depends on which it is");
                continue;
            }
            testSource.put(node.name, test);
            if (test && node.name.startsWith(HARNESS_PACKAGE)) {
                failures.add("confinement: the test-source class " + node.name.replace('/', '.') + " is declared in the"
                        + " harness's package " + HARNESS_PACKAGE.replace('/', '.') + " -- a class there reaches the"
                        + " harness's package-private members (VirtualInput, the bridges' tables), which no test may");
            }
        }
    }

    private boolean isTestSource(String internal) {
        return Boolean.TRUE.equals(testSource.get(internal));
    }

    private boolean isFramework(String internal) {
        return Boolean.FALSE.equals(testSource.get(internal));
    }

    /** A class off the runtime classpath (the JDK's, a library's, the game's), or null when none is found. */
    private ClassNode outsideNode(String internal) {
        if (outside.containsKey(internal)) {
            return outside.get(internal);
        }
        ClassNode node = null;
        try (InputStream in = loader.getResourceAsStream(internal + ".class")) {
            if (in != null) {
                node = new ClassNode();
                new ClassReader(in).accept(node, ClassReader.SKIP_CODE);
            }
        } catch (IOException e) {
            node = null;
        }
        outside.put(internal, node);
        return node;
    }

    /** Whether {@code internal} is Forge's {@code CommandEvent} or a class below it. */
    private boolean isCommandEvent(String internal) {
        String at = internal;
        for (int depth = 0; at != null && depth < 32; depth++) {
            if ("net/minecraftforge/event/CommandEvent".equals(at)) {
                return true;
            }
            ClassNode node = testNodes.containsKey(at) ? testNodes.get(at) : outsideNode(at);
            at = node == null ? null : node.superName;
        }
        return false;
    }

    /** Whether {@code internal} is a Forge event, by its superclass chain. */
    private boolean isEvent(String internal) {
        String at = internal;
        for (int depth = 0; at != null && depth < 32; depth++) {
            if (EVENT.equals(at)) {
                return true;
            }
            ClassNode node = testNodes.containsKey(at) ? testNodes.get(at) : outsideNode(at);
            at = node == null ? null : node.superName;
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------------
    // references

    /** One reference a class file makes: an owner, a member ({@code <class>} for the type itself), where. */
    private static final class Ref {
        final String owner;
        final String member;
        final String desc;
        final String where;
        final int opcode;
        /** The method that makes it, as {@code owner#name+desc}; null for a class-level reference. */
        String site;
        /** The call instruction, for a method call; null for any other reference. */
        MethodInsnNode call;

        Ref(String owner, String member, String desc, String where, int opcode) {
            this.owner = owner;
            this.member = member;
            this.desc = desc;
            this.where = where;
            this.opcode = opcode;
        }

        /** The same reference, judged against {@code declaring}, the class that declares its member. */
        Ref declaredBy(String declaring) {
            Ref r = new Ref(declaring, member, desc, where, opcode);
            r.site = site;
            r.call = call;
            return r;
        }
    }

    private static String typeOwner(Type t) {
        while (t.getSort() == Type.ARRAY) {
            t = t.getElementType();
        }
        return t.getSort() == Type.OBJECT ? t.getInternalName() : null;
    }

    private static void addType(List<Ref> out, Type t, String where) {
        String owner = typeOwner(t);
        if (owner != null) {
            out.add(new Ref(owner, "<class>", null, where, -1));
        }
    }

    private static void addDesc(List<Ref> out, String desc, String where) {
        if (desc == null) {
            return;
        }
        Type t = Type.getType(desc);
        if (t.getSort() == Type.METHOD) {
            for (Type arg : t.getArgumentTypes()) {
                addType(out, arg, where);
            }
            addType(out, t.getReturnType(), where);
        } else {
            addType(out, t, where);
        }
    }

    private static void addAnnotations(List<Ref> out, List<AnnotationNode> annotations, String where) {
        if (annotations == null) {
            return;
        }
        for (AnnotationNode a : annotations) {
            addDesc(out, a.desc, where + " @" + a.desc);
            addAnnotationValues(out, a.values, where + " @" + a.desc);
        }
    }

    private static void addAnnotationValues(List<Ref> out, List<Object> values, String where) {
        if (values == null) {
            return;
        }
        for (int i = 1; i < values.size(); i += 2) {
            addAnnotationValue(out, values.get(i), where);
        }
    }

    @SuppressWarnings("unchecked")
    private static void addAnnotationValue(List<Ref> out, Object value, String where) {
        if (value instanceof Type) {
            addType(out, (Type) value, where);
        } else if (value instanceof String[]) {
            addDesc(out, ((String[]) value)[0], where);
        } else if (value instanceof AnnotationNode) {
            addAnnotations(out, Collections.singletonList((AnnotationNode) value), where);
        } else if (value instanceof List) {
            for (Object element : (List<Object>) value) {
                addAnnotationValue(out, element, where);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Ref> classLevelRefs(ClassNode node) {
        List<Ref> out = new ArrayList<>();
        String where = node.name;
        if (node.superName != null) {
            out.add(new Ref(node.superName, "<class>", null, where + " extends", -1));
        }
        for (String i : (List<String>) node.interfaces) {
            out.add(new Ref(i, "<class>", null, where + " implements", -1));
        }
        addAnnotations(out, node.visibleAnnotations, where);
        addAnnotations(out, node.invisibleAnnotations, where);
        for (FieldNode f : (List<FieldNode>) node.fields) {
            String fw = where + "." + f.name;
            addDesc(out, f.desc, fw);
            addAnnotations(out, f.visibleAnnotations, fw);
            addAnnotations(out, f.invisibleAnnotations, fw);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Ref> methodRefs(ClassNode node, MethodNode m) {
        List<Ref> out = new ArrayList<>();
        String where = node.name + "#" + m.name;
        addDesc(out, m.desc, where);
        if (m.exceptions != null) {
            for (String e : (List<String>) m.exceptions) {
                out.add(new Ref(e, "<class>", null, where + " throws", -1));
            }
        }
        addAnnotations(out, m.visibleAnnotations, where);
        addAnnotations(out, m.invisibleAnnotations, where);
        if (m.visibleParameterAnnotations != null) {
            for (List<AnnotationNode> p : m.visibleParameterAnnotations) {
                addAnnotations(out, p, where);
            }
        }
        if (m.invisibleParameterAnnotations != null) {
            for (List<AnnotationNode> p : m.invisibleParameterAnnotations) {
                addAnnotations(out, p, where);
            }
        }
        if (m.annotationDefault != null) {
            addAnnotationValue(out, m.annotationDefault, where);
        }
        for (TryCatchBlockNode t : (List<TryCatchBlockNode>) m.tryCatchBlocks) {
            if (t.type != null) {
                out.add(new Ref(t.type, "<class>", null, where + " catch", -1));
            }
        }
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode) {
                MethodInsnNode mi = (MethodInsnNode) insn;
                String owner = mi.owner.startsWith("[") ? typeOwner(Type.getType(mi.owner)) : mi.owner;
                if (owner != null) {
                    Ref call = new Ref(owner, mi.name, mi.desc, where, mi.getOpcode());
                    call.call = mi;
                    out.add(call);
                }
            } else if (insn instanceof FieldInsnNode) {
                FieldInsnNode fi = (FieldInsnNode) insn;
                out.add(new Ref(fi.owner, fi.name, fi.desc, where, fi.getOpcode()));
            } else if (insn instanceof TypeInsnNode) {
                TypeInsnNode ti = (TypeInsnNode) insn;
                String owner = ti.desc.startsWith("[") ? typeOwner(Type.getType(ti.desc)) : ti.desc;
                if (owner != null) {
                    out.add(new Ref(owner, ti.getOpcode() == Opcodes.NEW ? "<init>" : "<class>", null, where,
                            ti.getOpcode()));
                }
            } else if (insn instanceof LdcInsnNode) {
                Object cst = ((LdcInsnNode) insn).cst;
                if (cst instanceof Type && ((Type) cst).getSort() != Type.METHOD) {
                    addType(out, (Type) cst, where + " (class literal)");
                } else if (cst instanceof Handle) {
                    Handle h = (Handle) cst;
                    out.add(new Ref(h.getOwner(), h.getName(), h.getDesc(), where + " (method handle)", -1));
                }
            } else if (insn instanceof InvokeDynamicInsnNode) {
                InvokeDynamicInsnNode indy = (InvokeDynamicInsnNode) insn;
                Handle bsm = indy.bsm;
                out.add(new Ref(bsm.getOwner(), bsm.getName(), bsm.getDesc(), where + " (bootstrap)", Opcodes.INVOKEDYNAMIC));
                for (Object arg : indy.bsmArgs) {
                    if (arg instanceof Handle) {
                        Handle h = (Handle) arg;
                        out.add(new Ref(h.getOwner(), h.getName(), h.getDesc(), where + " (lambda body)", -1));
                    } else if (arg instanceof Type && ((Type) arg).getSort() != Type.METHOD) {
                        addType(out, (Type) arg, where);
                    }
                }
            } else if (insn instanceof MultiANewArrayInsnNode) {
                addDesc(out, ((MultiANewArrayInsnNode) insn).desc, where);
            }
        }
        String site = node.name + "#" + m.name + m.desc;
        for (Ref r : out) {
            r.site = site;
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------
    // the e2e closure

    private int confineE2Es() {
        Set<String> seeds = new TreeSet<>();
        for (String name : testNodes.keySet()) {
            if (isTestSource(name) && reachesE2EBase(name)) {
                seeds.add(name);
            }
        }
        for (String seed : seeds) {
            walkE2E(seed);
        }
        return seeds.size();
    }

    private boolean reachesE2EBase(String internal) {
        String at = internal;
        for (int depth = 0; at != null && depth < 32; depth++) {
            ClassNode node = testNodes.get(at);
            if (node == null) {
                return false;
            }
            if (E2E_BASE.equals(node.superName)) {
                return true;
            }
            at = node.superName;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private void walkE2E(String seed) {
        Map<String, String> reachedFrom = new LinkedHashMap<>();
        Deque<String> todo = new ArrayDeque<>();
        reachedFrom.put(seed, null);
        todo.add(seed);
        Set<String> reported = new TreeSet<>();
        while (!todo.isEmpty()) {
            String at = todo.poll();
            ClassNode node = testNodes.get(at);
            List<Ref> refs = new ArrayList<>(classLevelRefs(node));
            for (MethodNode m : (List<MethodNode>) node.methods) {
                refs.addAll(methodRefs(node, m));
                for (String swallow : swallowsItsFailure(node, m)) {
                    String line = "confinement: the e2e " + seed.replace('/', '.') + " " + swallow + " (at " + node.name
                            + "#" + m.name + "; reached by " + path(reachedFrom, at) + ")";
                    if (reported.add(line)) {
                        failures.add(line);
                    }
                }
            }
            for (Ref ref : refs) {
                // A member named through a test-source owner may be INHERITED from the harness or the JDK:
                // javac writes the class it was called on, so the declaring class is found by climbing.
                Ref r = ref;
                if (isTestSource(ref.owner) && !"<class>".equals(ref.member)) {
                    String declaring = declaringOwner(ref.owner, ref.member, ref.desc);
                    if (!declaring.equals(ref.owner)) {
                        r = ref.declaredBy(declaring);
                    }
                }
                if (isTestSource(r.owner)) {
                    for (String owner : new String[]{ref.owner, r.owner}) {
                        if (!reachedFrom.containsKey(owner)) {
                            reachedFrom.put(owner, at);
                            todo.add(owner);
                        }
                    }
                    continue;
                }
                if (isTestSource(ref.owner) && !reachedFrom.containsKey(ref.owner)) {
                    reachedFrom.put(ref.owner, at);
                    todo.add(ref.owner);
                }
                String why = e2eRefusal(r);
                if (why != null) {
                    String line = "confinement: the e2e " + seed.replace('/', '.') + " reaches " + r.owner.replace('/', '.')
                            + (r.member.equals("<class>") ? "" : "#" + r.member) + " -- " + why + " (at " + r.where
                            + "; reached by " + path(reachedFrom, at) + ")";
                    if (reported.add(line)) {
                        failures.add(line);
                    }
                }
            }
        }
    }

    /**
     * The ways a method of e2e code can turn its own failure into a pass, each read off the class file:
     * <ul>
     *   <li>a {@code @Test(expected = …)} -- the test passes on a throw, so the throw an assertion makes
     *       is its green;</li>
     *   <li>a handler for {@code Throwable} or for an {@code Error} (an {@code AssertionError} and its
     *       subclasses among them) -- an exception-table entry, so a {@code catch}, a multi-catch and a
     *       {@code catch} a try-with-resources writes are one form: what a failed assertion throws is
     *       caught where it was thrown, whatever the handler then does;</li>
     *   <li>an EARLY RETURN from a {@code @Test} method -- more than one return instruction in it: javac
     *       writes one at the end of a body and one more for every {@code return} statement, so a second
     *       one is a path that ends the test before its last line, whether or not an assertion stood
     *       before it.</li>
     * </ul>
     * Not decidable here, so not refused: a {@code break} out of a labelled block that jumps over the
     * assertions; a helper that returns early (a helper is not the test's end); a {@code finally} that
     * returns (javac writes it as a handler of no type).
     */
    private List<String> swallowsItsFailure(ClassNode node, MethodNode m) {
        List<String> out = new ArrayList<>();
        AnnotationNode test = annotation(m.visibleAnnotations, m.invisibleAnnotations, "Lorg/junit/Test;");
        if (test != null && value(test, "expected") != null) {
            out.add("declares @Test(expected = …) on " + m.name + " -- an e2e that passes on a throw passes on the"
                    + " throw its own failed assertion makes");
        }
        for (Object o : m.tryCatchBlocks) {
            TryCatchBlockNode t = (TryCatchBlockNode) o;
            if (t.type != null && catchesAFailure(t.type) && !handlerAlwaysThrows(m, t)
                    && !diagnosticReader(node.name + "#" + m.name + m.desc)) {
                String line = "catches " + t.type.replace('/', '.') + " in " + m.name + " and can carry on -- a path"
                        + " from the handler completes without a throw, so what a failed assertion threw is swallowed"
                        + " and the e2e can go on to a green it did not earn (a handler that throws again, typed or"
                        + " with more to say, is admitted)";
                if (!out.contains(line)) {
                    out.add(line);
                }
            }
        }
        if (test != null) {
            int returns = 0;
            for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                int op = insn.getOpcode();
                if (op >= Opcodes.IRETURN && op <= Opcodes.RETURN) {
                    returns++;
                }
            }
            if (returns > 1) {
                out.add("returns early from its @Test " + m.name + " (" + returns + " return instructions) -- a path"
                        + " that ends the test before its last line is a verdict nobody asserted");
            }
        }
        return out;
    }

    /**
     * The one NAMED exception to the swallow rule: a reader of a failure message that must never throw,
     * because it is read while an assertion's message is built -- on the green path too -- and a reader
     * that threw there would replace the verdict it was asked to explain. It returns the caught throwable
     * in its text, and decides nothing. A new entry is a new reader of that kind, argued here.
     */
    private static boolean diagnosticReader(String method) {
        return "dev/stannismod/stellurgy/test/client/SeatDelivery#half(Z)Ljava/lang/String;".equals(method);
    }

    /**
     * Whether every path from the handler of {@code t} ends in a throw -- the caught failure thrown again,
     * as it is or typed -- rather than in a return or back in the method's normal flow. Exception edges and
     * what a called method does are not followed: a call that always throws counts as a path that goes on.
     */
    private static boolean handlerAlwaysThrows(MethodNode m, TryCatchBlockNode t) {
        AbstractInsnNode[] code = m.instructions.toArray();
        Map<LabelNode, Integer> index = new HashMap<>();
        for (int i = 0; i < code.length; i++) {
            if (code[i] instanceof LabelNode) {
                index.put((LabelNode) code[i], i);
            }
        }
        Integer start = index.get(t.handler);
        if (start == null) {
            return false;
        }
        Deque<Integer> work = new ArrayDeque<>();
        Set<Integer> seen = new HashSet<>();
        work.push(start);
        seen.add(start);
        while (!work.isEmpty()) {
            int i = work.pop();
            int op = code[i].getOpcode();
            if (op >= Opcodes.IRETURN && op <= Opcodes.RETURN) {
                return false;
            }
            if (op == Opcodes.ATHROW) {
                continue;
            }
            List<LabelNode> targets = new ArrayList<>();
            if (code[i] instanceof JumpInsnNode) {
                targets.add(((JumpInsnNode) code[i]).label);
            } else if (code[i] instanceof TableSwitchInsnNode) {
                targets.addAll(((TableSwitchInsnNode) code[i]).labels);
                targets.add(((TableSwitchInsnNode) code[i]).dflt);
            } else if (code[i] instanceof LookupSwitchInsnNode) {
                targets.addAll(((LookupSwitchInsnNode) code[i]).labels);
                targets.add(((LookupSwitchInsnNode) code[i]).dflt);
            }
            List<Integer> next = new ArrayList<>();
            for (LabelNode l : targets) {
                Integer at = index.get(l);
                if (at != null) {
                    next.add(at);
                }
            }
            boolean falls = op != Opcodes.GOTO && !(code[i] instanceof TableSwitchInsnNode)
                    && !(code[i] instanceof LookupSwitchInsnNode);
            if (falls) {
                if (i + 1 >= code.length) {
                    return false;
                }
                next.add(i + 1);
            }
            for (int n : next) {
                if (seen.add(n)) {
                    work.push(n);
                }
            }
        }
        return true;
    }

    /** {@code Throwable}, or a class whose superclass chain reaches {@code Error}. */
    private boolean catchesAFailure(String type) {
        if ("java/lang/Throwable".equals(type)) {
            return true;
        }
        String at = type;
        for (int depth = 0; at != null && depth < 32; depth++) {
            if ("java/lang/Error".equals(at)) {
                return true;
            }
            ClassNode n = testNodes.containsKey(at) ? testNodes.get(at) : outsideNode(at);
            at = n == null ? null : n.superName;
        }
        return false;
    }

    /**
     * The class that declares {@code member} as seen from {@code owner}: {@code owner} itself, a test-source
     * superclass, or the first class above them that is not test source (the harness's, the JDK's), which
     * is then the owner the reference is judged against. A field is matched by name, a method by name and
     * descriptor.
     */
    @SuppressWarnings("unchecked")
    private String declaringOwner(String owner, String member, String desc) {
        boolean method = desc != null && desc.startsWith("(");
        String at = owner;
        for (int depth = 0; depth < 32; depth++) {
            ClassNode node = testNodes.get(at);
            if (node == null || !isTestSource(at) || "<init>".equals(member) || "<clinit>".equals(member)) {
                return at;
            }
            if (method) {
                for (MethodNode m : (List<MethodNode>) node.methods) {
                    if (m.name.equals(member) && m.desc.equals(desc)) {
                        return at;
                    }
                }
            } else {
                for (FieldNode f : (List<FieldNode>) node.fields) {
                    if (f.name.equals(member)) {
                        return at;
                    }
                }
            }
            if (node.superName == null) {
                return at;
            }
            at = node.superName;
        }
        return at;
    }

    /**
     * As {@link #declaringOwner}, climbing through harness classes as well as test-source ones: the class of
     * the test output that declares {@code member}, or the first class above it that the output does not hold.
     */
    @SuppressWarnings("unchecked")
    private String declaringInTree(String owner, String member, String desc) {
        boolean method = desc != null && desc.startsWith("(");
        String at = owner;
        for (int depth = 0; depth < 32; depth++) {
            ClassNode node = testNodes.get(at);
            if (node == null || "<init>".equals(member) || "<clinit>".equals(member)) {
                return at;
            }
            if (method) {
                for (MethodNode m : (List<MethodNode>) node.methods) {
                    if (m.name.equals(member) && m.desc.equals(desc)) {
                        return at;
                    }
                }
            } else {
                for (FieldNode f : (List<FieldNode>) node.fields) {
                    if (f.name.equals(member)) {
                        return at;
                    }
                }
            }
            if (node.superName == null) {
                return at;
            }
            at = node.superName;
        }
        return at;
    }

    /** Every class of the test output (test source or harness) below {@code internal}, transitively. */
    @SuppressWarnings("unchecked")
    private Set<String> subtypes(String internal) {
        Set<String> known = subtypesOf.get(internal);
        if (known != null) {
            return known;
        }
        Set<String> out = new TreeSet<>();
        subtypesOf.put(internal, out);
        Deque<String> todo = new ArrayDeque<>();
        todo.add(internal);
        while (!todo.isEmpty()) {
            String at = todo.poll();
            for (ClassNode node : testNodes.values()) {
                if (testSource.containsKey(node.name) && !out.contains(node.name)
                        && (at.equals(node.superName) || ((List<String>) node.interfaces).contains(at))) {
                    out.add(node.name);
                    todo.add(node.name);
                }
            }
        }
        return out;
    }

    private static String path(Map<String, String> reachedFrom, String at) {
        List<String> chain = new ArrayList<>();
        for (String c = at; c != null; c = reachedFrom.get(c)) {
            chain.add(0, c.substring(c.lastIndexOf('/') + 1));
        }
        return String.join(" -> ", chain);
    }

    /** Why the reference {@code r}, made by e2e code, is refused; {@code null} when the allowlist admits it. */
    private String e2eRefusal(Ref r) {
        if (clockAdvanceSleep(r)) {
            return null;
        }
        String forbidden = forbiddenCategory(r);
        if (forbidden != null) {
            return forbidden;
        }
        if (isFramework(r.owner)) {
            return frameworkAdmits(r.owner, r.member) ? null
                    : "the harness outside an e2e's surface (PlayerInput, ClientReads, the e2e base's protected API,"
                    + " TestClient#read/arrange/act, PlayerStart)";
        }
        if (r.opcode == Opcodes.INVOKEDYNAMIC) {
            return lambdaBootstrap(r) ? null : "an invokedynamic bootstrap other than the lambda factory";
        }
        return jdkOrLibraryAdmits(r.owner, r.member) ? null
                : "outside the e2e allowlist (JDK value, collection, string and math types, gson's tree, JUnit's"
                + " Assert and @Test/@Before/@After) -- the game, the mod and every other library are reached only"
                + " through the server's commands and the client's input and reads";
    }

    private static boolean lambdaBootstrap(Ref r) {
        return "java/lang/invoke/LambdaMetafactory".equals(r.owner)
                && ("metafactory".equals(r.member) || "altMetafactory".equals(r.member));
    }

    /**
     * The categories refused by name, wherever they are reached from: what would let test code act outside
     * the gates. {@code null} when {@code r} is in none of them.
     */
    private String forbiddenCategory(Ref r) {
        String o = r.owner;
        String m = r.member;
        if (r.opcode == Opcodes.INVOKEDYNAMIC) {
            return null;
        }
        if (o.startsWith("java/lang/reflect/") || o.startsWith("sun/reflect/") || o.startsWith("jdk/internal/")
                || o.startsWith("sun/misc/") || (o.startsWith("java/lang/invoke/") && !lambdaBootstrapOwner(o))) {
            return "REFLECTION (java.lang.reflect / invoke / Unsafe)";
        }
        if (m.startsWith("getDeclared") || "setAccessible".equals(m) || "forName".equals(m)
                || ("java/lang/Class".equals(o) && !classAdmits(m))) {
            return "REFLECTION (Class members, forName, getDeclared*, setAccessible)";
        }
        if ("java/lang/ClassLoader".equals(o) || "java/net/URLClassLoader".equals(o)
                || "java/util/ServiceLoader".equals(o) || "java/lang/instrument/Instrumentation".equals(o)) {
            return "CLASS LOADING";
        }
        if ("java/lang/System".equals(o) && !systemAdmits(m)) {
            return "SYSTEM STATE (System." + m + ": properties, environment, streams, exit)";
        }
        if ("java/lang/Runtime".equals(o) || "java/lang/ProcessBuilder".equals(o) || "java/lang/Process".equals(o)
                || o.startsWith("java/lang/ProcessBuilder$") || "java/lang/SecurityManager".equals(o)
                || o.startsWith("java/lang/management/") || o.startsWith("javax/management/")) {
            return "THE PROCESS (Runtime, ProcessBuilder, Process, management)";
        }
        String wallClock = wallClockWait(r);
        if (wallClock != null) {
            return wallClock;
        }
        if (("java/lang/Thread".equals(o) && !threadAdmits(m)) || "java/lang/ThreadGroup".equals(o)
                || o.startsWith("java/lang/Thread$") || "java/util/Timer".equals(o) || "java/util/TimerTask".equals(o)
                || (o.startsWith("java/util/concurrent/") && !concurrentAdmits(o))
                || "parallel".equals(m) || "parallelStream".equals(m)) {
            return "THREADS (Thread, executors, timers, parallel streams)";
        }
        if ((o.startsWith("java/io/") && !ioAdmits(o, m)) || o.startsWith("java/nio/file/")
                || o.startsWith("java/nio/channels/") || o.startsWith("java/net/") || o.startsWith("javax/net/")) {
            return "FILES AND SOCKETS (java.io beyond PrintStream output, java.nio.file, java.net)";
        }
        if ("org/junit/Rule".equals(o) || "org/junit/ClassRule".equals(o) || o.startsWith("org/junit/runner/")
                || o.startsWith("org/junit/rules/") || o.startsWith("org/junit/runners/")
                || o.startsWith("org/junit/internal/runners/") || "org/junit/Assume".equals(o)
                || o.endsWith("AssumptionViolatedException") || "org/junit/BeforeClass".equals(o)
                || "org/junit/AfterClass".equals(o)) {
            return "A JUNIT RULE, RUNNER, LISTENER OR ASSUMPTION (a second harness, or a skip)";
        }
        if (isFramework(o)) {
            String simple = o.substring(o.lastIndexOf('/') + 1);
            if (simple.startsWith("HarnessBoot") || simple.startsWith("ClientBot") || simple.startsWith("ClientChannel")
                    || simple.startsWith("RealClientHarness") || simple.startsWith("RealDedicatedServerHarness")
                    || simple.startsWith("AbstractMechanicsTest") || simple.startsWith("TestClassScope")
                    || simple.startsWith("AbstractHeadlessServerTest") || simple.startsWith("AbstractOwnBootClientTest")
                    || m.startsWith("start")) {
                return "A HARNESS START OR THE BYPASS CLIENT (HarnessBoot, start*, ClientBot, ClientChannel, a"
                        + " mechanics base)";
            }
        }
        return null;
    }

    /**
     * The members of {@code Class} that neither load, initialise nor open a class: its name, a typed cast and
     * an instance test on a class literal the code already holds, and javac's own assertion switch.
     */
    private static boolean classAdmits(String member) {
        switch (member) {
            case "<class>":
            case "getName":
            case "getSimpleName":
            case "cast":
            case "isInstance":
            case "isAssignableFrom":
            case "desiredAssertionStatus":
                return true;
            default:
                return false;
        }
    }

    private static boolean lambdaBootstrapOwner(String owner) {
        return "java/lang/invoke/LambdaMetafactory".equals(owner);
    }

    private static boolean systemAdmits(String member) {
        switch (member) {
            case "currentTimeMillis":
            case "nanoTime":
            case "out":
            case "err":
            case "lineSeparator":
            case "arraycopy":
            case "identityHashCode":
                return true;
            default:
                return false;
        }
    }

    /**
     * Why {@code r} pauses the calling thread on the WALL clock, or {@code null}: {@code Thread.sleep},
     * {@code TimeUnit.sleep / timedWait / timedJoin}, {@code Object.wait} on any receiver, and every member of
     * {@code java.util.concurrent.locks} (a {@code LockSupport} park, a {@code Condition} await, a lock). A
     * wait on machine time is a budget about the machine; a test waits on the game's clock or on a record.
     */
    static String wallClockWait(Ref r) {
        String o = r.owner;
        String m = r.member;
        boolean sleep = "sleep".equals(m) && ("java/lang/Thread".equals(o) || "java/util/concurrent/TimeUnit".equals(o));
        boolean timed = ("timedWait".equals(m) || "timedJoin".equals(m)) && "java/util/concurrent/TimeUnit".equals(o);
        boolean objectWait = "wait".equals(m) && ("()V".equals(r.desc) || "(J)V".equals(r.desc) || "(JI)V".equals(r.desc));
        if (sleep || timed || objectWait || o.startsWith("java/util/concurrent/locks/")) {
            return "A WALL-CLOCK WAIT (Thread.sleep, TimeUnit.sleep, Object.wait, a lock's park or await): a wait is a"
                    + " link on a published record or a window of two reads on the game's clock";
        }
        return null;
    }

    private static boolean threadAdmits(String member) {
        switch (member) {
            case "currentThread":
            case "interrupt":
            case "isInterrupted":
            case "interrupted":
                return true;
            default:
                return false;
        }
    }

    private static boolean concurrentAdmits(String owner) {
        if (owner.startsWith("java/util/concurrent/atomic/")) {
            return true;
        }
        switch (owner) {
            case "java/util/concurrent/ConcurrentHashMap":
            case "java/util/concurrent/ConcurrentHashMap$KeySetView":
            case "java/util/concurrent/ConcurrentMap":
            case "java/util/concurrent/TimeUnit":
            case "java/util/concurrent/CopyOnWriteArrayList":
            case "java/util/concurrent/ConcurrentLinkedQueue":
            case "java/util/concurrent/ConcurrentLinkedDeque":
            case "java/util/concurrent/Callable":
                return true;
            default:
                return false;
        }
    }

    private static boolean ioAdmits(String owner, String member) {
        switch (owner) {
            case "java/io/PrintStream":
                return !"<init>".equals(member);
            case "java/io/IOException":
            case "java/io/UncheckedIOException":
            case "java/io/Serializable":
            case "java/io/Closeable":
            case "java/io/EOFException":
                return true;
            default:
                return false;
        }
    }

    /** The e2e's surface of the harness, by owner and member. */
    private static boolean frameworkAdmits(String owner, String member) {
        String base = "com/github/stannismod/forge/testing/";
        switch (owner.startsWith(base) ? owner.substring(base.length()) : owner) {
            case "client/PlayerInput":
            case "client/ClientReads":
                return true;
            case "server/TestClient":
                return "<class>".equals(member) || "read".equals(member) || "arrange".equals(member)
                        || "act".equals(member);
            case "junit/AbstractPlayerE2ETest":
                switch (member) {
                    case "<class>":
                    case "<init>":
                    case "input":
                    case "client":
                    case "server":
                    case "playerStart":
                        return true;
                    default:
                        return false;
                }
            case "junit/PlayerStart":
                return "<class>".equals(member) || "asShipped".equals(member) || "config".equals(member)
                        || "harvested".equals(member);
            case "junit/PlayerStart$XmlInsertion":
                return "<class>".equals(member) || "under".equals(member);
            case "server/ServerCommandFailed":
            case "client/ClientCommandRefused":
                return "<class>".equals(member) || "reply".equals(member) || "getMessage".equals(member)
                        || "toString".equals(member);
            default:
                return false;
        }
    }

    /** JDK value, collection, string and math types, gson's tree, JUnit's assertions and test annotations. */
    private static boolean jdkOrLibraryAdmits(String owner, String member) {
        if (owner.startsWith("java/util/function/") || owner.startsWith("java/util/stream/")
                || owner.startsWith("java/util/regex/") || owner.startsWith("java/util/concurrent/atomic/")
                || owner.startsWith("java/time/") || owner.startsWith("java/math/") || owner.startsWith("java/text/")
                || owner.startsWith("java/lang/annotation/")) {
            return true;
        }
        if (owner.startsWith("java/util/") && owner.indexOf('/', "java/util/".length()) < 0) {
            return true;
        }
        if (owner.startsWith("java/util/concurrent/")) {
            return concurrentAdmits(owner);
        }
        if (owner.startsWith("java/io/")) {
            return ioAdmits(owner, member);
        }
        switch (owner) {
            case "java/lang/Object":
            case "java/lang/String":
            case "java/lang/StringBuilder":
            case "java/lang/StringBuffer":
            case "java/lang/AbstractStringBuilder":
            case "java/lang/CharSequence":
            case "java/lang/Character":
            case "java/lang/Boolean":
            case "java/lang/Byte":
            case "java/lang/Short":
            case "java/lang/Integer":
            case "java/lang/Long":
            case "java/lang/Float":
            case "java/lang/Double":
            case "java/lang/Number":
            case "java/lang/Math":
            case "java/lang/StrictMath":
            case "java/lang/Comparable":
            case "java/lang/Iterable":
            case "java/lang/Enum":
            case "java/lang/Void":
            case "java/lang/AutoCloseable":
            case "java/lang/Runnable":
            case "java/lang/Cloneable":
            case "java/lang/Throwable":
            case "java/lang/Exception":
            case "java/lang/RuntimeException":
            case "java/lang/Error":
            case "java/lang/AssertionError":
            case "java/lang/IllegalStateException":
            case "java/lang/IllegalArgumentException":
            case "java/lang/UnsupportedOperationException":
            case "java/lang/NullPointerException":
            case "java/lang/IndexOutOfBoundsException":
            case "java/lang/ArrayIndexOutOfBoundsException":
            case "java/lang/ArithmeticException":
            case "java/lang/NumberFormatException":
            case "java/lang/ClassCastException":
            case "java/lang/InterruptedException":
            case "java/lang/CloneNotSupportedException":
            case "java/lang/NoSuchFieldError":
            case "java/lang/StackTraceElement":
            case "java/lang/Deprecated":
            case "java/lang/FunctionalInterface":
            case "java/lang/SafeVarargs":
            case "java/lang/Class":
            case "java/lang/System":
            case "java/lang/Thread":
            case "com/google/gson/JsonObject":
            case "com/google/gson/JsonElement":
            case "com/google/gson/JsonArray":
            case "com/google/gson/JsonPrimitive":
            case "com/google/gson/JsonNull":
            case "com/google/gson/JsonParser":
            case "com/google/gson/JsonParseException":
            case "com/google/gson/JsonSyntaxException":
            case "org/junit/Assert":
            case "org/junit/Test":
            case "org/junit/Before":
            case "org/junit/After":
            case "org/junit/ComparisonFailure":
                return true;
            default:
                return false;
        }
    }

    // ------------------------------------------------------------------------------------------------
    // the mechanics tier

    /**
     * Walk the mechanics tier and the test-source classes it reaches (helpers, nested and anonymous classes,
     * lambda bodies), refusing by reference: a write to a static outside the test output (state
     * of the game or the mod the test does not own), a thread / executor / timer, a wall-clock sleep, the
     * process, and reflection. The harness is the mechanics tier's surface and is not judged here; a wall
     * clock READ (a nonce, a diagnostic) is not a wait and is admitted.
     *
     * <p>What it does not see: an INSTANCE write to an object the test did not build (ownership is not in
     * bytecode); what a probe does in the child; code reached only through the harness.</p>
     *
     * @return {seeds, classes walked}
     */
    @SuppressWarnings("unchecked")
    private int[] confineMechanicsTests() {
        Map<String, String> reachedFrom = new LinkedHashMap<>();
        Deque<String> todo = new ArrayDeque<>();
        int seeds = 0;
        for (String name : testNodes.keySet()) {
            if (isTestSource(name) && inMechanicsPackage(name) && !reachesE2EBase(outermost(name))) {
                reachedFrom.put(name, null);
                todo.add(name);
                seeds++;
            }
        }
        Set<String> reported = new TreeSet<>();
        while (!todo.isEmpty()) {
            String at = todo.poll();
            ClassNode node = testNodes.get(at);
            for (String implemented : (List<String>) node.interfaces) {
                String typeRefusal = gameDirectoryTypeRefusal(at, implemented);
                if (typeRefusal != null && reported.add(typeRefusal)) {
                    failures.add(typeRefusal);
                }
            }
            List<Ref> refs = new ArrayList<>(classLevelRefs(node));
            for (MethodNode m : (List<MethodNode>) node.methods) {
                refs.addAll(methodRefs(node, m));
            }
            for (Ref ref : refs) {
                Ref r = ref;
                if (isTestSource(ref.owner) && !"<class>".equals(ref.member)) {
                    String declaring = declaringOwner(ref.owner, ref.member, ref.desc);
                    if (!declaring.equals(ref.owner)) {
                        r = ref.declaredBy(declaring);
                    }
                }
                for (String owner : new String[]{ref.owner, r.owner}) {
                    if (isTestSource(owner) && !reachedFrom.containsKey(owner)) {
                        reachedFrom.put(owner, at);
                        todo.add(owner);
                    }
                }
                if (isTestSource(r.owner)) {
                    continue;
                }
                String why = mechanicsRefusal(r);
                if (why != null) {
                    String line = "confinement: the mechanics tier reaches " + r.owner.replace('/', '.')
                            + ("<class>".equals(r.member) ? "" : "#" + r.member) + " -- " + why + " (at " + r.where
                            + "; reached by " + path(reachedFrom, at) + ")";
                    if (reported.add(line)) {
                        failures.add(line);
                    }
                }
            }
        }
        return new int[]{seeds, reachedFrom.size()};
    }

    /**
     * The compiled mechanics tier: the harness's server and client test packages. A mechanics test MAY
     * arrange through the harness, so the e2e's allowlist does not apply; what it may not do is act outside
     * the children by its own Java.
     */
    private static boolean inMechanicsPackage(String internal) {
        return internal.startsWith("dev/stannismod/stellurgy/test/server/")
                || internal.startsWith("dev/stannismod/stellurgy/test/client/");
    }

    /** The top-level class of a nested or anonymous one, by its binary name. */
    private static String outermost(String internal) {
        int dollar = internal.indexOf('$');
        return dollar < 0 ? internal : internal.substring(0, dollar);
    }

    /**
     * The one test-source METHOD whose wall-clock sleep is admitted: the server clock's own advance, which
     * sleeps between two READS of the game's clock and returns on the clock, never on the sleep -- the
     * harness's role ({@code ClientBot#waitTicks} does the same on the client side), living in the test
     * source. Every other method of {@code GameTicks}, its lambdas and its overloads included, is judged
     * like the rest of the tier.
     */
    private static final String CLOCK_ADVANCE = "dev/stannismod/stellurgy/test/GameTicks#advanceObserved"
            + "(Lcom/github/stannismod/forge/testing/server/TestClient;Ldev/stannismod/stellurgy/test/GameTicks$Clock;"
            + "ILjava/time/Duration;)J";

    /** The one admitted wall-clock wait: {@code Thread.sleep} made by {@link #CLOCK_ADVANCE} itself. */
    private static boolean clockAdvanceSleep(Ref r) {
        return CLOCK_ADVANCE.equals(r.site) && "java/lang/Thread".equals(r.owner) && "sleep".equals(r.member);
    }

    /**
     * The members of {@code System} mechanics code may not touch: the JVM's properties (written, cleared,
     * or handed out as the live table), its end, its security manager, its standard streams, and native
     * code. Reading one property, the clocks, and the rest are admitted.
     */
    private static boolean mechanicsSystemAdmits(String member) {
        switch (member) {
            case "setProperty":
            case "clearProperty":
            case "setProperties":
            case "getProperties":
            case "exit":
            case "setSecurityManager":
            case "setOut":
            case "setErr":
            case "setIn":
            case "load":
            case "loadLibrary":
                return false;
            default:
                return true;
        }
    }

    /**
     * Why {@code r}, made by mechanics-tier code (in the method {@code r.site}), is refused; {@code null}
     * when it is admitted.
     */
    private String mechanicsRefusal(Ref r) {
        String o = r.owner;
        String m = r.member;
        if (r.opcode == Opcodes.INVOKEDYNAMIC) {
            return lambdaBootstrap(r) ? null : "an invokedynamic bootstrap other than the lambda factory";
        }
        if (isFramework(o)) {
            return null;
        }
        if (r.opcode == Opcodes.PUTSTATIC) {
            return "A WRITE TO A STATIC outside the test source set: the game's or the mod's state, which a test"
                    + " does not own -- it arranges through the harness in the child";
        }
        String wallClock = wallClockWait(r);
        if (wallClock != null) {
            return clockAdvanceSleep(r) ? null : wallClock;
        }
        if ("java/lang/Runtime".equals(o) || "java/lang/ProcessBuilder".equals(o) || "java/lang/Process".equals(o)
                || o.startsWith("java/lang/ProcessBuilder$")) {
            return "THE PROCESS (Runtime, ProcessBuilder, Process)";
        }
        if ("java/lang/System".equals(o) && !mechanicsSystemAdmits(m)) {
            return "THE JVM'S OWN STATE (System." + m + ": a property, the process's end, a security manager, the"
                    + " standard streams) -- the test JVM's properties are what the harness launches its children"
                    + " from, and a mechanics class shares that JVM with the classes after it";
        }
        if (("java/lang/Thread".equals(o) && !mechanicsThreadAdmits(m)) || "java/lang/ThreadGroup".equals(o)
                || o.startsWith("java/lang/Thread$") || "java/util/Timer".equals(o) || "java/util/TimerTask".equals(o)
                || (o.startsWith("java/util/concurrent/") && !concurrentAdmits(o)
                        && !o.startsWith("java/util/concurrent/atomic/"))
                || "parallel".equals(m) || "parallelStream".equals(m)) {
            return "THREADS (Thread, executors, futures, timers, parallel streams): a test's verdict is taken on"
                    + " the thread that asked, from the children's records";
        }
        if (o.startsWith("java/lang/reflect/") || o.startsWith("sun/reflect/") || o.startsWith("jdk/internal/")
                || o.startsWith("sun/misc/") || (o.startsWith("java/lang/invoke/") && !lambdaBootstrapOwner(o))
                || m.startsWith("getDeclared") || "setAccessible".equals(m) || "forName".equals(m)
                || ("java/lang/Class".equals(o) && !mechanicsClassAdmits(m))
                || "java/lang/ClassLoader".equals(o) || "java/net/URLClassLoader".equals(o)
                || "java/util/ServiceLoader".equals(o)) {
            return "REFLECTION (java.lang.reflect / invoke, Class members, forName, getDeclared*, setAccessible,"
                    + " class loading): a test reaches the game through the harness, never around a type";
        }
        if (fileWrite(r)) {
            return "A FILE WRITE (java.nio.file / java.io): a game directory is written by the harness alone --"
                    + " a start through ServerRoot#seed, a change between two boots through ServerRoot#editWhileDown"
                    + " -- and a test's own report through TestArtefacts";
        }
        return null;
    }

    /**
     * Why the test-source class {@code at} may not implement {@code implemented}, or {@code null}. The harness
     * writes a game directory only from a {@code StartFiles} (before the first boot) or a {@code BetweenBootsEdit}
     * (between two), and what it writes is whatever the implementation renders -- so only the TYPED
     * implementations, which render the mod's own vocabulary (config keys, a planet catalogue, the world's saved
     * catalogue), may exist. A new kind of start or edit is a new typed class added here, never a test's own
     * rendering of a file. (Neither interface can be a lambda: each has two abstract methods.)
     */
    private static String gameDirectoryTypeRefusal(String at, String implemented) {
        boolean start = "com/github/stannismod/forge/testing/server/StartFiles".equals(implemented);
        boolean edit = "com/github/stannismod/forge/testing/server/BetweenBootsEdit".equals(implemented);
        if (!start && !edit) {
            return null;
        }
        if (start && "dev/stannismod/stellurgy/test/client/GameDirSeed".equals(at)) {
            return null;
        }
        if (edit && "dev/stannismod/stellurgy/test/server/SavedWorldEdit".equals(at)) {
            return null;
        }
        return "confinement: " + at.replace('/', '.') + " implements " + implemented.replace('/', '.')
                + " -- a game directory's start and its between-boots edits are TYPED: GameDirSeed and"
                + " SavedWorldEdit are the implementations; a test states values through them, never a file";
    }

    /**
     * Whether {@code r} creates, changes, moves or deletes a file or a directory. Reading is not refused: a
     * test may read what a boot left on disk (the harness's {@code ServerRoot} reads hand it text, never a
     * path to write through).
     */
    private static boolean fileWrite(Ref r) {
        String o = r.owner;
        String m = r.member;
        switch (o) {
            case "java/nio/file/Files":
                switch (m) {
                    case "write":
                    case "writeString":
                    case "copy":
                    case "move":
                    case "delete":
                    case "deleteIfExists":
                    case "createDirectory":
                    case "createDirectories":
                    case "createFile":
                    case "createTempDirectory":
                    case "createTempFile":
                    case "createLink":
                    case "createSymbolicLink":
                    case "newOutputStream":
                    case "newBufferedWriter":
                    case "newByteChannel":
                    case "setAttribute":
                    case "setLastModifiedTime":
                    case "setOwner":
                    case "setPosixFilePermissions":
                        return true;
                    default:
                        return false;
                }
            case "java/io/File":
                switch (m) {
                    case "delete":
                    case "deleteOnExit":
                    case "mkdir":
                    case "mkdirs":
                    case "renameTo":
                    case "createNewFile":
                    case "createTempFile":
                    case "setWritable":
                    case "setReadable":
                    case "setExecutable":
                    case "setLastModified":
                    case "setReadOnly":
                        return true;
                    default:
                        return false;
                }
            case "java/io/FileOutputStream":
            case "java/io/FileWriter":
            case "java/io/RandomAccessFile":
            case "java/nio/channels/FileChannel":
            case "java/nio/channels/AsynchronousFileChannel":
            case "java/nio/file/spi/FileSystemProvider":
                return !"<class>".equals(m);
            case "java/io/PrintWriter":
            case "java/io/PrintStream":
            case "javax/xml/transform/stream/StreamResult":
                return "<init>".equals(m) && r.desc != null
                        && (r.desc.startsWith("(Ljava/lang/String;") || r.desc.startsWith("(Ljava/io/File;"));
            case "javax/imageio/ImageIO":
                return "write".equals(m);
            default:
                return false;
        }
    }

    /** The members of {@code Thread} that neither start, stop nor pause a thread. */
    private static boolean mechanicsThreadAdmits(String member) {
        switch (member) {
            case "currentThread":
            case "interrupt":
            case "isInterrupted":
            case "interrupted":
            case "getName":
            case "getId":
            case "getStackTrace":
                return true;
            default:
                return false;
        }
    }

    /**
     * The members of {@code Class} a mechanics test may use: those an e2e may, plus reading an annotation it
     * declared (the runner and the scopes read a class's declarations) and finding a shipped resource by
     * path; none opens a member or loads a type.
     */
    private static boolean mechanicsClassAdmits(String member) {
        switch (member) {
            case "getAnnotation":
            case "isAnnotationPresent":
            case "getAnnotationsByType":
            case "getResource":
            case "getResourceAsStream":
                return true;
            default:
                return classAdmits(member);
        }
    }

    // ------------------------------------------------------------------------------------------------
    // code that runs in the children, walked method by method

    /** What one child-side walk refuses, beyond the categories refused everywhere. */
    private interface ChildPolicy {
        /** Why {@code r} is refused, or {@code null}. */
        String refusal(Ref r);

        /**
         * Why a call to a mixin's {@code @Shadow} METHOD is refused, or {@code null}: at run time it is a
         * call into the target class's own code, which the walk cannot follow.
         */
        default String shadowCall(Ref r) {
            return null;
        }

        /**
         * Whether a reference that leaves the test output is first held to the categories refused everywhere
         * ({@link #forbiddenCategory}); a policy that answers false judges those references itself.
         */
        default boolean everywhereCategories() {
            return true;
        }
    }

    /** Whether {@code owner#name+desc} is a method of the test output carrying {@code @Shadow}. */
    @SuppressWarnings("unchecked")
    private boolean isShadowMethod(String owner, String name, String desc) {
        ClassNode node = testNodes.get(owner);
        if (node == null) {
            return false;
        }
        for (MethodNode m : (List<MethodNode>) node.methods) {
            if (m.name.equals(name) && m.desc.equals(desc)) {
                return hasAnnotation(m.visibleAnnotations, m.invisibleAnnotations, "Lorg/spongepowered/asm/mixin/Shadow;");
            }
        }
        return false;
    }

    /** A method of a test-source class, the unit of a child-side walk. */
    private static String key(String owner, String name, String desc) {
        return owner + "#" + name + desc;
    }

    /**
     * The entry points that PUBLISH a record a test then reads as something production did: the client
     * bridge's {@code recordEvent}, the trace's {@code record*}, the server log's {@code record}. Only a
     * recorder's own publish path -- an observation mixin, whose call production's code makes -- may reach
     * one; from an instrument (run when the TEST asks) or anything else it would forge the record a link
     * waits on.
     */
    private static boolean publishesARecord(String owner, String member) {
        switch (owner) {
            case "com/github/stannismod/forge/testing/client/bridge/ForgeTestClientBootstrap":
                return "recordEvent".equals(member);
            case "dev/stannismod/stellurgy/test/trace/TestTrace":
                return member.startsWith("record");
            case "dev/stannismod/stellurgy/test/trace/ServerEventLog":
                return "record".equals(member);
            default:
                return false;
        }
    }

    /**
     * The record type a publishing call names -- its second-to-last argument, as every entry point of
     * {@link #publishesARecord} takes {@code (…, type, payload)} -- when it is a string constant at the call;
     * null otherwise.
     */
    @SuppressWarnings("unchecked")
    private String publishedType(Ref r) {
        if (r.call == null || r.site == null) {
            return null;
        }
        String owner = r.site.substring(0, r.site.indexOf('#'));
        ClassNode node = testNodes.get(owner);
        if (node == null) {
            return null;
        }
        String nameDesc = r.site.substring(r.site.indexOf('#') + 1);
        for (MethodNode m : (List<MethodNode>) node.methods) {
            if (!(m.name + m.desc).equals(nameDesc)) {
                continue;
            }
            org.objectweb.asm.tree.analysis.Frame<org.objectweb.asm.tree.analysis.SourceValue>[] frames;
            try {
                frames = new org.objectweb.asm.tree.analysis.Analyzer<>(
                        new org.objectweb.asm.tree.analysis.SourceInterpreter()).analyze(owner, m);
            } catch (org.objectweb.asm.tree.analysis.AnalyzerException unreadable) {
                return null;
            }
            int i = m.instructions.indexOf(r.call);
            if (i < 0 || frames[i] == null) {
                return null;
            }
            int args = Type.getArgumentTypes(r.call.desc).length;
            if (args < 2) {
                return null;
            }
            org.objectweb.asm.tree.analysis.SourceValue v =
                    frames[i].getStack(frames[i].getStackSize() - args + (args - 2));
            if (v.insns.size() == 1 && v.insns.iterator().next() instanceof LdcInsnNode) {
                Object cst = ((LdcInsnNode) v.insns.iterator().next()).cst;
                return cst instanceof String ? (String) cst : null;
            }
            return null;
        }
        return null;
    }

    /**
     * The record types the RECORDERS publish -- the test and harness mixins and every class with a bus
     * handler -- as the string constants at their publishing calls. A type a recorder hands on through a
     * parameter is not read here.
     */
    @SuppressWarnings("unchecked")
    private Set<String> recorderTypes() {
        if (recorderTypes != null) {
            return recorderTypes;
        }
        recorderTypes = new TreeSet<>();
        for (ClassNode node : testNodes.values()) {
            boolean recorder = node.name.startsWith(MIXIN_PACKAGE) || node.name.startsWith(HARNESS_PACKAGE + "mixin/");
            for (MethodNode m : (List<MethodNode>) node.methods) {
                recorder |= hasAnnotation(m.visibleAnnotations, m.invisibleAnnotations, SUBSCRIBE);
            }
            if (!recorder) {
                continue;
            }
            for (MethodNode m : (List<MethodNode>) node.methods) {
                for (Ref ref : methodRefs(node, m)) {
                    if (ref.call != null && publishesARecord(ref.owner, ref.member)) {
                        String type = publishedType(ref);
                        if (type != null) {
                            recorderTypes.add(type);
                        }
                    }
                }
            }
        }
        return recorderTypes;
    }

    /**
     * Walk the methods {@code starts} reach -- callees in the test source AND in the harness (a body that
     * hands its work to a harness method does that work itself), lambda bodies, the static initialisers of
     * the classes they touch -- judging every reference that leaves both by {@code policy}. A call from the
     * test source into the harness is first judged as a reference (a harness start or the bypass client
     * stays refused); a call to an entry point that publishes a record is refused unless {@code publishes}
     * (the subject is a recorder's own publish path). Answers how many methods were walked.
     */
    @SuppressWarnings("unchecked")
    private int walkMethods(String subject, List<String[]> starts, boolean publishes, ChildPolicy policy) {
        Deque<String[]> todo = new ArrayDeque<>();
        Set<String> seen = new LinkedHashSet<>();
        Set<String> classesTouched = new TreeSet<>();
        for (String[] s : starts) {
            todo.add(s);
        }
        int walked = 0;
        Set<String> reported = new TreeSet<>();
        while (!todo.isEmpty()) {
            String[] at = todo.poll();
            ClassNode node = testNodes.get(at[0]);
            if (node == null) {
                continue;
            }
            if (classesTouched.add(node.name)) {
                todo.add(new String[]{node.name, "<clinit>", null});
            }
            List<MethodNode> matched = new ArrayList<>();
            for (MethodNode m : (List<MethodNode>) node.methods) {
                if (m.name.equals(at[1]) && (at[2] == null || m.desc.equals(at[2]))) {
                    matched.add(m);
                }
            }
            if (matched.isEmpty() && at[2] != null && node.superName != null && testNodes.containsKey(node.superName)) {
                todo.add(new String[]{node.superName, at[1], at[2]});
            }
            boolean fromHarness = isFramework(node.name);
            for (MethodNode m : matched) {
                if (!seen.add(key(node.name, m.name, m.desc))) {
                    continue;
                }
                walked++;
                for (Ref ref : methodRefs(node, m)) {
                    Ref r = ref;
                    if (testNodes.containsKey(ref.owner) && !"<class>".equals(ref.member)) {
                        String declaring = declaringInTree(ref.owner, ref.member, ref.desc);
                        if (!declaring.equals(ref.owner)) {
                            r = ref.declaredBy(declaring);
                        }
                    }
                    String why = null;
                    if (testNodes.containsKey(r.owner)) {
                        boolean call = !"<class>".equals(r.member) && r.desc != null && r.desc.startsWith("(");
                        if (call && isShadowMethod(r.owner, r.member, r.desc)) {
                            why = policy.shadowCall(r);
                            if (why == null) {
                                continue;
                            }
                        } else if (call && publishesARecord(r.owner, r.member)) {
                            // The log itself: what its writer does with the record is the log's own state.
                            String type = publishes ? null : publishedType(r);
                            if (publishes || (type != null && !recorderTypes().contains(type))) {
                                continue;
                            }
                            why = "an entry point that PUBLISHES a record (" + r.owner.substring(r.owner.lastIndexOf('/') + 1)
                                    + "#" + r.member + ") " + (type == null ? "of a type that is no constant at the call"
                                    : "of the type '" + type + "', which a recorder publishes") + ": a body run on the"
                                    + " test's demand publishes only a record type of its own (a window's summary),"
                                    + " never one a link reads as what production did";
                        } else if (isFramework(r.owner) && !fromHarness) {
                            why = forbiddenCategory(r);
                        }
                        // A harness class initialising its own statics, or a constructor its own object, writes
                        // nothing anybody else holds: that object is being made.
                        boolean ownInit = r.owner.equals(node.name) && ("<clinit>".equals(m.name) || "<init>".equals(m.name));
                        if (why == null && (r.opcode == Opcodes.PUTFIELD || r.opcode == Opcodes.PUTSTATIC)
                                && isFramework(r.owner) && !ownInit) {
                            why = policy.refusal(r);
                        }
                        if (why == null) {
                            if (call) {
                                todo.add(new String[]{r.owner, r.member, r.desc});
                                // A virtual call runs whichever override the receiver has: every one is walked.
                                if (r.opcode == Opcodes.INVOKEVIRTUAL || r.opcode == Opcodes.INVOKEINTERFACE || r.opcode == -1) {
                                    for (String sub : subtypes(r.owner)) {
                                        todo.add(new String[]{sub, r.member, r.desc});
                                    }
                                }
                            } else if (!classesTouched.contains(r.owner)
                                    && (isTestSource(r.owner) || !"<class>".equals(r.member))) {
                                todo.add(new String[]{r.owner, "<clinit>", null});
                            }
                            continue;
                        }
                    } else {
                        why = policy.everywhereCategories() ? forbiddenCategory(r) : null;
                        if (r.opcode == Opcodes.INVOKEDYNAMIC) {
                            why = lambdaBootstrap(r) ? null : "an invokedynamic bootstrap other than the lambda factory";
                        } else if (why == null) {
                            why = policy.refusal(r);
                        }
                    }
                    if (why != null) {
                        String line = "confinement: " + subject + " reaches " + r.owner.replace('/', '.')
                                + ("<class>".equals(r.member) ? "" : "#" + r.member) + " -- " + why + " (at " + r.where + ")";
                        if (reported.add(line)) {
                            failures.add(line);
                        }
                    }
                }
            }
        }
        return walked;
    }

    /**
     * A named instrument may record and read: no field written outside the test source set, and no call
     * into the game or the mod beyond the reads it needs, named in {@link #instrumentReadAdmits}.
     */
    private int confineInstruments(List<String[]> rows, String side) {
        int walked = 0;
        for (String[] row : rows) {
            String owner = row[0].replace('.', '/');
            if (!isTestSource(owner)) {
                failures.add("confinement: the " + side + " instrument " + row[0] + "#" + row[1] + " names no class of"
                        + " the test source set");
                continue;
            }
            List<String[]> start = Collections.singletonList(new String[]{owner, row[1], null});
            walked += walkMethods("the " + side + " instrument " + row[0] + "#" + row[1], start, false, r -> {
                if (r.opcode == Opcodes.PUTFIELD || r.opcode == Opcodes.PUTSTATIC) {
                    return "a WRITE to a field outside the test source set: an instrument records into its own state";
                }
                if (r.opcode == Opcodes.GETFIELD || r.opcode == Opcodes.GETSTATIC
                        || jdkOrLibraryAdmits(r.owner, r.member) || "<class>".equals(r.member)) {
                    return null;
                }
                return instrumentReadAdmits(r.owner, r.member) ? null
                        : "a call into the game or the mod that is not one of the reads an instrument is admitted";
            });
        }
        return walked;
    }

    /**
     * A MECHANICS instrument may call into the game and the mod (a reader reads production state; a feed
     * calls production's input entry point), but never WRITES a field outside the test source set: the test
     * arranges through probe verbs in the child, not through a static's body. A row naming a class outside
     * the test source set is admitted only as {@code FEED_INPUT} or {@code FORGED_PACKET} — a production entry
     * point named for what it is fed — and its body (production's) is not walked.
     *
     * <p>Rows are {@code {class, method[, kind]}}; a server row carries no kind (the server table has none
     * outside the test source set).</p>
     */
    private int confineMechanicsInstruments(List<String[]> rows, String side) {
        int walked = 0;
        for (String[] row : rows) {
            String owner = row[0].replace('.', '/');
            String kind = row.length > 2 ? row[2] : "";
            if (!isTestSource(owner)) {
                if (!"FEED_INPUT".equals(kind) && !"FORGED_PACKET".equals(kind)) {
                    failures.add("confinement: the " + side + " mechanics instrument " + row[0] + "#" + row[1]
                            + " names no class of the test source set and is no FEED_INPUT or FORGED_PACKET row");
                }
                continue;
            }
            List<String[]> start = Collections.singletonList(new String[]{owner, row[1], null});
            walked += walkMethods("the " + side + " mechanics instrument " + row[0] + "#" + row[1], start, false, r -> {
                if (r.opcode == Opcodes.PUTFIELD || r.opcode == Opcodes.PUTSTATIC) {
                    return "a WRITE to a field outside the test source set: a mechanics instrument reads, records"
                            + " into its own state, or feeds input — it does not set the mod's state";
                }
                return null;
            });
        }
        return walked;
    }

    /**
     * The game reads an instrument may make, and nothing else: which side it runs on and the side's own
     * object its trace hangs on, and the world and tick a record is filed under. Each is a getter of state
     * the game already holds; a row added here is a claim that the member writes nothing.
     */
    private static boolean instrumentReadAdmits(String owner, String member) {
        switch (owner + "#" + member) {
            case "net/minecraft/client/Minecraft#getMinecraft":
            case "net/minecraftforge/fml/common/FMLCommonHandler#instance":
            case "net/minecraftforge/fml/common/FMLCommonHandler#getMinecraftServerInstance":
            case "net/minecraftforge/fml/common/FMLCommonHandler#getEffectiveSide":
            case "net/minecraftforge/fml/relauncher/Side#isClient":
            case "net/minecraftforge/fml/relauncher/Side#isServer":
            case "net/minecraftforge/common/DimensionManager#getWorld":
            case "net/minecraft/world/World#getTotalWorldTime":
            case "net/minecraft/world/WorldServer#getTotalWorldTime":
                return true;
            default:
                return false;
        }
    }

    // ------------------------------------------------------------------------------------------------
    // observation mixins

    @SuppressWarnings("unchecked")
    private int confineObservationMixins(Set<String> observation) {
        int checked = 0;
        for (String simple : new TreeSet<>(observation)) {
            String internal = MIXIN_PACKAGE + simple;
            ClassNode node = testNodes.get(internal);
            if (node == null) {
                failures.add("confinement: the OBSERVATION mixin " + simple + " has no class in the test output");
                continue;
            }
            checked++;
            Set<String> shadowFields = new TreeSet<>();
            for (FieldNode f : (List<FieldNode>) node.fields) {
                if (hasAnnotation(f.visibleAnnotations, f.invisibleAnnotations, "Lorg/spongepowered/asm/mixin/Shadow;")) {
                    shadowFields.add(f.name);
                }
            }
            List<String[]> starts = new ArrayList<>();
            // The one call a @Redirect observation makes ON PURPOSE: the call it replaces, forwarded unchanged
            // (redirectForwards holds that), by the method that carries the redirect.
            Map<String, String> forwarded = new HashMap<>();
            for (MethodNode m : (List<MethodNode>) node.methods) {
                String where = "the OBSERVATION mixin " + simple + "#" + m.name;
                observationMethod(node, m, where, shadowFields);
                starts.add(new String[]{node.name, m.name, m.desc});
                String target = redirectTarget(m);
                if (target != null) {
                    forwarded.put(node.name + "#" + m.name + m.desc, target);
                }
            }
            // What its methods CALL is held to the same rule as their own bodies: a helper of the test source
            // or of the harness writes no field outside the test source and cancels nothing, and neither it
            // nor the mixin calls a setter of the game or the mod, writes through reflection, or calls a
            // shadowed method of the target that is a setter. The mixin is a recorder: it may publish.
            walkMethods("the OBSERVATION mixin " + simple, starts, true, new ChildPolicy() {
                @Override
                public String refusal(Ref r) {
                    if (r.opcode == Opcodes.PUTFIELD || r.opcode == Opcodes.PUTSTATIC) {
                        return "a WRITE to a field outside the test source set: an observation writes only its own state";
                    }
                    if (r.owner.startsWith(CALLBACK) && ("cancel".equals(r.member) || "setReturnValue".equals(r.member))) {
                        return "an observation changes nothing the game decides";
                    }
                    if ((r.owner + "#" + r.member + r.desc).equals(forwarded.get(r.site))) {
                        return null;
                    }
                    if (gameSetter(r.owner, r.member)) {
                        return "a SETTER of the game or the mod: an observation changes nothing the game decides";
                    }
                    return observationOutsideRefusal(r);
                }

                @Override
                public String shadowCall(Ref r) {
                    return setterName(r.member) ? "a call to the target's own SETTER through a @Shadow method: an"
                            + " observation changes nothing the game decides" : null;
                }

                @Override
                public boolean everywhereCategories() {
                    return false;
                }
            });
        }
        return checked;
    }

    /** The call a {@code @Redirect} on {@code m} replaces, as {@code owner#name+desc}; null for none. */
    private static String redirectTarget(MethodNode m) {
        AnnotationNode redirect = annotation(m.visibleAnnotations, m.invisibleAnnotations,
                "Lorg/spongepowered/asm/mixin/injection/Redirect;");
        Object at = redirect == null ? null : value(redirect, "at");
        AnnotationNode atNode = at instanceof AnnotationNode ? (AnnotationNode) at
                : at instanceof List && !((List<?>) at).isEmpty() ? (AnnotationNode) ((List<?>) at).get(0) : null;
        String target = atNode == null ? null : (String) value(atNode, "target");
        if (target == null || !target.startsWith("L") || target.indexOf(';') < 0 || target.indexOf('(') < target.indexOf(';')) {
            return null;
        }
        int semi = target.indexOf(';');
        int paren = target.indexOf('(');
        return target.substring(1, semi) + "#" + target.substring(semi + 1, paren) + target.substring(paren);
    }

    /**
     * Why a reference an observation's code makes outside the test output is refused, beyond fields and
     * setters: what CHANGES something by another road -- a write or a call through reflection
     * ({@code Field.set*}, {@code Method.invoke}, a constructor, a method handle, {@code Unsafe}), a thread
     * started, a wall-clock wait (on the game's own thread), the process or the JVM's state, a file written.
     * A reflective READ, a stack trace, a clock read are admitted: an observation reads what it observes.
     */
    private static String observationOutsideRefusal(Ref r) {
        String o = r.owner;
        String m = r.member;
        if (("java/lang/reflect/Field".equals(o) && m.startsWith("set") && !"setAccessible".equals(m))
                || ("java/lang/reflect/Method".equals(o) && "invoke".equals(m))
                || ("java/lang/reflect/Constructor".equals(o) && "newInstance".equals(m))
                || ("java/lang/Class".equals(o) && "newInstance".equals(m))
                || (o.startsWith("java/lang/invoke/") && !"java/lang/invoke/LambdaMetafactory".equals(o))
                || o.startsWith("sun/misc/") || o.startsWith("jdk/internal/")
                || "java/lang/ClassLoader".equals(o) || "java/net/URLClassLoader".equals(o)) {
            return "a WRITE or a CALL through reflection (Field.set*, Method.invoke, a constructor, a method handle,"
                    + " Unsafe, class loading): an observation changes nothing the game decides";
        }
        if (("java/lang/Thread".equals(o) && ("start".equals(m) || "<init>".equals(m) || "stop".equals(m)))
                || "java/util/Timer".equals(o) || (o.startsWith("java/util/concurrent/") && !concurrentAdmits(o))) {
            return "THREADS (a thread started, an executor, a timer, a future): an observation records on the thread"
                    + " that made the call";
        }
        String wallClock = wallClockWait(r);
        if (wallClock != null) {
            return wallClock;
        }
        if ("java/lang/Runtime".equals(o) || "java/lang/ProcessBuilder".equals(o) || "java/lang/Process".equals(o)
                || ("java/lang/System".equals(o) && !mechanicsSystemAdmits(m))) {
            return "THE PROCESS OR THE JVM'S STATE (Runtime, a process, System properties, exit, streams)";
        }
        return fileWrite(r) ? "A FILE WRITE: an observation publishes to the log, never to the disk" : null;
    }

    /** A method named as a setter: {@code set} followed by an upper-case letter. */
    private static boolean setterName(String member) {
        return member.length() > 3 && member.startsWith("set") && Character.isUpperCase(member.charAt(3));
    }

    /**
     * A setter of the game, Forge, or the mod -- by NAME, which is the only reading of a game method this
     * check has: the game's methods are too many to classify, and a setter is the form a write takes when
     * it is not a field write.
     */
    private static boolean gameSetter(String owner, String member) {
        boolean game = owner.startsWith("net/minecraft/") || owner.startsWith("net/minecraftforge/")
                || owner.startsWith("dev/stannismod/stellurgy/") || owner.startsWith("org/valkyrienskies/");
        return game && setterName(member);
    }

    private static boolean hasAnnotation(List<AnnotationNode> visible, List<AnnotationNode> invisible, String desc) {
        return annotation(visible, invisible, desc) != null;
    }

    private static AnnotationNode annotation(List<AnnotationNode> visible, List<AnnotationNode> invisible, String desc) {
        for (List<AnnotationNode> list : java.util.Arrays.asList(visible, invisible)) {
            if (list != null) {
                for (AnnotationNode a : list) {
                    if (a.desc.equals(desc)) {
                        return a;
                    }
                }
            }
        }
        return null;
    }

    private static Object value(AnnotationNode a, String name) {
        if (a == null || a.values == null) {
            return null;
        }
        for (int i = 0; i + 1 < a.values.size(); i += 2) {
            if (name.equals(a.values.get(i))) {
                return a.values.get(i + 1);
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private void observationMethod(ClassNode node, MethodNode m, String where, Set<String> shadowFields) {
        List<AnnotationNode> all = new ArrayList<>();
        if (m.visibleAnnotations != null) {
            all.addAll(m.visibleAnnotations);
        }
        if (m.invisibleAnnotations != null) {
            all.addAll(m.invisibleAnnotations);
        }
        AnnotationNode inject = null;
        AnnotationNode redirect = null;
        AnnotationNode modifyArg = null;
        for (AnnotationNode a : all) {
            if (a.desc.startsWith(INJECTION) || "Lorg/spongepowered/asm/mixin/Overwrite;".equals(a.desc)) {
                switch (a.desc) {
                    case "Lorg/spongepowered/asm/mixin/injection/Inject;":
                        inject = a;
                        break;
                    case "Lorg/spongepowered/asm/mixin/injection/Redirect;":
                        redirect = a;
                        break;
                    case "Lorg/spongepowered/asm/mixin/injection/ModifyArg;":
                        modifyArg = a;
                        break;
                    default:
                        failures.add("confinement: " + where + " is an " + a.desc + ", which no observation is: it"
                                + " replaces or rewrites what the game computes");
                }
            }
            if ("Lorg/spongepowered/asm/mixin/gen/Accessor;".equals(a.desc)
                    && Type.getReturnType(m.desc).getSort() == Type.VOID) {
                failures.add("confinement: " + where + " is a SETTER @Accessor: it writes the target's field");
            }
        }
        if (inject != null && Boolean.TRUE.equals(value(inject, "cancellable"))) {
            failures.add("confinement: " + where + " is a CANCELLABLE @Inject: an observation cannot end the method"
                    + " it watches");
        }
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode) {
                MethodInsnNode mi = (MethodInsnNode) insn;
                if (mi.owner.startsWith(CALLBACK) && ("cancel".equals(mi.name) || "setReturnValue".equals(mi.name))) {
                    failures.add("confinement: " + where + " calls " + mi.owner.substring(CALLBACK.length()) + "#"
                            + mi.name + ": an observation changes nothing the game decides");
                }
            } else if (insn instanceof FieldInsnNode) {
                FieldInsnNode fi = (FieldInsnNode) insn;
                boolean put = fi.getOpcode() == Opcodes.PUTFIELD || fi.getOpcode() == Opcodes.PUTSTATIC;
                if (put && (fi.owner.equals(node.name) ? shadowFields.contains(fi.name) : !isTestSource(fi.owner))) {
                    failures.add("confinement: " + where + " WRITES " + fi.owner.replace('/', '.') + "." + fi.name
                            + (fi.owner.equals(node.name) ? " (a @Shadow of the target)" : "")
                            + ": an observation writes only its own state");
                }
            }
        }
        if (redirect != null) {
            redirectForwards(node, m, redirect, where);
        }
        if (modifyArg != null) {
            modifyArgReturnsIt(m, where);
        }
    }

    /** The parameter slots of {@code m}, in order. */
    private static int[] paramSlots(MethodNode m) {
        Type[] args = Type.getArgumentTypes(m.desc);
        int[] slots = new int[args.length];
        int slot = (m.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
        for (int i = 0; i < args.length; i++) {
            slots[i] = slot;
            slot += args[i].getSize();
        }
        return slots;
    }

    private static boolean noise(AbstractInsnNode insn) {
        return insn.getOpcode() < 0;
    }

    private static AbstractInsnNode previous(AbstractInsnNode insn) {
        AbstractInsnNode p = insn.getPrevious();
        while (p != null && noise(p)) {
            p = p.getPrevious();
        }
        return p;
    }

    private static AbstractInsnNode next(AbstractInsnNode insn) {
        AbstractInsnNode n = insn.getNext();
        while (n != null && noise(n)) {
            n = n.getNext();
        }
        return n;
    }

    private static boolean isLoad(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        return op >= Opcodes.ILOAD && op <= Opcodes.ALOAD && insn instanceof VarInsnNode;
    }

    private static boolean isStore(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        return op >= Opcodes.ISTORE && op <= Opcodes.ASTORE && insn instanceof VarInsnNode;
    }

    private static boolean isReturn(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        return op >= Opcodes.IRETURN && op <= Opcodes.ARETURN;
    }

    /** No parameter slot of {@code m} is ever stored to: an argument handed on is the one received. */
    private boolean paramsUntouched(MethodNode m, String where) {
        int[] slots = paramSlots(m);
        int end = slots.length == 0 ? ((m.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1)
                : slots[slots.length - 1] + Type.getArgumentTypes(m.desc)[slots.length - 1].getSize();
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            int var = insn instanceof VarInsnNode ? ((VarInsnNode) insn).var
                    : insn instanceof IincInsnNode ? ((IincInsnNode) insn).var : -1;
            if (var >= 0 && var < end && (isStore(insn) || insn instanceof IincInsnNode)) {
                failures.add("confinement: " + where + " writes its own parameter slot " + var + ": what it hands on"
                        + " must be what it received");
                return false;
            }
        }
        return true;
    }

    /**
     * A {@code @Redirect} observation performs the redirected call exactly once, on its own parameters in
     * order, and returns what that call returned.
     */
    private void redirectForwards(ClassNode node, MethodNode m, AnnotationNode redirect, String where) {
        Object at = value(redirect, "at");
        AnnotationNode atNode = at instanceof AnnotationNode ? (AnnotationNode) at
                : at instanceof List && !((List<?>) at).isEmpty() ? (AnnotationNode) ((List<?>) at).get(0) : null;
        String kind = atNode == null ? null : (String) value(atNode, "value");
        String target = atNode == null ? null : (String) value(atNode, "target");
        if (!"INVOKE".equals(kind) || target == null) {
            failures.add("confinement: " + where + " redirects " + kind + " " + target + ": only a redirected CALL is"
                    + " an observation this check can read as forwarded");
            return;
        }
        int paren = target.indexOf('(');
        int semi = target.indexOf(';');
        if (!target.startsWith("L") || semi < 0 || paren < semi) {
            failures.add("confinement: " + where + " has a target this check cannot read: " + target);
            return;
        }
        String name = target.substring(semi + 1, paren);
        String desc = target.substring(paren);
        if (!paramsUntouched(m, where)) {
            return;
        }
        List<MethodInsnNode> calls = new ArrayList<>();
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode && ((MethodInsnNode) insn).name.equals(name)
                    && ((MethodInsnNode) insn).desc.equals(desc)) {
                calls.add((MethodInsnNode) insn);
            }
        }
        if (calls.size() != 1) {
            failures.add("confinement: " + where + " makes the redirected call " + name + desc + " " + calls.size()
                    + " times: a forward makes it once");
            return;
        }
        MethodInsnNode call = calls.get(0);
        int operands = Type.getArgumentTypes(desc).length + (call.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1);
        int[] slots = paramSlots(m);
        if (operands > slots.length) {
            failures.add("confinement: " + where + " cannot hand the redirected call its " + operands + " operands");
            return;
        }
        AbstractInsnNode p = call;
        for (int i = operands - 1; i >= 0; i--) {
            p = previous(p);
            if (p == null || !isLoad(p) || ((VarInsnNode) p).var != slots[i]) {
                failures.add("confinement: " + where + " does not hand the redirected call its own parameters in"
                        + " order (operand " + i + "): a forward passes on what it received");
                return;
            }
        }
        if (Type.getReturnType(desc).getSort() == Type.VOID) {
            return;
        }
        AbstractInsnNode after = next(call);
        int resultSlot = -1;
        if (after != null && isStore(after)) {
            resultSlot = ((VarInsnNode) after).var;
            int stores = 0;
            for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if ((isStore(insn) && ((VarInsnNode) insn).var == resultSlot)
                        || (insn instanceof IincInsnNode && ((IincInsnNode) insn).var == resultSlot)) {
                    stores++;
                }
            }
            if (stores != 1) {
                failures.add("confinement: " + where + " overwrites the redirected call's result");
                return;
            }
        } else if (after == null || !isReturn(after)) {
            failures.add("confinement: " + where + " neither returns nor keeps the redirected call's result");
            return;
        }
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!isReturn(insn)) {
                continue;
            }
            AbstractInsnNode before = previous(insn);
            boolean returnsResult = before == call
                    || (resultSlot >= 0 && before != null && isLoad(before) && ((VarInsnNode) before).var == resultSlot);
            if (!returnsResult) {
                failures.add("confinement: " + where + " returns something other than the redirected call's result");
                return;
            }
        }
    }

    /** A {@code @ModifyArg} observation returns the argument it was handed, unchanged. */
    private void modifyArgReturnsIt(MethodNode m, String where) {
        int[] slots = paramSlots(m);
        if (slots.length == 0 || !paramsUntouched(m, where)) {
            if (slots.length == 0) {
                failures.add("confinement: " + where + " takes no argument to hand back");
            }
            return;
        }
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!isReturn(insn)) {
                continue;
            }
            AbstractInsnNode before = previous(insn);
            if (before == null || !isLoad(before) || ((VarInsnNode) before).var != slots[0]) {
                failures.add("confinement: " + where + " returns something other than the argument it was handed");
                return;
            }
        }
    }

    // ------------------------------------------------------------------------------------------------
    // bus subscribers, mods and loaders of the test source set

    @SuppressWarnings("unchecked")
    private int confineSubscribers() {
        Set<String> subscribers = new TreeSet<>();
        for (ClassNode node : testNodes.values()) {
            if (!isTestSource(node.name)) {
                continue;
            }
            for (String annotation : new String[]{"Lnet/minecraftforge/fml/common/Mod;",
                    "Lnet/minecraftforge/fml/common/Mod$EventBusSubscriber;"}) {
                if (hasAnnotation(node.visibleAnnotations, node.invisibleAnnotations, annotation)) {
                    failures.add("confinement: the test-source class " + node.name.replace('/', '.') + " carries "
                            + annotation + ": FML would load or subscribe it in every child, e2e or not");
                }
            }
            for (String i : (List<String>) node.interfaces) {
                if (("zone/rong/mixinbooter/IEarlyMixinLoader".equals(i) || "zone/rong/mixinbooter/ILateMixinLoader".equals(i)
                        || "net/minecraftforge/fml/relauncher/IFMLLoadingPlugin".equals(i)
                        || "org/spongepowered/asm/mixin/extensibility/IMixinConfigPlugin".equals(i))
                        && !"dev/stannismod/stellurgy/test/trace/StellurgyTestMixinPlugin".equals(node.name)) {
                    failures.add("confinement: the test-source class " + node.name.replace('/', '.') + " implements " + i
                            + ": a coremod, a mixin loader or a second mixin plugin decides what every child weaves");
                }
            }
            for (MethodNode m : (List<MethodNode>) node.methods) {
                for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (!(insn instanceof MethodInsnNode) || !EVENT_BUS.equals(((MethodInsnNode) insn).owner)
                            || !"register".equals(((MethodInsnNode) insn).name)) {
                        continue;
                    }
                    AbstractInsnNode arg = previous(insn);
                    if (arg instanceof LdcInsnNode && ((LdcInsnNode) arg).cst instanceof Type) {
                        subscribers.add(((Type) ((LdcInsnNode) arg).cst).getInternalName());
                    } else {
                        failures.add("confinement: " + node.name.replace('/', '.') + "#" + m.name + " puts something"
                                + " other than a class literal on a Forge bus: a subscriber of the test source set is"
                                + " registered as a CLASS, so this check can read every handler it adds");
                    }
                }
            }
        }
        for (String subscriber : subscribers) {
            ClassNode node = testNodes.get(subscriber);
            if (node == null || !isTestSource(subscriber)) {
                failures.add("confinement: the test source set registers " + subscriber.replace('/', '.')
                        + " on a Forge bus, which is no class of the test source set");
                continue;
            }
            List<String[]> handlers = new ArrayList<>();
            for (MethodNode m : (List<MethodNode>) node.methods) {
                if (hasAnnotation(m.visibleAnnotations, m.invisibleAnnotations, SUBSCRIBE)) {
                    handlers.add(new String[]{node.name, m.name, m.desc});
                }
            }
            // A subscriber is a RECORDER of an event production posted, so it may publish what it saw.
            walkMethods("the bus subscriber " + subscriber.replace('/', '.'), handlers, true, r -> {
                if ((r.opcode == Opcodes.PUTFIELD || r.opcode == Opcodes.PUTSTATIC)) {
                    return "a WRITE to a field outside the test source set: a recorder writes only its own state";
                }
                if (r.member.startsWith("set") && r.desc != null && r.desc.startsWith("(") && isEvent(r.owner)) {
                    return "a SETTER of a Forge event: a recorder's handler changes nothing an event decides (an"
                            + " un-cancel, a result, an exception)";
                }
                if ("getParameters".equals(r.member) && isCommandEvent(r.owner)) {
                    return "the ARGUMENT ARRAY of a command: the handler runs that very array, so a store into it"
                            + " rewrites what runs -- the server's mode gate re-judges it at LOWEST, and a test"
                            + " listener after that last word could still write; a recorder records the command's"
                            + " name and verdict, never its arguments";
                }
                return null;
            });
        }
        return subscribers.size();
    }

    // ------------------------------------------------------------------------------------------------
    // a test class with the name of a class the children also load

    /**
     * Every class of the test output whose name a class on the runtime classpath also has. The test output
     * precedes the mod's jar on every child's classpath, so such a class REPLACES the one the mod ships -- a
     * gate, a table, a probe kind -- in every child.
     */
    private int refuseShadows() throws IOException {
        Set<String> ours = new TreeSet<>();
        for (String name : testNodes.keySet()) {
            ours.add(name + ".class");
        }
        // A RESOURCE of the test source set shadows the same way: a recipe, a config default, a lang file,
        // a mixin configuration the mod's jar also holds is read from the test output in every child.
        Path testResources = root.resolve("build").resolve("resources").resolve("test");
        for (Path dir : new Path[]{testResources, root.resolve("src/test/resources"),
                root.resolve(FRAMEWORK_ROOT).getParent().resolve("resources")}) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.filter(Files::isRegularFile).forEach(p -> ours.add(dir.relativize(p).toString().replace('\\', '/')));
            }
        }
        Map<String, String> clashes = new TreeMap<>();
        for (File entry : classpathEntries()) {
            if (entry.exists() && (Files.isSameFile(entry.toPath(), testClasses)
                    || (Files.isDirectory(testResources) && Files.isSameFile(entry.toPath(), testResources)))) {
                continue;
            }
            if (entry.isDirectory()) {
                Path dir = entry.toPath();
                for (String name : ours) {
                    if (Files.isRegularFile(dir.resolve(name))) {
                        clashes.put(name, entry.toString());
                    }
                }
            } else if (entry.isFile() && entry.getName().endsWith(".jar")) {
                try (ZipFile zip = new ZipFile(entry)) {
                    for (String name : ours) {
                        ZipEntry found = zip.getEntry(name);
                        if (found != null) {
                            clashes.put(name, entry.toString());
                        }
                    }
                }
            }
        }
        clashes.forEach((name, where) -> failures.add(name.endsWith(".class")
                ? "confinement: the test class " + name.substring(0, name.length() - ".class".length()).replace('/', '.')
                + " has the name of a class in " + where + ": the test output precedes it on every child's classpath"
                + " and would replace it there"
                : "confinement: the test resource " + name + " has the path of a resource in " + where + ": the test"
                + " output precedes it on every child's classpath and would replace it there"));
        return ours.size();
    }

    /** The runtime classpath this check runs on, a manifest-only jar's {@code Class-Path} expanded. */
    private static List<File> classpathEntries() throws IOException {
        List<File> out = new ArrayList<>();
        for (String part : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
            if (part.isEmpty()) {
                continue;
            }
            File f = new File(part);
            out.add(f);
            if (f.isFile() && f.getName().endsWith(".jar")) {
                try (JarFile jar = new JarFile(f)) {
                    Manifest manifest = jar.getManifest();
                    String extra = manifest == null ? null : manifest.getMainAttributes().getValue("Class-Path");
                    if (extra != null) {
                        for (String url : extra.trim().split("\\s+")) {
                            try {
                                out.add(Paths.get(new java.net.URI(url).isAbsolute() ? new java.net.URI(url)
                                        : f.getParentFile().toURI().resolve(url)).toFile());
                            } catch (Exception malformed) {
                                out.add(new File(f.getParentFile(), url));
                            }
                        }
                    }
                }
            }
        }
        return out;
    }

    /** The resources named {@code name} on {@code loader}'s classpath. */
    static List<String> resources(ClassLoader loader, String name) throws IOException {
        List<String> out = new ArrayList<>();
        Enumeration<java.net.URL> found = loader.getResources(name);
        while (found.hasMoreElements()) {
            out.add(found.nextElement().toString());
        }
        return out;
    }
}
