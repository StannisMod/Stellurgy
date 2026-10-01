package dev.stannismod.stellurgy.test.mixin;

import net.minecraft.entity.player.EntityPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import dev.stannismod.stellurgy.event.PlanetEventHandler;
import dev.stannismod.stellurgy.test.trace.TestTrace;

/** Events of {@link PlanetEventHandler}: the space-dimension guard's station relocation. */
@Mixin(PlanetEventHandler.class)
public abstract class MixinPlanetEventHandlerEvents {

    private static final String SPACE_GUARD_INSTRUMENT = "space_guard_events";

    /**
     * The space-dimension guard MOVED a body onto a station's spawn, as an event.
     *
     * <p>{@code space_guard_relocated} is the STATION branch of the space-dimension guard ({@code evictIfOffStation}): a body
     * in the space dimension standing in no station's slot, put on the spawn of the station
     * furthest from it. The record carries {@code who} and the pose the write left him at, read
     * back off the entity immediately AFTER the write rather than off the local the handler chose —
     * the two are the same value on this path, and reading the entity says where the body actually
     * is instead of where production intended to put it.</p>
     *
     * <p><b>Why this seam exists at all.</b> The guard's other branch transfers through a
     * {@code BasicTeleporter} and is therefore already recorded ({@code teleporter_placed}); this
     * one commits through {@code Entity.setPositionAndUpdate} and was recorded NOWHERE. The nearest
     * witness, {@code pos_jump}, fires on a position write only when the VERTICAL move exceeds its
     * threshold, and this guard moves a body fifty thousand blocks horizontally while leaving Y
     * roughly where it was — so the one act the scenario is about was invisible, and the test that
     * pins it sampled the body's own X in a loop instead, which is a value and not a link.</p>
     *
     * <p>Server only: the guard runs at the end of the space world's server tick, and the routed
     * {@link TestTrace#record} stamps the record with the world the body is in.</p>
     *
     * <p>It also carries WHERE THE BODY WAS ({@code fromX/fromY/fromZ}), read before the write. A
     * transfer into the space dimension lands a body outside every slot before any scenario can place
     * it, so the arrival is itself relocated, and a wait that asks only "was a body relocated" can be
     * closed by that. It has to say which body position it is about. Hence a {@code Redirect} rather
     * than an injection: it is the one form that sees the position on both sides of the same write.
     * It makes that write unchanged.</p>
     *
     * <p>SILENT about: WHICH station was chosen (the handler's local is not read here) and the
     * fallback branch, which has its own record. It does not fire when the guard declines — a body
     * inside a station's slot, or riding a rocket — and it cannot: there is no act to record.</p>
     */
    @Redirect(method = "evictIfOffStation",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/entity/player/EntityPlayer;setPositionAndUpdate(DDD)V"))
    private void stellurgyTest$spaceGuardRelocated(EntityPlayer moved, double x, double y, double z) {
        double fromX = moved.posX;
        double fromY = moved.posY;
        double fromZ = moved.posZ;
        moved.setPositionAndUpdate(x, y, z);
        TestTrace.instrumentHere(SPACE_GUARD_INSTRUMENT);
        TestTrace.record(moved, "space_guard_relocated",
                "\"who\":\"" + TestTrace.json(moved.getName()) + "\""
                        + ",\"fromX\":" + TestTrace.fmt(fromX)
                        + ",\"fromY\":" + TestTrace.fmt(fromY)
                        + ",\"fromZ\":" + TestTrace.fmt(fromZ)
                        + ",\"x\":" + TestTrace.fmt(moved.posX)
                        + ",\"y\":" + TestTrace.fmt(moved.posY)
                        + ",\"z\":" + TestTrace.fmt(moved.posZ));
    }
}
