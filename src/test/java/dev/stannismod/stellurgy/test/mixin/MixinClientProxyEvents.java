package dev.stannismod.stellurgy.test.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.stannismod.stellurgy.client.ClientProxy;
import dev.stannismod.stellurgy.test.trace.TestTrace;

/**
 * The client proxy's lifecycle seams as client events. The test client's mute, which used to be a
 * static tick handler here, is a listener of its own and is recorded by
 * {@link MixinTestClientMuteEvents}.
 */
@Mixin(ClientProxy.class)
public abstract class MixinClientProxyEvents {

    private static final String INSTRUMENT = "client_proxy_events";

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
