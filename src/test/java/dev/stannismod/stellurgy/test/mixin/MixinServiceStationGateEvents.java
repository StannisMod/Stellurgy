package dev.stannismod.stellurgy.test.mixin;

import dev.stannismod.stellurgy.test.trace.PeriodicGateState;
import dev.stannismod.stellurgy.test.trace.TestTrace;
import dev.stannismod.stellurgy.tile.infrastructure.TileRocketServiceStation;
import net.minecraft.tileentity.TileEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The rocket service station's periodic gate OPENING, as the server event {@code periodic_gate_opened}
 * ({@link PeriodicGateState}, {@code tile} {@code rocket_service_station}). Read by
 * {@code RocketServiceStationLinkAndStateTest#theStationsGateOpensOnTotalTimeWhereTheTimeOfDayStandsStill}.
 *
 * <p>The instrument is noted at the HEAD of every server {@code canPerformFunction}, opened or not; the record is
 * taken at the first thing it asks once the clock gate let the call through — how many parts wait for repair —
 * so it means the gate opened. Whether the station then had work is not read here.</p>
 *
 * <p>SILENT about what the station did with an open gate.</p>
 */
@Mixin(TileRocketServiceStation.class)
public abstract class MixinServiceStationGateEvents {

    private static final String INSTRUMENT = "periodic_gate_events";

    @Inject(method = "canPerformFunction", remap = false, at = @At("HEAD"))
    private void stellurgyTest$stationAsked(CallbackInfoReturnable<Boolean> cir) {
        TileEntity self = (TileEntity) (Object) this;
        if (self.getWorld() != null && !self.getWorld().isRemote) {
            TestTrace.instrument(self.getWorld(), INSTRUMENT);
        }
    }

    @Inject(method = "canPerformFunction", remap = false,
            at = @At(value = "INVOKE", target = "Ljava/util/List;size()I", ordinal = 0, remap = false))
    private void stellurgyTest$stationGateOpened(CallbackInfoReturnable<Boolean> cir) {
        TileEntity self = (TileEntity) (Object) this;
        TestTrace.record(self.getWorld(), "periodic_gate_opened", PeriodicGateState.fields("rocket_service_station", self));
    }
}
