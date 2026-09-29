package zmaster587.advancedRocketry.test.mixin;

import java.util.HashMap;

import net.minecraftforge.fml.common.FMLCommonHandler;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import zmaster587.advancedRocketry.api.dimension.solar.StellarBody;
import zmaster587.advancedRocketry.dimension.DimensionManager;
import zmaster587.advancedRocketry.dimension.DimensionProperties;
import zmaster587.advancedRocketry.test.trace.TestTrace;

/**
 * The client's AR dimension registry GAINING a dimension and being EMPTIED, as events.
 *
 * <p>A remote login is followed by one {@code PacketDimInfo} per AR dimension, and each one the
 * client has never seen ends in {@code DimensionManager.registerDimNoUpdate} — that is where a
 * planet the server knows becomes a planet this client knows. A remote disconnect ends in
 * {@code unregisterAllDimensions} (the guarded call in {@code PlanetEventHandler.disconnected}),
 * which is the contract the disconnect test pins: leaving a remote server clears the registry. Both
 * used to be read by polling {@code getRegisteredDimensions()} through the reflective bridge — a
 * count, which could say neither WHICH dimension arrived nor whether a clear actually ran or the
 * registry had simply never filled.</p>
 *
 * <h2>The two seams</h2>
 *
 * <ul>
 * <li>{@code client_dim_registered} — {@code registerDimNoUpdate(DimensionProperties, boolean)} at
 * RETURN. The method has two exits: the early {@code false} for an id already in the map and the
 * final {@code true} after the put, so the record carries {@code registered} read off the return
 * value rather than assuming the second. {@code withForge} is the caller's flag, not whether Forge
 * actually registered the dimension (a gas giant, or an id Forge already knew, is skipped inside).</li>
 * <li>{@code client_dimensions_unregistered} — {@code unregisterAllDimensions()} at HEAD, BEFORE
 * the clear, so {@code dims} and {@code stars} are the sizes the registry held going in. A clear of an
 * already-empty registry records {@code 0,0} and is still a record: "cleared nothing" and "never
 * cleared" must stay distinguishable. {@code hasServer} names the path — {@code false} is the remote
 * disconnect at {@code PlanetEventHandler.disconnected}, {@code true} an integrated server's
 * {@code onServerStopped}.</li>
 * </ul>
 *
 * <h2>Side</h2>
 *
 * <p>The mixin is applied only in a client JVM (the {@code client} list), so neither event exists in
 * a dedicated server's log. Within the client JVM the record is routed by the calling thread's
 * effective side: a call from the client thread (the packet handler, the disconnect handler) lands in
 * the client event log; a call from an integrated server's thread — {@code loadDimensions},
 * {@code onServerStopped}, which in single-player share the very same {@code dimensionManagerClient}
 * instance — lands in that JVM's server-side log. The harness client tests connect a separate client
 * JVM to a dedicated server, so there every record is a client-thread record and {@code hasServer} is
 * {@code false}.</p>
 *
 * <h2>What it is silent about</h2>
 *
 * <p>A dimension the client already knows is REFRESHED by {@code PacketDimInfo} through
 * {@code setDimProperties} (a direct map put) and never passes {@code registerDimNoUpdate}, so a
 * re-sync of a known id produces no {@code client_dim_registered}; the event is a first sighting, not
 * a sync. Whether Forge's own registry gained or lost a dimension is not observed — only AR's map is.
 * And a client that reconnects to a second server in one session is a path this harness cannot
 * drive, so the ghost that motivated the clear is pinned by its remedy, not reproduced.</p>
 */
@Mixin(DimensionManager.class)
public abstract class MixinDimensionManagerEvents {

    private static final String INSTRUMENT = "client_dimension_registry_events";

    @Shadow private HashMap<Integer, DimensionProperties> dimensionList;
    @Shadow private HashMap<Integer, StellarBody> starList;

    @Inject(method = "registerDimNoUpdate", at = @At("RETURN"))
    private void arTest$dimRegistered(DimensionProperties properties, boolean registerWithForge,
                                      CallbackInfoReturnable<Boolean> cir) {
        TestTrace.instrumentHere(INSTRUMENT);
        if (properties == null) {
            return;
        }
        TestTrace.recordHere("client_dim_registered", "\"dim\":" + properties.getId()
                + ",\"name\":\"" + TestTrace.json(properties.getName())
                + "\",\"withForge\":" + registerWithForge
                + ",\"registered\":" + cir.getReturnValueZ());
    }

    @Inject(method = "unregisterAllDimensions", at = @At("HEAD"))
    private void arTest$dimensionsUnregistered(CallbackInfo ci) {
        TestTrace.instrumentHere(INSTRUMENT);
        boolean hasServer = FMLCommonHandler.instance().getMinecraftServerInstance() != null;
        TestTrace.recordHere("client_dimensions_unregistered",
                "\"dims\":" + (dimensionList == null ? 0 : dimensionList.size())
                        + ",\"stars\":" + (starList == null ? 0 : starList.size())
                        + ",\"hasServer\":" + hasServer);
    }
}
