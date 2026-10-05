package dev.stannismod.stellurgy.test.unit;

import org.junit.Test;
import dev.stannismod.stellurgy.api.satellite.SatelliteBase;
import dev.stannismod.stellurgy.satellite.SatelliteComposition;
import dev.stannismod.stellurgy.satellite.SatelliteDensity;
import dev.stannismod.stellurgy.satellite.SatelliteMassScanner;
import dev.stannismod.stellurgy.satellite.SatelliteOptical;
import dev.stannismod.stellurgy.satellite.SatelliteOreMapping;
import dev.stannismod.stellurgy.satellite.SatelliteSpyTelescope;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Scanning satellites' unit-tier contracts.
 *
 * <p>Six scanning satellite types (Optical, Density, Composition,
 * MassScanner, OreMapping, SpyTelescope) had ZERO unit/integration
 * coverage pre-this-test. {@code SatelliteTypeBehaviourTest} only
 * covered the three action-driven types (biomeChanger, solarEnergy,
 * weatherController).</p>
 *
 * <p>Pinning the unit-tier contract for the scanners:</p>
 *
 * <ul>
 *   <li><b>Constructor doesn't throw</b> — fresh instance OK.</li>
 *   <li><b>Display name</b> — what the player sees on the satellite
 *       chip ItemStack tooltip and satellite-builder GUI. A regression
 *       that swaps names between types is a player-visible UX bug.</li>
 *   <li><b>Failure chance ≥ 0</b> — sanity invariant; a negative
 *       failure chance would break {@code SatelliteWeatherController}'s
 *       random-failure logic.</li>
 *   <li><b>OreMapping ore-filter gate</b> — {@code canFilterOre()}
 *       requires {@code maxDataStorage == 3000}. Default-constructed
 *       satellite has 0 &rarr; can't filter. {@code setSelectedSlot} is
 *       silently ignored when can't filter.</li>
 * </ul>
 *
 * <p>The deeper scan-output contracts (canBeginScan with seeded battery,
 * scanChunk returning a populated grid) need a World context — those
 * belong at server tier where the harness has a real world. The
 * unit-tier pins here protect against regressions in the registry +
 * basic invariants without server-harness cost.</p>
 */
public class ScanningSatelliteContractTest {

    // Display-name contracts moved to the integration layer
    // (ScanningSatelliteNameContractTest): SatelliteBase.getName() now resolves
    // through LibVulpes.proxy.getLocalizedString(), which requires the proxy to
    // be bootstrapped — not available in the pure-unit layer.

    @Test
    public void allScanningSatellitesHaveNonNegativeFailureChance() {
        SatelliteBase[] scanners = new SatelliteBase[] {
                new SatelliteOreMapping(),
                new SatelliteDensity(),
                new SatelliteComposition(),
                new SatelliteMassScanner(),
                new SatelliteOptical(),
                new SatelliteSpyTelescope(),
        };
        for (SatelliteBase sat : scanners) {
            double fc = sat.failureChance();
            assertTrue(sat.getClass().getSimpleName() + ".failureChance() must be "
                            + ">= 0 (got " + fc + ")",
                    fc >= 0);
            // Failure chance is a probability — must be sane.
            assertTrue(sat.getClass().getSimpleName() + ".failureChance() must be "
                            + "<= 1 (got " + fc + ")",
                    fc <= 1);
        }
    }

    @Test
    public void oreMappingCanFilterOreRequires3000MaxDataStorage() {
        // canFilterOre gate per SatelliteOreMapping:220-222 — only the
        // top-tier chip (maxDataStorage == 3000) gets ore-filter ability.
        // Default-constructed satellite has 0 maxDataStorage -> can't filter.
        SatelliteOreMapping sat = new SatelliteOreMapping();
        assertFalse("default-constructed OreMapping cannot filter ore — "
                        + "satelliteProperties.getMaxDataStorage() == 0 != 3000",
                sat.canFilterOre());
    }

    @Test
    public void oreMappingSetSelectedSlotIsIgnoredWhenCannotFilter() {
        // Default OreMapping has selectedSlot=-1, canFilterOre=false.
        // setSelectedSlot guards on canFilterOre — so calling it should
        // be a no-op when the chip can't filter.
        SatelliteOreMapping sat = new SatelliteOreMapping();
        assertEquals("default selectedSlot is -1", -1, sat.getSelectedSlot());
        sat.setSelectedSlot(5);
        assertEquals("setSelectedSlot(5) on can't-filter chip must be ignored — "
                        + "preserves the GUI invariant that low-tier chips don't "
                        + "respond to filter-slot clicks",
                -1, sat.getSelectedSlot());
    }

}
