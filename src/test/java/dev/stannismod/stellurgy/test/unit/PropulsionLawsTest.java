package dev.stannismod.stellurgy.test.unit;

import dev.stannismod.stellurgy.api.StatsRocket;
import dev.stannismod.stellurgy.api.propulsion.ClassicAscent;
import dev.stannismod.stellurgy.api.propulsion.EngineDesign;
import dev.stannismod.stellurgy.api.propulsion.EngineRegime;
import dev.stannismod.stellurgy.api.propulsion.NuclearThermal;
import dev.stannismod.stellurgy.api.propulsion.PropellantDrain;
import dev.stannismod.stellurgy.ship.control.Actuator;
import dev.stannismod.stellurgy.ship.control.ChemicalMotor;
import dev.stannismod.stellurgy.ship.control.PropellantDemand;
import dev.stannismod.stellurgy.test.contract.Pins;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import dev.stannismod.stellurgy.test.contract.Regions;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The laws of the engine model and the propellant bill, C10 STAT-30..34 and STAT-37, on the pure
 * types they live in ({@code api.propulsion}, {@code ship.control.ChemicalMotor}): every input here
 * is built by this test or drawn from a seeded generator, and nothing reads a world.
 *
 * <p>What this does NOT see: which design a block declares and what the world feeds it (the core
 * stack's power, the registry's fluids, the world's pressure — {@code ChemicalMotorActuators}); the
 * flight's own per-tick wiring of {@link ClassicAscent#climbPerTick} and of the drain
 * ({@code EntityRocket}); the table rows (INV-WGT-09, {@code WeightEngineUnitTest}).</p>
 */
public class PropulsionLawsTest {

    /** A constant: the generator's seed, so a red reproduces bit for bit. */
    private static final long SEED = 490L;
    /**
     * A constant: designs drawn per sweep. Each family is drawn a quarter of the time, so every
     * region below is visited by a few hundred draws at this size; each region's minimum is stated at
     * its check.
     */
    private static final int DESIGNS = 2_000;
    /**
     * A constant: relative tolerance on a law computed in doubles from the same few products and
     * quotients the contract states — a handful of roundings, each half an ulp (1.1e-16).
     */
    private static final double DOUBLE_ROUNDING = 1e-12;

    /** A draw from {@code [lo, hi)} spread evenly over its orders of magnitude. */
    private static double logUniform(Random r, double lo, double hi) {
        return Math.exp(Math.log(lo) + r.nextDouble() * (Math.log(hi) - Math.log(lo)));
    }

    /** The thrust law as C10 STAT-30 states it, from the declared inputs: the oracle, never production. */
    private static double contractThrust(boolean powered, double fMax, double eta, double power, double ve) {
        double flow = fMax;
        if (!powered) {
            return flow;
        }
        return power > 0 ? Math.min(flow, 2 * eta * power / ve) : 0;
    }

    private static void assertClose(String what, double expected, double actual) {
        double tolerance = DOUBLE_ROUNDING * Math.max(1, Math.abs(expected));
        if (Math.abs(expected - actual) > tolerance) {
            fail(what + ": expected " + expected + ", got " + actual);
        }
    }

    /**
     * STAT-30 swept over every family shape: the thrust is {@code min(ṁ_max·v_e, 2ηP/v_e, F_max)} with
     * {@code ṁ_max = F_max/v_e}, the mass flow is {@code F/v_e}, chemistry's thrust ignores power and a
     * power-limited family gives nothing on none. Liveness: where the power covers the structural
     * limit the engine gives exactly {@code F_max} — the law delivers, it does not merely stay under.
     */
    @Pins("STAT-30")
    @Regions(of = "STAT-30", value = {"chemical", "nuclear thermal", "electric", "divertor", "power binds",
            "structure binds", "no power"})
    @RedWitness(member = "EngineRegime#thrust", at = "2 * jetEfficiency * powerWatts / exhaustVelocity",
            replacedBy = "jetEfficiency * powerWatts / exhaustVelocity",
            fails = "STAT-30 thrust", on = "2026-10-17")
    @Test
    public void theThrustLawHoldsForEveryFamilyShape() {
        Random r = new Random(SEED);
        int chemical = 0, nuclear = 0, electric = 0, divertor = 0, powerBinds = 0, structureBinds = 0,
                noPower = 0, deliveredWhole = 0;
        for (int i = 0; i < DESIGNS; i++) {
            double fMax = logUniform(r, 1e2, 1e7);
            double eta = 0.05 + 0.95 * r.nextDouble();
            double ceiling = logUniform(r, 1e3, 1e5);
            double power = r.nextInt(8) == 0 ? 0 : logUniform(r, 1e3, 1e12);
            int family = r.nextInt(4);
            List<EngineRegime> regimes = new ArrayList<>();
            boolean powered;
            if (family == 0) {
                regimes.add(EngineDesign.chemical(fMax, 0.5 + 0.5 * r.nextDouble(), 0.5 + 0.5 * r.nextDouble())
                        .regimeFor(ceiling));
                powered = false;
                chemical++;
            } else if (family == 1) {
                regimes.add(EngineDesign.nuclearThermal(fMax, eta, 0.5 + 0.5 * r.nextDouble()).regimeFor(ceiling));
                powered = true;
                nuclear++;
            } else if (family == 2) {
                regimes.addAll(EngineDesign.electric(fMax, eta, ceiling, logUniform(r, 1e-3, 1)).builtRegimes());
                powered = true;
                electric++;
            } else {
                regimes.addAll(EngineDesign.divertor(fMax, eta, ceiling, ceiling / (1.5 + 8 * r.nextDouble()),
                        logUniform(r, 1e-3, 1)).builtRegimes());
                powered = true;
                divertor++;
            }
            for (EngineRegime regime : regimes) {
                double ve = regime.exhaustVelocity();
                double expected = contractThrust(powered, fMax, eta, power, ve);
                String at = "STAT-30 thrust, family " + family + " F_max=" + fMax + " eta=" + eta + " P=" + power
                        + " v_e=" + ve;
                assertClose(at, expected, regime.thrust(power));
                assertClose("STAT-30 mass flow is F/v_e, " + at, expected / ve, regime.massFlow(regime.thrust(power)));
                if (powered && power == 0) {
                    noPower++;
                } else if (powered && 2 * eta * power / ve < fMax) {
                    powerBinds++;
                } else {
                    structureBinds++;
                    if (Math.abs(regime.thrust(power) - fMax) <= DOUBLE_ROUNDING * fMax) {
                        deliveredWhole++;
                    }
                }
            }
        }
        // Each region is visited by at least a twentieth of what an even split would give it.
        int floor = DESIGNS / 4 / 20;
        assertTrue("arrangement: every family drawn (" + chemical + "/" + nuclear + "/" + electric + "/" + divertor + ")",
                Math.min(Math.min(chemical, nuclear), Math.min(electric, divertor)) >= floor);
        assertTrue("arrangement: every binding drawn (power " + powerBinds + ", structure " + structureBinds
                + ", none " + noPower + ")", Math.min(Math.min(powerBinds, structureBinds), noPower) >= floor);
        assertEquals("STAT-30 liveness: where the power covers it, the engine gives its whole F_max",
                structureBinds, deliveredWhole);
    }

    /**
     * Acceptance 1 of the engine model: at a fixed power a power-limited engine's thrust FALLS as its
     * exhaust velocity rises wherever the power term binds, and a chemical engine's thrust does not
     * depend on the power at all.
     */
    @Pins("STAT-30")
    @RedWitness(member = "EngineRegime#thrust", at = "if (!powerLimited) {", replacedBy = "if (false) {",
            fails = "STAT-30 chemistry", on = "2026-10-17")
    @Test
    public void atFixedPowerAPowerLimitedEngineLosesThrustAsItsExhaustQuickens() {
        Random r = new Random(SEED + 1);
        int falls = 0;
        for (int i = 0; i < DESIGNS; i++) {
            double fMax = logUniform(r, 1e7, 1e9);
            double eta = 0.05 + 0.95 * r.nextDouble();
            double power = logUniform(r, 1e3, 1e9);
            double slow = logUniform(r, 1e3, 5e4);
            double fast = slow * (1.01 + r.nextDouble());
            EngineDesign design = EngineDesign.nuclearThermal(fMax, eta, 1.0);
            double tSlow = design.regimeFor(slow).thrust(power);
            double tFast = design.regimeFor(fast).thrust(power);
            if (2 * eta * power / slow < fMax) {
                assertTrue("STAT-30 power-limited: v_e " + slow + " -> " + fast + " at P=" + power
                        + " must cost thrust, got " + tSlow + " -> " + tFast, tFast < tSlow);
                falls++;
            }
            EngineRegime chem = EngineDesign.chemical(fMax, 1.0, 1.0).regimeFor(slow);
            assertEquals("STAT-30 chemistry: thrust at P=" + power + " vs P=0", chem.thrust(0), chem.thrust(power), 0);
        }
        assertTrue("arrangement: the power term bound in at least a tenth of the draws (" + falls + ")",
                falls >= DESIGNS / 10);
    }

    /**
     * STAT-31 and STAT-37 at the tier-2 actuator: the declared mass flow and power do not move with
     * the outside pressure, the force is the vacuum thrust less {@code p·A_exit} with the bell's area
     * measured as {@code F_max(1−f_sl)/p₀} — so one atmosphere gives {@code f_sl·F_max} — and past
     * separation it is zero, never a pull.
     */
    @Pins({"STAT-31", "STAT-37"})
    @Regions(of = "STAT-31", value = {"vacuum", "one atmosphere", "past separation"})
    @RedWitness(member = "EngineRegime#thrustAt", at = "Math.max(0, thrust(powerWatts) - Math.max(0, ambientPascals) * exitArea)",
            replacedBy = "thrust(powerWatts)", fails = "STAT-31 one atmosphere", on = "2026-10-17")
    @Test
    public void theOutsidePressureMovesTheForceAndNeverTheFlow() {
        Random r = new Random(SEED + 2);
        int separated = 0;
        for (int i = 0; i < DESIGNS; i++) {
            double fMax = logUniform(r, 1e3, 1e7);
            double fSl = 0.3 + 0.7 * r.nextDouble();
            double ceiling = logUniform(r, 1e3, 1e4);
            Map<String, EngineRegime> regimes = new LinkedHashMap<>();
            regimes.put("p", EngineDesign.chemical(fMax, 1.0, fSl).regimeFor(ceiling));
            double p0 = EngineDesign.STANDARD_ATMOSPHERE_PA;
            double beyond = p0 / (1 - fSl) * (1 + r.nextDouble());
            Actuator vacuum = ChemicalMotor.fromRegimes(0, 0, 0, 0, -1, 0, regimes, 0, 0);
            Actuator sea = ChemicalMotor.fromRegimes(0, 0, 0, 0, -1, 0, regimes, 0, p0);
            Actuator past = ChemicalMotor.fromRegimes(0, 0, 0, 0, -1, 0, regimes, 0, beyond);
            String at = "F_max=" + fMax + " f_sl=" + fSl;
            assertClose("STAT-31 vacuum, " + at, fMax, vacuum.maxForce().y());
            assertClose("STAT-31 one atmosphere, " + at, fSl * fMax, sea.maxForce().y());
            assertEquals("STAT-31 past separation pushes nothing, " + at, 0.0, past.maxForce().y(), 0);
            separated++;
            PropellantDemand dv = vacuum.demands().get(0), ds = sea.demands().get(0), dp = past.demands().get(0);
            assertClose("STAT-37 the flow is the pressure's to leave alone, " + at, dv.massFlow(1), ds.massFlow(1));
            assertClose("STAT-37 the flow is the pressure's to leave alone, " + at, dv.massFlow(1), dp.massFlow(1));
            assertClose("STAT-37 flow at full throttle is F_max/v_e, " + at, fMax / ceiling, dv.massFlow(1));
            assertClose("STAT-37 flow is linear in throttle, " + at, 0.5 * dv.massFlow(1), dv.massFlow(0.5));
        }
        assertTrue("arrangement: every draw reached past separation", separated == DESIGNS);
    }

    /**
     * STAT-37's choice where a nozzle's fluid is not decided: the force offered is the LEAST over its
     * regimes, and each regime still declares its own flow and power.
     */
    @Pins("STAT-37")
    @RedWitness(member = "ChemicalMotor#fromRegimes", at = "force = Math.min(force, regime.thrustAt(powerWatts, ambientPascals));",
            replacedBy = "force = regime.thrustAt(powerWatts, ambientPascals);", fails = "STAT-37 least", on = "2026-10-17")
    @Test
    public void aNozzleWhoseFluidIsUndecidedOffersTheLeastOfItsRegimes() {
        Random r = new Random(SEED + 3);
        for (int i = 0; i < DESIGNS; i++) {
            double fMax = logUniform(r, 1e5, 1e7);
            double eta = 0.5 + 0.5 * r.nextDouble();
            double power = logUniform(r, 1e8, 1e10);
            EngineDesign design = EngineDesign.nuclearThermal(fMax, eta, 1.0);
            double fastVe = logUniform(r, 5e3, 1e4);
            double slowVe = fastVe / (1.5 + r.nextDouble());
            // The faster exhaust — the weaker regime when power binds — goes FIRST, so a motor that took
            // its last regime's force rather than the least would be seen.
            Map<String, EngineRegime> regimes = new LinkedHashMap<>();
            regimes.put("fast", design.regimeFor(fastVe));
            regimes.put("slow", design.regimeFor(slowVe));
            Actuator a = ChemicalMotor.fromRegimes(0, 0, 0, 1, 0, 0, regimes, power, 0);
            double least = Math.min(contractThrust(true, fMax, eta, power, slowVe),
                    contractThrust(true, fMax, eta, power, fastVe));
            assertClose("STAT-37 least, P=" + power, least, -a.maxForce().x());
            assertEquals("STAT-37 one demand per regime", 2, a.demands().size());
            for (PropellantDemand d : a.demands()) {
                double ve = "slow".equals(d.propellant()) ? slowVe : fastVe;
                double f = contractThrust(true, fMax, eta, power, ve);
                assertClose("STAT-37 power drawn " + d, f * ve / (2 * eta), d.power(1));
            }
        }
    }

    /**
     * STAT-32's table half: a monopropellant's exhaust velocity is its row's, a pair's its pair row's
     * with the row's mixture ratio, a regime's is that ceiling times the design's cycle fraction — and
     * a propellant with no row has NO exhaust velocity, absent rather than a stand-in.
     */
    @Pins("STAT-32")
    @Regions(of = "STAT-32", value = {"monopropellant", "pair", "no row"})
    @RedWitness(member = "WeightEngine#pairOf", at = "oxidizer.getName().equals(row.oxidizer)", replacedBy = "true",
            fails = "STAT-32 no row", on = "2026-10-17")
    @Test
    public void aPropellantsExhaustVelocityIsItsRowsAndNothingElse() {
        dev.stannismod.stellurgy.util.WeightEngine table = dev.stannismod.stellurgy.util.WeightEngine.fromJson(
                "{\"formatVersion\":" + dev.stannismod.stellurgy.util.WeightEngine.FORMAT_VERSION + ","
                        + "\"fluids\":{\"t_mono\":{\"density\":900.0,\"monopropellantExhaustVelocity\":2100.0},"
                        + "\"t_fuel\":{\"density\":70.0},\"t_ox\":{\"density\":1100.0},\"t_other\":{\"density\":800.0}},"
                        + "\"propellantPairs\":[{\"fuel\":\"t_fuel\",\"oxidizer\":\"t_ox\",\"exhaustVelocity\":4400.0,"
                        + "\"mixtureRatio\":6.0}]}");
        net.minecraft.util.ResourceLocation still = new net.minecraft.util.ResourceLocation("stellurgy", "t_still");
        net.minecraftforge.fluids.Fluid mono = new net.minecraftforge.fluids.Fluid("t_mono", still, still);
        net.minecraftforge.fluids.Fluid fuel = new net.minecraftforge.fluids.Fluid("t_fuel", still, still);
        net.minecraftforge.fluids.Fluid ox = new net.minecraftforge.fluids.Fluid("t_ox", still, still);
        net.minecraftforge.fluids.Fluid other = new net.minecraftforge.fluids.Fluid("t_other", still, still);

        assertEquals("STAT-32 monopropellant row", 2100.0, table.monopropellantExhaustVelocityOf(mono).getAsDouble(), 0);
        EngineDesign design = EngineDesign.chemical(1e5, 0.9, 1.0);
        assertClose("STAT-32 the regime is the row times the cycle", 2100.0 * 0.9,
                design.regimeFor(table.monopropellantExhaustVelocityOf(mono).getAsDouble()).exhaustVelocity());
        assertTrue("STAT-32 pair row", table.pairOf(fuel, ox).isPresent());
        assertEquals("STAT-32 pair row v_e", 4400.0, table.pairOf(fuel, ox).get().exhaustVelocity, 0);
        assertEquals("STAT-32 pair row O/F", 6.0, table.pairOf(fuel, ox).get().mixtureRatio, 0);
        assertFalse("STAT-32 no row: a pair the table does not list", table.pairOf(fuel, other).isPresent());
        assertFalse("STAT-32 no row: the halves swapped are another pair", table.pairOf(ox, fuel).isPresent());
        assertFalse("STAT-32 no row: a fluid that burns in a pair is no monopropellant",
                table.monopropellantExhaustVelocityOf(fuel).isPresent());
    }

    /** STAT-32's nuclear-thermal exhaust velocity: the anchor, and {@code v_e ∝ sqrt(T/M)}. */
    @Pins("STAT-32")
    @Regions(of = "STAT-32", value = {"nuclear thermal"})
    @RedWitness(member = "NuclearThermal#exhaustVelocity", at = "Math.sqrt((temperatureKelvin / ANCHOR_TEMPERATURE)",
            replacedBy = "((temperatureKelvin / ANCHOR_TEMPERATURE)", fails = "STAT-32 sqrt(T/M)", on = "2026-10-17")
    @Test
    public void aNuclearExhaustGoesAsTheRootOfTemperatureOverMolarMass() {
        assertClose("STAT-32 anchor: H2 at 2 700 K", 8_500, NuclearThermal.exhaustVelocity(2_700, 2.016));
        Random r = new Random(SEED + 4);
        for (int i = 0; i < DESIGNS; i++) {
            double t = logUniform(r, 300, 5_000);
            double m = logUniform(r, 1, 100);
            double k = logUniform(r, 1.1, 10);
            double v = NuclearThermal.exhaustVelocity(t, m);
            assertClose("STAT-32 sqrt(T/M): T x" + k + " at T=" + t + " M=" + m, v * Math.sqrt(k),
                    NuclearThermal.exhaustVelocity(t * k, m));
            assertClose("STAT-32 sqrt(T/M): M x" + k + " at T=" + t + " M=" + m, v / Math.sqrt(k),
                    NuclearThermal.exhaustVelocity(t, m * k));
        }
    }

    /**
     * STAT-33 over time: a burn of many ticks at forces drawn per tick, into one tank or a pair split
     * by its mixture ratio. At EVERY tick the millibuckets taken so far, times the mass per
     * millibucket, never exceed the mass the law ejected and trail it by less than one millibucket per
     * tank; the pair's halves keep the ratio; a tick with no force takes nothing.
     */
    @Pins("STAT-33")
    @Regions(of = "STAT-33", value = {"monopropellant", "pair", "less than a millibucket a tick", "no force"})
    @RedWitness(member = "PropellantDrain#draw", at = "owed[i] = total - floor;", replacedBy = "owed[i] = 0;",
            fails = "STAT-33 conservation", on = "2026-10-17")
    @Test
    public void theDrainTakesWhatTheBurnEjectedToWithinAMillibucket() {
        Random r = new Random(SEED + 5);
        int tinyTicks = 0, idleTicks = 0, pairs = 0, monos = 0;
        for (int burn = 0; burn < 200; burn++) {
            boolean pair = burn % 2 == 1;
            double of = pair ? 1 + 8 * r.nextDouble() : 0;
            double[] shares = pair ? PropellantDrain.pairShares(of) : new double[]{1};
            double[] perMb = pair ? new double[]{logUniform(r, 0.05, 2), logUniform(r, 0.05, 2)}
                    : new double[]{logUniform(r, 0.05, 2)};
            double ve = logUniform(r, 1e3, 1e4);
            double[] owed = new double[shares.length];
            double[] takenKg = new double[shares.length];
            double ejected = 0;
            // Enough ticks to carry a fraction across many whole millibuckets: a burn of 2 000 ticks.
            for (int tick = 0; tick < 2_000; tick++) {
                int kind = r.nextInt(10);
                double force = kind == 0 ? 0 : kind == 1 ? logUniform(r, 1, 50) : logUniform(r, 1e3, 1e6);
                double kg = StatsRocket.ejectedMassPerTick(force, ve);
                if (force == 0) {
                    idleTicks++;
                } else if (kg * shares[0] < perMb[0]) {
                    tinyTicks++;
                }
                PropellantDrain.Draw d = PropellantDrain.draw(kg, shares, perMb, owed);
                ejected += kg;
                for (int k = 0; k < shares.length; k++) {
                    if (force == 0) {
                        assertEquals("STAT-33 no force takes nothing", 0, d.millibuckets(k));
                    }
                    owed[k] = d.owed(k);
                    takenKg[k] += d.millibuckets(k) * perMb[k];
                    double owedKg = ejected * shares[k];
                    String at = "STAT-33 conservation, burn " + burn + " tick " + tick + " tank " + k
                            + " ejected " + owedKg + " kg, taken " + takenKg[k] + " kg, mB " + perMb[k] + " kg";
                    assertTrue(at, takenKg[k] <= owedKg * (1 + DOUBLE_ROUNDING) + DOUBLE_ROUNDING);
                    assertTrue(at, owedKg - takenKg[k] < perMb[k] * (1 + DOUBLE_ROUNDING));
                }
            }
            if (pair) {
                pairs++;
                assertEquals("STAT-33 the pair burns at its mixture ratio",
                        of, (takenKg[1] + owed[1] * perMb[1]) / (takenKg[0] + owed[0] * perMb[0]), 1e-9 * of);
            } else {
                monos++;
            }
        }
        assertTrue("arrangement: sub-millibucket ticks " + tinyTicks + ", idle ticks " + idleTicks
                + ", pairs " + pairs + ", monopropellants " + monos,
                tinyTicks > 1_000 && idleTicks > 1_000 && pairs == 100 && monos == 100);
    }

    /**
     * Acceptance 2 of the engine model, STAT-34's law: with no gravity the classic ascent's burn gives
     * exactly what the rocket equation says, {@code Δv = v_e·ln(m₀/m₁)}, so the propellant a craft needs
     * for a Δv grows with what it carries as {@code m_dry·(e^{Δv/v_e} − 1)}. The tolerance is the
     * integrator's own, two ticks' Δv at the dry mass, {@code 2·F·τ/m₁}: a right Riemann sum of an
     * increasing acceleration over-reads the integral by at most one, and the last tick, which burns
     * only what is left, still climbs a whole tick — as the flight does.
     */
    @Pins("STAT-34")
    @RedWitness(member = "ClassicAscent#run", at = "double perTick = StatsRocket.ejectedMassPerTick(thrust, exhaustVelocity);",
            replacedBy = "double perTick = 50.0;", fails = "STAT-34 rocket equation", on = "2026-10-17")
    @Test
    public void thePropellantAClimbNeedsGrowsWithTheLoadAsTheRocketEquationSays() {
        Random r = new Random(SEED + 6);
        for (int i = 0; i < 300; i++) {
            double dry = logUniform(r, 1e3, 1e6);
            double ve = logUniform(r, 1.5e3, 9e3);
            double dv = logUniform(r, 100, 5e3);
            double propellant = dry * (Math.exp(dv / ve) - 1);
            double thrust = (dry + propellant) * StatsRocket.STANDARD_GRAVITY * logUniform(r, 1.2, 20);
            ClassicAscent.Forecast f = ClassicAscent.run(0, Double.MAX_VALUE, thrust, ve, dry + propellant,
                    propellant, 0, 0);
            double tolerance = 2 * thrust * StatsRocket.TICK_SECONDS / dry;
            String at = "STAT-34 rocket equation: dry " + dry + " kg, v_e " + ve + ", propellant " + propellant
                    + " kg, F " + thrust + " N";
            assertTrue(at + ": wanted " + dv + " m/s, got " + f.burnoutSpeed,
                    Math.abs(f.burnoutSpeed - dv) <= tolerance);
        }
    }

    /**
     * STAT-34 under gravity, judged by two independent bounds. Safety: a craft whose IDEAL Δv cannot
     * buy the coast to the line — {@code v_e·ln(m₀/m₁) < sqrt(2·g·h)} — never reaches it, whatever the
     * integrator does with gravity loss. Liveness: a craft at a thrust-to-weight of a hundred, whose
     * ideal Δv exceeds that by a tenth, does reach it.
     *
     * <p>The safety cases run at a thrust whose integrator excess — two ticks' Δv at the dry mass, as in
     * the rocket-equation law above — is at most a fortieth of the coast's Δv, under the tenth they
     * are short by; at a thrust-to-weight of a hundred one tick is itself worth most of a short climb,
     * which is a property of a one-tick step, not of the law.</p>
     */
    @Pins("STAT-34")
    @Regions(of = "STAT-34", value = {"reaches the line", "runs dry short of it"})
    @RedWitness(member = "ClassicAscent#run", at = "motion -= fall;", replacedBy = "motion -= 0;",
            fails = "STAT-34 safety", on = "2026-10-17")
    @Test
    public void aClimbReachesTheLineExactlyWhenItsPropellantCanBuyIt() {
        Random r = new Random(SEED + 7);
        int reached = 0, short_ = 0;
        for (int i = 0; i < 400; i++) {
            double gravity = logUniform(r, 0.1, 3);
            double g = gravity * StatsRocket.STANDARD_GRAVITY;
            // Drawn by the coast's Δv rather than its height, so that even the strongest gravity leaves
            // the safety cases a thrust that climbs.
            double needed = logUniform(r, 400, 3_000);
            double height = needed * needed / (2 * g);
            double ve = logUniform(r, 2e3, 9e3);
            double dry = logUniform(r, 1e3, 1e5);
            boolean enough = i % 2 == 0;
            double idealDv = needed * (enough ? 1.1 : 0.9);
            double propellant = dry * (Math.exp(idealDv / ve) - 1);
            double thrust = (dry + propellant) * g * 100;
            if (!enough) {
                thrust = Math.min(thrust, needed / 40 / 2 * dry / StatsRocket.TICK_SECONDS);
                dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged(
                        "a short craft must still climb: TWR " + thrust / ((dry + propellant) * g), thrust > 1.2 * (dry + propellant) * g);
            }
            ClassicAscent.Forecast f = ClassicAscent.run(0, height, thrust, ve, dry + propellant, propellant,
                    gravity, gravity);
            String at = "g " + gravity + ", line " + height + ", ideal dv " + idealDv + " vs " + needed;
            if (enough) {
                assertTrue("STAT-34 liveness: " + at, f.reaches);
                reached++;
            } else {
                assertFalse("STAT-34 safety: " + at, f.reaches);
                assertTrue("STAT-34 a short climb says how high it gets: " + at + " apex " + f.apexY,
                        f.apexY < height && f.apexY > 0);
                short_++;
            }
        }
        assertTrue("arrangement: both regions visited", reached == 200 && short_ == 200);
    }
}
