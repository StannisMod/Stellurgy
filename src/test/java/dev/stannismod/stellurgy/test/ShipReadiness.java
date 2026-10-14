package dev.stannismod.stellurgy.test;


import static org.junit.Assert.assertTrue;

/**
 * Whether this scenario's ships may UNLOAD — the one switch over ship loading a scenario owns.
 *
 * <p>Everything that used to stand beside it here COUNTED a world's ships — loaded, registered, or
 * "at least one" — or cleared every ship of a world, and is gone (2026-10-14): a scenario holds its own
 * craft's id from the record of its assembly, reads that craft by its id, and disposes of it by its id
 * ({@link Disposal}). Whether a scenario left a craft behind is its shared base's question
 * ({@link CraftCensus}), never its own.</p>
 */
public final class ShipReadiness {

    private ShipReadiness() {
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
        String reply = probe.exec("stellurgytest vs permaload false");
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
        String reply = probe.exec("stellurgytest vs permaload true");
        assertTrue("this scenario asked for ships to be held loaded again (" + why + ") and the"
                + " probe did not accept it: " + reply, Reply.of(reply).ok());
        System.out.println("[permaload] back ON for this scenario — " + why);
    }
}
