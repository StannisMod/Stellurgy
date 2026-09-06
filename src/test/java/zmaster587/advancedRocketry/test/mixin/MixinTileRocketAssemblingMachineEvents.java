package zmaster587.advancedRocketry.test.mixin;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.relauncher.Side;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import zmaster587.advancedRocketry.integration.vs.VSIntegration;
import zmaster587.advancedRocketry.test.trace.TestTrace;
import zmaster587.advancedRocketry.tile.TileRocketAssemblingMachine;

/**
 * The rocket assembler's two facts as events: a build attempt ENDED, and a GUI command REACHED the
 * tile.
 *
 * <h2>{@code rocket_assembled}</h2>
 *
 * <p>Recorded at every return of {@code assembleRocket()} — the one method that turns a scanned
 * structure into a rocket entity or, when the build carries an Advanced Flight Computer and the
 * physics integration is present, into a tier-2 ship. "Every return" is the point: the method also
 * leaves early (a remote call, no pad cache, a scan that is not {@code SUCCESS}, a cut that threw),
 * and each of those exits is a build attempt that ENDED with a verdict the tests used to poll the
 * tile's status for. The verdict is read through the tile's own public {@code getStatus()} in the
 * instant of the return, so {@code status} is whatever production left behind on that exit:
 * {@code FINISHED} on the tier-2 path, {@code FAIL_CUT} on a cut failure — and on the ordinary
 * rocket path NOT {@code FINISHED}, because that path ends with a rescan of the now-empty pad which
 * rewrites the status before the method returns. {@code tier2} says which fork was taken: the
 * scanned flight-computer position is still set AND the integration answers available (the same
 * two conditions production forks on). Routed by the calling thread's side, and {@code remote}
 * carries the world's own flag beside it, so a misroute would be readable rather than silent —
 * but no caller reaches this method on the client today ({@code performFunction} calls it only
 * inside its own {@code !world.isRemote} branch, and the probe calls it on the server thread), so
 * in practice {@code rocket_assembled} is a server-log event and the method's own
 * {@code world.isRemote} early return is unreached.</p>
 *
 * <h2>{@code assembler_command_received}</h2>
 *
 * <p>Recorded at the HEAD of {@code useNetworkData} — the packet handler every GUI button and every
 * sync packet arrives through. {@code id} is the packet's own id (0 scan, 1 build, 2 power/progress
 * sync, 3 infrastructure link), {@code canScan} and {@code isScanning} are the tile's answers BEFORE
 * the packet acts, which is what the handler itself checks against. Runs on both sides (id 2 is the
 * client-bound sync), so each side's log carries its own copy with {@code remote}.</p>
 *
 * <p><b>The sync id is recorded as an EDGE, and that is a deliberate deviation.</b> Id 2 is not a
 * command at all: production re-sends it to every client within 32 blocks on EVERY tick that an
 * under-powered assembler is scanning or building (the send is gated on {@code progress} having
 * advanced since the last one, which it has, every tick), and a buffer refilled at exactly the draw
 * sits in that state for the whole build. Recorded unconditionally that is 20 client records a
 * second and a 256-deep ring turned over in 13 s — the interesting records evicted by the noise
 * that is only their backdrop. So an id-2 packet is recorded only when {@code canScan} or
 * {@code isScanning} DIFFERS from the last sync this tile recorded, which keeps exactly the
 * transitions (a build starting, a build ending) and drops the identical repeats; ids 0, 1 and 3
 * are discrete player actions and are always recorded, and each of them clears the sync memory so
 * the next burst re-announces itself. What is lost is the COUNT of sync packets — nothing here can
 * be used to measure packet rate.</p>
 *
 * <h2>Silent about</h2>
 *
 * <p>Both subclasses — {@code TileStationAssembler} and {@code TileUnmannedVehicleAssembler} —
 * override {@code assembleRocket()} WITHOUT calling super, so {@code rocket_assembled} never fires
 * for a station build or an unmanned build; only the rocket assembler's own method is observed.
 * {@code assembler_command_received}, by contrast, fires for ALL THREE: the station assembler
 * overrides {@code useNetworkData} and delegates to super, and the unmanned assembler does not
 * override it at all — so a record's {@code pos} is the only thing that says which assembler it
 * came from, and the event alone cannot be read as "a ROCKET assembler was commanded".
 * The scan itself ({@code scanRocket}) is not observed — the assembly's verdict is read at the
 * return, not the scan's — and nothing here says WHICH entity or ship the build produced; the
 * registry's {@code ship_spawned} and the server bus's {@code entity_joined_world} say that.
 * Neither event distinguishes a build the player commanded from one the probe drove: both arrive
 * through the same two methods.</p>
 */
@Mixin(TileRocketAssemblingMachine.class)
public abstract class MixinTileRocketAssemblingMachineEvents {

    private static final String INSTRUMENT = "rocket_assembler_events";

    /** The tier-2 routing state production forks on; reset by every scan, set when a computer is found. */
    @Shadow private BlockPos scannedFlightComputerPos;

    /**
     * The {@code (canScan, isScanning)} pair as of the last PERIODIC sync (id 2) this assembler
     * recorded, or null when the last thing it recorded was a discrete command. An instance field,
     * one per assembler, so two machines in one world do not silence each other.
     */
    private String arTest$lastSyncKey;

    // RETURN, not TAIL, and deliberately: the method leaves from six places and each of them is an
    // attempt that ended. The early exits carry shorter local frames, which is what makes a RETURN
    // injection fragile — but this handler takes no target locals and no target parameters
    // (assembleRocket() has none), so there is nothing whose scope could differ between exits. The
    // state it reads is the tile's own, through public getters and a shadowed field.
    @Inject(method = "assembleRocket", at = @At("RETURN"))
    private void arTest$assembled(CallbackInfo ci) {
        TestTrace.instrumentHere(INSTRUMENT);
        TileRocketAssemblingMachine self = (TileRocketAssemblingMachine) (Object) this;
        // The status enum is a PROTECTED nested type of the target, so it cannot be named (nor
        // shadowed by its type) from this package; the tile's public getter hands it out and an
        // enum's name is readable through Object.
        Object status = self.getStatus();
        boolean tier2 = scannedFlightComputerPos != null && VSIntegration.isAvailable();
        TestTrace.recordHere("rocket_assembled", "\"pos\":\"" + arTest$xyz(self)
                + "\",\"status\":\"" + (status == null ? "null" : ((Enum<?>) status).name())
                + "\",\"tier2\":" + tier2
                + ",\"remote\":" + (self.getWorld() != null && self.getWorld().isRemote));
    }

    @Inject(method = "useNetworkData", at = @At("HEAD"))
    private void arTest$commandReceived(EntityPlayer player, Side side, byte id, NBTTagCompound nbt,
                                        CallbackInfo ci) {
        TestTrace.instrumentHere(INSTRUMENT);
        TileRocketAssemblingMachine self = (TileRocketAssemblingMachine) (Object) this;
        boolean canScan = self.canScan();
        boolean isScanning = self.isScanning();
        if (id == 2) {
            // The power/progress sync, re-sent to every nearby client on every tick of an
            // under-powered build. Only its TRANSITIONS carry anything the identical repeats do
            // not; see the class javadoc for what this drops.
            String key = canScan + "|" + isScanning;
            if (key.equals(arTest$lastSyncKey)) {
                return;
            }
            arTest$lastSyncKey = key;
        } else {
            arTest$lastSyncKey = null; // a real command; let the next sync burst announce itself
        }
        TestTrace.recordHere("assembler_command_received", "\"pos\":\"" + arTest$xyz(self)
                + "\",\"id\":" + id
                + ",\"who\":\"" + (player == null ? "null" : TestTrace.json(player.getName()))
                + "\",\"canScan\":" + canScan
                + ",\"isScanning\":" + isScanning
                + ",\"remote\":" + (self.getWorld() != null && self.getWorld().isRemote));
    }

    private static String arTest$xyz(TileRocketAssemblingMachine tile) {
        BlockPos pos = tile.getPos();
        if (pos == null) {
            return "null";
        }
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
