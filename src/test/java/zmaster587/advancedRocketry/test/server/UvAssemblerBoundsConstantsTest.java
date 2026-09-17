package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.Reply;
import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;
import static zmaster587.advancedRocketry.test.server.WorldCommandFixtures.exec;

/**
 * bounds-constants delta between
 * {@link zmaster587.advancedRocketry.tile.TileRocketAssemblingMachine} and
 * {@link zmaster587.advancedRocketry.tile.TileUnmannedVehicleAssembler}.
 *
 * <p>The two assemblers cap rocket-pad-bounds-scan differently:</p>
 *
 * <ul>
 *   <li>{@code TileRocketAssemblingMachine}: {@code MAX_SIZE=16, MAX_SIZE_Y=128}.
 *       Lets the player build tall ascending rockets that carry crew to orbit.</li>
 *   <li>{@code TileUnmannedVehicleAssembler}: {@code MAX_SIZE=17, MAX_SIZE_Y=17}.
 *       Lets the player build compact station-deployed drones; height is
 *       capped because they're meant to fit station bays, not crew Saturn V.</li>
 * </ul>
 *
 * <p><b>Why this is a contract, not an impl-detail</b>: a modpack expects
 * UV builders to be small (the GUI hint, the in-world structure-tower
 * hint, the documentation). A regression that swaps the two caps —
 * or unifies them — silently lets players build a 128-tall UV (which would
 * land catastrophically) or a 17-capped regular rocket (which can no longer
 * reach orbit-tier altitudes for some configs). Both are player-visible
 * regressions that no production assertion currently guards against.</p>
 *
 * <p>This test reads the private static final constants via the new
 * {@code /artest assembler max-y} reflective probe — a single round-trip
 * for both classes. The probe is read-only so it doesn't mutate global
 * state (no @After cleanup needed).</p>
 */
public class UvAssemblerBoundsConstantsTest extends AbstractSharedServerTest {

    private static final String ROCKET_MAX_Y = "rocketAssemblerMaxY";
    private static final String UV_MAX_Y = "uvAssemblerMaxY";

    @Test
    public void rocketAssemblerAllowsTallerStructureThanUvAssembler() throws Exception {
        String resp = exec("artest assembler max-y");
        Reply rmReply = Reply.of(resp);
        Reply umReply = Reply.of(resp);
        assertTrue("probe must surface rocketAssemblerMaxY: " + resp, rmReply.has(ROCKET_MAX_Y));
        assertTrue("probe must surface uvAssemblerMaxY: " + resp, umReply.has(UV_MAX_Y));
        int rocketMaxY = Integer.parseInt(rmReply.text(ROCKET_MAX_Y));
        int uvMaxY = Integer.parseInt(umReply.text(UV_MAX_Y));
        assertTrue("rocket assembler MAX_SIZE_Y must exceed UV's so the two "
                        + "assemblers serve their distinct rocket-class roles "
                        + "(rocket=" + rocketMaxY + ", uv=" + uvMaxY + ")",
                rocketMaxY > uvMaxY);
        // Both must be positive — a 0 cap would mean the assembler is unusable.
        assertTrue("rocket MAX_SIZE_Y must be a usable positive cap (got "
                + rocketMaxY + ")", rocketMaxY > 0);
        assertTrue("uv MAX_SIZE_Y must be a usable positive cap (got "
                + uvMaxY + ")", uvMaxY > 0);
    }

    @Test
    public void uvAssemblerHeightCapMatchesItsWidthCap() throws Exception {
        // UV is a compact cube — its height cap equals its width cap by
        // design (both 17). A regression that decouples them (e.g. height
        // unbumped from a default 17 while width changed) would let
        // partial-cube UV rockets be assembled, which would mess with
        // station-bay docking. Pin the cube invariant explicitly.
        String resp = exec("artest assembler max-y");
        Reply uyReply = Reply.of(resp);
        Reply uxReply = Reply.of(resp);
        assertTrue("probe must surface uvAssemblerMaxY: " + resp, uyReply.has(UV_MAX_Y));
        assertTrue("probe must surface uvAssemblerMaxXZ: " + resp, uxReply.has("uvAssemblerMaxXZ"));
        int uvMaxY = Integer.parseInt(uyReply.text(UV_MAX_Y));
        int uvMaxXZ = uxReply.integer("uvAssemblerMaxXZ");
        assertTrue("UV's height cap must match its width cap "
                        + "(MAX_SIZE_Y=" + uvMaxY + ", MAX_SIZE=" + uvMaxXZ + ")",
                uvMaxY == uvMaxXZ);
    }
}
