package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;
import com.google.gson.JsonObject;

import org.junit.Test;

import zmaster587.advancedRocketry.space.CellWorldMapper;
import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.test.Events;
import zmaster587.advancedRocketry.test.ShipIdentity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static zmaster587.advancedRocketry.test.ArrangementFailure.requireArranged;

/**
 * Login restore for a pilot who was ABOARD when he left: seated in a cell, seated on the ground
 * before the climb, or standing on his own deck. Each leg logs him out, restarts the server under
 * him, and requires him back on his ship.
 *
 * <p>See {@link AbstractSpaceLoginRestoreClientTest} for why this contract can only be tested on a
 * real client, and for the fixture the three legs share.</p>
 *
 * <p><b>Why this is three classes rather than one.</b> The original
 * {@code SpaceLoginRestoreClientE2ETest} held all seven legs, and every one of them boots a server
 * and a client and then restarts the server — 1032.8 s measured 2026-08-07 at 8 forks, in ONE gradle
 * fork, which made this single class the wall-clock FLOOR of the whole non-VS client tier while
 * seven forks idled. Sharing a harness is not available here: the restart IS the subject. So the
 * legs were split across three classes instead, which is the same optimisation from the other end —
 * the tier's floor is its longest UNIT, and a unit nobody can shorten can still be divided.</p>
 */
public class SpaceLoginRestoreSeatedPilotE2ETest extends AbstractSpaceLoginRestoreClientTest {

    /**
     * The pilot logs out seated on his ship and the server is restarted under him. He must come back
     * in his ship's cell and back in his seat.
     */
    @Test
    public void aPilotWhoLoggedOutSeatedOnHisShipComesBackAboardItAfterAServerRestart() throws Exception {
        requireHeComesBackAboardHisShip(seatThePilotAboardHisShip());
    }

    /**
     * The same contract, reached the way a player actually reaches it: the pilot takes his seat ON THE
     * GROUND and is still in it when his ship crosses into the cell. He never sits down inside a space
     * cell at all.
     *
     * <p><b>Why this is a separate leg and not a variant of the arrangement above.</b> The durable
     * aboard record is what the restore runs on, and it was for a long time written only by the mount
     * TRANSITION - so it existed for a pilot who sat down in a cell and did not exist for a pilot who
     * sat down on a planet and flew up. Both pilots are equally aboard, and the leg above cannot tell
     * them apart because its arrangement seats him after the arrival. This one is the route that was
     * broken in real play: fly to orbit, log out, come back standing at the build site you left hours
     * ago, with no message and your ship still in orbit without you.</p>
     *
     * <p>The arrangement is the witness for the mechanism as well as the setup for the restart: it
     * requires the record to be ABSENT while he sits on the planet and PRESENT once he has arrived,
     * so a green here cannot come from a record that was already there before the flight.</p>
     */
    @Test
    public void aPilotWhoBoardedOnThePlanetAndFlewUpComesBackAboardAfterAServerRestart()
            throws Exception {
        requireHeComesBackAboardHisShip(seatThePilotBeforeHeLeavesTheGround());
    }

    /**
     * A crew member who STANDS UP on his own ship in orbit is still aboard it, and must come back
     * aboard - on his feet, on his own deck, in his ship's cell - not seated, and not at an ordinary
     * spawn.
     *
     * <p>Two contracts meet here and must not be confused. That he comes back NOT SEATED is one: a
     * player who left his post must not be dragged back into it by the next login. That he comes
     * back IN HIS SHIP'S CELL is the other, and it is the one this leg used to pin INVERTED -
     * standing up dropped the durable record entirely, the restore then had no evidence he had ever
     * been aboard, and he woke at his overworld build site with his ship still in orbit without him.
     * This leg is what says that is fixed.</p>
     *
     * <p><b>What replaced this leg's second job.</b> While it asserted "overworld" it also served as
     * the falsifiability witness for the positive legs - the proof that these oracles can answer
     * "not aboard" at all. It cannot do that any more, so the witness now comes from a player who
     * was never aboard in the first place:
     * {@link #aPlayerWhoWasNeverAboardIsNotRestoredOntoTheShip}.</p>
     *
     * <p>Both of the ways this leg could be green for the wrong reason are closed before it looks at
     * the client: the production subsystem must be up on the second boot, and the ship must still be
     * in the ledger.</p>
     */
    @Test
    public void aPilotWhoStoodUpBeforeLoggingOutComesBackAboardOnHisFeet() throws Exception {
        int slotDim = seatThePilotAboardHisShip();

        // Stand up through the production path. The record must SURVIVE it and change SHAPE: he is
        // no longer in a seat, he is on the deck - which is a way of BEING aboard, not of leaving.
        // Two LINKS, not a value that settles: he leaves the mount, and the reconciler's next pass
        // writes the new shape. The record is refreshed on a one-second cadence, which is why a
        // single sample says nothing - but the answer to that is to wait for the WRITE, not to
        // sample the tag until it agrees.
        String tag = standUpAndAwaitTheStandingRecord(events());
        assertTrue("standing up on his own deck must keep him aboard, as a STANDING record - a "
                + "record dropped here is exactly what used to send him to an ordinary spawn: " + tag,
                tag.contains("\"tagged\":true") && tag.contains("\"posture\":\"STANDING\""));
        assertTrue("and it must still name the ship he is standing on: " + tag
                + " (entered ship " + arrangedShipId + ")", tag.contains(arrangedShipId));
        // He must really be resolved on the DECK, in the ship's own frame, before the restart: that
        // is what produces the record asserted above, and a hull-stand catch is not it.
        String capBefore = exec("artest vs deck-capture");
        requireArranged("he must be captured ABOARD the deck after standing up, or the record "
                + "above describes something other than a crew member on his feet: " + capBefore,
                capBefore.contains("\"alreadyTracked\":true")
                        && !capBefore.contains("\"hullStand\":true"));
        // The record asserted above names `arrangedShipId`; this line makes the capture name it too.
        // Without it the two claims are about possibly different craft and the sentence "the record
        // describes this crew member on this deck" is not established by either of them.
        ShipIdentity.assertCaptureAnchoredOn(capBefore,
                ShipIdentity.awaitPhysicsIdOf(this::exec, slotDim, arrangedShipId,
                        40, () -> bot().waitTicks(5)),
                "the deck he is captured on must be the ship the STANDING record names");

        String serverBeforeLogout = exec("artest player position-of " + BOT);
        assertEquals("the SERVER must still have him in his ship's slot dimension when it writes him "
                + "to disk: " + serverBeforeLogout, slotDim, readInt(serverBeforeLogout, "playerDim"));

        closeBoth();
        keepBootLog("boot1-standing");

        serverHarness = RealDedicatedServerHarness.startWith(root, false);

        // Both discriminators, BEFORE the client connects. Without them a client reading says
        // nothing about the record: a second boot whose subsystem stood down, or whose ledger did
        // not survive the shutdown save, would leave him at an ordinary spawn for its own reasons.
        String statusAfter = exec("artest space subsystem-status");
        assertTrue("the production subsystem must come up again on boot 2, or nothing below is "
                + "exercising it: " + statusAfter, statusAfter.contains("\"registered\":true"));
        String ledger = exec("artest space ledger-get " + arrangedShipId);
        assertTrue("his ship must still be ledgered - there has to be a ship to restore him ONTO: "
                + ledger, ledger.contains("\"found\":true"));

        exec("artest vs permaload true");

        // The mark before the client exists, because the restore fires ON his connection: taken
        // afterwards it could not tell "the hook never ran" from "the hook ran before I looked".
        Events restore = events();
        long restoreMark = restore.mark();
        startClient();
        bot().waitForWorld();

        // The restore's own verdict, on the server that took it. A crew member on his FEET queues
        // no seat - the deck hold places him instead - so this is one link and not the seated
        // legs' chain: `login_restored` carries the reason it decided on (ABOARD_SETTLED here, or
        // NO_TAG / SHIP_UNKNOWN / CELL_UNAVAILABLE when it did not) and the dimension it chose.
        // The poll it replaces waited for a client dimension and could not say which of those four
        // it had been handed.
        String restored = restore.await(restoreMark, "login_restored",
                "a crew member who logged out standing on his own ship in a cell must be RESTORED "
                        + "by the login hook - without that verdict he is an ordinary login and "
                        + "wakes at his overworld spawn",
                RESTORE_LINK_BUDGET_TICKS);
        // And the client's own side of it. Zero is the mark: this client JVM is brand new, so its
        // log starts empty and the join is recorded inside startClient, before a mark could exist.
        String joined = awaitClientEvent(CLIENT_SESSION_START, "client_dimension_changed", null,
                "the restored client must end up IN a world. Server verdict: " + restored,
                RESTORE_LINK_BUDGET_TICKS);

        int dim = clientDim();
        JsonObject riding = bot().reportRidingEntity();
        JsonObject state = bot().reportState();
        // The client's own seed verdicts ride along in every message below. The deck hold sends the
        // recorded deck point as a restore seed and the client decides what to do with it - APPLY,
        // KEEP_PREEXISTING, ALREADY_SEEDED, EXPIRE or WAIT - and which of the five it chose is the
        // difference between "he was put on his deck" and "the hold expired and vanilla had him".
        String seeds = String.valueOf(bot().eventsSince(CLIENT_SESSION_START, "deck_seed_decided"));
        String observed = "clientDim=" + dim + " riding=" + riding + " state=" + state
                + "\n  login_restored: " + restored
                + "\n  client dimension changes: " + joined
                + "\n  client seed decisions: " + seeds;

        assertTrue("the client must have a world at all before anything can be read from it: "
                + observed, dim != NO_CLIENT_WORLD);
        assertFalse("a pilot who stood up must NOT be re-seated on the ship he left: " + observed,
                riding.get("riding").getAsBoolean());
        assertNotEquals("he stood up ON HIS OWN SHIP in orbit, which is a way of BEING aboard - so he "
                + "must not come back at an ordinary spawn. Note dim 0 is an AMBIGUOUS failure: "
                + "vanilla also forces it when the target world did not load - the login_restored "
                + "record above is what separates the two, and it names both the reason the hook "
                + "decided on and the dimension it chose. " + observed, OVERWORLD_DIM, dim);

        // And he must be back ON his ship rather than merely in its cell: the deck hold puts the body
        // on the stored deck point, so his client-rendered position has to be at the ship.
        double[] shipPose = awaitShipPose(dim);
        assertNotNull("his ship must be live in the dimension he came back to: " + observed, shipPose);
        double clientX = state.get("playerX").getAsDouble();
        double clientY = state.get("playerY").getAsDouble();
        double clientZ = state.get("playerZ").getAsDouble();
        for (int attempt = 0; attempt < 40 && Math.abs(clientY - shipPose[1]) > POSE_EPSILON;
                attempt++) {
            bot().waitTicks(10);
            state = bot().reportState();
            if (!state.get("worldReady").getAsBoolean()) {
                continue;
            }
            clientX = state.get("playerX").getAsDouble();
            clientY = state.get("playerY").getAsDouble();
            clientZ = state.get("playerZ").getAsDouble();
            double[] livePose = awaitShipPose(dim);
            if (livePose != null) {
                shipPose = livePose;
            }
        }
        // Re-read at the END of the settle window, not at the start of it: the deck hold sends its
        // restore seed once the ship is up, which can be several seconds after the client joined,
        // so a verdict list taken on the join tick is routinely empty and says nothing.
        observed = "clientDim=" + dim + " state=" + state + " shipPose=[" + shipPose[0] + ","
                + shipPose[1] + "," + shipPose[2] + "]"
                + "\n  login_restored: " + restored
                + "\n  client seed decisions: "
                + bot().eventsSince(CLIENT_SESSION_START, "deck_seed_decided");
        assertEquals("he must come back at his ship on X: " + observed,
                shipPose[0], clientX, POSE_EPSILON);
        assertEquals("he must come back at his ship on Y: " + observed,
                shipPose[1], clientY, POSE_EPSILON);
        assertEquals("he must come back at his ship on Z: " + observed,
                shipPose[2], clientZ, POSE_EPSILON);

        // And that position must realize a coordinate inside his ship's own ledgered cell - the check
        // "not the overworld" cannot make, since an ordinary block height in the right slot world
        // would still pass it.
        GalacticCoord cell = GalacticCoord.fromCellKey(arrangedCellKey);
        assertNotNull("the ledger reported an unreadable cell key: " + arrangedCellKey, cell);
        GalacticCoord realized = CellWorldMapper.coordOfPose(cell, clientX, clientY, clientZ);
        assertTrue("the client's position must realize a coordinate in his ship's own cell "
                + arrangedCellKey + ", but it maps to " + realized.cellKey() + ": " + observed,
                realized.sameCell(cell));

        // Coming back ON the ship is only half of it: he also has to STAY where he was put. Every
        // position pin above is written at POSE_EPSILON, a tolerance sized to separate "at his ship"
        // from "at a spawn" - three orders of magnitude coarser than a drift a player feels under his
        // own feet, which is why this class was green while play reported exactly that.
        requireHeIsNotDraggedAlongHisDeck(dim);
    }

}
