package zmaster587.advancedRocketry.test.client;

import com.google.gson.JsonObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.lwjgl.input.Keyboard;

import zmaster587.advancedRocketry.test.Events;

import zmaster587.advancedRocketry.test.Plot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The shared-client base for the Valkyrien Skies / tier-2 ship scenarios.
 *
 * <p>It is a separate base class and not two more commands in {@link AbstractSharedClientE2ETest}
 * because that class's reset is paid by every scenario in the client tier, and the two channels
 * below belong to ship scenarios only.</p>
 *
 * <h2>The three channels a ship scenario leaves behind</h2>
 *
 * <ol>
 *   <li><b>The player is still RIDING.</b> Nearly every scenario here ends seated on a pilot seat's
 *       dummy or captured by a deck. A passenger is not moved by {@code /tp}, so without this the
 *       shared reset's plot assertion fails naming coordinates — the symptom, not the cause — and a
 *       scenario that opens by mounting would mount a seat it is already sitting on.</li>
 *   <li><b>{@code vs permaload}.</b> The headless affordance that keeps a freshly assembled ship
 *       loaded with no player to hold it. Several scenarios switch it on and never switch it off,
 *       which hands the next scenario a world where ships never unload — and a scenario whose
 *       subject IS the unload (a reload, a client-load gate) would then silently measure the
 *       affordance instead of the product. It is reset to OFF, so a scenario that needs it SETS
 *       it.</li>
 *   <li><b>The flight computer's probe command channels.</b> They are per-tile and name one ship
 *       each, so they cannot bleed onto a neighbour — but they deliberately OUTRANK the pilot
 *       channel, so one left in force hands the next scenario a computer that ignores its own pilot.
 *       They replaced four {@code static volatile} channels that every computer read as a fallback,
 *       where a probe throttle was not aimed at the ship it named at all: it kept flying every other
 *       ship in the world, including the next
 *       scenario's, until something cleared it. Under one boot per test there was never a next
 *       scenario, which is why this only surfaced here.</li>
 * </ol>
 *
 * <p>Both are closed and then ASSERTED, on the same principle as the base reset: a reset nobody
 * checks is indistinguishable from no reset.</p>
 */
public abstract class AbstractSharedVsClientE2ETest extends AbstractSharedClientE2ETest {

    /**
     * Where the parking plots live for a ship class.
     *
     * <p>Ship fixtures in this tier are built on the ground along the x==z diagonal between roughly
     * 2800 and 6500, and each scenario keeps the base coordinates its green runs were taken on. The
     * plots this lane hands out are only the place the reset PARKS the player between scenarios, so
     * they are pushed well off that diagonal: a plot that contained another scenario's ship would
     * make "stay inside your plot" mean nothing.</p>
     */
    protected static final Plot.Lane SHIP_PARKING_LANE = new Plot.Lane(2000, 8000, Plot.SIZE);

    // `SHIP_QUERY_RADIUS` LIVED HERE and is gone with what it bounded (2026-09-14). It was how far
    // from its query point a positional `vs ship-info` answer could be and still be attributed to
    // this scenario — 48 blocks, chosen against the 100-block fixture spacing so a neighbour could
    // never be admitted. Its own javadoc already said the quiet part: the bound "is not, and cannot
    // be, an identity", because the distance is the full 3-D one and these scenarios climb, so
    // bounded it answers `managed:false` about the ship they built and unbounded it answers about
    // the neighbour. The positional form is now removed outright; a scenario names its ship from the
    // `ship_spawned` record its own assembly wrote, and asks by id thereafter.

    private static final Pattern SHIP_ID = Pattern.compile("\"id\":\"([^\"]*)\"");
    /** The two quaternion components an upright test needs; see {@link #upYOf}. */
    private static final Pattern Q_X = Pattern.compile("\"qx\":(-?[0-9.E\\-]+)");
    private static final Pattern Q_Z = Pattern.compile("\"qz\":(-?[0-9.E\\-]+)");

    /**
     * Wait until the ship this scenario already NAMES is USABLE — the physics loop will step it.
     *
     * <p>This is the second half of what {@link #captureShipIdAt} used to do, separated from the
     * first. A scenario that builds its own ship knows its identity — the assembly records
     * {@code ship_spawned} and {@link #awaitShipSpawned} reads the id off it — so nothing afterwards
     * has any business re-deriving that identity from a position. What a scenario legitimately still
     * has to wait for is a different fact, and it is this one.</p>
     *
     * <p><b>It waits on production's own event, not on a probe reading.</b> The interim form of this
     * helper polled {@code ship-info} for {@code managed:true}, which was measured on 2026-09-06 to
     * be a literal {@code true} in the reply builder — it meant "the lookup found a ship and built a
     * report" and nothing about readiness, so every wait on it was a wait on nothing. Real readiness
     * is the conjunction the physics loop selects by, and it is now published as
     * {@code ShipEvent.ShipLoadedEvent} and recorded off the bus as {@code ship_usable}.</p>
     *
     * <p>The {@code mark} is the one taken BEFORE the assembly — the same mark
     * {@link #awaitShipSpawned} uses. Both facts happen after it, in that order, and a mark taken
     * later can miss the edge entirely: the event fires once per load and is not a state to poll.</p>
     *
     * <p>An ARRANGEMENT failure, not a contract one: a fixture that never became a usable ship has
     * disproved nothing about ships.</p>
     */
    protected final String awaitShipUsable(Events events, long mark, String shipId, int tickBudget)
            throws Exception {
        // Filtered on the SHIP, not merely on the type: on a shared world every neighbouring
        // scenario's craft becomes usable in the same log, and a type-only wait is satisfied by the
        // first of them. The substrate id is what `ship_usable` carries as `vsShip`, and it is the
        // same value as the durable name (one ship, one identity), so either spelling of the id
        // matches the record this scenario is waiting for.
        String reply = events.awaitCarrying(mark, "ship_usable", "\"" + shipId + "\"",
                "this scenario's ship " + shipId + " must become USABLE — the physics loop steps it —"
                        + " before anything can be asked of it", tickBudget);
        scenario().record("shipUsable_" + shipId, reply);
        return reply;
    }

    /** {@link #awaitShipUsable(Events, long, String, int)} with this tier's usual budget. */
    protected final String awaitShipUsable(Events events, long mark, String shipId) throws Exception {
        return awaitShipUsable(events, mark, shipId, 200);
    }

    // `captureShipIdAt(bx, by, bz[, samples])` lived here: it polled a bounded `ship-info` at a
    // scenario's base and took whatever answered as that scenario's identity. It had NO callers by
    // the time the suite was swept, and it is not coming back — a base is a place, and a place does
    // not name a ship. The identity comes from the creation record (`awaitShipSpawned`) or from the
    // assembler's own reply (`ShipIdentity.nameFromAssembly`); the LOAD is a separate fact and
    // `awaitShipUsable` is what waits for it.

    /**
     * The ship report for {@code shipId} <b>in dimension 0</b>. A {@code managed:false} here means
     * that ship is not loaded IN DIM 0 — it does not separate "not loaded" from "loaded in another
     * world", so a scenario whose craft can leave dim 0 wants {@link #shipInfoById(int, String)} and
     * has to say which world it is asking about.
     *
     * <p>This used to promise "wherever that ship now is", which the probe does not do: the verb is
     * {@code ship-info &lt;dim&gt; id &lt;uuid&gt;} and it resolves the world from that dim before
     * asking its ship manager at all (`TestProbeCommand.java:956`). The promise was harmless while
     * every caller stayed in dim 0 and it is not a silent wrong answer even outside it — a ship in a
     * cell world reads back `managed:false`, which fails loudly — but it fails naming the wrong
     * thing, and that is a whole debugging session for the reader who believes the sentence.</p>
     */
    protected final String shipInfoById(String shipId) throws Exception {
        return shipInfoById(0, shipId);
    }

    /**
     * The ship report for {@code shipId} as {@code dim}'s own ship manager knows it — the form a
     * scenario staged in a cell world (or one that crosses into another) must use.
     */
    protected final String shipInfoById(int dim, String shipId) throws Exception {
        return exec("artest vs ship-info " + dim + " id " + shipId);
    }

    /** How long a client is given to re-establish a rider's mount after a dimension change. 80
     *  ticks: generous against an eight-fork load, short enough that a crossing which genuinely
     *  drops its rider fails here rather than waiting out a budget. */
    protected static final int CLIENT_REMOUNT_BUDGET_TICKS = 80;

    /**
     * The client PERFORMED the remount after a dimension change — as the LINK it is — and then the
     * settled mount state, read once.
     *
     * <p>A dimension change tears the client's world down and rebuilds it, and the mount to the seat
     * entity is re-established after the new dimension is known. What this waits for is the client's
     * own {@code mount} record: his {@code startRiding} is what the server's set-passengers packet
     * makes him do, so the remount is something he DOES, at an instant, and a mark taken before the
     * departure makes it unmissable.</p>
     *
     * <p><b>This was a shared bounded POLL of {@code reportRidingEntity} until 2026-09-10</b>, and
     * it is the poll a maintainer ruling was given about: <i>"Событием же? Событие нельзя
     * пропустить"</i>. Its defect was not the budget. A single read lands in the gap between the
     * tear-down and the rebuild and answers {@code riding:false} for a rider who is about to be
     * seated; the poll "fixed" that by sampling until the state came back, which spends exactly the
     * property a mark buys — a record cannot be sampled past.</p>
     *
     * <p><b>What a failure SAYS is the point, and it is unchanged.</b> "He is not riding" names a
     * symptom and leaves the reader to guess whether the test was early or the product broke. So on
     * expiry this reports the SERVER's own mount/dismount record across the same window: if it
     * seated him and the client did not follow, that is a replication lag; if it never seated him,
     * the crossing dropped him and no wait here would ever have helped. It also proves the client's
     * own mount recorder RAN, because an absence from a recorder nobody installed is not evidence.
     * Raised through {@code scenario().arrangementFailed}, so the failure is TYPED as an arrangement
     * problem rather than a contract one — the same cut the message describes, made
     * machine-readable.</p>
     *
     * @param clientMark a mark on the CLIENT log, taken BEFORE the departure. A mark belongs to one
     *                   log: the server's sequence numbers compile here and answer about the wrong
     *                   numbering.
     */
    protected final JsonObject ridingOnceTheClientHasRemounted(long clientMark, int tickBudget)
            throws Exception {
        // The SERVER's refusing mark, for the failure narrative only: it reads both honesty flags
        // and hands back the reason instead of asserting, because a recorder that is not subscribed
        // is a HARNESS gap and this method's whole job is to keep such a gap out of the verdict.
        Events.MarkOrWhyNot serverMark = events().markIfInstrumented();
        try {
            clientEvents().awaitMatching(clientMark, "mount",
                    seen -> Events.countRecords(seen, "\"ok\":true") > 0, "seating him (ok:true)",
                    "the CLIENT must perform the remount after the crossing", tickBudget);
        } catch (AssertionError never) {
            String chain = serverMark.usable()
                    ? events().since(serverMark.seq, "mount") + " | "
                            + events().since(serverMark.seq, "dismount")
                    : "NO CHAIN: the position-writer recorder was not usable at the mark ("
                            + serverMark.refusal + ")";
            Events.assertInstrumentRan(clientEvents().since(clientMark, "mount"),
                    "entity_mount_writes", "the client's own mounts must be observed at all before"
                            + " an absent one can be read as a remount the client never performed");
            scenario().arrangementFailed("the client never performed the remount within "
                    + tickBudget + " ticks of the crossing. Client says "
                    + bot().reportRidingEntity() + "; its own mount records say "
                    + clientEvents().since(clientMark, "mount")
                    + "; the SERVER's mount/dismount record across the same window says " + chain
                    + " — if it seated him and the client did not follow this is a replication lag;"
                    + " if it never seated him the crossing dropped him, and that is a PRODUCT"
                    + " defect, not a wait that was too short. (" + never.getMessage() + ")");
        }
        // Read ONCE, now that the link above says the mount happened: a settled state, not a wait.
        return bot().reportRidingEntity();
    }

    /**
     * The client MOUNTED the bot — the replication half of a boarding the server has already
     * recorded — as a contract failure rather than an arrangement one.
     *
     * <p>The difference from {@link #ridingOnceTheClientHasRemounted} is only the TYPE of the
     * failure, and it is deliberate: a boarding that the client does not follow is the contract
     * breaking, while a remount the client does not follow after a crossing may be either the
     * crossing dropping him or replication lag, which is an arrangement question. The mechanism —
     * mark before the stimulus, wait for the client's own {@code startRiding}, prove the recorder
     * ran before reading a silence — is one implementation for both.</p>
     *
     * @param clientMark a mark on the CLIENT log, taken BEFORE the press or the login
     * @param diagnosis  what to append to a failure: the server's own record, the aim, whatever
     *                   this scenario knows and the base cannot
     */
    protected final JsonObject awaitClientMount(long clientMark, String what, int tickBudget,
                                                String diagnosis) throws Exception {
        try {
            clientEvents().awaitMatching(clientMark, "mount",
                    seen -> Events.countRecords(seen, "\"ok\":true") > 0, "seating him (ok:true)",
                    what, tickBudget);
        } catch (AssertionError never) {
            Events.assertInstrumentRan(clientEvents().since(clientMark, "mount"),
                    "entity_mount_writes", "the client's own mounts must be observed at all before"
                            + " an absent one can be read as a boarding the client did not follow");
            throw new AssertionError(never.getMessage() + diagnosis);
        }
        return bot().reportRidingEntity();
    }

    /**
     * The client DISMOUNTED the bot — the replication half of a dismount the server has already
     * recorded.
     *
     * <p>The mirror of {@link #awaitClientMount}, and it exists for the same reason: a bounded poll
     * of {@code reportRidingEntity} until it answers {@code false} can only ever sample the state
     * this record announces, and its {@code false} is equally produced by a client that had not yet
     * been told anything. The dismount record carries no {@code ok} — {@code dismountRidingEntity}
     * returns nothing and the record is taken at its HEAD, while the mount is still attached, so
     * the record can say what he was thrown off.</p>
     *
     * @param clientMark a mark on the CLIENT log, taken BEFORE whatever removes him
     */
    protected final JsonObject awaitClientDismount(long clientMark, String what, int tickBudget)
            throws Exception {
        try {
            clientEvents().await(clientMark, "dismount", what, tickBudget);
        } catch (AssertionError never) {
            Events.assertInstrumentRan(clientEvents().since(clientMark, "dismount"),
                    "entity_mount_writes", "the client's own dismounts must be observed at all"
                            + " before an absent one can be read as a client that kept him seated");
            throw new AssertionError(never.getMessage() + " clientRiding="
                    + bot().reportRidingEntity() + " serverDismountRecord="
                    + events().since(0L, "dismount"));
        }
        return bot().reportRidingEntity();
    }

    /**
     * How level a hull has to be before a pilot's "climb" is a climb at all.
     *
     * <p>A pilot's throttle is a BODY-frame command — it is mapped through the ship's attitude — so
     * on a hull lying over, "up" is mostly horizontal thrust and the craft travels instead of
     * rising. Any scenario whose claim is about ALTITUDE is therefore making a claim it cannot
     * support once the hull has tipped, and its red would name the control chain, the seat binding or
     * the crossing when none of them is at fault.</p>
     *
     * <p><b>The number is measured, not chosen.</b> A craft flying in clear air reads {@code up} =
     * 1.00 for the whole window; a craft that took its tilt from ground contact reads 0.59, 0.38 or
     * below within the first samples and decays toward 0. The audit that identified the substrate's
     * collision solver as the source of the torque stated its own acceptance in these terms — level
     * throughout at {@code >= 0.95}, tipped below {@code 0.9} — so this uses the same line.</p>
     */
    protected static final double UPRIGHT_UP_Y = 0.9;

    /** The world-frame Y of a ship's OWN up, from a {@code ship-info} reply, or NaN if unreported. */
    protected static double upYOf(String shipInfoJson) {
        Matcher qx = Q_X.matcher(shipInfoJson);
        Matcher qz = Q_Z.matcher(shipInfoJson);
        if (!qx.find() || !qz.find()) {
            return Double.NaN;
        }
        double ax = Double.parseDouble(qx.group(1));
        double az = Double.parseDouble(qz.group(1));
        return 1.0 - 2.0 * (ax * ax + az * az);
    }

    /**
     * Refuse to make an ALTITUDE claim about a hull that has tipped, and say so as a PRECONDITION
     * rather than as a verdict.
     *
     * <p>This is the difference between a red that reads "the restored control chain is dead" and one
     * that reads "the craft was lying on its side, so nothing here was ever a measurement of the
     * control chain". The state it declines to measure on is a known production defect with its own
     * ledger entry — a craft that takes off from a pad is tipped by the physics substrate's collision
     * response and the attitude law then holds the tilt, and a pilot cannot right it from the
     * controls — so a scenario blocked by it must not report its own subject as broken.</p>
     *
     * <p>An UNREPORTED attitude is not treated as tipped: a probe that answered nothing is a harness
     * problem, and turning it into a precondition failure would hide it.</p>
     *
     * @param shipInfoJson a {@code ship-info} reply for the craft the claim is about
     * @param theClaim     what the caller was about to assert, for the failure line
     */
    protected final void requireUprightForAnAltitudeClaim(String shipInfoJson, String theClaim)
            throws Exception {
        double up = upYOf(shipInfoJson);
        scenario().record("upY", up);
        if (Double.isNaN(up) || up >= UPRIGHT_UP_Y) {
            return;
        }
        scenario().step(Scenario.Phase.PRECONDITION, "read the hull's attitude before " + theClaim);
        scenario().arrangementFailed("the hull is LYING OVER (up=" + up + ", level is 1.0 and this"
                + " scenario needs at least " + UPRIGHT_UP_Y + "), so \"" + theClaim + "\" cannot be"
                + " measured here at all: the pilot's throttle is a body-frame command, and on a"
                + " tipped hull it is horizontal thrust. Nothing in this red is evidence about the"
                + " subject. The craft is tipped by the physics substrate's collision response when it"
                + " leaves the ground, and the attitude law then holds whatever tilt it was given."
                + " ship=" + shipInfoJson.replace('\n', ' '));
    }

    /**
     * How far ABOVE ITS OWN PAD a craft is lifted before it is flown — a clearance, not an altitude.
     *
     * <p><b>This was an absolute Y of 150 until 2026-09-14, and that is a different quantity wearing
     * the same number.</b> 150 is also {@link zmaster587.advancedRocketry.test.FixtureSite#OPEN_AIR_Y},
     * the band a fixture stands in so that it starts on no terrain — and the two are equal only by
     * coincidence of today's values. The moment a fixture moves into that band, "lift the craft to
     * 150" is a lift to where the craft already is: the pad contact this exists to break is not
     * broken, the arrival assertion passes because the craft is at the altitude it was asked for,
     * and nothing anywhere says the arrangement did nothing. The two numbers must never be merged
     * for the same reason — they answer *above what?* differently.</p>
     *
     * <p>86 is the clearance that has been in force since this helper existed: every caller stood on
     * a pad at y=64 and was lifted to 150. It is kept rather than re-derived because that is the
     * value the family's green runs were taken on. Its lower bound is argued, though: the arrival
     * check below accepts the craft within 20 blocks of its target, so a clearance near that could
     * be satisfied by a craft that never left the pad, and this one is more than four times it.</p>
     */
    protected static final int PAD_CLEARANCE_BLOCKS = 86;

    /**
     * The highest Y a lift may leave a craft at.
     *
     * <p>Absolute on purpose, and it stays absolute while the clearance above becomes relative: two
     * independent limits meet at this number and neither one scales with the site. A vanilla world's
     * top block is y=255, so a craft teleported past it is outside the world its scenario then reads;
     * and 255 is the lowest orbit line the rocket config permits, which the entry scenarios seed, so
     * a lift that reached it would perform the crossing those scenarios exist to command.</p>
     */
    protected static final int MAX_LIFT_Y = 255;

    /**
     * Take a freshly assembled craft OFF THE PAD it was built on, straight up, and prove it came up
     * level.
     *
     * <p><b>Why every scenario that flies wants this.</b> A craft assembled on a pad is resting on
     * solid blocks, and the physics substrate resolves that contact with an impulse applied at the
     * contact point — which on an asymmetric hull is off-axis and spins it. The attitude law then
     * pins its reference to wherever the pilot's craft now IS, so the tilt is permanent, and a
     * body-frame throttle on a hull lying over is horizontal thrust. Any scenario whose subject is
     * altitude therefore spends its whole window measuring a craft that cannot climb, and its red
     * accuses the control chain, the seat binding or the crossing instead. Off the ground the same
     * craft flies dead vertical.</p>
     *
     * <p>This is arrangement, not a workaround for a test: a player launches from a pad and gets the
     * same tilt, which is a live production defect with its own ledger entry. What the lift buys is
     * the ability to measure anything ELSE while that defect stands.</p>
     *
     * <p>The move is the substrate's own rigid teleport: the pose moves, the subspace blocks stay,
     * riders are carried. It leaves the ship PARKED by VS's recipe, so physics is re-enabled
     * afterwards and the arrival is read back BY IDENTITY — a positional read at the old base would
     * answer about whatever is nearest to a place this craft has just left.</p>
     *
     * @param clearanceBlocks how far above the craft's CURRENT altitude to leave it. The method took
     *                        an absolute target Y until 2026-09-14; it was renamed with the change so
     *                        that no call site could go on passing the old quantity to a parameter
     *                        that now means something else and be compiled.
     * @return the ship's report at its new altitude
     */
    protected final String liftClearOfThePad(String shipId, int clearanceBlocks) throws Exception {
        return liftClearOfThePad(0, shipId, clearanceBlocks);
    }

    /**
     * As above, for a craft that is not in the overworld.
     *
     * <p>Added 2026-09-13 because the dimension was hard-wired to 0 in four places here — the two
     * reads and the two commands — and a caller whose craft sits in a SLOT cell got none of that
     * said to it. What it got was this method's own first refusal, *"the craft must report a
     * position before it can be lifted off its pad"*, with a reply carrying {@code managed:false}:
     * a true sentence about the wrong world, which reads as a craft that has unloaded. The dim is
     * now a parameter and the refusal names it.</p>
     */
    protected final String liftClearOfThePad(int dim, String shipId, int clearanceBlocks)
            throws Exception {
        // The substrate's load controller drops a RIGID-TELEPORTED ship's physics object even with a
        // pilot aboard, and a ship that is not loaded is not ticked: its flight computer stops, so it
        // stops climbing and stops being reported at all. Measured 2026-08-23 — a craft lifted to 147
        // flew to 242 at full commanded speed and then went silent for the remaining ten minutes of
        // its window, with the gate reporting afcResolved=false. The affordance that holds it is this
        // one, and the family reset switches it back off.
        String held = exec("artest vs permaload true");
        scenario().requireArranged("a lifted ship must be held loaded, or the substrate's load"
                + " controller drops it mid-climb and every later reading is about a ship that is no"
                + " longer being ticked: " + held, held.contains("\"ok\":true"));

        String before = shipInfoById(dim, shipId);
        double x = readDoubleOr(before, POS_X, Double.NaN);
        double fromY = readDoubleOr(before, POS_Y, Double.NaN);
        double z = readDoubleOr(before, POS_Z, Double.NaN);
        scenario().requireArranged("the craft must report a position in dim " + dim + " before it"
                + " can be lifted off its pad — a reply carrying managed:false here is as likely to"
                + " mean the craft is in a DIFFERENT world as that it has unloaded: " + before,
                !Double.isNaN(x) && !Double.isNaN(fromY) && !Double.isNaN(z));

        // WHERE THE CLEARANCE IS MEASURED FROM: the craft's own reported altitude, which is the pad
        // it was assembled on. Read here rather than passed in, so a fixture that moves — into the
        // open-air band, onto a surveyed plot, into a cell — carries its lift with it instead of
        // leaving a call site holding a number that used to be above its pad.
        int toY = (int) Math.round(fromY) + clearanceBlocks;
        scenario().requireArranged("the lift would leave the craft at y=" + toY + ", above the"
                + " ceiling of " + MAX_LIFT_Y + ": this site (y=" + fromY + ") plus this clearance ("
                + clearanceBlocks + ") leaves the band a lift is allowed to end in. A craft already"
                + " standing that high is not on a pad to be taken off one, and one teleported past"
                + " the world's top block is outside the world every later reading is taken in.",
                toY <= MAX_LIFT_Y);
        scenario().record("liftFrom", fromY);
        scenario().record("liftClearance", clearanceBlocks);

        String moved = exec("artest vs teleport-ship-by-id " + dim + " " + shipId
                + " " + x + " " + toY + " " + z);
        scenario().requireArranged("the lift off the pad must take, or the craft flies its whole"
                + " window in ground contact: " + moved, moved.contains("\"ok\":true"));
        bot().waitTicks(30); // transform adoption + rider sync settle
        String unparked = exec("artest vs unpark-by-id " + dim + " " + shipId);
        scenario().requireArranged("the rigid teleport leaves the ship PARKED by the substrate's own"
                + " recipe, and a parked ship cannot be flown: " + unparked,
                unparked.contains("\"ok\":true"));
        bot().waitTicks(10);

        String after = shipInfoById(dim, shipId);
        double y = readDoubleOr(after, POS_Y, Double.NaN);
        scenario().requireArranged("the lifted craft must still be loaded and report its new"
                + " altitude (asked BY IDENTITY, so this cannot be a neighbour): " + after,
                !Double.isNaN(y) && Math.abs(y - toY) < 20.0);
        // The whole point of the lift, ASSERTED rather than assumed: it is only worth doing if the
        // craft is level when it arrives, and a craft that was already tipped on the pad stays tipped
        // through a rigid move.
        requireUprightForAnAltitudeClaim(after, "flying the craft after lifting it off its pad");
        scenario().record("liftedTo", y);
        return after;
    }

    /**
     * Client ticks the pilot's held vertical key is given to reach the altitude a scenario asked
     * for. Generous on purpose: this is ARRANGEMENT and its expiry fails the scenario, so the budget
     * has to cover a slow client rather than a healthy one.
     */
    protected static final int HOVER_LIFT_BUDGET_TICKS = 400;

    /**
     * How much higher the key is held than the gain the caller asked for, so that what the craft is
     * left at after the thrust is cut still clears it.
     *
     * <p>Inherited from the four sites this helper replaces, which held to 3 blocks and then
     * accepted 2 — and the first measurement of it does NOT support the reason that shape implies.
     * Across the four scenarios (2026-09-11, this fixture family), the craft held to +3.0 was left
     * at +3.07, +4.14, +4.73 and +4.75 once the thrust was cut: the drift after release is UPWARD
     * every time, and the margin has not yet been observed covering a sag at all. It is kept because
     * four samples of one hull on one machine are not enough to delete a guard, not because a sag
     * was seen. What the craft is actually left at is printed on every run — green included, where
     * the scenario journal is silent — so whoever tightens this has evidence rather than arithmetic.</p>
     */
    private static final double HOVER_SETTLE_MARGIN_BLOCKS = 1.0;

    /**
     * The gain that makes a hover a hover: far enough off the ground that nothing a scenario then
     * observes is a body or a hull still in contact with the terrain below it. The test's own
     * requirement, not production's — it is passed explicitly at every call site.
     */
    protected static final double CLEAR_HOVER_GAIN_BLOCKS = 2.0;

    /**
     * Fly the craft off the ground with the PILOT'S OWN vertical key and leave it hovering there.
     *
     * <p><b>Why flown and not commanded.</b> A craft held at an attitude by a probe is not the
     * configuration the reported defects live on: a hovering ship is under station-keeping, which
     * never brings a hull fully to rest, and the residual is the axis. Any scenario whose subject is
     * what happens ON a hovering ship — a still crew member, a walk across a deck, a pilot standing
     * up mid-hover — wants this arrangement and not a rigid teleport.</p>
     *
     * <p><b>Why this is a measurement and not a chain</b>, argued once here so no caller argues it
     * again. Two questions are being asked and only one of them has a link:</p>
     * <ul>
     *   <li><em>Did the held key reach the flight computer?</em> That is something production DOES,
     *       it is recorded where production delivers it, and it is awaited below
     *       ({@code pilot_input_delivered}) BEFORE any altitude is believed. Without that link a
     *       hull that could not climb and a key that never arrived produce the same red.</li>
     *   <li><em>Did the hull climb?</em> That is an ALTITUDE. Nothing DECIDES it, so there is no
     *       record to wait for and none worth adding — a per-tick hull position is a sample, not a
     *       fact about the game. It is measured, inside a window.</li>
     * </ul>
     *
     * <p>The window early-exits because the STIMULUS GOES ON ACTING. The key is held while it runs,
     * so a fixed budget does not bound an observation — it decides how far the craft flies, and far
     * enough leaves the loaded region, at which point the craft stops being ticked and stops
     * reporting a position at all. The exit is what cuts the thrust.</p>
     *
     * @param shipId     the craft's identity; every read is BY IDENTITY, so a neighbour sharing the
     *                   airspace can never answer for it
     * @param gainBlocks how far above its starting altitude the scenario needs the craft, in blocks,
     *                   measured after the thrust is cut
     * @return the craft's report at the altitude it is left hovering at
     */
    protected final String hoverOnPilotThrust(String shipId, double gainBlocks) throws Exception {
        String before = shipInfoById(shipId);
        final double y0 = readDoubleOr(before, POS_Y, Double.NaN);
        scenario().requireArranged("the craft must report an altitude before a climb from it can be"
                + " measured: " + before, !Double.isNaN(y0));

        Events events = events();
        long liftMark = events.markInstrumented();
        final double holdTo = gainBlocks + HOVER_SETTLE_MARGIN_BLOCKS;
        ClientPoll.Result<Double> lift;
        bot().holdKey(Keyboard.KEY_R);
        try {
            lift = ClientPoll.until(bot()::waitTicks,
                    () -> readDoubleOr(shipInfoById(shipId), POS_Y, y0),
                    y -> y - y0 >= holdTo, 2, HOVER_LIFT_BUDGET_TICKS / 2);
        } finally {
            bot().releaseKey(Keyboard.KEY_R);
        }

        // The LINK before the number. Past this line the input demonstrably reached the computer, so
        // an altitude that did not move is about the flight and nothing else.
        events.await(liftMark, "pilot_input_delivered", "the pilot's held vertical key must reach the"
                + " craft's flight computer — until this link is on the record, a craft that did not"
                + " climb says nothing about flight", HOVER_LIFT_BUDGET_TICKS);

        // The state the scenario will actually use: read AFTER the thrust is cut, not the sample the
        // window exited on. A craft still under its pilot's key is not the hover the callers arrange.
        String after = shipInfoById(shipId);
        double y = readDoubleOr(after, POS_Y, Double.NaN);
        scenario().record("hoverGain", y - y0);
        // The journal prints on failure only, and the number worth having is the one a GREEN run
        // leaves behind: how much of the margin above survives the thrust being cut.
        System.out.println("[hover] ship=" + shipId + " asked=" + gainBlocks + " heldTo=" + holdTo
                + " left=" + (y - y0) + " " + lift);
        scenario().requireArranged("the pilot must be able to fly his own craft " + gainBlocks
                + " blocks off the ground and leave it hovering there; it is at " + (y - y0)
                + " with the thrust cut (" + lift + "), so the hover every later reading is about"
                + " was never established: " + after,
                !Double.isNaN(y) && y - y0 >= gainBlocks);
        return after;
    }

    private static final Pattern PLAYER_NAME = Pattern.compile("\"player\":\"([^\"]+)\"");

    /**
     * The bot's own player name, as the server knows it.
     *
     * <p>Every record this tier waits on carries {@code who}, and the log is shared by every body
     * that crosses the same seam — so the name is what makes a wait about THIS player rather than
     * about whoever moved next.</p>
     */
    protected final String botName() throws Exception {
        String health = exec("artest player health");
        Matcher name = PLAYER_NAME.matcher(health);
        scenario().requireArranged("player health must echo the player name, or no wait on this tier"
                + " can be filtered to this body: " + health, name.find());
        return name.group(1);
    }

    private static final Pattern POS_X = Pattern.compile("\"posX\":(-?[0-9.E\\-]+)");
    private static final Pattern POS_Y = Pattern.compile("\"posY\":(-?[0-9.E\\-]+)");
    private static final Pattern POS_Z = Pattern.compile("\"posZ\":(-?[0-9.E\\-]+)");

    private static double readDoubleOr(String json, Pattern p, double fallback) {
        Matcher m = p.matcher(json);
        return m.find() ? Double.parseDouble(m.group(1)) : fallback;
    }

    /** The {@code "id"} field of a {@code ship-info} reply, or null when it carries none. */
    protected static String readShipId(String shipInfoJson) {
        Matcher m = SHIP_ID.matcher(shipInfoJson);
        return m.find() && !m.group(1).isEmpty() ? m.group(1) : null;
    }

    @Override
    protected Plot.Lane lane() {
        return SHIP_PARKING_LANE;
    }

    @Override
    protected void resetFamilyStateBeforeTeleport() throws Exception {
        // WAS he seated, and the CLIENT's mark, both taken BEFORE the dismount probe. The reset's
        // wait below is conditional on this answer for a reason the poll it replaces did not have
        // to think about: a dismount that has nothing to dismount publishes NOTHING, so a scenario
        // arriving un-seated (which is most of them) has no record to wait for, and an
        // unconditional wait would burn its budget on every single scenario in the family.
        JsonObject wasRiding = bot().reportRidingEntity();
        long clientMark = clientEvents().mark();
        exec("artest player dismount");
        exec("artest vs permaload false");
        // Release every per-tile PROBE command channel on the server. They name one ship each and
        // cannot bleed onto a neighbour, but they deliberately OUTRANK the pilot channel, so a
        // scenario that left one in force hands the next scenario a computer that ignores its own
        // pilot. The clear walks the loaded computers of every loaded world.
        //
        // This verb was born for a worse problem, now gone: a JVM-wide static flight input every
        // computer read as its fallback. Measured 2026-08-07 — a pilot-key scenario whose ship
        // climbed 32.7 blocks where the same body run alone climbs ~2, because an earlier scenario's
        // throttle was still held on a channel that belonged to nobody.
        //
        // Asserted, not trusted: a clear nobody checks is indistinguishable from no clear.
        String afcCleared = exec("artest vs afc-clear");
        assertTrue("the flight computer's bring-up channels must be cleared between"
                + " scenarios, or a later scenario's ship flies under an earlier one's throttle;"
                + " probe replied " + afcCleared, afcCleared.contains("\"ok\":true"));

        // Asserted on the CLIENT's own view. Where a dismount was actually owed — he arrived seated
        // from the previous scenario — the client PERFORMING it is the link, and it is waited for
        // as one; the poll that stood here sampled the state that link announces.
        if (isRiding(wasRiding)) {
            awaitClientDismount(clientMark, "a scenario that arrived SEATED from its predecessor"
                    + " must have its client dismount him before the next one measures its own"
                    + " mount step (client said " + wasRiding + " on entry)",
                    CLIENT_REMOUNT_BUDGET_TICKS);
        }
        JsonObject riding = bot().reportRidingEntity();
        assertFalse("a ship scenario must start un-seated as the CLIENT renders it, or its own"
                + " mount step measures the previous scenario's seat — and /tp does not move a"
                + " passenger, so the plot assertion that follows would fail for the wrong reason."
                + " client reports " + riding, isRiding(riding));
        scenario().record("resetRiding", riding);
    }

    private static boolean isRiding(JsonObject riding) {
        return riding != null && riding.has("riding") && riding.get("riding").getAsBoolean();
    }

    // ---- the hyperspace jump as a CHAIN of events, and the clock that drives it ----

    /**
     * The seven links a piloted hyperspace jump is, in the order production commits them: the crew is
     * picked up, the hull is cut out of its cell into the lane, the departure is committed, the crew is
     * seated on the parked hull for the flight, the hull is cut out of the lane into its destination,
     * the crew is put back on it, and only then is the arrival committed.
     *
     * <p>Every link is recorded by a test-only mixin at the seam where production performs it
     * ({@code MixinShipTransitManagerEvents}, {@code MixinVSShipCrosserEvents}). A red on this chain
     * names the link that did not happen and prints everything that did — where the loop it replaces
     * reported {@code expected:<13> but was:<3>} for a flight that ended with nobody aboard, a crew
     * that never boarded the parked hull, and a client that was merely slow, all alike.</p>
     */
    protected static final String[] PILOTED_JUMP_CHAIN = zmaster587.advancedRocketry.test.Chains.PILOTED_JUMP;

    /**
     * The deck-capture verdict for the player, read ONCE and proved to be about {@code shipId}.
     *
     * <p>Two defects it exists to remove, both measured 2026-09-06 across this family:</p>
     *
     * <ul>
     *   <li><b>Two samples, one verdict.</b> The idiom it replaces calls {@code exec("artest vs
     *       deck-capture")} twice — once to build the failure message, once for the assertion — so
     *       the reply a reader diagnoses from is not the reply that decided the test. Under load
     *       they disagree, and the disagreement looks like the subject misbehaving.</li>
     *   <li><b>A verdict with no subject.</b> {@code alreadyTracked} / {@code verdict} /
     *       {@code hullStand} say a ship holds this body, never WHICH. On a world this class shares
     *       with its siblings a body resolved against a neighbour's hull answers identically, and
     *       the id was in the reply the whole time ({@code anchorShipId}, 0 readers across 103 call
     *       sites).</li>
     * </ul>
     *
     * @param shipId this scenario's own ship, from {@link #awaitShipSpawned}
     * @param what   the scenario's sentence for what the capture means, used in the failure
     * @return the single reply, for the caller's own flag assertions and failure messages
     */
    protected final String deckCaptureOfThisShip(String shipId, String what) throws Exception {
        String reply = exec("artest vs deck-capture");
        zmaster587.advancedRocketry.test.ShipIdentity.assertCaptureAnchoredOn(reply, shipId, what);
        return reply;
    }

    /**
     * Wait for THIS scenario's assembly to become a ship in the physics mod's registry, and return
     * its physics id — read off the {@code ship_spawned} record rather than off a nearest-ship lookup
     * at the build site a tick later. The mark is taken before the assembly is queued, so the record
     * is this scenario's own ship and never a neighbour's.
     *
     * <p><b>Exactly ONE record, and the caller cannot check that afterwards — so it is checked
     * here.</b> {@link Events#await} returns the moment the count goes above zero and
     * {@link Events#lastField} then takes the LAST record in that reply, so a second assembly landing
     * in the same window would silently re-point every question the scenario asks from here on, and
     * the id it handed back would look exactly as legitimate as the right one. The window is the
     * caller's own mark, so two records in it means the mark was taken too early or a neighbour
     * assembled inside it; either way nothing here can say which ship is this scenario's, which is
     * why a second record raises an ARRANGEMENT failure naming the count instead of picking one.
     * ({@code VSGroundFlightGroupE2ETest} takes a separate mark per assembly — that is the shape a
     * class with two builds wants, and it is what keeps this count at one.)</p>
     */
    protected final String awaitShipSpawned(Events events, long mark, String what) throws Exception {
        String reply = events.await(mark, "ship_spawned", what, 200);
        int spawned = Events.countRecords(reply, "\"vsShip\":");
        scenario().requireArranged("exactly ONE ship may be spawned in this scenario's window, or"
                + " nothing here can say which is its own — " + spawned + " were: " + reply,
                spawned == 1);
        String vsShip = Events.lastField(reply, "vsShip");
        scenario().requireArranged("a ship_spawned record must name the ship: " + reply, vsShip != null);
        return vsShip;
    }

    /**
     * How long one link of the chain may take, in the {@link Events} clock's ticks. The probe's
     * transit manager advances ONLY when the probe ticks it, so every poll of the clock below also
     * drives ten transit ticks: this budget is 120 polls, i.e. 1 200 transit ticks and 600 client
     * ticks per link, against a flight priced at ~170 transit ticks by {@code HYPERSPACE_JUMP_SPEED}.
     * It is a deadline for a discrete event, not a guess at how long a value takes to settle.
     */
    protected static final int JUMP_LINK_BUDGET_TICKS = 600;

    /**
     * The event log, read against a caller-supplied probe.
     *
     * <p>Its step used to PUMP — {@code transit-tick 10} between every pair of reads — because the
     * transit manager these scenarios drove was the probe's own and nothing ticked it. That stopped
     * being true on 2026-09-08: the fixture runs on the server's subsystem now, so a jump advances
     * on the server tick like everything else, and a wait that drove it was a wait moving its own
     * subject. What is left is the reason this exists at all — it takes the PROBE, which
     * {@code events()} hard-codes, and one scenario reads through an envelope-aware one.</p>
     */
    protected final Events transitEvents(Events.Probe probe) {
        return new Events(probe, bot()::waitTicks);
    }

    /**
     * {@link Events#assertChain} for a chain that is this scenario's ARRANGEMENT rather than its
     * subject: the same chain, the same message, raised as an arrangement failure so the JUnit XML
     * separates "the jump the test needed did not happen" from "the contract under test broke".
     */
    protected final void requireChain(Events events, long mark, String what, String... types)
            throws Exception {
        try {
            events.assertChain(mark, what, JUMP_LINK_BUDGET_TICKS, types);
        } catch (AssertionError e) {
            scenario().arrangementFailed(e.getMessage());
        }
    }

    /**
     * The slot dimension the arrived ship sits in, read from the transit probe once the chain has
     * settled — the field the old arrival loops read from their last tick.
     */
    protected static int arrivedTargetDim(Events.Probe probe) throws Exception {
        // READ, not a tick: this runs once the chain says the jump has settled, so advancing
        // anything here would drive a mechanism whose completion has already been asserted.
        String tick = probe.exec("artest space transit-status");
        Matcher inTransit = Pattern.compile("\"inTransit\":(-?\\d+)").matcher(tick);
        assertTrue("the transit probe must report inTransit: " + tick, inTransit.find());
        assertEquals("the chain said the transit settled, so the probe must agree it is over: " + tick,
                0, Integer.parseInt(inTransit.group(1)));
        Matcher target = Pattern.compile("\"targetDim\":(-?\\d+)").matcher(tick);
        assertTrue("the transit probe must report targetDim: " + tick, target.find());
        int targetDim = Integer.parseInt(target.group(1));
        assertTrue("a settled transit must name the target cell's slot dimension: " + tick, targetDim >= 0);
        return targetDim;
    }

    /** The client-side deck-camera window: poses, the eye, and the two per-client counters. */
    private static final String DECK_CAMERA_WINDOW =
            "zmaster587.advancedRocketry.test.trace.DeckCameraState";

    /**
     * One field of the deck camera AS IT STANDS, taken as a record.
     *
     * <p>A peek writes the window's numbers and this reads the one that just landed, so every
     * camera reading a scenario makes is attributable to a moment rather than to whenever the
     * socket happened to ask. Replaces reflective reads of a static field: those could not say when
     * the value was true, and for the two counters they could not say whose ticks they counted.</p>
     */
    protected final double deckCamera(String field) throws Exception {
        return Events.number(deckCameraRecord(field), field);
    }

    /** The same reading for a non-numeric field ({@code active} comes back as "true"/"false"). */
    protected final String deckCameraText(String field) throws Exception {
        return Events.text(deckCameraRecord(field), field);
    }

    /** Zero this scenario's deck-camera counters. Without it, {@code cameraHookCalls} and
     *  {@code posLookApplies} are the whole client's, and a threshold on either is satisfied by
     *  whatever ran before — the shared-harness trap this window exists to close. */
    protected final void openDeckCameraWindow() throws Exception {
        bot().invokeStaticInt(DECK_CAMERA_WINDOW, "open");
    }

    private String deckCameraRecord(String field) throws Exception {
        long mark = clientEvents().mark();
        bot().invokeStaticInt(DECK_CAMERA_WINDOW, "peek");
        String rec = Events.lastRecord(clientEvents().since(mark, "deck_camera"));
        assertNotNull("no deck_camera record after a peek — the client did not answer, so " + field
                + " has no reading", rec);
        return rec;
    }

}
