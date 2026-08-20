package zmaster587.advancedRocketry.test.unit;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import net.minecraft.nbt.NBTTagCompound;

import zmaster587.advancedRocketry.api.ARConfiguration;
import zmaster587.advancedRocketry.atmosphere.AirState;
import zmaster587.advancedRocketry.atmosphere.gas.Gas;
import zmaster587.advancedRocketry.atmosphere.gas.GasRegistry;
import zmaster587.advancedRocketry.atmosphere.gas.GasRole;
import zmaster587.advancedRocketry.test.MinecraftBootstrap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * What air IS, asked of the air.
 *
 * <p>Breathability, combustion, toxicity and corrosivity are PREDICATES over a composition, never
 * flags stored beside it — and combustion is decided by the OXIDISER rather than by breathability.
 * Those two together are what closes the defect this slice exists for: the two questions were one
 * boolean, assigned from the BREATHING band, so a room nobody could breathe still lit torches.</p>
 *
 * <p>The first scenario is that defect and nothing else: it builds the band BETWEEN the two
 * thresholds, where the answers must differ. A model that kept them tied fails it whichever way it
 * tied them.</p>
 */
public class AtmospherePredicatesTest {

    private long prevMin;
    private long prevMax;
    private long prevBurn;
    private int prevAmbient;

    /** Parts per million of an atmosphere, which is the unit these numbers are quoted in. */
    private static long ppm(long partsPerMillion) {
        return partsPerMillion * AirState.PER_PPM;
    }

    @BeforeClass
    public static void bootstrap() {
        MinecraftBootstrap.ensure();
    }

    @Before
    public void shippedBands() {
        ARConfiguration config = ARConfiguration.getCurrentConfig();
        prevMin = config.lifeSupportMinPartialO2;
        prevMax = config.lifeSupportMaxPartialO2;
        prevBurn = config.lifeSupportCombustionMinPartialO2;
        prevAmbient = config.shipHeatAmbientKelvin;
        config.lifeSupportMinPartialO2 = ppm(160_000);
        config.lifeSupportMaxPartialO2 = ppm(300_000);
        config.lifeSupportCombustionMinPartialO2 = ppm(150_000);
        config.shipHeatAmbientKelvin = 293;
    }

    @After
    public void restoreBands() {
        ARConfiguration config = ARConfiguration.getCurrentConfig();
        config.lifeSupportMinPartialO2 = prevMin;
        config.lifeSupportMaxPartialO2 = prevMax;
        config.lifeSupportCombustionMinPartialO2 = prevBurn;
        config.shipHeatAmbientKelvin = prevAmbient;
    }

    /** A room at one atmosphere with the oxygen asked for, and nitrogen making up the rest. */
    private static AirState roomWithOxygen(long oxygen) {
        return new AirState(AirState.ONE_ATM - oxygen, oxygen, 0);
    }

    /**
     * Combustion follows the OXIDISER, and breathing follows its own band, so between the two
     * thresholds the answers differ. That gap is the whole clause.
     */
    @Test
    public void aRoomTooThinToBreatheCanStillBurnAndAThinnerOneCannot() {
        ARConfiguration config = ARConfiguration.getCurrentConfig();
        AirState between = roomWithOxygen((config.lifeSupportCombustionMinPartialO2
                + config.lifeSupportMinPartialO2) / 2);
        AirState thin = roomWithOxygen(config.lifeSupportCombustionMinPartialO2 / 3);
        AirState good = roomWithOxygen(ppm(210_000));

        assertTrue("premise: the shipped bands must actually differ, or this scenario cannot exist",
                config.lifeSupportCombustionMinPartialO2 < config.lifeSupportMinPartialO2);

        assertFalse("air between the two bands must NOT be breathable: " + between,
                between.isBreathableAir());
        assertTrue("but it must still burn - fire and lungs are two different questions about the"
                + " same gas: " + between, between.allowsCombustion());

        assertFalse("and air far below the combustion band must not burn, which is the defect this"
                + " slice exists to close: a room nobody can breathe used to light torches: " + thin,
                thin.allowsCombustion());
        assertFalse("premise: that thin room must also be unbreathable: " + thin,
                thin.isBreathableAir());

        assertTrue("ordinary air burns: " + good, good.allowsCombustion());
        assertTrue("and is breathable: " + good, good.isBreathableAir());
    }

    /** Nothing burns in a vacuum, whatever the config says, because there is no oxidiser there. */
    @Test
    public void aVacuumBurnsNothing() {
        assertFalse("an empty composition has no oxidiser to burn", AirState.vacuum().allowsCombustion());
        assertFalse("and nothing to breathe", AirState.vacuum().isBreathableAir());
    }

    /**
     * The predicates are MONOTONE: a strictly better atmosphere never reads as worse. Asserted as an
     * ordering rather than as numbers, so it survives every rebalance.
     */
    @Test
    public void aStrictlyBetterAtmosphereNeverReadsAsWorse() {
        for (long oxygen = 0; oxygen <= ppm(300_000); oxygen += ppm(10_000)) {
            AirState less = roomWithOxygen(oxygen);
            AirState more = roomWithOxygen(oxygen + ppm(10_000));
            assertTrue("adding oxidiser may never take combustion away (at " + oxygen + ")",
                    !less.allowsCombustion() || more.allowsCombustion());
            assertTrue("adding oxidiser may never take breathability away (at " + oxygen + ")",
                    !less.isBreathableAir() || more.isBreathableAir());
        }

        AirState clean = roomWithOxygen(ppm(210_000));
        AirState poisoned = roomWithOxygen(ppm(210_000));
        poisoned.add(GasRegistry.CARBON_MONOXIDE,
                GasRegistry.CARBON_MONOXIDE.hazardThreshold() * 2, 293.0D);
        assertFalse("premise: clean air is not toxic", clean.isToxic());
        assertTrue("and adding a poison must make it so", poisoned.isToxic());
        assertEquals("naming the poison, so a consumer can say which one", GasRegistry.CARBON_MONOXIDE,
                poisoned.worstToxin());

        poisoned.draw(GasRegistry.CARBON_MONOXIDE, Long.MAX_VALUE);
        assertFalse("and removing it must take the toxicity with it", poisoned.isToxic());
    }

    /**
     * A poison is judged against ITS OWN limit, so good air is no defence and the predicate needs to
     * know nothing about which gas it is looking at.
     */
    @Test
    public void aPoisonIsJudgedAgainstItsOwnLimitAndNotAgainstTheAirAroundIt() {
        Gas ammonia = GasRegistry.AMMONIA;
        Gas sulphide = GasRegistry.HYDROGEN_SULFIDE;
        assertTrue("premise: the two poisons must have DIFFERENT limits, or this proves nothing",
                ammonia.hazardThreshold() != sulphide.hazardThreshold());

        long justUnderAmmonia = ammonia.hazardThreshold() - 1;
        AirState room = roomWithOxygen(ppm(210_000));
        room.add(ammonia, justUnderAmmonia, 293.0D);
        assertFalse("under its own limit, a poison is not yet poisoning: " + room, room.isToxic());

        AirState other = roomWithOxygen(ppm(210_000));
        other.add(sulphide, justUnderAmmonia, 293.0D);
        assertTrue("the SAME amount of a stricter poison is over ITS limit: " + other,
                other.isToxic());
        assertEquals(sulphide, other.worstToxin());
    }

    /**
     * Corrosion needs something corrosive, something to dissolve it into, and heat — and it reads all
     * three rather than switching on one.
     */
    @Test
    public void corrosionIsWorseWetAndWorseHot() {
        AirState dry = roomWithOxygen(ppm(210_000));
        dry.add(GasRegistry.SULFUR_DIOXIDE, GasRegistry.SULFUR_DIOXIDE.hazardThreshold() * 10, 293.0D);

        AirState wet = roomWithOxygen(ppm(210_000));
        wet.add(GasRegistry.SULFUR_DIOXIDE, GasRegistry.SULFUR_DIOXIDE.hazardThreshold() * 10, 293.0D);
        wet.add(GasRegistry.WATER, AirState.ONE_ATM / 2, 293.0D);

        AirState hot = roomWithOxygen(ppm(210_000));
        hot.add(GasRegistry.SULFUR_DIOXIDE, GasRegistry.SULFUR_DIOXIDE.hazardThreshold() * 10, 600.0D);

        assertTrue("premise: a dry acid gas still attacks: " + dry.corrosionIndex(),
                dry.corrosionIndex() > 0.0D);
        assertTrue("water makes it worse: dry=" + dry.corrosionIndex() + " wet=" + wet.corrosionIndex(),
                wet.corrosionIndex() > dry.corrosionIndex());
        assertTrue("and so does heat: dry=" + dry.corrosionIndex() + " hot=" + hot.corrosionIndex(),
                hot.corrosionIndex() > dry.corrosionIndex());
        assertEquals("clean air attacks nothing", 0.0D, roomWithOxygen(ppm(210_000)).corrosionIndex(), 0.0D);
    }

    /**
     * Every substance in the model has a job, and every hazard a predicate can raise has a substance
     * that raises it — the two halves of "no storage without a consumer".
     */
    @Test
    public void everyGasHasAJobAndEveryHazardHasAGas() {
        for (Gas gas : GasRegistry.all()) {
            assertFalse("a gas with no role has no reason to be modelled: " + gas,
                    gas.roles().isEmpty());
            assertEquals("a gas must be findable by the name its save data uses: " + gas,
                    gas, GasRegistry.byName(gas.name()));
        }
        for (GasRole hazard : new GasRole[]{GasRole.OXIDISER, GasRole.TOXIC, GasRole.CORROSIVE,
                GasRole.SOLVENT}) {
            assertFalse("a predicate keys on " + hazard + " and nothing in the registry can raise it",
                    GasRegistry.withRole(hazard).isEmpty());
        }
        for (Gas gas : GasRegistry.withRole(GasRole.TOXIC)) {
            assertTrue("a poison with no limit can never poison anyone: " + gas,
                    gas.hazardThreshold() > 0);
        }
    }

    /**
     * A composition survives a save, INCLUDING a substance the three old keys could never name — and
     * a gas the running game no longer knows is dropped rather than guessed at.
     */
    @Test
    public void aCompositionSurvivesASaveAndAnUnknownGasIsDropped() {
        AirState written = roomWithOxygen(ppm(210_000));
        written.add(GasRegistry.METHANE, 1_234L, 293.0D);
        NBTTagCompound nbt = new NBTTagCompound();
        written.writeToNBT(nbt);

        AirState read = AirState.readFromNBT(nbt);
        assertEquals("oxygen comes back", written.getOxygen(), read.getOxygen());
        assertEquals("so does the nitrogen", written.getNitrogen(), read.getNitrogen());
        assertEquals("and so does a gas the old three keys could not have named",
                1_234L, read.partialPressure(GasRegistry.METHANE));
        assertEquals("and the temperature with them", written.getTemperatureMilliK(),
                read.getTemperatureMilliK());

        nbt.getCompoundTag("gases").setLong("unobtainium", 5_000L);
        AirState afterRemoval = AirState.readFromNBT(nbt);
        assertEquals("a substance this game no longer knows is DROPPED, never guessed at",
                read.getTotalPressure(), afterRemoval.getTotalPressure());
        assertNull("and it is not reachable by name either", GasRegistry.byName("unobtainium"));
        assertNotNull("premise: a known gas still is", GasRegistry.byName("methane"));
    }
}
