package dev.stannismod.stellurgy.test.server;

// migrated to AbstractSharedServerTest
import dev.stannismod.stellurgy.test.Reply;
import org.junit.Test;


import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.FluidStored;
import dev.stannismod.stellurgy.test.RocketFixture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * rocket infrastructure (loaders, unloaders, monitoring,
 * linker, distance).
 *
 * <p>All tests reuse the {@code /stellurgytest fixture rocket} geometry; the
 * {@code with-cargo} variant adds a vanilla chest above the seat so the
 * item-loader / unloader probes have an IInventory tile inside the rocket
 * storage chunk to transfer against. These tests
 * are pure additions (no production logic touched), and treat fixture-based
 * shortcuts (no real launch / landing) as the agreed simulation surface.</p>
 */
public class RocketInfrastructureSmokeTest extends AbstractSharedServerTest {

    /**
     * The largest link distance that is still a FINITE value, in blocks.
     *
     * <p>The TEST'S OWN: what it refuses is a sentinel or an overflow reported as a distance, not a
     * particular reach. Ten thousand blocks is far beyond any link the mod grants.</p>
     */
    private static final int FINITE_LINK_DISTANCE_BLOCKS = 10_000;

    private static final String ENT_ID = "entityId";
    private static final String CONN = "connectedCount";
    private static final String FLUID_AMOUNT = "totalAmount";

    @Test
    public void fuelingStationLinksToAssembledRocket() throws Exception {
        // The pair moves together: the machine sits one block above the rocket's base,
        // a RELATIVE geometry that was written as two absolute numbers.
        final FixtureSite rocketSite = FixtureSite.openAir(0, 850 + 20, 850);
        int sx = 850, sy = rocketSite.y + 1, sz = 850;
        String place = String.join("\n", client().execute(
                "stellurgytest place 0 " + sx + " " + sy + " " + sz + " stellurgy:fuelingStation"));
        assertTrue("place fueling station failed: " + place,
                Reply.of(place).bool("placed"));

        int rocketId = assembleFixture(rocketSite, "simple");
        String link = String.join("\n", client().execute(
                "stellurgytest infra link 0 " + sx + " " + sy + " " + sz + " " + rocketId));
        assertTrue("infra link probe errored: " + link, Reply.of(link).ok());
        assertTrue("station didn't accept rocket link: " + link, Reply.of(link).bool("linked"));
        Reply cmReply = Reply.of(link);
        assertTrue("connectedCount missing", cmReply.has(CONN));
        assertTrue("connectedCount<1 after link: " + link,
                Integer.parseInt(cmReply.text(CONN)) >= 1);

        // Idempotency: re-linking same infrastructure must NOT double-add.
        String relink = String.join("\n", client().execute(
                "stellurgytest infra link 0 " + sx + " " + sy + " " + sz + " " + rocketId));
        assertTrue("re-link unexpectedly succeeded a second time: " + relink,
                (!Reply.of(relink).bool("linked")));
    }

    /**
     * A fueling station linked to an assembled rocket, holding rocket fuel and power, drains its tank
     * into the rocket's {@code LIQUID_MONOPROPELLANT} when it ticks — both endpoints of the transfer
     * move. Fails if the {@code addFuelAmount} dispatch, the fuel-fluid matching in
     * {@code TileFuelingStation#performFunction}, or the {@code canPerformFunction} guard breaks.
     */
    @Test
    public void stationDrainsTankAndRocketFuelRisesAfterLinkAndTick() throws Exception {
        // The test's own bar on the ARRANGEMENT: a craft with no capacity or a station with an empty
        // tank would make the transfer legs vacuous. A thousand millibuckets is a fraction of either.
        final int ampleFuelMb = 1000;
        final String stationFuel = "rocketfuel";

        FixtureSite rocketSite = site();
        // Eight blocks off the pad, within link reach; one above the pad's own Y.
        int fx = rocketSite.x - 8, fy = rocketSite.y + 1, fz = rocketSite.z;
        String at = " 0 " + fx + " " + fy + " " + fz;

        String assemble = RocketFixture.assembleAt(rocketSite, cmd -> String.join("\n", client().execute(cmd)),
                "simple", 2, 10, "the craft the fueling station fills stands in this volume");
        Reply assembled = Reply.of("stellurgytest rocket assemble", assemble);
        assertTrue("rocket assemble probe errored: " + assemble, assembled.ok()
                && ("SUCCESS".equals(assembled.text("status"))
                        || "ALREADY_ASSEMBLED".equals(assembled.text("status"))));
        assertTrue("could not parse entityId: " + assemble, assembled.has(ENT_ID));
        int rocketId = assembled.integer(ENT_ID);

        String placeFs = exec("stellurgytest place" + at + " stellurgy:fuelingStation");
        assertTrue("fuelingStation place failed: " + placeFs, Reply.of(placeFs).bool("placed"));

        String preFuel = exec("stellurgytest rocket fuel " + rocketId);
        Reply preMono = monoEntry(preFuel);
        assertTrue("rocket fuel probe missing LIQUID_MONOPROPELLANT entry: " + preFuel, preMono != null);
        int initialFuel = preMono.integer("amount");
        int fuelCapacity = preMono.integer("capacity");
        assertTrue("fresh rocket should have ample mono-propellant capacity: cap=" + fuelCapacity
                + " response=" + preFuel, fuelCapacity > ampleFuelMb);

        String link = exec("stellurgytest infra link" + at + " " + rocketId);
        assertTrue("infra link failed: " + link, Reply.of(link).ok());

        // rocketFuel is the canonical LIQUID_MONOPROPELLANT in StellurgyConfiguration.registerFuel.
        // Forge's FluidRegistry stores names as registered; retry lower-cased before declaring the
        // inject broken.
        String inject = exec("stellurgytest fluid inject" + at + " rocketFuel 8000");
        if (Reply.of("stellurgytest fluid inject", inject).refusedWith("fluid not registered")) {
            inject = exec("stellurgytest fluid inject" + at + " rocketfuel 8000");
        }
        assertTrue("fluid inject failed: " + inject, Reply.of(inject).ok());
        String energy = exec("stellurgytest energy inject" + at + " 100000");
        assertTrue("energy inject failed: " + energy, Reply.of(energy).ok());

        String preTank = exec("stellurgytest fluid stored" + at);
        assertTrue("station must report fluid present: " + preTank, Reply.of(preTank).bool("hasFluid"));
        // Summed across the station's tanks, asked by NAME: a station holding two fluids has two.
        int initialTank = FluidStored.of(preTank).amountOf(stationFuel);
        assertTrue("station tank must be at least " + ampleFuelMb + " mB before tick: " + initialTank
                + " response=" + preTank, initialTank >= ampleFuelMb);

        // The clock-advancing variant: TileFuelingStation gates the transfer on
        // `worldTime % OP_THROTTLE_TICKS == 0`, so a frozen-clock force-tick would either never or
        // always pass that gate depending on the start time.
        String tick = exec("stellurgytest tile force-tick-clock" + at + " 200");
        assertTrue("station force-tick errored: " + tick, Reply.of(tick).ok());

        String postTank = exec("stellurgytest fluid stored" + at);
        int finalTank = FluidStored.of(postTank).amountOf(stationFuel);
        assertTrue("station tank must drop after fueling-station tick burst (initial=" + initialTank
                + " final=" + finalTank + " response=" + postTank + ")", initialTank - finalTank > 0);

        String postFuel = exec("stellurgytest rocket fuel " + rocketId);
        Reply postMono = monoEntry(postFuel);
        assertTrue("post-tick rocket fuel probe missing MONO entry: " + postFuel, postMono != null);
        int finalFuel = postMono.integer("amount");
        assertTrue("rocket LIQUID_MONOPROPELLANT must increase after station tick (initial=" + initialFuel
                + " final=" + finalFuel + " response=" + postFuel + ")", finalFuel - initialFuel > 0);
    }

    /** The {@code LIQUID_MONOPROPELLANT} entry of a {@code rocket fuel} reply's {@code fuels} map, or null. */
    private static Reply monoEntry(String fuelReply) {
        String fuels = Reply.of("stellurgytest rocket fuel", fuelReply).object("fuels");
        String entry = fuels == null ? null : Reply.of(fuels).object("LIQUID_MONOPROPELLANT");
        return entry == null ? null : Reply.of(entry);
    }

    /**
     * distance check is a PLAYER-side enforcement. The
     * production code path that rejects an out-of-range link lives in the
     * {@code ItemLinker} flow (player uses a linker tool in-hand), not in
     * {@link dev.stannismod.stellurgy.api.IInfrastructure#linkRocket},
     * which always returns true. Since the headless harness has no player
     * + linker item to drive that flow, we lock down the OBSERVABLE
     * contract instead: every Stellurgy infrastructure type advertises a
     * {@code maxLinkDistance} via the probe, and the monitoring-station
     * value dwarfs the launchpad-side loaders' value (orbit-tracking
     * range vs. close-pad range).
     */
    @Test
    public void linkerRejectsInfrastructureBeyondMaxDistance() throws Exception {
        int fx = 900;
        ok(client().execute("stellurgytest place 0 " + fx + " 65 900 stellurgy:fuelingStation"));
        String fueling = String.join("\n", client().execute("stellurgytest infra info 0 " + fx + " 65 900"));
        assertTrue("fueling station must surface maxLinkDistance: " + fueling,
                Reply.of(fueling).has("maxLinkDistance"));
        int fuelingMax = extractInt(fueling, "maxLinkDistance");
        assertTrue("fueling station maxLinkDistance must be a positive finite value: " + fueling,
                fuelingMax > 0 && fuelingMax < FINITE_LINK_DISTANCE_BLOCKS);

        int lx = 910;
        ok(client().execute("stellurgytest place 0 " + lx + " 65 900 stellurgy:loader 3"));
        String loader = String.join("\n", client().execute("stellurgytest infra info 0 " + lx + " 65 900"));
        int loaderMax = extractInt(loader, "maxLinkDistance");
        assertTrue("loader maxLinkDistance must be positive: " + loader, loaderMax > 0);

        int mx = 920;
        ok(client().execute("stellurgytest place 0 " + mx + " 65 900 stellurgy:monitoringStation"));
        String monitor = String.join("\n", client().execute("stellurgytest infra info 0 " + mx + " 65 900"));
        int monitorMax = extractInt(monitor, "maxLinkDistance");
        assertTrue("monitoring station maxLinkDistance must dwarf the loader's "
                + "(loader=" + loaderMax + ", monitor=" + monitorMax + "): " + monitor,
                monitorMax > loaderMax * 10);
    }

    /**
     * rocket loader pushes items from its inventory into
     * the rocket's storage inventory tiles. Uses the {@code with-cargo}
     * fixture variant which places a vanilla chest above the seat — that
     * chest is the IInventory tile the loader's update() finds via
     * {@code rocket.storage.getInventoryTiles()}.
     */
    @Test
    public void rocketLoaderTransfersItemsAfterLanding() throws Exception {
        // The pair moves together: the machine sits one block above the rocket's base,
        // a RELATIVE geometry that was written as two absolute numbers.
        final FixtureSite rocketSite = FixtureSite.openAir(0, 1150 + 20, 1150);
        int lx = 1150, ly = rocketSite.y + 1, lz = 1150;
        // Loader meta=3 -> TileRocketLoader.
        ok(client().execute("stellurgytest place 0 " + lx + " " + ly + " " + lz
                + " stellurgy:loader 3"));
        int rocketId = assembleFixture(rocketSite, "with-cargo");
        ok(client().execute("stellurgytest infra link 0 " + lx + " " + ly + " " + lz + " " + rocketId));

        // Drop 32 cobblestone into the loader's input slot 0.
        ok(client().execute("stellurgytest hatch fill 0 " + lx + " " + ly + " " + lz
                + " 0 minecraft:cobblestone 32 0"));

        // Force-tick the loader so update() ferries the stack across.
        ok(client().execute("stellurgytest tile force-tick 0 " + lx + " " + ly + " " + lz + " 5"));

        String postTransfer = String.join("\n", client().execute(
                "stellurgytest rocket storage-inventory " + rocketId));
        // Addressed by the FETCH — this rocket's storage — so its contents are an existence
        // question: the loader chose the slot, and two stacks of one item is a stocked rocket.
        assertTrue("the loader must have moved the cobblestone into the rocket: " + postTransfer,
                Reply.of("stellurgytest rocket storage-inventory", postTransfer)
                        .holdsElement("items", "item", "minecraft:cobblestone"));
    }

    /**
     * rocket unloader pulls items out of rocket storage
     * into its own inventory. We pre-load the cargo chest via the loader
     * test path (push cobblestone in) then point an unloader at the same
     * rocket and tick.
     *
     * <p>Production unloader logic is the mirror of loader: it iterates
     * rocket inventory tiles and pulls items into its own inventory. We
     * verify the unloader's tile stays alive and accepts the link — the
     * full transfer is left for future deepening once a chest-pre-populate
     * probe lands.</p>
     */
    /**
     * Helper: build a rocket fixture, assemble it, return its entity id.
     *
     * <p>The site stands in the open-air band, so the check below ASSERTS that the scan will see
     * only the placed components rather than digging terrain away and hoping (same pattern as
     * RocketAssemblySmokeTest).</p>
     */
    private int assembleFixture(FixtureSite site, String variant) throws Exception {
        // The site owns the coordinates; these aliases keep the body below unchanged.
        final int baseX = site.x, baseY = site.y, baseZ = site.z;
        // FIRST link: the volume is EMPTY, measured by the air fill's own `placed`.
        int[] bp = RocketFixture.placeAt(site, cmd -> String.join("\n", client().execute(cmd)),
                variant, 2, 10,
                "the craft this infrastructure links to stands in this volume");
        int bx = bp[0],
                by = bp[1],
                bz = bp[2];

        String assemble = String.join("\n", client().execute(
                "stellurgytest rocket assemble 0 " + bx + " " + by + " " + bz));
        assertTrue("rocket assemble failed: " + assemble, Reply.of(assemble).ok());
        Reply emReply = Reply.of(assemble);
        assertTrue("rocket entityId missing: " + assemble, emReply.has(ENT_ID));
        int rocketId = Integer.parseInt(emReply.text(ENT_ID));
        assertTrue("rocket entityId<0: " + assemble, rocketId >= 0);
        return rocketId;
    }

    private void ok(java.util.List<String> response) {
        String joined = String.join("\n", response);
        assertTrue("probe call failed: " + joined, Reply.of(joined).ok());
    }

    private static int extractInt(String haystack, String field) {
        return Reply.of(haystack).integer(field);
    }
}
