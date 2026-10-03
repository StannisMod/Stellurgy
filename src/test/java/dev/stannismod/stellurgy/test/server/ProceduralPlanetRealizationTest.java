package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.RealizedBody;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.CellInfo;
import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;

import org.junit.After;
import org.junit.Test;


import dev.stannismod.stellurgy.dimension.DimensionProperties;

import static org.junit.Assert.assertNotNull;
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
 * <p>SEPARATE-BOOT: a global mutation that cannot be undone. Realizing a body pins its system and
 * writes a dimension into it ({@code UniverseRegistry#realizeBody}), and nothing reverses either for
 * the server's lifetime — there is no un-realize, and {@code UniverseRegistry#remove} drops a whole
 * placement. Every scenario here installs the one seed measured to offer all four arrangements, and
 * two of them assert as their CONTROL that the family they find holds no world yet; on a shared server
 * a sibling that ran first has realized exactly that family (the sweeps find the same bodies), and the
 * control would fail on order rather than on the code. The generator itself is NOT the reason: it is
 * installed per server and restored by {@code gen-reset}.</p>
 */
public class ProceduralPlanetRealizationTest extends AbstractHeadlessServerTest {

    /**
     * A compact star spacing, and it has a floor: a system's bodies stand where their own orbits put
     * them, so a super-cell has to be wide enough to hold one. Below roughly 170 000 cells a system
     * starts losing its outer worlds and below a few cells only the star survives — which is a correct
     * outcome of "a system that will not fit loses BODIES, never scale", and a fixture with no landable
     * body in it. What is compact here is the distance BETWEEN stars, so a bounded sweep finds several.
     *
     * <p>The spacing is a balance knob and nothing here asserts one.</p>
     */
    private static final String GEN_INSTALL = "stellurgytest space gen-install 0.9 2000000 987654321";
    /** In SUPER-CELLS: the probe sweeps the partition the generator itself walks. */
    private static final int SWEEP_RADIUS = 4;

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    @After
    public void restoreGenerator() throws Exception {
        try {
            exec("stellurgytest space gen-reset");
        } catch (Exception ignored) {
        }
    }

    @Test
    public void aProceduralBodyBecomesTheWorldTheScanDescribed() throws Exception {
        String installed = exec(GEN_INSTALL);
        assertTrue("the procedural generator must install: " + installed,
                Reply.of(installed).ok());

        String found = exec("stellurgytest space find-procedural " + SWEEP_RADIUS);
        assertTrue("a dense procedural galaxy must offer a landable body: " + found,
                Reply.of(found).ok());
        String cell = jsonInt(found, "sx") + " " + jsonInt(found, "sy") + " " + jsonInt(found, "sz");
        assertTrue("the body must carry the orbit its physics is derived from: " + found,
                jsonInt(found, "orbitalDist") > 0);

        // CONTROL. Nothing in that cell is a descent target yet — which is the defect this whole path
        // exists to fix, and without measuring it first "descendTarget is true afterwards" would be a
        // statement about a flag that might always have been true.
        CellInfo before = CellInfo.atKey(this::exec, cell);
        assertFalse("no procedural body may be a descent target before it is realized: "
                + before.raw(), anyDescendTarget(before));

        // What the telescope would say, taken BEFORE anything is minted.
        String scan = exec("stellurgytest space derived " + cell);
        assertTrue("the derivation must answer for an unrealized body: " + scan,
                Reply.of(scan).ok());

        RealizedBody realized = RealizedBody.at(this::exec, cell);
        int dim = realized.dim;
        assertTrue("a realized dimension id must be real: " + realized.raw(), dim > 1);

        // The whole contract, field by field. Terrain is deliberately absent from this list: its tier
        // is APPROACH, not TELESCOPE, so the design lets it settle later — but it is compared anyway
        // because the derivation is the single origin of every one of these.
        assertEquals("orbital distance must be materialized, not re-rolled: scan " + scan
                + " vs world " + realized.raw(), jsonInt(scan, "orbitalDist"), realized.orbitalDist);
        assertEquals("gravity must match the scan: " + scan + " vs " + realized.raw(),
                jsonInt(scan, "gravity"), realized.gravityPercent);
        assertEquals("atmospheric pressure must match the scan: " + scan + " vs " + realized.raw(),
                jsonInt(scan, "pressure"), realized.pressure);
        assertEquals("temperature must match the scan: " + scan + " vs " + realized.raw(),
                jsonInt(scan, "temperature"), realized.temperature);
        assertEquals("a breathable atmosphere must match the scan: " + scan + " vs " + realized.raw(),
                jsonBool(scan, "oxygen"), realized.oxygen);
        assertEquals("tidal locking must match the scan: " + scan + " vs " + realized.raw(),
                jsonBool(scan, "locked"), realized.tidallyLocked);
        assertEquals("mass must match the scan: " + scan + " vs " + realized.raw(),
                jsonDouble(scan, "mass"), realized.mass, 1e-6d);
        assertEquals("radius must match the scan: " + scan + " vs " + realized.raw(),
                jsonDouble(scan, "radius"), realized.radius, 1e-6d);
        assertEquals("the star's metallicity must reach the world: " + scan + " vs " + realized.raw(),
                jsonDouble(scan, "metallicity"), realized.metallicity, 1e-6d);
        assertEquals("the terrain source drawn for the type must be the one fixed on the world: "
                + scan + " vs " + realized.raw(), jsonString(scan, "terrainSource"),
                realized.terrainSource);

        // Gravity is DERIVED from the bulk properties, so the world must not merely carry a number that
        // happens to match — the relation has to hold on the world itself.
        double mass = realized.mass;
        double radius = realized.radius;
        assertTrue("a realized world must carry real bulk properties: " + realized.raw(),
                mass > 0d && radius > 0d);
        double expected = Math.max(0.05d, Math.min(4d, mass / (radius * radius)));
        assertEquals("surface gravity must be M/R^2: " + realized.raw(),
                expected * 100d, realized.gravityPercent, 1.5d);

        assertTrue("the body must now advertise itself as a descent target: " + realized.raw(),
                realized.descendTarget);
        assertTrue("a procedural system keeps its synthetic negative star id: " + realized.raw(),
                realized.starId < 0);

        // Idempotency: the trigger is a per-tick proximity check, so asking again is the normal case.
        RealizedBody again = RealizedBody.at(this::exec, cell);
        assertEquals("a second descent must REUSE the world, not mint another: " + again.raw(),
                dim, again.dim);

        // And the world is a world: it loads, and it has ground rather than a column of air.
        String loaded = exec("stellurgytest dim time " + dim);
        assertFalse("the realized dimension must load: " + loaded, Reply.of(loaded).has("error"));
        String sample = exec("stellurgytest worldgen sample " + dim + " 0 0");
        assertFalse("the realized world must generate terrain: " + sample, Reply.of(sample).has("error"));
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
     * <p>Why this needs a running server and not a unit test: the corruption is in the DIMENSION the
     * realizer writes, not in the registry's bookkeeping, so only a real server holds the thing that is
     * wrong.</p>
     */
    @Test
    public void aMoonRealizedBeforeItsParentIsStillAMoon() throws Exception {
        String installed = exec(GEN_INSTALL);
        assertTrue("the procedural generator must install: " + installed,
                Reply.of(installed).ok());

        String found = exec("stellurgytest space find-moon " + SWEEP_RADIUS);
        assertTrue("a dense procedural galaxy must offer a planet with a moon: " + found,
                Reply.of(found).ok());
        // TWO cells, by KEY. A moon has a cell of its own inside its parent's zone, so the family is
        // spread across two addresses and neither is a galactic sector triple: the moon's sectors
        // count cells of ITS PARENT's lattice, and passing them as three numbers would ask about a
        // galactic cell somewhere else entirely.
        String cell = jsonString(found, "cellKey");
        String parentCell = jsonString(found, "parentCellKey");
        int moonVariant = jsonInt(found, "moonVariant");
        int parentVariant = jsonInt(found, "parentVariant");

        // CONTROL. Nothing in the family has a world yet, so the moon below is genuinely realized
        // FIRST - without this the test could pass on a parent that happened to be realized already,
        // which is the one arrangement the bug does not occur in.
        CellInfo before = CellInfo.atKey(this::exec, cell);
        CellInfo beforeParent = CellInfo.atKey(this::exec, parentCell);
        assertFalse("no member of the family may hold a world before the moon is realized: "
                + before.raw(), anyDescendTarget(before));
        assertFalse("...the parent's cell included: " + beforeParent.raw(),
                anyDescendTarget(beforeParent));

        RealizedBody moon = RealizedBody.at(this::exec, cell, moonVariant);
        assertTrue("a moon realized before its parent must still BE a moon: " + moon.raw(),
                moon.moon);
        int parentDim = moon.parent;
        assertTrue("and it must name a real parent dimension: " + moon.raw(), parentDim > 1);
        assertNotEquals("which is not the moon itself", moon.dim, parentDim);

        // The second half of the same corruption: a parentless moon kept its PARENT's distance from
        // the star as its own orbital distance, because that is the number its climate is derived
        // from. A moon's own orbit is around the parent, and the two are different numbers.
        RealizedBody parent = RealizedBody.at(this::exec, parentCell, parentVariant);
        assertEquals("realizing the parent afterwards must reuse the world the moon gave it",
                parentDim, parent.dim);
        assertFalse("the parent is not a moon: " + parent.raw(), parent.moon);
        assertNotEquals("a moon's orbital distance is its own, not its parent's: moon " + moon.raw()
                + " vs parent " + parent.raw(), parent.orbitalDist, moon.orbitalDist);
        // ...and it is its own on the only scale that says so: a moon's own orbit is small, a
        // planet's distance from its star is hundreds of units. A moon that lost its own law is
        // realized at MIN_DISTANCE, i.e. INSIDE its parent, and the assertion above cannot see that
        // — MIN_DISTANCE differs from the parent's number too. This one names the floor.
        assertTrue("a moon realized at the minimum distance has lost its own orbit and sits inside "
                        + "its parent: " + moon.raw(),
                moon.orbitalDist > DimensionProperties.MIN_DISTANCE);
    }

    /**
     * <b>A realized moon is listed under its PLANET, never among its star's planets.</b>
     *
     * <p>A star's planet list is what the system's authored content, the star map and the hologram
     * walk to find the bodies that orbit the STAR; a moon is reached through its parent's children.
     * {@code DimensionProperties#setStar} keeps moons out of that list on purpose. A moon listed there
     * too is a second account of one body: drawn at its own small orbit as though about the star, and
     * built into the system as a planet as well as a moon.</p>
     *
     * <p>Positive half on the same read: the moon's parent IS listed, so an empty or unread list cannot
     * pass the verdict.</p>
     *
     * <p>red-witnessed: 2026-10-01, on the code as it stood before the fix — {@code PlanetRealizer#materialize}
     * at {@code props.setStar(star)} called before the parent was set, plus a second
     * {@code star.addPlanet(props)} after registration: "moon 15 must be listed under its planet 14,
     * not among its star's planets [14, 15]".</p>
     */
    @Test
    public void aRealizedMoonIsNotListedAmongItsStarsPlanets() throws Exception {
        String installed = exec(GEN_INSTALL);
        dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged(
                "the procedural generator must install: " + installed, Reply.of(installed).ok());
        String found = exec("stellurgytest space find-moon " + SWEEP_RADIUS);
        dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged(
                "a dense procedural galaxy must offer a planet with a moon: " + found, Reply.of(found).ok());

        RealizedBody parent = RealizedBody.at(this::exec, jsonString(found, "parentCellKey"),
                jsonInt(found, "parentVariant"));
        RealizedBody moon = RealizedBody.at(this::exec, jsonString(found, "cellKey"),
                jsonInt(found, "moonVariant"));
        dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged(
                "the realized moon must be a moon of the realized planet: moon " + moon.raw()
                        + " / parent " + parent.raw(), moon.moon && moon.parent == parent.dim);

        Reply moonInfo = Reply.of(exec("stellurgytest planet info " + moon.dim));
        Reply star = Reply.of(exec("stellurgytest star get " + moonInfo.integer("starId")));
        java.util.List<Integer> listed = new java.util.ArrayList<>();
        for (int dim : star.intArray("planetDims")) {
            listed.add(dim);
        }
        dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged(
                "the moon's parent planet must be listed under the star (the positive half): " + star,
                listed.contains(parent.dim));
        assertFalse("moon " + moon.dim + " must be listed under its planet " + parent.dim
                + ", not among its star's planets " + listed + ": " + star, listed.contains(moon.dim));
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
                Reply.of(installed).ok());

        String found = exec("stellurgytest space find-moon " + SWEEP_RADIUS + " giant");
        assertTrue("a dense procedural galaxy must offer a gas giant with a moon: " + found,
                Reply.of(found).ok());
        assertTrue("arrangement: the parent must be the kind nothing can descend into: " + found,
                jsonBool(found, "parentGasGiant"));
        String cell = jsonString(found, "cellKey");
        String parentCell = jsonString(found, "parentCellKey");

        RealizedBody moon = RealizedBody.at(this::exec, cell, jsonInt(found, "moonVariant"));
        assertTrue("and it must be a moon, which it can only be if the giant got a record of its own: "
                + moon.raw(), moon.moon);
        assertTrue("naming a real parent dimension: " + moon.raw(), moon.parent > 1);
        assertFalse("the moon itself is not the gas giant: " + moon.raw(), moon.gasGiant);

        // The giant now EXISTS as a place - the family's record carries its dimension - and is still
        // not somewhere to land: it has no surface, so the descent flag every downstream consumer
        // reads stays false for it. Both halves matter; a fix that made the giant landable would pass
        // the moon assertions above and break the game.
        CellInfo after = CellInfo.atKey(this::exec, parentCell);
        CellInfo.Body giantEntry = bodyOfKind(after, "GAS_GIANT");
        assertEquals("the giant must now hold the very dimension the moon calls its parent: "
                + after.raw(), moon.parent, giantEntry.dim);
        assertFalse("and it must still not be a descent target: " + giantEntry,
                giantEntry.descendTarget);

        // A descent aimed at the giant is still refused, which is what "not landable" MEANS here -
        // the flag above is a report, this is the behaviour.
        String refused = exec("stellurgytest space realize " + parentCell + " "
                + jsonInt(found, "parentVariant"));
        assertNotNull("realizing a gas giant as a DESCENT must stay refused: " + refused,
                RealizedBody.refusedBecause(refused));
    }

    /**
     * The one body object of a {@code cell-info} report whose {@code kind} is {@code kind}.
     *
     * <p>Cut out rather than matched against the whole report on purpose: {@code cell-info} lists the
     * family, so a bare {@code contains("\"descendTarget\":false")} over the whole string would be
     * satisfied by any OTHER member of it - an assertion about the wrong body reads exactly like an
     * assertion about the right one.</p>
     */
    private static CellInfo.Body bodyOfKind(CellInfo cellInfo, String kind) {
        for (CellInfo.Body body : cellInfo.systemBodies) {
            if (kind.equals(body.kind)) {
                return body;
            }
        }
        throw new AssertionError("no body of kind " + kind + " in " + cellInfo.raw());
    }

    /** Whether ANY body of the reported family is a descent target — the family-wide control, said
     *  as a read over the list rather than as a substring over the whole report. */
    private static boolean anyDescendTarget(CellInfo cellInfo) {
        for (CellInfo.Body body : cellInfo.systemBodies) {
            if (body.descendTarget) {
                return true;
            }
        }
        return false;
    }

    private static int jsonInt(String json, String key) {
        Reply reply = Reply.of(json);
        assertTrue("missing int '" + key + "' in " + json, reply.has(key));
        return reply.integer(key);
    }

    private static double jsonDouble(String json, String key) {
        double value = Reply.of(json).number(key);
        return value;
    }

    private static boolean jsonBool(String json, String key) {
        Reply reply = Reply.of(json);
        assertTrue("missing boolean '" + key + "' in " + json, reply.has(key));
        return reply.bool(key);
    }

    private static String jsonString(String json, String key) {
        String value = Reply.of(json).text(key);
        return value;
    }
}
