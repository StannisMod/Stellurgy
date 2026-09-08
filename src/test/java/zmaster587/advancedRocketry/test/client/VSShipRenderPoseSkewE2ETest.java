package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;


import org.junit.Test;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import zmaster587.advancedRocketry.test.ArrangementFailure;
import zmaster587.advancedRocketry.test.Events;
import zmaster587.advancedRocketry.test.ShipIdentity;

import static org.junit.Assert.assertTrue;

/**
 * Render-pose vs collision-pose consistency for a body standing on a ship.
 *
 * <p>A ship is DRAWN through the client's interpolated render transform, but every collision /
 * standing computation for a resolved body maps through the GAME-TICK transform. When the two
 * poses diverge (a hovering ship holding an attitude never stops moving), the surface the player
 * collides with sits visibly beside the surface he sees — the playtest report: "I walk not on the
 * blocks I see but about a block away from them; it seems to be the right surface, yet not quite".
 *
 * <h2>Which waits are LINKS and which are values</h2>
 * Everything this scenario needs to have HAPPENED is awaited on the ordered event logs: the
 * assembly becoming a ship in the physics registry ({@code ship_spawned}, which also hands over the
 * identity every later question is keyed on), the parked deck TAKING the body
 * ({@code deck_captured}), the drop teleport being applied on the client
 * ({@code client_pos_look_applied}) and the hull-stand mode being committed
 * ({@code deck_mode_committed} with {@code mode=hull}). What stays a bounded poll is what a poll is
 * for: an attitude slewing to a threshold, a body beginning to fall, and a physics object becoming
 * loaded.
 *
 * <p>The observable is the {@code render_pose_skew} record, written on the client at every commit
 * this class makes: the distance between the committed world position (tick pose) and where the
 * renderer draws the same subspace point (render pose), carrying the ship, the resolution mode and
 * the raw pair the distance was taken between. The contract under test: that gap stays imperceptible
 * wherever a body is resolved against a ship. A parked ship is the control — its render transform
 * converges, so a large control reading would mean the instrument, not the subject.
 *
 * <p>Each leg opens a WINDOW and reads it once at the end, so its maximum is over every sample
 * production produced. The skew used to be polled off a client static every few ticks, which meant a
 * one-tick spike between two reads was invisible — a limit this class documented about itself. The
 * same poll also paired a skew read with a mode read taken a moment later, so a tick that changed
 * mode between the two attributed one mode's skew to the other; the mode now travels on the sample
 * it belongs to.
 *
 */
public class VSShipRenderPoseSkewE2ETest extends AbstractClientE2ETest {

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern POS_X = Pattern.compile("\"posX\":(-?[0-9.E\\-]+)");
    private static final Pattern POS_Y = Pattern.compile("\"posY\":(-?[0-9.E\\-]+)");
    private static final Pattern POS_Z = Pattern.compile("\"posZ\":(-?[0-9.E\\-]+)");
    private static final Pattern Q_X = Pattern.compile("\"qx\":(-?[0-9.E\\-]+)");
    private static final Pattern Q_Z = Pattern.compile("\"qz\":(-?[0-9.E\\-]+)");
    private static final Pattern WORLD_X = Pattern.compile("\"worldX\":(-?[0-9.E\\-]+)");
    private static final Pattern WORLD_Y = Pattern.compile("\"worldY\":(-?[0-9.E\\-]+)");
    private static final Pattern WORLD_Z = Pattern.compile("\"worldZ\":(-?[0-9.E\\-]+)");

    private static final String VARIANT = "with-pilot-deck";

    /** How long the physics registry is given to record the queued assembly as a ship. A deadline
     *  for a discrete event, not a guess at how long a value takes to settle. */
    private static final int SHIP_SPAWN_BUDGET_TICKS = 300;
    /** How long one deck-capture commit is given after the stimulus that must produce it. */
    private static final int DECK_LINK_BUDGET_TICKS = 300;
    /** How long a teleport is given to reach the CLIENT and be applied there. */
    private static final int POS_LOOK_BUDGET_TICKS = 300;

    /** THIS scenario's ship, by identity — captured once by {@link #buildShip}. */
    private String shipId;
    /** The observation point behind every skew record — asserted before a silence is read as "the
     *  skew was fine", which is what an empty window would otherwise say. */
    private static final String SKEW_INSTRUMENT = "render_pose_skew_events";
    /** The observation point behind every hull-sweep record, asserted for the same reason. */
    private static final String SOLID_INSTRUMENT = "hull_collision_solid_events";

    /** The gap a player can feel as "standing beside the blocks I see". The contract bound: the
     *  drawn surface and the collided surface must agree well under this. */
    private static final double VISIBLE_SKEW = 0.35;

    @Test
    public void theSurfaceABodyStandsOnIsTheSurfaceTheRendererDraws() throws Exception {
        final int bx = 7220, by = 64, bz = 7220;

        // ---- Leg A (control): a PARKED ship's render transform converges onto its tick pose, so
        // the skew of a body standing on its deck bounds the instrument's noise floor. A large
        // reading here would indict the instrument (or a constant pose offset), not ship motion.
        Events events = events();
        double[] ship = buildShip(events, bx, by, bz);
        // The mark goes before the teleport, so the capture cannot happen between two reads.
        long captureMark = events.markInstrumented();
        exec("tp @a " + ship[0] + " " + (ship[1] + 4) + " " + ship[2] + " 0 0");
        // The LINK, not eighty ticks: a deck TAKING a body is a commit production performs, and the
        // control window below is only a control if it opens on a body already held. A fixed wait
        // could only be too short (a red about the instrument) or needlessly long.
        // Carrying this scenario's ship: the record names the hull that took him, and the skew
        // sampled below is a comparison between one body and one ship's render pose.
        events.awaitCarrying(captureMark, "deck_captured", "\"ship\":\"" + shipId + "\"",
                "the client player must be TAKEN by THIS parked"
                + " deck before the control window opens", DECK_LINK_BUDGET_TICKS);
        // One sample, and proved to be about this scenario's craft: the skew measured below is the
        // render pose of the ANCHOR ship against the body it carries, so a capture taken by a
        // neighbouring hull would still produce numbers — about the wrong pair.
        String parkedCapture = exec("artest vs deck-capture");
        assertTrue("the client player must be captured on the parked deck before sampling: "
                        + parkedCapture,
                parkedCapture.contains("\"verdict\":true"));
        ShipIdentity.assertCaptureAnchoredOn(parkedCapture, shipId,
                "the client player must be captured on the parked deck this scenario built");
        // ONE window, read once at its end — not twenty polls of a static. Every commit production
        // makes on this client is a record, so the maximum below is over every sample it produced;
        // a spike between two polls, which the static could not show, is in the log.
        long restMark = clientMark();
        double restCrossMax = 0.0;
        StringBuilder restTrace = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            double cross = crossSideDelta();
            if (!Double.isNaN(cross)) restCrossMax = Math.max(restCrossMax, cross);
            restTrace.append(String.format(Locale.ROOT, "[cross=%.4f] ", cross));
            bot().waitTicks(6);
        }
        String restReply = clientEvents().since(restMark, "render_pose_skew");
        Events.assertInstrumentRan(restReply, SKEW_INSTRUMENT,
                "a body standing on the parked deck is committed against a pose at all");
        Skew rest = Skew.of(restReply, null);
        System.out.println("[poseskew] rest " + rest + " crossMax=" + restCrossMax
                + " :: " + restTrace);
        assertTrue("the skew instrument must fire while standing on the parked deck: " + rest,
                rest.samples > 0);
        assertTrue("on a PARKED ship the drawn pose must sit on the tick pose (control; " + rest
                + "): " + restTrace, rest.max < VISIBLE_SKEW);

        // ---- Leg B (subject): the reported configuration — a ship HOVERING on an attitude hold
        // (inverted, so the world-top is a hull-stand surface). Gate on the MEASURED attitude,
        // never elapsed ticks.
        double h = Math.toRadians(160.0) / 2.0;
        assertTrue("attitude hold must accept the past-vertical roll",
                exec("artest vs point-by-id 0 " + shipId + " "
                        + Math.cos(h) + " " + Math.sin(h) + " 0.0 0.0").contains("\"commanded\":true"));
        double upY = 1.0;
        String info = "";
        for (int i = 0; i < 60 && upY >= -0.3; i++) {
            bot().waitTicks(10);
            info = shipInfo();
            double qx = readDouble(info, Q_X), qz = readDouble(info, Q_Z);
            upY = 1.0 - 2.0 * (qx * qx + qz * qz);
        }
        assertTrue("the ship must reach the steep inversion before the hull leg (upY=" + upY + "): "
                + info, upY < -0.3);
        // The drop point must be FREE AIR, and nothing here guaranteed that it was. The fixture is
        // assembled into a 10-block band cleared inside whatever ground the base sits in, and the
        // rolled ship then sinks, so shipY+7 can land INSIDE the world's own terrain. Measured once:
        // the teleport reached the client (target == preY exactly), the body then rose 0.13 and
        // rested there with supportedByWorldTerrain=true, and the leg red'd on its own arrangement.
        // Clear the column the body must fall THROUGH. The ship's blocks live in the shipyard
        // subspace, so this removes world terrain only — the hull is untouched, and so is the
        // ground BELOW the ship, which is whatever its descent rests against.
        clearDropColumn(readDouble(info, POS_X), readDouble(info, POS_Y), readDouble(info, POS_Z));
        bot().waitTicks(20);
        info = shipInfo(); // re-read: the ship may settle once the terrain above it is gone
        double sx = readDouble(info, POS_X), sy = readDouble(info, POS_Y), sz = readDouble(info, POS_Z);
        String dropBlock = exec("artest block at 0 " + (int) Math.floor(sx) + " "
                + (int) Math.floor(sy + 7) + " " + (int) Math.floor(sz));
        assertTrue("the drop point must be free air — otherwise this leg measures the ground rather"
                        + " than the hull: " + dropBlock + " ship=" + info,
                dropBlock.contains("\"isAir\":true"));

        // Drop the bot onto the world-top of the inverted hull. Two marks, one per side: the SERVER's
        // for the mode commit the hull-stand hold IS, the CLIENT's for the teleport's arrival. Both
        // precede the stimulus, so neither link can fall between two reads.
        long hullMark = events.markInstrumented();
        long dropMark = clientMark();
        exec("tp @a " + sx + " " + (sy + 7) + " " + sz + " 0 0");
        // The teleport REACHING the client is a link — the pos-look packet it arrives as — and it is
        // awaited before anything is sampled. preY used to be read straight after the command, so on
        // a client that had not applied the teleport yet it was the OLD altitude and the "he began to
        // fall" poll below was satisfied by the teleport itself.
        clientEvents().await(dropMark, "client_pos_look_applied", "the drop teleport must be APPLIED"
                + " on the client before its fall can be watched", POS_LOOK_BUDGET_TICKS);
        double preY = bot().reportState().get("playerY").getAsDouble();
        long preTicks = bot().reportState().get("ticks").getAsLong();
        // Event-gated fall detection (load-scaled ceiling + early exit): a fixed 60-iteration budget can
        // miss a slow chunk-stream / tick start under concurrent-fork load and red a healthy encounter.
        ClientPoll.Result<Double> fall = ClientPoll.until(bot()::waitTicks,
                () -> bot().reportState().get("playerY").getAsDouble(),
                y -> Math.abs(y - preY) > 0.4, 2, 60);
        // WHAT THIS FAILURE MAY NOT BLAME - three candidates are now excluded BY CONSTRUCTION.
        // (1) "client tick/chunk-stream stall": the poll advances through waitTicks, which ERRORS on
        // its own load-scaled timeout, so a completed poll is proof the client ticked - the delta is
        // printed rather than asserted so the proof travels with the red. (2) "the teleport never
        // landed": the client's own record of applying it was awaited above, and preY was read only
        // afterwards. (3) "the world's ground caught him": the column above the ship was filled with
        // air and the drop point was asserted air a moment ago. What remains is something genuinely
        // holding him up - the inverted hull's own collision, or the deck capture - and BOTH would be
        // the PRODUCT WORKING. deck-capture is read-only and names which.
        long tickDelta = bot().reportState().get("ticks").getAsLong() - preTicks;
        assertTrue("the teleported client must start falling before the hull leg."
                + " target=" + (sy + 7) + " preY=" + preY + " poll=" + fall
                + " clientTicksElapsed=" + tickDelta
                + " (so this is NOT a tick stall) capture=" + exec("artest vs deck-capture"),
                fall.satisfied);

        // The hull-stand hold ENGAGING is a decision production commits in one place — it sets the
        // mode and then calls the one private method every mode transition goes through — so it is
        // awaited as the link it is, instead of polled out of a probe dump on a tick budget. A poll
        // could not see a hold that engaged and was lost between two samples; this can.
        //
        // Filtered on mode "hull" because the same commit names both modes. The recorder is blind in
        // one direction and it does not bite here: a RE-capture taken on the hull-stand travel path
        // reads "aboard" for a body that stays in hull-stand, so a hull commit can be followed by
        // "aboard" records — what is waited for is that at least one "hull" commit happened, which
        // first contact on the inverted hull is.
        String hullCommit;
        try {
            // Carrying, not the type alone: this seam answers more than one verdict and the wait is
            // about one of them.
            hullCommit = events.awaitCarrying(hullMark, "deck_mode_committed", "\"mode\":\"hull\"",
                    "the encounter must engage the HULL-STAND hold before sampling",
                    DECK_LINK_BUDGET_TICKS);
        } catch (AssertionError missed) {
            // The gate's own dump, read AT the failure rather than composed before the wait — the
            // old message printed three counters cumulative since client start, which described the
            // instrument and not this window.
            throw new AssertionError(missed.getMessage() + " | the server gate says: "
                    + exec("artest vs deck-capture") + " | client y="
                    + bot().reportState().get("playerY").getAsDouble());
        }
        System.out.println("[poseskew] hull-stand committed :: " + hullCommit
                + " | probe :: " + exec("artest vs deck-capture"));

        // Sample the skew across the hull-stand window; keep only samples the hull mode produced.
        // The mode travels ON each record, so the filter is per SAMPLE rather than per poll: the old
        // reading took the mode static and the skew static a moment apart and paired them, which on a
        // tick that changed mode between the two reads attributed one mode's skew to the other.
        long hullSkewMark = clientMark();
        StringBuilder hullTrace = new StringBuilder();
        double hullCrossMax = 0.0;
        for (int i = 0; i < 6; i++) {
            double cross = crossSideDelta();
            if (!Double.isNaN(cross)) hullCrossMax = Math.max(hullCrossMax, cross);
            hullTrace.append(String.format(Locale.ROOT, "[cross=%.4f y=%.2f] ",
                    cross, bot().reportState().get("playerY").getAsDouble()));
            bot().waitTicks(13);
        }
        String hullReply = clientEvents().since(hullSkewMark, "render_pose_skew");
        Events.assertInstrumentRan(hullReply, SKEW_INSTRUMENT,
                "a body on the inverted hull is committed against a pose at all");
        Skew hull = Skew.of(hullReply, "hull");
        // The other half of the same window: what SOLID each of those sweeps consumed, against the
        // body's own world box. Read from records rather than from a production static, because the
        // static production used to publish here had become a literal 0.0 — the assertion below could
        // not fail, at any attitude, for as long as it was read off that field.
        String solidReply = clientEvents().since(hullSkewMark, "hull_collision_solid");
        Events.assertInstrumentRan(solidReply, SOLID_INSTRUMENT,
                "the hull-stand sweep consumed the body's own world-upright volume");
        int solidSweeps = 0;
        double solidOffsetMax = 0.0;
        String worstSolid = "(none)";
        for (String record : Events.records(solidReply)) {
            solidSweeps++;
            double offset = Events.number(record, "offset");
            // `>=` on the first sweep too: a healthy body at rest measures exactly 0.0, and a
            // strict `>` would leave the narrative saying "(none)" — no worst sweep named — in
            // precisely the case where the reader wants to see one sample's numbers.
            if (solidSweeps == 1 || offset > solidOffsetMax) {
                solidOffsetMax = Math.max(solidOffsetMax, offset);
                worstSolid = record;
            }
        }
        String omega = shipInfo();
        System.out.println("[poseskew] hull " + hull + " crossMax=" + hullCrossMax
                + " restMax=" + rest.max + " :: " + hullTrace);
        System.out.println("[poseskew] hull solid sweeps=" + solidSweeps + " offsetMax="
                + solidOffsetMax + " worst=" + worstSolid);
        System.out.println("[poseskew] hull ship-info=" + omega);

        // ---- Leg C (driver isolation, diagnostic): the SAME hull-stand configuration under
        // SUSTAINED ship motion. The in-client skew is expected to stay ~0 (the body is committed
        // through the same client pose the renderer draws); the discriminating number is the
        // CROSS-SIDE delta — the client's committed position vs the SERVER's mapping of the same
        // subspace point. A delta that scales with the commanded speed names the client pose LAG
        // as the driver; a speed-independent delta names a constant cross-side pose offset.
        for (double climb : new double[]{0.6, 1.2}) {
            assertTrue("velocity command must engage for the moving leg",
                    exec("artest vs force-vel-by-id 0 " + shipId + " 0 " + climb + " 0")
                            .contains("\"commanded\":true"));
            long moveMark = clientMark();
            double moveCrossMax = 0.0, moveCrossSum = 0.0;
            int moveCrossN = 0;
            StringBuilder moveTrace = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                double cross = crossSideDelta();
                if (!Double.isNaN(cross)) {
                    moveCrossMax = Math.max(moveCrossMax, cross);
                    moveCrossSum += cross;
                    moveCrossN++;
                }
                moveTrace.append(String.format(Locale.ROOT, "[cross=%.4f] ", cross));
                bot().waitTicks(7);
            }
            Skew moving = Skew.of(clientEvents().since(moveMark, "render_pose_skew"), null);
            String after = shipInfo();
            System.out.println(String.format(Locale.ROOT,
                    "[poseskew] moving climb=%.1f %s crossMax=%.4f crossMean=%.4f (n=%d)"
                            + " :: %s", climb, moving, moveCrossMax,
                    moveCrossN == 0 ? -1.0 : moveCrossSum / moveCrossN, moveCrossN, moveTrace));
            System.out.println("[poseskew] moving ship-info=" + after);
        }
        exec("artest vs force-clear-by-id 0 " + shipId);

        assertTrue("the skew instrument must fire in HULL mode (" + hull + "): " + hullTrace,
                hull.samples > 0);
        // The contract: the hull a body stands against is the hull the player SEES. A skew past
        // the visible bound is the reported bug — walking beside the drawn blocks.
        assertTrue("a body hull-standing on a hovering ship must stand on the surface the renderer "
                + "draws — render-vs-collision pose skew " + hull + " (control at rest="
                + rest.max + ", bound=" + VISIBLE_SKEW + "): " + hullTrace,
                hull.max < VISIBLE_SKEW);
        // The lateral-offset report (walking the hull of a ~135-inverted ship "about a block
        // beside the visible blocks"): a hull-stand body is a WORLD-upright capsule, but the
        // sweep collides a SUBSPACE-aligned box — the two volumes sit h*sin(tilt/2) apart, so
        // every contact happens that far from where the player sees himself. At this leg's
        // ~160 degrees that is ~1.77 blocks; the contract is that the collision solid IS the
        // body's real volume.
        assertTrue("the hull sweep must be OBSERVED before its solid can be judged — no sweep was"
                + " recorded in a window that committed " + hull.records + " hull poses, so this"
                + " leg has nothing to conclude from", solidSweeps > 0);
        assertTrue("a hull-stand body must collide as its real world-upright volume, not a "
                + "subspace-aligned phantom displaced by h*sin(tilt/2) — the largest gap between the"
                + " swept solid and the body's own box was " + solidOffsetMax + " over "
                + solidSweeps + " sweeps (visible bound=" + VISIBLE_SKEW + ", upY=" + upY
                + "); worst sweep: " + worstSolid, solidOffsetMax < VISIBLE_SKEW);
    }

    // ---- helpers (self-contained, mirroring the other tier-2 e2e classes) ----------------------

    /**
     * One cross-side pose sample: the CLIENT's latest committed world position against the SERVER's
     * mapping of the same held subspace point ({@code artest vs to-world}, by this scenario's ship
     * id).
     *
     * <p>The client half comes from the last commit RECORDED in a two-tick window opened here, not
     * from a pair of statics. Two things follow. The freshness is bounded and stated — the pair is at
     * most two ticks old — where the statics were simply "whatever was written last", which on a
     * client that had stopped resolving was arbitrarily stale and looked identical to a fresh one.
     * And an absent sample is an absent RECORD: the old reading treated a subspace origin as "no
     * sample yet", so a body genuinely committed at its ship's origin was silently dropped.</p>
     *
     * <p>The server's reply is read a couple of ticks after the client's commit, so on a ship moving
     * at {@code v} the sample carries an error of roughly {@code v * 0.15 s} — read it for signals
     * well above that. NaN when the client committed nothing in the window, or the server cannot map
     * the point.</p>
     *
     * <p>Mapped through THIS ship's transform by id: the positional form maps through the first hull
     * whose box contains the point, and this is a comparison of two sides' transforms of the SAME
     * craft — through another one it would produce a plausible number about nothing.</p>
     */
    private double crossSideDelta() throws Exception {
        long mark = clientMark();
        bot().waitTicks(2);
        String latest = Events.lastRecord(clientEvents().since(mark, "render_pose_skew"));
        if (latest == null) {
            return Double.NaN; // this client committed nothing in the window
        }
        double subX = Events.number(latest, "subX");
        double subY = Events.number(latest, "subY");
        double subZ = Events.number(latest, "subZ");
        String tw = exec("artest vs to-world 0 id " + shipId + " "
                + subX + " " + subY + " " + subZ);
        if (!tw.contains("\"ok\":true")) {
            return Double.NaN;
        }
        return distance(
                new double[]{Events.number(latest, "commitX"), Events.number(latest, "commitY"),
                        Events.number(latest, "commitZ")},
                new double[]{readDouble(tw, WORLD_X), readDouble(tw, WORLD_Y),
                        readDouble(tw, WORLD_Z)});
    }

    /**
     * The render-pose skew samples of one window, folded.
     *
     * <p>{@code records} is every commit the client made in the window; {@code samples} is how many
     * of those were in the mode this fold keeps AND had a render pose to compare against, and
     * {@code max} is the largest gap among them. The three are kept apart because they fail
     * differently: no records means the resolver never ran, records without samples in a mode means
     * the mode never occurred, and samples with no render pose ({@code drawn:false}) means the
     * renderer had nothing to compare against — none of which is "the skew was fine".</p>
     */
    private static final class Skew {
        final String mode;
        final int records;
        final int samples;
        final int undrawn;
        final double max;

        private Skew(String mode, int records, int samples, int undrawn, double max) {
            this.mode = mode;
            this.records = records;
            this.samples = samples;
            this.undrawn = undrawn;
            this.max = max;
        }

        /** Fold a client {@code since} reply; {@code mode} filters to one resolution mode, or null
         *  keeps every commit. */
        static Skew of(String sinceReply, String mode) {
            int records = 0, samples = 0, undrawn = 0;
            double max = 0.0;
            for (String record : Events.records(sinceReply)) {
                records++;
                if (mode != null && !mode.equals(Events.text(record, "mode"))) {
                    continue;
                }
                double skew = Events.number(record, "skew");
                if (Double.isNaN(skew)) {
                    undrawn++;
                    continue;
                }
                samples++;
                max = Math.max(max, skew);
            }
            return new Skew(mode, records, samples, undrawn, max);
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "mode=%s records=%d samples=%d undrawn=%d max=%.4f",
                    mode == null ? "*" : mode, records, samples, undrawn, max);
        }
    }

    /** Empty the WORLD terrain the drop leg needs to be empty: a column around the ship's world
     *  position, from just above it to well over the drop point at {@code y+7}. Ship blocks live in
     *  the shipyard subspace, so nothing of the ship is touched — only ground that would catch the
     *  body before the hull does. Deliberately does NOT clear below the ship: whatever its descent
     *  rests against stays exactly where it was, so this changes the body's fall and nothing else. */
    private void clearDropColumn(double x, double y, double z) throws Exception {
        int fx = (int) Math.floor(x), fy = (int) Math.floor(y), fz = (int) Math.floor(z);
        String cleared = exec("artest fill 0 " + (fx - 8) + " " + (fy + 1) + " " + (fz - 8)
                + " " + (fx + 8) + " " + (fy + 14) + " " + (fz + 8) + " minecraft:air");
        assertTrue("the drop column must be cleared of world terrain: " + cleared,
                cleared.contains("\"ok\":true"));
        System.out.println("[poseskew] drop column cleared around (" + fx + "," + fy + "," + fz
                + "): " + cleared);
    }

    /** Build a ship at this base and wait for it to load with the client present; returns its world pos. */
    private double[] buildShip(Events events, int bx, int by, int bz) throws Exception {
        exec("tp @a " + (bx + 600) + " 120 " + (bz + 600) + " 0 0");
        bot().waitTicks(10);

        // The registry's own record of the ship being ADDED, since a mark taken before the assembly
        // was queued. That makes it THIS scenario's ship by construction, and — the part a count
        // could never do — it NAMES it: the identity every question below is keyed on comes out of
        // the record instead of a nearest-ship lookup at the build spot a tick later. This test rolls
        // its ship past vertical and then flies it upward on purpose, so a positional lookup would
        // drift off its own subject.
        long spawnMark = events.markInstrumented();
        String assemble = assembleFixture(bx, by, bz);
        assertTrue("a with-pilot-seat build must route to a ship: " + assemble,
                assemble.contains("\"rocketCount\":0"));
        String spawned = events.await(spawnMark, "ship_spawned", "the assembly must become a ship in"
                + " the physics registry (the spawn is queued, so this is a deadline for a discrete"
                + " event and not a settling value)", SHIP_SPAWN_BUDGET_TICKS);
        // Exactly ONE record in the window, then its ship. `lastField` takes the LAST record, so a
        // second assembly landing inside this mark would silently re-point every question below and
        // the id would look exactly as legitimate as the right one.
        int spawnedCount = Events.countRecords(spawned, "\"vsShip\":");
        ArrangementFailure.requireArranged("exactly ONE ship may be spawned in this scenario's"
                + " window, or nothing here can say which is its own — " + spawnedCount + " were: "
                + spawned, spawnedCount == 1);
        shipId = Events.lastField(spawned, "vsShip");
        assertTrue("a ship_spawned record must name the ship: " + spawned, shipId != null);
        bot().waitTicks(40);

        exec("tp @a " + (bx + 0.5) + " " + (by + 6) + " " + (bz + 0.5) + " 0 0");
        bot().waitTicks(20);

        // Whether the physics object is LOADED stays a bounded poll: nothing in the vocabulary
        // records that transition, and it is a state the substrate reaches rather than a commit. What
        // changed is that it is now asked BY IDENTITY, so no distance term can answer about a
        // neighbouring ship.
        String info = "";
        double[] where = null;
        for (int i = 0; i < 40 && where == null; i++) {
            bot().waitTicks(5);
            info = shipInfo();
            if (info.contains("\"managed\":true")) {
                where = new double[]{
                        readDouble(info, POS_X), readDouble(info, POS_Y), readDouble(info, POS_Z)};
            }
        }
        assertTrue("the ship this scenario assembled (" + shipId + ") must LOAD with the client"
                + " present; last reply was: " + info, where != null);
        System.out.println("[poseskew] ship at (" + bx + "," + by + "," + bz + ") -> "
                + java.util.Arrays.toString(where));
        return where;
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
        String fixture = exec("artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " " + VARIANT);
        assertTrue("fixture (" + VARIANT + ") failed: " + fixture, fixture.contains("\"ok\":true"));
        Matcher bp = BUILDER_POS.matcher(fixture);
        assertTrue("fixture missing builderPos: " + fixture, bp.find());
        return exec("artest rocket assemble 0 " + bp.group(1) + " " + bp.group(2) + " " + bp.group(3));
    }

    /** This scenario's ship, asked by identity — no distance term to be wrong about. */
    private String shipInfo() throws Exception {
        assertTrue("shipInfo() before buildShip() captured an identity", shipId != null);
        return exec("artest vs ship-info 0 id " + shipId);
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", serverClient().execute(cmd));
    }

    // ---- the two ordered event logs, one per side ----------------------------------------------
    //
    // This class's base is the harness's own per-method client pair, which offers neither the shared
    // AR base's `events()` nor a client-log reader, so both live here. They are deliberately small:
    // a mark taken before the stimulus, and a wait that fails naming the link.

    /** The SERVER's ordered event log, stepped by the real client's own ticks. */
    private Events events() {
        return new Events(this::exec, bot()::waitTicks);
    }

    /** The CLIENT's ordered event log, behind the same verbs. This class extends the harness base
     *  rather than an AR shared one, so it reaches the adapter directly. */
    private Events clientEvents() {
        return ClientEvents.of(bot());
    }

    /** The CLIENT event log's sequence, taken BEFORE the stimulus — refused unless a recorder is
     *  actually subscribed, since an empty log afterwards would otherwise read as "it never
     *  happened" when the truth is "nobody was listening". */
    private long clientMark() throws Exception {
        return clientEvents().mark();
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
