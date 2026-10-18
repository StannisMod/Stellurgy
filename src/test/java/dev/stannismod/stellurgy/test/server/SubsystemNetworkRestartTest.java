package dev.stannismod.stellurgy.test.server;

import com.github.stannismod.forge.testing.junit.AbstractHeadlessServerTest;
import com.github.stannismod.forge.testing.junit.AbstractMechanicsTest;
import com.github.stannismod.forge.testing.server.RealDedicatedServerHarness;
import com.github.stannismod.forge.testing.server.ServerRoot;
import dev.stannismod.stellurgy.test.*;
import dev.stannismod.stellurgy.test.contract.LegacyWitness;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import dev.stannismod.stellurgy.test.contract.OwnBoot;
import dev.stannismod.stellurgy.test.contract.Pins;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * What a restart must give back — split by who owns it.
 *
 * <p>A subsystem network is deliberately not saved: it has no durable name, being "the blocks that
 * happen to be connected right now", so it is rebuilt from the world every time. That decision is
 * only sound if the rebuild actually returns the same network, and until now nothing checked it.
 * The settings are the opposite case: they live on BLOCKS precisely because a block has a name, and
 * so they are the things that must survive byte-for-byte.</p>
 *
 * <p>So this pins both halves against one restart: the ventilation graph comes back with the same
 * shape (nothing persisted it — chunk load re-registers the nodes), while the vent's zone priority
 * and the shield console's resistance bias come back with the same VALUES (their tiles persisted
 * them). A regression in either direction is invisible in a single-boot test.</p>
 */
@OwnBoot(reason = OwnBoot.Reason.SERVER_RESTART)
public class SubsystemNetworkRestartTest extends AbstractMechanicsTest {

    /** One chunk, so a single probe pulls every node of both networks back into memory. */
    private static final int Y = 64;
    private static final int Z = 2608;
    private static final int VENT = 2608;
    private static final int DUCT_A = VENT + 1;
    private static final int DUCT_B = VENT + 2;
    private static final int PLANT = VENT + 3;
    private static final int SHIELD_SOURCE = VENT + 5;
    private static final int SHIELD_SINK = VENT + 6;
    private static final int SHIELD_CONSOLE = VENT + 7;

    /** The coolant loop sits in the same chunk, past the shield run. */
    private static final int PIPE_A = VENT + 9;
    private static final int PIPE_B = VENT + 10;
    private static final int ACCUMULATOR = VENT + 11;

    private static final int ZONE_PRIORITY = 1;
    private static final String RESISTANCE_BIAS = "0.75";
    /** Not a round number, so a value that came back by coincidence would not look like a pass. */
    private static final long STORED_HEAT = 4531L;

    /** The weapons scenario's site, in open air; a fresh world per test, so nothing else stands there. */
    private static final int WEAPON_SITE = 4800;
    private static final String WEAPON_CODE = "w636-code";
    /** Not the default rule, so a network that came back with a fresh default would not look like a pass. */
    private static final String WEAPON_RULE = "NONE";

    private ServerRoot workDir;
    private RealDedicatedServerHarness firstBoot;
    private RealDedicatedServerHarness secondBoot;

    @Before
    public void prepareWorkDir() throws Exception {
        Assume.assumeTrue(
                "Server harness disabled — set -D"
                        + AbstractHeadlessServerTest.PROP_HARNESS_ENABLED + "=true",
                Boolean.parseBoolean(System.getProperty(
                        AbstractHeadlessServerTest.PROP_HARNESS_ENABLED, "false")));
        workDir = ServerRoot.fresh("forge-server-subnet-restart-");
    }

    @After
    public void closeAll() throws Exception {
        if (firstBoot != null) firstBoot.close();
        if (secondBoot != null) secondBoot.close();
    }

    /**
     * <p>Not asserted: that the ventilation network's membership comes back the same. The verdict
     * compares two builds by the same code, and the member set is the positions of the component's
     * role maps — so it can only differ across the restart if some node came back with a different
     * set of roles, which moves one of the three counts above. (A fault that drops membership outright
     * would drop it before the restart too and leave the comparison equal; that is not this test's
     * contract.)</p>
     */
    @LegacyWitness(prose = "one inversion per verdict, 2026-09-30. CABLES — {@code TileVentilationDuct#onLoad} at"
            + " {@code SubsystemNetworkManager.of(world).register(this);} not re-registering a duct restored from the"
            + " save: \"the ventilation graph must come back with the same cable count … \\\"cables\\\":0\". SOURCES —"
            + " the same in {@code TileLifeSupportPlant#onLoad} at {@code SubsystemNetworkManager.of(world).register(this);}:"
            + " \"and the same source count: … \\\"sources\\\":0\". SINKS — the same in {@code TileVentilationPort#onLoad}"
            + " at {@code SubsystemNetworkManager.of(world).register(this);}: \"and the same sink count: …"
            + " \\\"sinks\\\":0\". (Taken on the pre-merge form {@code SubsystemNetworkRegistry.register(this)}.)")
    @RedWitness(verdict = "priority", member = "TileVentilationPort#readFromNBT",
            at = "zonePriority = Math.max(PRIORITY_MIN, Math.min(PRIORITY_MAX, nbt.getInteger(\"zonePriority\")));",
            replacedBy = "zonePriority = zonePriority;",
            fails = "the port's zone priority must survive the restart", on = "2026-10-18")
    @RedWitness(verdict = "bias", member = "TileEntityShieldConsole#readFromNBT",
            at = "shieldEnergyResistanceBias = compound.hasKey(\"shieldEnergyResistanceBias\")",
            replacedBy = "shieldEnergyResistanceBias = false",
            fails = "the console's resistance bias must survive the restart", on = "2026-10-18")
    @Test
    public void theNetworkIsRebuiltFromTheWorldWhileItsSettingsAreRestoredFromTheirBlocks()
            throws Exception {
        // ─────── Boot 1: build both networks, set both settings ───────
        firstBoot = RealDedicatedServerHarness.startWith(harnessBoot(), workDir, /*cleanupOnClose=*/false);

        place(firstBoot, "stellurgy:ventilationPort", VENT);
        place(firstBoot, "stellurgy:ventilationDuct", DUCT_A);
        place(firstBoot, "stellurgy:ventilationDuct", DUCT_B);
        place(firstBoot, "stellurgy:lifeSupportPlant", PLANT);
        place(firstBoot, "affs:shield_generator", SHIELD_SOURCE);
        place(firstBoot, "affs:field_generator", SHIELD_SINK);
        place(firstBoot, "affs:shield_console", SHIELD_CONSOLE);

        Reply priorityWrite = act(firstBoot,
                "stellurgytest vent priority 0 " + VENT + " " + Y + " " + Z + " " + ZONE_PRIORITY);
        assertEquals("priority write failed: " + priorityWrite,
                ZONE_PRIORITY, priorityWrite.integer("priority"));

        arrange(firstBoot, "stellurgytest shield console-bias 0 " + SHIELD_CONSOLE + " "
                + Y + " " + Z + " " + RESISTANCE_BIAS);

        act(firstBoot, "stellurgytest subnet solve lifesupport 0 2");
        Reply ventilationBefore = subnet(firstBoot, DUCT_A);
        int cablesBefore = ventilationBefore.integer("cables");
        int sourcesBefore = ventilationBefore.integer("sources");
        int sinksBefore = ventilationBefore.integer("sinks");
        assertEquals("premise: two ducts must be in the network before the restart: "
                + ventilationBefore, 2, cablesBefore);
        assertEquals("premise: the plant must be its source: " + ventilationBefore, 1, sourcesBefore);
        assertEquals("premise: the port must be its sink: " + ventilationBefore, 1, sinksBefore);

        firstBoot.close();
        firstBoot = null;

        // ─────── Boot 2: same world, nothing saved the network itself ───────
        secondBoot = RealDedicatedServerHarness.startWith(harnessBoot(), workDir, /*cleanupOnClose=*/true);

        // Bring the chunk in FIRST. Nothing about the network is restored by loading a save: the
        // tiles re-register from onLoad and the graph is rebuilt from their adjacency, so with the
        // chunk still on disk there is legitimately no network to report. In play a walking player
        // does this; here it is explicit, because a probe that quietly loaded the chunk would be
        // changing the thing it measures.
        arrange(secondBoot, "stellurgytest chunk forceload 0 " + (VENT >> 4) + " " + (Z >> 4));
        act(secondBoot, "stellurgytest subnet solve lifesupport 0 2");
        Reply ventilationAfter = subnet(secondBoot, DUCT_A);
        assertEquals("the ventilation graph must come back with the same cable count — it is "
                + "rebuilt from the world, and the world did not change: " + ventilationAfter,
                cablesBefore, ventilationAfter.integer("cables"));
        assertEquals("and the same source count: " + ventilationAfter,
                sourcesBefore, ventilationAfter.integer("sources"));
        assertEquals("and the same sink count: " + ventilationAfter,
                sinksBefore, ventilationAfter.integer("sinks"));

        // The settings, by contrast, are only here because their own tiles wrote them to NBT.
        Reply priorityAfter = read(secondBoot, "stellurgytest vent priority 0 " + VENT + " " + Y + " " + Z)
                .requireOk();
        assertEquals("the port's zone priority must survive the restart — it is the port's own "
                        + "setting, not the network's: " + priorityAfter,
                ZONE_PRIORITY, priorityAfter.integer("priority"));

        Reply consoleAfter = read(secondBoot, "stellurgytest shield console-info 0 " + SHIELD_CONSOLE
                + " " + Y + " " + Z);
        assertEquals("the console's resistance bias must survive the restart, and it is the only "
                        + "thing that re-seeds the rebuilt shield network: " + consoleAfter,
                Double.parseDouble(RESISTANCE_BIAS), consoleAfter.number("resistanceBias"), 1.0e-6);
    }

    /**
     * The third case, and the one that is neither of the other two: a coolant loop's ENERGY.
     *
     * <p>It is not a setting — nobody typed it — and it is not derivable from the blocks either, so
     * neither half of the test above covers it. It is written down per block for exactly the reason
     * the settings are: a loop has no durable name and is rebuilt from the world, while a block has
     * its position. This pins that decision, which is otherwise only an argument.</p>
     *
     * <p>Read per BLOCK first and per LOOP second, deliberately. The loop's figure alone could not
     * tell energy that came back from the blocks from energy that was never gone.</p>
     *
     * <p>Not asserted: the loop's capacity, stored heat and temperature after the restart, because
     * the loop re-sums capacity and heat from its member blocks on every solve and derives its
     * temperature from those two, so none of them could differ while the per-block energy and the
     * membership verdicts hold.</p>
     * Pins INV-HEAT-06 (A loop's energy survives a server restart, and comes back OFF THE BLOCKS).
     */
    @Pins("INV-HEAT-06")
    @RedWitness(verdict = "off the blocks", member = "TileHeatLoopBlock#readFromNBT",
            at = "storedHeat = nbt.getLong(NBT_STORED_HEAT);",
            replacedBy = "storedHeat = 0L;",
            fails = "every heat unit must come back off the blocks", on = "2026-10-18")
    @LegacyWitness(prose = "REBUILT — {@code TileHeatLoopBlock#onLoad} at {@code SubsystemNetworkManager.of(world).register(this);}"
            + " not re-registering a block restored from the save: \"the loop must be REBUILT with the same membership"
            + " — nothing persisted it: … \\\"members\\\":0\", 2026-09-30 (taken on the pre-merge form"
            + " {@code SubsystemNetworkRegistry.register(this)}).")
    @Test
    public void aCoolantLoopsEnergyComesBackFromItsBlocks() throws Exception {
        // ─────── Boot 1: a loop, and some energy in it ───────
        firstBoot = RealDedicatedServerHarness.startWith(harnessBoot(), workDir, /*cleanupOnClose=*/false);

        place(firstBoot, "stellurgy:heatPipe", PIPE_A);
        place(firstBoot, "stellurgy:heatPipe", PIPE_B);
        place(firstBoot, "stellurgy:heatAccumulator", ACCUMULATOR);

        Reply written = arrange(firstBoot,
                "stellurgytest heat set 0 " + PIPE_A + " " + Y + " " + Z + " " + STORED_HEAT);
        requireArranged("premise: the position must hold a loop block: " + written,
                written.bool("isLoopBlock"));
        assertEquals("premise: the block must accept the energy: " + written,
                STORED_HEAT, written.longInteger("heatStored"));

        // One tick, so the loop finds itself and spreads the energy over its members at one
        // temperature — which is the state a real ship would be saved in, not a lump in one pipe.
        act(firstBoot, "stellurgytest subnet solve heat 0 1");
        Reply loopBefore = subnetHeat(firstBoot, PIPE_A);
        long loopHeatBefore = loopBefore.longInteger("heatStored");
        assertEquals("premise: all three blocks must be one loop: " + loopBefore,
                3, loopBefore.integer("members"));
        assertEquals("premise: the loop must hold exactly what was put in it: " + loopBefore,
                STORED_HEAT, loopHeatBefore);

        firstBoot.close();
        firstBoot = null;

        // ─────── Boot 2: same world, and the loop is a fresh object ───────
        secondBoot = RealDedicatedServerHarness.startWith(harnessBoot(), workDir, /*cleanupOnClose=*/true);

        arrange(secondBoot, "stellurgytest chunk forceload 0 " + (PIPE_A >> 4) + " " + (Z >> 4));

        // The blocks first: this is where the energy actually was, and the only place it could have
        // come from — nothing saved the loop.
        long fromBlocks = 0L;
        for (int x : new int[]{PIPE_A, PIPE_B, ACCUMULATOR}) {
            Reply block = read(secondBoot, "stellurgytest heat read 0 " + x + " " + Y + " " + Z).requireOk();
            assertTrue("the loop block at " + x + " must be back: " + block, block.bool("isLoopBlock"));
            fromBlocks += block.longInteger("heatStored");
        }
        assertEquals("every heat unit must come back off the blocks that were holding it — this is "
                        + "the whole reason the energy lives on blocks and not in the network",
                STORED_HEAT, fromBlocks);

        act(secondBoot, "stellurgytest subnet solve heat 0 1");
        Reply loopAfter = subnetHeat(secondBoot, PIPE_A);
        assertEquals("the loop must be REBUILT with the same membership — nothing persisted it: "
                + loopAfter, 3, loopAfter.integer("members"));
    }

    /**
     * A weapons network's ORDERS come back from its consoles, and the battery does what it was told
     * before the restart.
     *
     * <p>The orders are given through one console and that console is then broken, so after the
     * restart the only block that can carry them is the OTHER console — what comes back proves both
     * that an order reaches every console of its network and that a console saves it. The verdict
     * that matters is the gun's: told to hold fire, on target and charged, it must hold after the
     * restart, and the control is the same gun firing once hold-fire is lifted.</p>
     *
     * <p>Not asserted: an entity or ship target, which this harness cannot stand up (no player, no
     * ship); they are carried by the same writer and reader as the point target. And a code-carrying
     * PLAYER not being engaged, which needs a player; what is asserted instead is the code the gun
     * judges a body by, read off the gun, which is that decision's only input from the network.</p>
     */
    @RedWitness(verdict = "held", member = "TileWeaponConsole#readFromNBT",
            at = "savedOrders = nbt.getCompoundTag(NBT_ORDERS);",
            replacedBy = "savedOrders = null;",
            fails = "the gun told to hold fire before the restart never held after it", on = "2026-10-18")
    @RedWitness(verdict = "held", member = "TileWeaponConsole#readFromNBT",
            at = "savedOrders = nbt.getCompoundTag(NBT_ORDERS);",
            replacedBy = "savedOrders = nbt.getCompoundTag(NBT_ORDERS); savedOrders.setBoolean(\"holdFire\", false);",
            fails = "the gun told to hold fire before the restart never held after it", on = "2026-10-18")
    @RedWitness(verdict = "code", member = "TileWeaponConsole#readFromNBT",
            at = "savedOrders = nbt.getCompoundTag(NBT_ORDERS);",
            replacedBy = "savedOrders = nbt.getCompoundTag(NBT_ORDERS); savedOrders.setString(\"accessCode\", \"\");",
            fails = "the network's access code must survive the restart", on = "2026-10-18")
    @RedWitness(verdict = "rule", member = "TileWeaponConsole#readFromNBT",
            at = "savedOrders = nbt.getCompoundTag(NBT_ORDERS);",
            replacedBy = "savedOrders = nbt.getCompoundTag(NBT_ORDERS); savedOrders.removeTag(\"hullAllegiance\");",
            fails = "and its hull rule", on = "2026-10-18")
    @RedWitness(verdict = "the gun's code", member = "TileTurret#getEffectiveAccessCode",
            at = "return state.getAccessCode();",
            replacedBy = "return accessCode;",
            fails = "the gun must judge a body by the network's code after the restart", on = "2026-10-18")
    @Test
    public void aWeaponNetworksOrdersComeBackFromItsConsolesAndTheBatteryObeysThem() throws Exception {
        // ─────── Boot 1: a gun between two consoles, ordered through one of them ───────
        firstBoot = RealDedicatedServerHarness.startWith(harnessBoot(), workDir, /*cleanupOnClose=*/false);
        FixtureSite site = FixtureSite.openAir(0, WEAPON_SITE, WEAPON_SITE);
        site.requireClear(command -> String.join("\n", firstBoot.client().arrange(command)), 1, 2, "a gun between two weapon consoles");
        int y = site.y + 1;
        int z = site.z + 2;
        int gun = site.x + 2;
        int ordering = gun - 1;
        int keeping = gun + 1;
        double targetX = gun + 0.5D, targetY = y + 0.5D, targetZ = z + 40.5D;
        arrange(firstBoot, "stellurgytest chunk forceload 0 " + (gun >> 4) + " " + (z >> 4));

        Events before = events(firstBoot);
        long built = before.markInstrumented();
        placeAt(firstBoot, "stellurgy:weaponConsole", ordering, y, z);
        placeAt(firstBoot, "stellurgy:weaponConsole", keeping, y, z);
        placeAt(firstBoot, "stellurgy:turret", gun, y, z);
        placeAt(firstBoot, "stellurgy:gunBarrel", gun, y + 1, z);
        Weapons.awaitAssembled(before, built, gun, y, z, 1, "the gun never assembled");

        String console = " 0 " + ordering + " " + y + " " + z + " ";
        order(firstBoot, "stellurgytest weaponconsole target" + console + targetX + " " + targetY + " " + targetZ);
        order(firstBoot, "stellurgytest weaponconsole code" + console + WEAPON_CODE);
        order(firstBoot, "stellurgytest weaponconsole allegiance" + console + WEAPON_RULE);
        order(firstBoot, "stellurgytest weaponconsole holdfire" + console + "true");
        arrange(firstBoot, "stellurgytest fill 0 " + ordering + " " + y + " " + z + " " + ordering + " "
                + y + " " + z + " minecraft:air");
        Reply kept = read(firstBoot, "stellurgytest weaponconsole read 0 " + keeping + " " + y + " " + z).requireOk();
        requireArranged("premise: the remaining console must still see the ordered network: " + kept,
                kept.bool("network") && WEAPON_CODE.equals(kept.text("code")) && kept.bool("holdFire"));

        firstBoot.close();
        firstBoot = null;

        // ─────── Boot 2: the network is new; only the remaining console carries the orders ───────
        secondBoot = RealDedicatedServerHarness.startWith(harnessBoot(), workDir, /*cleanupOnClose=*/true);
        Events after = events(secondBoot);
        long restarted = after.markInstrumented();
        arrange(secondBoot, "stellurgytest chunk forceload 0 " + (gun >> 4) + " " + (z >> 4));
        // Charged before it can reach its target, so a gun that is not holding has what it needs to
        // fire the moment it gets there — which is what makes the absence below a reading.
        arrange(secondBoot, "stellurgytest turret charge 0 " + gun + " " + y + " " + z);

        after.awaitRecordWithFields(restarted, "turret_hold_decided",
                "the gun told to hold fire before the restart never held after it",
                Weapons.ARRANGEMENT_TICKS, "pos", Weapons.at(gun, y, z), "held", "true");

        Reply orders = read(secondBoot, "stellurgytest weaponconsole read 0 " + keeping + " " + y + " " + z).requireOk();
        assertEquals("the network's access code must survive the restart: " + orders,
                WEAPON_CODE, orders.text("code"));
        assertEquals("and its hull rule: " + orders, WEAPON_RULE, orders.text("allegiance"));
        assertTrue("and its hold-fire order: " + orders, orders.bool("holdFire"));
        assertTrue("and its target: " + orders, orders.bool("hasTarget"));
        assertEquals("at the same point (x): " + orders, targetX, orders.number("targetX"), 0.0D);
        assertEquals("(y): " + orders, targetY, orders.number("targetY"), 0.0D);
        assertEquals("(z): " + orders, targetZ, orders.number("targetZ"), 0.0D);

        Reply gunRead = read(secondBoot, "stellurgytest turret read 0 " + gun + " " + y + " " + z).requireOk();
        assertEquals("the gun must judge a body by the network's code after the restart, as it did"
                + " before it: " + gunRead, WEAPON_CODE, gunRead.text("code"));

        // The control: the same gun, the same target, hold-fire lifted — it fires.
        long released = after.mark();
        order(secondBoot, "stellurgytest weaponconsole holdfire 0 " + keeping + " " + y + " " + z + " false");
        Weapons.awaitFired(after, released, gun, y, z,
                "the gun never fired once hold-fire was lifted, so its holding proves nothing");
        String fired = after.since(restarted, "turret_fired");
        Events.assertInstrumentRan(fired, "turret_fire_events", "the rounds fired while holding");
        List<String> whileHolding = Events.recordsWhere(fired, "pos", Weapons.at(gun, y, z));
        whileHolding.removeIf(round -> Events.number(round, "seq") >= released);
        assertTrue("the gun fired while the network was holding fire after the restart: " + whileHolding,
                whileHolding.isEmpty());
    }

    private Events events(RealDedicatedServerHarness harness) {
        return new Events(command -> String.join("\n", harness.client().read(command)),
                ticks -> GameTicks.advance(harness.client(), GameTicks.server(), ticks), new EvictionReports());
    }

    /** A console order, refused unless the console was on a network and took it. */
    private static void order(RealDedicatedServerHarness harness, String command) throws Exception {
        Reply reply = arrange(harness, command);
        requireArranged("the console is on no network, so this was not an order: " + reply,
                reply.bool("applied"));
    }

    private void placeAt(RealDedicatedServerHarness harness, String block, int x, int y, int z)
            throws Exception {
        Reply resp = arrange(harness, "stellurgytest place 0 " + x + " " + y + " " + z + " " + block);
        assertTrue(block + " place failed at " + Weapons.at(x, y, z) + ": " + resp, resp.bool("placed"));
    }

    private Reply subnetHeat(RealDedicatedServerHarness harness, int x) throws Exception {
        return read(harness, "stellurgytest subnet info heat 0 " + x + " " + Y + " " + Z);
    }

    private void place(RealDedicatedServerHarness harness, String block, int x) throws Exception {
        Reply resp = arrange(harness, "stellurgytest place 0 " + x + " " + Y + " " + Z + " " + block);
        assertTrue(block + " place failed at " + x + ": " + resp, resp.bool("placed"));
    }

    private Reply subnet(RealDedicatedServerHarness harness, int x) throws Exception {
        return read(harness, "stellurgytest subnet info lifesupport 0 " + x + " " + Y + " " + Z);
    }

    /** A verb that OBSERVES, sent in its role. */
    private static Reply read(RealDedicatedServerHarness harness, String command) throws Exception {
        return Reply.of(command, String.join("\n", harness.client().read(command)));
    }

    /** A step of the arrangement, sent in its role and refused unless it reported ok. */
    private static Reply arrange(RealDedicatedServerHarness harness, String command) throws Exception {
        return Reply.of(command, String.join("\n", harness.client().arrange(command))).requireOk(command);
    }

    /** A production entry called, sent in its role and refused unless it reported ok. */
    private static Reply act(RealDedicatedServerHarness harness, String command) throws Exception {
        return Reply.of(command, String.join("\n", harness.client().act(command))).requireOk(command);
    }
}
