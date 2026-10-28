package dev.stannismod.stellurgy.test.unit;

import dev.stannismod.stellurgy.space.GalacticCoord;
import dev.stannismod.stellurgy.space.ShipEntryController;
import dev.stannismod.stellurgy.space.SpaceSubsystem;
import dev.stannismod.stellurgy.test.contract.Pins;
import dev.stannismod.stellurgy.test.contract.RedWitness;
import dev.stannismod.stellurgy.universe.BodyEphemeris;
import dev.stannismod.stellurgy.universe.SystemBody;
import dev.stannismod.stellurgy.universe.SystemBodyKind;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Where a jump ends: {@link SpaceSubsystem#arrivalAround}, the decision on the bodies of the target's cell.
 */
public class ArrivalAroundTest {

    /** A constant: {@code GalacticCoord} is an immutable value: every field final, nothing mutable reachable. */
    private static final GalacticCoord NAME = GalacticCoord.ofSectorLocal(19, 0, 0, 0, 0, 0);

    /**
     * A planet named at {@link #NAME}, one Earth radius across. A constant: {@code SystemBody} is an immutable
     * value — every field final, holding immutable values (a coordinate, a frame, an ephemeris, numbers).
     */
    private static final SystemBody PLANET =
            SystemBody.fixedAt(NAME, SystemBodyKind.PLANET, 0, 0).withRadius(1d);

    /**
     * A wider body sharing the planet's cell, standing 5000 blocks off its centre — listed FIRST, so a
     * decision that takes the first or the widest body of the cell is the one that picks it. A constant:
     * {@code SystemBody} is an immutable value, as above.
     */
    private static final SystemBody WIDER_NEIGHBOUR = new SystemBody(NAME, PLANET.frame(),
            BodyEphemeris.fixed(5000L, 0L, 0L), SystemBodyKind.GAS_GIANT, 7, 0).withRadius(11d);

    /**
     * This test fails if {@code SpaceSubsystem#arrivalAround} stops deciding that a jump aimed at a body
     * arrives on THAT body's ring — the body the target names — and not on the ring of another body of the
     * cell, wider or first.
     */
    @Pins("INV-SPC-15")
    @RedWitness(member = "SpaceSubsystem#arrivalAround", at = "body.addressAt(worldTick).equals(target)",
            replacedBy = "true", fails = "a jump aimed at a body arrives on that body's ring", on = "2026-10-28")
    @Test
    public void aJumpAimedAtABodyArrivesOnThatBodysRing() {
        List<SystemBody> cell = Arrays.asList(WIDER_NEIGHBOUR, PLANET);
        long ring = ShipEntryController.entryRingAround(PLANET);
        assertTrue("arrangement: the neighbour's ring must be wider, or choosing it could not be seen",
                ShipEntryController.entryRingAround(WIDER_NEIGHBOUR) > ring);

        GalacticCoord arrived = SpaceSubsystem.arrivalAround(cell, NAME, 0L, 0x5A);
        // StandoffRing rounds each component down, never out: at most the ring away, under a block short per axis.
        assertEquals("a jump aimed at a body arrives on that body's ring", ring,
                arrived.staticFrameDistanceTo(NAME), 2.0);
    }

    /**
     * This test fails if {@code SpaceSubsystem#arrivalAround} stops deciding that a target which names no
     * body — a point the pilot chose — is arrived at exactly, even in a cell that holds bodies.
     */
    @Pins("INV-SPC-15")
    @RedWitness(member = "SpaceSubsystem#arrivalAround", at = "return target;",
            replacedBy = "return StandoffRing.pointAround(target, ShipEntryController.ENTRY_RING_BLOCKS, seed);",
            fails = "a target that names no body is not displaced", on = "2026-10-28")
    @Test
    public void aJumpAimedAtAPointThatIsNoBodyArrivesOnThePoint() {
        GalacticCoord point = NAME.plusLocalSaturating(123_456L, 0L, -7L);
        assertSame("a target that names no body is not displaced", point,
                SpaceSubsystem.arrivalAround(Arrays.asList(WIDER_NEIGHBOUR, PLANET), point, 0L, 0x5A));
    }
}
