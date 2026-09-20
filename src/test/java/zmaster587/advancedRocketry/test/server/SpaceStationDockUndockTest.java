package zmaster587.advancedRocketry.test.server;

// migrated to AbstractSharedServerTest
import zmaster587.advancedRocketry.test.Reply;
import zmaster587.advancedRocketry.test.StationInfo;
import zmaster587.advancedRocketry.test.StationPads;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * dock / undock contract on
 * {@code SpaceStationObject}.
 *
 * The smoke / depth tests in {@link SpaceStationLifecycleSmokeTest} and
 * {@link SpaceStationDepthTest} cover id allocation, fuel accounting,
 * and registry persistence. They do NOT exercise the LANDING-PAD
 * lifecycle: addLandingPad &rarr; setLandingPadAutoLandStatus &rarr;
 * getNextLandingPad &rarr; setPadStatus. That state is what every rocket
 * landing on a station and every rocket lifting off a pad mutates;
 * a subtle regression in this state machine would silently break
 * inter-dim travel for modpack players.
 *
 * What's pinned here:
 *
 * <ul>
 *   <li>{@code add-pad} grows the landing-pad list by 1 and the new
 *       pad starts <em>occupied=false, allowAutoLand=false</em>
 *       (default-state contract — easy to flip in a refactor).</li>
 *   <li>{@code dock} (== getNextLandingPad(true)) returns <em>no pad
 *       available</em> until a pad has been explicitly opted in to
 *       auto-landing. This is a non-obvious gate — a refactor that
 *       defaults pads to auto-land would silently land rockets on pads
 *       the station owner hadn't authorized.</li>
 *   <li>After enabling auto-land, dock returns the pad and marks it
 *       occupied; a second dock for the same pad fails with "no free pad".</li>
 *   <li>{@code undock(<x>,<z>)} frees the pad so the next dock returns
 *       it again.</li>
 *   <li>{@code remove-pad} shrinks the list and the removed pad's pos
 *       is no longer reported by {@code pads}.</li>
 *   <li>Two pads at the same (x,z) — addLandingPad must de-dupe (the
 *       prod code uses {@code !spawnLocations.contains(pos)} via
 *       StationLandingLocation.equals &rarr; BlockPos equality).</li>
 *   <li>{@code dock(commit=false)} reads next-free without consuming.</li>
 * </ul>
 */
public class SpaceStationDockUndockTest extends AbstractSharedServerTest {

    private static final String ID_PATTERN = "id";

    private int createStation() throws Exception {
        String resp = String.join("\n", client().execute("artest station create 0"));
        assertTrue("station create failed: " + resp, Reply.of(resp).ok());
        Reply mReply = Reply.of(resp);
        assertTrue("could not parse station id: " + resp, mReply.has(ID_PATTERN));
        return Integer.parseInt(mReply.text(ID_PATTERN));
    }

    private static String ok(java.util.List<String> resp) {
        return String.join("\n", resp);
    }

    @Test
    public void addPadGrowsListWithExpectedDefaults() throws Exception {
        int id = createStation();
        String add = ok(client().execute("artest station add-pad " + id + " 10 20 alpha"));
        assertTrue("add-pad must succeed: " + add, Reply.of(add).ok());
        // `add-pad` writes its own `padCount`, so it is read as ITS reply and not as a station
        // reading — and as a NUMBER: the substring form was a prefix, satisfied by 10 pads.
        assertEquals("padCount should be 1 after first add: " + add,
                1, Reply.of("artest station add-pad", add).integer("padCount"));

        // Read as ONE pad addressed by its position. The `x`-and-`z` substring pair this replaces
        // is satisfied by a station holding (10, 99) and (77, 20), i.e. by no such pad at all.
        StationPads.Pad pad = pads(id).at(10, 20);
        // Default state contract — pad starts unoccupied AND not opted into
        // auto-landing. A refactor that flips either default would silently
        // change the dock-allocation semantics.
        assertFalse("new pad must start occupied=false: " + pad.raw(), pad.occupied);
        assertFalse("new pad must start allowAutoLand=false: " + pad.raw(), pad.allowAutoLand);
        assertEquals("new pad must carry the supplied name: " + pad.raw(), "alpha", pad.name());
    }

    @Test
    public void dockRejectsPadWithoutAutoLandOptIn() throws Exception {
        // Critical: getNextLandingPad gates on BOTH not-occupied AND
        // allowedForAutoLanding. A pad just added via addLandingPad starts
        // with allowAutoLand=false — dock must NOT silently consume it.
        int id = createStation();
        ok(client().execute("artest station add-pad " + id + " 10 20 alpha"));
        String dock = ok(client().execute("artest station dock " + id));
        // `has("ok") && !ok()` and not a bare `!ok()`: the claim is that the verb REPORTED a
        // refusal, and a reply that says nothing at all also fails `ok()`.
        Reply dockRefusal = Reply.of("artest station dock", dock);
        assertTrue("dock must refuse a pad that hasn't opted into auto-land: " + dock,
                dockRefusal.has("ok") && !dockRefusal.ok()
                        && "no free landing pad".equals(dockRefusal.text("reason")));
    }

    @Test
    public void dockClaimsAutoLandPadAndMarksOccupied() throws Exception {
        int id = createStation();
        ok(client().execute("artest station add-pad " + id + " 30 40 beta"));
        ok(client().execute("artest station set-autoland " + id + " 30 40 true"));

        String dock = ok(client().execute("artest station dock " + id));
        assertTrue("dock must succeed once pad is auto-land enabled: " + dock,
                Reply.of(dock).ok());
        // Read as NUMBERS: `contains("\"x\":30")` is satisfied by a pad at x=300, and the same
        // prefix trap has already been measured on this producer's pad list.
        Reply docked = Reply.of("artest station dock", dock);
        assertTrue("dock response must echo the chosen pad coords: " + dock,
                docked.integer("x") == 30 && docked.integer("z") == 40);

        // After dock with commit=true, THE pad's occupied flag must flip — asked of the pad at
        // (30, 40) rather than of the list, which would answer for any occupied pad.
        assertTrue("dock must mark the pad occupied=true", pads(id).at(30, 40).occupied);

        // A second dock against the only pad MUST return no-free-pad.
        String dock2 = ok(client().execute("artest station dock " + id));
        Reply secondDock = Reply.of("artest station dock", dock2);
        assertTrue("second dock with no other free pad must fail: " + dock2,
                secondDock.has("ok") && !secondDock.ok());
    }

    @Test
    public void undockReturnsPadToFreePool() throws Exception {
        int id = createStation();
        ok(client().execute("artest station add-pad " + id + " 50 60 gamma"));
        ok(client().execute("artest station set-autoland " + id + " 50 60 true"));
        ok(client().execute("artest station dock " + id));  // consume

        // Pre-undock: the pad reports occupied=true.
        StationPads.Pad pre = pads(id).at(50, 60);
        assertTrue("pre-undock pad must read occupied=true: " + pre.raw(), pre.occupied);

        String undock = ok(client().execute("artest station undock " + id + " 50 60"));
        assertTrue("undock must succeed: " + undock, Reply.of(undock).ok());

        // Post-undock: pad is free again.
        StationPads.Pad post = pads(id).at(50, 60);
        assertFalse("post-undock pad must read occupied=false: " + post.raw(), post.occupied);

        // And the next dock call must successfully reclaim it.
        String reclaim = ok(client().execute("artest station dock " + id));
        assertTrue("post-undock dock must reclaim the just-freed pad: " + reclaim,
                Reply.of(reclaim).ok() && (Reply.of(reclaim).integer("x") == 50));
    }

    @Test
    public void dockWithCommitFalseDoesNotConsumePad() throws Exception {
        // The probe forwards commit=false to getNextLandingPad — production
        // path used for "preview which pad WOULD I land on?" checks. The
        // pad must NOT flip to occupied.
        int id = createStation();
        ok(client().execute("artest station add-pad " + id + " 70 80 delta"));
        ok(client().execute("artest station set-autoland " + id + " 70 80 true"));

        String preview = ok(client().execute("artest station dock " + id + " false"));
        assertTrue("preview dock must report ok and the pad coords: " + preview,
                Reply.of(preview).ok() && (Reply.of(preview).integer("x") == 70));

        StationPads.Pad previewed = pads(id).at(70, 80);
        assertFalse("preview dock must NOT mark the pad occupied: " + previewed.raw(),
                previewed.occupied);
    }

    @Test
    public void addPadIsIdempotentForSamePosition() throws Exception {
        // Production gate: spawnLocations.contains(pos) check uses
        // StationLandingLocation.equals which compares by position. Two
        // adds at the same (x,z) MUST collapse to one entry.
        int id = createStation();
        ok(client().execute("artest station add-pad " + id + " 90 90 first"));
        String second = ok(client().execute(
                "artest station add-pad " + id + " 90 90 second"));
        // padCount stays 1 even after the duplicate add.
        assertEquals("duplicate add at same (x,z) must NOT grow padCount: " + second,
                1, Reply.of("artest station add-pad", second).integer("padCount"));
    }

    @Test
    public void removePadShrinksList() throws Exception {
        int id = createStation();
        ok(client().execute("artest station add-pad " + id + " 100 100 toremove"));
        ok(client().execute("artest station add-pad " + id + " 110 110 keep"));

        String remove = ok(client().execute("artest station remove-pad " + id + " 100 100"));
        assertTrue("remove-pad must succeed and report removed=1: " + remove,
                Reply.of(remove).ok() && (Reply.of(remove).integer("removed") == 1));
        assertEquals("padCount must drop to 1 after remove: " + remove,
                1, Reply.of("artest station remove-pad", remove).integer("padCount"));

        // The remaining pad's coords must still be reachable. Asked as PADS: the two substring
        // pairs this replaces could each be satisfied by the other pad's coordinates.
        StationPads pads = pads(id);
        assertTrue("remaining pad must still be listed: " + pads.raw(), pads.has(110, 110));
        assertFalse("removed pad must be gone from list: " + pads.raw(), pads.has(100, 100));
    }

    @Test
    public void multipleStationsTrackPadsIndependently() throws Exception {
        // Per-station pad state must not bleed across stations. A regression
        // that consolidated landing pads into a global registry would
        // silently route rockets to the wrong station's pads.
        int a = createStation();
        int b = createStation();
        assertNotEquals("station ids must be unique", a, b);

        ok(client().execute("artest station add-pad " + a + " 200 200 a1"));
        ok(client().execute("artest station add-pad " + b + " 300 300 b1"));

        StationPads padsA = pads(a);
        StationPads padsB = pads(b);
        assertTrue("station A must have its pad: " + padsA.raw(), padsA.has(200, 200));
        assertFalse("station A must NOT have station B's pad: " + padsA.raw(),
                padsA.has(300, 300));
        assertTrue("station B must have its pad: " + padsB.raw(), padsB.has(300, 300));
        assertFalse("station B must NOT have station A's pad: " + padsB.raw(),
                padsB.has(200, 200));
    }

    @Test
    public void infoExposesPadCountAndFreePadFlag() throws Exception {
        // Pin the info probe's pad-related fields; downstream tooling
        // (rocket launch UI, station-finder satellite) reads these.
        int id = createStation();
        StationInfo empty = station(id);
        assertEquals("empty station: padCount=0 — read as a number, because the substring form was"
                + " also satisfied by a station holding 0 pads out of 10: " + empty.raw(),
                0, empty.padCount());
        assertFalse("empty station: hasFreePad=false (no pads at all): " + empty.raw(),
                empty.hasFreePad());

        ok(client().execute("artest station add-pad " + id + " 400 400 p1"));
        StationInfo oneOccupied = station(id);
        assertEquals("after add: padCount=1: " + oneOccupied.raw(), 1, oneOccupied.padCount());
        // hasFreeLandingPad checks for ANY pad with occupied=false, NOT
        // gating on auto-land. So even a non-auto-land pad reports
        // hasFreePad=true. Pin this contract — it's a separate axis from
        // dock-allocation.
        assertTrue("pad just added (not occupied) -> hasFreePad=true: "
                + oneOccupied.raw(), oneOccupied.hasFreePad());
    }

    /** What the server says about one station. */
    private StationInfo station(int stationId) throws Exception {
        return StationInfo.byId(cmd -> ok(client().execute(cmd)), stationId);
    }

    /** Every landing pad the station holds, addressable by position. */
    private StationPads pads(int stationId) throws Exception {
        return StationPads.byId(cmd -> ok(client().execute(cmd)), stationId);
    }
}
