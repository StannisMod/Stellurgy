package zmaster587.advancedRocketry.test.client;

import com.google.gson.JsonObject;

import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;
import org.lwjgl.input.Keyboard;


import zmaster587.advancedRocketry.test.SeatMount;
import zmaster587.advancedRocketry.test.Reply;
import zmaster587.advancedRocketry.test.ShipInfo;
import zmaster587.advancedRocketry.test.Events;

import zmaster587.advancedRocketry.test.Plot;

import static org.junit.Assert.assertTrue;

/**
 * A pilot who logs out SEATED logs back in SEATED — with a WORKING control chain: after the relog,
 * holding the real vertical-up key must lift the ship again, with no re-board and no re-click.
 *
 * <p>Subject: a planet-side assembled tier-2 ship. Here the relog path is vanilla's own mount
 * persistence — the seat mount rides the player's save data (removed from the world at logout,
 * re-spawned and re-mounted at login) and carries its seat binding in its entity NBT — so the pin
 * covers the full chain: the restored mount must still resolve its seat in the seat's CURRENT
 * frame, the client's input gate must re-open against the restored mount, and the input must reach
 * the ship's flight computer. A green "still riding" with a dead key would be exactly the
 * play-reported shape of a broken chain, which is why the post-relog CLIMB is the load-bearing
 * assertion, not the seating.</p>
 *
 * <p>The relog is real ({@code ClientBot.reconnect} — a full server logout with player-data save
 * and a fresh login). The boarding is the {@code vs seat-mount} probe + mount-entity (the harness
 * cannot right-click a post-assembly ship-subspace block); the flight stimulus and every
 * observation are the real client's (held key in; client-rendered rider altitude out).</p>
 *
 * <h2>The chain and the measurement, kept apart</h2>
 *
 * <p>An altitude converging under thrust is a physical value and stays a bounded poll — but the
 * javadoc's claim above is about LINKS, and until this class asserted them a red could only print
 * two numbers. So each climb is now preceded by its own chain: the client's gate re-opened and put
 * a packet on the wire ({@code pilot_input_sent}, client log), and the seat handed that input to the
 * ship's flight computer ({@code pilot_input_delivered}, server log). A dead key and a tipped hull
 * used to look identical; now only one of them gets as far as the altitude assertion. The two sides
 * are asserted separately and in no claimed order — the logs are joined only by game tick.</p>
 *
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class VSPilotSeatRelogControlE2ETest extends AbstractSharedVsClientE2ETest {

    @Override
    protected String subsystem() {
        return "vs-pilot-seat-relog-control";
    }

    private static final String BUILDER_POS = "builderPos";
    private static final String DUMMY_ID = "dummyId";

    private static final String VARIANT = "with-pilot-seat";
    // The surveyed-clean ground of the pinned seed. The old 7200/7200 was inside a mountain whose
    // surface is y=80..93, so this fixture's ship was assembled in rock and could not climb — which
    // was ledgered as a control-chain defect (#161) for eleven days.
    /** How long the CLIENT is given to PERFORM a seating the server has already done, in ticks. */
    private static final int SEAT_LINK_BUDGET_TICKS = 200;

    private static final int BX = Plot.CLEAN_GROUND_X;
    private static final int BY = Plot.CLEAN_GROUND_Y;
    private static final int BZ = Plot.CLEAN_GROUND_Z;

    /** A demonstrable climb: well above settle jitter, cheap to reach. */
    private static final double MIN_CLIMB = 1.0;

    /**
     * How long one LINK of the control chain may take, in the event clock's ticks. A held key is
     * re-asserted on its seat's own phase about once a second, and a relog crosses a fresh world
     * load, so this is deliberately generous — it is a deadline for a discrete event, not a guess at
     * how long the altitude takes to move.
     */
    private static final int LINK_BUDGET_TICKS = 400;

    /**
     * Client ticks between two reads of the login's own records — the step {@link Events}'s waits
     * advance by, so a budget expressed in ITERATIONS (as the poll here was) converts by
     * multiplying. Named because the conversion is otherwise a bare {@code * 5}.
     */
    private static final int RELOG_STEP_TICKS = 5;

    /** This scenario's ship, by identity — read off the registry's own record of ITS assembly, and
     *  used for every question afterwards. Never re-derived from a position. */
    private String shipId;

    @Test
    public void aPilotWhoRelogsSeatedKeepsControlOfHisShip() throws Exception {

        // ---- ARRANGE: build + assemble a piloted ship, seat the client player on it. ------------
        long awayMark = clientEvents().mark();
        exec("tp @a " + (BX + 600) + " 120 " + (BZ + 600) + " 0 0");
        awaitClientPlacedNear(awayMark, BX + 600, BZ + 600,
                "the assembly below must run with no observer near it, and the observer is a client");
        // THE MULTIPLIER STAYS on the CLIMB budgets below, and on the LOAD wait: what those wait on
        // is wall-clock work — a physical value converging, VS building the ship off the game loop —
        // and a busy box genuinely gives the same window less world. The IDENTITY is a different
        // shape and needs no budget of ours at all — the registry's own record of the ship being
        // added, since a mark taken before the assembly was queued, is THIS scenario's ship by
        // construction, where the count it replaces is answered by every neighbour that ever
        // assembled one.
        int budget = 40;
        Events events = events();
        long spawnMark = events.markInstrumented();
        String assemble = assembleFixture(BX, BY, BZ);
        scenario().requireArranged("a with-pilot-seat build must route to a ship: " + assemble,
                (Reply.of(assemble).integer("rocketCount") == 0));
        shipId = awaitShipSpawned(events, spawnMark,
                "assembly must create a NEW VS ship in the queryable registry (async spawn)");
        long approachMark = clientEvents().mark();
        exec("tp @a " + (BX + 0.5) + " " + (BY + 6) + " " + (BZ + 0.5) + " 0 0");
        awaitClientPlacedNear(approachMark, BX + 0.5, BZ + 0.5,
                "the client's ARRIVAL is what loads the ship, so the load wait below is waiting on"
                        + " something only an arrived client can cause");

        // WAIT for the ship this scenario ALREADY NAMES to be loaded, then take it OFF ITS PAD
        // before anyone flies it.
        //
        // Both halves are load-bearing. The wait, because `ship_spawned` is the registry's record of
        // an ADD and does not prove the physics object is loaded — everything this scenario reads
        // about the ship afterwards has to be about THIS ship, which is flown, relogged and flown
        // again, so the identity stays the one the assembly itself recorded: a positional lookup at
        // the build site answers about whichever craft is nearest to a place this one has left.
        // Readiness itself is production's own `ship_usable` — the conjunction the physics loop
        // selects a ship by — and it is read off the SAME mark taken before the assembly, because it
        // fires ONCE per load and is not a state that can be polled after the fact. The lift, because
        // a craft launched from a pad is tipped by the physics substrate's collision response and then
        // holds the tilt — and this scenario's whole claim is about ALTITUDE, which a body-frame
        // throttle cannot produce on a hull lying over. The red that state produces reads "the
        // restored control chain is dead" while the control chain is perfect.
        //
        // The budget keeps its wall-clock across a UNIT change: `budget` counts 5-tick polls, and the
        // event wait takes a tick budget, so it is multiplied by 5.
        awaitShipUsable(events, spawnMark, shipId, budget * 5);
        liftClearOfThePad(shipId, PAD_CLEARANCE_BLOCKS);

        SeatMount mountInfo = SeatMount.onShip(this::exec, 0, shipId);
        long seatMark = clientEvents().mark();
        String mount = exec("artest player mount-entity " + mountInfo.requireDummyId());
        scenario().requireArranged("bot must mount the seat dummy: " + mount,
                Reply.of(mount).bool("mounted"));
        // The control leg below asks whether the chain works BEFORE the relog; a client that has not
        // seated him yet would answer for a pilot who is not in the seat.
        awaitClientMount(seatMark, "the client must be riding the seat before the control leg, or"
                + " the post-relog leg has nothing to be compared against", SEAT_LINK_BUDGET_TICKS,
                " | server said: " + mount);

        // ---- CONTROL LEG (pre-relog): the chain works before the relog, or the post-relog leg
        // cannot indict the relog. -----------------------------------------------------------
        long controlMark = events.markInstrumented();
        Climb before = climbWith(Keyboard.KEY_R, clientPlayerY(), budget);
        // The LINK first, the number second — so an arrangement whose key never reached the ship's
        // computer says so instead of reporting an altitude that never moved.
        events.await(controlMark, "pilot_input_delivered", "control leg: the held key must reach the"
                + " ship's flight computer BEFORE the relog, or nothing measured after it can indict"
                + " the relog", LINK_BUDGET_TICKS);
        scenario().requireArranged("control leg: the pilot must be able to fly BEFORE the relog. "
                + before, before.climbed());
        bot().waitTicks(30); // let the station-hold settle the hovering ship

        // ---- ACT: the real relog — full server logout (player data saved) + fresh login. -------
        // The mark is taken before the reconnect: the re-seating happens DURING the login, and a
        // reader that arrives afterwards would be asking whether it can still see it.
        long relogMark = events.markInstrumented();
        // The CLIENT's own mark beside it: the login puts him back on his mount, and his client
        // PERFORMS that mount when it is told who is riding what. The replication below is a record
        // on this log, not a state to sample.
        long relogOnClient = clientEvents().mark();
        bot().reconnect();
        bot().waitForWorld();

        // ---- ASSERT 1: seated again, with NO re-board — two consecutive positive samples. ------
        // The SERVER's own re-seat first: vanilla re-spawns the persisted mount and forces the
        // player back onto it, and that is the link this contract is about. The client poll that
        // follows is the replication of it, and can now only fail as replication.
        events.await(relogMark, "mount", "a pilot who logged out SEATED must be put back on his mount"
                + " by the login itself - no re-board, no re-click", LINK_BUDGET_TICKS);
        // The replication, as the LINK it is. What stood here read `riding` twice with a wait
        // between, on the reasoning that a lost seat reads true for a packet-lag moment but never
        // twice — which is guessing at an EDGE from two samples of a level, and says nothing about
        // when or how often the seat changed hands in between. THE MULTIPLIER STAYS in the budget:
        // this waits for state the SERVER restores on login to reach the client and be applied, a
        // round trip whose latency is the machine's, not the game's.
        int rejoinBudget = 60;
        JsonObject riding = awaitClientMount(relogOnClient,
                "a pilot who logged out SEATED must log back in SEATED - no re-board, and his own"
                        + " client must perform the mount the login restored",
                rejoinBudget * RELOG_STEP_TICKS,
                " | the SERVER's mount record: " + events.since(relogMark, "mount"));
        assertTrue("...and he must still be on it when it is read: riding=" + riding,
                isRiding(riding));

        // ---- PRECONDITION before ASSERT 2 can mean anything: the hull is still level. ---------
        // Declared, not guessed: this leg's claim is about ALTITUDE, and a pilot's throttle is a
        // body-frame command. On a hull that has tipped the claim is unmeasurable, and the red would
        // name the restored control chain when the control chain is perfect. The base refuses to make
        // the claim in that state and says which one it is.
        requireUprightForAnAltitudeClaim(shipInfoById(shipId),
                "the restored control chain still lifts the ship");

        // ---- ASSERT 2 (load-bearing): the restored chain still FLIES the ship. -----------------
        // Its three links, asserted before the number they produce. Two of them are the ones the
        // class javadoc has always claimed and nothing ever checked: the CLIENT's input gate
        // re-opened against the restored mount and put a packet on the wire, and the seat handed
        // that input to the ship's computer. Each is asked of its own side's log, in no claimed
        // order — cross-side order within one tick is undefined.
        long flyMark = events.markInstrumented();
        long flyClientMark = clientEvents().mark();
        Climb after = climbWith(Keyboard.KEY_R, clientPlayerY(), budget);
        String clientSends = clientEvents().since(flyClientMark, "pilot_input_sent");
        assertTrue("after the relog the CLIENT's own pilot gate must re-open against the restored"
                        + " mount and put input on the wire - a gate that stayed shut is the first"
                        + " way this chain breaks, and it looks exactly like a dead key from the"
                        + " altitude alone. client sends since the mark: " + clientSends
                        + " | " + after,
                Events.countRecordsWithField(clientSends, "seat") > 0);
        events.await(flyMark, "pilot_input_delivered", "after the relog the restored seat must still"
                + " hand the pilot's input to the ship's flight computer - a re-seated pilot whose"
                + " seat no longer resolves its computer is the second way this chain breaks",
                LINK_BUDGET_TICKS);
        assertTrue("after the relog, held input must MOVE THE SHIP - a restored seat with a dead key"
                        + " is a broken control chain. The two links above are already asserted, so"
                        + " WHAT THIS RED MAY NOT BLAME is the seat binding or the input path: a ship"
                        + " whose `up` has fallen toward 0 has TIPPED, and the pilot's throttle is a"
                        + " body-frame command, so the chain can be perfect and the rider still sink."
                        + " Compare against the control leg, which flew the same key on the same"
                        + " craft: " + after
                        + " | control leg was: " + before
                        + " | deliveries since the mark: " + events.since(flyMark,
                                "pilot_input_delivered"),
                after.climbed());
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** What a held-throttle climb DID, not just where it ended — printable whole into a red. */
    private static final class Climb {
        final double from;
        final double last;
        final String trace;

        Climb(double from, double last, String trace) {
            this.from = from;
            this.last = last;
            this.trace = trace;
        }

        boolean climbed() {
            return (last - from) >= MIN_CLIMB;
        }

        @Override
        public String toString() {
            return String.format(java.util.Locale.ROOT,
                    "clientY %.3f -> %.3f (need +%.1f) :: %s", from, last, MIN_CLIMB, trace);
        }
    }

    /**
     * Hold {@code key} until the client-rendered rider altitude climbs {@link #MIN_CLIMB} over
     * {@code from} (bounded, early-exit), recording what the SHIP did while it happened.
     *
     * <p>The ship's pose rides along because without it this leg's red cannot be read. It used to
     * report {@code clientY 67.107 -> 65.467} and nothing else, which reads as "the key is dead" —
     * and the measured production behaviour it cannot be told apart from is a ship that has TIPPED:
     * the pilot's throttle is a BODY-frame command (`FreeFlightPhysics.shipVelocityCommand` maps it
     * through the attitude), so once the hull is over, "climb" is horizontal thrust and the rider
     * sinks while the control chain works perfectly. {@code up} is the world-frame Y of the ship's
     * own up: 1.0 upright, 0 on its side.</p>
     */
    private Climb climbWith(int key, double from, int budget) throws Exception {
        double last = from;
        StringBuilder trace = new StringBuilder();
        bot().holdKey(key);
        try {
            for (int i = 0; i < budget && (last - from) < MIN_CLIMB; i++) {
                bot().waitTicks(5);
                last = clientPlayerY();
                if (i % 4 == 0 && trace.length() < 700) {
                    trace.append('[').append(i * 5).append("t y=")
                            .append(String.format(java.util.Locale.ROOT, "%.2f", last))
                            .append(' ').append(shipPose()).append("] ");
                }
            }
        } finally {
            bot().releaseKey(key);
        }
        trace.append("[end ").append(shipPose()).append(']');
        return new Climb(from, last, trace.toString());
    }

    /** This scenario's ship, BY IDENTITY: its altitude and the world-frame Y of its OWN up.
     *  Read-only. Returns a self-describing string rather than throwing, so a probe failure can never
     *  mask the assertion it is annotating. It used to ask by position at the build site — a lookup
     *  that answers about the nearest craft, which after a lift and a climb is not this one, and
     *  whose plausible-looking pose is indistinguishable from a report about the subject. */
    private String shipPose() {
        try {
            String info = shipInfoById(shipId);
            if (!ShipInfo.isLoaded(info)) {
                return "NO-POSE-FOR-" + shipId + " " + info.replace('\n', ' ');
            }
            ShipInfo pose = ShipInfo.of(info);
            return String.format(java.util.Locale.ROOT, "shipY=%.2f up=%.2f", pose.y, pose.upY());
        } catch (Exception e) {
            return "ship-pose-failed: " + e;
        }
    }

    private double clientPlayerY() throws Exception {
        JsonObject state = bot().reportState();
        return state.has("playerY") ? state.get("playerY").getAsDouble() : Double.NaN;
    }

    private static boolean isRiding(JsonObject riding) {
        return riding != null && riding.has("riding") && riding.get("riding").getAsBoolean();
    }

    private String assembleFixture(int baseX, int baseY, int baseZ) throws Exception {
        int cx1 = (baseX - 2) >> 4, cz1 = (baseZ - 2) >> 4;
        int cx2 = (baseX + 7) >> 4, cz2 = (baseZ + 7) >> 4;
        scenario().requireArranged("chunk warmup failed",
                Reply.of(exec("artest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2)
                        ).ok());
        scenario().requireArranged("pre-clear failed",
                Reply.of(exec("artest fill 0 " + (baseX - 2) + " " + (baseY + 1) + " " + (baseZ - 2)
                        + " " + (baseX + 7) + " " + (baseY + 10) + " " + (baseZ + 7) + " minecraft:air")
                        ).ok());
        String fixture = exec("artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " " + VARIANT);
        scenario().requireArranged("fixture (" + VARIANT + ") failed: " + fixture,
                Reply.of(fixture).ok());
        int[] bp = Reply.of(fixture).blockPos(BUILDER_POS);
        scenario().requireArranged("fixture missing builderPos: " + fixture, bp != null);
        return exec("artest rocket assemble 0 " + bp[0] + " " + bp[1] + " " + bp[2]);
    }

}
