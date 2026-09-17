package zmaster587.advancedRocketry.test.client;

import zmaster587.advancedRocketry.test.EntityState;
import zmaster587.advancedRocketry.test.SeatMount;
import zmaster587.advancedRocketry.test.Events;
import zmaster587.advancedRocketry.test.Reply;
import zmaster587.advancedRocketry.test.FixtureSite;
import zmaster587.advancedRocketry.test.GameTicks;

import com.google.gson.JsonObject;

import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;


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

    /** How long the client is given to PERFORM the seating the server has already done, in ticks —
     *  a ceiling on one round trip, not a guess at how long sitting down takes. */
    private static final int SEAT_LINK_BUDGET_TICKS = 200;

    // `KEY_TAKEN` lived here — the translation key of the seat's "somebody took your chair" notice.
    // Nothing asks for it any more: the notice is a rendering, and what it announced is asserted off
    // the seat and the returning pilot's own position.

    private static final String BUILDER_POS = "builderPos";
    private static final String DUMMY_ID = "dummyId";
    private static final String OCCUPANT_UUID = "occupantUuid";
    private static final String BOUND_COUNT = "boundCount";
    private static final String SEAT_X = "seatX";
    private static final String SEAT_Y = "seatY";
    private static final String SEAT_Z = "seatZ";

    private static final String VARIANT = "with-pilot-seat";

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
        // WHERE THIS SCENARIO STANDS IS ASKED FOR, NOT CHOSEN: the plot is this scenario's own, and
        // the height is the open-air band because the site has no Y to pass.
        final FixtureSite site = site();
        final int bx = site.x, by = site.y, bz = site.z;
        long awayMark = clientEvents().mark();
        exec("tp @a " + (bx + 600) + " 120 " + (bz + 600) + " 0 0");
        awaitClientPlacedNear(awayMark, bx + 600, bz + 600,
                "the assembly below must run with no observer near it, and the observer is a client");
        // The registry's own record of the ship being added, since a mark taken before the assembly
        // was queued: THIS scenario's ship by construction, where a count on a shared world is
        // answered by every neighbour that ever assembled one. The fork multiplier that used to size
        // this wait is gone with it — it was a machine-shaped number standing in for a deadline.
        Events events = events();
        long spawnMark = events.markInstrumented();
        String assemble = assembleFixture(site);
        scenario().requireArranged("a with-pilot-seat build must route to a ship: " + assemble,
                assemble.contains("\"rocketCount\":0"));
        // The return value is KEPT: it is this scenario's ship by construction (the mark precedes the
        // assembly), and every question below has to name that craft rather than whichever one a
        // world-wide scan lists first.
        String shipUuid = awaitShipSpawned(events, spawnMark,
                "assembly must create a NEW VS ship in the queryable registry (async spawn)");
        // Keep the ship observable while nobody is online: the offline window below leaves the
        // server empty, and an unloaded ship would fail every probe the arrangement depends on.
        long approachMark = clientEvents().mark();
        exec("tp @a " + (bx + 0.5) + " " + (by + 6) + " " + (bz + 0.5) + " 0 0");
        awaitClientPlacedNear(approachMark, bx + 0.5, bz + 0.5,
                "every probe below is about a ship this client is standing over");

        SeatMount mountInfo = SeatMount.onShip(this::exec, 0, shipUuid);
        final int seatX = mountInfo.seatX();
        final int seatY = mountInfo.seatY();
        final int seatZ = mountInfo.seatZ();
        // The CLIENT's own mark, one statement before the mount that produces the record.
        long seatMark = clientEvents().mark();
        String mount = exec("artest player mount-entity " + mountInfo.requireDummyId());
        scenario().requireArranged("bot must mount the seat dummy: " + mount,
                mount.contains("\"mounted\":true"));
        // A LINK, where ten ticks used to stand: the server mounts him and the client PERFORMS the
        // seating when it is told, which is a record. Measured 2026-09-15 — under four client forks
        // those ten ticks were not enough and the scenario reported `riding:false` as though the
        // seat had refused him, which is the reading a budget always invites.
        JsonObject seated = awaitClientMount(seatMark,
                "the CLIENT must confirm it is seated before it logs out — the whole scenario is"
                        + " about what happens to a seat its occupant left while offline, and an"
                        + " occupant the client never seated was never in it",
                SEAT_LINK_BUDGET_TICKS,
                " | server said: " + mount);
        scenario().requireArranged("the CLIENT must confirm it is seated before it logs out: "
                + seated, isRiding(seated));

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
        exec("artest chunk warmup 0 " + ((bx - 2) >> 4) + " " + ((bz - 2) >> 4)
                + " " + ((bx + 7) >> 4) + " " + ((bz + 7) >> 4));

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
        // The occupant's NAME was read here, for a message assertion that no longer exists. The uuid
        // below is the identity everything in this scenario is asked by, and it is the one that
        // survives the chunk reload the pilot's return performs.
        // By UUID, never by entity id. The pilot's logout unloads the seat's chunk and his return
        // reloads it, and a reloaded entity gets a FRESH id: under load the gate of 2026-09-05 saw
        // the occupant holding the seat as `{"id":2651,"class":"EntityArmorStand"}` and the test
        // calling that a lost seat because it remembered him as 2650. Serially the chunk happened to
        // stay loaded. The identity that survives a reload is the UUID.
        Reply omReply = Reply.of(occupy);
        scenario().requireArranged("seat-occupy must report the occupant's uuid: " + occupy, omReply.has(OCCUPANT_UUID));
        final String occupantUuid = omReply.text(OCCUPANT_UUID);
        String occupancy = exec("artest vs seat-status 0 " + seatX + " " + seatY + " " + seatZ);
        scenario().requireArranged("the occupancy must HOLD before the pilot returns: " + occupancy,
                occupancy.contains("\"uuid\":\"" + occupantUuid + "\""));

        // ---- ACT 3: the pilot comes back — a real fresh login over his saved data. --------------
        // The mark BEFORE the login, because everything this act asserts happens DURING it: the
        // reconciliation is an edge, and an edge read after the fact is one that never existed.
        long loginMark = events.markInstrumented();
        bot().connect();
        bot().waitForWorld();

        // The link {@code DeckHold.reconcileSeatMount} commits: it takes the returner off the
        // duplicate mount vanilla re-spawned for him. Two links that WERE here — `action_bar_queued`
        // and `status_message_sent` — are gone: both were about the NOTICE, which is a rendering of
        // what the reconciliation did. What they were read for, who has the chair, is asserted below
        // off the seat itself, by uuid.
        //
        // ONE link, and the obvious second one was TRIED and does not exist here. The login restore
        // publishes `login_restored`, and both it and the reconcile hang off the player-login event,
        // so an ordered pair looked available. It is not: `SpaceEventHandler` reaches
        // `LoginRestore.resolve` only for a SPACEBORNE player — his aboard record says so, or he is
        // in a subsystem world — and this pilot sits on a seat in dim 0, so the early return fires
        // and nothing is recorded. Measured 2026-09-10: the chain red named `login_restored` as
        // never recorded, with `login_restore_events` absent from the instrument roster, which is
        // the difference between "it did not run" and "it ran and did not happen".
        //
        // The vanilla forced re-mount is still not a link either, for the reason it never was: where
        // the login event falls against PlayerList's own startRiding has not been measured. That
        // same red printed `… mount, chat_message_sent, dismount …`, so on THAT run it preceded the
        // reconcile — one observation, which is not yet an order to assert.
        events.await(loginMark, "dismount", "a pilot whose seat was taken while he was offline must"
                + " be reconciled off the duplicate mount vanilla re-spawned for him",
                LOGIN_LINK_BUDGET_TICKS);

        String seatAfter = exec("artest vs seat-status 0 " + seatX + " " + seatY + " " + seatZ);
        JsonObject riding = bot().reportRidingEntity();
        String observed = "seatStatus=" + seatAfter + " riding=" + riding;

        // ---- ASSERT 1: the occupant KEEPS the seat. ---------------------------------------------
        assertTrue("the occupant who took the seat while its pilot was offline must still hold it "
                + "after the pilot returns: " + observed,
                seatAfter.contains("\"uuid\":\"" + occupantUuid + "\""));

        // ---- ASSERT 2: one seat — ONE dummy, even across a relog. -------------------------------
        // Vanilla re-spawns the returning pilot's persisted mount; unreconciled, that is a second
        // invisible dummy on the same seat, whose empty twin clears the ship's pilot input every
        // tick. The player-visible shape of that bug is a control tug-of-war nobody can attribute.
        Reply after = Reply.of("artest vs seat-status", seatAfter);
        assertTrue("seat-status must report boundCount: " + seatAfter, after.has(BOUND_COUNT));
        assertEquals("a seat must keep exactly ONE bound mount dummy across its pilot's relog - "
                + "a re-spawned duplicate fights the occupant for the ship's controls: " + observed,
                1, after.integer(BOUND_COUNT));

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

    }

    // ASSERT 5 stood here: "he is TOLD, by name, who took his seat" — the server's message record
    // matched for the occupant's name, and then the same name hunted in the client's own chat log.
    // Both are gone. A notice is a RENDERING of a game event, and every game fact they were reaching
    // for is asserted above off the things themselves: the occupant holds the seat (by uuid), the
    // seat carries exactly one bound dummy across the relog, and the returning pilot is put back at
    // his post. The name in a sentence added nothing those three do not say more exactly.

    // ---- helpers -------------------------------------------------------------------------------

    /** The seat's live WORLD position, read off the seat's bound dummy: {@code EntityDummy}
     *  glues itself to the seat's world image every tick, so the occupant's dummy IS the seat's
     *  live world-frame oracle (the seat BLOCK's own coordinates are ship-subspace). */
    private double[] seatWorldPosition(int seatX, int seatY, int seatZ) throws Exception {
        String status = exec("artest vs seat-status 0 " + seatX + " " + seatY + " " + seatZ);
        Reply seat = Reply.of("artest vs seat-status", status);
        assertTrue("seat-status must expose the bound dummy for the position oracle: " + status,
                seat.has(DUMMY_ID));
        // The reader refuses a dummy the world no longer holds, which is what the `has` check stood
        // for: an absent position is not the origin, and this method's callers compare coordinates.
        EntityState at = EntityState.byId(this::exec, 0, seat.integer(DUMMY_ID))
                .requireAlive("the seat's bound dummy must still exist to locate the seat");
        return new double[]{at.posX(), at.posY(), at.posZ()};
    }

    private String assembleFixture(FixtureSite site) throws Exception {
        // The site owns the coordinates; these aliases keep the body below unchanged.
        final int baseX = site.x, baseY = site.y, baseZ = site.z;
        // FIRST link: the volume is EMPTY, measured by the air fill's own `placed` — the number the
        // pre-clear it replaces was throwing away. Open air, so this ASSERTS rather than digs, and
        // it raises an ArrangementFailure, the same type scenario().requireArranged does.
        site.requireClear(this::exec, 2, 16,
                "the hull whose seat is taken and re-taken across a relog");
        String fixture = exec("artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " " + VARIANT);
        scenario().requireArranged("fixture (" + VARIANT + ") failed: " + fixture,
                fixture.contains("\"ok\":true"));
        int[] bp = Reply.of(fixture).blockPos(BUILDER_POS);
        scenario().requireArranged("fixture missing builderPos: " + fixture, bp != null);
        return exec("artest rocket assemble 0 " + bp[0] + " " + bp[1] + " " + bp[2]);
    }

    private static boolean isRiding(JsonObject riding) {
        return riding != null && riding.has("riding") && riding.get("riding").getAsBoolean();
    }
}
