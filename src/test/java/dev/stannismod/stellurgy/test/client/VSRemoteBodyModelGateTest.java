package dev.stannismod.stellurgy.test.client;


import org.junit.FixMethodOrder;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runners.MethodSorters;


import dev.stannismod.stellurgy.test.DeckCapture;
import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.RocketFixture;
import dev.stannismod.stellurgy.test.ShipInfo;

import dev.stannismod.stellurgy.test.Plot;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Which bodies the client draws standing on a ship, and which it leaves upright.
 *
 * <p>The local player's model/eye gate on the movement truth (resolved ABOARD a deck), but a
 * REMOTE body has no capture state on this side at all - the client resolves only its own
 * player's movement. The gate for everyone else therefore used to be world-AABB CONTAINMENT,
 * which is true across the whole air volume around the hull: a body standing on the ground beside
 * an inverted ship was drawn lying on its side. The contract under test is the spatial one - a
 * body is drawn ship-aligned when the SHIP CARRIES IT, not when it happens to be inside the
 * ship's box.
 *
 * <p>The observable is a client-side WINDOW ({@code remote_model_window}, accumulated by the test
 * side at {@code ShipFrameCamera.modelRotationFor} and recorded when the window closes): over that
 * window, how many model-rotation decisions were taken at all, how many concerned remote bodies, and
 * how many of those pushed a rotation. A per-frame decision for an arbitrary body is a transient - a
 * first/last-call snapshot would land on an arbitrary moment and say nothing - and a record per
 * decision would turn its own ring over in a second, which is why this is an accumulator with an
 * explicit open and close rather than an event chain.
 *
 * <p>The two legs are each other's control, and the pairing is what makes either meaningful:
 * leg A (body on terrain) asserts NO remote body is rotated; leg B (body on the deck) asserts the
 * same instrument DOES report a rotation for a carried body. Each leg proves the instrument fired
 * for ITS OWN subject via {@link #assertInstrumentFired} (samples &gt; 0) BEFORE trusting the
 * rotation count, so a zero can never pass either leg vacuously - leg A would otherwise pass just as
 * well if the gate rejected everything, or if the body were never rendered.
 *
 * <p>An earlier "leg 0" spawned a LONE cow on open ground as a separate control that the client
 * draws remote bodies at all. It was removed: each contract leg's {@code assertInstrumentFired}
 * already covers "the hook fired", and it covers the real gameplay case (a body near a ship) rather
 * than a no-ship scenario that never occurs in play. That lone-body leg was also the only flaky one
 * here - a lone subject on open ground was not reliably sampled when this class runs its methods in
 * one shared client after the ship-building legs (a teleport/render-settle race), while the
 * ship-anchored legs sample reliably.
 *
 *
 * <p><b>The "render-observability gap" this class carried for a month was this arrangement, twice
 * over.</b> Leg A's subject was reported as intermittently never DRAWN - 543 frames rendered, zero
 * {@code RenderLivingBase.applyRotations} - and that was blamed on the physics mod's handling of world
 * entities inside a ship's box, said to need GPU contention to appear. It reproduces at ONE fork, and
 * neither half of the story was true. Two ordinary staging faults produced it, and each was found only
 * once the diagnostic was made to report the link it was silent about:
 *
 * <ul>
 *   <li>The candidate sweep lays a floor under every spot it probes, walking one column upward, so a
 *       higher candidate's floor lands inside the body of the spot below it. The subject spawned in
 *       stone, took {@code IN_WALL} damage and was GONE from the server world by the end of the
 *       window - the "not drawn" body had stopped existing. The floors are gone altogether now: the
 *       leg stands its subject on the real ground, because a floor inside the hull's volume is also
 *       an obstacle the hull collides with.</li>
 *   <li>The camera is teleported to {@code subject + (8,3,8)}, and the fixture base sits inside a
 *       hill: feet and eye were both in dirt. Vanilla grows {@code RenderGlobal.renderInfos} out of
 *       the chunk section the camera occupies, so a buried camera never reaches the section holding
 *       the subject and draws no living model at all. Fixed by clearing the volume both ends live in.
 *       </li>
 * </ul>
 *
 * <p>With both fixed the subject draws on the FIRST staging, and the re-stage workaround built for
 * the fiction is gone: one staging, and it must draw. The lesson worth keeping is about the
 * instrument rather than the subject - "no living model was drawn" was read as a statement about
 * rendering while it was silent on whether the body still existed and on where the camera was.</p>
 *
 * <p>The arrival gate is now the client's own RECORD of the body joining its world, taken from a
 * mark older than the spawn, rather than a sample of what the client happens to be holding when it
 * is asked. That is the difference the month was spent on: a snapshot cannot tell "it never
 * arrived" from "it arrived and was gone again before I looked", and those are different bugs with
 * the same empty answer. The record survives the removal, and it rides along in the render
 * diagnostic so a red says which of the two happened.</p>
 *
 * <p>What is still POLLED, and why: the model-rotation decision itself has no per-occurrence event —
 * it is consulted once per drawn body per frame, which no 256-deep ring can carry — so the "is this
 * subject being drawn" precondition watches the open window's live sample count. What is no longer
 * true is that the numbers are differences against per-JVM totals: each measurement is a window that
 * this leg opened, so a forgotten subtraction can no longer read as a rich sample. The limit the
 * findings above name still stands, and it is a property of the SUBJECT rather than of the
 * instrument: {@code samples} counts ANY non-local living body the client drew, not this one.</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class VSRemoteBodyModelGateTest extends AbstractSharedVsClientTest {

    /**
     * How far the pushed rotation must reach, in degrees, to be the ship's REAL attitude rather
     * than a token tilt.
     *
     * <p>The TEST'S OWN: past ninety degrees the deck is beyond vertical, where a model drawn in
     * the world frame and one drawn in the ship's cannot be confused.</p>
     */
    private static final double REAL_ATTITUDE_DEG = 90.0;

    /**
     * The steep roll both legs need, as deck-normal Y. The TEST'S OWN arrangement fact: -0.85 is
     * about 150 degrees over.
     */
    private static final double STEEP_ROLL_UP_Y = -0.85;

    /**
     * How far the client's actual aim may sit from the commanded one, in degrees.
     *
     * <p>The TEST'S OWN: the aim is set and then read back, so this is the float round-trip of a
     * yaw through the client plus one tick of settle — not a budget for drift.</p>
     */
    private static final double AIMED_AT_THE_SUBJECT_DEG = 15.0;

    @Override
    protected String subsystem() {
        return "vs-remote-body-render";
    }

    private static final String BUILDER_POS = "builderPos";
    private static final String POS_X = "posX";
    private static final String POS_Y = "posY";
    private static final String POS_Z = "posZ";
    private static final String ENTITY_ID = "entityId";
    private static final String OBSTACLES = "shipSupportObstacles";
    private static final String Q_X = "qx";
    private static final String Q_Z = "qz";

    private static final String VARIANT = "with-pilot-deck";

    /**
     * THIS scenario's ship, by identity — captured by {@code buildShip} at the one moment its base
     * provably holds no other, and the address every later question and command uses. A radius bound
     * is a mitigation, not an identity: these scenarios roll, hover and drop the ship on purpose, and
     * a shared client always has a neighbour in candidacy.
     */
    private String scenarioShipId;
    /** The TEST-side accumulator behind every model-gate window — production keeps no counters. */
    private static final String REMOTE_MODEL_WINDOW =
            "dev.stannismod.stellurgy.test.trace.RemoteModelWindow";
    /** How long the subject may take to be DRAWN once it is on the client — the fifteen-tick reads
     *  eight times over that the poll it replaced was given. */
    private static final int FIRST_SAMPLE_BUDGET_TICKS = 120;

    /**
     * The CLIENT log sequence taken immediately BEFORE the current subject was spawned — the mark its
     * arrival on this side is read from. An instance field because the spawn and the arrival gate are
     * different methods, and the mark has to be older than the spawn to be worth anything.
     */
    private long subjectSpawnMark;

    /** How long the spawned subject is given to reach the client world. A packet, not a value. */
    private static final int SUBJECT_ARRIVAL_BUDGET_TICKS = 200;

    /** A roll steep enough that a wrongly-rotated model is unmistakable (~160 deg): at a shallow
     *  tilt the identity and the ship attitude are nearly the same rotation, so a level ship
     *  cannot falsify anything here. */
    private static final String STEEP_ROLL = "0.17365 0.0 0.0 0.98481";

    /**
     * How long the commanded ~160-degree roll is given to finish, in ticks.
     *
     * <p>The hold's slew rate is the fixture's own: it is capped at the rate this hull's actuators
     * can still stop from, so it is not a constant of the test. The slew advances per tick, so the
     * number says how far the craft turns rather than how long we are willing to wait. The reached
     * value is printed on every run, so the size can be re-argued from a measurement. Reaching the
     * attitude is not staying at it: a nudge after this window is recovered at the same finite
     * authority, which is why leg A re-reads its precondition when its window closes.</p>
     *
     * <p>Measured on the run that introduced this form, in both scenarios of the class:
     * <b>-0.9346</b> and <b>-0.9347</b> against a gate of {@code < -0.85}. Two readings agreeing to
     * three decimals are the signature of an attitude that has ARRIVED and is being held — a craft
     * still slewing would not land on the same number twice.</p>
     */
    private static final int ROLL_WINDOW_TICKS = 120;

    // ---- Leg A: the bug - a body the ship does NOT carry must not be drawn ship-aligned --------

    @Ignore("RED ON ITS ARRANGEMENT, DETERMINISTICALLY, and the rule it checks is being redefined."
            + " The ground search takes the column under the rolled hull, whose probe right after the"
            + " spawn reads 'not supported' and a few seconds later reads 'supported by the ship' on a"
            + " still hull, so the window's precondition lapses every run. Whether that body is on"
            + " the ship is no longer decided by standing support: it will be decided by the craft's"
            + " artificial-gravity field (a body is held where its feet are in the field). Lift this"
            + " when the remote-body model gate asks that predicate, and re-stage the subject inside the"
            + " hull's world box but outside its field.")
    @Test
    public void aBodyStandingOnTerrainBesideARolledShipIsNotDrawnShipAligned() throws Exception {
        // GROUND-SUBJECT: this leg's premise is a body standing on REAL TERRAIN beside the ship, so
        // it takes the surveyed-clean plot rather than the open-air band. The old 7420 had 49 water
        // columns in its own footprint, and a body cannot stand on water.
        final int bx = Plot.CLEAN_GROUND_X, by = Plot.CLEAN_GROUND_Y, bz = Plot.CLEAN_GROUND_Z;

        double[] ship = buildShip(bx, by, bz);
        rollShip(bx, by, bz);

        // Find the spot on the REAL GROUND beside the hull that is valid for leg A: inside the ship's
        // grown world box (the bug's precondition) with ZERO ship support (a carried body belongs to
        // leg B). Searched for, not assumed — where a rolled hull's box meets the ground is not where
        // a hand-picked offset guesses — and both halves are the server's measurement: a body merely
        // near a ship, or one the ship carries, would make a green vacuous.
        //
        // NOTHING IS BUILT FOR IT. This used to lay a stone floor under ~24 candidates one to five
        // blocks above the ship's centre, on the claim that world blocks inside a ship's box are
        // independent of it. They are not: VS collides a hull with the world blocks around it
        // (WorldPhysicsCollider), so the arrangement was building obstacles in the hull's own volume,
        // and in the run that measured it the held ship moved 2.9 blocks inside one window. The
        // player-facing case is a body on the ground beside a tilted hull, so that is what is staged.
        double[] spot = null;
        StringBuilder tried = new StringBuilder();
        for (double[] candidateSpot : groundSpotsAround(ship, by + 1)) {
            // Exactly ONE cow may exist while probing: a rejected candidate left standing somewhere
            // supported would rotate legitimately and read as a red on the subject.
            exec("kill @e[type=cow]");
            int candidate = spawnSubject(candidateSpot[0], candidateSpot[1], candidateSpot[2]);
            DeckCapture probe = DeckCapture.byId(this::exec, 0, candidate);
            if (probe.aboardByContainment) {
                tried.append(String.format(java.util.Locale.ROOT,
                        "[%.1f,%.1f contain obst=%d terr=%s]", candidateSpot[0], candidateSpot[2],
                        probe.shipSupportObstacles, probe.supportedByWorldTerrain));
            }
            if (besideTheHull(probe)) {
                spot = candidateSpot;
                break;
            }
        }
        exec("kill @e[type=cow]");
        scenario().requireArranged("no spot on the ground around this ship was INSIDE its box, unsupported by"
                        + " it AND standing on world terrain — leg A cannot be staged here. Contained"
                        + " candidates: " + tried + " | ship: " + ShipInfo.of(shipInfo()),
                spot != null);

        // ONE staging, and it must draw. The camera settles first and the subject is spawned in front
        // of it (a teleport re-streams entities, so spawning before it would race the arrival).
        watchFromOutside(spot, ship);
        int subject = spawnSubject(spot[0], spot[1], spot[2]);
        Sampling s = awaitRemoteSampling(subject);
        System.out.println(String.format(java.util.Locale.ROOT, "[modelgate] legA staged at [%.1f,%.1f,%.1f] -> %s",
                spot[0], spot[1], spot[2], s.drawn ? "DRAWN" : "not drawn " + s.diagnostic));
        assertTrue("the staged body was never DRAWN by the client, so nothing below can be concluded"
                        + " about the model gate's DECISION. The diagnostic names the dead stage, both"
                        + " sides of the subject and what the camera stands in: " + s.diagnostic
                        + " | client cows=" + safeReportCows(),
                s.drawn);

        // WINDOW: the precondition is read at BOTH ends, and the verdict is only a verdict if it held
        // at both. "Beside the hull" is a relation to a hull that stays put; a held hull that moves
        // during the reading can sweep onto the body — then rotating it is right, and leg B's
        // contract — or away from it, and then a gate that rotates everything in the box would pass
        // for want of anything in the box. Each end carries the ship's pose, so a lapsed premise
        // names what moved.
        String shipAtOpen = shipInfo();
        DeckCapture contactAtOpen = DeckCapture.byId(this::exec, 0, subject);
        String legWindow = watchModelGate(60);
        String shipAtClose = shipInfo();
        DeckCapture contactAtClose = DeckCapture.byId(this::exec, 0, subject);

        long samples = (long) Events.number(legWindow, "samples");
        long rotated = (long) Events.number(legWindow, "rotated");
        System.out.println("[modelgate] legA window :: " + legWindow);
        // Instrument-fires check FIRST, and split by cause: a zero here would otherwise make the
        // rotated==0 assertion below true for the wrong reason — prove the instrument fires before
        // believing the zero it reports.
        assertInstrumentFired(legWindow);
        String bothEnds = " | open: " + ShipInfo.of(shipAtOpen) + " " + contactAtOpen.raw()
                + " | close: " + ShipInfo.of(shipAtClose) + " " + contactAtClose.raw();
        scenario().requireArranged("the subject must be inside the hull's box and unsupported by it at"
                        + " BOTH ends of the window, or the reading is not about a body beside the hull"
                        + bothEnds,
                besideTheHull(contactAtOpen) && besideTheHull(contactAtClose));
        assertTrue("a body on world terrain beside a rolled ship must NOT be drawn ship-aligned: "
                        + rotated + "/" + samples + " decisions pushed a rotation :: " + legWindow
                        + bothEnds,
                rotated == 0);
    }

    // ---- Leg B (control): the gate must still rotate a body the ship DOES carry ----------------

    @Test
    public void aBodyCarriedByARolledDeckIsStillDrawnShipAligned() throws Exception {
        // GROUND-SUBJECT: the second and last clean plot. This class's two scenarios share ONE world
        // and the pinned seed offers no third, so this sits sixteen blocks from leg A's — which is
        // why every assertion here resolves its ship BY IDENTITY rather than by what happens to be
        // nearby. The old 7620 was a slope (relief 21).
        final int bx = Plot.CLEAN_GROUND_X, by = Plot.CLEAN_GROUND_Y, bz = Plot.CLEAN_GROUND_Z2;

        double[] ship = buildShip(bx, by, bz);
        // Put the subject on the deck BEFORE the roll: it rides the deck up with the ship, which is
        // how a crew member gets to a steep deck in play. Spawning onto an already-inverted deck
        // would need a world point that is only derivable through the ship transform.
        int subject = spawnSubjectOnDeck(bx, by, bz);
        rollShip(bx, by, bz);

        DeckCapture contact = DeckCapture.byId(this::exec, 0, subject);
        assertTrue("the subject must be CARRIED by the ship for the control to mean anything: " + contact.raw(),
                contact.shipSupportObstacles > 0);
        // "The ship" — this one, and no other. The subject is a mob, so the probe answers on its
        // GATED branch (`canPassengerSteer:false`), where the support count is resolved by
        // containment-first-match and names nobody: `shipSupportObstacles:2` is a number about one of
        // the hulls containing the body with nothing saying which. The containment LIST is what says
        // so, and its size is the half that matters — one entry means the count above is
        // unambiguous, two mean it is a coin toss reading as a clean number either way.
        // Asserted on the ARRAY, not on a rendering of it. The expected side used to be a hand-built
        // `["<id>"]` against `Arrays.toString`, which quotes nothing — so the two sides could not
        // match for any world at all, and the leg reported a containment defect while the reply it
        // printed named exactly the one hull it wanted.
        assertArrayEquals("the hull carrying the subject must be the ship this leg rolled, and it"
                        + " must be the ONLY hull containing it — the support count beside this names"
                        + " no ship at all: " + contact.raw(),
                new String[]{scenarioShipId}, contact.containingShipIds());

        lookAt(ship[0], ship[1], ship[2]);
        Sampling s = awaitRemoteSampling(subject);
        assertTrue("the carried subject was never drawn by the client, so this control proves nothing: "
                        + s.diagnostic + " | client cows=" + safeReportCows(),
                s.drawn);
        String legWindow = watchModelGate(60);

        long samples = (long) Events.number(legWindow, "samples");
        long rotated = (long) Events.number(legWindow, "rotated");
        System.out.println("[modelgate] legB window :: " + legWindow);
        assertInstrumentFired(legWindow);
        assertTrue("a body carried by a steeply rolled deck must still be drawn ship-aligned: "
                        + rotated + "/" + samples + " decisions pushed a rotation :: " + legWindow,
                rotated > 0);
        assertTrue("the pushed rotation must be the ship's real attitude, not a token tilt :: "
                        + legWindow,
                Events.number(legWindow, "maxDeg") > REAL_ATTITUDE_DEG);
    }

    // ---- helpers (self-contained, mirroring the other tier-2 e2e classes) ----------------------

    /** The outcome of {@link #awaitRemoteSampling}: whether the client actually DREW the staged subject
     *  (the model-rotation hook sampled a remote body), and — when it did not — a self-classifying
     *  diagnostic naming which render stage was dead so a caller need not hypothesise. */
    private static final class Sampling {
        final boolean drawn;
        final String diagnostic;

        Sampling(boolean drawn, String diagnostic) {
            this.drawn = drawn;
            this.diagnostic = diagnostic;
        }
    }

    /** PRECONDITION gate: wait until the model-rotation hook is actually SAMPLING the staged remote
     *  body, so the measurement window that follows opens on a subject the client is already drawing.
     *  Under shared-harness load the subject's first rendered frame can lag the look, and a window
     *  opened before that reads zero samples on a client that draws models perfectly well.
     *
     *  <p>Returns {@link Sampling#drawn}=false rather than asserting, so the caller can RE-STAGE at a
     *  fresh spot (a world body inside a ship box is intermittently not drawn under load).
     *  When it returns false the diagnostic classifies the miss over the awaited window from the two
     *  render-stage controls — {@code cameraHookCalls} (frames) and the window's own {@code calls}
     *  (every living model, player included) — so a red run names its own failure stage:
     *  frames==0 → the draw stage is dead; frames&gt;0,models==0 → frames ran but no living model was
     *  drawn (applyRotations unreached); frames&gt;0,models&gt;0 → models ARE drawn but this subject is
     *  not (culled / absent from the render list).
     *
     *  <p>Only the precondition is awaited — the measurement window the caller opens afterwards stays
     *  a FIXED wait, deliberately. What is awaited here (the window's first remote sample) is NOT what
     *  either leg asserts on: its {@code rotated} is, read from that later window. Ending it
     *  early on a samples predicate would move what the assertion sees — leg A's {@code rotated == 0}
     *  gets easier the fewer samples it saw, and leg B's {@code rotated > 0} can exit before the first
     *  ROTATED frame lands. That is exactly the case in which the fixed wait must stay.</p> */
    private Sampling awaitRemoteSampling(int subjectId) throws Exception {
        // First THIS subject must have ARRIVED on this side. Both legs spawn it and only then move
        // the camera, and a teleport re-streams chunks AND entities - so the body reaches the client
        // after a race a fixed wait wins only sometimes.
        //
        // The gate asks for the SUBJECT BY ID. It used to ask whether the client's total loaded-entity
        // count was > 1, and in a shared client that predicate cannot fail: measured, the count sat at
        // 94-98 while the client held no cow at all, so the gate passed every time and the miss was
        // then re-diagnosed downstream as a render cull. Entity ids are assigned server-side and
        // repeated verbatim in the spawn packet, so the id is one address on both sides.
        //
        // It now waits for the ARRIVAL ITSELF - the client's own record of the body joining its world,
        // since a mark taken before the spawn - rather than sampling a list of what the client is
        // currently holding. The difference is the one this class paid a month for: a snapshot poll
        // cannot tell "it never arrived" from "it arrived and was gone again before I looked", and
        // those are different bugs. The record survives the removal; the snapshot did not.
        String arrivals;
        try {
            arrivals = clientEvents().awaitMatching(subjectSpawnMark, "entity_joined_world",
                    seen -> Events.countRecords(seen, "e", String.valueOf(subjectId)) > 0,
                    "naming the subject " + subjectId,
                    "the staged subject must reach the CLIENT world before its drawing is watched",
                    SUBJECT_ARRIVAL_BUDGET_TICKS);
        } catch (AssertionError neverArrived) {
            // Not a failure of the scenario: the caller re-stages at a fresh spot. So the expiry is
            // turned into the returned diagnostic, and the log read once for it.
            arrivals = clientEvents().since(subjectSpawnMark, "entity_joined_world");
            // An empty log is an answer only once somebody was listening. This is an ASSERTION and
            // not part of the returned diagnostic on purpose: a recorder that never ran is a harness
            // fault, and re-staging at a fresh spot would not fix it.
            Events.assertInstrumentRan(arrivals, "client_entity_join_events",
                    "subject " + subjectId + " never reached the client world");
            return new Sampling(false, "[subject " + subjectId + " never reached the CLIENT world:"
                    + " nothing joined it under that id within " + SUBJECT_ARRIVAL_BUDGET_TICKS
                    + " ticks. joins=" + arrivals + " server=" + serverEntity(subjectId)
                    + " " + clientSighting(subjectId) + "]");
        }

        // Its OWN window, so the predicate is "a remote body was drawn SINCE THIS WAIT BEGAN" — a
        // zero-based count rather than a difference against a per-JVM total another scenario had
        // already advanced. The live field is the only one read while a window is open; everything
        // the failure branch needs comes off the closing record.
        final long windowMark = clientEvents().mark();
        final long framesBefore = (long) deckCamera("cameraHookCalls");
        ClientWindow arrivalWindow = ClientWindow.open(bot(), REMOTE_MODEL_WINDOW);
        // A LINK: the window records its first decision about each remote body, by id, so "THIS
        // subject was drawn since this wait began" is a record — not a count peeked until it rose,
        // which any neighbour's body could raise while the subject itself was culled.
        String firstDrawn;
        try {
            clientEvents().awaitField(windowMark, "remote_model_first_sample", "e", subjectId,
                    "the staged subject must be drawn through the model gate once it is on the"
                            + " client", FIRST_SAMPLE_BUDGET_TICKS);
            arrivalWindow.close();
            return new Sampling(true, "");
        } catch (AssertionError neverDrawn) {
            // The caller re-stages on a miss, so the expiry becomes the diagnostic below.
            firstDrawn = neverDrawn.getMessage();
        }
        long frames = (long) deckCamera("cameraHookCalls") - framesBefore;
        arrivalWindow.close();
        String window = Events.lastRecord(clientEvents().since(windowMark, "remote_model_window"));
        long models = window == null ? -1L : (long) Events.number(window, "calls");
        long loaded = (long) deckCamera("loadedEntities");
        // The subject may have LEFT between the arrival gate and here, and "it is gone" and "it is
        // drawn wrong" are different bugs with the same zero. Read BOTH sides at the end of the
        // window so the verdict below is a claim about rendering only when the body is still there
        // to render: the server says whether the entity is alive and where, the client says whether
        // it holds it at all.
        String subject = "server=" + serverEntity(subjectId) + " " + clientSighting(subjectId)
                + " " + cameraBlocks() + " modelGateInstalled="
                + (window == null ? "?" : Events.text(window, "modelGateInstalled"))
                // The arrival record, kept alongside: a body that JOINED this client and is no
                // longer in the sighting has been removed, and that is a different bug from a body
                // the renderer declined to draw. The snapshot alone could not say which.
                + " joined=" + arrivals;
        String verdict = frames == 0 ? "draw-stage-dead(no frames)"
                : models == 0 ? "no-living-model-drawn(applyRotations unreached)"
                : "subject-culled(models drawn, subject absent from render list)";
        return new Sampling(false, String.format(java.util.Locale.ROOT,
                "[%s frames+=%d models+=%d loaded=%d %s | the wait: %s]",
                verdict, frames, models, loaded, subject, firstDrawn));
    }

    /** The subject as the SERVER holds it right now — alive, dead, or gone from the world entirely.
     *  This is the link the render diagnostic cannot supply and cannot do without: every "the client
     *  did not draw it" reading is vacuous if there was nothing left to draw. */
    private String serverEntity(int subjectId) {
        try {
            return exec("stellurgytest entity info 0 " + subjectId).replace('\n', ' ');
        } catch (Exception e) {
            return "entity-info-failed: " + e;
        }
    }

    /** What the camera is standing IN. Vanilla draws an entity only when its chunk SECTION reached
     *  {@code RenderGlobal.renderInfos}, and that set is grown from the section the camera occupies
     *  through the occlusion graph — so a camera buried in terrain can render hundreds of frames and
     *  reach no living model at all, which is indistinguishable from a cull unless somebody asks. */
    private String cameraBlocks() {
        try {
            double[] me = clientPos();
            int cx = (int) Math.floor(me[0]), cy = (int) Math.floor(me[1]), cz = (int) Math.floor(me[2]);
            return String.format(java.util.Locale.ROOT, "camera@[%d,%d,%d] feet=%s eye=%s",
                    cx, cy, cz, blockAt(cx, cy, cz), blockAt(cx, cy + 1, cz));
        } catch (Exception e) {
            return "camera-blocks-failed: " + e;
        }
    }

    private String blockAt(int x, int y, int z) throws Exception {
        return Reply.of("stellurgytest block at",
                exec("stellurgytest block at 0 " + x + " " + y + " " + z)).text("block");
    }

    /** Whether the CLIENT world holds THIS subject, and where it puts it. Best effort: a probe
     *  failure must not mask the assertion it is annotating. */
    private String clientSighting(int subjectId) {
        try {
            com.google.gson.JsonArray seen =
                    bot().reportEntities("Cow", 96.0).getAsJsonArray("entities");
            for (int i = 0; i < seen.size(); i++) {
                com.google.gson.JsonObject e = seen.get(i).getAsJsonObject();
                if (e.get("id").getAsInt() == subjectId) {
                    return String.format(java.util.Locale.ROOT, "client-has-subject@[%.1f,%.1f,%.1f]",
                            e.get("x").getAsDouble(), e.get("y").getAsDouble(),
                            e.get("z").getAsDouble());
                }
            }
            return "client-LACKS-subject(cows within 96=" + seen + ")";
        } catch (Exception e) {
            return "client-sighting-failed: " + e;
        }
    }

    /** Client-side positions of every cow the client currently sees, for a red-run diagnostic. Best
     *  effort: a probe failure must not mask the assertion it is annotating. */
    private String safeReportCows() {
        try {
            return bot().reportEntities("Cow", 80.0).toString();
        } catch (Exception e) {
            return "reportEntities(Cow) failed: " + e;
        }
    }

    /**
     * Watch the model gate for {@code ticks} and return the window's summary record.
     *
     * <p>A WINDOW, opened and closed, where this used to be two reads of cumulative statics with a
     * subtraction between them. The three counts, the maximum angle and the trace all come off one
     * record, so they describe one stretch of one run — where the statics were per-JVM totals that a
     * shared client had already been advancing before this scenario began, and a forgotten
     * subtraction read as a rich sample.</p>
     */
    private String watchModelGate(int ticks) throws Exception {
        long mark = clientEvents().mark();
        ClientWindow window = ClientWindow.open(bot(), REMOTE_MODEL_WINDOW);
        // WINDOW: its one record is the whole reading.
        bot().waitWorldTicks(ticks);
        window.close();
        String summary = Events.lastRecord(clientEvents().since(mark, "remote_model_window"));
        assertTrue("the model-gate window recorded nothing at all, so the harness — not the gate — "
                + "is what this leg would be measuring", summary != null);
        return summary;
    }

    /** Fail with the RIGHT diagnosis when nothing was sampled: a silent {@code require = 0} mixin
     *  miss and "the body was never rendered" both present as zero remote samples, and they are
     *  different bugs. */
    private void assertInstrumentFired(String window) {
        long calls = (long) Events.number(window, "calls");
        long samples = (long) Events.number(window, "samples");
        assertTrue("the applyRotations hook never ran in this window (calls=0) - the model gate is "
                        + "not installed at all (require = 0 mixin miss), so nothing here can be "
                        + "concluded about the gate's DECISION: " + window,
                calls > 0);
        assertTrue("the hook ran (" + calls + " calls) but decided about no REMOTE body - the "
                        + "subject was never drawn, so this leg proves nothing: " + window,
                samples > 0);
    }

    /** Hold the ship at a steep roll and wait for the attitude to actually CONVERGE - the stimulus
     *  depends on the fixture's dynamic state, so it gates on the measured attitude, never on a
     *  tick count (under suite load the slew takes longer than any fixed wait). */
    private void rollShip(int bx, int by, int bz) throws Exception {
        String infoBefore = shipInfo();
        double qxBefore = readDouble(infoBefore, Q_X), qzBefore = readDouble(infoBefore, Q_Z);
        double upBefore = 1.0 - 2.0 * (qxBefore * qxBefore + qzBefore * qzBefore);
        assertTrue("attitude hold must accept the steep roll",
                Reply.of(exec("stellurgytest vs point-by-id 0 " + scenarioShipId + " " + STEEP_ROLL)
                        ).bool("commanded"));
        // WINDOW: not a poll — and the comment above was right that a tick count cannot be the
        // GATE, which is a different claim from "so it must re-read until it likes the answer". An
        // attitude converging under a hold is a physical value nobody publishes, and the hold never
        // decides it has arrived, so there is no link to await; but a loop whose exit is the
        // assertion three lines below it can be timed out and never disproved. Give the slew its
        // ticks, then read: the hold applies torque toward its target every tick and HOLDS the
        // attitude once it is there, so a window longer than the slew reads the same state. Equal
        // ends mean the command was ignored; different-but-short means the window was.
        advanceServerAndClient(ROLL_WINDOW_TICKS);
        // The ship's own up, world-frame, from the attitude quaternion the probe reports.
        String info = shipInfo();
        double qx = readDouble(info, Q_X), qz = readDouble(info, Q_Z);
        double upY = 1.0 - 2.0 * (qx * qx + qz * qz);
        System.out.println("[modelgate] upY " + upBefore + " -> " + upY + " over " + ROLL_WINDOW_TICKS
                + " ticks (the gate is < -0.85)");
        assertTrue("the ship must reach the steep roll for either leg to mean anything (upY "
                + upBefore + " -> " + upY + " over " + ROLL_WINDOW_TICKS + " ticks)",
                upY < STEEP_ROLL_UP_Y);
    }

    /** How far from the ship's centre, in blocks per axis, the ground is searched for leg A's spot.
     *  The TEST'S OWN, and it bounds only the COST of the search: whether a spot qualifies is the
     *  probe's answer, and a search that finds none fails as an arrangement listing every candidate
     *  the box did contain — so a radius too small reads as "nothing contained", not as a verdict. */
    private static final int GROUND_SEARCH_RADIUS = 6;

    /** Block-centred standing points on the ground at feet height {@code feetY}, in a square of
     *  {@link #GROUND_SEARCH_RADIUS} around the ship's centre, nearest first. The ground here is the
     *  surveyed flat plot, so every point stands on the world's own terrain; which of them the rolled
     *  hull's box reaches is the probe's to say. */
    private static java.util.List<double[]> groundSpotsAround(double[] ship, int feetY) {
        final int cx = (int) Math.floor(ship[0]), cz = (int) Math.floor(ship[2]);
        java.util.List<double[]> spots = new java.util.ArrayList<double[]>();
        for (int dx = -GROUND_SEARCH_RADIUS; dx <= GROUND_SEARCH_RADIUS; dx++) {
            for (int dz = -GROUND_SEARCH_RADIUS; dz <= GROUND_SEARCH_RADIUS; dz++) {
                spots.add(new double[]{cx + dx + 0.5, feetY, cz + dz + 0.5});
            }
        }
        final double sx = ship[0], sz = ship[2];
        java.util.Collections.sort(spots, new java.util.Comparator<double[]>() {
            @Override
            public int compare(double[] a, double[] b) {
                return Double.compare(sq(a[0] - sx) + sq(a[2] - sz), sq(b[0] - sx) + sq(b[2] - sz));
            }
        });
        return spots;
    }

    private static double sq(double v) {
        return v * v;
    }

    /** Leg A's premise for one probe of the subject: inside the hull's grown box (the bug's
     *  precondition), with no ship support under it, standing on world terrain. {@code
     *  shipSupportObstacles} is {@code -1} when no hull's box contains the body, which is why
     *  containment is asked separately rather than read off a zero. */
    private static boolean besideTheHull(DeckCapture probe) {
        return probe.aboardByContainment && probe.shipSupportObstacles == 0 && !probe.supportedByShip
                && probe.supportedByWorldTerrain;
    }

    /** How far past the subject the camera stands, away from the hull, in blocks. The TEST'S OWN:
     *  close enough that a cow fills a useful part of the frame, far enough that the one block the
     *  camera stands on is outside any box that reached the subject's spot from the other side. */
    private static final double CAMERA_STANDOFF = 8.0;

    /** Stand the camera on the far side of {@code spot} from the ship and aim it at the spot.
     *
     *  <p>A camera buried in terrain draws no living model at all — vanilla grows
     *  {@code RenderGlobal.renderInfos} out of the chunk section the camera occupies — so the camera
     *  gets air to stand in and one block to stand on. That block is the only thing this leg builds,
     *  and it is put on the side AWAY from the hull because a world block near a hull is a collision
     *  VS resolves by pushing the hull. The air fill starts at the subject's feet, so the ground it
     *  stands on is untouched.</p> */
    private void watchFromOutside(double[] spot, double[] ship) throws Exception {
        double ox = spot[0] - ship[0], oz = spot[2] - ship[2];
        double len = Math.sqrt(ox * ox + oz * oz);
        if (len < 1.0e-6) {
            ox = 1.0; oz = 0.0; len = 1.0;
        }
        double camX = spot[0] + ox / len * CAMERA_STANDOFF, camZ = spot[2] + oz / len * CAMERA_STANDOFF;
        int feet = (int) Math.floor(spot[1]);
        int x0 = (int) Math.floor(Math.min(spot[0], camX)) - 1, x1 = (int) Math.floor(Math.max(spot[0], camX)) + 1;
        int z0 = (int) Math.floor(Math.min(spot[2], camZ)) - 1, z1 = (int) Math.floor(Math.max(spot[2], camZ)) + 1;
        String box = exec("stellurgytest fill 0 " + x0 + " " + feet + " " + z0 + " " + x1 + " " + (feet + 6)
                + " " + z1 + " minecraft:air");
        assertTrue("the camera-to-subject volume must clear: " + box, Reply.of(box).ok());
        int px = (int) Math.floor(camX), pz = (int) Math.floor(camZ);
        String pad = exec("stellurgytest fill 0 " + px + " " + (feet + 2) + " " + pz + " " + px + " "
                + (feet + 2) + " " + pz + " minecraft:stone");
        assertTrue("the camera needs a floor to stand on: " + pad, Reply.of(pad).ok());

        long moveMark = clientEvents().mark();
        exec("tp @a " + (px + 0.5) + " " + (feet + 3) + " " + (pz + 0.5) + " 0 0");
        awaitClientPlacedNear(moveMark, px + 0.5, pz + 0.5,
                "the camera is moved before it is aimed, and both are the client's");
        aimAt(spot[0], spot[1], spot[2]);
    }

    /** Spawn the subject mob ON the fixture's iron deck (built at {@code rocketY+3 = baseY+4}, walkable
     *  top at {@code baseY+5}, centred on {@code baseX+3 / baseZ+3}) and return its entity id.
     *
     *  <p>The deck's WORLD position is derived from the base, not from the ship-info reference point
     *  ({@code ship[1]} is the physics object's origin, not the deck floor — a {@code +2} offset off it
     *  floated the subject 3 blocks under the deck and read zero support). VS assembles the ship in
     *  place, so the deck blocks stay at their world coordinates until the roll. The derived height is
     *  then VERIFIED by the support probe (a small sweep tolerates a one-block VS settle), never
     *  assumed — a subject the ship does not actually carry would make this control leg vacuous.</p> */
    private int spawnSubjectOnDeck(int bx, int by, int bz) throws Exception {
        double cx = bx + 3 + 0.5, cz = bz + 3 + 0.5;
        int chosen = -1;
        StringBuilder tried = new StringBuilder();
        for (double y : new double[]{by + 5, by + 5.2, by + 6, by + 4.5, by + 7}) {
            exec("kill @e[type=cow]");
            int candidate = spawnSubject(cx, y, cz);
            DeckCapture probe = DeckCapture.byId(this::exec, 0, candidate);
            int obst = probe.shipSupportObstacles;
            tried.append(String.format(java.util.Locale.ROOT, "[y=%.1f obst=%d]", y, obst));
            if (obst > 0) {
                chosen = candidate;
                break;
            }
        }
        assertTrue("no height over the deck put the subject ON it (ship carries it, >=1 support "
                        + "obstacle); tried " + tried, chosen >= 0);
        return chosen;
    }

    private int spawnSubject(double x, double y, double z) throws Exception {
        // A COW is the subject, and the choice is load-bearing. The gate hooks
        // RenderLivingBase.applyRotations; RenderArmorStand OVERRIDES that method and never calls
        // super, so a stand is drawn without the hook ever running - a first draft used one and
        // measured a flat zero on a client that was rendering perfectly well. RenderCow inherits
        // the method (as does RenderPlayer on its normal branch), so a cow exercises the same code
        // path a remote crew member does.
        //
        // The client mark goes BEFORE the spawn, so the body's arrival on this side cannot fall
        // between two reads - see the arrival gate for why that matters here in particular.
        subjectSpawnMark = clientMark();
        String spawned = exec("stellurgytest vs drop-living 0 minecraft:cow " + x + " " + y + " " + z);
        System.out.println("[modelgate] spawn raw: " + spawned.replace('\n', ' '));
        assertTrue("the subject mob must spawn: " + spawned, Reply.of(spawned).ok());
        // No settle: its arrival on the client is awaited as the client's own join record, from
        // subjectSpawnMark, by whoever next needs it there.
        return Reply.of("stellurgytest entity spawn", spawned).integer(ENTITY_ID);
    }

    /** Teleport beside a world position and aim at it. Used by the ship legs, where the camera has
     *  to be moved to the fixture first. */
    private void lookAt(double x, double y, double z) throws Exception {
        long moveMark = clientEvents().mark();
        exec("tp @a " + (x + 8) + " " + (y + 3) + " " + (z + 8) + " 0 0");
        // `aimAt` computes the look FROM the client's own position and then verifies it, so the move
        // has to have reached the client first: aiming from where it used to be produces a valid
        // aim at the wrong thing.
        awaitClientPlacedNear(moveMark, x + 8, z + 8,
                "the camera is moved to the fixture before it is aimed at it, and both are the"
                        + " client's");
        aimAt(x, y, z);
    }

    /** Aim the client at a world position WITHOUT moving it, and verify the aim took. */
    private void aimAt(double x, double y, double z) throws Exception {
        double[] me = clientPos();
        double dx = x - me[0], dy = y - me[1], dz = z - me[2];
        float yaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
        // No advance: setLook writes the rotation on the client thread before it answers, and
        // everything read below is the client's.
        bot().setLook(yaw, pitch);

        // Read the look BACK. Setting it is not the same as it taking effect, and an unverified
        // aim is one more way for a draw-stage zero to mean nothing: a subject behind the camera
        // is culled and never drawn, which looks identical to "models are not drawn at all".
        com.google.gson.JsonObject st = bot().reportState();
        double gotYaw = st.get("playerYaw").getAsDouble(), gotPitch = st.get("playerPitch").getAsDouble();
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        System.out.println(String.format(java.util.Locale.ROOT,
                "[modelgate] look: want=(%.1f,%.1f) got=(%.1f,%.1f) dist=%.1f", yaw, pitch, gotYaw, gotPitch, dist));
        assertTrue(String.format(java.util.Locale.ROOT,
                        "the client must actually be aimed at the subject: wanted yaw %.1f, got %.1f",
                        yaw, gotYaw),
                Math.abs(wrap180(gotYaw - yaw)) < AIMED_AT_THE_SUBJECT_DEG);
    }

    private static double wrap180(double deg) {
        double d = deg % 360.0;
        if (d >= 180.0) d -= 360.0;
        if (d < -180.0) d += 360.0;
        return d;
    }

    private double[] clientPos() throws Exception {
        com.google.gson.JsonObject st = bot().reportState();
        return new double[]{st.get("playerX").getAsDouble(), st.get("playerY").getAsDouble(),
                st.get("playerZ").getAsDouble()};
    }

    /** Build a ship at this base and wait for it to load with the client present; returns its world pos. */
    private double[] buildShip(int bx, int by, int bz) throws Exception {
        long awayMark = clientEvents().mark();
        exec("tp @a " + (bx + 600) + " 120 " + (bz + 600) + " 0 0");
        awaitClientPlacedNear(awayMark, bx + 600, bz + 600,
                "the assembly below must run with no observer near it, and the observer is a client");

        // The registry's own record of the ship being ADDED, since a mark taken before the assembly
        // was queued. Two things a count could not do: it is THIS scenario's ship by construction —
        // where an incremented count on a shared world is answered by every neighbour that ever
        // assembled one — and it NAMES the ship, so the identity comes out of the record instead of a
        // nearest-ship lookup inside a radius bound. Both legs then roll that ship past vertical, so
        // an identity is the only address that keeps working.
        Events events = serverEvents();
        long spawnMark = events.markInstrumented();
        String assemble = assembleFixture(bx, by, bz);
        assertTrue("a " + VARIANT + " build must route to a ship: " + assemble,
                (Reply.of(assemble).integer("rocketCount") == 0));
        scenarioShipId = awaitShipSpawned(events, spawnMark, "assembly must create a VS ship in the"
                + " physics registry (the spawn is queued, so this is a deadline for a discrete event"
                + " and not a guess at how long a value takes to settle)");

        long approachMark = clientEvents().mark();
        exec("tp @a " + (bx + 0.5) + " " + (by + 6) + " " + (bz + 0.5) + " 0 0");
        awaitClientPlacedNear(approachMark, bx + 0.5, bz + 0.5,
                "the client's ARRIVAL is what pulls the ship's chunks, so what is asked of the"
                        + " ship below is only answerable because a client got here");

        // The LOAD is a record: `ship_usable`, later than every unload of THIS ship, from the
        // pre-assembly mark. Then ONE read of where it stands, asked BY IDENTITY.
        awaitShipUsable(events, spawnMark, scenarioShipId);
        String info = shipInfo();
        scenario().requireArranged("the ship this scenario assembled (" + scenarioShipId + ") must"
                + " LOAD with the client present; the reply was: " + info, ShipInfo.isLoaded(info));
        ShipInfo pose = ShipInfo.of(info);
        double[] where = new double[]{pose.x, pose.y, pose.z};
        System.out.println("[modelgate] ship at (" + bx + "," + by + "," + bz + ") -> "
                + java.util.Arrays.toString(where));
        return where;
    }

    /**
     * THE ONE GROUND SITE LEFT IN THE CLIENT TIER, and it is a ground site because the terrain is
     * this class's subject rather than its setting: leg A stands a body on real world blocks beside
     * the hull and asserts it is DRAWN there, and the support probe under it resolves against world
     * blocks. A hull hanging in the open-air band has no such ground to stand on.
     *
     * <p>What stood here was the pit: a chunk warmup plus a fill of {@code baseY+1..baseY+10}.
     * Three things replace it and none is the same fill under a new name. The volume is cleared by
     * the SITE, which refuses to dig an open-air one, so the shape cannot spread back. The warmup is
     * gone because the fill force-loads every chunk in its own box. And the clear now REPORTS what
     * it displaced — the number the pre-clear threw away: on the surveyed clean plot these
     * scenarios stand on it is expected to be 0, and a non-zero one is the reading that tells a
     * later red whether the body was on ground or in a hole.</p>
     *
     * <p>HEIGHT 12, against the old fill's 10: ~10 blocks of hull, plus the body released on its
     * deck at {@code by+5..by+7} and the headroom a standing body needs above that.</p>
     */
    private String assembleFixture(int baseX, int baseY, int baseZ) throws Exception {
        FixtureSite site = FixtureSite.onGround(0, baseX, baseY, baseZ,
                "a body stands on real world blocks beside the hull and must be DRAWN standing on"
                        + " them, and its support is resolved against those blocks");
        return RocketFixture.assembleAt(site, this::exec, VARIANT, 2, 12,
                "the hull, and the deck a subject is staged on above this surveyed plot");
    }

    /** This scenario's ship, asked by identity — no distance term to be wrong about. */
    private String shipInfo() throws Exception {
        assertTrue("shipInfo() before buildShip() captured an identity", scenarioShipId != null);
        return shipInfoById(scenarioShipId);
    }

    /**
     * The CLIENT event log's sequence, taken BEFORE the stimulus — and refused unless a recorder is
     * actually subscribed, because an empty log afterwards would otherwise read as "it never
     * happened" when the truth is "nobody was listening". The shared base wraps the SERVER probe's
     * log ({@code serverEvents()}); this class's arrival link is a client one, so it is read here.
     */
    private long clientMark() throws Exception {
        // The adapter's own mark, which makes exactly this check — a private copy of it here was a
        // second place for the assertion's wording to drift from the shared one.
        return clientEvents().mark();
    }

    private double readDouble(String json, String field) {
        double value = Reply.of(json).number(field);
        return value;
    }

    private int readInt(String json, String field) {
        return Reply.of(json).integer(field);
    }

}
