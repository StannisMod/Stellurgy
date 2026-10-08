package dev.stannismod.stellurgy.test.mixin;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.stannismod.stellurgy.client.ClientBeamTracker;
import dev.stannismod.stellurgy.test.trace.ClientBeamMemory;
import dev.stannismod.stellurgy.test.trace.TestTrace;

/**
 * The client DRAWING a beam, as an event: {@code client_beam_drawn}.
 *
 * <p>The RETURN of {@code ClientBeamTracker.lit}, the one place a beam the server announced enters
 * what the renderer draws from. Recorded when a gun's beam is newly held or when the number of
 * points in its path changes — {@code points} is that number and {@code bent} whether it has a
 * corner — read off the tracker's own entry after the write, never off the packet. Client log. The
 * edge memory is {@link ClientBeamMemory}, and it forgets a gun whenever the tracker stops holding it
 * (extinguished, gone stale, or a new world's tracker built empty), so a beam that goes out and
 * relights is recorded again. Read by {@code BeamReachesClientTest} and
 * {@code ABentBeamIsDrawnBentTest}.</p>
 *
 * <p>SILENT about a heartbeat that changes nothing drawn, and about the path's points moving without
 * their count changing.</p>
 */
@Mixin(ClientBeamTracker.class)
public abstract class MixinClientBeamTrackerEvents {

    private static final String INSTRUMENT = "client_beam_events";

    @Shadow
    @Final
    private Map<Long, ClientBeamTracker.ClientBeam> beams;

    @Inject(method = "lit", at = @At("RETURN"), require = 1)
    private void stellurgyTest$drawn(long gun, List<Vec3d> path, CallbackInfo ci) {
        TestTrace.instrumentHere(INSTRUMENT);
        ClientBeamTracker.ClientBeam beam = beams.get(gun);
        if (beam == null) {
            return;
        }
        int points = beam.getPath().size();
        Integer said = ClientBeamMemory.get().drawnPoints.put(gun, points);
        if (said != null && said == points) {
            return;
        }
        BlockPos at = BlockPos.fromLong(gun);
        TestTrace.recordHere("client_beam_drawn", "\"gun\":" + gun
                + ",\"pos\":\"" + at.getX() + "," + at.getY() + "," + at.getZ() + "\""
                + ",\"points\":" + points
                + ",\"bent\":" + beam.isBent());
    }

    @Inject(method = "extinguished", at = @At("RETURN"), require = 1)
    private void stellurgyTest$extinguished(long gun, CallbackInfo ci) {
        stellurgyTest$forgetWhatIsNoLongerHeld();
    }

    @Inject(method = "tick", at = @At("RETURN"), require = 1)
    private void stellurgyTest$aged(CallbackInfo ci) {
        stellurgyTest$forgetWhatIsNoLongerHeld();
    }

    /** A new world's tracker holds nothing, so whatever the recorder said about the last one is void. */
    @Inject(method = "<init>", at = @At("RETURN"), require = 1)
    private void stellurgyTest$built(CallbackInfo ci) {
        stellurgyTest$forgetWhatIsNoLongerHeld();
    }

    private void stellurgyTest$forgetWhatIsNoLongerHeld() {
        Iterator<Long> said = ClientBeamMemory.get().drawnPoints.keySet().iterator();
        while (said.hasNext()) {
            if (!beams.containsKey(said.next())) {
                said.remove();
            }
        }
    }
}
