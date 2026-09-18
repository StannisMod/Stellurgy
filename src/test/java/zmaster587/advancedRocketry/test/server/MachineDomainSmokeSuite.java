package zmaster587.advancedRocketry.test.server;

import zmaster587.advancedRocketry.test.EnergyStore;
import zmaster587.advancedRocketry.test.Reply;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import zmaster587.advancedRocketry.test.FixtureSite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * machine-domain smoke suite.
 *
 * <p>Consolidates 8 single-method smoke classes that each previously spawned
 * their own dedicated-server JVM (boot cost ~12 s × 8 ≈ 96 s wall) into a
 * single class scoped under {@link AbstractSharedServerTest} (one boot for
 * the whole suite).</p>
 *
 * <p>Method names are preserved verbatim from the original classes so failure
 * messages remain grep-able against historical CI output. Each method's
 * preamble comment names its source class.</p>
 *
 * <h2>Consolidated from</h2>
 * <ul>
 *   <li>{@code MultiMachineControllerSmokeTest} &rarr; {@link #allMachineControllersPlaceTickAndHaveRecipes()}</li>
 *   <li>{@code MultiblockValidationSmokeTest}  &rarr; {@link #cuttingMachineMultiblockValidatesAndInvalidates()}</li>
 *   <li>{@code EnergySystemsSmokeTest}         &rarr; {@link #solarPanelAccumulatesEnergyOverTicks()}</li>
 *   <li>{@code SealedRoomOxygenVentTest}       &rarr; {@link #sealedRoomBecomesBreathableThenLeaks()}</li>
 *   <li>{@code SuitVacuumSubsystemSmokeTest}   &rarr; {@link #suitItemsAndEnchantAreWiredUp()}</li>
 *   <li>{@code SpecialInfrastructureSmokeTest} &rarr; {@link #allSpecialBlocksPlaceAndTickWithoutException()}</li>
 *   <li>{@code MicrowaveReceiverSmokeTest}     &rarr; {@link #multiblockValidatesAndTicksWithoutCrash()}</li>
 *   <li>{@code BlackHoleGeneratorSmokeTest}    &rarr; {@link #controllerWithoutStructureTicksWithoutCrash()}</li>
 * </ul>
 *
 * <h2>State-leak audit</h2>
 *
 * <p>The shared-harness contract (see {@link AbstractSharedServerTest})
 * forbids state leaks between methods. Audit per method:</p>
 * <ul>
 *   <li><b>Position isolation</b>: each method uses a unique base-coordinate
 *       patch (see method-level comments). Patches do not overlap.</li>
 *   <li><b>Atmosphere density</b>: only
 *       {@link #suitItemsAndEnchantAreWiredUp()} mutates it, and restores
 *       in {@code finally}.</li>
 *   <li><b>Time / weather</b>: {@link #solarPanelAccumulatesEnergyOverTicks()}
 *       sets {@code day} + {@code clear} (intentional, doesn't restore — both
 *       are friendly state for every other method in this suite).</li>
 * </ul>
 */
public class MachineDomainSmokeSuite extends AbstractSharedServerTest {

    // ── Shared regex patterns ─────────────────────────────────────────────

    private static final String TICKED = "ticked";
    private static final String MULTIBLOCK_SAWBLADE_POS = "sawBladePos";
    private static final String VENT_SEALED = "isSealed";
    private static final String VENT_BLOB_SIZE = "blobSize";
    private static final String VENT_FLUID_AMT = "fluidAmount";
    private static final String VENT_BREATHABLE = "breathable";
    private static final String PLANET_DENSITY = "atmosphereDensity";

    // ── Machine block-id -> expected Tile* short class name ─

    /** Machine block id &rarr; expected Tile* class short name. */
    private static final Map<String, String> MACHINES = new LinkedHashMap<>();
    static {
        MACHINES.put("advancedrocketry:rollingMachine",             "TileRollingMachine");
        MACHINES.put("advancedrocketry:lathe",                      "TileLathe");
        MACHINES.put("advancedrocketry:crystallizer",               "TileCrystallizer");
        MACHINES.put("advancedrocketry:electrolyser",               "TileElectrolyser");
        MACHINES.put("advancedrocketry:chemicalReactor",            "TileChemicalReactor");
        MACHINES.put("advancedrocketry:centrifuge",                 "TileCentrifuge");
        MACHINES.put("advancedrocketry:arcfurnace",                 "TileElectricArcFurnace");
        MACHINES.put("advancedrocketry:precisionassemblingmachine", "TilePrecisionAssembler");
        MACHINES.put("advancedrocketry:precisionlaseretcher",       "TilePrecisionLaserEtcher");
    }

    // ─────────────────────────────────────────────────────────────────────
    // From MultiMachineControllerSmokeTest
    // Position patch: x=2100..2140 step 5, y=64, z=2100
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Controller-level recipe-machine smoke for all 9 non-cutting AR
     * multiblock controllers: place + tile-class assert + bare try-complete
     * (false) + force-tick + recipe-summary presence.
     */
    @Test
    public void allMachineControllersPlaceTickAndHaveRecipes() throws Exception {
        // recipes-summary baseline.
        String summary = join(client().execute("artest machine recipes-summary"));
        assertTrue("recipes-summary errored: " + summary,
                !Reply.of(summary).has("error"));

        // Layout: a row of machines in the open-air band, x=2100..2140 step 5. The Y was a
        // hard-coded 64 until 2026-09-14, described here as "flat stone" — which is a claim about
        // the pinned seed that nothing checked, and every machine below is placed, ticked and read
        // back without ever asking what is under it.
        int y = FixtureSite.OPEN_AIR_Y;
        int z = 2100;
        int xOff = 2100;

        StringBuilder failures = new StringBuilder();
        int idx = 0;
        for (Map.Entry<String, String> e : MACHINES.entrySet()) {
            String blockId = e.getKey();
            String tileClass = e.getValue();
            int x = xOff + idx * 5;
            idx++;

            String place = join(client().execute(
                    "artest place 0 " + x + " " + y + " " + z + " " + blockId));
            if (!Reply.of(place).bool("placed", false)) {
                failures.append(blockId).append("=PLACE_FAILED(").append(place).append(");\n");
                continue;
            }

            String info = join(client().execute(
                    "artest machine info 0 " + x + " " + y + " " + z));
            if (!info.contains(tileClass)) {
                failures.append(blockId).append("=WRONG_TILE_CLASS(expected ")
                        .append(tileClass).append("; got: ").append(info).append(");\n");
                continue;
            }

            String tryComplete = join(client().execute(
                    "artest machine try-complete 0 " + x + " " + y + " " + z));
            if (!(!Reply.of(tryComplete).bool("isComplete", true))) {
                failures.append(blockId).append("=BARE_TRY_COMPLETE_NOT_FALSE(")
                        .append(tryComplete).append(");\n");
                continue;
            }

            String tick = join(client().execute(
                    "artest tile force-tick 0 " + x + " " + y + " " + z + " 20"));
            if (!Reply.of(tick).ok()) {
                failures.append(blockId).append("=TICK_FAILED(").append(tick).append(");\n");
                continue;
            }
            Reply tmReply = Reply.of(tick);
            if (!tmReply.has(TICKED) || Integer.parseInt(tmReply.text(TICKED)) != 20) {
                failures.append(blockId).append("=INCOMPLETE_TICK(").append(tick).append(");\n");
                continue;
            }

            String postInfo = join(client().execute(
                    "artest machine info 0 " + x + " " + y + " " + z));
            if (!postInfo.contains(tileClass)) {
                failures.append(blockId).append("=POST_TICK_TILE_LOST(")
                        .append(postInfo).append(");\n");
                continue;
            }

            if (!Reply.of("artest machine recipes-summary", summary).has(tileClass)) {
                failures.append(blockId).append("=NOT_IN_RECIPE_SUMMARY;\n");
                continue;
            }
        }
        assertEquals("machine smoke failures:\n" + failures, "", failures.toString());
    }

    // ─────────────────────────────────────────────────────────────────────
    // From MultiblockValidationSmokeTest
    // Position patch: cutting fixture at x/z 300; probe sanity at x/z 200..212. Every Y here is the
    // open-air band — nothing in this method stands on ground, and until 2026-09-14 they were the
    // hard-coded 64 and 100 that put them wherever the seed's landscape happened to be.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    public void cuttingMachineMultiblockValidatesAndInvalidates() throws Exception {
        final int probeY = FixtureSite.OPEN_AIR_Y;
        // Step 0 — fixture-builder primitives still healthy.
        String emptyInfo = join(client().execute("artest machine info 0 200 " + probeY + " 200"));
        assertTrue("empty position machine info wrong: " + emptyInfo,
                "no tile entity".equals(Reply.of(emptyInfo).text("error")));
        String fill = join(client().execute(
                "artest fill 0 210 " + probeY + " 210 212 " + (probeY + 2) + " 212 minecraft:stone"));
        assertTrue("fill 3x3x3 stone failed: " + fill,
                Reply.of(fill).ok() && (Reply.of(fill).integerOr("volume", Integer.MIN_VALUE) == 27));

        // Step 1 — build the multiblock fixture. Its Y is the band: a cutting multiblock is
        // validated by its own STRUCTURE, so it wants nothing under it.
        final FixtureSite site = FixtureSite.openAir(0, 300, 300);
        int cx = site.x, cy = site.y, cz = site.z;
        String fixture = join(client().execute(
                "artest fixture machine cutting 0 " + cx + " " + cy + " " + cz));
        assertTrue("fixture machine cutting failed: " + fixture,
                Reply.of(fixture).ok());

        int[] sawBlade = Reply.of("artest fixture multiblock", fixture)
                .blockPos(MULTIBLOCK_SAWBLADE_POS);
        assertTrue("could not parse sawBladePos: " + fixture, sawBlade != null);
        int sx = sawBlade[0], sy = sawBlade[1], sz = sawBlade[2];

        // Step 2 — try-complete on the controller -> isComplete=true.
        String complete = join(client().execute(
                "artest machine try-complete 0 " + cx + " " + cy + " " + cz));
        assertTrue("try-complete errored: " + complete, Reply.of(complete).ok());
        assertTrue("structure didn't validate (isComplete=false): " + complete,
                Reply.of(complete).bool("isComplete", false));

        // Step 3 — break the sawblade -> re-validate -> isComplete=false.
        String breakBlock = join(client().execute(
                "artest place 0 " + sx + " " + sy + " " + sz + " minecraft:air"));
        assertTrue("could not replace sawBlade with air: " + breakBlock,
                Reply.of(breakBlock).ok());

        String broken = join(client().execute(
                "artest machine try-complete 0 " + cx + " " + cy + " " + cz));
        assertTrue("try-complete errored after break: " + broken, Reply.of(broken).ok());
        assertTrue("structure stayed complete after sawBlade removal — validator broken: " + broken,
                (!Reply.of(broken).bool("isComplete", true)));

        // Step 4 — restore the sawblade -> re-validate -> isComplete=true again.
        String restore = join(client().execute(
                "artest place 0 " + sx + " " + sy + " " + sz + " advancedrocketry:sawBlade"));
        assertTrue("could not restore sawBlade: " + restore,
                Reply.of(restore).bool("placed", false));

        String recomplete = join(client().execute(
                "artest machine try-complete 0 " + cx + " " + cy + " " + cz));
        assertTrue("validator failed to re-detect a restored structure: " + recomplete,
                Reply.of(recomplete).bool("isComplete", false));
    }

    // ─────────────────────────────────────────────────────────────────────
    // From EnergySystemsSmokeTest
    // Position patch: battery at x/z 1000, solar panel at x/z 1100, both in the open-air band.
    // Friendly globals: time=day, weather=clear. Not restored (no test in this
    // suite depends on natural time/weather).
    // ─────────────────────────────────────────────────────────────────────

    @Test
    public void solarPanelAccumulatesEnergyOverTicks() throws Exception {
        // The band, not terrain. Both Ys were hard-coded (64 and 100) until 2026-09-14, and the
        // SOLAR one is the reason this is not cosmetic: a panel's generation branch asks whether it
        // can see the sky, and a fixed Y over generated terrain answers that question with the seed.
        // The battery's Y never mattered and moves with it so the two stay one decision.
        final int siteY = FixtureSite.OPEN_AIR_Y;
        // 1. Empty-pos NPE guard. LEFT RAW: the subject of this line IS the error shape, which
        // `EnergyStore` refuses — and refuses precisely so the readings below cannot be satisfied
        // by a block that is not there.
        String empty = join(client().execute("artest energy stored 0 1000 " + siteY + " 1000"));
        assertTrue("expected 'no tile entity' on empty pos: " + empty,
                empty.contains("\"no tile entity\""));

        // 2. libVulpes creative battery — Forge-energy capability presence (optional).
        String placeBattery = join(client().execute(
                "artest place 0 1000 " + siteY + " 1000 libvulpes:creativepowerbattery"));
        if (Reply.of(placeBattery).bool("placed", false)) {
            EnergyStore bat = energy(1000, siteY, 1000)
                    .requireEnergy("creative battery missing IEnergyStorage");
            assertTrue("creative battery has zero capacity: " + bat.raw(), bat.capacity() > 0L);
        }

        // 3. Solar panel real generation.
        client().execute("time set day");
        client().execute("weather clear 100000");
        String placeSolar = join(client().execute(
                "artest place 0 1100 " + siteY + " 1100 advancedrocketry:solarGenerator"));
        assertTrue("could not place solarGenerator: " + placeSolar,
                Reply.of(placeSolar).bool("placed", false));

        EnergyStore s0 = energy(1100, siteY, 1100)
                .requireEnergy("solarGenerator missing IEnergyStorage");
        long initial = s0.stored();

        String tick = join(client().execute(
                "artest tile force-tick 0 1100 " + siteY + " 1100 100"));
        assertTrue("force-tick failed: " + tick, Reply.of(tick).ok());

        EnergyStore s1 = energy(1100, siteY, 1100);
        long after = s1.stored();
        assertTrue("solarGenerator did not accumulate energy: initial=" + initial
                        + " after-100-ticks=" + after + " response=" + s1.raw(),
                after > initial);
    }

    // ─────────────────────────────────────────────────────────────────────
    // From SealedRoomOxygenVentTest
    // Position patch: 5×5×4 room centred at x/z 1500, in the open-air band. Vent at floor.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    public void sealedRoomBecomesBreathableThenLeaks() throws Exception {
        // The room is BUILT here, floor walls and roof, so it needs no ground — and standing it in
        // the band is what makes "sealed" a property of what this scenario built rather than of
        // whatever the seed left touching it. The Y was a hard-coded 64 until 2026-09-14.
        final FixtureSite site = FixtureSite.openAir(0, 1500, 1500);
        int bx = site.x, by = site.y, bz = site.z;

        ok(client().execute("artest fill 0 " + (bx - 2) + " " + (by - 1) + " " + (bz - 2)
                + " " + (bx + 2) + " " + by + " " + (bz + 2) + " minecraft:stone"));

        for (int yy = by + 1; yy <= by + 2; yy++) {
            ok(client().execute("artest fill 0 " + (bx - 2) + " " + yy + " " + (bz - 2)
                    + " " + (bx + 2) + " " + yy + " " + (bz + 2) + " minecraft:stone"));
            ok(client().execute("artest fill 0 " + (bx - 1) + " " + yy + " " + (bz - 1)
                    + " " + (bx + 1) + " " + yy + " " + (bz + 1) + " minecraft:air"));
        }

        ok(client().execute("artest fill 0 " + (bx - 2) + " " + (by + 3) + " " + (bz - 2)
                + " " + (bx + 2) + " " + (by + 3) + " " + (bz + 2) + " minecraft:stone"));

        String place = join(client().execute(
                "artest place 0 " + bx + " " + by + " " + bz + " advancedrocketry:oxygenVent"));
        assertTrue("vent did not place: " + place, Reply.of(place).bool("placed", false));

        String preTick = join(client().execute(
                "artest vent info 0 " + bx + " " + by + " " + bz));
        assertTrue("probe must recognise the vent tile: " + preTick,
                Reply.of(preTick).bool("isVent", false));

        String fluidFill = join(client().execute(
                "artest fluid inject 0 " + bx + " " + by + " " + bz + " oxygen 16000"));
        assertTrue("oxygen fill failed: " + fluidFill, Reply.of(fluidFill).ok());

        String energyFill = join(client().execute(
                "artest energy inject 0 " + bx + " " + by + " " + bz + " 1000000"));
        assertTrue("energy fill failed: " + energyFill, Reply.of(energyFill).ok());

        String fueled = join(client().execute(
                "artest vent info 0 " + bx + " " + by + " " + bz));
        assertTrue("vent should report fluid after inject: " + fueled,
                Reply.of(fueled).has(VENT_FLUID_AMT)
                        && Integer.parseInt(matchOrFail(VENT_FLUID_AMT, fueled)) > 0);

        client().execute("artest tile force-tick 0 " + bx + " " + by + " " + bz + " 1");

        String reseal = join(client().execute(
                "artest vent reseal 0 " + bx + " " + by + " " + bz));
        assertTrue("vent reseal probe failed: " + reseal,
                Reply.of(reseal).ok());

        client().execute("artest tile force-tick 0 " + bx + " " + by + " " + bz + " 5");

        String sealed = join(client().execute(
                "artest vent info 0 " + bx + " " + by + " " + bz));
        assertEquals("vent must be sealed after reseal+tick: " + sealed,
                "true", matchOrFail(VENT_SEALED, sealed));
        int sealedBlobSize = Integer.parseInt(matchOrFail(VENT_BLOB_SIZE, sealed));
        assertTrue("vent blob must include the interior (>=18): " + sealed,
                sealedBlobSize >= 18);

        String atm = join(client().execute(
                "artest atmosphere get 0 " + bx + " " + (by + 1) + " " + bz));
        assertEquals("interior must be breathable when sealed: " + atm,
                "true", matchOrFail(VENT_BREATHABLE, atm));

        // Break one wall — blob must react.
        ok(client().execute("artest place 0 " + (bx + 2) + " " + (by + 1) + " " + bz
                + " minecraft:air"));

        String reseal2 = join(client().execute(
                "artest vent reseal 0 " + bx + " " + by + " " + bz));
        assertTrue("second reseal probe failed: " + reseal2,
                Reply.of(reseal2).ok());

        String leaked = join(client().execute(
                "artest vent info 0 " + bx + " " + by + " " + bz));
        int leakedBlobSize = Integer.parseInt(matchOrFail(VENT_BLOB_SIZE, leaked));
        boolean leakDetected =
                leakedBlobSize > sealedBlobSize
                || (leakedBlobSize == 0 && matchOrFail(VENT_SEALED, leaked).equals("false"));
        assertTrue("blob must react to wall break — either grow or void"
                        + " (was " + sealedBlobSize + ", now " + leakedBlobSize
                        + "): " + leaked, leakDetected);
    }

    // ─────────────────────────────────────────────────────────────────────
    // From SuitVacuumSubsystemSmokeTest
    // No world placement. Atmosphere density mutation restored in finally.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    public void suitItemsAndEnchantAreWiredUp() throws Exception {
        // 1. All four suit pieces registered + expose IProtectiveArmor capability.
        for (String id : new String[]{
                "advancedrocketry:spaceHelmet",
                "advancedrocketry:spaceChestplate",
                "advancedrocketry:spaceLeggings",
                "advancedrocketry:spaceBoots"
        }) {
            String resp = join(client().execute(
                    "artest item check " + id + " protective-armor"));
            assertTrue(id + " not registered: " + resp,
                    Reply.of(resp).bool("registered", false));
            assertTrue(id + " missing IProtectiveArmor capability: " + resp,
                    Reply.of(resp).bool("hasCapability", false));
        }

        // 2. SpaceBreathing enchantment registered.
        String ench = join(client().execute(
                "artest enchant check advancedrocketry:spacebreathing"));
        assertTrue("spacebreathing enchant missing: " + ench,
                Reply.of(ench).bool("registered", false));

        // 3. Vacuum precondition: Earth -> density 0 -> non-breathable.
        // Snapshot original so we restore it after.
        String planet = join(client().execute("artest planet info 0"));
        Reply dmReply = Reply.of(planet);
        int originalDensity = dmReply.has(PLANET_DENSITY) ? Integer.parseInt(dmReply.text(PLANET_DENSITY)) : 100;

        try {
            String setVac = join(client().execute(
                    "artest atmosphere set-density 0 0"));
            assertTrue("set-density 0 failed: " + setVac,
                    Reply.of(setVac).ok());

            String atm = join(client().execute(
                    "artest atmosphere get 0 0 70 0"));
            assertTrue("density=0 must yield non-breathable atmosphere: " + atm,
                    (!Reply.of(atm).bool("breathable", true)));
        } finally {
            client().execute("artest atmosphere set-density 0 " + originalDensity);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // From SpecialInfrastructureSmokeTest
    // Position patch: 4 devices at x=700,710,730,740, z=700, in the open-air band.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    public void allSpecialBlocksPlaceAndTickWithoutException() throws Exception {
        // The band, not terrain. Each device is placed, force-ticked and read back; none of them
        // asks what is beneath it. The Y was a hard-coded 64 until 2026-09-14.
        int y = FixtureSite.OPEN_AIR_Y;
        int baseX = 700, baseZ = 700;

        Map<String, Integer> devices = new LinkedHashMap<>();
        devices.put("advancedrocketry:railgun", 0);
        devices.put("advancedrocketry:beacon", 10);
        devices.put("advancedrocketry:spaceLaser", 30);
        devices.put("advancedrocketry:spaceElevatorController", 40);

        StringBuilder failures = new StringBuilder();
        int errors = 0;
        for (Map.Entry<String, Integer> e : devices.entrySet()) {
            String blockId = e.getKey();
            int x = baseX + e.getValue();
            String place = join(client().execute(
                    "artest place 0 " + x + " " + y + " " + baseZ + " " + blockId));
            if (!Reply.of(place).bool("placed", false)) {
                failures.append(blockId).append("=PLACE_FAILED;");
                errors++;
                continue;
            }
            String info = join(client().execute(
                    "artest machine info 0 " + x + " " + y + " " + baseZ));
            if (info.contains("Exception")
                    || (!info.contains("\"tileClass\"") && !info.contains("\"no tile entity\""))) {
                failures.append(blockId).append("=INFO_BAD;");
                errors++;
                continue;
            }
            if (info.contains("\"tileClass\"")) {
                String tick = join(client().execute(
                        "artest tile force-tick 0 " + x + " " + y + " " + baseZ + " 5"));
                if (tick.contains("Exception") || tick.contains("\"error\":\"tile.update")) {
                    failures.append(blockId).append("=TICK_THREW(").append(tick).append(");");
                    errors++;
                }
            }
        }

        assertEquals("special infrastructure failures: " + failures, 0, errors);
    }

    // ─────────────────────────────────────────────────────────────────────
    // From MicrowaveReceiverSmokeTest
    // Position patch: 5×5 multiblock at x/z 1700..1704 in the open-air band. Controller at +2,+2.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    public void multiblockValidatesAndTicksWithoutCrash() throws Exception {
        // The whole 5x5 is laid by this method, so nothing here wants ground. The Y was a
        // hard-coded 64 until 2026-09-14, which put a sky-facing receiver under whatever the seed
        // had grown over it.
        final FixtureSite site = FixtureSite.openAir(0, 1700, 1700);
        int x0 = site.x, y = site.y, z0 = site.z;
        int xC = x0 + 2, zC = z0 + 2;

        String fill = join(client().execute(
                "artest fill 0 " + x0 + " " + y + " " + z0 + " "
                        + (x0 + 4) + " " + y + " " + (z0 + 4)
                        + " advancedrocketry:solarPanel"));
        assertTrue("solar fill failed: " + fill, Reply.of(fill).ok());

        int[][] airPositions = new int[][]{
                {x0, z0},     {x0 + 4, z0},     {x0, z0 + 4},     {x0 + 4, z0 + 4},
                {x0 + 1, z0}, {x0 + 2, z0},     {x0 + 3, z0},
                {x0 + 1, z0 + 4}, {x0 + 2, z0 + 4}, {x0 + 3, z0 + 4},
                {x0, z0 + 1}, {x0, z0 + 2}, {x0, z0 + 3},
                {x0 + 4, z0 + 1}, {x0 + 4, z0 + 2}, {x0 + 4, z0 + 3}
        };
        for (int[] p : airPositions) {
            client().execute("artest place 0 " + p[0] + " " + y + " " + p[1] + " minecraft:air");
        }

        String place = join(client().execute(
                "artest place 0 " + xC + " " + y + " " + zC
                        + " advancedrocketry:microwaveReciever"));
        assertTrue("controller place failed: " + place,
                Reply.of(place).bool("placed", false));

        String info = join(client().execute(
                "artest machine info 0 " + xC + " " + y + " " + zC));
        assertTrue("expected microwave-receiver tile: " + info,
                info.contains("TileMicrowaveReciever"));

        String tick = join(client().execute(
                "artest tile force-tick 0 " + xC + " " + y + " " + zC + " 40"));
        assertTrue("force-tick errored: " + tick, Reply.of(tick).ok());
        assertEquals("must tick all 40 iterations",
                40, extractInt(tick, "ticked"));

        String postInfo = join(client().execute(
                "artest machine info 0 " + xC + " " + y + " " + zC));
        assertTrue("tile must survive tick burst: " + postInfo,
                postInfo.contains("TileMicrowaveReciever"));
    }

    // ─────────────────────────────────────────────────────────────────────
    // From BlackHoleGeneratorSmokeTest
    // Position patch: controller at x/z 1800 in the open-air band, no multiblock structure.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    public void controllerWithoutStructureTicksWithoutCrash() throws Exception {
        // The band, not terrain: a controller that is ticked without its structure has no interest
        // in what is under it, and the Y was a hard-coded 64 until 2026-09-14.
        int x = 1800, y = FixtureSite.OPEN_AIR_Y, z = 1800;

        String place = join(client().execute(
                "artest place 0 " + x + " " + y + " " + z
                        + " advancedrocketry:blackholegenerator"));
        assertTrue("controller place failed: " + place,
                Reply.of(place).bool("placed", false));

        String info = join(client().execute(
                "artest machine info 0 " + x + " " + y + " " + z));
        assertTrue("expected black-hole-generator tile: " + info,
                info.contains("TileBlackHoleGenerator"));

        String tick = join(client().execute(
                "artest tile force-tick 0 " + x + " " + y + " " + z + " 50"));
        assertTrue("force-tick errored: " + tick, Reply.of(tick).ok());
        assertEquals("must tick all 50 iterations",
                50, extractInt(tick, "ticked"));

        String postInfo = join(client().execute(
                "artest machine info 0 " + x + " " + y + " " + z));
        assertTrue("tile must survive tick burst: " + postInfo,
                postInfo.contains("TileBlackHoleGenerator"));
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static String join(List<String> resp) {
        return String.join("\n", resp);
    }

    /** Asserts the probe response contains {@code "ok":true} and returns nothing. */
    private static void ok(List<String> response) {
        String joined = join(response);
        assertTrue("probe call failed: " + joined, Reply.of(joined).ok());
    }

    /** What the Forge energy capability at one block reports — refusing a block that is not there. */
    private EnergyStore energy(int x, int y, int z) throws Exception {
        return EnergyStore.at(cmd -> join(client().execute(cmd)), 0, x, y, z);
    }

    private static String matchOrFail(String field, String s) {
        String value = Reply.of(s).text(field);
        assertTrue("field `" + field + "` not found in: " + s, value != null);
        return value;
    }

    private static int extractInt(String s, String field) {
        return Reply.of(s).integerOr(field, -1);
    }
}
