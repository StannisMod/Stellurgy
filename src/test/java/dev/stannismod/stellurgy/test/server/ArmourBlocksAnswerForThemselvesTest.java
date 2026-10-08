package dev.stannismod.stellurgy.test.server;

import org.junit.Test;

import java.util.List;

import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.Weapons;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Armour that ANSWERS — the half of the contact seam that had a contract, a set of result states and
 * no block in the game which implemented it.
 *
 * <p>Every claim here is about a block deciding its own fate, which is the thing toughness alone can
 * never express: a plate cannot send a body somewhere else, cannot spend a charge of its own, and
 * cannot tell a beam from a slug. Each scenario gets its own LANE across the line of fire, because a
 * round rich enough to be interesting outlives its own target and would otherwise arrive in the next
 * one's arrangement.</p>
 *
 * <p>What the block answered is read off the contact seam itself ({@code contact_answered}: the
 * block's own answer, recorded where the resolver asks it), so "the mirror sent it back" is the
 * mirror's answer rather than a sample of the round's velocity taken until it read negative.</p>
 */
public class ArmourBlocksAnswerForThemselvesTest extends AbstractSharedServerTest {

    private static final int DIM = 0;
    private static final int Y = 70, X = 2100;
    private static final int MIRROR_Z = 1200, MIRROR_SLUG_Z = 1220, BETTER_Z = 1240;
    private static final int REACTIVE_Z = 1260, REACTIVE_TWICE_Z = 1280, RAILGUN_Z = 1300;
    private static final int PRICE_Z = 1320;

    private static final double SPEED = 0.45D;

    private final Events events =
            new Events(command -> exec(command), ticks -> GameTicks.advance(client(), GameTicks.server(), ticks), evictionReports());

    /**
     * A mirror returns a beam and is SMASHED by a solid round, and the difference is the kind in the
     * contact and nothing else — the same block, the same face, the same energy.
     *
     * <p>The second half used to read "lets a solid round straight through", and it was pinning a
     * defect. A mirror has no OPTICAL opinion about a solid round, and it said so by answering "passed
     * through, carrying everything" — which is not "no opinion", it is "through, for free". The block
     * now DECLINES, and declining hands the meeting to the ordinary law: the film is priced off the
     * table and the eighth of a voxel it fills, and it breaks like the glass it is.</p>
     *
     * <p>red-witnessed: with {@code BlockMirrorPlating.onContact} ({@code BlockMirrorPlating#onContact} at {@code if (!isRadiant(contact.getKind()))})
     * declining beams as it declines solid rounds, this fails at "a beam fired at a mirror was never sent
     * back" on an answer reading {@code NO_OPINION} (2026-09-29).</p>
     *
     * <p>red-witnessed, one inversion per verdict, 2026-09-30: with the kind gate at
     * {@code BlockMirrorPlating.java:71} taken out (a solid round answered optically), this fails at "a
     * solid round bounced off glass and foil ... {...kind:KINETIC...answer:DEFLECTED...}"; with the
     * solid round's decline at {@code BlockMirrorPlating.java:78} answering "passed through, carrying
     * everything" instead, at "the film is still standing after a solid round crossed it ...
     * film={...stage:0...block:stellurgy:mirrorplatingaluminium...}".</p>
     */
    @Test
    public void aMirrorReturnsABeamAndIsSmashedByASolidRound() throws Exception {
        prepare(MIRROR_Z);
        prepare(MIRROR_SLUG_Z);
        place("stellurgy:mirrorPlatingAluminium", MIRROR_Z);
        place("stellurgy:mirrorPlatingAluminium", MIRROR_SLUG_Z);

        // Well inside what an aluminium film can shed, so the plate survives to reflect.
        long fired = events.markInstrumented();
        long beam = fire(MIRROR_Z, 3_000, "BEAM");
        String toBeam = answerAt(fired, MIRROR_Z);
        assertEquals("a beam fired at a mirror was never sent back — the plate answered as if it were"
                + " ordinary hull: " + toBeam, "DEFLECTED", Events.text(toBeam, "answer"));
        assertTrue("the mirror deflected the beam but not BACK along the line it came in on: " + toBeam,
                Events.number(toBeam, "outVx") < 0.0D);
        Weapons.awaitShotEnded(events, fired, beam, "the reflected beam never ended");

        long slugMark = events.mark();
        long slug = fire(MIRROR_SLUG_Z, 3_000, "KINETIC");
        String slugEnded = Weapons.awaitShotEnded(events, slugMark, slug, "the slug never ended");
        // Judged after the beam leg above proved the contact recorder live: whatever the mirror said
        // to the solid round — declined, or was never asked — it did not send it anywhere.
        String toSlug = events.since(slugMark, "contact_answered");
        Events.assertInstrumentRan(toSlug, "contact_events", "the mirror did not deflect the slug");
        assertTrue("a solid round bounced off glass and foil: a mirror has no OPTICAL opinion about a"
                + " solid round, and the kind in the contact is the only thing that separates the two"
                + " cases: " + toSlug, Events.recordsWhereAll(toSlug, "pos", Weapons.at(X, Y, MIRROR_SLUG_Z),
                "answer", "DEFLECTED").isEmpty());
        boolean standing = stillThere(MIRROR_SLUG_Z);
        assertTrue("the film is still standing after a solid round crossed it: then the round paid"
                + " nothing for it, and a mirror is armour that only the weapon it was built to stop"
                + " can remove. film=" + stageAt(X, MIRROR_SLUG_Z) + " | slug ended: " + slugEnded
                + " | its contacts: " + events.since(slugMark, "contact_ricochet_decided")
                + " | its payments: " + events.since(slugMark, "shot_energy_spent"), !standing);
    }

    /**
     * A mirror dies by what it ABSORBS. Two tiers, the same beam: the worse one lets more of it into
     * its film and is gone; the better one lets less in and survives.
     *
     * <p>red-witnessed: with {@code BlockMirrorPlating#onContact} at {@code int absorbed = (int) Math.ceil(contact.getEnergy() * (1.0D - reflectance));} absorbing as the aluminium tier whatever
     * the plate's own reflectance, this fails with "a gold mirror died to the same beam that killed an
     * aluminium one". 2026-09-30.</p>
     */
    @Test
    public void aBetterMirrorSurvivesWhatKillsAWorseOne() throws Exception {
        prepare(BETTER_Z);
        place("stellurgy:mirrorPlatingAluminium", BETTER_Z);

        // Chosen against the film rather than against a number in a test: enough that a tenth of it
        // exceeds what the film sheds, and a thirtieth of it does not. Read off production, where
        // both are private: the film sheds 4000 (Stellurgy.java:169, MIRROR_FILM_DISSIPATION) and the
        // tiers reflect 0.90 and 0.97 (Stellurgy.java:834 and :838), so aluminium absorbs 6000 > 4000
        // and gold 1800 <= 4000. (The control below is what keeps the number from deciding alone.)
        int killsAluminium = 60_000;

        // CONTROL, and the test is worthless without it — the first cut of this test passed with
        // the whole responder switched off. Ordinary damage prices the two tiers IDENTICALLY, so it
        // cannot produce a difference between them at all. Whatever separates aluminium from gold
        // below is therefore the reflectance, and can be nothing else.
        placeAt(X + 4, BETTER_Z, "stellurgy:mirrorPlatingGold");
        long aluminiumCost = stageAt(X, BETTER_Z).longInteger("stageCost");
        long goldCost = stageAt(X + 4, BETTER_Z).longInteger("stageCost");
        assertTrue("the two mirror tiers cost different amounts to break by ordinary damage"
                + " (aluminium=" + aluminiumCost + " gold=" + goldCost + "): then the ladder below can"
                + " be produced without any mirror law at all, and this test measures the toughness"
                + " table", aluminiumCost == goldCost);
        placeAt(X + 4, BETTER_Z, "minecraft:air");

        long fired = events.markInstrumented();
        long first = fire(BETTER_Z, killsAluminium, "BEAM");
        answerAt(fired, BETTER_Z);
        Weapons.awaitShotEnded(events, fired, first, "the first beam never ended");
        assertTrue("an aluminium mirror survived a beam that put more into its film than the film can"
                + " shed: then nothing burns out and a mirror is unconditional armour",
                !stillThere(BETTER_Z));

        place("stellurgy:mirrorPlatingGold", BETTER_Z);
        long again = events.mark();
        long second = fire(BETTER_Z, killsAluminium, "BEAM");
        answerAt(again, BETTER_Z);
        Weapons.awaitShotEnded(events, again, second, "the second beam never ended");
        assertTrue("a gold mirror died to the same beam that killed an aluminium one: then the tiers"
                + " are not the reflectances and the ladder means nothing", stillThere(BETTER_Z));
    }

    /**
     * A reactive plate stops one shot and is gone; the second through the same spot is not stopped.
     * That is a property of the thing rather than a counter somebody keeps.
     *
     * <p>red-witnessed: with {@code BlockReactivePlating#onContact} at {@code detonate(world, contact.getPos());}'s detonation removed, this fails with
     * "the charge is still standing after eating a round". The two verdicts after it were not
     * separately witnessed. 2026-09-30.</p>
     */
    @Test
    public void aReactivePlateStopsOneShotAndIsThenNotThere() throws Exception {
        prepare(REACTIVE_Z);
        place("stellurgy:reactivePlate", REACTIVE_Z);
        // Behind it, an ordinary block: what a spent charge stops protecting.
        placeAt(X + 2, REACTIVE_Z, "minecraft:stone");

        long fired = events.markInstrumented();
        long first = fire(REACTIVE_Z, 4_000, "KINETIC");
        answerAt(fired, REACTIVE_Z);
        Weapons.awaitShotEnded(events, fired, first, "the first round never ended");
        assertTrue("the charge is still standing after eating a round: a reactive plate spends ITSELF"
                + " or it is just a tough block", !stillThere(REACTIVE_Z));
        assertTrue("the block BEHIND the charge was hit through it: the charge did not stop the round"
                + " it spent itself on", clean(X + 2, REACTIVE_Z));

        long second = fire(REACTIVE_Z, 4_000, "KINETIC");
        Weapons.awaitShotEnded(events, fired, second, "the second round never ended");
        assertTrue("the second round through the same spot was stopped as well — then the charge was"
                + " never spent and reactive armour is free", !clean(X + 2, REACTIVE_Z));
    }

    /**
     * Twice the plating eats more of the same impact — the ordering the volume rule exists for.
     *
     * <p>red-witnessed: with {@code BlockReactivePlating#onContact} at {@code int eaten = Math.min(contact.getEnergy(), capacity);} eating the whole contact regardless of
     * capacity, this fails with "a round bigger than one plate can swallow was stopped by it anyway".
     * The full-block verdict was not separately witnessed. 2026-09-30.</p>
     */
    @Test
    public void twiceTheReactiveVolumeEatsMoreOfTheSameImpact() throws Exception {
        prepare(REACTIVE_TWICE_Z);
        prepare(RAILGUN_Z);
        place("stellurgy:reactivePlate", REACTIVE_TWICE_Z);
        placeAt(X + 2, REACTIVE_TWICE_Z, "minecraft:stone");
        place("stellurgy:reactiveBlock", RAILGUN_Z);
        placeAt(X + 2, RAILGUN_Z, "minecraft:stone");

        // More than one plate can swallow, less than a full block can: a plate takes 10000
        // (Stellurgy.java:175, REACTIVE_PLATE_CAPACITY, private) and a block twice that (:844).
        int between = 15_000;
        long fired = events.markInstrumented();
        long throughPlate = fire(REACTIVE_TWICE_Z, between, "KINETIC");
        long intoBlock = fire(RAILGUN_Z, between, "KINETIC");
        answerAt(fired, REACTIVE_TWICE_Z);
        answerAt(fired, RAILGUN_Z);
        Weapons.awaitShotEnded(events, fired, throughPlate, "the round through the plate never ended");
        Weapons.awaitShotEnded(events, fired, intoBlock, "the round into the block never ended");

        assertTrue("a round bigger than one plate can swallow was stopped by it anyway: then capacity"
                + " does not bound what a charge eats", !clean(X + 2, REACTIVE_TWICE_Z));
        assertTrue("the full block let through what it should have swallowed whole: then twice the"
                + " plating is not twice the protection and layering buys nothing",
                clean(X + 2, RAILGUN_Z));
    }

    /**
     * A mirror film is priced as the glass and foil it is, not as the hull plate its MATERIAL says.
     *
     * <p>Both plating families are declared {@code Material.IRON}, and the damage table resolves by
     * material when nothing has written a row. The comparator is REACTIVE plating: the same class,
     * the same thickness and the same declared material, with NO row of its own — so a difference in
     * price can come from exactly one place. Only the ORDERING is claimed.</p>
     *
     * <p>red-witnessed: with {@code WeightEngine#defaultToughnessByRegex} at {@code m.put("stellurgy:mirrorplating.*", 1.0);}'s mirror-plating row removed, this fails with
     * "a mirror film costs what the identically shaped plating beside it costs (film=55 reactive=55)".
     * 2026-09-30.</p>
     */
    @Test
    public void aMirrorFilmCostsLessToBreakThanTheMetalItsMaterialClaims() throws Exception {
        prepare(PRICE_Z);
        place("stellurgy:mirrorPlatingAluminium", PRICE_Z);
        placeAt(X + 4, PRICE_Z, "stellurgy:reactivePlate");

        Reply film = stageAt(X, PRICE_Z);
        Reply metal = stageAt(X + 4, PRICE_Z);
        long filmCost = film.longInteger("stageCost"), metalCost = metal.longInteger("stageCost");
        assertTrue("a mirror film costs what the identically shaped plating beside it costs (film="
                + filmCost + " reactive=" + metalCost + "): the two differ only in that one has a row"
                + " of its own, so this says the row is not being read at all and glass with foil on"
                + " it still resists like hull plate. film=" + film + " reactive=" + metal,
                filmCost < metalCost);
    }

    // ---- driving

    private long fire(int lane, int energy, String kind) throws Exception {
        Reply fired = ask("stellurgytest shot fire " + DIM + " " + (X - 3.0D) + " " + (Y + 0.5D) + " "
                + (lane + 0.5D) + " " + SPEED + " 0 0 " + energy + " " + Weapons.ROUND_LIFETIME_TICKS
                + " " + kind + " 0.25 1.0")
                .requireOk("fire down lane " + lane);
        long id = fired.longInteger("id");
        assertTrue("the substrate refused the " + kind + " shot down lane " + lane + ": " + fired, id >= 0);
        return id;
    }

    private void place(String block, int lane) throws Exception {
        placeAt(X, lane, block);
    }

    private void placeAt(int x, int lane, String block) throws Exception {
        Reply placed = ask("stellurgytest place " + DIM + " " + x + " " + Y + " " + lane + " " + block);
        assertTrue("failed to place " + block + " at " + x + "," + lane + ": " + placed, placed.bool("placed"));
    }

    private void prepare(int lane) throws Exception {
        // HELD, not merely warmed. There is no player on this server, so a warmed chunk unloads again
        // on its own, and a swept segment SKIPS a voxel whose chunk is not loaded rather than calling
        // it solid or empty. Measured 2026-09-29 on this class: a slug fired down the mirror lane once
        // the beam leg had finished met nothing at all and EXPIRED with the film untouched.
        for (int cx = (X - 16) >> 4; cx <= (X + 40) >> 4; cx++) {
            ask("stellurgytest chunk forceload " + DIM + " " + cx + " " + (lane >> 4)).requireOk("hold a chunk");
        }
        ask("stellurgytest chunk warmup " + DIM + " " + ((X - 16) >> 4) + " " + ((lane - 16) >> 4) + " "
                + ((X + 40) >> 4) + " " + ((lane + 16) >> 4)).requireOk("warm the lane's chunks");
        ask("stellurgytest fill " + DIM + " " + (X - 8) + " " + (Y - 2) + " " + (lane - 3) + " " + (X + 40)
                + " " + (Y + 4) + " " + (lane + 3) + " minecraft:air").requireOk("clear the lane");
    }

    // ---- reading

    /**
     * The first answer the armour at {@code (X, Y, lane)} gave since {@code mark} — the block's own
     * decision about the body that met it.
     */
    private String answerAt(long mark, int lane) throws Exception {
        String reply = events.awaitMatching(mark, "contact_answered",
                one -> !Events.recordsWhere(one, "pos", Weapons.at(X, Y, lane)).isEmpty(),
                "at the armour in lane " + lane, "nothing ever met the armour in lane " + lane
                        + ", so whatever it did or did not do is about nothing", Weapons.SUBJECT_TICKS);
        List<String> here = Events.recordsWhere(reply, "pos", Weapons.at(X, Y, lane));
        return here.get(0);
    }

    /** Is the armour block still where it was placed? */
    private boolean stillThere(int lane) throws Exception {
        return !"minecraft:air".equals(stageAt(X, lane).text("block"));
    }

    /** Is the block behind the armour untouched — never staged and never destroyed? */
    private boolean clean(int x, int lane) throws Exception {
        Reply state = stageAt(x, lane);
        return !"minecraft:air".equals(state.text("block")) && !state.bool("wasDestroyed")
                && state.integer("stage") == 0;
    }

    private Reply stageAt(int x, int lane) throws Exception {
        return ask("stellurgytest damage stage " + DIM + " " + x + " " + Y + " " + lane).requireOk("read a stage");
    }
}
