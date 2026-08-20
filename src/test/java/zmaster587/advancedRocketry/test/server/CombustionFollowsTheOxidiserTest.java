package zmaster587.advancedRocketry.test.server;

import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static zmaster587.advancedRocketry.test.AdvancedRocketryTestConstants.ppm;
import static zmaster587.advancedRocketry.test.server.WorldCommandFixtures.exec;

/**
 * Whether a fire can start is asked of the AIR, on the path the game actually uses.
 *
 * <p>Combustion is decided by the OXIDISER, never by breathability. The two were one boolean for
 * years, assigned from the BREATHING band, so a room nobody could breathe still lit torches. The unit
 * test pins the predicate; this pins that the game consults it — a real sealed compartment, its real
 * air, and the same call every ignition path makes.</p>
 *
 * <p><b>The label is asserted BESIDE the answer, and it still disagrees.</b> That is not an oversight:
 * the atmosphere types keep their hand-assigned flags until the slice that deletes them, so the
 * readout carries both and this test states which one the game obeys. When the types go, the label
 * field goes with them and this assertion changes shape rather than quietly passing.</p>
 */
public class CombustionFollowsTheOxidiserTest extends AbstractSharedServerTest {

    private static final Pattern TYPE = Pattern.compile("\"type\":\"([^\"]*)\"");
    private static final Pattern COMBUSTIBLE = Pattern.compile("\"combustible\":(true|false)");
    private static final Pattern LABEL = Pattern.compile("\"labelCombustible\":(true|false)");
    private static final Pattern BREATHABLE_AIR = Pattern.compile("\"breathableAir\":(true|false)");
    private static final Pattern OXYGEN = Pattern.compile("\"oxygen\":(\\d+)");

    /** Well clear of every other fixture in the shared world. */
    private static final int ROOM_X = 1320;
    private static final int ROOM_Y = 100;
    private static final int ROOM_Z = 2880;

    /**
     * Far below anything that burns, and far below anything that can be breathed. In parts per
     * million of an atmosphere, which is the unit a room's mix is quoted in; the readout answers in
     * the composition's own finer unit, so an assertion against it converts.
     */
    private static final int THIN_OXYGEN = 50_000;
    private static final int NORMAL_OXYGEN = 210_000;

    /**
     * The same room twice: too thin to burn, then ordinary air. The label says "combustible" in both,
     * and the game must follow the air.
     */
    @Test
    public void aRoomTooThinToBurnRefusesFireWhileItsLabelStillSaysOtherwise() throws Exception {
        buildRoomWithVent();

        setAir(AirMix.THIN);
        String thin = atmosphere();
        assertEquals("premise: the composition must have arrived: " + thin,
                ppm(THIN_OXYGEN), longOf(thin, OXYGEN));
        assertEquals("premise: air this thin is not breathable: " + thin,
                "false", stringOf(thin, BREATHABLE_AIR));
        assertEquals("nothing may light in air this thin - and this is the defect the slice closes,"
                + " because the LABEL below still says it can: " + thin,
                "false", stringOf(thin, COMBUSTIBLE));
        assertEquals("the label's own flag is unchanged, and it is WRONG - it was assigned from the"
                + " breathing band. It stays visible until the types are deleted: " + thin,
                "true", stringOf(thin, LABEL));
        assertEquals("premise: the label the game shows is still the thin-air one: " + thin,
                "lowO2", stringOf(thin, TYPE));

        setAir(AirMix.NORMAL);
        String normal = atmosphere();
        assertEquals("premise: the room was refilled: " + normal,
                ppm(NORMAL_OXYGEN), longOf(normal, OXYGEN));
        assertEquals("ordinary air burns: " + normal, "true", stringOf(normal, COMBUSTIBLE));
        assertEquals("and is breathable: " + normal, "true", stringOf(normal, BREATHABLE_AIR));
    }

    // ─── the rig ───────────────────────────────────────────────────────

    private enum AirMix { THIN, NORMAL }

    private void setAir(AirMix mix) throws Exception {
        int oxygen = mix == AirMix.THIN ? THIN_OXYGEN : NORMAL_OXYGEN;
        String set = exec("artest vent setair 0 " + ROOM_X + " " + ROOM_Y + " " + ROOM_Z
                + " " + ppm(1_000_000 - oxygen) + " " + ppm(oxygen) + " 0");
        assertTrue("setair failed: " + set, set.contains("\"ok\":true"));
    }

    /** What a person standing in the room breathes, and what the air itself says about burning. */
    private String atmosphere() throws Exception {
        return exec("artest atmosphere get 0 " + ROOM_X + " " + (ROOM_Y + 1) + " " + ROOM_Z);
    }

    /** A sealed room with a powered, sealed vent — the same rig the zone-air tests run on. */
    private void buildRoomWithVent() throws Exception {
        exec("artest fill 0 " + (ROOM_X - 2) + " " + (ROOM_Y - 1) + " " + (ROOM_Z - 2)
                + " " + (ROOM_X + 2) + " " + ROOM_Y + " " + (ROOM_Z + 2) + " minecraft:stone");
        for (int yy = ROOM_Y + 1; yy <= ROOM_Y + 2; yy++) {
            exec("artest fill 0 " + (ROOM_X - 2) + " " + yy + " " + (ROOM_Z - 2)
                    + " " + (ROOM_X + 2) + " " + yy + " " + (ROOM_Z + 2) + " minecraft:stone");
            exec("artest fill 0 " + (ROOM_X - 1) + " " + yy + " " + (ROOM_Z - 1)
                    + " " + (ROOM_X + 1) + " " + yy + " " + (ROOM_Z + 1) + " minecraft:air");
        }
        exec("artest fill 0 " + (ROOM_X - 2) + " " + (ROOM_Y + 3) + " " + (ROOM_Z - 2)
                + " " + (ROOM_X + 2) + " " + (ROOM_Y + 3) + " " + (ROOM_Z + 2) + " minecraft:stone");

        String vent = exec("artest place 0 " + ROOM_X + " " + ROOM_Y + " " + ROOM_Z
                + " advancedrocketry:oxygenVent");
        assertTrue("vent place failed: " + vent, vent.contains("\"placed\":true"));
        exec("artest energy inject 0 " + ROOM_X + " " + ROOM_Y + " " + ROOM_Z + " 1000000");
        exec("artest fluid inject 0 " + ROOM_X + " " + ROOM_Y + " " + ROOM_Z + " oxygen 16000");
        exec("artest tile force-tick 0 " + ROOM_X + " " + ROOM_Y + " " + ROOM_Z + " 1");
        exec("artest vent reseal 0 " + ROOM_X + " " + ROOM_Y + " " + ROOM_Z);
        exec("artest tile force-tick 0 " + ROOM_X + " " + ROOM_Y + " " + ROOM_Z + " 5");
    }

    private static long longOf(String json, Pattern pattern) {
        Matcher m = pattern.matcher(json);
        assertTrue("no " + pattern.pattern() + " in: " + json, m.find());
        return Long.parseLong(m.group(1));
    }

    private static String stringOf(String json, Pattern pattern) {
        Matcher m = pattern.matcher(json);
        assertTrue("no " + pattern.pattern() + " in: " + json, m.find());
        return m.group(1);
    }
}
