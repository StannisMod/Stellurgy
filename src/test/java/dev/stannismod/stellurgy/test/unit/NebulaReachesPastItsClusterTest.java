package dev.stannismod.stellurgy.test.unit;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import dev.stannismod.stellurgy.space.GalacticCoord;
import dev.stannismod.stellurgy.test.ArrangementFailure;
import dev.stannismod.stellurgy.universe.ClusteredGalaxyGenerator;
import dev.stannismod.stellurgy.universe.Galaxy;
import dev.stannismod.stellurgy.universe.GalaxyGenConfig;
import dev.stannismod.stellurgy.universe.IUniverseLaws;
import dev.stannismod.stellurgy.universe.Nebula;
import dev.stannismod.stellurgy.universe.ReportOnce;
import dev.stannismod.stellurgy.universe.StarCluster;

import static org.junit.Assert.assertTrue;

/**
 * A nebula's matter is where its cloud is, not where its cluster is.
 *
 * <p>A unit test of {@code NebulaField}'s density readings, on the clouds a stock
 * {@link ClusteredGalaxyGenerator} — an instance this test builds and owns — seats in its home galaxy.
 * Each probe point stands outside its cloud's CLUSTER, in a coarse super-cell no cluster contains, and
 * inside the CLOUD, where the cloud's own {@link Nebula#densityAt} says there is matter.</p>
 */
public class NebulaReachesPastItsClusterTest {

    /** The test's own world seed; any seed seats clouds, and this one is fixed so a red reproduces. */
    private static final long SEED = 0x5EED_0698L;

    /**
     * How many cluster cells either side of the origin the search for clouds covers — the test's own
     * reach, chosen to hold several clouds; the arrangement checks below say whether it did.
     */
    private static final long SEARCH_CLUSTER_CELLS = 4L;

    /**
     * The density reading at a point outside every cluster's ball counts the cloud that reaches it.
     *
     * <p>Fails if {@code NebulaField#densityAtLightYears} stops deciding that every cloud reaching a
     * point contributes to it (it answered only for the cluster whose ball holds the point's super-cell,
     * so a cloud read as ending at its cluster's edge — where a Gaussian 1.5-3 times wider is still a
     * sixth to two thirds of its peak).</p>
     * <p>red-witnessed: with {@code NebulaField#densityAtLightYears} at {@code return densityAmong(cloudsReaching(seed, galaxy, supX, supY, supZ, supX, supY, supZ),} reading only the cloud of the point's own cluster ({@code nebulaAt}), and {@code NebulaField#columnDensityBetween} at {@code double density = densityAmong(clouds, ax + dx * t, ay + dy * t, az + dz * t);} sampling through it, fails: "at (-1347.4151752811385, -685.6316157404024, 184.82243554741282) ly in Nebula[DARK r=67ly d=0.8570544725797633 around StarCluster[Molecular Cloud k=1 r=8 super-cells @ -461,-230,62]] the cloud alone h …" (2026-10-07).</p>
     */
    @Test
    public void aPointOutsideTheClusterButInsideItsCloudHasMatter() {
        ClusteredGalaxyGenerator g = new ClusteredGalaxyGenerator(new ReportOnce(), GalaxyGenConfig.defaults());
        Galaxy home = g.galaxies().home(SEED);
        List<Probe> probes = probesOutsideEveryCluster(g, home);
        ArrangementFailure.requireArranged("the search must find a point outside every cluster and inside "
                + "a cloud", !probes.isEmpty());
        for (Probe p : probes) {
            double held = p.cloud.densityAt(p.x, p.y, p.z);
            double reading = g.nebulae().densityAtLightYears(SEED, home, p.x, p.y, p.z);
            assertTrue("at " + p + " the cloud alone holds " + held + " and the field read " + reading,
                    reading >= held);
        }
    }

    /**
     * A sight line that runs outside every cluster's ball, through a cloud, crosses matter.
     *
     * <p>Fails if {@code NebulaField#columnDensityBetween} stops integrating every cloud that reaches
     * the line (it sampled the cluster-clipped reading, so a line through a cloud's outer part read as
     * clear — and so did every survey and obscuration behind one).</p>
     *
     * <p>The line runs one coarse super-cell outward from each probe point; its far end must stand
     * outside every cluster and inside the cloud too, or the line is not used.</p>
     * <p>red-witnessed: with {@code NebulaField#densityAtLightYears} at {@code return densityAmong(cloudsReaching(seed, galaxy, supX, supY, supZ, supX, supY, supZ),} reading only the cloud of the point's own cluster ({@code nebulaAt}), and {@code NebulaField#columnDensityBetween} at {@code double density = densityAmong(clouds, ax + dx * t, ay + dy * t, az + dz * t);} sampling through it, fails: "the line from (-1347.4151752811385, -685.6316157404024, 184.82243554741282) ly in Nebula[DARK r=67ly d=0.8570544725797633 around StarCluster[Molecular Cloud k=1 r=8 super-cells @ -461,-230,62]] one su …" (2026-10-07).</p>
     */
    @Test
    public void aLineThroughACloudOutsideItsClusterCrossesMatter() {
        ClusteredGalaxyGenerator g = new ClusteredGalaxyGenerator(new ReportOnce(), GalaxyGenConfig.defaults());
        Galaxy home = g.galaxies().home(SEED);
        IUniverseLaws laws = g.laws();
        long s = g.config().minSpacing;
        int lines = 0;
        for (Probe p : probesOutsideEveryCluster(g, home)) {
            long fromX = laws.cellsAt(p.x);
            long fromY = laws.cellsAt(p.y);
            long fromZ = laws.cellsAt(p.z);
            long toX = fromX + s;
            if (!(p.cloud.densityAt(laws.lightYearsForCells(toX), p.y, p.z) > 0d)
                    || g.clusters().clusterAt(SEED, home, Math.floorDiv(toX, s), Math.floorDiv(fromY, s),
                            Math.floorDiv(fromZ, s)).isPresent()) {
                continue;
            }
            lines++;
            double column = g.nebulae().columnDensityBetween(SEED, home,
                    GalacticCoord.ofSectorLocal(fromX, fromY, fromZ, 0L, 0L, 0L),
                    GalacticCoord.ofSectorLocal(toX, fromY, fromZ, 0L, 0L, 0L));
            assertTrue("the line from " + p + " one super-cell outward runs through a cloud and read a "
                    + "column of " + column, column > 0d);
        }
        ArrangementFailure.requireArranged("the search must find a line outside every cluster and inside a "
                + "cloud", lines > 0);
    }

    /** A point, and the cloud that reaches it. */
    private static final class Probe {
        final Nebula cloud;
        final double x;
        final double y;
        final double z;

        Probe(Nebula cloud, double x, double y, double z) {
            this.cloud = cloud;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public String toString() {
            return "(" + x + ", " + y + ", " + z + ") ly in " + cloud;
        }
    }

    /**
     * For every cloud near the origin, the first point outward from its centre along +x whose coarse
     * super-cell lies outside its cluster's ball and outside every cluster, while the cloud still holds
     * matter there. A cloud that ends before such a point is passed over.
     */
    private static List<Probe> probesOutsideEveryCluster(ClusteredGalaxyGenerator g, Galaxy home) {
        IUniverseLaws laws = g.laws();
        long s = g.config().minSpacing;
        long r = SEARCH_CLUSTER_CELLS * g.clusters().spacingSuperCells();
        List<Probe> probes = new ArrayList<>();
        for (Nebula n : g.nebulae().nebulaeInRegion(SEED, home, -r, -r, -r, r, r, r)) {
            StarCluster c = n.cluster();
            double y = n.centreYLy();
            double z = n.centreZLy();
            for (long k = c.radiusSuperCells() + 1L; ; k++) {
                double x = laws.lightYearsForCells((double) (c.centreSuperX() + k) * s);
                if (!(n.densityAt(x, y, z) > 0d)) {
                    break;
                }
                long supX = Math.floorDiv(laws.cellsAt(x), s);
                long supY = Math.floorDiv(laws.cellsAt(y), s);
                long supZ = Math.floorDiv(laws.cellsAt(z), s);
                if (c.containsSuperCell(supX, supY, supZ)
                        || g.clusters().clusterAt(SEED, home, supX, supY, supZ).isPresent()) {
                    continue;
                }
                probes.add(new Probe(n, x, y, z));
                break;
            }
        }
        return probes;
    }
}
