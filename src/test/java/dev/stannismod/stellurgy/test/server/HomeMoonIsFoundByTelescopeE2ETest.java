package dev.stannismod.stellurgy.test.server;

import java.util.Arrays;

import org.junit.Test;

import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.test.CellInfo;
import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.Plot;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.TelescopeReading;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The home world's moon is a destination of its own, and a player learns its address by LOOKING.
 *
 * <p>Contract (maintainer ruling 2026-09-30): a moon has a cell of its own inside its planet's zone,
 * so the home world's address no longer covers it. A starter crystal carries the home world and not
 * the bodies inside its zone; the observatory's local radar, standing in the home system, is what
 * names them. And an observatory may stand ON that moon: it surveys from the moon's system, in the
 * galactic lattice a survey walks.</p>
 *
 * <p>Played as the game ships: the aperture and the resolve margin are the configuration defaults,
 * asserted as an arrangement rather than set, because an instrument widened for the test can resolve
 * a system that a player's cannot.</p>
 */
public class HomeMoonIsFoundByTelescopeE2ETest extends AbstractSharedServerTest {

    /** The overworld: the world a player starts on, and the body whose zone is the subject. */
    private static final int HOME_DIM = 0;

    /**
     * How long the radar's completion record may take to appear. With research off production
     * resolves the whole region in the FIRST {@code completeRegionScanIfDue} pass, which the tile
     * runs every server tick ({@code TileObservatory.update}); the rest is the reader's own step
     * ({@code Events} reads the log every 5 ticks). Running out is red, never a pass.
     */
    private static final int RADAR_RECORD_TICKS = 20;

    private final Events events =
            new Events(this::exec, ticks -> GameTicks.advance(client(), GameTicks.server(), ticks));

    private String exec(String command) throws Exception {
        return String.join("\n", client().execute(command));
    }

    /**
     * The regime under test, stated: research OFF, the shipped default, where an observation
     * resolves outright. The radar reaches only the system it stands in (radius 0 — "the system you
     * are standing in and nothing else", per the setting's own definition), which is the whole
     * subject; a wider sweep would add systems this test says nothing about.
     */
    private void defaultGameLocalRadar() throws Exception {
        exec("stellurgytest config set planetsMustBeDiscovered false");
        exec("stellurgytest config set telescopePassiveRadiusSteps 0");
    }

    /** A body of the home world's system whose own cell lies INSIDE the home world's zone. */
    private static final class ZoneBody {
        final int dim;
        final String homeCell;
        final String cell;

        ZoneBody(int dim, String homeCell, String cell) {
            this.dim = dim;
            this.homeCell = homeCell;
            this.cell = cell;
        }

        @Override
        public String toString() {
            return "dim " + dim + " at " + cell + " inside " + homeCell;
        }
    }

    /**
     * The rule is about the home ZONE, so the subject is found by that and nothing else: the
     * registry names the home world's cell, and a body of its system whose cell key extends it by a
     * zone separator is inside its zone.
     */
    private ZoneBody bodyInsideTheHomeZone() throws Exception {
        CellInfo home = CellInfo.atSector(this::exec, 0, 0, 0, HOME_DIM);
        requireArranged("the registry places no home world, so there is no zone to hold a moon: "
                + home, home.dimCell != null);
        CellInfo system = CellInfo.atKey(this::exec, home.dimCell);
        for (CellInfo.Body body : system.systemBodies) {
            if (body.dim != HOME_DIM && body.cell != null
                    && body.cell.startsWith(home.dimCell + ".")) {
                return new ZoneBody(body.dim, home.dimCell, body.cell);
            }
        }
        requireArranged("the home world's system has no body inside its zone, so there is nothing"
                + " for this rule to be about: " + system, false);
        throw new AssertionError("unreachable");
    }

    private static boolean names(int[] dims, int dim) {
        for (int d : dims) {
            if (d == dim) {
                return true;
            }
        }
        return false;
    }

    private static String at(FixtureSite site) {
        return site.dim + " " + site.x + " " + site.y + " " + site.z;
    }

    /** The recorder names a machine by {@code x,y,z} — no dimension — so a site's key is that. */
    private static String recordKey(FixtureSite site) {
        return site.x + "," + site.y + "," + site.z;
    }

    /** An observatory at {@code site} holding a crystal read back as blank. */
    private TelescopeReading observatoryWithBlankCrystal(FixtureSite site) throws Exception {
        requireArranged("could not place an observatory at " + site,
                Reply.of(exec("stellurgytest telescope place " + at(site))).ok());
        String crystal = exec("stellurgytest telescope crystal " + at(site));
        requireArranged("the crystal must start blank, or what the radar wrote cannot be told from"
                        + " what was already there: " + crystal,
                Reply.of("stellurgytest telescope crystal", crystal).integer("addresses") == 0);
        TelescopeReading idle = TelescopeReading.at(this::exec, at(site));
        requireArranged("the instrument must be the one the game ships — aperture "
                        + StellurgyConfiguration.DEFAULT_TELESCOPE_LIMITING_MAGNITUDE + ": " + idle.raw(),
                idle.limitMagnitude == StellurgyConfiguration.DEFAULT_TELESCOPE_LIMITING_MAGNITUDE);
        requireArranged("and it must characterise whole systems, the only setting that names a"
                + " body at all: " + idle.raw(), idle.wholeSystem);
        return idle;
    }

    /** Run the local radar at {@code site} and read the instrument once it says it has finished. */
    private TelescopeReading localRadar(FixtureSite site) throws Exception {
        long mark = events.markInstrumented();
        TelescopeReading.of(exec("stellurgytest telescope passive " + at(site)))
                .requireOk("the local radar did not start");
        // A RECORD is the positive observation, so the recorder ran; markInstrumented has already
        // ruled out the two silences (bus unsubscribed, mixins unwoven) that would make a timeout
        // here mean nothing.
        events.awaitRecordWithFields(mark, "region_scan_advanced",
                "the local radar must finish", RADAR_RECORD_TICKS,
                "pos", recordKey(site), "complete", "true");
        return TelescopeReading.at(this::exec, at(site));
    }

    /**
     * <p>red-witnessed: with {@code CrystalSeeding.java:94}'s zone skip reverted to cell equality
     * ({@code home.cellKey().equals(coord.cellKey())}): "a starter crystal must not carry a body
     * inside the home world's zone (dim 2 at 19_0_0.1_0_0 inside 19_0_0) … Starter holds [0, 2]";
     * and with {@code CrystalSeeding.java:61}'s home record disabled: "a starter crystal must carry
     * the home world: … crystalDims:[]" — one inversion per run, 2026-09-30.</p>
     */
    @Test
    public void aStarterCrystalCarriesTheHomeWorldButNotItsMoon() throws Exception {
        defaultGameLocalRadar();
        ZoneBody moon = bodyInsideTheHomeZone();

        String reply = exec("stellurgytest telescope starter " + HOME_DIM);
        Reply starter = Reply.of("stellurgytest telescope starter", reply);
        requireArranged("the probe must seed a crystal: " + reply, starter.ok());
        int[] dims = starter.intArray("crystalDims");

        // The positive half, on the same reply: seeding ran and wrote a body. Without it an empty
        // crystal also says "no moon". THIS TEST DOES NOT SEE the other half of the rule - that
        // bodies OUTSIDE the home zone stay common knowledge - because this server's starter holds
        // the home world alone (measured 2026-09-30: [0]).
        assertTrue("a starter crystal must carry the home world: " + reply, names(dims, HOME_DIM));
        assertFalse("a starter crystal must not carry a body inside the home world's zone (" + moon
                        + "): its address is found with a telescope. Starter holds "
                        + Arrays.toString(dims),
                names(dims, moon.dim));
    }

    /**
     * <p>red-witnessed: with {@code TelescopeScan.java:257}'s body loop skipping a {@code MOON}:
     * "the local radar at home must write the body inside the home world's zone (dim 2 at
     * 19_0_0.1_0_0 inside 19_0_0); the crystal names [0]", 2026-09-30.</p>
     */
    @Test
    public void theLocalRadarAtHomeWritesTheHomeWorldsMoon() throws Exception {
        defaultGameLocalRadar();
        ZoneBody moon = bodyInsideTheHomeZone();
        FixtureSite site = site();
        observatoryWithBlankCrystal(site);

        TelescopeReading done = localRadar(site);
        assertTrue("the local radar at home must write the body inside the home world's zone ("
                        + moon + "); the crystal names " + Arrays.toString(done.crystalDims())
                        + ": " + done.raw(),
                names(done.crystalDims(), moon.dim));
    }

    /**
     * <p>red-witnessed: with {@code TileObservatory.scanOrigin}'s {@code .map(galacticCell)} removed:
     * "an observatory on the moon must survey from its system's galactic cell … expected 19_0_0 but
     * was 19_0_0.1_0_0"; with {@code TelescopeScan.java:257}'s body loop skipping a {@code MOON}: "the
     * moon's local radar must name the moon it stands on"; and skipping a {@code PLANET}: "and the
     * world that moon orbits … crystalDims:[2]" — one inversion per run, 2026-09-30. (An earlier
     * version of this class also measured what the origin inversion does past the assertion: the
     * dedicated server goes down with "Ticking block entity" from {@code TelescopeScan.detect}.)</p>
     */
    @Test
    public void anObservatoryOnTheMoonSurveysFromItsSystemsGalacticCell() throws Exception {
        defaultGameLocalRadar();
        ZoneBody moon = bodyInsideTheHomeZone();
        Plot plot = plot();
        // Two machines in one plot, at a stated separation: the recorder keys a machine by x,y,z
        // without its dimension, so the moon's must not share the home one's coordinates.
        FixtureSite homeSite = plot.site();
        FixtureSite onTheMoon = FixtureSite.openAir(moon.dim,
                plot.x(Plot.FIXTURE_INSET + 10), plot.z(Plot.FIXTURE_INSET));

        String homeOrigin = observatoryWithBlankCrystal(homeSite).originCellKey();
        requireArranged("the home observatory must stand in the home world's own cell ("
                + moon.homeCell + "), or it is no reference for the moon's: " + homeOrigin,
                moon.homeCell.equals(homeOrigin));

        String moonOrigin = observatoryWithBlankCrystal(onTheMoon).originCellKey();
        assertEquals("an observatory on the moon must survey from its system's galactic cell, not"
                        + " from the moon's name inside its planet's zone (" + moon.cell + ")",
                homeOrigin, moonOrigin);

        // And the survey it starts from there runs to the end and names the system it stands in:
        // the moon underfoot and the world it orbits.
        TelescopeReading done = localRadar(onTheMoon);
        assertTrue("the moon's local radar must name the moon it stands on: " + done.raw(),
                names(done.crystalDims(), moon.dim));
        assertTrue("and the world that moon orbits: " + done.raw(),
                names(done.crystalDims(), HOME_DIM));
    }
}
