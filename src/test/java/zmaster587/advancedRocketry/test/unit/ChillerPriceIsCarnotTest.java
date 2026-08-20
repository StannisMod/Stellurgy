package zmaster587.advancedRocketry.test.unit;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.subsystem.heat.HeatNetwork;
import zmaster587.advancedRocketry.test.MinecraftBootstrap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * What a chiller PAYS, and why the price is the tier's ceiling rather than a number somebody chose.
 *
 * <p>The duty is COOLING: every caller divides the heat taken off the cold side by this coefficient
 * to get the work. So the coefficient is {@code Tc / (Th - Tc)}, and the thing these pin is that a
 * value BELOW ONE is legal — it says the work costs more than the heat it moves, which is exactly the
 * regime a wide gradient puts you in. The clause that pinned the chiller before this one was
 * {@code Qh = Qc + W}, an identity true for ANY coefficient, which is why the heating coefficient
 * could sit here priced against cooling and no test could see it.
 */
public class ChillerPriceIsCarnotTest {

    /** Pinned so no assertion depends on the shipped default moving. */
    private static final int HALF_OF_IDEAL = 500;

    private int prevFraction;

    @BeforeClass
    public static void bootstrap() {
        MinecraftBootstrap.ensure();
    }

    @Before
    public void setFraction() {
        prevFraction = ARConfiguration.getCurrentConfig().shipHeatChillerCopFraction;
        ARConfiguration.getCurrentConfig().shipHeatChillerCopFraction = HALF_OF_IDEAL;
    }

    @After
    public void restoreFraction() {
        ARConfiguration.getCurrentConfig().shipHeatChillerCopFraction = prevFraction;
    }

    /** Half of the Carnot ideal, and the ideal is the COOLING one. */
    @Test
    public void theCoefficientIsTheCoolingOneScaledByWhatARealMachineManages() {
        double cop = HeatNetwork.coefficientOfPerformance(293.0D, 500.0D);

        double idealCooling = 293.0D / (500.0D - 293.0D);
        assertEquals("half of Tc/(Th-Tc), not half of Th/(Th-Tc)",
                idealCooling * 0.5D, cop, 0.01D);
    }

    /**
     * The clause a floor of 1.0 used to destroy. Past a certain gradient a chiller costs more work
     * than the heat it moves, and that is what makes driving the hot side further cost more for less.
     */
    @Test
    public void aWideGradientCostsMoreWorkThanTheHeatItMoves() {
        double cop = HeatNetwork.coefficientOfPerformance(100.0D, 500.0D);

        assertTrue("a cooling coefficient below one is ordinary physics and must be reachable: " + cop,
                cop < 1.0D);
    }

    /** Monotone in the right direction: the wider the gap, the worse the deal. */
    @Test
    public void wideningTheGapAlwaysMakesTheDealWorse() {
        double narrow = HeatNetwork.coefficientOfPerformance(293.0D, 350.0D);
        double middle = HeatNetwork.coefficientOfPerformance(293.0D, 500.0D);
        double wide = HeatNetwork.coefficientOfPerformance(293.0D, 900.0D);

        assertTrue("a narrow gradient is the cheapest", narrow > middle);
        assertTrue("and a wide one the dearest", middle > wide);
    }

    /** Colder is dearer, at a fixed hot side — the third law showing up as a price. */
    @Test
    public void aColderRoomCostsMorePerUnitMoved() {
        double warm = HeatNetwork.coefficientOfPerformance(293.0D, 600.0D);
        double cold = HeatNetwork.coefficientOfPerformance(50.0D, 600.0D);

        assertTrue("pulling heat out of something colder must cost more, not the same: "
                + warm + " against " + cold, cold < warm);
        assertTrue("and near absolute zero it must be ruinous rather than clamped to parity",
                HeatNetwork.coefficientOfPerformance(1.0D, 600.0D) < 0.05D);
    }

    /** No gradient to fight is not a licence to divide by zero. */
    @Test
    public void aHotSideNoHotterThanTheColdOneIsBoundedRatherThanInfinite() {
        double cop = HeatNetwork.coefficientOfPerformance(500.0D, 400.0D);

        assertTrue("heat flowing downhill is cheap", cop > 1.0D);
        assertTrue("but still a finite number the caller can divide by", cop < 1000.0D);
    }
}
