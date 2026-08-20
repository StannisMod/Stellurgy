package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.GameTicks;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * shared command-invocation + result-readback helpers for the
 * {@code /ar} (WorldCommand) test suites. Keeps each test class small
 * by absorbing the duplicated <em>"run a command then read state back"</em>
 * boilerplate.
 *
 * <p>Result-readback strategy: prefer {@code /artest planet info <dim>}
 * (independent reader, JSON output) over re-reading via {@code /ar planet get}
 * (shares its codepath with {@code /ar planet set} — same impl reading
 * the same field, so they'd agree-but-be-wrong on a shared bug).
 * {@code /ar planet get} is checked for its own contract once, then we
 * trust the independent JSON readback everywhere else.</p>
 *
 * <p>Package-private — only the {@code /ar} test classes need it.</p>
 */
final class WorldCommandFixtures {

    private static final Pattern INT_FIELD =
            Pattern.compile("\"%s\":(-?\\d+)");
    private static final Pattern FLOAT_FIELD =
            Pattern.compile("\"%s\":(-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)");

    private WorldCommandFixtures() {}

    /** Send a command via the shared {@link AbstractSharedServerTest}
     *  harness and return the concatenated console response. */
    static String exec(String cmd) throws Exception {
        return String.join("\n", AbstractSharedServerTest.client().execute(cmd));
    }

    /**
     * What time it is in the GAME, asked of the server.
     *
     * <p>The server's own tick counter. A test that needs to know how long it is willing to wait for
     * something asks here rather than looking at a watch.</p>
     *
     * <p>The three tick helpers below are DELEGATES. The implementation lives in the public
     * {@link GameTicks}, because this class is package-private and bound to the shared-server
     * harness while a third of the suite's waiting sites are in classes that cannot reach it — and
     * because two implementations of "wait for the game" is exactly one too many. What stays here is
     * the vocabulary: these names read better at a {@code /ar} call site.</p>
     */
    static long serverTick() throws Exception {
        return GameTicks.read(AbstractSharedServerTest.client(), GameTicks.server());
    }

    /**
     * Wait for something to become true, budgeting in SERVER TICKS.
     *
     * <p><b>This is the difference between an experiment and a stopwatch.</b> An asynchronous
     * mechanic needs a certain amount of WORLD to happen — so many ticks of a controller, a queue, a
     * settle. A budget in seconds does not ask for that: it asks for a certain amount of the
     * machine's attention, and a machine that is busy gives the same test less world for the same
     * money. That is how a green test turns red because something unrelated was running, and it is
     * why budgets here used to be multiplied by the build's fork count — a number about the machine,
     * standing in for a number about the game.</p>
     *
     * <p>Ticks are asked of the server, so they are the same ticks the mechanic under test runs on.
     * How OFTEN we ask is wall-clock and deliberately unimportant: polling faster changes nothing
     * but the sharpness of the answer.</p>
     *
     * @param tickBudget how much world the mechanic is allowed, in server ticks
     * @param condition  what is being waited for; asked once before any waiting at all
     * @param eachPoll   work the wait itself must keep doing — arrangement that has to be re-applied
     *                   while the mechanic runs. May be null.
     * @return true if the condition held within the budget
     */
    static boolean awaitWithinTicks(int tickBudget, TickCondition condition, TickAction eachPoll)
            throws Exception {
        return GameTicks.until(AbstractSharedServerTest.client(), GameTicks.server(),
                tickBudget, condition, eachPoll);
    }

    /**
     * Let the world run for this many server ticks.
     *
     * <p>For an OBSERVATION window rather than a wait: when a test wants to watch something hold
     * still, the window has to be measured in the ticks the subject runs on. A window in seconds
     * covers fewer of the subject's ticks on a busy machine, which does not merely make the test
     * slower — it makes it BLIND, and a drift that needed forty ticks to show up passes as stable.
     * That is the failure direction worth spending a helper on: a wall-clock wait turns green into
     * red, a wall-clock observation turns red into green.</p>
     */
    static void advanceTicks(int ticks) throws Exception {
        GameTicks.advance(AbstractSharedServerTest.client(), GameTicks.server(), ticks);
    }

    /**
     * What {@link #awaitWithinTicks} is waiting for. Allowed to ask the server, so it throws.
     *
     * <p>Kept as a name of its own, and made a subtype so the same lambda serves both: these read
     * better at a {@code /ar} call site than the generic ones, and the implementation is shared.</p>
     */
    interface TickCondition extends GameTicks.Condition {
    }

    /** Work a wait has to keep doing. Allowed to talk to the server, so it throws. */
    interface TickAction extends GameTicks.Action {
    }

    /** Read an integer field out of {@code /artest planet info <dim>}
     *  JSON. Asserts the field is present (matcher must find). */
    static int planetIntField(int dim, String field) throws Exception {
        return Integer.parseInt(matchOrThrow(planetInfo(dim), field, INT_FIELD));
    }

    /** Read a float/double field out of {@code /artest planet info <dim>}. */
    static double planetFloatField(int dim, String field) throws Exception {
        return Double.parseDouble(matchOrThrow(planetInfo(dim), field, FLOAT_FIELD));
    }

    /** True iff AR's planet registry knows the given dim, observed via
     *  {@code /ar planet list} (which iterates {@code getRegisteredDimensions()}
     *  &rarr; the underlying {@code dimensionList} keyset). Cannot use
     *  {@code /artest planet info} here because
     *  {@code DimensionManager.getDimensionProperties} falls back to
     *  {@code overworldProperties} for unknown dims (line 539), so the
     *  info probe is incapable of distinguishing "registered" from
     *  "absent" by itself. */
    static boolean planetExists(int dim) throws Exception {
        String list = exec("ar planet list");
        return list.contains("DIM" + dim + ":");
    }

    private static String planetInfo(int dim) throws Exception {
        return exec("artest planet info " + dim);
    }

    private static String matchOrThrow(String src, String field, Pattern template) {
        Pattern p = Pattern.compile(String.format(template.pattern(),
                Pattern.quote(field)));
        Matcher m = p.matcher(src);
        if (!m.find()) {
            throw new AssertionError("field \"" + field + "\" not found in: " + src);
        }
        return m.group(1);
    }

    /** First line that contains the substring, or {@code null}. Useful
     *  for chat-output assertions that don't pin exact wording. */
    static String firstLineContaining(List<String> lines, String needle) {
        for (String l : lines) {
            if (l.contains(needle)) return l;
        }
        return null;
    }
}
