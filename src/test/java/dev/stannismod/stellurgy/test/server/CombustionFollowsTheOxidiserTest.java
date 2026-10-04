package dev.stannismod.stellurgy.test.server;

import dev.stannismod.stellurgy.test.FixtureSite;
import dev.stannismod.stellurgy.test.Reply;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static dev.stannismod.stellurgy.test.StellurgyTestConstants.ppm;

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

    /** Where the room's vent stands, from this scenario's own site (see {@link #buildRoomWithVent}). */
    private int roomX;
    private int roomY;
    private int roomZ;

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
     *
     * <p>red-witnessed: one inversion per verdict, 2026-09-30. THIN REFUSES — {@code AtmosphereHandler#allowsCombustionAt} at {@code return air.allowsCombustion();} answering the label's flag instead of the air's: "nothing may light in
     * air this thin … \"combustible\":true". LABEL — the {@code Atmosphere#LOWOXYGEN} at {@code new Atmosphere(true, false, true, "lowO2")} constant building {@code lowO2}
     * non-combustible: "the label's own flag is unchanged, and it is WRONG … \"labelCombustible\":false".
     * ORDINARY BURNS — {@code AirState#allowsCombustion} at {@code return needed > 0 && roleTotal(GasRole.OXIDISER) >= needed;} refusing combustion everywhere: "ordinary air burns: …
     * \"combustible\":false". BREATHABLE — {@code AirState#isBreathableAir} at {@code || roleTotal(GasRole.OXIDISER) >= config.lifeSupportMinPartialO2;} refusing breathability everywhere:
     * "and is breathable: … \"breathableAir\":false". The premises (the composition arrived, it is
     * not breathable, the label is {@code lowO2}, the room was refilled) are arrangements and are
     * not witnessed.</p>
     */
    @Test
    public void aRoomTooThinToBurnRefusesFireWhileItsLabelStillSaysOtherwise() throws Exception {
        buildRoomWithVent();

        setAir(AirMix.THIN);
        Reply thin = atmosphere();
        // The premise is that the room is thin, not that it holds one exact figure: a maintained
        // room's vent begins restoring oxygen the moment the composition lands, so the number moves
        // between the write and the read. What matters to this test is that it stays far below the
        // breathing threshold, which is what the rest of the scenario rests on.
        long thinOxygen = oxygenOf(thin);
        assertTrue("premise: the composition must have arrived, and thin: " + thin,
                thinOxygen >= ppm(THIN_OXYGEN) && thinOxygen < ppm(THIN_OXYGEN) + ppm(1_000));
        assertFalse("premise: air this thin is not breathable: " + thin, thin.bool("breathableAir"));
        assertFalse("nothing may light in air this thin - and this is the defect the slice closes,"
                + " because the LABEL below still says it can: " + thin, thin.bool("combustible"));
        assertTrue("the label's own flag is unchanged, and it is WRONG - it was assigned from the"
                + " breathing band. It stays visible until the types are deleted: " + thin,
                thin.bool("labelCombustible"));
        assertEquals("premise: the label the game shows is still the thin-air one: " + thin,
                "lowO2", thin.text("type"));

        setAir(AirMix.NORMAL);
        Reply normal = atmosphere();
        assertEquals("premise: the room was refilled: " + normal,
                ppm(NORMAL_OXYGEN), oxygenOf(normal));
        assertTrue("ordinary air burns: " + normal, normal.bool("combustible"));
        assertTrue("and is breathable: " + normal, normal.bool("breathableAir"));
    }

    // ─── the rig ───────────────────────────────────────────────────────

    private enum AirMix { THIN, NORMAL }

    private void setAir(AirMix mix) throws Exception {
        int oxygen = mix == AirMix.THIN ? THIN_OXYGEN : NORMAL_OXYGEN;
        arrange("stellurgytest vent setair 0 " + roomX + " " + roomY + " " + roomZ
                + " " + ppm(1_000_000 - oxygen) + " " + ppm(oxygen) + " 0");
    }

    /** What a person standing in the room breathes, and what the air itself says about burning. */
    private Reply atmosphere() throws Exception {
        return ask("stellurgytest atmosphere get 0 " + roomX + " " + (roomY + 1) + " " + roomZ);
    }

    /** The oxygen in the room's composition — a member of {@code gases}, keyed by gas name. */
    private static long oxygenOf(Reply atmosphere) {
        return Reply.of("the `gases` of " + atmosphere, atmosphere.object("gases")).longInteger("oxygen");
    }

    /** A sealed room with a powered, sealed vent — the same rig the zone-air tests run on. */
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
}
