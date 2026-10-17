package dev.stannismod.stellurgy.test.unit;

import dev.stannismod.stellurgy.atmosphere.hazard.Poisoning;
import dev.stannismod.stellurgy.test.contract.RedWitness;
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

    @RedWitness(member = "Poisoning#damageFor", at = "dose >= DOSE_THRESHOLD ?", replacedBy = "dose >= 20.0D ?",
            fails = "expected:<30> but was:<20>", on = "2026-10-17")
    @Test
    public void airExactlyAtItsLimitHarmsFromTheThirtiethSecondAndNotBefore() {
        assertEquals(30, firstHarmfulSecond(1.0D));
    }

    @RedWitness(member = "Poisoning#nextDose", at = "return dose + toxicIndex;", replacedBy = "return dose + 1.0D;",
            fails = "expected:<15> but was:<30>", on = "2026-10-17")
    @Test
    public void airTwiceAsPoisonousHarmsTwiceAsSoon() {
        assertEquals(15, firstHarmfulSecond(2.0D));
    }

    @RedWitness(member = "Poisoning#nextDose", at = "if (toxicIndex >= 1.0D)", replacedBy = "if (toxicIndex > 0.0D)",
            fails = "air just under its limit never harms", on = "2026-10-17")
    @Test
    public void airBelowItsLimitAddsNothing() {
        assertEquals("air just under its limit never harms", -1, firstHarmfulSecond(0.99D));
    }

    @RedWitness(member = "Poisoning#nextDose", at = "1.0D / HALF_LIFE_SECONDS", replacedBy = "1.0D / 60.0D",
            fails = "five minutes of clean air halve the dose", on = "2026-10-17")
    @Test
    public void cleanAirClearsHalfADoseInFiveMinutes() {
        double dose = 60.0D;
        for (int second = 0; second < 300; second++) {
            dose = Poisoning.nextDose(dose, 0.0D);
        }
        assertEquals("five minutes of clean air halve the dose", 30.0D, dose, 1e-6D);
    }

    @RedWitness(member = "Poisoning#damageFor", at = "(float) (dose / DOSE_THRESHOLD)", replacedBy = "1.0F",
            fails = "twice the dose, twice the harm", on = "2026-10-17")
    @Test
    public void aHeavierDoseHarmsHarder() {
        float atThreshold = Poisoning.damageFor(30.0D);
        assertTrue("the threshold dose harms: " + atThreshold, atThreshold > 0.0F);
        assertEquals("twice the dose, twice the harm", 2.0F * atThreshold, Poisoning.damageFor(60.0D), 1e-6F);
    }
}
