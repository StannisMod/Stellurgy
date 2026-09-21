package zmaster587.advancedRocketry.test.client;

import java.util.Locale;

import com.google.gson.JsonObject;

import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;
import org.lwjgl.input.Keyboard;

import zmaster587.advancedRocketry.test.SubsystemStatus;
import zmaster587.advancedRocketry.test.SeatMount;
import zmaster587.advancedRocketry.test.Reply;
import zmaster587.advancedRocketry.test.Events;
import zmaster587.advancedRocketry.test.FixtureSite;
import zmaster587.advancedRocketry.test.RocketFixture;

import static org.junit.Assert.assertTrue;

/**
 * A player who SITS DOWN IN THE PILOT SEAT BEFORE HIS SHIP IS ASSEMBLED must still be flying that
 * ship the moment assembly finishes - without standing up and sitting down again.
 *
 * <p>This is how a human actually builds and boards a tier-2 craft: he walks onto the launchpad,
 * right-clicks the pilot seat while the craft is still a pile of loose blocks, and only then runs
 * the assembly. The seat he clicked is therefore the seat at its ORIGINAL world position. Assembly
 * moves the craft's blocks into the ship's own coordinate space, and the pilot's control binding has
 * to survive that move.</p>
 *
 * <p><b>Two methods, because this scenario changes TWO things at once.</b> Measured against the
 * working post-assembly-boarding flight test, the human's route differs in WHEN he boards (before vs
 * after assembly) AND in HOW he boards (a real client right-click on the seat block vs a probe that
 * spawns the seat dummy server-side and mounts it). A single red run over both variables could not
 * say which one drove it. So the scenario is run twice over a {boarding mechanism} x {boarding
 * moment} matrix:</p>
 *
 * <pre>
 *                     | pre-assembly            | post-assembly
 *   ------------------+-------------------------+---------------------------------
 *   probe mount       | {@link #aPilotBoardedByProbeBeforeAssemblyCanFlyTheShip()}
 *                     |                         | (the existing passing flight test)
 *   real right-click  | {@link #aPilotWhoRightClickedTheSeatBeforeAssemblyCanFlyTheShip()}
 *                     |                         | NOT REACHABLE
 * </pre>
 *
 * <p>The fourth cell - a real right-click on the seat of an ALREADY ASSEMBLED ship - cannot be
 * written with this harness, and that hole is named rather than hidden: the client's block
 * interaction takes WORLD coordinates, while an assembled ship's blocks live in the ship's own
 * subspace at coordinates the client never renders at. There is no world position to click.</p>
 *
 * <p><b>What the two methods jointly discriminate.</b> If BOTH fail, the driver is WHEN: the pilot's
 * control binding does not survive assembly, regardless of how it was established. If ONLY the
 * right-click method fails, the driver is HOW: the real block-activation path never confers ship
 * control in the first place, and the pre-assembly moment is innocent.</p>
 *
 * <p><b>The boarding must be confirmed, not assumed.</b> A seat's block-activation handler reports
 * success to the client unconditionally while doing its real work server-side only, so the client's
 * own return value cannot distinguish "the player sat down" from "the server silently dropped the
 * right-click" (reach limit, an unconfirmed teleport, a held item preempting the click). The test
 * therefore confirms the boarding independently: on the SERVER's own ordered log, where the human's
 * route must show a click reaching the server and then a mount ({@code right_click_block} &rarr;
 * {@code mount}) while the probe's shows the mount alone - so the two failure families the class
 * exists to separate arrive already separated - and then from what the CLIENT reports it is riding,
 * checking WHAT it is riding and WHERE that thing is, so "he took the pilot seat" cannot be
 * satisfied by riding something else. A failure there is reported as a BROKEN ARRANGEMENT, not as a
 * broken contract.</p>
 *
 * <p><b>The climb is measured against a no-key control leg.</b> A freshly assembled physics object
 * that overlaps solid geometry can be resolved by displacing it UPWARD, and this craft is assembled
 * directly above a solid launchpad. Without a control, that settle would be credited to the pilot's
 * key and turn a still-broken control path green - the worst possible outcome, because a green here
 * would be read as "the bug is not real". So the ship is first settled to a stable altitude, then
 * its free drift is measured over a window of the SAME length as the measurement window with NO key
 * held, and only then is the key-held climb measured. Both numbers are reported side by side.</p>
 *
 * <p><b>History.</b> This file began as the missing repro for a craft that a hand-boarded pilot
 * could not fly: a pilot seated before assembly kept riding a mount bound to the seat's build-time
 * world position, which assembly vacates, so nothing in his control chain resolved and the piloting
 * client never sent a single input packet. It now pins the fixed contract: the assembler queues
 * every seated pilot's binding for re-expression onto the relocated seat, and both cells must stay
 * green.</p>
 *
 * <p>On the shared VS client base. This file ran its own server + client pair per method until
 * 2026-08-23, justified by a config that "has to be written into the game directory BEFORE the
 * server boots" - it writes none, and its root was a fresh empty temp dir handed to a harness that
 * makes one of those itself. The second boot bought nothing, and off the shared base an arrangement
 * failure here could not even be TYPED as one: the two are the same move.</p>
 *
 * <p></p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class VSPreAssemblyBoardingPilotControlE2ETest extends AbstractSharedVsClientE2ETest {

    @Override
    protected String subsystem() {
        return "vs-pre-assembly-boarding";
    }

    private static final String POS_Y = "posY";

    /**
     * This scenario's ship, by identity — read off the assembly's own {@code ship_spawned} record
     * (the base's {@code awaitShipSpawned}), never re-derived from a position.
     */
    private String shipUuid;
    private static final String DUMMY_ID = "dummyId";
    private static final String SEAT_X = "seatX";
    private static final String SEAT_Y = "seatY";
    private static final String SEAT_Z = "seatZ";

    private static final String VARIANT = "with-pilot-seat";

    /**
     * THIS CLASS'S SITE IS PER-SCENARIO, and it was one shared constant until 2026-09-14.
     *
     * <p>Both scenarios built at (2800, 2800) on one shared client, and therefore in one world.
     * Nothing said so: the pit pre-clear each of them ran silently levelled whatever the other had
     * left standing, so the overlap could not be seen from inside either test. The first link's
     * arithmetic found it the moment the site moved into the band and the clear became an
     * ASSERTION — <i>18 of the 1600 blocks in the working area were NOT air</i>, which is the
     * previous scenario's structure tower and rocket builder, left behind when its craft was cut
     * into a ship.</p>
     *
     * <p>The fix is the suite's own rule and not a teardown: one plot per scenario, never recycled.
     * A teardown has to be remembered and can fail; a plot nobody else looks at cannot.</p>
     */
    private FixtureSite site;

    /** This scenario's base, from its own site. */
    private int bx, by, bz;

    /**
     * Where the fixture puts the pilot seat: the craft's centre column is (baseX+3, baseZ+3) and the
     * seat caps it four blocks above the craft's base row (which itself sits one above the pad).
     * Measured against the world before the click, never trusted blind.
     */
    private int seatX, seatY, seatZ;

    /**
     * A clear spot on the launchpad, two blocks north of the craft's column and well inside the
     * pad's edge (a player teleported here with leftover falling momentum must not be able to
     * slide off before it is damped). The craft occupies only the z=baseZ+3 slice, so this column
     * is empty from the pad up.
     */
    private double standX, standY, standZ;

    /** Bind this scenario to its own plot; every coordinate below is derived from it. */
    private void useSite(FixtureSite chosen) {
        this.site = chosen;
        this.bx = chosen.x;
        this.by = chosen.y;
        this.bz = chosen.z;
        this.seatX = bx + 3;
        this.seatY = by + 5;
        this.seatZ = bz + 3;
        this.standX = bx + 3.5;
        this.standY = by + 1;
        this.standZ = bz + 1.5;
    }

    /**
     * The server refuses a block interaction beyond (reach + 3) blocks, so wherever the client
     * actually ends up standing has to be inside that radius of the seat's centre.
     */
    private static final double MAX_INTERACT_DIST_SQ = 64.0;

    /**
     * How far the ridden mount may sit from the seat block's centre and still count as "he is in
     * THAT seat". The seat's mount is spawned at the block centre offset by a fifth of a block
     * vertically, so two blocks of slack is generous while still excluding a neighbouring block.
     */
    private static final double MOUNT_AT_SEAT_DIST_SQ = 4.0;

    /** The ship must gain at least this much altitude while the key is held, or it is not flying. */
    private static final double MIN_CLIMB = 1.0;

    // ---- Measurement windows -------------------------------------------------------------------
    // TICKS_PER_SAMPLE/MEASURE_SAMPLES: the ship gets 200 ticks (10 s) to climb one block, sampled
    // every 5 ticks. The no-key control leg runs for exactly the same 200 ticks so the two numbers
    // are directly comparable; the key-held leg may exit EARLY once it has climbed, which only makes
    // the comparison stronger (less time to accumulate the same drift).
    // SETTLE_*: before either leg the ship must hold one altitude within SETTLE_EPS across 100 ticks
    // - half the measurement window, the same order of magnitude, and long enough that a post-
    // assembly upward resolve has visibly ended rather than merely paused. SETTLE_EPS is 5 cm: far
    // below MIN_CLIMB, far above the double-precision jitter of a physics object at rest. The settle
    // budget is 1200 ticks (60 s), generous because a settle that never converges is itself the
    // finding: the fixture cannot sit still and no climb measured on it would mean anything.
    // MAX_CONTROL_DRIFT is a quarter of MIN_CLIMB: any free drift at or above that makes the
    // key-held climb unattributable.
    private static final int TICKS_PER_SAMPLE = 5;
    private static final int MEASURE_SAMPLES = 40;
    private static final int SETTLE_STABLE_SAMPLES = 20;
    /**
     * How long a dismount may stand UNANSWERED on the server's log before the pilot counts as
     * thrown out. The rebind's dismount and its mount are two statements inside ONE method call, so
     * what is being waited out here is only the gap between two probe reads, not any real latency -
     * 60 ticks is three seconds of slack on a microsecond-wide event, and the loop exits on its
     * first pass whenever no dismount is standing at all.
     */
    private static final int SEATED_CONFIRM_SAMPLES = 12;

    /**
     * How long the CLIENT may take to perform a mount the server has already recorded, in ticks.
     *
     * <p>A deadline for a discrete record, not a settle: the client mounts when it is told who is
     * riding what, so what is waited out is one server-to-client round trip. The number is the
     * budget the 20-iteration poll it replaces spent ({@code 20 * TICKS_PER_SAMPLE}), kept so the
     * conversion changes the FORM of the wait and not how long it is willing to wait.</p>
     */
    private static final int CLIENT_MOUNT_BUDGET_TICKS = 20 * TICKS_PER_SAMPLE;

    /**
     * How long the CLIENT may take to be told where it now stands, and to be sent the blocks it is
     * standing among, in ticks.
     *
     * <p>Two discrete records, not a settle: a teleport arrives as a pos-look packet and a chunk
     * arrives as chunk data, and the client can neither be clicked through nor asked what it sees
     * until they have. The number is the budget the 20-iteration poll it replaces spent
     * ({@code 20 * TICKS_PER_SAMPLE}), kept so the conversion changes the FORM of the wait and not
     * how long it is willing to wait.</p>
     */
    private static final int CLIENT_TERRAIN_BUDGET_TICKS = 20 * TICKS_PER_SAMPLE;
    private static final int SETTLE_MAX_SAMPLES = 240;
    private static final double SETTLE_EPS = 0.05;
    private static final double MAX_CONTROL_DRIFT = MIN_CLIMB / 4.0;

    /** How the pilot gets into the seat. The variable the second test method isolates. */
    private enum Boarding {
        /** The probe path the passing post-assembly flight test uses, performed BEFORE assembly. */
        PROBE,
        /** The human's path: a real client right-click on the loose seat block. */
        RIGHT_CLICK
    }

    /** Where the fixture reported its rocket builder - the block the assembly step is driven from. */
    private int builderX, builderY, builderZ;

    /**
     * The human's own route: right-click the seat of a craft that is still loose blocks, then
     * assemble, then fly. Changes BOTH the boarding moment and the boarding mechanism relative to
     * the passing post-assembly flight test - which is why the probe variant below exists.
     */
    @Test
    public void aPilotWhoRightClickedTheSeatBeforeAssemblyCanFlyTheShip() throws Exception {
        runPreAssemblyBoardingScenario(site(), Boarding.RIGHT_CLICK);
    }

    /**
     * The SAME boarding mechanism the passing post-assembly flight test uses, moved to BEFORE
     * assembly. This isolates the boarding MOMENT: everything else - fixture, coordinates, seeded
     * config, control key, thresholds, polling shape - is the passing test's.
     */
    @Test
    public void aPilotBoardedByProbeBeforeAssemblyCanFlyTheShip() throws Exception {
        // ITS OWN PLOT, and it is the allocator that says so rather than a second coordinate typed
        // here. The two scenarios shared one site until 2026-09-14 and each silently levelled the
        // other's leavings with its pre-clear; splitting them by hand fixed this pair and nothing
        // else, which is why asking for the plot replaced choosing one.
        runPreAssemblyBoardingScenario(site(), Boarding.PROBE);
    }

    /**
     * The shared scenario, so the two variants cannot drift apart: build loose, board (by
     * {@code how}), assemble, settle, measure a no-key control leg, then measure the key-held climb.
     */
    private void runPreAssemblyBoardingScenario(FixtureSite plot, Boarding how) throws Exception {
        useSite(plot);

        // The subsystem must actually be up, or the run silently degrades into a different
        // configuration than the one a player is in and its result would mean nothing.
        SubsystemStatus status = SubsystemStatus.read(this::exec);
        scenario().requireArranged("the production space subsystem must be REGISTERED - the seeded "
                        + "config is what opts it in: " + status.raw(),
                status.registered);

        long awayMark = clientEvents().mark();
        exec("tp @a " + (bx + 600) + " 120 " + (bz + 600) + " 0 0");
        awaitClientPlacedNear(awayMark, bx + 600, bz + 600,
                "the assembly below must run with no observer near it, and the observer is a client");

        // Build the craft as LOOSE BLOCKS only. Assembly is deliberately deferred until after the
        // player has taken his seat - that ordering is the entire experiment.
        buildLooseFixture(site, VARIANT);

        // Stand the client on the pad, next to the craft. The wait is not cosmetic: the server
        // ignores block interactions while a teleport it issued is still unconfirmed by the client,
        // and a right-click sent too early would be dropped without a trace. That confirmation is a
        // RECORD — the pos-look packet the teleport arrives as — so it is awaited rather than
        // counted out in ticks, and the mark is taken before the command that must produce it.
        long standMark = clientEvents().mark();
        exec("tp @a " + standX + " " + standY + " " + standZ + " 0 0");
        clientEvents().await(standMark, "client_pos_look_applied",
                "the stand-beside-the-craft teleport must be APPLIED on the client before anything"
                        + " is asked of it or clicked through it", CLIENT_TERRAIN_BUDGET_TICKS);

        // MEASURE the seat before touching it - the fixture's geometry is verified, never assumed.
        // An unloaded chunk reports an EMPTY block name, which is otherwise indistinguishable from
        // "the wrong block is there" - so the chunk reaching this client is established FIRST, and
        // it too is a record: `chunk_data_applied` is the instant the client can see a chunk's
        // blocks, which no Forge event reports (the load event fires on an empty chunk).
        //
        // Asked of the CLIENT's own state first, because the client is the side that must react.
        // This class runs the scenario twice against one shared client, and on the second pass the
        // seat's chunk may already be applied — nobody owes a record then, and an unconditional wait
        // would spend its whole budget learning that.
        JsonObject seatBlock = bot().blockState(seatX, seatY, seatZ);
        if (!(seatBlock.has("loaded") && seatBlock.get("loaded").getAsBoolean())) {
            // BOTH coordinates, on ONE record: `cx` alone is satisfied by any chunk in that column,
            // and two separate field waits would be satisfied by two different records.
            clientEvents().awaitMatching(standMark, "chunk_data_applied",
                    reply -> Events.anyRecordHasAll(reply,
                            "cx", String.valueOf(seatX >> 4), "cz", String.valueOf(seatZ >> 4)),
                    "carrying cx = " + (seatX >> 4) + " and cz = " + (seatZ >> 4),
                    "the seat's chunk must reach the client before its block can be measured",
                    CLIENT_TERRAIN_BUDGET_TICKS);
            seatBlock = bot().blockState(seatX, seatY, seatZ);
        }
        boolean seatChunkLoaded = seatBlock.has("loaded") && seatBlock.get("loaded").getAsBoolean();
        String seatName = seatChunkLoaded ? seatBlock.get("block").getAsString() : "";
        scenario().requireArranged("the seat's chunk must be LOADED on the client before its block can "
                        + "be measured - an unloaded chunk reports an empty block name, not a wrong "
                        + "one. measured=" + seatBlock,
                seatChunkLoaded);
        scenario().requireArranged("the block the test is about to board must really be the pilot seat "
                        + "as the CLIENT sees it, at (" + seatX + "," + seatY + "," + seatZ
                        + "). measured=" + seatBlock,
                seatName.toLowerCase(Locale.ROOT).contains("pilotseat"));

        // The mark goes BEFORE the stimulus, so the mount recorded after it can only be this
        // boarding's. markInstrumented, because the mount is a test-mixin record and a mixin that
        // never wove answers with exactly the empty log a boarding that never happened does.
        Events events = events();
        long boardMark = events.markInstrumented();
        // The CLIENT's own mark beside it: his client performs the mount when it is told who is
        // riding what, and that is the half read below.
        long boardOnClient = clientEvents().mark();

        String boardingEvidence = (how == Boarding.RIGHT_CLICK)
                ? boardByRightClick()
                : boardByProbe();

        // The links each route actually commits — which is the very variable this class exists to
        // separate. The human's route must reach the server as a CLICK before the seat can answer
        // it, so a red that stops at `right_click_block` names the reach / held-item / unconfirmed-
        // teleport family and a red that stops at `mount` names the seat's own refusal. The probe
        // route mounts him server-side and never touches the interaction path, so it has one link.
        // Raised as an ARRANGEMENT failure: a boarding that never happened disproves nothing about
        // flying a ship boarded before assembly.
        String[] boardingChain = how == Boarding.RIGHT_CLICK
                ? new String[]{"right_click_block", "mount"}
                : new String[]{"mount"};
        try {
            events.assertChain(boardMark, "the bot never took the seat, so the scenario under test "
                            + "never started. boarding=" + how + " evidence=" + boardingEvidence,
                    100, boardingChain);
        } catch (AssertionError e) {
            scenario().arrangementFailed(e.getMessage());
        }

        // The boarding's own return value cannot be trusted to mean "he sat down" - confirm it from
        // what the client reports it is riding, WHAT that thing is, and WHERE it is.
        JsonObject riding;
        try {
            riding = ridingOnceTheClientHasMounted(boardOnClient, CLIENT_MOUNT_BUDGET_TICKS,
                    "the CLIENT must catch up with a boarding the SERVER has already recorded (the"
                            + " chain above), or nothing below is measured on a pilot this client"
                            + " believes is aboard. boarding=" + how + " evidence="
                            + boardingEvidence);
        } catch (AssertionError notArranged) {
            scenario().arrangementFailed(notArranged.getMessage()
                    + " | serverMountRecord=" + events.since(boardMark, "mount"));
            throw notArranged; // unreachable: arrangementFailed always throws
        }
        scenario().requireArranged("the bot is riding SOMETHING, but not "
                        + "the pilot seat's mount, so it is not piloting anything. boarding=" + how
                        + " riding=" + riding,
                "EntityDummy".equals(simpleNameOf(entityClassOf(riding))));
        double mountDistSq = distanceSqFromMountToSeatCentre(riding);
        scenario().requireArranged("the mount the bot is riding is not "
                        + "at the seat it was supposed to board, at (" + seatX + "," + seatY + ","
                        + seatZ + "). boarding=" + how + " distSq=" + mountDistSq
                        + " limit=" + MOUNT_AT_SEAT_DIST_SQ + " riding=" + riding,
                mountDistSq < MOUNT_AT_SEAT_DIST_SQ);

        // No rebind baseline is taken any more, and none is needed: the queue's give-up bookkeeping
        // is a RECORD naming the entry it is about, so the mark below scopes it the same way it
        // scopes the decisions. What used to stand here was a pair of cumulative counters for the
        // whole server process, read before and after in the hope that a delta meant something.

        // Now assemble the craft, with the pilot already aboard. Marked first: everything the
        // assembly does to this pilot — throwing him out of his seat, or swapping his stale mount
        // for the relocated one — is recorded after this point and nowhere else.
        long assemblyMark = events.markInstrumented();
        // The CLIENT's own mark beside it: the rebind swaps his stale mount for the relocated one,
        // and his client performs that mount. Read below as a record rather than sampled — a read
        // taken between the tear-down and the rebuild answers `riding:false` for a pilot the
        // assembly is in the middle of re-seating, which is the very verdict this leg reports.
        long assemblyOnClient = clientEvents().mark();
        String assemble = assembleFixture();
        scenario().requireArranged("a with-pilot-seat build must route to a ship: " + assemble,
                Reply.of(assemble).ok());
        bot().waitTicks(20);

        // CONTRACT, first half: sitting still means sitting. Assembling the ship under a seated
        // player must not throw him out of his seat.
        //
        // The client's own view is half of it, and it used to be the whole of it: a server-side
        // dismount whose packet has not yet arrived still reads as seated, so the old form asked for
        // two consecutive positive samples and hoped the gap was long enough. The server's record
        // answers that directly - a dismount either happened since the assembly or it did not - so
        // the heuristic is gone. A dismount alone is NOT the defect: the crew rebind swaps a stale
        // mount for the relocated one inside a single call, which is a dismount immediately followed
        // by a mount. A dismount with nothing after it is the pilot standing in his own hold.
        //
        // The two halves of that record are one ordered log read through TWO probe round-trips, so
        // they are two SNAPSHOTS taken milliseconds apart - and the swap they are judging happens
        // inside a single method call. A rebind that lands BETWEEN the two reads shows its dismount
        // to the later read and its mount to neither, and the pair then says "a dismount with
        // nothing after it" about a swap that completed. Measured on the 2026-09-06 gate: the mount
        // view was taken first and came back empty; the dismount view, taken a moment later, carried
        // one dismount whose caller trail ends at the swap's own first line - and the two envelopes
        // are not the same instant (one more dropped record, and `flight_computer_unmanned_events`
        // announced in the second only, i.e. the ship went LIVE between the reads, which is exactly
        // when the rebind becomes possible). Eviction is excluded as the cause: the log keeps one
        // ring PER TYPE and `droppedByType` named only `entity_joined_world`.
        //
        // So the dismount is read FIRST and the mount SECOND - the mount view is then never the
        // older of the two - and the pair is RE-READ while a dismount stands unanswered. The loop
        // costs nothing on the ordinary path (no dismount at all is already "seated", which is the
        // reading during the whole time the pilot waits on his stale mount) and spends ticks only in
        // the one case that is about to be called a defect.
        // Read through the shared wait rather than a hand-rolled sample loop. It buys three things
        // the loop did not: it fails AT the moment the claim is disproved rather than leaving the
        // verdict to an assertion that restated its own exit condition; its message carries the
        // ordered chain that DID happen plus the four-cause triage for an empty window; and the
        // budget arithmetic and the narrative stop being this class's to keep right.
        //
        // The type it waits on is `dismount`, NOT `mount`, and that preserves the read order the
        // paragraph above argues for: the verb fetches the dismount view first and the predicate
        // fetches the mount view second, so the mount view is never the older of the two.
        events.awaitMatching(assemblyMark, "dismount",
                dismountView -> seatedOnTheServersRecord(
                        events.since(assemblyMark, "mount"), dismountView),
                "leaving him SEATED on the server's own record (no dismount without a later mount)",
                "a player who sat in the pilot seat before assembling his ship must STILL be seated"
                        + " once assembly finishes - he should never have to stand up and sit down"
                        + " again to fly what he just built. A dismount with no mount after it is"
                        + " the shape the crew rebind produces when it retires the stale mount and"
                        + " then finds no seat mount to give him back (CrewTransfer's `dummy =="
                        + " null` exit) - read the dismount's `by` trail for the un-seater."
                        + " boarding=" + how + " ridingBeforeAssembly=" + riding,
                SEATED_CONFIRM_SAMPLES * TICKS_PER_SAMPLE);
        String dismounts = events.since(assemblyMark, "dismount");
        String mounts = events.since(assemblyMark, "mount");
        // The CLIENT's half of the same claim, as the LINK it is: the rebind's re-seat, performed by
        // his own client. The CONTRACT form — a client the assembly never re-seated is exactly what
        // this leg exists to catch, so it fails as an assertion and not as an arrangement.
        JsonObject ridingAfter = ridingOnceTheClientHasMounted(assemblyOnClient,
                CLIENT_MOUNT_BUDGET_TICKS,
                "a player who sat in the pilot seat before assembling his ship must be left seated by"
                        + " the assembly, and his own client must perform the re-seat. boarding="
                        + how);
        // Half of this claim is an ABSENCE, so the instrument that would have recorded a dismount is
        // shown RUNNING first. `entity_position_writers` is announced by the same mixin that carries
        // the mount and dismount hooks, and its injections are all required — it is woven whole or
        // the game never starts.
        Events.assertInstrumentRan(dismounts, "entity_position_writers",
                "assembly left the pilot in his seat");
        // The server's half is the wait above — it fails there, with the chain, the moment the claim
        // is disproved. What is left here is the CLIENT's settled state, which the link before it
        // established: he is on a mount, and the next assertion says WHICH.
        assertTrue("...and his own client must still have him on a mount when it is read. boarding="
                        + how + " ridingBeforeAssembly=" + riding + " ridingAfterAssembly="
                        + ridingAfter + " | the SERVER's own record since the assembly: dismounts="
                        + dismounts + " mounts=" + mounts,
                isRiding(ridingAfter));
        assertTrue("the mount a player is left riding after assembly must still be the pilot seat's, "
                        + "not some leftover entity. boarding=" + how + " riding=" + ridingAfter,
                "EntityDummy".equals(simpleNameOf(entityClassOf(ridingAfter))));

        // ARRANGEMENT window, load-aware: the boarding is re-expressed onto the relocated seat by
        // an asynchronous rebind that can only run once the physics mod has finished relocating the
        // craft into its subspace — and under a parallel-suite load that relocation lags by many
        // seconds. The contract clock ("controls the ship immediately") starts at the physics
        // object going LIVE, so waiting for the rebind here measures the contract, not a softened
        // version of it; without the wait the measurement legs below just run out before the ship
        // exists. Early exit the moment the rebind lands.
        // THE MULTIPLIER STAYS: what is being waited for is the VS ship OBJECT going live, which VS
        // does off the game loop, so a busy box needs more ticks to elapse before the rebind can land.
        //
        // The rebind is a DECISION production makes and now records as one: `crew_rebind_decided`
        // carries the tri-state the pending queue retries on, edge-collapsed, so the loop waits for
        // the decision itself instead of an increment in a cumulative counter — and a red names
        // which decision it was stuck on. NOT_READY to the end means the relocated seat never became
        // resolvable (the ship object never went live); NOT_ON_STALE_MOUNT means the pilot is not on
        // the mount the queue recorded, which is a different defect entirely and used to arrive as
        // the same "rebindRebound did not move" message.
        //
        // The queue's give-up counters are REPORTED here, not asserted on, and the difference is
        // whose pilot they are about. `rebindCancelled` / `rebindExpired` count for the SERVER
        // PROCESS and carry no identity: no player, no stale mount, no queue entry - so a delta
        // across this window says only "the queue gave up on somebody", and on the shared server
        // that somebody is routinely NOT this scenario's pilot. This class's own first method
        // leaves its entry pending when it reds, and that entry's give-up budget is minutes long,
        // so it expires INSIDE the second method's wait loop; `rebindLastOutcome` cannot separate
        // them either, because the anchor it prints is the assembly anchor (the flight computer,
        // one block above its build position) and both methods build the same fixture at the same
        // coordinates, so both entries carry the SAME anchor. Measured on the 2026-09-06 gate: this
        // was a hard assertion, and it red on `expired anchor=BlockPos{x=2802, y=69, z=2803}` - this
        // fixture's own flight computer - about a minute after the FIRST method assembled, while
        // this method's own entry had not yet been queued long enough to expire at all.
        //
        // What is left is the decision log, which IS scoped: `crew_rebind_decided` is read from this
        // scenario's own assembly mark. Its blind spot, stated rather than hidden: the record
        // carries the player's name and the anchor, and both methods share both, so a leftover entry
        // from the other method rebinding inside this window would read here as this pilot's. Only a
        // give-up record naming the queue entry (player + stale mount id) would let the counters
        // become an assertion again; there is none, so they stay in the failure message.
        int rebindBudget = 240;
        // The shared wait, keyed on the outcome, instead of a sample loop that re-read three things
        // per iteration to build a message it might never print. Two of those three — the probe's
        // seat-delivery state and the queue's own log — are DIAGNOSTICS, and a diagnostic belongs on
        // the failing path and at the end of the healthy one, not inside the wait: re-reading them
        // every sample cost a probe round trip per tick and changed no verdict.
        //
        // Wrapped, and the wrapper is the point: this rebind is this leg's ARRANGEMENT — the control
        // chain under test cannot come up without it — so a failure has to stay TYPED as one, which
        // a bare `awaitCarrying` (a contract AssertionError) would not. The catch re-reads the
        // diagnostics, proves the seam RAN before the silence is read as a refusal, and re-raises
        // through `arrangementFailed` with the wait's own chain appended.
        String rebindState;
        String decisions;
        String queue;
        String gaveUp;
        try {
            events.awaitField(assemblyMark, "crew_rebind_decided","outcome", "REBOUND",
                    "the crew rebind must decide REBOUND for the pilot whose ship was just"
                            + " assembled", rebindBudget);
        } catch (AssertionError never) {
            decisions = events.since(assemblyMark, "crew_rebind_decided");
            queue = events.since(assemblyMark, "crew_rebind_queue");
            // An empty decision log has two readings and only one is about the product: the rebind
            // seam ran and never reached REBOUND, or it never ran at all — the queue never asked,
            // which is a defect one step earlier and used to arrive as "the counter did not move".
            Events.assertInstrumentRan(decisions, "crew_transfer_events",
                    "the assembly's crew rebind never completed");
            scenario().arrangementFailed("the assembly's crew rebind never completed - the"
                    + " relocated ship/seat never became resolvable, so the control chain under"
                    + " test never came up. boarding=" + how
                    + " lastDecision=" + Events.lastField(decisions, "outcome")
                    + " delivery=" + exec("artest vs seat-delivery")
                    + " | the queue's own entries in this window: " + describeGiveUps(queue)
                    + " :: " + queue + " | " + never.getMessage());
        }
        rebindState = exec("artest vs seat-delivery");
        decisions = events.since(assemblyMark, "crew_rebind_decided");
        queue = events.since(assemblyMark, "crew_rebind_queue");
        gaveUp = describeGiveUps(queue);
        // Printed on a GREEN run too, not only into the failure: this is the queue's own account of
        // whose entry it took and whose it dropped, and a reader who only ever sees it on a red has
        // no idea what the healthy shape looks like.
        System.out.println("[preassembly] rebind queue :: " + gaveUp + " :: " + queue
                + " :: lastDecision=" + Events.lastField(decisions, "outcome")
                + " delivery=" + rebindState);
        // The REBOUND itself is the wait above, typed as the arrangement it is. Nothing is
        // re-asserted here: a second check of `decisions.contains("REBOUND")` would restate the
        // condition the wait already exited on, and its message would describe a rebind failure for
        // what could only ever be a budget that expired.

        // Paste-site census, printed unconditionally (visible in green runs too): assembly pastes
        // the craft one block above its build position before relocating it into the ship's
        // subspace, and whether anything LINGERS in the world afterwards distinguishes a clean
        // relocation from a leftover world-frame copy (which would explain pilot input landing on
        // a world-coordinate seat in live play).
        System.out.println("[PASTE-SITE] boarding=" + how
                + " seatBuild=" + bot().blockState(seatX, seatY, seatZ)
                + " seatPaste=" + bot().blockState(seatX, seatY + 1, seatZ));

        // The ship's IDENTITY, taken from its CREATION. This scenario built the craft and assembled
        // it, so it was already TOLD which ship that is: the assembly records `ship_spawned`, read
        // here from this scenario's own assembly mark. Every altitude read below is keyed on that
        // id — the legs that follow settle, drift-check and CLIMB the ship, and a nearest-ship query
        // about the build site cannot tell "my ship rose" from "a neighbour is now the closest thing
        // to that point".
        shipUuid = awaitShipSpawned(events, assemblyMark, "the craft this scenario assembled must "
                + "become a ship in the physics mod's registry before any altitude can be attributed "
                + "to it. boarding=" + how);

        // Identity is not readiness, and the second half is still owed: `ship_spawned` is the
        // REGISTRY's record of an ADD and does not prove the physics object is loaded, which under a
        // parallel-suite load lags by seconds. Readiness is waited for on PRODUCTION's own
        // announcement of it — `ship_usable`, published when the physics loop can step this ship —
        // and never on a `ship-info` poll: `managed` is a literal true in the reply builder, so it
        // only ever said "the lookup found a ship and built a report". The wait is keyed on the id
        // captured above, so it can never be satisfied by something else being nearer, which is what
        // a bounded nearest-query cannot say once the subject climbs. It runs from this scenario's
        // own ASSEMBLY mark — the same mark `ship_spawned` was read from, and the only one taken
        // before the load — because the event fires once and is not a state to poll. The rebind
        // above already proves the craft is live and it has not yet been asked to move, so this
        // ordinarily returns on an event already in the log; the budget stays the settle budget this
        // site was given, converted to Events.await's TICKS (240 five-tick polls = 1200 ticks).
        awaitShipUsable(events, assemblyMark, shipUuid, SETTLE_MAX_SAMPLES * TICKS_PER_SAMPLE);

        // ---- CONTROL LEG ---------------------------------------------------------------------
        // Settle first: a freshly assembled physics object may be resolved upward out of the pad it
        // overlaps, and that motion is not the pilot's.
        Settle rest = settleShipAltitude();
        double yRest = rest.y;
        scenario().requireArranged("the ship never reached a stable "
                        + "resting altitude within " + (SETTLE_MAX_SAMPLES * TICKS_PER_SAMPLE)
                        + " ticks, so it will not sit still and NO climb measured on it could be "
                        + "attributed to pilot input. boarding=" + how + " — " + rest.trace,
                !Double.isNaN(yRest));

        double controlDrift = measureMaxDrift(yRest);
        scenario().requireArranged("with NO key held the ship still "
                        + "moved " + controlDrift + " blocks over "
                        + (MEASURE_SAMPLES * TICKS_PER_SAMPLE) + " ticks (limit " + MAX_CONTROL_DRIFT
                        + "). A ship that drifts on its own cannot be used to measure whether the "
                        + "pilot's key lifted it - this says nothing about the contract, only that "
                        + "the arrangement is not a usable instrument. boarding=" + how
                        + " yRest=" + yRest,
                controlDrift < MAX_CONTROL_DRIFT);

        // ---- EXPERIMENTAL LEG ----------------------------------------------------------------
        double yBefore = shipPosY();
        scenario().requireArranged("the ship must still report an altitude at the start of the "
                        + "key-held window", !Double.isNaN(yBefore));

        // The real key, through the real client input path, exactly as a player holds it. Both logs
        // are marked FIRST, so the delivery chain read afterwards describes THIS window and nothing
        // that happened while the ship was settling.
        final double y0 = yBefore;
        long inputServerMark = events.markInstrumented();
        long inputClientMark = clientEvents().mark();
        bot().holdKey(Keyboard.KEY_R); // flightVerticalUp
        ClientPoll.Result<Double> lift;
        try {
            // Event-gated hover-lift (bounded ceiling + early exit): the loop returns the moment the
            // ship has climbed, so the ceiling is patience and not how far it flies. The probe keeps
            // the NaN-tolerant read (returns the baseline when shipPosY is unparseable).
            lift = ClientPoll.until(bot()::waitTicks,
                    () -> { double y = shipPosY(); return Double.isNaN(y) ? y0 : y; },
                    y -> (y - y0) >= MIN_CLIMB, TICKS_PER_SAMPLE, MEASURE_SAMPLES);
        } finally {
            bot().releaseKey(Keyboard.KEY_R);
        }
        double yAfter = lift.value;

        // Late paste-site census: by now the relocation demonstrably finished (the settle and the
        // measurement windows ran on the live ship), so anything still at the paste site is a
        // LINGERING world-frame copy, not relocation-in-progress.
        System.out.println("[PASTE-SITE post-flight] boarding=" + how
                + " seatPaste=" + bot().blockState(seatX, seatY + 1, seatZ));

        // Delivery-chain diagnostics, gathered AFTER the key-held window so they describe this very
        // attempt. Folded into the failure message: a red run must name the gate that ate the
        // input (client never sent / server dropped / delivered but no motion), not just report
        // "climb 0.0" and leave the chain to be guessed at.
        String delivery = deliveryDiagnostics(events, inputServerMark, inputClientMark);

        assertTrue("a player who took the pilot seat BEFORE assembling his ship must be able to FLY "
                        + "that ship right after assembly: holding the vertical-up key has to lift it, "
                        + "with no re-seating. boarding=" + how
                        + " | CONTROL (no key, same " + (MEASURE_SAMPLES * TICKS_PER_SAMPLE)
                        + "-tick window): drift=" + controlDrift
                        + " | EXPERIMENT (key held): yBefore=" + yBefore + " yAfter=" + yAfter
                        + " climb=" + (yAfter - yBefore) + " (need >= " + MIN_CLIMB + ")"
                        + " | DELIVERY: " + delivery
                        + " | riding=" + ridingAfter + " subsystem=" + status.raw(),
                (yAfter - yBefore) >= MIN_CLIMB);
    }

    /**
     * Reads both halves of the pilot-input delivery chain over the key-held window: what the CLIENT
     * decided and sent (its own event log — the gate's answer per seated tick, and every packet it
     * actually put on the wire), and what the SERVER received and delivered to the flight computer
     * (its log, plus the read-only {@code seat-delivery} probe). Never throws — a diagnostic that
     * kills the run it is meant to explain would be worse than none — and reports read failures
     * inline instead.
     *
     * <p>It replaces a reflective read of five client counters. The counters could say how MANY
     * ticks the gate was closed; the records say what it decided and when, which packets left, which
     * arrived and which reached the computer — so a red can be read as "the client never sent",
     * "the server never received" or "it was delivered and the ship did not move" instead of being
     * inferred from five numbers.</p>
     *
     * <p><b>What it is silent about.</b> Neither log says WHY a gate closed on a seated tick (no
     * link, or a link whose ship is gone); that is still the seat resolver's own reading. And
     * {@code ship_pilot_gate_decided} records once per seated tick against a 256-deep ring, so on a
     * window longer than about thirteen seconds the reply is a TAIL — the {@code droppedByType} it
     * carries says by how much.</p>
     */
    private String deliveryDiagnostics(Events events, long serverMark, long clientMark) {
        String client;
        try {
            client = "gate=" + clientEvents().since(clientMark, "ship_pilot_gate_decided")
                    + " sent=" + clientEvents().since(clientMark, "pilot_input_sent");
        } catch (Exception e) {
            client = "unreadable(" + e + ")";
        }
        String server;
        try {
            server = exec("artest vs seat-delivery")
                    + " received=" + events.since(serverMark, "pilot_input_received")
                    + " delivered=" + events.since(serverMark, "pilot_input_delivered");
        } catch (Exception e) {
            server = "unreadable(" + e + ")";
        }
        return "client{" + client + "} server{" + server + "}";
    }

    // ---- Boarding variants -------------------------------------------------------------------

    /**
     * The human's stimulus: a real right-click on the seat of an UNASSEMBLED craft, preceded by the
     * two arrangement checks that would otherwise let the server drop the click without a trace.
     */
    private String boardByRightClick() throws Exception {
        // Where the client ACTUALLY is - not where it was told to go. The teleport is what could
        // have failed, so measuring the target instead of the observation would check nothing.
        // And it DOES fail on the first try: a player teleported while still falling from the
        // staging position carries his momentum through the teleport, lands moving, and can slide
        // off the pad - so the teleport is re-issued until the client is OBSERVABLY standing in
        // reach, not merely told to be.
        double px = Double.NaN, py = Double.NaN, pz = Double.NaN;
        double distSq = Double.POSITIVE_INFINITY;
        for (int attempt = 0; attempt < 5 && distSq >= MAX_INTERACT_DIST_SQ; attempt++) {
            if (attempt > 0) {
                exec("tp @a " + standX + " " + standY + " " + standZ + " 0 0");
            }
            // The wait is load-bearing twice over: the server drops interactions while its own
            // teleport is unconfirmed, and the client needs time to damp any leftover motion.
            bot().waitTicks(20);
            JsonObject state = bot().reportState();
            for (int ready = 0; ready < 20 && !isWorldReady(state); ready++) {
                bot().waitTicks(TICKS_PER_SAMPLE);
                state = bot().reportState();
            }
            scenario().requireArranged("the client's world must be ready before its position can be "
                    + "read: " + state, isWorldReady(state));
            px = state.get("playerX").getAsDouble();
            py = state.get("playerY").getAsDouble();
            pz = state.get("playerZ").getAsDouble();
            distSq = distanceSqToSeatCentre(px, py, pz);
        }
        scenario().requireArranged("the client must OBSERVABLY be standing within the server's "
                        + "interaction reach of the seat, or the right-click is discarded before it "
                        + "reaches the seat block - and repeated teleports could not put it there. "
                        + "observed=(" + px + "," + py + "," + pz + ")"
                        + " distSq=" + distSq + " limit=" + MAX_INTERACT_DIST_SQ,
                distSq < MAX_INTERACT_DIST_SQ);

        // A held stack can consume the right-click before the block ever sees it - and a freshly
        // joined player does NOT start empty-handed (mods hand out items on first join), so the
        // hand is emptied explicitly and then VERIFIED from the client, not assumed.
        exec("clear @a");
        bot().selectHotbar(0);
        // The clear takes a few ticks to reach the client, so poll for the OBSERVED empty hand
        // rather than sampling once.
        JsonObject items = bot().reportPlayerItems();
        String heldId = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            if (isWorldReady(items)) {
                heldId = items.getAsJsonObject("held").get("id").getAsString();
                if (heldId.isEmpty()) {
                    break;
                }
            }
            bot().waitTicks(TICKS_PER_SAMPLE);
            items = bot().reportPlayerItems();
        }
        scenario().requireArranged("the client must be in a ready world before its held item can be "
                        + "read - a not-yet-ready client reports no hand at all: " + items,
                isWorldReady(items));
        if (heldId == null || !heldId.isEmpty()) {
            heldId = items.getAsJsonObject("held").get("id").getAsString();
        }
        scenario().requireArranged("the bot's main hand must be EMPTY (it was cleared server-side) so "
                        + "the right-click reaches the seat block rather than being consumed by a "
                        + "held item. held=" + heldId,
                heldId != null && heldId.isEmpty());

        return "click=" + bot().interactBlock(seatX, seatY, seatZ);
    }

    /**
     * The passing post-assembly test's boarding mechanism, performed BEFORE assembly. The seat probe
     * finds its seat by scanning the world's loaded tile entities, so a seat that is still a loose
     * world block is exactly as findable as one already relocated into a ship - which is what makes
     * this variant possible at all.
     */
    private String boardByProbe() throws Exception {
        SeatMount mountInfo = SeatMount.firstLoadedSeat(this::exec, 0);
        scenario().requireArranged("the seat probe must FIND the loose pilot seat before assembly: "
                + mountInfo.raw(), mountInfo.seatFound);

        // The probe takes the first pilot seat it finds anywhere in the world; pin that it found
        // OUR seat, at the position the block measurement just verified.
        scenario().requireArranged("the seat the probe bound must be the fixture's seat at (" + seatX
                        + "," + seatY + "," + seatZ + "), not some other pilot seat in the world: "
                        + mountInfo.raw(),
                mountInfo.seatX() == seatX
                        && mountInfo.seatY() == seatY
                        && mountInfo.seatZ() == seatZ);

        String mount = exec("artest player mount-entity " + mountInfo.requireDummyId());
        scenario().requireArranged("the bot must mount the seat's dummy: " + mount,
                Reply.of(mount).bool("mounted"));
        bot().waitTicks(10);
        return "seatMount=" + mountInfo.raw() + " mount=" + mount;
    }

    // ---- Observation helpers -----------------------------------------------------------------

    /** The NAMED ship's report, wherever it now is — no distance term to be wrong about. */
    private String shipInfo() throws Exception {
        scenario().requireArranged("shipInfo() before the ship's identity was captured",
                shipUuid != null);
        return shipInfoById(shipUuid);
    }

    /** The ship's world altitude, or {@code NaN} while it is not reporting one. */
    private double shipPosY() throws Exception {
        Reply mReply = Reply.of(shipInfo());
        return mReply.has(POS_Y) ? Double.parseDouble(mReply.text(POS_Y)) : Double.NaN;
    }

    /** A settle attempt: the altitude it came to rest at ({@code NaN} if it never did), and why. */
    private static final class Settle {
        final double y;
        final String trace;
        Settle(double y, String trace) { this.y = y; this.trace = trace; }
    }

    /**
     * Waits for the ship to hold one altitude within {@link #SETTLE_EPS} across
     * {@link #SETTLE_STABLE_SAMPLES} consecutive samples, and answers that altitude - or
     * {@code NaN}, with the reading, if it never settles within the budget.
     *
     * <p>CLASSIFIED, and it stays a loop. The altitude is a physical value nobody publishes and the
     * physics object never decides it has come to rest, so there is no link to await; but neither is
     * this the window-then-read form that governs a converging value, because the claim is not about
     * a VALUE at all — it is about the SEQUENCE. "The ship is sitting still" is a statement that N
     * consecutive readings agreed, and no single read taken at the end of a window can make it: a
     * craft still oscillating about the pad answers with a plausible altitude every time it is
     * asked. The loop IS the measurement here, not a way of waiting for one.</p>
     *
     * <p>What the expiry may no longer do is report {@code NaN} and leave the caller to say
     * "it never settled", which would be equally true of a ship that had drifted a hundred blocks
     * and of one wobbling by six centimetres. The trace carries the last anchor, the last reading
     * and the longest run of agreeing samples, so a refusal can be argued with rather than only
     * repeated.</p>
     */
    private Settle settleShipAltitude() throws Exception {
        double anchor = Double.NaN;
        double last = Double.NaN;
        int stable = 0;
        int bestRun = 0;
        int unresolved = 0;
        for (int sample = 0; sample < SETTLE_MAX_SAMPLES; sample++) {
            bot().waitTicks(TICKS_PER_SAMPLE);
            double y = shipPosY();
            last = y;
            if (Double.isNaN(y)) {
                unresolved++;
                anchor = Double.NaN;
                stable = 0;
                continue;
            }
            if (Double.isNaN(anchor) || Math.abs(y - anchor) > SETTLE_EPS) {
                anchor = y;
                stable = 0;
            } else {
                stable++;
                bestRun = Math.max(bestRun, stable);
                if (stable >= SETTLE_STABLE_SAMPLES) {
                    return new Settle(y, "settled at " + y + " after " + (sample + 1) + " samples");
                }
            }
        }
        return new Settle(Double.NaN, "never held one altitude to within " + SETTLE_EPS
                + " across " + SETTLE_STABLE_SAMPLES + " samples: longest agreeing run " + bestRun
                + ", last anchor " + anchor + ", last reading " + last + ", "
                + unresolved + " of " + SETTLE_MAX_SAMPLES + " samples resolved no ship at all");
    }

    /** The largest deviation from {@code from} over a full measurement window. */
    private double measureMaxDrift(double from) throws Exception {
        double worst = 0.0;
        for (int sample = 0; sample < MEASURE_SAMPLES; sample++) {
            bot().waitTicks(TICKS_PER_SAMPLE);
            double y = shipPosY();
            if (!Double.isNaN(y)) {
                worst = Math.max(worst, Math.abs(y - from));
            }
        }
        return worst;
    }

    /**
     * Wait for the CLIENT to perform the mount, then read what it is riding — ONCE.
     *
     * <p><b>Now the base's {@code awaitClientMount}</b>, which is the same mechanism: mark before
     * the stimulus, wait for the client's own {@code startRiding} with {@code ok:true}, prove the
     * recorder RAN before a silence is read as a refusal, and read the state once afterwards. This
     * class was the FOURTH copy of it; the other three were folded the same day. What stays local is
     * the {@code what} and the diagnosis, which is all that ever differed between the four.</p>
     *
     * <p>The CONTRACT form: it fails as an ordinary assertion. The boarding gate below wraps it into
     * an arrangement failure, exactly as {@code requireChain} wraps {@code assertChain} — a client
     * that never caught up with a boarding disproves nothing about flying a ship, while a client the
     * ASSEMBLY never re-seated is this class's subject.</p>
     *
     * @param clientMark taken on {@code clientEvents()} BEFORE the stimulus that seats him
     */
    private JsonObject ridingOnceTheClientHasMounted(long clientMark, int budgetTicks, String what)
            throws Exception {
        return awaitClientMount(clientMark, what, budgetTicks, "");
    }

    /**
     * Whether the SERVER's own record leaves the player SEATED: no dismount at all since the mark,
     * or one that a later mount undid.
     *
     * <p>A dismount by itself is not the defect. The assembly's crew rebind swaps the stale mount
     * for the relocated one inside a single call — a dismount immediately followed by a mount — and
     * a scenario that forbade dismounts outright would red on the very mechanism it is here to
     * prove. A dismount with nothing after it is the pilot left standing in his own hold, which is
     * exactly the failure the rebind's own {@code dummy == null} exit produces.</p>
     *
     * <p>Compared by sequence rather than by count: the two replies are filtered views of one
     * ordered log, and the log's {@code seq} is what still carries their order once they are split.</p>
     *
     * <p><b>The two replies must be read dismount-first.</b> Each is its own probe round-trip, so
     * they are snapshots of the log at two different instants, and the swap being judged happens
     * inside one method call. Read mount-first, a rebind landing between the two calls hands its
     * dismount to the later reply and its mount to neither - and this returns "left standing" about
     * a swap that completed. Read dismount-first, the mount view is never the older of the two.</p>
     */
    private static boolean seatedOnTheServersRecord(String mounts, String dismounts) {
        long off = lastSeq(dismounts);
        return off < 0 || lastSeq(mounts) > off;
    }

    /** The {@code seq} of the LAST record in a {@code since} reply, or -1 when it carries none.
     *
     *  <p>Asked of the parsed record rather than of the reply's text. The regex this replaces was
     *  defended with "the envelope has no {@code seq} of its own" — true of a {@code since} reply
     *  and false of a {@code mark} one, which is the kind of claim that holds until someone passes
     *  the other reply in. */
    private static long lastSeq(String sinceReply) {
        String last = Events.lastRecord(sinceReply);
        if (last == null) {
            return -1L;
        }
        double seq = Events.number(last, "seq");
        return Double.isNaN(seq) ? -1L : (long) seq;
    }

    /**
     * The queue's own entries in a window, and WHOSE they are.
     *
     * <p>Each {@code crew_rebind_queue} record names the entry it is about — the pilot's uuid and the
     * stale mount id — so a give-up can be attributed instead of merely counted. This scenario's
     * entry is the one its own {@code queued} record identifies, and the assembly that produced it
     * happened inside the window, so no probe and no baseline is needed to find it.</p>
     *
     * <p>What this replaces was a pair of counters cumulative for the server PROCESS, carrying no
     * player and no mount, read before and after in the hope a delta meant something. It could not:
     * on a shared server every scenario writes them, and two entries built at the same fixture
     * coordinates print the same anchor. Measured on the 2026-09-06 gate — a hard assertion red on
     * an earlier method's pilot expiring inside this method's wait loop.</p>
     *
     * <p>Still not asserted on, and now for an honest reason: whether the QUEUE gave up is a fact
     * about the queue, while the contract under test is whether the rebind completed — which the
     * decision log answers. This makes the failure message name the right pilot.</p>
     */
    private static String describeGiveUps(String queueReply) {
        String mine = null;
        StringBuilder out = new StringBuilder();
        for (String record : Events.records(queueReply)) {
            // `staleMount` and `attempts` are recorded as JSON NUMBERS, so they are read with
            // `number` and not `text` — which matches quoted values only and answers null. That
            // slip printed "after null ticks" and left the stale-mount half of the identity dead,
            // so two entries of the SAME pilot compared equal: precisely the confusion this record
            // exists to remove. Caught by printing the healthy case, not by a red.
            String entry = Events.text(record, "who") + ":"
                    + (long) Events.number(record, "staleMount");
            String outcome = Events.text(record, "outcome");
            if ("queued".equals(outcome)) {
                if (mine != null) {
                    out.append("[a SECOND entry was queued in this window: ").append(entry)
                            .append(" - anything below may be about either] ");
                }
                mine = entry;
                continue;
            }
            out.append('[').append(outcome).append(' ')
                    .append(entry.equals(mine) ? "THIS pilot's entry" : "somebody else's entry")
                    .append(" after ").append((long) Events.number(record, "attempts"))
                    .append(" ticks] ");
        }
        if (mine == null) {
            return "the queue took NO entry in this window - the assembler never asked for a rebind";
        }
        return out.length() == 0
                ? "one entry queued (" + mine + ") and the queue gave up on nobody" : out.toString();
    }

    private static boolean isRiding(JsonObject riding) {
        return riding != null && riding.has("riding") && riding.get("riding").getAsBoolean();
    }

    private static String entityClassOf(JsonObject riding) {
        return riding != null && riding.has("entityClass")
                ? riding.get("entityClass").getAsString() : "";
    }

    /**
     * The SIMPLE name of a qualified class, so "he is riding the dummy" is a comparison and not a
     * search. `contains("EntityDummy")` was satisfied by any class merely ENDING in it and by any
     * package component carrying those letters.
     */
    private static String simpleNameOf(String qualified) {
        int lastDot = qualified.lastIndexOf('.');
        return lastDot < 0 ? qualified : qualified.substring(lastDot + 1);
    }

    private static boolean isWorldReady(JsonObject report) {
        return report != null && report.has("worldReady") && report.get("worldReady").getAsBoolean();
    }

    /** How far the ridden mount sits from the seat block's centre, or {@code +inf} if unreported. */
    private double distanceSqFromMountToSeatCentre(JsonObject riding) {
        if (riding == null || !riding.has("posX") || !riding.has("posY") || !riding.has("posZ")) {
            return Double.POSITIVE_INFINITY;
        }
        return distanceSqToSeatCentre(riding.get("posX").getAsDouble(),
                riding.get("posY").getAsDouble(), riding.get("posZ").getAsDouble());
    }

    /** Instance, not static: the seat it measures to belongs to THIS scenario's plot. */
    private double distanceSqToSeatCentre(double x, double y, double z) {
        double dx = x - (seatX + 0.5);
        double dy = y - (seatY + 0.5);
        double dz = z - (seatZ + 0.5);
        return dx * dx + dy * dy + dz * dz;
    }

    // ---- Fixture -----------------------------------------------------------------------------

    /**
     * Places the craft's blocks and STOPS. Assembly is a separate step here, unlike the
     * post-assembly-boarding tests which do both at once, because the player has to be able to sit
     * down in between.
     */
    private void buildLooseFixture(FixtureSite site, String variant) throws Exception {
        // The site owns the coordinates; these aliases keep the body below unchanged.
        final int baseX = site.x, baseY = site.y, baseZ = site.z;
        // FIRST link: the volume is EMPTY, measured by the air fill's own `placed`. Open air, so
        // this ASSERTS rather than digs. It matters here because the player WALKS to the loose
        // craft and clicks its seat before anything is assembled — a pit puts him on a rim a metre
        // above the block he is aiming at, and the red then reads as the right-click path.
        int[] bp = RocketFixture.placeAt(site, this::exec, variant, 2, 16,
                "the loose craft, and the air the player stands and clicks in beside it");
        builderX = bp[0];
        builderY = bp[1];
        builderZ = bp[2];
    }

    /** Turns the loose blocks placed by {@link #buildLooseFixture} into a ship. */
    private String assembleFixture() throws Exception {
        return exec("artest rocket assemble 0 " + builderX + " " + builderY + " " + builderZ);
    }
}
