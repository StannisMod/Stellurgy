package zmaster587.advancedRocketry.test.mixin;

import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import zmaster587.advancedRocketry.test.trace.TestTrace;

/**
 * The server KICKING a player, with the reason it gave — recorded at the one method every
 * server-initiated disconnect on the play connection goes through.
 *
 * <h2>Why this exists</h2>
 *
 * <p>A client that vanishes mid-scenario leaves two stories that look identical from the test's
 * side: the SERVER threw him out, or the client went away by itself (a crash, a netty exception, a
 * timeout at the other end). Nothing in this tree could tell them apart. AR's own
 * {@code client_disconnected} is recorded on the CLIENT, off
 * {@code ClientDisconnectionFromServerEvent}, which carries no reason at all — and its
 * {@code remote} field answers "is there no integrated server", which in a two-JVM harness is
 * always true and says nothing about who closed the connection.</p>
 *
 * <p>{@code NetHandlerPlayServer.disconnect(ITextComponent)} is where the answer lives. Vanilla
 * reaches it for every kick it decides on — the flying check, the idle timeout, the two
 * "invalid movement" guards (both of which fire on a NON-FINITE coordinate, never on a large one)
 * — and so does any mod that kicks. The reason component is the discriminator: a scenario that
 * moved a craft a very long way wants to know whether the player was thrown out for FLYING, for a
 * NaN in his own position, or not thrown out at all.</p>
 *
 * <h2>What it records</h2>
 *
 * <p>{@code server_kicked_player}, on the SERVER log: {@code who} (the player's name),
 * {@code key} (the translation key when the reason IS a {@link TextComponentTranslation} — which is
 * what every vanilla kick uses, so this is the field to match on), and {@code text} (the
 * component's unformatted text, for a reason that is a literal).</p>
 *
 * <h2>What this mixin is silent about</h2>
 *
 * <p>It sees the server's DECISION, not the connection's fate: a client that dropped on its own, a
 * channel closed by a netty exception, and a timeout detected at the client end never enter this
 * method. That silence is exactly what makes the record useful — this type ABSENT while a player
 * is gone says the server did not throw him out. It also does not see a disconnect during login
 * ({@code NetHandlerLoginServer} is a different handler) nor the reason ever reaching the client.</p>
 */
@Mixin(NetHandlerPlayServer.class)
public abstract class MixinServerDisconnectEvents {

    private static final String INSTRUMENT = "server_disconnect_events";

    @Inject(method = "disconnect", at = @At("HEAD"))
    private void arTest$kicked(ITextComponent reason, CallbackInfo ci) {
        TestTrace.instrumentHere(INSTRUMENT);
        NetHandlerPlayServer self = (NetHandlerPlayServer) (Object) this;
        String who = self.player == null ? "" : self.player.getName();
        String key = reason instanceof TextComponentTranslation
                ? ((TextComponentTranslation) reason).getKey() : "";
        String text = reason == null ? "" : reason.getUnformattedText();
        TestTrace.recordServer("server_kicked_player",
                "\"who\":\"" + TestTrace.json(who)
                        + "\",\"key\":\"" + TestTrace.json(key)
                        + "\",\"text\":\"" + TestTrace.json(text) + "\"");
    }
}
