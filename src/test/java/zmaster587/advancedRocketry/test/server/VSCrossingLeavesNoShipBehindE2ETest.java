package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.GameTicks;
import zmaster587.advancedRocketry.test.ShipIdentity;

import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A per-ship crossing MOVES a ship, so it must not ADD one to the world it left.
 *
 * <p>A crossing snapshots the source ship's subspace shipyard, cuts those blocks, pastes them elsewhere
 * and re-assembles a fresh ship there. One ship goes away exactly as one appears, so the number of ships
 * the source world holds is CONSERVED across it. Both legs assert that single invariant.</p>
 *
 * <p><b>Two counters, each blind to one of the two ways a ship can be left behind.</b>
 * {@code vs ship-count} is the LOADED physics-object set; {@code vs ship-count-all} is the queryable
 * REGISTRY. A ship object left loaded but unregistered is invisible to the second; a registry entry left
 * behind with nothing loaded to collect it is invisible to the first. Both are read on every crossing, so
 * neither leak can hide behind the counter that cannot see it. The unregistered-object half is what this
 * class is aimed at; the other half has its own arrangement in
 * {@link VSCrossingOutOfAnUnloadedSourceE2ETest}, because it needs the opposite starting state.</p>
 *
 * <p><b>Measured as a delta, never against zero.</b> The server is shared across this class's methods, so
 * each crossing is measured against counts taken immediately before it.</p>
 *
 * <p><b>The enabling condition is asserted, not assumed.</b> A ship OBJECT can only be stranded if the
 * source was loaded when its blocks were cut, so each leg asserts the source is loaded before it crosses
 * — otherwise a leg whose arrangement quietly drifted would pass while measuring nothing.</p>
 *
 * <p><b>Why arrival is checked by POSE.</b> The ship lookup behind {@code vs ship-info} returns the
 * nearest loaded ship at any distance, so on a build that strands ships it answers with the stranded one
 * and "a ship is managed here" would be true before anything arrived. Every arrival check therefore
 * requires the resolved ship to actually be AT the destination.</p>
 *
 * <p><b>Why nothing here pumps {@code vs load-ships}.</b> It is not needed — a ship is created already
 * loaded, and {@code permaload} keeps it that way for the whole class, so the loaded set fills itself.
 * It is also not safe: pumping a load while {@code permaload} holds crashes the dedicated server if any
 * registered ship happens to be unloaded, which a shared harness cannot rule out.</p>
 *
 * <p>Gated on the server's real VS presence; skips cleanly otherwise.</p>
 */
public class VSCrossingLeavesNoShipBehindE2ETest extends AbstractSharedServerTest {

    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");

    private static final int BASE_Z = 5400;
    /** Where a ship is built, and the clear-sky altitude every crossing lands at. */
    private static final int BUILD_Y = 80, SKY_Y = 150;
    /** One base per method, far enough apart that no method can resolve another's ship. */
    private static final int LEG1_X = 5400, LEG2_X = 6000, LEG3_X = 6600;
    /** Distance between a crossing's source and its destination — well beyond {@link #POSE_TOLERANCE}. */
    private static final int HOP = 160;
    /** How far a re-assembled ship's own pose may sit from the anchor it was seeded on. */
    private static final double POSE_TOLERANCE = 64.0;

    /**
     * How much WORLD a bounded wait is allowed: 200 server ticks, the ten seconds the old
     * {@code 40 x 250 ms} meant on an idle box. On the SERVER's clock, because what these wait for —
     * an assembly queued on the physics thread, a queued load being served — is driven by the server
     * tick loop, and the worlds involved are often the ones that have not started ticking yet.
     */
    private static final int WAIT_TICKS = 200;

    /** The defect in one crossing: the ship object left behind in the world the crossing departed. */
    @Test
    public void aCrossingDoesNotLeaveAShipInTheWorldItLeft() throws Exception {
        exec("artest vs permaload true");

        buildShipAt(LEG1_X);
        assertTrue("this leg measures the ship OBJECT a crossing strands, which can only exist if the "
                        + "source is loaded when it is cut - no loaded ship sits at " + LEG1_X + ","
                        + BUILD_Y + ": " + counters(), waitUntilShipIsAt(LEG1_X, BUILD_Y));

        crossConserving(LEG1_X, BUILD_Y, LEG1_X + HOP, SKY_Y, "the crossing");
    }

    /** The same leak three crossings deep — the shape a player walks (entry, jump, descent). */
    @Test
    public void threeCrossingsDoNotAccumulateShips() throws Exception {
        exec("artest vs permaload true");

        buildShipAt(LEG2_X);
        assertTrue("the source must be loaded when it is cut: " + counters(),
                waitUntilShipIsAt(LEG2_X, BUILD_Y));

        int x = LEG2_X, y = BUILD_Y;
        for (int i = 1; i <= 3; i++) {
            crossConserving(x, y, x + HOP, SKY_Y, "crossing " + i + " of 3");
            x += HOP;
            y = SKY_Y;
        }
    }

    /**
     * The nearest-ship lookup must not answer with a hull that owns no blocks.
     *
     * <p>The two legs above measure what the WORLD is left holding. This one measures what the
     * LOOKUP is willing to say, which is a different failure and outlives the first: the manager
     * collects an emptied hull on its next tick, so for the remainder of the tick in which a
     * crossing cuts one, a blockless craft is still in the loaded set and still has a position.
     * Anything asking "which ship is here" in that window — a seat lookup, a pose read, a teleport,
     * the opening lookup of the next crossing out of the same place — could be handed a craft that
     * is on its way out of the world, and this class's own history records the symptom: a seat
     * search answering {@code seatFound:false} on a ship that had just been built.
     *
     * <p><b>The window is entered deliberately, not waited for.</b> Provoking it for real means
     * winning a race against the destroy pass; the probe empties a loaded ship and asks the lookup
     * inside ONE call, which is the only place the window is guaranteed to still be open — two probe
     * commands are separated by a whole world pass, so a second command would ask after the
     * collector had run and would pass on a build with no filter at all.
     *
     * <p>Nothing is left behind: the destroy pass collects the emptied hull on its next tick, the
     * same path a cut hull takes in production.
     */
    @Test
    public void theNearestShipLookupRefusesAHullWithNoBlocks() throws Exception {
        exec("artest vs permaload true");

        buildShipAt(LEG3_X);
        assertTrue("the ship must be loaded and findable before it is emptied, or this leg tests the"
                        + " lookup against nothing: " + counters(), waitUntilShipIsAt(LEG3_X, BUILD_Y));

        String looked = exec("artest vs empty-nearest-and-look 0 "
                + LEG3_X + " " + BUILD_Y + " " + BASE_Z);
        assertTrue("the fault injection itself failed, so this leg measures nothing: " + looked,
                looked.contains("\"ok\":true"));

        String emptied = field(looked, "emptied");
        String answered = field(looked, "lookupAnswered");
        assertTrue("the injection must name the ship it emptied, or there is nothing to compare the"
                + " lookup's answer against: " + looked, !emptied.isEmpty());
        assertTrue("the nearest-ship lookup answered with the hull whose blocks had just been taken"
                        + " away. A craft with no blocks is not a craft: it is a record on its way out"
                        + " of the world, and handing it to a caller that asked which ship is HERE"
                        + " gives an answer that is about to stop being true — the seat searches, pose"
                        + " reads and teleports behind this lookup then act on a ship that is leaving."
                        + " emptied=" + emptied + " lookupAnswered=" + answered,
                !emptied.equals(answered));
    }

    /** One named field out of a probe envelope, or {@code ""} when it carries none. */
    private static String field(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : "";
    }

    @org.junit.After
    public void resetPermaload() throws Exception {
        // Shared-harness state-leak contract: never leave "permanently loaded" set for a later method.
        exec("artest vs permaload false");
    }

    // --- the invariant ------------------------------------------------------------------------------

    /** Cross the ship at the source and assert the world holds as many ships after as before, by BOTH counters. */
    private void crossConserving(int sx, int sy, int dx, int dy, String what) throws Exception {
        int loadedBefore = loadedShips();
        int registryBefore = queryableShips();

        String cross = repack(sx, sy, dx, dy);
        assertTrue(what + " itself failed, so this leg measures nothing: " + cross,
                cross.contains("\"ok\":true"));
        assertTrue("the crossed ship never arrived at " + dx + "," + dy + "; " + what + "=" + cross
                + " " + counters(), waitUntilShipIsAt(dx, dy));

        int loadedAfter = loadedShips();
        int registryAfter = queryableShips();
        assertEquals(what + " left a ship OBJECT behind in the world it departed: loaded ships went "
                        + loadedBefore + " -> " + loadedAfter + " across a crossing that moved a single "
                        + "ship, while the registry went " + registryBefore + " -> " + registryAfter + ". "
                        + "A loaded ship the registry does not hold is never destroyed and never "
                        + "unloaded, and a crossing leaves one every time.",
                loadedBefore, loadedAfter);
        assertEquals(what + " left a registry ENTRY behind in the world it departed: registered ships "
                        + "went " + registryBefore + " -> " + registryAfter + " (loaded " + loadedBefore
                        + " -> " + loadedAfter + ").",
                registryBefore, registryAfter);
    }

    // --- arrangement --------------------------------------------------------------------------------

    /** Build one tier-2 ship at {@code (baseX, BUILD_Y, BASE_Z)} and wait until VS has really created it. */
    private void buildShipAt(int baseX) throws Exception {
        clearArea(baseX, BUILD_Y);
        for (int i = 1; i <= 3; i++) {
            clearArea(baseX + i * HOP, SKY_Y);
        }
        int registryBefore = queryableShips();
        String coords = placeFixture(baseX, BUILD_Y, BASE_Z, "with-pilot-seat");
        String asm = exec("artest rocket assemble 0 " + coords);
        assertTrue("with VS an AFC-bearing build must route to a ship (no rocket): " + asm,
                asm.contains("\"rocketCount\":0"));
        assertTrue("the ship never entered VS's registry at " + baseX + "," + BUILD_Y + "," + BASE_Z
                        + ": " + counters(), registryExceeds(registryBefore));
        durableShipId = ShipIdentity.nameFromAssembly(asm);
    }

    /**
     * This craft's DURABLE name. Not its physics id: this class crosses the same ship three times in
     * a row and every crossing mints a new physics id, so the name is the only handle that spans the
     * run — which is also why the cut is aimed with a freshly translated id each time.
     */
    private String durableShipId;

    private String repack(int sx, int sy, int dx, int dy) throws Exception {
        // The crossing CUTS a ship, so it is told which one. The positional form resolves the yard as
        // "whatever craft is nearest", and this class exists to prove no ship is LEFT BEHIND — so the
        // leftovers it hunts for are precisely what such a lookup would reach for.
        String shipId = ShipIdentity.physicsIdOf(this::exec, 0, durableShipId);
        return exec("artest vs ship-repack 0 id " + shipId + " " + sx + " " + sy + " " + BASE_Z
                + " " + dx + " " + dy + " " + BASE_Z);
    }

    // --- observation --------------------------------------------------------------------------------

    private int loadedShips() throws Exception {
        return extractInt(exec("artest vs ship-count 0"), "count");
    }

    private int queryableShips() throws Exception {
        return extractInt(exec("artest vs ship-count-all 0"), "count");
    }

    /** Both counters together: the pair is the diagnosis, either one alone is just a number. */
    private String counters() throws Exception {
        return "[loaded=" + loadedShips() + " registry=" + queryableShips() + "]";
    }

    /**
     * Is there a loaded ship whose own pose is at {@code (x,y,BASE_Z)}?
     *
     * <p>Answered by asking EVERY loaded ship where it is. The previous form asked the world which
     * ship was nearest the spot and then compared that one ship's pose — an unbounded lookup with a
     * filter on its single answer, so a hull sitting exactly here was invisible whenever the lookup
     * preferred another. Same claim, no lookup that can pick the wrong craft to test.</p>
     */
    private boolean shipIsAt(int x, int y) throws Exception {
        return ShipIdentity.aLoadedShipIsAt(this::exec, 0, x, y, BASE_Z, POSE_TOLERANCE);
    }

    /**
     * Has the registry grown past {@code floor}? A READ, not a wait.
     *
     * <p>The entry IS deferred — {@code queueShipSpawn} only adds to a spawn queue the world drains
     * on its next tick — but nothing here can observe the pre-drain state: every probe command is
     * drained on the server thread behind the task queue's own monitor, so two consecutive commands
     * are separated by a complete pass. The poll this replaces could only ever spend its budget in
     * runs where the answer was going to be no.</p>
     */
    private boolean registryExceeds(int floor) throws Exception {
        return queryableShips() > floor;
    }

    /** Poll until a loaded ship sits at {@code (x,y,BASE_Z)}. Bounded; deliberately pumps no load. */
    private boolean waitUntilShipIsAt(int x, int y) throws Exception {
        return GameTicks.until(client(), GameTicks.server(), WAIT_TICKS, () -> shipIsAt(x, y));
    }

    // --- helpers ------------------------------------------------------------------------------------

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }

    private void clearArea(int baseX, int baseY) throws Exception {
        int cx1 = (baseX - 4) >> 4, cz1 = (BASE_Z - 4) >> 4;
        int cx2 = (baseX + 20) >> 4, cz2 = (BASE_Z + 20) >> 4;
        assertTrue("chunk warmup failed",
                exec("artest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2).contains("\"ok\":true"));
        assertTrue("pre-clear failed", exec("artest fill 0 " + (baseX - 4) + " " + (baseY - 2) + " " + (BASE_Z - 4)
                + " " + (baseX + 20) + " " + (baseY + 12) + " " + (BASE_Z + 20)
                + " minecraft:air").contains("\"ok\":true"));
    }

    private String placeFixture(int baseX, int baseY, int baseZ, String variant) throws Exception {
        String fixture = exec("artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " " + variant);
        assertTrue("fixture (" + variant + ") failed: " + fixture, fixture.contains("\"ok\":true"));
        Matcher bp = BUILDER_POS.matcher(fixture);
        assertTrue("fixture (" + variant + ") missing builderPos: " + fixture, bp.find());
        return bp.group(1) + " " + bp.group(2) + " " + bp.group(3);
    }

    private static int extractInt(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MIN_VALUE;
    }

    private static double extractDouble(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?)").matcher(json);
        return m.find() ? Double.parseDouble(m.group(1)) : 0.0;
    }
}
