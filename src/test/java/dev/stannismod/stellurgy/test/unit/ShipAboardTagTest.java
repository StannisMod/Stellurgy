package dev.stannismod.stellurgy.test.unit;

import dev.stannismod.stellurgy.space.GalacticCoord;
import dev.stannismod.stellurgy.space.ShipAboardTag;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.*;

/**
 * Contract tests for the durable "which ship is this player aboard" tag.
 *
 * Pins the persistence contract the login restore path depends on: a stamped record round-trips
 * across a save/load of the player's ForgeData compound; anything absent or malformed answers
 * {@code null} instead of throwing (this code runs inside the login path, where a throw is a failed
 * login and a half-read record is a player teleported somewhere he never was); a ship at the galactic
 * origin is still distinguishable from "no record"; and the shared ForgeData compound is never
 * polluted beyond the one namespaced key. Does not pin the tag's internal field names.
 */
public class ShipAboardTagTest {

    /** A constant: a {@code UUID} is an immutable value: two final longs. */
    private static final UUID SHIP = UUID.fromString("f7a1c3d2-0000-4000-8000-00000000beef");
    /**
     * A constant: {@code GalacticCoord} is an immutable value: every field final, nothing mutable reachable.
     */
    private static final GalacticCoord COORD =
            GalacticCoord.ofSectorLocal(12L, -3L, 7L, 640L, -128L, 4096L);

    private static ShipAboardTag.Aboard sample() {
        return new ShipAboardTag.Aboard(SHIP, COORD, 0, 2, -3);
    }

    @Test
    public void aRecordWithNoPostureReadsAsSeated() {
        // The seated shape is what a seated record writes, and the restore must not start treating
        // one as a standing crew member parked at the flight computer - that would put a returning
        // pilot inside the machine instead of in his chair.
        NBTTagCompound forgeData = new NBTTagCompound();
        ShipAboardTag.write(forgeData, sample());

        ShipAboardTag.Aboard restored = ShipAboardTag.read(forgeData);
        assertNotNull(restored);
        assertEquals(ShipAboardTag.Posture.SEATED, restored.posture);
    }

    @Test
    public void seatedAndStandingAreDifferentRecordsEvenAtTheSamePoint() {
        // The two postures are restored differently - one re-seats, one puts a body on the deck - so
        // a writer that only refreshes on CHANGE must be able to see the difference between them.
        ShipAboardTag.Aboard seated = new ShipAboardTag.Aboard(SHIP, COORD, 1, 2, 3);
        ShipAboardTag.Aboard standing = ShipAboardTag.Aboard.standing(SHIP, COORD, 1.0D, 2.0D, 3.0D);
        assertFalse("posture is part of the record's identity", seated.equals(standing));
    }

    @Test
    public void shipAtGalacticOriginIsNotMistakenForNoRecord() {
        // The shared coordinate decoder answers ORIGIN for an absent coordinate, so "absent" and
        // "at the origin" must be told apart here or a crewless player would be restored aboard.
        NBTTagCompound forgeData = new NBTTagCompound();
        ShipAboardTag.write(forgeData,
                new ShipAboardTag.Aboard(SHIP, GalacticCoord.ORIGIN, 0, 1, 0));

        ShipAboardTag.Aboard restored = ShipAboardTag.read(forgeData);
        assertNotNull(restored);
        assertEquals(GalacticCoord.ORIGIN, restored.coord);
        assertNull("a compound with no record at all still reads null",
                ShipAboardTag.read(new NBTTagCompound()));
    }

    @Test
    public void absentRecordReadsAsNull() {
        assertNull(ShipAboardTag.read(new NBTTagCompound()));

        NBTTagCompound foreign = new NBTTagCompound();
        foreign.setString("someOtherModsKey", "value");
        assertNull(ShipAboardTag.read(foreign));
    }

    @Test
    public void malformedRecordReadsAsNullRatherThanThrowing() {
        // Wrong shape entirely: the key holds a string, not a compound.
        NBTTagCompound wrongType = new NBTTagCompound();
        wrongType.setString(ShipAboardTag.KEY, "junk");
        assertNull(ShipAboardTag.read(wrongType));

        // A compound with no ship id.
        NBTTagCompound noShip = new NBTTagCompound();
        NBTTagCompound sub = new NBTTagCompound();
        COORD.writeToNBT(sub);
        noShip.setTag(ShipAboardTag.KEY, sub);
        assertNull(ShipAboardTag.read(noShip));

        // A ship id that is not a UUID.
        NBTTagCompound badId = new NBTTagCompound();
        NBTTagCompound badSub = new NBTTagCompound();
        badSub.setString("shipId", "not-a-uuid");
        COORD.writeToNBT(badSub);
        badId.setTag(ShipAboardTag.KEY, badSub);
        assertNull(ShipAboardTag.read(badId));

    }

    @Test
    public void aShipInNoCellStillProducesAnAboardRecord() {
        // A ship parked on a planet is in no cell at all, and a crew member aboard it still has to
        // be put back on its deck after a relog - so the coordinate is optional and its absence is
        // not corruption. What it must NOT do is answer the question "which world does he belong
        // in": a record with no cell says nothing about that, and hasPresence() is how a reader
        // asks. (A ship at the galactic ORIGIN is a different thing entirely and stays present.)
        ShipAboardTag.Aboard cellless = ShipAboardTag.Aboard.standing(SHIP, null, 1.5D, 0.0D, -2.5D);
        assertFalse("no coordinate means no presence", cellless.hasPresence());

        NBTTagCompound forgeData = new NBTTagCompound();
        ShipAboardTag.write(forgeData, cellless);
        ShipAboardTag.Aboard restored = ShipAboardTag.read(forgeData);
        assertNotNull("a ship-relative-only record must survive the round-trip", restored);
        assertNull("the coordinate must come back absent, not as some default cell", restored.coord);
        assertFalse(restored.hasPresence());

        assertTrue("a record that names a cell HAS presence", sample().hasPresence());
    }

    @Test
    public void aCrewMemberCarriedThroughAJumpIsStillEvidenceOfBeingOutInSpace() {
        // The case with NEITHER of the usual pieces of evidence. A jumping ship is in no cell, so
        // the record carries no coordinate; and the world it is parked in has its dimension id
        // re-minted by a free-id scan on every boot, so the id he was saved under stops naming
        // anything at exactly the event this has to survive - a restart. Left to those two, a pilot
        // who quit mid-jump comes back at spawn while his ship waits in hyperspace.
        ShipAboardTag.Aboard seatedMidJump =
                new ShipAboardTag.Aboard(SHIP, null, 0, 2, -3).inTransit();
        assertFalse("hyperspace is no cell, so there is still no presence",
                seatedMidJump.hasPresence());
        assertTrue("but the record must still say he was out in space",
                seatedMidJump.saysSpaceborne());

        NBTTagCompound forgeData = new NBTTagCompound();
        ShipAboardTag.write(forgeData, seatedMidJump);
        ShipAboardTag.Aboard restored = ShipAboardTag.read(forgeData);
        assertNotNull("a mid-jump record must survive the round-trip", restored);
        assertTrue(restored.saysSpaceborne());

        // The control, and it is the half that must NOT change: a ship parked on a planet is in no
        // cell either, and it opens nothing. Without this the flag would read as "any record with
        // no coordinate", which is the bug it is meant to fix, inverted.
        ShipAboardTag.Aboard planetSide = ShipAboardTag.Aboard.standing(SHIP, null, 1.5D, 0D, -2.5D);
        assertFalse("a ship parked on a planet is not a ship in a jump", planetSide.inTransit);
        assertFalse("and it must not open the space restore", planetSide.saysSpaceborne());
        assertTrue("while a ship in a cell does, as it always did", sample().saysSpaceborne());
    }

    @Test
    public void clearRemovesTheRecordAndIsIdempotent() {
        NBTTagCompound forgeData = new NBTTagCompound();
        ShipAboardTag.write(forgeData, sample());
        assertNotNull(ShipAboardTag.read(forgeData));

        ShipAboardTag.clear(forgeData);
        assertNull("a cleared player is no longer aboard", ShipAboardTag.read(forgeData));

        // Clearing again, and clearing a compound that never had a record, must both be no-ops.
        ShipAboardTag.clear(forgeData);
        ShipAboardTag.clear(new NBTTagCompound());
        assertNull(ShipAboardTag.read(forgeData));
    }

    @Test
    public void theSharedForgeDataCompoundIsNotPolluted() {
        // ForgeData is shared with every other mod on the pack: the record must live entirely under
        // one namespaced key and must not disturb anything else on write or on clear.
        NBTTagCompound forgeData = new NBTTagCompound();
        forgeData.setString("someOtherModsKey", "value");
        forgeData.setInteger("anotherModsCounter", 42);

        ShipAboardTag.write(forgeData, sample());
        assertTrue(forgeData.hasKey(ShipAboardTag.KEY));
        assertEquals(3, forgeData.getKeySet().size());
        assertEquals("value", forgeData.getString("someOtherModsKey"));
        assertEquals(42, forgeData.getInteger("anotherModsCounter"));

        ShipAboardTag.clear(forgeData);
        assertFalse(forgeData.hasKey(ShipAboardTag.KEY));
        assertEquals("value", forgeData.getString("someOtherModsKey"));
        assertEquals(42, forgeData.getInteger("anotherModsCounter"));
    }

    @Test
    public void nullishInputsAreToleratedRatherThanThrowing() {
        assertNull(ShipAboardTag.read(null));
        ShipAboardTag.write(null, sample());          // no target compound: nothing to do
        ShipAboardTag.clear((NBTTagCompound) null);

        // A null record clears rather than leaving a half-formed tag a reader would have to reject.
        NBTTagCompound forgeData = new NBTTagCompound();
        ShipAboardTag.write(forgeData, sample());
        ShipAboardTag.write(forgeData, null);
        assertNull(ShipAboardTag.read(forgeData));

        // Likewise for a record whose SHIP went missing upstream - the one field the record cannot
        // be missing, since it is the whole binding. (A missing coordinate is not that: see
        // aShipInNoCellStillProducesAnAboardRecord.)
        ShipAboardTag.write(forgeData, new ShipAboardTag.Aboard(null, COORD, 0, 0, 0));
        assertNull(ShipAboardTag.read(forgeData));
    }
}
