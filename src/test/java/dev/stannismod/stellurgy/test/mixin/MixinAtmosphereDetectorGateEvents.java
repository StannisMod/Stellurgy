package dev.stannismod.stellurgy.test.mixin;

import dev.stannismod.stellurgy.test.trace.PeriodicGateState;
import dev.stannismod.stellurgy.test.trace.TestTrace;
import dev.stannismod.stellurgy.tile.atmosphere.TileAtmosphereDetector;
import net.minecraft.tileentity.TileEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The atmosphere detector's periodic gate OPENING, as the server event {@code periodic_gate_opened}
 * ({@link PeriodicGateState}, {@code tile} {@code atmosphere_detector}). Read by
 * {@code AtmosphereDetectorWatchesAStatementTest#theDetectorSamplesOnTotalTimeWhereTheTimeOfDayStandsStill}.
 *
 * <p>The instrument is noted at the HEAD of every server {@code update}, opened or not, so a window with no
 * opening is told from a window nobody watched; the record is taken at the call the gate guards,
 * {@code statementHolds()}, so it means the gate let a sample through on its own tick. The probe's forced sample
 * calls {@code statementHolds} directly and is not seen here.</p>
 *
 * <p>SILENT about what the sample found.</p>
 */
@Mixin(TileAtmosphereDetector.class)
public abstract class MixinAtmosphereDetectorGateEvents {

    private static final String INSTRUMENT = "periodic_gate_events";

    @Inject(method = "update", remap = false, at = @At("HEAD"))
    private void stellurgyTest$detectorUpdated(CallbackInfo ci) {
        TileEntity self = (TileEntity) (Object) this;
        if (self.getWorld() != null && !self.getWorld().isRemote) {
            TestTrace.instrument(self.getWorld(), INSTRUMENT);
        }
    }

    @Inject(method = "update", remap = false, at = @At(value = "INVOKE", remap = false,
            target = "Ldev/stannismod/stellurgy/tile/atmosphere/TileAtmosphereDetector;statementHolds()Z"))
    private void stellurgyTest$detectorGateOpened(CallbackInfo ci) {
        TileEntity self = (TileEntity) (Object) this;
        TestTrace.record(self.getWorld(), "periodic_gate_opened", PeriodicGateState.fields("atmosphere_detector", self));
    }
}
