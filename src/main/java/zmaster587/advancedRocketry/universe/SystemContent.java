package zmaster587.advancedRocketry.universe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import zmaster587.advancedRocketry.api.Constants;
import zmaster587.advancedRocketry.api.dimension.IDimensionProperties;
import zmaster587.advancedRocketry.api.dimension.solar.StellarBody;
import zmaster587.advancedRocketry.dimension.DimensionManager;
import zmaster587.advancedRocketry.dimension.DimensionProperties;
import zmaster587.advancedRocketry.space.AbsolutePos;
import zmaster587.advancedRocketry.space.BlockDelta;
import zmaster587.advancedRocketry.space.GalacticCoord;
import zmaster587.advancedRocketry.space.ZoneScale;
import zmaster587.advancedRocketry.util.AstronomicalBodyHelper;

/**
 * Derives the addressable {@link SystemBody} content of an AUTHORED system (a catalogued {@link StellarBody})
 * from its planets/moons (universe-model.md &sect;2 amendment A#1a + &sect;4). A system is an anchored
 * NEIGHBOURHOOD of cells: the star sits at the anchor cell's centre; every planet/belt gets its <b>own cell</b>
 * at a sector offset scaled from its orbital position ({@link #ORBIT_UNIT_BLOCKS blocks per unit}),
 * snapped to that cell's centre (zone content sits near the cell centre); a moon gets its own cell
 * inside its parent's ZONE, named in that zone's lattice ({@link ZoneScale}). Inter-body space is
 * cells of void.
 *
 * <p>A body's cell is its durable NAME, derived once at {@link #NAME_TICK} and thereafter recorded. Where
 * that cell IS stays a function of time: each body cell carries a {@link CellFrame} whose origin is its
 * primary's position, so the neighbourhood rides the body it belongs to — and a moon's frame is NESTED
 * in its planet's, so a moon's cell rides the moon while the moon rides the planet.</p>
 *
 * <p>The neighbourhood is BOUNDED: every body cell is clamped (with a WARN) into the system's declared
 * clear space around its anchor — the load-time guard that keeps two systems' neighbourhoods from
 * interleaving, whatever an XML author wrote for {@code orbitalDistance} (its cap is
 * {@code Integer.MAX_VALUE}).</p>
 *
 * <p>Pure DATA — a walkable realization is Layer 2. Scale constants are {@code tunable}.</p>
 */
public final class SystemContent {

    /**
     * Blocks per unit of {@code DimensionProperties} orbital distance — the chart metric's own
     * conversion, shared with the procedural generator so that one orbital distance means one
     * distance in both families AND at both LEVELS.
     *
     * <p>There used to be a second one beside it, {@code MOON_UNIT_BLOCKS = 200}, for a moon's
     * distance from its planet — <b>a factor of 29 920 between two units for the same quantity</b>.
     * Neither was arithmetically wrong; what was wrong is that the field meant a different LENGTH
     * depending on which level had written it, so a reader that did not know the level was wrong by
     * that factor. {@code ReferenceFrames.soiRadiusBlocks} takes a live block displacement rather
     * than reading the field, and says so, for exactly this reason.</p>
     */
    static final long ORBIT_UNIT_BLOCKS = AstronomicalBodyHelper.BLOCKS_PER_DISTANCE_UNIT;

    /**
     * The floor an authored moon is lifted to, in parent radii — see {@link #moonLawOf}.
     *
     * <p>PUBLIC because it is half of a window, not a private tuning knob. A zone's lattice has to
     * be coarse enough that a body's own descent shell (1.0157 radii) fits in its own cell and fine
     * enough that a moon at THIS floor still lands on an index of its own, so the two numbers
     * together decide whether any lattice can satisfy both — a factor of 2.46, against a
     * power-of-two step of 2.0. Lowering this closes that window, and the thing that notices must
     * be able to read it rather than repeat it.</p>
     */
    public static final double MOON_MIN_PARENT_RADII = 2.5d;
    /** Cells kept clear of the super-cell faces when clamping a body cell into its system's box. */
    static final int BOX_MARGIN_CELLS = 2;

    /** Ticks in one Minecraft day — the unit {@code AstronomicalBodyHelper} reports orbital periods in. */
    private static final double TICKS_PER_DAY = 24_000d;

    // Self-contained logger (not AdvancedRocketry.logger): loading the mod class triggers Forge bootstrap,
    // which would break pure unit tests of this derivation.
    private static final Logger LOGGER = LogManager.getLogger("AdvancedRocketry|Universe");

    private SystemContent() {
    }

    /**
     * The reference moment a cell name is derived at. Zero, and it must stay zero: the orbital law is
     * {@code ((tick % period) / period) · 2π} (AstronomicalBodyHelper), so tick 0 is the one instant
     * whose time term is exactly zero for every body regardless of its period — the name then depends
     * on the AUTHORED elements alone (distance, base angle, inclination, retrograde) and on nothing
     * that ticks.
     */
    static final long NAME_TICK = 0L;

    /**
     * Where a body's durable cell name comes from.
     *
     * <p>A name is derived once and then RECORDED, because a name that is only ever re-derived is
     * hostage to the precision of its inputs and to every later change of this derivation: the
     * authored angles round-trip through the world's XML, and one degree at a large orbital radius is
     * more than a cell. A recorded name survives all of that; a re-derived one silently renames a
     * coordinate a player wrote down.</p>
     *
     * <p>The system a body belongs to and its anchor's box travel with the request, because a
     * recorded name has a LIFECYCLE: it may only be served while it still names a cell inside its own
     * system's neighbourhood, and a dimension id that has been recycled must not inherit the name of
     * whatever used to hold it. The store is the only place that can see either.</p>
     *
     * <p>{@link #DERIVED_NAMES} is the identity — "no store, take the derivation" — which is what a
     * pure unit test wants and what any caller without a registry gets.</p>
     */
    public interface CellNames {
        /**
         * The recorded name for {@code dimId} in system {@code starId}, or {@code derived} if this is
         * its first derivation (or the recorded one may no longer be served).
         *
         * @param anchor          the system's anchor cell, for the containment check
         * @param minSpacingCells the generator's super-cell edge — the box the name must lie in
         */
        GalacticCoord nameFor(int dimId, int starId, GalacticCoord anchor, int minSpacingCells,
                              GalacticCoord derived);
    }

    /** The no-store resolver: every body keeps the name this derivation just computed. */
    public static final CellNames DERIVED_NAMES = new CellNames() {
        @Override
        public GalacticCoord nameFor(int dimId, int starId, GalacticCoord anchor, int minSpacingCells,
                                     GalacticCoord derived) {
            return derived;
        }
    };

    /** Legacy-spacing overload: derives with the default super-cell edge. */
    public static List<SystemBody> bodiesOf(StellarBody star, GalacticCoord systemCoord) {
        return bodiesOf(star, systemCoord, GalaxyGenConfig.DEFAULT_MIN_SPACING);
    }

    /**
     * The system's bodies, per-body-cell (A#1a). {@code minSpacingCells} is the active generator's
     * super-cell edge — the box every body cell is clamped into.
     */
    public static List<SystemBody> bodiesOf(StellarBody star, GalacticCoord systemCoord, int minSpacingCells) {
        return bodiesOf(star, systemCoord, minSpacingCells, DERIVED_NAMES);
    }

    /**
     * The system's bodies, with each body's cell name taken from {@code names} rather than from this
     * derivation alone.
     *
     * <p>No tick is taken and none is needed: a body's NAME is a function of the authored elements
     * only, and where it stands is answered by the accessor that asks — {@code inCellOffsetAt},
     * {@code addressAt}, {@code absoluteAt}. A derivation that took a tick had to pick one instant for
     * the whole system and hand every consumer the chart as of that instant; carrying the LAW instead
     * lets each consumer ask for the moment it is really talking about.</p>
     */
    public static List<SystemBody> bodiesOf(StellarBody star, GalacticCoord systemCoord, int minSpacingCells,
                                            CellNames names) {
        List<SystemBody> bodies = new ArrayList<>();
        if (star == null) {
            return bodies;
        }
        int starId = star.getId();
        GalacticCoord anchor = systemCoord.cellCentre();
        AbsolutePos anchorAbs = AbsolutePos.ofCellName(anchor);
        // The star sits at the anchor and does not move: a degenerate frame, not an exemption.
        // Its MASS travels with it because every zone in the system is sized against it: a sphere of
        // influence is r = a·(m/M)^(2/5), and an M nobody stated makes every zone below it vanish.
        SystemBody starBody = new SystemBody(anchor, CellFrame.staticAt(anchor), BodyEphemeris.STATIC,
                SystemBodyKind.STAR, Constants.INVALID_PLANET, starId, SystemBody.ORBIT_UNKNOWN,
                AstronomicalBodyHelper.starRadiusEarths(star),
                AstronomicalBodyHelper.starMassEarths(star));
        bodies.add(starBody);

        for (IDimensionProperties p : star.getPlanets()) {
            if (!(p instanceof DimensionProperties)) {
                continue;
            }
            DimensionProperties planet = (DimensionProperties) p;
            BodyEphemeris planetLaw = orbitLawOf(planet, star);
            GalacticCoord planetName = nameOf(planet, planetLaw, anchor, minSpacingCells, starId, names);
            CellFrame planetFrame = CellFrame.of(anchorAbs, planetLaw);
            // The orbit travels on the body for authored systems too, so the field means the same thing
            // for the whole catalogue: how far this body is from its star. A body that knew its orbit
            // only when it was procedural would be a field that lies for half the galaxy.
            SystemBody planetBody = new SystemBody(planetName, planetFrame, BodyEphemeris.STATIC,
                    kindOf(planet, SystemBodyKind.PLANET), planet.getId(), starId,
                    planet.getOrbitalDist(), planet.getRadius(), planet.getOrbitalMass());
            bodies.add(planetBody);

            for (int moonId : planet.getChildPlanets()) {
                DimensionProperties moon = DimensionManager.getInstance().getDimensionProperties(moonId);
                if (moon == null) {
                    continue;
                }
                BodyEphemeris moonLaw = moonLawOf(moon, planet);
                // A moon has its OWN cell, inside its planet's ZONE, and that cell rides the moon —
                // so the moon sits at its own frame's origin and has no in-cell offset, exactly as a
                // planet does one level up. That is what makes a craft parked beside a moon keep
                // station for free; while a moon shared its parent's cell, the parent's frame carried
                // it and the moon did not, and the craft drifted 42 descent shells in a day.
                // A moon carries its PARENT's distance from the star — what warms a moon is where its
                // planet is; how far it sits from the planet is in its frame's law, which is what
                // positions it. Same convention as the procedural side.
                bodies.add(new SystemBody(
                        moonNameOf(moon, planetBody, starBody, moonLaw, anchor, minSpacingCells,
                                starId, names),
                        CellFrame.within(planetFrame, moonLaw), BodyEphemeris.STATIC,
                        kindOf(moon, SystemBodyKind.MOON), moon.getId(), starId,
                        planet.getOrbitalDist(), moon.getRadius(), moon.getOrbitalMass()));
            }
        }
        auditOneRealBodyPerCell(bodies, starId);
        return bodies;
    }

    /**
     * A planet-level body's orbital law about its system ANCHOR — the law that both derives its
     * durable name (evaluated at {@link #NAME_TICK}) and drives its cell's frame.
     */
    private static BodyEphemeris orbitLawOf(DimensionProperties planet, StellarBody star) {
        double periodTicks = star == null ? 0d
                : TICKS_PER_DAY * AstronomicalBodyHelper.getOrbitalPeriod(planet.getOrbitalDist(),
                        star.getMass());
        return BodyEphemeris.orbit(planet.getOrbitalDist(), planet.baseOrbitTheta, planet.orbitalPhi,
                planet.isRetrograde, periodTicks, ORBIT_UNIT_BLOCKS);
    }

    /** A moon's orbital law about its PARENT — its offset inside the shared cell, live at every tick. */
    private static BodyEphemeris moonLawOf(DimensionProperties moon, DimensionProperties parent) {
        // A FLOOR rather than a replacement: an authored pack keeps the spacing it wrote, unless what
        // it wrote would put the moon inside its parent. That became possible only when bodies got a
        // real radius — an Earth is 25 513 blocks across, so an authored orbit of 100 units (20 000
        // blocks) is under the surface. The pack's intent is kept where it is expressible.
        int authored = moon.getOrbitalDist();
        double parentRadiusBlocks = Math.max(0.05d, parent.getRadius())
                * AstronomicalBodyHelper.EARTH_RADIUS_BLOCKS;
        long floorUnits = Math.round(parentRadiusBlocks * MOON_MIN_PARENT_RADII
                / (double) ORBIT_UNIT_BLOCKS);
        int orbit = (int) Math.max(authored, Math.max(1L, Math.min(Integer.MAX_VALUE, floorUnits)));
        // THE PERIOD OF THE ORBIT THE MOON IS PUT ON, which is the floored one. This used to be
        // derived from the AUTHORED distance and handed to an ephemeris built with the floored one,
        // so a lifted moon turned at the angular rate of an orbit it is not on — a radius and a
        // period that do not belong to each other are not a Keplerian orbit at all. It stayed
        // invisible while both numbers were small; it showed up the moment the period law was
        // anchored on the real Moon, as a body that failed to come back after one of its own periods.
        double periodTicks = TICKS_PER_DAY * AstronomicalBodyHelper.getMoonOrbitalPeriod(
                orbit, (float) parent.getOrbitalMass());
        return BodyEphemeris.orbit(orbit, moon.baseOrbitTheta, moon.orbitalPhi,
                moon.isRetrograde, periodTicks, ORBIT_UNIT_BLOCKS);
    }

    /**
     * The cell a PLANET-level body occupies — its DURABLE name, not a snapshot of where it is now.
     *
     * <p>Derived from the body's orbital elements evaluated at {@link #NAME_TICK}, snapped to that
     * cell's centre and clamped into the anchor's super-cell box so a neighbourhood can never reach a
     * neighbouring system's territory. The store is consulted AFTER the clamp, so a later change to
     * the spacing — or to this arithmetic — can never move a name that has already been recorded.</p>
     *
     * <p>It used to be derived from the LIVE orbital angle, which made a body's address a function of
     * world time: one orbit unit is a quarter of a cell, an orbit is hundreds of units, so a planet
     * left the cell a parked ship was sitting in every few minutes and simply vanished from its sky.
     * Orbital motion moves a body WITHIN its cell; it does not move it between cells.</p>
     */
    static GalacticCoord nameOf(DimensionProperties planet, BodyEphemeris law, GalacticCoord anchor,
                                int minSpacingCells, int starId, CellNames names) {
        BlockDelta at0 = law.offsetAt(NAME_TICK);
        GalacticCoord derived = clampIntoBox(
                anchor.plusLocal(at0.dx(), at0.dy(), at0.dz()).cellCentre(),
                anchor, minSpacingCells, planet.getId());
        return names == null ? derived
                : names.nameFor(planet.getId(), starId, anchor, minSpacingCells, derived);
    }

    /**
     * The cell a MOON occupies — a cell of its PARENT's zone lattice, whose key names the parent's own
     * cell. Its durable name, derived at {@link #NAME_TICK} exactly as a planet's is.
     *
     * <p>Why not a finer galactic lattice: a moon 1.54M blocks from its planet cannot get a distinct
     * galactic SECTOR at a cell of 32 000 000, so it would keep being named by the same cell as its
     * parent — which is the thing being removed. The lattice a moon is named in is sized to the zone
     * it is in ({@link ZoneScale}), and the name is a PATH — so "a name contains its parent's" holds
     * structurally rather than by audit.</p>
     *
     * <p><b>When the parent defines no zone the moon keeps the parent's name, and it says so.</b> That
     * is the pre-zone behaviour and it is a DEGRADATION, not an answer: the two bodies then share one
     * address and a jump aimed at it cannot say which it meant. It happens only where a parent has no
     * usable mass or no distance from its star, i.e. where there is no sphere of influence to divide.</p>
     */
    private static GalacticCoord moonNameOf(DimensionProperties moon, SystemBody parent,
                                            SystemBody primary, BodyEphemeris moonLaw,
                                            GalacticCoord anchor, int minSpacingCells, int starId,
                                            CellNames names) {
        GalacticCoord derived = moonCellIn(parent, primary, moonLaw, starId, moon.getId());
        return names == null ? derived
                : names.nameFor(moon.getId(), starId, anchor, minSpacingCells, derived);
    }

    /**
     * The cell of {@code parent}'s zone lattice that a moon on {@code moonLaw} occupies — the
     * derivation shared by the authored catalogue and the procedural generator, so a moon is named
     * the same way whichever built it.
     *
     * <p>See {@link #moonNameOf} for what the fallback costs. Reported once per parent per session,
     * because this derivation runs on every query.</p>
     */
    static GalacticCoord moonCellIn(SystemBody parent, SystemBody primary, BodyEphemeris moonLaw,
                                    int starId, int moonDimId) {
        GalacticCoord derived = ZoneScale.cellWithin(parent, primary, moonLaw.offsetAt(NAME_TICK),
                NAME_TICK);
        if (derived != null) {
            return derived;
        }
        if (reportOnce("noZone:" + starId + ':' + parent.name().cellKey())) {
            LOGGER.error("dim {} orbits the body at cell {}, which states no MASS ({}) and so has no "
                    + "zone to divide - there is no lattice to name the moon in and it FALLS BACK to "
                    + "sharing its parent's cell. The two are then one indistinguishable destination: "
                    + "a jump aimed at that address cannot say which body it meant. Give the parent a "
                    + "mass; a primary is NOT needed (a body without one is bounded by its cell).",
                    moonDimId, parent.name().cellKey(), parent.massEarths());
        }
        return parent.name();
    }

    /**
     * What a dimensioned body IS, for the purposes of aiming at it. A body with no surface is not a
     * place a ship can put down — the descent resolver has no terrain to find and no world to paste
     * into — so it must not be advertised as one. That answer belongs HERE, at the one place bodies
     * are made, rather than as an extra filter at the descent trigger: the nav GUI, the render
     * channel and the flight computer all read {@code isDescendTarget()} and must agree.
     */
    private static SystemBodyKind kindOf(DimensionProperties body, SystemBodyKind ifWalkable) {
        return body.hasSurface() ? ifWalkable : SystemBodyKind.GAS_GIANT;
    }

    /**
     * Layout problems already reported. This derivation runs on EVERY query — the console's forecast
     * once a second, the render broadcast, the entry resolver, every probe — so an unguarded report
     * is a flood, not a diagnostic: a 28-minute playtest produced 28,061 clamp warnings and drowned
     * the log it was needed in. One report per distinct problem, per session.
     */
    private static final Set<String> REPORTED = Collections.synchronizedSet(new HashSet<String>());

    /**
     * Forget what has already been reported (server stop). Without this the set outlives the world it
     * described: a second world in the same JVM — a dev restart, or every test class after the first
     * in one fork — has a genuine layout fault swallowed because an earlier, unrelated world already
     * minted that key.
     */
    public static void reset() {
        REPORTED.clear();
    }

    /** Report {@code message} once per distinct {@code key} per session. Returns whether it was said. */
    static boolean reportOnce(String key) {
        return REPORTED.add(key);
    }

    /**
     * INVARIANT: at most ONE real body per cell. Every real body owns its own cell — a moon included,
     * whose cell is one of its parent's ZONE. A zone-qualified key can never equal a galactic one, so the audit compares moons against their siblings in the same
     * zone and against nothing else.
     *
     * <p>The moon EXEMPTION this audit used to carry is gone with the rule that needed it. It was
     * load-bearing while a moon shared its parent's cell — every planet with moons would have been
     * reported — and keeping it now would silence the one collision that can still happen: two moons
     * whose orbits land them in the same cell of their parent's lattice.</p>
     *
     * <p>Two real bodies in one cell is not a cosmetic problem. A cell is what a jump can be aimed at
     * and what a ship arrives into, so a collision means two destinations the player cannot tell apart
     * or choose between, and an arrival that cannot say which body it came for. It is reported, not
     * repaired: silently moving an authored body would make the address a player wrote down mean
     * something else, and the honest repair belongs where the layout is decided.</p>
     */
    private static void auditOneRealBodyPerCell(List<SystemBody> bodies, int starId) {
        Map<String, List<Integer>> realBodiesByCell = new LinkedHashMap<>();
        for (SystemBody body : bodies) {
            String cell = body.name().cellKey();
            List<Integer> occupants = realBodiesByCell.get(cell);
            if (occupants == null) {
                occupants = new ArrayList<>();
                realBodiesByCell.put(cell, occupants);
            }
            occupants.add(body.dimId());
        }
        for (Map.Entry<String, List<Integer>> e : realBodiesByCell.entrySet()) {
            if (e.getValue().size() < 2) {
                continue;
            }
            if (REPORTED.add("collision:" + starId + ':' + e.getKey() + ':' + e.getValue())) {
                LOGGER.error("system {}: cell {} holds {} REAL bodies (dims {}) - a cell may hold at "
                        + "most one. They are one indistinguishable destination: a jump "
                        + "aimed at that address cannot say which body it meant, and an arrival cannot "
                        + "either. Spread the authored orbits, or give the bodies explicit cells.",
                        starId, e.getKey(), e.getValue().size(), e.getValue());
            }
        }
    }

    /**
     * Clamp a body's cell into its anchor's super-cell box, {@link #BOX_MARGIN_CELLS} clear of the faces.
     * A clamp means the authored orbit exceeds what the spacing guarantee can host — WARN, don't crash.
     */
    private static GalacticCoord clampIntoBox(GalacticCoord bodyCell, GalacticCoord anchor,
                                              int minSpacingCells, int dimId) {
        long s = Math.max(1, minSpacingCells);
        long reach = reachCells(s);
        long cx = clampAxis(bodyCell.sectorX(), anchor.sectorX(), reach);
        long cy = clampAxis(bodyCell.sectorY(), anchor.sectorY(), reach);
        long cz = clampAxis(bodyCell.sectorZ(), anchor.sectorZ(), reach);
        if ((cx != bodyCell.sectorX() || cy != bodyCell.sectorY() || cz != bodyCell.sectorZ())
                && REPORTED.add("clamp:" + dimId + ':' + bodyCell.cellKey())) {
            LOGGER.warn("orbit of dim {} reaches past its system's clear space ({} cells at a spacing "
                    + "of {}); clamping its cell from ({},{},{}) back inside it",
                    dimId, reach, s, bodyCell.sectorX(), bodyCell.sectorY(), bodyCell.sectorZ());
        }
        return GalacticCoord.ofSectorLocal(cx, cy, cz, 0L, 0L, 0L);
    }

    /**
     * Whether {@code cell} lies inside the neighbourhood box of {@code anchor} at {@code
     * minSpacingCells} — the containment that keeps two systems' neighbourhoods from overlapping, and
     * the question a RECORDED name must keep answering yes to. Shared with the store so the check that
     * admits a name and the clamp that produces one cannot drift apart.
     */
    public static boolean withinBoxOf(GalacticCoord cell, GalacticCoord anchor, int minSpacingCells) {
        if (cell == null || anchor == null) {
            return false;
        }
        // The box is measured in GALACTIC cells, so a zoned name is asked about through the galactic
        // cell its zone is in — a moon is inside its system's clear space exactly when its planet is.
        // Comparing a zone-local index against a galactic anchor would compare counts of two lattices
        // four orders of magnitude apart, and every moon in the game would read as inside the box for
        // arithmetic reasons rather than geometric ones.
        GalacticCoord here = cell.galacticCell();
        long reach = reachCells(Math.max(1, minSpacingCells));
        return Math.abs(here.sectorX() - anchor.sectorX()) <= reach
                && Math.abs(here.sectorY() - anchor.sectorY()) <= reach
                && Math.abs(here.sectorZ() - anchor.sectorZ()) <= reach;
    }

    /**
     * How far from its anchor a body of this system may be NAMED, in cells: the system's declared clear
     * space, or as much of it as this spacing can give.
     *
     * <p>It used to be half the spacing outright, which was the same number while a system's extent was
     * defined as a fraction of the distance to the next star. Once stars stand a real distance apart,
     * half of that is several thousand times more room than a system has any business occupying, and an
     * authored orbit could be named right up against the neighbouring star. The bound that matters is
     * the system's own clear space, and it is the same one the procedural generator seats against.</p>
     */
    private static long reachCells(long s) {
        long margin = (s > 2L * BOX_MARGIN_CELLS) ? BOX_MARGIN_CELLS : 0L;
        return Math.min(Math.max(0L, s / 2L - margin),
                Math.max(0L, UniverseScale.SEAT_MARGIN_CELLS - margin));
    }

    /**
     * The per-axis bound: {@code reach} cells either side OF THE ANCHOR.
     *
     * <p>This used to snap to the GRID super-cell containing the anchor —
     * {@code [floorDiv(anchor,s)*s + margin, … + s-1-margin]} — which is a different box, and for the
     * home system a disastrous one: with the anchor at sector 0 and {@code s = 512} the legal range
     * was {@code [2, 509]}, i.e. the POSITIVE OCTANT ONLY. Every body with a negative offset was
     * clamped flat onto the faces {@code x=2}/{@code y=2}, so half of every orbit collapsed into a
     * handful of cells and several real bodies ended up sharing one address, though at most one real
     * body may ever hold a cell name (ledger #118). Measured 2026-07-28: dim 0's own cell derived as
     * {@code (-3,0,25)} and clamped to {@code (2,2,25)} — which is exactly the cell a ship entering
     * space from Earth then settled in.
     * Centring the box on the anchor is also what this class's javadoc and
     * {@code ClusteredGalaxyGenerator} ("minSpacing/2 - margin") always claimed it did.</p>
     */
    private static long clampAxis(long sector, long anchorSector, long reach) {
        long lo = anchorSector - reach;
        long hi = anchorSector + reach;
        if (sector < lo) {
            return lo;
        }
        return sector > hi ? hi : sector;
    }
}
