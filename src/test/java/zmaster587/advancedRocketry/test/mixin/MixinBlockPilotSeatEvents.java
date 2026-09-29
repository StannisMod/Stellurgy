package zmaster587.advancedRocketry.test.mixin;

import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import zmaster587.advancedRocketry.block.BlockPilotSeat;
import zmaster587.advancedRocketry.test.trace.TestTrace;
import zmaster587.advancedRocketry.tile.TilePilotSeat;

/**
 * A pilot SAT DOWN, and the seat decided whether his controls would be live: {@code
 * pilot_seat_sit_decided}.
 *
 * <h2>What the fact is</h2>
 *
 * <p>A tier-2 seat takes the pilot whether or not his craft is a ship — the craft is deaf, not a
 * wall — so the sit itself says nothing about whether anything will answer his keys. What decides
 * that is whether a physics ship MANAGES this seat, and production asks exactly that question one
 * statement after it has mounted him. The answer used to leave the game only as a notice ("this
 * craft is not assembled"), which is a rendering: the game fact is that the seat took a pilot whose
 * orders will reach nobody.</p>
 *
 * <h2>Why a redirect, and why on THIS call</h2>
 *
 * <p>The verdict is an inline local in {@code BlockPilotSeat.onBlockActivated} —
 * {@code te instanceof TilePilotSeat && isLinked() && isManagedByShip(world)} — and there is no
 * injection point between its assignment and the branch that reads it, so a local capture would
 * have to be taken inside the not-assembled branch and could never record the assembled case.
 * Recomputing the conjunction in the mixin is the one thing forbidden outright: a copied
 * observation keeps evaluating the old, correct expression on the day production breaks, and the
 * scenario then passes green over the bug.</p>
 *
 * <p>So the seam is the last call of the conjunction. Redirecting it hands over production's own
 * return value, at production's own instant, and the handler performs the original call — nothing
 * is computed twice and nothing is re-derived. It also carries the two terms ahead of it for free:
 * {@code &&} short-circuits, so reaching this call at all IS "the block has a pilot-seat tile and
 * that tile is linked". The record does not state those as fields — a literal in a payload is a
 * constant wearing a measurement's clothes — it states the one thing that was actually decided
 * here, and this javadoc says what its presence already implies.</p>
 *
 * <h2>Silent about</h2>
 *
 * <p><b>A sit on an unlinked seat, or on a block with no tile</b>: the conjunction short-circuits
 * before this call and NO record is taken. An absence of this type therefore means "he sat on
 * something that is not a linked pilot seat" OR "nobody sat down at all", and the two are told
 * apart by the {@code mount} record beside it, never by this silence alone.</p>
 *
 * <p><b>WHO sat down</b>: the player is an argument of the enclosing method, not of the redirected
 * call, and appending the enclosing signature to the handler is a descriptor that must be matched
 * exactly at apply time for a name the record does not need — the {@code mount} link immediately
 * before it on the chain carries the rider. <b>The client's own opinion</b>: production runs this
 * whole block only on the server ({@code if (!world.isRemote)}), so the record is server-side by
 * construction; the client's parallel verdict is {@code ship_pilot_gate_decided}, a different seam
 * asked every tick. And <b>the refusals above it</b> — an occupied seat returns before the mount,
 * so no sit is decided at all there.</p>
 */
@Mixin(BlockPilotSeat.class)
public abstract class MixinBlockPilotSeatEvents {

    private static final String INSTRUMENT = "pilot_seat_sit_events";

    @Redirect(method = "onBlockActivated",
            at = @At(value = "INVOKE",
                    target = "Lzmaster587/advancedRocketry/tile/TilePilotSeat;"
                            + "isManagedByShip(Lnet/minecraft/world/World;)Z"))
    private boolean arTest$sitDecided(TilePilotSeat seat, World seatWorld) {
        // The original call, first and once: this hook observes production's answer and must not
        // become a second asking of the question.
        boolean managed = seat.isManagedByShip(seatWorld);
        TestTrace.instrument(seatWorld, INSTRUMENT);
        TestTrace.record(seatWorld, "pilot_seat_sit_decided",
                "\"pos\":\"" + arTest$xyz(seat) + "\""
                        + ",\"managed\":" + managed
                        + ",\"dim\":" + (seatWorld.provider == null
                                ? Integer.MIN_VALUE : seatWorld.provider.getDimension()));
        return managed;
    }

    private static String arTest$xyz(TilePilotSeat seat) {
        return seat.getPos() == null ? "null"
                : seat.getPos().getX() + "," + seat.getPos().getY() + "," + seat.getPos().getZ();
    }
}
