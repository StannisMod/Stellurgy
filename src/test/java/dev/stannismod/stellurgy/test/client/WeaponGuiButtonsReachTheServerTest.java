package dev.stannismod.stellurgy.test.client;

import dev.stannismod.stellurgy.test.EvictionReports;
import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import org.junit.Test;

import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.Weapons;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static org.junit.Assert.assertTrue;

/**
 * The buttons on the weapon console's and the fire-control sensor's screens change what the SERVER's
 * guns and sensors do, and the console's screen shows what the server's network is doing.
 *
 * <p>Every other test of the console and the sensor drives them through probe verbs, which call the
 * same methods on the server directly. A player has no probe: the screen is the only way he has to
 * hold fire, clear a target or switch a sensor to illuminating, and what a button press does is
 * decided on the CLIENT — the screen's button hands the press to the client's copy of the tile. So
 * whether a press reaches the server is a question only a real client connected to a separate server
 * can ask, and this asks it.</p>
 *
 * <p>Each verdict is a link on the server's own record of the thing the press was meant to change:
 * the gun answering that it holds fire ({@code turret_hold_decided}), and the sensor sweeping in the
 * mode it was switched to ({@code sensor_swept}).</p>
 *
 * <p>Gated by {@code forge.test.client.enabled=true}; auto-skips on headless CI.</p>
 *
 * <p>NEW-GROUP: weapons as a real client sees and drives them -- beam, shot and aim replication, the
 * weapon GUIs, the linker, the hand repair. No existing client group holds the weapon cluster; this
 * class and the eight others on {@code AbstractClientE2ETest} that name this cluster are its members
 * until they are folded into one group class. A mechanics test, not an e2e: it arranges the
 * weapon's state by probe.</p>
 */
public class WeaponGuiButtonsReachTheServerTest extends AbstractClientE2ETest {

    /** What the logs this test reads have already announced about evictions. */
    private final EvictionReports evictions = new EvictionReports();

    private static final int DIM = 0;
    /** This class's own site, clear of the other client scenarios. */
    private static final int X = 620, Y = 84, Z = 620;
    /** Controller + four barrels + two cooling jackets; the controller is not a part. */
    private static final int PARTS = 6;
    /** The console's hold-fire button, as the console's own screen numbers it. */
    private static final int CONSOLE_HOLD_FIRE_BUTTON = 0;
    /** The sensor's mode button, as the sensor's own screen numbers it. */
    private static final int SENSOR_MODE_BUTTON = 0;

    /**
     * The console's Hold Fire button makes the battery hold fire on the server — the gun, on target
     * and charged, answers its own hold question with {@code held:true}.
     *
     * <p>This has never been seen GREEN: on the tree as of 2026-09-30 it is RED, because the
     * press never leaves the client — {@code TileWeaponConsole.onInventoryButtonPressed} calls
     * {@code setHoldFire} on the client's own copy of the tile, which looks the network up in the
     * client JVM's (empty) network map and does nothing, and no packet carries the press to the
     * server. Measured on this test's first run.</p>
     *
     * <p>red-witnessed: with {@code TileWeaponConsole#onInventoryButtonPressed} at {@code if (buttonId == BUTTON_HOLD_FIRE)}
     * acting on the client's copy (calling {@code setHoldFire} directly) instead of sending the press
     * to the server — the shape it shipped with — this fails at "the console's Hold Fire button was
     * pressed and the battery never held fire on the server: the press never left the client — no
     * `turret_hold_decided` carrying pos = 623,84,620 and held = true was recorded within 600 ticks"
     * (2026-09-30, on the pre-fix tree; green once the press became a packet the same day).</p>
     */
    @Test
    public void theConsolesHoldFireButtonHoldsTheBatteryOnTheServer() throws Exception {
        Events server = new Events(this::exec, GameTicks.serverAndClient(serverClient(), GameTicks.server(), bot()::waitWorldTicks), evictions);
        Events client = ClientEvents.of(bot(), GameTicks.serverAndClient(serverClient(), GameTicks.server(), bot()::waitWorldTicks), evictions);
        prepareSite();
        int gunX = X + 3, consoleX = X + 4;
        long built = server.markInstrumented();
        buildGun(gunX);
        Weapons.awaitAssembled(server, built, gunX, Y, Z, PARTS, "the gun never assembled");
        placeBlock("stellurgy:weaponConsole", consoleX, Y, Z);
        ask("stellurgytest turret charge " + DIM + " " + gunX + " " + Y + " " + Z).requireOk("charge the gun");

        // The console's target, given the way every other test gives it: the arrangement, not the
        // subject. The gun firing on it proves the console commands the gun before any button.
        long ordered = server.mark();
        Reply applied = ask("stellurgytest weaponconsole target " + DIM + " " + consoleX + " " + Y + " " + Z
                + " " + (gunX + 40.5D) + " " + (Y + 0.5D) + " " + (Z + 0.5D)).requireOk("give the console a target");
        requireArranged("the console refused the target: " + applied, applied.bool("applied"));
        Weapons.awaitFired(server, ordered, gunX, Y, Z,
                "the gun never fired on the console's target, so a hold below would prove nothing");

        standBeside();
        String screen = ClientGuiTestSupport.openGuiByRightClick(bot(), client, consoleX, Y, Z);
        requireArranged("right-clicking the console opened no screen: " + screen, !screen.isEmpty());

        long pressed = server.mark();
        bot().clickButtonById(CONSOLE_HOLD_FIRE_BUTTON);
        ask("stellurgytest turret charge " + DIM + " " + gunX + " " + Y + " " + Z).requireOk("recharge the gun");
        server.awaitRecordWithFields(pressed, "turret_hold_decided",
                "the console's Hold Fire button was pressed and the battery never held fire on the"
                        + " server: the press never left the client",
                Weapons.SUBJECT_TICKS, "pos", Weapons.at(gunX, Y, Z), "held", "true");
        bot().closeScreen();
    }

    /**
     * The console's screen says what the SERVER's network says — its gun count and status — and
     * follows it when it changes: Hold Fire pressed, the screen says the network is holding.
     *
     * <p>The screen's readout is the client log's {@code client_console_readout}, written where the
     * server's readout is handed to the screen's lines. The status is checked against a WINDOW of two
     * server reads, one before the screen opened and one after its readout landed: a network's status
     * may legitimately move in between (a gun finishing its charge), and the screen is right if it
     * shows either of the states the server was in while it was being told.</p>
     *
     * <p>red-witnessed: with {@code TileWeaponConsole#getModules} at {@code modules.add(new ReadoutSync(this));}
     * followed by the client copy filling its own lines ({@code showReadout(readoutTag())}) and
     * {@code TileWeaponConsole#useNetworkData} at {@code if (id == NET_READOUT)} ignoring the server's
     * readout — the shape the screen shipped with, every line derived on the client — this fails at
     * "the console's screen never showed the server's battery — one gun, not holding — no
     * `client_console_readout` carrying pos = 624,84,620 and guns = 1 and holding = false was recorded
     * within 600 ticks", with a {@code client_console_readout} in the same log, so the instrument ran
     * and saw the client's own zero (2026-10-01; green on the fixed tree the same day).</p>
     *
     * <p>red-witnessed: with {@code TileWeaponConsole#readoutTag} at {@code tag.setString("status", networkStatusKey());}
     * writing the fixed key {@code msg.weaponConsole.status.unknown} instead — a screen told SOMETHING,
     * but not the server's status — this fails at "the console's screen shows network status
     * 'unknown', which the server's network was in neither before the screen opened ('disconnected')
     * nor after its readout landed ('disconnected')" (2026-10-01).</p>
     *
     * <p>red-witnessed: with {@code TileWeaponConsole#isUpdateRequired} at {@code return world != null && !world.isRemote && !console.readoutTag().equals(sent);}
     * also requiring {@code sent == null} — the readout sent on opening and never again — this fails at
     * "Hold Fire was pressed and the console's screen never said the network is holding — no
     * `client_console_readout` carrying pos = 624,84,620 and holding = true was recorded within 600
     * ticks" (2026-10-01).</p>
     */
    @Test
    public void theConsolesScreenShowsTheServersNetwork() throws Exception {
        Events server = new Events(this::exec, GameTicks.serverAndClient(serverClient(), GameTicks.server(), bot()::waitWorldTicks), evictions);
        Events client = ClientEvents.of(bot(), GameTicks.serverAndClient(serverClient(), GameTicks.server(), bot()::waitWorldTicks), evictions);
        prepareSite();
        int gunX = X + 3, consoleX = X + 4;
        long built = server.markInstrumented();
        buildGun(gunX);
        Weapons.awaitAssembled(server, built, gunX, Y, Z, PARTS, "the gun never assembled");
        placeBlock("stellurgy:weaponConsole", consoleX, Y, Z);
        ask("stellurgytest turret charge " + DIM + " " + gunX + " " + Y + " " + Z).requireOk("charge the gun");
        String console = DIM + " " + consoleX + " " + Y + " " + Z;
        Reply before = ask("stellurgytest weaponconsole read " + console).requireOk("read the console on the server");
        requireArranged("the console is not commanding the one gun beside it on the server, so its"
                + " screen has nothing to show: " + before, before.bool("network") && before.integer("guns") == 1);

        standBeside();
        long opened = client.mark();
        String screen = ClientGuiTestSupport.openGuiByRightClick(bot(), client, consoleX, Y, Z);
        requireArranged("right-clicking the console opened no screen: " + screen, !screen.isEmpty());
        String shown = client.awaitRecordWithFields(opened, "client_console_readout",
                "the console's screen never showed the server's battery — one gun, not holding",
                Weapons.SUBJECT_TICKS, "pos", Weapons.at(consoleX, Y, Z), "guns", "1", "holding", "false");
        Reply after = ask("stellurgytest weaponconsole read " + console).requireOk("read the console again");
        String screenStatus = Events.text(shown, "status");
        assertTrue("the console's screen shows network status '" + screenStatus + "', which the server's"
                        + " network was in neither before the screen opened ('" + before.text("status")
                        + "') nor after its readout landed ('" + after.text("status") + "')",
                screenStatus.equals(before.text("status")) || screenStatus.equals(after.text("status")));

        long pressed = client.mark();
        bot().clickButtonById(CONSOLE_HOLD_FIRE_BUTTON);
        client.awaitRecordWithFields(pressed, "client_console_readout",
                "Hold Fire was pressed and the console's screen never said the network is holding",
                Weapons.SUBJECT_TICKS, "pos", Weapons.at(consoleX, Y, Z), "holding", "true");
        bot().closeScreen();
    }

    /**
     * The sensor's mode button switches the SERVER's sensor to illuminating — its next sweeps are
     * taken in {@code ACTIVE} mode.
     *
     * <p>This has never been seen GREEN: on the tree as of 2026-09-30 it is RED for the same
     * reason as the console's — {@code TileFireControlSensor.onInventoryButtonPressed} sets the mode
     * field of the client's copy of the tile and sends nothing. Measured on this test's first run.</p>
     *
     * <p>red-witnessed: with {@code TileFireControlSensor#onInventoryButtonPressed} at {@code if (buttonId == BUTTON_MODE)}
     * setting the client copy's mode (calling {@code setMode} directly) instead of sending the press to
     * the server — the shape it shipped with — this fails at "the sensor's mode button was pressed and
     * the sensor on the server never swept in ACTIVE mode: the press never left the client — no
     * `sensor_swept` carrying pos = 627,84,620 and mode = ACTIVE was recorded within 600 ticks"
     * (2026-09-30, on the pre-fix tree; green once the press became a packet the same day).</p>
     */
    @Test
    public void theSensorsModeButtonSwitchesTheSensorOnTheServer() throws Exception {
        Events server = new Events(this::exec, GameTicks.serverAndClient(serverClient(), GameTicks.server(), bot()::waitWorldTicks), evictions);
        Events client = ClientEvents.of(bot(), GameTicks.serverAndClient(serverClient(), GameTicks.server(), bot()::waitWorldTicks), evictions);
        prepareSite();
        int sensorX = X + 7;
        long placed = server.markInstrumented();
        placeBlock("stellurgy:fireControlSensor", sensorX, Y, Z);
        ask("stellurgytest sensor charge " + DIM + " " + sensorX + " " + Y + " " + Z).requireOk("charge the sensor");
        String passive = server.awaitRecordWithFields(placed, "sensor_swept",
                "the sensor never swept, so its mode was never decided on the server",
                Weapons.ARRANGEMENT_TICKS, "pos", Weapons.at(sensorX, Y, Z));
        requireArranged("a fresh sensor is not listening, so switching it proves nothing: " + passive,
                "PASSIVE".equals(Events.text(passive, "mode")));

        standBeside();
        String screen = ClientGuiTestSupport.openGuiByRightClick(bot(), client, sensorX, Y, Z);
        requireArranged("right-clicking the sensor opened no screen: " + screen, !screen.isEmpty());

        long pressed = server.mark();
        bot().clickButtonById(SENSOR_MODE_BUTTON);
        server.awaitRecordWithFields(pressed, "sensor_swept",
                "the sensor's mode button was pressed and the sensor on the server never swept in"
                        + " ACTIVE mode: the press never left the client",
                Weapons.SUBJECT_TICKS, "pos", Weapons.at(sensorX, Y, Z), "mode", "ACTIVE");
        bot().closeScreen();
    }

    // ---- arrangement

    /** Air round the site, a stone floor to stand on. */
    private void prepareSite() throws Exception {
        ask("stellurgytest chunk warmup " + DIM + " " + ((X - 16) >> 4) + " " + ((Z - 16) >> 4) + " "
                + ((X + 64) >> 4) + " " + ((Z + 16) >> 4)).requireOk("warm the site's chunks");
        ask("stellurgytest fill " + DIM + " " + (X - 3) + " " + Y + " " + (Z - 4) + " " + (X + 60) + " "
                + (Y + 8) + " " + (Z + 3) + " minecraft:air").requireOk("clear the site");
        ask("stellurgytest fill " + DIM + " " + (X - 3) + " " + (Y - 1) + " " + (Z - 4) + " " + (X + 12) + " "
                + (Y - 1) + " " + (Z + 3) + " minecraft:stone").requireOk("lay the floor");
        for (int cx = (X - 16) >> 4; cx <= (X + 64) >> 4; cx++) {
            ask("stellurgytest chunk forceload " + DIM + " " + cx + " " + (Z >> 4)).requireOk("hold a chunk");
        }
    }

    private void buildGun(int gx) throws Exception {
        placeBlock("stellurgy:turret", gx, Y, Z);
        for (int i = 1; i <= 4; i++) {
            placeBlock("stellurgy:gunBarrel", gx, Y + i, Z);
        }
        placeBlock("stellurgy:gunCooling", gx, Y, Z + 1);
        placeBlock("stellurgy:gunCooling", gx, Y, Z - 1);
    }

    /** On the floor, a couple of blocks in front of the row, within reach of every block in it. */
    private void standBeside() throws Exception {
        exec("tp @a " + (X + 4.5D) + " " + Y + " " + (Z - 2.5D) + " 0 30");
    }

    private void placeBlock(String block, int x, int y, int z) throws Exception {
        Reply placed = ask("stellurgytest place " + DIM + " " + x + " " + y + " " + z + " " + block);
        requireArranged("failed to place " + block + ": " + placed, placed.bool("placed"));
    }

    /** A vanilla command, whose reply is chat rather than JSON and is not read. */
    private String exec(String command) throws Exception {
        return String.join("\n", serverClient().execute(command));
    }

    private Reply ask(String command) throws Exception {
        return Reply.of(command, exec(command));
    }
}
