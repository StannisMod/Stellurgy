package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.space.CellSeam;
import dev.stannismod.stellurgy.space.GalacticCoord;
import dev.stannismod.stellurgy.test.EntrySlots;
import dev.stannismod.stellurgy.test.EntryStatus;
import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.Plot;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.OrbitLine;
import dev.stannismod.stellurgy.test.RocketFixture;
import dev.stannismod.stellurgy.test.ShipIdentity;
import dev.stannismod.stellurgy.test.ShipInfo;

import org.junit.After;
import org.junit.Test;

import static dev.stannismod.stellurgy.test.server.WorldCommandFixtures.awaitEnteredSpace;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The sphere seam on a live ship: a craft crossing a MOON's sphere of influence changes lattice
 * there — out into its parent's, in into the moon's own — named where it is and without moving, and
 * it stays.
 *
 * <p><b>This is a test of the seam's MECHANICS, not an end-to-end test.</b> The craft is put beside
 * the moon and moved across the sphere by probes, because flying out of Luna's sphere by hand is some
 * 250 000 blocks at the assist's 3 blocks a tick — over an hour — and the hysteresis bands are
 * positions no player aims for. The player's own path through the INWARD crossing is walked in
 * {@code M1PlanetToPlanetMilestoneE2ETest} (leg 7b: a jump beside the moon is carried into its zone,
 * seated, and keeps station there).</p>
 *
 * <p>Inside a zone the seam is a sphere, not the cube face {@code VSShipCellSeamTest} flies
 * through. The decision and the naming are pinned on the real solar arithmetic by
 * {@code ZoneCrossingAimsAtTheRightCellTest}; what nothing else shows is a REAL ship doing it: the
 * controller arming on the sphere for a craft its cube predicate calls "inside", the carry cutting and
 * pasting it into another slot world, and the ledger naming where it arrived.</p>
 *
 * <p><b>Every CARRY here is the craft's own.</b> The craft is moved across the sphere and the test
 * waits for its flight computer's tick to ask the controller and be granted the carry
 * ({@code carry_requested}, recorded where {@code CellCrossingController#requestCarry} returns, with
 * the pose it was decided on), then for the arrival production announces. So the trigger wiring inside
 * {@code TileAdvancedFlightComputer} is covered too. This class used to drive the carry itself through
 * {@code space seam-carry} on the premise that the computer had stopped ticking by the time the craft
 * was moved; that premise was false — every server world here ticks
 * ({@code MixinWorldServerAlwaysTicks}). Measured 2026-10-04: where the physics engine froze the craft
 * after each probe teleport ("Ship tried moving too fast"), no self-carry was seen before the test's
 * own; where it did not freeze, the computer carried the craft on the tick after the move, before the
 * test's read of it, and all three scenarios went red in their arrangement. Which of the two the
 * engine does is not this class's subject, so the class no longer depends on it.</p>
 *
 * <p>A "STAYS" verdict is still a READING — the controller's decision asked through
 * {@code space seam-carry}, which answers {@code wouldCarry} — because a carry that does not happen
 * publishes nothing to wait on. The craft's ledger row is checked first, so a computer that wrongly
 * carried it away is named at the verdict and not as a missing hull. Every arrival is on the side of
 * the sphere it STARTS from, so a wrong decision meets a control rather than the computer's tick in
 * the moment after a paste.</p>
 *
 * <p><b>The moon is Luna</b>, the one the default world ships beside the overworld, found through
 * the registry as the MOON whose zone is the launch planet's own cell — never by a literal key.</p>
 *
 * <p><b>How a craft is put beside it.</b> Through the real on-ramp into the launch cell, then
 * {@code space jump-key} to a cell that rides the moon, at a NAMED offset from it. It has to be
 * named: the launch cell's own offsets are the planet's distance from its galactic cell's centre,
 * fourteen million blocks, and carried over they aim the jump at a cell far outside the sphere.</p>
 *
 * <p><b>Cleanup (STEP 9)</b>: each scenario's craft ends in a slot world of its own that the other
 * scenario never enters, and the class boots its own server.</p>
 *
 * <p>Gated on the server's real VS presence (run with); skips cleanly otherwise.</p>
 */
public class VSShipZoneSphereCrossingTest extends AbstractSharedServerTest {

    /** How much WORLD an async crossing is allowed to settle in, in server ticks — thirty seconds. */
    private static final int SETTLE_TICKS = 600;


    /** Float noise on a commanded rate read back through a double, not a tolerance. */
    private static final double EXACTLY_ZERO = 1e-9;

    /**
     * Where INSIDE the sphere a craft stands, as a fraction of the radius. Half-way out: far inside
     * the inward threshold (which is {@code 1 - 1/1000} of it), and far outside the body itself,
     * whose radius is under three per cent of its sphere.
     */
    private static final double INSIDE_AT = 0.5d;

    /**
     * How far outside the sphere the craft is put to be carried OUT, as a fraction of its radius:
     * production's own INWARD threshold, mirrored. Ten times the carry margin, so the pose is well
     * clear of the decision boundary — the boundary itself is {@code CellSeamTest}'s question, on the
     * pure layer — and still a fraction of the radius, the unit the seam is written in.
     */
    private static final double OUTSIDE_BY = CellSeam.SPHERE_REENTRY_FRACTION;

    /**
     * Where the OUTWARD craft ARRIVES, as a fraction of the radius — deeper than where its control is
     * asked ({@link #INSIDE_AT}), and that difference is the point.
     *
     * <p>The craft's computer ticks for a moment after the jump's paste and asks the controller
     * itself (measured, see the class note). Arrived at the control's own spot, a controller that
     * wrongly carried a craft that deep would do it on that tick, and the test would go red in its
     * arrangement instead of at the control. A tenth of the radius is outside the moon's descent
     * shell and inside a sphere a quarter of the real one. The move to {@link #INSIDE_AT} is checked
     * against the ledger before the control is asked, so a computer that wrongly carries the craft
     * from there is named as such.</p>
     */
    private static final double ARRIVE_AT = 0.1d;

    /**
     * Where the INWARD craft ARRIVES, as a fraction of the moon's radius — for the same reason as
     * {@link #ARRIVE_AT}, from the outside: three radii is beyond even the sphere Luna gets when it
     * is measured against the STAR instead of its planet (638 428 blocks, 2.4 R — the shipped defect
     * that sphere-of-influence code has already had once), so a controller carrying it in would meet
     * the control at {@link #BESIDE_AT}, not the paste.
     */
    private static final double ARRIVE_FAR_AT = 3.0d;

    /**
     * Where a craft stands BETWEEN the spheres before it flies in, as a fraction of the moon's
     * radius: outside the moon's, and — at a few hundred thousand blocks from a moon a million and a
     * half from its planet — deep inside the planet's.
     */
    private static final double BESIDE_AT = 1.5d;

    /**
     * How far the carried address may sit from the pose the carry was decided on, in blocks — the
     * ROUNDING of an integer address taken through two frame origins, nothing more. The defect this
     * separates displaces the craft by 321 993.76 blocks (measured in
     * {@code ZoneCrossingAimsAtTheRightCellTest}), five orders of magnitude above this.
     */
    private static final double CONTINUITY_SLACK = 1d;

    /**
     * How far the arrived hull's reported pose may sit from the address it was placed at, in blocks
     * — physics, not rounding. The crossing teleports the hull exactly onto the address and then
     * RELEASES it to the physics ({@code ShipCrossingService.tick}: pose, re-seat, unpark), and the
     * hull is read several ticks later. Measured 2026-09-29 on this fixture: (1.39, -1.16, 0.50) from
     * an address of (264996, 0, 0) on most runs, identical to three decimals, and (0, 0, 0) on one. Not explained further here, and not the
     * crossing's to explain: the address itself is checked to the block. The defect this separates is
     * the same 321 993-block displacement.
     */
    private static final double SETTLE_SLACK = 4d;

    /** This class's own region of the overworld, clear of the other on-ramp classes' lanes. */
    private static final int SRC_X = 7600, SRC_Z = 7600;

    @Override
    protected Plot.Lane lane() {
        return new Plot.Lane(SRC_X, SRC_Z, Plot.SIZE);
    }

    /**
     * OUT: a craft deep inside Luna's zone is moved just outside the sphere and carried into Earth's
     * lattice — named by Luna's own cell there, at the offset it had — and is then judged to stay.
     *
     * <p>red-witnessed: 2026-10-04, on the form where the craft's own computer carries it, after two
     * healthy runs of the class (3/3 each). With {@code CellSeam#hasLeftZone} at
     * {@code return zoneRadiusBlocks > 0d} answering {@code false}, it fails at the first carry,
     * "production does not agree the craft has left the moon's sphere (264996 blocks out against a
     * radius of 264731): its flight computer was granted no carry, and the controller asked directly
     * answers {"started":false,"wouldCarry":false …}". With {@code TileAdvancedFlightComputer#update}
     * at {@code if (stack.cellCrossings.requestCarry(world.provider.getDimension(),} never asking, it
     * fails at the same carry with the controller answering {@code "wouldCarry":true} — the trigger,
     * told apart from the decision. With {@code CellSeam#hasEnteredZone} at
     * {@code return zoneRadiusBlocks > 0d} answering {@code false}, it fails at the carry back in,
     * "…has entered the moon's sphere (132365 blocks out …) … {"wouldCarry":false …}".</p>
     *
     * <p>The records below were taken 2026-09-29 on the earlier form, which drove the carry itself
     * through {@code space seam-carry}; the code they name is unchanged, the failure texts they quote
     * are that form's.</p>
     *
     * <p>red-witnessed: 2026-09-29, four inversions, one production line each, each restored, with the
     * method run healthy in the same arrangement first (and twice since).
     * (1) {@code CellSeam#hasLeftZone} at {@code return zoneRadiusBlocks > 0d} answering {@code false}: fails at the carry decision, "production
     * does not agree the craft has left the moon's sphere … {"started":false,"wouldCarry":false …}".
     * (2) the same method firing for any body with a sphere: fails in the ARRANGEMENT, reproducibly,
     * "the throttle could not be released — the craft's ledger row is now … @19_0_0.1_0_0 +132365,0,0"
     * — the computer's own tick right after the jump's paste carried out a craft at half the radius
     * before the control could be asked. That is why the craft now ARRIVES at a tenth of the radius
     * and is moved to the control's spot afterwards ({@code ARRIVE_AT}).
     * (2b) The CONTROL, witnessed in this class since: with {@code SpaceSubsystem#zoneMembershipIn} at {@code double zoneRadius = sphereRadiusOf(reg, zoneBody, zoneCell, tick)}
     * reading a quarter of the sphere, it fails with "a craft 132365.0 blocks from a moon whose sphere
     * is 264731 must be left where it is: {"started":true,"wouldCarry":true,"toCell":"19_0_0.1_0_0" …}".
     * (3) {@code SpaceSubsystem#addressIn} at {@code GalacticCoord cell = latticeAddress.cellCentre()} handing back the lattice address: fails with "the ledger no
     * longer names the cell the carry announced — the craft was carried again after it arrived …
     * @19_0_0.1_0_0.0_0_0 +-46699,0,1650": misplaced some 311 000 blocks back inside the sphere, it was
     * carried straight back in.
     * (4) {@code SpaceSubsystem#latticeOf} at {@code long named = reg == null ? GalacticCoord.WIDTH_UNKNOWN} ignoring the recorded width: fails at the naming verdict,
     * "expected:&lt;19_0_0.[1]_0_0&gt; but was:&lt;19_0_0.[0]_0_0&gt;" — named by EARTH's cell.
     * (5) {@code CellSeam#hasEnteredZone} at {@code && distanceBlocks < zoneRadiusBlocks * (1d - SPHERE_REENTRY_FRACTION)} entering at the sphere itself (no inward margin): fails at the
     * hysteresis band, "a craft back inside the moon's sphere but not past the inward threshold (the
     * hysteresis), 264598 blocks from a moon whose sphere is 264731 must be left where it is:
     * {"started":true,"wouldCarry":true,"toCell":"19_0_0.1_0_0.0_0_0" …}" — carried straight back in.
     * The inward scenario stays green on it.
     * (6) The POSITIVE half of that band check: with {@code CellSeam#hasEnteredZone} at {@code return zoneRadiusBlocks > 0d} answering
     * {@code false}, the band still reads "stays" and the method fails one step later, where the
     * craft is taken deeper: "production does not agree the craft has entered the moon's sphere …
     * {"started":false,"wouldCarry":false …}".</p>
     */
    @Test
    public void aCraftFlownOutOfAMoonsSphereIsCarriedIntoItsParentsLatticeAndStaysThere()
            throws Exception {
        Moon luna = arrangeACraftBesideTheMoon();
        String insideKey = GalacticCoord.inZone(luna.moonKey, luna.moonLattice, 0L, 0L, 0L,
                0L, 0L, 0L).cellKey();
        Placed arrived = jumpTo(luna, insideKey, (long) (luna.radius * ARRIVE_AT));
        // THE ARRANGEMENT IS ASSERTED: everything below is about the moon's sphere only if the craft
        // is named inside the moon's zone and stands inside the sphere.
        assertEquals("arrangement: the craft must be named inside the moon's own zone: "
                + arrived.ledger, luna.moonKey, GalacticCoord.fromCellKey(arrived.ledger.cellKey).zone());
        // Moved to where the CONTROL is asked only after arriving, so a wrong decision meets the
        // control and not the computer's own tick in the moment after the paste (see ARRIVE_AT).
        Placed deep = moveWithin(arrived, (long) (luna.radius * INSIDE_AT));
        assertTrue("arrangement: the craft must stand INSIDE the moon's sphere (" + deep.fromMoon
                + " against " + luna.radius + "): " + deep.pose.raw(), deep.fromMoon < luna.radius);

        // CONTROL: inside the sphere, the controller leaves the craft alone. Without this, a carry
        // armed for any craft in any zone satisfies every assertion below exactly as well.
        assertStays(deep.ledger.slotDim, luna.durableId, "a craft " + deep.fromMoon
                + " blocks from a moon whose sphere is " + luna.radius);

        Carried out = moveAndCarry(luna, deep,
                (long) Math.ceil(luna.radius * (1d + OUTSIDE_BY)), "left the moon's sphere");
        assertEquals("the carry left from the moon's zone this craft was put in: " + out.record,
                deep.ledger.cellKey, Events.text(out.record, "origin"));
        assertEquals("a craft carried out of a moon's sphere belongs to the moon's PARENT, and is "
                + "named in the parent's lattice: " + out.record, luna.planetKey,
                GalacticCoord.fromCellKey(out.cell()).zone());
        assertEquals("a craft that has only just left the moon is still where the moon is, so it is "
                + "named by the moon's OWN cell in that lattice: " + out.record, luna.moonKey,
                out.cell());
        assertRenamedNotMoved(out, luna.durableId);
        // THE HYSTERESIS, where it can bite: back INSIDE the sphere, but not as deep as the inward
        // threshold. Geometrically in the moon's influence again; by the hysteresis still the
        // planet's. The arrival pose itself (R·1.001) is outside the band and cannot tell a craft
        // that is held by the hysteresis from one that is simply not near the boundary.
        Placed band = moveIntoTheBandAndAssertStays(luna, out.ledger.slotDim,
                (long) Math.floor(luna.radius * (1d - CellSeam.SPHERE_REENTRY_FRACTION / 2d)),
                "a craft back inside the moon's sphere but not past the inward threshold "
                        + "(the hysteresis)");
        // ...and the POSITIVE half, in this method (STEP 7): deeper in, the same lattice DOES carry
        // it into the moon's zone. Without this, a dead inward crossing reads as the hysteresis.
        Carried back = moveAndCarry(luna, band, (long) (luna.radius * INSIDE_AT),
                "entered the moon's sphere");
        assertEquals("past the inward threshold the craft must be taken back into the moon's zone: "
                + back.record, luna.moonKey, GalacticCoord.fromCellKey(back.cell()).zone());
    }

    /**
     * IN: a craft in Earth's lattice, beside Luna but outside its sphere, is moved deep inside it and
     * carried into Luna's own zone at the offset it had — and is then judged to stay.
     *
     * <p>The other code path of the same seam: outward re-addresses against the GRANDPARENT, inward
     * against a child the controller has just found, so the one cannot vouch for the other.</p>
     *
     * <p>red-witnessed: 2026-10-04, on the form where the craft's own computer carries it, after two
     * healthy runs of the class. With {@code CellSeam#hasEnteredZone} at
     * {@code return zoneRadiusBlocks > 0d} answering {@code false}, it fails at the carry in,
     * "production does not agree the craft has entered the moon's sphere (132365 blocks out against a
     * radius of 264731): its flight computer was granted no carry, and the controller asked directly
     * answers {"started":false,"wouldCarry":false …}". With {@code TileAdvancedFlightComputer#update} at
     * {@code if (stack.cellCrossings.requestCarry(world.provider.getDimension(),} never asking, the
     * same carry fails with the controller answering {@code "wouldCarry":true}. With
     * {@code CellSeam#hasLeftZone} at {@code return zoneRadiusBlocks > 0d} answering {@code false}, it
     * fails at the carry back out, "…has left the moon's sphere (264996 blocks out …) …
     * {"wouldCarry":false …}". The records after this one were taken 2026-09-29 on the earlier form,
     * which drove the carry itself through {@code space seam-carry}; their failure texts are that
     * form's.</p>
     *
     * <p>red-witnessed: 2026-09-29, with {@code CellSeam#hasEnteredZone} at {@code return zoneRadiusBlocks > 0d} answering {@code false}:
     * fails at the carry decision, "production does not agree the craft has entered the moon's sphere
     * (… 132365 blocks out against a radius of 264731): {"started":false,"wouldCarry":false,
     * "fromCell":"19_0_0.1_0_0" …}", while the outward scenario stays green on that same inversion.
     * The naming and continuity verdicts are shared with the outward scenario through
     * {@code addressIn}, whose inversion is recorded there. And with {@code CellSeam#hasLeftZone} at {@code && distanceBlocks > zoneRadiusBlocks * (1d + SPHERE_CARRY_FRACTION)} leaving
     * at the sphere itself (no outward margin), it fails at the hysteresis band: "a craft back outside
     * the moon's sphere but not past the outward threshold (the hysteresis), 264745 blocks from a moon
     * whose sphere is 264731 must be left where it is: {"started":true,"wouldCarry":true …}" —
     * carried straight back out. The outward scenario stays green on it. And the CONTROL between the
     * spheres: with {@code SpaceSubsystem#zoneMembershipIn} at {@code double childRadius = ZoneScale.realizedRadiusBlocks(child, zoneBody, tick)} measuring the moon's sphere against the STAR
     * (the 638 428-block sphere, the shape of a defect this code has shipped once), it fails with "a
     * craft 397096.1095767623 blocks from a moon whose sphere is 264731 must be left where it is:
     * {"started":true,"wouldCarry":true …}". And the POSITIVE half of the band check: with
     * {@code CellSeam#hasLeftZone} at {@code return zoneRadiusBlocks > 0d} answering {@code false}, the band still reads "stays" and the method
     * fails one step later, where the craft is taken further out: "production does not agree the
     * craft has left the moon's sphere … {"started":false,"wouldCarry":false …}".</p>
     */
    @Test
    public void aCraftFlownIntoAMoonsSphereIsCarriedIntoTheMoonsZoneAndStaysThere()
            throws Exception {
        Moon luna = arrangeACraftBesideTheMoon();
        Placed far = jumpTo(luna, luna.moonKey, (long) (luna.radius * ARRIVE_FAR_AT));
        assertEquals("arrangement: the craft must be named by the moon's own cell in its PLANET's "
                + "lattice, or this is not the inward crossing: " + far.ledger, luna.moonKey,
                far.ledger.cellKey);
        // Moved to where the CONTROL is asked only after arriving — see ARRIVE_FAR_AT.
        Placed beside = moveWithin(far, (long) (luna.radius * BESIDE_AT));
        assertTrue("arrangement: the craft must stand OUTSIDE the moon's sphere (" + beside.fromMoon
                + " against " + luna.radius + "): " + beside.pose.raw(),
                beside.fromMoon > luna.radius);

        // CONTROL: between the spheres — outside the moon's, inside the planet's — the controller
        // leaves the craft alone, so the carry below is the moon's sphere acting and not a carry
        // that fires for anything near a moon.
        assertStays(beside.ledger.slotDim, luna.durableId, "a craft " + beside.fromMoon
                + " blocks from a moon whose sphere is " + luna.radius);

        Carried in = moveAndCarry(luna, beside, (long) (luna.radius * INSIDE_AT),
                "entered the moon's sphere");
        assertEquals("the carry left from the moon's own cell this craft was put in: " + in.record,
                luna.moonKey, Events.text(in.record, "origin"));
        assertEquals("a craft carried into a moon's sphere belongs to that MOON, and is named in its "
                + "own zone: " + in.record, luna.moonKey, GalacticCoord.fromCellKey(in.cell()).zone());
        assertRenamedNotMoved(in, luna.durableId);
        // THE HYSTERESIS, from this side: back OUTSIDE the sphere, but not as far as the outward
        // threshold. Geometrically out of the moon's influence; by the hysteresis still the moon's.
        Placed band = moveIntoTheBandAndAssertStays(luna, in.ledger.slotDim,
                (long) Math.ceil(luna.radius * (1d + CellSeam.SPHERE_CARRY_FRACTION / 2d)),
                "a craft back outside the moon's sphere but not past the outward threshold "
                        + "(the hysteresis)");
        // ...and the POSITIVE half, in this method (STEP 7): further out, the same zone DOES carry it
        // out to the planet's lattice. Without this, a dead outward crossing reads as the hysteresis.
        Carried back = moveAndCarry(luna, band, (long) Math.ceil(luna.radius * (1d + OUTSIDE_BY)),
                "left the moon's sphere");
        assertEquals("past the outward threshold the craft must be carried out to the planet's "
                + "lattice: " + back.record, luna.planetKey,
                GalacticCoord.fromCellKey(back.cell()).zone());
    }

    /**
     * How far the carried craft's new offset may sit from where the moon was READ to be, in blocks,
     * as a fraction of the sphere — the moon's own motion between that read and the carry decision,
     * nothing more.
     *
     * <p>In the planet's galactic cell the craft's pose is measured from the PLANET, and the moon
     * moves against it: 294 236 blocks over 20 000 ticks relative to Earth (measured in
     * {@code ParkedCraftKeepsStationTest}), about 15 blocks a tick. A handful of probe round trips lie
     * between reading the moon's position and the controller deciding, so the craft's offset from the
     * moon is known to tens of blocks, not to one. A hundredth of the sphere (~2 600 blocks for Luna)
     * covers well over a hundred ticks of that motion and still sits two orders below what it must
     * separate: a craft named on its in-cell offset from the wrong origin is displaced by the moon's
     * whole distance from its lattice slot (321 993 blocks) or by up to half a cell.</p>
     */
    private static final double MOON_MOTION_SLACK_OF_RADIUS = 0.01d;

    /**
     * IN, from the PLANET'S OWN cell: a craft fresh from the planet — addressed in the planet's
     * galactic cell, as every craft that enters space is — is moved inside the moon's sphere and
     * carried into the moon's zone at the position it had.
     *
     * <p>The two scenarios above start from a ZONED address, which only a jump hands out. This is the
     * address a pilot holds when he flies to the moon by hand, and it is decided by a different branch:
     * a galactic cell has no sphere of its own, so only the inward question is asked of it.</p>
     *
     * <p>Positions here are measured from the PLANET, not the moon (the planet's cell rides the
     * planet), so the moon's position in this cell is read from production
     * ({@code space zone-sphere} → {@code fromGalacticCell}) immediately before each move.</p>
     *
     * <p>red-witnessed: 2026-10-04, on the form where the craft's own computer carries it, after two
     * healthy runs of the class. With {@code CellSeam#hasEnteredZone} at
     * {@code return zoneRadiusBlocks > 0d} answering {@code false}, it fails at the carry, "production
     * does not agree the craft has entered the moon's sphere from the planet's own cell (moved
     * 132365.5 blocks from a moon whose sphere is 264731): its flight computer was granted no carry,
     * and the controller asked directly answers {"started":false,"wouldCarry":false,
     * "fromCell":"19_0_0" …}". With {@code TileAdvancedFlightComputer#update} at
     * {@code if (stack.cellCrossings.requestCarry(world.provider.getDimension(),} never asking, the same
     * carry fails with the controller answering {@code "wouldCarry":true}. With
     * {@code CellSeam#hasLeftZone} at {@code return zoneRadiusBlocks > 0d} answering {@code false} it
     * stays green, as it must: nothing here leaves a sphere. The record after this one was taken on
     * the earlier form, which drove the carry through {@code space seam-carry}.</p>
     *
     * <p>red-witnessed: 2026-10-01, after a healthy run of the class (3/3), with
     * {@code SpaceSubsystem#zoneMembershipIn} at {@code if (craftCoord == null || craftCoord.cellBlocks() <= 0L)}
     * put back to refusing any craft whose {@code zone()} is null, this fails at the carry decision:
     * "production does not agree a craft in the planet's own cell has entered the moon's sphere
     * (132366.0131491464 blocks out against 264731): {"started":false,"wouldCarry":false,
     * "fromCell":"19_0_0" …}". <b>The CONTROL's null is not witnessable here</b>: with
     * {@code SpaceSubsystem#zoneMembershipIn} at {@code return null; // in no child's sphere} made to
     * re-address every craft in the planet's cell, the craft's own computer carries it in the moment
     * after the entry paste (the class note) and the method fails in its ARRANGEMENT — "the throttle
     * could not be released — the craft's ledger row is now … @19_0_0.-7_0_-2" — before the control
     * is asked. That null was witnessed on the same inversion by the integration-tier zone-crossing
     * test's planet's-own-cell, outside-every-moon's-sphere case, which was removed on 2026-10-02 with
     * the fast-tier tests that needed a running server; nothing in the tree witnesses it now.</p>
     */
    @Test
    public void aCraftFreshFromThePlanetFlownIntoItsMoonsSphereIsCarriedIntoTheMoonsZone()
            throws Exception {
        Moon luna = arrangeACraftBesideTheMoon();
        EntryStatus launched = EntryStatus.forShip(this::exec, luna.durableId).requireFound(
                "the craft entered space, so the ledger must hold its row");
        assertEquals("arrangement: a craft fresh from the planet must be named by the planet's own "
                + "cell: " + launched, luna.planetKey, launched.cellKey);
        assertEquals("arrangement: ...which is a GALACTIC cell, or this is not the branch under test: "
                + launched, null, GalacticCoord.fromCellKey(launched.cellKey).zone());
        int slot = launched.slotDim;
        String vsId = ShipIdentity.physicsIdOf(this::exec, slot, luna.durableId);
        stopTheCraft(slot, vsId, luna.durableId);

        // CONTROL: beside the moon, outside its sphere, the controller leaves the craft alone. Without
        // this, a carry armed for every craft in a planet's cell satisfies every assertion below.
        double[] besideMoon = moonInPlanetCell(luna);
        long[] besidePose = inCell(besideMoon[0] + luna.radius * BESIDE_AT, besideMoon[1],
                besideMoon[2]);
        moveTo(slot, vsId, besidePose[0], besidePose[1], besidePose[2]);
        assertNotCarriedAway(launched, "moved beside the moon, outside its sphere");
        release(slot, vsId);
        ShipInfo besideHull = ShipInfo.byId(this::exec, slot, vsId);
        assertEquals("arrangement: the craft is not where it was moved to: " + besideHull.raw(),
                besidePose[0], besideHull.x, CONTINUITY_SLACK);
        double beside = distance(new double[] {besideHull.x, besideHull.y, besideHull.z},
                moonInPlanetCell(luna));
        assertTrue("arrangement: the craft must stand OUTSIDE the moon's sphere (" + beside
                + " against " + luna.radius + ")", beside > luna.radius);
        assertStays(slot, luna.durableId, "a craft in the planet's own cell " + beside
                + " blocks from a moon whose sphere is " + luna.radius);

        double[] moonAtMove = moonInPlanetCell(luna);
        long[] insidePose = inCell(moonAtMove[0] + luna.radius * INSIDE_AT, moonAtMove[1],
                moonAtMove[2]);
        // Marked BEFORE the move: the craft's own computer decides on the tick after it.
        long carryMark = events.mark();
        moveTo(slot, vsId, insidePose[0], insidePose[1], insidePose[2]);
        String granted = awaitOwnCarry(carryMark, slot, luna.durableId, "entered the moon's sphere "
                + "from the planet's own cell (moved " + luna.radius * INSIDE_AT + " blocks from a "
                + "moon whose sphere is " + luna.radius + ")");
        double[] decidedOn = decidedOn(granted);
        // Read right after the grant: the moon moves ~15 blocks a tick in this cell, and the slack
        // below is sized against that motion (MOON_MOTION_SLACK_OF_RADIUS).
        double[] moonAtCarry = moonInPlanetCell(luna);
        double inside = distance(decidedOn, moonAtCarry);
        assertTrue("arrangement: the carry must have been decided on a craft inside the moon's entry "
                        + "threshold (" + inside + " against "
                        + luna.radius * (1d - CellSeam.SPHERE_REENTRY_FRACTION) + "): " + granted,
                inside < luna.radius * (1d - CellSeam.SPHERE_REENTRY_FRACTION));

        Arrival arrival = arrivalSince(carryMark, luna.durableId, granted);
        String record = arrival.record;
        assertEquals("the carry left from the planet's own cell: " + record, luna.planetKey,
                Events.text(record, "origin"));
        String destination = Events.text(record, "destination");
        assertEquals("a craft in the planet's cell that flew into a moon's sphere belongs to that "
                + "MOON, and is named in its own zone: " + record, luna.moonKey,
                GalacticCoord.fromCellKey(destination).zone());
        EntryStatus arrived = arrival.ledger;

        // RENAMED, NOT MOVED: in the moon's zone the offset is measured from the MOON, so it must be
        // the pose the carry was decided on less where the moon stood.
        double slack = luna.radius * MOON_MOTION_SLACK_OF_RADIUS;
        assertEquals("the carry displaced the craft along X — it must only be renamed: " + arrived,
                decidedOn[0] - moonAtCarry[0], arrived.lx, slack);
        assertEquals("...along Y: " + arrived, decidedOn[1] - moonAtCarry[1], arrived.ly, slack);
        assertEquals("...along Z: " + arrived, decidedOn[2] - moonAtCarry[2], arrived.lz, slack);
        ShipInfo hull = ShipInfo.byId(this::exec, arrived.slotDim,
                ShipIdentity.physicsIdOf(this::exec, arrived.slotDim, luna.durableId));
        assertEquals("the arrived ship is not where its own ledger row puts it, along X: "
                + hull.raw() + " vs " + arrived, arrived.lx, hull.x, SETTLE_SLACK);
        assertEquals("...along Y: " + hull.raw() + " vs " + arrived, arrived.ly, hull.y, SETTLE_SLACK);
        assertEquals("...along Z: " + hull.raw() + " vs " + arrived, arrived.lz, hull.z, SETTLE_SLACK);
    }

    /**
     * Where the moon stands right now as a pose in the PLANET's galactic cell — production's reading
     * ({@code space zone-sphere} → {@code fromGalacticCell}), asserted to be about that cell.
     */
    private double[] moonInPlanetCell(Moon moon) throws Exception {
        Reply sphere = Reply.of(exec("stellurgytest space zone-sphere " + moon.moonKey));
        assertTrue("arrangement: production reads no sphere for the moon " + moon.moonKey + ": "
                + sphere, sphere.bool("found"));
        assertEquals("arrangement: the moon's position must be read in the planet's own cell: "
                + sphere, moon.planetKey, sphere.text("galacticCell"));
        return new double[] {sphere.arrayNumber("fromGalacticCell", 0),
                sphere.arrayNumber("fromGalacticCell", 1), sphere.arrayNumber("fromGalacticCell", 2)};
    }

    /** A pose in the craft's cell, asserted to stay inside it, as the block pose a move is given. */
    private static long[] inCell(double x, double y, double z) {
        assertTrue("arrangement: the craft must stay inside its cell, or the cube decides before any "
                        + "sphere: (" + x + "," + y + "," + z + ")",
                Math.abs(x) < GalacticCoord.HALF_CELL - CellSeam.REENTRY_DEPTH
                        && Math.abs(y) < GalacticCoord.HALF_CELL - CellSeam.REENTRY_DEPTH
                        && Math.abs(z) < GalacticCoord.HALF_CELL - CellSeam.REENTRY_DEPTH);
        return new long[] {(long) x, (long) y, (long) z};
    }

    private static double distance(double[] a, double[] b) {
        double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    // ---- the arrangement both scenarios share -----------------------------------------------------

    /** The moon a scenario flies to, and the craft it built to fly there. */
    private static final class Moon {
        final String durableId;
        final int launchSlot;
        final String planetKey;
        final String moonKey;
        final long radius;
        final long moonLattice;

        Moon(String durableId, int launchSlot, String planetKey, String moonKey, long radius,
             long moonLattice) {
            this.durableId = durableId;
            this.launchSlot = launchSlot;
            this.planetKey = planetKey;
            this.moonKey = moonKey;
            this.radius = radius;
            this.moonLattice = moonLattice;
        }
    }

    /** Where a jump left the craft: its ledger row, its physics id there, and its pose. */
    private static final class Placed {
        final EntryStatus ledger;
        final String vsId;
        final ShipInfo pose;
        /** Distance from the moon — the cells here ride it, so a world pose IS an offset from it. */
        final double fromMoon;

        Placed(EntryStatus ledger, String vsId, ShipInfo pose) {
            this.ledger = ledger;
            this.vsId = vsId;
            this.pose = pose;
            this.fromMoon = Math.sqrt(pose.x * pose.x + pose.y * pose.y + pose.z * pose.z);
        }
    }

    /** Where a carry arrived: the arrival record, and the ledger row after it. */
    private static final class Arrival {
        final String record;
        final EntryStatus ledger;

        Arrival(String record, EntryStatus ledger) {
            this.record = record;
            this.ledger = ledger;
        }
    }

    /**
     * What one carry did: the pose it was decided on, the grant that decided it, the arrival record,
     * and the ledger after it.
     */
    private static final class Carried {
        final double[] decidedOn;
        final String granted;
        final String record;
        final EntryStatus ledger;

        Carried(double[] decidedOn, String granted, Arrival arrival) {
            this.decidedOn = decidedOn;
            this.granted = granted;
            this.record = arrival.record;
            this.ledger = arrival.ledger;
        }

        String cell() {
            return Events.text(record, "destination");
        }
    }

    /** A craft built here and flown into space through the real on-ramp, and the moon it will visit. */
    private Moon arrangeACraftBesideTheMoon() throws Exception {
        String setup = exec("stellurgytest space entry-setup 2");
        assertTrue("entry setup failed: " + setup, Reply.of(setup).ok());
        int[] pad = RocketFixture.placeAt(site(), this::exec, "with-pilot-seat", 4, 12,
                "the craft this scenario builds stands in this volume");
        String asm = exec("stellurgytest rocket assemble 0 " + pad[0] + " " + pad[1] + " " + pad[2]);
        assertEquals("with VS an AFC-bearing build must route to a ship (no rocket): " + asm,
                0, Reply.of(asm).integer("rocketCount"));
        String durableId = ShipIdentity.nameFromAssembly(asm);
        String padVsId = ShipIdentity.physicsIdOf(this::exec, 0, durableId);
        ShipInfo onPad = ShipInfo.byId(this::exec, 0, padVsId);

        String held = exec("stellurgytest vs ff-input-by-id 0 " + padVsId + " 0 1 0 0 0 0");
        assertTrue("the held input must reach this ship's flight computer: " + held,
                Reply.of(held).bool("afcResolved"));
        assertTrue("climb teleport failed", Reply.of(exec("stellurgytest vs teleport-ship-by-id 0 "
                + padVsId + " " + (int) onPad.x + " " + OrbitLine.of(this::exec, 0).aboveEntryCeiling() + " " + (int) onPad.z)).ok());
        // Marked BEFORE the unpark, which is what lets the entry start: it is announced once.
        long entryMark = events.mark();
        exec("stellurgytest vs unpark-by-id 0 " + padVsId);
        awaitEnteredSpace(events, entryMark, durableId,
                "the flight-computer tick must carry this craft out of the atmosphere", SETTLE_TICKS,
                () -> EntrySlots.loadAll(this::exec, setup));
        EntryStatus launched = EntryStatus.forShip(this::exec, durableId).requireFound(
                "the entry was announced, so the ledger must hold this craft's row");

        // The launch planet is the body whose dimension the craft left (0); its moon is the MOON
        // whose zone is that planet's own cell. A moon's name is a path containing its parent's, so
        // this is a lookup, not a proximity guess.
        Reply system = Reply.of(exec("stellurgytest space cell-info " + launched.cellKey)).requireOk(
                "cell-info of the launch cell");
        String planetKey = null;
        String moonKey = null;
        for (String body : system.objectArray("bodies")) {
            Reply b = Reply.of(body);
            // Refusing reads on purpose: the producer always writes `dim`, `kind` and `cell` for
            // every body (`cell-info`'s one body writer), so a missing field is a broken producer.
            if (b.integer("dim") == 0) {
                planetKey = b.text("cell");
            }
        }
        assertNotNull("arrangement: the launch cell's system holds no body for dimension 0: "
                + system, planetKey);
        for (String body : system.objectArray("bodies")) {
            Reply b = Reply.of(body);
            // As above: the producer always writes `kind` and `cell`.
            if ("MOON".equals(b.text("kind"))
                    && planetKey.equals(GalacticCoord.fromCellKey(b.text("cell")).zone())) {
                assertTrue("arrangement: the launch planet has more than one moon, so which one this "
                        + "scenario flies to would be an iteration order: " + system, moonKey == null);
                moonKey = b.text("cell");
            }
        }
        assertNotNull("arrangement: the launch planet " + planetKey + " has no moon in its zone: "
                + system, moonKey);

        Reply sphere = Reply.of(exec("stellurgytest space zone-sphere " + moonKey));
        assertTrue("arrangement: production reads no sphere for the moon " + moonKey + ": " + sphere,
                sphere.bool("found"));
        long radius = sphere.longInteger("radius");
        assertTrue("arrangement: the moon must have a sphere to cross: " + sphere, radius > 0L);
        return new Moon(durableId, launched.slotDim, planetKey, moonKey, radius,
                sphere.longInteger("latticeBlocks"));
    }

    /**
     * Jump the craft to {@code cellKey}, a cell that rides the moon, at {@code offsetX} blocks from
     * it along +X, and bring it to rest there.
     */
    private Placed jumpTo(Moon moon, String cellKey, long offsetX) throws Exception {
        long jumpMark = events.mark();
        Reply jump = Reply.of(exec("stellurgytest space jump-key id " + moon.durableId + " " + cellKey
                + " " + moon.launchSlot + " 5000000 " + offsetX + " 0 0"))
                .requireOk("jump-key to " + cellKey);
        assertTrue("the jump to " + cellKey + " did not begin: " + jump, jump.bool("began"));
        events.awaitRecordWithFields(jumpMark, "ship_transit_ended",
                "the craft must arrive in the cell it was aimed at; " + jump, SETTLE_TICKS,
                "ship", moon.durableId, "destination", jump.text("toCell"));
        EntryStatus ledger = EntryStatus.forShip(this::exec, moon.durableId).requireFound(
                "the jump was announced, so the ledger must hold this craft's row");
        String vsId = ShipIdentity.physicsIdOf(this::exec, ledger.slotDim, moon.durableId);
        stopTheCraft(ledger.slotDim, vsId, moon.durableId);
        return new Placed(ledger, vsId, ShipInfo.byId(this::exec, ledger.slotDim, vsId));
    }

    /** Move a placed craft along X to {@code toX} from the moon, within its cell, and prove it moved. */
    private Placed moveWithin(Placed from, long toX) throws Exception {
        return moveWithin(from, toX, (long) from.pose.z);
    }

    /** The same, also putting the craft at {@code toZ} across the axis. */
    private Placed moveWithin(Placed from, long toX, long toZ) throws Exception {
        int slot = from.ledger.slotDim;
        moveTo(slot, from.vsId, toX, (long) from.pose.y, toZ);
        assertNotCarriedAway(from.ledger, "moved to " + toX + " from the moon");
        release(slot, from.vsId);
        ShipInfo moved = ShipInfo.byId(this::exec, slot, from.vsId);
        assertEquals("arrangement: the craft is not where it was moved to: " + moved.raw(),
                toX, moved.x, CONTINUITY_SLACK);
        return new Placed(from.ledger, from.vsId, moved);
    }

    /**
     * Move the craft along X to {@code toX} from the moon (Y and Z kept) and wait for its OWN flight
     * computer to be granted the carry, then for the arrival. The arrangement is asserted on the pose
     * the carry was decided on — the craft is judged where it was moved — and the verdict that it has
     * {@code crossed} is the grant itself.
     */
    private Carried moveAndCarry(Moon moon, Placed from, long toX, String crossed) throws Exception {
        int slot = from.ledger.slotDim;
        // Marked BEFORE the move: the computer decides on the tick after it, and a mark taken
        // afterwards can miss that decision.
        long carryMark = events.mark();
        moveTo(slot, from.vsId, toX, (long) from.pose.y, (long) from.pose.z);
        String granted = awaitOwnCarry(carryMark, slot, moon.durableId, crossed + " (" + toX
                + " blocks out against a radius of " + moon.radius + ")");
        double[] decidedOn = decidedOn(granted);
        assertEquals("arrangement: the carry was decided on a pose other than the one the craft was "
                + "moved to: " + granted, toX, decidedOn[0], CONTINUITY_SLACK);
        return new Carried(decidedOn, granted, arrivalSince(carryMark, moon.durableId, granted));
    }

    /**
     * Put the craft at a pose in its slot world. Nothing is read back and nothing else is done: the
     * craft's own computer acts on the very next tick, before even a release to the physics reaches
     * it — measured 2026-10-04, an {@code unpark-by-id} sent right after the move found no such ship
     * in the slot, three scenarios out of three — so anything taken here races it.
     */
    private void moveTo(int slot, String vsId, long x, long y, long z) throws Exception {
        assertTrue("the move within the cell failed", Reply.of(exec("stellurgytest vs "
                + "teleport-ship-by-id " + slot + " " + vsId + " " + x + " " + y + " " + z)).ok());
    }

    /** Release a craft that was moved and LEFT where it is to the physics again. */
    private void release(int slot, String vsId) throws Exception {
        assertTrue("the moved craft could not be released to the physics — it is not in its slot",
                Reply.of(exec("stellurgytest vs unpark-by-id " + slot + " " + vsId)).ok());
    }

    /**
     * The craft's ledger row still names the cell it was in: its own computer did not carry it away
     * from a pose it must be left at. Read BEFORE the hull, so a wrong carry is named here and not as
     * a hull missing from the slot it was moved in.
     */
    private void assertNotCarriedAway(EntryStatus before, String where) throws Exception {
        EntryStatus now = EntryStatus.forShip(this::exec, before.shipId).requireFound(
                "the craft just moved within its cell must still have its ledger row");
        assertEquals("the craft's own flight computer carried it out of " + before.cellKey + " when "
                + where + ", a pose it must be left at: " + now, before.cellKey, now.cellKey);
    }

    /**
     * The craft's own flight computer GRANTED a carry since {@code mark} — the record written where
     * {@code CellCrossingController#requestCarry} returns. When none comes, the controller is asked
     * the same question directly, so the failure says which half is wrong: a decision of "stays" is
     * the controller's, a decision of "carry" that the computer never acted on is the trigger's.
     */
    private String awaitOwnCarry(long mark, int slot, String durableId, String crossed)
            throws Exception {
        try {
            return events.awaitRecordWithFields(mark, "carry_requested", "the craft's flight computer "
                            + "must be granted a carry once it has " + crossed, SETTLE_TICKS,
                    "ship", durableId, "granted", "true");
        } catch (AssertionError noGrant) {
            Reply asked = Reply.of(exec("stellurgytest space seam-carry " + slot + " id " + durableId));
            throw new AssertionError("production does not agree the craft has " + crossed + ": its "
                    + "flight computer was granted no carry, and the controller asked directly answers "
                    + asked + " — wouldCarry:false is the decision, wouldCarry:true is a trigger that "
                    + "never asked", noGrant);
        }
    }

    /** The pose a granted carry was decided on, as {@code carry_requested} recorded it. */
    private static double[] decidedOn(String granted) {
        double[] pose = {Events.number(granted, "px"), Events.number(granted, "py"),
                Events.number(granted, "pz")};
        assertFalse("the grant names no pose it was decided on, so nothing below can compare against "
                + "it: " + granted, Double.isNaN(pose[0]) || Double.isNaN(pose[1])
                || Double.isNaN(pose[2]));
        return pose;
    }

    /**
     * The arrival of this craft since {@code mark}, and its ledger row after it — asserted to still
     * name the cell the arrival announced.
     */
    private Arrival arrivalSince(long mark, String durableId, String granted) throws Exception {
        // The FIRST arrival of this craft since the mark is this carry's; a later one would be a
        // second carry, which the ledger check below exists to catch.
        String record = Events.recordsWhere(events.awaitField(mark, "ship_entered_cell", "ship",
                durableId, "the carry was granted (" + granted + "), so the craft must settle where "
                        + "it was aimed", SETTLE_TICKS), "ship", durableId).get(0);
        EntryStatus ledger = EntryStatus.forShip(this::exec, durableId).requireFound(
                "the carry was announced, so the ledger must hold this craft's row");
        assertEquals("the ledger no longer names the cell the carry announced — the craft was carried "
                + "again after it arrived: " + ledger, Events.text(record, "destination"),
                ledger.cellKey);
        return new Arrival(record, ledger);
    }

    /**
     * The name changed and the craft did not move: both cells ride the moon, so the offsets it is
     * named at are the pose the carry was decided on — and the hull is physically there.
     */
    private void assertRenamedNotMoved(Carried c, String durableId) throws Exception {
        double[] decidedOn = c.decidedOn;
        assertEquals("the carry moved the craft along X — it was renamed, and must not have been "
                + "displaced: " + c.ledger, decidedOn[0], c.ledger.lx, CONTINUITY_SLACK);
        assertEquals("...along Y: " + c.ledger, decidedOn[1], c.ledger.ly, CONTINUITY_SLACK);
        assertEquals("...along Z: " + c.ledger, decidedOn[2], c.ledger.lz, CONTINUITY_SLACK);

        // A ledger row written by the carry itself says only what the carry believes.
        ShipInfo arrived = ShipInfo.byId(this::exec, c.ledger.slotDim,
                ShipIdentity.physicsIdOf(this::exec, c.ledger.slotDim, durableId));
        System.out.println("[zone-sphere] decided on " + java.util.Arrays.toString(decidedOn)
                + " | named " + c.ledger.cellKey + " at (" + c.ledger.lx + "," + c.ledger.ly + ","
                + c.ledger.lz + ") | arrived pose (" + arrived.x + "," + arrived.y + "," + arrived.z
                + ")");
        assertEquals("the arrived ship is not where its own ledger row puts it, along X: "
                + arrived.raw() + " vs " + c.ledger, c.ledger.lx, arrived.x, SETTLE_SLACK);
        assertEquals("...along Y: " + arrived.raw() + " vs " + c.ledger, c.ledger.ly, arrived.y,
                SETTLE_SLACK);
        assertEquals("...along Z: " + arrived.raw() + " vs " + c.ledger, c.ledger.lz, arrived.z,
                SETTLE_SLACK);
    }

    /**
     * Move the just-carried craft into the hysteresis band and assert the controller leaves it
     * there. Returns where it stands, so the caller can assert the POSITIVE half in the same method
     * (STEP 7): a "stays" is only the hysteresis if the same crossing, further on, does fire.
     */
    private Placed moveIntoTheBandAndAssertStays(Moon moon, int slot, long toX, String craft)
            throws Exception {
        String vsId = ShipIdentity.physicsIdOf(this::exec, slot, moon.durableId);
        EntryStatus ledger = EntryStatus.forShip(this::exec, moon.durableId).requireFound(
                "the carried craft must still have its ledger row");
        // ON the axis: the arrival ring stands the craft off the moon by twice its descent shell (for
        // Luna ~14 000 blocks, at a bearing drawn from the ship's id), and the band is only a few
        // hundred blocks deep on its outer side, so an X placed with that Z kept misses it.
        Placed band = moveWithin(new Placed(ledger, vsId, ShipInfo.byId(this::exec, slot, vsId)), toX, 0L);
        // And the DISTANCE is still what is asserted, not the X: Y is kept as the craft came.
        assertTrue("arrangement: the craft must stand INSIDE the hysteresis band ("
                        + moon.radius * (1d - CellSeam.SPHERE_REENTRY_FRACTION) + " .. "
                        + moon.radius * (1d + CellSeam.SPHERE_CARRY_FRACTION) + "), it is "
                        + band.fromMoon + " from the moon: " + band.pose.raw(),
                band.fromMoon > moon.radius * (1d - CellSeam.SPHERE_REENTRY_FRACTION)
                        && band.fromMoon < moon.radius * (1d + CellSeam.SPHERE_CARRY_FRACTION));
        assertStays(slot, moon.durableId, craft + ", " + band.fromMoon + " blocks from a moon whose "
                + "sphere is " + moon.radius);
        return band;
    }

    /**
     * The controller's decision on the craft in {@code slot} is "stays". Asked, not waited for: a
     * carry that does not happen publishes nothing, so a quiet window would pin nothing; the decision
     * is a reading. A refusal carries a {@code reason} and decided nothing, so it is told
     * apart first.
     */
    private void assertStays(int slot, String durableId, String craft) throws Exception {
        Reply decision = Reply.of(exec("stellurgytest space seam-carry " + slot + " id " + durableId));
        assertFalse("the craft was refused rather than judged, so nothing was asked: " + decision,
                decision.has("reason"));
        assertFalse(craft + " must be left where it is: " + decision, decision.bool("wouldCarry"));
    }

    /**
     * Take the throttle off and zero the cruise, asserted on the computer's read-back. The on-ramp
     * held full up to get past the ceiling; a craft still under thrust drifts between the pose it is
     * put at and the pose the carry decides on.
     */
    private void stopTheCraft(int slot, String vsId, String durableId) throws Exception {
        // The ledger row rides along on failure because the likeliest reason there is no computer to
        // talk to is that the craft is no longer in this slot: its computer ticks for a moment after
        // a paste, and a controller that arms for a craft it should leave alone carries it right
        // then — before this test's control can ask it anything.
        assertTrue("the throttle could not be released — the craft's ledger row is now "
                        + EntryStatus.forShip(this::exec, durableId),
                Reply.of(exec("stellurgytest vs ff-input-by-id " + slot + " " + vsId
                        + " 0 0 0 0 0 0")).bool("afcResolved"));
        Reply stopped = Reply.of(exec("stellurgytest vs ff-cruise-by-id " + slot + " " + vsId
                + " 0 0 0"));
        assertTrue("the cruise could not be commanded: " + stopped, stopped.bool("afcResolved"));
        assertTrue("the computer still holds a cruise after being told to stop: " + stopped,
                Math.abs(stopped.number("cruiseForward")) < EXACTLY_ZERO
                        && Math.abs(stopped.number("cruiseRight")) < EXACTLY_ZERO
                        && Math.abs(stopped.number("cruiseUp")) < EXACTLY_ZERO);
    }

    @After
    public void cleanup() throws Exception {
        exec("stellurgytest space entry-clear");
    }

    /** This tier's reader of the server's ordered event log. */
    private final Events events =
            new Events(this::exec, ticks -> GameTicks.advanceWorld(client(), 0, ticks), evictionReports());
}
