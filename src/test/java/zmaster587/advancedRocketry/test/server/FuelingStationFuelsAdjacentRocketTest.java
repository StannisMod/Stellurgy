package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.FluidStored;
import zmaster587.advancedRocketry.test.Reply;
import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import org.junit.Test;

import java.util.List;

import zmaster587.advancedRocketry.test.FixtureSite;

import static org.junit.Assert.assertTrue;

/**
 * fueling-station &rArr; rocket fuel transfer.
 *
 * <p>Pins the {@link zmaster587.advancedRocketry.tile.infrastructure.TileFuelingStation}
 * {@code performFunction} cause-effect: with a fueling station linked to an
 * assembled {@link zmaster587.advancedRocketry.entity.EntityRocket}, containing
 * rocketFuel in its tank and enough power, force-ticking the station must
 * drain the tank AND increase the rocket's {@code LIQUID_MONOPROPELLANT}
 * fuel amount (matched accounting).</p>
 *
 * <p>Steps:</p>
 * <ol>
 *   <li>Build a rocket fixture at (X_ROCKET, ...) via {@code /artest fixture rocket}
 *       and assemble it into an {@code EntityRocket}.</li>
 *   <li>Place a {@code fuelingStation} adjacent to the rocket pad area.</li>
 *   <li>Link the station &rarr; rocket via {@code /artest infra link}.</li>
 *   <li>Inject {@code rocketFuel} into the station's tank and feed it
 *       RF via {@code /artest energy inject} so {@code canPerformFunction}
 *       returns true.</li>
 *   <li>Force-tick the station; assert station tank dropped and the rocket's
 *       primary-fuel amount rose (matched accounting, modulo capacity clamp).</li>
 * </ol>
 *
 * <p>A regression that breaks the {@code addFuelAmount} dispatch, the
 * fuel-fluid matching in {@code performFunction}, or the
 * {@code canPerformFunction} guard would fail this test.</p>
 */
public class FuelingStationFuelsAdjacentRocketTest extends AbstractHeadlessServerTest {

    /**
     * The fuel capacity a fresh craft must have, and the tank the station must hold, in mB.
     *
     * <p>Both are the test's own bars on an ARRANGEMENT: the transfer legs below are about fuel
     * moving, so a craft with no capacity or a station with an empty tank would make them vacuous.
     * A thousand millibuckets is a fraction of either.</p>
     */
    private static final int AMPLE_FUEL_MB = 1000;

    /** Rocket pad center coords — isolated patch (no collisions). */
    private static final int RX = 2800;
    private static final int RY = FixtureSite.OPEN_AIR_Y;
    private static final int RZ = 2800;
    /** Fueling station placed 8 blocks away — within max link distance. */
    private static final int FX = RX - 8;
    private static final int FY = RY + 1;
    private static final int FZ = RZ;

    private static final String ENTITY_ID = "entityId";
    /** The fuel entry this station fills. The reply is a MAP of fuel types, so the entry is
     *  read as a nested object rather than matched across two adjacent fields. */
    private static final String MONO = "LIQUID_MONOPROPELLANT";

    private static Reply monoEntry(String fuelReply) {
        String fuels = Reply.of("artest rocket fuel", fuelReply).object("fuels");
        String entry = fuels == null ? null : Reply.of(fuels).object(MONO);
        return entry == null ? null : Reply.of(entry);
    }
    /** What the station is filled with above, and therefore what its drop is measured in. Asked by
     *  NAME rather than "the first tank", because a station holding two fluids has two. */
    private static final String STATION_FUEL = "rocketfuel";

    @Test
    public void stationDrainsTankAndRocketFuelRisesAfterLinkAndTick() throws Exception {
        // ─── 0+1. The volume is EMPTY, then the craft is built and assembled in it ─────
        //
        // What stood here was three things the shared builder now does in one call, and the reason
        // the first of them existed is worth keeping: natural overworld terrain poking into the
        // scan's bbCache volume confuses scanRocket's component detection, so the fuel-tank count
        // came out biome-dependent and the rocket assembled with cap=0. That was mitigated by a
        // chunk warmup plus a fill; in the open-air band there is no terrain to poke, the clear
        // ASSERTS instead of digging, and its fill force-loads the same chunks the warmup did.
        //
        // The builder position is the fixture's OWN answer now. It was three arithmetic
        // expressions — `RX+2, RY+1, RZ-1` — under a comment saying where the fixture "places" it:
        // a copy of production's layout kept in a test, which goes silently wrong the day the
        // layout moves.
        String assemble = zmaster587.advancedRocketry.test.RocketFixture.assembleAt(
                FixtureSite.openAir(0, RX, RZ), cmd -> join(client().execute(cmd)),
                "simple", 2, 10,
                "the craft the fueling station fills stands in this volume");
        assertTrue("rocket assemble probe errored: " + assemble,
                Reply.of(assemble).ok()
                        && ("SUCCESS".equals(Reply.of(assemble).text("status"))
                                || "ALREADY_ASSEMBLED".equals(Reply.of(assemble).text("status"))));
        Reply assembled = Reply.of("artest rocket assemble", assemble);
        assertTrue("could not parse entityId: " + assemble, assembled.has(ENTITY_ID));
        int rocketId = assembled.integer(ENTITY_ID);

        // ─── 2. Place fueling station + read initial rocket fuel ───────
        String placeFs = join(client().execute(
                "artest place 0 " + FX + " " + FY + " " + FZ + " advancedrocketry:fuelingStation"));
        assertTrue("fuelingStation place failed: " + placeFs,
                Reply.of(placeFs).bool("placed"));

        String preFuel = join(client().execute("artest rocket fuel " + rocketId));
        Reply preMono = monoEntry(preFuel);
        assertTrue("rocket fuel probe missing LIQUID_MONOPROPELLANT entry: " + preFuel,
                preMono != null);
        int initialFuel = preMono.integer("amount");
        int fuelCapacity = preMono.integer("capacity");
        assertTrue("fresh rocket should have ample mono-propellant capacity: cap=" + fuelCapacity
                        + " response=" + preFuel,
                fuelCapacity > AMPLE_FUEL_MB);

        // ─── 3. Link station -> rocket ──────────────────────────────────
        String link = join(client().execute(
                "artest infra link 0 " + FX + " " + FY + " " + FZ + " " + rocketId));
        assertTrue("infra link failed: " + link, Reply.of(link).ok());

        // ─── 4. Fluid + power into station ─────────────────────────────
        // rocketFuel is the canonical LIQUID_MONOPROPELLANT in
        // ARConfiguration.registerFuel. Inject 8 000 mB (large enough that
        // the per-tick drain consumes only a fraction).
        // Forge's FluidRegistry stores names case-sensitively as registered;
        // AR registers the fluid under "rocketFuel". If the lookup misses
        // (different Forge variant or test profile), retry with the lower-
        // cased form before declaring the inject broken.
        String inject = join(client().execute(
                "artest fluid inject 0 " + FX + " " + FY + " " + FZ + " rocketFuel 8000"));
        // The refusal itself, compared. As a substring it was also satisfied by the `name` echo
        // this verb writes beside it, and by any other reply quoting the phrase.
        if (Reply.of("artest fluid inject", inject).refusedWith("fluid not registered")) {
            inject = join(client().execute(
                    "artest fluid inject 0 " + FX + " " + FY + " " + FZ + " rocketfuel 8000"));
        }
        assertTrue("fluid inject failed: " + inject, Reply.of(inject).ok());

        // Charge RF — fueling station consumes 30 RF per operation; 100 000
        // RF is enough for many ticks.
        String energy = join(client().execute(
                "artest energy inject 0 " + FX + " " + FY + " " + FZ + " 100000"));
        assertTrue("energy inject failed: " + energy, Reply.of(energy).ok());

        // Read tank before tick — pin the baseline.
        String preTank = join(client().execute(
                "artest fluid stored 0 " + FX + " " + FY + " " + FZ));
        assertTrue("station must report fluid present: " + preTank,
                Reply.of(preTank).bool("hasFluid"));
        // Summed across the station's tanks: the amount is a TANK's field, not the reply's.
        int initialTank = FluidStored.of(preTank).amountOf(STATION_FUEL);
        assertTrue("station tank must be at least 1 000 mB before tick: " + initialTank
                        + " response=" + preTank,
                initialTank >= AMPLE_FUEL_MB);

        // ─── 5. Force-tick station -> drains tank + fills rocket ────────
        // 200 ticks via the clock-advancing variant: TileFuelingStation
        // gates the transfer on `worldTime % OP_THROTTLE_TICKS == 0`, so a
        // frozen-clock force-tick would either never or always pass that gate
        // depending on the start time. force-tick-clock advances world time by
        // one per update(), letting the throttle modulus cycle as in real play.
        String tick = join(client().execute(
                "artest tile force-tick-clock 0 " + FX + " " + FY + " " + FZ + " 200"));
        assertTrue("station force-tick errored: " + tick,
                Reply.of(tick).ok());

        // ─── 6. Verify both endpoints of the matched-accounting claim ───
        String postTank = join(client().execute(
                "artest fluid stored 0 " + FX + " " + FY + " " + FZ));
        int finalTank = FluidStored.of(postTank).amountOf(STATION_FUEL);
        int tankDrop = initialTank - finalTank;
        assertTrue("station tank must drop after fueling-station tick burst "
                        + "(initial=" + initialTank + " final=" + finalTank
                        + " response=" + postTank + ")",
                tankDrop > 0);

        String postFuel = join(client().execute("artest rocket fuel " + rocketId));
        Reply postMono = monoEntry(postFuel);
        assertTrue("post-tick rocket fuel probe missing MONO entry: " + postFuel, postMono != null);
        int finalFuel = postMono.integer("amount");
        int fuelGain = finalFuel - initialFuel;
        assertTrue("rocket LIQUID_MONOPROPELLANT must increase after station tick "
                        + "(initial=" + initialFuel + " final=" + finalFuel
                        + " response=" + postFuel + ")",
                fuelGain > 0);
    }

    private static String join(List<String> resp) {
        return String.join("\n", resp);
    }
}
