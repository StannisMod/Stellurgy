package dev.stannismod.stellurgy.test.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.Before;
import org.junit.Test;

import dev.stannismod.stellurgy.space.GalacticCoord;
import dev.stannismod.stellurgy.universe.ClusteredGalaxyGenerator;
import dev.stannismod.stellurgy.universe.GalaxyGenConfig;
import dev.stannismod.stellurgy.universe.ReportOnce;
import dev.stannismod.stellurgy.universe.SectorBox;
import dev.stannismod.stellurgy.universe.TelescopeScan;
import dev.stannismod.stellurgy.universe.UniverseRegistry;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Where the registry meets the procedural generator around an AUTHORED system: the pack's word on what
 * stands in an authored neighbourhood is the whole of it, so no seat the generator offers whose own
 * neighbourhood reaches that box is a system — not to a survey's territory, and not to attribution —
 * while a PINNED procedural system, which is not authored, clears nothing around it.
 *
 * <p>The joint is real on both sides: a registry this test owns, the shipped
 * {@link ClusteredGalaxyGenerator} at the shipped {@link GalaxyGenConfig#defaults()}, wired through
 * {@link UniverseRegistry#attachGenerator}. No world, no mod state.</p>
 *
 * <p>An authored system's neighbourhood is the box half a territory to each side of its anchor — the
 * box its bodies are clamped into and its member cells attribute by
 * ({@code UniverseRegistry#storedAnchorNear}); a procedural seat's is what the generator says it owns
 * ({@link ClusteredGalaxyGenerator#neighbourhoodOf}).</p>
 */
public class AuthoredNeighbourhoodTest {

    /**
     * The world seed. Any seed would do: what the scenarios need from the field is MEASURED against it
     * before anything is placed, and refused as an arrangement if the field does not offer it.
     */
    private static final long SEED = 0L;

    /** The star id the authored system is placed under; the registry keys a placement by it. */
    private static final int AUTHORED_STAR = 1;

    private final GalaxyGenConfig config = GalaxyGenConfig.defaults();
    private ClusteredGalaxyGenerator generator;
    private UniverseRegistry registry;

    @Before
    public void aRegistryOverTheShippedField() {
        ReportOnce reports = new ReportOnce();
        generator = new ClusteredGalaxyGenerator(reports, config);
        registry = new UniverseRegistry();
        registry.bindReports(reports);
        registry.attachGenerator(generator);
        registry.bindWorldSeed(SEED);
    }

    /**
     * Around an authored anchor, every seat the registry answers a territory with is either the
     * authored system itself or a seat whose neighbourhood stays clear of the authored box; and no
     * cell is attributed to a seat that reaches it.
     *
     * <p>Fails if {@code UniverseRegistry#anchorsInTerritory} stops dropping the seats
     * {@code UniverseRegistry#clearOfAuthored} refuses, or {@code UniverseRegistry#anchorForCell} stops
     * refusing them as the system a cell belongs to.</p>
     *
     * <p>The arrangement reads the generator BEFORE the system is placed: the seats its field puts in
     * the 27 territories around the anchor, and which of them reach the box — at least one inside it
     * and at least one outside it whose own cells cross into it, so both halves of "reaches" are
     * exercised.</p>
     *
     * <p>red-witnessed: with {@code UniverseRegistry#anchorsInTerritory} at
     * {@code if (clearOfAuthored(seat))} reading {@code if (true)}, this fails with "a survey's
     * territory may not offer a seat that reaches the authored neighbourhood"; with
     * {@code UniverseRegistry#anchorForCell} at
     * {@code return seat.isPresent() && clearOfAuthored(seat.get()) ? seat : Optional.<GalacticCoord>empty();}
     * returning {@code seat}, with "no cell may be attributed to a seat that reaches the authored
     * neighbourhood" — one inversion per run, 2026-10-03.</p>
     * Pins ADDR-23 (no procedural seat whose neighbourhood reaches an authored one is a system; a pin clears nothing).
     */
    @Test
    public void noSeatThatReachesAnAuthoredNeighbourhoodIsASystem() {
        GalacticCoord authored = GalacticCoord.ORIGIN.cellCentre();
        SectorBox box = SectorBox.around(authored, config.minSpacing / 2L);
        List<GalacticCoord> looks = looksAround(authored);

        // 1. The field before the authored system is placed.
        List<GalacticCoord> reaching = new ArrayList<>();
        int inside = 0;
        for (GalacticCoord look : looks) {
            for (GalacticCoord seat : generator.anchorsInTerritory(SEED, look, TelescopeScan.MAX_SEATS_PER_LOOK)) {
                if (owns(seat).intersects(box)) {
                    reaching.add(seat);
                    inside += SectorBox.around(seat, 0L).intersects(box) ? 1 : 0;
                }
            }
        }
        System.out.println("[authored] " + reaching.size() + " seats reach the box " + box + ", " + inside
                + " of them inside it");
        requireArranged("the field must seat a system INSIDE the authored box: " + reaching, inside > 0);
        requireArranged("and one outside it whose own cells cross into it: " + reaching,
                reaching.size() > inside);

        registry.place(authored, AUTHORED_STAR);

        // 2. What the registry answers for the territories, and for the reaching seats' cells.
        List<String> offered = new ArrayList<>();
        for (GalacticCoord look : looks) {
            for (GalacticCoord seat : registry.anchorsInTerritory(look, TelescopeScan.MAX_SEATS_PER_LOOK)) {
                if (!seat.sameCell(authored) && owns(seat).intersects(box)) {
                    offered.add(seat.cellKey() + " for the look at " + look.cellKey());
                }
            }
        }
        assertEquals("a survey's territory may not offer a seat that reaches the authored neighbourhood "
                + box, new ArrayList<String>(), offered);

        List<String> attributed = new ArrayList<>();
        for (GalacticCoord seat : reaching) {
            Optional<GalacticCoord> owner = registry.anchorForCell(seat);
            if (owner.isPresent() && !owner.get().sameCell(authored) && owns(owner.get()).intersects(box)) {
                attributed.add(seat.cellKey() + " -> " + owner.get().cellKey());
            }
        }
        assertEquals("no cell may be attributed to a seat that reaches the authored neighbourhood " + box,
                new ArrayList<String>(), attributed);
    }

    /**
     * A PINNED procedural system is not authored, and clears nothing around it: a sibling seat whose
     * neighbourhood reaches the pinned system's box is still offered for a look outside that box.
     *
     * <p>Fails if {@code UniverseRegistry#clearOfAuthored} stops telling a pin from an authored
     * placement — the index it reads ({@code UniverseRegistry#authoredBySuperIndex}) at
     * {@code if (pinnedSystems.containsKey(anchor.cellKey()))} skipping pins.</p>
     *
     * <p>The pin is written the way a touch writes one ({@link UniverseRegistry#pinSystem}), on a seat
     * the field offers; the sibling and the look are chosen from the field before the pin, and the look
     * is measured to lie outside the pinned box, where the territory answers with the generator's seats
     * rather than with the pin.</p>
     *
     * <p>Does NOT see what a pin does to the attribution of the cells INSIDE its box — that is a
     * different question, with an answer of its own.</p>
     *
     * <p>red-witnessed: with {@code UniverseRegistry#authoredBySuperIndex} at
     * {@code if (pinnedSystems.containsKey(anchor.cellKey()))} reading {@code if (false)}, this fails
     * with "a pinned system is not authored and must not clear its sibling" — 2026-10-03.</p>
     * Pins ADDR-23 (no procedural seat whose neighbourhood reaches an authored one is a system; a pin clears nothing).
     */
    @Test
    public void aPinnedSystemClearsNothingAroundIt() {
        long reach = config.minSpacing / 2L;
        GalacticCoord pin = null;
        GalacticCoord sibling = null;
        GalacticCoord look = null;
        search:
        for (GalacticCoord territory : looksAround(GalacticCoord.ORIGIN.cellCentre())) {
            List<GalacticCoord> seats = generator.anchorsInTerritory(SEED, territory, TelescopeScan.MAX_SEATS_PER_LOOK);
            for (GalacticCoord candidate : seats) {
                SectorBox pinned = SectorBox.around(candidate, reach);
                for (GalacticCoord other : seats) {
                    if (other.sameCell(candidate) || !owns(other).intersects(pinned)) {
                        continue;
                    }
                    GalacticCoord corner = farCornerOf(other, candidate);
                    if (!SectorBox.around(corner, 0L).intersects(pinned)) {
                        pin = candidate;
                        sibling = other;
                        look = corner;
                        break search;
                    }
                }
            }
        }
        requireArranged("the field must seat two systems in one territory whose neighbourhoods meet, with a"
                + " cell of that territory outside the first one's box", pin != null);
        requireArranged("the pin must be written: " + pin.cellKey(), registry.pinSystem(pin));
        System.out.println("[pinned] pin " + pin.cellKey() + ", sibling " + sibling.cellKey() + ", look "
                + look.cellKey());

        boolean offered = false;
        for (GalacticCoord seat : registry.anchorsInTerritory(look, TelescopeScan.MAX_SEATS_PER_LOOK)) {
            offered |= seat.sameCell(sibling);
        }
        assertTrue("a pinned system is not authored and must not clear its sibling " + sibling.cellKey()
                + " from the look at " + look.cellKey(), offered);
    }

    /**
     * What the generator says the system at {@code seat} owns; a seat it handed out always has one.
     *
     * <p>red-witnessed: with {@code ClusteredGalaxyGenerator#neighbourhoodOf} at
     * {@code if (!seated.isPresent() || !seated.get().sameCell(here))} always true, both scenarios fail
     * here with "the generator must say what its own seat … owns" — 2026-10-03.</p>
     */
    private SectorBox owns(GalacticCoord seat) {
        Optional<SectorBox> box = generator.neighbourhoodOf(SEED, seat);
        assertTrue("the generator must say what its own seat " + seat.cellKey() + " owns", box.isPresent());
        return box.get();
    }

    /** A look in each of the 27 territories around {@code centre}, one territory apart. */
    private List<GalacticCoord> looksAround(GalacticCoord centre) {
        long s = config.minSpacing;
        List<GalacticCoord> looks = new ArrayList<>();
        for (long i = -1; i <= 1; i++) {
            for (long j = -1; j <= 1; j++) {
                for (long k = -1; k <= 1; k++) {
                    looks.add(GalacticCoord.ofSectorLocal(centre.sectorX() + i * s, centre.sectorY() + j * s,
                            centre.sectorZ() + k * s, 0L, 0L, 0L));
                }
            }
        }
        return looks;
    }

    /** The corner of {@code seat}'s territory farthest from {@code away} on every axis. */
    private GalacticCoord farCornerOf(GalacticCoord seat, GalacticCoord away) {
        long s = config.minSpacing;
        return GalacticCoord.ofSectorLocal(corner(seat.sectorX(), away.sectorX(), s),
                corner(seat.sectorY(), away.sectorY(), s), corner(seat.sectorZ(), away.sectorZ(), s),
                0L, 0L, 0L);
    }

    private static long corner(long sector, long away, long s) {
        long low = Math.floorDiv(sector, s) * s;
        return Math.abs(low - away) >= Math.abs(low + s - 1L - away) ? low : low + s - 1L;
    }
}
