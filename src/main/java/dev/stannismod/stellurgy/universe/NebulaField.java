package dev.stannismod.stellurgy.universe;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import dev.stannismod.stellurgy.space.GalacticCoord;

/**
 * Where the nebulae are — which is: wherever a star cluster still has gas.
 *
 * <p>There is no nebula lattice and no nebula spacing. A cloud is derived from the cluster it wraps
 * and that cluster's residual gas, so the two can never disagree about where they are, and adding
 * nebulae cost the generator no new partition, no new occupancy draw and no new number to invent.
 * A cloud with no stars in it is expressible too — it is a cluster type whose subdivision is 1.</p>
 *
 * <h3>This class is a SEAM, and it has no consumer yet</h3>
 * <p>What a nebula DOES to a ship that flies into it — muffled sensors, drag, concealment, something
 * to mine — is deliberately not here. None of those numbers is ratified, and building a mechanic
 * beside the criteria that would judge it is how a mechanic comes to measure itself. What is here is
 * everything such a mechanic would need: where the clouds are, how big they are, and
 * {@link Nebula#densityAt} for how thick one is at a point.</p>
 */
public final class NebulaField {

    private static final long SALT_NEBULA_GAS = 0x301L;
    private static final long SALT_NEBULA_SPREAD = 0x302L;

    /** How much a cluster's residual gas may vary from the figure its type states. */
    private static final double GAS_VARIATION = 0.35d;

    /** Step of the column integral, in light years. A cloud is tens across, so its profile is resolved. */
    private static final double COLUMN_SAMPLE_STEP_LY = 1d;

    /** Ceiling on that integral's samples: a bound on WORK, not a statement about the sky. */
    private static final int MAX_COLUMN_SAMPLES = 512;

    private final GalaxyGenConfig config;
    /** The metric this field measures with — its schema's, not a global one. */
    private final IUniverseLaws laws;
    private final ClusterField clusters;
    /** How far a density reading searches for clouds: {@link #cloudReachSuperCells}. */
    private final long cloudReachSuperCells;

    public NebulaField(GalaxyGenConfig config, ClusterField clusters, IUniverseLaws laws) {
        this.laws = (laws == null) ? UniverseLawsV0.INSTANCE : laws;
        this.config = (config == null) ? GalaxyGenConfig.defaults() : config;
        this.clusters = clusters;
        this.cloudReachSuperCells = cloudReachSuperCells(this.config, this.laws);
    }

    /**
     * The cloud wrapping this cluster, or empty when it has none left.
     *
     * <p>An ancient globular has blown its gas away and gets nothing; a molecular cloud is all gas and
     * no stars; the open clusters between them are the interesting middle.</p>
     */
    public Optional<Nebula> nebulaOf(long seed, StarCluster cluster) {
        if (cluster == null) {
            return Optional.empty();
        }
        double stated = cluster.type().nebulaFraction;
        if (!(stated > 0d)) {
            return Optional.empty();
        }
        // The type says how gassy its age is; the draw says how gassy THIS one is.
        double swing = (CellHash.of(seed, cluster.centreSuperX(), cluster.centreSuperY(),
                cluster.centreSuperZ(), SALT_NEBULA_GAS) >>> 11) * 0x1.0p-53;
        double gas = Math.min(1d, Math.max(0d, stated + (swing - 0.5d) * 2d * GAS_VARIATION));
        if (gas < Nebula.MINIMUM_VISIBLE_GAS) {
            return Optional.empty();
        }

        double spreadRoll = CellHash.norm(CellHash.of(seed, cluster.centreSuperX(),
                cluster.centreSuperY(), cluster.centreSuperZ(), SALT_NEBULA_SPREAD));
        double clusterRadiusLy = laws.lightYearsForCells(
                (double) cluster.radiusSuperCells() * config.minSpacing);
        double radiusLy = clusterRadiusLy * Nebula.spreadFor(spreadRoll);

        long s = config.minSpacing;
        return Optional.of(new Nebula(cluster, Nebula.appearanceFor(gas),
                laws.lightYearsForCells((double) cluster.centreSuperX() * s),
                laws.lightYearsForCells((double) cluster.centreSuperY() * s),
                laws.lightYearsForCells((double) cluster.centreSuperZ() * s),
                radiusLy, gas, laws));
    }

    /** The cloud covering this coarse super-cell, if a cluster covers it and still has one. */
    public Optional<Nebula> nebulaAt(long seed, Galaxy galaxy, long supX, long supY, long supZ) {
        Optional<StarCluster> cluster = clusters.clusterAt(seed, galaxy, supX, supY, supZ);
        return cluster.isPresent() ? nebulaOf(seed, cluster.get()) : Optional.<Nebula>empty();
    }

    /**
     * Every nebula seated in the box of coarse super-cells {@code [min, max]} — what a render or a
     * long-range scan asks, because a cloud is meant to be seen from OUTSIDE it.
     *
     * <p>Enumerated over the CLUSTER lattice rather than per super-cell, so the cost is the number of
     * cluster cells the box crosses and not its volume.</p>
     */
    public List<Nebula> nebulaeInRegion(long seed, Galaxy galaxy, long supMinX, long supMinY,
                                        long supMinZ, long supMaxX, long supMaxY, long supMaxZ) {
        List<Nebula> out = new ArrayList<>();
        if (galaxy == null) {
            return out;
        }
        long spacing = clusters.spacingSuperCells();
        // A cloud reaches beyond its own cluster cell, so the sweep widens by one cell each way.
        for (long cx = Math.floorDiv(supMinX, spacing) - 1L;
                cx <= Math.floorDiv(supMaxX, spacing) + 1L; cx++) {
            for (long cy = Math.floorDiv(supMinY, spacing) - 1L;
                    cy <= Math.floorDiv(supMaxY, spacing) + 1L; cy++) {
                for (long cz = Math.floorDiv(supMinZ, spacing) - 1L;
                        cz <= Math.floorDiv(supMaxZ, spacing) + 1L; cz++) {
                    Optional<StarCluster> cluster = clusters.clusterAtIndex(seed, galaxy, cx, cy, cz);
                    if (!cluster.isPresent()) {
                        continue;
                    }
                    Optional<Nebula> nebula = nebulaOf(seed, cluster.get());
                    if (nebula.isPresent()) {
                        out.add(nebula.get());
                    }
                }
            }
        }
        // The nucleus is not on the cluster lattice, so it is asked for separately — the same
        // exception the cluster tier already makes for it.
        Optional<StarCluster> nucleus = clusters.nucleusOf(seed, galaxy);
        if (nucleus.isPresent()) {
            Optional<Nebula> core = nebulaOf(seed, nucleus.get());
            if (core.isPresent()) {
                out.add(core.get());
            }
        }
        return out;
    }

    /**
     * How much diffuse matter lies ALONG A LINE, in density-light-years — the integral of
     * {@link #densityAtSector} from one cell to another.
     *
     * <p><b>Built once, on purpose.</b> Every consequence of a cloud that involves LOOKING is this
     * number: what a survey loses to a cloud between it and its target, and what a ship inside one
     * loses looking out, are the same integral with the endpoints moved. Two functions computing it
     * would drift in the third decimal and nobody would notice for months.</p>
     *
     * <p>Sampled rather than solved. A closed form exists for one Gaussian, but the line crosses an
     * arbitrary set of clouds seated on a lattice, and the sampled form stays correct when the
     * profile changes. The step is a light year — a cloud is tens of them across, so its profile is
     * resolved many times over — and the sample count is bounded, which is a bound on WORK and not a
     * physical statement.</p>
     */
    public double columnDensityBetween(long seed, Galaxy galaxy, GalacticCoord from,
                                       GalacticCoord to) {
        if (galaxy == null || from == null || to == null) {
            return 0d;
        }
        GalacticCoord a = from.cellCentre();
        GalacticCoord b = to.cellCentre();
        double ax = laws.lightYearsForCells(a.sectorX());
        double ay = laws.lightYearsForCells(a.sectorY());
        double az = laws.lightYearsForCells(a.sectorZ());
        double bx = laws.lightYearsForCells(b.sectorX());
        double by = laws.lightYearsForCells(b.sectorY());
        double bz = laws.lightYearsForCells(b.sectorZ());
        double dx = bx - ax, dy = by - ay, dz = bz - az;
        double lengthLy = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (lengthLy <= 0d) {
            return 0d;
        }

        int samples = (int) Math.max(2L, Math.min(MAX_COLUMN_SAMPLES,
                Math.round(lengthLy / COLUMN_SAMPLE_STEP_LY) + 1L));
        double step = lengthLy / (samples - 1);
        // Every cloud that can reach any point of the line, gathered once for the whole walk.
        long s = config.minSpacing;
        List<Nebula> clouds = cloudsReaching(seed, galaxy,
                Math.min(Math.floorDiv(a.sectorX(), s), Math.floorDiv(b.sectorX(), s)),
                Math.min(Math.floorDiv(a.sectorY(), s), Math.floorDiv(b.sectorY(), s)),
                Math.min(Math.floorDiv(a.sectorZ(), s), Math.floorDiv(b.sectorZ(), s)),
                Math.max(Math.floorDiv(a.sectorX(), s), Math.floorDiv(b.sectorX(), s)),
                Math.max(Math.floorDiv(a.sectorY(), s), Math.floorDiv(b.sectorY(), s)),
                Math.max(Math.floorDiv(a.sectorZ(), s), Math.floorDiv(b.sectorZ(), s)));
        double sum = 0d;
        for (int i = 0; i < samples; i++) {
            double t = i / (double) (samples - 1);
            double density = densityAmong(clouds, ax + dx * t, ay + dy * t, az + dz * t);
            // Trapezoid: the endpoints are half-weighted, so the answer does not depend on which
            // end the walk started from.
            sum += (i == 0 || i == samples - 1) ? density * 0.5d : density;
        }
        return sum * step;
    }

    /**
     * The density at a point stated in light years — what the line integral samples.
     *
     * <p><b>Every cloud that REACHES the point counts, not the cluster the point is in.</b> A cloud is
     * {@link Nebula#spreadFor 1.5-3 times} wider than its cluster and Gaussian, so at the cluster's own
     * edge it is still a sixth to two thirds of its peak. Asking only the cluster that contains the
     * point read every cloud as ending at its cluster's ball, while the sky drew the whole of it.
     * Overlapping clouds add, capped at the scale's {@code 1}.</p>
     */
    public double densityAtLightYears(long seed, Galaxy galaxy, double xLy, double yLy, double zLy) {
        if (galaxy == null) {
            return 0d;
        }
        long s = config.minSpacing;
        long supX = Math.floorDiv(laws.cellsAt(xLy), s);
        long supY = Math.floorDiv(laws.cellsAt(yLy), s);
        long supZ = Math.floorDiv(laws.cellsAt(zLy), s);
        return densityAmong(cloudsReaching(seed, galaxy, supX, supY, supZ, supX, supY, supZ),
                xLy, yLy, zLy);
    }

    /** Every cloud that can reach some point of the coarse super-cell box {@code [min, max]}. */
    private List<Nebula> cloudsReaching(long seed, Galaxy galaxy, long minX, long minY, long minZ,
                                        long maxX, long maxY, long maxZ) {
        return nebulaeInRegion(seed, galaxy, minX - cloudReachSuperCells, minY - cloudReachSuperCells,
                minZ - cloudReachSuperCells, maxX + cloudReachSuperCells, maxY + cloudReachSuperCells,
                maxZ + cloudReachSuperCells);
    }

    /** The summed density of {@code clouds} at a point, capped at the scale's {@code 1}. */
    private static double densityAmong(List<Nebula> clouds, double xLy, double yLy, double zLy) {
        double total = 0d;
        for (Nebula nebula : clouds) {
            total += nebula.densityAt(xLy, yLy, zLy);
        }
        return Math.min(1d, total);
    }

    /**
     * How much diffuse matter lies at this cell, {@code 0}..{@code 1} — the one query a consequence
     * would be written against, whatever the consequence turns out to be. The same reading as
     * {@link #densityAtLightYears} at the cell's own point.
     */
    public double densityAtSector(long seed, Galaxy galaxy, long sectorX, long sectorY, long sectorZ) {
        return densityAtLightYears(seed, galaxy, laws.lightYearsForCells(sectorX),
                laws.lightYearsForCells(sectorY), laws.lightYearsForCells(sectorZ));
    }

    /**
     * How far, in coarse super-cells, the widest cloud this table can seat reaches from its centre —
     * the widest cluster type (the nucleus included) at the widest spread, plus one cell for where in
     * its super-cell a centre or a point sits.
     */
    private static long cloudReachSuperCells(GalaxyGenConfig config, IUniverseLaws laws) {
        double widestClusterLy = GalaxyGenConfig.NUCLEUS.maxRadiusLy;
        for (GalaxyGenConfig.ClusterType type : config.clusterTypes) {
            widestClusterLy = Math.max(widestClusterLy, type.maxRadiusLy);
        }
        long s = Math.max(1L, config.minSpacing);
        long clusterSuperCells = Math.max(1L, laws.cellsForLightYears(widestClusterLy) / s);
        return (long) Math.ceil(clusterSuperCells * Nebula.spreadFor(1d)) + 1L;
    }
}
