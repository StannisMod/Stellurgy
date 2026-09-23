package zmaster587.advancedRocketry.test.client;

import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;


import static org.junit.Assert.assertTrue;
import zmaster587.advancedRocketry.test.PlayerShipData;
import zmaster587.advancedRocketry.test.Events;
import zmaster587.advancedRocketry.test.Reply;
import zmaster587.advancedRocketry.test.FixtureSite;
import zmaster587.advancedRocketry.test.GameTicks;
import zmaster587.advancedRocketry.test.RocketFixture;
import zmaster587.advancedRocketry.test.ShipIdentity;
import zmaster587.advancedRocketry.test.ShipInfo;

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

    private static final String COUNT = "count";
    private static final String LOCAL_X = "localX";
    private static final String LOCAL_Y = "localY";
    private static final String LOCAL_Z = "localZ";
    private static final String PLAYER_X = "playerX";
    private static final String PLAYER_Y = "playerY";
    private static final String PLAYER_Z = "playerZ";

    private static final String VARIANT = "with-pilot-seat";
    /** Roll to command, in degrees. Well past the angle at which an un-held entity would slide off. */
    private static final double ROLL_DEG = 45.0;

    /** The roll's slew window, in ticks of the hull's world clock — the 120 client ticks it was. */
    private static final int ROLL_WINDOW_TICKS = 120;
    /** A deadline for the landing record, not a guess at how long four blocks of fall take. */
    private static final int LANDING_LINK_BUDGET_TICKS = 200;

    private int count(String sub) throws Exception {
        String command = "artest vs " + sub + " 0";
        return Reply.of(command, exec(command)).integer(COUNT);
    }

    private double readDouble(String json, String field) {
        double value = Reply.of(json).number(field);
        return value;
    }

    private static double distance(double[] a, double[] b) {
        double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private String assembleFixture(FixtureSite site, String variant) throws Exception {
        // The site owns the coordinates; these aliases keep the body below unchanged.
        final int baseX = site.x, baseY = site.y, baseZ = site.z;
        // FIRST link: the volume is EMPTY, measured by the air fill's own `placed`. Open air, so
        // this ASSERTS rather than digging the shaft it replaces — whose rim sat above the hull and
        // put a block of world terrain under a crew member standing on his own rolling deck, which
        // is the exact configuration this class is about.
        //
        // HEIGHT 24 is the ENVELOPE: ~10 of hull, the deck on top, a body standing and jumping
        // there, and the room the hull sweeps as it rolls.
        return RocketFixture.assembleAt(site, this::exec, variant, 2, 24,
                "the hull, the deck a crew member rides, and the air it rolls through");
    }

    @Test
    public void aStandingCrewMemberStaysOnTheDeckWhenTheShipRolls() throws Exception {

        // WHERE THIS SCENARIO STANDS IS ASKED FOR, NOT CHOSEN: the plot is this scenario's own, and
        // the height is the open-air band because the site has no Y to pass.
        final FixtureSite site = site();
        final int bx = site.x, by = site.y, bz = site.z;

        // Keep the observer far while the ship spawns (a nearby observer trips the double-load path).
        long awayMark = clientEvents().mark();
        exec("tp @a " + (bx + 600) + " 120 " + (bz + 600) + " 0 0");
        awaitClientPlacedNear(awayMark, bx + 600, bz + 600,
                "the observer that must not be near the spawn is a CLIENT, so his being away is a"
                        + " fact about the client and not about elapsed ticks");

        String assemble = assembleFixture(site, VARIANT);
        assertTrue("a with-pilot-seat build must route to a ship: " + assemble,
                (Reply.of(assemble).integer("rocketCount") == 0));

        // Event-gated async-VS assembly barrier (bounded ceiling + early exit): AWAIT the SPAWNED
        // stage instead of a fixed tick budget that reds a healthy spawn under concurrent-fork load.
        ClientPoll.Result<Integer> spawned = ClientPoll.until(bot()::waitTicks,
                () -> count("ship-count-all"), n -> n >= 1, 5, 40);
        int all = spawned.value;
        assertTrue("assembly must create a ship (all=" + all + ")", all >= 1);

        // WHICH ship, from the assembler that minted its name. The base was the handle before, and a
        // base is a place: this class shares its world, and the lookup answered for the nearest hull
        // whether or not that hull was the one this scenario had just built.
        String shipId = ShipIdentity.awaitPhysicsIdOf(this::exec, 0,
                ShipIdentity.nameFromAssembly(assemble), 40, () -> bot().waitTicks(5));

        exec("tp @a " + (bx + 0.5) + " " + (by + 8) + " " + (bz + 0.5) + " 0 0");
        // Await the ship LOADING near the client (same event-gated barrier, bounded + early exit).
        ClientPoll.Result<Integer> loadedShips = ClientPoll.until(bot()::waitTicks,
                () -> count("ship-count"), n -> n >= 1, 5, 40);
        int loaded = loadedShips.value;
        assertTrue("the ship must LOAD with the client present", loaded >= 1);

        // The ship does not stay at the pad base — which is exactly why it is asked for by NAME.
        // Find it, then drop the bot ONTO it: standing next to a ship would prove nothing.
        ShipInfo where = ShipInfo.byId(this::exec, 0, shipId);
        long dropMark = clientEvents().mark();
        exec("tp @a " + where.x + " " + (where.y + 4) + " " + where.z + " 0 0");
        awaitClientPlacedNear(dropMark, where.x, where.z,
                "the drop onto the deck is the client's fall, so the client must first BE over the"
                        + " deck");
        // THE LANDING IS A LINK. `deck_contact` is the client's resolver owning a tick's move and
        // putting the body on a surface it was not on the tick before, named for the ship whose
        // deck that surface is — the end of this fall. He is four blocks up after the teleport, so
        // the edge is owed. A body that misses the deck is not resolved when it lands and writes
        // nothing, so an expiry here is the drop missing, not a slow fall.
        //
        clientEvents().awaitField(dropMark, "deck_contact", "ship", shipId,
                "the crew member dropped over this scenario's ship must LAND on its deck",
                LANDING_LINK_BUDGET_TICKS);
        // Every read below is the SERVER's, which learns of the landing from the movement packet
        // the client sends at the end of that tick — over the player's connection, not the probe's,
        // so arrival order between the two is not given. The fence is: once the server has echoed
        // a line the client sent after landing, the landing packet has been handled.
        fenceWhatTheClientSent("the server must have handled the landing before it is asked about it");

        PlayerShipData level = PlayerShipData.read(this::exec);
        // "Aboard" is tested by CONTAINMENT (shipLoaded: the player's world position lies inside a
        // loaded ship's box), NOT by VS's own lastTouchedShip. AR now resolves an aboard entity's
        // movement itself and cancels the vanilla move VS associates inside, so lastTouchedShip stays
        // null even though the player is standing on the deck - the containment answer is the true one.
        assertTrue("a player standing on the ship must be recognised as aboard it: " + level.raw(),
                level.shipLoaded);
        // ...and aboard THIS scenario's ship. Containment cannot be satisfied by a distant hull, but
        // it can by an adjacent one, and every deck-frame number read below (localX/Y/Z) is expressed
        // in the subspace of whichever hull answered — so a neighbour here does not mislabel the
        // claim, it changes what the ride comparison at the foot of this method is measuring.
        level.requireAboard( shipId,
                "the crew member must be aboard the ship this scenario built");
        assertTrue("walking crew must NOT be reported as mounted (that is the seated pilot): " + level.raw(),
                !level.mounted);
        assertTrue("a player standing on the deck must be on the ground: " + level.raw(),
                level.onGround);

        double[] localBefore = {level.localX(), level.localY(), level.localZ()};
        double[] worldBefore = {level.playerX, level.playerY, level.playerZ};

        // Roll the ship about its nose. Quaternion (w,x,y,z) for ROLL_DEG about +Z.
        double half = Math.toRadians(ROLL_DEG) / 2.0;
        double upBeforeRoll = ShipInfo.byId(this::exec, 0, shipId).upY();
        String point = exec("artest vs point-by-id 0 " + shipId
                + " " + Math.cos(half) + " 0.0 0.0 " + Math.sin(half));
        assertTrue("attitude hold must accept the roll command: " + point, Reply.of(point).bool("commanded"));
        // WINDOW: `level` before, `rolled` after, and the claim at the foot of this method is the
        // difference between them — how far he moved on the deck against how far in the world —
        // with both readings in its message. Counted on the hull's world clock, and the roll it
        // bought is GATED below: a hull that has turned a few degrees satisfies every claim here
        // without testing any of them.
        GameTicks.advanceWorld(serverClient(), 0, ROLL_WINDOW_TICKS);
        double upAfterRoll = ShipInfo.byId(this::exec, 0, shipId).upY();
        scenario().requireArranged("the deck must actually be rolled before the ride is judged - the"
                        + " test's own premise is at least half of the commanded " + ROLL_DEG
                        + " degrees: upY " + upBeforeRoll + " -> " + upAfterRoll + " over "
                        + ROLL_WINDOW_TICKS + " server ticks",
                upAfterRoll < Math.cos(Math.toRadians(ROLL_DEG / 2.0)));

        PlayerShipData rolled = PlayerShipData.read(this::exec);
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
                + clientEvents().since(0, "deck_entered")
                + " deckReleases=" + clientEvents().since(0, "deck_released")
                // The server's half as ITS records, not a probe reply of statics: the verb that
                // served them is gone, and each record here names the body it is about.
                + " || server ticks=" + Events.fieldLines(
                        events().since(0, "ship_frame_tick"), "line")
                + " || server releases=" + events().since(0, "deck_released"));
        assertTrue("the crew member must still be aboard after the roll: " + rolled.raw(),
                rolled.shipLoaded);
        // The roll is the moment a capture can be handed to the wrong hull, so "still aboard" is only
        // the claim this test means if it is still aboard the SAME ship it started on.
        rolled.requireAboard( shipId,
                "the crew member must still be aboard the ship he started the roll on");
        assertTrue("the crew member must not fall off a rolled deck: " + rolled.raw(),
                rolled.onGround);

        double[] localAfter = {rolled.localX(), rolled.localY(), rolled.localZ()};
        double[] worldAfter = {rolled.playerX, rolled.playerY, rolled.playerZ};

        double movedOnDeck = distance(localBefore, localAfter);
        double movedInWorld = distance(worldBefore, worldAfter);
        assertTrue("the crew member must ride the deck, not the world: he moved " + movedOnDeck
                        + " relative to the ship but only " + movedInWorld + " in the world"
                        + "\n  level  = " + level.raw() + "\n  rolled = " + rolled.raw(),
                movedOnDeck < movedInWorld);
    }
}
