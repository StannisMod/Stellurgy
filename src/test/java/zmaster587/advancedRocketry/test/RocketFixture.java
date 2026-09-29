package zmaster587.advancedRocketry.test;


/**
 * BUILD a rocket fixture at a site and assemble it — the one copy of a sequence that had twenty-seven.
 *
 * <h2>Why this is a type and not a private helper per class</h2>
 *
 * <p>Twenty-one client classes and six server classes each carried a private {@code assembleFixture}
 * of their own, and all twenty-seven ran the same four steps: make room, lay the fixture, read its
 * {@code builderPos}, assemble at that position. What differed between them was noise — {@code
 * assertTrue} in some and the arrangement gate in others, the dimension written as a literal
 * {@code 0} in all but one, the {@code builderPos} field name declared twenty-seven times.</p>
 *
 * <p>A copied form carries its bug into every copy and leaves the reason behind, and this suite has
 * the receipts: the pit pre-clear was copied into every one of those helpers and dug a ten-block shaft
 * at each of them, and three separate reds — read for weeks as a render gap, a capture gate
 * misfiring and a body that would not fall — were that one arrangement fault wearing three names.
 * A copy cannot be fixed once.</p>
 *
 * <h2>The room is not optional, and that is the point</h2>
 *
 * <p>There is no overload without {@code halo}, {@code height} and {@code what}: a fixture may not
 * be built over a volume nobody has examined, and the only way to make that structural rather than
 * remembered is for the builder to be uncallable without one. The three arguments are the
 * scenario's own knowledge — how far its subject reaches, and what the volume is FOR — so they stay
 * at the call site, where the sentence that explains them can live beside them.</p>
 *
 * @see FixtureSite#makeRoom
 */
public final class RocketFixture {

    /** Where the fixture reports the builder block it just laid. */
    private static final String BUILDER_POS = "builderPos";
    /** Where an assembled rocket reports its entity. */
    private static final String ENTITY_ID = "entityId";

    private RocketFixture() {
    }

    /**
     * Make room, lay the fixture, assemble it, and answer with the assemble reply VERBATIM.
     *
     * <p>The reply is returned unjudged on purpose. What every caller asserts about it is that the
     * build routed to the thing that scenario is about — a VS ship rather than a rocket entity, or
     * the other way round — and that is the scenario's contract, in the scenario's words. What is
     * asserted HERE is only what belongs to the fixture itself: that the fixture command took, and
     * that it reported a builder position to assemble at.</p>
     *
     * @param site    where it stands, and which of {@link FixtureSite#makeRoom}'s two halves runs
     * @param probe   how this tier runs a probe command
     * @param variant the fixture variant, as {@code artest fixture rocket} names it
     * @param halo    how far out from the launchpad's footprint the working volume reaches
     * @param height  how far ABOVE the site the SUBJECT reaches — not how tall the build is
     * @param what    a scenario-facing sentence for what that volume is for
     */
    public static String assembleAt(FixtureSite site, Events.Probe probe, String variant,
                                    int halo, int height, String what) throws Exception {
        return assembleBuilt(site, probe, placeAt(site, probe, variant, halo, height, what));
    }

    /**
     * The FIRST half alone: make room, lay the craft's blocks, and answer the builder position —
     * leaving them LOOSE.
     *
     * <p>For the scenarios whose subject lives between the two halves: a player who has to walk to
     * an unassembled craft and sit down in it before anything becomes a ship, or a gate that asks
     * what a build is allowed to become. They are not a variation on {@link #assembleAt} and do not
     * call it; the two halves are the same steps, taken with something in between.</p>
     */
    public static int[] placeAt(FixtureSite site, Events.Probe probe, String variant,
                                int halo, int height, String what) throws Exception {
        site.makeRoom(probe, halo, height, what);
        String fixture = probe.exec("artest fixture rocket " + site.dim
                + " " + site.x + " " + site.y + " " + site.z + " " + variant);
        ArrangementFailure.requireArranged("fixture (" + variant + ") failed at " + site + ": "
                + fixture, fixture != null && Reply.of(fixture).ok());
        int[] builder = Reply.of(fixture).blockPos(BUILDER_POS);
        ArrangementFailure.requireArranged("fixture (" + variant + ") laid nothing to assemble —"
                + " no " + BUILDER_POS + " in: " + fixture, builder != null);
        return builder;
    }

    /** The SECOND half: assemble what {@link #placeAt} laid, at the builder position it answered. */
    public static String assembleBuilt(FixtureSite site, Events.Probe probe, int[] builderPos)
            throws Exception {
        return probe.exec("artest rocket assemble " + site.dim
                + " " + builderPos[0] + " " + builderPos[1] + " " + builderPos[2]);
    }

    /**
     * The ROCKET ENTITY an assemble reply produced, for a scenario whose subject is a rocket rather
     * than a ship.
     *
     * <p>Separate from {@link #assembleAt} rather than an alternative return type on it: a build
     * that routes to a VS ship reports no entity at all, so asking for one is a statement about
     * what this scenario expects to have built, and it fails saying so.</p>
     */
    public static int rocketEntityId(String assembleReply) {
        ArrangementFailure.requireArranged("rocket assemble failed: " + assembleReply,
                assembleReply != null && Reply.of(assembleReply).ok());
        Reply reply = Reply.of(assembleReply);
        ArrangementFailure.requireArranged("the assembled build reports no " + ENTITY_ID + ", so it"
                + " did not route to a rocket entity: " + assembleReply, reply.has(ENTITY_ID));
        int id = Integer.parseInt(reply.text(ENTITY_ID));
        ArrangementFailure.requireArranged("rocket " + ENTITY_ID + " is negative, which no live"
                + " entity has: " + assembleReply, id >= 0);
        return id;
    }
}
