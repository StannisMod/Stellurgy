package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.Reply;
import zmaster587.advancedRocketry.test.ShipReadiness;
import zmaster587.advancedRocketry.test.GameTicks;
import zmaster587.advancedRocketry.test.EntrySlots;
import zmaster587.advancedRocketry.test.EntryStatus;
import zmaster587.advancedRocketry.test.Events;
import zmaster587.advancedRocketry.test.ShipIdentity;
import zmaster587.advancedRocketry.test.ShipInfo;

import org.junit.After;
import org.junit.Test;


import zmaster587.advancedRocketry.test.FixtureSite;
import zmaster587.advancedRocketry.test.RocketFixture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static zmaster587.advancedRocketry.test.server.WorldCommandFixtures.awaitEnteredSpace;

/**
 * E2E: crossing OUT of an atmosphere is a PHYSICAL event, so it does not ask who is holding a key.
 *
 * <p>This is {@code VSShipEntryE2ETest}'s piloted climb minus one line — its {@code ff-input}
 * arrangement — and that difference is the whole subject: a pilot is the only thing that changes
 * between the two, and the outcome must not depend on it. The mode it protects is the mod's own
 * autopilot on the ordinary path: with no input the flight computer keeps commanding the retained
 * velocity setpoint, so a ship under cruise is physically flying while the deleted {@code flying} flag
 * read false for it — the one flight mode where nobody is watching was exactly the one that could not
 * leave a planet.</p>
 *
 * <h2>Why this is its own class, and not a third method next door</h2>
 *
 * <p>It lives alone because of a defect in something else, measured 2026-08-11 and NOT in this
 * subject: <b>the THIRD {@code entry-setup} in one server boot cannot enter space</b>, whichever
 * scenario occupies that position. Three readings establish it:</p>
 *
 * <ul>
 *   <li>added as a third method next door, this leg fails <b>2/2 cache-busted runs</b> with
 *       {@code {"pending":0,"ships":0}} — a status that structurally excludes every settle outcome, so
 *       the entry was never requested;</li>
 *   <li>run <b>ALONE it passes</b>, so the unpiloted crossing itself works and the subject is
 *       innocent;</li>
 *   <li>before this leg existed the same failure landed on the <b>piloted sibling</b> instead — the
 *       class has no {@code @FixMethodOrder}, so JUnit's hash order reshuffles when a method is added
 *       or renamed, and the red follows the third POSITION rather than any particular test.</li>
 * </ul>
 *
 * <p>Narrowing the probe stack's slot set to its own slots (the binder used to see the whole pool) did
 * NOT fix it, which rules that hypothesis out by control. A server JVM is started per CLASS here
 * ({@code @BeforeClass}/{@code @AfterClass}), so a class of its own gives this leg a boot in which it is
 * the FIRST consumer — the home the bug ledger asked for rather than a red shipped or an order pinned
 * to hide which scenario lands third.</p>
 *
 * <p>Gated on the server's real VS presence (run with); skips cleanly otherwise.</p>
 */
public class VSUnpilotedEntryE2ETest extends AbstractSharedServerTest {


    /**
     * Budgets in SERVER TICKS: 600 is the thirty seconds the old 120 x 250 ms meant on an idle box,
     * 200 the ten of 40 x 250 ms. A crossing needs ticks, not a share of a box.
     */
    private static final int SETTLE_TICKS = 600;

    /** Where the ship is built — its own region, clear of every other server-tier fixture. */
    private static final int SRC_X = 6800, SRC_Y = FixtureSite.OPEN_AIR_Y, SRC_Z = 6800;
    /** A world Y comfortably above the default orbit ceiling (ARConfiguration.orbit = 1000). */
    private static final int ABOVE_CEILING_Y = 1200;

    @After
    public void cleanup() throws Exception {
        exec("artest space entry-clear");
    }

    @Test
    public void anUnpilotedShipClimbingPastTheCeilingStillEntersSpace() throws Exception {

        String setup = exec("artest space entry-setup 2");
        assertTrue("entry setup failed: " + setup, Reply.of(setup).ok());

        EntryStatus control = EntryStatus.wholeLedger(this::exec);
        assertEquals("witness sensitivity control — no ship must be ledgered before the climb: " + control,
                0, control.ships);

        String coords = placeFixture(FixtureSite.openAir(0, SRC_X, SRC_Z), "with-pilot-seat");
        String asm = exec("artest rocket assemble 0 " + coords);
        assertTrue("with VS an AFC-bearing build must route to a ship (no rocket): " + asm,
                (Reply.of(asm).integer("rocketCount") == 0));
        assertTrue("the source VS ship never loaded", loadedShips(0) >= 1);

        String launch = exec("artest space launch-cell 0");
        assertTrue("launch-cell resolve failed: " + launch, Reply.of(launch).ok());
        String expectedCell = extractString(launch, "cellKey");
        assertTrue("launch dim resolved to no cell: " + launch, expectedCell != null);

        String durableId = ShipIdentity.nameFromAssembly(asm);
        String vsId = ShipIdentity.physicsIdOf(this::exec, 0, durableId);

        ShipInfo src = ShipInfo.byId(this::exec, 0, vsId);
        double sx = src.x, sy = src.y, sz = src.z;

        // THE ONE DIFFERENCE from the piloted leg, and it is ASSERTED rather than assumed: this ship's
        // own flight computer holds no pilot input. The probe reports the state it left behind, so
        // "nobody is at the controls" is a reading rather than a hope — the earlier form of this line
        // scrubbed a world-wide static, which said nothing about THIS ship.
        String hands = exec("artest vs ff-input-by-id 0 " + vsId);
        assertTrue("this ship's flight computer must resolve, and hold NO pilot input, or the climb"
                + " below is the piloted leg again: " + hands,
                Reply.of(hands).bool("afcResolved") && "null".equals(Reply.of(hands).text("input")));

        String tp = exec("artest vs teleport-ship-by-id 0 " + vsId + " "
                + (int) sx + " " + ABOVE_CEILING_Y + " " + (int) sz);
        assertTrue("climb teleport failed: " + tp, Reply.of(tp).ok());
        // Marked before the unpark, which is what lets the entry start: the arrival is announced
        // once, and a mark taken after it would wait for a second entry.
        long entryMark = events.mark();
        exec("artest vs unpark-by-id 0 " + vsId);

        // Linked on the record the entry publishes at its settle. What this scenario is ABOUT is
        // that the announcement happens at all for an unpiloted craft, so the announcement is the
        // right thing to wait for: the crossing is world plus geometry, and an atmosphere does not
        // check whose hands are on the stick.
        awaitEnteredSpace(events, entryMark, durableId,
                "a ship with NOBODY at the controls must still cross out of the atmosphere",
                SETTLE_TICKS, () -> loadAllEntrySlots(setup));
        EntryStatus status = EntryStatus.forShip(this::exec, durableId).requireFound(
                "the arrival was announced, so the ledger must hold this craft's row");
        assertEquals("entry settled in a different cell than the launch resolver answers", expectedCell,
                status.cellKey);
    }

    // --- helpers (byte-identical to VSShipEntryE2ETest's, as the server-tier classes keep them) ------

    /** This class's reader of the server's ordered event log. */
    private final Events events =
            new Events(this::exec, ticks -> GameTicks.advanceWorld(client(), 0, ticks));

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    /** Keep every slot world's ships load-queued while a wait runs. See {@link EntrySlots}. */
    private void loadAllEntrySlots(String setup) throws Exception {
        EntrySlots.loadAll(this::exec, setup);
    }

    /** How many ships are LOADED in {@code dim} right now. A read, not a wait: measured across this
     *  tier at one and at six forks, the ship is already loaded whenever a scenario asks. */
    private int loadedShips(int dim) throws Exception {
        return ShipReadiness.loadedCount(this::exec, dim);
    }


    /**
     * WHERE this scenario's craft stands, and the first link that says the volume is empty.
     *
     * <p>What stood here was a pair: a {@code clearArea} that ran a chunk warmup and an air fill
     * over {@code y-2 .. y+12}, and a {@code placeFixture} that laid the blocks. The fill DUG
     * rather than asked, and threw away its own answer — {@code placed}, the count of blocks that
     * were standing in the volume. The shared builder asks instead, and on an open-air site
     * anything found is an arrangement failure that names itself. The warmup went with it: the
     * fill force-loads every chunk in its own box, so the first link was already doing that job.</p>
     *
     * <p>HALO 4 and HEIGHT 12 are the old volume's own numbers, kept rather than re-derived:
     * they are what this scenario's green runs were taken over.</p>
     */
    private String placeFixture(FixtureSite site, String variant) throws Exception {
        int[] bp = RocketFixture.placeAt(site, this::exec, variant, 4, 12,
                "the craft this scenario builds stands in this volume");
        return bp[0] + " " + bp[1] + " " + bp[2];
    }

    private static int extractInt(String json, String key) {
        // absence is the answer, and WHICH answer is the CALLER's: this verb is handed a
        // FIELD name, so it cannot know what a missing one means — and the callers here
        // include waits, which read the shape that does not carry the field yet.
        return Reply.of(json).integerOr(key, Integer.MIN_VALUE);
    }

    private static double extractDouble(String json, String key) {
        // absence is the answer, and WHICH answer is the CALLER's: this verb is handed a
        // FIELD name, so it cannot know what a missing one means — and the callers here
        // include waits, which read the shape that does not carry the field yet.
        return Reply.of(json).numberOr(key, 0.0);
    }

    private static String extractString(String json, String key) {
        // absence is the answer, and WHICH answer is the CALLER's: this verb is handed a
        // FIELD name, so it cannot know what a missing one means — and the callers here
        // include waits, which read the shape that does not carry the field yet.
        return Reply.of(json).textOr(key, null);
    }
}
