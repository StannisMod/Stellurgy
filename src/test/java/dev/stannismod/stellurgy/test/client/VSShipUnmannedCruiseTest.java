package dev.stannismod.stellurgy.test.client;

import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;
import org.lwjgl.input.Keyboard;


import dev.stannismod.stellurgy.test.SeatMount;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.ShipInfo;
import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.RocketFixture;
import dev.stannismod.stellurgy.tile.TileAdvancedFlightComputer;

import static org.junit.Assert.assertTrue;

/**
 * Flight Assist is an AUTOPILOT, not a dead-man switch: the FA state (mode + cruise setpoint) is a
 * SETTING the ship keeps executing when the pilot leaves his seat. A pilot who ramps a cruise with
 * a real held key and then dismounts leaves a ship that KEEPS CRUISING at that setpoint — it must
 * not brake to a hover the moment nobody is seated (that behaviour made the "autopilot" a
 * per-tick input echo). Re-mounting must not interrupt or reset the executing cruise either — the
 * saved setting is the pilot's to come back to.
 *
 * <p>Full honest path: the cruise is ramped by a REAL held key on the real client; the dismount
 * and re-mount are player actions (probe-driven where the harness cannot right-click a subspace
 * seat); the ship's continued motion is the server oracle. The zero-setpoint degenerate case
 * (station-hold, "the hovering ship fell" fix) stays pinned by the existing flight suite. Gated
 * on real VS —</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class VSShipUnmannedCruiseTest extends AbstractSharedVsClientTest {

    /**
     * How far the held key must have climbed before the dismount can test anything, in blocks.
     *
     * <p>The TEST'S OWN sensitivity bar: without a real climb the leg would be asking whether a
     * ship that was never moving kept moving.</p>
     */
    private static final double RAMPED_A_CLIMB_BLOCKS = 2.0;

    /**
     * How far the ship must climb in the LAST ten ticks of a forty-tick window, in blocks, for its
     * cruise to have SURVIVED a dismount or a re-mount.
     *
     * <p>The test's own bar, and the same RATE as before: it used to ask for 4 blocks over the whole
     * 40 ticks, which is 1 per 10. What changed is WHERE it is asked — see
     * {@code altitudeEveryTenTicks}.</p>
     */
    private static final double KEPT_CRUISING_BLOCKS_PER_SLICE = 1.0;

    /** How long the CLIENT is given to PERFORM a seating the server has already done, in ticks. */
    private static final int SEAT_LINK_BUDGET_TICKS = 200;

    @Override
    protected String subsystem() {
        return "vs-ship-unmanned-cruise";
    }

    private static final String POS_Y = "posY";
    private static final String DUMMY_ID = "dummyId";

    private static final String VARIANT = "with-pilot-seat";

    /** THIS scenario's ship, by identity — the address every altitude sample uses. */
    private String shipId;

    /**
     * A pilot who stands up leaves his ship cruising, and one who sits back down gets the cruise
     * back rather than a stopped or reset ship.
     *
     * <p>red-witnessed: the ramp and both cruise verdicts, 2026-09-28. THE RAMP — with
     * {@code TileAdvancedFlightComputer#setPilotInput} at {@code this.pilotInput = input} discarding
     * every input: "the held throttle must
     * ramp this ship's vertical cruise all the way to the computer's own ceiling … no
     * `cruise_setpoint_changed` with via = pilot and up at SHIP_MAX_SPEED was recorded within 200
     * ticks"; the setpoint verdict after that link is its read-back and falls with it. The dismount
     * and re-mount waits are links on the arrangement (he stood up; his client sat back down).
     * KEEPS CRUISING — with
     * {@code TileAdvancedFlightComputer}'s unmanned Flight-Assist command
     * ({@code TileAdvancedFlightComputer#update} at
     * {@code FreeFlightPhysics.shipVelocityCommand(FreeFlightInput.zero(), attitudeReference,}) built
     * from a zero setpoint
     * instead of {@code velocitySetpoint}: "Climb over the LAST ten ticks of the window=0.0", the
     * slices after the pilot left reading +10.9, +0.7, 0, 0. SURVIVES REMOUNT — with
     * {@code EntityDummy#syncFlightTelemetry} zeroing the cruise while a rider sits with no input yet:
     * the same message at the re-mount verdict, slices 0, 0, 0, 0. BOTH inversions first left the
     * verdicts GREEN in their earlier form, one climb over the whole forty ticks: a ship braking from
     * cruise to a hover still covered 11.7 and 7.4 blocks against a bar of 4. Healthy, each slice
     * reads about +20.</p>
     */
    @Test
    public void aDismountedPilotsShipKeepsCruisingAndSurvivesRemount() throws Exception {

        Events events = events();
        // WHERE THIS SCENARIO STANDS IS ASKED FOR, NOT CHOSEN. The plot is this scenario's own and
        // cannot overlap a sibling's; the height is the open-air band, because the site has no Y to
        // pass. Neither is a number this test has to get right, and both used to be.
        final FixtureSite site = site();
        final int bx = site.x, by = site.y, bz = site.z;
        // The mark is taken BEFORE the assembly is queued, so the ship_spawned record it waits for is
        // THIS assembly's ship and can be nobody else's. What it replaces was a poll for an ABSOLUTE
        // ship count >= 1 — a question any ship the world already held answers — followed by a
        // nearest-ship lookup at the build site to recover an identity the registry's own record
        // carries.
        long spawnMark = events.markInstrumented();
        long awayMark = clientEvents().mark();
        exec("tp @a " + (bx + 600) + " 120 " + (bz + 600) + " 0 0");
        awaitClientPlacedNear(awayMark, bx + 600, bz + 600,
                "the assembly below must run with no observer near it, and the observer is a client");
        String assemble = assembleFixture(site);
        assertTrue("a with-pilot-seat build must route to a ship: " + assemble,
                (Reply.of(assemble).integer("rocketCount") == 0));
        shipId = awaitShipSpawned(events,
                spawnMark, "a with-pilot-seat assembly must create a VS ship in the registry");

        long approachMark = clientEvents().mark();
        exec("tp @a " + (bx + 0.5) + " " + (by + 6) + " " + (bz + 0.5) + " 0 0");
        awaitClientPlacedNear(approachMark, bx + 0.5, bz + 0.5,
                "the client's ARRIVAL is what pulls the ship's chunks, so what is asked of the"
                        + " ship below is only answerable because a client got here");
        // The LOAD is a record: `ship_usable` for THIS ship, later than every unload of it — which is
        // what the "it flickers" argument for a loop here was about. Then ONE read, by identity.
        awaitShipUsable(events, spawnMark, shipId);
        String lastLookup = shipInfoById(shipId);
        // A ARRANGEMENT failure, and typed as one: a ship that never loaded has disproved nothing
        // about autopilots.
        scenario().requireArranged("this scenario's ship (" + shipId + ") must be loaded right after"
                        + " its usable record — reply " + lastLookup.replace('\n', ' '),
                ShipInfo.isLoaded(lastLookup));
        double y0 = ShipInfo.of(lastLookup).y;

        // Seat the bot, ramp a vertical cruise with the REAL key (Flight Assist is on by default:
        // holding the throttle ramps the setpoint; ~3 s of full deflection reaches cruise speed).
        SeatMount mountInfo = SeatMount.onShip(this::exec, 0, shipId);
        assertTrue("seat-mount must find the pilot seat: " + mountInfo.raw(),
                mountInfo.seatFound);
        long seatMark = clientEvents().mark();
        String mount = exec("stellurgytest player mount-entity " + mountInfo.requireDummyId());
        assertTrue("bot must mount the seat dummy: " + mount, Reply.of(mount).bool("mounted"));
        // The deflection below is a real key on a client that must already be riding; the setpoint
        // ramp it drives is what the whole scenario measures.
        awaitClientMount(seatMark, "the client must be riding the seat before the cruise is flown"
                + " from it", SEAT_LINK_BUDGET_TICKS, " | server said: " + mount);

        // 60 ticks of full deflection = the whole setpoint ramp (rest -> cruise speed). Kept
        // short deliberately: the ship keeps climbing for the rest of the test, and it must stay
        // within the (grounded) client's load range the whole time.
        double yRamped = y0;
        long rampMark = events.markInstrumented();
        bot().holdKey(Keyboard.KEY_R);
        try {
            // The key must reach THIS ship's flight computer before a climb can mean anything: the
            // seat forwards the client's packet and the computer is HANDED an input. Awaited as a
            // link, so "the ship never rose" cannot be reported for a key that never got there.
            // (No ship filter: this class builds one ship and flies it, and the mark is fresh.)
            events.awaitField(rampMark, "pilot_input_set", "input", "set",
                    "the real held key must reach the ship's flight computer at all", 100);
            // The ramp needs the FULL hold, and the computer says when it has it: every tick the ramp
            // moves the setpoint it records `cruise_setpoint_changed` (`via:"pilot"`), and the ramp
            // saturates at `SHIP_MAX_SPEED` — so "fully ramped" is the record whose `up` reached it.
            // A position read cannot answer that (a ship still accelerating has climbed too).
            events.awaitMatching(rampMark, "cruise_setpoint_changed",
                    reply -> {
                        for (String r : Events.recordsWhere(reply, "via", "pilot")) {
                            if (Events.number(r, "up") >= TileAdvancedFlightComputer.SHIP_MAX_SPEED - 1e-3) {
                                return true;
                            }
                        }
                        return false;
                    },
                    "with via = pilot and up at SHIP_MAX_SPEED",
                    "the held throttle must ramp this ship's vertical cruise all the way to the"
                            + " computer's own ceiling before the key is let go", 200);
            yRamped = shipY();
        } finally {
            bot().releaseKey(Keyboard.KEY_R);
        }
        // The pilot's own throttle moved the cruise setpoint (`via:"pilot"`), which is the setting
        // the whole rest of this scenario claims survives a dismount; the wait above already took
        // the saturated record. Read again for the message: without it a red below could not tell a
        // ramp that never happened from an autopilot that dropped it.
        String ramped = events.since(rampMark, "cruise_setpoint_changed");
        assertTrue("the held throttle must have moved the ship's CRUISE SETPOINT — that setting, not"
                        + " the key, is what an unmanned ship keeps executing. Recorded since the"
                        + " hold began: " + ramped,
                !Events.recordsWhere(ramped, "via", "pilot").isEmpty());
        scenario().requireArranged("the held key must have ramped a real climb before the dismount "
                        + "can test anything (y0=" + y0 + " yRamped=" + yRamped + ")",
                yRamped - y0 > RAMPED_A_CLIMB_BLOCKS);

        // Dismount mid-cruise. (The probe dismount stands in for any exit that is not the brake
        // key — standing up must not zero the cruise; braking to a stop before standing is the
        // pilot's separate, deliberate choice.)
        double yDismount = shipY();
        long dismountMark = events.markInstrumented();
        exec("stellurgytest player dismount");
        // The computer must SEE the pilot leave before "it kept cruising" says anything: the
        // riderless dummy clears the computer's pilot input, and until that lands the ship is simply
        // still being flown. This is the link the 40-tick window used to hide — a red then read as
        // two altitudes whether the dismount had reached the computer or not.
        events.awaitField(dismountMark, "pilot_input_set", "input", "null",
                "the flight computer must be told the pilot has gone, or the climb below is just a"
                        + " ship that is still being piloted", 100);
        // The window OPENS here and not at yDismount: the climb between the dismount and the
        // computer learning of it is still piloted flight, and on a slow box that stretch alone
        // could clear the bar and turn a dropped autopilot green.
        double yPilotGone = shipY();
        double[] unmanned = altitudeEveryTenTicks();
        double unmannedLastSlice = unmanned[3] - unmanned[2];
        assertTrue("an unmanned ship with Flight Assist on and a non-zero cruise setpoint must "
                        + "KEEP CRUISING after the pilot dismounts — that is what makes it an "
                        + "autopilot. Climb over the LAST ten ticks of the window="
                        + unmannedLastSlice + " (yDismount=" + yDismount + " yPilotGone="
                        + yPilotGone + ", then every 10 ticks: " + java.util.Arrays.toString(unmanned)
                        + ")",
                unmannedLastSlice > KEPT_CRUISING_BLOCKS_PER_SLICE);

        // Re-mounting must not interrupt (or reset) the executing cruise: the seat's dummy is
        // REUSED and the ship flies on while the returned pilot holds no key.
        SeatMount remount = SeatMount.onShip(this::exec, 0, shipId);
        assertTrue("seat-mount must still find the seat: " + remount.raw(),
                remount.seatFound);
        assertTrue("the re-mount must REUSE the seat's single dummy: " + remount.raw(),
                remount.reused);
        long remountOnClient = clientEvents().mark();
        String mounted = exec("stellurgytest player mount-entity " + remount.requireDummyId());
        assertTrue("bot must re-mount the seat dummy: " + mounted,
                Reply.of(mounted).bool("mounted"));
        // His CLIENT performs the seating too, and a client that resets the cruise on taking the
        // seat does it then — so the window opens only once it has, or a late seating would fall
        // after the window and its reset go unseen.
        awaitClientMount(remountOnClient, "the returning pilot's client must perform the re-seating"
                + " before the cruise is watched", SEAT_LINK_BUDGET_TICKS, " | server said: " + mounted);
        double yRemount = shipY();
        double[] remounted = altitudeEveryTenTicks();
        double remountedLastSlice = remounted[3] - remounted[2];
        assertTrue("a re-mounted pilot receives the executing cruise BACK — the ship must not "
                        + "stop or reset because he sat down. Climb over the LAST ten ticks of the"
                        + " window=" + remountedLastSlice + " (yRemount=" + yRemount
                        + ", then every 10 ticks: " + java.util.Arrays.toString(remounted) + ")",
                remountedLastSlice > KEPT_CRUISING_BLOCKS_PER_SLICE);
    }

    // ---- helpers -------------------------------------------------------------------------------

    /**
     * The hull's altitude at the end of each of four ten-tick slices, from now.
     *
     * <p>Why slices and not one climb over the whole forty: a ship whose cruise was DROPPED does not
     * stop at once — it brakes, and braking from cruise speed carries it well over any whole-window
     * bar. With the unmanned cruise zeroed in production, the single-climb form of both verdicts
     * below stayed green. The LAST slice is where a braking ship has already stopped and a cruising
     * one has not.</p>
     */
    private double[] altitudeEveryTenTicks() throws Exception {
        double[] y = new double[4];
        // WINDOW: four reads ten ticks apart, and the verdict is over the last difference and names
        // all four. The link would be a record of the hull's pose per tick, and none exists.
        for (int i = 0; i < y.length; i++) {
            bot().waitTicks(10);
            y[i] = shipY();
        }
        return y;
    }

    private double shipY() throws Exception {
        return readDouble(shipInfoById(shipId), POS_Y);
    }

    // WHY EVERY WAIT HERE NAMES A FIELD. `Events.await` matches on the type alone, and the two
    // links this scenario waits for differ only in their payload: a `pilot_input_set` carrying
    // "set" is the throttle arriving, one carrying "null" is the pilot leaving, and waiting for
    // "either" would let the mount satisfy the dismount's wait. The join is the `input` FIELD,
    // through Events.awaitField. The local wrapper this replaced matched a raw substring
    // (`"input":"set"`), which rides on the writer's field ORDER and on the quoting it happened to
    // use.

    private double readDouble(String json, String field) {
        double value = Reply.of(json).number(field);
        return value;
    }

    private String assembleFixture(FixtureSite site) throws Exception {
        // The site owns the coordinates; these aliases keep the body below unchanged.
        final int baseX = site.x, baseY = site.y, baseZ = site.z;
        // FIRST link: the volume the craft is built in and cruises out of is EMPTY, measured by the
        // air fill's own `placed`. Open air, so this ASSERTS rather than digging the shaft it
        // replaces — a craft that cruises out of a pit meets its rim, and the red then names the
        // cruise. The fill force-loads every chunk in the box, so the warmup lost nothing.
        return RocketFixture.assembleAt(site, this::exec, VARIANT, 2, 16,
                "the hull, and the first blocks of the lane it cruises along");
    }
}
