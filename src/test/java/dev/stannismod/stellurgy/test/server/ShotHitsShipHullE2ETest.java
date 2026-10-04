package dev.stannismod.stellurgy.test.server;

import org.junit.Before;
import org.junit.Test;

import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.ShipReadiness;
import dev.stannismod.stellurgy.test.WarShip;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A shot that meets a SHIP — the case the whole substrate exists for, and the one its world-block
 * tests cannot reach.
 *
 * <p>A ship's blocks are not where the ship appears to be: they sit at fixed addresses in a shipyard
 * subspace millions of blocks away while the hull flies around. So a swept segment computed in world
 * coordinates crosses <b>nothing</b> — the world frame is empty air where the hull visibly is. The
 * substrate therefore maps both ends of the segment into each candidate ship's frame, traverses
 * there, and maps the crossing point back out. Three conversions, none of which announces itself when
 * it is wrong: a frame error here is not an exception, it is a round that flies through a hull.</p>
 *
 * <h3>What makes this evidence rather than a coincidence</h3>
 * <p>Two controls, both asserted before any conclusion is drawn. The world frame at the target must
 * genuinely hold <b>air</b>, so a hit cannot have come from the world-frame traversal; and the
 * subject block must be undamaged at its subspace address beforehand, so "damaged afterwards" is
 * about this shot. The shot's own position after the crossing is then checked against the ship's
 * WORLD position — with the mapping-back-out leg deleted it would be five million blocks away in a
 * shipyard nobody can see, and every other assertion here would still pass.</p>
 *
 * <p>The substrate is stepped by the probe ({@code shield tick}), so each read follows the step it is
 * about. The craft is built and addressed by identity ({@link WarShip}); the class used to carry an
 * {@code Assume} on a {@code vs available} verb that no longer exists, so it had been SKIPPED on every
 * run since that verb was removed.</p>
 */
public class ShotHitsShipHullE2ETest extends AbstractSharedServerTest {

    /** A build site of this class's own, clear of the other ship scenarios on this shared server. */
    private static final int SRC_X = 6400, SRC_Z = 6400;
    /** Where the ship is moved to: in the air, INSIDE build height, so "the world is air there" is a
     *  measurement rather than a consequence of being above the world's ceiling. */
    private static final int FAR_X = 6400, FAR_Y = 150, FAR_Z = 8800;

    /** Fast enough that one tick's segment crosses the whole hull — the case a point test misses. */
    private static final double SPEED = 40.0D;
    /**
     * How much of a block's destruction price the round is given, as a multiple. Enough to spend into
     * the hull and stop inside it, not enough to bore out the far side. Priced off the target block's
     * own cost, never hard-coded.
     */
    private static final double BUDGET_IN_BLOCKS = 1.5D;
    /** The fixture spans about twenty blocks; a point further than this from the hull is not on it. */
    private static final double ON_THE_HULL = 64.0D;

    /** A second site: two ship scenarios on one shared server must not build over each other. */
    private static final int MOVE_SRC_X = 6700, MOVE_SRC_Z = 6400;
    private static final int MOVE_FAR_X = 6700, MOVE_FAR_Y = 150, MOVE_FAR_Z = 8800;
    /** Where the ship goes WHILE the round is inside it — far enough that no tolerance can absorb it. */
    private static final int MOVE_AGAIN_X = 6700, MOVE_AGAIN_Y = 150, MOVE_AGAIN_Z = 9100;

    private final Events events =
            new Events(this::exec, ticks -> GameTicks.advance(client(), GameTicks.server(), ticks), evictionReports());

    /** Each config key a scenario set, with the value it held before; emptied by {@link #restoreConfig}. */
    private final java.util.Map<String, Double> configBefore = new java.util.LinkedHashMap<>();

    /** A craft left behind goes on ticking in the world the next scenario runs in. */
    @Before
    public void disposeOfEarlierCraft() throws Exception {
        System.out.println("[reset] craft cleared: " + ShipReadiness.clearCraftFrom(this::exec, 0));
    }

    /** The probe's own contract for `config set`: a test puts back what it changed. */
    @org.junit.After
    public void restoreConfig() throws Exception {
        for (java.util.Map.Entry<String, Double> key : configBefore.entrySet()) {
            ask("stellurgytest config set " + key.getKey() + " " + key.getValue()).requireOk("restore " + key.getKey());
        }
        configBefore.clear();
    }

    /** Set a numeric config key for this scenario, remembering what it held the first time. */
    private void config(String key, double value) throws Exception {
        if (!configBefore.containsKey(key)) {
            configBefore.put(key, ask("stellurgytest config get " + key).requireOk("read " + key).number("value"));
        }
        ask("stellurgytest config set " + key + " " + value).requireOk("set " + key);
    }

    /**
     * red-witnessed: with {@code StructureCrossing#firstAlong} at {@code for (Map.Entry<String, AxisAlignedBB> ship : ships.entrySet())}'s loop over candidate ships skipped (only
     * the world frame traversed), this fails with "the shot is still in the air with its budget
     * untouched after a step that crossed the hull ... {...present:true,energy:60,hull:null...}". The
     * block-damaged and crossing-point verdicts were not separately witnessed. 2026-09-30.
     *
     * <p>red-witnessed: with {@code StructureCrossing#visit} at {@code worldPoint = new Vec3d(w[0], w[1], w[2]);} leaving a ship-frame crossing point in
     * the ship's frame instead of mapping it back out, this fails at "after crossing the hull the shot
     * is at (1.9200001E7,126.99770000005135,51200.0), 1.919364683222029E7 blocks off the vertical line it
     * was fired down through" (2026-09-30). The block-damaged verdict before it passed on that run.</p>
     */
    @Test
    public void aShotFindsAMovedShipsHullInItsOwnFrameAndDamagesTheRightBlock() throws Exception {
        ask("stellurgytest damage clear-impacts").requireOk("forget earlier impacts");
        ask("stellurgytest shot clear 0").requireOk("clear the air");

        WarShip ship = WarShip.build(events, this::exec, FixtureSite.openAir(0, SRC_X, SRC_Z), null,
                "the craft the round is fired at");
        ship.parkAt(FAR_X, FAR_Y, FAR_Z, ON_THE_HULL);

        // A block of this ship whose subspace address we know: its pilot seat.
        int[] sub = ship.seat();
        double[] world = ship.toWorld(sub[0], sub[1], sub[2]);

        // ARRANGEMENT CONTROL — the mapped point is actually on the ship as the world sees it.
        Reply moved = ship.info();
        double offHull = Math.sqrt(sq(world[0] - moved.number("posX")) + sq(world[1] - moved.number("posY"))
                + sq(world[2] - moved.number("posZ")));
        requireArranged("the seat's mapped world point is " + offHull + " blocks from the ship's own"
                + " world position: the fixture, not the substrate, is what this run would be measuring. "
                + moved, offHull < ON_THE_HULL);

        // CONTROL 1 — the WORLD frame is air along the line of fire.
        for (int drop = -4; drop <= 4; drop++) {
            Reply worldBlock = stage((int) Math.floor(world[0]), (int) Math.floor(world[1]) + drop,
                    (int) Math.floor(world[2]));
            requireArranged("the world frame holds a block at the target, " + drop + " blocks off the"
                    + " seat: " + worldBlock + ". A shot stopping here would prove nothing about ship"
                    + " frames", "minecraft:air".equals(worldBlock.text("block")));
        }

        // CONTROL 2 — the subject is undamaged at its SUBSPACE address, where the ship's blocks are.
        Reply before = stage(sub[0], sub[1], sub[2]);
        requireArranged("the seat's subspace address holds no block, so nothing below is about the"
                + " ship: " + before, !"minecraft:air".equals(before.text("block")));
        requireArranged("the subject block is already damaged before the shot: " + before,
                before.integer("stage") == 0);

        // Fire straight down through the seat's WORLD position, from clear air above it.
        int energy = (int) Math.round(before.integer("stageCost") * Math.max(1, before.integer("maxStage"))
                * BUDGET_IN_BLOCKS);
        requireArranged("the target block has no price, so the round's budget would be meaningless: "
                + before, energy > 0);
        long id = fire(world[0] + " " + (world[1] + 30.0D) + " " + world[2] + " 0 " + (-SPEED) + " 0 " + energy + " 40");
        ask("stellurgytest shield tick 0").requireOk("step the substrate");

        Reply read = ask("stellurgytest shot read 0 " + id).requireOk("read the round");
        long left = read.bool("present") ? Reply.of("the round", read.object("shot")).longInteger("energy") : -1L;
        assertTrue("the shot is still in the air with its budget untouched after a step that crossed the"
                + " hull — a segment computed in the world frame finds nothing where a ship visibly is,"
                + " which is exactly what this substrate maps around: " + read,
                !read.bool("present") || left < energy);

        // The damage landed on the SHIP's own block, at its subspace address.
        Reply hull = stage(sub[0], sub[1], sub[2]);
        assertTrue("the shot crossed the hull but the ship's own block is untouched at its subspace"
                + " address (before=" + before + " after=" + hull + "): the impact was handed over in"
                + " the wrong frame, or to the wrong target",
                hull.integer("stage") > 0 || hull.bool("wasDestroyed") || "minecraft:air".equals(hull.text("block")));

        // And the crossing was expressed in WORLD coordinates: a round that ended in the hull ended
        // at a world point, which the registry remembers; one still flying is read where it is.
        double[] at = read.bool("present")
                ? new double[]{Reply.of("the round", read.object("shot")).number("x"),
                        Reply.of("the round", read.object("shot")).number("y"),
                        Reply.of("the round", read.object("shot")).number("z")}
                : new double[]{read.number("endX"), read.number("endY"), read.number("endZ")};
        // Fired straight DOWN through the seat's world point, so wherever it is now lies on that
        // vertical line: horizontally within a block of it by construction. A crossing point left in
        // the ship's frame sits at the yard's address instead, the divergence away.
        double sideways = Math.sqrt(sq(at[0] - world[0]) + sq(at[2] - world[2]));
        assertTrue("after crossing the hull the shot is at (" + at[0] + "," + at[1] + "," + at[2] + "), "
                + sideways + " blocks off the vertical line it was fired down through: the crossing point"
                + " was never mapped out of the ship's frame: " + read, sideways < 1.0D);
    }

    /**
     * red-witnessed: with {@code ShotFrame#worldPosition} at {@code double[] w = VSIntegration.toWorldFrameFor(world, shot.getHullId(), local.x, local.y, local.z);} answering, for a lodged round, the world point it
     * was embedded at instead of mapping its plate coordinates through the hull's CURRENT pose, this
     * fails with "the round left the hull when the ship moved: {...z:8800.0...hull:null}" — the round
     * left behind where the ship used to be. 2026-09-30. (Witnessed on the earlier, single-read form
     * of this method, whose message that is; not re-run against the two-read form below.)
     *
     * <p>red-witnessed: with {@code ShotFrame.worldPosition} ({@code ShotFrame#worldPosition} at {@code double[] w = VSIntegration.toWorldFrameFor(world, shot.getHullId(), local.x, local.y, local.z);}) mapping a lodged
     * round's Z to where the ship used to stand (z 8800) instead of through the hull's current pose,
     * this fails at "the round left the hull once the ship moved, in the pass the teleport answered in:
     * {...z:8800.0...}" (2026-09-30). The hull-frame and position verdicts after it were not reached by
     * that inversion.</p>
     *
     * <p>What this test used to be red on, 2026-09-30, and why it is not any more: the round flew at
     * 0.2 a tick, came out of the one-block seat's underside inside the read window, and the damage
     * walk then reported that it had got nowhere — see
     * {@code ShotBoresOverTimeE2ETest#aRoundTooPoorForAStageComesOutOfTheFarSide}, which pins that
     * defect on its own. The teleport was only ever coincident with the exit.</p>
     */
    @Test
    public void aRoundDrillingAHullGoesWhereTheShipGoes() throws Exception {
        ask("stellurgytest shot clear 0").requireOk("clear the air");

        WarShip ship = WarShip.build(events, this::exec, FixtureSite.openAir(0, MOVE_SRC_X, MOVE_SRC_Z), null,
                "the craft the round lodges in");
        ship.parkAt(MOVE_FAR_X, MOVE_FAR_Y, MOVE_FAR_Z, ON_THE_HULL);
        int[] sub = ship.seat();
        double[] world = ship.toWorld(sub[0], sub[1], sub[2]);

        // A round too poor to buy even one stage: a stage is bought whole or not at all, so it pays
        // nothing, loses no speed, and crosses the seat block without damaging it — spending
        // 1 / speed ticks INSIDE it. That is deliberate: this test is about WHERE a round inside a
        // hull is, and the plate must stay intact for the question to have one answer.
        long stageCost = stage(sub[0], sub[1], sub[2]).longInteger("stageCost");
        requireArranged("the plate has no price, so no budget can be chosen against it", stageCost > 1);
        int energy = (int) (stageCost / 2);

        // The speed is bounded on both sides by production. Above the penetration speed floor, or the
        // round is treated as come to rest the moment it enters and ENDS instead of lodging. And slow
        // enough that it is still inside the one-block seat — 1 / speed ticks of it — however many
        // world ticks pass between the reads below: the shared server ticks on its own while the
        // probe calls travel, so the window is not two ticks but two plus the round trips. Measured
        // 2026-09-30: at 0.2 a tick the round came out of the seat's underside inside the window, and
        // at 0.1 it had aged ten ticks between the first read and the second. So the floor is lowered
        // for this scenario (restored after) and the round flies at twice it: a hundred ticks inside.
        // The subject — whether a hull carries what is inside it — does not depend on the floor.
        double floor = 0.005D;
        config("shotPenetrationSpeedFloor", floor);
        double speed = 2.0D * floor;
        double approach = 0.15D;
        // `world` is the seat block's lower corner, so its top face is one block up: start just above it.
        long id = fire(world[0] + " " + (world[1] + 1.0D + approach) + " " + world[2] + " 0 " + (-speed) + " 0 "
                + energy + " 400");

        Reply lodged = null;
        // STIMULUS: each iteration is one probe-driven step of the substrate, and the read after it
        // is of the step just taken; the loop ends on the round's own report that a hull holds it.
        // Bounded by the approach itself: its length at this speed, and a few steps more.
        int approachSteps = (int) Math.ceil(approach / speed) + 5;
        for (int tick = 0; tick < approachSteps && lodged == null; tick++) {
            ask("stellurgytest shield tick 0").requireOk("step the substrate");
            Reply read = ask("stellurgytest shot read 0 " + id).requireOk("read the round");
            // the producer always writes `present` on an ok `shot read`, whether the round is flying
            // or has ended, so refusing on a missing one is the right failure.
            if (!read.bool("present")) {
                break;
            }
            Reply shot = Reply.of("the round", read.object("shot"));
            // absence is the answer: a round in open air carries hull:null, which is "not lodged yet".
            if (shot.has("hull")) {
                lodged = shot;
            }
        }
        requireArranged("the round never came to be inside the hull — with nothing lodged there is no"
                + " frame question to ask: " + ask("stellurgytest shot read 0 " + id), lodged != null);

        double hullX = lodged.number("hullX");
        double hullY = lodged.number("hullY");
        double hullZ = lodged.number("hullZ");
        double beforeX = lodged.number("x");
        double beforeZ = lodged.number("z");

        // The ship manoeuvres with the round still in it.
        ask("stellurgytest vs teleport-ship-by-id 0 " + ship.vsShip + " " + MOVE_AGAIN_X + " " + MOVE_AGAIN_Y
                + " " + MOVE_AGAIN_Z).requireOk("move the ship with the round in it");

        // WINDOW: "the round goes where the ship goes" is a standing state, and the move is not
        // finished when the teleport answers — the physics adopts the new pose on its next pass
        // (VSBridge.teleportShip says so of itself), and the substrate steps the round on the
        // world's own ticks. So two reads: one in the pass the teleport answered in, and one after
        // two world ticks, by which the adoption has run and at least one substrate step has run
        // after it, whichever of the two a tick runs first. The claim is made of BOTH reads; a round
        // carried at the first and dropped at the second is a round the ship does not keep.
        // Where the seat block now stands in the world: the premise of every read below is that the
        // round is still somewhere inside it, and a round that has honestly come out of the underside
        // is an arrangement that outran its window, not a hull that let go.
        double seatBottomY = ship.toWorld(sub[0], sub[1], sub[2])[1];
        Reply sameTick = lodgedAfterTheMove(id, "in the pass the teleport answered in", seatBottomY);
        // WINDOW: the ticks between the two reads described above; every verdict below names both.
        GameTicks.advance(client(), GameTicks.server(), 2);
        Reply stepped = lodgedAfterTheMove(id, "after the world stepped it past the move", seatBottomY);

        // ACROSS the bore the plate holds it exactly: a ship's translation must not show up as the
        // round sliding sideways inside its own hole. ALONG the bore the world's own ticks may have
        // stepped it a little further in, and not by the hundreds of blocks the ship travelled.
        //
        // Every bound below is DEFINITIONAL rather than tuned: the two hypotheses — carried with the
        // hull, or left where the hull used to be — are the ship's own travel apart, so each verdict
        // line sits halfway between them.
        double shipMovedZ = MOVE_AGAIN_Z - MOVE_FAR_Z;
        double halfway = Math.abs(shipMovedZ) / 2.0D;
        for (Reply after : new Reply[]{sameTick, stepped}) {
            String both = " | same pass: " + sameTick + " | stepped: " + stepped;
            assertEquals("the round moved sideways WITHIN the plate because the ship moved — the hull's"
                    + " own motion must not reach its frame at all" + both, hullX, after.number("hullX"), 1.0E-6D);
            assertEquals("the round moved sideways WITHIN the plate along Z" + both, hullZ,
                    after.number("hullZ"), 1.0E-6D);
            double boredFurther = hullY - after.number("hullY");
            assertTrue("along the bore the round went " + boredFurther + " blocks while the ship travelled "
                    + Math.abs(shipMovedZ) + ": it is being carried, not drilling" + both,
                    boredFurther >= 0.0D && boredFurther < halfway);
            double movedZ = after.number("z") - beforeZ;
            assertEquals("the round stayed where the ship USED to be: a body inside a hull that"
                    + " manoeuvres travels with it, and one stored in world coordinates does not" + both,
                    shipMovedZ, movedZ, halfway);
            assertEquals("the round drifted across the manoeuvre on an axis the ship did not move along"
                    + both, 0.0D, after.number("x") - beforeX, halfway);
        }

        ask("stellurgytest shot clear 0").requireOk("clear the air");
    }

    /**
     * The round {@code id}, required to be still in the air and still held by a hull — provided it is
     * still within the seat block, whose underside stands at {@code seatBottomY} in the world.
     */
    private Reply lodgedAfterTheMove(long id, String when, double seatBottomY) throws Exception {
        Reply read = ask("stellurgytest shot read 0 " + id).requireOk("read the round");
        assertTrue("the round ended once the ship moved, " + when + ": " + read, read.bool("present"));
        Reply shot = Reply.of("the round", read.object("shot"));
        requireArranged("the round came out of the seat's underside (y " + shot.number("y") + " below "
                + seatBottomY + ") " + when + ": it outran the window, so this read is not about the hull",
                shot.number("y") >= seatBottomY);
        assertTrue("the round left the hull once the ship moved, " + when + ": " + shot, shot.has("hull"));
        return shot;
    }

    private long fire(String spec) throws Exception {
        Reply fired = ask("stellurgytest shot fire 0 " + spec).requireOk("fire a round");
        long id = fired.longInteger("id");
        requireArranged("the launch was refused, so nothing else here means anything: " + fired, id > 0);
        return id;
    }

    private Reply stage(int x, int y, int z) throws Exception {
        return ask("stellurgytest damage stage 0 " + x + " " + y + " " + z).requireOk("read a stage");
    }

    private static double sq(double v) {
        return v * v;
    }


    private Reply ask(String command) throws Exception {
        return Reply.of(command, exec(command));
    }
}
