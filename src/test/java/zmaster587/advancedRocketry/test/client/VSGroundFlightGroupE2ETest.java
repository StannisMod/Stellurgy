package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.TestTimeouts;
import com.google.gson.JsonObject;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;
import org.lwjgl.input.Keyboard;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import zmaster587.advancedRocketry.api.FreeFlightPhysics;
import zmaster587.advancedRocketry.test.Events;

import static org.junit.Assert.assertTrue;

/**
 * Ground-assembled tier-2 ships, flown: four scenarios that used to be four classes and four client
 * boots, now one boot.
 *
 * <p>Each scenario keeps the base coordinates its own green runs were taken on — 2200, 2400, 2600,
 * 2800 along the x==z diagonal, 200 blocks apart — because a migrating test that changes its ground
 * has changed its subject. The fifth scenario is not a migration: it is the control that proves the
 * ship oracle this class relies on can tell two ships apart.</p>
 *
 * <h2>Why every ship question here is keyed on an ID</h2>
 *
 * <p>{@code vs ship-info <dim> <x> <y> <z>} answers about the loaded ship NEAREST the point. On the
 * one-boot-per-test world these scenarios came from there was only ever one ship, so the position
 * was an identity by accident. Here there are four, and neither available bound works: unbounded,
 * the reply describes whichever neighbour happens to be closest; bounded by
 * {@link #SHIP_QUERY_RADIUS}, it describes nothing at all as soon as a scenario flies its ship
 * further than that — and {@link #seatedPilotFliesShipTravelsWithItAndCameraLocksToNose} holds the
 * lift key for sixty uninterrupted ticks on purpose.</p>
 *
 * <p>So no scenario here derives an identity from a position at all. A scenario that assembles its
 * own fixture was already TOLD which ship that is: the assembly writes a {@code ship_spawned} record
 * and {@link #awaitShipSpawned} reads the id straight off it, from a mark taken before the assembly
 * was queued. What remains to wait for is a different fact — the physics object being LOADED, which
 * the registry record does not prove — and that wait is not a positional question either
 * ({@link #awaitShipUsable}, on production's own {@code ship_usable} event since that same
 * pre-assembly mark), so it can neither lose a ship that climbed nor find a neighbour's that drifted
 * in.</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class VSGroundFlightGroupE2ETest extends AbstractSharedVsClientE2ETest {

    @Override
    protected String subsystem() {
        return "vs-ground-flight";
    }

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern POS_X = Pattern.compile("\"posX\":(-?[0-9.E\\-]+)");
    /**
     * The travel along {@code p} since {@code before}, or {@code null} when the ship is no longer
     * reporting a position at all.
     *
     * <h2>Why every driving window needs this, measured the hard way</h2>
     *
     * <p>These legs used to poll until the claim held and then stop. Replacing that with a WINDOW
     * equal to the poll's ceiling was argued as safe on the ground that any run the poll would have
     * passed had at most that long — which is true of the ASSERTION and false of the STIMULUS. The
     * poll released the key the moment the ship had risen; a full window keeps commanding it, and a
     * ship under a held throttle for 200 ticks flies out of the loaded region. It then answers
     * {@code {"managed":false,"id":…}} with no position in it, and the read fails as "expected a
     * number" — which is a true statement about the reply and tells the reader nothing.</p>
     *
     * <p>So a driving window stops on OBSERVABILITY, never on the claim: when the subject leaves,
     * there is nothing left to measure and what was measured stands. That is a different condition
     * from the one the assertion makes, which is the whole point — the maximum over the samples
     * taken is still falsifiable by a ship that never moved.</p>
     */
    private static Double travelOrNull(String shipInfo, Pattern p, double before) {
        Matcher m = p.matcher(shipInfo);
        return m.find() ? Double.parseDouble(m.group(1)) - before : null;
    }

    /**
     * How long a commanded flight is DRIVEN before it is measured, in server ticks.
     *
     * <p>Was the ceiling of a bounded loop whose exit condition was the assertion that followed it —
     * "keep commanding until it has climbed 1.5, then assert it climbed 1.0". Nothing in that shape
     * can fail except the ceiling, and the failure text then blames the force controller for a
     * timeout. The loops now drive the whole window and the assertions measure its EXTREMUM, so the
     * claim is falsifiable and a quantity that comes back (a ship that rises and settles, a hull
     * that turns full circle) is not read as one that never moved.</p>
     *
     * <p>The number is the old ceiling unchanged: every run those loops used to pass had at most
     * this long, so nothing that passed before is given less now.</p>
     */
    private static final int FLIGHT_WINDOW_TICKS = 80;

    /** The same, for the attitude-hold convergence leg — its loop's own ceiling was 120. */
    private static final int ATTITUDE_WINDOW_TICKS = 120;

    /**
     * Hold {@code key} for a travel window and return {@code {maxDriven, otherAtThatInstant}} — the
     * sample with the largest travel along {@code driven}, and the travel along {@code other} read
     * off THE SAME reply.
     *
     * <p>The pair is taken from ONE {@code ship-info} reply, because a dominance claim ("the nose
     * axis beats the lateral one") built from two reads a moment apart attributes one instant's
     * travel to another's — which is what the form this replaces did.</p>
     *
     * <p><b>The drive still stops on the threshold, and that is deliberate rather than a relapse.</b>
     * A held key goes on flying the craft, and a craft flown for the full ceiling leaves the loaded
     * region and stops reporting a position at all (measured — see {@link #travelOrNull}). What
     * keeps this honest is that the DOMINANCE assertion measures something the threshold did not
     * establish: "it moved 2 blocks along Z" says nothing about whether X moved more. Where a drive
     * must stop on its own claim, at least one assertion has to read a fact the stop did not
     * settle, or the leg is back to asserting its own exit condition.</p>
     *
     * <p>The ceiling is the poll's own — 2 ticks × 60 iterations, scaled by
     * {@link TestTimeouts#factor()} — so a frame-starved client under concurrent-fork load still
     * gets every tick it used to.</p>
     */
    private double[] travelWindow(String shipId, int key, Pattern driven, Pattern other,
                                  double drivenBefore, double otherBefore) throws Exception {
        double best = 0.0;
        double otherThere = 0.0;
        String endedBy = "window";
        bot().holdKey(key);
        try {
            int ceiling = (int) Math.ceil(2 * 60 * TestTimeouts.factor());
            for (int spent = 0; spent < ceiling && Math.abs(best) <= 2.0; spent += 2) {
                bot().waitTicks(2);
                String info = shipInfoById(shipId);
                Double d = travelOrNull(info, driven, drivenBefore);
                if (d == null) {
                    endedBy = "ship stopped reporting a position at " + spent + " ticks";
                    break;
                }
                if (Math.abs(d) > Math.abs(best)) {
                    best = d;
                    // The other axis off THE SAME reply, so the dominance claim is about one instant.
                    Double o = travelOrNull(info, other, otherBefore);
                    otherThere = o == null ? otherThere : o;
                }
            }
        } finally {
            bot().releaseKey(key);
        }
        scenario().record("travelWindow_" + key, "maxDriven=" + best + " otherThere=" + otherThere
                + " endedBy=" + endedBy);
        return new double[] {best, otherThere};
    }

    private static final Pattern POS_Y = Pattern.compile("\"posY\":(-?[0-9.E\\-]+)");
    private static final Pattern POS_Z = Pattern.compile("\"posZ\":(-?[0-9.E\\-]+)");
    private static final Pattern VEL_Y = Pattern.compile("\"velY\":(-?[0-9.E\\-]+)");
    private static final Pattern QW = Pattern.compile("\"qw\":(-?[0-9.E\\-]+)");
    private static final Pattern QX = Pattern.compile("\"qx\":(-?[0-9.E\\-]+)");
    private static final Pattern QY = Pattern.compile("\"qy\":(-?[0-9.E\\-]+)");
    private static final Pattern QZ = Pattern.compile("\"qz\":(-?[0-9.E\\-]+)");
    private static final Pattern COUNT = Pattern.compile("\"count\":(-?\\d+)");
    private static final Pattern DUMMY_ID = Pattern.compile("\"dummyId\":(-?\\d+)");

    private static final String SEAT_VARIANT = "with-pilot-seat";
    private static final String AFC_VARIANT = "with-advanced-flight-computer";

    // ── the ship oracle's own control ────────────────────────────────────────

    /**
     * The instrument this whole class rests on, exercised against the situation it exists for: TWO
     * loaded ships, and a question that must name one of them.
     *
     * <p>A detector nobody has watched fire manufactures confidence, so this asserts BOTH legs. The
     * id-keyed lookup must answer about the ship it names and about no other — including when that
     * ship is nowhere near where it was built. And the unbounded positional lookup, asked at the
     * FIRST ship's base after the second one has been parked closer to it, must be shown answering
     * with the WRONG ship: without that leg "the id form was right" would be indistinguishable from
     * "any form would have been right here".</p>
     */
    @Test
    public void aShipQuestionKeyedOnIdNamesItsOwnShipAndTheNearestFormDoesNot() throws Exception {

        final int ax = 5400, ay = 64, az = 5400;
        final int bx = 5500, by = 64, bz = 5500;

        // Two ships, built 141 blocks apart — this tier's own fixture spacing.
        exec("tp @a " + (ax + 600) + " 120 " + (az + 600) + " 0 0");
        bot().waitTicks(10);

        // Each ship's identity comes from ITS OWN creation record. Two ships in one scenario is
        // exactly the case a single mark could not tell apart — `ship_spawned` since one mark would
        // carry both records — so each assembly gets its own mark taken immediately before it, and
        // the id read after it is that assembly's ship by construction. This is also the leg the
        // control needs most: if the two ids came from a positional lookup, "the two fixtures are two
        // different ships" would be a claim made by the very instrument under test.
        Events events = events();
        long spawnMarkA = events.markInstrumented();
        String assembleA = assembleFixture(ax, ay, az, AFC_VARIANT);
        scenario().requireArranged("ship A must assemble: " + assembleA,
                assembleA.contains("\"rocketCount\":0"));
        String idA = awaitShipSpawned(events, spawnMarkA,
                "ship A's assembly must create a VS ship in the queryable registry (async spawn)");

        long spawnMarkB = events.markInstrumented();
        String assembleB = assembleFixture(bx, by, bz, AFC_VARIANT);
        scenario().requireArranged("ship B must assemble: " + assembleB,
                assembleB.contains("\"rocketCount\":0"));
        String idB = awaitShipSpawned(events, spawnMarkB,
                "ship B's assembly must create a VS ship in the queryable registry (async spawn)");

        // permaload so BOTH stay loaded with one client that cannot stand in two places. This is an
        // affordance, and it is scoped OFF the leg under test: the question here is which ship a
        // lookup names, never whether a ship stays loaded.
        exec("artest vs permaload true");
        exec("artest vs load-ships 0");
        exec("tp @a " + (ax + 0.5) + " " + (ay + 6) + " " + (az + 0.5) + " 0 0");
        bot().waitTicks(20);

        // Readiness, awaited twice — both ships must be USABLE before either leg below means
        // anything: LEG 2 needs B loaded for the nearest form to be able to answer with it, and
        // LEG 3's managed:false is only evidence about the RADIUS if A is loaded somewhere else.
        // Each wait carries that ship's OWN pre-assembly mark, the one its `awaitShipSpawned` above
        // was given: `ship_usable` fires ONCE per load and is not a state to poll, so a mark taken
        // here — after the approach that loaded both — would wait for an edge already gone by.
        awaitShipUsable(events, spawnMarkA, idA);
        awaitShipUsable(events, spawnMarkB, idB);

        scenario().record("idA", idA).record("idB", idB);
        scenario().requireArranged("the two fixtures must be two DIFFERENT ships, or this control has "
                + "nothing to discriminate (idA=" + idA + " idB=" + idB + ")", !idA.equals(idB));

        // Move A far from its build site — the situation a bounded query cannot survive and an
        // unbounded one answers wrongly. It is put NEXT TO B so "nearest to A's base" is B.
        // Both calls address A BY NAME. The positional forms were the very defect this scenario
        // exists to expose, used to arrange it: the move resolved "nearest to A's base" and the
        // unpark "nearest to A's new pose" — which is 8 blocks from B, so the unpark had two
        // candidates and no way to say which it took.
        String moved = exec("artest vs teleport-ship-by-id 0 " + idA
                + " " + (bx + 8) + " " + (by + 40) + " " + (bz + 8));
        scenario().record("teleportA", moved);
        exec("artest vs unpark-by-id 0 " + idA);
        bot().waitTicks(20);

        // LEG 1 — the id still names A, and the position it reports is A's NEW one.
        String byIdA = shipInfoById(idA);
        scenario().record("byIdA", byIdA);
        assertTrue("an id-keyed lookup must still answer about the ship it names after that ship "
                + "has moved: " + byIdA, byIdA.contains("\"managed\":true"));
        assertTrue("…and the id in the reply must be the one asked for: " + byIdA,
                idA.equals(readShipId(byIdA)));
        double aY = readDouble(byIdA, POS_Y);
        assertTrue("…and it must report where A IS now, not where it was built (posY=" + aY + ")",
                aY > ay + 20);

        // LEG 2 — the positional form is GONE, and that is now the contract.
        //
        // Two legs stood here until 2026-09-14 and they were controls: asked at A's own base with A
        // flown away, the unbounded nearest lookup answered with B (the wrong ship the id form
        // exists to prevent), and the bounded form answered about nothing at all — correct about the
        // neighbour, useless about A, which is why a radius was a mitigation and never an identity.
        // Both demonstrated a form that no longer exists: a ship's blocks live in its subspace, so
        // in the world it has a pose and no extent for a distance to be measured to, and the whole
        // family was removed. A control for a form nobody can call is not a control.
        //
        // What survives is the half that is still checkable and is the reason the removal happened:
        // the probe must REFUSE the positional spelling rather than quietly answering about
        // somebody's craft.
        String positional = exec("artest vs ship-info 0 " + ax + " " + ay + " " + az);
        scenario().record("positionalRefused", positional);
        assertTrue("asking for a ship BY POSITION must be refused, not answered: the reply must not "
                + "look like a ship report. reply=" + positional,
                !positional.contains("\"managed\":true"));
    }

    // ── migrated: VSShipClientLoadE2ETest ────────────────────────────────────

    /**
     * Migrated verbatim from {@code VSShipClientLoadE2ETest}. Every {@code ship-info} in the body
     * was a positional nearest query about a ship this scenario had just built; each is now keyed on
     * the id this scenario's own assembly recorded. Nothing else changed.
     */
    @Test
    public void assembledShipLoadsWithClientPresentAndFliesAndRotatesUnderForce() throws Exception {

        final int BX = 2200, BY = 64, BZ = 2200;

        // Keep the client FAR AWAY during assembly + spawn. VS crashes with
        // "Tried loading a ShipData that was already loaded?" if a player is near the
        // ship as it spawns (spawn-load and proximity-load collide in one server tick).
        // Assemble with no observer, let the ship settle, THEN approach so a single
        // proximity load runs.
        exec("tp @a " + (BX + 600) + " 120 " + (BZ + 600) + " 0 0");
        bot().waitTicks(10);

        // The registry's own record of the ship being added, since a mark taken before the assembly
        // was queued: THIS scenario's ship by construction — where a count incremented on a shared
        // world is answered by every neighbour that ever assembled one — and it names the ship.
        Events events = events();
        long spawnMark = events.markInstrumented();
        String assemble = assembleFixture(BX, BY, BZ, AFC_VARIANT);
        assertTrue("with VS, the AFC build must route to a ship (no rocket): " + assemble,
                assemble.contains("\"rocketCount\":0"));
        final String shipId = awaitShipSpawned(events, spawnMark,
                "assembly must create a VS ship in the queryable registry (async spawn)");
        bot().waitTicks(40); // settle before any observer approaches

        // Now walk the client ONTO the ship's projected location. A real client near the
        // ship pulls its chunks in and VS loads it — the thing testServer never does.
        exec("tp @a " + (BX + 0.5) + " " + (BY + 6) + " " + (BZ + 0.5) + " 0 0");
        bot().waitTicks(20);

        // Wait for the ship to become USABLE (the state testServer could never reach). The identity
        // is already held from the spawn record above, so this waits for the one fact that record
        // does not carry, and every question below is about THIS ship. The mark is that same
        // pre-assembly one: `ship_usable` fires once per load and cannot be polled for afterwards.
        awaitShipUsable(events, spawnMark, shipId);
        // The event record names the ship and its dimension but carries NO position, so the
        // baseline coordinate still comes from an id-keyed ship-info.
        double zBefore = readDouble(shipInfoById(shipId), POS_Z);
        assertTrue("a VS ship must LOAD with a client present", !Double.isNaN(zBefore));

        // Now that it is loaded + physics-enabled, command a straight-UP velocity realized
        // as FORCE (the working path — a raw setpoint does nothing). Up isolates the result
        // from ground friction: the only thing to overcome is gravity, and the controller's
        // deadbeat force (F = mass·accel, clamped to thrust authority) exceeds it. Re-command
        // each tick and POLL for the climb — VS's physics-thread activation after a load can
        // lag a few ticks, so drive until it rises (bounded) rather than a fixed window.
        double yBefore = readDouble(shipInfoById(shipId), POS_Y);
        double yAfter = yBefore;
        double maxClimb = 0.0;
        double velY = 0.0;
        // The loop DRIVES; it no longer decides. Its exit condition used to be `yAfter - yBefore
        // <= 1.5`, i.e. the very claim the assertion below makes — so the assertion could not fail
        // except by the loop running out, and its message then blamed the force controller for a
        // timeout. What is measured is the MAXIMUM climb over the window, not the last sample: a
        // quantity read at one instant can have come back, and the window's extremum cannot.
        for (int i = 0; i < FLIGHT_WINDOW_TICKS; i++) {
            String cmd = exec("artest vs force-vel-by-id 0 " + shipId + " 0 8 0");
            assertTrue("force-vel must reach THIS ship's own flight computer: " + cmd,
                    cmd.contains("\"commanded\":true"));
            bot().waitTicks(1);
            String info = shipInfoById(shipId);
            yAfter = readDouble(info, POS_Y);
            velY = readDouble(info, VEL_Y);
            maxClimb = Math.max(maxClimb, yAfter - yBefore);
        }

        // Force actually integrates into motion: the ship must climb. (Model A's setLinearVelocity
        // left this flat with velY≈1.8; a force controller lifts it.)
        assertTrue("commanded +Y velocity (via force) must lift the loaded ship "
                        + "(yBefore=" + yBefore + " yAfter=" + yAfter + " maxClimb=" + maxClimb
                        + " velY=" + velY + ")",
                maxClimb > 1.0);

        // The same controller must also ROTATE the ship: command a yaw angular velocity,
        // realized as TORQUE (linear zeroed -> the ship hovers while it turns). The ship's
        // body->world attitude quaternion must move meaningfully off where it started. Poll
        // for the turn (bounded) for the same activation-lag robustness.
        double[] qBefore = readQuat(shipInfoById(shipId));
        double dot = 1.0;
        double minDot = 1.0;
        // The MINIMUM over the window is the measurement, and here that is not a refinement — it is
        // the only correct reading. A ship under a held 1 rad/s yaw command passes through every
        // attitude, so |dot| FALLS and then RISES back toward 1.0 as it comes round; a last-sample
        // read of a full turn says "unmoved". The old loop hid that by exiting the moment the dot
        // dropped — on the same predicate the assertion then restated.
        for (int i = 0; i < FLIGHT_WINDOW_TICKS; i++) {
            String cmd = exec("artest vs force-rot-by-id 0 " + shipId + " 0 1.0 0");
            assertTrue("force-rot must reach THIS ship's own flight computer: " + cmd,
                    cmd.contains("\"commanded\":true"));
            bot().waitTicks(1);
            double[] qNow = readQuat(shipInfoById(shipId));
            // |dot| of two unit quaternions is cos(halfAngle); < 0.98 => rotated by more than ~23°.
            dot = Math.abs(qBefore[0] * qNow[0] + qBefore[1] * qNow[1]
                    + qBefore[2] * qNow[2] + qBefore[3] * qNow[3]);
            minDot = Math.min(minDot, dot);
        }
        assertTrue("commanded yaw (via torque) must rotate the loaded ship "
                        + "(min |quat dot| over the window=" + minDot + ", last=" + dot
                        + ", 1.0 = unmoved)",
                minDot < 0.98);

        // ATTITUDE HOLD: command an absolute target orientation (90° yaw about world Y) and the
        // controller must drive the ship's attitude TO it and converge — the interface Free
        // Flight feeds (its per-tick target quaternion). Poll for convergence (bounded).
        final double[] target = {0.70710678, 0.0, 0.70710678, 0.0}; // {w,x,y,z}
        double convDot = 0.0;
        // The FINAL value is the measurement here, and unlike the two windows above that is the
        // right reading: the claim is that the controller CONVERGES and HOLDS, so a maximum along
        // the way would pass on a ship that swung through the target and carried on. The loop drives
        // the whole window and no longer exits on the predicate the assertion restates.
        for (int i = 0; i < ATTITUDE_WINDOW_TICKS; i++) {
            String cmd = exec("artest vs point-by-id 0 " + shipId
                    + " " + target[0] + " " + target[1] + " " + target[2] + " " + target[3]);
            assertTrue("point must reach THIS ship's own flight computer: " + cmd,
                    cmd.contains("\"commanded\":true"));
            bot().waitTicks(1);
            double[] q = readQuat(shipInfoById(shipId));
            convDot = Math.abs(q[0] * target[0] + q[1] * target[1] + q[2] * target[2] + q[3] * target[3]);
        }
        assertTrue("attitude-hold must converge the ship to the commanded orientation and still be"
                        + " there at the end of the window (|dot to target|=" + convDot
                        + ", 1.0 = exact)",
                convDot > 0.98);

        // FULL FREE FLIGHT PATH: hand the flight computer a held pilot input. Its server tick
        // reads the ship's attitude, runs FreeFlightPhysics, and publishes to the controller —
        // no probe touches the ship command directly here. A throttle input must MOVE the ship
        // through that path (throttle -> body axis -> world velocity -> controller force). We assert
        // total displacement (not just altitude): the ship is left tilted by the earlier rotate
        // and attitude phases, so "body up" is not world up — moving at all is the contract here.
        // Hand the ship back to its own flight computer first. The three rungs above drove this
        // computer's PROBE channel, which deliberately outranks the pilot channel — so leaving it in
        // force would make the FF rung measure the attitude hold that is still commanded, not the
        // throttle. Asserted: a release nobody checks is indistinguishable from no release.
        String released = exec("artest vs force-clear-by-id 0 " + shipId);
        scenario().requireArranged("the probe command must be released before the FF path is measured: "
                + released, released.contains("\"cleared\":true"));

        double[] pBefore = readVec(shipInfoById(shipId));
        double[] at = pBefore;
        double disp = 0.0;
        double maxDisp = 0.0;
        // Drives the whole window and measures the MAXIMUM displacement: the exit condition used to
        // be the assertion below, and a ship that moves out and drifts back reads zero at the end.
        for (int i = 0; i < FLIGHT_WINDOW_TICKS; i++) {
            // Addressed by SHIP and re-issued from its freshest pose each iteration. The input used to
            // go to a server-wide static, which no pilot has; re-sending is also what a real pilot's
            // client does every tick, and it keeps the address on a ship that is by now moving.
            String cmd = exec("artest vs ff-input-by-id 0 " + shipId
                    + " 0 1 0 0 0 0"); // throttleVertical = full up
            assertTrue("the throttle must reach this ship's own flight computer: " + cmd,
                    cmd.contains("\"afcResolved\":true"));
            bot().waitTicks(1);
            double[] p = readVec(shipInfoById(shipId));
            at = p;
            double dx = p[0] - pBefore[0], dy = p[1] - pBefore[1], dz = p[2] - pBefore[2];
            disp = Math.sqrt(dx * dx + dy * dy + dz * dz);
            maxDisp = Math.max(maxDisp, disp);
        }
        assertTrue("a Free Flight throttle input must move the ship through the AFC's FF path "
                        + "(max displacement over the window=" + maxDisp + ", last=" + disp + ")",
                maxDisp > 1.0);
    }

    // ── migrated: VSShipNearbyObserverNoCrashE2ETest ─────────────────────────

    /**
     * Migrated from {@code VSShipNearbyObserverNoCrashE2ETest}. Its two gates were whole-dimension
     * counts, which on a shared world are answered by a neighbour's ship before this scenario builds
     * anything: "appeared in the registry" is now this scenario's own {@code ship_spawned} record,
     * and "loaded" is the id that record named resolving to a managed ship.
     */
    @Test
    public void assemblingWithAnObserverAtThePadDoesNotCrashVs() throws Exception {

        final int BX = 2400, BY = 64, BZ = 2400;

        // Put the observer AT the build site and keep it there through assembly + spawn — the
        // double-load window the sister test avoids. If the guard is absent, VS faults here.
        exec("tp @a " + (BX + 0.5) + " " + (BY + 6) + " " + (BZ + 0.5) + " 0 0");
        bot().waitTicks(10);

        Events events = events();
        long spawnMark = events.markInstrumented();
        String assemble = assembleFixture(BX, BY, BZ, AFC_VARIANT);
        assertTrue("with VS, the AFC build must route to a ship (no rocket): " + assemble,
                assemble.contains("\"rocketCount\":0"));

        // The ship must appear in the queryable registry (async spawn did not fault) — its own
        // record, which also NAMES it: the identity every question below is keyed on.
        String shipId = awaitShipSpawned(events, spawnMark, "assembly with an observer present must"
                + " still create a VS ship — a fault in the double-load window would prevent it");

        // ... and it must LOAD (the observer never left, so the proximity load runs in the same
        // window as the spawn load). Reaching LOADED with the observer present through spawn is
        // the no-crash contract: the guard turned the illegal double-load into a no-op. Awaited from
        // THIS scenario's own pre-assembly mark — a load that happened before this assembly cannot
        // answer it. The budget stays this site's own, longer than the tier's default, unchanged by
        // this move: the old parameter was 60 five-tick polls, which is 300 ticks in the units the
        // event wait takes.
        awaitShipUsable(events, spawnMark, shipId, 300);
        assertTrue("a VS ship assembled under a nearby observer must load without VS faulting "
                        + "(id=" + shipId + ", registry: " + events.since(spawnMark, "ship_spawned") + ")",
                shipInfoById(shipId).contains("\"managed\":true"));
    }

    // ── migrated: VSShipSeatDriveE2ETest ─────────────────────────────────────

    /**
     * Migrated from {@code VSShipSeatDriveE2ETest}: the server-side bisection of the seat &rarr; AFC
     * &rarr; force path. Body unchanged apart from the ship oracle.
     */
    @Test
    public void seatPathResolvesAfcAndFliesTheShip() throws Exception {

        final int BX = 2600, BY = 64, BZ = 2600;

        // Assemble far from any observer (double-load window), then approach to load.
        exec("tp @a " + (BX + 600) + " 120 " + (BZ + 600) + " 0 0");
        bot().waitTicks(10);

        Events events = events();
        long spawnMark = events.markInstrumented();
        String assemble = assembleFixture(BX, BY, BZ, SEAT_VARIANT);
        assertTrue("a with-pilot-seat build must route to a ship (no rocket): " + assemble,
                assemble.contains("\"rocketCount\":0"));
        // The identity, off this scenario's own creation record — not re-derived from the base below.
        final String shipId = awaitShipSpawned(events, spawnMark, "assembly must create a VS ship");
        bot().waitTicks(40);

        exec("tp @a " + (BX + 0.5) + " " + (BY + 6) + " " + (BZ + 0.5) + " 0 0");
        bot().waitTicks(20);

        // Readiness only: the approach above must have got the physics object loaded. Awaited on
        // production's own event, from the pre-assembly mark — it fires once per load, so a later
        // mark would wait for an edge that has already gone by.
        awaitShipUsable(events, spawnMark, shipId);
        // The event record carries no position; the baseline comes from an id-keyed ship-info.
        double yBefore = readDouble(shipInfoById(shipId), POS_Y);

        // Server-side seat drive: the seat must resolve its AFC, and a full-up throttle through
        // the seat->AFC per-tile path must lift the ship (isolates ground friction: up only).
        double yAfter = yBefore;
        double maxSeatClimb = 0.0;
        String lastSeat = "";
        // Drives the whole window; the exit condition was the assertion below. Measures the MAXIMUM
        // climb, so a ship that rises and settles back is not read as one that never rose.
        for (int i = 0; i < FLIGHT_WINDOW_TICKS; i++) {
            // BY ID: this world is shared with every other scenario in the class, and the
            // unaddressed `seat-input` takes whichever pilot seat it lists first — a command that
            // answers afcResolved:true from somebody else's ship while this one sits still.
            lastSeat = exec("artest vs seat-input-by-id 0 " + shipId + " 0 1 0 0 0 0"); // full up
            assertTrue("seat-input must find THIS ship's pilot seat: " + lastSeat,
                    lastSeat.contains("\"seatFound\":true"));
            assertTrue("the pilot seat must resolve its linked flight computer (offset intact "
                            + "after VS relocation): " + lastSeat,
                    lastSeat.contains("\"afcResolved\":true"));
            bot().waitTicks(1);
            yAfter = readDouble(shipInfoById(shipId), POS_Y);
            maxSeatClimb = Math.max(maxSeatClimb, yAfter - yBefore);
        }
        assertTrue("a throttle driven through the pilot seat -> AFC -> force path must lift the ship "
                        + "(yBefore=" + yBefore + " yAfter=" + yAfter + " maxClimb=" + maxSeatClimb
                        + ", lastSeat=" + lastSeat + ")",
                maxSeatClimb > 1.0);
    }

    // ── migrated: VSShipPilotKeysE2ETest ─────────────────────────────────────

    /**
     * Migrated from {@code VSShipPilotKeysE2ETest} — the full-path pilot e2e. This is the scenario
     * that makes the id keying compulsory rather than tidy: it holds the lift key for sixty
     * uninterrupted ticks to climb clear of the terrain, which puts the ship far outside any bound
     * a nearest-lookup could carry.
     *
     * <p>Its seat lookup is NARROWER, not scoped. {@code vs seat-mount} takes the FIRST pilot seat in
     * the world's loaded-tile list with no position filter, so on a shared world it mounts whichever
     * ship happens to be listed first; the anchored {@code vs find-seat} resolves through
     * {@code VSBridge.shipyardBoundsAt}, which answers for the ship NEAREST the anchor — unbounded,
     * over the registry. This paragraph claimed it "resolves the seat inside the ship at a given
     * world anchor", which is the guarantee the first {@code @Test} of this very class exists to
     * disprove. The identity-keyed form is {@code find-seat <dim> id <shipUuid>}.</p>
     */
    @Test
    public void seatedPilotFliesShipTravelsWithItAndCameraLocksToNose() throws Exception {

        final int BX = 2800, BY = 64, BZ = 2800;

        exec("tp @a " + (BX + 600) + " 120 " + (BZ + 600) + " 0 0");
        bot().waitTicks(10);

        Events events = events();
        long spawnMark = events.markInstrumented();
        String assemble = assembleFixture(BX, BY, BZ, SEAT_VARIANT);
        assertTrue("a with-pilot-seat build must route to a ship: " + assemble,
                assemble.contains("\"rocketCount\":0"));
        // The identity, off this scenario's own creation record. It is fixed HERE, before the ship has
        // moved a block, and it stays valid through the sixty-tick climb below — the flight that no
        // positional bound survives.
        final String shipId = awaitShipSpawned(events, spawnMark, "assembly must create a VS ship");
        bot().waitTicks(40);

        // Approach so the client loads the ship (and its seat/AFC tiles).
        exec("tp @a " + (BX + 0.5) + " " + (BY + 6) + " " + (BZ + 0.5) + " 0 0");
        bot().waitTicks(20);

        // Readiness only: the approach must have got the physics object loaded. Awaited on
        // production's own event, from the pre-assembly mark — it fires once per load, so a later
        // mark would wait for an edge that has already gone by.
        awaitShipUsable(events, spawnMark, shipId);
        // The event record carries no position, so the at-rest pose — the climb's baseline, and what
        // gets recorded for the report — still comes from an id-keyed ship-info.
        String atRest = shipInfoById(shipId);
        double yBefore = readDouble(atRest, POS_Y);
        scenario().record("shipAtRest", atRest);

        // Sit the bot on THIS ship's pilot seat: resolve the seat inside the ship this scenario
        // NAMES — the anchored form resolved the yard nearest a point, which is a different ship
        // whenever a neighbour's is nearer — then mount that subspace block.
        String found = exec("artest vs find-seat 0 id " + shipId);
        Matcher sm = Pattern.compile("\"seatX\":(-?\\d+),\"seatY\":(-?\\d+),\"seatZ\":(-?\\d+)")
                .matcher(found);
        assertTrue("find-seat must resolve THIS ship's subspace seat: " + found, sm.find());
        String mountInfo = exec("artest vs seat-mount-at 0 " + sm.group(1) + " " + sm.group(2)
                + " " + sm.group(3));
        Matcher dm = DUMMY_ID.matcher(mountInfo);
        assertTrue("seat-mount-at must report a dummy id: " + mountInfo, dm.find());
        String mount = exec("artest player mount-entity " + dm.group(1));
        assertTrue("bot must mount the seat dummy: " + mount,
                mount.contains("\"mounted\":true"));
        bot().waitTicks(10); // let the mount replicate and the client recognise the pilot seat

        // Baseline the CLIENT pilot position BEFORE the climb: the mount the bot rides (its dummy)
        // and the player camera. A pilot glued to the ship rises with it; a detached one stays here.
        double riderYBefore = bot().reportRidingEntity().get("posY").getAsDouble();
        double camYBefore = bot().reportState().get("playerY").getAsDouble();

        // Drive REAL keys: hold vertical-up. The client samples it, sends it to the seat, and the
        // AFC lifts the ship. Up isolates from ground friction; poll for the climb (bounded).
        // THE CLIMB IS THIS LEG'S ARRANGEMENT, and that is the correction. Its own name says what it
        // pins — the pilot TRAVELS with the ship and the camera LOCKS to the nose — and both of
        // those need a ship that is still there to be read. So the key is held only until the craft
        // is unambiguously airborne and is then RELEASED: flying on is not part of the claim.
        //
        // Measured, and it is why this leg is not a fixed window like the four command legs above.
        // Converting it to one (the poll's own 200-tick ceiling, spent in full) reds the scenario
        // with `{"managed":false,"id":…}`: the poll released the key the moment the ship had risen,
        // while a full window keeps commanding it, and a ship under a held throttle for 200 ticks
        // leaves the loaded region. The safety argument for "window = the poll's ceiling" holds for
        // an assertion's THRESHOLD and not for a STIMULUS that goes on acting.
        //
        // What makes this not the old defect: the drive is typed as ARRANGEMENT and the contract
        // assertions below read something it did not establish — the rider's climb tracking the
        // ship's, the camera's, and the nose lock. A climb that never happens fails as an
        // arrangement, which is what it would be.
        double maxLift = 0.0;
        double yAfter = yBefore;
        bot().holdKey(Keyboard.KEY_R); // flightVerticalUp
        try {
            int ceiling = (int) Math.ceil(2 * 100 * TestTimeouts.factor());
            for (int spent = 0; spent < ceiling && maxLift <= 1.5; spent += 2) {
                bot().waitTicks(2);
                Double climbed = travelOrNull(shipInfoById(shipId), POS_Y, yBefore);
                if (climbed == null) {
                    scenario().record("liftEndedBy", "ship no longer reporting a position at "
                            + spent + " ticks; maxLift=" + maxLift);
                    break;
                }
                yAfter = yBefore + climbed;
                maxLift = Math.max(maxLift, climbed);
            }
        } finally {
            bot().releaseKey(Keyboard.KEY_R);
        }
        scenario().record("maxLift", maxLift);

        scenario().requireArranged("holding the vertical-up key while seated must get the ship "
                        + "airborne through the FULL client path (key -> packet -> seat -> AFC -> "
                        + "force) before the pilot can be asked to travel with it: yBefore="
                        + yBefore + " yAfter=" + yAfter + " maxLift=" + maxLift,
                maxLift > 1.0);

        // --- The seated pilot must TRAVEL with the ship (client-observed). Read the CLIENT rider +
        // camera again: both must have climbed, and the rider's climb must track the server ship's.
        // Before the fix that glues the seat dummy to the moving ship, the dummy stays at spawn while
        // the ship departs, so these client deltas would be ~0 even though the server ship moved.
        bot().waitTicks(6); // let the client ship transform settle at the new altitude
        String afterSettle = shipInfoById(shipId);
        double serverYAfter = readDouble(afterSettle, POS_Y);
        double riderYAfter = bot().reportRidingEntity().get("posY").getAsDouble();
        double camYAfter = bot().reportState().get("playerY").getAsDouble();
        scenario().record("shipAfterSettle", afterSettle)
                .record("riderBeforeAfter", riderYBefore + " -> " + riderYAfter)
                .record("serverBeforeAfter", yBefore + " -> " + serverYAfter);
        assertTrue("the CLIENT-rendered rider must climb with the ship (it stayed behind): "
                        + "riderYBefore=" + riderYBefore + " riderYAfter=" + riderYAfter,
                riderYAfter - riderYBefore > 1.0);
        assertTrue("the pilot's CLIENT camera must climb with the ship: camYBefore=" + camYBefore
                        + " camYAfter=" + camYAfter,
                camYAfter - camYBefore > 1.0);
        assertTrue("the client rider climb must TRACK the server ship climb (client="
                        + (riderYAfter - riderYBefore) + " server=" + (serverYAfter - yBefore) + ")",
                Math.abs((riderYAfter - riderYBefore) - (serverYAfter - yBefore)) < 3.0);

        // --- The OTHER TWO translation axes, in world coordinates. The vertical key above proves
        // exactly ONE channel of the pilot path; nose and lateral are separate fields of the same
        // packet and separate components of the body-frame setpoint, so a channel that never leaves
        // the client — a binding whose conflict context is off, a field dropped on the wire — is
        // invisible to a vertical-only test. Q/E in particular share their default keys with vanilla
        // drop/inventory and reach the craft only because the pilot-seat conflict context suppresses
        // the vanilla action, a gate the always-active W/S do not carry.
        //
        // A freshly assembled VS ship carries the IDENTITY attitude and nothing above has commanded a
        // rotation, so its nose is world +Z and its right is world +X: which axis a key drives can be
        // read straight off the world position, without asking the ship where it is pointing. It is
        // checked PER AXIS on purpose — "the ship moved" would go green on a key that drove the wrong
        // axis entirely. (This runs BEFORE the mouse leg below, which commands roll and would take
        // body-right off world +X.)
        //
        // Climb clear of the terrain first and then CUT: Flight Assist is a cruise control, so
        // releasing the vertical key leaves the ship climbing, and a horizontal leg flown at pad
        // height could be stopped by a hillside rather than by the ship's own controls.
        bot().holdKey(Keyboard.KEY_R);
        bot().waitTicks(60);
        bot().releaseKey(Keyboard.KEY_R);
        cutAndSettle();

        final double xBeforeNose = readDouble(shipInfoById(shipId), POS_X);
        final double zBeforeNose = readDouble(shipInfoById(shipId), POS_Z);
        // A WINDOW, and the axis pair is taken from ONE reply per sample. Two changes, both about
        // what the old form could not say: the poll exited on `dz > 2.0` while the first assertion
        // claimed `dz > 1.0`, so that half could not fail; and the dominance check compared a dz
        // from the poll's exit against a dx read afterwards, attributing one moment's travel to
        // another's. The sample kept is the one with the largest |dz|, and its dx comes off the same
        // ship-info as its dz.
        double[] nose = travelWindow(shipId, Keyboard.KEY_W, POS_Z, POS_X, zBeforeNose, xBeforeNose);
        cutAndSettle();
        assertTrue("holding the FORWARD key while seated must drive the ship along its NOSE — world "
                        + "+Z on an identity-attitude ship — through the full client path. "
                        + "zBefore=" + zBeforeNose + " maxDz=" + nose[0] + " dxThere=" + nose[1],
                nose[0] > 1.0);
        assertTrue("…and it must be the NOSE axis it drives, not merely some motion: the world-Z "
                        + "travel must dominate the world-X travel AT THE SAME INSTANT. dz="
                        + nose[0] + " dx=" + nose[1],
                Math.abs(nose[0]) > Math.abs(nose[1]));

        final double xBeforeStrafe = readDouble(shipInfoById(shipId), POS_X);
        final double zBeforeStrafe = readDouble(shipInfoById(shipId), POS_Z);
        // The same window, with the axes the other way round.
        double[] strafe = travelWindow(shipId, Keyboard.KEY_Q, POS_X, POS_Z,
                xBeforeStrafe, zBeforeStrafe);
        cutAndSettle();
        assertTrue("holding the STRAFE key while seated must drive the ship along its LATERAL axis — "
                        + "world +X on an identity-attitude ship. That key is Q, which vanilla binds "
                        + "to drop and which reaches the craft only because the pilot-seat conflict "
                        + "context suppresses the vanilla action; a red here is that suppression, the "
                        + "strafe field on the wire, or the axis it lands on. xBefore=" + xBeforeStrafe
                        + " maxDx=" + strafe[0] + " dzThere=" + strafe[1],
                strafe[0] > 1.0);
        assertTrue("…and it must be the LATERAL axis it drives: the world-X travel must dominate "
                        + "the world-Z travel AT THE SAME INSTANT. dx=" + strafe[0]
                        + " dz=" + strafe[1],
                Math.abs(strafe[0]) > Math.abs(strafe[1]));

        // --- The mouse must STEER the ship, never free-look the camera (the FF cockpit contract).
        // The ship is now hovering roughly upright. Inject a hard SIDEWAYS mouse look each tick
        // (horizontal mouse -> roll cursor; pure roll leaves the nose direction fixed). A camera that
        // free-looks would accumulate tens of degrees off; a nose-locked one is re-pinned to the
        // (unmoved) ship nose every client tick. Read BOTH the client camera and the server ship
        // attitude, converting the latter to a heading with the SAME quat->Euler the lock uses.
        double camYawBefore = bot().reportState().get("playerYaw").getAsDouble();
        for (int i = 0; i < 6; i++) {
            JsonObject st = bot().reportState();
            bot().setLook(st.get("playerYaw").getAsFloat() + 30f, st.get("playerPitch").getAsFloat());
            bot().waitTicks(1);
        }
        bot().waitTicks(4);
        double camYawAfter = bot().reportState().get("playerYaw").getAsDouble();
        float shipNoseYaw = shipNoseYaw(shipInfoById(shipId));
        assertTrue("a hard sideways mouse look must NOT free-look the camera — the view stays locked "
                        + "(camYawBefore=" + camYawBefore + " camYawAfter=" + camYawAfter + ")",
                angDiff(camYawAfter, camYawBefore) < 15.0);
        assertTrue("the CLIENT camera yaw must be LOCKED to the ship nose, not where the mouse pointed "
                        + "(camYawAfter=" + camYawAfter + " shipNose=" + shipNoseYaw + ")",
                angDiff(camYawAfter, shipNoseYaw) < 12.0);

        exec("artest player dismount");
    }

    // ── shared helpers ───────────────────────────────────────────────────────

    /**
     * Zero the cruise setpoint and let the ship come to rest. Flight Assist RETAINS a released
     * throttle, so without this each leg would measure the one before it still coasting.
     */
    private void cutAndSettle() throws Exception {
        bot().holdKey(Keyboard.KEY_X);          // throttle cut
        bot().waitTicks(40);
        bot().releaseKey(Keyboard.KEY_X);
        bot().waitTicks(10);
    }

    /** The ship nose heading (MC yaw, degrees) from the attitude quaternion in {@code vs ship-info},
     *  using the SAME quat&rarr;Euler conversion the production camera lock uses (no convention drift). */
    private float shipNoseYaw(String shipInfoJson) {
        return FreeFlightPhysics.eulerFromQuat(new FreeFlightPhysics.Quat(
                readDouble(shipInfoJson, QW), readDouble(shipInfoJson, QX),
                readDouble(shipInfoJson, QY), readDouble(shipInfoJson, QZ)))[0];
    }

    /** Wrapped angular distance on the circle, degrees in [0, 180]. */
    private static double angDiff(double a, double b) {
        return Math.abs(((a - b + 540) % 360) - 180);
    }

    private int count(String sub) throws Exception {
        Matcher m = COUNT.matcher(exec("artest vs " + sub + " 0"));
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    private double[] readVec(String shipInfoJson) {
        return new double[]{readDouble(shipInfoJson, POS_X), readDouble(shipInfoJson, POS_Y),
                readDouble(shipInfoJson, POS_Z)};
    }

    private double[] readQuat(String shipInfoJson) {
        return new double[]{readDouble(shipInfoJson, QW), readDouble(shipInfoJson, QX),
                readDouble(shipInfoJson, QY), readDouble(shipInfoJson, QZ)};
    }

    private double readDouble(String json, Pattern p) {
        Matcher m = p.matcher(json);
        assertTrue("expected a number in: " + json, m.find());
        return Double.parseDouble(m.group(1));
    }

    private String assembleFixture(int baseX, int baseY, int baseZ, String variant) throws Exception {
        int cx1 = (baseX - 2) >> 4, cz1 = (baseZ - 2) >> 4;
        int cx2 = (baseX + 7) >> 4, cz2 = (baseZ + 7) >> 4;
        assertTrue("chunk warmup failed",
                exec("artest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2)
                        .contains("\"ok\":true"));
        assertTrue("pre-clear failed",
                exec("artest fill 0 " + (baseX - 2) + " " + (baseY + 1) + " " + (baseZ - 2)
                        + " " + (baseX + 7) + " " + (baseY + 10) + " " + (baseZ + 7) + " minecraft:air")
                        .contains("\"ok\":true"));
        String fixture = exec("artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ
                + " " + variant);
        assertTrue("fixture (" + variant + ") failed: " + fixture, fixture.contains("\"ok\":true"));
        Matcher bp = BUILDER_POS.matcher(fixture);
        assertTrue("fixture missing builderPos: " + fixture, bp.find());
        return exec("artest rocket assemble 0 " + bp.group(1) + " " + bp.group(2) + " " + bp.group(3));
    }
}
