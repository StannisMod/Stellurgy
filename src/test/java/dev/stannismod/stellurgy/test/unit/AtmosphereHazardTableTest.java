package dev.stannismod.stellurgy.test.unit;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.BeforeClass;
import org.junit.Test;

import dev.stannismod.stellurgy.api.atmosphere.Atmosphere;
import dev.stannismod.stellurgy.api.atmosphere.AtmosphereHazard;
import dev.stannismod.stellurgy.atmosphere.hazard.AtmosphereHazards;
import dev.stannismod.stellurgy.atmosphere.hazard.HazardEffect;
import dev.stannismod.stellurgy.atmosphere.hazard.HazardExposure;
import dev.stannismod.stellurgy.test.MinecraftBootstrap;

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

    /**
     * <p>red-witnessed: one inversion per verdict, 2026-09-30, each a row given to an air atmosphere in
     * the table at {@code AtmosphereHazards:146}. AIR: "air is what raises nothing". PRESSURIZED AIR:
     * "and so is pressurised air".</p>
     */
    @Test
    public void breathableAirDoesNothingToAnybody() {
        assertTrue("air is what raises nothing", of(Atmosphere.AIR).isEmpty());
        assertTrue("and so is pressurised air", of(Atmosphere.PRESSURIZEDAIR).isEmpty());
    }

    /**
     * <p>red-witnessed: one inversion per verdict, 2026-09-30, each a change to that atmosphere's rows in
     * {@code AtmosphereHazards:133-145}. VACUUM ({@code :133}, given suffocation instead):
     * "expected:&lt;[DECOMPRESSION]&gt; but was:&lt;[SUFFOCATION]&gt;". NOO2 ({@code :134}, a pressure
     * row added): "expected:&lt;[SUFFOCATION]&gt; but was:&lt;[SUFFOCATION, PRESSURE]&gt;". LOWOXYGEN
     * ({@code :135}, a pressure row added): the same text. HIGHOXYGEN ({@code :136}, a pressure row
     * added): "expected:&lt;[OXYGEN_TOXICITY]&gt; but was:&lt;[OXYGEN_TOXICITY, PRESSURE]&gt;".
     * HIGHPRESSURE ({@code :137}, given oxygen toxicity instead): "expected:&lt;[PRESSURE]&gt; but
     * was:&lt;[OXYGEN_TOXICITY]&gt;". SUPERHIGHPRESSURE ({@code :138}, a heat row added):
     * "expected:&lt;[PRESSURE]&gt; but was:&lt;[PRESSURE, HEAT]&gt;". VERYHOT ({@code :139}, given
     * oxygen toxicity instead): "expected:&lt;[HEAT]&gt; but was:&lt;[OXYGEN_TOXICITY]&gt;".
     * SUPERHEATED ({@code :140}, a pressure row added): "expected:&lt;[HEAT]&gt; but was:&lt;[PRESSURE,
     * HEAT]&gt;". HIGHPRESSURENOO2 ({@code :141}, a heat row added): "expected:&lt;[SUFFOCATION,
     * PRESSURE]&gt; but was:&lt;[SUFFOCATION, PRESSURE, HEAT]&gt;". SUPERHIGHPRESSURENOO2
     * ({@code :142}, a heat row added): the same text. VERYHOTNOO2 ({@code :144}, a pressure row
     * added): "expected:&lt;[SUFFOCATION, HEAT]&gt; but was:&lt;[SUFFOCATION, PRESSURE, HEAT]&gt;".
     * SUPERHEATEDNOO2 ({@code :145}, a pressure row added): the same text.</p>
     */
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

    /**
     * <p>red-witnessed: one inversion per verdict, 2026-09-30, each a change to that atmosphere's rows in
     * {@code AtmosphereHazards:133-145}. LOWOXYGEN ({@code :135}, a pressure row added): "thin air is a
     * breathing problem". NOO2 ({@code :134}, a pressure row added): "so is airless air". HIGHOXYGEN
     * ({@code :136}, a pressure row added): "so is too much oxygen". VACUUM ({@code :133}, given
     * suffocation instead): "a vacuum is not". HIGHPRESSURE ({@code :137}, given oxygen toxicity
     * instead): "nor is depth". VERYHOT ({@code :139}, given oxygen toxicity instead): "nor is heat".
     * HIGHPRESSURENOO2 ({@code :141}, its pressure row dropped): "suffocating AND crushed still needs
     * the whole suit". SUPERHEATEDNOO2 ({@code :145}, its heat row dropped): "suffocating AND cooking
     * too".</p>
     */
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

    /**
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. Six assertions carry no message and went
     * red as a bare AssertionError at their own line. VACUUM - {@code AtmosphereHazards:53} the
     * decompression row no longer supplying oxygen: "nothing outside to work with". NOO2 -
     * {@code :59} the suffocation row no longer supplying it. HIGHPRESSURENOO2 - {@code :69} the
     * dense-suffocation row no longer supplying it. SUPERHEATEDNOO2 - {@code :145} suffocating
     * through the thin-air row instead. LOWOXYGEN - {@code :75} the thin-air row supplying oxygen:
     * "thin air can be concentrated". HIGHOXYGEN - {@code :81} the toxicity row supplying it.
     * HIGHPRESSURE - {@code :87} the pressure row supplying it. VERYHOT - {@code :111} the heat row
     * supplying it.</p>
     */
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

    /**
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. VACUUM - {@code AtmosphereHazards:53}
     * the row's key changed: "expected:&lt;msg.[noOxygen]&gt; but was:&lt;msg.[vacuum]&gt;". LOWOXYGEN -
     * {@code :75}: "expected:&lt;msg.[noOxyge]n&gt; but was:&lt;msg.[thi]n&gt;". HIGHOXYGEN - {@code :82}:
     * "expected:&lt;msg.[highOxygen]&gt; but was:&lt;msg.[x]&gt;". HIGHPRESSURE - {@code :87}:
     * "expected:&lt;msg.[tooDense]&gt; but was:&lt;msg.[y]&gt;". SUPERHIGHPRESSURE - {@code :97}:
     * "expected:&lt;msg.[muchTooDense]&gt; but was:&lt;msg.[z]&gt;". VERYHOT - {@code :139} given oxygen
     * toxicity instead: "expected:&lt;msg.[tooHot]&gt; but was:&lt;msg.[highOxygen]&gt;".
     * SUPERHEATEDNOO2 - {@code HazardExposure:103} taking the LEAST severe hazard's message:
     * "expected:&lt;msg.[noOxygen]&gt; but was:&lt;msg.[tooHot]&gt;". HIGHPRESSURENOO2 -
     * {@code AtmosphereHazards:69} the dense-suffocation row's key changed:
     * "expected:&lt;msg.[noOxygen]&gt; but was:&lt;msg.[w]&gt;".</p>
     */
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

    /**
     * <p>red-witnessed: one inversion per verdict, 2026-09-30; the assertions without a message went red
     * as a bare AssertionError at their own line. NOO2 FIRES ON 10 - {@code AtmosphereHazards:57}
     * period 20: "suffocating outright acts twice as often as merely running short
     * expected:&lt;true&gt; but was:&lt;false&gt;". LOWOXYGEN NOT ON 10 - {@code :73} period 10.
     * LOWOXYGEN ON 20 - {@code :73} period 40. HIGHOXYGEN NOT ON 20 - {@code :79} period 20: "oxygen
     * toxicity is the slowest of them expected:&lt;false&gt; but was:&lt;true&gt;". HIGHOXYGEN ON 40 -
     * {@code :79} period 80. VERYHOT DAMAGE - {@code :110} damage 2: "the two heat rungs differ by their
     * damage and nothing else expected:&lt;1&gt; but was:&lt;2&gt;". SUPERHEATED DAMAGE - {@code :120}
     * damage 3: "expected:&lt;4&gt; but was:&lt;3&gt;". NOO2 SLOWNESS - {@code :59} slowness 3: "and
     * the two suffocation rungs by how hard they hit expected:&lt;4&gt; but was:&lt;3&gt;". LOWOXYGEN
     * SLOWNESS - {@code :75} slowness 3: "expected:&lt;2&gt; but was:&lt;3&gt;".</p>
     */
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

    /**
     * <p>red-witnessed: one inversion per verdict, 2026-09-30; the assertions without a message went red
     * as a bare AssertionError at their own line. VERYHOT - {@code AtmosphereHazards:111} the heat row
     * not igniting: "a hot breathable room sets you alight". SUPERHEATED - {@code :121} the searing row
     * not igniting. VERYHOTNOO2 - {@code :116} the airless heat row igniting: "and the same heat with
     * no oxygen does not — deliberately preserved". SUPERHEATEDNOO2 - {@code :126} the airless
     * searing row igniting.</p>
     */
    @Test
    public void hotAirIgnitesYouOnlyWhereYouCouldHaveBreathedIt() {
        assertTrue("a hot breathable room sets you alight",
                row(Atmosphere.VERYHOT, AtmosphereHazard.HEAT).ignites());
        assertTrue(row(Atmosphere.SUPERHEATED, AtmosphereHazard.HEAT).ignites());

        assertFalse("and the same heat with no oxygen does not — deliberately preserved",
                row(Atmosphere.VERYHOTNOO2, AtmosphereHazard.HEAT).ignites());
        assertFalse(row(Atmosphere.SUPERHEATEDNOO2, AtmosphereHazard.HEAT).ignites());
    }

    /**
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. BREATHABLE DEEP - {@code AtmosphereHazards:96}
     * damage 2: "crushing breathable air draws blood expected:&lt;1&gt; but was:&lt;2&gt;". AIRLESS
     * DEEP - {@code :101} given a damage source and an amount of 1: "crushing airless air does not —
     * deliberately preserved expected:&lt;0&gt; but was:&lt;1&gt;".</p>
     */
    @Test
    public void theDeepestRungInjuresOnlyWhereTheAirIsBreathable() {
        assertEquals("crushing breathable air draws blood", 1,
                row(Atmosphere.SUPERHIGHPRESSURE, AtmosphereHazard.PRESSURE).damageAmount());
        assertEquals("crushing airless air does not — deliberately preserved", 0,
                row(Atmosphere.SUPERHIGHPRESSURENOO2, AtmosphereHazard.PRESSURE).damageAmount());
    }

    /**
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. AIRLESS DEEP - {@code AtmosphereHazards:101}
     * narcosis off: "the airless deep takes your jump — deliberately preserved". BREATHABLE DEEP -
     * {@code :97} narcosis on: "and the breathable deep does not".</p>
     */
    @Test
    public void narcosisBelongsToTheAirlessDepthsAlone() {
        assertTrue("the airless deep takes your jump — deliberately preserved",
                row(Atmosphere.SUPERHIGHPRESSURENOO2, AtmosphereHazard.PRESSURE).narcosis());
        assertFalse("and the breathable deep does not",
                row(Atmosphere.SUPERHIGHPRESSURE, AtmosphereHazard.PRESSURE).narcosis());
    }

    /**
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. HIGHPRESSURENOO2 -
     * {@code AtmosphereHazards:69} nausea 1: "depth makes suffocation more nauseating — deliberately
     * preserved expected:&lt;2&gt; but was:&lt;1&gt;". SUPERHIGHPRESSURENOO2 - {@code :142} suffocating
     * through the plain row: "expected:&lt;2&gt; but was:&lt;1&gt;". VERYHOTNOO2 - {@code :144}
     * suffocating through the dense row: "while heat does not expected:&lt;1&gt; but was:&lt;2&gt;".
     * NOO2 - {@code :134} suffocating through the dense row: "expected:&lt;1&gt; but
     * was:&lt;2&gt;".</p>
     */
    @Test
    public void suffocatingUnderPressureIsQueasierThanSuffocatingAnywhereElse() {
        assertEquals("depth makes suffocation more nauseating — deliberately preserved", 2,
                row(Atmosphere.HIGHPRESSURENOO2, AtmosphereHazard.SUFFOCATION).nausea());
        assertEquals(2, row(Atmosphere.SUPERHIGHPRESSURENOO2, AtmosphereHazard.SUFFOCATION).nausea());
        assertEquals("while heat does not", 1,
                row(Atmosphere.VERYHOTNOO2, AtmosphereHazard.SUFFOCATION).nausea());
        assertEquals(1, row(Atmosphere.NOO2, AtmosphereHazard.SUFFOCATION).nausea());
    }

    /**
     * <p>red-witnessed: with {@code AtmosphereHazards:133} giving the vacuum suffocation instead of
     * decompression: "nothing in the game can inflict [DECOMPRESSION]", 2026-09-30.</p>
     */
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
