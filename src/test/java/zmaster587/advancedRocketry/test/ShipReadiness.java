package zmaster587.advancedRocketry.test;


import static org.junit.Assert.assertTrue;

/**
 * How many ships are LOADED in a dimension — read once, asserted immediately, never waited for.
 *
 * <h2>Why there is no wait here, and how that was decided</h2>
 *
 * <p>Seventeen server-tier scenarios used to carry a private {@code waitForLoadedShip}: poll until
 * the registry knows a ship, pump {@code vs load-ships}, poll until one is loaded, up to 200 ticks.
 * Hashing the seventeen bodies gave SIX different ones — six experiments under one name.</p>
 *
 * <p>Before collapsing them, the question "what does this wait actually wait for" was measured
 * rather than assumed, with a pair of plants: first the already-satisfied branch was made to throw,
 * then the branch that waits was. <b>Every scenario failed on the first plant, at its FIRST call.
 * None failed on the second — at one fork, and again at six.</b> Seventeen classes, twenty tests,
 * zero executions of the wait under six-way concurrent load. By the time any of them asks, the ship
 * is loaded. The old polls exited on their first iteration for the same reason, which is why nobody
 * noticed in the years they stood there.</p>
 *
 * <p><b>So the wait was not waiting for anything, and a wait that waits for nothing is a defect
 * wearing a helper's clothes.</b> A wait exists because something is not true synchronously after
 * the action; when it IS true, what remains is either a fossil of a fault since fixed or a habit
 * adopted without measuring. Either way it costs the same thing: it converts a state that should
 * fail LOUDLY into up to 200 ticks of absorbed ambiguity, and then returns a number that is zero for
 * several different reasons.</p>
 *
 * <p>What replaces it is stronger, not weaker. A read and an assertion say "a ship is loaded here"
 * as a postcondition, fail at once when it is not, and name what to look at next. If a genuinely
 * asynchronous path ever appears, this is where it will surface — as a red with a discriminating
 * message, rather than as a wait that silently makes it go away.</p>
 */
public final class ShipReadiness {

    private ShipReadiness() {
    }

    private static final String COUNT = "count";

    /**
     * Clear the craft this scenario built out of {@code dim} — every registered ship in that world
     * is marked finished and the substrate collects it.
     *
     * <p><b>Why a scenario owes this.</b> A craft left behind used to be harmless: nobody was near
     * it, so it unloaded, and an unloaded hull does not move. Now a test server holds its ships
     * loaded, and a hull left behind goes on ticking — measured 2026-09-16, inside one class: one
     * craft still climbing at y=609, one fallen to y=70 and drifting sideways, in the world the next
     * scenario runs in. Maintainer, the same day: <i>"в @After всем тестам с кораблями надо добавить
     * убийство их кораблей. Негоже мусор после себя оставлять."</i></p>
     *
     * <p>Marks EVERY ship in the world, not this scenario's alone, because that is what a shared
     * world's cleanup means: the next scenario is entitled to an empty sky, and a craft that belongs
     * to nobody in particular is exactly the one nothing else will clear. A class that stages two
     * craft and means to keep one across scenarios must not call this.</p>
     *
     * <p>Answers how many it marked, so a caller can print it; nothing here asserts on the number —
     * a scenario that built nothing legitimately clears nothing.</p>
     */
    public static int clearCraftFrom(Events.Probe probe, int dim) throws Exception {
        String reply = probe.exec("artest vs destroy-ships " + dim);
        Reply mReply = Reply.of(String.valueOf(reply));
        assertTrue("the cleanup verb must answer how many craft it marked, or a scenario cannot say"
                + " whether it left anything behind: " + reply, mReply.has("marked"));
        return mReply.integer("marked");
    }

    /**
     * Let this scenario's ships UNLOAD again — the opt-out from the default a test server runs
     * under, for the handful of scenarios whose subject is the unload itself.
     *
     * <p>A test server holds every ship permanently loaded from the moment the probes register: a
     * headless run has no player to hold one, and a craft that vanishes between two probe calls is
     * an arrangement failure in nearly every scenario there is. In a few it is the SUBJECT — a
     * registered ship nobody has loaded, an unmanned arrival that must establish its own
     * loadedness, a player's own loop where production is supposed to keep its ship loaded and a
     * harness affordance would hide the failure to do it. Those say so here.</p>
     *
     * <p><b>A method rather than the raw command, and a static rather than a base-class method.</b>
     * The scenarios that need it sit under three different bases ({@code AbstractHeadlessServerTest},
     * {@code AbstractSharedServerTest}, the milestone's own), so a method on any one of them would be
     * re-invented as a raw {@code exec} by the other two — which is how the same line ends up written
     * four different ways.</p>
     *
     * <p>The reason is a PARAMETER because it is the deliverable: it is printed into the run log, so
     * a reader of a red can see which scenario stepped out of the default and what it claimed in
     * exchange. And the reply is checked — an opt-out that silently failed would leave the scenario
     * measuring the affordance instead of the product, which is exactly what it is opting out of.</p>
     *
     * @param why what this scenario's subject is, in its own words
     */
    public static void letShipsUnload(Events.Probe probe, String why) throws Exception {
        String reply = probe.exec("artest vs permaload false");
        assertTrue("this scenario asked for ships to be able to unload (" + why + ") and the probe"
                + " did not accept it, so it is still running under the server's default and would"
                + " measure that instead: " + reply, Reply.of(reply).ok());
        System.out.println("[permaload] OFF for this scenario — " + why);
    }

    /**
     * Put the default back for the rest of this scenario, after {@link #letShipsUnload}.
     *
     * <p>Only a scenario that needs BOTH states in sequence calls this — the one that has to watch a
     * ship unload and then be asked for twice on the same tick. Nobody else should: a scenario that
     * has not opted out is already running under it, and re-stating the default is the ceremony that
     * 45 classes carried until 2026-09-16.</p>
     *
     * @param why why this scenario needs the state BACK, having just asked for the other one
     */
    public static void holdShipsLoaded(Events.Probe probe, String why) throws Exception {
        String reply = probe.exec("artest vs permaload true");
        assertTrue("this scenario asked for ships to be held loaded again (" + why + ") and the"
                + " probe did not accept it: " + reply, Reply.of(reply).ok());
        System.out.println("[permaload] back ON for this scenario — " + why);
    }

    /**
     * How many ships are LOADED in {@code dim} right now — a plain read, no pump and no wait.
     *
     * <p>The form to use inside somebody else's poll condition, where a failed read is an answer
     * ("not yet") and must not throw.</p>
     */
    public static int loadedCount(Events.Probe probe, int dim) throws Exception {
        return countOf(probe.exec("artest vs ship-count " + dim));
    }

    /** How many ships the registry KNOWS in {@code dim}, loaded or not. Read for the same reason. */
    public static int registeredCount(Events.Probe probe, int dim) throws Exception {
        return countOf(probe.exec("artest vs ship-count-all " + dim));
    }

    /**
     * Assert that at least {@code want} ships are loaded in {@code dim}, and answer how many.
     *
     * <p>The failure separates the two states a bare count cannot: a craft the registry never heard
     * of, and one it knows but which is not loaded. Those send a reader to different subsystems, so
     * the message carries both counts and says which it is.</p>
     *
     * @param what a scenario-facing sentence for what this ship being loaded MEANS here
     */
    public static int requireLoaded(Events.Probe probe, int dim, int want, String what)
            throws Exception {
        int loaded = loadedCount(probe, dim);
        if (loaded >= want) {
            return loaded;
        }
        int registered = registeredCount(probe, dim);
        assertTrue(what + " — dimension " + dim + " holds " + loaded + " loaded ship(s), wanted "
                        + want + ". The registry knows " + registered + " there, so this is "
                        + (registered < want
                                ? "a craft that never REGISTERED: look at what was supposed to create"
                                        + " it, not at loading"
                                : "a craft that registered and did not LOAD: a headless server has no"
                                        + " player near it, so something was expected to ask —"
                                        + " `artest vs load-ships " + dim + "` is that ask")
                        + ". This is asserted rather than waited for: measured across this tier at"
                        + " one and at six forks, the ship is always already loaded by the time a"
                        + " scenario asks, so a delay here is a finding and not something to sit out",
                loaded >= want);
        return loaded;
    }

    /** One loaded ship in {@code dim} — the floor almost every scenario asks for. */
    public static int requireLoaded(Events.Probe probe, int dim, String what) throws Exception {
        return requireLoaded(probe, dim, 1, what);
    }

    /** The {@code count} of a probe reply, or {@link Integer#MIN_VALUE} when it carries none. */
    private static int countOf(String reply) {
        Reply mReply = Reply.of(String.valueOf(reply));
        // absence is the answer: this reader's own contract is "or MIN_VALUE when the reply
        // carries none", and its callers branch on that.
        return mReply.has(COUNT) ? mReply.integer(COUNT) : Integer.MIN_VALUE;
    }
}
