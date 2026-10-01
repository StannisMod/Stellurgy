package dev.stannismod.stellurgy.test.server;

import org.junit.Test;

import java.util.List;

import dev.stannismod.stellurgy.projectile.ContactResolver;
import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.Weapons;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * What a hull does about WHAT hit it and HOW, rather than only about how much.
 *
 * <p>Until now a block resisted with one number and every arrival paid it: a beam dug like a slug, and
 * a round skimming a steel plate at five degrees dug in exactly as one arriving square-on. Three
 * claims here, and each is an ordering rather than a quantity, because every number behind them is
 * balance and will move.</p>
 *
 * <ul>
 *   <li><b>Two columns.</b> Being boiled away costs far more per joule than being pushed through, so
 *       a beam buys much less depth than a slug carrying the same energy. That is not a nerf: it is
 *       what makes a laser buy precision and having nothing to reload instead of digging power.</li>
 *   <li><b>A price a faint beam cannot meet.</b> A stage is bought whole or not at all, so a beam
 *       carrying a slug's price for a block does not scratch it — and, keeping its energy rather
 *       than banking it, never will however long it is held.</li>
 *   <li><b>A graze skips off METAL.</b> And off metal only, so a player meets bouncing rounds where a
 *       player expects them and never off a plank wall.</li>
 * </ul>
 *
 * <p>Every "it did not dig" below is paired with the record that it ARRIVED: the contact seam's own
 * decision at the block ({@code contact_ricochet_decided}, asked of every massive or massless body
 * meeting a block that does not answer for itself). An untouched wall is otherwise also what a round
 * that never got there leaves.</p>
 */
public class ArmourAnswersByKindAndAngleE2ETest extends AbstractSharedServerTest {

    private static final int DIM = 0;
    /** A site of this class's own, clear of the other shot scenarios on this shared server. */
    private static final int Y = 70, X = 1700;
    /**
     * Every scenario gets its own LANE, and they are separated across the line of fire rather than
     * along it.
     *
     * <p>Down one lane they were not separate at all: a round rich enough to be interesting punches
     * through its own ten-block wall with budget in hand and flies on into the next scenario's wall
     * twenty blocks downrange — so "how deep did the beam get" was measured on a hole the SLUG made.
     * It passed for as long as the budgets were too small to leave the first wall, which is the worst
     * way for an arrangement to be wrong: silently, until the numbers get interesting.</p>
     */
    private static final int SLUG_Z = 1010, BEAM_Z = 1030, FAINT_Z = 1050, STRONG_Z = 1070;
    private static final int STEEL_Z = 1090, WOOD_Z = 1110;
    private static final int SOLID_Z = 1130, THIN_Z = 1150;

    private static final int WALL_DEPTH = 10;
    private static final double BORE_SPEED = 0.45D;
    /** The reference body every lane here fires; its face is what a beam's intensity is spread over. */
    private static final double RADIUS = 0.25D;

    private final Events events =
            new Events(command -> exec(command), ticks -> GameTicks.advance(client(), GameTicks.server(), ticks));

    /**
     * The same energy, the same body, the same wall — only the KIND differs, and the depths must not
     * be the same. Priced off the wall rather than asserted as a number: what is claimed is that
     * boiling material away costs more per joule than pushing through it, not by how much.
     *
     * <p>red-witnessed: with {@code StructureDamageEngine.stageCost} ({@code StructureDamageEngine#stageCost} at {@code double resistance = WeightEngine.INSTANCE.getResistance(world, pos, kind);})
     * pricing every kind from the kinetic column, this fails at "a beam dug as deep as a slug carrying the
     * same energy (beam=10 slug=10, budget=20000)" (2026-09-29).</p>
     */
    @Test
    public void aBeamBuysFarLessDepthThanASlugOfTheSameEnergy() throws Exception {
        prepare(SLUG_Z);
        prepare(BEAM_Z);
        buildWall(SLUG_Z);
        buildWall(BEAM_Z);

        // Rich enough that the beam is over the intensity threshold and genuinely drilling: below it
        // this test would go green for the threshold's reasons and say nothing about the columns.
        int budget = budgetForBlocks(SLUG_Z, 20.0D);
        long fired = events.markInstrumented();
        long slug = fire(SLUG_Z, budget, "KINETIC");
        long beam = fire(BEAM_Z, budget, "BEAM");
        Weapons.awaitShotEnded(events, fired, slug, "the slug never ended");
        Weapons.awaitShotEnded(events, fired, beam, "the beam never ended");

        int slugDepth = boreDepth(SLUG_Z);
        int beamDepth = boreDepth(BEAM_Z);
        assertTrue("the slug did not get into the wall at all, so the comparison is between two"
                + " zeroes. budget=" + budget + " wall=" + stageAt(X, SLUG_Z), slugDepth > 0);
        assertTrue("the beam removed nothing at a budget chosen to be far over the intensity"
                + " threshold, so this comparison is about the threshold and not the columns: budget="
                + budget, beamDepth > 0);
        assertTrue("a beam dug as deep as a slug carrying the same energy (beam=" + beamDepth
                + " slug=" + slugDepth + ", budget=" + budget + "): then the two channels are one"
                + " column and a laser is simply a better gun", beamDepth < slugDepth);
    }

    /**
     * A beam carrying exactly what would destroy a block outright as a slug does not scratch it.
     *
     * <p>This is the two-column claim at its sharpest, and it is also where the intensity threshold
     * turns out to live already: a stage is bought whole or not at all, so a beam that cannot afford
     * one buys nothing this tick and — carrying its energy onward rather than banking it — nothing on
     * any later tick either.</p>
     *
     * <p>red-witnessed: with the kinetic column used for every kind ({@code StructureDamageEngine#stageCost} at {@code double resistance = WeightEngine.INSTANCE.getResistance(world, pos, kind);})
     * AND the intensity gate disabled ({@code StructureDamageEngine#tooFaintToDrill} at {@code if (!WeightEngine.isThermalChannel(kind))}) — either alone leaves it
     * green, because the faint beam is refused by both — this fails at "a beam removed material with a
     * slug's price for it" (2026-09-29).</p>
     */
    @Test
    public void aBeamCarryingABlocksWorthOfEnergyDoesNotScratchIt() throws Exception {
        prepare(FAINT_Z);
        buildWall(FAINT_Z);

        // Exactly one block's worth at the mechanical column — a slug with this much destroys it.
        int budget = budgetForBlocks(FAINT_Z, 1.0D);
        long fired = events.markInstrumented();
        long id = fire(FAINT_Z, budget, "BEAM");
        Weapons.awaitShotEnded(events, fired, id, "the beam never ended");

        assertArrived(fired, FAINT_Z, "BEAM");
        Reply face = stageAt(X, FAINT_Z);
        assertTrue("a beam removed material with a slug's price for it (" + face + "): then boiling a"
                + " block away costs what pushing through it costs, the two channels are one column,"
                + " and a laser is simply a better gun",
                face.integer("stage") == 0 && !face.bool("wasDestroyed"));
    }

    /**
     * The intensity threshold, as the one thing the price does not do.
     *
     * <p>Two beams into identical walls, the same body, the same wall, differing only in how much
     * energy is behind that face — sized from the CONFIGURED threshold and the body's own face, so
     * one sits below it and one above it whatever the threshold is tuned to. Both can afford stages
     * on price; the only thing separating them is intensity. What makes this worth a test rather
     * than a tuning note is where the weak beam's energy goes: it is absorbed by the plate. Without
     * the threshold that beam does not warm the hull, it passes clean through it carrying everything
     * it arrived with.</p>
     *
     * <p>red-witnessed: with {@code StructureDamageEngine.tooFaintToDrill} ({@code StructureDamageEngine#tooFaintToDrill} at {@code if (!WeightEngine.isThermalChannel(kind))})
     * answering false for every kind, this fails at "a beam below the intensity threshold removed material
     * anyway" (2026-09-29).</p>
     */
    @Test
    public void aBeamBelowTheIntensityThresholdRemovesNothingWhileAStrongerOneDigs() throws Exception {
        prepare(FAINT_Z);
        prepare(STRONG_Z);
        buildWall(FAINT_Z);
        buildWall(STRONG_Z);

        double threshold = ask("stellurgytest config get beamAblationIntensityThreshold")
                .requireOk("read the ablation threshold").number("value");
        assertTrue("the ablation threshold is switched off, so nothing here straddles it: " + threshold,
                threshold > 0.0D);
        int atThreshold = (int) Math.round(threshold * ContactResolver.areaOf(RADIUS));
        int faintEnergy = (int) Math.round(atThreshold * 0.8D);
        int strongEnergy = (int) Math.round(atThreshold * 1.4D);
        int beamStage = stageAt(X, FAINT_Z).integer("stageCostBeam");
        assertTrue("the faint beam cannot even afford a stage on price (" + faintEnergy + " vs a stage"
                + " at " + beamStage + "), so its failing to dig would be the price's doing and not the"
                + " threshold's", faintEnergy >= beamStage);

        long fired = events.markInstrumented();
        long faint = fire(FAINT_Z, faintEnergy, "BEAM");
        long strong = fire(STRONG_Z, strongEnergy, "BEAM");
        Weapons.awaitShotEnded(events, fired, faint, "the faint beam never ended");
        Weapons.awaitShotEnded(events, fired, strong, "the strong beam never ended");

        assertArrived(fired, FAINT_Z, "BEAM");
        Reply faintFace = stageAt(X, FAINT_Z);
        assertTrue("a beam below the intensity threshold removed material anyway (" + faintFace + "):"
                + " then the threshold is not a gate and a faint beam either digs or, worse, passes"
                + " clean through the plate with all it arrived with",
                faintFace.integer("stage") == 0 && !faintFace.bool("wasDestroyed"));
        Reply strongFace = stageAt(X, STRONG_Z);
        assertTrue("the stronger beam removed nothing either (" + strongFace + "): then this run"
                + " compared two refusals and the threshold is not where it was thought to be",
                strongFace.integer("stage") > 0 || strongFace.bool("wasDestroyed"));
    }

    /**
     * A block is priced by how much of its voxel it actually FILLS.
     *
     * <p>The law is an energy per unit of volume removed, and a voxel is a cubic metre only when
     * something fills it. Panes and solid glass are the same material — the same row of the same
     * table — so the only thing separating these two walls is that one of them is mostly air.</p>
     *
     * <p>red-witnessed: with {@code StructureDamageEngine#occupancyOf} at {@code if (world == null || pos == null)}'s {@code occupancyOf} answering 1.0
     * for every block, this fails with "a wall of panes cost the same to bore as a wall of solid glass
     * (panes=3 solid=3)". 2026-09-30.</p>
     */
    @Test
    public void aRoundGoesFurtherThroughWhatIsMostlyAir() throws Exception {
        prepare(SOLID_Z);
        prepare(THIN_Z);
        buildWallOf(SOLID_Z, "minecraft:glass");
        buildWallOf(THIN_Z, "minecraft:glass_pane");

        int budget = budgetForBlocks(SOLID_Z, 3.0D);
        long fired = events.markInstrumented();
        long throughSolid = fire(SOLID_Z, budget, "KINETIC");
        long throughPanes = fire(THIN_Z, budget, "KINETIC");
        Weapons.awaitShotEnded(events, fired, throughSolid, "the round into solid glass never ended");
        Weapons.awaitShotEnded(events, fired, throughPanes, "the round into the panes never ended");

        int solidDepth = boreDepth(SOLID_Z);
        int paneDepth = boreDepth(THIN_Z);
        assertTrue("the round did not get into the solid wall at all, so this compares two zeroes",
                solidDepth > 0);
        assertTrue("a wall of panes cost the same to bore as a wall of solid glass (panes=" + paneDepth
                + " solid=" + solidDepth + "): then a block is priced as a full cubic metre of material"
                + " however little of its voxel it fills, and the law stops being about volume",
                paneDepth > solidDepth);
    }

    /**
     * A graze skips off steel and digs into wood. Two plates, one angle, one round: the material is
     * the only difference, which is what makes this about the narrowing rather than about the angle.
     *
     * <p>The evidence is the contact seam's own ricochet decision at each plate — not the plate being
     * unmarked, which a round that missed it entirely would also leave.</p>
     *
     * <p>red-witnessed: with the metal test in {@code ContactResolver.ricochet} ({@code ContactResolver#ricochet} at {@code if (!WeightEngine.INSTANCE.isMetal(world, contact.getPos()))})
     * disabled, this fails at "the same round at the same angle skipped off WOOD" (2026-09-29).</p>
     *
     * <p>red-witnessed, one inversion per verdict, 2026-09-30: with {@code ContactResolver.ricochet}'s
     * deflection ({@code ContactResolver.java:142}) answering null, this fails at "a round grazing a
     * steel plate never skipped ... {...incidence:82.4...skipped:false}"; with the skip at
     * {@code ContactResolver.java:106-110} also spending the round into the plate through the default
     * law, at "the steel plate took damage from a round that skipped off it: [{...pos:1703,70,1090,
     * from:0,to:4...}]".</p>
     */
    @Test
    public void aGrazingRoundSkipsOffSteelAndDigsIntoWood() throws Exception {
        prepare(STEEL_Z);
        prepare(WOOD_Z);
        buildPlate(STEEL_Z, "minecraft:iron_block");
        buildPlate(WOOD_Z, "minecraft:planks");

        int budget = budgetForBlocks(WOOD_Z, 4.0D);
        long fired = events.markInstrumented();
        long offSteel = grazeAt(STEEL_Z, budget);
        String steel = firstRicochetDecision(fired, STEEL_Z);
        assertEquals("a round grazing a steel plate never skipped — it dug in, and metal is the one"
                + " material a glancing hit is supposed to skip off: " + steel, "true",
                Events.text(steel, "skipped"));
        Weapons.awaitShotEnded(events, fired, offSteel, "the round off the steel never ended");

        long intoWood = grazeAt(WOOD_Z, budget);
        String wood = firstRicochetDecision(fired, WOOD_Z);
        assertEquals("the same round at the same angle skipped off WOOD: a plank wall must never bounce"
                + " a shell, or ricochet stops being where a player expects it: " + wood, "false",
                Events.text(wood, "skipped"));
        Weapons.awaitShotEnded(events, fired, intoWood, "the round into the wood never ended");
        assertTrue("the round that dug into the wood marked no block of the plate — then it did not"
                + " dig, and this run compared nothing", !stagesOnPlate(fired, WOOD_Z).isEmpty());
        assertTrue("the steel plate took damage from a round that skipped off it: "
                + stagesOnPlate(fired, STEEL_Z), stagesOnPlate(fired, STEEL_Z).isEmpty());
    }

    // ---- driving

    /** Straight down the X axis into the face of the wall: square-on, so nothing can ricochet. */
    private long fire(int lane, int energy, String kind) throws Exception {
        Reply fired = ask("stellurgytest shot fire " + DIM + " " + (X - 3.0D) + " " + (Y + 0.5D) + " "
                + (lane + 0.5D) + " " + BORE_SPEED + " 0 0 " + energy + " " + Weapons.ROUND_LIFETIME_TICKS
                + " " + kind + " " + RADIUS
                + " 1.0").requireOk("fire down lane " + lane);
        long id = fired.longInteger("id");
        assertTrue("the substrate refused the " + kind + " shot down lane " + lane + ": " + fired, id >= 0);
        return id;
    }

    /**
     * A round arriving at a very shallow angle to the plate's top face: mostly along it, barely into
     * it. The plate is one block thick and the round comes in from above and beside.
     */
    private long grazeAt(int lane, int energy) throws Exception {
        Reply fired = ask("stellurgytest shot fire " + DIM + " " + (X - 1.0D) + " " + (Y + 1.6D) + " "
                + (lane + 0.5D) + " 1.5 -0.2 0 " + energy + " " + Weapons.ROUND_LIFETIME_TICKS
                + " KINETIC " + RADIUS + " 1.0")
                .requireOk("graze lane " + lane);
        long id = fired.longInteger("id");
        assertTrue("the substrate refused the graze down lane " + lane + ": " + fired, id >= 0);
        return id;
    }

    private void buildWall(int lane) throws Exception {
        buildWallOf(lane, "minecraft:stone");
    }

    private void buildWallOf(int lane, String block) throws Exception {
        ask("stellurgytest fill " + DIM + " " + X + " " + Y + " " + lane + " " + (X + WALL_DEPTH - 1) + " "
                + Y + " " + lane + " " + block).requireOk("build the wall");
    }

    /** One block thick and long enough to be grazed along, with clear air above it. */
    private void buildPlate(int lane, String block) throws Exception {
        ask("stellurgytest fill " + DIM + " " + X + " " + Y + " " + (lane - 1) + " " + (X + 8) + " " + Y
                + " " + (lane + 1) + " " + block).requireOk("build the plate");
    }

    private void prepare(int lane) throws Exception {
        // Scenario isolation, and this class went without it for four scenarios: a round that punches
        // through its own wall keeps flying for the rest of its lifetime, and the next scenario builds
        // its arrangement while somebody else's round is still in the air.
        ask("stellurgytest shot clear " + DIM).requireOk("clear the air");
        // HELD, not merely warmed. There is no player on this server, so a warmed chunk unloads again
        // on its own — and a swept segment SKIPS a voxel whose chunk is not loaded rather than calling
        // it solid or empty, because nobody looked.
        for (int cx = (X - 16) >> 4; cx <= (X + 32) >> 4; cx++) {
            ask("stellurgytest chunk forceload " + DIM + " " + cx + " " + (lane >> 4)).requireOk("hold a chunk");
        }
        ask("stellurgytest chunk warmup " + DIM + " " + ((X - 16) >> 4) + " " + ((lane - 16) >> 4) + " "
                + ((X + 60) >> 4) + " " + ((lane + 16) >> 4)).requireOk("warm the lane's chunks");
        // Cleared far past the wall along the line of fire: a round that punches through must fly out
        // into empty air rather than into the next thing this class built.
        ask("stellurgytest fill " + DIM + " " + (X - 8) + " " + (Y - 2) + " " + (lane - 3) + " " + (X + 60)
                + " " + (Y + 6) + " " + (lane + 3) + " minecraft:air").requireOk("clear the lane");
    }

    // ---- reading

    /** The contact seam decided, at the lane's first wall block, about a body of this kind. */
    private void assertArrived(long mark, int lane, String kind) throws Exception {
        String decided = events.since(mark, "contact_ricochet_decided");
        Events.assertInstrumentRan(decided, "contact_events", "the " + kind + " reached the wall");
        assertTrue("the " + kind + " never met the wall's face, so its being unmarked proves nothing: "
                + decided, !Events.recordsWhereAll(decided, "pos", Weapons.at(X, Y, lane), "kind", kind)
                .isEmpty());
    }

    /** The first ricochet decision taken anywhere on the plate across {@code lane}. */
    private String firstRicochetDecision(long mark, int lane) throws Exception {
        String reply = events.awaitMatching(mark, "contact_ricochet_decided",
                one -> !onPlate(one, lane).isEmpty(), "on the plate at lane " + lane,
                "the round never met the plate at lane " + lane + ", so nothing was decided about a"
                        + " graze", Weapons.SUBJECT_TICKS);
        return onPlate(reply, lane).get(0);
    }

    private static List<String> onPlate(String reply, int lane) {
        List<String> here = new java.util.ArrayList<>();
        for (String record : Events.records(reply)) {
            String[] at = String.valueOf(Events.text(record, "pos")).split(",");
            int x = Integer.parseInt(at[0]), y = Integer.parseInt(at[1]), z = Integer.parseInt(at[2]);
            if (x >= X && x <= X + 8 && y == Y && Math.abs(z - lane) <= 1) {
                here.add(record);
            }
        }
        return here;
    }

    /** Every stage write on the plate across {@code lane} since {@code mark}. */
    private List<String> stagesOnPlate(long mark, int lane) throws Exception {
        String staged = events.since(mark, "block_stage_set");
        Events.assertInstrumentRan(staged, "damage_stage_events", "the plate at lane " + lane + " was staged");
        return onPlate(staged, lane);
    }

    private int boreDepth(int lane) throws Exception {
        int depth = 0;
        for (int i = 0; i < WALL_DEPTH; i++) {
            Reply stage = stageAt(X + i, lane);
            // the producer always writes `stage`, `wasDestroyed` and `block` on an ok `damage stage`
            // reply (stageAt requires ok), so refusing on a missing one is the right failure.
            if (stage.integer("stage") > 0 || stage.bool("wasDestroyed")
                    || "minecraft:air".equals(stage.text("block"))) {
                depth = i + 1;
            }
        }
        return depth;
    }

    private int budgetForBlocks(int lane, double blocks) throws Exception {
        Reply stage = stageAt(X, lane);
        int budget = (int) (stage.integer("stageCost") * Math.max(1, stage.integer("maxStage")) * blocks);
        assertTrue("the wall has no price, so no budget here means anything: " + stage, budget > 0);
        return budget;
    }

    private Reply stageAt(int x, int lane) throws Exception {
        return ask("stellurgytest damage stage " + DIM + " " + x + " " + Y + " " + lane).requireOk("read a stage");
    }

    private static String exec(String command) throws Exception {
        return String.join("\n", client().execute(command));
    }

    private static Reply ask(String command) throws Exception {
        return Reply.of(command, exec(command));
    }
}
