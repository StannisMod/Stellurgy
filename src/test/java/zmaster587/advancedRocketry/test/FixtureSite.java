package zmaster587.advancedRocketry.test;


/**
 * WHERE a fixture stands, and the first link that says the volume around it is empty.
 *
 * <h2>Why a site is a type and not three integers</h2>
 *
 * <p>Every craft and entity fixture in this suite used to be staged by passing a hard-coded
 * {@code y = 64} into a private per-class helper, next to a pre-clear of
 * {@code baseY+1 .. baseY+10}. On generated terrain that is not a clearing: it is a ten-block SHAFT
 * with rock on every side, and the shaft's RIM sits above the hull a body is aimed at. The failure
 * is silent for weeks — the fixture assembles, the craft flies, and the red arrives many links later
 * wearing the name of whatever mechanic ran into the rock. Four separate investigations went to the
 * wrong subsystem first: <i>the release gate is misfiring</i>, <i>the hold refuses to engage</i>,
 * <i>the client is not carrying the body</i>. The block was never in the story because nothing had
 * ever asked about it.</p>
 *
 * <p>So the Y stops being an argument a call site picks. A site is either {@link #openAir}, which
 * puts it in the band above generated terrain where a plot starts empty whatever the seed rolled, or
 * {@link #onGround}, which keeps its terrain and must say in one sentence why the ground is the
 * SUBJECT. There is no third form, and neither one can be written without deciding which it is.</p>
 *
 * <h2>What the fixture gives you, and what it does not</h2>
 *
 * <p>{@code artest fixture rocket} lays a 6&times;6 launchpad of solid blocks at the site's own Y, so
 * an open-air site is not a craft hanging in nothing — the pad is the plate the hull rests on, and it
 * is placed before anything is assembled. What the pad does NOT give is floor beyond its own edge: a
 * body dropped outside it falls, and a scenario that needs somewhere to STAND off the craft builds
 * that itself.</p>
 */
public final class FixtureSite {

    /**
     * The open-air band, in blocks: above generated terrain and far below the world ceiling.
     *
     * <p>This is the one definition; {@code Plot.DEFAULT_Y} reads it rather than repeating it. The
     * number is not a preference — it is high enough that no landform on a generated world reaches
     * it, which is the whole property being bought.</p>
     */
    public static final int OPEN_AIR_Y = 150;

    /** Edge of the launchpad the rocket fixture lays at the site, in blocks. */
    static final int PAD = 5;

    private static final String PLACED = "placed";
    private static final String VOLUME = "volume";

    public final int dim;
    public final int x;
    public final int y;
    public final int z;

    /** Why this site keeps its terrain, or {@code null} when it stands in open air. */
    private final String groundIsTheSubject;

    /**
     * The plot this site was ALLOCATED from, or {@code null} when the coordinates were chosen by
     * hand. It is what turns non-overlap from "we picked different numbers" into something
     * {@link #requireClear} can check: the volume a scenario actually clears is asserted to lie
     * inside its own plot, so a fixture that reaches into a neighbour's fails as an arrangement
     * instead of silently levelling somebody else's craft.
     */
    private final Plot plot;

    private FixtureSite(int dim, int x, int y, int z, String groundIsTheSubject, Plot plot) {
        this.dim = dim;
        this.x = x;
        this.y = y;
        this.z = z;
        this.groundIsTheSubject = groundIsTheSubject;
        this.plot = plot;
    }

    /** A site allocated from a plot: open air, and bound to the plot for the containment check. */
    static FixtureSite openAirIn(Plot plot, int x, int z) {
        return new FixtureSite(plot.dim, x, OPEN_AIR_Y, z, null, plot);
    }

    /**
     * A site in the open-air band: the caller chooses only WHERE horizontally.
     *
     * <p>The Y is not a parameter on purpose. Every value a call site could pass below the band is
     * the defect this type exists to end, and a value above it buys nothing the band does not
     * already give.</p>
     */
    public static FixtureSite openAir(int dim, int x, int z) {
        return new FixtureSite(dim, x, OPEN_AIR_Y, z, null, null);
    }

    /**
     * A site that KEEPS its terrain, because contact with the ground is what the scenario is about —
     * a descent, a landing, a body walking on world blocks beside a hull.
     *
     * @param why one sentence naming the subject, not the convenience. It is recorded on the site and
     *            reproduced in any failure raised here, so a reader meets the decision rather than
     *            having to infer it from a coordinate.
     */
    public static FixtureSite onGround(int dim, int x, int y, int z, String why) {
        if (why == null || why.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "a ground site must say why the ground is its SUBJECT — an unexplained one is"
                            + " indistinguishable from a site nobody examined, which is the state"
                            + " this argument exists to end");
        }
        return new FixtureSite(dim, x, y, z, why, null);
    }

    /** Is this site standing on world terrain on purpose? */
    public boolean isOnGround() {
        return groundIsTheSubject != null;
    }

    /**
     * The FIRST link: the volume this scenario is about to use is EMPTY, and a failure names what was
     * in it.
     *
     * <p>The measurement is the air fill's own answer. {@code artest fill … minecraft:air} reports
     * {@code placed} — how many {@code setBlockState} calls actually changed something — and vanilla
     * short-circuits a write of a state a block already has. So over a volume filled with air,
     * {@code placed} is exactly <b>the number of non-air blocks that were standing in it</b>. The
     * call therefore clears and measures in one pass, and the assertion is on the measurement.</p>
     *
     * <p><b>The envelope, not the floor.</b> {@code height} is how far ABOVE the site the subject
     * will reach — the top of the hull, the air a crew member jumps into over its deck, the lane a
     * craft climbs — not the height of what is built. A floor check passes on a craft whose deck sits
     * a fraction of a block under uncleared rock, which is exactly how this was missed before.</p>
     *
     * @param halo how far out from the launchpad's footprint the volume reaches, in blocks
     * @param height how far above the site the volume reaches, in blocks
     * @param what a scenario-facing sentence for what this volume is FOR
     */
    public void requireClear(Events.Probe probe, int halo, int height, String what) throws Exception {
        if (isOnGround()) {
            ArrangementFailure.arrangementFailed(
                    what + " — this is a GROUND site (" + groundIsTheSubject + "), and asking a"
                            + " ground site to be empty asserts the opposite of what it was declared"
                            + " for. A scenario whose subject is the terrain clears the volume it"
                            + " needs and says so; it does not claim the site was already clear");
        }
        int x1 = x - halo, z1 = z - halo;
        int x2 = x + PAD + halo, z2 = z + PAD + halo;
        int y1 = y + 1, y2 = y + height;
        // THE VOLUME YOU ARE ABOUT TO TOUCH IS INSIDE YOUR OWN PLOT. Checked, not assumed — this is
        // what makes the plot a CONTRACT rather than a convention. A scenario that asks for a halo
        // its plot cannot hold would otherwise clear a strip of the neighbour's, which is invisible
        // from inside either test and surfaces much later as somebody else's craft gone missing.
        // A site whose coordinates were chosen by hand has no plot to be checked against, and says
        // so by being unchecked; that is the state this whole seam exists to retire.
        if (plot != null) {
            if (!plot.containsBox(x1, z1, x2, z2)) {
                ArrangementFailure.arrangementFailed(
                        what + " — the working area (" + x1 + "," + z1 + ")..(" + x2 + "," + z2 + ")"
                                + " leaves this scenario's own plot " + plot + ". halo=" + halo
                                + " and a fixture standing at " + x + "," + z + " on this plot may"
                                + " clear at most " + plot.maxHaloAt(x, z) + "; a scenario that"
                                + " needs more room declares a WIDER LANE, because the alternative"
                                + " is clearing a strip of a neighbouring scenario's world and"
                                + " neither test can see that happen");
            }
            // AND NOT INTO A STRUCTURE THIS SAME SCENARIO ALREADY BUILT. The check above keeps the
            // volume inside one plot; a scenario standing two fixtures on that plot can still put
            // them through each other, which is the identical defect one level down.
            plot.claimWorkingVolume(x, z, x1, z1, x2, z2, what);
        }
        String reply = probe.exec("artest fill " + dim
                + " " + x1 + " " + y1 + " " + z1
                + " " + x2 + " " + y2 + " " + z2
                + " minecraft:air");
        ArrangementFailure.requireArranged(
                what + " — the working area could not even be read: " + reply,
                reply != null && Reply.of(reply).ok());
        int placed = intOf(reply, PLACED);
        if (placed == 0) {
            return;
        }
        ArrangementFailure.arrangementFailed(
                what + " — " + placed + " of the " + intOf(reply, VOLUME) + " blocks in the working"
                        + " area were NOT air before this scenario touched anything. The volume is"
                        + " (" + x1 + "," + y1 + "," + z1 + ")..(" + x2 + "," + y2 + "," + z2 + ")"
                        + " in dim " + dim + ". A site in the open-air band starts empty, so"
                        + " something is standing in it: either the band is not where this thinks it"
                        + " is, or an earlier scenario left its own structure here and the plots"
                        + " overlap. It is raised now, as an arrangement problem, because the"
                        + " alternative is a red many links later carrying some mechanic's name"
                        + " while the cause is a block nobody asked about");
    }

    @Override
    public String toString() {
        return "FixtureSite[" + dim + " " + x + "," + y + "," + z
                + (isOnGround() ? " GROUND: " + groundIsTheSubject : " open air") + "]";
    }

    private static int intOf(String reply, String field) {
        return Reply.of(String.valueOf(reply)).integer(field);
    }
}
