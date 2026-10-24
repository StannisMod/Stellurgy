package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.*;
import dev.stannismod.stellurgy.test.contract.Pins;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import dev.stannismod.stellurgy.test.contract.Wait;
import org.junit.After;
import org.junit.Test;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertTrue;

/**
 * AUTO-TAKEOFF as the motion slot flies it: the pilot's take-off key hands the craft {@code GO_TO} the
 * orbit of the body it stands on, and guidance climbs it straight up past the orbit line, where the entry
 * crossing takes it into space with the order still in its slot, on its body's ring, where the order has
 * arrived. One group, three scenarios — the climb that enters and arrives, the climb refused by a blocked
 * column, and the pilot taking the craft back mid-climb.
 *
 * <p>What this does NOT see: the client's keybind and the seat packet's pilot guard (the key is pressed
 * through the {@code space auto-takeoff} probe, which calls the flight computer's take-off entry the seat
 * packet calls); the message a refused pilot is shown (no player is seated — the refusal reaches the pilot
 * by the same call every refusal does); a MOON's orbit, which moves inside the cell it shares; and the laws
 * of the orbit target and of the entry's placement, which are pinned without a world in
 * {@code MotionGuidanceLawsTest} and {@code ShipEntryControllerTest}.</p>
 */
public class VSShipAutoTakeoffTest extends AbstractSharedServerTest {

    /**
     * How far below the overworld's entry line the climbing craft is put, blocks: a climb guidance must
     * accelerate, close and cross on its own — several hull lengths — while keeping the scenario short.
     * The line itself is read from the server: it is the body's own, not a number this class can know.
     */
    private static final int BELOW_THE_LINE = 20;

    /** A LINK budget in server ticks: a craft named and usable, one or two ticks away. */
    private static final int USABLE_TICKS = 200;

    /** A LINK budget in server ticks: the climb from {@link #BELOW_THE_LINE} plus the entry's settle. */
    private static final int CLIMB_TICKS = 800;

    /**
     * A LINK budget in server ticks, from before the climb: the climb and the entry's settle
     * ({@link #CLIMB_TICKS}), then the craft coming to rest on the orbit it was put on, with room. Measured
     * 2026-10-23: the arrival was declared within a tick of the entry's own settle record
     * ({@code logs/t847-takeoff2-results.xml}, the {@code [takeoff-orbit]} line).
     */
    private static final int ORBIT_TICKS = CLIMB_TICKS + 200;

    /** A LINK budget in server ticks: the refusal window ({@code MotionSlot.REFUSAL_WINDOW_TICKS}, 40) and room. */
    private static final int REFUSE_TICKS = 200;

    /**
     * How long the craft climbs before the pilot takes it, in server ticks, and how far it must have risen
     * by then, blocks: enough to read as climbing rather than settling on its pad.
     */
    private static final int CLIMB_BEFORE_INPUT_TICKS = 40;
    private static final double RISEN_AT_LEAST = 1.0D;

    /** The window after the pilot lets go over which the craft must not climb on, in server ticks. */
    private static final int AFTER_LETTING_GO_TICKS = 60;

    /**
     * The fraction of the rate it was climbing at before the pilot took it back that the craft may still
     * rise at after he lets go. A craft that resumed the climb would rise at the climb's own rate or
     * faster; one that holds rises at none.
     */
    private static final double CLIMB_FRACTION_LEFT = 0.25D;

    /** This class's reader of the server's ordered event log, on the server's own clock. */
    private final Events events =
            new Events(c -> read(c).raw(), ticks -> GameTicks.advance(client(), GameTicks.server(), ticks),
                    evictionReports());

    /** The mark taken before this scenario's build, and the durable name of the craft it built. */
    private long buildMark;
    private String builtDurable;

    /** The craft goes first, wherever its latest naming put it; the entry stack after it. */
    @After
    public void cleanup() throws Exception {
        if (builtDurable != null) {
            OwnShip.disposeOf(c -> read(c).raw(), c -> arrange(c).raw(), events, buildMark, builtDurable);
            builtDurable = null;
        }
        arrange("stellurgytest space entry-clear");
    }

    /**
     * The take-off key issues ONE {@code GO_TO} the orbit of the overworld's body; the craft climbs, the
     * entry takes it into space onto that body's ring, and there the order has arrived and the slot holds it.
     */
    @Pins({"INV-GDN-17", "SHIPCTL-22"})
    @RedWitness(verdict = "enters", member = "GuidanceTarget#orbitIn",
            at = "double top = line + CLIMB_PAST_THE_LINE;", replacedBy = "double top = line - CLIMB_PAST_THE_LINE;",
            fails = "the take-off must climb the craft across the entry line into space", on = "2026-10-23")
    @RedWitness(verdict = "rides on", member = "GuidanceTarget#writeToNBT",
            at = "nbt.setString(NBT_KIND, KIND_ORBIT);", replacedBy = "nbt.setString(NBT_KIND, \"\");",
            fails = "in space the take-off's order arrives at the overworld's orbit", on = "2026-10-23")
    @RedWitness(verdict = "arrives", member = "ShipEntryController#entryPoint",
            at = "frames.positionInCell(cell, b.absoluteAt(tick), tick)",
            replacedBy = "CellFrames.STATIC.positionInCell(cell, b.absoluteAt(tick), tick)",
            fails = "in space the take-off's order arrives at the overworld's orbit", on = "2026-10-23")
    @RedWitness(verdict = "arrives at its body's own ring", member = "ShipEntryController#entryPoint",
            at = "entryRingAround(b), seed);",
            replacedBy = "entryRingAround(java.util.Collections.max(atAddress,"
                    + " java.util.Comparator.comparingLong(ShipEntryController::entryRingAround))), seed);",
            fails = "in space the take-off's order arrives at the overworld's orbit", on = "2026-10-23")
    @RedWitness(verdict = "issued", member = "TileAdvancedFlightComputer#toggleAutoTakeoff",
            at = "issueMotion(takeoff);", replacedBy = "",
            fails = "the take-off key must hand the craft's motion slot one GO_TO the orbit", on = "2026-10-23")
    @Test
    public void theTakeoffKeyClimbsTheCraftIntoSpaceWhereItHoldsInOrbit() throws Exception {
        Reply setup = arrange("stellurgytest space entry-setup 2");
        requireArranged("the entry stack must be there to take the craft into space: " + setup, setup.ok());
        String shipId = buildCraft("the craft the take-off flies");
        ShipInfo built = ShipInfo.byId(c -> read(c).raw(), 0, shipId);
        int line = OrbitLine.of(c -> read(c).raw(), 0).entryCeiling();
        Reply hop = arrange("stellurgytest vs teleport-ship-by-id 0 " + shipId + " " + built.x + " "
                + (line - BELOW_THE_LINE) + " " + built.z);
        requireArranged("the craft must be put " + BELOW_THE_LINE + " blocks below the line: " + hop, hop.ok());
        requireArranged("the craft must fly again after the hop",
                arrange("stellurgytest vs unpark-by-id 0 " + shipId).ok());

        long climbMark = events.mark();
        Reply pressed = act("stellurgytest space auto-takeoff 0 id " + shipId);
        // The target is read only of a slot that holds something: an empty one has none to read.
        assertTrue("the take-off key must hand the craft's motion slot one GO_TO the orbit: " + pressed,
                "ACTIVE".equals(pressed.text("state")) && "orbit 0".equals(pressed.text("target")));

        // Linked on the entry's own record of the craft leaving its planet, posted at the settle.
        String left = events.awaitRecordWithField(climbMark, "ship_left_planet", "ship", builtDurable,
                "the take-off must climb the craft across the entry line into space", CLIMB_TICKS);
        int cellDim = (int) Events.number(left, "cellDim");
        // Linked on guidance's own declaration that the craft is on its body's orbit, at rest. The entry
        // puts it on that body's ring, so this is the order finished, not a flight across the system: a
        // craft put anywhere else is still making for the orbit when the budget runs out.
        String arrived = events.awaitRecordWithFields(climbMark, "motion_arrived",
                "in space the take-off's order arrives at the overworld's orbit", ORBIT_TICKS,
                "ship", builtDurable, "target", "orbit 0");
        // Arrival is declared only in space: on the planet the climb is made for, never arrived at.
        Reply slot = read("stellurgytest vs motion-read-by-id " + cellDim + " " + shipId);
        System.out.println("[takeoff-orbit] left " + left + " :: arrived " + arrived + " :: slot " + slot);
        assertTrue("in space the take-off's order arrives at the overworld's orbit, in the cell the entry put the"
                        + " craft in, and the slot holds it there (" + left + " :: " + arrived + "): " + slot,
                (int) Events.number(arrived, "dim") == cellDim && slot.bool("afcResolved")
                        && "ACTIVE".equals(slot.text("state")) && "orbit 0".equals(slot.text("target"))
                        && slot.bool("arrived"));
    }

    /**
     * A solid block in the column over the craft refuses the climb: the refusal is announced, and the slot
     * reads REFUSED with the reason, not empty.
     */
    @Pins("INV-GDN-18")
    @RedWitness(verdict = "refused", member = "GuidanceTarget#orbitIn", at = "if (!around.openAbove(",
            replacedBy = "if (false && !around.openAbove(",
            fails = "a blocked column must refuse the take-off", on = "2026-10-23")
    @RedWitness(verdict = "the slot says so", member = "MotionSlot#fly",
            at = "state = State.REFUSED;", replacedBy = "state = State.ACTIVE;",
            fails = "refused, the slot reads REFUSED with the reason", on = "2026-10-23")
    @Test
    public void aBlockedColumnRefusesTheTakeoffAndTheSlotSaysSo() throws Exception {
        FixtureSite site = site();
        String shipId = buildCraftAt(site, "the craft whose way up is blocked");
        ShipInfo built = ShipInfo.byId(c -> read(c).raw(), 0, shipId);
        // A slab across the whole pad, above everything the fixture laid (its tower tops out at site.y+6),
        // inside the volume the site cleared.
        int slabY = site.y + SLAB_ABOVE_SITE;
        Reply slab = arrange("stellurgytest fill 0 " + site.x + " " + slabY + " " + site.z + " "
                + (site.x + FixtureSite.PAD) + " " + slabY + " " + (site.z + FixtureSite.PAD) + " minecraft:stone");
        requireArranged("a slab must stand over the craft (pose y=" + built.y + ", slab y=" + slabY + "): " + slab,
                slab.ok() && slabY > built.y);

        long refusalMark = events.mark();
        Reply pressed = act("stellurgytest space auto-takeoff 0 id " + shipId);
        requireArranged("the key must have handed the slot the climb before it is refused: " + pressed,
                "ACTIVE".equals(pressed.text("state")));
        String refused = events.awaitRecordWithFields(refusalMark, "motion_refused",
                "a blocked column must refuse the take-off", REFUSE_TICKS,
                "ship", builtDurable, "target", "orbit 0", "reason", "PATH_BLOCKED");
        Reply slot = read("stellurgytest vs motion-read-by-id 0 " + shipId);
        assertTrue("refused, the slot reads REFUSED with the reason and still holds the order (" + refused
                        + "): " + slot,
                "REFUSED".equals(slot.text("state")) && "PATH_BLOCKED".equals(slot.text("refusal"))
                        && "orbit 0".equals(slot.text("target")));
    }

    /** Where the blocking slab is laid above the site, blocks: over the fixture's tower, inside the cleared volume. */
    private static final int SLAB_ABOVE_SITE = 10;

    /**
     * A pilot's input mid-climb takes the craft back: the slot empties, and when he lets go nothing resumes
     * — the slot stays empty and the craft does not climb on.
     */
    @Pins("SHIPCTL-21")
    @RedWitness(member = "TileAdvancedFlightComputer#setPilotInput",
            at = "if (input != null && motion.reclaim()) {", replacedBy = "if (false && motion.reclaim()) {",
            fails = "the pilot's input must take the craft back for good", on = "2026-10-23")
    @Wait(shape = Wait.Shape.STIMULUS, at = "GameTicks.advance(client(), GameTicks.server(), CLIMB_BEFORE_INPUT_TICKS);",
            why = "the climb is a dose before the pilot's input: how far it rose is read and required after it.")
    @Wait(shape = Wait.Shape.WINDOW, at = "Window<ShipInfo> climbing = Window.over(",
            why = "the climb's rate: a height that changes continuously, judged over the ticks stamped at both reads.")
    @Wait(shape = Wait.Shape.WINDOW, at = "Window<ShipInfo> afterwards = Window.over(",
            why = "the rate after letting go: a height that changes continuously, judged over the ticks stamped"
                    + " at both reads.")
    @Test
    public void aPilotsInputMidClimbTakesTheCraftBackAndLettingGoResumesNothing() throws Exception {
        String shipId = buildCraft("the craft the pilot takes back");
        Events.Step serverTicks = ticks -> GameTicks.advance(client(), GameTicks.server(), ticks);
        ShipInfo built = ShipInfo.byId(c -> read(c).raw(), 0, shipId);
        Reply pressed = act("stellurgytest space auto-takeoff 0 id " + shipId);
        requireArranged("the key must have handed the slot the climb: " + pressed,
                "ACTIVE".equals(pressed.text("state")));
        GameTicks.advance(client(), GameTicks.server(), CLIMB_BEFORE_INPUT_TICKS);
        Window<ShipInfo> climbing = Window.over(() -> ShipInfo.byId(c -> read(c).raw(), 0, shipId), client(),
                GameTicks.server(), serverTicks, CLIMB_BEFORE_INPUT_TICKS);
        double climbRate = climbing.rate(info -> info.y);
        ShipInfo before = ShipInfo.byId(c -> read(c).raw(), 0, shipId);
        int line = OrbitLine.of(c -> read(c).raw(), 0).entryCeiling();
        requireArranged("the craft must be climbing, still below the line (built y=" + built.y + ", line " + line
                        + ", rate " + climbRate + " blocks/tick): " + before,
                before.y - built.y >= RISEN_AT_LEAST && climbRate * CLIMB_BEFORE_INPUT_TICKS >= RISEN_AT_LEAST
                        && before.y < line);

        // The pilot turns the craft — an input that leaves the cruise he left it alone — through the seat's
        // own hop to its computer, the path his control packet takes on the server.
        Reply turning = arrange("stellurgytest vs seat-input-by-id 0 " + shipId + " 0 0 0 1 0 0");
        requireArranged("the input must reach the craft through its seat: " + turning, turning.bool("afcResolved"));
        Reply taken = read("stellurgytest vs motion-read-by-id 0 " + shipId);
        // ...and lets go: the idle input a released key sends.
        Reply released = arrange("stellurgytest vs seat-input-by-id 0 " + shipId + " 0 0 0 0 0 0");
        requireArranged("the release must reach the craft through its seat: " + released, released.bool("afcResolved"));
        Window<ShipInfo> afterwards = Window.over(() -> ShipInfo.byId(c -> read(c).raw(), 0, shipId), client(),
                GameTicks.server(), serverTicks, AFTER_LETTING_GO_TICKS);
        Reply after = read("stellurgytest vs motion-read-by-id 0 " + shipId);
        double rateAfter = afterwards.rate(info -> info.y);
        System.out.println("[takeoff-reclaim] climbing " + climbRate + " blocks/tick, after letting go " + rateAfter
                + " :: taken " + taken + " :: after " + after);
        assertTrue("the pilot's input must take the craft back for good: the slot after his input " + taken
                        + ", after he let go " + after + "; rising " + rateAfter + " blocks/tick against the climb's "
                        + climbRate + " :: " + afterwards,
                "EMPTY".equals(taken.text("state")) && "EMPTY".equals(after.text("state"))
                        && rateAfter < CLIMB_FRACTION_LEFT * climbRate);
    }

    /** Build and assemble a piloted craft on this scenario's own site; answers its physics id once usable. */
    private String buildCraft(String what) throws Exception {
        return buildCraftAt(site(), what);
    }

    private String buildCraftAt(FixtureSite site, String what) throws Exception {
        // HEIGHT 12: the fixture's tower reaches site.y+6 and a blocking slab, where one is laid, site.y+10.
        int[] builder = RocketFixture.placeAt(site, c -> arrange(c).raw(), "with-pilot-seat", 2, 12,
                "the craft stands and starts its climb in this volume");
        buildMark = events.mark();
        String pressed = RocketFixture.assembleBuilt(site, c -> act(c).raw(), builder);
        ShipBirth birth = ShipBirth.ofPress(events, buildMark, site.dim, builder, pressed, USABLE_TICKS, what);
        builtDurable = birth.durable;
        requireArranged("with VS, the pilot-seat build must route to a ship (no rocket): " + birth,
                birth.rocketsBorn.isEmpty());
        return ArrangementFailure.arranged(() -> birth.awaitUsable(c -> read(c).raw(), events, USABLE_TICKS));
    }
}
