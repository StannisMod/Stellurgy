package dev.stannismod.stellurgy.test.client;

import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.DeckCapture;
import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.ShipIdentity;
import dev.stannismod.stellurgy.test.ShipInfo;

import com.github.stannismod.forge.testing.client.RealClientHarness;
import com.github.stannismod.forge.testing.junit.ClassScope;
import com.github.stannismod.forge.testing.junit.ClassScopeRunner;
import com.github.stannismod.forge.testing.junit.ScopedTest;
import com.github.stannismod.forge.testing.junit.TestClassScope;
import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;
import com.google.gson.JsonObject;
import org.junit.After;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

import dev.stannismod.stellurgy.space.CellWorldMapper;
import dev.stannismod.stellurgy.space.GalacticCoord;
import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.PlayerPosition;
import dev.stannismod.stellurgy.test.ShipReadiness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;

/**
 * A crew member's login into a space cell while the server keeps RUNNING: a plain relog, aboard his
 * ship or orphaned from it. One server and one client for every scenario.
 *
 * <p>NEW-GROUP: the space subsystem's login restore across a relog WITHOUT a restart. Its arrangement —
 * a ship flown into a cell through the real entry on-ramp, settled in the ledger — lives in
 * {@link AbstractSpaceLoginRestoreClientTest}, which no shared client base can inherit; the planet-side
 * relog group ({@code VSCrewRelogPersistenceTest}) has none of it. The scenarios whose subject is a
 * SERVER RESTART stay on their own boots ({@code SpaceLoginRestoreSeatedPilotTest},
 * {@code SpaceLoginRestoreRefusalTest}): a restart is the one thing a shared pair cannot do.</p>
 *
 * <p><b>What one world costs, and how each scenario pays it back.</b> The entry flies every scenario's
 * ship to the launch body's own cell, and the re-seat matches a seat by proximity with no ship-id
 * filter, so a ship left in the cell is a second candidate. Each scenario therefore builds on its own
 * launch site, counts the ledger as a delta, and ends by dismounting and releasing the player, forgetting
 * its ship in the ledger and destroying it in the cell ({@link #releaseThisScenariosShipAndCrew}).</p>
 */
@RunWith(ClassScopeRunner.class)
@ClassScope(SpaceCellRelogGroupTest.RelogPair.class)
public class SpaceCellRelogGroupTest extends AbstractSpaceLoginRestoreClientTest
        implements ScopedTest<SpaceCellRelogGroupTest.RelogPair> {

    /**
     * This class run's server and client, booted by the first scenario that flies a ship and closed
     * when the class run ends. Owned by the runner's run of the class; nothing static holds it.
     */
    public static final class RelogPair extends TestClassScope {
        RealDedicatedServerHarness server;
        RealClientHarness client;
        /** How many launch sites this class run has handed out — each scenario builds on its own. */
        int launchSitesIssued;

        @Override
        protected void open(Class<?> testClass) {
            // Nothing yet: the first scenario boots the pair inside its arrangement.
        }

        @Override
        protected void close() throws Exception {
            Exception deferred = null;
            if (client != null) {
                try {
                    client.close();
                } catch (Exception e) {
                    deferred = e;
                }
                client = null;
            }
            if (server != null) {
                try {
                    server.close();
                } catch (Exception e) {
                    if (deferred == null) deferred = e;
                    else deferred.addSuppressed(e);
                }
                server = null;
            }
            if (deferred != null) {
                throw deferred;
            }
        }
    }

    /** This class run's pair; handed over by the runner before any rule or {@code @Before}. */
    private RelogPair pair;

    /** The slot dimension this scenario's ship settled in, once its arrangement has run. */
    private int scenarioSlotDim = Integer.MIN_VALUE;

    @Override
    public void attachScope(RelogPair scope) {
        this.pair = scope;
    }

    @Override
    protected void bringUpTheServer() throws Exception {
        if (pair.server == null) {
            super.bringUpTheServer();
            pair.server = serverHarness;
        } else {
            serverHarness = pair.server;
        }
    }

    @Override
    protected void startClient() throws Exception {
        if (pair.client == null) {
            super.startClient();
            pair.client = clientHarness;
        } else {
            clientHarness = pair.client;
        }
    }

    /** The pair is the class run's, released by {@link RelogPair#close}; a scenario only lets go of it. */
    @Override
    protected void closeBoth() {
        clientHarness = null;
        serverHarness = null;
    }

    /** Each scenario on its own launch site, 300 blocks apart along X, so no two builds overlap. */
    @Override
    protected FixtureSite launchSite() {
        return FixtureSite.openAir(LAUNCH_DIM, SRC_X + 300 * pair.launchSitesIssued++, SRC_Z);
    }

    @Override
    protected int seatThePilotAboardHisShip() throws Exception {
        scenarioSlotDim = super.seatThePilotAboardHisShip();
        return scenarioSlotDim;
    }

    /**
     * Give the next scenario a world with no ship of this one's in the cell and nothing binding the
     * player: off any mount, released by production's own service, back in the overworld; this
     * scenario's ship forgotten by the ledger and destroyed in its slot. Runs before the base's
     * {@code @After}, while this instance still holds the pair.
     */
    @After
    public void releaseThisScenariosShipAndCrew() throws Exception {
        if (serverHarness == null) {
            return; // the arrangement never brought the pair up, so there is nothing of this scenario's
        }
        exec("stellurgytest player dismount");
        exec("stellurgytest player release");
        if (arrangedShipId != null) {
            exec("stellurgytest space ledger-forget " + arrangedShipId);
        }
        if (scenarioSlotDim != Integer.MIN_VALUE) {
            ShipReadiness.clearCraftFrom(this::exec, scenarioSlotDim);
        }
        exec("stellurgytest tp " + OVERWORLD_DIM);
    }

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
     * budget is spent on {@link dev.stannismod.stellurgy.test.GameTicks#server()}.
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
        String tag = standUpAndAwaitTheStandingRecord(serverEvents());
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
                ShipIdentity.awaitPhysicsIdOf(this::exec, serverEvents(), slotDim, arrangedShipId,
                        200),
                "the capture the relog must restore is the one on THIS scenario's own deck");

        // A REAL logout that leaves the world running. Both marks BEFORE the disconnect: the client
        // JVM is REUSED across a plain relog, so its log still holds this session's records and zero
        // would be the whole session rather than the relog.
        Events offlineLog = connectionEvents();
        long logoutMark = offlineLog.mark();
        long clientMark = clientEvents().mark();
        bot().disconnect();
        // Waited for as the LINK it is - the space subsystem's own logout handler running. The record it leaves
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
        String offline = exec("stellurgytest player position-of " + BOT);
        // absence is the answer: a player who is still connected answers a POSITION and no
        // `error` at all, so "no error" is the world this claim is measured against.
        requireArranged("the server must see him GONE after the disconnect, or nothing below "
                        + "is a relog: " + offline,
                "no such player".equals(Reply.of(offline).textOr("error", null))
                        || "no players connected".equals(Reply.of(offline).textOr("error", null)));

        // Nobody is left near the ship to hold its chunks while he is away.

        Events restore = serverEvents();
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

        String tag = standUpAndAwaitTheStandingRecord(serverEvents());
        requireArranged("standing up must keep him aboard as a STANDING record: " + tag,
                Reply.of(tag).bool("tagged") && "STANDING".equals(Reply.of(tag).text("posture")));
        // Read ONCE: the two-exec idiom this replaces diagnosed from a different sample than the one
        // that decided the line, and under load the two disagree.
        DeckCapture capUpright = DeckCapture.read(this::exec);
        requireArranged("he must be captured on the deck while the ship is still upright: "
                        + capUpright.raw(),
                capUpright.alreadyTracked);
        capUpright.requireAnchoredOn(
                ShipIdentity.awaitPhysicsIdOf(this::exec, serverEvents(), slotDim, arrangedShipId,
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
        String rolled = exec("stellurgytest vs point-at " + slotDim + " " + arrangedAfcPos
                + " " + Math.cos(half) + " " + Math.sin(half) + " 0.0 0.0");
        requireArranged("the roll must reach THIS ship's own flight computer: " + rolled,
                Reply.of(rolled).bool("commanded"));
        // Read BY NAME, both times. This used to be "the ship nearest (0,0,0) in the slot", with a
        // one-ship count asserted first as its premise — but a count of one is not evidence that the
        // one is THIS craft, and the case where it is not is exactly the case where this scenario's
        // ship failed to load and something else did.
        String rolledShipId = ShipIdentity.awaitPhysicsIdOf(this::exec, serverEvents(), slotDim, arrangedShipId,
                200);
        double upY = 1.0;
        for (int attempt = 0; attempt < 40 && upY > -0.9; attempt++) {
            advanceServerAndClient(10);
            upY = shipUpY(jsonOf(exec("stellurgytest vs ship-info " + slotDim + " id " + rolledShipId)));
        }
        String info = jsonOf(exec("stellurgytest vs ship-info " + slotDim + " id " + rolledShipId));
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
        Events offlineLog = connectionEvents();
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
        String offline = exec("stellurgytest player position-of " + BOT);
        // absence is the answer: a player who is still connected answers a POSITION and no
        // `error` at all, so "no error" is the world this claim is measured against.
        requireArranged("the server must see him GONE after the disconnect: " + offline,
                "no such player".equals(Reply.of(offline).textOr("error", null))
                        || "no players connected".equals(Reply.of(offline).textOr("error", null)));

        Events restore = serverEvents();
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
     * When the server genuinely has no record of a returning pilot's ship, the restore ORPHANS him on
     * that ground and places him somewhere survivable — not silently stood up at his spawn point.
     *
     * <p>The subject is the RESTORE'S VERDICT — {@code login_restored} carrying {@code SHIP_UNKNOWN},
     * production's own enum value at the one place the decision is made — and where it actually put
     * him. One of four orphan causes, asked by name: {@code NO_TAG} and {@code CELL_UNAVAILABLE} land
     * the player in the same place for different reasons, and a test that could not tell them apart
     * would pass on a fixture that never wrote an aboard record.</p>
     *
     * <p><b>Why the ship is removed rather than the ledger damaged.</b> "The ledger has no such ship" is
     * one verdict reached from several directions; removal is the one that is both production behaviour
     * and arrangeable, so the arrangement asserts the ledger KNEW the ship first. <b>A relog is enough and
     * is deliberate</b> — the decision is made when a player's save file is read, which a rejoin does as
     * faithfully as a reboot, and it keeps the ship, the cell and the ledger in one server's lifetime.</p>
     */
    @Test
    public void aPilotWhoseShipTheServerNoLongerKnowsIsOrphanedWhenHeComesBack() throws Exception {
        // The restore's own name for "his aboard record names a ship the ledger does not have" —
        // mirrors `LoginRestore.Reason`.
        final String reasonShipUnknown = "SHIP_UNKNOWN";
        // A deadline for a discrete decision production makes once per login — not a settle.
        final int restoreVerdictBudgetTicks = 200;

        seatThePilotAboardHisShip();

        // The mark BEFORE the ship is taken away: a restore decided at any earlier point is outside the
        // window and cannot satisfy the wait.
        Events events = serverEvents();
        long mark = events.markInstrumented();

        String forgot = exec("stellurgytest space ledger-forget " + arrangedShipId);
        assertTrue("arrangement: the ledger must have KNOWN this ship before being told to forget it - "
                + "otherwise the login below is about a ship that never existed: " + forgot,
                readBool(forgot, "wasKnown"));
        assertFalse("arrangement: and it must not know it afterwards: " + forgot, readBool(forgot, "found"));

        bot().reconnect();
        bot().waitForWorld();

        String restored = events.awaitField(mark, "login_restored", "reason", reasonShipUnknown,
                "a pilot whose ship the server cannot find must be ORPHANED by the restore, on that"
                        + " ground and not on some other, rather than silently appearing at his spawn"
                        + " point", restoreVerdictBudgetTicks);
        // absence is the answer: the claim is that NO record says he is aboard, so a window holding no
        // such record is the pass this asserts.
        assertFalse("...and the restore must not count him as aboard anything: " + restored,
                Events.anyRecordHas(restored, "aboard", "true"));

        // Placement is read from the SERVER: on this path the client can still be rendering the slot
        // world it was in while the server has him in the overworld — a filed, pre-existing split of
        // the orphan path that this test must not read as the placement being broken.
        // `playerDimField` is quoted beside `playerDim` because they are maintained separately.
        PlayerPosition serverPos = PlayerPosition.of(this::exec, BOT);
        JsonObject riding = bot().reportRidingEntity();
        assertEquals("the server must actually have placed him out of the cell, or the message is "
                        + "describing something that did not happen: " + serverPos.raw()
                        + " clientRiding=" + riding + " clientRenderedDim=" + clientDim(),
                OVERWORLD_DIM, serverPos.dim);
        assertEquals("and his persisted dimension field must agree, or the next login starts from a "
                + "world he is not in: " + serverPos.raw(), OVERWORLD_DIM, serverPos.dimField);
        assertFalse("and the client agrees he is riding nothing: " + riding + " server=" + serverPos,
                riding.get("riding").getAsBoolean());
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
