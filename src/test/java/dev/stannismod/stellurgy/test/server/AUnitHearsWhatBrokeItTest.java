package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.Weapons;
import dev.stannismod.stellurgy.test.contract.LegacyWitness;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import org.junit.Test;

import java.util.List;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A unit is TOLD what happened to it — including, and especially, when what happened killed it.
 *
 * <p>The stage a block carries answers <em>how broken am I</em> and survives everything. It cannot
 * answer <em>what just happened to me</em>: a shell and a collapsing hyperspace window leave the same
 * stage behind, and a unit that only ever reads its stage can never tell them apart. So the cause is
 * pushed once, at the moment it is true, and these are the claims that makes.</p>
 *
 * <p><b>The subject is a chest</b>, because a unit has to have a tile to hear anything and a chest is
 * the cheapest thing in the game that has one. What is being pinned is the DELIVERY, which is the same
 * for every unit; what a particular machine then DOES about being damaged is that machine's own and is
 * deliberately not here.</p>
 *
 * <p><b>How it hears.</b> On a harness server every tile carries a listener attached through the
 * damage-aware capability — the route a foreign mod takes — and each occurrence it is handed becomes
 * one {@code damage_occurrence} record in the server event log, carrying the position it was
 * delivered at. Each method takes a mark before its blow and reads only the records at ITS unit's
 * position, so what another scenario's blows delivered is not in its window.</p>
 */
public class AUnitHearsWhatBrokeItTest extends AbstractSharedServerTest {

    private static final int DIM = 0;
    private static final int Y = 70, X = 2400;
    private static final int SURVIVES_Z = 1400, DIES_Z = 1420, DEAF_Z = 1440;
    /** The listener's instrument name, as the probe's recorder declares it. */
    private static final String RECORDER = "damage_occurrence_recorder";

    private final Events log =
            new Events(c -> read(c).raw(),
                    ticks -> GameTicks.advance(client(), GameTicks.server(), ticks), evictionReports());

    /**
     * A unit that survives hears about it, and what it hears names the cause, the severity and the
     * stage it moved to — not a verdict, because the consequence is the unit's own to compute.
     */
    @RedWitness(verdict = "delivered", member = "ShipDamageService#tellTheUnits",
            at = "if (walk == null || walk.touched.isEmpty())", replacedBy = "if (true)",
            fails = "nothing was delivered to a unit that was just damaged", on = "2026-10-17")
    @RedWitness(verdict = "names the cause", member = "ShipDamageService#tellTheUnits",
            at = "unit.onDamage(new DamageOccurrence(request.getCause(), request.getKind(), world,",
            replacedBy = "unit.onDamage(new DamageOccurrence(DamageCause.COLLISION, request.getKind(), world,",
            fails = "the occurrence does not name what caused it", on = "2026-10-17")
    @RedWitness(verdict = "names the kind", member = "ShipDamageService#tellTheUnits",
            at = "unit.onDamage(new DamageOccurrence(request.getCause(), request.getKind(), world,",
            replacedBy = "unit.onDamage(new DamageOccurrence(request.getCause(), ImpactKind.THERMAL, world,",
            fails = "the occurrence does not name the kind of blow", on = "2026-10-17")
    @RedWitness(verdict = "carries a severity", member = "ShipDamageService#tellTheUnits",
            at = "t.pos, where, t.stageBefore, t.stageAfter, t.maxStage, t.budgetSpent, shipId",
            replacedBy = "t.pos, where, t.stageBefore, t.stageAfter, t.maxStage, 0, shipId",
            fails = "the occurrence carries no severity", on = "2026-10-17")
    @RedWitness(verdict = "still standing", member = "DamageOccurrence#isDestroyed",
            at = "return stageAfter >= maxStage;", replacedBy = "return stageAfter < maxStage;",
            fails = "the occurrence says the unit was destroyed when it is still standing", on = "2026-10-17")
    @RedWitness(verdict = "carries the world", member = "ShipDamageService#tellTheUnits",
            at = "unit.onDamage(new DamageOccurrence(request.getCause(), request.getKind(), world,",
            replacedBy = "unit.onDamage(new DamageOccurrence(request.getCause(), request.getKind(), null,",
            fails = "the occurrence carries no world", on = "2026-10-17")
    @Test
    public void aUnitThatSurvivesIsToldWhatHappenedAndHowHard() throws Exception {
        prepare(SURVIVES_Z);
        place(SURVIVES_Z, "minecraft:chest");

        // One stage's worth, priced off the block itself rather than written here: every cost in this
        // engine is tunable, so a budget picked by hand would pin the tuning.
        int oneStage = stageAt(SURVIVES_Z).integer("stageCost");
        assertTrue("the chest has no price, so no budget here means anything", oneStage > 0);
        long blow = log.mark();
        strike(SURVIVES_Z, oneStage);

        String told = log.awaitRecordWithFields(blow, "damage_occurrence", "nothing was delivered to a"
                + " unit that was just damaged: the stage moved and the unit was never told, so a"
                + " machine can only ever poll and can never react", Weapons.SUBJECT_TICKS,
                at(SURVIVES_Z));
        assertEquals("the occurrence does not name what caused it: " + told, "IMPACT",
                Events.text(told, "cause"));
        assertEquals("the occurrence does not name the kind of blow: " + told, "KINETIC",
                Events.text(told, "kind"));
        assertTrue("the occurrence carries no severity, so a unit cannot tell a scratch from a "
                + "near-miss-with-the-reactor: " + told, Events.number(told, "spent") > 0);
        assertEquals("the occurrence says the unit was destroyed when it is still standing: " + told,
                "false", Events.text(told, "destroyed"));
        assertEquals("the occurrence carries no world, so a unit that wants to do anything about being "
                + "damaged has nothing to do it with: " + told, "true", Events.text(told, "hasWorld"));
    }

    /**
     * <b>The one that matters.</b> A unit destroyed outright is still told — and it is told that it was
     * destroyed.
     *
     * <p>This is the occurrence a naive implementation loses: the block becomes air inside the damage
     * walk, so anything looking the unit up afterwards finds nothing and says nothing. It is also the
     * occurrence a unit most needs, because what a failing machine does about being killed is its own
     * business and differs by machine — a chemical engine goes like TNT, an ion engine merely ceases to
     * exist, a plasma engine is a tank letting go. A unit that never hears about its own death cannot
     * have one of those.</p>
     */
    @RedWitness(verdict = "told", member = "ShipDamageService#tellTheUnits",
            at = "if (walk == null || walk.touched.isEmpty())", replacedBy = "if (true)",
            fails = "a unit destroyed outright was told NOTHING", on = "2026-10-17")
    @RedWitness(verdict = "arrangement: destroyed", member = "StructureDamageEngine#spendInto",
            at = "if (!mayRemove(world, pos, state))", replacedBy = "if (true)",
            fails = "the unit is still standing, so this run never tested a destruction at all", on = "2026-10-17")
    @RedWitness(verdict = "told it died", member = "DamageOccurrence#isDestroyed",
            at = "return stageAfter >= maxStage;", replacedBy = "return stageAfter < maxStage;",
            fails = "the unit was told, but not that it had DIED", on = "2026-10-17")
    @Test
    public void aUnitKilledOutrightIsStillToldThatItDied() throws Exception {
        prepare(DIES_Z);
        place(DIES_Z, "minecraft:chest");

        // Enough for every stage at once, so the unit goes from pristine to gone in one blow.
        Reply priced = stageAt(DIES_Z);
        int all = priced.integer("stageCost") * Math.max(1, priced.integer("maxStage")) * 4;
        long blow = log.mark();
        strike(DIES_Z, all);

        log.awaitRecordWithFields(blow, "damage_occurrence", "a unit destroyed outright was told"
                + " NOTHING: by the time anyone looks the block is already air, and the blow that ends a"
                + " unit is the one it most needs to hear about — it is what a machine's own failure is"
                + " made of", Weapons.SUBJECT_TICKS, at(DIES_Z));
        Reply after = stageAt(DIES_Z);
        requireArranged("the unit is still standing, so this run never tested a destruction at all: "
                + after, after.bool("wasDestroyed") || "minecraft:air".equals(after.text("block")));

        List<String> heard = Events.recordsWhereAll(log.since(blow, "damage_occurrence"), at(DIES_Z));
        String last = heard.get(heard.size() - 1);
        assertEquals("the unit was told, but not that it had DIED: then it cannot tell a dent from its "
                + "own destruction, and every failure mode collapses into one: " + heard, "true",
                Events.text(last, "destroyed"));
    }

    /**
     * A unit the blow did not touch is not told, however willing it is to hear. The control: without
     * it, every assertion above would also pass against a build that told EVERYTHING to everyone,
     * which is a different and much worse mechanism.
     *
     * <p>The bystander has to be LISTENING, or this cannot fail: the listener hangs off a capability
     * attached to TILES, and a stone has none. So the stone is struck with a chest beside it that
     * carries the listener, and then that same chest is struck, which is what proves it was listening
     * all along. The silence is read as a closed window from a mark taken before the stone's blow,
     * after the blow's own reply: the probe resolves the whole impact inside the command, so every
     * occurrence that blow delivers is in the log before the command answers.</p>
     *
     * <p>Not witnessed on the first form of the neighbour inversion: placed AFTER
     * {@code if (unit == null)}'s {@code continue}, it never ran for the struck stone (a stone has no
     * listener), and the method stayed green.</p>
     */
    @RedWitness(verdict = "arrangement: staged", member = "StructureDamageEngine#spendInto",
            at = "DamageState.setStage(world, pos, stage); result.blocksStaged++;",
            replacedBy = "DamageState.setStage(world, pos, stage);",
            fails = "the blow did not stage the stone, so nothing below was delivered for a reason", on = "2026-10-17")
    @RedWitness(verdict = "arrangement: bystander untouched", member = "StructureDamageEngine#spendInto",
            at = "} else { DamageState.setStage(world, pos, stage);",
            replacedBy = "} else { DamageState.setStage(world, pos, stage); DamageState.setStage(world, pos.south(2), 1);",
            fails = "the blow reached the bystander, so it would be RIGHT to tell it", on = "2026-10-17")
    @RedWitness(verdict = "silence", member = "ShipDamageService#tellTheUnits",
            at = "for (StructureDamageEngine.Touched t : walk.touched)",
            replacedBy = "for (BlockPos near : BlockPos.getAllInBox(walk.touched.get(0).pos.add(-3, -3, -3),"
                    + " walk.touched.get(0).pos.add(3, 3, 3))) { IDamageAware listener ="
                    + " CapabilityDamageAware.get(world.getTileEntity(near)); if (listener != null"
                    + " && !near.equals(walk.touched.get(0).pos)) { listener.onDamage(new DamageOccurrence("
                    + "request.getCause(), request.getKind(), world, near, new Vec3d(near.getX() + 0.5D,"
                    + " near.getY() + 0.5D, near.getZ() + 0.5D), 0, 0, 1, 0, shipId)); } }"
                    + " for (StructureDamageEngine.Touched t : walk.touched)",
            fails = "a listening unit the blow never touched was handed an occurrence", on = "2026-10-17")
    @RedWitness(verdict = "the bystander struck directly is told", member = "ShipDamageService#tellTheUnits",
            at = "if (walk == null || walk.touched.isEmpty())", replacedBy = "if (true)",
            fails = "the bystander, struck directly, was not told either", on = "2026-10-17")
    @LegacyWitness(prose = "2026-09-30:"
            + " <ul>"
            + " <li>(taken on the pre-merge form, when the recorder lived in the probe command and wrote"
            + " through a static log) with {@code DamageOccurrenceRecorder#onAttach} at"
            + " {@code log.noteInstrumentEntered(INSTRUMENT);} removed (and its twin in"
            + " {@code DamageOccurrenceRecorder#record}), the instrument check fails with \"the observation"
            + " point \"damage_occurrence_recorder\" never executed\";</li>"
            + " </ul>")
    @Test
    public void aListeningUnitTheBlowMissedIsNotTold() throws Exception {
        prepare(DEAF_Z);
        int bystanderZ = DEAF_Z + 2;
        place(DEAF_Z, "minecraft:stone");
        place(bystanderZ, "minecraft:chest");

        int oneStage = stageAt(DEAF_Z).integer("stageCost");
        assertTrue("the stone has no price, so no budget here means anything", oneStage > 0);
        long missed = log.mark();
        Reply struckStone = strike(DEAF_Z, oneStage);
        requireArranged("the blow did not stage the stone, so nothing below was delivered for a reason: "
                + struckStone, struckStone.integer("staged") >= 1);
        Reply bystander = stageAt(bystanderZ);
        requireArranged("the blow reached the bystander, so it would be RIGHT to tell it: " + bystander,
                bystander.integer("stage") == 0 && !bystander.bool("wasDestroyed"));

        String window = log.since(missed, "damage_occurrence");
        Events.assertInstrumentRan(window, RECORDER, "the bystander was not told");
        assertTrue("a listening unit the blow never touched was handed an occurrence: then delivery is"
                + " not to what was struck but to whoever is listening: " + window,
                Events.recordsWhereAll(window, at(bystanderZ)).isEmpty());

        // The same chest, struck: it was listening, so the silence above was the delivery's.
        long struck = log.mark();
        strike(bystanderZ, stageAt(bystanderZ).integer("stageCost"));
        log.awaitRecordWithFields(struck, "damage_occurrence", "the bystander, struck directly, was"
                + " not told either — so it was never listening and the silence above proved nothing",
                Weapons.SUBJECT_TICKS, at(bystanderZ));
    }

    // ---- driving

    private void place(int lane, String block) throws Exception {
        Reply placed = arrange("stellurgytest place " + DIM + " " + X + " " + Y + " " + lane + " " + block);
        assertTrue("failed to place " + block + ": " + placed, placed.bool("placed"));
    }

    private void prepare(int lane) throws Exception {
        arrange("stellurgytest chunk warmup " + DIM + " " + ((X - 16) >> 4) + " " + ((lane - 16) >> 4) + " "
                + ((X + 16) >> 4) + " " + ((lane + 16) >> 4)).requireOk("warm the lane's chunks");
        arrange("stellurgytest fill " + DIM + " " + (X - 6) + " " + (Y - 1) + " " + (lane - 2) + " " + (X + 6)
                + " " + (Y + 2) + " " + (lane + 2) + " minecraft:air").requireOk("clear the lane");
    }



    /** One blow along +X into the lane, as {@code damage impact}, read as data. */
    private Reply strike(int lane, int budget) throws Exception {
        arrange("stellurgytest damage clear-impacts").requireOk("forget earlier impacts");
        return act("stellurgytest damage impact " + DIM + " " + (X - 2.5D) + " " + (Y + 0.5D) + " "
                + (lane + 0.5D) + " 1 0 0 " + budget + " KINETIC").requireOk("strike the lane");
    }

    private Reply stageAt(int lane) throws Exception {
        return read("stellurgytest damage stage " + DIM + " " + X + " " + Y + " " + lane);
    }

    /**
     * The field pairs naming THIS lane's unit on a {@code damage_occurrence} record: its position.
     * Not its dimension — the record takes that from the occurrence's world, and an occurrence that
     * lost its world is exactly what the first method's last verdict must be able to catch, so a
     * narrowing on it would turn that verdict into "nothing was delivered". The lanes of this class
     * are its own, so a position names one unit.
     */
    private static String[] at(int lane) {
        return new String[]{"x", String.valueOf(X), "y", String.valueOf(Y), "z", String.valueOf(lane)};
    }
}
