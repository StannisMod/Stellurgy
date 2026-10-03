package dev.stannismod.stellurgy.test.mixin;

import java.util.List;

import net.minecraft.entity.Entity;
import net.minecraft.world.WorldServer;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.stannismod.stellurgy.api.StellurgyConfiguration;
import dev.stannismod.stellurgy.api.sensor.TargetTrack;
import dev.stannismod.stellurgy.test.trace.TestTrace;
import dev.stannismod.stellurgy.tile.sensor.TileFireControlSensor;

/**
 * A fire-control sensor's sweep, as an event: {@code sensor_swept}.
 *
 * <p>The RETURN of {@code sweep}, one record per sweep — every {@code fireControlSensorScanIntervalTicks}
 * ticks while the sensor is enabled — carrying the contact list the sweep just decided:
 * {@code contacts} (how many), {@code names} (each contact's entity name, comma-separated, {@code ?}
 * for one no longer in the world), {@code tracks} (each contact as an object: {@code entityId}, the
 * entity's id or -1 for one no longer in the world, and the {@code distance} and {@code quality} the
 * sweep decided for it — a reader asking about ONE body addresses it by id rather than by a name
 * two bodies of one kind share), and about the best one {@code quality} and {@code locked}
 * (production's own {@code isLocked} against the configured lock floor), plus the sensor's
 * {@code mode} as it actually worked this sweep and whether it is {@code emitting}. Per sweep and not
 * per change, because a reader judging "never acquired over N sweeps" needs the sweeps it was not
 * acquired in. Server log. Read by {@code SensorFriendIsNeverAcquiredE2ETest},
 * {@code FireControlSensorE2ETest} and {@code ASensorAboardAShipE2ETest}.</p>
 *
 * <p>And the refusal that stands in front of every sweep, as its own event: {@code sensor_gate_refused},
 * documented at its seam below.</p>
 *
 * <p>The instrument is declared at the HEAD of {@code update}, which runs every tick whether or not
 * the sensor is enabled, so "no sweep was recorded" can be told from "nobody was looking": a
 * disabled sensor still announces the instrument and writes no sweep.</p>
 */
@Mixin(TileFireControlSensor.class)
public abstract class MixinTileFireControlSensorEvents {

    private static final String INSTRUMENT = "sensor_sweep_events";

    @Shadow
    private List<TargetTrack> contacts;

    @Inject(method = "update", at = @At("HEAD"), require = 1)
    private void stellurgyTest$looking(CallbackInfo ci) {
        TileFireControlSensor self = (TileFireControlSensor) (Object) this;
        if (self.getWorld() != null && !self.getWorld().isRemote) {
            TestTrace.instrument(self.getWorld(), INSTRUMENT);
        }
    }

    /**
     * Whether this sensor's last pass through its switch gate was a refusal; false before its first.
     * On the tile, so two sensors cannot share it.
     */
    @Unique
    private boolean stellurgyTest$refusing = false;

    /**
     * {@code sensor_gate_refused}: the sensor's tick met its switch gate and was turned back — the
     * acquisition flag off — at the one assignment that gate makes (the contact list emptied), before
     * any sweep, payment or network registration. Carries the flag as production read it. EDGE-ONLY per sensor: written on the first refusing tick after a tick
     * that passed the gate (or after the sensor's first tick), and SILENT for every refusing tick after
     * that — a reader waits for the edge from a mark taken while the sensor was still running.
     */
    @Inject(method = "update", at = @At(value = "FIELD",
            target = "Ldev/stannismod/stellurgy/tile/sensor/TileFireControlSensor;contacts:Ljava/util/List;",
            opcode = Opcodes.PUTFIELD), require = 1)
    private void stellurgyTest$gateRefused(CallbackInfo ci) {
        TileFireControlSensor self = (TileFireControlSensor) (Object) this;
        if (self.getWorld() == null || self.getWorld().isRemote || stellurgyTest$refusing) {
            return;
        }
        stellurgyTest$refusing = true;
        StellurgyConfiguration config = StellurgyConfiguration.getCurrentConfig();
        TestTrace.record(self.getWorld(), "sensor_gate_refused", "\"pos\":\"" + self.getPos().getX() + ","
                + self.getPos().getY() + "," + self.getPos().getZ() + "\""
                + ",\"sensor\":" + config.enableFireControlSensor);
    }

    /** The gate passed: the next refusal is an edge again. At the call that follows the gate. */
    @Inject(method = "update", at = @At(value = "INVOKE",
            target = "Ldev/stannismod/stellurgy/tile/sensor/TileFireControlSensor;payForMode()Z"), require = 1)
    private void stellurgyTest$gatePassed(CallbackInfo ci) {
        stellurgyTest$refusing = false;
    }

    @Inject(method = "sweep", at = @At("RETURN"), require = 1)
    private void stellurgyTest$swept(CallbackInfo ci) {
        TileFireControlSensor self = (TileFireControlSensor) (Object) this;
        if (self.getWorld() == null || self.getWorld().isRemote) {
            return;
        }
        StringBuilder names = new StringBuilder();
        StringBuilder tracks = new StringBuilder();
        for (TargetTrack track : contacts) {
            if (names.length() > 0) {
                names.append(',');
                tracks.append(',');
            }
            Entity entity = self.getWorld() instanceof WorldServer && track.getEntity() != null
                    ? ((WorldServer) self.getWorld()).getEntityFromUuid(track.getEntity()) : null;
            names.append(entity == null ? "?" : entity.getName());
            tracks.append("{\"entityId\":").append(entity == null ? -1 : entity.getEntityId())
                    .append(",\"distance\":").append(track.getDistance())
                    .append(",\"quality\":").append(track.getQuality()).append('}');
        }
        TargetTrack best = self.getBestContact();
        double floor = StellurgyConfiguration.getCurrentConfig().fireControlSensorLockQualityToFire;
        TestTrace.record(self.getWorld(), "sensor_swept", "\"pos\":\"" + self.getPos().getX() + ","
                + self.getPos().getY() + "," + self.getPos().getZ() + "\""
                + ",\"contacts\":" + contacts.size()
                + ",\"names\":\"" + names + "\""
                + ",\"tracks\":[" + tracks + "]"
                + ",\"quality\":" + (best == null ? 0.0D : best.getQuality())
                + ",\"locked\":" + (best != null && best.isLocked(floor))
                + ",\"mode\":\"" + self.effectiveMode().name() + "\""
                + ",\"emitting\":" + self.isEmitting());
    }
}
