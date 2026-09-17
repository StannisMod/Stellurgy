package zmaster587.advancedRocketry.test.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.github.stannismod.forge.testing.client.RealClientHarness;
import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.lwjgl.input.Keyboard;

import zmaster587.advancedRocketry.test.SubsystemStatus;
import zmaster587.advancedRocketry.test.SeatMount;
import zmaster587.advancedRocketry.test.Reply;
import zmaster587.advancedRocketry.test.ShipInfo;
import zmaster587.advancedRocketry.test.Events;
import zmaster587.advancedRocketry.test.FixtureSite;
import zmaster587.advancedRocketry.test.Plot;

import static org.junit.Assert.assertTrue;

/**
 * A seated pilot must be able to fly his ship WHILE THE SPACE SUBSYSTEM IS LIVE.
 *
 * <p>This is the same stimulus as the plain pilot-keys e2e - a real client player on the pilot seat
 * holding the real vertical-up key - with one difference: the production space subsystem is switched
 * ON. That difference is the whole point.</p>
 *
 * <p><b>Why this test has to exist separately.</b> The subsystem stands down whenever it detects a
 * test harness, so EVERY other tier-2 flight test on this branch runs with the branch's own headline
 * feature disabled. In real play it is the other way round: {@code enableSpaceSubsystem} defaults to
 * true and Valkyrien Skies is on by default for {@code runClient}/{@code runServer}, so a player is
 * always flying with the subsystem live. The flight computer's tick has subsystem-dependent branches
 * (a hyperspace park gate, a cell pose report, the entry on-ramp and the descent check), and NONE of
 * them are reachable in a harness run that leaves the subsystem down. The config flag seeded below
 * opts the production wiring back in, which is what makes this the configuration a player actually
 * runs.</p>
 *
 * <p>Manual server + client lifecycle rather than the shared base class, because the config has to be
 * written into the game directory BEFORE the server boots and the base class owns a throwaway root
 * it never exposes.</p>
 *
 * <p></p>
 */
public class VSPilotKeysWithSpaceSubsystemE2ETest {

    private static final String BUILDER_POS = "builderPos";
    private static final String DUMMY_ID = "dummyId";

    private static final String VARIANT = "with-pilot-seat";
    /**
     * This scenario's patch of world. The lane keeps the coordinates this test's green runs were
     * taken on; the SITE inside it is allocated, so what it clears is checked against the plot
     * instead of being trusted. This class boots its own server and runs one scenario — it does not
     * extend a shared base, which is why it allocates here rather than calling {@code site()}.
     *
     * <p>Not static: a plot records the ground its scenario has cleared, and that record belongs to
     * the test instance that made it.</p>
     */
    private final Plot plot =
            Plot.forScenario(0, "the pilot-keys craft", 0, new Plot.Lane(2800, 2800, Plot.SIZE));
    private final FixtureSite site = plot.site();
    /** The site owns the coordinates; these aliases keep the body below unchanged. */
    private final int bx = site.x, by = site.y, bz = site.z;

    /**
     * How long the craft is given to become USABLE with the client present, in ticks.
     *
     * <p>A deadline for an event production publishes, not a guess at how long loading takes: the
     * poll it replaced spent forty rounds of five ticks asking a probe, so this is that same
     * ceiling, spent waiting for the record instead of sampling for its consequence.</p>
     */
    private static final int SHIP_LOAD_BUDGET_TICKS = 200;

    /** How long the CLIENT is given to APPLY a placement the server has already written, in ticks —
     *  a ceiling on one round trip. */
    private static final int PLACEMENT_LINK_BUDGET_TICKS = 200;
    private static final String BOT = "ForgeTestClient";

    /** The ship must gain at least this much altitude while the key is held, or it is not flying. */
    private static final double MIN_CLIMB = 1.0;

    private Path root;
    private RealDedicatedServerHarness serverHarness;
    private RealClientHarness clientHarness;

    @Before
    public void startBoth() throws Exception {
        Assume.assumeTrue("Server harness disabled - set -D" + AbstractHeadlessServerTest.PROP_HARNESS_ENABLED + "=true",
                Boolean.parseBoolean(System.getProperty(
                        AbstractHeadlessServerTest.PROP_HARNESS_ENABLED, "false")));
        Assume.assumeTrue("Client harness disabled - set -D" + AbstractClientE2ETest.PROP_CLIENT_ENABLED + "=true",
                Boolean.parseBoolean(System.getProperty(
                        AbstractClientE2ETest.PROP_CLIENT_ENABLED, "false")));

        root = Files.createTempDirectory("forge-pilot-with-space-");

        serverHarness = RealDedicatedServerHarness.startWith(root, false);
        try {
            clientHarness = RealClientHarness.start(serverHarness);
        } catch (Exception startFailed) {
            serverHarness.close();
            serverHarness = null;
            throw startFailed;
        }
    }

    @After
    public void stopBoth() throws Exception {
        Exception first = null;
        if (clientHarness != null) {
            try {
                clientHarness.close();
            } catch (Exception e) {
                first = e;
            }
            clientHarness = null;
        }
        if (serverHarness != null) {
            try {
                serverHarness.close();
            } catch (Exception e) {
                if (first == null) {
                    first = e;
                } else {
                    first.addSuppressed(e);
                }
            }
            serverHarness = null;
        }
        if (first != null) {
            throw first;
        }
    }

    @Test
    public void aSeatedPilotCanStillFlyHisShipWhileTheSpaceSubsystemIsRegistered() throws Exception {

        // The subsystem must actually be up, or this test silently degrades into the plain
        // pilot-keys case and its green would mean nothing.
        SubsystemStatus status = SubsystemStatus.read(this::exec);
        assertTrue("the production space subsystem must be REGISTERED for this test to be about "
                + "anything - the seeded config is what opts it in: " + status.raw(),
                status.registered);

        // The CLIENT's own log, for the two placements this scenario depends on. This class boots
        // its own harness pair rather than extending the tier's base, so the shared wait is reached
        // through its static form instead of an inherited method.
        Events clientLog = ClientEvents.of(clientHarness.bot());
        long awayMark = clientLog.mark();
        exec("tp @a " + (bx + 600) + " 120 " + (bz + 600) + " 0 0");
        ClientEvents.awaitPlacedNear(clientLog, awayMark, bx + 600, bz + 600,
                "the assembly below must run with no observer near it, and the observer is a client",
                PLACEMENT_LINK_BUDGET_TICKS);

        // The event log, built by hand because this class boots its own harness pair rather than
        // sharing the tier's base: the probe channel is the server's, and the client supplies the
        // clock the reader steps on. Nothing else about it differs.
        Events events = new Events(this::exec, ticks -> clientHarness.bot().waitTicks(ticks));
        long spawnMark = events.markInstrumented();
        String assemble = assembleFixture(site, VARIANT);
        assertTrue("a with-pilot-seat build must route to a ship: " + assemble,
                assemble.contains("\"ok\":true"));

        // The ship's IDENTITY comes off the registry's own record of it being added, since a mark
        // taken before the assembly was queued — not off a nearest-ship lookup at the build site a
        // tick later, which answers about whichever loaded ship is closest to a point.
        String spawned = events.await(spawnMark, "ship_spawned",
                "assembly must create a VS ship in the queryable registry (async spawn)", 400);
        final String shipUuid = Events.lastField(spawned, "vsShip");
        assertTrue("a ship_spawned record must name the ship: " + spawned, shipUuid != null);

        // Stand the client next to the ship so it stays loaded, then read its resting altitude.
        long approachMark = clientLog.mark();
        exec("tp @a " + (bx + 0.5) + " " + (by + 6) + " " + (bz + 0.5) + " 0 0");
        ClientEvents.awaitPlacedNear(clientLog, approachMark, bx + 0.5, bz + 0.5,
                "\"stand the client next to the ship so it stays loaded\" is the arrangement, and it"
                        + " is true when the CLIENT is there, not when the server says so",
                PLACEMENT_LINK_BUDGET_TICKS);

        // A ship becoming LOADED **does** have an event of its own, and this comment said otherwise
        // until the poll below was converted: production publishes `ShipLoadedEvent` when a craft
        // becomes steppable, recorded as `ship_usable` and carrying both of its identities. So the
        // wait is that record for THIS ship, and the position is read once afterwards — a poll of
        // the probe could only say that a lookup eventually answered, never when the craft became
        // usable, and on expiry it reported the last empty reply as though that were the finding.
        events.awaitField(spawnMark, "ship_usable","vsShip", shipUuid,
                "the ship the registry named must become USABLE with the client present — until it"
                        + " is, its physics are not stepped and every reading below describes a"
                        + " craft that cannot move", SHIP_LOAD_BUDGET_TICKS);
        String atBase = exec("artest vs ship-info 0 id " + shipUuid);
        double yBefore = ShipInfo.isLoaded(atBase) ? ShipInfo.of(atBase).y : Double.NaN;
        assertTrue("the ship the registry named must LOAD with the client present within 200 ticks."
                        + " The lookup's own answer IS the diagnosis and this message used to throw"
                        + " it away: a reply carrying \"managed\":false means the physics mod does not"
                        + " own it yet - a different wait, not a longer one. ship=" + shipUuid
                        + " reply=" + atBase.replace('\n', ' '),
                !Double.isNaN(yBefore));

        SeatMount mountInfo = SeatMount.onShip(this::exec, 0, shipUuid);
        // The reader REFUSES a reply with no bound dummy, which is what this asserted.
        long seatMark = clientLog.mark();
        String mount = exec("artest player mount-entity " + mountInfo.requireDummyId());
        assertTrue("bot must mount the seat dummy: " + mount, mount.contains("\"mounted\":true"));
        // The key below goes through the real client input path, and a client that is not yet
        // riding routes it somewhere else entirely — so the seating is a link, not ten ticks.
        ClientEvents.awaitMounted(clientLog, seatMark,
                "the client must be riding the seat before a pilot key is pressed on it",
                PLACEMENT_LINK_BUDGET_TICKS);

        // The real key, through the real client input path, exactly as a player holds it.
        final double y0 = yBefore;
        long flightMark = events.markInstrumented();
        clientHarness.bot().holdKey(Keyboard.KEY_R); // flightVerticalUp
        ClientPoll.Result<Double> lift;
        try {
            // Event-gated hover-lift (bounded ceiling + early exit): the loop returns the moment the
            // ship has climbed, so the ceiling is patience and not how far it flies.
            // The probe keeps the tolerant nullable ship-info parse (returns the baseline when a reply
            // is unparseable), so the predicate holds only on a genuine climb.
            lift = ClientPoll.until(clientHarness.bot()::waitTicks,
                    () -> {
                        String sample = exec("artest vs ship-info 0 id " + shipUuid);
                        return ShipInfo.isLoaded(sample) ? ShipInfo.of(sample).y : y0;
                    },
                    y -> (y - y0) >= MIN_CLIMB, 5, 40);
        } finally {
            clientHarness.bot().releaseKey(Keyboard.KEY_R);
        }
        double yAfter = lift.value;

        // The LINK before the number. The climb poll's probe deliberately answers with the baseline
        // when a ship-info reply is unparseable, so a ship that unloaded and one that never moved
        // produce the same red — and neither says whether the key ever reached the ship at all.
        // Asserting the delivery first splits those apart: past this line the input demonstrably got
        // to the flight computer, so a flat altitude is about the physics and nothing else.
        events.await(flightMark, "pilot_input_delivered", "the held key must reach the ship's flight"
                + " computer while the space subsystem is registered - until this link is on the"
                + " record, an altitude that did not move says nothing about flight", 400);

        assertTrue("a seated pilot holding the vertical-up key must lift his ship even with the space "
                        + "subsystem registered - this is the configuration every real player runs, and "
                        + "it is the ONLY tier-2 flight configuration no other test covers. The input"
                        + " demonstrably reached the computer (see the link above), so this red is"
                        + " about the flight itself. "
                        + "yBefore=" + yBefore + " yAfter=" + yAfter + " ship=" + shipUuid
                        + " subsystem=" + status.raw(),
                (yAfter - yBefore) >= MIN_CLIMB);
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", serverHarness.client().execute(cmd));
    }

    private String assembleFixture(FixtureSite site, String variant) throws Exception {
        // The site owns the coordinates; these aliases keep the body below unchanged.
        final int baseX = site.x, baseY = site.y, baseZ = site.z;
        // FIRST link: the volume the craft is built in and CLIMBS out of is EMPTY, measured by the
        // air fill's own `placed`. Open air, so this ASSERTS rather than digging the shaft it
        // replaces — a scenario whose subject is the pilot's keys reading a climb cannot afford a
        // craft that meets the rim instead, because the red then accuses the key path.
        site.requireClear(this::exec, 2, 16,
                "the hull, and the first blocks of the lane the pilot's key drives it up");
        String fixture = exec("artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " " + variant);
        assertTrue("fixture (" + variant + ") failed: " + fixture, fixture.contains("\"ok\":true"));
        int[] bp = Reply.of(fixture).blockPos(BUILDER_POS);
        assertTrue("fixture missing builderPos: " + fixture, bp != null);
        return exec("artest rocket assemble 0 " + bp[0] + " " + bp[1] + " " + bp[2]);
    }
}
