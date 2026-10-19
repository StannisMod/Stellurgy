package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.*;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import dev.stannismod.stellurgy.test.contract.Wait;
import org.junit.After;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A fire-control sensor bolted to a ship: what it must hold, and what it must never hold.
 *
 * <h3>The two things only a sensor on a hull has to get right</h3>
 * <p>A block of a ship sits in the SHIPYARD, a fixed address far from where the hull visibly is,
 * while every body the sensor could see lives in the world. So the sweep has to look out from where
 * the HULL puts the block, not from the block's own address; and it has to leave alone whoever stands
 * on its own deck, because a battery fed by it would otherwise be handed the ship's own crew. Neither
 * failure announces itself: the first is a sensor that never sees anything, the second a sensor that
 * works and is pointed at the wrong people.</p>
 *
 * <h3>One sweep answers both</h3>
 * <p>Two identical hostiles are put down: one standing on the sensor block itself, one in open air
 * beside the hull. The verdict is read off ONE record of the sensor's own sweep
 * ({@code sensor_swept}): the sweep that holds the open-air body is the positive control — the sensor
 * was sweeping, looking out from the right place, and can hear this kind of body — and in that same
 * sweep the nearer body on the deck is absent. Both bodies were in the world before that sweep began,
 * so its silence about the deck is a decision, not a sweep taken too early.</p>
 */
public class ASensorAboardAShipTest extends AbstractSharedServerTest {

    private static final int DIM = 0;

    /** This class's own build site and parking spot, clear of every other ship scenario. */
    private static final int SITE_X = 12400, SITE_Z = 12400;
    private static final int PARK_X = 12400, PARK_Y = 150, PARK_Z = 14800;

    /**
     * How far off the hull, along world X, the open-air body stands. The fixture is a launch stack a
     * few blocks across, so this is outside its box — and that is read from the substrate below, not
     * assumed. At this range a creeper is heard: {@code SignatureModel} gives a 300 K body of its
     * 4.8 m² a detection range of 3·√(σ·300⁴·4.8) ≈ 141 blocks, and a passive quality of
     * (300/500)⁴·(32/20)² ≈ 0.33 — measured 0.3312 on the first run.
     */
    private static final double OFF_HULL_DX = 20.0D;

    /**
     * How closely the range the sensor reports must agree with the range from the hull's transform.
     * The bodies do not move and the hull is parked, and both numbers are the same arithmetic on one
     * pose: measured 6.1e-7 blocks apart on 2026-09-30 (20.0180550 reported, 20.0180544 computed; the
     * reported one is float-rounded). A two-block frame error moved the range by 0.1.
     */
    private static final double RANGE_AGREEMENT = 1.0e-3D;

    /**
     * The deadline for a registry or physics record after an assembly. The substrate drains its
     * spawn queue on the game thread inside a world tick, so these are one or two ticks away; this is
     * two orders of magnitude past that, not an estimate of it.
     */
    private static final int ASSEMBLY_LINK_TICKS = 200;

    /** How many of the sensor's own sweep intervals the verdict's link is given; it needs ONE. */
    private static final int SWEEPS_OF_DEADLINE = 20;

    /** The sensor ticks in world {@link #DIM}, so its log is stepped by that world's clock. */
    private final Events events =
            new Events(c -> read(c).raw(), ticks -> GameTicks.advanceWorld(client(), DIM, ticks), evictionReports());

    /** Each config key this scenario set, with the value it held before the scenario touched it. */
    private final Map<String, String> configBefore = new LinkedHashMap<>();

    /** The physics id of the hull this scenario assembled, once it has one; disposed of when it ends. */
    private String builtHull;

    @After
    public void disposeOfTheBuiltHull() throws Exception {
        if (builtHull != null) {
            Disposal.ship(c -> arrange(c).raw(), events, DIM, builtHull);
            builtHull = null;
        }
    }

    @RedWitness(verdict = "its own deck", member = "TacticalScan#trackOf",
            at = "if (isAboard(world, ownShipId, candidate)) {", replacedBy = "if (false) {",
            fails = "the sensor holds the creeper standing on its own deck", on = "2026-10-19")
    @RedWitness(verdict = "held at all", member = "TileFireControlSensor#worldPosition",
            at = "TurretFireControl.worldPositionOf(world, pos, shipId())",
            replacedBy = "TurretFireControl.worldPositionOf(world, pos, null)",
            fails = "the sensor aboard the hull never held the creeper in open air beside it", on = "2026-10-19")
    @RedWitness(verdict = "the range", member = "TileFireControlSensor#worldPosition",
            at = "return TurretFireControl.worldPositionOf(world, pos, shipId());",
            replacedBy = "return TurretFireControl.worldPositionOf(world, pos, shipId()).addVector(0, 0, 2);",
            fails = "the sensor reports the open-air creeper at", on = "2026-10-19")
    @Test
    public void aSensorOnAShipHoldsWhatIsOffItAndNeverItsOwnDeck() throws Exception {
        // The sensor's update refuses to sweep unless its switch is on
        // (TileFireControlSensor.update); the test rests on it, so it states it.
        config("enableFireControlSensor", "true");
        double radius = Double.parseDouble(configText("fireControlSensorRadius"));
        int sweepInterval = Integer.parseInt(configText("fireControlSensorScanIntervalTicks"));

        String hull = assembleHull();
        parkStill(hull);

        // The sensor goes on top of the pilot seat: the seat is a block this hull owns, and the block
        // above it is in the same chunk column of the yard. Whether it landed on THIS hull is then
        // read back from the same question production asks (registeredShipIdManagingBlock).
        Reply seat = read("stellurgytest vs find-seat " + DIM + " id " + hull).requireOk("find the hull's seat");
        requireArranged("hull " + hull + " has no pilot seat to build on: " + seat, seat.bool("seatFound"));
        int sx = seat.integer("seatX"), sy = seat.integer("seatY") + 1, sz = seat.integer("seatZ");
        Reply placed = arrange("stellurgytest place " + DIM + " " + sx + " " + sy + " " + sz
                + " stellurgy:fireControlSensor").requireOk("place the sensor");
        requireArranged("the sensor was not placed: " + placed, placed.bool("placed"));
        Reply owner = read("stellurgytest vs managed-by " + DIM + " " + sx + " " + sy + " " + sz)
                .requireOk("ask which hull manages the sensor block");
        requireArranged("the sensor block is not a block of hull " + hull + ", so it is not a sensor on"
                + " a ship: " + owner, hull.equals(owner.textOr("shipId", null)));

        // Where the sensor and its top face are in the world, through this hull's transform. The
        // sensor measures from its block centre (TurretFireControl.center).
        double[] sensor = toWorld(hull, sx + 0.5D, sy + 0.5D, sz + 0.5D);
        double[] deck = toWorld(hull, sx + 0.5D, sy + 1.0D, sz + 0.5D);
        double[] air = {sensor[0] + OFF_HULL_DX, sensor[1], sensor[2]};
        holdChunksAround(sensor, air);

        // The two premises, read from the substrate's own containment (VSBridge.shipIdsAt — the very
        // test TacticalScan.isAboard makes), asked of THIS hull: the deck spot is inside its box, the
        // open-air spot outside it. This scenario built no other craft, and its base leaves none
        // from an earlier one.
        Reply onDeck = read("stellurgytest vs ship-contains " + DIM + " " + hull + " " + deck[0] + " "
                + deck[1] + " " + deck[2]).requireOk("ask whether the hull's box contains the deck spot");
        requireArranged("the deck spot is not inside hull " + hull + "'s box: " + onDeck,
                onDeck.bool("contains"));
        Reply inAir = read("stellurgytest vs ship-contains " + DIM + " " + hull + " " + air[0] + " "
                + air[1] + " " + air[2]).requireOk("ask whether the hull's box contains the open-air spot");
        requireArranged("the open-air spot is inside hull " + hull + "'s box, so it is not off the ship: "
                + inAir, !inAir.bool("contains"));

        long mark = events.markInstrumented();
        // The deck body first, so any sweep that holds the open-air one began with both in the world.
        Creeper aboard = drop(deck, "the creeper on the deck");
        Creeper outside = drop(air, "the creeper in open air");
        double expected = distance(sensor, outside.centre());
        double aboardRange = distance(sensor, aboard.centre());
        requireArranged("the open-air creeper is " + expected + " blocks from the sensor, outside its "
                + radius + "-block reach", expected < radius);
        requireArranged("the deck creeper is not the nearer of the two (" + aboardRange + " against "
                + expected + "), so its absence could be read as range", aboardRange < expected);

        String pos = sx + "," + sy + "," + sz;
        String sweeps = events.awaitMatching(mark, "sensor_swept",
                reply -> sweepHolding(reply, pos, outside.entityId) != null,
                "at " + pos + " holding entity " + outside.entityId,
                "the sensor aboard the hull never held the creeper in open air beside it — it is not"
                        + " looking out from where the hull is", SWEEPS_OF_DEADLINE * sweepInterval);
        Reply sweep = sweepHolding(sweeps, pos, outside.entityId);
        System.out.println("[sensor-aboard] sweeps at " + pos + " since the mark: "
                + Events.recordsWhere(sweeps, "pos", pos).size() + " (interval " + sweepInterval + ")");

        assertFalse("the sensor holds the creeper standing on its own deck, in the very sweep that holds"
                + " the one beside the ship — a battery fed by it would be handed its own crew: " + sweep,
                sweep.holdsElement("tracks", "entityId", String.valueOf(aboard.entityId)));

        // Where it was held: the range the sensor reports is the range from where the HULL is.
        double reported = sweep.element("tracks", "entityId", String.valueOf(outside.entityId))
                .number("distance");
        System.out.println("[sensor-aboard] reported range " + reported + ", computed " + expected
                + ", difference " + Math.abs(reported - expected));
        assertTrue("the sensor reports the open-air creeper at " + reported + " blocks, and it stands "
                + expected + " blocks from where the hull puts the sensor — it is measuring from"
                + " somewhere else: " + sweep, Math.abs(reported - expected) < RANGE_AGREEMENT);
    }

    /** The first sweep of the sensor at {@code pos} whose tracks hold this entity, or null. */
    private static Reply sweepHolding(String reply, String pos, int entityId) {
        for (String record : Events.recordsWhere(reply, "pos", pos)) {
            Reply sweep = Reply.of("sensor_swept", record);
            if (sweep.holdsElement("tracks", "entityId", String.valueOf(entityId))) {
                return sweep;
            }
        }
        return null;
    }

    // ---- the hull

    /**
     * Lay the with-pilot-seat fixture in this class's open-air site, assemble it, and answer the
     * physics id of the hull THIS assembly made — linked on the registry's record of it
     * ({@code ship_spawned}, carrying the durable name the assembly reported) and then on its
     * physics object being built ({@code ship_loaded}, keyed by that physics id).
     */
    private String assembleHull() throws Exception {
        FixtureSite site = FixtureSite.openAir(DIM, SITE_X, SITE_Z);
        long mark = events.markInstrumented();
        // The fixture verb lays a 6x6 pad with its builder and tower one block outside it, and its
        // tallest part is that tower, six above the pad: a halo of 4 and a height of 12 contain it.
        site.makeRoom(c -> arrange(c).raw(), 4, 12, "the craft the sensor is bolted to");
        Reply fixture = arrange("stellurgytest fixture rocket " + DIM + " " + site.x + " " + site.y + " " + site.z
                + " with-pilot-seat").requireOk("lay the fixture");
        int[] builder = fixture.blockPos("builderPos");
        Reply assembled = act("stellurgytest rocket assemble " + DIM + " " + builder[0] + " " + builder[1]
                + " " + builder[2]).requireOk("assemble the fixture");
        java.util.List<String> rockets = RocketFixture.rocketsBornAt(events, mark, builder);
        requireArranged("the build did not become one ship (rockets born " + rockets
                        + ", flight computers " + assembled.integer("afcCount") + "): " + assembled,
                rockets.isEmpty() && assembled.integer("afcCount") == 1);
        String durable = assembled.text("shipId");
        String spawned = events.awaitRecordWithField(mark, "ship_spawned", "stellurgyShip", durable,
                "the assembly never registered a hull named " + durable, ASSEMBLY_LINK_TICKS);
        String hull = Events.text(spawned, "vsShip");
        builtHull = hull;
        events.awaitField(mark, "ship_loaded", "vsShip", hull,
                "hull " + hull + " was registered and never got a physics object", ASSEMBLY_LINK_TICKS);
        return hull;
    }

    /**
     * Park the hull in open air with its physics off (the teleport verb's production move leaves it
     * so — VSBridge.teleportShip), and establish that it stands still where it was put.
     *
     * <p>The sensor's range is compared with a range this test computes from the hull's transform,
     * and the two are taken at different ticks — so the hull must not move between them.</p>
     */
    @Wait(shape = Wait.Shape.WINDOW, at = "Reply first = shipInfo(hull);",
            why = "a parked hull is a VALUE that must hold still, and nothing publishes \"it is still\". Two reads"
                    + " a world tick apart — the tick on which the physics object adopts the written pose"
                    + " (VSBridge.teleportShip sets it to use the game transform) — and the claim names both.")
    @Wait(shape = Wait.Shape.WINDOW, at = "GameTicks.advanceWorld(client(), DIM, 1);",
            why = "the one world tick between the two reads named above; the claim below names both.")
    private void parkStill(String hull) throws Exception {
        arrange("stellurgytest vs teleport-ship-by-id " + DIM + " " + hull + " " + PARK_X + " " + PARK_Y + " "
                + PARK_Z).requireOk("park hull " + hull);
        // Still in mid-air means FLOWN: a craft only built is released by its computer and falls. A zero
        // cruise through its own computer arms its hold, as a pilot braking to a stop leaves it.
        Reply flown = arrange("stellurgytest vs ff-cruise-by-id " + DIM + " " + hull + " 0 0 0");
        // absence is the answer "not flown": a verb that resolves no flight computer answers without the field.
        requireArranged("hull " + hull + " must be flown — its hold armed — to stand still: " + flown,
                flown.boolOr("afcResolved", false));
        Reply first = shipInfo(hull);
        GameTicks.advanceWorld(client(), DIM, 1);
        Reply second = shipInfo(hull);
        double moved = Math.sqrt(sq(second.number("posX") - first.number("posX"))
                + sq(second.number("posY") - first.number("posY"))
                + sq(second.number("posZ") - first.number("posZ")));
        double fromPark = Math.sqrt(sq(second.number("posX") - PARK_X) + sq(second.number("posY") - PARK_Y)
                + sq(second.number("posZ") - PARK_Z));
        System.out.println("[sensor-aboard] parked hull moved " + moved + " over a tick, " + fromPark
                + " from where it was put");
        requireArranged("hull " + hull + " is not standing still where it was parked (moved " + moved
                + " over a tick, " + fromPark + " from the spot): " + first + " then " + second,
                moved == 0.0D && fromPark < 1.0e-6D);
    }

    private Reply shipInfo(String hull) throws Exception {
        Reply info = read("stellurgytest vs ship-info " + DIM + " id " + hull);
        requireArranged("hull " + hull + " is not loaded here: " + info, info.boolOr("managed", false));
        return info;
    }

    private double[] toWorld(String hull, double x, double y, double z) throws Exception {
        Reply mapped = read("stellurgytest vs to-world " + DIM + " id " + hull + " " + x + " " + y + " " + z)
                .requireOk("map a subspace point of the hull to the world");
        return new double[]{mapped.number("worldX"), mapped.number("worldY"), mapped.number("worldZ")};
    }

    // ---- the bodies

    /**
     * A creeper put down where asked, without AI ({@code vs drop-living}): an AI-less mob is not a
     * "server world" to itself, so it never travels and never falls (EntityLivingBase.travel). A
     * creeper because it is hostile (TacticalScan admits it whatever the hostiles-only switch says)
     * and, unlike a zombie, does not catch fire in daylight — a burning body is a beacon.
     */
    private Creeper drop(double[] at, String what) throws Exception {
        Reply spawned = arrange("stellurgytest vs drop-living " + DIM + " minecraft:creeper " + at[0] + " " + at[1]
                + " " + at[2]);
        requireArranged("could not put down " + what + ": " + spawned,
                spawned.bool("ok") && spawned.bool("found") && !spawned.bool("dead"));
        return new Creeper(spawned.integer("entityId"), at, spawned.number("height"));
    }

    /**
     * Load and hold the world chunks the two bodies will stand in. A mob is refused by a chunk that
     * is not loaded (World.spawnEntity), and a sweep only finds bodies in loaded chunks; nobody is
     * standing near a parked hull to keep them loaded otherwise. {@code chunk warmup} provides them
     * now, {@code chunk forceload} keeps them.
     */
    private void holdChunksAround(double[] sensor, double[] air) throws Exception {
        int cx1 = (int) Math.floor(Math.min(sensor[0], air[0]) - 16) >> 4;
        int cx2 = (int) Math.floor(Math.max(sensor[0], air[0]) + 16) >> 4;
        int cz1 = (int) Math.floor(sensor[2] - 16) >> 4;
        int cz2 = (int) Math.floor(sensor[2] + 16) >> 4;
        arrange("stellurgytest chunk warmup " + DIM + " " + cx1 + " " + cz1 + " " + cx2 + " " + cz2)
                .requireOk("load the chunks around the hull");
        for (int cx = cx1; cx <= cx2; cx++) {
            for (int cz = cz1; cz <= cz2; cz++) {
                arrange("stellurgytest chunk forceload " + DIM + " " + cx + " " + cz).requireOk("hold a chunk");
            }
        }
    }

    // ---- config: the probe's own contract is that a key set is restored

    private void config(String key, String value) throws Exception {
        if (!configBefore.containsKey(key)) {
            configBefore.put(key, configText(key));
        }
        arrange("stellurgytest config set " + key + " " + value).requireOk("set " + key);
    }

    private String configText(String key) throws Exception {
        return read("stellurgytest config get " + key).requireOk("read " + key).text("value");
    }

    @After
    public void restoreConfig() throws Exception {
        for (Map.Entry<String, String> key : configBefore.entrySet()) {
            arrange("stellurgytest config set " + key.getKey() + " " + key.getValue())
                    .requireOk("restore " + key.getKey());
        }
        configBefore.clear();
    }

    // ---- plumbing

    private static double distance(double[] a, double[] b) {
        return Math.sqrt(sq(a[0] - b[0]) + sq(a[1] - b[1]) + sq(a[2] - b[2]));
    }

    private static double sq(double v) {
        return v * v;
    }



    /** A body this scenario put down, and where the sensor takes its middle to be (TacticalScan.bodyCentre). */
    private static final class Creeper {
        final int entityId;
        final double[] feet;
        final double height;

        Creeper(int entityId, double[] feet, double height) {
            this.entityId = entityId;
            this.feet = feet;
            this.height = height;
        }

        double[] centre() {
            return new double[]{feet[0], feet[1] + height * 0.5D, feet[2]};
        }
    }
}
