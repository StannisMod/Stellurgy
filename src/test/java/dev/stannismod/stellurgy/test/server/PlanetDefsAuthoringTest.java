package dev.stannismod.stellurgy.test.server;

import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;
import dev.stannismod.stellurgy.api.Constants;
import dev.stannismod.stellurgy.dimension.DimensionProperties;
import dev.stannismod.stellurgy.universe.GalaxyGenConfig;
import dev.stannismod.stellurgy.test.DimList;
import dev.stannismod.stellurgy.test.Reply;
import com.github.stannismod.forge.testing.junit.ClassScope;
import com.github.stannismod.forge.testing.junit.ClassScopeRunner;
import com.github.stannismod.forge.testing.junit.ScopedTest;
import com.github.stannismod.forge.testing.junit.TestClassScope;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertEquals;

/**
 * What the game DECIDES about a pack's {@code planetDefs.xml} — the structure, the bounds, the
 * defaults and the fallbacks the loader applies — read where the running server keeps the result.
 *
 * <p>NEW-GROUP: planetDefs.xml authoring — the cluster of decisions {@code XMLPlanetLoader} and the
 * universe registry take about a pack's file (hierarchy, dimension allocation, clamps, laser-drill
 * ore resolution, terrain source, weather engagement, the procedural galaxy's configuration and the
 * galaxy an anchor is declared against). Production reads that file exactly once, at server start
 * ({@code DimensionManager#createAndLoadDimensions}, from {@code <gameDir>/config/advRocketry/}), so
 * the arrangement is a file that must be on disk BEFORE the boot. {@link AbstractSharedServerTest}
 * starts its server in its own {@code @BeforeClass} on a fresh directory and has no way to carry
 * one, and the three classes that do boot an authored file ({@code PlanetXmlConfigIntegrationTest},
 * {@code PlanetDefsFaultToleranceTest}, {@code PlanetWeatherGateTest}) each boot once PER METHOD
 * around a world of their own. This class boots ONCE, around one file that holds every case below;
 * every method only READS, through read-only probe verbs, so no method can see another's.</p>
 *
 * <p><b>Who owns the server</b> — {@link AuthoredWorld}, the class scope: the runner's run of this
 * class opens it before the first test and closes it after the last, so no static holds it.</p>
 *
 * <p><b>No waits.</b> Every decision read here is taken before the server reports ready: the file is
 * parsed at server-about-to-start and the universe is populated at server-starting, both ahead of the
 * console line the harness waits for. Each read is of standing state, once.</p>
 *
 * <p><b>What this does not see.</b> It reads the state the loader left; it does not see a player USE
 * any of it — no laser drill is run, no moon is flown to, no weather cycle is ticked, no procedural
 * system is realized, no foreign world type is resolved. Those consumers are other mechanics'
 * subjects. Nor does it see the file the server WRITES back on save. And it does not see the case of a
 * file with no {@code <galaxyGen>} at all, which needs a different file:
 * {@code PlanetXmlConfigIntegrationTest} reads that off its own authored world.</p>
 */
@RunWith(ClassScopeRunner.class)
@ClassScope(PlanetDefsAuthoringTest.AuthoredWorld.class)
public class PlanetDefsAuthoringTest implements ScopedTest<PlanetDefsAuthoringTest.AuthoredWorld> {

    private static final int PRIMUS = 9601;
    private static final int PRIMUS_MOON = 9602;
    private static final int HEAVY = 9603;
    private static final int LIGHT = 9604;
    private static final int DRILLER = 9605;
    private static final int DRILLER_UNRESOLVED = 9606;
    private static final int MOD_TERRAIN = 9607;
    private static final int BOGUS_TERRAIN = 9608;
    private static final int STATES_NO_WEATHER = 9609;
    private static final int STATES_A_WEATHER_LENGTH = 9610;

    /**
     * The id a body written AFTER the unnumbered one states: the lowest id the allocator may hand
     * out, which is the configured {@code minDimension} at its shipped default. Measured 2026-10-02
     * in this world before that body was added: the unnumbered body was allocated exactly this id
     * ({@code stellurgyDimensions:[9601,9602,2,…]}, {@code logs/repin-b-inv1.log}).
     *
     * <p>A constant: a final {@code int}: the DEFAULT of a fresh configuration object, a literal in its field
     * initialiser, never the live configuration.</p>
     */
    private static final int FIRST_FREE = new dev.stannismod.stellurgy.api.StellurgyConfiguration().minDimension;
    private static final String STATES_THE_FIRST_FREE_ID = "StatesTheFirstFreeId";

    /** The id two bodies of the file state: the earlier holds it, the later must be refused. */
    private static final int HELD_TWICE = 9611;
    private static final String HOLDS_THE_ID = "HoldsTheId";
    private static final String STATES_THE_SAME_ID = "StatesTheSameId";

    /** The one body the counted star declares by hand, beside the worlds its count asks for. */
    private static final int COUNTED_DECLARED = 9612;

    /** A planet whose one moon states {@link #HELD_TWICE}, an id a planet of the star holds. */
    private static final int SECUNDUS = 9613;
    /** A planet whose two moons both state {@link #TERTIUS_MOON}: the first holds it, the second is refused. */
    private static final int TERTIUS = 9614;
    private static final int TERTIUS_MOON = 9615;

    /** Every DIMID this file states — the ids the allocator must NOT hand the unnumbered body. */
    private final int[] authoredDims = {PRIMUS, PRIMUS_MOON, HEAVY, LIGHT, DRILLER,
            DRILLER_UNRESOLVED, MOD_TERRAIN, BOGUS_TERRAIN, STATES_NO_WEATHER, STATES_A_WEATHER_LENGTH,
            FIRST_FREE, HELD_TWICE, COUNTED_DECLARED, SECUNDUS, TERTIUS, TERTIUS_MOON};

    private static final String UNNUMBERED = "Unnumbered";

    /**
     * An ore-dictionary name that the running game has RESERVED but registered no stack under —
     * what a pack meets when it names an ore whose providing mod is absent.
     *
     * <p>Measured 2026-10-02 with {@code oredict empty} on this boot: the reserved-and-empty names
     * were {@code nuggetDiamond, nuggetLead, nuggetSilver, nuggetUranium, rodIron}. {@code rodIron}
     * is reserved by the mod's own crafting recipe {@code pipesealer_alt.json}, whose
     * {@code forge:ore_dict} ingredient asks the dictionary for the name while recipes load at mod
     * initialisation — before the server starts and so before the file is parsed. If the name ever
     * gains a stack, the arrangement check below fails and prints the current list.</p>
     */
    private static final String RESERVED_EMPTY_ORE = "rodIron";

    /** The two stars, in the order the file states them; the loader numbers stars in that order. */
    private static final int STAR_SOL = 0;
    private static final int STAR_FARAWAY = 1;
    private static final int STAR_COUNTED = 2;
    private static final String FARAWAY_GALAXY = "4,-1,2";

    /** What the counted star's {@code numPlanets} and {@code numGasGiants} ask for, in the file. */
    private static final int COUNTED_PLANETS = 2;
    private static final int COUNTED_GAS_GIANTS = 1;

    /** This class run's authored world; handed over by the runner before any test runs. */
    private AuthoredWorld world;

    @Override
    public void attachScope(AuthoredWorld scope) {
        this.world = scope;
    }

    /**
     * The one server this class boots, around a fresh directory holding {@link #authoredFile()} — owned
     * by the runner's run of the class, started before its first test and closed after its last.
     */
    public static final class AuthoredWorld extends TestClassScope {

        private RealDedicatedServerHarness harness;

        @Override
        protected void open(Class<?> testClass) throws Exception {
            Assume.assumeTrue("Server harness disabled — set -D"
                            + AbstractHeadlessServerTest.PROP_HARNESS_ENABLED + "=true",
                    Boolean.parseBoolean(System.getProperty(
                            AbstractHeadlessServerTest.PROP_HARNESS_ENABLED, "false")));
            Path workDir = Files.createTempDirectory("forge-server-planetdefs-authoring-");
            // Where production looks for a pack's file: "./config/" + configFolder, relative to the
            // server's working directory — which the harness sets to the root it is handed.
            Path configDir = workDir.resolve("config").resolve(
                    dev.stannismod.stellurgy.api.StellurgyConfiguration.configFolder);
            Files.createDirectories(configDir);
            Files.write(configDir.resolve("planetDefs.xml"), authoredFile().getBytes(StandardCharsets.UTF_8));
            harness = RealDedicatedServerHarness.startWith(workDir, /*cleanupOnClose=*/true);
        }

        @Override
        protected void close() throws Exception {
            if (harness != null) {
                try {
                    harness.close();
                } finally {
                    harness = null;
                }
            }
        }
    }

    /** The one file. Each block is labelled with the method that reads it. */
    private static String authoredFile() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<galaxy>\n"
                // aPacksGalaxyGenIsTheConfigurationTheRunningUniverseUses: two occupancies that
                // differ from each other and from the shipped defaults, so a swap or a dropped value
                // reads as a different number; both spacings left unstated.
                + "    <galaxyGen density=\"0.37\" galaxyDensity=\"0.61\">\n"
                + "        <starType temp=\"55\" minSize=\"0.7\" maxSize=\"1.3\" weight=\"3\"/>\n"
                + "        <galaxyType name=\"Fat Disc\" profile=\" spheroid \" minRadius=\"20000\""
                + " maxRadius=\"40000\" thickness=\"0.3\" arms=\"0\" rotationSpeed=\"90\""
                + " coreFraction=\"0.2\" minSatellites=\"0\" maxSatellites=\"1\" weight=\"5\"/>\n"
                + "        <galaxyType name=\"Thick\" thickness=\"0.25\"/>\n"
                + "    </galaxyGen>\n"
                // aStarsAnchorIsDeclaredAgainstTheHomeGalaxyUnlessItNamesAnother: no `galaxy` here.
                + "    <star name=\"Sol\" temp=\"100\" x=\"0\" y=\"0\" size=\"1.0\""
                + " galacticCoord=\"12,0,-7\" numPlanets=\"0\" numGasGiants=\"0\">\n"
                // aNestedPlanetIsMadeAChildOfTheBodyItIsWrittenInside
                + "        <planet name=\"Primus\" DIMID=\"" + PRIMUS + "\">\n"
                + "            <mass>1.0</mass>\n"
                + "            <radius>1.0</radius>\n"
                + "            <planet name=\"PrimusMoon\" DIMID=\"" + PRIMUS_MOON + "\">\n"
                + "                <mass>1.0</mass>\n"
                + "                <radius>1.0</radius>\n"
                + "            </planet>\n"
                + "        </planet>\n"
                // aPlanetThatStatesNoDimensionIsGivenAFreeOne
                + "        <planet name=\"" + UNNUMBERED + "\">\n"
                + "            <mass>1.0</mass>\n"
                + "            <radius>1.0</radius>\n"
                + "        </planet>\n"
                // ...and, AFTER it, a body stating the id the allocator hands out first.
                + "        <planet name=\"" + STATES_THE_FIRST_FREE_ID + "\" DIMID=\"" + FIRST_FREE + "\">\n"
                + "            <mass>1.0</mass>\n"
                + "            <radius>1.0</radius>\n"
                + "        </planet>\n"
                // aSecondBodyStatingAHeldIdIsRefusedAndNotBoundToItsStar: one id stated twice.
                + "        <planet name=\"" + HOLDS_THE_ID + "\" DIMID=\"" + HELD_TWICE + "\">\n"
                + "            <mass>1.0</mass>\n"
                + "            <radius>1.0</radius>\n"
                + "        </planet>\n"
                + "        <planet name=\"" + STATES_THE_SAME_ID + "\" DIMID=\"" + HELD_TWICE + "\">\n"
                + "            <mass>1.0</mass>\n"
                + "            <radius>1.0</radius>\n"
                + "        </planet>\n"
                // aRefusedMoonIsNoLongerItsParentsChild: a moon stating an id a planet of the star
                // holds, and a planet whose two moons state one id between them.
                + "        <planet name=\"Secundus\" DIMID=\"" + SECUNDUS + "\">\n"
                + "            <mass>1.0</mass>\n"
                + "            <radius>1.0</radius>\n"
                + "            <planet name=\"SecundusMoonOnAHeldId\" DIMID=\"" + HELD_TWICE + "\">\n"
                + "                <mass>1.0</mass>\n"
                + "                <radius>1.0</radius>\n"
                + "            </planet>\n"
                + "        </planet>\n"
                + "        <planet name=\"Tertius\" DIMID=\"" + TERTIUS + "\">\n"
                + "            <mass>1.0</mass>\n"
                + "            <radius>1.0</radius>\n"
                + "            <planet name=\"TertiusFirstMoon\" DIMID=\"" + TERTIUS_MOON + "\">\n"
                + "                <mass>1.0</mass>\n"
                + "                <radius>1.0</radius>\n"
                + "            </planet>\n"
                + "            <planet name=\"TertiusSecondMoon\" DIMID=\"" + TERTIUS_MOON + "\">\n"
                + "                <mass>1.0</mass>\n"
                + "                <radius>1.0</radius>\n"
                + "            </planet>\n"
                + "        </planet>\n"
                // atmosphereAndGravityOutsideTheirRangeAreClampedIntoIt
                + "        <planet name=\"Heavy\" DIMID=\"" + HEAVY + "\">\n"
                + "            <mass>1.0</mass>\n"
                + "            <radius>1.0</radius>\n"
                // Computed from the ceiling: a written-out number stops being "above the maximum"
                // the moment the maximum moves.
                + "            <atmosphereDensity>" + (DimensionProperties.MAX_ATM_PRESSURE + 1)
                + "</atmosphereDensity>\n"
                + "            <gravitationalMultiplier>900</gravitationalMultiplier>\n"
                + "        </planet>\n"
                + "        <planet name=\"Light\" DIMID=\"" + LIGHT + "\">\n"
                + "            <mass>1.0</mass>\n"
                + "            <radius>1.0</radius>\n"
                + "            <atmosphereDensity>-50</atmosphereDensity>\n"
                + "            <gravitationalMultiplier>-20</gravitationalMultiplier>\n"
                + "        </planet>\n"
                // aLaserDrillEntryIsTrimmedCountedAndCopiedOffTheDictionary: whitespace on both
                // sides of both fields, the way a hand-formatted list is written.
                + "        <planet name=\"Driller\" DIMID=\"" + DRILLER + "\">\n"
                + "            <mass>1.0</mass>\n"
                + "            <radius>1.0</radius>\n"
                + "            <laserDrillOres> oreIron ; 3 </laserDrillOres>\n"
                + "        </planet>\n"
                // aLaserDrillOreThatResolvesToNothingIsSkippedAndThePlanetKept: the unresolvable
                // name FIRST, so a failure on it is a failure before the good entry is reached.
                + "        <planet name=\"DrillerUnresolved\" DIMID=\"" + DRILLER_UNRESOLVED + "\">\n"
                + "            <mass>1.0</mass>\n"
                + "            <radius>1.0</radius>\n"
                + "            <laserDrillOres>" + RESERVED_EMPTY_ORE + ";2,oreGold;2</laserDrillOres>\n"
                + "        </planet>\n"
                // aTerrainSourceIsReadTolerantlyAndAnUnknownOneKeepsThePlanet
                + "        <planet name=\"ModTerrain\" DIMID=\"" + MOD_TERRAIN + "\">\n"
                + "            <mass>1.0</mass>\n"
                + "            <radius>1.0</radius>\n"
                + "            <terrainSource> mod_worldtype </terrainSource>\n"
                + "            <terrainWorldType> flat </terrainWorldType>\n"
                + "        </planet>\n"
                + "        <planet name=\"BogusTerrain\" DIMID=\"" + BOGUS_TERRAIN + "\">\n"
                + "            <mass>1.0</mass>\n"
                + "            <radius>1.0</radius>\n"
                + "            <terrainSource>NOT_A_TERRAIN_SOURCE</terrainSource>\n"
                + "        </planet>\n"
                // aStatedWeatherLengthEngagesThePlanetsOwnCycleAndNoneLeavesItShared
                + "        <planet name=\"StatesNoWeather\" DIMID=\"" + STATES_NO_WEATHER + "\">\n"
                + "            <mass>1.0</mass>\n"
                + "            <radius>1.0</radius>\n"
                + "        </planet>\n"
                + "        <planet name=\"StatesAWeatherLength\" DIMID=\"" + STATES_A_WEATHER_LENGTH + "\">\n"
                + "            <mass>1.0</mass>\n"
                + "            <radius>1.0</radius>\n"
                + "            <rainStartLength>3000</rainStartLength>\n"
                + "        </planet>\n"
                + "    </star>\n"
                + "    <star name=\"Faraway\" temp=\"80\" x=\"40\" y=\"40\" size=\"0.9\""
                + " galacticCoord=\"3,0,5\" galaxy=\"" + FARAWAY_GALAXY + "\""
                + " numPlanets=\"0\" numGasGiants=\"0\">\n"
                + "    </star>\n"
                // anAuthoredStarHoldsWhatItsPackDeclaresAndNothingElse: Faraway above is the bare star;
                // this one declares one body by hand and asks for more by count. Its anchor is in the
                // home galaxy, as Sol's is, so the reserved-galaxy list stays what its own test reads,
                // and three territories out along X (the spacing this file leaves unstated), so its
                // neighbourhood and Sol's are two and not one.
                + "    <star name=\"Counted\" temp=\"90\" x=\"80\" y=\"80\" size=\"1.0\""
                + " galacticCoord=\"" + (3L * GalaxyGenConfig.DEFAULT_MIN_SPACING) + ",0,0\""
                + " numPlanets=\"" + COUNTED_PLANETS + "\""
                + " numGasGiants=\"" + COUNTED_GAS_GIANTS + "\">\n"
                + "        <planet name=\"CountedDeclared\" DIMID=\"" + COUNTED_DECLARED + "\">\n"
                + "            <mass>1.0</mass>\n"
                + "            <radius>1.0</radius>\n"
                + "        </planet>\n"
                + "    </star>\n"
                + "</galaxy>\n";
    }

    /**
     * A {@code <planet>} written inside another is that body's moon, and the link is held from BOTH
     * ends: the parent lists it among its children and the moon names the parent. Either half alone
     * is a broken system — a parent that does not list its moon draws a sky without it, a moon that
     * does not name its parent is laid out as a planet of the star.
     *
     * <p>red-witnessed: 2026-10-02, with {@code XMLPlanetLoader#readPlanetFromNode} at
     * {@code properties.addChildPlanet(child);} removed, this fails with both halves wrong —
     * {@code Primus.children=[]} and {@code PrimusMoon.parent=-2147483647} (the invalid sentinel).</p>
     */
    @Test
    public void aNestedPlanetIsMadeAChildOfTheBodyItIsWrittenInside() throws Exception {
        DimList dims = dims();
        requireArranged("both bodies of the nested pair must be registered before their link is read: "
                + dims, dims.holds(PRIMUS) && dims.holds(PRIMUS_MOON));

        Reply parent = authored(PRIMUS);
        Reply moon = authored(PRIMUS_MOON);
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("Primus.children", Arrays.toString(new int[]{PRIMUS_MOON}));
        expected.put("PrimusMoon.parent", String.valueOf(PRIMUS));
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("Primus.children", Arrays.toString(parent.intArray("children")));
        actual.put("PrimusMoon.parent", String.valueOf(moon.integer("parent")));
        assertEquals("a nested <planet> must be its parent's child AND name the parent: "
                + parent + " / " + moon, expected, actual);
    }

    /**
     * A body the pack gave no {@code DIMID} still becomes a world: the loader takes a FREE dimension
     * for it — a real id, not one of vanilla's three and not one ANOTHER BODY OF THE SAME FILE states,
     * wherever in the file that body is written — rather than dropping it or registering it under
     * the invalid sentinel. And the body that does state an id keeps it: it is a world under that id,
     * and the star lists exactly one body there.
     *
     * <p>The file writes the unnumbered body BEFORE {@code StatesTheFirstFreeId}, which states the id
     * the allocator hands out first — the order in which the shipped loader gave that id away. This is
     * the regression guard for that defect: one id names one body in the registry and the star alike,
     * a stated id is honoured, and an allocated id is never a stated one.</p>
     *
     * <p>red-witnessed: 2026-10-03, with {@code XMLPlanetLoader#readPlanetFromNode} at
     * {@code statesADimId(planetNode)} put back to the shipped allocation — every body allocated
     * {@code getNextFreeDim(offset)} at parse time, before the file's stated ids were known — this
     * fails with {@code itsIdTakenByAnother=true}, {@code ownerOf2=Unnumbered} and
     * {@code StatesTheFirstFreeId.dims=[]}. On the shipped tree, before the fix, it failed on
     * production as it stood (2026-10-02, {@code logs/bugs-b-repro3.log}).</p>
     */
    @Test
    public void aPlanetThatStatesNoDimensionIsGivenAFreeOne() throws Exception {
        Reply named = probe("stellurgytest planet named " + UNNUMBERED);
        int[] ids = named.intArray("dims");
        Reply stated = probe("stellurgytest planet named " + STATES_THE_FIRST_FREE_ID);
        Reply owner = probe("stellurgytest planet authored " + FIRST_FREE);
        Reply sol = probe("stellurgytest star get " + STAR_SOL);
        DimList dims = dims();
        // Vanilla's own three ids (overworld 0, nether -1, end 1) and the loader's invalid sentinel
        // are never free; the file's stated ids belong to other bodies.
        List<Integer> taken = new ArrayList<>(Arrays.asList(0, -1, 1, Constants.INVALID_PLANET));
        for (int statedDim : authoredDims) {
            taken.add(statedDim);
        }
        List<String> solUnderFirstFree = new ArrayList<>();
        List<Integer> solDimsOfUnnumbered = new ArrayList<>();
        for (String element : sol.objectArray("planetBodies")) {
            Reply body = Reply.of("star planetBodies", element);
            // the producer always writes `dim` and `name` on every planetBodies element, so a
            // refusal here is a broken probe, never a reading of the world.
            if (body.integer("dim") == FIRST_FREE) {
                solUnderFirstFree.add(body.text("name"));
            }
            // (the producer always writes `name` too — the same reason as above)
            if (UNNUMBERED.equals(body.text("name"))) {
                solDimsOfUnnumbered.add(body.integer("dim"));
            }
        }
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("bodiesNamedUnnumbered", "1");
        expected.put("itsIdTakenByAnother", "false");
        expected.put("itsIdRegisteredAsAWorld", "true");
        expected.put("StatesTheFirstFreeId.dims", Arrays.toString(new int[]{FIRST_FREE}));
        expected.put("ownerOf" + FIRST_FREE, STATES_THE_FIRST_FREE_ID);
        expected.put("Sol.bodiesUnder" + FIRST_FREE, Arrays.asList(STATES_THE_FIRST_FREE_ID).toString());
        expected.put("Sol.listsUnnumberedUnder", Arrays.toString(ids));
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("bodiesNamedUnnumbered", String.valueOf(ids.length));
        actual.put("itsIdTakenByAnother", ids.length == 1 ? String.valueOf(taken.contains(ids[0])) : "n/a");
        actual.put("itsIdRegisteredAsAWorld", ids.length == 1 ? String.valueOf(dims.holds(ids[0])) : "n/a");
        actual.put("StatesTheFirstFreeId.dims", Arrays.toString(stated.intArray("dims")));
        // absence is the answer for `name`: an id that holds no body replies found:false with no
        // name, and the verdict must show that beside the other fields rather than refuse first.
        actual.put("ownerOf" + FIRST_FREE, owner.textOr("name", "nobody"));
        actual.put("Sol.bodiesUnder" + FIRST_FREE, solUnderFirstFree.toString());
        actual.put("Sol.listsUnnumberedUnder", solDimsOfUnnumbered.toString());
        assertEquals("a body the pack gave no DIMID must be ONE world under an id no other body states,"
                + " and the body stating id " + FIRST_FREE + " must own it (taken: " + taken + "): "
                + named + " / " + stated + " / " + owner + " / " + sol + " / " + dims, expected, actual);
    }

    /**
     * Two bodies of one file state the same {@code DIMID}: the EARLIER holds it, the later is not
     * loaded — registered under no id and bound to no star — and the world loads around it. Before
     * this was decided the later body was refused by the registry and still bound to its star, whose
     * id-keyed map then held IT under the id while the registry held the other.
     *
     * <p>The WARN naming both bodies is part of the clause and is NOT asserted: a log line is never a
     * test's source.</p>
     *
     * <p>red-witnessed: 2026-10-03, with {@code DimensionManager#createAndLoadDimensions} at
     * {@code if (!this.registerDimNoUpdate(properties, properties.isNativeDimension))} put back to the
     * shipped unchecked call (its refusal branch and the later {@code refusedBodies.contains(properties)}
     * skip removed), this fails with {@code Sol.bodiesUnder9611=[StatesTheSameId]}.</p>
     */
    @Test
    public void aSecondBodyStatingAHeldIdIsRefusedAndNotBoundToItsStar() throws Exception {
        Reply owner = probe("stellurgytest planet authored " + HELD_TWICE);
        Reply second = probe("stellurgytest planet named " + STATES_THE_SAME_ID);
        Reply sol = probe("stellurgytest star get " + STAR_SOL);
        List<String> solUnderHeld = new ArrayList<>();
        List<Integer> solDimsOfSecond = new ArrayList<>();
        for (String element : sol.objectArray("planetBodies")) {
            Reply body = Reply.of("star planetBodies", element);
            // the producer always writes `dim` and `name` on every planetBodies element, so a
            // refusal here is a broken probe, never a reading of the world.
            if (body.integer("dim") == HELD_TWICE) {
                solUnderHeld.add(body.text("name"));
            }
            // (the producer always writes `name` too — the same reason as above)
            if (STATES_THE_SAME_ID.equals(body.text("name"))) {
                solDimsOfSecond.add(body.integer("dim"));
            }
        }
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("ownerOf" + HELD_TWICE, HOLDS_THE_ID);
        expected.put("StatesTheSameId.dims", "[]");
        expected.put("Sol.bodiesUnder" + HELD_TWICE, Arrays.asList(HOLDS_THE_ID).toString());
        expected.put("Sol.listsStatesTheSameIdUnder", "[]");
        Map<String, String> actual = new LinkedHashMap<>();
        // absence is the answer for `name`: an id that holds no body replies found:false with no
        // name, and the verdict must show that beside the other fields rather than refuse first.
        actual.put("ownerOf" + HELD_TWICE, owner.textOr("name", "nobody"));
        actual.put("StatesTheSameId.dims", Arrays.toString(second.intArray("dims")));
        actual.put("Sol.bodiesUnder" + HELD_TWICE, solUnderHeld.toString());
        actual.put("Sol.listsStatesTheSameIdUnder", solDimsOfSecond.toString());
        assertEquals("the earlier of two bodies stating " + HELD_TWICE + " must hold it, and the later"
                + " be loaded nowhere — not even into its star: " + owner + " / " + second + " / " + sol,
                expected, actual);
    }

    /**
     * A moon refused for a held {@code DIMID} is no longer its parent's moon: the parent does not list
     * that id among its children, since the body under it is not its moon. Unless it IS — two moons of
     * one planet stating one id leave the first holding it, and that one stays the planet's child.
     *
     * <p>red-witnessed: with {@code DimensionManager#createAndLoadDimensions} at
     * {@code detachFromParseParent(refused);} not called (the shape it shipped with), this fails showing
     * {@code Secundus.children=[9611]} — the id of HoldsTheId, a planet of the star (2026-10-06).</p>
     *
     * <p>red-witnessed: with {@code DimensionManager#detachFromParseParent} at
     * {@code if (holder != null && holder.getParentPlanet() == parentId)} never taken, this fails
     * showing {@code Tertius.children=[]} — the first moon, which holds the id, dropped with the
     * refused second (2026-10-06).</p>
     */
    @Test
    public void aRefusedMoonIsNoLongerItsParentsChild() throws Exception {
        DimList dims = dims();
        requireArranged("the two parents and the moon that holds Tertius's id must be registered: " + dims,
                dims.holds(SECUNDUS) && dims.holds(TERTIUS) && dims.holds(TERTIUS_MOON));

        Reply secundus = authored(SECUNDUS);
        Reply tertius = authored(TERTIUS);
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("Secundus.children", "[]");
        expected.put("Tertius.children", Arrays.toString(new int[]{TERTIUS_MOON}));
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("Secundus.children", Arrays.toString(secundus.intArray("children")));
        actual.put("Tertius.children", Arrays.toString(tertius.intArray("children")));
        assertEquals("a parent must list exactly the moons that are its own — not the holder of a refused"
                + " moon's id, and still the first of two moons on one id: " + secundus + " / " + tertius,
                expected, actual);
    }

    /**
     * An atmosphere density or a gravity outside what the game models is brought to the nearest
     * bound, from above and from below — not taken as written, and not rejected.
     *
     * <p>red-witnessed: 2026-10-02, two runs. With {@code XMLPlanetLoader#readPlanetFromNode} at
     * {@code Math.min(Math.max(Integer.parseInt(planetPropertyNode.getTextContent()),
     * DimensionProperties.MIN_ATM_PRESSURE), DimensionProperties.MAX_ATM_PRESSURE)} losing its
     * ceiling and {@code XMLPlanetLoader#readPlanetFromNode} at
     * {@code Math.min(Math.max(Integer.parseInt(planetPropertyNode.getTextContent()),
     * DimensionProperties.MIN_GRAVITY), DimensionProperties.MAX_GRAVITY)} losing its floor, this
     * fails showing {@code Heavy.atmosphereDensity=5000.0} and {@code Light.gravity=-0.2}; with the
     * atmosphere's floor and the gravity's ceiling dropped instead, showing
     * {@code Light.atmosphereDensity=-50.0} and {@code Heavy.gravity=9.0}.</p>
     */
    @Test
    public void atmosphereAndGravityOutsideTheirRangeAreClampedIntoIt() throws Exception {
        DimList dims = dims();
        requireArranged("both out-of-range bodies must be registered before they are read: " + dims,
                dims.holds(HEAVY) && dims.holds(LIGHT));

        Reply heavy = authored(HEAVY);
        Reply light = authored(LIGHT);
        Map<String, Double> expected = new LinkedHashMap<>();
        expected.put("Heavy.atmosphereDensity", (double) DimensionProperties.MAX_ATM_PRESSURE);
        expected.put("Heavy.gravity", (double) (DimensionProperties.MAX_GRAVITY / 100f));
        expected.put("Light.atmosphereDensity", (double) DimensionProperties.MIN_ATM_PRESSURE);
        expected.put("Light.gravity", (double) (DimensionProperties.MIN_GRAVITY / 100f));
        Map<String, Double> actual = new LinkedHashMap<>();
        actual.put("Heavy.atmosphereDensity", heavy.number("atmosphereDensity"));
        actual.put("Heavy.gravity", heavy.number("gravity"));
        actual.put("Light.atmosphereDensity", light.number("atmosphereDensity"));
        actual.put("Light.gravity", light.number("gravity"));
        assertEquals("authored ceiling+1 / 900 and -50 / -20 must land on the modelled bounds: "
                + heavy + " / " + light, expected, actual);
    }

    /**
     * A laser-drill entry written with whitespace around its name and its count resolves to that
     * ore, carries the stated count, and is a COPY of the dictionary's stack: the dictionary's own
     * stack — what every other reader of {@code oreIron} receives — keeps the count it was
     * registered with.
     *
     * <p>red-witnessed: 2026-10-02, three inversions, one run each: with
     * {@code XMLPlanetLoader#readPlanetFromNode} at {@code String oreName = parts[0].trim();}
     * untrimmed this fails with {@code Driller.laserDrillOres=[]}; with
     * {@code XMLPlanetLoader#readPlanetFromNode} at
     * {@code item.setCount(Integer.parseInt(parts[1].trim()));} untrimmed, with
     * {@code [minecraft:iron_ore@0x1]}; with {@code XMLPlanetLoader#readPlanetFromNode} at
     * {@code ItemStack item = ores.get(0).copy();} not copied, with
     * {@code dictionary.oreIron.count=3}.</p>
     */
    @Test
    public void aLaserDrillEntryIsTrimmedCountedAndCopiedOffTheDictionary() throws Exception {
        DimList dims = dims();
        requireArranged("the drilling body must be registered before its ore list is read: " + dims,
                dims.holds(DRILLER));
        Reply dictionary = probe("stellurgytest oredict get oreIron");
        requireArranged("oreIron must have a registered stack for an entry to resolve to: " + dictionary,
                dictionary.integer("entries") > 0);
        Reply prototype = Reply.of("oreIron first", dictionary.object("first"));

        Reply driller = authored(DRILLER);
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("Driller.laserDrillOres",
                Arrays.asList(stack(prototype.text("item"), prototype.integer("meta"), 3)).toString());
        // The count Forge registers a block's ore entry with: OreDictionary.registerOre(String, Block)
        // wraps it in `new ItemStack(block)`, which is one.
        expected.put("dictionary.oreIron.count", "1");
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("Driller.laserDrillOres", stacks(driller).toString());
        actual.put("dictionary.oreIron.count", String.valueOf(prototype.integer("count")));
        assertEquals("' oreIron ; 3 ' must resolve to oreIron x3 without touching the dictionary's"
                + " stack: " + driller + " / " + dictionary, expected, actual);
    }

    /**
     * An ore name the dictionary has RESERVED but holds nothing under — a pack naming an ore whose
     * mod is not installed — is skipped as an entry; the planet carrying it is still a world and its
     * other entries still resolve. (Before this was decided, the empty lookup threw, and the per-planet
     * isolation dropped the whole planet.)
     *
     * <p>red-witnessed: 2026-10-02, with {@code XMLPlanetLoader#readPlanetFromNode} at
     * {@code if (ores.isEmpty())} made never to hold (so the empty list reaches {@code ores.get(0)}),
     * this fails with {@code DrillerUnresolved.registered=false}: the whole planet was dropped.</p>
     */
    @Test
    public void aLaserDrillOreThatResolvesToNothingIsSkippedAndThePlanetKept() throws Exception {
        Reply unresolved = probe("stellurgytest oredict get " + RESERVED_EMPTY_ORE);
        requireArranged("the unresolvable name must be RESERVED and EMPTY in this game, or the case is"
                        + " a different one: " + unresolved + " — reserved-and-empty names here: "
                        + probe("stellurgytest oredict empty"),
                unresolved.bool("exists") && unresolved.integer("entries") == 0);
        Reply gold = probe("stellurgytest oredict get oreGold");
        requireArranged("oreGold must have a registered stack for the sibling entry to resolve to: "
                + gold, gold.integer("entries") > 0);
        Reply goldFirst = Reply.of("oreGold first", gold.object("first"));

        DimList dims = dims();
        Reply driller = authoredOrAbsent(DRILLER_UNRESOLVED);
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("DrillerUnresolved.registered", "true");
        expected.put("DrillerUnresolved.laserDrillOres",
                Arrays.asList(stack(goldFirst.text("item"), goldFirst.integer("meta"), 2)).toString());
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("DrillerUnresolved.registered", String.valueOf(dims.holds(DRILLER_UNRESOLVED)));
        actual.put("DrillerUnresolved.laserDrillOres",
                driller == null ? "absent" : stacks(driller).toString());
        assertEquals("a body naming an unresolvable ore must stay a world, with that entry skipped and"
                + " its resolvable one kept: " + dims + " / " + driller, expected, actual);
    }

    /**
     * A {@code <terrainSource>} is read regardless of case and surrounding whitespace, and its world
     * type without the whitespace around it; a value the game does not know does not cost the pack
     * its planet — the body is kept and generates natively.
     *
     * <p>red-witnessed: 2026-10-02, two inversions, one run each: with
     * {@code XMLPlanetLoader#readPlanetFromNode} at
     * {@code properties.setTerrainSource(TerrainSource.byName(planetPropertyNode.getTextContent().trim()));}
     * calling a strict {@code TerrainSource.valueOf} on the same text, this fails with
     * {@code ModTerrain.registered=false} and {@code BogusTerrain.registered=false} (both planets
     * dropped); with {@code XMLPlanetLoader#readPlanetFromNode} at
     * {@code properties.setTerrainWorldType(planetPropertyNode.getTextContent().trim());} untrimmed,
     * with {@code ModTerrain.terrainWorldType= flat }.</p>
     */
    @Test
    public void aTerrainSourceIsReadTolerantlyAndAnUnknownOneKeepsThePlanet() throws Exception {
        DimList dims = dims();
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("ModTerrain.registered", "true");
        expected.put("ModTerrain.terrainSource", "MOD_WORLDTYPE");
        expected.put("ModTerrain.terrainWorldType", "flat");
        expected.put("BogusTerrain.registered", "true");
        expected.put("BogusTerrain.terrainSource", "NATIVE");
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("ModTerrain.registered", String.valueOf(dims.holds(MOD_TERRAIN)));
        Reply mod = authoredOrAbsent(MOD_TERRAIN);
        actual.put("ModTerrain.terrainSource", mod == null ? "absent" : mod.text("terrainSource"));
        actual.put("ModTerrain.terrainWorldType", mod == null ? "absent" : mod.text("terrainWorldType"));
        actual.put("BogusTerrain.registered", String.valueOf(dims.holds(BOGUS_TERRAIN)));
        Reply bogus = authoredOrAbsent(BOGUS_TERRAIN);
        actual.put("BogusTerrain.terrainSource", bogus == null ? "absent" : bogus.text("terrainSource"));
        assertEquals("' mod_worldtype ' / ' flat ' must read as MOD_WORLDTYPE / flat, and an unknown"
                + " source must leave a NATIVE planet in place: " + dims, expected, actual);
    }

    /**
     * A planet whose author states a weather length runs its OWN weather cycle — that is the only
     * way the stated length can take effect — and a planet whose author states no weather at all
     * keeps the shared one. The flag read is the one {@code WorldProviderPlanet#updateWeather}
     * branches on.
     *
     * <p>red-witnessed: 2026-10-02, two inversions, one run each: with
     * {@code DimensionProperties#updateCustomWorldInfo} at {@code customWorldInfo = !isDefault;}
     * negated, this fails with {@code StatesAWeatherLength.usesCustomWorldInfo=false} (and
     * {@code StatesNoWeather} stays false — a planet stating no weather never reaches that method);
     * with {@code DimensionProperties#usesCustomWorldInfo} at {@code return customWorldInfo;} made to
     * answer {@code true}, with {@code StatesNoWeather.usesCustomWorldInfo=true}.</p>
     */
    @Test
    public void aStatedWeatherLengthEngagesThePlanetsOwnCycleAndNoneLeavesItShared() throws Exception {
        DimList dims = dims();
        requireArranged("both weather bodies must be registered before they are read: " + dims,
                dims.holds(STATES_NO_WEATHER) && dims.holds(STATES_A_WEATHER_LENGTH));

        Reply none = authored(STATES_NO_WEATHER);
        Reply length = authored(STATES_A_WEATHER_LENGTH);
        Map<String, Boolean> expected = new LinkedHashMap<>();
        expected.put("StatesAWeatherLength.usesCustomWorldInfo", true);
        expected.put("StatesNoWeather.usesCustomWorldInfo", false);
        Map<String, Boolean> actual = new LinkedHashMap<>();
        actual.put("StatesAWeatherLength.usesCustomWorldInfo", length.bool("usesCustomWorldInfo"));
        actual.put("StatesNoWeather.usesCustomWorldInfo", none.bool("usesCustomWorldInfo"));
        assertEquals("a stated rainStartLength must engage the planet's own cycle, and no weather"
                + " must leave the shared one: " + length + " / " + none, expected, actual);
    }

    /**
     * A pack's {@code <galaxyGen>} is the configuration the RUNNING universe's generator was built
     * from — read off the generator in force, past the point where the save's schema turned the
     * staged knobs into one. Within it: an authored star table and galaxy table REPLACE the stock
     * ones rather than extending them; a galaxy type's profile is read regardless of case and
     * whitespace; a galaxy type that states only some attributes takes every shape attribute it left
     * out from the SHIPPED spiral, and a weight of one; and an attribute the element leaves out takes
     * the shipped default.
     *
     * <p>red-witnessed: 2026-10-02, two verdicts. The generator verdict: with
     * {@code UniverseRegistry#populate} at
     * {@code reg.attachSchemaGenerator(schema.generator(packGalaxyConfig, galaxy.getPlanetTypes(),}
     * handed {@code null} for the config, this fails with {@code EmptyGalaxyGenerator}. The
     * configuration verdict, three runs: with {@code XMLPlanetLoader#readGalaxyType} at
     * {@code attrInt(node, ATTR_ARMS, stock.armCount)} defaulting to 0 and at
     * {@code attrInt(node, ATTR_WEIGHT, 1)} defaulting to 7, it fails with {@code Thick} at
     * {@code armCount=0, weight=7}; with {@code XMLPlanetLoader#readGalaxyGen} at
     * {@code List<GalaxyGenConfig.StarType> types = new ArrayList<>();} and at
     * {@code List<GalaxyGenConfig.GalaxyType> galaxyTypes = new ArrayList<>();} seeded with the
     * stock tables, and at {@code int minSpacing = attrInt(node, ATTR_MINSPACING, defaults.minSpacing);}
     * defaulting one higher, it fails with the stock star and galaxy types ahead of the authored ones
     * and {@code minSpacing} one over the shipped default.</p>
     */
    @Test
    public void aPacksGalaxyGenIsTheConfigurationTheRunningUniverseUses() throws Exception {
        Reply inForce = probe("stellurgytest space gen-config");
        assertEquals("a file with a <galaxyGen> must run a procedural generator: " + inForce,
                "ClusteredGalaxyGenerator", inForce.text("generator"));
        Reply config = Reply.of("gen-config config", inForce.object("config"));

        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("density", 0.37);
        expected.put("galaxyDensity", 0.61);
        expected.put("minSpacing", (double) GalaxyGenConfig.DEFAULT_MIN_SPACING);
        expected.put("galaxySpacing", (double) GalaxyGenConfig.DEFAULT_GALAXY_SPACING);
        List<Map<String, Object>> starTypes = new ArrayList<>();
        starTypes.add(starType(55, 0.7, 1.3, 3));
        expected.put("starTypes", starTypes);
        GalaxyGenConfig.GalaxyType spiral = GalaxyGenConfig.stockSpiral();
        List<Map<String, Object>> galaxyTypes = new ArrayList<>();
        galaxyTypes.add(galaxyType("Fat Disc", "SPHEROID", 20000, 40000, 0.3, 0, 90, 0.2, 0, 1, 5));
        galaxyTypes.add(galaxyType("Thick", "DISC", spiral.minRadiusLy, spiral.maxRadiusLy, 0.25,
                spiral.armCount, spiral.rotationSpeedKmS, spiral.coreRadiusFraction,
                spiral.minSatellites, spiral.maxSatellites, 1));
        expected.put("galaxyTypes", galaxyTypes);

        Map<String, Object> actual = new LinkedHashMap<>();
        actual.put("density", config.number("density"));
        actual.put("galaxyDensity", config.number("galaxyDensity"));
        actual.put("minSpacing", config.number("minSpacing"));
        actual.put("galaxySpacing", config.number("galaxySpacing"));
        List<Map<String, Object>> actualStars = new ArrayList<>();
        for (String element : config.objectArray("starTypes")) {
            Reply t = Reply.of("starType", element);
            actualStars.add(starType(t.number("temperature"), t.number("minSize"), t.number("maxSize"),
                    t.number("weight")));
        }
        actual.put("starTypes", actualStars);
        List<Map<String, Object>> actualGalaxies = new ArrayList<>();
        for (String element : config.objectArray("galaxyTypes")) {
            Reply t = Reply.of("galaxyType", element);
            actualGalaxies.add(galaxyType(t.text("name"), t.text("profile"), t.number("minRadiusLy"),
                    t.number("maxRadiusLy"), t.number("scaleHeightRatio"), t.number("armCount"),
                    t.number("rotationSpeedKmS"), t.number("coreRadiusFraction"),
                    t.number("minSatellites"), t.number("maxSatellites"), t.number("weight")));
        }
        actual.put("galaxyTypes", actualGalaxies);
        assertEquals("the generator in force must carry the pack's <galaxyGen>: " + inForce,
                expected, actual);
    }

    /**
     * A star's {@code galacticCoord} is declared against the HOME galaxy unless the star names
     * another, and every other galaxy an anchor names is RESERVED in the generator's configuration —
     * its cell holds a galaxy under every seed, so the authored system has somewhere to be.
     *
     * <p>red-witnessed: 2026-10-02, two inversions, one run each: with
     * {@code XMLPlanetLoader#readGalaxyKey} at {@code return GalaxyKey.HOME;} (the unstated branch)
     * answering galaxy {@code 1,0,0}, this fails with {@code Sol.galaxy=1,0,0}; with
     * {@code XMLPlanetLoader#readAllPlanets} at
     * {@code if (coupling.galaxyGenConfig != null && !coupling.declaredGalaxies.isEmpty())} negated on
     * its second half, with {@code reservedGalaxies=[home]}.</p>
     */
    @Test
    public void aStarsAnchorIsDeclaredAgainstTheHomeGalaxyUnlessItNamesAnother() throws Exception {
        Reply sol = probe("stellurgytest star get " + STAR_SOL);
        Reply faraway = probe("stellurgytest star get " + STAR_FARAWAY);
        requireArranged("the two authored stars must hold the ids the file's order gives them: "
                        + sol + " / " + faraway,
                sol.has("name") && "Sol".equals(sol.text("name"))
                        && faraway.has("name") && "Faraway".equals(faraway.text("name")));

        Reply solAnchor = probe("stellurgytest space anchor " + STAR_SOL);
        Reply farAnchor = probe("stellurgytest space anchor " + STAR_FARAWAY);
        Reply inForce = probe("stellurgytest space gen-config");
        Reply config = Reply.of("gen-config config", inForce.object("config"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("Sol.declared", "true");
        expected.put("Sol.galaxy", "home");
        expected.put("Faraway.declared", "true");
        expected.put("Faraway.galaxy", FARAWAY_GALAXY);
        expected.put("reservedGalaxies", Arrays.asList("home", FARAWAY_GALAXY).toString());
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("Sol.declared", String.valueOf(solAnchor.bool("declared")));
        actual.put("Faraway.declared", String.valueOf(farAnchor.bool("declared")));
        // absence is the answer for `galaxy`: a star whose anchor was never declared replies
        // declared:false with no galaxy at all, and the verdict must SHOW that as "absent" beside the
        // other fields rather than refuse before printing them.
        actual.put("Sol.galaxy", solAnchor.textOr("galaxy", "absent"));
        actual.put("Faraway.galaxy", farAnchor.textOr("galaxy", "absent"));
        actual.put("reservedGalaxies", Arrays.asList(config.textArray("reservedGalaxies")).toString());
        assertEquals("an unqualified anchor must be the home galaxy's and a named galaxy must be"
                + " reserved: " + solAnchor + " / " + farAnchor + " / " + inForce, expected, actual);
    }

    /**
     * An authored star holds exactly what its pack declares: one written with no body and a count of
     * zero is its star alone, and one written with a body and a count of N holds that body and N major
     * worlds derived around it, with the moons and belts that derivation brings — nothing else is
     * attributed to either. (Measured 2026-10-03 for this file's count of 3: 2 planets, 1 giant, 2
     * moons, 2 belts.)
     *
     * <p>Fails if {@code UniverseRegistry#withDerivedRetinue} stops deriving exactly the count the
     * star carries or starts deriving for a star that asked for none, or {@code DimensionManager}
     * stops carrying {@code numPlanets + numGasGiants} as that count.</p>
     *
     * <p>Read where the registry answers it: the system the star's placed cell belongs to
     * ({@code space cell-info} on the cell {@code space anchor} reports), by body. A derived world has no
     * dimension until someone lands, so it is told from the declared body by its dimension.</p>
     *
     * <p>Does NOT see the procedural field around these stars, nor a derived world being realized.</p>
     *
     * <p>red-witnessed: 2026-10-03, three inversions, one run each: with
     * {@code UniverseRegistry#withDerivedRetinue} at {@code if (asked <= 0 || generator == null)}
     * always true, this fails with {@code Counted=… majors=0}; with
     * {@code int asked = star.getMaxRetinueBodies();} reading {@code Math.max(1, …)}, with
     * {@code Faraway=… majors=1}; with {@code DimensionManager#createAndLoadDimensions} at
     * {@code int retinue = loader.getMaxNumPlanets(star) + loader.getMaxNumGasGiants(star);} dropping
     * the giants, with {@code Counted=… majors=2}.</p>
     */
    @Test
    public void anAuthoredStarHoldsWhatItsPackDeclaresAndNothingElse() throws Exception {
        Reply counted = probe("stellurgytest star get " + STAR_COUNTED);
        requireArranged("the counted star must hold the id the file's order gives it: " + counted,
                counted.has("name") && "Counted".equals(counted.text("name")));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("Faraway", composition(new int[0], 0));
        expected.put("Counted", composition(new int[] {COUNTED_DECLARED},
                COUNTED_PLANETS + COUNTED_GAS_GIANTS));
        Map<String, String> actual = new LinkedHashMap<>();
        Reply faraway = systemOf(STAR_FARAWAY);
        Reply countedSystem = systemOf(STAR_COUNTED);
        actual.put("Faraway", compositionOf(faraway));
        actual.put("Counted", compositionOf(countedSystem));
        assertEquals("an authored system holds its declared bodies and the count its pack asked for,"
                + " nothing else: " + faraway + " / " + countedSystem, expected, actual);
    }

    // ---- instruments -----------------------------------------------------------------------------

    /** The system the registry anchors star {@code starId} at, as {@code space cell-info} reads it. */
    private Reply systemOf(int starId) throws Exception {
        Reply anchor = probe("stellurgytest space anchor " + starId);
        requireArranged("star " + starId + " must be placed somewhere: " + anchor, anchor.has("cell"));
        return probe("stellurgytest space cell-info " + anchor.text("cell")).requireOk("read star "
                + starId + "'s system");
    }

    /**
     * One star, the declared bodies' dimensions, how many derived MAJOR worlds (a planet or a giant
     * with no dimension yet), and whether everything else derived is what a derived world brings with
     * it — its moons — or a belt the same derivation lays around the system.
     */
    private static String composition(int[] declaredDims, int derivedMajors) {
        StringBuilder dims = new StringBuilder();
        for (int dim : declaredDims) {
            dims.append(dims.length() > 0 ? "," : "").append(dim);
        }
        return "stars=1 declared=[" + dims + "] majors=" + derivedMajors + " restIsMoonsAndBelts=true";
    }

    private static String compositionOf(Reply system) {
        int stars = 0;
        int majors = 0;
        boolean restIsMoonsAndBelts = true;
        StringBuilder dims = new StringBuilder();
        for (String element : system.objectArray("bodies")) {
            Reply body = Reply.of("cell-info [bodies]", element);
            String kind = body.text("kind");
            int dim = body.integer("dim");
            if ("STAR".equals(kind)) {
                stars++;
            } else if (dim != dev.stannismod.stellurgy.api.Constants.INVALID_PLANET) {
                dims.append(dims.length() > 0 ? "," : "").append(dim);
            } else if ("PLANET".equals(kind) || "GAS_GIANT".equals(kind)) {
                majors++;
            } else {
                restIsMoonsAndBelts &= "MOON".equals(kind) || "ASTEROID_BELT".equals(kind);
            }
        }
        return "stars=" + stars + " declared=[" + dims + "] majors=" + majors
                + " restIsMoonsAndBelts=" + restIsMoonsAndBelts;
    }

    private Reply probe(String command) throws Exception {
        return Reply.of(command, String.join("\n", world.harness.client().execute(command)));
    }

    private DimList dims() throws Exception {
        return DimList.of(String.join("\n", world.harness.client().execute("stellurgytest dim list")));
    }

    /** A body this file states, which the arrangement requires to exist. */
    private Reply authored(int dim) throws Exception {
        Reply reply = probe("stellurgytest planet authored " + dim);
        requireArranged("dimension " + dim + " must hold a body before its fields are read: " + reply,
                reply.bool("found"));
        return reply;
    }

    /** A body whose EXISTENCE is part of the verdict: {@code null} when the server holds none. */
    private Reply authoredOrAbsent(int dim) throws Exception {
        Reply reply = probe("stellurgytest planet authored " + dim);
        return reply.bool("found") ? reply : null;
    }

    private static String stack(String item, int meta, int count) {
        return item + "@" + meta + "x" + count;
    }

    private static List<String> stacks(Reply body) {
        List<String> out = new ArrayList<>();
        for (String element : body.objectArray("laserDrillOres")) {
            Reply ore = Reply.of("laserDrillOres", element);
            out.add(stack(ore.text("item"), ore.integer("meta"), ore.integer("count")));
        }
        return out;
    }

    private static Map<String, Object> starType(double temperature, double minSize, double maxSize,
                                                double weight) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("temperature", temperature);
        m.put("minSize", minSize);
        m.put("maxSize", maxSize);
        m.put("weight", weight);
        return m;
    }

    private static Map<String, Object> galaxyType(String name, String profile, double minRadiusLy,
                                                  double maxRadiusLy, double scaleHeightRatio,
                                                  double armCount, double rotationSpeedKmS,
                                                  double coreRadiusFraction, double minSatellites,
                                                  double maxSatellites, double weight) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("profile", profile);
        m.put("minRadiusLy", minRadiusLy);
        m.put("maxRadiusLy", maxRadiusLy);
        m.put("scaleHeightRatio", scaleHeightRatio);
        m.put("armCount", armCount);
        m.put("rotationSpeedKmS", rotationSpeedKmS);
        m.put("coreRadiusFraction", coreRadiusFraction);
        m.put("minSatellites", minSatellites);
        m.put("maxSatellites", maxSatellites);
        m.put("weight", weight);
        return m;
    }
}
