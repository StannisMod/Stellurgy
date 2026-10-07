package dev.stannismod.stellurgy.test.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.stannismod.stellurgy.tile.atmosphere.TileOxygenVent;
import dev.stannismod.stellurgy.test.trace.TestTrace;

/**
 * Each scrub an oxygen vent runs, recorded as it ends: how many of its scrubbers absorbed, what the vent's
 * store held while they did, and the price the vent will now ask of it. Read by
 * {@code LifeSupportZoneTest#aVentScrubsOnlyWhatItsStoreCanPayFor}.
 *
 * <p>{@code vent_scrubbed} is written at the RETURN of {@code TileOxygenVent#scrub}, and only on the call
 * that ran a scrub (its interval counter is back at zero). The store is read before the tick's payment,
 * which {@code TileOxygenVent#update} takes after the function returns — so {@code stored} is what the
 * scrub had to be paid out of.</p>
 *
 * <p>Fields: {@code pos} as {@code x,y,z}; {@code working}; {@code stored}; {@code price}. SILENT on a tick
 * that is not a scrub, and on a vent whose room is not sealed (it never reaches {@code scrub}).</p>
 */
@Mixin(value = TileOxygenVent.class, remap = false)
public abstract class MixinTileOxygenVentScrubEvents {

    private static final String INSTRUMENT = "vent_scrub_events";

    @Shadow private int workingScrubbers;
    @Shadow private int ticksSinceScrub;

    @Inject(method = "scrub", at = @At("RETURN"), require = 1)
    private void stellurgyTest$scrubbed(CallbackInfo ci) {
        TileOxygenVent self = (TileOxygenVent) (Object) this;
        if (self.getWorld() == null || self.getWorld().isRemote) {
            return;
        }
        TestTrace.instrument(self.getWorld(), INSTRUMENT);
        if (ticksSinceScrub != 0) {
            return;
        }
        TestTrace.record(self.getWorld(), "vent_scrubbed", "\"pos\":\"" + self.getPos().getX() + ","
                + self.getPos().getY() + "," + self.getPos().getZ() + "\",\"working\":" + workingScrubbers
                + ",\"stored\":" + self.getUniversalEnergyStored() + ",\"price\":" + self.getPowerPerOperation());
    }
}
