package zmaster587.advancedRocketry.test.server;

import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;

import org.junit.After;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * <b>A procedural planet becomes somewhere you can stand, and it is the planet the scan described.</b>
 *
 * <p>Before this batch the generator filled the galaxy with bodies carrying {@code INVALID_PLANET}, so
 * {@code isDescendTarget()} was false for every one of them and a system full of planets had nowhere to
 * land. This drives the real realization path on a real server and measures three things that are easy
 * to claim and easy to get wrong:</p>
 *
 * <ol>
 *   <li><b>The scan and the landing agree.</b> Mass, atmosphere, temperature, gravity and water are
 *       promised to a telescope from across the system, so the world that is minted has to MATERIALIZE
 *       those numbers rather than roll fresh ones. The test compares the realized dimension against the
 *       derivation's own answer, read before anything was minted — never against a literal it wrote
 *       itself, which would pass just as well if both sides were wrong together.</li>
 *   <li><b>Realization is idempotent.</b> The trigger is a per-tick proximity check, so a second ask
 *       must reuse the world rather than mint another.</li>
 *   <li><b>The world is real.</b> It loads, it has ground, and the body now advertises itself as a
 *       descent target — the flag every downstream consumer reads.</li>
 * </ol>
 *
 * <p>Per-method harness on purpose: this installs a procedural generator, which is a JVM-global, and a
 * shared server would carry it into every class that ran after it.</p>
 */
public class ProceduralPlanetRealizationE2ETest extends AbstractHeadlessServerTest {

    /**
     * A compact star spacing, and it has a floor: a system's bodies stand where their own orbits put
     * them, so a super-cell has to be wide enough to hold one. Below roughly 170 000 cells a system
     * starts losing its outer worlds and below a few cells only the star survives — which is a correct
     * outcome of "a system that will not fit loses BODIES, never scale", and a fixture with no landable
     * body in it. What is compact here is the distance BETWEEN stars, so a bounded sweep finds several.
     *
     * <p>The spacing is a balance knob and nothing here asserts one.</p>
     */
    private static final String GEN_INSTALL = "artest space gen-install 0.9 2000000 987654321";
    /** In SUPER-CELLS: the probe sweeps the partition the generator itself walks. */
    private static final int SWEEP_RADIUS = 4;

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    @After
    public void restoreGenerator() throws Exception {
        try {
            exec("artest space gen-reset");
        } catch (Exception ignored) {
        }
    }

    @Test
    public void aProceduralBodyBecomesTheWorldTheScanDescribed() throws Exception {
        String installed = exec(GEN_INSTALL);
        assertTrue("the procedural generator must install: " + installed,
                installed.contains("\"ok\":true"));

        String found = exec("artest space find-procedural " + SWEEP_RADIUS);
        assertTrue("a dense procedural galaxy must offer a landable body: " + found,
                found.contains("\"ok\":true"));
        String cell = jsonInt(found, "sx") + " " + jsonInt(found, "sy") + " " + jsonInt(found, "sz");
        assertTrue("the body must carry the orbit its physics is derived from: " + found,
                jsonInt(found, "orbitalDist") > 0);

        // CONTROL. Nothing in that cell is a descent target yet — which is the defect this whole path
        // exists to fix, and without measuring it first "descendTarget is true afterwards" would be a
        // statement about a flag that might always have been true.
        String before = exec("artest space cell-info " + cell);
        assertTrue("cell-info must answer: " + before, before.contains("\"ok\":true"));
        assertFalse("no procedural body may be a descent target before it is realized: " + before,
                before.contains("\"descendTarget\":true"));

        // What the telescope would say, taken BEFORE anything is minted.
        String scan = exec("artest space derived " + cell);
        assertTrue("the derivation must answer for an unrealized body: " + scan,
                scan.contains("\"ok\":true"));

        String realized = exec("artest space realize " + cell);
        assertTrue("realization must mint a world: " + realized, realized.contains("\"ok\":true"));
        int dim = jsonInt(realized, "dim");
        assertTrue("a realized dimension id must be real: " + realized, dim > 1);

        // The whole contract, field by field. Terrain is deliberately absent from this list: its tier
        // is APPROACH, not TELESCOPE, so the design lets it settle later — but it is compared anyway
        // because the derivation is the single origin of every one of these.
        assertEquals("orbital distance must be materialized, not re-rolled: scan " + scan
                + " vs world " + realized, jsonInt(scan, "orbitalDist"), jsonInt(realized, "orbitalDist"));
        assertEquals("gravity must match the scan: " + scan + " vs " + realized,
                jsonInt(scan, "gravity"), jsonInt(realized, "gravity"));
        assertEquals("atmospheric pressure must match the scan: " + scan + " vs " + realized,
                jsonInt(scan, "pressure"), jsonInt(realized, "pressure"));
        assertEquals("temperature must match the scan: " + scan + " vs " + realized,
                jsonInt(scan, "temperature"), jsonInt(realized, "temperature"));
        assertEquals("a breathable atmosphere must match the scan: " + scan + " vs " + realized,
                jsonBool(scan, "oxygen"), jsonBool(realized, "oxygen"));
        assertEquals("tidal locking must match the scan: " + scan + " vs " + realized,
                jsonBool(scan, "locked"), jsonBool(realized, "locked"));
        assertEquals("mass must match the scan: " + scan + " vs " + realized,
                jsonDouble(scan, "mass"), jsonDouble(realized, "mass"), 1e-6d);
        assertEquals("radius must match the scan: " + scan + " vs " + realized,
                jsonDouble(scan, "radius"), jsonDouble(realized, "radius"), 1e-6d);
        assertEquals("the star's metallicity must reach the world: " + scan + " vs " + realized,
                jsonDouble(scan, "metallicity"), jsonDouble(realized, "metallicity"), 1e-6d);
        assertEquals("the terrain source drawn for the type must be the one fixed on the world: "
                + scan + " vs " + realized, jsonString(scan, "terrainSource"),
                jsonString(realized, "terrainSource"));

        // Gravity is DERIVED from the bulk properties, so the world must not merely carry a number that
        // happens to match — the relation has to hold on the world itself.
        double mass = jsonDouble(realized, "mass");
        double radius = jsonDouble(realized, "radius");
        assertTrue("a realized world must carry real bulk properties: " + realized,
                mass > 0d && radius > 0d);
        double expected = Math.max(0.05d, Math.min(4d, mass / (radius * radius)));
        assertEquals("surface gravity must be M/R^2: " + realized,
                expected * 100d, jsonInt(realized, "gravity"), 1.5d);

        assertTrue("the body must now advertise itself as a descent target: " + realized,
                realized.contains("\"descendTarget\":true"));
        assertTrue("a procedural system keeps its synthetic negative star id: " + realized,
                jsonInt(realized, "starId") < 0);

        // Idempotency: the trigger is a per-tick proximity check, so asking again is the normal case.
        String again = exec("artest space realize " + cell);
        assertTrue("a second descent must succeed: " + again, again.contains("\"ok\":true"));
        assertEquals("a second descent must REUSE the world, not mint another: " + again,
                dim, jsonInt(again, "dim"));

        // And the world is a world: it loads, and it has ground rather than a column of air.
        String loaded = exec("artest dim time " + dim);
        assertFalse("the realized dimension must load: " + loaded, loaded.contains("\"error\""));
        String sample = exec("artest worldgen sample " + dim + " 0 0");
        assertFalse("the realized world must generate terrain: " + sample, sample.contains("\"error\""));
        assertNotEquals("a realized planet must have ground under its sky: " + sample,
                "minecraft:air", jsonString(sample, "topBlock"));
    }

    // ─── tiny JSON readers (the probe surface is flat JSON on purpose) ─────────

    /**
     * <b>A moon reached before its parent is still a moon.</b>
     *
     * <p>Moon-ness is carried by a parent DIMENSION id, so a moon realized while its parent has no
     * world was written down as a plain planet standing at the parent's own distance from the star -
     * silently, and permanently, since nothing re-parented it afterwards. The ordering is ordinary
     * play: a moon orbits at a few parent radii, so it is often the nearer body when a ship closes on
     * the family.</p>
     *
     * <p>What makes this an e2e and not a unit test: the corruption is in the DIMENSION the realizer
     * writes, not in the registry's bookkeeping, so only a real server holds the thing that is wrong.</p>
     */
    @Test
    public void aMoonRealizedBeforeItsParentIsStillAMoon() throws Exception {
        String installed = exec(GEN_INSTALL);
        assertTrue("the procedural generator must install: " + installed,
                installed.contains("\"ok\":true"));

        String found = exec("artest space find-moon " + SWEEP_RADIUS);
        assertTrue("a dense procedural galaxy must offer a planet with a moon: " + found,
                found.contains("\"ok\":true"));
        String cell = jsonInt(found, "sx") + " " + jsonInt(found, "sy") + " " + jsonInt(found, "sz");
        int moonVariant = jsonInt(found, "moonVariant");
        int parentVariant = jsonInt(found, "parentVariant");

        // CONTROL. Nothing in the family has a world yet, so the moon below is genuinely realized
        // FIRST - without this the test could pass on a parent that happened to be realized already,
        // which is the one arrangement the bug does not occur in.
        String before = exec("artest space cell-info " + cell);
        assertFalse("no member of the family may hold a world before the moon is realized: " + before,
                before.contains("\"descendTarget\":true"));

        String moon = exec("artest space realize " + cell + " " + moonVariant);
        assertTrue("the moon must be realizable on its own account: " + moon,
                moon.contains("\"ok\":true"));
        assertTrue("a moon realized before its parent must still BE a moon: " + moon,
                jsonBool(moon, "moon"));
        int parentDim = jsonInt(moon, "parent");
        assertTrue("and it must name a real parent dimension: " + moon, parentDim > 1);
        assertNotEquals("which is not the moon itself", jsonInt(moon, "dim"), parentDim);

        // The second half of the same corruption: a parentless moon kept its PARENT's distance from
        // the star as its own orbital distance, because that is the number its climate is derived
        // from. A moon's own orbit is around the parent, and the two are different numbers.
        String parent = exec("artest space realize " + cell + " " + parentVariant);
        assertTrue("the parent must answer with the world it was just given: " + parent,
                parent.contains("\"ok\":true"));
        assertEquals("realizing the parent afterwards must reuse the world the moon gave it",
                parentDim, jsonInt(parent, "dim"));
        assertFalse("the parent is not a moon: " + parent, jsonBool(parent, "moon"));
        assertNotEquals("a moon's orbital distance is its own, not its parent's: moon " + moon
                + " vs parent " + parent, jsonInt(parent, "orbitalDist"), jsonInt(moon, "orbitalDist"));
    }

    /**
     * <b>A gas giant's moon is a moon, and its parent never becomes a place you can stand.</b>
     *
     * <p>The half of the same defect that is not an ordering accident. A gas giant is not a descent
     * target, so no descent ever realizes it - which made "the parent has no world yet" permanent for
     * every one of its up-to-five moons rather than a race one could lose. The parent is therefore
     * given a properties record and, having no surface, no walkable dimension: that distinction is
     * the whole reason this is safe to do.</p>
     */
    @Test
    public void aGasGiantsMoonIsAMoonAndTheGiantStaysUnlandable() throws Exception {
        String installed = exec(GEN_INSTALL);
        assertTrue("the procedural generator must install: " + installed,
                installed.contains("\"ok\":true"));

        String found = exec("artest space find-moon " + SWEEP_RADIUS + " giant");
        assertTrue("a dense procedural galaxy must offer a gas giant with a moon: " + found,
                found.contains("\"ok\":true"));
        assertTrue("arrangement: the parent must be the kind nothing can descend into: " + found,
                jsonBool(found, "parentGasGiant"));
        String cell = jsonInt(found, "sx") + " " + jsonInt(found, "sy") + " " + jsonInt(found, "sz");

        String moon = exec("artest space realize " + cell + " " + jsonInt(found, "moonVariant"));
        assertTrue("a gas giant's moon must be realizable: " + moon, moon.contains("\"ok\":true"));
        assertTrue("and it must be a moon, which it can only be if the giant got a record of its own: "
                + moon, jsonBool(moon, "moon"));
        assertTrue("naming a real parent dimension: " + moon, jsonInt(moon, "parent") > 1);
        assertFalse("the moon itself is not the gas giant: " + moon, jsonBool(moon, "gasGiant"));

        // The giant now EXISTS as a place - the family's record carries its dimension - and is still
        // not somewhere to land: it has no surface, so the descent flag every downstream consumer
        // reads stays false for it. Both halves matter; a fix that made the giant landable would pass
        // the moon assertions above and break the game.
        String after = exec("artest space cell-info " + cell);
        String giantEntry = bodyOfKind(after, "GAS_GIANT");
        assertEquals("the giant must now hold the very dimension the moon calls its parent: " + after,
                jsonInt(moon, "parent"), jsonInt(giantEntry, "dim"));
        assertFalse("and it must still not be a descent target: " + giantEntry,
                jsonBool(giantEntry, "descendTarget"));

        // A descent aimed at the giant is still refused, which is what "not landable" MEANS here -
        // the flag above is a report, this is the behaviour.
        String refused = exec("artest space realize " + cell + " " + jsonInt(found, "parentVariant"));
        assertTrue("realizing a gas giant as a DESCENT must stay refused: " + refused,
                refused.contains("\"ok\":false"));
    }

    /**
     * The one body object of a {@code cell-info} report whose {@code kind} is {@code kind}.
     *
     * <p>Cut out rather than matched against the whole report on purpose: {@code cell-info} lists the
     * family, so a bare {@code contains("\"descendTarget\":false")} over the whole string would be
     * satisfied by any OTHER member of it - an assertion about the wrong body reads exactly like an
     * assertion about the right one.</p>
     */
    private static String bodyOfKind(String cellInfo, String kind) {
        Matcher m = Pattern.compile("\\{[^{}]*\"kind\"\\s*:\\s*\"" + Pattern.quote(kind) + "\"[^{}]*\\}")
                .matcher(cellInfo);
        assertTrue("no body of kind " + kind + " in " + cellInfo, m.find());
        return m.group();
    }

    private static int jsonInt(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        assertTrue("missing int '" + key + "' in " + json, m.find());
        return Integer.parseInt(m.group(1));
    }

    private static double jsonDouble(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?[\\d.eE+-]+)")
                .matcher(json);
        assertTrue("missing number '" + key + "' in " + json, m.find());
        return Double.parseDouble(m.group(1));
    }

    private static boolean jsonBool(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(true|false)").matcher(json);
        assertTrue("missing boolean '" + key + "' in " + json, m.find());
        return Boolean.parseBoolean(m.group(1));
    }

    private static String jsonString(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        assertTrue("missing string '" + key + "' in " + json, m.find());
        return m.group(1);
    }
}
