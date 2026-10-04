package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.LedgerEntry;
import dev.stannismod.stellurgy.test.MaterializedCell;
import dev.stannismod.stellurgy.test.NebulaSearch;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.SkyNebulae;
import dev.stannismod.stellurgy.test.SubsystemStatus;
import org.junit.After;
import org.junit.Test;

import dev.stannismod.stellurgy.universe.GalaxyGenConfig;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * What a cell's sky is told about: the bodies around it, which belong to the CELL and not to a ship's
 * lifecycle stage, and the clouds a generated galaxy seats around it (the scenarios at the end).
 *
 * <p>The render feed ({@code SystemBodiesProducer} &rarr; {@code PacketSystemBodiesSync} &rarr;
 * {@code BoundarySky}) is what tells a client which bodies to draw around a live cell. This test drives
 * the PRODUCTION producer on a real server and reads back the feed it would broadcast (the
 * {@code space bodies} probe reports the actual packet, so the observation cannot drift from what is
 * sent), for the two arrangements where a cell is live but no ship in it is settled:</p>
 *
 * <ol>
 *   <li><b>Nobody's ship is in the cell at all.</b> The cell is held live by an occupant with no ship —
 *       the state of a crew member whose ship departed without him, or a passenger dropped in by an
 *       on-ramp. His sky is the cell's.</li>
 *   <li><b>The only ship in the cell is mid-jump.</b> Measured off a real session: a pilot sitting in a
 *       live cell world (slot bound, six bodies in it) whose ship the ledger called {@code IN_TRANSIT}
 *       after an arrival crossing stranded it. Every body in his sky disappeared, and a blank sky looks
 *       exactly like an empty cell — which is why this leg exists as a test and not as a playtest note.</li>
 * </ol>
 *
 * <p>Both legs are arranged through probes that change only WHICH DATA the producer has (a registered POI,
 * an occupant refcount, a ledger state), never which object or code path produces the feed. Each leg
 * carries its own control: the same cell measured before the POI exists, so a later body count is a real
 * observation rather than a coincidence.</p>
 *
 * <p>No Valkyrien Skies needed — {@code entry-setup} builds the stack without touching physics, and
 * nothing here loads a ship.</p>
 */
public class SystemBodiesFeedFollowsTheCellTest extends AbstractSharedServerTest {

    /**
     * Cells far enough out that nothing else claims them, so the body count of a cell is exactly what
     * this test put in it. The two methods use different cells: the shared server runs both, and a
     * cell is global state.
     *
     * <p><b>The distance that matters is {@code minSpacing/2}, not "away from sector zero".</b> These
     * used to sit at {@code sy = 5000} on the reasoning that a non-zero sector Y kept them clear of the
     * generated fallback stars at {@code sy=sz=0} — which guarded against the wrong neighbour and left
     * both legs failing. A cell is attributed to a stored anchor by
     * {@code UniverseRegistry.storedAnchorNear}, whose reach is HALF THE SUPER-CELL — about 2 501 180
     * cells at the shipped spacing — so {@code sy = 5000} is 0.2 % of the way out and both cells were
     * squarely inside the shipped solar system's own neighbourhood. The feed was answering correctly:
     * it offered the sun, the overworld and a moon at 1.6·10¹¹ blocks.</p>
     *
     * <p>Stated as a multiple of the reach rather than as a literal, so the fixture cannot silently
     * move back inside the neighbourhood the day the spacing is retuned.</p>
     */
    private static final long CLEAR_OF_ANY_ANCHOR =
            3L * (GalaxyGenConfig.DEFAULT_MIN_SPACING / 2L);
    private static final String CELL_NO_SHIP = "31 " + CLEAR_OF_ANY_ANCHOR + " 2";
    private static final String CELL_MID_JUMP = "32 " + CLEAR_OF_ANY_ANCHOR + " 2";

    /** A body a few thousand blocks out, i.e. the geometry a pilot has to fly at to descend. */
    private static final String BODY_LOCAL = "2900 0 -1200";

    /** The feed, as the probe reports it straight off the production packet: one entry per cell. */
    private static final String FEED = "feed";
    private static final String SLOT_DIM = "slotDim";
    private static final String BODY_COUNT = "bodyCount";

    @After
    public void clearStack() throws Exception {
        try {
            exec("stellurgytest space entry-clear");
        } catch (Exception ignored) {
        }
    }

    @Test
    public void aLiveCellWithNoShipInItIsStillToldWhatIsAroundIt() throws Exception {
        String setup = exec("stellurgytest space entry-setup 2");
        assertTrue("entry setup failed: " + setup, Reply.of(setup).ok());

        // Hold the cell live with an occupant refcount and NO ship anywhere in the ledger.
        int slotDim = MaterializedCell.at(this::exec, CELL_NO_SHIP)
                .requireMaterialized("occupy must materialize the cell")
                .slotDim();

        // CONTROL: the cell is live and its feed entry exists, but it holds nothing yet. A later
        // non-zero count is then attributable to the POI and to nothing else.
        String before = exec("stellurgytest space bodies");
        assertEquals("a live cell must be in the feed even while it is empty; " + before,
                0, feedBodyCount(before, slotDim));

        String poi = exec("stellurgytest space add-poi " + CELL_NO_SHIP + " " + BODY_LOCAL + " PLANET 0 7");
        assertTrue("add-poi must register a descend target: " + poi,
                Reply.of(poi).ok() && Reply.of(poi).bool("descendTarget"));

        String after = exec("stellurgytest space bodies");
        assertEquals("the cell's own body must reach the feed with no ship in the cell at all; "
                + after, 1, feedBodyCount(after, slotDim));
        assertEquals("and it must not have invented a second feed dimension; " + after,
                1, jsonInt(after, "feedDims"));
        assertEquals("no ship was ledgered by any of this; " + after, 0, jsonInt(after, "shipCount"));
    }

    @Test
    public void aLiveCellWhoseOnlyShipIsMidJumpIsStillToldWhatIsAroundIt() throws Exception {
        String setup = exec("stellurgytest space entry-setup 2");
        assertTrue("entry setup failed: " + setup, Reply.of(setup).ok());

        String poi = exec("stellurgytest space add-poi " + CELL_MID_JUMP + " " + BODY_LOCAL + " MOON 0 7");
        assertTrue("add-poi must register a descend target: " + poi,
                Reply.of(poi).ok() && Reply.of(poi).bool("descendTarget"));

        // A settled ship first: this is the state the feed already handled, and it is the control that
        // proves the arrangement can produce a body at all.
        String settle = exec("stellurgytest space ledger-settle " + CELL_MID_JUMP + " -1");
        assertTrue("ledger-settle must succeed: " + settle, Reply.of(settle).ok());
        int slotDim = jsonInt(settle, "slotDim");
        String shipId = jsonString(settle, "shipId");
        String settled = exec("stellurgytest space bodies");
        assertEquals("control: with the ship SETTLED the cell's body is fed; " + settled,
                1, feedBodyCount(settled, slotDim));

        // Now the ship goes mid-jump. Nothing about the WORLD changes: the cell stays bound to the same
        // slot, still holds its body, and whoever is standing in it is still looking at it.
        String transit = exec("stellurgytest space ledger-transit " + CELL_MID_JUMP + " " + shipId);
        assertTrue("ledger-transit must record the ship as in transit: " + transit,
                "IN_TRANSIT".equals(Reply.of(transit).text("state")));

        String bodies = exec("stellurgytest space bodies");
        // THIS scenario's ship, by the uuid it was ledgered under, and its state read off that row.
        // Addressing the row by the STATE asks the feed "is anything in transit", which every other
        // scenario's leftover jump answers — and would answer with this ship missing entirely.
        assertEquals("the feed must carry this ship as the one in transit: " + bodies,
                "IN_TRANSIT",
                Reply.of("stellurgytest space bodies", bodies)
                        .element("ships", "ship", shipId).text("state"));
        assertEquals("the cell is still bound to the same slot world; " + bodies,
                slotDim, LedgerEntry.forShip(this::exec, shipId).slotDim());
        assertEquals("a cell's bodies must not vanish from its sky because a ship in it is mid-jump; "
                + bodies, 1, feedBodyCount(bodies, slotDim));
    }

    // --- the subsystem's own bookkeeping: the slot pool and the ledger ------------------------------

    /**
     * Registering the slot pool a second time reuses it instead of minting another. Dimension
     * registration is JVM-global: a second pool would leave every slot already bound to a cell on its
     * id while the subsystem handed out different ones, and a ship's world and the pool's idea of it
     * would silently diverge.
     */
    @Test
    public void registeringThePoolASecondTimeReusesItInsteadOfMintingAnother() throws Exception {
        SubsystemStatus status = SubsystemStatus.read(this::exec);
        assertTrue("production subsystem must be live: " + status.raw(), status.registered);

        String again = exec("stellurgytest space pool-idempotence");
        assertTrue("re-registering must not grow the pool: " + again, (!Reply.of(again).bool("grew")));
        assertTrue("and it must hand back the dimensions that already exist: " + again,
                Reply.of(again).bool("returnedExisting"));
    }

    /**
     * The ledger says "no" for a ship it never settled — the witness for every restore pinned by
     * {@code SpaceRestartPersistenceTest}: a ledger read that answered "found" unconditionally would
     * make those pass on a subsystem that restored nothing.
     */
    @Test
    public void anUnknownShipIsReportedMissingRatherThanInvented() throws Exception {
        SubsystemStatus status = SubsystemStatus.read(this::exec);
        assertTrue("production subsystem must be live: " + status.raw(), status.registered);

        LedgerEntry missing = LedgerEntry.forShip(this::exec, java.util.UUID.randomUUID().toString());
        assertFalse("a ship that was never settled must read back as absent: " + missing.raw(), missing.found);
    }

    // --- the clouds a cell's sky is told about ------------------------------------------------------
    //
    // The unit tier pins the geometry — which way a cloud lies, how big it looks, what is filtered out.
    // These pin what that tier cannot see: that a real generator in a real world SEATS clouds, and that
    // the reply a client would be sent is derived from that world's own seed. A generator is installed
    // per server and parked; every scenario that installs one is paired with the reset below. The cloud
    // walk stays at sector Y zero, millions of cells from the two feed cells above.

    /** A dense galaxy so a bounded sweep finds a cluster, at the shipped star spacing. */
    private static final String GEN_INSTALL = "stellurgytest space gen-install 0.9 8 987654321";

    @After
    public void restoreGenerator() throws Exception {
        try {
            exec("stellurgytest space gen-reset");
        } catch (Exception ignored) {
        }
    }

    /** Install the dense procedural galaxy, refusing an install that did not happen. */
    private void installDenseGalaxy() throws Exception {
        String installed = exec(GEN_INSTALL);
        assertTrue("the procedural generator must install: " + installed, Reply.of(installed).ok());
    }

    /** Walk out for a cell with a cloud in its sky, refusing a walk that found none. */
    private NebulaSearch findACloud() throws Exception {
        return NebulaSearch.walk(this::exec, 512, 64)
                .requireFound("a dense galaxy must have a cloud somewhere in it");
    }

    /**
     * A sight line THROUGH a found cloud's core, from two radii short of its centre to two radii past
     * it along X, as {@code "near far"} coordinates. Built from where the generator says the cloud IS;
     * the reader refuses an absent centre, which would build a zero-length line.
     */
    private static String[] lineThrough(NebulaSearch found) {
        long radius = found.radiusCells();
        return new String[]{
                (found.centreX() - 2 * radius) + " " + found.centreY() + " " + found.centreZ(),
                (found.centreX() + 2 * radius) + " " + found.centreY() + " " + found.centreZ()};
    }

    /** A decimal field of the {@code space extinction} reply, read by NAME. */
    private static double decimal(String json, String name) {
        return Reply.of("stellurgytest space extinction", json).number(name);
    }

    @Test
    public void aGalaxyWithClustersInItHasCloudsToLookAt() throws Exception {
        installDenseGalaxy();
        SkyNebulae feed = SkyNebulae.at(this::exec, findACloud().sectorX(), 0, 0);
        assertTrue("and that sky must hold the cloud the finder found: " + feed.raw(), feed.drawn >= 1);
        // The claim is that a drawn cloud covers something, so it is about the value.
        for (SkyNebulae.Cloud cloud : feed.clouds()) {
            assertTrue("a cloud that is drawn must cover something of the sky: " + cloud.raw(),
                    cloud.angularRadius > 0d);
        }
    }

    /**
     * An authored-only pack has no galaxies, so no clusters and no gas: a feed that produced a cloud
     * here would be producing it from nothing.
     */
    @Test
    public void withoutAProceduralGeneratorTheSkyIsEmptyRatherThanInvented() throws Exception {
        String reset = exec("stellurgytest space gen-reset");
        assertTrue("the default generator must be restorable: " + reset, Reply.of(reset).ok());

        SkyNebulae feed = SkyNebulae.at(this::exec, 0, 0, 0);
        assertEquals("a universe with no clusters must seat no clouds: " + feed.raw(), 0, feed.seated);
        assertEquals("and must draw none: " + feed.raw(), 0, feed.drawn);
    }

    /**
     * A real sight line through a real cloud crosses matter and dims; the same line with no generator,
     * hence no clusters, dims nothing. What a survey does with the column is the telescope's own
     * scenario ({@code TelescopeRegionScanServerTest}).
     */
    @Test
    public void aRealCloudDimsWhatIsBehindItAndClearSpaceDoesNot() throws Exception {
        installDenseGalaxy();
        String[] line = lineThrough(findACloud());

        String through = exec("stellurgytest space extinction " + line[0] + " " + line[1]);
        assertTrue("the probe must answer for a real sight line: " + through, Reply.of(through).ok());
        assertTrue("a line that reaches a cloud's neighbourhood must cross SOME matter: " + through,
                decimal(through, "column") > 0d);
        assertTrue("and the magnitudes must follow the column, not be invented: " + through,
                decimal(through, "magnitudes") > 0d);

        String reset = exec("stellurgytest space gen-reset");
        assertTrue("the default generator must be restorable: " + reset, Reply.of(reset).ok());
        String clear = exec("stellurgytest space extinction " + line[0] + " " + line[1]);
        assertEquals("a universe with no clouds must dim nothing: " + clear, 0d,
                decimal(clear, "magnitudes"), 1.0E-9d);
    }

    /**
     * The concealment flag REMOVES its mechanic rather than softening it, and the reading it is judged
     * against is unchanged either way. The threshold this server had is read off the first reply and
     * put back.
     */
    @Test
    public void theConcealmentThresholdCanBeTurnedOff() throws Exception {
        installDenseGalaxy();
        String[] line = lineThrough(findACloud());
        String extinction = "stellurgytest space extinction " + line[0] + " " + line[1];
        double threshold = decimal(exec(extinction), "threshold");
        try {
            exec("stellurgytest config set telescopeObscuredAtMagnitudes 0.0001");
            String strict = exec(extinction);
            assertTrue("at a threshold below the real reading the line must count as obscured: "
                    + strict, Reply.of(strict).bool("obscured"));

            exec("stellurgytest config set telescopeObscuredAtMagnitudes 0");
            String off = exec(extinction);
            assertTrue("with the mechanic off nothing is obscured: " + off, (!Reply.of(off).bool("obscured")));
            assertTrue("and the dust itself is still measured — the flag removes the RULE, not the"
                    + " physics: " + off, decimal(off, "magnitudes") > 0d);
        } finally {
            exec("stellurgytest config set telescopeObscuredAtMagnitudes " + threshold);
        }
    }

    /**
     * What is seated and what is drawn are reported side by side, so a working level-of-detail filter
     * can be told from a generator that stopped seating.
     */
    @Test
    public void whatIsSeatedAndWhatIsDrawnAreReportedSeparately() throws Exception {
        installDenseGalaxy();
        SkyNebulae feed = SkyNebulae.at(this::exec, findACloud().sectorX(), 0, 0);
        assertTrue("what is drawn may never exceed what is seated: " + feed.raw(), feed.drawn <= feed.seated);
    }

    // --- helpers ---------------------------------------------------------------------------------

    /**
     * The body count the feed carries for {@code slotDim}, or {@code -1} when the feed does not mention
     * that dimension at all. The two answers are deliberately different: "no bodies here" and "this
     * world is not in the feed" are the two halves a blank sky splits into.
     */
    private static int feedBodyCount(String json, int slotDim) {
        for (String entry : Reply.of("the system-bodies feed", json).objectArray(FEED)) {
            Reply cell = Reply.of("one feed entry", entry);
            // absence is the answer: this walks a LIST looking for one cell, and an entry
            // that carries no slot dim is not the one being looked for.
            if (cell.integerOr(SLOT_DIM, Integer.MIN_VALUE) == slotDim) {
                return cell.integer(BODY_COUNT);
            }
        }
        return -1;
    }

    private static int jsonInt(String json, String field) {
        assertTrue("probe response carries no numeric \"" + field + "\": " + json, Reply.of(json).has(field));
        return Reply.of(json).integer(field);
    }

    private static String jsonString(String json, String field) {
        assertTrue("probe response carries no string \"" + field + "\": " + json, Reply.of(json).has(field));
        return Reply.of(json).text(field);
    }
}
