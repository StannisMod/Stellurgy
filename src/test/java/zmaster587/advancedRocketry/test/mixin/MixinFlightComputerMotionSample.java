package zmaster587.advancedRocketry.test.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import zmaster587.advancedRocketry.command.test.MotionTrace;
import zmaster587.advancedRocketry.tile.TileAdvancedFlightComputer;

/**
 * One flight-recorder sample per SERVER TICK of a flight computer.
 *
 * <h2>Where it is taken, and why that is not a detail</h2>
 *
 * <p>The quantity this channel exists to measure is the INTERVAL between one tile's ticks — so a
 * tick the server never got round to is exactly the case that must still produce no sample, and a
 * tick that ran must always produce one. Production took it immediately after the HUD refresh and
 * before every gate below, because each of those gates can decline to run for reasons that have
 * nothing to do with the clock. The injection therefore targets the {@code refreshHudDrive} call
 * itself rather than the method head: the two early returns above it mean "this tile did not tick",
 * and a sample there would be a lie.</p>
 *
 * <p>The command and setpoint recorded are last tick's published values — the sample describes the
 * state the tick STARTS from, which is also the state the physics thread has been chasing since.</p>
 */
@Mixin(TileAdvancedFlightComputer.class)
public abstract class MixinFlightComputerMotionSample {

    @Shadow private double[] velocitySetpoint;

    @Inject(method = "update",
            at = @At(value = "INVOKE",
                    target = "Lzmaster587/advancedRocketry/tile/TileAdvancedFlightComputer;"
                            + "refreshHudDrive(J)V",
                    shift = At.Shift.AFTER))
    private void arTest$gameTickSample(CallbackInfo ci) {
        TileAdvancedFlightComputer self = (TileAdvancedFlightComputer) (Object) this;
        MotionTrace.game(
                MotionTrace.keyOf(self.getWorld().provider.getDimension(),
                        self.getPos().getX(), self.getPos().getY(), self.getPos().getZ()),
                self.pilotInput != null,
                arTest$magnitude(self.commandedVelocity),
                arTest$magnitude(velocitySetpoint));
    }

    /** Vector length, with the same null/short tolerance production's own helper has. Duplicated
     *  rather than shadowed because it is three lines of pure arithmetic and shadowing a private
     *  static would tie this file to a name production is free to change. */
    private static double arTest$magnitude(double[] v) {
        if (v == null || v.length < 3) {
            return 0.0;
        }
        return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }
}
