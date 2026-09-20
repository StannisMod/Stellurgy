package zmaster587.advancedRocketry.test.client;


import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;


import zmaster587.advancedRocketry.test.Events;
import zmaster587.advancedRocketry.test.PilotSeat;
import zmaster587.advancedRocketry.test.TransitSetup;
import zmaster587.advancedRocketry.test.Reply;
import zmaster587.advancedRocketry.test.ShipIdentity;
import zmaster587.advancedRocketry.test.ShipInfo;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A player riding a mount aboard a CRUISING ship never loses that mount.
 *
 * <p><b>The report.</b> Above roughly half throttle the client threw the maintainer out of his pilot
 * seat every few seconds and picked him up again, for the whole burn, while the server kept him
 * mounted and the ship flew on. The seat, not the ship, is what became unusable.</p>
 *
 * <p><b>The mechanism.</b> A mount glued to a moving ship outruns the tracking anchor its own
 * {@code EntityTrackerEntry} publishes; once the rider is more than the mount's tracking range from
 * that stale anchor the server stops sending it, the client destroys it, and a passenger whose
 * vehicle was removed is dismounted on the next tick. This test measures BOTH ends in the same
 * samples: the server-side cause (is the rider still in the mount's tracking set, and how far has
 * the anchor fallen behind him) and the client-side consequence ({@code reportRidingEntity} plus the
 * client's own view of the mount entity).</p>
 *
 * <p><b>Why the subject is a PASSENGER mount and not the pilot's seat.</b> Same mechanism, but only
 * this subject can be put under load on demand. A mount bound to a LINKED pilot seat republishes six
 * flight-telemetry floats, and every write re-pins its own anchor - vanilla's third refresh
 * disjunct - so its anchor never goes stale while anything about the flight is changing. Measured on
 * that subject: at 4.075 blocks/tick the lag never exceeded 3.34 blocks against a 16-block range, and
 * no speed the physics mod permits (it freezes a ship at ~223 blocks/s) can close that gap. The
 * pilot's own mount reaches the stale-anchor state the moment he stops working the controls and
 * coasts - which is exactly when the report's log shows the bursts - but a test cannot hold a real
 * ship that quiet on demand. A passenger's chair publishes nothing and is carried by the same deck.</p>
 *
 * <p><b>The stimulus must be HORIZONTAL.</b> Vanilla's visibility test compares X and Z and ignores Y
 * entirely, so the obvious idiom in this suite - hold the throttle and climb - cannot exhibit this at
 * any speed. That is also why taking off from a planet at full throttle is symptom-free: it is
 * {@code fwd=0, vert=1}.</p>
 *
 * <p><b>The instrument carries its own proof.</b> The bot is dismounted on purpose before the cruise
 * and the poll is required to SEE it; and the achieved speed and anchor lag are asserted, so a green
 * cannot mean "the arrangement never loaded the mechanism". Confirmed RED on the clean build at
 * 24/40 samples unseated with a 36.8-block anchor lag, GREEN with the fix at 0/40 and a 41.0-block
 * lag - i.e. the guard held under a HARDER load than the one that broke it.</p>
 *
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class VSRiderKeepsHisMountAtCruiseE2ETest extends AbstractSharedVsClientE2ETest {

    @Override
    protected String subsystem() {
        return "vs-rider-mount-at-cruise";
    }

    private static final String PLAYER_NAME = "player";
    private static final String BUILDER_POS = "builderPos";

    /**
     * The mount is registered with a tracking range of 16 blocks and an anchor republished every 20
     * ticks, so its anchor falls behind by {@code speed x 20} blocks between refreshes: past
     * 16/20 = 0.8 blocks/tick on either horizontal axis it leaves its own box. The cruise must clear
     * that, or the leg proves nothing.
     */
    private static final double EVICTION_THRESHOLD_BLOCKS_PER_TICK = 0.8;

    /** Commanded cruise, blocks/SECOND (the physics velocity unit): 2 blocks/tick, the reported speed. */
    private static final double COMMANDED_SPEED_BLOCKS_PER_SECOND = 40.0;

    /** Ticks between the two samples a cruise-speed measurement is taken from. */
    private static final int SETTLE_SAMPLE_TICKS = 10;

    /** How long the CLIENT is given to perform a seating or a release the server has already done,
     *  in ticks — a ceiling on one round trip. */
    private static final int SEAT_LINK_BUDGET_TICKS = 200;

    /** How close two successive speed samples must be before the cruise counts as STEADY. Loose
     *  enough to survive physics jitter, tight enough that the telemetry the mount publishes has
     *  stopped moving - which is the condition the anchor staleness needs. */
    private static final double STEADY_EPSILON = 0.002;

    /** Four full 20-tick tracking cycles: on the broken build the mount is evicted for roughly half
     *  of every one of them. */
    private static final int OBSERVE_TICKS = 80;
    private static final int POLL_EVERY_TICKS = 2;

    private static double readDouble(String json, String field) {
        double value = Reply.of(json).number(field);
        return value;
    }

    private static int readInt(String json, String key) {
        assertTrue("expected \"" + key + "\" in: " + json, Reply.of(json).has(key));
        return Reply.of(json).integer(key);
    }

    private static boolean readBool(String json, String key) {
        return Reply.of(json).bool(key);
    }

    private boolean riding() throws Exception {
        return bot().reportRidingEntity().get("riding").getAsBoolean();
    }

    private static int readIntOr(String json, String key, int fallback) {
        // absence is the answer, and WHICH answer is the CALLER's: this verb takes the
        // default as an argument, so every call site names what a missing field means there.
        return Reply.of(json).integerOr(key, fallback);
    }

    /** Poll for a loaded VS ship in {@code dim} (assembly is async; a headless server forces the load). */
    private int waitForLoadedShip(int dim) throws Exception {
        for (int i = 0; i < 40; i++) {
            if (readIntOr(exec("artest vs ship-count-all " + dim), "count", -1) >= 1) {
                exec("artest vs load-ships " + dim);
                int loaded = readIntOr(exec("artest vs ship-count " + dim), "count", -1);
                if (loaded >= 1) {
                    return loaded;
                }
            }
            bot().waitTicks(5);
        }
        return 0;
    }

    @Test
    public void aSeatedRiderNeverLosesTheMountHeIsRidingWhileTheShipCruises() throws Exception {

        // Headless: nobody is near the ship between probe calls, so pin it loaded. This is
        // arrangement only - what is under test is what the client is TOLD about an entity it
        // already has, not whether the ship loads.

        int dim = TransitSetup.empty(this::exec).originDim;

        // The cell is a void world, so this is not about escaping terrain — it is about ONE
        // definition of where a fixture stands instead of a 64 nobody chose. The first link still
        // earns its place: it MEASURES that the cell is empty rather than taking the setup probe's
        // word for it.
        //
        // NOT ALLOCATED FROM A PLOT, deliberately. A plot keeps a scenario clear of its siblings in
        // a SHARED world; this craft is alone in a cell made for it, so there is nobody to be kept
        // clear of, and moving it into a region of that dimension whose extent nobody has measured
        // would be a real risk taken for no contract.
        final zmaster587.advancedRocketry.test.FixtureSite site =
                zmaster587.advancedRocketry.test.FixtureSite.openAir(dim, 40, 40);
        int bx = site.x, by = site.y, bz = site.z;
        site.requireClear(this::exec, 2, 16,
                "the craft whose rider must stay aboard through the cruise");

        String fixture = exec("artest fixture rocket " + dim + " " + bx + " " + by + " " + bz
                + " with-pilot-seat");
        scenario().requireArranged("with-pilot-seat fixture failed: " + fixture, readBool(fixture, "ok"));
        int[] bp = Reply.of(fixture).blockPos(BUILDER_POS);
        scenario().requireArranged("fixture missing builderPos: " + fixture, bp != null);
        String assembled = exec("artest rocket assemble " + dim
                + " " + bp[0] + " " + bp[1] + " " + bp[2]);
        scenario().requireArranged("a with-pilot-seat build must route to a ship: " + assembled,
                (Reply.of(assembled).integer("rocketCount") == 0));
        scenario().requireArranged("the ship never assembled/loaded in the cell (dim " + dim + ")",
                waitForLoadedShip(dim) >= 1);

        // The seat inside the craft this scenario BUILT, named by the assembler that minted it. The
        // anchored form resolved the yard nearest a point over the whole registry, so it could reach
        // a neighbour's craft — or a blockless crossing remnant — and report seatFound for it.
        String durableShipId = ShipIdentity.nameFromAssembly(assembled);
        String scenarioShipId = ShipIdentity.awaitPhysicsIdOf(this::exec, dim, durableShipId, 40,
                () -> bot().waitTicks(5));
        PilotSeat seat = PilotSeat.byId(this::exec, dim, scenarioShipId)
                .requireFound("the pilot seat must be found, or the test is vacuous");
        int seatX = seat.seatX, seatY = seat.seatY, seatZ = seat.seatZ;
        // A WORLD-frame pose, carried through a name lookup into the `space enter` below. Sound
        // here for one reason worth stating in a class whose whole subject is a MOVING hull: the
        // craft has just been assembled at its berth and nothing has commanded it — the cruise is
        // this test's STIMULUS and is not ordered for another eighty lines. The seatX/Y/Z beside it
        // are SUBSPACE and carry no such condition.
        int sx = (int) Math.round(seat.shipWorldX);
        int sy = (int) Math.round(seat.shipWorldY);
        int sz = (int) Math.round(seat.shipWorldZ);

        String health = exec("artest player health");
        Reply nameMReply = Reply.of(health);
        String botName = nameMReply.text(PLAYER_NAME);

        scenario().requireArranged("the bot must enter the cell",
                readBool(exec("artest space enter " + botName + " " + dim
                        + " " + sx + " " + sy + " " + sz), "ok"));
        bot().waitTicks(20);
        int clientDim = bot().reportWeather().get("dim").getAsInt();
        scenario().requireArranged("the client must have followed into the cell — it renders dim "
                + clientDim + ", the ship is in " + dim, clientDim == dim);

        // The subject is a mount on a MOVING SHIP whose data-watcher is quiet - a passenger seat,
        // not the pilot's. This is not a detail: a mount bound to a LINKED pilot seat republishes six
        // flight-telemetry floats, and each write re-pins its own tracking anchor, which is vanilla's
        // third refresh disjunct. Measured on that subject: at 4.075 blocks/tick the anchor never fell
        // more than 3.34 blocks behind a 16-block range - the mechanism cannot be put under load at
        // any speed VS will allow. A mount one block off the pilot seat resolves no flight computer,
        // publishes nothing, and is carried by the same ship - which is exactly a passenger's chair.
        int mountX = seatX + 1, mountY = seatY, mountZ = seatZ;
        String mount = "";
        boolean mounted = false;
        int dummyId = -1;
        // The CLIENT's mark before the FIRST attempt: every pass performs a real mount, so the
        // record that closes the wait below may belong to any of them.
        long seatClientMark = clientEvents().mark();
        for (int attempt = 0; attempt < 5 && !mounted; attempt++) {
            String mountAt = exec("artest vs seat-mount-at " + dim
                    + " " + mountX + " " + mountY + " " + mountZ);
            scenario().requireArranged("seat-mount-at must spawn the seat dummy: " + mountAt,
                    readBool(mountAt, "ok"));
            dummyId = readInt(mountAt, "dummyId");
            mount = exec("artest player mount-entity " + dummyId);
            // absence is the answer: this is a retry loop, and "not yet" is what it is reading for.
            mounted = Reply.of(mount).boolOr("mounted", false);
            if (!mounted) {
                bot().waitTicks(10);
            }
        }
        scenario().requireArranged("the bot must mount the pilot-seat dummy: " + mount, mounted);
        // THE CLIENT'S OWN SEATING, as a link. The ten ticks that stood here produced the red whose
        // text is three lines below — "the mount reported success and he is off ten ticks later" —
        // and under four client forks it was replication lag: the server reported him riding a live
        // dummy carrying one passenger while the client had not applied the packet yet.
        awaitClientMount(seatClientMark, "the client must report the bot seated before anything"
                + " else — this whole scenario is about a rider the client is rendering aboard",
                SEAT_LINK_BUDGET_TICKS, "");
        // The same discriminator the mid-transit relog scenario carries, and for the same reason:
        // "he is not seated" is produced BOTH by something removing the dummy under him and by
        // something dismounting him from a dummy that is still there, and this class exists for
        // precisely the contract those two break. `deck-capture <dim> <id>` answers "entity not
        // found" for a removed entity; read only on the failing path.
        if (!riding()) {
            scenario().arrangementFailed("the client must report the bot seated before anything else"
                    + " — the mount reported success and he is off ten ticks later. Whether the seat"
                    + " dummy (entity " + dummyId + ") still EXISTS separates a removal under him"
                    + " from a dismount: mountReply=" + mount
                    + " dummyNow=" + exec("artest vs deck-capture " + dim + " " + dummyId)
                    + " serverSaysRiding=" + exec("artest player riding-of " + botName));
        }

        // The instrument's own proof: it must be able to say FALSE. Without this leg, the cruise
        // assertion below is green on a reporter that is simply stuck on true.
        long offClientMark = clientEvents().mark();
        exec("artest player dismount");
        // The control's own far side is a record too: the client LETTING GO is what makes the
        // reporter's FALSE mean something, and ten ticks were the same bet as above in reverse.
        awaitClientDismount(offClientMark, "CONTROL: the client must be able to report NOT riding —"
                + " otherwise the cruise leg below is green on a reporter stuck on true",
                SEAT_LINK_BUDGET_TICKS);
        assertFalse("CONTROL: the client must be able to report NOT riding - otherwise the cruise"
                        + " leg cannot fail", riding());

        mounted = false;
        // The CLIENT's mark, before the re-seat is ordered: the control below reads the
        // client, and the link is what says the mount reached it.
        long reseatClientMark = clientEvents().mark();
        for (int attempt = 0; attempt < 5 && !mounted; attempt++) {
            String mountAt = exec("artest vs seat-mount-at " + dim
                    + " " + mountX + " " + mountY + " " + mountZ);
            mount = exec("artest player mount-entity " + readInt(mountAt, "dummyId"));
            // absence is the answer: this is a retry loop, and "not yet" is what it is reading for.
            mounted = Reply.of(mount).boolOr("mounted", false);
            if (!mounted) {
                bot().waitTicks(10);
            }
        }
        scenario().requireArranged("the bot must be re-seated after the control: " + mount, mounted);
        // WAS `waitTicks(10)` and a read. The server says it seated him; this reads the
        // CLIENT, and a budget between the two is an assertion about replication speed.
        ridingOnceTheClientHasRemounted(reseatClientMark, CLIENT_REMOUNT_BUDGET_TICKS);
        scenario().requireArranged("seated again before the cruise", riding());

        // ---- STIMULUS: a COASTING horizontal cruise. Three properties, each load-bearing.
        //
        // HORIZONTAL, because vanilla's visibility test reads X and Z and ignores Y entirely: a
        // climb cannot exhibit this at any speed. (That is also why the reported flight over a
        // planet - a vertical take-off - was symptom-free while the same speed in a cell was not.)
        //
        // COASTING, because the mount republishes six flight-telemetry floats and every write
        // dirties its DataWatcher, which is the third disjunct of vanilla's anchor-refresh gate. A
        // pilot who is actively working the controls, or a ship whose speed is still changing,
        // re-pins the tracking anchor every tick and can never be evicted at any speed. The held
        // throttle is therefore set ONCE, to a constant, and the leg then waits for the speed to
        // stop changing. In the report's own log the unseating bursts fall exactly in the seconds
        // where the pilot-input trace is silent - this is that state.
        //
        // NOT at the cell's pose band: Y is irrelevant to the mechanism, and a ship teleported to
        // Y ~ 2,000,000 froze the client outright (measured; a separate defect, ledgered).
        //
        // SUBSTITUTION, named here because a shortcut nobody wrote down is a hole nobody weighs at
        // the go/no-go: a human reaches cruise on his own
        // throttle. This fixture cannot - measured, `with-pilot-seat` tops out at ~0.15 blocks/tick
        // under its own power and no higher-thrust variant exists - so the SPEED is commanded while
        // the throttle stays held. What that replaces is only how the ship gets moving; the mount,
        // the rider, the seat binding and the tracking are all the production objects.
        // The throttle is deliberately NOT held here: measured, a held throttle makes the flight
        // computer fight the commanded velocity and the ship falls to ~0.11 blocks/tick, well under
        // the speed this leg needs. A coasting ship at a commanded constant is both faster and
        // quieter, which is the state the report was flown in.
        // Commanded by the name this scenario has held since the assembly. The identity used to be
        // re-derived here from a bounded read at the seat's reported spot — a second answer to a
        // question already answered, and one a neighbour's craft can give.
        String shipId = scenarioShipId;
        scenario().requireArranged("this scenario's ship must be managed before it is commanded;"
                + " id=" + shipId, ShipInfo.loadedIn(this::exec, dim, shipId));

        String commanded = exec("artest vs force-vel-by-id " + dim + " " + shipId
                + " " + COMMANDED_SPEED_BLOCKS_PER_SECOND + " 0 0");
        scenario().requireArranged("the cruise command must reach THIS ship's flight computer: "
                + commanded, readBool(commanded, "commanded"));

        double steady = Double.NaN, prev = Double.NaN;
        for (int attempt = 0; attempt < 60 && Double.isNaN(steady); attempt++) {
            ShipInfo s0 = ShipInfo.byId(this::exec, dim, shipId);
            bot().waitTicks(SETTLE_SAMPLE_TICKS);
            ShipInfo s1 = ShipInfo.byId(this::exec, dim, shipId);
            double speed = Math.hypot(s1.x - s0.x, s1.z - s0.z) / SETTLE_SAMPLE_TICKS;
            if (speed > EVICTION_THRESHOLD_BLOCKS_PER_TICK
                    && !Double.isNaN(prev) && Math.abs(speed - prev) < STEADY_EPSILON) {
                steady = speed;
            }
            prev = speed;
        }
        scenario().requireArranged("the ship must reach a STEADY cruise above "
                + EVICTION_THRESHOLD_BLOCKS_PER_TICK + " blocks/tick - while it is still accelerating"
                + " the mount re-pins its own tracking anchor every tick and nothing can be evicted,"
                + " so an unsettled ship makes this leg unfalsifiable. Last speed sample: " + prev,
                !Double.isNaN(steady));

        ShipInfo before = ShipInfo.byId(this::exec, dim, shipId);
        double x0 = before.x, z0 = before.z;
        double px0 = bot().reportState().get("playerX").getAsDouble();
        double pz0 = bot().reportState().get("playerZ").getAsDouble();

        // A transient wants cumulative counters plus a bounded history, never a first/last snapshot:
        // the mount is expected to come and go several times per second. The client's own view of
        // the mount ENTITY is sampled next to the riding flag, because the entity is what the server
        // evicts - if that count never drops, the eviction never happened and the riding flag has
        // nothing to report.
        int notRiding = 0, samples = 0, mountMissing = 0, riderUntracked = 0;
        double maxAnchorLag = 0.0;
        StringBuilder trace = new StringBuilder();
        for (int t = 0; t < OBSERVE_TICKS; t += POLL_EVERY_TICKS) {
            bot().waitTicks(POLL_EVERY_TICKS);
            samples++;
            boolean seated = riding();
            int mounts = bot().reportEntities("EntityDummy", 64.0).get("count").getAsInt();
            // Server-side CAUSE, sampled next to the client-side consequence: if the rider stops
            // being tracked, or the mount's published anchor drifts past the tracking range, the
            // client is about to lose the mount. Without this the run can only say THAT the seat
            // held, never why - and a green would not distinguish "the guard worked" from "the
            // arrangement never put the mechanism under load".
            String track = exec("artest vs mount-tracking " + dim);
            // absence is the answer for `seatedRider`: the verb writes `{"error":"world not
            // loaded"}` with no fields at all when the dimension is between worlds, which across a
            // jump is exactly the moment this counter exists to describe — and a refusing read
            // there would end the measurement window with a complaint about the instrument.
            // `riderTracks` stays a refusing read on purpose: the producer always writes it on a
            // reply that says `seatedRider` is true, so the `&&` reaches it only where it is
            // there — and a default would quietly turn "not tracked" into "nothing to report".
            if (Reply.of(track).boolOr("seatedRider", false)
                    && !readBool(track, "riderTracks")) {
                riderUntracked++;
            }
            double lagX = Events.number(track, "anchorLagX");
            double lagZ = Events.number(track, "anchorLagZ");
            if (!Double.isNaN(lagX)) {
                maxAnchorLag = Math.max(maxAnchorLag, lagX);
            }
            if (!Double.isNaN(lagZ)) {
                maxAnchorLag = Math.max(maxAnchorLag, lagZ);
            }
            if (!seated) {
                notRiding++;
            }
            if (mounts == 0) {
                mountMissing++;
            }
            trace.append(seated ? (mounts == 0 ? 'M' : '.') : (mounts == 0 ? 'X' : 'x'));
        }
        System.out.println("[#163] seated/mount trace ('.' seated+mount present, 'x' unseated,"
                + " 'M' mount gone, 'X' both): " + trace);

        ShipInfo after = ShipInfo.byId(this::exec, dim, shipId);
        double shipDX = after.x - x0, shipDZ = after.z - z0;
        double shipTravel = Math.hypot(shipDX, shipDZ);
        double perTickX = Math.abs(shipDX) / OBSERVE_TICKS;
        double perTickZ = Math.abs(shipDZ) / OBSERVE_TICKS;
        double fastestAxis = Math.max(perTickX, perTickZ);
        double playerTravel = Math.hypot(
                bot().reportState().get("playerX").getAsDouble() - px0,
                bot().reportState().get("playerZ").getAsDouble() - pz0);

        String measured = " [ship " + shipTravel + " blocks over " + OBSERVE_TICKS + " ticks ="
                + fastestAxis + "/tick on its fastest axis (x=" + perTickX + ", z=" + perTickZ + ");"
                + " client player travelled " + playerTravel + " blocks; " + samples + " samples,"
                + " " + notRiding + " unseated, " + mountMissing + " with the mount gone,"
                + " " + riderUntracked + " with the rider NOT in the mount's tracking set,"
                + " worst anchor lag " + maxAnchorLag + " blocks vs a 16-block range]";
        System.out.println("[#163] measured:" + measured);

        // Both checked BEFORE the verdict. A ship that did not cruise, or a mount that did not
        // carry the player with it, makes the poll below unfalsifiable - and that is an
        // arrangement failure, not a passing build.
        scenario().requireArranged("the ship must cruise faster than the mount's own tracking headroom"
                + " (" + EVICTION_THRESHOLD_BLOCKS_PER_TICK + " blocks/tick) or this leg cannot"
                + " exhibit the fault." + measured,
                fastestAxis > EVICTION_THRESHOLD_BLOCKS_PER_TICK);
        scenario().requireArranged("the seated player must TRAVEL WITH the ship - a mount that stays"
                + " put keeps its tracking anchor fresh, so nothing here could ever be evicted and"
                + " the leg would pass on any build." + measured,
                playerTravel > shipTravel * 0.5);

        assertEquals("the client threw the rider off his mount " + notRiding + " of " + samples
                + " samples while the ship cruised:"
                + " a rider must never lose the vehicle he is riding." + measured,
                0, notRiding);
    }
}
