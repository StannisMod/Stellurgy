package dev.stannismod.stellurgy.test.client;

import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import org.junit.Test;

import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.Weapons;

import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;

/**
 * The buttons on the weapon console's and the fire-control sensor's screens change what the SERVER's
 * guns and sensors do.
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
 */
public class WeaponGuiButtonsReachTheServerE2ETest extends AbstractClientE2ETest {

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
        Events server = new Events(this::exec, bot()::waitTicks);
        Events client = ClientEvents.of(bot());
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
        Events server = new Events(this::exec, bot()::waitTicks);
        Events client = ClientEvents.of(bot());
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
