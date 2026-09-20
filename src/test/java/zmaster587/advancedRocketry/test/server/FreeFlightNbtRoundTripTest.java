package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.RocketList;
import zmaster587.advancedRocketry.test.Reply;
import org.junit.Test;


import zmaster587.advancedRocketry.test.FixtureSite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Save-compat contract for {@code EntityRocket} Free Flight state.
 *
 * <p>The FF attitude quaternion, flight mode, flight-assist toggle and velocity
 * setpoint are persisted by {@code writeEntityToNBT} / {@code readEntityFromNBT}
 * (each with its own legacy-default branch). This pins that a full save/load
 * cycle preserves them, and that a legacy save missing the FF keys degrades to
 * the documented defaults (upright identity attitude, flight-assist ON).
 *
 * <p>"saves must survive" is a project invariant: a read/write asymmetry (a key
 * written but not read, a swapped component, an un-normalised load, a wrong
 * default branch) would silently corrupt stored rockets with NO other test
 * failing. The {@code entity rocket-nbt-roundtrip} probe sets a canonical
 * non-default FF state, drives the real save path into a fresh peer, and also
 * reads a legacy (FF-keys-stripped) copy so both directions are covered.
 */
public class FreeFlightNbtRoundTripTest extends AbstractSharedServerTest {

    private static final String BUILDER_POS = "builderPos";

    private static String ok(java.util.List<String> resp) {
        return String.join("\n", resp);
    }

    private static double num(String json, String key) {
        assertTrue("response missing numeric key " + key + ": " + json, Reply.of(json).has(key));
        return Reply.of(json).number(key);
    }

    private static boolean bool(String json, String key) {
        Reply reply = Reply.of(json);
        assertTrue("response missing boolean key " + key + ": " + json, reply.has(key));
        return reply.bool(key);
    }

    private static String str(String json, String key) {
        assertTrue("response missing string key " + key + ": " + json, Reply.of(json).has(key));
        return Reply.of(json).text(key);
    }

    private int buildAndAssemble(FixtureSite site) throws Exception {
        // The site owns the coordinates; these aliases keep the
        // body below unchanged, so what moved is visible in one place.
        final int baseX = site.x, baseY = site.y, baseZ = site.z;
        // FIRST link: the volume this craft is built and flown in is EMPTY. The site
        // stands in open air, so this ASSERTS rather than digs - anything standing here
        // means the arrangement is wrong, and it is said now instead of arriving many
        // links later wearing some mechanic's name.
        site.requireClear(cmd -> ok(client().execute(cmd)), 2, 10,
                "the craft is built and flown in this volume");

        String fixture = ok(client().execute(
                "artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " simple"));
        assertTrue("fixture failed: " + fixture, Reply.of(fixture).ok());
        int[] bp = Reply.of(fixture).blockPos(BUILDER_POS);
        assertTrue("fixture missing builderPos: " + fixture, bp != null);
        int bx = bp[0];
        int by = bp[1];
        int bz = bp[2];

        String assemble = ok(client().execute(
                "artest rocket assemble 0 " + bx + " " + by + " " + bz));
        assertTrue("assemble failed: " + assemble, Reply.of(assemble).ok());

        String list = ok(client().execute("artest rocket list 0"));
        java.util.List<RocketList.Entry> built = RocketList.of(list);
        assertTrue("rocket list empty after assemble: " + list, !built.isEmpty());
        return built.get(built.size() - 1).id;
    }

    @Test
    public void freeFlightStateSurvivesNbtRoundTrip() throws Exception {
        int id = buildAndAssemble(FixtureSite.openAir(0, 3300, 700));

        String r = ok(client().execute("artest entity rocket-nbt-roundtrip 0 " + id));
        assertTrue("round-trip probe failed: " + r, Reply.of(r).ok());

        // Flight mode survives.
        assertEquals("flight mode must survive save/load: " + r,
                "FREE_FLIGHT", str(r, "peerMode"));

        // Attitude quaternion survives component-for-component (double -> float
        // -> double on save, so allow float precision).
        double tol = 1e-3;
        assertEquals("quat W must survive: " + r, num(r, "srcQuatW"), num(r, "peerQuatW"), tol);
        assertEquals("quat X must survive: " + r, num(r, "srcQuatX"), num(r, "peerQuatX"), tol);
        assertEquals("quat Y must survive: " + r, num(r, "srcQuatY"), num(r, "peerQuatY"), tol);
        assertEquals("quat Z must survive: " + r, num(r, "srcQuatZ"), num(r, "peerQuatZ"), tol);
        // Sanity: the round-tripped attitude is genuinely non-identity, so the
        // assertions above are not trivially satisfied by an all-zero write.
        assertTrue("round-trip attitude must be non-identity: " + r,
                Math.abs(num(r, "peerQuatX")) + Math.abs(num(r, "peerQuatY"))
                        + Math.abs(num(r, "peerQuatZ")) > 0.1);

        // Flight-assist toggle + velocity setpoint survive.
        assertTrue("flight-assist ON must survive: " + r, bool(r, "peerFaOn"));
        assertEquals("FA fwd setpoint must survive: " + r, 0.3d, num(r, "peerFaFwd"), tol);
        assertEquals("FA right setpoint must survive: " + r, -0.2d, num(r, "peerFaRight"), tol);
        assertEquals("FA up setpoint must survive: " + r, 0.5d, num(r, "peerFaUp"), tol);
    }

    @Test
    public void legacySaveMissingFreeFlightKeysDefaultsSafely() throws Exception {
        int id = buildAndAssemble(FixtureSite.openAir(0, 3340, 700));

        String r = ok(client().execute("artest entity rocket-nbt-roundtrip 0 " + id));
        assertTrue("round-trip probe failed: " + r, Reply.of(r).ok());

        // A save with no ffQuat* keys must load as the upright identity attitude.
        double tol = 1e-3;
        assertEquals("legacy attitude must default to identity W: " + r, 1.0d, num(r, "legacyQuatW"), tol);
        assertEquals("legacy attitude must default to identity X: " + r, 0.0d, num(r, "legacyQuatX"), tol);
        assertEquals("legacy attitude must default to identity Y: " + r, 0.0d, num(r, "legacyQuatY"), tol);
        assertEquals("legacy attitude must default to identity Z: " + r, 0.0d, num(r, "legacyQuatZ"), tol);

        // A save with no flightAssistOn key must default flight-assist ON.
        assertTrue("legacy flight-assist must default ON: " + r, bool(r, "legacyFaOn"));
    }
}
