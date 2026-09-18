package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.Reply;
import zmaster587.advancedRocketry.test.ShipReadiness;
import zmaster587.advancedRocketry.test.GameTicks;
import zmaster587.advancedRocketry.test.EntrySlots;
import zmaster587.advancedRocketry.test.EntryStatus;
import zmaster587.advancedRocketry.test.ShipIdentity;
import zmaster587.advancedRocketry.test.ShipInfo;

import org.junit.After;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import zmaster587.advancedRocketry.test.FixtureSite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

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

    private static final String BUILDER_POS = "builderPos";

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

        clearArea(SRC_X, SRC_Z);
        String coords = placeFixture(SRC_X, SRC_Y, SRC_Z, "with-pilot-seat");
        String asm = exec("artest rocket assemble 0 " + coords);
        assertTrue("with VS an AFC-bearing build must route to a ship (no rocket): " + asm,
                (Reply.of(asm).integerOr("rocketCount", Integer.MIN_VALUE) == 0));
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
                Reply.of(hands).bool("afcResolved", false) && "null".equals(Reply.of(hands).text("input")));

        String tp = exec("artest vs teleport-ship-by-id 0 " + vsId + " "
                + (int) sx + " " + ABOVE_CEILING_Y + " " + (int) sz);
        assertTrue("climb teleport failed: " + tp, Reply.of(tp).ok());
        exec("artest vs unpark-by-id 0 " + vsId);

        final EntryStatus[] status = new EntryStatus[1];
        boolean settled = GameTicks.until(client(), GameTicks.server(), SETTLE_TICKS,
                () -> {
                    status[0] = EntryStatus.forShip(this::exec, durableId);
                    return status[0].found && status[0].settled();
                },
                () -> loadAllEntrySlots(setup));
        assertTrue("a ship with NOBODY at the controls must still cross out of the atmosphere — the"
                + " crossing is world plus geometry, and an atmosphere does not check whose hands are"
                + " on the stick; last status=" + status[0], settled);
        assertEquals("entry settled in a different cell than the launch resolver answers", expectedCell,
                status[0].cellKey);
    }

    // --- helpers (byte-identical to VSShipEntryE2ETest's, as the server-tier classes keep them) ------

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

    private void clearArea(int baseX, int baseZ) throws Exception {
        int cx1 = (baseX - 4) >> 4, cz1 = (baseZ - 4) >> 4;
        int cx2 = (baseX + 20) >> 4, cz2 = (baseZ + 20) >> 4;
        assertTrue("chunk warmup failed",
                Reply.of(exec("artest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2)).ok());
        assertTrue("pre-clear failed", Reply.of(exec("artest fill 0 " + (baseX - 4) + " " + (SRC_Y - 2) + " " + (baseZ - 4)
                + " " + (baseX + 20) + " " + (SRC_Y + 12) + " " + (baseZ + 20) + " minecraft:air")).ok());
    }

    private String placeFixture(int baseX, int baseY, int baseZ, String variant) throws Exception {
        String fixture = exec("artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " " + variant);
        assertTrue("fixture (" + variant + ") failed: " + fixture, Reply.of(fixture).ok());
        int[] bp = Reply.of(fixture).blockPos(BUILDER_POS);
        assertTrue("fixture (" + variant + ") missing builderPos: " + fixture, bp != null);
        return bp[0] + " " + bp[1] + " " + bp[2];
    }

    private static int extractInt(String json, String key) {
        return Reply.of(json).integerOr(key, Integer.MIN_VALUE);
    }

    private static double extractDouble(String json, String key) {
        return Reply.of(json).numberOr(key, 0.0);
    }

    private static String extractString(String json, String key) {
        return Reply.of(json).text(key);
    }
}
