package zmaster587.advancedRocketry.test.client;

import zmaster587.advancedRocketry.test.Events;
import zmaster587.advancedRocketry.test.GameTicks;

import com.google.gson.JsonObject;

import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A pilot seat TAKEN while its pilot was OFFLINE stays with the occupant. The returning pilot is
 * restored STANDING aboard his ship — never seated, never fighting the occupant for the chair —
 * and is told, by name, who took his seat. And through it all the seat keeps exactly ONE bound
 * mount dummy: vanilla persists a seated player's mount inside his own player data and re-spawns
 * it at login, so without a reconciliation the returning pilot brings a DUPLICATE dummy back with
 * him — two invisible mounts on one seat, the empty one clearing the ship's pilot input every tick
 * (a control tug-of-war), and two riders both believing they hold the chair.
 *
 * <p><b>Why this must be a client test.</b> The subject is a live player's LOGIN — the one seam
 * every lower tier fakes. The offline window is real: the client genuinely quits the server (his
 * player data, mount included, is written to disk), the seat is taken while the world runs without
 * him, and his return is a real fresh login that re-reads that data. The refusal message is read
 * from BOTH ends — the server's record of what it sent and with which translation arguments, and
 * the client's record of the line its HUD was handed — so "the chair was lost silently" is told
 * apart from "he was told and the message had faded before anyone looked"; the not-seated outcome
 * comes off the client's own riding report.</p>
 *
 * <p><b>Subject on the hard side:</b> a real ASSEMBLED ship (the seat block lives in ship
 * subspace, its dummy at the seat's live world position — the frame split that every seat-binding
 * bug in this repo has lived in), not a loose world seat. The boarding is the {@code vs seat-mount}
 * probe + mount-entity (the harness cannot right-click a post-assembly ship-subspace block); the
 * occupant is the {@code vs seat-occupy} armor stand (no AI, holds the seat indefinitely — a
 * second human client is not needed to make the seat contested).</p>
 *
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class VSPilotSeatTakenWhileOfflineE2ETest extends AbstractSharedVsClientE2ETest {

    @Override
    protected String subsystem() {
        return "vs-pilot-seat-offline";
    }

    /**
     * World the server is given to notice the logout, in SERVER ticks - the old 40 x 250 ms with a
     * fork multiplier on top. The client cannot supply a clock here: it is the thing that went away.
     */
    private static final int LOGOUT_TICKS = 200;

    /**
     * How long one link of the login reconciliation may take, in the event clock's ticks. The
     * refusal message is queued twenty server ticks past the login and delivered after that, and the
     * whole round trip crosses a fresh world load — so this is deliberately generous. It is a
     * DEADLINE for discrete events, not a guess at how long a value settles, and unlike the overlay
     * poll it replaces, arriving late costs nothing: the records are buffered.
     */
    private static final int LOGIN_LINK_BUDGET_TICKS = 600;

    /** The key the seat's "somebody took your chair" refusal is composed from. The key is the
     *  message's identity — the lang file and any resource pack are keyed on it. */
    private static final String KEY_TAKEN = "msg.pilotseat.taken";

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern DUMMY_ID = Pattern.compile("\"dummyId\":(-?\\d+)");
    private static final Pattern OCCUPANT_NAME = Pattern.compile("\"occupantName\":\"([^\"]+)\"");
    private static final Pattern OCCUPANT_UUID = Pattern.compile("\"occupantUuid\":\"([^\"]+)\"");
    private static final Pattern BOUND_COUNT = Pattern.compile("\"boundCount\":(-?\\d+)");
    private static final Pattern SEAT_AT = Pattern.compile(
            "\"seatX\":(-?\\d+),\"seatY\":(-?\\d+),\"seatZ\":(-?\\d+)");
    private static final Pattern POS = Pattern.compile(
            "\"posX\":(-?[0-9.E\\-]+),\"posY\":(-?[0-9.E\\-]+),\"posZ\":(-?[0-9.E\\-]+)");

    private static final String VARIANT = "with-pilot-seat";
    private static final int BX = 7600, BY = 64, BZ = 7600;

    /** The account the client harness plays under — the server keys his data and probes by it. */
    private static final String BOT = "ForgeTestClient";

    /**
     * How far off his seat the restored-standing pilot may be and still count as "aboard at his
     * post". Covers the deck spot beside/under the seat plus settle drift; far too small to be
     * satisfied by a world-spawn fallback or a fall off the hull.
     */
    private static final double ABOARD_EPSILON = 6.0;

    @Test
    public void aSeatTakenWhileThePilotWasOfflineStaysWithTheOccupant() throws Exception {

        // ---- ARRANGE: build + assemble a piloted ship, seat the client player on it. ------------
        exec("tp @a " + (BX + 600) + " 120 " + (BZ + 600) + " 0 0");
        bot().waitTicks(10);
        // The registry's own record of the ship being added, since a mark taken before the assembly
        // was queued: THIS scenario's ship by construction, where a count on a shared world is
        // answered by every neighbour that ever assembled one. The fork multiplier that used to size
        // this wait is gone with it — it was a machine-shaped number standing in for a deadline.
        Events events = events();
        long spawnMark = events.markInstrumented();
        String assemble = assembleFixture(BX, BY, BZ);
        scenario().requireArranged("a with-pilot-seat build must route to a ship: " + assemble,
                assemble.contains("\"rocketCount\":0"));
        awaitShipSpawned(events, spawnMark,
                "assembly must create a NEW VS ship in the queryable registry (async spawn)");
        // Keep the ship observable while nobody is online: the offline window below leaves the
        // server empty, and an unloaded ship would fail every probe the arrangement depends on.
        exec("artest vs permaload true");
        exec("tp @a " + (BX + 0.5) + " " + (BY + 6) + " " + (BZ + 0.5) + " 0 0");
        bot().waitTicks(40);

        String mountInfo = exec("artest vs seat-mount 0");
        Matcher dm = DUMMY_ID.matcher(mountInfo);
        scenario().requireArranged("seat-mount must report a dummy id: " + mountInfo, dm.find());
        Matcher sm = SEAT_AT.matcher(mountInfo);
        scenario().requireArranged("seat-mount must report the seat's block pos: " + mountInfo,
                sm.find());
        final int seatX = Integer.parseInt(sm.group(1));
        final int seatY = Integer.parseInt(sm.group(2));
        final int seatZ = Integer.parseInt(sm.group(3));
        String mount = exec("artest player mount-entity " + dm.group(1));
        scenario().requireArranged("bot must mount the seat dummy: " + mount,
                mount.contains("\"mounted\":true"));
        bot().waitTicks(10);
        scenario().requireArranged("the CLIENT must confirm it is seated before it logs out: "
                + bot().reportRidingEntity(), isRiding(bot().reportRidingEntity()));

        // ---- ACT 1: a REAL logout that leaves the world running (disconnect half only). ---------
        // The client is away, so it has no world of its own to wait in - but the SERVER is still
        // ticking, and processing a disconnect is something it does on a tick. So the log is read on
        // the SERVER's clock: every poll advances the server's own tick counter and then asks again.
        // The mark is taken before the disconnect, so the record cannot be missed between two reads.
        Events offlineEvents = new Events(this::exec,
                ticks -> GameTicks.advance(serverClient(), GameTicks.server(), ticks));
        long logoutMark = offlineEvents.markInstrumented();
        bot().disconnect();
        String loggedOut = offlineEvents.await(logoutMark, "player_logged_out",
                "the server must FINISH handling the pilot's disconnect (his player data, mount"
                        + " included, written to disk) before the seat can be taken behind his back",
                LOGOUT_TICKS);
        scenario().requireArranged("the logout record must be the PILOT's: " + loggedOut,
                loggedOut.contains("\"who\":\"" + BOT + "\""));
        // The record says what he was riding as he left, which is exactly the premise the seat check
        // below rests on: vanilla takes a SEATED player's mount with him into his own player data.
        // Asserted here rather than inferred there, so a pilot who somehow left the seat first fails
        // as an arrangement problem and not as "the seat kept a dummy it should not have".
        scenario().requireArranged("the pilot must have gone OFFLINE STILL SEATED — his mount is what"
                + " vanilla persists inside his player data and re-spawns at his return, and this"
                + " whole scenario is about that duplicate: " + loggedOut,
                loggedOut.contains("\"riding\":\"EntityDummy\""));

        // With no player near them the ship's chunks can drop out from under the probes below —
        // force them back in before acting on the seat.
        exec("artest chunk warmup 0 " + ((BX - 2) >> 4) + " " + ((BZ - 2) >> 4)
                + " " + ((BX + 7) >> 4) + " " + ((BZ + 7) >> 4));

        // Vanilla takes a seated player's mount WITH him into his player data — witnessed here
        // because the occupy below must therefore spawn the seat's fresh (single) dummy, and
        // because it is exactly what the returning login will try to re-spawn back into the world.
        String seatWhileGone = exec("artest vs seat-status 0 " + seatX + " " + seatY + " " + seatZ);
        scenario().requireArranged("with its pilot offline the seat must have NO bound dummy left "
                + "(vanilla persists the mount inside the player's own data): " + seatWhileGone,
                seatWhileGone.contains("\"dummyFound\":false"));

        // ---- ACT 2: someone takes the seat while he is offline. ---------------------------------
        String occupy = exec("artest vs seat-occupy 0 " + seatX + " " + seatY + " " + seatZ);
        scenario().requireArranged("the seat-occupy probe must seat an NPC occupant: " + occupy,
                occupy.contains("\"ok\":true") && occupy.contains("\"mounted\":true"));
        Matcher nm = OCCUPANT_NAME.matcher(occupy);
        scenario().requireArranged("seat-occupy must report the occupant's name: " + occupy, nm.find());
        final String occupantName = nm.group(1);
        // By UUID, never by entity id. The pilot's logout unloads the seat's chunk and his return
        // reloads it, and a reloaded entity gets a FRESH id: under load the gate of 2026-09-05 saw
        // the occupant holding the seat as `{"id":2651,"class":"EntityArmorStand"}` and the test
        // calling that a lost seat because it remembered him as 2650. Serially the chunk happened to
        // stay loaded. The identity that survives a reload is the UUID.
        Matcher om = OCCUPANT_UUID.matcher(occupy);
        scenario().requireArranged("seat-occupy must report the occupant's uuid: " + occupy, om.find());
        final String occupantUuid = om.group(1);
        String occupancy = exec("artest vs seat-status 0 " + seatX + " " + seatY + " " + seatZ);
        scenario().requireArranged("the occupancy must HOLD before the pilot returns: " + occupancy,
                occupancy.contains("\"uuid\":\"" + occupantUuid + "\""));

        // ---- ACT 3: the pilot comes back — a real fresh login over his saved data. --------------
        // Both marks BEFORE the login, because everything this act asserts happens DURING it. The
        // refusal message used to be hunted on the action bar, which the client counts down and
        // discards about four seconds later: a reader that arrived after the fade could not tell a
        // silently-lost chair from a message that was shown, and the fork multiplier on that loop was
        // buying nothing but a bigger chance of watching an empty bar. A record waits.
        long loginMark = events.markInstrumented();
        long loginClientMark = bot().eventMark().get("seq").getAsLong();
        bot().connect();
        bot().waitForWorld();

        // The links DeckHold.reconcileSeatMount commits, in its own source order: it takes the
        // returner off the duplicate mount vanilla re-spawned for him, and then — after the whole
        // restore — queues him the notice naming the occupant, deliberately delayed past the join
        // flood; the delivery cannot precede the queueing. The vanilla forced re-mount that PRECEDES
        // all three is deliberately NOT on this chain: where the login event falls against
        // PlayerList's own startRiding is a fact of a run, and this test has not measured it.
        events.assertChain(loginMark, "a pilot whose seat was taken while he was offline must be"
                        + " reconciled off the duplicate mount and TOLD who has his chair",
                LOGIN_LINK_BUDGET_TICKS,
                "dismount", "action_bar_queued", "status_message_sent");
        String queued = events.since(loginMark, "action_bar_queued");
        assertTrue("the notice queued for the returning pilot must be the seat-taken one, keyed on "
                        + KEY_TAKEN + ": " + queued,
                queued.contains("\"key\":\"" + KEY_TAKEN + "\""));

        String seatAfter = exec("artest vs seat-status 0 " + seatX + " " + seatY + " " + seatZ);
        JsonObject riding = bot().reportRidingEntity();
        String sent = events.since(loginMark, "status_message_sent");
        String observed = "seatStatus=" + seatAfter + " riding=" + riding
                + " statusMessages=" + sent;

        // ---- ASSERT 1: the occupant KEEPS the seat. ---------------------------------------------
        assertTrue("the occupant who took the seat while its pilot was offline must still hold it "
                + "after the pilot returns: " + observed,
                seatAfter.contains("\"uuid\":\"" + occupantUuid + "\""));

        // ---- ASSERT 2: one seat — ONE dummy, even across a relog. -------------------------------
        // Vanilla re-spawns the returning pilot's persisted mount; unreconciled, that is a second
        // invisible dummy on the same seat, whose empty twin clears the ship's pilot input every
        // tick. The player-visible shape of that bug is a control tug-of-war nobody can attribute.
        Matcher bc = BOUND_COUNT.matcher(seatAfter);
        assertTrue("seat-status must report boundCount: " + seatAfter, bc.find());
        assertEquals("a seat must keep exactly ONE bound mount dummy across its pilot's relog - "
                + "a re-spawned duplicate fights the occupant for the ship's controls: " + observed,
                1, Integer.parseInt(bc.group(1)));

        // ---- ASSERT 3: the returner is NOT seated — twice, so a late re-mount cannot hide. ------
        assertFalse("a pilot whose seat was taken while he was offline must NOT come back seated: "
                + observed, isRiding(riding));
        bot().waitTicks(20);
        JsonObject ridingLater = bot().reportRidingEntity();
        assertFalse("...and must STAY unseated (no delayed re-mount stealing the seat back): "
                + ridingLater, isRiding(ridingLater));

        // ---- ASSERT 4: he is restored STANDING ABOARD, at his post — not dropped at spawn, -----
        // not fallen off the hull. Client-observed position against the seat's live world
        // position (server oracle, read fresh: the ship may have settled).
        double[] seatWorld = seatWorldPosition(seatX, seatY, seatZ);
        JsonObject state = bot().reportState();
        assertTrue("the client must report a player position: " + state,
                state.get("worldReady").getAsBoolean());
        double cx = state.get("playerX").getAsDouble();
        double cy = state.get("playerY").getAsDouble();
        double cz = state.get("playerZ").getAsDouble();
        String posObserved = "client=(" + cx + "," + cy + "," + cz + ") seatWorld=("
                + seatWorld[0] + "," + seatWorld[1] + "," + seatWorld[2] + ") " + observed;
        assertEquals("the displaced pilot must be restored ABOARD at his post on X: " + posObserved,
                seatWorld[0], cx, ABOARD_EPSILON);
        assertEquals("the displaced pilot must be restored ABOARD at his post on Y: " + posObserved,
                seatWorld[1], cy, ABOARD_EPSILON);
        assertEquals("the displaced pilot must be restored ABOARD at his post on Z: " + posObserved,
                seatWorld[2], cz, ABOARD_EPSILON);

        // ---- ASSERT 5: he is TOLD, by name, who took his seat. ----------------------------------
        // Two halves, because they fail for different reasons and a single overlay poll conflated
        // them. The SERVER half pins the name exactly — it is a format argument of the translation,
        // not a substring of a rendered sentence that a generic armour-stand name could satisfy by
        // accident. The CLIENT half says it actually arrived at the HUD.
        assertTrue("the returning pilot must be told WHO took his seat — the message the server sent"
                        + " him must carry the occupant's name (\"" + occupantName + "\") as its own"
                        + " format argument, and a silently-lost chair reads as a broken relog: "
                        + observed,
                Events.countRecords(sent, "\"" + occupantName + "\"") > 0);
        String shown = awaitClientChat(loginClientMark, occupantName, LOGIN_LINK_BUDGET_TICKS,
                "the seat-taken notice the server sent must reach the returning pilot's own HUD");
        assertTrue("the line the client was handed must name the occupant: " + shown + " | "
                + observed, shown.contains(occupantName));
    }

    /**
     * Wait for the client's HUD to be HANDED a line containing {@code needle}, and return its text.
     *
     * <p>The client half of a message, taken off {@code client_chat_received} — the harness records
     * every line the in-game HUD is given, chat and action bar alike, so a record made three seconds
     * ago is still there when this asks. The overlay poll it replaces read a FADING value, which is
     * why its budget had a fork multiplier on it: on a loaded box the reader was more likely to
     * arrive after the message had gone, and a green then meant nothing while a red said "silently
     * lost chair" about a chair that was announced.</p>
     *
     * <p>Written here rather than on the shared base because this class does not own that base;
     * three other classes in this family carry the same lines for the same reason.</p>
     */
    private String awaitClientChat(long mark, String needle, int tickBudget, String what)
            throws Exception {
        String reply = "";
        String lower = needle.toLowerCase(Locale.ROOT);
        for (int waited = 0; waited <= tickBudget; waited += 5) {
            reply = String.valueOf(bot().eventsSince(mark, "client_chat_received"));
            Matcher m = Pattern.compile("\"text\":\"([^\"]*)\"").matcher(reply);
            while (m.find()) {
                if (m.group(1).toLowerCase(Locale.ROOT).contains(lower)) {
                    return m.group(1);
                }
            }
            bot().waitTicks(5);
        }
        Events.assertInstrumentRan(reply, "client_chat_events", what);
        throw new AssertionError(what + " — no `client_chat_received` carrying \"" + needle
                + "\" within " + tickBudget + " ticks. Everything the HUD WAS handed since the mark: "
                + reply);
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** The seat's live WORLD position, read off the seat's bound dummy: {@code EntityDummy}
     *  glues itself to the seat's world image every tick, so the occupant's dummy IS the seat's
     *  live world-frame oracle (the seat BLOCK's own coordinates are ship-subspace). */
    private double[] seatWorldPosition(int seatX, int seatY, int seatZ) throws Exception {
        String status = exec("artest vs seat-status 0 " + seatX + " " + seatY + " " + seatZ);
        Matcher dm = DUMMY_ID.matcher(status);
        assertTrue("seat-status must expose the bound dummy for the position oracle: " + status,
                dm.find());
        String pos = exec("artest entity info 0 " + dm.group(1));
        Matcher pm = POS.matcher(pos);
        assertTrue("the entity-info probe must answer for the seat's dummy: " + pos, pm.find());
        return new double[]{Double.parseDouble(pm.group(1)),
                Double.parseDouble(pm.group(2)), Double.parseDouble(pm.group(3))};
    }

    private String assembleFixture(int baseX, int baseY, int baseZ) throws Exception {
        int cx1 = (baseX - 2) >> 4, cz1 = (baseZ - 2) >> 4;
        int cx2 = (baseX + 7) >> 4, cz2 = (baseZ + 7) >> 4;
        scenario().requireArranged("chunk warmup failed",
                exec("artest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2)
                        .contains("\"ok\":true"));
        scenario().requireArranged("pre-clear failed",
                exec("artest fill 0 " + (baseX - 2) + " " + (baseY + 1) + " " + (baseZ - 2)
                        + " " + (baseX + 7) + " " + (baseY + 10) + " " + (baseZ + 7) + " minecraft:air")
                        .contains("\"ok\":true"));
        String fixture = exec("artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " " + VARIANT);
        scenario().requireArranged("fixture (" + VARIANT + ") failed: " + fixture,
                fixture.contains("\"ok\":true"));
        Matcher bp = BUILDER_POS.matcher(fixture);
        scenario().requireArranged("fixture missing builderPos: " + fixture, bp.find());
        return exec("artest rocket assemble 0 " + bp.group(1) + " " + bp.group(2) + " " + bp.group(3));
    }

    private static boolean isRiding(JsonObject riding) {
        return riding != null && riding.has("riding") && riding.get("riding").getAsBoolean();
    }
}
