package dev.stannismod.stellurgy.test.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.util.SoundCategory;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.stannismod.stellurgy.client.ClientProxy;
import dev.stannismod.stellurgy.test.trace.TestTrace;

/**
 * The harness client going SILENT as a client event.
 *
 * <h2>What the event is</h2>
 *
 * <p>{@code test_client_muted} records that production's {@code ClientProxy.muteTestClientSound}
 * reached its final return — the one line where it has just written the master sound level to zero
 * and latched itself off. The payload {@code master} is the level {@code GameSettings} reports at
 * that instant, read back from the same settings object production wrote, not a copy of the constant
 * it wrote. A test that awaits this event learns two things at once: that the mute RAN, and what it
 * left behind — so a mute that was clamped or mis-applied records a non-zero {@code master} instead
 * of silence.</p>
 *
 * <h2>Which seam, on which side</h2>
 *
 * <p>TAIL of {@code muteTestClientSound(TickEvent.ClientTickEvent)}, a public static Forge tick
 * handler on the client proxy. TAIL is the method's single final return, and that return is reached
 * only on the path that actually muted: the method returns EARLY when the work is already done, when
 * the {@code -Dforge.test.client} marker is absent (a human playtest — it latches without muting),
 * and when the sound handler is not yet up. None of those early exits is a TAIL, so this fires at
 * most once per client session and never on a manual {@code runClient}.</p>
 *
 * <p>The handler runs on the client thread, so the record lands in the CLIENT log through
 * {@link TestTrace#recordHere}; the instrument announces itself through
 * {@link TestTrace#instrumentHere} for the same reason.</p>
 *
 * <p>Sits beside {@code MixinClientProxyDiag}, which fills {@code ClientDiag.testClientMasterVolume}
 * from the same seam for the readback poll in {@code ClientBootBaselineGroupE2ETest}; that mixin is
 * left in place until its reader moves onto this event.</p>
 *
 * <h2>It is recorded BEFORE any test can mark</h2>
 *
 * <p>The mute lands on one of the client's first END ticks, so this record's sequence is lower than
 * every {@code Events.mark()} a scenario will ever take. A {@code since(mark)} await therefore never
 * sees it, no matter how long it waits, and reading a silence there as "the client was not muted"
 * would be wrong. A reader asks for the whole ring instead —
 * {@code ClientBot.eventsSince(0, "test_client_muted")} — and the record is still in it: the ring is bounded PER TYPE and this type is written at most once per client session,
 * so nothing can evict it. The instrument {@code client_proxy_events} answers the same question more
 * cheaply when only "did the seam run" is wanted.</p>
 *
 * <p>Early as it is, it is not TOO early to be recorded: the client sink drops a record while its
 * {@code recording} flag is false, and that flag is set by the harness bootstrap, which production
 * starts from {@code ClientProxy.preinit()} — before the game loop, and so before the first
 * {@code ClientTickEvent} this seam can fire on.</p>
 *
 * <h2>What it is SILENT about</h2>
 *
 * <ul>
 *   <li>The non-harness latch. A client without the marker sets the latch and returns early on its
 *       first END tick; that decision never reaches TAIL, so "this client decided not to mute" is
 *       not an event here — only the absence of {@code test_client_muted} says so, and absence is
 *       what the log's {@code recording} flag exists to qualify.</li>
 *   <li>The waiting ticks. Every END tick spent with the sound handler still null returns early and
 *       records nothing; the event does not say how long the mute took to land.</li>
 *   <li>Any later change of the level. The seam is the mute itself; a level restored afterwards by a
 *       GUI, a test or a config reload is invisible to it.</li>
 *   <li>The other sound categories. Production zeroes MASTER only, and only MASTER is read back.</li>
 * </ul>
 */
@Mixin(ClientProxy.class)
public abstract class MixinClientProxyEvents {

    private static final String INSTRUMENT = "client_proxy_events";

    @Inject(method = "muteTestClientSound", at = @At("TAIL"))
    private static void stellurgyTest$muted(TickEvent.ClientTickEvent event, CallbackInfo ci) {
        TestTrace.instrumentHere(INSTRUMENT);
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.gameSettings == null) {
            // Production could not have muted without settings, so this branch is unreachable
            // at TAIL; kept as a refusal rather than a guess at a level that was never read.
            return;
        }
        TestTrace.recordHere("test_client_muted", "\"master\":"
                + TestTrace.fmt(mc.gameSettings.getSoundLevel(SoundCategory.MASTER)));
    }

    /**
     * The client LEFT a server, as an event — taken at the head of {@code ClientProxy.release}, where
     * the client drops its whole view of that server, before it withdraws anything.
     *
     * <p>{@code client_disconnected} carries the two things the release is about to act on:
     * {@code remote} — the very question production asks before withdrawing the dimension
     * registrations the server's planets made here — and {@code dimsBefore}, how many Stellurgy
     * dimensions that server's galaxy held at that instant. Relog tests used to poll a dimension count
     * for a DROP to learn that a disconnect had been processed; a count cannot say whether the drop
     * came from leaving or from the next server's sync, nor whether a clear was even due
     * ({@code remote=false} means it was not).</p>
     *
     * <p>The release runs on the game thread — when the client unloads the last world of a server
     * whose connection is closed, or when a new connection finds an old view that no world released —
     * so the count is read on the thread that owns the registry, and the record lands in the CLIENT
     * log through {@link TestTrace#recordHere}. One record per connection that ended.</p>
     *
     * <p>SILENT about: whether the clear then happened and what it removed (that is
     * {@code client_dimensions_unregistered}, recorded at the registry's own seam); the moment the
     * channel closed, which is earlier and on the network thread; and which server was left.</p>
     */
    @Inject(method = "release", at = @At("HEAD"))
    private void stellurgyTest$released(dev.stannismod.stellurgy.client.ServerView view, CallbackInfo ci) {
        TestTrace.instrumentHere(INSTRUMENT);
        TestTrace.recordHere("client_disconnected", "\"remote\":" + view.remote()
                + ",\"dimsBefore\":" + view.dimensions.getRegisteredDimensions().length);
    }
}
