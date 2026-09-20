package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.Reply;
import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import org.junit.Assume;
import org.junit.Test;


import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Regression guard (finding L2) for the null-station guard at
 * {@code TileGuidanceComputer.getTransBodyInjection(...)} (~line 296).
 *
 * <p>Reasonable use: a classic-mode rocket carrying a guidance computer with a
 * PLANET destination chip runs its in-space launch-burn calc while positioned in
 * an empty grid cell not over any station (the inter-station void). {@code
 * getSpaceStationFromBlockCoords} returns null for that off-station position, and
 * the {@code destinationSpaceStation}/{@code INVALID_PLANET} short-circuit is
 * bypassed because the destination is a real planet. Formerly the unguarded {@code
 * currentSpaceStation.getOrbitingPlanetId()} dereferenced null and crashed; the fix
 * folds {@code currentSpaceStation == null} into the early-return, degrading to no
 * trans-body burn (the base launch-clearance burn still applies). (After the C076
 * grid-mapping fix the perimeter reach-sliver now maps to its own station, so this
 * null path is reached only when genuinely off-station, as here.)</p>
 *
 * <p>The {@code guidance launch-seq} probe drives the real
 * {@code getLaunchSequence(spaceDimId, offSlotPos)} on a placed guidance computer
 * (faithful — the burn calc depends only on currentDim/currentPos/slot-0 chip,
 * not on being rocket-embedded). This pins the corrected contract: off-slot in-space
 * launch-burn no longer throws and returns a real (non-sentinel) burn value. Edge
 * reachability (the sliver), but a real survival-reachable path.</p>
 */
public class TileGuidanceComputerOffSlotBurnNpeTest extends AbstractHeadlessServerTest {

    private static final int SPACE_DIM = -2;
    private static final String AR_DIMS = "arDimensions";
    private static final String BURN = "burn";

    @Test
    public void offSlotPlanetLaunchBurnInSpaceDimDegradesToBaseBurn() throws Exception {
        ok(exec("artest dim load " + SPACE_DIM));

        int destDim = firstPlanetDim();
        Assume.assumeTrue("needs a registered AR planet dim as launch destination", destDim != Integer.MIN_VALUE);

        // A station exists somewhere (models 'the player has a station'); it does
        // NOT occupy the off-slot cell we launch from.
        String create = exec("artest station create 0");
        assertTrue("station must create: " + create, Reply.of(create).ok());

        // Off-station: an empty grid cell far from the created station. After the C076
        // grid-mapping fix, getSpaceStationFromBlockCoords(4608,·,4608) reverse-maps to grid
        // (2,2) → spiral index 18 → no station → null (the created station sits at index 1).
        // The band. This stands in the SPACE dimension, which is void, so the lift is not about
        // escaping terrain — it is about one definition of where a fixture stands instead of a 100
        // nobody chose. The X and Z are load-bearing (they reverse-map to an empty grid cell); the
        // Y is not, and now says so.
        int x = 4608, y = zmaster587.advancedRocketry.test.FixtureSite.OPEN_AIR_Y, z = 4608;
        ok(exec("artest fill " + SPACE_DIM + " " + (x - 1) + " " + (y - 1) + " " + (z - 1)
                + " " + (x + 1) + " " + (y + 1) + " " + (z + 1) + " minecraft:air"));
        String place = exec("artest place " + SPACE_DIM + " " + x + " " + y + " " + z
                + " advancedrocketry:guidanceComputer");
        assertTrue("guidance computer must place: " + place,
                Reply.of(place).ok() || Reply.of(place).bool("placed"));

        String r = exec("artest guidance launch-seq " + SPACE_DIM + " " + x + " " + y + " " + z + " " + destDim);
        assertTrue("probe must run: " + r, Reply.of(r).ok());
        // `has` is false for an absent field AND for a JSON null, which is the claim; the needle
        // was one rendering of it and also matched the string inside any other field.
        assertFalse("launch position must be off any station (proves the null path): " + r,
                Reply.of("artest guidance launch-seq", r).has("stationAtPos"));
        assertTrue("chip must be programmed to the real planet dim so the INVALID_PLANET short-circuit "
                        + "is bypassed and the guarded null-station path is reached: " + r,
                String.valueOf(destDim).equals(Reply.of(r).text("chipDim")));
        assertTrue("L2 null-station guard: off-slot in-space launch-burn must NOT throw — "
                        + "TileGuidanceComputer folds a null currentSpaceStation into the early return. Got: " + r,
                (!Reply.of(r).bool("threw")));
        int burn = extractInt(BURN, r);
        assertTrue("a real burn must be returned (not the probe's Integer.MIN_VALUE 'did not run' sentinel), "
                        + "and it must be non-negative — the base launch-clearance burn with no trans-body "
                        + "contribution: got burn=" + burn + " in " + r,
                burn != Integer.MIN_VALUE && burn >= 0);
        assertTrue("server survives", client().isAlive());
    }

    private static int extractInt(String field, String s) {
        Reply reply = Reply.of(s);
        assertTrue("field `" + field + "` not found in: " + s, reply.has(field));
        return reply.integer(field);
    }

    private int firstPlanetDim() throws Exception {
        String list = exec("artest dim list");
        for (int d : Reply.of("artest dim list", list).intArray(AR_DIMS)) {
            if (d != 0 && d != -1 && d != SPACE_DIM) return d;
        }
        return Integer.MIN_VALUE;
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    private static String ok(String resp) {
        return resp;
    }
}
