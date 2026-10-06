package dev.stannismod.stellurgy.test.mixin;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import dev.stannismod.stellurgy.test.trace.TestTrace;
import dev.stannismod.stellurgy.tile.sensor.TileFireControlSensor;

/**
 * A fire-control sensor judging whether a press may act, as an event: {@code sensor_press_judged}.
 *
 * <p>Wraps the {@code canInteractWithContainer} call in the SERVER branch of {@code useNetworkData}
 * and records production's own answer, passed through untouched: {@code pos}, {@code player} and
 * {@code reachable}. Server log. Read by
 * {@code MachineGuiClientGroupTest#aFireControlSensorPressFromBeyondReachChangesNothing}.</p>
 *
 * <p>SILENT about what the press then did — the sensor's mode, read by the test through the sensor.</p>
 */
@Mixin(TileFireControlSensor.class)
public abstract class MixinTileFireControlSensorPressEvents {

    private static final String INSTRUMENT = "sensor_press_events";

    @Redirect(method = "useNetworkData", at = @At(value = "INVOKE",
            target = "Ldev/stannismod/stellurgy/tile/sensor/TileFireControlSensor;canInteractWithContainer(Lnet/minecraft/entity/player/EntityPlayer;)Z"),
            require = 1)
    private boolean stellurgyTest$judged(TileFireControlSensor sensor, EntityPlayer player) {
        boolean reachable = sensor.canInteractWithContainer(player);
        if (sensor.getWorld() != null && !sensor.getWorld().isRemote) {
            BlockPos pos = sensor.getPos();
            TestTrace.instrument(sensor.getWorld(), INSTRUMENT);
            TestTrace.record(sensor.getWorld(), "sensor_press_judged",
                    "\"pos\":\"" + pos.getX() + "," + pos.getY() + "," + pos.getZ() + "\""
                            + ",\"player\":\"" + (player == null ? "" : player.getName()) + "\""
                            + ",\"reachable\":" + reachable);
        }
        return reachable;
    }
}
