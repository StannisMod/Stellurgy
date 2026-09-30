package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.ArrangementFailure;
import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.RocketFixture;
import dev.stannismod.stellurgy.test.ShipIdentity;
import dev.stannismod.stellurgy.test.ShipInfo;
import dev.stannismod.stellurgy.test.ShipReadiness;
import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import org.junit.Test;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertEquals;

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
 * once, three times, or never. So these drive the transitions a craft actually goes through and count
 * what was announced for THIS craft — one {@code ship_lifecycle} record per announcement, keyed by
 * its physics identity, over a window marked before the first transition.</p>
 *
 * <p><b>Why the causes must be distinguishable.</b> A consumer minting a durable record for a new
 * vessel wants only the first transition; a consumer rebuilding derived state wants all of them. If
 * an assembly and a crossing announce the same thing, the first consumer mints a second record for a
 * craft that already had one, and nothing downstream can tell the two vessels apart again.</p>
 *
 * <p><b>Where a count is read.</b> Each window is read once, after the LAST transition it is about
 * has been announced. A duplicate announcement is published in the same manager tick as the one it
 * duplicates, so by the time the awaited record is in the log its twin is too.</p>
 *
 * <p>red-witnessed: with {@code WorldServerShipManager.spawnNewShips} ({@code :405}) noting every
 * spawn twice and as {@code ASSEMBLED}, the cycle fails with "must be announced as ASSEMBLED exactly
 * once" and the crossing with "no `ship_lifecycle` carrying … cause = PASTED was recorded within 200
 * ticks", 2026-09-29. With the UNLOADED note ({@code :592}) removed, the cycle fails "dropping the
 * ship object must be announced — no … cause = UNLOADED … within 200 ticks"; with the LOADED note
 * ({@code :529}) made twice, it fails "coming back must be announced exactly once", 2026-09-29.
 * One break per remaining verdict, 2026-09-30: the spawn note at {@code :405} removed fails "a craft
 * that has just been built must be announced"; the LOADED note at {@code :529} removed fails "coming
 * back must be announced"; the UNLOADED note at {@code :592} made twice fails "dropping … exactly
 * once"; a PASTED note added beside {@code :529}'s LOADED fails "nothing here was cut and pasted"; a
 * DESTROYED note added beside {@code :592}'s UNLOADED fails "an unloaded craft still EXISTS"; a
 * PASTED spawn at {@code :405} noted twice fails "announced as PASTED exactly once"; a PASTED spawn at
 * {@code :405} also noted as ASSEMBLED fails "a crossing is not a new build".</p>
 */
public class ShipNamingEdgeIsAnnouncedOncePerTransitionE2ETest extends AbstractHeadlessServerTest {

    private static final int BASE_Z = 9400;
    /** One craft per method, each on its own X lane, so neither can see the other's leftovers. */
    private static final int LANE_CYCLE_X = 9400, LANE_CROSS_X = 9800;
    /** The hop the proven crossing tests use: same Z, one step along X, into the same open-air band. */
    private static final int HOP = 160;

    /**
     * A LINK budget in server ticks: each awaited announcement is published at the end of the manager
     * tick that made the transition, so its expiry means the transition never happened.
     */
    private static final int WAIT_TICKS = 200;

    private final Events events =
            new Events(this::exec, ticks -> GameTicks.advance(client(), GameTicks.server(), ticks));

    /** A craft is built, dropped, and fetched back: ASSEMBLED, then UNLOADED, then LOADED — each once. */
    @Test
    public void buildingDroppingAndFetchingBackAreThreeDistinctAnnouncements() throws Exception {

        long mark = events.mark();
        String shipId = buildShipAt(LANE_CYCLE_X);
        awaitAnnounced(mark, shipId, "ASSEMBLED", "a craft that has just been built must be announced");

        // Nothing holds a ship loaded on a headless server once permanent loading is off: there is no
        // player for the world's own pass to measure a distance to, so it drops the craft by itself.
        ShipReadiness.letShipsUnload(this::exec, "the un-naming half is the subject: the craft has to be"
                + " dropped by the world's own pass");
        awaitAnnounced(mark, shipId, "UNLOADED", "dropping the ship object must be announced");

        ShipReadiness.holdShipsLoaded(this::exec, "the LOADED edge is the last subject: the craft has to"
                + " be fetched back");
        exec("stellurgytest vs load-ships 0");
        String window = awaitAnnounced(mark, shipId, "LOADED", "coming back must be announced");

        assertEquals("a craft that has just been built must be announced as ASSEMBLED exactly once: "
                + window, 1, announced(window, shipId, "ASSEMBLED"));
        assertEquals("dropping the ship object must be announced exactly once: " + window,
                1, announced(window, shipId, "UNLOADED"));
        assertEquals("coming back must be announced exactly once: " + window,
                1, announced(window, shipId, "LOADED"));
        assertEquals("nothing here was cut and pasted - a consumer that mints a durable record only for"
                + " a genuinely new vessel would mint nothing at all if a build were reported as a"
                + " paste: " + window, 0, announced(window, shipId, "PASTED"));
        assertEquals("an unloaded craft still EXISTS - it is registered and on disk and will be back."
                + " Reporting it as destroyed would tell every consumer holding something durable for"
                + " this vessel to throw it away: " + window, 0, announced(window, shipId, "DESTROYED"));
    }

    /** A craft that crosses is announced as PASTED, never as a second birth. */
    @Test
    public void aCrossingIsAnnouncedAsAPasteAndNotAsANewBuild() throws Exception {

        long mark = events.mark();
        String shipId = buildShipAt(LANE_CROSS_X);
        ArrangementFailure.arranged(() -> awaitAnnounced(mark, shipId, "ASSEMBLED",
                "the craft must exist before it can cross"));

        // The production crossing: the blocks are cut out and pasted elsewhere, and the craft is
        // re-registered around them. Told WHICH craft to cut; the source pose is only where the
        // riders are gathered.
        ShipInfo source = ShipInfo.byId(this::exec, 0, shipId);
        String repack = exec("stellurgytest vs ship-repack 0 id " + shipId + " "
                + (int) source.x + " " + (int) source.y + " " + (int) source.z + " "
                + (LANE_CROSS_X + HOP) + " " + FixtureSite.OPEN_AIR_Y + " " + BASE_Z);
        requireArranged("the crossing must actually run, or nothing below is a paste: " + repack,
                Reply.of(repack).ok());
        // The identity the craft came out under, as the crossing itself reports it.
        String crossedId = Reply.of("stellurgytest vs ship-repack", repack).text("shipUuid");

        String window = awaitAnnounced(mark, crossedId, "PASTED",
                "a craft re-registered around pasted blocks must be announced as PASTED");
        assertEquals("a craft re-registered around pasted blocks must be announced as PASTED exactly"
                + " once: " + window, 1, announced(window, crossedId, "PASTED"));
        assertEquals("a crossing is not a new build. This is the distinction the whole cause enum exists"
                + " for: reported as ASSEMBLED, a vessel would acquire a second birth record every time"
                + " it crossed. The crossing keeps the identity when it can (" + shipId + " -> "
                + crossedId + "), so the build's own ASSEMBLED is the only one this id may carry: "
                + window,
                shipId.equals(crossedId) ? 1 : 0, announced(window, crossedId, "ASSEMBLED"));
    }

    // --- observation ------------------------------------------------------------------------------

    /**
     * Wait for {@code cause} to be announced for {@code shipId} since {@code mark}, and answer the
     * WHOLE window of announcements at that moment — the reply every count above is read from.
     */
    private String awaitAnnounced(long mark, String shipId, String cause, String what) throws Exception {
        events.awaitRecordWithFields(mark, "ship_lifecycle", what, WAIT_TICKS,
                "ship", shipId, "cause", cause);
        return events.since(mark, "ship_lifecycle");
    }

    /** How many announcements of {@code cause} the window holds for {@code shipId}. */
    private static int announced(String window, String shipId, String cause) {
        return Events.recordsWhereAll(window, "ship", shipId, "cause", cause).size();
    }

    // --- arrangement ------------------------------------------------------------------------------

    /** Build and assemble a craft on its own lane, and answer its physics identity once it is usable. */
    private String buildShipAt(int baseX) throws Exception {
        String asm = RocketFixture.assembleAt(FixtureSite.openAir(0, baseX, BASE_Z), this::exec,
                "with-pilot-seat", 4, 12, "the craft whose naming edges are counted stands in this volume");
        requireArranged("with the physics mod an AFC-bearing build must become a ship, not a rocket: " + asm,
                Reply.of(asm).integer("rocketCount") == 0);
        return ArrangementFailure.arranged(() -> ShipIdentity.awaitPhysicsIdOf(this::exec, events, 0,
                ShipIdentity.nameFromAssembly(asm), WAIT_TICKS));
    }

    private String exec(String cmd) throws Exception {
        return String.join("\n", client().execute(cmd));
    }
}
