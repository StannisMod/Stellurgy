package dev.stannismod.stellurgy.test.client;

import dev.stannismod.stellurgy.test.EvictionReports;
import com.github.stannismod.forge.testing.junit.AbstractClientE2ETest;
import com.google.gson.JsonObject;
import org.junit.Test;

import dev.stannismod.stellurgy.test.ArrangementFailure;
import dev.stannismod.stellurgy.test.Events;
import dev.stannismod.stellurgy.test.GameTicks;
import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.Plot;
import dev.stannismod.stellurgy.test.Reply;
import dev.stannismod.stellurgy.test.Weapons;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A player names his battery's target with a linker: he binds it to the battery's console, looks at
 * a block twenty blocks off and right-clicks, and the gun on the console's network turns onto it and
 * fires; then he looks at a creature and right-clicks, and it turns onto that instead.
 *
 * <p>The player's path, end to end, every act his client's and every click a press of the right mouse
 * button through the input pipeline, never a verb naming its target: the bind is a sneaking right-click
 * with his crosshair on the console (a plain one opens the console's screen, as on every machine with a
 * screen), the aim is his look, and the designation is a right-click with nothing in reach under the
 * crosshair, which vanilla turns into a use of the held item. Each pick is read before the press. Probes ARRANGE only what no interface lets a player do — the
 * blocks, the gun's charge (power delivery is not this path's subject; a generator beside the gun is
 * the player's way), the linker in his inventory, a creature that stands still — and otherwise only
 * READ.</p>
 *
 * <p>Until 2026-10-04 this path did not exist: binding never took, and a completed link pointed the
 * battery at itself. Every weapon test assigned targets by probe, so nothing saw it.</p>
 *
 * <p><b>What it does NOT see.</b> Whether the rounds HIT: nothing corrects a named target's aim for
 * the round's fall, so an unled round drops short of a far point, and the verdict is that the gun is
 * pointed at what he named and fires on it, not that the round arrives. A gun linked directly rather
 * than through a console, which runs the same designation on the other tile. A ship-mounted battery,
 * and a target on a ship.</p>
 */
public class ALinkerNamesTheBatteryItsTargetE2ETest extends AbstractClientE2ETest {

    /** What the logs this test reads have already announced about evictions. */
    private final EvictionReports evictions = new EvictionReports();

    private static final int OVERWORLD = 0;

    /** The harness's own client username ({@code RealClientHarness}'s single-client default). */
    private static final String PLAYER = "ForgeTestClient";

    /** Vanilla's default sneak binding, left shift. */
    private static final int SNEAK_KEY = 42;

    /** Vanilla's default use-item binding, the right mouse button ({@code GameSettings.keyBindUseItem}). */
    private static final int USE_ITEM_BUTTON = -99;

    /**
     * Deadline for a link that is one network round trip plus a tick of server work: his pose
     * reaching the server, a click being handled. A few ticks when healthy; this is a deadline far
     * past that, never an estimate of it.
     */
    private static final int ROUND_TRIP_TICKS = 200;

    /** How far out of the site the working volume reaches: room for both targets east of the gun. */
    private static final int HALO = 20;

    /** How far east of the gun the target block stands. */
    private static final int TARGET_RANGE = 22;

    /** Vanilla's standing eye height, which both sides add to {@code posY} for the line of sight. */
    private static final double EYE_HEIGHT = 1.62D;

    /**
     * Vanilla's server-side reach for a click on a block: the reach attribute (5 in survival) plus 3
     * of latency slack, {@code NetHandlerPlayServer#processTryUseItemOnBlock}. A target past it is one
     * no click on a block could ever name.
     */
    private static final double BLOCK_CLICK_REACH = 5.0D + 3.0D;

    /**
     * How close the server's look must be to the one set. At the 22-block range a hundredth of a
     * degree moves the line of sight by under 0.004 blocks — far inside the 0.05 the hit is checked to,
     * and more than twice the 0.004-degree difference measured between the look set and the look held.
     */
    private static final double LOOK_TOLERANCE_DEGREES = 0.01D;

    /** An adult zombie's height ({@code EntityZombie}'s own size), for its body centre — the point a
     *  gun follows on a creature it tracks. */
    private static final double ZOMBIE_HEIGHT = 1.95D;

    private static final long ZOMBIE_UUID_MOST = 0x5a11e5ab1e000615L;
    private static final long ZOMBIE_UUID_LEAST = 0x8000000000000615L;
    /** A constant: a String. */
    private static final String ZOMBIE_UUID = new java.util.UUID(ZOMBIE_UUID_MOST, ZOMBIE_UUID_LEAST).toString();

    /**
     * red-witnessed: with {@code LinkerDesignation#bind} at {@code ItemLinker.setMasterCoords(linker, tile.getPos());} removed, fails at the bind: "the linker must carry the CONSOLE's position once he has clicked it … expected:<[x,y,z]> but was:<[0,0,0]>" (2026-10-04; again 2026-10-08 with every click a right-button press through the input pipeline).
     * red-witnessed: with {@code TileWeaponConsole#onLinkAimed} at {@code state.clearTarget();} removed, legs 1 and 2 pass and leg 3 fails: "the gun must turn off the zombie and back onto the block — no `turret_aim` … onTarget true later than" the designation, the gun still firing on the zombie (2026-10-04; again 2026-10-08 through the input pipeline: "no `turret_aim` carrying [pos, …, onTarget, true] later than seq 1930 was recorded within 600 ticks").
     */
    @Test
    public void aPlayerNamesTheBatteryItsTargetByLookingAtItThroughALinker() throws Exception {
        Events server = new Events(this::exec, GameTicks.serverAndClient(serverClient(), GameTicks.server(), bot()::waitWorldTicks), evictions);
        Events client = ClientEvents.of(bot(), GameTicks.serverAndClient(serverClient(), GameTicks.server(), bot()::waitWorldTicks), evictions);

        // ---- where it stands: open air, a plot of its own, proved empty first
        Plot plot = Plot.forScenario(0, getClass().getSimpleName(), OVERWORLD, Plot.Lane.DEFAULT);
        FixtureSite site = plot.site();
        site.requireClear(this::exec, HALO, 4, "the battery, the player beside it and both targets");
        int x0 = site.x, y0 = site.y, z0 = site.z;

        int gx = x0 + 2, gy = y0 + 1, gz = z0 + 2;           // the gun's controller
        int cx = x0 + 1, cy = y0 + 1, cz = z0 + 2;           // its console, touching it on the west
        int tx = gx + TARGET_RANGE, ty = y0 + 1, tz = z0 + 4; // the block he names
        int px = tx, py = y0, pz = z0 - 2;                   // the zombie's footing

        requireOk("a floor for him", exec("stellurgytest fill " + OVERWORLD + " " + (x0 - 1) + " " + y0
                + " " + z0 + " " + (x0 + 4) + " " + y0 + " " + (z0 + 6) + " minecraft:stone"));
        requireOk("the target block", exec(place(tx, ty, tz, "minecraft:stone")));
        requireOk("the zombie's footing", exec(place(px, py, pz, "minecraft:stone")));

        // ---- the battery: console first, then the barrel, then the controller that walks them
        long built = server.mark();
        requireOk("the console", exec(place(cx, cy, cz, "stellurgy:weaponconsole")));
        requireOk("the barrel", exec(place(gx, gy + 1, gz, "stellurgy:gunbarrel")));
        requireOk("the gun", exec(place(gx, gy, gz, "stellurgy:turret")));
        String assembled = server.awaitRecordWithFields(built, "turret_assembled",
                "the gun must count its build before it is anything to aim", Weapons.ARRANGEMENT_TICKS,
                "pos", Weapons.at(gx, gy, gz), "operable", "true");
        long afterAssembly = seqOf(assembled);
        server.awaitMatching(built, "subsystem_network_rebuilt",
                seen -> !laterThan(Events.recordsWhere(seen, "domain", "Weapon"), afterAssembly).isEmpty(),
                "a Weapon rebuild later than the gun's first tick",
                "the gun and its console must be joined into one network", Weapons.ARRANGEMENT_TICKS);
        Reply console = Reply.of(exec(consoleCommand("read", cx, cy, cz))).requireOk("console read");
        ArrangementFailure.requireArranged("the console must command exactly this one gun: " + console,
                console.bool("network") && console.integer("guns") == 1);
        requireOk("the gun's charge", exec(turretCommand("charge", gx, gy, gz)));
        Reply before = Reply.of(exec(turretCommand("read", gx, gy, gz))).requireOk("turret read");
        ArrangementFailure.requireArranged("before he names anything the gun must have NO target, or"
                + " what follows could not be his doing: " + before, !before.bool("hasTarget"));

        // ---- him: on the floor, behind the gun, the linker in his hand
        ClientEvents.placeOntoGroundItHolds(bot(), client, this::exec,
                "tp " + PLAYER + " " + (x0 + 0.5) + " " + (y0 + 1) + " " + (z0 + 4.5),
                x0 + 0.5, y0 + 1, z0 + 4.5, "he stands on the floor behind his battery", ROUND_TRIP_TICKS);
        // Emptied first: a mod hands every joining player a guide book, and it occupies the first slot.
        exec("clear " + PLAYER);
        long given = client.mark();
        exec("give " + PLAYER + " libvulpes:linker");
        client.awaitMatching(given, "client_slot_set",
                seen -> Events.anyRecordFieldContains(seen, "item", "libvulpes:linker"),
                "holding libvulpes:linker", "the linker must reach his inventory", ROUND_TRIP_TICKS);
        bot().selectHotbar(0);
        JsonObject state = bot().reportState();
        ArrangementFailure.requireArranged("the linker must be in his main hand: " + state,
                state.get("heldItem").getAsString().contains("linker"));

        // ---- the bind: a sneaking right-click on the console, the way he would — he looks at the
        // face turned toward him, and the click goes wherever vanilla's own pick says it goes
        setSneaking(server, true);
        lookAt(server, new double[]{cx + 0.5D, cy + 0.5D, cz + 1.0D});
        JsonObject onConsole = bot().reportMouseOver();
        ArrangementFailure.requireArranged("his crosshair must rest on the console before he clicks: " + onConsole,
                "BLOCK".equals(onConsole.get("typeOfHit").getAsString())
                        && onConsole.get("blockX").getAsInt() == cx && onConsole.get("blockY").getAsInt() == cy
                        && onConsole.get("blockZ").getAsInt() == cz);
        long binding = server.mark();
        rightClick();
        String bound = server.awaitRecordWithFields(binding, "weapon_linker_bound",
                "his sneaking right-click on the console must bind the linker to it", ROUND_TRIP_TICKS,
                "pos", Weapons.at(cx, cy, cz));
        assertEquals("the linker must carry the CONSOLE's position once he has clicked it: " + bound,
                Weapons.at(cx, cy, cz), (long) Events.number(bound, "linkedX") + ","
                        + (long) Events.number(bound, "linkedY") + "," + (long) Events.number(bound, "linkedZ"));
        setSneaking(server, false);

        // ---- leg 1: he looks at the block and right-clicks
        double[] faceCentre = {tx, ty + 0.5D, tz + 0.5D};
        double[] eye = lookAt(server, faceCentre);
        double range = distance(eye, faceCentre);
        ArrangementFailure.requireArranged("the target must stand beyond any click on a block ("
                + BLOCK_CLICK_REACH + "), or this proves nothing a click could not do; it stands "
                + range, range > BLOCK_CLICK_REACH);

        requireNothingInReach("the block twenty blocks off");
        long namingBlock = server.mark();
        rightClick();
        String designated = server.awaitRecordWithFields(namingBlock, "weapon_designated",
                "his right-click must hand the console what he was looking at", ROUND_TRIP_TICKS,
                "pos", Weapons.at(cx, cy, cz));
        assertEquals("the console must TAKE the designation: " + designated,
                "true", Events.text(designated, "taken"));
        assertEquals("what he looked at was a BLOCK: " + designated, "BLOCK", Events.text(designated, "hit"));
        double[] hit = {Events.number(designated, "hitX"), Events.number(designated, "hitY"),
                Events.number(designated, "hitZ")};
        // Predicted, not read back: a ray aimed at the centre of the block's near face lands there.
        assertTrue("his line of sight must land on the centre of the target's near face "
                        + Arrays.toString(faceCentre) + ", not " + Arrays.toString(hit),
                distance(hit, faceCentre) < 0.05D);

        awaitGun(server, namingBlock, seqOf(designated), gx, gy, gz, "turret_aim", "onTarget", "true",
                "the gun must turn onto the block he named");
        Reply aimed = Reply.of(exec(turretCommand("read", gx, gy, gz))).requireOk("turret read");
        assertTrue("the gun's target must be the point he named " + Arrays.toString(hit) + ": " + aimed,
                aimed.bool("hasTarget") && distance(hit, targetOf(aimed)) < 1.0E-6D);
        awaitGun(server, namingBlock, seqOf(designated), gx, gy, gz, "turret_fired", null, null,
                "the gun must fire on the block he named");

        // ---- leg 2: a creature, standing still, and he names it instead
        // A zombie and not an animal: the test server runs with spawn-animals off, and vanilla then
        // kills every animal on its first tick, Invulnerable or not (measured 2026-10-04: a summoned
        // pig removed dead within the tick). No AI and unkillable, so the point the gun must follow
        // stays put while it is read; its uuid is given, so the designation is checked against THIS one.
        long summoning = server.mark();
        String summoned = exec("summon minecraft:zombie " + (px + 0.5D) + " " + (py + 1) + " " + (pz + 0.5D)
                + " {NoAI:1b,Invulnerable:1b,UUIDMost:" + ZOMBIE_UUID_MOST + "L,UUIDLeast:" + ZOMBIE_UUID_LEAST + "L}");
        Reply zombies = Reply.of(exec("stellurgytest entity near " + OVERWORLD + " " + (px + 0.5D) + " "
                + (py + 1) + " " + (pz + 0.5D) + " 2 EntityZombie")).requireOk("entity near");
        // the producer always writes `count` on an ok `entity near` reply, so a reply without it is a
        // broken probe and refusing here is the right failure.
        if (zombies.integer("count") != 1) {
            ArrangementFailure.arrangementFailed("exactly one zombie must stand by its footing: " + zombies
                    + " | the summon said: " + summoned
                    + " | removed since the summon: " + server.since(summoning, "entity_removed"));
        }
        // Where it actually stands, read rather than assumed from the summon's arguments.
        Reply zombie = Reply.of(zombies.objectArray("entities")[0]);
        // Shots on the block spent the charge; what the gun has left is not this leg's subject.
        requireOk("the gun's charge", exec(turretCommand("charge", gx, gy, gz)));
        double[] zombieCentre = {zombie.number("x"), zombie.number("y") + ZOMBIE_HEIGHT / 2.0D,
                zombie.number("z")};
        lookAt(server, zombieCentre);

        requireNothingInReach("the zombie");
        long namingZombie = server.mark();
        rightClick();
        String designatedZombie = server.awaitRecordWithFields(namingZombie, "weapon_designated",
                "his right-click on the zombie must hand the console the zombie", ROUND_TRIP_TICKS,
                "pos", Weapons.at(cx, cy, cz));
        assertEquals("what he looked at was a CREATURE: " + designatedZombie,
                "ENTITY", Events.text(designatedZombie, "hit"));
        assertEquals("and it was this zombie: " + designatedZombie, ZOMBIE_UUID, Events.text(designatedZombie, "entity"));
        assertEquals("the console must take it: " + designatedZombie, "true", Events.text(designatedZombie, "taken"));

        // Later than the designation, not merely later than the mark: the gun was on target and
        // firing at the block when he clicked, and an edge or a round from those last ticks would
        // otherwise read as one on the zombie.
        awaitGun(server, namingZombie, seqOf(designatedZombie), gx, gy, gz, "turret_aim", "onTarget", "true",
                "the gun must turn off the block and onto the zombie");
        Reply onZombie = Reply.of(exec(turretCommand("read", gx, gy, gz))).requireOk("turret read");
        assertTrue("the gun must follow the zombie's body centre " + Arrays.toString(zombieCentre)
                + " — a designation that left the block order standing would still point at the"
                + " block: " + onZombie, onZombie.bool("hasTarget") && distance(zombieCentre, targetOf(onZombie)) < 0.05D);
        awaitGun(server, namingZombie, seqOf(designatedZombie), gx, gy, gz, "turret_fired", null, null,
                "the gun must fire on the zombie he named");

        // ---- leg 3: back to the block. A followed creature outranks a point, so this is the leg
        // that fails if naming a point leaves the creature order standing.
        lookAt(server, faceCentre);
        requireNothingInReach("the block again");
        long renamingBlock = server.mark();
        rightClick();
        String designatedAgain = server.awaitRecordWithFields(renamingBlock, "weapon_designated",
                "his right-click on the block must hand the console the block again", ROUND_TRIP_TICKS,
                "pos", Weapons.at(cx, cy, cz));
        assertEquals("what he looked at was the BLOCK again: " + designatedAgain,
                "BLOCK", Events.text(designatedAgain, "hit"));
        awaitGun(server, renamingBlock, seqOf(designatedAgain), gx, gy, gz, "turret_aim", "onTarget", "true",
                "the gun must turn off the zombie and back onto the block");
        Reply back = Reply.of(exec(turretCommand("read", gx, gy, gz))).requireOk("turret read");
        assertTrue("the gun must point at the block again, not keep following the zombie "
                + Arrays.toString(zombieCentre) + ": " + back,
                back.bool("hasTarget") && distance(faceCentre, targetOf(back)) < 0.05D);
    }

    /**
     * Waits for a record of the gun at {@code (x,y,z)} that is LATER than {@code afterSeq} — the
     * designation that is to have caused it — optionally carrying {@code field = value}.
     */
    private static void awaitGun(Events server, long mark, long afterSeq, int x, int y, int z,
                                 String type, String field, String value, String what) throws Exception {
        String[] pairs = field == null ? new String[]{"pos", Weapons.at(x, y, z)}
                : new String[]{"pos", Weapons.at(x, y, z), field, value};
        server.awaitMatching(mark, type,
                seen -> !laterThan(Events.recordsWhereAll(seen, pairs), afterSeq).isEmpty(),
                "carrying " + Arrays.toString(pairs) + " later than seq " + afterSeq, what,
                Weapons.SUBJECT_TICKS);
    }

    /**
     * One press of the right mouse button through the client's input pipeline: the key edge vanilla's
     * keybind poll turns into {@code rightClickMouse()}, which then decides from its own pick whether
     * this is a click on a block, on a creature, or a use of the held item. Released at once, so the
     * held-button repeat never adds a second click.
     */
    private void rightClick() throws Exception {
        bot().setKey(USE_ITEM_BUTTON, true);
        bot().setKey(USE_ITEM_BUTTON, false);
    }

    /**
     * His crosshair rests on nothing within reach, which is what sends vanilla's right-click down the
     * item's own use path rather than onto a block or a creature: the target is named by the linker's
     * long trace, never by the click's pick.
     */
    private void requireNothingInReach(String target) throws Exception {
        JsonObject pick = bot().reportMouseOver();
        ArrangementFailure.requireArranged("looking at " + target + ", nothing within reach may be under"
                + " his crosshair, or the click is not a use of the linker: " + pick,
                "MISS".equals(pick.get("typeOfHit").getAsString()));
    }

    /**
     * Presses or releases sneak, and waits for the SERVER to hold it: the click that follows is
     * decided on the server's idea of whether he is sneaking, and travels in a different packet.
     */
    private void setSneaking(Events server, boolean sneaking) throws Exception {
        long pressed = server.mark();
        bot().setKey(SNEAK_KEY, sneaking);
        server.awaitMatching(pressed, "player_pose_received",
                seen -> !Events.recordsWhereAll(seen, "who", PLAYER, "sneaking", String.valueOf(sneaking))
                        .isEmpty(),
                "sneaking " + sneaking, "the server must hold his sneak before he clicks", ROUND_TRIP_TICKS);
    }

    /**
     * Turns his head to look from his eye at {@code point}, and waits for the SERVER to hold that
     * look: the item-use packet carries no rotation, so a click sent before the look arrives is traced
     * along his previous one.
     *
     * @return his eye, as the look was computed from it
     */
    private double[] lookAt(Events server, double[] point) throws Exception {
        JsonObject state = bot().reportState();
        double[] eye = {state.get("playerX").getAsDouble(), state.get("playerY").getAsDouble() + EYE_HEIGHT,
                state.get("playerZ").getAsDouble()};
        double dx = point[0] - eye[0], dy = point[1] - eye[1], dz = point[2] - eye[2];
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        long turned = server.mark();
        bot().setLook(yaw, pitch);
        server.awaitMatching(turned, "player_pose_received",
                seen -> holdsLook(seen, yaw, pitch),
                "yaw " + yaw + " pitch " + pitch, "the server must hold his look before he clicks",
                ROUND_TRIP_TICKS);
        return eye;
    }

    /**
     * Within {@link #LOOK_TOLERANCE_DEGREES}: the look the server holds is not bit-identical to the
     * one set. Measured 2026-10-04: pitch 2.7286277 set, 2.7246094 held (yaw -90.0 exact). What
     * changes it on the way is not measured.
     */
    private static boolean holdsLook(String sinceReply, float yaw, float pitch) {
        for (String record : Events.recordsWhere(sinceReply, "who", PLAYER)) {
            if (Math.abs(Events.number(record, "yaw") - yaw) < LOOK_TOLERANCE_DEGREES
                    && Math.abs(Events.number(record, "pitch") - pitch) < LOOK_TOLERANCE_DEGREES) {
                return true;
            }
        }
        return false;
    }

    private static java.util.List<String> laterThan(java.util.List<String> records, long seq) {
        java.util.List<String> later = new java.util.ArrayList<>();
        for (String record : records) {
            if (seqOf(record) > seq) {
                later.add(record);
            }
        }
        return later;
    }

    private static long seqOf(String record) {
        return (long) Events.number(record, "seq");
    }

    private static double[] targetOf(Reply turretRead) {
        return new double[]{turretRead.number("targetX"), turretRead.number("targetY"),
                turretRead.number("targetZ")};
    }

    private static double distance(double[] a, double[] b) {
        double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static String place(int x, int y, int z, String block) {
        return "stellurgytest place " + OVERWORLD + " " + x + " " + y + " " + z + " " + block;
    }

    private static String turretCommand(String verb, int x, int y, int z) {
        return "stellurgytest turret " + verb + " " + OVERWORLD + " " + x + " " + y + " " + z;
    }

    private static String consoleCommand(String verb, int x, int y, int z) {
        return "stellurgytest weaponconsole " + verb + " " + OVERWORLD + " " + x + " " + y + " " + z;
    }

    private static void requireOk(String what, String reply) {
        ArrangementFailure.requireArranged(what + " could not be arranged: " + reply,
                reply != null && Reply.of(reply).ok());
    }

    private String exec(String command) throws Exception {
        return String.join("\n", serverClient().execute(command));
    }
}
