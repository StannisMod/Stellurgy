package zmaster587.advancedRocketry.test.client;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.github.stannismod.forge.testing.TestTimeouts;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;
import org.lwjgl.input.Keyboard;

import static org.junit.Assert.assertTrue;

/**
 * Both answers the space-entry crossing can give a pilot who flies his ship through the atmosphere
 * ceiling under his own power — GRANTED and REFUSED — on one client.
 *
 * <ul>
 *   <li><b>Granted.</b> He arrives in the cell still seated, not falling, still in control, and
 *       carrying the durable aboard record a logout in space is restored from. A play-tested failure
 *       of this seam reported exactly the inverse: the pilot off his ship, falling, in a black
 *       cell.</li>
 *   <li><b>Refused.</b> A refusal costs him nothing but a message: he stays in his seat, in the
 *       launch world, and is told why. The historical defect was an order-of-operations one — the
 *       crew was captured (dismounted, mounts retired) BEFORE the pool was asked, so a refusal left
 *       the pilot standing beside a ship that never went anywhere.</li>
 * </ul>
 *
 * <p>Both fly the real on-ramp: a real client on the pilot seat holds the real vertical-up key and
 * the flight computer's own tick notices the ship above the dimension's orbit line. Every earlier
 * entry test started the crossing artificially, so nothing verified the seam a player actually flies
 * through. Each leg opens with an in-run CONTROL leg — plain flight far below the line — so a red
 * indicts the crossing rather than the cockpit.</p>
 *
 * <h2>What made these one class</h2>
 *
 * <p>They were two, each paying its own server + client boot because each wrote its own
 * {@code advancedRocketry.cfg}. The settings never disagreed: both want the same
 * {@code orbitHeight=255}, and only one cares about the pool size. What they really contend for is
 * the POOL ITSELF, in opposite directions — one needs room in it, the other needs none — and that is
 * a shared-world problem with three shared-world answers, none of which is "put the world back":</p>
 *
 * <ol>
 *   <li>the pool is SIZED for what the family does — one slot for the ship that settles, one for the
 *       cells the refusing scenario fills and hands back ({@link #seedGameDirectory});</li>
 *   <li>{@link #resetFamilyStateBeforeTeleport} releases the PROBE-held cells and nothing else: a
 *       settled craft leaves a cell by descending, and evicting one would make every later assertion
 *       about a subsystem no player can reach;</li>
 *   <li>each scenario MEASURES the state its premise names instead of assuming it — the refusing one
 *       occupies until the pool says exhausted, and claims only that its own entry ledgered nothing
 *       (a delta), never that space is globally empty.</li>
 * </ol>
 *
 * <p>So neither scenario depends on which of them ran first.</p>
 *
 * <p>Setup shortcuts, named: the bot boards via the {@code vs seat-mount} probe + mount-entity (the
 * harness cannot right-click a post-assembly ship-subspace block); pool pressure comes from a
 * probe-held cell occupant, not ten real ships. Neither changes the path under test — the flight
 * computer's own tick fires the entry against the production subsystem.</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class VSShipEntryClientGroupE2ETest extends AbstractSharedVsClientE2ETest {

    @Override
    protected String subsystem() {
        return "vs-ship-entry";
    }

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern POS_Y = Pattern.compile("\"posY\":(-?[0-9.E\\-]+)");
    private static final Pattern DUMMY_ID = Pattern.compile("\"dummyId\":(-?\\d+)");
    private static final Pattern LEDGER = Pattern.compile("\"ledger\":(-?\\d+)");
    private static final Pattern SLOT_DIMS = Pattern.compile("\"slotDims\":\\[([0-9,\\-]*)]");
    private static final Pattern AFC_X = Pattern.compile("\"afcX\":(-?\\d+)");
    private static final Pattern AFC_Y = Pattern.compile("\"afcY\":(-?\\d+)");
    private static final Pattern AFC_Z = Pattern.compile("\"afcZ\":(-?\\d+)");
    private static final Pattern VEL_Y = Pattern.compile("\"velY\":(-?[0-9.E\\-]+)");
    /** Ledger #264 discriminator: the seat's own delivery counters, sampled across the climb. */
    private static final Pattern RECEIVED = Pattern.compile("\"received\":(\\d+)");
    private static final Pattern DELIVERED = Pattern.compile("\"delivered\":(\\d+)");
    /** The ship's own attitude, so a climb that goes nowhere can be told from one that goes SIDEWAYS. */
    private static final Pattern Q_X = Pattern.compile("\"qx\":(-?[0-9.E\\-]+)");
    private static final Pattern Q_Z = Pattern.compile("\"qz\":(-?[0-9.E\\-]+)");
    private static final Pattern P_X = Pattern.compile("\"posX\":(-?[0-9.E\\-]+)");
    private static final Pattern P_Z = Pattern.compile("\"posZ\":(-?[0-9.E\\-]+)");

    /** The account every client harness launches under; the server keys his player data by it. */
    private static final String BOT = "ForgeTestClient";

    private static final String VARIANT = "with-pilot-seat";
    private static final int BY = 64;
    /** The granted leg's build site — each scenario keeps the ground its green runs were taken on. */
    private static final int GRANTED_BX = 2800, GRANTED_BZ = 2800;
    /** The refused leg's, a hundred blocks clear of it. */
    private static final int REFUSED_BX = 3000, REFUSED_BZ = 3000;

    /** The seeded atmosphere ceiling: the config key's minimum, so the climb stays short. */
    private static final int ORBIT_LINE = 255;

    /** Control leg: the ship must demonstrably fly at all before either entry leg means anything. */
    private static final double MIN_CONTROL_CLIMB = 1.0;

    /** Arrival free-fall discriminator: two seconds of genuine free fall drop ~20 blocks; a parked
     *  ship's settle jitter is well under this. */
    private static final double MAX_ARRIVAL_SINK = 5.0;

    /** The refusal message's stable needle (en_US: "Space is saturated - the ship cannot enter
     *  orbit right now. Descend and try again later."). */
    private static final String REFUSAL_NEEDLE = "space is saturated";

    /**
     * The cells this family's PROBE occupants are parked on — a fixed, family-owned list, so the
     * reset can hand them back without having to discover what the previous scenario did.
     */
    private static final int[] PRESSURE_CELLS = {90, 91, 92, 93};

    @Override
    protected void seedGameDirectory(GameDirSeed seed) {
        // Pull the orbit line down to the config key's minimum so a powered climb is seconds rather
        // than minutes; the trigger predicate is the same whatever the number.
        seed.config("rockets", "I:orbitHeight", ORBIT_LINE, getClass());
        // TWO slots, and the number is derived rather than chosen. One is the smallest pool a
        // refusal can be provoked in, and it is what each of these scenarios used alone — but a ship
        // that SETTLES holds its slot for the rest of the class and nothing may take it back: a
        // settled craft leaves a cell by descending, so a reset that unbound it would be fault
        // injection dressed as housekeeping. So the pool carries one slot for the scenario that can
        // settle a ship, plus one the refusing scenario can fill and hand back. A third scenario that
        // settles would need a third slot.
        seed.config("performance", "I:spaceCellPoolSize", 2, getClass());
    }

    /**
     * Hand back the cells this family's PROBE occupants hold — and nothing else.
     *
     * <p>The pool is the one thing these two scenarios pull in opposite directions: the refusing one
     * fills it, the granted one needs room in it. What this does NOT do is evict a ship that has
     * SETTLED — a settled craft leaves a cell by descending, so unbinding one here would be fault
     * injection dressed as housekeeping, and every later assertion would then be about a subsystem no
     * player can reach. The pool is sized for that instead; see {@link #seedGameDirectory}.</p>
     *
     * <p>Asserted like every other channel on this base: a scenario that started with the pool still
     * full would report the SYMPTOM — an entry refused, or granted, for a reason that is not its
     * own.</p>
     */
    @Override
    protected void resetFamilyStateBeforeTeleport() throws Exception {
        super.resetFamilyStateBeforeTeleport();

        String status = exec("artest space subsystem-status");
        if (!status.contains("\"registered\":true")) {
            // Nothing to hand back — the subsystem is not up, and the scenario's own arrangement says
            // so far more usefully than a reset would.
            return;
        }
        for (int cell : PRESSURE_CELLS) {
            String released = exec("artest space release " + cell + " 0 0");
            assertTrue("a probe-held cell must be handed back between scenarios, or the next"
                    + " scenario's pool is full for a reason that has nothing to do with its own"
                    + " premise. cell=" + cell + " reply=" + released,
                    released.contains("\"ok\":true"));
        }
        scenario().record("spaceAtStart", exec("artest space subsystem-status").replace('\n', ' '));
    }

    // ── granted: he flies up, and arrives seated and in control ─────────────────────────────────

    @Test
    public void aPilotWhoClimbsThroughTheCeilingArrivesSeatedAndInControl() throws Exception {

        String status = exec("artest space subsystem-status");
        scenario().requireArranged("the production space subsystem must be REGISTERED - the seeded "
                + "config opts it in: " + status, status.contains("\"registered\":true"));

        int budget = (int) (40 * TestTimeouts.factor());
        String shipUuid = boardAssembledCraftAt(GRANTED_BX, GRANTED_BZ, budget);
        double yRest = shipY(shipUuid);
        scenario().requireArranged("the ship must report an altitude before it is flown: "
                + shipInfoById(shipUuid), !Double.isNaN(yRest));

        // ---- CONTROL LEG: plain flight works far below the line, or the entry leg is void. ----
        double yControl = yRest;
        bot().holdKey(Keyboard.KEY_R);
        try {
            for (int attempt = 0; attempt < budget && (yControl - yRest) < MIN_CONTROL_CLIMB; attempt++) {
                bot().waitTicks(5);
                double y = shipY(shipUuid);
                if (!Double.isNaN(y)) {
                    yControl = y;
                }
            }
            scenario().requireArranged("control leg: the pilot must be able to fly AT ALL before the "
                            + "entry leg can indict the crossing. yRest=" + yRest + " yControl="
                            + yControl, (yControl - yRest) >= MIN_CONTROL_CLIMB);
            System.out.println("[GATE-STATS after control leg] " + clientGateStats());

            // ---- ENTRY LEG: keep climbing until the ledger records the settled entry. ---------
            // While the crossing runs, the origin-world ship vanishes (the cut), so posY going
            // silent is progress, not failure; the ledger is the single source of arrival truth.
            // THE MULTIPLIER STAYS: a held key is sampled and re-sent per CLIENT TICK (on change,
            // plus a re-assert every PilotInputCadence.REPEAT_TICKS), so a loaded box stretches the
            // climb through the client's TICK rate. NOT per rendered frame — that reading was
            // refuted 2026-08-21.
            int climbBudget = (int) (800 * TestTimeouts.factor());
            int ledger = 0;
            double lastY = yControl;
            for (int attempt = 0; attempt < climbBudget && ledger < 1; attempt++) {
                bot().waitTicks(5);
                if (attempt % 4 == 3) {
                    Matcher lm = LEDGER.matcher(exec("artest space subsystem-status"));
                    if (lm.find()) {
                        ledger = Integer.parseInt(lm.group(1));
                    }
                } else {
                    double y = shipY(shipUuid);
                    if (!Double.isNaN(y)) {
                        lastY = y;
                    }
                }
            }
            System.out.println("[GATE-STATS after entry leg] " + clientGateStats());
            System.out.println("[ARRIVAL-TRACE entry leg] " + exec("artest vs arrival-trace"));
            assertTrue("a ship climbing under its own power past the orbit line (" + ORBIT_LINE
                            + ") must be taken by the entry crossing and SETTLE in a cell - the whole "
                            + "on-ramp a real player flies. lastSeenY=" + lastY
                            + " ledger=" + ledger + " delivery=" + exec("artest vs seat-delivery")
                            + " subsystem=" + exec("artest space subsystem-status"),
                    ledger >= 1);
        } finally {
            bot().releaseKey(Keyboard.KEY_R);
        }

        // ---- ARRIVAL: the three play-reported symptoms, measured from the client. -------------
        String statusAfter = exec("artest space subsystem-status");
        Matcher sd = SLOT_DIMS.matcher(statusAfter);
        scenario().requireArranged("subsystem-status must list slot dims: " + statusAfter, sd.find());
        String slotDims = "," + sd.group(1) + ",";

        // (1) The client's OWN world is a slot dim (the client followed the crossing).
        int clientDim = Integer.MIN_VALUE;
        // THE MULTIPLIER STAYS. This waits for state the SERVER restores on login to arrive at the
        // client and be applied - a round trip whose latency is the machine's, not the game's.
        int arrivalBudget = (int) (40 * TestTimeouts.factor());
        for (int attempt = 0; attempt < arrivalBudget; attempt++) {
            bot().waitTicks(5);
            JsonObject weather = bot().reportWeather();
            if (weather.has("dim")) {
                clientDim = weather.get("dim").getAsInt();
                if (slotDims.contains("," + clientDim + ",")) {
                    break;
                }
            }
        }
        assertTrue("after a granted entry the CLIENT itself must be in a space-cell dimension - "
                        + "the pilot follows his ship through the seam. clientDim=" + clientDim
                        + " slotDims=[" + sd.group(1) + "] status=" + statusAfter,
                slotDims.contains("," + clientDim + ","));

        // (2) Still seated: two consecutive positive samples (a lost seat reads riding=true for a
        // packet-lag moment, never twice with a wait between).
        JsonObject riding = bot().reportRidingEntity();
        boolean prev = isRiding(riding);
        boolean seatedTwice = false;
        for (int attempt = 0; attempt < arrivalBudget && !seatedTwice; attempt++) {
            bot().waitTicks(5);
            riding = bot().reportRidingEntity();
            seatedTwice = prev && isRiding(riding);
            prev = isRiding(riding);
        }
        // Position-writer timeline for the arrival, printed win-or-lose: the harness deletes its
        // child workdirs on close, so the only way to read the writers post-run is through the
        // test's own stdout. Both halves come from their side's event log, which test-only mixins
        // feed — the client half used to be a static-field read of a ring that production carried.
        System.out.println("[ARRIVAL-TRACE server] " + exec("artest vs arrival-trace"));
        System.out.println("[ARRIVAL-TRACE client] " + bot().eventsSince(0, null));
        assertTrue("the pilot who FLEW his ship into space must still be in his seat on arrival - "
                        + "a crossing must never stand him up. riding=" + riding
                        + " delivery=" + exec("artest vs seat-delivery"),
                seatedTwice);

        // (3) Not falling: over a two-second window the client-rendered altitude must not sink
        // like a body in free fall.
        double y0 = clientPlayerY();
        bot().waitTicks(40);
        double y1 = clientPlayerY();
        assertTrue("the arrived pilot must NOT be in free fall (clientY " + y0 + " -> " + y1
                        + " over 40 ticks; free fall sinks ~20). riding=" + bot().reportRidingEntity(),
                (y0 - y1) < MAX_ARRIVAL_SINK);

        // (4) Still in control: the key lifts the ARRIVED ship - measured from the rider's own
        // client-rendered altitude (the pilot rides what the key moves).
        double before = clientPlayerY();
        double after = before;
        bot().holdKey(Keyboard.KEY_R);
        try {
            for (int attempt = 0; attempt < budget && (after - before) < MIN_CONTROL_CLIMB; attempt++) {
                bot().waitTicks(5);
                after = clientPlayerY();
            }
        } finally {
            bot().releaseKey(Keyboard.KEY_R);
        }
        assertTrue("the pilot must still CONTROL his ship after the crossing - the fresh seat "
                        + "binding on the re-assembled ship must carry his input. clientY " + before
                        + " -> " + after + " (need +" + MIN_CONTROL_CLIMB + ")"
                        + " delivery=" + exec("artest vs seat-delivery"),
                (after - before) >= MIN_CONTROL_CLIMB);

        // (5) He carries the durable aboard record. That record - not his dimension id, which is a
        // per-boot slot number - is what a logout in space is restored from; without it the login
        // lands him at his overworld spawn with no message while his ship stays in orbit. The pilot
        // who boarded on the PLANET and flew up is precisely the route that never produced one while
        // the record was written only by the mount transition. Polled: it is maintained from state on
        // the server tick, so it becomes true some ticks after the arrival rather than at it.
        String tag = "";
        for (int attempt = 0; attempt < arrivalBudget && !tag.contains("\"tagged\":true"); attempt++) {
            tag = exec("artest space aboard-tag " + BOT);
            if (!tag.contains("\"tagged\":true")) {
                bot().waitTicks(5);
            }
        }
        assertTrue("a pilot who flew his own ship into a cell must carry a durable aboard record - "
                        + "it is the only evidence the login restore has that he was ever aboard. "
                        + "tag=" + tag + " riding=" + bot().reportRidingEntity()
                        + " status=" + exec("artest space subsystem-status"),
                tag.contains("\"tagged\":true"));
    }

    // ── refused: he stays in his seat, in the launch world, and is told why ──────────────────────

    @Test
    public void aRefusedEntryLeavesThePilotSeatedWithAMessage() throws Exception {

        String status = exec("artest space subsystem-status");
        scenario().requireArranged("the production space subsystem must be REGISTERED - the seeded "
                + "config opts it in: " + status, status.contains("\"registered\":true"));

        // Fill the pool with foreign cells and PROVE it is full: a further occupy must come back
        // exhausted, or a later "refused" observation is unattributable.
        //
        // Filled by MEASUREMENT rather than by counting on a pool of one. The seed does ask for one
        // slot, but a scenario whose whole premise is "the pool is full" must not take the pool's
        // SIZE on trust from a config file it does not read — and on a shared client it must not
        // assume the previous scenario left it empty either.
        // The ledger's size BEFORE this scenario: a craft the granted scenario settled is a
        // legitimate resident of this world, so what this scenario may claim is that ITS OWN entry
        // ledgered nothing — never that space is globally empty.
        int ledgerBefore = ledgerSize();

        String occupy = "";
        boolean exhausted = false;
        for (int cell : PRESSURE_CELLS) {
            occupy = exec("artest space occupy " + cell + " 0 0");
            if (occupy.contains("\"exhausted\":true")) {
                exhausted = true;
                break;
            }
            scenario().requireArranged("the pool must accept an occupant on cell " + cell
                    + " or say it is exhausted; it said neither: " + occupy,
                    occupy.contains("\"ok\":true"));
        }
        scenario().requireArranged("instrument control: with the slots held, a further occupy must be"
                + " REFUSED - else the pool is not actually exhausted and the entry would be"
                + " granted: " + occupy, exhausted);

        int budget = (int) (40 * TestTimeouts.factor());
        String shipUuid = boardAssembledCraftAt(REFUSED_BX, REFUSED_BZ, budget);
        double yRest = shipY(shipUuid);
        scenario().requireArranged("the ship must report an altitude before it is flown: "
                + shipInfoById(shipUuid), !Double.isNaN(yRest));

        // ---- CONTROL LEG: plain flight works far below the line, or the refusal leg is void. ----
        double yControl = yRest;
        String refusalLine = null;
        double maxShipY = yRest;
        StringBuilder climb = new StringBuilder(64);
        StringBuilder diag = new StringBuilder(64);
        bot().holdKey(Keyboard.KEY_R);
        try {
            for (int attempt = 0; attempt < budget && (yControl - yRest) < MIN_CONTROL_CLIMB; attempt++) {
                bot().waitTicks(5);
                // BY IDENTITY: this loop's whole subject is a ship LEAVING the base, so the base is
                // the one point it is guaranteed not to be at by the end.
                double y = shipY(shipUuid);
                if (!Double.isNaN(y)) {
                    yControl = y;
                }
            }
            scenario().requireArranged("control leg: the pilot must be able to fly AT ALL before the "
                            + "refusal leg can indict the entry. yRest=" + yRest + " yControl="
                            + yControl, (yControl - yRest) >= MIN_CONTROL_CLIMB);

            // ---- REFUSAL LEG: keep climbing until the refusal message lands in the CLIENT chat.
            // The exhausted pool refuses the entry the moment the ship crosses the line; the
            // pilot's own chat is where the player reads it (i18n already resolved).
            //
            // The gate is sampled along the way, sparsely (every ~100 ticks - the readout scans the
            // ship's subspace yard, so a per-poll read would be a load source in the very climb it
            // is watching). Without it a missing message has four indistinguishable explanations:
            // the ship never reached the line, the trigger declined, the entry was declined, or the
            // message was sent to nobody. The last sample and the highest altitude seen are what
            // the assertion below reports.
            int climbBudget = (int) (800 * TestTimeouts.factor());
            for (int attempt = 0; attempt < climbBudget && refusalLine == null; attempt++) {
                bot().waitTicks(5);
                refusalLine = chatLineContaining(REFUSAL_NEEDLE);
                if (attempt % 10 == 0) {
                    // The ship's own state, asked BY IDENTITY and off the registry - it neither
                    // force-loads the ship's subspace yard nor touches a chunk, so the climb it is
                    // watching gets exactly the resources it would have got unwatched.
                    String s = shipInfoById(shipUuid);
                    // THE DISCRIMINATOR for ledger #264, sampled ACROSS the dying climb rather than
                    // after it. Three candidate causes, and the climb trace alone cannot separate
                    // them: the tile instance is being replaced under the ship (afcIdentity changes),
                    // the computer is not ticking at all (controllerTicks flat), or the packet
                    // arrives and is refused at the seat's pilot guard (received climbs while
                    // delivered does not). Sampled at the same cadence as the altitude so the two
                    // timelines line up tick for tick.
                    if (diag.length() < 900) {
                        String d = exec("artest vs seat-delivery");
                        diag.append(' ').append(attempt).append(":recv=")
                                .append(firstGroupOr(RECEIVED, d, "?"))
                                .append("/deliv=").append(firstGroupOr(DELIVERED, d, "?"));
                    }
                    Matcher py = POS_Y.matcher(s);
                    Matcher vy = VEL_Y.matcher(s);
                    if (py.find()) {
                        double y = Double.parseDouble(py.group(1));
                        maxShipY = Math.max(maxShipY, y);
                        // A bounded timeline, not a last-value snapshot: a climb that stops is a
                        // shape, and the tick it changed shape at is the whole question. The
                        // VERTICAL VELOCITY rides along because an altitude that stops rising
                        // cannot say whether the ship is being held, braked or simply not pushed.
                        // upY (the world-frame Y of the ship's OWN up, from its attitude) and the
                        // horizontal distance travelled ride along for one reason: the pilot's
                        // "climb" is a SHIP-FRAME command, so on a tilted hull it is mostly
                        // horizontal thrust. A flat altitude with upY well under 1 and a growing
                        // travel is a ship flying SIDEWAYS, which is a different bug from a ship
                        // that is not being pushed at all - and the two are identical in a
                        // y/velY trace.
                        Matcher qx = Q_X.matcher(s), qz = Q_Z.matcher(s);
                        Matcher px = P_X.matcher(s), pz = P_Z.matcher(s);
                        double upY = Double.NaN, horiz = Double.NaN;
                        if (qx.find() && qz.find()) {
                            double ax = Double.parseDouble(qx.group(1));
                            double az = Double.parseDouble(qz.group(1));
                            upY = 1.0 - 2.0 * (ax * ax + az * az);
                        }
                        if (px.find() && pz.find()) {
                            double dx = Double.parseDouble(px.group(1)) - REFUSED_BX;
                            double dz = Double.parseDouble(pz.group(1)) - REFUSED_BZ;
                            horiz = Math.sqrt(dx * dx + dz * dz);
                        }
                        if (climb.length() < 1400) {
                            climb.append(' ').append(attempt).append(':')
                                    .append(String.format(Locale.ROOT, "%.1f", y))
                                    .append('/')
                                    .append(vy.find() ? vy.group(1) : "?")
                                    .append(String.format(Locale.ROOT, "/up=%.2f/horiz=%.1f",
                                            upY, horiz));
                        }
                    }
                }
            }
        } finally {
            bot().releaseKey(Keyboard.KEY_R);
        }
        // Printed on the GREEN path too, deliberately. This class's red is intermittent and only
        // appears under the loaded gate, so its trace is otherwise unreadable on the run you can
        // actually iterate on - and the shape of a healthy climb is what tells you whether a sick
        // one differs in altitude, in attitude, or only in speed.
        System.out.println("[entryrefused] maxShipY=" + maxShipY + " yRest=" + yRest
                + " climb(attempt:y/velY/up/horiz)=[" + climb.toString().trim() + "]");
        // The gate is read HERE, on the failing path only, for the same reason the climb is sampled
        // passively above: it resolves the ship through its subspace yard, which force-loads chunks.
        // A missing refusal has four explanations - the ship never reached the line, the trigger
        // declined, the entry declined, or nobody was there to tell - and these two readings
        // separate all four. `lastDecision` NEVER-ASKED with a maxShipY under the ceiling is the
        // first of them, and it exonerates every part of the entry path.
        String gate = exec("artest space entry-gate 0 " + shipUuid);

        // THE PRECONDITION, before the verdict: a refusal can only be missing if a refusal was ever
        // ASKED FOR, and that needs the craft to have crossed the ceiling. Declared here rather than
        // folded into the message below, because the two need opposite responses — a craft that never
        // got there says nothing whatever about the refusal path, while one that did and was told
        // nothing is the defect this scenario exists for.
        if (maxShipY < ORBIT_LINE) {
            scenario().step(Scenario.Phase.PRECONDITION,
                    "measure how high the craft actually got before judging the refusal");
            requireUprightForAnAltitudeClaim(shipInfoById(shipUuid),
                    "a climb past the orbit line, which is what makes a refusal possible");
            scenario().arrangementFailed("the craft never reached the orbit line (" + ORBIT_LINE
                    + "); the highest it got was " + maxShipY + ", so the entry was never asked for"
                    + " and the absence of a refusal message means nothing. The hull was level"
                    + " throughout, so the tilt this class usually dies of is NOT the reason —"
                    + " climb(attempt:y/velY/up/horiz)=[" + climb.toString().trim() + "] gate=" + gate);
        }

        assertTrue("a pilot whose entry is refused (pool exhausted) must be TOLD so in his own "
                        + "chat - a silent refusal reads as a dead ship. chat="
                        + bot().reportChat(8) + " subsystem=" + exec("artest space subsystem-status")
                        + " maxShipY=" + maxShipY
                        + " delivery(attempt:recv/deliv)=[" + diag.toString().trim() + "]"
                        + " climb(attempt:y/velY)=[" + climb.toString().trim()
                        + "] gate=" + gate
                        + " physics=" + physDiag(gate, shipUuid),
                refusalLine != null);

        // Still seated: two consecutive positive samples (a lost seat can read riding=true for a
        // packet-lag moment, never twice with a wait between).
        JsonObject riding = bot().reportRidingEntity();
        boolean prev = isRiding(riding);
        boolean seatedTwice = false;
        // THE MULTIPLIER STAYS. This waits for state the SERVER restores on login to arrive at the
        // client and be applied - a round trip whose latency is the machine's, not the game's.
        int settleBudget = (int) (20 * TestTimeouts.factor());
        for (int attempt = 0; attempt < settleBudget && !seatedTwice; attempt++) {
            bot().waitTicks(5);
            riding = bot().reportRidingEntity();
            seatedTwice = prev && isRiding(riding);
            prev = isRiding(riding);
        }
        assertTrue("a REFUSED entry must leave the pilot IN HIS SEAT - the crossing may unseat "
                        + "nobody until it is granted. riding=" + riding
                        + " delivery=" + exec("artest vs seat-delivery"), seatedTwice);

        // Still in the launch world: the ship never crossed, and neither did the pilot.
        JsonObject weather = bot().reportWeather();
        int clientDim = weather.has("dim") ? weather.get("dim").getAsInt() : Integer.MIN_VALUE;
        assertTrue("after a refusal the pilot's client must still be in the LAUNCH dimension "
                + "(0), not a space cell. clientDim=" + clientDim, clientDim == 0);

        // And nothing entered space: the refusal was a refusal, not a half-crossing. Measured as a
        // DELTA, because a ship an earlier scenario legitimately settled is still in this world's
        // ledger and is not this scenario's business.
        int ledgerAfter = ledgerSize();
        assertTrue("a refused entry must ledger NOTHING into space — the ledger held "
                        + ledgerBefore + " craft before this scenario and " + ledgerAfter
                        + " after it", ledgerAfter == ledgerBefore);
    }

    // ── arrangement, shared by both scenarios ────────────────────────────────────────────────────

    /**
     * Build a with-pilot-seat craft at {@code (bx, BY, bz)}, wait for the physics mod to own it, seat
     * the bot on it, and return the ship's IDENTITY.
     *
     * <p>The identity is captured at the one moment a positional lookup is defensible — freshly
     * assembled, still at its own base. Both scenarios then fly the ship away from that base, after
     * which a nearest-ship query answers about a neighbour or about nothing, in the same shape as a
     * correct reply.</p>
     */
    private String boardAssembledCraftAt(int bx, int bz, int budget) throws Exception {
        // Stand the client well clear while the fixture is built, then beside it so it stays loaded.
        exec("tp @a " + (bx + 600) + " 120 " + (bz + 600) + " 0 0");
        bot().waitTicks(10);
        String assemble = assembleFixture(bx, BY, bz, VARIANT);
        scenario().requireArranged("a with-pilot-seat build must route to a ship: " + assemble,
                assemble.contains("\"ok\":true"));
        exec("tp @a " + (bx + 0.5) + " " + (BY + 6) + " " + (bz + 0.5) + " 0 0");
        bot().waitTicks(20);

        // THE MULTIPLIER IS IN THE BUDGET the caller passed. What it waits on is VS building the ship
        // on its OWN thread, off the game loop: that work finishes in wall-clock time, so a busy box
        // genuinely needs more game ticks to elapse before it is done.
        String shipUuid = captureShipIdAt(bx, BY, bz, budget);

        // The craft's attitude BEFORE anyone boards it. The pilot's throttle is a BODY-frame command
        // (FreeFlightPhysics.shipVelocityCommand maps it through the attitude), so a hull that is not
        // upright turns "climb" into a diagonal - and the attitude controller pins its reference to
        // wherever the ship IS whenever the pilot commands no rotation, so a tilt acquired once is
        // held. This read separates "assembly left it crooked" from "flying tilted it", which the
        // climb trace alone cannot.
        String atRest = shipInfoById(shipUuid);
        Matcher aq = Q_X.matcher(atRest), az = Q_Z.matcher(atRest);
        System.out.println("[vs-entry] attitude AT REST, pre-boarding: upY="
                + (aq.find() && az.find()
                    ? String.valueOf(1.0 - 2.0 * (Double.parseDouble(aq.group(1))
                        * Double.parseDouble(aq.group(1))
                        + Double.parseDouble(az.group(1)) * Double.parseDouble(az.group(1))))
                    : "?")
                + " :: " + atRest.replace('\n', ' '));

        // Board post-assembly (the proven path - boarding variants have their own test).
        String mountInfo = exec("artest vs seat-mount 0");
        Matcher dm = DUMMY_ID.matcher(mountInfo);
        scenario().requireArranged("seat-mount must report a dummy id: " + mountInfo, dm.find());
        String mount = exec("artest player mount-entity " + dm.group(1));
        scenario().requireArranged("bot must mount the seat dummy: " + mount,
                mount.contains("\"mounted\":true"));
        bot().waitTicks(10);

        return shipUuid;
    }

    private String assembleFixture(int baseX, int baseY, int baseZ, String variant) throws Exception {
        int cx1 = (baseX - 2) >> 4, cz1 = (baseZ - 2) >> 4;
        int cx2 = (baseX + 7) >> 4, cz2 = (baseZ + 7) >> 4;
        scenario().requireArranged("chunk warmup failed",
                exec("artest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2)
                        .contains("\"ok\":true"));
        scenario().requireArranged("pre-clear failed",
                exec("artest fill 0 " + (baseX - 2) + " " + (baseY + 1) + " " + (baseZ - 2)
                        + " " + (baseX + 7) + " " + (baseY + 10) + " " + (baseZ + 7) + " minecraft:air")
                        .contains("\"ok\":true"));
        String fixture = exec("artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " " + variant);
        scenario().requireArranged("fixture (" + variant + ") failed: " + fixture,
                fixture.contains("\"ok\":true"));
        Matcher bp = BUILDER_POS.matcher(fixture);
        scenario().requireArranged("fixture missing builderPos: " + fixture, bp.find());
        return exec("artest rocket assemble 0 " + bp.group(1) + " " + bp.group(2) + " " + bp.group(3));
    }

    // ── observation helpers ─────────────────────────────────────────────────────────────────────

    /** How many craft the space ledger holds right now. */
    private int ledgerSize() throws Exception {
        String status = exec("artest space subsystem-status");
        Matcher lm = LEDGER.matcher(status);
        scenario().requireArranged("subsystem-status must report the ledger: " + status, lm.find());
        return Integer.parseInt(lm.group(1));
    }

    /** The NAMED ship's altitude, or {@code NaN} while it is reporting none (the cut, for instance). */
    private double shipY(String shipUuid) throws Exception {
        Matcher m = POS_Y.matcher(shipInfoById(shipUuid));
        return m.find() ? Double.parseDouble(m.group(1)) : Double.NaN;
    }

    private double clientPlayerY() throws Exception {
        JsonObject state = bot().reportState();
        return state.has("playerY") ? state.get("playerY").getAsDouble() : Double.NaN;
    }

    /** The newest client chat line containing {@code needle} (case-insensitive), or null. */
    private String chatLineContaining(String needle) throws Exception {
        JsonArray lines = bot().reportChat(8).getAsJsonArray("lines");
        if (lines == null) {
            return null;
        }
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).getAsString();
            if (line.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT))) {
                return line;
            }
        }
        return null;
    }

    /** The client-side pilot-input gate discriminators (the delivery chain's CLIENT half). */
    private String clientGateStats() throws Exception {
        String cls = "zmaster587.advancedRocketry.command.test.SeatDiag";
        return "open=" + bot().readStaticField(cls, "shipGateOpenTicks").get("value").getAsString()
                + " closed=" + bot().readStaticField(cls, "shipGateClosedTicks").get("value").getAsString()
                + " sends=" + bot().readStaticField(cls, "shipInputSendCount").get("value").getAsString()
                + " clientTicks=" + bot().readStaticField(
                        "com.github.stannismod.forge.testing.client.bridge.ForgeTestClientBootstrap",
                        "CLIENT_TICKS").get("value").getAsString()
                + " wallMs=" + System.currentTimeMillis()
                + " shipData=" + exec("artest vs player-ship-data")
                + " riding=" + bot().reportRidingEntity();
    }

    /**
     * Why a ship that IS being commanded is not moving — read only on the failing path.
     *
     * <p>The delivery trace exonerates the packet chain (received == delivered, the guard and the AFC
     * both resolved), and the climb trace shows the ship pinned. What those two cannot separate is
     * what happens AFTER the input lands: the tile being reconstructed under the ship (a fresh
     * {@code afcIdentity} between reads means a command written to one instance is invisible to the
     * next), the controller never being invoked ({@code controllerTicks} flat), this ship's computer
     * never being collected as a force controller at all ({@code controllers} 0), the physics loop
     * skipping the ship on one of its three conjuncts, or a command that never became a velocity
     * ({@code pilotCmdVel}). Those need opposite fixes, which is why they are separate fields — and
     * why a red that reports none of them can only be answered by guessing.
     *
     * <p>Read HERE and not in the climb loop on purpose: {@code phys-diag} force-loads the computer's
     * chunk, so sampling it along the way would make the watcher a load source in the very climb it
     * is watching.
     */
    private String physDiag(String gateJson, String shipUuid) throws Exception {
        Matcher ax = AFC_X.matcher(gateJson);
        Matcher ay = AFC_Y.matcher(gateJson);
        Matcher az = AFC_Z.matcher(gateJson);
        if (!ax.find() || !ay.find() || !az.find()) {
            return "(the gate readout named no flight computer, so its state cannot be read)";
        }
        return exec("artest vs phys-diag 0 " + shipUuid + " "
                + ax.group(1) + " " + ay.group(1) + " " + az.group(1));
    }

    /** First capture group of {@code p} in {@code s}, or {@code fallback} — a missing field must read
     *  as "not answered" and never as a number, which is how a dead probe reads as a real zero. */
    private static String firstGroupOr(Pattern p, String s, String fallback) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : fallback;
    }

    private static boolean isRiding(JsonObject riding) {
        return riding != null && riding.has("riding") && riding.get("riding").getAsBoolean();
    }
}
