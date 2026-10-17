package dev.stannismod.stellurgy.test.unit;

import dev.stannismod.stellurgy.atmosphere.AirState;
import dev.stannismod.stellurgy.atmosphere.gas.Gas;
import dev.stannismod.stellurgy.atmosphere.gas.GasRegistry;
import dev.stannismod.stellurgy.atmosphere.gas.GasRole;
import dev.stannismod.stellurgy.test.MinecraftBootstrap;
import dev.stannismod.stellurgy.test.contract.LegacyWitness;
import dev.stannismod.stellurgy.test.contract.NotYetRed;
import dev.stannismod.stellurgy.test.contract.Pins;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * What air IS, asked of the air.
 *
 * <p>Breathability, combustion, toxicity and corrosivity are PREDICATES over a composition, never
 * flags stored beside it — and combustion is decided by the OXIDISER rather than by breathability.
 * Those two together are what closes the defect this slice exists for: the two questions were one
 * boolean, assigned from the BREATHING band, so a room nobody could breathe still lit torches.</p>
 *
 * <p>Not here: the scenarios that depend on where the configured bands sit — the room between the
 * combustion and breathing thresholds, and monotonicity across them — need a loaded configuration,
 * which a unit test does not have.</p>
 */
public class AtmospherePredicatesTest {

    /** Parts per million of an atmosphere, which is the unit these numbers are quoted in. */
    private static long ppm(long partsPerMillion) {
        return partsPerMillion * AirState.PER_PPM;
    }

    @BeforeClass
    public static void bootstrap() {
        MinecraftBootstrap.ensure();
    }

    /** A room at one atmosphere with the oxygen asked for, and nitrogen making up the rest. */
    private static AirState roomWithOxygen(long oxygen) {
        return new AirState(AirState.ONE_ATM - oxygen, oxygen, 0);
    }

    /**
     * Nothing burns in a vacuum, whatever the config says, because there is no oxidiser there.
     */
    @RedWitness(verdict = "no burning", member = "AirState#allowsCombustion",
            at = "if (getTotalPressure() <= VACUUM_CEILING) { return false; }", replacedBy = "if (true) { return true; }",
            fails = "an empty composition has no oxidiser to burn", on = "2026-10-17")
    @RedWitness(verdict = "no breathing", member = "AirState#isBreathableAir",
            at = "if (getTotalPressure() <= VACUUM_CEILING) { return false; }", replacedBy = "if (true) { return true; }",
            fails = "and nothing to breathe", on = "2026-10-17")
    @Test
    public void aVacuumBurnsNothing() {
        assertFalse("an empty composition has no oxidiser to burn", AirState.vacuum().allowsCombustion());
        assertFalse("and nothing to breathe", AirState.vacuum().isBreathableAir());
    }

    /**
     * Toxicity follows the poison: adding one past its limit makes the air toxic, and drawing it off
     * takes the toxicity with it.
     *
     * Pins INV-ATM-22 (A strictly better atmosphere never reads as worse).
     */
    @Pins("INV-ATM-22")
    @RedWitness(verdict = "a poison makes it toxic", member = "AirState#isToxic", at = "return toxicIndex() >= 1.0D;",
            replacedBy = "return toxicIndex() >= 3.0D;", fails = "and adding a poison must make it so", on = "2026-10-17")
    @RedWitness(verdict = "removing it clears it", member = "AirState#draw", at = "set(gas, partialPressure(gas) - taken);",
            replacedBy = "set(gas, partialPressure(gas) - taken / 2);",
            fails = "and removing it must take the toxicity with it", on = "2026-10-17")
    @Test
    public void aPoisonMakesAirToxicAndDrawingItOffClearsIt() {
        AirState clean = roomWithOxygen(ppm(210_000));
        AirState poisoned = roomWithOxygen(ppm(210_000));
        poisoned.add(GasRegistry.CARBON_MONOXIDE,
                GasRegistry.CARBON_MONOXIDE.hazardThreshold() * 2, 293.0D);
        assertFalse("premise: clean air is not toxic", clean.isToxic());
        assertTrue("and adding a poison must make it so", poisoned.isToxic());

        poisoned.draw(GasRegistry.CARBON_MONOXIDE, Long.MAX_VALUE);
        assertFalse("and removing it must take the toxicity with it", poisoned.isToxic());
    }

    /**
     * A poison is judged against ITS OWN limit, so good air is no defence and the predicate needs to
     * know nothing about which gas it is looking at.
     *
     * Pins INV-ATM-23 (A poison is judged against ITS OWN limit, so the same amount of two different gases is not the same hazard, and good air is no).
     */
    @Pins("INV-ATM-23")
    @RedWitness(verdict = "under its own limit", member = "AirState#isToxic", at = "return toxicIndex() >= 1.0D;",
            replacedBy = "return toxicIndex() >= 0.5D;",
            fails = "under its own limit, a poison is not yet poisoning:", on = "2026-10-17")
    @RedWitness(verdict = "a stricter poison is over", member = "AirState#toxicIndex",
            at = "index += (double) entry.getValue() / gas.hazardThreshold();",
            replacedBy = "index += (double) entry.getValue() / GasRegistry.AMMONIA.hazardThreshold();",
            fails = "the SAME amount of a stricter poison is over ITS limit:", on = "2026-10-17")
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
    }

    /**
     * Poisons ADD UP, each by its share of its own limit, and carbon dioxide is one of them past five
     * percent — before it has displaced enough oxygen to suffocate anybody.
     */
    @RedWitness(verdict = "mixture", member = "AirState#toxicIndex",
            at = "index += (double) entry.getValue() / gas.hazardThreshold();",
            replacedBy = "index = Math.max(index, (double) entry.getValue() / gas.hazardThreshold());",
            fails = "two poisons at sixty percent of their limits are past it together:", on = "2026-10-17")
    @RedWitness(verdict = "carbon dioxide", member = "AirState#toxicIndex",
            at = "if (gas.is(GasRole.TOXIC) && gas.hazardThreshold() > 0)",
            replacedBy = "if (gas.is(GasRole.TOXIC) && gas != GasRegistry.CARBON_DIOXIDE && gas.hazardThreshold() > 0)",
            fails = "six percent carbon dioxide is poisonous:", on = "2026-10-17")
    @Test
    public void poisonsAddUpAndCarbonDioxidePastFivePercentIsOne() {
        AirState mixture = roomWithOxygen(ppm(210_000));
        mixture.add(GasRegistry.CARBON_MONOXIDE, GasRegistry.CARBON_MONOXIDE.hazardThreshold() * 6 / 10, 293.0D);
        mixture.add(GasRegistry.SULFUR_DIOXIDE, GasRegistry.SULFUR_DIOXIDE.hazardThreshold() * 6 / 10, 293.0D);
        assertTrue("two poisons at sixty percent of their limits are past it together: " + mixture,
                mixture.isToxic());

        AirState stale = roomWithOxygen(ppm(210_000));
        stale.add(GasRegistry.CARBON_DIOXIDE, ppm(40_000), 293.0D);
        assertFalse("four percent carbon dioxide is stale, not poisonous: " + stale, stale.isToxic());
        stale.add(GasRegistry.CARBON_DIOXIDE, ppm(20_000), 293.0D);
        assertTrue("six percent carbon dioxide is poisonous: " + stale, stale.isToxic());
        assertTrue("while it still has the oxygen to breathe: " + stale, stale.isBreathableAir());
    }

    /**
     * Corrosion needs something corrosive, something to dissolve it into, and heat — and it reads all
     * three rather than switching on one.
     */
    @NotYetRed(prose = "WET IS WORSE, 2026-10-17: AirState#corrosionIndex at `double wet = 1.0D + (double)"
            + " roleTotal(GasRole.SOLVENT) / ONE_ATM;` replaced by `double wet = 1.0D;` (the solvent ignored), and then"
            + " by `double wet = 1.0D / (1.0D + (double) roleTotal(GasRole.SOLVENT) / ONE_ATM);` (water made it"
            + " better): the verdict \"water makes it worse\" stayed GREEN under both. The prose record of 2026-09-30"
            + " read dry=10.0 wet=10.0 under the first.")
    @RedWitness(verdict = "hot is worse", member = "AirState#corrosionIndex",
            at = "double warm = Math.max(0.5D, getTemperatureKelvin() / Math.max(1, ambientKelvin()));",
            replacedBy = "double warm = 1.0D;", fails = "and so does heat: dry=10.0 hot=10.0", on = "2026-10-17")
    @RedWitness(verdict = "clean air", member = "AirState#corrosionIndex", at = "return 0.0D;", replacedBy = "return 0.1D;",
            fails = "clean air attacks nothing expected:<0.0> but was:<0.1>", on = "2026-10-17")
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
    @RedWitness(verdict = "findable by name", member = "GasRegistry#byName", at = "index.put(gas.name(), gas);",
            replacedBy = "index.put(gas.name().toUpperCase(java.util.Locale.ROOT), gas);",
            fails = "a gas must be findable by the name its save data uses:", on = "2026-10-17")
    @LegacyWitness(prose = "one inversion per verdict, 2026-09-30. EVERY GAS HAS A ROLE - a DOUBLE"
            + " inversion, the {@code GasRegistry#HELIUM} at {@code new Gas(\"helium\", \"helium\", 4.0D, 4.2D, 0, GasRole.INERT)} row declared with no role AND {@code Gas#Gas} at"
            + " {@code if (roles.length == 0)} made never to hold: \"a gas with no role has no reason to be"
            + " modelled: Gas[helium []]\". FINDABLE BY NAME - {@code GasRegistry#byName} at"
            + " {@code index.put(gas.name(), gas);} keying by the upper-cased name: \"a gas must be findable by"
            + " the name its save data uses: Gas[nitrogen [INERT]] … but was:&lt;null&gt;\". EVERY HAZARD HAS A"
            + " GAS - the {@code GasRegistry#WATER} at {@code new Gas(\"water\", \"water\", 18.0D, 373.1D, 0, GasRole.SOLVENT)} row made inert: \"a predicate keys on SOLVENT and nothing in"
            + " the registry can raise it\". EVERY POISON HAS A LIMIT - the {@code GasRegistry#AMMONIA} at {@code new Gas(\"ammonia\", \"ammonia\", 17.0D, 239.8D, 300, GasRole.TOXIC, GasRole.FUEL)} row given"
            + " no limit: \"a poison with no limit can never poison anyone: Gas[ammonia [FUEL, TOXIC]]\". (The last"
            + " two were taken while each row was still wrapped in {@code register(...)}; the rows' values are"
            + " unchanged since.)")
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
     * Every gas the registry declares is one a save can name: it is listed, and it is found by the
     * name its composition is written under. A declared gas missing from the list would be read back
     * from a save as unknown and dropped — the composition would lose it without a word.
     */
    @RedWitness(verdict = "found by name", member = "GasRegistry#byName", at = "index.put(gas.name(), gas);",
            replacedBy = "index.put(gas.name().toUpperCase(java.util.Locale.ROOT), gas);",
            fails = "declared gas NITROGEN must be found by its saved name", on = "2026-10-17")
    @LegacyWitness(prose = "one inversion per verdict, 2026-09-30, each in {@code GasRegistry}. LISTED —"
            + " {@code HELIUM} left out of {@code ALL}: \"declared gas HELIUM is missing from the registry's"
            + " list\". FOUND BY NAME — {@code GasRegistry#byName} at {@code index.put(gas.name(), gas);} keying"
            + " by the upper-cased name: \"declared gas NITROGEN must be found by its saved name … but"
            + " was:&lt;null&gt;\". NOTHING EXTRA — {@code NITROGEN} listed in {@code ALL} twice: \"and the list"
            + " holds nothing the registry does not declare expected:&lt;11&gt; but was:&lt;12&gt;\".")
    @Test
    public void everyDeclaredGasIsOneASaveCanName() throws Exception {
        int declared = 0;
        for (java.lang.reflect.Field field : GasRegistry.class.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (field.getType() != Gas.class || !java.lang.reflect.Modifier.isStatic(modifiers)
                    || !java.lang.reflect.Modifier.isPublic(modifiers)) {
                continue;
            }
            Gas gas = (Gas) field.get(null);
            declared++;
            assertTrue("declared gas " + field.getName() + " is missing from the registry's list",
                    GasRegistry.all().contains(gas));
            assertEquals("declared gas " + field.getName() + " must be found by its saved name",
                    gas, GasRegistry.byName(gas.name()));
        }
        assertEquals("and the list holds nothing the registry does not declare",
                declared, GasRegistry.all().size());
    }

    /**
     * A composition survives a save, INCLUDING a substance the three old keys could never name — and
     * a gas the running game no longer knows is dropped rather than guessed at.
     *
     * Pins INV-ATM-24 (A composition survives a save including a substance the three old keys could not name, and a substance this game no longer knows).
     */
    @Pins("INV-ATM-24")
    @RedWitness(verdict = "oxygen", member = "AirState#writeToNBT", at = "entry.getValue());",
            replacedBy = "entry.getKey() == GasRegistry.OXYGEN ? entry.getValue() / 2 : entry.getValue());",
            fails = "oxygen comes back", on = "2026-10-17")
    @RedWitness(verdict = "nitrogen", member = "AirState#writeToNBT", at = "entry.getValue());",
            replacedBy = "entry.getKey() == GasRegistry.NITROGEN ? entry.getValue() / 2 : entry.getValue());",
            fails = "so does the nitrogen", on = "2026-10-17")
    @RedWitness(verdict = "methane", member = "AirState#readFromNBT",
            at = "state.set(GasRegistry.byName(name), gases.getLong(name));",
            replacedBy = "Gas known = GasRegistry.byName(name); if (known == GasRegistry.NITROGEN"
                    + " || known == GasRegistry.OXYGEN || known == GasRegistry.CARBON_DIOXIDE)"
                    + " state.set(known, gases.getLong(name));",
            fails = "and so does a gas the old three keys could not have named", on = "2026-10-17")
    @RedWitness(verdict = "temperature", member = "AirState#writeToNBT",
            at = "nbt.setInteger(\"airK\", temperatureMilliK);", replacedBy = "nbt.setInteger(\"airK\", temperatureMilliK / 2);",
            fails = "and the temperature with them", on = "2026-10-17")
    @RedWitness(verdict = "dropped", member = "AirState#readFromNBT",
            at = "state.set(GasRegistry.byName(name), gases.getLong(name));",
            replacedBy = "Gas into = GasRegistry.byName(name) == null ? GasRegistry.NITROGEN : GasRegistry.byName(name);"
                    + " state.set(into, state.partialPressure(into) + gases.getLong(name));",
            fails = "a substance this game no longer knows is DROPPED, never guessed at", on = "2026-10-17")
    @LegacyWitness(prose = "one inversion per verdict, 2026-09-30. OXYGEN - {@code AirState#writeToNBT} at {@code gases.setLong(entry.getKey().name(), entry.getValue());} writing"
            + " oxygen at half: \"oxygen comes back expected:&lt;210000000&gt; but was:&lt;105000000&gt;\"."
            + " NITROGEN - {@code AirState#writeToNBT} at {@code gases.setLong(entry.getKey().name(), entry.getValue());} writing nitrogen at half: \"so does the nitrogen"
            + " expected:&lt;790000000&gt; but was:&lt;395000000&gt;\". METHANE - {@code AirState#readFromNBT} at {@code state.set(GasRegistry.byName(name), gases.getLong(name));} skipping"
            + " methane on read: \"and so does a gas the old three keys could not have named"
            + " expected:&lt;1234&gt; but was:&lt;0&gt;\". TEMPERATURE - {@code AirState#writeToNBT} at {@code nbt.setInteger(\"airK\", temperatureMilliK);} writing half the"
            + " temperature: \"and the temperature with them expected:&lt;293000&gt; but was:&lt;146500&gt;\"."
            + " DROPPED - {@code AirState#readFromNBT} at {@code state.set(GasRegistry.byName(name), gases.getLong(name));} reading an unknown gas as nitrogen: \"a substance this game no"
            + " longer knows is DROPPED, never guessed at expected:&lt;1000001234&gt; but"
            + " was:&lt;1000006234&gt;\". NOT REACHABLE BY NAME -"
            + " {@code GasRegistry#byName} at {@code return name == null ? null : BY_NAME.get(name);} answering helium for a name it does not know: \"a substance this game does"
            + " not know is not reachable by name expected null, but was:&lt;Gas[helium [INERT]]&gt;\". The known-gas line is a premise"
            + " and is not witnessed.")
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

        // Asked of the registry BEFORE any save carries the unknown name: reading a save goes through
        // the same lookup, so asked afterwards a lookup that guessed would already have failed the
        // DROPPED verdict, and this one could never speak for itself.
        assertNotNull("premise: a known gas is reachable by name", GasRegistry.byName("methane"));
        assertNull("a substance this game does not know is not reachable by name",
                GasRegistry.byName("unobtainium"));

        nbt.getCompoundTag("gases").setLong("unobtainium", 5_000L);
        AirState afterRemoval = AirState.readFromNBT(nbt);
        assertEquals("a substance this game no longer knows is DROPPED, never guessed at",
                read.getTotalPressure(), afterRemoval.getTotalPressure());
    }
}
