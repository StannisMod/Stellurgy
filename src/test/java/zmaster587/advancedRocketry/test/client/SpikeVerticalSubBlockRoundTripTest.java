package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import com.google.gson.JsonObject;

import org.junit.Test;
import zmaster587.advancedRocketry.test.Reply;
import zmaster587.advancedRocketry.test.GameTicks;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;

/**
 * SPIKE — does a CONNECTED player's sub-block position survive the client&harr;server round trip
 * high above the origin, on the <b>Y</b> axis?
 *
 * <h2>Why a separate class rather than a Y parameter on the X one</h2>
 * {@code SpikeSubBlockPositionGranularityTest} asks the same question along X, and the obvious move
 * is to give it a second ladder. That is not available, and the reason is not a preference: <b>two
 * different layers of vanilla forbid the X instrument from ever standing at a large Y.</b>
 * <ul>
 *   <li><b>Its arena cannot exist.</b> The X class stands the player on a stone floor it fills in,
 *       and {@code World.isValid} refuses any block outside {@code [0, 256)} in Y
 *       ({@code World.isOutsideBuildHeight}). There is no floor at 16 000 000, nor anything else
 *       made of blocks — the whole column above 255 is air by construction.</li>
 *   <li><b>Its stimulus cannot be spoken.</b> The X class steps the camera with plain {@code /tp},
 *       and vanilla's own coordinate parser bounds the Y argument of {@code /tp} and
 *       {@code /teleport} to <b>&plusmn;4096</b> ({@code CommandTP:77}, {@code CommandTeleport:66},
 *       via {@code CommandBase.parseCoordinate(base, arg, min, max, centerBlock)}), which throws
 *       {@code commands.generic.num.tooBig} rather than silently clamping. Every rung from 2M up
 *       would be refused by the command, not by the coordinate.</li>
 * </ul>
 * So the Y question needs its own hold and its own stimulus, and pretending otherwise would have
 * produced a ladder in which nothing past the control ever moved — the exact failure the X class
 * was rewritten to remove.
 *
 * <h2>The hold, and what it costs</h2>
 * There is nothing to stand on, so the player is held by <b>spectator flight</b>: it is the only
 * hover reachable from a command, and {@code far-tp} zeroes {@code motionY} so the hover starts from
 * rest. In free fall a reading taken six ticks after the ask is already a couple of blocks out, which
 * would swamp a 0.05-block quantity.
 *
 * <p><b>Stated limit, so nobody reads this as more than it is:</b> this measures the POSITION round
 * trip on Y — a value asked for, written by the server, pushed over the wire, and read back from
 * both ends. It does <b>not</b> measure survival-mode vertical physics at a large Y, because the
 * client's movement branch under spectator flight is not the walking one. The walking half at a
 * large Y has no subject anyway: walking needs a floor, and there are no blocks up there.</p>
 *
 * <h2>The stimulus</h2>
 * Both the rung delivery and the sub-block steps go through {@code /artest player far-tp}, which
 * parses raw doubles and calls the same {@code connection.setPlayerLocation} a dimension transfer
 * calls. Using one path for both keeps the control and the rungs on the same instrument; the X class
 * could afford two paths because {@code /tp} was legal at every one of its coordinates.
 *
 * <h2>Where the ladder stops, and why it is not arbitrary</h2>
 * {@code NetHandlerPlayServer.isMovePlayerPacketInvalid} disconnects a player whose position packet
 * exceeds {@code 3.0E7} on ANY axis, Y included — measured, and the reason the top of every cell is
 * currently uninhabitable. So 24 000 000 is the last rung a connected player can be asked about at
 * all, and this ladder deliberately stays below that line: it is measuring the habitable part of the
 * band, not probing the kick.
 *
 * <h2>Acceptance, stated before the run</h2>
 * At every rung, for every offset in {0, 0.05, 0.1, 0.25, 0.5, 1.0} from the same base:
 * <ol>
 *   <li>the SERVER's {@code posY} must equal the asked position within
 *       {@value #SERVER_TOLERANCE} blocks;</li>
 *   <li>the CLIENT's own {@code posY} must agree with it within {@value #CLIENT_TOLERANCE} blocks;</li>
 *   <li>every non-zero offset must read back DISTINCT from the offset-0 base — a quantum would
 *       collapse the small ones onto it. A {@code float} carrying an absolute coordinate has an ulp
 *       of 1 block at 16M and 2 at 24M, so this is what such a narrowing would look like.</li>
 * </ol>
 * Two controls decide whether any of that is evidence, and both are asserted before the subject:
 * <ol>
 *   <li><b>The ordinary-Y rung</b> ({@value #CONTROL_Y}) runs the identical ladder through the
 *       identical hold. If the offsets do not resolve THERE, the instrument is broken and no far rung
 *       says anything.</li>
 *   <li><b>The hover holds.</b> At each rung {@code posY} is read twice across
 *       {@value #HOLD_TICKS} ticks and must not have moved. A sinking player would produce a
 *       plausible-looking error that grows with nothing but time, and it is indistinguishable from a
 *       coordinate defect once it is in the table.</li>
 * </ol>
 *
 * <p><b>Designed to come back NO.</b> If every offset resolves at 24M exactly as at 200, then the
 * position path does not narrow on Y either, and the only thing left standing between a pilot and
 * the top of the band is the packet check's Y term.</p>
 */
public class SpikeVerticalSubBlockRoundTripTest extends AbstractClientE2ETest {

    /** The control's altitude: an ordinary one, well clear of terrain, held the same way as a rung. */
    private static final int CONTROL_Y = 200;

    /**
     * The control, today's half-cell, the ratified half-cell, and the measured margin — the same
     * rungs the X ladder uses, so the two tables can be read against each other. The kick threshold
     * (3.0E7) is above the last rung on purpose; see the class javadoc.
     */
    private static final int[] Y_LADDER = {CONTROL_Y, 2_000_000, 8_000_000, 16_000_000, 24_000_000};
    private static final double[] OFFSETS = {0d, 0.05d, 0.1d, 0.25d, 0.5d, 1.0d};

    /**
     * The arena's X/Z. X is the origin, so the physics mod's reserved quadrant (X &ge; 5,094,416
     * &and; Z &ge; -25,584) cannot veto a delivery here whatever Z is; Z matches the X-ladder spikes
     * so the two runs share an arena shape.
     */
    private static final int ARENA_X = 0;
    private static final int ARENA_Z = -100_000;

    private static final int OVERWORLD = 0;

    private static final double SERVER_TOLERANCE = 0.001d;
    private static final double CLIENT_TOLERANCE = 0.05d;
    private static final double ARRIVAL_TOLERANCE = 1.0d;
    /** How far the hover may drift across {@link #HOLD_TICKS} before the reading is a falling one. */
    private static final double HOLD_TOLERANCE = 0.01d;
    private static final int HOLD_TICKS = 20;
    /** How many (deliver, settle) rounds a rung gets before it is called undeliverable. */
    private static final int DELIVERY_ATTEMPTS = 4;

    private String botName;

    private String exec(String cmd) throws Exception {
        return String.join("\n", serverClient().execute(cmd));
    }

    @Test
    public void doesASubBlockAltitudeSurviveTheRoundTripHighAboveTheOrigin() throws Exception {
        bot().waitForWorld();
        exec("gamerule sendCommandFeedback false");
        exec("gamerule logAdminCommands false");
        exec("gamerule doMobSpawning false");
        exec("gamerule doDaylightCycle false");
        exec("gamerule doWeatherCycle false");
        exec("weather clear");
        bot().setRenderDistance(4);

        String health = exec("artest player health");
        String named = Reply.of("artest player health", health).text("player");
        assertTrue("player health must echo the player name: " + health, named != null);
        botName = named;

        // The hold. Spectator is the only hover a command can ask for, and without it every reading
        // past the first tick is of a falling player.
        exec("gamemode spectator " + botName);
        exec("artest chunk forceload " + OVERWORLD + " " + (ARENA_X >> 4) + " " + (ARENA_Z >> 4));
        GameTicks.advanceWorld(serverClient(), OVERWORLD, 20);

        List<String> report = new ArrayList<>();
        List<String> inconclusive = new ArrayList<>();
        List<String> broken = new ArrayList<>();
        boolean controlHeld = false;

        // The cheapest competing explanations, asked once each, before anything is attributed to a
        // coordinate: a border that refuses a move while reporting success, and a hold that is not on.
        report.add("worldborder: " + oneLine(exec("worldborder get")));
        report.add("gamemode: " + oneLine(exec("artest player health")));

        for (int y : Y_LADDER) {
            String delivery = deliverAndHover(y);
            if (delivery != null) {
                inconclusive.add("y=" + y + " " + delivery);
                continue;
            }

            double base = Double.NaN;
            List<String> rows = new ArrayList<>();
            List<String> rungFailures = new ArrayList<>();
            for (double offset : OFFSETS) {
                double target = y + offset;
                exec("artest player far-tp " + fmt(ARENA_X + 0.5d) + " " + fmt(target) + " "
                        + fmt(ARENA_Z + 0.5d));
                GameTicks.advanceWorld(serverClient(), OVERWORLD, 6);
                bot().waitTicks(6);

                double gotServer = serverY();
                double gotClient = clientY();
                if (offset == 0d) {
                    base = gotServer;
                }
                double serverErr = Math.abs(gotServer - target);
                double clientErr = Math.abs(gotClient - gotServer);
                boolean distinct = offset == 0d || Math.abs(gotServer - base) > SERVER_TOLERANCE;

                rows.add("+" + fmt(offset) + " asked " + fmt(target)
                        + " server " + fmt(gotServer) + " (err " + fmt(serverErr) + ")"
                        + " client " + fmt(gotClient) + " (delta " + fmt(clientErr) + ")"
                        + " distinctFromBase=" + distinct);
                if (serverErr > SERVER_TOLERANCE) {
                    rungFailures.add("+" + fmt(offset) + " server missed by " + fmt(serverErr));
                }
                if (clientErr > CLIENT_TOLERANCE) {
                    rungFailures.add("+" + fmt(offset) + " client disagrees by " + fmt(clientErr));
                }
                if (!distinct) {
                    rungFailures.add("+" + fmt(offset) + " collapsed onto the base");
                }
            }

            report.add("y=" + y + (rungFailures.isEmpty() ? " OK" : " FAIL " + rungFailures));
            for (String r : rows) {
                report.add("      " + r);
            }
            if (y == CONTROL_Y) {
                controlHeld = rungFailures.isEmpty();
            } else if (!rungFailures.isEmpty()) {
                broken.add(y + rungFailures.toString());
            }
        }

        StringBuilder out = new StringBuilder("[SPIKE sub-block altitude round trip]\n");
        for (String line : report) {
            out.append("  ").append(line).append('\n');
        }
        for (String line : inconclusive) {
            out.append("  INCONCLUSIVE ").append(line).append('\n');
        }
        System.out.println(out);
        writeReport("far-altitude-subblock-roundtrip.txt", out.toString());

        // The control decides whether anything else in this run is evidence. Asserted FIRST, so a
        // broken instrument reports as a broken instrument and not as an altitude ceiling.
        assertTrue("the y=" + CONTROL_Y + " control did not resolve its own offset ladder - the "
                + "instrument is broken, so no rung here says anything about high altitudes:\n" + out,
                controlHeld);
        assertTrue("a sub-block altitude was lost at: " + broken + "\n" + out, broken.isEmpty());
    }

    // ─── arrangement ────────────────────────────────────────────────────────────

    /**
     * Delivers the player to the rung and does not return until he is HOVERING there — arrived, and
     * still at the same altitude {@value #HOLD_TICKS} ticks later. The second condition is the one
     * that matters: a player who arrived and is sinking reads back a plausible error that grows with
     * time alone, and in the table that is indistinguishable from a coordinate defect.
     *
     * @return {@code null} once he is hovering, or a reason string for the INCONCLUSIVE list
     */
    private String deliverAndHover(int y) throws Exception {
        double arrivedAt = Double.NaN;
        double heldAt = Double.NaN;
        String lastReply = "";
        for (int attempt = 1; attempt <= DELIVERY_ATTEMPTS; attempt++) {
            lastReply = exec("artest player far-tp " + fmt(ARENA_X + 0.5d) + " " + fmt(y) + " "
                    + fmt(ARENA_Z + 0.5d));
            GameTicks.advanceWorld(serverClient(), OVERWORLD, 40);
            bot().waitTicks(30);
            arrivedAt = serverY();
            if (Math.abs(arrivedAt - y) >= ARRIVAL_TOLERANCE) {
                continue;
            }
            GameTicks.advanceWorld(serverClient(), OVERWORLD, HOLD_TICKS);
            bot().waitTicks(HOLD_TICKS);
            heldAt = serverY();
            if (Math.abs(heldAt - arrivedAt) < HOLD_TOLERANCE) {
                return null;
            }
        }
        boolean arrived = Math.abs(arrivedAt - y) < ARRIVAL_TOLERANCE;
        return (arrived
                ? "he arrived but would not hover (posY drifted from " + fmt(arrivedAt) + " to "
                        + fmt(heldAt) + " in " + HOLD_TICKS + " ticks - the hold is off, so every "
                        + "reading here would be of a falling player)"
                : "the player never arrived (server posY=" + arrivedAt + ", wanted " + y + ")")
                + " after " + DELIVERY_ATTEMPTS + " deliveries - arrangement, not the coordinate."
                + " lastReply=" + oneLine(lastReply);
    }

    // ─── instruments ────────────────────────────────────────────────────────────

    private double serverY() throws Exception {
        return field(exec("artest player health"), "posY");
    }

    /** The CLIENT's own record of how high it thinks it is — the far end of the round trip. */
    private double clientY() throws Exception {
        JsonObject state = bot().reportState();
        return state.has("playerY") ? state.get("playerY").getAsDouble() : Double.NaN;
    }

    private static double field(String json, String key) {
        return Reply.of(json).number(key);
    }

    /** The report is the deliverable, so it also lands on disk and survives a truncated console. */
    private static void writeReport(String name, String text) {
        try {
            java.nio.file.Path dir = java.nio.file.Paths.get("build", "spike-reports").toAbsolutePath();
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Files.write(dir.resolve(name), text.getBytes("UTF-8"));
        } catch (Exception e) {
            System.out.println("[SPIKE] could not write the report file: " + e);
        }
    }

    private static String oneLine(String s) {
        return s.replace((char) 10, ' ').replace((char) 13, ' ').trim();
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.4f", v);
    }
}
