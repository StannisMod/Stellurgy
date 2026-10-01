package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.Reply;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static dev.stannismod.stellurgy.test.ArrangementFailure.requireArranged;
import static dev.stannismod.stellurgy.test.StellurgyTestConstants.ppm;
import static dev.stannismod.stellurgy.test.server.WorldCommandFixtures.arrange;
import static dev.stannismod.stellurgy.test.server.WorldCommandFixtures.ask;

/**
 * What being too hot COSTS: the two rungs of the failure ladder that a ship can reach today.
 *
 * <p>The ladder's clause is an ORDER and a SUBJECT per rung, never a threshold. So every scenario
 * here reads the threshold off the server and arranges the world on the far side of it - a test that
 * named a temperature would be restating a tuned number instead of the rule that crossing it changes
 * what the room, or the drive, IS.</p>
 *
 * <ul>
 *   <li><b>Crew damage - subject: the zone's air.</b> An overheated compartment presents one of the
 *       hostile atmospheres a scorching planet already presents, so the same suit protects against
 *       it and there is no second damage path. What is pinned here is that the room turns hostile
 *       and turns back, and that the gases only choose which variant of hostile it is.</li>
 *   <li><b>Hyperdrive refusal - subject: the coolant the drive is bolted to.</b> Free, and above the
 *       commit line: the pilot is told no and his capacitor is still full.</li>
 * </ul>
 *
 * <p><b>What this cannot cover, and why.</b> The crew rung's damage itself is not observable on this
 * tier: {@code AtmosphereHandler.onTick} returns before the effect path for a connectionless player,
 * which is every player a headless server can supply, so a health assertion here would measure the
 * harness rather than the mechanic. The damage a hostile atmosphere does is pinned where a real
 * player exists - {@code test/client/VacuumAndSuitClientGroupE2ETest
 * .overheatedZoneAirHurtsAnUnsuitedCrewman} - and the derivation that puts the player in one is
 * pinned here and in {@code unit/AirStateTest}.</p>
 */
public class HeatFailureLadderTest extends AbstractSharedServerTest {

    /** The compartment's vent: a sealed room on this scenario's own site (see {@link #buildRoomWithVent}). */
    private int roomX;
    private int roomY;
    private int roomZ;

    /**
     * The ship's flight computer as {@code x y z}, and its navigation computer two blocks under it
     * as {@code dim x y z}; both from this scenario's own site (see {@link #standShip}). One
     * scenario lays coolant against the drive and one does not - the second is the control.
     */
    private String ship;
    private String nav;

    /**
     * Ask for this scenario's site and prove it empty. The drive fixture clears twenty blocks either
     * side of the flight computer on its own two layers, so the site's whole allowance is taken.
     */
    private void standShip(String what) throws Exception {
        FixtureSite site = clearedSite(20, 4, what);
        ship = site.x + " " + (site.y + 3) + " " + site.z;
        nav = site.dim + " " + site.x + " " + (site.y + 1) + " " + site.z;
    }

    /** The drive's own coolant run: three pipes laid along the top of the generator. */
    private static final int PIPES = 3;

    // ─── Rung one: the air is what hurts the crew ──────────────────────────────

    /**
     * A compartment past the crew threshold is a hostile atmosphere, and cooling it gives the room
     * back.
     *
     * <p>The second half is what makes the first a measurement. "The room reports VeryHot" would
     * also be true of a build that reported VeryHot for every room, and of one that latched on the
     * first hazard it ever saw - which is a real failure mode this very code path has had.</p>
     *
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. HOSTILE — {@code AirState#deriveAtmosphere} at {@code if (config.shipHeatCrewVeryHotKelvin > 0 && kelvin >= config.shipHeatCrewVeryHotKelvin)} raising
     * the rung 1000 K: "past the threshold the room itself is the hazard … expected:&lt;[VeryHot]&gt;
     * but was:&lt;[PressurizedAir]&gt;". BACK — {@code AreaBlob#setData} at {@code data = obj;} refusing to publish over a VeryHot
     * zone: "and the room must come BACK when it is cooled … expected:&lt;[PressurizedAir]&gt; but
     * was:&lt;[VeryHot]&gt;". The two premises at its head are arrangements and are not witnessed.</p>
     */
    @Test
    public void anOverheatedCompartmentTurnsHostileAndCoolingItGivesTheRoomBack() throws Exception {
        buildRoomWithVent();
        int veryHot = configInt("shipHeatCrewVeryHotKelvin");
        int ambient = configInt("shipHeatAmbientKelvin");
        assertTrue("premise: the rung must be switched on, or this scenario asks nothing",
                veryHot > 0 && ambient < veryHot);

        setAir(790_000, 210_000, 0, ambient * 1000);
        assertEquals("premise: a room at cabin temperature must be an ordinary breathable room",
                "PressurizedAir", atmosphereInRoom());

        setAir(790_000, 210_000, 0, (veryHot + 10) * 1000);
        assertEquals("past the threshold the room itself is the hazard, and it is the same hazard a "
                + "scorching planet presents - so the suit that works there works here",
                "VeryHot", atmosphereInRoom());

        setAir(790_000, 210_000, 0, ambient * 1000);
        assertEquals("and the room must come BACK when it is cooled: a rung that latched would leave "
                + "a crew in a repaired ship still dying in it", "PressurizedAir", atmosphereInRoom());
    }

    /**
     * The temperature chooses the rung; the gases choose which variant of it.
     *
     * <p>Two facts about one room - it is cooking you and there is nothing to breathe - and the
     * existing atmosphere types say both at once. Asserting the plain variant alongside is what
     * stops this passing on a build that simply always answers with the NoO2 one.</p>
     *
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. BOTH HAZARDS — {@code AirState#deriveAtmosphere} at {@code return breathableGas ? Atmosphere.SUPERHEATED : Atmosphere.SUPERHEATEDNOO2;}
     * always answering the breathable variant: "a suffocating room that is also lethally hot must say
     * so expected:&lt;Superheated[NoOxygen]&gt; but was:&lt;Superheated[]&gt;". THE PLAIN ONE — {@code AirState#deriveAtmosphere} at {@code return breathableGas ? Atmosphere.SUPERHEATED : Atmosphere.SUPERHEATEDNOO2;} always answering the NoO2 variant: "and the same temperature with air to breathe
     * is the plain lethal one expected:&lt;Superheated[]&gt; but was:&lt;Superheated[NoOxygen]&gt;".
     * The premise at its head is an arrangement and is not witnessed.</p>
     */
    @Test
    public void hotAirWithNothingToBreatheIsBothHazardsAtOnce() throws Exception {
        buildRoomWithVent();
        int superheated = configInt("shipHeatCrewSuperheatedKelvin");
        assertTrue("premise: the harsher rung must be switched on", superheated > 0);

        setAir(1_000_000, 0, 0, (superheated + 10) * 1000);
        assertEquals("a suffocating room that is also lethally hot must say so",
                "SuperheatedNoOxygen", atmosphereInRoom());

        setAir(790_000, 210_000, 0, (superheated + 10) * 1000);
        assertEquals("and the same temperature with air to breathe is the plain lethal one",
                "Superheated", atmosphereInRoom());
    }

    // ─── Rung four: the drive will not fire ────────────────────────────────────

    /**
     * A drive whose coolant is past the threshold refuses, says why, and charges nothing for it.
     *
     * <p>The unspent capacitor is half the clause. A refusal raised below the commit line costs the
     * pilot nothing, and the whole reason this check lives at the gate rather than at the burst is
     * that a paid refusal is the failure the jump sequence is built to prevent.</p>
     *
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. MUST NOT FIRE — {@code JumpGate#check} at {@code return ship.driveCoolantKelvin() < refusalKelvin ? null}
     * raising the refusal 10000 K: "a drive whose coolant is past the threshold must not fire: …
     * \"allowed\":true". WHICH REFUSAL — {@code JumpGate#check} at {@code : new Objection(Severity.HARD, MSG_DRIVE_OVERHEATED);} raising the no-drive message instead:
     * "and the pilot must be told which of the refusals this is: … \"message\":\"msg.jumpgate.nodrive\"".
     * NOT WOUND UP — {@code JumpTrigger#press} at {@code if (!verdict.allowed())} ignoring the verdict: "a refused jump must not wind the
     * drive up: {\"ok\":true,\"spooling\":true}". COSTS NOTHING — {@code JumpTrigger#press} at {@code spool.clearWarning();} firing the
     * burst before refusing: "and must cost the pilot nothing … \"charge\":720000". REMEMBERS NOTHING
     * — {@code JumpTrigger#press} at {@code spool.clearWarning();} clearing the aim on a refusal: "cooling the ship is the whole of the
     * fix - the gate is read-only and remembers nothing: … \"allowed\":false". The premises at its
     * head and the two after the cook and the cool are arrangements and are not witnessed.</p>
     *
     * <p>What the gate read is not asserted beside the message: the overheated refusal is raised only
     * when the same coolant reading is past the threshold, so it would be WHICH REFUSAL again.</p>
     */
    @Test
    public void anOverheatedDriveRefusesToFireAndTheRefusalIsFree() throws Exception {
        standShip("an armed ship with coolant laid against its drive");
        buildArmedShip(ship, nav);
        layCoolantAlongTheGenerator();
        int refusal = configInt("shipHeatDriveRefusalKelvin");
        assertTrue("premise: the rung must be switched on", refusal > 0);

        Reply cold = driveInfo(ship);
        assertTrue("premise: the ship must be able to jump before it is cooked: " + cold,
                cold.bool("allowed"));
        long coldReading = cold.longInteger("driveCoolantMilliK");
        assertTrue("premise: the drive must actually have found the coolant against it, or the "
                + "refusal below would be about a loop nobody measured: " + cold, coldReading > 0);
        assertTrue("premise: and that coolant must start well below the threshold: " + cold,
                coldReading < refusal * 1000L);
        long chargeBefore = cold.longInteger("charge");
        assertTrue("premise: with an empty bank the gate would refuse for a different reason: " + cold,
                chargeBefore >= cold.longInteger("burstCost"));

        Reply cooked = cook(refusal);
        assertTrue("premise: the loop must actually have been driven past the threshold: " + cooked,
                cooked.longInteger("temperatureMilliK") >= refusal * 1000L);

        Reply hot = driveInfo(ship);
        assertFalse("a drive whose coolant is past the threshold must not fire: " + hot,
                hot.bool("allowed"));
        assertEquals("and the pilot must be told which of the refusals this is: " + hot,
                "msg.jumpgate.driveoverheated", hot.text("message"));

        Reply pressed = arrange("stellurgytest drive press 0 " + ship);
        assertFalse("a refused jump must not wind the drive up: " + pressed, pressed.bool("spooling"));
        Reply afterPress = driveInfo(ship);
        assertEquals("and must cost the pilot nothing - this refusal is above the commit line: "
                + afterPress, chargeBefore, afterPress.longInteger("charge"));

        Reply shed = arrange("stellurgytest heat cycle 0 " + pipeAt(0) + " 0 1");
        requireArranged("premise: the drive's pipes must still be a loop: " + shed, shed.bool("inLoop"));
        assertTrue("premise: the loop must actually have shed what it was holding: " + shed,
                shed.longInteger("temperatureMilliK") < refusal * 1000L);
        Reply cooled = driveInfo(ship);
        assertTrue("cooling the ship is the whole of the fix - the gate is read-only and remembers "
                + "nothing: " + cooled, cooled.bool("allowed"));
    }

    /**
     * A drive with no coolant against it is unmeasured, and an unmeasured drive is not refused.
     *
     * <p>Zero is the answer for "nobody is watching this", and it must not read as "cold enough" by
     * accident nor as "too hot" by defaulting the safe way: a ship built before anyone laid a pipe
     * has to keep flying.</p>
     *
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. NOTHING TO READ — {@code ShipDrive#coolantKelvin} at {@code double hottest = 0.0D;} starting the reading at ambient: "with nothing bolted to the drive there is
     * nothing to read: … \"driveCoolantMilliK\":293000". ALLOWED — {@code JumpGate#check} at {@code return ship.driveCoolantKelvin() < refusalKelvin ? null} refusing an
     * unmeasured drive: "and an unmeasured drive must still be allowed to jump: … \"allowed\":false".</p>
     */
    @Test
    public void aDriveWithNoCoolantAgainstItIsNotMeasuredAndNotRefused() throws Exception {
        standShip("an armed ship with nothing against its drive");
        buildArmedShip(ship, nav);

        Reply info = driveInfo(ship);

        assertEquals("with nothing bolted to the drive there is nothing to read: " + info,
                0L, info.longInteger("driveCoolantMilliK"));
        assertTrue("and an unmeasured drive must still be allowed to jump: " + info,
                info.bool("allowed"));
    }

    // ─── the rig ───────────────────────────────────────────────────────────────

    /** A sealed room with a powered, sealed vent - the same rig the zone-air tests run on. */
    private void buildRoomWithVent() throws Exception {
        FixtureSite site = clearedSite(2, 6, "a sealed room with a powered vent");
        roomX = site.x + 2;
        roomY = site.y + 2;
        roomZ = site.z + 2;
        arrange("stellurgytest fill 0 " + (roomX - 2) + " " + (roomY - 1) + " " + (roomZ - 2)
                + " " + (roomX + 2) + " " + roomY + " " + (roomZ + 2) + " minecraft:stone");
        for (int yy = roomY + 1; yy <= roomY + 2; yy++) {
            arrange("stellurgytest fill 0 " + (roomX - 2) + " " + yy + " " + (roomZ - 2)
                    + " " + (roomX + 2) + " " + yy + " " + (roomZ + 2) + " minecraft:stone");
            arrange("stellurgytest fill 0 " + (roomX - 1) + " " + yy + " " + (roomZ - 1)
                    + " " + (roomX + 1) + " " + yy + " " + (roomZ + 1) + " minecraft:air");
        }
        arrange("stellurgytest fill 0 " + (roomX - 2) + " " + (roomY + 3) + " " + (roomZ - 2)
                + " " + (roomX + 2) + " " + (roomY + 3) + " " + (roomZ + 2) + " minecraft:stone");

        Reply vent = arrange("stellurgytest place 0 " + roomX + " " + roomY + " " + roomZ
                + " stellurgy:oxygenVent");
        assertTrue("vent place failed: " + vent, vent.bool("placed"));
        arrange("stellurgytest energy inject 0 " + roomX + " " + roomY + " " + roomZ + " 1000000");
        arrange("stellurgytest fluid inject 0 " + roomX + " " + roomY + " " + roomZ + " oxygen 16000");
        arrange("stellurgytest tile force-tick 0 " + roomX + " " + roomY + " " + roomZ + " 1");
        arrange("stellurgytest vent reseal 0 " + roomX + " " + roomY + " " + roomZ);
        arrange("stellurgytest tile force-tick 0 " + roomX + " " + roomY + " " + roomZ + " 5");
    }

    /** Gases in parts per million of an atmosphere, which is how a room's mix is quoted. */
    private void setAir(int n2, int o2, int co2, int milliK) throws Exception {
        arrange("stellurgytest vent setair 0 " + roomX + " " + roomY + " " + roomZ
                + " " + ppm(n2) + " " + ppm(o2) + " " + ppm(co2) + " " + milliK);
    }

    /** What a person standing in the compartment breathes, as the handler publishes it. */
    private String atmosphereInRoom() throws Exception {
        return ask("stellurgytest atmosphere get 0 " + roomX + " " + (roomY + 1) + " " + roomZ)
                .text("type");
    }

    private Reply driveInfo(String afc) throws Exception {
        return ask("stellurgytest drive info 0 " + afc);
    }

    /** A ship that can jump: a drive, a full bank, a navigation computer, a target, armed. */
    private void buildArmedShip(String afc, String nav) throws Exception {
        arrange("stellurgytest drive build 0 " + afc + " 4 8 0 0 0");
        arrange("stellurgytest drive charge 0 " + afc + " full");
        arrange("stellurgytest nav place " + nav);
        Reply linked = arrange("stellurgytest nav link " + nav + " " + afc);
        requireArranged("the navigation computer must be linked to the drive: " + linked,
                linked.bool("linked"));
        arrange("stellurgytest nav target " + nav + " 7 0 0");
        Reply armed = arrange("stellurgytest drive arm 0 " + afc + " on");
        assertTrue("the ship must be armed, or the gate refuses for a different reason: " + armed,
                armed.bool("armed"));
    }

    /**
     * Coolant pipe along the top of the generator. The drive fixture stands the generator two blocks
     * from the flight computer with its coils running one way, so the row above it is clear - and
     * "bolted to the drive" is exactly this: pipe touching the machine's own footprint.
     */
    private void layCoolantAlongTheGenerator() throws Exception {
        for (int i = 0; i < PIPES; i++) {
            Reply placed = arrange("stellurgytest place 0 " + pipeAt(i) + " stellurgy:heatPipe");
            assertTrue("pipe place failed at " + pipeAt(i) + ": " + placed, placed.bool("placed"));
        }
        Reply solved = arrange("stellurgytest subnet solve all 0 1");
        assertEquals("the loop never solved, so it does not exist yet: " + solved,
                1, solved.integer("ticksSolved"));
    }

    /** Charge the drive's loop past {@code refusalKelvin} in one call, and answer what it reached. */
    private Reply cook(int refusalKelvin) throws Exception {
        Reply empty = arrange("stellurgytest heat cycle 0 " + pipeAt(0) + " 0 1");
        requireArranged("premise: the drive's pipes must form a loop: " + empty, empty.bool("inLoop"));
        long capacity = empty.longInteger("heatCapacity");
        assertTrue("premise: the loop must have thermal mass to charge: " + empty, capacity > 0);
        int ambient = configInt("shipHeatAmbientKelvin");
        long charge = (refusalKelvin + 100L - ambient) * capacity;
        return arrange("stellurgytest heat cycle 0 " + pipeAt(0) + " " + charge + " 1");
    }

    /** The i-th pipe of the drive's coolant run: above the generator, running away from the coils. */
    private String pipeAt(int i) {
        String[] afc = ship.split(" ");
        int x = Integer.parseInt(afc[0]) + 2;
        int y = Integer.parseInt(afc[1]) + 1;
        int z = Integer.parseInt(afc[2]) - i;
        return x + " " + y + " " + z;
    }

    private int configInt(String key) throws Exception {
        return arrange("stellurgytest config get " + key).integer("value");
    }
}
