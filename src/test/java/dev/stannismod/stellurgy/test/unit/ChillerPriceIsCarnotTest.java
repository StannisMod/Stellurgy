package dev.stannismod.stellurgy.test.unit;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.subsystem.heat.HeatNetwork;
import dev.stannismod.stellurgy.test.MinecraftBootstrap;

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

    /**
     * Pinned so no assertion depends on the shipped defaults moving — and because a unit test runs
     * against a config nobody loaded, where both of these are 0 until they are said out loud. A
     * ceiling of zero collapses every answer onto the floor, which is a different function.
     */
    private static final int HALF_OF_IDEAL = 500;
    private static final int MAX_COP = 50;

    private int prevFraction;
    private int prevMaxCop;

    @BeforeClass
    public static void bootstrap() {
        MinecraftBootstrap.ensure();
    }

    @Before
    public void setFraction() {
        prevFraction = StellurgyConfiguration.getCurrentConfig().shipHeatChillerCopFraction;
        prevMaxCop = StellurgyConfiguration.getCurrentConfig().shipHeatChillerMaxCop;
        StellurgyConfiguration.getCurrentConfig().shipHeatChillerCopFraction = HALF_OF_IDEAL;
        StellurgyConfiguration.getCurrentConfig().shipHeatChillerMaxCop = MAX_COP;
    }

    @After
    public void restoreFraction() {
        StellurgyConfiguration.getCurrentConfig().shipHeatChillerCopFraction = prevFraction;
        StellurgyConfiguration.getCurrentConfig().shipHeatChillerMaxCop = prevMaxCop;
    }

    /**
     * Half of the Carnot ideal, and the ideal is the COOLING one.
     *
     * <p>red-witnessed: with {@code HeatNetwork:663} pricing on the heating coefficient
     * {@code Th/(Th-Tc)}: "half of Tc/(Th-Tc), not half of Th/(Th-Tc)
     * expected:&lt;0.7077294685990339&gt; but was:&lt;1.2077294685990339&gt;", 2026-09-30.</p>
     */
    @Test
    public void theCoefficientIsTheCoolingOneScaledByWhatARealMachineManages() {
        double cop = HeatNetwork.coefficientOfPerformance(293.0D, 500.0D);

        double idealCooling = 293.0D / (500.0D - 293.0D);
        // EXACT: `HeatNetwork.coefficientOfPerformance` computes the same `Tc / (Th - Tc)` and scales
        // it by 500 / 1000.0 = 0.5, well inside its floor and ceiling — the same operations on the same
        // doubles give the same bits. Tc/(Th-Tc) and Th/(Th-Tc) differ by 1, so any slack hides nothing
        // today and would hide the next wrong formula that happens to land near.
        assertEquals("half of Tc/(Th-Tc), not half of Th/(Th-Tc)",
                idealCooling * 0.5D, cop, 0.0D);
    }

    /**
     * The clause a floor of 1.0 used to destroy. Past a certain gradient a chiller costs more work
     * than the heat it moves, and that is what makes driving the hot side further cost more for less.
     *
     * <p>red-witnessed: with {@code HeatNetwork:669} flooring the coefficient at 1.0 again: "a
     * cooling coefficient below one is ordinary physics and must be reachable: 1.0", 2026-09-30.</p>
     */
    @Test
    public void aWideGradientCostsMoreWorkThanTheHeatItMoves() {
        double cop = HeatNetwork.coefficientOfPerformance(100.0D, 500.0D);

        assertTrue("a cooling coefficient below one is ordinary physics and must be reachable: " + cop,
                cop < 1.0D);
    }

    /**
     * Monotone in the right direction: the wider the gap, the worse the deal.
     *
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. NARROW IS CHEAPEST -
     * {@code HeatNetwork:663} pricing on {@code (Th-Tc)/Tc}: "a narrow gradient is the cheapest". WIDE
     * IS DEAREST - {@code HeatNetwork:669} flooring the coefficient at 1.0: "and a wide one the
     * dearest".</p>
     */
    @Test
    public void wideningTheGapAlwaysMakesTheDealWorse() {
        double narrow = HeatNetwork.coefficientOfPerformance(293.0D, 350.0D);
        double middle = HeatNetwork.coefficientOfPerformance(293.0D, 500.0D);
        double wide = HeatNetwork.coefficientOfPerformance(293.0D, 900.0D);

        assertTrue("a narrow gradient is the cheapest", narrow > middle);
        assertTrue("and a wide one the dearest", middle > wide);
    }

    /**
     * Colder is dearer, at a fixed hot side — the third law showing up as a price.
     *
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. COLDER COSTS MORE -
     * {@code HeatNetwork:669} flooring the coefficient at 1.0: "pulling heat out of something colder
     * must cost more, not the same: 1.0 against 1.0". RUINOUS NEAR ZERO - {@code HeatNetwork:663}
     * pricing on the heating coefficient {@code Th/(Th-Tc)}: "and near absolute zero it must be
     * ruinous rather than clamped to parity".</p>
     */
    @Test
    public void aColderRoomCostsMorePerUnitMoved() {
        double warm = HeatNetwork.coefficientOfPerformance(293.0D, 600.0D);
        double cold = HeatNetwork.coefficientOfPerformance(50.0D, 600.0D);

        assertTrue("pulling heat out of something colder must cost more, not the same: "
                + warm + " against " + cold, cold < warm);
        assertTrue("and near absolute zero it must be ruinous rather than clamped to parity",
                HeatNetwork.coefficientOfPerformance(1.0D, 600.0D) < 0.05D);
    }

    /**
     * No gradient to fight is not a licence to divide by zero.
     *
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. CHEAP - {@code HeatNetwork:662}
     * answering 0.5 when the hot side is no hotter: "heat flowing downhill is cheap". FINITE -
     * {@code HeatNetwork:86} returning an infinite ceiling: "but still a finite number the caller can
     * divide by".</p>
     */
    @Test
    public void aHotSideNoHotterThanTheColdOneIsBoundedRatherThanInfinite() {
        double cop = HeatNetwork.coefficientOfPerformance(500.0D, 400.0D);

        assertTrue("heat flowing downhill is cheap", cop > 1.0D);
        assertTrue("but still a finite number the caller can divide by", cop < 1000.0D);
    }
}
