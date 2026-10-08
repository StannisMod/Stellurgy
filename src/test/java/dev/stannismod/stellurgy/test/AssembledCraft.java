package dev.stannismod.stellurgy.test;

import dev.stannismod.stellurgy.tile.TileAdvancedFlightComputer;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;

/**
 * One craft laid by {@code stellurgytest fixture rocket}, assembled by its own assembler, and resolved
 * into every name a scenario addresses it by: its durable id, its physics id, and its flight
 * computer's SUBSPACE address.
 *
 * <h2>Why this is an instrument</h2>
 *
 * <p>Two server groups need an assembled tier-2 craft they can read and build onto — the flight
 * model's and the hyperdrive's. Both need the same three names, and the names come out of production's
 * own records rather than out of the assemble reply, which carries only the durable id: the physics id
 * is on {@code ship_lifecycle} (edge {@code named}), and the flight computer's address aboard is on the
 * computer's first {@code flight_model_changed}. A second private copy of that sequence would be a
 * second place for its waits and its budget to drift.</p>
 *
 * <h2>The two halves</h2>
 *
 * <p>{@link #lay} makes room and lays the fixture, answering the fixture's own reply — which carries
 * what a scenario may need beyond where to press, such as where a hold or a drive machine stands
 * relative to the flight computer. {@link #assemble} presses the assembler the fixture laid and
 * resolves the craft. They are separate because a scenario reads the reply between them.</p>
 *
 * <p>A build the assembler warns about first — a hull that cannot hold its weight — is pressed a
 * second time: that is how a player builds one, and whether it WAS warned about is not this
 * instrument's question.</p>
 */
public final class AssembledCraft {

    /**
     * The deadline of each LINK here. The naming is announced at assembly; the first flight model is
     * built at the latest on the computer's first load round, and those come round every
     * {@link TileAdvancedFlightComputer#MASS_ROUND_TICKS}. Twice that is the deadline, so an expiry
     * means the record never came rather than that its round fell late in the window.
     */
    public static final int LINK_BUDGET_TICKS = 2 * TileAdvancedFlightComputer.MASS_ROUND_TICKS;

    /** The world the craft was built in. */
    public final int dim;
    /** The craft's durable id — the one that survives a crossing, as production's records name it. */
    public final String durable;
    /** The physics engine's id for the craft, as {@code ship_lifecycle} names it. */
    public final String physicsId;
    /** The flight computer's address aboard: a SUBSPACE block once the craft is assembled. */
    public final int afcX;
    public final int afcY;
    public final int afcZ;

    private AssembledCraft(int dim, String durable, String physicsId, int afcX, int afcY, int afcZ) {
        this.dim = dim;
        this.durable = durable;
        this.physicsId = physicsId;
        this.afcX = afcX;
        this.afcY = afcY;
        this.afcZ = afcZ;
    }

    /**
     * Make room at {@code site} and lay the {@code variant} fixture there, answering the fixture's
     * reply.
     *
     * @param halo   how far out from the launchpad's footprint the working volume reaches
     * @param height how far ABOVE the site the subject reaches
     * @param what   a scenario-facing sentence for what the craft is for
     */
    public static Reply lay(FixtureSite site, Events.Probe probe, String variant, int halo, int height,
                            String what) throws Exception {
        site.makeRoom(probe, halo, height, what);
        Reply fixture = Reply.of(probe.exec("stellurgytest fixture rocket " + site.dim + " " + site.x
                + " " + site.y + " " + site.z + " " + variant));
        requireArranged(what + " — the fixture (" + variant + ") must be laid: " + fixture,
                fixture.ok() && fixture.blockPos("builderPos") != null);
        return fixture;
    }

    /**
     * Press the assembler {@link #lay} laid, and wait for the two records that make the craft
     * addressable: its naming (which hands over the physics id) and its first flight model (which
     * hands over the flight computer's address aboard).
     *
     * <p>red-witnessed: {@code TileRocketAssemblingMachine#assembleRocket} at {@code VSIntegration.assembleBuiltTier2Ship(world, rocketBB);} (the build never handed to the
     * physics mod) fails "the craft must be named once it is assembled" in every scenario that builds,
     * 2026-09-30 — taken on the pre-2026-10-07 form, which handed over a pasted snapshot at that line.</p>
     * <p>red-witnessed: {@code TileAdvancedFlightComputer#rebuildFlightModel} at {@code net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(} (a rebuilt flight model never announced)
     * fails "the craft's flight computer must build its first flight model" in every scenario that
     * builds, 2026-09-30</p>
     */
    public static AssembledCraft assemble(FixtureSite site, Events.Probe probe, Events events,
                                          Reply fixture, String what) throws Exception {
        long mark = events.mark();
        int[] builder = fixture.blockPos("builderPos");
        Reply press = Reply.of(RocketFixture.assembleBuilt(site, probe, builder));
        requireArranged(what + " — the assemble press must answer whether it built the ship: "
                + press, press.ok() && press.has("built"));
        if (!press.bool("built")) {
            press = Reply.of(RocketFixture.assembleBuilt(site, probe, builder));
            requireArranged(what + " — a second press on the same build must build it: " + press,
                    press.ok() && press.bool("built"));
        }
        String durable = press.text("shipId");
        String named = events.awaitRecordWithFields(mark, "ship_lifecycle",
                what + " — the craft must be named once it is assembled", LINK_BUDGET_TICKS,
                "durable", durable, "edge", "named");
        String model = events.awaitRecordWithFields(mark, "flight_model_changed",
                what + " — the craft's flight computer must build its first flight model",
                LINK_BUDGET_TICKS, "ship", durable);
        return new AssembledCraft(site.dim, durable, Events.text(named, "ship"),
                (int) Events.number(model, "afcX"), (int) Events.number(model, "afcY"),
                (int) Events.number(model, "afcZ"));
    }

    /** The flight computer as a probe addresses a ship: {@code <dim> <afcX> <afcY> <afcZ>}. */
    public String flightComputer() {
        return dim + " " + afcX + " " + afcY + " " + afcZ;
    }

    @Override
    public String toString() {
        return "AssembledCraft[" + durable + " physics=" + physicsId + " afc=" + flightComputer() + "]";
    }
}
