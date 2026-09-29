package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.Reply;
import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import dev.stannismod.stellurgy.test.ShipIdentity;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Becoming a ship, and stopping being one, is announced exactly once per transition — and the
 * announcement says WHICH transition it was.
 *
 * <p><b>Why an announcement rather than a question.</b> Anything that must act at the moment a craft
 * becomes a ship — an authoritative recompute of its mass, a registration built at first contact, a
 * durable record minted at birth — cannot find that moment by asking "is it named yet" over and over.
 * Each subsystem that polls arrives at its own private answer about which tick the craft started
 * existing on, and there are enough of them that the private answers would disagree about what the
 * craft was when it was born.</p>
 *
 * <p><b>Why the count is the property, not the state.</b> An edge leaves nothing behind in the world
 * it changes: afterwards the ship is simply named, and that looks identical whether the edge fired
 * once, three times, or never. So these drive the transitions a craft actually goes through and read
 * what was announced, by identity.</p>
 *
 * <p><b>Why the causes must be distinguishable.</b> A consumer minting a durable record for a new
 * vessel wants only the first transition; a consumer rebuilding derived state wants all of them. If
 * an assembly and a crossing announce the same thing, the first consumer mints a second record for a
 * craft that already had one, and nothing downstream can tell the two vessels apart again.
 *
 * <p><b>Two methods, because the two arrangements are different and each one already works
 * elsewhere.</b> The load cycle needs a craft the world is free to drop and fetch back; the crossing
 * needs a craft cut and re-pasted, and its geometry — same Z, one hop along X, into clear sky at
 * {@link #SKY_Y} — is copied unchanged from the crossing test that already does this reliably. An
 * earlier single-method version ran the crossing AFTER the load cycle, onto a destination of its own
 * choosing, and the re-assembly was refused as "ship too big": building a new arrangement beside one
 * that already works is what cost the runs.</p>
 *
 * <p><b>One craft, and it is asserted.</b> Every reading is keyed on the ship's own identity, but the
 * identity itself comes from a probe that answers about a ship near a point. That is only exact while
 * the world holds one, so the recorder's own ship count is checked at the same moment — an
 * arrangement that quietly grew a second craft fails here rather than reporting a plausible number
 * about the wrong one.</p>
 *
 * <p>Needs the physics mod: without it there are no ships to name and nothing announces anything.</p>
 */
public class ShipNamingEdgeIsAnnouncedOncePerTransitionE2ETest extends AbstractHeadlessServerTest {

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");

    private static final int BASE_Z = 9400, BUILD_Y = 80, SKY_Y = 150;
    /** One craft per method, each on its own X lane, so neither can see the other's leftovers. */
    private static final int LANE_CYCLE_X = 9400, LANE_CROSS_X = 9800;
    /** The hop the proven crossing test uses. */
    private static final int HOP = 160;
    /** How far a ship's own pose may sit from the anchor it was assembled on. */
    private static final double POSE_TOLERANCE = 64.0;

    /** A craft is built, dropped, and fetched back: ASSEMBLED, then UNLOADED, then LOADED — each once. */
    @Test
    public void buildingDroppingAndFetchingBackAreThreeDistinctAnnouncements() throws Exception {

        exec("stellurgytest vs permaload true");
        assertTrue("the recorder must start empty, or the counts below are somebody else's",
                Reply.of(exec("stellurgytest vs lifecycle reset")).ok());

        String shipUuid = theShipBuiltBy(buildShipAt(LANE_CYCLE_X));

        String afterAssembly = lifecycle(shipUuid);
        assertEquals("the world must hold exactly one craft for the identity above to be this one; "
                        + "with a second one present every count below could be about the other: "
                        + afterAssembly, 1, extractInt(afterAssembly, "ships"));
        assertEquals("a craft that has just been built must be announced as ASSEMBLED exactly once: "
                + afterAssembly, 1, extractInt(afterAssembly, "assembled"));
        assertEquals("a fresh build is not a paste - a consumer that mints a durable record only for a "
                        + "genuinely new vessel would mint nothing at all if this were reported as one: "
                        + afterAssembly, 0, extractInt(afterAssembly, "pasted"));
        assertEquals("a fresh build is not a load: " + afterAssembly, 0, extractInt(afterAssembly, "loaded"));
        assertEquals("nothing has gone away yet: " + afterAssembly, 0, extractInt(afterAssembly, "unnamed"));

        // Nothing holds a ship loaded on a headless server once permanent loading is off: there is no
        // player for the world's own pass to measure a distance to, so it drops the craft by itself.
        exec("stellurgytest vs permaload false");
        assertTrue("the craft never unloaded, so the un-naming half is not being exercised: " + recorder(),
                waitUntilLoadedShips(0));

        String afterUnload = lifecycle(shipUuid);
        assertEquals("dropping the ship object must be announced exactly once: " + afterUnload,
                1, extractInt(afterUnload, "unloaded"));
        assertEquals("an unloaded craft still EXISTS - it is registered and on disk and will be back. "
                        + "Reporting it as destroyed would tell every consumer holding something durable "
                        + "for this vessel to throw it away: " + afterUnload,
                0, extractInt(afterUnload, "destroyed"));
        assertEquals("unloading is not a second assembly: " + afterUnload,
                1, extractInt(afterUnload, "assembled"));

        exec("stellurgytest vs permaload true");
        exec("stellurgytest vs load-ships 0");
        assertTrue("the craft never came back, so the LOADED edge is not being exercised: " + recorder(),
                waitUntilLoadedShips(1));

        String afterLoad = lifecycle(shipUuid);
        assertEquals("coming back must be announced exactly once: " + afterLoad,
                1, extractInt(afterLoad, "loaded"));
        assertEquals("a craft that was merely reloaded was not built again - a consumer keyed on "
                        + "ASSEMBLED would mint a second durable record for it every time the world "
                        + "brought it back: " + afterLoad, 1, extractInt(afterLoad, "assembled"));
    }

    /** A craft that crosses is announced as PASTED, never as a second birth. */
    @Test
    public void aCrossingIsAnnouncedAsAPasteAndNotAsANewBuild() throws Exception {

        exec("stellurgytest vs permaload true");
        assertTrue("the recorder must start empty", Reply.of(exec("stellurgytest vs lifecycle reset")).ok());

        buildShipAt(LANE_CROSS_X);
        assertTrue("this leg cuts a LOADED source, so the source must be loaded first: " + recorder(),
                waitUntilShipIsAt(LANE_CROSS_X, BUILD_Y));

        // The production crossing: the blocks are cut out and pasted elsewhere, and the craft is
        // re-registered around them. The vessel is the same one; only its registration is new.
        int dstX = LANE_CROSS_X + HOP;
        String repack = exec("stellurgytest vs ship-repack 0 " + LANE_CROSS_X + " " + BUILD_Y + " " + BASE_Z
                + " " + dstX + " " + SKY_Y + " " + BASE_Z);
        assertTrue("ARRANGEMENT: the crossing must actually run, or nothing below is a paste: " + repack,
                Reply.of(repack).ok());
        String crossedUuid = extractString(repack, "shipUuid");
        assertTrue("the crossing reported no identity, so its announcement cannot be attributed: " + repack,
                crossedUuid != null);
        assertTrue("the crossed craft never arrived at " + dstX + "," + SKY_Y + "; repack=" + repack
                + " " + recorder(), waitUntilShipIsAt(dstX, SKY_Y));

        String afterPaste = lifecycle(crossedUuid);
        assertEquals("a craft re-registered around pasted blocks must be announced as PASTED exactly "
                        + "once: " + afterPaste, 1, extractInt(afterPaste, "pasted"));
        assertEquals("a crossing is not a new build. This is the distinction the whole cause enum "
                        + "exists for: reported as ASSEMBLED, a vessel would acquire a second birth "
                        + "record every time it crossed - and the crossing KEEPS the identity, so that "
                        + "second record would land on the very same key as the first: " + afterPaste,
                1, extractInt(afterPaste, "assembled"));
    }

    // --- observation ------------------------------------------------------------------------------

    /** What was announced for one ship, by identity. */
    private String lifecycle(String shipUuid) throws Exception {
        String reply = exec("stellurgytest vs lifecycle " + shipUuid);
        assertTrue("the lifecycle probe refused the identity " + shipUuid + ": " + reply,
                Reply.of(reply).ok());
        return reply;
    }

    /** The whole recorder, for a failure message: it separates "none for this ship" from "none at all". */
    private String recorder() throws Exception {
        return exec("stellurgytest vs lifecycle 00000000-0000-0000-0000-000000000000");
    }

    /**
     * The identity of the one loaded ship near {@code (x, y, BASE_Z)}, asserted to exist. Positional,
     * and only defensible because the caller checks the recorder's own ship count — see the class
     * javadoc.
     */
    private String theShipBuiltBy(String assembleReply) throws Exception {
        assertTrue("no craft became a ship: " + recorder(), waitUntilLoadedShips(1));
        // Named by the assembly that created it, never by proximity: the identity chain starts at
        // the moment the craft was made, so no neighbouring hull can answer in its place.
        return ShipIdentity.physicsIdOf(this::exec, 0, ShipIdentity.nameFromAssembly(assembleReply));
    }

    /** Is there a loaded ship whose own pose is at {@code (x,y,BASE_Z)}? A question about a PLACE. */
    private boolean shipIsAt(int x, int y) throws Exception {
        return ShipIdentity.aLoadedShipIsAt(this::exec, 0, x, y, BASE_Z, POSE_TOLERANCE);
    }

    private boolean waitUntilShipIsAt(int x, int y) throws Exception {
        for (int i = 0; i < 40; i++) {
            if (shipIsAt(x, y)) {
                return true;
            }
            Thread.sleep(250);
        }
        return false;
    }

    private int loadedShips() throws Exception {
        return extractInt(exec("stellurgytest vs ship-count 0"), "count");
    }

    private boolean waitUntilLoadedShips(int wanted) throws Exception {
        for (int i = 0; i < 40; i++) {
            if (loadedShips() == wanted) {
                return true;
            }
            Thread.sleep(250);
        }
        return false;
    }

    // --- arrangement ------------------------------------------------------------------------------

    private String buildShipAt(int baseX) throws Exception {
        clearArea(baseX, BUILD_Y);
        String coords = placeFixture(baseX, BUILD_Y, BASE_Z, "with-pilot-seat");
        String asm = exec("stellurgytest rocket assemble 0 " + coords);
        assertTrue("with the physics mod an AFC-bearing build must become a ship, not a rocket: " + asm,
                asm.contains("\"rocketCount\":0"));
        return asm;
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }


    private void clearArea(int baseX, int baseY) throws Exception {
        int cx1 = (baseX - 4) >> 4, cz1 = (BASE_Z - 4) >> 4;
        int cx2 = (baseX + 20) >> 4, cz2 = (BASE_Z + 20) >> 4;
        assertTrue("chunk warmup failed",
                Reply.of(exec("stellurgytest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2)).ok());
        assertTrue("pre-clear failed", Reply.of(exec("stellurgytest fill 0 " + (baseX - 4) + " " + (baseY - 2) + " " + (BASE_Z - 4)
                + " " + (baseX + 20) + " " + (baseY + 12) + " " + (BASE_Z + 20)
                + " minecraft:air")).ok());
    }

    private String placeFixture(int baseX, int baseY, int baseZ, String variant) throws Exception {
        String fixture = exec("stellurgytest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " " + variant);
        assertTrue("fixture (" + variant + ") failed: " + fixture, Reply.of(fixture).ok());
        Matcher bp = BUILDER_POS.matcher(fixture);
        assertTrue("fixture (" + variant + ") missing builderPos: " + fixture, bp.find());
        return bp.group(1) + " " + bp.group(2) + " " + bp.group(3);
    }

    // --- helpers ----------------------------------------------------------------------------------

    private static int extractInt(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MIN_VALUE;
    }

    private static double extractDouble(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+(?:\\.\\d+)?)").matcher(json);
        return m.find() ? Double.parseDouble(m.group(1)) : 0.0;
    }

    private static String extractString(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":\"([^\"]+)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }
}
