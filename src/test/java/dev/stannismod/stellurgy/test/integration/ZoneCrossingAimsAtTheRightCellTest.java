package dev.stannismod.stellurgy.test.integration;

import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import dev.stannismod.stellurgy.api.dimension.solar.StellarBody;
import dev.stannismod.stellurgy.dimension.DimensionManager;
import dev.stannismod.stellurgy.dimension.DimensionProperties;
import dev.stannismod.stellurgy.space.AbsolutePos;
import dev.stannismod.stellurgy.space.CellSeam;
import dev.stannismod.stellurgy.space.GalacticCoord;
import dev.stannismod.stellurgy.space.SpaceSubsystem;
import dev.stannismod.stellurgy.test.MinecraftBootstrap;
import dev.stannismod.stellurgy.universe.SystemBody;
import dev.stannismod.stellurgy.universe.UniverseRegistry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Where a craft that has crossed a zone's sphere is NAMED — the aiming half of the reference-frame
 * clause, asked of the real universe.
 *
 * <p>The arming half (how far past a radius counts as out) is arithmetic and lives in
 * {@code CellSeamTest}; the lattice's own sizing lives in {@code ZoneScaleTest}. Neither can see the
 * question here, which is what happens when the two are put together against a registry that has
 * already NAMED the bodies involved. That join is where the address is chosen, and it is the one
 * place in the crossing whose failure mode is silent: a cell key carries no lattice width, so an
 * address built on the wrong lattice does not throw and does not mis-normalise — it denotes a
 * different cell, and every reader downstream answers correctly about the wrong place.</p>
 *
 * <p><b>Why an integration test and not the VS e2e.</b> The e2e flies a real ship and is the only
 * thing that can show a crossing happening in a world. It cannot cheaply put a craft at a chosen
 * distance from a chosen moon, and it would report a mis-aimed address as "the ship ended up
 * somewhere odd". This asks the question directly, on the real solar arithmetic.</p>
 */
public class ZoneCrossingAimsAtTheRightCellTest {

    @BeforeClass
    public static void bootstrap() {
        MinecraftBootstrap.ensure();
    }

    @After
    public void unwireTheStarLookup() {
        UniverseRegistry.setStarLookup(null);
    }

    private static final int STAR_ID = 4262;
    private static final int EARTH_DIM = 794;
    private static final int LUNA_DIM = 795;

    /**
     * <b>A craft carried OUT of a moon's sphere is named on the lattice its new neighbours are named
     * on.</b>
     *
     * <p>A craft leaving Luna's influence is, at that instant, still where Luna is. The cell it is
     * given must therefore be Luna's own cell in Earth's zone — the same cell, on the same lattice,
     * that Luna itself was named by when the system was built.</p>
     *
     * <p>Measured before this was pinned: the crossing sized its own lattice from a literal "nothing
     * to name apart", so it addressed the craft on a cell four times too coarse (7 397 280 blocks
     * against the 1 849 294 this fixture's Luna is named on). The craft arrived in the cell holding
     * EARTH, 1.5M blocks from where it was, and the moon it had just left was not among that cell's
     * bodies — it could not descend to it and its sky did not draw it.</p>
     *
     * <p>red-witnessed: 2026-09-29, TWICE, one inversion per assertion, each leaving the other two
     * scenarios green. With {@code SpaceSubsystem#latticeOf} at {@code long named = reg == null ? GalacticCoord.WIDTH_UNKNOWN} made to ignore the recorded width, the
     * naming verdict fails *"expected:&lt;1849294&gt; but was:&lt;7397176&gt;"*. With
     * {@code SpaceSubsystem#addressIn} at {@code GalacticCoord cell = latticeAddress.cellCentre()} made to hand back the lattice address unchanged, the
     * continuity verdict fails *"re-addressing must not displace the craft expected:&lt;0.0&gt; but
     * was:&lt;321993.763009472&gt;"*.</p>
     */
    @Test
    public void aCraftLeavingAMoonsSphereIsNamedByTheMoonsOwnCell() {
        Fixture home = arrangeEarthAndLuna();
        long lunaCell = home.luna.name().cellBlocks();
        assertTrue("arrangement: the moon must be named on a real lattice of its parent's zone",
                lunaCell > 0L && home.earth.name().cellKey().equals(home.luna.name().zone()));

        // A craft just outside Luna's sphere, in Luna's own zone — the pose the outward seam fires at.
        long lunaSphere = dev.stannismod.stellurgy.space.ZoneScale
                .realizedRadiusBlocks(home.luna, home.earth, TICK);
        assertTrue("arrangement: the moon must have a sphere to leave", lunaSphere > 0L);
        System.out.println("[zone-crossing] Luna's sphere against EARTH=" + lunaSphere
                + " blocks, against SOL=" + dev.stannismod.stellurgy.space.ZoneScale
                        .realizedRadiusBlocks(home.luna, home.sol, TICK)
                + " blocks; Earth's zone lattice=" + home.luna.name().cellBlocks());
        long out = (long) Math.ceil(lunaSphere * (1d + CellSeam.SPHERE_CARRY_FRACTION)) + 1L;
        GalacticCoord craft = inLunasZone(home, out);
        assertTrue("arrangement: production's own predicate must agree the craft has left the "
                        + "sphere, or this measures a craft that never crossed anything",
                CellSeam.hasLeftZone(CellSeam.distanceFromZoneBody(craft), lunaSphere));

        GalacticCoord named = SpaceSubsystem.zoneMembershipIn(home.reg, craft, TICK);
        assertNotNull("a craft past its zone's sphere must be re-addressed, not left where it is",
                named);

        assertEquals("the crossing must address the craft on the lattice its new neighbours are "
                        + "named on; the moon beside it was named on " + lunaCell,
                lunaCell, named.cellBlocks());
        assertTrue("a craft that has only just left the moon is still where the moon is, so it must "
                        + "be named by the moon's OWN cell (" + home.luna.name().cellKey() + "); it "
                        + "was named by " + named.cellKey() + " on a lattice of "
                        + named.cellBlocks() + " blocks",
                named.sameCell(home.luna.name()));

        // ...and it did not move. The name changed; the craft did not.
        assertEquals("re-addressing must not displace the craft", 0d,
                absoluteOf(home.reg, named).distanceTo(absoluteOf(home.reg, craft)), CONTINUITY_SLACK);
    }

    /**
     * <b>A craft carried INTO a moon's sphere lands in that moon's zone, at the offset it had.</b>
     *
     * <p>The inward half of the same seam, and it is a different code path: outward re-addresses
     * against the GRANDPARENT, inward against a child the loop has just found. The two shared the
     * lattice defect and would have to share its fix.</p>
     *
     * <p>red-witnessed: 2026-09-29, with {@code CellSeam#hasEnteredZone} at
     * {@code return zoneRadiusBlocks > 0d} made to answer {@code false} always, this fails with *"a craft inside a child's sphere must be taken into that child's
     * zone"* while the outward and hysteresis scenarios stay green — so the red is this path's and
     * not the seam's in general. Re-run 2026-09-30 (`:142`) after the arrangement began asserting
     * the craft's distance from the moon: same verdict, the arrangement held.</p>
     */
    @Test
    public void aCraftEnteringAMoonsSphereLandsInTheMoonsOwnZone() {
        Fixture home = arrangeEarthAndLuna();
        long lunaSphere = dev.stannismod.stellurgy.space.ZoneScale
                .realizedRadiusBlocks(home.luna, home.earth, TICK);
        assertTrue("arrangement: the moon must have a sphere to enter", lunaSphere > 0L);

        // Well inside Luna's sphere, but addressed in EARTH's zone — a craft that has flown in.
        long in = (long) (lunaSphere * 0.5d);
        GalacticCoord craft = inEarthsZoneNearLuna(home, in);
        // ARRANGEMENT, as the quantity the threshold compares: the craft's distance from the MOON,
        // against the entry threshold production states. The address is in Earth's zone, so the
        // zone distance would measure from the wrong body; this reads where the craft actually is.
        // Plain arithmetic on purpose, NOT `hasEnteredZone`: that predicate is what this scenario's
        // red-witness breaks, and an arrangement that called it would take the red on itself.
        double fromLuna = absoluteOf(home.reg, craft).distanceTo(home.luna.absoluteAt(TICK));
        assertTrue("arrangement: the craft must be inside the moon's entry threshold (" + fromLuna
                        + " blocks, sphere " + lunaSphere + ")",
                fromLuna < lunaSphere * (1d - CellSeam.SPHERE_REENTRY_FRACTION));

        GalacticCoord named = SpaceSubsystem.zoneMembershipIn(home.reg, craft, TICK);
        assertNotNull("a craft inside a child's sphere must be taken into that child's zone", named);
        assertEquals("...whose lattice is the child's own", home.luna.name().cellKey(),
                named.zone());
        assertEquals("re-addressing inward must not displace the craft either", 0d,
                absoluteOf(home.reg, named).distanceTo(absoluteOf(home.reg, craft)), CONTINUITY_SLACK);
    }

    /**
     * Between the two thresholds nothing happens — the hysteresis, read through the whole decision
     * rather than through the arithmetic alone.
     *
     * <p>The CONTROL for both tests above: without it, a re-address that fired on every tick would
     * satisfy them exactly as well as one that fires when a boundary is crossed.</p>
     *
     * <p>red-witnessed: 2026-09-29, with {@code CellSeam#hasLeftZone} at
     * {@code return zoneRadiusBlocks > 0d} made to fire for any body with a sphere — the exact "re-addresses on every tick" defect this control exists to exclude — it
     * fails with *"must be left where it is expected null, but was: GalacticCoord[zone=19_0_0@1849294,
     * sector=(1,0,0) …]"*, and the other two scenarios stay green ON that same inversion, which is
     * precisely why a control is needed: they cannot tell the difference. Re-run the same day after
     * the positive half below was added, with the same result, and again 2026-09-30 (`:129`) after
     * the arrangement began asserting the craft's distance: same verdict, the arrangement held.</p>
     *
     * <p><b>The positive half, and the inversion that shows why it is here</b>: with
     * {@code SystemBody#definesFrame} at {@code || kind == SystemBodyKind.ROGUE_PLANET || kind == SystemBodyKind.MOON}
     * no longer counting a MOON, {@code zoneMembershipIn} cannot resolve
     * the moon's zone and answers {@code null} for that reason alone — which the null verdict below
     * would have read as the hysteresis, green. The positive half fails instead: "arrangement: this
     * zone must resolve — a craft past its sphere is re-addressed" (2026-09-29).</p>
     */
    @Test
    public void aCraftWellInsideItsOwnZoneIsLeftAlone() {
        Fixture home = arrangeEarthAndLuna();
        long lunaSphere = dev.stannismod.stellurgy.space.ZoneScale
                .realizedRadiusBlocks(home.luna, home.earth, TICK);
        assertTrue("arrangement: the moon must have a sphere to be inside", lunaSphere > 0L);
        // The POSITIVE half, in this method (STEP 7): the same zone, the same registry, a craft past
        // the sphere IS re-addressed. A null from `zoneMembershipIn` also means "the universe could
        // not be asked", so without this the null below could be that and not the hysteresis.
        long out = (long) Math.ceil(lunaSphere * (1d + CellSeam.SPHERE_CARRY_FRACTION)) + 1L;
        assertNotNull("arrangement: this zone must resolve — a craft past its sphere is re-addressed",
                SpaceSubsystem.zoneMembershipIn(home.reg, inLunasZone(home, out), TICK));

        GalacticCoord craft = inLunasZone(home, lunaSphere / 2L);
        // Plain arithmetic, not `hasLeftZone`: that predicate is what this control's red-witness
        // breaks, and an arrangement that called it would take the red on itself.
        double fromLuna = CellSeam.distanceFromZoneBody(craft);
        assertTrue("arrangement: the craft must be inside the exit threshold (" + fromLuna
                        + " blocks, sphere " + lunaSphere + "), or a null below is the geometry and "
                        + "not the hysteresis",
                fromLuna >= 0d && fromLuna < lunaSphere * (1d + CellSeam.SPHERE_CARRY_FRACTION));
        assertNull("a craft inside its own zone and inside nothing else must be left where it is",
                SpaceSubsystem.zoneMembershipIn(home.reg, craft, TICK));
    }

    /**
     * <b>A craft in the PLANET'S OWN galactic cell that flies into a moon's sphere lands in that
     * moon's zone, at the position it had.</b>
     *
     * <p>This is the address every craft holds when it enters space from a planet, so it is the
     * player's own path to a moon by hand. The two scenarios above start from a ZONED address,
     * which only a jump or an earlier crossing hands out; they cannot see this one.</p>
     *
     * <p>red-witnessed: 2026-10-01, with {@code SpaceSubsystem#zoneMembershipIn} at
     * {@code if (craftCoord == null || craftCoord.cellBlocks() <= 0L)} put back to refusing any
     * craft whose {@code zone()} is null (the guard as it was), this fails with "a craft in a planet's
     * own cell that is inside a moon's sphere must be taken into the moon's zone, not left riding the
     * planet", while the three zoned scenarios stay green on the same inversion.</p>
     */
    @Test
    public void aCraftInThePlanetsOwnCellEnteringAMoonsSphereLandsInTheMoonsZone() {
        Fixture home = arrangeEarthAndLuna();
        long lunaSphere = dev.stannismod.stellurgy.space.ZoneScale
                .realizedRadiusBlocks(home.luna, home.earth, TICK);
        assertTrue("arrangement: the moon must have a sphere to enter", lunaSphere > 0L);

        GalacticCoord craft = inEarthsGalacticCellNearLuna(home, (long) (lunaSphere * 0.5d));
        assertNull("arrangement: the craft must hold a GALACTIC address, as one fresh from the "
                + "planet does: " + craft, craft.zone());
        // Plain arithmetic, not `hasEnteredZone`, for the reason given on the zoned inward scenario.
        double fromLuna = absoluteOf(home.reg, craft).distanceTo(home.luna.absoluteAt(TICK));
        assertTrue("arrangement: the craft must be inside the moon's entry threshold (" + fromLuna
                        + " blocks, sphere " + lunaSphere + ")",
                fromLuna < lunaSphere * (1d - CellSeam.SPHERE_REENTRY_FRACTION));

        GalacticCoord named = SpaceSubsystem.zoneMembershipIn(home.reg, craft, TICK);
        assertNotNull("a craft in a planet's own cell that is inside a moon's sphere must be taken "
                + "into the moon's zone, not left riding the planet", named);
        assertEquals("...whose lattice is the moon's own", home.luna.name().cellKey(), named.zone());
        assertEquals("re-addressing out of the galactic cell must not displace the craft", 0d,
                absoluteOf(home.reg, named).distanceTo(absoluteOf(home.reg, craft)), CONTINUITY_SLACK);
    }

    /**
     * A craft in the planet's own galactic cell that is in NO moon's sphere is left alone — the
     * planet's cell has no sphere of its own to carry it out of, so its boundary stays the cube.
     *
     * <p>The CONTROL for the scenario above: a re-address that fired for every craft in a planet's
     * cell would satisfy it exactly as well. The positive half is in this method (STEP 7), on the
     * same registry, so a null here cannot be "the universe could not be asked".</p>
     *
     * <p>red-witnessed: 2026-10-01, both halves. With {@code SpaceSubsystem#zoneMembershipIn} at
     * {@code return null; // in no child's sphere} made to address every such craft in the planet's
     * zone lattice instead, the null verdict fails with "expected null, but was:
     * &lt;GalacticCoord[zone=19_0_0@1849294, sector=(1,0,0), local=(397096,0,0)]&gt;" while the
     * entering scenario above stays green — which is why this control exists. With the old
     * {@code zone() == null} guard back, the positive half fails instead: "arrangement: this cell
     * must resolve".</p>
     */
    @Test
    public void aCraftInThePlanetsOwnCellOutsideEveryMoonsSphereIsLeftAlone() {
        Fixture home = arrangeEarthAndLuna();
        long lunaSphere = dev.stannismod.stellurgy.space.ZoneScale
                .realizedRadiusBlocks(home.luna, home.earth, TICK);
        assertTrue("arrangement: the moon must have a sphere", lunaSphere > 0L);
        assertNotNull("arrangement: this cell must resolve — a craft inside the moon's sphere is "
                        + "re-addressed",
                SpaceSubsystem.zoneMembershipIn(home.reg,
                        inEarthsGalacticCellNearLuna(home, lunaSphere / 2L), TICK));

        GalacticCoord craft = inEarthsGalacticCellNearLuna(home, lunaSphere * 3L / 2L);
        double fromLuna = absoluteOf(home.reg, craft).distanceTo(home.luna.absoluteAt(TICK));
        assertTrue("arrangement: the craft must be outside the moon's sphere (" + fromLuna
                + " blocks, sphere " + lunaSphere + ")", fromLuna > lunaSphere);
        assertNull("a craft in a planet's own cell and in no moon's sphere must be left where it is",
                SpaceSubsystem.zoneMembershipIn(home.reg, craft, TICK));
    }

    // ---- fixture ------------------------------------------------------------------------------

    /** The tick everything is evaluated at. Not zero: a fixture that only works at rest hides a frame. */
    private static final long TICK = 5_000L;

    /**
     * How much displacement a re-address may show before the claim "it did not move" is a lie, in
     * blocks — <b>and it is the rounding, not a budget</b>.
     *
     * <p>The address is integer blocks and the comparison runs through two frame origins, so a
     * faithful re-address can land one block out by rounding alone; nothing smaller is measurable
     * here. <b>The number it has to separate is measured, not guessed</b>: the defect this assertion
     * exists to catch displaces the craft by <b>321 993.76</b> blocks (see the red-witness on the
     * outward scenario), which is five orders of magnitude above this. There is no value between the
     * two that would make the verdict go either way, which is what makes the slack safe to state as
     * one block rather than argued down to zero.</p>
     */
    private static final double CONTINUITY_SLACK = 1d;

    private static final class Fixture {
        final UniverseRegistry reg;
        final SystemBody sol;
        final SystemBody earth;
        final SystemBody luna;

        Fixture(UniverseRegistry reg, SystemBody sol, SystemBody earth, SystemBody luna) {
            this.reg = reg;
            this.sol = sol;
            this.earth = earth;
            this.luna = luna;
        }
    }

    /**
     * Earth and Luna at their real bulk and separation, in a registry that has NAMED them.
     *
     * <p>The naming is the point: the lattice this test is about is the one the registry recorded
     * when it built these bodies, and a fixture that handed the bodies over without going through
     * the registry would be supplying the very answer under test.</p>
     */
    private static Fixture arrangeEarthAndLuna() {
        StellarBody star = new StellarBody();
        star.setId(STAR_ID);
        star.setName("Sol");
        star.setSize(1f);

        DimensionProperties earth = new DimensionProperties(EARTH_DIM);
        earth.orbitalDist =
                dev.stannismod.stellurgy.util.AstronomicalBodyHelper.DISTANCE_UNITS_PER_AU;
        earth.baseOrbitTheta = 0.0;
        earth.orbitTheta = 0.0;
        earth.orbitalPhi = 0;
        earth.setBulk(1d, 1d);

        DimensionProperties luna = new DimensionProperties(LUNA_DIM);
        luna.orbitalDist = dev.stannismod.stellurgy.util.AstronomicalBodyHelper.MOON_REFERENCE_UNITS;
        luna.baseOrbitTheta = 0.0;
        luna.orbitTheta = 0.0;
        luna.orbitalPhi = 0;
        luna.setBulk(0.0123d, 0.2727d);

        DimensionManager.getInstance().setDimProperties(EARTH_DIM, earth);
        DimensionManager.getInstance().setDimProperties(LUNA_DIM, luna);
        earth.setStar(star);
        luna.setParentPlanet(earth);

        UniverseRegistry reg = new UniverseRegistry();
        reg.place(GalacticCoord.ORIGIN, STAR_ID);
        UniverseRegistry.setStarLookup(id -> id == STAR_ID ? star : null);

        List<SystemBody> bodies = reg.systemBodiesAt(GalacticCoord.ORIGIN);
        SystemBody earthBody = bodyOf(bodies, EARTH_DIM);
        SystemBody lunaBody = bodyOf(bodies, LUNA_DIM);
        SystemBody solBody = null;
        for (SystemBody b : bodies) {
            if (b.kind() == dev.stannismod.stellurgy.universe.SystemBodyKind.STAR) {
                solBody = b;
                break;
            }
        }
        assertNotNull("the registry must produce the planet", earthBody);
        assertNotNull("the registry must produce the moon", lunaBody);
        assertNotNull("the registry must produce the star", solBody);
        return new Fixture(reg, solBody, earthBody, lunaBody);
    }

    /**
     * A craft {@code offsetBlocks} out along +X from the MOON, addressed inside the moon's own zone.
     *
     * <p>Luna has no children, so its zone is one cell spanning its whole sphere — that is what the
     * naming pass would produce and it is derived here rather than read off a name, because a
     * childless zone names nothing to read it from.</p>
     */
    private static GalacticCoord inLunasZone(Fixture home, long offsetBlocks) {
        long width = dev.stannismod.stellurgy.space.ZoneScale
                .cellBlocks(home.luna, home.earth, 0L, TICK);
        assertTrue("arrangement: the moon must have a zone to address a craft in", width > 0L);
        return dev.stannismod.stellurgy.space.ZoneScale.addressOnLattice(
                home.luna.name().cellKey(), width,
                dev.stannismod.stellurgy.space.BlockDelta.of(offsetBlocks, 0L, 0L));
    }

    /**
     * A craft {@code offsetBlocks} out along +X from the MOON, addressed inside EARTH's zone — the
     * address a craft flying in from the planet's neighbourhood holds.
     *
     * <p>The lattice is read off the MOON'S OWN NAME, which is where the naming pass recorded it.
     * Deriving it here instead would let the arrangement and the subject share a mistake.</p>
     */
    private static GalacticCoord inEarthsZoneNearLuna(Fixture home, long offsetBlocks) {
        long width = home.luna.name().cellBlocks();
        assertTrue("arrangement: the moon's name must carry its parent's lattice", width > 0L);
        dev.stannismod.stellurgy.space.BlockDelta fromEarth =
                home.luna.absoluteAt(TICK).minus(home.earth.absoluteAt(TICK));
        return dev.stannismod.stellurgy.space.ZoneScale.addressOnLattice(
                home.earth.name().cellKey(), width,
                dev.stannismod.stellurgy.space.BlockDelta.of(fromEarth.dx() + offsetBlocks,
                        fromEarth.dy(), fromEarth.dz()));
    }

    /**
     * A craft {@code offsetBlocks} out along +X from the MOON, addressed in EARTH'S OWN GALACTIC
     * cell — the address a craft fresh from the planet's surface holds. The offset is taken from the
     * cell's own frame origin, read through the registry, so the arrangement and the subject resolve
     * positions the same way and nothing here assumes where in its cell the planet stands.
     */
    private static GalacticCoord inEarthsGalacticCellNearLuna(Fixture home, long offsetBlocks) {
        GalacticCoord cell = home.earth.name().cellCentre();
        assertNull("arrangement: the planet must be named in the galactic lattice", cell.zone());
        dev.stannismod.stellurgy.space.BlockDelta fromOrigin =
                home.luna.absoluteAt(TICK).minus(home.reg.originAt(cell, TICK));
        long dx = fromOrigin.dx() + offsetBlocks;
        assertTrue("arrangement: the craft must stand inside the planet's cell, or the cube would "
                        + "decide before any sphere: " + dx,
                Math.abs(dx) < GalacticCoord.HALF_CELL && Math.abs(fromOrigin.dy()) < GalacticCoord.HALF_CELL
                        && Math.abs(fromOrigin.dz()) < GalacticCoord.HALF_CELL);
        return cell.withLocal(dx, fromOrigin.dy(), fromOrigin.dz());
    }

    /** Where an address actually is, resolved through the registry's own frames. */
    private static AbsolutePos absoluteOf(UniverseRegistry reg, GalacticCoord coord) {
        return reg.originAt(coord.cellCentre(), TICK)
                .plus(coord.localX(), coord.localY(), coord.localZ());
    }

    private static SystemBody bodyOf(List<SystemBody> bodies, int dimId) {
        for (SystemBody b : bodies) {
            if (b.dimId() == dimId) {
                return b;
            }
        }
        return null;
    }
}
