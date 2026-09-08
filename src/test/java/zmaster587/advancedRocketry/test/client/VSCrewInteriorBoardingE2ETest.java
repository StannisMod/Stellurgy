package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.TestTimeouts;

import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import zmaster587.advancedRocketry.test.Events;
import zmaster587.advancedRocketry.test.ShipIdentity;

import static org.junit.Assert.assertTrue;

/**
 * Interior boarding of a non-upright ship - the enclosed-interior capture, and the flying-aboard
 * rule that lets a creative flyer be captured without ever touching the deck.
 *
 * <p>The pinned contract: a body inside a ship's hull with a deck below it IN THE SHIP FRAME is
 * the DECK's to claim - stopping creative flight there seats it back on the deck (ship-frame
 * gravity carries it, at any attitude) with the ship camera engaged. Before the interior gate
 * existed, WORLD gravity owned that body instead: over this fixture's cockpit opening (facing
 * world-down at 170 degrees) it fell clean out of the ship to the terrain; in an enclosed
 * cavity it was pinned to the interior world-floor by the outer-hull fallback with a world
 * camera - the reported "captured, but the camera never flips" desync. Both flavors of that
 * gap are closed by the same gate this test pins.</p>
 *
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class VSCrewInteriorBoardingE2ETest extends AbstractSharedVsClientE2ETest {

    @Override
    protected String subsystem() {
        return "vs-crew-boarding";
    }

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern POS_X = Pattern.compile("\"posX\":(-?[0-9.E\\-]+)");
    private static final Pattern POS_Y = Pattern.compile("\"posY\":(-?[0-9.E\\-]+)");
    private static final Pattern POS_Z = Pattern.compile("\"posZ\":(-?[0-9.E\\-]+)");
    private static final Pattern DUMMY_ID = Pattern.compile("\"dummyId\":(\\d+)");
    private static final Pattern SEAT_X = Pattern.compile("\"seatX\":(-?\\d+)");
    private static final Pattern SEAT_Y = Pattern.compile("\"seatY\":(-?\\d+)");
    private static final Pattern SEAT_Z = Pattern.compile("\"seatZ\":(-?\\d+)");

    private static final String VARIANT = "with-pilot-deck";

    /**
     * THIS scenario's ship, by identity — captured by {@code buildShip} at the one moment its base
     * provably holds no other, and the address every later question and command uses. A radius bound
     * is a mitigation, not an identity: these scenarios roll, hover and drop the ship on purpose, and
     * a shared client always has a neighbour in candidacy.
     */
    private String scenarioShipId;
    private static final String SHIP_CAMERA = "zmaster587.advancedRocketry.client.ShipFrameCamera";
    private static final String SHIP_FRAME_TRAVEL =
            "zmaster587.advancedRocketry.integration.vs.ShipFrameTravel";

    /**
     * How long one link of a deck-capture chain may take. A DEADLINE for a discrete event, not a
     * guess at how long a value takes to settle: production either releases, claims and captures a
     * body or it does not, and 240 ticks is generous against an eight-fork load while still failing
     * a scenario that never gets there rather than waiting out a budget.
     */
    private static final int DECK_LINK_BUDGET_TICKS = 240;

    // Every contract this class pins is a CLIENT fact — the resolver that releases and reclaims a
    // body inside a hull is the client's, and for an {@code EntityPlayerMP} the server rebases the
    // position instead of releasing at all, so a server probe can answer "still tracked" straight
    // through a release the client really performed. So every wait below reads the base's
    // {@link #clientEvents()}, not {@link #events()}.

    /**
     * {@link Events#await} for a link this scenario ARRANGES rather than pins — raised through
     * {@code scenario().arrangementFailed} so the JUnit XML separates "the setup this test needed
     * never happened" from "the contract under test broke".
     */
    private String requireLink(Events events, long mark, String type, String what) throws Exception {
        try {
            return events.await(mark, type, what, DECK_LINK_BUDGET_TICKS);
        } catch (AssertionError notArranged) {
            scenario().arrangementFailed(notArranged.getMessage());
            return ""; // unreachable: arrangementFailed always throws
        }
    }

    /**
     * The MODE production last committed for a captured body, from its own record — {@code "aboard"}
     * (deck semantics: deck gravity, deck camera) or {@code "hull"} (world semantics on the outer
     * hull), or {@code null} when it committed none since {@code mark}.
     *
     * <p>Every mode transition goes through one private method and this is the record taken there, so
     * the distinction all three scenarios assert is read from production's own commit rather than
     * inferred from a probe's dump of the state map. Its one blind spot is named in the mixin: a
     * re-capture taken on the hull-stand travel path restores {@code hullStand} after the record, and
     * reads {@code "aboard"} for a body that finishes the tick in hull-stand — which is why the
     * server's own end-of-window verdict is still read beside it.</p>
     */
    private static String lastCommittedMode(String modeReply) {
        return Events.lastField(modeReply, "mode");
    }

    /**
     * The {@code deck_mode_committed} records since {@code mark}, once the last of them says
     * {@code wanted} — or once the budget is spent, so the caller's own assertion produces the
     * failure and prints the whole sequence.
     *
     * <p>A bounded POLL and not an {@code await}, on purpose: the mode is a STATE production can
     * reach in two commits — a body may be taken in hull-stand and PROMOTED to aboard on a later
     * tick — so what is waited for is the settled answer rather than one edge, exactly as the
     * probe-sampling loop this replaces did. What changed is the channel: the answer now comes from
     * production's own commit record, so a failure prints every mode it committed and in which
     * order instead of the last sample of a state dump.</p>
     */
    private String awaitCommittedMode(Events events, long mark, String wanted) throws Exception {
        String reply = events.since(mark, "deck_mode_committed");
        for (int waited = 0; waited < DECK_LINK_BUDGET_TICKS
                && !wanted.equals(lastCommittedMode(reply)); waited += 4) {
            bot().waitTicks(4);
            reply = events.since(mark, "deck_mode_committed");
        }
        return reply;
    }

    @Test
    public void aBodyReleasedInsideAnInvertedShipIsSeatedBackOnTheDeck()
            throws Exception {
        final int bx = 6620, by = 64, bz = 6620;

        // Seat the bot, invert the ship under him, dismount INSIDE: the dismount seed captures
        // him ABOARD in the cockpit of the inverted ship.
        buildAndBoardShip(bx, by, bz);
        bot().waitTicks(20);
        double h = Math.toRadians(170.0) / 2.0;
        assertTrue("attitude hold must accept the inversion",
                exec("artest vs point-by-id 0 " + scenarioShipId + " "
                        + Math.cos(h) + " " + Math.sin(h) + " 0.0 0.0").contains("\"commanded\":true"));
        bot().waitTicks(200);

        // The arrangement as a CHAIN, not a budget: the probe un-seats him and the deck takes him.
        // `dismount` is recorded at the un-seating and `deck_captured` at the capture production
        // installs, so a failure names WHICH link never happened - where the 30x4 poll it replaces
        // could only print the last sample of a server verdict.
        Events events = events();
        long dismountMark = events.markInstrumented();
        exec("artest player dismount");
        requireChain(events, dismountMark, "the dismounted pilot must be taken by the deck inside the"
                + " inverted ship", "dismount", "deck_captured");
        // ...and ABOARD, not stood on the outer hull. Production commits the mode itself at every
        // transition, so it is read from that commit instead of inferred from the probe's dump.
        String modesBefore = awaitCommittedMode(events, dismountMark, "aboard");
        Events.assertInstrumentRan(modesBefore, "deck_mode_events",
                "the deck committed a capture MODE for the dismounted pilot");
        assertTrue("the dismounted pilot must be captured ABOARD inside the inverted ship, not held"
                + " with world semantics on the outer hull (last committed mode="
                + lastCommittedMode(modesBefore) + "): " + modesBefore + " | server verdict "
                + exec("artest vs deck-capture"), "aboard".equals(lastCommittedMode(modesBefore)));
        double preY = bot().reportState().get("playerY").getAsDouble();

        // Subspace census at the QUIET STANDING phase (the ledgered obst=0 already shows here):
        // server = control (must be rich), client statics = the side under suspicion. Server rich +
        // client empty ==> the client never received the ship's subspace chunks.
        System.out.println("[interior] census standing: server=" + exec("artest vs subspace-census")
                + " client={ticks=" + censusStatic("censusTicks")
                + " ship=" + censusStatic("censusShipId")
                + " tracked=" + censusStatic("censusTracked")
                + " subPos=" + censusStatic("censusSubPos")
                + " chunkLoaded=" + censusStatic("censusChunkLoaded")
                + " nonAir=" + censusStatic("censusNonAir")
                + " boxes=" + censusStatic("censusCollisionBoxes")
                + " region=" + censusStatic("censusRegion")
                + " regionNonAir=" + censusStatic("censusRegionNonAir") + "}"
                + " seed={attempts=" + censusStatic("seedAttempts")
                + " oks=" + censusStatic("seedOks")
                + " refusals=" + censusStatic("seedRefusals")
                + " lastRefusal=" + censusStatic("lastSeedRefusal")
                + " notLoaded=" + censusStatic("seedNotLoaded") + "}");

        // Release the capture DETERMINISTICALLY, with the body still inside the hull: a small
        // world teleport reads as an external move, the guard drops the capture, and the body is
        // exactly the interior-boarding subject - inside the ship's region, un-captured, under
        // WORLD gravity.
        // (A creative-flight release is the report's flavor, but the flying body drifts
        // unpredictably and can leave the hull before flight ends - flight interaction belongs
        // to the flying-aboard contract's own test.)
        //
        // Direction is MEASURED, not assumed: at 170 deg world-UP maps to ship-DOWN (deeper
        // aboard, toward the deck) plus a subspace-Z step INTO the region. World-DOWN was the
        // opposite - the seat dismount can stand the body on the region's boundary block (the
        // cockpit doorway), 0.2 blocks from the face, and a world-down nudge carries a ~0.1
        // subspace-Z component that pushes it OUT through that face; an outside body is not
        // the interior gate's subject at all (it rightly refuses a body outside the region) and the
        // test then measured its own ejection, not the contract.
        Events clientEvents = clientEvents();
        long releaseMark = clientEvents.mark();
        exec("tp @a ~ ~0.6 ~");

        // Subject validity (fixture geometry by measurement): the released body must still BE
        // inside the ship's block region, or the run is measuring a doorway ejection.
        bot().waitTicks(2);
        String subAfterRelease = censusStatic("censusSubPos");
        String regionStr = censusStatic("censusRegion");
        assertTrue("the released body must remain INSIDE the ship's block region (sub="
                + subAfterRelease + " region=" + regionStr + ")",
                subInRegion(subAfterRelease, regionStr));

        // ARRANGEMENT, and it is a CLIENT fact: the guard must actually drop the client's capture.
        // For an EntityPlayerMP the server REBASES the position instead of releasing, so the server
        // probe can report "still tracked" straight through a release the client really performed -
        // which is the hole the 30-sample server majority this replaces used to fall into.
        String releases = requireLink(clientEvents, releaseMark, "deck_released",
                "the world teleport must read as an external move and drop the CLIENT capture, or"
                        + " nothing below is about a re-claim");
        String releaseReason = Events.firstField(releases, "reason");
        if (releaseReason == null || !releaseReason.startsWith("externalMove")) {
            scenario().arrangementFailed("the release must be the EXTERNAL-MOVE guard - any other"
                    + " gate (leftShipRegion, steppedOntoTerrain, an excluded state) means the body"
                    + " left the subject's premise rather than being handed back to world gravity"
                    + " inside the hull. reason=" + releaseReason + " :: " + releases);
        }

        // The subject: the deck reclaims the released body. The mark is taken AFTER the release on
        // purpose - `deck_captured` is written on EVERY resolved tick, so a mark from before it is
        // satisfied by the captures that preceded it and would prove nothing. From here the first
        // record is the RE-capture; and because an ongoing capture keeps writing one every tick,
        // this cannot miss a re-claim that landed between the two reads either.
        long reclaimMark = clientEvents.mark();
        // Carrying THIS ship: the record names the hull that re-took the body, and "the deck reclaimed
        // him" is a claim about the ship he was released inside — a type-only wait cannot tell it
        // from another hull picking him up on his way down.
        String reclaimed = clientEvents.awaitCarrying(reclaimMark, "deck_captured",
                "\"ship\":\"" + scenarioShipId + "\"",
                "THIS ship's deck must reclaim the body released inside the inverted ship, instead of"
                        + " leaving it to world gravity through the world-down cockpit opening",
                DECK_LINK_BUDGET_TICKS);

        // Sample the settle: where does the body come to rest, and what camera does the client own?
        StringBuilder trace = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            bot().waitTicks(3);
            trace.append(String.format(java.util.Locale.ROOT,
                    "[t%d y=%.2f obst=%s onDeck=%s cSub=%s cLoaded=%s cAir=%s cBox=%s cRegAir=%s] ",
                    i * 3, bot().reportState().get("playerY").getAsDouble(),
                    bot().readStaticField(SHIP_FRAME_TRAVEL, "lastObstacleCount").get("value")
                            .getAsString(),
                    bot().readStaticField(SHIP_FRAME_TRAVEL, "lastOnDeck").get("value")
                            .getAsString(),
                    censusStatic("censusSubPos"),
                    censusStatic("censusChunkLoaded"),
                    censusStatic("censusNonAir"),
                    censusStatic("censusCollisionBoxes"),
                    censusStatic("censusRegionNonAir")));
        }
        // The mode is read from the RELEASE mark, not from the re-claim mark, and the difference is
        // the whole reason this read can answer at all. `deck_mode_committed` is an EDGE — it is
        // written at `logCapture`, which production calls only when a capture is INSTALLED or its
        // mode TRANSITIONS — while `deck_captured` is a per-tick commit. Production repairs an
        // external-move release inside the same tick that performs it (the travel commit re-captures
        // the body on the spot it moved to), so the one mode commit of this episode is already
        // written by the time the release record has been read and a fresh mark taken: a window that
        // opens at the re-claim contains the captures and never the commit, and the read came back
        // null on a body the trace shows resolved on the deck. The window from the release covers
        // the release AND the re-claim, and every commit in it is post-release by construction.
        String modesAfter = awaitCommittedMode(clientEvents, releaseMark, "aboard");
        Events.assertInstrumentRan(modesAfter, "deck_mode_events",
                "the reclaimed body was committed ABOARD rather than onto the outer hull");
        String releasesAfter = clientEvents.since(reclaimMark, "deck_released");
        boolean shipCam = Boolean.parseBoolean(
                bot().readStaticField(SHIP_CAMERA, "shipCamActive").get("value").getAsString());
        double settledY = bot().reportState().get("playerY").getAsDouble();
        String capEnd = exec("artest vs deck-capture");
        System.out.println("[interior] shipCamActive=" + shipCam + " preY=" + preY + " settledY="
                + settledY + " reclaim=" + reclaimed + " modes=" + modesAfter
                + " releasesSinceReclaim=" + releasesAfter
                + " censusEnd(server)=" + exec("artest vs subspace-census")
                + " :: " + trace);

        // The interior-boarding contract: the deck reclaims the released body - it is carried
        // back by SHIP-frame gravity (never lost through the world-down cockpit opening to the
        // world below, never pinned by the outer-hull fallback), stays resolved ABOARD at its
        // deck spot, and the client's ship camera engages.
        assertTrue("a body released inside the ship must be re-seated ABOARD, not held with world"
                + " semantics on the outer hull (last committed mode="
                + lastCommittedMode(modesAfter) + ", releases since the re-claim: " + releasesAfter
                + "): " + trace, "aboard".equals(lastCommittedMode(modesAfter)));
        assertTrue("the body must stay WITH the inverted ship at its deck spot, not fall out "
                + "(preY=" + preY + " settledY=" + settledY + ", cap=" + capEnd + "): " + trace,
                Math.abs(settledY - preY) < 2.5 && capEnd.contains("\"alreadyTracked\":true"));
        // ...and by THIS ship. "He is held" and "he is held by the craft this scenario built"
        // are different claims, and on a world three scenarios share only the second one is the
        // contract. The id is in the reply already.
        ShipIdentity.assertCaptureAnchoredOn(capEnd, scenarioShipId,
                "the body must stay with the INVERTED ship it was released inside");
        assertTrue("the client camera must engage for the re-seated interior body "
                + "(shipCamActive=" + shipCam + ")", shipCam);
    }

    // ---- Enclosed cavity: the interior gate claims an UNSUPPORTED roofed body with a deck below -

    @Test
    public void aBodyLostMidCavityOfAnEnclosedInvertedShipIsReclaimedByTheDeck() throws Exception {
        final int bx = 6820, by = 64, bz = 6820;

        // The open-topped cockpit above cannot exercise interior boarding: since the enclosure
        // term, its re-seat path is supported first contact (the body is pressed against the
        // deck), and the interior gate never fires there. This test's subject is the gate's own
        // claim: a body UNSUPPORTED mid-cavity of an ENCLOSED cockpit - deck below AND roof
        // above in the ship frame - with world gravity pulling it AWAY from the deck (the ship
        // is inverted). Pre-gate, that body belonged to world gravity: it fell onto the roof -
        // the cavity's world-floor - and the outer-hull fallback pinned it there with a world
        // camera, the reported "captured, but the camera never flips" desync. The contract: the
        // deck reclaims it without standing support and carries it back AGAINST world gravity.
        buildAndBoardShip(bx, by, bz, "with-roofed-deck");
        bot().waitTicks(20);
        double h = Math.toRadians(170.0) / 2.0;
        assertTrue("attitude hold must accept the inversion",
                exec("artest vs point-by-id 0 " + scenarioShipId + " "
                        + Math.cos(h) + " " + Math.sin(h) + " 0.0 0.0").contains("\"commanded\":true"));
        bot().waitTicks(200);

        // Same arrangement chain as the open-cockpit scenario: `dismount` then `deck_captured`, and
        // the MODE off production's own commit.
        Events events = events();
        long dismountMark = events.markInstrumented();
        exec("artest player dismount");
        requireChain(events, dismountMark, "the dismounted pilot must be taken by the deck inside the"
                + " inverted roofed ship", "dismount", "deck_captured");
        String modesBefore = awaitCommittedMode(events, dismountMark, "aboard");
        Events.assertInstrumentRan(modesBefore, "deck_mode_events",
                "the deck committed a capture MODE for the dismounted pilot");
        assertTrue("the dismounted pilot must be captured ABOARD inside the inverted ship (last"
                + " committed mode=" + lastCommittedMode(modesBefore) + "): " + modesBefore
                + " | server verdict " + exec("artest vs deck-capture"),
                "aboard".equals(lastCommittedMode(modesBefore)));
        double preY = bot().reportState().get("playerY").getAsDouble();
        double[] sub0 = parseSub(censusStatic("censusSubPos"));

        // Fixture enclosure by measurement: the ROOF must have entered the assembled ship - the
        // subspace block region reaches at least four blocks above the stand (roofless deck
        // variant: one). An open-topped build here would silently turn this test into the
        // supported-first-contact one above.
        String regionStr = censusStatic("censusRegion");
        double regionMaxY = parseRegionMaxY(regionStr);
        System.out.println("[cavity] stand sub0=" + censusStatic("censusSubPos")
                + " region=" + regionStr + " regionNonAir=" + censusStatic("censusRegionNonAir")
                + " server=" + exec("artest vs subspace-census"));
        assertTrue("the assembled ship must include the roof (region " + regionStr
                + " must reach >= 4 blocks above the stand at subY=" + sub0[1] + ")",
                regionMaxY >= sub0[1] + 4.0);

        // Displace the body OFF the deck into the cavity. At 170 degrees world-DOWN is ship-UP:
        // the teleport reads as an external move (drops the capture) and leaves the body
        // mid-cavity with no standing support, world gravity pulling it deeper into the cavity
        // (toward the roof), ship-frame gravity - if the interior gate claims it - pulling it
        // back to the deck. The two verdicts diverge by ~3 world blocks; the settle cannot
        // straddle them.
        //
        // The displacement targets STATE, not time: on a loaded box the attitude hold can still
        // be converging when the fixed pre-dismount wait elapses, and a world-down step at a
        // half-turned attitude maps mostly into the deck PLANE - the body never leaves the
        // stand. Re-step until the measured subspace position is actually mid-cavity (the ship
        // keeps turning between attempts), and only then judge the settle.
        Events clientEvents = clientEvents();
        String subAfter = censusStatic("censusSubPos");
        // The mark is re-taken INSIDE the loop so it belongs to the displacement that finally lands
        // the body mid-cavity: each earlier attempt is its own release-and-reclaim, and a mark from
        // before the first one would let an earlier round's records answer for the last.
        long releaseMark = clientEvents.mark();
        for (int i = 0; i < 8 && parseSub(subAfter)[1] <= sub0[1] + 0.5; i++) {
            if (i > 0) {
                bot().waitTicks(20); // reclaimed to the stand meanwhile; let the hold keep turning
            }
            releaseMark = clientEvents.mark();
            exec("tp @a ~ ~-1.2 ~");
            bot().waitTicks(2);
            subAfter = censusStatic("censusSubPos");
        }

        // Subject validity (geometry by measurement): the displaced body must still be INSIDE
        // the region AND off the deck - a body that stayed at the stand would be re-claimed by
        // plain standing support and prove nothing about the interior gate.
        assertTrue("the displaced body must remain INSIDE the ship's block region (sub="
                + subAfter + " region=" + regionStr + ")", subInRegion(subAfter, regionStr));
        assertTrue("the displaced body must be OFF the deck, mid-cavity (sub=" + subAfter
                + " vs stand " + sub0[1] + "; is the attitude hold converged? ship-info="
                + shipInfo() + ")", parseSub(subAfter)[1] > sub0[1] + 0.5);

        // ARRANGEMENT: the displacement must have dropped the CLIENT's capture (the server rebases
        // an EntityPlayerMP instead of releasing, so its probe cannot witness this).
        String releases = requireLink(clientEvents, releaseMark, "deck_released",
                "the mid-cavity displacement must read as an external move and drop the CLIENT"
                        + " capture, or nothing below is about a re-claim");

        // THE SUBJECT: the displaced body comes back under DECK semantics - carried by ship-frame
        // gravity against world gravity, at its deck stand, with the ship camera - instead of being
        // pinned to the cavity's world-floor by the outer-hull fallback (the reported "captured, but
        // the camera never flips" desync).
        //
        // NOT the `interior_claimed` record, and that is a statement about which mechanism a
        // teleport drives rather than a softening of the pin. `interiorCandidate` is consulted only
        // for a body the gate finds UNTRACKED, and a world teleport never leaves one for a tick: the
        // external-move guard releases inside the same tick's travel, and that tick's own commit
        // re-captures the body on the spot it moved to - production says so where it does it
        // ("reached only when heldShipFramePos released mid-tick (externalMove) and this commit
        // re-captures on the same anchor"), and a run's own record chain says it too: released,
        // captured and mode-committed with nothing in between. So the CLAIM gate is unreachable from
        // this stimulus, and awaiting it fails a healthy client. What the enclosure term does on the
        // path a teleport DOES drive is keep the re-captured body aboard - the anchored branch
        // demotes an unsupported body to hull-stand (mode "hull") or lets it go for having no deck
        // below unless it is inside the region under a ship-frame roof - and that is what the mode
        // commit and the settle below discriminate.
        //
        // A run that wants the claim gate itself has to arrange a body that is untracked INSIDE the
        // hull for at least one gate call: an entry through a hatch, a relog inside the cavity, or a
        // flight-off, none of which is a teleport.
        long reclaimMark = clientEvents.mark();
        String reclaimed = clientEvents.awaitCarrying(reclaimMark, "deck_captured",
                "\"ship\":\"" + scenarioShipId + "\"",
                "the displaced body must be re-captured BY THIS SHIP - a displacement that ends with"
                        + " no capture at all leaves the body to world gravity in the cavity, and one"
                        + " that ends on another hull has left the cavity under test",
                DECK_LINK_BUDGET_TICKS);

        // Sample the settle: where does the claimed body come to rest?
        StringBuilder trace = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            bot().waitTicks(3);
            trace.append(String.format(java.util.Locale.ROOT,
                    "[t%d y=%.2f cSub=%s obst=%s] ",
                    i * 3, bot().reportState().get("playerY").getAsDouble(),
                    censusStatic("censusSubPos"),
                    bot().readStaticField(SHIP_FRAME_TRAVEL, "lastObstacleCount").get("value")
                            .getAsString()));
        }
        // From the RELEASE mark for the same reason as the open-cockpit scenario above: the mode is
        // an EDGE written where a capture is installed or its mode changes, and the one commit of
        // this episode lands in the same tick as the release - before a mark taken at the re-claim
        // could open. The window from the release holds it, and everything in that window is
        // post-displacement.
        String modesAfter = awaitCommittedMode(clientEvents, releaseMark, "aboard");
        Events.assertInstrumentRan(modesAfter, "deck_mode_events",
                "the claimed cavity body was committed ABOARD rather than onto the outer hull");
        boolean shipCam = Boolean.parseBoolean(
                bot().readStaticField(SHIP_CAMERA, "shipCamActive").get("value").getAsString());
        double settledY = bot().reportState().get("playerY").getAsDouble();
        double[] subEnd = parseSub(censusStatic("censusSubPos"));
        String capEnd = exec("artest vs deck-capture");
        // Every release in the displacement window, each with the gate that performed it: a
        // `noDeckBelow` or `noHullContact` here would say the enclosure term did NOT hold the body,
        // which is the failure this scenario is about and is not visible in a height alone.
        String releasesAfter = clientEvents.since(releaseMark, "deck_released");
        System.out.println("[cavity] shipCamActive=" + shipCam + " preY=" + preY + " settledY="
                + settledY + " subEnd=" + subEnd[1] + " release=" + releases
                + " reclaim=" + reclaimed + " modes=" + modesAfter
                + " releasesInWindow=" + releasesAfter + " :: " + trace);

        // The interior-boarding contract, positive half: the ENCLOSED unsupported body is the
        // deck's - claimed ABOARD (not pinned by the outer-hull fallback on the cavity's
        // world-floor), carried back against world gravity to its deck stand, ship camera on.
        assertTrue("an unsupported body in an enclosed cavity must be claimed ABOARD, not pinned by"
                + " the outer-hull fallback (last committed mode=" + lastCommittedMode(modesAfter)
                + "): " + modesAfter + " | the releases in this window, each with the gate that"
                + " performed it: " + releasesAfter + " | server verdict " + capEnd + " :: " + trace,
                "aboard".equals(lastCommittedMode(modesAfter)));
        assertTrue("deck gravity must carry the body BACK to the deck, not let it settle on the "
                + "roof ~3 world blocks below (preY=" + preY + " settledY=" + settledY + "): " + trace,
                Math.abs(settledY - preY) < 1.5 && capEnd.contains("\"alreadyTracked\":true"));
        ShipIdentity.assertCaptureAnchoredOn(capEnd, scenarioShipId,
                "deck gravity must carry the body back to THIS ship's deck");
        assertTrue("the body must re-seat at its deck stand in subspace (subY " + subEnd[1]
                + " vs stand " + sub0[1] + "; seat-top landing allowed): " + trace,
                Math.abs(subEnd[1] - sub0[1]) <= 1.1);
        assertTrue("the client camera must engage for the reclaimed interior body "
                + "(shipCamActive=" + shipCam + ")", shipCam);
    }

    /** The max subspace Y of a census "x,y,z..x,y,z" region string, or NaN when malformed. */
    private static double parseRegionMaxY(String region) {
        try {
            return Double.parseDouble(region.split("\\.\\.")[1].split(",")[1].trim());
        } catch (RuntimeException malformed) {
            return Double.NaN;
        }
    }

    // ---- Flying-aboard: a captured flyer's flight kinematics resolve in the DECK frame ---------

    @Test
    public void aFlyingCrewMemberAscendsAlongTheDeckNormalAndReseatsOnFlightOff() throws Exception {
        final int bx = 6720, by = 64, bz = 6720;

        // The flying-aboard contract on a steeply ROLLED ship: starting creative flight on the
        // deck keeps the body the deck's (no release, ship camera stays), the vertical fly
        // intent ascends along the DECK NORMAL - measured in SUBSPACE, where deck-up is plain +Y
        // regardless of the roll; a world-up ascent would instead leak most of its motion into
        // the subspace deck PLANE (at 60 deg: cos60 = 0.5 up, sin60 = 0.87 sideways) - and
        // turning flight off hands the body to deck gravity, which seats it back on the deck.
        buildAndBoardShip(bx, by, bz);
        exec("gamemode creative @a"); // flight needs creative; the harness default is not
        bot().waitTicks(20);
        double h = Math.toRadians(60.0) / 2.0;
        assertTrue("attitude hold must accept the roll",
                exec("artest vs point-by-id 0 " + scenarioShipId + " "
                        + Math.cos(h) + " " + Math.sin(h) + " 0.0 0.0").contains("\"commanded\":true"));
        bot().waitTicks(150);

        // Same arrangement chain as the two interior scenarios.
        Events events = events();
        long dismountMark = events.markInstrumented();
        exec("artest player dismount");
        requireChain(events, dismountMark, "the dismounted pilot must be taken by the rolled deck",
                "dismount", "deck_captured");
        String modesBefore = awaitCommittedMode(events, dismountMark, "aboard");
        Events.assertInstrumentRan(modesBefore, "deck_mode_events",
                "the deck committed a capture MODE for the dismounted pilot");
        assertTrue("the dismounted pilot must be captured ABOARD on the rolled deck (last committed"
                + " mode=" + lastCommittedMode(modesBefore) + "): " + modesBefore
                + " | server verdict " + exec("artest vs deck-capture"),
                "aboard".equals(lastCommittedMode(modesBefore)));
        double[] sub0 = parseSub(censusStatic("censusSubPos"));

        // Start creative flight: double-tap space (the first tap is a deck jump; the second,
        // within the toggle window, flips flight). Marked FIRST: "flight must not release the
        // capture" is an ABSENCE, and an absence is only an answer when it is read from a log whose
        // window opened before the stimulus.
        Events clientEvents = clientEvents();
        long flightMark = clientEvents.mark();
        bot().holdKey(org.lwjgl.input.Keyboard.KEY_SPACE);
        bot().waitTicks(2);
        bot().releaseKey(org.lwjgl.input.Keyboard.KEY_SPACE);
        bot().waitTicks(2);
        bot().holdKey(org.lwjgl.input.Keyboard.KEY_SPACE);
        bot().waitTicks(2);
        bot().releaseKey(org.lwjgl.input.Keyboard.KEY_SPACE);
        bot().waitTicks(4);

        // The double-tap itself climbs a few blocks (a deck jump + held-space flight ticks), so
        // re-baseline AFTER flight is on: the pin measures the held-ascend phase alone. The hold
        // is SHORT deliberately - the stay region ends ~4 blocks above the hull top, and a climb
        // that exits it is a legitimate release (leaving the region ends the capture), not this
        // pin's subject.
        double[] subFly = parseSub(censusStatic("censusSubPos"));
        StringBuilder trace = new StringBuilder();
        int trackedSeen = 0, camSeen = 0, samples = 0;
        double[] subEnd = subFly;
        // Climb TO A TARGET RISE (+2 subspace blocks), not for a fixed time: the climb rate
        // varies run to run, and a timed hold can overshoot into the stay region's edge - whose
        // release is the region-exit rule doing its job, not this pin's subject. From a ~129-130
        // start the +2 target tops out well below that edge.
        bot().holdKey(org.lwjgl.input.Keyboard.KEY_SPACE);
        try {
            // Scale the climb-sampling ceiling by the fork factor (load-tail): under client
            // frame-starvation the held-SPACE climb reaches the +2 target in more client ticks. This
            // loop also accumulates the per-sample tracked/cam invariants, so scale the COUNT in place
            // (early-exit on the +2 target kept) rather than threshold-poll it (it double-duties).
            int climbIters = (int) Math.ceil(10 * TestTimeouts.factor());
            for (int i = 0; i < climbIters && subEnd[1] - subFly[1] < 2.0; i++) {
                bot().waitTicks(2);
                samples++;
                String cap = exec("artest vs deck-capture");
                // Anchored on THIS scenario's craft, per sample: the count below is a claim about
                // one body keeping one ship's interior frame through the climb, and a sample taken
                // against another hull in the same airspace is not evidence for it.
                boolean tracked = cap.contains("\"alreadyTracked\":true")
                        && !cap.contains("\"hullStand\":true")
                        && scenarioShipId.equals(ShipIdentity.anchorOf(cap));
                if (tracked) trackedSeen++;
                if (Boolean.parseBoolean(bot().readStaticField(SHIP_CAMERA, "shipCamActive")
                        .get("value").getAsString())) {
                    camSeen++;
                }
                subEnd = parseSub(censusStatic("censusSubPos"));
                trace.append(String.format(java.util.Locale.ROOT, "[t%d sub=%.1f,%.1f,%.1f cap=%b] ",
                        i * 2, subEnd[0], subEnd[1], subEnd[2], tracked));
            }
        } finally {
            bot().releaseKey(org.lwjgl.input.Keyboard.KEY_SPACE);
        }
        double dySub = subEnd[1] - subFly[1];
        double dxzSub = Math.sqrt((subEnd[0] - subFly[0]) * (subEnd[0] - subFly[0])
                + (subEnd[2] - subFly[2]) * (subEnd[2] - subFly[2]));
        System.out.println("[flyaboard] subFly=" + subFly[1] + " dySub=" + dySub + " dxzSub="
                + dxzSub + " tracked=" + trackedSeen + "/" + samples + " cam=" + camSeen
                + "/" + samples + " :: " + trace);

        // The contract, in its three player-visible parts. The first is an ABSENCE and is read as
        // one: the client's resolver records EVERY release with the gate that performed it, so
        // "starting flight did not release the capture" is "no `deck_released` since the mark" -
        // where the per-sample server probe could only miss a release that was recovered between
        // two samples, and could not name `creativeFlight` if it caught one.
        String releasesInFlight = clientEvents.since(flightMark, "deck_released");
        Events.assertInstrumentRan(releasesInFlight, "deck_capture_events",
                "starting creative flight on the deck did not release the client's capture");
        assertTrue("starting flight on the deck must NOT release the capture - a flyer the deck"
                + " already owns keeps deck semantics, and `creativeFlight` is the gate that would"
                + " have taken it away: " + releasesInFlight + " :: " + trace,
                Events.countRecords(releasesInFlight, "\"reason\"") == 0);
        assertTrue("starting flight on the deck must NOT release the capture (tracked "
                + trackedSeen + "/" + samples + "): " + trace, trackedSeen == samples);
        assertTrue("the ship camera must stay engaged for a flying-aboard body (cam " + camSeen
                + "/" + samples + ")", camSeen == samples);
        // The census position is block-floored, so allow a block of lateral jitter; a WORLD-up
        // ascent at 60 deg would drift the deck plane by ~1.7x the climb (several blocks here).
        assertTrue("holding ascend must climb along the DECK NORMAL (subspace +Y): dySub=" + dySub
                + " dxzSub=" + dxzSub + " :: " + trace, dySub > 1.2 && dxzSub < 1.6);

        // Descend back toward the deck first - the flight-off double-tap itself adds a little
        // climb, and toggling at the stay region's edge exits it mid-flight (leaving the stay
        // region is a legitimate release, but then WORLD gravity owns the fall and the reseat
        // below is not this contract's). The descend leg also pins the OTHER vertical intent:
        // sneak sinks along the deck normal exactly as space climbs it.
        double[] subHigh = subEnd;
        // Event-gated descend (load-scaled ceiling + early exit): hold sneak until the body has sunk
        // along the deck normal, instead of a fixed 14-tick budget a frame-starved client can under-sink
        // under concurrent-fork load. Census-Y is block-floored, so the predicate is a strict drop below
        // the captured start height.
        bot().holdKey(org.lwjgl.input.Keyboard.KEY_LSHIFT);
        try {
            ClientPoll.until(bot()::waitTicks,
                    () -> parseSub(censusStatic("censusSubPos"))[1],
                    y -> y < subHigh[1], 2, 7);
        } finally {
            bot().releaseKey(org.lwjgl.input.Keyboard.KEY_LSHIFT);
        }
        double[] subDown = parseSub(censusStatic("censusSubPos"));
        assertTrue("holding descend must sink along the DECK NORMAL (subspace -Y): "
                + subHigh[1] + " -> " + subDown[1], subDown[1] < subHigh[1]);

        // Flight off: double-tap again; deck gravity reclaims the airborne body and seats it.
        // The toggle targets STATE, not time: under suite load the client can stretch the two
        // taps past vanilla's double-tap window and the toggle silently misses (the body then
        // hovers forever and the seat wait below measures nothing) - so re-tap while the
        // capture probe still reports the body flying.
        boolean seated = false;
        String capEnd = "";
        double[] subSeated = subEnd;
        long flightOffMark = clientEvents.mark();
        for (int round = 0; round < 4; round++) {
            bot().holdKey(org.lwjgl.input.Keyboard.KEY_SPACE);
            bot().waitTicks(2);
            bot().releaseKey(org.lwjgl.input.Keyboard.KEY_SPACE);
            bot().waitTicks(2);
            bot().holdKey(org.lwjgl.input.Keyboard.KEY_SPACE);
            bot().waitTicks(2);
            bot().releaseKey(org.lwjgl.input.Keyboard.KEY_SPACE);
            bot().waitTicks(6);
            if (!exec("artest vs deck-capture").contains("\"isFlying\":true")) {
                break; // the toggle registered; NEVER tap again or flight flips back on
            }
        }
        for (int i = 0; i < 40 && !seated; i++) {
            bot().waitTicks(3);
            capEnd = exec("artest vs deck-capture");
            subSeated = parseSub(censusStatic("censusSubPos"));
            // The descend leg parks the body over the SEAT column, so deck gravity may seat it on
            // the seat block's top - one block above the deck stand. Either landing is "seated on
            // the ship's geometry at the deck spot"; only staying airborne (or lost to the world)
            // fails.
            // `subSeated` is a subspace coordinate of the ANCHOR ship and `sub0` was taken in this
            // scenario's own — so without the anchor the height comparison below is between two
            // frames rather than two moments, and it is that comparison, not the flag, that decides
            // "he came back down onto the deck".
            seated = capEnd.contains("\"alreadyTracked\":true")
                    && !capEnd.contains("\"hullStand\":true")
                    && scenarioShipId.equals(ShipIdentity.anchorOf(capEnd))
                    && subSeated[1] <= sub0[1] + 1.4;
        }
        // The LANDING itself, as production's own edge: the deck resolver owned the tick and put the
        // body on a surface it was not on before. That is the moment deck gravity finishes the job,
        // and it is what separates "seated by the deck" from "happened to be near the stand" - which
        // is all a floored census position and a boolean can say between them.
        String contacts = clientEvents.since(flightOffMark, "deck_contact");
        Events.assertInstrumentRan(contacts, "deck_contact_events",
                "deck gravity seated the body on the ship's geometry when flight ended");
        exec("gamemode survival @a"); // leave the shared world as the other tests expect it
        assertTrue("turning flight off must hand the body to deck gravity and seat it back "
                + "(sub=" + subSeated[1] + " vs start " + sub0[1] + "): " + capEnd, seated);
        assertTrue("the body must actually make CONTACT with the ship's geometry when flight ends -"
                + " a body merely hovering at the right height was never seated by deck gravity: "
                + contacts, Events.countRecords(contacts, "\"ship\"") > 0);
    }

    /** "x,y,z" census position as doubles (block coords are integral; that is fine here). */
    private static double[] parseSub(String sub) {
        String[] s = sub.split(",");
        return new double[]{Double.parseDouble(s[0].trim()), Double.parseDouble(s[1].trim()),
                Double.parseDouble(s[2].trim())};
    }

    // ---- helpers (self-contained, mirroring the other tier-2 e2e classes) ----------------------

    /** Build the ship and sit the bot on its pilot seat; returns the ship's world position. */
    private double[] buildAndBoardShip(int bx, int by, int bz) throws Exception {
        return buildAndBoardShip(bx, by, bz, VARIANT);
    }

    private int readIntFrom(String json, Pattern p) {
        Matcher m = p.matcher(json);
        assertTrue("expected an integer in: " + json, m.find());
        return Integer.parseInt(m.group(1));
    }

    private double[] buildAndBoardShip(int bx, int by, int bz, String variant) throws Exception {
        double[] ship = buildShip(bx, by, bz, variant);
        // The seat is located INSIDE this scenario's own ship: `vs seat-mount <dim>` takes the first
        // pilot seat in the world's loaded-tile list with no position filter, which is unambiguous
        // only while the world holds one ship, and mounts a neighbour's once scenarios share one.
        // By identity. The positional form resolves the yard through the ship NEAREST the base — no
        // containment test and no distance bound — so on this shared world it can seat the bot on a
        // neighbour's craft and say seatFound:true doing it.
        String seat = exec("artest vs find-seat 0 id " + scenarioShipId);
        assertTrue("find-seat must locate the pilot seat inside THIS scenario's ship ("
                + scenarioShipId + ", built at " + bx + "," + by + "," + bz + "): " + seat,
                seat.contains("\"seatFound\":true"));
        String mountInfo = exec("artest vs seat-mount-at 0 " + readIntFrom(seat, SEAT_X) + " "
                + readIntFrom(seat, SEAT_Y) + " " + readIntFrom(seat, SEAT_Z));
        Matcher dm = DUMMY_ID.matcher(mountInfo);
        assertTrue("seat-mount-at must report a dummy id: " + mountInfo, dm.find());
        assertTrue("bot must mount the seat dummy: " + mountInfo,
                exec("artest player mount-entity " + dm.group(1)).contains("\"mounted\":true"));
        bot().waitTicks(10);
        return ship;
    }

    private double[] buildShip(int bx, int by, int bz, String variant) throws Exception {
        exec("tp @a " + (bx + 600) + " 120 " + (bz + 600) + " 0 0");
        bot().waitTicks(10);

        // The mark is taken BEFORE the assembly is queued, so the record awaited below is THIS
        // scenario's own ship and never a neighbour's - which is what a whole-dimension COUNT could
        // never be on a shared world.
        Events events = events();
        long spawnMark = events.markInstrumented();
        exec("artest vs spawn-diag reset");
        String assemble = assembleFixture(bx, by, bz, variant);
        assertTrue("a with-pilot-seat build must route to a ship: " + assemble,
                assemble.contains("\"rocketCount\":0"));

        // The registry's own addShip, awaited as a LINK. The count poll this replaces could not see
        // one: raising its budget from 200 to 600 ticks was measured and changed nothing (2/4 red
        // either way) because the ship never entered the registry at all, and a count
        // that never moves says only "not yet" however long it is given.
        try {
            // The identity comes from HERE — the registry's record of this assembly's own add — and
            // not from a lookup at the base afterwards.
            scenarioShipId = awaitShipSpawned(events, spawnMark,
                    "the tier-2 assembly must become a VS ship in the queryable registry");
        } catch (AssertionError neverSpawned) {
            throw new AssertionError(neverSpawned.getMessage()
                    + " | spawn-diag: " + exec("artest vs spawn-diag").replace('\n', ' ')
                    + " | assemble said: " + assemble.replace('\n', ' '), neverSpawned);
        }
        bot().waitTicks(40);

        exec("tp @a " + (bx + 0.5) + " " + (by + 6) + " " + (bz + 0.5) + " 0 0");
        bot().waitTicks(20);

        // An ARRANGEMENT gate, and it stays a probe read: `ship_spawned` says the REGISTRY knows the
        // ship, which is a different fact from "a physics object is loaded here with the client
        // present" - the state everything below needs. What is polled is that LOAD, of the craft
        // already named above; the previous form re-derived the identity here from a bounded lookup
        // at the base and accepted whatever answered within 24 blocks of it.
        String info = "";
        double[] where = null;
        for (int i = 0; i < 40 && where == null; i++) {
            bot().waitTicks(5);
            info = exec("artest vs ship-info 0 id " + scenarioShipId);
            if (!info.contains("\"managed\":true")) {
                continue;
            }
            where = new double[]{readDouble(info, POS_X), readDouble(info, POS_Y),
                    readDouble(info, POS_Z)};
        }
        assertTrue("this scenario's ship (" + scenarioShipId + ") must LOAD with the client present;"
                + " last reply was: " + info, where != null);

        // Fixture completeness by measurement: how many blocks did the assembled ship actually
        // get (region census + the ship's own blockPositions count + iron in the grown
        // neighbourhood)? Sampled twice a second apart to tell a stalled-but-progressing
        // relocation from a settled short count. The census probe resolves the ship by
        // containment, so stand the bot INSIDE the craft's world box for the reading.
        exec("tp @a " + (bx + 3.5) + " " + (by + 6) + " " + (bz + 3.5) + " 0 0");
        bot().waitTicks(4);
        String census1 = exec("artest vs subspace-census");
        bot().waitTicks(20);
        String census2 = exec("artest vs subspace-census");
        // The deck is built at (rocketX+-2, rocketY+3, rocketZ+-2) with rocket=(base+3,base+1,base+3),
        // i.e. world (bx+1..bx+5, by+4, bz+1..bz+5) before assembly relocates it into the ship.
        String leftover = exec("testforblock " + (bx + 3) + " " + (by + 4) + " " + (bz + 3)
                + " minecraft:iron_block")
                + " | " + exec("testforblock " + (bx + 5) + " " + (by + 4) + " " + (bz + 5)
                + " minecraft:iron_block");
        System.out.println("[interior] census postBuild#1=" + census1);
        System.out.println("[interior] census postBuild#2=" + census2);
        System.out.println("[interior] leftoverDeckAtBase=" + leftover);
        // Roofed-variant diagnostic: iron left at the roof plane after assembly means the roof
        // did not join the ship - pre-lift (by+9) = never scanned, post-lift (by+10) = scanned
        // but dropped by the assembly's connectivity flood-fill.
        System.out.println("[interior] leftoverRoofAtBase="
                + exec("testforblock " + (bx + 3) + " " + (by + 9) + " " + (bz + 3)
                        + " minecraft:iron_block")
                + " | " + exec("testforblock " + (bx + 3) + " " + (by + 10) + " " + (bz + 3)
                        + " minecraft:iron_block"));
        return where;
    }

    private String assembleFixture(int baseX, int baseY, int baseZ, String variant) throws Exception {
        // EXPERIMENT (#60 root cause): the site (6820,6820) is FORESTED. VS's flood detector treats
        // leaves/logs as floodable (they are NOT in shipSpawnDetectorBlacklist), so when the tier-2
        // assembly flood escapes the craft it grabs the surrounding canopy and hits the 15001 cap ->
        // "Ship too big" abort -> no ship. The tight pre-clear only cleared the fixture's own box, not
        // the trees. Fell everything in the flood's measured reach (bbox was ~[base-16..+19, ..90]).
        int cx1 = (baseX - 18) >> 4, cz1 = (baseZ - 18) >> 4;
        int cx2 = (baseX + 22) >> 4, cz2 = (baseZ + 22) >> 4;
        assertTrue("chunk warmup failed",
                exec("artest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2)
                        .contains("\"ok\":true"));
        // Two stacked fills: the fill verb caps volume at 32768; base-18..base+21 (40 wide) x 20 tall
        // x 40 = 32000 each, covering the measured escaped-flood bbox in two layers.
        assertTrue("pre-clear (lower) failed",
                exec("artest fill 0 " + (baseX - 18) + " " + (baseY + 1) + " " + (baseZ - 18)
                        + " " + (baseX + 21) + " " + (baseY + 20) + " " + (baseZ + 21) + " minecraft:air")
                        .contains("\"ok\":true"));
        assertTrue("pre-clear (upper) failed",
                exec("artest fill 0 " + (baseX - 18) + " " + (baseY + 21) + " " + (baseZ - 18)
                        + " " + (baseX + 21) + " " + (baseY + 40) + " " + (baseZ + 21) + " minecraft:air")
                        .contains("\"ok\":true"));
        String fixture = exec("artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " " + variant);
        assertTrue("fixture (" + variant + ") failed: " + fixture, fixture.contains("\"ok\":true"));
        Matcher bp = BUILDER_POS.matcher(fixture);
        assertTrue("fixture missing builderPos: " + fixture, bp.find());
        return exec("artest rocket assemble 0 " + bp.group(1) + " " + bp.group(2) + " " + bp.group(3));
    }

    /** This scenario's ship, asked by identity — no distance term to be wrong about. */
    private String shipInfo() throws Exception {
        assertTrue("shipInfo() before buildShip() captured an identity", scenarioShipId != null);
        return shipInfoById(scenarioShipId);
    }

    /** One CLIENT-side subspace-census static (ShipFrameTravel.census*), as a plain string. */
    private String censusStatic(String field) throws Exception {
        return bot().readStaticField(SHIP_FRAME_TRAVEL, field).get("value").getAsString();
    }

    /** Whether a census "x,y,z" block position lies inside a census "x,y,z..x,y,z" region. */
    private static boolean subInRegion(String sub, String region) {
        try {
            String[] s = sub.split(",");
            String[] r = region.split("\\.\\.");
            String[] lo = r[0].split(",");
            String[] hi = r[1].split(",");
            for (int a = 0; a < 3; a++) {
                int v = Integer.parseInt(s[a].trim());
                if (v < Integer.parseInt(lo[a].trim()) || v > Integer.parseInt(hi[a].trim())) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    private double readDouble(String json, Pattern p) {
        Matcher m = p.matcher(json);
        assertTrue("expected a number in: " + json, m.find());
        return Double.parseDouble(m.group(1));
    }

    private static double distance(double[] a, double[] b) {
        double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
