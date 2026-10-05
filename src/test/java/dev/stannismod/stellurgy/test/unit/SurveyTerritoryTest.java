package dev.stannismod.stellurgy.test.unit;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.Test;

import dev.stannismod.stellurgy.space.GalacticCoord;
import dev.stannismod.stellurgy.universe.ClusteredGalaxyGenerator;
import dev.stannismod.stellurgy.universe.GalaxyGenConfig;
import dev.stannismod.stellurgy.universe.ReportOnce;
import dev.stannismod.stellurgy.universe.TelescopeScan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * What one look of a survey owes the direction it is pointed in: every system its star TERRITORY
 * holds, not the one that happens to sit at the point it landed on.
 *
 * <p><b>Why this is a unit test.</b> The question is answered by the procedural generator alone, and
 * the generator here is an instance this test builds and owns, from the shipped galaxy description
 * ({@link GalaxyGenConfig#defaults()}) — no running game, no registry, no configuration. Whether a
 * running survey ASKS this question of the generator in force is the survey's wiring, pinned on a
 * server.</p>
 *
 * <p>The seed is the arrangement's own: the contract holds for every seed, and nothing below depends
 * on what this one draws beyond the measured precondition that some territory near home is divided.</p>
 */
public class SurveyTerritoryTest {

    private static final long SEED = 20_261_002L;

    /**
     * How many sample points per axis the test drops into a territory to find its seats by POINT
     * resolution. A territory a look enumerates is divided at most four ways per axis
     * ({@code 4^3 = 64 = TelescopeScan.MAX_SEATS_PER_LOOK}), so eight points per axis put at least one
     * in every sub-cell.
     */
    private static final int SAMPLES_PER_AXIS = 8;

    /** How many territories out from home the slab reaches, on each side, in X and Z. */
    private static final int REACH = 12;

    /**
     * A look over a territory returns every seat that point resolution finds anywhere in it, and only
     * seats that really are inside it.
     *
     * <p>Fails if {@code ClusteredGalaxyGenerator#anchorsInTerritory} stops deciding that a look
     * enumerates its whole (divided) territory — the property that lets a survey stride by one
     * territory while the field is divided more finely.</p>
     *
     * <p>red-witnessed: with {@code ClusteredGalaxyGenerator#anchorsInTerritory} at
     * {@code for (long i = 0; i < k; i++)} reading {@code i < 1}, this fails with "a look over
     * territory -12,0,-10 missed seats that point resolution finds in it"; at
     * {@code if (k <= 1 || seats > Math.max(1, limit))} reading {@code if (true)}, with "no look returned
     * more than one seat …"; with {@code Lattice.of(supX, supY, supZ, i, j, m, k, s, local.ownField,}
     * reading {@code supX + 1}, with "a look over territory -12,0,-12 returned …, which lies in another
     * territory"; with a territory corner appended after
     * {@code local.dilution(), local.material)).ifPresent(anchors::add);}, with "a look returned
     * -42303755_1_-42303755, which is not a seat of the field" — one inversion per run,
     * 2026-10-02.</p>
     */
    @Test
    public void aLookReturnsEverySeatOfItsTerritory() {
        GalaxyGenConfig config = GalaxyGenConfig.defaults();
        ClusteredGalaxyGenerator galaxy = new ClusteredGalaxyGenerator(new ReportOnce(), config);
        long s = config.minSpacing;
        int limit = TelescopeScan.MAX_SEATS_PER_LOOK;

        int enumerated = 0;
        int dividedBeyondAPoint = 0;
        for (long tx = -REACH; tx <= REACH; tx++) {
            for (long tz = -REACH; tz <= REACH; tz++) {
                GalacticCoord corner = GalacticCoord.ofSectorLocal(tx * s, 0L, tz * s, 0L, 0L, 0L);
                List<GalacticCoord> look = galaxy.anchorsInTerritory(SEED, corner, limit);

                Set<String> returned = new LinkedHashSet<>();
                for (GalacticCoord seat : look) {
                    assertEquals("a look over territory " + tx + ",0," + tz + " returned " + seat.cellKey()
                                    + ", which lies in another territory",
                            tx + "," + 0 + "," + tz, Math.floorDiv(seat.sectorX(), s) + ","
                                    + Math.floorDiv(seat.sectorY(), s) + "," + Math.floorDiv(seat.sectorZ(), s));
                    assertEquals("a look returned " + seat.cellKey() + ", which is not a seat of the field",
                            Optional.of(seat), galaxy.anchorAt(SEED, seat));
                    returned.add(seat.cellKey());
                }

                Set<String> byPoint = seatsByPoint(galaxy, tx * s, tz * s, s);
                if (returned.size() > 1) {
                    enumerated++;
                    assertTrue("a look over territory " + tx + ",0," + tz + " missed seats that point"
                                    + " resolution finds in it: returned " + returned + ", points found " + byPoint,
                            returned.containsAll(byPoint));
                    if (byPoint.size() > 1) {
                        dividedBeyondAPoint++;
                    }
                }
            }
        }
        System.out.println("of " + (2 * REACH + 1) * (2 * REACH + 1) + " territories, " + enumerated
                + " were enumerated by a look and " + dividedBeyondAPoint
                + " of those hold more than one seat that points find");
        assertTrue("no look returned more than one seat, so the slab holds no divided territory a look"
                + " enumerates — or looks stopped enumerating them", dividedBeyondAPoint > 0);
    }

    /** The distinct seats point resolution finds in a territory, sampled on a regular grid. */
    private static Set<String> seatsByPoint(ClusteredGalaxyGenerator galaxy, long x0, long z0, long s) {
        Set<String> found = new LinkedHashSet<>();
        for (int i = 0; i < SAMPLES_PER_AXIS; i++) {
            for (int j = 0; j < SAMPLES_PER_AXIS; j++) {
                for (int k = 0; k < SAMPLES_PER_AXIS; k++) {
                    long x = x0 + (2L * i + 1L) * s / (2L * SAMPLES_PER_AXIS);
                    long y = (2L * j + 1L) * s / (2L * SAMPLES_PER_AXIS);
                    long z = z0 + (2L * k + 1L) * s / (2L * SAMPLES_PER_AXIS);
                    Optional<GalacticCoord> seat = galaxy.anchorAt(SEED,
                            GalacticCoord.ofSectorLocal(x, y, z, 0L, 0L, 0L));
                    if (seat.isPresent()) {
                        found.add(seat.get().cellKey());
                    }
                }
            }
        }
        return found;
    }
}
