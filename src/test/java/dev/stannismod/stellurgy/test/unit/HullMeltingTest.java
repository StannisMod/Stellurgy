package dev.stannismod.stellurgy.test.unit;

import org.junit.BeforeClass;
import org.junit.Test;

import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.subsystem.heat.HullMelting;
import dev.stannismod.stellurgy.test.MinecraftBootstrap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The environment's half of the melting rung: how hot the outside alone can drive a block.
 *
 * <p>The incident flux is quoted on the same curve a radiator sheds on, so turning it into a
 * temperature is that curve read backwards - and the only thing worth pinning is that it IS the same
 * curve. A separate model here would let a ship parked in a star melt at one temperature and radiate
 * as if it were at another.</p>
 */
public class HullMeltingTest {

    @BeforeClass
    public static void bootstrap() {
        MinecraftBootstrap.ensure();
    }

    /**
     * <p>red-witnessed: with {@code HullMelting#equilibriumKelvin} at {@code double atReference = HeatNetwork.cellPowerAt(reference);} reading the reference point off a curve twice as
     * bright as the one a radiator sheds on: "a surface in exactly the flux it radiates sits at that
     * temperature expected:&lt;500.0&gt; but was:&lt;420.44820762685725&gt;", 2026-09-30.</p>
     */
    @Test
    public void theEquilibriumTemperatureIsTheRadiationCurveReadBackwards() {
        StellurgyConfiguration config = StellurgyConfiguration.getCurrentConfig();
        config.shipHeatRadiatorReferenceKelvin = 500;
        config.shipHeatRadiatorCellPower = 6000;

        // What a cell radiates AT the reference is the flux that would hold it there, so feeding that
        // flux back must return the reference itself.
        double atReference = HullMelting.equilibriumKelvin(6000 / 20);

        // EXACT: at the reference the flux over the reference power is 300 / 300.0 = 1, and the fourth
        // root of 1 is 1 — `HullMelting.equilibriumKelvin` has nothing left to round.
        assertEquals("a surface in exactly the flux it radiates sits at that temperature",
                500.0D, atReference, 0.0D);
    }

    /**
     * <p>red-witnessed: with {@code HullMelting#equilibriumKelvin} at {@code return reference * Math.pow(incidentFluxPerCell / atReference, 0.25D);} taking the square root of the flux ratio instead
     * of the fourth: "sixteen times the flux is twice the temperature ... expected:&lt;2.0&gt; but
     * was:&lt;4.0&gt;", 2026-09-30. The premise before it is an arrangement and is not witnessed.</p>
     */
    @Test
    public void moreFluxIsAHotterSurfaceButOnlyToTheFourthRoot() {
        StellurgyConfiguration config = StellurgyConfiguration.getCurrentConfig();
        config.shipHeatRadiatorReferenceKelvin = 500;
        config.shipHeatRadiatorCellPower = 6000;

        double single = HullMelting.equilibriumKelvin(300);
        double sixteenfold = HullMelting.equilibriumKelvin(300 * 16);

        assertTrue("premise: a surface under any flux at all has a temperature", single > 0);
        // One ulp: the ratio is exactly `Math.pow(16, 0.25)`, and `Math.pow`'s contract is to within
        // one ulp of the true result, which is 2.
        assertEquals("sixteen times the flux is twice the temperature - the law is quartic, so a star"
                + " that is far brighter is not proportionally hotter on your hull",
                2.0D, sixteenfold / single, Math.ulp(2.0D));
    }

    /**
     * <p>red-witnessed: with {@code HullMelting#equilibriumKelvin} at {@code return 0.0D;} answering 1 K for no incident flux: "an unlit
     * surface is not driven anywhere by the environment expected:&lt;0.0&gt; but was:&lt;1.0&gt;",
     * 2026-09-30.</p>
     */
    @Test
    public void nothingArrivingIsNoTemperatureAtAll() {
        assertEquals("an unlit surface is not driven anywhere by the environment", 0.0D,
                HullMelting.equilibriumKelvin(0), 0.0D);
    }
}
