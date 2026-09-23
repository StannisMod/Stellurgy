package zmaster587.advancedRocketry.test.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import zmaster587.advancedRocketry.space.SpaceSubsystem;
import zmaster587.advancedRocketry.test.trace.TestTrace;

/**
 * A SAVE POINT WALKED INTO THE ARMED FAULT — the moment the one failure the save handler undertakes
 * to survive actually happens.
 *
 * <h2>Why a record and not the flag</h2>
 *
 * <p>The fault is a one-shot: {@code armSaveFaultOnce} sets a flag and the first save point to reach
 * it disarms itself and throws. The scenario that needs this waits for the WORLD autosave — not a
 * save a command asked for, because a command's save runs on the calling path and cannot take the
 * server down with it — and it waited by asking the subsystem, once every few ticks, whether the
 * flag was still armed. That is a level-triggered reading of an edge: the flag is false before the
 * arming and false after the firing, so a reading is only meaningful relative to the arming that
 * preceded it, and nothing in the reading says which side of it the reader is on.</p>
 *
 * <p>This is the edge itself, written from inside the throw's own branch, so it says the fault FIRED
 * rather than that it is no longer armed.</p>
 *
 * <h2>Side and silence</h2>
 *
 * <p>The save point runs on the server thread; the record is routed by the calling thread, so a fire
 * on any other would say so by where it lands. SILENT about: the arming (the probe's reply carries
 * that, and the scenario asserts on it), what the save handler then DOES with the throw — the
 * catch, the log line, the untouched snapshot, which are the subject of the scenario's own
 * assertions — and about an ordinary save point, which reaches this method and does nothing.</p>
 */
@Mixin(SpaceSubsystem.class)
public abstract class MixinSpaceSubsystemSaveFaultEvents {

    private static final String INSTRUMENT = "space_save_fault_events";

    @Inject(method = "failSavePointIfArmed", at = @At("HEAD"))
    private static void arTest$saveFaultReached(CallbackInfo ci) {
        TestTrace.instrumentHere(INSTRUMENT);
        if (!SpaceSubsystem.isSaveFaultArmed()) {
            return; // an ordinary save point passes through here and throws nothing
        }
        TestTrace.recordServer("save_fault_fired", "\"armed\":true");
    }
}
