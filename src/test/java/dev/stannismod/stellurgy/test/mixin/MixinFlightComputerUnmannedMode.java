package dev.stannismod.stellurgy.test.mixin;

import net.minecraft.util.math.BlockPos;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.stannismod.stellurgy.test.trace.TestTrace;
import dev.stannismod.stellurgy.tile.TileAdvancedFlightComputer;

/**
 * Which way an UNMANNED flight computer decided — {@code unmanned_mode}, {@code mode}
 * {@code released} or {@code held}. Read by {@code TierTwoCraftFlightModelGroupTest}'s cell scenario.
 *
 * <p>Taken in {@code TileAdvancedFlightComputer#update}, in the stretch that runs only when nobody is
 * at the helm: between that branch's call to {@code VSIntegration.ensureShipPhysicsEnabled} and the
 * piloted branch's own. Inside it the first write of {@code flightCommand} is the Flight-Assist-off
 * release (a command with no velocity) and the second is the Flight-Assist-on hold (the retained
 * setting executed). So a record names the branch production TOOK, not the flag a probe set: a craft
 * whose release branch is broken is recorded as {@code held} however its flag reads.</p>
 *
 * <p>One record per CHANGE of mode on a given computer, not one per tick — the branch runs twenty times
 * a second and a scenario asks only "has it been released since my mark". The computer's address
 * ({@code afcX/Y/Z}, a subspace one aboard an assembled ship) and {@code dim} name which.</p>
 *
 * <p>What it is silent about: the piloted branch, and a computer that is not on a ship (it returns
 * before the unmanned stretch).</p>
 */
@Mixin(TileAdvancedFlightComputer.class)
public abstract class MixinFlightComputerUnmannedMode {

    private static final String INSTRUMENT = "flight_computer_unmanned_mode";

    private static final String ENSURE_PHYSICS = "Ldev/stannismod/stellurgy/integration/vs/VSIntegration;"
            + "ensureShipPhysicsEnabled(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;)V";

    private static final String FLIGHT_COMMAND = "Ldev/stannismod/stellurgy/tile/TileAdvancedFlightComputer;"
            + "flightCommand:Ldev/stannismod/stellurgy/ship/control/FlightCommand;";

    /** The mode last recorded for this computer, so only a change is written. Test source set only. */
    @Unique
    private String stellurgyTest$lastMode;

    @Inject(method = "update", require = 1,
            slice = @Slice(
                    from = @At(value = "INVOKE", target = ENSURE_PHYSICS, ordinal = 0, remap = false),
                    to = @At(value = "INVOKE", target = ENSURE_PHYSICS, ordinal = 1, remap = false)),
            at = @At(value = "FIELD", target = FLIGHT_COMMAND, opcode = org.objectweb.asm.Opcodes.PUTFIELD,
                    ordinal = 0, shift = At.Shift.AFTER, remap = false))
    private void stellurgyTest$released(CallbackInfo ci) {
        stellurgyTest$note("released");
    }

    @Inject(method = "update", require = 1,
            slice = @Slice(
                    from = @At(value = "INVOKE", target = ENSURE_PHYSICS, ordinal = 0, remap = false),
                    to = @At(value = "INVOKE", target = ENSURE_PHYSICS, ordinal = 1, remap = false)),
            at = @At(value = "FIELD", target = FLIGHT_COMMAND, opcode = org.objectweb.asm.Opcodes.PUTFIELD,
                    ordinal = 1, shift = At.Shift.AFTER, remap = false))
    private void stellurgyTest$held(CallbackInfo ci) {
        stellurgyTest$note("held");
    }

    @Unique
    private void stellurgyTest$note(String mode) {
        TileAdvancedFlightComputer self = (TileAdvancedFlightComputer) (Object) this;
        if (self.getWorld() == null || self.getWorld().isRemote) {
            return;
        }
        TestTrace.instrument(self.getWorld(), INSTRUMENT);
        if (mode.equals(stellurgyTest$lastMode)) {
            return;
        }
        stellurgyTest$lastMode = mode;
        BlockPos p = self.getPos();
        TestTrace.record(self.getWorld(), "unmanned_mode", "\"mode\":\"" + mode + "\""
                + ",\"dim\":" + self.getWorld().provider.getDimension()
                + ",\"afcX\":" + p.getX() + ",\"afcY\":" + p.getY() + ",\"afcZ\":" + p.getZ());
    }
}
