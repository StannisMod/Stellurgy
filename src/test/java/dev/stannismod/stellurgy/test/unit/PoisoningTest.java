package dev.stannismod.stellurgy.test.unit;

import dev.stannismod.stellurgy.atmosphere.hazard.Poisoning;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The poison dose law, second by second: air exactly at its limit harms from the thirtieth second and
 * not before; air twice as poisonous, twice as soon; air below its limit adds nothing; clean air clears
 * half a dose in five minutes; and a heavier dose harms harder. These are the numbers the game was
 * given, read as a player meets them.
 */
public class PoisoningTest {

    /** The second at which air of this toxic index first harms somebody who starts clean. */
    private static int firstHarmfulSecond(double toxicIndex) {
        double dose = 0.0D;
        for (int second = 1; second <= 10_000; second++) {
            dose = Poisoning.nextDose(dose, toxicIndex);
            if (Poisoning.damageFor(dose) > 0.0F) {
                return second;
            }
        }
        return -1;
    }

    /** red-witnessed: with {@code Poisoning#DOSE_THRESHOLD} at {@code 30.0D;} made {@code 20.0D;}, the
     *  first harm came at second 20. */
    @Test
    public void airExactlyAtItsLimitHarmsFromTheThirtiethSecondAndNotBefore() {
        assertEquals(30, firstHarmfulSecond(1.0D));
    }

    /** red-witnessed: with {@code Poisoning#nextDose} at {@code return dose + toxicIndex;} adding one
     *  instead of the index, twice the poison harmed at second 30 too. */
    @Test
    public void airTwiceAsPoisonousHarmsTwiceAsSoon() {
        assertEquals(15, firstHarmfulSecond(2.0D));
    }

    /** red-witnessed: with {@code Poisoning#nextDose} at {@code if (toxicIndex >= 1.0D)} made
     *  {@code > 0.0D}, air just under its limit harmed at second 31. */
    @Test
    public void airBelowItsLimitAddsNothing() {
        assertEquals("air just under its limit never harms", -1, firstHarmfulSecond(0.99D));
    }

    /** red-witnessed: with {@code Poisoning#HALF_LIFE_SECONDS} at {@code 300.0D;} made {@code 60.0D;},
     *  five minutes left 1.875 of a 60 dose. */
    @Test
    public void cleanAirClearsHalfADoseInFiveMinutes() {
        double dose = 60.0D;
        for (int second = 0; second < 300; second++) {
            dose = Poisoning.nextDose(dose, 0.0D);
        }
        assertEquals("five minutes of clean air halve the dose", 30.0D, dose, 1e-6D);
    }

    /** red-witnessed: with {@code Poisoning#damageFor} at {@code (float) (dose / DOSE_THRESHOLD)}
     *  made {@code 1.0F}, twice the dose harmed 1.0 a second, the same as the threshold. */
    @Test
    public void aHeavierDoseHarmsHarder() {
        float atThreshold = Poisoning.damageFor(30.0D);
        assertTrue("the threshold dose harms: " + atThreshold, atThreshold > 0.0F);
        assertEquals("twice the dose, twice the harm", 2.0F * atThreshold, Poisoning.damageFor(60.0D), 1e-6F);
    }
}
