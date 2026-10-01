package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.Reply;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static dev.stannismod.stellurgy.test.server.WorldCommandFixtures.arrange;
import static dev.stannismod.stellurgy.test.server.WorldCommandFixtures.ask;

/**
 * What the OUTSIDE does to a ship's heat: the incident flux, and the shield that thins it.
 *
 * <p>Three clauses, independent of one another. That every outside source arrives through ONE term —
 * shown by putting the same rig in two physically different environments and requiring its response to
 * track the one number both of them report. That where more arrives than a cell can shed the net runs
 * BACKWARDS and the ship heats regardless of its own temperature — with the control that a loop which
 * built no radiators does not heat at all, because a radiating cell is the ship's coupling to the
 * outside in both directions. And that a shield thins the flux and never removes it, asked with the
 * configuration demanding every last percent.</p>
 *
 * <p><b>Most of this happens in a real space cell</b>, materialized from the overworld's own system,
 * because that is where the mechanic lives: on a world a coolant loop cannot be driven backwards at
 * all — the environment there is the world's own temperature, which is by construction below what the
 * loop sits at. A test that arranged the backwards case on a planet would be arranging something the
 * game cannot do.</p>
 *
 * <p>The star's STRENGTH is a setting and is turned up rather than flown to. How bright a star is
 * belongs to the universe layer; what a ship does with what arrives is this one, and the arrival path
 * itself — a real star, at its real distance, through the production registry — is what the space leg
 * of the first scenario exercises and measures.</p>
 */
public class HeatEnvironmentTest extends AbstractSharedServerTest {

    /** The row every rig stands on, in open air so a cell facing up has nothing over it but sky;
     *  from this scenario's own site (see {@link #stand} and {@link #standInCell}). */
    private int y;
    private int z;

    /** A second loop stands this far along from the first, clear of it. */
    private static final int CONTROL_OFFSET = 10;
    /** The shielded loop's radiator stands just past the emitter, inside the shield. */
    private static final int SHIELDED_OFFSET = 3;
    /** The unshielded loop stands twenty blocks from the generator, outside the shield. */
    private static final int UNSHIELDED_OFFSET = 20;
    /** Out from the site's footprint far enough to hold the unshielded loop's far end. */
    private static final int HALO = 18;

    /**
     * How far the world-vs-space difference may sit from the reported flux difference, and it is
     * nothing but the readouts' own quantisation. Each leg's net is {@code (long) ((gross - incident)
     * * cells)} ({@code HeatNetwork.rejectHeat}): a truncation toward zero worth less than one unit,
     * and the two legs can truncate in opposite directions when one net is positive and the other
     * negative — under two units between them. Each leg's flux is {@code Math.round(flux * 1000)},
     * half a milli-unit, twice. The gross term is the same in both legs (same capacity, same charge,
     * so the same temperature) and cancels.
     */
    private static final double QUANTISATION_BOUND = 2.0D + 2 * 0.0005D;

    /** Ask for this scenario's site on the world, prove it empty, and answer where the rig starts. */
    private int stand(String what) throws Exception {
        FixtureSite site = clearedSite(HALO, 3, what);
        y = site.y + 1;
        z = site.z;
        return site.x;
    }

    /**
     * The same allocation, proved empty in the space cell's OWN world rather than the overworld —
     * the cell is where these rigs are built, and a slot world is shared by every scenario here.
     */
    private int standInCell(Cell cell, String what) throws Exception {
        FixtureSite allocated = site();
        y = allocated.y + 1;
        z = allocated.z;
        requireClearInCell(cell, allocated.x, what);
        return allocated.x;
    }

    private void requireClearInCell(Cell cell, int x0, String what) throws Exception {
        FixtureSite.openAir(cell.dim, x0, z).requireClear(WorldCommandFixtures::exec, HALO, 3, what);
    }

    /** `getStateFromMeta` maps this to a cell radiating UP, so nothing but sky is in front of it. */
    private static final String RADIATOR_FACING_UP = "1";

    private static final int LOOP_LENGTH = 4;

    private static final String DEFAULT_STAR_KELVIN = "278";

    /**
     * A star strong enough that what it delivers dwarfs what a cell at room temperature radiates, so
     * the backwards case and the shield's residue are both unmistakable rather than marginal. Stated
     * as the temperature a cell would settle at under it, which is what the setting means.
     */
    private static final String FIERCE_STAR_KELVIN = "2000";

    /**
     * Two physically different sources, one term. The same rig is built twice — once on a world, where
     * what reaches it is that world's own warmth, and once in a space cell, where what reaches it is a
     * star a hundred million blocks away — and in both places the loop's response is the same function
     * of the ONE number the environment reports.
     *
     * <p>Stated as a difference so nothing about the radiator's own physics has to be restated: both
     * legs sit at the same temperature, so what each cell RADIATES is identical and cancels, and the
     * whole gap between the two nets must be the gap between the two reported fluxes. A star wired as
     * its own mechanism would move one of those and not the other.</p>
     *
     * <p>red-witnessed: with {@code HeatEnvironment#incidentFluxPerCell} at {@code return unshieldedFluxPerCell;} putting twice the reported flux into each
     * cell: "a warm world and a distant star must reach a radiator through ONE term: … netted 50 on a
     * world and -1129 in space, a difference of 1179.0, against a reported flux difference of
     * 589.965", 2026-09-30. The premises on both legs are arrangements and are not witnessed.</p>
     */
    @Test
    public void aWorldsWarmthAndAStarArriveThroughTheSameTerm() throws Exception {
        int x0 = stand("one radiating loop on the overworld, and the same loop in a space cell");
        buildLoop(0, x0, 1);
        Reply built = loopInfo(0, x0);
        assertEquals("premise: exactly one radiating cell, so per-cell figures are per-loop figures: "
                + built, 1, built.integer("radiatingCells"));
        long capacity = built.longInteger("heatCapacity");
        long charge = 100L * capacity;

        Reply onAWorld = cycle(0, x0, charge);
        long fluxOnAWorld = onAWorld.longInteger("incidentFluxMilli");
        long netOnAWorld = onAWorld.longInteger("rejected");
        assertTrue("premise: a world must be radiating something at the ship standing on it, or this "
                + "leg is deep space with extra steps: " + onAWorld, fluxOnAWorld > 0);

        Cell cell = occupyHomeCell();
        long fluxInSpace;
        long netInSpace;
        // Turned up because the two environments happen to be within a few units of each other at the
        // shipped numbers, and a test whose arms differ by less than its own rounding cannot tell a
        // working sum from a broken one.
        setConfig("shipHeatStarFluxReferenceKelvin", "600");
        try {
            requireClearInCell(cell, x0, "the same loop, in the space cell");
            buildLoop(cell.dim, x0, 1);
            Reply inSpace = cycle(cell.dim, x0, charge);
            assertEquals("premise: the two rigs must be the same size, or their temperatures differ "
                            + "and what they radiate no longer cancels: " + onAWorld + " | " + inSpace,
                    capacity, inSpace.longInteger("heatCapacity"));
            assertEquals("premise: and must have the same radiating surface: " + inSpace,
                    1, inSpace.integer("radiatingCells"));
            fluxInSpace = inSpace.longInteger("incidentFluxMilli");
            netInSpace = inSpace.longInteger("rejected");
            assertTrue("premise: a star must actually be reaching this cell, or the second source does "
                    + "not exist and only one thing is under test: " + inSpace, fluxInSpace > 0);
            assertTrue("premise: the two environments must differ by more than the readouts' own "
                            + "quantisation, or the comparison below cannot tell a working sum from a "
                            + "broken one: world=" + fluxOnAWorld + " space=" + fluxInSpace,
                    Math.abs(fluxInSpace - fluxOnAWorld) / 1000.0D > QUANTISATION_BOUND);
        } finally {
            setConfig("shipHeatStarFluxReferenceKelvin", DEFAULT_STAR_KELVIN);
            release(cell);
        }

        double fluxDifference = (fluxInSpace - fluxOnAWorld) / 1000.0D;
        double netDifference = netOnAWorld - netInSpace;
        assertTrue("a warm world and a distant star must reach a radiator through ONE term: the same "
                        + "loop at the same temperature netted " + netOnAWorld + " on a world and "
                        + netInSpace + " in space, a difference of " + netDifference + ", against a "
                        + "reported flux difference of " + fluxDifference + ". They do not match, so "
                        + "one of the two sources reaches the loop by a path the environment readout "
                        + "does not describe.",
                Math.abs(netDifference - fluxDifference) < QUANTISATION_BOUND);
    }

    /**
     * More arriving than can be shed: the surface runs backwards and the ship heats although it is
     * doing nothing and holding nothing.
     *
     * <p>The control is a loop of the same size that built no radiators, under the same star. It must
     * stay at zero — a radiating cell is the ship's coupling to the outside in both directions, so a
     * ship that built none is not warmed by one. Without that control this scenario would also pass on
     * a bug that simply added the environment to every loop in the world.</p>
     *
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. BACKWARDS — {@code HeatNetwork#rejectHeat} at {@code if (share != 0L)}
     * exchanging only positive shares: "under a star this strong the net must run BACKWARDS … \"rejected\":0".
     * IN THE LOOP — {@code HeatNetwork#tickThermodynamics} at {@code stored = Math.max(0L, stored - rejected);} subtracting only positive rejection: "and the energy must
     * actually be in the loop, not merely reported: … \"rejected\":-76764,\"heatStored\":0". NO
     * RADIATING SURFACE — {@code HeatNetwork#rejectHeat} at {@code return 0L;} giving a loop with no cells the incident flux:
     * "a loop with no radiating surface must take nothing from the environment … expected:&lt;0&gt;
     * but was:&lt;76800&gt;". The three premises before the star is turned up are arrangements and are
     * not witnessed.</p>
     */
    @Test
    public void aShipUnderAFierceStarHeatsThroughItsRadiators() throws Exception {
        Cell cell = occupyHomeCell();
        try {
            int withX = standInCell(cell, "a loop with a radiator and a loop without, under one star");
            int withoutX = withX + CONTROL_OFFSET;
            buildLoop(cell.dim, withX, 1);
            buildLoop(cell.dim, withoutX, 0);
            Reply withRadiator = loopInfo(cell.dim, withX);
            Reply withoutRadiator = loopInfo(cell.dim, withoutX);
            assertEquals("premise: one cell of radiating surface: " + withRadiator,
                    1, withRadiator.integer("radiatingCells"));
            assertEquals("premise: and none at all on the control: " + withoutRadiator,
                    0, withoutRadiator.integer("radiatingCells"));

            // The control on the environment first: an ordinary star must leave a charged loop
            // shedding, so the reversal below is the star and not something the rig does regardless.
            long capacity = withRadiator.longInteger("heatCapacity");
            Reply calm = cycle(cell.dim, withX, 100L * capacity);
            assertTrue("premise: under an ordinary star a charged loop must still be shedding: " + calm,
                    calm.longInteger("rejected") > 0);

            setConfig("shipHeatStarFluxReferenceKelvin", FIERCE_STAR_KELVIN);
            try {
                Reply radiating = cycle(cell.dim, withX, 0L);
                assertTrue("under a star this strong the net must run BACKWARDS — a loop that can only "
                                + "ever lose heat gives a ship free immunity to its environment: "
                                + radiating, radiating.longInteger("rejected") < 0);
                assertTrue("and the energy must actually be in the loop, not merely reported: "
                        + radiating, radiating.longInteger("heatStored") > 0);

                Reply bare = cycle(cell.dim, withoutX, 0L);
                assertEquals("a loop with no radiating surface must take nothing from the environment "
                                + "— the cells are the coupling, and a hull is not one: " + bare,
                        0L, bare.longInteger("heatStored"));
            } finally {
                setConfig("shipHeatStarFluxReferenceKelvin", DEFAULT_STAR_KELVIN);
            }
        } finally {
            release(cell);
        }
    }

    /**
     * A shield is sunscreen and never a wall. Asked for ALL of the incident flux, it takes most and
     * leaves a residue, so a ship parked in a star heats slowly rather than not at all.
     *
     * <p>Both halves matter and neither implies the other. The attenuation is real — the shielded loop
     * must gain far less than an identical unshielded one under the same star — and it is bounded: the
     * shielded loop must still gain something, with the configuration demanding a hundred percent. That
     * second assertion is the clause: the refusal lives in the code, so no setting can reach it.</p>
     *
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. MOST — {@code HeatEnvironment#incidentFluxPerCell} at {@code if (ShieldCoverage.isCovered(shieldGenerators, world, pos))}
     * treating no cell as covered: "a raised shield must take most of the incident flux off the ship
     * (shielded=76764 unshielded=76764)". NOT ALL — the {@code HeatEnvironment#MAX_SHIELD_ATTENUATION} at {@code 0.995D} constant letting the shield take
     * everything: "and it must NOT take all of it, however much the configuration asks for … (shielded=0)".
     * The emitter premise and the unshielded premise are arrangements and are not witnessed.</p>
     */
    @Test
    public void aShieldThinsTheFluxAndNeverRemovesIt() throws Exception {
        Cell cell = occupyHomeCell();
        try {
            int generatorX = standInCell(cell, "a shield, a loop inside it and a loop outside it");
            int emitterX = generatorX + 1;
            int shieldedX = generatorX + SHIELDED_OFFSET;
            int unshieldedX = generatorX + UNSHIELDED_OFFSET;
            place(cell.dim, generatorX, "affs:shield_generator", null);
            place(cell.dim, emitterX, "affs:field_generator", null);
            buildLoopWithRadiatorFirst(cell.dim, shieldedX);
            buildLoopWithRadiatorFirst(cell.dim, unshieldedX);

            // STIMULUS: fifteen rounds of charge, generator tick and network solve are the DOSE that
            // brings the emitter up, and every round is driven by a probe rather than by world time,
            // so the same dose does the same work on any box. No record closes it because nothing
            // here waits: the one read after the dose is refused as an arrangement failure below.
            for (int i = 0; i < 15; i++) {
                arrange("stellurgytest energy inject " + cell.dim + " " + generatorX + " " + y + " " + z
                        + " 4000");
                arrange("stellurgytest tile force-tick " + cell.dim + " " + generatorX + " " + y + " " + z
                        + " 1");
                arrange("stellurgytest shield tick " + cell.dim);
            }
            Reply emitter = ask("stellurgytest shield read " + cell.dim + " " + emitterX + " " + y + " " + z);
            requireArranged("premise: the emitter never came up, so nothing below is a test of a shield: "
                    + emitter, emitter.bool("powered"));

            setConfig("shipHeatStarFluxReferenceKelvin", FIERCE_STAR_KELVIN);
            setConfig("shipHeatShieldAttenuation", "1000");
            try {
                Reply shielded = cycle(cell.dim, shieldedX, 0L);
                Reply unshielded = cycle(cell.dim, unshieldedX, 0L);
                long gainedShielded = shielded.longInteger("heatStored");
                long gainedUnshielded = unshielded.longInteger("heatStored");

                assertTrue("premise: the unshielded control must be heating hard, or there is nothing "
                        + "for the shield to have stopped: " + unshielded, gainedUnshielded > 0);
                // MOST is the clause's own word and its own boundary: more than half. A tighter figure
                // would be a copy of the shield's private cap, and this test's second half already
                // pins the side of that cap which is the contract — that it is not all.
                assertTrue("a raised shield must take most of the incident flux off the ship (shielded="
                                + gainedShielded + " unshielded=" + gainedUnshielded + "): " + shielded,
                        gainedShielded * 2L < gainedUnshielded);
                assertTrue("and it must NOT take all of it, however much the configuration asks for — "
                                + "a ship parked in a star heats slowly and always (shielded="
                                + gainedShielded + "): " + shielded, gainedShielded > 0);
            } finally {
                setConfig("shipHeatShieldAttenuation", "900");
                setConfig("shipHeatStarFluxReferenceKelvin", DEFAULT_STAR_KELVIN);
            }
        } finally {
            release(cell);
        }
    }

    // ─── the rig ───────────────────────────────────────────────────────

    /** A live cell of the overworld's own system, and the slot world it is bound to. */
    private static final class Cell {
        private final String args;
        private final int dim;

        private Cell(String args, int dim) {
            this.args = args;
            this.dim = dim;
        }
    }

    /**
     * Materialize the cell the overworld itself lives in, so the sky over the rig is a real system with
     * a real star at its real distance rather than something this test invented.
     */
    private Cell occupyHomeCell() throws Exception {
        String cellKey = ask("stellurgytest space cell-info 0 0 0 0").text("dimCell");
        String[] sectors = cellKey.split("_");
        assertEquals("a cell key is a sector triple: " + cellKey, 3, sectors.length);
        String args = sectors[0] + " " + sectors[1] + " " + sectors[2];
        Reply occupied = arrange("stellurgytest space occupy " + args);
        requireArranged("the cell must materialize, or there is no space environment to test in: "
                + occupied, occupied.bool("worldLoaded"));
        return new Cell(args, occupied.integer("slotDim"));
    }

    /** Hand the slot back. A test that holds a pool slot is a test that breaks somebody else's. */
    private void release(Cell cell) throws Exception {
        arrange("stellurgytest space release " + cell.args);
    }

    /** A straight run of {@value #LOOP_LENGTH} blocks with {@code radiators} cells at the far end. */
    private void buildLoop(int dim, int x0, int radiators) throws Exception {
        buildRun(dim, x0, LOOP_LENGTH - radiators, radiators);
    }

    /**
     * The same run with its single cell at the NEAR end — the shield scenario needs the radiator at a
     * known distance from the emitter and the rest of the run trailing away from it.
     */
    private void buildLoopWithRadiatorFirst(int dim, int x0) throws Exception {
        buildRun(dim, x0, 0, 1);
    }

    private void buildRun(int dim, int x0, int radiatorOffset, int radiators) throws Exception {
        for (int i = 0; i < LOOP_LENGTH; i++) {
            boolean radiator = i >= radiatorOffset && i < radiatorOffset + radiators;
            place(dim, x0 + i, radiator ? "stellurgy:heatRadiator" : "stellurgy:heatPipe",
                    radiator ? RADIATOR_FACING_UP : null);
        }
        // `subnet info` answers ok for a position in no network too, with every quantity zero, so
        // "the run is built" is `inNetwork` and not `ok`.
        Reply info = loopInfo(dim, x0);
        requireArranged("the run must be built before it is solved: " + info, info.bool("inNetwork"));
        Reply solved = arrange("stellurgytest subnet solve heat " + dim + " 1");
        assertEquals("solve failed in dim " + dim + ": " + solved, 1, solved.integer("ticksSolved"));
    }

    /**
     * Charge the loop to a known energy and advance exactly one tick, in ONE probe call.
     *
     * <p>One call because between probe calls the world ticks normally and the heat domain ticks with
     * it, so charging in one command and measuring in the next measures whatever survived some natural
     * ticks — and here that gap would quietly deliver a whole star's worth of flux into the answer.</p>
     */
    private Reply cycle(int dim, int x0, long charge) throws Exception {
        Reply cycled = arrange("stellurgytest heat cycle " + dim + " " + x0 + " " + y + " " + z + " " + charge
                + " 1");
        requireArranged("heat cycle found no loop at " + x0 + " in dim " + dim + ": " + cycled,
                cycled.bool("inLoop"));
        assertEquals("premise: the loop must have been charged with exactly what was asked: " + cycled,
                charge, cycled.longInteger("charged"));
        return cycled;
    }

    private void place(int dim, int x, String block, String meta) throws Exception {
        Reply resp = arrange("stellurgytest place " + dim + " " + x + " " + y + " " + z + " " + block
                + (meta == null ? "" : " " + meta));
        assertTrue(block + " place failed at " + x + " in dim " + dim + ": " + resp, resp.bool("placed"));
    }

    private void setConfig(String key, String value) throws Exception {
        arrange("stellurgytest config set " + key + " " + value);
    }

    private Reply loopInfo(int dim, int x) throws Exception {
        return ask("stellurgytest subnet info heat " + dim + " " + x + " " + y + " " + z);
    }
}
