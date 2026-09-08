package zmaster587.advancedRocketry.test.client;

import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;
import zmaster587.advancedRocketry.test.Events;
import zmaster587.advancedRocketry.test.ShipIdentity;

/**
 * A crew member who is NOT seated rides a tier-2 ship's deck.
 *
 * <p>The contract, stated as the player sees it: <b>stand on a tier-2 ship, roll the ship, and you
 * are still standing on it.</b> You travel with the deck rather than being left behind in the world
 * or dropped through the hull. This is the foundation every walking-crew feature is built on, so it
 * is pinned before any of them exist.</p>
 *
 * <p>Both halves are asserted against a REAL client standing on a REAL assembled ship:</p>
 * <ul>
 *   <li>the standing player is aboard <em>this</em> ship - his world position lies inside the ship's
 *       box (he is not merely near it) - and he is not "mounted": this is walking crew, not a seated
 *       pilot;</li>
 *   <li>after the ship is commanded to a ~45 degree roll he is <b>still aboard and still on the
 *       ground</b>, and his position <em>relative to the ship</em> has moved far less than his
 *       position in the world - i.e. he rode the deck instead of standing still while it left.</li>
 * </ul>
 *
 * <p>What this deliberately does NOT pin: his orientation, the direction gravity pulls him, or the
 * shape of his collision box. Those are the open problems of the walking-crew work; this test only
 * guarantees the ground he stands on.</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class VSCrewRidesRollingDeckE2ETest extends AbstractSharedVsClientE2ETest {

    @Override
    protected String subsystem() {
        return "vs-crew-rides-rolling-deck";
    }

    private static final Pattern COUNT = Pattern.compile("\"count\":(-?\\d+)");
    private static final Pattern BUILDER_POS =
            Pattern.compile("\"builderPos\":\\[(-?\\d+),(-?\\d+),(-?\\d+)]");
    private static final Pattern POS_X = Pattern.compile("\"posX\":(-?[0-9.E\\-]+)");
    private static final Pattern POS_Y = Pattern.compile("\"posY\":(-?[0-9.E\\-]+)");
    private static final Pattern POS_Z = Pattern.compile("\"posZ\":(-?[0-9.E\\-]+)");
    private static final Pattern LOCAL_X = Pattern.compile("\"localX\":(-?[0-9.E\\-]+)");
    private static final Pattern LOCAL_Y = Pattern.compile("\"localY\":(-?[0-9.E\\-]+)");
    private static final Pattern LOCAL_Z = Pattern.compile("\"localZ\":(-?[0-9.E\\-]+)");
    private static final Pattern PLAYER_X = Pattern.compile("\"playerX\":(-?[0-9.E\\-]+)");
    private static final Pattern PLAYER_Y = Pattern.compile("\"playerY\":(-?[0-9.E\\-]+)");
    private static final Pattern PLAYER_Z = Pattern.compile("\"playerZ\":(-?[0-9.E\\-]+)");

    private static final String VARIANT = "with-pilot-seat";
    private static final int BX = 2900, BY = 64, BZ = 2900;
    /** Roll to command, in degrees. Well past the angle at which an un-held entity would slide off. */
    private static final double ROLL_DEG = 45.0;

    private int count(String sub) throws Exception {
        Matcher m = COUNT.matcher(exec("artest vs " + sub + " 0"));
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    private double readDouble(String json, Pattern p) {
        Matcher m = p.matcher(json);
        assertTrue("expected a number in: " + json, m.find());
        return Double.parseDouble(m.group(1));
    }

    private static double distance(double[] a, double[] b) {
        double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private String assembleFixture(int baseX, int baseY, int baseZ, String variant) throws Exception {
        int cx1 = (baseX - 2) >> 4, cz1 = (baseZ - 2) >> 4;
        int cx2 = (baseX + 7) >> 4, cz2 = (baseZ + 7) >> 4;
        assertTrue("chunk warmup failed",
                exec("artest chunk warmup 0 " + cx1 + " " + cz1 + " " + cx2 + " " + cz2)
                        .contains("\"ok\":true"));
        assertTrue("pre-clear failed",
                exec("artest fill 0 " + (baseX - 2) + " " + (baseY + 1) + " " + (baseZ - 2)
                        + " " + (baseX + 7) + " " + (baseY + 10) + " " + (baseZ + 7) + " minecraft:air")
                        .contains("\"ok\":true"));
        String fixture = exec("artest fixture rocket 0 " + baseX + " " + baseY + " " + baseZ + " " + variant);
        assertTrue("fixture (" + variant + ") failed: " + fixture, fixture.contains("\"ok\":true"));
        Matcher bp = BUILDER_POS.matcher(fixture);
        assertTrue("fixture missing builderPos: " + fixture, bp.find());
        return exec("artest rocket assemble 0 " + bp.group(1) + " " + bp.group(2) + " " + bp.group(3));
    }

    @Test
    public void aStandingCrewMemberStaysOnTheDeckWhenTheShipRolls() throws Exception {

        // Keep the observer far while the ship spawns (a nearby observer trips the double-load path).
        exec("tp @a " + (BX + 600) + " 120 " + (BZ + 600) + " 0 0");
        bot().waitTicks(10);

        String assemble = assembleFixture(BX, BY, BZ, VARIANT);
        assertTrue("a with-pilot-seat build must route to a ship: " + assemble,
                assemble.contains("\"rocketCount\":0"));

        // Event-gated async-VS assembly barrier (load-scaled ceiling + early exit): AWAIT the SPAWNED
        // stage instead of a fixed tick budget that reds a healthy spawn under concurrent-fork load.
        ClientPoll.Result<Integer> spawned = ClientPoll.until(bot()::waitTicks,
                () -> count("ship-count-all"), n -> n >= 1, 5, 40);
        int all = spawned.value;
        assertTrue("assembly must create a ship (all=" + all + ")", all >= 1);
        bot().waitTicks(40);

        // WHICH ship, from the assembler that minted its name. The base was the handle before, and a
        // base is a place: this class shares its world, and the lookup answered for the nearest hull
        // whether or not that hull was the one this scenario had just built.
        String shipId = ShipIdentity.awaitPhysicsIdOf(this::exec, 0,
                ShipIdentity.nameFromAssembly(assemble), 40, () -> bot().waitTicks(5));

        exec("tp @a " + (BX + 0.5) + " " + (BY + 8) + " " + (BZ + 0.5) + " 0 0");
        // Await the ship LOADING near the client (same event-gated barrier, load-scaled + early exit).
        ClientPoll.Result<Integer> loadedShips = ClientPoll.until(bot()::waitTicks,
                () -> count("ship-count"), n -> n >= 1, 5, 40);
        int loaded = loadedShips.value;
        assertTrue("the ship must LOAD with the client present", loaded >= 1);

        // The ship does not stay at the pad base — which is exactly why it is asked for by NAME.
        // Find it, then drop the bot ONTO it: standing next to a ship would prove nothing.
        String where = exec("artest vs ship-info 0 id " + shipId);
        assertTrue("ship must be managed: " + where, where.contains("\"managed\":true"));
        exec("tp @a " + readDouble(where, POS_X) + " " + (readDouble(where, POS_Y) + 4)
                + " " + readDouble(where, POS_Z) + " 0 0");
        bot().waitTicks(80); // fall onto the deck and settle

        String level = exec("artest vs player-ship-data");
        // "Aboard" is tested by CONTAINMENT (shipLoaded: the player's world position lies inside a
        // loaded ship's box), NOT by VS's own lastTouchedShip. AR now resolves an aboard entity's
        // movement itself and cancels the vanilla move VS associates inside, so lastTouchedShip stays
        // null even though the player is standing on the deck - the containment answer is the true one.
        assertTrue("a player standing on the ship must be recognised as aboard it: " + level,
                level.contains("\"shipLoaded\":true"));
        // ...and aboard THIS scenario's ship. Containment cannot be satisfied by a distant hull, but
        // it can by an adjacent one, and every deck-frame number read below (localX/Y/Z) is expressed
        // in the subspace of whichever hull answered — so a neighbour here does not mislabel the
        // claim, it changes what the ride comparison at the foot of this method is measuring.
        ShipIdentity.assertAboardShip(level, shipId,
                "the crew member must be aboard the ship this scenario built");
        assertTrue("walking crew must NOT be reported as mounted (that is the seated pilot): " + level,
                level.contains("\"mounted\":false"));
        assertTrue("a player standing on the deck must be on the ground: " + level,
                level.contains("\"playerOnGround\":true"));

        double[] localBefore = {readDouble(level, LOCAL_X), readDouble(level, LOCAL_Y), readDouble(level, LOCAL_Z)};
        double[] worldBefore = {readDouble(level, PLAYER_X), readDouble(level, PLAYER_Y), readDouble(level, PLAYER_Z)};

        // Roll the ship about its nose. Quaternion (w,x,y,z) for ROLL_DEG about +Z.
        double half = Math.toRadians(ROLL_DEG) / 2.0;
        String point = exec("artest vs point-by-id 0 " + shipId
                + " " + Math.cos(half) + " 0.0 0.0 " + Math.sin(half));
        assertTrue("attitude hold must accept the roll command: " + point, point.contains("\"commanded\":true"));
        bot().waitTicks(120); // let the controller actually roll the ship

        String rolled = exec("artest vs player-ship-data");
        // Client-observed resolution state (the CLIENT owns a player's movement, so ITS ShipFrameTravel
        // statics are the honest half; the server's are the competing resolution). Diagnostic printout
        // for any failure below - which side captured, which side thrashed.
        // The resolver's own capture records rather than a lifetime counter: `resolvedTicks` was
        // cumulative and JVM-global, so it had already been advanced by whatever ran before this
        // scenario and could not be read as "it resolved HIM". Each record names body and ship.
        // The drops, likewise, as the releases they are: the counter that used to be read here was a
        // lifetime total over every body this JVM has resolved, so on a shared client it said nothing
        // about this roll. Each record names its body and the gate's whole reason.
        System.out.println("[rollingdeck] client deckCommits="
                + clientEvents().since(0, "deck_captured")
                + " deckReleases=" + clientEvents().since(0, "deck_released")
                // The server's half as ITS records, not a probe reply of statics: the verb that
                // served them is gone, and each record here names the body it is about.
                + " || server ticks=" + Events.fieldLines(
                        events().since(0, "ship_frame_tick"), "line")
                + " || server releases=" + events().since(0, "deck_released"));
        assertTrue("the crew member must still be aboard after the roll: " + rolled,
                rolled.contains("\"shipLoaded\":true"));
        // The roll is the moment a capture can be handed to the wrong hull, so "still aboard" is only
        // the claim this test means if it is still aboard the SAME ship it started on.
        ShipIdentity.assertAboardShip(rolled, shipId,
                "the crew member must still be aboard the ship he started the roll on");
        assertTrue("the crew member must not fall off a rolled deck: " + rolled,
                rolled.contains("\"playerOnGround\":true"));

        double[] localAfter = {readDouble(rolled, LOCAL_X), readDouble(rolled, LOCAL_Y), readDouble(rolled, LOCAL_Z)};
        double[] worldAfter = {readDouble(rolled, PLAYER_X), readDouble(rolled, PLAYER_Y), readDouble(rolled, PLAYER_Z)};

        double movedOnDeck = distance(localBefore, localAfter);
        double movedInWorld = distance(worldBefore, worldAfter);
        assertTrue("the crew member must ride the deck, not the world: he moved " + movedOnDeck
                        + " relative to the ship but only " + movedInWorld + " in the world"
                        + "\n  level  = " + level + "\n  rolled = " + rolled,
                movedOnDeck < movedInWorld);
    }
}
