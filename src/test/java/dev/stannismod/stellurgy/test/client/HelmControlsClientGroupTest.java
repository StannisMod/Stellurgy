package dev.stannismod.stellurgy.test.client;

import com.google.gson.JsonObject;
import dev.stannismod.stellurgy.test.*;
import dev.stannismod.stellurgy.test.contract.LegacyWitness;
import dev.stannismod.stellurgy.test.contract.Wait;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import static org.junit.Assert.assertEquals;

/**
 * What a pilot's keys do at the helm, beyond flying: the commands a seated player gives his ship.
 *
 * <p>NEW-GROUP: the helm's command keys — a seated pilot's key press, sampled by his client's own key
 * handling and decided on the server for the ship his seat belongs to. Its subject needs a real client
 * (the key, the seat's pilot gate, the view pinned to the ship) and a tier-2 ship; the flight groups
 * hold steering, and no group holds what the helm commands other than motion.</p>
 *
 * <p>What a scenario here does NOT see: the seating itself — he is mounted by probe, as no interface
 * reaches a ship block for a bot — and whether a round HITS (nothing corrects a ship order's aim for the
 * round's fall). A hull that is moving: the lead is pinned at the muzzle by {@code TurretOnAShipTest}.
 * The "weapons carry our code" and "no friends" rules, which that server group pins; the crew rule is
 * here because it needs a player.</p>
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class HelmControlsClientGroupTest extends AbstractSharedVsClientTest {

    /** The harness's own client username ({@code RealClientHarness}'s single-client default). */
    private static final String PLAYER = "ForgeTestClient";

    /** LWJGL's key code for T, the designation's default binding. */
    private static final int KEY_T = 20;

    /**
     * Deadline for a link that is one network round trip plus a tick of server work. A few ticks when
     * healthy; this is a deadline far past that, never an estimate of it.
     */
    private static final int ROUND_TRIP_TICKS = 200;

    /** Vanilla's standing eye height; a seated player's eye is at the same offset from his position. */
    private static final double EYE_HEIGHT = 1.62D;

    /** The two reads of his view that must agree before it is taken as his heading, this many ticks apart. */
    private static final int VIEW_WINDOW_TICKS = 5;
    /** How far apart, in degrees, those two reads may be: a parked hull does not turn at all. */
    private static final double VIEW_STILL_DEGREES = 1.0E-3D;

    /** Where the three hulls are built; the shooter is parked at PARK, the others placed from his view. */
    private static final int SRC_Z = 12000, SHOOTER_SRC_X = 12000, QUARRY_SRC_X = 12400, DECOY_SRC_X = 12800;
    private static final int PARK_X = 12000, PARK_Y = 170, PARK_Z = 14000;
    /** The quarry stands this far along his heading; the decoy nearer, this many degrees off it. */
    private static final double QUARRY_RANGE = 80.0D, DECOY_RANGE = 50.0D, DECOY_OFF_DEGREES = 45.0D;
    private static final double ON_THE_HULL = 64.0D;

    /** Controller + four barrels + two cooling jackets; the controller is not a part. */
    private static final int PARTS = 6;

    @Override
    protected String subsystem() {
        return "helm-controls";
    }

    /**
     * A pilot presses T and his ship's battery engages the ship nearest his heading — not the nearest
     * one, and not one behind him; under "a ship whose crew carries our code is a friend", that ship is
     * fired on while nobody aboard it carries the code and spared once he stands on it carrying it.
     *
     * <p>His view is his ship's heading: his client pins his rotation to the hull's attitude. So the
     * hulls are placed from the view his client reports — a quarry dead ahead and a NEARER decoy
     * forty-five degrees off — and the first T must name the quarry. Moved behind him, the quarry stops
     * being a candidate, and the second T names the decoy. A designation that chose by distance names
     * the decoy first; one that ignored the heading would name the quarry behind him second.</p>
     */
    @LegacyWitness(prose = "with {@code ShipDesignation#designate} at"
            + " {@code double angle = angleFromSight(eye, look, ship.getValue());} ranking the ships ahead by"
            + " DISTANCE instead, this fails at \"with a ship dead ahead and a nearer one forty-five degrees off,"
            + " another was named\" (2026-10-05).")
    @LegacyWitness(prose = "with {@code HullAllegianceRule#isFriend} at"
            + " {@code && CodeUtils.entityHasMatchingCode(player, accessCode)) {} in {@code CODE_ON_CREW} negated,"
            + " this fails at \"with him aboard carrying the code, his battery never decided about that ship again"
            + " — no `turret_fire_decided` carrying … friendly = true\" (2026-10-05).")
    @Test
    public void aPilotsTNamesTheShipAheadAndTheCrewRuleSparesItOnceHeBoardsIt() throws Exception {
        Events server = serverEvents();
        Events client = clientEvents();

        // ---- three hulls: his, and two that will be placed from his view
        WarShip shooter = WarShip.build(server, commands(), FixtureSite.openAir(0, SHOOTER_SRC_X, SRC_Z), null,
                "his own ship");
        disposeOfShipAtEnd(shooter.vsShip);
        shooter.parkAt(PARK_X, PARK_Y, PARK_Z, ON_THE_HULL);
        WarShip quarry = WarShip.build(server, commands(), FixtureSite.openAir(0, QUARRY_SRC_X, SRC_Z), null,
                "the ship he will name");
        disposeOfShipAtEnd(quarry.vsShip);
        WarShip decoy = WarShip.build(server, commands(), FixtureSite.openAir(0, DECOY_SRC_X, SRC_Z), null,
                "the nearer ship off his heading");
        disposeOfShipAtEnd(decoy.vsShip);

        // ---- his battery: a gun beside his seat, a console touching it, on "no ship is a friend"
        int[] seat = shooter.seat();
        int gx = seat[0] + 3, gy = seat[1], gz = seat[2];
        long built = server.markInstrumented();
        buildGun(gx, gy, gz);
        Weapons.awaitAssembled(server, built, gx, gy, gz, PARTS, "his ship's gun never assembled");
        requireOk("charge the gun", arrange("stellurgytest turret charge 0 " + gx + " " + gy + " " + gz).raw());
        int cx = gx + 1;
        long placed = server.mark();
        requireOk("the console", arrange("stellurgytest place 0 " + cx + " " + gy + " " + gz + " stellurgy:weaponConsole").raw());
        server.awaitRecordWithFields(placed, "subsystem_network_rebuilt",
                "the weapons network never rebuilt after the console was placed", Weapons.ARRANGEMENT_TICKS,
                "domain", "Weapon", "dim", "0");
        String console = "0 " + cx + " " + gy + " " + gz;
        Reply network = Reply.of(read("stellurgytest weaponconsole read " + console).raw()).requireOk("read the console");
        ArrangementFailure.requireArranged("the console touching the gun is not commanding it: " + network,
                network.bool("network") && network.integer("guns") == 1);
        requireOk("no ship is a friend", arrange("stellurgytest weaponconsole allegiance " + console + " NONE").raw());

        // ---- him: the code device in his hand (it matters only in the last leg), then his seat
        arrange("clear " + PLAYER).raw();
        long given = client.mark();
        arrange("stellurgytest arrange coded-item " + PLAYER + " ALPHA");
        client.awaitMatching(given, "client_slot_set",
                seen -> Events.anyRecordFieldContains(seen, "item", "affs:code_device"),
                "holding affs:code_device", "the code device must reach his inventory", ROUND_TRIP_TICKS);
        bot().selectHotbar(0);
        PilotSeat helm = PilotSeat.byId(c -> read(c).raw(), 0, shooter.vsShip).requireFound("his ship's pilot seat");
        // Beside his ship first: a seat's mount far from him is an entity his client was never sent,
        // and the server seating him on it is a mount his client cannot follow.
        long walked = client.mark();
        arrange("tp " + PLAYER + " " + helm.shipWorldX + " " + (helm.shipWorldY + 3.0D) + " " + helm.shipWorldZ);
        ClientEvents.awaitPlacedNear(client, walked, helm.shipWorldX, helm.shipWorldZ,
                "he must be put beside his ship before he takes its seat", ROUND_TRIP_TICKS);
        long boarding = client.mark();
        Reply mount = Reply.of(arrange("stellurgytest vs seat-mount-at 0 " + helm.seatX + " " + helm.seatY + " "
                + helm.seatZ).raw()).requireOk("the seat's mount");
        ArrangementFailure.requireArranged("he could not take the mount: " + mount,
                Reply.of(arrange("stellurgytest player mount-entity " + mount.integer("dummyId")).raw()).bool("mounted"));
        ClientEvents.awaitMounted(client, boarding, "his client must seat him at the helm", ROUND_TRIP_TICKS);
        client.awaitField(boarding, "ship_pilot_gate_decided", "open", true,
                "his client must take him for his ship's pilot, or T is not the helm's key", ROUND_TRIP_TICKS);

        // ---- his view, as his client shows it: two reads that agree, since a parked hull is still
        double[] eye = new double[3];
        double[] heading = steadyView(eye);
        double[] ahead = along(eye, heading, QUARRY_RANGE);
        double[] behind = along(eye, heading, -QUARRY_RANGE);
        double[] offAxis = along(eye, turnedAboutUp(heading, DECOY_OFF_DEGREES), DECOY_RANGE);
        quarry.parkAt(round(ahead[0]), round(ahead[1]), round(ahead[2]), ON_THE_HULL);
        decoy.parkAt(round(offAxis[0]), round(offAxis[1]), round(offAxis[2]), ON_THE_HULL);

        // ---- T with the quarry dead ahead and the decoy nearer but off: the quarry
        long namingQuarry = server.markInstrumented();
        pressT();
        String named = server.awaitRecordWithFields(namingQuarry, "ship_designated",
                "his T must reach the helm and name a ship", ROUND_TRIP_TICKS, "pilot", PLAYER);
        assertEquals("with a ship dead ahead and a nearer one forty-five degrees off, another was named: "
                + named, quarry.vsShip, Events.text(named, "ship"));
        Weapons.awaitFired(server, namingQuarry, gx, gy, gz, "his battery never fired on the ship he named");
        assertEquals("his battery is not on the ship he named", quarry.vsShip, consoleTarget(console));

        // ---- the quarry moved behind him: T names the decoy, the one ship still ahead of him
        quarry.parkAt(round(behind[0]), round(behind[1]), round(behind[2]), ON_THE_HULL);
        long namingDecoy = server.mark();
        pressT();
        String renamed = server.awaitRecordWithFields(namingDecoy, "ship_designated",
                "his second T must name a ship again", ROUND_TRIP_TICKS, "pilot", PLAYER);
        assertEquals("with the first ship behind him, the one still ahead was not named: " + renamed,
                decoy.vsShip, Events.text(renamed, "ship"));
        assertEquals("his battery did not move onto the ship he named second", decoy.vsShip,
                consoleTarget(console));

        // ---- a ship whose crew carries our code is a friend — and he becomes its crew
        quarry.parkAt(round(ahead[0]), round(ahead[1]), round(ahead[2]), ON_THE_HULL);
        requireOk("our code", arrange("stellurgytest weaponconsole code " + console + " ALPHA").raw());
        requireOk("crew vouches", arrange("stellurgytest weaponconsole allegiance " + console + " CODE_ON_CREW").raw());
        requireOk("a fresh charge", arrange("stellurgytest turret charge 0 " + gx + " " + gy + " " + gz).raw());
        long crewless = server.markInstrumented();
        pressT();
        String renamedBack = server.awaitRecordWithFields(crewless, "ship_designated",
                "his third T must name a ship", ROUND_TRIP_TICKS, "pilot", PLAYER);
        assertEquals("with the first ship ahead again, it was not named: " + renamedBack, quarry.vsShip,
                Events.text(renamedBack, "ship"));
        // Both fields on ONE record: a decision taken while the gun was still cooling from the last
        // leg reads friendly:false and permitted:false, and says nothing about the rule.
        server.awaitRecordWithFields(crewless, "turret_fire_decided",
                "with nobody aboard the ship ahead carrying the code, his battery was never permitted to"
                        + " fire on it", ROUND_TRIP_TICKS,
                "pos", Weapons.at(gx, gy, gz), "friendly", "false", "permitted", "true");

        arrange("stellurgytest player dismount");
        PilotSeat theirs = PilotSeat.byId(c -> read(c).raw(), 0, quarry.vsShip).requireFound("the ship ahead's seat");
        long boarded = server.mark();
        arrange("tp " + PLAYER + " " + theirs.shipWorldX + " " + (theirs.shipWorldY + 3.0D) + " " + theirs.shipWorldZ);
        String spared = server.awaitRecordWithFields(boarded, "turret_fire_decided",
                "with him aboard carrying the code, his battery never decided about that ship again",
                ROUND_TRIP_TICKS, "pos", Weapons.at(gx, gy, gz), "friendly", "true");
        assertEquals("his battery was PERMITTED to fire on a ship whose crew carries its code: " + spared,
                "false", Events.text(spared, "permitted"));
    }

    /** LWJGL mouse buttons as {@code KeyBinding} codes: left, right, middle. */
    private static final int MOUSE_LEFT = -100, MOUSE_RIGHT = -99, MOUSE_MIDDLE = -98;
    /** The control's site, on the ground, and the cursor scenario's own build site: assembly leaves a pad. */
    private static final int FOOT_X = 13600, FOOT_Z = 12000, CURSOR_SRC_X = 13200;
    /** The seated window is this many times the control's own slowest link, and never shorter than the floor. */
    private static final int WINDOW_FACTOR = 10, WINDOW_FLOOR_TICKS = 20;

    /**
     * From the helm the cursor acts on nothing: no blow, no use, no pick, and no block outlined as the
     * one about to be hit — while the same three buttons, on foot, do all four.
     *
     * <p><b>Why a control, and where its number comes from.</b> The seated half asserts that something
     * does NOT happen, which no record can announce. So the same presses are first made on foot, where
     * each act and the outline are recorded at the client's own seams ({@code client_cursor_act},
     * {@code client_block_outlined}) — proving the stimulus reaches them and that both instruments run —
     * and the seated window is {@value #WINDOW_FACTOR} times the slowest of those links, measured on
     * this run in client ticks (an upper bound: read before the press and after the record arrived).
     * Measured 2026-10-05: the slowest act landed within 6 client ticks, so the window was 60.</p>
     */
    @LegacyWitness(prose = "with {@code KeyBindings#scopeSteeringKeysToCockpit} at"
            + " {@code gs.keyBindAttack.setKeyConflictContext(StellurgyKeyConflictContext.NOT_PILOTING);} and its"
            + " two siblings for use and pick removed, this fails at \"from the helm the cursor acted on the world"
            + " (held 60 ticks)\" (2026-10-05).")
    @LegacyWitness(prose = "with {@code KeyBindings#onHelmBlockHighlight} at"
            + " {@code if (StellurgyKeyConflictContext.PILOTING.isActive()) {} never cancelling, this fails at"
            + " \"from the helm a block was outlined as the one about to be hit (over 60 ticks)\" (2026-10-05).")
    @Test
    @Wait(shape = Wait.Shape.EXPERIMENT, at = "bot().waitWorldTicks(1);",
            why = "one client tick after the block reached his client's world (linked above): the tick in which"
                    + " vanilla re-derives the crosshair's pick from that world (Minecraft.runTick ->"
                    + " getMouseOver). Nothing records a pick, and the read after is that tick's.")
    @Wait(shape = Wait.Shape.EXPERIMENT, at = "bot().waitWorldTicks(window);",
            why = "the three buttons are held for `window` client ticks -- a multiple of the slowest act measured"
                    + " on foot on this run -- and the verdict is that none of them acted and nothing was outlined"
                    + " over it: an absence over a dose, which no record can announce.")
    public void fromTheHelmTheCursorActsOnNothing() throws Exception {
        Events server = serverEvents();
        Events client = clientEvents();
        arrange("gamemode creative " + PLAYER);

        // ---- on foot: a floor under him, looking down at it, the three buttons
        FixtureSite foot = FixtureSite.openAir(0, FOOT_X, FOOT_Z);
        requireOk("a floor", arrange("stellurgytest fill 0 " + (foot.x - 2) + " " + foot.y + " " + (foot.z - 2) + " "
                + (foot.x + 4) + " " + foot.y + " " + (foot.z + 2) + " minecraft:stone").raw());
        long walked = client.mark();
        arrange("tp " + PLAYER + " " + (foot.x + 0.5D) + " " + (foot.y + 1) + " " + (foot.z + 0.5D) + " -90 50");
        ClientEvents.awaitPlacedNear(client, walked, foot.x + 0.5D, foot.z + 0.5D,
                "he must stand on the control's floor", ROUND_TRIP_TICKS);
        long footMark = client.mark();
        long longest = 0;
        for (int button : new int[]{MOUSE_LEFT, MOUSE_RIGHT, MOUSE_MIDDLE}) {
            long pressedAt = client.mark();
            long ticksBefore = clientTicks();
            press(button);
            client.awaitRecordWithFields(pressedAt, "client_cursor_act",
                    "on foot, his " + button + " button never reached the client's own act", ROUND_TRIP_TICKS,
                    "button", String.valueOf(button));
            longest = Math.max(longest, clientTicks() - ticksBefore);
        }
        String outlined = client.awaitRecordWithFields(footMark, "client_block_outlined",
                "on foot, looking at the floor, no block was ever outlined", ROUND_TRIP_TICKS, "outlined", "true");
        System.out.println("[measure] on foot: slowest act within " + longest + " client ticks; outline " + outlined);

        // ---- at the helm: a parked ship, him in its seat, a block before its nose
        WarShip ship = WarShip.build(server, commands(), FixtureSite.openAir(0, CURSOR_SRC_X, SRC_Z), null,
                "the ship he sits in");
        disposeOfShipAtEnd(ship.vsShip);
        ship.parkAt(PARK_X, PARK_Y, PARK_Z, ON_THE_HULL);
        seatAtTheHelm(client, ship);
        double[] eye = new double[3];
        double[] heading = steadyView(eye);
        double[] ahead = along(eye, heading, 3.0D);
        int noseX = (int) Math.floor(ahead[0]), noseY = (int) Math.floor(ahead[1]), noseZ = (int) Math.floor(ahead[2]);
        long noseMark = client.mark();
        Reply nose = Reply.of(arrange("stellurgytest place 0 " + noseX + " " + noseY + " " + noseZ
                + " minecraft:stone").raw())
                .requireOk("a block before the nose");
        ArrangementFailure.requireArranged("the block before the nose was not placed: " + nose, nose.bool("placed"));

        // The outline half asks whether a block UNDER his cursor is outlined; with none under it, its
        // silence would be about the aim. So the block must be in his CLIENT's world before his crosshair
        // is read: linked on the client applying the server's block change, from a mark taken before the
        // placement. A block his client was never sent fails this as an ARRANGEMENT, naming it.
        ArrangementFailure.arranged(() -> client.awaitRecordWithFields(noseMark, "client_block_changed",
                "the block placed before the nose must reach his client's world", ROUND_TRIP_TICKS,
                "x", String.valueOf(noseX), "y", String.valueOf(noseY), "z", String.valueOf(noseZ),
                "block", "minecraft:stone"));
        bot().waitWorldTicks(1);
        JsonObject cursor = bot().reportMouseOver();
        ArrangementFailure.requireArranged("from the helm his cursor is on no block, so nothing could be"
                + " outlined either way: " + cursor, "BLOCK".equals(cursor.get("typeOfHit").getAsString()));

        int window = (int) Math.max(WINDOW_FLOOR_TICKS, longest * WINDOW_FACTOR);
        long seatedMark = client.mark();
        for (int button : new int[]{MOUSE_LEFT, MOUSE_RIGHT, MOUSE_MIDDLE}) {
            bot().setKey(button, true);
        }
        bot().waitWorldTicks(window);
        for (int button : new int[]{MOUSE_LEFT, MOUSE_RIGHT, MOUSE_MIDDLE}) {
            bot().setKey(button, false);
        }
        String acts = client.since(seatedMark, "client_cursor_act");
        String outlines = client.since(seatedMark, "client_block_outlined");
        Events.assertInstrumentRan(acts, "client_cursor_acts", "the cursor did or did not act from the helm");
        Events.assertInstrumentRan(outlines, "client_block_outlines",
                "a block was or was not outlined from the helm");
        assertEquals("from the helm the cursor acted on the world (held " + window + " ticks): " + acts,
                0, Events.records(acts).size());
        assertEquals("from the helm a block was outlined as the one about to be hit (over " + window
                + " ticks): " + outlines, 0, Events.recordsWhere(outlines, "outlined", "true").size());
    }

    /** Seat him at {@code ship}'s helm: beside it first, then its seat, then his client's pilot gate. */
    private void seatAtTheHelm(Events client, WarShip ship) throws Exception {
        PilotSeat helm = PilotSeat.byId(c -> read(c).raw(), 0, ship.vsShip).requireFound("the ship's pilot seat");
        long walked = client.mark();
        arrange("tp " + PLAYER + " " + helm.shipWorldX + " " + (helm.shipWorldY + 3.0D) + " " + helm.shipWorldZ);
        ClientEvents.awaitPlacedNear(client, walked, helm.shipWorldX, helm.shipWorldZ,
                "he must be put beside the ship before he takes its seat", ROUND_TRIP_TICKS);
        long boarding = client.mark();
        Reply mount = Reply.of(arrange("stellurgytest vs seat-mount-at 0 " + helm.seatX + " " + helm.seatY + " "
                + helm.seatZ).raw()).requireOk("the seat's mount");
        ArrangementFailure.requireArranged("he could not take the mount: " + mount,
                Reply.of(arrange("stellurgytest player mount-entity " + mount.integer("dummyId")).raw()).bool("mounted"));
        ClientEvents.awaitMounted(client, boarding, "his client must seat him at the helm", ROUND_TRIP_TICKS);
        client.awaitField(boarding, "ship_pilot_gate_decided", "open", true,
                "his client must take him for the ship's pilot", ROUND_TRIP_TICKS);
    }

    private long clientTicks() throws Exception {
        return bot().reportState().get("ticks").getAsLong();
    }

    private void press(int button) throws Exception {
        bot().setKey(button, true);
        bot().setKey(button, false);
    }

    // ---- driving

    private void pressT() throws Exception {
        bot().setKey(KEY_T, true);
        bot().setKey(KEY_T, false);
    }

    /**
     * His view direction, read off his client twice {@link #VIEW_WINDOW_TICKS} apart and required to
     * agree, by vanilla's own rotation-to-direction formula; fills {@code eye} with his eye position.
     */
    @Wait(shape = Wait.Shape.WINDOW, at = "bot().waitWorldTicks(VIEW_WINDOW_TICKS);",
            why = "two reads of his view and the requirement names both: they must agree. Nothing in the game"
                    + " decides that a view has come to rest, so no record answers.")
    private double[] steadyView(double[] eye) throws Exception {
        JsonObject first = bot().reportState();
        bot().waitWorldTicks(VIEW_WINDOW_TICKS);
        JsonObject second = bot().reportState();
        double yaw = second.get("playerYaw").getAsDouble(), pitch = second.get("playerPitch").getAsDouble();
        ArrangementFailure.requireArranged("his view is not still on a parked hull — read " + first + " then "
                        + second + " — so there is no heading to place the ships from",
                Math.abs(first.get("playerYaw").getAsDouble() - yaw) < VIEW_STILL_DEGREES
                        && Math.abs(first.get("playerPitch").getAsDouble() - pitch) < VIEW_STILL_DEGREES);
        eye[0] = second.get("playerX").getAsDouble();
        eye[1] = second.get("playerY").getAsDouble() + EYE_HEIGHT;
        eye[2] = second.get("playerZ").getAsDouble();
        double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
        return new double[]{-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p)};
    }

    private static double[] along(double[] from, double[] direction, double distance) {
        return new double[]{from[0] + direction[0] * distance, from[1] + direction[1] * distance,
                from[2] + direction[2] * distance};
    }

    /** {@code direction} turned {@code degrees} about the world's vertical. */
    private static double[] turnedAboutUp(double[] direction, double degrees) {
        double a = Math.toRadians(degrees), c = Math.cos(a), s = Math.sin(a);
        return new double[]{direction[0] * c - direction[2] * s, direction[1], direction[0] * s + direction[2] * c};
    }

    private static int round(double v) {
        return (int) Math.round(v);
    }

    private String consoleTarget(String console) throws Exception {
        return Reply.of(read("stellurgytest weaponconsole read " + console).raw()).requireOk("read the console")
                .text("targetShip");
    }

    private void buildGun(int gx, int gy, int gz) throws Exception {
        requireOk("the gun", arrange("stellurgytest place 0 " + gx + " " + gy + " " + gz + " stellurgy:turret").raw());
        for (int i = 1; i <= 4; i++) {
            requireOk("a barrel", arrange("stellurgytest place 0 " + gx + " " + (gy + i) + " " + gz + " stellurgy:gunBarrel").raw());
        }
        requireOk("a jacket", arrange("stellurgytest place 0 " + gx + " " + gy + " " + (gz + 1) + " stellurgy:gunCooling").raw());
        requireOk("a jacket", arrange("stellurgytest place 0 " + gx + " " + gy + " " + (gz - 1) + " stellurgy:gunCooling").raw());
    }

    private static void requireOk(String what, String reply) {
        Reply.of(what, reply).requireOk(what);
    }
}
