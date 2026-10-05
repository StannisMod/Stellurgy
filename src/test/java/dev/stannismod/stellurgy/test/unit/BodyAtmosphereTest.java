package dev.stannismod.stellurgy.test.unit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.Set;

import org.junit.Test;

import dev.stannismod.stellurgy.atmosphere.AirState;
import dev.stannismod.stellurgy.atmosphere.BodyAtmosphere;
import dev.stannismod.stellurgy.atmosphere.gas.Gas;
import dev.stannismod.stellurgy.atmosphere.gas.GasRegistry;

/**
 * The rule that shares an unauthored body's pressure between gases: one rule for every body, taken
 * from its own mass, size and temperature.
 * <p>
 * The bodies below are the solar system's, with their measured mass and radius in Earth units and
 * their mean surface temperature (one-bar level for the giants). Each is a place where the rule has a
 * known right answer, so each verdict is "the rule reproduces what that body really holds", never a
 * number the rule happened to produce.
 */
public class BodyAtmosphereTest {

    private static final long ONE_ATM = AirState.ONE_ATM;

    /**
     * A world the derivation gave oxygen breathes Earth's mix, whatever its pressure.
     *
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. SHARED OUT — {@code BodyAtmosphere#derive}
     * at {@code air.add(entry.getKey(), Math.round(totalPressure * (entry.getValue() / share)), kelvin);}
     * halving every amount: "the whole stated pressure is shared out expected:&lt;2.0E9&gt; but
     * was:&lt;1.0E9&gt;". OXYGEN — {@code BodyAtmosphere#derive} at {@code if (oxygenated)} made never to
     * hold: "oxygen share expected 0.21 but was 0.0". NITROGEN — {@code BodyAtmosphere#derive} at
     * {@code kept.put(entry.getKey(), (double) entry.getValue());} followed by moving a tenth of the
     * nitrogen into helium: "expected:&lt;0.79&gt; but was:&lt;0.711&gt;".</p>
     */
    @Test
    public void anOxygenatedWorldBreathesEarthsMix() {
        AirState air = BodyAtmosphere.derive(1.0D, 1.0D, 288, false, true, 2L * ONE_ATM);
        AirState earth = AirState.earthLike();
        assertEquals("the whole stated pressure is shared out", 2L * ONE_ATM, air.getTotalPressure(), 2L);
        assertEquals("oxygen share expected " + share(earth, GasRegistry.OXYGEN) + " but was "
                        + share(air, GasRegistry.OXYGEN),
                share(earth, GasRegistry.OXYGEN), share(air, GasRegistry.OXYGEN), 1e-9D);
        assertEquals(share(earth, GasRegistry.NITROGEN), share(air, GasRegistry.NITROGEN), 1e-9D);
    }

    /**
     * Venus: heavy, hot, never rearranged by life — the outgassed air itself, carbon dioxide over
     * nitrogen, with its trace of water still airborne because nothing is cold enough to trap it.
     *
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. WHAT IT HOLDS — {@code BodyAtmosphere#derive}
     * at {@code boolean escapes = retention * gas.molarMass() < ESCAPE_THRESHOLD;} with the threshold
     * raised from 4.6 to 8.0: "Venus holds [nitrogen, carbondioxide], expected [carbondioxide,
     * nitrogen, water]". DOMINANT — {@code BodyAtmosphere#derive} at
     * {@code for (Map.Entry<Gas, Double> entry : (gasGiant ? NEBULAR : OUTGASSED).entrySet())} reading
     * an outgassed inventory with its carbon dioxide and nitrogen fractions swapped: "carbon dioxide
     * dominates Venus: AirState{…carbondioxide…=1609967800, …nitrogen…=44389112218…}".</p>
     */
    @Test
    public void venusKeepsItsOutgassedAir() {
        AirState air = BodyAtmosphere.derive(0.815D, 0.949D, 737, false, false, 92L * ONE_ATM);
        assertGases("Venus", air, GasRegistry.CARBON_DIOXIDE, GasRegistry.NITROGEN, GasRegistry.WATER);
        assertTrue("carbon dioxide dominates Venus: " + air,
                share(air, GasRegistry.CARBON_DIOXIDE) > 0.9D);
    }

    /**
     * Titan: too cold for carbon dioxide to stay airborne, heavy enough for the cold to keep its
     * nitrogen — a nitrogen world, as it really is.
     *
     * <p>red-witnessed: with {@code BodyAtmosphere#derive} at
     * {@code boolean frozen = !gasGiant && kelvin < gas.boilingKelvin();} made false, this fails with
     * "Titan holds [carbondioxide, nitrogen], expected [nitrogen]"; and with the threshold at
     * {@code boolean escapes = retention * gas.molarMass() < ESCAPE_THRESHOLD;} raised to 8.0, with
     * "Titan holds [], expected [nitrogen]" (2026-09-30).</p>
     */
    @Test
    public void titanIsANitrogenWorldBecauseItsCarbonDioxideIsIce() {
        AirState air = BodyAtmosphere.derive(0.0225D, 0.404D, 94, false, false, 145L * ONE_ATM / 100L);
        assertGases("Titan", air, GasRegistry.NITROGEN);
    }

    /**
     * Mars: light but cold enough to hold both of its outgassed bulk gases, and cold enough that its
     * water lies on the ground rather than in the air.
     *
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. KEEPS NITROGEN — {@code BodyAtmosphere#derive}
     * at {@code boolean escapes = retention * gas.molarMass() < ESCAPE_THRESHOLD;} with the threshold
     * raised to 8.0: "Mars holds [carbondioxide], expected [carbondioxide, nitrogen]". FREEZES ITS
     * WATER — {@code BodyAtmosphere#derive} at
     * {@code boolean frozen = !gasGiant && kelvin < gas.boilingKelvin();} made false: "Mars holds
     * [carbondioxide, nitrogen, water], expected [carbondioxide, nitrogen]".</p>
     */
    @Test
    public void marsKeepsCarbonDioxideAndNitrogenAndFreezesItsWater() {
        AirState air = BodyAtmosphere.derive(0.107D, 0.532D, 210, false, false, 6L * ONE_ATM / 1000L);
        assertGases("Mars", air, GasRegistry.CARBON_DIOXIDE, GasRegistry.NITROGEN);
    }

    /**
     * The Moon, Mercury and Ganymede hold nothing: whatever total they are asked to share out, every
     * gas either escapes or freezes, and the honest answer is a vacuum rather than a pressure made of
     * nothing. Ganymede is the one that separates this from Titan — nearly the same size and cold,
     * and its nitrogen still escapes.
     *
     * <p>red-witnessed: one inversion per verdict, 2026-09-30, each at {@code BodyAtmosphere#derive}.
     * MERCURY — at {@code boolean escapes = retention * gas.molarMass() < ESCAPE_THRESHOLD;} with the
     * threshold lowered to 4.0: "Mercury holds [carbondioxide], expected []". GANYMEDE — the same line
     * with the threshold at 4.3, which Mercury still clears: "Ganymede holds [nitrogen], expected []";
     * and {@code boolean frozen = !gasGiant && kelvin < gas.boilingKelvin();} made false: "Ganymede
     * holds [carbondioxide], expected []". MOON — {@code BodyAtmosphere#retention} at
     * {@code return (massEarths / radiusEarths) / (kelvin / EARTH_SURFACE_KELVIN);} inverted to radius
     * over mass: "Moon holds [nitrogen, carbondioxide], expected []". The closing total is not
     * witnessed apart from the Moon's gas set: a vacuum with no gas in it has nothing to total.</p>
     */
    @Test
    public void airlessBodiesAreAVacuumWhateverTheyAreAskedToHold() {
        assertGases("Moon", BodyAtmosphere.derive(0.0123D, 0.273D, 250, false, false, ONE_ATM));
        assertGases("Mercury", BodyAtmosphere.derive(0.0553D, 0.383D, 440, false, false, ONE_ATM));
        assertGases("Ganymede", BodyAtmosphere.derive(0.0248D, 0.413D, 110, false, false, ONE_ATM));
        assertEquals("a vacuum totals nothing", 0L,
                BodyAtmosphere.derive(0.0123D, 0.273D, 250, false, false, ONE_ATM).getTotalPressure());
    }

    /**
     * A giant keeps the nebula it formed from — hydrogen, helium and methane — while a hot one has
     * burnt its methane to carbon monoxide.
     *
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. HOT — {@code BodyAtmosphere#derive} at
     * {@code boolean burnt = gasGiant && gas == GasRegistry.METHANE && kelvin >= METHANE_CEILING_KELVIN;}
     * made false: "hot Jupiter holds [helium, methane, hydrogen], expected [hydrogen, helium]".
     * KEEPS — {@code BodyAtmosphere#retention} at
     * {@code return (massEarths / radiusEarths) / (kelvin / EARTH_SURFACE_KELVIN);} inverted to radius
     * over mass: "Jupiter holds [], expected [hydrogen, helium, methane]".</p>
     */
    @Test
    public void aGiantKeepsItsNebulaAndAHotOneLosesItsMethane() {
        assertGases("Jupiter", BodyAtmosphere.derive(317.8D, 11.21D, 165, true, false, 10L * ONE_ATM),
                GasRegistry.HYDROGEN, GasRegistry.HELIUM, GasRegistry.METHANE);
        assertGases("hot Jupiter", BodyAtmosphere.derive(317.8D, 13.0D, 1500, true, false, 10L * ONE_ATM),
                GasRegistry.HYDROGEN, GasRegistry.HELIUM);
    }

    /**
     * Neptune is colder than methane's boiling point and still holds it: a giant has no surface to
     * freeze onto. This is what keeps the cold trap a rule about rocky worlds.
     *
     * <p>red-witnessed: with {@code BodyAtmosphere#derive} at
     * {@code boolean frozen = !gasGiant && kelvin < gas.boilingKelvin();} applied to giants too, this
     * fails with "Neptune holds [helium, hydrogen], expected [hydrogen, helium, methane]" (2026-09-30).</p>
     */
    @Test
    public void aGiantHasNoColdTrap() {
        assertGases("Neptune", BodyAtmosphere.derive(17.15D, 3.883D, 72, true, false, 10L * ONE_ATM),
                GasRegistry.HYDROGEN, GasRegistry.HELIUM, GasRegistry.METHANE);
    }

    /**
     * More mass never costs a body a gas: across a sweep, everything a lighter body keeps, a heavier
     * one of the same size and temperature keeps too. (Temperature has no such rule — colder holds
     * against escape but freezes out — which is why this sweeps mass alone.)
     *
     * <p>red-witnessed: with {@code BodyAtmosphere#retention} at
     * {@code return (massEarths / radiusEarths) / (kelvin / EARTH_SURFACE_KELVIN);} inverted to radius
     * over mass, this fails with "mass 2.919292602539063 kept nitrogen, mass 4.378938903808595 did not
     * (T=250)" (2026-09-30). The closing count is the sweep's own premise and is not witnessed.</p>
     */
    @Test
    public void moreMassNeverCostsABodyAGas() {
        int gasesSeen = 0;
        for (int kelvin : new int[]{60, 120, 250, 400, 900}) {
            Set<Gas> lighter = null;
            double lighterMass = 0.0D;
            for (double mass = 0.01D; mass < 5.0D; mass *= 1.5D) {
                Set<Gas> kept = BodyAtmosphere.derive(mass, 0.5D, kelvin, false, false, ONE_ATM)
                        .composition().keySet();
                gasesSeen += kept.size();
                if (lighter != null) {
                    for (Gas gas : lighter) {
                        if (!kept.contains(gas)) {
                            fail("mass " + lighterMass + " kept " + gas.name() + ", mass " + mass
                                    + " did not (T=" + kelvin + ")");
                        }
                    }
                }
                lighter = kept;
                lighterMass = mass;
            }
        }
        // The sweep must cross the escape boundary for the verdict above to say anything: a sweep
        // in which every body kept nothing would pass it vacuously.
        assertTrue("the sweep never produced an atmosphere, so it compared nothing", gasesSeen > 0);
    }

    /**
     * A body with no mass or no size has nothing to hold air with, and the rule refuses rather than
     * answers.
     *
     * <p>red-witnessed: with {@code BodyAtmosphere#derive} at
     * {@code if (!(massEarths > 0.0D) || !(radiusEarths > 0.0D))} made never to hold, this fails with
     * "no refusal for mass 0" (2026-09-30).</p>
     */
    @Test
    public void aBodyWithoutBulkIsRefused() {
        try {
            BodyAtmosphere.derive(0.0D, 1.0D, 288, false, false, ONE_ATM);
            fail("no refusal for mass 0");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("mass"));
        }
    }

    private static void assertGases(String body, AirState air, Gas... expected) {
        Set<Gas> held = air.composition().keySet();
        boolean same = held.size() == expected.length;
        for (Gas gas : expected) {
            same &= held.contains(gas);
        }
        if (!same) {
            fail(body + " holds " + names(held) + ", expected " + names(Arrays.asList(expected)));
        }
    }

    private static String names(Iterable<Gas> gases) {
        StringBuilder sb = new StringBuilder("[");
        for (Gas gas : gases) {
            sb.append(sb.length() > 1 ? ", " : "").append(gas.name());
        }
        return sb.append(']').toString();
    }

    private static double share(AirState air, Gas gas) {
        long total = air.getTotalPressure();
        if (total <= 0L) {
            return 0.0D;
        }
        Long held = air.composition().get(gas);
        return held == null ? 0.0D : (double) held / total;
    }
}
