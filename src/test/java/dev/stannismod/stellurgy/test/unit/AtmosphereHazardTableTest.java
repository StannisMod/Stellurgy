package dev.stannismod.stellurgy.test.unit;

import dev.stannismod.stellurgy.api.atmosphere.Atmosphere;
import dev.stannismod.stellurgy.api.atmosphere.AtmosphereHazard;
import dev.stannismod.stellurgy.atmosphere.hazard.AtmosphereHazards;
import dev.stannismod.stellurgy.atmosphere.hazard.HazardEffect;
import dev.stannismod.stellurgy.atmosphere.hazard.HazardExposure;
import dev.stannismod.stellurgy.test.MinecraftBootstrap;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

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

    @RedWitness(verdict = "air", member = "AtmosphereHazards#byAtmosphere",
            at = "put(table, Atmosphere.SUPERHEATEDNOO2, SUFFOCATION, SEARING_HEAT_WHERE_AIRLESS);",
            replacedBy = "put(table, Atmosphere.SUPERHEATEDNOO2, SUFFOCATION, SEARING_HEAT_WHERE_AIRLESS);"
                    + " put(table, Atmosphere.AIR, HEAT);",
            fails = "air is what raises nothing", on = "2026-10-17")
    @RedWitness(verdict = "pressurised air", member = "AtmosphereHazards#byAtmosphere",
            at = "put(table, Atmosphere.SUPERHEATEDNOO2, SUFFOCATION, SEARING_HEAT_WHERE_AIRLESS);",
            replacedBy = "put(table, Atmosphere.SUPERHEATEDNOO2, SUFFOCATION, SEARING_HEAT_WHERE_AIRLESS);"
                    + " put(table, Atmosphere.PRESSURIZEDAIR, HEAT);",
            fails = "and so is pressurised air", on = "2026-10-17")
    @Test
    public void breathableAirDoesNothingToAnybody() {
        assertTrue("air is what raises nothing", of(Atmosphere.AIR).isEmpty());
        assertTrue("and so is pressurised air", of(Atmosphere.PRESSURIZEDAIR).isEmpty());
    }

    @RedWitness(verdict = "VACUUM", member = "AtmosphereHazards#byAtmosphere",
            at = "put(table, Atmosphere.VACUUM, DECOMPRESSION);", replacedBy = "put(table, Atmosphere.VACUUM, SUFFOCATION);",
            fails = "expected:<[DECOMPRESSION]> but was:<[SUFFOCATION]>", on = "2026-10-17")
    @RedWitness(verdict = "NOO2", member = "AtmosphereHazards#byAtmosphere",
            at = "put(table, Atmosphere.NOO2, SUFFOCATION);", replacedBy = "put(table, Atmosphere.NOO2, SUFFOCATION, PRESSURE);",
            fails = "expected:<[SUFFOCATION]> but was:<[SUFFOCATION, PRESSURE]>", on = "2026-10-17")
    @RedWitness(verdict = "LOWOXYGEN", member = "AtmosphereHazards#byAtmosphere",
            at = "put(table, Atmosphere.LOWOXYGEN, THIN_AIR);",
            replacedBy = "put(table, Atmosphere.LOWOXYGEN, THIN_AIR, PRESSURE);",
            fails = "expected:<[SUFFOCATION]> but was:<[SUFFOCATION, PRESSURE]>", on = "2026-10-17")
    @RedWitness(verdict = "HIGHOXYGEN", member = "AtmosphereHazards#byAtmosphere",
            at = "put(table, Atmosphere.HIGHOXYGEN, OXYGEN_TOXICITY);",
            replacedBy = "put(table, Atmosphere.HIGHOXYGEN, OXYGEN_TOXICITY, PRESSURE);",
            fails = "expected:<[OXYGEN_TOXICITY]> but was:<[OXYGEN_TOXICITY, PRESSURE]>", on = "2026-10-17")
    @RedWitness(verdict = "HIGHPRESSURE", member = "AtmosphereHazards#byAtmosphere",
            at = "put(table, Atmosphere.HIGHPRESSURE, PRESSURE);",
            replacedBy = "put(table, Atmosphere.HIGHPRESSURE, OXYGEN_TOXICITY);",
            fails = "expected:<[PRESSURE]> but was:<[OXYGEN_TOXICITY]>", on = "2026-10-17")
    @RedWitness(verdict = "SUPERHIGHPRESSURE", member = "AtmosphereHazards#byAtmosphere",
            at = "put(table, Atmosphere.SUPERHIGHPRESSURE, CRUSHING_PRESSURE_WHERE_BREATHABLE);",
            replacedBy = "put(table, Atmosphere.SUPERHIGHPRESSURE, CRUSHING_PRESSURE_WHERE_BREATHABLE, HEAT);",
            fails = "expected:<[PRESSURE]> but was:<[PRESSURE, HEAT]>", on = "2026-10-17")
    @RedWitness(verdict = "VERYHOT", member = "AtmosphereHazards#byAtmosphere",
            at = "put(table, Atmosphere.VERYHOT, HEAT);", replacedBy = "put(table, Atmosphere.VERYHOT, OXYGEN_TOXICITY);",
            fails = "expected:<[HEAT]> but was:<[OXYGEN_TOXICITY]>", on = "2026-10-17")
    @RedWitness(verdict = "SUPERHEATED", member = "AtmosphereHazards#byAtmosphere",
            at = "put(table, Atmosphere.SUPERHEATED, SEARING_HEAT);",
            replacedBy = "put(table, Atmosphere.SUPERHEATED, SEARING_HEAT, PRESSURE);",
            fails = "expected:<[HEAT]> but was:<[PRESSURE, HEAT]>", on = "2026-10-17")
    @RedWitness(verdict = "HIGHPRESSURENOO2", member = "AtmosphereHazards#byAtmosphere",
            at = "put(table, Atmosphere.HIGHPRESSURENOO2, SUFFOCATION_WHERE_DENSE, PRESSURE);",
            replacedBy = "put(table, Atmosphere.HIGHPRESSURENOO2, SUFFOCATION_WHERE_DENSE, PRESSURE, HEAT);",
            fails = "expected:<[SUFFOCATION, PRESSURE]> but was:<[SUFFOCATION, PRESSURE, HEAT]>", on = "2026-10-17")
    @RedWitness(verdict = "SUPERHIGHPRESSURENOO2", member = "AtmosphereHazards#byAtmosphere",
            at = "put(table, Atmosphere.SUPERHIGHPRESSURENOO2, SUFFOCATION_WHERE_DENSE,",
            replacedBy = "put(table, Atmosphere.SUPERHIGHPRESSURENOO2, SUFFOCATION_WHERE_DENSE, HEAT,",
            fails = "expected:<[SUFFOCATION, PRESSURE]> but was:<[SUFFOCATION, PRESSURE, HEAT]>", on = "2026-10-17")
    @RedWitness(verdict = "VERYHOTNOO2", member = "AtmosphereHazards#byAtmosphere",
            at = "put(table, Atmosphere.VERYHOTNOO2, SUFFOCATION, HEAT_WHERE_AIRLESS);",
            replacedBy = "put(table, Atmosphere.VERYHOTNOO2, SUFFOCATION, HEAT_WHERE_AIRLESS, PRESSURE);",
            fails = "expected:<[SUFFOCATION, HEAT]> but was:<[SUFFOCATION, PRESSURE, HEAT]>", on = "2026-10-17")
    @RedWitness(verdict = "SUPERHEATEDNOO2", member = "AtmosphereHazards#byAtmosphere",
            at = "put(table, Atmosphere.SUPERHEATEDNOO2, SUFFOCATION, SEARING_HEAT_WHERE_AIRLESS);",
            replacedBy = "put(table, Atmosphere.SUPERHEATEDNOO2, SUFFOCATION, SEARING_HEAT_WHERE_AIRLESS, PRESSURE);",
            fails = "expected:<[SUFFOCATION, HEAT]> but was:<[SUFFOCATION, PRESSURE, HEAT]>", on = "2026-10-17")
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

    @RedWitness(verdict = "LOWOXYGEN", member = "AtmosphereHazards#byAtmosphere",
            at = "put(table, Atmosphere.LOWOXYGEN, THIN_AIR);",
            replacedBy = "put(table, Atmosphere.LOWOXYGEN, THIN_AIR, PRESSURE);",
            fails = "thin air is a breathing problem", on = "2026-10-17")
    @RedWitness(verdict = "NOO2", member = "AtmosphereHazards#byAtmosphere",
            at = "put(table, Atmosphere.NOO2, SUFFOCATION);", replacedBy = "put(table, Atmosphere.NOO2, SUFFOCATION, PRESSURE);",
            fails = "so is airless air", on = "2026-10-17")
    @RedWitness(verdict = "HIGHOXYGEN", member = "AtmosphereHazards#byAtmosphere",
            at = "put(table, Atmosphere.HIGHOXYGEN, OXYGEN_TOXICITY);",
            replacedBy = "put(table, Atmosphere.HIGHOXYGEN, OXYGEN_TOXICITY, PRESSURE);",
            fails = "so is too much oxygen", on = "2026-10-17")
    @RedWitness(verdict = "VACUUM", member = "AtmosphereHazards#byAtmosphere",
            at = "put(table, Atmosphere.VACUUM, DECOMPRESSION);", replacedBy = "put(table, Atmosphere.VACUUM, SUFFOCATION);",
            fails = "a vacuum is not", on = "2026-10-17")
    @RedWitness(verdict = "HIGHPRESSURE", member = "AtmosphereHazards#byAtmosphere",
            at = "put(table, Atmosphere.HIGHPRESSURE, PRESSURE);",
            replacedBy = "put(table, Atmosphere.HIGHPRESSURE, OXYGEN_TOXICITY);",
            fails = "nor is depth", on = "2026-10-17")
    @RedWitness(verdict = "VERYHOT", member = "AtmosphereHazards#byAtmosphere",
            at = "put(table, Atmosphere.VERYHOT, HEAT);", replacedBy = "put(table, Atmosphere.VERYHOT, OXYGEN_TOXICITY);",
            fails = "nor is heat", on = "2026-10-17")
    @RedWitness(verdict = "HIGHPRESSURENOO2", member = "AtmosphereHazards#byAtmosphere",
            at = "put(table, Atmosphere.HIGHPRESSURENOO2, SUFFOCATION_WHERE_DENSE, PRESSURE);",
            replacedBy = "put(table, Atmosphere.HIGHPRESSURENOO2, SUFFOCATION_WHERE_DENSE);",
            fails = "suffocating AND crushed still needs the whole suit", on = "2026-10-17")
    @RedWitness(verdict = "SUPERHEATEDNOO2", member = "AtmosphereHazards#byAtmosphere",
            at = "put(table, Atmosphere.SUPERHEATEDNOO2, SUFFOCATION, SEARING_HEAT_WHERE_AIRLESS);",
            replacedBy = "put(table, Atmosphere.SUPERHEATEDNOO2, SUFFOCATION);",
            fails = "suffocating AND cooking too", on = "2026-10-17")
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

    @RedWitness(verdict = "VACUUM", member = "AtmosphereHazards#byAtmosphere",
            at = "put(table, Atmosphere.VACUUM, DECOMPRESSION);",
            replacedBy = "put(table, Atmosphere.VACUUM, new HazardEffect(AtmosphereHazard.DECOMPRESSION, 10,"
                    + " AtmosphereHandler.vacuumDamage, () -> StellurgyConfiguration.getCurrentConfig().vacuumDamage,"
                    + " 4, 4, 1, false, false, true, false, \"msg.noOxygen\"));",
            fails = "nothing outside to work with", on = "2026-10-17")
    @RedWitness(verdict = "NOO2", member = "AtmosphereHazards#byAtmosphere",
            at = "put(table, Atmosphere.NOO2, SUFFOCATION);",
            replacedBy = "put(table, Atmosphere.NOO2, new HazardEffect(AtmosphereHazard.SUFFOCATION, 10,"
                    + " AtmosphereHandler.lowOxygenDamage, () -> 1, 4, 4, 1, false, false, true, false, \"msg.noOxygen\"));",
            fails = "null", on = "2026-10-17")
    @RedWitness(verdict = "HIGHPRESSURENOO2", member = "AtmosphereHazards#byAtmosphere",
            at = "put(table, Atmosphere.HIGHPRESSURENOO2, SUFFOCATION_WHERE_DENSE, PRESSURE);",
            replacedBy = "put(table, Atmosphere.HIGHPRESSURENOO2, new HazardEffect(AtmosphereHazard.SUFFOCATION, 10,"
                    + " AtmosphereHandler.lowOxygenDamage, () -> 1, 4, 4, 2, false, false, true, false, \"msg.noOxygen\"),"
                    + " PRESSURE);",
            fails = "null", on = "2026-10-17")
    @RedWitness(verdict = "SUPERHEATEDNOO2", member = "AtmosphereHazards#byAtmosphere",
            at = "put(table, Atmosphere.SUPERHEATEDNOO2, SUFFOCATION, SEARING_HEAT_WHERE_AIRLESS);",
            replacedBy = "put(table, Atmosphere.SUPERHEATEDNOO2, THIN_AIR, SEARING_HEAT_WHERE_AIRLESS);",
            fails = "null", on = "2026-10-17")
    @RedWitness(verdict = "LOWOXYGEN", member = "AtmosphereHazards#byAtmosphere",
            at = "put(table, Atmosphere.LOWOXYGEN, THIN_AIR);",
            replacedBy = "put(table, Atmosphere.LOWOXYGEN, new HazardEffect(AtmosphereHazard.SUFFOCATION, 20,"
                    + " AtmosphereHandler.lowOxygenDamage, () -> 1, 2, 2, HazardEffect.NONE, false, false, true, true,"
                    + " \"msg.noOxygen\"));",
            fails = "thin air can be concentrated", on = "2026-10-17")
    @RedWitness(verdict = "HIGHOXYGEN", member = "AtmosphereHazards#byAtmosphere",
            at = "put(table, Atmosphere.HIGHOXYGEN, OXYGEN_TOXICITY);",
            replacedBy = "put(table, Atmosphere.HIGHOXYGEN, new HazardEffect(AtmosphereHazard.OXYGEN_TOXICITY, 40,"
                    + " AtmosphereHandler.oxygenToxicityDamage, () -> 1, HazardEffect.NONE, HazardEffect.NONE,"
                    + " HazardEffect.NONE, false, false, true, true, \"msg.highOxygen\"));",
            fails = "null", on = "2026-10-17")
    @RedWitness(verdict = "HIGHPRESSURE", member = "AtmosphereHazards#byAtmosphere",
            at = "put(table, Atmosphere.HIGHPRESSURE, PRESSURE);",
            replacedBy = "put(table, Atmosphere.HIGHPRESSURE, new HazardEffect(AtmosphereHazard.PRESSURE, 20, null, null,"
                    + " 2, 2, HazardEffect.NONE, false, false, false, true, \"msg.tooDense\"));",
            fails = "null", on = "2026-10-17")
    @RedWitness(verdict = "VERYHOT", member = "AtmosphereHazards#byAtmosphere",
            at = "put(table, Atmosphere.VERYHOT, HEAT);",
            replacedBy = "put(table, Atmosphere.VERYHOT, new HazardEffect(AtmosphereHazard.HEAT, 20,"
                    + " AtmosphereHandler.heatDamage, () -> 1, 3, HazardEffect.NONE, HazardEffect.NONE, false, true, false,"
                    + " true, \"msg.tooHot\"));",
            fails = "null", on = "2026-10-17")
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

    @RedWitness(verdict = "VACUUM", member = "AtmosphereHazards#byAtmosphere",
            at = "put(table, Atmosphere.VACUUM, DECOMPRESSION);",
            replacedBy = "put(table, Atmosphere.VACUUM, new HazardEffect(AtmosphereHazard.DECOMPRESSION, 10,"
                    + " AtmosphereHandler.vacuumDamage, () -> StellurgyConfiguration.getCurrentConfig().vacuumDamage,"
                    + " 4, 4, 1, false, false, true, true, \"msg.vacuum\"));",
            fails = "expected:<msg.[noOxygen]> but was:<msg.[vacuum]>", on = "2026-10-17")
    @RedWitness(verdict = "LOWOXYGEN", member = "AtmosphereHazards#byAtmosphere",
            at = "put(table, Atmosphere.LOWOXYGEN, THIN_AIR);",
            replacedBy = "put(table, Atmosphere.LOWOXYGEN, new HazardEffect(AtmosphereHazard.SUFFOCATION, 20,"
                    + " AtmosphereHandler.lowOxygenDamage, () -> 1, 2, 2, HazardEffect.NONE, false, false, true, false,"
                    + " \"msg.thin\"));",
            fails = "expected:<msg.[noOxyge]n> but was:<msg.[thi]n>", on = "2026-10-17")
    @RedWitness(verdict = "HIGHOXYGEN", member = "AtmosphereHazards#byAtmosphere",
            at = "put(table, Atmosphere.HIGHOXYGEN, OXYGEN_TOXICITY);",
            replacedBy = "put(table, Atmosphere.HIGHOXYGEN, new HazardEffect(AtmosphereHazard.OXYGEN_TOXICITY, 40,"
                    + " AtmosphereHandler.oxygenToxicityDamage, () -> 1, HazardEffect.NONE, HazardEffect.NONE,"
                    + " HazardEffect.NONE, false, false, true, false, \"msg.x\"));",
            fails = "expected:<msg.[highOxygen]> but was:<msg.[x]>", on = "2026-10-17")
    @RedWitness(verdict = "HIGHPRESSURE", member = "AtmosphereHazards#byAtmosphere",
            at = "put(table, Atmosphere.HIGHPRESSURE, PRESSURE);",
            replacedBy = "put(table, Atmosphere.HIGHPRESSURE, new HazardEffect(AtmosphereHazard.PRESSURE, 20, null, null,"
                    + " 2, 2, HazardEffect.NONE, false, false, false, false, \"msg.y\"));",
            fails = "expected:<msg.[tooDense]> but was:<msg.[y]>", on = "2026-10-17")
    @RedWitness(verdict = "SUPERHIGHPRESSURE", member = "AtmosphereHazards#byAtmosphere",
            at = "put(table, Atmosphere.SUPERHIGHPRESSURE, CRUSHING_PRESSURE_WHERE_BREATHABLE);",
            replacedBy = "put(table, Atmosphere.SUPERHIGHPRESSURE, new HazardEffect(AtmosphereHazard.PRESSURE, 20,"
                    + " AtmosphereHandler.oxygenToxicityDamage, () -> 1, 3, 3, HazardEffect.NONE, false, false, true,"
                    + " false, \"msg.z\"));",
            fails = "expected:<msg.[muchTooDense]> but was:<msg.[z]>", on = "2026-10-17")
    @RedWitness(verdict = "VERYHOT", member = "AtmosphereHazards#byAtmosphere",
            at = "put(table, Atmosphere.VERYHOT, HEAT);", replacedBy = "put(table, Atmosphere.VERYHOT, OXYGEN_TOXICITY);",
            fails = "expected:<msg.[tooHot]> but was:<msg.[highOxygen]>", on = "2026-10-17")
    @RedWitness(verdict = "SUPERHEATEDNOO2", member = "HazardExposure#messageKey",
            at = "row.hazard().ordinal() < worst.hazard().ordinal()",
            replacedBy = "row.hazard().ordinal() > worst.hazard().ordinal()",
            fails = "expected:<msg.[noOxygen]> but was:<msg.[tooHot]>", on = "2026-10-17")
    @RedWitness(verdict = "HIGHPRESSURENOO2", member = "AtmosphereHazards#byAtmosphere",
            at = "put(table, Atmosphere.HIGHPRESSURENOO2, SUFFOCATION_WHERE_DENSE, PRESSURE);",
            replacedBy = "put(table, Atmosphere.HIGHPRESSURENOO2, new HazardEffect(AtmosphereHazard.SUFFOCATION, 10,"
                    + " AtmosphereHandler.lowOxygenDamage, () -> 1, 4, 4, 2, false, false, true, true, \"msg.w\"),"
                    + " PRESSURE);",
            fails = "expected:<msg.[noOxygen]> but was:<msg.[w]>", on = "2026-10-17")
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

    @RedWitness(verdict = "NOO2 fires on 10", member = "AtmosphereHazards#byAtmosphere",
            at = "put(table, Atmosphere.NOO2, SUFFOCATION);",
            replacedBy = "put(table, Atmosphere.NOO2, new HazardEffect(AtmosphereHazard.SUFFOCATION, 20,"
                    + " AtmosphereHandler.lowOxygenDamage, () -> 1, 4, 4, 1, false, false, true, true, \"msg.noOxygen\"));",
            fails = "suffocating outright acts twice as often as merely running short expected:<true> but was:<false>",
            on = "2026-10-17")
    @RedWitness(verdict = "LOWOXYGEN not on 10", member = "AtmosphereHazards#byAtmosphere",
            at = "put(table, Atmosphere.LOWOXYGEN, THIN_AIR);",
            replacedBy = "put(table, Atmosphere.LOWOXYGEN, new HazardEffect(AtmosphereHazard.SUFFOCATION, 10,"
                    + " AtmosphereHandler.lowOxygenDamage, () -> 1, 2, 2, HazardEffect.NONE, false, false, true, false,"
                    + " \"msg.noOxygen\"));",
            fails = "null", on = "2026-10-17")
    @RedWitness(verdict = "LOWOXYGEN on 20", member = "AtmosphereHazards#byAtmosphere",
            at = "put(table, Atmosphere.LOWOXYGEN, THIN_AIR);",
            replacedBy = "put(table, Atmosphere.LOWOXYGEN, new HazardEffect(AtmosphereHazard.SUFFOCATION, 40,"
                    + " AtmosphereHandler.lowOxygenDamage, () -> 1, 2, 2, HazardEffect.NONE, false, false, true, false,"
                    + " \"msg.noOxygen\"));",
            fails = "null", on = "2026-10-17")
    @RedWitness(verdict = "HIGHOXYGEN not on 20", member = "AtmosphereHazards#byAtmosphere",
            at = "put(table, Atmosphere.HIGHOXYGEN, OXYGEN_TOXICITY);",
            replacedBy = "put(table, Atmosphere.HIGHOXYGEN, new HazardEffect(AtmosphereHazard.OXYGEN_TOXICITY, 20,"
                    + " AtmosphereHandler.oxygenToxicityDamage, () -> 1, HazardEffect.NONE, HazardEffect.NONE,"
                    + " HazardEffect.NONE, false, false, true, false, \"msg.highOxygen\"));",
            fails = "oxygen toxicity is the slowest of them expected:<false> but was:<true>", on = "2026-10-17")
    @RedWitness(verdict = "HIGHOXYGEN on 40", member = "AtmosphereHazards#byAtmosphere",
            at = "put(table, Atmosphere.HIGHOXYGEN, OXYGEN_TOXICITY);",
            replacedBy = "put(table, Atmosphere.HIGHOXYGEN, new HazardEffect(AtmosphereHazard.OXYGEN_TOXICITY, 80,"
                    + " AtmosphereHandler.oxygenToxicityDamage, () -> 1, HazardEffect.NONE, HazardEffect.NONE,"
                    + " HazardEffect.NONE, false, false, true, false, \"msg.highOxygen\"));",
            fails = "null", on = "2026-10-17")
    @RedWitness(verdict = "VERYHOT damage", member = "AtmosphereHazards#byAtmosphere",
            at = "put(table, Atmosphere.VERYHOT, HEAT);",
            replacedBy = "put(table, Atmosphere.VERYHOT, new HazardEffect(AtmosphereHazard.HEAT, 20,"
                    + " AtmosphereHandler.heatDamage, () -> 2, 3, HazardEffect.NONE, HazardEffect.NONE, false, true, false,"
                    + " false, \"msg.tooHot\"));",
            fails = "the two heat rungs differ by their damage and nothing else expected:<1> but was:<2>", on = "2026-10-17")
    @RedWitness(verdict = "SUPERHEATED damage", member = "AtmosphereHazards#byAtmosphere",
            at = "put(table, Atmosphere.SUPERHEATED, SEARING_HEAT);",
            replacedBy = "put(table, Atmosphere.SUPERHEATED, new HazardEffect(AtmosphereHazard.HEAT, 20,"
                    + " AtmosphereHandler.heatDamage, () -> 3, 3, HazardEffect.NONE, HazardEffect.NONE, false, true, false,"
                    + " false, \"msg.tooHot\"));",
            fails = "expected:<4> but was:<3>", on = "2026-10-17")
    @RedWitness(verdict = "NOO2 slowness", member = "AtmosphereHazards#byAtmosphere",
            at = "put(table, Atmosphere.NOO2, SUFFOCATION);",
            replacedBy = "put(table, Atmosphere.NOO2, new HazardEffect(AtmosphereHazard.SUFFOCATION, 10,"
                    + " AtmosphereHandler.lowOxygenDamage, () -> 1, 3, 4, 1, false, false, true, true, \"msg.noOxygen\"));",
            fails = "and the two suffocation rungs by how hard they hit expected:<4> but was:<3>", on = "2026-10-17")
    @RedWitness(verdict = "LOWOXYGEN slowness", member = "AtmosphereHazards#byAtmosphere",
            at = "put(table, Atmosphere.LOWOXYGEN, THIN_AIR);",
            replacedBy = "put(table, Atmosphere.LOWOXYGEN, new HazardEffect(AtmosphereHazard.SUFFOCATION, 20,"
                    + " AtmosphereHandler.lowOxygenDamage, () -> 1, 3, 2, HazardEffect.NONE, false, false, true, false,"
                    + " \"msg.noOxygen\"));",
            fails = "expected:<2> but was:<3>", on = "2026-10-17")
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

    @RedWitness(verdict = "VERYHOT", member = "AtmosphereHazards#byAtmosphere",
            at = "put(table, Atmosphere.VERYHOT, HEAT);",
            replacedBy = "put(table, Atmosphere.VERYHOT, HEAT_WHERE_AIRLESS);",
            fails = "a hot breathable room sets you alight", on = "2026-10-17")
    @RedWitness(verdict = "SUPERHEATED", member = "AtmosphereHazards#byAtmosphere",
            at = "put(table, Atmosphere.SUPERHEATED, SEARING_HEAT);",
            replacedBy = "put(table, Atmosphere.SUPERHEATED, SEARING_HEAT_WHERE_AIRLESS);",
            fails = "null", on = "2026-10-17")
    @RedWitness(verdict = "VERYHOTNOO2", member = "AtmosphereHazards#byAtmosphere",
            at = "put(table, Atmosphere.VERYHOTNOO2, SUFFOCATION, HEAT_WHERE_AIRLESS);",
            replacedBy = "put(table, Atmosphere.VERYHOTNOO2, SUFFOCATION, HEAT);",
            fails = "and the same heat with no oxygen does not", on = "2026-10-17")
    @RedWitness(verdict = "SUPERHEATEDNOO2", member = "AtmosphereHazards#byAtmosphere",
            at = "put(table, Atmosphere.SUPERHEATEDNOO2, SUFFOCATION, SEARING_HEAT_WHERE_AIRLESS);",
            replacedBy = "put(table, Atmosphere.SUPERHEATEDNOO2, SUFFOCATION, SEARING_HEAT);",
            fails = "null", on = "2026-10-17")
    @Test
    public void hotAirIgnitesYouOnlyWhereYouCouldHaveBreathedIt() {
        assertTrue("a hot breathable room sets you alight",
                row(Atmosphere.VERYHOT, AtmosphereHazard.HEAT).ignites());
        assertTrue(row(Atmosphere.SUPERHEATED, AtmosphereHazard.HEAT).ignites());

        assertFalse("and the same heat with no oxygen does not — deliberately preserved",
                row(Atmosphere.VERYHOTNOO2, AtmosphereHazard.HEAT).ignites());
        assertFalse(row(Atmosphere.SUPERHEATEDNOO2, AtmosphereHazard.HEAT).ignites());
    }

    @RedWitness(verdict = "breathable deep", member = "AtmosphereHazards#byAtmosphere",
            at = "put(table, Atmosphere.SUPERHIGHPRESSURE, CRUSHING_PRESSURE_WHERE_BREATHABLE);",
            replacedBy = "put(table, Atmosphere.SUPERHIGHPRESSURE, new HazardEffect(AtmosphereHazard.PRESSURE, 20,"
                    + " AtmosphereHandler.oxygenToxicityDamage, () -> 2, 3, 3, HazardEffect.NONE, false, false, true,"
                    + " false, \"msg.muchTooDense\"));",
            fails = "crushing breathable air draws blood expected:<1> but was:<2>", on = "2026-10-17")
    @RedWitness(verdict = "airless deep", member = "AtmosphereHazards#byAtmosphere",
            at = "CRUSHING_PRESSURE_WHERE_AIRLESS);",
            replacedBy = "new HazardEffect(AtmosphereHazard.PRESSURE, 20, AtmosphereHandler.oxygenToxicityDamage,"
                    + " () -> 1, 3, 3, HazardEffect.NONE, true, false, false, false, \"msg.muchTooDense\"));",
            fails = "crushing airless air does not", on = "2026-10-17")
    @Test
    public void theDeepestRungInjuresOnlyWhereTheAirIsBreathable() {
        assertEquals("crushing breathable air draws blood", 1,
                row(Atmosphere.SUPERHIGHPRESSURE, AtmosphereHazard.PRESSURE).damageAmount());
        assertEquals("crushing airless air does not — deliberately preserved", 0,
                row(Atmosphere.SUPERHIGHPRESSURENOO2, AtmosphereHazard.PRESSURE).damageAmount());
    }

    @RedWitness(verdict = "airless deep", member = "AtmosphereHazards#byAtmosphere",
            at = "CRUSHING_PRESSURE_WHERE_AIRLESS);",
            replacedBy = "new HazardEffect(AtmosphereHazard.PRESSURE, 20, null, null, 3, 3, HazardEffect.NONE, false,"
                    + " false, false, false, \"msg.muchTooDense\"));",
            fails = "the airless deep takes your jump", on = "2026-10-17")
    @RedWitness(verdict = "breathable deep", member = "AtmosphereHazards#byAtmosphere",
            at = "put(table, Atmosphere.SUPERHIGHPRESSURE, CRUSHING_PRESSURE_WHERE_BREATHABLE);",
            replacedBy = "put(table, Atmosphere.SUPERHIGHPRESSURE, new HazardEffect(AtmosphereHazard.PRESSURE, 20,"
                    + " AtmosphereHandler.oxygenToxicityDamage, () -> 1, 3, 3, HazardEffect.NONE, true, false, true,"
                    + " false, \"msg.muchTooDense\"));",
            fails = "and the breathable deep does not", on = "2026-10-17")
    @Test
    public void narcosisBelongsToTheAirlessDepthsAlone() {
        assertTrue("the airless deep takes your jump — deliberately preserved",
                row(Atmosphere.SUPERHIGHPRESSURENOO2, AtmosphereHazard.PRESSURE).narcosis());
        assertFalse("and the breathable deep does not",
                row(Atmosphere.SUPERHIGHPRESSURE, AtmosphereHazard.PRESSURE).narcosis());
    }

    @RedWitness(verdict = "HIGHPRESSURENOO2", member = "AtmosphereHazards#byAtmosphere",
            at = "put(table, Atmosphere.HIGHPRESSURENOO2, SUFFOCATION_WHERE_DENSE, PRESSURE);",
            replacedBy = "put(table, Atmosphere.HIGHPRESSURENOO2, SUFFOCATION, PRESSURE);",
            fails = "depth makes suffocation more nauseating", on = "2026-10-17")
    @RedWitness(verdict = "SUPERHIGHPRESSURENOO2", member = "AtmosphereHazards#byAtmosphere",
            at = "put(table, Atmosphere.SUPERHIGHPRESSURENOO2, SUFFOCATION_WHERE_DENSE,",
            replacedBy = "put(table, Atmosphere.SUPERHIGHPRESSURENOO2, SUFFOCATION,",
            fails = "expected:<2> but was:<1>", on = "2026-10-17")
    @RedWitness(verdict = "VERYHOTNOO2", member = "AtmosphereHazards#byAtmosphere",
            at = "put(table, Atmosphere.VERYHOTNOO2, SUFFOCATION, HEAT_WHERE_AIRLESS);",
            replacedBy = "put(table, Atmosphere.VERYHOTNOO2, SUFFOCATION_WHERE_DENSE, HEAT_WHERE_AIRLESS);",
            fails = "while heat does not expected:<1> but was:<2>", on = "2026-10-17")
    @RedWitness(verdict = "NOO2", member = "AtmosphereHazards#byAtmosphere",
            at = "put(table, Atmosphere.NOO2, SUFFOCATION);",
            replacedBy = "put(table, Atmosphere.NOO2, SUFFOCATION_WHERE_DENSE);",
            fails = "expected:<1> but was:<2>", on = "2026-10-17")
    @Test
    public void suffocatingUnderPressureIsQueasierThanSuffocatingAnywhereElse() {
        assertEquals("depth makes suffocation more nauseating — deliberately preserved", 2,
                row(Atmosphere.HIGHPRESSURENOO2, AtmosphereHazard.SUFFOCATION).nausea());
        assertEquals(2, row(Atmosphere.SUPERHIGHPRESSURENOO2, AtmosphereHazard.SUFFOCATION).nausea());
        assertEquals("while heat does not", 1,
                row(Atmosphere.VERYHOTNOO2, AtmosphereHazard.SUFFOCATION).nausea());
        assertEquals(1, row(Atmosphere.NOO2, AtmosphereHazard.SUFFOCATION).nausea());
    }

    @RedWitness(member = "AtmosphereHazards#byAtmosphere", at = "put(table, Atmosphere.VACUUM, DECOMPRESSION);",
            replacedBy = "put(table, Atmosphere.VACUUM, SUFFOCATION);",
            fails = "nothing in the game can inflict [DECOMPRESSION]", on = "2026-10-17")
    @Test
    public void everyHazardTheModelDeclaresIsRaisedBySomething() {
        // The other half of "no storage without a consumer": a kind of harm nothing can inflict is
        // a row in an enum pretending to be a mechanic.
        List<AtmosphereHazard> unraised = new ArrayList<>();
        for (AtmosphereHazard hazard : AtmosphereHazard.values()) {
            // Poison is raised by the air's composition rather than by a named atmosphere.
            boolean raised = hazard == dev.stannismod.stellurgy.atmosphere.hazard.Poisoning.hazard();
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
