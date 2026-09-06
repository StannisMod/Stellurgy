package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.TestTimeouts;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;
import org.lwjgl.input.Keyboard;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import zmaster587.advancedRocketry.test.Events;

import static org.junit.Assert.assertTrue;

/**
 * Flight Assist is an AUTOPILOT, not a dead-man switch: the FA state (mode + cruise setpoint) is a
 * SETTING the ship keeps executing when the pilot leaves his seat. A pilot who ramps a cruise with
 * a real held key and then dismounts leaves a ship that KEEPS CRUISING at that setpoint — it must
 * not brake to a hover the moment nobody is seated (that behaviour made the "autopilot" a
 * per-tick input echo). Re-mounting must not interrupt or reset the executing cruise either — the
 * saved setting is the pilot's to come back to.
 *
 * <p>Full honest path: the cruise is ramped by a REAL held key on the real client; the dismount
 * and re-mount are player actions (probe-driven where the harness cannot right-click a subspace
 * seat); the ship's continued motion is the server oracle. The zero-setpoint degenerate case
 * (station-hold, "the hovering ship fell" fix) stays pinned by the existing flight suite. Gated
 * on real VS —</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class VSShipUnmannedCruiseE2ETest extends AbstractSharedVsClientE2ETest {

    @Override
    protected String subsystem() {
        return "vs-ship-unmanned-cruise";
    }

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern POS_Y = Pattern.compile("\"posY\":(-?[0-9.E\\-]+)");
    private static final Pattern DUMMY_ID = Pattern.compile("\"dummyId\":(-?\\d+)");

    private static final String VARIANT = "with-pilot-seat";
    private static final int BX = 4800, BY = 64, BZ = 4800;

    /** THIS scenario's ship, by identity — the address every altitude sample uses. */
    private String shipId;

    @Test
    public void aDismountedPilotsShipKeepsCruisingAndSurvivesRemount() throws Exception {

        Events events = events();
        // The mark is taken BEFORE the assembly is queued, so the ship_spawned record it waits for is
        // THIS assembly's ship and can be nobody else's. What it replaces was a poll for an ABSOLUTE
        // ship count >= 1 — a question any ship the world already held answers — followed by a
        // nearest-ship lookup at the build site to recover an identity the registry's own record
        // carries.
        long spawnMark = events.markInstrumented();
        exec("tp @a " + (BX + 600) + " 120 " + (BZ + 600) + " 0 0");
        bot().waitTicks(10);
        String assemble = assembleFixture(BX, BY, BZ);
        assertTrue("a with-pilot-seat build must route to a ship: " + assemble,
                assemble.contains("\"rocketCount\":0"));
        shipId = awaitShipSpawned(events,
                spawnMark, "a with-pilot-seat assembly must create a VS ship in the registry");
        bot().waitTicks(40);

        exec("tp @a " + (BX + 0.5) + " " + (BY + 6) + " " + (BZ + 0.5) + " 0 0");
        bot().waitTicks(20);
        // The LOAD is the one gate here the log cannot answer: `managed:true` means the physics mod
        // owns a loaded object for this ship, and nothing records that. It stays a bounded poll — but
        // asked BY IDENTITY, so it has no distance term to be wrong about however far this ship then
        // climbs, and a `managed:false` reply means "not loaded" rather than "somebody else's ship".
        double y0 = Double.NaN;
        String lastLookup = "(never asked)";
        for (int i = 0; i < 40 && Double.isNaN(y0); i++) {
            bot().waitTicks(5);
            lastLookup = shipInfoById(shipId);
            if (lastLookup.contains("\"managed\":true")) {
                y0 = readDouble(lastLookup, POS_Y);
            }
        }
        // An ARRANGEMENT failure, and typed as one: a ship that never loaded has disproved nothing
        // about autopilots.
        scenario().requireArranged("this scenario's ship (" + shipId + ") must LOAD with the client"
                        + " present within 200 ticks — last reply " + lastLookup.replace('\n', ' ')
                        + " (a reply with \"managed\":false is a ship the physics mod does not own"
                        + " yet - a different wait, not a longer one)",
                !Double.isNaN(y0));

        // Seat the bot, ramp a vertical cruise with the REAL key (Flight Assist is on by default:
        // holding the throttle ramps the setpoint; ~3 s of full deflection reaches cruise speed).
        String mountInfo = exec("artest vs seat-mount 0");
        assertTrue("seat-mount must find the pilot seat: " + mountInfo,
                mountInfo.contains("\"seatFound\":true"));
        Matcher dm = DUMMY_ID.matcher(mountInfo);
        assertTrue("seat-mount must report a dummy id: " + mountInfo, dm.find());
        String mount = exec("artest player mount-entity " + dm.group(1));
        assertTrue("bot must mount the seat dummy: " + mount, mount.contains("\"mounted\":true"));
        bot().waitTicks(10);

        // 60 ticks of full deflection = the whole setpoint ramp (rest -> cruise speed). Kept
        // short deliberately: the ship keeps climbing for the rest of the test, and it must stay
        // within the (grounded) client's load range the whole time.
        double yRamped = y0;
        long rampMark = events.markInstrumented();
        bot().holdKey(Keyboard.KEY_R);
        try {
            // The key must reach THIS ship's flight computer before a climb can mean anything: the
            // seat forwards the client's packet and the computer is HANDED an input. Awaited as a
            // link, so "the ship never rose" cannot be reported for a key that never got there.
            // (No ship filter: this class builds one ship and flies it, and the mark is fresh.)
            awaitRecord(events, rampMark, "pilot_input_set",
                    "the real held key must reach the ship's flight computer at all", 100,
                    "\"input\":\"set\"");
            // Scale the ramp hold by the fork factor (load-tail): the setpoint ramp is driven by the
            // CLIENT re-sending the held key each tick, so under frame-starvation fewer ramp steps land
            // in a fixed 60 ticks. Scale the DURATION - no early-exit, the ramp needs the full hold and
            // a position early-exit would release before the setpoint is ramped (audit: not poll-able).
            int rampIters = (int) Math.ceil(30 * TestTimeouts.factor());
            for (int i = 0; i < rampIters; i++) {
                bot().waitTicks(2);
            }
            yRamped = shipY();
        } finally {
            bot().releaseKey(Keyboard.KEY_R);
        }
        // The hold stays a fixed DURATION — the ramp is a value climbing, not a commit, and there is
        // no single record that says "fully ramped". What the log does say is that the ramp reached
        // the computer at all: the pilot's own throttle moved the cruise setpoint (`via:"pilot"`),
        // which is the setting the whole rest of this scenario claims survives a dismount. Without it
        // a red below could not tell a ramp that never happened from an autopilot that dropped it.
        String ramped = events.since(rampMark, "cruise_setpoint_changed");
        assertTrue("the held throttle must have moved the ship's CRUISE SETPOINT — that setting, not"
                        + " the key, is what an unmanned ship keeps executing. Recorded since the"
                        + " hold began: " + ramped,
                matchingRecords(ramped, "\"via\":\"pilot\"") > 0);
        scenario().requireArranged("the held key must have ramped a real climb before the dismount "
                        + "can test anything (y0=" + y0 + " yRamped=" + yRamped + ")",
                yRamped - y0 > 2.0);
        bot().waitTicks(10);

        // Dismount mid-cruise. (The probe dismount stands in for any exit that is not the brake
        // key — standing up must not zero the cruise; braking to a stop before standing is the
        // pilot's separate, deliberate choice.)
        double yDismount = shipY();
        long dismountMark = events.markInstrumented();
        exec("artest player dismount");
        // The computer must SEE the pilot leave before "it kept cruising" says anything: the
        // riderless dummy clears the computer's pilot input, and until that lands the ship is simply
        // still being flown. This is the link the 40-tick window used to hide — a red then read as
        // two altitudes whether the dismount had reached the computer or not.
        awaitRecord(events, dismountMark, "pilot_input_set",
                "the flight computer must be told the pilot has gone, or the climb below is just a"
                        + " ship that is still being piloted", 100,
                "\"input\":\"null\"");
        bot().waitTicks(40);
        double yUnmanned = shipY();
        assertTrue("an unmanned ship with Flight Assist on and a non-zero cruise setpoint must "
                        + "KEEP CRUISING after the pilot dismounts — that is what makes it an "
                        + "autopilot (yDismount=" + yDismount + " after 2s=" + yUnmanned + ")",
                yUnmanned - yDismount > 4.0);

        // Re-mounting must not interrupt (or reset) the executing cruise: the seat's dummy is
        // REUSED and the ship flies on while the returned pilot holds no key.
        String remount = exec("artest vs seat-mount 0");
        assertTrue("seat-mount must still find the seat: " + remount,
                remount.contains("\"seatFound\":true"));
        assertTrue("the re-mount must REUSE the seat's single dummy: " + remount,
                remount.contains("\"reused\":true"));
        Matcher rm = DUMMY_ID.matcher(remount);
        assertTrue(remount, rm.find());
        String mounted = exec("artest player mount-entity " + rm.group(1));
        assertTrue("bot must re-mount the seat dummy: " + mounted,
                mounted.contains("\"mounted\":true"));
        double yRemount = shipY();
        bot().waitTicks(40);
        double yAfter = shipY();
        assertTrue("a re-mounted pilot receives the executing cruise BACK — the ship must not "
                        + "stop or reset because he sat down (yRemount=" + yRemount
                        + " after 2s=" + yAfter + ")",
                yAfter - yRemount > 4.0);
    }

    // ---- helpers -------------------------------------------------------------------------------

    private double shipY() throws Exception {
        return readDouble(shipInfoById(shipId), POS_Y);
    }

    /**
     * Wait for a record of {@code type} whose payload carries every one of {@code needles} — an
     * {@link Events#await} that can say WHICH record it means.
     *
     * <p>Local to this class because {@code Events.await} matches on the type alone, and the two
     * links this scenario waits for are distinguished only by their payload: a
     * {@code pilot_input_set} carrying {@code "set"} is the throttle arriving and one carrying
     * {@code "null"} is the pilot leaving, and waiting for "either" would let the mount satisfy the
     * dismount's wait.</p>
     */
    private String awaitRecord(Events events, long mark, String type, String what, int tickBudget,
                               String... needles) throws Exception {
        String reply = "";
        // This budget is a DEADLINE for a discrete commit with an early exit — how patient the test
        // is, never how far the world moves: the loop returns the moment the record appears, and
        // reaching the end of it is a failure either way. Scaled by the fork factor for the same
        // reason the ramp hold above is: both links are driven by the CLIENT, and a frame-starved
        // client under concurrent-fork load spends more of OUR ticks reaching the same commit.
        tickBudget = (int) Math.ceil(tickBudget * TestTimeouts.factor());
        for (int waited = 0; waited <= tickBudget; waited += 5) {
            reply = events.since(mark, type);
            if (matchingRecords(reply, needles) > 0) {
                return reply;
            }
            bot().waitTicks(5);
        }
        throw new AssertionError(what + " — no `" + type + "` carrying "
                + java.util.Arrays.toString(needles) + " was recorded within " + tickBudget
                + " ticks. Records of that type since the mark: " + reply
                + " | everything recorded since the mark, in order: "
                + Events.typesOf(events.since(mark)));
    }

    /** How many records of a {@code since} reply carry EVERY one of {@code needles}. */
    private static int matchingRecords(String sinceReply, String... needles) {
        int n = 0;
        for (String record : String.valueOf(sinceReply).split("\\{\"seq\":")) {
            // The split's first chunk is the reply's ENVELOPE, which carries no `"type"` — without
            // this guard an envelope field could be counted as a record.
            if (!record.contains("\"type\":")) {
                continue;
            }
            boolean all = true;
            for (String needle : needles) {
                if (!record.contains(needle)) {
                    all = false;
                    break;
                }
            }
            if (all) {
                n++;
            }
        }
        return n;
    }

    private double readDouble(String json, Pattern p) {
        Matcher m = p.matcher(json);
        assertTrue("expected a number in: " + json, m.find());
        return Double.parseDouble(m.group(1));
    }

    private String assembleFixture(int baseX, int baseY, int baseZ) throws Exception {
        int cx1 = (baseX - 2) >> 4, cz1 = (baseZ - 2) >> 4;
        int cx2 = (baseX + 7) >> 4, cz2 = (baseZ + 7) >> 4;
        assertTrue("chunk warmup failed",
                exec("artest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2)
                        .contains("\"ok\":true"));
        assertTrue("pre-clear failed",
                exec("artest fill 0 " + (baseX - 2) + " " + (baseY + 1) + " " + (baseZ - 2)
                        + " " + (baseX + 7) + " " + (baseY + 10) + " " + (baseZ + 7) + " minecraft:air")
                        .contains("\"ok\":true"));
        String fixture = exec("artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ
                + " " + VARIANT);
        assertTrue("fixture (" + VARIANT + ") failed: " + fixture, fixture.contains("\"ok\":true"));
        Matcher bp = BUILDER_POS.matcher(fixture);
        assertTrue("fixture missing builderPos: " + fixture, bp.find());
        return exec("artest rocket assemble 0 " + bp.group(1) + " " + bp.group(2) + " " + bp.group(3));
    }
}
