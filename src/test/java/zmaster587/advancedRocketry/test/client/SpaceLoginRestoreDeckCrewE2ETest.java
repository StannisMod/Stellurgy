package zmaster587.advancedRocketry.test.client;

import zmaster587.advancedRocketry.test.Reply;
import zmaster587.advancedRocketry.test.DeckCapture;
import zmaster587.advancedRocketry.test.Events;
import zmaster587.advancedRocketry.test.ShipIdentity;
import zmaster587.advancedRocketry.test.ShipInfo;

import com.google.gson.JsonObject;

import org.junit.Ignore;
import org.junit.Test;

import zmaster587.advancedRocketry.space.CellWorldMapper;
import zmaster587.advancedRocketry.space.GalacticCoord;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static zmaster587.advancedRocketry.test.ArrangementFailure.requireArranged;

/**
 * Relogging while standing on a ship's deck — with no server restart — must not drag the crew member
 * along it, upright or inverted.
 *
 * <p>The measurement is a no-change control in the same run: the same body, on the same deck, over
 * the same window, with the relog absent. A deck is never perfectly still, so "he moved" is not an
 * observation about the relog until you know what he does when nothing is done to him.</p>
 *
 * <p>See {@link AbstractSpaceLoginRestoreClientTest} for the shared fixture, and for why the class
 * was split into three.</p>
 */
public class SpaceLoginRestoreDeckCrewE2ETest extends AbstractSpaceLoginRestoreClientTest {

    /**
     * How far past vertical the hull must be before the relog leg means anything — deck-normal Y.
     *
     * <p>The TEST'S OWN arrangement fact: at -0.9 the craft is about 155 degrees over, which is
     * where a body that is not being carried falls off instead of sliding. Without it this leg is
     * silently the upright one again.</p>
     */
    private static final double INVERTED_UP_Y = -0.9;

    /**
     * How long the space subsystem's logout handler is given to run, in SERVER ticks - the old
     * 40 x 250 ms. The client cannot supply a clock here: it is the thing that went away, so the
     * budget is spent on {@link zmaster587.advancedRocketry.test.GameTicks#server()}.
     *
     * <p>A deadline for a discrete event, not a window for a value to settle: a logout is something
     * the server does on one tick, and this is only how long it may take to get to it.</p>
     */
    private static final int LOGOUT_TICKS = 200;

    /**
     * THE REPORTED CASE, and it is deliberately NOT the restart case: a crew member standing on his
     * deck logs out and back IN while the server keeps running.
     *
     * <p><b>Why this is a separate leg.</b> The restart leg above measures the same body, the same
     * deck and the same posture and finds the hold exact - so whatever the report is about, a restart
     * does not carry it. A restart wipes every live object: the ship is re-assembled from disk, the
     * slot dimension re-minted, the capture rebuilt from nothing. A plain relog wipes none of that.
     * If two writers are fighting over where a restored body belongs, the restart is the arrangement
     * that destroys the fight before it can be observed, and this is the one that keeps it.</p>
     *
     * <p>The slot dimension is asserted UNCHANGED here, unlike across a restart: without a reboot the
     * pool does not re-mint its ids, so a different slot would mean something moved his ship, not
     * that the ids churned.</p>
     */
    @Test
    @Ignore("HELD FOR THE BODY-MOVEMENT CONTRACT BATCH, by the maintainer's ruling of 2026-09-23:"
            + " every deck-hold red waits for the contract on moving an entity aboard a craft. Red"
            + " on a full client tier: a capture the LOGIN installed takes all six"
            + " walk inputs (6/6) and the collision sweep pins the step on five and six of them,"
            + " where a capture re-installed by a sit-and-stand walks 0.94 on the same deck. Green"
            + " alone, and green on the next full tier — so it is intermittent, not gone. RE-ENABLE"
            + " with that batch; the acceptance is this method green on a full tier, twice.")
    public void aCrewMemberWhoRelogsWithoutARestartIsNotDraggedAlongHisDeck() throws Exception {
        int slotDim = seatThePilotAboardHisShip();

        // The posture the report is about: on his feet, on his own deck.
        String tag = standUpAndAwaitTheStandingRecord(events());
        requireArranged("standing up must keep him aboard as a STANDING record: " + tag,
                Reply.of(tag).bool("tagged") && "STANDING".equals(Reply.of(tag).text("posture")));
        DeckCapture capBefore = DeckCapture.read(this::exec);
        requireArranged("he must be captured ABOARD the deck before the relog, or the leg is "
                        + "not about a restored deck capture at all: " + capBefore.raw(),
                capBefore.alreadyTracked
                        && !capBefore.hullStand);
        // On HIS deck. The capture's anchor is the PHYSICS id, and this scenario holds the durable
        // one, so the two are bridged by name rather than by asking what is standing at his feet.
        capBefore.requireAnchoredOn(
                ShipIdentity.awaitPhysicsIdOf(this::exec, events(), slotDim, arrangedShipId,
                        200),
                "the capture the relog must restore is the one on THIS scenario's own deck");

        // A REAL logout that leaves the world running. Both marks BEFORE the disconnect: the client
        // JVM is REUSED across a plain relog, so its log still holds this session's records and zero
        // would be the whole session rather than the relog.
        Events offlineLog = serverClockEvents();
        long logoutMark = offlineLog.mark();
        long clientMark = clientEvents().mark();
        bot().disconnect();
        // Waited for as the LINK it is - the space subsystem's own logout handler running - on the
        // server's clock, because the client is the thing that went away. The record it leaves
        // carries the aboard tag AS RECONCILED at that moment, which is the very state the login
        // below reads back; the poll it replaces could only see him vanish from the player list.
        String loggedOut = offlineLog.await(logoutMark, "player_logged_out",
                "a disconnect must reach the space subsystem's logout handler - everything below "
                        + "reads the record that handler leaves behind", LOGOUT_TICKS);
        requireArranged("the record he logs out with is the one his next login resolves from, so it "
                        + "must still say he was aboard his ship, on his feet: " + loggedOut,
                // ONE logout record saying both: two field tests are satisfied by a tagged logout
                // beside a different record whose posture happens to be STANDING.
                Events.anyRecordHasAll(loggedOut, "tagged", "true", "posture", "STANDING"));
        // LEFT RAW: the subject here IS the error shape. `PlayerPosition` refuses it — it must,
        // because read as a position that reply puts him at the origin of the overworld, and every
        // other site in this family is asserting which world he is in.
        String offline = exec("artest player position-of " + BOT);
        // absence is the answer: a player who is still connected answers a POSITION and no
        // `error` at all, so "no error" is the world this claim is measured against.
        requireArranged("the server must see him GONE after the disconnect, or nothing below "
                        + "is a relog: " + offline,
                "no such player".equals(Reply.of(offline).textOr("error", null))
                        || "no players connected".equals(Reply.of(offline).textOr("error", null)));

        // Nobody is left near the ship to hold its chunks while he is away.

        Events restore = events();
        long restoreMark = restore.mark();
        bot().connect();
        bot().waitForWorld();
        // His ship IS spaceborne, so the login hook owns this login: `login_restored` is its verdict
        // and names the dimension it chose. The client's own side of it is a world rebuilt from a
        // fresh join. Two logs, awaited separately - cross-side order within one tick is undefined.
        String restored = restore.await(restoreMark, "login_restored",
                "a crew member who logged out standing on his ship in a cell must be RESTORED by the "
                        + "login hook - an ordinary login would leave him wherever vanilla puts him",
                RESTORE_LINK_BUDGET_TICKS);
        String joined = awaitClientEventWithField(clientMark, "client_dimension_changed",
                "via", "join",
                "the reconnected client must be given a world. Server verdict: " + restored,
                RESTORE_LINK_BUDGET_TICKS);
        int dim = clientDim();
        assertEquals("he relogged while standing on his ship in its cell, and no reboot re-minted the "
                        + "pool, so he must come back in the very same slot dimension: clientDim="
                        + dim + " riding=" + bot().reportRidingEntity()
                        + "\n  login_restored: " + restored
                        + "\n  client dimension changes: " + joined,
                slotDim, dim);

        requireHeIsNotDraggedAlongHisDeck(dim);
    }

    /**
     * The same relog, on an INVERTED deck - the attitude the report actually comes from.
     *
     * <p><b>Why the attitude is not decoration.</b> This path is governed by the any-attitude crew
     * contract: gravity is projected along the DECK normal rather than world -Y, the floor search looks
     * below the body's feet in the SHIP frame, the aboard/hull-stand classification depends on contact
     * orientation, and the deck-plane axes change sign. An upright fixture cannot exhibit an
     * attitude-dependent defect at all - which is why fourteen upright runs of the leg above could not,
     * and why "it did not reproduce" was a statement about the arrangement, not about the code.</p>
     *
     * <p>The ship is rolled while he is ALREADY captured on the deck, so the capture carries his deck
     * spot through the roll and leaves him standing on the deck of an inverted ship - hanging under the
     * hull in world terms - the same way the planet-side inverted leg arranges it. The inversion is
     * established BEFORE the logout, on the assumption that the ship was already inverted when he left;
     * "inverted while he was away" is a different arrangement and would need its own leg.</p>
     */
    @Test
    @Ignore("RED ON A REAL DEFECT WITH A KNOWN HISTORY, and the contract it asserts is the right"
            + " one: a crew member restored onto his deck must not keep travelling once he stops"
            + " walking. MEASURED on a full client tier — after the key is released a RESTORED"
            + " capture leaks 0.4948 blocks over 40 ticks against a 0.35 bar, of which 0.4928 is"
            + " per-tick creep, while a FRESHLY installed capture leaks 0.0894 under the identical"
            + " stimulus with walk travel equal to three decimals. That control is built into this"
            + " method and is the whole finding: the two differ by 5.5x, so it is a question about"
            + " what a login REINSTALLS, not about the test's patience. The player-visible form is"
            + " the old one: you slide along your own deck after a login into a space cell until"
            + " you sit down and stand up again. The cause established for it in July — a guard"
            + " that tore the capture off a falling body — was DELETED from production in"
            + " September, and the symptom outlived it, so the standing candidate is the space"
            + " subsystem's own login restore, named in the original report and never measured."
            + " RE-ENABLE when a restored capture and a fresh one leak the same; the acceptance is"
            + " this method green on a full tier, twice.")
    public void aCrewMemberWhoRelogsOnAnInvertedDeckIsNotDraggedAlongIt() throws Exception {
        int slotDim = seatThePilotAboardHisShip();

        String tag = standUpAndAwaitTheStandingRecord(events());
        requireArranged("standing up must keep him aboard as a STANDING record: " + tag,
                Reply.of(tag).bool("tagged") && "STANDING".equals(Reply.of(tag).text("posture")));
        // Read ONCE: the two-exec idiom this replaces diagnosed from a different sample than the one
        // that decided the line, and under load the two disagree.
        DeckCapture capUpright = DeckCapture.read(this::exec);
        requireArranged("he must be captured on the deck while the ship is still upright: "
                        + capUpright.raw(),
                capUpright.alreadyTracked);
        capUpright.requireAnchoredOn(
                ShipIdentity.awaitPhysicsIdOf(this::exec, events(), slotDim, arrangedShipId,
                        200),
                "the deck he stands on before the roll must be his own ship's");

        // Roll the ship to (near-)inverted UNDER him, by commanding the attitude his ship's computer
        // is to hold. Two things had to change before this verb could be used here at all, and both
        // are the same defect seen from different sides:
        //
        //  - The verb used to be POSITION-keyed, so on a shared world it rolled whatever craft was
        //    nearest. It names this ship now.
        //  - It used to lose to the pilot channel. The climb above left a held all-zero flight input
        //    in a JVM-wide static, and an all-zero input is still an input: the computer stayed in
        //    its PILOTED branch and re-commanded "hold the current attitude" every tick, cancelling
        //    the target before it turned anything (measured then: three runs, upY stayed exactly 1.0
        //    while the verb answered commanded=true). The climb publishes no such input any more, and
        //    a probe command now outranks the pilot channel besides.
        //
        // The old workaround - rolling through the input's ROLL channel - is what could not be kept:
        // it needed that same server-wide static, because a riderless seat clears a real per-ship
        // input every tick.
        double[] pose = awaitShipPose(slotDim);
        assertNotNull("the ship must be live to be rolled", pose);
        // 170 degrees about the ship's own forward axis: past vertical, so the deck is overhead.
        double half = Math.toRadians(170.0) / 2.0;
        // Addressed at the computer's own block. The ledger's durable ship id and the VS ship uuid the
        // `*-by-id` verbs resolve are DIFFERENT identities, and this scenario holds the first.
        String rolled = exec("artest vs point-at " + slotDim + " " + arrangedAfcPos
                + " " + Math.cos(half) + " " + Math.sin(half) + " 0.0 0.0");
        requireArranged("the roll must reach THIS ship's own flight computer: " + rolled,
                Reply.of(rolled).bool("commanded"));
        // Read BY NAME, both times. This used to be "the ship nearest (0,0,0) in the slot", with a
        // one-ship count asserted first as its premise — but a count of one is not evidence that the
        // one is THIS craft, and the case where it is not is exactly the case where this scenario's
        // ship failed to load and something else did.
        String rolledShipId = ShipIdentity.awaitPhysicsIdOf(this::exec, events(), slotDim, arrangedShipId,
                200);
        double upY = 1.0;
        for (int attempt = 0; attempt < 40 && upY > -0.9; attempt++) {
            bot().waitTicks(10);
            upY = shipUpY(jsonOf(exec("artest vs ship-info " + slotDim + " id " + rolledShipId)));
        }
        String info = jsonOf(exec("artest vs ship-info " + slotDim + " id " + rolledShipId));
        requireArranged("the ship must be (near-)inverted before the relog, or this leg is "
                + "silently the upright one again (upY=" + upY + "): " + info, upY < INVERTED_UP_Y);
        DeckCapture capInverted = DeckCapture.read(this::exec);
        requireArranged("he must still be captured on the INVERTED deck: " + capInverted.raw(),
                capInverted.alreadyTracked);
        // The INVERTED one — this ship, the one the roll above was addressed to. A capture that
        // moved to any other hull in the slot is by construction on an upright deck, which is the
        // arrangement this leg exists to leave behind.
        capInverted.requireAnchoredOn( rolledShipId,
                "the deck he is held on must be the ship this leg rolled");

        // Both marks before the disconnect - see the upright leg for why the client's own log needs
        // one and cannot start from zero.
        Events offlineLog = serverClockEvents();
        long logoutMark = offlineLog.mark();
        long clientMark = clientEvents().mark();
        bot().disconnect();
        String loggedOut = offlineLog.await(logoutMark, "player_logged_out",
                "a disconnect must reach the space subsystem's logout handler - everything below "
                        + "reads the record that handler leaves behind", LOGOUT_TICKS);
        requireArranged("the record he logs out with is the one his next login resolves from, and "
                        + "an inverted deck must not change that: " + loggedOut,
                // ONE logout record saying both: two field tests are satisfied by a tagged logout
                // beside a different record whose posture happens to be STANDING.
                Events.anyRecordHasAll(loggedOut, "tagged", "true", "posture", "STANDING"));
        String offline = exec("artest player position-of " + BOT);
        // absence is the answer: a player who is still connected answers a POSITION and no
        // `error` at all, so "no error" is the world this claim is measured against.
        requireArranged("the server must see him GONE after the disconnect: " + offline,
                "no such player".equals(Reply.of(offline).textOr("error", null))
                        || "no players connected".equals(Reply.of(offline).textOr("error", null)));

        Events restore = events();
        long restoreMark = restore.mark();
        bot().connect();
        bot().waitForWorld();
        String restored = restore.await(restoreMark, "login_restored",
                "a crew member who logged out standing on his INVERTED ship in a cell must be "
                        + "RESTORED by the login hook, exactly as an upright one is",
                RESTORE_LINK_BUDGET_TICKS);
        String joined = awaitClientEventWithField(clientMark, "client_dimension_changed",
                "via", "join",
                "the reconnected client must be given a world. Server verdict: " + restored,
                RESTORE_LINK_BUDGET_TICKS);
        int dim = clientDim();
        assertEquals("he relogged standing on his INVERTED ship in its cell: clientDim=" + dim
                        + " riding=" + bot().reportRidingEntity()
                        + "\n  login_restored: " + restored
                        + "\n  client dimension changes: " + joined,
                slotDim, dim);

        requireHeIsNotDraggedAlongHisDeck(dim);
    }

    /**
     * How far the ship's own UP points along world up, from a {@code ship-info} reply: {@code +1}
     * upright, {@code -1} fully inverted. The full expression, {@code 1 - 2(qx^2 + qz^2)} - the
     * single-axis shortcut this leg used to carry read {@code qx} alone and answered a confident
     * {@code 1.0} for a ship that had rolled about a different axis.
     */
    private double shipUpY(String shipInfoJson) {
        return ShipInfo.upYOrNaN(shipInfoJson);
    }
}
