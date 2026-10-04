package dev.stannismod.stellurgy.mixin;

import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import dev.stannismod.stellurgy.integration.vs.ShipFrameBody;
import dev.stannismod.stellurgy.integration.vs.ShipFrameTravel;

/**
 * Gives every entity its own ship-frame capture slot. A field of the object, so the client's and the
 * server's copies of one player in a single JVM — equal by network id — hold two slots, not one.
 */
@Mixin(Entity.class)
public abstract class MixinEntityShipFrameCapture implements ShipFrameBody {

    @Unique
    private ShipFrameTravel.ShipFrameState stellurgy$shipFrame;

    @Unique
    private long stellurgy$captureEpoch;

    @Override
    public ShipFrameTravel.ShipFrameState stellurgy$shipFrame() {
        return stellurgy$shipFrame;
    }

    @Override
    public void stellurgy$setShipFrame(ShipFrameTravel.ShipFrameState state) {
        stellurgy$shipFrame = state;
    }

    @Override
    public long stellurgy$captureEpoch() {
        return stellurgy$captureEpoch;
    }

    @Override
    public void stellurgy$setCaptureEpoch(long epoch) {
        stellurgy$captureEpoch = epoch;
    }
}
