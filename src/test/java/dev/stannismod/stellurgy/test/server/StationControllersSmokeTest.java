package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.MachineInfo;
import dev.stannismod.stellurgy.test.Reply;
import org.junit.Test;

import dev.stannismod.stellurgy.test.FixtureSite;

import static org.junit.Assert.assertTrue;

/**
 * Station-controller tile smoke.
 *
 * <p>Three station-internal tiles managed station orbital mechanics in
 * production:</p>
 *
 * <ul>
 *   <li>{@link dev.stannismod.stellurgy.tile.station.TileStationAltitudeController}
 *       — drives the orbiting station's altitude (de-orbit / re-orbit).</li>
 *   <li>{@link dev.stannismod.stellurgy.tile.station.TileStationGravityController}
 *       — adjusts gravity on the station.</li>
 *   <li>{@link dev.stannismod.stellurgy.tile.station.TileStationOrientationController}
 *       — sets the station's rotational angle.</li>
 * </ul>
 *
 * <p>Pre-this-test, <b>zero tests at any layer</b> referenced these
 * three tiles. The full functional contract requires a station
 * context (a {@code SpaceObject} occupying the block's pos), which
 * is heavy to fixture. Smoke-level pin: each block places to the
 * right tile class, ticks without crashing, and survives force-tick
 * bursts.</p>
 *
 * <p>A regression that breaks tile-class registration (renames the
 * block or its tile, drops the block from the item-registry) or that
 * throws on idle ticking (NPE on null station context) would fire
 * these smoke tests. The deeper "altitude actually changes station
 * altitude" / "gravity controller mutates DimensionProperties.gravity"
 * contracts need station-context fixtures — those belong in a
 * follow-up TASK if a regression motivates them.</p>
 */
public class StationControllersSmokeTest extends AbstractSharedServerTest {

    private static final int CY = FixtureSite.OPEN_AIR_Y;
    private static final int CZ = 9000;
    private static final int CX_ORIENT  = 9000;
    private static final int CX_GRAV    = 9100;
    private static final int CX_ALT     = 9200;

    @Test
    public void orientationControllerPlacesAndTicksWithoutCrash() throws Exception {
        assertPlacesTicksAndReportsCorrectTileClass(
                CX_ORIENT, "stellurgy:orientationController",
                "TileStationOrientationController");
    }

    @Test
    public void gravityControllerPlacesAndTicksWithoutCrash() throws Exception {
        assertPlacesTicksAndReportsCorrectTileClass(
                CX_GRAV, "stellurgy:gravityController",
                "TileStationGravityController");
    }

    @Test
    public void altitudeControllerPlacesAndTicksWithoutCrash() throws Exception {
        assertPlacesTicksAndReportsCorrectTileClass(
                CX_ALT, "stellurgy:altitudeController",
                "TileStationAltitudeController");
    }

    private void assertPlacesTicksAndReportsCorrectTileClass(
            int cx, String registryName, String tileSimpleName) throws Exception {
        String place = exec("stellurgytest place 0 " + cx + " " + CY + " " + CZ
                + " " + registryName);
        assertTrue("block " + registryName + " must place: " + place,
                Reply.of(place).bool("placed"));

        String info = exec("stellurgytest machine info 0 " + cx + " " + CY + " " + CZ);
        assertTrue("block " + registryName + " must produce tile "
                        + tileSimpleName + ": " + info,
                MachineInfo.of(info).isTile(tileSimpleName));

        // 40 force-ticks — enough for any % N == 0 gate to fire at least
        // once. Pure smoke: must not throw, tile must remain queryable.
        String tick = exec("stellurgytest tile force-tick 0 " + cx + " " + CY + " " + CZ
                + " 40");
        assertTrue("force-tick on " + registryName + " must succeed: " + tick,
                Reply.of(tick).ok());

        // Re-query — proves the tile survived the tick burst (no
        // unregister, no replace).
        String postInfo = exec("stellurgytest machine info 0 " + cx + " " + CY + " " + CZ);
        assertTrue("tile must remain " + tileSimpleName + " after ticking: "
                        + postInfo,
                MachineInfo.of(postInfo).isTile(tileSimpleName));
    }
}
