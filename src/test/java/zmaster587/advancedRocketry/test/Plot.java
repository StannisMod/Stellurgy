package zmaster587.advancedRocketry.test;

/**
 * A private patch of world handed to exactly one scenario of a shared-harness class.
 *
 * <p><b>It lived in {@code test.client} until 2026-09-14.</b> A patch of world is not a client
 * concept: the server tier needs one for the same reason and had nothing — its position-isolation
 * contract was prose in a javadoc, asking each method to pick a unique base by hand. Moving it up
 * is what lets {@link #site} exist, and {@code site()} is the whole point: the plot supplies
 * NON-OVERLAP and {@link FixtureSite} supplies the HEIGHT, and until they were joined every
 * scenario chose coordinates for itself.</p>
 *
 * <p>When one client harness carries several scenarios they also share one WORLD, and that
 * multiplies a specific failure: a "find the X" query answering with a DIFFERENT scenario's object,
 * so the assertion passes on scaffolding the test never built. Three queries in the client suite are
 * already global and would do exactly this — {@code artest rocket list 0} in
 * {@code RocketBuilderGuiE2ETest} and {@code FreeFlightModeE2ETest}, {@code artest station list} in
 * {@code SpaceDimGuardE2ETest}.</p>
 *
 * <p>The defence is spatial and it is deliberately dumb: <b>one plot per scenario, never
 * recycled</b>. Nothing has to be cleaned up afterwards, because nothing else is ever going to look
 * here — which is cheaper and far more reliable than a teardown that has to be remembered. A
 * scenario that must ask a global question narrows it with {@link #contains} instead of trusting the
 * answer.</p>
 *
 * <p>Plots only need to be unique WITHIN a class: every test class boots its own harness and
 * therefore its own world.</p>
 */
public final class Plot {

    /** Edge of a plot, in blocks. Wide enough for a rocket fixture plus its pad. */
    public static final int SIZE = 64;

    /**
     * Open air above generated terrain, so a plot starts empty without clearing anything.
     *
     * <p>The band is defined once, at {@link zmaster587.advancedRocketry.test.FixtureSite#OPEN_AIR_Y},
     * because a client plot and a server fixture are asking the same question of the world and two
     * numbers that are the SAME quantity must not be able to drift apart.</p>
     */
    public static final int DEFAULT_Y = zmaster587.advancedRocketry.test.FixtureSite.OPEN_AIR_Y;

    /**
     * The ground a fixture that needs REAL TERRAIN stands on, measured rather than chosen.
     *
     * <p>GROUND-SUBJECT: this constant IS the terrain. It is the surveyed surface height of the two
     * clean plots the pinned seed offers, so a fixture-site counter that flags it for standing below
     * the open-air band is reporting the definition of the exception rather than an instance of the
     * defect. Every other Y in the suite moved into the band on 2026-09-14; this one is why the band
     * has an opposite.</p>
     *
     * <p>A fixture built at a fixed Y needs the surface to be AT that Y, flat, dry and unobstructed
     * across its whole footprint, and the pinned seed grants that almost nowhere. Surveyed
     * 2026-08-14 with {@code artest worldgen survey} over each candidate's real 16x16 footprint:
     * <b>61 chunk-centre candidates across a 384x384 region, plus a 110-plot grid over the
     * neighbourhood, yielded exactly TWO clean plots</b> — this one and {@link #CLEAN_GROUND_Z2}.
     * Both read {@code relief=0, modeTopY=64, modeTopShare=1.0, liquidColumns=0,
     * solidObstructedColumns=0}, all grass, plains.</p>
     *
     * <p>What the same survey said about where these fixtures USED to stand is why this exists:
     * 7200/7220 sit under a mountain whose surface is y=80..99, so a fixture at y=64 was buried
     * sixteen to thirty-five blocks inside rock; 7420 had 49 water columns; 3000 sat under a forest
     * canopy on ground at y=71..79. None were marginal landscapes a luckier seed would have saved.</p>
     *
     * <p>A flat harness world would make every coordinate equally good and was tried on 2026-08-14;
     * it cost more heap, more wall clock and three unexplained reds, so the survey stands.
     * <b>Do not move these numbers without re-running the survey</b> —
     * {@code FixtureGroundOnPinnedSeedTest} asserts every ground fixture's own surface.</p>
     */
    public static final int CLEAN_GROUND_X = 7096;
    /** @see #CLEAN_GROUND_X */
    public static final int CLEAN_GROUND_Y = 64;
    /** @see #CLEAN_GROUND_X */
    public static final int CLEAN_GROUND_Z = 7224;
    /**
     * The second and last clean plot, for a class whose scenarios share one world. Sixteen blocks
     * from {@link #CLEAN_GROUND_Z}, which is as far apart as the pinned seed allows two clean plots
     * to be — the survey found no third within 160 blocks in any direction.
     */
    public static final int CLEAN_GROUND_Z2 = 7240;

    /** Edge of the surveyed-clean footprint around a clean-ground base, as an offset. */
    public static final int CLEAN_GROUND_FOOT_MIN = -4;
    /** @see #CLEAN_GROUND_FOOT_MIN */
    public static final int CLEAN_GROUND_FOOT_MAX = 11;

    /**
     * Where a class's plots live and how far apart they sit.
     *
     * <p>This is a per-class choice on purpose, and the reason is terrain. A plot at ground level
     * inherits whatever the fixed world seed generated there — a hill, an ocean, a forest — and this
     * repo has lost runs to all three. <b>A class migrating an existing test should keep the
     * coordinates that test already proved</b> rather than inherit {@link #DEFAULT}: those numbers
     * are backed by however many green runs the test has behind it, and a fresh lane is not.</p>
     */
    public static final class Lane {
        public final int originX;
        public final int originZ;
        /** Distance between successive plot origins. Must be at least {@link #plotSize}. */
        public final int stride;
        /**
         * Edge of every plot on this lane. Widen it for a class whose fixtures are bigger than
         * {@link Plot#SIZE} — a pair of linked machines standing 60 blocks apart, a multiblock plus
         * its clearance. Widening the plot is the honest move there; reaching past the plot's edge
         * into a neighbour's is what the allocator exists to prevent.
         */
        public final int plotSize;

        public Lane(int originX, int originZ, int stride) {
            this(originX, originZ, stride, SIZE);
        }

        public Lane(int originX, int originZ, int stride, int plotSize) {
            if (stride < plotSize) {
                throw new IllegalArgumentException("stride " + stride + " < plot size " + plotSize
                        + " — plots would overlap, which is the one thing they exist to prevent");
            }
            this.originX = originX;
            this.originZ = originZ;
            this.stride = stride;
            this.plotSize = plotSize;
        }

        /**
         * Far from every fixture range the existing suites use (200-350, 2100-2140, 3000-5100,
         * 6620/6820), and green 10/10 for the seal-detector pilot at {@link Plot#DEFAULT_Y}, which
         * is air and therefore terrain-independent. <b>Do not move it</b> — that green is what makes
         * it a default rather than a guess. A scenario that needs GROUND is on its own terrain and
         * should declare its own lane.
         */
        public static final Lane DEFAULT = new Lane(4000, 4000, SIZE);
    }

    private final int index;
    private final String owner;
    public final int dim;
    /** North-west corner of the plot. */
    public final int originX;
    public final int originZ;
    /** Edge of this plot, from its lane. Usually {@link #SIZE}. */
    public final int size;

    private Plot(int index, String owner, int dim, Lane lane) {
        this.index = index;
        this.owner = owner;
        this.dim = dim;
        this.originX = lane.originX + index * lane.stride;
        this.originZ = lane.originZ;
        this.size = lane.plotSize;
    }

    /**
     * The plot for one scenario. {@code index} must be unique within the class that allocates —
     * that, plus {@link Lane}'s refusal of a stride narrower than a plot, is the whole non-overlap
     * argument, and it is structural rather than a convention anyone has to remember.
     *
     * <p>Public because two shared bases in two packages allocate, and the allocator's own test
     * builds plots directly rather than through a JUnit lifecycle.</p>
     */
    public static Plot forScenario(int index, String owner, int dim, Lane lane) {
        return new Plot(index, owner, dim, lane);
    }

    /**
     * How far into the plot a fixture stands. Leaves {@value} blocks of margin on the low side and
     * {@code size - 1 - INSET - FixtureSite.PAD} on the high side, so a fixture's working envelope
     * fits around it; {@link #maxHalo()} is that budget, and {@code requireClear} refuses a halo
     * past it rather than quietly reaching into a neighbour.
     *
     * <p><b>PUBLIC because a migrating class needs to subtract it.</b> A lane declares its ORIGIN,
     * a site stands {@value} blocks into the plot, and a class that means "put the fixture exactly
     * where its green runs were taken" therefore writes {@code new Lane(proven - FIXTURE_INSET, …)}.
     * A lane written AT the proven number instead moves the fixture twenty blocks and nothing says
     * so, which is the whole reason this is not private.</p>
     */
    public static final int FIXTURE_INSET = 20;

    /**
     * WHERE THIS SCENARIO'S FIXTURE STANDS — the one supported way to get a site.
     *
     * <p>It joins the two halves that were never joined: the plot decides WHERE (unique per
     * scenario, on a lane whose stride cannot be narrower than a plot), and {@link FixtureSite}
     * decides the HEIGHT — its {@code openAir} takes no Y at all. A scenario that asks for this
     * cannot collide with its sibling and cannot stand in terrain, and neither is a thing it has to
     * get right.</p>
     */
    public FixtureSite site() {
        return siteAt(FIXTURE_INSET, FIXTURE_INSET);
    }

    /**
     * A site at a CHOSEN point inside this plot, for the rare scenario that stands TWO structures
     * and needs a stated distance between them — a ship and the control cabin it is compared
     * against, two craft one of which is later parked near the other's base.
     *
     * <p>This is not a way round {@link #site}: the offsets are into THIS plot, so {@link #x} and
     * {@link #z} refuse anything outside it, and the two sites' working volumes are checked against
     * each other when they are cleared (see {@link #claimWorkingVolume}). What the caller is
     * choosing is the SEPARATION, which is the thing its scenario actually means; where the pair
     * lives is still the allocator's.</p>
     *
     * <p>A scenario whose structures do not fit declares a wider {@link Lane#plotSize}.</p>
     */
    public FixtureSite siteAt(int dx, int dz) {
        return FixtureSite.openAirIn(this, x(dx), z(dz));
    }

    /**
     * The widest halo a fixture standing at this world point may clear before its envelope would
     * leave the plot. A lane with a wider {@code plotSize} raises it; that is the supported way to
     * need more room.
     */
    public int maxHaloAt(int worldX, int worldZ) {
        int west = worldX - originX;
        int north = worldZ - originZ;
        int east = originX + size - 1 - (worldX + FixtureSite.PAD);
        int south = originZ + size - 1 - (worldZ + FixtureSite.PAD);
        return Math.min(Math.min(west, north), Math.min(east, south));
    }

    /** The budget for a site from {@link #site()} — the one nearly every scenario stands on. */
    public int maxHalo() {
        return maxHaloAt(x(FIXTURE_INSET), z(FIXTURE_INSET));
    }

    /**
     * Working volumes already cleared on this plot, keyed by the SITE that cleared each.
     *
     * <p>Keyed by site and not by box on purpose: one fixture re-prepared with a different halo is
     * one structure and must be allowed to grow, while two DIFFERENT bases reaching into each other
     * is the defect. A plot is handed to one scenario and a scenario runs once, so nothing here
     * outlives the test method that filled it.</p>
     */
    private final java.util.Map<String, int[]> cleared = new java.util.LinkedHashMap<>();

    /**
     * Record that a fixture at {@code (siteX, siteZ)} is about to clear this box, and REFUSE it if
     * another site on this same plot has already cleared ground it overlaps.
     *
     * <p>{@link #forScenario} keeps two SCENARIOS apart; this keeps two STRUCTURES of one scenario
     * apart, which is the same failure one level down and the one {@link #siteAt} makes possible.
     * Neither is a promise a test has to keep — both are refusals before a block is touched.</p>
     */
    void claimWorkingVolume(int siteX, int siteZ, int x1, int z1, int x2, int z2, String what) {
        String key = siteX + "," + siteZ;
        for (java.util.Map.Entry<String, int[]> other : cleared.entrySet()) {
            if (other.getKey().equals(key)) {
                continue;
            }
            int[] o = other.getValue();
            boolean disjoint = x2 < o[0] || o[2] < x1 || z2 < o[1] || o[3] < z1;
            if (!disjoint) {
                ArrangementFailure.arrangementFailed(
                        what + " — this scenario already cleared (" + o[0] + "," + o[1] + ")..("
                                + o[2] + "," + o[3] + ") for its fixture at " + other.getKey()
                                + ", and the volume asked for now, (" + x1 + "," + z1 + ")..("
                                + x2 + "," + z2 + "), reaches into it. One of the two structures"
                                + " would level the other and neither half of the scenario could"
                                + " see it happen; stand them further apart on " + this);
            }
        }
        cleared.put(key, new int[]{x1, z1, x2, z2});
    }

    /** Does this whole horizontal box lie inside the plot? */
    public boolean containsBox(int x1, int z1, int x2, int z2) {
        return x1 >= originX && z1 >= originZ
                && x2 < originX + size && z2 < originZ + size;
    }

    /** Absolute X of a point {@code dx} blocks into the plot. */
    public int x(int dx) {
        if (dx < 0 || dx >= size) {
            throw new IllegalArgumentException("dx=" + dx + " leaves plot " + this
                    + " — a scenario that needs more room declares a wider lane, not a neighbour's plot");
        }
        return originX + dx;
    }

    /** Absolute Z of a point {@code dz} blocks into the plot. */
    public int z(int dz) {
        if (dz < 0 || dz >= size) {
            throw new IllegalArgumentException("dz=" + dz + " leaves plot " + this
                    + " — a scenario that needs more room declares a wider lane, not a neighbour's plot");
        }
        return originZ + dz;
    }

    public int centerX() {
        return originX + size / 2;
    }

    public int centerZ() {
        return originZ + size / 2;
    }

    /**
     * Is this world position inside the plot? Use it to filter a GLOBAL probe answer down to this
     * scenario's own objects — the answer to "is there a rocket in dim 0" is not the answer to "did
     * MY arrangement build one".
     */
    public boolean contains(double worldX, double worldZ) {
        return worldX >= originX && worldX < originX + size
                && worldZ >= originZ && worldZ < originZ + size;
    }

    public int index() {
        return index;
    }

    public String owner() {
        return owner;
    }

    @Override
    public String toString() {
        return "Plot#" + index + "[" + owner + " dim=" + dim
                + " x=" + originX + ".." + (originX + size - 1)
                + " z=" + originZ + ".." + (originZ + size - 1) + "]";
    }
}
