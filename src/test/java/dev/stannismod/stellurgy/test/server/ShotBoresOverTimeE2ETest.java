package dev.stannismod.stellurgy.test.server;

import org.junit.Test;

import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.Weapons;

import java.util.SortedMap;
import java.util.TreeMap;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A round does not finish its whole life in the tick it touches a hull.
 *
 * <p>Before this, meeting structure was terminal: one call resolved a bore up to sixty-four blocks
 * deep and the round ceased to exist at the surface. Now it <b>keeps being a round while it bores</b>
 * — it is still there next tick, deeper and worth less, until it either comes out the far side or
 * runs out inside. That is one claim and it is the one worth a server test, because nothing smaller
 * can exhibit it: it is a statement about what is true BETWEEN two ticks.</p>
 *
 * <p>The second claim is the only body ordering the penetration law makes on its own: at the same
 * energy, the narrower round goes deeper. Not a depth — an ordering, because the depth is balance.</p>
 *
 * <p>What happens between ticks is read off the round's own history and the wall's: the energy it
 * paid ({@code shot_energy_spent}), the stages it wrote ({@code block_stage_set}), and its ending
 * ({@code shot_ended}), each stamped with the tick it happened on.</p>
 */
public class ShotBoresOverTimeE2ETest extends AbstractSharedServerTest {

    private static final int DIM = 0;
    private static final int Y = 70;
    private static final int Z = 870;

    /**
     * A lifetime far longer than any wait here: a round that sails through its wall must still be in
     * the air when a wait on its ENDING gives up, so that wait fails on the right silence.
     */
    private static final int LONG_LIFETIME_TICKS = 1200;

    /** Slow on purpose: a round that crosses a block per tick cannot be caught in the middle of one. */
    private static final double BORE_SPEED = 0.45D;

    /** This class's reader of the server's ordered event log. */
    private final Events events =
            new Events(this::exec, ticks -> GameTicks.advance(client(), GameTicks.server(), ticks));

    /**
     * The claim is about what is true BETWEEN ticks, so it is read off the round's own history rather
     * than off samples of it: every tick in which the round paid for depth, and the tick it ended.
     * Before penetration-over-time the whole bore resolved inside ONE step and the round ended there;
     * that history has at most one paying tick, and it is the ending tick.
     *
     * <p>red-witnessed: with the still-inside branch of {@code ShotSubstrate.step}
     * ({@code ShotSubstrate#step} at {@code else if (!contact.leftTheStructure)}) made to end the round at the contact tick, this fails at "the
     * round paid for depth on fewer than two ticks before it ended (paying ticks [23], ended at 23)"
     * (2026-09-29).</p>
     *
     * <p>red-witnessed, one inversion per verdict, 2026-09-30: with both of {@code ShotSubstrate.step}'s
     * come-to-rest returns ({@code ShotSubstrate.java:283} and {@code :320}) answering EXPIRED, this
     * fails at "the round ended, but not by coming to rest in what it was drilling ... but
     * was:&lt;[EXPIRED]&gt;"; with {@code StructureDamageEngine.java:367} charging a stage 1/2.7 of its
     * price (the probe's quoted price unchanged, so the budget is still "3.5 blocks" by it), at "the
     * round reached the far side of a wall it could not afford: {...stage:2...block:minecraft:stone...}".</p>
     */
    @Test
    public void aRoundKeepsBoringAcrossTicksInsteadOfEndingAtTheSurface() throws Exception {
        int wallX = 1400;
        prepare(wallX);
        buildWall(wallX, 10);

        // Sized from what a block of this wall actually costs, read off the probe rather than guessed:
        // the price comes from the toughness table, which is balance and will move, and a hard-coded
        // budget silently becomes "sails clean through" the day it does.
        int budget = budgetForBlocks(wallX, 3.5D);
        long fired = events.markInstrumented();
        long id = fire(wallX - 3.5D, BORE_SPEED, budget, 0.25D, LONG_LIFETIME_TICKS);
        assertTrue("the substrate refused the shot, so there is nothing to observe: id=" + id, id >= 0);

        // A deadline for the link, not a verdict: 3.5 blocks of approach and 3.5 of wall at 0.45 a tick
        // is under twenty ticks. A round that sailed through the wall flies on to its 1200-tick
        // lifetime and fails here, which is the right failure for it.
        String ended = events.awaitRecordWithField(fired, "shot_ended", "shot", id,
                "the round never ended within the wall it cannot afford — it sailed through, or is"
                        + " still in the air", 400);
        assertEquals("the round ended, but not by coming to rest in what it was drilling: " + ended,
                "STRUCTURE_IMPACT", Events.text(ended, "reason"));
        long endTick = (long) Events.number(ended, "tick");

        String spent = events.since(fired, "shot_energy_spent");
        Events.assertInstrumentRan(spent, "shot_events", "the round paid for depth on these ticks");
        java.util.SortedSet<Long> payingTicks = new java.util.TreeSet<>();
        for (String record : Events.recordsWhere(spent, "shot", String.valueOf(id))) {
            payingTicks.add((long) Events.number(record, "tick"));
        }
        assertTrue("the round paid for depth on fewer than two ticks before it ended (paying ticks "
                + payingTicks + ", ended at " + endTick + "): then the bore resolved in one go and the"
                + " round merely lingered, which is the behaviour penetration-over-time replaces | "
                + spent, payingTicks.headSet(endTick).size() >= 2);

        // And it left a bore, not a crater: the front of the wall is damaged, and the far side of it
        // was never reached.
        Reply front = stageAt(wallX);
        assertTrue("the wall's front block is untouched, so the round never actually spent anything"
                + " into it: " + front, front.integer("stage") > 0 || front.bool("wasDestroyed"));
        Reply far = stageAt(wallX + 9);
        assertTrue("the round reached the far side of a wall it could not afford: " + far,
                far.integer("stage") == 0 && !far.bool("wasDestroyed"));
    }

    /**
     * The one body ordering the law makes by itself: energy buys depth against the material's
     * resistance ACROSS THE BODY'S FACE, so the same energy through a narrower round goes further.
     *
     * <p>red-witnessed: with {@code ContactResolver.areaOf} ({@code ContactResolver#areaOf} at {@code return r <= 0.0D ? ImpactRequest.REFERENCE_AREA : Math.PI * r * r;}) answering
     * the reference area for every radius, this fails at "the narrow round must bore deeper than the wide
     * one on the same energy (narrow=4 wide=4)" (2026-09-29).</p>
     */
    @Test
    public void aNarrowerRoundOutrunsAWiderOneOnTheSameEnergy() throws Exception {
        int narrowX = 1440, wideX = 1470;
        prepare(narrowX);
        prepare(wideX);
        buildWall(narrowX, 10);
        buildWall(wideX, 10);

        int budget = budgetForBlocks(narrowX, 3.5D);
        long fired = events.markInstrumented();
        // Given a lifetime inside the wait, so that whichever way a round ends — stopped by its wall,
        // or by expiry — the record comes before the deadline.
        long narrow = fire(narrowX - 3.5D, BORE_SPEED, budget, 0.25D, Weapons.ROUND_LIFETIME_TICKS);
        long wide = fire(wideX - 3.5D, BORE_SPEED, budget, 0.75D, Weapons.ROUND_LIFETIME_TICKS);
        assertTrue("both rounds must be admitted or the comparison is about one of them: " + narrow
                + " / " + wide, narrow >= 0 && wide >= 0);
        Weapons.awaitShotEnded(events, fired, narrow, "the narrow round never ended inside its wall");
        Weapons.awaitShotEnded(events, fired, wide, "the wide round never ended inside its wall");

        int narrowDepth = boreDepth(narrowX);
        int wideDepth = boreDepth(wideX);
        assertTrue("the narrow round did not get into the wall at all, so the comparison is between"
                + " two zeroes", narrowDepth > 0);
        assertTrue("the narrow round must bore deeper than the wide one on the same energy"
                + " (narrow=" + narrowDepth + " wide=" + wideDepth + "): the material resists across"
                + " the body's face, so a wider face buys less depth per unit of energy",
                narrowDepth > wideDepth);
    }

    /**
     * A round that cannot get through comes to REST inside, and the hole it is making GROWS while it
     * does. The second half is the one worth a server test: a bore that deepened all at once and then
     * sat there would satisfy every "it is still present" assertion in this class and still be the
     * instant resolution penetration-over-time replaced. So the wall's own stage writes are grouped by
     * the tick they happened on, and the claim is that a LATER tick before the round's end reached
     * deeper than the first one did.
     *
     * <p>red-witnessed: with the still-inside branch of {@code ShotSubstrate.step}
     * ({@code ShotSubstrate#step} at {@code else if (!contact.leftTheStructure)}) made to end the round at the contact tick, this fails at "the bore
     * reached 1 blocks on its first tick and never deeper on a later one ... (deepest by tick {102=1},
     * ended at 102)" (2026-09-29).</p>
     *
     * <p>red-witnessed, one inversion per verdict, 2026-09-30: with both come-to-rest returns
     * ({@code ShotSubstrate.java:283} and {@code :320}) answering EXPIRED, this fails at "the round
     * stopped, but not by coming to rest in what it was drilling ... but was:&lt;[EXPIRED]&gt;"; with
     * {@code StructureDamageEngine.java:367} charging a stage 1/2.7 of its price, at "a round with a
     * fraction of the wall's price came out the far side: {...stage:2...}".</p>
     */
    @Test
    public void aRoundThatCannotGetThroughRestsInsideAndItsCraterGrowsWhileItDoes() throws Exception {
        int wallX = 1500;
        prepare(wallX);
        buildWall(wallX, 10);

        // Enough to eat several blocks, nowhere near enough for ten: it must run out INSIDE.
        int budget = budgetForBlocks(wallX, 3.5D);
        long fired = events.markInstrumented();
        long id = fire(wallX - 3.5D, BORE_SPEED, budget, 0.25D, LONG_LIFETIME_TICKS);
        assertTrue("the substrate refused the shot", id >= 0);

        String ended = Weapons.awaitShotEnded(events, fired, id,
                "a round with a fraction of the wall's price never came to rest");
        assertEquals("the round stopped, but not by coming to rest in what it was drilling: " + ended,
                "STRUCTURE_IMPACT", Events.text(ended, "reason"));
        long endTick = (long) Events.number(ended, "tick");

        // The deepest block of the wall row staged on each tick, strictly before the round ended.
        String staged = events.since(fired, "block_stage_set");
        Events.assertInstrumentRan(staged, "damage_stage_events", "the wall's stage writes during the bore");
        SortedMap<Long, Integer> deepestByTick = new TreeMap<>();
        for (String record : Events.recordsWhere(staged, "dim", String.valueOf(DIM))) {
            String[] at = String.valueOf(Events.text(record, "pos")).split(",");
            int x = Integer.parseInt(at[0]);
            long tick = (long) Events.number(record, "tick");
            if (Integer.parseInt(at[1]) == Y && Integer.parseInt(at[2]) == Z && x >= wallX && x < wallX + 10
                    && tick <= endTick) {
                deepestByTick.merge(tick, x - wallX + 1, Math::max);
            }
        }
        assertTrue("nothing was staged while the round was in the wall: " + staged, !deepestByTick.isEmpty());
        int firstDepth = deepestByTick.get(deepestByTick.firstKey());
        int laterDepth = 0;
        for (int depth : deepestByTick.tailMap(deepestByTick.firstKey() + 1).values()) {
            laterDepth = Math.max(laterDepth, depth);
        }
        assertTrue("the bore reached " + firstDepth + " blocks on its first tick and never deeper on a"
                + " later one while the round was still inside (deepest by tick " + deepestByTick
                + ", ended at " + endTick + "): then the crater was cut in one go and the round merely"
                + " lingered — which is the behaviour penetration-over-time replaces",
                laterDepth > firstDepth);
        Reply far = stageAt(wallX + 9);
        assertTrue("a round with a fraction of the wall's price came out the far side: " + far,
                far.integer("stage") == 0 && !far.bool("wasDestroyed"));
    }

    /**
     * A round richer than the plate goes THROUGH it, and is worth less and slower on the far side —
     * and, the part that has no other way of being observed, its continuation is not refused as a
     * duplicate of its own first impact. Two thin plates with a gap: one round, both staged. A dedup
     * memory keyed on the shot rather than on the impact would let the first plate be hit and silently
     * drop everything the same round did afterwards, and no single-plate test can tell.
     *
     * <p>red-witnessed: with the contact identity in {@code ShotSubstrate.step} ({@code ShotSubstrate#step} at {@code TravellingBody body = new TravellingBody(ShotRegistry.get(world).nextImpactId(),})
     * taken from the SHOT rather than from the world's impact counter, this fails at "the SECOND plate was
     * never staged by a round that flew past it with budget in hand" (2026-09-29).</p>
     *
     * <p>red-witnessed, one inversion per verdict, 2026-09-30: with {@code ShotSubstrate.java:286}'s
     * energy write skipped for a round that left the structure, this fails at "the round left the
     * plates with everything it arrived with (6000 of 6000)"; with {@code ShotSubstrate.java:310}'s
     * slowing skipped on the same branch, at "the round left the plates at its muzzle speed (2.0 of
     * 2.0)".</p>
     */
    /**
     * A round too poor to buy even one stage of the block it meets is stopped by it: the armour holds.
     * It does the block no damage, it does not get into it, and it does not come out of the far side.
     *
     * <p>A stage is bought whole or not at all, so such a round has nothing to push the block aside
     * with. Before the maintainer's ruling of 2026-10-03 it paid nothing and lost nothing and crossed
     * the block at the speed it arrived with — the poorer the round, the cleaner it went through.</p>
     *
     * <p>The column is four blocks tall because a round fired level in the overworld falls as it
     * flies, and the verdict is on X alone, which gravity does not touch. The round's lifetime is
     * three blocks of travel against the one block it has to cover to reach the column, so a round
     * that went through instead ends in open air past the far face, by expiry.</p>
     *
     * <p>red-witnessed: with {@code Walk#armourHeld} at
     * {@code return world.isBlockLoaded(axis) && isStructure(world, axis, world.getBlockState(axis));}
     * answering false (the pass-through shape this replaces), this fails with "a round with half a
     * stage's worth was not stopped at the near face of a one-block column: it ended 2.000099997018424
     * blocks past that face (more than half its 0.2-a-tick travel either way; the far face is 1 past):
     * the armour did not hold | {...ended:EXPIRED...}" (2026-10-03).</p>
     *
     * <p>red-witnessed: with {@code ShotSubstrate#step} at {@code return ShotEndReason.STRUCTURE_IMPACT;}
     * (the come-to-rest return after {@code contact.result.isStopped()}) answering EXPIRED, this fails
     * at "the round ended at the column, but not by meeting it: {...reason:EXPIRED} expected:&lt;[STRUCTURE_IMPACT]&gt;
     * but was:&lt;[EXPIRED]&gt;" (2026-10-03).</p>
     *
     * <p>red-witnessed: with {@code StructureDamageEngine#spendInto} at
     * {@code while (stage < maxStage && left >= stageCost)} buying a stage for half its price
     * ({@code left * 2 >= stageCost}), this fails at "a round that could not afford a stage damaged the
     * block anyway: {...stage:1...} expected:&lt;0&gt; but was:&lt;1&gt;" (2026-10-03).</p>
     */
    @Test
    public void aRoundTooPoorForAStageIsStoppedByTheBlockItMeets() throws Exception {
        int colX = 1600;
        prepare(colX);
        ask("stellurgytest fill " + DIM + " " + colX + " " + (Y - 2) + " " + Z + " " + colX + " " + (Y + 1) + " " + Z
                + " minecraft:stone").requireOk("raise the column");
        int stageCost = stageAt(colX).integer("stageCost");
        requireArranged("the column has no price, so no budget can be chosen under it", stageCost > 1);

        double speed = 0.2D;
        int lifetime = 15;
        long fired = events.markInstrumented();
        long id = fire(colX - 1.0D, speed, stageCost / 2, 0.25D, lifetime);
        assertTrue("the substrate refused the shot", id >= 0);
        String ended = events.awaitRecordWithField(fired, "shot_ended", "shot", id,
                "the round never ended within its own lifetime", lifetime + 40);

        Reply end = ask("stellurgytest shot read " + DIM + " " + id).requireOk("read the ended round");
        System.out.println("FIXTURE armour-holds: stageCost=" + stageCost + " energy=" + (stageCost / 2)
                + " ended=" + ended + " read=" + end);
        // How far past the column's near face the round ended, along its own direction of travel (+X):
        // positive is inside the column or beyond it, negative is short of it. A round that got into
        // the block at all stands a whole tick's travel (its speed) past the face, and one that came
        // out of the far side stands more than a block past it, so "within half a tick's travel of the
        // face, on either side" is the stop at the face and nothing else.
        double pastNearFace = end.number("endX") - colX;
        assertTrue("a round with half a stage's worth was not stopped at the near face of a one-block"
                + " column: it ended " + pastNearFace + " blocks past that face (more than half its "
                + speed + "-a-tick travel either way; the far face is 1 past): the armour did not hold | "
                + end + " | " + ended, Math.abs(pastNearFace) <= speed / 2);
        assertEquals("the round ended at the column, but not by meeting it: " + ended,
                "STRUCTURE_IMPACT", Events.text(ended, "reason"));
        Reply column = stageAt(colX);
        assertEquals("a round that could not afford a stage damaged the block anyway: " + column, 0,
                column.integer("stage"));
    }

    @Test
    public void aRoundThroughAThinHullLeavesSlowerAndItsOwnContinuationIsNotRefused() throws Exception {
        int firstX = 1540;
        int secondX = firstX + 4;
        prepare(firstX);
        ask("stellurgytest fill " + DIM + " " + firstX + " " + (Y - 1) + " " + (Z - 1) + " " + (secondX + 2)
                + " " + (Y + 1) + " " + (Z + 1) + " minecraft:air").requireOk("clear the gap");
        place("minecraft:stone", firstX);
        place("minecraft:stone", secondX);

        // Rich enough for both plates and then some: this test is about what a round DOES on the far
        // side, so it must not be a test about running out.
        int budget = budgetForBlocks(firstX, 6.0D);
        double muzzleSpeed = 2.0D;
        long fired = events.markInstrumented();
        long id = fire(firstX - 3.0D, muzzleSpeed, budget, 0.25D, LONG_LIFETIME_TICKS);
        assertTrue("the substrate refused the shot", id >= 0);

        events.awaitRecordWithFields(fired, "block_stage_set",
                "the first plate was never staged, so this round never went through anything",
                Weapons.SUBJECT_TICKS, "dim", String.valueOf(DIM), "pos", Weapons.at(firstX, Y, Z));
        events.awaitRecordWithFields(fired, "block_stage_set",
                "the SECOND plate was never staged by a round that flew past it with budget in hand:"
                        + " the round's own continuation was refused as a duplicate of its first"
                        + " impact, which is the one failure a single-plate test cannot see",
                Weapons.SUBJECT_TICKS, "dim", String.valueOf(DIM), "pos", Weapons.at(secondX, Y, Z));

        Reply read = ask("stellurgytest shot read " + DIM + " " + id).requireOk("read the round");
        assertTrue("the round did not survive the second plate, so nothing is known about the far"
                + " side: " + read, read.bool("present"));
        Reply past = Reply.of("the round", read.object("shot"));
        assertTrue("the round is not past the second plate after staging it: " + past,
                past.number("x") > secondX + 1.0D);
        assertTrue("the round left the plates with everything it arrived with (" + past.integer("energy")
                + " of " + budget + "): going through has to cost something", past.integer("energy") < budget);
        assertTrue("the round left the plates at its muzzle speed (" + past.number("speed") + " of "
                + muzzleSpeed + "): spending energy on depth costs a body its speed",
                past.number("speed") < muzzleSpeed);
    }

    // ---- driving

    /** The round's id, or the substrate's own {@code -1} when it refused to admit one. */
    private long fire(double x, double speed, int energy, double radius, int lifetime) throws Exception {
        return ask("stellurgytest shot fire " + DIM + " " + x + " " + (Y + 0.5D) + " " + (Z + 0.5D)
                + " " + speed + " 0 0 " + energy + " " + lifetime + " KINETIC " + radius + " 1.0")
                .requireOk("fire a round").longInteger("id");
    }

    private void buildWall(int fromX, int depth) throws Exception {
        for (int i = 0; i < depth; i++) {
            place("minecraft:stone", fromX + i);
        }
    }

    private void prepare(int wallX) throws Exception {
        ask("stellurgytest chunk warmup " + DIM + " " + ((wallX - 16) >> 4) + " " + ((Z - 16) >> 4) + " "
                + ((wallX + 24) >> 4) + " " + ((Z + 16) >> 4)).requireOk("chunk warmup");
        ask("stellurgytest fill " + DIM + " " + (wallX - 8) + " " + (Y - 2) + " " + (Z - 3) + " "
                + (wallX + 20) + " " + (Y + 4) + " " + (Z + 3) + " minecraft:air").requireOk("clear the site");
    }

    // ---- reading

    /** How many blocks deep into the wall took damage: the bore's own length. */
    private int boreDepth(int wallX) throws Exception {
        int depth = 0;
        for (int i = 0; i < 10; i++) {
            Reply stage = stageAt(wallX + i);
            // the producer always writes `stage` and `wasDestroyed` on a `damage stage` reply, so
            // refusing on a missing one is the right failure.
            if (stage.integer("stage") > 0 || stage.bool("wasDestroyed")) {
                depth = i + 1;
            }
        }
        return depth;
    }

    /** What boring {@code blocks} of this wall costs at the reference cross-section, priced by the game. */
    private int budgetForBlocks(int wallX, double blocks) throws Exception {
        Reply stage = stageAt(wallX);
        int cost = stage.integer("stageCost");
        int stages = Math.max(1, stage.integer("maxStage"));
        assertTrue("the wall block has no stage cost, so nothing below is priced: " + stage, cost > 0);
        return (int) Math.round(cost * stages * blocks);
    }

    private Reply stageAt(int x) throws Exception {
        return ask("stellurgytest damage stage " + DIM + " " + x + " " + Y + " " + Z);
    }

    private void place(String block, int x) throws Exception {
        Reply placed = ask("stellurgytest place " + DIM + " " + x + " " + Y + " " + Z + " " + block);
        assertTrue("failed to place " + block + " at " + x + ": " + placed, placed.bool("placed"));
    }

    private String exec(String command) throws Exception {
        return String.join("\n", client().execute(command));
    }

    private Reply ask(String command) throws Exception {
        return Reply.of(command, exec(command));
    }
}
