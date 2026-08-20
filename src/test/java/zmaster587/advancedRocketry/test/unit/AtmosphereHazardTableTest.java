package zmaster587.advancedRocketry.test.unit;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.BeforeClass;
import org.junit.Test;

import zmaster587.advancedRocketry.api.IAtmosphere;
import zmaster587.advancedRocketry.api.atmosphere.AtmosphereHazard;
import zmaster587.advancedRocketry.atmosphere.AtmosphereType;
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

    private static HazardExposure of(IAtmosphere atmosphere) {
        return AtmosphereHazards.exposureOf(atmosphere);
    }

    private static Set<AtmosphereHazard> hazardsOf(IAtmosphere atmosphere) {
        return of(atmosphere).hazards();
    }

    /** The single row of a one-hazard atmosphere, or the row raising this hazard where several do. */
    private static HazardEffect row(IAtmosphere atmosphere, AtmosphereHazard hazard) {
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
        assertTrue("air is what raises nothing", of(AtmosphereType.AIR).isEmpty());
        assertTrue("and so is pressurised air", of(AtmosphereType.PRESSURIZEDAIR).isEmpty());
    }

    @Test
    public void eachNamedAtmosphereRaisesWhatItAlwaysDid() {
        assertEquals(EnumSet.of(AtmosphereHazard.DECOMPRESSION), hazardsOf(AtmosphereType.VACUUM));
        assertEquals(EnumSet.of(AtmosphereHazard.SUFFOCATION), hazardsOf(AtmosphereType.NOO2));
        assertEquals(EnumSet.of(AtmosphereHazard.SUFFOCATION), hazardsOf(AtmosphereType.LOWOXYGEN));
        assertEquals(EnumSet.of(AtmosphereHazard.OXYGEN_TOXICITY), hazardsOf(AtmosphereType.HIGHOXYGEN));
        assertEquals(EnumSet.of(AtmosphereHazard.PRESSURE), hazardsOf(AtmosphereType.HIGHPRESSURE));
        assertEquals(EnumSet.of(AtmosphereHazard.PRESSURE), hazardsOf(AtmosphereType.SUPERHIGHPRESSURE));
        assertEquals(EnumSet.of(AtmosphereHazard.HEAT), hazardsOf(AtmosphereType.VERYHOT));
        assertEquals(EnumSet.of(AtmosphereHazard.HEAT), hazardsOf(AtmosphereType.SUPERHEATED));

        // The four that were spelled out as classes are the products they always were.
        assertEquals(EnumSet.of(AtmosphereHazard.SUFFOCATION, AtmosphereHazard.PRESSURE),
                hazardsOf(AtmosphereType.HIGHPRESSURENOO2));
        assertEquals(EnumSet.of(AtmosphereHazard.SUFFOCATION, AtmosphereHazard.PRESSURE),
                hazardsOf(AtmosphereType.SUPERHIGHPRESSURENOO2));
        assertEquals(EnumSet.of(AtmosphereHazard.SUFFOCATION, AtmosphereHazard.HEAT),
                hazardsOf(AtmosphereType.VERYHOTNOO2));
        assertEquals(EnumSet.of(AtmosphereHazard.SUFFOCATION, AtmosphereHazard.HEAT),
                hazardsOf(AtmosphereType.SUPERHEATEDNOO2));
    }

    // ─── what a suit must cover, and what it spends ────────────────────────────────────────────

    @Test
    public void aSealedFaceIsEnoughOnlyWhereTheHarmIsWhatYouBreathe() {
        assertFalse("thin air is a breathing problem", of(AtmosphereType.LOWOXYGEN).needsFullSuit());
        assertFalse("so is airless air", of(AtmosphereType.NOO2).needsFullSuit());
        assertFalse("so is too much oxygen", of(AtmosphereType.HIGHOXYGEN).needsFullSuit());

        assertTrue("a vacuum is not", of(AtmosphereType.VACUUM).needsFullSuit());
        assertTrue("nor is depth", of(AtmosphereType.HIGHPRESSURE).needsFullSuit());
        assertTrue("nor is heat", of(AtmosphereType.VERYHOT).needsFullSuit());

        // And where both are true the stricter one decides — which is what the four product
        // classes each said by hand.
        assertTrue("suffocating AND crushed still needs the whole suit",
                of(AtmosphereType.HIGHPRESSURENOO2).needsFullSuit());
        assertTrue("suffocating AND cooking too",
                of(AtmosphereType.SUPERHEATEDNOO2).needsFullSuit());
    }

    @Test
    public void aSuitSpendsItsTankOnlyWhereThereIsNoOxidiserToConcentrate() {
        assertTrue("nothing outside to work with", of(AtmosphereType.VACUUM).needsSuppliedOxygen());
        assertTrue(of(AtmosphereType.NOO2).needsSuppliedOxygen());
        assertTrue(of(AtmosphereType.HIGHPRESSURENOO2).needsSuppliedOxygen());
        assertTrue(of(AtmosphereType.SUPERHEATEDNOO2).needsSuppliedOxygen());

        // Thin air is still air: the extractor works, and the tank is untouched. This is what
        // makes running out of air a hazard of airless worlds rather than of stale rooms.
        assertFalse("thin air can be concentrated", of(AtmosphereType.LOWOXYGEN).needsSuppliedOxygen());
        assertFalse(of(AtmosphereType.HIGHOXYGEN).needsSuppliedOxygen());
        assertFalse(of(AtmosphereType.HIGHPRESSURE).needsSuppliedOxygen());
        assertFalse(of(AtmosphereType.VERYHOT).needsSuppliedOxygen());
    }

    @Test
    public void theWarningNamesTheMostUrgentThingWrong() {
        assertEquals("msg.noOxygen", of(AtmosphereType.VACUUM).messageKey());
        assertEquals("msg.noOxygen", of(AtmosphereType.LOWOXYGEN).messageKey());
        assertEquals("msg.highOxygen", of(AtmosphereType.HIGHOXYGEN).messageKey());
        assertEquals("msg.tooDense", of(AtmosphereType.HIGHPRESSURE).messageKey());
        assertEquals("msg.muchTooDense", of(AtmosphereType.SUPERHIGHPRESSURE).messageKey());
        assertEquals("msg.tooHot", of(AtmosphereType.VERYHOT).messageKey());

        // A scorching room with nothing to breathe warns about the air, not the heat — which is
        // what its hand-written class said, and it is the more urgent of the two.
        assertEquals("msg.noOxygen", of(AtmosphereType.SUPERHEATEDNOO2).messageKey());
        assertEquals("msg.noOxygen", of(AtmosphereType.HIGHPRESSURENOO2).messageKey());
    }

    // ─── the numbers, as they were ─────────────────────────────────────────────────────────────

    @Test
    public void theRungsKeepTheirPeriodsAndTheirSeverities() {
        assertEquals("suffocating outright acts twice as often as merely running short",
                true, row(AtmosphereType.NOO2, AtmosphereHazard.SUFFOCATION).firesOn(10L));
        assertFalse(row(AtmosphereType.LOWOXYGEN, AtmosphereHazard.SUFFOCATION).firesOn(10L));
        assertTrue(row(AtmosphereType.LOWOXYGEN, AtmosphereHazard.SUFFOCATION).firesOn(20L));

        assertEquals("oxygen toxicity is the slowest of them", false,
                row(AtmosphereType.HIGHOXYGEN, AtmosphereHazard.OXYGEN_TOXICITY).firesOn(20L));
        assertTrue(row(AtmosphereType.HIGHOXYGEN, AtmosphereHazard.OXYGEN_TOXICITY).firesOn(40L));

        assertEquals("the two heat rungs differ by their damage and nothing else", 1,
                row(AtmosphereType.VERYHOT, AtmosphereHazard.HEAT).damageAmount());
        assertEquals(4, row(AtmosphereType.SUPERHEATED, AtmosphereHazard.HEAT).damageAmount());

        assertEquals("and the two suffocation rungs by how hard they hit", 4,
                row(AtmosphereType.NOO2, AtmosphereHazard.SUFFOCATION).slowness());
        assertEquals(2, row(AtmosphereType.LOWOXYGEN, AtmosphereHazard.SUFFOCATION).slowness());
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
                row(AtmosphereType.VERYHOT, AtmosphereHazard.HEAT).ignites());
        assertTrue(row(AtmosphereType.SUPERHEATED, AtmosphereHazard.HEAT).ignites());

        assertFalse("and the same heat with no oxygen does not — deliberately preserved",
                row(AtmosphereType.VERYHOTNOO2, AtmosphereHazard.HEAT).ignites());
        assertFalse(row(AtmosphereType.SUPERHEATEDNOO2, AtmosphereHazard.HEAT).ignites());
    }

    @Test
    public void theDeepestRungInjuresOnlyWhereTheAirIsBreathable() {
        assertEquals("crushing breathable air draws blood", 1,
                row(AtmosphereType.SUPERHIGHPRESSURE, AtmosphereHazard.PRESSURE).damageAmount());
        assertEquals("crushing airless air does not — deliberately preserved", 0,
                row(AtmosphereType.SUPERHIGHPRESSURENOO2, AtmosphereHazard.PRESSURE).damageAmount());
    }

    @Test
    public void narcosisBelongsToTheAirlessDepthsAlone() {
        assertTrue("the airless deep takes your jump — deliberately preserved",
                row(AtmosphereType.SUPERHIGHPRESSURENOO2, AtmosphereHazard.PRESSURE).narcosis());
        assertFalse("and the breathable deep does not",
                row(AtmosphereType.SUPERHIGHPRESSURE, AtmosphereHazard.PRESSURE).narcosis());
    }

    @Test
    public void suffocatingUnderPressureIsQueasierThanSuffocatingAnywhereElse() {
        assertEquals("depth makes suffocation more nauseating — deliberately preserved", 2,
                row(AtmosphereType.HIGHPRESSURENOO2, AtmosphereHazard.SUFFOCATION).nausea());
        assertEquals(2, row(AtmosphereType.SUPERHIGHPRESSURENOO2, AtmosphereHazard.SUFFOCATION).nausea());
        assertEquals("while heat does not", 1,
                row(AtmosphereType.VERYHOTNOO2, AtmosphereHazard.SUFFOCATION).nausea());
        assertEquals(1, row(AtmosphereType.NOO2, AtmosphereHazard.SUFFOCATION).nausea());
    }

    @Test
    public void everyHazardTheModelDeclaresIsRaisedBySomething() {
        // The other half of "no storage without a consumer": a kind of harm nothing can inflict is
        // a row in an enum pretending to be a mechanic.
        List<AtmosphereHazard> unraised = new ArrayList<>();
        for (AtmosphereHazard hazard : AtmosphereHazard.values()) {
            boolean raised = false;
            for (IAtmosphere atmosphere : new IAtmosphere[]{
                    AtmosphereType.VACUUM, AtmosphereType.NOO2, AtmosphereType.LOWOXYGEN,
                    AtmosphereType.HIGHOXYGEN, AtmosphereType.HIGHPRESSURE,
                    AtmosphereType.SUPERHIGHPRESSURE, AtmosphereType.VERYHOT,
                    AtmosphereType.SUPERHEATED, AtmosphereType.HIGHPRESSURENOO2,
                    AtmosphereType.SUPERHIGHPRESSURENOO2, AtmosphereType.VERYHOTNOO2,
                    AtmosphereType.SUPERHEATEDNOO2}) {
                raised |= hazardsOf(atmosphere).contains(hazard);
            }
            if (!raised) {
                unraised.add(hazard);
            }
        }
        assertTrue("nothing in the game can inflict " + unraised, unraised.isEmpty());
    }
}
