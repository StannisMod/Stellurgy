package zmaster587.advancedRocketry.test.mixin;

import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.network.FMLNetworkEvent.ClientDisconnectionFromServerEvent;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import zmaster587.advancedRocketry.dimension.DimensionManager;
import zmaster587.advancedRocketry.event.PlanetEventHandler;
import zmaster587.advancedRocketry.test.trace.TestTrace;

/**
 * The client LEFT a server, as an event — taken at the head of the mod's own disconnect handler,
 * before it decides what to forget.
 *
 * <p>{@code client_disconnected} is the production fact that Forge's
 * {@code ClientDisconnectionFromServerEvent} reached {@code PlanetEventHandler.disconnected}: the
 * handler that reloads the on-disk config and, when the server it is leaving was REMOTE, clears the
 * client's dimension registry so the previous server's planets do not bleed into the next one. The
 * record carries the two things the handler is about to act on: {@code remote} — the very question
 * production asks ({@code getMinecraftServerInstance() == null}) — and {@code dimsBefore}, how many
 * AR dimensions the client's registry held at that instant, read from the same registry the handler
 * is about to clear. Relog tests used to poll a dimension count for a DROP to learn that a
 * disconnect had been processed; a count cannot say whether the drop came from this handler or from
 * the next server's sync, nor whether a clear was even due ({@code remote=false} means it was not).</p>
 *
 * <p>{@code dimsBefore} is a COUNT when it could be taken and a named refusal when it could not:
 * {@code -1} means the manager singleton was not up, {@code -2} that the registry threw while being
 * read. Neither is a dimension count and neither may be compared against one — the registry is a
 * plain {@code HashMap} owned by the game thread and this seam runs on another, so a snapshot taken
 * mid-mutation can fail. It is caught here because an instrument that throws would take production's
 * disconnect (the config reload and the clear) down with it.</p>
 *
 * <p>Recorded on the CLIENT log by {@link TestTrace#recordHere}: Forge posts this event from the
 * netty channel's outbound {@code disconnect} / {@code close} handlers
 * ({@code NetworkDispatcher:487,502}, read on disk), so the call arrives on that channel's event
 * loop — a netty client IO thread for a remote server and the local-channel client thread for an
 * integrated one, never {@code ThreadMinecraftServer}, which is what keeps the router's answer
 * CLIENT on both, and the client sink is locked, so the record lands in the
 * right log — but its tick stamp is whatever the client tick counter reads at that moment, and its
 * order against game-thread records of the same tick is not defined. The mixin sits in the common
 * list because the handler class is common; the seam itself only ever fires on the client, so the
 * server log never carries this type.</p>
 *
 * <p>SILENT about: whether the clear then happened and what it removed (that is
 * {@code client_dimensions_unregistered}, recorded at the registry's own seam); the config reload;
 * the other subscriber to the same Forge event ({@code ClientProxy.forgetServerState}), whose order
 * against this handler is the bus's and not measured here; and which server was left. Forge fires
 * the underlying event from two netty callbacks, so one disconnect MAY produce two records of this
 * type — a chain awaits the first and never counts them.</p>
 */
@Mixin(PlanetEventHandler.class)
public abstract class MixinPlanetEventHandlerEvents {

    private static final String INSTRUMENT = "client_disconnect_events";

    @Inject(method = "disconnected", at = @At("HEAD"))
    private void arTest$disconnected(ClientDisconnectionFromServerEvent event, CallbackInfo ci) {
        TestTrace.instrumentHere(INSTRUMENT);
        // The same question production asks one line later, asked before it acts on the answer.
        boolean remote = FMLCommonHandler.instance().getMinecraftServerInstance() == null;
        DimensionManager manager = DimensionManager.getInstance();
        int dimsBefore;
        if (manager == null) {
            dimsBefore = -1;
        } else {
            try {
                dimsBefore = manager.getRegisteredDimensions().length;
            } catch (RuntimeException failed) {
                // `dimensionList` is a plain HashMap owned by the game thread and this handler runs
                // on a netty thread: a snapshot taken while the game thread is mutating it can throw.
                // An instrument may not break the disconnect it is watching, and it may not answer
                // with a number it did not read either — so the refusal gets its own value.
                dimsBefore = -2;
            }
        }
        TestTrace.recordHere("client_disconnected", "\"remote\":" + remote
                + ",\"dimsBefore\":" + dimsBefore);
    }
}
