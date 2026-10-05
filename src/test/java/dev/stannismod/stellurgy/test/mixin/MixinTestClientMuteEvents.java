package dev.stannismod.stellurgy.test.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.util.SoundCategory;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.stannismod.stellurgy.client.TestClientMute;
import dev.stannismod.stellurgy.test.trace.TestTrace;

/**
 * The harness client going SILENT as a client event.
 *
 * <h2>What the event is</h2>
 *
 * <p>{@code test_client_muted} records that production's {@code TestClientMute.muteTestClientSound}
 * reached its final return — the one line where it has just written the master sound level to zero
 * and taken itself off the event bus. The payload {@code master} is the level {@code GameSettings}
 * reports at that instant, read back from the same settings object production wrote, not a copy of
 * the constant it wrote. A test that awaits this event learns two things at once: that the mute RAN,
 * and what it left behind — so a mute that was clamped or mis-applied records a non-zero
 * {@code master} instead of silence.</p>
 *
 * <h2>Which seam, on which side</h2>
 *
 * <p>TAIL of {@code muteTestClientSound(TickEvent.ClientTickEvent)}, the tick handler of the
 * one-shot listener the client proxy registers at pre-init, and only when the client carries the
 * {@code -Dforge.test.client} marker. TAIL is the method's single final return, reached only on the
 * path that muted: the method returns EARLY on a START tick and while the sound handler is not yet
 * up. After the TAIL the listener is off the bus, so this fires at most once per client session and
 * never on a manual {@code runClient}, where no listener is registered at all.</p>
 *
 * <p>The handler runs on the client thread, so the record lands in the CLIENT log through
 * {@link TestTrace#recordHere}; the instrument announces itself through
 * {@link TestTrace#instrumentHere} for the same reason.</p>
 *
 * <h2>It is recorded BEFORE any test can mark</h2>
 *
 * <p>The mute lands on one of the client's first END ticks, so this record's sequence is lower than
 * every {@code Events.mark()} a scenario will ever take. A {@code since(mark)} await therefore never
 * sees it; a reader asks for the whole ring instead — {@code ClientBot.eventsSince(0,
 * "test_client_muted")} — and the record is still in it: the ring is bounded PER TYPE and this type is
 * written at most once per client session, so nothing can evict it.</p>
 *
 * <p>Early as it is, it is not TOO early to be recorded: the client sink drops a record while its
 * {@code recording} flag is false, and that flag is set by the harness bootstrap, which production
 * starts from {@code ClientProxy.preinit()} — before the game loop, and so before the first
 * {@code ClientTickEvent} this seam can fire on.</p>
 *
 * <h2>What it is SILENT about</h2>
 *
 * <ul>
 *   <li>A client without the marker. Nothing is registered there, so "this client decided not to
 *       mute" is not an event — only the absence of {@code test_client_muted} says so, and absence is
 *       what the log's {@code recording} flag exists to qualify.</li>
 *   <li>The waiting ticks. Every END tick spent with the sound handler still null returns early and
 *       records nothing; the event does not say how long the mute took to land.</li>
 *   <li>Any later change of the level. The seam is the mute itself; a level restored afterwards by a
 *       GUI, a test or a config reload is invisible to it.</li>
 *   <li>The other sound categories. Production zeroes MASTER only, and only MASTER is read back.</li>
 * </ul>
 */
@Mixin(TestClientMute.class)
public abstract class MixinTestClientMuteEvents {

    private static final String INSTRUMENT = "test_client_mute_events";

    @Inject(method = "muteTestClientSound", at = @At("TAIL"))
    private void stellurgyTest$muted(TickEvent.ClientTickEvent event, CallbackInfo ci) {
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
}
