package zmaster587.advancedRocketry.test.unit;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.BeforeClass;
import org.junit.Test;

import zmaster587.advancedRocketry.api.atmosphere.Atmosphere;
import zmaster587.advancedRocketry.api.atmosphere.AtmosphereHazard;
import zmaster587.advancedRocketry.atmosphere.hazard.AtmosphereHazards;
import zmaster587.advancedRocketry.atmosphere.hazard.HazardEffect;
import zmaster587.advancedRocketry.atmosphere.hazard.HazardExposure;
import zmaster587.advancedRocketry.test.MinecraftBootstrap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The hazard table against the fourteen hand-written methods it replaced.
 *
 * <p><b>This is a characterisation test, and it is one on purpose.</b> The slice that produced the
 * table promised to change no behaviour: every number below was read out of the fourteen classes
 * before they were deleted, so an assertion failing here means a number MOVED, not that the design
 * is wrong. That is exactly the guard a refactor of this size needs, and it is the only place the
 * deliberate oddities carried across are written down as deliberate.</p>
 *
 * <p><b>What it cannot see</b>: whether the effects are actually applied to anybody. This reads the
 * table's description of itself. That damage lands, on the schedule the rows state, is the server
 * tier's job — the vacuum, suffocation and heat rungs each have a scenario there.</p>
 */
public class AtmosphereHazardTableTest {

    @BeforeClass
    public static void bootstrap() {
        MinecraftBootstrap.ensure();
    }

    private static HazardExposure of(Atmosphere atmosphere) {
        return AtmosphereHazards.exposureOf(atmosphere);
    }

    private static Set<AtmosphereHazard> hazardsOf(Atmosphere atmosphere) {
        return of(atmosphere).hazards();
    }

    /** The single row of a one-hazard atmosphere, or the row raising this hazard where several do. */
    private static HazardEffect row(Atmosphere atmosphere, AtmosphereHazard hazard) {
        for (HazardEffect candidate : of(atmosphere).rows()) {
            if (candidate.hazard() == hazard) {
                return candidate;
            }
        }
        throw new AssertionError(atmosphere + " raises no " + hazard);
    }

    // ─── the fourteen cells, as hazard sets ────────────────────────────────────────────────────

    @Test
    public void breathableAirDoesNothingToAnybody() {
        assertTrue("air is what raises nothing", of(Atmosphere.AIR).isEmpty());
        assertTrue("and so is pressurised air", of(Atmosphere.PRESSURIZEDAIR).isEmpty());
    }

    @Test
    public void eachNamedAtmosphereRaisesWhatItAlwaysDid() {
        assertEquals(EnumSet.of(AtmosphereHazard.DECOMPRESSION), hazardsOf(Atmosphere.VACUUM));
        assertEquals(EnumSet.of(AtmosphereHazard.SUFFOCATION), hazardsOf(Atmosphere.NOO2));
        assertEquals(EnumSet.of(AtmosphereHazard.SUFFOCATION), hazardsOf(Atmosphere.LOWOXYGEN));
        assertEquals(EnumSet.of(AtmosphereHazard.OXYGEN_TOXICITY), hazardsOf(Atmosphere.HIGHOXYGEN));
        assertEquals(EnumSet.of(AtmosphereHazard.PRESSURE), hazardsOf(Atmosphere.HIGHPRESSURE));
        assertEquals(EnumSet.of(AtmosphereHazard.PRESSURE), hazardsOf(Atmosphere.SUPERHIGHPRESSURE));
        assertEquals(EnumSet.of(AtmosphereHazard.HEAT), hazardsOf(Atmosphere.VERYHOT));
        assertEquals(EnumSet.of(AtmosphereHazard.HEAT), hazardsOf(Atmosphere.SUPERHEATED));

        // The four that were spelled out as classes are the products they always were.
        assertEquals(EnumSet.of(AtmosphereHazard.SUFFOCATION, AtmosphereHazard.PRESSURE),
                hazardsOf(Atmosphere.HIGHPRESSURENOO2));
        assertEquals(EnumSet.of(AtmosphereHazard.SUFFOCATION, AtmosphereHazard.PRESSURE),
                hazardsOf(Atmosphere.SUPERHIGHPRESSURENOO2));
        assertEquals(EnumSet.of(AtmosphereHazard.SUFFOCATION, AtmosphereHazard.HEAT),
                hazardsOf(Atmosphere.VERYHOTNOO2));
        assertEquals(EnumSet.of(AtmosphereHazard.SUFFOCATION, AtmosphereHazard.HEAT),
                hazardsOf(Atmosphere.SUPERHEATEDNOO2));
    }

    // ─── what a suit must cover, and what it spends ────────────────────────────────────────────

    @Test
    public void aSealedFaceIsEnoughOnlyWhereTheHarmIsWhatYouBreathe() {
        assertFalse("thin air is a breathing problem", of(Atmosphere.LOWOXYGEN).needsFullSuit());
        assertFalse("so is airless air", of(Atmosphere.NOO2).needsFullSuit());
        assertFalse("so is too much oxygen", of(Atmosphere.HIGHOXYGEN).needsFullSuit());

        assertTrue("a vacuum is not", of(Atmosphere.VACUUM).needsFullSuit());
        assertTrue("nor is depth", of(Atmosphere.HIGHPRESSURE).needsFullSuit());
        assertTrue("nor is heat", of(Atmosphere.VERYHOT).needsFullSuit());

        // And where both are true the stricter one decides — which is what the four product
        // classes each said by hand.
        assertTrue("suffocating AND crushed still needs the whole suit",
                of(Atmosphere.HIGHPRESSURENOO2).needsFullSuit());
        assertTrue("suffocating AND cooking too",
                of(Atmosphere.SUPERHEATEDNOO2).needsFullSuit());
    }

    @Test
    public void aSuitSpendsItsTankOnlyWhereThereIsNoOxidiserToConcentrate() {
        assertTrue("nothing outside to work with", of(Atmosphere.VACUUM).needsSuppliedOxygen());
        assertTrue(of(Atmosphere.NOO2).needsSuppliedOxygen());
        assertTrue(of(Atmosphere.HIGHPRESSURENOO2).needsSuppliedOxygen());
        assertTrue(of(Atmosphere.SUPERHEATEDNOO2).needsSuppliedOxygen());

        // Thin air is still air: the extractor works, and the tank is untouched. This is what
        // makes running out of air a hazard of airless worlds rather than of stale rooms.
        assertFalse("thin air can be concentrated", of(Atmosphere.LOWOXYGEN).needsSuppliedOxygen());
        assertFalse(of(Atmosphere.HIGHOXYGEN).needsSuppliedOxygen());
        assertFalse(of(Atmosphere.HIGHPRESSURE).needsSuppliedOxygen());
        assertFalse(of(Atmosphere.VERYHOT).needsSuppliedOxygen());
    }

    @Test
    public void theWarningNamesTheMostUrgentThingWrong() {
        assertEquals("msg.noOxygen", of(Atmosphere.VACUUM).messageKey());
        assertEquals("msg.noOxygen", of(Atmosphere.LOWOXYGEN).messageKey());
        assertEquals("msg.highOxygen", of(Atmosphere.HIGHOXYGEN).messageKey());
        assertEquals("msg.tooDense", of(Atmosphere.HIGHPRESSURE).messageKey());
        assertEquals("msg.muchTooDense", of(Atmosphere.SUPERHIGHPRESSURE).messageKey());
        assertEquals("msg.tooHot", of(Atmosphere.VERYHOT).messageKey());

        // A scorching room with nothing to breathe warns about the air, not the heat — which is
        // what its hand-written class said, and it is the more urgent of the two.
        assertEquals("msg.noOxygen", of(Atmosphere.SUPERHEATEDNOO2).messageKey());
        assertEquals("msg.noOxygen", of(Atmosphere.HIGHPRESSURENOO2).messageKey());
    }

    // ─── the numbers, as they were ─────────────────────────────────────────────────────────────

    @Test
    public void theRungsKeepTheirPeriodsAndTheirSeverities() {
        assertEquals("suffocating outright acts twice as often as merely running short",
                true, row(Atmosphere.NOO2, AtmosphereHazard.SUFFOCATION).firesOn(10L));
        assertFalse(row(Atmosphere.LOWOXYGEN, AtmosphereHazard.SUFFOCATION).firesOn(10L));
        assertTrue(row(Atmosphere.LOWOXYGEN, AtmosphereHazard.SUFFOCATION).firesOn(20L));

        assertEquals("oxygen toxicity is the slowest of them", false,
                row(Atmosphere.HIGHOXYGEN, AtmosphereHazard.OXYGEN_TOXICITY).firesOn(20L));
        assertTrue(row(Atmosphere.HIGHOXYGEN, AtmosphereHazard.OXYGEN_TOXICITY).firesOn(40L));

        assertEquals("the two heat rungs differ by their damage and nothing else", 1,
                row(Atmosphere.VERYHOT, AtmosphereHazard.HEAT).damageAmount());
        assertEquals(4, row(Atmosphere.SUPERHEATED, AtmosphereHazard.HEAT).damageAmount());

        assertEquals("and the two suffocation rungs by how hard they hit", 4,
                row(Atmosphere.NOO2, AtmosphereHazard.SUFFOCATION).slowness());
        assertEquals(2, row(Atmosphere.LOWOXYGEN, AtmosphereHazard.SUFFOCATION).slowness());
    }

    // ─── the disagreements carried across on purpose ───────────────────────────────────────────
    //
    // Each of these is a place where the fourteen classes disagreed with what composing their
    // hazards would produce. They were kept so the table changed nothing on the day it landed, and
    // each is a candidate for deletion on its own merits. A failure here means somebody removed one
    // — which may well be right, but it is a decision about the GAME and must be made as one.

    @Test
    public void hotAirIgnitesYouOnlyWhereYouCouldHaveBreathedIt() {
        assertTrue("a hot breathable room sets you alight",
                row(Atmosphere.VERYHOT, AtmosphereHazard.HEAT).ignites());
        assertTrue(row(Atmosphere.SUPERHEATED, AtmosphereHazard.HEAT).ignites());

        assertFalse("and the same heat with no oxygen does not — deliberately preserved",
                row(Atmosphere.VERYHOTNOO2, AtmosphereHazard.HEAT).ignites());
        assertFalse(row(Atmosphere.SUPERHEATEDNOO2, AtmosphereHazard.HEAT).ignites());
    }

    @Test
    public void theDeepestRungInjuresOnlyWhereTheAirIsBreathable() {
        assertEquals("crushing breathable air draws blood", 1,
                row(Atmosphere.SUPERHIGHPRESSURE, AtmosphereHazard.PRESSURE).damageAmount());
        assertEquals("crushing airless air does not — deliberately preserved", 0,
                row(Atmosphere.SUPERHIGHPRESSURENOO2, AtmosphereHazard.PRESSURE).damageAmount());
    }

    @Test
    public void narcosisBelongsToTheAirlessDepthsAlone() {
        assertTrue("the airless deep takes your jump — deliberately preserved",
                row(Atmosphere.SUPERHIGHPRESSURENOO2, AtmosphereHazard.PRESSURE).narcosis());
        assertFalse("and the breathable deep does not",
                row(Atmosphere.SUPERHIGHPRESSURE, AtmosphereHazard.PRESSURE).narcosis());
    }

    @Test
    public void suffocatingUnderPressureIsQueasierThanSuffocatingAnywhereElse() {
        assertEquals("depth makes suffocation more nauseating — deliberately preserved", 2,
                row(Atmosphere.HIGHPRESSURENOO2, AtmosphereHazard.SUFFOCATION).nausea());
        assertEquals(2, row(Atmosphere.SUPERHIGHPRESSURENOO2, AtmosphereHazard.SUFFOCATION).nausea());
        assertEquals("while heat does not", 1,
                row(Atmosphere.VERYHOTNOO2, AtmosphereHazard.SUFFOCATION).nausea());
        assertEquals(1, row(Atmosphere.NOO2, AtmosphereHazard.SUFFOCATION).nausea());
    }

    @Test
    public void everyHazardTheModelDeclaresIsRaisedBySomething() {
        // The other half of "no storage without a consumer": a kind of harm nothing can inflict is
        // a row in an enum pretending to be a mechanic.
        List<AtmosphereHazard> unraised = new ArrayList<>();
        for (AtmosphereHazard hazard : AtmosphereHazard.values()) {
            boolean raised = false;
            for (Atmosphere atmosphere : new Atmosphere[]{
                    Atmosphere.VACUUM, Atmosphere.NOO2, Atmosphere.LOWOXYGEN,
                    Atmosphere.HIGHOXYGEN, Atmosphere.HIGHPRESSURE,
                    Atmosphere.SUPERHIGHPRESSURE, Atmosphere.VERYHOT,
                    Atmosphere.SUPERHEATED, Atmosphere.HIGHPRESSURENOO2,
                    Atmosphere.SUPERHIGHPRESSURENOO2, Atmosphere.VERYHOTNOO2,
                    Atmosphere.SUPERHEATEDNOO2}) {
                raised |= hazardsOf(atmosphere).contains(hazard);
            }
            if (!raised) {
                unraised.add(hazard);
            }
        }
        assertTrue("nothing in the game can inflict " + unraised, unraised.isEmpty());
    }
}
