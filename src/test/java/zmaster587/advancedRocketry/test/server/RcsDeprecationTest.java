package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.Reply;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import zmaster587.advancedRocketry.test.RocketList;
import zmaster587.advancedRocketry.test.FixtureSite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Contract for RCS deprecation (Option B — Free Flight Mode supersedes RCS).
 *
 * <p>What we pin:
 *
 * <ul>
 *   <li>The legacy TOGGLE_RCS server handler ({@code EntityRocket.toggleRCS})
 *       no longer flips the {@code RCS_MODE} datawatcher — the field keeps its
 *       value across a toggle, even where the old code would have flipped it.</li>
 *   <li>It instead notifies the pilot with a deprecation message pointing at
 *       Free Flight Mode (M-key), sent via {@code messagePilot} — deliberately
 *       NOT via {@code setError}, so the notice carries no launch-abort side
 *       effect ({@code setError} would post a RocketAbortEvent and set
 *       LAUNCH_COUNTER = -1).</li>
 *   <li>The {@code RCS_MODE} datawatcher key itself is intact so save-compat
 *       and solar-map deep-space navigation (which still uses it internally)
 *       continue to work.</li>
 * </ul>
 *
 * <p>Note the R keybind cannot reach {@code toggleRCS} from a seated pilot: it
 * wears the {@code NOT_PILOTING} conflict context AND its handler additionally
 * requires riding a rocket, which are mutually exclusive. The probe drives the
 * server-side method directly so the contract is pinnable regardless.
 *
 * <p>This is the "Option B" half of the migration — see the solar-map design
 * task for the eventual full removal.</p>
 */
public class RcsDeprecationTest extends AbstractSharedServerTest {

    private static final String BUILDER_POS = "builderPos";
    private static final String ROCKET_LIST_ID = "id";
    private static final String RCS_BEFORE = "rcsBefore";
    private static final String RCS_AFTER = "rcsAfter";

    private static String ok(java.util.List<String> resp) {
        return String.join("\n", resp);
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
        assertTrue("fixture failed: " + fixture, fixture.contains("\"ok\":true"));
        int[] bp = Reply.of(fixture).blockPos(BUILDER_POS);
        assertTrue("fixture missing builderPos: " + fixture, bp != null);
        int bx = bp[0];
        int by = bp[1];
        int bz = bp[2];

        String assemble = ok(client().execute(
                "artest rocket assemble 0 " + bx + " " + by + " " + bz));
        assertTrue("assemble failed: " + assemble, assemble.contains("\"ok\":true"));

        String list = ok(client().execute("artest rocket list 0"));
        java.util.List<RocketList.Entry> built = RocketList.of(list);
        assertTrue("rocket list empty after assemble: " + list, !built.isEmpty());
        return built.get(built.size() - 1).id;
    }

    @Test
    public void rcsToggleNoLongerMutatesRcsMode() throws Exception {
        int id = buildAndAssemble(FixtureSite.openAir(0, 3300, 500));

        // Drive the deprecated TOGGLE_RCS server path directly — the probe
        // invokes EntityRocket.toggleRCS() and reports RCS_MODE before/after.
        String resp = ok(client().execute("artest rocket toggle-rcs " + id));
        assertTrue("toggle-rcs probe failed: " + resp, resp.contains("\"ok\":true"));

        Reply toggled = Reply.of("artest rocket toggle-rcs", resp);
        assertTrue("response missing rcsBefore: " + resp, toggled.has(RCS_BEFORE));
        assertTrue("response missing rcsAfter: " + resp, toggled.has(RCS_AFTER));

        // The deprecation contract: toggleRCS is now a no-op on RCS_MODE.
        // A regression that restored the flip would make after != before.
        assertEquals("deprecated toggleRCS must NOT flip RCS_MODE: " + resp,
                toggled.text(RCS_BEFORE), toggled.text(RCS_AFTER));
    }

    @Test
    public void deprecationLangKeyIsRegisteredInLangFile() {
        // Static check: the lang file ships the deprecation key. A regression
        // that removes the key would surface as a raw "msg.entity.rocket.rcsDeprecated"
        // shown to players instead of the localised redirect message.
        try (java.io.InputStream is = getClass().getResourceAsStream(
                "/assets/advancedrocketry/lang/en_US.lang")) {
            assertTrue("lang resource must be on test classpath", is != null);
            java.util.Scanner sc = new java.util.Scanner(is, "UTF-8").useDelimiter("\\A");
            String body = sc.hasNext() ? sc.next() : "";
            assertTrue("en_US.lang must define msg.entity.rocket.rcsDeprecated key",
                    body.contains("msg.entity.rocket.rcsDeprecated="));
            assertTrue("deprecation message must mention Free Flight Mode and M-key",
                    body.contains("[M]") && body.toLowerCase().contains("free flight"));
        } catch (java.io.IOException ex) {
            org.junit.Assert.fail("lang lookup failed: " + ex.getMessage());
        }
    }
}
