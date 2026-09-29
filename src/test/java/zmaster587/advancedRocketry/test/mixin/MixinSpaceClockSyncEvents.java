package zmaster587.advancedRocketry.test.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import zmaster587.advancedRocketry.space.SpaceClockSync;
import zmaster587.advancedRocketry.test.trace.TestTrace;

/**
 * The client TAKING a space-clock baseline from the server, as an event.
 *
 * <p>{@code space_clock_synced} is the production fact that {@link SpaceClockSync#accept(long)} ran:
 * the client's copy of the space clock was just re-based on the server's counter. Production sends
 * that baseline once at login and then once per {@code SpaceEventHandler.CLOCK_SYNC_TICKS} per
 * player, phase-smeared by player id, so the seam fires at an interval, never per tick — nothing
 * here needs edge-deduplication. The record is taken at the method's HEAD, before the baseline is
 * overwritten, and carries the server's value ({@code serverTick}) beside the client's own elapsed
 * counter at that instant ({@code localTicks}, the shadowed private static the class re-bases
 * against) plus {@code first} — whether the client held NO baseline when this one arrived, read off
 * {@link SpaceClockSync#hasSync()} while the old state is still in place. A chain that waits for
 * "the login sync arrived" wants {@code first:true}; a drift-bound test wants two consecutive
 * records and their {@code serverTick} delta against {@code localTicks}.</p>
 *
 * <p><b>{@code first} is not "first ever" and must not be read as one.</b> {@code SpaceClockSync.reset()}
 * clears the baseline on every disconnect ({@code ClientProxy} calls it from its own disconnect
 * handler), so a shared client that leaves one server and joins another records {@code first:true}
 * a second time. What the field says is "no baseline was in place at this instant" — which is the
 * login-sync question for one connection and says nothing across connections.</p>
 *
 * <p>Recorded on the CLIENT: the only production caller is {@code PacketSpaceClockSync.executeClient},
 * which libVulpes's channel decoder hands to the client main thread through
 * {@code LibVulpes.proxy.addScheduledTask} before {@code HandlerClient.channelRead0} runs it, so
 * {@link TestTrace#recordHere} resolves to the client log. The class itself is common code that must
 * load on a dedicated server, but nothing there ever calls {@code accept}; this mixin belongs in the
 * {@code client} list of the test mixin config, and a server-side weave would apply and never run.</p>
 *
 * <p>SILENT about: every client tick that advances the copy ({@code onClientTick} — a per-tick seam
 * that would turn the ring over in seconds and says nothing a {@code localTicks} delta does not);
 * {@code reset()} on disconnect (a separate fact, not this event); the value the clock ANSWERS
 * afterwards ({@code now()} is a pure function of what is recorded here plus elapsed ticks, and a
 * test that wants it reads it); and the SERVER's decision to send, which lives in
 * {@code SpaceEventHandler} and is not observed by this file. A record here proves a baseline
 * ARRIVED and was accepted, not that it was correct — the server's counter is the truth by
 * definition and this seam has no second source to check it against.</p>
 */
@Mixin(SpaceClockSync.class)
public abstract class MixinSpaceClockSyncEvents {

    private static final String INSTRUMENT = "space_clock_sync_events";

    /** The client's own tick counter — the number the incoming baseline is about to be pinned to. */
    @Shadow private static long localTicks;

    @Inject(method = "accept", at = @At("HEAD"))
    private static void arTest$synced(long serverTick, CallbackInfo ci) {
        TestTrace.instrumentHere(INSTRUMENT);
        // HEAD, before `baseTick` is overwritten: `hasSync()` still describes the state this baseline
        // is replacing, which is the only moment an unsynced client can be told from a re-sync.
        TestTrace.recordHere("space_clock_synced", "\"serverTick\":" + serverTick
                + ",\"localTicks\":" + localTicks
                + ",\"first\":" + !SpaceClockSync.hasSync());
    }
}
