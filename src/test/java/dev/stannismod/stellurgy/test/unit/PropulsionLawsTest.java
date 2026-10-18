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
    @RedWitness(member = "PropellantDemand#forceAt", at = "Math.max(0, vacuumForce - Math.max(0, ambientPascals) * exitArea)",
            replacedBy = "vacuumForce", fails = "STAT-31 one atmosphere", on = "2026-10-17")
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
    @RedWitness(member = "Actuator#leastForce", at = "force = Math.min(force, d.forceAt(ambientPascals));",
            replacedBy = "force = d.forceAt(ambientPascals);", fails = "STAT-37 least", on = "2026-10-17")
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

    // ─── Tier-2 consumption, boost columns and pressure bands: STAT-38..43 ───────────────────────

    /** A constant: one block of the test deck, kg — an input of the experiment, not a fact about production. */
    private static final double DECK_KG = 1_000.0;
    /**
     * A constant: one physics step, SI seconds — an input of the experiment; the law holds for any step,
     * and this is the order of the substrate's own (a sixtieth of an engine second times 1.806).
     */
    private static final double STEP_SECONDS = 0.03;
    /**
     * A constant: STAT-16's residual tolerance, relative to the largest column, as ship-flight-model
     * INV-SFM-01 states it ("1e-6 of the largest column"); STAT-43's band is defined against it.
     */
    private static final double RESIDUAL = 1.0e-6;

    /**
     * Six deck blocks about the origin, one on each axis at one metre: the centre of mass at the origin
     * and the same inertia about every axis, so two drives mirrored across it balance at equal force.
     */
    private static dev.stannismod.stellurgy.ship.mass.ShipMassFrame deck() {
        dev.stannismod.stellurgy.ship.mass.ShipMassFrameBuilder b = new dev.stannismod.stellurgy.ship.mass.ShipMassFrameBuilder();
        for (int s = -1; s <= 1; s += 2) {
            b.add(dev.stannismod.stellurgy.ship.mass.MassContributor.ofBlock(s, 0, 0, DECK_KG,
                    dev.stannismod.stellurgy.ship.mass.MassContributor.Kind.STRUCTURAL));
            b.add(dev.stannismod.stellurgy.ship.mass.MassContributor.ofBlock(0, s, 0, DECK_KG,
                    dev.stannismod.stellurgy.ship.mass.MassContributor.Kind.STRUCTURAL));
            b.add(dev.stannismod.stellurgy.ship.mass.MassContributor.ofBlock(0, 0, s, DECK_KG,
                    dev.stannismod.stellurgy.ship.mass.MassContributor.Kind.STRUCTURAL));
        }
        return b.build();
    }

    private static org.joml.Vector3dc forward() {
        return dev.stannismod.stellurgy.ship.control.ControlFrame.HELM.axis(dev.stannismod.stellurgy.ship.control.ControlAxis.SURGE);
    }

    private static org.joml.Vector3dc up() {
        return dev.stannismod.stellurgy.ship.control.ControlFrame.HELM.axis(dev.stannismod.stellurgy.ship.control.ControlAxis.HEAVE);
    }

    /** A drive at {@code side} metres along the helm's up, pushing forward, at {@code pascals} outside. */
    private static Actuator driveAt(int index, int side, List<PropellantDemand> demands, double pascals) {
        org.joml.Vector3d at = new org.joml.Vector3d(up()).mul(side);
        return Actuator.reactionDrive(new dev.stannismod.stellurgy.ship.control.ActuatorId(0, side, 0, index), at,
                forward(), demands, pascals);
    }

    private static PropellantDemand demand(dev.stannismod.stellurgy.ship.control.Propellant p, double force, double area,
                                           double ve) {
        return new PropellantDemand(p, force, area, force / ve, 0);
    }

    private static int indexOf(dev.stannismod.stellurgy.ship.control.ShipCapability cap, Actuator a) {
        for (int i = 0; i < cap.actuators().size(); i++) {
            if (cap.actuators().get(i).id().equals(a.id())) {
                return i;
            }
        }
        throw new AssertionError("arrangement: " + a + " is not in the capability");
    }

    /**
     * STAT-38: a drive is fed by a regime ALL of whose fluids are aboard; among the fed regimes it runs the
     * one with the least force, and that one alone; a drive none of whose regimes is fed is idle. Its
     * DESIGN — the least over every regime — is not moved by what is aboard.
     */
    @Pins("STAT-38")
    @Regions(of = "STAT-38", value = {"fed", "one of two fed", "none fed"})
    @RedWitness(verdict = "STAT-38 none fed", member = "Actuator#fedBy", at = "fed &= aboard.test(fluid);",
            replacedBy = "fed &= true;", fails = "STAT-38 none fed", on = "2026-10-17")
    @RedWitness(verdict = "STAT-38 both fed least", member = "Actuator#fedBy", at = "f < chosenForce",
            replacedBy = "f > chosenForce", fails = "STAT-38 both fed least", on = "2026-10-17")
    @Test
    public void aDriveRunsOnlyOnWhatIsAboard() {
        Random r = new Random(SEED + 8);
        int fed = 0, oneOfTwo = 0, none = 0;
        for (int i = 0; i < DESIGNS; i++) {
            double fMono = logUniform(r, 1e4, 1e7);
            double fPair = fMono * (1.5 + r.nextDouble());
            double ratio = 1 + 7 * r.nextDouble();
            dev.stannismod.stellurgy.ship.control.Propellant mono = dev.stannismod.stellurgy.ship.control.Propellant.of("m");
            dev.stannismod.stellurgy.ship.control.Propellant pair = dev.stannismod.stellurgy.ship.control.Propellant.pair("f", "o", ratio);
            List<PropellantDemand> two = new ArrayList<>();
            two.add(demand(mono, fMono, 0, 2_000));
            two.add(demand(pair, fPair, 0, 4_000));
            Actuator drive = driveAt(0, 1, two, 0);
            String at = "F_mono=" + fMono + " F_pair=" + fPair;

            Actuator single = driveAt(1, -1, java.util.Collections.singletonList(demand(mono, fMono, 0, 2_000)), 0);
            assertTrue("STAT-38 none fed: half a pair feeds nothing, " + at,
                    drive.fedBy(java.util.Collections.singleton("o")::contains) == null);
            assertTrue("STAT-38 none fed: nothing aboard, " + at,
                    single.fedBy(java.util.Collections.<String>emptySet()::contains) == null);
            none++;

            Actuator fedSingle = single.fedBy(java.util.Collections.singleton("m")::contains);
            assertTrue("STAT-38 fed, " + at, fedSingle != null && fedSingle.feed() != null
                    && "m".equals(fedSingle.feed().propellant()));
            assertClose("STAT-38 fed pushes its regime's force, " + at, fMono, fedSingle.maxForce().length());
            fed++;

            Actuator strongOnly = drive.fedBy(new java.util.HashSet<>(java.util.Arrays.asList("f", "o"))::contains);
            assertTrue("STAT-38 one of two fed, " + at, strongOnly != null && "f+o".equals(strongOnly.feed().propellant()));
            assertClose("STAT-38 one of two fed runs the fed one, " + at, fPair, strongOnly.maxForce().length());
            assertClose("STAT-38 design is the least of all regimes, untouched, " + at, fMono, drive.maxForce().length());
            Actuator both = drive.fedBy(new java.util.HashSet<>(java.util.Arrays.asList("m", "f", "o"))::contains);
            assertTrue("STAT-38 both fed least, " + at, both != null && "m".equals(both.feed().propellant()));
            oneOfTwo++;

        }
        assertTrue("arrangement: every region visited", fed == DESIGNS && oneOfTwo == DESIGNS && none == DESIGNS);
    }

    /**
     * STAT-39: two drives mirrored across the centre — one on a monopropellant, one on a pair — asked for
     * half their clean surge. Covered: each runs at half throttle and the command ejects, per fluid, the
     * declared flow times throttle times the step, a pair split by its O/F. Bites: the scarcest fluid
     * holds the whole command to what it can give, says so, and no fluid is ejected past what was
     * available. Empty: nothing moves and nothing is ejected.
     */
    @Pins("STAT-39")
    @Regions(of = "STAT-39", value = {"covered", "bites", "empty", "pair"})
    @RedWitness(verdict = "STAT-39 bites", member = "CleanAxisScheme#allocate", at = "u[i] *= Math.max(0.0D, afford);",
            replacedBy = "", fails = "STAT-39 bites", on = "2026-10-17")
    @RedWitness(verdict = "STAT-39 pair", member = "PropellantBudget#ejected",
            at = "kg.merge(share.getKey(), mass * share.getValue(), Double::sum);",
            replacedBy = "kg.merge(share.getKey(), mass, Double::sum);", fails = "STAT-39 pair", on = "2026-10-17")
    @Test
    public void aStepEjectsWhatItsThrottlesBurnAndNeverMoreThanIsAboard() {
        Random r = new Random(SEED + 9);
        int covered = 0, bites = 0, empty = 0;
        for (int i = 0; i < 200; i++) {
            double force = logUniform(r, 1e4, 1e6);
            double veMono = logUniform(r, 1.5e3, 3e3);
            double vePair = logUniform(r, 3e3, 4.5e3);
            double ratio = 1 + 7 * r.nextDouble();
            dev.stannismod.stellurgy.ship.control.Propellant mono = dev.stannismod.stellurgy.ship.control.Propellant.of("m");
            dev.stannismod.stellurgy.ship.control.Propellant pair = dev.stannismod.stellurgy.ship.control.Propellant.pair("f", "o", ratio);
            Actuator a = driveAt(0, 1, java.util.Collections.singletonList(demand(mono, force, 0, veMono)), 0);
            Actuator b = driveAt(0, -1, java.util.Collections.singletonList(demand(pair, force, 0, vePair)), 0);
            dev.stannismod.stellurgy.ship.mass.ShipMassFrame mass = deck();
            dev.stannismod.stellurgy.ship.control.ShipCapability cap = dev.stannismod.stellurgy.ship.control.ShipCapability.solve(
                    java.util.Arrays.asList(a, b), mass, dev.stannismod.stellurgy.ship.control.ControlFrame.HELM);
            org.joml.Vector3d want = new org.joml.Vector3d(forward()).mul(force / mass.getTotalMass());
            double eM = 0.5 * force / veMono * STEP_SECONDS;
            double eF = 0.5 * force / vePair * STEP_SECONDS / (1 + ratio);
            double eO = 0.5 * force / vePair * STEP_SECONDS * ratio / (1 + ratio);
            String at = "F=" + force + " ve=" + veMono + "/" + vePair + " O/F=" + ratio;

            Map<String, Double> plenty = new java.util.HashMap<>();
            plenty.put("m", 1e9);
            plenty.put("f", 1e9);
            plenty.put("o", 1e9);
            dev.stannismod.stellurgy.ship.control.ActuatorCommand c = allocate(cap, want, plenty);
            assertFalse("STAT-39 covered is not saturated, " + at, c.isSaturated());
            assertClose("STAT-39 covered pushes what was asked, " + at, force, c.force().dot(forward()));
            assertClose("STAT-39 covered ejects flow x throttle x step, " + at, eM, c.ejectedKg().get("m"));
            assertClose("STAT-39 pair fuel share, " + at, eF, c.ejectedKg().get("f"));
            assertClose("STAT-39 pair oxidizer share, " + at, eO, c.ejectedKg().get("o"));
            covered++;

            double scarce = 0.1 + 0.8 * r.nextDouble();
            Map<String, Double> short_ = new java.util.HashMap<>(plenty);
            short_.put("o", eO * scarce);
            c = allocate(cap, want, short_);
            assertTrue("STAT-39 bites and says so, " + at, c.isSaturated());
            assertTrue("STAT-39 bites: never more than available, " + at + " ejected " + c.ejectedKg().get("o")
                    + " of " + eO * scarce, c.ejectedKg().get("o") <= eO * scarce * (1 + DOUBLE_ROUNDING));
            assertClose("STAT-39 bites: one factor for the whole command, " + at, eM * scarce, c.ejectedKg().get("m"));
            assertClose("STAT-39 bites: the force falls by the same factor, " + at, force * scarce,
                    c.force().dot(forward()));
            bites++;

            Map<String, Double> none = new java.util.HashMap<>(plenty);
            none.put("m", 0.0);
            c = allocate(cap, want, none);
            assertTrue("STAT-39 empty is saturated, " + at, c.isSaturated());
            assertEquals("STAT-39 empty pushes nothing, " + at, 0.0, c.force().length(), 0.0);
            double ejected = 0;
            for (double kg : c.ejectedKg().values()) {
                ejected += kg;
            }
            assertEquals("STAT-39 empty ejects nothing, " + at, 0.0, ejected, 0.0);
            empty++;
        }
        assertTrue("arrangement: every region visited", covered == 200 && bites == 200 && empty == 200);
    }

    private static dev.stannismod.stellurgy.ship.control.ActuatorCommand allocate(
            dev.stannismod.stellurgy.ship.control.ShipCapability cap, org.joml.Vector3dc want, Map<String, Double> pool) {
        return dev.stannismod.stellurgy.ship.control.ControlScheme.cleanAxes().allocate(cap, want, new org.joml.Vector3d(),
                new dev.stannismod.stellurgy.ship.control.MomentumStore(), STEP_SECONDS,
                dev.stannismod.stellurgy.ship.control.StepConditions.of(
                        dev.stannismod.stellurgy.ship.control.PropellantBudget.of(pool), Double.NaN, false));
    }

    /**
     * STAT-40: what a step ejects is owed at once — the next budget is the pool net of it — until a drain
     * settles it; and the body a drain leaves is exactly the body built without what it took, mass,
     * centre and tensor, even a body millions of metres from the origin (the subspace frame's case).
     */
    @Pins("STAT-40")
    @Regions(of = "STAT-40", value = {"net of what was ejected", "drained", "the record"})
    @RedWitness(verdict = "STAT-40 net", member = "PropellantLedger#budget",
            at = "available.put(e.getKey(), Math.max(0.0D, e.getValue() - owed));",
            replacedBy = "available.put(e.getKey(), e.getValue());", fails = "STAT-40 net", on = "2026-10-17")
    @RedWitness(verdict = "STAT-40 the record", member = "ShipMassFrameBuilder#remove", at = "ixy += m * x * y;",
            replacedBy = "ixy -= m * x * y;", fails = "STAT-40 the record", on = "2026-10-17")
    @Test
    public void whatAStepEjectsIsOwedUntilDrainedAndLeavesTheBodyExactly() {
        dev.stannismod.stellurgy.ship.control.PropellantLedger ledger = new dev.stannismod.stellurgy.ship.control.PropellantLedger();
        Map<String, Double> pool = new java.util.HashMap<>();
        pool.put("m", 10.0);
        ledger.settle(java.util.Collections.<String, Double>emptyMap(), pool);
        Map<String, Double> step = new java.util.HashMap<>();
        step.put("m", 3.0);
        ledger.book(step);
        assertEquals("STAT-40 net of what was ejected", 7.0, ledger.budget().available("m"), 0.0);
        Map<String, Double> drained = new java.util.HashMap<>();
        drained.put("m", 2.0);
        Map<String, Double> left = new java.util.HashMap<>();
        left.put("m", 8.0);
        ledger.settle(drained, left);
        assertEquals("STAT-40 drained: what is still owed", 1.0, ledger.undrained().get("m"), 0.0);
        assertEquals("STAT-40 net after the drain", 7.0, ledger.budget().available("m"), 0.0);
        ledger.unmetered();
        assertFalse("STAT-40 unmetered owes nothing", ledger.budget().isMetered());

        Random r = new Random(SEED + 10);
        int bodies = 0;
        for (int i = 0; i < 300; i++) {
            double ox = 5.12e6 * r.nextDouble(), oy = 64 * r.nextDouble(), oz = 1e5 * (r.nextDouble() - 0.5);
            List<dev.stannismod.stellurgy.ship.mass.MassContributor> kept = new ArrayList<>();
            List<dev.stannismod.stellurgy.ship.mass.MassContributor> taken = new ArrayList<>();
            List<dev.stannismod.stellurgy.ship.mass.MassContributor> takenFar = new ArrayList<>();
            int n = 2 + r.nextInt(30);
            for (int k = 0; k < n; k++) {
                double x = r.nextInt(12) + 0.5, y = r.nextInt(6) + 0.5, z = r.nextInt(12) + 0.5;
                double kg = logUniform(r, 1, 1e4);
                dev.stannismod.stellurgy.ship.mass.MassContributor.Kind kind = k % 3 == 0
                        ? dev.stannismod.stellurgy.ship.mass.MassContributor.Kind.REACTION
                        : dev.stannismod.stellurgy.ship.mass.MassContributor.Kind.STRUCTURAL;
                if (kind == dev.stannismod.stellurgy.ship.mass.MassContributor.Kind.REACTION && r.nextBoolean()) {
                    taken.add(dev.stannismod.stellurgy.ship.mass.MassContributor.ofBlock(x, y, z, kg, kind));
                    takenFar.add(dev.stannismod.stellurgy.ship.mass.MassContributor.ofBlock(x + ox, y + oy, z + oz, kg, kind));
                } else {
                    kept.add(dev.stannismod.stellurgy.ship.mass.MassContributor.ofBlock(x, y, z, kg, kind));
                }
            }
            if (taken.isEmpty() || kept.isEmpty()) {
                continue;
            }
            List<dev.stannismod.stellurgy.ship.mass.MassContributor> all = new ArrayList<>(kept);
            all.addAll(taken);
            dev.stannismod.stellurgy.ship.mass.ShipMassFrame whole =
                    new dev.stannismod.stellurgy.ship.mass.ShipMassFrameBuilder().addAll(all).build().translated(ox, oy, oz);
            dev.stannismod.stellurgy.ship.mass.ShipMassFrame rest =
                    new dev.stannismod.stellurgy.ship.mass.ShipMassFrameBuilder().addAll(kept).build().translated(ox, oy, oz);
            double[] got = dev.stannismod.stellurgy.ship.mass.ShipMassFrameBuilder.remove(whole.getTotalMass(),
                    whole.getCentreOfMass(), whole.getInertia(), takenFar);
            String at = "body " + i + " far by " + ox;
            assertTrue("STAT-40 the record: something is left, " + at, got != null);
            double scale = rest.getTotalMass();
            assertClose("STAT-40 the record: mass, " + at, rest.getTotalMass(), got[0]);
            org.joml.Vector3dc c = rest.getCentreOfMass();
            double centreError = new org.joml.Vector3d(got[1], got[2], got[3]).distance(c);
            assertTrue("STAT-40 the record: centre off by " + centreError + ", " + at, centreError < 1e-6);
            org.joml.Matrix3dc t = rest.getInertia();
            double[] expect = {t.m00(), t.m11(), t.m22(), t.m10(), t.m20(), t.m21()};
            // Removing is a subtraction from the WHOLE body's moments, so its rounding is on the whole body's
            // scale: a few ulps of positions millions of metres out, times the masses taken, is ~1e-10 of it.
            org.joml.Matrix3dc w = whole.getInertia();
            double tensorScale = Math.max(Math.abs(w.m00()), Math.max(Math.abs(w.m11()), Math.abs(w.m22())));
            for (int k = 0; k < 6; k++) {
                assertTrue("STAT-40 the record: tensor component " + k + " " + got[4 + k] + " vs " + expect[k] + ", " + at,
                        Math.abs(got[4 + k] - expect[k]) <= 1e-8 * tensorScale);
            }
            bodies++;
        }
        assertTrue("arrangement: enough bodies drained, " + bodies, bodies >= 100);
    }

    /**
     * STAT-42 on an asymmetric pair: drive A (up) has only a cruise column of 100 kN; drive B (down) a
     * cruise column of 60 kN and a boost column of 150 kN. Clean surge needs equal forces, so cruise
     * authority is 2·60 and boost authority 2·min(100, 150): the solver boosts B, the drive that limits,
     * and raises A to full. Switch off, a command past cruise uses no boost; switch on, a command inside
     * cruise uses none; past cruise it is delivered, and B's two columns never exceed one capacity.
     */
    @Pins("STAT-42")
    @Regions(of = "STAT-42", value = {"switch off", "within cruise", "past cruise", "asymmetric"})
    @RedWitness(verdict = "STAT-42 switch off", member = "CleanAxisScheme#allocate",
            at = "if (amount > recipe.authority && conditions.boost()) {", replacedBy = "if (amount > recipe.authority) {",
            fails = "STAT-42 switch off", on = "2026-10-17")
    @RedWitness(verdict = "STAT-42 within cruise", member = "CleanAxisScheme#allocate",
            at = "if (amount > recipe.authority && conditions.boost()) {", replacedBy = "if (conditions.boost()) {",
            fails = "STAT-42 within cruise", on = "2026-10-17")
    @RedWitness(verdict = "STAT-42 asymmetric", member = "ShipCapability#solve",
            at = "Recipe boostSustained = massless ? sustained : solveOne(fixed, mass, frame, d, true, true);",
            replacedBy = "Recipe boostSustained = sustained;", fails = "STAT-42 asymmetric", on = "2026-10-17")
    @Test
    public void boostIsASecondColumnUnderOneCapacityTakenOnlyPastCruise() {
        dev.stannismod.stellurgy.ship.control.Propellant p = dev.stannismod.stellurgy.ship.control.Propellant.of("m");
        Actuator a = driveAt(0, 1, java.util.Collections.singletonList(demand(p, 100e3, 0, 4e3)), 0);
        Actuator bCruise = driveAt(0, -1, java.util.Collections.singletonList(demand(p, 60e3, 0, 4e3)), 0);
        Actuator bBoost = Actuator.boostOf(bCruise, java.util.Collections.singletonList(demand(p, 150e3, 0, 1.5e3)));
        dev.stannismod.stellurgy.ship.mass.ShipMassFrame mass = deck();
        dev.stannismod.stellurgy.ship.control.ShipCapability cap = dev.stannismod.stellurgy.ship.control.ShipCapability.solve(
                java.util.Arrays.asList(a, bCruise, bBoost), mass, dev.stannismod.stellurgy.ship.control.ControlFrame.HELM);
        dev.stannismod.stellurgy.ship.control.ControlDirection surge = dev.stannismod.stellurgy.ship.control.ControlDirection.SURGE_POSITIVE;
        dev.stannismod.stellurgy.ship.control.Endurance sustained = dev.stannismod.stellurgy.ship.control.Endurance.SUSTAINED;
        assertClose("STAT-42 asymmetric: cruise authority", 2 * 60e3, cap.authority(surge, sustained));
        assertClose("STAT-42 asymmetric: boost authority", 2 * 100e3, cap.boostAuthority(surge, sustained));
        int ia = indexOf(cap, a), ic = indexOf(cap, bCruise), ib = indexOf(cap, bBoost);
        double m = mass.getTotalMass();

        dev.stannismod.stellurgy.ship.control.ActuatorCommand off = boostAllocate(cap, 180e3 / m, false);
        assertEquals("STAT-42 switch off: no boost column runs", 0.0, off.throttle(ib), 0.0);
        assertTrue("STAT-42 switch off: past cruise is short and said so", off.isSaturated()
                && off.force().dot(forward()) <= 120e3 * (1 + DOUBLE_ROUNDING));

        dev.stannismod.stellurgy.ship.control.ActuatorCommand inside = boostAllocate(cap, 100e3 / m, true);
        assertEquals("STAT-42 within cruise: no boost column runs", 0.0, inside.throttle(ib), 0.0);
        assertClose("STAT-42 within cruise: delivered", 100e3, inside.force().dot(forward()));

        dev.stannismod.stellurgy.ship.control.ActuatorCommand past = boostAllocate(cap, 180e3 / m, true);
        assertFalse("STAT-42 past cruise: delivered whole", past.isSaturated());
        assertClose("STAT-42 past cruise: delivered", 180e3, past.force().dot(forward()));
        assertTrue("STAT-42 past cruise: the limiting drive is boosted", past.throttle(ib) > 0);
        assertTrue("STAT-42 past cruise: one capacity, " + past.throttle(ic) + " + " + past.throttle(ib),
                past.throttle(ic) + past.throttle(ib) <= 1 + DOUBLE_ROUNDING);
        assertTrue("STAT-42 past cruise: the clean axis holds, torque " + past.torque().length(),
                past.torque().length() <= RESIDUAL * 100e3);
        assertClose("STAT-42 past cruise: the other drive is raised to carry its half", 90e3 / 100e3, past.throttle(ia));
    }

    private static dev.stannismod.stellurgy.ship.control.ActuatorCommand boostAllocate(
            dev.stannismod.stellurgy.ship.control.ShipCapability cap, double accel, boolean boost) {
        return dev.stannismod.stellurgy.ship.control.ControlScheme.cleanAxes().allocate(cap,
                new org.joml.Vector3d(forward()).mul(accel), new org.joml.Vector3d(),
                new dev.stannismod.stellurgy.ship.control.MomentumStore(), STEP_SECONDS,
                dev.stannismod.stellurgy.ship.control.StepConditions.of(
                        dev.stannismod.stellurgy.ship.control.PropellantBudget.unmetered(), Double.NaN, boost));
    }

    /**
     * STAT-43 on two mirrored drives with different bells, solved at 50 kPa: the band's half-width is the
     * contract's — STAT-16's tolerance on the largest column over the recipe's pressure slope — inside it
     * the force is each drive's affine law at the step's pressure and the off-axis torque stays inside the
     * tolerance; outside it the capability says it no longer holds.
     */
    @Pins("STAT-43")
    @Regions(of = "STAT-43", value = {"inside", "outside"})
    @RedWitness(verdict = "STAT-43 outside", member = "ShipCapability#holdsAt",
            at = "Math.abs(pascals - ambientPascals) <= bandHalfWidth", replacedBy = "true",
            fails = "STAT-43 outside", on = "2026-10-17")
    @RedWitness(verdict = "STAT-43 inside", member = "Actuator#wrenchAtPressure",
            at = "return wrenchOf(new Vector3d(direction).mul(leastForce(demands, ambientPascals)), centre);",
            replacedBy = "return wrenchAbout(centre);", fails = "STAT-43 inside", on = "2026-10-17")
    @Test
    public void aRecipeHoldsOverItsPressureBandAndFollowsTheAffineLawInside() {
        double p0 = 50e3, vac = 100e3, areaA = 0.1, areaB = 0.3;
        dev.stannismod.stellurgy.ship.control.Propellant p = dev.stannismod.stellurgy.ship.control.Propellant.of("m");
        Actuator a = driveAt(0, 1, java.util.Collections.singletonList(demand(p, vac, areaA, 3e3)), p0);
        Actuator b = driveAt(0, -1, java.util.Collections.singletonList(demand(p, vac, areaB, 3e3)), p0);
        dev.stannismod.stellurgy.ship.mass.ShipMassFrame mass = deck();
        dev.stannismod.stellurgy.ship.control.ShipCapability cap = dev.stannismod.stellurgy.ship.control.ShipCapability.solve(
                java.util.Arrays.asList(a, b), mass, dev.stannismod.stellurgy.ship.control.ControlFrame.HELM);
        double fA = vac - p0 * areaA, fB = vac - p0 * areaB;
        // The only recipe that runs a drive is clean surge: B full, A held to B's force. The arms are one metre.
        double uA = fB / fA, uB = 1;
        // Both arms are one metre and perpendicular to the push, so the largest force column and the largest
        // torque column are both fA, and the force and torque slopes are equal: one bound.
        double largest = fA;
        double slope = uA * areaA + uB * areaB;
        double w = RESIDUAL * largest / slope;
        assertClose("STAT-43 the band is the contract's", w, cap.bandHalfWidth());

        double pIn = p0 + 0.5 * w;
        assertTrue("STAT-43 inside holds", cap.holdsAt(pIn) && cap.holdsAt(p0 - 0.5 * w));
        dev.stannismod.stellurgy.ship.control.ActuatorCommand c = dev.stannismod.stellurgy.ship.control.ControlScheme.cleanAxes()
                .allocate(cap, new org.joml.Vector3d(forward()).mul(fB / mass.getTotalMass()), new org.joml.Vector3d(),
                        new dev.stannismod.stellurgy.ship.control.MomentumStore(), STEP_SECONDS,
                        dev.stannismod.stellurgy.ship.control.StepConditions.of(
                                dev.stannismod.stellurgy.ship.control.PropellantBudget.unmetered(), pIn, false));
        double ua = c.throttle(indexOf(cap, a)), ub = c.throttle(indexOf(cap, b));
        double affine = ua * (vac - pIn * areaA) + ub * (vac - pIn * areaB);
        assertClose("STAT-43 inside: the force is the affine law at the step's pressure", affine, c.force().dot(forward()));
        assertTrue("STAT-43 inside: off-axis torque " + c.torque().length() + " within the tolerance",
                c.torque().length() <= RESIDUAL * largest);

        assertFalse("STAT-43 outside", cap.holdsAt(p0 + 1.01 * w));
        assertFalse("STAT-43 outside", cap.holdsAt(p0 - 1.01 * w));
    }
}
