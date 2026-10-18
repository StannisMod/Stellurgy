package dev.stannismod.stellurgy.test.client;

import dev.stannismod.stellurgy.test.*;
import dev.stannismod.stellurgy.test.contract.LegacyWitness;
import dev.stannismod.stellurgy.test.contract.Wait;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import static org.junit.Assert.*;

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
public class VSRiderKeepsHisMountAtCruiseTest extends AbstractSharedVsClientTest {

    @Override
    protected String subsystem() {
        return "vs-rider-mount-at-cruise";
    }

    private static final String PLAYER_NAME = "player";

    /**
     * The mount is registered with a tracking range of 16 blocks and an anchor republished every 20
     * ticks, so its anchor falls behind by {@code speed x 20} blocks between refreshes: past
     * 16/20 = 0.8 blocks/tick on either horizontal axis it leaves its own box. The cruise must clear
     * that, or the leg proves nothing.
     */
    private static final double EVICTION_THRESHOLD_BLOCKS_PER_TICK = 0.8;

    /** Commanded cruise, blocks/SECOND (the physics velocity unit): 2 blocks/tick, the reported speed. */
    private static final double COMMANDED_SPEED_BLOCKS_PER_SECOND = 40.0;

    /** Ticks between the two samples a cruise-speed measurement is taken from — twenty, so a
     *  reading's quantization is half what a ten-tick one carries (measured below). */
    private static final int SETTLE_SAMPLE_TICKS = 20;

    /**
     * Ticks from the cruise command before its speed is judged. Measured 2026-09-24 in ten-tick
     * samples: 0.58, 1.63, 2.07, 2.00, 2.07, 2.27, 2.00 blocks/tick — at cruise by the third sample
     * (~30 ticks), so sixty is twice that.
     */
    private static final int CRUISE_RAMP_TICKS = 60;

    /** How long the CLIENT is given to perform a seating or a release the server has already done,
     *  in ticks — a ceiling on one round trip. */
    private static final int SEAT_LINK_BUDGET_TICKS = 200;

    /**
     * How close two successive twenty-tick speed readings must be for the cruise to count as STEADY.
     * From the same measurement: at cruise, successive ten-tick readings differed by up to 0.27
     * (sampling quantization — twenty ticks halves it to ~0.13), while the ramp's successive steps
     * were 1.05 and 0.43. 0.2 sits between the two. The 0.002 it replaced was met only when two
     * readings happened to come out identical, on the seventh attempt of a loop.
     */
    private static final double STEADY_TOLERANCE = 0.2;

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

    /**
     * The rider keeps his mount through a coasting horizontal cruise faster than the mount's
     * tracking headroom.
     */
    @LegacyWitness(prose = "with {@code MixinEntityTrackerRiderSeesVehicle#ar$aRiderNeverLosesItsVehicle} at"
            + " {@code cir.setReturnValue(true);} removed — no longer forcing the mount visible to its own rider —"
            + " this fails with \"the client threw the rider off his mount 24 of 40"
            + " samples\", worst anchor lag 40.0 blocks against a 16-block range — 2026-09-28, on the stimulus as"
            + " it stands after the settle became a dose and a two-speed window.")
    @Test
    @Wait(shape = Wait.Shape.EXPERIMENT, at = "advanceServerAndClient(CRUISE_RAMP_TICKS);",
            why = "CRUISE_RAMP_TICKS from the command for the ship to reach its cruise (measured, see the"
                    + " constant); then the cruise is judged ONCE, by the window below.")
    @Wait(shape = Wait.Shape.WINDOW, at = "ShipInfo s0 = ShipInfo.byId(c -> read(c).raw(), dim, shipId);",
            why = "three reads, two consecutive SETTLE_SAMPLE_TICKS-long speeds; the verdict names both. The"
                    + " physics velocity controller never decides it has arrived, so no record answers.")
    @Wait(shape = Wait.Shape.WINDOW, at = "ShipInfo s1 = ShipInfo.byId(c -> read(c).raw(), dim, shipId);",
            why = "a cruise speed is a value, not an event; no record answers.")
    @Wait(shape = Wait.Shape.WINDOW, at = "ShipInfo s2 = ShipInfo.byId(c -> read(c).raw(), dim, shipId);",
            why = "the second speed of the pair.")
    @Wait(shape = Wait.Shape.WINDOW, at = "for (int t = 0; t < OBSERVE_TICKS; t += POLL_EVERY_TICKS) {",
            why = "it COUNTS how many samples of a cruise found the rider unseated, his mount missing or the"
                    + " anchor lagging, plus the WORST lag seen. Every one of those is a statistic over the"
                    + " cruise, and the contract is about the cruise rather than about any tick of it. What it"
                    + " cannot see: a seat lost and regained inside one sampling gap.")
    public void aSeatedRiderNeverLosesTheMountHeIsRidingWhileTheShipCruises() throws Exception {

        // Headless: nobody is near the ship between probe calls, so pin it loaded. This is
        // arrangement only - what is under test is what the client is TOLD about an entity it
        // already has, not whether the ship loads.

        int dim = TransitSetup.empty(c -> arrange(c).raw()).originDim;

        // The cell is a void world, so this is not about escaping terrain — it is about ONE
        // definition of where a fixture stands instead of a 64 nobody chose. The first link still
        // earns its place: it MEASURES that the cell is empty rather than taking the setup probe's
        // word for it.
        //
        // NOT ALLOCATED FROM A PLOT, deliberately. A plot keeps a scenario clear of its siblings in
        // a SHARED world; this craft is alone in a cell made for it, so there is nobody to be kept
        // clear of, and moving it into a region of that dimension whose extent nobody has measured
        // would be a real risk taken for no contract.
        final dev.stannismod.stellurgy.test.FixtureSite site =
                dev.stannismod.stellurgy.test.FixtureSite.openAir(dim, 40, 40);
        int bx = site.x, by = site.y, bz = site.z;
        ShipAssembly built = ShipAssembly.at(site, c -> arrange(c).raw(), c -> act(c).raw(), serverEvents(),
                "with-pilot-seat", 2, 16, "the craft whose rider must stay aboard through the cruise");
        String assembled = built.reply;
        scenario().requireArranged("a with-pilot-seat build must route to a ship, making no rocket: "
                + built, built.madeNoRocket());

        // The seat inside the craft this scenario BUILT, named by the assembler that minted it. The
        // anchored form resolved the yard nearest a point over the whole registry, so it could reach
        // a neighbour's craft — or a blockless crossing remnant — and report seatFound for it. The
        // craft is loaded before the rider is put on it: the link is its own `ship_usable`.
        String durableShipId = ShipIdentity.nameFromAssembly(assembled);
        disposeOfShipAtEnd(durableShipId);
        String scenarioShipId = ShipIdentity.awaitPhysicsIdOf(c -> read(c).raw(), serverEvents(), dim, durableShipId, 200);
        PilotSeat seat = PilotSeat.byId(c -> read(c).raw(), dim, scenarioShipId)
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

        String health = read("stellurgytest player health").raw();
        Reply nameMReply = Reply.of(health);
        String botName = nameMReply.text(PLAYER_NAME);

        long enterMark = clientEvents().mark();
        scenario().requireArranged("the bot must enter the cell",
                readBool(arrange("stellurgytest space enter " + botName + " " + dim
                        + " " + sx + " " + sy + " " + sz).raw(), "ok"));
        awaitClientDim(enterMark, dim, "the client must follow the bot into the ship's cell");

        // The subject is a mount on a MOVING SHIP whose data-watcher is quiet - a passenger seat,
        // not the pilot's. This is not a detail: a mount bound to a LINKED pilot seat republishes six
        // flight-telemetry floats, and each write re-pins its own tracking anchor, which is vanilla's
        // third refresh disjunct. Measured on that subject: at 4.075 blocks/tick the anchor never fell
        // more than 3.34 blocks behind a 16-block range - the mechanism cannot be put under load at
        // any speed VS will allow. A plain seat one block off the pilot seat resolves no flight
        // computer, publishes nothing, and is carried by the same ship - which is exactly a passenger's
        // chair. It is a real seat BLOCK, set into the craft's shipyard beside the pilot seat: a dummy
        // with no rider stands only on a seat (EntityDummy#seatStands), so one bound to an empty cell
        // removes itself on its first tick, before anybody can be put on it.
        int mountX = seatX + 1, mountY = seatY, mountZ = seatZ;
        String chair = arrange("stellurgytest fill " + dim + " " + mountX + " " + mountY + " " + mountZ
                + " " + mountX + " " + mountY + " " + mountZ + " stellurgy:seat").raw();
        scenario().requireArranged("the passenger seat must be set into the craft beside the pilot seat: "
                + chair, Reply.of(chair).integerOr("placed", 0) == 1);
        // ONE mount. It used to be retried five times ten ticks apart, which would hide WHY a first
        // mount is refused — and a refused mount is the arrangement's own failure, reported with the
        // server's answer. The client's mark goes before it: the link below is this mount's.
        long seatClientMark = clientEvents().mark();
        String mountAt = arrange("stellurgytest vs seat-mount-at " + dim
                + " " + mountX + " " + mountY + " " + mountZ).raw();
        scenario().requireArranged("seat-mount-at must spawn the seat dummy: " + mountAt,
                readBool(mountAt, "ok"));
        int dummyId = readInt(mountAt, "dummyId");
        String mount = arrange("stellurgytest player mount-entity " + dummyId).raw();
        // `mounted` is missing only from the probe's "entity not found" reply, which names
        // `playerDim`, `foundInDim` and `gone` — so absence is the answer: a refused mount, reported
        // with that reply.
        scenario().requireArranged("the bot must mount the pilot-seat dummy: " + mount,
                Reply.of(mount).boolOr("mounted", false));
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
                    + " dummyNow=" + read("stellurgytest vs deck-capture " + dim + " " + dummyId).raw()
                    + " serverSaysRiding=" + read("stellurgytest player riding-of " + botName).raw());
        }

        // The instrument's own proof: it must be able to say FALSE. Without this leg, the cruise
        // assertion below is green on a reporter that is simply stuck on true.
        long offClientMark = clientEvents().mark();
        arrange("stellurgytest player dismount");
        // The control's own far side is a record too: the client LETTING GO is what makes the
        // reporter's FALSE mean something, and ten ticks were the same bet as above in reverse.
        awaitClientDismount(offClientMark, "CONTROL: the client must be able to report NOT riding —"
                + " otherwise the cruise leg below is green on a reporter stuck on true",
                SEAT_LINK_BUDGET_TICKS);
        assertFalse("CONTROL: the client must be able to report NOT riding - otherwise the cruise"
                        + " leg cannot fail", riding());

        // The CLIENT's mark, before the re-seat is ordered: the control below reads the
        // client, and the link is what says the mount reached it. ONE re-seat, for the reason above.
        long reseatClientMark = clientEvents().mark();
        String remountAt = arrange("stellurgytest vs seat-mount-at " + dim
                + " " + mountX + " " + mountY + " " + mountZ).raw();
        scenario().requireArranged("seat-mount-at must spawn the seat dummy again: " + remountAt,
                readBool(remountAt, "ok"));
        mount = arrange("stellurgytest player mount-entity " + readInt(remountAt, "dummyId")).raw();
        // Same reading as the first mount: absence is the answer, a refused mount.
        scenario().requireArranged("the bot must be re-seated after the control: " + mount,
                Reply.of(mount).boolOr("mounted", false));
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
                + " id=" + shipId, ShipInfo.loadedIn(c -> read(c).raw(), dim, shipId));

        String commanded = arrange("stellurgytest vs force-vel-by-id " + dim + " " + shipId
                + " " + COMMANDED_SPEED_BLOCKS_PER_SECOND + " 0 0").raw();
        scenario().requireArranged("the cruise command must reach THIS ship's flight computer: "
                + commanded, readBool(commanded, "commanded"));

        advanceServerAndClient(CRUISE_RAMP_TICKS);
        ShipInfo s0 = ShipInfo.byId(c -> read(c).raw(), dim, shipId);
        advanceServerAndClient(SETTLE_SAMPLE_TICKS);
        ShipInfo s1 = ShipInfo.byId(c -> read(c).raw(), dim, shipId);
        advanceServerAndClient(SETTLE_SAMPLE_TICKS);
        ShipInfo s2 = ShipInfo.byId(c -> read(c).raw(), dim, shipId);
        double first = Math.hypot(s1.x - s0.x, s1.z - s0.z) / SETTLE_SAMPLE_TICKS;
        double second = Math.hypot(s2.x - s1.x, s2.z - s1.z) / SETTLE_SAMPLE_TICKS;
        scenario().requireArranged("the ship must be in a STEADY cruise above "
                + EVICTION_THRESHOLD_BLOCKS_PER_TICK + " blocks/tick - while it is still accelerating"
                + " the mount re-pins its own tracking anchor every tick and nothing can be evicted,"
                + " so an unsettled ship makes this leg unfalsifiable. Speeds over two consecutive "
                + SETTLE_SAMPLE_TICKS + "-tick windows, " + CRUISE_RAMP_TICKS + " ticks after the"
                + " command: " + first + ", " + second + " (steady within " + STEADY_TOLERANCE + ")",
                first > EVICTION_THRESHOLD_BLOCKS_PER_TICK && second > EVICTION_THRESHOLD_BLOCKS_PER_TICK
                        && Math.abs(first - second) < STEADY_TOLERANCE);

        ShipInfo before = ShipInfo.byId(c -> read(c).raw(), dim, shipId);
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
            advanceServerAndClient(POLL_EVERY_TICKS);
            samples++;
            boolean seated = riding();
            int mounts = bot().reportEntities("EntityDummy", 64.0).get("count").getAsInt();
            // Server-side CAUSE, sampled next to the client-side consequence: if the rider stops
            // being tracked, or the mount's published anchor drifts past the tracking range, the
            // client is about to lose the mount. Without this the run can only say THAT the seat
            // held, never why - and a green would not distinguish "the guard worked" from "the
            // arrangement never put the mechanism under load".
            String track = read("stellurgytest vs mount-tracking " + dim).raw();
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

        ShipInfo after = ShipInfo.byId(c -> read(c).raw(), dim, shipId);
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

    // --- a pilot is shown on his seat (C30 ENT-7, ENT-8) -------------------------------------------

    /**
     * How far beyond the launchpad's footprint the pilot-seat fixture reaches: its assembler and its
     * structure tower each stand one block outside the pad (the fixture's own layout).
     */
    private static final int SEAT_FIXTURE_HALO = 1;

    /**
     * How far above the site the pilot-seat fixture and its seated pilot reach: the structure tower's
     * top course is the site plus six, and a pilot seated on the seat (the site plus five) has his head
     * near the site plus seven; eight covers both.
     */
    private static final int SEAT_FIXTURE_HEIGHT = 8;

    /**
     * How far the craft is lifted off its pad before it flies. The TEST'S OWN: enough that no part of
     * the hull is in contact with the pad, whose contact tips an asymmetric hull (the lift's own
     * javadoc), and small against the room the open-air band leaves above the site.
     */
    private static final int SEAT_LIFT_CLEARANCE_BLOCKS = 10;

    /**
     * How long the pilot holds his vertical key, in ticks of the craft's world. An EXPERIMENT: the dose
     * that makes the craft fly. Forty ticks is two thirds of the setpoint's full sweep
     * ({@code FA_SETPOINT_MAX_SPEED / SETPOINT_RAMP} = 60 ticks), so the craft is still accelerating at
     * release and its pose changes on every tick of the window — which is what makes a rider a tick
     * behind his seat distinguishable from one on it.
     */
    private static final int SEAT_CLIMB_DOSE_TICKS = 40;

    /**
     * How many client ticks of the window the craft must have MOVED on for the verdict to mean anything,
     * and by how much. A still craft shows a rider a tick behind his seat at the seat exactly, so ticks
     * on which the seat's image did not move are ticks on which the verdict cannot fail. Half the dose is
     * far below what a forty-tick climb gives and far above zero; a tenth of a block per tick is below
     * the ramp's first full tick (0.05 + 0.05 blocks/tick) and above float noise.
     */
    private static final int SEAT_MIN_MOVING_TICKS = SEAT_CLIMB_DOSE_TICKS / 2;
    private static final double SEAT_MOVED_BLOCKS = 0.1;

    /**
     * How far a rider may stand from his seat's image plus vanilla's seating offset and still be ON it,
     * in blocks: double rounding of world coordinates of a few hundred, and nothing else. A rider placed
     * through a pose a tick old is a whole tick's travel — at least {@link #SEAT_MOVED_BLOCKS} on every
     * tick the verdict counts — away.
     */
    private static final double SEAT_ON_HIS_SEAT_BLOCKS = 1.0e-6;

    /**
     * A seated pilot, on a craft in flight, stands exactly where his seat is on every tick, on both sides
     * (C30 ENT-8), and the physics substrate's carry of bodies that touched a hull never acts on him (C30
     * ENT-7: a rider is placed by his vehicle alone).
     *
     * <p><b>Where it reads.</b> At the HEAD of each side's tick, the state the previous tick left:
     * {@code seat_rider_shown} on the client — every frame since the last tick has drawn the rider toward
     * his current position and the craft toward its current pose, so these are what the pilot sees both
     * arrive at — and {@code seat_rider_placed} on the server, where his position is what the tracker
     * sends and the next tick starts from. Each takes the rider and the seat block's image through the
     * craft's current pose on that side in one read. A rider glued to the pose BEFORE the end-of-tick
     * advance stands a whole tick's travel below a climbing seat.</p>
     *
     * <p><b>Why the craft has a DECK.</b> The substrate arms its carry of a body only when the body's own
     * move meets the hull, and a pilot on a seat over open air never meets it — with the seat's disarm
     * removed, the carry stayed unarmed through a whole climb (2026-10-17), so on that craft the ENT-7
     * verdict could not go red. A seated rider stands 0.15 below the bottom of his seat's block (the seat
     * entity sits 0.2 into it, vanilla seats a player 0.35 below his vehicle), so over a deck his box
     * meets the deck's top on every move — the shape of a real cockpit, and the one the carry arms on.
     * The carry's state is read off the rider at the head of his seat's update ({@code dummy_glue}'s
     * {@code riderDrag}): the substrate applies it at the end of a tick, after the seat's
     * {@code updatePassenger}, so a carry still held there is one the seat did not disarm.</p>
     *
     * <p><b>It does not see</b>: the input pipeline into the seat — a probe binds the seat and seats him,
     * and the climb is the pilot's key; and the frame mathematics of the seat's image, which the
     * instruments compute with the same function the seat is glued with — this sees WHEN and by WHOM he
     * is placed. It stages one region: a straight vertical climb of an upright craft, so a rotating
     * craft's carry of the rider's YAW is not visited.</p>
     */
    @Test
    @dev.stannismod.stellurgy.test.contract.Pins({"ENT-7", "ENT-8"})
    @dev.stannismod.stellurgy.test.contract.Regions(of = "ENT-8", value = "bound seat")
    @dev.stannismod.stellurgy.test.contract.RedWitness(member = "ClientDeckFollowsItsShip#afterTheShipsHaveMoved",
            at = "EntityDummy.followBoundSeats(Minecraft.getMinecraft().world);", replacedBy = "",
            fails = "a seated pilot must be shown exactly where his seat is shown on the client", on = "2026-10-17",
            verdict = "ENT-8 on the client")
    @dev.stannismod.stellurgy.test.contract.RedWitness(member = "DeckFollowsItsShip#afterTheShipsHaveMoved",
            at = "dev.stannismod.stellurgy.entity.EntityDummy.followBoundSeats(event.world);", replacedBy = "",
            fails = "a seated pilot must be placed exactly where his seat is placed on the server", on = "2026-10-17",
            verdict = "ENT-8 on the server")
    @dev.stannismod.stellurgy.test.contract.RedWitness(member = "EntityDummy#updatePassenger",
            at = "VSIntegration.suppressShipDrag(passenger);", replacedBy = "",
            fails = "a seated pilot must be placed by his seat alone (C30 ENT-7)", on = "2026-10-17",
            verdict = "ENT-7")
    @Wait(shape = Wait.Shape.EXPERIMENT, at = "liftClearOfThePad(craft.physicsId, SEAT_LIFT_CLEARANCE_BLOCKS);",
            why = "the lift is arrangement whose own stretches are claimed and gated on it; nothing read after"
                    + " it here is a reading of its settle — the next read is the seat, which the lift carries")
    @Wait(shape = Wait.Shape.EXPERIMENT, at = "climbOnPilotKey(0, SEAT_CLIMB_DOSE_TICKS,",
            why = "the dose IS the experiment: the craft must fly for the placed positions of a rider and"
                    + " his seat to be able to differ at all, and the windows read after it are the flight")
    public void aSeatedPilotIsShownOnHisSeatOnEveryTickOfAFlight() throws Exception {
        FixtureSite site = site();
        Events.Probe arranging = c -> arrange(c).raw();
        String what = "a piloted craft whose seated pilot is watched against his seat in flight";
        Reply fixture = AssembledCraft.lay(site, arranging, "with-pilot-deck", SEAT_FIXTURE_HALO,
                SEAT_FIXTURE_HEIGHT, what);
        AssembledCraft craft = AssembledCraft.assemble(site, c -> act(c).raw(), serverEvents(), fixture, what);
        disposeOfShipAtEnd(craft.physicsId);
        liftClearOfThePad(craft.physicsId, SEAT_LIFT_CLEARANCE_BLOCKS);

        int seatId = SeatMount.onShip(c -> arrange(c).raw(), 0, craft.physicsId)
                .requireSeatFound(what).requireDummyId();
        long seating = clientEvents().mark();
        Reply mounted = arrange("stellurgytest player mount-entity " + seatId);
        scenario().requireArranged("the pilot must be seated on his craft's pilot seat: " + mounted,
                mounted.ok());
        awaitClientMount(seating, "the client must seat the pilot on the craft's pilot seat before his"
                + " flight is watched", SEAT_LINK_BUDGET_TICKS, "");

        long flight = clientEvents().mark();
        long serverFlight = serverEvents().markInstrumented();
        climbOnPilotKey(0, SEAT_CLIMB_DOSE_TICKS, "the pilot's held vertical key must reach his craft's"
                + " flight computer, or the craft does not fly and nothing below can differ");

        assertOnHisSeat("client", "shown", clientEvents().since(flight, "seat_rider_shown"),
                "seat_rider_shown_events", seatId);
        assertOnHisSeat("server", "placed", serverEvents().since(serverFlight, "seat_rider_placed"),
                "seat_rider_placed_events", seatId);

        String glue = clientEvents().since(flight, "dummy_glue");
        Events.assertInstrumentRan(glue, "dummy_glue_events", "the rider's carry state must be observed at"
                + " all before its absence can be read");
        java.util.List<String> seatUpdates = Events.recordsWhere(glue, "e", String.valueOf(seatId));
        int ridden = 0;
        int carried = 0;
        String firstCarried = null;
        for (String update : seatUpdates) {
            com.google.gson.JsonElement riderDrag = new com.google.gson.JsonParser().parse(update)
                    .getAsJsonObject().get("riderDrag");
            if (riderDrag == null || !riderDrag.isJsonObject()) {
                // an update of the seat with nobody on it carries no rider to have carried
                continue;
            }
            ridden++;
            com.google.gson.JsonObject drag = riderDrag.getAsJsonObject();
            if (drag.get("addedVelX").getAsDouble() != 0.0 || drag.get("addedVelY").getAsDouble() != 0.0
                    || drag.get("addedVelZ").getAsDouble() != 0.0) {
                carried++;
                if (firstCarried == null) {
                    firstCarried = update;
                }
            }
        }
        System.out.println("[seat-rider] ridden seat updates=" + ridden + " carried=" + carried);
        scenario().requireArranged("the pilot's seat must have been updated with him on it during the"
                + " flight, or nothing says what carried him: " + glue, ridden >= SEAT_MIN_MOVING_TICKS);
        assertEquals("a seated pilot must be placed by his seat alone (C30 ENT-7): the physics substrate"
                        + " held a carry for him on " + carried + " of " + ridden + " of his seat's"
                        + " updates, first " + firstCarried,
                0, carried);
    }

    /**
     * The verdict of ENT-8 on one side: over the window's records of THIS seat's rider, on every tick his
     * craft moved, he stands at his seat's image plus vanilla's seating offset.
     *
     * @param side  which side's records these are, for the message
     * @param verb  what that side does with his position, for the message
     */
    private void assertOnHisSeat(String side, String verb, String window, String instrument, int seatId)
            throws Exception {
        Events.assertInstrumentRan(window, instrument, "the " + side + "'s view of a seated rider against"
                + " his seat must be observed at all before it can be read as on his seat");
        java.util.List<String> samples = Events.recordsWhere(window, "mount", String.valueOf(seatId));
        int moving = 0;
        int offSeat = 0;
        double worstOff = 0.0;
        String firstOff = null;
        double lastSeatX = Double.NaN;
        double lastSeatY = Double.NaN;
        double lastSeatZ = Double.NaN;
        for (String sample : samples) {
            Reply s = Reply.of(sample);
            scenario().requireArranged("the " + side + " must find the pilot's seat on his craft on every"
                    + " tick of the flight: " + sample, s.bool("seatFound"));
            double seatX = s.number("seatX");
            double seatY = s.number("seatY");
            double seatZ = s.number("seatZ");
            if (!Double.isNaN(lastSeatX) && Math.sqrt((seatX - lastSeatX) * (seatX - lastSeatX)
                    + (seatY - lastSeatY) * (seatY - lastSeatY)
                    + (seatZ - lastSeatZ) * (seatZ - lastSeatZ)) >= SEAT_MOVED_BLOCKS) {
                moving++;
                double dx = s.number("riderX") - seatX;
                double dy = s.number("riderY") - (seatY + s.number("offsetY"));
                double dz = s.number("riderZ") - seatZ;
                double off = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (off > SEAT_ON_HIS_SEAT_BLOCKS) {
                    offSeat++;
                    if (firstOff == null) {
                        firstOff = sample;
                    }
                }
                worstOff = Math.max(worstOff, off);
            }
            lastSeatX = seatX;
            lastSeatY = seatY;
            lastSeatZ = seatZ;
        }
        System.out.println("[seat-rider] " + side + " samples=" + samples.size() + " moving=" + moving
                + " offSeat=" + offSeat + " worstOff=" + worstOff);
        scenario().requireArranged("the craft must FLY under the pilot for " + SEAT_MIN_MOVING_TICKS
                + " " + side + " ticks of the window, or a rider a tick behind his seat would be " + verb
                + " on it: it moved on " + moving + " of " + samples.size() + " ticks: " + samples,
                moving >= SEAT_MIN_MOVING_TICKS);
        assertEquals("a seated pilot must be " + verb + " exactly where his seat is " + verb + " on the "
                        + side + ", on every tick his craft moves (C30 ENT-8): on " + offSeat + " of " + moving
                        + " such ticks he was not, worst " + worstOff + " blocks off, first " + firstOff,
                0, offSeat);
    }
}
