package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.AssembledCraft;
import dev.stannismod.stellurgy.test.ConfigFlag;
import dev.stannismod.stellurgy.test.Disposal;
import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.RocketFixture;
import dev.stannismod.stellurgy.test.ScenarioRockets;
import dev.stannismod.stellurgy.test.ShipBirth;
import dev.stannismod.stellurgy.test.contract.Pins;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Disableability contract for {@code rocketRequireFuel} at rocket-assembly time.
 *
 * <p>{@code rocketRequireFuel=false} means "fuel is not required to fly". The
 * player-facing promise is that a valid rocket (engines + guidance) then
 * assembles regardless of fuel adequacy — the assembly fuel-capacity gate is
 * skipped entirely.</p>
 *
 * <p>This pins a regression introduced by the weight-system merge: it added a
 * {@code getBaseFuelRate() <= 0 -> NOFUEL} guard to {@code hasEnoughFuel}. With
 * {@code rocketRequireFuel=false}, {@link
 * dev.stannismod.stellurgy.api.StatsRocket#getBaseFuelRate} returns 0 by
 * design (the rocket burns no fuel), so the new guard turned every
 * {@code rocketRequireFuel=false} build into {@code NOFUEL} — no number of fuel
 * tanks could satisfy it (the gate fails on the engine fuel <i>rate</i>, not on
 * tank capacity). Before the merge the same path divided by that zero rate and
 * accidentally passed via a {@code +Infinity} burn time.</p>
 *
 * <p>The {@code simple} fixture assembles to SUCCESS with {@code rocketRequireFuel=true}; the
 * contract here is that flipping the flag off does not break that. The class also holds the other
 * fuel-at-assembly contract: the climb-to-orbit check is a ROCKET's, never a ship's. Each scenario
 * states the flag it needs and puts back what it found — the shared harness world starts with it
 * off.</p>
 */
public class RocketRequireFuelDisableAssemblesTest extends AbstractSharedServerTest {

    private static final String STATUS = "status";

    private final ScenarioRockets rockets = ScenarioRockets.of(commands(), evictionReports());

    /** The server's log, stepped on the world the crafts are built in, for the ship's naming. */
    private final Events events =
            new Events(c -> read(c).raw(), t -> GameTicks.advanceWorld(client(), 0, t), evictionReports());

    /** The ships this scenario's presses made, by the physics id their naming announced. */
    private final java.util.List<String> ships = new java.util.ArrayList<>();

    @After
    public void disposeOfThisScenariosCrafts() throws Exception {
        try {
            for (String ship : ships) {
                Disposal.ship(c -> arrange(c).raw(), events, 0, ship);
            }
        } finally {
            ships.clear();
            rockets.disposeStanding();
        }
    }

    /** Lay and press {@code variant} at {@code site}, holding every rocket the press made; the raw reply. */
    private String press(FixtureSite site, String variant, String what) throws Exception {
        int[] builder = RocketFixture.placeAt(site, cmd -> String.join("\n", client().arrange(cmd)),
                variant, 2, 10, what);
        long mark = rockets.mark();
        String reply = RocketFixture.assembleBuilt(site, cmd -> String.join("\n", client().act(cmd)), builder);
        rockets.allBornAt(mark, builder);
        return reply;
    }

    /** Build the simple fixture at the given pad and return the raw assemble response. */
    private String buildAndAssemble(FixtureSite site) throws Exception {
        final int baseX = site.x, baseZ = site.z;
        int cx1 = (baseX - 2) >> 4, cz1 = (baseZ - 2) >> 4;
        int cx2 = (baseX + 7) >> 4, cz2 = (baseZ + 7) >> 4;
        client().arrange("stellurgytest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2);
        // FIRST link: the volume this craft is built and flown in is EMPTY. The site
        // stands in open air, so this ASSERTS rather than digs - anything standing here
        // means the arrangement is wrong, and it is said now instead of arriving many
        // links later wearing some mechanic's name.
        return press(site, "simple", "the craft is built and flown in this volume");
    }

    private static String status(String assembleResponse) {
        Reply mReply = Reply.of(assembleResponse);
        return mReply.has(STATUS) ? mReply.text(STATUS) : "<none>";
    }

    /**
     * With fuel required, a ROCKET is told whether its tanks carry it to its world's orbit line — a
     * forecast it builds with, never a refusal — and a SHIP built round a flight computer is not held
     * to that climb at all.
     *
     * <p>This fails if production breaks the contract that <b>{@code TileRocketAssemblingMachine}
     * forecasts the climb to orbit for a rocket only</b>: a rocket's one flight is that climb, while a
     * ship is flown and is a legitimate craft for flights that never leave the planet. The rocket leg is
     * the control — it shows the forecast is made in this world (the overworld's line is its body's,
     * 100 000 blocks, out of reach of four tanks), so the ship's empty forecast is the exemption and not
     * an empty check.</p>
     *
     * Pins INV-RASM-13 (the fuel checks at assembly are a ROCKET's only; the reach check is a forecast).
     */
    @Pins("INV-RASM-13")
    @RedWitness(member = "TileRocketAssemblingMachine#scanRocket", at = "ascentForecast = scannedFlightComputerPos == null && status == ErrorCodes.SUCCESS",
            replacedBy = "ascentForecast = status == ErrorCodes.SUCCESS", fails = "INV-RASM-13 a ship", on = "2026-10-17")
    @Test
    public void aShipIsNotHeldToTheClimbToOrbitThatARocketIs() throws Exception {
        try (ConfigFlag fuelRequired = ConfigFlag.set(commands(), "rocketRequireFuel", true)) {
            // The fixtures' own extent: two blocks round the pad, ten above it.
            String rocket = press(FixtureSite.openAir(0, 3520, 3400), "with-fluid-cargo",
                    "the rocket is built in this volume");
            assertTrue("INV-RASM-13 a rocket short of the climb still builds: " + rocket, Reply.of(rocket).ok());
            String rocketForecast = Reply.of(rocket).object("ascentForecast");
            assertTrue("control: the rocket's scan must forecast its climb, or the ship below proves nothing: "
                    + rocket, rocketForecast != null);
            assertFalse("control: four tanks do not reach a 100 000-block line, and the forecast must say so: "
                    + rocket, Reply.of(rocketForecast).bool("reaches"));

            FixtureSite shipSite = FixtureSite.openAir(0, 3580, 3400);
            int[] shipBuilder = RocketFixture.placeAt(shipSite, c -> String.join("\n", client().arrange(c)),
                    "with-pilot-deck", 2, 10, "the ship is built in this volume");
            long mark = events.markInstrumented();
            String ship = RocketFixture.assembleBuilt(shipSite, c -> String.join("\n", client().act(c)),
                    shipBuilder);
            assertTrue("a ship must assemble with fuel required even though its tanks cannot reach "
                    + "orbit: " + ship, Reply.of(ship).ok());
            assertTrue("INV-RASM-13 a ship is not held to the climb, so its scan forecasts none: " + ship,
                    Reply.of(ship).object("ascentForecast") == null);
            ShipBirth birth = ShipBirth.ofPress(events, mark, 0, shipBuilder, ship,
                    AssembledCraft.LINK_BUDGET_TICKS, "the ship assembled with fuel required");
            ships.add(birth.physicsId);
            for (String born : birth.rocketsBorn) {
                rockets.holdBorn(born);
            }
            assertTrue("…and it must be a SHIP, not a rocket entity: the press's assembler wrote "
                    + birth.rocketsBorn + "; " + ship, birth.rocketsBorn.isEmpty());
        }
    }

    /** What is poured into one of the rocket's tanks, millibuckets: any amount at all is fluid in a tank. */
    private static final int POURED_MB = 1;

    /**
     * A ROCKET whose fuel tank holds fluid is refused, by name, and nothing is built: a rocket burns its
     * stats' propellant, loaded at the fuelling station, so a tank block's contents would ride as weight no
     * engine draws (INV-RASM-15). The control is the same rocket laid with its tanks as they come, which
     * builds — so the refusal is the poured millibucket's, not the fixture's.
     *
     * <p>Seen: the press's own verdict and the assembler's {@code rocket_born} records at its address. Not
     * seen: the unmanned-vehicle assembler's copy of the same check.</p>
     */
    @Pins("INV-RASM-15")
    @RedWitness(member = "TileRocketAssemblingMachine#scanRocket",
            at = "} else if (scannedFlightComputerPos == null && fluidInFuelTanks) {",
            replacedBy = "} else if (scannedFlightComputerPos == null && false) {",
            fails = "INV-RASM-15 a rocket whose tank holds fluid", on = "2026-10-18")
    @Test
    public void aRocketWhoseFuelTanksHoldFluidIsRefused() throws Exception {
        FixtureSite controlSite = plot().site();
        int[] controlBuilder = RocketFixture.placeAt(controlSite, c -> String.join("\n", client().arrange(c)),
                "simple", 2, 10, "the control rocket is built in this volume");
        long controlMark = rockets.mark();
        String control = RocketFixture.assembleBuilt(controlSite, c -> String.join("\n", client().act(c)),
                controlBuilder);
        java.util.List<String> controlBorn = rockets.allBornAt(controlMark, controlBuilder);
        assertTrue("control: the rocket with its tanks as laid must build, or the refusal below proves nothing: "
                + control + " " + controlBorn, Reply.of(control).ok() && controlBorn.size() == 1);

        FixtureSite site = plot().siteAt(dev.stannismod.stellurgy.test.Plot.FIXTURE_INSET
                + dev.stannismod.stellurgy.test.Plot.SIZE / 2, dev.stannismod.stellurgy.test.Plot.FIXTURE_INSET);
        site.makeRoom(c -> arrange(c).raw(), 2, 10, "the poured rocket is built in this volume");
        Reply fixture = arrange("stellurgytest fixture rocket " + site.dim + " " + site.x + " " + site.y + " "
                + site.z + " simple");
        int[][] tanks = fixture.blockPosArray("fuelTanks");
        int[] builder = fixture.blockPos("builderPos");
        dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged(
                "the rocket must be laid with its fuel tanks: " + fixture, fixture.ok() && tanks.length > 0 && builder != null);
        Reply pour = arrange("stellurgytest fluid inject " + site.dim + " " + tanks[0][0] + " " + tanks[0][1] + " "
                + tanks[0][2] + " hydrazine " + POURED_MB);
        dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged(
                "one tank must take the poured hydrazine: " + pour, pour.ok() && pour.integer("filled") == POURED_MB);

        long mark = rockets.mark();
        String refused = RocketFixture.assembleBuilt(site, c -> String.join("\n", client().act(c)), builder);
        java.util.List<String> born = rockets.allBornAt(mark, builder);
        assertTrue("INV-RASM-15 a rocket whose tank holds fluid is refused by name: " + refused,
                !Reply.of(refused).ok() && "FLUIDINFUELTANKS".equals(status(refused)));
        assertTrue("INV-RASM-15 a rocket whose tank holds fluid is not built: " + born, born.isEmpty());
    }

    /** Pins INV-RASM-06 (with rocketRequireFuel off a valid structure still assembles). */
    @Pins("INV-RASM-06")
    @Test
    public void validRocketAssemblesWhenFuelNotRequired() throws Exception {
        try (ConfigFlag restored = ConfigFlag.set(commands(), "rocketRequireFuel", true)) {
            // Positive control: the simple fixture assembles with fuel required.
            // The assemble probe reports "ok":true only when the SCAN status was
            // SUCCESS; the "status" field it echoes is the POST-assemble status
            // (ALREADY_ASSEMBLED), so we gate on "ok":true, not status==SUCCESS.
            String on = buildAndAssemble(FixtureSite.openAir(0, 3400, 3400));
            assertTrue("simple fixture must assemble on rocketRequireFuel=true (scan SUCCESS): " + on,
                    Reply.of(on).ok());

            // Contract: flipping fuel off must NOT block assembly. Pre-fix the
            // scan returned NOFUEL (the regression) and "ok":true was absent.
            assertTrue(Reply.of(arrange("stellurgytest config set rocketRequireFuel false").raw()).ok());
            String off = buildAndAssemble(FixtureSite.openAir(0, 3460, 3400));
            assertTrue("with rocketRequireFuel=false a valid rocket must still assemble "
                    + "(no fuel-adequacy gate); scan status was " + status(off) + ": " + off,
                    Reply.of(off).ok());
        }
    }
}
