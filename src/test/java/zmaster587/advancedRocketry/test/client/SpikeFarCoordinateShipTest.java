package zmaster587.advancedRocketry.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;

import org.junit.Test;
import org.lwjgl.input.Keyboard;
import zmaster587.advancedRocketry.test.SeatMount;
import zmaster587.advancedRocketry.test.Events;
import zmaster587.advancedRocketry.test.Reply;
import zmaster587.advancedRocketry.test.GameTicks;
import zmaster587.advancedRocketry.test.ShipIdentity;
import zmaster587.advancedRocketry.test.ShipInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import zmaster587.advancedRocketry.test.FixtureSite;

import static org.junit.Assert.assertTrue;

/**
 * SPIKE — does a tier-2 ship survive a far world coordinate the way a bare player does?
 *
 * <p>A player walks, stands, collides, holds a sub-block position and is rendered without a quantum
 * out to 24M. None of that transfers: a ship's blocks live in the shipyard subspace while its pose
 * lives in the world, and the two are bridged by a transform of its own. So the ship is the last
 * subject that could still move the ratified half-cell, and this is the leg that measures it.</p>
 *
 * <h2>Why this ASSEMBLES at the coordinate instead of teleporting a ship to it</h2>
 * {@code VSShipExtremeCoordinatesE2ETest} reached extreme <b>Y</b> by rigid-teleporting an assembled
 * ship, and left the extreme-|X| leg unautomated for a reason recorded in its own javadoc: after a
 * SECOND relocation the physics goes inert — neither a pilot key nor a velocity setpoint moves the
 * ship — and the pilot-key path dies after a dismount and re-seat across the map. Those are
 * relocation-SEQUENCE findings. Teleporting to |X| would re-run straight into them and produce a red
 * that says nothing about the coordinate.
 *
 * <p>So the stimulus changes rather than the measurement: the fixture is built, and the ship
 * assembled, AT the far coordinate. There is exactly one relocation in the whole leg — the player's,
 * through {@code far-tp} — and the ship is never moved at all.</p>
 *
 * <h2>Where the arena sits, and why</h2>
 * {@code Z = }{@value #ARENA_Z}, below the physics mod's reserved quadrant
 * ({@code chunkX >= 318401 && chunkZ >= -1599}). Above that Z the quadrant would swallow the arena at
 * 16M: the blocks would be shipyard blocks, the player's delivery would be cancelled silently, and
 * the leg would measure the reservation instead of the coordinate.
 *
 * <h2>Acceptance, stated before the run</h2>
 * The {@code x = 0} rung is the control, assembled and flown in the same run by the same commands.
 * At every rung:
 * <ol>
 *   <li>assembly must produce a VS ship (the ship count rises), and it must LOAD ({@code managed});</li>
 *   <li>the pilot seat must be findable and mountable — crew retention through the far assembly;</li>
 *   <li>a real held vertical-up key must lift the server ship by more than
 *       {@value #MIN_LIFT_BLOCKS} block;</li>
 *   <li>the CLIENT-rendered rider must track that climb to within {@value #TRACK_TOLERANCE} blocks —
 *       a transform that has lost precision shows up here as divergence, and nowhere earlier.</li>
 * </ol>
 *
 * <p><b>Designed to come back NO.</b> A ship that will not assemble, will not load, will not lift or
 * whose rider drifts at 16M is a finding against the ratified half-cell, and the number moves.</p>
 */
public class SpikeFarCoordinateShipTest extends AbstractClientE2ETest {

    private static final String BUILDER_POS = "builderPos";
    private static final String COUNT = "count";
    private static final String DUMMY_ID = "dummyId";

    /** One command, then this many samples this many ticks apart, watching for motion to cease. */
    private static final int SURVIVAL_SAMPLES = 40;
    private static final int SURVIVAL_SAMPLE_TICKS = 10;
    /** Blocks per sample below which the ship counts as no longer being driven. */
    private static final double SURVIVAL_STEP_EPSILON = 0.05d;

    /** The control, then the ratified half-cell. 24M is not carried: one far rung is the question. */
    private static final int[] X_LADDER = {0, 16_000_000};

    /** Below the reserved quadrant's Z edge (Z ≥ -25,584), so the arena is ordinary world at every X. */
    private static final int ARENA_Z = -100_000;
    /** Well above sea level: 16M is ocean, and a fixture built into water is not a fixture. */
    /**
     * The arena's base Y: the OPEN-AIR band. It was a hand-picked 140 until 2026-09-14 — already
     * clear of terrain, and that is exactly the point: two numbers meaning "high enough to be above
     * whatever is down there" is one number too many, and the other one moves when the band does.
     * The stone pad this class lays a block below it stays; at x=16M the surface is ocean, and a
     * pad the test builds is what keeps the fixture out of water whatever the band is set to.
     */
    private static final int BASE_Y = FixtureSite.OPEN_AIR_Y;

    private static final String VARIANT = "with-pilot-seat";
    private static final double MIN_LIFT_BLOCKS = 1.0d;
    private static final double TRACK_TOLERANCE = 3.0d;
    private static final double ARRIVAL_TOLERANCE = 1.0d;
    private static final int DELIVERY_ATTEMPTS = 4;
    /** 5-tick polls the CLIENT gets to agree it is riding the seat the server already mounted it on. */
    private static final int RIDING_ATTEMPTS = 24;

    private String exec(String cmd) throws Exception {
        return String.join("\n", serverClient().execute(cmd));
    }

    @Test
    public void doesAShipAssembleLoadAndFlyFarFromTheOrigin() throws Exception {

        bot().waitForWorld();
        exec("gamerule sendCommandFeedback false");
        exec("gamerule logAdminCommands false");
        exec("gamerule doMobSpawning false");
        exec("gamerule doDaylightCycle false");
        exec("gamerule doWeatherCycle false");
        exec("weather clear");
        // Headless has no player holding a distant ship loaded, and the client is one player in one
        // place while two ships exist in this run — which a test server now answers for every
        // scenario, from the moment the probes register.

        Map<Integer, String> verdicts = new LinkedHashMap<>();
        // Which ship answered for which rung. Two rungs that report the same id measured one subject
        // twice, and two rungs agreeing to four decimals is what that looks like from the outside.
        Map<Integer, String> shipIds = new LinkedHashMap<>();
        List<String> report = new ArrayList<>();
        List<String> inconclusive = new ArrayList<>();
        StringBuilder out;

        try {
            for (int x : X_LADDER) {
                int before = count("ship-count-all");

                String arrangement = arrange(x);
                if (arrangement != null) {
                    inconclusive.add("x=" + x + " " + arrangement);
                    continue;
                }

                String assemble = assembleFixture(x);
                if (assemble == null) {
                    inconclusive.add("x=" + x + " the fixture did not build or did not assemble"
                            + " (arrangement, not the coordinate)");
                    continue;
                }
                // absence is the answer: this SWEEPS coordinates and records a verdict per
                // one, so a probe that answered nothing is this row's failure and not the
                // end of the sweep.
                if (!(Reply.of(assemble).integerOr("rocketCount", Integer.MIN_VALUE) == 0)) {
                    verdicts.put(x, "the build did not route to a SHIP: " + oneLine(assemble));
                    continue;
                }

                int after = before;
                for (int i = 0; i < 40 && after <= before; i++) {
                    bot().waitTicks(5);
                    after = count("ship-count-all");
                }
                if (after <= before) {
                    verdicts.put(x, "assembly created no VS ship (count " + before + " -> " + after + ")");
                    continue;
                }

                // Put the pilot on the ship. This is the ONLY relocation in the leg, and it is the
                // player's, not the ship's.
                String delivery = deliver(x);
                if (delivery != null) {
                    inconclusive.add("x=" + x + " " + delivery);
                    continue;
                }

                // This rung's ship, by the name its assembler minted. The identity used to be read
                // back out of a bounded lookup at the rung's own spot — defensible only as long as
                // that spot held one hull, which is a premise about the arrangement rather than
                // about the lookup. Every later reading goes by the id, which has no distance term.
                String shipId = ShipIdentity.awaitPhysicsIdOf(this::exec, 0,
                        ShipIdentity.nameFromAssembly(assemble), 40, () -> bot().waitTicks(5));
                double y0 = Double.NaN;
                String lastInfo = "";
                for (int i = 0; i < 40 && Double.isNaN(y0); i++) {
                    bot().waitTicks(5);
                    lastInfo = exec("artest vs ship-info 0 id " + shipId);
                    if (ShipInfo.isLoaded(lastInfo)) {
                        y0 = ShipInfo.of(lastInfo).y;
                    }
                }
                if (Double.isNaN(y0)) {
                    verdicts.put(x, "the ship never LOADED with the client present: " + oneLine(lastInfo));
                    continue;
                }
                if (shipId == null) {
                    verdicts.put(x, "the ship loaded but reported no id, so no later reading can be "
                            + "attributed to it: " + oneLine(lastInfo));
                    continue;
                }
                if (shipIds.containsValue(shipId)) {
                    verdicts.put(x, "this rung's ship is the SAME ship a previous rung measured (id "
                            + shipId + ") - the ladder is measuring one subject twice");
                    continue;
                }
                shipIds.put(x, shipId);

                // NAME the ship. The bare form takes the first loaded pilot seat, and this ladder
                // keeps every rung's ship permanently loaded — so at 16M it mounted the pilot onto
                // the ORIGIN ship's seat, the client 16M away saw no entity to ride, and the reply
                // read exactly like a far-coordinate failure. It was not one.
                //
                // By ID, not by a 512-block radius. The radius form was removed on 2026-09-14 with
                // the rest of the positional resolves, and this rung is the sharpest argument for
                // why: a bound chosen against how far apart the rungs are BUILT says nothing on a
                // ladder whose whole subject is distance. The id is in hand two lines up.
                SeatMount mountInfo = SeatMount.onShip(this::exec, 0, shipId);
                if (!mountInfo.seatFound) {
                    verdicts.put(x, "the pilot seat was not findable: " + oneLine(mountInfo.raw()));
                    continue;
                }
                if (!mountInfo.seatFound) {
                    verdicts.put(x, "seat-mount reported no dummy id: " + oneLine(mountInfo.raw()));
                    continue;
                }
                long mountMark = clientEvents().mark();
                String mounted = exec("artest player mount-entity "
                        + mountInfo.requireDummyId());
                // absence is the answer, as above: one row's verdict, not the sweep's end.
                if (!Reply.of(mounted).boolOr("mounted", false)) {
                    verdicts.put(x, "the bot could not mount the seat dummy: " + oneLine(mounted));
                    continue;
                }
                // "mounted":true is the SERVER's word. The climb measures the CLIENT-rendered rider,
                // so wait until the CLIENT agrees it is riding — the first run of this leg read the
                // rider's posY one tick too early and died on a missing field, which reads exactly
                // like a coordinate failure and is not one.
                String riding = awaitRiding(mountInfo.requireDummyId(), mountMark);
                if (riding != null) {
                    verdicts.put(x, riding + " (server said " + oneLine(mounted) + ")");
                    continue;
                }

                String flight = climbLeg(shipId, y0);
                // The seat's own position is a SUBSPACE coordinate — the shipyard is where a ship's
                // blocks actually live. Recording it makes the magnitude the ship's own math runs on
                // visible in the report, which is the only number that changes if the shipyard moves.
                report.add("x=" + x + " ship=" + shipId + " shipY0=" + fmt(y0)
                        + " subspaceSeatX=" + fmt((double) mountInfo.seatX())
                        + " subspaceSeatZ=" + fmt((double) mountInfo.seatZ())
                        + " " + flight);
                verdicts.put(x, flight.startsWith("OK") ? null : flight);

                exec("artest player dismount");
                bot().waitTicks(10);
            }
        } finally {
            // The report is the deliverable and it is worth MOST when the leg died mid-ladder, so it
            // is emitted before anything can escape. The first run of this leg threw past its own
            // report writer and left nothing on disk to read.
            for (Map.Entry<Integer, String> e : verdicts.entrySet()) {
                if (e.getValue() != null) {
                    report.add("x=" + e.getKey() + " FAILED " + e.getValue());
                }
            }
            StringBuilder built = new StringBuilder("[SPIKE far-coordinate VS ship]\n");
            for (String line : report) {
                built.append("  ").append(line).append('\n');
            }
            for (String line : inconclusive) {
                built.append("  INCONCLUSIVE ").append(line).append('\n');
            }
            for (int x : X_LADDER) {
                if (!verdicts.containsKey(x) && !hasPrefix(inconclusive, "x=" + x + " ")) {
                    built.append("  NOT REACHED x=").append(x).append('\n');
                }
            }
            System.out.println(built);
            writeReport("far-coordinate-ship.txt", built.toString());
            out = built;
            try {
                exec("artest player dismount");
            } catch (Exception ignored) {
                // teardown must not mask the finding
            }
        }

        // The control is asserted first and separately: a ship that will not fly at the ORIGIN makes
        // every far reading meaningless, and that is an instrument failure, not a coordinate ceiling.
        assertTrue("the x=0 control produced no measurement at all, so no far rung is evidence:\n" + out,
                verdicts.containsKey(0));
        assertTrue("the x=0 control failed - the instrument, not the coordinate: " + verdicts.get(0)
                + "\n" + out, verdicts.get(0) == null);

        List<String> failed = new ArrayList<>();
        for (Map.Entry<Integer, String> e : verdicts.entrySet()) {
            if (e.getKey() != 0 && e.getValue() != null) {
                failed.add("x=" + e.getKey() + ": " + e.getValue());
            }
        }
        assertTrue("a ship does not behave at a far coordinate as it does at the origin: " + failed
                + "\n" + out, failed.isEmpty());
        assertTrue("no far rung was measured at all - the leg answered nothing:\n" + out,
                verdicts.size() > 1);
    }

    /**
     * SPIKE — how long does a ONE-SHOT commanded setpoint survive, and does the shipyard's position
     * change that?
     *
     * <h2>The question this exists to settle</h2>
     * Two measurements of this tree disagree. Moving the shipyard to {@code CHUNK_X_START =
     * 1,200,000} makes {@code aStillCrewMemberOnAFastClimbingShipKeepsHisCapture} report
     * {@code travelled=0.0} on 3 of 3 runs while it is green on 3 of 3 at {@code 320000} — yet the
     * ladder above lifts a ship 4.7–5.1 blocks at that same subspace magnitude. Both cannot be
     * describing "a ship cannot move out there".
     *
     * <p>They stop disagreeing under one hypothesis: the failure is not in DELIVERING a command but
     * in its SURVIVAL. The ladder holds a real key, so it re-commands every tick and outlives any
     * loss of state; {@code seat-input} writes a setpoint ONCE, into
     * {@code TileAdvancedFlightComputer} — and a flight computer tile that is re-created underneath
     * the ship loses every live field it holds, {@code velocitySetpoint} included, while persistent
     * {@code stationKeeping} survives. A command that is silently dropped a few seconds in reads as
     * {@code travelled=0.0}.</p>
     *
     * <p>Independently, the registration is known to leak in this tree:
     * {@code ClaimedChunkCacheController:122} re-registers EVERY tile of a chunk each time the claim
     * cache loads it, {@code MixinChunk:48} adds on tile add, and {@code MixinChunk:53} removes only
     * when a tile is genuinely removed — so an unload/load cycle leaves the old instance registered
     * forever and adds a new one.</p>
     *
     * <h2>What this measures, and what would settle it</h2>
     * One command, then the ship's own position sampled until it stops moving. The number is the
     * SURVIVAL WINDOW in ticks. Run at both constants, on a wiped world, at ordinary world
     * coordinates so the shipyard's position is the only thing that differs.
     * <ul>
     *   <li>window shorter at {@code 1,200,000} → the two measurements are reconciled and the
     *       shipyard move is implicated through the recreation rate;</li>
     *   <li>window the same → the recreation story is still true but does NOT explain the red, and
     *       the cause of that red is still unnamed.</li>
     * </ul>
     * Prints, never asserts a threshold: there is no defensible number to assert before the first
     * pair of readings exists.
     */
    @Test
    public void howLongDoesAOneShotCommandSurvive() throws Exception {

        bot().waitForWorld();
        exec("gamerule sendCommandFeedback false");
        exec("gamerule logAdminCommands false");
        exec("gamerule doMobSpawning false");
        bot().setRenderDistance(4);

        StringBuilder out = new StringBuilder("[SPIKE one-shot command survival]\n");
        try {
            String arrangement = arrange(0);
            assertTrue("the arena did not build: " + arrangement, arrangement == null);
            String assemble = assembleFixture(0);
            assertTrue("the fixture did not assemble", assemble != null);
            assertTrue("the build must route to a ship: " + oneLine(assemble),
                    (Reply.of(assemble).integer("rocketCount") == 0));
            for (int i = 0; i < 40 && count("ship-count-all") < 1; i++) {
                bot().waitTicks(5);
            }
            String delivery = deliver(0);
            assertTrue("the pilot was not delivered: " + delivery, delivery == null);

            // By name, from the assembler that minted it — not by a bounded read at the arena origin.
            String shipId = ShipIdentity.awaitPhysicsIdOf(this::exec, 0,
                    ShipIdentity.nameFromAssembly(assemble), 40, () -> bot().waitTicks(5));

            // By ID. The `near <x> <y> <z> <maxDist>` form this used went with the rest of the
            // positional resolves on 2026-09-14: a distance to a craft whose blocks live in its
            // subspace measures nothing, and the id was already resolved three lines up.
            SeatMount mountInfo = SeatMount.onShip(this::exec, 0, shipId);
            assertTrue("no seat: " + oneLine(mountInfo.raw()), mountInfo.seatFound);
            int dummyId = mountInfo.requireDummyId();
            long mountMark = clientEvents().mark();
            assertTrue("could not mount",
                    Reply.of(exec("artest player mount-entity " + dummyId)).bool("mounted"));
            String riding = awaitRiding(dummyId, mountMark);
            assertTrue("the client never began riding: " + riding, riding == null);

            // ONE command. Forward throttle rather than vertical: horizontal travel has no ceiling to
            // be mistaken for a command that stopped surviving.
            double[] before = shipXZ(shipId);
            // Addressed: this spike flies its ship to coordinates where "the first pilot seat the
            // world lists" is the least trustworthy address there is, and the id is already in hand.
            String commanded = exec("artest vs seat-input-by-id 0 " + shipId + " 1 0 0 0 0 0");
            out.append("  commanded once: ").append(oneLine(commanded)).append('\n');
            out.append("  subspaceSeat=(").append(fmt((double) mountInfo.seatX())).append(',')
                    .append(fmt((double) mountInfo.seatZ())).append(")\n");

            double lastDist = 0d;
            int stoppedAtTick = -1;
            int quiet = 0;
            for (int sample = 1; sample <= SURVIVAL_SAMPLES; sample++) {
                bot().waitTicks(SURVIVAL_SAMPLE_TICKS);
                double[] now = shipXZ(shipId);
                double dist = Math.hypot(now[0] - before[0], now[1] - before[1]);
                double step = dist - lastDist;
                out.append("    t=").append(sample * SURVIVAL_SAMPLE_TICKS)
                        .append(" travelled=").append(fmt(dist))
                        .append(" step=").append(fmt(step)).append('\n');
                if (step < SURVIVAL_STEP_EPSILON) {
                    quiet++;
                    if (quiet >= 3 && stoppedAtTick < 0 && dist > 0.1d) {
                        stoppedAtTick = (sample - 2) * SURVIVAL_SAMPLE_TICKS;
                    }
                } else {
                    quiet = 0;
                }
                lastDist = dist;
            }
            out.append("  SURVIVAL WINDOW: ")
                    .append(stoppedAtTick < 0
                            ? "never stopped within " + (SURVIVAL_SAMPLES * SURVIVAL_SAMPLE_TICKS)
                                    + " ticks (total " + fmt(lastDist) + " blocks)"
                            : stoppedAtTick + " ticks, then motion ceased (total " + fmt(lastDist)
                                    + " blocks)")
                    .append('\n');
        } finally {
            System.out.println(out);
            writeReport("one-shot-command-survival.txt", out.toString());
            try {
                exec("artest player dismount");
            } catch (Exception ignored) {
                // teardown must not mask the reading
            }
        }
    }

    /** The ship's world X and Z, by id. */
    private double[] shipXZ(String shipId) {
        String last = "";
        for (int i = 0; i < 10; i++) {
            try {
                last = exec("artest vs ship-info 0 id " + shipId);
                if (ShipInfo.isLoaded(last)) {
                    ShipInfo info = ShipInfo.of(last);
                    if (!Double.isNaN(info.x) && !Double.isNaN(info.z)) {
                        return new double[] {info.x, info.z};
                    }
                }
                bot().waitTicks(2);
            } catch (Exception e) {
                throw new AssertionError("ship-info threw: " + e, e);
            }
        }
        throw new AssertionError("ship-info never returned a parseable position; last: " + last);
    }

    // ─── the measurement ────────────────────────────────────────────────────────

    /**
     * One controllability measurement where the ship already is: hold the REAL vertical-up key, the
     * SERVER ship must climb, and the CLIENT-rendered rider must climb with it. A transform that has
     * lost precision at a far coordinate shows up as divergence between those two and nowhere else.
     *
     * @return {@code "OK ..."} with the numbers, or the reason it failed
     */
    /**
     * Waits until the CLIENT reports it is riding something, and — if it never does — asks the three
     * questions that decide WHICH thing failed, because "the client is not riding" on its own cannot
     * tell a coordinate ceiling from an arrangement fault:
     * <ol>
     *   <li>where the CLIENT thinks the player is (a client that never arrived explains everything);</li>
     *   <li>what entities the CLIENT can see near him (an empty list means entity tracking never
     *       delivered the seat dummy — the mount had nothing to bind to);</li>
     *   <li>where the SERVER holds that same dummy (so a client/server split is visible as one).</li>
     * </ol>
     *
     * @return {@code null} once the client is riding, else the reason plus that diagnosis
     */
    private String awaitRiding(int dummyId, long clientMark) throws Exception {
        com.google.gson.JsonObject last;
        try {
            // The CLIENT's own mount chain, not a sample of reportRidingEntity: the poll this
            // replaced could only ever read the state this record announces, and its "not riding"
            // was equally produced by a client that had not been told anything yet.
            // MixinEntityPositionWriters is in the COMMON mixin list, so the record is there for a
            // spike as much as for the tier.
            ClientEvents.awaitMounted(clientEvents(), clientMark,
                    "the client must begin riding the seat dummy", RIDING_ATTEMPTS * 5);
            last = bot().reportRidingEntity();
            if (last.has("riding") && last.get("riding").getAsBoolean() && last.has("posY")) {
                return null;
            }
        } catch (AssertionError never) {
            last = bot().reportRidingEntity();
        }
        String clientState;
        String clientEntities;
        try {
            clientState = String.valueOf(bot().reportState());
            clientEntities = String.valueOf(bot().reportEntities("", 128d));
        } catch (Exception e) {
            clientState = "unreadable: " + e;
            clientEntities = "unreadable";
        }
        return "the CLIENT never began riding the seat after " + (RIDING_ATTEMPTS * 5)
                + " ticks (last report: " + last + ")"
                + " | client state: " + oneLine(clientState)
                + " | client sees near him: " + oneLine(clientEntities)
                + " | server holds the dummy at: "
                + oneLine(exec("artest entity info 0 " + dummyId));
    }

    /** The CLIENT's own ordered event log, behind the same verbs the server's is read through.
     *  {@link Events#mark} refuses a sequence unless a recorder is subscribed, which is what keeps
     *  an empty log later from reading as "it never happened". */
    private Events clientEvents() throws Exception {
        return ClientEvents.of(bot());
    }

    private double riderY() throws Exception {
        return bot().reportRidingEntity().get("posY").getAsDouble();
    }

    private String climbLeg(String shipId, double yBefore) throws Exception {
        double riderYBefore = riderY();
        bot().holdKey(Keyboard.KEY_R); // flightVerticalUp
        try {
            ClientPoll.until(bot()::waitTicks,
                    () -> shipY(shipId),
                    y -> y - yBefore > 1.5, 2, 100);
        } finally {
            bot().releaseKey(Keyboard.KEY_R);
        }
        bot().waitTicks(6);
        double serverDelta = shipY(shipId) - yBefore;
        double riderDelta = riderY() - riderYBefore;
        String numbers = "serverLift=" + fmt(serverDelta) + " riderLift=" + fmt(riderDelta)
                + " divergence=" + fmt(Math.abs(riderDelta - serverDelta));
        if (!(serverDelta > MIN_LIFT_BLOCKS)) {
            // A third witness separates "the seat glue died" from "the ship would not move".
            return "the vertical-up key did not lift the ship (" + numbers + "); server player: "
                    + oneLine(exec("artest player health"));
        }
        if (Math.abs(riderDelta - serverDelta) >= TRACK_TOLERANCE) {
            return "the CLIENT rider did not track the server ship (" + numbers + ")";
        }
        return "OK " + numbers;
    }

    // ─── arrangement ────────────────────────────────────────────────────────────

    /** @return {@code null} once the site is loaded and clear, else what is wrong with it */
    private String arrange(int x) throws Exception {
        int cx1 = (x - 32) >> 4, cz1 = (ARENA_Z - 32) >> 4;
        int cx2 = (x + 32) >> 4, cz2 = (ARENA_Z + 32) >> 4;
        String warm = exec("artest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2);
        if (!Reply.of(warm).ok()) {
            return "chunk warmup failed: " + oneLine(warm);
        }
        // A stone pad at BASE_Y-1 and air above it: 16M is ocean, and the fixture must not be built
        // into water or into whatever the generator put there.
        exec("artest fill 0 " + (x - 8) + " " + (BASE_Y - 1) + " " + (ARENA_Z - 8) + " "
                + (x + 12) + " " + (BASE_Y - 1) + " " + (ARENA_Z + 12) + " minecraft:stone");
        String clear = exec("artest fill 0 " + (x - 8) + " " + BASE_Y + " " + (ARENA_Z - 8) + " "
                + (x + 12) + " " + (BASE_Y + 14) + " " + (ARENA_Z + 12) + " minecraft:air");
        if (!Reply.of(clear).ok()) {
            return "pre-clear failed: " + oneLine(clear);
        }
        String pad = exec("artest block at 0 " + x + " " + (BASE_Y - 1) + " " + ARENA_Z);
        // The id, compared: `contains("stone")` also accepts cobblestone and sandstone, so a pad
        // laid out of the wrong block passed the control that exists to check it. Refusing,
        // because the producer always writes `block` for a loaded dimension and this asks dim 0.
        if (!"minecraft:stone".equals(Reply.of(pad).text("block"))) {
            return "the pad is not stone (" + oneLine(pad) + ")";
        }
        return null;
    }

    /**
     * DELIBERATELY NOT ON THE SHARED BUILDER, and this is the read rather than an oversight.
     *
     * <p>{@code RocketFixture} raises an ARRANGEMENT FAILURE when a fixture will not lay, which is
     * right for every scenario whose subject is what happens afterwards. This one's subject is the
     * laying: it sweeps |x| out to sixteen million asking WHERE the build stops working, so a
     * refusal is the measurement and must be recorded and walked past rather than thrown. It also
     * lays its own stone pad, because at those coordinates the world is ocean.</p>
     *
     * @return the assemble reply, or {@code null} if the fixture itself never landed
     */
    private String assembleFixture(int x) throws Exception {
        String fixture = exec("artest fixture rocket 0 " + x + " " + BASE_Y + " " + ARENA_Z
                + " " + VARIANT);
        if (!Reply.of(fixture).ok()) {
            System.out.println("[SPIKE ship] fixture at x=" + x + " failed: " + oneLine(fixture));
            return null;
        }
        int[] bp = Reply.of(fixture).blockPos(BUILDER_POS);
        if (bp == null) {
            System.out.println("[SPIKE ship] fixture at x=" + x + " gave no builderPos: "
                    + oneLine(fixture));
            return null;
        }
        return exec("artest rocket assemble 0 " + bp[0] + " " + bp[1] + " " + bp[2]);
    }

    /**
     * Puts the pilot on the ship through the long-jump path, retried: the chunks are loaded on the
     * SERVER while the client has not received them yet, and the first delivery of a far rung lands
     * in a world the client cannot see.
     *
     * @return {@code null} once he is there, or a reason string for the INCONCLUSIVE list
     */
    private String deliver(int x) throws Exception {
        double lastX = Double.NaN;
        // STAYS A LOOP, and the refusal names the link. The re-issued far-tp IS the stimulus — a
        // delivery that did not take is not recoverable by reading longer — and the exit is a
        // CONVERGENCE on where the server holds him. The link that looks right is `pos_jump`, and
        // it does not answer: it fires only on a VERTICAL write past a threshold and carries
        // `from`/`to` in Y alone, so it cannot say he arrived at this X. What this cannot see: a
        // delivery that landed and was undone between two attempts.
        for (int attempt = 1; attempt <= DELIVERY_ATTEMPTS; attempt++) {
            exec("artest player far-tp " + fmt(x + 0.5d) + " " + (BASE_Y + 6) + " "
                    + fmt(ARENA_Z + 0.5d));
            GameTicks.advanceWorld(serverClient(), 0, 40);
            bot().waitTicks(30);
            lastX = field(exec("artest player health"), "posX");
            if (Math.abs(lastX - (x + 0.5d)) < ARRIVAL_TOLERANCE) {
                return null;
            }
        }
        return "the pilot never arrived (server posX=" + lastX + ", wanted " + (x + 0.5d)
                + ") after " + DELIVERY_ATTEMPTS + " deliveries - delivery, not the ship";
    }

    // ─── instruments ────────────────────────────────────────────────────────────

    /**
     * The server ship's {@code posY}, asked BY ID and tolerant of unrelated console lines
     * interleaving with the probe's reply — at far coordinates a VS collision mixin can print into
     * the same window.
     *
     * <p>By id, not by position: a nearest-ship lookup has a distance term to be wrong about, and on
     * this ladder — two ships, one of them 16M away — a rung whose own ship had unloaded would
     * silently be answered with the OTHER rung's ship. That failure looks like two rungs agreeing to
     * four decimals, which is exactly what a clean far-coordinate result also looks like.</p>
     */
    private double shipY(String shipId) {
        String last = "";
        for (int i = 0; i < 10; i++) {
            try {
                last = exec("artest vs ship-info 0 id " + shipId);
                if (ShipInfo.isLoaded(last)) {
                    double py = ShipInfo.of(last).y;
                    if (!Double.isNaN(py)) {
                        return py;
                    }
                }
                bot().waitTicks(2);
            } catch (Exception e) {
                throw new AssertionError("ship-info threw: " + e, e);
            }
        }
        throw new AssertionError("ship-info never returned a parseable posY; last reply: " + last);
    }

    private int count(String sub) throws Exception {
        String command = "artest vs " + sub + " 0";
        return Reply.of(command, exec(command)).integer(COUNT);
    }

    private static double field(String json, String key) {
        // absence is the answer: this spike SWEEPS coordinates and records a verdict per one,
        // so a probe that answered nothing for a given x must leave that row unmeasured
        // rather than end the sweep. The caller tests for NaN.
        return Reply.of(json).numberOr(key, Double.NaN);
    }

    /** The report is the deliverable, so it also lands on disk and survives a truncated console. */
    private static void writeReport(String name, String text) {
        try {
            java.nio.file.Path dir = java.nio.file.Paths.get("build", "spike-reports").toAbsolutePath();
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Files.write(dir.resolve(name), text.getBytes("UTF-8"));
        } catch (Exception e) {
            System.out.println("[SPIKE] could not write the report file: " + e);
        }
    }

    private static boolean hasPrefix(List<String> lines, String prefix) {
        for (String line : lines) {
            if (line.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static String oneLine(String s) {
        return s.replace((char) 10, ' ').replace((char) 13, ' ').trim();
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.4f", v);
    }
}
