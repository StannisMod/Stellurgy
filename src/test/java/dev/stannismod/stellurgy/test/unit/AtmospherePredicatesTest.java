package dev.stannismod.stellurgy.test.unit;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import net.minecraft.nbt.NBTTagCompound;

import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.atmosphere.AirState;
import dev.stannismod.stellurgy.atmosphere.gas.Gas;
import dev.stannismod.stellurgy.atmosphere.gas.GasRegistry;
import dev.stannismod.stellurgy.atmosphere.gas.GasRole;
import dev.stannismod.stellurgy.test.MinecraftBootstrap;

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
        StellurgyConfiguration config = StellurgyConfiguration.getCurrentConfig();
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
        StellurgyConfiguration config = StellurgyConfiguration.getCurrentConfig();
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
     *
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. BETWEEN IS UNBREATHABLE -
     * {@code AirState:440} breathing from the combustion threshold: "air between the two bands must
     * NOT be breathable: ...". BETWEEN STILL BURNS - {@code AirState:426} burning only from the
     * breathing threshold: "but it must still burn - fire and lungs are two different questions about
     * the same gas: ...". THIN DOES NOT BURN - {@code AirState:426} burning whatever the oxidiser: "and
     * air far below the combustion band must not burn ...". ORDINARY AIR BURNS - {@code AirState:426}
     * refusing combustion from 20% oxidiser up: "ordinary air burns: ...". AND IS BREATHABLE -
     * {@code AirState:440} refusing breath from 20% oxidiser up: "and is breathable: ...". The two
     * premises are arrangements and are not witnessed.</p>
     */
    @Test
    public void aRoomTooThinToBreatheCanStillBurnAndAThinnerOneCannot() {
        StellurgyConfiguration config = StellurgyConfiguration.getCurrentConfig();
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

    /**
     * Nothing burns in a vacuum, whatever the config says, because there is no oxidiser there.
     *
     * <p>red-witnessed: one DOUBLE inversion per verdict, 2026-09-30 - each predicate has two defences
     * here, the vacuum ceiling and the oxidiser threshold. NO BURNING - {@code AirState:420} no longer
     * refusing a vacuum AND {@code AirState:426} accepting any oxidiser at all: "an empty composition
     * has no oxidiser to burn". NO BREATHING - {@code AirState:434} no longer refusing a vacuum AND
     * {@code AirState:440} accepting any oxidiser at all: "and nothing to breathe".</p>
     */
    @Test
    public void aVacuumBurnsNothing() {
        assertFalse("an empty composition has no oxidiser to burn", AirState.vacuum().allowsCombustion());
        assertFalse("and nothing to breathe", AirState.vacuum().isBreathableAir());
    }

    /**
     * The predicates are MONOTONE: a strictly better atmosphere never reads as worse. Asserted as an
     * ordering rather than as numbers, so it survives every rebalance.
     *
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. COMBUSTION MONOTONE -
     * {@code AirState:426} refusing combustion from 20% oxidiser up: "adding oxidiser may never take
     * combustion away (at 190000000)". BREATH MONOTONE - {@code AirState:440} refusing breath from 20%
     * oxidiser up: "adding oxidiser may never take breathability away (at 190000000)". POISON MAKES IT
     * TOXIC - {@code AirState:460} calling a poison toxic only at three times its limit: "and adding a
     * poison must make it so". REMOVING IT CLEARS IT - {@code AirState:129} removing half of what was
     * drawn: "and removing it must take the toxicity with it". The clean-air premise is an arrangement
     * and is not witnessed. The poison-naming verdict is not witnessed: with one poison present,
     * {@code isToxic()} is {@code worstToxin() != null}, so the verdict before it already implies
     * which gas is named.</p>
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
     *
     * <p>red-witnessed: 2026-09-30. UNDER ITS OWN LIMIT - a DOUBLE inversion, {@code AirState:453} AND
     * {@code AirState:460} both halving the limit: "under its own limit, a poison is not yet
     * poisoning: ..."; {@code AirState:460} alone stays green, because the starting worst excess of 1.0
     * enforces the same limit a second time. STRICTER POISON IS OVER - {@code AirState:459} judging
     * every poison against ammonia's limit: "the SAME amount of a stricter poison is over ITS limit:
     * ...". The premise is an arrangement and is not witnessed. The naming verdict is not witnessed:
     * with one poison present the verdict before it already implies which gas is named.</p>
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
     *
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. WET IS WORSE - {@code AirState:494}
     * ignoring the solvent: "water makes it worse: dry=10.0 wet=10.0". HOT IS WORSE -
     * {@code AirState:495} ignoring the temperature: "and so does heat: dry=10.0 hot=10.0". CLEAN AIR -
     * {@code AirState:489} answering 0.1 when nothing attacks: "clean air attacks nothing
     * expected:&lt;0.0&gt; but was:&lt;0.1&gt;". The premise is an arrangement and is not
     * witnessed.</p>
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
     *
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. EVERY GAS HAS A ROLE - a DOUBLE
     * inversion, {@code GasRegistry:47} registering helium with no role AND {@code GasRegistry:75}
     * no longer refusing that at registration: "a gas with no role has no reason to be modelled:
     * Gas[helium []]". FINDABLE BY NAME - {@code GasRegistry:79} keying by the upper-cased name: "a gas
     * must be findable by the name its save data uses: ... but was:&lt;null&gt;". EVERY HAZARD HAS A
     * GAS - {@code GasRegistry:41} making water inert: "a predicate keys on SOLVENT and nothing in the
     * registry can raise it". EVERY POISON HAS A LIMIT - {@code GasRegistry:54} giving ammonia no
     * limit: "a poison with no limit can never poison anyone: Gas[ammonia [FUEL, TOXIC]]".</p>
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
     *
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. OXYGEN - {@code AirState:560} writing
     * oxygen at half: "oxygen comes back expected:&lt;210000000&gt; but was:&lt;105000000&gt;".
     * NITROGEN - {@code AirState:560} writing nitrogen at half: "so does the nitrogen
     * expected:&lt;790000000&gt; but was:&lt;395000000&gt;". METHANE - {@code AirState:576} skipping
     * methane on read: "and so does a gas the old three keys could not have named
     * expected:&lt;1234&gt; but was:&lt;0&gt;". TEMPERATURE - {@code AirState:563} writing half the
     * temperature: "and the temperature with them expected:&lt;293000&gt; but was:&lt;146500&gt;".
     * DROPPED - {@code AirState:576} reading an unknown gas as nitrogen: "a substance this game no
     * longer knows is DROPPED, never guessed at expected:&lt;1000001234&gt; but
     * was:&lt;1000006234&gt;". The not-reachable-by-name verdict is not witnessed: a fallback in
     * {@code GasRegistry:94} goes red on the DROPPED verdict first, because reading uses the same
     * lookup. The last line is a premise and is not witnessed.</p>
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
