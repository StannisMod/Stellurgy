package zmaster587.advancedRocketry.test.client;


import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;


import zmaster587.advancedRocketry.test.DeckCapture;
import zmaster587.advancedRocketry.test.Events;
import zmaster587.advancedRocketry.test.GameTicks;
import zmaster587.advancedRocketry.test.Reply;
import zmaster587.advancedRocketry.test.PilotSeat;
import zmaster587.advancedRocketry.test.ShipInfo;
import zmaster587.advancedRocketry.test.FixtureSite;
import zmaster587.advancedRocketry.test.RocketFixture;
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

    /**
     * How far a body may settle from where it was on an INVERTED hull, in blocks.
     *
     * <p>The TEST'S OWN: the claim is that he stayed with the ship, and the failure it refuses is
     * falling out of it — which is many blocks. Two and a half is the room a body has inside the
     * cockpit cavity it is released in.</p>
     */
    private static final double STAYED_WITH_THE_SHIP_BLOCKS = 2.5;

    /**
     * The same bound where deck gravity must carry him BACK, in blocks — tighter, because the
     * failure here is settling on the roof about three world blocks below.
     */
    private static final double CARRIED_BACK_TO_DECK_BLOCKS = 1.5;

    /**
     * How far from his own subspace stand a re-seated body may be, in blocks.
     *
     * <p>The TEST'S OWN: a seat top is about a block above the floor it stands on, so this allows a
     * landing on the seat rather than beside it, and nothing further.</p>
     */
    private static final double RESEATED_AT_HIS_STAND_BLOCKS = 1.1;

    /**
     * How far an ascending body must rise ALONG THE DECK NORMAL, and how far it may stray across
     * it, in subspace blocks.
     *
     * <p>Both are the test's own, and the pair is the whole claim: the climb is along the deck's
     * own +Y rather than the world's. The lateral bound is deliberately close to the vertical one,
     * so a body climbing at 45 degrees — which is what a world-frame ascent looks like on a rolled
     * deck — fails.</p>
     */
    private static final double ASCENT_ALONG_NORMAL_BLOCKS = 1.2;
    /** @see #ASCENT_ALONG_NORMAL_BLOCKS */
    private static final double ASCENT_LATERAL_BLOCKS = 1.6;

    /**
     * Readings, two client ticks apart, of a flyer holding ascend on the deck — and so the DOSE of
     * flight: four client ticks, which the 2026-09-23 gate measured taking him +2 along the deck
     * normal (subFly=129.0, dySub=2.0 after two samples), against the {@link
     * #ASCENT_ALONG_NORMAL_BLOCKS} the assertion asks for. One measurement, printed on every run as
     * {@code [flyaboard]}, is what a retuning starts from.
     */
    private static final int FLY_ABOARD_DOSE_SAMPLES = 2;

    /** Client ticks of held descend after the climb — derived, not measured; see its use. */
    private static final int FLY_ABOARD_DESCEND_TICKS = 10;

    @Override
    protected String subsystem() {
        return "vs-crew-boarding";
    }

    private static final String DUMMY_ID = "dummyId";

    private static final String VARIANT = "with-pilot-deck";

    /**
     * THIS scenario's ship, by identity — captured by {@code buildShip} at the one moment its base
     * provably holds no other, and the address every later question and command uses. A radius bound
     * is a mitigation, not an identity: these scenarios roll, hover and drop the ship on purpose, and
     * a shared client always has a neighbour in candidacy.
     */
    private String scenarioShipId;
    private static final String SHIP_FRAME_TRAVEL =
            "zmaster587.advancedRocketry.integration.vs.ShipFrameTravel";

    /**
     * How long one link of a deck-capture chain may take. A DEADLINE for a discrete event, not a
     * guess at how long a value takes to settle: production either releases, claims and captures a
     * body or it does not, and 240 ticks is generous against an eight-fork load while still failing
     * a scenario that never gets there rather than waiting out a budget.
     */
    private static final int DECK_LINK_BUDGET_TICKS = 240;

    /**
     * The WINDOW the attitude hold is given to slew the hull into its inversion, in SERVER ticks of
     * the world the hull stands in — spent in full, because a hold never decides it has arrived and
     * so there is no record to end on. The number is the 200 the fixed client wait it replaces used.
     *
     * <p>It is NOT the slew's own clock: the hold's torque is applied on the physics mod's thread,
     * which paces its fixed-interval steps by wall clock. So this window does not make the arrival
     * box-independent; what makes the arrangement honest is the attitude read after it, which fails
     * as an arrangement when the hull is short instead of letting a later step paper over it.</p>
     */
    private static final int INVERSION_SLEW_TICKS = 200;

    /** The 60-degree roll's slew window, in the hull's world ticks — the 150 client ticks it used
     *  to be, moved onto the clock the hull's world advances on. */
    private static final int ROLL_SLEW_TICKS = 150;

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
     * The {@code deck_mode_committed} records since {@code mark}, once the LAST of them says
     * {@code wanted} — or a failure here, carrying every mode production committed and in which
     * order.
     *
     * <p><b>An {@code awaitMatching} and not an {@code await}</b>, because the mode is a state
     * production can reach in two commits: a body may be taken in hull-stand and PROMOTED to aboard
     * on a later tick, so what is waited for is the settled ANSWER rather than one edge. That is a
     * condition over the records, which is what {@code awaitMatching} is for — and it is still the
     * log, not a probe dump, so a failure narrates the sequence.</p>
     */
    private String awaitCommittedMode(Events log, long mark, String wanted, String what)
            throws Exception {
        // The shared wait, and it FAILS here rather than handing a reply back to be re-checked.
        // Each of the five call sites used to read this loop's result and then assert
        // `wanted.equals(lastCommittedMode(reply))` — the loop's own exit condition, restated. So
        // none of those five could fail except by the budget running out, and their messages then
        // made a claim about the deck's capture mode for what was a timeout.
        try {
            return log.awaitMatching(mark, "deck_mode_committed",
                    reply -> wanted.equals(lastCommittedMode(reply)),
                    "committing `" + wanted + "` as its LAST mode", what, DECK_LINK_BUDGET_TICKS);
        } catch (AssertionError never) {
            // Which silence: the deck committed nothing at all, or committed something else. The
            // instrument check separates them before the message is believed, and the server's own
            // verdict goes in beside it because that is what a reader compares the log against.
            Events.assertInstrumentRan(log.since(mark, "deck_mode_committed"), "deck_mode_events",
                    "the deck committed a capture MODE at all");
            throw new AssertionError(never.getMessage() + " | server verdict "
                    + exec("artest vs deck-capture"));
        }
    }

    @Test
    public void aBodyReleasedInsideAnInvertedShipIsSeatedBackOnTheDeck()
            throws Exception {
        final FixtureSite site = site();
        final int bx = site.x, by = site.y, bz = site.z;

        // Seat the bot, invert the ship under him, dismount INSIDE: the dismount seed captures
        // him ABOARD in the cockpit of the inverted ship.
        buildAndBoardShip(site);
        double h = Math.toRadians(170.0) / 2.0;
        double upBefore = ShipInfo.byId(this::exec, 0, scenarioShipId).upY();
        assertTrue("attitude hold must accept the inversion",
                Reply.of(exec("artest vs point-by-id 0 " + scenarioShipId + " "
                        + Math.cos(h) + " " + Math.sin(h) + " 0.0 0.0")).bool("commanded"));
        // WINDOW: the same slew window the roofed-deck scenario below argues, counted on the hull's
        // world clock; its two ends are upBefore and the read after, and the gate names both.
        GameTicks.advanceWorld(serverClient(), 0, INVERSION_SLEW_TICKS);
        double upAfter = ShipInfo.byId(this::exec, 0, scenarioShipId).upY();
        scenario().requireArranged("the hull must be upside down before the pilot is released inside"
                        + " it, or this is the upright case again: upY " + upBefore + " -> " + upAfter
                        + " over " + INVERSION_SLEW_TICKS + " server ticks",
                upAfter < 0.0);

        // The arrangement as a CHAIN, not a budget: the probe un-seats him and the deck takes him.
        // `dismount` is recorded at the un-seating and `deck_entered` at the capture production
        // installs, so a failure names WHICH link never happened - where the 30x4 poll it replaces
        // could only print the last sample of a server verdict.
        Events events = events();
        long dismountMark = events.markInstrumented();
        exec("artest player dismount");
        requireChain(events, dismountMark, "the dismounted pilot must be taken by the deck inside the"
                + " inverted ship", "dismount", "deck_entered");
        // ...and ABOARD, not stood on the outer hull. Production commits the mode itself at every
        // transition, so it is read from that commit instead of inferred from the probe's dump.
        String modesBefore = awaitCommittedMode(events, dismountMark, "aboard",
                "the dismounted pilot must be captured ABOARD inside the inverted ship, not held"
                        + " with world semantics on the outer hull");
        double preY = bot().reportState().get("playerY").getAsDouble();

        // Subspace census at the QUIET STANDING phase (the ledgered obst=0 already shows here):
        // server = control (must be rich), client statics = the side under suspicion. Server rich +
        // client empty ==> the client never received the ship's subspace chunks.
        // The client's own latest census RECORD, and every seed outcome recorded in this scenario's
        // window. Both used to be statics: nine census fields and five seed counters, one set per
        // JVM, so on a shared client they carried whatever body ran last — and the seed counters were
        // lifetime totals, which cannot say whether THIS dismount's seed landed.
        System.out.println("[interior] census standing: server=" + exec("artest vs subspace-census")
                + " client={ship=" + censusField("ship")
                + " tracked=" + censusField("tracked")
                + " subPos=" + censusField("subPos")
                + " chunkLoaded=" + censusField("chunkLoaded")
                + " nonAir=" + censusField("nonAir")
                + " boxes=" + censusField("collisionBoxes")
                + " region=" + censusField("region")
                + " regionNonAir=" + censusField("regionNonAir") + "}"
                // The whole client ring, deliberately: `dismountMark` is a SERVER sequence and the
                // seeds under suspicion are the CLIENT's, so windowing this by it would silently
                // read one log's mark against the other's numbering. Each record names its body.
                + " seeds=" + clientEvents().since(0, "ship_frame_seed"));

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
        //
        // Two links, because the read is of a CLIENT census and it has to be one taken after the
        // client applied the teleport: a census from before it reports the stand he was moved off,
        // which is inside the region by construction and would pass this check on any teleport.
        String moved = clientEvents.await(releaseMark, "client_pos_look_applied",
                "the in-hull teleport must land on the client", DECK_LINK_BUDGET_TICKS);
        long movedSeq = (long) Events.number(Events.lastRecord(moved), "seq");
        clientEvents.await(movedSeq + 1, "subspace_census",
                "the client must take a census after applying the teleport; none at all means no"
                        + " ship claims his position on his own client",
                DECK_LINK_BUDGET_TICKS);
        String subAfterRelease = censusField("subPos");
        String regionStr = censusField("region");
        assertTrue("the released body must remain INSIDE the ship's block region (sub="
                + subAfterRelease + " region=" + regionStr + ")",
                subInRegion(subAfterRelease, regionStr));

        // THE PREMISE OF THIS LEG CHANGED WITH THE GAME, and it is not a test detail — read this
        // before the assertion below.
        //
        // It used to require that a world teleport INSIDE the hull dropped the client's capture, via
        // the external-move guard, so that the re-claim it then measured had something to re-claim.
        // That guard compared the body's live ship-frame point against the one the last tick had
        // committed and called a large enough difference a foreign mover. It is gone (2026-09-16):
        // a capture is a STATE entered and left by edges, and a body moved WITHIN the craft that
        // holds it has not left anything, so nothing releases it.
        //
        // So the teleport no longer ends the episode, and the correct assertion is the opposite of
        // the old one: the body STAYS aboard. That is the behaviour the ruling asks for — being
        // moved about inside a ship you are on does not throw you off it — and it is a stronger
        // contract than the re-claim ever was, because a re-claim can only be observed after a drop
        // the player should never have experienced.
        DeckCapture stillHeld = deckCaptureOfThisShip(scenarioShipId,
                "a body teleported WITHIN the hull that holds it must still be held by that hull:"
                        + " moving inside a craft is not leaving it");
        scenario().requireArranged("the body must remain aboard across a teleport inside the hull,"
                + " with no release at all: " + stillHeld.raw()
                + " | releases in the window (there should be none): "
                + clientEvents.since(releaseMark, "deck_released"),
                stillHeld.verdict);

        // THE SUBJECT MOVED WITH THE PREMISE, and the wait that stood here is gone rather than
        // renamed. It awaited a RE-capture: the teleport dropped the body, and the deck was supposed
        // to take it back instead of letting it fall out through the world-down cockpit opening.
        // With no release there is no re-capture, and a wait for one would spend its whole budget on
        // an edge nobody is going to write — the exact defect this family spent two days on, only
        // inverted.
        //
        // What that wait was really defending is unchanged and is now asserted ABOVE, one gate
        // earlier: the body is still held by this craft after being moved about inside it. The
        // settle sampled below — where he comes to rest, and whose camera he has — is the other half
        // and needs no wait at all, because he never left.
        String reclaimed = "no re-capture: the body was never released (see the premise above)";

        StringBuilder trace = new StringBuilder();
        // Sample the settle: where does the body come to rest, and what camera does the client own?
        // WINDOW: the trace is its deliverable. Coming to rest is a value approached over ticks, not
        // an instant production commits, and the per-tick record read after this loop is what
        // carries the verdict. What this cannot see: motion inside one 3-tick sample.
        for (int i = 0; i < 30; i++) {
            bot().waitTicks(3);
            // No obst=/onDeck= columns: both are in the per-tick line appended after this loop
            // (its `s=` tail and its `d=` flag), written on every resolved tick where this sampled
            // every third, and fetched once where this paid a round trip per field per iteration.
            trace.append(String.format(java.util.Locale.ROOT,
                    "[t%d y=%.2f cSub=%s cLoaded=%s cAir=%s cBox=%s cRegAir=%s] ",
                    i * 3, bot().reportState().get("playerY").getAsDouble(),
                    censusField("subPos"),
                    censusField("chunkLoaded"),
                    censusField("nonAir"),
                    censusField("collisionBoxes"),
                    censusField("regionNonAir")));
        }
        trace.append(System.lineSeparator()).append("  per-tick resolution: ")
                .append(Events.fieldLines(clientEvents().since(0, "ship_frame_tick"), "line"));
        // The mode is read from the RELEASE mark, not from the re-claim mark, and the difference is
        // the whole reason this read can answer at all. `deck_mode_committed` is an EDGE — it is
        // written at `logCapture`, which production calls only when a capture is INSTALLED or its
        // mode TRANSITIONS — and it can be written in the very tick that performs the release, so a
        // window opened at the RE-CLAIM can miss it entirely and the read then comes back null on a
        // body the trace shows resolved on the deck. The window from the RELEASE covers the release
        // and the re-claim both, and every mode commit in it is post-release by construction.
        //
        // NOT a wait on `deck_mode_committed` any more, and the reason is the whole change: that
        // record is an EDGE, written when a capture is installed or its MODE TRANSITIONS. The
        // episode is never broken here now, so the mode never transitions and the edge never fires
        // — the wait spent its whole budget and reported "the deck committed nothing", over a
        // window holding hundreds of resolved ticks of a body that was aboard the entire time.
        // Measured 2026-09-16, both interior scenarios.
        //
        // The claim was never about a transition. It is that the body ends up under DECK semantics
        // rather than pinned to the cavity's world floor by the outer-hull fallback, and that is a
        // STATE: `hullStand` false on a live capture. Read it, do not wait for an edge that says it.
        DeckCapture modesAfter = deckCaptureOfThisShip(scenarioShipId,
                "the body in the cavity must still be held by THIS craft");
        assertTrue("the body inside the hull must be held under DECK semantics, not demoted to the"
                + " outer-hull mode that pins it to the cavity's world floor: " + modesAfter.raw(),
                !modesAfter.hullStand);
        // Since the TELEPORT, not since a re-claim that no longer happens: the window this leg cares
        // about is "did anything let go of him while he was being moved about inside the hull", and
        // the honest answer is that there should be nothing in it at all.
        String releasesAfter = clientEvents.since(releaseMark, "deck_released");
        boolean shipCam = Boolean.parseBoolean(deckCameraText("active"));
        double settledY = bot().reportState().get("playerY").getAsDouble();
        DeckCapture capEnd = DeckCapture.read(this::exec);
        System.out.println("[interior] shipCamActive=" + shipCam + " preY=" + preY + " settledY="
                + settledY + " reclaim=" + reclaimed + " modes=" + modesAfter.raw()
                + " releasesSinceReclaim=" + releasesAfter
                + " censusEnd(server)=" + exec("artest vs subspace-census")
                + " :: " + trace);

        // The interior-boarding contract: the deck reclaims the released body - it is carried
        // back by SHIP-frame gravity (never lost through the world-down cockpit opening to the
        // world below, never pinned by the outer-hull fallback), stays resolved ABOARD at its
        // deck spot, and the client's ship camera engages.
        // Read at the END of the settle, off the live capture, and NOT off the mode edge: the
        // episode is unbroken through this whole leg, so nothing transitions and no mode record is
        // written. `hullStand` false IS "held with deck semantics"; it is production's own
        // distinction, asked of the state rather than of a history of changes to it.
        DeckCapture modesSettled = deckCaptureOfThisShip(scenarioShipId,
                "the settled body must still be held by THIS craft");
        assertTrue("a body moved about inside the ship must stay held with DECK semantics, not with"
                + " world semantics on the outer hull (" + modesSettled.raw() + ", releases in this"
                + " window: " + releasesAfter + "): " + trace,
                !modesSettled.hullStand);
        assertTrue("the body must stay WITH the inverted ship at its deck spot, not fall out "
                + "(preY=" + preY + " settledY=" + settledY + ", cap=" + capEnd.raw() + "): " + trace,
                Math.abs(settledY - preY) < STAYED_WITH_THE_SHIP_BLOCKS && capEnd.alreadyTracked);
        // ...and by THIS ship. "He is held" and "he is held by the craft this scenario built"
        // are different claims, and on a world three scenarios share only the second one is the
        // contract. The id is in the reply already.
        capEnd.requireAnchoredOn( scenarioShipId,
                "the body must stay with the INVERTED ship it was released inside");
        assertTrue("the client camera must engage for the re-seated interior body "
                + "(shipCamActive=" + shipCam + ")", shipCam);
    }

    // ---- Enclosed cavity: the interior gate claims an UNSUPPORTED roofed body with a deck below -

    @Test
    public void aBodyLostMidCavityOfAnEnclosedInvertedShipIsReclaimedByTheDeck() throws Exception {
        final FixtureSite site = site();
        final int bx = site.x, by = site.y, bz = site.z;

        // The open-topped cockpit above cannot exercise interior boarding: since the enclosure
        // term, its re-seat path is supported first contact (the body is pressed against the
        // deck), and the interior gate never fires there. This test's subject is the gate's own
        // claim: a body UNSUPPORTED mid-cavity of an ENCLOSED cockpit - deck below AND roof
        // above in the ship frame - with world gravity pulling it AWAY from the deck (the ship
        // is inverted). Pre-gate, that body belonged to world gravity: it fell onto the roof -
        // the cavity's world-floor - and the outer-hull fallback pinned it there with a world
        // camera, the reported "captured, but the camera never flips" desync. The contract: the
        // deck reclaims it without standing support and carries it back AGAINST world gravity.
        buildAndBoardShip(site, "with-roofed-deck");
        double h = Math.toRadians(170.0) / 2.0;
        double upBeforeInversion = ShipInfo.byId(this::exec, 0, scenarioShipId).upY();
        assertTrue("attitude hold must accept the inversion",
                Reply.of(exec("artest vs point-by-id 0 " + scenarioShipId + " "
                        + Math.cos(h) + " " + Math.sin(h) + " 0.0 0.0")).bool("commanded"));
        // A WINDOW, then the attitude READ — the pair that replaces a fixed 200 CLIENT ticks and the
        // re-stepping loop further down that existed to survive them. What the READ buys is the
        // whole point: a hull short of its inversion now fails HERE, as an arrangement failure that
        // names its up axis, where the loop re-teleported the body until the geometry happened to
        // work out and so hid the short slew entirely.
        //
        // WHAT THE WINDOW DOES NOT BUY, and an earlier version of this comment claimed it did: the
        // slew is not on any game clock. The hold's torque is applied on the physics mod's own
        // thread, which steps a FIXED simulated interval per physics tick and paces those ticks by
        // wall clock at its target rate (`VSWorldPhysicsLoop.run`). Server ticks are the clock the
        // hull's WORLD advances on, which is why they replace the client's; they are not the clock
        // the SLEW advances on, so on a starved box this window can still end short — and then the
        // read below says so, loudly and typed. A link on the hull reaching its commanded attitude
        // would remove that too; nothing publishes one yet.
        // WINDOW: upBeforeInversion -> the read below, both named by the gate.
        GameTicks.advanceWorld(serverClient(), 0, INVERSION_SLEW_TICKS);
        ShipInfo inverted = ShipInfo.byId(this::exec, 0, scenarioShipId);
        // WHY -sqrt(1/2): the step below moves the body world-DOWN, and it only reaches the cavity
        // if world-down is MOSTLY ship-up — the component along the hull's up axis larger than the
        // one across the deck plane, i.e. past 135 degrees. That is where "a half-turned attitude
        // maps the step into the deck plane" stops being possible. The command asks for 170.
        scenario().requireArranged("the hull must be well into its inversion before the body is displaced,"
                        + " or a world-down step lands in the deck plane rather than the cavity:"
                        + " upY " + upBeforeInversion + " -> " + inverted.upY() + " over "
                        + INVERSION_SLEW_TICKS + " server ticks; " + inverted.raw(),
                inverted.upY() < -Math.sqrt(0.5));

        // Same arrangement chain as the open-cockpit scenario: `dismount` then `deck_entered`, and
        // the MODE off production's own commit.
        Events events = events();
        long dismountMark = events.markInstrumented();
        exec("artest player dismount");
        requireChain(events, dismountMark, "the dismounted pilot must be taken by the deck inside the"
                + " inverted roofed ship", "dismount", "deck_entered");
        String modesBefore = awaitCommittedMode(events, dismountMark, "aboard",
                "the dismounted pilot must be captured ABOARD inside the inverted ship");
        double preY = bot().reportState().get("playerY").getAsDouble();
        double[] sub0 = parseSub(censusField("subPos"));

        // Fixture enclosure by measurement: the ROOF must have entered the assembled ship - the
        // subspace block region reaches at least four blocks above the stand (roofless deck
        // variant: one). An open-topped build here would silently turn this test into the
        // supported-first-contact one above.
        String regionStr = censusField("region");
        double regionMaxY = parseRegionMaxY(regionStr);
        System.out.println("[cavity] stand sub0=" + censusField("subPos")
                + " region=" + regionStr + " regionNonAir=" + censusField("regionNonAir")
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
        // ONE displacement, and the proof that it happened is the STIMULUS, not a position read
        // after it. Both halves of that sentence were learned on 2026-09-23.
        //
        // The loop that stood here re-stepped the body up to eight times and read its subspace
        // position two ticks after each step, until one read caught it more than half a block off
        // the deck. That read RACES THE SUBJECT: since the capture became a state held across a
        // move inside the hull, the deck keeps the body and re-images its stand every tick, so two
        // ticks after the step the body can already be back where it stands. Measured on the
        // wave's boundary gate: a single step from a hull at `upY = -0.98` — fully inverted — read
        // `subY = 128` against a stand of 128. The loop did not survive a short slew, as an earlier
        // note here claimed; it re-rolled that race until it lost it.
        //
        // So the displacement is proved by its two measured premises instead. The CLIENT applied the
        // teleport — `client_pos_look_applied` at the world Y the step aims for — and the hull was
        // measured past 135 degrees above, where `|upY| > sqrt(1/2)`. A 1.2-block world-down step on
        // such a hull moves the body more than 1.2 * sqrt(1/2) = 0.85 blocks along the SHIP's up
        // axis, into the cavity: more than the half block the old read asked for, at the instant the
        // step lands, whatever the deck does a tick later — which is the subject below.
        Events clientEvents = clientEvents();
        long releaseMark = clientEvents.mark();
        exec("tp @a ~ ~-1.2 ~");
        String applied = clientEvents.await(releaseMark, "client_pos_look_applied",
                "the displacement must land on the client", DECK_LINK_BUDGET_TICKS);
        double appliedY = Events.number(Events.lastRecord(applied), "y");
        scenario().requireArranged("the displacement must put the client 1.2 below where he stood:"
                        + " applied y=" + appliedY + " against " + (preY - 1.2) + " (stood at "
                        + preY + ")",
                Math.abs(appliedY - (preY - 1.2)) < 0.5);
        String subAfter = censusField("subPos");

        // Still INSIDE the ship's block region — true whether the deck has already taken him back
        // to his stand or not, so this read does not race anything.
        assertTrue("the displaced body must remain INSIDE the ship's block region (sub="
                + subAfter + " region=" + regionStr + ")", subInRegion(subAfter, regionStr));

        // ARRANGEMENT, and it is the OPPOSITE of what it was until 2026-09-16 — see the sibling
        // scenario above for the full reasoning. This used to require that the displacement DROPPED
        // the client's capture, through the external-move guard, so that a re-claim could be
        // measured. That guard is gone: a capture is a state entered and left by edges, and a body
        // moved WITHIN the craft holding it has left nothing. So the displacement must now leave the
        // episode intact, and "the body is still this craft's" is the stronger claim anyway —
        // a re-claim is only observable after a drop the player should never have felt.
        String releases = clientEvents.since(releaseMark, "deck_released");
        scenario().requireArranged("a body displaced INSIDE the cavity of the craft that holds it"
                + " must not be released at all: " + releases,
                Events.records(releases).isEmpty());

        // THE SUBJECT: the displaced body comes back under DECK semantics - carried by ship-frame
        // gravity against world gravity, at its deck stand, with the ship camera - instead of being
        // pinned to the cavity's world-floor by the outer-hull fallback (the reported "captured, but
        // the camera never flips" desync).
        //
        // NOT the `interior_claimed` record, and that was true before this change for a reason that
        // is now simply stronger: `interiorCandidate` is consulted only for a body the gate finds
        // UNTRACKED, and a teleport inside the hull never produces one. It used to be that the
        // guard released and the same tick's commit re-captured, so the untracked window was one
        // tick wide; now there is no release at all and the window does not exist. Either way the
        // CLAIM gate is unreachable from this stimulus and awaiting it fails a healthy client.
        //
        // A run that wants the claim gate itself has to arrange a body that is untracked INSIDE the
        // hull for at least one gate call: an entry through a hatch, a relog inside the cavity, or a
        // flight-off, none of which is a teleport.
        //
        // The wait for a re-capture that stood here is gone with the drop it depended on. What it
        // defended — the body ends up under DECK semantics in the cavity rather than pinned to the
        // world floor by the outer-hull fallback — is what the mode commit and the settle below
        // discriminate, and neither needs an edge.
        String reclaimed = "no re-capture: the episode was never broken (see the arrangement above)";

        StringBuilder trace = new StringBuilder();
        // Sample the settle: where does the claimed body come to rest?
        // WINDOW: for the same reason as its sibling above — rest is approached, not announced, and
        // this trace is what a red reads. What it cannot see: motion inside one 3-tick sample.
        for (int i = 0; i < 30; i++) {
            bot().waitTicks(3);
            trace.append(String.format(java.util.Locale.ROOT,
                    "[t%d y=%.2f cSub=%s] ",
                    i * 3, bot().reportState().get("playerY").getAsDouble(),
                    censusField("subPos")));
        }
        // From the RELEASE mark for the same reason as the open-cockpit scenario above: the mode is
        // an EDGE written where a capture is installed or its mode changes, and the one commit of
        // this episode lands in the same tick as the release - before a mark taken at the re-claim
        // could open. The window from the release holds it, and everything in that window is
        // post-displacement.
        // A live STATE read, not a wait on the mode edge — see the sibling scenario above for why
        // that edge no longer fires: the episode is never broken, so the mode never transitions.
        DeckCapture modesAfter = deckCaptureOfThisShip(scenarioShipId,
                "the displaced cavity body must still be held by THIS craft");
        assertTrue("the displaced body must be held under DECK semantics, not demoted to the"
                + " outer-hull mode that pins it to the cavity's world floor: " + modesAfter.raw(),
                !modesAfter.hullStand);
        boolean shipCam = Boolean.parseBoolean(deckCameraText("active"));
        double settledY = bot().reportState().get("playerY").getAsDouble();
        double[] subEnd = parseSub(censusField("subPos"));
        DeckCapture capEnd = DeckCapture.read(this::exec);
        // Every release in the displacement window, each with the gate that performed it: a
        // `noDeckBelow` or `noHullContact` here would say the enclosure term did NOT hold the body,
        // which is the failure this scenario is about and is not visible in a height alone.
        String releasesAfter = clientEvents.since(releaseMark, "deck_released");
        System.out.println("[cavity] shipCamActive=" + shipCam + " preY=" + preY + " settledY="
                + settledY + " subEnd=" + subEnd[1] + " release=" + releases
                + " reclaim=" + reclaimed + " modes=" + modesAfter.raw()
                + " releasesInWindow=" + releasesAfter + " :: " + trace);

        // The interior-boarding contract, positive half: the ENCLOSED unsupported body is the
        // deck's - claimed ABOARD (not pinned by the outer-hull fallback on the cavity's
        // world-floor), carried back against world gravity to its deck stand, ship camera on.
        // The live state at the end of the settle, for the reason given at the sibling scenario:
        // the episode never breaks here, so the mode edge never fires and a history of changes is
        // the wrong instrument for a claim about where the body ENDED.
        DeckCapture modesSettled = deckCaptureOfThisShip(scenarioShipId,
                "the settled cavity body must still be held by THIS craft");
        assertTrue("an unsupported body in an enclosed cavity must be held with DECK semantics, not"
                + " pinned by the outer-hull fallback (" + modesSettled.raw() + ") | the releases in this"
                + " window, each with the gate that performed it: " + releasesAfter
                + " | server verdict " + capEnd.raw() + " :: " + trace,
                !modesSettled.hullStand);
        assertTrue("deck gravity must carry the body BACK to the deck, not let it settle on the "
                + "roof ~3 world blocks below (preY=" + preY + " settledY=" + settledY + "): " + trace,
                Math.abs(settledY - preY) < CARRIED_BACK_TO_DECK_BLOCKS && capEnd.alreadyTracked);
        capEnd.requireAnchoredOn( scenarioShipId,
                "deck gravity must carry the body back to THIS ship's deck");
        assertTrue("the body must re-seat at its deck stand in subspace (subY " + subEnd[1]
                + " vs stand " + sub0[1] + "; seat-top landing allowed): " + trace,
                Math.abs(subEnd[1] - sub0[1]) <= RESEATED_AT_HIS_STAND_BLOCKS);
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
        final FixtureSite site = site();
        final int bx = site.x, by = site.y, bz = site.z;

        // The flying-aboard contract on a steeply ROLLED ship: starting creative flight on the
        // deck keeps the body the deck's (no release, ship camera stays), the vertical fly
        // intent ascends along the DECK NORMAL - measured in SUBSPACE, where deck-up is plain +Y
        // regardless of the roll; a world-up ascent would instead leak most of its motion into
        // the subspace deck PLANE (at 60 deg: cos60 = 0.5 up, sin60 = 0.87 sideways) - and
        // turning flight off hands the body to deck gravity, which seats it back on the deck.
        //
        // Flight needs creative, and creative is what the shared base restores before every
        // scenario (AbstractSharedClientE2ETest.resetBetweenScenarios). Not re-issued here: a
        // `gamemode` resends the abilities with flight OFF, and one landing after the double-tap
        // below would switch the flight it starts back off.
        buildAndBoardShip(site);
        double h = Math.toRadians(60.0) / 2.0;
        double upBefore = ShipInfo.byId(this::exec, 0, scenarioShipId).upY();
        assertTrue("attitude hold must accept the roll",
                Reply.of(exec("artest vs point-by-id 0 " + scenarioShipId + " "
                        + Math.cos(h) + " " + Math.sin(h) + " 0.0 0.0")).bool("commanded"));
        // WINDOW: the slew, counted on the hull's world clock, with both ends in the gate. The gate's
        // angle is not a choice: the ascent bounds below (ASCENT_ALONG_NORMAL_BLOCKS 1.2 along the
        // normal, ASCENT_LATERAL_BLOCKS 1.6 across it) tell a deck-normal ascent from a world-up one
        // only past tan(theta) = 1.6 / 1.2, i.e. 53.1 degrees — below that a world-up climb can pass
        // both. So the hull must be past it, with a degree of margin inside the commanded 60.
        GameTicks.advanceWorld(serverClient(), 0, ROLL_SLEW_TICKS);
        double upAfter = ShipInfo.byId(this::exec, 0, scenarioShipId).upY();
        scenario().requireArranged("the hull must be rolled past the angle the ascent bounds can"
                        + " discriminate at (53.1 degrees): upY " + upBefore + " -> " + upAfter
                        + " over " + ROLL_SLEW_TICKS + " server ticks",
                upAfter < Math.cos(Math.toRadians(54.0)));

        // Same arrangement chain as the two interior scenarios.
        Events events = events();
        long dismountMark = events.markInstrumented();
        exec("artest player dismount");
        requireChain(events, dismountMark, "the dismounted pilot must be taken by the rolled deck",
                "dismount", "deck_entered");
        String modesBefore = awaitCommittedMode(events, dismountMark, "aboard",
                "the dismounted pilot must be captured ABOARD on the rolled deck");
        double[] sub0 = parseSub(censusField("subPos"));

        // Start creative flight: double-tap space (the first tap is a deck jump; the second,
        // within the toggle window, flips flight). Marked FIRST: "flight must not release the
        // capture" is an ABSENCE, and an absence is only an answer when it is read from a log whose
        // window opened before the stimulus.
        Events clientEvents = clientEvents();
        long flightMark = clientEvents.mark();
        bot().holdKey(org.lwjgl.input.Keyboard.KEY_SPACE);
        bot().waitTicks(2); // STIMULUS: the first tap's press
        bot().releaseKey(org.lwjgl.input.Keyboard.KEY_SPACE);
        bot().waitTicks(2); // STIMULUS: the gap between taps, inside vanilla's toggle window
        bot().holdKey(org.lwjgl.input.Keyboard.KEY_SPACE);
        bot().waitTicks(2); // STIMULUS: the second tap's press, the one that toggles flight
        bot().releaseKey(org.lwjgl.input.Keyboard.KEY_SPACE);
        // EXPERIMENT: the held-ascend phase is defined to begin four client ticks after the second
        // tap's release. A player's own motion is simulated by his client, one step per client
        // tick, so this is four steps of the tap's residual climb on any box — not a wait for it.
        bot().waitTicks(4);

        // The double-tap itself climbs a few blocks (a deck jump + held-space flight ticks), so
        // re-baseline AFTER flight is on: the pin measures the held-ascend phase alone. The hold
        // is SHORT deliberately - the stay region ends ~4 blocks above the hull top, and a climb
        // that exits it is a legitimate release (leaving the region ends the capture), not this
        // pin's subject.
        double[] subFly = parseSub(censusField("subPos"));
        StringBuilder trace = new StringBuilder();
        int trackedSeen = 0, camSeen = 0, samples = 0;
        double[] subEnd = subFly;
        // A fixed DOSE of held ascend, sampled as it goes. It used to climb "to a target rise" with
        // an early exit at +2, on the belief that the climb rate varies run to run; it is the
        // player's own flight, stepped once per CLIENT tick by his client, and the census that
        // measures it is taken by that same client on those same ticks — so a count of client ticks
        // is a count of flight on any box. The stay region's edge is ~4 blocks above the hull top,
        // and the dose stops well short of it.
        bot().holdKey(org.lwjgl.input.Keyboard.KEY_SPACE);
        try {
            // EXPERIMENT: FLY_ABOARD_DOSE_SAMPLES readings two client ticks apart with ascend held —
            // four ticks of flight, the stretch the 2026-09-23 gate measured reaching +2 (subFly=129.0
            // dySub=2.0 after two samples). Every reading also carries the per-sample tracked/cam
            // invariants the assertions below count.
            for (int i = 0; i < FLY_ABOARD_DOSE_SAMPLES; i++) {
                bot().waitTicks(2);
                samples++;
                DeckCapture cap = DeckCapture.read(this::exec);
                // Anchored on THIS scenario's craft, per sample: the count below is a claim about
                // one body keeping one ship's interior frame through the climb, and a sample taken
                // against another hull in the same airspace is not evidence for it.
                boolean tracked = cap.alreadyTracked
                        && !cap.hullStand
                        && cap.anchoredOn(scenarioShipId);
                if (tracked) trackedSeen++;
                if (Boolean.parseBoolean(deckCameraText("active"))) {
                    camSeen++;
                }
                subEnd = parseSub(censusField("subPos"));
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
                Events.countRecordsWithField(releasesInFlight, "reason") == 0);
        assertTrue("starting flight on the deck must NOT release the capture (tracked "
                + trackedSeen + "/" + samples + "): " + trace, trackedSeen == samples);
        assertTrue("the ship camera must stay engaged for a flying-aboard body (cam " + camSeen
                + "/" + samples + ")", camSeen == samples);
        // The census position is block-floored, so allow a block of lateral jitter; a WORLD-up
        // ascent at 60 deg would drift the deck plane by ~1.7x the climb (several blocks here).
        assertTrue("holding ascend must climb along the DECK NORMAL (subspace +Y): dySub=" + dySub
                + " dxzSub=" + dxzSub + " :: " + trace, dySub > ASCENT_ALONG_NORMAL_BLOCKS && dxzSub < ASCENT_LATERAL_BLOCKS);

        // Descend back toward the deck first - the flight-off double-tap itself adds a little
        // climb, and toggling at the stay region's edge exits it mid-flight (leaving the stay
        // region is a legitimate release, but then WORLD gravity owns the fall and the reseat
        // below is not this contract's). The descend leg also pins the OTHER vertical intent:
        // sneak sinks along the deck normal exactly as space climbs it.
        double[] subHigh = subEnd;
        // A dose for the other vertical intent. A client that is starved of FRAMES still steps his
        // own motion once per client TICK, so a tick count does not under-sink on a busy box — which
        // is what the early-exit poll that stood here was written against. Census-Y is
        // block-floored, so the assertion is a strict drop below the captured start height, and that
        // needs MORE than a block of travel.
        bot().holdKey(org.lwjgl.input.Keyboard.KEY_LSHIFT);
        try {
            // EXPERIMENT: FLY_ABOARD_DESCEND_TICKS client ticks of held descend. NOT measured, and
            // not the ascent's four: vanilla creative flight from rest covers about 1.0 block in
            // four ticks of sneak (0.15/tick added, 0.6 kept), which a floored census can fail to
            // register, and about 3.2 in ten. The deck below stops a longer fall; the old poll's
            // ceiling was fourteen. The sink it bought is printed in the assertion.
            bot().waitTicks(FLY_ABOARD_DESCEND_TICKS);
        } finally {
            bot().releaseKey(org.lwjgl.input.Keyboard.KEY_LSHIFT);
        }
        double[] subDown = parseSub(censusField("subPos"));
        assertTrue("holding descend must sink along the DECK NORMAL (subspace -Y): "
                + subHigh[1] + " -> " + subDown[1], subDown[1] < subHigh[1]);

        // Flight off: double-tap again; deck gravity reclaims the airborne body and seats it.
        // ONE double-tap. It used to be retried up to four times while the capture still read
        // "flying", on the argument that suite load stretches the two taps past vanilla's double-tap
        // window — but the taps are four CLIENT ticks apart and the window is seven client ticks, so
        // load cannot stretch one past the other, and the argument was never measured. A tap that
        // did not register now fails the landing link below, naming it.
        DeckCapture capEnd = null;
        double[] subSeated = subEnd;
        long flightOffMark = clientEvents.mark();
        // STIMULUS: the double-tap itself, two ticks down, two up, two down.
        bot().holdKey(org.lwjgl.input.Keyboard.KEY_SPACE);
        // STIMULUS: the same double-tap.
        bot().waitTicks(2);
        bot().releaseKey(org.lwjgl.input.Keyboard.KEY_SPACE);
        // STIMULUS: the same double-tap.
        bot().waitTicks(2);
        bot().holdKey(org.lwjgl.input.Keyboard.KEY_SPACE);
        // STIMULUS: the same double-tap.
        bot().waitTicks(2);
        bot().releaseKey(org.lwjgl.input.Keyboard.KEY_SPACE);
        // THE LANDING IS A LINK, and it was already being asserted twenty lines below as one —
        // `deck_contact`, the tick the deck resolver put the body on a surface it was not on before.
        // So it is waited for HERE, off the mark taken before the flight toggle, and the geometry
        // below is then read as the settled state it is. What stood here was a 40-step poll of four
        // probe readings whose exit condition (`seated`) was re-asserted verbatim afterwards — so
        // that assertion could only ever fail by the poll running out, and its message blamed deck
        // gravity for a budget.
        // Narrowed to THIS craft. The needle that stood here asked only that the record carry a
        // `ship` field at all, which every `deck_contact` does — including one written for a body
        // landing on a neighbour's hull, and including the literal "hull" the recorder writes for a
        // craft with no durable name. Naming the ship is what makes this a wait for this scenario.
        String landing = clientEvents.awaitField(flightOffMark, "deck_contact", "ship", scenarioShipId,
                "turning flight off must hand the body to deck gravity and put it in CONTACT with"
                        + " the ship's geometry — a body merely hovering at the right height was"
                        + " never seated by anything", DECK_LINK_BUDGET_TICKS);
        // WINDOW: from the first contact (the `landing` record, printed below) to where the body is
        // ten ticks on (capEnd and subSeated, also printed); the assertion holds the contact on THIS
        // ship at one end and the seat on its deck at the other. A body that touched and then slid
        // or bounced away is what the far end is for, and overshoot only gives it longer to do so.
        bot().waitTicks(10);
        capEnd = DeckCapture.read(this::exec);
        subSeated = parseSub(censusField("subPos"));
        // The descend leg parks the body over the SEAT column, so deck gravity may seat it on
        // the seat block's top - one block above the deck stand. Either landing is "seated on
        // the ship's geometry at the deck spot"; only staying airborne (or lost to the world)
        // fails.
        // `subSeated` is a subspace coordinate of the ANCHOR ship and `sub0` was taken in this
        // scenario's own — so without the anchor the height comparison below is between two
        // frames rather than two moments, and it is that comparison, not the flag, that decides
        // "he came back down onto the deck".
        boolean seated = capEnd.alreadyTracked
                && !capEnd.hullStand
                && capEnd.anchoredOn(scenarioShipId)
                && subSeated[1] <= sub0[1] + 1.4;
        exec("gamemode survival @a"); // leave the shared world as the other tests expect it
        // WHERE it came to rest. The CONTACT is the link above; this is the geometry, and the two
        // are different claims: a body can touch the hull and be held there by the outer-hull
        // fallback (`hullStand`), or touch a neighbour's craft (hence the anchor check), or come
        // down a metre high on the seat block. None of that is visible in the contact record.
        assertTrue("turning flight off must seat the body back on THIS ship's deck geometry "
                + "(sub=" + subSeated[1] + " vs start " + sub0[1] + ", landing=" + landing + "): "
                + (capEnd == null ? "the capture was never read" : capEnd.raw()), seated);
    }

    /** "x,y,z" census position as doubles (block coords are integral; that is fine here). */
    private static double[] parseSub(String sub) {
        String[] s = sub.split(",");
        return new double[]{Double.parseDouble(s[0].trim()), Double.parseDouble(s[1].trim()),
                Double.parseDouble(s[2].trim())};
    }

    // ---- helpers (self-contained, mirroring the other tier-2 e2e classes) ----------------------

    /** Build the ship and sit the bot on its pilot seat; returns the ship's world position. */
    private double[] buildAndBoardShip(FixtureSite site) throws Exception {
        return buildAndBoardShip(site, VARIANT);
    }

    private int readIntFrom(String json, String field) {
        Reply reply = Reply.of(json);
        assertTrue("expected an int `" + field + "` in: " + json, reply.has(field));
        return reply.integer(field);
    }

    private double[] buildAndBoardShip(FixtureSite site, String variant) throws Exception {
        // The site owns the coordinates; these aliases keep the body below unchanged.
        final int bx = site.x, by = site.y, bz = site.z;
        double[] ship = buildShip(site, variant);
        // The seat is located INSIDE this scenario's own ship: `vs seat-mount <dim>` takes the first
        // pilot seat in the world's loaded-tile list with no position filter, which is unambiguous
        // only while the world holds one ship, and mounts a neighbour's once scenarios share one.
        // By identity. The positional form resolves the yard through the ship NEAREST the base — no
        // containment test and no distance bound — so on this shared world it can seat the bot on a
        // neighbour's craft and say seatFound:true doing it.
        PilotSeat seat = PilotSeat.byId(this::exec, 0, scenarioShipId)
                .requireFound("find-seat must locate the pilot seat inside THIS scenario's ship ("
                        + scenarioShipId + ", built at " + bx + "," + by + "," + bz + ")");
        String mountInfo = exec("artest vs seat-mount-at 0 " + seat.seatX + " "
                + seat.seatY + " " + seat.seatZ);
        int dummyId = Reply.of("artest vs seat-mount-at", mountInfo).integer(DUMMY_ID);
        // The CLIENT's mark before the mount, because the reply above is the SERVER's receipt and
        // every caller of this helper goes on to drive the bot as a seated pilot.
        long seatClientMark = clientEvents().mark();
        assertTrue("bot must mount the seat dummy: " + mountInfo,
                Reply.of(exec("artest player mount-entity " + dummyId)).bool("mounted"));
        awaitClientMount(seatClientMark, "the bot must be seated as HIS OWN CLIENT renders him"
                + " before this helper hands the ship back — the server reporting a mount is the"
                + " other process", DECK_LINK_BUDGET_TICKS, " mountInfo=" + mountInfo);
        return ship;
    }

    private double[] buildShip(FixtureSite site, String variant) throws Exception {
        // The site owns the coordinates; these aliases keep the body below unchanged.
        final int bx = site.x, by = site.y, bz = site.z;
        long awayMark = clientEvents().mark();
        exec("tp @a " + (bx + 600) + " 120 " + (bz + 600) + " 0 0");
        awaitClientPlacedNear(awayMark, bx + 600, bz + 600,
                "the assembly below must run with no observer near it, and the observer is a client");

        // The mark is taken BEFORE the assembly is queued, so the record awaited below is THIS
        // scenario's own ship and never a neighbour's - which is what a whole-dimension COUNT could
        // never be on a shared world.
        Events events = events();
        long spawnMark = events.markInstrumented();
        exec("artest vs spawn-diag reset");
        String assemble = assembleFixture(site, variant);
        assertTrue("a with-pilot-seat build must route to a ship: " + assemble,
                (Reply.of(assemble).integer("rocketCount") == 0));

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

        long approachMark = clientEvents().mark();
        exec("tp @a " + (bx + 0.5) + " " + (by + 6) + " " + (bz + 0.5) + " 0 0");
        awaitClientPlacedNear(approachMark, bx + 0.5, bz + 0.5,
                "the client's ARRIVAL is what pulls the ship's chunks, so what is asked of the"
                        + " ship below is only answerable because a client got here");

        // An ARRANGEMENT gate, and its own argument stands: `ship_spawned` says the REGISTRY knows
        // the ship, which is a different fact from the craft being live here — the state everything
        // below needs. What that argument was aimed at was `ship_spawned`; the fact itself HAS an
        // event, and it is a stronger one than the poll it replaces.
        //
        // `ship_usable` is published on the tick a craft becomes ready to be FLOWN (physics-ready,
        // surrounding chunks cached), where the `managed:true` polled here is true as soon as a
        // PhysicsObject for the id exists. This scenario then releases a body inside the hull and
        // reads where the deck resolver puts it, so a craft that is loaded and not yet stepping is
        // not the state it needs. The mark is `spawnMark`, taken before the assembly: readiness is an
        // EDGE that fires once, so a mark taken here could miss it entirely.
        awaitShipUsable(events, spawnMark, scenarioShipId);
        ShipInfo info = ShipInfo.byId(this::exec, 0, scenarioShipId);
        double[] where = new double[]{info.x, info.y, info.z};

        // Fixture completeness by measurement: how many blocks did the assembled ship actually
        // get (region census + the ship's own blockPositions count + iron in the grown
        // neighbourhood)? The census probe resolves the ship by containment of the SERVER player,
        // so stand the bot INSIDE the craft's world box for the reading; `tp` has moved the server
        // player before it replies, so nothing is waited for.
        //
        // ONE sample. There used to be two, a second apart, to tell a relocation still in progress
        // from a settled short count — but the substrate registers a ship only after injecting its
        // blocks, in the same tick (`WorldServerShipManager.spawnNewShips`), so by the
        // `ship_spawned` awaited above there is no relocation left to be in progress.
        exec("tp @a " + (bx + 3.5) + " " + (by + 6) + " " + (bz + 3.5) + " 0 0");
        String census1 = exec("artest vs subspace-census");
        // The deck is built at (rocketX+-2, rocketY+3, rocketZ+-2) with rocket=(base+3,base+1,base+3),
        // i.e. world (bx+1..bx+5, by+4, bz+1..bz+5) before assembly relocates it into the ship.
        String leftover = exec("testforblock " + (bx + 3) + " " + (by + 4) + " " + (bz + 3)
                + " minecraft:iron_block")
                + " | " + exec("testforblock " + (bx + 5) + " " + (by + 4) + " " + (bz + 5)
                + " minecraft:iron_block");
        System.out.println("[interior] census postBuild=" + census1);
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

    private String assembleFixture(FixtureSite site, String variant) throws Exception {
        // The site owns the coordinates; these aliases keep the body below unchanged.
        final int baseX = site.x, baseY = site.y, baseZ = site.z;
        // THE FOREST THIS USED TO FELL IS NOT HERE ANY MORE, and the history is worth keeping
        // because it is the sharpest instance of what the band buys. The site (6820,6820) is
        // FORESTED at ground level: VS's flood detector treats leaves and logs as floodable (they
        // are not in shipSpawnDetectorBlacklist), so when the tier-2 assembly flood escaped the
        // craft it took the surrounding canopy with it, hit the 15001-block cap and aborted with
        // "Ship too big" — no ship at all. The treatment was a 40x20x40 pre-clear in two stacked
        // fills (the fill verb caps a volume at 32768), sized to the flood's measured bbox, felling
        // the wood before every assembly. None of that is a property of the subject; all of it is a
        // property of standing in a forest.
        //
        // In the open-air band the flood escapes into air, which it cannot take. The halo stays
        // WIDE (18) all the same, because what it now asserts is that nothing of anyone else's is
        // standing in the volume this flood can reach — the same question, asked instead of dug.
        //
        // HEIGHT 18, not the old 40. The 40 was sized to a forest canopy reaching y≈90 over a base
        // at 64; there is no canopy here. 18 is this class's own envelope: the tallest variant it
        // builds is with-roofed-deck, whose roof sits at base+9 on a tower raised to take it, and a
        // body is released in the cavity under that roof. It also has to fit: `artest fill` caps a
        // volume at 32768 and a 42x42 footprint leaves room for 18 layers (31752), so a taller box
        // here would be refused by the probe rather than by a reviewer.
        return RocketFixture.assembleAt(site, this::exec, variant, 18, 18,
                "the hull, the cockpit cavity a body is released inside, and the whole volume the"
                        + " assembly flood can escape into");
    }

    /** This scenario's ship, asked by identity — no distance term to be wrong about. */
    private String shipInfo() throws Exception {
        assertTrue("shipInfo() before buildShip() captured an identity", scenarioShipId != null);
        return shipInfoById(scenarioShipId);
    }

    /** One CLIENT-side subspace-census static (ShipFrameTravel.census*), as a plain string. */
    /**
     * One field of the CLIENT's most recent subspace census, as text.
     *
     * <p>Read from the census RECORD, not from the nine statics production used to refresh every
     * client tick. The statics were one set per JVM, so on a shared client every scenario wrote over
     * every other's, and this class's central question — "does this side's world hold the ship's
     * blocks where the body is standing?" — could be answered about somebody else's body without
     * looking any different.</p>
     *
     * <p>The mark ROLLS: each call reads only what was recorded since the last one and keeps the
     * newest, so the twenty-odd reads below cost one small reply each instead of the whole ring.
     * Returns "" until the client has taken a census at all, which is what a body near no ship
     * produces — an absence, not a zero.</p>
     */
    private long censusMark = 0L;
    private String latestCensus = null;

    private String censusField(String field) throws Exception {
        for (String record : Events.records(clientEvents().since(censusMark, "subspace_census"))) {
            latestCensus = record;
            censusMark = (long) Events.number(record, "seq") + 1L;
        }
        if (latestCensus == null) {
            return "";
        }
        // One accessor for all three shapes the census carries — quoted text, a JSON number and a
        // bare boolean — because the caller asks for a column, not for a type. The regex this
        // replaces spelled that alternation out and then had to strip the quotes back off; a parsed
        // primitive knows its own type.
        // absence is the answer, and WHICH answer is the CALLER's: this verb is handed a
        // FIELD name, so it cannot know what a missing one means — and the callers here
        // include waits, which read the shape that does not carry the field yet.
        return Reply.of("artest vs subspace-census", latestCensus).textOr(field, "");
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

    private double readDouble(String json, String field) {
        // absence is the answer, and WHICH answer is the CALLER's: this verb is handed a
        // FIELD name, so it cannot know what a missing one means — and the callers here
        // include waits, which read the shape that does not carry the field yet.
        double value = Reply.of(json).numberOr(field, Double.NaN);
        return value;
    }

    private static double distance(double[] a, double[] b) {
        double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
